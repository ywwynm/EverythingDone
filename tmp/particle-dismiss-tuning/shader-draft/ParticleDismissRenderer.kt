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
 * Dialog 粒子动画的 GL 渲染线程：RGBA8888 / ES 3.0，每帧绘制一层完整快照
 * 与一层 `GL_POINTS` 粒子。
 *
 * 消失动画在启动时预计算与桌面 A 相同的连续 PBD 薄面，并把 241 帧轨迹上传为纹理
 * 数组；逐粒子仍由 `gl_VertexID` 派生材料坐标，但位置、脱离初态和折叠密度都来自同一
 * 张有状态曲面。CPU 每帧只更新 `uTime`。出现动画继续使用既有凝聚模型，两条 Shader
 * 路径相互独立。
 *
 * 回调在渲染线程调用，调用方负责切主线程。
 */
internal class ParticleDismissRenderer(
    private val surfaceTexture: SurfaceTexture,
    private val viewportWidth: Int,
    private val viewportHeight: Int,
    private val spec: ParticleDismissSpec,
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
        } catch (_: Throwable) {
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

    /** 返回 true 表示动画完整播完；false 表示中途取消或 surface 失效。 */
    private fun renderAnimation(): Boolean {
        val snapshot = spec.snapshot
        val isCondense = spec.condenseFromT != null
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
        // 桌面 A 固定使用 144 列材料采样。Android 也采用相同的归一化网格；
        // 这既消除真机 255 列造成的密度差，也把四个采样层的最坏顶点数控制在
        // 约 8.2 万，而不是继续以重 Shader 绘制近二十万顶点。
        val cellPx = if (isCondense) {
            spec.cellPx
        } else {
            max(spec.cellPx, snapshot.width / DISMISS_TARGET_COLUMNS.toFloat())
        }
        // 出现动画维持既有模型；消失动画使用连续材料释放场。二者不再靠同一
        // Shader 的时间倒放互相牵制，后续可分别演进。
        val program = createProgram(
            if (isCondense) VERTEX_SHADER else DISMISS_VERTEX_SHADER,
            if (isCondense) FRAGMENT_SHADER else DISMISS_FRAGMENT_SHADER
        )
        val stillProgram = createProgram(
            STILL_VERTEX_SHADER,
            if (isCondense) STILL_FRAGMENT_SHADER else DISMISS_STILL_FRAGMENT_SHADER
        )
        val cols = ceil(snapshot.width / cellPx).toInt().coerceAtLeast(1)
        val rows = ceil(snapshot.height / cellPx).toInt().coerceAtLeast(1)
        val particleCount = cols * rows
        val replicas = if (isCondense) REPLICAS else DISMISS_REPLICAS

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
        // 揭开的宏观时序已由 DISMISS_FIELD_GLSL 的释放核决定，消散不再需要
        // 单向扫描项；凝聚路径本来就传 0。uSweepDir 仍提供飞行方向。
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
        val condenseFrom = spec.condenseFromT
        val startNanos = System.nanoTime()
        var firstFrameReported = false
        while (!cancelled) {
            val elapsed = (System.nanoTime() - startNanos) / 1e9f / scale
            // 凝聚模式：逻辑时钟从 condenseFromT 倒放到 0（末帧 = 完整原图）
            val clampedT: Float
            val lastFrame: Boolean
            if (condenseFrom != null) {
                val progress = min(elapsed / spec.condenseDurationS, 1f)
                clampedT = condenseFrom * (1f - progress)
                lastFrame = progress >= 1f
            } else {
                clampedT = min(elapsed, TOTAL_DURATION)
                lastFrame = elapsed >= TOTAL_DURATION
            }
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(stillProgram)
            GLES30.glUniform1f(stillTimeLocation, clampedT)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glUseProgram(program)
            GLES30.glUniform1f(timeLocation, clampedT)
            // 消散的第 0 区间是基础材料采样，后三个区间按同一轨迹补充
            // 彩色内容、当前释放锋线和真实折叠脊；凝聚仍保留既有三副本。
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

        /**
         * 消散每 cell 只画一个粒子。参考实测的浓淡对比来自存活数量，
         * 同位置叠副本只会把颗粒糊成雾。
         */
        private const val DISMISS_REPLICAS = 3

        /** 消失路径与桌面 A 共用的横向材料采样数。 */
        /**
         * 材料采样列数。参考实测的粒径约为卡宽的 0.42%，间距与粒径相当，
         * 即约 240 列。密度要靠多而细的粒子拿，不能靠放大粒径——放大粒径
         * 能把密度指标顶上去，画面却会变成一颗颗圆盘。
         */
        private const val DISMISS_TARGET_COLUMNS = 256

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

        /**
         * 粒子层与静止层共享同一张运行时释放场。该场是一张沿风向单调推进、横向低频
         * 弯曲的连续相位场，不含任何参考视频帧，也不会叠加多个局部圆形波前。
         */
        private val DISMISS_FIELD_GLSL = """
            // 复测参考后的释放模型常量。它们是 GLSL 常量而不是 uniform：
            // 桌面 canonical 直接抽取本段源码，因此两端不存在第二份参数。
            // 数值来源见 docs/features/dialog-particle-dismiss/
            // research-2026-08-30-reference-remeasure.md。
            const float CURTAIN_RATE_EXPONENT = 1.00;
            const float CURTAIN_RELEASE_END = 0.98;         // 释放相位映射到的动画时刻上限
            // 释放场 = 一条前沿（2026-09-02 按参考逐帧目视重定，取代点源扩张）。
            // 参考 n=0.10：底边中央到右侧一条横带先化；n=0.17-0.24：前沿是一条从
            // 右上到左下的斜线，绕左下角转到约 35° 后不再转；n=0.24-0.45：这条线
            // 平行于自身向左上平移，左边缘截距 0.85→0.50；n=0.31 起左上角另起一团；
            // n=0.52 只剩脸——它是"前沿最后到"与"角上那团没盖到"的交集，不需要
            // 任何以卡心为原点的构造。粒子出生后沿前沿方向（风向）飞走，
            // 前沿身后是空的。
            const float CURTAIN_FRONT_ONSET = -0.12;        // 前沿从迎风角出发的时刻
            const float CURTAIN_FRONT_SWEEP = 0.78;        // 前沿从迎风角扫到顺风角的用时
            const float CURTAIN_FRONT_LAG = 0.26;          // 前沿中段的滞后（相位）
            const float CURTAIN_FRONT_LAG_CENTRE = 0.10;   // 滞后中心在沿前沿坐标上的位置
            const float CURTAIN_FRONT_LAG_WIDTH = 0.36;    // 滞后鼓包的宽度（沿前沿，半宽单位）
            const float CURTAIN_FRONT_SWAY = 0.05;         // 前沿沿其长度的起伏（相位单位）
            const float CURTAIN_FRONT_SWAY_FREQ = 1.6;     // 起伏沿前沿的频率
            const float CURTAIN_CORNER_REACH = 0.45;       // 远角起火的影响半径（半对角单位）
            const float CURTAIN_CORNER_START = 0.30;       // 远角起火的起始相位
            const float CURTAIN_CORNER_SLOPE = 0.22;       // 从远角向内推进的相位斜率
            const float CURTAIN_CORNER_ROUGH = 0.6;        // 角区边界的粗糙度
            const float CURTAIN_CORNER_ROUGH_SCALE = 3.5;  // 粗糙度的空间频率
            const float CURTAIN_RELEASE_ONSET = 0.012;      // 最早释放时刻 / 释放区间
            const float CURTAIN_DITHER_BASE = 0.25;         // 抖动在开场时的比例，随释放进程升到 1
            const float CURTAIN_SWIRL_SCALE = 1.9;    // 湍流势的空间频率（相干长度约 0.3 卡宽）
            const float CURTAIN_SWIRL_STRETCH = 0.42;      // 势场沿飞行轴的拉长（<1 为拉长）
            const float CURTAIN_SWIRL_DRIFT = 0.80;   // 势场随时间漂移的速率
            const float CURTAIN_SWIRL_EPS = 0.02;     // 取旋度的中心差分步长
            const float CURTAIN_FBM_SCALE = 1.30;           // 最粗一层的空间频率
            const float CURTAIN_DITHER = 0.24;             // 逐 cell 抖动全幅
            const float CURTAIN_CAMERA_DIST = 3.0;         // 相机距离 / 短边
            const float CURTAIN_SHADE_MIN = 1.00;          // 背面的亮度下限
            // 下限有一条算得出来的底线：白色粒子经 CURTAIN_FRESH_BOOST 之后
            // 亮度约 212·f，要在深色背景（亮度约 40）上仍清晰可辨需要至少两倍
            // 背景，即 f >= 0.38。压到 0.18 时整条背面在深色 UI 上凭空消失，
            // 看上去像粒子云被切了一刀，其实一颗都没少。
            const float CURTAIN_SHADE_MAX = 1.00;          // 迎光面的亮度上限
            const vec3  CURTAIN_LIGHT = vec3(-0.42, -0.55, 0.72); // 光向
            const float CURTAIN_PREDARK_LEAD = 0.08;        // 化掉前开始变暗的提前量
            const float CURTAIN_PREDARK_FLOOR = 0.87;       // 化掉前一刻的亮度比
            const float CURTAIN_PEEL_TAU = 0.11;           // 粒子卷起的时间常数
            const float CURTAIN_PEEL_ANGLE = 2.20;         // 卷起的最大角度（弧度）
            const float CURTAIN_PEEL_LIFT = 0.0;         // 卷起的抬升 / 短边
            const float CURTAIN_FOLD_EDGE = 0.20;          // 判定翻面的法向 z 阈值
            const float CURTAIN_FRONT_DIM = 0.95;          // 正面相对纯白留出的余量
            const float CURTAIN_FOLD_RIM = 0.0;           // 折脊上的高光强度
            const float CURTAIN_FOLD_RIM_WIDTH = 0.10;     // 折脊高光的角度半宽
            const float CURTAIN_FOLD_HOLD = 0.05;          // 翻面判据随年龄退场的时间常数
            const float CURTAIN_OLD_DIM = 1.00;            // 粒子到寿命末尾的亮度比
            const float CURTAIN_FOLD_SOFT = 0.045;          // 折脊的软化半宽
            const float CURTAIN_DITHER_LOW = 0.45;         // 交接带最窄处的宽度倍率
            const float CURTAIN_DITHER_HIGH = 1.50;        // 交接带最宽处的宽度倍率
            const float CURTAIN_DITHER_SCALE = 1.6;        // 交接带宽窄沿前沿起伏的频率
            const float CURTAIN_SWAY_DRIFT = 0.16;          // 交接带宽窄沿飞行轴的变化率
            const float CURTAIN_DITHER_COARSE = 0.70;      // 粗孔噪声在抖动里的占比
            const float CURTAIN_DITHER_COARSE_CELLS = 7.0; // 粗孔的尺度（格）
            const float CURTAIN_CELL_JITTER = 0.60;         // 粒子在本 cell 内的位置抖动

            uint curtainPcg(uint value) {
                value = value * 747796405u + 2891336453u;
                value = ((value >> ((value >> 28u) + 4u)) ^ value) * 277803737u;
                return (value >> 22u) ^ value;
            }

            float curtainRandom(int index, uint salt) {
                uint value = curtainPcg(uint(index + 1) * 1597334673u ^ uHashSeed ^ salt);
                return float(value & 65535u) / 65535.0;
            }

            float curtainLattice(ivec2 node) {
                uint key = uint(node.x + 4096) * 1597334673u
                        ^ uint(node.y + 4096) * 3812015801u
                        ^ uHashSeed;
                return float(curtainPcg(key) & 65535u) / 65535.0;
            }

            float curtainValueNoise(vec2 p) {
                ivec2 node = ivec2(floor(p));
                vec2 f = fract(p);
                vec2 w = f * f * (3.0 - 2.0 * f);
                return mix(
                    mix(curtainLattice(node), curtainLattice(node + ivec2(1, 0)), w.x),
                    mix(curtainLattice(node + ivec2(0, 1)), curtainLattice(node + ivec2(1, 1)), w.x),
                    w.y
                );
            }

            // 以最粗一层为主的三层噪声。参考的溶解边界在任何时刻都是深度
            // 指状的（紧致度实测 0.039），完整区内部会先出现成片的洞，而且
            // 剩余内容到卡片边缘的平均距离全程恒定（0.346->0.336）——后者
            // 说明侵蚀在空间上没有偏向，必须由大幅度的中尺度噪声承担。
            // 权重偏向粗层是为了让相位分布尽量平坦：等权多八度会趋近高斯，
            // 使面积-时间曲线变成 S 形，而参考的是近似线性。
            float curtainFbm(vec2 p) {
                return 0.72 * curtainValueNoise(p)
                        + 0.20 * curtainValueNoise(p * 2.11 + vec2(11.7, -7.3))
                        + 0.08 * curtainValueNoise(p * 4.37 + vec2(-5.1, 3.9));
            }

            // 细尺度项用两个 octave 就够：它们本来就只提供细纹理，第三个 octave
            // 的频率已高于一个 cell，对逐格的释放时刻没有贡献，只是白付开销。
            // 释放相位里原有 6 次 curtainFbm（18 次 value noise），且静止层是逐
            // 像素求值——真机上 90 分位一度到 28ms、jank 11.4%。
            float curtainFbm2(vec2 p) {
                return 0.78 * curtainValueNoise(p)
                        + 0.22 * curtainValueNoise(p * 2.11 + vec2(11.7, -7.3));
            }

            // 纱面的标量势。curl noise 与高度场共用同一个势，噪声只取样一次。
            float curtainPotential(vec2 p, float t) {
                // 势场沿飞行轴拉长：各向同性的势给出圆形的 churn，拉长之后涡是
                // 细长的，粒子被卷成缕而不是搅成团——这是烟雾里「涡丝」那一层的
                // 廉价近似（Biot-Savart 从少数几条涡线重建速度场的效果）。
                vec2 q = vec2(p.x * CURTAIN_SWIRL_STRETCH, p.y);
                return curtainFbm(
                    q * CURTAIN_SWIRL_SCALE
                    + vec2(t * CURTAIN_SWIRL_DRIFT, -t * CURTAIN_SWIRL_DRIFT * 0.63)
                ) - 0.5;
            }

            // 二维 curl noise：v = (dPsi/dy, -dPsi/dx)，恒为无散度，因此粒子不会
            // 在某处堆积或稀释，只有平滑的涡旋（Bridson, SIGGRAPH 2007）。
            //
            // 这是「一起飘」的关键：此前唯一的非均匀运动是逐粒子随机踢，那是
            // 空间上不相关的白噪声，相邻两颗粒子的偏移毫无关系，所以只能像沙。
            // 人眼判定「这是一块布」靠的正是相邻粒子一起动。
            //
            // 同时返回势值与梯度：势值是纱面高度场的湍流项，梯度给出它的法向。
            vec2 curtainSwirl(vec2 p, float t, out float value, out vec2 grad) {
                float e = CURTAIN_SWIRL_EPS;
                value = curtainPotential(p, t);
                grad = vec2(
                    curtainPotential(p + vec2(e, 0.0), t) - curtainPotential(p - vec2(e, 0.0), t),
                    curtainPotential(p + vec2(0.0, e), t) - curtainPotential(p - vec2(0.0, e), t)
                ) / (2.0 * e);
                return vec2(grad.y, -grad.x);
            }

            // 材料坐标 -> 以半对角线归一化的中心坐标。用半对角线而不是 uv，
            // 是为了让释放核在宽 Dialog 上仍然各向同性。
            vec2 curtainMaterial(vec2 uv) {
                vec2 halfPx = uSnapshotPx * 0.5;
                return (uv - 0.5) * uSnapshotPx / max(length(halfPx), 1.0);
            }

            // 连续释放相位，值域严格落在 [0, 1]。
            //
            // 低频项决定宏观时序与方向；分形项让任何时刻全卡都在同时打洞、
            // 并把边界撕成指状。分形扰动按 (1 - low^2) 收敛，所以最远角处
            // 恒等于 1，表面仍然一定在 CURTAIN_RELEASE_END 处消耗完。
            float curtainReleasePhase(vec2 uv) {
                vec2 q = curtainMaterial(uv);
                vec2 corner = curtainMaterial(vec2(1.0));
                vec2 direction = normalize(uSweepDir);

                // 释放场 = 一条垂直于风向的前沿，沿风向平推（2026-09-02 按参考逐帧
                // 目视 + 光流重定）。参考的粒子光流 -120°~-130°（图像坐标，左上），
                // 前沿是一条从右下角出发、与风向垂直的斜线，以约 1000 px/单位时间
                // 向左上平移，粒子以 400-800 px/单位时间跟在它后面——所以亮带贴着
                // 前沿走，前沿身后很快就空了。没有任何以卡心或某个点为原点的构造。
                //
                // 前沿最先到的是最迎风的角（右下），最后到的是最顺风的角（左上）；
                // 后者另起一团（参考 n≈0.3 起）。前沿中段（脸的位置）比两端晚一截，
                // 这就是用户指出的"向下凸"。
                vec2 perpendicular = vec2(-direction.y, direction.x);
                vec2 upwindCorner = vec2(direction.x >= 0.0 ? -corner.x : corner.x,
                                         direction.y >= 0.0 ? -corner.y : corner.y);
                vec2 downwindCorner = -upwindCorner;
                float span = max(dot(downwindCorner - upwindCorner, direction), 1.0e-3);
                float progress = dot(q - upwindCorner, direction) / span;   // 0 迎风角 … 1 顺风角

                // 沿前沿的坐标，以卡片在该方向的半宽归一（-1 … 1）。
                float acrossExtent = abs(corner.x * perpendicular.x) + abs(corner.y * perpendicular.y);
                float across = dot(q, perpendicular) / max(acrossExtent, 1.0e-4);
                // 中段滞后：一个平滑的鼓包，两端不滞后。
                float hump = across - CURTAIN_FRONT_LAG_CENTRE;
                float lag = CURTAIN_FRONT_LAG * exp(-hump * hump / (2.0 * CURTAIN_FRONT_LAG_WIDTH * CURTAIN_FRONT_LAG_WIDTH));
                // 前沿沿其长度的小起伏。
                float sway = (curtainFbm(vec2(across * CURTAIN_FRONT_SWAY_FREQ, 0.37) + vec2(5.1, 2.3)) - 0.5)
                        * CURTAIN_FRONT_SWAY;
                float arrival = CURTAIN_FRONT_ONSET + progress * CURTAIN_FRONT_SWEEP + lag + sway;

                float cornerRough = 1.0 + CURTAIN_CORNER_ROUGH
                        * (curtainFbm2(q * CURTAIN_CORNER_ROUGH_SCALE + vec2(-5.3, 7.1)) - 0.5);
                float cornerArrival = CURTAIN_CORNER_START
                        + length(q - downwindCorner) / max(CURTAIN_CORNER_REACH, 1.0e-3)
                          * CURTAIN_CORNER_SLOPE * cornerRough;

                float phase = min(arrival, cornerArrival);
                return clamp(phase, 0.0, 1.0);
            }

            ivec2 curtainCellOf(vec2 uv) {
                vec2 grid = vec2(uGrid);
                return ivec2(clamp(floor(clamp(uv, 0.0, 1.0) * grid), vec2(0.0), grid - 1.0));
            }

            // 逐 cell 抖动只负责最细一层的颗粒交接；成片的洞与指状边界由
            // curtainFbm 提供。
            float curtainCellDither(ivec2 cell) {
                uint key = uint(cell.x + 1) * 1597334673u
                        ^ uint(cell.y + 1) * 3812015801u
                        ^ uHashSeed;
                float fine = float(curtainPcg(key) & 65535u) / 65535.0 - 0.5;
                // 逐格的白噪声只能打出一格大小的细孔，最后剩下的那片看上去还是
                // 一整块实心；参考在同一时刻的脸是被打出几格到十几格大小的粗孔、
                // 千疮百孔的半透明。掺一档跨越若干格的粗噪声，孔就有大有小。
                // 它仍然只在抖动幅度之内起作用，不会在前沿之外单独打洞。
                vec2 coarseUv = (vec2(cell) + 0.5) / CURTAIN_DITHER_COARSE_CELLS;
                float coarse = curtainValueNoise(coarseUv + vec2(4.7, -2.9)) - 0.5;
                return mix(fine, coarse * 1.6, CURTAIN_DITHER_COARSE);
            }

            // 本 cell 离开表面的动画时刻。静止层与粒子层共用它，因此交接
            // 逐格精确，不会重叠也不会漏格。
            // 相位 -> 动画时刻。表面的消耗速率不是常数：参考的已释放面积对时间
            // 明显是凸的（0.0018 / 0.0214 / 0.0543 / 0.1562 / 0.3004 分别在
            // t = 0.0005 / 0.02 / 0.07 / 0.16 / 0.24），开场慢、后段快。相位本身
            // 是线性的，这里做一次单调重映射把这条速率曲线补上：等值线与拓扑
            // 一点不变，只改每条等值线到达的时刻。
            float curtainRateMap(float phase) {
                // 起步偏置：叠层是在真对话框窗口之上接管的，第 0 帧必须与原窗口
                // 逐像素相同，否则交接处会看到一次跳变。而起火角的相位恰好是 0，
                // 没有偏置时它的释放时刻也是 0——第 0 帧角上就已经缺了一块。
                // 偏置只要大于一个显示帧就够。
                return (CURTAIN_RELEASE_ONSET
                        + (1.0 - CURTAIN_RELEASE_ONSET)
                          * pow(clamp(phase, 0.0, 1.0), CURTAIN_RATE_EXPONENT))
                        * CURTAIN_RELEASE_END;
            }

            float curtainShade(vec3 normal) {
                return mix(
                    CURTAIN_SHADE_MIN,
                    CURTAIN_SHADE_MAX,
                    clamp(dot(normal, normalize(CURTAIN_LIGHT)), 0.0, 1.0)
                );
            }

            // 折脊必须画在**不含抖动**的平滑时刻上。用粒子自己的年龄算，相邻格
            // 的抖动最大到 ±0.36，同一小块里挤着年龄差很多的粒子，折脊于是被抹
            // 成一片渐变，看不出是一条线。平滑时刻在空间上连续，它的等值线才是
            // 一条真正的曲线。
            //
            // 一次相位求值同时给出两者，不额外付一遍 6 次 fbm 的代价。
            void curtainReleasePair(ivec2 cell, out float dithered, out float smoothAt) {
                vec2 uv = (vec2(cell) + 0.5) / vec2(uGrid);
                float base = curtainReleasePhase(uv);
                smoothAt = curtainRateMap(base);
                float room = min(base, 1.0 - base);
                vec2 material = curtainMaterial(uv);
                vec2 dir = normalize(uSweepDir);
                float acrossHere = dot(material, vec2(-dir.y, dir.x));
                float alongHere = dot(material, dir);
                float widthRoll = curtainFbm2(
                    vec2(acrossHere * CURTAIN_DITHER_SCALE,
                         alongHere * CURTAIN_DITHER_SCALE * CURTAIN_SWAY_DRIFT)
                    + vec2(-2.7, 5.9));
                float widthVar = mix(CURTAIN_DITHER_LOW, CURTAIN_DITHER_HIGH,
                                     clamp(widthRoll, 0.0, 1.0));
                float ditherAmount = CURTAIN_DITHER * widthVar
                        * (CURTAIN_DITHER_BASE + (1.0 - CURTAIN_DITHER_BASE) * base);
                // 与 curtainReleaseTime 完全一致的分方向钳位：静止层与粒子层必须
                // 算出同一个释放时刻，否则格子消失的时刻与粒子出生的时刻错开。
                float d = curtainCellDither(cell);
                float amp = d < 0.0
                        ? min(ditherAmount, base * 0.85)
                        : min(ditherAmount, (1.0 - base) * 0.85);
                dithered = curtainRateMap(base + d * amp);
            }

            float curtainReleaseTime(ivec2 cell) {
                vec2 uv = (vec2(cell) + 0.5) / vec2(uGrid);
                float base = curtainReleasePhase(uv);
                // 抖动必须在相位的两端收敛到 0。直接加一个全幅 CURTAIN_DITHER 的
                // 抖动，再靠 clamp 兜底，会把相位低于半幅的一整片格子压到恰好 0：
                // 实测抖动半幅 0.14 时有 8.9% 的格子释放时刻为 0（参考 0.18%），
                // 屏幕上就是开场瞬间卡片多处同时粒子化。
                float room = min(base, 1.0 - base);
                // 抖动随释放进程增大：参考的侵蚀过渡带会随时间变宽（左上缺口
                // 从 52‰ 卡宽涨到 88‰），我们此前是恒定的 38‰ 上下。材料越到
                // 后面越松，交接也就越碎。
                //
                // 抖动的宽度还必须**沿前沿起伏**。此前它只随相位变化，也就是
                // 沿整条前沿处处等宽：每一处都是「窄而饱和」的一条实心带，整条
                // 曲线于是又粗又匀，没有轻重。参考的交接带宽窄相差极大——有的
                // 地方几乎是干净的一刀，有的地方散成一大片零星颗粒，轻盈感正
                // 来自这种疏密对比。用一档低频噪声调制宽度即可，它与前沿位置
                // 用的是同一族噪声，因此宽处与凸处不会对齐成规则花纹。
                vec2 material = curtainMaterial(uv);
                vec2 dir = normalize(uSweepDir);
                float acrossHere = dot(material, vec2(-dir.y, dir.x));
                float alongHere = dot(material, dir);
                // 单层 value noise 就够：这里要的是一档缓慢起伏的宽度，不需要
                // 细节。静止层每个像素都要跑一遍这段，用 fbm2（两层）实测把真机
                // 的 90 分位从 16ms 抬到 20ms。
                float widthRoll = curtainValueNoise(
                    vec2(acrossHere * CURTAIN_DITHER_SCALE,
                         alongHere * CURTAIN_DITHER_SCALE * CURTAIN_SWAY_DRIFT)
                    + vec2(-2.7, 5.9));
                float widthVar = mix(CURTAIN_DITHER_LOW, CURTAIN_DITHER_HIGH,
                                     clamp(widthRoll, 0.0, 1.0));
                float ditherAmount = CURTAIN_DITHER * widthVar
                        * (CURTAIN_DITHER_BASE + (1.0 - CURTAIN_DITHER_BASE) * base);
                // 抖动幅度必须硬性不超过本格到相位两端的余量。此前用
                // smoothstep(0, amount*0.5, room) 做渐隐，它只是「靠近端点时变
                // 小」，并不保证不越界：amount=0.36、base=0.09 时渐隐系数 0.5，
                // 相位仍被推到 -0.09，clamp 之后就是 0——开场第一帧全卡各处同时
                // 冒粒子。改成直接对余量取 min，越界在构造上不可能发生。
                // 抖动的钳位必须**分方向**。此前两边都按 min(base, 1-base) 钳，
                // 相位接近 1 的那片材料几乎没有抖动，于是最后消失的那片是一整块
                // 边缘干净的实心，参考在同一时刻的脸却是被打得千疮百孔的半透明。
                // 只有会越出 [0,1] 的那一侧需要钳：往早推只受 base 限制，往晚推
                // 只受 1-base 限制。
                float d = curtainCellDither(cell);
                float amp = d < 0.0
                        ? min(ditherAmount, base * 0.85)
                        : min(ditherAmount, (1.0 - base) * 0.85);
                float phase = base + d * amp;
                return curtainRateMap(phase);
            }
        """.trimIndent()

        /**
         * 消失动画：粒子从 PBD 连续薄面取得当前状态及脱离初态，随后进入有界弹道。
         * 四个顶点区间是同一材料轨迹上的主体和密度副本，不再表示彼此独立的前后层；
         * 折叠脊来自真实曲率与投影压缩，触点同时提供主方向与柔光位置。
         */
        private val DISMISS_VERTEX_SHADER = """
            #version 300 es
            precision highp float;
            precision highp int;

            uniform sampler2D uSnapshot;
            uniform vec2 uViewportPx;
            uniform vec2 uOriginPx;
            uniform vec2 uSnapshotPx;
            uniform float uCellPx;
            uniform ivec2 uGrid;
            uniform float uTime;
            uniform uint uHashSeed;
            uniform vec2 uSweepDir;
            uniform float uMaxPointPx;

            out highp vec4 vColor;

            // 运动模型常量，同样是 GLSL 常量而非 uniform。
            const float CURTAIN_WIND_RATIO = 0.75;    // 峰值风速 / 卡片对角线
            const float CURTAIN_WIND_KNEE = 0.20;     // 风速线性升到此刻后走平
            const float CURTAIN_DRAG_TAU = 0.020;
            const float CURTAIN_INHERIT = 0.90;       // 出生时继承的纱布速度 / 风速
            const float CURTAIN_WIND_LATERAL = 0.30;  // 风速的横向梯度（右快左慢）     // 粒子追上风速的松弛时间
            const float CURTAIN_SPEED_SPREAD = 0.18;  // 逐粒子速度倍率半幅，sd≈0.43
            const float CURTAIN_KICK_SD = 0.10;       // 固定随机踢的 sd / 峰值风速
            const float CURTAIN_LIFE_MAX = 0.22;      // 主体粒子的寿命上限
            const float CURTAIN_LIFE_TAIL = 0.45;     // 少量尾粒子的寿命上限
            const float CURTAIN_CLEAR_AT = 0.95;      // 最后一颗粒子消失的时刻
            const float CURTAIN_TAIL_SHARE = 0.06;    // 取尾粒子寿命的比例
            // 新鲜粒子必须比源色更亮。此前设成 0.86（比源色暗）是为了与静止层的
            // 预变暗衔接，方向搞反了：参考的顺序是「表面先变暗 -> 释放出明亮的
            // 粒子」，这个由暗到亮的跳变正是侵蚀前沿那条明亮密集带的来源。设成
            // 比 1 小，前沿就只剩稀疏发暗的粒子，看不出「材料被掀起来堆在那里」。
            const float CURTAIN_FRESH_BOOST = 1.00;   // 新鲜粒子相对源色的亮度
            const float CURTAIN_GLOW = 0.35;          // 离开前沿后向白混合的峰值比例
            const float CURTAIN_POINT_RATIO = 1.30;   // 基准粒径 / 网格步长，不随年龄变化
            const float CURTAIN_SWIRL_AMPLITUDE = 0.0; // 湍流位移 / 短边（每单位年龄）
            const float CURTAIN_WAVE_NUMBER = 4.5;    // 颤振行波的波数
            const float CURTAIN_WAVE_SPEED = 11.0;    // 行波的角频率
            const float CURTAIN_WAVE_SKEW = 2.0;      // 波前相对横风向的倾斜
            const float CURTAIN_WAVE_AMPLITUDE = 0.0; // 行波的高度 / 短边
            const float CURTAIN_HEIGHT_NOISE = 0.0;  // 高度场里湍流势的权重
            const float CURTAIN_WIND_TILT = 0.75;     // 风的离屏分量 / 面内分量
            const float CURTAIN_PRESS = 0.16;         // 风压沿法向的耦合系数
            // 亮度下限不能低到与背景无异：粒子是被 shade 乘出来的，压到 0.18 时
            // 白色粒子在深色背景上等于消失，屏幕上是一条横贯粒子云的黑带——
            // 看上去像几何断层，其实粒子都还在。真实的纱在背光处是灰的，不是不见。
            // 用「关掉明暗重渲一帧」的废止实验判定：关掉后云是连续的一片，说明
            // 断层完全来自明暗。
            //
            // 下限有一条可算的底线：白色粒子经 CURTAIN_FRESH_BOOST 之后的亮度约
            // 212·f，要在深色 UI 背景（亮度约 40）上清晰可辨需要至少两倍背景，
            // 即 f >= 0.38。原来的 0.18 算出来正好是 38，与背景等亮。
            const float CURTAIN_FRESH_SIZE = 1.00;    // 新生粒子的粒径倍率，随年龄回落到 1
            const float CURTAIN_SIZE_MIN = 0.62;      // 逐粒子粒径倍率的下限
            const float CURTAIN_SIZE_MAX = 1.35;      // 逐粒子粒径倍率的上限

            $DISMISS_FIELD_GLSL

            // 共同风的位移积分：线性升到 uWindKnee 后走平。
            float curtainWindIntegral(float n, float windMaxPx) {
                float knee = max(CURTAIN_WIND_KNEE, 1.0e-4);
                return n < knee
                        ? windMaxPx * n * n / (2.0 * knee)
                        : windMaxPx * (knee * 0.5 + (n - knee));
            }

            float curtainWind(float n, float windMaxPx) {
                return windMaxPx * min(n / max(CURTAIN_WIND_KNEE, 1.0e-4), 1.0);
            }

            void hideParticle() {
                gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                gl_PointSize = 0.0;
                vColor = vec4(0.0);
            }

            void main() {
                // 每格发 uReplicas 个粒子。逐帧对照实测我们的粒子占比全程只有参考的
                // 五到六成（峰值 0.15 对 0.27），而参考的粒子并不慢——它的云是密
                // 而且快，只能是每块材料出的粒子更多。副本共用本格的释放时刻，
                // 各自有独立的抖动、粒径、随机踢与寿命。
                int cellsTotal = uGrid.x * uGrid.y;
                int replica = gl_VertexID / cellsTotal;
                int cellId = gl_VertexID - replica * cellsTotal;
                ivec2 cell = ivec2(cellId % uGrid.x, cellId / uGrid.x);
                if (cell.y >= uGrid.y) {
                    hideParticle();
                    return;
                }
                int id = cell.y * uGrid.x + cell.x + replica * cellsTotal;

                float birth;
                float smoothBirth;
                curtainReleasePair(cell, birth, smoothBirth);
                float age = uTime - birth;
                if (age < 0.0) {
                    hideParticle();
                    return;
                }

                // 寿命分布 life = lifeMax * (1 - sqrt(u))。它的生存函数恰为
                // (1 - age / lifeMax)^2，与参考实测的占位率衰减逐点吻合，
                // 因此浓淡对比只由存活数量产生，不需要独立的高密队列，也
                // 不需要任何全局收尾包络。少量粒子取更长的 lifeMax，对应
                // 参考末段那层稀疏但仍可见的尘埃。
                float lifeMax = mix(
                    CURTAIN_LIFE_MAX,
                    CURTAIN_LIFE_TAIL,
                    step(1.0 - CURTAIN_TAIL_SHARE, curtainRandom(id, 0xa136aaadu))
                );
                float life = lifeMax
                        * (1.0 - sqrt(curtainRandom(id, 0x9e3779b9u)));
                // 寿命上限不能是"到 1.0 为止"：那样所有晚出生的粒子会在同一帧齐灭。
                // 参考在 n=0.93 清空、末段是渐稀的尘，因此上限按逐粒子随机错开。
                life = min(life, (CURTAIN_CLEAR_AT - birth)
                        * mix(0.6, 1.0, curtainRandom(id, 0x27d4eb2fu)));
                if (age >= life) {
                    hideParticle();
                    return;
                }

                vec2 jitter = vec2(
                    curtainRandom(id, 0x85ebca6bu),
                    curtainRandom(id, 0xc2b2ae35u)
                ) - 0.5;
                vec2 uv = clamp(
                    (vec2(cell) + 0.5 + jitter * CURTAIN_CELL_JITTER) / vec2(uGrid),
                    0.0,
                    1.0
                );
                vec4 color = textureLod(uSnapshot, uv, 0.0);
                if (color.a <= 0.001) {
                    hideParticle();
                    return;
                }

                vec2 direction = normalize(uSweepDir);
                vec2 perpendicular = vec2(-direction.y, direction.x);
                float windMaxPx = CURTAIN_WIND_RATIO * length(uSnapshotPx);
                // 风速有横向梯度。参考分区域光流实测右侧约 500 px/单位时间、左侧约
                // 224——左边那团羽流因此在卡片附近留得更久、更大，我们此前是匀速风，
                // 左下那条羽流比参考小一圈。横风坐标以卡片半宽归一，右侧为正。
                {
                    vec2 lateralMaterial = curtainMaterial(uv);
                    vec2 lateralCorner = curtainMaterial(vec2(1.0));
                    vec2 lateralDir = normalize(uSweepDir);
                    float lateralExtent = abs(lateralCorner.x * lateralDir.y)
                            + abs(lateralCorner.y * lateralDir.x);
                    float lateralN = dot(lateralMaterial, vec2(-lateralDir.y, lateralDir.x))
                            / max(lateralExtent, 1.0e-4);
                    windMaxPx *= 1.0 + CURTAIN_WIND_LATERAL * clamp(lateralN, -1.0, 1.0);
                }
                float speedScale = 1.0 + CURTAIN_SPEED_SPREAD
                        * (curtainRandom(id, 0x68bc21ebu) * 2.0 - 1.0);
                // 均匀分布的 sd 是半幅的 1/sqrt(3)，所以按 sqrt(3) 还原成实测 sd。
                vec2 kick = (
                    perpendicular * (curtainRandom(id, 0x2545f491u) * 2.0 - 1.0)
                    + direction * (curtainRandom(id, 0x1b873593u) * 2.0 - 1.0) * 0.5
                ) * (CURTAIN_KICK_SD * 1.7320508 * windMaxPx);

                // 一阶阻力松弛：粒子从静止开始，以时间常数 CURTAIN_DRAG_TAU
                // 追上当前风速。参考实测同一帧内年龄 0.05 与 0.33 的速度比约
                // 0.63，正对应 tau≈0.055；这也是密度峰留在迎风侧锋线附近的
                // 原因——新生粒子几乎不动，老粒子才跑得快。
                // 逐粒子粒径要在运动之前算出来：它同时决定 Stokes 数。
                float sizeRoll = curtainRandom(id, 0x85ebca6bu);
                float sizeVar = mix(CURTAIN_SIZE_MIN, CURTAIN_SIZE_MAX, sizeRoll * sizeRoll);

                // Stokes 数：粒子的响应时间 tau 正比于直径的平方。小颗粒紧跟气流、
                // 被涡旋卷着走；大颗粒滞后、走得更直。此前所有粒子共用同一个 tau
                // 和同一个湍流耦合强度，响应完全一致——这正是「机械」的来源。
                // 烟尘那种轻盈感来自细尘缠绕、粗粒穿行两层同时存在。
                float stokes = sizeVar * sizeVar;
                float tau = max(CURTAIN_DRAG_TAU * stokes, 1.0e-4);
                float relax = 1.0 - exp(-age / tau);
                float lag = tau * relax;
                float travel = curtainWindIntegral(uTime, windMaxPx)
                        - curtainWindIntegral(birth, windMaxPx)
                        // 粒子出生时带着所在纱布的速度（用户 2026-09-02 选定）。
                        // 分界线附近的纱布在撕开前已经被风掀起在动，碎片不会从
                        // 静止起步；lag 是"从静止追上风速"欠下的位移，只欠其中
                        // (1 - CURTAIN_INHERIT) 那一部分。逐帧光流实测参考在 n=0.15
                        // 时 93% 的粒子已在动，我们此前只有 54%。
                        - curtainWind(uTime, windMaxPx) * lag * (1.0 - CURTAIN_INHERIT);
                travel = max(travel, 0.0);
                // 这里曾经有一个「横向发散」项：横向速度正比于该粒子到主轴的
                // 距离，粒群随时间扇形铺开。理由是参考的沿风/横风伸展比由
                // 1.35 单调降到 0.62。这个理由是错的——把两个分量分开量才看得
                // 出来：参考的横向 sd 是 0.273 -> 0.261（基本不变，斜率 -0.036），
                // 沿轴 sd 是 0.322 -> 0.166（大幅收缩）。比值下降是纵向在收缩，
                // 不是横向在膨胀。粒群整体被搬走，不从中心炸开。
                //
                // 该项使膨胀/平移之比达到 0.24（参考 -0.04），并把粒子云的前沿
                // 抹成看不出边界的一团。已删除；横向的少量随机由 kick 提供。

                vec2 material = curtainMaterial(uv);
                float shortSide = min(uSnapshotPx.x, uSnapshotPx.y);
                vec2 corner = curtainMaterial(vec2(1.0));
                float alongAxis = dot(material, direction);
                float acrossAxis = dot(material, perpendicular);
                float extent = abs(corner.x * direction.x) + abs(corner.y * direction.y);

                // 相干湍流。中点近似 swirl(material, birth + age/2) * age 保持无
                // 状态：势场随时间漂移，云在飘的过程中不断改变形态。
                float potential;
                vec2 potentialGrad;
                vec2 swirl = curtainSwirl(material, birth + age * 0.5, potential, potentialGrad);
                // 湍流耦合按 1/(1+St)：小颗粒被涡旋完全带走，大颗粒几乎不受影响。
                vec2 turbulence = swirl
                        * (CURTAIN_SWIRL_AMPLITUDE * shortSide * age / (1.0 + stokes));

                // 纱面的高度场。柔性薄片在均匀流中的颤振本征形态是「从前缘出发、
                // 向后缘传播且幅度递增的行波」；这一项是时变的，静态的折叠位移
                // 给不出「飘」，只能给出固定的条纹。
                float envelope = clamp(
                    (alongAxis + extent) / (2.0 * max(extent, 1.0e-4)), 0.0, 1.0
                );
                float dEnvelope = step(0.001, envelope) * step(envelope, 0.999)
                        / (2.0 * max(extent, 1.0e-4));
                float wavePhase = alongAxis * CURTAIN_WAVE_NUMBER
                        - uTime * CURTAIN_WAVE_SPEED
                        + acrossAxis * CURTAIN_WAVE_SKEW;
                // 被掀起的是粒子群。纱面从前沿开始向后卷：刚脱离的那一批还近乎
                // 贴着原来的面，越往后卷得越过去，**越过折脊的那一部分露出背面**。
                // 参考的羽流正是这样——亮的一片和暗的一片之间有一条清晰的曲线，
                // 那条线就是折脊，线下方是背面。我们此前所有粒子一样亮，整团白，
                // 没有任何立体结构。
                //
                // 卷角随年龄单调增大，因此透视缩放也是单调的。让它先增后减会把
                // 缩放拉过去再收回来，而透视是以卡心为原点的径向缩放——每颗粒子
                // 于是被额外径向拽一下再拉回，方向随它在卡上的位置而不同，实测
                // 逐粒子方向 sd 峰值从 21° 涨到 42°，粒群朝四面八方散。
                float foldAge = max(uTime - smoothBirth, 0.0);
                float peel = 1.0 - exp(-foldAge / max(CURTAIN_PEEL_TAU, 1.0e-4));
                float peelAngle = CURTAIN_PEEL_ANGLE * peel;
                float height = CURTAIN_WAVE_AMPLITUDE * envelope * sin(wavePhase)
                        + CURTAIN_HEIGHT_NOISE * potential
                        + CURTAIN_PEEL_LIFT * (1.0 - cos(peelAngle));
                vec2 heightGrad =
                    direction * (CURTAIN_WAVE_AMPLITUDE
                        * (dEnvelope * sin(wavePhase)
                           + envelope * CURTAIN_WAVE_NUMBER * cos(wavePhase)))
                    + perpendicular * (CURTAIN_WAVE_AMPLITUDE * envelope
                        * CURTAIN_WAVE_SKEW * cos(wavePhase))
                    + CURTAIN_HEIGHT_NOISE * potentialGrad;

                // 风必须作用在**面元**上，而不是对所有粒子一视同仁地平移。
                //
                // 此前 curtainWind 只是一条标量速度曲线乘上固定方向，对每颗粒子
                // 完全相同；而 height 只进了 perspective 与 normal，从不进入位置。
                // 也就是说纱面的形状纯粹是装饰：它改变东西看起来怎样，不改变任何
                // 东西往哪走。风与形状之间零耦合，所以怎么调都不像布。
                //
                // 真实的风作用在纱上是压力沿面元法向、大小正比于它有多正对气流：
                // F = k (U·n) n。这一项有反馈——略微鼓起的地方更正对风、受力更大、
                // 于是鼓得更厉害。凸起、折叠、翻滚是同一个反馈的三种表现，不是
                // 三种要分别捏出来的效果。
                // 受力用的是**真实**的几何法向，明暗才用夸张过的那个。
                //
                // 高度以短边为单位、材料坐标以半对角线为单位，真实坡度必须做这个
                // 换算；`CURTAIN_NORMAL_GAIN` 是为了让明暗带够强而调出来的夸张
                // 系数（5.0），拿它当物理法向会把力的方向整个带偏——法向会变成
                // 几乎躺在平面内，压力于是几乎不进 z，鼓不起来。
                float toSlope = shortSide / max(length(uSnapshotPx) * 0.5, 1.0);
                // 法向要在**卷过去之后**的面上取：卷角超过 90° 的那部分法向的 z
                // 变负，朗伯项落到下限，于是自然形成「折脊一侧亮、另一侧暗」。
                // 颤振行波的起伏仍叠在这个已经转过去的面上。
                vec3 trueNormal = normalize(vec3(
                    direction * sin(peelAngle) - heightGrad * toSlope * cos(peelAngle),
                    cos(peelAngle)
                ));

                // 风必须有离屏分量：纱面初始几乎正对观察者，若风完全躺在屏幕平面
                // 内，(U·n) 恒等于 0，一点力都吃不到，也就永远鼓不起来。
                float windSpeed = curtainWind(uTime, windMaxPx);
                vec3 windVec = vec3(direction * windSpeed, -CURTAIN_WIND_TILT * windSpeed);
                float facing = dot(normalize(windVec), trueNormal);
                // 常力下位移随年龄平方增长。
                vec3 pressure = trueNormal * (facing * CURTAIN_PRESS * windSpeed * age * age);

                // 法向力的 z 分量把已经鼓起的地方推得更鼓——这就是颤振失稳的
                // 一步显式迭代；xy 分量则真正搬运材料，褶皱因此会推着材料走。
                height += pressure.z / max(shortSide, 1.0);

                vec2 flatPx = uOriginPx + uv * uSnapshotPx
                        + direction * travel * speedScale
                        + kick * (age - lag)
                        + turbulence
                        + pressure.xy;

                // 把 z 真的算出来再投影。透视本身同时给出三件事：近处外扩、
                // 远处收拢的视差；纱面越接近侧视、单位屏幕面积承载的材料越多
                // 造成的密度起伏；以及粒径随深度变化。三者一起出现，眼睛才会
                // 判定这是一块有厚度、在三维里飘的纱，而不是一张平面贴图。
                //
                // 密度起伏因此是投影雅可比的必然结果，不需要再有单独的「折叠
                // 压缩」位移项——那一项是静态的，只会退化成固定条纹。
                // 相机距离必须足够远：透视因子是 camera/(camera - h·shortSide)，
                // 它乘的是粒子到中心的距离。相机取 1.1 倍短边、高度到 ±0.4 倍短边
                // 时因子在 0.73-1.57 之间摆，而行波在整张卡上有两个周期——相邻
                // 材料点跨过一个波峰时因子变化 2.27，折算成位移差约 680px，远大于
                // 波长本身（约 390px），薄片被生生撕开，画面上是一条断层。
                //
                // 不撕裂的判据：位移场对材料坐标的梯度必须小于 1。把相机推远到
                // 3.0 倍短边、并把波数从 7.0 降到 4.5 之后该梯度约 0.3，
                // 透视仍有 ±15% 的视差，足够读出深度。
                vec2 centrePx = uOriginPx + uSnapshotPx * 0.5;
                float camera = CURTAIN_CAMERA_DIST * shortSide;
                float perspective = camera / max(camera - height * shortSide, camera * 0.3);
                vec2 posPx = centrePx + (flatPx - centrePx) * perspective;

                // 纱的折叠是**轻微**的：不存在被转过去的背光面。
                //
                // 此前这里有一套很强的明暗（下限 0.18、法向增益 7.8），是为了把
                // 「云内部局部亮度的变异系数」凑到参考的 0.24-0.31。那个目标测错
                // 了：参考**源图**的局部亮度变异系数是 0.537，远高于云内部的
                // 0.24-0.31——云的起伏比源图还低，因为粒子把不同位置的材料混在
                // 一起。也就是说参考那些「明暗带」是照片本身的明暗被粒子带着走，
                // 不是光照。我们的对话框源图只有 0.102（一片白），这个指标本就
                // 追不得，更不该用假光照去凑；强行凑的结果是整条背光带在深色
                // 背景上消失，看上去像几何断层。
                //
                // 现在只保留很轻的起伏：用真实几何法向，区间贴近 1，只让纱面有
                // 一点明暗层次，不产生任何朝向背面的部分。
                // 折脊要看得见，靠的不是「亮的一侧更亮」——白色对话框的粒子经
                // boost 之后早就顶到白，再亮也没有余量。只能靠**暗的一侧在折脊
                // 处立刻暗下去**。朗伯项本身是平滑过渡，铺开就是一片渐变而不是
                // 一条线；这里在它之外再叠一道窄的翻面判据：面元一旦转过折脊，
                // 亮度在很短的角度区间里落到下限。
                // 变量名不能叫 facing：上面的风压那一段已经有一个同名的
                // dot(风向, 法向)，GLSL 会直接拒绝链接，而桌面渲染只会安静地
                // 沿用上一次的帧序列——两轮都以为改动生效了，其实一帧都没重画。
                float foldFacing = cos(peelAngle);
                float backness = smoothstep(
                    CURTAIN_FOLD_EDGE + CURTAIN_FOLD_SOFT,
                    CURTAIN_FOLD_EDGE - CURTAIN_FOLD_SOFT,
                    foldFacing
                );
                // 正面必须**留出余量**，不能顶到纯白。白色对话框的粒子经 boost
                // 之后本来就已经是白，折脊上再亮也没有余地，那条线于是只能靠
                // 「暗的一侧」单边显形，看上去是渐变不是线。把正面压到 0.86，
                // 折脊那一圈才有亮上去的空间，一亮一暗夹出一条真正的线。
                float lit = curtainShade(trueNormal) * CURTAIN_FRONT_DIM;
                float rimT = (foldFacing - CURTAIN_FOLD_EDGE) / CURTAIN_FOLD_RIM_WIDTH;
                float rim = exp(-rimT * rimT);
                // 翻面只在粒子**还贴着纱面**的那段时间里成立。碎片一旦被吹散，
                // 「哪一面朝上」就没有意义了，再一直按背面压暗，整个后段的云都是
                // 暗的：逐帧实测 n=0.67-0.79 我们比参考暗 35-44 级，而参考那段的
                // 远尘仍是亮的。按年龄让翻面判据自己退场。
                float attached = exp(-age / max(CURTAIN_FOLD_HOLD, 1.0e-4));
                float shade = mix(lit, CURTAIN_SHADE_MIN, backness * attached)
                        + CURTAIN_FOLD_RIM * rim * attached;
                // 老粒子要暗下去。逐帧实测 n=0.74-0.82 我们比参考亮 54-88 级：翻面
                // 判据退场之后老粒子回到正面亮度，而参考的远尘是暗的、渐稀的。
                shade *= mix(1.0, CURTAIN_OLD_DIM, smoothstep(0.30, 0.90, age / max(life, 1.0e-4)));

                float fade = smoothstep(life, life * 0.70, age);
                float boost = mix(CURTAIN_FRESH_BOOST, 1.0, smoothstep(0.0, 0.16, age));
                // 参考的羽流是被照白的：离完好区 8-40 px 的粒子亮度超背景 100-130 级，
                // 与它们原来的内容色无关（海报的黑色区域飞出去也是白的）。我们此前
                // 只按内容色乘一个系数，暗内容的粒子飞出去仍是暗的。按年龄向白混合，
                // 峰值落在离开前沿之后（0.05-0.14），之后回落。
                // 峰值在出生时：参考里离前沿 8-20 px（刚释放 0.01-0.04）的粒子最亮，
                // 远尘才暗下去。此前按"离开前沿后才亮"做，亮带没出现。
                // 亮带从一开始就有：按出生时刻开闸的做法（2026-09-02 试过）让早期粒子
                // 暗到与纱面分不开，前 1/3 的粒子几乎看不见。
                // 亮度不随年龄变（用户 2026-09-02：参考的粒子区域不会明暗不停变，
                // 只有前沿在动）。照白是常量，远处的尘只靠稀疏与 alpha 淡出变淡。
                float glow = CURTAIN_GLOW;
                vec3 lit3 = mix(color.rgb * boost * shade, vec3(1.0), glow);
                vColor = vec4(min(lit3, vec3(1.0)), color.a * fade);

                vec2 ndc = posPx / uViewportPx * 2.0 - 1.0;
                gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);
                // 逐粒子粒径：粒径不随年龄或寿命变化（浓淡只由存活数量产生），
                // 但同一批粒子必须有大有小。参考稀疏区的等效直径 P10/中位/P90
                // 是 2.35 / 3.32 / 6.22（卡宽千分比），P90/P10 约 2.7；而我们
                // 此前全部粒子同径，P10 恰好等于一个网格步长，屏幕上密密麻麻
                // 一片、没有层次。用 u*u 做右偏分布：多数偏小，少量明显更大。

                // 新生粒子必须更大。此前这里立过「粒径不得随年龄变化，浓淡只由存活
                // 数量产生」的契约，那条被前沿的实际观感推翻了：一个刚释放的格子
                // 若没被自己的粒子盖住就露出暗背景，前沿于是是一片**粗黑的洞**，
                // 而参考那里是**细密明亮的尘**——同内容放大对照可以直接看到。
                // 只放大新生的那一批，全局的浓淡不受影响。
                sizeVar *= mix(CURTAIN_FRESH_SIZE, 1.0, smoothstep(0.0, 0.18, age));
                gl_PointSize = clamp(
                    uCellPx * CURTAIN_POINT_RATIO * sizeVar * perspective,
                    1.0,
                    max(uMaxPointPx, 1.0)
                );
            }
        """.trimIndent()
        private val DISMISS_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;

            in highp vec4 vColor;
            out vec4 outColor;

            void main() {
                // 圆形软边细粒，粒径恒定。浓淡只由存活数量决定，不靠放大
                // 粒径或叠加同位置副本伪造。
                float radius = length(gl_PointCoord - 0.5);
                float shape = 1.0 - smoothstep(0.36, 0.50, radius);
                float alpha = vColor.a * shape;
                outColor = vec4(vColor.rgb * alpha, alpha);
            }
        """.trimIndent()

        /**
         * 未被任何帷幔触及的区域保留完整快照；局部前缘用约 133ms 的
         * 预转换窗口与原位细粒柔和交接。没有普通区域预先打孔，也没有
         * 被抽稀格子的提前擦除或整张快照模糊。
         */
        private val DISMISS_STILL_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            precision highp int;

            uniform sampler2D uSnapshot;
            uniform vec2 uOriginPx;
            uniform vec2 uSnapshotPx;
            uniform ivec2 uGrid;
            uniform float uTime;
            uniform uint uHashSeed;
            uniform vec2 uSweepDir;

            in highp vec2 vUv;
            out vec4 outColor;

            $DISMISS_FIELD_GLSL

            void main() {
                // 逐 cell 硬切：本格要么完整、要么已交给粒子层，没有中间的
                // 半透明状态，也没有孔洞噪声或相位滤波。参考实测的完整表面
                // 与原图逐位相同，位移 < 0.12 px。
                ivec2 cell = curtainCellOf(vUv);
                float releaseAt = curtainReleaseTime(cell);
                if (uTime >= releaseAt) {
                    discard;
                }
                vec4 color = texture(uSnapshot, vUv);
                // 这里曾经有一个「释放前压暗」：按本格离释放还有多久，把亮度
                // 压到 CURTAIN_PREDARK_FLOOR。它有两种写法，两种都不能要。
                //
                // 用**不含抖动的平滑时刻**算，压暗量在空间上连续，屏幕上是一条
                // 贴着前沿的柔和灰色渐变带。在参考那张照片上看不出来，在白色
                // 对话框上就是糊在白底上的一片灰影——用户指的「莫名其妙的暗区」
                // 就是它。
                //
                // 改用**本格自己的释放时刻**算，压暗量逐格随机，白底上是一片
                // 灰阶椒盐噪点。前一种像脏影，后一种像信号噪声，都不像侵蚀。
                //
                // 参考里根本没有亮度压暗这回事：那里「变暗」的观感完全来自
                // **已经走掉的散格露出深色背景**——本来就是颗粒状的，而且不需要
                // 任何额外构造，抖动已经提供了。既然如此就不要再压亮度。
                // 化掉前先暗一下：窄而浅、逐格。参考逐帧实测：离化掉 0.20 时亮度
                // 1.00、0.10 时 0.98、0.06 时 0.92、0.03 时 0.87——只在最后约 6% 的
                // 时间里暗到八七折，是贴着分界线的一道薄的、带颗粒感的灰边。
                // 按本格自己的（含抖动的）释放时刻算，灰边的边缘因此与分界线一样
                // 是颗粒状的，不会铺成一片。曾经的宽阴影（前方 30% 行程、最深
                // 五五折、平滑起伏）在白底上就是一片脏影，已删；控件本体不动。
                float lead = releaseAt - uTime;
                float dim = mix(CURTAIN_PREDARK_FLOOR, 1.0,
                                smoothstep(0.0, CURTAIN_PREDARK_LEAD, lead));
                outColor = vec4(color.rgb * dim * color.a, color.a);
            }
        """.trimIndent()
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
    }
}
