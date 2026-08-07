package com.ywwynm.everythingdone.spatial

import kotlin.math.min

/**
 * 连续区域使用共享顶点的三角网格，只有显式遮挡断边两侧使用独立 splat。
 *
 * 这避免把人脸等连续主体拆成会各自运动的纹理块，同时阻止三角形跨越真实前后景断层。
 */
internal object SpatialHybridMeshBuilder {

    data class Mesh(
        val connected: List<SpatialSplatMeshBuilder.Chunk>,
        val boundarySplats: List<SpatialSplatMeshBuilder.Chunk>
    )

    fun build(
        width: Int,
        height: Int,
        surfaceDepth: FloatArray,
        cutRight: BooleanArray,
        cutDown: BooleanArray,
        nearCutPaddingSamples: Float = 0f,
        excludedSamples: BooleanArray? = null
    ): Mesh {
        require(width > 0 && height > 0) { "混合网格尺寸必须为正数" }
        require(surfaceDepth.size == width * height) { "混合网格深度尺寸不匹配" }
        require(cutRight.size == height * (width - 1).coerceAtLeast(0)) {
            "混合网格横向断边尺寸不匹配"
        }
        require(cutDown.size == (height - 1).coerceAtLeast(0) * width) {
            "混合网格纵向断边尺寸不匹配"
        }
        require(excludedSamples == null || excludedSamples.size == width * height) {
            "混合网格排除区域尺寸不匹配"
        }

        val boundarySamples = collectBoundarySamples(
            width = width,
            height = height,
            cutRight = cutRight,
            cutDown = cutDown,
            excludedSamples = excludedSamples
        )
        return Mesh(
            connected = buildConnectedChunks(
                width = width,
                height = height,
                surfaceDepth = surfaceDepth,
                cutRight = cutRight,
                cutDown = cutDown,
                excludedSamples = excludedSamples
            ),
            boundarySplats = SpatialSplatMeshBuilder.build(
                width = width,
                height = height,
                surfaceDepth = surfaceDepth,
                cutRight = cutRight,
                cutDown = cutDown,
                includedSamples = boundarySamples,
                nearCutPaddingSamples = nearCutPaddingSamples
            )
        )
    }

    private fun buildConnectedChunks(
        width: Int,
        height: Int,
        surfaceDepth: FloatArray,
        cutRight: BooleanArray,
        cutDown: BooleanArray,
        excludedSamples: BooleanArray?
    ): List<SpatialSplatMeshBuilder.Chunk> {
        if (width < 2 || height < 2) return emptyList()
        require(width * 2 <= MAX_UNSIGNED_SHORT_VERTICES) {
            "混合网格宽度超出 GLES2 索引能力"
        }
        val rowsPerChunk = min(
            MAX_ROWS_PER_CHUNK,
            MAX_UNSIGNED_SHORT_VERTICES / width
        ).coerceAtLeast(2)
        val chunks = mutableListOf<SpatialSplatMeshBuilder.Chunk>()
        var firstRow = 0
        while (firstRow < height - 1) {
            val rowCount = min(rowsPerChunk, height - firstRow)
            val vertices = FloatArray(
                width * rowCount * SpatialSplatMeshBuilder.FLOATS_PER_VERTEX
            )
            for (localY in 0 until rowCount) {
                val y = firstRow + localY
                val v = y.toFloat() / (height - 1)
                for (x in 0 until width) {
                    val target = (localY * width + x) *
                        SpatialSplatMeshBuilder.FLOATS_PER_VERTEX
                    vertices[target] = x.toFloat() / (width - 1)
                    vertices[target + 1] = v
                    vertices[target + 2] = surfaceDepth[y * width + x]
                }
            }

            val indices = ShortArray((rowCount - 1) * (width - 1) * 6)
            var indexCount = 0
            fun append(value: Int) {
                indices[indexCount++] = value.toShort()
            }
            for (localY in 0 until rowCount - 1) {
                val y = firstRow + localY
                for (x in 0 until width - 1) {
                    val topLeft = localY * width + x
                    val topRight = topLeft + 1
                    val bottomLeft = topLeft + width
                    val bottomRight = bottomLeft + 1

                    val topConnected = !cutRight[y * (width - 1) + x]
                    val rightConnected = !cutDown[y * width + x + 1]
                    val globalTopLeft = y * width + x
                    val globalTopRight = globalTopLeft + 1
                    val globalBottomLeft = globalTopLeft + width
                    val globalBottomRight = globalBottomLeft + 1
                    if (topConnected && rightConnected &&
                        excludedSamples?.get(globalTopLeft) != true &&
                        excludedSamples?.get(globalTopRight) != true &&
                        excludedSamples?.get(globalBottomRight) != true
                    ) {
                        append(topLeft)
                        append(topRight)
                        append(bottomRight)
                    }

                    val leftConnected = !cutDown[y * width + x]
                    val bottomConnected = !cutRight[(y + 1) * (width - 1) + x]
                    if (leftConnected && bottomConnected &&
                        excludedSamples?.get(globalTopLeft) != true &&
                        excludedSamples?.get(globalBottomRight) != true &&
                        excludedSamples?.get(globalBottomLeft) != true
                    ) {
                        append(topLeft)
                        append(bottomRight)
                        append(bottomLeft)
                    }
                }
            }
            if (indexCount > 0) {
                chunks += SpatialSplatMeshBuilder.Chunk(
                    vertices = vertices,
                    indices = indices.copyOf(indexCount)
                )
            }
            firstRow += rowCount - 1
        }
        return chunks
    }

    private fun collectBoundarySamples(
        width: Int,
        height: Int,
        cutRight: BooleanArray,
        cutDown: BooleanArray,
        excludedSamples: BooleanArray?
    ): BooleanArray {
        val selected = BooleanArray(width * height)
        if (width > 1) {
            for (y in 0 until height) {
                for (x in 0 until width - 1) {
                    if (!cutRight[y * (width - 1) + x]) continue
                    val left = y * width + x
                    val right = left + 1
                    if (excludedSamples?.get(left) != true) selected[left] = true
                    if (excludedSamples?.get(right) != true) selected[right] = true
                }
            }
        }
        if (height > 1) {
            for (y in 0 until height - 1) {
                for (x in 0 until width) {
                    if (!cutDown[y * width + x]) continue
                    val top = y * width + x
                    val bottom = top + width
                    if (excludedSamples?.get(top) != true) selected[top] = true
                    if (excludedSamples?.get(bottom) != true) selected[bottom] = true
                }
            }
        }
        return selected
    }

    private const val MAX_ROWS_PER_CHUNK = 96
    private const val MAX_UNSIGNED_SHORT_VERTICES = 65_535
}
