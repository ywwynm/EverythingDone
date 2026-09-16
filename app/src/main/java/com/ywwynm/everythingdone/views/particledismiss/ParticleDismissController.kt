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
import android.graphics.Rect
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.ViewTreeObserver
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.annotation.RequiresApi
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.utils.AnimatorDurationScale
import java.time.Duration
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
    private val warmupStarted = AtomicBoolean()
    private val finishingHosts = WeakHashMap<Activity, Boolean>()
    private val pendingDismissCaptures = WeakHashMap<Activity, Int>()

    /** 透明 Activity 不能在 Dialog.onDismiss 时销毁仍在播放消散的宿主窗口。 */
    fun finishAfterAnimations(activity: Activity, finish: Runnable): Boolean {
        if (finishingHosts.containsKey(activity)) return true
        activity.window.decorView as? ViewGroup ?: return false
        fun activeOverlays() = ParticleDismissOverlay.activeIn(activity)
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
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runCatching {
                val rules = ParticleMicroflakeRenderer.sharedResources(context.applicationContext.assets).rules
                val pixels = IntArray(256 * 256) { -1 }
                ParticleMicroflakeModel.build(240f, 320f, pixels, 256, 256, 65f, 0L, rules)
                val material = ParticleMicroflakeModel.build(240f, 320f, pixels, 256, 256, 65f, 1L, rules)
                ParticleMicroflakeGpuWarmup.run(context.applicationContext.assets, material)
            }.onFailure { Log.w("ParticleMicroflake", "材料预热未完成，关闭时正常构建", it) }
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
        // 下面还压着别的弹窗时，粒子必须抬到一个全屏承载窗口上，否则会被它盖住。
        val elevated = acquireElevated(activity, dialog)
        val started = startContent(activity, window, window.decorView, touchInWindow,
            onAnimationStarted, onOverlayShown, useDefaultTouchDistance,
            elevatedDecor = elevated)
        if (!started && elevated != null) ParticleElevatedHost.release(activity)
        return started
    }

    /**
     * 需要抬升就取一块全屏画布，否则返回 null（走 Activity 那张常驻表面，与以前逐帧一致）。
     * 取到的必须配一次 [ParticleElevatedHost.release]。
     */
    private fun acquireElevated(activity: Activity, dialog: Dialog?): ViewGroup? {
        if (!ParticleDialogStack.needsElevation(activity, dialog)) {
            if (BuildConfig.DEBUG) {
                Log.i(ParticleMicroflakeRenderer.TAG,
                    "elevate=no stack=${ParticleDialogStack.stackedCount(activity)} " +
                        "dialog=${dialog?.javaClass?.simpleName}")
            }
            return null
        }
        val decor = ParticleElevatedHost.acquire(activity)
        if (BuildConfig.DEBUG) {
            Log.i(ParticleMicroflakeRenderer.TAG,
                "elevate=${decor != null} stack=${ParticleDialogStack.stackedCount(activity)} " +
                    "dialog=${dialog?.javaClass?.simpleName}")
        }
        return decor
    }

    fun startContent(
        activity: Activity, window: Window, decor: View, touchInWindow: PointF?,
        onAnimationStarted: Runnable, onOverlayShown: Runnable,
        useDefaultTouchDistance: Boolean = false, onDone: Runnable? = null,
        elevatedDecor: ViewGroup? = null
    ): Boolean {
        cancelAppearance(decor)
        val requestedAtNanos = System.nanoTime()
        // 每个前置拒绝都要能在日志里分辨出来：这一串里任何一条命中，弹窗都会以系统默认
        // 动画退出，观感就是「消散没播」，而此前一条日志都没有。
        fun reject(reason: String): Boolean {
            if (BuildConfig.DEBUG) Log.w(ParticleMicroflakeRenderer.TAG, "消散未受理：$reason")
            return false
        }
        if (Looper.myLooper() != Looper.getMainLooper()) return reject("不在主线程")
        if (!ValueAnimator.areAnimatorsEnabled()) return reject("系统动画已关闭")
        if (!decor.isAttachedToWindow) return reject("弹窗 decor 未附着")
        if (decor.width <= 0 || decor.height <= 0) return reject("弹窗尺寸为 0（${decor.width}x${decor.height}）")
        if (activity.isFinishing || activity.isDestroyed || activity.isChangingConfigurations) {
            return reject("宿主正在结束或重建 finishing=${activity.isFinishing} " +
                "destroyed=${activity.isDestroyed} changingConfig=${activity.isChangingConfigurations}")
        }
        // 承载窗口刚 show 出来时 decor 要等下一次遍历才附着；宁可退回 Activity 那张表面
        // （层级仍是旧的），也不能因此整场不播。
        val elevated = elevatedDecor?.takeIf { it.isAttachedToWindow }
        if (elevatedDecor != null && elevated == null) ParticleElevatedHost.release(activity)
        val hostDecor = elevated
            ?: (activity.window.decorView as? ViewGroup ?: return reject("宿主没有 decor"))
        if (!hostDecor.isAttachedToWindow) return reject("宿主 decor 未附着")
        // 1.2：不再改任何窗口属性。刷新率改由粒子自己的 Surface 投一次无缝 60 Hz
        // （见 ParticleWindowSurface.applyFrameRate）。
        // 一窗一 Surface：在请求这一刻就把宿主窗口的粒子 SurfaceView 建起来，首次动画不必
        // 再等表面创建，也避免在别的遍历过程中往 DecorView 加子 View。
        ParticleWindowSurface.of(activity, hostDecor)
        val feedback = ParticleTouchFeedback.begin(activity, decor)
        val releaseContent = ParticleSnapshot.hold(decor)
        pendingDismissCaptures[activity] = (pendingDismissCaptures[activity] ?: 0) + 1
        val capture = {
            // 消散一律先走离屏硬件抓图，有没有可见的独立 Surface 都一样（有的话再逐个
            // PixelCopy 合成回去）。窗口 PixelCopy 只作回退。理由有两条，都来自魅族
            // 20 Infinity（Flyme / Android 15）的实测：它对「调整颜色」面板与录音弹窗
            // 用点外部关闭时整个重试窗口都返回 ERROR_SOURCE_NO_DATA、消散整场不播；
            // 而且这一次失败的捕获会连带把弹窗自己闪一下（连续两帧被裁成直角的一块）。
            // 详见 ParticleCaptureRoute.preferOffscreen。
            val offscreen: ((Bitmap?) -> Unit) -> Unit = { done ->
                if (feedback != null) feedback.withoutRipple { captureSnapshotAsync(decor, activity, done) }
                else captureSnapshotAsync(decor, activity, done)
            }
            val captureAtNanos = System.nanoTime()
            val hasSurface = ParticleSnapshot.hasVisibleSurface(decor)
            fun deliver(snapshot: Bitmap?, fallbackUsed: Boolean) {
                try {
                    if (snapshot != null && !activity.isFinishing && !activity.isDestroyed &&
                        hostDecor.isAttachedToWindow && decor.isAttachedToWindow) {
                        val radius = dialogCornerRadius(activity)
                        if (!ParticlePerfTuning.maskOnMaterialThread) applyRoundedCornerMask(snapshot, radius)
                        attachDismissOverlay(activity, hostDecor, decor, snapshot, touchInWindow,
                            onAnimationStarted, onOverlayShown,
                            requestedAtNanos, useDefaultTouchDistance, radius,
                            Runnable { releaseContent(); onDone?.run() }, feedback, elevated)
                    } else {
                        // 走到这里弹窗会以系统默认动画退出，画面上就是「没有粒子」。
                        // 快照为空要能一眼看出走的是哪条抓图路径、卡在哪一步。
                        if (BuildConfig.DEBUG) Log.w(ParticleMicroflakeRenderer.TAG,
                            if (snapshot == null)
                                "消散未开播：快照为空 ${ParticleCaptureDiagnostics.describe()} " +
                                    "耗时=${(System.nanoTime() - captureAtNanos) / 1e6} " +
                                    "有涟漪=${feedback != null} 有Surface=$hasSurface " +
                                    "已回退=$fallbackUsed 尺寸=${decor.width}x${decor.height}"
                            else "消散未开播：交接期宿主或弹窗已不可用 finishing=${activity.isFinishing} " +
                                "destroyed=${activity.isDestroyed} 宿主附着=${hostDecor.isAttachedToWindow} " +
                                "弹窗附着=${decor.isAttachedToWindow} " +
                                "耗时=${(System.nanoTime() - captureAtNanos) / 1e6}")
                        releaseContent()
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
            ParticleSnapshot.capture(window, decor, asyncOffscreen = offscreen,
                dismissal = true) { snapshot ->
                // 首选（消散一律是离屏）没拿到图时，用窗口 PixelCopy 再试一次，
                // 而不是直接放弃整场动画。只试这一次。
                val retryable = ParticleCaptureRoute.shouldRetryOtherRoute(
                    snapshotMissing = snapshot == null,
                    contentUsable = decor.isAttachedToWindow &&
                        !activity.isFinishing && !activity.isDestroyed)
                if (!retryable) {
                    if (BuildConfig.DEBUG && hasSurface) ParticleCaptureDump.save(activity, snapshot)
                    deliver(snapshot, false)
                } else {
                    if (BuildConfig.DEBUG) Log.w(ParticleMicroflakeRenderer.TAG,
                        "消散抓图回退：离屏失败，改用窗口 PixelCopy 再试一次 " +
                            ParticleCaptureDiagnostics.describe())
                    ParticleSnapshot.capture(window, decor) { retried -> deliver(retried, true) }
                }
            }
        }
        // 受理这一行是每次消散的锚点：后面的「未开播 / 降级淡出 / 完成 count=」都归到它名下。
        // 时长缩放必须从这里读回——`settings get global` 与框架真正施加的值可能不一致。
        if (BuildConfig.DEBUG) Log.i(ParticleMicroflakeRenderer.TAG,
            "消散受理：尺寸=${decor.width}x${decor.height} 有触点=${touchInWindow != null} " +
                "默认触点距离=$useDefaultTouchDistance 有涟漪=${feedback != null} " +
                "有Surface=${ParticleSnapshot.hasVisibleSurface(decor)} " +
                "时长缩放=${animatorDurationScale(activity)}")
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
        cornerRadiusPx: Float,
        onDone: Runnable?,
        feedback: ParticleTouchFeedback?,
        /** 非空表示这一次被抬到了 [ParticleElevatedHost] 的全屏窗口上，收尾时要归还。 */
        elevatedDecor: ViewGroup?
    ) {
        // 圆角遮罩在材料线程上时，这张快照本身还是直角的——盖回真实弹窗必须自己裁角，
        // 否则交接期弹窗会有一瞬间变成直角。
        val releaseSurfaceCover = ParticleSnapshot.coverSurfaces(
            decor, snapshot, if (ParticlePerfTuning.maskOnMaterialThread) cornerRadiusPx else 0f)
        val dialogLocation = IntArray(2)
        val hostLocation = IntArray(2)
        decor.getLocationOnScreen(dialogLocation)
        hostDecor.getLocationOnScreen(hostLocation)
        val originX = dialogLocation[0] - hostLocation[0]
        val originY = dialogLocation[1] - hostLocation[1]
        val visibleScreen = Rect()
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
                    (visibleScreen.right-dialogLocation[0]).toFloat(), (visibleScreen.bottom-dialogLocation[1]).toFloat()),
            roundedCornerRadiusPx = if (ParticlePerfTuning.maskOnMaterialThread) cornerRadiusPx else 0f
        )
        val overlay = ParticleDismissOverlay(
            activity = activity,
            spec = spec,
            onAnimationStarted = onAnimationStarted,
            onDone = Runnable {
                releaseSurfaceCover?.invoke()
                if (elevatedDecor != null) ParticleElevatedHost.release(activity)
                onDone?.run()
            },
            touchFeedback = feedback,
            hostDecor = elevatedDecor
        )
        ParticleOverlayHost.attach(hostDecor, overlay)
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
        (ParticleSession.TOTAL_DURATION * 1000).toLong()

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
        val elevated = acquireElevated(activity, dialog)
        val started = appearContent(activity, window, window.decorView, true, { dialog.isShowing },
            onAppearanceProgress, onOverlayCreated = onOverlayCreated,
            elevatedDecor = elevated,
            onAppearanceFinished = if (elevated == null) null
                else ({ ParticleElevatedHost.release(activity) }))
        if (!started && elevated != null) ParticleElevatedHost.release(activity)
        return started
    }

    private val appearancePreparations = WeakHashMap<View, Runnable>()

    fun cancelAppearance(decor: View) { appearancePreparations.remove(decor)?.run() }

    /**
     * [appearanceTouchInWindow] 是本次出现的真实触点（[decor] 所在 window 的坐标），
     * 创建页传首页新建按钮的中心。开着 2b 时它决定方向、触点距离与强度；关掉时只记录在
     * spec 里供对比工具使用，方向仍走 [appearanceDirection]。
     * [skipEmptyTail] 允许跳过倒放尾段几乎无粒子的部分（2c）。第四批起弹窗、页内面板与
     * 新卡片默认也启用（[ParticlePerfTuning.dialogAppearanceSkipEmptyTail]），创建页由
     * [ThingCreationTransition] 传 [ParticlePerfTuning.creationAppearanceSkipEmptyTail]。
     * [creationPage] 标出「这是新建记事的入场」，决定要不要用/写提前算好的轨迹。
     */
    fun appearContent(
        activity: Activity, window: Window, decor: View,
        hideWindow: Boolean = false, isShowing: () -> Boolean = { decor.isShown },
        onAppearanceProgress: ((Float) -> Unit)? = null,
        appearanceDirection: Float? = null,
        roundSnapshot: Boolean = true,
        onAppearanceFinished: (() -> Unit)? = null,
        appearanceTouchInWindow: PointF? = null,
        skipEmptyTail: Boolean = ParticlePerfTuning.dialogAppearanceSkipEmptyTail,
        creationPage: Boolean = false,
        prewarmKey: ParticleCreationKey? = null,
        traceLabel: String? = null,
        elevatedDecor: ViewGroup? = null,
        onOverlayCreated: (ParticleDismissOverlay) -> Unit
    ): Boolean {
        val requestedAtNanos = System.nanoTime()
        traceLabel?.let { ParticleLaunchTrace.mark("$it-request") }
        fun reject(reason: String): Boolean {
            if (BuildConfig.DEBUG) Log.w(ParticleMicroflakeRenderer.TAG, "出现未受理：$reason")
            return false
        }
        if (Looper.myLooper() != Looper.getMainLooper()) return reject("不在主线程")
        if (!ValueAnimator.areAnimatorsEnabled()) return reject("系统动画已关闭")
        if (activity.isFinishing || activity.isDestroyed || activity.isChangingConfigurations) {
            return reject("宿主正在结束或重建")
        }
        // 出现这条路是异步取宿主的：真正用到 hostDecor 是在 preDraw 之后的捕获回调里，
        // 那时承载窗口早已附着。这里**不能**照消散那样做同步的附着检查——承载窗口是刚
        // show 出来的，decor 必然还没附着，一查就回退到 Activity 表面，抬升等于没做，
        // 而窗口已经显示又被保留着，就成了一块没人用的全屏画布（2026-09-16 黑屏根因之一）。
        val elevated = elevatedDecor
        val hostDecor = elevated
            ?: (activity.window.decorView as? ViewGroup ?: return reject("宿主没有 decor"))
        appearancePreparations.remove(decor)?.run()
        ParticleWindowSurface.of(activity, hostDecor)
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
                // 走到这里就是没能开播：内容直接露出，观感是「卡一下然后直接显示」。
                // 这一条要能分清是等不到稳定布局（retries 在涨）还是压根没收到 preDraw。
                if (BuildConfig.DEBUG) Log.w(ParticleMicroflakeRenderer.TAG,
                    "出现未能开播 elapsedMs=${(System.nanoTime() - requestedAtNanos) / 1e6} " +
                        "retries=$layoutRetries captureQueued=$captureQueued " +
                        "attached=${decor.isAttachedToWindow} size=${decor.width}x${decor.height} " +
                        "showing=${runCatching { isShowing() }.getOrDefault(false)}")
                handled = true; removeListener(); appearancePreparations.remove(decor)
                setAlpha(prevAlpha); releaseContent()
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
                    traceLabel?.let { ParticleLaunchTrace.mark("$it-layout") }
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
                        val radius = if (roundSnapshot) dialogCornerRadius(activity) else 0f
                        if (roundSnapshot && !ParticlePerfTuning.maskOnMaterialThread) applyRoundedCornerMask(snapshot, radius)
                        if (BuildConfig.DEBUG) {
                            Log.i(ParticleMicroflakeRenderer.TAG,
                                "出现捕获 layoutMs=${(captureAtNanos-requestedAtNanos)/1e6} captureMs=${(System.nanoTime()-captureAtNanos)/1e6} retries=$layoutRetries")
                        }
                        traceLabel?.let { ParticleLaunchTrace.mark("$it-capture") }
                        val at = IntArray(2); decor.getLocationOnScreen(at)
                        val hostAt = IntArray(2); hostDecor.getLocationOnScreen(hostAt)
                        val x = (at[0]-hostAt[0]).toFloat(); val y = (at[1]-hostAt[1]).toFloat()
                        val angle = Math.toRadians(-(appearanceDirection
                            ?: ParticleAppearanceDirection.degrees(Math.random())).toDouble()).toFloat()
                        val reach = VIRTUAL_TOUCH_FACTOR * hypot(snapshot.width.toFloat(), snapshot.height.toFloat())
                        // 真实触点换算到快照局部坐标；decor 就是被拍下来的那棵树，所以
                        // 减掉它自己在 window 里的位置即可。
                        val local = appearanceTouchInWindow?.let {
                            val inWindow = IntArray(2); decor.getLocationInWindow(inWindow)
                            PointF(it.x - inWindow[0], it.y - inWindow[1])
                        }?.takeIf { it.x.isFinite() && it.y.isFinite() &&
                            hypot(it.x - snapshot.width/2f, it.y - snapshot.height/2f) > DIRECTION_EPSILON_PX }
                        val mode = ParticlePerfTuning.creationAppearanceTouch
                        val realTouch = local?.takeIf { mode >= ParticlePerfTuning.TOUCH_DIRECTION }
                        val visibleScreen = Rect()
                        hostDecor.getWindowVisibleDisplayFrame(visibleScreen)
                        // 第四批 ②：先看能不能用上首页空闲时提前算好的轨迹。命中时种子必须沿用
                        // 那一次预算的，否则观察器与对比工具会把两条路径当成同一个随机。
                        val prepared = if (creationPage)
                            ParticleCreationPrewarm.take(prewarmKey, snapshot.width, snapshot.height, x, y)
                            else null
                        val spec = ParticleDismissSpec(snapshot = snapshot, originXPx = x, originYPx = y,
                            // 有真实触点就直接用它：fromSpec 只从「中心 → 触点」这条射线取方向，
                            // 射线上取哪一点都一样，用真实点最直白。
                            virtualTouchXPx = if (realTouch != null) x + realTouch.x
                                else x + snapshot.width/2f + cos(angle)*reach,
                            virtualTouchYPx = if (realTouch != null) y + realTouch.y
                                else y + snapshot.height/2f + sin(angle)*reach,
                            durationScale = animatorDurationScale(activity),
                            hashSeed = prepared?.seed ?: (Math.random()*Int.MAX_VALUE).toInt(),
                            requestedAtNanos = requestedAtNanos,
                            reverse = true, playbackDurationS = ParticleReversePlan.APPEARANCE_SECONDS,
                            // 方向、触点距离、触点强度是三个可以分开采用的输入：3b 的归因轮证明
                            // 「扇形汇入」的观感来自距离归零，而每采样预计算变贵来自强度归零。
                            touchGap = realTouch?.takeIf { mode >= ParticlePerfTuning.TOUCH_DIRECTION_GAP }?.let {
                                ParticleFlowGeometry.touchGap(it.x, it.y,
                                    snapshot.width.toFloat(), snapshot.height.toFloat()) },
                            touchStrength = realTouch?.takeIf { mode >= ParticlePerfTuning.TOUCH_FULL }?.let {
                                ParticleFlowGeometry.touchStrength(it.x, it.y,
                                    snapshot.width.toFloat(), snapshot.height.toFloat(),
                                    (visibleScreen.left - at[0]).toFloat(), (visibleScreen.top - at[1]).toFloat(),
                                    (visibleScreen.right - at[0]).toFloat(), (visibleScreen.bottom - at[1]).toFloat()) },
                            roundedCornerRadiusPx = if (ParticlePerfTuning.maskOnMaterialThread) radius else 0f,
                            appearanceTouchXPx = local?.let { x + it.x },
                            appearanceTouchYPx = local?.let { y + it.y },
                            skipEmptyTail = skipEmptyTail,
                            creationPage = creationPage,
                            // 第五批：这一屏就是这个键对应的创建页首屏，落盘后冷启动可以直接用。
                            prewarmKey = if (creationPage) prewarmKey else null)
                        var overlayRef: ParticleDismissOverlay? = null
                        val overlay = ParticleDismissOverlay(activity, spec, onDone = Runnable {
                            onAppearanceProgress?.invoke(1f)
                            setAlpha(prevAlpha)
                            runAfterShown(decor, OVERLAY_SHOWN_TIMEOUT_MS) {
                                releaseContent(); overlayRef?.release()
                            }
                        }, touchFeedback = appearanceFeedback,
                            onPresentedProgress = onAppearanceProgress, prepared = prepared,
                            hostDecor = elevated)
                        overlayRef = overlay
                        overlay.afterRelease(Runnable { onAppearanceFinished?.invoke() })
                        onOverlayCreated(overlay)
                        ParticleOverlayHost.attach(hostDecor, overlay)
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
        if (Build.VERSION.SDK_INT >= 29 && !ParticleSnapshot.hasVisibleSurface(decor)) {
            ParticleHardwareSnapshot.capture(decor, done)
        } else {
            ParticleCaptureDiagnostics.begin("离屏软件绘制")
            done(captureSnapshot(decor, context, softwareOnly = true))
        }
    }

    /** 通用内容保留自身轮廓；不能套用 Dialog 的圆角遮罩。 */
    fun captureContent(view: View, done: (Bitmap?) -> Unit) {
        if (view.width <= 0 || view.height <= 0) { done(null); return }
        if (Build.VERSION.SDK_INT >= 29) ParticleHardwareSnapshot.capture(view, done)
        else {
            val bitmap = try { captureViaSoftwareDraw(view) }
            catch (error: RuntimeException) {
                Log.w(ParticleMicroflakeRenderer.TAG, "内容快照失败", error)
                null
            }
            done(bitmap)
        }
    }

    /**
     * [softwareOnly] 用于「弹窗里有可见 `SurfaceView`」这一格：**绝不能用第二个
     * `HardwareRenderer` 去画活着的视图树**。
     *
     * Android 15 起 `SurfaceView` 的裁剪由 RenderThread 驱动（`Flags.clipSurfaceviews`）：
     * 它的 SurfaceControl 位置与 crop 来自「这一帧把它的 RenderNode 画到了哪里」，由
     * `replacePositionUpdateListener` 上报。离屏渲染器一旦把同一个 RenderNode 再画一遍，
     * 上报的就是离屏那一帧的边界，被应用到**真实的** SurfaceControl 上——魅族 20 Infinity
     * 录屏逐帧实测：消散开始时有连续两帧，海浪那一层被裁成 974×1357（内容位置一个像素
     * 没动，只是可见区域被裁小），观感是弹窗一瞬间变成直角、整屏亮度掉约 10 个灰阶，
     * 随后真实窗口下一帧上报正确边界又自己恢复。
     *
     * 软件 `Canvas` 不走 RenderNode，因此不会触发这条上报。代价是子 View 的
     * `clipToOutline` 不生效、绘制在主线程同步完成；只有这一小类弹窗（录音、音频附件
     * 播放）走这条路，它们的圆角来自窗口背景而不是子 View 的 outline。
     * `SurfaceView` 的透明洞由框架的 `punchHole` 记下，软件画布同样有原生实现。
     */
    private fun captureSnapshot(decor: View, context: Context,
                                softwareOnly: Boolean = false): Bitmap? {
        return try {
            val bitmap = if (!softwareOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                captureViaHardwareRenderer(decor) ?: captureViaSoftwareDraw(decor)
            } else {
                captureViaSoftwareDraw(decor)
            }
            applyRoundedCornerMask(bitmap, context)
            bitmap
        } catch (error: Throwable) {
            ParticleCaptureDiagnostics.note("离屏软件绘制异常=${error.javaClass.simpleName}")
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
    @RequiresApi(Build.VERSION_CODES.Q)
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
                    image.fence.await(Duration.ofMillis(3000))
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
    private fun applyRoundedCornerMask(bitmap: Bitmap, context: Context) =
        applyRoundedCornerMask(bitmap, dialogCornerRadius(context))

    /** 面板圆角半径；0 表示不需要裁角。 */
    internal fun dialogCornerRadius(context: Context): Float =
        context.resources.getDimension(R.dimen.app_chrome_dialog_popup_corner_radius).coerceAtLeast(0f)

    internal fun applyRoundedCornerMask(bitmap: Bitmap, radius: Float) {
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

    /** 全应用唯一的时间基准；API 33+ 就是框架给 ValueAnimator 施加的那个值。 */
    private fun animatorDurationScale(context: Context): Float =
        AnimatorDurationScale.scale(context)

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
