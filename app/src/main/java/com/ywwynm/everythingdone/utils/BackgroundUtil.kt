@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.utils

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.ViewTreeObserver
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import android.widget.TextView
import android.widget.ProgressBar
import androidx.annotation.Keep
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat

import androidx.cardview.widget.CardView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import kotlin.math.ceil
import java.util.WeakHashMap

/**
 * Algorithmic helpers for deriving colors and brightness-aware foreground choices
 * from an arbitrary background color.
 *
 * Replaces the palette-table-lookup approach in
 * [DisplayUtil.getLightColor] /
 * [DisplayUtil.getDarkColor] so the app can
 * accept any thing color, not just the 10 fixed palette entries.
 *
 * Phase 1 of the color-system migration — see
 * docs/features/color-system-migration/plan.md.
 *
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 */
object BackgroundUtil {
    private const val MUTED_SURFACE_ACCENT_LIGHT = 0.05f
    private const val MUTED_SURFACE_ACCENT_DARK = 0.08f
    private val textShaderTokens = WeakHashMap<TextView, Int>()

    enum class CompoundDrawableGradientMode {
        NONE,
        SEPARATE,
        COMBINED
    }

    private interface PreTintedGradientDrawable

    // ---------------------------------------------------------------------------
    // Hue buckets — used by search-by-similar-color. See
    // docs/features/color-system-migration/plan.md section 4.5. Values are
    // stable ints persisted/transmitted via Intent / DAO calls; do not reorder.
    // ---------------------------------------------------------------------------
    const val HUE_BUCKET_NONE: Int   = 0 // sentinel: no filter
    const val HUE_BUCKET_RED: Int    = 1
    const val HUE_BUCKET_ORANGE: Int = 2
    const val HUE_BUCKET_YELLOW: Int = 3
    const val HUE_BUCKET_GREEN: Int  = 4
    const val HUE_BUCKET_CYAN: Int   = 5
    const val HUE_BUCKET_BLUE: Int   = 6
    const val HUE_BUCKET_PURPLE: Int = 7
    const val HUE_BUCKET_GREY: Int   = 8

    /**
     * Classify `color` into one of the `HUE_BUCKET_*` buckets.
     * Saturation below 0.15 maps to [HUE_BUCKET_GREY] regardless of hue.
     * Hue ranges (HSL degrees):
     *   red    345-360, 0-15
     *   orange 15-45
     *   yellow 45-70
     *   green  70-165
     *   cyan   165-195
     *   blue   195-255
     *   purple 255-345 (includes magenta)
     */
    @JvmStatic
    fun hueBucket(color: Int): Int {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(color, hsl)
        val h: Float = hsl[0]
        val s: Float = hsl[1]
        if (s < 0.15f) return HUE_BUCKET_GREY
        if (h !in 15f..<345f) return HUE_BUCKET_RED
        if (h < 45f)              return HUE_BUCKET_ORANGE
        if (h < 70f)              return HUE_BUCKET_YELLOW
        if (h < 165f)             return HUE_BUCKET_GREEN
        if (h < 195f)             return HUE_BUCKET_CYAN
        if (h < 255f)             return HUE_BUCKET_BLUE
        return HUE_BUCKET_PURPLE
    }

    /** Hue bucket for the representative colour of a [ThingBackground]. */
    @JvmStatic
    fun hueBucket(bg: ThingBackground?): Int {
        if (bg == null) return HUE_BUCKET_NONE
        return hueBucket(bg.representativeColor())
    }

    /**
     * Whether `bg` should match a search for hue `bucket`. PURE
     * matches when its one colour falls into that bucket. GRADIENT matches
     * when EITHER stop's bucket equals `bucket` — a red→blue gradient
     * shows up under both the red and the blue search. Sentinel
     * [HUE_BUCKET_NONE] (= "no filter") always matches.
     */
    @JvmStatic
    fun matchesHueBucket(bg: ThingBackground?, bucket: Int): Boolean {
        if (bucket == HUE_BUCKET_NONE) return true
        if (bg == null) return false
        if (hueBucket(bg.color) == bucket) return true
        return bg.mode === ThingBackground.Mode.GRADIENT
                && hueBucket(bg.endColor) == bucket
    }

    /** Standard "on" alpha tiers — match the existing white_*p / black_*p resources. */
    const val ON_ALPHA_PRIMARY: Float   = 0.86f
    const val ON_ALPHA_SECONDARY: Float = 0.76f
    const val ON_ALPHA_TERTIARY: Float  = 0.66f
    const val ON_ALPHA_DISABLED: Float  = 0.54f

    /**
     * Whether `background` counts as "light" — meaning it should be paired
     * with a dark (black-side) foreground rather than a light (white-side) one.
     * Uses Rec. 601 perceived-luminance with threshold 150, matching the
     * Everything-Android Kotlin reference implementation.
     */
    @JvmStatic
    fun isLight(background: Int): Boolean {
        val r: Int = Color.red(background)
        val g: Int = Color.green(background)
        val b: Int = Color.blue(background)
        return r * 0.299 + g * 0.587 + b * 0.114 > 150
    }

    /**
     * 与 [isLight] 相同的明暗判断，但接收完整的 [ThingBackground]。当背景是 App 默认强调
     * 渐变（accent + accent2）时，无论其代表色亮度如何都按"深色背景"处理（返回 false），让
     * 前景统一走偏白的一侧——该渐变代表色亮度恰好略高于阈值会被误判为浅色，但视觉上更适合
     * 白色前景。其余背景仍按代表色亮度判断。
     *
     * 凡是"根据颜色/渐变背景自适应前景偏白或偏黑"的地方都应优先调用本重载（而不是先取
     * [ThingBackground.representativeColor] 再调 [isLight]），否则渐变两端信息已被抹平、无法
     * 识别 accent 渐变。
     */
    @JvmStatic
    fun isLight(background: ThingBackground): Boolean {
        if (isAccentGradient(background)) return false
        return isLight(background.representativeColor())
    }

    /** 该背景是否为 App 默认强调渐变（accent ↔ accent2 两色，起止顺序不限）。 */
    @JvmStatic
    fun isAccentGradient(background: ThingBackground): Boolean {
        if (background.mode !== ThingBackground.Mode.GRADIENT) return false
        val accent = App.defaultAccentBackground
        return (background.color == accent.color && background.endColor == accent.endColor) ||
                (background.color == accent.endColor && background.endColor == accent.color)
    }

    /**
     * Foreground color to draw on top of `background`, with the requested
     * `alpha` (0~1). Returns a black-tinted color on light backgrounds and
     * a white-tinted color on dark backgrounds.
     */
    @JvmStatic
    fun onColor(background: Int, alpha: Float): Int {
        val rgb: Int = if (isLight(background)) 0x000000 else 0xFFFFFF
        val a: Int   = Math.round(clamp01(alpha) * 255f)
        return (a shl 24) or rgb
    }

    /** [onColor] 的 [ThingBackground] 版：accent 渐变按深色背景处理，前景走白。 */
    @JvmStatic
    fun onColor(background: ThingBackground, alpha: Float): Int {
        val rgb: Int = if (isLight(background)) 0x000000 else 0xFFFFFF
        val a: Int   = Math.round(clamp01(alpha) * 255f)
        return (a shl 24) or rgb
    }

    /** Linearly blend `color` toward white. amount=0 keeps color; amount=1 returns white. */
    @JvmStatic
    fun lighter(color: Int, amount: Float): Int {
        return blendWith(color, 0xFFFFFF, amount)
    }

    /** Linearly blend `color` toward black. amount=0 keeps color; amount=1 returns black. */
    @JvmStatic
    fun darker(color: Int, amount: Float): Int {
        return blendWith(color, 0x000000, amount)
    }

    /**
     * Build a Folder-tinted surface that still reads as the surrounding list
     * surface. Used where a transparent-looking Folder card needs an opaque
     * fill to cover native elevation shadow.
     */
    @JvmStatic
    fun mutedSurfaceBackground(
        background: ThingBackground?,
        surfaceColor: Int
    ): ThingBackground {
        val bg = background ?: return ThingBackground.pure(surfaceColor)
        val accentAmount = if (isLight(surfaceColor)) {
            MUTED_SURFACE_ACCENT_LIGHT
        } else {
            MUTED_SURFACE_ACCENT_DARK
        }
        val start = blendColors(surfaceColor, bg.color, accentAmount)
        if (bg.mode === ThingBackground.Mode.PURE) {
            return ThingBackground.pure(start)
        }
        val end = blendColors(surfaceColor, bg.endColor, accentAmount)
        return ThingBackground.gradient(start, end, bg.orientation)
    }

    private fun blendWith(color: Int, targetRgb: Int, amount: Float): Int {
        return blendColors(
            color,
            Color.rgb(
                (targetRgb shr 16) and 0xFF,
                (targetRgb shr 8) and 0xFF,
                targetRgb and 0xFF
            ),
            amount
        )
    }

    private fun blendColors(from: Int, to: Int, amount: Float): Int {
        val a: Float = clamp01(amount)
        val r: Int = Color.red(from)
        val g: Int = Color.green(from)
        val b: Int = Color.blue(from)
        val tr: Int = Color.red(to)
        val tg: Int = Color.green(to)
        val tb: Int = Color.blue(to)
        val nr: Int = Math.round(r * (1 - a) + tr * a)
        val ng: Int = Math.round(g * (1 - a) + tg * a)
        val nb: Int = Math.round(b * (1 - a) + tb * a)
        return Color.rgb(nr, ng, nb)
    }

    private fun clamp01(v: Float): Float {
        if (v < 0f) return 0f
        if (v > 1f) return 1f
        return v
    }

    // ---------------------------------------------------------------------------
    // ThingBackground painters
    // ---------------------------------------------------------------------------

    /**
     * Paint `background` onto `view`.
     * PURE: regular `setBackgroundColor`. GRADIENT: a reusable
     * [GradientDrawable] — the same instance is mutated on subsequent calls so
     * the view doesn't churn drawables (matters for the colour-change animation in
     * DetailActivity).
     */
    @JvmStatic
    fun applyBackground(view: View?, background: ThingBackground?) {
        if (view == null || background == null) return
        if (background.mode === ThingBackground.Mode.PURE) {
            // Pure: defer to setBackgroundColor, which uses a cheap ColorDrawable.
            view.setBackgroundColor(background.color)
        } else {
            val gd: GradientDrawable = obtainGradient(view)
            gd.setOrientation(toGdOrientation(background.orientation))
            gd.colors = intArrayOf(background.color, background.endColor)
        }
    }

    @JvmStatic
    fun applyOvalBackground(view: View?, background: ThingBackground?) {
        if (view == null || background == null) return
        val gd: GradientDrawable = obtainGradient(view)
        gd.setShape(GradientDrawable.OVAL)
        if (background.mode === ThingBackground.Mode.PURE) {
            gd.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT)
            gd.colors = intArrayOf(background.color, background.color)
        } else {
            gd.setOrientation(toGdOrientation(background.orientation))
            gd.colors = intArrayOf(background.color, background.endColor)
        }
    }

    /**
     * Paint `background` onto a [CardView].
     *
     * Both PURE and GRADIENT route through [View.setBackground]
     * so `cv.getBackground()` is replaced on every bind — this avoids the
     * "stale GradientDrawable left over from a recycled ViewHolder" bug that
     * showed up when we used [CardView.setCardBackgroundColor] for PURE
     * after a GRADIENT bind (CardView only updates its internal drawable, not
     * the View.background field, so the stale GradientDrawable kept rendering).
     *
     * CardView's rounded-corner clipping and elevation shadow are driven by
     * the view's background outline on API 21+. Keep the runtime drawable's
     * corner radius in sync with [CardView.getRadius], otherwise replacing
     * CardView's internal round-rect background silently makes the card square.
     */
    @JvmStatic
    fun applyCardBackground(cv: CardView?, background: ThingBackground?) {
        if (cv == null || background == null) return

        val gd: GradientDrawable
        val existing: Drawable? = cv.background
        if (existing is GradientDrawable) {
            // Reuse the existing instance (cheaper) and just mutate its colours.
            gd = existing
        } else {
            gd = GradientDrawable()
            gd.setShape(GradientDrawable.RECTANGLE)
            cv.background = gd
        }
        gd.cornerRadius = cv.radius

        if (background.mode === ThingBackground.Mode.PURE) {
            // Use identical stops instead of setBackgroundColor so recycled
            // pure/gradient binds all keep the same rounded drawable shape.
            gd.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT)
            gd.colors = intArrayOf(background.color, background.color)
        } else {
            gd.setOrientation(toGdOrientation(background.orientation))
            gd.colors = intArrayOf(background.color, background.endColor)
        }
    }

    private fun obtainGradient(view: View): GradientDrawable {
        val existing: Drawable? = view.background
        if (existing is GradientDrawable) {
            return existing
        }
        val gd = GradientDrawable()
        gd.setShape(GradientDrawable.RECTANGLE)
        view.background = gd
        return gd
    }

    /**
     * Animate `view`'s background from `from` to `to` over
     * `duration` ms by running two [ArgbEvaluator]s in lock-step over
     * the start and end colors. Orientation snaps to `to.orientation` at
     * the midpoint (cleaner than trying to interpolate enum directions).
     *
     * When both endpoints stay equal during a given frame (i.e. PURE-PURE
     * animation), we fall back to [View.setBackgroundColor] so the
     * view keeps using a cheap ColorDrawable and other view code that casts
     * to ColorDrawable continues to work.
     */
    @JvmStatic
    fun animateBackground(
            view: View?, from: ThingBackground?, to: ThingBackground?, duration: Long): ValueAnimator? {
        if (view == null || from == null || to == null) return null
        val eval = ArgbEvaluator()
        val fStart: Int = from.color
        val fEnd: Int   = from.endColor
        val tStart: Int = to.color
        val tEnd: Int   = to.endColor
        val oFrom: ThingBackground.Orientation = from.orientation
        val oTo: ThingBackground.Orientation   = to.orientation

        val anim: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)
        anim.setDuration(duration)
        anim.addUpdateListener { a: ValueAnimator ->
            val f: Float = a.getAnimatedValue() as Float
            val s: Int = eval.evaluate(f, fStart, tStart) as Int
            val e: Int = eval.evaluate(f, fEnd,   tEnd) as Int
            if (s == e) {
                view.setBackgroundColor(s)
            } else {
                val o: ThingBackground.Orientation = if (f < 0.5f) oFrom else oTo
                applyBackground(view, ThingBackground.gradient(s, e, o))
            }
        }
        anim.start()
        return anim
    }

    /**
     * Apply `background` as the ink fill of `textView`'s glyphs.
     *
     * PURE: regular [TextView.setTextColor] + clears any shader
     * left over from a previous GRADIENT bind.
     *
     * GRADIENT: installs a [LinearGradient] on the TextView's
     * [android.text.TextPaint]. Paint shaders fill glyph masks during
     * drawing, so each character is coloured by the gradient.
     *
     * Key subtlety: the shader spans the entire `textView` bounding box
     * by default, but typical dialog labels are short text centred in a wide
     * TextView — the visible glyphs would only sample a thin middle slice of the
     * gradient and look uniform. We therefore measure the actual text width and
     * translate the shader so the gradient endpoints align with the text's left
     * and right edges (accounting for the TextView's gravity-driven offset).
     * The shader is applied after layout (via a one-shot
     * [ViewTreeObserver.OnPreDrawListener]) when the view's measured size
     * and rendered text layout are settled. Before that first shader pass, the
     * TextView uses the gradient start colour as a temporary base colour.
     */
    @JvmStatic
    @JvmOverloads
    fun applyTextBackground(
        textView: TextView?,
        background: ThingBackground?,
        compoundDrawableMode: CompoundDrawableGradientMode =
            CompoundDrawableGradientMode.SEPARATE
    ) {
        if (textView == null || background == null) return

        if (background.mode === ThingBackground.Mode.PURE) {
            invalidatePendingTextShader(textView)
            val paint: android.text.TextPaint = textView.paint
            if (paint.shader != null) paint.setShader(null)
            textView.setTextColor(background.color)
            applyCompoundDrawableBackground(textView, background, compoundDrawableMode, null)
            textView.invalidate()
            return
        }

        // 首帧布局完成前先使用起始色，避免短暂回落到代表色。
        textView.setTextColor(background.color)

        val token = nextTextShaderToken(textView)
        // 等待最终 text/layout/compound drawable 状态稳定后再计算 shader。
        textView.getViewTreeObserver().addOnPreDrawListener(
                object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        textView.getViewTreeObserver().removeOnPreDrawListener(this)
                        if (textShaderTokens[textView] == token) {
                            applyTextShaderNow(
                                textView,
                                background,
                                compoundDrawableMode
                            )
                        }
                        return true
                    }
                })
    }

    private fun nextTextShaderToken(textView: TextView): Int {
        val next = (textShaderTokens[textView] ?: 0) + 1
        textShaderTokens[textView] = next
        return next
    }

    private fun invalidatePendingTextShader(textView: TextView) {
        textShaderTokens[textView] = (textShaderTokens[textView] ?: 0) + 1
    }

    /** Build a text-width-fitted LinearGradient and install it on the TextView's paint. */
    private fun applyTextShaderNow(
        textView: TextView,
        bg: ThingBackground,
        compoundDrawableMode: CompoundDrawableGradientMode
    ) {
        val text: CharSequence? = textView.getText()
        if (text.isNullOrEmpty()) return
        val viewW: Int = textView.width
        val viewH: Int = textView.height
        if (viewW <= 0 || viewH <= 0) return

        // TextView 绘制文字前已平移到 compoundPaddingLeft；
        // 这里必须使用 Layout 内部坐标，不能叠加外层 view padding。
        val layout: android.text.Layout? = textView.layout
        var textW: Float
        var textH: Float
        var textLeft: Float
        var textTop: Float
        if (layout != null && layout.lineCount > 0) {
            val lineLeft: Float   = layout.getLineLeft(0)
            val lineRight: Float  = layout.getLineRight(0)
            val lineTop: Int      = layout.getLineTop(0)
            val lineBottom: Int   = layout.getLineBottom(0)
            textW = lineRight - lineLeft
            textH = (lineBottom - lineTop).toFloat()
            textLeft = lineLeft
            textTop = lineTop.toFloat()
        } else {
            // 理论上 pre-draw 时已有 Layout；这里保留兜底估算。
            textW = textView.paint.measureText(text, 0, text.length)
            textH = textView.paint.fontSpacing
            textLeft = 0f
            textTop = 0f
        }
        if (textW <= 0) textW = viewW.toFloat()
        if (textH <= 0) textH = viewH.toFloat()

        val textSpan = GradientSpan(textLeft, textTop, textLeft + textW, textTop + textH)
        val shaderSpan = if (compoundDrawableMode == CompoundDrawableGradientMode.COMBINED) {
            compoundTextSpan(textView, textSpan)
        } else {
            textSpan
        }
        applyCompoundDrawableBackground(textView, bg, compoundDrawableMode, shaderSpan)

        val lg: LinearGradient = linearGradientFor(bg, shaderSpan.width, shaderSpan.height)
        val m = Matrix()
        m.setTranslate(shaderSpan.left, shaderSpan.top)
        lg.setLocalMatrix(m)
        textView.paint.setShader(lg)
        textView.invalidate()
    }

    private data class GradientSpan(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = (right - left).coerceAtLeast(1f)
        val height: Float get() = (bottom - top).coerceAtLeast(1f)
    }

    private data class DrawableSlot(
        val index: Int,
        val drawable: Drawable,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    ) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
    }

    private fun compoundTextSpan(textView: TextView, textSpan: GradientSpan): GradientSpan {
        var left = textSpan.left
        var top = textSpan.top
        var right = textSpan.right
        var bottom = textSpan.bottom
        for (slot in compoundDrawableSlots(textView, textSpan)) {
            left = kotlin.math.min(left, slot.left)
            top = kotlin.math.min(top, slot.top)
            right = kotlin.math.max(right, slot.right)
            bottom = kotlin.math.max(bottom, slot.bottom)
        }
        return GradientSpan(left, top, right, bottom)
    }

    private fun compoundDrawableSlots(
        textView: TextView,
        textSpan: GradientSpan
    ): List<DrawableSlot> {
        val drawables = textView.compoundDrawablesRelative
        val padding = textView.compoundDrawablePadding.toFloat()
        val textCenterX = (textSpan.left + textSpan.right) / 2f
        val textCenterY = (textSpan.top + textSpan.bottom) / 2f
        val slots = ArrayList<DrawableSlot>(4)

        fun add(index: Int, left: Float, top: Float) {
            val drawable = drawables[index] ?: return
            val w = drawableRenderWidth(drawable).toFloat()
            val h = drawableRenderHeight(drawable).toFloat()
            if (w <= 0f || h <= 0f) return
            slots.add(DrawableSlot(index, drawable, left, top, w, h))
        }

        drawables[0]?.let {
            val w = drawableRenderWidth(it).toFloat()
            val h = drawableRenderHeight(it).toFloat()
            add(0, textSpan.left - padding - w, textCenterY - h / 2f)
        }
        drawables[2]?.let {
            val h = drawableRenderHeight(it).toFloat()
            add(2, textSpan.right + padding, textCenterY - h / 2f)
        }
        drawables[1]?.let {
            val w = drawableRenderWidth(it).toFloat()
            val h = drawableRenderHeight(it).toFloat()
            add(1, textCenterX - w / 2f, textSpan.top - padding - h)
        }
        drawables[3]?.let {
            val w = drawableRenderWidth(it).toFloat()
            add(3, textCenterX - w / 2f, textSpan.bottom + padding)
        }

        return slots
    }

    private fun applyCompoundDrawableBackground(
        textView: TextView,
        bg: ThingBackground,
        mode: CompoundDrawableGradientMode,
        shaderSpan: GradientSpan?
    ) {
        if (mode == CompoundDrawableGradientMode.NONE) return
        val textSpan = shaderSpan ?: GradientSpan(0f, 0f, 1f, 1f)
        val drawables = textView.compoundDrawablesRelative
        val slots = compoundDrawableSlots(textView, textSpan)
        if (slots.isEmpty()) return

        val tinted = arrayOfNulls<Drawable>(4)
        for (i in drawables.indices) {
            tinted[i] = drawables[i]
        }
        for (slot in slots) {
            val source = slot.drawable
            val target = if (source is PreTintedGradientDrawable) {
                source
            } else if (mode == CompoundDrawableGradientMode.COMBINED && shaderSpan != null) {
                tintDrawableInGradientSpan(
                    textView.resources,
                    source,
                    bg,
                    shaderSpan,
                    slot
                )
            } else {
                tintDrawable(textView.resources, source, bg)
            }
            ensureDrawableBounds(target, slot.width.toInt(), slot.height.toInt())
            tinted[slot.index] = target
        }
        textView.setCompoundDrawablesRelative(tinted[0], tinted[1], tinted[2], tinted[3])
    }

    private fun tintDrawableInGradientSpan(
        res: android.content.res.Resources?,
        source: Drawable,
        bg: ThingBackground,
        span: GradientSpan,
        slot: DrawableSlot
    ): Drawable {
        if (bg.mode === ThingBackground.Mode.PURE) {
            val d = source.mutate()
            d.setColorFilter(bg.color, PorterDuff.Mode.SRC_ATOP)
            return d
        }

        val w = slot.width.toInt().coerceAtLeast(1)
        val h = slot.height.toInt().coerceAtLeast(1)
        val out = android.graphics.Bitmap.createBitmap(
            w, h, android.graphics.Bitmap.Config.ARGB_8888
        )
        val c = android.graphics.Canvas(out)
        val mask = source.mutate()
        mask.colorFilter = null
        mask.setBounds(0, 0, w, h)
        mask.draw(c)
        normalizeMaskAlpha(out)

        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = linearGradientFor(bg, span.width, span.height).also {
            val m = Matrix()
            m.setTranslate(-(slot.left - span.left), -(slot.top - span.top))
            it.setLocalMatrix(m)
        }
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)

        return android.graphics.drawable.BitmapDrawable(res, out)
    }

    private fun drawableRenderWidth(drawable: Drawable): Int {
        val bounds = drawable.bounds
        if (!bounds.isEmpty && bounds.width() > 0) return bounds.width()
        return drawable.intrinsicWidth.takeIf { it > 0 } ?: 0
    }

    private fun drawableRenderHeight(drawable: Drawable): Int {
        val bounds = drawable.bounds
        if (!bounds.isEmpty && bounds.height() > 0) return bounds.height()
        return drawable.intrinsicHeight.takeIf { it > 0 } ?: 0
    }

    private fun ensureDrawableBounds(drawable: Drawable?, width: Int, height: Int) {
        if (drawable == null) return
        val w = width.takeIf { it > 0 } ?: drawable.intrinsicWidth
        val h = height.takeIf { it > 0 } ?: drawable.intrinsicHeight
        if (w > 0 && h > 0 && drawable.bounds.isEmpty) {
            drawable.setBounds(0, 0, w, h)
        }
    }

    @JvmStatic
    fun createLinearGradient(bg: ThingBackground, width: Float, height: Float): LinearGradient {
        return linearGradientFor(bg, width, height)
    }

    private fun linearGradientFor(bg: ThingBackground, width: Float, height: Float): LinearGradient {
        val x0: Float
        val y0: Float
        val x1: Float
        val y1: Float
        when (bg.orientation) {
            ThingBackground.Orientation.L_R   -> { x0 = 0f;     y0 = 0f;      x1 = width; y1 = 0f }
            ThingBackground.Orientation.T_B   -> { x0 = 0f;     y0 = 0f;      x1 = 0f;    y1 = height }
            ThingBackground.Orientation.LT_RB -> { x0 = 0f;     y0 = 0f;      x1 = width; y1 = height }
            ThingBackground.Orientation.RT_LB -> { x0 = width;  y0 = 0f;      x1 = 0f;    y1 = height }
            ThingBackground.Orientation.LB_RT -> { x0 = 0f;     y0 = height;  x1 = width; y1 = 0f }
            ThingBackground.Orientation.RB_LT -> { x0 = width;  y0 = height;  x1 = 0f;    y1 = 0f }
            ThingBackground.Orientation.R_L   -> { x0 = width;  y0 = 0f;      x1 = 0f;    y1 = 0f }
            ThingBackground.Orientation.B_T   -> { x0 = 0f;     y0 = height;  x1 = 0f;    y1 = 0f }
        }
        return LinearGradient(
                x0, y0, x1, y1,
                bg.color, bg.endColor, Shader.TileMode.CLAMP)
    }

    /**
     * Tint `source` with `bg`. PURE → mutated original with a
     * solid [android.graphics.PorterDuff.Mode.SRC_ATOP] colour filter
     * (cheap, in-place). GRADIENT → render the drawable to an offscreen bitmap
     * as an alpha mask, then fill that mask with a [LinearGradient] via
     * `SRC_IN`, and wrap the result in a [android.graphics.drawable.BitmapDrawable].
     *
     * Caller gets a fresh drawable for GRADIENT (safe to setBounds / install
     * via `setCompoundDrawables`); the original `source` is left
     * untouched in that branch. Use this anywhere a single-tone icon needs to
     * adopt the accent — radio check, lock, small UI glyphs, etc.
     */
    @JvmStatic
    fun tintDrawable(
            res: android.content.res.Resources?, source: Drawable?, bg: ThingBackground?): Drawable? {
        if (source == null || bg == null) return source
        if (bg.mode === ThingBackground.Mode.PURE) {
            val d: Drawable = source.mutate()
            d.setColorFilter(bg.color, android.graphics.PorterDuff.Mode.SRC_ATOP)
            return d
        }
        var w: Int = source.intrinsicWidth
        var h: Int = source.intrinsicHeight
        if (w <= 0) w = 48
        if (h <= 0) h = 48

        val out: android.graphics.Bitmap = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c: android.graphics.Canvas = android.graphics.Canvas(out)

        // 1. Draw the icon onto the bitmap with no tint so we get its alpha mask
        //    (drawable's own paint draws in its native colour — usually opaque
        //    black for these single-tone vector glyphs).
        val mask: Drawable = source.mutate()
        mask.setBounds(0, 0, w, h)
        // Ensure no leftover filter from a previous tint.
        mask.colorFilter = null
        mask.draw(c)

        normalizeMaskAlpha(out)

        // 2. Overlay a rect filled with the gradient, masked by what's already
        //    there. SRC_IN keeps the destination's alpha (the icon shape) and
        //    replaces its colour with the source (the gradient).
        val p: android.graphics.Paint = android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.setShader(linearGradientFor(bg, w.toFloat(), h.toFloat()))
        p.xfermode = android.graphics.PorterDuffXfermode(
            android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)

        return android.graphics.drawable.BitmapDrawable(res, out)
    }

    @JvmStatic
    fun tintDrawableOpaque(
            res: android.content.res.Resources?, source: Drawable?, bg: ThingBackground?): Drawable? {
        if (source == null || bg == null) return source
        var w: Int = source.intrinsicWidth
        var h: Int = source.intrinsicHeight
        if (w <= 0) w = 48
        if (h <= 0) h = 48

        val out = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val mask = source.mutate()
        mask.setBounds(0, 0, w, h)
        mask.colorFilter = null
        mask.alpha = 255
        mask.draw(c)
        forceOpaqueMaskAlpha(out)

        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        if (bg.mode === ThingBackground.Mode.GRADIENT) {
            p.shader = linearGradientFor(bg, w.toFloat(), h.toFloat())
        } else {
            p.color = bg.color or -0x1000000
        }
        p.xfermode = android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)

        return android.graphics.drawable.BitmapDrawable(res, out).also {
            it.setBounds(0, 0, w, h)
        }
    }

    private fun normalizeMaskAlpha(bitmap: android.graphics.Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var maxAlpha = 0
        for (pixel in pixels) {
            maxAlpha = kotlin.math.max(maxAlpha, Color.alpha(pixel))
        }
        if (maxAlpha == 0 || maxAlpha == 255) return

        for (i in pixels.indices) {
            val alpha = Color.alpha(pixels[i])
            pixels[i] = if (alpha == 0) {
                Color.TRANSPARENT
            } else {
                val scaledAlpha = (alpha * 255 + maxAlpha / 2) / maxAlpha
                Color.argb(scaledAlpha.coerceAtMost(255), 0, 0, 0)
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun forceOpaqueMaskAlpha(bitmap: android.graphics.Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            pixels[i] = if (Color.alpha(pixels[i]) == 0) {
                Color.TRANSPARENT
            } else {
                Color.argb(255, 0, 0, 0)
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    class GradientStrokeDrawable(
        private val background: ThingBackground,
        var cornerRadius: Float,
        strokeWidthPx: Float,
        private val fillColor: Int = Color.TRANSPARENT
    ) : Drawable() {

        var strokeWidthPx: Float = strokeWidthPx
            set(value) {
                field = value
                invalidateSelf()
            }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val rect = RectF()
        private var externalAlpha = 255

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            if (Color.alpha(fillColor) > 0) {
                fillPaint.alpha = (externalAlpha * Color.alpha(fillColor) / 255f).toInt()
                rect.set(bounds)
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
            }

            val stroke = strokeWidthPx
            if (stroke <= 0f) return
            strokePaint.strokeWidth = stroke
            strokePaint.alpha = externalAlpha
            if (background.mode === ThingBackground.Mode.GRADIENT) {
                val shader = createLinearGradient(
                        background,
                        bounds.width().coerceAtLeast(1).toFloat(),
                        bounds.height().coerceAtLeast(1).toFloat()
                )
                val matrix = Matrix()
                matrix.setTranslate(bounds.left.toFloat(), bounds.top.toFloat())
                shader.setLocalMatrix(matrix)
                strokePaint.shader = shader
            } else {
                strokePaint.shader = null
                strokePaint.color = background.color
            }
            rect.set(bounds)
            rect.inset(stroke / 2f, stroke / 2f)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
            strokePaint.shader = null
        }

        override fun setAlpha(alpha: Int) {
            externalAlpha = alpha.coerceIn(0, 255)
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * Paint a coloured underline strip across `editText`'s bottom edge
     * that adopts `bg`'s stops (PURE or GRADIENT).
     *
     * Installed as the EditText's [View.setForeground] foreground,
     * not its background — so the platform's native EditText padding / hit
     * area / IME positioning stay unchanged, and `DisplayUtil.tintView`'s
     * tint on the background underline gets visually overlaid when the
     * EditText is focused.
     *
     * To remove the gradient (e.g. on blur), pass `null` —
     * [clearEditTextUnderline] does the same.
     *
     * The strip is drawn by a tiny inner [Drawable] that re-positions
     * the line layer to the bottom of its bounds on every `onBoundsChange`,
     * so it stays anchored to the EditText's bottom edge even after layout
     * changes.
     */
    /** Material AppCompat EditText state-transition fade duration (ms). The
     *  native StateListDrawable selector uses ~150ms for activated/default
     *  swaps; we match it for the foreground gradient strip so focus
     *  transitions feel consistent. */
    private const val EDIT_TEXT_UNDERLINE_FADE_MS: Int = 150

    @JvmStatic
    fun applyEditTextUnderline(editText: android.widget.EditText?, bg: ThingBackground?) {
        if (editText == null) return
        if (bg == null) {
            clearEditTextUnderline(editText)
            return
        }
        val strokeHeightPx: Int = Math.max(1,
                ceil((editText.resources.displayMetrics.density * 2).toDouble()).toInt())
        val line = GradientDrawable()
        line.setShape(GradientDrawable.RECTANGLE)
        if (bg.mode === ThingBackground.Mode.GRADIENT) {
            line.setOrientation(toGdOrientation(bg.orientation))
            line.colors = intArrayOf(bg.color, bg.endColor)
        } else {
            line.setColor(bg.color)
        }
        // Align with native underline geometry by reading the EditText's
        // InsetDrawable child bounds — those are exactly the rectangle the
        // 9-patch underline occupies, so length + vertical position match
        // pixel-for-pixel. Falls back to paddingBottom if the background
        // isn't an InsetDrawable (custom EditText themes).
        val d = BottomLineDrawable(editText, line, strokeHeightPx)
        // Cancel any in-flight fade so the new bg appears immediately at full opacity.
        cancelUnderlineFade(editText)
        d.setAlpha(0)
        editText.setForeground(d)
        // Fade in to match Material EditText's state-transition animation.
        val anim: android.animation.ObjectAnimator = android.animation.ObjectAnimator.ofInt(
                d, "alpha", 0, 255)
        anim.setDuration(EDIT_TEXT_UNDERLINE_FADE_MS.toLong())
        editText.setTag(R_ID_UNDERLINE_ANIM, anim)
        anim.start()
    }

    /** Counterpart to [applyEditTextUnderline] — fades the foreground
     *  strip out and clears it, matching native EditText's transition timing. */
    @JvmStatic
    fun clearEditTextUnderline(editText: android.widget.EditText?) {
        if (editText == null) return
        val fg: Drawable? = editText.foreground
        cancelUnderlineFade(editText)
        if (fg !is BottomLineDrawable) {
            editText.setForeground(null)
            return
        }
        val existing: Drawable = fg
        val anim: android.animation.ObjectAnimator = android.animation.ObjectAnimator.ofInt(
                existing, "alpha", existing.alpha, 0)
        anim.setDuration(EDIT_TEXT_UNDERLINE_FADE_MS.toLong())
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) {
                // Only clear if the foreground is still our drawable — a
                // newer applyEditTextUnderline() might have replaced it
                // while this fade was running.
                if (editText.foreground === existing) {
                    editText.setForeground(null)
                }
            }
        })
        editText.setTag(R_ID_UNDERLINE_ANIM, anim)
        anim.start()
    }

    /** Tag id used to stash the in-flight underline fade Animator on an
     *  EditText, so a state change can cancel the previous fade before
     *  starting a new one. R.id.* would be cleaner but this util mustn't
     *  depend on app R — we use a stable hash of a unique key string. */
    private val R_ID_UNDERLINE_ANIM: Int =
            "BackgroundUtil#underlineFadeAnim".hashCode()

    private fun cancelUnderlineFade(editText: android.widget.EditText) {
        val tag: Any? = editText.getTag(R_ID_UNDERLINE_ANIM)
        if (tag is android.animation.ObjectAnimator) {
            tag.cancel()
        }
        editText.setTag(R_ID_UNDERLINE_ANIM, null)
    }

    /** Renders `inner` as a thin horizontal strip pixel-aligned with the
     *  host EditText's native 9-patch underline. Position is recomputed on
     *  every bounds change by inspecting the EditText's background
     *  [android.graphics.drawable.InsetDrawable] — its child's bounds
     *  are exactly the rectangle the native underline draws into, so width
     *  + bottom edge match exactly. Falls back to the view bottom edge if
     *  the background isn't an InsetDrawable (themed / custom EditText). */
    private class BottomLineDrawable(
            host: android.widget.EditText, private val inner: GradientDrawable, private val strokeHeightPx: Int) : Drawable() {
        private val hostRef: java.lang.ref.WeakReference<android.widget.EditText> =
                java.lang.ref.WeakReference<android.widget.EditText>(host)

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            var lineLeft: Int   = bounds.left
            var lineRight: Int  = bounds.right
            var lineBottom: Int = bounds.bottom
            val et: android.widget.EditText? = hostRef.get()
            if (et != null) {
                var bg: Drawable? = et.background
                // Unwrap DrawableCompat.wrap() so we can reach the
                // InsetDrawable even after DisplayUtil.tintView wrapped it.
                // On API 23+ DrawableCompat.wrap returns the original
                // drawable; on older platforms it returns an androidx
                // WrappedDrawable that unwrap() handles transparently.
                bg = androidx.core.graphics.drawable.DrawableCompat.unwrap(bg!!)
                if (bg is android.graphics.drawable.InsetDrawable) {
                    val child: Drawable? = bg.drawable
                    if (child != null) {
                        val cb: android.graphics.Rect = child.getBounds()
                        if (cb.width() > 0 && cb.height() > 0) {
                            // child bounds are in the same coordinate space
                            // as this drawable's bounds (InsetDrawable sets
                            // child bounds = its own bounds - insets).
                            lineLeft   = cb.left
                            lineRight  = cb.right
                            lineBottom = cb.bottom
                        }
                    }
                }
            }
            val lineTop: Int = lineBottom - strokeHeightPx
            inner.setBounds(lineLeft, lineTop, lineRight, lineBottom)
        }

        override fun draw(canvas: android.graphics.Canvas) {
            // The host View draws its foreground in the SCROLLED coordinate space —
            // unlike the background, onDrawForeground() does NOT undo the view's
            // scroll translation. A singleLine EditText scrolls its content
            // (scrollX > 0) to keep the caret visible once the text outgrows the
            // field, which would otherwise drag this underline strip left along
            // with the text. Mirror View.drawBackground()'s scroll compensation so
            // the strip stays anchored to the field's visible edges.
            val et: android.widget.EditText? = hostRef.get()
            val sx: Int = et?.scrollX ?: 0
            val sy: Int = et?.scrollY ?: 0
            if ((sx or sy) == 0) {
                inner.draw(canvas)
            } else {
                canvas.translate(sx.toFloat(), sy.toFloat())
                inner.draw(canvas)
                canvas.translate(-sx.toFloat(), -sy.toFloat())
            }
        }

        @Keep
        override fun setAlpha(alpha: Int) {
            inner.alpha = alpha
            invalidateSelf()
        }
        override fun getAlpha(): Int { return inner.alpha
        }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {
            inner.colorFilter = cf
        }
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun getOpacity(): Int { return android.graphics.PixelFormat.TRANSLUCENT }
    }

    /**
     * Paint a [com.google.android.material.tabs.TabLayout]'s indicator with
     * `bg`. PURE → solid indicator colour. GRADIENT → a [GradientDrawable]
     * used as the selected-tab indicator drawable (Material's TabLayout accepts a
     * Drawable here).
     *
     * Note: the indicator drawable is laid out by TabLayout to span the
     * selected tab's width — exactly the kind of "any width" use case where a
     * GradientDrawable shines.
     */
    @JvmStatic
    fun applyTabIndicator(
            tabLayout: com.google.android.material.tabs.TabLayout?, bg: ThingBackground?) {
        if (tabLayout == null || bg == null) return
        if (bg.mode === ThingBackground.Mode.PURE) {
            tabLayout.setSelectedTabIndicator(null as Drawable?)
            tabLayout.setSelectedTabIndicatorColor(bg.color)
        } else {
            val gd = GradientDrawable()
            gd.setShape(GradientDrawable.RECTANGLE)
            gd.setOrientation(toGdOrientation(bg.orientation))
            gd.colors = intArrayOf(bg.color, bg.endColor)
            tabLayout.setSelectedTabIndicator(gd)
        }
    }

    /** Default ripple tint for circular fake-FAB cells over colour/gradient
     *  backgrounds — 54% white, matches the legacy
     *  `color_picker_fab.xml` `rippleColor="@color/white_54p"`. */
    const val RIPPLE_LIGHT: Int = 0x89FFFFFF.toInt()

    /** Ripple tint for fake-FAB cells over light backgrounds where a light
     *  ripple would disappear — 12% black, matches Material's
     *  `?attr/colorControlHighlight` on Light theme. */
    const val RIPPLE_DARK: Int  = 0x1F000000

    /**
     * Build a circular [android.graphics.drawable.RippleDrawable] with
     * the default [RIPPLE_LIGHT] tint. Use [circularRipple] (Int)
     * when the host background is light and a dark ripple is needed.
     */
    @JvmStatic
    fun circularRipple(): android.graphics.drawable.RippleDrawable {
        return circularRipple(RIPPLE_LIGHT)
    }

    /**
     * Build a circular [android.graphics.drawable.RippleDrawable] for
     * use as the `foreground` of a view with a circular background
     * drawable. The ripple is clipped to an OVAL mask layer so the press
     * feedback stays circular regardless of view bounds.
     */
    @JvmStatic
    fun circularRipple(rippleColor: Int): android.graphics.drawable.RippleDrawable {
        // Mask layer: a white OVAL — only the shape matters, RippleDrawable
        // uses it solely for clipping the ripple bounds.
        val mask = GradientDrawable()
        mask.setShape(GradientDrawable.OVAL)
        mask.setColor(0xFFFFFFFF.toInt())
        return android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(rippleColor),
                null,
                mask)
    }

    @JvmStatic
    fun appChromeRippleColor(context: Context): Int {
        return ContextCompat.getColor(context, R.color.app_chrome_ripple)
    }

    @JvmStatic
    fun thingRippleColor(thingColor: Int): Int {
        return if (isLight(thingColor)) 0x29000000 else 0x29FFFFFF
    }

    /**
     * 自适应 ripple 颜色，用于「选中态」等本身已铺底色的控件：底色亮 → 偏黑波纹，
     * 暗 → 偏白波纹；根目录（[bg] 为 null）或 accent 渐变固定偏白。偏白 alpha 36%、
     * 偏黑 alpha 16%。[isLight] 已把 accent 渐变当暗色处理（→偏白）。
     */
    @JvmStatic
    fun adaptiveRippleColor(bg: ThingBackground?): Int {
        // 偏白比偏黑更易被彩色底色冲淡，alpha 取更高一些（0x5C ≈ 36%）。
        if (bg == null) return 0x5CFFFFFF
        return if (isLight(bg)) 0x29000000 else 0x5CFFFFFF
    }

    /** 顶栏图标 ripple 固定半径（dp）：导航按钮与菜单项统一、居中于图标，不被各自控件尺寸撑大。 */
    const val TOOLBAR_ICON_RIPPLE_RADIUS_DP = 21f

    /**
     * 遍历系统 [Toolbar] 子 view（导航 ImageButton、ActionMenuView 内菜单项 / overflow），给每个
     * 设由 [rippleFactory]（入参=固定半径 px）现造的 foreground ripple，并清掉系统自带那层。子 view
     * 布局后才存在，故 post；菜单 / chrome 刷新后需重调。工厂由调用方提供（避免 utils→views 反向依赖）。
     */
    @JvmStatic
    fun applyToolbarIconRipples(
        toolbar: Toolbar,
        radiusDp: Float = TOOLBAR_ICON_RIPPLE_RADIUS_DP,
        rippleFactory: (radiusPx: Float) -> Drawable
    ) {
        toolbar.post {
            val radiusPx = toolbar.resources.displayMetrics.density * radiusDp
            for (i in 0 until toolbar.childCount) {
                when (val child = toolbar.getChildAt(i)) {
                    is ActionMenuView -> {
                        for (j in 0 until child.childCount) {
                            child.getChildAt(j).apply {
                                background = null
                                foreground = rippleFactory(radiusPx)
                            }
                        }
                    }
                    is ImageButton -> child.apply {
                        background = null
                        foreground = rippleFactory(radiusPx)
                    }
                }
            }
        }
    }

    /**
     * 「选中态」等需要常驻铺底色的填充 [Drawable]：PURE → 纯色，GRADIENT → 线性渐变。
     * 通常与 [adaptiveRippleColor] 叠成 RippleDrawable（底色 + 自适应波纹）一起用。
     */
    @JvmStatic
    fun fillDrawable(bg: ThingBackground): Drawable {
        return if (bg.mode == ThingBackground.Mode.GRADIENT) {
            GradientDrawable(toGdOrientation(bg.orientation), intArrayOf(bg.color, bg.endColor))
        } else {
            GradientDrawable().apply { setColor(bg.color) }
        }
    }

    @JvmStatic
    fun installAppChromePillRipple(view: View?, context: Context) {
        installPillRipple(view, appChromeRippleColor(context))
    }

    @JvmStatic
    fun installAppChromeDialogActionButton(view: TextView?, context: Context) {
        if (view == null) return
        val res = context.resources
        val paddingHorizontal = res.getDimensionPixelSize(
            R.dimen.app_chrome_dialog_action_button_padding_horizontal
        )
        val paddingVertical = res.getDimensionPixelSize(
            R.dimen.app_chrome_dialog_action_button_padding_vertical
        )
        view.includeFontPadding = false
        view.gravity = (view.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) or Gravity.CENTER_VERTICAL
        view.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            res.getDimension(R.dimen.app_chrome_dialog_action_text_size)
        )
        view.setPaddingRelative(
            paddingHorizontal,
            paddingVertical,
            paddingHorizontal,
            paddingVertical
        )
        installAppChromePillRipple(view, context)
    }

    @JvmStatic
    fun installAppChromeCircleRipple(view: View?, context: Context) {
        installCircleRipple(view, appChromeRippleColor(context))
    }

    @JvmStatic
    fun installThingPillRipple(view: View?, thingColor: Int) {
        installPillRipple(view, thingRippleColor(thingColor))
    }

    @JvmStatic
    fun installThingCircleRipple(view: View?, thingColor: Int) {
        installCircleRipple(view, thingRippleColor(thingColor))
    }

    @JvmStatic
    fun installPillRipple(view: View?, rippleColor: Int) {
        if (view == null) return
        val paddingStart = view.paddingStart
        val paddingTop = view.paddingTop
        val paddingEnd = view.paddingEnd
        val paddingBottom = view.paddingBottom
        view.background = null
        view.setPaddingRelative(paddingStart, paddingTop, paddingEnd, paddingBottom)
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, v.height / 2f)
            }
        }

        val mask = GradientDrawable()
        mask.shape = GradientDrawable.RECTANGLE
        mask.cornerRadius = 1000f
        mask.setColor(Color.WHITE)
        view.foreground = RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            null,
            mask
        )
    }

    @JvmStatic
    fun installCircleRipple(view: View?, rippleColor: Int) {
        if (view == null) return
        val paddingStart = view.paddingStart
        val paddingTop = view.paddingTop
        val paddingEnd = view.paddingEnd
        val paddingBottom = view.paddingBottom
        view.background = null
        view.setPaddingRelative(paddingStart, paddingTop, paddingEnd, paddingBottom)
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setOval(0, 0, v.width, v.height)
            }
        }
        view.foreground = circularRipple(rippleColor)
    }

    /**
     * Render `bg` as a `width × height` bitmap with the given
     * `alpha` (0-255) applied to every stop. PURE → uniform color
     * bitmap; GRADIENT → [GradientDrawable] rasterised to the bitmap.
     * Used by paths that can't accept a [Shader] or [android.graphics.drawable.Drawable]
     * directly — notably [android.widget.RemoteViews]-driven AppWidget
     * surfaces (RemoteViews accepts a [android.graphics.Bitmap] via
     * `setImageViewBitmap`, but no shader / drawable).
     *
     * Pass `alpha=255` for opaque output.
     */
    @JvmStatic
    fun renderBackgroundBitmap(
            bg: ThingBackground?, width: Int, height: Int, alpha: Int): android.graphics.Bitmap? {
        if (width <= 0 || height <= 0) return null
        val bm: android.graphics.Bitmap = android.graphics.Bitmap.createBitmap(
                width, height, android.graphics.Bitmap.Config.ARGB_8888)
        if (bg == null) return bm
        if (bg.mode === ThingBackground.Mode.PURE) {
            val color: Int = (alpha shl 24) or (bg.color and 0x00FFFFFF)
            bm.eraseColor(color)
            return bm
        }
        // GRADIENT: rasterise a GradientDrawable into the bitmap canvas.
        val s: Int = (alpha shl 24) or (bg.color    and 0x00FFFFFF)
        val e: Int = (alpha shl 24) or (bg.endColor and 0x00FFFFFF)
        val gd = GradientDrawable()
        gd.setShape(GradientDrawable.RECTANGLE)
        gd.setOrientation(toGdOrientation(bg.orientation))
        gd.colors = intArrayOf(s, e)
        gd.setBounds(0, 0, width, height)
        gd.draw(android.graphics.Canvas(bm))
        return bm
    }

    /**
     * Build a translucent [GradientDrawable] from `bg` where every
     * stop has its alpha replaced with `alpha` (0-255). PURE collapses
     * to a uniform rectangle; GRADIENT keeps its orientation but with
     * see-through stops. Used for overlay backgrounds that need to dim the
     * accent (e.g. NoticeableNotificationActivity's half-overlay card).
     */
    @JvmStatic
    fun makeTranslucentGradient(bg: ThingBackground?, alpha: Int): GradientDrawable {
        val gd = GradientDrawable()
        gd.setShape(GradientDrawable.RECTANGLE)
        val s: Int = (alpha shl 24) or (bg!!.color    and 0x00FFFFFF)
        val e: Int = (alpha shl 24) or (bg.endColor and 0x00FFFFFF)
        gd.setOrientation(toGdOrientation(bg.orientation))
        gd.colors = intArrayOf(s, e)
        return gd
    }

    // -------------------------------------------------------------------
    // ProgressBar — gradient tint via Drawable wrapper
    // -------------------------------------------------------------------

    private const val CHECKED_CONTROL_ANIM_MS: Long = 160

    // Default intrinsic footprint for the gradient checkbox/colour-pick drawables:
    // equals the 24dp visible drawing square, i.e. no extra margin (unchanged
    // behaviour for ColorPicker / Detail / widget config checkboxes).
    private const val CHECKBOX_DEFAULT_FOOTPRINT_DP: Float = 24f
    // Footprint for label-row checkboxes (label on the left, checkbox
    // alignParentRight) so the box keeps spacing from its label and centre-aligns
    // with neighbouring right-aligned help icons. Tuned against Settings rows; see
    // docs/features/theme-accent-migration/decisions.md.
    const val CHECKBOX_LABEL_ROW_FOOTPRINT_DP: Float = 32f

    /**
     * Indeterminate progress drawable following the visible implementation used
     * by the video crop editor: a self-drawn rotating arc with a SweepGradient.
     */
    class GradientTintDrawable(
        context: Context,
        private val background: ThingBackground
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val indicatorBounds = RectF()
        private val density = context.resources.displayMetrics.density
        private val frameCallback = Runnable { invalidateSelf() }
        private var externalAlpha = 255

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val size = minOf(bounds.width(), bounds.height()).toFloat()
                .coerceAtMost(32f * density)
            if (size <= 0f) return
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val strokeWidth = (3f * density).coerceAtMost(size / 6f)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = strokeWidth
            paint.alpha = externalAlpha
            indicatorBounds.set(
                cx - size / 2f + strokeWidth / 2f,
                cy - size / 2f + strokeWidth / 2f,
                cx + size / 2f - strokeWidth / 2f,
                cy + size / 2f - strokeWidth / 2f
            )
            bindSweepPaint(paint, background, indicatorBounds)
            val rotation = (SystemClock.uptimeMillis() % 1200L) * 360f / 1200f
            canvas.drawArc(indicatorBounds, rotation - 90f, 280f, false, paint)
            paint.shader = null

            if (isVisible) {
                unscheduleSelf(frameCallback)
                scheduleSelf(frameCallback, SystemClock.uptimeMillis() + 16L)
            }
        }

        override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
            val ownChanged = super.setVisible(visible, restart)
            if (visible) {
                scheduleSelf(frameCallback, SystemClock.uptimeMillis() + 16L)
            } else {
                unscheduleSelf(frameCallback)
            }
            return ownChanged
        }

        override fun setAlpha(alpha: Int) {
            externalAlpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = (36f * density).toInt()
        override fun getIntrinsicHeight(): Int = (36f * density).toInt()
    }

    private class GradientHorizontalProgressDrawable(
        context: Context,
        private val background: ThingBackground
    ) : Drawable() {

        private val density = context.resources.displayMetrics.density
        private val rect = RectF()
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.app_chrome_control_unchecked)
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private var externalAlpha = 255

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val h = bounds.height().toFloat().coerceAtMost(4f * density)
            val top = bounds.exactCenterY() - h / 2f
            val bottom = top + h
            val radius = h / 2f
            trackPaint.alpha = (externalAlpha * 0.24f).toInt()
            rect.set(bounds.left.toFloat(), top, bounds.right.toFloat(), bottom)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)

            val progressRight = bounds.left + bounds.width() * (level / 10000f)
            if (progressRight <= bounds.left) return
            rect.set(bounds.left.toFloat(), top, progressRight, bottom)
            progressPaint.alpha = externalAlpha
            if (background.mode === ThingBackground.Mode.GRADIENT) {
                progressPaint.shader = linearGradientFor(background, bounds.width().toFloat(), h)
            } else {
                progressPaint.shader = null
                progressPaint.color = background.color
            }
            canvas.drawRoundRect(rect, radius, radius, progressPaint)
            progressPaint.shader = null
        }

        override fun onLevelChange(level: Int): Boolean {
            invalidateSelf()
            return true
        }

        override fun setAlpha(alpha: Int) {
            externalAlpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            progressPaint.colorFilter = colorFilter
            trackPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun bindSweepPaint(paint: Paint, bg: ThingBackground, bounds: RectF) {
        if (bg.mode === ThingBackground.Mode.GRADIENT) {
            paint.shader = SweepGradient(
                bounds.centerX(),
                bounds.centerY(),
                intArrayOf(bg.color, bg.endColor, bg.color),
                floatArrayOf(0f, 0.75f, 1f)
            )
        } else {
            paint.shader = null
            paint.color = bg.color
        }
    }

    /** Replace progress drawables on [pb] with self-drawn gradient drawables. */
    @JvmStatic
    fun applyProgressBarGradient(pb: ProgressBar, bg: ThingBackground) {
        pb.indeterminateTintList = null
        pb.progressTintList = null
        pb.progressBackgroundTintList = null
        pb.secondaryProgressTintList = null
        pb.indeterminateDrawable = GradientTintDrawable(pb.context, bg)
        pb.progressDrawable = GradientHorizontalProgressDrawable(pb.context, bg)
    }

    // -------------------------------------------------------------------
    // CheckBox — gradient checked-state drawable
    // -------------------------------------------------------------------

    /**
     * Draw a custom [android.widget.CompoundButton] button whose checked fill uses
     * the supplied [ThingBackground].
     * It draws the box, fill and checkmark itself so gradients are not reduced
     * to a single tint colour.
     *
     * Gradients stay visible instead of being collapsed into one tint colour.
     */
    @JvmStatic
    @JvmOverloads
    fun applyCheckboxAccent(
        button: android.widget.CompoundButton,
        bg: ThingBackground,
        uncheckedColor: Int = ContextCompat.getColor(
            button.context, R.color.app_chrome_control_unchecked
        ),
        footprintDp: Float = CHECKBOX_DEFAULT_FOOTPRINT_DP,
        /**
         * 未选中的方框描边是否也用 [bg] 本身（整体降 alpha，不把渐变压成单色）而不是中性色。
         * 默认 false：全应用的 checkbox 未选中态一律是中性描边，只有明确要求"两种状态都跟着
         * 强调色走"的入口才打开它。
         */
        uncheckedGradient: Boolean = false
    ) {
        button.buttonTintList = null
        if (button is androidx.appcompat.widget.AppCompatCheckBox) {
            button.supportButtonTintList = null
        }
        button.buttonDrawable = GradientCheckboxDrawable(
            button.context,
            bg,
            uncheckedColor,
            initialChecked = button.isChecked,
            animate = false,
            stateDriven = true,
            footprintDp = footprintDp,
            uncheckedGradient = uncheckedGradient
        )
        button.refreshDrawableState()
    }

    @JvmStatic
    @JvmOverloads
    fun createGradientCheckboxDrawable(
        context: Context,
        bg: ThingBackground,
        uncheckedColor: Int,
        checked: Boolean,
        animate: Boolean = false
    ): Drawable {
        return GradientCheckboxDrawable(
            context,
            bg,
            uncheckedColor,
            checked,
            animate,
            stateDriven = false
        )
    }

    private class GradientCheckboxDrawable(
        context: Context,
        private val background: ThingBackground,
        private val uncheckedColor: Int,
        initialChecked: Boolean,
        animate: Boolean,
        private val stateDriven: Boolean,
        footprintDp: Float = CHECKBOX_DEFAULT_FOOTPRINT_DP,
        private val uncheckedGradient: Boolean = false
    ) : Drawable(), PreTintedGradientDrawable {

        private val density = context.resources.displayMetrics.density
        private val sizePx = (24f * density).toInt().coerceAtLeast(1)
        // Intrinsic footprint reserved by the drawable. The visible box stays
        // sizePx; a larger footprint only adds transparent margin around it, used
        // as a button drawable so a wrap_content CheckBox keeps proper spacing from
        // its label and aligns with neighbouring right-aligned icons.
        private val footprintPx = (footprintDp * density).toInt().coerceAtLeast(sizePx)
        private val strokePx = 2f * density
        private val radiusPx = 2.5f * density
        private val boxInsetPx = 3f * density
        private val rect = RectF()
        private val checkPath = Path()
        private val gradientMatrix = Matrix()
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            color = uncheckedColor
        }
        private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2.4f * density
            // 对号压在填充色上，因此按填充色明暗取偏黑或偏白，而不是固定白色——浅色强调色
            // （例如明黄）上的白对号根本看不出来。走 ThingBackground 那个重载：它认得 App
            // 默认强调渐变，渐变两端的信息不会先被代表色抹平。
            color = onColor(background, 1f)
        }
        private var checked = initialChecked
        private var enabled = true
        private var externalAlpha = 255
        private var resolvedState = !stateDriven
        private var checkedProgress = if (checked) 1f else 0f
        private var checkedAnimator: ValueAnimator? = null

        init {
            if (animate) {
                checkedProgress = if (checked) 0f else 1f
                animateCheckedProgress(if (checked) 1f else 0f)
            }
        }

        override fun draw(canvas: Canvas) {
            val boundsSide = minOf(bounds.width(), bounds.height()).toFloat()
            if (boundsSide <= 0f) return
            // Draw the box at its fixed visual size (sizePx) centered in the
            // possibly-larger footprint: a wider intrinsic only adds margin around
            // the box, it does not scale the box.
            val side = minOf(boundsSide, sizePx.toFloat())
            val left = bounds.left + (bounds.width() - side) / 2f
            val top = bounds.top + (bounds.height() - side) / 2f
            val scale = side / sizePx.toFloat()
            val inset = boxInsetPx * scale
            val radius = radiusPx * scale
            rect.set(left + inset, top + inset, left + side - inset, top + side - inset)

            val stateAlpha = if (enabled) externalAlpha else (externalAlpha * 0.38f).toInt()
            val progress = checkedProgress.coerceIn(0f, 1f)
            if (progress > 0f) {
                fillPaint.alpha = (stateAlpha * progress).toInt()
                if (background.mode === ThingBackground.Mode.GRADIENT) {
                    val shader = linearGradientFor(background, side, side)
                    // The gradient is built in box-local (0,0)-(side,side) space;
                    // shift it onto the box, which may be inset inside a larger
                    // footprint.
                    gradientMatrix.setTranslate(left, top)
                    shader.setLocalMatrix(gradientMatrix)
                    fillPaint.shader = shader
                } else {
                    fillPaint.shader = null
                    fillPaint.color = background.color
                }
                // The unchecked outline is a centred STROKE, so its visible box
                // extends strokePx/2 beyond `rect`. Fill out to that same outer
                // edge so the checked box is not a ring smaller than the unchecked
                // one (and so the checked fill alone, no mismatched stroke, shows).
                val fillExpand = strokePx / 2f
                canvas.drawRoundRect(
                    rect.left - fillExpand, rect.top - fillExpand,
                    rect.right + fillExpand, rect.bottom + fillExpand,
                    radius + fillExpand, radius + fillExpand, fillPaint
                )
                fillPaint.shader = null

                checkPaint.alpha = (stateAlpha * progress).toInt()
                checkPath.reset()
                checkPath.moveTo(left + side * 0.32f, top + side * 0.52f)
                checkPath.lineTo(left + side * 0.45f, top + side * 0.65f)
                checkPath.lineTo(left + side * 0.70f, top + side * 0.38f)
                canvas.drawPath(checkPath, checkPaint)
            }
            if (progress < 1f) {
                val fade = stateAlpha * (1f - progress)
                if (uncheckedGradient) {
                    // 未选中同样保留**完整**渐变，不把它压成起点单色（D18 给胶囊定的规则），
                    // 也**不降 alpha**：描边只有 2dp 宽，淡一点就比旁边的控件明显发虚。
                    if (background.mode === ThingBackground.Mode.GRADIENT) {
                        val shader = linearGradientFor(background, side, side)
                        gradientMatrix.setTranslate(left, top)
                        shader.setLocalMatrix(gradientMatrix)
                        strokePaint.shader = shader
                    } else {
                        strokePaint.shader = null
                        strokePaint.color = background.color
                    }
                    strokePaint.alpha = fade.toInt()
                } else {
                    strokePaint.shader = null
                    strokePaint.color = uncheckedColor
                    strokePaint.alpha = (
                        fade * Color.alpha(uncheckedColor) / 255f
                    ).toInt()
                }
                canvas.drawRoundRect(rect, radius, radius, strokePaint)
                strokePaint.shader = null
            }
        }

        override fun isStateful(): Boolean = true

        override fun onStateChange(state: IntArray): Boolean {
            val newChecked = if (stateDriven) {
                state.contains(android.R.attr.state_checked)
            } else {
                checked
            }
            val newEnabled = state.contains(android.R.attr.state_enabled)
            if (newChecked == checked && newEnabled == enabled && resolvedState) return false
            val checkedChanged = newChecked != checked
            checked = newChecked
            enabled = newEnabled
            if (!resolvedState) {
                checkedProgress = if (checked) 1f else 0f
                resolvedState = true
                invalidateSelf()
                return true
            }
            if (checkedChanged) {
                animateCheckedProgress(if (checked) 1f else 0f)
                return true
            }
            invalidateSelf()
            return true
        }

        private fun animateCheckedProgress(target: Float) {
            checkedAnimator?.cancel()
            checkedAnimator = ValueAnimator.ofFloat(checkedProgress, target).apply {
                duration = CHECKED_CONTROL_ANIM_MS
                addUpdateListener {
                    checkedProgress = it.animatedValue as Float
                    invalidateSelf()
                }
                start()
            }
        }

        override fun setAlpha(alpha: Int) {
            externalAlpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
            checkPaint.colorFilter = colorFilter
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = footprintPx
        override fun getIntrinsicHeight(): Int = footprintPx

        override fun jumpToCurrentState() {
            checkedAnimator?.cancel()
            checkedProgress = if (checked) 1f else 0f
            invalidateSelf()
        }
    }

    @JvmStatic
    fun createGradientRadioDrawable(
        context: Context,
        bg: ThingBackground,
        uncheckedColor: Int,
        checked: Boolean,
        animate: Boolean
    ): Drawable {
        return GradientRadioDrawable(context, bg, uncheckedColor, checked, animate)
    }

    /**
     * RadioButton 版的 [applyCheckboxAccent]：圆环 + 选中内点走同一套强调渐变、
     * 状态驱动与 footprint 规则，让单选钮行与勾选框行在两种状态下观感一致。
     */
    @JvmStatic
    @JvmOverloads
    fun applyRadioAccent(
        button: android.widget.CompoundButton,
        bg: ThingBackground,
        uncheckedColor: Int = ContextCompat.getColor(
            button.context, R.color.app_chrome_control_unchecked
        ),
        footprintDp: Float = CHECKBOX_DEFAULT_FOOTPRINT_DP,
        uncheckedGradient: Boolean = false
    ) {
        androidx.core.widget.CompoundButtonCompat.setButtonTintList(button, null)
        button.buttonDrawable = GradientRadioDrawable(
            button.context,
            bg,
            uncheckedColor,
            initialChecked = button.isChecked,
            animate = false,
            stateDriven = true,
            footprintDp = footprintDp,
            uncheckedGradient = uncheckedGradient
        )
        button.refreshDrawableState()
    }

    private class GradientRadioDrawable(
        context: Context,
        private val background: ThingBackground,
        private val uncheckedColor: Int,
        initialChecked: Boolean,
        animate: Boolean,
        private val stateDriven: Boolean = false,
        footprintDp: Float = 24f,
        private val uncheckedGradient: Boolean = false
    ) : Drawable(), PreTintedGradientDrawable {

        private val density = context.resources.displayMetrics.density
        private val sizePx = (24f * density).toInt().coerceAtLeast(1)
        // 同 GradientCheckboxDrawable：更大的 footprint 只在圆周围加透明边距，
        // 圆本身固定 sizePx 视觉尺寸。
        private val footprintPx = (footprintDp * density).toInt().coerceAtLeast(sizePx)
        private val strokePx = 2f * density
        private val gradientMatrix = Matrix()
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private var checked = initialChecked
        private var enabled = true
        private var externalAlpha = 255
        private var resolvedState = !stateDriven
        private var checkedProgress = if (checked) 1f else 0f
        private var animator: ValueAnimator? = null

        init {
            if (animate) {
                checkedProgress = if (checked) 0f else 1f
                animateCheckedProgress(if (checked) 1f else 0f)
            }
        }

        override fun draw(canvas: Canvas) {
            val boundsSide = minOf(bounds.width(), bounds.height()).toFloat()
            if (boundsSide <= 0f) return
            val side = minOf(boundsSide, sizePx.toFloat())
            val left = bounds.left + (bounds.width() - side) / 2f
            val top = bounds.top + (bounds.height() - side) / 2f
            val scale = side / sizePx.toFloat()
            val stroke = strokePx * scale
            val centerX = left + side / 2f
            val centerY = top + side / 2f
            val radius = side * 0.34f
            val stateAlpha = if (enabled) externalAlpha else (externalAlpha * 0.38f).toInt()
            val progress = checkedProgress.coerceIn(0f, 1f)

            // 渐变建在圆的局部坐标系里，平移到实际位置——footprint 大于圆时圆不在
            // bounds 原点（同 GradientCheckboxDrawable 的处理）。
            fun bindAccent(paint: Paint) {
                if (background.mode === ThingBackground.Mode.GRADIENT) {
                    val shader = linearGradientFor(background, side, side)
                    gradientMatrix.setTranslate(left, top)
                    shader.setLocalMatrix(gradientMatrix)
                    paint.shader = shader
                } else {
                    paint.shader = null
                    paint.color = background.color
                }
            }

            strokePaint.strokeWidth = stroke
            if (progress < 1f) {
                val fade = stateAlpha * (1f - progress)
                if (uncheckedGradient) {
                    // 同 GradientCheckboxDrawable：未选中保留完整渐变，不压单色、不降 alpha
                    bindAccent(strokePaint)
                    strokePaint.alpha = fade.toInt()
                } else {
                    strokePaint.shader = null
                    strokePaint.color = uncheckedColor
                    strokePaint.alpha = (
                        fade * Color.alpha(uncheckedColor) / 255f
                    ).toInt()
                }
                canvas.drawCircle(centerX, centerY, radius, strokePaint)
                strokePaint.shader = null
            }

            if (progress > 0f) {
                bindAccent(strokePaint)
                strokePaint.alpha = (stateAlpha * progress).toInt()
                canvas.drawCircle(centerX, centerY, radius, strokePaint)
                strokePaint.shader = null

                bindAccent(fillPaint)
                fillPaint.alpha = (stateAlpha * progress).toInt()
                canvas.drawCircle(centerX, centerY, radius * 0.58f * progress, fillPaint)
                fillPaint.shader = null
            }
        }

        override fun isStateful(): Boolean = true

        override fun onStateChange(state: IntArray): Boolean {
            val newChecked = if (stateDriven) {
                state.contains(android.R.attr.state_checked)
            } else {
                checked
            }
            val newEnabled = state.contains(android.R.attr.state_enabled)
            if (newChecked == checked && newEnabled == enabled && resolvedState) return false
            val checkedChanged = newChecked != checked
            checked = newChecked
            enabled = newEnabled
            if (!resolvedState) {
                checkedProgress = if (checked) 1f else 0f
                resolvedState = true
                invalidateSelf()
                return true
            }
            if (checkedChanged) {
                animateCheckedProgress(if (checked) 1f else 0f)
                return true
            }
            invalidateSelf()
            return true
        }

        private fun animateCheckedProgress(target: Float) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(checkedProgress, target).apply {
                duration = CHECKED_CONTROL_ANIM_MS
                addUpdateListener {
                    checkedProgress = it.animatedValue as Float
                    invalidateSelf()
                }
                start()
            }
        }

        override fun setAlpha(alpha: Int) {
            externalAlpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            strokePaint.colorFilter = colorFilter
            fillPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = footprintPx
        override fun getIntrinsicHeight(): Int = footprintPx

        override fun jumpToCurrentState() {
            animator?.cancel()
            animator = null
            checkedProgress = if (checked) 1f else 0f
            invalidateSelf()
        }
    }

    /** Map our [ThingBackground.Orientation] → platform [GradientDrawable.Orientation]. */
    private fun toGdOrientation(o: ThingBackground.Orientation): GradientDrawable.Orientation {
        return when (o) {
            ThingBackground.Orientation.L_R   -> GradientDrawable.Orientation.LEFT_RIGHT
            ThingBackground.Orientation.T_B   -> GradientDrawable.Orientation.TOP_BOTTOM
            ThingBackground.Orientation.LT_RB -> GradientDrawable.Orientation.TL_BR
            ThingBackground.Orientation.RT_LB -> GradientDrawable.Orientation.TR_BL
            ThingBackground.Orientation.LB_RT -> GradientDrawable.Orientation.BL_TR
            ThingBackground.Orientation.RB_LT -> GradientDrawable.Orientation.BR_TL
            ThingBackground.Orientation.R_L   -> GradientDrawable.Orientation.RIGHT_LEFT
            ThingBackground.Orientation.B_T   -> GradientDrawable.Orientation.BOTTOM_TOP
        }
    }
}
