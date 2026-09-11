package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Dialog 粒子动画的 EGL 线程。消失使用共享的 GLES 3.1 固定步长微片模型，
 * 出现保留既有 GLES 3.0 凝聚模型；两条路径只共用窗口和生命周期管理。
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
        if (spec.condenseFromT == null) {
            val prepareStart = System.nanoTime()
            val input = preparation?.await(viewportWidth, viewportHeight)
                ?: ParticleMicroflakeRenderer.fromSpec(assets, viewportWidth, viewportHeight, density, spec)
            val inputReady = System.nanoTime()
            return ParticleMicroflakeRenderer(assets, viewportWidth, viewportHeight, input).use { renderer ->
                renderer.prepare()
                if (com.ywwynm.everythingdone.BuildConfig.DEBUG) {
                    android.util.Log.i(ParticleMicroflakeRenderer.TAG,
                        "准备耗时 ${(System.nanoTime() - prepareStart) / 1e6} ms，GLES ${GLES30.glGetString(GLES30.GL_VERSION)}")
                }
                renderer.play(spec.durationScale, refreshRate, { cancelled }) {
                    if (com.ywwynm.everythingdone.BuildConfig.DEBUG) {
                        android.util.Log.i(ParticleMicroflakeRenderer.TAG,
                            "启动阶段 buildMs=${preparation?.buildMs ?: (inputReady-prepareStart)/1e6} " +
                            "waitMs=${(inputReady-prepareStart)/1e6} " +
                            "gpuFirstMs=${(System.nanoTime()-inputReady)/1e6} " +
                            "requestToFirstMs=${(System.nanoTime()-spec.requestedAtNanos)/1e6}")
                    }
                    onFirstFrame()
                }
            }
        }
        val snapshot = spec.snapshot
        var sweepDx = spec.virtualTouchXPx - (spec.originXPx + spec.snapshot.width / 2f)
        var sweepDy = spec.virtualTouchYPx - (spec.originYPx + spec.snapshot.height / 2f)
        val sweepLen = kotlin.math.hypot(sweepDx, sweepDy)
        if (sweepLen > 1f) {
            sweepDx /= sweepLen
            sweepDy /= sweepLen
        } else {
            sweepDx = 0f
            sweepDy = -1f
        }
        // 出现路径保留原有点粒子与完整静止层。
        val cellPx = spec.cellPx
        val program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        val stillProgram = createProgram(STILL_VERTEX_SHADER, STILL_FRAGMENT_SHADER)
        val cols = ceil(snapshot.width / cellPx).toInt().coerceAtLeast(1)
        val rows = ceil(snapshot.height / cellPx).toInt().coerceAtLeast(1)
        val stillVertices = 4
        val stillPrimitive = GLES30.GL_TRIANGLE_STRIP
        val particleCount = cols * rows
        val replicas = REPLICAS

        val texture = IntArray(1)
        GLES30.glGenTextures(1, texture, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0])
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE
        )
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, snapshot, 0)

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        // shader 输出预乘 alpha，与 TextureView 的合成预期一致
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES30.glClearColor(0f, 0f, 0f, 0f)

        val pointSizeRange = FloatArray(2)
        GLES30.glGetFloatv(GLES30.GL_ALIASED_POINT_SIZE_RANGE, pointSizeRange, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uSnapshot"), 0)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uViewportPx"),
            viewportWidth.toFloat(), viewportHeight.toFloat()
        )
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uOriginPx"), spec.originXPx, spec.originYPx
        )
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uSnapshotPx"),
            snapshot.width.toFloat(), snapshot.height.toFloat()
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uCellPx"), cellPx)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(program, "uGrid"), cols, rows)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uWaveOriginUv"),
            spec.waveOriginUv.x, spec.waveOriginUv.y
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uSpreadTime"), spec.spreadTime)
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(program, "uDelayJitter"), spec.delayJitter
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uLifetime"), LIFETIME)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uDriftPx"), spec.driftPx)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uVirtualTouchPx"),
            spec.virtualTouchXPx, spec.virtualTouchYPx
        )
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uLightPx"),
            spec.lightXPx, spec.lightYPx
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(program, "uNoiseScalePx"), spec.noiseScalePx
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uSwirl"), SWIRL)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uFlowEvolve"), FLOW_EVOLVE)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uPinch"), PINCH)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uKick"), KICK)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uWaveWarp"), spec.waveWarp)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uFlarePx"), spec.flarePx)
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(program, "uPinchMaxPx"), spec.pinchMaxPx
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(program, "uTurbPx"), cellPx * TURBULENCE_CELLS
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(program, "uMaxPointPx"),
            pointSizeRange[1].coerceAtLeast(1f)
        )
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uNoiseSeed"),
            spec.noiseSeedX, spec.noiseSeedY
        )
        GLES30.glUniform1ui(GLES30.glGetUniformLocation(program, "uHashSeed"), spec.hashSeed)
        GLES30.glUniform3f(
            GLES30.glGetUniformLocation(program, "uPanelColor"),
            ((spec.panelColor shr 16) and 0xFF) / 255f,
            ((spec.panelColor shr 8) and 0xFF) / 255f,
            (spec.panelColor and 0xFF) / 255f
        )
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uReplicas"), replicas)
        // 蓝本用它出 A/B 对比；应用端恒为 1（不设档位，与蓝本 GLSL 保持一致）
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uContentBoost"), 1f)
        // 保留出现路径的既有参数。
        val sweepTime = 0f
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uSweepTime"), sweepTime)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uSweepDir"), sweepDx, sweepDy)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uArcBow"), 0f)
        val timeLocation = GLES30.glGetUniformLocation(program, "uTime")

        // 静止层：波前未扫到的区域以逐像素原图绘制，与粒子层共用波前公式
        GLES30.glUseProgram(stillProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(stillProgram, "uSnapshot"), 0)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(stillProgram, "uViewportPx"),
            viewportWidth.toFloat(), viewportHeight.toFloat()
        )
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(stillProgram, "uOriginPx"),
            spec.originXPx, spec.originYPx
        )
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(stillProgram, "uSnapshotPx"),
            snapshot.width.toFloat(), snapshot.height.toFloat()
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(stillProgram, "uCellPx"), cellPx)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(stillProgram, "uGrid"), cols, rows)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(stillProgram, "uWaveOriginUv"),
            spec.waveOriginUv.x, spec.waveOriginUv.y
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(stillProgram, "uSpreadTime"), spec.spreadTime
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(stillProgram, "uDelayJitter"), spec.delayJitter
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(stillProgram, "uNoiseScalePx"), spec.noiseScalePx
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(stillProgram, "uWaveWarp"), spec.waveWarp
        )
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(stillProgram, "uNoiseSeed"),
            spec.noiseSeedX, spec.noiseSeedY
        )
        GLES30.glUniform1ui(
            GLES30.glGetUniformLocation(stillProgram, "uHashSeed"), spec.hashSeed
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(stillProgram, "uSweepTime"), sweepTime
        )
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(stillProgram, "uSweepDir"), sweepDx, sweepDy
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(stillProgram, "uArcBow"), 0f
        )
        GLES30.glUniform3f(
            GLES30.glGetUniformLocation(stillProgram, "uPanelColor"),
            ((spec.panelColor shr 16) and 0xFF) / 255f,
            ((spec.panelColor shr 8) and 0xFF) / 255f,
            (spec.panelColor and 0xFF) / 255f
        )
        val stillTimeLocation = GLES30.glGetUniformLocation(stillProgram, "uTime")

        val scale = spec.durationScale.coerceAtLeast(0.1f)
        val condenseFrom = checkNotNull(spec.condenseFromT)
        val startNanos = System.nanoTime()
        var firstFrameReported = false
        while (!cancelled) {
            val elapsed = (System.nanoTime() - startNanos) / 1e9f / scale
            // 凝聚模式：逻辑时钟从 condenseFromT 倒放到 0（末帧 = 完整原图）
            val progress = min(elapsed / spec.condenseDurationS, 1f)
            val clampedT = condenseFrom * (1f - progress)
            val lastFrame = progress >= 1f
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(stillProgram)
            GLES30.glUniform1f(stillTimeLocation, clampedT)
            GLES30.glDrawArrays(stillPrimitive, 0, stillVertices)
            GLES30.glUseProgram(program)
            GLES30.glUniform1f(timeLocation, clampedT)
            // 凝聚保留既有三副本。
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, particleCount * replicas)
            // swap 由 TextureView 消费端按显示帧率背压节流，无需额外 pacing
            if (!EGL14.eglSwapBuffers(EGL14.eglGetCurrentDisplay(), EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW))) {
                return false
            }
            if (!firstFrameReported) {
                firstFrameReported = true
                onFirstFrame()
            }
            if (lastFrame) return true
        }
        return false
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

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("program link failed: $log")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("shader compile failed: $log")
        }
        return shader
    }

    private fun eglFailure(operation: String): String =
        "$operation failed: EGL 0x${Integer.toHexString(EGL14.eglGetError())}"

    companion object {
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
        /**
         * cell 总数上限；超出时由 controller 放大网格步长自适应。细密化
         * （cell 1.5dp -> 1.1dp）后同尺寸面板 cell 数约 ×1.86，上限相应
         * 提高（顶点 = ×REPLICAS = 66 万，vertex 阶段为主要开销，真机验证）。
         */
        const val MAX_PARTICLES = 220_000

        /**
         * 每 cell 顶点数：1 主粒子 + 2 彩色副本。副本真实增加彩色粒子数量
         * （内容色增强）；低饱和格的副本在 vertex 阶段移出裁剪，片元开销
         * 为零。
         */
        const val REPLICAS = 3

        /** 凝聚路径的波前扩散时长；消散不使用。 */
        internal const val SPREAD_TIME = 0.0f

        /** 消散的逐粒子激活抖动上限（0.12 -> 0.08 配平总时长）。 */
        internal const val DELAY_JITTER = 0.04f

        /**
         * 消散的锋线波浪幅度（秒）。第二十九轮 0.28 -> 0.14：单向揭开的
         * 带形要清楚，波浪只做锋线的不齐与多段咬入。
         */
        internal const val WAVE_WARP = 0.14f

        /** 激活即刻的起飞冲量（ease 占比）：消除"点阵化但原地不动"的假 dialog 带。 */
        private const val KICK = 0.02f

        /** 单粒子从激活到消失的基准寿命；逐粒子按 lifeMod（0.55–1.25）调制。 */
        private const val LIFETIME = 0.58f

        /**
         * 新消失动画的总时长。主体约 0.85 秒，少量尾粒子最晚在 1.0 秒内
         * 结束；出现动画有独立的 condenseDuration，不依赖此值。
         */
        const val TOTAL_DURATION = 1.0f

        /** 逐粒子独立抖动的振幅，以网格步长为单位；主运动来自空间连贯流场。 */
        private const val TURBULENCE_CELLS = 1.4f

        /**
         * 旋涡场强度（相对主方向速度）。1.0 时粒子早期被流场主导、四面八方
         * 乱卷；0.6 用户又觉得过于规整，0.75 折中（方向整形保证不回流）。
         */
        private const val SWIRL = 0.75f

        /** 流场随时间的演化速率：静帧看不出、动画里让烟缕活动起来。 */
        private const val FLOW_EVOLVE = 0.5f

        /**
         * Genie 收拢强度（macOS 窗口入 Dock 的漏斗形，2026-08-26 用户点名）：
         * 粒子相对触点主轴的横向偏移随飞行进度收回的比例，1 = 完全收拢到轴。
         * 0.96 为用户指定值（0.55 温和 → 0.91 → 0.96 逐轮加强）。
         */
        private const val PINCH = 0.96f

        // Maleoon/Mali 严格拒绝跨阶段精度不一致的链接：varying 两侧显式 highp。
        // 运动模型与 tmp/particle-dismiss-tuning/render_frames.py 的 VERT_NEW 保持
        // 同步（桌面蓝本先行验证，2026-08-26 第二轮定稿）。
        private val VERTEX_SHADER = """
            #version 300 es
            precision highp float;
            precision highp int;

            uniform sampler2D uSnapshot;
            uniform vec2 uViewportPx;
            uniform vec2 uOriginPx;
            uniform float uCellPx;
            uniform ivec2 uGrid;
            uniform float uTime;
            uniform vec2 uWaveOriginUv;
            uniform float uSpreadTime;
            uniform float uDelayJitter;
            uniform float uLifetime;
            uniform float uDriftPx;
            uniform vec2 uVirtualTouchPx;
            uniform float uNoiseScalePx;
            uniform float uSwirl;
            uniform float uFlowEvolve;
            uniform float uPinch;
            uniform float uKick;
            uniform float uWaveWarp;
            uniform float uFlarePx;
            uniform float uPinchMaxPx;
            uniform float uTurbPx;
            uniform float uMaxPointPx;
            uniform vec2 uNoiseSeed;
            uniform uint uHashSeed;
            uniform vec3 uPanelColor;
            uniform int uReplicas;
            uniform float uContentBoost;
            uniform float uSweepTime;
            uniform vec2 uSweepDir;
                uniform float uArcBow;

            out highp vec4 vColor;
            out highp float vActivation;
            out highp float vGlow;

            // 整数 hash：sin-hash 的 dot 参数达 1e3 弧度量级，移动 GPU 的 sin
            // 是低阶多项式近似、大参数严重失真，噪声在真机上退化（桌面蓝本与
            // 真机不一致的根因，2026-08-26 用户发现）。整数运算跨平台逐位一致
            float hash21(vec2 p) {
                uvec2 q = uvec2(ivec2(p)) * uvec2(1597334673u, 3812015801u);
                uint n = (q.x ^ q.y) * 1597334673u;
                return float(n) * (1.0 / 4294967296.0);
            }

            // uNoiseSeed：每次动画随机平移噪声域，凝实/消散图案每次全新
            float vnoise(vec2 p) {
                p += uNoiseSeed;
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(
                    mix(hash21(i), hash21(i + vec2(1.0, 0.0)), u.x),
                    mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), u.x),
                    u.y
                );
            }

            float fbm(vec2 p) {
                return vnoise(p) * 0.667 + vnoise(p * 2.03 + 11.3) * 0.333;
            }

            // 无散度旋涡场：烟缕的来源。空间连贯——相邻粒子被同一股气流带动，
            // 逐粒子白噪声方向给不出这种成团成缕的非均匀感
            vec2 curlField(vec2 p) {
                float e = 0.12;
                float dy = fbm(p + vec2(0.0, e)) - fbm(p - vec2(0.0, e));
                float dx = fbm(p + vec2(e, 0.0)) - fbm(p - vec2(e, 0.0));
                return vec2(dy, -dx) / (2.0 * e);
            }

            void main() {
                // 副本扩倍：每 cell 发 uReplicas 个顶点。replica 0 = 主粒子
                // （hash 输入与静止层完全一致，行为与无副本时逐位相同）；
                // replica > 0 = 彩色格的增量副本（起飞时与主粒子重叠在同一格、
                // 随轨迹随机分开），真实增加彩色粒子数量
                int replica = gl_VertexID % uReplicas;
                int cellId = gl_VertexID / uReplicas;
                int ix = cellId % uGrid.x;
                int iy = cellId / uGrid.x;
                vec2 cell = vec2(float(ix), float(iy));
                vec2 uv = (cell + 0.5) / vec2(uGrid);
                // vertex 阶段无自动 LOD，显式取 0 级；权重与副本门控需要颜色，
                // 采样上移到激活检查之前
                vec4 color = textureLod(uSnapshot, uv, 0.0);

                // 主 hash：输入 = cellId，与静止层逐位一致——delay 由它派生，
                // 主粒子起飞与静止层擦除严格对齐（PCG：cellId 到 15 万量级时
                // 浮点 sin-hash 的有效随机位不足，会出现可见条带）
                uint hm = uint(cellId) ^ uHashSeed;
                hm = hm * 747796405u + 2891336453u;
                hm = ((hm >> ((hm >> 28u) + 4u)) ^ hm) * 277803737u;
                hm = (hm >> 22u) ^ hm;
                float h1m = float(hm & 1023u) * 0.0009775171;
                float h2m = float((hm >> 10u) & 1023u) * 0.0009775171;
                // 自身 hash：副本的输入偏移出主空间（轨迹独立）；replica 0 时
                // 与主 hash 相同，主粒子的全部随机行为不变
                uint h = (uint(cellId) + uint(replica) * uint(uGrid.x * uGrid.y)) ^ uHashSeed;
                h = h * 747796405u + 2891336453u;
                h = ((h >> ((h >> 28u) + 4u)) ^ h) * 277803737u;
                h = (h >> 22u) ^ h;
                float h1 = float(h & 1023u) * 0.0009775171;
                float h2 = float((h >> 10u) & 1023u) * 0.0009775171;
                float h3 = float((h >> 20u) & 1023u) * 0.0009775171;
                float h4 = fract(h1 + h2 * 0.618034);
                float h5 = fract(h2 + h3 * 0.618034);

                // 内容色权重：与面板本体色的色距为主（黑/灰字中档）、饱和度
                // 加成（彩色最高档）。颜色本身逐位不变，只做重加权——白底粒子
                // 是烟云质感载体，内容色粒子更大、更持久、真实更多
                float colorDist = length(color.rgb - uPanelColor) * 0.5774;
                float sat = max(color.r, max(color.g, color.b))
                        - min(color.r, min(color.g, color.b));
                float wDist = smoothstep(0.08, 0.42, colorDist);
                float wSat = smoothstep(0.18, 0.42, sat);
                vec2 snapshotPx = vec2(uGrid) * uCellPx;
                vec2 basePx = uOriginPx + (cell + 0.5) * uCellPx;
                // 边缘并入内容权重（第三十三轮，用户模型）：浓带 = 边缘与
                // 内容色转换出的密集粒子群本身——边缘粒子享受全保留/尺寸/
                // 长寿/副本全套增强，成为掀起时随波浪变形飘动的亮弧线。
                // 弯曲与断续（第三十四轮）：边界线位置受噪声摆动（波浪而非
                // 直线）、沿边强度低频变化（同一条边有的段浓有的段稀甚至
                // 断开；四条边对噪声采样不同、彼此表现各异）
                float edgeDistW = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
                float edgeWave = (vnoise(basePx / (uNoiseScalePx * 0.45) + 210.7) - 0.5)
                        * 0.08;
                float edgeMod = smoothstep(0.22, 0.72,
                        vnoise(basePx / (uNoiseScalePx * 0.7) + 123.4));
                float edgeBoost = smoothstep(0.06, 0.015, edgeDistW + edgeWave) * edgeMod;
                float w = min(wDist * 0.62 + wSat * 0.6 + 0.10 * edgeBoost, 1.0)
                        * uContentBoost;

                // 波前：起点到本粒子的像素距离按快照对角线归一化
                float waveDist = distance(uv * snapshotPx, uWaveOriginUv * snapshotPx)
                        / length(snapshotPx);
                // 锋线波浪：双尺度噪声（低频大波浪 + 中频谷整形多段咬入），
                // 幅度收小保带形。静止层用完全同款公式保持擦除对齐
                // 大振幅弧线锋线（第三十一轮，按华为截图钉死）：低频大波长
                // 噪声——横跨整卡一两个起伏、振幅 ~40% 跨度，弧线一侧先掀开
                // 一大片、另一侧滞后；起始只是边缘的一段（弧线谷的先头），
                // 不是整条边
                float lowN = vnoise(basePx / (uNoiseScalePx * 2.2) + 7.7);
                float waveWarp = lowN * uWaveWarp;
                // 单向揭开（第二十九轮，用户模型）：delay 主项 = 沿飞行方向
                // 的投影。揭开线垂直于飞行方向、从飞行反侧边缘（proj=0）扫到
                // 前侧（proj=1）——往上飞从下缘揭开、往右上飞浓带呈左上-右下
                // 走向，像揭一张便利贴。凝聚传 uSweepTime=0 退回斑块凝实
                float sweepBase = min(0.0, snapshotPx.x * uSweepDir.x)
                        + min(0.0, snapshotPx.y * uSweepDir.y);
                float sweepSpan = abs(snapshotPx.x * uSweepDir.x)
                        + abs(snapshotPx.y * uSweepDir.y);
                float proj = (dot(basePx - uOriginPx, uSweepDir) - sweepBase)
                        / max(sweepSpan, 1.0);
                // delay 用主 h1（静止层对齐）；副本在主粒子之后小的正偏移
                // 起飞——绝不早于格子擦除，"凭空多一颗"不会发生（凝聚倒放
                // 同理：副本先落）
                // 次级边缘起碎（仅消散）：其他边缘（含前侧）的噪声谷段也
                // 同时开始局部粒子化（华为截图：上缘左侧同步掀起）
                float edgeDist = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
                // 四周边缘同时向内侵蚀（第三十九轮，用户反馈"轮廓一直方方正正"）：
                // 距边缘 13% 以内的格子大幅提前碎化，侵蚀边界受噪声摆动（±10%）、
                // 强度沿边低频起伏——四条边同时开始破碎成弯曲曲线并向内推进，
                // 轮廓在动画早期就不再方正；揭开线只保留整体推进与浓带
                float erodeWave = (vnoise(basePx / (uNoiseScalePx * 0.4) + 300.7) - 0.5) * 0.10;
                float erodeMod = 0.45 + 0.55 * vnoise(basePx / (uNoiseScalePx * 0.62) + 412.3);
                float localEdge = smoothstep(0.13, 0.0, edgeDist + erodeWave) * erodeMod
                        * step(0.001, uSweepTime);
                // 揭开线的夸张大弧（第四十轮，用户要求"波峰/波谷"）：中间比两侧
                // 早揭开 uArcBow 秒——揭开边界沿飞行方向凸出成一道大弧（往上飞是
                // 波峰、往下飞是波谷），弧心每次动画随机偏移，不完全对称
                vec2 perpDir = vec2(-uSweepDir.y, uSweepDir.x);
                float perpSpan = abs(snapshotPx.x * perpDir.x) + abs(snapshotPx.y * perpDir.y);
                float perpBase = min(0.0, snapshotPx.x * perpDir.x)
                        + min(0.0, snapshotPx.y * perpDir.y);
                float lateral = (dot(basePx - uOriginPx, perpDir) - perpBase)
                        / max(perpSpan, 1.0);
                float bowCenter = 0.5 + (fract(uNoiseSeed.x * 0.0137) - 0.5) * 0.16;
                float lat = (lateral - bowCenter) / max(max(bowCenter, 1.0 - bowCenter), 0.001);
                float arcBow = uArcBow * lat * lat * step(0.001, uSweepTime);
                // 帷幔浓带（第四十一轮，用户要求"一条边上的一部分粒子形成帷幔"）：
                // 沿揭开起始边用低频噪声选出局部区段（约占边长 1/3），该区段全保留
                // 且副本全开——形成一道浓密的下垂弧带；其余部分（含边缘）照常抽稀，
                // 轮廓因此不再方正
                float curtainCenter = fract(uNoiseSeed.y * 0.0091) * 0.6 + 0.2;
                float curtainHalf = 0.13 + 0.07 * fract(uNoiseSeed.x * 0.0233);
                float curtainSeg = smoothstep(1.0, 0.25,
                        abs(lateral - curtainCenter) / curtainHalf);
                float curtainBand = smoothstep(0.34, 0.0, proj);
                float curtain = curtainSeg * curtainBand * step(0.001, uSweepTime);
                // 帷幔自身的下垂弧：段中心先掀、两端拖后，掀起的一角呈幕布弧
                float curtainArc = (1.0 - curtainSeg) * 0.07 * curtainBand
                        * step(0.001, uSweepTime);
                float delayMain = max(
                    waveDist * uSpreadTime + proj * uSweepTime + arcBow + waveWarp + curtainArc
                            - localEdge * 0.55 * uSweepTime, 0.0
                ) + h1m * uDelayJitter;
                float delay = delayMain
                        + float(replica) * (0.3 + 0.7 * h2) * 0.5 * uDelayJitter;
                // 掀起点更厚：揭开起始段（delay 最早）连白色副本也激活
                float originBoost = 1.0 - smoothstep(0.08, 0.30, delayMain);
                // 白底抽稀（第三十二轮，用户提议）：华为通知卡毛玻璃底不透明
                // 度实测仅 0.26——粒子化时透明底区的粒子淡到不可见，可见粒子
                // 天然集中在内容与边缘，浓稀对比是内容透明度分布的直接映射。
                // 对不透明控件的等效：本体色格子只保留约 30% 转化为粒子（其余
                // 碎化瞬间直接消失露背景），几何边缘带与内容色格子全保留——
                // 浓（边缘/内容密集带）与稀（白底零星）的对比内生。凝聚不
                // 抽稀（要凝实成完整面板）。判定用主 hash（主/副本一致）
                // 白底抽稀率也随空间起伏（稀疏场浓淡不均，不是均匀撒点）
                float keepBase = 0.03 + 0.06 * vnoise(basePx / (uNoiseScalePx * 0.8) + 87.1);
                float keep = max(max(keepBase, w), curtain);
                bool culled = step(0.001, uSweepTime) > 0.5 && h2m > keep;

                // 寿命两极分化（第二十九轮量化调参）：85% 粒子短寿
                // （0.16–0.28）——只活在揭开线的浓带里（带宽 = 可见期/扫速 ≈
                // 跨度 25–30%，对齐华为 30%），死亡后已扫区快速清空；15% 长寿
                // （0.70–1.25）稀疏飘完全程。峰/尾密度比目标 4–8（华为实测
                // 4–8；蓝本修正口径实测 6.6–8.4、已扫区 0.12–0.15 对齐华为
                // 0.14）。内容色长寿概率更高（余缕以内容色为主）
                float lifeMix = 0.5 * vnoise(basePx / (uNoiseScalePx * 0.9) + 67.9)
                        + 0.5 * h5;
                float h6 = fract(h3 + h4 * 0.618034);
                float shortLife = 0.16 + 0.12 * lifeMix;
                float longLife = 0.70 + 0.55 * lifeMix;
                float longGate = step(0.96 - 0.49 * w, h6);
                // 两极寿命仅消散（sweep>0）生效；凝聚用连续寿命（倒放下短寿
                // 会造成大量格子既无粒子也无静止层的黑洞）
                float bipolar = step(0.001, uSweepTime);
                float lifeMod = mix(
                    0.55 + 0.70 * lifeMix,
                    mix(0.55 + 0.70 * lifeMix,
                            mix(shortLife, longLife, longGate), bipolar),
                    uContentBoost
                );
                // 抽稀格子的粒子在揭开线扫到的**瞬间闪现**（极短寿命 0.09）——
                // 浓带因此是全格子密度、带外只剩保留粒子（约 1/6），浓稀对比来自
                // 数量本身；它们的静止层早在 delay 的 1/4 就擦除了（露背景），
                // 闪现前那段时间该格子确实是空的
                lifeMod = mix(lifeMod, 0.07, culled ? 1.0 : 0.0);
                float tl = clamp((uTime - delay) / (uLifetime * lifeMod), 0.0, 1.0);

                // 副本：彩色与高权重（边缘 w=0.85，黑字 0.62 除外——用户裁定
                // 黑字不增量）全程激活 ×3（浓带三倍密度贯穿动画）；其余粒子
                // 仅刚起飞段（带内副本）。未碎化粒子由静止层逐像素显示；
                // 被抽稀（culled）的格子碎化即消失、露出 dialog 底下的界面
                bool bandReplica = tl > 0.0 && tl < 0.38;
                bool replicaDead = replica != 0 && (culled || uContentBoost < 0.5 ||
                        (curtain < 0.5 &&
                        wSat < 0.5 && w < 0.78 && originBoost < 0.4 && !bandReplica));
                // 碎化即飞散（第三十五轮，用户报"两段感"）：粒子在自己的
                // delay 时刻碎化并**立即**起飞，无悬浮等待阶段——碎化与
                // 消逝同步（华为同款）。"开始就露背景"由被抽稀的白底格子
                // 提前消失承担（静止层在 delay 的 1/4 时刻擦除它们）
                if (uTime - delay <= 0.0 || replicaDead) {
                    vColor = vec4(0.0);
                    vActivation = 0.0;
                    vGlow = 0.0;
                    gl_PointSize = 1.0;
                    gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
                    return;
                }

                // 前慢后快（1.35 -> 1.6：带内滞留更久、堆积成浓带）+ 激活即刻
                // 的小冲量：几帧内滑出约 4dp，消除"点阵化但原地不动"的假
                // dialog 带（锋线后的粒子立即离位、边缘起沙散开）
                float ease = pow(tl, 1.6) + uKick * smoothstep(0.0, 0.06, tl);
                // 气流略早于主位移，但不抢跑：早期运动以主方向为主，缕状摆动
                // 中后期才加入（0.8 时早期被气流主导，观感混沌）
                float flowEase = pow(tl, 1.15);

                // 低频噪声调制主方向速度：不同区域快慢不同
                float riseMod = 0.45 + 1.2 * vnoise(basePx / uNoiseScalePx * 0.55 + 3.7);

                // 逐粒子主方向：指向虚拟远触点。方向随粒子位置平滑渐变——触点在
                // 左上时右侧粒子更偏左、左侧粒子更偏上；远点保证触点贴近快照
                // （点按钮）时两侧粒子不对冲
                vec2 dir = normalize(uVirtualTouchPx - basePx);

                // 低频弯曲场：指向远点的方向场是线性收敛场，整团像做线性 warp、
                // 两侧呈一致斜边。大尺度噪声给每个区域一个转向角（左右弯向不同，
                // 打破对称直边），转角随时间平滑增长——起飞时仍朝触点，随后弯出
                // 弧线（轨迹成螺线弧）
                float bendNoise = vnoise(basePx / (uNoiseScalePx * 1.8) + 27.4) - 0.5;
                float bend = (bendNoise * 1.6 + (h2 - 0.5) * 0.3)
                        * smoothstep(0.0, 1.0, tl);
                float cb = cos(bend);
                float sb = sin(bend);
                vec2 dirBent = vec2(dir.x * cb - dir.y * sb, dir.x * sb + dir.y * cb);

                // 流场采样点跟随主位移走（路径弯曲），场自身随时间演化
                vec2 samplePos = (basePx + dirBent * ease * uDriftPx * 0.4) / uNoiseScalePx;
                vec2 flow = curlField(samplePos + vec2(0.0, uTime * uFlowEvolve));

                // 流场方向整形：剔除逆行进方向分量（没有粒子往回跑），横摆全保留
                // （成缕与云宽靠它），顺向减弱——整团烟保持朝触点的整体流动
                float along = dot(flow, dirBent);
                vec2 flowShaped = (flow - dirBent * along) + dirBent * max(along, 0.0) * 0.4;

                // Genie 收拢：粒子相对"过触点、沿主方向"轴线的横向偏移随飞行
                // 进度收回。配合波前（近触点侧先飞、收得多；远侧后激活、仍全
                // 宽），整团呈沿触点方向的漏斗形。
                // 收拢量做双层调制：均匀线性收缩会让快照左右边缘的粒子收缩后
                // 仍然共线，云的侧边像刀切的直线——大尺度噪声让不同区段收得
                // 多收得少（边缘波浪化），逐粒子随机再加毛糙羽化；均值 1 保持
                // 整体漏斗力度，封顶防止过冲穿轴
                vec2 wavePx = uOriginPx + uWaveOriginUv * snapshotPx;
                vec2 rel = basePx - wavePx;
                vec2 lateralVec = rel - dir * dot(rel, dir);
                float pinchMod = (0.55 + 0.9 * vnoise(basePx / (uNoiseScalePx * 0.7) + 41.7))
                        * (0.75 + 0.5 * h3);

                // 外扩羽流：低频噪声门控约一半区段，边缘粒子向外推出、可超过
                // 原 Dialog 宽度；外扩区收拢同时打折——主体被吸走、边缘流苏
                // 逸散，云宽不再被收拢锁死
                float flare = max(vnoise(basePx / (uNoiseScalePx * 0.8) + 91.3) - 0.55, 0.0)
                        / 0.45;
                float lateralLen = length(lateralVec);
                vec2 lateralDir = lateralLen > 1.0 ? lateralVec / lateralLen : vec2(0.0);
                vec2 flareDrift = lateralDir * flare * uFlarePx * flowEase * (0.5 + h5);

                // 收拢位移设绝对上限：收拢按比例作用于初始横向偏移，宽 Dialog
                // 边缘的绝对收拢量线性放大、会被一口气拉向中轴——封顶后漏斗
                // 保留但不勒死
                float pinchAmount = min(uPinch * ease * pinchMod, 0.98)
                        * (1.0 - 0.7 * flare);
                vec2 pinchVec = -lateralVec * pinchAmount;
                float pinchLen = length(pinchVec);
                if (pinchLen > uPinchMaxPx) {
                    pinchVec *= uPinchMaxPx / pinchLen;
                }

                vec2 pinch = pinchVec + flareDrift;

                // 方向插值（替换爆散位移段）：运动方向从"随机偏侧向"平滑过渡
                // 到主流方向，位移曲线不变——轨迹呈先斜出再拐向主流的连续
                // 弧线。无额外速度段（不急、不加时长）；早期 ease 小、散开量
                // 天然温和；中部粒子早期方向以纯随机为主，不会集体撤离中轴
                // 形成空洞
                float randAng = h5 * 6.2831853;
                vec2 randDir = vec2(cos(randAng), sin(randAng));
                vec2 earlySum = lateralDir * (0.4 + 0.5 * h4) + randDir * 0.8;
                vec2 earlyDir = length(earlySum) > 0.05 ? normalize(earlySum) : randDir;
                float dirBlend = smoothstep(0.0, 0.55, tl);
                vec2 moveSum = mix(earlyDir, dirBent, dirBlend);
                vec2 moveDir = length(moveSum) > 0.05 ? normalize(moveSum) : dirBent;

                float speedJitter = 0.6 + 0.9 * h3;
                vec2 drift = (moveDir * riseMod * ease + flowShaped * uSwirl * flowEase)
                        * uDriftPx * speedJitter + pinch;

                // 逐粒子幅度随机的湍流抖动
                vec2 jitter = vec2(
                    sin(uTime * 6.0 + h1 * 41.0),
                    cos(uTime * 5.1 + h2 * 37.0)
                ) * uTurbPx * tl * (0.5 + h4);

                vec2 posPx = basePx + drift + jitter;

                // 烟缕浓淡：低频噪声调制透明度（0.4~1.0）；背景减密（轻度）：
                // 低权重（白底）粒子在飞散途中更早变稀薄，内容色不减
                float density = min(
                    0.4 + 0.75 * vnoise(basePx / uNoiseScalePx * 0.8 + 17.3), 1.0
                ) * (1.0 - 0.28 * (1.0 - w) * uContentBoost);
                // 平台式衰减（短寿=浓带成员：平台满亮后快谢）；长寿粒子早衰
                // 渐隐（飘着的淡纱，已扫区不再满亮压底——华为已扫区密度
                // 0.14 的构成）
                float fadeShort = 1.0 - smoothstep(0.50, 0.80, tl);
                float fadeLong = pow(1.0 - smoothstep(0.03, 0.65, tl), 2.2);
                float fade = mix(
                    pow(1.0 - smoothstep(0.02, 0.92, tl), 1.7),
                    mix(fadeShort, fadeLong, longGate), bipolar
                );
                // 浓淡噪声的介入延后到锋线带之后（0.05->0.30 起），带内不被打薄
                float alphaMul = mix(1.0, density, smoothstep(0.30, 0.60, tl));
                // 锋线辉光（第二十五轮）：起碎瞬间为粒子原色的发光版本——
                // 亮度增益后截断，深色内容变亮色、白色保持纯白（用户裁定：
                // 不固定成银色），随寿命前段衰减回真实色；锋线密集带叠成亮纱
                float glowEase = pow(max(1.0 - tl * 2.2, 0.0), 1.5);
                vec3 glowRgb = min(color.rgb * 2.2, vec3(1.0));
                vec3 litRgb = mix(color.rgb, glowRgb, 0.75 * glowEase);
                // 白色带内副本在 tl 0.18-0.30 淡出（浓带随锋线移动、身后
                // 还回单颗）
                float bandReplicaFade = (replica != 0 && wSat < 0.5 && originBoost < 0.4)
                        ? (1.0 - smoothstep(0.18, 0.30, tl)) : 1.0;
                vColor = vec4(litRgb, color.a * fade * alphaMul * bandReplicaFade);
                vActivation = smoothstep(0.0, 0.05, tl);
                vGlow = glowEase;

                // 粒子大小不均：低频区域差 × 逐粒子随机（平方偏斜：多数小、
                // 偶有大颗粒），静止拼图由静止层负责后尺寸已无约束。内容色
                // 放大：高权重粒子最大 +70%；副本略缩一档保持主次层次
                float sizeMod = (0.7 + 0.6 * vnoise(basePx / (uNoiseScalePx * 0.5) + 53.1))
                        * (0.55 + 0.95 * h4 * h4)
                        * (1.0 + 0.7 * w) * (replica != 0 ? 0.8 : 1.0)
                        * (1.0 + 0.6 * (1.0 - smoothstep(0.0, 0.35, tl)))
                        * (1.0 + 1.0 * curtain);
                // 第二十五轮：起步再收一档（1.0/0.34 -> 0.9/0.32，叠加 cell
                // 1.5dp -> 1.1dp 共约 -34%）——细密成纱
                gl_PointSize = clamp(
                    mix(uCellPx * 0.9, uCellPx * 0.32, tl) * sizeMod, 1.0, uMaxPointPx
                );
                vec2 ndc = posPx / uViewportPx * 2.0 - 1.0;
                gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;

            in highp vec4 vColor;
            in highp float vActivation;
            in highp float vGlow;
            out vec4 outColor;

            void main() {
                // 静止阶段为方点（无缝覆盖网格），激活后渐变为软边圆点；
                // 辉光期软边内径放宽（0.30 -> 0.12）：光晕更柔更晕，硬边
                // 消失成纱
                float r = length(gl_PointCoord - 0.5);
                float inner = mix(0.30, 0.12, vGlow);
                float circle = 1.0 - smoothstep(inner, 0.5, r);
                float shape = mix(1.0, circle, vActivation);
                float a = vColor.a * shape;
                outColor = vec4(vColor.rgb * a, a);
            }
        """.trimIndent()

        // 静止层：波前未扫到的区域直接以逐像素原图绘制（粒子拼图是 cellPx
        // 网格的重采样近似，文字会糊——2026-08-26 用户真机反馈定位）；按与
        // 粒子完全同款的网格/hash/波前公式逐格擦除，边界与粒子激活精确对齐
        private val STILL_VERTEX_SHADER = """
            #version 300 es
            precision highp float;
            precision highp int;

            uniform vec2 uViewportPx;
            uniform vec2 uOriginPx;
            uniform vec2 uSnapshotPx;
            uniform float uCellPx;
            uniform ivec2 uGrid;

            out highp vec2 vUv;

            void main() {
                vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
                vUv = corner;
                vec2 posPx = uOriginPx + corner * uSnapshotPx;
                vec2 ndc = posPx / uViewportPx * 2.0 - 1.0;
                gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);
            }
        """.trimIndent()

        private val STILL_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            precision highp int;

            uniform sampler2D uSnapshot;
            uniform ivec2 uGrid;
            uniform float uCellPx;
            uniform vec2 uOriginPx;
            uniform vec2 uWaveOriginUv;
            uniform float uSpreadTime;
            uniform float uDelayJitter;
            uniform float uNoiseScalePx;
            uniform float uWaveWarp;
            uniform float uTime;
            uniform vec2 uNoiseSeed;
            uniform uint uHashSeed;
            uniform float uSweepTime;
            uniform vec2 uSweepDir;
                uniform float uArcBow;
            uniform vec3 uPanelColor;

            in highp vec2 vUv;
            out vec4 outColor;

            // 整数 hash：与粒子层同款（sin-hash 在移动 GPU 大参数失真）
            float hash21(vec2 p) {
                uvec2 q = uvec2(ivec2(p)) * uvec2(1597334673u, 3812015801u);
                uint n = (q.x ^ q.y) * 1597334673u;
                return float(n) * (1.0 / 4294967296.0);
            }

            // 种子必须与粒子层一致，擦除边界才能精确对齐
            float vnoise(vec2 p) {
                p += uNoiseSeed;
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(
                    mix(hash21(i), hash21(i + vec2(1.0, 0.0)), u.x),
                    mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), u.x),
                    u.y
                );
            }

            void main() {
                vec2 cell = floor(vUv * vec2(uGrid));
                uint id = uint(cell.y) * uint(uGrid.x) + uint(cell.x);
                uint h = (id ^ uHashSeed) * 747796405u + 2891336453u;
                h = ((h >> ((h >> 28u) + 4u)) ^ h) * 277803737u;
                h = (h >> 22u) ^ h;
                float h1 = float(h & 1023u) * 0.0009775171;
                vec2 snapshotPx = vec2(uGrid) * uCellPx;
                vec2 cellUv = (cell + 0.5) / vec2(uGrid);
                vec2 basePx = uOriginPx + (cell + 0.5) * uCellPx;
                float waveDist = distance(cellUv * snapshotPx, uWaveOriginUv * snapshotPx)
                        / length(snapshotPx);
                // 与粒子层完全同款的大弧线锋线与单向揭开投影，擦除边界精确对齐
                float lowN = vnoise(basePx / (uNoiseScalePx * 2.2) + 7.7);
                float waveWarp = lowN * uWaveWarp;
                float sweepBase = min(0.0, snapshotPx.x * uSweepDir.x)
                        + min(0.0, snapshotPx.y * uSweepDir.y);
                float sweepSpan = abs(snapshotPx.x * uSweepDir.x)
                        + abs(snapshotPx.y * uSweepDir.y);
                float proj = (dot(basePx - uOriginPx, uSweepDir) - sweepBase)
                        / max(sweepSpan, 1.0);
                float edgeDist = min(min(cellUv.x, 1.0 - cellUv.x),
                        min(cellUv.y, 1.0 - cellUv.y));
                // 四周边缘同时向内侵蚀（第三十九轮，用户反馈"轮廓一直方方正正"）：
                // 距边缘 13% 以内的格子大幅提前碎化，侵蚀边界受噪声摆动（±10%）、
                // 强度沿边低频起伏——四条边同时开始破碎成弯曲曲线并向内推进，
                // 轮廓在动画早期就不再方正；揭开线只保留整体推进与浓带
                float erodeWave = (vnoise(basePx / (uNoiseScalePx * 0.4) + 300.7) - 0.5) * 0.10;
                float erodeMod = 0.45 + 0.55 * vnoise(basePx / (uNoiseScalePx * 0.62) + 412.3);
                float localEdge = smoothstep(0.13, 0.0, edgeDist + erodeWave) * erodeMod
                        * step(0.001, uSweepTime);
                // 揭开线的夸张大弧（第四十轮，用户要求"波峰/波谷"）：中间比两侧
                // 早揭开 uArcBow 秒——揭开边界沿飞行方向凸出成一道大弧（往上飞是
                // 波峰、往下飞是波谷），弧心每次动画随机偏移，不完全对称
                vec2 perpDir = vec2(-uSweepDir.y, uSweepDir.x);
                float perpSpan = abs(snapshotPx.x * perpDir.x) + abs(snapshotPx.y * perpDir.y);
                float perpBase = min(0.0, snapshotPx.x * perpDir.x)
                        + min(0.0, snapshotPx.y * perpDir.y);
                float lateral = (dot(basePx - uOriginPx, perpDir) - perpBase)
                        / max(perpSpan, 1.0);
                float bowCenter = 0.5 + (fract(uNoiseSeed.x * 0.0137) - 0.5) * 0.16;
                float lat = (lateral - bowCenter) / max(max(bowCenter, 1.0 - bowCenter), 0.001);
                float arcBow = uArcBow * lat * lat * step(0.001, uSweepTime);
                // 帷幔浓带（第四十一轮，用户要求"一条边上的一部分粒子形成帷幔"）：
                // 沿揭开起始边用低频噪声选出局部区段（约占边长 1/3），该区段全保留
                // 且副本全开——形成一道浓密的下垂弧带；其余部分（含边缘）照常抽稀，
                // 轮廓因此不再方正
                float curtainCenterS = fract(uNoiseSeed.y * 0.0091) * 0.6 + 0.2;
                float curtainHalfS = 0.13 + 0.07 * fract(uNoiseSeed.x * 0.0233);
                float curtainSegS = smoothstep(1.0, 0.25,
                        abs(lateral - curtainCenterS) / curtainHalfS);
                float curtainBandS = smoothstep(0.34, 0.0, proj);
                float curtainS = curtainSegS * curtainBandS * step(0.001, uSweepTime);
                float curtainArcS = (1.0 - curtainSegS) * 0.07 * curtainBandS
                        * step(0.001, uSweepTime);
                float delay = max(
                    waveDist * uSpreadTime + proj * uSweepTime + arcBow + waveWarp + curtainArcS
                            - localEdge * 0.55 * uSweepTime, 0.0
                ) + h1 * uDelayJitter;
                // 抽稀格子提前擦除（第三十五轮）：白底大多数格子不产生粒子，
                // 在 delay 的 1/4 时刻就消失——动画一开始整卡快速变稀薄、
                // 露出 dialog 底下的界面；保留的格子（边缘/内容色/少量白粒）
                // 仍以原图显示，直到自己的 delay 被粒子层接管（碎化即飞散，
                // 无静止分割线）。判定必须与粒子层逐位一致
                float h2s = float((h >> 10u) & 1023u) * 0.0009775171;
                vec4 cs = texture(uSnapshot, cellUv);
                float colorDistS = length(cs.rgb - uPanelColor) * 0.5774;
                float satS = max(cs.r, max(cs.g, cs.b)) - min(cs.r, min(cs.g, cs.b));
                float edgeDistWS = min(min(cellUv.x, 1.0 - cellUv.x),
                        min(cellUv.y, 1.0 - cellUv.y));
                float edgeWaveS = (vnoise(basePx / (uNoiseScalePx * 0.45) + 210.7) - 0.5)
                        * 0.08;
                float edgeModS = smoothstep(0.22, 0.72,
                        vnoise(basePx / (uNoiseScalePx * 0.7) + 123.4));
                float edgeBoostS = smoothstep(0.06, 0.015, edgeDistWS + edgeWaveS)
                        * edgeModS;
                float wS = min(smoothstep(0.08, 0.42, colorDistS) * 0.62
                        + smoothstep(0.18, 0.42, satS) * 0.6 + 0.10 * edgeBoostS, 1.0);

                        float keepBaseS = 0.03
                        + 0.06 * vnoise(basePx / (uNoiseScalePx * 0.8) + 87.1);
                bool culledS = step(0.001, uSweepTime) > 0.5
                        && h2s > max(max(keepBaseS, wS), curtainS);
                if (uTime > (culledS ? delay * 0.15 : delay)) discard;
                vec4 c = texture(uSnapshot, vUv);
                outColor = vec4(c.rgb * c.a, c.a);
            }
        """.trimIndent()

    }
}
