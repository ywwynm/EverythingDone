package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class FableSolLightColorPolicyTest {

    @Test
    fun negativeLongitudinalResponseReturnsExactBase() {
        val bases = intArrayOf(
            rgb(240, 42, 75), rgb(46, 139, 87), rgb(61, 111, 224),
            rgb(229, 185, 61), rgb(170, 104, 205)
        )
        for (base in bases) {
            assertEquals(base,
                FableSolLightColorPolicy.resolveLongitudinal(base, -0.25, 0.0))
            assertEquals(base,
                FableSolLightColorPolicy.resolveLongitudinal(base, 0.0, 0.625))
        }
    }

    @Test
    fun positiveLongitudinalResponseOnlyScalesTheBaseColorUpward() {
        val base = rgb(80, 90, 120)
        assertEquals(
            rgb(81, 91, 122),
            FableSolLightColorPolicy.resolveLongitudinal(base, 0.25, 0.0)
        )
        assertEquals(
            rgb(81, 91, 121),
            FableSolLightColorPolicy.resolveLongitudinal(base, 0.25, 1.0)
        )
    }

    @Test
    fun positiveLongitudinalResponseIsCappedAtOnePointFivePercent() {
        val base = rgb(201, 101, 41)
        val atCap = FableSolLightColorPolicy.resolveLongitudinal(base, 0.125, 0.0)
        val farAboveCap = FableSolLightColorPolicy.resolveLongitudinal(base, 1.0, 0.0)
        assertEquals(rgb(204, 103, 42), atCap)
        assertEquals(atCap, farAboveCap)
    }

    @Test
    fun macroShadowMaskRejectsWeakSlopesAndFarRowsButKeepsCrestLocality() {
        assertEquals(0.0, FableSolLightColorPolicy.macroShadowMask(0.079, 0.0, 1.0), 0.0)
        assertEquals(0.0, FableSolLightColorPolicy.macroShadowMask(0.30, 0.70, 1.0), 0.0)
        assertEquals(0.30, FableSolLightColorPolicy.macroShadowMask(0.18, 0.0, 0.0), 1e-12)
        assertEquals(1.0, FableSolLightColorPolicy.macroShadowMask(0.18, 0.0, 0.08), 1e-12)
    }

    @Test
    fun identityColorShadowIsCappedByFinalLinearLuminanceLoss() {
        val base = rgb(170, 160, 190)
        val deep = rgb(82, 72, 106)
        val shadowed = FableSolLightColorPolicy.resolveLongitudinal(
            base = base,
            positiveNdl = 0.0,
            depth01 = 0.0,
            negativeNdl = 0.30,
            deep = deep,
            crestPinch = 0.10,
            shadowLumaCap = 0.018
        )
        val loss = (linearLuma(base) - linearLuma(shadowed)) / linearLuma(base)

        assertTrue(loss > 0.010)
        assertTrue(loss <= 0.022)
    }

    @Test
    fun zeroShadowCapExactlyRestoresD87PositiveOnlyOutput() {
        val base = rgb(80, 90, 120)
        val d87 = FableSolLightColorPolicy.resolveLongitudinal(base, 0.25, 0.0)
        val disabled = FableSolLightColorPolicy.resolveLongitudinal(
            base, 0.25, 0.0, 0.40, rgb(30, 40, 60), 1.0, 0.0
        )

        assertEquals(d87, disabled)
        assertEquals(0.018, FableSolParams().get("macro_shadow_luma_cap"), 0.0)
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xff shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun linearLuma(color: Int): Double {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return linear(color ushr 16 and 0xff) * 0.2126 +
            linear(color ushr 8 and 0xff) * 0.7152 +
            linear(color and 0xff) * 0.0722
    }

}
