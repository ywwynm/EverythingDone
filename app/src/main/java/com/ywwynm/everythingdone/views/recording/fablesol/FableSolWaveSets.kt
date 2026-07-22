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

// 主浪的陡峭度红线：单模态波高 / 波长。Stokes 破碎极限是 0.142，而 2026-07-21
// 实测 CLIMAX 段波峰 height/width 的 p90 已到 0.134——贴着破碎线，正是"尖锐的浪"。
// 0.085 把最陡的十分位压到约 0.072（宽度仍是高度的 14 倍以上），可见起伏 RMS 只掉
// 2~5%：浪要更高，能量就必须转到更长的模态上，"高"总是伴随"宽"。
const val MAX_HEIGHT_OVER_LENGTH = 0.085

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
    private val ampScratch = DoubleArray(n)
    /** 测试专用：强制走逐点直接求值分支，用于与递推路径做逐点对照。 */
    internal var forceDirectEvaluationForTest = false
    private val sinState = DoubleArray(n)
    private val cosState = DoubleArray(n)
    private val stepSinState = DoubleArray(n)
    private val stepCosState = DoubleArray(n)
    // 步进旋转只依赖 k[c]·dx；k 仅在 retune 改变（同时改 baseLen），dx 是采样网格步长。
    // 两者都未变时跨帧复用，每帧再省 2n 次 libm。NaN 初值保证首帧必算。
    private var stepCacheDx = Double.NaN
    private var stepCacheBaseLen = Double.NaN

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
        val out = DoubleArray(xDp.size)
        sampleInto(xDp, t, ampDp, breathDepth, out)
        return out
    }

    fun sampleInto(
        xDp: DoubleArray,
        t: Double,
        ampDp: Double,
        breathDepth: Double,
        out: DoubleArray
    ) {
        require(out.size >= xDp.size)
        for (c in 0 until n) {
            ampScratch[c] = ampDp * relAmp[c] *
                (1.0 + breathDepth * sin(TWO_PI * t / breathT[c] + breathPhi[c]))
        }
        val lenX = xDp.size
        if (!forceDirectEvaluationForTest &&
            lenX >= 2 && FableSolWaveRecurrence.isUniform(xDp, lenX)
        ) {
            // 与 Hero 同构的相位递推：每帧 libm 从 9 层 × 216 点 × 4 模态 = 7776 次
            // 降到 4 × 2 = 8 次。这条路径没有"能量过低"早退，静音时同样在跑。
            // 四组 (s, c) 交错推进，累加顺序仍是 c = 0→n-1。
            val dx = xDp[1] - xDp[0]
            val x0 = xDp[0]
            if (dx != stepCacheDx || baseLen != stepCacheBaseLen) {
                for (c in 0 until n) {
                    val stepPhase = k[c] * dx
                    stepSinState[c] = sin(stepPhase)
                    stepCosState[c] = cos(stepPhase)
                }
                stepCacheDx = dx
                stepCacheBaseLen = baseLen
            }
            for (c in 0 until n) {
                val base = x0 * k[c] + phase[c]
                sinState[c] = sin(base)
                cosState[c] = cos(base)
            }
            for (i in 0 until lenX) {
                var s = 0.0
                for (c in 0 until n) {
                    val sc = sinState[c]
                    val cc = cosState[c]
                    s += sc * ampScratch[c]
                    val stepCos = stepCosState[c]
                    val stepSin = stepSinState[c]
                    cosState[c] = cc * stepCos - sc * stepSin
                    sinState[c] = sc * stepCos + cc * stepSin
                }
                out[i] = s
            }
            return
        }
        for (i in xDp.indices) {
            val x = xDp[i]
            var s = 0.0
            for (c in 0 until n) s += sin(x * k[c] + phase[c]) * ampScratch[c]
            out[i] = s
        }
    }
}

/**
 * 相位旋转递推的公共前置条件。
 *
 * 递推 `cos(φ + iΔ)` 只在采样点严格等距时成立。生产调用点传入的都是
 * `uGrid`（`(i − (N−1)/2)·DX_DP`）或它加一个常量平移，天然等距；这里做一次
 * O(1) 校验，任何未来传入非均匀网格的调用点都会自动落回逐点直接求值，
 * 不会静默算错。
 */
internal object FableSolWaveRecurrence {
    /**
     * 容差按首步长取相对值。生产网格是 `(i − (N−1)/2)·DX_DP` 再加常量平移，相邻差
     * 的浮点误差约 1.7e-14 相对，留足余量；而 216 步递推在 1e-12 相对失配下的
     * 相位漂移只有约 1e-10 弧度，远低于下游 float32 的 6e-8。
     */
    private const val RELATIVE_TOLERANCE = 1e-12

    /**
     * 全量扫描而非抽样：均值与中点抽查会漏掉中段的单点扭结（例如整体等距、
     * 只有第 40 点被推移），那种网格用递推会静默算错。216 次比较相对于本次
     * 省下的三万余次 libm 调用可以忽略。
     */
    fun isUniform(xDp: DoubleArray, lenX: Int): Boolean {
        if (lenX < 2) return false
        val firstStep = xDp[1] - xDp[0]
        if (firstStep == 0.0 || !firstStep.isFinite()) return false
        val tolerance = abs(firstStep) * RELATIVE_TOLERANCE
        for (i in 2 until lenX) {
            if (abs((xDp[i] - xDp[i - 1]) - firstStep) > tolerance) return false
        }
        return true
    }

    /** [scanSteps] 的输出；由调用方持有并复用，热路径零分配、天然线程隔离。 */
    internal class StepScan {
        /** 与 `min_{i≥1}(xDp[i] − xDp[i−1])` 同义；`lenX < 2` 时为 [Double.MAX_VALUE]。 */
        @JvmField var minimumStep = Double.MAX_VALUE
        /** 与 [isUniform] 同判定。 */
        @JvmField var uniform = false
    }

    /**
     * 一趟同时求最小步长与均匀性。判定式与容差与 [isUniform] 逐字相同，只是不再
     * 提前返回——最小步长本来就要扫完整段，两趟合一没有额外代价。
     */
    fun scanSteps(xDp: DoubleArray, lenX: Int, into: StepScan) {
        if (lenX < 2) {
            into.minimumStep = Double.MAX_VALUE
            into.uniform = false
            return
        }
        val firstStep = xDp[1] - xDp[0]
        var uniform = firstStep != 0.0 && firstStep.isFinite()
        val tolerance = abs(firstStep) * RELATIVE_TOLERANCE
        // 起点保持 MAX_VALUE 再比一次首步：NaN 步长在 `<` 下恒为 false，与旧的
        // 「MAX_VALUE 起步、i=1 开始扫」逐位同结果（NaN 步长被跳过而非污染最小值）。
        var minimum = Double.MAX_VALUE
        if (firstStep < minimum) minimum = firstStep
        for (i in 2 until lenX) {
            val step = xDp[i] - xDp[i - 1]
            if (step < minimum) minimum = step
            if (uniform && abs(step - firstStep) > tolerance) uniform = false
        }
        into.minimumStep = minimum
        into.uniform = uniform
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
    /** 每个尺度组的振幅上限（陡峭度红线）；由 [retuneDerived] 维护。 */
    @JvmField val bandCeilingDp = DoubleArray(3)
    /** 每个尺度组载波的相速度 dp/s；空间能量包络必须以它输运。 */
    @JvmField val bandPhaseSpeedDps = DoubleArray(3)
    private val kScratch = DoubleArray(6)
    private val weightScratch = DoubleArray(6)
    /** 测试专用：强制走逐点直接求值分支，用于与递推路径做逐点对照。 */
    internal var forceDirectEvaluationForTest = false
    private val heroSin = DoubleArray(6)
    private val heroCos = DoubleArray(6)
    private val heroStepSin = DoubleArray(6)
    private val heroStepCos = DoubleArray(6)
    // 步进旋转只依赖 kScratch[m]·dx，而 kScratch 只由 baseLen 与不可变的 lenMult 决定；
    // baseLen 与 dx 都未变时跨帧复用。NaN 初值保证首帧必算。
    private var stepCacheDx = Double.NaN
    private var stepCacheBaseLen = Double.NaN
    private val stepScan = FableSolWaveRecurrence.StepScan()
    private var profileLagScratch = DoubleArray(FableSolSpec.N_POINTS)
    private var displacementScratch = DoubleArray(FableSolSpec.N_POINTS)
    private var gradientScratch = DoubleArray(FableSolSpec.N_POINTS)
    private var advectedXScratch = DoubleArray(FableSolSpec.N_POINTS)

    init {
        val rng = FableSolRng(seed)
        val near = max(0.0, 1.0 - 4.0 * depth01)   // 层0=1.0、层1=0.5、层2起=0
        val base = rng.uniform(0.90, 1.12) * (1.0 + 0.18 * depth01 + 0.30 * near)
        // 2026-07-21 收窄谱宽：旧表 2.20~0.50 跨 4.4 倍波长，相速度差 2.1 倍，
        // 六条模态的相对相位每秒漂 0.55rad——即使音频完全不变，可见剖面每 0.1s
        // 仍有 10% 的改形残差，这正是"浪自己变来变去"的主体。收到 2.9 倍跨度后
        // 残差降到 8%，同时因为能量更集中，可见起伏 RMS 反而从 8.2 涨到 9.6dp。
        // 六个数值仍互不成小整数比（最接近的 1.84/0.63≈2.92），避免拍频。
        val mult = doubleArrayOf(1.84, 1.42, 1.19, 0.96, 0.79, 0.63)
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
        retuneDerived()
    }

    /**
     * 随基准波长变化的两张逐尺度表（对应 Python `HeroWave._retune_derived`）。
     *
     * 组 j 的两个模态是 `lenMult[2j]`（长）与 `lenMult[2j+1]`（短）。
     *
     * * [bandCeilingDp]：振幅上限，由该组最短模态的波长定——越短的浪越矮。
     * * [bandPhaseSpeedDps]：该组载波的相速度 `c=sqrt(g/k)`，按权重 0.58/0.42
     *   加权。空间能量包络必须以它输运，否则包络会从自己的波峰上滑过去，
     *   屏幕里的浪就会原地长高变矮。
     */
    private fun retuneDerived() {
        for (j in 0 until 3) {
            val longLen = baseLen * lenMult[2 * j]
            val shortLen = baseLen * lenMult[2 * j + 1]
            bandCeilingDp[j] = MAX_HEIGHT_OVER_LENGTH * shortLen / 2.0
            bandPhaseSpeedDps[j] =
                0.58 * sqrt(GRAVITY_DP_S2 * longLen / TWO_PI) +
                    0.42 * sqrt(GRAVITY_DP_S2 * shortLen / TWO_PI)
        }
    }

    fun retune(baseLenDp: Double) {
        val next = max(baseLenDp, 48.0)
        if (next == baseLen) return
        baseLen = next
        retuneDerived()
    }

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
        val out = DoubleArray(xDp.size)
        sampleInto(xDp, ampField3, t, breathDepth, meanMask, roughness01, out)
        return out
    }

    /** Simulation 热路径的无分配采样入口；scratch 由每个 HeroWave 实例持有并复用。 */
    fun sampleInto(
        xDp: DoubleArray,
        ampField3: Array<DoubleArray>,
        t: Double,
        breathDepth: Double,
        meanMask: BooleanArray?,
        roughness01: Double,
        out: DoubleArray
    ) {
        val lenX = xDp.size
        require(lenX > 0 && out.size >= lenX)
        ensurePointCapacity(lenX)
        var maxAmp = 0.0
        for (band in 0 until 3) {
            val field = ampField3[band]
            for (i in 0 until lenX) if (field[i] > maxAmp) maxAmp = field[i]
        }
        if (maxAmp < 0.05) {
            for (i in 0 until lenX) out[i] = 0.0
            return
        }
        for (m in 0 until 6) {
            kScratch[m] = TWO_PI / (baseLen * lenMult[m])
            weightScratch[m] = weight[m] * (1.0 + breathDepth * 0.20 *
                sin(TWO_PI * t / breathT[m] + breathPhi[m]))
        }
        val profLag = profileLagScratch
        val disp = displacementScratch
        // 均匀性判定与「最小采样间隔」原本是对 xDp 的两趟独立全扫描；合成一趟，
        // 两个结果的定义与逐位取值都不变（最小值仍覆盖 i=1..lenX-1）。
        FableSolWaveRecurrence.scanSteps(xDp, lenX, stepScan)
        if (!forceDirectEvaluationForTest && lenX >= 2 && stepScan.uniform) {
            // 等距网格上把 sin/cos 换成相位旋转递推：每帧 libm 从
            // 9 层 × 216 点 × 6 模态 × 2 = 23328 次降到 6 × 2 = 12 次。
            // 六个模态的 (s, c) 交错推进，累加顺序仍是 m = 0→5，与逐点直接求值
            // 的求和次序和乘法结合序完全一致，只有递推自身约 1e-14 的漂移。
            // 附带好处：`phase[m]` 在 advance() 里无界累加，长时间运行后会进
            // libm 的 Payne-Hanek 慢路径，递推版天然免疫。
            val dx = xDp[1] - xDp[0]
            val x0 = xDp[0]
            if (dx != stepCacheDx || baseLen != stepCacheBaseLen) {
                for (m in 0 until 6) {
                    val stepPhase = kScratch[m] * dx
                    heroStepSin[m] = sin(stepPhase)
                    heroStepCos[m] = cos(stepPhase)
                }
                stepCacheDx = dx
                stepCacheBaseLen = baseLen
            }
            for (m in 0 until 6) {
                val base = x0 * kScratch[m] + phase[m]
                heroSin[m] = sin(base)
                heroCos[m] = cos(base)
            }
            for (i in 0 until lenX) {
                var sinSum = 0.0
                var cosSum = 0.0
                for (m in 0 until 6) {
                    val s = heroSin[m]
                    val c = heroCos[m]
                    val localAmp = ampField3[group[m]][i]
                    sinSum += s * weightScratch[m] * localAmp
                    cosSum += c * weightScratch[m] * localAmp
                    val stepCos = heroStepCos[m]
                    val stepSin = heroStepSin[m]
                    heroCos[m] = c * stepCos - s * stepSin
                    heroSin[m] = s * stepCos + c * stepSin
                }
                profLag[i] = sinSum
                disp[i] = cosSum
            }
        } else {
            for (i in 0 until lenX) {
                val x = xDp[i]
                var sinSum = 0.0; var cosSum = 0.0
                for (m in 0 until 6) {
                    val ph = x * kScratch[m] + phase[m]
                    val localAmp = ampField3[group[m]][i]
                    sinSum += sin(ph) * weightScratch[m] * localAmp
                    cosSum += cos(ph) * weightScratch[m] * localAmp
                }
                profLag[i] = sinSum
                disp[i] = cosSum
            }
        }
        // 有界 Gerstner 水平轨道位移：采样点向波峰聚集，形成窄峰宽谷
        val rough = roughness01.coerceIn(0.0, 1.0)
        val near = max(0.0, 1.0 - 3.0 * depth01)
        val chop = 0.10 + 0.10 * rough + 0.04 * near
        val dsign = -travelDir * chop
        for (i in 0 until lenX) disp[i] = dsign * disp[i]
        val dx = if (lenX >= 2) xDp[1] - xDp[0] else 1.0
        val grad = gradientScratch
        FableSolMath.gradientInto(disp, lenX, dx, grad)
        var maxGrad = 0.0
        for (i in 0 until lenX) { val a = abs(grad[i]); if (a > maxGrad) maxGrad = a }
        if (maxGrad > 0.52) { val f = 0.52 / maxGrad; for (i in 0 until lenX) disp[i] *= f }
        // 数值保险：强制最小采样间隔，随后重采样回单调 heightfield
        val minStep = max(stepScan.minimumStep * 0.24, 1e-4)
        val adv = advectedXScratch
        var run = -Double.MAX_VALUE
        for (i in 0 until lenX) {
            val tmp = (xDp[i] + disp[i]) - i * minStep
            if (tmp > run) run = tmp
            adv[i] = run + i * minStep
        }
        // xDp 与 adv 均严格递增，用单调游标完成 np.interp 等价重采样，不创建临时数组。
        var segment = 0
        for (i in 0 until lenX) {
            val query = xDp[i]
            out[i] = when {
                query <= adv[0] -> profLag[0]
                query >= adv[lenX - 1] -> profLag[lenX - 1]
                else -> {
                    while (segment + 1 < lenX - 1 && adv[segment + 1] < query) segment++
                    val fraction = (query - adv[segment]) /
                        (adv[segment + 1] - adv[segment])
                    profLag[segment] + (profLag[segment + 1] - profLag[segment]) * fraction
                }
            }
        }
        var m = 0.0
        if (meanMask != null) {
            var cnt = 0
            for (i in 0 until lenX) if (meanMask[i]) { m += out[i]; cnt++ }
            m = if (cnt > 0) m / cnt else 0.0
        } else {
            for (i in 0 until lenX) m += out[i]
            m /= lenX
        }
        for (i in 0 until lenX) out[i] -= m
    }

    private fun ensurePointCapacity(pointCount: Int) {
        if (profileLagScratch.size >= pointCount) return
        profileLagScratch = DoubleArray(pointCount)
        displacementScratch = DoubleArray(pointCount)
        gradientScratch = DoubleArray(pointCount)
        advectedXScratch = DoubleArray(pointCount)
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
