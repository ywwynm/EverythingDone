package com.ywwynm.everythingdone.views.particledismiss

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Choreographer
import android.view.ViewTreeObserver
import android.view.View
import android.view.ViewGroup
import com.ywwynm.everythingdone.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Dialog 粒子消散动画的入口与编排。
 *
 * 使用 PixelCopy 异步取得 Dialog 快照，等待 Activity 上的动画层与首帧均就绪
 * 后释放真实窗口。粒子可越过原窗口边界，背景暗层沿同一逻辑时钟淡出。
 */
internal object ParticleDismissController {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val warmupStarted = java.util.concurrent.atomic.AtomicBoolean()
    private val finishingHosts = java.util.WeakHashMap<Activity, Boolean>()
    private val pendingDismissCaptures = java.util.WeakHashMap<Activity, Int>()

    /** 透明 Activity 不能在 Dialog.onDismiss 时销毁仍在播放消散的宿主窗口。 */
    fun finishAfterAnimations(activity: Activity, finish: Runnable): Boolean {
        if (finishingHosts.containsKey(activity)) return true
        val root = activity.window.decorView as? ViewGroup ?: return false
        fun activeOverlays() = (0 until root.childCount)
            .mapNotNull { root.getChildAt(it) as? ParticleDismissOverlay }
            .filter { it.isDisappearance && it.isAttachedToWindow }
        if ((pendingDismissCaptures[activity] ?: 0) == 0 && activeOverlays().isEmpty()) return false
        finishingHosts[activity] = true
        fun waitUntilReleased() {
            if (activity.isDestroyed) {
                finishingHosts.remove(activity)
                return
            }
            // 认证等回调会在 dismiss() 后同步 finish，此时 PixelCopy 尚未创建动画层。
            if ((pendingDismissCaptures[activity] ?: 0) > 0) {
                mainHandler.postDelayed({ waitUntilReleased() }, 16)
                return
            }
            val overlays = activeOverlays()
            if (overlays.isEmpty()) {
                finishingHosts.remove(activity)
                finish.run()
                return
            }
            var remaining = overlays.size
            overlays.forEach { it.afterRelease {
                remaining--
                if (remaining == 0) mainHandler.post { waitUntilReleased() }
            } }
        }
        waitUntilReleased()
        return true
    }

    /** 首个可见界面或弹窗展示后异步预热数值代码与驱动首用编译，不保存用户快照。 */
    fun warmDismissModel(context: Context) {
        if (!ValueAnimator.areAnimatorsEnabled() || !warmupStarted.compareAndSet(false, true)) return
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runCatching {
                val rules = ParticleMicroflakeRenderer.sharedResources(context.applicationContext.assets).rules
                val pixels = IntArray(256 * 256) { -1 }
                ParticleMicroflakeModel.build(240f, 320f, pixels, 256, 256, 65f, 0L, rules)
                val material = ParticleMicroflakeModel.build(240f, 320f, pixels, 256, 256, 65f, 1L, rules)
                ParticleMicroflakeGpuWarmup.run(context.applicationContext.assets, material)
            }.onFailure { android.util.Log.w("ParticleMicroflake", "材料预热未完成，关闭时正常构建", it) }
        }, "ParticleModelWarmup").start()
    }

    /**
     * 尝试为 [dialog] 启动粒子消散动画。
     *
     * 返回 true 表示动画层已接管：调用方应把窗口退出动画置空并**推迟**真实
     * dismiss——[onOverlayShown] 会在动画层实际上屏后（约 1–2 帧，附 100ms
     * 兜底）在主线程回调恰好一次，调用方在其中清除自身 dim 并执行真实 dismiss。
     * 时序保证画面上每一刻都恰有一层等浓度的暗：接力 dim 先在 Dialog window
     * 底下就位（被挡、无视觉变化）→ 调用方清 dim（底层接力暗层顶上，亮度
     * 连续）→ window 移除（已无 dim 可淡出）——任何一步跨帧都不闪。
     *
     * 返回 false 表示环境不满足，调用方保持系统默认退出行为（不会回调）。
     *
     * [touchInWindow] 表示直接触发本次关闭的触点，返回分发可传入左上虚拟触点；其他
     * 异步完成和代码关闭传 null，统一使用右上虚拟触点。
     */
    fun start(
        dialog: Dialog, touchInWindow: PointF?, onAnimationStarted: Runnable,
        onOverlayShown: Runnable, useDefaultTouchDistance: Boolean = false
    ): Boolean {
        val window = dialog.window ?: return false
        val activity = activityFrom(dialog.context) ?: return false
        return startContent(activity, window, window.decorView, touchInWindow,
            onAnimationStarted, onOverlayShown, useDefaultTouchDistance)
    }

    fun startContent(
        activity: Activity, window: android.view.Window, decor: View, touchInWindow: PointF?,
        onAnimationStarted: Runnable, onOverlayShown: Runnable,
        useDefaultTouchDistance: Boolean = false, onDone: Runnable? = null,
        captureOffscreen: Boolean = false
    ): Boolean {
        cancelAppearance(decor)
        val requestedAtNanos = System.nanoTime()
        if (Looper.myLooper() != Looper.getMainLooper() || !ValueAnimator.areAnimatorsEnabled()) return false
        if (!decor.isAttachedToWindow || decor.width <= 0 || decor.height <= 0) return false
        if (activity.isFinishing || activity.isDestroyed || activity.isChangingConfigurations) return false
        val hostDecor = activity.window.decorView as? ViewGroup ?: return false
        if (!hostDecor.isAttachedToWindow) return false
        val releaseFrameRate = ParticleWindowFrameRate.holdTransition(activity.window, window)
        val feedback = ParticleTouchFeedback.begin(activity, decor)
        val releaseContent = ParticleSnapshot.hold(decor)
        pendingDismissCaptures[activity] = (pendingDismissCaptures[activity] ?: 0) + 1
        val capture = {
            val offscreen: (((Bitmap?) -> Unit) -> Unit)? = if (feedback != null || captureOffscreen) {
                { done ->
                    if (feedback != null) feedback.withoutRipple { captureSnapshotAsync(decor, activity, done) }
                    else captureSnapshotAsync(decor, activity, done)
                }
            } else null
            ParticleSnapshot.capture(window, decor, asyncOffscreen = offscreen) { snapshot ->
                try {
                    if (snapshot != null && !activity.isFinishing && !activity.isDestroyed &&
                        hostDecor.isAttachedToWindow && decor.isAttachedToWindow) {
                        applyRoundedCornerMask(snapshot, activity)
                        attachDismissOverlay(activity, hostDecor, decor, snapshot, touchInWindow,
                            onAnimationStarted, onOverlayShown,
                            requestedAtNanos, useDefaultTouchDistance,
                            Runnable { releaseContent(); releaseFrameRate(); onDone?.run() }, feedback)
                    } else {
                        releaseContent()
                        releaseFrameRate()
                        snapshot?.recycle()
                        onAnimationStarted.run()
                        onOverlayShown.run()
                        onDone?.run()
                    }
                } finally {
                    val pending = (pendingDismissCaptures[activity] ?: 1) - 1
                    if (pending == 0) pendingDismissCaptures.remove(activity)
                    else pendingDismissCaptures[activity] = pending
                }
            }
        }
        // Surface 的窗口洞必须从真实窗口捕获，等反馈自然结束再复制；普通控件可立即准备干净快照。
        if (feedback != null && ParticleSnapshot.hasVisibleSurface(decor)) feedback.afterSettled { capture() }
        else mainHandler.post { capture() }
        return true
    }

    private fun attachDismissOverlay(
        activity: Activity,
        hostDecor: ViewGroup,
        decor: View,
        snapshot: Bitmap,
        touchInWindow: PointF?,
        onAnimationStarted: Runnable,
        onOverlayShown: Runnable,
        requestedAtNanos: Long,
        useDefaultTouchDistance: Boolean,
        onDone: Runnable?,
        feedback: ParticleTouchFeedback?
    ) {
        val releaseSurfaceCover = ParticleSnapshot.coverSurfaces(decor, snapshot)
        val dialogLocation = IntArray(2)
        val hostLocation = IntArray(2)
        decor.getLocationOnScreen(dialogLocation)
        hostDecor.getLocationOnScreen(hostLocation)
        val originX = dialogLocation[0] - hostLocation[0]
        val originY = dialogLocation[1] - hostLocation[1]
        val visibleScreen = android.graphics.Rect()
        hostDecor.getWindowVisibleDisplayFrame(visibleScreen)

        // 消散主方向严格朝直接因果触点。真实方向不设置宽阈值：只在向量
        // 数值上无法归一化时回退。所有无触点关闭统一从正上向右偏 30°，
        // 即右上但仍以上行为主。
        val baseAngle = if (touchInWindow != null) {
            val dx = touchInWindow.x - decor.width / 2f
            val dy = touchInWindow.y - decor.height / 2f
            if (hypot(dx, dy) > DIRECTION_EPSILON_PX) {
                atan2(dy, dx)
            } else {
                DEFAULT_DISMISS_ANGLE_RAD
            }
        } else {
            DEFAULT_DISMISS_ANGLE_RAD
        }
        val tiltRadians = baseAngle
        val reach = VIRTUAL_TOUCH_FACTOR *
            hypot(snapshot.width.toFloat(), snapshot.height.toFloat())
        val centerX = originX + snapshot.width / 2f
        val centerY = originY + snapshot.height / 2f
        val virtualTouchX = centerX + cos(tiltRadians) * reach
        val virtualTouchY = centerY + sin(tiltRadians) * reach
        val spec = ParticleDismissSpec(
            snapshot = snapshot,
            originXPx = originX.toFloat(),
            originYPx = originY.toFloat(),
            virtualTouchXPx = virtualTouchX,
            virtualTouchYPx = virtualTouchY,
            durationScale = animatorDurationScale(activity),
            hashSeed = (Math.random() * Int.MAX_VALUE).toInt(),
            requestedAtNanos = requestedAtNanos,
            touchGap = if (useDefaultTouchDistance || touchInWindow == null) null else
                ParticleFlowGeometry.touchGap(touchInWindow.x, touchInWindow.y, snapshot.width.toFloat(), snapshot.height.toFloat()),
            touchStrength = if (useDefaultTouchDistance || touchInWindow == null) null else
                ParticleFlowGeometry.touchStrength(touchInWindow.x, touchInWindow.y, snapshot.width.toFloat(), snapshot.height.toFloat(),
                    (visibleScreen.left-dialogLocation[0]).toFloat(), (visibleScreen.top-dialogLocation[1]).toFloat(),
                    (visibleScreen.right-dialogLocation[0]).toFloat(), (visibleScreen.bottom-dialogLocation[1]).toFloat())
        )
        val overlay = ParticleDismissOverlay(
            activity = activity,
            spec = spec,
            onAnimationStarted = onAnimationStarted,
            onDone = Runnable { releaseSurfaceCover?.invoke(); onDone?.run() },
            touchFeedback = feedback
        )
        hostDecor.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // 等 overlay（含快照）实际上屏后再通知调用方执行真实 dismiss：overlay
        // 与 Dialog 分属两个 window、渲染管线不同步，同帧提交也可能错开一帧
        // 生效——先就位、后揭开才能保证画面连续。
        var waiting = if (releaseSurfaceCover == null) 1 else 2
        val shown = Runnable { if (--waiting == 0) onOverlayShown.run() }
        runAfterShown(overlay, OVERLAY_SHOWN_TIMEOUT_MS, shown)
        if (releaseSurfaceCover != null) runAfterShown(decor, OVERLAY_SHOWN_TIMEOUT_MS, shown)
    }

    /**
     * 在 [view] 所在 window 的下一帧实际提交后运行 [action]（恰好一次）。
     * preDraw 或下一次 UI vsync 都不能证明 RenderThread 已提交，海浪窗口交接必须等提交回调。
     * [timeoutMs] 兜底保证 action 必然执行（跨窗口衔接不能卡死流程）。
     */
    private fun runAfterShown(view: View, timeoutMs: Long, action: Runnable) {
        var fired = false
        val fire = Runnable {
            if (!fired) {
                fired = true
                action.run()
            }
        }
        view.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    view.viewTreeObserver.removeOnPreDrawListener(this)
                    if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) {
                        view.viewTreeObserver.registerFrameCommitCallback { mainHandler.post(fire) }
                    } else Choreographer.getInstance().postFrameCallback { fire.run() }
                    return true
                }
            }
        )
        // 兜底必须挂全局 Handler：View.postDelayed 在 view 已 detach 时进入
        // RunQueue 等待 re-attach、永不执行——window 被移除后 preDraw 与
        // view 级兜底同时失效，动画层永久残留（2026-08-26"Dialog 凝固"根因）
        mainHandler.postDelayed(fire, timeoutMs)
    }

    /** ViewPropertyAnimator 会自行乘系统缩放；这里只提供逻辑时长，避免重复缩放。 */
    fun dismissAnimatorDurationMs(): Long =
        (ParticleDismissRenderer.TOTAL_DURATION * 1000).toLong()

    /**
     * 为 [dialog] 倒序播放当前共同消散模型：保持完整一秒轨迹、每次随机材料。
     * 先完成首次绘制准备再抓取最终配色，末帧恢复完整原图和真实面板。
     *
     * 返回 true 表示已接管（面板已置透明，动画结束或任何失败路径都会恢复，
     * 附兜底）。[onOverlayCreated] 在动画层创建时回调，调用方应持有引用并在
     * dismiss 拦截时调用其 release()——否则凝聚未完成就 dismiss 时，残留的
     * 动画层会以末帧形态永久盖在界面上（"Dialog 凝固"，2026-08-26 检查更新
     * 场景定位）。
     */
    fun startCondense(dialog: Dialog, onAppearanceProgress: ((Float) -> Unit)? = null,
                      onOverlayCreated: (ParticleDismissOverlay) -> Unit): Boolean {
        val window = dialog.window ?: return false
        val activity = activityFrom(dialog.context) ?: return false
        return appearContent(activity, window, window.decorView, true, { dialog.isShowing },
            onAppearanceProgress, onOverlayCreated = onOverlayCreated)
    }

    private val appearancePreparations = java.util.WeakHashMap<View, Runnable>()

    fun cancelAppearance(decor: View) { appearancePreparations.remove(decor)?.run() }

    fun appearContent(
        activity: Activity, window: android.view.Window, decor: View,
        hideWindow: Boolean = false, isShowing: () -> Boolean = { decor.isShown },
        onAppearanceProgress: ((Float) -> Unit)? = null,
        appearanceDirection: Float? = null,
        roundSnapshot: Boolean = true,
        onAppearanceFinished: (() -> Unit)? = null,
        onOverlayCreated: (ParticleDismissOverlay) -> Unit
    ): Boolean {
        val requestedAtNanos = System.nanoTime()
        if (Looper.myLooper() != Looper.getMainLooper() || !ValueAnimator.areAnimatorsEnabled()) return false
        if (activity.isFinishing || activity.isDestroyed || activity.isChangingConfigurations) return false
        val hostDecor = activity.window.decorView as? ViewGroup ?: return false
        appearancePreparations.remove(decor)?.run()
        val releaseFrameRate = ParticleWindowFrameRate.holdTransition(activity.window, window)
        val appearanceFeedback = ParticleTouchFeedback.current(activity)
            ?: ParticleTouchFeedback.begin(activity, hostDecor)
        val prevAlpha = if (hideWindow) window.attributes.alpha else decor.alpha
        fun setAlpha(alpha: Float) {
            if (hideWindow) window.attributes = window.attributes.apply { this.alpha = alpha }
            else decor.alpha = alpha
        }
        // Window alpha 隐藏整个合成树，独立 Surface 不会先于面板露出；缓冲本身仍可 PixelCopy。
        setAlpha(0f)
        val releaseContent = ParticleSnapshot.hold(decor)
        var handled = false
        var captureQueued = false
        var layoutRetries = 0
        var listener: ViewTreeObserver.OnPreDrawListener? = null
        fun removeListener() {
            listener?.let { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(it) }
            listener = null
        }
        val restore = Runnable {
            if (!handled) {
                handled = true; removeListener(); appearancePreparations.remove(decor)
                setAlpha(prevAlpha); releaseContent(); releaseFrameRate()
                onAppearanceProgress?.invoke(1f)
                onAppearanceFinished?.invoke()
            }
        }
        appearancePreparations[decor] = restore
        listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (handled || captureQueued) return true
                captureQueued = true
                decor.post capture@{
                    captureQueued = false
                    if (handled) return@capture
                    if (!isShowing() || !decor.isAttachedToWindow || activity.isFinishing || activity.isDestroyed) {
                        restore.run(); return@capture
                    }
                    // 透明宿主与 Dialog 的 onStart 可以先于宿主附着；等待真正布局，不直接跳过。
                    if (!releaseContent.settleLayout() || !hostDecor.isAttachedToWindow || decor.width <= 0 || decor.height <= 0 ||
                        hasPendingLayout(decor) || decor.parent?.isLayoutRequested == true) {
                        layoutRetries++; return@capture
                    }
                    removeListener()
                    captureQueued = true
                    val captureAtNanos = System.nanoTime()
                    ParticleSnapshot.capture(window, decor,
                        asyncOffscreen = { done ->
                            if (roundSnapshot) captureSnapshotAsync(decor, activity, done)
                            else captureContent(decor, done)
                        }) copy@{ snapshot ->
                        if (handled) { snapshot?.recycle(); return@copy }
                        if (snapshot == null || !isShowing() || !decor.isAttachedToWindow ||
                            !hostDecor.isAttachedToWindow || activity.isFinishing || activity.isDestroyed) {
                            snapshot?.recycle(); restore.run(); return@copy
                        }
                        handled = true
                        mainHandler.removeCallbacks(restore)
                        appearancePreparations.remove(decor)
                        if (roundSnapshot) applyRoundedCornerMask(snapshot, activity)
                        if (com.ywwynm.everythingdone.BuildConfig.DEBUG) {
                            android.util.Log.i(ParticleMicroflakeRenderer.TAG,
                                "出现捕获 layoutMs=${(captureAtNanos-requestedAtNanos)/1e6} captureMs=${(System.nanoTime()-captureAtNanos)/1e6} retries=$layoutRetries")
                        }
                        val at = IntArray(2); decor.getLocationOnScreen(at)
                        val hostAt = IntArray(2); hostDecor.getLocationOnScreen(hostAt)
                        val x = (at[0]-hostAt[0]).toFloat(); val y = (at[1]-hostAt[1]).toFloat()
                        val angle = Math.toRadians(-(appearanceDirection
                            ?: ParticleAppearanceDirection.degrees(Math.random())).toDouble()).toFloat()
                        val reach = VIRTUAL_TOUCH_FACTOR * hypot(snapshot.width.toFloat(), snapshot.height.toFloat())
                        val spec = ParticleDismissSpec(snapshot = snapshot, originXPx = x, originYPx = y,
                            virtualTouchXPx = x + snapshot.width/2f + cos(angle)*reach,
                            virtualTouchYPx = y + snapshot.height/2f + sin(angle)*reach,
                            durationScale = animatorDurationScale(activity), hashSeed = (Math.random()*Int.MAX_VALUE).toInt(),
                            requestedAtNanos = requestedAtNanos,
                            reverse = true, playbackDurationS = ParticleReversePlan.APPEARANCE_SECONDS)
                        var overlayRef: ParticleDismissOverlay? = null
                        val overlay = ParticleDismissOverlay(activity, spec, onDone = Runnable {
                            onAppearanceProgress?.invoke(1f)
                            setAlpha(prevAlpha)
                            runAfterShown(decor, OVERLAY_SHOWN_TIMEOUT_MS) {
                                releaseContent(); overlayRef?.release()
                            }
                        }, touchFeedback = appearanceFeedback,
                            onPresentedProgress = onAppearanceProgress)
                        overlayRef = overlay
                        overlay.afterRelease(Runnable { releaseFrameRate(); onAppearanceFinished?.invoke() })
                        onOverlayCreated(overlay)
                        hostDecor.addView(overlay, ViewGroup.LayoutParams(-1, -1))
                    }
                }
                return true
            }
        }
        decor.viewTreeObserver.addOnPreDrawListener(listener)
        // 首次 Surface 尚未提交时允许等待其首帧；成功就立刻开播，不增加固定延迟。
        mainHandler.postDelayed(restore, 1100L)
        return true
    }

    private fun hasPendingLayout(view: View): Boolean {
        // GONE 子树可能保留 requestLayout 标记，却不参加本轮布局。
        if (view.visibility == View.GONE) return false
        if (view.isLayoutRequested) return true
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            if (hasPendingLayout(view.getChildAt(i))) return true
        }
        return false
    }

    /**
     * 凝聚场景的快照：面板在准备阶段 alpha=0，不能从 window surface 复制
     * 可见内容。首次绘制准备完成后通过离屏硬件绘制获取最终配色及裁剪；
     * View.draw 不合成根 View 自身的 alpha，子节点按当前失效状态重新录制。
     * 失败回退软件 draw（子 View 的 outline 裁剪不保留）。
     */
    private fun captureSnapshotAsync(decor: View, context: Context, done: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= 29) ParticleHardwareSnapshot.capture(decor, done)
        else done(captureSnapshot(decor, context))
    }

    /** 通用内容保留自身轮廓；不能套用 Dialog 的圆角遮罩。 */
    fun captureContent(view: View, done: (Bitmap?) -> Unit) {
        if (view.width <= 0 || view.height <= 0) { done(null); return }
        if (Build.VERSION.SDK_INT >= 29) ParticleHardwareSnapshot.capture(view, done)
        else {
            val bitmap = try { captureViaSoftwareDraw(view) }
            catch (error: RuntimeException) {
                android.util.Log.w(ParticleMicroflakeRenderer.TAG, "内容快照失败", error)
                null
            }
            done(bitmap)
        }
    }

    private fun captureSnapshot(decor: View, context: Context): Bitmap? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                captureViaHardwareRenderer(decor) ?: captureViaSoftwareDraw(decor)
            } else {
                captureViaSoftwareDraw(decor)
            }
            applyRoundedCornerMask(bitmap, context)
            bitmap
        } catch (_: Throwable) {
            null
        }
    }

    private fun captureViaSoftwareDraw(decor: View): Bitmap {
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        return bitmap
    }

    /**
     * 加固版离屏硬件渲染（按 2026-08-26 调研清单）：检查 syncAndDraw 返回码
     * （失败静默、不产帧，空图首要嫌疑）、acquireNextImage、API 33+ 等 GPU
     * 完成栅栏（不等则 GPU 繁忙时读到未写完的缓冲）、光源（不设则 elevation
     * 阴影不渲染）、setOpaque(false)、官方释放顺序。
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun captureViaHardwareRenderer(decor: View): Bitmap? {
        val width = decor.width
        val height = decor.height
        var imageReader: ImageReader? = null
        var renderer: HardwareRenderer? = null
        val node = RenderNode("particleCondenseSnapshot")
        return try {
            imageReader = ImageReader.newInstance(
                width, height, PixelFormat.RGBA_8888, 3,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
            )
            renderer = HardwareRenderer()
            renderer.setName("particleCondenseSnapshot")
            renderer.isOpaque = false
            renderer.setSurface(imageReader.surface)
            node.setPosition(0, 0, width, height)
            val recordingCanvas = node.beginRecording(width, height)
            decor.draw(recordingCanvas)
            node.endRecording()
            renderer.setContentRoot(node)
            val metrics = decor.resources.displayMetrics
            val locationOnScreen = IntArray(2)
            decor.getLocationOnScreen(locationOnScreen)
            renderer.setLightSourceGeometry(
                metrics.widthPixels / 2f - locationOnScreen[0],
                -locationOnScreen[1].toFloat(),
                600f * metrics.density,
                800f * metrics.density
            )
            renderer.setLightSourceAlpha(0.039f, 0.19f)
            renderer.start()
            val sync = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            if (sync != HardwareRenderer.SYNC_OK &&
                sync != HardwareRenderer.SYNC_REDRAW_REQUESTED
            ) {
                return null
            }
            val image = imageReader.acquireNextImage() ?: return null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    image.fence.await(java.time.Duration.ofMillis(3000))
                }
                val hardwareBuffer = image.hardwareBuffer ?: return null
                val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
                hardwareBuffer.close()
                // isMutable 必须为 true：后续圆角遮罩要在其上建 Canvas（2026-08-26
                // 探针定位：false 时抛 Immutable bitmap，凝聚整体失效）
                val software = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, true)
                hardwareBitmap?.recycle()
                software
            } finally {
                image.close()
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { node.discardDisplayList() }
            runCatching { renderer?.destroy() }
            runCatching { imageReader?.close() }
        }
    }

    /**
     * 软件 draw 不应用 clipToOutline，内容若溢出面板圆角会在快照四角露出直角，
     * 在 CPU 侧按面板同款圆角半径裁掉。
     */
    private fun applyRoundedCornerMask(bitmap: Bitmap, context: Context) {
        val radius = context.resources.getDimension(R.dimen.app_chrome_dialog_popup_corner_radius)
        if (radius <= 0f) return
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawRoundRect(
            0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), radius, radius, paint
        )
    }

    private fun animatorDurationScale(context: Context): Float =
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ).coerceAtLeast(0.1f)

    private fun activityFrom(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    /** overlay 上屏探测的兜底时限：超时也放行真实 dismiss，Dialog 不能关不掉。 */
    private const val OVERLAY_SHOWN_TIMEOUT_MS = 100L

    /** 虚拟远触点距离 = 因子 × 快照对角线：越小方向汇聚感越强。 */
    private const val VIRTUAL_TOUCH_FACTOR = 1.1f

    /** 无触点或真实触点恰在中心时：从正上向右偏 30°。 */
    private val DEFAULT_DISMISS_ANGLE_RAD = Math.toRadians(-60.0).toFloat()

    /** 只防止零向量归一化，不形成用户可感知的中心回退区域。 */
    private const val DIRECTION_EPSILON_PX = 0.001f
}
