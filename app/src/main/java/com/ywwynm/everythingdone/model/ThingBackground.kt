package com.ywwynm.everythingdone.model

import android.graphics.Color
import org.json.JSONException
import org.json.JSONObject

/**
 * The visual background of a thing — either a single (PURE) color, or a two-color
 * linear GRADIENT. Eventually persisted as a JSON string in the `background`
 * column of the `things` table (added in Phase 3 of the color-system migration;
 * see COLOR_MIGRATION_PLAN.md). For now, Phase 2 only wraps the legacy int color.
 *
 * Immutable. Create with the [pure] / [gradient] factories. Use
 * [com.ywwynm.everythingdone.utils.BackgroundUtil.applyBackground] /
 * `applyCardBackground` to paint it onto a view.
 */
class ThingBackground private constructor(
    @JvmField val mode: Mode,
    @JvmField val color: Int,
    @JvmField val endColor: Int,
    @JvmField val orientation: Orientation
) {

    enum class Mode { PURE, GRADIENT }

    /**
     * Gradient direction enum mirroring the Everything-Android Kotlin reference's
     * `GradientOrientation`. Used only when [mode] is [Mode.GRADIENT].
     */
    enum class Orientation { L_R, T_B, LT_RB, RT_LB, LB_RT, RB_LT, R_L, B_T }

    /**
     * The legacy single-int "color" of this background, usable wherever an Android API
     * only accepts a 32-bit ARGB int (notification setColor, widget RemoteViews,
     * single-channel tints, etc.). For PURE this is the color itself; for GRADIENT
     * it's the arithmetic mean of start/end in RGB space. Phase 4 may switch to a
     * perceptually-uniform mean later.
     */
    fun representativeColor(): Int {
        if (mode == Mode.PURE) return color
        val r = (Color.red(color)   + Color.red(endColor))   / 2
        val g = (Color.green(color) + Color.green(endColor)) / 2
        val b = (Color.blue(color)  + Color.blue(endColor))  / 2
        return Color.rgb(r, g, b)
    }

    /** Serialize to a JSON string suitable for persisting in the `background` DB column. */
    fun toJson(): String? {
        try {
            val o = JSONObject()
            o.put(K_MODE,  mode.name)
            o.put(K_COLOR, color)
            if (mode == Mode.GRADIENT) {
                o.put(K_END,    endColor)
                o.put(K_ORIENT, orientation.name)
            }
            return o.toString()
        } catch (e: JSONException) {
            // putting ints / strings can't actually throw, but org.json's checked
            // exception forces a catch. Fall back to a safe PURE encoding.
            return "{\"" + K_MODE + "\":\"PURE\",\"" + K_COLOR + "\":" + color + "}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ThingBackground) return false
        val that = other
        if (this.mode !== that.mode) return false
        if (this.mode == Mode.PURE) return this.color == that.color
        return this.color == that.color
                && this.endColor == that.endColor
                && this.orientation === that.orientation
    }

    override fun hashCode(): Int {
        var h = mode.hashCode()
        h = h * 31 + color
        if (mode == Mode.GRADIENT) {
            h = h * 31 + endColor
            h = h * 31 + orientation.hashCode()
        }
        return h
    }

    override fun toString(): String {
        if (mode == Mode.PURE) {
            return "PURE(#" + Integer.toHexString(color) + ")"
        }
        return "GRADIENT(#" + Integer.toHexString(color) +
                "->#" + Integer.toHexString(endColor) +
                " " + orientation + ")"
    }

    companion object {

        // -----------------------------------------------------------------------
        // JSON I/O — persisted shape:
        //   PURE:     {"mode":"PURE","color":-65536}
        //   GRADIENT: {"mode":"GRADIENT","color":-65536,"endColor":-16711936,"orientation":"LB_RT"}
        // -----------------------------------------------------------------------

        private const val K_MODE: String   = "mode"
        private const val K_COLOR: String  = "color"
        private const val K_END: String    = "endColor"
        private const val K_ORIENT: String = "orientation"

        /** A pure (single-color) background. */
        @JvmStatic
        fun pure(color: Int): ThingBackground? {
            return ThingBackground(Mode.PURE, color, color, Orientation.L_R)
        }

        /** A two-color linear gradient background. */
        @JvmStatic
        fun gradient(startColor: Int, endColor: Int, orientation: Orientation?): ThingBackground? {
            return ThingBackground(Mode.GRADIENT, startColor, endColor, orientation!!)
        }

        /**
         * A random [ThingBackground] matching the production new-thing
         * distribution: 50/50 PURE vs GRADIENT, fully random RGB (per the
         * COLOR_MIGRATION_PLAN.md "no HSL clamp" rule). Convenience for callers
         * that need a one-shot random bg without going through App.rollBackground —
         * notably `DBHelper.generateInsertInitialSQL` on fresh install.
         */
        @JvmStatic
        fun fromRandom(): ThingBackground? {
            val rng = java.util.Random()
            val s = Color.rgb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
            if (rng.nextBoolean()) {
                return pure(s)
            }
            val e = Color.rgb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
            val orientations = Orientation.values()
            return gradient(s, e, orientations[rng.nextInt(orientations.size)])
        }

        /**
         * Parse a previously-[toJson]-serialized string. Returns `null` when
         * `json` is `null` / empty / malformed, so the caller can fall back to
         * legacy palette behaviour (eg. `ThingBackground.pure(thing.getColor())`).
         */
        @JvmStatic
        fun fromJson(json: String?): ThingBackground? {
            if (json == null || json.isEmpty()) return null
            try {
                val o = JSONObject(json)
                val modeStr = o.optString(K_MODE, Mode.PURE.name)
                val color   = o.optInt(K_COLOR, 0)
                val mode    = Mode.valueOf(modeStr)
                if (mode == Mode.PURE) {
                    return pure(color)
                }
                val endColor = o.optInt(K_END, color)
                val orStr    = o.optString(K_ORIENT, Orientation.L_R.name)
                val orientation: Orientation
                try { orientation = Orientation.valueOf(orStr) }
                catch (ex: IllegalArgumentException) { return gradient(color, endColor, Orientation.L_R) }
                return gradient(color, endColor, orientation)
            } catch (e: JSONException) {
                return null
            } catch (e: IllegalArgumentException) {
                return null
            }
        }
    }
}
