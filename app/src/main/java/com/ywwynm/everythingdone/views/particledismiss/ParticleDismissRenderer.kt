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
import kotlin.math.ceil
import kotlin.math.min

/**
 * Dialog 粒子消散的 GL 渲染线程：RGBA8888 / ES 3.0，单 program、每帧一个
 * `GL_POINTS` draw call。
 *
 * 粒子完全无状态：逐粒子属性（网格位置、颜色、随机数）全部在 vertex shader 内由
 * `gl_VertexID` 派生，位置是时间的闭式函数，CPU 每帧只更新一个 uTime。t=0 时全体
 * 粒子静止且不透明，逐像素拼回快照原图，因此真实 Dialog 的消失与动画起始之间没有
 * 视觉断裂；波前扫过后粒子获得初速度并伴随湍流飘散、alpha 衰减。
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
        val cellPx = spec.cellPx
        val program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        val stillProgram = createProgram(STILL_VERTEX_SHADER, STILL_FRAGMENT_SHADER)
        val cols = ceil(snapshot.width / cellPx).toInt().coerceAtLeast(1)
        val rows = ceil(snapshot.height / cellPx).toInt().coerceAtLeast(1)
        val particleCount = cols * rows

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
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, particleCount)
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
        /** 粒子总数上限；超出时由 controller 放大网格步长自适应。 */
        const val MAX_PARTICLES = 150_000

        /**
         * 消散的波前扫过时长（逻辑秒）。取值偏长："半卡半云"的过渡期是观感
         * 核心（2026-08-26 桌面蓝本第二轮验证）。凝聚模式不用此值（见
         * Controller 的凝聚专用参数）。
         */
        internal const val SPREAD_TIME = 0.40f

        /** 消散的逐粒子激活抖动上限，打碎波前与快照边缘的整齐感。 */
        internal const val DELAY_JITTER = 0.12f

        /** 消散的锋线低频扭曲幅度（秒）：溶解边界呈弯曲参差的不规则线。 */
        internal const val WAVE_WARP = 0.18f

        /** 激活即刻的起飞冲量（ease 占比）：消除"点阵化但原地不动"的假 dialog 带。 */
        private const val KICK = 0.02f

        /** 单粒子从激活到消失的基准寿命；逐粒子按 lifeMod（0.55–1.25）调制。 */
        private const val LIFETIME = 0.58f

        const val TOTAL_DURATION =
            SPREAD_TIME + DELAY_JITTER + WAVE_WARP * 0.5f + LIFETIME * 1.25f

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

            out highp vec4 vColor;
            out highp float vActivation;

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
                int ix = gl_VertexID % uGrid.x;
                int iy = gl_VertexID / uGrid.x;
                vec2 cell = vec2(float(ix), float(iy));
                vec2 uv = (cell + 0.5) / vec2(uGrid);

                // PCG 整数 hash：gl_VertexID 到 15 万量级时浮点 sin-hash 的有效
                // 随机位不足，会出现可见条带；uHashSeed 让逐粒子行为每次不同
                uint h = uint(gl_VertexID) ^ uHashSeed;
                h = h * 747796405u + 2891336453u;
                h = ((h >> ((h >> 28u) + 4u)) ^ h) * 277803737u;
                h = (h >> 22u) ^ h;
                float h1 = float(h & 1023u) * 0.0009775171;
                float h2 = float((h >> 10u) & 1023u) * 0.0009775171;
                float h3 = float((h >> 20u) & 1023u) * 0.0009775171;
                float h4 = fract(h1 + h2 * 0.618034);
                float h5 = fract(h2 + h3 * 0.618034);

                // 波前：起点到本粒子的像素距离按快照对角线归一化
                vec2 snapshotPx = vec2(uGrid) * uCellPx;
                vec2 basePx = uOriginPx + (cell + 0.5) * uCellPx;
                float waveDist = distance(uv * snapshotPx, uWaveOriginUv * snapshotPx)
                        / length(snapshotPx);
                // 锋线低频扭曲：溶解边界不再是从触点扩散的光滑圆弧，而是弯曲
                // 参差的不规则线（华为效果的锋线形态）。静止层用完全同款公式
                float waveWarp = (vnoise(basePx / (uNoiseScalePx * 0.6) + 7.7) - 0.5)
                        * uWaveWarp;
                float delay = waveDist * uSpreadTime + waveWarp + h1 * uDelayJitter;

                // 未激活的粒子由静止层以逐像素原图呈现（粒子拼图是网格重采样，
                // 文字会糊），这里直接移出裁剪范围
                if (uTime - delay <= 0.0) {
                    vColor = vec4(0.0);
                    vActivation = 0.0;
                    gl_PointSize = 1.0;
                    gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
                    return;
                }
                // 寿命不均：区域 + 逐粒子双层，有的区域早早消散变稀、有的坚持
                // 到最后成余缕——密度分布随时间演化不均，更灵动
                float lifeMod = 0.55 + 0.7 * (
                    0.5 * vnoise(basePx / (uNoiseScalePx * 0.9) + 67.9) + 0.5 * h5
                );
                float tl = clamp((uTime - delay) / (uLifetime * lifeMod), 0.0, 1.0);

                // vertex 阶段无自动 LOD，显式取 0 级；只有激活粒子才需要颜色
                vec4 color = textureLod(uSnapshot, uv, 0.0);

                // 前慢后快 + 激活即刻的小冲量：几帧内滑出约 4dp，消除"点阵化但
                // 原地不动"的假 dialog 带（锋线后的粒子立即离位、边缘起沙散开）
                float ease = pow(tl, 1.35) + uKick * smoothstep(0.0, 0.06, tl);
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

                // 烟缕浓淡：低频噪声调制透明度（0.4~1.0），激活后才参与
                float density = min(
                    0.4 + 0.75 * vnoise(basePx / uNoiseScalePx * 0.8 + 17.3), 1.0
                );
                // 激活即开始衰减（0.15 起步时锋线后有一条满 alpha 的实心粒子带）
                float fade = pow(1.0 - smoothstep(0.02, 0.92, tl), 1.7);
                float alphaMul = mix(1.0, density, smoothstep(0.05, 0.35, tl));
                vColor = vec4(color.rgb, color.a * fade * alphaMul);
                vActivation = smoothstep(0.0, 0.05, tl);

                // 粒子大小不均：低频区域差 × 逐粒子随机（平方偏斜：多数小、
                // 偶有大颗粒），静止拼图由静止层负责后尺寸已无约束
                float sizeMod = (0.7 + 0.6 * vnoise(basePx / (uNoiseScalePx * 0.5) + 53.1))
                        * (0.55 + 0.95 * h4 * h4);
                // 尺寸曲线 1.2/0.4 -> 1.0/0.34（约 -17%）：用户反馈稍大
                gl_PointSize = clamp(
                    mix(uCellPx * 1.0, uCellPx * 0.34, tl) * sizeMod, 1.0, uMaxPointPx
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
            out vec4 outColor;

            void main() {
                // 静止阶段为方点（无缝覆盖网格），激活后渐变为软边圆点
                float r = length(gl_PointCoord - 0.5);
                float circle = 1.0 - smoothstep(0.30, 0.5, r);
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
            uniform float uCellPx;
            uniform ivec2 uGrid;

            out highp vec2 vUv;

            void main() {
                vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
                vUv = corner;
                vec2 posPx = uOriginPx + corner * vec2(uGrid) * uCellPx;
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
                // 与粒子层完全同款的锋线扭曲，保持擦除边界精确对齐
                float waveWarp = (vnoise(basePx / (uNoiseScalePx * 0.6) + 7.7) - 0.5)
                        * uWaveWarp;
                float delay = waveDist * uSpreadTime + waveWarp + h1 * uDelayJitter;
                if (uTime > delay) discard;
                vec4 c = texture(uSnapshot, vUv);
                outColor = vec4(c.rgb * c.a, c.a);
            }
        """.trimIndent()

        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
    }
}
