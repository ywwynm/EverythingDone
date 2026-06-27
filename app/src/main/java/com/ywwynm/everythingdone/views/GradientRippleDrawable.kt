package com.ywwynm.everythingdone.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 触摸时从手指按下点扩散的「渐变色」水波纹，用作 View 的 foreground / background。
 *
 * 原生 [android.graphics.drawable.RippleDrawable] 的波纹只能是单色（其颜色取自
 * ColorStateList → 单个 int，见 AOSP RippleDrawable.updateRipplePaint），无法让扩散的
 * 波纹本身呈现渐变。这里用 [android.graphics.LinearGradient] 自绘扩散圆，使未选中态控件
 * 在按下时浮现一圈从起始色到结束色的渐变波纹；[ThingBackground.Mode.PURE] 背景退化为单色波纹。
 *
 * 动画对齐系统 ripple 的手感：
 * - 按下瞬间**锁定圆心**为当时的触摸点，扩散过程中不再随手指移动（手指滑动不会让波纹跟着滑）。
 * - 半径扩散一旦开始就一定跑到铺满，**松手 / 被父容器取消都不打断它**；松手只让 alpha 淡出。
 *   这样即使很快松手或滑动取消，也能看到波纹铺满后淡出，而不是半路消失。
 * - alpha 快速升满、与半径解耦，整体更接近系统的响应速度。
 *
 * 颜色取自 [background]（PURE / GRADIENT，方向沿用 [ThingBackground.Orientation]），通过
 * [BackgroundUtil.createLinearGradient] 生成 shader。波纹被裁剪到 [shapeOval] 指定的轮廓
 * （OVAL 圆形，或 ROUND_RECT 圆角矩形 + [cornerRadiusPx]，≤0 时取 height/2 即胶囊）。
 *
 * 写法参照 [com.ywwynm.everythingdone.utils.DisplayUtil] 中的 SeekBarTrackDrawable。
 */
class GradientRippleDrawable(
    background: ThingBackground,
    private val shapeOval: Boolean,
    private val cornerRadiusPx: Float = 0f
) : Drawable() {

    private var background: ThingBackground = background

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val boundsF = RectF()

    // 最新触摸点（setHotspot 持续更新，含 ACTION_MOVE）。
    private var hotspotX = Float.NaN
    private var hotspotY = Float.NaN
    // 波纹圆心：按下时锁定为当时的触摸点，扩散过程中固定不动，避免「跟手滑动」。
    private var originX = Float.NaN
    private var originY = Float.NaN

    private var cachedShader: Shader? = null
    private var shaderW = -1
    private var shaderH = -1

    // 半径与 alpha 各自独立：半径一旦开始扩散就一定跑到满（松手 / 取消都不打断），
    // alpha 单独淡入淡出。这样快速松手或滑动取消时波纹也能铺满再淡出，而不是半路消失。
    private var radiusAnimator: ValueAnimator? = null
    private var alphaAnimator: ValueAnimator? = null
    private var radiusFraction = 0f
    private var alphaFraction = 0f
    private var pressed = false
    private var drawableAlpha = 255

    /** 复用同一实例、仅切换颜色（scope / 记事色变化时）。 */
    fun updateBackground(bg: ThingBackground) {
        background = bg
        cachedShader = null
        invalidateSelf()
    }

    /** detach / RecyclerView 复用时调用，停止动画并复位，避免残影与多余的 vsync 回调。 */
    fun stopAnimations() {
        radiusAnimator?.cancel(); radiusAnimator = null
        alphaAnimator?.cancel(); alphaAnimator = null
        pressed = false
        radiusFraction = 0f
        alphaFraction = 0f
        originX = Float.NaN
        originY = Float.NaN
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = state.contains(android.R.attr.state_pressed)
        if (now == pressed) return false
        pressed = now
        if (now) enter() else exit()
        return true
    }

    override fun setHotspot(x: Float, y: Float) {
        hotspotX = x
        hotspotY = y
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        boundsF.set(bounds)
        rebuildClip()
        cachedShader = null
    }

    private fun rebuildClip() {
        clipPath.reset()
        if (boundsF.isEmpty) return
        if (shapeOval) {
            clipPath.addOval(boundsF, Path.Direction.CW)
        } else {
            val rad = if (cornerRadiusPx > 0f) cornerRadiusPx else boundsF.height() / 2f
            clipPath.addRoundRect(boundsF, rad, rad, Path.Direction.CW)
        }
    }

    private fun enter() {
        // 按下瞬间锁定圆心（系统在 ACTION_DOWN 时先 setHotspot 再置 pressed，故此处 hotspot 已就位）。
        originX = if (hotspotX.isNaN()) bounds.exactCenterX() else hotspotX
        originY = if (hotspotY.isNaN()) bounds.exactCenterY() else hotspotY

        radiusAnimator?.cancel()
        radiusFraction = 0f
        radiusAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RADIUS_DURATION
            interpolator = RADIUS_INTERPOLATOR
            addUpdateListener {
                radiusFraction = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }

        alphaAnimator?.cancel()
        alphaAnimator = ValueAnimator.ofFloat(alphaFraction, 1f).apply {
            duration = ALPHA_ENTER_DURATION
            interpolator = LINEAR
            addUpdateListener {
                alphaFraction = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    private fun exit() {
        // 不打断 radiusAnimator：让波纹继续扩散到铺满，只把 alpha 淡出。
        alphaAnimator?.cancel()
        alphaAnimator = ValueAnimator.ofFloat(alphaFraction, 0f).apply {
            duration = ALPHA_EXIT_DURATION
            interpolator = LINEAR
            addUpdateListener {
                alphaFraction = it.animatedValue as Float
                invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    radiusAnimator?.cancel(); radiusAnimator = null
                    radiusFraction = 0f
                    alphaFraction = 0f
                    originX = Float.NaN
                    originY = Float.NaN
                }
            })
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        if (alphaFraction <= 0f) return
        val b = bounds
        if (b.isEmpty) return
        val cx = if (originX.isNaN()) b.exactCenterX() else originX
        val cy = if (originY.isNaN()) b.exactCenterY() else originY
        val r = radiusFraction * maxRadius(cx, cy)
        if (r <= 0f) return

        val save = canvas.save()
        canvas.clipPath(clipPath)
        ensureShaderAndPaint()
        val a = (PEAK_ALPHA * alphaFraction * (drawableAlpha / 255f) * 255f).roundToInt()
        paint.alpha = a.coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r, paint)
        canvas.restoreToCount(save)
    }

    private fun maxRadius(cx: Float, cy: Float): Float {
        val b = bounds
        val dx = max(cx - b.left, b.right - cx)
        val dy = max(cy - b.top, b.bottom - cy)
        return hypot(dx, dy)
    }

    private fun ensureShaderAndPaint() {
        if (background.mode == ThingBackground.Mode.PURE) {
            paint.shader = null
            paint.color = background.color
            return
        }
        val w = bounds.width()
        val h = bounds.height()
        if (cachedShader == null || shaderW != w || shaderH != h) {
            shaderW = w
            shaderH = h
            cachedShader = BackgroundUtil.createLinearGradient(background, w.toFloat(), h.toFloat())
        }
        paint.shader = cachedShader
    }

    override fun jumpToCurrentState() {
        radiusAnimator?.cancel(); radiusAnimator = null
        alphaAnimator?.cancel(); alphaAnimator = null
        if (pressed) {
            radiusFraction = 1f
            alphaFraction = 1f
        } else {
            radiusFraction = 0f
            alphaFraction = 0f
            originX = Float.NaN
            originY = Float.NaN
        }
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        if (drawableAlpha != alpha) {
            drawableAlpha = alpha
            invalidateSelf()
        }
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** 渐变波纹峰值不透明度。鲜艳品牌色作波纹，需高于现有 10–16% 的淡对比色 ripple 才看得清。 */
        private const val PEAK_ALPHA = 0.36f
        /** 半径铺满时长；略短于 alpha 淡出，保证铺满瞬间仍可见。 */
        private const val RADIUS_DURATION = 260L
        /** alpha 升满时长，尽量短，快速点击/滑动也能看到颜色。 */
        private const val ALPHA_ENTER_DURATION = 60L
        /** alpha 淡出时长。 */
        private const val ALPHA_EXIT_DURATION = 300L
        private val RADIUS_INTERPOLATOR = DecelerateInterpolator()
        private val LINEAR = LinearInterpolator()
    }
}
