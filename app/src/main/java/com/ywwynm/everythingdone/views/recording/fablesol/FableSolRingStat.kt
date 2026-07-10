package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 滚动数值环（对应 features.py 的 RingStat）：支持"最近 K 个"的百分位查询，
 * 锚点经 EMA 平滑防阶跃（滚动窗滑过响度突变边界时百分位会阶跃，锚缓慢滑移、v 本身仍即时通过）。
 */
class FableSolRingStat(private val cap: Int, private val anchorAlpha: Double) {

    private val buf = DoubleArray(cap)
    @JvmField var idx = 0
    @JvmField var count = 0
    private var lo = 0.0
    private var hi = 0.0
    private var hasAnchor = false

    fun reset() {
        idx = 0; count = 0; hasAnchor = false
    }

    fun push(v: Double) {
        buf[idx] = v
        idx = (idx + 1) % cap
        count = if (count + 1 < cap) count + 1 else cap
    }

    fun recent(kIn: Int): DoubleArray {
        val k = minOf(kIn, count)
        if (k <= 0) return EMPTY
        val start = ((idx - k) % cap + cap) % cap
        val out = DoubleArray(k)
        if (start + k <= cap) {
            System.arraycopy(buf, start, out, 0, k)
        } else {
            val first = cap - start
            System.arraycopy(buf, start, out, 0, first)
            System.arraycopy(buf, 0, out, first, k - first)
        }
        return out
    }

    fun span01(v: Double, k: Int, pLo: Double, pHi: Double, minSpan: Double): Double {
        val r = recent(k)
        if (r.size < 8) return 0.0
        val pr = FableSolMath.percentiles(r, doubleArrayOf(pLo, pHi))
        var loV = pr[0]; var hiV = pr[1]
        if (hasAnchor) {
            loV = lo + (loV - lo) * anchorAlpha
            hiV = hi + (hiV - hi) * anchorAlpha
        }
        lo = loV; hi = hiV; hasAnchor = true
        val span = maxOf(hiV - loV, minSpan)
        return ((v - loV) / span).coerceIn(0.0, 1.0)
    }

    companion object {
        private val EMPTY = DoubleArray(0)
    }
}
