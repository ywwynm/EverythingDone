package com.ywwynm.everythingdone.utils;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

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
     * PURE: {@link CardView#setCardBackgroundColor}. GRADIENT: a GradientDrawable
     * set directly on the CardView itself (with the CardView's corner radius applied
     * so the rounded outline still matches). CardView's outline shadow continues to
     * work because the elevation-based shadow uses the view's outline, not the
     * background drawable shape.
     */
    public static void applyCardBackground(CardView cv, ThingBackground background) {
        if (cv == null || background == null) return;
        if (background.mode == ThingBackground.Mode.PURE) {
            // CardView's own setCardBackgroundColor resets its internal RoundRect
            // drawable, naturally clobbering any GradientDrawable we may have set.
            cv.setCardBackgroundColor(background.color);
        } else {
            GradientDrawable gd;
            Drawable existing = cv.getBackground();
            if (existing instanceof GradientDrawable) {
                gd = (GradientDrawable) existing;
            } else {
                gd = new GradientDrawable();
                gd.setShape(GradientDrawable.RECTANGLE);
                gd.setCornerRadius(cv.getRadius());
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
