package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolLayerColorPolicyTest {

    @Test
    fun nearestLayerKeepsPureAndGradientThingIdentityExactly() {
        val start = rgb("F02A4B")
        val end = rgb("6E62DF")
        val pure = FableSolLayerColorPolicy.palette(
            FableSolLayerColorPolicy.baseColors(start, null),
            0.864,
            moodBright = 1.0,
            breath = 0.24
        )
        val gradient = FableSolLayerColorPolicy.palette(
            FableSolLayerColorPolicy.baseColors(start, end),
            0.864,
            moodBright = 1.0,
            breath = 0.24
        )

        assertArrayEquals(start, pure.layers[0].start)
        assertArrayEquals(start, pure.layers[0].end)
        assertArrayEquals(start, gradient.layers[0].start)
        assertArrayEquals(end, gradient.layers[0].end)
    }

    @Test
    fun defaultPaletteUsesExactStaticLightenFarWhiteMixForEveryLayerAndStop() {
        val start = rgb("F02A4B")
        val end = rgb("6E62DF")
        val identityStops = arrayOf(
            start,
            FableSolColor.mixOklab(start, end, 0.21),
            FableSolColor.mixOklab(start, end, 0.56),
            end
        )
        val palette = FableSolLayerColorPolicy.palette(
            FableSolLayerColorPolicy.baseColors(start, end),
            FableSolLayerColorPolicy.DEFAULT_LIGHTEN_FAR,
            moodBright = 0.91,
            breath = 0.24
        )

        for (layer in 0 until FableSolSpec.N_LAYERS) {
            val amount = layer / 8.0 * 0.864
            val expected = identityStops.map {
                FableSolColor.mixOklab(it, WHITE, amount)
            }
            val actual = palette.layers[layer]
            assertArrayEquals("layer=$layer start", expected[0], actual.start)
            assertArrayEquals("layer=$layer stop1", expected[1], actual.stop1)
            assertArrayEquals("layer=$layer stop2", expected[2], actual.stop2)
            assertArrayEquals("layer=$layer end", expected[3], actual.end)
        }
    }

    @Test
    fun moodBreathRecordingInputsNeverChangeBodyPalette() {
        val base = FableSolLayerColorPolicy.baseColors(rgb("2C6CDE"), rgb("0438B1"))
        val quiet = FableSolLayerColorPolicy.palette(base, 0.864, 0.0, 0.0)
        val energetic = FableSolLayerColorPolicy.palette(base, 0.864, 1.0, 0.24)

        for (layer in 0 until FableSolSpec.N_LAYERS) {
            assertStopsEqual(quiet.layers[layer], energetic.layers[layer])
        }
        assertEquals(
            FableSolLayerColorPolicy.lightenAmount(0.5, 0.864, 0.0, 0.0),
            FableSolLayerColorPolicy.lightenAmount(0.5, 0.864, 1.0, 0.24),
            0.0
        )
    }

    @Test
    fun farWhiteMixIsClampedToEightySixPointFourPercent() {
        assertEquals(0.864, FableSolLayerColorPolicy.lightenAmount(1.0, 0.864, 0.0, 0.0), 0.0)
        assertEquals(0.864, FableSolLayerColorPolicy.lightenAmount(1.0, 0.96, 1.0, 0.24), 0.0)
        assertEquals(0.0, FableSolLayerColorPolicy.lightenAmount(1.0, -1.0, 0.0, 0.0), 0.0)
        assertEquals(0.0, FableSolLayerColorPolicy.lightenAmount(0.0, 0.864, 1.0, 0.24), 0.0)

        val base = rgb("8A0751")
        val ramp = FableSolLayerColorPolicy.ramp(base, 2.0, 1.0, 0.24)
        assertArrayEquals(
            FableSolColor.mixOklab(base, WHITE, 0.864),
            ramp.colorAt(1.0)
        )
    }

    @Test
    fun allInterfaceShoulderWeightsAreZero() {
        val palette = FableSolLayerColorPolicy.palette(
            FableSolLayerColorPolicy.baseColors(rgb("00FFF6"), rgb("4B7ADB")),
            0.864,
            1.0,
            0.24
        )
        val weights = palette.interfaceWeights
        for (values in arrayOf(weights.start, weights.stop1, weights.stop2, weights.end)) {
            assertArrayEquals(DoubleArray(FableSolSpec.N_LAYERS), values, 0.0)
        }
        for (layer in 0 until FableSolSpec.N_LAYERS) {
            assertArrayEquals(DoubleArray(4), weights.forContour(layer), 0.0)
        }
    }

    @Test
    fun builtInAndRealThingFixturesFollowTheSameNineStepWhiteMix() {
        val fixtures = arrayOf(
            "607D8B" to null, "00B1C6" to null, "366686" to null,
            "364656" to null, "96766B" to null, "166096" to null,
            "21A675" to null, "665696" to null, "D28656" to null,
            "AE6060" to null,
            "F02A4B" to "6E62DF", "00FFF6" to "4B7ADB",
            "8A0751" to null, "B6BF8F" to null,
            "D72DB8" to "EB8B6D", "F9799A" to "E7EB13",
            "FE79B8" to "5F5080", "2C6CDE" to "0438B1"
        )

        for ((startHex, endHex) in fixtures) {
            val start = rgb(startHex)
            val end = endHex?.let(::rgb)
            val palette = FableSolLayerColorPolicy.palette(
                FableSolLayerColorPolicy.baseColors(start, end),
                0.864,
                0.91,
                0.24
            )
            val extractors = arrayOf<(FableSolLayerGradientStops) -> IntArray>(
                { it.start }, { it.stop1 }, { it.stop2 }, { it.end }
            )
            for (extract in extractors) {
                val colors = palette.layers.map(extract)
                assertEquals(
                    "$startHex/$endHex 应保留九个混白层级",
                    FableSolSpec.N_LAYERS,
                    colors.map(IntArray::toList).distinct().size
                )
                val lightness = colors.map { FableSolColor.rgbToOklab(it)[0] }
                assertTrue(lightness.zipWithNext().all { (near, far) -> far + 0.002 >= near })
            }
        }
    }

    private fun assertStopsEqual(
        expected: FableSolLayerGradientStops,
        actual: FableSolLayerGradientStops
    ) {
        assertArrayEquals(expected.start, actual.start)
        assertArrayEquals(expected.stop1, actual.stop1)
        assertArrayEquals(expected.stop2, actual.stop2)
        assertArrayEquals(expected.end, actual.end)
    }

    private fun rgb(hex: String): IntArray = intArrayOf(
        hex.substring(0, 2).toInt(16),
        hex.substring(2, 4).toInt(16),
        hex.substring(4, 6).toInt(16)
    )

    private companion object {
        val WHITE = intArrayOf(255, 255, 255)
    }
}
