package com.ywwynm.everythingdone.views.particledismiss

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内容分析拆成「取样位置 + 按样本分析」之后（零拷贝快照让 GPU 去取样本），结果必须与拆分之前逐位相同。
 * [legacyAnalyse] 是拆分之前的实现，原样搬过来作对照。
 */
class ParticleDriftContentSamplesTest {

    private fun legacyAnalyse(width: Int, height: Int, rgba: ByteBuffer, baseCount: Int): ParticleDriftContent.Analysis {
        if (width <= 0 || height <= 0) return ParticleDriftContent.Analysis.NONE
        fun channel(index: Int, k: Int) = rgba.get(index * 4 + k).toInt() and 255
        val stride = ceil(sqrt(width.toDouble() * height / 65536.0)).toInt().coerceAtLeast(1)
        val counts = IntArray(4096)
        val sums = Array(3) { LongArray(4096) }
        var opaque = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val i = y * width + x
                if (channel(i, 3) == 255) {
                    val r = channel(i, 0); val g = channel(i, 1); val b = channel(i, 2)
                    val key = (r / 16) * 256 + (g / 16) * 16 + b / 16
                    counts[key]++
                    sums[0][key] += r.toLong(); sums[1][key] += g.toLong(); sums[2][key] += b.toLong()
                    opaque++
                }
                x += stride
            }
            y += stride
        }
        if (opaque == 0) return ParticleDriftContent.Analysis.NONE
        var best = 0
        for (key in counts.indices) if (counts[key] > counts[best]) best = key
        val panel = FloatArray(3) { (sums[it][best].toDouble() / counts[best] / 255.0).toFloat() }
        val fraction = counts[best].toFloat() / opaque
        val panelWeight = smooth((fraction - ParticleDriftContent.PANEL_MIN_FRACTION) /
            (ParticleDriftContent.PANEL_FULL_FRACTION - ParticleDriftContent.PANEL_MIN_FRACTION))
        if (panelWeight <= .01f) return ParticleDriftContent.Analysis(panel, 0f, 1, 1, FloatArray(0), 0)
        val grid = ParticleDriftContent.GRID
        val cellSamples = 4
        val cellPx = max(1f, max(width, height).toFloat() / grid)
        val gridWidth = ceil(width / cellPx).toInt().coerceIn(1, grid)
        val gridHeight = ceil(height / cellPx).toInt().coerceIn(1, grid)
        val density = FloatArray(gridWidth * gridHeight)
        var total = 0.0
        for (j in 0 until gridHeight) for (i in 0 until gridWidth) {
            var sum = 0f
            for (sy in 0 until cellSamples) for (sx in 0 until cellSamples) {
                val px = (((i + (sx + .5f) / cellSamples) / gridWidth) * width).toInt().coerceIn(0, width - 1)
                val py = (((j + (sy + .5f) / cellSamples) / gridHeight) * height).toInt().coerceIn(0, height - 1)
                val index = py * width + px
                val a = channel(index, 3)
                if (a == 0) continue
                val alpha = a.toFloat()
                sum += ParticleDriftContent.weight(min(channel(index, 0) / alpha, 1f), min(channel(index, 1) / alpha, 1f),
                    min(channel(index, 2) / alpha, 1f), panel, panelWeight) * (a / 255f)
            }
            val d = ParticleDriftContent.COPIES * panelWeight * sum / (cellSamples * cellSamples)
            density[j * gridWidth + i] = d
            total += d
        }
        val cells = gridWidth * gridHeight
        val replicas = min((baseCount * total / cells).roundToInt(), ParticleDriftContent.MAX_TOTAL - baseCount).coerceAtLeast(0)
        if (replicas == 0 || total <= 0.0) {
            return ParticleDriftContent.Analysis(panel, panelWeight, gridWidth, gridHeight, FloatArray(0), 0)
        }
        val cumulative = FloatArray(cells)
        var running = 0.0
        for (k in 0 until cells) {
            running += density[k]
            cumulative[k] = (running / total).toFloat()
        }
        cumulative[cells - 1] = 1f
        return ParticleDriftContent.Analysis(panel, panelWeight, gridWidth, gridHeight, cumulative, replicas)
    }

    private fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 预乘 RGBA8：白色面板、彩色块、透明圆角、半透明边、随机噪点。[kind] 换着来，覆盖各条分支。 */
    private fun image(width: Int, height: Int, kind: Int, random: Random): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        val base = when (kind % 4) { 0 -> intArrayOf(255, 255, 255); 1 -> intArrayOf(33, 33, 33); 2 -> intArrayOf(238, 238, 238)
            else -> intArrayOf(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        val blocks = List(3 + random.nextInt(6)) {
            intArrayOf(random.nextInt(width), random.nextInt(height), 1 + random.nextInt(max(1, width / 3)),
                1 + random.nextInt(max(1, height / 4)), random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }
        val corner = min(width, height) / 10
        for (y in 0 until height) for (x in 0 until width) {
            var r = base[0]; var g = base[1]; var b = base[2]; var a = 255
            for (block in blocks) if (x >= block[0] && x < block[0] + block[2] && y >= block[1] && y < block[1] + block[3]) {
                r = block[4]; g = block[5]; b = block[6]
            }
            if (kind % 3 == 2 && random.nextInt(50) == 0) { r = random.nextInt(256); g = random.nextInt(256); b = random.nextInt(256) }
            val inCorner = (x < corner && y < corner) || (x >= width - corner && y >= height - corner)
            if (inCorner) a = 0
            else if (x == 0 || y == 0 || x == width - 1 || y == height - 1) a = 128
            if (kind == 7) a = 0
            buffer.put((r * a / 255).toByte()).put((g * a / 255).toByte()).put((b * a / 255).toByte()).put(a.toByte())
        }
        buffer.rewind()
        return buffer
    }

    @Test fun `按样本分析与拆分之前逐位相同`() {
        val random = Random(20261006)
        val sizes = listOf(1440 to 3088, 1200 to 587, 1206 to 795, 734 to 2499, 1300 to 300, 1 to 1, 7 to 3, 333 to 129,
            64 to 64, 65 to 1000)
        for ((index, size) in sizes.withIndex()) for (kind in 0 until 8) {
            if (size.first * size.second > 2_000_000 && kind > 2) continue
            val (width, height) = size
            val pixels = image(width, height, kind + index, random)
            val baseCount = 10000 + random.nextInt(40000)
            val expected = legacyAnalyse(width, height, pixels, baseCount)
            val actual = ParticleDriftContent.analyse(width, height, pixels, baseCount)
            val label = "$width × $height 样式 $kind"
            assertArrayEquals(label, expected.panel, actual.panel, 0f)
            assertEquals(label, expected.panelWeight, actual.panelWeight, 0f)
            assertEquals(label, expected.gridWidth, actual.gridWidth)
            assertEquals(label, expected.gridHeight, actual.gridHeight)
            assertArrayEquals(label, expected.cumulative, actual.cumulative, 0f)
            assertEquals(label, expected.replicas, actual.replicas)
        }
    }

    @Test fun `取样位置都在图内且与按字节取的样本一一对应`() {
        for ((width, height) in listOf(1440 to 3088, 1200 to 587, 7 to 3, 1 to 1)) {
            val positions = ParticleDriftContent.samplePositions(width, height)
            assertTrue(positions.isNotEmpty())
            assertTrue(positions.all { it in 0 until width * height })
            val pixels = image(width, height, 1, Random(7))
            val samples = ParticleDriftContent.samplesFrom(pixels, positions)
            for (k in positions.indices step 97) {
                val o = positions[k] * 4
                val expected = (pixels.get(o).toInt() and 255) or ((pixels.get(o + 1).toInt() and 255) shl 8) or
                    ((pixels.get(o + 2).toInt() and 255) shl 16) or ((pixels.get(o + 3).toInt() and 255) shl 24)
                assertEquals(expected, samples[k])
            }
        }
        // 整屏快照 1440 × 3088：步长 9 的网格 160 × 344 个点，加上 30 × 64 格每格 16 个点
        assertEquals(160 * 344 + 30 * 64 * 16, ParticleDriftContent.samplePositions(1440, 3088).size)
    }

    @Test fun `整字取样不受缓冲字节序和当前位置影响且不改变调用方状态`() {
        for (order in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            val pixels = ByteBuffer.allocateDirect(12).order(order)
            for (value in intArrayOf(1, 2, 3, 4, 16, 32, 48, 64, 255, 128, 64, 0)) pixels.put(value.toByte())
            pixels.position(5)
            val result = ParticleDriftContent.samplesFrom(pixels.asReadOnlyBuffer().order(order), intArrayOf(2, 0, 1, 2))
            assertArrayEquals(intArrayOf(0x004080ff, 0x04030201, 0x40302010, 0x004080ff), result)
            assertEquals(5, pixels.position())
            assertEquals(order, pixels.order())
        }
    }

    @Test fun `重复颜色与不同透明度的权重复用仍与原实现逐位相同`() {
        val width = 129
        val height = 144
        val random = Random(2026100602)
        val colors = IntArray(1024) { random.nextInt() }
        val pixels = ByteBuffer.allocateDirect(width * height * 4)
        for (i in 0 until width * height) {
            // 大面积纯色确保启用内容强调；其余颜色超过缓存容量，并覆盖半透明和非标准预乘。
            val value = if (i % 3 != 0) -1 else colors[(i / 3) % colors.size]
            for (channel in 0..3) pixels.put((value ushr (8 * channel)).toByte())
        }
        val expected = legacyAnalyse(width, height, pixels, 30000)
        val actual = ParticleDriftContent.analyse(width, height, pixels, 30000)
        assertArrayEquals(expected.panel, actual.panel, 0f)
        assertEquals(expected.panelWeight, actual.panelWeight, 0f)
        assertArrayEquals(expected.cumulative, actual.cumulative, 0f)
        assertEquals(expected.replicas, actual.replicas)
    }
}
