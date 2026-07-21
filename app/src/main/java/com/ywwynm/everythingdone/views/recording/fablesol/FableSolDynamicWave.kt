package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * 动态浪（对应 waves.py 的 DynamicWave）：1D 波动方程 heightfield（速度形式），
 * 能量注入 = 平滑位移 + 行波速度对。
 */
class FableSolDynamicWave(private val n: Int, private val dx: Double) {

    @JvmField val u = DoubleArray(n)   // 高度偏移 dp
    @JvmField val v = DoubleArray(n)   // 垂直速度 dp/s
    private val lap = DoubleArray(n)
    private val advU = DoubleArray(n)
    private val advV = DoubleArray(n)
    // 注入行波速度对的梯度缓冲；按最大网格宽度预分配，有效区间由 inject 的 len 限定。
    private val injectGradient = DoubleArray(n)

    /**
     * c_scale：每列波速缩放（0 处波无法传播 → 反弹壁）。
     * v_decay：只吸动能的摩擦带。visc：黏滞系数剖面（v 的拉普拉斯平滑，抗尖峰）。
     */
    fun step(dt: Double, cDps: Double, dampHalflifeS: Double,
             spongeDecay: DoubleArray?, cScale: DoubleArray?,
             vDecay: DoubleArray?, visc: DoubleArray?, advectDps: Double) {
        val c = minOf(cDps, 0.9 * dx / dt)   // CFL clamp
        lap[0] = u[1] - u[0]                  // Neumann 端
        lap[n - 1] = u[n - 2] - u[n - 1]
        for (i in 1 until n - 1) lap[i] = u[i - 1] + u[i + 1] - 2.0 * u[i]
        val coef = dt * c * c / (dx * dx)
        if (cScale != null) for (i in 0 until n) v[i] += coef * cScale[i] * lap[i]
        else for (i in 0 until n) v[i] += coef * lap[i]
        val damp = exp(-LN2 * dt / maxOf(dampHalflifeS, 1e-3))
        for (i in 0 until n) v[i] *= damp
        if (spongeDecay != null) for (i in 0 until n) {
            v[i] *= spongeDecay[i]
            u[i] *= 1.0 - (1.0 - spongeDecay[i]) * 0.5   // 海绵区同吸位移，防残余平台
        }
        if (vDecay != null) for (i in 0 until n) v[i] *= vDecay[i]
        if (visc != null) {
            lap[0] = 0.0; lap[n - 1] = 0.0
            for (i in 1 until n - 1) lap[i] = v[i - 1] + v[i + 1] - 2.0 * v[i]
            for (i in 0 until n) v[i] += visc[i] * lap[i]
        }
        for (i in 0 until n) v[i] = v[i].coerceIn(-300.0, 300.0)  // 倾斜猛转动量安全阀
        for (i in 0 until n) u[i] += dt * v[i]
        // 均匀背景流的物质导数：一阶迎风（|U|dt/dx<1 单调稳定）
        val q = (advectDps * dt / dx).coerceIn(-0.9, 0.9)
        if (q > 1e-7) {
            advU[0] = u[0] * (1.0 - q); advV[0] = v[0] * (1.0 - q)
            for (i in 1 until n) {
                advU[i] = u[i] - q * (u[i] - u[i - 1])
                advV[i] = v[i] - q * (v[i] - v[i - 1])
            }
            System.arraycopy(advU, 0, u, 0, n); System.arraycopy(advV, 0, v, 0, n)
        } else if (q < -1e-7) {
            advU[n - 1] = u[n - 1] * (1.0 + q); advV[n - 1] = v[n - 1] * (1.0 + q)
            for (i in 0 until n - 1) {
                advU[i] = u[i] - q * (u[i + 1] - u[i])
                advV[i] = v[i] - q * (v[i + 1] - v[i])
            }
            System.arraycopy(advU, 0, u, 0, n); System.arraycopy(advV, 0, v, 0, n)
        }
        var mean = 0.0
        for (i in 0 until n) mean += u[i]
        mean /= n
        val sub = mean * (dt / RENORM_TAU_S)   // 体积漂移缓慢泄放
        for (i in 0 until n) u[i] -= sub
    }

    /**
     * bump 为已含幅度的 Hann 轮廓（dp）。travel∈[-1,1]：0 对称铺开，±1 纯行波。
     *
     * bump 允许比 `i1 - i0` 长（调用方复用 scratch）；只读前 `i1 - i0` 个元素，
     * 梯度也只在这段上求，与传入恰好等长数组时逐位一致。
     */
    fun inject(i0: Int, i1: Int, bump: DoubleArray, travel: Double, cDps: Double) {
        val len = i1 - i0
        for (k in 0 until len) u[i0 + k] += bump[k]
        if (abs(travel) > 1e-6) {
            val grad = injectGradient
            FableSolMath.gradientInto(bump, len, dx, grad)
            val f = -travel * cDps
            for (k in 0 until len) v[i0 + k] += f * grad[k]
        }
    }

    companion object {
        private const val RENORM_TAU_S = 2.4
        private val LN2 = ln(2.0)
    }
}
