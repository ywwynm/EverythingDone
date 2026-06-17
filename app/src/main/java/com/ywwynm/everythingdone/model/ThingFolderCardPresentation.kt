package com.ywwynm.everythingdone.model

import androidx.annotation.IntDef
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

data class ThingFolderCardPresentation(
    @param:Mode val mode: Int = MODE_SUMMARY,
    val thumbnailLimit: Int = DEFAULT_THUMBNAIL_LIMIT,
    @param:SpanMode val spanMode: Int = SPAN_NORMAL
) {

    fun toJson(): String {
        try {
            val o = JSONObject()
            o.put(K_VERSION, VERSION)
            o.put(K_MODE, normalizeMode(mode))
            o.put(K_THUMBNAIL_LIMIT, normalizeThumbnailLimit(thumbnailLimit))
            o.put(K_SPAN_MODE, normalizeSpanMode(spanMode))
            return o.toString()
        } catch (_: JSONException) {
            return DEFAULT_JSON
        }
    }

    fun withMode(@Mode mode: Int): ThingFolderCardPresentation {
        return copy(mode = normalizeMode(mode))
    }

    fun withThumbnailLimit(limit: Int): ThingFolderCardPresentation {
        return copy(thumbnailLimit = normalizeThumbnailLimit(limit))
    }

    fun withSpanMode(@SpanMode spanMode: Int): ThingFolderCardPresentation {
        return copy(spanMode = normalizeSpanMode(spanMode))
    }

    fun effectiveThumbnailPreviewLimit(): Int {
        return if (normalizeSpanMode(spanMode) == SPAN_FULL) {
            FULL_SPAN_THUMBNAIL_PREVIEW_LIMIT
        } else {
            NORMAL_THUMBNAIL_PREVIEW_LIMIT
        }
    }

    @IntDef(MODE_SUMMARY, MODE_THUMBNAILS)
    @Retention(AnnotationRetention.SOURCE)
    annotation class Mode

    @IntDef(SPAN_NORMAL, SPAN_FULL)
    @Retention(AnnotationRetention.SOURCE)
    annotation class SpanMode

    companion object {
        const val VERSION: Int = 1

        const val MODE_SUMMARY: Int = 0
        const val MODE_THUMBNAILS: Int = 1

        const val SPAN_NORMAL: Int = 0
        const val SPAN_FULL: Int = 1

        const val DEFAULT_THUMBNAIL_LIMIT: Int = 4
        const val MIN_THUMBNAIL_LIMIT: Int = 1
        const val MAX_THUMBNAIL_LIMIT: Int = 12

        const val NORMAL_THUMBNAIL_PREVIEW_LIMIT: Int = 3
        const val FULL_SPAN_THUMBNAIL_PREVIEW_LIMIT: Int = 6

        const val DEFAULT_JSON: String =
            "{\"version\":1,\"mode\":0,\"thumbnailLimit\":4,\"spanMode\":0}"

        private const val K_VERSION = "version"
        private const val K_MODE = "mode"
        private const val K_THUMBNAIL_LIMIT = "thumbnailLimit"
        private const val K_SPAN_MODE = "spanMode"

        @JvmStatic
        fun default(): ThingFolderCardPresentation {
            return ThingFolderCardPresentation()
        }

        @JvmStatic
        fun fromJson(json: String?): ThingFolderCardPresentation? {
            if (json.isNullOrEmpty()) return null
            try {
                val o = JSONObject(json)
                return ThingFolderCardPresentation(
                    mode = normalizeMode(o.optInt(K_MODE, MODE_SUMMARY)),
                    thumbnailLimit = normalizeThumbnailLimit(
                        o.optInt(K_THUMBNAIL_LIMIT, DEFAULT_THUMBNAIL_LIMIT)
                    ),
                    spanMode = normalizeSpanMode(o.optInt(K_SPAN_MODE, SPAN_NORMAL))
                )
            } catch (_: JSONException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
        }

        @Mode
        @JvmStatic
        fun normalizeMode(mode: Int): Int {
            return when (mode) {
                MODE_THUMBNAILS -> MODE_THUMBNAILS
                else -> MODE_SUMMARY
            }
        }

        @SpanMode
        @JvmStatic
        fun normalizeSpanMode(spanMode: Int): Int {
            return when (spanMode) {
                SPAN_FULL -> SPAN_FULL
                else -> SPAN_NORMAL
            }
        }

        @JvmStatic
        fun normalizeThumbnailLimit(limit: Int): Int {
            return max(MIN_THUMBNAIL_LIMIT, min(MAX_THUMBNAIL_LIMIT, limit))
        }
    }
}
