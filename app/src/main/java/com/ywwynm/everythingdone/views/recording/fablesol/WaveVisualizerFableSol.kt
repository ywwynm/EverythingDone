package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

import com.ywwynm.everythingdone.model.ThingBackground

import kotlin.math.abs
import kotlin.math.atan2
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

    private var mLastFrameTime = 0L
    private var mAnimating = false
    private var mLastAudioElapsed = 0L
    private var mGravitySeeded = false

    // 渲染 scratch
    private val xsPx = DoubleArray(FableSolSpec.N_POINTS)
    private val ysPx = DoubleArray(FableSolSpec.N_POINTS)

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

    /** 重力方向（屏幕平面）→ 容器倾角。符号可能需真机校准（见 README）。 */
    fun setContainerGravity(x: Float, y: Float, @Suppress("UNUSED_PARAMETER") z: Float) {
        val deg = Math.toDegrees(atan2(x.toDouble(), y.toDouble()))
        if (!mGravitySeeded) { sim.setTilt(deg, snap = true); mGravitySeeded = true }
        else sim.setTilt(deg, snap = false)
        ensureAnimating()
    }

    // ------------------------------------------------------------------ 音频接收（采集线程）
    override fun onAudioFrames(frames: List<FableSolFeatureFrame>, events: List<FableSolEvent>) {
        if (frames.isEmpty() && events.isEmpty()) return
        synchronized(lock) {
            pendingFrames.addAll(frames)
            pendingEvents.addAll(events)
        }
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ 帧循环
    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        var dt = if (mLastFrameTime == 0L) 0.016f else (now - mLastFrameTime) / 1000f
        mLastFrameTime = now
        if (dt <= 0f) dt = 0.016f
        if (dt > MAX_DT) dt = MAX_DT

        drainAndApply(now)
        sim.update(dt.toDouble())
        drawWater(canvas)

        if (shouldAnimate()) postInvalidateOnAnimation() else mAnimating = false
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
        }
    }

    private fun ensureAnimating() {
        if (!mAnimating && shouldAnimate()) {
            mAnimating = true
            mLastFrameTime = 0L
            postInvalidateOnAnimation()
        }
    }

    private fun shouldAnimate(): Boolean = isAttachedToWindow && width > 0 && height > 0

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ensureAnimating() }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mGravitySeeded = false
    }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); ensureAnimating() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); if (isVisible) ensureAnimating() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && density > 0.0) {
            // 必须使用布局系统最终测得的 View 宽度；XML 280dp/TimelyClockView 只参与上游测量。
            sim.setContainerWidthDp(w / density)
        }
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
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        drawEnvironment(canvas, w, h)

        val info = sim.renderInfo()
        val i0 = info.i0; val i1 = info.i1
        val cnt = i1 - i0
        if (cnt < 2) return
        val hG = info.hG
        for (j in 0 until cnt) xsPx[j] = sim.uGrid[i0 + j] * density
        val fillBottom = hG / 2.0 * density + FILL_EXTRA_DP * density

        val save = canvas.save()
        canvas.translate(w / 2f, h / 2f)
        canvas.rotate(-Math.toDegrees(info.thetaRad).toFloat())
        // 远→近叠加（层 N-1 最远先画，层 0 最近后画）
        for (i in FableSolSpec.N_LAYERS - 1 downTo 0) {
            val row = sim.heights[i]
            for (j in 0 until cnt) ysPx[j] = (hG / 2.0 - row[i0 + j]) * density
            drawLayer(canvas, i, cnt, fillBottom, i0)
        }
        canvas.restoreToCount(save)
    }

    private fun drawEnvironment(canvas: Canvas, w: Float, h: Float) {
        val strength = params.get("environment_tint")
        val top = FableSolColor.mixOklab(WHITE, FableSolColor.mixOklab(c2Base, WHITE, 0.72), strength * 0.55)
        val horizon = FableSolColor.mixOklab(WHITE, FableSolColor.mixOklab(c1Base, WHITE, 0.78), strength)
        val bottom = FableSolColor.mixOklab(WHITE, FableSolColor.mixOklab(c2Base, WHITE, 0.84), strength * 0.42)
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(FableSolColor.toColor(top, 255), FableSolColor.toColor(horizon, 255), FableSolColor.toColor(bottom, 255)),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        fillPaint.shader = null
    }

    private fun drawLayer(canvas: Canvas, i: Int, cnt: Int, fillBottom: Double, i0: Int) {
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

        fillPaint.shader = layerShader(c1, c2, a255, cnt, fillBottom)
        canvas.drawPath(fillPath, fillPaint)
        fillPaint.shader = null

        if (params.get("crest_on") >= 0.5) drawHighlights(canvas, i, cnt, c1, c2, a255, i0, depth01)
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
                              i0: Int, depth01: Double) {
        val ls = sim.layers[i]
        val ys = DoubleArray(cnt) { ysPx[it] }
        val dxPx = xsPx[1] - xsPx[0]
        val ker = KER3
        val gradY = FableSolMath.gradient(ys, dxPx)
        val slopeRaw = DoubleArray(cnt) { -gradY[it] }
        val slope = FableSolMath.convolveSame(slopeRaw, ker)
        val gradGrad = FableSolMath.gradient(gradY, dxPx)
        val curvRaw = DoubleArray(cnt) { -gradGrad[it] * density }
        val curv = FableSolMath.convolveSame(curvRaw, ker)
        val cap = ls.capillary01 * params.get("capillary_glint_gain")
        val rough = ls.roughness01
        val uDp = DoubleArray(cnt) { xsPx[it] / density }
        val micro: DoubleArray; val microCurv: DoubleArray
        if (i <= 4) {
            val (mSlope, mCurv) = ls.optical.sample(uDp, cap, rough)
            micro = mSlope; microCurv = mCurv
        } else { micro = DoubleArray(cnt); microCurv = DoubleArray(cnt) }
        val opticalSlope = DoubleArray(cnt) { slope[it] + micro[it] }
        val s0 = tan(Math.toRadians(params.get("light_azimuth_deg")) / 2.0)
        val sigma = GLINT_SIGMA * (1.0 + 0.42 * rough)
        val sinElev = sin(Math.toRadians(VIEW_ELEVATION_DEG))
        val flatFres = WATER_F0 + (1.0 - WATER_F0) * (1.0 - sinElev).pow(5)
        val glintDepth = max(0.0, 1.0 - depth01 / 0.42)
        val glintStrength = params.get("crest_glint_strength")
        val skyStrength = params.get("sky_reflection_strength")
        val glowStrength = params.get("crest_glow_strength")
        val bodyStrength = params.get("body_light_strength")
        val fres = DoubleArray(cnt)
        val edge = DoubleArray(cnt)
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
        val edgeS = smoothSignal(edge, 3)
        for (j in 0 until cnt) if (edgeS[j] < 0.015) edgeS[j] = 0.0
        val crestLight0 = DoubleArray(cnt) { (curv[it] / (-GLOW_KAPPA)).coerceIn(0.0, 1.0) * glowStrength }
        val crestLight = FableSolMath.convolveSame(crestLight0, FULL5)
        val volume = DoubleArray(cnt) { ((0.16 + 0.84 * crestLight[it]) * (1.0 - fres[it]) * bodyStrength).coerceIn(0.0, 1.0) }

        val pearlPhase = sin(2.0 * Math.PI * sim.t / 12.0 + i * 0.21)
        val pearlDeg = params.get("pearl_shift_deg") * pearlPhase
        var hc = FableSolColor.mixOklab(FableSolColor.mix(c1, c2, 0.3), WHITE, params.get("crest_lighten"))
        hc = FableSolColor.shiftHue(hc, pearlDeg)
        val bodyColor = FableSolColor.mixOklab(c1, hc, 0.46)
        val a01 = a255 / 255.0

        if (bodyStrength > 1e-3) {
            val dPx = params.get("crest_glow_depth_dp") * density
            val topArr = DoubleArray(cnt) { ys[it] + 0.35 * density }
            val thickness = DoubleArray(cnt) { dPx * (0.34 + 0.66 * volume[it]) }
            drawOneSidedBand(canvas, cnt, topArr, thickness, bodyColor,
                (72 * a01 * bodyStrength).toInt(), true)
        }
        if (glintStrength > 1e-3) {
            var mx = 0.0; for (v in edgeS) if (v > mx) mx = v
            if (mx > 1e-3) {
                val amt = DoubleArray(cnt) { edgeS[it] * a01 }
                drawVariableBand(canvas, cnt, ys, amt, hc, params.get("crest_width_dp") * density, (188 * a01).toInt())
            }
        }
        val veilStrength = params.get("crest_veil_strength")
        if (veilStrength > 1e-3 && i <= 2) {
            val veilRaw = DoubleArray(cnt) { ls.crestVeil[i0 + it] }
            val veil = smoothSignal(veilRaw, 4)
            var mx = 0.0
            for (j in 0 until cnt) { veil[j] *= veilStrength; if (veil[j] > mx) mx = veil[j] }
            if (mx > 1e-3) {
                val veilColor = FableSolColor.mixOklab(hc, WHITE, 0.32)
                val ysV = DoubleArray(cnt) { ys[it] - 0.20 * density }
                val amt = DoubleArray(cnt) { veil[it] * a01 }
                drawVariableBand(canvas, cnt, ysV, amt, veilColor, 3.2 * density, (96 * a01 * veilStrength).toInt())
            }
        }
    }

    /** 连续变宽的镜面光斑（对应 _draw_variable_band）：用填充几何承载强度。 */
    private fun drawVariableBand(canvas: Canvas, cnt: Int, ys: DoubleArray, amountIn: DoubleArray,
                                rgb: IntArray, maxWidthPx: Double, alpha: Int) {
        val amount = DoubleArray(cnt) { amountIn[it].coerceIn(0.0, 1.0).pow(0.72) }
        if (cnt >= 2) { amount[0] = 0.0; amount[1] = 0.0; amount[cnt - 1] = 0.0; amount[cnt - 2] = 0.0 }
        val dxg = FableSolMath.gradient(sliceX(cnt), 1.0)
        val dyg = FableSolMath.gradient(ys, 1.0)
        val upperX = DoubleArray(cnt); val upperY = DoubleArray(cnt)
        val lowerX = DoubleArray(cnt); val lowerY = DoubleArray(cnt)
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

    /** 表面到水下的连续透光体（对应 _draw_one_sided_band）。 */
    private fun drawOneSidedBand(canvas: Canvas, cnt: Int, top: DoubleArray, thicknessIn: DoubleArray,
                                rgb: IntArray, alpha: Int, fade: Boolean) {
        val thickness = DoubleArray(cnt) { max(thicknessIn[it], 0.0) }
        if (cnt >= 2) { thickness[0] = 0.0; thickness[1] = 0.0; thickness[cnt - 1] = 0.0; thickness[cnt - 2] = 0.0 }
        val bottom = DoubleArray(cnt) { top[it] + thickness[it] }
        bandPath.reset()
        buildSmooth(bandPath, sliceX(cnt), top, cnt, true)
        buildSmooth(bandPath, reverse(sliceX(cnt), cnt), reverse(bottom, cnt), cnt, false)
        bandPath.close()
        if (fade) {
            val y0 = FableSolMath.percentile(top, 45.0)
            val y1 = max(y0 + 1.0, FableSolMath.percentile(bottom, 70.0))
            fillPaint.shader = LinearGradient(0f, y0.toFloat(), 0f, y1.toFloat(),
                intArrayOf(
                    FableSolColor.toColor(rgb, 0),
                    FableSolColor.toColor(rgb, (alpha * 0.72).toInt().coerceIn(0, 255)),
                    FableSolColor.toColor(rgb, (alpha * 0.36).toInt().coerceIn(0, 255)),
                    FableSolColor.toColor(rgb, 0)
                ), floatArrayOf(0f, 0.24f, 0.58f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(bandPath, fillPaint)
            fillPaint.shader = null
        } else {
            bandPaint.color = FableSolColor.toColor(rgb, alpha.coerceIn(0, 255))
            canvas.drawPath(bandPath, bandPaint)
        }
    }

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
    private fun smoothSignal(values: DoubleArray, radius: Int): DoubleArray {
        if (radius <= 0) return values
        val han = FableSolMath.hanning(radius * 2 + 3)
        val kernel = DoubleArray(radius * 2 + 1) { han[it + 1] }
        var ksum = 0.0; for (v in kernel) ksum += v
        for (j in kernel.indices) kernel[j] /= ksum
        val padded = FableSolMath.padEdge(values, radius)
        return FableSolMath.convolveValid(padded, kernel)
    }

    private fun sliceX(cnt: Int): DoubleArray = DoubleArray(cnt) { xsPx[it] }
    private fun reverse(a: DoubleArray, cnt: Int): DoubleArray = DoubleArray(cnt) { a[cnt - 1 - it] }

    companion object {
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
        private val POS4 = floatArrayOf(0f, 0.24f, 0.60f, 1f)
        private val KER3 = doubleArrayOf(0.25, 0.5, 0.25)
        private val FULL5 = doubleArrayOf(0.2, 0.2, 0.2, 0.2, 0.2)
    }
}
