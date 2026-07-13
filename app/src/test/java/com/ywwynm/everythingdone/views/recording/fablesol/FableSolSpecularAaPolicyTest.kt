package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolSpecularAaPolicyTest {

    @Test
    fun `specular aa has an independent default-on switch`() {
        assertEquals(1.0, FableSolParams().get("specular_aa_strength"), 0.0)
    }

    @Test
    fun `band limit follows the analytic sample footprint`() {
        assertEquals(0.0, FableSolSpecularAaPolicy.resolvedAmplitude(6.0, 3.0), 0.0)
        assertEquals(1.0, FableSolSpecularAaPolicy.resolvedAmplitude(12.0, 3.0), 0.0)
        assertTrue(FableSolSpecularAaPolicy.resolvedAmplitude(9.0, 3.0) in 0.0..1.0)
    }

    @Test
    fun `unresolved slope variance widens and normalizes the glint lobe`() {
        val base = 0.072
        val effective = FableSolSpecularAaPolicy.effectiveSigma(base, 0.0012)
        val normalization = FableSolSpecularAaPolicy.peakNormalization(base, effective)

        assertTrue(effective > base)
        assertTrue(normalization >= 0.0 && normalization < 1.0)
    }

    @Test
    fun `zero strength reproduces the unfiltered optical slopes exactly`() {
        val waves = FableSolOpticalWaveSet(6000L, 0.0)
        val x = DoubleArray(80) { it * 3.2 }
        val filtered = DoubleArray(x.size)
        val filteredCurvature = DoubleArray(x.size)
        val raw = DoubleArray(x.size)
        val rawCurvature = DoubleArray(x.size)
        val baseline = DoubleArray(x.size)
        val baselineCurvature = DoubleArray(x.size)

        val movedVariance = waves.sampleInto(
            x, x.size, 0.8, 0.4,
            filtered, filteredCurvature, 1.0, raw, rawCurvature
        )
        val movedCurvatureVariance = waves.lastUnresolvedCurvatureVariance
        val zeroVariance = waves.sampleInto(
            x, x.size, 0.8, 0.4,
            baseline, baselineCurvature, 0.0
        )

        assertTrue(movedVariance > 0.0)
        assertTrue(movedCurvatureVariance > 0.0)
        assertEquals(0.0, zeroVariance, 0.0)
        assertEquals(0.0, waves.lastUnresolvedCurvatureVariance, 0.0)
        for (index in x.indices) {
            assertEquals(raw[index], baseline[index], 1e-12)
            assertEquals(rawCurvature[index], baselineCurvature[index], 1e-12)
        }
        assertTrue(filtered.indices.any { kotlin.math.abs(filtered[it] - raw[it]) > 1e-8 })
    }
}
