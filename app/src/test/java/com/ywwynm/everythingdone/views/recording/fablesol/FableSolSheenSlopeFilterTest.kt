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
