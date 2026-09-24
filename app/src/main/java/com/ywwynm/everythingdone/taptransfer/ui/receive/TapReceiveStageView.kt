package com.ywwynm.everythingdone.taptransfer.ui.receive

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import com.ywwynm.everythingdone.taptransfer.ui.particles.FlowNoise
import com.ywwynm.everythingdone.taptransfer.ui.particles.ParticleSpriteBatch
import com.ywwynm.everythingdone.taptransfer.ui.particles.PerimeterParticles
import com.ywwynm.everythingdone.taptransfer.ui.particles.StageClock
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * 接收状态下盖在首页上的一层：页面底色的罩层，加一圈沿屏幕四边流动的粒子边框，表示正在等待
 * 被碰。
 *
 * 碰上之后边框换成收到的记事的颜色、继续流动，直到数据到齐；收到的卡片由粒子层直接在列表的
 * 空位里拼出来（`TapParticleFlight.Inflow`），颗粒一部分从屏幕四边流出——那一刻边框随之淡去
 * （[converge]）。卡片拼好之后罩层在它们的位置上留出空洞（[highlight]），刚收到的卡片保持
 * 原样的亮度，直到下一台碰上或退出接收。
 *
 * 除了按钮所在的区域，本视图吞掉所有触摸，列表在接收状态下完全不动（决策 2026-09-24「接收
 * 状态下首页列表完全不动」）。按钮叠在本视图之上，本身不需要让位。
 */
@SuppressLint("ViewConstructor")
class TapReceiveStageView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        /** 退场淡出播完。带上舞台本身：淡出期间可能已经有新的一次接收、新的舞台。 */
        fun onExitFinished(stage: TapReceiveStageView)
    }

    private enum class Phase { IDLE, WAITING, BUSY, EXITING }

    private val density = resources.displayMetrics.density
    private val clock = StageClock()
    private val random = Random(SEED.toLong())
    private val batch = ParticleSpriteBatch()
    private val border = PerimeterParticles(BORDER_CAPACITY, FlowNoise(SEED))

    private var phase = Phase.IDLE
    private var borderAlpha = 0f
    private var borderTarget = 0f
    private var borderFadeS = BORDER_FADE_S
    private var dimAlpha = 0f
    private var dimTarget = 0f
    private var screenCornerRadius = 24f * density

    /** 边框的调色板：从 [paletteFrom] 过渡到 [paletteTo]，[paletteT] 为过渡进度。 */
    private var paletteFrom = DEFAULT_PALETTE
    private var paletteTo = DEFAULT_PALETTE
    private var paletteT = 1f

    /** 罩层上的空洞（刚收到的卡片），[holeOpen] 为 1 时完全透出，0 时与罩层一样深。 */
    private val holes = ArrayList<RectF>()
    private var holeRadius = 0f
    private var holeOpen = 0f
    private var holeTarget = 0f
    private val dimPath = Path()
    private var dimPathDirty = true

    private val dimPaint = Paint()
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowRect = RectF()
    private val glowMatrix = Matrix()
    private var glowShader: SweepGradient? = null

    /** 盖在列表上的一层：用页面的底色而不是黑色，列表退到后面，但不会发灰发脏。 */
    var dimColor: Int = Color.WHITE
    var dimMaxAlpha: Float = 0.5f

    init {
        glowPaint.style = Paint.Style.STROKE
        border.palette = DEFAULT_PALETTE
        border.paletteCycleS = 22f
        dimPath.fillType = Path.FillType.EVEN_ODD
    }

    fun setScreenCornerRadius(px: Float) {
        screenCornerRadius = px
    }

    /** 边框粒子流动的那条线（本视图坐标）：接收的颗粒从这里流出。 */
    fun ringFrame(out: RectF) {
        val inset = (BORDER_INSET_DP + BORDER_LANE_DP / 2f) * density
        out.set(inset, inset, width - inset, height - inset)
    }

    // ---------------------------------------------------------------- 动作

    fun enter() {
        clock.tick()
        populateBorder(clock.now)
        borderTarget = 1f
        borderFadeS = BORDER_FADE_S
        dimTarget = dimMaxAlpha
        setPhase(Phase.WAITING)
    }

    /**
     * 碰上了：边框换成收到的记事的颜色（[colors] 为空时保持原色），继续流动，等数据到齐。
     * 上一批留下的空洞随之合上。
     */
    fun touched(colors: IntArray) {
        clearHighlights()
        // 取不到背景色的条目发来的是 0，不参与换色
        val usable = colors.filter { it != 0 }
        if (usable.isNotEmpty()) {
            fadePaletteTo(spread(usable))
        }
        borderTarget = 1f
        borderFadeS = BORDER_FADE_S
        setPhase(Phase.BUSY)
    }

    /** 颗粒开始从屏幕四边流向卡片：边框随之淡去。 */
    fun converge() {
        borderTarget = 0f
        borderFadeS = CONVERGE_FADE_S
    }

    /** 这一台收完（或失败）了：边框换回原色、重新出现，继续等下一台。 */
    fun resumeWaiting() {
        if (phase != Phase.BUSY) {
            return
        }
        clock.tick()
        if (borderAlpha <= .01f) {
            populateBorder(clock.now)
        }
        fadePaletteTo(DEFAULT_PALETTE)
        borderTarget = 1f
        borderFadeS = BORDER_FADE_S
        setPhase(Phase.WAITING)
    }

    /**
     * 在罩层上为刚拼好的卡片留出空洞（本视图坐标）。与卡片显示出来在同一条消息里调用：
     * 粒子层的末帧是没有罩层的卡片，交接那一帧前后一样亮。
     */
    fun highlight(rects: List<RectF>, cornerRadius: Float) {
        holes.clear()
        rects.mapTo(holes) { RectF(it) }
        holeRadius = cornerRadius
        holeOpen = 1f
        holeTarget = 1f
        dimPathDirty = true
        postInvalidateOnAnimation()
    }

    /** 空洞慢慢合上：刚收到的卡片与列表里其余的卡片一起退到罩层后面。 */
    fun clearHighlights() {
        holeTarget = 0f
        postInvalidateOnAnimation()
    }

    fun exit() {
        clock.tick()
        borderTarget = 0f
        borderFadeS = BORDER_FADE_S
        dimTarget = 0f
        setPhase(Phase.EXITING)
    }

    /** 接收状态下旋转屏幕：边框跟着新的屏幕尺寸走，粒子按周长比例留在原来的位置附近。 */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dimPathDirty = true
        if (phase == Phase.IDLE || oldw == 0 || oldh == 0) {
            return
        }
        // 空洞对应的卡片已经不在原来的位置
        holes.clear()
        holeOpen = 0f
        holeTarget = 0f
        insetBorder()
        border.angularPaletteCenter = PointF(w / 2f, h / 2f)
        glowShader = null
    }

    /** 接收状态下列表完全不动：触摸一律吞掉。按钮叠在舞台之上，先拿到自己的触摸。 */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return phase != Phase.IDLE
    }

    // ---------------------------------------------------------------- 绘制

    private fun fadePaletteTo(target: IntArray) {
        paletteFrom = currentPalette()
        paletteTo = target
        paletteT = 0f
    }

    private fun currentPalette(): IntArray {
        if (paletteT >= 1f) {
            return paletteTo
        }
        return IntArray(PALETTE_SIZE) { PerimeterParticles.lerpColor(paletteFrom[it], paletteTo[it], paletteT) }
    }

    private fun insetBorder() {
        val inset = BORDER_INSET_DP * density
        border.shape.set(inset, inset, width - inset, height - inset, (screenCornerRadius - inset).coerceAtLeast(8f * density))
    }

    private fun populateBorder(now: Float) {
        insetBorder()
        // 与底下那圈光同一口径取色：按绕屏幕中心的角度
        border.angularPaletteCenter = PointF(width / 2f, height / 2f)
        val n = (border.shape.length / (BORDER_SPACING_DP * density)).toInt().coerceIn(200, BORDER_CAPACITY)
        border.populate(
            n,
            laneWidth = BORDER_LANE_DP * density,
            speedPx = BORDER_SPEED_DP * density,
            minRadius = 1.0f * density,
            maxRadius = 2.6f * density,
            random = random,
            nowS = now
        )
    }

    private fun setPhase(next: Phase) {
        phase = next
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (phase == Phase.IDLE) {
            return
        }
        clock.tick()
        val now = clock.now
        val dt = clock.dt
        borderAlpha = approach(borderAlpha, borderTarget, dt / borderFadeS)
        dimAlpha = approach(dimAlpha, dimTarget, dt / DIM_FADE_S)
        holeOpen = approach(holeOpen, holeTarget, dt / HOLE_CLOSE_S)
        if (paletteT < 1f) {
            paletteT = min(1f, paletteT + dt / PALETTE_FADE_S)
            border.palette = currentPalette()
            glowShader = null
        }
        if (phase == Phase.EXITING && borderAlpha <= .01f && dimAlpha <= .005f) {
            border.clear()
            phase = Phase.IDLE
            post { listener.onExitFinished(this) }
            return
        }

        if (dimAlpha > .001f) {
            drawDim(canvas)
        }
        if (borderAlpha > .01f && border.count > 0) {
            border.alpha = borderAlpha
            border.update(dt, now, BORDER_WOBBLE_DP * density, 1f / (140f * density))
            drawEdgeGlow(canvas, now)
            batch.begin(canvas)
            border.draw(batch, now, clock.trailScale(BORDER_TRAIL_S), fadeInS = .8f)
            batch.end()
        }
        // 罩层与边框在淡入淡出、边框在流动：接收状态期间一直刷新
        postInvalidateOnAnimation()
    }

    /** 罩层；有空洞时空洞处不盖，空洞合上的过程中再按 1 - [holeOpen] 补上。 */
    private fun drawDim(canvas: Canvas) {
        val alpha = (dimAlpha * 255f).toInt()
        dimPaint.color = dimColor
        dimPaint.alpha = alpha
        if (holes.isEmpty() || holeOpen <= .001f) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            if (holeTarget <= 0f) {
                holes.clear()
            }
            return
        }
        if (dimPathDirty) {
            dimPath.reset()
            dimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            for (hole in holes) {
                dimPath.addRoundRect(hole, holeRadius, holeRadius, Path.Direction.CW)
            }
            dimPathDirty = false
        }
        canvas.drawPath(dimPath, dimPaint)
        if (holeOpen < 1f) {
            holePaint.color = dimColor
            holePaint.alpha = (alpha * (1f - holeOpen)).toInt()
            for (hole in holes) {
                canvas.drawRoundRect(hole, holeRadius, holeRadius, holePaint)
            }
        }
    }

    /**
     * 粒子边框底下的一圈光：颜色沿屏幕四周与粒子同一套调色板，并随时间转动，粒子在光带里流，
     * 边框读起来是连成一圈的，而不是散落在四边的点。
     */
    private fun drawEdgeGlow(canvas: Canvas, now: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val shader = glowShader ?: SweepGradient(cx, cy, border.palette + border.palette[0], null)
            .also { glowShader = it }
        glowMatrix.setRotate(now * 360f / border.paletteCycleS, cx, cy)
        shader.setLocalMatrix(glowMatrix)
        glowPaint.shader = shader
        // 由外向内一圈比一圈淡，叠出一道没有硬边的光
        val ring = GLOW_RING_DP * density
        glowPaint.strokeWidth = ring
        for (k in 0 until GLOW_RINGS) {
            val inset = ring * (k + .5f)
            val falloff = 1f - k / GLOW_RINGS.toFloat()
            glowPaint.alpha = (GLOW_ALPHA * falloff * falloff * borderAlpha * 255f).toInt()
            glowRect.set(inset, inset, width - inset, height - inset)
            val r = (screenCornerRadius - inset).coerceAtLeast(0f)
            canvas.drawRoundRect(glowRect, r, r, glowPaint)
        }
    }

    companion object {
        private const val SEED = 20260925

        private const val BORDER_CAPACITY = 2000
        private const val BORDER_INSET_DP = 3f
        private const val BORDER_SPACING_DP = 1.7f
        private const val BORDER_LANE_DP = 14f
        private const val BORDER_SPEED_DP = 80f
        private const val BORDER_WOBBLE_DP = 3.5f
        private const val BORDER_TRAIL_S = .2f
        private const val BORDER_FADE_S = .6f
        private const val DIM_FADE_S = .35f

        /** 颗粒从四边流出时边框淡去的时长：与颗粒离开边缘的那一段对上。 */
        private const val CONVERGE_FADE_S = .35f

        /** 边框换色的时长。 */
        private const val PALETTE_FADE_S = .5f

        /** 刚收到的卡片退到罩层后面的时长。 */
        private const val HOLE_CLOSE_S = .45f

        /** 边框底下那层光：GLOW_RINGS 圈、每圈 GLOW_RING_DP 宽，透明度由外向内按平方衰减。 */
        private const val GLOW_RINGS = 20
        private const val GLOW_RING_DP = 1.2f
        private const val GLOW_ALPHA = .3f

        private const val PALETTE_SIZE = 6

        /** 与应用的强调色、记事配色同源：珊瑚红、琥珀、松绿、青、深蓝、紫。 */
        private val DEFAULT_PALETTE = intArrayOf(
            0xFFF66048.toInt(), 0xFFFFAE36.toInt(), 0xFF21A675.toInt(),
            0xFF00B1C6.toInt(), 0xFF166096.toInt(), 0xFF665696.toInt()
        )

        /** 把收到的记事的颜色铺成与默认调色板同样的长度，换色时逐项过渡。 */
        private fun spread(colors: List<Int>): IntArray {
            val opaque = colors.map { it or 0xFF000000.toInt() }
            return IntArray(PALETTE_SIZE) { opaque[it % opaque.size] }
        }

        private fun approach(value: Float, target: Float, step: Float): Float {
            return if (value < target) min(target, value + step) else max(target, value - step)
        }
    }
}
