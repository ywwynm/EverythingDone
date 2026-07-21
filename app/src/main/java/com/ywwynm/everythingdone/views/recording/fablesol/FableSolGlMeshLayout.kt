package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.min

/** OpenGL 连续水面静态索引布局；列数不变时可跨帧复用。 */
internal object FableSolGlMeshLayout {

    const val COMPONENTS_PER_VERTEX = 8
    const val SHEEN_SLOPE_X_OFFSET = 6
    const val SHEEN_SLOPE_Z_OFFSET = 7
    const val GROUP_COUNT = FableSolSpec.N_LAYERS - 1
    private const val MAX_UNSIGNED_SHORT_VERTEX_COUNT = 0x10000

    fun vertexCount(columns: Int): Int = FableSolContinuousSurface.Z_ROWS * columns

    fun indicesPerGroup(columns: Int): Int =
        FableSolContinuousSurface.ROWS_PER_LAYER * (columns - 1) * 6

    fun buildIndices(columns: Int): ShortArray {
        require(columns >= 2)
        require(columns <= MAX_UNSIGNED_SHORT_VERTEX_COUNT / FableSolContinuousSurface.Z_ROWS) {
            "连续水面顶点数超出 GL_UNSIGNED_SHORT 索引范围：" +
                "rows=${FableSolContinuousSurface.Z_ROWS}, columns=$columns"
        }
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

    /**
     * 每个 pass 都是「读 source、写 target」的乒乓双缓冲，输出元素只依赖只读的
     * source，因此逐行切分与串行执行逐位一致，可以安全按行并行。19012 个元素
     * 乘 7 个 pass、再乘 slopeX/slopeZ 两路，是 build 段的第三大开销。
     *
     * 三个横向 pass 只读写本行，彼此之间不需要跨行汇合，因此融进同一次派发的
     * 行体（行内乒乓，仍是整段 source→target 的双缓冲，只是缓冲区按行分段）；
     * 四个纵深 pass 读相邻行，屏障必须保留。单路派发数 7 → 5。
     */
    fun smooth(values: FloatArray, scratch: FloatArray, rows: Int, columns: Int) {
        require(rows > 0 && columns > 0)
        val count = rows * columns
        require(values.size >= count && scratch.size >= count)

        FableSolRowParallel.run(rows) { startRow, endRow ->
            for (row in startRow until endRow) horizontalPasses(values, scratch, row, columns)
        }
        var source = if (HORIZONTAL_PASSES % 2 == 0) values else scratch
        var target = if (HORIZONTAL_PASSES % 2 == 0) scratch else values
        repeat(DEPTH_PASSES) {
            val from = source
            val into = target
            FableSolRowParallel.run(rows) { startRow, endRow ->
                for (row in startRow until endRow) depthPass(from, into, row, rows, columns)
            }
            val swap = source
            source = target
            target = swap
        }
        if (source !== values) System.arraycopy(source, 0, values, 0, count)
    }

    /**
     * slopeX / slopeZ 两路共享同一组 pass 结构，行体内先后处理即可共派发；
     * 两路各自持有 scratch，互不干扰。每个元素经历的运算与顺序与单路调用
     * 逐位相同，只是把 14 次派发压成 5 次。
     */
    fun smoothPair(
        valuesA: FloatArray,
        scratchA: FloatArray,
        valuesB: FloatArray,
        scratchB: FloatArray,
        rows: Int,
        columns: Int
    ) {
        require(rows > 0 && columns > 0)
        val count = rows * columns
        require(valuesA.size >= count && scratchA.size >= count)
        require(valuesB.size >= count && scratchB.size >= count)
        require(scratchA !== scratchB)

        FableSolRowParallel.run(rows) { startRow, endRow ->
            for (row in startRow until endRow) {
                horizontalPasses(valuesA, scratchA, row, columns)
                horizontalPasses(valuesB, scratchB, row, columns)
            }
        }
        var sourceA = if (HORIZONTAL_PASSES % 2 == 0) valuesA else scratchA
        var targetA = if (HORIZONTAL_PASSES % 2 == 0) scratchA else valuesA
        var sourceB = if (HORIZONTAL_PASSES % 2 == 0) valuesB else scratchB
        var targetB = if (HORIZONTAL_PASSES % 2 == 0) scratchB else valuesB
        repeat(DEPTH_PASSES) {
            val fromA = sourceA
            val intoA = targetA
            val fromB = sourceB
            val intoB = targetB
            FableSolRowParallel.run(rows) { startRow, endRow ->
                for (row in startRow until endRow) {
                    depthPass(fromA, intoA, row, rows, columns)
                    depthPass(fromB, intoB, row, rows, columns)
                }
            }
            var swap = sourceA
            sourceA = targetA
            targetA = swap
            swap = sourceB
            sourceB = targetB
            targetB = swap
        }
        if (sourceA !== valuesA) System.arraycopy(sourceA, 0, valuesA, 0, count)
        if (sourceB !== valuesB) System.arraycopy(sourceB, 0, valuesB, 0, count)
    }

    /** 单行的整组横向 pass；只读写 `[row*columns, row*columns+columns)` 两段。 */
    private fun horizontalPasses(values: FloatArray, scratch: FloatArray, row: Int, columns: Int) {
        var from = values
        var into = scratch
        repeat(HORIZONTAL_PASSES) {
            horizontalPass(from, into, row, columns)
            val swap = from
            from = into
            into = swap
        }
    }

    private fun horizontalPass(from: FloatArray, into: FloatArray, row: Int, columns: Int) {
        val rowStart = row * columns
        val lastColumn = columns - 1
        // 两端单独处理只是把 coerce 提出内层，求和顺序必须与 clamp 版
        // 完全一致：浮点加法不结合，`(b + 2a) + a` 与 `b + 3a` 的舍入
        // 次数不同，合并成 3a 会破坏逐位一致。
        val first = rowStart
        into[first] = (
            from[first] + 2f * from[first] + from[first + min(1, lastColumn)]
            ) * 0.25f
        for (column in 1 until lastColumn) {
            val center = rowStart + column
            into[center] = (
                from[center - 1] + 2f * from[center] + from[center + 1]
                ) * 0.25f
        }
        if (lastColumn > 0) {
            val last = rowStart + lastColumn
            into[last] = (
                from[last - 1] + 2f * from[last] + from[last]
                ) * 0.25f
        }
    }

    private fun depthPass(
        from: FloatArray,
        into: FloatArray,
        row: Int,
        rows: Int,
        columns: Int
    ) {
        val nearRow = (row - 1).coerceAtLeast(0) * columns
        val centerRow = row * columns
        val farRow = (row + 1).coerceAtMost(rows - 1) * columns
        for (column in 0 until columns) {
            into[centerRow + column] = (
                from[nearRow + column] +
                    2f * from[centerRow + column] +
                    from[farRow + column]
                ) * 0.25f
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
