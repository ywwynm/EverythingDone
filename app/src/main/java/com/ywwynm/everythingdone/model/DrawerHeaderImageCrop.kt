package com.ywwynm.everythingdone.model

import org.json.JSONException
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * The single shared crop applied to the Drawer Header Image — one aspect ratio,
 * one crop center, one user zoom — used identically by the navigation drawer and
 * the statistic screen. See CONTEXT.md ("Drawer Header Image Crop") and ADR-0008.
 *
 * [ratio] is width / height of the header target (16:9 ≈ 1.778 by default),
 * matching [com.ywwynm.everythingdone.views.ThingCardCropEditorView]'s
 * `targetAspectRatio`. [centerX]/[centerY]/[scale] mirror the project's
 * center / scale crop math ([com.ywwynm.everythingdone.helpers.MediaCropBitmapRenderer.Crop]).
 */
data class DrawerHeaderImageCrop(
    val ratio: Double = DEFAULT_RATIO,
    val centerX: Double = DEFAULT_CROP_CENTER,
    val centerY: Double = DEFAULT_CROP_CENTER,
    val scale: Double = DEFAULT_USER_SCALE
) {

    fun normalized(): DrawerHeaderImageCrop = DrawerHeaderImageCrop(
        ratio = normalizeRatio(ratio),
        centerX = normalizeCropRatio(centerX),
        centerY = normalizeCropRatio(centerY),
        scale = normalizeUserScale(scale)
    )

    fun isDefault(): Boolean {
        val n = normalized()
        return n.ratio == DEFAULT_RATIO &&
            n.centerX == DEFAULT_CROP_CENTER &&
            n.centerY == DEFAULT_CROP_CENTER &&
            n.scale == DEFAULT_USER_SCALE
    }

    fun toJson(): String {
        return try {
            val o = JSONObject()
            o.put(K_RATIO, normalizeRatio(ratio))
            o.put(K_CENTER_X, normalizeCropRatio(centerX))
            o.put(K_CENTER_Y, normalizeCropRatio(centerY))
            o.put(K_SCALE, normalizeUserScale(scale))
            o.toString()
        } catch (_: JSONException) {
            DEFAULT_JSON
        }
    }

    companion object {
        const val MIN_RATIO: Double = 0.5
        const val MAX_RATIO: Double = 65.0 / 24.0 // ≈ 2.708, 与 DetailAttachment fullSpan 上限一致
        const val DEFAULT_RATIO: Double = 16.0 / 9.0
        const val DEFAULT_CROP_CENTER: Double = 0.5
        const val DEFAULT_USER_SCALE: Double = 1.0

        const val DEFAULT_JSON: String = "{}"

        private const val K_RATIO = "ratio"
        private const val K_CENTER_X = "centerX"
        private const val K_CENTER_Y = "centerY"
        private const val K_SCALE = "scale"

        @JvmStatic
        fun default(): DrawerHeaderImageCrop = DrawerHeaderImageCrop()

        @JvmStatic
        fun fromJson(json: String?): DrawerHeaderImageCrop {
            if (json.isNullOrEmpty()) return DrawerHeaderImageCrop()
            return try {
                val o = JSONObject(json)
                DrawerHeaderImageCrop(
                    ratio = normalizeRatio(o.optDouble(K_RATIO, DEFAULT_RATIO)),
                    centerX = normalizeCropRatio(o.optDouble(K_CENTER_X, DEFAULT_CROP_CENTER)),
                    centerY = normalizeCropRatio(o.optDouble(K_CENTER_Y, DEFAULT_CROP_CENTER)),
                    scale = normalizeUserScale(o.optDouble(K_SCALE, DEFAULT_USER_SCALE))
                )
            } catch (_: JSONException) {
                DrawerHeaderImageCrop()
            } catch (_: IllegalArgumentException) {
                DrawerHeaderImageCrop()
            }
        }

        fun normalizeRatio(value: Double): Double {
            if (value.isNaN() || value.isInfinite()) return DEFAULT_RATIO
            return max(MIN_RATIO, min(MAX_RATIO, value))
        }

        private fun normalizeCropRatio(value: Double): Double {
            if (value.isNaN() || value.isInfinite()) return DEFAULT_CROP_CENTER
            return max(0.0, min(1.0, value))
        }

        private fun normalizeUserScale(value: Double): Double {
            if (value.isNaN() || value.isInfinite()) return DEFAULT_USER_SCALE
            return max(DEFAULT_USER_SCALE, value)
        }
    }
}
