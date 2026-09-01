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
    private val onDone: Runnable? = null
) : FrameLayout(activity), TextureView.SurfaceTextureListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapshotView: ImageView?
    private val textureView: TextureView
    private var renderer: ParticleDismissRenderer? = null
    private var finished = false
    private var doneFired = false
    private var animationStartedFired = false

    private val isCondense get() = spec.condenseFromT != null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        textureView = TextureView(activity).apply {
            isOpaque = false
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 兜底：GL 线程异常卡住时强制收尾，叠加层绝不常驻
        val logicalDuration =
            if (isCondense) spec.condenseDurationS else ParticleDismissRenderer.TOTAL_DURATION
        val timeoutMs = (logicalDuration * spec.durationScale * 1000).toLong() + 1000L
        mainHandler.postDelayed({ removeSelf() }, timeoutMs)
    }

    override fun onDetachedFromWindow() {
        finished = true
        renderer?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        fireDone()
        super.onDetachedFromWindow()
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
        onAnimationStarted?.run()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (renderer != null || finished) return
        renderer = ParticleDismissRenderer(
            surfaceTexture = surface,
            viewportWidth = width,
            viewportHeight = height,
            spec = spec,
            onFirstFrame = { mainHandler.post { onGlFirstFrame() } },
            onFinished = { completed -> mainHandler.post { onGlFinished(completed) } }
        ).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderer?.cancel()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun onGlFirstFrame() {
        if (finished) return
        snapshotView?.visibility = GONE
        fireAnimationStarted()
    }

    private fun onGlFinished(completed: Boolean) {
        if (finished) return
        if (isCondense) {
            // 凝聚完成（或失败）：只通知宿主恢复真实面板，本层保持显示末帧
            // （与面板逐像素相同）——宿主确认面板上屏后才调用 release() 移除，
            // 重叠期内容一致，跨窗口切换的任何一帧都无缝（先显后撤）
            fireDone()
        } else if (completed) {
            removeSelf()
        } else {
            // GL 首帧前失败时也必须释放 dim，并与降级淡出同时开始。
            fireAnimationStarted()
            fallbackFadeOut()
        }
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
            .setDuration((FALLBACK_FADE_MS * spec.durationScale).toLong())
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
        if (renderer?.isAlive != true) {
            spec.snapshot.recycle()
        }
    }

    private companion object {
        const val FALLBACK_FADE_MS = 240f
    }
}
