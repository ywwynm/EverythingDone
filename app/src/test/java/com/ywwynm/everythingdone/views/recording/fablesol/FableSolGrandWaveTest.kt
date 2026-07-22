package com.ywwynm.everythingdone.views.recording.fablesol

import com.ywwynm.everythingdone.views.recording.fablesol.FableSolSpec.PHYSICS_DT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FableSolGrandWaveTest {

    @Test
    fun triggerCreatesOneWideWaveOffscreenAndRejectsRetrigger() {
        val sim = FableSolSimulation(FableSolParams())

        assertTrue(sim.triggerGrandWave())
        val wave = sim.grandWave
        assertTrue(wave.active)
        assertEquals(960.0, wave.widthDp, 0.0)
        assertEquals(120.0, wave.plateauDp, 0.0)
        assertEquals(420.0, wave.flankDp, 0.0)
        assertEquals(144.0, wave.amplitudeDp, 0.0)
        // 出生贴的是 3% 等高线而非支撑边界：侧翼最外那段看不见的尾巴不该拖慢入场。
        assertEquals(60.0 + 420.0 * FableSolGrandWave.VISIBLE_CUT_Q, wave.visibleHalfDp, 1e-12)
        assertEquals(
            sim.geometrySpan() / 2.0 + wave.visibleHalfDp + 8.0,
            wave.centerUDp,
            1e-12
        )
        assertEquals(-sim.layerWaveSpeedDps(0), wave.transportDps, 1e-12)
        assertFalse(sim.triggerGrandWave(1.5))
        assertEquals(1, wave.triggerCount)
    }

    @Test
    fun profileHasAFlatCrownAndMirroredFlanksAndReusesStorage() {
        val sim = FableSolSimulation(FableSolParams())
        val wave = FableSolGrandWave(pointCount = 9)
        assertTrue(wave.trigger(sim))
        wave.centerUDp = 0.0
        val halfPlateau = wave.plateauDp / 2.0
        val flank = wave.flankDp
        val u = doubleArrayOf(
            -halfPlateau - flank - 0.01,
            -halfPlateau - flank,
            -halfPlateau - flank / 2.0,
            -halfPlateau,
            0.0,
            halfPlateau,
            halfPlateau + flank / 2.0,
            halfPlateau + flank,
            halfPlateau + flank + 0.01
        )

        val first = wave.sample(u)!!
        val second = wave.sample(u)!!
        assertSame(first, second)
        assertEquals(0.0, first[0], 0.0)
        assertEquals(0.0, first[1], 0.0)
        assertEquals(0.0, first[7], 0.0)
        assertEquals(0.0, first[8], 0.0)
        // 峰顶在整个平顶上严格平坦，不是只在一个点上取驻值。
        assertEquals(wave.amplitudeDp, first[3], 1e-12)
        assertEquals(wave.amplitudeDp, first[4], 1e-12)
        assertEquals(wave.amplitudeDp, first[5], 1e-12)
        assertEquals(wave.amplitudeDp * 0.5, first[2], 1e-12)
        assertEquals(wave.amplitudeDp * 0.5, first[6], 1e-12)
        assertEquals(0.12, wave.backgroundKeep(wave.amplitudeDp), 1e-12)
        assertEquals(1.0, wave.backgroundKeep(0.0), 0.0)
    }

    @Test
    fun crownSeamAndSupportEdgeAreBothC2() {
        val sim = FableSolSimulation(FableSolParams())
        val wave = FableSolGrandWave(pointCount = 5)
        assertTrue(wave.trigger(sim))
        wave.centerUDp = 0.0
        val halfPlateau = wave.plateauDp / 2.0
        val flank = wave.flankDp
        val epsilon = 0.01
        // quintic 的值与一、二阶导在归一化侧翼坐标的两端都为 0，因此极小内侧样本
        // 只能按 O(e^3) 起步——支撑外缘和平顶接缝各查一次。
        val scale = wave.amplitudeDp * 10.0 *
            (epsilon / flank) * (epsilon / flank) * (epsilon / flank)

        val edge = wave.sample(
            doubleArrayOf(
                halfPlateau + flank - epsilon, halfPlateau + flank,
                halfPlateau + flank + epsilon, -halfPlateau - flank + epsilon, 0.0
            )
        )!!.copyOf()
        assertTrue(edge[0] in 0.0..(scale * 1.01))
        assertEquals(0.0, edge[1], 0.0)
        assertEquals(0.0, edge[2], 0.0)
        assertEquals(edge[0], edge[3], 1e-12)

        val seam = wave.sample(
            doubleArrayOf(
                halfPlateau - epsilon, halfPlateau, halfPlateau + epsilon,
                -halfPlateau - epsilon, -halfPlateau + epsilon
            )
        )!!.copyOf()
        // 平顶内（含接缝本身）严格等于振幅；越过接缝进入侧翼后只按 O(e^3) 下降，
        // 两侧互为镜像——接缝处的值与一、二阶导都连续。
        assertEquals(wave.amplitudeDp, seam[0], 1e-12)
        assertEquals(wave.amplitudeDp, seam[1], 1e-12)
        assertEquals(wave.amplitudeDp, seam[4], 1e-12)
        assertTrue(wave.amplitudeDp - seam[2] in 0.0..(scale * 1.01))
        assertEquals(seam[2], seam[3], 1e-15)
    }

    @Test
    fun plateauWidensTheCrownWithoutSteepeningTheFlank() {
        val sim = FableSolSimulation(FableSolParams())
        val count = 4001
        val step = 0.25
        val wave = FableSolGrandWave(pointCount = count)
        assertTrue(wave.trigger(sim))
        wave.centerUDp = 0.0
        val u = DoubleArray(count) { -500.0 + it * step }
        val profile = wave.sample(u)!!

        // 90% 等高线必须盖住两端跑的最宽画面（Python 320dp，Android 实测 276dp）。
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (index in u.indices) {
            if (profile[index] >= 0.9 * wave.amplitudeDp) {
                lo = min(lo, u[index]); hi = max(hi, u[index])
            }
        }
        assertTrue("90% 顶宽 ${hi - lo} 应盖住 320dp 画面", hi - lo >= 320.0)

        // 侧翼陡峭度与加平顶之前一致：quintic 峰值斜率 = 1.875·A/flank，而 flank
        // 就是旧轮廓的支撑半宽。顶变宽不得动它。
        var maxSlope = 0.0
        for (index in 1 until count) {
            maxSlope = max(maxSlope, abs(profile[index] - profile[index - 1]) / step)
        }
        assertEquals(
            FableSolGrandWave.SMOOTHERSTEP_MAX_SLOPE * wave.amplitudeDp / wave.flankDp,
            maxSlope, 2e-3
        )
        // 陡峭度上限从「0.24×支撑宽」改成侧翼斜率限，数值本身没变。
        assertEquals(
            0.24 * 840.0,
            FableSolGrandWave.MAX_FLANK_SLOPE * wave.flankDp /
                FableSolGrandWave.SMOOTHERSTEP_MAX_SLOPE,
            1e-9
        )
    }

    @Test
    fun withoutPlateauTheProfileIsExactlyThePreviousQuintic() {
        // 平顶是新增的一个自由度，不是替换轮廓：P=0 时必须逐点退回旧的纯 quintic。
        val sim = FableSolSimulation(FableSolParams())
        val count = 201
        val wave = FableSolGrandWave(pointCount = count)
        assertTrue(wave.trigger(sim))
        wave.centerUDp = 0.0
        wave.plateauDp = 0.0
        wave.flankDp = 420.0
        wave.widthDp = 840.0

        val u = DoubleArray(count) { -500.0 + it * 5.0 }
        val actual = wave.sample(u)!!
        for (index in u.indices) {
            val q = abs(u[index] / 420.0)
            val expected = if (q < 1.0) {
                wave.amplitudeDp * (1.0 - q * q * q * (q * (q * 6.0 - 15.0) + 10.0))
            } else {
                0.0
            }
            assertEquals(expected, actual[index], 1e-12)
        }
        assertEquals(420.0 * FableSolGrandWave.VISIBLE_CUT_Q, wave.visibleHalfDp, 1e-12)
    }

    @Test
    fun fixedStepAdvectsAtPhysicalTransportAndDeactivatesAfterPassingScreen() {
        val sim = FableSolSimulation(FableSolParams())
        sim.layers[0].flowDps = -24.0
        assertTrue(sim.triggerGrandWave())
        val wave = sim.grandWave
        val expectedTransport = -sim.layerWaveSpeedDps(0) - 24.0
        val initialCenter = wave.centerUDp

        sim.update(PHYSICS_DT)

        assertEquals(expectedTransport, wave.transportDps, 1e-9)
        assertEquals(initialCenter + expectedTransport * PHYSICS_DT, wave.centerUDp, 1e-9)
        assertEquals(abs(expectedTransport * PHYSICS_DT), wave.distanceDp, 1e-9)

        repeat(16) {
            if (wave.active) {
                sim.t += 0.5
                wave.advance(sim)
            }
        }
        assertFalse("事件浪应在完整通过画面后自然离场", wave.active)
        assertTrue(sim.triggerGrandWave(expressionGain = 2.0))
        assertEquals(
            FableSolGrandWave.MAX_FLANK_SLOPE * wave.flankDp /
                FableSolGrandWave.SMOOTHERSTEP_MAX_SLOPE,
            wave.amplitudeDp, 1e-12
        )
    }

    @Test
    fun triggerDoesNotShiftTheLayerDcTerm() {
        // 巨浪出生在网格右端之外，但侧翼仍覆盖最右侧几十列。让这部分参与 L0 的
        // DC 项，整层就会在浪进入网格的那一帧瞬间下沉、离场时弹回（D178）。
        val sim = FableSolSimulation(FableSolParams())
        repeat(180) { sim.update(1.0 / 60.0) }
        val before = DoubleArray(FableSolSpec.N_LAYERS)
        sim.fillLayerDcDp(before)

        assertTrue(sim.triggerGrandWave())
        sim.update(0.0)   // 只重新合成，不推进物理
        val after = DoubleArray(FableSolSpec.N_LAYERS)
        sim.fillLayerDcDp(after)

        for (layer in before.indices) {
            assertEquals("第 $layer 层的 DC 项不应因巨浪进入网格而跳变",
                before[layer], after[layer], 1e-9)
        }

        // 未修正的原始均值确实跳了，否则这条测试是空的。
        var raw = 0.0
        for (value in sim.heights[0]) raw += value
        raw /= sim.heights[0].size
        assertTrue("巨浪应当确实覆盖了网格右端，否则本测试无效",
            abs(raw - after[0]) > 1.0)
        assertEquals(raw - after[0], sim.grandDcBiasDp, 1e-9)
    }

    @Test
    fun crownMaskIsPublishedForTheDirectionalFieldAndOrbit() {
        // 冠部支配 mask 此前只作用于锚点 detail，方向场与轨道从未被覆盖，平顶上
        // 因此浮着方向模态、波包与轨道位移。它现在必须逐列发布给 surface。
        val sim = FableSolSimulation(FableSolParams())
        for (value in sim.grandKeep) assertEquals(1.0, value, 0.0)

        assertTrue(sim.triggerGrandWave())
        sim.grandWave.centerUDp = 0.0
        sim.update(0.0)

        val halfPlateau = sim.grandWave.plateauDp / 2.0
        var crownColumns = 0
        for (index in sim.uGrid.indices) {
            if (abs(sim.uGrid[index]) <= halfPlateau) {
                assertEquals("平顶列必须压到 BACKGROUND_KEEP_AT_CREST",
                    FableSolGrandWave.BACKGROUND_KEEP_AT_CREST, sim.grandKeep[index], 1e-12)
                crownColumns++
            }
            if (abs(sim.uGrid[index]) > halfPlateau + sim.grandWave.flankDp) {
                assertEquals("支撑之外必须完全不压制", 1.0, sim.grandKeep[index], 1e-12)
            }
        }
        assertTrue(crownColumns > 8)
    }

    @Test
    fun layerZeroCompositionSuppressesBackgroundUnderCrestWithoutTouchingOtherLayers() {
        val eventParams = quietParams()
        val controlParams = quietParams()
        val event = FableSolSimulation(eventParams)
        val control = FableSolSimulation(controlParams)
        event.layers[0].wave.u.fill(40.0)
        control.layers[0].wave.u.fill(40.0)

        assertTrue(event.triggerGrandWave())
        event.grandWave.centerUDp = 0.0
        event.update(0.0)
        control.update(0.0)

        val profile = event.grandWave.sample(event.uGrid)!!
        val centerIndex = event.uGrid.indices.minByOrNull { abs(event.uGrid[it]) }!!
        val keep = event.grandWave.backgroundKeep(profile[centerIndex])
        val controlDetail = control.heights[0][centerIndex] - 96.0
        val expected = 96.0 + controlDetail * keep + profile[centerIndex]
        assertEquals(expected, event.heights[0][centerIndex], 1e-9)

        for (layer in 1 until FableSolSpec.N_LAYERS) {
            for (point in event.uGrid.indices) {
                assertEquals(control.heights[layer][point], event.heights[layer][point], 1e-12)
            }
        }
    }

    private fun quietParams() = FableSolParams().also {
        it.setForTest("ambient_gain", 0.0)
        it.setForTest("wander_gain", 0.0)
        it.setForTest("hero_gain", 0.0)
    }
}
