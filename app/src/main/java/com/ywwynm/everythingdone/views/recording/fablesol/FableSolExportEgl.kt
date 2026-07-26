package com.ywwynm.everythingdone.views.recording.fablesol

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.view.Surface

/**
 * 导出用的 EGL 会话：目标是 `MediaCodec.createInputSurface()`，不是任何窗口。
 *
 * 因此 HDR 与显示器无关（fablesol-video-export D5）——只取决于 EGL 是否广告
 * `EGL_EXT_gl_colorspace_bt2020_hlg`、以及 GL 能否渲染 `GL_RGBA16F`。
 */
internal class FableSolExportEgl(
    codecSurface: Surface,
    /** true 时给表面打 BT.2020 HLG 色彩空间；建不起来直接抛，由调用方沿阶梯降级。 */
    hdr: Boolean,
    /**
     * true 时按 RGB10_A2 建表面。**它与 [hdr] 是两件事**：HEVC Main10 SDR 档同样需要
     * 10-bit 表面，否则画面先被量化到 8-bit 再交给 10-bit 编码器，既拿不到精度，又因为
     * 不算 8-bit 档而没启用抖动，色带反而更明显。
     */
    tenBit: Boolean = hdr
) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

            val config = chooseConfig(display, tenBit)
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

            val attributes = if (hdr) {
                intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_HLG_EXT, EGL14.EGL_NONE)
            } else {
                intArrayOf(EGL14.EGL_NONE)
            }
            surface = EGL14.eglCreateWindowSurface(display, config, codecSurface, attributes, 0)
            check(surface != EGL14.EGL_NO_SURFACE) {
                "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
            }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                "eglMakeCurrent failed"
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
        EGL14.eglMakeCurrent(
            display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
        )
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        surface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
    }

    private fun chooseConfig(display: EGLDisplay, tenBit: Boolean): EGLConfig {
        val componentBits = if (tenBit) 10 else 8
        val alphaBits = if (tenBit) 2 else 8
        val attributes = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, componentBits,
            EGL14.EGL_GREEN_SIZE, componentBits,
            EGL14.EGL_BLUE_SIZE, componentBits,
            EGL14.EGL_ALPHA_SIZE, alphaBits,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0
        ) { "eglChooseConfig failed (tenBit=$tenBit)" }
        return checkNotNull(configs[0])
    }

    companion object {

        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
        private const val EXTENSION_BT2020_HLG = "EGL_EXT_gl_colorspace_bt2020_hlg"
        private const val EXTENSION_COLORSPACE = "EGL_KHR_gl_colorspace"
        private const val GL_EXT_COLOR_BUFFER_HALF_FLOAT = "GL_EXT_color_buffer_half_float"
        private const val GL_EXT_COLOR_BUFFER_FLOAT = "GL_EXT_color_buffer_float"

        /**
         * 建链之前先探一次能力：EGL 是否支持 BT.2020 HLG 色彩空间，GL 能否渲染
         * `GL_RGBA16F`。用一次性的 pbuffer 上下文完成，探完立刻拆掉，不影响后续建链。
         */
        fun probe(): Capability {
            var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            var context: EGLContext = EGL14.EGL_NO_CONTEXT
            var surface: EGLSurface = EGL14.EGL_NO_SURFACE
            try {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (display == EGL14.EGL_NO_DISPLAY) return Capability(false, false)
                val version = IntArray(2)
                if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                    return Capability(false, false)
                }
                val eglExtensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: ""
                val wideColor = eglExtensions.contains(EXTENSION_COLORSPACE) &&
                    eglExtensions.contains(EXTENSION_BT2020_HLG)

                val attributes = intArrayOf(
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) ||
                    count[0] <= 0
                ) return Capability(false, wideColor)
                context = EGL14.eglCreateContext(
                    display,
                    configs[0],
                    EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                    0
                )
                if (context == EGL14.EGL_NO_CONTEXT) return Capability(false, wideColor)
                surface = EGL14.eglCreatePbufferSurface(
                    display,
                    configs[0],
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                    0
                )
                if (surface == EGL14.EGL_NO_SURFACE) return Capability(false, wideColor)
                if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                    return Capability(false, wideColor)
                }
                val glExtensions = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
                val halfFloat = glExtensions.contains(GL_EXT_COLOR_BUFFER_HALF_FLOAT) ||
                    glExtensions.contains(GL_EXT_COLOR_BUFFER_FLOAT)
                return Capability(halfFloat, wideColor)
            } catch (ignored: Throwable) {
                return Capability(false, false)
            } finally {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                    )
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(display)
                }
            }
        }
    }

    /**
     * @param linearSceneSupported 场景能否用 `GL_RGBA16F` 合成（超白值算不算得出来）。
     * @param bt2020HlgSupported EGL 能否给编码器 surface 打 BT.2020 HLG 色彩空间。
     */
    internal data class Capability(
        val linearSceneSupported: Boolean,
        val bt2020HlgSupported: Boolean
    )
}
