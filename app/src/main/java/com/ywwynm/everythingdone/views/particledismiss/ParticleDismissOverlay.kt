package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 粒子动画的宿主层：挂在 Activity 的 DecorView 上，触摸完全穿透。
 *
 * 第二批之前这一层自带一个 TextureView 与一条 GL 线程。现在粒子画面统一由宿主窗口那一个
 * [ParticleWindowSurface]（`setZOrderOnTop` 的 SurfaceView）呈现，本层只剩两件事：
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
    private val gesture: ParticleGestureProgress? = null
) : FrameLayout(activity) {

    private val host: Activity = activity
    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapshotView: ImageView?
    private var session: ParticleSession? = null
    private var finished = false
    private var doneFired = false
    private var animationStartedFired = false
    private var firstFramePresented = false
    private var presentedProgress = 0f
    private var releaseNotified = false
    private var particleVisible = true
    private val releaseListeners = mutableListOf<Runnable>()
    /** 观察器读的是「帧真正上屏」的时刻；沿用第一批的三元组形状。 */
    internal val textureTimings = java.util.Collections.synchronizedList(mutableListOf<LongArray>())
    internal val isDisappearance get() = !spec.reverse

    internal fun afterRelease(action: Runnable) {
        if (releaseNotified) action.run() else releaseListeners += action
    }

    private fun notifyReleased() {
        if (releaseNotified) return
        releaseNotified = true
        val callbacks = releaseListeners.toList()
        releaseListeners.clear()
        callbacks.forEach { it.run() }
    }

    private val preparation = ParticleMicroflakePreparation(context.assets, resources.displayMetrics.density, spec)

    private val isCondense get() = spec.reverse

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        // 凝聚（出现动画）模式无需快照遮蔽层：动画从散开态开始、面板从无到有
        snapshotView = if (isCondense || gesture != null) null else ImageView(activity).apply {
            setImageBitmap(spec.snapshot)
            scaleType = ImageView.ScaleType.FIT_XY
            // 圆角遮罩改在材料线程完成（1.6），本层可能早它一两帧上屏；按同一半径裁自己的轮廓，
            // 遮罩落到位之前也不会露出直角。遮罩到位后二者重合，是幂等的。
            if (spec.roundedCornerRadiusPx > 0f) {
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
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
        session?.setVisible(value)
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
        val window = ParticleWindowSurface.of(host)
        if (window == null) {
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
            refreshRate = display?.refreshRate ?: 60f,
            listener = listener
        )
        session = created
        created.setVisible(particleVisible)
        window.add(created)
        // 兜底：GL 线程异常卡住时强制收尾，叠加层绝不常驻。
        // 起步这一段要留够余量：着色器首次编译在慢机上要一秒以上（2026-09-03 实测
        // OPD2515 触点到首帧 1.64 s）。真正的时长看门狗在首帧到达时才开始走。
        mainHandler.postDelayed(watchdog, logicalDurationMs() + STARTUP_SLACK_MS)
    }

    override fun onDetachedFromWindow() {
        finished = true
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
        fireAnimationStarted()
    }

    private fun onPresented(progress: Float, target: Long, atNanos: Long) {
        // 观察器按「标记位 0 + 墙钟 + 与提交对应的时间戳」解析，第一批是 TextureView 的
        // onSurfaceTextureUpdated，第二批换成「这一帧确认上屏」，形状保持一致。
        if (com.ywwynm.everythingdone.BuildConfig.DEBUG) textureTimings += longArrayOf(0, atNanos, target)
        if (finished) return
        presentedProgress = progress
        onPresentedProgress?.invoke(progress)
    }

    private fun onSessionFinished(completed: Boolean) {
        if (finished) return
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
        else { fireAnimationStarted(); fallbackFadeOut() }
    }

    /** 宿主在真实内容确认上屏后调用，移除本层。 */
    fun release() {
        if (finished) return
        if (gesture != null && session?.cancelled == false) {
            // 左滑取消：真实卡片刚被恢复，必须等它上屏之后再撤粒子（先显后撤）。
            afterHostFrame { removeSelf() }
            return
        }
        removeSelf()
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
        if (current?.snapshotConsumed == true && preparation.isFinished) {
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

        /** 首帧之后允许的额外等待。 */
        const val RUNNING_SLACK_MS = 700L

        const val FALLBACK_FADE_MS = 240f

        /** 等宿主窗口出一帧的兜底时限。 */
        private const val HOST_FRAME_TIMEOUT_MS = 100L
    }
}
