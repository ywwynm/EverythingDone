package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Choreographer
import android.view.TextureView
import android.view.View
import com.ywwynm.everythingdone.model.ThingBackground
import kotlin.math.min

/** Stage 1 TextureView 宿主：UI 线程只投递 60Hz 时间戳，全部水体工作在 FableSolGles 线程。 */
class WaveVisualizerFableSolGl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener, FableSolFrameReceiver {

    private val density = resources.displayMetrics.density.toDouble()
    private val framePacer = FableSolFramePacer(TARGET_FPS)
    private val renderThread = FableSolGlRenderThread(context, density) { message ->
        post {
            contentDescription = "FableSol GLES unavailable: $message"
            onGlFailure?.invoke(message)
        }
    }
    internal var onGlFailure: ((String) -> Unit)? = null
    private var frameCallbackPosted = false
    private var animating = false
    private var surfaceReady = false
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameCallbackPosted = false
        if (!shouldAnimate()) {
            animating = false
            framePacer.reset()
            return@FrameCallback
        }
        if (framePacer.shouldRender(frameTimeNanos)) {
            renderThread.requestRender(frameTimeNanos)
        }
        scheduleFrameCallback()
    }

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun setThingBackground(background: ThingBackground) {
        renderThread.setThingBackground(background)
    }

    fun setContainerGravity(x: Float, y: Float, z: Float) {
        renderThread.setGravity(x, y, z)
    }

    internal fun setPerformanceMonitor(monitor: FableSolPerformanceMonitor?) {
        renderThread.setPerformanceMonitor(monitor)
    }

    override fun onAudioFrames(frames: List<FableSolFeatureFrame>, events: List<FableSolEvent>) {
        renderThread.onAudioFrames(frames, events)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceReady = true
        renderThread.attach(surface, width, height)
        ensureAnimating()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderThread.resize(width, height)
        ensureAnimating()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        surfaceReady = false
        stopFrameLoop()
        renderThread.detachBlocking()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureAnimating()
    }

    override fun onDetachedFromWindow() {
        stopFrameLoop()
        if (surfaceReady) {
            surfaceReady = false
            renderThread.detachBlocking()
        }
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) ensureAnimating() else stopFrameLoop()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) ensureAnimating() else stopFrameLoop()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (surfaceReady && width > 0 && height > 0) renderThread.resize(width, height)
        ensureAnimating()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveIntrinsic(widthMeasureSpec, INTRINSIC_W_DP),
            resolveIntrinsic(heightMeasureSpec, INTRINSIC_H_DP)
        )
    }

    private fun ensureAnimating() {
        if (!animating && shouldAnimate()) {
            animating = true
            framePacer.reset()
        }
        if (animating) scheduleFrameCallback()
    }

    private fun scheduleFrameCallback() {
        if (frameCallbackPosted) return
        frameCallbackPosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        if (frameCallbackPosted) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            frameCallbackPosted = false
        }
        animating = false
        framePacer.reset()
    }

    private fun shouldAnimate(): Boolean = surfaceReady && isAttachedToWindow &&
        width > 0 && height > 0 && windowVisibility == View.VISIBLE && isShown

    private fun resolveIntrinsic(spec: Int, dp: Float): Int {
        val mode = MeasureSpec.getMode(spec)
        val size = MeasureSpec.getSize(spec)
        val intrinsic = (dp * resources.displayMetrics.density).toInt()
        return when (mode) {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> min(size, intrinsic)
            else -> intrinsic
        }
    }

    private companion object {
        const val TARGET_FPS = 60.0
        const val INTRINSIC_W_DP = 280f
        const val INTRINSIC_H_DP = 420f
    }
}
