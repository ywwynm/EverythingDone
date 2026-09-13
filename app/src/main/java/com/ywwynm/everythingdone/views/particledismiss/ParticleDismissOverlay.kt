package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 粒子消散动画的全屏叠加层，挂在 Activity 的 DecorView 上，触摸完全穿透。
 * 背景暗层不在这里——它由 [DialogDimLayer] 从 Dialog 出现起全程管理，dismiss
 * 时按粒子动画等长淡出，本层只负责内容（快照与粒子）。
 *
 * 两层结构自底向上：
 * 1. 快照 ImageView——精确摆在原 Dialog 位置，遮蔽 EGL 建链的数十 ms 首帧延迟，
 *    也是 GL 失败时的降级淡出载体；
 * 2. 全屏透明 TextureView——GL 首帧（t=0 与快照逐像素相同）就绪后快照层隐藏，
 *    粒子动画无缝接管。
 */
internal class ParticleDismissOverlay(
    activity: Activity,
    private val spec: ParticleDismissSpec,
    private val onAnimationStarted: Runnable? = null,
    private val onDone: Runnable? = null,
    private val touchFeedback: ParticleTouchFeedback? = null,
    private val onPresentedProgress: ((Float) -> Unit)? = null
) : FrameLayout(activity), TextureView.SurfaceTextureListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapshotView: ImageView?
    private val textureView: TextureView
    private var renderer: ParticleDismissRenderer? = null
    private var finished = false
    private var doneFired = false
    private var animationStartedFired = false
    private var firstTexturePresented = false
    private var glCompleted = false
    private var presentedProgress = 0f
    private val releaseListeners = mutableListOf<Runnable>()
    private var releaseFrameRate: (() -> Unit)? = null
    internal val textureTimings = java.util.Collections.synchronizedList(mutableListOf<LongArray>())
    internal val isDisappearance get() = !spec.reverse

    internal fun afterRelease(action: Runnable) {
        if (finished && parent == null) action.run() else releaseListeners += action
    }

    private fun notifyReleased() {
        val callbacks = releaseListeners.toList()
        releaseListeners.clear()
        callbacks.forEach { it.run() }
    }
    private val preparation = ParticleMicroflakePreparation(context.assets, resources.displayMetrics.density, spec)

    private val isCondense get() = spec.reverse

    // ViewRootImpl 在开始 draw 之前收集提交回调；TextureView 的 updated 通知
    // 在 draw 内才发出，此时登记会落到下一帧。预绘制登记才能交接当前消费的缓冲。
    private val frameObserver = android.view.ViewTreeObserver.OnPreDrawListener {
        val surface = textureView.surfaceTexture
        if (!finished && surface != null && android.os.Build.VERSION.SDK_INT >= 29 && isHardwareAccelerated) {
            viewTreeObserver.registerFrameCommitCallback {
                val timestamp = runCatching { surface.timestamp }.getOrDefault(0L)
                val committedAt = System.nanoTime()
                if (com.ywwynm.everythingdone.BuildConfig.DEBUG) textureTimings += longArrayOf(1, committedAt, timestamp)
                if (Looper.myLooper() == Looper.getMainLooper()) onTextureFrameCommitted(timestamp, committedAt)
                else mainHandler.post { onTextureFrameCommitted(timestamp, committedAt) }
            }
        }
        true
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        textureView = TextureView(activity).apply {
            isOpaque = false
            // GL 线程更新的纹理也需显式表达帧率，避免自适应刷新率把它当静态内容降档。
            if (android.os.Build.VERSION.SDK_INT >= 35) requestedFrameRate = 60f
            surfaceTextureListener = this@ParticleDismissOverlay
        }
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 凝聚（出现动画）模式无需快照遮蔽层：动画从散开态开始、面板从无到有
        snapshotView = if (isCondense) null else ImageView(activity).apply {
            setImageBitmap(spec.snapshot)
            scaleType = ImageView.ScaleType.FIT_XY
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
            if (isCondense) spec.playbackDurationS else ParticleDismissRenderer.TOTAL_DURATION
        return (logicalDuration * spec.durationScale * 1000).toLong()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(frameObserver)
        releaseFrameRate = ParticleWindowFrameRate.hold((context as Activity).window)
        // 兜底：GL 线程异常卡住时强制收尾，叠加层绝不常驻。
        // 起步这一段要留够余量：着色器首次编译在慢机上要一秒以上（2026-09-03 实测
        // OPD2515 触点到首帧 1.64 s）。此前从请求时刻起算 1 s 余量，动画被截成 0.36 s
        // 就整块消失。真正的时长看门狗在首帧到达时才开始走（见 fireAnimationStarted）。
        mainHandler.postDelayed(watchdog, logicalDurationMs() + STARTUP_SLACK_MS)
    }

    override fun onDetachedFromWindow() {
        finished = true
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(frameObserver)
        releaseFrameRate?.invoke(); releaseFrameRate = null
        renderer?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        fireDone()
        super.onDetachedFromWindow()
        notifyReleased()
    }

    /** 完成通知恰好一次：所有退出路径（正常/降级/超时/detach）都会到达。 */
    private fun fireDone() {
        if (doneFired) return
        doneFired = true
        onDone?.run()
    }

    /** dim 与粒子必须从同一个已经交换到屏幕的 GL 首帧开始。 */
    private fun fireAnimationStarted() {
        if (animationStartedFired) return
        animationStartedFired = true
        // 首帧到了：把看门狗改成「从现在起一个动画时长 + 少量余量」
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, logicalDurationMs() + RUNNING_SLACK_MS)
        onAnimationStarted?.run()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (renderer != null || finished) return
        renderer = ParticleDismissRenderer(
            assets = context.assets,
            refreshRate = display?.refreshRate ?: 60f,
            density = resources.displayMetrics.density,
            surfaceTexture = surface,
            viewportWidth = width,
            viewportHeight = height,
            spec = spec,
            preparation = preparation,
            touchFeedback = touchFeedback,
            onFirstFrame = { mainHandler.post { onGlFirstFrame() } },
            onFinished = { completed -> mainHandler.post { onGlFinished(completed) } }
        ).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderer?.cancel()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (finished) return
        if (com.ywwynm.everythingdone.BuildConfig.DEBUG) textureTimings += longArrayOf(0, System.nanoTime(), surface.timestamp)
        if (android.os.Build.VERSION.SDK_INT < 29 || !isHardwareAccelerated) {
            postOnAnimation { onTextureFrameCommitted(runCatching { surface.timestamp }.getOrDefault(0L), System.nanoTime()) }
        }
        textureView.postInvalidateOnAnimation()
    }

    private fun onTextureFrameCommitted(timestamp: Long, committedAt: Long) {
        if (finished) return
        val active = renderer ?: return
        // TextureView 建链时会先送 timestamp=0 的空缓冲；这不是粒子首帧。
        // 误认它会提前撤下快照、提前启动暗层，并在准备期间暴露一帧空白。
        if (!active.isAnimationFrame(timestamp)) return
        active.framePresented(timestamp, committedAt)
        presentedProgress = active.progressForPresentation(timestamp)
        if (!firstTexturePresented) {
            firstTexturePresented = true
            // swap 完成不等于 Texture 已进入本窗口；保持快照直到纹理实际更新。
            snapshotView?.visibility = GONE
            fireAnimationStarted()
            if (com.ywwynm.everythingdone.BuildConfig.DEBUG) {
                android.util.Log.i(ParticleMicroflakeRenderer.TAG,
                    "首帧合成 requestToVisibleMs=${(System.nanoTime()-spec.requestedAtNanos)/1e6}")
            }
        }
        onPresentedProgress?.invoke(presentedProgress)
        // 独立 GL 缓冲可能在本轮 HWUI 取帧之后才入队，持续请求下一次取帧，
        // 避免等待 frameAvailable 的跨线程失效通知而漏掉一个显示节拍。
        if (glCompleted && presentedProgress >= 1f) completePresentedAnimation()
    }

    private fun onGlFirstFrame() {
        // 第一个缓冲仅已入队，实际交接在 onSurfaceTextureUpdated。
    }

    private fun onGlFinished(completed: Boolean) {
        if (finished) return
        if (completed) {
            glCompleted = true
            if (presentedProgress >= 1f) completePresentedAnimation()
            return
        }
        if (isCondense) {
            // 凝聚完成（或失败）：只通知宿主恢复真实面板，本层保持显示末帧
            // （与面板逐像素相同）——宿主确认面板上屏后才调用 release() 移除，
            // 重叠期内容一致，跨窗口切换的任何一帧都无缝（先显后撤）
            fireDone()
        } else {
            // GL 首帧前失败时也必须释放 dim，并与降级淡出同时开始。
            fireAnimationStarted()
            fallbackFadeOut()
        }
    }

    private fun completePresentedAnimation() {
        if (isCondense) fireDone() else removeSelf()
    }

    /** 宿主在真实面板确认上屏后调用，移除本层。 */
    fun release() {
        removeSelf()
    }

    /**
     * GL 未能完整播放时的降级：整层淡出。首帧前失败时快照仍可见，观感退化为普通
     * 淡出；中途失败时 TextureView 停留的最后一帧随层一起淡出。
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
        renderer?.cancel()
        fireDone()
        (parent as? ViewGroup)?.removeView(this)
        snapshotView?.setImageDrawable(null)
        // 渲染线程可能仍持有 bitmap（纹理上传中途取消），活着时交给 GC 回收
        if (renderer?.isAlive != true && preparation.isFinished) {
            spec.snapshot.recycle()
        }
    }

    private companion object {
        /** 首帧之前允许的额外等待：着色器首次编译在慢机上可能占掉一秒以上。 */
        const val STARTUP_SLACK_MS = 4000L

        /** 首帧之后允许的额外等待。 */
        const val RUNNING_SLACK_MS = 700L

        const val FALLBACK_FADE_MS = 240f
    }
}
