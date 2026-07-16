package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

class FableSolDepthBaselineTest {

    @Test
    fun depthBaselinePassesThroughEveryLayerAnchor() {
        val anchors = realisticLayerMeans()
        val tangents = DoubleArray(anchors.size)
        FableSolDepthBaseline.updateTangents(anchors, tangents)

        for (layer in anchors.indices) {
            assertEquals(
                "layer=$layer",
                anchors[layer],
                FableSolDepthBaseline.value(anchors, tangents, layer.toDouble()),
                0.0
            )
        }
    }

    @Test
    fun depthBaselineHasOneSharedDerivativeAtEveryInteriorAnchor() {
        val anchors = doubleArrayOf(3.0, 12.0, 7.0, 31.0, -2.0, 15.0, 30.0, 9.0, 24.0)
        val tangents = DoubleArray(anchors.size)
        FableSolDepthBaseline.updateTangents(anchors, tangents)
        val epsilon = 1e-7

        for (layer in 1 until anchors.lastIndex) {
            val atAnchor = FableSolDepthBaseline.value(anchors, tangents, layer.toDouble())
            val leftDerivative = (atAnchor - FableSolDepthBaseline.value(
                anchors, tangents, layer - epsilon
            )) / epsilon
            val rightDerivative = (FableSolDepthBaseline.value(
                anchors, tangents, layer + epsilon
            ) - atAnchor) / epsilon
            assertEquals("layer=$layer", leftDerivative, rightDerivative, 1e-4)
        }
    }

    @Test
    fun depthBaselineMatchesTheShapePreservingPchipReference() {
        val anchors = doubleArrayOf(0.0, 1.0, 3.0)
        val tangents = DoubleArray(anchors.size)
        FableSolDepthBaseline.updateTangents(anchors, tangents)

        assertEquals(0.3958333333333333, FableSolDepthBaseline.value(
            anchors, tangents, 0.5
        ), 1e-12)
        assertEquals(1.8541666666666667, FableSolDepthBaseline.value(
            anchors, tangents, 1.5
        ), 1e-12)
    }

    @Test
    fun monotoneLayerMeansNeverOvershootTheirAdjacentAnchors() {
        val profiles = arrayOf(
            doubleArrayOf(0.0, 0.2, 2.0, 2.1, 8.0, 8.4, 9.0, 15.0, 15.1),
            doubleArrayOf(18.0, 13.0, 12.8, 9.0, 4.0, 3.9, 2.0, 1.0, -3.0)
        )

        for (anchors in profiles) {
            val tangents = DoubleArray(anchors.size)
            FableSolDepthBaseline.updateTangents(anchors, tangents)
            for (layer in 0 until anchors.lastIndex) {
                val lower = minOf(anchors[layer], anchors[layer + 1])
                val upper = maxOf(anchors[layer], anchors[layer + 1])
                for (sample in 0..32) {
                    val position = layer + sample / 32.0
                    val actual = FableSolDepthBaseline.value(anchors, tangents, position)
                    assertEquals(actual.coerceIn(lower, upper), actual, 1e-12)
                }
            }
        }
    }

    @Test
    fun depthOrbitCrossingAnAnchorDoesNotCreateClusteredContourKinks() {
        // L6 两侧采用诊断帧中同量级、且方向相反的层均值差，三个波长均来自生产水面谱。
        val anchors = doubleArrayOf(
            0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 75.07, -12.26, 0.0
        )
        val tangents = DoubleArray(anchors.size)
        FableSolDepthBaseline.updateTangents(anchors, tangents)
        val stepDp = 0.5
        val sampleCount = 1001
        val smooth = DoubleArray(sampleCount)
        val oldLinear = DoubleArray(sampleCount)

        for (sample in 0 until sampleCount) {
            val xDp = -250.0 + sample * stepDp
            val orbitZ = 1.5 * (
                sin(2.0 * PI * xDp / 145.0) +
                    0.4 * sin(2.0 * PI * xDp / 78.0 + 0.7) +
                    0.3 * sin(2.0 * PI * xDp / 58.0 + 1.2)
                )
            val layerPosition = 6.0 + orbitZ / 120.0 * (anchors.size - 1)
            smooth[sample] = FableSolDepthBaseline.value(anchors, tangents, layerPosition)
            oldLinear[sample] = linearValue(anchors, layerPosition)
        }

        val threshold = 1.45e-3
        val oldScore = trimmedJerkScore(oldLinear, stepDp, marginSamples = 100)
        val smoothScore = trimmedJerkScore(smooth, stepDp, marginSamples = 100)
        assertTrue("fixture must reproduce the old knot defect: score=$oldScore", oldScore > threshold)
        assertTrue("PCHIP contour remains kinked: score=$smoothScore", smoothScore <= threshold)
    }

    private fun realisticLayerMeans() = doubleArrayOf(
        96.0, 108.0, 114.0, 120.0, 129.0, 136.0, 145.0, 154.0, 160.0
    )

    private fun linearValue(anchors: DoubleArray, position: Double): Double {
        if (position <= 0.0) return anchors[0]
        if (position >= anchors.lastIndex) return anchors[anchors.lastIndex]
        val start = floor(position).toInt()
        val fraction = position - start
        return anchors[start] + (anchors[start + 1] - anchors[start]) * fraction
    }

    private fun trimmedJerkScore(values: DoubleArray, step: Double, marginSamples: Int): Double {
        var derivative = gaussianSmooth(values, sigmaSamples = 1.5)
        repeat(3) { derivative = gradient(derivative, step) }
        val local = DoubleArray(derivative.size - 2 * marginSamples) { index ->
            kotlin.math.abs(derivative[index + marginSamples])
        }
        local.sort()
        val keep = floor(0.95 * local.size).toInt().coerceAtLeast(1)
        var sum = 0.0
        for (index in 0 until keep) sum += local[index]
        return sum / keep
    }

    private fun gaussianSmooth(values: DoubleArray, sigmaSamples: Double): DoubleArray {
        val radius = ceil(4.0 * sigmaSamples).toInt()
        val weights = DoubleArray(2 * radius + 1)
        var weightSum = 0.0
        for (offset in -radius..radius) {
            val weight = exp(-0.5 * offset * offset / (sigmaSamples * sigmaSamples))
            weights[offset + radius] = weight
            weightSum += weight
        }
        val result = DoubleArray(values.size)
        for (index in values.indices) {
            var sum = 0.0
            for (offset in -radius..radius) {
                val source = (index + offset).coerceIn(0, values.lastIndex)
                sum += values[source] * weights[offset + radius]
            }
            result[index] = sum / weightSum
        }
        return result
    }

    private fun gradient(values: DoubleArray, step: Double): DoubleArray {
        val result = DoubleArray(values.size)
        result[0] = (values[1] - values[0]) / step
        for (index in 1 until values.lastIndex) {
            result[index] = (values[index + 1] - values[index - 1]) / (2.0 * step)
        }
        result[result.lastIndex] = (values[values.lastIndex] - values[values.lastIndex - 1]) / step
        return result
    }
}
