package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.graphics.Outline
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.ywwynm.everythingdone.BuildConfig
import java.util.Collections

/**
 * 粒子动画的宿主层：挂在 Activity 的 DecorView 上，触摸完全穿透。
 *
 * 第二批之前这一层自带一个 TextureView 与一条 GL 线程。现在粒子画面统一由宿主窗口那一个
 * [ParticleWindowSurface]（`setZOrderOnTop` 的 SurfaceView）呈现。Android 14 起卡片也复用
 * 此 Surface，只在交接时同步显示含阴影的快照；旧系统的卡片沿用 TextureView。其余职责：
 *
 * 1. 承接快照的 ImageView——精确摆在原 Dialog 位置，遮蔽 EGL 建链与预计算的首帧延迟，
 *    也是 GL 失败时的降级淡出载体；粒子首帧确认上屏后隐藏；
 * 2. 生命周期：登记/注销会话、看门狗、完成与释放通知。
 *
 * 背景暗层不在这里——它由 [DialogDimLayer] 从 Dialog 出现起全程管理。
 */
internal class ParticleDismissOverlay(
    activity: Activity,
    private val spec: ParticleDismissSpec,
    private val onAnimationStarted: Runnable? = null,
    private val onDone: Runnable? = null,
    private val touchFeedback: ParticleTouchFeedback? = null,
    private val onPresentedProgress: ((Float) -> Unit)? = null,
    private val gesture: ParticleGestureProgress? = null,
    /** 第四批 ②：命中提前算好的轨迹时由 [ParticleDismissController] 传进来。 */
    private val prepared: ParticlePreparedAppearance? = null,
    /**
     * 这一层挂在哪个窗口的 decor 上。为空即 Activity 自己的 decor（绝大多数情况）；
     * 弹窗叠弹窗时由 [ParticleDismissController] 传 [ParticleElevatedHost] 的全屏画布，
     * 粒子才能盖住下面那个弹窗。
     */
    private val hostDecor: android.view.ViewGroup? = null,
    private val cardSource: View? = null,
    /**
     * 收尾时要不要把交接快照重新显示出来、让 [cardSource] 回到可见。
     *
     * 卡片那条默认为真（滑动取消要把卡片放回去）。**消散**那条必须传 false：
     * 内容已经散掉了，末尾再显示一次静态快照等于整块闪回一下。
     */
    private val restoreSourceOnRelease: Boolean = true,
    cardSources: List<View>? = null,
    private val onBodyReleased: Runnable? = null
) : FrameLayout(activity) {

    private val host: Activity = activity
    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapshotView: ImageView?
    private var session: ParticleSession? = null
    private val synchronizedCard = spec.inWindow && cardSource != null && ParticleCardSurfaceHandoff.supported()
    private val sourceViews = (cardSources ?: listOfNotNull(cardSource)).toMutableList()
    private var bodyReleased = false
    private var windowSurface: ParticleWindowSurface? = null
    private var cardHandoff: ParticleCardSurfaceHandoff? = null
    private var handoffPreparing = false
    private var returningToSource = false
    private var sourceRestored = false
    private val sourceRestorations = mutableListOf<() -> Unit>()
    private var finished = false
    private var doneFired = false
    private var animationStartedFired = false
    private var firstFramePresented = false
    private var presentedProgress = 0f
    private var releaseNotified = false
    private var particleVisible = true
    /** 会话根本没建起来（宿主窗口拿不到粒子 Surface），与「会话跑起来之后失败」要分开记。 */
    private var noParticleSurface = false
    private val releaseListeners = mutableListOf<Runnable>()
    /** 观察器读的是「帧真正上屏」的时刻；沿用第一批的三元组形状。 */
    internal val textureTimings = Collections.synchronizedList(mutableListOf<LongArray>())
    internal val isDisappearance get() = !spec.reverse
    internal val hasVisibleBody get() = isDisappearance &&
        !finished && presentedProgress < (session?.bodyGoneProgress ?: 1f)
    internal val belowNativeForeground get() = spec.inWindow && !synchronizedCard
    internal val nativeCardSource get() = cardSource

    internal fun afterRelease(action: Runnable) {
        if (releaseNotified) action.run() else releaseListeners += action
    }

    private fun notifyReleased() {
        if (releaseNotified) return
        releaseNotified = true
        // U9：一轮动画收尾时把这一轮的呈现比例交给自动调档的规则（默认关闭）。
        runCatching { ParticleFrameStats.roundFinished(context) }
        val callbacks = releaseListeners.toList()
        releaseListeners.clear()
        callbacks.forEach { it.run() }
    }

    // 命中预算时只解像素、不建材：材料与轨迹都是现成的，那 40–70 ms 直接省掉。
    private val preparation = ParticleMicroflakePreparation(
        context.assets, resources.displayMetrics.density, spec, deferred = prepared != null)

    private val isCondense get() = spec.reverse

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        // 凝聚（出现动画）模式无需快照遮蔽层：动画从散开态开始、面板从无到有。
        // 走窗口内原子交接时也不要它：那条路的遮蔽由 [ParticleCardSurfaceHandoff] 自有的
        // SurfaceControl 承担，两份一起画会让**半透明的投影**叠成两层，交接那一两帧明显变深。
        snapshotView = if (isCondense || gesture != null || synchronizedCard) null
            else ImageView(activity).apply {
            setImageBitmap(spec.snapshot)
            scaleType = ImageView.ScaleType.FIT_XY
            // 圆角遮罩改在材料线程完成（1.6），本层可能早它一两帧上屏；按同一半径裁自己的轮廓，
            // 遮罩落到位之前也不会露出直角。遮罩到位后二者重合，是幂等的。
            if (spec.roundedCornerRadiusPx > 0f) {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, spec.roundedCornerRadiusPx)
                    }
                }
                clipToOutline = true
            }
        }
        if (snapshotView != null) {
            // 物理 LEFT|TOP + margin 绝对定位，不受 RTL 镜像影响
            addView(snapshotView, LayoutParams(spec.snapshot.width, spec.snapshot.height).apply {
                gravity = Gravity.LEFT or Gravity.TOP
                leftMargin = spec.originXPx.toInt()
                topMargin = spec.originYPx.toInt()
            })
        }
    }

    /** 兜底移除自己的那个任务，首帧到达后要换成新的一份，所以留着引用。 */
    private val watchdog = Runnable { removeSelf() }

    private fun logicalDurationMs(): Long {
        val logicalDuration =
            if (isCondense) spec.playbackDurationS else ParticleSession.TOTAL_DURATION
        return (logicalDuration * spec.durationScale * 1000).toLong()
    }

    /** 左滑在识别之前不显示粒子。会话尚未建立时先记住，建立时一起应用。 */
    fun setParticleVisible(value: Boolean) {
        particleVisible = value
        if (synchronizedCard && value) startCardWhenReady()
        else session?.setVisible(value)
    }

    private fun startCardWhenReady() {
        val handoff = cardHandoff ?: return
        val current = session ?: return
        val window = windowSurface ?: return
        if (finished || returningToSource || !particleVisible) return
        if (isCondense) {
            handoff.keepSourceHidden {
                if (!finished && !returningToSource) current.setVisible(true)
            }
        } else handoff.enter { transaction ->
            if (finished || returningToSource) transaction.close()
            else window.startCardFrame(current, transaction)
        }
    }

    private val listener = object : ParticleSession.Listener {
        override fun onFirstFramePresented() {
            mainHandler.post { onFirstFrame() }
        }

        override fun onPresented(target: Long, progress: Float) {
            val at = System.nanoTime()
            mainHandler.post { onPresented(progress, target, at) }
        }

        override fun onFinished(completed: Boolean) {
            mainHandler.post { onSessionFinished(completed) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 挂进 ViewGroupOverlay 之后不再是 DecorView 的子 View，遍历子节点找不到它；
        // 需要「当前有哪些动画层」的两处（透明宿主延迟 finish、debug 观察器）改查这份登记。
        synchronized(active) { active += this }
        val window = if (spec.inWindow && !synchronizedCard) ParticleWindowSurface.inWindow(host, this) {
            if (!finished) fireAnimationStarted()
        } else ParticleWindowSurface.of(host, hostDecor ?: (host.window?.decorView as? ViewGroup))
        if (window == null) {
            // 会话建不起来，提前算好的轨迹也就没人接手了——必须当场还回去，否则那份
            // GPU 资源（历史缓冲 + 前景纹理）会一直挂着。
            prepared?.let { ParticleCreationPrewarm.rejected(it, "宿主窗口没有粒子 Surface") }
            noParticleSurface = true
            mainHandler.post { onSessionFinished(false) }
            return
        }
        val created = ParticleSession(
            assets = context.assets,
            density = resources.displayMetrics.density,
            spec = spec,
            preparation = preparation,
            touchFeedback = touchFeedback,
            gesture = gesture,
            // 采样数按**粒子层自己投的**帧率算，不读面板此刻的瞬时值：两者在同一次动画里
            // 就会不一致（魅族上预算时 120、点下去时 60），而粒子层始终按投票的节拍出帧。
            refreshRate = ParticleWindowSurface.VOTED_FRAME_RATE,
            listener = listener,
            prepared = prepared
        )
        session = created
        windowSurface = window
        created.setVisible(particleVisible && !synchronizedCard)
        window.add(created)
        if (synchronizedCard) {
            handoffPreparing = true
            ParticleCardSurfaceHandoff.prepare(checkNotNull(cardSource), spec,
                window.view as android.view.SurfaceView,
                onForegroundFailure = { if (!finished) onSessionFinished(false) },
                sources = sourceViews) { handoff ->
                handoffPreparing = false
                if (finished) {
                    handoff?.dispose()
                    if (created.snapshotConsumed && preparation.isFinished) spec.snapshot.recycle()
                } else if (handoff == null) onSessionFinished(false)
                else { cardHandoff = handoff; startCardWhenReady() }
            }
        }
        // 兜底：GL 线程异常卡住时强制收尾，叠加层绝不常驻。
        // 起步这一段要留够余量：着色器首次编译在慢机上要一秒以上（2026-09-03 实测
        // OPD2515 触点到首帧 1.64 s）。真正的时长看门狗在首帧到达时才开始走。
        mainHandler.postDelayed(watchdog, logicalDurationMs() + STARTUP_SLACK_MS +
            if (spec.deferForeground) DEFERRED_SLACK_MS else 0L)
    }

    override fun onDetachedFromWindow() {
        finished = true
        cardHandoff?.dispose()
        if (synchronizedCard && cardSource != null && !returningToSource) {
            restoreSources()
        }
        synchronized(active) { active -= this }
        session?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
        // detach 发生在 ViewGroup.removeView 尚未更新子节点数组时。外部回调
        // 可能移除同一 DecorView 中的承接快照／触摸层，必须等本次移除返回。
        mainHandler.post { fireDone(); notifyReleased() }
    }

    /** 完成通知恰好一次：所有退出路径（正常/降级/超时/detach）都会到达。 */
    private fun fireDone() {
        if (doneFired) return
        doneFired = true
        onDone?.run()
    }

    /** dim 与粒子必须从同一个已经上屏的粒子首帧开始。 */
    private fun fireAnimationStarted() {
        if (animationStartedFired) return
        animationStartedFired = true
        // 首帧到了：把看门狗改成「从现在起一个动画时长 + 少量余量」
        mainHandler.removeCallbacks(watchdog)
        if (gesture == null) mainHandler.postDelayed(watchdog, logicalDurationMs() + RUNNING_SLACK_MS)
        onAnimationStarted?.run()
    }

    private fun onFirstFrame() {
        if (finished || firstFramePresented) return
        firstFramePresented = true
        // 粒子已经在 Surface 上了，这时才撤快照——先显后撤，中间没有空白帧。
        snapshotView?.visibility = GONE
        if (!spec.inWindow || synchronizedCard) fireAnimationStarted()
    }

    private fun onPresented(progress: Float, target: Long, atNanos: Long) {
        // 观察器按「标记位 0 + 墙钟 + 与提交对应的时间戳」解析，第一批是 TextureView 的
        // onSurfaceTextureUpdated，第二批换成「这一帧确认上屏」，形状保持一致。
        if (BuildConfig.DEBUG) textureTimings += longArrayOf(0, atNanos, target)
        if (finished) return
        presentedProgress = progress
        onPresentedProgress?.invoke(progress)
        if (!isCondense && !bodyReleased && progress >= (session?.bodyGoneProgress ?: 1f)) {
            bodyReleased = true
            onBodyReleased?.run()
        }
    }

    private fun onSessionFinished(completed: Boolean) {
        if (finished || returningToSource) return
        if (gesture != null) { removeSelf(); return }
        if (completed) {
            if (isCondense) {
                // 凝聚完成：只通知宿主恢复真实面板，本层与 Surface 保持末帧
                // （与面板逐像素相同）——宿主确认面板上屏后才调用 release() 移除，
                // 重叠期内容一致，跨窗口切换的任何一帧都无缝（先显后撤）
                fireDone()
            } else {
                removeSelf()
            }
            return
        }
        // GL 首帧前失败时也必须释放 dim，并与降级淡出同时开始。
        if (isCondense) fireDone()
        else {
            // 观感是「整块面板淡出」而不是粒子。两种来源要分得开：一种是窗口连
            // 粒子 Surface 都没有（会话没建起来），一种是会话跑起来之后失败。
            if (BuildConfig.DEBUG) Log.w(ParticleMicroflakeRenderer.TAG,
                "消散降级淡出：${if (noParticleSurface) "宿主窗口没有粒子 Surface" else "会话失败"} " +
                    "首帧=${firstFramePresented} 进度=$presentedProgress")
            fireAnimationStarted(); fallbackFadeOut()
        }
    }

    private fun restoreSources() {
        sourceViews.forEach { ParticleCardPose.release(it); it.alpha = 1f }
    }

    /** 批量退场已开始补位，解除旧 holder 的所有权，避免收尾改到被复用的新卡片。 */
    fun detachCardSources() {
        restoreSources()
        sourceViews.clear()
    }

    /** 宿主在真实内容确认上屏后调用，移除本层。 */
    fun release() {
        if (finished) return
        if (returningToSource) return
        if (synchronizedCard && cardHandoff != null) {
            releaseToSource(showCard = restoreSourceOnRelease) { restoreSources() }
            return
        }
        if (spec.inWindow) {
            // 原 View 恢复与本层隐藏都属于同一窗口；不能再先显后撤半透明阴影。
            alpha = 0f
            mainHandler.post { removeSelf() }
            return
        }
        if (gesture != null && session?.cancelled == false) {
            // 左滑取消：真实卡片刚被恢复，必须等它上屏之后再撤粒子（先显后撤）。
            afterHostFrame { removeSelf() }
            return
        }
        removeSelf()
    }

    /** 卡片恢复属性与交接快照的撤下绑定到同一窗口帧。 */
    fun releaseToSource(showCard: Boolean = true, restore: () -> Unit) {
        if (returningToSource) {
            if (sourceRestored) restore() else sourceRestorations += restore
            return
        }
        val handoff = cardHandoff
        val window = windowSurface
        val current = session
        val restoreSource = {
            sourceViews.forEach { ParticleCardPose.release(it) }
            sourceRestored = true
            restore()
            val additional = sourceRestorations.toList()
            sourceRestorations.clear()
            additional.forEach { it() }
        }
        if (!finished && handoff != null && window != null && current != null) {
            returningToSource = true
            handoff.leave(window, current, showCard, restoreSource) { removeSelf() }
        } else {
            restoreSource()
            release()
        }
    }

    /** 在宿主窗口的下一帧实际提交之后执行（附兜底，绝不卡住流程）。 */
    private fun afterHostFrame(action: () -> Unit) {
        var fired = false
        val fire = Runnable { if (!fired) { fired = true; action() } }
        if (Build.VERSION.SDK_INT >= 29 && isAttachedToWindow && isHardwareAccelerated &&
            viewTreeObserver.isAlive) {
            runCatching { viewTreeObserver.registerFrameCommitCallback { mainHandler.post(fire) } }
        }
        mainHandler.postDelayed(fire, HOST_FRAME_TIMEOUT_MS)
    }

    /**
     * GL 未能完整播放时的降级：整层淡出。首帧前失败时快照仍可见，观感退化为普通
     * 淡出；中途失败时 Surface 上停留的最后一帧由会话退出时抹掉。
     */
    private fun fallbackFadeOut() {
        animate()
            .alpha(0f)
            .setDuration(FALLBACK_FADE_MS.toLong())
            .withEndAction { removeSelf() }
            .start()
    }

    private fun removeSelf() {
        if (finished) return
        if (synchronizedCard && cardHandoff != null && !returningToSource) {
            releaseToSource(showCard = restoreSourceOnRelease) { restoreSources() }
            return
        }
        finished = true
        fireAnimationStarted()
        mainHandler.removeCallbacksAndMessages(null)
        // 会话引用要留着：debug 观察器在 afterRelease 里按反射读它的时间线，置空就什么都取不到。
        val current = session
        current?.cancel()
        fireDone()
        ParticleOverlayHost.detach(host.window?.decorView as? ViewGroup, this)
        snapshotView?.setImageDrawable(null)
        // 材料线程或 GPU 上传可能仍在读这张位图；两边都取走之后才回收
        if (!handoffPreparing && current?.snapshotConsumed == true && preparation.isFinished) {
            runCatching { spec.snapshot.recycle() }
        }
        notifyReleased()
    }

    internal companion object {
        /** 当前挂着的全部动画层；overlay 路径下这是唯一可枚举的来源。 */
        private val active = mutableListOf<ParticleDismissOverlay>()

        fun activeIn(activity: Activity): List<ParticleDismissOverlay> =
            synchronized(active) { active.filter { it.host === activity } }

        /** 首帧之前允许的额外等待：着色器首次编译在慢机上可能占掉一秒以上。 */
        const val STARTUP_SLACK_MS = 4000L

        /** 真实快照稍后交进来的会话（碰一碰接收提前建的那一层）：还要等列表让位、图片加载与截图。 */
        const val DEFERRED_SLACK_MS = 6000L

        /** 首帧之后允许的额外等待。 */
        const val RUNNING_SLACK_MS = 700L

        const val FALLBACK_FADE_MS = 240f

        /** 等宿主窗口出一帧的兜底时限。 */
        private const val HOST_FRAME_TIMEOUT_MS = 100L
    }
}
