package com.ywwynm.everythingdone.hdr

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ln
import kotlin.math.cos

/** 查看器与对照页共享的独立输出层。扩展缺失只记录，实际格式必须严格校验。 */
internal class HdrProbeSurface(context: Context, private val data: HdrGpuData,
    private val output: Output, private val gainHeadroom: Float, requestedHeadroom: Float,
    private val report: (String, String, Boolean) -> Unit) : SurfaceView(context), SurfaceHolder.Callback2 {

    enum class Output { SCRGB, PQ, SDR }
    private val worker = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "hdr-egl-probe") }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    private val lock = Any()
    @Volatile private var live = false
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private val textures = IntArray(2)
    private var stage = "not_started"
    private var surfaceCreationAttempted = false
    private val drawQueued = AtomicBoolean(false)
    @Volatile private var imageTransform: FloatArray? = null
    @Volatile private var revision = 0
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0
    @Volatile private var frameFormat = 0
    @Volatile private var failed = false
    @Volatile private var surfaceGeneration = 0
    private var submitted = false
    private val createdNanos = System.nanoTime()
    private var setupMs = 0L
    @Volatile private var hdrStrength = 1f
    @Volatile var isFrameReady = false
        private set
    @Volatile var readyUptimeMs = 0L
        private set
    @Volatile var lastSubmittedStrength = Float.NaN
        private set
    @Volatile var firstGainUptimeMs = 0L
        private set
    @Volatile var fullGainUptimeMs = 0L
        private set
    val currentHdrStrength: Float get() = hdrStrength
    private val redrawLock = Any()
    private val redrawCompletions = mutableListOf<Runnable>()
    private val gainLock = Any()
    private data class GainTransition(val from: Float, val to: Float, val durationNanos: Long, var startedNanos: Long)
    private var gainTransition: GainTransition? = null
    private val animationFrameQueued = AtomicBoolean(false)

    /** SDR 也保持同一 PQ 容器，仅调整重建强度，不触发 Surface／EGL 重建。 */
    fun setHdrStrength(strength: Float) {
        val next = strength.coerceIn(0f, 1f)
        synchronized(gainLock) {
            gainTransition = null
            if (hdrStrength == next) return
            hdrStrength = next
        }
        revision++
        requestDraw()
    }

    /** 首帧完成同步后直接在渲染线程推进，不等待主线程的 ready 回调或动画帧。 */
    fun animateHdrStrength(strength: Float, durationMs: Long) {
        val next = strength.coerceIn(0f, 1f)
        synchronized(gainLock) {
            if (gainTransition?.to == next) return
            gainTransition = if (hdrStrength == next) null else GainTransition(hdrStrength, next,
                durationMs.coerceAtLeast(1) * 1_000_000, if (isFrameReady) System.nanoTime() else 0L)
        }
        revision++
        requestDraw()
    }

    fun freezeHdrStrength() {
        synchronized(gainLock) {
            gainTransition = null
            lastSubmittedStrength.takeIf { it.isFinite() }?.let { hdrStrength = it }
        }
    }

    private fun frameStrength(): Float = synchronized(gainLock) {
        val transition = gainTransition
        if (transition != null && isFrameReady) {
            val now = System.nanoTime()
            if (transition.startedNanos == 0L) transition.startedNanos = now
            val progress = ((now - transition.startedNanos).toDouble() / transition.durationNanos).coerceIn(0.0, 1.0)
            val eased = ((1.0 - cos(Math.PI * progress)) * 0.5).toFloat()
            hdrStrength = if (progress == 1.0) transition.to else transition.from + (transition.to - transition.from) * eased
            if (progress == 1.0) gainTransition = null
        }
        hdrStrength
    }

    private fun scheduleGainFrame() {
        if (!live || failed || !isFrameReady || worker.isShutdown ||
            synchronized(gainLock) { gainTransition == null } || !animationFrameQueued.compareAndSet(false, true)) return
        try {
            worker.schedule({
                animationFrameQueued.set(false)
                if (synchronized(gainLock) { gainTransition != null }) requestDraw()
            }, 8L, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) { animationFrameQueued.set(false) }
    }

    /** 只更新坐标并合并待绘帧；手势中不重新解码、上传纹理或创建 EGL。 */
    fun setImageTransform(matrix: FloatArray) {
        if (imageTransform?.contentEquals(matrix) == true) return
        imageTransform = matrix.copyOf()
        revision++
        requestDraw()
    }

    init {
        holder.setFormat(when (output) {
            Output.SCRGB -> PixelFormat.RGBA_F16
            Output.PQ -> PixelFormat.RGBA_1010102
            Output.SDR -> PixelFormat.RGBA_8888
        })
        if (Build.VERSION.SDK_INT >= 35) {
            runCatching { setDesiredHdrHeadroom(if (output == Output.SDR) 1f else requestedHeadroom) }
                .onFailure { report("SURFACE_HEADROOM_ERROR", it.stackTraceToString(), false) }
        }
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceGeneration++
        live = true; failed = false; submitted = false; isFrameReady = false; readyUptimeMs = 0L
        firstGainUptimeMs = 0L; fullGainUptimeMs = 0L
    }

    override fun surfaceRedrawNeededAsync(holder: SurfaceHolder, drawingFinished: Runnable) {
        if (!live || failed || worker.isShutdown) { drawingFinished.run(); return }
        val finished = AtomicBoolean(false)
        synchronized(redrawLock) { redrawCompletions.add(Runnable {
            if (finished.compareAndSet(false, true)) drawingFinished.run()
        }) }
        revision++
        requestDraw()
    }

    // 本路径要求 API 34+；系统使用上面的异步协议，主线程不等待 EGL。
    override fun surfaceRedrawNeeded(holder: SurfaceHolder) { revision++; requestDraw() }

    private fun takeRedrawCompletions(): List<Runnable> = synchronized(redrawLock) {
        redrawCompletions.toList().also { redrawCompletions.clear() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        frameWidth = width; frameHeight = height; frameFormat = format
        revision++
        requestDraw()
    }

    private fun requestDraw() {
        if (!live || failed || frameWidth <= 0 || frameHeight <= 0 || worker.isShutdown || !drawQueued.compareAndSet(false, true)) return
        try { worker.execute {
            var drawnRevision = -1
            var completions = emptyList<Runnable>()
            var drewFrame = false
            var drawingGeneration = -1
            try {
                synchronized(lock) {
                    if (!live || !holder.surface.isValid) return@synchronized
                    drawnRevision = revision
                    drawingGeneration = surfaceGeneration
                    // 只完成本次绘制之前的请求，绘制期间收到的新尺寸请求留给下一帧。
                    completions = takeRedrawCompletions()
                    try {
                        if (eglContext == EGL14.EGL_NO_CONTEXT) {
                            val started = System.nanoTime()
                            initialize()
                            setupMs = (System.nanoTime() - started) / 1_000_000
                        }
                        draw(frameWidth, frameHeight)
                        drewFrame = true
                        if (!submitted) {
                            submitted = true
                            report("EGL_FRAME_SUBMITTED", "output=$output; surface=${frameWidth}x$frameHeight; holderFormat=$frameFormat; " +
                                "setupMs=$setupMs; sinceViewMs=${(System.nanoTime() - createdNanos) / 1_000_000}; " +
                                "gainHeadroom=$gainHeadroom; ${data.description}\n" +
                                "成功提交帧不代表宿主合成或屏幕已显示 HDR，需记录肉眼观感。", true)
                        }
                    } catch (failure: Throwable) {
                        failed = true
                        completions += takeRedrawCompletions()
                        report("EGL_FAILED", "output=$output; stage=$stage; surfaceCreationAttempted=$surfaceCreationAttempted\n${failure.stackTraceToString()}", false)
                        releaseEgl()
                    }
                }
                // 框架可能等待 BLAST 的事务；绝不持有 EGL 生命周期锁调用完成回调。
                completions.forEach { it.run() }
                if (drewFrame && completions.isNotEmpty() && live && !failed && !isFrameReady && drawingGeneration == surfaceGeneration) {
                    readyUptimeMs = android.os.SystemClock.uptimeMillis()
                    isFrameReady = true
                    synchronized(gainLock) { gainTransition?.takeIf { it.startedNanos == 0L }?.startedNanos = System.nanoTime() }
                    report("EGL_REDRAW_READY", "output=$output; strength=$lastSubmittedStrength; protocol=SurfaceHolder.Callback2", true)
                }
            } finally {
                drawQueued.set(false)
                if (drawnRevision != revision) requestDraw()
                else scheduleGainFrame()
            }
        } } catch (_: RejectedExecutionException) {
            // onDetachedFromWindow 可能恰好关闭了执行器；Surface 已失效，不再排帧。
            drawQueued.set(false)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        live = false
        surfaceGeneration++
        isFrameReady = false
        // 返回前停止使用 Surface，避免后台线程继续向已经销毁的窗口提交帧。
        synchronized(lock) { releaseEgl() }
        takeRedrawCompletions().forEach { it.run() }
    }

    override fun onDetachedFromWindow() {
        live = false
        worker.shutdownNow()
        synchronized(lock) { releaseEgl() }
        takeRedrawCompletions().forEach { it.run() }
        super.onDetachedFromWindow()
    }

    private fun initialize() {
        stage = "eglInitialize"
        surfaceCreationAttempted = false
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { eglError("eglGetDisplay") }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { eglError("eglInitialize") }
        val extensions = EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS).orEmpty()
        report("EGL_CAPABILITIES", "vendor=${EGL14.eglQueryString(eglDisplay, EGL14.EGL_VENDOR)}; " +
            "version=${EGL14.eglQueryString(eglDisplay, EGL14.EGL_VERSION)}\nextensions=$extensions", true)
        val required = when (output) {
            Output.SCRGB -> listOf("EGL_KHR_gl_colorspace", "EGL_EXT_gl_colorspace_scrgb_linear", "EGL_EXT_pixel_format_float")
            Output.PQ -> listOf("EGL_KHR_gl_colorspace", "EGL_EXT_gl_colorspace_bt2020_pq")
            Output.SDR -> emptyList()
        }
        val missing = required.filterNot { it in extensions.split(' ') }
        report("EGL_EXTENSION_PREFLIGHT_BYPASSED", "output=$output; advertisedMissing=$missing; " +
            "policy=attempt_even_when_unadvertised; 不以扩展列表或 Display.isHdr 阻止创建。", true)
        check(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)) { eglError("eglBindAPI") }
        val rgbBits = when (output) { Output.SCRGB -> 16; Output.PQ -> 10; Output.SDR -> 8 }
        val alphaBits = if (output == Output.PQ) 2 else rgbBits
        val attributes = mutableListOf(EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_RED_SIZE, rgbBits,
            EGL14.EGL_GREEN_SIZE, rgbBits, EGL14.EGL_BLUE_SIZE, rgbBits, EGL14.EGL_ALPHA_SIZE, alphaBits)
        if (output == Output.SCRGB) attributes.addAll(listOf(0x3339, 0x333B))
        attributes.add(EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(64)
        val count = IntArray(1)
        eglAttempt("eglChooseConfig", "attributes=${attributes.hexAttributes()}", { it }) {
            EGL14.eglChooseConfig(eglDisplay, attributes.toIntArray(), 0, configs, 0, configs.size, count, 0)
        }
        report("EGL_CONFIG_COUNT", "matched=${count[0]}; returnedCapacity=${configs.size}", true)
        stage = "selectExactConfig"
        fun value(config: EGLConfig, key: Int): Int {
            val result = IntArray(1)
            check(EGL14.eglGetConfigAttrib(eglDisplay, config, key, result, 0)) { eglError("eglGetConfigAttrib") }
            return result[0]
        }
        val config = configs.take(minOf(count[0], configs.size)).filterNotNull().firstOrNull {
            value(it, EGL14.EGL_RED_SIZE) == rgbBits && value(it, EGL14.EGL_GREEN_SIZE) == rgbBits &&
                value(it, EGL14.EGL_BLUE_SIZE) == rgbBits && value(it, EGL14.EGL_ALPHA_SIZE) == alphaBits
        } ?: error("没有满足精确通道位数的 EGL 配置：RGBA=$rgbBits/$rgbBits/$rgbBits/$alphaBits")
        if (output == Output.SCRGB) check(value(config, 0x3339) == 0x333B) { "EGL 配置不是浮点格式" }
        report("EGL_CONFIG_SELECTED", "configId=${value(config, EGL14.EGL_CONFIG_ID)}; " +
            "RGBA=$rgbBits/$rgbBits/$rgbBits/$alphaBits; componentType=${if (output == Output.SCRGB) "FLOAT" else "FIXED"}", true)
        eglContext = eglAttempt("eglCreateContext", "clientVersion=3", { it != EGL14.EGL_NO_CONTEXT }) {
            EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        }
        val colorSpace = when (output) { Output.SCRGB -> 0x3350; Output.PQ -> 0x3340; Output.SDR -> 0 }
        val surfaceAttributes = if (output == Output.SDR) intArrayOf(EGL14.EGL_NONE)
            else intArrayOf(0x309D, colorSpace, EGL14.EGL_NONE)
        surfaceCreationAttempted = true
        eglSurface = eglAttempt("eglCreateWindowSurface", "attributes=${surfaceAttributes.toList().hexAttributes()}; " +
            "advertisedMissing=$missing; nativeSurfaceValid=${holder.surface.isValid}", { it != EGL14.EGL_NO_SURFACE }) {
            EGL14.eglCreateWindowSurface(eglDisplay, config, holder.surface, surfaceAttributes, 0)
        }
        eglAttempt("eglMakeCurrent", "", { it }) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }
        stage = "eglQuerySurfaceColorspace"
        val actualColorSpace = IntArray(1)
        EGL14.eglGetError()
        val queried = EGL14.eglQuerySurface(eglDisplay, eglSurface, 0x309D, actualColorSpace, 0)
        val queryError = EGL14.eglGetError()
        report("EGL_ACTUAL_FORMAT", "RGBA=$rgbBits/$rgbBits/$rgbBits/$alphaBits; colorspaceQuery=$queried; " +
            "colorspace=0x${actualColorSpace[0].toString(16)}; requested=0x${colorSpace.toString(16)}; queryError=${errorName(queryError)}\n" +
            "GL_VENDOR=${GLES30.glGetString(GLES30.GL_VENDOR)}\nGL_RENDERER=${GLES30.glGetString(GLES30.GL_RENDERER)}\n" +
            "GL_VERSION=${GLES30.glGetString(GLES30.GL_VERSION)}\nGL_EXTENSIONS=${GLES30.glGetString(GLES30.GL_EXTENSIONS)}", true)
        if (output != Output.SDR) check(queried && queryError == EGL14.EGL_SUCCESS && actualColorSpace[0] == colorSpace) {
            "EGL 色彩空间未确认：requested=0x${colorSpace.toString(16)} actual=0x${actualColorSpace[0].toString(16)}; ${errorName(queryError)}"
        }
        stage = "shaderAndTextureUpload"
        program = link(VERTEX, FRAGMENT)
        GLES30.glGenTextures(2, textures, 0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        for (index in 0..1) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + index)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[index])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            if (index == 0) {
                data.base.rewind()
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0,
                    if (data.srgbTexture) GLES30.GL_SRGB8_ALPHA8 else GLES30.GL_RGBA16F, data.width, data.height,
                    0, GLES30.GL_RGBA, if (data.srgbTexture) GLES30.GL_UNSIGNED_BYTE else GLES30.GL_HALF_FLOAT, data.base)
            } else {
                data.gain.rewind()
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, data.gainWidth, data.gainHeight,
                    0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, data.gain)
            }
        }
        checkGl("texture upload")
    }

    private fun draw(width: Int, height: Int) {
        stage = "draw"
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { eglError("eglMakeCurrent draw") }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DITHER)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        fun location(name: String) = GLES30.glGetUniformLocation(program, name)
        val scale = minOf(width.toFloat() / data.width, height.toFloat() / data.height)
        val matrix = imageTransform ?: HdrViewerGeometry.transform(floatArrayOf(
            scale, 0f, (width - data.width * scale) / 2f,
            0f, scale, (height - data.height * scale) / 2f, 0f, 0f, 1f
        ), data.width, data.height, width, height)
        GLES30.glUniformMatrix3fv(location("imageTransform"), 1, false, matrix, 0)
        GLES30.glUniform1i(location("baseTexture"), 0)
        GLES30.glUniform1i(location("gainTexture"), 1)
        GLES30.glUniform1i(location("outputMode"), when (output) { Output.SCRGB -> 0; Output.PQ -> 1; Output.SDR -> 2 })
        GLES30.glUniform1f(location("weight"), if (output == Output.SDR) 0f else HdrProbeMath.weight(gainHeadroom, data.minimum, data.full))
        val frameStrength = frameStrength()
        GLES30.glUniform1f(location("hdrStrength"), frameStrength)
        fun vector(name: String, values: FloatArray) = GLES30.glUniform3fv(location(name), 1, values, 0)
        vector("logMin", data.ratioMin.map { ln(it) }.toFloatArray())
        vector("logMax", data.ratioMax.map { ln(it) }.toFloatArray())
        vector("gainGamma", data.gamma)
        vector("epsilonSdr", data.epsilonSdr)
        vector("epsilonHdr", data.epsilonHdr)
        GLES30.glUniformMatrix3fv(location("baseToGain"), 1, false, data.baseToMath, 0)
        GLES30.glUniformMatrix3fv(location("colorTransform"), 1, false, data.matrix, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("draw")
        check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { eglError("eglSwapBuffers") }
        lastSubmittedStrength = frameStrength
        val now = android.os.SystemClock.uptimeMillis()
        if (frameStrength > 0f && firstGainUptimeMs == 0L) {
            firstGainUptimeMs = now
            report("EGL_FIRST_GAIN_FRAME", "readyToGainMs=${if (readyUptimeMs > 0) now - readyUptimeMs else 0}; strength=$frameStrength", true)
        }
        if (frameStrength == 1f && fullGainUptimeMs == 0L) {
            fullGainUptimeMs = now
            report("EGL_FULL_GAIN_FRAME", "readyToFullGainMs=${if (readyUptimeMs > 0) now - readyUptimeMs else 0}", true)
        }
        // 释放线程绑定，允许生命周期回调在同一锁内安全销毁上下文。
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    }

    private fun link(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                error("GLSL compile failed: $log")
            }
            return shader
        }
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
        var fs = 0
        val result = GLES30.glCreateProgram()
        try {
            fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
            GLES30.glAttachShader(result, vs)
            GLES30.glAttachShader(result, fs)
            GLES30.glLinkProgram(result)
            val status = IntArray(1)
            GLES30.glGetProgramiv(result, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "GLSL link failed: ${GLES30.glGetProgramInfoLog(result)}" }
            return result
        } catch (failure: Throwable) { GLES30.glDeleteProgram(result); throw failure }
        finally { GLES30.glDeleteShader(vs); if (fs != 0) GLES30.glDeleteShader(fs) }
    }

    private fun releaseEgl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            if (eglSurface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                if (program != 0) GLES30.glDeleteProgram(program)
                GLES30.glDeleteTextures(2, textures, 0)
            }
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
        }
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglTerminate(eglDisplay)
        EGL14.eglReleaseThread()
        eglContext = EGL14.EGL_NO_CONTEXT; eglSurface = EGL14.EGL_NO_SURFACE; eglDisplay = EGL14.EGL_NO_DISPLAY
        program = 0; textures.fill(0)
    }

    private fun eglError(operation: String) = "$operation EGL error=0x${EGL14.eglGetError().toString(16)}"
    private fun List<Int>.hexAttributes() = joinToString { "0x${it.toString(16)}" }

    private fun <T> eglAttempt(operation: String, details: String, valid: (T) -> Boolean, call: () -> T): T {
        stage = operation
        EGL14.eglGetError() // 清除旧错误；结果必须在下一次 EGL 调用之前读取。
        report("EGL_CALL_ATTEMPT", "operation=$operation; $details", true)
        val result = call()
        val error = EGL14.eglGetError()
        val success = valid(result) && error == EGL14.EGL_SUCCESS
        report("EGL_CALL_RESULT", "operation=$operation; validResult=${valid(result)}; error=${errorName(error)}", success)
        check(success) { "$operation failed: ${errorName(error)}" }
        return result
    }

    private fun errorName(error: Int): String {
        val name = when (error) {
            0x3000 -> "EGL_SUCCESS"; 0x3001 -> "EGL_NOT_INITIALIZED"; 0x3002 -> "EGL_BAD_ACCESS"
            0x3003 -> "EGL_BAD_ALLOC"; 0x3004 -> "EGL_BAD_ATTRIBUTE"; 0x3005 -> "EGL_BAD_CONFIG"
            0x3006 -> "EGL_BAD_CONTEXT"; 0x3007 -> "EGL_BAD_CURRENT_SURFACE"; 0x3008 -> "EGL_BAD_DISPLAY"
            0x3009 -> "EGL_BAD_MATCH"; 0x300A -> "EGL_BAD_NATIVE_PIXMAP"; 0x300B -> "EGL_BAD_NATIVE_WINDOW"
            0x300C -> "EGL_BAD_PARAMETER"; 0x300D -> "EGL_BAD_SURFACE"; 0x300E -> "EGL_CONTEXT_LOST"
            else -> "EGL_UNKNOWN"
        }
        return "$name(0x${error.toString(16)})"
    }
    private fun checkGl(operation: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "$operation GL error=0x${error.toString(16)}" }
    }

    companion object {
        const val VERTEX = """#version 300 es
precision highp float;
uniform mat3 imageTransform;
out vec2 uv;
void main() {
    vec2 p = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    uv = vec2(p.x, 1.0 - p.y);
    vec3 position = imageTransform * vec3(uv, 1.0);
    gl_Position = vec4(position.xy, 0.0, position.z);
}
"""
        const val FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D baseTexture;
uniform sampler2D gainTexture;
uniform vec3 logMin, logMax, gainGamma, epsilonSdr, epsilonHdr;
uniform mat3 baseToGain, colorTransform;
uniform float weight, hdrStrength;
uniform int outputMode;
in vec2 uv;
out vec4 color;
vec3 pq(vec3 relativeLinear) {
    // 此探针固定 SDR 参考白为 203 nit，不冒充设备实际参考白测量。
    vec3 l = pow(clamp(relativeLinear * (203.0 / 10000.0), 0.0, 1.0), vec3(2610.0 / 16384.0));
    return pow((vec3(3424.0 / 4096.0) + (2413.0 / 128.0) * l) /
        (vec3(1.0) + (2392.0 / 128.0) * l), vec3(2523.0 / 32.0));
}
vec3 srgb(vec3 linearValue) {
    vec3 c = clamp(linearValue, 0.0, 1.0);
    return mix(12.92 * c, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c));
}
void main() {
    vec3 base = baseToGain * texture(baseTexture, uv).rgb;
    vec3 result = base;
    if (outputMode != 2) {
        vec3 gain = pow(clamp(texture(gainTexture, uv).rgb, 0.0, 1.0), gainGamma);
        // 强度为零时必须精确返回 SDR 基图，不能残留不同 epsilon 的偏移。
        float strength = clamp(hdrStrength, 0.0, 1.0);
        result = (base + epsilonSdr * strength) * exp(mix(logMin, logMax, gain) * weight * strength) - epsilonHdr * strength;
    }
    result = colorTransform * result;
    if (outputMode == 1) result = pq(result);
    else if (outputMode == 2) result = srgb(result);
    color = vec4(result, 1.0);
}
"""
    }
}
