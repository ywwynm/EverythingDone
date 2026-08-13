package com.ywwynm.everythingdone.spatial

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class SpatialSurfaceChartBuilderTest {

    @Test
    fun `全图像素被多个四连通 chart 无重叠地覆盖`() {
        val fixture = fixture(width = 144, height = 96)

        val result = SpatialSurfaceChartBuilder.build(
            fixture.colors,
            fixture.width,
            fixture.height,
            fixture.depth
        )

        assertTrue(result.charts.chartCount >= 8)
        assertEquals(fixture.width * fixture.height, result.charts.labels.size)
        assertTrue(result.charts.labels.all { it in 0 until result.charts.chartCount })
        assertEveryChartIsConnected(result.charts)
    }

    @Test
    fun `每个 chart 只保留刚性位移且全局视差接近三十六像素`() {
        val fixture = fixture(width = 192, height = 128)

        val result = SpatialSurfaceChartBuilder.build(
            fixture.colors,
            fixture.width,
            fixture.height,
            fixture.depth
        )

        val firstHorizontal = FloatArray(result.charts.chartCount) { Float.NaN }
        val firstVertical = FloatArray(result.charts.chartCount) { Float.NaN }
        for (index in result.charts.labels.indices) {
            val label = result.charts.labels[index]
            val horizontal = result.motionBasis.horizontalX[index]
            val vertical = result.motionBasis.verticalY[index]
            if (firstHorizontal[label].isNaN()) {
                firstHorizontal[label] = horizontal
                firstVertical[label] = vertical
            } else {
                assertEquals(firstHorizontal[label], horizontal, 0f)
                assertEquals(firstVertical[label], vertical, 0f)
            }
            assertEquals(0f, result.motionBasis.horizontalY[index], 0f)
            assertEquals(0f, result.motionBasis.verticalX[index], 0f)
        }

        val spanAt720 = robustSpan(result.chartScalars) *
            720f / max(fixture.width, fixture.height)
        assertTrue("chart 视差过小：$spanAt720", spanAt720 >= 27f)
        assertTrue("chart 视差超出目标：$spanAt720", spanAt720 <= 40f)
        assertEquals(
            SpatialSurfaceChartBuilder.REQUESTED_MAXIMUM_PARALLAX,
            result.viewEnvelope.amplitudes.maxOrNull() ?: 0f,
            0f
        )
    }

    @Test
    fun `人物区域包含多组内部深度响应而不是整块卡片`() {
        val width = 240
        val height = 320
        val colors = IntArray(width * height)
        val depth = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val person = x in 58..184 && y in 34..294
                val red = if (person) 120 + (x * 91 / width) else 36 + y * 42 / height
                val green = if (person) 78 + (y * 104 / height) else 54 + x * 35 / width
                val blue = if (person) 62 + ((x + y) % 45) else 72 + y * 20 / height
                colors[index] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
                val background = 0.15f + 0.20f * y / height
                depth[index] = if (person) {
                    val head = if (y < 112) 0.16f else 0f
                    val torsoCurve = 0.12f * (1f - abs(x - 121f) / 64f).coerceAtLeast(0f)
                    val armDifference = when {
                        x < 82 -> -0.08f
                        x > 160 -> 0.09f
                        else -> 0f
                    }
                    (0.55f + head + torsoCurve + armDifference + 0.08f * y / height)
                        .coerceIn(0f, 1f)
                } else {
                    background
                }
            }
        }

        val result = SpatialSurfaceChartBuilder.build(colors, width, height, depth)
        val personCharts = LinkedHashSet<Int>()
        val personMotionPixels = ArrayList<Float>()
        for (y in 34..294) {
            for (x in 58..184) {
                val index = y * width + x
                personCharts += result.charts.labels[index]
                personMotionPixels += -SpatialSurfaceChartBuilder.REQUESTED_MAXIMUM_PARALLAX *
                    result.motionBasis.horizontalX[index] * (width - 1)
            }
        }

        assertTrue("人物只落入 ${personCharts.size} 个 chart", personCharts.size >= 18)
        val internalSpanAt720 = robustSpan(personMotionPixels.toFloatArray()) *
            720f / max(width, height)
        assertTrue("人物内部视差不足：$internalSpanAt720", internalSpanAt720 >= 8f)
    }

    @Test
    fun `隐藏底板只有公共平移且固定取景边距有效`() {
        val fixture = fixture(width = 180, height = 120)
        val result = SpatialSurfaceChartBuilder.build(
            fixture.colors,
            fixture.width,
            fixture.height,
            fixture.depth
        )

        val basis = result.backgroundMotionBasis
        assertTrue(result.charts.guardFraction in (14f / 720f)..0.20f)
        for (index in 1 until fixture.width * fixture.height) {
            assertEquals(basis.horizontalX[0], basis.horizontalX[index], 0f)
            assertEquals(basis.horizontalY[0], basis.horizontalY[index], 0f)
            assertEquals(basis.verticalX[0], basis.verticalX[index], 0f)
            assertEquals(basis.verticalY[0], basis.verticalY[index], 0f)
        }
    }

    private data class Fixture(
        val width: Int,
        val height: Int,
        val colors: IntArray,
        val depth: FloatArray
    )

    private fun fixture(width: Int, height: Int): Fixture {
        val colors = IntArray(width * height)
        val depth = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val red = (25 + x * 210 / width).coerceIn(0, 255)
                val green = (20 + y * 220 / height).coerceIn(0, 255)
                val blue = (45 + (x * 3 + y * 5) % 170).coerceIn(0, 255)
                colors[index] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
                depth[index] = (
                    0.12f + 0.58f * x / width + 0.22f * y / height +
                        if (x in width / 3..width / 2 && y > height / 4) 0.14f else 0f
                    ).coerceIn(0f, 1f)
            }
        }
        return Fixture(width, height, colors, depth)
    }

    private fun assertEveryChartIsConnected(charts: SpatialSurfaceChartData) {
        val seen = BooleanArray(charts.labels.size)
        val components = IntArray(charts.chartCount)
        val queue = ArrayDeque<Int>()
        for (start in charts.labels.indices) {
            if (seen[start]) continue
            val label = charts.labels[start]
            components[label]++
            seen[start] = true
            queue.add(start)
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                val x = index % charts.width
                val y = index / charts.width
                fun visit(neighbor: Int) {
                    if (!seen[neighbor] && charts.labels[neighbor] == label) {
                        seen[neighbor] = true
                        queue.add(neighbor)
                    }
                }
                if (x > 0) visit(index - 1)
                if (x + 1 < charts.width) visit(index + 1)
                if (y > 0) visit(index - charts.width)
                if (y + 1 < charts.height) visit(index + charts.width)
            }
        }
        assertTrue(components.all { it == 1 })
    }

    private fun robustSpan(values: FloatArray): Float {
        val sorted = values.copyOf()
        sorted.sort()
        fun percentile(fraction: Float): Float {
            val position = sorted.lastIndex * fraction
            val lower = position.toInt()
            val upper = kotlin.math.ceil(position).toInt()
            val weight = position - lower
            return sorted[lower] + (sorted[upper] - sorted[lower]) * weight
        }
        return percentile(0.95f) - percentile(0.05f)
    }
}
