package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FableSolMathBufferTest {

    @Test
    fun `buffered gradient ignores unused scratch tail`() {
        val input = doubleArrayOf(1.0, 3.0, 8.0, 16.0, 999.0, 999.0)
        val expected = FableSolMath.gradient(input.copyOf(4), 0.5)
        val actual = DoubleArray(input.size)

        FableSolMath.gradientInto(input, 4, 0.5, actual)

        assertArrayEquals(expected, actual.copyOf(4), 1e-12)
    }

    @Test
    fun `buffered same convolution is numerically identical`() {
        val input = doubleArrayOf(1.0, 3.0, 8.0, 16.0, 999.0)
        val kernel = doubleArrayOf(0.25, 0.5, 0.25)
        val expected = FableSolMath.convolveSame(input.copyOf(4), kernel)
        val actual = DoubleArray(input.size)

        FableSolMath.convolveSameInto(input, 4, kernel, actual)

        assertArrayEquals(expected, actual.copyOf(4), 1e-12)
    }

    @Test
    fun `buffered Hann smoothing matches the original padded convolution`() {
        val input = DoubleArray(32) { index ->
            kotlin.math.sin(index * 0.31) + kotlin.math.cos(index * 0.07) * 0.4
        }
        for (radius in 3..6) {
            val hann = FableSolMath.hanning(radius * 2 + 3)
            val kernel = DoubleArray(radius * 2 + 1) { hann[it + 1] }
            val sum = kernel.sum()
            for (index in kernel.indices) kernel[index] /= sum
            val expected = FableSolMath.convolveValid(FableSolMath.padEdge(input, radius), kernel)
            val actual = DoubleArray(input.size)

            FableSolMath.smoothHannInto(input, input.size, radius, actual)

            assertArrayEquals("radius=$radius", expected, actual, 1e-12)
        }
    }
}
