package com.ywwynm.everythingdone.helpers

/** 与 GridLayoutManager 相同的余数分配，用于不同列数分区的像素对齐。 */
object DetailGridGeometry {
    fun borders(width: Int, columns: Int): IntArray {
        val count = columns.coerceAtLeast(1)
        val borders = IntArray(count + 1)
        val size = width / count
        val remainder = width % count
        var additional = 0
        var used = 0
        for (i in 1..count) {
            var cell = size
            additional += remainder
            if (additional > 0 && count - additional < remainder) {
                cell++
                additional -= count
            }
            used += cell
            borders[i] = used
        }
        return borders
    }
}
