package com.ywwynm.everythingdone.taptransfer.ui.receive

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.widget.FrameLayout
import com.ywwynm.everythingdone.taptransfer.ui.flight.TapParticleParams
import com.ywwynm.everythingdone.taptransfer.ui.flight.TapSessionRecorder
import com.ywwynm.everythingdone.taptransfer.ui.particles.StageClock
import com.ywwynm.everythingdone.taptransfer.ui.particles.TapDustBand
import com.ywwynm.everythingdone.taptransfer.ui.particles.TapDustLayer
import org.json.JSONArray
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 接收状态下盖在首页上的一层：页面底色的罩层，加屏幕四周一圈光尘（第三版，[TapDustBand]）与带底下的
 * 柔光，表示正在等待被碰。
 *
 * 碰上之后光尘换成收到的记事的颜色、随传输进度向内扩散，天线处出现扩散圈（颗粒从那里冒出）；数据到齐之后
 * 列表让位，光尘继续缓慢扩散。拼卡那一层开始流入时（[inflow]）一部分光尘被颗粒接手（照常运动到交接那一刻、
 * 随后不再画），其余向外退去、淡去；扩散圈边收缩边淡去。卡片拼好之后罩层在它们的位置上留出空洞
 * （[highlight]），刚收到的卡片保持原样的亮度，直到下一台碰上或退出接收。
 *
 * 罩层与空洞在本视图的 [onDraw] 里画；光尘、柔光与扩散圈在 GPU 上画（[TapDustLayer]，叠在罩层之上的子视图），
 * 本视图每帧把状态交给它。
 *
 * 除了按钮所在的区域，本视图吞掉所有触摸，列表在接收状态下完全不动（决策 2026-09-24「接收
 * 状态下首页列表完全不动」）。按钮叠在本视图之上，本身不需要让位。
 */
@SuppressLint("ViewConstructor")
class TapReceiveStageView(context: Context, private val listener: Listener) : FrameLayout(context) {

    interface Listener {
        /** 退场淡出播完。带上舞台本身：淡出期间可能已经有新的一次接收、新的舞台。 */
        fun onExitFinished(stage: TapReceiveStageView)
    }

    private enum class Phase { IDLE, WAITING, BUSY, EXITING }

    private val density = resources.displayMetrics.density
    private val params = TapParticleParams.get(context)
    private val clock = StageClock()

    private var phase = Phase.IDLE

    /** 整条光尘带的不透明度：出现、退场，以及拼好之后重新出现。 */
    private var borderAlpha = 0f
    private var borderTarget = 0f
    private var borderFadeS = params.borderFadeS
    private var dimAlpha = 0f
    private var dimTarget = 0f
    private var screenCornerRadius = 24f * density

    /** 调色板：从 [paletteFrom] 过渡到 [paletteTo]，[paletteT] 为过渡进度。 */
    private var paletteFrom = params.borderPalette
    private var paletteTo = params.borderPalette
    private var paletteT = 1f
    private var palette = params.borderPalette

    /** 罩层上的空洞（刚收到的卡片），[holeOpen] 为 1 时完全透出，0 时与罩层一样深。 */
    private val holes = ArrayList<RectF>()
    private var holeRadius = 0f
    private var holeOpen = 0f
    private var holeTarget = 0f
    private val dimPath = Path()
    private var dimPathDirty = true
    private val dimPaint = Paint()
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- 光尘带（GPU 上画）
    private var dust: TapDustBand? = null
    private val dustLayer = TapDustLayer(context)

    /** 扩散程度与扩散开始的舞台时刻（碰上）；由控制器逐帧给出扩散程度。 */
    private var spread = 0f
    private var touchedAt = Float.NaN

    /** 拼卡那一层开始流入的舞台时刻：此后没被接手的光尘外退、扩散圈收起。 */
    private var inflowAt = Float.NaN
    private var exitAt = Float.NaN

    /** 倒放走到哪儿：被拼卡那一层接手的光尘过了各自的交接位置就不再画（[setHandoff]）。 */
    private var tau = 0f

    /** 恢复等待时带还看得见（接收失败）：先淡出，淡完再回到等待的样子。 */
    private var resetPending = false

    // ---- 天线处的扩散圈
    private var antennaX = Float.NaN
    private var antennaY = Float.NaN

    /** 盖在列表上的一层：用页面的底色而不是黑色，列表退到后面，但不会发灰发脏。 */
    var dimColor: Int = Color.WHITE
    var dimMaxAlpha: Float = 0.5f

    init {
        setWillNotDraw(false)
        dimPath.fillType = Path.FillType.EVEN_ODD
        addView(dustLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setScreenCornerRadius(px: Float) {
        screenCornerRadius = px
    }

    /** 本机天线在本视图里的位置：扩散圈画在这里。 */
    fun setAntenna(x: Float, y: Float) {
        antennaX = x
        antennaY = y
    }

    /** 屏幕四周颗粒的终点所在的那条线（本视图坐标）：流向四周的颗粒落在它向里的一条带里。 */
    fun ringFrame(out: RectF) {
        val inset = (params.borderInsetDp + params.borderLaneDp / 2f) * density
        out.set(inset, inset, width - inset, height - inset)
    }

    /** 光尘带（尺寸定下之后才有）。光尘接手要按它求光尘在交接时刻的位置。 */
    internal val dustBand: TapDustBand? get() = dust

    /** 舞台时钟的起点（`AnimationUtils` 的毫秒）：光尘按这个时钟求值。 */
    val clockOriginMs: Long get() = clock.originMs

    /** 碰上时（舞台时钟）。 */
    val touchedAtS: Float get() = touchedAt

    /** 当前的调色板目标（碰上之后是收到的记事的颜色）。 */
    val targetPalette: IntArray get() = paletteTo

    // ---------------------------------------------------------------- 动作

    fun enter() {
        TapSessionRecorder.current?.event("stage-enter") {
            val at = IntArray(2).also { getLocationInWindow(it) }
            val decorAt = IntArray(2).also { rootView.getLocationInWindow(it) }
            put("rect", JSONArray().put(at[0] - decorAt[0]).put(at[1] - decorAt[1]).put(width).put(height))
            put("corner", screenCornerRadius.toDouble())
            put("dim_color", String.format("%08X", dimColor))
            put("dim_alpha", dimMaxAlpha.toDouble())
        }
        clock.tick()
        buildDust()
        borderTarget = 1f
        borderFadeS = params.borderFadeS
        dimTarget = dimMaxAlpha
        setPhase(Phase.WAITING)
    }

    /**
     * 碰上了：光尘换成收到的记事的颜色（[colors] 为空时保持原色），开始向内扩散；天线处出现扩散圈。
     * 上一批留下的空洞随之合上。握手里的卡片形状一并记下（会话记录，模拟器据此预计落点）。
     */
    fun touched(colors: IntArray, aspects: FloatArray, fullSpan: BooleanArray) {
        TapSessionRecorder.current?.event("stage-touched") {
            put("colors", JSONArray().also { list -> colors.forEach { list.put(String.format("%08X", it)) } })
            if (aspects.isNotEmpty()) {
                put("aspects", JSONArray().also { list -> aspects.forEach { list.put(it.toDouble()) } })
                put("spans", JSONArray().also { list -> fullSpan.forEach { list.put(if (it) "full" else "normal") } })
            }
        }
        clearHighlights()
        clock.tick()
        resetPending = false
        // 取不到背景色的条目发来的是 0，不参与换色
        val usable = colors.filter { it != 0 }
        if (usable.isNotEmpty()) {
            fadePaletteTo(spread(usable, params.borderPalette.size))
        }
        touchedAt = clock.now
        inflowAt = Float.NaN
        exitAt = Float.NaN
        spread = 0f
        tau = 0f
        dustLayer.setHideTau(null)
        borderTarget = 1f
        borderFadeS = params.borderFadeS
        setPhase(Phase.BUSY)
    }

    /** 每帧：光尘的扩散程度（`TapReceiveTimeline.spread`）与倒放走到哪儿（交接的光尘据此隐去）。 */
    fun update(spread: Float, tau: Float) {
        if (phase != Phase.BUSY) return
        this.spread = spread
        this.tau = tau
    }

    /** 拼卡那一层交出的光尘接手：被接手的那几片各自隐去时的倒放位置（其余正无穷）。 */
    fun setHandoff(hideTau: FloatArray?) {
        dustLayer.setHideTau(hideTau)
    }

    /**
     * 拼卡那一层开始流入：被接手的光尘照常运动到交接那一刻，其余向外退去、淡去；扩散圈边收缩边淡去。
     * 整条带的不透明度同时收到 0（拼好之后从 0 重新出现）。
     */
    fun inflow() {
        TapSessionRecorder.current?.event("stage-converge")
        clock.tick()
        inflowAt = clock.now
        borderTarget = 0f
        borderFadeS = params.borderConvergeS
    }

    /** 这一台收完（或失败）了：光尘换回原色、回到等待的样子，继续等下一台。 */
    fun resumeWaiting() {
        if (phase != Phase.BUSY) {
            return
        }
        TapSessionRecorder.current?.event("stage-resume")
        clock.tick()
        if (borderAlpha > .01f && inflowAt.isNaN()) {
            // 没拼成就结束了（接收失败）：带还看得见，扩散的位置不能一下跳回去，先淡出
            resetPending = true
            borderTarget = 0f
            borderFadeS = params.borderConvergeS
        } else {
            resetToWaiting()
        }
        setPhase(Phase.WAITING)
    }

    private fun resetToWaiting() {
        resetPending = false
        touchedAt = Float.NaN
        inflowAt = Float.NaN
        spread = 0f
        tau = 0f
        dustLayer.setHideTau(null)
        fadePaletteTo(params.borderPalette)
        borderTarget = 1f
        borderFadeS = params.borderFadeS
    }

    /**
     * 在罩层上为刚拼好的卡片留出空洞（本视图坐标）。与卡片显示出来在同一条消息里调用：
     * 粒子层的末帧是没有罩层的卡片，交接那一帧前后一样亮。
     */
    fun highlight(rects: List<RectF>, cornerRadius: Float) {
        TapSessionRecorder.current?.event("stage-highlight") {
            put("rects", JSONArray().also { list ->
                rects.forEach { list.put(JSONArray().put(it.left.toDouble()).put(it.top.toDouble())
                    .put(it.right.toDouble()).put(it.bottom.toDouble())) }
            })
            put("radius", cornerRadius.toDouble())
        }
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
        TapSessionRecorder.current?.event("stage-clear-highlights")
        holeTarget = 0f
        postInvalidateOnAnimation()
    }

    fun exit() {
        TapSessionRecorder.current?.event("stage-exit")
        clock.tick()
        exitAt = clock.now
        borderTarget = 0f
        borderFadeS = params.borderFadeS
        dimTarget = 0f
        setPhase(Phase.EXITING)
    }

    /** 接收状态下旋转屏幕：光尘带按新的屏幕尺寸重建。 */
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
        // 交接是按旧尺寸算的
        buildDust()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dustLayer.release()
        dust = null
    }

    /** 接收状态下列表完全不动：触摸一律吞掉。按钮叠在舞台之上，先拿到自己的触摸。 */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return phase != Phase.IDLE
    }

    // ---------------------------------------------------------------- 绘制

    private fun buildDust() {
        dust = null
        if (width <= 0 || height <= 0) return
        val band = TapDustBand(width.toFloat(), height.toFloat(), density, screenCornerRadius, params)
        dust = band
        dustLayer.setBand(band)
    }

    private fun fadePaletteTo(target: IntArray) {
        paletteFrom = currentPalette()
        paletteTo = target
        paletteT = 0f
    }

    private fun currentPalette(): IntArray {
        if (paletteT >= 1f) {
            return paletteTo
        }
        return IntArray(paletteTo.size) { lerpColor(paletteFrom[it], paletteTo[it], paletteT) }
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
        dimAlpha = approach(dimAlpha, dimTarget, dt / params.receiveDimFadeS)
        holeOpen = approach(holeOpen, holeTarget, dt / params.holeCloseS)
        if (paletteT < 1f) {
            paletteT = min(1f, paletteT + dt / params.borderPaletteFadeS)
            palette = currentPalette()
        }
        if (resetPending && borderAlpha <= .01f) {
            resetToWaiting()
        }
        if (phase == Phase.EXITING && borderAlpha <= .01f && dimAlpha <= .005f && ripples(now).isEmpty()) {
            phase = Phase.IDLE
            renderDust(now, idle = true)
            post { listener.onExitFinished(this) }
            return
        }

        if (dimAlpha > .001f) {
            drawDim(canvas)
        }
        renderDust(now)
        // 罩层、光尘与扩散圈一直在动：接收状态期间一直刷新
        postInvalidateOnAnimation()
    }

    /**
     * 把这一帧的光尘带、柔光与扩散圈交给 GPU 层。拼卡那一层流入期间（到拼好为止）整条带的不透明度按 1 算：
     * 被接手的那几片照常画到交接时刻，其余按外退的进度淡去；此外按 [borderAlpha]（出现、退场、拼好之后重新
     * 出现）。[idle] 时交一份空的，GPU 层清掉画面。
     */
    private fun renderDust(now: Float, idle: Boolean = false) {
        val band = dust ?: return
        val diffuse = if (touchedAt.isNaN()) 0f else (now - touchedAt).coerceAtLeast(0f)
        val spreadNow = if (touchedAt.isNaN()) 0f else spread
        var bandAlpha = borderAlpha
        var glowAlpha = borderAlpha
        var retreat = 0f
        var retreatAlpha = 1f
        val inflowing = !inflowAt.isNaN() && now - inflowAt < params.appearS && phase == Phase.BUSY
        if (inflowing) {
            val (distanceDp, duration) = params.dustRetreat.let { it[0] to it[1] }
            val u = ((now - inflowAt) / duration.coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
            retreat = distanceDp * density * u * u * (3f - 2f * u)
            retreatAlpha = (1f - u).pow(1.5f)
            bandAlpha = 1f
            glowAlpha = retreatAlpha
        }
        if (idle) {
            bandAlpha = 0f
            glowAlpha = 0f
        }
        // 柔光贴着屏幕边缘，随扩散稍微向里延伸：以带的中线为轴的高斯晕
        val reach = band.reach(spreadNow)
        val glowCenter = band.inset + reach * .1f
        val glowSigma = max(reach * .4f + params.dustGlow[0] * density, 1f)
        val rings = if (idle) FloatArray(0) else ripples(now)
        dustLayer.render(TapDustLayer.Frame(
            now, spreadNow, diffuse, bandAlpha, retreat, retreatAlpha, tau, palette,
            now / params.borderPaletteCycleS, glowCenter, glowSigma, params.dustGlow[1] * glowAlpha,
            antennaX, antennaY, rings, if (isDark(dimColor)) RING_ON_DARK else RING_ON_LIGHT
        ))
    }

    /**
     * 天线处的扩散圈（与发送同一套）：开始接收时淡入，颗粒从这里冒出；流入时边收缩边淡去，卡片拼好时
     * 已经消失；退出时淡去。返回逐圈的（半径, 不透明度）。
     */
    private fun ripples(now: Float): FloatArray {
        if (touchedAt.isNaN() || antennaX.isNaN()) return FloatArray(0)
        val fade = params.borderFadeS
        var envelope = min((now - touchedAt) / fade, 1f)
        var leave = 0f
        if (!inflowAt.isNaN()) {
            val span = max(.6f * params.appearS, 1e-3f)
            leave = smooth(inflowAt, inflowAt + span, now)
        }
        if (!exitAt.isNaN()) {
            leave = max(leave, ((now - exitAt) / fade).coerceIn(0f, 1f))
        }
        envelope *= 1f - leave
        if (envelope <= .003f) return FloatArray(0)
        val scale = 1f - params.pulseShrink * leave
        val clockS = now - touchedAt
        val out = FloatArray(params.pulseRings * 2)
        for (k in 0 until params.pulseRings) {
            val ringPhase = ((clockS / params.pulsePeriodS) + k.toFloat() / params.pulseRings) % 1f
            val eased = 1f - (1f - ringPhase).pow(2)
            out[k * 2] = (params.pulseRadiusDp[0] + params.pulseRadiusDp[1] * eased) * density * scale
            out[k * 2 + 1] = (1f - ringPhase) * params.pulseAlpha * envelope
        }
        return out
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

    companion object {
        // 罩层、光尘带、柔光、扩散圈与调色板的取值见 shared/tap-transfer/particles.properties（receive_*）

        /** 扩散圈的颜色：深色罩层上是白色，浅色罩层上是中性深灰（不与彩色的光尘带抢色）。 */
        private const val RING_ON_DARK = 0xFFFFFFFF.toInt()
        private const val RING_ON_LIGHT = 0xFF4A525E.toInt()

        /** 把收到的记事的颜色铺成与默认调色板同样的长度，换色时逐项过渡。 */
        private fun spread(colors: List<Int>, size: Int): IntArray {
            val opaque = colors.map { it or 0xFF000000.toInt() }
            return IntArray(size) { opaque[it % opaque.size] }
        }

        private fun lerpColor(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = ((((a shr shift) and 0xFF) * (1f - t) + ((b shr shift) and 0xFF) * t) + .5f).toInt()
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        private fun approach(value: Float, target: Float, step: Float): Float {
            return if (value < target) min(target, value + step) else max(target, value - step)
        }

        private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** 与模拟器相同的明暗判断：按 0.3 / 0.59 / 0.11 加权的亮度低于一半。 */
        private fun isDark(color: Int): Boolean {
            val lum = ((color shr 16) and 0xFF) * .3f + ((color shr 8) and 0xFF) * .59f + (color and 0xFF) * .11f
            return lum < 128f
        }
    }
}
