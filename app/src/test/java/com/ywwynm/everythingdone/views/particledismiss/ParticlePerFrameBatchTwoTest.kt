package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐帧成本专项第二批（2026-09-17）的契约。
 *
 * 本批全部是 G0：只改「什么时候在哪条命令流上算」，一帧的像素一个比特不动。能在 JVM 上
 * 守住的是两类东西——预读档位的纯算术，以及几条**必须成立才不会出错**的结构约束
 * （优先级要读回、跨上下文交接要 fence→flush→wait、出帧那一侧不许等低优先级的在途工作）。
 * 真正的收益与逐位一致性由真机矩阵与 `ParticleTrajectoryCheck` 给。
 */
class ParticlePerFrameBatchTwoTest {

    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "shared/particle-dismiss/rules.properties").isFile }

    private fun main(name: String) =
        File(root, "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/$name").readText()

    /**
     * 注释不算实现。断言「某处必须 / 不得出现某个调用」之前先把注释去掉，否则一条解释
     * 为什么不能这么写的注释就能让禁令测试判死，或者让肯定式断言假通过。
     */
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

    // ---- F15：预读档位的算术 ----

    @Test fun `预读档数随速度成比例且有上限`() {
        // 停住与慢拖：一档就够，画面每拍都能取到手指要的相位。
        assertEquals(1, ParticleSession.gestureLookahead(0f))
        assertEquals(2, ParticleSession.gestureLookahead(.4f))
        assertEquals(2, ParticleSession.gestureLookahead(1f))
        // 快拖一拍跨三四档，下一拍要画的档位必须已经在备的范围里。
        assertEquals(4, ParticleSession.gestureLookahead(2.6f))
        assertEquals(5, ParticleSession.gestureLookahead(3.2f))
        // 再快也不超过一拍能算完的量，多备只是白占 GPU。
        assertEquals(ParticleSession.GESTURE_MAX_LOOKAHEAD, ParticleSession.gestureLookahead(40f))
        assertEquals(1, ParticleSession.gestureLookahead(Float.NaN))
    }

    @Test fun `目标档位只增不减且不超过总档数`() {
        val samples = 36
        // 反向拖回来时 need 变小，目标不能跟着退——历史只增不减，退了就要重算。
        assertEquals(20, ParticleSession.gestureGoal(20, 3, 2, samples))
        assertEquals(12, ParticleSession.gestureGoal(4, 10, 2, samples))
        assertEquals(samples, ParticleSession.gestureGoal(4, samples, 6, samples))
        assertEquals(samples, ParticleSession.gestureGoal(samples, 2, 6, samples))
    }

    @Test fun `按下阶段与停住时的档数比第一批更长`() {
        assertTrue("甩动时材料就绪已经拖过两成，按下阶段要备得够深",
            ParticleSession.GESTURE_PREROLL >= 12)
        assertTrue("预读不再阻塞 CPU，手指停住那段是真的空闲",
            ParticleSession.GESTURE_IDLE_LOOKAHEAD >= ParticleSession.gestureLookahead(1f))
    }

    // ---- F14：两个上下文 ----

    @Test fun `优先级必须读回来核对，两档没分开就退回单上下文`() {
        val source = main("ParticleGl.kt")
        val ensure = body(source, "private fun ensureBackgroundContext", "private fun measureSwitchCost")
        assertTrue("请求值只是 hint，必须 eglQueryContext 读回", "eglQueryContext" in code(source))
        assertTrue("读回的档位要进日志与观察器产物", "contextPriorities" in ensure)
        assertTrue("两档没分开就把后台上下文销毁掉", "eglDestroyContext" in ensure)
        assertTrue("驱动没有这个扩展时不建第二个上下文",
            "EGL_IMG_context_priority" in ensure || "priorityAvailable" in ensure)
    }

    @Test fun `切到后台之前必须 fence + flush，切过去再 wait`() {
        val source = main("ParticleGl.kt")
        val toBackground = body(source, "fun useBackground(): Boolean", "fun useForeground()")
        assertTrue("出帧侧刚上传的材料要对后台可见", "glFenceSync" in toBackground)
        assertTrue("glWaitSync 没有自带的 flush 位，生产侧必须自己冲", "glFlush" in toBackground)
        assertTrue("消费侧服务端等待，不阻塞 CPU", "glWaitSync" in toBackground)
    }

    @Test fun `切回出帧不得等后台的在途工作`() {
        val toForeground = body(main("ParticleGl.kt"), "fun useForeground()", "private fun chooseConfig")
        assertFalse("高优先级的一帧去等低优先级上下文，就是优先级反转",
            "glWaitSync" in toForeground)
    }

    // ---- F15：异步预读的交接不变式 ----

    @Test fun `可画档位只算确认完成的采样`() {
        val source = main("ParticleMicroflakeRenderer.kt")
        val prepared = body(source, "val preparedSamples get()", "\n")
        assertTrue("异步模式下可画档位取已完成的那一个", "reverseCompleted" in prepared)
        val submitted = body(source, "val submittedSamples get()", "\n")
        assertTrue("已提交的那一个另算", "reverseFrame - 1" in submitted)
        val poll = body(source, "fun pollReverse(): Boolean", "private fun drainReverse")
        assertTrue("轮询必须是非阻塞的（超时 0）", "glClientWaitSync(head[1], 0, 0)" in poll)
        assertTrue("确认完成之后才往前推可画档位", "reverseCompleted = head[0].toInt()" in poll)
    }

    @Test fun `提交一个采样之后必须冲一次`() {
        val advance = body(main("ParticleMicroflakeRenderer.kt"),
            "fun advanceReverse(cancelled", "private fun reverseInFlightLimit")
        val fence = advance.indexOf("glFenceSync")
        val flush = advance.indexOf("glFlush")
        assertTrue("提交之后要放栅栏", fence >= 0)
        assertTrue("栅栏之后必须 glFlush，否则消费侧可能永远等不到", flush > fence)
        assertFalse("异步口径里不许再阻塞等自己刚提交的那一批",
            "glClientWaitSync" in advance)
    }

    @Test fun `手势第 0 档留在出帧上下文里`() {
        val seed = body(main("ParticleMicroflakeRenderer.kt"), "fun seedGesture()", "\n    }")
        assertTrue("与首帧同流，次序由 GL 保证，直接算已完成", "reverseCompleted = 0" in seed)
        assertFalse("同流就不需要栅栏", "glFenceSync" in seed)
        val begin = body(main("ParticleSession.kt"), "private fun beginPrecompute()", "private fun stepBackground")
        assertTrue("调用方要显式切到出帧上下文", "ParticleGl.useForeground()" in begin)
    }

    // ---- 首帧不等节拍 ----

    @Test fun `提前出的首帧是把一拍挪前，不是多出一帧`() {
        val present = body(main("ParticleGl.kt"), "private fun presentFirstFrames", "private fun ensureContext")
        assertTrue("呈现时刻写当前时刻，即尽快；写到未来会加深流水线", "System.nanoTime()" in present)
        assertTrue("要替掉的那一拍必须真的吃掉，否则两张缓冲落在同一个显示周期里，"
            + "先提交的会被丢掉、永远等不到呈现时刻",
            "consumedTarget = maxOf(consumedTarget, now + ParticleClock.INTERVAL_NANOS)" in present)
        assertTrue("所有窗口一起合成，别的会话不会因为这次提前而少一帧",
            "for (window in live) window.composite(now)" in present)
    }

    @Test fun `有会话在出帧时预算不许跨过下一拍`() {
        val deadline = body(main("ParticleGl.kt"), "private fun workDeadline", "private fun presentFirstFrames")
        // 第三批把「一拍多长」改成按真实 vsync 量出来的值（16.53–16.55 ms，不是 16.67）。
        assertTrue("按节拍间隔倒推", "ParticleClock.outputPeriodNanos" in deadline)
        assertTrue("给合成与呈现回调留出余量", "FRAME_RESERVE_NANOS" in deadline)
        assertTrue("没有会话在出帧时仍用完整预算", "presenting" in deadline)
    }

    // ---- 开关 ----

    @Test fun `第二批三项默认开，首帧提前量不出收益默认关`() {
        assertTrue(ParticlePerfTuning.backgroundContext)
        assertTrue(ParticlePerfTuning.asyncPrecompute)
        assertTrue(ParticlePerfTuning.yieldWorkBeforeBeat)
        assertFalse("上限只有一拍，两端都落在单轮噪声里；不留「理论上更快」的改动",
            ParticlePerfTuning.immediateFirstFrame)
        assertEquals("在途上限默认沿用两批在途", 0, ParticlePerfTuning.reverseInFlight)
        val attach = body(main("ParticlePerfTuning.kt"), "fun attach(filesDir: File)", "\n    /**")
        for (key in listOf("backgroundContext", "asyncPrecompute", "reverseInFlight",
                "yieldWorkBeforeBeat", "immediateFirstFrame")) {
            assertTrue("$key 要能由 files/ 的配置覆盖", "\"$key\"" in attach)
        }
    }
}
