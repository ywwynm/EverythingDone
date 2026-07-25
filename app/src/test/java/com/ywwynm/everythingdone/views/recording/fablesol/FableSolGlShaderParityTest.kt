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
        // 2026-07-18：朝阳次表面散射随参数移除；subsurface 色仍供厚度透光/银边。
        assertFalse(fragment.contains("addSunriseSubsurface"))
        assertFalse(vertex.contains("uDepthScatteringStrength"))
        assertFalse(vertex.contains("vec3 depthScattering"))
    }

    @Test
    fun microNormalSystemIsFullyRemovedFromTheWaterShaders() {
        val vertex = shader("water.vert")
        val source = shader("water.frag")

        // 2026-07-18：风梳微法线/微法线带限随参数移除；valueNoiseDerivative
        // 仍供银丝滑动亮结（crestRimKnot01）使用。
        assertTrue(source.contains("valueNoiseDerivative"))
        assertFalse(source.contains("octaveBandLimit"))
        assertFalse(source.contains("windCombedMicroDerivative"))
        assertFalse(source.contains("uSpecularAaStrength"))
        assertFalse(source.contains("uMicroNormalStrength"))
        assertFalse(vertex.contains("uMicroNormalWeights"))
        assertFalse(vertex.contains("vMicroNormalWeight"))
        assertTrue(source.contains("vec2 continuousSlope = vSheenSlope;"))
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
        assertTrue(source.contains("backlitTransmissionExcess(transmissionFresnel, thicknessMask)"))
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
    fun sunriseSssIsFullyRemovedFromTheWaterShader() {
        val source = shader("water.frag")

        // 2026-07-18：朝阳次表面散射（含收束）随参数移除。
        assertFalse(source.contains("uSunSssFalloff"))
        assertFalse(source.contains("uSunSssStrength"))
        assertFalse(source.contains("sunriseSubsurfaceMask"))
        assertFalse(source.contains("vCrestPinch"))
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
        // 颜色过渡揭示门（2026-07-17 调参 Dialog）：alpha 从常量 1 改为
        // colorRevealAlpha()，uColorRevealSoftPx<=0（默认）时恒 1，逐位不变。
        assertTrue(water.contains("vec4(max(outLinear, vec3(0.0)), colorRevealAlpha())"))
        assertTrue(water.contains("if (uColorRevealSoftPx <= 0.0) return 1.0"))
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
        // D216：体光带（原 hdrEligibility[i] = volume 来源）已移除；
        // vHdrEligibility 的活体生产者只剩闪点包络。
        assertTrue(optics.contains("FableSolGlintEnvelopePolicy.hdrEligibility(intensity)"))
        assertFalse(optics.contains("buildBodyLight"))
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
        // D156：front fill 早退分支内有自己的银边 HDR excess（录音门控），
        // 是唯一被许可的例外；主光照路径（fill 分支之后）仍必须 SDR 纯净。
        val bodyBlock = source.substring(
            source.indexOf("vec2 continuousSlope"), hdrStart
        )

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
        assertTrue(source.contains("backlitTransmissionExcess(transmissionFresnel, thicknessMask)"))
    }

    @Test
    fun waterBacklitTransmissionRemainsWithoutAContinuousReflectionMask() {
        val source = shader("water.frag")
        val vertex = shader("water.vert")

        assertTrue(vertex.contains("uniform float uHdrTransmissionPeaks[9]"))
        assertTrue(vertex.contains("vHdrTransmissionPeak = sampleLayerCurve"))
        assertTrue(source.contains("backlitTransmissionExcess"))
        // 2026-07-18：日出 SSS 掩码随参数移除，HDR 透射掩码只剩厚度透光。
        assertTrue(source.contains("min(thicknessMask, 1.0)"))
        assertTrue(source.contains("0.58 * (1.0 - fresnel) * mask"))
        assertTrue(source.contains("thicknessExcessMask(normal)"))
        assertFalse(source.contains("reflectionCompetition"))
        assertTrue(source.contains("subsurfaceLinear / maximum"))
        assertTrue(source.contains("backlitTransmissionExcess(transmissionFresnel, thicknessMask)"))
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

    @Test
    fun everyQueriedUniformIsActuallyUsedInItsShaderProgram() {
        // FableSolGlProgram.uniform() 对缺失 uniform 直接 check 失败；GLES 链接器
        // 会把"声明了但没使用"的 uniform 裁出 active 列表（location = -1）。渲染器
        // 查询任何死 uniform 都会让 GL 路径每帧崩溃并静默回退 Canvas（2026-07-18
        // uTimeSeconds/uSurfaceHeadingRad 实际发生过）。这里静态钉死：查询名必须
        // 出现在对应 program 的 shader 正文（去掉 uniform 声明行）中。
        val renderer = projectFile(
            "app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/" +
                "FableSolGlRenderer.kt"
        )
        val programSources = mapOf(
            "waterProgram" to (shader("water.vert") + shader("water.frag")),
            "opticalProgram" to (shader("optical.vert") + shader("optical.frag")),
            "presentationProgram" to (shader("fullscreen.vert") + shader("present.frag")),
            "environmentProgram" to (shader("fullscreen.vert") + shader("environment.frag"))
        )
        val queries = Regex("(\\w+Program)\\.uniform\\(\"([^\"]+)\"\\)")
            .findAll(renderer)
            .map { it.groupValues[1] to it.groupValues[2] }
            .distinct()
        var checked = 0
        for ((program, uniformName) in queries) {
            val source = programSources[program] ?: continue
            val body = source.lineSequence()
                .filterNot { it.trimStart().startsWith("uniform ") }
                .joinToString("\n")
            val base = uniformName.removeSuffix("[0]")
            assertTrue("$program 查询了 shader 未使用的 uniform：$uniformName",
                body.contains(base))
            checked++
        }
        assertTrue(checked > 20)
    }

    private fun shader(name: String): String {
        return projectFile("shared/fablesol/glsl/$name")
    }

    /**
     * 读取仓库文件并把行尾规范化为 LF。
     *
     * 仓库的 `core.autocrlf=true`，工作区是 CRLF；本类有若干断言按 `"\n}\n"`
     * 之类的换行模式切函数体，直接读原文会因行尾而失配（D221 踩到）。断言的都是
     * shader 内容本身，与行尾无关，因此在入口统一规范化。
     */
    private fun projectFile(relativePath: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(5) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) {
                return candidate.readText(Charsets.UTF_8).replace("\r\n", "\n")
            }
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到 $relativePath")
    }
}
