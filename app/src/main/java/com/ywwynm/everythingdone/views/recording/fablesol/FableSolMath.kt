package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 移植所需的 numpy 基本操作端口（gradient / interp / convolve / percentile / hanning /
 * geomspace / searchsorted 等），均为一维 double 数组标量实现，语义对齐 numpy 默认行为。
 */
object FableSolMath {

    /** np.gradient(y, dx)：内部中心差分，端点单边差分。 */
    fun gradient(y: DoubleArray, dx: Double): DoubleArray {
        val n = y.size
        val out = DoubleArray(n)
        gradientInto(y, dx, out)
        return out
    }

    fun gradientInto(y: DoubleArray, dx: Double, out: DoubleArray) {
        val n = y.size
        if (n == 1) { out[0] = 0.0; return }
        out[0] = (y[1] - y[0]) / dx
        out[n - 1] = (y[n - 1] - y[n - 2]) / dx
        val inv2 = 1.0 / (2.0 * dx)
        for (i in 1 until n - 1) out[i] = (y[i + 1] - y[i - 1]) * inv2
    }

    /** np.interp(xq, xp, fp, left, right)：xp 升序；越界 clamp 到 left/right。 */
    fun interp(xq: DoubleArray, xp: DoubleArray, fp: DoubleArray,
               left: Double, right: Double): DoubleArray {
        val out = DoubleArray(xq.size)
        val n = xp.size
        for (idx in xq.indices) {
            val x = xq[idx]
            when {
                x <= xp[0] -> out[idx] = if (x < xp[0]) left else fp[0]
                x >= xp[n - 1] -> out[idx] = if (x > xp[n - 1]) right else fp[n - 1]
                else -> {
                    // 二分找区间 [j-1, j]
                    var lo = 0; var hi = n - 1
                    while (hi - lo > 1) {
                        val mid = (lo + hi) ushr 1
                        if (xp[mid] <= x) lo = mid else hi = mid
                    }
                    val x0 = xp[lo]; val x1 = xp[hi]
                    val t = (x - x0) / (x1 - x0)
                    out[idx] = fp[lo] + (fp[hi] - fp[lo]) * t
                }
            }
        }
        return out
    }

    /** np.maximum.accumulate。 */
    fun cumMax(a: DoubleArray): DoubleArray {
        val out = DoubleArray(a.size)
        if (a.isEmpty()) return out
        var m = a[0]; out[0] = m
        for (i in 1 until a.size) { if (a[i] > m) m = a[i]; out[i] = m }
        return out
    }

    /** np.pad(a, r, mode="edge")。 */
    fun padEdge(a: DoubleArray, r: Int): DoubleArray {
        val n = a.size
        val out = DoubleArray(n + 2 * r)
        for (i in 0 until r) out[i] = a[0]
        System.arraycopy(a, 0, out, r, n)
        for (i in 0 until r) out[r + n + i] = a[n - 1]
        return out
    }

    private fun convolveFull(a: DoubleArray, v: DoubleArray): DoubleArray {
        val n = a.size; val m = v.size
        val out = DoubleArray(n + m - 1)
        for (i in 0 until n) {
            val ai = a[i]
            if (ai == 0.0) continue
            for (j in 0 until m) out[i + j] += ai * v[j]
        }
        return out
    }

    /** np.convolve(a, v, mode="same")，要求 a.size >= v.size。 */
    fun convolveSame(a: DoubleArray, v: DoubleArray): DoubleArray {
        val n = a.size; val m = v.size
        val full = convolveFull(a, v)
        val start = (m - 1) / 2
        val out = DoubleArray(n)
        System.arraycopy(full, start, out, 0, n)
        return out
    }

    /** np.convolve(a, v, mode="valid")，返回长度 a.size - v.size + 1。 */
    fun convolveValid(a: DoubleArray, v: DoubleArray): DoubleArray {
        val n = a.size; val m = v.size
        val full = convolveFull(a, v)
        val len = n - m + 1
        val out = DoubleArray(len)
        System.arraycopy(full, m - 1, out, 0, len)
        return out
    }

    /** np.hanning(n)。 */
    fun hanning(n: Int): DoubleArray {
        if (n <= 1) return DoubleArray(n) { 1.0 }
        val out = DoubleArray(n)
        val d = (n - 1).toDouble()
        for (i in 0 until n) out[i] = 0.5 - 0.5 * cos(2.0 * Math.PI * i / d)
        return out
    }

    /** np.percentile(a, q)（q 为 0..100，linear 插值）；会复制排序，不改动入参。 */
    fun percentile(a: DoubleArray, q: Double): Double {
        val s = a.copyOf(); s.sort()
        return percentileSorted(s, q)
    }

    /** 一次排序算多个分位数。 */
    fun percentiles(a: DoubleArray, qs: DoubleArray): DoubleArray {
        val s = a.copyOf(); s.sort()
        return DoubleArray(qs.size) { percentileSorted(s, qs[it]) }
    }

    /**
     * 按相邻边界求和：[b0,b1)、[b1,b2)...；最后一段严格止于最后一个边界，
     * 不采用 np.add.reduceat“最后一段延伸到数组末尾”的语义。
     */
    fun sumAdjacentSegments(values: DoubleArray, boundaries: IntArray): DoubleArray {
        if (boundaries.size < 2) return DoubleArray(0)
        val out = DoubleArray(boundaries.size - 1)
        for (i in out.indices) {
            val start = boundaries[i].coerceIn(0, values.size)
            val end = boundaries[i + 1].coerceIn(start, values.size)
            var sum = 0.0
            for (j in start until end) sum += values[j]
            out[i] = sum
        }
        return out
    }

    private fun percentileSorted(s: DoubleArray, q: Double): Double {
        val n = s.size
        if (n == 0) return 0.0
        if (n == 1) return s[0]
        val pos = q / 100.0 * (n - 1)
        val lo = Math.floor(pos).toInt()
        val hi = Math.ceil(pos).toInt().coerceAtMost(n - 1)
        val frac = pos - lo
        return s[lo] * (1.0 - frac) + s[hi] * frac
    }

    /** np.geomspace(start, stop, n)：几何等分（含端点）。 */
    fun geomspace(start: Double, stop: Double, n: Int): DoubleArray {
        if (n == 1) return doubleArrayOf(start)
        val a = ln(start); val b = ln(stop)
        val out = DoubleArray(n)
        for (i in 0 until n) out[i] = exp(a + (b - a) * i / (n - 1))
        return out
    }

    /** np.searchsorted(a, v, side="left")：a 升序，返回插入点。 */
    fun searchsorted(a: DoubleArray, v: Double): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun mean(a: DoubleArray): Double {
        if (a.isEmpty()) return 0.0
        var s = 0.0
        for (x in a) s += x
        return s / a.size
    }

    fun mean(a: DoubleArray, from: Int, until: Int): Double {
        var s = 0.0
        val n = until - from
        if (n <= 0) return 0.0
        for (i in from until until) s += a[i]
        return s / n
    }

    fun rmsWeighted(sqSum: Double): Double = sqrt(sqSum)
}
