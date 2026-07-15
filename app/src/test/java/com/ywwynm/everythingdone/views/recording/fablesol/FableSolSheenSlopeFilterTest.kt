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
