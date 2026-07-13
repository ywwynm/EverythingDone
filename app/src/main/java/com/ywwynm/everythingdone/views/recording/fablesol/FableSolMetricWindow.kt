package com.ywwynm.everythingdone.views.recording.fablesol

/** 固定容量滚动窗口；写入无分配，分位数仅在低频日志汇总时复制排序。 */
internal class FableSolMetricWindow(capacity: Int) {

    private val values = DoubleArray(capacity)
    private var cursor = 0
    var size: Int = 0
        private set

    fun add(value: Double) {
        values[cursor] = value
        cursor = (cursor + 1) % values.size
        if (size < values.size) size++
    }

    fun percentile(q: Double): Double {
        if (size == 0) return 0.0
        val sorted = values.copyOf(size)
        sorted.sort()
        if (size == 1) return sorted[0]
        val position = q.coerceIn(0.0, 100.0) / 100.0 * (size - 1)
        val lower = position.toInt()
        val upper = kotlin.math.ceil(position).toInt().coerceAtMost(size - 1)
        val fraction = position - lower
        return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction
    }
}
