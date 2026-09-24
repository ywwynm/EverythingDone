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
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.cardview.widget.CardView
import com.ywwynm.everythingdone.taptransfer.ui.TapAntennaClearance
import com.ywwynm.everythingdone.taptransfer.ui.card.TapCardHolderLayout
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 分享界面的上半部分：要分享的记事以首页卡片（宽版）的样子抬升起来，停在上半屏；选了多条时
 * 叠成一叠，最上面一张带条数。碰上之后画面交给粒子层（`TapParticleFlight.Outflow`），这里只管
 * 卡片本身的抬升、落回，以及天线处的等待提示与吸入时的光。
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

    private class Slot(val view: View, val origin: RectF?) {
        var width = 0
        var height = 0
        var targetCx = 0f
        var targetCy = 0f
        var targetScale = 1f
        var targetRotation = 0f
    }

    private val density = resources.displayMetrics.density
    private val stack = TapCardHolderLayout(context)
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

    private var waitingPulse = false
    private var glow = 0f

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val spriteMatrix = Matrix()
    private val glowShader = BitmapShader(GLOW_SPRITE, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val ringShader = BitmapShader(RING_SPRITE, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val pulseStart = SystemClock.uptimeMillis()

    init {
        isClickable = false
        setWillNotDraw(false)
        addView(stack, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        stack.decoration = { canvas -> drawBadge(canvas) }
        badgePaint.color = BADGE_COLOR
        badgeTextPaint.color = Color.WHITE
        badgeTextPaint.textAlign = Paint.Align.CENTER
        badgeTextPaint.typeface = Typeface.DEFAULT_BOLD
        badgeTextPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }

    /** 装卡片的那一层；截粒子快照时截它。 */
    val stackView: View get() = stack

    /** 卡片可以占的区域（状态栏之下、面板之上），本视图坐标。 */
    fun setCardArea(area: RectF) {
        cardArea.set(area)
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
     * 放上卡片。[views] 第 0 张在最上面；[origins] 与之同序，是抬升之前卡片在页面上的位置
     * （本视图坐标），为 null 表示从页面里升起（详情页）。[total] 是要分享的条数（可能比卡片多）。
     */
    fun setCards(views: List<View>, origins: List<RectF?>, total: Int) {
        stack.removeAllViews()
        slots.clear()
        val count = min(views.size, MAX_STACK)
        // 从最底下一张开始加，最上面一张最后画
        for (k in count - 1 downTo 0) {
            stack.addView(views[k])
        }
        for (k in 0 until count) {
            slots.add(Slot(views[k], origins.getOrNull(k)))
        }
        totalCount = total
        measureCards()
        layoutTargets()
        applyLift(0f)
    }

    private fun measureCards() {
        for (slot in slots) {
            val lp = slot.view.layoutParams
            val widthSpec = MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY)
            val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            slot.view.measure(widthSpec, heightSpec)
            slot.width = slot.view.measuredWidth
            slot.height = slot.view.measuredHeight
        }
    }

    /**
     * 卡片区里的目标位置：整叠居中，放不下时整体缩小（不裁掉内容）。天线落在整叠卡片的范围里时，
     * 整叠挪到天线的上方或下方（[TapAntennaClearance]）：颗粒要看得出是流向那一点。
     */
    private fun layoutTargets() {
        if (slots.isEmpty() || cardArea.isEmpty) {
            return
        }
        val margin = AREA_MARGIN_DP * density
        val tallest = slots.maxOf { it.height }.coerceAtLeast(1)
        var fit = min(1f, (cardArea.height() - 2f * margin) / tallest)
        val several = slots.size > 1
        // 整叠的外框（相对最上面那张的中心）：每张按旋转之后的包围盒算，连同叠放的错位
        var extentTop = 0f
        var extentBottom = 0f
        var halfWidth = 0f
        for ((k, slot) in slots.withIndex()) {
            val scale = fit * STACK_SCALE.pow(k)
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
        // 外框在卡片区里居中；天线落在外框里时挪到它的上方或下方，必要时缩小
        val height = extentBottom - extentTop
        var boxTop = cardArea.centerY() - height / 2f
        var boxScale = 1f
        if (antennaKnown) {
            val placement = TapAntennaClearance.place(
                boxTop, height, cardArea.centerX() - halfWidth, cardArea.centerX() + halfWidth,
                antennaX, antennaY, ANTENNA_CLEARANCE_DP * density,
                cardArea.top + margin, cardArea.bottom - margin, MIN_AVOID_SCALE
            )
            boxTop = placement.top
            boxScale = placement.scale
        }
        fit *= boxScale
        val anchorY = boxTop - extentTop * boxScale
        for ((k, slot) in slots.withIndex()) {
            slot.targetScale = fit * STACK_SCALE.pow(k)
            slot.targetRotation = if (several) STACK_ROTATION[k] else 0f
            slot.targetCx = cardArea.centerX() + (if (several) STACK_OFFSET_X[k] else 0f) * density
            slot.targetCy = anchorY + (if (several) STACK_OFFSET_Y[k] else 0f) * density
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
        animateLift(1f, LIFT_MS, LIFT_INTERPOLATOR) { listener.onLiftFinished() }
        animateElevation(1f, LIFT_MS)
    }

    fun drop() {
        animateLift(0f, DROP_MS, DROP_INTERPOLATOR) { listener.onDropFinished() }
        animateElevation(0f, DROP_MS)
    }

    private fun animateLift(to: Float, durationMs: Long, interpolator: PathInterpolator, onEnd: () -> Unit) {
        liftAnimator?.let { it.removeAllListeners(); it.cancel() }
        liftAnimator = ValueAnimator.ofFloat(lift, to).apply {
            duration = (durationMs * abs(to - lift)).toLong().coerceAtLeast(1L)
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
        val detailRiseDy = DETAIL_RISE_DP * density
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
                startScale = slot.targetScale * DETAIL_RISE_SCALE
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
            view.alpha = (local / FADE_IN_PORTION).coerceIn(0f, 1f)
        }
        stack.invalidate()
    }

    /** 卡片投影的强弱：1 为抬升后的高度，0 为贴在页面上。截粒子快照之前压到 0，交接处不闪。 */
    fun animateElevation(to: Float, durationMs: Long) {
        elevationAnimator?.cancel()
        val from = currentElevationFraction
        elevationAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            addUpdateListener { setElevationFraction(it.animatedValue as Float) }
            start()
        }
    }

    private var currentElevationFraction = 0f

    private fun setElevationFraction(fraction: Float) {
        currentElevationFraction = fraction
        val elevation = LIFTED_ELEVATION_DP * density * fraction
        for (slot in slots) {
            (slot.view as? CardView)?.cardElevation = elevation
        }
    }

    /** 粒子层接管时藏起卡片，流回之后再显示。 */
    fun setCardsShown(shown: Boolean) {
        stack.alpha = if (shown) 1f else 0f
    }

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

    /** 等待被碰时在天线位置画一圈圈向外扩散的提示。 */
    fun setWaitingPulse(on: Boolean) {
        waitingPulse = on
        invalidate()
    }

    /** 吸入时天线处的光，由流出进度决定（0–1）。 */
    fun setGlow(level: Float) {
        glow = level.coerceIn(0f, 1f)
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (glow > .01f) {
            // 画成预先做好的白色位图：部分定制系统的强制深色会把画笔与渐变里的白色反成黑色
            drawSprite(canvas, glowShader, GLOW_SPRITE_SIZE, (14f + 10f * glow) * density, glow * .7f)
        }
        if (waitingPulse) {
            val now = (SystemClock.uptimeMillis() - pulseStart) / 1000f
            for (k in 0 until PULSE_RINGS) {
                val phase = ((now / PULSE_PERIOD_S) + k.toFloat() / PULSE_RINGS) % 1f
                val eased = 1f - (1f - phase).pow(2)
                drawSprite(canvas, ringShader, RING_SPRITE_SIZE, (10f + 34f * eased) * density, (1f - phase) * .6f)
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
        waitingPulse = false
    }

    companion object {
        private const val MAX_STACK = 4
        private const val LIFT_MS = 460L
        private const val DROP_MS = 320L
        private val LIFT_INTERPOLATOR = PathInterpolator(.2f, 0f, 0f, 1f)
        private val DROP_INTERPOLATOR = PathInterpolator(.4f, 0f, .6f, 1f)

        /** 抬起来的卡片在这段进度内淡入，盖住列表里的原件。 */
        private const val FADE_IN_PORTION = .35f

        /** 详情页里卡片从下方多远升起、从多小放大到原尺寸。 */
        private const val DETAIL_RISE_DP = 56f
        private const val DETAIL_RISE_SCALE = .94f

        private const val AREA_MARGIN_DP = 20f

        /** 卡片边缘到天线至少留这么远，颗粒流进天线的那一段看得见；等待圈最大半径 44dp。 */
        private const val ANTENNA_CLEARANCE_DP = 64f

        /** 为了让开天线，卡片最多缩到这么大；再小就不让了。 */
        private const val MIN_AVOID_SCALE = .75f
        private const val LIFTED_ELEVATION_DP = 10f

        private val STACK_ROTATION = floatArrayOf(0f, -6f, 5f, -2.5f)
        private val STACK_OFFSET_X = floatArrayOf(0f, -10f, 12f, -4f)
        private val STACK_OFFSET_Y = floatArrayOf(0f, 8f, 10f, 16f)
        private const val STACK_SCALE = .97f
        private const val STACK_STAGGER = .08f

        private const val BADGE_COLOR = 0xE6202124.toInt()

        private const val PULSE_RINGS = 2
        private const val PULSE_PERIOD_S = 1.8f
        private const val GLOW_SPRITE_SIZE = 128

        /** 光点的高斯衰减系数：半径一半处剩约 30%。 */
        private const val GLOW_FALLOFF = 5f
        private const val RING_SPRITE_SIZE = 256

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        /**
         * 天线处的光：白色，透明度按高斯衰减（中心亮、四周很快淡下去），读起来是一个光点而不是
         * 一块圆斑；边缘处减去常数项归零，不留硬边。
         */
        private val GLOW_SPRITE: Bitmap by lazy {
            val size = GLOW_SPRITE_SIZE
            val pixels = IntArray(size * size)
            val c = (size - 1) / 2f
            val floor = exp(-GLOW_FALLOFF)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val r = (sqrt((x - c) * (x - c) + (y - c) * (y - c)) / c).coerceAtMost(1f)
                    val falloff = ((exp(-GLOW_FALLOFF * r * r) - floor) / (1f - floor)).coerceIn(0f, 1f)
                    pixels[y * size + x] = ((falloff * 255f + .5f).toInt() shl 24) or 0xFFFFFF
                }
            }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }

        /** 等待时的扩散圈：一道细白环，环宽约为半径的 3%。 */
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
