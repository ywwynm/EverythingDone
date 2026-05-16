package com.ywwynm.everythingdone.utils;

import android.graphics.Color;
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
     * Paint {@code background} onto {@code view}. For now (Phase 2) only PURE mode
     * is wired up; GRADIENT mode degrades to the representative color until Phase 4
     * adds real GradientDrawable support.
     */
    public static void applyBackground(View view, ThingBackground background) {
        if (view == null || background == null) return;
        if (background.mode == ThingBackground.Mode.PURE) {
            view.setBackgroundColor(background.color);
        } else {
            // Phase 4 will replace this with a GradientDrawable.
            view.setBackgroundColor(background.representativeColor());
        }
    }

    /**
     * Paint {@code background} onto a {@link CardView}. Same Phase 2 semantics as
     * {@link #applyBackground(View, ThingBackground)} — Phase 4 will swap in a
     * GradientDrawable wrapper for the GRADIENT branch.
     */
    public static void applyCardBackground(CardView cv, ThingBackground background) {
        if (cv == null || background == null) return;
        if (background.mode == ThingBackground.Mode.PURE) {
            cv.setCardBackgroundColor(background.color);
        } else {
            cv.setCardBackgroundColor(background.representativeColor());
        }
    }
}
