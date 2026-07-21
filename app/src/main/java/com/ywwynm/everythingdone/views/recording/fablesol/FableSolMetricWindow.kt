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
        val sorted = sortedSnapshot()
        return interpolate(sorted, q)
    }

    /**
     * 一次排序取三个分位数。摘要与 HUD 每次都要 p50/p95/p99，逐个调用
     * [percentile] 会对同一份数据复制并排序三遍；这些代码跑在 GL 线程上。
     */
    fun percentiles(into: DoubleArray, q0: Double, q1: Double, q2: Double) {
        if (size == 0) {
            into[0] = 0.0; into[1] = 0.0; into[2] = 0.0
            return
        }
        val sorted = sortedSnapshot()
        into[0] = interpolate(sorted, q0)
        into[1] = interpolate(sorted, q1)
        into[2] = interpolate(sorted, q2)
    }

    /** 快照缓冲跨调用复用；窗口容量固定，稳态不分配。 */
    private fun sortedSnapshot(): DoubleArray {
        var buffer = snapshot
        if (buffer == null || buffer.size != size) {
            buffer = DoubleArray(size)
            snapshot = buffer
        }
        System.arraycopy(values, 0, buffer, 0, size)
        buffer.sort()
        return buffer
    }

    private fun interpolate(sorted: DoubleArray, q: Double): Double {
        if (size == 1) return sorted[0]
        val position = q.coerceIn(0.0, 100.0) / 100.0 * (size - 1)
        val lower = position.toInt()
        val upper = kotlin.math.ceil(position).toInt().coerceAtMost(size - 1)
        val fraction = position - lower
        return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction
    }

    private var snapshot: DoubleArray? = null
}
