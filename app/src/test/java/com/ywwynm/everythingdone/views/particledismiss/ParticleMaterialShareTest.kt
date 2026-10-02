package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 碰一碰接收两层之间沿用材料（[ParticleMaterialShare]）：输入逐项相同才沿用；沿用到的值数组是复制的一份，光尘接手
 * 改它不会动到发布方；有一项不同就不沿用，并指出是哪一项。
 */
class ParticleMaterialShareTest {

    private val root = generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .first { File(it, "shared/particle-dismiss/rules.properties").isFile }
    private val rules = ParticleMicroflakeRules.read(
        File(root, "shared/particle-dismiss/rules.properties").inputStream(),
        File(root, "shared/particle-dismiss/common-release.f32").inputStream())

    private val width = 40
    private val height = 24

    /** 两张代表色卡片，中间一道透明缝：与接收时的占位快照、代理卡片同一类图。 */
    private fun cardPixels(): IntArray = IntArray(width * height) { i ->
        val x = i % width
        when {
            x in width / 2 - 2 until width / 2 + 2 -> 0
            x < width / 2 -> 0xFF4A7BD0.toInt()
            else -> 0xFFE0A040.toInt()
        }
    }

    private fun build(pixels: IntArray): ParticleMicroflakeModel.Materials =
        ParticleMicroflakeModel.build(120f, 72f, pixels, width, height, 75f, 129L, rules,
            ParticleReleaseOrigin(.5f, 0f), 0f)

    private fun key(
        pixels: IntArray, pixelWidth: Int = width, seed: Int = 129, originX: Float = 48f,
        releaseOrigin: ParticleReleaseOrigin? = ParticleReleaseOrigin(.5f, 0f),
        sink: FloatArray? = ParticleSink(540f, 60f).signature(), cellDp: Float = 0f, native: Boolean = false
    ) = ParticleMaterialShare.Key(pixels, pixelWidth, height, 3f, originX, 400f, 540f, 60f, seed, 1f,
        releaseOrigin, sink, cellDp, native)

    @Test fun `输入逐项相同时沿用，值数组是复制的一份`() {
        val pixels = cardPixels()
        val materials = build(pixels)
        val visible = BooleanArray(materials.count) { it % 3 != 0 }
        val share = ParticleMaterialShare()
        share.publish(key(pixels), materials, visible, .5)

        val adoption = ParticleMaterialShare.Adoption(share)
        // 沿用方的像素是另一份数组，只要内容相同
        val taken = adoption.take(key(pixels.copyOf()))
        assertNotNull("输入逐项相同时应沿用", taken)
        taken!!
        // 与同样输入重新建一遍的结果逐位相同（建材是确定的）
        assertArrayEquals(build(pixels.copyOf()).values, taken.materials.values, 0f)
        assertNotSame("值数组必须复制：光尘接手要改写它", materials.values, taken.materials.values)
        assertSame("只读的几项直接共用", materials.pigment, taken.materials.pigment)
        assertSame(visible, taken.visible)

        val before = materials.values[6]
        taken.materials.values[6] = before + 1f
        assertEquals("改沿用到的一份不能动到发布方", before, materials.values[6], 0f)

        adoption.finish(true, .01, .02)
        val result = checkNotNull(adoption.result)
        assertTrue(result.adopted)
        assertEquals("输入逐项相同", result.reason)
        assertEquals(.5, result.publishedSeconds, 0.0)
        assertEquals(.01, result.materialSeconds, 0.0)
        assertEquals(.02, result.handoffSeconds, 0.0)
    }

    @Test fun `还没建好、已经清掉时都不沿用`() {
        val pixels = cardPixels()
        val share = ParticleMaterialShare()
        val early = ParticleMaterialShare.Adoption(share)
        assertNull(early.take(key(pixels)))
        early.finish(false, .3, 0.0)
        assertTrue(checkNotNull(early.result).reason.contains("还没建好"))

        share.publish(key(pixels), build(pixels), null, .4)
        share.clear()
        val late = ParticleMaterialShare.Adoption(share)
        assertNull(late.take(key(pixels)))
        late.finish(false, .3, 0.0)
        assertTrue(checkNotNull(late.result).reason.contains("清掉"))

        // 清掉之后发布方的建材才走完：不再收
        share.publish(key(pixels), build(pixels), null, .4)
        assertNull(ParticleMaterialShare.Adoption(share).take(key(pixels)))
    }

    @Test fun `任何一项不同都不沿用并指出是哪一项`() {
        val pixels = cardPixels()
        val share = ParticleMaterialShare()
        share.publish(key(pixels), build(pixels), null, .4)

        fun reason(other: ParticleMaterialShare.Key): String {
            val adoption = ParticleMaterialShare.Adoption(share)
            assertNull(adoption.take(other))
            adoption.finish(false, 0.0, 0.0)
            return checkNotNull(adoption.result).also { assertFalse(it.adopted) }.reason
        }

        assertTrue(reason(key(pixels, pixelWidth = width + 1)).contains("快照尺寸"))
        assertTrue(reason(key(pixels, seed = 130)).contains("种子"))
        assertTrue(reason(key(pixels, originX = 49f)).contains("快照原点"))
        assertTrue(reason(key(pixels, releaseOrigin = null)).contains("剥离起点"))
        assertTrue(reason(key(pixels, cellDp = 1.08f)).contains("微片尺寸"))
        assertTrue(reason(key(pixels, native = true)).contains("建材实现"))
        assertTrue(reason(key(pixels, sink = null)).contains("有没有终点"))
        assertTrue(reason(key(pixels, sink = ParticleSink(541f, 60f).signature())).contains("终点位置"))
        val slower = ParticleSink(540f, 60f, tuning = ParticleSink.Tuning(releaseScale = .7f)).signature()
        assertTrue(reason(key(pixels, sink = slower)).contains("剥离与飞行参数"))

        // 只差一个像素（例如预计的卡片边缘差了一像素）：个数与第一处都要报出来
        val shifted = pixels.copyOf().also { it[5 * width + 7] = 0xFF000000.toInt() }
        val pixelReason = reason(key(shifted))
        assertTrue(pixelReason, pixelReason.contains("1 个不同"))
        assertTrue(pixelReason, pixelReason.contains("(7, 5)"))
    }

    @Test fun `终点的逐位比较覆盖柔性终点的每一项，没有 NaN`() {
        fun field(blendDp: Float) = ParticleSink.Field(
            arrivalFirst = .5f, arrivalLast = .9f, glideStart = .1f, glideEnd = .6f, glideGain = 1f, spiral = 0f,
            absorbShare = .4f, spreadDp = 24f, drift = .1f, freeLife = 1.2f, absorbInnerDp = 0f, absorbOuterDp = 36f,
            fadeS = .15f, nearScale = .6f, bandDp = 18f, bandSpiral = .2f, curl = .3f, vortexDp = 12f, groupBorn = .1f,
            groupArrive = .05f, groupSwirl = .2f, handoffGlide = .14f, handoffCurl = 1f, handoffScale = 1.37f,
            blendDp = blendDp)
        val base = ParticleSink(540f, 60f, field = field(48f)).signature()
        assertTrue(base.none { it.isNaN() })
        assertEquals("有柔性终点、没有卡片时是 45 项", 45, base.size)
        assertArrayEquals(base, ParticleSink(540f, 60f, field = field(48f)).signature(), 0f)

        val pixels = cardPixels()
        val share = ParticleMaterialShare()
        share.publish(key(pixels, sink = base), build(pixels), null, .4)
        val adoption = ParticleMaterialShare.Adoption(share)
        assertNull(adoption.take(key(pixels, sink = ParticleSink(540f, 60f, field = field(47f)).signature())))
        adoption.finish(false, 0.0, 0.0)
        assertTrue(checkNotNull(adoption.result).reason.contains("柔性终点参数"))

        // 卡片矩形从第 45 位起，每张 4 位
        assertEquals("卡片张数", ParticleSink.signatureLabel(44))
        assertEquals("第 1 张卡片的矩形", ParticleSink.signatureLabel(45))
        assertEquals("第 2 张卡片的矩形", ParticleSink.signatureLabel(49))
    }
}
