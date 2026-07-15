package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 只用于渲染网格的三次重建。物理网格仍保持 216 点，避免改变 CFL、传播速度与既有波形。
 */
internal object FableSolCubicResampler {

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
