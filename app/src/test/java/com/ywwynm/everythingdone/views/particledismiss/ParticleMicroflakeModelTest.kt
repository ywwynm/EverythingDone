package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ParticleMicroflakeModelTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "shared/particle-dismiss/rules.properties").isFile }
    private val rules get() = ParticleMicroflakeRules.read(File(root, "shared/particle-dismiss/rules.properties").inputStream(), File(root, "shared/particle-dismiss/common-release.f32").inputStream())

    @Test fun `触点距离从边缘起算并且不受整体像素缩放影响`() {
        for ((width, height) in listOf(480f to 480f, 300f to 900f, 960f to 240f)) {
            for (degrees in listOf(0.0, 45.0, 90.0, 135.0, 247.0)) {
                val angle = Math.toRadians(degrees)
                val ux = kotlin.math.cos(angle); val uy = -kotlin.math.sin(angle)
                val edge = minOf(width / (2 * maxOf(kotlin.math.abs(ux), 1e-9)), height / (2 * maxOf(kotlin.math.abs(uy), 1e-9)))
                for (gap in listOf(0f, .15f, .65f, 1.5f)) {
                    val reach = edge + gap * minOf(width, height)
                    val x = (width * .5 + ux * reach).toFloat(); val y = (height * .5 + uy * reach).toFloat()
                    assertEquals(gap, ParticleFlowGeometry.touchGap(x, y, width, height), 1e-6f)
                    assertEquals(gap, ParticleFlowGeometry.touchGap(x * 2, y * 2, width * 2, height * 2), 1e-6f)
                }
            }
            assertEquals(0f, ParticleFlowGeometry.touchGap(width / 2, height / 2, width, height), 0f)
        }
    }

    @Test fun `长轴增长不会无限拉长局部涡旋且横竖交换对称`() {
        for (length in listOf(120f, 144f, 240f, 480f, 1200f)) {
            val tall = ParticleFlowGeometry.from(120f, length)
            val wide = ParticleFlowGeometry.from(length, 120f)
            assertTrue(tall.height <= 162.00001)
            assertEquals(tall.width, wide.height, 1e-9)
            assertEquals(tall.height, wide.width, 1e-9)
            assertEquals(tall.blend, wide.blend, 0.0)
        }
    }

    @Test fun `并发准备不同关闭实例不会混入彼此的材料`() {
        val currentRules = rules
        val pixels = intArrayOf(-1, 0xff00ddaa.toInt(), 0xffe86520.toInt(), 0x00ffffff)
        fun build(seed: Long) = ParticleMicroflakeModel.build(120f, 170f, pixels, 2, 2,
            (seed * 37 % 360).toFloat(), seed, currentRules)
        val seeds = listOf(9L, 17L, 38L)
        val expected = seeds.map(::build)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            val results = seeds.map { seed -> executor.submit<ParticleMicroflakeModel.Materials> { build(seed) } }
            for (i in results.indices) {
                val actual = results[i].get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertArrayEquals(expected[i].values, actual.values, 0f)
                assertArrayEquals(expected[i].pigment, actual.pigment, 0f)
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun `屏幕背景窄和宽时近中远均可区分且缩放不改变输入`() {
        for (space in listOf(36f, 360f)) for (scale in listOf(.5f, 1f, 2f)) {
            val values = listOf(.12f, .52f, .94f).map { fraction ->
                ParticleFlowGeometry.touchStrength(100f * scale, -space * fraction * scale,
                    200f * scale, 300f * scale, -space * scale, -space * scale,
                    (200f + space) * scale, (300f + space) * scale)
            }
            assertArrayEquals(floatArrayOf(.12f, .52f, .94f), values.toFloatArray(), 2e-6f)
            assertTrue(values[0] < values[1] && values[1] < values[2])
        }
        // 左上射线穿过控件角落，两轴须取同一个实际可触摸范围。
        assertEquals(.5f, ParticleFlowGeometry.touchStrength(-50f, -50f, 200f, 200f,
            -100f, -100f, 500f, 600f), 1e-6f)
    }

    @Test fun `不同种子改变起始区域同种子仍可复现`() {
        val currentRules = rules
        val centers = (0L until 32L).map { seed ->
            val variation = ParticleMicroflakeVariation.fromSeed(seed, currentRules)
            val field = ParticleMicroflakeModel.releaseField(64, 64, 135f, currentRules, variation = variation)
            assertArrayEquals(field, ParticleMicroflakeModel.releaseField(64, 64, 135f, currentRules, variation = variation), 0f)
            val first = field.indices.sortedBy { field[it] }.take(field.size / 20)
            assertTrue(field.all { it >= .008f && it <= .78f })
            first.map { (it % 64 + .5) / 64 }.average() to first.map { (it / 64 + .5) / 64 }.average()
        }
        assertTrue(centers.maxOf { it.first } - centers.minOf { it.first } > .25)
        assertTrue(centers.maxOf { it.second } - centers.minOf { it.second } > .25)
    }

    @Test fun `随机时钟单调且释放时间可逆`() {
        val currentRules = rules
        for (seed in 0L until 64L) {
            val variation = ParticleMicroflakeVariation.fromSeed(seed, currentRules)
            assertEquals(0.0, variation.time(0.0), 0.0)
            assertEquals(1.0, variation.time(1.0), 0.0)
            // 包含表格采样点之间的位置，验证快速求逆仍满足原时序精度。
            for (step in 0..4097) {
                val t = step / 4097.0
                assertTrue(variation.rate(t) > .6)
                assertEquals(t, variation.time(variation.inverseTime(t)), 1e-8)
            }
        }
    }

    @Test fun `两端从同一原图独立建材的随机值时序和法线一致`() {
        val pixels = intArrayOf(-1, 0xffe83030.toInt(), 0x00ffffff, 0xff2c387e.toInt(), 0xff00dedd.toInt(), 0xffd8d8d8.toInt())
        val cases = listOf(doubleArrayOf(120.0,160.0,137.0,909602.0), doubleArrayOf(47.0,211.0,0.0,99.0), doubleArrayOf(231.0,39.0,315.0,4294967305.0))
        for ((case, input) in cases.withIndex()) {
            fun floats(name: String): FloatArray {
                val bytes = javaClass.getResourceAsStream("/particle-dismiss/$name-$case.f32")!!.readBytes()
                return FloatArray(bytes.size/4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            }
            val expected = floats("materials"); val pigment = floats("pigment")
            val actual = ParticleMicroflakeModel.build(input[0].toFloat(), input[1].toFloat(), pixels, 3, 2, input[2].toFloat(), input[3].toLong(), rules)
            assertEquals(expected.size, actual.values.size)
            val offsets = (expected.indices step 12).associateBy { expected[it + 3].toInt() }
            for (i in 0 until actual.count) {
                val offset = i*12; val original = actual.values[offset+3].toInt()
                val expectedOffset = offsets.getValue(original)
                for (k in 0..11) {
                    val tolerance = if (k>=8 || k==3) 0f else if (k in 4..5) 3e-5f else 2e-5f
                    assertEquals("case=$case id=$original field=$k",expected[expectedOffset+k],actual.values[offset+k],tolerance)
                }
                assertEquals(pigment[expectedOffset/12], actual.pigment[i], 1e-6f)
            }
        }
    }
    @Test fun `八方向释放时刻与桌面冻结输入一致`() {
        for (direction in listOf(0, 45, 90, 125, 180, 225, 270, 315)) {
            val bytes = javaClass.getResourceAsStream("/particle-dismiss/release-$direction.f32")!!.readBytes()
            val expected = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(expected)
            assertArrayEquals("方向 $direction", expected, ParticleMicroflakeModel.releaseField(37, 29, direction.toFloat(), rules), 2e-6f)
        }
    }

    @Test fun `材料可重复且密铺原片完整副片有界并在一秒内退场`() {
        val pixels = intArrayOf(-1, -1, 0xffff6400.toInt(), 0x00ffffff)
        fun build() = ParticleMicroflakeModel.build(120f, 160f, pixels, 2, 2, 137f, 909602L, rules)
        val a = build(); val b = build()
        assertArrayEquals(a.values, b.values, 0f)
        val ids = HashSet<Int>()
        for (i in 0 until a.count) {
            val p = i * 12
            assertTrue(ids.add(a.values[p + 3].toInt()))
            assertTrue(a.values[p] > 0f && a.values[p] < 120f)
            assertTrue(a.values[p + 1] > 0f && a.values[p + 1] < 160f)
            assertTrue(a.values[p + 2] > 0f)
            assertTrue(a.values[p + 6] >= .11f)
            assertTrue(a.values[p + 2] + a.values[p + 6] < 1f)
            assertTrue(a.values.sliceArray(p until p + 12).all { it.isFinite() })
        }
        val gridCount = a.columns * a.rows
        assertTrue((0 until gridCount).all { it in ids })
        assertTrue(ids.all { it < gridCount * 3 })
        assertTrue(a.count <= rules.number("max_cells").toInt())
    }

    @Test fun `透明像素不改变白底识别且整片强调色不会被误认成内容`() {
        val white = ParticleMicroflakeModel.build(24f, 30f, intArrayOf(-1, 0), 2, 1, 90f, 9L, rules)
        val color = ParticleMicroflakeModel.build(24f, 30f, intArrayOf(0xffff0000.toInt()), 1, 1, 90f, 9L, rules)
        assertEquals(1f, white.bodyWeight, 0f)
        assertEquals(0f, color.bodyWeight, 0f)
        assertTrue(white.pigment.all { it == 0f })
        assertTrue(color.pigment.all { it == 0f })
        assertEquals(color.columns * color.rows, color.count)
    }

    @Test fun `白底的灰字和彩色内容得到副片但面板本身不复制`() {
        val pixels = IntArray(100) { if (it == 45) 0xff656565.toInt() else if (it == 46) 0xff148dcc.toInt() else -1 }
        val result = ParticleMicroflakeModel.build(100f, 100f, pixels, 10, 10, 135f, 909L, rules)
        val gridCount = result.columns * result.rows
        val primaries = (0 until result.count).filter { result.values[it*12+3] < gridCount }
            .associateBy { result.values[it*12+3].toInt() }
        val replicas = (0 until result.count).filter { result.values[it*12+3] >= gridCount }
        assertTrue(replicas.isNotEmpty())
        for (index in replicas) {
            val parent = primaries.getValue(result.values[index*12+3].toInt() % gridCount)
            assertEquals(1f, result.pigment[parent], 0f)
            assertEquals(result.values[parent*12+2], result.values[index*12+2], 0f)
            assertEquals(result.values[parent*12+7], result.values[index*12+7], 0f)
        }
        assertEquals(primaries.values.count { result.pigment[it] == 1f } * 2, replicas.size)
    }

    @Test fun `大面板复制内容之后仍满足总材料上限`() {
        val pixels = IntArray(100) { if (it < 65) -1 else 0xff00a6b0.toInt() }
        val result = ParticleMicroflakeModel.build(1600f, 2100f, pixels, 10, 10, 135f, 64L, rules)
        assertTrue(result.count <= rules.number("max_cells").toInt())
        assertTrue(result.count > result.columns * result.rows)
    }

    @Test fun `实际GLES资源通过语法编译`() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "shared/particle-dismiss").isDirectory }
        val validator = File("E:/AndroidSDK/emulator/lib64/vulkan/glslangValidator.exe")
        org.junit.Assume.assumeTrue(validator.isFile)
        for (name in listOf("step.comp", "material.vert", "material.frag", "resolve.vert", "resolve.frag")) {
            val process = ProcessBuilder(validator.path, File(root, "shared/particle-dismiss/$name").path).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals("$name\n$output", 0, process.waitFor())
        }
    }
}
