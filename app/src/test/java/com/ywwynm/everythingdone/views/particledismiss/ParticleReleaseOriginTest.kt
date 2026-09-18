package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class ParticleReleaseOriginTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "shared/particle-dismiss/rules.properties").isFile }
    private val rules by lazy {
        ParticleMicroflakeRules.read(File(root, "shared/particle-dismiss/rules.properties").inputStream(),
            File(root, "shared/particle-dismiss/common-release.f32").inputStream())
    }

    @Test fun `顶栏裁切和阴影外扩后触点仍对应原屏幕位置`() {
        // 原卡片在 (100,160)，快照左侧外扩 16，顶部被顶栏裁掉 80。
        val expected = ParticleReleaseOrigin(136f / 432, 40f / 180)
        for (scale in listOf(1, 2, 4)) {
            val actual = ParticleReleaseOrigin.fromScreen(220f * scale, 280f * scale,
                84f * scale, 240f * scale, 432 * scale, 180 * scale)
            assertEquals(expected, actual)
            assertEquals(220f * scale, 84f * scale + actual.x * 432 * scale, 1e-4f)
            assertEquals(280f * scale, 240f * scale + actual.y * 180 * scale, 1e-4f)
        }
        assertEquals(ParticleReleaseOrigin(0f, 1f),
            ParticleReleaseOrigin.fromScreen(70f, 460f, 84f, 240f, 432, 180))
    }

    @Test fun `固定一个触点不会增加起点数量或重排其它随机起点`() {
        for (seed in 0L until 32L) {
            val random = ParticleReleaseTopology.nativePatches(320f, 180f, seed)
            val anchored = ParticleReleaseTopology.nativePatches(320f, 180f, seed,
                ParticleReleaseOrigin(.25f, .75f))
            assertEquals(random.size, anchored.size)
            assertEquals(80.0, anchored[0], 0.0)
            assertEquals(135.0, anchored[1], 0.0)
            assertEquals(0.0, anchored[2], 0.0)
            assertArrayEquals(random.copyOfRange(3, random.size), anchored.copyOfRange(3, anchored.size), 0.0)
        }
    }

    @Test fun `横竖卡片左右滑动的触点邻域始终在早期释放并向外扩展`() {
        for ((width, height) in listOf(360f to 120f, 120f to 360f)) {
            val nx = (width / 3).toInt(); val ny = (height / 3).toInt()
            for (direction in listOf(0f, 180f)) for (seed in 0L until 32L) {
                for (point in listOf(ParticleReleaseOrigin(.25f, .25f), ParticleReleaseOrigin(.5f, .5f),
                    ParticleReleaseOrigin(.8f, .8f), ParticleReleaseOrigin(.98f, .02f))) {
                    val field = ParticleMicroflakeModel.releaseField(nx, ny, direction, rules, width, height,
                        ParticleMicroflakeVariation.fromSeed(seed, rules), releaseOrigin = point)
                    val near = mutableListOf<Float>(); val ring = mutableListOf<Float>()
                    for (y in 0 until ny) for (x in 0 until nx) {
                        val distance = hypot((x + .5f) / nx * width - point.x * width,
                            (y + .5f) / ny * height - point.y * height) / minOf(width, height)
                        if (distance <= .04f) near += field[y * nx + x]
                        if (distance in .12f.. .20f) ring += field[y * nx + x]
                    }
                    val context = "$width x $height direction=$direction seed=$seed point=$point"
                    assertTrue(context, near.average() < .14)
                    assertTrue(context, near.average() < ring.average())
                    assertTrue(context, field.all { it.isFinite() && it in .008f.. .78f })
                }
            }
        }
    }

    @Test fun `触点变化传到最终材料且保留粒子数量和颜色`() {
        val first = ParticleReleaseOrigin(.25f, .25f)
        val second = ParticleReleaseOrigin(.75f, .75f)
        fun build(point: ParticleReleaseOrigin) = ParticleMicroflakeModel.build(240f, 160f,
            intArrayOf(0xffedaa60.toInt()), 1, 1, 180f, 42017L, rules, point)
        val a = build(first); val b = build(second)
        assertEquals(a.count, b.count)
        assertArrayEquals(a.pigment, b.pigment, 0f)
        fun early(material: ParticleMicroflakeModel.Materials, point: ParticleReleaseOrigin): Double =
            (0 until material.count).filter { i ->
                hypot(material.values[i * 12] - point.x * 240, material.values[i * 12 + 1] - point.y * 160) < 8
            }.map { material.values[it * 12 + 2].toDouble() }.average()
        assertTrue(early(a, first) < early(b, first))
        assertTrue(early(b, second) < early(a, second))
    }
}
