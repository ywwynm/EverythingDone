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

    @Test fun `噪声值域只算各单元角上的样点，与逐点扫描相同`() {
        fun scan(lattice: FloatArray, w: Int, h: Int, width: Int, height: Int, u: Float): FloatArray {
            var low = Float.MAX_VALUE
            var high = -Float.MAX_VALUE
            for (y in 0 until height step 8) for (x in 0 until width step 8) {
                val value = ParticleDriftModel.noise(lattice, w, h, (x + .5f) * u, (y + .5f) * u)
                low = minOf(low, value)
                high = maxOf(high, value)
            }
            return floatArrayOf(low, high)
        }
        val random = Random(20261006)
        val sizes = listOf(1440 to 3088, 1300 to 900, 1300 to 300, 2520 to 1680, 360 to 240, 1440 to 220) +
            List(60) { (120 + random.nextInt(2480)) to (120 + random.nextInt(2980)) }
        for ((width, height) in sizes) {
            val size = ParticleDriftModel.latticeSize(width, height)
            val u = ParticleDriftModel.latticeScale(width, height)
            repeat(5) {
                val lattice = FloatArray(size[0] * size[1]) { random.nextFloat() * 2f - .5f }
                val expected = scan(lattice, size[0], size[1], width, height, u)
                val actual = ParticleDriftModel.noiseRange(lattice, size[0], size[1], width, height, u)
                assertEquals("$width × $height 最小", expected[0], actual[0], 0f)
                assertEquals("$width × $height 最大", expected[1], actual[1], 0f)
            }
        }
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
        assertEquals(ParticleDriftModel.MAX_COUNT, ParticleDriftModel.count(1440, 3088, 1f))
    }

    @Test fun `粒子数量倍率乘在截好的粒子数上`() {
        // 小卡片截到 1 万、整屏截到 5 万之后再乘：0.2 与 1.6 在两头都起作用
        assertEquals(2000, ParticleDriftModel.count(100, 100, .2f))
        assertEquals(80000, ParticleDriftModel.count(1440, 3088, 1.6f))
        for ((w, h) in listOf(1225 to 760, 917 to 915, 600 to 420)) {
            val base = ParticleDriftModel.count(w, h, 1f)
            for (amount in ParticleTuning.AMOUNT_LEVELS) {
                assertEquals("$w × $h × $amount", Math.round(base * amount.toDouble()).toInt(),
                    ParticleDriftModel.count(w, h, amount))
            }
        }
    }

    @Test fun `粒子数量与粒子大小的档位`() {
        val amount = ParticleTuning.AMOUNT_LEVELS
        assertEquals(.1f, amount.first(), 1e-6f)
        assertEquals(1.6f, amount.last(), 1e-6f)
        assertEquals(1f, amount[ParticleTuning.DEFAULT_AMOUNT_INDEX], 0f)
        val size = ParticleTuning.DRIFT_SIZE_LEVELS
        assertEquals(31, size.size)
        assertEquals(.5f, size.first(), 1e-6f)
        assertEquals(6f, size.last(), 1e-6f)
        assertTrue(size.contentEquals(ParticleTuning.FLOW_SIZE_LEVELS))
        assertEquals(1f, size[ParticleTuning.DEFAULT_SIZE_INDEX], 0f)
        for (k in 1 until amount.size) {
            assertEquals(.1f, amount[k] - amount[k - 1], 1e-5f)
        }
    }

    @Test fun `触点越远风越强且只在有真实触点时偏向终点`() {
        fun wind(strength: Float?, gap: Float?): Pair<Float, Float?> {
            val model = ParticleDriftModel.random(1200, 680, 1f, -1f, 1f, Random(3), touchGap = gap, touchStrength = strength)
            return hypot(model.windX, model.windY) to model.touchGap
        }
        val (plain, noGap) = wind(null, null)
        assertNull(noGap)
        // 没有真实触点（返回键）：与此前完全相同
        val before = ParticleDriftModel.random(1200, 680, 1f, -1f, 1f, Random(3))
        assertEquals(hypot(before.windX, before.windY), plain, 0f)
        assertEquals(plain, wind(.5f, null).first, plain * 1e-6f)
        assertEquals(plain * .55f, wind(0f, .2f).first, plain * 1e-5f)
        assertEquals(plain * 1.45f, wind(1f, 2f).first, plain * 1e-5f)
        assertEquals(.2f, wind(0f, .2f).second!!, 0f)
    }

    @Test fun `粒子大小倍率进入模型`() {
        val styles = listOf(style("ironman", 135f, ironmanLattice), style("ref1", 100.6f))
        for (seed in 0 until 40) {
            val model = ParticleDriftModel.random(1200, 680, -1f, -1f, 1f, ParticleDriftModel.seededRandom(seed), styles,
                size = 1.6f)
            assertEquals(1.6f, model.sizeScale, 0f)
        }
        assertEquals(1f, ParticleDriftModel.random(1200, 680, 0f, -1f, 1f, Random(3)).sizeScale, 0f)
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
        assertEquals(setOf("kobe"), names)
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
            assertTrue(picked == null || picked.name.startsWith("ref1"))
        }
    }

    /** 十二段参考的风向（`drift-styles.json`）；朝上一组是 63°～118° 那六段。 */
    private fun allStyles() = listOf(
        style("reference-0926-5", 63.1f), style("reference-0926-4", 73.8f), style("reference-0926-7", 84.7f),
        style("reference-0926-1", 100.6f), style("ironman-up-reference", 103.8f), style("reference-0926-2", 118.3f),
        style("thanos", 134.4f), style("ironman", 135f, ironmanLattice), style("kobe", 135.1f),
        style("reference-0926-6", 135.4f), style("reference-0926-3", 138.7f), style("spiderman-original", 141.4f))

    @Test fun `朝上一组是风向离正上方三十五度以内的六段`() {
        val up = allStyles().filter { ParticleDriftStyles.isUpFamily(it) }.map { it.name }
        assertEquals(listOf("reference-0926-5", "reference-0926-4", "reference-0926-7", "reference-0926-1",
            "ironman-up-reference", "reference-0926-2"), up)
    }

    /** 按名字（去掉变体后缀）统计一种方向下的选取结果。 */
    private fun tally(angle: Float, seed: Long, trials: Int): Map<String, IntArray> {
        val random = Random(seed)
        val counts = HashMap<String, IntArray>()
        repeat(trials) {
            val picked = ParticleDriftStyles.pick(allStyles(), angle, random)
            val name = picked?.name?.substringBefore(ParticleDriftStyles.VARIANT_SUFFIX) ?: "随机"
            val slot = counts.getOrPut(name) { IntArray(2) }
            if (picked != null && picked.name.contains(ParticleDriftStyles.VARIANT_SUFFIX)) slot[1]++ else slot[0]++
        }
        return counts
    }

    @Test fun `正上方向朝上一组约占四分之三其中四成是原样`() {
        val trials = 8000
        val counts = tally(90f, 31, trials)
        // 90° 对得上的是参考 4、7、1 与向上钢铁侠
        val family = listOf("reference-0926-4", "reference-0926-7", "reference-0926-1", "ironman-up-reference")
        assertEquals(family.toSet() + "随机", counts.keys)
        val original = family.sumOf { counts.getValue(it)[0] }
        val variants = family.sumOf { counts.getValue(it)[1] }
        assertEquals(ParticleDriftStyles.UP_FAMILY_PROBABILITY * trials, (original + variants).toFloat(), 240f)
        assertEquals(ParticleDriftStyles.FAVORITE_ORIGINAL_SHARE, original.toFloat() / (original + variants), .03f)
        // 组内等概率
        for (name in family) assertEquals(.25f, counts.getValue(name).sum().toFloat() / (original + variants), .03f)
        assertEquals((1 - ParticleDriftStyles.UP_FAMILY_PROBABILITY) * trials, counts.getValue("随机")[0].toFloat(), 240f)
    }

    @Test fun `上方偏左时离哪一组近就偏向哪一组`() {
        val trials = 8000
        // 120°：参考 2（118.3°）比旧钢铁侠（135°）近，偏向朝上一组；旧钢铁侠只作为普通候选、不出变体
        val near = tally(120f, 37, trials)
        val family = listOf("reference-0926-1", "ironman-up-reference", "reference-0926-2")
        val inFamily = family.sumOf { near[it]?.sum() ?: 0 }
        assertEquals(ParticleDriftStyles.UP_FAMILY_PROBABILITY * trials, inFamily.toFloat(), 240f)
        assertEquals(0, near["ironman"]?.get(1) ?: 0)
        // 135°（返回键）：旧钢铁侠最近，与此前一样是旧钢铁侠一族一半
        val back = tally(135f, 41, trials)
        val ironman = back.getValue("ironman").sum()
        assertEquals(ParticleDriftStyles.FAVORITE_PROBABILITY * trials, ironman.toFloat(), 240f)
        assertTrue(back.keys.none { it in family.take(2) })
    }

    @Test fun `只有旧钢铁侠一族时选取结果与此前逐位相同`() {
        // 此前的写法：先抽旧钢铁侠一族，再在其余候选与随机之间抽
        fun previous(styles: List<ParticleDriftStyles.Style>, angle: Float, random: Random): String? {
            val favorite = styles.firstOrNull { it.name == "ironman" }
            val matches = { s: ParticleDriftStyles.Style ->
                abs(ParticleDriftStyles.angleDifference(s.angle, angle)) <= ParticleDriftStyles.MATCH_DEGREES }
            if (favorite != null && matches(favorite) && random.nextFloat() < ParticleDriftStyles.FAVORITE_PROBABILITY) {
                return if (random.nextFloat() < ParticleDriftStyles.FAVORITE_ORIGINAL_SHARE) favorite.name
                else ParticleDriftStyles.variantOf(favorite, random).name
            }
            val candidates = styles.filter { it !== favorite && matches(it) }
            if (candidates.isEmpty() || random.nextFloat() >= ParticleDriftStyles.REFERENCE_PROBABILITY) return null
            return candidates[random.nextInt(candidates.size)].name
        }
        val styles = allStyles().filter { !ParticleDriftStyles.isUpFamily(it) && it.name != "thanos" }
        for (angle in listOf(135f, 140f, 150f, 125f, 160f, 90f)) {
            val a = Random(77)
            val b = Random(77)
            repeat(500) {
                assertEquals(previous(styles, angle, a), ParticleDriftStyles.pick(styles, angle, b)?.name)
            }
        }
    }

    @Test fun `任意一段参考都能生成变体并可在调试包里钉住`() {
        val base = style("reference-0926-1", 100.6f)
        val variant = ParticleDriftStyles.variantOf(base, Random(5))
        assertTrue(variant.name.startsWith("reference-0926-1-variant（"))
        ParticleDriftStyles.forcedForTests = "reference-0926-1-variant"
        try {
            val picked = ParticleDriftStyles.pick(allStyles(), 270f, Random(1))
            assertNotNull(picked)
            assertTrue(picked!!.name.startsWith("reference-0926-1-variant"))
        } finally {
            ParticleDriftStyles.forcedForTests = null
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
