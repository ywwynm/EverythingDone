package com.ywwynm.everythingdone.taptransfer.ui.share

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.cardview.widget.CardView
import com.ywwynm.everythingdone.taptransfer.ui.TapAntennaClearance
import com.ywwynm.everythingdone.taptransfer.ui.card.TapCardHolderLayout
import com.ywwynm.everythingdone.taptransfer.ui.card.TapThingCards
import com.ywwynm.everythingdone.taptransfer.ui.flight.TapParticleParams
import com.ywwynm.everythingdone.taptransfer.ui.flight.TapSessionRecorder
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 分享界面的卡片区：要分享的记事以首页的卡片抬升起来（字号、封面与行数的规则按首页，宽度加宽，
 * 见 `TapThingCards.LiftedCard`），放不下时先删减内容、压扁封面，仍放不下才整体缩小；选了多条时
 * 按面积叠成一叠（最大的在最下面），最上面一张带条数，整叠共用一片投影。碰上之后画面交给粒子层
 * （`TapParticleFlight.Outflow`），这里只管卡片本身的抬升、落回与发完之后的重新抬起，以及天线处的
 * 扩散圈（第三版起不再画光点）。
 *
 * 卡片是真实的视图（首页卡片布局），抬升、落回都是视图属性动画；位置一律用平移表达：卡片布局
 * 在左上角，平移到目标位置，轴心在卡片中心。触摸不消费，落到下面的面板与暗层。
 */
@SuppressLint("ViewConstructor")
class TapShareCardStage(context: Context, private val listener: Listener) : FrameLayout(context) {

    interface Listener {
        fun onLiftFinished()
        fun onDropFinished()
    }

    private class Slot(val card: TapThingCards.LiftedCard, val origin: RectF?) {
        val view: View get() = card.view
        var width = 0
        var height = 0
        var targetCx = 0f
        var targetCy = 0f
        var targetScale = 1f
        var targetRotation = 0f
    }

    private val density = resources.displayMetrics.density
    private val params = TapParticleParams.get(context)
    private val liftInterpolator = params.liftInterpolator()
    private val dropInterpolator = params.dropInterpolator()
    private val stack = TapCardHolderLayout(context)

    /**
     * 整叠卡片的投影（决策 2026-09-26 第 6 条）：一个不画任何内容的视图，轮廓是几张卡片轮廓的并集，
     * 投影的深浅由它的 Z 决定。卡片自己不投影，上面的卡片也就不会压暗下面的。
     */
    private val shadow = View(context)
    private val shadowPath = Path()
    private val cardPath = Path()
    private val cardMatrix = Matrix()
    private val slots = ArrayList<Slot>()
    private var totalCount = 0
    private val cardArea = RectF()
    private var antennaX = 0f
    private var antennaY = 0f
    private var antennaKnown = false

    /** 抬升进度：0 在原处（或页面里），1 在目标位。 */
    private var lift = 0f
    private var liftAnimator: ValueAnimator? = null
    private var elevationAnimator: ValueAnimator? = null

    private var pulse = false

    /** 扩散圈收缩的程度（0–1）：流出时随进度增加；重新抬起期间由本视图按 1 − 抬起进度给出。 */
    private var pulseShrink = 0f
    private var relifting = false

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val spriteMatrix = Matrix()
    private val ringShader = BitmapShader(RING_SPRITE, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val pulseStart = SystemClock.uptimeMillis()

    init {
        isClickable = false
        setWillNotDraw(false)
        shadow.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = buildShadowOutline(outline)
        }
        shadow.alpha = 0f
        addView(shadow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(stack, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 同一层里 Z 高的后画：卡片那一层始终高过投影（它自己没有轮廓，不投影），投影才在卡片底下
        stack.outlineProvider = null
        stack.translationZ = (params.elevationDp + 1f) * density
        stack.decoration = { canvas -> drawBadge(canvas) }
        badgePaint.color = BADGE_COLOR
        badgeTextPaint.color = Color.WHITE
        badgeTextPaint.textAlign = Paint.Align.CENTER
        badgeTextPaint.typeface = Typeface.DEFAULT_BOLD
        badgeTextPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }

    /** 装卡片的那一层；截粒子快照时截它。 */
    val stackView: View get() = stack

    /** 卡片可以占的区域（状态栏之下、面板之上，或手机横屏时面板左边），本视图坐标。 */
    fun setCardArea(area: RectF) {
        if (area == cardArea) {
            return
        }
        cardArea.set(area)
        fitCards()
        layoutTargets()
    }

    /** NFC 天线在本视图里的位置。卡片会让开它。 */
    fun setAntenna(x: Float, y: Float) {
        antennaX = x
        antennaY = y
        antennaKnown = true
        layoutTargets()
        invalidate()
    }

    /**
     * 放上卡片。[cards] 按选中的先后；[origins] 与之同序，是抬升之前卡片在页面上的位置
     * （本视图坐标），为 null 表示从页面里升起（详情页）。[total] 是要分享的条数（可能比卡片多）。
     * 叠放的顺序按面积另排（[fitCards]）。
     */
    internal fun setCards(cards: List<TapThingCards.LiftedCard>, origins: List<RectF?>, total: Int) {
        stack.removeAllViews()
        slots.clear()
        for (k in 0 until min(cards.size, MAX_STACK)) {
            slots.add(Slot(cards[k], origins.getOrNull(k)))
        }
        totalCount = total
        fitCards()
        layoutTargets()
        applyLift(0f)
    }

    /**
     * 按卡片区排每一张：宽度、放不下时的删减（`TapThingCards.LiftedCard.fit`），高度上限是整块卡片区
     * （可以压住天线）；叠放时再扣掉错位与旋转多出来的高度，每张都按最坏的那个位置扣，叠好之后整叠不会
     * 超出。排完按面积从小到大自上而下叠：最大的在最下面，面积相同的先选的在上。
     */
    private fun fitCards() {
        if (slots.isEmpty() || cardArea.isEmpty) {
            return
        }
        val budget = cardArea.height() - 2f * AREA_MARGIN_DP * density
        var drop = 0f
        var tilt = 0f
        if (slots.size > 1) {
            for (k in slots.indices) {
                drop = max(drop, STACK_OFFSET_Y[k] * density)
                tilt = max(tilt, abs(STACK_ROTATION[k]))
            }
        }
        val tiltSin = sin(Math.toRadians(tilt.toDouble())).toFloat()
        val minCover = (cardArea.height() * MIN_COVER_FRACTION).toInt()
        for (slot in slots) {
            val width = slot.card.widthIn(cardArea.width().toInt())
            val maxHeight = (budget - drop - width * tiltSin).toInt().coerceAtLeast(1)
            slot.height = slot.card.fit(width, maxHeight, minCover)
            slot.width = width
        }
        // sortBy 是稳定的：面积相同时保持选中的先后
        slots.sortBy { it.width.toLong() * it.height }
        stack.removeAllViews()
        // 从最底下一张开始加，最上面一张最后画
        for (k in slots.indices.reversed()) {
            stack.addView(slots[k].view)
        }
    }

    /**
     * 卡片区里的目标位置：整叠居中，删减之后仍放不下才整体缩小。天线落在整叠卡片的范围里时，整叠不缩小
     * 就能放在天线上方或下方的，挪过去（[TapAntennaClearance]），颗粒看得出是流向那一点；放不下就压着天线
     * （决策 2026-09-26 第 1 条）。
     */
    private fun layoutTargets() {
        if (slots.isEmpty() || cardArea.isEmpty) {
            return
        }
        val margin = AREA_MARGIN_DP * density
        val several = slots.size > 1
        // 整叠的外框（相对最上面那张的中心，原尺寸）：每张按旋转之后的包围盒算，连同叠放的错位
        var extentTop = 0f
        var extentBottom = 0f
        var halfWidth = 0f
        for ((k, slot) in slots.withIndex()) {
            val scale = STACK_SCALE.pow(k)
            val angle = Math.toRadians((if (several) STACK_ROTATION[k] else 0f).toDouble())
            val c = abs(cos(angle)).toFloat()
            val s = abs(sin(angle)).toFloat()
            val halfH = (slot.width * s + slot.height * c) * scale / 2f
            val halfW = (slot.width * c + slot.height * s) * scale / 2f
            val dx = (if (several) STACK_OFFSET_X[k] else 0f) * density
            val dy = (if (several) STACK_OFFSET_Y[k] else 0f) * density
            extentTop = min(extentTop, dy - halfH)
            extentBottom = max(extentBottom, dy + halfH)
            halfWidth = max(halfWidth, abs(dx) + halfW)
        }
        // 整体缩小是最后的办法：删减之后仍比卡片区高，或斜放的卡片角伸出卡片区
        val fit = min(1f, min((cardArea.height() - 2f * margin) / (extentBottom - extentTop).coerceAtLeast(1f),
            cardArea.width() / (2f * halfWidth).coerceAtLeast(1f)))
        val height = (extentBottom - extentTop) * fit
        var boxTop = cardArea.centerY() - height / 2f
        if (antennaKnown) {
            val placement = TapAntennaClearance.place(
                boxTop, height, cardArea.centerX() - halfWidth * fit, cardArea.centerX() + halfWidth * fit,
                antennaX, antennaY, ANTENNA_CLEARANCE_DP * density,
                cardArea.top + margin, cardArea.bottom - margin
            )
            boxTop = placement.top
        }
        val anchorY = boxTop - extentTop * fit
        for ((k, slot) in slots.withIndex()) {
            slot.targetScale = fit * STACK_SCALE.pow(k)
            slot.targetRotation = if (several) STACK_ROTATION[k] else 0f
            slot.targetCx = cardArea.centerX() + (if (several) STACK_OFFSET_X[k] else 0f) * density * fit
            slot.targetCy = anchorY + (if (several) STACK_OFFSET_Y[k] else 0f) * density * fit
        }
        applyLift(lift)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 图片载入后卡片可能变高：按新尺寸重新摆
        var resized = false
        for (slot in slots) {
            if (slot.view.width != slot.width || slot.view.height != slot.height) {
                slot.width = slot.view.width
                slot.height = slot.view.height
                resized = true
            }
        }
        if (resized) {
            layoutTargets()
        }
    }

    // ---------------------------------------------------------------- 抬升与落回

    fun lift() {
        animateLift(1f, params.liftMs, liftInterpolator) { listener.onLiftFinished() }
        animateElevation(1f, params.liftMs)
    }

    fun drop() {
        relifting = false
        animateLift(0f, params.dropMs, dropInterpolator) { listener.onDropFinished() }
        animateElevation(0f, params.dropMs)
    }

    /**
     * 发完、「已发送」停留之后：卡片按打开时的那段抬起重新出现（此前已化成颗粒流走），投影随之恢复。
     * 不再从天线处流回：流回读起来像发送失败、被撤回。扩散圈随抬起展开。
     */
    fun relift(onEnd: () -> Unit) {
        relifting = true
        applyLift(0f)
        setCardsShown(true)
        animateLift(1f, params.liftMs, liftInterpolator) {
            relifting = false
            pulseShrink = 0f
            onEnd()
        }
        animateElevation(1f, params.liftMs)
    }

    private fun animateLift(to: Float, durationMs: Long, interpolator: PathInterpolator, onEnd: () -> Unit) {
        liftAnimator?.let { it.removeAllListeners(); it.cancel() }
        val from = lift
        liftAnimator = ValueAnimator.ofFloat(lift, to).apply {
            duration = (durationMs * abs(to - lift)).toLong().coerceAtLeast(1L)
            val actual = duration
            TapSessionRecorder.current?.event("lift") {
                put("from", from.toDouble()); put("to", to.toDouble()); put("duration", actual)
            }
            this.interpolator = interpolator
            addUpdateListener { applyLift(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = onEnd()
            })
            start()
        }
    }

    private fun applyLift(t: Float) {
        lift = t
        val detailRiseDy = params.detailRiseDp * density
        for ((k, slot) in slots.withIndex()) {
            if (slot.width == 0) {
                continue
            }
            // 叠放时后面的卡片晚一点点离开页面
            val local = if (slots.size > 1) ((t - k * STACK_STAGGER) / (1f - k * STACK_STAGGER)).coerceIn(0f, 1f) else t
            val origin = slot.origin
            val startCx: Float
            val startCy: Float
            val startScale: Float
            val startRotation: Float
            if (origin != null) {
                // 从列表里那张卡片的位置与大小出发
                startCx = origin.centerX()
                startCy = origin.centerY()
                startScale = origin.width() / slot.width
                startRotation = 0f
            } else {
                // 详情页：从页面里升起，页面内容保持显示
                startCx = slot.targetCx
                startCy = slot.targetCy + detailRiseDy
                startScale = slot.targetScale * params.detailRiseScale
                startRotation = slot.targetRotation
            }
            val cx = lerp(startCx, slot.targetCx, local)
            val cy = lerp(startCy, slot.targetCy, local)
            val view = slot.view
            view.pivotX = slot.width / 2f
            view.pivotY = slot.height / 2f
            view.translationX = cx - slot.width / 2f
            view.translationY = cy - slot.height / 2f
            val scale = lerp(startScale, slot.targetScale, local)
            view.scaleX = scale
            view.scaleY = scale
            view.rotation = lerp(startRotation, slot.targetRotation, local)
            // 列表里的原件一直在：抬起来的这张先淡入、盖在原件上
            view.alpha = (local / params.liftFade).coerceIn(0f, 1f)
        }
        stack.invalidate()
        updateShadow()
    }

    /** 投影跟着卡片走：轮廓按卡片此刻的位置重算，透明度跟最先淡入的那张（与卡片自己投影时一样）。 */
    private fun updateShadow() {
        var alpha = 0f
        for (slot in slots) {
            alpha = max(alpha, slot.view.alpha)
        }
        shadow.alpha = alpha * stack.alpha
        shadow.invalidateOutline()
    }

    /**
     * 投影的轮廓：几张卡片（含平移、缩放、旋转）圆角矩形的并集。Android 11 以下的轮廓只能是凸的，
     * 退回用最底下那张（最大的）。还没淡入的卡片不算。
     */
    private fun buildShadowOutline(outline: Outline) {
        shadowPath.reset()
        val union = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val casters = if (union) slots else slots.takeLast(1)
        for (slot in casters) {
            val view = slot.view
            if (slot.width == 0 || slot.height == 0 || view.alpha <= 0f) {
                continue
            }
            val radius = (view as? CardView)?.radius ?: 0f
            cardPath.reset()
            cardPath.addRoundRect(0f, 0f, slot.width.toFloat(), slot.height.toFloat(), radius, radius, Path.Direction.CW)
            cardMatrix.set(view.matrix)
            cardMatrix.postTranslate(view.left.toFloat(), view.top.toFloat())
            cardPath.transform(cardMatrix)
            if (shadowPath.isEmpty) {
                shadowPath.set(cardPath)
            } else {
                shadowPath.op(cardPath, Path.Op.UNION)
            }
        }
        if (shadowPath.isEmpty) {
            outline.setEmpty()
        } else if (union) {
            outline.setPath(shadowPath)
        } else {
            @Suppress("DEPRECATION")
            outline.setConvexPath(shadowPath)
        }
    }

    /** 卡片投影的强弱：1 为抬升后的高度，0 为贴在页面上。截粒子快照之前压到 0，交接处不闪。 */
    fun animateElevation(to: Float, durationMs: Long) {
        elevationAnimator?.cancel()
        val from = currentElevationFraction
        TapSessionRecorder.current?.event("elevation") {
            put("from", from.toDouble()); put("to", to.toDouble()); put("duration", durationMs)
        }
        elevationAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            addUpdateListener { setElevationFraction(it.animatedValue as Float) }
            start()
        }
    }

    private var currentElevationFraction = 0f

    private fun setElevationFraction(fraction: Float) {
        currentElevationFraction = fraction
        shadow.elevation = params.elevationDp * density * fraction
    }

    /** 粒子层接管时藏起卡片，流回之后再显示。 */
    fun setCardsShown(shown: Boolean) {
        stack.alpha = if (shown) 1f else 0f
        updateShadow()
        TapSessionRecorder.current?.event("cards") { put("shown", shown) }
    }

    /** 调试（会话记录）：每张卡片的尺寸、起点与目标，舞台坐标。 */
    fun describeSlots(): JSONArray = JSONArray().also { list ->
        for (slot in slots) {
            val origin = slot.origin
            list.put(JSONObject()
                .put("size", JSONArray().put(slot.width).put(slot.height))
                .put("target", JSONArray().put(slot.targetCx.toDouble()).put(slot.targetCy.toDouble())
                    .put(slot.targetScale.toDouble()).put(slot.targetRotation.toDouble()))
                .put("origin", origin?.let { JSONArray().put(it.left.toDouble()).put(it.top.toDouble())
                    .put(it.right.toDouble()).put(it.bottom.toDouble()) } ?: JSONObject.NULL))
        }
    }

    /** 调试（会话记录）：卡片视图，第 0 张在最上面。 */
    fun slotViews(): List<View> = slots.map { it.view }

    /** 调试（会话记录）：等待圈的时钟起点（uptime 毫秒）与天线位置（舞台坐标）。 */
    val pulseStartUptime: Long get() = pulseStart
    val antennaInStage: FloatArray get() = floatArrayOf(antennaX, antennaY)
    val stackCount: Int get() = totalCount

    /**
     * 卡片（含旋转与缩放）在 [stackView] 里占的范围，截粒子快照用；没有卡片时返回 false。
     */
    fun snapshotRegion(out: Rect): Boolean {
        if (slots.isEmpty()) {
            return false
        }
        val bounds = RectF()
        val corners = FloatArray(8)
        var first = true
        for (slot in slots) {
            val view = slot.view
            corners[0] = 0f; corners[1] = 0f
            corners[2] = slot.width.toFloat(); corners[3] = 0f
            corners[4] = slot.width.toFloat(); corners[5] = slot.height.toFloat()
            corners[6] = 0f; corners[7] = slot.height.toFloat()
            val m = Matrix(view.matrix)
            m.postTranslate(view.left.toFloat(), view.top.toFloat())
            m.mapPoints(corners)
            for (i in 0 until 4) {
                if (first) {
                    bounds.set(corners[0], corners[1], corners[0], corners[1])
                    first = false
                }
                bounds.union(corners[i * 2], corners[i * 2 + 1])
            }
        }
        out.set(
            floor(bounds.left).toInt() - 2, floor(bounds.top).toInt() - 2,
            ceil(bounds.right).toInt() + 2, ceil(bounds.bottom).toInt() + 2
        )
        return out.intersect(0, 0, stack.width, stack.height)
    }

    // ---------------------------------------------------------------- 天线提示

    /**
     * 天线处一圈圈向外扩散的提示：从可以被碰一直播到关闭，中途不断（第三版）。流出时随进度收缩
     * （[setPulseShrink]），重新抬起时随抬起展开。
     */
    fun setPulse(on: Boolean) {
        if (on != pulse) TapSessionRecorder.current?.event("pulse") { put("on", on) }
        pulse = on
        invalidate()
    }

    /** 扩散圈收缩的程度（0–1），流出期间按粒子层实际呈现的进度给。重新抬起期间不接受外部的值。 */
    fun setPulseShrink(shrink: Float) {
        if (relifting) return
        pulseShrink = shrink.coerceIn(0f, 1f)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (pulse) {
            val now = (SystemClock.uptimeMillis() - pulseStart) / 1000f
            val shrink = if (relifting) 1f - lift else pulseShrink
            val scale = 1f - params.pulseShrink * shrink
            // 画成预先做好的白色位图：部分定制系统的强制深色会把画笔里的白色反成黑色
            for (k in 0 until params.pulseRings) {
                val phase = ((now / params.pulsePeriodS) + k.toFloat() / params.pulseRings) % 1f
                val eased = 1f - (1f - phase).pow(2)
                drawSprite(canvas, ringShader, RING_SPRITE_SIZE,
                    (params.pulseRadiusDp[0] + params.pulseRadiusDp[1] * eased) * density * scale,
                    (1f - phase) * params.pulseAlpha)
            }
            postInvalidateOnAnimation()
        }
    }

    private fun drawSprite(canvas: Canvas, shader: BitmapShader, size: Int, radius: Float, alpha: Float) {
        spriteMatrix.setScale(2f * radius / size, 2f * radius / size)
        spriteMatrix.postTranslate(antennaX - radius, antennaY - radius)
        shader.setLocalMatrix(spriteMatrix)
        spritePaint.shader = shader
        spritePaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawRect(antennaX - radius, antennaY - radius, antennaX + radius, antennaY + radius, spritePaint)
    }

    /** 叠放时最上面一张的右上角标出条数；画在装卡片的那一层里，粒子快照会一起截进去。 */
    private fun drawBadge(canvas: Canvas) {
        val top = slots.firstOrNull() ?: return
        if (totalCount <= 1 || top.width == 0) {
            return
        }
        val view = top.view
        val alpha = view.alpha
        if (alpha <= .01f) {
            return
        }
        val half = top.width * view.scaleX / 2f
        val halfH = top.height * view.scaleY / 2f
        val cx = view.translationX + top.width / 2f + half - 2f * density
        val cy = view.translationY + top.height / 2f - halfH + 2f * density
        val r = 13f * density
        badgePaint.alpha = (alpha * 235f).toInt()
        badgeTextPaint.alpha = (alpha * 255f).toInt()
        canvas.drawCircle(cx, cy, r, badgePaint)
        val fm = badgeTextPaint.fontMetrics
        canvas.drawText(totalCount.toString(), cx, cy - (fm.ascent + fm.descent) / 2f, badgeTextPaint)
    }

    fun release() {
        liftAnimator?.let { it.removeAllListeners(); it.cancel() }
        elevationAnimator?.cancel()
        pulse = false
        relifting = false
    }

    companion object {
        private const val MAX_STACK = 4
        // 抬起、落回、淡入、升起的距离与缩放见 shared/tap-transfer/particles.properties（send_*）

        private const val AREA_MARGIN_DP = 20f

        /** 卡片边缘到天线至少留这么远，颗粒流进天线的那一段看得见；等待圈最大半径 44dp。 */
        private const val ANTENNA_CLEARANCE_DP = 64f

        /** 放不下时封面最矮压到卡片区高度的这么多。 */
        private const val MIN_COVER_FRACTION = .25f

        private val STACK_ROTATION = floatArrayOf(0f, -6f, 5f, -2.5f)
        private val STACK_OFFSET_X = floatArrayOf(0f, -10f, 12f, -4f)
        private val STACK_OFFSET_Y = floatArrayOf(0f, 8f, 10f, 16f)
        private const val STACK_SCALE = .97f
        private const val STACK_STAGGER = .08f

        private const val BADGE_COLOR = 0xE6202124.toInt()

        private const val RING_SPRITE_SIZE = 256

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        /** 扩散圈：一道细白环，环宽约为半径的 3%。接收方的舞台在 GPU 上画同一个形状（`TapDustLayer`）。 */
        private val RING_SPRITE: Bitmap by lazy {
            val size = RING_SPRITE_SIZE
            val pixels = IntArray(size * size)
            val c = (size - 1) / 2f
            val ringR = c - 3f
            val halfWidth = 2.2f
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val d = abs(sqrt((x - c) * (x - c) + (y - c) * (y - c)) - ringR)
                    val a = ((1f - (d - halfWidth + 1f).coerceIn(0f, 1f)) * 255f + .5f).toInt()
                    pixels[y * size + x] = (a shl 24) or 0xFFFFFF
                }
            }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }

    }
}
