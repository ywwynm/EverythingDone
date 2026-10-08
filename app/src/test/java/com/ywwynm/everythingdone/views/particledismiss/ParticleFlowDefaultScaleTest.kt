package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

/** 验证用户看到的 1× 基准：同一快照下数量接近飘散，大小不再随数量预算漂移。 */
class ParticleFlowDefaultScaleTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "shared/particle-dismiss/rules.properties").isFile }
    private val rules get() = ParticleMicroflakeRules.read(
        File(root, "shared/particle-dismiss/rules.properties").inputStream(),
        File(root, "shared/particle-dismiss/common-release.f32").inputStream())

    private fun build(w: Int, h: Int, density: Float, amount: Float = 1f, size: Float = 1f,
                      pixels: IntArray = IntArray(w * h) { 0xffeeeeee.toInt() }): ParticleMicroflakeModel.Materials {
        val scale = density / ParticleMicroflakeModel.DP_UNITS.toFloat()
        return ParticleMicroflakeModel.build(w / scale, h / scale, pixels, w, h, 135f, 129L,
            rules, cellDp = 0f, particleAmount = amount, particleSizeScale = size)
    }

    @Test fun `同一纯色快照的默认数量接近飘散`() {
        val failures = ArrayList<String>()
        for ((w, h) in listOf(630 to 300, 1050 to 1047, 1380 to 2281, 1440 to 3088)) {
            val flow = build(w, h, 3.75f)
            val drift = ParticleDriftModel.count(w, h, 1f)
            val ratio = flow.count.toFloat() / drift
            val diameter = sqrt(flow.cellX * flow.particleScaleX * flow.cellY * flow.particleScaleY) /
                ParticleMicroflakeModel.DP_UNITS.toFloat() * .9f
            val row = "$w x $h: flow=${flow.count}, drift=$drift, ratio=$ratio, contourDp=$diameter"
            println(row)
            if (abs(ratio - 1f) > .03f) failures += row
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `默认圆边基准接近飘散一倍且不随屏幕密度改变`() {
        for (density in listOf(1.5f, 2.75f, 3.75f, 4.5f)) {
            val flow = build(720, 960, density)
            val diameter = sqrt(flow.cellX * flow.particleScaleX * flow.cellY * flow.particleScaleY) /
                ParticleMicroflakeModel.DP_UNITS.toFloat() * .9f
            // 两种着色器仍保留各自的年龄／内容变化；此处比较不含生命周期缩放的圆边基准。
            assertEquals("density=$density", 1.6f, diameter, 2e-6f)
        }
    }

    @Test fun `数量倍率保持近似比例且不改变默认粒径`() {
        val base = build(1050, 1047, 3.75f)
        for (amount in listOf(.1f, .4f, 1.6f)) {
            val flow = build(1050, 1047, 3.75f, amount)
            assertEquals(amount, flow.count.toFloat() / base.count, amount * .03f)
            assertEquals(base.cellX * base.particleScaleX, flow.cellX * flow.particleScaleX, 1e-5f)
            assertEquals(base.cellY * base.particleScaleY, flow.cellY * flow.particleScaleY, 1e-5f)
        }
    }

    @Test fun `有色内容补充后的总量接近飘散且保留内容颗粒`() {
        val w = 1050; val h = 1047
        val pixels = IntArray(w * h) { i ->
            if (i % w in 90..720 && i / w in 100..280) 0xffdf3048.toInt() else 0xffeeeeee.toInt()
        }
        val flow = build(w, h, 3.75f, pixels = pixels)
        val samples = ParticleDriftContent.samplePositions(w, h).map { i ->
            val c = pixels[i]
            (c and -0x1000000) or ((c and 255) shl 16) or (c and 0xff00) or ((c ushr 16) and 255)
        }.toIntArray()
        val base = ParticleDriftModel.count(w, h, 1f)
        val content = ParticleDriftContent.analyseSamples(w, h, samples, base)
        assertTrue(flow.count > flow.columns * flow.rows)
        assertEquals((base + content.replicas).toFloat(), flow.count.toFloat(), (base + content.replicas) * .06f)
    }
}
