package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.view.Surface

/**
 * Dialog 粒子动画的 EGL 线程。出现与消失都使用共享的 GLES 3.1 微片模型，
 * 出现按同一模型计算的轨迹倒序播放。
 *
 * 回调在渲染线程调用，调用方负责切主线程。
 */
internal class ParticleDismissRenderer(
    private val assets: android.content.res.AssetManager,
    private val refreshRate: Float,
    private val density: Float,
    private val surfaceTexture: SurfaceTexture,
    private val viewportWidth: Int,
    private val viewportHeight: Int,
    private val spec: ParticleDismissSpec,
    private val preparation: ParticleMicroflakePreparation? = null,
    private val onFirstFrame: () -> Unit,
    private val onFinished: (completed: Boolean) -> Unit
) : Thread("ParticleDismissGl") {

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    override fun run() {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var windowSurface: Surface? = null
        var completed = false
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            windowSurface = Surface(surfaceTexture)

            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { eglFailure("eglGetDisplay") }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) {
                eglFailure("eglInitialize")
            }
            val config = chooseConfig(display)
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            check(context != EGL14.EGL_NO_CONTEXT) { eglFailure("eglCreateContext") }
            eglSurface = EGL14.eglCreateWindowSurface(
                display, config, windowSurface, intArrayOf(EGL14.EGL_NONE), 0
            )
            check(eglSurface != EGL14.EGL_NO_SURFACE) { eglFailure("eglCreateWindowSurface") }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                eglFailure("eglMakeCurrent")
            }

            completed = renderAnimation()
        } catch (error: Throwable) {
            android.util.Log.e("ParticleMicroflake", "粒子动画回退", error)
            completed = false
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, eglSurface)
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context)
                }
                EGL14.eglReleaseThread()
                // 不调用 eglTerminate：display 是进程级共享连接，terminate 会波及
                // 同时存活的其它 EGL 会话（FableSol、SpatialPhotoView）。保持
                // initialized 与 HWUI 的常驻行为一致，无资源泄漏。
            }
            windowSurface?.release()
            onFinished(completed)
        }
    }

    private fun renderAnimation(): Boolean {
        val prepareStart = System.nanoTime()
        return ParticleMicroflakeRenderer(assets, viewportWidth, viewportHeight).use { renderer ->
            renderer.preparePipeline(ParticleMicroflakeRenderer.sharedResources(assets))
            if (spec.reverse) renderer.prepareReversePipeline()
            val pipelineReady = System.nanoTime()
            if (cancelled) return@use false
            val input = preparation?.await(viewportWidth, viewportHeight)
                ?: ParticleMicroflakeRenderer.fromSpec(assets, viewportWidth, viewportHeight, density, spec)
            val inputReady = System.nanoTime()
            if (cancelled) return@use false
            renderer.prepare(input)
            if (com.ywwynm.everythingdone.BuildConfig.DEBUG) {
                android.util.Log.i(ParticleMicroflakeRenderer.TAG,
                    "准备耗时 ${(System.nanoTime() - prepareStart) / 1e6} ms，GLES ${GLES30.glGetString(GLES30.GL_VERSION)}")
            }
            val first = {
                if (com.ywwynm.everythingdone.BuildConfig.DEBUG) {
                    val stage = if (spec.reverse) "出现启动" else "启动阶段"
                    android.util.Log.i(ParticleMicroflakeRenderer.TAG,
                        "$stage buildMs=${preparation?.buildMs ?: (inputReady-prepareStart)/1e6} " +
                        "pipelineMs=${(pipelineReady-prepareStart)/1e6} waitMs=${(inputReady-pipelineReady)/1e6} " +
                        "gpuFirstMs=${(System.nanoTime()-inputReady)/1e6} " +
                        "requestToFirstMs=${(System.nanoTime()-spec.requestedAtNanos)/1e6}")
                }
                onFirstFrame()
            }
            if (spec.reverse) renderer.playReverse(spec.playbackDurationS, spec.durationScale, refreshRate, { cancelled }, first)
            else renderer.play(spec.durationScale, refreshRate, { cancelled }, first)
        }
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig {
        val attributes = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, count, 0) &&
                count[0] > 0
        ) { eglFailure("eglChooseConfig(RGBA8)") }
        return checkNotNull(configs[0])
    }

    private fun eglFailure(operation: String): String =
        "$operation failed: EGL 0x${Integer.toHexString(EGL14.eglGetError())}"

    companion object {
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
        const val TOTAL_DURATION = ParticleMicroflakeModel.DURATION
    }
}
