package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 飘散高帧率的契约（2026-10-06）：高帧率节拍的分频、粒子 Surface 的刷新率投票、左滑的补帧。
 *
 * JVM 上守的是分频的纯算术与几条必须成立的结构约束；真正的帧率与出帧由真机给
 * （`device/perf_compare.py --async-input`、`device/cadence_record.py`）。
 */
class ParticleDriftFrameRateTest {

    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "shared/particle-dismiss/rules.properties").isFile }

    /** 换行统一成 LF：检出时 autocrlf 可能给出 CRLF。 */
    private fun main(name: String) =
        File(root, "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/$name").readText()
            .replace("\r\n", "\n")

    /** 注释不算实现：断言之前先去掉注释。 */
    private fun code(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                }
                text.startsWith("//", i) -> {
                    val end = text.indexOf('\n', i)
                    i = if (end < 0) text.length else end
                }
                else -> { append(text[i]); i++ }
            }
        }
    }

    /** 取一个函数从声明到下一个同级声明之间的正文（够用即可，只服务于契约断言）。 */
    private fun body(source: String, declaration: String, until: String): String =
        code(source.substringAfter(declaration).substringBefore(until))

    // ---- 分频 ----

    /** 按高帧率门限走一遍 vsync 序列，返回实际出拍的频率。 */
    private fun highRateHz(vsyncNanos: Long): Double {
        val threshold = ParticleClock.beatThreshold(vsyncNanos, ParticleClock.HIGH_INTERVAL_NANOS)
        var first = -1L
        var last = 0L
        var count = 0
        for (i in 0 until 400) {
            val now = i * vsyncNanos
            if (i == 0 || now - last >= threshold) {
                if (first < 0) first = now
                last = now
                count++
            }
        }
        return 1e9 * (count - 1) / (last - first).toDouble()
    }

    @Test fun `高帧率节拍在 60 到 144 Hz 的面板上都是每个 vsync 一拍`() {
        // 魅族触摸期实测 8.276 ms、三星 8.333 ms
        assertEquals(120.8, highRateHz(8_276_000L), .5)
        assertEquals(120.0, highRateHz(8_333_333L), .5)
        assertEquals(90.0, highRateHz(11_111_111L), .5)
        // 144 Hz 面板按 144 帧出，没有限到 120
        assertEquals(144.0, highRateHz(6_944_444L), .5)
        assertEquals(60.0, highRateHz(16_666_666L), .5)
    }

    @Test fun `缺省节拍仍按 60 Hz 分频`() {
        assertTrue("120 Hz 面板在缺省间隔下要隔一个 vsync 才出拍",
            ParticleClock.beatThreshold(8_333_333L, ParticleClock.INTERVAL_NANOS) > 8_333_333L)
    }

    // ---- 刷新率投票 ----

    @Test fun `空闲窗口不投票，只有飘散时投 120，有流体时投 60`() {
        val update = body(main("ParticleGl.kt"), "private fun updateFrameRate()", "\n    }")
        assertTrue("有会话的窗口全是飘散才切高帧率", "busy.all { it.prefersHighFrameRate }" in update)
        assertTrue("节拍间隔跟着切", "ParticleClock.setHighRate(high)" in update)
        // 粒子 Surface 常驻投 60 时，SurfaceFlinger 给整个 uid 设 60 Hz 的帧率覆盖，首页滚动也被压住
        assertTrue("没有会话的窗口不投", "!window.busy -> 0f" in update)
        assertTrue("只有飘散投高帧率", "high -> ParticleWindowSurface.HIGH_FRAME_RATE" in update)
        assertTrue("其余投 60", "ParticleWindowSurface.VOTED_FRAME_RATE" in update)
    }

    @Test fun `流体会话不按高帧率出`() {
        assertFalse("轨迹按 60 Hz 预算，不能覆盖成高帧率",
            "prefersHighFrameRate" in code(main("ParticleSession.kt")))
        val layer = code(main("ParticleLayerSession.kt"))
        assertTrue("接口缺省是 false", "val prefersHighFrameRate: Boolean get() = false" in layer)
        assertTrue("飘散每帧是闭式函数，任意时刻都能画",
            "override val prefersHighFrameRate get() = true" in code(main("ParticleDriftSession.kt")))
    }

    // ---- 左滑补帧 ----

    @Test fun `补帧只在高帧率下、只补当前这一拍、每个窗口每拍最多一帧`() {
        val source = main("ParticleGl.kt")
        val late = body(source, "private fun lateFrames", "\n    }")
        assertTrue("60 Hz（有流体会话）时不做", "ParticleClock.intervalNanos != ParticleClock.HIGH_INTERVAL_NANOS" in late)
        assertTrue("首帧提前吃掉的那一拍、已经来了的下一拍都不补", "target != beatTarget" in late)
        assertTrue("只在本拍前半个节拍间隔里补", "ParticleClock.intervalNanos / 2" in late)
        assertTrue("本窗口这一拍画过就不再画", "window.compositedTarget == target" in late)
        assertTrue("只补手势会话，不按「想不想出帧」补", "window.wantsLateFrame()" in late && "needsFrame" !in late)
        val drift = body(main("ParticleDriftSession.kt"), "override fun wantsLateFrame()", "\n\n")
        assertTrue("首帧仍按节拍出，补帧只给出过帧的左滑", "isGesture" in drift && "firstFrameSubmitted" in drift)
        assertTrue("位置真的变了才补", "gesturePosition() != previousPosition" in drift)
        assertTrue("新的一拍照常合成，补帧只发生在不是节拍的那几次唤醒里",
            "else lateFrames(live)" in body(source, "private fun pump()", "\n    }"))
        val gestureMoved = body(source, "fun gestureMoved()", "\n    }")
        assertTrue("60 Hz 时不唤醒", "ParticleClock.HIGH_INTERVAL_NANOS" in gestureMoved)
    }

    @Test fun `窗口只在真的合成时记下这一拍`() {
        val composite = body(main("ParticleWindowSurface.kt"), "private fun compositeOnce", "\n    }")
        val skip = composite.indexOf("if (!needsFrame())")
        val mark = composite.indexOf("compositedTarget = target")
        assertTrue("这一拍没出帧时不能记成画过，否则位置晚到的那一拍补不上", skip in 0 until mark)
    }

    @Test fun `左滑写完位置就唤醒 GL 线程`() {
        val track = body(main("ParticleSwipeAnimator.kt"), "fun track(displacement: Float)", "\n        }")
        val update = track.indexOf("gesture.update(")
        val wake = track.indexOf("ParticleGl.gestureMoved()")
        assertTrue("先写位置再唤醒", update in 0 until wake)
    }
}
