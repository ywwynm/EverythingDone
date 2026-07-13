package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.View

import com.ywwynm.everythingdone.model.ThingBackground

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * FableSol 录音水波可视化：把 audioVisualizerSimulatorFable 的实时分析 → 九层水体物理 → 渲染
 * 一比一移植进来（见 docs/features/audio-visualization-fable-sol/）。物理/高光复刻原版，配色改接
 * 记事 [ThingBackground]（纯色 + 渐变，渐变方向沿用记事 8 向）。音频帧在采集线程到达
 * （[onAudioFrames]）入并发队列，vsync 帧循环 drain → mapper → simulation → 渲染，二者解耦。
 */
class WaveVisualizerFableSol @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs), FableSolFrameReceiver {

    private val density = resources.displayMetrics.density.toDouble()

    private val params = FableSolParams()
    private val sim = FableSolSimulation(params)
    private val mapper = FableSolFeatureMapper(params)

    private var c1Base = FableSolColor.fromColor(Color.parseColor("#F02A4B"))
    private var c2Base = FableSolLayerColorPolicy.baseColors(c1Base, null).end
    private var bgOrientation: ThingBackground.Orientation = ThingBackground.Orientation.T_B
    private var bgIsGradient = false

    // 两个 Paint 隔离 alpha：fillPaint 专管渐变填充（环境天空 / 层水面 / 单侧透光 fade 带），
    // alpha 恒 255、透明度全走渐变 stop；bandPaint 管纯色高光带（setColor 自带 alpha）。
    // 若共用一个 Paint，高光带 setColor 会把 Paint.alpha 改小，泄漏到下一层的渐变填充——Android
    // 会用 Paint.alpha 调制 shader，使水体被压成半透明，且该 alpha 随高光每帧脉动 → 透明 + 闪烁。
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPath = Path()
    private val bandPath = Path()

    private val lock = Any()
    private var pendingFrames = ArrayList<FableSolFeatureFrame>()
    private var pendingEvents = ArrayList<FableSolEvent>()

    private var mLastFrameTimeNanos = 0L
    private var mAnimating = false
    private var mFrameCallbackPosted = false
    private var mPendingFrameTimeNanos = 0L
    private var mLastAudioElapsed = 0L
    private var mGravitySeeded = false
    private val framePacer = FableSolFramePacer(TARGET_FPS)
    private val gravityInbox = FableSolGravityInbox()
    private val gravityScratch = FloatArray(3)
    private var consumedGravitySequence = 0
    private var performanceMonitor: FableSolPerformanceMonitor? = null
    private var renderSamplingNs = 0L
    private var renderColorNs = 0L
    private var renderAssemblyNs = 0L
    private var glFallbackDiagnostic = false
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        mFrameCallbackPosted = false
        if (!shouldAnimate()) {
            mAnimating = false
            framePacer.reset()
            return@FrameCallback
        }
        if (framePacer.shouldRender(frameTimeNanos)) {
            mPendingFrameTimeNanos = frameTimeNanos
            invalidate()
        }
        scheduleFrameCallback()
    }

    // 渲染 scratch
    private val xsPx = DoubleArray(FableSolSpec.N_POINTS)
    private val ysPx = DoubleArray(FableSolSpec.N_POINTS)
    private val surfaceXsPx = Array(FableSolContinuousSurface.Z_ROWS) {
        DoubleArray(FableSolSpec.N_POINTS)
    }
    private val surfaceYsPx = Array(FableSolContinuousSurface.Z_ROWS) {
        DoubleArray(FableSolSpec.N_POINTS)
    }
    // 每个九层深度区间含 3 条 ribbon；三角网格按区间批量提交，把 24 次 Path+Gradient
    // 降为 8 次 drawVertices。数组按最大 3×215×6 个顶点预分配，帧内零扩容。
    private val surfaceMeshVertices = FloatArray(
        FableSolContinuousSurface.ROWS_PER_LAYER * (FableSolSpec.N_POINTS - 1) * 6 * 2
    )
    private val surfaceMeshColors = IntArray(
        FableSolContinuousSurface.ROWS_PER_LAYER * (FableSolSpec.N_POINTS - 1) * 6
    )
    private val surfaceVertexColors = Array(FableSolContinuousSurface.Z_ROWS) {
        IntArray(FableSolSpec.N_POINTS)
    }
    private val surfaceSourceIndex = IntArray(FableSolSpec.N_POINTS)
    private val surfaceSourceFraction = DoubleArray(FableSolSpec.N_POINTS)
    private val surfaceSlopeX = Array(FableSolContinuousSurface.Z_ROWS) {
        DoubleArray(FableSolSpec.N_POINTS)
    }
    private val surfaceSlopeZ = Array(FableSolContinuousSurface.Z_ROWS) {
        DoubleArray(FableSolSpec.N_POINTS)
    }
    private val layerMeans = DoubleArray(FableSolSpec.N_LAYERS)
    // 各层光学函数同步嵌套调用；用游标式帧内池替代每层每帧的短命 DoubleArray。
    // 每个锚层绘制结束即回退游标，因此容量按单层最大并发量而非九层总量计算。
    private val doubleScratch = Array(DOUBLE_SCRATCH_CAPACITY) {
        DoubleArray(FableSolSpec.N_POINTS)
    }
    private var doubleScratchIndex = 0

    // 环境基色（D21 修复）：暗色模式下对话框表面是深色，天空基色必须取主题
    // colorBackground 而不是硬编码白——否则录音开始的 View alpha 变化会呈现
    // "昏暗天空突然翻白"。
    private val envBase: IntArray = run {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
            && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT
        ) FableSolColor.fromColor(tv.data) else intArrayOf(255, 255, 255)
    }

    // A5 实体跟踪：闪点/珍珠/流光是持久实体（跟随浪面滑行、软生软灭），
    // 不做逐帧重新选峰——那会读作锯齿闪烁/一闪而过（Python 版被用户否决过）。
    private class Track(var u: Double, var inten: Double, var size: Double, val seed: Double)
    private class Streak(var u: Double, var age: Double, val life: Double,
                         val len: Double, val seed: Double)
    private val glintTracks = Array(FableSolSpec.N_LAYERS) { ArrayList<Track>(4) }
    private val streakTracks = Array(FableSolSpec.N_LAYERS) { ArrayList<Streak>(4) }
    private val streakSeq = IntArray(FableSolSpec.N_LAYERS)
    private val streakNextT = DoubleArray(FableSolSpec.N_LAYERS)
    private var trackT = 0.0
    private val unitRect = RectF(-1f, -1f, 1f, 1f)
    // C 阶段返工：AGSL 不允许 uniform 数组动态索引（真机红屏确诊），轮廓数据改
    // 编码进 RGBA_F16 位图（r=top01、g=th01，归一化后半浮点精度 ≈0.3px）。每次
    // 绘制从池中取新位图——显示列表不快照位图内容，同帧复用会串数据。
    // 位图池按三帧轮换：UI 线程写第 N+1 帧数据时 RenderThread 可能仍在渲染
    // 引用同一位图的第 N 帧显示列表（位图内容不做快照），同池跨帧复用会
    // 偶发串帧闪烁（用户真机实测）。
    private val dataBitmapPools = Array(3) { ArrayList<android.graphics.Bitmap>(40) }
    private var poolPhase = 0
    private var dataBitmapIdx = 0
    private val halfBuf: java.nio.ShortBuffer =
        java.nio.ShortBuffer.allocate(FableSolSpec.N_POINTS * 4)

    private fun contourData(top: DoubleArray, th: DoubleArray?, aux: DoubleArray?,
                            cnt: Int, yMin: Double, yRange: Double): android.graphics.BitmapShader {
        val pool = dataBitmapPools[poolPhase]
        if (dataBitmapIdx >= pool.size) {
            pool.add(android.graphics.Bitmap.createBitmap(
                FableSolSpec.N_POINTS, 1, android.graphics.Bitmap.Config.RGBA_F16))
        }
        val bmp = pool[dataBitmapIdx++]
        val inv = 1.0 / yRange
        val zero = android.util.Half.toHalf(0f)
        val one = android.util.Half.toHalf(1f)
        halfBuf.clear()
        for (j in 0 until FableSolSpec.N_POINTS) {
            if (j < cnt) {
                halfBuf.put(android.util.Half.toHalf(((top[j] - yMin) * inv).toFloat()))
                halfBuf.put(if (th != null)
                    android.util.Half.toHalf((th[j] * inv).toFloat()) else zero)
                halfBuf.put(if (aux != null)
                    android.util.Half.toHalf(aux[j].toFloat()) else zero)
            } else { halfBuf.put(zero); halfBuf.put(zero); halfBuf.put(zero) }
            halfBuf.put(one)
        }
        halfBuf.rewind()
        bmp.copyPixelsFromBuffer(halfBuf)
        return android.graphics.BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    // 焦散已按用户裁决整体移除（2026-07-11，两轮修形后仍"不好看"——宁少勿烂）。
    private val anchorsScratch = ArrayList<DoubleArray>(6)

    fun setThingBackground(background: ThingBackground) {
        val isGradient = background.mode == ThingBackground.Mode.GRADIENT
        val baseColors = FableSolLayerColorPolicy.baseColors(
            FableSolColor.fromColor(background.color),
            if (isGradient) FableSolColor.fromColor(background.endColor) else null
        )
        c1Base = baseColors.start
        c2Base = baseColors.end
        if (isGradient) {
            bgOrientation = background.orientation
            bgIsGradient = true
        } else {
            bgOrientation = ThingBackground.Orientation.T_B
            bgIsGradient = false
        }
        invalidate()
    }

    internal fun setPerformanceMonitor(monitor: FableSolPerformanceMonitor?) {
        performanceMonitor = monitor
    }

    internal fun setGlFallbackDiagnostic(enabled: Boolean) {
        glFallbackDiagnostic = enabled
        invalidate()
    }

    /** 完整三维重力方向 → 左右滚转 + 连续水面的前后俯仰。 */
    fun setContainerGravity(x: Float, y: Float, z: Float) {
        gravityInbox.offer(x, y, z)
    }

    /** 只在帧循环消费传感器线程写入的最后一个样本，避免跨线程修改 Simulation。 */
    private fun applyLatestGravity() {
        val sequence = gravityInbox.drainLatest(consumedGravitySequence, gravityScratch)
        if (sequence == consumedGravitySequence) return
        consumedGravitySequence = sequence
        val x = gravityScratch[0]
        val y = gravityScratch[1]
        val z = gravityScratch[2]
        val deg = Math.toDegrees(atan2(x.toDouble(), y.toDouble()))
        val pitch = Math.toDegrees(atan2(z.toDouble(), hypot(x.toDouble(), y.toDouble())))
        if (!mGravitySeeded) {
            sim.setTilt(deg, snap = true)
            sim.setPitch(pitch, snap = true)
            mGravitySeeded = true
        } else {
            sim.setTilt(deg, snap = false)
            sim.setPitch(pitch, snap = false)
        }
    }

    // ------------------------------------------------------------------ 音频接收（采集线程）
    override fun onAudioFrames(frames: List<FableSolFeatureFrame>, events: List<FableSolEvent>) {
        if (frames.isEmpty() && events.isEmpty()) return
        synchronized(lock) {
            pendingFrames.addAll(frames)
            pendingEvents.addAll(events)
        }
    }

    // ------------------------------------------------------------------ 帧循环
    override fun onDraw(canvas: Canvas) {
        val frameTimeNanos = if (mPendingFrameTimeNanos != 0L) {
            mPendingFrameTimeNanos.also { mPendingFrameTimeNanos = 0L }
        } else {
            SystemClock.elapsedRealtimeNanos()
        }
        val now = SystemClock.elapsedRealtime()
        var dt = if (mLastFrameTimeNanos == 0L) {
            TARGET_FRAME_SECONDS.toFloat()
        } else {
            (frameTimeNanos - mLastFrameTimeNanos) / 1_000_000_000f
        }
        mLastFrameTimeNanos = frameTimeNanos
        if (dt <= 0f) dt = 0.016f
        if (dt > MAX_DT) dt = MAX_DT

        val drainStart = SystemClock.elapsedRealtimeNanos()
        drainAndApply(now)
        applyLatestGravity()
        val physicsStart = SystemClock.elapsedRealtimeNanos()
        sim.update(dt.toDouble())
        val drawStart = SystemClock.elapsedRealtimeNanos()
        renderSamplingNs = 0L
        renderColorNs = 0L
        renderAssemblyNs = 0L
        drawWater(canvas)
        val drawEnd = SystemClock.elapsedRealtimeNanos()
        val residual = (drawEnd - drawStart - renderSamplingNs - renderColorNs - renderAssemblyNs)
            .coerceAtLeast(0L)
        performanceMonitor?.recordDrawStages(
            physicsStart - drainStart,
            drawStart - physicsStart,
            renderSamplingNs,
            renderColorNs,
            renderAssemblyNs,
            residual
        )
    }

    private fun drainAndApply(now: Long) {
        val frames: ArrayList<FableSolFeatureFrame>?
        val events: ArrayList<FableSolEvent>
        synchronized(lock) {
            frames = if (pendingFrames.isEmpty()) null else pendingFrames
            if (frames != null) pendingFrames = ArrayList()
            events = pendingEvents
            pendingEvents = ArrayList()
        }
        if (frames != null) {
            mapper.applyFrame(sim, frames[frames.size - 1])
            mLastAudioElapsed = now
        } else if (mLastAudioElapsed != 0L && now - mLastAudioElapsed > IDLE_SILENCE_MS) {
            mapper.applySilence(sim)
        }
        for (e in events) when (e) {
            is FableSolEvent.Onset -> mapper.applyOnset(sim, e)
            is FableSolEvent.Section -> mapper.applySection(sim, e)
            is FableSolEvent.Prominence -> mapper.applyProminence(sim, e)
        }
    }

    private fun ensureAnimating() {
        if (!mAnimating && shouldAnimate()) {
            mAnimating = true
            mLastFrameTimeNanos = 0L
            framePacer.reset()
        }
        if (mAnimating) scheduleFrameCallback()
    }

    private fun scheduleFrameCallback() {
        if (mFrameCallbackPosted) return
        mFrameCallbackPosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        if (mFrameCallbackPosted) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            mFrameCallbackPosted = false
        }
        mAnimating = false
        mPendingFrameTimeNanos = 0L
        mLastFrameTimeNanos = 0L
        framePacer.reset()
    }

    private fun shouldAnimate(): Boolean = isAttachedToWindow && width > 0 && height > 0 &&
        windowVisibility == VISIBLE && isShown

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ensureAnimating() }
    override fun onDetachedFromWindow() {
        stopFrameLoop()
        super.onDetachedFromWindow()
        mGravitySeeded = false
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) ensureAnimating() else stopFrameLoop()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) ensureAnimating() else stopFrameLoop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && density > 0.0) {
            // 必须使用布局系统最终测得的 View 宽度；XML 280dp/TimelyClockView 只参与上游测量。
            sim.setContainerWidthDp(w / density)
        }
        // attach 常先于首次 layout：onAttachedToWindow 时宽高仍为 0，帧循环不会启动。
        // 首次拿到有效尺寸后必须再次尝试，否则只会留下布局触发的那一张静止画面。
        ensureAnimating()
    }

    // BaseDialogFragment 根布局 WRAP_CONTENT、靠 min 尺寸定尺：match_parent 的本 View 若走默认
    // onMeasure（getDefaultSize）会在 AT_MOST 下上报可用空间、把对话框撑大。故只上报固有尺寸，
    // 让根布局 min 尺寸决定对话框大小（FrameLayout 会对 match_parent 子项以 EXACTLY 二次测量铺满）。
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveIntrinsic(widthMeasureSpec, INTRINSIC_W_DP),
            resolveIntrinsic(heightMeasureSpec, INTRINSIC_H_DP)
        )
    }

    private fun resolveIntrinsic(spec: Int, dp: Float): Int {
        val mode = MeasureSpec.getMode(spec)
        val size = MeasureSpec.getSize(spec)
        val intrinsic = (dp * resources.displayMetrics.density).toInt()
        return when (mode) {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> min(size, intrinsic)
            else -> intrinsic
        }
    }

    // ------------------------------------------------------------------ 渲染
    private fun drawWater(canvas: Canvas) {
        doubleScratchIndex = 0
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        drawEnvironment(canvas, w, h)

        val continuous = params.get("surface2d_on") >= 0.5
        val info = if (continuous) sim.continuousRenderInfo() else sim.renderInfo()
        val i0 = info.i0; val i1 = info.i1
        val rawCnt = i1 - i0
        val cnt = if (continuous) sim.continuousRenderColumnCount(rawCnt) else rawCnt
        if (cnt < 2) return
        val hG = info.hG
        if (continuous) {
            for (j in 0 until cnt) {
                val source = sim.continuousRenderSourcePosition(i0, rawCnt, cnt, j)
                val index = min(source.toInt(), i1 - 2)
                val fraction = (source - index).coerceIn(0.0, 1.0)
                surfaceSourceIndex[j] = index
                surfaceSourceFraction[j] = fraction
                xsPx[j] = (sim.uGrid[index] * (1.0 - fraction) +
                    sim.uGrid[index + 1] * fraction) * density
            }
        } else {
            for (j in 0 until cnt) xsPx[j] = sim.uGrid[i0 + j] * density
        }
        val fillBottom = hG / 2.0 * density + FILL_EXTRA_DP * density

        val save = canvas.save()
        canvas.translate(w / 2f, h / 2f)
        canvas.rotate(-Math.toDegrees(info.thetaRad).toFloat())
        poolPhase = (poolPhase + 1) % 3  // 三帧轮换防跨帧位图别名
        dataBitmapIdx = 0  // 位图池按帧复位（必须在层循环之前——填充与软带都会取用）
        if (continuous) {
            drawContinuousSurface(canvas, info, cnt, fillBottom)
        } else {
            // 内部 A 基线：原九层完整水体，远→近各自填到底部。
            for (i in FableSolSpec.N_LAYERS - 1 downTo 0) {
                val row = sim.heights[i]
                for (j in 0 until cnt) ysPx[j] = (hG / 2.0 - row[i0 + j]) * density
                drawLayer(canvas, i, cnt, fillBottom, i0)
            }
        }
        canvas.restoreToCount(save)
        trackT = sim.t  // 实体跟踪时基（帧末推进，各层同帧共享同一 dt）
    }

    private fun drawEnvironment(canvas: Canvas, w: Float, h: Float) {
        val strength = params.get("environment_tint")
        val top = FableSolColor.mixOklab(envBase, FableSolColor.mixOklab(c2Base, WHITE, 0.72), strength * 0.55)
        val horizon = FableSolColor.mixOklab(envBase, FableSolColor.mixOklab(c1Base, WHITE, 0.78), strength)
        val bottom = FableSolColor.mixOklab(envBase, FableSolColor.mixOklab(c2Base, WHITE, 0.84), strength * 0.42)
        fillPaint.shader = dithered(LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(FableSolColor.toColor(top, 255), FableSolColor.toColor(horizon, 255), FableSolColor.toColor(bottom, 255)),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP
        ))
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        fillPaint.shader = null
    }

    private class LayerColors(val start: IntArray, val end: IntArray, val alpha255: Int)

    private fun layerColors(i: Int): LayerColors {
        val depth01 = i.toDouble() / (FableSolSpec.N_LAYERS - 1)
        val breath = params.get("color_breath") *
            (0.30 * (sim.colorBright01 - 0.45) + 0.18 * (sim.colorEnergy01 - 0.5))
        val f = FableSolLayerColorPolicy.lightenAmount(
            depth01, params.get("lighten_far"), sim.moodBright, breath
        )
        return LayerColors(
            FableSolColor.mixOklab(c1Base, WHITE, f),
            FableSolColor.mixOklab(c2Base, WHITE, f),
            (params.lget("alpha", i) * 255).roundToInt().coerceIn(0, 255)
        )
    }

    /**
     * 九层状态吸收到一张连续曲面：25 条 Z 行共享边界，远→近绘制 ribbon；九条
     * 原层轮廓继续作为几何、颜色与完整光学族锚线，第一层是唯一前景剪影。
     */
    private fun drawContinuousSurface(canvas: Canvas, info: FableSolRenderInfo,
                                      cnt: Int, fillBottom: Double) {
        val samplingStart = SystemClock.elapsedRealtimeNanos()
        val sample = sim.surface2d.sample(sim)
        val means = layerMeans
        for (i in means.indices) {
            var sum = 0.0
            for (v in sim.heights[i]) sum += v
            means[i] = sum / sim.heights[i].size
        }
        val viewBase = params.get("surface_view_elev_deg")
        val viewElev = FableSolPitchPolicy.viewElevationDeg(sim.pitchDeg, viewBase)
        val depthScale = sin(Math.toRadians(viewElev)) /
            max(sin(Math.toRadians(viewBase)), 0.2)
        for (r in 0 until FableSolContinuousSurface.Z_ROWS) {
            for (j in 0 until cnt) {
                val xIndex = surfaceSourceIndex[j]
                val fraction = surfaceSourceFraction[j]
                val orbitZ = sample.orbitZ[r][xIndex] * (1.0 - fraction) +
                    sample.orbitZ[r][xIndex + 1] * fraction
                val orbitX = sample.orbitX[r][xIndex] * (1.0 - fraction) +
                    sample.orbitX[r][xIndex + 1] * fraction
                val worldEta = sample.worldEta[r][xIndex] * (1.0 - fraction) +
                    sample.worldEta[r][xIndex + 1] * fraction
                val uDp = sim.uGrid[xIndex] * (1.0 - fraction) +
                    sim.uGrid[xIndex + 1] * fraction
                val zEff = sample.zDp[r] + orbitZ
                val z01 = (zEff / max(sample.depthDp, 1e-6)).coerceIn(-0.08, 1.08)
                val f = z01.coerceIn(0.0, 1.0) * (FableSolSpec.N_LAYERS - 1)
                val a = min(f.toInt(), FableSolSpec.N_LAYERS - 2)
                val q = f - a
                var baseH = means[a] + (means[a + 1] - means[a]) * q
                baseH = means[0] + (baseH - means[0]) * depthScale
                val perspective = 1.0 / (1.0 + 0.16 * z01.coerceIn(0.0, 1.1))
                surfaceXsPx[r][j] = (uDp + orbitX) *
                    density * perspective
                surfaceYsPx[r][j] = (info.hG / 2.0 -
                    (baseH + worldEta)) * density
                surfaceSlopeX[r][j] = sample.slopeX[r][xIndex] * (1.0 - fraction) +
                    sample.slopeX[r][xIndex + 1] * fraction
                surfaceSlopeZ[r][j] = sample.slopeZ[r][xIndex] * (1.0 - fraction) +
                    sample.slopeZ[r][xIndex + 1] * fraction
            }
        }
        renderSamplingNs += SystemClock.elapsedRealtimeNanos() - samplingStart

        val colorStart = SystemClock.elapsedRealtimeNanos()
        val palettes = Array(FableSolSpec.N_LAYERS) { layerColors(it) }
        val colorStops = Array(FableSolSpec.N_LAYERS) { i ->
            arrayOf(
                palettes[i].start,
                FableSolColor.mixOklab(palettes[i].start, palettes[i].end, 0.21),
                FableSolColor.mixOklab(palettes[i].start, palettes[i].end, 0.56),
                palettes[i].end
            )
        }
        val environment = environmentColors()
        val gradientGeometry = buildLayerGradientGeometry(cnt, fillBottom)
        renderColorNs += SystemClock.elapsedRealtimeNanos() - colorStart

        // 每三行恰好落在一个九层锚点。先完成该深度区间，再画其远端锚线光学；
        // 后绘制的近侧 ribbon 自然遮挡越界部分，保持原层间遮挡语义。
        for (layer in FableSolSpec.N_LAYERS - 1 downTo 1) {
            val farAnchor = layer * FableSolContinuousSurface.ROWS_PER_LAYER
            val nearAnchor = (layer - 1) * FableSolContinuousSurface.ROWS_PER_LAYER
            drawContinuousRibbonGroup(canvas, farAnchor, nearAnchor, layer,
                cnt, viewElev, info.thetaRad, palettes, colorStops,
                gradientGeometry, environment)
            drawContinuousOptics(canvas, layer, farAnchor, cnt, palettes[layer])
        }

        // 第一层以下的深水体积；第一层是唯一近端剪影。
        for (j in 0 until cnt) {
            xsPx[j] = surfaceXsPx[0][j]
            ysPx[j] = surfaceYsPx[0][j]
        }
        fillPath.reset()
        buildSmooth(fillPath, xsPx, ysPx, cnt, true)
        fillPath.lineTo(xsPx[cnt - 1].toFloat(), fillBottom.toFloat())
        fillPath.lineTo(xsPx[0].toFloat(), fillBottom.toFloat())
        fillPath.close()
        val front = palettes[0]
        fillPaint.shader = dithered(layerShader(front.start, front.end, 255, cnt, fillBottom))
        canvas.drawPath(fillPath, fillPaint)
        fillPaint.shader = null
        drawContinuousOptics(canvas, 0, 0, cnt, front)
    }

    private class LayerGradientGeometry(
        val ox: Double, val oy: Double, val dx: Double, val dy: Double,
        val denominator: Double
    )

    private class EnvironmentColors(
        val top: IntArray, val horizon: IntArray, val bottom: IntArray,
        val horizonColor: Int
    )

    private fun buildLayerGradientGeometry(cnt: Int, fillBottom: Double): Array<LayerGradientGeometry> =
        Array(FableSolSpec.N_LAYERS) { layer ->
        val row = layer * FableSolContinuousSurface.ROWS_PER_LAYER
        var x0 = surfaceXsPx[row][0]
        var x1 = x0
        var yTop = surfaceYsPx[row][0]
        for (j in 1 until cnt) {
            x0 = min(x0, surfaceXsPx[row][j])
            x1 = max(x1, surfaceXsPx[row][j])
            yTop = min(yTop, surfaceYsPx[row][j])
        }
        val midX = (x0 + x1) * 0.5
        val midY = (yTop + fillBottom) * 0.5
        val orientation = if (bgIsGradient) bgOrientation else ThingBackground.Orientation.T_B
        val ox: Double; val oy: Double; val dx: Double; val dy: Double
        when (orientation) {
            ThingBackground.Orientation.L_R -> { ox = x0; oy = midY; dx = x1 - x0; dy = 0.0 }
            ThingBackground.Orientation.R_L -> { ox = x1; oy = midY; dx = x0 - x1; dy = 0.0 }
            ThingBackground.Orientation.T_B -> { ox = midX; oy = yTop; dx = 0.0; dy = fillBottom - yTop }
            ThingBackground.Orientation.B_T -> { ox = midX; oy = fillBottom; dx = 0.0; dy = yTop - fillBottom }
            ThingBackground.Orientation.LT_RB -> { ox = x0; oy = yTop; dx = x1 - x0; dy = fillBottom - yTop }
            ThingBackground.Orientation.RB_LT -> { ox = x1; oy = fillBottom; dx = x0 - x1; dy = yTop - fillBottom }
            ThingBackground.Orientation.LB_RT -> { ox = x0; oy = fillBottom; dx = x1 - x0; dy = yTop - fillBottom }
            ThingBackground.Orientation.RT_LB -> { ox = x1; oy = yTop; dx = x0 - x1; dy = fillBottom - yTop }
        }
        LayerGradientGeometry(ox, oy, dx, dy, max(dx * dx + dy * dy, 1e-6))
    }

    private fun environmentColors(): EnvironmentColors {
        if (glFallbackDiagnostic) {
            return EnvironmentColors(GL_FALLBACK_RED, GL_FALLBACK_RED, GL_FALLBACK_RED, Color.RED)
        }
        val strength = params.get("environment_tint")
        val top = FableSolColor.mixOklab(
            envBase, FableSolColor.mixOklab(c2Base, WHITE, 0.72), strength * 0.55
        )
        val horizon = FableSolColor.mixOklab(
            envBase, FableSolColor.mixOklab(c1Base, WHITE, 0.78), strength
        )
        val bottom = FableSolColor.mixOklab(
            envBase, FableSolColor.mixOklab(c2Base, WHITE, 0.84), strength * 0.42
        )
        return EnvironmentColors(top, horizon, bottom, FableSolColor.toColor(horizon, 255))
    }

    /** 三条 ribbon 合成一次三角网格提交；颜色边界通过逐 ribbon 重复顶点保持清晰。 */
    private fun drawContinuousRibbonGroup(canvas: Canvas,
                                          farAnchor: Int, nearAnchor: Int, startLayer: Int,
                                          cnt: Int, viewElev: Double,
                                          thetaRad: Double, palettes: Array<LayerColors>,
                                           colorStops: Array<Array<IntArray>>,
                                           geometry: Array<LayerGradientGeometry>,
                                           environment: EnvironmentColors) {
        val colorStart = SystemClock.elapsedRealtimeNanos()
        for (r in nearAnchor..farAnchor) {
            for (j in 0 until cnt) {
                surfaceVertexColors[r][j] = continuousVertexColor(
                    r, j, startLayer, viewElev, thetaRad,
                    palettes, colorStops, geometry, environment
                )
            }
        }
        renderColorNs += SystemClock.elapsedRealtimeNanos() - colorStart
        val assemblyStart = SystemClock.elapsedRealtimeNanos()
        var vertex = 0
        for (r in farAnchor - 1 downTo nearAnchor) {
            val far = r + 1
            for (j in 0 until cnt - 1) {
                val far0 = surfaceVertexColors[far][j]
                val near0 = surfaceVertexColors[r][j]
                val far1 = surfaceVertexColors[far][j + 1]
                val near1 = surfaceVertexColors[r][j + 1]
                vertex = putSurfaceVertex(vertex, surfaceXsPx[far][j], surfaceYsPx[far][j], far0)
                vertex = putSurfaceVertex(vertex, surfaceXsPx[r][j], surfaceYsPx[r][j], near0)
                vertex = putSurfaceVertex(vertex, surfaceXsPx[far][j + 1], surfaceYsPx[far][j + 1], far1)
                vertex = putSurfaceVertex(vertex, surfaceXsPx[r][j], surfaceYsPx[r][j], near0)
                vertex = putSurfaceVertex(vertex, surfaceXsPx[r][j + 1], surfaceYsPx[r][j + 1], near1)
                vertex = putSurfaceVertex(vertex, surfaceXsPx[far][j + 1], surfaceYsPx[far][j + 1], far1)
            }
        }
        renderAssemblyNs += SystemClock.elapsedRealtimeNanos() - assemblyStart
        fillPaint.shader = null
        fillPaint.color = Color.WHITE
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, vertex * 2,
            surfaceMeshVertices, 0, null, 0, surfaceMeshColors, 0,
            null, 0, 0, fillPaint)
    }

    private fun putSurfaceVertex(index: Int, x: Double, y: Double, color: Int): Int {
        surfaceMeshVertices[index * 2] = x.toFloat()
        surfaceMeshVertices[index * 2 + 1] = y.toFloat()
        surfaceMeshColors[index] = color
        return index + 1
    }

    private fun continuousVertexColor(row: Int, j: Int, startLayer: Int,
                                      viewElev: Double, thetaRad: Double,
                                      palettes: Array<LayerColors>,
                                      colorStops: Array<Array<IntArray>>,
                                      geometry: Array<LayerGradientGeometry>,
                                      environment: EnvironmentColors): Int {
        val x = surfaceXsPx[row][j]
        val y = surfaceYsPx[row][j]
        val globalY = height * 0.5 - sin(thetaRad) * x + cos(thetaRad) * y
        val envQ = (globalY / max(height.toDouble(), 1.0)).coerceIn(0.0, 1.0)
        val envA: IntArray; val envB: IntArray; val envT: Double
        if (envQ <= 0.42) {
            envA = environment.top; envB = environment.horizon; envT = envQ / 0.42
        } else {
            envA = environment.horizon; envB = environment.bottom; envT = (envQ - 0.42) / 0.58
        }
        var red = (envA[0] + (envB[0] - envA[0]) * envT).roundToInt()
        var green = (envA[1] + (envB[1] - envA[1]) * envT).roundToInt()
        var blue = (envA[2] + (envB[2] - envA[2]) * envT).roundToInt()
        for (layer in FableSolSpec.N_LAYERS - 1 downTo startLayer) {
            val g = geometry[layer]
            val q = (((x - g.ox) * g.dx + (y - g.oy) * g.dy) / g.denominator)
                .coerceIn(0.0, 1.0)
            val stops = colorStops[layer]
            val a: IntArray; val b: IntArray; val t: Double
            when {
                q <= 0.24 -> { a = stops[0]; b = stops[1]; t = q / 0.24 }
                q <= 0.60 -> { a = stops[1]; b = stops[2]; t = (q - 0.24) / 0.36 }
                else -> { a = stops[2]; b = stops[3]; t = (q - 0.60) / 0.40 }
            }
            val alpha = palettes[layer].alpha255 / 255.0
            val layerRed = a[0] + (b[0] - a[0]) * t
            val layerGreen = a[1] + (b[1] - a[1]) * t
            val layerBlue = a[2] + (b[2] - a[2]) * t
            red = (layerRed * alpha + red * (1.0 - alpha)).roundToInt()
            green = (layerGreen * alpha + green * (1.0 - alpha)).roundToInt()
            blue = (layerBlue * alpha + blue * (1.0 - alpha)).roundToInt()
        }
        val base = Color.rgb(red.coerceIn(0, 255), green.coerceIn(0, 255), blue.coerceIn(0, 255))
        return applyLongitudinalLight(base, surfaceSlopeX[row][j],
            surfaceSlopeZ[row][j], viewElev, environment.horizonColor,
            row.toDouble() / (FableSolContinuousSurface.Z_ROWS - 1))
    }

    /** 只加入 Z 坡度相对原 A 受光的物理差；Z=0 时逐通道恒等。 */
    private fun applyLongitudinalLight(base: Int, sx: Double, sz: Double,
                                       viewElevDeg: Double, sky: Int, depth01: Double): Int {
        if (abs(sz) <= 1e-12) return base
        val inv = 1.0 / max(sqrt(1.0 + sx * sx + sz * sz), 1e-9)
        val nx = -sx * inv; val ny = inv; val nz = -sz * inv
        val invRef = 1.0 / max(sqrt(1.0 + sx * sx), 1e-9)
        val nxRef = -sx * invRef; val nyRef = invRef
        val elev = Math.toRadians(viewElevDeg)
        val vx = 0.0; val vy = sin(elev); val vz = -cos(elev)
        val lightElev = Math.toRadians(50.0)
        val az = Math.toRadians(params.get("light_azimuth_deg"))
        val lx = sin(az) * cos(lightElev)
        val ly = sin(lightElev)
        val lz = -cos(az) * cos(lightElev)
        val fullNdv = (nx * vx + ny * vy + nz * vz).coerceIn(0.001, 1.0)
        val fullNdl = (nx * lx + ny * ly + nz * lz).coerceIn(0.0, 1.0)
        val fullFres = WATER_F0 + (1.0 - WATER_F0) * (1.0 - fullNdv).pow(5)
        val refNdv = (nxRef * vx + nyRef * vy).coerceIn(0.001, 1.0)
        val refNdl = (nxRef * lx + nyRef * ly).coerceIn(0.0, 1.0)
        val refFres = WATER_F0 + (1.0 - WATER_F0) * (1.0 - refNdv).pow(5)
        fun litChannel(baseChannel: Int, skyChannel: Int): Int {
            val b = srgbToLinear(baseChannel)
            val sk = srgbToLinear(skyChannel)
            val f = fullNdl * (1.0 - fullFres) * b + fullFres * sk
            val r = refNdl * (1.0 - refFres) * b + refFres * sk
            return linearToSrgb(b + f - r)
        }
        val candidate = Color.rgb(
            litChannel(Color.red(base), Color.red(sky)),
            litChannel(Color.green(base), Color.green(sky)),
            litChannel(Color.blue(base), Color.blue(sky))
        )
        return FableSolLightColorPolicy.resolveLongitudinal(base, candidate, depth01)
    }

    private fun srgbToLinear(v: Int): Double {
        return SRGB_TO_LINEAR[v.coerceIn(0, 255)]
    }

    private fun linearToSrgb(value: Double): Int {
        val index = (value.coerceIn(0.0, 1.0) * LINEAR_TO_SRGB_LAST).roundToInt()
        return LINEAR_TO_SRGB[index]
    }

    private fun drawContinuousOptics(canvas: Canvas, i: Int, row: Int, cnt: Int,
                                     lc: LayerColors) {
        val scratchMark = doubleScratchIndex
        for (j in 0 until cnt) {
            xsPx[j] = surfaceXsPx[row][j]
            ysPx[j] = surfaceYsPx[row][j]
        }
        val depth01 = i.toDouble() / (FableSolSpec.N_LAYERS - 1)
        if (i <= 6 && params.get("surface_strip_gain") > 1e-3) {
            drawSurfaceStrip(canvas, i, cnt, lc.start, lc.end, lc.alpha255, depth01)
        }
        if (params.get("crest_on") >= 0.5) {
            drawHighlights(canvas, i, cnt, lc.start, lc.end, lc.alpha255, 0, depth01,
                surfaceSourceIndex, surfaceSourceFraction)
        }
        if (i >= 7) drawEdgeFeather(canvas, cnt, depth01)
        doubleScratchIndex = scratchMark
    }

    /** C1（AGSL）：渐变包一层三角抖动，消除 OLED 平缓渐变的色阶条纹；不支持时原样返回。 */
    private fun dithered(src: Shader): Shader {
        val d = FableSolAgsl.dither ?: return src
        d.setInputShader("src", src)
        return d
    }

    private fun drawLayer(canvas: Canvas, i: Int, cnt: Int, fillBottom: Double, i0: Int) {
        val scratchMark = doubleScratchIndex
        val depth01 = i.toDouble() / (FableSolSpec.N_LAYERS - 1)
        val breath = params.get("color_breath") *
                (0.30 * (sim.colorBright01 - 0.45) + 0.18 * (sim.colorEnergy01 - 0.5))
        val f = FableSolLayerColorPolicy.lightenAmount(
            depth01,
            params.get("lighten_far"),
            sim.moodBright,
            breath
        )
        val alpha = params.lget("alpha", i)
        val a255 = (alpha * 255).roundToInt().coerceIn(0, 255)
        val c1 = FableSolColor.mixOklab(c1Base, WHITE, f)
        val c2 = FableSolColor.mixOklab(c2Base, WHITE, f)

        fillPath.reset()
        buildSmooth(fillPath, xsPx, ysPx, cnt, true)
        fillPath.lineTo(xsPx[cnt - 1].toFloat(), fillBottom.toFloat())
        fillPath.lineTo(xsPx[0].toFloat(), fillBottom.toFloat())
        fillPath.close()

        // C3（AGSL）：层填充在渐变之上做逐像素深度吸收 + 焦散；不可用则纯渐变。
        val gradient = dithered(layerShader(c1, c2, a255, cnt, fillBottom))
        val fillFx = FableSolAgsl.layerFill
        val absorbGain = params.get("absorption_gain")
        if (fillFx != null && absorbGain > 1e-3) {
            var yMin = Double.MAX_VALUE; var yMax = -Double.MAX_VALUE
            for (j in 0 until cnt) {
                if (ysPx[j] < yMin) yMin = ysPx[j]
                if (ysPx[j] > yMax) yMax = ysPx[j]
            }
            val yRange = max(yMax - yMin, 1.0)
            fillFx.setInputShader("src", gradient)
            fillFx.setInputBuffer("data", contourData(ysPx, null, null, cnt, yMin, yRange))
            fillFx.setFloatUniform("x0", xsPx[0].toFloat())
            fillFx.setFloatUniform("dxStep", (xsPx[1] - xsPx[0]).toFloat())
            fillFx.setFloatUniform("cntF", cnt.toFloat())
            fillFx.setFloatUniform("yMin", yMin.toFloat())
            fillFx.setFloatUniform("yRange", yRange.toFloat())
            fillFx.setFloatUniform("densityPx", density.toFloat())
            fillFx.setFloatUniform("absorb", absorbGain.toFloat())
            fillPaint.shader = fillFx
        } else {
            fillPaint.shader = gradient
        }
        canvas.drawPath(fillPath, fillPaint)
        fillPaint.shader = null

        // 浪顶表面带：38°俯角下可见的水面平面（立体感主承重墙）。接触阴影已按
        // 用户裁决移除（2026-07-11），层间厚度感由波背自阴影承担。
        if (i <= 6 && params.get("surface_strip_gain") > 1e-3) {
            drawSurfaceStrip(canvas, i, cnt, c1, c2, a255, depth01)
        }
        if (params.get("crest_on") >= 0.5) drawHighlights(canvas, i, cnt, c1, c2, a255, i0, depth01)
        // 顶边羽化（透纳）：平静时最远两层轮廓向环境地平溶解。
        if (i >= 7) drawEdgeFeather(canvas, cnt, depth01)
        doubleScratchIndex = scratchMark
    }

    private fun layerShader(c1: IntArray, c2: IntArray, a255: Int, cnt: Int, fillBottom: Double): LinearGradient {
        var x0 = xsPx[0]; var x1 = xsPx[0]; var yTop = ysPx[0]
        for (j in 0 until cnt) {
            if (xsPx[j] < x0) x0 = xsPx[j]
            if (xsPx[j] > x1) x1 = xsPx[j]
            if (ysPx[j] < yTop) yTop = ysPx[j]
        }
        val yBottom = fillBottom
        val midX = (x0 + x1) / 2.0; val midY = (yTop + yBottom) / 2.0
        // 纯色记事两端同色；渐变记事沿用原始两端色与 8 向 orientation。
        val gx0: Double; val gy0: Double; val gx1: Double; val gy1: Double
        when (if (bgIsGradient) bgOrientation else ThingBackground.Orientation.T_B) {
            ThingBackground.Orientation.L_R -> { gx0 = x0; gy0 = midY; gx1 = x1; gy1 = midY }
            ThingBackground.Orientation.R_L -> { gx0 = x1; gy0 = midY; gx1 = x0; gy1 = midY }
            ThingBackground.Orientation.T_B -> { gx0 = midX; gy0 = yTop; gx1 = midX; gy1 = yBottom }
            ThingBackground.Orientation.B_T -> { gx0 = midX; gy0 = yBottom; gx1 = midX; gy1 = yTop }
            ThingBackground.Orientation.LT_RB -> { gx0 = x0; gy0 = yTop; gx1 = x1; gy1 = yBottom }
            ThingBackground.Orientation.RB_LT -> { gx0 = x1; gy0 = yBottom; gx1 = x0; gy1 = yTop }
            ThingBackground.Orientation.RT_LB -> { gx0 = x1; gy0 = yTop; gx1 = x0; gy1 = yBottom }
            ThingBackground.Orientation.LB_RT -> { gx0 = x0; gy0 = yBottom; gx1 = x1; gy1 = yTop }
        }
        val colors = intArrayOf(
            FableSolColor.toColor(c1, a255),
            FableSolColor.toColor(FableSolColor.mixOklab(c1, c2, 0.21), a255),
            FableSolColor.toColor(FableSolColor.mixOklab(c1, c2, 0.56), a255),
            FableSolColor.toColor(c2, a255)
        )
        return LinearGradient(gx0.toFloat(), gy0.toFloat(), gx1.toFloat(), gy1.toFloat(),
            colors, POS4, Shader.TileMode.CLAMP)
    }

    /**
     * 物理近似高光（对应 canvas.py 的 _draw_highlights）：镜面闪光（坡度匹配光源半角）+ 菲涅尔亮边
     * + 波峰透光 + 波冠轻纱。三项都是表面坡度/曲率的局部函数，无需光线追踪。
     */
    private fun drawHighlights(canvas: Canvas, i: Int, cnt: Int, c1: IntArray, c2: IntArray, a255: Int,
                              i0: Int, depth01: Double,
                              sourceIndex: IntArray? = null,
                              sourceFraction: DoubleArray? = null) {
        val ls = sim.layers[i]
        val ys = scratchArray(cnt) { ysPx[it] }
        val dxPx = xsPx[1] - xsPx[0]
        val ker = KER3
        val gradY = scratchZero(cnt)
        FableSolMath.gradientInto(ys, cnt, dxPx, gradY)
        val slopeRaw = scratchArray(cnt) { -gradY[it] }
        val slope = scratchZero(cnt)
        FableSolMath.convolveSameInto(slopeRaw, cnt, ker, slope)
        val gradGrad = scratchZero(cnt)
        FableSolMath.gradientInto(gradY, cnt, dxPx, gradGrad)
        val curvRaw = scratchArray(cnt) { -gradGrad[it] * density }
        val curv = scratchZero(cnt)
        FableSolMath.convolveSameInto(curvRaw, cnt, ker, curv)
        val cap = ls.capillary01 * params.get("capillary_glint_gain")
        val rough = ls.roughness01
        val uDp = scratchArray(cnt) { xsPx[it] / density }
        val micro = scratchZero(cnt)
        val microCurv = scratchZero(cnt)
        if (FableSolMaterialPolicy.glintCapacity(i) > 0) {
            ls.optical.sampleInto(uDp, cnt, cap, rough, micro, microCurv)
        }
        val opticalSlope = scratchArray(cnt) { slope[it] + micro[it] }
        val s0 = tan(Math.toRadians(params.get("light_azimuth_deg")) / 2.0)
        val sigma = GLINT_SIGMA * (1.0 + 0.42 * rough)
        val sinElev = sin(Math.toRadians(VIEW_ELEVATION_DEG))
        val flatFres = WATER_F0 + (1.0 - WATER_F0) * (1.0 - sinElev).pow(5)
        val glintDepth = max(0.0, 1.0 - depth01 / 0.42)
        val glintStrength = params.get("crest_glint_strength")
        val skyStrength = params.get("sky_reflection_strength")
        val glowStrength = params.get("crest_glow_strength")
        val bodyStrength = params.get("body_light_strength")
        val fres = scratchZero(cnt)
        val edge = scratchZero(cnt)
        for (j in 0 until cnt) {
            val os = opticalSlope[j]
            val glint = exp(-((os - s0) / sigma).pow(2))
            val facet = (abs(microCurv[j]) / (0.004 + 0.006 * rough)).coerceIn(0.0, 1.0).pow(0.58)
            val cosTheta = (sinElev / sqrt(1.0 + os * os)).coerceIn(0.0, 1.0)
            val fr = WATER_F0 + (1.0 - WATER_F0) * (1.0 - cosTheta).pow(5)
            fres[j] = fr
            val fresDetail = ((fr - flatFres) * 4.0).coerceIn(0.0, 1.0)
            val edgeRaw = (glint * facet * glintStrength * glintDepth + fresDetail * skyStrength * 0.24)
                .coerceIn(0.0, 1.0)
            edge[j] = ((edgeRaw - 0.08) / 0.92).coerceIn(0.0, 1.0)
        }
        val edgeS = smoothSignal(edge, cnt, 3)
        for (j in 0 until cnt) if (edgeS[j] < 0.015) edgeS[j] = 0.0
        val crestLight0 = scratchArray(cnt) { (curv[it] / (-GLOW_KAPPA)).coerceIn(0.0, 1.0) * glowStrength }
        val crestLight = scratchZero(cnt)
        FableSolMath.convolveSameInto(crestLight0, cnt, FULL5, crestLight)
        val volume = scratchArray(cnt) { ((0.16 + 0.84 * crestLight[it]) * (1.0 - fres[it]) * bodyStrength).coerceIn(0.0, 1.0) }

        // 轨道微摆：线性深水波的物质水平位移 ξ=slope/k——光斑骑在"水"上而非钉在
        // "波形"上，随浪经过绕基点回摆，与竖直起伏合成轨道运动。
        val sway = scratchArray(cnt) {
            (slope[it] * params.get("orbital_sway_dp") * density)
                .coerceIn(-8.0 * density, 8.0 * density)
        }
        val hc = FableSolOpticalColorPolicy.highlight(
            FableSolColor.mix(c1, c2, 0.3), params.get("crest_lighten")
        )
        val bodyColor = FableSolColor.mixOklab(c1, hc, 0.46)
        val a01 = a255 / 255.0
        // 空气透视压缩：远层所有装饰统一向该层基调收缩（构图的音量控制器）。
        val kAir = 1.0 - params.get("aerial_contrast") * depth01

        if (bodyStrength > 1e-3) {
            val dPx = params.get("crest_glow_depth_dp") * density
            val topArr = scratchArray(cnt) { ys[it] + 0.35 * density }
            val thickness = scratchArray(cnt) { dPx * (0.34 + 0.66 * volume[it]) }
            drawOneSidedBand(canvas, cnt, topArr, thickness, bodyColor,
                (72 * a01 * kAir * bodyStrength).toInt(), true)
        }
        // 薄峰透光内辉：高出均线的圆峰水最薄，光穿透后以本层身份色从内部亮起
        // ——反射族之外唯一的透射族证据，水因此读作有厚度的介质。
        val tg = params.get("thin_glow_gain")
        if (tg > 1e-3 && i <= 4) {
            val glow = smoothSignal(thinGlowField(ys, curv, cnt), cnt, 5)
            var gm = 0.0; for (v in glow) if (v > gm) gm = v
            if (gm > 0.03) {
                val glowC = FableSolOpticalColorPolicy.thinTransmission(hc)
                val topArr = scratchArray(cnt) { ys[it] + 0.4 * density }
                val th = scratchArray(cnt) {
                    FableSolMaterialPolicy.thinGlowThicknessDp(glow[it]) * density
                }
                drawOneSidedBand(canvas, cnt, topArr, th, glowC,
                    (140 * a01 * tg * kAir).toInt(), true)
            }
        }
        // 波背自阴影：亮脊紧贴背光暗窝（层内明暗转折）。阴影色只将本层色沿明度轴压暗，
        // 不额外偏色、不发灰，只随浪出现，接替已移除的灰色接触阴影。
        val bs = params.get("back_shade_gain")
        if (bs > 1e-3 && i <= 5) {
            val litSign = if (s0 >= 0) 1.0 else -1.0
            val shade = smoothSignal(backShadeField(slope, curv, litSign, cnt), cnt, 4)
            var sm = 0.0; for (v in shade) if (v > sm) sm = v
            if (sm > 0.04) {
                val shadeC = FableSolShadowColorPolicy.backShade(
                    c1, params.get("hue_temp_deg"), depth01)
                val topArr = scratchArray(cnt) { ys[it] + 0.3 * density }
                val th = scratchArray(cnt) { (2.0 + 13.0 * shade[it]) * density * sqrt(shade[it]) }
                drawOneSidedBand(canvas, cnt, topArr, th, shadeC,
                    (88 * a01 * bs * kAir).toInt(), true)
            }
        }
        // 镜面高光：持久实体闪点（原地生灭的明灭，不是行驶的车厢）。
        if (glintStrength > 1e-3) {
            var mx = 0.0; for (v in edgeS) if (v > mx) mx = v
            if (mx > 1e-3) drawGlints(canvas, i, cnt, ys, edgeS, sway, hc, a01, kAir)
        }
        val veilStrength = params.get("crest_veil_strength")
        if (veilStrength > 1e-3 && i <= 2) {
            val veilRaw = scratchArray(cnt) {
                if (sourceIndex == null || sourceFraction == null) {
                    ls.crestVeil[i0 + it]
                } else {
                    val index = sourceIndex[it]
                    val fraction = sourceFraction[it]
                    ls.crestVeil[index] * (1.0 - fraction) +
                        ls.crestVeil[index + 1] * fraction
                }
            }
            val veil = smoothSignal(veilRaw, cnt, 4)
            var mx = 0.0
            for (j in 0 until cnt) { veil[j] *= veilStrength; if (veil[j] > mx) mx = veil[j] }
            if (mx > 1e-3) {
                val veilColor = FableSolOpticalColorPolicy.crestVeil(hc)
                val ysV = scratchArray(cnt) { ys[it] - 0.20 * density }
                val amt = scratchArray(cnt) { veil[it] * a01 }
                drawVariableBand(canvas, cnt, ysV, amt, veilColor, 3.2 * density, (96 * a01 * veilStrength).toInt())
            }
        }
    }

    /** 连续变宽的镜面光斑（对应 _draw_variable_band）：用填充几何承载强度。 */
    private fun drawVariableBand(canvas: Canvas, cnt: Int, ys: DoubleArray, amountIn: DoubleArray,
                                rgb: IntArray, maxWidthPx: Double, alpha: Int) {
        val amount = scratchArray(cnt) { amountIn[it].coerceIn(0.0, 1.0).pow(0.72) }
        if (cnt >= 2) { amount[0] = 0.0; amount[1] = 0.0; amount[cnt - 1] = 0.0; amount[cnt - 2] = 0.0 }
        val dxg = scratchZero(cnt)
        FableSolMath.gradientInto(xsPx, cnt, 1.0, dxg)
        val dyg = scratchZero(cnt)
        FableSolMath.gradientInto(ys, cnt, 1.0, dyg)
        val upperX = scratchZero(cnt); val upperY = scratchZero(cnt)
        val lowerX = scratchZero(cnt); val lowerY = scratchZero(cnt)
        for (j in 0 until cnt) {
            val inv = 1.0 / max(hypot(dxg[j], dyg[j]), 1e-6)
            val nx = -dyg[j] * inv; val ny = dxg[j] * inv
            val half = 0.5 * maxWidthPx * amount[j]
            upperX[j] = xsPx[j] + nx * half; upperY[j] = ys[j] + ny * half
            lowerX[j] = xsPx[j] - nx * half; lowerY[j] = ys[j] - ny * half
        }
        bandPath.reset()
        buildSmooth(bandPath, upperX, upperY, cnt, true)
        buildSmooth(bandPath, reverse(lowerX, cnt), reverse(lowerY, cnt), cnt, false)
        bandPath.close()
        bandPaint.color = FableSolColor.toColor(rgb, alpha.coerceIn(0, 255))
        canvas.drawPath(bandPath, bandPaint)
    }

    /**
     * 表面到水下的连续透光体（对应 _draw_one_sided_band 定稿）。fade 剖面不再用
     * 全局 y 百分位锚定的竖直渐变——浪高时带的几何下缘会以 ~0.4·alpha 截断成割裂
     * 亮边（Python 侧用户实测）。改为跟随带几何的子带剖面：alpha 只依赖带内相对深
     * 度。低透明度带（<40）硬下缘每通道 ≤~6 不可辨，单次填充即视觉等价；另先裁剪
     * 到非零厚度跨度，局部带（珍珠/透光/猫爪）光栅面积数倍缩小。
     */
    private fun drawOneSidedBand(canvas: Canvas, cnt: Int, top: DoubleArray, thicknessIn: DoubleArray,
                                rgb: IntArray, alphaIn: Int, fade: Boolean) {
        val alpha = alphaIn.coerceIn(0, 255)
        if (alpha <= 0) return
        val thickness = scratchArray(cnt) { max(thicknessIn[it], 0.0) }
        if (cnt >= 2) { thickness[0] = 0.0; thickness[1] = 0.0; thickness[cnt - 1] = 0.0; thickness[cnt - 2] = 0.0 }
        if (fade) {
            var lo = -1; var hi = -1
            for (j in 0 until cnt) if (thickness[j] > 0.05) { if (lo < 0) lo = j; hi = j }
            if (lo < 0) return
            lo = max(lo - 2, 0); hi = min(hi + 3, cnt)
            if (hi - lo < 4) return
            // C2（AGSL）：逐像素连续钟形剖面——数学精确、无子带阶差，路径光栅
            // 移到 GPU；shader 不可用（<API33 或编译失败）则走下方 CPU 子带近似。
            val bandShader = FableSolAgsl.band
            if (bandShader != null) {
                var yMin = Double.MAX_VALUE; var yMax = -Double.MAX_VALUE
                for (j in lo until hi) {
                    if (top[j] < yMin) yMin = top[j]
                    val b = top[j] + thickness[j]
                    if (b > yMax) yMax = b
                }
                val yRange = max(yMax - yMin, 1.0)
                bandShader.setInputBuffer("data",
                    contourData(top, thickness, null, cnt, yMin, yRange))
                bandShader.setFloatUniform("x0", xsPx[0].toFloat())
                bandShader.setFloatUniform("dxStep", (xsPx[1] - xsPx[0]).toFloat())
                bandShader.setFloatUniform("cntF", cnt.toFloat())
                bandShader.setFloatUniform("yMin", yMin.toFloat())
                bandShader.setFloatUniform("yRange", yRange.toFloat())
                bandShader.setFloatUniform("tint", rgb[0] / 255f, rgb[1] / 255f,
                    rgb[2] / 255f, alpha / 255f)
                fillPaint.shader = bandShader
                canvas.drawRect(xsPx[lo].toFloat(), yMin.toFloat(),
                    xsPx[hi - 1].toFloat(), yMax.toFloat(), fillPaint)
                fillPaint.shader = null
                return
            }
            if (alpha < 40) {
                bandPaint.color = FableSolColor.toColor(rgb, (alpha * 0.55).toInt())
                bandPathLite(lo, hi, top, thickness, 0.06, 0.88)
                canvas.drawPath(bandPath, bandPaint)
            } else {
                // 剖面（叠加）：上沿 0.14 软入 → 中段 0.72 聚光 → 下缘 0.14 收出。
                for (s in 0 until 3) {
                    bandPaint.color = FableSolColor.toColor(rgb, (alpha * SUB_AA[s]).toInt())
                    bandPathLite(lo, hi, top, thickness, SUB_OFF[s], SUB_FRAC[s])
                    canvas.drawPath(bandPath, bandPaint)
                }
            }
            return
        }
        val bottom = scratchArray(cnt) { top[it] + thickness[it] }
        bandPath.reset()
        buildSmooth(bandPath, sliceX(cnt), top, cnt, true)
        buildSmooth(bandPath, reverse(sliceX(cnt), cnt), reverse(bottom, cnt), cnt, false)
        bandPath.close()
        bandPaint.color = FableSolColor.toColor(rgb, alpha)
        canvas.drawPath(bandPath, bandPaint)
    }

    /** fade 子带的轻量路径：2 倍下采样折线（子带是内部 alpha 结构，视觉等价于样条）。 */
    private fun bandPathLite(lo: Int, hi: Int, top: DoubleArray, thickness: DoubleArray,
                             off: Double, frac: Double) {
        bandPath.reset()
        var first = true
        var j = lo
        while (j < hi) {
            val y = (top[j] + thickness[j] * off).toFloat()
            if (first) { bandPath.moveTo(xsPx[j].toFloat(), y); first = false }
            else bandPath.lineTo(xsPx[j].toFloat(), y)
            if (j == hi - 1) break
            j = min(j + 2, hi - 1)
        }
        j = hi - 1
        while (j >= lo) {
            val st = top[j] + thickness[j] * off
            bandPath.lineTo(xsPx[j].toFloat(), (st + thickness[j] * frac).toFloat())
            if (j == lo) break
            j = max(j - 2, lo)
        }
        bandPath.close()
    }

    // ================================================================== A5/B 立体感手法
    /** 浪顶表面带（_draw_surface_strip）：迎光坡宽、背坡窄，随浪起伏开合；
     *  颜色=地平天空倒影混层高光色——轮廓线成为"平面的近边"，画面获得体积。 */
    private fun drawSurfaceStrip(canvas: Canvas, i: Int, cnt: Int, c1: IntArray, c2: IntArray,
                                 a255: Int, depth01: Double) {
        val dxPx = xsPx[1] - xsPx[0]
        val gradY = scratchZero(cnt)
        FableSolMath.gradientInto(ysPx, cnt, dxPx, gradY)
        val slopeRaw = scratchArray(cnt) { -gradY[it] }
        val slope = scratchZero(cnt)
        FableSolMath.convolveSameInto(slopeRaw, cnt, KER3, slope)
        val gradGrad = scratchZero(cnt)
        FableSolMath.gradientInto(gradY, cnt, dxPx, gradGrad)
        val curvRaw = scratchArray(cnt) { -gradGrad[it] * density }
        val curv = scratchZero(cnt)
        FableSolMath.convolveSameInto(curvRaw, cnt, KER3, curv)
        val facing = scratchArray(cnt) {
            val q = ((slope[it] + 0.05) / 0.50).coerceIn(0.0, 1.0)
            q * q * (3.0 - 2.0 * q)
        }
        val crest = scratchArray(cnt) { (curv[it] / (-GLOW_KAPPA)).coerceIn(0.0, 1.0) }
        val widthInput = scratchArray(cnt) {
            FableSolMaterialPolicy.surfaceBandWidthDp(facing[it], crest[it], depth01)
        }
        val wDp = smoothSignal(widthInput, cnt, 4)
        val tint = params.get("environment_tint")
        val horizon = FableSolColor.mixOklab(envBase,
            FableSolColor.mixOklab(c1Base, WHITE, 0.78), tint)
        val hcS = FableSolOpticalColorPolicy.highlight(
            FableSolColor.mix(c1, c2, 0.3), params.get("crest_lighten")
        )
        val strip = FableSolColor.mixOklab(horizon, hcS, 0.42)
        val a01 = a255 / 255.0
        val kAir = 1.0 - params.get("aerial_contrast") * depth01
        val breath = 1.0 + 0.10 * params.get("pink_mod") * (2.0 * pink01(sim.t, 9.7) - 1.0)
        val alpha = ((92.0 - 34.0 * depth01) * a01 * kAir * breath
                * params.get("surface_strip_gain")).toInt()
        val topArr = scratchArray(cnt) { ysPx[it] + 0.2 * density }
        val th = scratchArray(cnt) { wDp[it] * density }
        drawOneSidedBand(canvas, cnt, topArr, th, strip, alpha, true)
        // 流光条纹：材质相对波形滚动的证据——顺层流漂移、随轨道回摆，只在浪顶平面可见。
        if (i <= 2 && params.get("flow_streak_gain") > 1e-3) {
            val sway = scratchArray(cnt) {
                (slope[it] * params.get("orbital_sway_dp") * density)
                    .coerceIn(-8.0 * density, 8.0 * density)
            }
            drawFlowStreaks(canvas, i, cnt, facing, sway, strip, a01)
        }
    }

    /** 顶边羽化（_draw_edge_feather）：平静时远层轮廓以环境地平色的软带溶解。 */
    private fun drawEdgeFeather(canvas: Canvas, cnt: Int, depth01: Double) {
        val f = sim.calm01 * ((depth01 - 0.55) / 0.45).coerceIn(0.0, 1.0)
        if (f < 0.06) return
        val horizon = FableSolColor.mixOklab(envBase,
            FableSolColor.mixOklab(c1Base, WHITE, 0.78), params.get("environment_tint"))
        val th = (3.0 + 8.0 * f) * density
        val topArr = scratchArray(cnt) { ysPx[it] - th * 0.55 }
        val thickness = scratchArray(cnt) { th }
        drawOneSidedBand(canvas, cnt, topArr, thickness, horizon, (105 * f).toInt(), true)
    }

    /** 场的局部极大 → 锚点 (u, 强度, 半宽)（_field_peaks）。半宽随浪形伸缩。 */
    private fun fieldPeaks(field: DoubleArray, cnt: Int, floor: Double, minSepPx: Double,
                           kMax: Int): ArrayList<DoubleArray> {
        anchorsScratch.clear()
        // 简化：按强度降序取局部极大（cnt≈216，冒泡式选前 kMax 足够快）
        val idx = ArrayList<Int>(8)
        for (j in 2 until cnt - 2) {
            if (field[j] >= field[j - 1] && field[j] > field[j + 1] && field[j] > floor) idx.add(j)
        }
        idx.sortByDescending { field[it] }
        for (j in idx) {
            val u = xsPx[j]
            var ok = true
            for (a in anchorsScratch) if (abs(u - a[0]) < minSepPx) { ok = false; break }
            if (!ok) continue
            val half = 0.5 * field[j]
            var l = j; while (l > 0 && field[l] > half) l--
            var r = j; while (r < cnt - 1 && field[r] > half) r++
            anchorsScratch.add(doubleArrayOf(u, field[j], max(xsPx[r] - xsPx[l], 6.0)))
            if (anchorsScratch.size >= kMax) break
        }
        return anchorsScratch
    }

    /** 通用实体跟踪（_track_entities）：锚点匹配→位置平滑跟随，强度攻击/释放。 */
    private fun trackEntities(tracks: ArrayList<Track>, anchors: ArrayList<DoubleArray>,
                              dt: Double, matchPx: Double, attackS: Double, releaseS: Double,
                              posTau: Double, cap: Int) {
        val kPos = 1.0 - exp(-dt / max(posTau, 1e-3))
        val kAtt = 1.0 - exp(-dt / max(attackS, 1e-3))
        val kRel = 1.0 - exp(-dt / max(releaseS, 1e-3))
        val used = BooleanArray(anchors.size)
        for (e in tracks) {
            var best = -1; var bestD = matchPx
            for (ai in anchors.indices) {
                if (used[ai]) continue
                val d = abs(anchors[ai][0] - e.u)
                if (d < bestD) { best = ai; bestD = d }
            }
            if (best >= 0) {
                used[best] = true
                val a = anchors[best]
                e.u += (a[0] - e.u) * kPos
                val k = if (a[1] > e.inten) kAtt else kRel
                e.inten += (a[1] - e.inten) * k
                e.size += (a[2] - e.size) * kAtt
            } else e.inten -= e.inten * kRel
        }
        for (ai in anchors.indices) {
            if (!used[ai] &&
                anchors[ai][1] > FableSolMaterialPolicy.GLINT_FIELD_FLOOR &&
                tracks.size < cap
            ) {
                val a = anchors[ai]
                val seed = (sin(a[0] * 12.9898) * 43758.5453).let { it - Math.floor(it) }
                tracks.add(Track(a[0], a[1] * 0.12, a[2], seed))
            }
        }
        tracks.removeAll { it.inten <= 0.015 }
        tracks.sortByDescending { it.inten }
        while (tracks.size > cap) tracks.removeAt(tracks.size - 1)
    }

    /** 镜面闪点（_draw_glints）：少量持久实体贴着受光浪面滑行，慢呼吸明暗。 */
    private fun drawGlints(canvas: Canvas, i: Int, cnt: Int, ys: DoubleArray, prob: DoubleArray,
                           sway: DoubleArray, hc: IntArray, a01: Double, kAir: Double) {
        val dt = max(sim.t - trackT, 0.0)
        val pink = 1.0 + 0.12 * params.get("pink_mod") * (2.0 * pink01(sim.t, 3.1) - 1.0)
        val spark = (0.35 + 0.65 * sim.sparkle01) * pink
        val fieldInput = scratchArray(cnt) {
            (prob[it] * 1.5).coerceIn(0.0, 1.0) * spark * kAir
        }
        val field = smoothSignal(fieldInput, cnt, 5)
        val cap = FableSolMaterialPolicy.glintCapacity(i)
        val anchors = fieldPeaks(
            field,
            cnt,
            FableSolMaterialPolicy.GLINT_FIELD_FLOOR,
            FableSolMaterialPolicy.GLINT_MIN_SEPARATION_DP * density,
            cap
        )
        trackEntities(glintTracks[i], anchors, dt, 34.0 * density, 0.30, 0.80, 0.10, cap)
        val tracks = glintTracks[i]
        if (tracks.isEmpty()) return
        val core = FableSolColor.mixOklab(hc, WHITE, 0.35)
        val dxPx = xsPx[1] - xsPx[0]
        for (e in tracks) {
            val breath = 1.0 + 0.12 * sin(2.0 * Math.PI * sim.t / (2.6 + 1.4 * e.seed) + e.seed * 6.28)
            val inten = (e.inten * breath).coerceIn(0.0, 1.0)
            if (inten < 0.04) continue
            val cx = e.u + interpAt(sway, cnt, e.u)
            val j = ((cx - xsPx[0]) / dxPx).toInt().coerceIn(1, cnt - 2)
            val cy = interpAt(ys, cnt, cx)
            val ang = Math.toDegrees(atan2(ys[j + 1] - ys[j - 1], 2.0 * dxPx))
            val length = (e.size * 0.62).coerceIn(6.0 * density, 34.0 * density) * (0.8 + 0.4 * inten)
            val thick = (1.1 + 0.8 * e.seed) * density
            val aPk = (235 * a01 * inten.pow(0.8)).toInt().coerceIn(0, 255)
            drawGlowEllipse(canvas, cx, cy, ang, length, thick, core, hc, aPk, 0.5)
        }
    }

    /** 流光条纹（_draw_flow_streaks + _step_streaks）：浪顶平面上的持久发光条。 */
    private fun drawFlowStreaks(canvas: Canvas, i: Int, cnt: Int, facing: DoubleArray,
                                sway: DoubleArray, baseC: IntArray, a01: Double) {
        val dt = max(sim.t - trackT, 0.0)
        val flowPxS = sim.layers[i].flowDps * density
        val cap = if (i == 0) 3 else 2
        val tracks = streakTracks[i]
        // 推进：顺流漂移、寿命衰老、越界剔除；缺员按确定性节奏补生。
        for (e in tracks) { e.age += dt; e.u += flowPxS * dt }
        val margin = 60.0 * density
        tracks.removeAll { it.age >= it.life || it.u <= xsPx[0] - margin || it.u >= xsPx[cnt - 1] + margin }
        if (tracks.size < cap && sim.t >= streakNextT[i]) {
            val s = hash01(streakSeq[i] * 1.7 + 0.37, i * 2.9)
            val s2 = hash01(streakSeq[i] * 3.1 + 1.11, i * 5.3)
            tracks.add(Streak(
                xsPx[0] + (0.08 + 0.84 * s) * (xsPx[cnt - 1] - xsPx[0]), 0.0,
                5.0 + 4.0 * s2,
                (26.0 + 38.0 * hash01(streakSeq[i] + 9.1, i.toDouble())) * density, s2))
            streakSeq[i]++
            streakNextT[i] = sim.t + 0.8 + 1.6 * s
        }
        if (tracks.isEmpty()) return
        val gain = params.get("flow_streak_gain")
        val streakC = FableSolColor.mixOklab(baseC, WHITE, 0.45)
        val dxPx = xsPx[1] - xsPx[0]
        val pink = 1.0 + 0.15 * params.get("pink_mod") * (2.0 * pink01(sim.t, 17.3) - 1.0)
        for (e in tracks) {
            val env = sin(Math.PI * min(e.age / e.life, 1.0)).pow(0.8) * pink
            val cx = e.u + interpAt(sway, cnt, e.u)
            val fAt = interpAt(facing, cnt, cx)
            val vis = env * fAt.pow(1.1)
            if (vis < 0.05) continue
            val j = ((cx - xsPx[0]) / dxPx).toInt().coerceIn(1, cnt - 2)
            val cy = interpAt(ysPx, cnt, cx) + (1.4 + 1.0 * e.seed) * density
            val ang = Math.toDegrees(atan2(ysPx[j + 1] - ysPx[j - 1], 2.0 * dxPx))
            val length = e.len * 0.5 * (0.85 + 0.30 * fAt)
            val thick = (1.1 + 0.9 * e.seed) * density
            val aPk = (92 * a01 * gain * vis).toInt().coerceIn(0, 255)
            if (aPk <= 1) continue
            drawGlowEllipse(canvas, cx, cy, ang, length, thick, streakC, streakC, aPk, 0.42)
        }
    }

    /** 旋转径向渐变椭圆（闪点/流光共用绘制管线）。 */
    private fun drawGlowEllipse(canvas: Canvas, cx: Double, cy: Double, angDeg: Double,
                                length: Double, thick: Double, core: IntArray, edge: IntArray,
                                aPk: Int, midStop: Double) {
        if (aPk <= 1) return
        bandPaint.shader = RadialGradient(0f, 0f, 1f,
            intArrayOf(FableSolColor.toColor(core, aPk),
                FableSolColor.toColor(edge, (aPk * midStop).toInt()),
                FableSolColor.toColor(edge, 0)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        val save = canvas.save()
        canvas.translate(cx.toFloat(), cy.toFloat())
        canvas.rotate(angDeg.toFloat())
        canvas.scale(length.toFloat(), thick.toFloat())
        canvas.drawOval(unitRect, bandPaint)
        canvas.restoreToCount(save)
        bandPaint.shader = null
    }

    /** 薄峰透光场（_thin_glow_field）：绝对海拔门(4→14dp) × 上凸薄度。 */
    private fun thinGlowField(ys: DoubleArray, curv: DoubleArray, cnt: Int): DoubleArray {
        var mean = 0.0; for (j in 0 until cnt) mean += ys[j]; mean /= cnt
        return scratchArray(cnt) {
            val elevDp = (mean - ys[it]) / density
            var gate = ((elevDp - 4.0) / 10.0).coerceIn(0.0, 1.0)
            gate = gate * gate * (3.0 - 2.0 * gate)
            val thin = (curv[it] / (-GLOW_KAPPA)).coerceIn(0.0, 1.0)
            gate * (0.15 + 0.85 * thin)
        }
    }

    /** 波背自阴影场（_back_shade_field）：背光坡 × 脊线邻近；平水自动归零。 */
    private fun backShadeField(slope: DoubleArray, curv: DoubleArray, litSign: Double,
                               cnt: Int): DoubleArray = scratchArray(cnt) {
        var back = ((-slope[it] * litSign - 0.05) / 0.40).coerceIn(0.0, 1.0)
        back = back * back * (3.0 - 2.0 * back)
        val crest = (curv[it] / (-GLOW_KAPPA)).coerceIn(0.0, 1.0)
        back * (0.30 + 0.70 * crest)
    }

    /** 1/f 慢调制（_pink01）：四时间尺度值噪声按 1/k 加权，确定性无状态。 */
    private fun pink01(t: Double, seed: Double): Double {
        var total = 0.0; var wsum = 0.0
        for (k in 0 until 4) {
            val w = 1.0 / (k + 1)
            val x = t / PINK_TAU[k] + seed * (7.31 + k)
            val i0 = Math.floor(x)
            var f = x - i0
            f = f * f * (3.0 - 2.0 * f)
            val v = (1.0 - f) * hash01(i0, seed + k * 3.7) + f * hash01(i0 + 1.0, seed + k * 3.7)
            total += w * v; wsum += w
        }
        return total / wsum
    }

    private fun hash01(a: Double, b: Double): Double {
        val x = sin(a * 127.1 + b * 311.7) * 43758.5453
        return x - Math.floor(x)
    }

    /** 均匀网格线性插值（xs 等距，按索引直接定位）。 */
    private fun interpAt(arr: DoubleArray, cnt: Int, x: Double): Double {
        val dxPx = xsPx[1] - xsPx[0]
        val f = (x - xsPx[0]) / dxPx
        val j = f.toInt().coerceIn(0, cnt - 2)
        val frac = (f - j).coerceIn(0.0, 1.0)
        return arr[j] * (1.0 - frac) + arr[j + 1] * frac
    }

    private fun scratchArray(cnt: Int, initializer: (Int) -> Double): DoubleArray {
        check(doubleScratchIndex < doubleScratch.size) { "FableSol double scratch exhausted" }
        val result = doubleScratch[doubleScratchIndex++]
        for (i in 0 until cnt) result[i] = initializer(i)
        return result
    }

    private fun scratchZero(cnt: Int): DoubleArray = scratchArray(cnt) { 0.0 }

    private fun sliceY(cnt: Int): DoubleArray = scratchArray(cnt) { ysPx[it] }

    /** Catmull-Rom → cubic Bezier，穿过全部采样点，C1 连续（对应 _smooth_curve）。 */
    private fun buildSmooth(path: Path, xs: DoubleArray, ys: DoubleArray, cnt: Int, moveFirst: Boolean) {
        if (moveFirst) path.moveTo(xs[0].toFloat(), ys[0].toFloat())
        else path.lineTo(xs[0].toFloat(), ys[0].toFloat())
        for (k in 0 until cnt - 1) {
            val xkm1 = xs[if (k - 1 < 0) 0 else k - 1]; val ykm1 = ys[if (k - 1 < 0) 0 else k - 1]
            val xk2 = xs[if (k + 2 >= cnt) cnt - 1 else k + 2]; val yk2 = ys[if (k + 2 >= cnt) cnt - 1 else k + 2]
            val c1x = xs[k] + (xs[k + 1] - xkm1) / 6.0
            val c1y = ys[k] + (ys[k + 1] - ykm1) / 6.0
            val c2x = xs[k + 1] - (xk2 - xs[k]) / 6.0
            val c2y = ys[k + 1] - (yk2 - ys[k]) / 6.0
            path.cubicTo(c1x.toFloat(), c1y.toFloat(), c2x.toFloat(), c2y.toFloat(),
                xs[k + 1].toFloat(), ys[k + 1].toFloat())
        }
    }

    /** 短 Hann 核消除逐采样闪烁，保留峰值位置（对应 _smooth_signal）。 */
    private fun smoothSignal(values: DoubleArray, cnt: Int, radius: Int): DoubleArray {
        if (radius <= 0) return values
        val output = scratchZero(cnt)
        FableSolMath.smoothHannInto(values, cnt, radius, output)
        return output
    }

    private fun sliceX(cnt: Int): DoubleArray = scratchArray(cnt) { xsPx[it] }
    private fun reverse(a: DoubleArray, cnt: Int): DoubleArray = scratchArray(cnt) { a[cnt - 1 - it] }

    companion object {
        private const val TARGET_FPS = 60.0
        private const val TARGET_FRAME_SECONDS = 1.0 / TARGET_FPS
        private const val DOUBLE_SCRATCH_CAPACITY = 128
        private const val MAX_DT = 0.05f
        private const val IDLE_SILENCE_MS = 200L
        private const val FILL_EXTRA_DP = 80.0
        private const val INTRINSIC_W_DP = 280f
        private const val INTRINSIC_H_DP = 420f

        private const val GLINT_SIGMA = 0.072
        private const val GLOW_KAPPA = 0.009
        private val WATER_F0 = ((1.333 - 1.0) / (1.333 + 1.0)).pow(2)
        private const val VIEW_ELEVATION_DEG = 38.0

        private val WHITE = intArrayOf(255, 255, 255)
        private val GL_FALLBACK_RED = intArrayOf(255, 0, 0)
        private val POS4 = floatArrayOf(0f, 0.24f, 0.60f, 1f)
        private val KER3 = doubleArrayOf(0.25, 0.5, 0.25)
        private val FULL5 = doubleArrayOf(0.2, 0.2, 0.2, 0.2, 0.2)
        private const val LINEAR_TO_SRGB_LAST = 4096
        private val SRGB_TO_LINEAR = DoubleArray(256) { value ->
            val c = value / 255.0
            if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        private val LINEAR_TO_SRGB = IntArray(LINEAR_TO_SRGB_LAST + 1) { index ->
            val c = index.toDouble() / LINEAR_TO_SRGB_LAST
            val v = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055
            (v * 255.0).roundToInt().coerceIn(0, 255)
        }

        // fade 子带剖面（上沿软入→中段聚光→下缘收出），见 drawOneSidedBand 注释
        private val SUB_OFF = doubleArrayOf(0.00, 0.10, 0.24)
        private val SUB_FRAC = doubleArrayOf(1.00, 0.62, 0.42)
        private val SUB_AA = doubleArrayOf(0.14, 0.34, 0.24)
        private val PINK_TAU = doubleArrayOf(0.9, 3.7, 14.0, 55.0)
    }
}
