package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolOpticalColorPolicyTest {

    @Test
    fun reflectionAndTransmissionStayOnTheThingColorToNeutralWhiteAxis() {
        val samples = arrayOf(
            intArrayOf(232, 52, 90),
            intArrayOf(236, 128, 42),
            intArrayOf(62, 172, 112),
            intArrayOf(55, 126, 224),
            intArrayOf(154, 76, 210)
        )

        for (base in samples) {
            val highlight = FableSolOpticalColorPolicy.highlight(base, 0.40)
            val transmission = FableSolOpticalColorPolicy.thinTransmission(highlight)

            val highlightHueDistance = hueDistanceDeg(base, highlight)
            val transmissionHueDistance = hueDistanceDeg(base, transmission)
            assertTrue("highlight=${base.contentToString()} distance=$highlightHueDistance",
                highlightHueDistance <= 1.5)
            assertTrue("transmission=${base.contentToString()} distance=$transmissionHueDistance",
                transmissionHueDistance <= 1.5)
            assertTrue(lightness(highlight) > lightness(base))
            assertTrue(lightness(transmission) >= lightness(highlight))
        }
    }

    private fun lightness(color: IntArray): Double = FableSolColor.rgbToOklab(color)[0]

    private fun hueDistanceDeg(first: IntArray, second: IntArray): Double {
        fun hue(color: IntArray): Double {
            val lab = FableSolColor.rgbToOklab(color)
            return atan2(lab[2], lab[1])
        }
        var delta = abs(hue(first) - hue(second))
        if (delta > PI) delta = 2.0 * PI - delta
        return Math.toDegrees(delta)
    }
}
