package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FableSolGlShaderParityTest {

    @Test
    fun waterUsesBoundedIdentityColorMacroShadowAndLayerLocalFourStopGradients() {
        val source = shader("water.vert")

        assertTrue(source.contains("relativeLongitudinalLight"))
        val functionStart = source.indexOf("vec3 relativeLongitudinalLight")
        val functionEnd = source.indexOf("\n}\n", functionStart) + 3
        val longitudinal = source.substring(functionStart, functionEnd)
        assertTrue(source.contains("MAX_RELATIVE_LONGITUDINAL_LIFT = 0.015"))
        assertTrue(source.contains("LONGITUDINAL_LIGHT_RESPONSE = 0.12"))
        assertTrue(source.contains("uniform float uMacroShadowLumaCap"))
        assertTrue(source.contains("MACRO_SHADOW_NDL_START = 0.080"))
        assertTrue(source.contains("MACRO_SHADOW_FAR_END = 0.700"))
        assertTrue(longitudinal.contains("positiveNdl"))
        assertTrue(longitudinal.contains("max(-relativeNdl, 0.0)"))
        assertTrue(longitudinal.contains("deepLinear"))
        assertTrue(longitudinal.contains("requestedLoss"))
        assertTrue(longitudinal.contains("linearToSrgb"))
        assertFalse(longitudinal.contains("blackMix"))
        assertFalse(longitudinal.contains("uHorizonColor"))
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
    fun derivedDeepAndSubsurfaceColorsRemainLiveWithoutDisabledDepthScattering() {
        val vertex = shader("water.vert")
        val fragment = shader("water.frag")

        assertTrue(vertex.contains("layout(location = 3) in float aCrestPinch"))
        assertTrue(vertex.contains("uLayerDeepStart[9]"))
        assertTrue(vertex.contains("uLayerSubsurfaceStart[9]"))
        assertTrue(vertex.contains("deepColor"))
        assertTrue(vertex.contains("relativeLongitudinalLight"))
        assertTrue(vertex.contains("materialSubsurface = subsurfaceColor"))
        assertTrue(fragment.contains("addSunriseSubsurface"))
        assertFalse(vertex.contains("uDepthScatteringStrength"))
        assertFalse(vertex.contains("vec3 depthScattering"))
        assertFalse(vertex.contains("nearShadingWeight"))
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
        assertTrue(source.contains("exp2(-6.5 * radiusSquared)"))
        assertTrue(source.contains("smoothstep(0.72, 1.0, vLocalUv.y)"))
    }

    @Test
    fun presentationPassOwnsLegacyAlphaAndPremultipliedRoundedCorners() {
        val source = shader("present.frag")

        assertTrue(source.contains("uPresentationAlpha"))
        assertTrue(source.contains("uCornerRadiusPx"))
        assertTrue(source.contains("roundedRectCoverage"))
        assertTrue(source.contains("mix("))
        assertTrue(source.contains("vec4(color * coverage, coverage)"))
    }

    @Test
    fun hdrPipelineUsesLinearSceneEncodingWithoutChangingSdrPresentationMix() {
        val environment = shader("environment.frag")
        val water = shader("water.frag")
        val optical = shader("optical.frag")
        val presentation = shader("present.frag")

        assertTrue(environment.contains("uniform bool uSceneLinear"))
        assertTrue(environment.contains("srgbToLinear(encodedColor)"))
        assertTrue(water.contains("uniform bool uSceneLinear"))
        assertTrue(water.contains("srgbToLinear(encodedColor)"))
        assertTrue(optical.contains("uSceneLinear ? srgbToLinear(encodedColor) : encodedColor"))
        assertTrue(presentation.contains("encodedScene = linearToSrgb(sceneColor)"))
        assertTrue(presentation.contains("srgbToLinear(mix(uBackdropColor"))
    }

    @Test
    fun hdrExcessIsLimitedToGlintCrestAndTransmissionRatherThanStreakOrHalo() {
        val source = shader("optical.frag")
        val hdrBlock = source.substring(source.indexOf("if (uSceneLinear && uHdrGain"))

        assertTrue(hdrBlock.contains("uHdrCorePeak"))
        assertTrue(hdrBlock.contains("uHdrCrestPeak"))
        assertTrue(hdrBlock.contains("uHdrTransmissionPeak"))
        assertTrue(hdrBlock.contains("smoothstep(0.28, 0.82, vHdrEligibility)"))
        assertFalse(hdrBlock.contains("vOpticalMode > 1.5"))
        assertFalse(hdrBlock.contains("vOpticalMode > 6.5"))
    }

    @Test
    fun waterGrazingSheenAddsSuperWhiteOnlyInSceneLinear() {
        val vertex = shader("water.vert")
        val source = shader("water.frag")

        assertTrue(source.contains("grazingSheenExcess"))
        assertTrue(source.contains("outLinear += grazingSheenExcess(normal, fresnel)"))
        // 只在 scene-linear 录音态叠加超白差量：SDR 分支（uSceneLinear=false）逐字节不变。
        assertTrue(source.contains("uSceneLinear && vFrontFill == 0 && uHdrGain"))
        // 掠射 Fresnel 反射 + 朝太阳集中，几何驱动、不接音频。
        assertTrue(source.contains("reflect(-viewDir, normal)"))
        assertTrue(source.contains("pow(1.0 - NdV, 5.0)"))
        // HDR 银泽使用独立低通法线，避免把水体几何网格的三角形边界放大出来。
        assertTrue(vertex.contains("layout(location = 4) in vec2 aSheenSlope"))
        assertTrue(vertex.contains("vSheenSlope = aSheenSlope"))
        assertTrue(source.contains("in vec2 vSheenSlope"))
        assertTrue(source.contains("vec3(-vSheenSlope.x, 1.0, -vSheenSlope.y)"))
        assertTrue(source.contains("pow(clamp(fresnel * sunBoost, 0.0, 1.0), 0.70)"))
        assertTrue(source.contains("mix(2.0, 1.0"))
    }

    @Test
    fun waterBacklitTransmissionUsesIdentityColorAndTheComplementaryFresnelBudget() {
        val source = shader("water.frag")

        assertTrue(source.contains("uniform float uHdrTransmissionPeak"))
        assertTrue(source.contains("backlitTransmissionExcess"))
        assertTrue(source.contains("(1.0 - fresnel) * sunriseSubsurfaceMask()"))
        assertTrue(source.contains("subsurfaceLinear / maximum"))
        assertTrue(source.contains("outLinear += backlitTransmissionExcess(fresnel)"))
        assertTrue(source.contains("outLinear = min(outLinear, vec3(uHdrHeadroom))"))
        // SDR 仍只经过原有 encodedColor；透射超白和银泽共享同一个 scene-linear 门。
        val hdrStart = source.indexOf("if (uSceneLinear && vFrontFill == 0")
        assertTrue(hdrStart > source.indexOf("vec3 encodedColor"))
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
