package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 连续水面公平化与渲染三次重建。物理求解网格仍保持 216 点，避免改变 CFL、传播速度与既有波形。
 */
internal object FableSolCubicResampler {

    /**
     * 把等距物理采样解释为 uniform cubic B-spline 控制点。
     *
     * [values] 与 [derivatives] 是同一条 C2 曲线在节点处的精确 Hermite 表示；
     * 对称 1/6-4/6-1/6 模板保持行均值，并把网格级折角限制在局部凸包内。
     * 调用方提供并复用输出数组，稳态不分配。
     */
    fun fairCubicBsplineRow(
        controls: DoubleArray,
        values: DoubleArray,
        derivatives: DoubleArray,
        step: Double
    ) {
        require(controls.size >= 2)
        require(values.size == controls.size && derivatives.size == controls.size)
        val safeStep = step.coerceAtLeast(1e-9)
        val last = controls.lastIndex
        if (last == 1) {
            val derivative = (controls[1] - controls[0]) / safeStep
            values[0] = controls[0]
            values[1] = controls[1]
            derivatives[0] = derivative
            derivatives[1] = derivative
            return
        }

        fairCubicBsplineRange(controls, values, derivatives, safeStep, 0, last)
    }

    /**
     * 只对 `[lo, hi]` 求值的 fair 化。
     *
     * 端点式（index 0 与 last）必须**继续绑定真实数组端**而不是窗口端——倾角大时
     * `lo` 真的可能落到 0，若把窗口端当数组端处理会在窗口边界造出一段不同曲率的
     * 曲线，表现为可见接缝。窗口内部一律走内点模板。
     */
    fun fairCubicBsplineRange(
        controls: DoubleArray,
        values: DoubleArray,
        derivatives: DoubleArray,
        step: Double,
        lo: Int,
        hi: Int
    ) {
        val safeStep = step.coerceAtLeast(1e-9)
        val last = controls.lastIndex
        // 两个除数都是整行的循环不变量。ART 只在倒数可精确表示（2 的幂）时才把
        // 除法降级成乘法，6.0 与 2·step 都不满足，因此原式每个点要付两次双精度除法。
        val inverseSix = 1.0 / 6.0
        val inverseTwoStep = 1.0 / (2.0 * safeStep)
        val from = lo.coerceIn(0, last)
        val to = hi.coerceIn(from, last)

        if (from == 0) {
            values[0] = (5.0 * controls[0] + controls[1]) * inverseSix
            derivatives[0] = (controls[1] - controls[0]) * inverseTwoStep
        }
        for (index in max(from, 1)..min(to, last - 1)) {
            values[index] = (
                controls[index - 1] + 4.0 * controls[index] + controls[index + 1]
                ) * inverseSix
            derivatives[index] = (
                controls[index + 1] - controls[index - 1]
                ) * inverseTwoStep
        }
        if (to == last) {
            values[last] = (controls[last - 1] + 5.0 * controls[last]) * inverseSix
            derivatives[last] = (controls[last] - controls[last - 1]) * inverseTwoStep
        }
    }

    /** 高阶软饱和；与 Python `soft_limit(..., order=6)` 同式且没有硬平台。 */
    fun softLimit(value: Double, limit: Double): Double {
        val scale = limit.coerceAtLeast(1e-9)
        val ratio = value / scale
        val ratio2 = ratio * ratio
        val ratio6 = ratio2 * ratio2 * ratio2
        // 恒等区直接返回，保证零值与极小值逐位不变。
        if (ratio6 == 0.0) return value
        if (ratio6 <= FAST_PATH_MAX_RATIO6) {
            // (1+u)^(-1/6) 在 u ≤ 1e-3 上的四项二项展开，截断相对误差 < 5.6e-14，
            // 远低于下游转成 float 的精度。避开一次开方与 Halley 迭代里的三次除法；
            // |value| ≤ limit·(1e-3)^(1/6) = 0.3162·limit，实测命中率约 85~95%。
            return value * (1.0 - ratio6 * ONE_SIXTH +
                ratio6 * ratio6 * SEVEN_SEVENTY_SECONDS -
                ratio6 * ratio6 * ratio6 * NINETY_ONE_TWELVE_NINETY_SIXTHS)
        }
        return value / sqrt(cbrtAtLeastOne(1.0 + ratio6))
    }

    /**
     * `x ∈ [1, ∞)` 的立方根，纯算术实现。
     *
     * `softLimit` 每帧被调用 41904 次（97 行 × 216 点 × X/Z 两路）。桌面 HotSpot 把
     * `Math.cbrt` 当内联函数，代价接近零；Android ART 上它是走 native 的 libm 调用，
     * 在 debuggable 构建里每次约 1µs——2026-07-21 真机实测 `sample()` 达 44ms/帧，
     * 其中绝大部分就是这一项（桌面同一函数只要 0.26ms，相差 170 倍）。
     *
     * 位级初值把指数除以 3（仅对正规正数有效，`x ≥ 1` 恒满足），再做三次 Halley
     * 迭代（三阶收敛）。相对误差在 1e-15 量级，即约 1 ulp，与 `Math.cbrt` 的差别
     * 远低于下游转成 float 的精度，画面无差异。
     */
    internal fun cbrtAtLeastOne(x: Double): Double {
        val bits = java.lang.Double.doubleToRawLongBits(x)
        var y = java.lang.Double.longBitsToDouble(bits / 3L + CBRT_SEED_BIAS)
        // y ← y·(y³ + 2x) / (2y³ + x)
        var cube = y * y * y
        y *= (cube + 2.0 * x) / (2.0 * cube + x)
        cube = y * y * y
        y *= (cube + 2.0 * x) / (2.0 * cube + x)
        cube = y * y * y
        y *= (cube + 2.0 * x) / (2.0 * cube + x)
        return y
    }

    /** `(2/3)·2^52·(1023 + 0.0450466)`：位级立方根初值偏置，Halley 迭代随后收敛到 ~1ulp。 */
    private const val CBRT_SEED_BIAS = 0x2A9F76253119D328L

    // 二项展开快速路的阈值与系数。阈值不能放宽到 1e-2：`FableSolCurveFairnessTest`
    // 以 1e-12 绝对容差探测 value ∈ {0, ±3, ±10, ±36}，v=3 恰好落在快速路
    // （u = 7.29e-4，绝对误差 4.8e-14），放宽即失去这层保护。
    private const val FAST_PATH_MAX_RATIO6 = 1e-3
    private const val ONE_SIXTH = 1.0 / 6.0
    private const val SEVEN_SEVENTY_SECONDS = 7.0 / 72.0
    private const val NINETY_ONE_TWELVE_NINETY_SIXTHS = 91.0 / 1296.0

    /**
     * 一段原始步长违反单值高度场约束时，返回整行朝基线收回所需的最大比例。
     * 每行最终只应用一个最小比例，因此不会把局部翻折替换成平台或阶梯。
     */
    fun monotoneBlendBound(
        rawStep: Double,
        baselineStep: Double,
        minimumSpacingRatio: Double
    ): Double {
        require(baselineStep > 0.0)
        val ratio = minimumSpacingRatio.coerceIn(0.0, 0.95)
        val floor = ratio * baselineStep
        if (rawStep >= floor) return 1.0
        val denominator = baselineStep - rawStep
        if (denominator <= 0.0) return 1.0
        return ((baselineStep - floor) / denominator).coerceIn(0.0, 1.0)
    }

    /**
     * 对世界空间轨道执行一次整行单调修复；返回实际使用的全局比例。
     *
     * [monotoneBlendBound] 在 `baselineStep` 与 `minimumSpacingRatio` 固定时对
     * `rawStep` **单调不减**（`rawStep ≥ floor` 恒为 1；低于 floor 后分母
     * `baselineStep − rawStep` 随 rawStep 减小而增大，商随之减小）。因此
     *
     *     min_j bound(rawStep_j) ≡ bound(min_j rawStep_j)
     *
     * 在 IEEE-754 下逐位成立——右边就是把同一个函数作用在同一个 double 上。
     * 于是整行 215 次调用降为一次，热路径只剩一趟求最小值。
     */
    fun repairOrbitRowMonotone(
        orbit: DoubleArray,
        baselineStep: Double,
        minimumSpacingRatio: Double,
        lo: Int = 0,
        hi: Int = orbit.lastIndex
    ): Double {
        require(orbit.size >= 2)
        val from = lo.coerceIn(0, orbit.lastIndex)
        val to = hi.coerceIn(from, orbit.lastIndex)
        var minimumRawStep = Double.MAX_VALUE
        for (index in from until to) {
            // 表达式原样保留：改成先求 orbit 差再加 baselineStep 会改变舍入。
            val rawStep = baselineStep + orbit[index + 1] - orbit[index]
            if (rawStep < minimumRawStep) minimumRawStep = rawStep
        }
        val scale = min(
            1.0,
            monotoneBlendBound(minimumRawStep, baselineStep, minimumSpacingRatio)
        )
        if (scale < 1.0) {
            for (index in from..to) orbit[index] *= scale
        }
        return scale
    }

    fun hermiteValue(
        start: Double,
        end: Double,
        startDerivative: Double,
        endDerivative: Double,
        fraction: Double,
        step: Double
    ): Double {
        val t = fraction.coerceIn(0.0, 1.0)
        val t2 = t * t
        val t3 = t2 * t
        val startTangent = startDerivative * step
        val endTangent = endDerivative * step
        return (2.0 * t3 - 3.0 * t2 + 1.0) * start +
            (t3 - 2.0 * t2 + t) * startTangent +
            (-2.0 * t3 + 3.0 * t2) * end +
            (t3 - t2) * endTangent
    }

    fun hermiteDerivative(
        start: Double,
        end: Double,
        startDerivative: Double,
        endDerivative: Double,
        fraction: Double,
        step: Double
    ): Double {
        val t = fraction.coerceIn(0.0, 1.0)
        val t2 = t * t
        val startTangent = startDerivative * step
        val endTangent = endDerivative * step
        val derivativeT = (6.0 * t2 - 6.0 * t) * start +
            (3.0 * t2 - 4.0 * t + 1.0) * startTangent +
            (-6.0 * t2 + 6.0 * t) * end +
            (3.0 * t2 - 2.0 * t) * endTangent
        return derivativeT / step.coerceAtLeast(1e-9)
    }

    fun catmullRom(
        before: Double,
        start: Double,
        end: Double,
        after: Double,
        fraction: Double
    ): Double {
        val t = fraction.coerceIn(0.0, 1.0)
        val t2 = t * t
        val t3 = t2 * t
        return 0.5 * (
            2.0 * start +
                (-before + end) * t +
                (2.0 * before - 5.0 * start + 4.0 * end - after) * t2 +
                (-before + 3.0 * start - 3.0 * end + after) * t3
            )
    }

    fun catmullRomDerivative(
        before: Double,
        start: Double,
        end: Double,
        after: Double,
        fraction: Double,
        step: Double
    ): Double {
        val t = fraction.coerceIn(0.0, 1.0)
        val derivativeT = 0.5 * (
            (-before + end) +
                2.0 * (2.0 * before - 5.0 * start + 4.0 * end - after) * t +
                3.0 * (-before + 3.0 * start - 3.0 * end + after) * t * t
            )
        return derivativeT / step.coerceAtLeast(1e-9)
    }
}

/** 每帧只计算一次的 Catmull–Rom 系数表，避免在 97 行顶点中重复求幂。 */
internal class FableSolCatmullRomWeightTable(capacity: Int) {
    val w0 = DoubleArray(capacity)
    val w1 = DoubleArray(capacity)
    val w2 = DoubleArray(capacity)
    val w3 = DoubleArray(capacity)
    val dw0 = DoubleArray(capacity)
    val dw1 = DoubleArray(capacity)
    val dw2 = DoubleArray(capacity)
    val dw3 = DoubleArray(capacity)

    fun update(index: Int, fraction: Double, step: Double) {
        val t = fraction.coerceIn(0.0, 1.0)
        val t2 = t * t
        val t3 = t2 * t
        w0[index] = -0.5 * t + t2 - 0.5 * t3
        w1[index] = 1.0 - 2.5 * t2 + 1.5 * t3
        w2[index] = 0.5 * t + 2.0 * t2 - 1.5 * t3
        w3[index] = -0.5 * t2 + 0.5 * t3
        val inverseStep = 1.0 / step.coerceAtLeast(1e-9)
        dw0[index] = (-0.5 + 2.0 * t - 1.5 * t2) * inverseStep
        dw1[index] = (-5.0 * t + 4.5 * t2) * inverseStep
        dw2[index] = (0.5 + 4.0 * t - 4.5 * t2) * inverseStep
        dw3[index] = (-t + 1.5 * t2) * inverseStep
    }
}

/** 每帧只计算一次的 Hermite 系数表；导数系数直接以物理 X 为自变量。 */
internal class FableSolHermiteWeightTable(capacity: Int) {
    val h00 = DoubleArray(capacity)
    val h10 = DoubleArray(capacity)
    val h01 = DoubleArray(capacity)
    val h11 = DoubleArray(capacity)
    val dh00 = DoubleArray(capacity)
    val dh10 = DoubleArray(capacity)
    val dh01 = DoubleArray(capacity)
    val dh11 = DoubleArray(capacity)

    fun update(index: Int, fraction: Double, step: Double) {
        val t = fraction.coerceIn(0.0, 1.0)
        val t2 = t * t
        val t3 = t2 * t
        h00[index] = 2.0 * t3 - 3.0 * t2 + 1.0
        h10[index] = (t3 - 2.0 * t2 + t) * step
        h01[index] = -2.0 * t3 + 3.0 * t2
        h11[index] = (t3 - t2) * step
        val inverseStep = 1.0 / step.coerceAtLeast(1e-9)
        dh00[index] = (6.0 * t2 - 6.0 * t) * inverseStep
        dh10[index] = 3.0 * t2 - 4.0 * t + 1.0
        dh01[index] = (-6.0 * t2 + 6.0 * t) * inverseStep
        dh11[index] = 3.0 * t2 - 2.0 * t
    }
}
