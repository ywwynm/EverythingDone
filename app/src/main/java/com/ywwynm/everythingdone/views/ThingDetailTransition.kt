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
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.view.WindowCallbackWrapper
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.view.doOnPreDraw
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.ThingCardMediaHelper
import com.ywwynm.everythingdone.interaction.UiInteractions
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.views.particledismiss.ParticleDismissController
import com.ywwynm.everythingdone.views.particledismiss.ParticleHardwareSnapshot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 列表与详情仍使用各自 Activity；一次性列表画面承接窗口交接，
 * 容器及关键元素在详情窗口内共用一个进度，不依赖系统 Activity 动画。
 */
internal class ThingDetailTransition private constructor(
    private val activity: Activity,
    private val content: View,
    private val pending: Pending,
    private val onFinished: () -> Unit
) {
    private val host = activity.window.decorView as ViewGroup
    private val originalAlpha = content.alpha
    private val originalAccessibility = content.importantForAccessibility
    private val originalSoftInputMode = activity.window.attributes.softInputMode
    private val systemBars = WindowInsetsControllerCompat(activity.window, host)
    private var targetLightStatusBars = systemBars.isAppearanceLightStatusBars
    private var appliedLightStatusBars = targetLightStatusBars
    private val inputLease = UiInteractions.open(activity, "thing-detail-enter")
    private val layer = TransitionLayer()
    private var animator: ValueAnimator? = null
    private var readyFrames = 0
    private var closed = false
    private var returning = false
    private val previousWindowCallback = activity.window.callback
    // 转场仍保护编辑控件，只受理详情返回按钮所在区域的完整点击。
    private val backInput = object : WindowCallbackWrapper(previousWindowCallback) {
        private var tracking = false
        private var cancelled = false
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (closed || returning) return super.dispatchTouchEvent(event)
            val back = activity.findViewById<View>(R.id.ib_back)
            val rect = Rect()
            val inside = back?.isEnabled == true && back.getGlobalVisibleRect(rect) &&
                rect.contains(event.rawX.toInt(), event.rawY.toInt())
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { tracking = inside; cancelled = false }
                MotionEvent.ACTION_MOVE -> if (!inside) cancelled = true
                MotionEvent.ACTION_CANCEL -> { val handled = tracking; tracking = false; return handled || super.dispatchTouchEvent(event) }
                MotionEvent.ACTION_UP -> if (tracking) {
                    tracking = false
                    if (!cancelled && inside) back.performClick()
                    return true
                }
            }
            return tracking || super.dispatchTouchEvent(event)
        }
    }
    internal val isRunning: Boolean get() = !closed
    internal val progress: Float get() = layer.progress
    internal val matchedElementCount: Int get() = layer.matches.size
    internal val hasMatchedMedia: Boolean get() = layer.matches.any { it.source.role == MEDIA }
    internal var finishReason = "running"
        private set
    private val timeout = Runnable { finish("preparation-timeout") }
    private val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (or > ol && ob > ot && (l != ol || t != ot || r != or || b != ob)) finish()
    }

    private fun start() {
        mark("attach")
        content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        activity.window.setSoftInputMode(
            (originalSoftInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        content.addOnLayoutChangeListener(layoutListener)
        host.addView(layer, ViewGroup.LayoutParams(-1, -1))
        activity.window.callback = backInput
        updateStatusBarAppearance()
        host.postDelayed(timeout, 1500)
        awaitTarget(SystemClock.uptimeMillis() + 250)
    }

    private fun awaitTarget(mediaDeadline: Long) {
        content.doOnPreDraw {
            if (closed || returning) return@doOnPreDraw
            if (host.width != pending.width || host.height != pending.height ||
                !content.isAttachedToWindow) {
                finish("window-size-changed")
                return@doOnPreDraw
            }
            // 冷加载封面可以比首次布局晚一两帧；仍展示原列表，给它一个有上限的准备窗口。
            // 首屏不存在相应附件时立即退化为容器过渡，不滚动或展开真实详情布局。
            val mediaReady = layer.mediaReady() || SystemClock.uptimeMillis() >= mediaDeadline
            val ready = activity.hasWindowFocus() && mediaReady && !content.isLayoutRequested
            if (BuildConfig.DEBUG && diagnosticObserver != null)
                mark("pre-draw:focus=${activity.hasWindowFocus()},ready=$ready")
            readyFrames = if (ready) readyFrames + 1 else 0
            // 媒体超时可以降级，窗口焦点和稳定布局不能跳过，否则慢设备会在准备期耗掉动画时长。
            if (readyFrames < 2) {
                host.postOnAnimation {
                    if (!closed && !returning) { awaitTarget(mediaDeadline); content.invalidate() }
                }
                return@doOnPreDraw
            }
            layer.prepare()
            mark("prepared")
            host.removeCallbacks(timeout)
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 360
                interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                addUpdateListener {
                    // 与返回一致：首次提交后的时间校正不能使已经显示的几何倒退。
                    layer.progress = max(layer.progress, it.animatedValue as Float)
                    updateStatusBarAppearance()
                    layer.invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { finish() }
                })
                start()
            }
        }
    }

    /** 详情异步确定配色时先记住目标；列表承接画面仍使用来源状态栏图标。 */
    fun setTargetLightStatusBars(light: Boolean) {
        targetLightStatusBars = light
        updateStatusBarAppearance()
    }

    /** 保留当前合成画面，不先跳到完整详情；输入保护交给返回截图阶段。 */
    fun freezeForReturn() {
        returning = true
        host.removeCallbacks(timeout)
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        inputLease?.release()
    }

    fun captureReturnFrame(done: (ThingDetailReturnTransition.Frame?) -> Unit) {
        val geometry = layer.returnGeometry()
        val captured: (Bitmap?) -> Unit = { pixels ->
            if (closed) done(null)
            else {
                // 退出窗口尚未移除时仍展示同一帧，不能在 onPause 清理时露出完整详情。
                layer.returnPixels = pixels
                done(pixels?.let { ThingDetailReturnTransition.Frame(it,
                    RectF(0f, 0f, host.width.toFloat(), host.height.toFloat()),
                    geometry.first, geometry.second, pending.radius * (1f - layer.progress),
                    emptyList(), null, systemBars.isAppearanceLightStatusBars,
                    host.width, host.height, content.background?.constantState?.newDrawable()?.mutate()) })
            }
        }
        if (Build.VERSION.SDK_INT >= 29 && host.isHardwareAccelerated) {
            ParticleHardwareSnapshot.capture(host) { pixels ->
                if (pixels != null) captured(pixels) else ParticleDismissController.captureContent(host, done = captured)
            }
        } else ParticleDismissController.captureContent(host, done = captured)
    }

    private fun updateStatusBarAppearance() {
        val light = if (closed || layer.coversStatusBar()) targetLightStatusBars else pending.lightStatusBars
        if (appliedLightStatusBars != light) {
            systemBars.isAppearanceLightStatusBars = light
            appliedLightStatusBars = light
        }
    }

    /** 后台、旋转、立即返回与正常结束走同一清理；不在 View 绘制遍历中移除节点。 */
    fun finish(reason: String = "finished") {
        if (closed) return
        closed = true
        finishReason = reason
        val retainReturnFrame = returning && activity.isFinishing && layer.returnPixels != null
        if (!retainReturnFrame) updateStatusBarAppearance()
        host.removeCallbacks(timeout)
        content.removeOnLayoutChangeListener(layoutListener)
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        content.alpha = originalAlpha
        content.importantForAccessibility = originalAccessibility
        activity.window.setSoftInputMode(originalSoftInputMode)
        inputLease?.release()
        if (activity.window.callback === backInput) activity.window.callback = previousWindowCallback
        val removeLayer = Runnable {
            host.removeView(layer)
            layer.returnPixels = null
            layer.matches.clear()
            pending.release()
        }
        if (retainReturnFrame && host.isAttachedToWindow) {
            host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {}
                override fun onViewDetachedFromWindow(view: View) {
                    view.removeOnAttachStateChangeListener(this)
                    Handler(Looper.getMainLooper()).post(removeLayer)
                }
            })
        } else {
            layer.visibility = View.GONE
            // 不在布局或分离遍历中移除子节点，也不提前回收 RenderThread 正在使用的位图。
            Handler(Looper.getMainLooper()).post(removeLayer)
        }
        onFinished()
        mark("finished")
    }

    private data class Element(
        val role: Int, val bounds: RectF, val visible: RectF,
        val text: String? = null, val textSize: Float = 0f, val baseline: Float = 0f,
        val textInset: Float = 0f, val scale: Float = 1f, var pixels: Bitmap? = null
    )

    private data class Pending(
        var scene: Bitmap?, val width: Int, val height: Int,
        val card: RectF, val visibleCard: RectF, val radius: Float,
        val elements: List<Element>, val mediaKey: String?, val thingId: Long, val lightStatusBars: Boolean
    ) {
        fun release() { scene = null; elements.forEach { it.pixels = null } }
    }

    private data class Match(
        val source: Element, val target: View, val end: RectF, val visibleEnd: RectF,
        val pixels: Bitmap? = null
    )

    private inner class TransitionLayer : View(activity) {
        var returnPixels: Bitmap? = null
        private var motionMarked = false
        var progress = 0f
        val matches = mutableListOf<Match>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val path = Path()
        private val current = RectF()
        private val currentClip = RectF()
        private val end = RectF()
        private val destination = RectF()
        private var statusBarHeight = 0
        private val background = content.background?.constantState?.newDrawable()?.mutate()

        init {
            isClickable = true
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setWillNotDraw(false)
        }

        fun returnGeometry(): Pair<RectF, RectF> {
            if (end.isEmpty || progress == 0f) return RectF(pending.card) to RectF(pending.visibleCard)
            val rect = RectF()
            val clip = RectF()
            interpolate(pending.card, end, progress, rect)
            interpolate(pending.visibleCard, end, progress, clip)
            return rect to clip
        }

        private fun targetMedia(): ImageView? = descendants(content).filterIsInstance<ImageView>().firstOrNull {
            pending.mediaKey != null &&
                it.getTag(R.id.tag_detail_attachment_bound_source) == pending.mediaKey &&
                visibleBounds(it, host) != null
        }

        fun mediaReady(): Boolean {
            if (pending.elements.none { it.role == MEDIA }) return true
            val target = targetMedia() ?: return true
            return target.drawable != null && !target.isLayoutRequested && !content.isLayoutRequested
        }

        fun prepare() {
            end.set(bounds(content, host))
            statusBarHeight = ViewCompat.getRootWindowInsets(host)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
            val media = targetMedia()?.takeIf { it.drawable != null }
            pending.elements.forEach { element ->
                val target: View? = when (element.role) {
                    TITLE -> content.findViewById<TextView>(R.id.et_title)
                    BODY -> content.findViewById<TextView>(R.id.et_content)
                    else -> media
                }
                val visible = target?.let { visibleBounds(it, host) } ?: return@forEach
                if (target is TextView) {
                    val a = element.text.orEmpty().trimEnd('…')
                    if (a.isBlank() || !target.text.toString().startsWith(a)) return@forEach
                }
                val pixels = if (element.role == MEDIA &&
                    target.width.toLong() * target.height <= host.width.toLong() * host.height) {
                    // 详情媒体请求禁用硬件 bitmap；冻结一次实际裁切结果，避免直接重入 ImageView 的硬件绘制。
                    Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888).also { target.draw(Canvas(it)) }
                } else null
                matches += Match(element, target, bounds(target, host), visible, pixels = pixels)
            }
            matches.sortBy { if (it.source.role == MEDIA) 0 else 1 }
        }

        fun coversStatusBar(): Boolean = !end.isEmpty && progress >= .9f &&
            pending.card.top + (end.top - pending.card.top) * progress <= statusBarHeight * .5f

        override fun onDraw(canvas: Canvas) {
            returnPixels?.let { paint.alpha = 255; canvas.drawBitmap(it, 0f, 0f, paint); return }
            if (progress > 0f && !motionMarked) {
                motionMarked = true
                mark("first-motion")
            }
            val scene = pending.scene ?: return
            // 覆盖层在真实页面上方完整遮挡窗口，无需改变真实页面 alpha；
            // 配色采样、可见区计算等逻辑仍能读取真实页面的正常显示状态。
            canvas.drawColor(Color.BLACK)
            paint.alpha = 255
            canvas.drawBitmap(scene, 0f, 0f, paint)
            if (progress <= 0f || end.isEmpty) return
            interpolate(pending.card, end, progress, current)
            interpolate(pending.visibleCard, end, progress, currentClip)
            path.rewind()
            val radius = pending.radius * (1f - progress)
            path.addRoundRect(current, radius, radius, Path.Direction.CW)
            val outer = canvas.save()
            canvas.clipRect(currentClip)
            canvas.clipPath(path)
            background?.let {
                it.setBounds(current.left.roundToInt(), current.top.roundToInt(),
                    current.right.roundToInt(), current.bottom.roundToInt())
                it.draw(canvas)
            }

            // 两端内容各自等比缩放并裁剪。匹配元素从容器绘制中排除，由下面的独立轨迹绘制一次。
            val sourceAlpha = 1f - fraction(progress, 0.05f, 0.6f)
            if (sourceAlpha > 0f) {
                val saved = canvas.save()
                val scale = current.width() / pending.card.width()
                canvas.translate(current.left, current.top)
                canvas.scale(scale, scale)
                canvas.translate(-pending.card.left, -pending.card.top)
                canvas.clipRect(pending.visibleCard)
                matches.forEach { canvas.clipOutRect(it.source.bounds) }
                paint.alpha = (255 * sourceAlpha).roundToInt()
                canvas.drawBitmap(scene, 0f, 0f, paint)
                canvas.restoreToCount(saved)
            }
            matches.forEach { drawMatch(canvas, scene, it) }
            val targetAlpha = fraction(progress, 0.12f, 0.85f)
            if (targetAlpha > 0f) {
                val saved = canvas.save()
                val scale = current.width() / end.width()
                canvas.translate(current.left, current.top)
                canvas.scale(scale, scale)
                val alphaLayer = canvas.saveLayerAlpha(0f, 0f, content.width.toFloat(),
                    content.height.toFloat(), (255 * targetAlpha).roundToInt())
                val background = content.background
                val backgroundAlpha = background?.alpha
                try {
                    // 只在本次同步绘制中排除匹配控件。INVISIBLE 不改变布局，且不会把图片上方的按钮挖掉。
                    // 随即恢复，避免配色控制器在下一次 pre-draw 把图片误判为透明。
                    matches.forEach { it.target.visibility = View.INVISIBLE }
                    background?.alpha = 0
                    content.draw(canvas)
                } finally {
                    if (backgroundAlpha != null) background.alpha = backgroundAlpha
                    matches.forEach { it.target.visibility = View.VISIBLE }
                }
                canvas.restoreToCount(alphaLayer)
                canvas.restoreToCount(saved)
            }
            canvas.restoreToCount(outer)
        }

        private fun drawMatch(canvas: Canvas, scene: Bitmap, match: Match) {
            val source = match.source
            interpolate(source.bounds, match.end, progress, destination)
            val crossfade = fraction(progress, 0.2f, 0.8f)
            val saved = canvas.save()
            val clip = RectF()
            interpolate(source.visible, match.visibleEnd, progress, clip)
            canvas.clipRect(clip)
            if (source.role == MEDIA) {
                if (crossfade < 1f) {
                    paint.alpha = (255 * (1f - crossfade)).roundToInt()
                    // 保留原图框的完整几何；来源部分离屏时不能把可见像素拉满整个目标框。
                    val frame = canvas.save()
                    canvas.clipRect(destination)
                    val scale = max(destination.width() / source.bounds.width(),
                        destination.height() / source.bounds.height())
                    canvas.translate(destination.centerX(), destination.centerY())
                    canvas.scale(scale, scale)
                    val mediaPixels = source.pixels
                    if (mediaPixels != null) {
                        canvas.translate(-source.bounds.width() / 2, -source.bounds.height() / 2)
                        canvas.drawBitmap(mediaPixels, null,
                            RectF(0f, 0f, source.bounds.width(), source.bounds.height()), paint)
                    } else {
                        canvas.translate(-source.bounds.centerX(), -source.bounds.centerY())
                        canvas.clipRect(source.visible)
                        canvas.drawBitmap(scene, 0f, 0f, paint)
                    }
                    canvas.restoreToCount(frame)
                }
                val pixels = match.pixels
                if (pixels != null) {
                    paint.alpha = (255 * crossfade).roundToInt()
                    val frame = canvas.save()
                    canvas.clipRect(destination)
                    val scale = max(destination.width() / pixels.width, destination.height() / pixels.height)
                    val image = RectF(destination.centerX() - pixels.width * scale / 2,
                        destination.centerY() - pixels.height * scale / 2,
                        destination.centerX() + pixels.width * scale / 2,
                        destination.centerY() + pixels.height * scale / 2)
                    canvas.drawBitmap(pixels, null, image, paint)
                    canvas.restoreToCount(frame)
                } else drawTarget(canvas, match.target, destination, crossfade, true)
            } else {
                val target = match.target as TextView
                val sourceSize = source.textSize * source.scale
                val targetScale = match.end.width() / target.width
                val size = sourceSize + (target.textSize * targetScale - sourceSize) * progress
                val baseline = (source.bounds.top + source.baseline * source.scale) * (1f - progress) +
                    (match.end.top + target.baseline * targetScale) * progress
                val textLeft = (source.bounds.left + source.textInset * source.scale) * (1f - progress) +
                    (match.end.left + target.totalPaddingLeft * targetScale) * progress
                source.pixels?.let {
                    val s = size / source.textSize
                    paint.alpha = (255 * (1f - crossfade)).roundToInt()
                    val matrix = Matrix().apply {
                        setScale(s, s)
                        postTranslate(textLeft - source.textInset * s, baseline - source.baseline * s)
                    }
                    canvas.drawBitmap(it, matrix, paint)
                }
                val scale = size / target.textSize
                val left = textLeft - target.totalPaddingLeft * scale
                val targetRect = RectF(left, baseline - target.baseline * scale,
                    left + target.width * scale,
                    baseline - target.baseline * scale + target.height * scale)
                drawTarget(canvas, target, targetRect, crossfade, false)
            }
            canvas.restoreToCount(saved)
        }

        private fun drawTarget(canvas: Canvas, view: View, rect: RectF, alpha: Float, crop: Boolean) {
            if (alpha <= 0f) return
            val saved = canvas.save()
            canvas.clipRect(rect)
            val scale = if (crop) max(rect.width() / view.width, rect.height() / view.height)
                else rect.width() / view.width
            canvas.translate(rect.left + if (crop) (rect.width() - view.width * scale) / 2f else 0f,
                rect.top + if (crop) (rect.height() - view.height * scale) / 2f else 0f)
            canvas.scale(scale, scale)
            val alphaLayer = canvas.saveLayerAlpha(0f, 0f, view.width.toFloat(), view.height.toFloat(),
                (255 * alpha).roundToInt())
            view.draw(canvas)
            canvas.restoreToCount(alphaLayer)
            canvas.restoreToCount(saved)
        }
    }

    companion object {
        /** 仅由 debug 探针启用；正常运行不采样、不输出日志、不保存记事内容。 */
        internal var diagnosticObserver: ((String) -> Unit)? = null
        internal fun mark(stage: String) {
            if (BuildConfig.DEBUG) diagnosticObserver?.invoke(stage)
        }
        const val EXTRA_TOKEN = "thing_detail_transition_token"
        internal const val EXTRA_FALLBACK = "thing_detail_transition_fallback"
        private const val TITLE = 0
        private const val BODY = 1
        private const val MEDIA = 2
        private val handler = Handler(Looper.getMainLooper())
        private val pendingTransitions = mutableMapOf<Long, Pending>()

        fun launch(activity: Activity, intent: Intent, card: View, thing: Thing) {
            // “正在做”等入口会返回其它 Activity，只有详情窗口能消费这一组画面与返回资格。
            if (intent.component?.className != com.ywwynm.everythingdone.activities.DetailActivity::class.java.name) {
                activity.startActivityForResult(intent, Def.Communication.REQUEST_ACTIVITY_DETAIL)
                return
            }
            mark("click")
            if (!ValueAnimator.areAnimatorsEnabled() || card.width <= 0 || card.height <= 0) {
                intent.putExtra(EXTRA_FALLBACK, "animators-disabled-or-empty-card")
                activity.startActivityForResult(intent, Def.Communication.REQUEST_ACTIVITY_DETAIL)
                return
            }
            val lease = UiInteractions.open(activity, "thing-detail-capture") ?: return
            ThingDetailReturnTransition.rememberOrigin(activity, intent, card, thing.id)
            val decor = activity.window.decorView as ViewGroup
            val sourceIdentity = boundThingId(card, decor)
            if (sourceIdentity != null && sourceIdentity != thing.id) {
                intent.putExtra(EXTRA_FALLBACK, "rebound-source")
                lease.release()
                activity.startActivityForResult(intent, Def.Communication.REQUEST_ACTIVITY_DETAIL,
                    ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle())
                return
            }
            val sourceBounds = bounds(card, decor)
            val visible = visibleBounds(card, decor)
            if (visible == null) {
                intent.putExtra(EXTRA_FALLBACK, "invisible-source")
                lease.release()
                activity.startActivityForResult(intent, Def.Communication.REQUEST_ACTIVITY_DETAIL,
                    ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle())
                return
            }
            val elements = mutableListOf<Element>()
            for ((id, role) in listOf(R.id.tv_thing_title to TITLE, R.id.tv_thing_content to BODY)) {
                val view = card.findViewById<TextView>(id) ?: continue
                val rect = visibleBounds(view, decor) ?: continue
                if (view.text.isBlank() || view.width.toLong() * view.height > decor.width.toLong() * decor.height) continue
                val pixels = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(pixels))
                val fullBounds = bounds(view, decor)
                elements += Element(role, fullBounds, rect, view.text.toString(),
                    view.textSize, view.baseline.toFloat(), view.totalPaddingLeft.toFloat(),
                    fullBounds.width() / view.width, pixels)
            }
            val mediaView = listOf(R.id.iv_thing_image, R.id.iv_thing_media_background)
                .mapNotNull { card.findViewById<ImageView>(it) }
                .firstOrNull { it.drawable != null && visibleBounds(it, decor) != null &&
                    it.width.toLong() * it.height <= decor.width.toLong() * decor.height }
            val mediaElement = mediaView?.let { Element(MEDIA, bounds(it, decor), visibleBounds(it, decor)!!) }
            mediaElement?.let(elements::add)
            val mediaKey = ThingCardMediaHelper.resolveEffectiveMediaSource(thing)?.typePathName
            val width = decor.width
            mark("source-elements")
            val height = decor.height
            var scene: Bitmap? = null
            var capturesLeft = if (mediaView != null) 2 else 1
            var cancelled = false
            val lifecycle = (activity as? LifecycleOwner)?.lifecycle
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP ||
                    event == Lifecycle.Event.ON_DESTROY) {
                    cancelled = true
                    lease.release()
                }
            }
            lifecycle?.addObserver(observer)
            fun captured() {
                if (--capturesLeft > 0) return
                mark("captured")
                lifecycle?.removeObserver(observer)
                lease.release()
                // 认证弹窗刚关闭时主窗口可能尚未重新获得焦点；以生命周期判断取消，不能吞掉认证成功后的打开。
                if (cancelled || activity.isFinishing || activity.isDestroyed) return
                val token = SystemClock.elapsedRealtimeNanos()
                if (scene != null && card.isAttachedToWindow && decor.width == width && decor.height == height &&
                    boundThingId(card, decor) == sourceIdentity) {
                    if (pendingTransitions.size >= 2) {
                        pendingTransitions.keys.firstOrNull()?.let { pendingTransitions.remove(it)?.release() }
                    }
                    pendingTransitions[token] = Pending(scene, width, height, sourceBounds, visible,
                        (card as? CardView)?.radius ?: 0f, elements, mediaKey, thing.id,
                        WindowInsetsControllerCompat(activity.window, decor).isAppearanceLightStatusBars)
                    scene?.prepareToDraw()
                    elements.forEach { it.pixels?.prepareToDraw() }
                    intent.putExtra(EXTRA_TOKEN, token)
                    handler.postDelayed({ pendingTransitions.remove(token)?.release() }, 5000)
                } else {
                    intent.putExtra(EXTRA_FALLBACK, when {
                        scene == null -> "snapshot-unavailable"
                        !card.isAttachedToWindow -> "detached-source"
                        decor.width != width || decor.height != height -> "source-window-size-changed"
                        else -> "rebound-source-after-capture"
                    })
                }
                try {
                    mark("start-activity")
                    activity.startActivityForResult(intent, Def.Communication.REQUEST_ACTIVITY_DETAIL,
                        ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle())
                    mark("start-activity-return")
                } catch (failure: RuntimeException) {
                    pendingTransitions.remove(token)?.release()
                    throw failure
                }
            }
            // 整屏承接画面只交给硬件 Canvas，不读取像素；避免 GPU → CPU → GPU 的整屏往返。
            // 旧系统或硬件捕获失败仍沿用已验证的内容快照路径。
            if (Build.VERSION.SDK_INT >= 29 && decor.isHardwareAccelerated) {
                ParticleHardwareSnapshot.captureKeepingHardware(decor) { bitmap ->
                    if (bitmap != null) { scene = bitmap; captured() }
                    else ParticleDismissController.captureContent(decor) { scene = it; captured() }
                }
            } else ParticleDismissController.captureContent(decor) { scene = it; captured() }
            // 单独保留媒体像素，背景封面上方的标题不能烘焙进去再与标题轨迹重复绘制。
            if (mediaView != null) ParticleDismissController.captureContent(mediaView) {
                mediaElement?.pixels = it
                captured()
            }
        }

        fun attach(activity: Activity, content: View, thingId: Long, onFinished: () -> Unit): ThingDetailTransition? {
            val token = activity.intent.getLongExtra(EXTRA_TOKEN, 0)
            activity.intent.removeExtra(EXTRA_TOKEN)
            val pending = pendingTransitions.remove(token) ?: return null
            if (pending.thingId != thingId || !ValueAnimator.areAnimatorsEnabled() || activity.isFinishing) {
                pending.release()
                return null
            }
            return ThingDetailTransition(activity, content, pending, onFinished).also { it.start() }
        }

        internal fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }

        private fun boundThingId(view: View, root: View): Long? {
            var child: View? = view
            while (child != null && child !== root) {
                (child.getTag(R.id.tag_thing_card_bound_stable_id) as? Long)?.let {
                    // 文件夹缩略预览的外层身份为负数，不能误当成所点击的记事。
                    return it.takeIf { id -> id >= 0L }
                }
                child = child.parent as? View
            }
            return null
        }
        internal fun bounds(view: View, root: View): RectF {
            val matrix = Matrix()
            var child: View = view
            while (child !== root) {
                matrix.postConcat(child.matrix)
                val parent = child.parent as? View ?: break
                matrix.postTranslate((child.left - parent.scrollX).toFloat(), (child.top - parent.scrollY).toFloat())
                child = parent
            }
            return RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()).also { matrix.mapRect(it) }
        }

        internal fun visibleBounds(view: View, root: View): RectF? {
            if (!view.isShown || view.width <= 0 || view.height <= 0) return null
            val rect = Rect()
            if (!view.getGlobalVisibleRect(rect)) return null
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            return RectF(rect).apply { offset(-location[0].toFloat(), -location[1].toFloat()) }
        }

        private fun interpolate(a: RectF, b: RectF, t: Float, result: RectF) {
            result.set(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
                a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t)
        }

        private fun fraction(t: Float, start: Float, end: Float): Float = ((t - start) / (end - start)).coerceIn(0f, 1f)

    }
}
