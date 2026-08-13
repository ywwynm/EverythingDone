package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class SpatialSurfaceChartRenderDataTest {

    @Test
    fun `高斯图集在每个源像素严格归一化且无 coverage 空洞`() {
        val source = source(width = 128, height = 96)
        val charts = SpatialSurfaceChartBuilder.build(
            source.colors,
            source.width,
            source.height,
            source.depth
        )
        val renderData = SpatialSurfaceChartRenderDataBuilder.build(
            charts.charts,
            charts.motionBasis,
            maximumAtlasSize = 1024
        )

        val sum = reconstructSourceWeightSum(charts.charts, renderData)
        for (index in sum.indices) {
            assertEquals("源像素 $index 的权重和不为 1", 1f, sum[index], 2e-4f)
        }
        assertEquals(charts.charts.chartCount, renderData.quads.size)
        assertTrue(renderData.quads.all { it.zWeight in 0f..1f && it.zWeight > 0f })
    }

    @Test
    fun `目标位置分子分母归一化不会把纯色表面变成深色拖带`() {
        val source = source(width = 132, height = 100)
        val charts = SpatialSurfaceChartBuilder.build(
            source.colors,
            source.width,
            source.height,
            source.depth
        )
        val renderData = SpatialSurfaceChartRenderDataBuilder.build(
            charts.charts,
            charts.motionBasis,
            maximumAtlasSize = 1024
        )
        val numerator = FloatArray(source.width * source.height)
        val denominator = FloatArray(numerator.size)
        val coverage = FloatArray(numerator.size)
        for (quad in renderData.quads) {
            val tile = decodeTile(source.width, source.height, renderData, quad)
            val sourceIndex = charts.charts.labels.indexOf(quad.chartIndex)
            val displacement = charts.motionBasis.horizontalX[sourceIndex] *
                -SpatialSurfaceChartBuilder.REQUESTED_MAXIMUM_PARALLAX *
                (source.width - 1)
            val integerDisplacement = displacement.roundToInt()
            for (localY in 0 until tile.height) {
                val targetY = tile.top + localY
                if (targetY !in 0 until source.height) continue
                for (localX in 0 until tile.width) {
                    val targetX = tile.left + localX + integerDisplacement
                    if (targetX !in 0 until source.width) continue
                    val weight = renderData.atlasWeights[
                        (tile.atlasTop + localY) * renderData.atlasWidth +
                            tile.atlasLeft + localX
                        ]
                    val target = targetY * source.width + targetX
                    val zWeight = quad.zWeight
                    numerator[target] += UNIFORM_COLOR * weight * zWeight
                    denominator[target] += weight * zWeight
                    coverage[target] += weight
                }
            }
        }

        var checked = 0
        for (index in coverage.indices) {
            if (coverage[index] < SpatialSurfaceChartBuilder.COVERAGE_ALPHA_HIGH) continue
            assertTrue(denominator[index] > 0f)
            assertEquals(UNIFORM_COLOR, numerator[index] / denominator[index], 2e-5f)
            checked++
        }
        assertTrue("有效 coverage 区域过少", checked > coverage.size * 0.70f)
    }

    @Test
    fun `局部 tile 图集尺寸受控且高斯尾部留在 tile 内`() {
        val source = source(width = 192, height = 144)
        val charts = SpatialSurfaceChartBuilder.build(
            source.colors,
            source.width,
            source.height,
            source.depth
        )
        val renderData = SpatialSurfaceChartRenderDataBuilder.build(
            charts.charts,
            charts.motionBasis,
            maximumAtlasSize = 1024
        )

        assertTrue(renderData.atlasWidth <= 1024)
        assertTrue(renderData.atlasHeight <= 1024)
        assertTrue(renderData.atlasWeights.all { it.isFinite() && it >= 0f })
        assertTrue(
            renderData.atlasWeights.count { it > 0f } <
                source.width * source.height * charts.charts.chartCount / 3
        )
    }

    private data class Source(
        val width: Int,
        val height: Int,
        val colors: IntArray,
        val depth: FloatArray
    )

    private fun source(width: Int, height: Int): Source {
        val colors = IntArray(width * height)
        val depth = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                colors[index] = (0xff shl 24) or
                    ((35 + x * 190 / width) shl 16) or
                    ((45 + y * 170 / height) shl 8) or
                    (55 + (x + 2 * y) % 150)
                depth[index] = (
                    0.08f + 0.70f * x / width + 0.14f * y / height +
                        if (x > width / 2 && y in height / 4..height * 3 / 4) 0.16f else 0f
                    ).coerceIn(0f, 1f)
            }
        }
        return Source(width, height, colors, depth)
    }

    private data class DecodedTile(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val atlasLeft: Int,
        val atlasTop: Int
    )

    private fun decodeTile(
        width: Int,
        height: Int,
        renderData: SpatialSurfaceChartRenderData,
        quad: SpatialSurfaceChartRenderData.ChartQuad
    ): DecodedTile {
        val left = (quad.sourceLeft * (width - 1)).roundToInt()
        val top = (quad.sourceTop * (height - 1)).roundToInt()
        val right = (quad.sourceRight * (width - 1)).roundToInt()
        val bottom = (quad.sourceBottom * (height - 1)).roundToInt()
        return DecodedTile(
            left = left,
            top = top,
            width = right - left + 1,
            height = bottom - top + 1,
            atlasLeft = (quad.atlasLeft * renderData.atlasWidth - 0.5f).roundToInt(),
            atlasTop = (quad.atlasTop * renderData.atlasHeight - 0.5f).roundToInt()
        )
    }

    private fun reconstructSourceWeightSum(
        charts: SpatialSurfaceChartData,
        renderData: SpatialSurfaceChartRenderData
    ): FloatArray {
        val result = FloatArray(charts.labels.size)
        for (quad in renderData.quads) {
            val tile = decodeTile(charts.width, charts.height, renderData, quad)
            for (localY in 0 until tile.height) {
                val globalY = tile.top + localY
                for (localX in 0 until tile.width) {
                    val globalX = tile.left + localX
                    result[globalY * charts.width + globalX] +=
                        renderData.atlasWeights[
                            (tile.atlasTop + localY) * renderData.atlasWidth +
                                tile.atlasLeft + localX
                            ]
                }
            }
        }
        return result
    }

    private companion object {
        const val UNIFORM_COLOR = 0.63f
    }
}
