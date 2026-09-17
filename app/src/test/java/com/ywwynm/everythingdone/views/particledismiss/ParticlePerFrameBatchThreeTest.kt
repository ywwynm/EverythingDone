package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐帧成本专项第三批（2026-09-17）的契约：节拍相位锁定。
 *
 * 本批是 G0——画面内容一个比特不动，改的只是「这一拍的目标时刻怎么算、这一帧最早允许
 * 什么时候上屏」。JVM 上能守住的是分频的纯算术与两条结构约束；真机那一侧由
 * `analyze_beats.py` 的呈现间隔直方图给判据。
 */
class ParticlePerFrameBatchThreeTest {

    private val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
        .first { File(it, "shared/particle-dismiss/rules.properties").isFile }

    private fun main(name: String) =
        File(root, "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/$name").readText()

    /** 注释不算实现：断言「某处必须/不得出现某个调用」之前先把注释去掉。 */
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

    private fun body(source: String, declaration: String, until: String): String =
        code(source.substringAfter(declaration).substringBefore(until))

    // ---- 分频 ----

    /** 按门限走一遍 vsync 序列，返回实际出拍的那些时刻。 */
    private fun beats(vsyncNanos: Long, count: Int): List<Long> {
        val threshold = ParticleClock.beatThreshold(vsyncNanos)
        val out = ArrayList<Long>()
        var last = 0L
        for (i in 0 until count) {
            val now = i * vsyncNanos
            if (i == 0 || now - last >= threshold) { out += now; last = now }
        }
        return out
    }

    private fun outputHz(vsyncNanos: Long): Double {
        val b = beats(vsyncNanos, 400)
        return 1e9 * (b.size - 1) / (b.last() - b.first()).toDouble()
    }

    @Test fun `出拍门限给出不低于 60 Hz 的最省的那一档`() {
        // 120.8 Hz（魅族触摸期实测 8.276 ms）：每两个 vsync 一拍。
        assertEquals(60.4, outputHz(8_276_000L), .5)
        assertEquals(60.0, outputHz(8_333_333L), .5)
        // 60.5 Hz（魅族实测 16.53 ms，不是 16.67）：每个 vsync 都出拍。
        assertEquals(60.5, outputHz(16_529_000L), .5)
        assertEquals(60.0, outputHz(16_666_666L), .5)
        // 比 60 稍慢的面板同样每拍都出，不能一拍都不出。
        assertEquals(59.5, outputHz(16_800_000L), .5)
        // 90 Hz：11.11 ms 恰好压在「减半个 vsync」的门限上，必须再让出一点余量，
        // 否则一半时候掉到 45 Hz——比 60 还差。
        assertEquals(90.0, outputHz(11_111_111L), .5)
        // 144 Hz（华为 MatePad Pro 13.2 有这一档）：每两个 vsync 一拍 = 72 Hz。
        assertEquals(72.0, outputHz(6_944_444L), .5)
    }

    @Test fun `还没量到 vsync 周期时每拍都出`() {
        // 第一拍之前没有间隔可量，门限必须是 0，否则节拍整个起不来。
        assertEquals(0L, ParticleClock.beatThreshold(0L))
    }

    @Test fun `没量到节拍间隔时输出周期退回固定间隔`() {
        // 预算截止时间与呈现时刻下限都按它倒推，不能给出 0。
        assertTrue(ParticleClock.outputPeriodNanos > 0)
    }

    // ---- 结构约束 ----

    @Test fun `相位锁定按「距上一拍多久」判，目标是本次 vsync`() {
        val locked = body(main("ParticleClock.kt"), "private fun phaseLockedTarget", "\n    /**")
        // vsync 投递本身不规律（魅族实测同一次左滑里 8.28 与 16.55 交替），
        // 按 vsync 计数分频会跟着乱，必须按时间判。
        assertTrue("按距上一拍的时长判", "frameTimeNanos - lastBeatVsync" in locked
            && "beatThreshold" in locked)
        assertTrue("目标就是本次 vsync 时刻", "return frameTimeNanos" in locked)
        // 系统给的预期呈现时刻在魅族上是 vsync + 26.0 ms，而我们实际达到 vsync + 17.7 ms，
        // 按它写目标等于主动多等一个显示周期。
        assertFalse("不得把预期呈现时刻当成目标", "expectedPresentation" in locked)
    }

    @Test fun `输出周期量的是节拍自己的间隔，不由 vsync 周期推算`() {
        val period = body(main("ParticleClock.kt"), "val outputPeriodNanos", "\n    /**")
        assertTrue("取节拍间隔的滑动均值", "beatPeriod" in period)
        assertFalse("不按 vsync 周期乘分频数推算", "measuredVsyncNanos" in period)
        val locked = body(main("ParticleClock.kt"), "private fun phaseLockedTarget", "\n    /**")
        assertTrue("异常间隔不计进均值", "MIN_BEAT_NANOS..MAX_BEAT_NANOS" in locked)
    }

    @Test fun `呈现时刻仍写本拍目标，不设下限`() {
        val composite = body(main("ParticleWindowSurface.kt"),
            "private fun compositeOnce", "fun resolvePresented")
        // 同批试过写「本拍 vsync + 一个输出周期 − 余量」的下限，想挡住「早到一个显示周期」
        // 的那一档。魅族上根本不生效：下限在 vsync + 12.6 ms，仍有 28/69 帧在 vsync + 8.3 ms
        // 就上屏了。量不出效果又可能在别的机型上多等一个周期，所以不留。
        assertTrue("写本拍目标 = 尽快呈现",
            "eglPresentationTimeANDROID(ParticleGl.display, eglSurface, target)" in composite)
        assertFalse("不留没有可测收益的下限", "presentationTime(" in composite)
    }

    @Test fun `第三批只留相位锁定一个开关且默认开`() {
        assertTrue(ParticlePerfTuning.beatPhaseLock)
        // 同批评估过的「栅栏挪到两拍之间轮询」被取证证伪（等待实测 0.02 ms），整段删除。
        val tuning = code(main("ParticlePerfTuning.kt"))
        assertFalse("被证伪的项不留在代码里", "fenceOffBeat" in tuning)
        val gl = code(main("ParticleGl.kt"))
        assertFalse("pump 里也不留", "pollFence" in gl)
    }

    // ---- 取证 ----

    @Test fun `取证在 release 包里是零开销`() {
        val trace = main("ParticleBeatTrace.kt")
        assertTrue("记录点先看 BuildConfig.DEBUG", "if (!BuildConfig.DEBUG || !enabled) return" in trace)
        assertTrue("有上限，不能一直涨", "CAPACITY" in trace)
    }

    @Test fun `没画的那一拍要记下卡在哪一条判定上`() {
        val skip = body(main("ParticleSession.kt"), "fun traceSkip(target: Long)", "\n    /**")
        for (reason in listOf("SKIP_CANCELLED", "SKIP_INVISIBLE", "SKIP_STAGE",
                "SKIP_NO_RENDERER", "SKIP_SAME_POSITION")) {
            assertTrue("原因码 $reason 要能被记到", reason in skip)
        }
        val composite = body(main("ParticleWindowSurface.kt"),
            "private fun compositeOnce", "fun resolvePresented")
        assertTrue("needsFrame 判否时逐层记一次", "sessions.forEach { it.traceSkip(target) }" in composite)
    }
}
