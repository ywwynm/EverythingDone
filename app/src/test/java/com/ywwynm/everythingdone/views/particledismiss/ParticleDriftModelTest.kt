package com.ywwynm.everythingdone.views.particledismiss

import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 飘散样式的纯算式部分：出生时刻、粒子数、参考样式的选取与格点套用、背景亮度换算。 */
class ParticleDriftModelTest {

    private fun style(name: String, angle: Float, lattice: FloatArray = FloatArray(16) { (it * 7 % 16) / 15f },
                      width: Int = 1048, height: Int = 1061) =
        ParticleDriftStyles.Style(name, angle, 28000f, width, height, width * 1.2584f, height * 1.5858f,
            lattice, 4, 4, .5f, 0f, 1.15f)

    @Test fun `出生时刻按120Hz量化且至少一帧`() {
        // 阈值 1.05 × T，α ≤ 0.9 即 n − 1.05T ≤ 0.135
        assertEquals(1f / 120f, ParticleDriftModel.bornSeconds(.1f), 1e-6f)
        val n = .7f
        val born = ParticleDriftModel.bornSeconds(n)
        assertTrue(n - 1.05f * born <= .135f + 1e-5f)
        assertTrue(n - 1.05f * (born - 1f / 120f) > .135f - 1e-5f)
    }

    @Test fun `动画从第一处开始变淡的时刻起算`() {
        val lattice = FloatArray(12) { .5f + it / 24f }
        val model = ParticleDriftModel(1200, 680, lattice, 4, 3, 0f, -1f, 20000)
        val lowest = ParticleDriftModel.noiseRange(lattice, 4, 3, 1200, 680, model.scaleU)[0] * .8f + .2f
        // 这一刻最低处的遮罩恰好开始离开 1（n − 阈值 = 柔边 0.15），早于第一颗出生
        assertEquals(ParticleDriftModel.SOFTNESS, lowest - ParticleDriftModel.RATE_PER_SECOND * model.startSeconds, 1e-4f)
        assertTrue(model.startSeconds < ParticleDriftModel.bornSeconds(lowest))
        assertTrue(model.startSeconds < model.bodyGoneSeconds)
    }

    @Test fun `粒子数与桌面实现同一公式`() {
        // 桌面 area_particle_count：round(4 万 × 面积 / 1.3M / 1000) × 1000，截在 [1 万, 5 万]
        fun desktop(w: Int, h: Int): Int {
            val thousands = Math.rint(40000.0 * (w.toDouble() * h) / 1.3e6 / 1000.0)
            return (thousands * 1000).toInt().coerceIn(10000, 50000)
        }
        for ((w, h) in listOf(1048 to 1061, 1225 to 1240, 1225 to 760, 980 to 520, 1440 to 3088, 300 to 200)) {
            assertEquals("$w × $h", desktop(w, h), ParticleDriftModel.count(w, h, 1f))
        }
        // 1.3M 设备像素² 恰好 4 万；按千颗取整
        assertEquals(40000, ParticleDriftModel.count(1300, 1000, 1f))
        assertEquals(0, ParticleDriftModel.count(1225, 1240, 1f) % 1000)
        // 五成双：40000 × 面积 / 1.3e9 = 37.5 时取 38，38.5 时取 38
        assertEquals(38000, ParticleDriftModel.count(1218750, 1, 1f))
        assertEquals(38000, ParticleDriftModel.count(1251250, 1, 1f))
        assertEquals(ParticleDriftModel.MIN_COUNT, ParticleDriftModel.count(100, 100, 1f))
        assertEquals(ParticleDriftModel.MAX_COUNT, ParticleDriftModel.count(1440, 3088, 1.5f))
        // 倍率乘在面积上
        assertEquals(ParticleDriftModel.count(1200, 600, 1f), ParticleDriftModel.count(1200, 1200, .5f))
    }

    @Test fun `方向角之差折进正负180`() {
        assertEquals(-10f, ParticleDriftStyles.angleDifference(350f, 0f), 1e-4f)
        assertEquals(10f, ParticleDriftStyles.angleDifference(0f, 350f), 1e-4f)
        assertEquals(-180f, ParticleDriftStyles.angleDifference(270f, 90f), 1e-4f)
    }

    @Test fun `向下或水平的方向不会选到朝上的参考样式`() {
        val styles = listOf(style("ironman", 135f), style("ref5", 63f))
        val random = Random(7)
        repeat(200) {
            assertNull(ParticleDriftStyles.pick(styles, 270f, random))
            assertNull(ParticleDriftStyles.pick(styles, 180f, random))
        }
    }

    @Test fun `方向合适时参考样式按概率出现而不是每次都出现`() {
        val styles = listOf(style("kobe", 135.1f), style("thanos", 134.4f), style("ref5", 63f))
        val random = Random(11)
        var picked = 0
        val names = HashSet<String>()
        repeat(2000) {
            ParticleDriftStyles.pick(styles, 130f, random)?.let { picked++; names += it.name }
        }
        assertEquals(ParticleDriftStyles.REFERENCE_PROBABILITY * 2000, picked.toFloat(), 120f)
        assertEquals(setOf("kobe", "thanos"), names)
    }

    /** 旧钢铁侠的格点值（`drift-styles.json`）：卡片内一处起点，右边那排卡片外的格点上三处。 */
    private val ironmanLattice = floatArrayOf(
        .27312f, .80432f, .80807f, 0f,
        .75776f, .97998f, .415f, .0093f,
        .77373f, 0f, .2553f, .64142f,
        .47323f, .99335f, .22922f, .0992f)

    @Test fun `左上方向旧钢铁侠一族约占一半其中四成是原样`() {
        val styles = listOf(style("ironman", 135f, ironmanLattice), style("kobe", 135.1f), style("thanos", 134.4f))
        val random = Random(5)
        var original = 0
        var variants = 0
        var others = 0
        var none = 0
        val trials = 4000
        repeat(trials) {
            val picked = ParticleDriftStyles.pick(styles, 135f, random)
            when {
                picked == null -> none++
                picked.name == "ironman" -> original++
                picked.name.startsWith("ironman-variant") -> variants++
                else -> others++
            }
        }
        val family = ParticleDriftStyles.FAVORITE_PROBABILITY
        assertEquals(family * trials, (original + variants).toFloat(), 160f)
        assertEquals(ParticleDriftStyles.FAVORITE_ORIGINAL_SHARE, original.toFloat() / (original + variants), .04f)
        // 没选中这一族的那一半，按原来的规则在其余参考与随机之间各占一半
        assertEquals((1 - family) * ParticleDriftStyles.REFERENCE_PROBABILITY * trials, others.toFloat(), 160f)
        assertEquals((1 - family) * (1 - ParticleDriftStyles.REFERENCE_PROBABILITY) * trials, none.toFloat(), 160f)
    }

    @Test fun `正上方向不会选到旧钢铁侠`() {
        val styles = listOf(style("ironman", 135f, ironmanLattice), style("ref1", 100.6f))
        val random = Random(9)
        repeat(1000) {
            val picked = ParticleDriftStyles.pick(styles, 90f, random)
            assertTrue(picked == null || picked.name == "ref1")
        }
    }

    @Test fun `旧钢铁侠的变体保留颗粒衰减方式只改起点与风力`() {
        val base = style("ironman", 135f, ironmanLattice)
        val random = Random(17)
        val labels = HashSet<String>()
        repeat(400) {
            val variant = ParticleDriftStyles.variantOf(base, random)
            assertTrue(variant.name.startsWith("ironman-variant"))
            labels += variant.name.substringAfter("（").substringBefore("）")
            assertEquals(base.fadeAlphaPower, variant.fadeAlphaPower, 0f)
            assertEquals(base.fadeSizePower, variant.fadeSizePower, 0f)
            assertTrue(variant.wind in base.wind * .85f..base.wind * 1.15f)
            assertTrue(variant.lattice.all { it in 0f..1f })
            // 至少留一处起点（含卡片外那一排）
            assertTrue(variant.lattice.any { it < ParticleDriftStyles.START_POINT_VALUE })
        }
        assertEquals(setOf("起点-1", "起点不变", "起点+1", "起点+2"), labels)
    }

    @Test fun `去掉卡片内那一处起点之后只从右缘开始`() {
        val base = style("ironman", 135f, ironmanLattice)
        val random = Random(23)
        var checked = 0
        repeat(400) {
            val variant = ParticleDriftStyles.variantOf(base, random)
            if (variant.name.endsWith("（起点-1）")) {
                // 卡片内唯一的起点在第 3 行第 2 列（下标 9）
                assertTrue(variant.lattice[9] >= .3f)
                assertTrue(variant.lattice[3] < ParticleDriftStyles.START_POINT_VALUE)
                checked++
            }
        }
        assertTrue(checked > 20)
    }

    @Test fun `尺寸与参考相同时格点值原样套用`() {
        val lattice = floatArrayOf(.27f, .80f, .81f, 0f, .76f, .98f, .42f, .01f, .77f, 0f, .26f, .64f, .47f, .99f, .23f, .10f)
        val reference = style("ironman", 135f, lattice)
        val out = ParticleDriftStyles.resample(reference, reference.width, reference.height)
        assertEquals(16, out.size)
        // 归一化位置取样在整数格点上就是格点值本身；可见范围对齐是恒等变换
        for (k in out.indices) assertEquals("格点 $k", lattice[k], out[k], 2e-3f)
    }

    @Test fun `套到别的尺寸时可见范围与参考对齐`() {
        val reference = style("ironman", 135f)
        val width = 1200
        val height = 680
        val out = ParticleDriftStyles.resample(reference, width, height)
        val size = ParticleDriftModel.latticeSize(width, height)
        val own = ParticleDriftModel.noiseRange(out, size[0], size[1], width, height,
            ParticleDriftModel.latticeScale(width, height))
        val expected = ParticleDriftModel.noiseRange(reference.lattice, 4, 4, reference.width, reference.height,
            ParticleDriftModel.latticeScale(reference.width, reference.height))
        assertEquals(expected[0], own[0], 2e-3f)
        assertEquals(expected[1], own[1], 2e-3f)
    }

    @Test fun `选用参考样式时风力取参考的而风向仍是本次方向`() {
        val styles = listOf(style("ironman", 145f))
        var model: ParticleDriftModel? = null
        for (seed in 0 until 64) {
            val candidate = ParticleDriftModel.random(1200, 680, -1f, -1f, 1f,
                ParticleDriftModel.seededRandom(seed), styles)
            if (candidate.styleName != null) { model = candidate; break }
        }
        assertNotNull(model)
        val wind = checkNotNull(model)
        // y 向下：左上是 (−, −)。参考的风向是 145°，本次方向 135°，风向取本次的
        val angle = Math.toDegrees(atan2(-wind.windY.toDouble(), wind.windX.toDouble()))
        assertEquals(135.0, angle, 1e-3)
        assertEquals(28000.0, hypot(wind.windX.toDouble(), wind.windY.toDouble()), 1e-2)
        assertEquals(0f, wind.fadeAlphaPower, 0f)
        // 参考样式保持源码的湍流场
        assertEquals(0f, wind.turbulenceOffsetX, 0f)
        assertEquals(0f, wind.turbulenceOffsetY, 0f)
    }

    @Test fun `相邻的小种子打散之后第一个数不再挤在一起`() {
        val firsts = (0 until 64).map { ParticleDriftModel.seededRandom(it).nextFloat() }
        assertTrue(firsts.count { it < .5f } in 16..48)
    }

    @Test fun `随机生成时风沿本次方向`() {
        val model = ParticleDriftModel.random(1200, 680, 0f, 5f, 1f, Random(3))
        assertNull(model.styleName)
        // 随机生成时湍流场每次不同
        assertTrue(model.turbulenceOffsetX in 0f..ParticleDriftModel.TURBULENCE_OFFSET_RANGE)
        assertTrue(model.turbulenceOffsetX != 0f || model.turbulenceOffsetY != 0f)
        assertTrue(abs(model.windX) < 1e-3f)
        assertTrue(model.windY > 0f)
    }

    @Test fun `取样里的暗层按编码值还原`() {
        // #EEEEEE 压上 0.6 的黑色暗层（编码值上混合）读回来是 0x5F 左右；还原后回到 #EEEEEE 的亮度
        val dimmed = (0xEE * .4).toInt()
        val sample = (0xFF shl 24) or (dimmed shl 16) or (dimmed shl 8) or dimmed
        val restored = ParticleDriftBackground.lightness(
            ParticleDriftBackground.relativeLuminance(sample, ParticleDriftBackground.undimGain(.6f)))
        assertEquals(94f, restored, 1.5f)
        // 不还原时会被当成暗背景
        assertTrue(ParticleDriftBackground.lightness(ParticleDriftBackground.relativeLuminance(sample)) <
            ParticleDriftBackground.DARK_LIGHTNESS)
    }

    @Test fun `参考样式的湍流坐标系与桌面复刻的发射区相同`() {
        // 旧钢铁侠：快照 1048 × 1061，扩展 1318.76 × 1682.56（harmony_native.py 的 position / emit_size）
        val frame = ParticleDriftModel.emitFrame(1048, 1061, 1318.76f, 1682.56f)
        assertEquals(67.5f, frame[0], 1e-3f)
        assertEquals(155.25f, frame[1], 1e-3f)
        assertEquals(524f, frame[2], 1e-3f)
        assertEquals(530.5f, frame[3], 1e-3f)
        val styles = listOf(style("ironman", 135f, ironmanLattice))
        val model = ParticleDriftModel.random(1200, 680, -1f, -1f, 1f, Random(1), styles)
        if (model.styleName != null) assertNotNull(model.turbulenceFrame)
        assertNull(ParticleDriftModel.random(1200, 680, 0f, 1f, 1f, Random(1)).turbulenceFrame)
    }

    private fun image(width: Int, height: Int, color: (Int, Int) -> Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val c = color(x, y)
            buffer.put((c shr 16 and 255).toByte()).put((c shr 8 and 255).toByte()).put((c and 255).toByte())
                .put((c ushr 24).toByte())
        }
        buffer.rewind()
        return buffer
    }

    @Test fun `白底弹窗的红色标题多出颗粒且只落在标题上`() {
        val width = 600
        val height = 400
        // 白底，顶部 40 像素高的一条红色标题（占面积 10%）
        val pixels = image(width, height) { _, y -> if (y < 40) 0xFFE53935.toInt() else 0xFFFFFFFF.toInt() }
        val analysis = ParticleDriftContent.analyse(width, height, pixels, 20000)
        assertEquals(1f, analysis.panelWeight, 1e-3f)
        assertEquals(1f, analysis.panel[0], 2e-2f)
        // 每个红色格子最多多出 2 倍：约 20000 × 0.1 × 2
        assertEquals(4000f, analysis.replicas.toFloat(), 600f)
        // 分布表里非零增量只出现在标题所在的格子
        val cells = analysis.cumulative
        for (k in cells.indices) {
            val step = cells[k] - (if (k == 0) 0f else cells[k - 1])
            val row = k / analysis.gridWidth
            val top = row.toFloat() / analysis.gridHeight * height
            if (step > 1e-6f) assertTrue("第 $row 行不该有副本", top < 40f)
        }
        assertEquals(1f, cells.last(), 0f)
    }

    @Test fun `没有明确本体色的照片不做强调`() {
        val random = Random(3)
        val pixels = image(400, 400) { _, _ -> (0xFF shl 24) or random.nextInt(0xFFFFFF) }
        val analysis = ParticleDriftContent.analyse(400, 400, pixels, 20000)
        assertEquals(0f, analysis.panelWeight, 0f)
        assertEquals(0, analysis.replicas)
    }

    @Test fun `灰色正文在白底上也算内容而白色本体不算`() {
        val panel = floatArrayOf(1f, 1f, 1f)
        assertEquals(0f, ParticleDriftContent.weight(1f, 1f, 1f, panel, 1f), 1e-4f)
        assertEquals(1f, ParticleDriftContent.weight(.46f, .46f, .46f, panel, 1f), 1e-4f)
        assertEquals(1f, ParticleDriftContent.weight(.9f, .22f, .21f, panel, 1f), 1e-4f)
    }

    @Test fun `背景亮度换算到L星`() {
        assertEquals(94f, ParticleDriftBackground.lightness(ParticleDriftBackground.relativeLuminance(0xFFEEEEEE.toInt())), 1f)
        assertEquals(6f, ParticleDriftBackground.lightness(ParticleDriftBackground.relativeLuminance(0xFF121212.toInt())), 1f)
        assertEquals(50f, ParticleDriftBackground.lightness(ParticleDriftBackground.relativeLuminance(0xFF777777.toInt())), 1f)
    }
}
