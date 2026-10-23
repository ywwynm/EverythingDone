package com.ywwynm.everythingdone.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.ThingsActivity
import com.ywwynm.everythingdone.adapters.ThingsAdapter
import com.ywwynm.everythingdone.helpers.ThingCardMediaHelper
import com.ywwynm.everythingdone.helpers.ThingPrivacyResolver
import com.ywwynm.everythingdone.interaction.UiInteractions
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.views.ThingDetailTransition.Companion.bounds
import com.ywwynm.everythingdone.views.ThingDetailTransition.Companion.descendants
import com.ywwynm.everythingdone.views.ThingDetailTransition.Companion.visibleBounds
import com.ywwynm.everythingdone.views.particledismiss.ParticleDismissController
import com.ywwynm.everythingdone.views.particledismiss.ParticleHardwareSnapshot
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/** 已提交的返回由列表窗口承接。来源只保存弱身份；画面和几何都在这次返回时取得。 */
internal class ThingDetailReturnTransition(
    private val activity: ThingsActivity,
    private val list: RecyclerView,
    private val source: Frame,
    private val route: Origin,
    private val onFinished: () -> Unit
) {
    private val host = activity.window.decorView as ViewGroup
    private val content = host.findViewById<View>(android.R.id.content)
    private val layer = ReturnLayer()
    private val input = UiInteractions.restore(activity, "thing-detail-return")
    private val accessibility = content.importantForAccessibility
    private val bars = WindowInsetsControllerCompat(activity.window, host)
    private var targetLightBars = bars.isAppearanceLightStatusBars
    private val manager = ThingManager.getInstance(activity)!!
    private val authGeneration = manager.getPrivacyAuthGeneration()
    private var animator: ValueAnimator? = null
    private var destination: Frame? = null
    private var listScene: Bitmap? = null
    private var backgroundBounds = RectF()
    private var target: View? = null
    private val pausedMedia = mutableSetOf<Animatable>()
    private var readyFrames = 0
    private var preparing = false
    private var diagnosticLayoutState: String? = null
    private var frozen = false
    private val timeout = Runnable { finish("preparation-timeout") }
    private val sizeListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (or > ol && ob > ot && (l != ol || t != ot || r != or || b != ob)) finish("window-size-changed")
    }
    internal var isRunning = true
        private set
    internal val progress get() = layer.motionProgress
    internal var hasTarget = false
        private set
    internal var targetBounds: RectF? = null
        private set
    internal var matchedElementCount = 0
        private set
    internal var finishReason = "running"
        private set

    init {
        content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        bars.isAppearanceLightStatusBars = source.lightBars
        host.addView(layer, ViewGroup.LayoutParams(-1, -1))
        host.addOnLayoutChangeListener(sizeListener)
        host.postDelayed(timeout, 1500)
        mark("attached")
    }

    /** Activity 先完成本次列表提交；从真实布局取得终点，不能复用来源 position。 */
    fun awaitDestination() {
        if (!isRunning || preparing) return
        mark("destination-requested")
        preparing = true
        awaitLayout()
    }

    fun onListChromeApplied() {
        targetLightBars = bars.isAppearanceLightStatusBars
        if (isRunning && layer.motionProgress <= .12f) bars.isAppearanceLightStatusBars = source.lightBars
    }

    private fun valid(): Boolean = !activity.isFinishing && !activity.isDestroyed &&
        manager.getPrivacyAuthGeneration() == authGeneration &&
        (!route.privateContent || manager.isThingPrivacyAuthenticated(route.thingId) ||
            manager.isFolderPrivacyAuthenticated(route.folderId)) &&
        host.width == source.windowWidth && host.height == source.windowHeight

    private fun awaitLayout() {
        host.doOnPreDraw {
            if (!isRunning) return@doOnPreDraw
            if (!valid()) { finish("invalid-window-or-auth"); return@doOnPreDraw }
            val ready = !list.isComputingLayout &&
                !list.isLayoutRequested && !list.hasPendingAdapterUpdates() && !content.isLayoutRequested
            if (BuildConfig.DEBUG && diagnosticObserver != null) {
                val state = "layout-check:focus=${activity.hasWindowFocus()},layout=${list.isLayoutRequested}," +
                    "updates=${list.hasPendingAdapterUpdates()},content=${content.isLayoutRequested}"
                if (state != diagnosticLayoutState) { diagnosticLayoutState = state; mark(state) }
            }
            readyFrames = if (ready) readyFrames + 1 else 0
            if (readyFrames < 2) {
                host.postOnAnimation { if (isRunning) { awaitLayout(); host.invalidate() } }
                return@doOnPreDraw
            }
            bars.isAppearanceLightStatusBars = source.lightBars
            mark("layout-ready")
            target = findTarget(list, route)
            list.stopScroll()
            list.suppressLayout(true)
            frozen = true
            // 动态显示帧只在本次交接期间暂停；不共享或接管解码器。
            target?.let { card -> descendants(card).filterIsInstance<ImageView>().forEach {
                (it.drawable as? Animatable)?.takeIf { drawable -> drawable.isRunning }?.let { drawable ->
                    pausedMedia += drawable
                    drawable.stop()
                }
            } }
            captureDestination()
        }
    }

    private fun captureDestination() {
        val card = target
        var remaining = if (card == null) 1 else 2
        fun captured() {
            if (!isRunning) return
            if (--remaining != 0) return
            if (!valid()) { finish("invalid-window-or-auth"); return }
            if (listScene == null || (card != null && destination == null)) {
                finish("destination-unavailable"); return
            }
            layer.prepare()
            hasTarget = destination != null
            targetBounds = destination?.rect?.let(::RectF)
            matchedElementCount = layer.matches.size
            mark(if (hasTarget) "prepared-target" else "prepared-fade")
            animateWhenFocused()
        }
        if (card != null) {
            captureFrame(card, host, false, card.getTag(R.id.tag_thing_card_bound_media_source) as? String,
                targetLightBars) { frame ->
                mark("target-ready")
                if (isRunning && target === card) destination = frame
                captured()
            }
        }
        backgroundBounds = bounds(content, host)
        // 内容根不包含 Decor 上的承接层。只在同步录制显示树期间隐藏卡片，
        // 得到其后面的真实文件夹／列表背景；恢复后不留不可见卡片。
        val oldVisibility = card?.visibility
        try {
            card?.visibility = View.INVISIBLE
            capturePixels(content, null) { pixels ->
                mark("background-ready")
                if (isRunning) listScene = pixels
                captured()
            }
        } finally {
            if (oldVisibility != null) card?.visibility = oldVisibility
        }
    }

    /** 布局稳定即可捕获，和窗口焦点交接并行；运动仍等待首页真正取得焦点。 */
    private fun animateWhenFocused() {
        if (!isRunning) return
        if (!valid()) { finish("invalid-window-or-auth"); return }
        if (!activity.hasWindowFocus()) {
            host.doOnPreDraw {
                host.postOnAnimation { animateWhenFocused() }
            }
            host.invalidate()
            return
        }
        host.removeCallbacks(timeout)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (hasTarget) 300L else 150L
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!valid()) { finish("invalid-window-or-auth"); return@addUpdateListener }
                // 某些设备会在首帧提交后校正 ValueAnimator 时间，避免中途短暂倒退。
                layer.progress = max(layer.progress, it.animatedValue as Float)
                bars.isAppearanceLightStatusBars = if (layer.motionProgress > .12f) targetLightBars else source.lightBars
                layer.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    host.postOnAnimation { finish("finished") }
                }
            })
            start()
        }
    }

    fun finish(reason: String = "cancelled") {
        if (!isRunning) return
        isRunning = false
        finishReason = reason
        host.removeCallbacks(timeout)
        host.removeOnLayoutChangeListener(sizeListener)
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        bars.isAppearanceLightStatusBars = targetLightBars
        content.importantForAccessibility = accessibility
        layer.visibility = View.GONE
        if (frozen) { list.suppressLayout(false); frozen = false }
        pausedMedia.forEach { it.start() }
        pausedMedia.clear()
        // 布局回调也会取消；在消息队列移除节点并释放所有快照引用。
        host.post {
            host.removeView(layer)
            listScene = null
            destination = null
            source.release()
            layer.matches.clear()
        }
        onFinished()
        mark("finished:$reason")
    }

    /** 输入保护覆盖随后一批列表提交，避免旧卡片按 Manager 的新位置响应点击。 */
    fun releaseInput() = input.release()

    private inner class ReturnLayer : FrameLayout(activity) {
        // 前 252 ms 完成几何收回，再用 48 ms 在同一位置交接；总时长仍为 300 ms。
        var progress = 0f
        private val motionCurve = PathInterpolator(.2f, 0f, 0f, 1f)
        val motionProgress get() = motionCurve.getInterpolation(
            if (destination == null) progress else (progress / .84f).coerceIn(0f, 1f))
        val matches = mutableListOf<Pair<Part, Part>>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val path = Path()
        private val shadow = ThingDetailTransitionShadow(activity)
        private var motionMarked = false

        init {
            isClickable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            clipChildren = false
            addView(shadow, LayoutParams(-1, -1))
        }

        fun prepare() {
            val end = destination ?: return
            source.parts.forEach { a ->
                val b = end.parts.firstOrNull { it.role == a.role } ?: return@forEach
                if (a.pixels == null || b.pixels == null) return@forEach
                if (a.role == MEDIA && source.mediaKey != end.mediaKey) return@forEach
                if (a.role != MEDIA && !a.text.orEmpty().startsWith(b.text.orEmpty().trimEnd('…'))) return@forEach
                matches += a to b
            }
            matches.sortBy { if (it.first.role == MEDIA) 0 else 1 }
        }

        override fun dispatchDraw(canvas: Canvas) {
            val startPixels = source.pixels ?: return
            val motion = motionProgress
            if (motion > 0f && !motionMarked) { motionMarked = true; mark("first-motion") }
            if (motion == 0f || listScene == null) {
                paint.alpha = 255
                canvas.drawBitmap(startPixels, null, source.pixelsRect, paint)
                return
            }
            canvas.drawColor(Color.BLACK)
            paint.alpha = 255
            listScene?.let { canvas.drawBitmap(it, null, backgroundBounds, paint) }
            val end = destination
            if (end == null) {
                paint.alpha = ((1f - motion) * 255).roundToInt()
                canvas.drawBitmap(startPixels, null, source.pixelsRect, paint)
                return
            }
            val rect = lerp(source.rect, end.rect, motion)
            val clip = lerp(source.clip, end.clip, motion)
            val radius = source.radius + (end.radius - source.radius) * motion
            val style = end.shadow ?: source.shadow
            shadow.update(rect, radius, style,
                (source.shadow?.elevation ?: 0f) * (1f - motion) + (end.shadow?.elevation ?: 0f) * motion)
            val projection = canvas.save()
            val window = RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.clipRect(lerp(source.shadow?.clip ?: window, end.shadow?.clip ?: window, motion))
            path.rewind()
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.clipOutPath(path)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(projection)
            val saved = canvas.save()
            canvas.clipRect(clip)
            canvas.clipPath(path)
            val blend = fraction(motion, .08f, .8f)
            for ((frame, alpha) in listOf(source to 1f, end to blend)) {
                frame.background?.let {
                    it.setBounds(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
                    it.alpha = (255 * alpha).roundToInt()
                    it.draw(canvas)
                }
            }
            // 不对应的文字/控件先退后进，不能把不同排版的两张截图同时叠画。
            drawFrame(canvas, source, rect, (1f - blend * 2f).coerceAtLeast(0f), matches.map { it.first })
            drawFrame(canvas, end, rect, (blend * 2f - 1f).coerceAtLeast(0f), matches.map { it.second })
            matches.forEach { (a, b) -> drawPart(canvas, a, b) }
            canvas.restoreToCount(saved)
            // 容器和所有元素到达真实位置后才交接，避免两个不同位置的卡片同时出现。
            // 仍使用同一展示版本的真实内容，保留遮罩、阴影和晚到的媒体显示。
            val handoff = fraction(progress, .84f, 1f)
            if (handoff > 0f) {
                val real = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * handoff).roundToInt())
                val rect = bounds(content, host)
                canvas.translate(rect.left, rect.top)
                content.draw(canvas)
                canvas.restoreToCount(real)
            }
        }

        private fun drawFrame(canvas: Canvas, frame: Frame, rect: RectF, alpha: Float, excluded: List<Part>) {
            val pixels = frame.pixels ?: return
            if (alpha <= 0f) return
            val saved = canvas.save()
            val scale = rect.width() / frame.rect.width()
            canvas.translate(rect.left, rect.top)
            canvas.scale(scale, scale)
            canvas.translate(-frame.rect.left, -frame.rect.top)
            canvas.clipRect(frame.clip)
            excluded.forEach { canvas.clipOutRect(it.rect) }
            paint.alpha = (255 * alpha).roundToInt()
            canvas.drawBitmap(pixels, null, frame.pixelsRect, paint)
            canvas.restoreToCount(saved)
        }

        private fun drawPart(canvas: Canvas, a: Part, b: Part) {
            val motion = motionProgress
            val rect = lerp(a.rect, b.rect, motion)
            val saved = canvas.save()
            canvas.clipRect(lerp(a.clip, b.clip, motion))
            val blend = fraction(motion, .16f, .84f)
            if (a.role == MEDIA) {
                canvas.clipRect(rect)
                drawMedia(canvas, a, rect, 1f - blend)
                drawMedia(canvas, b, rect, blend)
            } else {
                val size = a.textSize * a.scale * (1f - motion) + b.textSize * b.scale * motion
                val baseline = (a.rect.top + a.baseline * a.scale) * (1f - motion) +
                    (b.rect.top + b.baseline * b.scale) * motion
                val left = (a.rect.left + a.padding * a.scale) * (1f - motion) +
                    (b.rect.left + b.padding * b.scale) * motion
                // 单行同文已按字号和基线对齐；换行/摘要不同则顺序交接，避免同一句出现两遍。
                val aligned = a.text == b.text && a.lineCount == 1 && b.lineCount == 1 &&
                    !a.ellipsized && !b.ellipsized
                val out = if (aligned) 1f - blend else (1f - blend * 2f).coerceAtLeast(0f)
                val into = if (aligned) blend else (blend * 2f - 1f).coerceAtLeast(0f)
                for ((part, alpha) in listOf(a to out, b to into)) {
                    val pixels = part.pixels ?: continue
                    if (alpha <= 0f) continue
                    val scale = size / part.textSize
                    val box = RectF(left - part.padding * scale, baseline - part.baseline * scale,
                        left + (pixels.width - part.padding) * scale,
                        baseline + (pixels.height - part.baseline) * scale)
                    paint.alpha = (255 * alpha).roundToInt()
                    canvas.drawBitmap(pixels, null, box, paint)
                }
            }
            canvas.restoreToCount(saved)
        }

        private fun drawMedia(canvas: Canvas, part: Part, rect: RectF, alpha: Float) {
            val pixels = part.pixels ?: return
            val scale = max(rect.width() / pixels.width, rect.height() / pixels.height)
            val box = RectF(rect.centerX() - pixels.width * scale / 2, rect.centerY() - pixels.height * scale / 2,
                rect.centerX() + pixels.width * scale / 2, rect.centerY() + pixels.height * scale / 2)
            paint.alpha = (255 * alpha).roundToInt()
            canvas.drawBitmap(pixels, null, box, paint)
        }
    }

    internal data class Origin(val owner: WeakReference<ThingsActivity>, val thingId: Long,
        val parentId: Long?, val mediaKey: String? = null, val privateContent: Boolean = false,
        val folderId: Long? = null)
    internal data class Part(val role: Int, val rect: RectF, val clip: RectF, var pixels: Bitmap?,
        val text: String? = null, val textSize: Float = 0f, val baseline: Float = 0f,
        val padding: Float = 0f, val scale: Float = 1f,
        val lineCount: Int = 0, val ellipsized: Boolean = false)
    internal data class Frame(var pixels: Bitmap?, val pixelsRect: RectF, val rect: RectF, val clip: RectF,
        val radius: Float, val parts: List<Part>, val mediaKey: String?, val lightBars: Boolean,
        val windowWidth: Int, val windowHeight: Int, val background: Drawable?,
        val shadow: ThingDetailTransitionShadow.Style? = null) {
        fun release() { pixels = null; parts.forEach { it.pixels = null } }
    }

    companion object {
        private const val EXTRA_ORIGIN = "thing_detail_return_origin"
        private const val TITLE = 0
        private const val BODY = 1
        private const val MEDIA = 2
        private val origins = linkedMapOf<Long, Origin>()
        private val detailOrigins = WeakHashMap<Activity, Origin>()
        private var nextOrigin = 0L
        private val handler = Handler(Looper.getMainLooper())
        internal var diagnosticObserver: ((String) -> Unit)? = null
        private fun mark(stage: String) { if (BuildConfig.DEBUG) diagnosticObserver?.invoke(stage) }

        fun rememberOrigin(activity: Activity, intent: Intent, card: View, id: Long) {
            if (activity !is ThingsActivity) return
            origins.entries.removeAll { it.value.owner.get()?.let { owner -> owner === activity || owner.isDestroyed } != false }
            while (origins.size >= 8) origins.remove(origins.keys.first())
            val token = ++nextOrigin
            origins[token] = Origin(WeakReference(activity), id, parentIdentity(card))
            intent.putExtra(EXTRA_ORIGIN, token)
        }

        /** 一次性交给实际打开的详情实例；复制 Intent、通知重入和重建都不能继承旧窗口的来源。 */
        fun bindOrigin(activity: Activity, thingId: Long) {
            val token = activity.intent.getLongExtra(EXTRA_ORIGIN, 0)
            activity.intent.removeExtra(EXTRA_ORIGIN)
            val route = origins.remove(token) ?: return
            val owner = route.owner.get() ?: return
            if (route.thingId == thingId && owner.taskId == activity.taskId &&
                !owner.isDestroyed && !owner.isFinishing) detailOrigins[activity] = route
        }

        fun forgetOrigin(activity: Activity) {
            detailOrigins.remove(activity)
            origins.remove(activity.intent.getLongExtra(EXTRA_ORIGIN, 0))
            activity.intent.removeExtra(EXTRA_ORIGIN)
        }

        /** 仅在业务已决定退出后调用；失败也只执行一次原有 finish。 */
        fun captureAndFinish(activity: Activity, thing: Thing,
                             entering: ThingDetailTransition? = null, finish: () -> Unit): Boolean {
            val route = detailOrigins.remove(activity) ?: return false
            val owner = route.owner.get() ?: return false
            val host = activity.window.decorView
            if (route.thingId != thing.id || owner.taskId != activity.taskId ||
                owner.isDestroyed || owner.isFinishing || !ValueAnimator.areAnimatorsEnabled() ||
                !activity.hasWindowFocus() || host.width <= 0 ||
                ViewCompat.getRootWindowInsets(host)?.isVisible(WindowInsetsCompat.Type.ime()) == true) return false
            entering?.freezeForReturn()
            val input = UiInteractions.open(activity, "thing-detail-return-capture") ?: return false
            val generation = ThingManager.getInstance(activity)!!.getPrivacyAuthGeneration()
            var completed = false
            val lifecycle = (activity as? LifecycleOwner)?.lifecycle
            lateinit var observer: LifecycleEventObserver
            lateinit var timeout: Runnable
            fun complete(frame: Frame?) {
                if (completed) { frame?.release(); return }
                completed = true
                mark("source-ready")
                handler.removeCallbacks(timeout)
                lifecycle?.removeObserver(observer)
                input.release()
                if (frame != null && !owner.isDestroyed && !owner.isFinishing &&
                    generation == ThingManager.getInstance(activity)!!.getPrivacyAuthGeneration() &&
                    host.width == owner.window.decorView.width && host.height == owner.window.decorView.height) {
                    owner.acceptDetailReturn(frame, route.copy(mediaKey = frame.mediaKey,
                        privateContent = ThingPrivacyResolver.isEffectivelyPrivate(activity, thing),
                        folderId = thing.folderId))
                } else frame?.release()
                finish()
                activity.overridePendingTransition(0, 0)
            }
            observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) complete(null)
            }
            timeout = Runnable { complete(null) }
            lifecycle?.addObserver(observer)
            handler.postDelayed(timeout, 700)
            mark("capture")
            if (entering != null) entering.captureReturnFrame(::complete)
            else captureFrame(host, host, true, ThingCardMediaHelper.resolveEffectiveMediaSource(thing)?.typePathName,
                WindowInsetsControllerCompat(activity.window, host).isAppearanceLightStatusBars, ::complete)
            return true
        }

        private fun parentIdentity(card: View): Long? {
            var current = card.parent as? View
            while (current != null) {
                (current.getTag(R.id.tag_thing_card_bound_stable_id) as? Long)?.takeIf { it < 0L }?.let { return it }
                current = current.parent as? View
            }
            return null
        }

        private fun findTarget(list: RecyclerView, route: Origin): View? {
            val cards = descendants(list).filter {
                it.getTag(R.id.tag_thing_card_bound_stable_id) == route.thingId
            }.mapNotNull { bound -> bound.findViewById<View>(R.id.cv_thing)?.takeIf {
                val holder = list.findContainingViewHolder(it)
                val position = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
                val boundId = holder?.itemView?.getTag(R.id.tag_thing_card_bound_stable_id)
                // 被移除的动画子节点可能暂时仍挂在 ViewGroup，不能把它当成当前列表成员。
                position != RecyclerView.NO_POSITION &&
                    boundId == (list.adapter as? ThingsAdapter)?.presentedEntryAt(position)?.stableId &&
                    it.isAttachedToWindow && it.alpha == 1f && visibleBounds(it, list.rootView) != null
            } }.distinct().toList()
            return cards.singleOrNull { parentIdentity(it) == route.parentId } ?: cards.singleOrNull()
        }

        private fun captureFrame(view: View, host: View, detail: Boolean, mediaKey: String?,
                                 light: Boolean, done: (Frame?) -> Unit) {
            val rect = if (view === host) RectF(0f, 0f, host.width.toFloat(), host.height.toFloat()) else bounds(view, host)
            val visible = if (view === host) rect else visibleBounds(view, host)
            if (visible == null || rect.isEmpty) { done(null); return }
            val parts = mutableListOf<Part>()
            for ((id, role) in if (detail) listOf(R.id.et_title to TITLE, R.id.et_content to BODY)
                else listOf(R.id.tv_thing_title to TITLE, R.id.tv_thing_content to BODY)) {
                val text = view.findViewById<TextView>(id) ?: continue
                val clip = visibleBounds(text, host) ?: continue
                if (text.text.isBlank() || text.width.toLong() * text.height > host.width.toLong() * host.height) continue
                val pixels = runCatching {
                    Bitmap.createBitmap(text.width, text.height, Bitmap.Config.ARGB_8888).also { text.draw(Canvas(it)) }
                }.getOrNull() ?: continue
                val box = bounds(text, host)
                parts += Part(role, box, clip, pixels, text.text.toString(), text.textSize,
                    text.baseline.toFloat(), text.totalPaddingLeft.toFloat(), box.width() / text.width,
                    text.lineCount, text.layout?.let { layout ->
                        (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }
                    } == true)
            }
            val media = if (detail) descendants(view).filterIsInstance<ImageView>().firstOrNull {
                mediaKey != null && it.getTag(R.id.tag_detail_attachment_bound_source) == mediaKey &&
                    it.drawable != null && visibleBounds(it, host) != null
            } else listOf(R.id.iv_thing_image, R.id.iv_thing_media_background).mapNotNull {
                view.findViewById<ImageView>(it)
            }.firstOrNull { it.drawable != null && visibleBounds(it, host) != null }
            val boundedMedia = media?.takeIf { it.width.toLong() * it.height <= host.width.toLong() * host.height }
            var remaining = if (boundedMedia == null) 1 else 2
            var scene: Bitmap? = null
            val scale = rect.width() / view.width
            val clip = Rect(((visible.left - rect.left) / scale).roundToInt(),
                ((visible.top - rect.top) / scale).roundToInt(),
                ((visible.right - rect.left) / scale).roundToInt(),
                ((visible.bottom - rect.top) / scale).roundToInt())
            fun captured() {
                if (--remaining != 0) return
                done(scene?.let { Frame(it, RectF(visible), rect, RectF(visible),
                    (view as? CardView)?.radius ?: 0f, parts, mediaKey, light, host.width, host.height,
                    (if (detail) view.findViewById<View>(R.id.fl_root_detail)?.background else view.background)
                        ?.constantState?.newDrawable()?.mutate(),
                    if (detail) null else ThingDetailTransitionShadow.Style.capture(view, host)) })
            }
            capturePixels(view, if (view === host) null else clip) { scene = it; captured() }
            boundedMedia?.let { image ->
                val part = Part(MEDIA, bounds(image, host), visibleBounds(image, host)!!, null)
                parts += part
                captureMedia(image) { part.pixels = it; captured() }
            }
        }

        /** GIF 当前显示帧已在内存；只复制当前画面，不再为该元素单独创建离屏渲染器。 */
        private fun captureMedia(image: ImageView, done: (Bitmap?) -> Unit) {
            if (image.drawable is GifDrawable && !image.clipToOutline && image.background == null &&
                image.foreground == null) {
                val pixels = runCatching {
                    Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also {
                        image.draw(Canvas(it))
                    }
                }.getOrNull()
                if (pixels != null) {
                    mark("gif-frame-snapshot")
                    done(pixels)
                    return
                }
            }
            ParticleDismissController.captureContent(image, done = done)
        }

        private fun capturePixels(view: View, clip: Rect?, done: (Bitmap?) -> Unit) {
            if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated && clip == null) {
                ParticleHardwareSnapshot.captureKeepingHardware(view, done)
            } else ParticleDismissController.captureContent(view, clip, done)
        }

        private fun lerp(a: RectF, b: RectF, t: Float) = RectF(a.left + (b.left - a.left) * t,
            a.top + (b.top - a.top) * t, a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t)
        private fun fraction(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)
    }
}
