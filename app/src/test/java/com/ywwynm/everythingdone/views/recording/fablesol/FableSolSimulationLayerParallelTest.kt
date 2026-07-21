package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C3（perFrame 的 comp 段）与 C8（物理子步的层循环）都改成了按层并行。
 *
 * 两项的等价论证是同一条：每层只写自身状态与 `heights[i]`，跨层量在层循环之前
 * 写好、循环内只读，`surface2d.advance` 仍留在层循环之后串行。因此并行只改变完成
 * 顺序，不改变任何一层的逐位结果。
 *
 * 这里把同一组输入跑满整条模拟，用**串行**结果当稳定基准，反复与并行结果逐位对拍。
 * 基准取串行而不是"并行跑两遍互比"是有意的：某层若误写共享 scratch，两遍并行有可能
 * 恰好同向出错而互相吻合，与串行比则必然分叉。多轮重复是为了让不同的线程交错都有
 * 机会出现（实测这台机器上后台 worker 承担了约 3/4 的层任务，交错是真实发生的）。
 */
class FableSolSimulationLayerParallelTest {

    @Test
    fun 并行层循环与串行逐位相同() {
        org.junit.Assume.assumeTrue(FableSolRowParallel.parallelWorkerCountForTest > 0)
        val reference = runSimulation(parallel = false)
        repeat(6) { round ->
            val actual = runSimulation(parallel = true)
            assertEquals("第 $round 轮长度不同", reference.size, actual.size)
            for (index in reference.indices) {
                if (reference[index] != actual[index]) {
                    throw AssertionError(
                        "第 $round 轮第 $index 个采样点与串行基准不逐位相同：" +
                            "serial=${java.lang.Double.longBitsToDouble(reference[index])} " +
                            "parallel=${java.lang.Double.longBitsToDouble(actual[index])}"
                    )
                }
            }
        }
    }

    /** 串行路径自身必须可复现，否则上面那条对拍的基准就不成立。 */
    @Test
    fun 串行层循环可复现() {
        val first = runSimulation(parallel = false)
        val second = runSimulation(parallel = false)
        for (index in first.indices) assertEquals(first[index], second[index])
    }

    /**
     * 结构门禁：两个层任务体只准通过 `ls.` 触碰可变状态。
     *
     * 为什么需要它——上面那条对拍是**时序相关**的：worker 是否真的抢到某一轮的层任务
     * 取决于它当时在自旋还是已经挂起。实测把 `ls.heroShiftedX` 换成
     * `layers[0].heroShiftedX`（人为制造跨层共享写）后，对拍并没有失败，而同样位置
     * 一个 1e-13 的确定性差异立刻被抓住——说明harness 本身是灵的，只是那一轮恰好
     * 没有交错。竞态无法用单测证伪，只能把"层体不碰共享可变量"这条不变量本身钉住。
     *
     * 两条断言合起来是可判定的：四组 scratch 只声明在 FableSolLayerSim 上（Simulation
     * 不再持有同名字段），且两个层体不按下标访问 `layers[]`。于是层体里任何一个
     * 同名标识符都只能是从传入的 `ls` 取来的局部别名，跨层共享写无处可藏。
     * heroVisibleMask / heroSourceWeight 是层循环前写好的只读量，不在禁列。
     */
    @Test
    fun 层任务体不得触碰层外可变状态() {
        val source = simulationSource()
        val layerSimStart = source.indexOf("class FableSolLayerSim(")
        val simulationStart = source.indexOf("class FableSolSimulation(")
        assertTrue(layerSimStart in 0 until simulationStart)
        val layerSimBlock = source.substring(layerSimStart, simulationStart)
        val simulationBlock = source.substring(simulationStart)

        for (name in MIGRATED_SCRATCH) {
            assertTrue(
                "$name 必须声明在 FableSolLayerSim 上",
                Regex("val $name\\b").containsMatchIn(layerSimBlock)
            )
            assertFalse(
                "FableSolSimulation 不得再持有同名的 $name——那正是层并行前的共享 scratch",
                Regex("(private|@JvmField)[^\\n]*\\b(val|var) $name\\b")
                    .containsMatchIn(simulationBlock)
            )
        }

        for (body in listOf("private fun perFrameLayer(", "private fun physicsLayerStep(")) {
            val start = source.indexOf(body)
            assertTrue("找不到 $body", start >= 0)
            // 到下一个顶层函数声明为止。
            val next = source.indexOf("\n    private fun ", start + body.length)
            val end = if (next < 0) source.length else next
            assertFalse(
                "$body 里不得按下标访问 layers[]，只能操作传入的 ls",
                source.substring(start, end).contains("layers[")
            )
        }
    }

    private fun simulationSource(): String {
        var directory = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = java.io.File(
                directory,
                "app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/" +
                    "FableSolSimulation.kt"
            )
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到 FableSolSimulation.kt")
    }

    /** 层任务里任何一次异常都必须传回调用线程，而不是被吞掉留下半更新的水面。 */
    @Test
    fun 层任务的异常不会被静默吞掉() {
        val visits = java.util.concurrent.atomic.AtomicInteger()
        var thrown: Throwable? = null
        try {
            FableSolRowParallel.runUnits(FableSolSpec.N_LAYERS) { start, end ->
                for (unit in start until end) {
                    visits.incrementAndGet()
                    if (unit == FableSolSpec.N_LAYERS - 1) error("层任务失败")
                }
            }
        } catch (error: Throwable) {
            thrown = error
        }
        assertTrue("异常必须传回调用线程", thrown != null)
        assertTrue(visits.get() > 0)
    }

    /**
     * 一次完整的模拟重放：注入、节拍、倾斜、材质驱动都给上非平凡输入，确保
     * perFrame 与物理子步两条层循环都跑在有内容的状态上（静默水面不足以暴露竞态）。
     */
    private fun runSimulation(parallel: Boolean): LongArray {
        val params = FableSolParams()
        val sim = FableSolSimulation(params)
        sim.parallelLayerLoopsEnabled = parallel
        sim.setContainerWidthDp(288.0)
        sim.setTilt(11.0, snap = true)
        sim.setBeat(96.0, 0.25, 0.8)
        sim.setColorDrive(0.62, 0.55)
        sim.setMaterialDrive(0.42, 0.3)
        sim.setSpatialDrive(0.7, 0.35)
        sim.setTension01(0.4)
        sim.flow01 = 0.6
        sim.flow01Deep = 0.4
        sim.breath01 = 0.5
        sim.sparkle01 = 0.8

        repeat(FRAMES) { frame ->
            if (frame % 7 == 0) {
                sim.injectEvent(
                    xDp = -40.0 + 12.0 * (frame % 5),
                    widthDp = 96.0,
                    ampDp = 4.2,
                    travel = -0.7,
                    layerAmps = DoubleArray(FableSolSpec.N_LAYERS) { 1.0 - 0.05 * it },
                    cascade = frame % 14 == 0
                )
            }
            if (frame % 11 == 0) {
                sim.surface2d.injectPacket(sim, strength = 0.5, pan01 = 0.4, zDominant = frame % 22 == 0)
            }
            sim.setTilt(11.0 + 4.0 * kotlin.math.sin(frame * 0.09))
            // dt 略大于一个物理步长，稳定产生 substeps ≥ 1 且偶尔为 2。
            sim.update(1.0 / 55.0)
        }

        val sample = sim.surface2d.sample(sim)
        val rows = FableSolContinuousSurface.Z_ROWS
        val lo = sample.windowLo
        val hi = sample.windowHi
        val out = LongArray(
            FableSolSpec.N_LAYERS * FableSolSpec.N_POINTS + rows * (hi - lo + 1) * 2
        )
        var cursor = 0
        // 九层高度场是 comp 段（C3）的直接产物。
        for (layer in 0 until FableSolSpec.N_LAYERS) {
            val row = sim.heights[layer]
            for (n in 0 until FableSolSpec.N_POINTS) {
                out[cursor++] = java.lang.Double.doubleToRawLongBits(row[n])
            }
        }
        // worldEta 与 orbitX 再把物理子步（C8）推进出的波场一并锁进来。
        for (r in 0 until rows) {
            val world = sample.worldEta[r]
            val orbitX = sample.orbitX[r]
            for (x in lo..hi) {
                out[cursor++] = java.lang.Double.doubleToRawLongBits(world[x])
                out[cursor++] = java.lang.Double.doubleToRawLongBits(orbitX[x])
            }
        }
        assertEquals(out.size, cursor)
        return out
    }

    private companion object {
        const val FRAMES = 140

        /** C3 从 Simulation 迁进 FableSolLayerSim 的四组 scratch（迁移清单即此）。 */
        val MIGRATED_SCRATCH = listOf(
            "heroBandTargetScratch",
            "heroShiftedX",
            "heroInterpIndex",
            "heroInterpFraction"
        )
    }
}
