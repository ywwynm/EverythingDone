package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolContinuousStateChannelsTest {

    @Test
    fun levelSlewNeverExceedsThirtyThreePointSixDpPerSecond() {
        val channels = FableSolContinuousStateChannels()
        val output = FableSolContinuousVisualChannels()
        channels.step(
            0.0, FableSolVisualState.PEAK, false,
            1.0, 1.0, 1.0, 1.0, 1.0, 0.5, 1.0, 0.0, output
        )
        val first = output.levelDp
        assertTrue(first > 0.0)
        assertTrue(first <= 33.6 / 60.0)
        channels.step(
            1.0 / 60.0, FableSolVisualState.PEAK, false,
            1.0, 1.0, 1.0, 1.0, 1.0, 0.5, 1.0, 0.0, output
        )
        assertTrue(output.levelDp > first)
        assertTrue(output.levelDp - first <= 33.6 / 60.0)
    }

    @Test
    fun equalWaterProducesEqualLevelGoalsAcrossGrades() {
        val states = arrayOf(
            FableSolVisualState.GROOVE,
            FableSolVisualState.PEAK,
            FableSolVisualState.CLIMAX
        )
        for (state in states) {
            val channels = FableSolContinuousStateChannels()
            val output = channels.step(
                0.0, state, false,
                0.72, 0.8, 0.7, 0.6, 0.6, 0.5, 1.0, 0.0
            )
            assertEquals(
                FableSolContinuousStateChannels.waterLevelGoalDp(0.72),
                output.levelGoalDp, 1e-12
            )
        }
    }

    @Test
    fun waterLevelMappingKeepsQuietSlopeAndCapsFullDrive() {
        // 低响度段保持改前斜率，安静时的水位观感不变。
        assertEquals(
            160.0,
            FableSolContinuousStateChannels.waterLevelGoalDp(0.01) / 0.01, 0.1
        )
        // 满驱动落在 120dp：L0 基准 96dp，静水面 216dp，为 144dp 的巨浪在 420dp
        // 容器里留出余量。
        assertEquals(120.0, FableSolContinuousStateChannels.waterLevelGoalDp(1.0), 1e-12)
        assertEquals(0.0, FableSolContinuousStateChannels.waterLevelGoalDp(0.0), 0.0)
        // 全区间严格单调——硬钳位会把高响度整段压平、失去分辨。
        var previous = -1.0
        for (i in 0..200) {
            val value = FableSolContinuousStateChannels.waterLevelGoalDp(i / 200.0)
            assertTrue("水位映射必须严格单调", value > previous)
            previous = value
        }
        assertEquals(
            FableSolContinuousStateChannels.waterLevelGoalDp(1.0),
            FableSolContinuousStateChannels.waterLevelGoalDp(1.5), 0.0
        )
        assertEquals(0.0, FableSolContinuousStateChannels.waterLevelGoalDp(-0.2), 0.0)
    }

    @Test
    fun climaxRaisesWaveAndMaterialTargetsWithoutChangingTransportCeiling() {
        fun settled(state: FableSolVisualState): FableSolContinuousVisualChannels {
            val channels = FableSolContinuousStateChannels()
            val output = FableSolContinuousVisualChannels()
            repeat(600) { index ->
                channels.step(
                    index / 60.0, state, false,
                    0.82, 0.78, 0.80, 0.72, 0.72, 0.62, 1.0, 0.0, output
                )
            }
            return output
        }

        val groove = settled(FableSolVisualState.GROOVE)
        val climax = settled(FableSolVisualState.CLIMAX)
        assertTrue(climax.waveScale > groove.waveScale * 1.9)
        assertTrue(climax.rim01 > groove.rim01)
        assertTrue(climax.cap01 > groove.cap01)
        assertEquals(groove.flow01, climax.flow01, 1e-12)
        assertEquals(groove.targetDps, climax.targetDps, 1e-9)
    }
}
