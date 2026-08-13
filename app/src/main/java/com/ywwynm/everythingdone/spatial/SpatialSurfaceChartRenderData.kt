package com.ywwynm.everythingdone.spatial

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** 归一化全表面 chart renderer 的 CPU 侧权重图集和刚性四边形。 */
internal data class SpatialSurfaceChartRenderData(
    val atlasWidth: Int,
    val atlasHeight: Int,
    /** 单通道浮点权重；源位置上所有 chart 的权重和严格归一化为 1。 */
    val atlasWeights: FloatArray,
    val quads: List<ChartQuad>
) {
    init {
        require(atlasWidth > 0 && atlasHeight > 0)
        require(atlasWeights.size == atlasWidth * atlasHeight)
        require(quads.isNotEmpty())
    }

    data class ChartQuad(
        val chartIndex: Int,
        val sourceLeft: Float,
        val sourceTop: Float,
        val sourceRight: Float,
        val sourceBottom: Float,
        val atlasLeft: Float,
        val atlasTop: Float,
        val atlasRight: Float,
        val atlasBottom: Float,
        val horizontalX: Float,
        val horizontalY: Float,
        val verticalX: Float,
        val verticalY: Float,
        /** 已减去全局最大标量后的软 z 权重，范围为 (0, 1]。 */
        val zWeight: Float
    )
}

/**
 * 把离散 chart 标签转成局部高斯重叠图集。归一化发生在源位置；渲染时仍须在目标位置分别
 * 累加颜色分子、软 z 分母和原始 coverage，不能把这里的权重改作普通 over alpha。
 */
internal object SpatialSurfaceChartRenderDataBuilder {

    fun build(
        charts: SpatialSurfaceChartData,
        motionBasis: SpatialScreenSpaceMotionBasis,
        maximumAtlasSize: Int = DEFAULT_MAXIMUM_ATLAS_SIZE,
        overlapSigmaPxAt720: Float = SpatialSurfaceChartBuilder.OVERLAP_SIGMA_PX_AT_720
    ): SpatialSurfaceChartRenderData {
        require(motionBasis.width == charts.width && motionBasis.height == charts.height)
        require(maximumAtlasSize >= 256)
        require(overlapSigmaPxAt720 in 2f..12f)
        val sigma = overlapSigmaPxAt720 *
            max(charts.width, charts.height) / REFERENCE_LONG_EDGE
        val radius = ceil(GAUSSIAN_RADIUS * sigma).toInt().coerceAtLeast(1)
        val kernel = gaussianKernel(sigma, radius)
        val bounds = chartBounds(charts)
        val rawTiles = ArrayList<RawTile>(charts.chartCount)
        val weightSum = FloatArray(charts.labels.size)
        for (chart in 0 until charts.chartCount) {
            val bound = bounds[chart]
            val left = (bound.left - radius).coerceAtLeast(0)
            val top = (bound.top - radius).coerceAtLeast(0)
            val right = (bound.right + radius).coerceAtMost(charts.width - 1)
            val bottom = (bound.bottom + radius).coerceAtMost(charts.height - 1)
            val tileWidth = right - left + 1
            val tileHeight = bottom - top + 1
            val horizontal = FloatArray(tileWidth * tileHeight)
            for (localY in 0 until tileHeight) {
                val globalY = top + localY
                for (localX in 0 until tileWidth) {
                    val globalX = left + localX
                    var value = 0f
                    for (offset in -radius..radius) {
                        val sourceX = reflectIndex(globalX + offset, charts.width)
                        if (charts.labels[globalY * charts.width + sourceX] == chart) {
                            value += kernel[offset + radius]
                        }
                    }
                    horizontal[localY * tileWidth + localX] = value
                }
            }
            val weights = FloatArray(tileWidth * tileHeight)
            for (localY in 0 until tileHeight) {
                val globalY = top + localY
                for (localX in 0 until tileWidth) {
                    var value = 0f
                    for (offset in -radius..radius) {
                        val sourceY = reflectIndex(globalY + offset, charts.height)
                        if (sourceY in top..bottom) {
                            value += horizontal[
                                (sourceY - top) * tileWidth + localX
                                ] * kernel[offset + radius]
                        }
                    }
                    val localIndex = localY * tileWidth + localX
                    weights[localIndex] = value
                    weightSum[globalY * charts.width + left + localX] += value
                }
            }
            rawTiles += RawTile(
                chartIndex = chart,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                width = tileWidth,
                height = tileHeight,
                weights = weights
            )
        }
        require(weightSum.all { it > MINIMUM_WEIGHT_SUM && it.isFinite() }) {
            "chart 高斯覆盖存在空洞"
        }
        for (tile in rawTiles) {
            for (localY in 0 until tile.height) {
                val globalY = tile.top + localY
                for (localX in 0 until tile.width) {
                    val globalIndex = globalY * charts.width + tile.left + localX
                    val localIndex = localY * tile.width + localX
                    tile.weights[localIndex] /= weightSum[globalIndex]
                }
            }
        }

        val placements = packTiles(rawTiles, maximumAtlasSize)
        val atlasWidth = placements.atlasWidth
        val atlasHeight = placements.atlasHeight
        val atlas = FloatArray(atlasWidth * atlasHeight)
        val scalarByChart = FloatArray(charts.chartCount)
        val firstIndex = IntArray(charts.chartCount) { -1 }
        for (index in charts.labels.indices) {
            val chart = charts.labels[index]
            if (firstIndex[chart] < 0) firstIndex[chart] = index
        }
        val requestedAmplitude = SpatialSurfaceChartBuilder.REQUESTED_MAXIMUM_PARALLAX
        for (chart in 0 until charts.chartCount) {
            val index = firstIndex[chart]
            scalarByChart[chart] = -motionBasis.horizontalX[index] *
                (charts.width - 1) * requestedAmplitude
        }
        val lowerScalar = percentile(scalarByChart, 0.05f)
        val upperScalar = percentile(scalarByChart, 0.95f)
        val scalarRange = (upperScalar - lowerScalar).coerceAtLeast(1e-6f)
        val depthUnit = FloatArray(charts.chartCount) {
            (scalarByChart[it] - lowerScalar) / scalarRange
        }
        val maximumDepthUnit = depthUnit.maxOrNull() ?: 1f

        val quadByChart = arrayOfNulls<SpatialSurfaceChartRenderData.ChartQuad>(
            charts.chartCount
        )
        for (placement in placements.tiles) {
            val tile = placement.tile
            for (localY in 0 until tile.height) {
                val atlasOffset = (placement.y + GUTTER + localY) * atlasWidth +
                    placement.x + GUTTER
                tile.weights.copyInto(
                    destination = atlas,
                    destinationOffset = atlasOffset,
                    startIndex = localY * tile.width,
                    endIndex = (localY + 1) * tile.width
                )
            }
            val index = firstIndex[tile.chartIndex]
            quadByChart[tile.chartIndex] = SpatialSurfaceChartRenderData.ChartQuad(
                chartIndex = tile.chartIndex,
                sourceLeft = tile.left.toFloat() / (charts.width - 1),
                sourceTop = tile.top.toFloat() / (charts.height - 1),
                sourceRight = tile.right.toFloat() / (charts.width - 1),
                sourceBottom = tile.bottom.toFloat() / (charts.height - 1),
                atlasLeft = (placement.x + GUTTER + 0.5f) / atlasWidth,
                atlasTop = (placement.y + GUTTER + 0.5f) / atlasHeight,
                atlasRight = (
                    placement.x + GUTTER + tile.width - 0.5f
                    ) / atlasWidth,
                atlasBottom = (
                    placement.y + GUTTER + tile.height - 0.5f
                    ) / atlasHeight,
                horizontalX = motionBasis.horizontalX[index],
                horizontalY = motionBasis.horizontalY[index],
                verticalX = motionBasis.verticalX[index],
                verticalY = motionBasis.verticalY[index],
                zWeight = exp(
                    SpatialSurfaceChartBuilder.Z_SOFTNESS *
                        (depthUnit[tile.chartIndex] - maximumDepthUnit)
                ).coerceIn(MINIMUM_Z_WEIGHT, 1f)
            )
        }
        return SpatialSurfaceChartRenderData(
            atlasWidth = atlasWidth,
            atlasHeight = atlasHeight,
            atlasWeights = atlas,
            quads = quadByChart.map { checkNotNull(it) }
        )
    }

    private data class Bounds(
        var left: Int = Int.MAX_VALUE,
        var top: Int = Int.MAX_VALUE,
        var right: Int = Int.MIN_VALUE,
        var bottom: Int = Int.MIN_VALUE
    )

    private fun chartBounds(charts: SpatialSurfaceChartData): Array<Bounds> {
        val result = Array(charts.chartCount) { Bounds() }
        for (index in charts.labels.indices) {
            val x = index % charts.width
            val y = index / charts.width
            val bound = result[charts.labels[index]]
            bound.left = minOf(bound.left, x)
            bound.top = minOf(bound.top, y)
            bound.right = maxOf(bound.right, x)
            bound.bottom = maxOf(bound.bottom, y)
        }
        require(result.all { it.left <= it.right && it.top <= it.bottom })
        return result
    }

    private data class RawTile(
        val chartIndex: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val width: Int,
        val height: Int,
        val weights: FloatArray
    )

    private data class Placement(val tile: RawTile, val x: Int, val y: Int)

    private data class PackedTiles(
        val atlasWidth: Int,
        val atlasHeight: Int,
        val tiles: List<Placement>
    )

    private fun packTiles(
        tiles: List<RawTile>,
        maximumAtlasSize: Int
    ): PackedTiles {
        val sorted = tiles.sortedWith(
            compareByDescending<RawTile> { it.height }.thenByDescending { it.width }
        )
        val maximumTileWidth = sorted.maxOf { it.width + 2 * GUTTER }
        val totalArea = sorted.sumOf {
            (it.width + 2 * GUTTER).toLong() * (it.height + 2 * GUTTER)
        }
        var atlasWidth = nextPowerOfTwo(
            max(maximumTileWidth, ceil(sqrt(totalArea.toDouble())).toInt())
        ).coerceAtMost(maximumAtlasSize)
        require(atlasWidth >= maximumTileWidth) { "chart 权重 tile 超过纹理上限" }
        while (true) {
            val placements = ArrayList<Placement>(tiles.size)
            var x = 0
            var y = 0
            var rowHeight = 0
            for (tile in sorted) {
                val packedWidth = tile.width + 2 * GUTTER
                val packedHeight = tile.height + 2 * GUTTER
                if (x + packedWidth > atlasWidth) {
                    x = 0
                    y += rowHeight
                    rowHeight = 0
                }
                placements += Placement(tile, x, y)
                x += packedWidth
                rowHeight = max(rowHeight, packedHeight)
            }
            val usedHeight = y + rowHeight
            val atlasHeight = nextPowerOfTwo(usedHeight)
            if (atlasHeight <= maximumAtlasSize) {
                return PackedTiles(atlasWidth, atlasHeight, placements)
            }
            require(atlasWidth < maximumAtlasSize) { "chart 权重图集超过纹理上限" }
            atlasWidth = (atlasWidth * 2).coerceAtMost(maximumAtlasSize)
        }
    }

    private fun gaussianKernel(sigma: Float, radius: Int): FloatArray {
        val result = FloatArray(radius * 2 + 1)
        var sum = 0f
        for (offset in -radius..radius) {
            val value = exp(-0.5f * offset * offset / (sigma * sigma))
            result[offset + radius] = value
            sum += value
        }
        for (index in result.indices) result[index] /= sum
        return result
    }

    private fun reflectIndex(index: Int, size: Int): Int {
        var reflected = index
        while (reflected < 0 || reflected >= size) {
            reflected = if (reflected < 0) -reflected else 2 * size - 2 - reflected
        }
        return reflected
    }

    private fun percentile(values: FloatArray, fraction: Float): Float {
        val sorted = values.copyOf()
        sorted.sort()
        val position = sorted.lastIndex * fraction.coerceIn(0f, 1f)
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        val weight = position - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * weight
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value) result = result shl 1
        return result
    }

    private const val REFERENCE_LONG_EDGE = 720f
    private const val GAUSSIAN_RADIUS = 4f
    private const val GUTTER = 1
    private const val MINIMUM_WEIGHT_SUM = 1e-5f
    private const val MINIMUM_Z_WEIGHT = 1e-6f
    private const val DEFAULT_MAXIMUM_ATLAS_SIZE = 4096
}
