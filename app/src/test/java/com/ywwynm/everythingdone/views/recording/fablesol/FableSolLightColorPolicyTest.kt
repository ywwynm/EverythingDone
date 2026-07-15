package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
            rgb(81, 91, 121),
            FableSolLightColorPolicy.resolveLongitudinal(base, 0.25, 0.0)
        )
        assertEquals(
            base,
            FableSolLightColorPolicy.resolveLongitudinal(base, 0.25, 1.0)
        )
    }

    @Test
    fun positiveLongitudinalResponseIsCappedAtOnePointFivePercent() {
        val base = rgb(201, 101, 41)
        val atCap = FableSolLightColorPolicy.resolveLongitudinal(base, 0.125, 0.0)
        val farAboveCap = FableSolLightColorPolicy.resolveLongitudinal(base, 1.0, 0.0)
        assertEquals(rgb(202, 102, 41), atCap)
        assertEquals(atCap, farAboveCap)
    }

    @Test
    fun macroShadowMaskRejectsWeakSlopesAndLastRowButKeepsAQuietFarTail() {
        assertEquals(0.0, FableSolLightColorPolicy.macroShadowMask(0.079, 0.0, 1.0), 0.0)
        assertEquals(0.0, FableSolLightColorPolicy.macroShadowMask(0.30, 1.0, 1.0), 0.0)
        assertTrue(FableSolLightColorPolicy.macroShadowMask(0.30, 0.70, 1.0) > 0.0)
        assertEquals(0.30, FableSolLightColorPolicy.macroShadowMask(0.18, 0.0, 0.0), 1e-12)
        assertEquals(1.0, FableSolLightColorPolicy.macroShadowMask(0.18, 0.0, 0.08), 1e-12)
    }

    @Test
    fun macroVisibilityOnlyRemovesTheDirectLobeAndNeverDarkensTheBody() {
        val base = rgb(170, 160, 190)
        val lit = FableSolLightColorPolicy.resolveLongitudinal(
            base = base,
            positiveNdl = 1.0,
            depth01 = 0.0,
            negativeNdl = 0.0,
            directNdl = 1.0,
            crestPinch = 0.10
        )
        val shadowed = FableSolLightColorPolicy.resolveLongitudinal(
            base = base,
            positiveNdl = 1.0,
            depth01 = 0.0,
            negativeNdl = 0.30,
            directNdl = 1.0,
            crestPinch = 0.10
        )

        assertTrue(lit != base)
        assertEquals(base, shadowed)
    }

    @Test
    fun zeroDirectAndZeroRelativeResponseReturnExactBase() {
        val base = rgb(80, 90, 120)
        assertEquals(
            base,
            FableSolLightColorPolicy.resolveLongitudinal(
                base = base,
                positiveNdl = 0.0,
                depth01 = 0.0,
                negativeNdl = 0.40,
                directNdl = 0.0,
                crestPinch = 1.0
            )
        )
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xff shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

}
