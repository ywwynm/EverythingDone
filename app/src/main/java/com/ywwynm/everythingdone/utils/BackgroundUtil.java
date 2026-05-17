package com.ywwynm.everythingdone.utils;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.view.ViewTreeObserver;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

import androidx.cardview.widget.CardView;

import com.ywwynm.everythingdone.model.ThingBackground;

/**
 * Algorithmic helpers for deriving colors and brightness-aware foreground choices
 * from an arbitrary background color.
 *
 * <p>Replaces the palette-table-lookup approach in
 * {@link DisplayUtil#getLightColor(int, android.content.Context)} /
 * {@link DisplayUtil#getDarkColor(int, android.content.Context)} so the app can
 * accept any thing color, not just the 10 fixed palette entries.
 *
 * <p>Phase 1 of the color-system migration — see COLOR_MIGRATION_PLAN.md.
 */
public final class BackgroundUtil {

    private BackgroundUtil() {}

    // ---------------------------------------------------------------------------
    // Hue buckets — used by search-by-similar-color. See COLOR_MIGRATION_PLAN.md
    // section 4.5. Values are stable ints persisted/transmitted via Intent / DAO
    // calls; do not reorder.
    // ---------------------------------------------------------------------------
    public static final int HUE_BUCKET_NONE   = 0; // sentinel: no filter
    public static final int HUE_BUCKET_RED    = 1;
    public static final int HUE_BUCKET_ORANGE = 2;
    public static final int HUE_BUCKET_YELLOW = 3;
    public static final int HUE_BUCKET_GREEN  = 4;
    public static final int HUE_BUCKET_CYAN   = 5;
    public static final int HUE_BUCKET_BLUE   = 6;
    public static final int HUE_BUCKET_PURPLE = 7;
    public static final int HUE_BUCKET_GREY   = 8;

    /**
     * Classify {@code color} into one of the {@code HUE_BUCKET_*} buckets.
     * Saturation below 0.15 maps to {@link #HUE_BUCKET_GREY} regardless of hue.
     * Hue ranges (HSL degrees):
     *   red    345-360, 0-15
     *   orange 15-45
     *   yellow 45-70
     *   green  70-165
     *   cyan   165-195
     *   blue   195-255
     *   purple 255-345 (includes magenta)
     */
    public static int hueBucket(int color) {
        float[] hsl = new float[3];
        androidx.core.graphics.ColorUtils.colorToHSL(color, hsl);
        float h = hsl[0];
        float s = hsl[1];
        if (s < 0.15f) return HUE_BUCKET_GREY;
        if (h >= 345f || h < 15f) return HUE_BUCKET_RED;
        if (h < 45f)              return HUE_BUCKET_ORANGE;
        if (h < 70f)              return HUE_BUCKET_YELLOW;
        if (h < 165f)             return HUE_BUCKET_GREEN;
        if (h < 195f)             return HUE_BUCKET_CYAN;
        if (h < 255f)             return HUE_BUCKET_BLUE;
        return HUE_BUCKET_PURPLE;
    }

    /** Hue bucket for the representative colour of a {@link ThingBackground}. */
    public static int hueBucket(ThingBackground bg) {
        if (bg == null) return HUE_BUCKET_NONE;
        return hueBucket(bg.representativeColor());
    }

    /** Standard "on" alpha tiers — match the existing white_*p / black_*p resources. */
    public static final float ON_ALPHA_PRIMARY   = 0.86f;
    public static final float ON_ALPHA_SECONDARY = 0.76f;
    public static final float ON_ALPHA_TERTIARY  = 0.66f;
    public static final float ON_ALPHA_DISABLED  = 0.54f;

    /**
     * Whether {@code background} counts as "light" — meaning it should be paired
     * with a dark (black-side) foreground rather than a light (white-side) one.
     * Uses Rec. 601 perceived-luminance with threshold 150, matching the
     * Everything-Android Kotlin reference implementation.
     */
    public static boolean isLight(int background) {
        int r = Color.red(background);
        int g = Color.green(background);
        int b = Color.blue(background);
        return r * 0.299 + g * 0.587 + b * 0.114 > 150;
    }

    /**
     * Foreground color to draw on top of {@code background}, with the requested
     * {@code alpha} (0~1). Returns a black-tinted color on light backgrounds and
     * a white-tinted color on dark backgrounds.
     */
    public static int onColor(int background, float alpha) {
        int rgb = isLight(background) ? 0x000000 : 0xFFFFFF;
        int a   = Math.round(clamp01(alpha) * 255f);
        return (a << 24) | rgb;
    }

    /** Linearly blend {@code color} toward white. amount=0 keeps color; amount=1 returns white. */
    public static int lighter(int color, float amount) {
        return blendWith(color, 0xFFFFFF, amount);
    }

    /** Linearly blend {@code color} toward black. amount=0 keeps color; amount=1 returns black. */
    public static int darker(int color, float amount) {
        return blendWith(color, 0x000000, amount);
    }

    private static int blendWith(int color, int targetRgb, float amount) {
        amount = clamp01(amount);
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        int tr = (targetRgb >> 16) & 0xFF;
        int tg = (targetRgb >> 8)  & 0xFF;
        int tb = targetRgb         & 0xFF;
        int nr = Math.round(r * (1 - amount) + tr * amount);
        int ng = Math.round(g * (1 - amount) + tg * amount);
        int nb = Math.round(b * (1 - amount) + tb * amount);
        return Color.rgb(nr, ng, nb);
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    // ---------------------------------------------------------------------------
    // ThingBackground painters
    // ---------------------------------------------------------------------------

    /**
     * Paint {@code background} onto {@code view}.
     * PURE: regular {@code setBackgroundColor}. GRADIENT: a reusable
     * {@link GradientDrawable} — the same instance is mutated on subsequent calls so
     * the view doesn't churn drawables (matters for the colour-change animation in
     * DetailActivity).
     */
    public static void applyBackground(View view, ThingBackground background) {
        if (view == null || background == null) return;
        if (background.mode == ThingBackground.Mode.PURE) {
            // Pure: defer to setBackgroundColor, which uses a cheap ColorDrawable.
            view.setBackgroundColor(background.color);
        } else {
            GradientDrawable gd = obtainGradient(view);
            gd.setOrientation(toGdOrientation(background.orientation));
            gd.setColors(new int[] { background.color, background.endColor });
        }
    }

    /**
     * Paint {@code background} onto a {@link CardView}.
     *
     * <p>Both PURE and GRADIENT route through {@link View#setBackground(Drawable)}
     * so {@code cv.getBackground()} is replaced on every bind — this avoids the
     * "stale GradientDrawable left over from a recycled ViewHolder" bug that
     * showed up when we used {@link CardView#setCardBackgroundColor} for PURE
     * after a GRADIENT bind (CardView only updates its internal drawable, not
     * the View.background field, so the stale GradientDrawable kept rendering).
     *
     * <p>CardView's rounded-corner clipping and elevation shadow are driven by
     * the view's outline (set by CardView in {@code onSizeChanged}) — not by
     * the background drawable — so replacing the drawable doesn't break either.
     */
    public static void applyCardBackground(CardView cv, ThingBackground background) {
        if (cv == null || background == null) return;

        if (background.mode == ThingBackground.Mode.PURE) {
            // View.setBackgroundColor wraps a fresh ColorDrawable and assigns it
            // to View.background — replacing any stale GradientDrawable from a
            // previous (recycled) bind. CardView's outline / shadow keep working.
            cv.setBackgroundColor(background.color);
        } else {
            GradientDrawable gd;
            Drawable existing = cv.getBackground();
            if (existing instanceof GradientDrawable) {
                // Reuse the existing instance (cheaper) and just mutate its colours.
                gd = (GradientDrawable) existing;
            } else {
                gd = new GradientDrawable();
                gd.setShape(GradientDrawable.RECTANGLE);
                cv.setBackground(gd);
            }
            gd.setOrientation(toGdOrientation(background.orientation));
            gd.setColors(new int[] { background.color, background.endColor });
        }
    }

    private static GradientDrawable obtainGradient(View view) {
        Drawable existing = view.getBackground();
        if (existing instanceof GradientDrawable) {
            return (GradientDrawable) existing;
        }
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        view.setBackground(gd);
        return gd;
    }

    /**
     * Animate {@code view}'s background from {@code from} to {@code to} over
     * {@code duration} ms by running two {@link ArgbEvaluator}s in lock-step over
     * the start and end colors. Orientation snaps to {@code to.orientation} at
     * the midpoint (cleaner than trying to interpolate enum directions).
     *
     * <p>When both endpoints stay equal during a given frame (i.e. PURE-PURE
     * animation), we fall back to {@link View#setBackgroundColor(int)} so the
     * view keeps using a cheap ColorDrawable and other view code that casts
     * to ColorDrawable continues to work.
     */
    public static ValueAnimator animateBackground(
            final View view, final ThingBackground from, final ThingBackground to, long duration) {
        if (view == null || from == null || to == null) return null;
        final ArgbEvaluator eval = new ArgbEvaluator();
        final int fStart = from.color,    fEnd = from.endColor;
        final int tStart = to.color,      tEnd = to.endColor;
        final ThingBackground.Orientation oFrom = from.orientation;
        final ThingBackground.Orientation oTo   = to.orientation;

        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(duration);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                int s = (Integer) eval.evaluate(f, fStart, tStart);
                int e = (Integer) eval.evaluate(f, fEnd,   tEnd);
                if (s == e) {
                    view.setBackgroundColor(s);
                } else {
                    ThingBackground.Orientation o = (f < 0.5f) ? oFrom : oTo;
                    applyBackground(view, ThingBackground.gradient(s, e, o));
                }
            }
        });
        anim.start();
        return anim;
    }

    /**
     * Apply {@code background} as the ink fill of {@code textView}'s glyphs.
     *
     * <p>PURE: regular {@link TextView#setTextColor(int)} + clears any shader
     * left over from a previous GRADIENT bind.
     *
     * <p>GRADIENT: installs a {@link LinearGradient} on the TextView's
     * {@link android.text.TextPaint}. Paint shaders fill glyph masks during
     * drawing, so each character is coloured by the gradient.
     *
     * <p>Key subtlety: the shader spans the entire {@code textView} bounding box
     * by default, but typical dialog labels are short text centred in a wide
     * TextView — the visible glyphs would only sample a thin middle slice of the
     * gradient and look uniform. We therefore measure the actual text width and
     * translate the shader so the gradient endpoints align with the text's left
     * and right edges (accounting for the TextView's gravity-driven offset).
     * The shader is applied after layout (via a one-shot
     * {@link ViewTreeObserver.OnPreDrawListener}) when the view's measured size
     * and rendered text layout are settled. The TextView is also re-tinted with
     * {@code representativeColor} synchronously so any pre-shader draw still
     * has the right base colour.
     */
    public static void applyTextBackground(final TextView textView, final ThingBackground background) {
        if (textView == null || background == null) return;

        if (background.mode == ThingBackground.Mode.PURE) {
            android.text.TextPaint paint = textView.getPaint();
            if (paint.getShader() != null) paint.setShader(null);
            textView.setTextColor(background.color);
            textView.invalidate();
            return;
        }

        // Pre-set representative as a synchronous fallback so any early draw
        // (e.g. dialog enter animation) still uses the new colour.
        textView.setTextColor(background.representativeColor());

        final Runnable apply = new Runnable() {
            @Override public void run() {
                applyTextShaderNow(textView, background);
            }
        };
        if (textView.getWidth() > 0 && textView.getHeight() > 0
                && textView.getText() != null && textView.getText().length() > 0) {
            apply.run();
            return;
        }
        // One-shot pre-draw listener — guaranteed to fire after layout and right
        // before the next frame, more reliable than post() across dialog
        // lifecycles.
        textView.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        textView.getViewTreeObserver().removeOnPreDrawListener(this);
                        applyTextShaderNow(textView, background);
                        return true;
                    }
                });
    }

    /** Build a text-width-fitted LinearGradient and install it on the TextView's paint. */
    private static void applyTextShaderNow(TextView textView, ThingBackground bg) {
        CharSequence text = textView.getText();
        if (text == null || text.length() == 0) return;
        int viewW = textView.getWidth();
        int viewH = textView.getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        // Use TextView.getLayout() to get the exact rendered bounds of the first
        // line — Layout.getLineLeft / getLineRight / getLineTop / getLineBottom
        // are gravity-, padding-, alignment- and transformation-aware (handles
        // textAllCaps too), so the gradient lines up identically across
        // Button/TextView/TabView regardless of how each one positions its
        // text. Layout-coord origin is at (totalPaddingLeft, totalPaddingTop)
        // in view space, so we add those padding offsets to translate the
        // shader into view coordinates.
        android.text.Layout layout = textView.getLayout();
        float textX, textY, textW, textH;
        if (layout != null && layout.getLineCount() > 0) {
            float lineLeft   = layout.getLineLeft(0);
            float lineRight  = layout.getLineRight(0);
            int   lineTop    = layout.getLineTop(0);
            int   lineBottom = layout.getLineBottom(0);
            textW = lineRight - lineLeft;
            textH = lineBottom - lineTop;
            textX = textView.getTotalPaddingLeft() + lineLeft;
            textY = textView.getTotalPaddingTop()  + lineTop;
        } else {
            // Layout not yet built — fall back to a paint-based estimate.
            textW = textView.getPaint().measureText(text, 0, text.length());
            textH = textView.getPaint().getFontSpacing();
            textX = textView.getPaddingLeft();
            textY = (viewH - textH) / 2f;
        }
        if (textW <= 0) textW = viewW;
        if (textH <= 0) textH = viewH;

        // Build the gradient over (textW × textH) and translate to (textX, textY)
        // so it lines up with the rendered glyphs regardless of view size.
        LinearGradient lg = linearGradientFor(bg, textW, textH);
        Matrix m = new Matrix();
        m.setTranslate(textX, textY);
        lg.setLocalMatrix(m);
        textView.getPaint().setShader(lg);
        textView.invalidate();
    }

    /** Build a LinearGradient covering {@code width × height} matching the given background's stops. */
    private static LinearGradient linearGradientFor(ThingBackground bg, float width, float height) {
        float x0, y0, x1, y1;
        switch (bg.orientation) {
            case L_R:   x0 = 0;     y0 = 0;      x1 = width; y1 = 0;      break;
            case T_B:   x0 = 0;     y0 = 0;      x1 = 0;     y1 = height; break;
            case LT_RB: x0 = 0;     y0 = 0;      x1 = width; y1 = height; break;
            case RT_LB: x0 = width; y0 = 0;      x1 = 0;     y1 = height; break;
            case LB_RT: x0 = 0;     y0 = height; x1 = width; y1 = 0;      break;
            case RB_LT: x0 = width; y0 = height; x1 = 0;     y1 = 0;      break;
            case R_L:   x0 = width; y0 = 0;      x1 = 0;     y1 = 0;      break;
            case B_T:   x0 = 0;     y0 = height; x1 = 0;     y1 = 0;      break;
            default:    x0 = 0;     y0 = 0;      x1 = width; y1 = 0;      break;
        }
        return new LinearGradient(
                x0, y0, x1, y1,
                bg.color, bg.endColor, Shader.TileMode.CLAMP);
    }

    /**
     * Tint {@code source} with {@code bg}. PURE → mutated original with a
     * solid {@link android.graphics.PorterDuff.Mode#SRC_ATOP} colour filter
     * (cheap, in-place). GRADIENT → render the drawable to an offscreen bitmap
     * as an alpha mask, then fill that mask with a {@link LinearGradient} via
     * {@code SRC_IN}, and wrap the result in a {@link android.graphics.drawable.BitmapDrawable}.
     *
     * <p>Caller gets a fresh drawable for GRADIENT (safe to setBounds / install
     * via {@code setCompoundDrawables}); the original {@code source} is left
     * untouched in that branch. Use this anywhere a single-tone icon needs to
     * adopt the accent — radio check, lock, small UI glyphs, etc.
     */
    public static Drawable tintDrawable(
            android.content.res.Resources res, Drawable source, ThingBackground bg) {
        if (source == null || bg == null) return source;
        if (bg.mode == ThingBackground.Mode.PURE) {
            Drawable d = source.mutate();
            d.setColorFilter(bg.color, android.graphics.PorterDuff.Mode.SRC_ATOP);
            return d;
        }
        int w = source.getIntrinsicWidth();
        int h = source.getIntrinsicHeight();
        if (w <= 0) w = 48;
        if (h <= 0) h = 48;

        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);

        // 1. Draw the icon onto the bitmap with no tint so we get its alpha mask
        //    (drawable's own paint draws in its native colour — usually opaque
        //    black for these single-tone vector glyphs).
        Drawable mask = source.mutate();
        mask.setBounds(0, 0, w, h);
        // Ensure no leftover filter from a previous tint.
        mask.setColorFilter(null);
        mask.draw(c);

        // 2. Overlay a rect filled with the gradient, masked by what's already
        //    there. SRC_IN keeps the destination's alpha (the icon shape) and
        //    replaces its colour with the source (the gradient).
        android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setShader(linearGradientFor(bg, w, h));
        p.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.SRC_IN));
        c.drawRect(0, 0, w, h, p);

        return new android.graphics.drawable.BitmapDrawable(res, out);
    }

    /**
     * Paint a {@link com.google.android.material.tabs.TabLayout}'s indicator with
     * {@code bg}. PURE → solid indicator colour. GRADIENT → a {@link GradientDrawable}
     * used as the selected-tab indicator drawable (Material's TabLayout accepts a
     * Drawable here).
     *
     * <p>Note: the indicator drawable is laid out by TabLayout to span the
     * selected tab's width — exactly the kind of "any width" use case where a
     * GradientDrawable shines.
     */
    public static void applyTabIndicator(
            com.google.android.material.tabs.TabLayout tabLayout, ThingBackground bg) {
        if (tabLayout == null || bg == null) return;
        if (bg.mode == ThingBackground.Mode.PURE) {
            tabLayout.setSelectedTabIndicator((Drawable) null);
            tabLayout.setSelectedTabIndicatorColor(bg.color);
        } else {
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setOrientation(toGdOrientation(bg.orientation));
            gd.setColors(new int[] { bg.color, bg.endColor });
            tabLayout.setSelectedTabIndicator(gd);
        }
    }

    /**
     * Render {@code bg} as a {@code width × height} bitmap with the given
     * {@code alpha} (0-255) applied to every stop. PURE → uniform color
     * bitmap; GRADIENT → {@link GradientDrawable} rasterised to the bitmap.
     * Used by paths that can't accept a {@link Shader} or {@link android.graphics.drawable.Drawable}
     * directly — notably {@link android.widget.RemoteViews}-driven AppWidget
     * surfaces (RemoteViews accepts a {@link android.graphics.Bitmap} via
     * {@code setImageViewBitmap}, but no shader / drawable).
     *
     * <p>Pass {@code alpha=255} for opaque output.
     */
    public static android.graphics.Bitmap renderBackgroundBitmap(
            ThingBackground bg, int width, int height, int alpha) {
        if (width <= 0 || height <= 0) return null;
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(
                width, height, android.graphics.Bitmap.Config.ARGB_8888);
        if (bg == null) return bm;
        if (bg.mode == ThingBackground.Mode.PURE) {
            int color = (alpha << 24) | (bg.color & 0x00FFFFFF);
            bm.eraseColor(color);
            return bm;
        }
        // GRADIENT: rasterise a GradientDrawable into the bitmap canvas.
        int s = (alpha << 24) | (bg.color    & 0x00FFFFFF);
        int e = (alpha << 24) | (bg.endColor & 0x00FFFFFF);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setOrientation(toGdOrientation(bg.orientation));
        gd.setColors(new int[] { s, e });
        gd.setBounds(0, 0, width, height);
        gd.draw(new android.graphics.Canvas(bm));
        return bm;
    }

    /**
     * Build a translucent {@link GradientDrawable} from {@code bg} where every
     * stop has its alpha replaced with {@code alpha} (0-255). PURE collapses
     * to a uniform rectangle; GRADIENT keeps its orientation but with
     * see-through stops. Used for overlay backgrounds that need to dim the
     * accent (e.g. NoticeableNotificationActivity's half-overlay card).
     */
    public static GradientDrawable makeTranslucentGradient(ThingBackground bg, int alpha) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        int s = (alpha << 24) | (bg.color    & 0x00FFFFFF);
        int e = (alpha << 24) | (bg.endColor & 0x00FFFFFF);
        gd.setOrientation(toGdOrientation(bg.orientation));
        gd.setColors(new int[] { s, e });
        return gd;
    }

    /** Map our {@link ThingBackground.Orientation} → platform {@link GradientDrawable.Orientation}. */
    private static GradientDrawable.Orientation toGdOrientation(ThingBackground.Orientation o) {
        switch (o) {
            case L_R:   return GradientDrawable.Orientation.LEFT_RIGHT;
            case T_B:   return GradientDrawable.Orientation.TOP_BOTTOM;
            case LT_RB: return GradientDrawable.Orientation.TL_BR;
            case RT_LB: return GradientDrawable.Orientation.TR_BL;
            case LB_RT: return GradientDrawable.Orientation.BL_TR;
            case RB_LT: return GradientDrawable.Orientation.BR_TL;
            case R_L:   return GradientDrawable.Orientation.RIGHT_LEFT;
            case B_T:   return GradientDrawable.Orientation.BOTTOM_TOP;
        }
        return GradientDrawable.Orientation.LEFT_RIGHT;
    }
}
