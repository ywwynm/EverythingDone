package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ParticleMicroflakeModelTest {
    @Test fun `八方向释放时刻与桌面冻结输入一致`() {
        for (direction in listOf(0, 45, 90, 125, 180, 225, 270, 315)) {
            val bytes = javaClass.getResourceAsStream("/particle-dismiss/release-$direction.f32")!!.readBytes()
            val expected = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(expected)
            assertArrayEquals("方向 $direction", expected, ParticleMicroflakeModel.releaseField(37, 29, direction.toFloat()), 2e-6f)
        }
    }

    @Test fun `材料可重复且每个位置恰有一个微片并在一秒内退场`() {
        val pixels = intArrayOf(-1, -1, 0xffff6400.toInt(), 0x00ffffff)
        fun build() = ParticleMicroflakeModel.build(120f, 160f, pixels, 2, 2, 137f, 909602L)
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
        assertEquals(a.columns * a.rows, ids.size)
    }

    @Test fun `透明像素不改变白底识别且彩色材料仍保留原色`() {
        val white = ParticleMicroflakeModel.build(24f, 30f, intArrayOf(-1, 0), 2, 1, 90f, 9L)
        val color = ParticleMicroflakeModel.build(24f, 30f, intArrayOf(0xffff0000.toInt()), 1, 1, 90f, 9L)
        assertEquals(1f, white.bodyWeight, 0f)
        assertEquals(0f, color.bodyWeight, 0f)
        assertTrue(white.pigment.all { it == 0f })
        assertTrue(color.pigment.all { it == 1f })
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
