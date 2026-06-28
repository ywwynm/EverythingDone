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
import android.view.View
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
 * （OVAL 圆形；否则按 [cornerRadiusPx]：>0 用该半径圆角矩形，<0 为胶囊(height/2)，=0 为直角矩形）。
 *
 * [fixedRadiusPx] >0 时，波纹铺满半径固定为该值（不再随控件尺寸取「圆心到最远角」），用于顶栏
 * 图标这类「无边界」按钮：让导航按钮与菜单项波纹一样大，且与系统原生那层尺寸一致，不被各自控件
 * 尺寸撑大。=−1（默认）时按控件铺满，模拟有边界 ripple，适合矩形 item / 胶囊 / 圆形按钮。
 *
 * [centered] = true 时波纹仍从触摸点开始扩散，但圆心在扩散过程中向控件中心迁移、铺满时正好居中
 * （仿系统 ripple 的 origin 收敛），用于顶栏图标这类「图标居中、可点区域比图标大」的按钮，避免点到
 * 边缘时最终波纹偏置。[peakAlphaOverride] ≥0 时用它替代默认 [PEAK_ALPHA]，供「选中态自适应波纹」
 * 直接沿用 [BackgroundUtil.adaptiveRippleColor] 自带的透明度。
 *
 * 写法参照 [com.ywwynm.everythingdone.utils.DisplayUtil] 中的 SeekBarTrackDrawable。
 */
class GradientRippleDrawable(
    background: ThingBackground,
    private val shapeOval: Boolean,
    private val cornerRadiusPx: Float = 0f,
    private val fixedRadiusPx: Float = -1f,
    private val centered: Boolean = false,
    private val peakAlphaOverride: Float = -1f
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

    // 半径一旦开始扩散就跑到满（松手不打断）。alpha 分淡入 / 淡出两段：松手时若淡入还没结束，
    // 先记 pendingExit，等淡入到满再淡出——保证「按一下也完整播放铺满 + 淡出」，不会一点就消失。
    private var radiusAnimator: ValueAnimator? = null
    private var alphaInAnimator: ValueAnimator? = null
    private var alphaOutAnimator: ValueAnimator? = null
    private var pendingExit = false
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
        pendingExit = false
        radiusAnimator?.cancel(); radiusAnimator = null
        alphaInAnimator?.cancel(); alphaInAnimator = null
        alphaOutAnimator?.cancel(); alphaOutAnimator = null
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
        } else if (cornerRadiusPx > 0f) {
            clipPath.addRoundRect(boundsF, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        } else if (cornerRadiusPx < 0f) {
            val r = boundsF.height() / 2f
            clipPath.addRoundRect(boundsF, r, r, Path.Direction.CW)
        } else {
            clipPath.addRect(boundsF, Path.Direction.CW)
        }
    }

    private fun enter() {
        // 先结束可能在跑的淡出（其 onEnd 会复位半径/圆心），再设置本次按下的起点。
        alphaOutAnimator?.cancel(); alphaOutAnimator = null
        // 按下瞬间锁定起始圆心为触摸点（系统在 ACTION_DOWN 时先 setHotspot 再置 pressed，故此处
        // hotspot 已就位），缺失时兜底为中心。centered 模式下圆心会随扩散迁移到控件中心（见 draw）。
        originX = if (hotspotX.isNaN()) bounds.exactCenterX() else hotspotX
        originY = if (hotspotY.isNaN()) bounds.exactCenterY() else hotspotY
        pendingExit = false

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

        // 淡入到满；若已在淡入则不打断。
        if (alphaInAnimator?.isRunning != true) {
            alphaInAnimator = ValueAnimator.ofFloat(alphaFraction, 1f).apply {
                duration = ALPHA_ENTER_DURATION
                interpolator = LINEAR
                addUpdateListener {
                    alphaFraction = it.animatedValue as Float
                    invalidateSelf()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        alphaInAnimator = null
                        // 松手早于淡入结束：此刻已铺满淡入，开始淡出，保证完整播放。
                        if (pendingExit) startExitFade()
                    }
                })
                start()
            }
        }
    }

    private fun exit() {
        // 淡入还没结束就松手：先记下，等淡入满了再淡出，绝不半路截断（确保铺满 + 淡出完整可见）。
        if (alphaInAnimator?.isRunning == true) {
            pendingExit = true
        } else {
            startExitFade()
        }
    }

    private fun startExitFade() {
        pendingExit = false
        alphaOutAnimator?.cancel()
        alphaOutAnimator = ValueAnimator.ofFloat(alphaFraction, 0f).apply {
            duration = ALPHA_EXIT_DURATION
            interpolator = LINEAR
            addUpdateListener {
                alphaFraction = it.animatedValue as Float
                invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    alphaOutAnimator = null
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
        val startX = if (originX.isNaN()) b.exactCenterX() else originX
        val startY = if (originY.isNaN()) b.exactCenterY() else originY
        // centered：圆心随扩散从触摸点迁移到控件中心，铺满（radiusFraction→1）时正好居中。
        val cx = if (centered) startX + (b.exactCenterX() - startX) * radiusFraction else startX
        val cy = if (centered) startY + (b.exactCenterY() - startY) * radiusFraction else startY
        val r = radiusFraction * maxRadius(cx, cy)
        if (r <= 0f) return

        val save = canvas.save()
        canvas.clipPath(clipPath)
        ensureShaderAndPaint()
        val peak = if (peakAlphaOverride >= 0f) peakAlphaOverride else PEAK_ALPHA
        val a = (peak * alphaFraction * (drawableAlpha / 255f) * 255f).roundToInt()
        paint.alpha = a.coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r, paint)
        canvas.restoreToCount(save)
    }

    private fun maxRadius(cx: Float, cy: Float): Float {
        if (fixedRadiusPx > 0f) return fixedRadiusPx
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
        if (pressed) {
            pendingExit = false
            radiusAnimator?.cancel(); radiusAnimator = null
            alphaInAnimator?.cancel(); alphaInAnimator = null
            alphaOutAnimator?.cancel(); alphaOutAnimator = null
            radiusFraction = 1f
            alphaFraction = 1f
            invalidateSelf()
            return
        }
        // 未按下且正在淡出 / 待淡出：保留，让动画播放完，别被框架的 jump 打断。
        if (pendingExit || alphaOutAnimator?.isRunning == true) return
        radiusAnimator?.cancel(); radiusAnimator = null
        alphaInAnimator?.cancel(); alphaInAnimator = null
        alphaOutAnimator?.cancel(); alphaOutAnimator = null
        radiusFraction = 0f
        alphaFraction = 0f
        originX = Float.NaN
        originY = Float.NaN
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
        /**
         * 「确定 / 取消」类对话框按钮的触摸 ripple：胶囊形（与系统取消按钮的胶囊一致），颜色与按钮
         * 文本同源的强调 [ThingBackground]；[bg] 为 null 时退化为 [fallbackColor] 纯色。
         */
        @JvmStatic
        fun applyAccentRipple(view: View, bg: ThingBackground?, fallbackColor: Int) {
            applyAccentRippleShaped(view, bg, fallbackColor, cornerRadiusPx = -1f)
        }

        /** 列表行 / 整块 item 的触摸 ripple：直角矩形，颜色取自强调 [bg]（null → [fallbackColor] 纯色）。 */
        @JvmStatic
        fun applyAccentRowRipple(view: View, bg: ThingBackground?, fallbackColor: Int) {
            applyAccentRippleShaped(view, bg, fallbackColor, cornerRadiusPx = 0f)
        }

        private fun applyAccentRippleShaped(
            view: View, bg: ThingBackground?, fallbackColor: Int, cornerRadiusPx: Float
        ) {
            val b = bg ?: ThingBackground.pure(fallbackColor)
            // 复用已有同款实例，仅换色：列表重绑（notifyDataSetChanged）时不丢正在播放的波纹动画。
            val existing = view.background as? GradientRippleDrawable
            if (existing != null) {
                existing.updateBackground(b)
            } else {
                view.background = GradientRippleDrawable(b, shapeOval = false, cornerRadiusPx = cornerRadiusPx)
            }
        }

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
