package com.ywwynm.everythingdone.views.particledismiss

import com.ywwynm.everythingdone.views.GradientRippleDrawable
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第一批新增逻辑的 JVM 验证：播放时钟的相位、时长缩放补偿、历史合并的着色器变换。 */
class ParticleBatchOneTest {

    private val interval = (1e9 / 60.0).toLong()

    @Test fun `120Hz 上每两拍取一次且相位不漂移`() {
        val vsync = (1e9 / 120.0).toLong()
        var next = 0L
        val taken = ArrayList<Long>()
        for (i in 0 until 12) {
            val frame = i * vsync
            if (frame + ParticleClock.TOLERANCE_NANOS < next) continue
            val target = ParticleClock.presentationTarget(frame, next, interval)
            next = target + interval
            taken += target
        }
        // 目标始终是起点 + k × 16.67 ms，一个不多一个不少。
        assertEquals((0 until 6).map { it * interval }, taken)
    }

    @Test fun `一次迟到不会把后续相位吸附到迟到的那一拍上`() {
        var next = 0L
        // 第 0 拍正常取；随后 GPU 慢了，直到 40 ms 才回来。
        var target = ParticleClock.presentationTarget(0L, next, interval)
        next = target + interval
        assertEquals(0L, target)
        target = ParticleClock.presentationTarget(40_000_000L, next, interval)
        next = target + interval
        // 40 ms 落在第 2 个目标（33.3 ms）之后、第 3 个（50 ms）之前：取第 2 个，丢掉第 1 个。
        assertEquals(2 * interval, target)
        // 再下一拍仍然落在原相位上，不是「40 ms + 16.67 ms」。
        target = ParticleClock.presentationTarget(50_100_000L, next, interval)
        assertEquals(3 * interval, target)
    }

    @Test fun `涟漪标称寿命覆盖淡入与淡出`() {
        assertEquals(360L, GradientRippleDrawableLifetime)
    }

    private val GradientRippleDrawableLifetime: Long
        get() = GradientRippleDrawable.NOMINAL_FEEDBACK_LIFETIME_MS

    @Test fun `历史合并变换保留原积分主体并补上五分量写入`() {
        val source = File(repoRoot(), "shared/particle-dismiss/step.comp").readText()
        val plain = ParticleReverseShader.integration(source)
        val merged = ParticleReverseShader.integration(source, withHistory = true)
        // 原 main 改名为 integrate，新的 main 先调用它。
        assertTrue("void integrate(){" in merged)
        assertTrue("integrate();" in merged)
        assertFalse("void main(){\n    uint i=gl_GlobalInvocationID.x;" in merged)
        // 运动公式一个字都没改：改名之前的正文与不合并时完全相同。
        assertEquals(plain.replace("void main(){", "void integrate(){"),
            merged.substring(0, merged.indexOf("\n\nlayout(std430,binding=4) readonly buffer Ranges")))
        // 写入的五个分量、下标与 history.comp 的保存方向一致。
        val history = File(repoRoot(), "app/src/main/assets/particle-playback/history.comp").readText()
        for (line in listOf("history[h]=s.pos.x;history[h+1]=s.pos.y;history[h+2]=s.pos.z;",
                "history[h+3]=s.vel.x;history[h+4]=s.vel.y;")) {
            assertTrue(line, line in history)
            assertTrue(line, line in merged)
        }
        assertTrue("int h=(r.z+history_frame-r.x)*5;" in merged)
    }

    @Test fun `宏注入必须落在 version 之后`() {
        val source = "#version 310 es\nprecision highp float;\n"
        assertEquals("#version 310 es\n#define BAND 12\nprecision highp float;\n",
            ParticleReverseShader.withDefines(source, "#define BAND 12\n"))
        assertEquals(source, ParticleReverseShader.withDefines(source, ""))
    }

    private fun repoRoot(): File =
        generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "shared/particle-dismiss").isDirectory }
}
