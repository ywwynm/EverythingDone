package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FableSolGlShaderParityTest {

    @Test
    fun macroVisibilityOnlyAttenuatesARealIdentityDirectLobe() {
        val source = shader("water.vert")
        val functionStart = source.indexOf("float relativeLongitudinalDirect")
        val functionEnd = source.indexOf("\n}\n", functionStart) + 3
        val longitudinal = source.substring(functionStart, functionEnd)

        assertTrue(source.contains("MAX_RELATIVE_LONGITUDINAL_LIFT = 0.015"))
        assertTrue(source.contains("LONGITUDINAL_LIGHT_RESPONSE = 0.12"))
        assertTrue(source.contains("DIRECT_LIGHT_BASE_RESPONSE = 0.004"))
        assertTrue(source.contains("MAX_DIRECT_LIGHT_LOBE = 0.019"))
        assertTrue(source.contains("uniform float uMacroLightWeights[9]"))
        assertTrue(source.contains("uniform float uMacroShadowWeights[9]"))
        assertTrue(longitudinal.contains("float ndl = max(dot(normal, lightDir), 0.0)"))
        assertTrue(longitudinal.contains("float directLobe = min("))
        assertTrue(longitudinal.contains("max(-relativeNdl, 0.0)"))
        assertTrue(longitudinal.contains("return directLobe * (1.0 - clamp(shadowMask"))
        assertFalse(longitudinal.contains("deepLinear"))
        assertFalse(longitudinal.contains("linearToSrgb"))
        assertFalse(source.contains("uMacroShadowLumaCap"))
        assertFalse(source.contains("uLayerDeep"))
        assertTrue(source.contains("uLayerStop1[9]"))
        assertTrue(source.contains("uLayerStop2[9]"))
        assertTrue(source.contains("uGradientOrigin[9]"))
        assertTrue(source.contains("uGradientDirection[9]"))
    }

    @Test
    fun sdrDitherIsAppliedOnceAtPresentationAndSkippedByHdrScenePasses() {
        val environment = shader("environment.frag")
        val water = shader("water.frag")
        val presentation = shader("present.frag")

        assertFalse(environment.contains("triangularDither"))
        assertFalse(water.contains("triangularDither"))
        assertTrue(environment.contains("uSceneLinear ? srgbToLinear(uEnvironmentTop)"))
        assertTrue(environment.contains("? srgbToLinear(uEnvironmentHorizon)"))
        assertTrue(environment.contains("? srgbToLinear(uEnvironmentBottom)"))
        assertTrue(presentation.contains("((a + b) * 0.5 - 0.5) / 255.0"))
        assertTrue(presentation.contains("color + triangularDither(gl_FragCoord.xy)"))
        val hdrBranch = presentation.substring(
            presentation.indexOf("if (uSceneLinear)"),
            presentation.indexOf("} else {")
        )
        assertFalse(hdrBranch.contains("triangularDither"))
    }

    @Test
    fun glintShaderKeepsCurvedCoreGeometryWithoutPackedOrPeriodicHalo() {
        val vertex = shader("optical.vert")
        val fragment = shader("optical.frag")
        val optics = projectFile(
            "app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/" +
                "FableSolGlOptics.kt"
        )
        val canvas = projectFile(
            "app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/" +
                "WaveVisualizerFableSol.kt"
        )

        assertTrue(vertex.contains("aEdgeColor"))
        assertTrue(fragment.contains("glintCoreCoverage"))
        assertTrue(fragment.contains("coverage = glintCoreCoverage"))
        assertFalse(fragment.contains("float haloRatio"))
        assertFalse(fragment.contains("haloRatio * haloCoverage"))
        assertTrue(optics.contains("val packedMode = OPTICAL_MODE_GLINT"))
        assertFalse(optics.contains("OPTICAL_MODE_GLINT + 0.49f"))
        assertFalse(optics.contains("analytic_halo_strength"))
        assertFalse(optics.contains("track.intensity * breath"))
        assertFalse(optics.contains("0.8 + 0.4 * intensity"))
        assertFalse(optics.contains("track.size +="))
        assertFalse(canvas.contains("e.inten * breath"))
        assertFalse(canvas.contains("0.8 + 0.4 * inten"))
        assertFalse(canvas.contains("e.size +="))
    }

    @Test
    fun subsurfaceRemainsLiveWhileDeepBodyMultiplicationIsRemoved() {
        val vertex = shader("water.vert")
        val fragment = shader("water.frag")

        assertTrue(vertex.contains("layout(location = 3) in float aCrestPinch"))
        assertTrue(vertex.contains("uLayerSubsurfaceStart[9]"))
        assertTrue(vertex.contains("relativeLongitudinalDirect"))
        assertTrue(vertex.contains("materialSubsurface = mix("))
        assertTrue(vertex.contains("behindSubsurface"))
        assertFalse(vertex.contains("uLayerDeepStart"))
        assertFalse(vertex.contains("deepColor"))
        assertTrue(fragment.contains("addSunriseSubsurface"))
        assertFalse(vertex.contains("uDepthScatteringStrength"))
        assertFalse(vertex.contains("vec3 depthScattering"))
    }

    @Test
    fun microNormalsRemainForRefractionWithoutVisibleContinuousSheen() {
        val vertex = shader("water.vert")
        val source = shader("water.frag")

        assertTrue(source.contains("valueNoiseDerivative"))
        assertTrue(source.contains("octave0"))
        assertTrue(source.contains("octave1"))
        assertTrue(source.contains("octave2"))
        assertTrue(source.contains("octaveBandLimit"))
        assertTrue(source.contains("uSpecularAaStrength"))
        assertTrue(vertex.contains("uniform float uMicroNormalWeights[9]"))
        assertTrue(vertex.contains("vMicroNormalWeight = sampleLayerCurve"))
        assertTrue(source.contains("vec2 continuousSlope = vSheenSlope + microSlope"))
        assertTrue(source.contains("applyRefractionAndBeer("))
        assertFalse(source.contains("float distributionGgx("))
        assertFalse(source.contains("float visibilitySmithGgxCorrelated"))
        assertFalse(source.contains("float fresnelSchlick("))
        assertFalse(source.contains("float effectiveSpecularRoughness("))
        assertFalse(source.contains("applyWindCombedMicroNormals"))
        assertFalse(source.contains("lightDelta <"))

        val main = source.substring(source.indexOf("void main()"))
        assertFalse(main.contains("dFdx(continuousSlope)"))
        assertFalse(main.contains("dFdy(continuousSlope)"))
        assertFalse(main.contains("fwidth(continuousSlope)"))
    }

    @Test
    fun layerInteriorContinuousSheenAndPairedDarkMarksAreAbsent() {
        val source = shader("water.frag")

        listOf(
            "stableWindFacetCoverage",
            "continuousHdrLocality",
            "microfacetSheenExcess",
            "boundedSdrReflection",
            "applyBoundedSunReflection",
            "applySameNormalFormShade",
            "uSheenCoverageScale",
            "uSunReflectionCoverageWeights",
            "vSunReflectionCoverageWeight",
            "vHdrSheenPeak"
        ).forEach { removed -> assertFalse(removed, source.contains(removed)) }
        assertTrue(source.contains("backlitTransmissionExcess(transmissionFresnel)"))
    }

    @Test
    fun dormantInterfaceShoulderPipelineReceivesOnlyZeroPolicyWeights() {
        val vertex = shader("water.vert")
        val fragment = shader("water.frag")
        val policy = projectFile(
            "app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/" +
                "FableSolLayerColorPolicy.kt"
        )

        assertFalse(vertex.contains("uInterface"))
        assertFalse(fragment.contains("applyInterfaceShoulder"))
        assertTrue(policy.contains("val zero = DoubleArray(FableSolSpec.N_LAYERS)"))
        assertTrue(policy.contains(
            "zero.copyOf(), zero.copyOf(), zero.copyOf(), zero.copyOf()"
        ))
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
    fun glintCoreKeepsCurvedGeometryWithoutPackedHalo() {
        val source = shader("optical.frag")
        val optics = projectFile(
            "app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/" +
                "FableSolGlOptics.kt"
        )

        assertFalse(source.contains("(vOpticalMode - 3.0) / 0.49"))
        // 覆盖计算已抽成 opticalCoverage(vec2 uv,...) 供逐子样本调用，剖面数学不变。
        assertTrue(source.contains(
            "float inward = 1.0 - smoothstep(0.08, 0.72, uv.y)"
        ))
        assertFalse(source.contains("smoothstep(0.0, 0.16, corePoint.y)"))
        assertTrue(source.contains("glintCoreCoverage = along * inward"))
        assertTrue(source.contains("coverage = glintCoreCoverage"))
        // 光学 pass 对逐像素程序化形状做旋转网格 4 采样（RGSS）超采样，MSAA 只多采样几何。
        assertTrue(source.contains("void opticalCoverage(vec2 uv"))
        assertTrue(source.contains("vec4 shadeOpticalSample(vec2 uv)"))
        assertTrue(source.contains("dFdx(vLocalUv)"))
        assertTrue(source.contains("dFdy(vLocalUv)"))
        assertTrue("shadeOpticalSample\\(vLocalUv".toRegex().findAll(source).count() == 4)
        assertTrue(optics.contains("MIN_CURVED_BAND_SEGMENTS = 12"))
        assertTrue(optics.contains("CURVED_BAND_TARGET_SEGMENT_DP = 3.2"))
        assertTrue(optics.contains("val packedMode = OPTICAL_MODE_GLINT"))
        assertFalse(optics.contains("OPTICAL_MODE_GLINT + 0.49f"))
    }

    @Test
    fun presentationPassComposesLinearHdrWithoutEncodingRoundTrip() {
        val source = shader("present.frag")

        assertTrue(source.contains("uPresentationAlpha"))
        assertTrue(source.contains("uCornerRadiusPx"))
        assertTrue(source.contains("uniform float uHdrHeadroom"))
        assertTrue(source.contains("roundedRectCoverage"))
        assertTrue(source.contains("sceneColor = clamp(sceneColor"))
        assertTrue(source.contains("mix(srgbToLinear(uBackdropColor), sceneColor"))
        assertFalse(source.contains("linearToSrgb"))
        assertTrue(source.contains("vec4(color * coverage, coverage)"))
    }

    @Test
    fun hdrPipelineDecodesInputsOnceAndKeepsSceneLinearUntilPresentation() {
        val environment = shader("environment.frag")
        val waterVertex = shader("water.vert")
        val water = shader("water.frag")
        val optical = shader("optical.frag")
        val presentation = shader("present.frag")

        assertTrue(environment.contains("uSceneLinear ? srgbToLinear(uEnvironmentTop)"))
        assertFalse(environment.contains("srgbToLinear(encodedColor)"))
        assertTrue(water.contains("vec3 linearColor = uSceneLinear ? vColor : srgbToLinear(vColor)"))
        assertTrue(water.contains("waterEdgeCoverage"))
        assertTrue(water.contains("fwidth(vDepth01)"))
        assertTrue(water.contains("smoothstep(-0.5 * pixelDepth, 0.5 * pixelDepth, insideDistance)"))
        assertTrue(water.contains("edgeBehindBaseline"))
        assertTrue(water.contains("mix(edgeBehindBaseline(), outLinear, coverage)"))
        assertTrue(water.contains("vec4(max(outLinear, vec3(0.0)), 1.0)"))
        assertTrue(water.contains("uniform highp int uStartLayer"))
        assertTrue(waterVertex.contains("uniform highp int uStartLayer"))
        assertTrue(optical.contains("? srgbToLinear(vColor.rgb)"))
        assertTrue(presentation.contains("mix(srgbToLinear(uBackdropColor), sceneColor"))
    }

    @Test
    fun hdrOpticalPeakIsIndependentFromCoverageAlphaAndUsesPremultipliedSdr() {
        val source = shader("optical.frag")
        val optics = projectFile(
            "app/src/main/java/com/ywwynm/everythingdone/views/recording/" +
                "fablesol/FableSolGlOptics.kt"
        )
        val hdrBlock = source.substring(source.indexOf("if (uSceneLinear && uHdrGain"))

        assertTrue(hdrBlock.contains("uHdrCorePeak"))
        assertTrue(hdrBlock.contains("uHdrCrestPeak"))
        assertTrue(hdrBlock.contains("uHdrTransmissionPeak"))
        assertTrue(hdrBlock.contains("smoothstep(0.28, 0.82, vHdrEligibility)"))
        assertTrue(hdrBlock.contains("pow(clamp(glintCoreCoverage, 0.0, 1.0), 1.65)"))
        assertTrue(hdrBlock.contains("color * opticalAlpha + hdrExcess"))
        assertFalse(hdrBlock.contains("darkCompensation"))
        assertFalse(hdrBlock.contains("/ opticalAlpha"))
        assertFalse(hdrBlock.contains("vOpticalMode > 1.5"))
        assertFalse(hdrBlock.contains("vOpticalMode > 6.5"))
        assertTrue(optics.contains("hdrEligibility[i] = volume"))
    }

    @Test
    fun waterLayerInteriorSheenIsAbsentWhileOtherHdrOpticsRemain() {
        val vertex = shader("water.vert")
        val source = shader("water.frag")
        val renderer = projectFile(
            "app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/" +
                "FableSolGlRenderer.kt"
        )
        val hdrStart = source.indexOf("if (uSceneLinear && vFrontFill == 0")
        val bodyBlock = source.substring(source.indexOf("void main()"), hdrStart)

        assertFalse(source.contains("SUN_RADIANCE_RESPONSE"))
        assertFalse(source.contains("SDR_REFLECTION_CEILING"))
        assertFalse(source.contains("continuousHdrLocality"))
        assertFalse(source.contains("microfacetSheenExcess"))
        assertFalse(source.contains("microfacetSunResponse("))
        assertFalse(source.contains("vec2(216.0, 129.0)"))
        assertFalse(source.contains("patchPoint"))
        assertFalse(source.contains("patchMask"))
        assertFalse(source.contains("directionalSheenLocality"))
        assertFalse(source.contains("sunBoost"))
        assertFalse(bodyBlock.contains("uHdrGain"))
        assertTrue(bodyBlock.contains("vec3 outLinear = boundedReferenceWhite(linearColor)"))
        assertTrue(source.contains("? vMaterialColor"))
        assertTrue(source.contains(": srgbToLinear(vMaterialColor)"))
        assertFalse(source.contains("vec3 identityTint = vColor / maxChannel"))
        assertTrue(vertex.contains("layout(location = 4) in vec2 aSheenSlope"))
        assertFalse(vertex.contains("uniform float uHdrSheenPeaks[9]"))
        assertFalse(vertex.contains("uniform float uSunReflectionCoverageWeights[9]"))
        assertFalse(renderer.contains("uSunReflectionCoverageWeights"))
        assertFalse(renderer.contains("uHdrSheenPeaks"))
        assertFalse(renderer.contains("uSheenCoverageScale"))
        assertTrue(source.contains("backlitTransmissionExcess(transmissionFresnel)"))
    }

    @Test
    fun waterBacklitTransmissionRemainsWithoutAContinuousReflectionMask() {
        val source = shader("water.frag")
        val vertex = shader("water.vert")

        assertTrue(vertex.contains("uniform float uHdrTransmissionPeaks[9]"))
        assertTrue(vertex.contains("vHdrTransmissionPeak = sampleLayerCurve"))
        assertTrue(source.contains("backlitTransmissionExcess"))
        assertTrue(source.contains("(1.0 - fresnel) * sunriseSubsurfaceMask()"))
        assertFalse(source.contains("reflectionCompetition"))
        assertTrue(source.contains("subsurfaceLinear / maximum"))
        assertTrue(source.contains("backlitTransmissionExcess(transmissionFresnel)"))
        assertTrue(source.contains("outLinear = min(outLinear, vec3(uHdrHeadroom))"))
    }

    @Test
    fun refractionSamplesImmutableBackgroundAndBeerOnlyAffectsTransmission() {
        val vertex = shader("water.vert")
        val source = shader("water.frag")

        assertTrue(vertex.contains("out vec3 vMaterialColor"))
        assertTrue(vertex.contains("out vec3 vBehindColor"))
        assertTrue(vertex.contains("out float vMaterialOpacity"))
        assertTrue(vertex.contains("out vec2 vScreenUv"))
        assertTrue(source.contains("uniform sampler2D uPreWaterScene"))
        assertTrue(source.contains("const float WATER_IOR = 1.333"))
        assertTrue(source.contains("textureLod(uPreWaterScene, safeUv, 0.0)"))
        assertTrue(source.contains("refract(incident, normal, 1.0 / WATER_IOR)"))
        assertTrue(source.contains("vec3 beer = exp(-sigma * opticalPath)"))
        assertTrue(source.contains("mix(identity, refractedBackground, effectiveTransmission)"))
        assertTrue(source.contains("return mix(behind, volume, clamp(vMaterialOpacity"))
        assertFalse(source.contains("texture(uScene"))
    }

    private fun shader(name: String): String {
        return projectFile("shared/fablesol/glsl/$name")
    }

    private fun projectFile(relativePath: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(5) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到 $relativePath")
    }
}
