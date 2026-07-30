package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 从完整场景 CFD 生成 T/UWA 005.1 Base Parameters 与两段 3Spline。
 *
 * HDR10+ 与 HDR Vivid 在这里共享的是「最终线性 BT.2020 场景 → 内容感知目标映射」，而不是码流
 * 字段。目标映射仍由现有 HDR10+ 九锚点曲线给出；本类把它重新拟合到 HDR Vivid 的有理幂函数
 * 曲线族，并且只对最终量化后的参数求值和选优。
 */
internal object FableSolHdrVividCurve {

    fun parameters(
        stats: FableSolHdr10PlusStats,
        targetNits: Double,
        highlightStartPercent: Int
    ): FableSolHdrVividToneMapping {
        require(targetNits.isFinite() && targetNits > 0.0)
        val histogram = checkNotNull(stats.histogram) {
            "HDR Vivid tone mapping requires the complete scene histogram"
        }
        val sourceLinear = (
            stats.maxScl.maxOrNull()
                ?: histogram.percentile(100.0)
            ).coerceIn(0.0, 1.0)
        val sourcePq = FableSolExportHdr10PlusMetadata.linearToPq(sourceLinear)
        val targetPq = FableSolExportHdr10PlusMetadata.linearToPq(
            (targetNits / FableSolExportTransfer.PQ_MAX_NITS).coerceIn(0.0, 1.0)
        )
        val compressing = sourcePq > targetPq + PQ_EPSILON
        val base = if (compressing) {
            fitBase(
                stats = stats,
                sourceLinear = sourceLinear,
                sourcePq = sourcePq,
                targetPq = targetPq,
                targetNits = targetNits,
                highlightStartPercent = highlightStartPercent
            )
        } else {
            identityBase()
        }
        return FableSolHdrVividToneMapping(
            targetedSystemDisplayMaximumLuminancePq = targetCode(targetPq),
            base = base,
            splines = splineParameters(
                histogram = histogram,
                sourcePq = sourcePq,
                enableCorrection = compressing
            )
        )
    }

    /**
     * 直接拟合最终接收端会使用的量化 Base Curve。`base_param_Delta_mode=3` 表示目标显示峰值
     * 不匹配时也直接使用这组参数，不再让接收端按其它 Delta 模式二次改写。
     */
    private fun fitBase(
        stats: FableSolHdr10PlusStats,
        sourceLinear: Double,
        sourcePq: Double,
        targetPq: Double,
        targetNits: Double,
        highlightStartPercent: Int
    ): FableSolHdrVividBaseCurve {
        if (sourcePq <= PQ_EPSILON || sourceLinear <= 0.0) return identityBase()

        val targetShape = try {
            FableSolExportHdr10PlusCurve(
                sourcePeakNits =
                    sourceLinear * FableSolExportTransfer.PQ_MAX_NITS,
                targetNits = targetNits,
                highlightStartPercent = highlightStartPercent
            ).shapeForScene(stats)
        } catch (ignored: FableSolExportHdr10PlusCurve.Unsolvable) {
            null
        }
        val input = DoubleArray(FIT_SAMPLES) { index ->
            sourcePq * index / (FIT_SAMPLES - 1).toDouble()
        }
        val desired = DoubleArray(FIT_SAMPLES) { index ->
            val x = input[index]
            if (index == 0) {
                0.0
            } else if (targetShape != null) {
                val normalizedLinear =
                    FableSolExportHdr10PlusMetadata.pqToLinear(x) / sourceLinear
                val mappedLinear = targetShape.evaluate(normalizedLinear) *
                    targetNits / FableSolExportTransfer.PQ_MAX_NITS
                FableSolExportHdr10PlusMetadata.linearToPq(mappedLinear)
            } else {
                // 极端 S/T 超出 HDR10+ 九锚点可行域时，仍给 HDR Vivid 一条端点严格受控的
                // 单调 PQ 肩部；这不是格式降级，也不会发布一条被截断的 HDR10+ 曲线。
                targetPq * (x / sourcePq).coerceIn(0.0, 1.0).pow(FALLBACK_POWER)
            }
        }
        val weights = fittingWeights(checkNotNull(stats.histogram), input)

        var best: FableSolHdrVividBaseCurve? = null
        var bestError = Double.POSITIVE_INFINITY
        for (mpValue in MP_CANDIDATES) {
            val mpCode = quantizeMp(mpValue)
            val mp = mpCode * 10.0 / MP_SCALE
            for (mmCode in MM_CANDIDATES) {
                val mm = mmCode / 10.0
                for (mnCode in MN_CANDIDATES) {
                    val mn = mnCode / 10.0
                    val core = DoubleArray(FIT_SAMPLES) { index ->
                        baseCore(input[index], mp, mm, mn)
                    }
                    val endpointCore = core.last()
                    if (!endpointCore.isFinite() || endpointCore <= 0.0) continue

                    var numerator = 0.0
                    var denominator = 0.0
                    for (index in core.indices) {
                        numerator += weights[index] * core[index] * desired[index]
                        denominator += weights[index] * core[index] * core[index]
                    }
                    val leastSquaresMa = if (denominator > 0.0) {
                        numerator / denominator
                    } else {
                        0.0
                    }
                    val endpointMa = targetPq / endpointCore
                    val maCandidates = linkedSetOf<Int>()
                    for (value in listOf(leastSquaresMa, endpointMa)) {
                        val center = (value * MA_SCALE).roundToInt()
                        for (offset in -1..1) {
                            maCandidates += (center + offset).coerceIn(1, MA_SCALE)
                        }
                    }
                    for (maCode in maCandidates) {
                        val ma = maCode / MA_SCALE.toDouble()
                        var error = 0.0
                        for (index in core.indices) {
                            val delta = ma * core[index] - desired[index]
                            error += weights[index] * delta * delta
                        }
                        val endpointDelta = ma * endpointCore - targetPq
                        error += ENDPOINT_WEIGHT * endpointDelta * endpointDelta
                        if (error < bestError) {
                            bestError = error
                            best = FableSolHdrVividBaseCurve(
                                mP = mpCode,
                                mM = mmCode,
                                mA = maCode,
                                mB = 0,
                                mN = mnCode,
                                k1 = 1,
                                k2 = 1,
                                k3 = 1,
                                deltaMode = 3,
                                delta = 0
                            )
                        }
                    }
                }
            }
        }
        val fitted = best ?: endpointBase(sourcePq, targetPq)
        return if (passesGate(fitted, sourcePq, targetPq)) {
            fitted
        } else {
            endpointBase(sourcePq, targetPq)
        }
    }

    /**
     * T/UWA 005.1 附录 A.3 推荐的两段 3Spline：
     *
     * - 低亮段以 0.15、0.35 PQ 为边界；
     * - 高亮段以 U=6 从 0.35 到场景峰值取最后两档，并按像素密度向下扩展；
     * - 两段都在中间 1/2 的八等分候选中选择像素最少的区间中心作为 TH2。
     */
    private fun splineParameters(
        histogram: FableSolExportHdr10PlusHistogram,
        sourcePq: Double,
        enableCorrection: Boolean
    ): List<FableSolHdrVividSpline> {
        val boundedSource = sourcePq.coerceIn(0.0, 1.0)
        val lowEnd = minOf(LOW_END_PQ, boundedSource)
        val lowStart = if (lowEnd >= LOW_END_PQ) {
            LOW_START_PQ
        } else {
            lowEnd * LOW_START_FRACTION
        }
        val low = optimizeSpline(
            histogram = histogram,
            low = lowStart,
            high = lowEnd,
            enableCorrection = enableCorrection
        )

        val highBase = lowEnd + (boundedSource - lowEnd) *
            (SPLINE_U - 2.0) / SPLINE_U
        val highRatio = histogram.massBetween(
            FableSolExportHdr10PlusMetadata.pqToLinear(highBase),
            FableSolExportHdr10PlusMetadata.pqToLinear(boundedSource)
        ).toDouble() / histogram.pixelCount.coerceAtLeast(1L)
        val wholeRatio = if (boundedSource > 0.0) {
            (boundedSource - highBase) / boundedSource
        } else {
            0.0
        }
        val densityExpansion = if (wholeRatio > 0.0) {
            sqrt((highRatio / wholeRatio).coerceAtLeast(0.0)) *
                (boundedSource - lowEnd) / SPLINE_U
        } else {
            0.0
        }
        val highStart = (highBase - densityExpansion)
            .coerceIn(lowEnd, boundedSource)
        val high = optimizeSpline(
            histogram = histogram,
            low = highStart,
            high = boundedSource,
            enableCorrection = enableCorrection
        )
        return listOf(low, high)
    }

    private fun optimizeSpline(
        histogram: FableSolExportHdr10PlusHistogram,
        low: Double,
        high: Double,
        enableCorrection: Boolean
    ): FableSolHdrVividSpline {
        val th1 = low.coerceIn(0.0, 1.0)
        val th3 = high.coerceIn(th1, minOf(1.0, th1 + MAX_SPLINE_SPAN))
        val span = th3 - th1
        var bestInterval = SPLINE_INTERVALS / 2
        var bestMass = Long.MAX_VALUE
        if (span > 0.0) {
            for (interval in SPLINE_INTERVALS / 4..SPLINE_INTERVALS * 3 / 4) {
                val intervalLow = th1 + span * interval / SPLINE_INTERVALS
                val intervalHigh = th1 + span * (interval + 1) / SPLINE_INTERVALS
                val mass = histogram.massBetween(
                    FableSolExportHdr10PlusMetadata.pqToLinear(intervalLow),
                    FableSolExportHdr10PlusMetadata.pqToLinear(
                        intervalHigh.coerceAtMost(th3)
                    )
                )
                if (mass < bestMass) {
                    bestMass = mass
                    bestInterval = interval
                }
            }
        }
        val th2 = if (span > 0.0) {
            (
                th1 + span * bestInterval / SPLINE_INTERVALS +
                    span / (SPLINE_INTERVALS * 2.0)
                ).coerceIn(th1, th3)
        } else {
            th1
        }
        val num1 = histogram.massBetween(
            FableSolExportHdr10PlusMetadata.pqToLinear(th1),
            FableSolExportHdr10PlusMetadata.pqToLinear(th2)
        )
        val num2 = histogram.massBetween(
            FableSolExportHdr10PlusMetadata.pqToLinear(th2),
            FableSolExportHdr10PlusMetadata.pqToLinear(th3)
        )
        var strength = 0.0
        if (enableCorrection && num1 < num2) strength -= 0.2
        if (enableCorrection && num1 <= Long.MAX_VALUE / 2 && num1 * 2 < num2) {
            strength -= 0.4
        }
        return FableSolHdrVividSpline(
            mode = 1,
            threshold = quantizePq(th1),
            delta1 = quantizeSplineDelta(th2 - th1),
            delta2 = quantizeSplineDelta(th3 - th2),
            strength = quantizeStrength(strength)
        )
    }

    private fun fittingWeights(
        histogram: FableSolExportHdr10PlusHistogram,
        inputPq: DoubleArray
    ): DoubleArray {
        val weights = DoubleArray(inputPq.size)
        val uniform = 1.0 / inputPq.size
        val pixels = histogram.pixelCount.coerceAtLeast(1L).toDouble()
        for (index in inputPq.indices) {
            val lowPq = if (index == 0) {
                0.0
            } else {
                (inputPq[index - 1] + inputPq[index]) * 0.5
            }
            val highPq = if (index == inputPq.lastIndex) {
                inputPq.last()
            } else {
                (inputPq[index] + inputPq[index + 1]) * 0.5
            }
            val content = histogram.massBetween(
                FableSolExportHdr10PlusMetadata.pqToLinear(lowPq),
                FableSolExportHdr10PlusMetadata.pqToLinear(highPq)
            ) / pixels
            weights[index] = UNIFORM_PRIOR * uniform +
                (1.0 - UNIFORM_PRIOR) * content
        }
        return weights
    }

    private fun endpointBase(
        sourcePq: Double,
        targetPq: Double
    ): FableSolHdrVividBaseCurve {
        val mpCode = quantizeMp(10.0)
        val core = baseCore(
            sourcePq,
            mp = mpCode * 10.0 / MP_SCALE,
            mm = 0.1,
            mn = 1.0
        )
        val maCode = (targetPq / core.coerceAtLeast(1e-9) * MA_SCALE)
            .roundToInt()
            .coerceIn(1, MA_SCALE)
        return FableSolHdrVividBaseCurve(
            mP = mpCode,
            mM = 1,
            mA = maCode,
            mB = 0,
            mN = 10,
            deltaMode = 3,
            delta = 0
        )
    }

    private fun identityBase(): FableSolHdrVividBaseCurve =
        FableSolHdrVividBaseCurve(
            mP = quantizeMp(1.0),
            mM = 10,
            mA = MA_SCALE,
            mB = 0,
            mN = 10,
            deltaMode = 3,
            delta = 0
        )

    private fun passesGate(
        base: FableSolHdrVividBaseCurve,
        sourcePq: Double,
        targetPq: Double
    ): Boolean {
        var previous = base.evaluate(0.0)
        for (index in 1..GATE_SAMPLES) {
            val current = base.evaluate(sourcePq * index / GATE_SAMPLES)
            if (!current.isFinite() || current + MONOTONIC_EPSILON < previous) return false
            previous = current
        }
        return kotlin.math.abs(base.evaluate(sourcePq) - targetPq) <= MAX_PEAK_ERROR_PQ
    }

    private fun baseCore(inputPq: Double, mp: Double, mm: Double, mn: Double): Double {
        if (inputPq <= 0.0 || mp <= 0.0 || mm <= 0.0 || mn <= 0.0) return 0.0
        val xn = inputPq.coerceIn(0.0, 1.0).pow(mn)
        val denominator = (mp - 1.0) * xn + 1.0
        if (denominator <= 0.0) return 0.0
        return (mp * xn / denominator).coerceAtLeast(0.0).pow(mm)
    }

    private fun quantizeMp(value: Double): Int =
        (value.coerceIn(0.0, 10.0) / 10.0 * MP_SCALE)
            .roundToInt()
            .coerceIn(1, MP_SCALE)

    private fun quantizePq(value: Double): Int =
        (value.coerceIn(0.0, 1.0) * PQ_SCALE).roundToInt()

    private fun quantizeSplineDelta(value: Double): Int =
        (value.coerceIn(0.0, MAX_SPLINE_DELTA) / MAX_SPLINE_DELTA * SPLINE_DELTA_SCALE)
            .roundToInt()

    private fun quantizeStrength(value: Double): Int =
        ((value.coerceIn(-1.0, 1.0) + 1.0) * STRENGTH_SCALE / 2.0)
            .roundToInt()

    private fun targetCode(targetPq: Double): Int {
        val code = quantizePq(targetPq)
        // 2080 在标准里保留为 SDR 目标；HDR 参考峰值若恰好落在相邻量化边界，向上一档。
        return if (code == SDR_TARGET_CODE) code + 1 else code
    }

    private const val FIT_SAMPLES = 41
    private const val GATE_SAMPLES = 64
    private const val MP_SCALE = 0x3FFF
    private const val MA_SCALE = 0x3FF
    private const val PQ_SCALE = 0xFFF
    private const val SPLINE_DELTA_SCALE = 0x3FF
    private const val STRENGTH_SCALE = 0xFF
    private const val SDR_TARGET_CODE = 2080
    private const val LOW_START_PQ = 0.15
    private const val LOW_END_PQ = 0.35
    private const val LOW_START_FRACTION = 0.45
    private const val SPLINE_U = 6.0
    private const val SPLINE_INTERVALS = 8
    private const val MAX_SPLINE_DELTA = 0.25
    private const val MAX_SPLINE_SPAN = MAX_SPLINE_DELTA * 2.0
    private const val UNIFORM_PRIOR = 0.5
    private const val ENDPOINT_WEIGHT = 12.0
    private const val FALLBACK_POWER = 0.82
    private const val PQ_EPSILON = 1e-6
    private const val MONOTONIC_EPSILON = 1e-10
    private const val MAX_PEAK_ERROR_PQ = 0.025

    private val MP_CANDIDATES = doubleArrayOf(
        1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0, 6.0, 7.5, 9.0, 10.0
    )
    private val MM_CANDIDATES = intArrayOf(
        24, 20, 16, 12, 10, 8, 6, 4, 3, 2, 30, 36, 45, 54, 63
    )
    private val MN_CANDIDATES = intArrayOf(10, 8, 12, 6, 15, 20)
}
