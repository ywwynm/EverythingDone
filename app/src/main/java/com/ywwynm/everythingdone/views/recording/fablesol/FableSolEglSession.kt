package com.ywwynm.everythingdone.views.recording.fablesol

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.view.Surface

/** SurfaceView Surface 上的 EGL ES 3.0 会话；HDR 建链失败时在原 Surface 上自动回退 SDR。 */
internal class FableSolEglSession(
    private val windowSurface: Surface,
    preferHdr: Boolean
) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    var isHdrOutput: Boolean = false
        private set

    var diagnostic: String = "sdr"
        private set

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { eglFailure("eglGetDisplay") }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) {
                eglFailure("eglInitialize")
            }

            var hdrFailure: String? = null
            if (preferHdr) {
                val eglExtensions = extensionSet(EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS))
                val missing = HDR_EGL_EXTENSIONS.filterNot(eglExtensions::contains)
                if (missing.isEmpty()) {
                    try {
                        createPipeline(hdr = true)
                        verifyHalfFloatSceneTargetSupport()
                        isHdrOutput = true
                        diagnostic = "fp16-linear-scrgb"
                    } catch (error: Throwable) {
                        hdrFailure = error.message ?: error.javaClass.simpleName
                        destroyPipeline()
                        clearEglErrors()
                    }
                } else {
                    hdrFailure = "missing ${missing.joinToString(",")}"
                }
            }

            if (!isHdrOutput) {
                createPipeline(hdr = false)
                diagnostic = if (preferHdr) "sdr-fallback: ${hdrFailure ?: "hdr unavailable"}" else "sdr"
            }
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    fun swapBuffers(presentationTimeNanos: Long): Boolean {
        EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeNanos)
        return EGL14.eglSwapBuffers(display, surface)
    }

    fun release() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        destroyPipeline()
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
    }

    private fun createPipeline(hdr: Boolean) {
        val config = chooseConfig(hdr)
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { eglFailure("eglCreateContext") }

        val surfaceAttributes = if (hdr) {
            intArrayOf(
                EGL_GL_COLORSPACE_KHR,
                EGL_GL_COLORSPACE_SCRGB_LINEAR_EXT,
                EGL14.EGL_NONE
            )
        } else {
            intArrayOf(EGL14.EGL_NONE)
        }
        surface = EGL14.eglCreateWindowSurface(
            display,
            config,
            windowSurface,
            surfaceAttributes,
            0
        )
        check(surface != EGL14.EGL_NO_SURFACE) { eglFailure("eglCreateWindowSurface") }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
            eglFailure("eglMakeCurrent")
        }
    }

    private fun chooseConfig(hdr: Boolean): EGLConfig {
        val componentBits = if (hdr) 16 else 8
        val attributes = ArrayList<Int>(20).apply {
            add(EGL14.EGL_SURFACE_TYPE)
            add(EGL14.EGL_WINDOW_BIT)
            add(EGL14.EGL_RENDERABLE_TYPE)
            add(EGL_OPENGL_ES3_BIT_KHR)
            add(EGL14.EGL_RED_SIZE)
            add(componentBits)
            add(EGL14.EGL_GREEN_SIZE)
            add(componentBits)
            add(EGL14.EGL_BLUE_SIZE)
            add(componentBits)
            add(EGL14.EGL_ALPHA_SIZE)
            add(componentBits)
            if (hdr) {
                add(EGL_COLOR_COMPONENT_TYPE_EXT)
                add(EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT)
            }
            add(EGL14.EGL_NONE)
        }.toIntArray()
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                display,
                attributes,
                0,
                configs,
                0,
                configs.size,
                count,
                0
            ) && count[0] > 0
        ) { eglFailure(if (hdr) "eglChooseConfig(FP16)" else "eglChooseConfig(RGBA8)") }
        return checkNotNull(configs[0])
    }

    private fun verifyHalfFloatSceneTargetSupport() {
        val glExtensions = extensionSet(GLES30.glGetString(GLES30.GL_EXTENSIONS))
        check(
            GL_EXT_COLOR_BUFFER_HALF_FLOAT in glExtensions ||
                GL_EXT_COLOR_BUFFER_FLOAT in glExtensions
        ) { "FP16 scene framebuffer is not color-renderable" }
    }

    private fun destroyPipeline() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(
            display,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        surface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
    }

    private fun clearEglErrors() {
        while (EGL14.eglGetError() != EGL14.EGL_SUCCESS) {
            // 清空 HDR 探测失败留下的错误，避免污染随后建立的 SDR 会话。
        }
    }

    private fun extensionSet(value: String?): Set<String> =
        value?.split(' ')?.filterTo(HashSet()) { it.isNotBlank() } ?: emptySet()

    private fun eglFailure(operation: String): String =
        "$operation failed: EGL 0x${Integer.toHexString(EGL14.eglGetError())}"

    private companion object {
        const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
        const val EGL_GL_COLORSPACE_KHR = 0x309D
        const val EGL_COLOR_COMPONENT_TYPE_EXT = 0x3339
        const val EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT = 0x333B
        const val EGL_GL_COLORSPACE_SCRGB_LINEAR_EXT = 0x3350
        const val GL_EXT_COLOR_BUFFER_HALF_FLOAT = "GL_EXT_color_buffer_half_float"
        const val GL_EXT_COLOR_BUFFER_FLOAT = "GL_EXT_color_buffer_float"

        val HDR_EGL_EXTENSIONS = setOf(
            "EGL_KHR_gl_colorspace",
            "EGL_EXT_gl_colorspace_scrgb_linear",
            "EGL_EXT_pixel_format_float"
        )
    }
}
