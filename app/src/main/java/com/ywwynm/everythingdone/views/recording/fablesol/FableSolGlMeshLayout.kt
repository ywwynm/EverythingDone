package com.ywwynm.everythingdone.views.recording.fablesol

/** OpenGL 连续水面静态索引布局；列数不变时可跨帧复用。 */
internal object FableSolGlMeshLayout {

    const val COMPONENTS_PER_VERTEX = 6 // x、y、slopeX、slopeZ、depth01、crestPinch
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
