package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1 把 `sample()` 的 field 段从「模态外层、列内层」改成「列外层、状态数组交错推进」。
 *
 * 互换只改变遍历次序与内存趟数，不改变任何一次浮点运算的操作数或到达顺序，
 * 因此两条路径必须**逐位**相同（`doubleToRawLongBits` 相等，而非某个容差内相等）。
 * 旧路径由 `forceModeOuterFieldForTest` 永久保留，本测试是它唯一的用途，也是
 * 这项优化的硬门禁——任何一处结合序被"顺手整理"都会在这里立刻暴露。
 */
class FableSolContinuousFieldParityTest {

    @Test
    fun 列外层field与模态外层在生产规模下逐位相同() {
        var comparedWindows = 0
        for (configuration in 0 until 4) {
            val sim = buildSimulation(configuration)
            // 全 216 列（倾斜触顶时的最坏窗口）与自然裁窗（竖屏 θ≈0 时约 116 列）
            // 两种规模都要覆盖：列数决定递推链长度，裁窗还改变 rawLo 的相位起点。
            val natural = sim.continuousRenderInfo()
            val windows = listOf(
                FableSolRenderInfo(0, FableSolSpec.N_POINTS, natural.thetaRad, natural.hG),
                natural
            )
            for (info in windows) {
                val surface = sim.surface2d
                surface.forceModeOuterFieldForTest = true
                val reference = snapshot(surface.sample(sim, info))
                surface.forceModeOuterFieldForTest = false
                val actual = snapshot(surface.sample(sim, info))

                assertEquals(reference.size, actual.size)
                assertTrue("窗口为空：configuration=$configuration", reference.isNotEmpty())
                for (index in reference.indices) {
                    if (reference[index] != actual[index]) {
                        throw AssertionError(
                            "field 列外层与模态外层不逐位相同：configuration=$configuration " +
                                "slot=$index reference=${java.lang.Double.longBitsToDouble(reference[index])} " +
                                "actual=${java.lang.Double.longBitsToDouble(actual[index])}"
                        )
                    }
                }
                comparedWindows++
            }
        }
        assertEquals(8, comparedWindows)
    }

    /** 波包数达到自然上限时链条数最多，交错推进的槽位分配也最容易出错。 */
    @Test
    fun 满波包配置下同样逐位相同() {
        val sim = buildSimulation(0)
        repeat(7) { index ->
            sim.surface2d.injectPacket(
                sim,
                strength = 0.35 + 0.09 * index,
                pan01 = 0.12 + 0.11 * index,
                zDominant = index % 2 == 0
            )
        }
        repeat(24) { sim.update(FableSolSpec.PHYSICS_DT) }

        val info = sim.continuousRenderInfo()
        sim.surface2d.forceModeOuterFieldForTest = true
        val reference = snapshot(sim.surface2d.sample(sim, info))
        assertTrue(sim.surface2d.perfPacketCount >= 4)
        sim.surface2d.forceModeOuterFieldForTest = false
        val actual = snapshot(sim.surface2d.sample(sim, info))

        for (index in reference.indices) {
            assertEquals(reference[index], actual[index])
        }
    }

    /** 没有波包时只剩九条模态链，覆盖 packetCount = 0 的边界。 */
    @Test
    fun 静态零输入下同样逐位相同() {
        val sim = FableSolSimulation(FableSolParams())
        val info = sim.continuousRenderInfo()
        sim.surface2d.forceModeOuterFieldForTest = true
        val reference = snapshot(sim.surface2d.sample(sim, info))
        sim.surface2d.forceModeOuterFieldForTest = false
        val actual = snapshot(sim.surface2d.sample(sim, info))

        for (index in reference.indices) {
            assertEquals(reference[index], actual[index])
        }
    }

    private fun buildSimulation(configuration: Int): FableSolSimulation {
        val params = FableSolParams()
        params.setForTest("surface_heading_deg", 12.0 + 9.0 * configuration)
        params.setForTest("surface_spread_deg", 18.0 + 11.0 * configuration)
        params.setForTest("ambient_gain", 0.7 + 0.25 * configuration)
        val sim = FableSolSimulation(params)
        sim.setContainerWidthDp(276.0 + 22.0 * configuration)
        sim.setTilt(configuration * 9.0, snap = true)
        sim.setColorDrive(0.3 + 0.15 * configuration, 0.25 + 0.2 * configuration)
        sim.injectEvent(
            xDp = -60.0 + 40.0 * configuration,
            widthDp = 90.0,
            ampDp = 3.5 + configuration,
            travel = -0.6,
            layerAmps = DoubleArray(FableSolSpec.N_LAYERS) { 1.0 - 0.06 * it },
            cascade = true
        )
        sim.surface2d.injectPacket(sim, strength = 0.4 + 0.12 * configuration,
            pan01 = 0.3 + 0.1 * configuration)
        // 推进若干子步，让相位、波包位置与九层轮廓都离开初值。
        repeat(18 + 7 * configuration) { sim.update(FableSolSpec.PHYSICS_DT) }
        return sim
    }

    /**
     * 把整个窗口内的 field 下游产物打成一串 raw bits。worldEta / 两路轨道及其解析
     * 切线、两路坡度全部由 field 结果推出，任何一位差异都会落进这串比较里。
     */
    private fun snapshot(sample: FableSolContinuousSurface.Sample): LongArray {
        val lo = sample.windowLo
        val hi = sample.windowHi
        val rows = FableSolContinuousSurface.Z_ROWS
        val columns = hi - lo + 1
        val sources = listOf(
            sample.eta, sample.worldEta, sample.orbitX, sample.orbitZ,
            sample.orbitXSlope, sample.orbitZSlope, sample.slopeX, sample.slopeZ
        )
        val out = LongArray(sources.size * rows * columns)
        var cursor = 0
        for (source in sources) {
            for (r in 0 until rows) {
                val row = source[r]
                for (x in lo..hi) out[cursor++] = java.lang.Double.doubleToRawLongBits(row[x])
            }
        }
        return out
    }
}
