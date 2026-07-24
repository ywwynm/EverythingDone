package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolHdrPolicyTest {

    @Test
    fun factoryDefaultStrengthIsTheMaximumWhileCalibrationAnchorStays() {
        // 2026-07-24 用户裁定：出厂默认档 = 上限；标定锚 3.6 不随默认档移动。
        assertEquals(9.6f, FableSolHdrPolicy.MAX_STRENGTH, 0f)
        assertEquals(FableSolHdrPolicy.MAX_STRENGTH, FableSolHdrPolicy.DEFAULT_STRENGTH, 0f)
        assertEquals(1f, FableSolHdrPolicy.excessScale(3.6f), 1e-6f)
    }

    @Test
    fun unavailableOrInvalidHeadroomProducesExactSdr() {
        assertEquals(1f, FableSolHdrPolicy.usableHeadroom(Float.NaN, CALIBRATION), 0f)
        assertEquals(1f, FableSolHdrPolicy.usableHeadroom(Float.POSITIVE_INFINITY, CALIBRATION), 0f)
        assertEquals(1f, FableSolHdrPolicy.usableHeadroom(0.8f, CALIBRATION), 0f)
        assertEquals(1f, FableSolHdrPolicy.usableHeadroom(1.01f, CALIBRATION), 0f)
        assertEquals(1.45f, FableSolHdrPolicy.usableHeadroom(1.45f, CALIBRATION), 0f)
        assertEquals(3.6f, FableSolHdrPolicy.usableHeadroom(4f, CALIBRATION), 0f)
    }

    @Test
    fun userStrengthCapsUsableHeadroomAndOneMeansStrictlyOff() {
        // 强度 1.0 = 关闭：显示器再有余量也严格回 SDR。
        assertEquals(1f, FableSolHdrPolicy.usableHeadroom(4f, 1f), 0f)
        // 强度低于显示授予时以强度为上限；高于授予时以授予为上限。
        assertEquals(2f, FableSolHdrPolicy.usableHeadroom(4f, 2f), 0f)
        assertEquals(4.2f, FableSolHdrPolicy.usableHeadroom(4.2f, 9.6f), 0f)
        assertEquals(9.6f, FableSolHdrPolicy.usableHeadroom(12f, 9.6f), 0f)
        // 越界强度收敛到 [1, 9.6]。
        assertEquals(9.6f, FableSolHdrPolicy.usableHeadroom(10f, 99f), 0f)
        assertEquals(1f, FableSolHdrPolicy.usableHeadroom(4f, 0f), 0f)
    }

    @Test
    fun excessScaleIsLinearInStrengthWithExactAnchors() {
        // k = (S−1)/2.6：1.0→0（关）、3.6→1（标定档）、9.6→8.6/2.6≈3.308（上限）。
        assertEquals(0f, FableSolHdrPolicy.excessScale(1f), 0f)
        assertEquals(1f, FableSolHdrPolicy.excessScale(3.6f), 1e-6f)
        assertEquals(2.5f, FableSolHdrPolicy.excessScale(7.5f), 1e-6f)
        assertEquals(8.6f / 2.6f, FableSolHdrPolicy.excessScale(9.6f), 1e-5f)
        assertEquals(0.5f, FableSolHdrPolicy.excessScale(2.3f), 1e-6f)
        // 越界输入收敛后再换算。
        assertEquals(0f, FableSolHdrPolicy.excessScale(0.2f), 0f)
        assertEquals(8.6f / 2.6f, FableSolHdrPolicy.excessScale(99f), 1e-5f)
    }

    @Test
    fun calibrationScaleReproducesTheEstablishedPerLayerHierarchy() {
        // k=1 时与 3.6 档标定表逐层一致（Python 一比一的基准不因强度机制改变）。
        assertPeakCurve(
            floatArrayOf(3.6f, 2.8f, 2.4f, 2f, 1.6f, 1.36f, 1.29f, 1.16f, 1f)
        ) { layer -> FableSolHdrPolicy.glintCorePeak(layer, 1f) }
        assertPeakCurve(
            floatArrayOf(3.2f, 2.7f, 2.24f, 1.96f, 1.6f, 1.29f, 1.18f, 1.08f, 1f)
        ) { layer -> FableSolHdrPolicy.surfaceReflectionPeak(layer, 1f) }
        assertPeakCurve(
            floatArrayOf(1.08f, 1.06f, 1.04f, 1.02f, 1f, 1f, 1f, 1f, 1f)
        ) { layer -> FableSolHdrPolicy.transmissionPeak(layer, 1f) }
        assertPeakArray(
            floatArrayOf(1.6f, 1.5f, 1.36f, 1.29f, 1.21f, 1.14f, 1.08f, 1f, 1f),
            FableSolHdrPolicy.CONTINUOUS_TRANSMISSION_PEAKS
        )
    }

    @Test
    fun strengthScalesExcessNotPeaksSoLayersFadeProportionallyToZero() {
        // k=0：所有层严格回 1.0（无任何超白），而不是峰值整体除以 3.6 后远层跌破 1。
        for (layer in 0..8) {
            assertEquals(1f, FableSolHdrPolicy.glintCorePeak(layer, 0f), 0f)
            assertEquals(1f, FableSolHdrPolicy.surfaceReflectionPeak(layer, 0f), 0f)
            assertEquals(1f, FableSolHdrPolicy.transmissionPeak(layer, 0f), 0f)
        }
        // k=0.5：每层增量恰为标定档一半，层间比例结构不变。
        assertEquals(2.3f, FableSolHdrPolicy.glintCorePeak(0, 0.5f), 1e-6f)
        assertEquals(1.08f, FableSolHdrPolicy.glintCorePeak(7, 0.5f), 1e-6f)
        // k=2.5（强度 7.5 档）：第 0 层闪点核心 7.5，远层保持弱增量。
        assertEquals(7.5f, FableSolHdrPolicy.glintCorePeak(0, 2.5f), 1e-5f)
        assertEquals(1.4f, FableSolHdrPolicy.glintCorePeak(7, 2.5f), 1e-5f)
        // 上限档：k=excessScale(9.6)，第 0 层闪点核心恰好到达 9.6。
        assertEquals(
            9.6f,
            FableSolHdrPolicy.glintCorePeak(0, FableSolHdrPolicy.excessScale(9.6f)),
            1e-5f
        )
        // mode8 肩部在最高档也只到 1.2；无增量的层严格保持 1。
        assertEquals(1.2f, FableSolHdrPolicy.transmissionPeak(0, 2.5f), 1e-6f)
        assertEquals(1f, FableSolHdrPolicy.transmissionPeak(4, 2.5f), 0f)
        // 越界表索引仍回 1（原有契约）。
        assertEquals(1f, FableSolHdrPolicy.glintCorePeak(99, 2.5f), 0f)
    }

    @Test
    fun continuousTransmissionFillMatchesScalarScalingPerLayer() {
        val target = FloatArray(9)

        FableSolHdrPolicy.fillContinuousTransmissionPeaks(target, 2.5f)

        for (layer in 0..8) {
            val base = FableSolHdrPolicy.CONTINUOUS_TRANSMISSION_PEAKS[layer]
            assertEquals("layer=$layer", 1f + (base - 1f) * 2.5f, target[layer], 1e-6f)
        }
        FableSolHdrPolicy.fillContinuousTransmissionPeaks(target, 0f)
        for (layer in 0..8) {
            assertEquals("layer=$layer", 1f, target[layer], 0f)
        }
    }

    @Test
    fun availableHeadroomRisesSmoothlyButDropsToTheRealLimitImmediately() {
        val halfway = FableSolHdrPolicy.advanceHeadroom(1f, 3.6f, CALIBRATION, 0.18f)

        assertEquals(2.3f, halfway, 1e-6f)
        assertEquals(3.6f, FableSolHdrPolicy.advanceHeadroom(halfway, 3.6f, CALIBRATION, 0.18f), 1e-6f)
        assertEquals(1.2f, FableSolHdrPolicy.advanceHeadroom(3.6f, 1.2f, CALIBRATION, 0.01f), 0f)
        assertEquals(1f, FableSolHdrPolicy.advanceHeadroom(1.8f, Float.NaN, CALIBRATION, 0.01f), 0f)
    }

    @Test
    fun rampDurationIsInvariantAcrossStrengthAndStrengthDropObeysImmediately() {
        // 强度 9.6：半程 0.18s 恰到中点，再 0.18s 到满，行程时长与默认档一致。
        val halfway = FableSolHdrPolicy.advanceHeadroom(1f, 9.6f, 9.6f, 0.18f)
        assertEquals(5.3f, halfway, 1e-5f)
        assertEquals(9.6f, FableSolHdrPolicy.advanceHeadroom(halfway, 9.6f, 9.6f, 0.18f), 1e-5f)
        // 用户把强度调低：目标立即服从新上限，不做平滑。
        assertEquals(2f, FableSolHdrPolicy.advanceHeadroom(3.6f, 6f, 2f, 0.001f), 0f)
        // 强度 1.0：授予再多也停在 SDR。
        assertEquals(1f, FableSolHdrPolicy.advanceHeadroom(1f, 6f, 1f, 1f), 0f)
    }

    @Test
    fun recordingTransitionIsSmoothSymmetricAndCompletesInSharedDuration() {
        val transition = FableSolHdrTransition()

        assertEquals(0.5f, transition.update(true, 0.18f), 1e-6f)
        assertEquals(1f, transition.update(true, 0.18f), 1e-6f)
        assertEquals(0.5f, transition.update(false, 0.18f), 1e-6f)
        assertEquals(0f, transition.update(false, 0.18f), 1e-6f)
    }

    @Test
    fun interruptedTransitionContinuesFromCurrentValueWithoutJump() {
        val transition = FableSolHdrTransition()
        val rising = transition.update(true, 0.09f)
        val firstFalling = transition.update(false, 0f)

        assertTrue(rising in 0f..1f)
        assertEquals(rising, firstFalling, 0f)
        assertTrue(transition.update(false, 0.09f) < rising)
    }

    private fun assertPeakCurve(expected: FloatArray, valueAt: (Int) -> Float) {
        expected.forEachIndexed { index, value ->
            assertEquals("layer=$index", value, valueAt(index), 1e-6f)
        }
    }

    private fun assertPeakArray(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.forEachIndexed { index, value ->
            assertEquals("layer=$index", value, actual[index], 0f)
        }
    }

    private companion object {
        /** 峰值表标定档：headroom/斜坡测试沿用 3.6 锚点，与出厂默认档解耦。 */
        const val CALIBRATION = 3.6f
    }
}
