package com.ywwynm.everythingdone.spatial

import kotlin.math.abs
import kotlin.math.min

/**
 * 为遮挡边界样本构建无跨样本连接的前向 splat。
 *
 * 每个被选中的深度样本拥有一块独立纹理四边形。连续区域应交给
 * [SpatialHybridMeshBuilder] 的连通网格；这里的 splat 只负责断边两侧不能安全插值的窄带。
 * [includedSamples] 为空时仍可构建完整采样网格，供低层测试和诊断使用。
 */
internal object SpatialSplatMeshBuilder {

    const val FLOATS_PER_VERTEX = 3

    fun build(
        width: Int,
        height: Int,
        surfaceDepth: FloatArray,
        cutRight: BooleanArray,
        cutDown: BooleanArray,
        includedSamples: BooleanArray? = null,
        nearCutPaddingSamples: Float = 0f
    ): List<Chunk> {
        require(width > 0 && height > 0) { "splat 网格尺寸必须为正数" }
        require(surfaceDepth.size == width * height) { "splat 深度尺寸不匹配" }
        require(cutRight.size == height * (width - 1).coerceAtLeast(0)) {
            "splat 横向断边尺寸不匹配"
        }
        require(cutDown.size == (height - 1).coerceAtLeast(0) * width) {
            "splat 纵向断边尺寸不匹配"
        }
        require(includedSamples == null || includedSamples.size == width * height) {
            "splat 选中样本尺寸不匹配"
        }
        require(nearCutPaddingSamples in 0f..0.5f) {
            "断边近侧覆盖扩张必须位于 0..0.5 个采样间距"
        }
        require(width * VERTICES_PER_SPLAT <= MAX_UNSIGNED_SHORT_VERTICES) {
            "splat 网格宽度超出 GLES2 索引能力"
        }

        val rowsPerChunk = (MAX_UNSIGNED_SHORT_VERTICES / (width * VERTICES_PER_SPLAT))
            .coerceAtLeast(1)
        val chunks = mutableListOf<Chunk>()
        var firstRow = 0
        while (firstRow < height) {
            val rowCount = min(rowsPerChunk, height - firstRow)
            val firstSample = firstRow * width
            val lastSampleExclusive = (firstRow + rowCount) * width
            val splatCount = if (includedSamples == null) {
                width * rowCount
            } else {
                (firstSample until lastSampleExclusive).count { includedSamples[it] }
            }
            if (splatCount == 0) {
                firstRow += rowCount
                continue
            }
            val vertices = FloatArray(
                splatCount * VERTICES_PER_SPLAT * FLOATS_PER_VERTEX
            )
            val indices = ShortArray(splatCount * INDICES_PER_SPLAT)
            var vertexOffset = 0
            var indexOffset = 0
            for (localY in 0 until rowCount) {
                val y = firstRow + localY
                for (x in 0 until width) {
                    val sampleIndex = y * width + x
                    if (includedSamples != null && !includedSamples[sampleIndex]) continue
                    val depth = surfaceDepth[sampleIndex]
                    val u0 = (
                        lowerBoundary(x, width) -
                            horizontalOverlap(
                                depth = surfaceDepth,
                                cuts = cutRight,
                                width = width,
                                y = y,
                                currentX = x,
                                leftX = x - 1,
                                rightX = x,
                                nearCutPaddingSamples = nearCutPaddingSamples
                            )
                        ).coerceAtLeast(0f)
                    val u1 = (
                        upperBoundary(x, width) +
                            horizontalOverlap(
                                depth = surfaceDepth,
                                cuts = cutRight,
                                width = width,
                                y = y,
                                currentX = x,
                                leftX = x,
                                rightX = x + 1,
                                nearCutPaddingSamples = nearCutPaddingSamples
                            )
                        ).coerceAtMost(1f)
                    val v0 = (
                        lowerBoundary(y, height) -
                            verticalOverlap(
                                depth = surfaceDepth,
                                cuts = cutDown,
                                width = width,
                                height = height,
                                x = x,
                                currentY = y,
                                topY = y - 1,
                                bottomY = y,
                                nearCutPaddingSamples = nearCutPaddingSamples
                            )
                        ).coerceAtLeast(0f)
                    val v1 = (
                        upperBoundary(y, height) +
                            verticalOverlap(
                                depth = surfaceDepth,
                                cuts = cutDown,
                                width = width,
                                height = height,
                                x = x,
                                currentY = y,
                                topY = y,
                                bottomY = y + 1,
                                nearCutPaddingSamples = nearCutPaddingSamples
                            )
                        ).coerceAtMost(1f)
                    val firstVertex = vertexOffset / FLOATS_PER_VERTEX

                    putVertex(vertices, vertexOffset, u0, v0, depth)
                    vertexOffset += FLOATS_PER_VERTEX
                    putVertex(vertices, vertexOffset, u1, v0, depth)
                    vertexOffset += FLOATS_PER_VERTEX
                    putVertex(vertices, vertexOffset, u1, v1, depth)
                    vertexOffset += FLOATS_PER_VERTEX
                    putVertex(vertices, vertexOffset, u0, v1, depth)
                    vertexOffset += FLOATS_PER_VERTEX

                    indices[indexOffset++] = firstVertex.toShort()
                    indices[indexOffset++] = (firstVertex + 1).toShort()
                    indices[indexOffset++] = (firstVertex + 2).toShort()
                    indices[indexOffset++] = firstVertex.toShort()
                    indices[indexOffset++] = (firstVertex + 2).toShort()
                    indices[indexOffset++] = (firstVertex + 3).toShort()
                }
            }
            chunks += Chunk(vertices = vertices, indices = indices)
            firstRow += rowCount
        }
        return chunks
    }

    private fun horizontalOverlap(
        depth: FloatArray,
        cuts: BooleanArray,
        width: Int,
        y: Int,
        currentX: Int,
        leftX: Int,
        rightX: Int,
        nearCutPaddingSamples: Float
    ): Float {
        if (leftX < 0 || rightX >= width) return 0f
        if (cuts[y * (width - 1) + leftX]) {
            val currentDepth = depth[y * width + currentX]
            val neighborX = if (currentX == leftX) rightX else leftX
            val neighborDepth = depth[y * width + neighborX]
            return if (currentDepth > neighborDepth) {
                nearCutPaddingSamples / (width - 1)
            } else {
                0f
            }
        }
        val difference = abs(
            depth[y * width + leftX] - depth[y * width + rightX]
        )
        return difference * SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE * 0.5f
    }

    private fun verticalOverlap(
        depth: FloatArray,
        cuts: BooleanArray,
        width: Int,
        height: Int,
        x: Int,
        currentY: Int,
        topY: Int,
        bottomY: Int,
        nearCutPaddingSamples: Float
    ): Float {
        if (topY < 0 || bottomY >= height) return 0f
        if (cuts[topY * width + x]) {
            val currentDepth = depth[currentY * width + x]
            val neighborY = if (currentY == topY) bottomY else topY
            val neighborDepth = depth[neighborY * width + x]
            return if (currentDepth > neighborDepth) {
                nearCutPaddingSamples / (height - 1)
            } else {
                0f
            }
        }
        val difference = abs(
            depth[topY * width + x] - depth[bottomY * width + x]
        )
        return difference * SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE * 0.5f
    }

    private fun lowerBoundary(position: Int, size: Int): Float = when {
        size == 1 || position == 0 -> 0f
        else -> (position - 0.5f) / (size - 1)
    }

    private fun upperBoundary(position: Int, size: Int): Float = when {
        size == 1 || position == size - 1 -> 1f
        else -> (position + 0.5f) / (size - 1)
    }

    private fun putVertex(
        target: FloatArray,
        offset: Int,
        u: Float,
        v: Float,
        depth: Float
    ) {
        target[offset] = u
        target[offset + 1] = v
        target[offset + 2] = depth
    }

    internal data class Chunk(
        val vertices: FloatArray,
        val indices: ShortArray
    )

    private const val VERTICES_PER_SPLAT = 4
    private const val INDICES_PER_SPLAT = 6
    private const val MAX_UNSIGNED_SHORT_VERTICES = 65_535
}
