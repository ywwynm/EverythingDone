package com.ywwynm.everythingdone.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.view.WindowCallbackWrapper
import androidx.appcompat.widget.Toolbar
import android.view.animation.PathInterpolator
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.github.chrisbanes.photoview.PhotoView
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.activities.ImageViewerActivity
import com.ywwynm.everythingdone.interaction.UiInteractions
import com.ywwynm.everythingdone.helpers.MediaPreviewThumbnail
import com.ywwynm.everythingdone.views.particledismiss.ParticleDismissController
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import kotlin.math.max
import kotlin.math.roundToInt

/** 详情画面保留到此次查看器关闭；返回来源使用弱引用，每次重新读取附件位置。 */
internal class MediaPreviewTransition private constructor(
    private val viewer: Activity,
    private val origin: WeakReference<DetailActivity>
) {
    private var animation: Presentation? = null
    // 交互返回在原窗口恢复前需要详情背景；复用打开时的单张画面，不冻结当前媒体。
    private var detailFrame: Frame? = null
    private var backgroundGeneration = 0
    var returning = false
        private set
    val isRunning get() = animation?.running == true

    fun cancelEnter() { animation?.close("cancelled"); animation = null }

    fun dismissBackground(): Bitmap? = detailFrame?.takeIf {
        it.width == viewer.window.decorView.width && it.height == viewer.window.decorView.height
    }?.pixels

    fun refreshDismissBackground() {
        val token = ++backgroundGeneration
        detailFrame = null
        val detail = origin.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        val root = detail.window.decorView
        val destination = viewer.window.decorView
        if (root.width != destination.width || root.height != destination.height) return
        capture(root, null, bars(detail)) { frame ->
            if (backgroundGeneration == token && !viewer.isDestroyed && !returning) detailFrame = frame
        }
    }

    fun close() { cancelEnter(); backgroundGeneration++; detailFrame = null }

    fun returnInteractively(photo: PhotoView?, key: String?, gesture: MediaPreviewDismissGesture?,
                            freezeMedia: () -> Unit, finish: () -> Unit): Boolean {
        if (returning) return true
        if (gesture?.isActive != true || photo == null || !ValueAnimator.areAnimatorsEnabled()) return false
        val detail = origin.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return false
        val background = detailFrame?.takeIf { it.pixels === dismissBackground() } ?: return false
        val destination = detail.window.decorView as ViewGroup
        if (destination.width != background.width || destination.height != background.height) return false
        // 详情仍在后台，getGlobalVisibleRect 会因为窗口不可见返回 false；读取保留的布局与祖先裁剪。
        val sourceView = detail.findMediaPreviewSource(key) ?: return false
        val target = laidOutDetailRect(sourceView, destination) ?: return false
        val targetMedia = sourceView.findViewById<ImageView>(R.id.iv_image_attachment) ?: return false
        val travel = imageTravel(photo, detail, key, false) ?: return false
        val source = photoRect(photo, viewer.window.decorView) ?: return false
        returning = true
        mark("return-interactive-start")
        if (BuildConfig.DEBUG) {
            diagnosticGeometry?.invoke(false, source, target)
            diagnosticImageGeometry?.invoke(false, travel.width, travel.height, travel.from, travel.to)
        }
        mark("return-prepared")
        val started = gesture.animateReturn(target, travel.to, targetMedia, ThingDetailTransition.bounds(targetMedia, destination)) {
            holdDetailEndpoint(detail, background, key)
            mark("return-finished")
            finish()
            viewer.overridePendingTransition(0, 0)
        }
        if (!started) { returning = false; return false }
        freezeMedia()
        return true
    }

    /** 只在运动结束后等待真实详情恢复；遮盖端点，避免旧窗口撤去时出现空图或布局跳变。 */
    private fun holdDetailEndpoint(detail: DetailActivity, frame: Frame, key: String?) {
        val host = detail.window.decorView as ViewGroup
        val layer = StillLayer(detail, frame)
        val lease = UiInteractions.restore(detail, "media-preview-return-endpoint")
        var closed = false
        var stable = 0
        var fadeStarted = false
        lateinit var timeout: Runnable
        lateinit var listener: android.view.ViewTreeObserver.OnPreDrawListener
        fun close() {
            if (closed) return
            closed = true
            host.removeCallbacks(timeout)
            host.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
            layer.animate().cancel()
            host.removeView(layer)
            lease.release()
            detail.setMediaPreviewTransitionPaused(false)
            mark("return-endpoint-released")
        }
        timeout = Runnable { close() }
        listener = android.view.ViewTreeObserver.OnPreDrawListener {
            if (!closed && !fadeStarted) {
                val ready = detail.hasWindowFocus() && detail.isMediaPreviewLayoutReady(key)
                stable = if (ready) stable + 1 else 0
                if (stable >= 2) {
                    fadeStarted = true
                    layer.animate().alpha(0f).setDuration(96L).withEndAction { close() }.start()
                }
                host.postOnAnimation { host.invalidate() }
            }
            true
        }
        layer.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { close() }
        })
        detail.setMediaPreviewTransitionPaused(true)
        host.addView(layer, ViewGroup.LayoutParams(-1, -1))
        host.viewTreeObserver.addOnPreDrawListener(listener)
        host.postDelayed(timeout, 1500)
    }

    fun returnToDetail(photo: PhotoView?, key: String?, finish: () -> Unit): Boolean {
        if (returning) return true
        val detail = origin.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return false
        if (!ValueAnimator.areAnimatorsEnabled()) return false
        returning = true
        val captureLease = UiInteractions.restore(viewer, "media-preview-return-capture")
        // 快速返回保留已显示的几何，不先跳至完整预览。
        val entering = animation?.takeIf { it.running }
        entering?.freeze()
        val root = viewer.window.decorView as ViewGroup
        val rect = entering?.currentRect() ?: photoRect(photo, root)
        val movingImage = if (entering == null) imageTravel(photo, detail, key, false) else null
        mark("return-capture")
        capture(root, rect, bars(viewer)) { frame ->
            captureLease.release()
            if (viewer.isDestroyed || viewer.isFinishing) { entering?.close("viewer-gone"); return@capture }
            if (frame == null || detail.isDestroyed || detail.isFinishing) {
                entering?.close("capture-unavailable")
                finish()
                return@capture
            }
            // onPause 会停止视频；先留住已捕获的当前帧，直到旧窗口真正被移除。
            val retained = StillLayer(viewer, frame)
            root.addView(retained, ViewGroup.LayoutParams(-1, -1))
            retained.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    retained.removeOnAttachStateChangeListener(this)
                    main.post { root.removeView(retained) }
                }
            })
            entering?.close("returning")
            animation = null
            Presentation(detail, frame, entering = false,
                destination = { detail.findMediaPreviewSource(key) },
                settled = { detail.isMediaPreviewLayoutReady(key) },
                travelProvider = { movingImage?.let { image ->
                    detail.mediaPreviewImageBounds(key, image.width, image.height)?.let {
                        image.copy(to = it)
                    }
                } },
                completed = { detail.setMediaPreviewTransitionPaused(false) })
                .also {
                    detail.setMediaPreviewTransitionPaused(true)
                    it.start()
                }
            finish()
            viewer.overridePendingTransition(0, 0)
        }
        return true
    }

    private class Frame(val pixels: Bitmap, val rect: RectF?, val lightBars: Boolean) {
        val width get() = pixels.width
        val height get() = pixels.height
    }

    private data class Travel(val width: Int, val height: Int, val from: RectF, val to: RectF)

    private class StillLayer(activity: Activity, private val frame: Frame) : View(activity) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(canvas: Canvas) {
            canvas.drawBitmap(frame.pixels, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
        }
    }

    /** 源窗口画面在第一帧原样显示，目标窗口稳定并取得焦点后才开始运动。 */
    private class Presentation(
        private val activity: Activity,
        private val source: Frame,
        private val entering: Boolean,
        private val destination: () -> View?,
        private val settled: () -> Boolean = { true },
        private val travelProvider: () -> Travel? = { null },
        private val completed: () -> Unit
    ) {
        private val host = activity.window.decorView as ViewGroup
        private val content = host.findViewById<View>(android.R.id.content)
        private val lease = UiInteractions.restore(activity, "media-preview-transition")
        private val systemBars = WindowInsetsControllerCompat(activity.window, host)
        private val destinationBars = systemBars.isAppearanceLightStatusBars
        private val previousCallback = activity.window.callback
        private val backInput = object : WindowCallbackWrapper(previousCallback) {
            private var pressed: ImageButton? = null
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (!entering || !running || frozen) return super.dispatchTouchEvent(event)
                val toolbar = activity.findViewById<Toolbar>(R.id.actionbar)
                val back = toolbar?.let { bar -> (0 until bar.childCount).map { bar.getChildAt(it) }
                    .filterIsInstance<ImageButton>().firstOrNull() }
                val bounds = Rect()
                val inside = back?.getGlobalVisibleRect(bounds) == true && bounds.contains(event.rawX.toInt(), event.rawY.toInt())
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> pressed = back?.takeIf { inside }
                    MotionEvent.ACTION_MOVE -> if (!inside) pressed = null
                    MotionEvent.ACTION_CANCEL -> pressed = null
                    MotionEvent.ACTION_UP -> if (pressed != null) {
                        val clicked = pressed; pressed = null
                        if (inside) clicked?.performClick()
                        return true
                    }
                }
                return pressed != null || super.dispatchTouchEvent(event)
            }
        }
        private val layer = Layer()
        private var target: Frame? = null
        private var travel: Travel? = null
        private var animator: ValueAnimator? = null
        private var stableFrames = 0
        private var frozen = false
        private var progress = 0f
        private var destinationPrepared = false
        var running = true
            private set
        private val timeout = Runnable { close("timeout") }
        private val lifecycle = (activity as LifecycleOwner).lifecycle
        private val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) close("background")
        }
        private val resize = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if ((entering || destinationPrepared) && or > ol && ob > ot &&
                (r - l != or - ol || b - t != ob - ot)) close("resized")
        }

        fun start() {
            host.addView(layer, ViewGroup.LayoutParams(-1, -1))
            host.addOnLayoutChangeListener(resize)
            if (entering) activity.window.callback = backInput
            lifecycle.addObserver(observer)
            systemBars.isAppearanceLightStatusBars = source.lightBars
            host.postDelayed(timeout, 1500)
            mark(if (entering) "enter-attached" else "return-attached")
            awaitLayout()
        }

        private fun awaitLayout() {
            host.doOnPreDraw {
                if (!running || frozen) return@doOnPreDraw
                val sameSize = host.width == source.width && host.height == source.height
                if (entering && !sameSize) { close("window-mismatch"); return@doOnPreDraw }
                val view = destination()
                // 大图的最终解码可能尚未结束，先等完整构图的小预览及 PhotoView 矩阵就绪。
                // 不因短暂解码等待而把放大退化成空画面淡入；总准备时间仍受 timeout 约束。
                val drawableReady = !entering || photoRect(view as? PhotoView, host) != null
                val ready = activity.hasWindowFocus() && sameSize && !content.isLayoutRequested && settled() && drawableReady
                stableFrames = if (ready) stableFrames + 1 else 0
                if (stableFrames < 2) {
                    host.postOnAnimation { if (running && !frozen) { awaitLayout(); host.invalidate() } }
                    return@doOnPreDraw
                }
                val rect = if (entering) photoRect(view as? PhotoView, host) else visibleRect(view, host)
                mark(if (entering) "enter-layout-ready" else "return-layout-ready")
                val contentRect = ThingDetailTransition.bounds(content, host)
                val detail = activity as? DetailActivity
                val toolbarGeneration = detail?.mediaPreviewToolbarGeneration()
                // 内容根不含 Decor 上的承接层，捕获的是既有布局处理后的真实页面。
                capture(content, rect?.apply { offset(-contentRect.left, -contentRect.top) }, destinationBars) { result ->
                    if (!running || frozen) return@capture
                    if (!entering && (!settled() || toolbarGeneration != detail?.mediaPreviewToolbarGeneration())) {
                        stableFrames = 0
                        awaitLayout()
                        host.invalidate()
                        return@capture
                    }
                    if (result == null) {
                        close("destination-unavailable"); return@capture
                    }
                    target = if (result.width == host.width && result.height == host.height &&
                        contentRect.left == 0f && contentRect.top == 0f) result else {
                        val pixels = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
                        Canvas(pixels).apply {
                            drawColor(Color.BLACK)
                            drawBitmap(result.pixels, contentRect.left, contentRect.top, null)
                        }
                        Frame(pixels, result.rect?.let(::RectF)?.apply {
                            offset(contentRect.left, contentRect.top)
                        }, result.lightBars)
                    }
                    destinationPrepared = true
                    mark(if (entering) "enter-captured" else "return-captured")
                    travel = travelProvider()
                    if (BuildConfig.DEBUG) travel?.let {
                        diagnosticImageGeometry?.invoke(entering, it.width, it.height, it.from, it.to)
                    }
                    host.removeCallbacks(timeout)
                    mark(if (entering) "enter-prepared" else if (rect == null) "return-fade" else "return-prepared")
                    if (BuildConfig.DEBUG) diagnosticGeometry?.invoke(entering, source.rect, target?.rect)
                    animator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = if (rect == null) 180 else if (entering) 360 else 300
                        interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
                        addUpdateListener {
                            progress = max(progress, it.animatedValue as Float)
                            systemBars.isAppearanceLightStatusBars = if (progress > .5f) destinationBars else source.lightBars
                            layer.invalidate()
                            mark("${if (entering) "enter" else "return"}-progress:$progress")
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) { close("finished") }
                        })
                        start()
                    }
                }
            }
        }

        fun freeze() {
            frozen = true
            host.removeCallbacks(timeout)
            animator?.removeAllListeners(); animator?.cancel(); animator = null
        }

        fun currentRect(): RectF? {
            val a = source.rect ?: return null
            val b = target?.rect ?: return RectF(a)
            return mix(a, b, progress)
        }

        fun close(reason: String) {
            if (!running) return
            running = false
            host.removeCallbacks(timeout)
            animator?.removeAllListeners(); animator?.cancel(); animator = null
            host.removeOnLayoutChangeListener(resize)
            lifecycle.removeObserver(observer)
            if (activity.window.callback === backInput) activity.window.callback = previousCallback
            systemBars.isAppearanceLightStatusBars = destinationBars
            layer.visibility = View.GONE
            main.post { host.removeView(layer); target = null; travel = null }
            lease.release()
            completed()
            mark("${if (entering) "enter" else "return"}-$reason")
        }

        private inner class Layer : View(activity) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            private val crop = Rect()
            private val screen = RectF()
            private fun drawFrame(canvas: Canvas, frame: Frame, alpha: Float, omitMedia: Boolean = false) {
                val save = canvas.save()
                if (omitMedia) frame.rect?.let(canvas::clipOutRect)
                paint.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
                canvas.drawBitmap(frame.pixels, null, screen, paint)
                canvas.restoreToCount(save)
            }
            private fun drawMedia(canvas: Canvas, frame: Frame, rect: RectF, alpha: Float) {
                val area = frame.rect ?: return
                area.round(crop)
                if (crop.isEmpty) return
                val save = canvas.save()
                canvas.clipRect(rect)
                val scale = max(rect.width() / crop.width(), rect.height() / crop.height())
                val fitted = RectF(rect.centerX() - crop.width() * scale / 2,
                    rect.centerY() - crop.height() * scale / 2,
                    rect.centerX() + crop.width() * scale / 2,
                    rect.centerY() + crop.height() * scale / 2)
                paint.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
                canvas.drawBitmap(frame.pixels, crop, fitted, paint)
                canvas.restoreToCount(save)
            }
            private fun drawAlignedMedia(canvas: Canvas, frame: Frame, imageAtEndpoint: RectF,
                                         movingImage: RectF, clip: RectF, alpha: Float) {
                val area = frame.rect ?: return
                if (alpha <= 0f || imageAtEndpoint.isEmpty) return
                area.round(crop)
                // 只允许等比缩放；裁剪区域变化不能改变图像纵横比。
                val scale = movingImage.width() / imageAtEndpoint.width()
                val mapped = RectF(
                    movingImage.left + (area.left - imageAtEndpoint.left) * scale,
                    movingImage.top + (area.top - imageAtEndpoint.top) * scale,
                    movingImage.left + (area.right - imageAtEndpoint.left) * scale,
                    movingImage.top + (area.bottom - imageAtEndpoint.top) * scale)
                val save = canvas.save()
                canvas.clipRect(clip)
                paint.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
                canvas.drawBitmap(frame.pixels, crop, mapped, paint)
                canvas.restoreToCount(save)
            }
            override fun onDraw(canvas: Canvas) {
                screen.set(0f, 0f, width.toFloat(), height.toFloat())
                val end = target
                if (end == null || progress == 0f) { drawFrame(canvas, source, 1f); return }
                val rect = currentRect()
                if (rect == null || end.rect == null) {
                    drawFrame(canvas, end, 1f); drawFrame(canvas, source, 1f - progress); return
                }
                canvas.drawColor(Color.BLACK)
                // 两端的固定媒体均挖去，只画一处随进度移动的媒体，避免双重图像。
                drawFrame(canvas, end, progress, omitMedia = true)
                drawFrame(canvas, source, 1f - progress, omitMedia = true)
                val image = travel
                if (image != null) {
                    val imageRect = mix(image.from, image.to, progress)
                    // 复用已捕获的实际显示帧，避免再次在主线程重绘大图和 HDR 增益图。
                    // 返回缩放过的照片时，先用目标取景补齐当前屏幕之外的部分。
                    drawAlignedMedia(canvas, end, image.to, imageRect, rect, 1f)
                    if (!entering) drawAlignedMedia(canvas, source, image.from, imageRect, rect, 1f)
                    // 来源徽标和不同播放帧仅在端点附近交接，不在整个运动中重叠两套裁剪。
                    drawAlignedMedia(canvas, source, image.from, imageRect, rect, (1f - progress / .16f).coerceAtLeast(0f))
                    drawAlignedMedia(canvas, end, image.to, imageRect, rect, ((progress - .84f) / .16f).coerceAtLeast(0f))
                } else {
                    drawMedia(canvas, source, rect, 1f)
                    drawMedia(canvas, end, rect, ((progress - .75f) / .25f).coerceAtLeast(0f))
                }
            }
        }
    }

    companion object {
        const val EXTRA_TOKEN = "media_preview_transition_token"
        private val main = Handler(Looper.getMainLooper())
        private data class Pending(val frame: Frame, val origin: WeakReference<DetailActivity>, val key: String?,
                                   val preview: CompletableFuture<MediaPreviewThumbnail.Preview?>?)
        private val pending = mutableMapOf<Long, Pending>()
        internal var diagnosticObserver: ((String) -> Unit)? = null
        internal var diagnosticGeometry: ((Boolean, RectF?, RectF?) -> Unit)? = null
        internal var diagnosticImageGeometry: ((Boolean, Int, Int, RectF, RectF) -> Unit)? = null
        internal var diagnosticInteractiveFrame: ((Float, Float, RectF, RectF) -> Unit)? = null
        internal fun reportInteractiveFrame(progress: Float, elapsedMs: Float, image: RectF, clip: RectF) {
            if (BuildConfig.DEBUG) diagnosticInteractiveFrame?.invoke(progress, elapsedMs, image, clip)
        }
        internal fun mark(stage: String) { if (BuildConfig.DEBUG) diagnosticObserver?.invoke(stage) }
        private fun bars(activity: Activity) = WindowInsetsControllerCompat(activity.window, activity.window.decorView).isAppearanceLightStatusBars

        fun launch(activity: DetailActivity, intent: Intent, source: View) {
            fun launchNow() = activity.startActivityForResult(intent, Def.Communication.REQUEST_ACTIVITY_IMAGE_VIEWER,
                ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle())
            if (!ValueAnimator.areAnimatorsEnabled()) { launchNow(); return }
            val lease = UiInteractions.open(activity, "media-preview-capture") ?: return
            val root = activity.window.decorView as ViewGroup
            val rect = visibleRect(source, root)
            if (rect == null) { lease.release(); launchNow(); return }
            val index = intent.getIntExtra(Def.Communication.KEY_POSITION, 0)
            val key = intent.getStringArrayListExtra(Def.Communication.KEY_TYPE_PATH_NAME)?.getOrNull(index)
            // 先复制详情已保留的完整小图；没有缓存再读内嵌图，与窗口准备重叠，不重新解码大原图。
            val preview = key?.takeIf { it.startsWith("0") && MediaPreviewThumbnail.isStaticImage(it.substring(1)) }
                ?.let { MediaPreviewThumbnail.prepare(it.substring(1)) }
            val lifecycle = activity.lifecycle
            var cancelled = false
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) {
                    cancelled = true; lease.release()
                }
            }
            lifecycle.addObserver(observer)
            mark("launch-capture")
            capture(root, rect, bars(activity)) { frame ->
                mark("launch-captured")
                lifecycle.removeObserver(observer)
                lease.release()
                if (cancelled || activity.isFinishing || activity.isDestroyed) return@capture
                if (frame != null && source.isAttachedToWindow && root.width == frame.width && root.height == frame.height) {
                    val token = SystemClock.elapsedRealtimeNanos()
                    pending.clear()
                    pending[token] = Pending(frame, WeakReference(activity), key, preview)
                    intent.putExtra(EXTRA_TOKEN, token)
                    main.postDelayed({ pending.remove(token) }, 5000)
                }
                launchNow()
            }
        }

        fun preparedPreview(activity: Activity, key: String): CompletableFuture<MediaPreviewThumbnail.Preview?>? =
            pending[activity.intent.getLongExtra(EXTRA_TOKEN, 0)]?.takeIf { it.key == key }?.preview

        fun attach(activity: Activity, photo: () -> PhotoView?, completed: () -> Unit): MediaPreviewTransition? {
            val token = activity.intent.getLongExtra(EXTRA_TOKEN, 0)
            activity.intent.removeExtra(EXTRA_TOKEN)
            val data = pending.remove(token) ?: return null
            if (!ValueAnimator.areAnimatorsEnabled()) return null
            return MediaPreviewTransition(activity, data.origin).also { session ->
                session.detailFrame = data.frame
                session.animation = Presentation(activity, data.frame, true, photo,
                    travelProvider = { data.origin.get()?.let { imageTravel(photo(), it, data.key, true) } }, completed = {
                    session.animation = null
                    completed()
                })
                session.animation!!.start()
            }
        }

        private fun visibleRect(view: View?, root: View): RectF? =
            view?.let { ThingDetailTransition.visibleBounds(it, root) }

        internal fun laidOutDetailRect(view: View?, root: View): RectF? {
            if (view == null || view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return null
            val rect = ThingDetailTransition.bounds(view, root)
            var parent = view.parent as? View
            while (parent != null && parent !== root) {
                if (parent is ViewGroup && parent.clipChildren && !rect.intersect(ThingDetailTransition.bounds(parent, root))) return null
                parent = parent.parent as? View
            }
            return rect.takeIf { it.intersect(0f, 0f, root.width.toFloat(), root.height.toFloat()) }
        }

        private fun photoRect(photo: PhotoView?, root: View): RectF? {
            val view = photo ?: return null
            val rect = fullPhotoRect(view, root) ?: return null
            val bounds = ThingDetailTransition.bounds(view, root)
            if (!rect.intersect(bounds)) return null
            if (!rect.intersect(0f, 0f, root.width.toFloat(), root.height.toFloat())) return null
            return rect
        }

        internal fun fullPhotoRect(view: PhotoView, root: View): RectF? {
            (view.context as? ImageViewerActivity)?.motionPreviewBounds(view)?.let { return it }
            return view.displayRect?.let { mapToRoot(view, root, it) }
        }

        /** 包含父容器的交互缩放，不能只给 displayRect 加一个左上角偏移。 */
        internal fun mapToRoot(view: View, root: View, rect: RectF): RectF {
            val matrix = Matrix()
            var child = view
            while (child !== root) {
                matrix.postConcat(child.matrix)
                val parent = child.parent as? View ?: break
                matrix.postTranslate((child.left - parent.scrollX).toFloat(), (child.top - parent.scrollY).toFloat())
                child = parent
            }
            return RectF(rect).also(matrix::mapRect)
        }

        private fun imageTravel(photo: PhotoView?, detail: DetailActivity, key: String?, entering: Boolean): Travel? {
            val view = photo ?: return null
            val viewerBounds = fullPhotoRect(view, view.rootView) ?: return null
            val width = viewerBounds.width().roundToInt().coerceAtLeast(1)
            val height = viewerBounds.height().roundToInt().coerceAtLeast(1)
            val detailBounds = detail.mediaPreviewImageBounds(key, width, height) ?: return null
            return if (entering) Travel(width, height, detailBounds, viewerBounds) else Travel(width, height, viewerBounds, detailBounds)
        }

        private fun capture(root: View, rect: RectF?, lightBars: Boolean, done: (Frame?) -> Unit) {
            ParticleDismissController.captureContent(root) { pixels ->
                if (pixels == null) mark("capture-unavailable:${com.ywwynm.everythingdone.views.particledismiss.ParticleCaptureDiagnostics.describe()}")
                val bounded = rect?.let(::RectF)?.takeIf {
                    pixels != null && it.intersect(0f, 0f, pixels.width.toFloat(), pixels.height.toFloat())
                }
                done(pixels?.let { Frame(it, bounded, lightBars) })
            }
        }

        private fun mix(a: RectF, b: RectF, p: Float) = RectF(
            a.left + (b.left - a.left) * p, a.top + (b.top - a.top) * p,
            a.right + (b.right - a.right) * p, a.bottom + (b.bottom - a.bottom) * p)
    }
}
