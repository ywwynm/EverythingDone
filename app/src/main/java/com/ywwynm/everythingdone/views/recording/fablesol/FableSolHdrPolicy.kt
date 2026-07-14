package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.min

/**
 * FableSol 的 HDR 亮度预算。
 *
 * 所有数值均以 SDR reference white 为 1.0；这里只分配额外亮度，不改变几何、音频映射或
 * SDR 材质基线。显示器当前没有可用余量时，[usableHeadroom] 严格返回 1.0。
 */
internal object FableSolHdrPolicy {

    const val MAX_CONTENT_HEADROOM = 2.0f
    const val DESIRED_SURFACE_HEADROOM = 2.0f
    const val TRANSITION_SECONDS = 0.36f
    const val WATER_TRANSMISSION_PEAK = 1.45f
    private const val MIN_VISIBLE_HEADROOM = 1.01f

    private val glintCorePeaks = floatArrayOf(2.00f, 1.90f, 1.75f, 1.50f, 1.35f, 1.20f, 1f, 1f, 1f)
    private val litCrestPeaks = floatArrayOf(1.40f, 1.36f, 1.30f, 1.22f, 1.14f, 1.08f, 1f, 1f, 1f)
    // Step D 后 mode8 只保留很弱的独立肩部；主要 HDR 透射改由连续水面 SSS 承担。
    private val transmissionPeaks = floatArrayOf(1.08f, 1.06f, 1.04f, 1.02f, 1f, 1f, 1f, 1f, 1f)

    fun usableHeadroom(displayHdrSdrRatio: Float): Float {
        if (!displayHdrSdrRatio.isFinite() || displayHdrSdrRatio <= MIN_VISIBLE_HEADROOM) return 1f
        return min(displayHdrSdrRatio, MAX_CONTENT_HEADROOM)
    }

    /** 向上变化平滑展开；向下变化立即服从显示器当前上限，绝不短暂输出超出实际余量的值。 */
    fun advanceHeadroom(current: Float, available: Float, deltaSeconds: Float): Float {
        val target = usableHeadroom(available)
        if (target <= current) return target
        val maximumStep = (MAX_CONTENT_HEADROOM - 1f) *
            deltaSeconds.coerceAtLeast(0f) / TRANSITION_SECONDS
        return min(target, current.coerceAtLeast(1f) + maximumStep)
    }

    fun glintCorePeak(layer: Int): Float = glintCorePeaks.getOrElse(layer) { 1f }

    fun litCrestPeak(layer: Int): Float = litCrestPeaks.getOrElse(layer) { 1f }

    fun transmissionPeak(layer: Int): Float = transmissionPeaks.getOrElse(layer) { 1f }
}

/** 录音状态切换只改变统一 HDR 增益；不会建立另一套闪点生命周期。 */
internal class FableSolHdrTransition {

    var value: Float = 0f
        private set

    private var start = 0f
    private var target = 0f
    private var elapsedSeconds = FableSolHdrPolicy.TRANSITION_SECONDS

    fun update(enabled: Boolean, deltaSeconds: Float): Float {
        val nextTarget = if (enabled) 1f else 0f
        if (nextTarget != target) {
            start = value
            target = nextTarget
            elapsedSeconds = 0f
        }
        elapsedSeconds = (elapsedSeconds + deltaSeconds.coerceAtLeast(0f))
            .coerceAtMost(FableSolHdrPolicy.TRANSITION_SECONDS)
        val progress = elapsedSeconds / FableSolHdrPolicy.TRANSITION_SECONDS
        val eased = progress * progress * (3f - 2f * progress)
        value = start + (target - start) * eased
        return value
    }

    fun reset() {
        value = 0f
        start = 0f
        target = 0f
        elapsedSeconds = FableSolHdrPolicy.TRANSITION_SECONDS
    }
}
