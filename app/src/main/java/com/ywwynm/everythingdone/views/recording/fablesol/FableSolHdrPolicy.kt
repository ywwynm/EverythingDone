package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.min

/**
 * FableSol 的 HDR 亮度预算。
 *
 * 所有数值均以 SDR reference white 为 1.0。逐层峰值表是 3.6 强度档的标定基准
 * （与 Python 模拟器一比一，不随用户设置改写）；用户强度
 * [STRENGTH_OFF]～[MAX_STRENGTH] 经 [excessScale] 只线性缩放各效果的超白增量
 * （峰值−1），不改变几何、音频映射、SDR 材质基线或九层增量的比例结构。
 * 显示器当前没有可用余量时，[usableHeadroom] 严格返回 1.0。
 */
internal object FableSolHdrPolicy {

    /**
     * 用户强度语义（D204；2026-07-24 上限 7.5→9.6，默认改为上限）：
     * 1.0 = 不开启 HDR；3.6 = 峰值表标定档；默认 = [MAX_STRENGTH]。
     */
    const val STRENGTH_OFF = 1f
    const val MAX_STRENGTH = 9.6f
    const val DEFAULT_STRENGTH = MAX_STRENGTH
    const val TRANSITION_SECONDS = 0.36f
    /** 峰值表标定锚：k=(S−1)/(3.6−1)，3.6 档逐字复现四张基准表；不随默认档移动。 */
    private const val CALIBRATION_STRENGTH = 3.6f
    private const val MIN_VISIBLE_HEADROOM = 1.01f

    private val glintCorePeaks = floatArrayOf(3.60f, 2.80f, 2.40f, 2.00f, 1.60f, 1.36f, 1.29f, 1.16f, 1f)
    private val surfaceReflectionPeaks =
        floatArrayOf(3.20f, 2.70f, 2.24f, 1.96f, 1.60f, 1.29f, 1.18f, 1.08f, 1f)
    val CONTINUOUS_TRANSMISSION_PEAKS =
        floatArrayOf(1.60f, 1.50f, 1.36f, 1.29f, 1.21f, 1.14f, 1.08f, 1f, 1f)
    // mode8 只保留很弱的独立肩部；主要 HDR 透射由连续水面的局部背光分瓣承担。
    private val transmissionPeaks = floatArrayOf(1.08f, 1.06f, 1.04f, 1.02f, 1f, 1f, 1f, 1f, 1f)

    /**
     * 强度→增量倍率：k = (S−1)/(3.6−1)。缩放对象是超白增量而非峰值本身——直接按比例
     * 缩放峰值会在低强度端让远层峰值跌破 1.0、整层提前熄灭，破坏既定的逐层衰减结构；
     * 缩放增量则任意档位都保持层间比例，并在 S=1 时精确退到 0（等价关闭）。
     */
    fun excessScale(strength: Float): Float {
        val value = strength.coerceIn(STRENGTH_OFF, MAX_STRENGTH)
        return (value - STRENGTH_OFF) / (CALIBRATION_STRENGTH - STRENGTH_OFF)
    }

    fun usableHeadroom(displayHdrSdrRatio: Float, strength: Float): Float {
        if (!displayHdrSdrRatio.isFinite() || displayHdrSdrRatio <= MIN_VISIBLE_HEADROOM) return 1f
        val cap = strength.coerceIn(STRENGTH_OFF, MAX_STRENGTH)
        if (cap <= MIN_VISIBLE_HEADROOM) return 1f
        return min(displayHdrSdrRatio, cap)
    }

    /**
     * 向上变化平滑展开（满行程恒为 [TRANSITION_SECONDS]）；向下变化立即服从显示器
     * 当前上限与用户强度，绝不短暂输出超出实际余量的值。
     */
    fun advanceHeadroom(
        current: Float,
        available: Float,
        strength: Float,
        deltaSeconds: Float
    ): Float {
        val target = usableHeadroom(available, strength)
        if (target <= current) return target
        val range = strength.coerceIn(STRENGTH_OFF, MAX_STRENGTH) - 1f
        val maximumStep = range * deltaSeconds.coerceAtLeast(0f) / TRANSITION_SECONDS
        return min(target, current.coerceAtLeast(1f) + maximumStep)
    }

    fun glintCorePeak(layer: Int, excessScale: Float): Float =
        scaledPeak(glintCorePeaks, layer, excessScale)

    fun surfaceReflectionPeak(layer: Int, excessScale: Float): Float =
        scaledPeak(surfaceReflectionPeaks, layer, excessScale)

    fun transmissionPeak(layer: Int, excessScale: Float): Float =
        scaledPeak(transmissionPeaks, layer, excessScale)

    /** 连续水面透射峰值表按当前增量倍率写入 [target]（供 uHdrTransmissionPeaks 上传）。 */
    fun fillContinuousTransmissionPeaks(target: FloatArray, excessScale: Float) {
        for (index in target.indices) {
            target[index] = scaledPeak(CONTINUOUS_TRANSMISSION_PEAKS, index, excessScale)
        }
    }

    private fun scaledPeak(basePeaks: FloatArray, layer: Int, excessScale: Float): Float =
        1f + (basePeaks.getOrElse(layer) { 1f } - 1f) * excessScale.coerceAtLeast(0f)
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

    /** 直接落到某个增益并视为过渡已完成；离线导出第一帧就要满增益，没有淡入这一说。 */
    fun snapTo(newValue: Float) {
        value = newValue
        start = newValue
        target = newValue
        elapsedSeconds = FableSolHdrPolicy.TRANSITION_SECONDS
    }
}
