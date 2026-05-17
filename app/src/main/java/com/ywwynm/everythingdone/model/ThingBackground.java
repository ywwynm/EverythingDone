package com.ywwynm.everythingdone.model;

import android.graphics.Color;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The visual background of a thing — either a single (PURE) color, or a two-color
 * linear GRADIENT. Eventually persisted as a JSON string in the {@code background}
 * column of the {@code things} table (added in Phase 3 of the color-system migration;
 * see COLOR_MIGRATION_PLAN.md). For now, Phase 2 only wraps the legacy int color.
 *
 * <p>Immutable. Create with the {@link #pure(int)} / {@link #gradient(int, int, Orientation)}
 * factories. Use {@link com.ywwynm.everythingdone.utils.BackgroundUtil#applyBackground} /
 * {@code applyCardBackground} to paint it onto a view.
 */
public final class ThingBackground {

    public enum Mode { PURE, GRADIENT }

    /**
     * Gradient direction enum mirroring the Everything-Android Kotlin reference's
     * {@code GradientOrientation}. Used only when {@link #mode} is {@link Mode#GRADIENT}.
     */
    public enum Orientation { L_R, T_B, LT_RB, RT_LB, LB_RT, RB_LT, R_L, B_T }

    public final Mode        mode;
    public final int         color;        // PURE: the color. GRADIENT: startColor.
    public final int         endColor;     // GRADIENT only.
    public final Orientation orientation;  // GRADIENT only.

    private ThingBackground(Mode mode, int color, int endColor, Orientation orientation) {
        this.mode        = mode;
        this.color       = color;
        this.endColor    = endColor;
        this.orientation = orientation;
    }

    /** A pure (single-color) background. */
    public static ThingBackground pure(int color) {
        return new ThingBackground(Mode.PURE, color, color, Orientation.L_R);
    }

    /** A two-color linear gradient background. */
    public static ThingBackground gradient(int startColor, int endColor, Orientation orientation) {
        return new ThingBackground(Mode.GRADIENT, startColor, endColor, orientation);
    }

    /**
     * A random {@link ThingBackground} matching the production new-thing
     * distribution: 50/50 PURE vs GRADIENT, fully random RGB (per the
     * COLOR_MIGRATION_PLAN.md "no HSL clamp" rule). Convenience for callers
     * that need a one-shot random bg without going through App.rollBackground —
     * notably {@code DBHelper.generateInsertInitialSQL} on fresh install.
     */
    public static ThingBackground fromRandom() {
        java.util.Random rng = new java.util.Random();
        int s = Color.rgb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256));
        if (rng.nextBoolean()) {
            return pure(s);
        }
        int e = Color.rgb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256));
        Orientation[] orientations = Orientation.values();
        return gradient(s, e, orientations[rng.nextInt(orientations.length)]);
    }

    /**
     * The legacy single-int "color" of this background, usable wherever an Android API
     * only accepts a 32-bit ARGB int (notification setColor, widget RemoteViews,
     * single-channel tints, etc.). For PURE this is the color itself; for GRADIENT
     * it's the arithmetic mean of start/end in RGB space. Phase 4 may switch to a
     * perceptually-uniform mean later.
     */
    public int representativeColor() {
        if (mode == Mode.PURE) return color;
        int r = (Color.red(color)   + Color.red(endColor))   / 2;
        int g = (Color.green(color) + Color.green(endColor)) / 2;
        int b = (Color.blue(color)  + Color.blue(endColor))  / 2;
        return Color.rgb(r, g, b);
    }

    // -----------------------------------------------------------------------
    // JSON I/O — persisted shape:
    //   PURE:     {"mode":"PURE","color":-65536}
    //   GRADIENT: {"mode":"GRADIENT","color":-65536,"endColor":-16711936,"orientation":"LB_RT"}
    // -----------------------------------------------------------------------

    private static final String K_MODE     = "mode";
    private static final String K_COLOR    = "color";
    private static final String K_END      = "endColor";
    private static final String K_ORIENT   = "orientation";

    /** Serialize to a JSON string suitable for persisting in the {@code background} DB column. */
    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put(K_MODE,  mode.name());
            o.put(K_COLOR, color);
            if (mode == Mode.GRADIENT) {
                o.put(K_END,    endColor);
                o.put(K_ORIENT, orientation.name());
            }
            return o.toString();
        } catch (JSONException e) {
            // putting ints / strings can't actually throw, but org.json's checked
            // exception forces a catch. Fall back to a safe PURE encoding.
            return "{\"" + K_MODE + "\":\"PURE\",\"" + K_COLOR + "\":" + color + "}";
        }
    }

    /**
     * Parse a previously-{@link #toJson()}-serialized string. Returns {@code null} when
     * {@code json} is {@code null} / empty / malformed, so the caller can fall back to
     * legacy palette behaviour (eg. {@code ThingBackground.pure(thing.getColor())}).
     */
    public static ThingBackground fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            String modeStr = o.optString(K_MODE, Mode.PURE.name());
            int    color   = o.optInt(K_COLOR, 0);
            Mode   mode    = Mode.valueOf(modeStr);
            if (mode == Mode.PURE) {
                return pure(color);
            }
            int    endColor = o.optInt(K_END, color);
            String orStr    = o.optString(K_ORIENT, Orientation.L_R.name());
            Orientation orientation;
            try { orientation = Orientation.valueOf(orStr); }
            catch (IllegalArgumentException ex) { orientation = Orientation.L_R; }
            return gradient(color, endColor, orientation);
        } catch (JSONException | IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ThingBackground)) return false;
        ThingBackground that = (ThingBackground) o;
        if (this.mode != that.mode) return false;
        if (this.mode == Mode.PURE) return this.color == that.color;
        return this.color == that.color
                && this.endColor == that.endColor
                && this.orientation == that.orientation;
    }

    @Override
    public int hashCode() {
        int h = mode.hashCode();
        h = h * 31 + color;
        if (mode == Mode.GRADIENT) {
            h = h * 31 + endColor;
            h = h * 31 + orientation.hashCode();
        }
        return h;
    }

    @Override
    public String toString() {
        if (mode == Mode.PURE) {
            return "PURE(#" + Integer.toHexString(color) + ")";
        }
        return "GRADIENT(#" + Integer.toHexString(color)
                + "->#" + Integer.toHexString(endColor)
                + " " + orientation + ")";
    }
}
