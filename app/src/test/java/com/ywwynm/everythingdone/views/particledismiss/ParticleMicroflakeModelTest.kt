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
