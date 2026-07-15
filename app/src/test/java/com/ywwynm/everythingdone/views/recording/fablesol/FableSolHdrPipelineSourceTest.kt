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
        assertTrue(source.contains("drawEnvironmentTo(sceneFramebufferId)"))
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
        val loopStart = source.indexOf("for (layer in FableSolSpec.N_LAYERS - 1 downTo 0)")
        val loopEnd = source.indexOf("layerVertexCount[layer]", loopStart)
        val loop = source.substring(loopStart, loopEnd)

        val shoulder = loop.indexOf("buildInterfaceShoulder(")
        val shadow = loop.indexOf("buildBackShade(")
        val body = loop.indexOf("buildBodyLight(")
        val thin = loop.indexOf("buildThinGlow(")
        val veil = loop.indexOf("buildCrestVeil(")
        val surface = loop.indexOf("buildSurfaceBand(")
        val streak = loop.indexOf("buildStreaks(")
        val glint = loop.indexOf("buildGlints(")
        assertTrue(listOf(shoulder, shadow, body, thin, veil, surface, streak, glint)
            .all { it >= 0 })
        assertTrue(shoulder < shadow)
        assertTrue(shadow < body)
        assertTrue(body < thin)
        assertTrue(thin < veil)
        assertTrue(veil < surface)
        assertTrue(surface < streak)
        assertTrue(streak < glint)
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
