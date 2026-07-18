package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// 屏幕空间"有效重力"：dp 非现实米制，数值只负责保持正确的色散比例。
// c_phase=sqrt(g/k) → 长浪传播更快、短浪振荡更频繁；背景流 U 另行叠加。
private const val GRAVITY_DP_S2 = 32.0
private const val CAPILLARY_LENGTH_DP = 6.0
private const val TWO_PI = 2.0 * Math.PI

/** 流速接近零时沿用旧方向，避免相位方向在零点来回翻转。 */
private fun travelSign(velocity: Double, previous: Double): Double =
    if (abs(velocity) > 1e-3) Math.signum(velocity) else previous

/**
 * 环境波（对应 ambient.py 的 AmbientSet）：接近无声时的基础起伏。运动学正弦合成，随流速行进。
 */
class FableSolAmbientSet(seed: Long, baseLenDp: Double, private val n: Int = 4) {
    private val wavelength: DoubleArray
    private val k: DoubleArray
    private val relAmp: DoubleArray
    private val phase: DoubleArray
    private val speedJitter: DoubleArray
    private val breathT: DoubleArray
    private val breathPhi: DoubleArray
    private val dispersionJitter: DoubleArray
    private var travelDir = -1.0
    private var baseLen: Double

    init {
        val rng = FableSolRng(seed)
        // 波长在基准的 [0.5, 2] 倍间随机，避免整数比
        val e = rng.uniform(-0.69, 0.69, n)
        wavelength = DoubleArray(n) { baseLenDp * exp(e[it]) }
        k = DoubleArray(n) { TWO_PI / wavelength[it] }
        var sum = 0.0
        for (w in wavelength) sum += w
        relAmp = DoubleArray(n) { wavelength[it] / sum }   // 振幅 ∝ 波长，Σ=1
        phase = rng.uniform(0.0, TWO_PI, n)
        speedJitter = rng.uniform(0.88, 1.12, n)
        breathT = rng.uniform(8.0, 20.0, n)
        breathPhi = rng.uniform(0.0, TWO_PI, n)
        dispersionJitter = rng.uniform(0.94, 1.06, n)
        baseLen = baseLenDp
    }

    /** 基准波长参数变化时按比例缩放，保持相位连续。 */
    fun retune(baseLenDp: Double) {
        if (abs(baseLenDp - baseLen) < 1e-9) return
        val ratio = baseLenDp / baseLen
        for (c in 0 until n) { wavelength[c] *= ratio; k[c] = TWO_PI / wavelength[c] }
        baseLen = baseLenDp
    }

    fun advance(dt: Double, velDps: Double) {
        travelDir = travelSign(velDps, travelDir)
        for (c in 0 until n) {
            val omega = sqrt(GRAVITY_DP_S2 * k[c]) * dispersionJitter[c]
            phase[c] -= (k[c] * velDps * speedJitter[c] + travelDir * omega) * dt
        }
    }

    fun sample(xDp: DoubleArray, t: Double, ampDp: Double, breathDepth: Double): DoubleArray {
        val amps = DoubleArray(n) {
            ampDp * relAmp[it] * (1.0 + breathDepth * sin(TWO_PI * t / breathT[it] + breathPhi[it]))
        }
        val out = DoubleArray(xDp.size)
        for (i in xDp.indices) {
            val x = xDp[i]
            var s = 0.0
            for (c in 0 until n) s += sin(x * k[c] + phase[c]) * amps[c]
            out[i] = s
        }
        return out
    }
}

/**
 * 主浪（对应 ambient.py 的 HeroWave）：六个色散模态组成的低/中/高三尺度能量库。
 * 音频只向模态注入能量，模态各自攻击/释放并按波长色散行进。采样在可见窗归零均值，只塑形不改水位。
 */
class FableSolHeroWave(seed: Long, private val depth01: Double) {
    private val lenMult: DoubleArray
    private val group = intArrayOf(0, 0, 1, 1, 2, 2)
    private val weight = doubleArrayOf(0.58, 0.42, 0.58, 0.42, 0.58, 0.42)
    private val dispersionJitter: DoubleArray
    private val phase: DoubleArray
    private val breathT: DoubleArray
    private val breathPhi: DoubleArray
    private var travelDir = -1.0
    private var baseLen = 360.0

    init {
        val rng = FableSolRng(seed)
        val near = max(0.0, 1.0 - 4.0 * depth01)   // 层0=1.0、层1=0.5、层2起=0
        val base = rng.uniform(0.90, 1.12) * (1.0 + 0.18 * depth01 + 0.30 * near)
        val mult = doubleArrayOf(2.20, 1.62, 1.18, 0.91, 0.68, 0.50)
        lenMult = DoubleArray(6) { base * mult[it] }
        dispersionJitter = rng.uniform(0.94, 1.06, 6)
        phase = rng.uniform(0.0, TWO_PI, 6)
        // 长浪共享同一片"母水面"，中高频仍各自独立
        val sharedA = 0.72; val sharedB = 4.21
        val jit = rng.uniform(-0.08, 0.08, 2)
        phase[0] = sharedA + depth01 * 0.18 + jit[0]
        phase[1] = sharedB + depth01 * (-0.12) + jit[1]
        val bt = rng.uniform(5.5, 13.0, 6)
        breathT = DoubleArray(6) { bt[it] * (1.0 + 0.45 * depth01) }
        breathPhi = rng.uniform(0.0, TWO_PI, 6)
    }

    fun retune(baseLenDp: Double) { baseLen = max(baseLenDp, 48.0) }

    fun advance(dt: Double, velDps: Double) {
        travelDir = travelSign(velDps, travelDir)
        for (m in 0 until 6) {
            val km = TWO_PI / (baseLen * lenMult[m])
            val omega = sqrt(GRAVITY_DP_S2 * km) * dispersionJitter[m]
            phase[m] -= (km * velDps + travelDir * omega) * dt
        }
    }

    /**
     * ampField3：低/中/高三模态的空间能量包络。meanMask 给定时在该窗口（可见区）内归零均值。
     * 返回与 xDp 等长的高度贡献；总能量过低时返回全 0。
     */
    fun sample(xDp: DoubleArray, ampField3: Array<DoubleArray>, t: Double, breathDepth: Double,
               meanMask: BooleanArray?, roughness01: Double): DoubleArray {
        val lenX = xDp.size
        var maxAmp = 0.0
        for (band in 0 until 3) {
            val field = ampField3[band]
            for (i in 0 until lenX) if (field[i] > maxAmp) maxAmp = field[i]
        }
        if (maxAmp < 0.05) return DoubleArray(lenX)
        val kk = DoubleArray(6) { TWO_PI / (baseLen * lenMult[it]) }
        val w = DoubleArray(6) {
            weight[it] * (1.0 + breathDepth * 0.20 *
                    sin(TWO_PI * t / breathT[it] + breathPhi[it]))
        }
        val profLag = DoubleArray(lenX)
        val disp = DoubleArray(lenX)
        for (i in 0 until lenX) {
            val x = xDp[i]
            var sinSum = 0.0; var cosSum = 0.0
            for (m in 0 until 6) {
                val ph = x * kk[m] + phase[m]
                val localAmp = ampField3[group[m]][i]
                sinSum += sin(ph) * w[m] * localAmp
                cosSum += cos(ph) * w[m] * localAmp
            }
            profLag[i] = sinSum
            disp[i] = cosSum
        }
        // 有界 Gerstner 水平轨道位移：采样点向波峰聚集，形成窄峰宽谷
        val rough = roughness01.coerceIn(0.0, 1.0)
        val near = max(0.0, 1.0 - 3.0 * depth01)
        val chop = 0.10 + 0.10 * rough + 0.04 * near
        val dsign = -travelDir * chop
        for (i in 0 until lenX) disp[i] = dsign * disp[i]
        val dx = if (lenX >= 2) xDp[1] - xDp[0] else 1.0
        val grad = FableSolMath.gradient(disp, dx)
        var maxGrad = 0.0
        for (g in grad) { val a = abs(g); if (a > maxGrad) maxGrad = a }
        if (maxGrad > 0.52) { val f = 0.52 / maxGrad; for (i in 0 until lenX) disp[i] *= f }
        // 数值保险：强制最小采样间隔，随后重采样回单调 heightfield
        var minDiff = Double.MAX_VALUE
        for (i in 1 until lenX) { val d = xDp[i] - xDp[i - 1]; if (d < minDiff) minDiff = d }
        val minStep = max(minDiff * 0.24, 1e-4)
        val adv = DoubleArray(lenX)
        var run = -Double.MAX_VALUE
        for (i in 0 until lenX) {
            val tmp = (xDp[i] + disp[i]) - i * minStep
            if (tmp > run) run = tmp
            adv[i] = run + i * minStep
        }
        val prof = FableSolMath.interp(xDp, adv, profLag, profLag[0], profLag[lenX - 1])
        var m = 0.0
        if (meanMask != null) {
            var cnt = 0
            for (i in 0 until lenX) if (meanMask[i]) { m += prof[i]; cnt++ }
            m = if (cnt > 0) m / cnt else 0.0
        } else {
            for (i in 0 until lenX) m += prof[i]
            m /= lenX
        }
        for (i in 0 until lenX) prof[i] -= m
        return prof
    }
}

/**
 * 光学毛细波（对应 ambient.py 的 OpticalWaveSet）：只扰动光学法线的毛细波谱，
 * 不改变可见轮廓，但同样遵循重力/表面张力色散并被背景流平流。
 */
class FableSolOpticalWaveSet(seed: Long, depth01: Double, private val n: Int = 10) {
    private val wavelength: DoubleArray
    private val k: DoubleArray
    private val phase: DoubleArray
    private val weight: DoubleArray
    private val dispersionJitter: DoubleArray
    private var travelDir = -1.0

    init {
        val rng = FableSolRng(seed)
        val base = FableSolMath.geomspace(6.0, 54.0, n)
        val jit = rng.uniform(0.88, 1.12, n)
        wavelength = DoubleArray(n) { base[it] * jit[it] * (1.0 + 0.20 * depth01) }
        k = DoubleArray(n) { TWO_PI / wavelength[it] }
        phase = rng.uniform(0.0, TWO_PI, n)
        // 中等尺度最显眼，两端收敛；归一化后 sample 的 RMS 约等于目标坡度
        val center = ln(16.0 * (1.0 + 0.15 * depth01))
        val wj = rng.uniform(0.82, 1.18, n)
        weight = DoubleArray(n) {
            exp(-0.5 * ((ln(wavelength[it]) - center) / 0.68) * ((ln(wavelength[it]) - center) / 0.68)) * wj[it]
        }
        var sq = 0.0
        for (w in weight) sq += w * w
        val rms = sqrt(0.5 * sq)
        val inv = 1.0 / max(rms, 1e-6)
        for (i in 0 until n) weight[i] *= inv
        dispersionJitter = rng.uniform(0.94, 1.06, n)
    }

    fun advance(dt: Double, velDps: Double) {
        travelDir = travelSign(velDps, travelDir)
        for (c in 0 until n) {
            // 毛细项在短波上提高频率（屏幕空间等效毛细长度）
            val kc = k[c]
            val omega = sqrt(GRAVITY_DP_S2 * kc * (1.0 + (kc * CAPILLARY_LENGTH_DP) * (kc * CAPILLARY_LENGTH_DP))) *
                    dispersionJitter[c]
            phase[c] -= (kc * velDps + travelDir * omega) * dt
        }
    }

    /**
     * 把毛细坡度/曲率扰动写入 slope/curv（毛细增益与微法线带限已随参数移除，
     * 只剩固定基线）。曲率供闪点 facet 碎裂因子（2026-07-18 恢复闪点出生）。
     */
    fun sampleInto(xDp: DoubleArray, count: Int, roughness01: Double,
                   slope: DoubleArray, curv: DoubleArray) {
        val targetRms = 0.012 * (0.78 + 0.44 * roughness01.coerceIn(0.0, 1.0))
        for (i in 0 until count) {
            val x = xDp[i]
            var cs = 0.0
            var ss = 0.0
            for (c in 0 until n) {
                val ph = x * k[c] + phase[c]
                cs += cos(ph) * weight[c]
                ss += sin(ph) * (weight[c] * k[c])
            }
            slope[i] = targetRms * cs
            curv[i] = targetRms * (-ss)
        }
    }
}
