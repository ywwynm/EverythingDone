package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.AttributeSet
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.ywwynm.everythingdone.model.ThingBackground
import kotlin.math.max
import kotlin.math.min

/** SurfaceView 宿主：UI 线程只投递 60Hz 时间戳，全部水体工作在 FableSolGles 线程。 */
class WaveVisualizerFableSolGl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, FableSolFrameReceiver {

    private val density = resources.displayMetrics.density.toDouble()
    private val framePacer = FableSolFramePacer(TARGET_FPS)
    private val renderThread = FableSolGlRenderThread(
        context,
        density,
        onHdrStatus = { active, _ ->
            post {
                hdrContentAvailable = surfaceReady && active
                updateDesiredHdrHeadroom()
            }
        },
        onFatalError = { message ->
            post {
                contentDescription = "FableSol GLES unavailable: $message"
                onGlFailure?.invoke(message)
            }
        }
    )
    internal var onGlFailure: ((String) -> Unit)? = null
    private var frameCallbackPosted = false
    private var animating = false
    private var surfaceReady = false
    private var recordingHdrRequested = false
    private var hdrContentAvailable = false
    private var desiredHdrHeadroomRaised = false
    private var lastHeadroomPollNanos = Long.MIN_VALUE
    private var lastRefreshPollNanos = Long.MIN_VALUE
    private val releaseHdrHeadroom = Runnable {
        if (!recordingHdrRequested) {
            desiredHdrHeadroomRaised = false
            applyDesiredHdrHeadroom(1f)
        }
    }
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameCallbackPosted = false
        if (!shouldAnimate()) {
            animating = false
            framePacer.reset()
            return@FrameCallback
        }
        pollHdrHeadroom(frameTimeNanos)
        pollDisplayRefreshRate(frameTimeNanos)
        if (framePacer.shouldRender(frameTimeNanos)) {
            renderThread.requestRender(frameTimeNanos)
        }
        scheduleFrameCallback()
    }

    init {
        setZOrderOnTop(false)
        holder.setFormat(
            if (Build.VERSION.SDK_INT >= 34 && resources.configuration.isScreenHdr) {
                PixelFormat.RGBA_F16
            } else {
                PixelFormat.RGBA_8888
            }
        )
        holder.addCallback(this)
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

    internal fun setPresentationAlpha(alpha: Float) {
        renderThread.setPresentationAlpha(alpha)
    }

    internal fun setRecordingHdrActive(active: Boolean) {
        recordingHdrRequested = active
        renderThread.setHdrRecordingRequested(active)
        updateDesiredHdrHeadroom()
    }

    internal fun setTuningValue(key: String, value: Double) {
        renderThread.setTuningValue(key, value)
    }

    /**
     * 暂停冻结（与 Python 模拟器同语义）：模拟与音频泵停住、画面静止，但渲染
     * 循环照跑——冻结画面上调参、换色、HDR 切换仍逐帧实时生效。
     */
    internal fun setSimulationPaused(paused: Boolean) {
        renderThread.setSimulationPaused(paused)
    }

    internal fun beginBackgroundTransition(background: ThingBackground) {
        renderThread.beginBackgroundTransition(background)
    }

    internal fun setContentVerticalOffsetDp(offsetDp: Float) {
        renderThread.setContentVerticalOffsetDp(offsetDp)
    }

    internal fun setBottomCornerRadiusPx(radiusPx: Float) {
        renderThread.setBottomCornerRadiusPx(radiusPx)
    }

    override fun onAudioFrames(frames: List<FableSolFeatureFrame>, events: List<FableSolEvent>) {
        renderThread.onAudioFrames(frames, events)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!holder.surface.isValid) return
        surfaceReady = true
        val frame = holder.surfaceFrame
        val preferHdr = canBuildHdrSurface()
        val hdrSdrRatio = currentHdrSdrRatio()
        renderThread.attach(
            holder.surface,
            max(width, frame.width()).coerceAtLeast(1),
            max(height, frame.height()).coerceAtLeast(1),
            preferHdr,
            hdrSdrRatio
        )
        renderThread.setHdrRecordingRequested(recordingHdrRequested)
        lastHeadroomPollNanos = Long.MIN_VALUE
        ensureAnimating()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!surfaceReady && holder.surface.isValid) {
            surfaceReady = true
            renderThread.attach(
                holder.surface,
                width.coerceAtLeast(1),
                height.coerceAtLeast(1),
                canBuildHdrSurface(),
                currentHdrSdrRatio()
            )
            renderThread.setHdrRecordingRequested(recordingHdrRequested)
        } else {
            renderThread.resize(width, height)
        }
        ensureAnimating()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopFrameLoop()
        resetHdrSurfaceState()
        if (surfaceReady) {
            surfaceReady = false
            renderThread.detachBlocking()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureAnimating()
    }

    override fun onDetachedFromWindow() {
        stopFrameLoop()
        resetHdrSurfaceState()
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

    private fun canBuildHdrSurface(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val currentDisplay = display ?: return false
        return currentDisplay.isHdr && currentDisplay.isHdrSdrRatioAvailable
    }

    private fun currentHdrSdrRatio(): Float {
        if (Build.VERSION.SDK_INT < 34) return 1f
        val currentDisplay = display ?: return 1f
        if (!currentDisplay.isHdr || !currentDisplay.isHdrSdrRatioAvailable) return 1f
        return currentDisplay.hdrSdrRatio
    }

    /**
     * 让渲染频率跟随当前显示模式：60Hz 面板维持既有 60fps，高刷面板放开到
     * 120fps 上限（模拟以真实时间戳推进，水体速度与两端视觉合同不变）。
     * 刷新率查询按 250ms 节流；显示模式切换（如系统省电降到 60Hz）时 pacer
     * 自动收回节奏。
     */
    private fun pollDisplayRefreshRate(frameTimeNanos: Long) {
        if (lastRefreshPollNanos != Long.MIN_VALUE &&
            frameTimeNanos - lastRefreshPollNanos < HEADROOM_POLL_INTERVAL_NANOS
        ) return
        lastRefreshPollNanos = frameTimeNanos
        val refreshRate = display?.refreshRate ?: 0f
        val target = if (refreshRate >= MIN_VALID_REFRESH_RATE) {
            min(refreshRate.toDouble(), MAX_RENDER_FPS)
        } else {
            TARGET_FPS
        }
        framePacer.setTargetFps(target)
    }

    private fun pollHdrHeadroom(frameTimeNanos: Long) {
        if (!hdrContentAvailable || Build.VERSION.SDK_INT < 34) return
        if (lastHeadroomPollNanos != Long.MIN_VALUE &&
            frameTimeNanos - lastHeadroomPollNanos < HEADROOM_POLL_INTERVAL_NANOS
        ) return
        lastHeadroomPollNanos = frameTimeNanos
        renderThread.setDisplayHdrSdrRatio(currentHdrSdrRatio())
    }

    private fun updateDesiredHdrHeadroom() {
        removeCallbacks(releaseHdrHeadroom)
        if (Build.VERSION.SDK_INT < 35) return
        if (recordingHdrRequested && hdrContentAvailable) {
            desiredHdrHeadroomRaised = true
            applyDesiredHdrHeadroom(FableSolHdrPolicy.DESIRED_SURFACE_HEADROOM)
        } else if (hdrContentAvailable && desiredHdrHeadroomRaised) {
            postDelayed(releaseHdrHeadroom, HDR_RELEASE_DELAY_MS)
        } else {
            desiredHdrHeadroomRaised = false
            applyDesiredHdrHeadroom(1f)
        }
    }

    private fun applyDesiredHdrHeadroom(value: Float) {
        if (Build.VERSION.SDK_INT >= 35) setDesiredHdrHeadroom(value)
    }

    private fun resetHdrSurfaceState() {
        removeCallbacks(releaseHdrHeadroom)
        hdrContentAvailable = false
        desiredHdrHeadroomRaised = false
        lastHeadroomPollNanos = Long.MIN_VALUE
        applyDesiredHdrHeadroom(1f)
        renderThread.setDisplayHdrSdrRatio(1f)
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

    companion object {
        // 显示模式未知时的保底节奏；实际目标随显示刷新率在 [60, 120] 内跟随。
        const val TARGET_FPS = 60.0
        const val MAX_RENDER_FPS = 120.0
        private const val MIN_VALID_REFRESH_RATE = 10f
        private const val HEADROOM_POLL_INTERVAL_NANOS = 250_000_000L
        private const val HDR_RELEASE_DELAY_MS = 360L
        private const val INTRINSIC_W_DP = 280f
        private const val INTRINSIC_H_DP = 420f
    }
}
