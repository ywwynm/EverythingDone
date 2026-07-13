package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FableSolGlShaderParityTest {

    @Test
    fun waterUsesCanvasRelativeLongitudinalLightAndLayerLocalFourStopGradients() {
        val source = shader("water.vert")

        assertTrue(source.contains("relativeLongitudinalLight"))
        assertTrue(source.contains("linearBase + fullLight - referenceLight"))
        assertTrue(source.contains("0.14 * sqrt(darkness) * depthScale"))
        assertFalse(source.contains("0.90 + 0.10 * ndl"))
        assertTrue(source.contains("uLayerStop1[9]"))
        assertTrue(source.contains("uLayerStop2[9]"))
        assertTrue(source.contains("uGradientOrigin[9]"))
        assertTrue(source.contains("uGradientDirection[9]"))
    }

    @Test
    fun canvasStrengthDitherCoversSkyAndFrontFillOnly() {
        val environment = shader("environment.frag")
        val water = shader("water.frag")

        assertTrue(environment.contains("((a + b) * 0.5 - 0.5) / 255.0"))
        assertTrue(environment.contains("triangularDither(gl_FragCoord.xy)"))
        assertTrue(water.contains("vFrontFill == 1 ? triangularDither"))
        assertFalse(water.contains("(a + b - 1.0) / 255.0"))
    }

    @Test
    fun glintShaderKeepsCurvedWaterSideGeometryButRestoresTwoToneHalo() {
        val vertex = shader("optical.vert")
        val fragment = shader("optical.frag")

        assertTrue(vertex.contains("aEdgeColor"))
        assertTrue(fragment.contains("mix(vColor.rgb, vEdgeColor"))
        assertTrue(fragment.contains("vOpticalMode > 5.5"))
    }

    @Test
    fun depthScatteringUsesDerivedPairWithViewCrestAndSunMasks() {
        val source = shader("water.vert")

        assertTrue(source.contains("layout(location = 3) in float aCrestPinch"))
        assertTrue(source.contains("uLayerDeepStart[9]"))
        assertTrue(source.contains("uLayerSubsurfaceStart[9]"))
        assertTrue(source.contains("float grazing = smoothstep(0.18, 0.92, depth01)"))
        assertTrue(source.contains("float thinCrest = crestPinch * mix(0.65, 1.0, sunFacing)"))
        assertTrue(source.contains("mix(deep, subsurface, subsurfaceMask)"))
        assertTrue(source.contains("uDepthScatteringStrength"))
    }

    @Test
    fun microNormalsUseThreeAnalyticDerivativeOctavesAndRowBandLimiting() {
        val source = shader("water.frag")

        assertTrue(source.contains("valueNoiseDerivative"))
        assertTrue(source.contains("octave0"))
        assertTrue(source.contains("octave1"))
        assertTrue(source.contains("octave2"))
        assertTrue(source.contains("rowFootprint"))
        assertTrue(source.contains("uSpecularAaStrength"))
        assertTrue(source.contains("uMicroNormalStrength"))
    }

    @Test
    fun sunriseSssUsesStableCrestMaskAndHighFalloffWithoutAudioUniforms() {
        val source = shader("water.frag")

        assertTrue(source.contains("uSunSssFalloff"))
        assertTrue(source.contains("clamp(uSunSssFalloff, 4.0, 10.0)"))
        assertTrue(source.contains("pow(clamp(vCrestPinch, 0.0, 1.0), 1.35)"))
        assertFalse(source.contains("Audio"))
        assertFalse(source.contains("Transient"))
    }

    @Test
    fun analyticGlintHaloUsesMathematicalFalloffBeforeCoreMode() {
        val source = shader("optical.frag")
        val halo = source.indexOf("vOpticalMode > 6.5")
        val core = source.indexOf("vOpticalMode > 2.5")

        assertTrue(halo >= 0)
        assertTrue(core > halo)
        assertTrue(source.contains("exp2(-4.6 * radiusSquared)"))
    }

    private fun shader(name: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(5) {
            val candidate = File(directory, "shared/fablesol/glsl/$name")
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到 shared/fablesol/glsl/$name")
    }
}
