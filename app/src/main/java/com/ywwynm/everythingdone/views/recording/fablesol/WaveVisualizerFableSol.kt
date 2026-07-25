package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
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

    private val params = FableSolParams().also { FableSolTuning.applyStored(context, it) }
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

    private val audioInbox = FableSolAnalysisBatchInbox()

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
    private var simulationPaused = false
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
    // 每个九层深度区间含 12 条 ribbon，共 97 行；三角网格仍按区间批量提交为
    // 8 次 drawVertices。数组容量由 ROWS_PER_LAYER 派生，帧内零扩容。
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
    private val surfaceSourceUDp = DoubleArray(FableSolSpec.N_POINTS)
    private val surfaceHermiteWeights = FableSolHermiteWeightTable(FableSolSpec.N_POINTS)
    private val surfaceSlopeX = Array(FableSolContinuousSurface.Z_ROWS) {
        DoubleArray(FableSolSpec.N_POINTS)
    }
    private val surfaceSlopeZ = Array(FableSolContinuousSurface.Z_ROWS) {
        DoubleArray(FableSolSpec.N_POINTS)
    }
    private val surfaceCrestPinch = Array(FableSolContinuousSurface.Z_ROWS) {
        DoubleArray(FableSolSpec.N_POINTS)
    }
    private val layerMeans = DoubleArray(FableSolSpec.N_LAYERS)
    private val layerMeanTangents = DoubleArray(FableSolSpec.N_LAYERS)
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
    private class Track(
        var u: Double,
        var inten: Double,
        val birthSize: Double,
        val seed: Double,
        val birthPathWeight: Double
    )
    private val glintTracks = Array(FableSolSpec.N_LAYERS) { ArrayList<Track>(4) }
    private val effectiveGlintCapacity = IntArray(FableSolSpec.N_LAYERS)
    private val eligibleGlintLayerCount = (0 until FableSolSpec.N_LAYERS).count {
        FableSolMaterialPolicy.glintCapacity(it) > 0
    }
    private val glintAnchorUsed = BooleanArray(MAX_GLINT_ANCHORS)
    private val glitterCandidateLayer = IntArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidateU = DoubleArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidateIntensity = DoubleArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidateSize = DoubleArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidatePathWeight = DoubleArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidateScore = DoubleArray(MAX_GLITTER_CANDIDATES)
    private val glitterCandidateUsed = BooleanArray(MAX_GLITTER_CANDIDATES)
    private var glitterCandidateCount = 0
    private var trackT = 0.0
    private val canvasDepthAxisX = DoubleArray(FableSolSpec.N_POINTS)
    private val canvasDepthAxisY = DoubleArray(FableSolSpec.N_POINTS)
    private val unitRect = RectF(-1f, -1f, 1f, 1f)
    private val unitSurfaceGlint = Path().apply {
        moveTo(-0.98f, 0.12f)
        cubicTo(-0.94f, 0.04f, -0.78f, 0f, -0.58f, 0f)
        lineTo(0.58f, 0f)
        cubicTo(0.78f, 0f, 0.94f, 0.04f, 0.98f, 0.12f)
        cubicTo(0.88f, 0.55f, 0.50f, 0.84f, 0f, 1f)
        cubicTo(-0.50f, 0.84f, -0.88f, 0.55f, -0.98f, 0.12f)
        close()
    }
    private val surfaceGlintMatrix = Matrix()
    private val surfaceGlintTransform = FloatArray(9)
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

    /** 运行时调参（调参 Dialog 实时预览）：本 View 的物理与渲染都在 UI 线程，直接写。 */
    internal fun setTuningValue(key: String, value: Double) {
        params.set(key, value)
    }

    /** 暂停冻结（与 Python canvas 同语义）：模拟与音频泵停住，绘制照跑。 */
    internal fun setSimulationPaused(paused: Boolean) {
        simulationPaused = paused
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
        audioInbox.offer(frames, events)
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
        val physicsStart = SystemClock.elapsedRealtimeNanos()
        if (!simulationPaused) {
            applyLatestGravity()
            sim.update(dt.toDouble())
        }
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
        val batches = audioInbox.drain()
        if (simulationPaused) {
            // 冻结：丢弃本帧特征与事件，静默衰减计时锚随帧推移一并冻结。
            if (mLastAudioElapsed != 0L) mLastAudioElapsed = now
            return
        }
        val hasFrames = batches.any { it.frames.isNotEmpty() }
        if (hasFrames) {
            // Canvas 回退与 GLES 主路径共享同一 authoritative 音频时钟和事件交织。
            FableSolAnalysisBatchConsumer.consume(
                batches,
                { mapper.applyFrame(sim, it) },
                ::applyAudioEvent
            )
            mLastAudioElapsed = now
        } else if (mLastAudioElapsed != 0L && now - mLastAudioElapsed > IDLE_SILENCE_MS) {
            mapper.applySilence(sim)
            FableSolAnalysisBatchConsumer.consume(batches, {}, ::applyAudioEvent)
        } else {
            FableSolAnalysisBatchConsumer.consume(batches, {}, ::applyAudioEvent)
        }

    }

    private fun applyAudioEvent(event: FableSolEvent) {
        when (event) {
            is FableSolEvent.Onset -> mapper.applyOnset(sim, event)
            is FableSolEvent.Section -> mapper.applySection(sim, event)
            is FableSolEvent.Prominence -> mapper.applyProminence(sim, event)
            is FableSolEvent.Drop -> mapper.applyDrop(sim, event)
            is FableSolEvent.NoveltyMinor -> Unit
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
        glitterCandidateCount = 0
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val breath = params.get("color_breath") *
            (0.30 * (sim.colorBright01 - 0.45) + 0.18 * (sim.colorEnergy01 - 0.5))
        val layerPalette = FableSolLayerColorPolicy.palette(
            FableSolLayerColorPolicy.baseColors(c1Base, c2Base),
            params.get("lighten_far"),
            sim.moodBright,
            breath
        )
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
                surfaceSourceUDp[j] = sim.uGrid[index] * (1.0 - fraction) +
                    sim.uGrid[index + 1] * fraction
                surfaceHermiteWeights.update(j, fraction, FableSolSpec.DX_DP)
                xsPx[j] = surfaceSourceUDp[j] * density
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
            drawContinuousSurface(canvas, info, cnt, fillBottom, layerPalette)
        } else {
            // 内部 A 基线：原九层完整水体，远→近各自填到底部。
            for (i in FableSolSpec.N_LAYERS - 1 downTo 0) {
                val row = sim.heights[i]
                for (j in 0 until cnt) ysPx[j] = (hG / 2.0 - row[i0 + j]) * density
                drawLayer(canvas, i, cnt, fillBottom, i0, layerPalette)
            }
        }
        canvas.restoreToCount(save)
        scheduleGlitterBirths(
            FableSolGlintEnvelopePolicy.trackingDeltaSeconds(max(sim.t - trackT, 0.0))
        )
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

    private class LayerColors(
        val start: IntArray,
        val stop1: IntArray,
        val stop2: IntArray,
        val end: IntArray,
        val interfaceWeights: DoubleArray,
        val alpha255: Int
    )

    private fun layerColors(i: Int, palette: FableSolLayerColorPalette): LayerColors {
        val stops = palette.layers[i]
        val weights = palette.interfaceWeights
        return LayerColors(
            stops.start,
            stops.stop1,
            stops.stop2,
            stops.end,
            weights.forContour(i),
            (params.lget("alpha", i) * 255).roundToInt().coerceIn(0, 255)
        )
    }

    /**
     * 九层状态吸收到一张连续曲面：25 条 Z 行共享边界，远→近绘制 ribbon；九条
     * 原层轮廓继续作为几何、颜色与完整光学族锚线，第一层是唯一前景剪影。
     */
    private fun drawContinuousSurface(canvas: Canvas, info: FableSolRenderInfo,
                                      cnt: Int, fillBottom: Double,
                                      layerPalette: FableSolLayerColorPalette) {
        val samplingStart = SystemClock.elapsedRealtimeNanos()
        val sample = sim.surface2d.sample(sim)
        val means = layerMeans
        sim.fillLayerDcDp(means)   // 巨浪的局部隆起不计入基准高度（D178）
        FableSolDepthBaseline.updateTangents(means, layerMeanTangents)
        val viewBase = params.get("surface_view_elev_deg")
        val viewElev = FableSolPitchPolicy.viewElevationDeg(sim.pitchDeg, viewBase)
        val depthScale = sin(Math.toRadians(viewElev)) /
            max(sin(Math.toRadians(viewBase)), 0.2)
        for (r in 0 until FableSolContinuousSurface.Z_ROWS) {
            for (j in 0 until cnt) {
                val xIndex = surfaceSourceIndex[j]
                val fraction = surfaceSourceFraction[j]
                val next = xIndex + 1
                // 与 GLES buildFrame 完全相同：ContinuousSurface 已把物理节点公平化为
                // 同一条 C2 cubic B-spline 的节点值与解析切线。Canvas 只做 Hermite
                // 重建，禁止再套一层 Catmull-Rom 造成过冲、尖角或局部下凹。
                val orbitZ =
                    sample.orbitZ[r][xIndex] * surfaceHermiteWeights.h00[j] +
                        sample.orbitZSlope[r][xIndex] * surfaceHermiteWeights.h10[j] +
                        sample.orbitZ[r][next] * surfaceHermiteWeights.h01[j] +
                        sample.orbitZSlope[r][next] * surfaceHermiteWeights.h11[j]
                val orbitX =
                    sample.orbitX[r][xIndex] * surfaceHermiteWeights.h00[j] +
                        sample.orbitXSlope[r][xIndex] * surfaceHermiteWeights.h10[j] +
                        sample.orbitX[r][next] * surfaceHermiteWeights.h01[j] +
                        sample.orbitXSlope[r][next] * surfaceHermiteWeights.h11[j]
                val worldEta =
                    sample.worldEta[r][xIndex] * surfaceHermiteWeights.h00[j] +
                        sample.slopeX[r][xIndex] * surfaceHermiteWeights.h10[j] +
                        sample.worldEta[r][next] * surfaceHermiteWeights.h01[j] +
                        sample.slopeX[r][next] * surfaceHermiteWeights.h11[j]
                val uDp = surfaceSourceUDp[j]
                val zEff = sample.zDp[r] + orbitZ
                // 不钳制外层纵向轨道；DepthBaseline 沿端点切线延拓，避免穿过
                // 第一/末层时导数突然归零并形成“斜线接平台”。
                val z01 = zEff / max(sample.depthDp, 1e-6)
                val layerPosition = z01 * (FableSolSpec.N_LAYERS - 1)
                var baseH = FableSolDepthBaseline.value(
                    means,
                    layerMeanTangents,
                    layerPosition
                )
                baseH = means[0] + (baseH - means[0]) * depthScale
                val perspective = 1.0 / (1.0 + 0.16 * z01)
                surfaceXsPx[r][j] = (uDp + orbitX) *
                    density * perspective
                surfaceYsPx[r][j] = (info.hG / 2.0 -
                    (baseH + worldEta)) * density
                surfaceSlopeX[r][j] =
                    sample.worldEta[r][xIndex] * surfaceHermiteWeights.dh00[j] +
                        sample.slopeX[r][xIndex] * surfaceHermiteWeights.dh10[j] +
                        sample.worldEta[r][next] * surfaceHermiteWeights.dh01[j] +
                        sample.slopeX[r][next] * surfaceHermiteWeights.dh11[j]
                // slopeZ 是着色属性；与 GLES 一样做线性采样，避免无必要的四点超调。
                surfaceSlopeZ[r][j] = sample.slopeZ[r][xIndex] * (1.0 - fraction) +
                    sample.slopeZ[r][next] * fraction
                val orbitDerivative =
                    sample.orbitX[r][xIndex] * surfaceHermiteWeights.dh00[j] +
                        sample.orbitXSlope[r][xIndex] * surfaceHermiteWeights.dh10[j] +
                        sample.orbitX[r][next] * surfaceHermiteWeights.dh01[j] +
                        sample.orbitXSlope[r][next] * surfaceHermiteWeights.dh11[j]
                surfaceCrestPinch[r][j] =
                    FableSolDepthScatteringPolicy.crestPinch(orbitDerivative)
            }
            // 透视与横向轨道叠加后若逼近回折，整行只使用一个比例朝无轨道
            // 基线收回。禁止逐点 accumulate/clip；后者会制造平台和小阶梯。
            FableSolCanvasProjection.repairMonotoneInPlace(
                projectedX = surfaceXsPx[r],
                sourceUDp = surfaceSourceUDp,
                count = cnt,
                baselinePerspective = 1.0 / (1.0 + 0.16 * sample.z01[r]),
                density = density,
                minimumSpacingRatio = PROJECTED_MINIMUM_SPACING_RATIO
            )
        }
        renderSamplingNs += SystemClock.elapsedRealtimeNanos() - samplingStart

        val colorStart = SystemClock.elapsedRealtimeNanos()
        val palettes = Array(FableSolSpec.N_LAYERS) { layerColors(it, layerPalette) }
        val colorStops = Array(FableSolSpec.N_LAYERS) { i ->
            arrayOf(
                palettes[i].start,
                palettes[i].stop1,
                palettes[i].stop2,
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
                cnt, info.thetaRad, palettes, colorStops,
                gradientGeometry, environment)
            drawContinuousOptics(canvas, layer, farAnchor, cnt, palettes[layer], fillBottom)
        }

        // 第一层以下的深水体积；第一层是唯一近端剪影。
        for (j in 0 until cnt) {
            xsPx[j] = surfaceXsPx[0][j]
            ysPx[j] = surfaceYsPx[0][j]
        }
        fillPath.reset()
        appendPolyline(fillPath, xsPx, ysPx, cnt, true)
        fillPath.lineTo(xsPx[cnt - 1].toFloat(), fillBottom.toFloat())
        fillPath.lineTo(xsPx[0].toFloat(), fillBottom.toFloat())
        fillPath.close()
        val front = palettes[0]
        fillPaint.shader = dithered(layerShader(front, 255, cnt, fillBottom))
        canvas.drawPath(fillPath, fillPaint)
        fillPaint.shader = null
        drawContinuousOptics(canvas, 0, 0, cnt, front, fillBottom)
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
                                          cnt: Int, thetaRad: Double,
                                          palettes: Array<LayerColors>,
                                           colorStops: Array<Array<IntArray>>,
                                           geometry: Array<LayerGradientGeometry>,
                                           environment: EnvironmentColors) {
        val colorStart = SystemClock.elapsedRealtimeNanos()
        for (r in nearAnchor..farAnchor) {
            for (j in 0 until cnt) {
                surfaceVertexColors[r][j] = continuousVertexColor(
                    r, j, startLayer, thetaRad,
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
                                      thetaRad: Double,
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
                q <= 0.24 -> {
                    a = stops[0]; b = stops[1]; t = q / 0.24
                }
                q <= 0.60 -> {
                    a = stops[1]; b = stops[2]; t = (q - 0.24) / 0.36
                }
                else -> {
                    a = stops[2]; b = stops[3]; t = (q - 0.60) / 0.40
                }
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
            surfaceSlopeZ[row][j],
            row.toDouble() / (FableSolContinuousSurface.Z_ROWS - 1),
            surfaceCrestPinch[row][j])
    }

    /** 加入同色直射光瓣；背坡可见度只削减该光瓣，不乘暗主体。 */
    private fun applyLongitudinalLight(base: Int, sx: Double, sz: Double,
                                       depth01: Double, crestPinch: Double): Int {
        val inv = 1.0 / max(sqrt(1.0 + sx * sx + sz * sz), 1e-9)
        val nx = -sx * inv; val ny = inv; val nz = -sz * inv
        val invRef = 1.0 / max(sqrt(1.0 + sx * sx), 1e-9)
        val nxRef = -sx * invRef; val nyRef = invRef
        val lightElev = Math.toRadians(50.0)
        val az = Math.toRadians(params.get("light_azimuth_deg"))
        val lx = sin(az) * cos(lightElev)
        val ly = sin(lightElev)
        val lz = -cos(az) * cos(lightElev)
        val fullNdl = (nx * lx + ny * ly + nz * lz).coerceIn(0.0, 1.0)
        val refNdl = (nxRef * lx + nyRef * ly).coerceIn(0.0, 1.0)
        val relativeNdl = fullNdl - refNdl
        return FableSolLightColorPolicy.resolveLongitudinal(
            base,
            relativeNdl.coerceAtLeast(0.0),
            depth01,
            (-relativeNdl).coerceAtLeast(0.0),
            fullNdl,
            crestPinch
        )
    }

    private fun drawContinuousOptics(canvas: Canvas, i: Int, row: Int, cnt: Int,
                                     lc: LayerColors, fillBottom: Double) {
        val scratchMark = doubleScratchIndex
        val depthStride = max(1, FableSolContinuousSurface.ROWS_PER_LAYER / 3)
        val depthRow = min(row + depthStride, FableSolContinuousSurface.Z_ROWS - 1)
        for (j in 0 until cnt) {
            xsPx[j] = surfaceXsPx[row][j]
            ysPx[j] = surfaceYsPx[row][j]
            canvasDepthAxisX[j] = surfaceXsPx[depthRow][j] - xsPx[j]
            canvasDepthAxisY[j] = surfaceYsPx[depthRow][j] - ysPx[j]
        }
        val depth01 = i.toDouble() / (FableSolSpec.N_LAYERS - 1)
        drawInterfaceShoulder(canvas, i, cnt, lc, fillBottom)
        drawBackShade(canvas, i, cnt, lc, fillBottom, depth01)
        if (params.get("crest_on") >= 0.5) {
            drawHighlights(
                canvas = canvas,
                i = i,
                cnt = cnt,
                colors = lc,
                i0 = 0,
                depth01 = depth01,
                fillBottom = fillBottom,
                depthAxisX = canvasDepthAxisX,
                depthAxisY = canvasDepthAxisY
            )
        }
        doubleScratchIndex = scratchMark
    }

    /** C1（AGSL）：渐变包一层三角抖动，消除 OLED 平缓渐变的色阶条纹；不支持时原样返回。 */
    private fun dithered(src: Shader): Shader {
        val d = FableSolAgsl.dither ?: return src
        d.setInputShader("src", src)
        return d
    }

    private fun drawLayer(canvas: Canvas, i: Int, cnt: Int, fillBottom: Double, i0: Int,
                          palette: FableSolLayerColorPalette) {
        val scratchMark = doubleScratchIndex
        val depth01 = i.toDouble() / (FableSolSpec.N_LAYERS - 1)
        val lc = layerColors(i, palette)

        fillPath.reset()
        appendPolyline(fillPath, xsPx, ysPx, cnt, true)
        fillPath.lineTo(xsPx[cnt - 1].toFloat(), fillBottom.toFloat())
        fillPath.lineTo(xsPx[0].toFloat(), fillBottom.toFloat())
        fillPath.close()

        // D127：主体填充只承载层色。旧 AGSL 在编码 sRGB 中把整层乘暗并不是真实
        // Beer–Lambert；体积吸收必须等待独立透射/折射分瓣，不能污染主体。
        val gradient = dithered(layerShader(lc, lc.alpha255, cnt, fillBottom))
        fillPaint.shader = gradient
        canvas.drawPath(fillPath, fillPaint)
        fillPaint.shader = null

        drawInterfaceShoulder(canvas, i, cnt, lc, fillBottom)
        drawBackShade(canvas, i, cnt, lc, fillBottom, depth01)

        if (params.get("crest_on") >= 0.5) {
            drawHighlights(canvas, i, cnt, lc, i0, depth01, fillBottom)
        }
        doubleScratchIndex = scratchMark
    }

    private fun currentLayerGradientGeometry(cnt: Int, fillBottom: Double): LayerGradientGeometry {
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
        val dx = gx1 - gx0
        val dy = gy1 - gy0
        return LayerGradientGeometry(gx0, gy0, dx, dy, max(dx * dx + dy * dy, 1e-6))
    }

    private fun layerShader(colors: LayerColors, a255: Int, cnt: Int,
                            fillBottom: Double): LinearGradient {
        val geometry = currentLayerGradientGeometry(cnt, fillBottom)
        val packedColors = intArrayOf(
            FableSolColor.toColor(colors.start, a255),
            FableSolColor.toColor(colors.stop1, a255),
            FableSolColor.toColor(colors.stop2, a255),
            FableSolColor.toColor(colors.end, a255)
        )
        return LinearGradient(
            geometry.ox.toFloat(), geometry.oy.toFloat(),
            (geometry.ox + geometry.dx).toFloat(),
            (geometry.oy + geometry.dy).toFloat(),
            packedColors, POS4, Shader.TileMode.CLAMP)
    }

    /**
     * D129：主体色阶在浅色/色域边缘留下的相邻分离缺口，由宽、软、低幅的同色界面肩补足。
     * 权重随四个 Thing 渐变停靠点变化；亮侧和深侧都只移动当前位置层色的 OKLab L，
     * 不画固定白边、黑边或八条等强描线。该步骤先于反射、透射与闪点，避免暗侧覆盖光学分瓣。
     */
    private fun drawInterfaceShoulder(canvas: Canvas, layer: Int, cnt: Int,
                                      colors: LayerColors, fillBottom: Double) {
        if (layer >= FableSolSpec.N_LAYERS - 1) return
        var maximum = 0.0
        for (weight in colors.interfaceWeights) maximum = max(maximum, weight)
        if (maximum < 1e-3) return

        val scratchMark = doubleScratchIndex
        val geometry = currentLayerGradientGeometry(cnt, fillBottom)
        val brightTop = scratchZero(cnt)
        val brightThickness = scratchZero(cnt)
        val deepTop = scratchZero(cnt)
        val deepThickness = scratchZero(cnt)
        for (j in 0 until cnt) {
            val q = (((xsPx[j] - geometry.ox) * geometry.dx +
                (ysPx[j] - geometry.oy) * geometry.dy) / geometry.denominator)
                .coerceIn(0.0, 1.0)
            val weight = interpolateFourStops(colors.interfaceWeights, q)
            val envelope = sqrt(weight.coerceIn(0.0, 1.0))
            val width = (FableSolInterfaceShoulderPolicy.MIN_WIDTH_DP +
                (FableSolInterfaceShoulderPolicy.MAX_WIDTH_DP -
                    FableSolInterfaceShoulderPolicy.MIN_WIDTH_DP) * weight) * density * envelope
            brightTop[j] = ysPx[j] - width
            brightThickness[j] = width
            deepTop[j] = ysPx[j]
            deepThickness[j] = width * 0.72
        }

        val bright = interfaceLayerColors(colors, bright = true)
        val deep = interfaceLayerColors(colors, bright = false)
        drawGradientOneSidedBand(
            canvas, cnt, deepTop, deepThickness, deep,
            255, fillBottom
        )
        drawGradientOneSidedBand(
            canvas, cnt, brightTop, brightThickness, bright,
            255, fillBottom
        )
        doubleScratchIndex = scratchMark
    }

    private fun interpolateFourStops(values: DoubleArray, qIn: Double): Double {
        val q = qIn.coerceIn(0.0, 1.0)
        return when {
            q <= 0.24 -> values[0] + (values[1] - values[0]) * (q / 0.24)
            q <= 0.60 -> values[1] + (values[2] - values[1]) * ((q - 0.24) / 0.36)
            else -> values[2] + (values[3] - values[2]) * ((q - 0.60) / 0.40)
        }
    }

    private fun interfaceLayerColors(colors: LayerColors, bright: Boolean): LayerColors {
        fun shift(color: IntArray, stop: Int): IntArray = if (bright) {
            FableSolInterfaceShoulderPolicy.bright(color, colors.interfaceWeights[stop])
        } else {
            FableSolInterfaceShoulderPolicy.deep(color, colors.interfaceWeights[stop])
        }
        return LayerColors(
            shift(colors.start, 0), shift(colors.stop1, 1),
            shift(colors.stop2, 2), shift(colors.end, 3),
            colors.interfaceWeights, colors.alpha255
        )
    }

    /** 带内 alpha 仍由 C2 轮廓 shader 产生；颜色改为当前层四停靠点渐变。 */
    private fun drawGradientOneSidedBand(canvas: Canvas, cnt: Int,
                                         top: DoubleArray, thickness: DoubleArray,
                                         colors: LayerColors, alphaIn: Int,
                                         fillBottom: Double) {
        drawOneSidedBandWithColorShader(
            canvas, cnt, top, thickness, alphaIn
        ) { alpha ->
            layerShader(colors, alpha, cnt, fillBottom)
        }
    }

    private inline fun drawOneSidedBandWithColorShader(
        canvas: Canvas,
        cnt: Int,
        top: DoubleArray,
        thickness: DoubleArray,
        alphaIn: Int,
        shaderForAlpha: (Int) -> Shader
    ) {
        val alpha = alphaIn.coerceIn(0, 255)
        if (alpha <= 0) return
        var lo = -1; var hi = -1
        for (j in 0 until cnt) {
            if (thickness[j] > 0.05) {
                if (lo < 0) lo = j
                hi = j
            }
        }
        if (lo < 0) return
        lo = max(lo - 2, 0); hi = min(hi + 3, cnt)
        if (hi - lo < 4) return

        val bandShader = FableSolAgsl.band
        if (bandShader != null) {
            try {
                var yMin = Double.MAX_VALUE; var yMax = -Double.MAX_VALUE
                for (j in lo until hi) {
                    if (top[j] < yMin) yMin = top[j]
                    yMax = max(yMax, top[j] + thickness[j])
                }
                val yRange = max(yMax - yMin, 1.0)
                bandShader.setInputBuffer(
                    "data", contourData(top, thickness, null, cnt, yMin, yRange)
                )
                bandShader.setFloatUniform("x0", xsPx[0].toFloat())
                bandShader.setFloatUniform("dxStep", (xsPx[1] - xsPx[0]).toFloat())
                bandShader.setFloatUniform("cntF", cnt.toFloat())
                bandShader.setFloatUniform("yMin", yMin.toFloat())
                bandShader.setFloatUniform("yRange", yRange.toFloat())
                bandShader.setFloatUniform("tint", 1f, 1f, 1f, alpha / 255f)
                fillPaint.shader = ComposeShader(
                    shaderForAlpha(255),
                    bandShader,
                    PorterDuff.Mode.DST_IN
                )
                canvas.drawRect(
                    xsPx[lo].toFloat(), yMin.toFloat(),
                    xsPx[hi - 1].toFloat(), yMax.toFloat(), fillPaint
                )
                fillPaint.shader = null
                return
            } catch (_: Throwable) {
                fillPaint.shader = null
            }
        }

        // API <33 或组合 shader 不可用：三条几何子带保持同一四停靠点颜色语义。
        for (s in 0 until 3) {
            val subAlpha = (alpha * SUB_AA[s]).roundToInt().coerceIn(0, 255)
            fillPaint.shader = shaderForAlpha(subAlpha)
            bandPathLite(lo, hi, top, thickness, SUB_OFF[s], SUB_FRAC[s])
            canvas.drawPath(bandPath, fillPaint)
        }
        fillPaint.shader = null
    }

    /**
     * 波背自阴影（对应 canvas.py 的 _draw_back_shade）：主体之后、反射/透射之前
     * 绘制当前位置的随层保色暗带。
     * D169 恢复注：原式的空气透视因子随 aerial_contrast 按 0 固化为 1。
     */
    private fun drawBackShade(canvas: Canvas, layer: Int, cnt: Int,
                              colors: LayerColors, fillBottom: Double,
                              depth01: Double) {
        val gain = params.get("back_shade_gain")
        val layerWeight = FableSolMaterialPolicy.backShadeAlphaWeight(layer)
        if (gain <= 1e-3 || layerWeight <= 0.0) return

        val scratchMark = doubleScratchIndex
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
        val litSign = if (params.get("light_azimuth_deg") >= 0.0) 1.0 else -1.0
        val shade = smoothSignal(backShadeField(slope, curv, litSign, cnt), cnt, 4)
        var maximum = 0.0
        for (value in shade) maximum = max(maximum, value)
        if (maximum > 0.04) {
            val top = scratchArray(cnt) { ysPx[it] + 0.3 * density }
            val thickness = scratchArray(cnt) {
                (2.0 + 13.0 * shade[it]) * density * sqrt(shade[it]) *
                    FableSolMaterialPolicy.backShadeWidthWeight(layer)
            }
            fun shadow(color: IntArray): IntArray = FableSolShadowColorPolicy.backShade(
                color, params.get("hue_temp_deg"), depth01
            )
            val shadowColors = LayerColors(
                shadow(colors.start), shadow(colors.stop1),
                shadow(colors.stop2), shadow(colors.end),
                colors.interfaceWeights, colors.alpha255
            )
            val alpha = (88.0 * (colors.alpha255 / 255.0) * gain * layerWeight)
                .roundToInt()
            drawGradientOneSidedBand(
                canvas, cnt, top, thickness, shadowColors, alpha, fillBottom
            )
        }
        doubleScratchIndex = scratchMark
    }

    /** 波背自阴影场：背光坡 × 脊线邻近；平水坡度≈0 时场自动归零，不常驻。 */
    private fun backShadeField(slope: DoubleArray, curv: DoubleArray, litSign: Double,
                               cnt: Int): DoubleArray = scratchArray(cnt) {
        var back = ((-slope[it] * litSign - 0.05) / 0.40).coerceIn(0.0, 1.0)
        back = back * back * (3.0 - 2.0 * back)
        val crest = (curv[it] / (-GLOW_KAPPA)).coerceIn(0.0, 1.0)
        back * (0.30 + 0.70 * crest)
    }

    /**
     * 物理近似高光（对应 canvas.py 的 _draw_highlights）：菲涅尔亮边 + 水体透光 + 闪点。
     * 全部是表面坡度的局部函数，无需光线追踪。
     */
    private fun drawHighlights(canvas: Canvas, i: Int, cnt: Int, colors: LayerColors,
                              i0: Int, depth01: Double, fillBottom: Double,
                              depthAxisX: DoubleArray? = null,
                              depthAxisY: DoubleArray? = null) {
        val c1 = colors.start
        val c2 = colors.end
        val a255 = colors.alpha255
        val ls = sim.layers[i]
        val ys = scratchArray(cnt) { ysPx[it] }
        val dxPx = xsPx[1] - xsPx[0]
        val ker = KER3
        val gradY = scratchZero(cnt)
        FableSolMath.gradientInto(ys, cnt, dxPx, gradY)
        val slopeRaw = scratchArray(cnt) { -gradY[it] }
        val slope = scratchZero(cnt)
        FableSolMath.convolveSameInto(slopeRaw, cnt, ker, slope)
        val rough = ls.roughness01
        val uDp = scratchArray(cnt) { xsPx[it] / density }
        val micro = scratchZero(cnt)
        val microCurv = scratchZero(cnt)
        if (FableSolMaterialPolicy.glintCapacity(i) > 0) {
            ls.optical.sampleInto(uDp, cnt, rough, micro, microCurv)
        }
        val opticalSlope = scratchArray(cnt) { slope[it] + micro[it] }
        val s0 = tan(Math.toRadians(params.get("light_azimuth_deg")) / 2.0)
        val sigma = GLINT_SIGMA * (1.0 + 0.42 * rough)
        val sinElev = sin(Math.toRadians(VIEW_ELEVATION_DEG))
        val flatFres = WATER_F0 + (1.0 - WATER_F0) * (1.0 - sinElev).pow(5)
        val skyStrength = params.get("sky_reflection_strength")
        val edge = scratchZero(cnt)
        // 镜面反射项（2026-07-18 恢复闪点出生）：强度固化 0.90（原
        // crest_glint_strength 默认，参数不恢复），数量总门 glint_capacity_gain。
        for (j in 0 until cnt) {
            val os = opticalSlope[j]
            val glint = exp(-((os - s0) / sigma).pow(2))
            val facet = (abs(microCurv[j]) / (0.004 + 0.006 * rough))
                .coerceIn(0.0, 1.0).pow(0.58)
            val cosTheta = (sinElev / sqrt(1.0 + os * os)).coerceIn(0.0, 1.0)
            val fr = WATER_F0 + (1.0 - WATER_F0) * (1.0 - cosTheta).pow(5)
            val fresDetail = ((fr - flatFres) * 4.0).coerceIn(0.0, 1.0)
            val edgeRaw = (glint * facet * 0.90 + fresDetail * skyStrength * 0.24)
                .coerceIn(0.0, 1.0)
            edge[j] = ((edgeRaw - 0.08) / 0.92).coerceIn(0.0, 1.0)
        }
        val edgeS = smoothSignal(edge, cnt, 3)
        for (j in 0 until cnt) if (edgeS[j] < 0.015) edgeS[j] = 0.0

        // 体光带已随 D216 整项移除（无感 + HDR 资格门数学死路）。
        val hc = FableSolColor.mix(c1, c2, 0.3)
        val a01 = a255 / 255.0
        // 闪点最后绘制，不能再被半透明介质衰减。
        var mx = 0.0; for (v in edgeS) if (v > mx) mx = v
        if (mx > 1e-3) {
            drawGlints(
                canvas, i, cnt, ys, edgeS, hc, a01,
                depthAxisX, depthAxisY
            )
        }
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
        appendPolyline(bandPath, xsPx, top, cnt, true)
        appendPolyline(bandPath, xsPx, bottom, cnt, false, reverse = true)
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

    /** 场的局部极大 → 锚点 (u, 强度, 半宽)（_field_peaks）。半宽随浪形伸缩。 */
    private fun fieldPeaks(field: DoubleArray, cnt: Int, floor: Double, minSepPx: Double,
                           kMax: Int): ArrayList<DoubleArray> {
        anchorsScratch.clear()
        if (kMax <= 0) return anchorsScratch
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
    private fun updateGlintTracks(tracks: ArrayList<Track>, anchors: ArrayList<DoubleArray>,
                                  dt: Double, matchPx: Double, attackS: Double,
                                  releaseS: Double, posTau: Double, cap: Int) {
        val kPos = 1.0 - exp(-dt / max(posTau, 1e-3))
        val kAtt = 1.0 - exp(-dt / max(attackS, 1e-3))
        val kRel = 1.0 - exp(-dt / max(releaseS, 1e-3))
        java.util.Arrays.fill(glintAnchorUsed, false)
        for (e in tracks) {
            var best = -1; var bestD = matchPx
            for (ai in anchors.indices) {
                if (glintAnchorUsed[ai]) continue
                val d = abs(anchors[ai][0] - e.u)
                if (d < bestD) { best = ai; bestD = d }
            }
            if (best >= 0) {
                glintAnchorUsed[best] = true
                val a = anchors[best]
                e.u += (a[0] - e.u) * kPos
                val k = if (a[1] > e.inten) kAtt else kRel
                e.inten += (a[1] - e.inten) * k
            } else e.inten -= e.inten * kRel
        }
        tracks.removeAll {
            it.inten <= FableSolGlintEnvelopePolicy.TRACK_RETIRE_INTENSITY
        }
        tracks.sortByDescending { it.inten }
        while (tracks.size > cap) tracks.removeAt(tracks.size - 1)
    }

    /** 镜面闪点（_draw_glints）：少量持久实体贴着受光浪面滑行，无周期呼吸或强度驱动的尺寸扩张。 */
    private fun drawGlints(canvas: Canvas, i: Int, cnt: Int, ys: DoubleArray, prob: DoubleArray,
                           hc: IntArray, a01: Double,
                           depthAxisX: DoubleArray?, depthAxisY: DoubleArray?) {
        val dt = FableSolGlintEnvelopePolicy.trackingDeltaSeconds(
            max(sim.t - trackT, 0.0)
        )
        val spark = 0.35 + 0.65 * sim.sparkle01
        val fieldInput = scratchArray(cnt) {
            (prob[it] * 1.5).coerceIn(0.0, 1.0) * spark
        }
        val field = smoothSignal(fieldInput, cnt, 5)
        val cap = Math.round(
            FableSolMaterialPolicy.glintCapacity(i) *
                (params.get("glint_capacity_gain") * sim.glintCapacity01)
                    .coerceIn(0.0, 1.0)
        ).toInt()
        effectiveGlintCapacity[i] = cap
        val anchors = fieldPeaks(
            field,
            cnt,
            FableSolMaterialPolicy.GLINT_FIELD_FLOOR,
            FableSolMaterialPolicy.GLINT_MIN_SEPARATION_DP * density,
            cap
        )
        updateGlintTracks(
            glintTracks[i], anchors, dt, 34.0 * density, 0.30, 0.80, 0.10, cap
        )
        val depth01 = i.toDouble() / (FableSolSpec.N_LAYERS - 1)
        val visibleSpan = max(xsPx[cnt - 1] - xsPx[0], 1e-6)
        val lightAzimuth = params.get("light_azimuth_deg")
        for (anchor in anchors.indices) {
            if (glintAnchorUsed[anchor] ||
                glitterCandidateCount >= MAX_GLITTER_CANDIDATES
            ) continue
            val candidate = anchors[anchor]
            val x01 = ((candidate[0] - xsPx[0]) / visibleSpan).coerceIn(0.0, 1.0)
            val pathWeight = FableSolSunGlitterPolicy.birthWeight(
                x01, depth01, lightAzimuth
            )
            glitterCandidateLayer[glitterCandidateCount] = i
            glitterCandidateU[glitterCandidateCount] = candidate[0]
            glitterCandidateIntensity[glitterCandidateCount] = candidate[1]
            glitterCandidateSize[glitterCandidateCount] = candidate[2]
            glitterCandidatePathWeight[glitterCandidateCount] = pathWeight
            glitterCandidateScore[glitterCandidateCount] = candidate[1] * pathWeight
            glitterCandidateCount++
        }
        val tracks = glintTracks[i]
        if (tracks.isEmpty()) return
        val core = FableSolColor.mixOklab(hc, WHITE, 0.35)
        val dxPx = xsPx[1] - xsPx[0]
        for (e in tracks) {
            val inten = e.inten.coerceIn(0.0, 1.0)
            val alpha = FableSolGlintEnvelopePolicy.coreAlpha(
                inten,
                a01,
                FableSolMaterialPolicy.glintCoreAlphaWeight(i)
            )
            if (alpha <= 1.0 / 255.0) continue
            val cx = e.u
            val j = ((cx - xsPx[0]) / dxPx).toInt().coerceIn(1, cnt - 2)
            val cy = interpAt(ys, cnt, cx)
            val halfLength = (
                e.birthSize * 0.42 * FableSolMaterialPolicy.glintLengthWeight(i)
                ).coerceIn(2.4 * density, 12.0 * density)
            val baseDepth = (1.1 + 0.8 * e.seed) * density
            val requestedDepth = FableSolSunGlitterPolicy.depthAxisLengthDp(
                i, e.birthPathWeight
            ) * density
            val tangentX = 2.0 * dxPx
            val tangentY = ys[j + 1] - ys[j - 1]
            val tangentLength = hypot(tangentX, tangentY).coerceAtLeast(1e-4)
            val unitTangentX = tangentX / tangentLength
            val unitTangentY = tangentY / tangentLength
            var depthX = -unitTangentY * max(baseDepth, requestedDepth)
            var depthY = unitTangentX * max(baseDepth, requestedDepth)
            if (depthAxisX != null && depthAxisY != null) {
                val axisX = interpAt(depthAxisX, cnt, cx)
                val axisY = interpAt(depthAxisY, cnt, cx)
                val available = hypot(axisX, axisY)
                if (available > 1e-4) {
                    val depthLength = min(max(baseDepth, requestedDepth), available)
                    depthX = axisX / available * depthLength
                    depthY = axisY / available * depthLength
                }
            }
            val aPk = (alpha * 255.0).roundToInt().coerceIn(0, 255)
            drawSurfaceGlint(
                canvas,
                cx,
                cy,
                unitTangentX * halfLength,
                unitTangentY * halfLength,
                depthX,
                depthY,
                core,
                hc,
                aPk
            )
        }
    }

    /** 所有层候选按分数贪心兑现且不超层容量（1/f 呼吸出生预算已随参数移除）。 */
    private fun scheduleGlitterBirths(dt: Double) {
        if (eligibleGlintLayerCount <= 0 || glitterCandidateCount <= 0) return

        java.util.Arrays.fill(glitterCandidateUsed, 0, glitterCandidateCount, false)
        while (true) {
            var best = -1
            var bestScore = MIN_GLITTER_BIRTH_SCORE
            for (candidate in 0 until glitterCandidateCount) {
                if (glitterCandidateUsed[candidate]) continue
                val layer = glitterCandidateLayer[candidate]
                if (glintTracks[layer].size >= effectiveGlintCapacity[layer]) {
                    continue
                }
                val distributedScore = glitterCandidateScore[candidate] /
                    (1.0 + 0.28 * glintTracks[layer].size)
                if (distributedScore > bestScore) {
                    best = candidate
                    bestScore = distributedScore
                }
            }
            if (best < 0) break
            glitterCandidateUsed[best] = true
            val layer = glitterCandidateLayer[best]
            val u = glitterCandidateU[best]
            val seedValue = sin(u * 12.9898 + layer * 78.233) * 43758.5453
            glintTracks[layer].add(
                Track(
                    u,
                    glitterCandidateIntensity[best] * 0.12,
                    glitterCandidateSize[best],
                    seedValue - Math.floor(seedValue),
                    glitterCandidatePathWeight[best]
                )
            )
        }
    }

    /** Canvas 回退闪点：顶边贴住水面，只向水体内部展开的实心短光迹。 */
    private fun drawSurfaceGlint(
        canvas: Canvas,
        cx: Double,
        cy: Double,
        tangentX: Double,
        tangentY: Double,
        depthX: Double,
        depthY: Double,
        core: IntArray,
        edge: IntArray,
        peakAlpha: Int
    ) {
        if (peakAlpha <= 1) return
        bandPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            1f,
            intArrayOf(
                FableSolColor.toColor(core, (peakAlpha * 0.92).roundToInt()),
                FableSolColor.toColor(core, peakAlpha),
                FableSolColor.toColor(edge, (peakAlpha * 0.28).roundToInt()),
                FableSolColor.toColor(edge, 0)
            ),
            floatArrayOf(0f, 0.24f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
        surfaceGlintTransform[Matrix.MSCALE_X] = tangentX.toFloat()
        surfaceGlintTransform[Matrix.MSKEW_X] = depthX.toFloat()
        surfaceGlintTransform[Matrix.MTRANS_X] = cx.toFloat()
        surfaceGlintTransform[Matrix.MSKEW_Y] = tangentY.toFloat()
        surfaceGlintTransform[Matrix.MSCALE_Y] = depthY.toFloat()
        surfaceGlintTransform[Matrix.MTRANS_Y] = cy.toFloat()
        surfaceGlintTransform[Matrix.MPERSP_0] = 0f
        surfaceGlintTransform[Matrix.MPERSP_1] = 0f
        surfaceGlintTransform[Matrix.MPERSP_2] = 1f
        surfaceGlintMatrix.setValues(surfaceGlintTransform)
        val save = canvas.save()
        canvas.concat(surfaceGlintMatrix)
        canvas.drawPath(unitSurfaceGlint, bandPaint)
        canvas.restoreToCount(save)
        bandPaint.shader = null
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

    /**
     * 连续面已按 196 列完成解析 Hermite 重建；Path 只连接最终投影点，不再做
     * 第二次三次拟合。与 GLES 三角网格边界同构，也避免 Catmull-Rom 的局部过冲。
     */
    private fun appendPolyline(path: Path, xs: DoubleArray, ys: DoubleArray, cnt: Int,
                               moveFirst: Boolean, reverse: Boolean = false) {
        if (cnt <= 0) return
        val first = if (reverse) cnt - 1 else 0
        if (moveFirst) path.moveTo(xs[first].toFloat(), ys[first].toFloat())
        else path.lineTo(xs[first].toFloat(), ys[first].toFloat())
        if (reverse) {
            for (index in cnt - 2 downTo 0) {
                path.lineTo(xs[index].toFloat(), ys[index].toFloat())
            }
        } else {
            for (index in 1 until cnt) {
                path.lineTo(xs[index].toFloat(), ys[index].toFloat())
            }
        }
    }

    /** 短 Hann 核消除逐采样闪烁，保留峰值位置（对应 _smooth_signal）。 */
    private fun smoothSignal(values: DoubleArray, cnt: Int, radius: Int): DoubleArray {
        if (radius <= 0) return values
        val output = scratchZero(cnt)
        FableSolMath.smoothHannInto(values, cnt, radius, output)
        return output
    }

    companion object {
        private const val TARGET_FPS = 60.0
        private const val TARGET_FRAME_SECONDS = 1.0 / TARGET_FPS
        private const val DOUBLE_SCRATCH_CAPACITY = 128
        private const val MAX_DT = 0.05f
        private const val IDLE_SILENCE_MS = 200L
        private const val FILL_EXTRA_DP = 80.0
        private const val PROJECTED_MINIMUM_SPACING_RATIO = 0.12
        private const val INTRINSIC_W_DP = 280f
        private const val INTRINSIC_H_DP = 420f

        private const val MIN_GLITTER_BIRTH_SCORE = 0.03
        private const val MAX_GLINT_ANCHORS = 4
        private const val MAX_GLITTER_CANDIDATES = FableSolSpec.N_LAYERS * MAX_GLINT_ANCHORS
        private const val GLINT_SIGMA = 0.072
        // 波峰透光的曲率尺度 (dp^-1)；波背自阴影的脊线邻近门也用它归一。
        private const val GLOW_KAPPA = 0.009
        private val WATER_F0 = ((1.333 - 1.0) / (1.333 + 1.0)).pow(2)
        private const val VIEW_ELEVATION_DEG = 38.0
        private val WHITE = intArrayOf(255, 255, 255)
        private val GL_FALLBACK_RED = intArrayOf(255, 0, 0)
        private val POS4 = floatArrayOf(0f, 0.24f, 0.60f, 1f)
        private val KER3 = doubleArrayOf(0.25, 0.5, 0.25)
        // fade 子带剖面（上沿软入→中段聚光→下缘收出），见 drawOneSidedBand 注释
        private val SUB_OFF = doubleArrayOf(0.00, 0.10, 0.24)
        private val SUB_FRAC = doubleArrayOf(1.00, 0.62, 0.42)
        private val SUB_AA = doubleArrayOf(0.14, 0.34, 0.24)
    }
}

/**
 * Canvas 投影 X 的整行单调修复。独立成纯数学策略，既便于 JVM 回归，也保证
 * 97 条深度行全部走同一公式；调用方复用既有数组，函数本身不分配。
 */
internal object FableSolCanvasProjection {
    fun repairMonotoneInPlace(
        projectedX: DoubleArray,
        sourceUDp: DoubleArray,
        count: Int,
        baselinePerspective: Double,
        density: Double,
        minimumSpacingRatio: Double
    ): Double {
        require(count in 0..min(projectedX.size, sourceUDp.size))
        if (count < 2) return 1.0

        var scale = 1.0
        var previousBaseline = sourceUDp[0] * density * baselinePerspective
        var previousRaw = projectedX[0]
        for (column in 1 until count) {
            val baseline = sourceUDp[column] * density * baselinePerspective
            val raw = projectedX[column]
            scale = min(
                scale,
                FableSolCubicResampler.monotoneBlendBound(
                    raw - previousRaw,
                    baseline - previousBaseline,
                    minimumSpacingRatio
                )
            )
            previousBaseline = baseline
            previousRaw = raw
        }
        if (scale < 1.0) {
            for (column in 0 until count) {
                val baseline = sourceUDp[column] * density * baselinePerspective
                projectedX[column] = baseline + scale * (projectedX[column] - baseline)
            }
        }
        return scale
    }
}
