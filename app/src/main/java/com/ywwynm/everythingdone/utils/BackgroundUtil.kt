@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.utils

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Shader
import android.view.ViewTreeObserver
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.TextView
import androidx.annotation.Keep
import androidx.core.content.ContextCompat

import androidx.cardview.widget.CardView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import kotlin.math.ceil

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
     * and rendered text layout are settled. The TextView is also re-tinted with
     * `representativeColor` synchronously so any pre-shader draw still
     * has the right base colour.
     */
    @JvmStatic
    fun applyTextBackground(textView: TextView?, background: ThingBackground?) {
        if (textView == null || background == null) return

        if (background.mode === ThingBackground.Mode.PURE) {
            val paint: android.text.TextPaint = textView.paint
            if (paint.shader != null) paint.setShader(null)
            textView.setTextColor(background.color)
            textView.invalidate()
            return
        }

        // Pre-set representative as a synchronous fallback so any early draw
        // (e.g. dialog enter animation) still uses the new colour.
        textView.setTextColor(background.representativeColor())

        val apply = Runnable {
            applyTextShaderNow(textView, background)
        }
        if (textView.width > 0 && textView.height > 0
                && textView.getText() != null && textView.getText().length > 0) {
            apply.run()
            return
        }
        // One-shot pre-draw listener — guaranteed to fire after layout and right
        // before the next frame, more reliable than post() across dialog
        // lifecycles.
        textView.getViewTreeObserver().addOnPreDrawListener(
                object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        textView.getViewTreeObserver().removeOnPreDrawListener(this)
                        applyTextShaderNow(textView, background)
                        return true
                    }
                })
    }

    /** Build a text-width-fitted LinearGradient and install it on the TextView's paint. */
    private fun applyTextShaderNow(textView: TextView, bg: ThingBackground) {
        val text: CharSequence? = textView.getText()
        if (text.isNullOrEmpty()) return
        val viewW: Int = textView.width
        val viewH: Int = textView.height
        if (viewW <= 0 || viewH <= 0) return

        // Use TextView.getLayout() to get the exact rendered bounds of the first
        // line — Layout.getLineLeft / getLineRight / getLineTop / getLineBottom
        // are gravity-, padding-, alignment- and transformation-aware (handles
        // textAllCaps too), so the gradient lines up identically across
        // Button/TextView/TabView regardless of how each one positions its
        // text. Layout-coord origin is at (totalPaddingLeft, totalPaddingTop)
        // in view space, so we add those padding offsets to translate the
        // shader into view coordinates.
        val layout: android.text.Layout? = textView.layout
        var textX: Float
        var textY: Float
        var textW: Float
        var textH: Float
        if (layout != null && layout.lineCount > 0) {
            val lineLeft: Float   = layout.getLineLeft(0)
            val lineRight: Float  = layout.getLineRight(0)
            val lineTop: Int      = layout.getLineTop(0)
            val lineBottom: Int   = layout.getLineBottom(0)
            textW = lineRight - lineLeft
            textH = (lineBottom - lineTop).toFloat()
            textX = textView.totalPaddingLeft + lineLeft
            textY = textView.totalPaddingTop + lineTop.toFloat()
        } else {
            // Layout not yet built — fall back to a paint-based estimate.
            textW = textView.paint.measureText(text, 0, text.length)
            textH = textView.paint.fontSpacing
            textX = textView.getPaddingLeft().toFloat()
            textY = (viewH - textH) / 2f
        }
        if (textW <= 0) textW = viewW.toFloat()
        if (textH <= 0) textH = viewH.toFloat()

        // Build the gradient over (textW × textH) and translate to (textX, textY)
        // so it lines up with the rendered glyphs regardless of view size.
        val lg: LinearGradient = linearGradientFor(bg, textW, textH)
        val m = Matrix()
        m.setTranslate(textX, textY)
        lg.setLocalMatrix(m)
        textView.paint.setShader(lg)
        textView.invalidate()
    }

    /** Build a LinearGradient covering `width × height` matching the given background's stops. */
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
            inner.draw(canvas)
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
