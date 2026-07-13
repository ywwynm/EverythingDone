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
        assertFalse(source.contains("loudness01 * hdr"))
        assertFalse(source.contains("onset * hdr"))
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
