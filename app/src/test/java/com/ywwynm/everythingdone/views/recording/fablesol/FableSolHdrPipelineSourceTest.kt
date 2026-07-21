package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FableSolHdrPipelineSourceTest {

    @Test
    fun eglRequestsFp16LinearScrgbAndKeepsAnRgba8Fallback() {
        val source = source("FableSolEglSession.kt")

        assertTrue(source.contains("EGL_EXT_pixel_format_float"))
        assertTrue(source.contains("EGL_EXT_gl_colorspace_scrgb_linear"))
        assertTrue(source.contains("EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT"))
        assertTrue(source.contains("EGL_GL_COLORSPACE_SCRGB_LINEAR_EXT"))
        assertTrue(source.contains("createPipeline(hdr = false)"))
    }

    @Test
    fun rendererUsesFp16OnlyForHdrSceneAndSdrNeverGeneratesExcess() {
        val source = source("FableSolGlRenderer.kt")

        assertTrue(source.contains("GLES30.GL_RGBA16F"))
        assertTrue(source.contains("FableSolHdrPolicy.advanceHeadroom"))
        assertTrue(source.contains("hdrContentEnabled && hdrRecordingRequested"))
        assertTrue(source.contains("FableSolLayerColorPolicy.palette("))
        assertTrue(source.contains("palette.interfaceWeights.start"))
        assertTrue(source.contains("palette.interfaceWeights.stop1"))
        assertTrue(source.contains("palette.interfaceWeights.stop2"))
        assertTrue(source.contains("palette.interfaceWeights.end"))
        assertFalse(source.contains("quantizedDeltaEOk"))
        assertFalse(source.contains("loudness01 * hdr"))
        assertFalse(source.contains("onset * hdr"))
    }

    @Test
    fun refractionUsesASeparateImmutableBackgroundTargetWithAtomicFallback() {
        val source = source("FableSolGlRenderer.kt")

        assertTrue(source.contains("private var preWaterFramebufferId = 0"))
        assertTrue(source.contains("private var preWaterTextureId = 0"))
        assertTrue(source.contains("drawEnvironmentTo(preWaterFramebufferId)"))
        // MSAA 场景 resolve 回单采样 sceneFramebufferId，折射仍采样这张独立不可变背景。
        assertTrue(source.contains("drawEnvironmentTo(sceneDrawFramebufferId)"))
        assertTrue(source.contains("GLES30.GL_DRAW_FRAMEBUFFER, sceneFramebufferId"))
        assertTrue(source.contains("GLES30.glActiveTexture(GLES30.GL_TEXTURE1)"))
        assertTrue(source.contains("waterProgram.uniform(\"uPreWaterScene\"), 1"))
        assertTrue(source.contains("GLES30.GL_LINEAR"))
        assertTrue(source.contains("val textures = IntArray(2)"))
        assertTrue(source.contains("val framebuffers = IntArray(2)"))
        assertTrue(source.contains("val requestedHdrTargets = hdrContentEnabled"))
        assertTrue(source.contains("hdrContentEnabled = false"))
        assertTrue(source.contains("releaseSceneTargets()"))
        val fallback = source.substring(
            source.indexOf("val requestedHdrTargets = hdrContentEnabled"),
            source.indexOf("private fun createSceneTargets")
        )
        assertFalse(fallback.contains("sceneLinear = false"))
    }

    @Test
    fun surfaceRequestsHeadroomOnlyOnSupportedApisAndRecordingStateOwnsTheSwitch() {
        val source = source("WaveVisualizerFableSolGl.kt")

        assertTrue(source.contains("Build.VERSION.SDK_INT < 34"))
        assertTrue(source.contains("isHdrSdrRatioAvailable"))
        assertTrue(source.contains("Build.VERSION.SDK_INT < 35"))
        assertTrue(source.contains("setDesiredHdrHeadroom(value)"))
        assertTrue(source.contains("setRecordingHdrActive"))
    }

    @Test
    fun opticalBuilderKeepsVolumeBehindSurfaceAndGlintsLast() {
        val source = source("FableSolGlOptics.kt")
        // C2 之后逐层构面搬进 LayerScratch.buildLayer（层循环本身只剩压实），
        // 守的仍是同一条契约：单层内部的发射顺序，以及已移除的建带函数不得复活。
        val loopStart = source.indexOf("fun buildLayer(")
        val loopEnd = source.indexOf("segmentFloatCount = cursor - segmentStart", loopStart)
        assertTrue(loopStart >= 0 && loopEnd > loopStart)
        val loop = source.substring(loopStart, loopEnd)

        // D160：表面亮带（buildSurfaceBand）已整项移除；2026-07-18 薄峰透光/
        // 波冠轻纱/流光随参数删除；波背自阴影经 D169 恢复，序列为
        // 界面肩→波背暗带→体光→闪点（暗带不得压脏其后的光学分瓣）。
        val shoulder = loop.indexOf("buildInterfaceShoulder(")
        val backShade = loop.indexOf("buildBackShade(")
        val body = loop.indexOf("buildBodyLight(")
        val glint = loop.indexOf("buildGlints(")
        assertTrue(listOf(shoulder, backShade, body, glint).all { it >= 0 })
        assertTrue(loop.indexOf("buildSurfaceBand(") < 0)
        assertTrue(loop.indexOf("buildThinGlow(") < 0)
        assertTrue(loop.indexOf("buildCrestVeil(") < 0)
        assertTrue(loop.indexOf("buildStreaks(") < 0)
        assertTrue(shoulder < backShade)
        assertTrue(backShade < body)
        assertTrue(body < glint)
    }

    private fun source(name: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(5) {
            val candidate = File(
                directory,
                "app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/$name"
            )
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile ?: return@repeat
        }
        error("找不到 FableSol 源文件 $name")
    }
}
