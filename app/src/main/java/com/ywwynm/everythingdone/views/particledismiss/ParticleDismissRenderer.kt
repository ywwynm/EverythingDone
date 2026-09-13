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
    private val touchFeedback: ParticleTouchFeedback? = null,
    private val onFirstFrame: () -> Unit,
    private val onFinished: (completed: Boolean) -> Unit
) : Thread("ParticleDismissGl") {

    @Volatile
    private var cancelled = false
    private var playbackActive = false
    @Volatile private var playbackLooper: android.os.Looper? = null
    @Volatile private var firstFrameNanos = 0L
    @Volatile private var presentedFrameNanos = 0L
    private val presentationSignal = Object()
    @Volatile internal var lastFrameNanos = 0L
        private set

    internal fun progressForPresentation(timestamp: Long): Float {
        if (firstFrameNanos == 0L) return 0f
        val duration = (if (spec.reverse) spec.playbackDurationS else 1f) * spec.durationScale.coerceAtLeast(.1f)
        return ((timestamp - firstFrameNanos) / 1e9 / duration).toFloat().coerceIn(0f, 1f)
    }

    internal fun isAnimationFrame(timestamp: Long): Boolean = firstFrameNanos > 0L && timestamp >= firstFrameNanos

    internal fun framePresented(timestamp: Long, committedAt: Long) {
        synchronized(presentationSignal) {
            if (com.ywwynm.everythingdone.BuildConfig.DEBUG && timestamp > presentedFrameNanos) {
                // 记录提交回调到达及交接处理时刻；显示间隔还需结合系统跟踪核对。
                presentedTimings += longArrayOf(timestamp, committedAt, System.nanoTime())
            }
            presentedFrameNanos = maxOf(presentedFrameNanos, timestamp)
            presentationSignal.notifyAll()
        }
    }

    fun cancel() {
        cancelled = true
        playbackLooper?.quit()
        synchronized(presentationSignal) { presentationSignal.notifyAll() }
    }

    override fun run() {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var windowSurface: Surface? = null
        var completed = false
        var completionSent = false
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            android.os.Looper.prepare()
            playbackLooper = android.os.Looper.myLooper()
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
            if (completed) {
                completionSent = true
                onFinished(true)
                // disconnect 会丢弃尚未被 TextureView 取走的末帧；保持连接直到实际呈现。
                val deadline = android.os.SystemClock.uptimeMillis() + 700
                synchronized(presentationSignal) {
                    while (!cancelled && presentedFrameNanos < lastFrameNanos) {
                        val remaining = deadline - android.os.SystemClock.uptimeMillis()
                        if (remaining <= 0) break
                        presentationSignal.wait(remaining)
                    }
                }
            }
        } catch (error: Throwable) {
            android.util.Log.e("ParticleMicroflake", "粒子动画回退", error)
            completed = false
        } finally {
            if (playbackActive) ParticleGpuWork.endPlayback()
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
            if (!completionSent) onFinished(completed)
        }
    }

    private fun renderAnimation(): Boolean {
        // 准备阶段宿主也在绘制、切换窗口。没有 ripple 的出现同样要限制在途
        // 逆算批次，不能等粒子首帧才登记、让整段计算先占满 GPU 队列。
        ParticleGpuWork.beginPlayback()
        playbackActive = true
        val prepareStart = System.nanoTime()
        return ParticleMicroflakeRenderer(assets, viewportWidth, viewportHeight).use { renderer ->
            renderer.preparePipeline(ParticleMicroflakeRenderer.sharedResources(assets))
            renderer.prepareReversePipeline()
            val pipelineReady = System.nanoTime()
            if (cancelled) return@use false
            val input = preparation?.await(viewportWidth, viewportHeight)
                ?: ParticleMicroflakeRenderer.fromSpec(assets, viewportWidth, viewportHeight, density, spec)
            val inputReady = System.nanoTime()
            if (cancelled) return@use false
            renderer.prepare(input)
            if (!spec.reverse && touchFeedback?.awaitPlayback { cancelled } == false) return@use false
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
            val submitted: (Long, Float) -> Unit = { timestamp, _ ->
                if (firstFrameNanos == 0L) firstFrameNanos = timestamp
                lastFrameNanos = timestamp
                if (com.ywwynm.everythingdone.BuildConfig.DEBUG) submittedTimings += longArrayOf(timestamp, System.nanoTime())
            }
            // 出现窗口与触摸所在窗口各自呈现；准备完成即可播放，反馈仍保留 GPU 优先级。
            // 等反馈结束只适用于要移除反馈本体的消散，不能串行化工具栏反馈与新窗口出现。
            val completed = if (spec.reverse) renderer.playReverse(spec.playbackDurationS, spec.durationScale, refreshRate,
                { cancelled }, first, onSubmitted = submitted)
            else renderer.play(spec.durationScale, refreshRate, { cancelled }, first, submitted)
            drawTimings = renderer.drawTimings.toList()
            completed
        }
    }

    internal val submittedTimings: MutableList<LongArray> = java.util.Collections.synchronizedList(ArrayList())
    internal val presentedTimings: MutableList<LongArray> = java.util.Collections.synchronizedList(ArrayList())
    @Volatile internal var drawTimings: List<LongArray> = emptyList()
        private set

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
