package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 离线导出的定步长门禁（fablesol-video-export D9 / 第四轮评审第 1 条）。
 *
 * D9 用来论证 120fps 的核心理由是「渲染与物理正好 1:1，两者之间没有时间混叠」。这条断言
 * 一度**从未成立**：导出把整数纳秒帧时间戳交给渲染器反推 dt，`1e9/120` 截断后比
 * [FableSolSpec.PHYSICS_DT] 少约 0.33ns，定步长累加器于是给出 `0,1,2` 循环——平均速度对，
 * 相邻帧却要么原地不动要么跳两步。
 *
 * 这里把「有理数步长恒定 1 步 / 2 步」钉死，并同时把当年那个截断写法的失败形态记录下来，
 * 免得有人"顺手"改回按时间戳推 dt。
 */
class FableSolExportFixedDtTest {

    private fun substepsPerFrame(dt: Double, frames: Int): List<Int> {
        val sim = FableSolSimulation(FableSolParams())
        val counts = ArrayList<Int>(frames)
        repeat(frames) {
            sim.update(dt)
            counts.add(sim.perfSubsteps)
        }
        return counts
    }

    @Test
    fun exactDtAt120FpsGivesExactlyOneSubstepEveryFrame() {
        val counts = substepsPerFrame(1.0 / 120.0, FRAMES)
        assertEquals(List(FRAMES) { 1 }, counts)
    }

    @Test
    fun exactDtAt60FpsGivesExactlyTwoSubstepsEveryFrame() {
        val counts = substepsPerFrame(1.0 / 60.0, FRAMES)
        assertEquals(List(FRAMES) { 2 }, counts)
    }

    /**
     * 反例：按整数纳秒时间戳反推 dt 会退化成不均匀的子步序列。
     * 断言"不是恒 1"——它记录的是被修掉的那个 bug，而不是期望行为。
     */
    @Test
    fun nanosecondTimestampDtDegradesIntoAnUnevenSubstepPattern() {
        val sim = FableSolSimulation(FableSolParams())
        val counts = ArrayList<Int>(FRAMES)
        var previousNanos = 0L
        for (frame in 1..FRAMES) {
            val nanos = frame.toLong() * 1_000_000_000L / 120L
            sim.update((nanos - previousNanos) / 1_000_000_000.0)
            previousNanos = nanos
            counts.add(sim.perfSubsteps)
        }
        assertNotEquals(List(FRAMES) { 1 }, counts)
        // 总量仍然守恒：不均匀的是分布，不是平均速度。
        assertEquals(FRAMES, counts.sum())
    }

    private companion object {
        const val FRAMES = 240
    }
}
