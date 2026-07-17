package com.ywwynm.everythingdone.views.recording.fablesol

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.ywwynm.everythingdone.model.ThingBackground
import kotlin.math.min

/**
 * Stage 1 运行时宿主：正常只运行 GLES；任何 GL 失败时切换旧 Canvas，并把天空强制为纯红色。
 */
class WaveVisualizerFableSolHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), FableSolFrameReceiver {

    private val canvasFallback = WaveVisualizerFableSol(context).apply {
        visibility = View.GONE
    }
    private val glView = WaveVisualizerFableSolGl(context).apply {
        onGlFailure = { activateCanvasFallback() }
    }
    private var fallbackActive = false
    private var currentBackground: ThingBackground? = null
    private var gravityX = 0f
    private var gravityY = 1f
    private var gravityZ = 0f
    private var performanceMonitor: FableSolPerformanceMonitor? = null
    private var presentationAlpha = PREPARED_PRESENTATION_ALPHA
    private var presentationAnimator: ValueAnimator? = null

    init {
        val match = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(canvasFallback, match)
        addView(glView, match)
        setPresentationAlpha(PREPARED_PRESENTATION_ALPHA)
    }

    fun setThingBackground(background: ThingBackground) {
        currentBackground = background
        glView.setThingBackground(background)
        if (fallbackActive) canvasFallback.setThingBackground(background)
    }

    fun setContainerGravity(x: Float, y: Float, z: Float) {
        gravityX = x
        gravityY = y
        gravityZ = z
        if (fallbackActive) canvasFallback.setContainerGravity(x, y, z)
        else glView.setContainerGravity(x, y, z)
    }

    internal fun setPerformanceMonitor(monitor: FableSolPerformanceMonitor?) {
        performanceMonitor = monitor
        glView.setPerformanceMonitor(if (fallbackActive) null else monitor)
        canvasFallback.setPerformanceMonitor(if (fallbackActive) monitor else null)
    }

    fun setPresentationAlpha(alpha: Float) {
        val value = alpha.coerceIn(0f, 1f)
        presentationAlpha = value
        glView.setPresentationAlpha(value)
        canvasFallback.alpha = value
    }

    fun animatePresentationAlpha(targetAlpha: Float, durationMs: Long) {
        presentationAnimator?.cancel()
        val target = targetAlpha.coerceIn(0f, 1f)
        if (durationMs <= 0L || presentationAlpha == target) {
            setPresentationAlpha(target)
            return
        }
        presentationAnimator = ValueAnimator.ofFloat(presentationAlpha, target).apply {
            duration = durationMs
            addUpdateListener { setPresentationAlpha(it.animatedValue as Float) }
            start()
        }
    }

    fun setRecordingHdrActive(active: Boolean) {
        glView.setRecordingHdrActive(active && !fallbackActive)
    }

    /** 运行时调参（调参 Dialog 实时预览）：直达当前活动的渲染后端。 */
    fun setTuningValue(key: String, value: Double) {
        glView.setTuningValue(key, value)
        if (fallbackActive) canvasFallback.setTuningValue(key, value)
    }

    /** 暂停冻结（调参 Dialog）：画面静止但渲染照跑，调参实时可见。 */
    fun setSimulationPaused(paused: Boolean) {
        glView.setSimulationPaused(paused)
        canvasFallback.setSimulationPaused(paused)
    }

    /**
     * 渐变切换配色（调参 Dialog 换色）：GL 端新颜色的波浪从右缘涌入；
     * Canvas 回退无过渡动画、直切。
     */
    fun animateThingBackground(background: ThingBackground) {
        currentBackground = background
        glView.beginBackgroundTransition(background)
        if (fallbackActive) canvasFallback.setThingBackground(background)
    }

    /** 预览取景：内容整体沿屏幕 y 平移（dp，负 = 上移）；仅 GL 路径。 */
    fun setContentVerticalOffsetDp(offsetDp: Float) {
        glView.setContentVerticalOffsetDp(offsetDp)
    }

    /** 底部两角半径覆盖（px；<0 恢复与顶部一致）；仅 GL 路径。 */
    fun setBottomCornerRadiusPx(radiusPx: Float) {
        glView.setBottomCornerRadiusPx(radiusPx)
    }

    override fun onAudioFrames(frames: List<FableSolFeatureFrame>, events: List<FableSolEvent>) {
        if (fallbackActive) canvasFallback.onAudioFrames(frames, events)
        else glView.onAudioFrames(frames, events)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(resolveIntrinsic(widthMeasureSpec, INTRINSIC_W_DP), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(resolveIntrinsic(heightMeasureSpec, INTRINSIC_H_DP), MeasureSpec.EXACTLY)
        )
    }

    override fun onDetachedFromWindow() {
        presentationAnimator?.cancel()
        presentationAnimator = null
        super.onDetachedFromWindow()
    }

    private fun activateCanvasFallback() {
        if (fallbackActive) return
        fallbackActive = true
        glView.setPerformanceMonitor(null)
        glView.setRecordingHdrActive(false)
        glView.visibility = View.GONE
        canvasFallback.setGlFallbackDiagnostic(true)
        currentBackground?.let(canvasFallback::setThingBackground)
        canvasFallback.setContainerGravity(gravityX, gravityY, gravityZ)
        canvasFallback.setPerformanceMonitor(performanceMonitor)
        canvasFallback.visibility = View.VISIBLE
    }

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
        const val INTRINSIC_W_DP = 280f
        const val INTRINSIC_H_DP = 420f
        const val PREPARED_PRESENTATION_ALPHA = 0.16f
    }
}
