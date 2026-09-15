package com.ywwynm.everythingdone.views.particledismiss

import android.content.res.AssetManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log

/**
 * 进程内唯一的粒子渲染线程与 EGL 上下文（D4：全部常驻，不随动画释放）。
 *
 * - 上下文、着色器程序、累积纹理与帧缓冲、两张三维流场纹理、历史缓冲池都常驻；
 *   每个会话只建自己的材料/状态/色素/压缩 SSBO、前景纹理与压力网格，结束只删这些。
 * - 线程上始终有一个 1×1 的 pbuffer 作为空闲绘制面，所以**准备与预计算不需要窗口 Surface**，
 *   可以在 SurfaceView 的表面还没创建出来的时候就先跑起来。
 * - 一拍里依次处理各窗口的各会话，最后每个窗口只 `eglSwapBuffers` 一次。
 * - 不再每帧 `glFinish`：每次交换后放一个栅栏，下一拍开画之前等它，保证最多一帧在途。
 */
internal object ParticleGl {

    private const val MSG_PUMP = 1
    private const val MSG_POLL = 2

    /** 一次 pump 里连续做准备工作的墙钟预算；超过就让出，保证呈现回调与下一拍不被拖住。 */
    private const val WORK_BUDGET_NANOS = 8_000_000L

    private val thread: HandlerThread by lazy {
        HandlerThread("ParticleGl", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    }

    private val handler: Handler by lazy {
        Handler(thread.looper) { message ->
            if (message.what == MSG_PUMP || message.what == MSG_POLL) pump()
            true
        }
    }

    @Volatile private var beatTarget = 0L
    private var consumedTarget = 0L

    @Volatile private var clockRunning = false

    /** 只在 GL 线程访问。 */
    private val windows = ArrayList<ParticleWindowSurface>()

    var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private set
    var config: EGLConfig? = null
        private set
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var idleSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var bound: EGLSurface = EGL14.EGL_NO_SURFACE
    private var broken = false

    var shared: ParticleMicroflakeRenderer.Shared? = null
        private set

    @Volatile private var clockAttached = false

    fun post(action: () -> Unit) {
        handler.post {
            runCatching { action() }.onFailure {
                Log.e(ParticleMicroflakeRenderer.TAG, "粒子 GL 任务失败", it)
            }
        }
    }

    /**
     * 请求尽快跑一次 pump。可从任意线程调用。
     *
     * 立即与延迟用两种消息：延迟的那一条**不能**覆盖已经排好的立即消息，否则节拍线程刚投进来的
     * 一拍会被 pump 结尾的轮询重排、白白晚 2 ms。
     */
    fun wake() {
        handler.removeMessages(MSG_POLL)
        if (!handler.hasMessages(MSG_PUMP)) handler.sendEmptyMessage(MSG_PUMP)
    }

    private fun wakeLater(delayMs: Long) {
        if (handler.hasMessages(MSG_PUMP) || handler.hasMessages(MSG_POLL)) return
        handler.sendEmptyMessageDelayed(MSG_POLL, delayMs)
    }

    fun register(window: ParticleWindowSurface) {
        attachClock()
        post {
            if (!windows.contains(window)) windows += window
            wake()
        }
    }

    fun unregister(window: ParticleWindowSurface) {
        post {
            windows -= window
            window.releaseGl()
            if (bound != EGL14.EGL_NO_SURFACE && bound == window.lastEglSurface) bindIdle()
            wake()
        }
    }

    /** 内存告警：释放可再生的大块（历史缓冲池）；上下文、程序与常驻纹理不动。 */
    fun trimMemory() {
        // 没用过粒子动画的进程不必为了这一下把线程起起来。
        if (shared == null && !clockAttached) return
        post { if (windows.none { it.busy }) shared?.trim() }
    }

    private fun attachClock() {
        if (clockAttached) return
        clockAttached = true
        ParticleClock.setListener { target ->
            beatTarget = target
            wake()
        }
    }

    private fun startClock() {
        if (clockRunning) return
        clockRunning = true
        ParticleClock.start()
    }

    private fun stopClock() {
        if (!clockRunning) return
        clockRunning = false
        ParticleClock.stop()
    }

    private fun pump() {
        if (windows.isEmpty()) { stopClock(); return }
        if (!ensureContext()) {
            windows.toList().forEach { it.failAll() }
            stopClock()
            return
        }
        val target = beatTarget
        val beat = target > consumedTarget
        if (beat) consumedTarget = target

        val live = windows.toList()
        if (beat) for (window in live) window.composite(target)
        for (window in live) window.resolvePresented()

        // 准备与预计算：只要还没到下一拍就一直推进，超出预算就让出一轮。
        val deadline = System.nanoTime() + WORK_BUDGET_NANOS
        var progressed = false
        var working = true
        while (working && beatTarget <= consumedTarget && System.nanoTime() < deadline) {
            working = false
            for (window in live) if (window.advanceOnce()) { working = true; progressed = true }
        }

        for (window in windows.toList()) if (window.detached) { windows -= window; window.releaseGl() }
        if (windows.isEmpty()) { stopClock(); return }

        if (windows.any { it.needsBeat() }) startClock() else stopClock()
        val pending = windows.any { it.needsWork() }
        if (beatTarget > consumedTarget) wake()
        else if (pending && progressed) wake()
        else if (pending || windows.any { it.awaitingPresentation }) wakeLater(POLL_INTERVAL_MS)
    }

    /** 准备阶段在等外部（材料线程、触摸反馈）时的轮询间隔。 */
    private const val POLL_INTERVAL_MS = 2L

    private fun ensureContext(): Boolean {
        if (broken) return false
        if (context != EGL14.EGL_NO_CONTEXT) return true
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { eglFailure("eglGetDisplay") }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { eglFailure("eglInitialize") }
            // 常驻线程上始终要有一个可绑定的绘制面，准备与预计算才能在窗口 Surface 出现之前
            // 就跑起来。优先 WINDOW|PBUFFER 的配置；个别驱动没有这种组合时退回只要 WINDOW，
            // 空闲时用无表面绑定（EGL_KHR_surfaceless_context）。
            val chosen = chooseConfig(display, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT)
                ?: chooseConfig(display, EGL14.EGL_WINDOW_BIT)
            checkNotNull(chosen) { eglFailure("eglChooseConfig(RGBA8)") }
            config = chosen
            context = EGL14.eglCreateContext(display, chosen, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { eglFailure("eglCreateContext") }
            idleSurface = EGL14.eglCreatePbufferSurface(display, chosen,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (idleSurface == EGL14.EGL_NO_SURFACE) {
                Log.w(ParticleMicroflakeRenderer.TAG,
                    "粒子 pbuffer 不可用，改用无表面绑定 EGL 0x${Integer.toHexString(EGL14.eglGetError())}")
            }
            check(EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)) {
                eglFailure("eglMakeCurrent")
            }
            bound = idleSurface
            ParticleMicroflakeRenderer.logCapabilities()
            true
        } catch (error: Throwable) {
            Log.e(ParticleMicroflakeRenderer.TAG, "粒子 EGL 上下文建立失败", error)
            broken = true
            false
        }
    }

    /**
     * 建立/更新共享资源。尺寸变化时重建累积纹理，但只在没有会话开工时才允许——
     * 会话的 `frameWidth/frameHeight` 是按建时尺寸算的，中途换掉会让粒子错位。
     */
    fun ensureShared(assets: AssetManager, width: Int, height: Int,
                     allowResize: Boolean): ParticleMicroflakeRenderer.Shared? {
        if (!ensureContext()) return null
        val existing = shared ?: ParticleMicroflakeRenderer.Shared(assets).also { shared = it }
        if (existing.prepared && !allowResize) return existing
        return runCatching { existing.ensure(width, height); existing }.getOrElse {
            Log.e(ParticleMicroflakeRenderer.TAG, "粒子共享资源建立失败", it)
            broken = true
            null
        }
    }

    fun bind(surface: EGLSurface): Boolean {
        if (surface == bound) return true
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return false
        bound = surface
        return true
    }

    fun bindIdle() {
        if (context == EGL14.EGL_NO_CONTEXT) return
        if (EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)) bound = idleSurface
    }

    /** 该 Surface 正被绑定时先切回空闲面，调用方随后才能销毁它。 */
    fun unbindIfCurrent(surface: EGLSurface) {
        if (surface == bound) bindIdle()
    }

    private fun chooseConfig(display: EGLDisplay, surfaceType: Int): EGLConfig? {
        val attributes = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, surfaceType,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, count, 0) ||
            count[0] <= 0) return null
        return configs[0]
    }

    private fun eglFailure(operation: String): String =
        "$operation failed: EGL 0x${Integer.toHexString(EGL14.eglGetError())}"

    private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
}
