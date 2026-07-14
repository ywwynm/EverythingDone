package com.ywwynm.everythingdone.views.recording.fablesol

/** OpenGL 连续水面静态索引布局；列数不变时可跨帧复用。 */
internal object FableSolGlMeshLayout {

    const val COMPONENTS_PER_VERTEX = 8
    const val SHEEN_SLOPE_X_OFFSET = 6
    const val SHEEN_SLOPE_Z_OFFSET = 7
    const val GROUP_COUNT = FableSolSpec.N_LAYERS - 1

    fun vertexCount(columns: Int): Int = FableSolContinuousSurface.Z_ROWS * columns

    fun indicesPerGroup(columns: Int): Int =
        FableSolContinuousSurface.ROWS_PER_LAYER * (columns - 1) * 6

    fun buildIndices(columns: Int): ShortArray {
        require(columns >= 2)
        val perGroup = indicesPerGroup(columns)
        val indices = ShortArray(perGroup * GROUP_COUNT)
        var cursor = 0
        for (layer in FableSolSpec.N_LAYERS - 1 downTo 1) {
            val farAnchor = layer * FableSolContinuousSurface.ROWS_PER_LAYER
            val nearAnchor = (layer - 1) * FableSolContinuousSurface.ROWS_PER_LAYER
            for (row in farAnchor - 1 downTo nearAnchor) {
                val far = row + 1
                for (column in 0 until columns - 1) {
                    val far0 = far * columns + column
                    val near0 = row * columns + column
                    val far1 = far0 + 1
                    val near1 = near0 + 1
                    indices[cursor++] = far0.toShort()
                    indices[cursor++] = near0.toShort()
                    indices[cursor++] = far1.toShort()
                    indices[cursor++] = near0.toShort()
                    indices[cursor++] = near1.toShort()
                    indices[cursor++] = far1.toShort()
                }
            }
        }
        check(cursor == indices.size)
        return indices
    }
}

/**
 * 对 HDR 大面积银泽单独做法线预滤波。几何、微法线、背坡阴影仍使用原始坡度，避免为了隐藏
 * 网格分片而削平水体本身；这里只移除会被 Fresnel 放大的单元级高频变化。
 */
internal object FableSolSheenSlopeFilter {
    private const val HORIZONTAL_PASSES = 3
    private const val DEPTH_PASSES = 4

    fun smooth(values: FloatArray, scratch: FloatArray, rows: Int, columns: Int) {
        require(rows > 0 && columns > 0)
        val count = rows * columns
        require(values.size >= count && scratch.size >= count)

        repeat(HORIZONTAL_PASSES) {
            for (row in 0 until rows) {
                val rowStart = row * columns
                for (column in 0 until columns) {
                    val center = rowStart + column
                    val left = rowStart + (column - 1).coerceAtLeast(0)
                    val right = rowStart + (column + 1).coerceAtMost(columns - 1)
                    scratch[center] = (values[left] + 2f * values[center] + values[right]) * 0.25f
                }
            }
            System.arraycopy(scratch, 0, values, 0, count)
        }

        repeat(DEPTH_PASSES) {
            for (row in 0 until rows) {
                val nearRow = (row - 1).coerceAtLeast(0) * columns
                val centerRow = row * columns
                val farRow = (row + 1).coerceAtMost(rows - 1) * columns
                for (column in 0 until columns) {
                    scratch[centerRow + column] = (
                        values[nearRow + column] +
                            2f * values[centerRow + column] +
                            values[farRow + column]
                        ) * 0.25f
                }
            }
            System.arraycopy(scratch, 0, values, 0, count)
        }
    }
}

/** EBO 上传缓存必须跟随 EGL/GL 资源生命周期失效，不能只按列数跨上下文复用。 */
internal class FableSolGlIndexBufferState {
    var columns: Int = 0
        private set
    var indexCountPerGroup: Int = 0
        private set

    fun requiresUpload(columns: Int): Boolean = this.columns != columns

    fun onUploaded(columns: Int) {
        this.columns = columns
        indexCountPerGroup = FableSolGlMeshLayout.indicesPerGroup(columns)
    }

    fun onGlResourcesReleased() {
        columns = 0
        indexCountPerGroup = 0
    }
}
