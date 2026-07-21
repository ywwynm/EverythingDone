package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FableSolSheenSlopeFilterTest {

    @Test
    fun constantSlopeIsPreservedExactly() {
        val values = FloatArray(7 * 11) { 0.375f }
        val scratch = FloatArray(values.size)

        FableSolSheenSlopeFilter.smooth(values, scratch, rows = 7, columns = 11)

        for (value in values) assertEquals(0.375f, value, 0f)
    }

    @Test
    fun alternatingGridEnergyIsStronglyReducedWithoutChangingArrayShape() {
        val rows = 9
        val columns = 13
        val values = FloatArray(rows * columns) { index ->
            if (((index / columns) + index % columns) % 2 == 0) 1f else -1f
        }
        val before = adjacentVariation(values, rows, columns)
        val scratch = FloatArray(values.size)

        FableSolSheenSlopeFilter.smooth(values, scratch, rows, columns)

        assertTrue(adjacentVariation(values, rows, columns) < before * 0.08)
        assertTrue(values.all { it.isFinite() })
    }

    @Test
    fun pingPongImplementationMatchesRepeatedEdgeClampReference() {
        val rows = 6
        val columns = 12
        val values = FloatArray(rows * columns) { index ->
            (kotlin.math.sin(index * 0.37) + 0.2 * kotlin.math.cos(index * 0.11)).toFloat()
        }
        val expected = referenceSmooth(values, rows, columns)

        FableSolSheenSlopeFilter.smooth(values, FloatArray(values.size), rows, columns)

        for (index in values.indices) assertEquals(expected[index], values[index], 0f)
    }

    /**
     * 生产规模（97 行 × 196 列）会越过 [FableSolRowParallel] 的并行阈值，走多线程
     * 分块路径；上面几例的行数都小于阈值，只覆盖了串行分支。每个 pass 都是读
     * source 写 target 的乒乓双缓冲，逐行切分必须与串行逐位一致。
     */
    @Test
    fun productionSizedGridMatchesReferenceBitExactlyOnTheParallelPath() {
        val rows = FableSolContinuousSurface.Z_ROWS
        val columns = 196
        assertTrue(rows >= 16)
        val values = FloatArray(rows * columns) { index ->
            (kotlin.math.sin(index * 0.013) + 0.35 * kotlin.math.cos(index * 0.071)).toFloat()
        }
        val expected = referenceSmooth(values, rows, columns)

        FableSolSheenSlopeFilter.smooth(values, FloatArray(values.size), rows, columns)

        for (index in values.indices) assertEquals(expected[index], values[index], 0f)
    }

    /** 单列时横向核退化为恒等；跳过整组横向 pass 不得改变结果。 */
    @Test
    fun singleColumnGridMatchesReference() {
        val rows = 21
        val values = FloatArray(rows) { index -> kotlin.math.sin(index * 0.41).toFloat() }
        val expected = referenceSmooth(values, rows, 1)

        FableSolSheenSlopeFilter.smooth(values, FloatArray(values.size), rows, 1)

        for (index in values.indices) assertEquals(expected[index], values[index], 0f)
    }

    /**
     * C6 把 slopeX/slopeZ 两路压进同一组派发（14 → 5）。两路共派发只改变行体内的
     * 执行编排，每个元素经历的运算序列必须与单路调用逐位相同。生产规模同时覆盖
     * 并行分块路径。
     */
    @Test
    fun 两路共派发与逐路单独平滑逐位相同() {
        val rows = FableSolContinuousSurface.Z_ROWS
        val columns = 196
        val seedX = FloatArray(rows * columns) { index ->
            (kotlin.math.sin(index * 0.013) + 0.35 * kotlin.math.cos(index * 0.071)).toFloat()
        }
        val seedZ = FloatArray(rows * columns) { index ->
            (kotlin.math.cos(index * 0.021) - 0.6 * kotlin.math.sin(index * 0.043)).toFloat()
        }
        val expectedX = seedX.copyOf()
        val expectedZ = seedZ.copyOf()
        FableSolSheenSlopeFilter.smooth(expectedX, FloatArray(expectedX.size), rows, columns)
        FableSolSheenSlopeFilter.smooth(expectedZ, FloatArray(expectedZ.size), rows, columns)

        val actualX = seedX.copyOf()
        val actualZ = seedZ.copyOf()
        FableSolSheenSlopeFilter.smoothPair(
            actualX,
            FloatArray(actualX.size),
            actualZ,
            FloatArray(actualZ.size),
            rows,
            columns
        )

        for (index in actualX.indices) {
            assertEquals(expectedX[index], actualX[index], 0f)
            assertEquals(expectedZ[index], actualZ[index], 0f)
        }
        // 与独立参考实现也必须逐位一致，防止两条路径同时错。
        val referenceX = referenceSmooth(seedX, rows, columns)
        for (index in actualX.indices) assertEquals(referenceX[index], actualX[index], 0f)
    }

    private fun referenceSmooth(input: FloatArray, rows: Int, columns: Int): FloatArray {
        var values = input.copyOf()
        repeat(3) {
            val next = FloatArray(values.size)
            for (row in 0 until rows) for (column in 0 until columns) {
                val center = row * columns + column
                val left = row * columns + (column - 1).coerceAtLeast(0)
                val right = row * columns + (column + 1).coerceAtMost(columns - 1)
                next[center] = (values[left] + 2f * values[center] + values[right]) * 0.25f
            }
            values = next
        }
        repeat(4) {
            val next = FloatArray(values.size)
            for (row in 0 until rows) for (column in 0 until columns) {
                val near = (row - 1).coerceAtLeast(0) * columns + column
                val center = row * columns + column
                val far = (row + 1).coerceAtMost(rows - 1) * columns + column
                next[center] = (values[near] + 2f * values[center] + values[far]) * 0.25f
            }
            values = next
        }
        return values
    }

    private fun adjacentVariation(values: FloatArray, rows: Int, columns: Int): Double {
        var total = 0.0
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val index = row * columns + column
                if (column + 1 < columns) total += abs(values[index] - values[index + 1])
                if (row + 1 < rows) total += abs(values[index] - values[index + columns])
            }
        }
        return total
    }
}
