package com.ywwynm.everythingdone.model

import org.json.JSONException
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

data class ThingCardAppearance(
    @param:Thing.ThingCardSpanMode val spanMode: Int = Thing.THING_CARD_SPAN_NORMAL,
    @param:Thing.ThingCardImagePlacement val imagePlacement: Int =
        Thing.THING_CARD_IMAGE_PLACEMENT_DEFAULT,
    val sideMediaWidthPercent: Int = DEFAULT_SIDE_MEDIA_WIDTH_PERCENT,
    val appearanceUpdateTime: Long = 0L,
    val mediaSourceKey: String? = null,
    val mediaBackgroundEnabled: Boolean = false,
    val sources: Map<String, SourceAppearance> = emptyMap()
) {

    fun toJson(): String {
        try {
            val o = JSONObject()
            o.put(K_VERSION, VERSION)
            o.put(K_SPAN_MODE, normalizeSpanMode(spanMode))
            o.put(K_IMAGE_PLACEMENT, normalizeImagePlacement(imagePlacement))
            o.put(K_APPEARANCE_UPDATE_TIME, max(0L, appearanceUpdateTime))
            if (mediaSourceKey == null) {
                o.put(K_MEDIA_SOURCE_KEY, JSONObject.NULL)
            } else {
                o.put(K_MEDIA_SOURCE_KEY, mediaSourceKey)
            }
            o.put(K_MEDIA_BACKGROUND_ENABLED, mediaBackgroundEnabled)
            val sourcesJson = JSONObject()
            for ((key, value) in sources) {
                sourcesJson.put(key, value.toJsonObject())
            }
            o.put(K_SOURCES, sourcesJson)
            return o.toString()
        } catch (_: JSONException) {
            return default().toJson()
        }
    }

    fun withAppearanceUpdateTime(time: Long): ThingCardAppearance {
        return copy(appearanceUpdateTime = max(0L, time))
    }

    fun hasSamePresentationAs(other: ThingCardAppearance): Boolean {
        return copy(appearanceUpdateTime = 0L).toJson() ==
                other.copy(appearanceUpdateTime = 0L).toJson()
    }

    fun withSpanMode(@Thing.ThingCardSpanMode spanMode: Int): ThingCardAppearance {
        return copy(spanMode = normalizeSpanMode(spanMode))
    }

    fun withImagePlacement(
        @Thing.ThingCardImagePlacement imagePlacement: Int
    ): ThingCardAppearance {
        return copy(imagePlacement = normalizeImagePlacement(imagePlacement))
    }

    fun removeSources(keysToRemove: Set<String>): ThingCardAppearance {
        if (keysToRemove.isEmpty() || sources.isEmpty()) return this
        val kept = LinkedHashMap<String, SourceAppearance>()
        for ((key, value) in sources) {
            if (!keysToRemove.contains(key)) kept[key] = value
        }
        val newSourceKey = if (isMediaSourceNone(mediaSourceKey)) {
            mediaSourceKey
        } else if (mediaSourceKey != null && keysToRemove.contains(mediaSourceKey)) {
            null
        } else {
            mediaSourceKey
        }
        return copy(mediaSourceKey = newSourceKey, sources = kept)
    }

    fun retainSources(availableKeys: Set<String>): ThingCardAppearance {
        val kept = LinkedHashMap<String, SourceAppearance>()
        for ((key, value) in sources) {
            if (availableKeys.contains(key)) kept[key] = value
        }
        val newSourceKey = if (isMediaSourceNone(mediaSourceKey)) {
            mediaSourceKey
        } else if (mediaSourceKey != null && !availableKeys.contains(mediaSourceKey)) {
            null
        } else {
            mediaSourceKey
        }
        return copy(mediaSourceKey = newSourceKey, sources = kept)
    }

    data class SourceAppearance(
        val fileSize: Long? = null,
        val lastModified: Long? = null,
        val mediaBackgroundMaskStrength: Double = DEFAULT_MASK_STRENGTH,
        val mediaBackgroundHeightRatio: Double? = null,
        val thumbnailCrop: ThingCardThumbnailCrop? = null,
        val backgroundCrop: ThingCardMediaBackgroundCrop? = null,
        val videoFrameMs: Long? = null,
        val sideMediaDisplayAspectRatioHint: Double? = null,
        val presentations: Map<String, MediaPresentationAppearance> = emptyMap()
    ) {

        fun toJsonObject(): JSONObject {
            val o = JSONObject()
            putNullableLong(o, K_FILE_SIZE, fileSize)
            putNullableLong(o, K_LAST_MODIFIED, lastModified)
            putNullableLong(o, K_VIDEO_FRAME_MS, videoFrameMs)
            val presentationsJson = presentationsForJson()
            if (presentationsJson.isNotEmpty()) {
                val po = JSONObject()
                for ((key, value) in presentationsJson) {
                    po.put(key, value.toJsonObject())
                }
                o.put(K_PRESENTATIONS, po)
            }
            return o
        }

        fun presentation(key: String): MediaPresentationAppearance? {
            return presentations[key] ?: legacyPresentation(key)
        }

        fun withPresentation(
            key: String,
            presentation: MediaPresentationAppearance?
        ): SourceAppearance {
            val newPresentations = LinkedHashMap(presentations)
            if (presentation == null) {
                newPresentations.remove(key)
            } else {
                newPresentations[key] = presentation
            }
            return copy(presentations = newPresentations)
        }

        fun thumbnailCropWithTargetRatio(defaultTargetAspectRatio: Double): ThingCardThumbnailCrop {
            val presentation = presentation(PRESENTATION_THUMBNAIL)
            val crop = presentation?.crop ?: thumbnailCrop?.toMediaCrop()
            return ThingCardThumbnailCrop(
                centerX = crop?.centerX ?: DEFAULT_CROP_CENTER,
                centerY = crop?.centerY ?: DEFAULT_CROP_CENTER,
                scale = crop?.scale ?: DEFAULT_USER_SCALE,
                sourceAspectRatio = positiveOrNull(
                    presentation?.targetAspectRatio
                        ?: thumbnailCrop?.sourceAspectRatio
                        ?: defaultTargetAspectRatio
                )
            )
        }

        fun sidePanelCrop(): ThingCardThumbnailCrop {
            val presentation = presentation(PRESENTATION_SIDE_PANEL)
            val crop = presentation?.crop ?: thumbnailCrop?.toMediaCrop()
            return ThingCardThumbnailCrop(
                centerX = crop?.centerX ?: DEFAULT_CROP_CENTER,
                centerY = crop?.centerY ?: DEFAULT_CROP_CENTER,
                scale = crop?.scale ?: DEFAULT_USER_SCALE,
                sourceAspectRatio = positiveOrNull(presentation?.targetAspectRatio)
            )
        }

        fun sidePanelTargetAspectRatio(defaultTargetAspectRatio: Double? = null): Double? {
            return positiveOrNull(
                presentation(PRESENTATION_SIDE_PANEL)?.targetAspectRatio
                    ?: defaultTargetAspectRatio
            )
        }

        fun mediaBackgroundCrop(): ThingCardMediaBackgroundCrop {
            val crop = presentation(PRESENTATION_MEDIA_BACKGROUND)?.crop
                ?: backgroundCrop?.toMediaCrop()
            return ThingCardMediaBackgroundCrop(
                centerX = crop?.centerX ?: DEFAULT_CROP_CENTER,
                centerY = crop?.centerY ?: DEFAULT_CROP_CENTER,
                scale = crop?.scale ?: DEFAULT_USER_SCALE
            )
        }

        fun mediaBackgroundMaskStrength(): Double {
            return normalizeMaskStrength(
                presentation(PRESENTATION_MEDIA_BACKGROUND)?.maskStrength
                    ?: mediaBackgroundMaskStrength
            )
        }

        fun mediaBackgroundTargetAspectRatio(): Double? {
            val ratio = positiveOrNull(
                presentation(PRESENTATION_MEDIA_BACKGROUND)?.targetAspectRatio
            )
            if (ratio != null) return ratio
            val legacyHeightRatio = positiveOrNull(mediaBackgroundHeightRatio)
            return if (legacyHeightRatio == null) null else 1.0 / legacyHeightRatio
        }

        private fun presentationsForJson(): Map<String, MediaPresentationAppearance> {
            val normalized = LinkedHashMap<String, MediaPresentationAppearance>()
            normalized.putAll(presentations)
            legacyPresentation(PRESENTATION_THUMBNAIL)?.let {
                if (!normalized.containsKey(PRESENTATION_THUMBNAIL)) {
                    normalized[PRESENTATION_THUMBNAIL] = it
                }
            }
            legacyPresentation(PRESENTATION_MEDIA_BACKGROUND)?.let {
                if (!normalized.containsKey(PRESENTATION_MEDIA_BACKGROUND)) {
                    normalized[PRESENTATION_MEDIA_BACKGROUND] = it
                }
            }
            return normalized
        }

        private fun legacyPresentation(key: String): MediaPresentationAppearance? {
            return when (key) {
                PRESENTATION_THUMBNAIL -> legacyThumbnailPresentation()
                PRESENTATION_MEDIA_BACKGROUND -> legacyMediaBackgroundPresentation()
                else -> null
            }
        }

        private fun legacyThumbnailPresentation(): MediaPresentationAppearance? {
            val crop = thumbnailCrop ?: return null
            return MediaPresentationAppearance(
                targetAspectRatio = positiveOrNull(crop.sourceAspectRatio),
                crop = crop.toMediaCrop()
            )
        }

        private fun legacyMediaBackgroundPresentation(): MediaPresentationAppearance? {
            val targetAspectRatio = positiveOrNull(mediaBackgroundHeightRatio)?.let { 1.0 / it }
            val crop = backgroundCrop?.toMediaCrop()
            val mask = normalizeMaskStrength(mediaBackgroundMaskStrength)
            if (targetAspectRatio == null && crop == null && mask == DEFAULT_MASK_STRENGTH) {
                return null
            }
            return MediaPresentationAppearance(
                targetAspectRatio = targetAspectRatio,
                crop = crop,
                maskStrength = mask
            )
        }

        companion object {
            fun fromJsonObject(o: JSONObject?): SourceAppearance? {
                if (o == null) return null
                val presentations = LinkedHashMap<String, MediaPresentationAppearance>()
                val presentationsJson = o.optJSONObject(K_PRESENTATIONS)
                if (presentationsJson != null) {
                    val keys = presentationsJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = MediaPresentationAppearance.fromJsonObject(
                            presentationsJson.optJSONObject(key)
                        )
                        if (value != null && isKnownPresentation(key)) {
                            presentations[key] = value
                        }
                    }
                }
                return SourceAppearance(
                    fileSize = nullableLong(o, K_FILE_SIZE),
                    lastModified = nullableLong(o, K_LAST_MODIFIED),
                    mediaBackgroundMaskStrength = normalizeMaskStrength(
                        o.optDouble(K_BACKGROUND_MASK, DEFAULT_MASK_STRENGTH)
                    ),
                    mediaBackgroundHeightRatio = positiveOrNull(
                        nullableDouble(o, K_BACKGROUND_HEIGHT_RATIO)
                    ),
                    thumbnailCrop = ThingCardThumbnailCrop.fromJsonObject(
                        o.optJSONObject(K_THUMBNAIL_CROP)
                    ),
                    backgroundCrop = ThingCardMediaBackgroundCrop.fromJsonObject(
                        o.optJSONObject(K_BACKGROUND_CROP)
                    ),
                    videoFrameMs = nullableLong(o, K_VIDEO_FRAME_MS),
                    sideMediaDisplayAspectRatioHint = positiveOrNull(
                        nullableDouble(o, K_SIDE_MEDIA_DISPLAY_ASPECT_RATIO_HINT)
                    ),
                    presentations = presentations
                )
            }
        }
    }

    data class MediaPresentationAppearance(
        val targetAspectRatio: Double? = null,
        val crop: ThingCardMediaCrop? = null,
        val maskStrength: Double? = null
    ) {

        fun toJsonObject(): JSONObject {
            val o = JSONObject()
            putNullableDouble(o, K_TARGET_ASPECT_RATIO, positiveOrNull(targetAspectRatio))
            if (crop == null) {
                o.put(K_CROP, JSONObject.NULL)
            } else {
                o.put(K_CROP, crop.toJsonObject())
            }
            putNullableDouble(o, K_MASK_STRENGTH, maskStrength?.let { normalizeMaskStrength(it) })
            return o
        }

        companion object {
            fun fromJsonObject(o: JSONObject?): MediaPresentationAppearance? {
                if (o == null) return null
                return MediaPresentationAppearance(
                    targetAspectRatio = positiveOrNull(nullableDouble(o, K_TARGET_ASPECT_RATIO)),
                    crop = ThingCardMediaCrop.fromJsonObject(o.optJSONObject(K_CROP)),
                    maskStrength = nullableDouble(o, K_MASK_STRENGTH)?.let {
                        normalizeMaskStrength(it)
                    }
                )
            }
        }
    }

    data class ThingCardMediaCrop(
        val centerX: Double = DEFAULT_CROP_CENTER,
        val centerY: Double = DEFAULT_CROP_CENTER,
        val scale: Double = DEFAULT_USER_SCALE
    ) {

        fun toJsonObject(): JSONObject {
            val o = JSONObject()
            o.put(K_CENTER_X, normalizeRatio(centerX))
            o.put(K_CENTER_Y, normalizeRatio(centerY))
            o.put(K_SCALE, normalizeUserScale(scale))
            return o
        }

        companion object {
            fun fromJsonObject(o: JSONObject?): ThingCardMediaCrop? {
                if (o == null) return null
                return ThingCardMediaCrop(
                    centerX = normalizeRatio(o.optDouble(K_CENTER_X, DEFAULT_CROP_CENTER)),
                    centerY = normalizeRatio(o.optDouble(K_CENTER_Y, DEFAULT_CROP_CENTER)),
                    scale = normalizeUserScale(o.optDouble(K_SCALE, DEFAULT_USER_SCALE))
                )
            }
        }
    }

    data class ThingCardThumbnailCrop(
        val centerX: Double = DEFAULT_CROP_CENTER,
        val centerY: Double = DEFAULT_CROP_CENTER,
        val scale: Double = DEFAULT_USER_SCALE,
        val sourceAspectRatio: Double? = null
    ) {

        fun toJsonObject(): JSONObject {
            val o = JSONObject()
            o.put(K_CENTER_X, normalizeRatio(centerX))
            o.put(K_CENTER_Y, normalizeRatio(centerY))
            o.put(K_SCALE, normalizeUserScale(scale))
            putNullableDouble(o, K_SOURCE_ASPECT_RATIO, positiveOrNull(sourceAspectRatio))
            return o
        }

        fun toMediaCrop(): ThingCardMediaCrop {
            return ThingCardMediaCrop(
                centerX = centerX,
                centerY = centerY,
                scale = scale
            )
        }

        companion object {
            fun fromJsonObject(o: JSONObject?): ThingCardThumbnailCrop? {
                if (o == null) return null
                return ThingCardThumbnailCrop(
                    centerX = normalizeRatio(o.optDouble(K_CENTER_X, DEFAULT_CROP_CENTER)),
                    centerY = normalizeRatio(o.optDouble(K_CENTER_Y, DEFAULT_CROP_CENTER)),
                    scale = normalizeUserScale(o.optDouble(K_SCALE, DEFAULT_USER_SCALE)),
                    sourceAspectRatio = positiveOrNull(nullableDouble(o, K_SOURCE_ASPECT_RATIO))
                )
            }
        }
    }

    data class ThingCardMediaBackgroundCrop(
        val centerX: Double = DEFAULT_CROP_CENTER,
        val centerY: Double = DEFAULT_CROP_CENTER,
        val scale: Double = DEFAULT_USER_SCALE
    ) {

        fun toJsonObject(): JSONObject {
            val o = JSONObject()
            o.put(K_CENTER_X, normalizeRatio(centerX))
            o.put(K_CENTER_Y, normalizeRatio(centerY))
            o.put(K_SCALE, normalizeUserScale(scale))
            return o
        }

        fun toMediaCrop(): ThingCardMediaCrop {
            return ThingCardMediaCrop(
                centerX = centerX,
                centerY = centerY,
                scale = scale
            )
        }

        companion object {
            fun fromJsonObject(o: JSONObject?): ThingCardMediaBackgroundCrop? {
                if (o == null) return null
                return ThingCardMediaBackgroundCrop(
                    centerX = normalizeRatio(o.optDouble(K_CENTER_X, DEFAULT_CROP_CENTER)),
                    centerY = normalizeRatio(o.optDouble(K_CENTER_Y, DEFAULT_CROP_CENTER)),
                    scale = normalizeUserScale(o.optDouble(K_SCALE, DEFAULT_USER_SCALE))
                )
            }
        }
    }

    companion object {
        const val VERSION: Int = 2
        const val DEFAULT_SIDE_MEDIA_WIDTH_PERCENT: Int = 42
        const val MIN_SIDE_MEDIA_WIDTH_PERCENT: Int = 30
        const val MAX_SIDE_MEDIA_WIDTH_PERCENT: Int = 60
        const val DEFAULT_MASK_STRENGTH: Double = 0.45
        const val DEFAULT_CROP_CENTER: Double = 0.5
        const val DEFAULT_USER_SCALE: Double = 1.0
        const val MEDIA_SOURCE_NONE: String = "__thing_card_media_none__"
        const val PRESENTATION_THUMBNAIL: String = "thumbnail"
        const val PRESENTATION_SIDE_PANEL: String = "sidePanel"
        const val PRESENTATION_MEDIA_BACKGROUND: String = "mediaBackground"

        private const val K_VERSION = "version"
        private const val K_SPAN_MODE = "spanMode"
        private const val K_IMAGE_PLACEMENT = "imagePlacement"
        private const val K_SIDE_MEDIA_WIDTH = "sideMediaWidthPercent"
        private const val K_APPEARANCE_UPDATE_TIME = "appearanceUpdateTime"
        private const val K_MEDIA_SOURCE_KEY = "mediaSourceKey"
        private const val K_MEDIA_BACKGROUND_ENABLED = "mediaBackgroundEnabled"
        private const val K_SOURCES = "sources"

        private const val K_FILE_SIZE = "fileSize"
        private const val K_LAST_MODIFIED = "lastModified"
        private const val K_BACKGROUND_MASK = "mediaBackgroundMaskStrength"
        private const val K_BACKGROUND_HEIGHT_RATIO = "mediaBackgroundHeightRatio"
        private const val K_THUMBNAIL_CROP = "thumbnailCrop"
        private const val K_BACKGROUND_CROP = "backgroundCrop"
        private const val K_VIDEO_FRAME_MS = "videoFrameMs"
        private const val K_SIDE_MEDIA_DISPLAY_ASPECT_RATIO_HINT =
            "sideMediaDisplayAspectRatioHint"
        private const val K_PRESENTATIONS = "presentations"
        private const val K_TARGET_ASPECT_RATIO = "targetAspectRatio"
        private const val K_CROP = "crop"
        private const val K_MASK_STRENGTH = "maskStrength"

        private const val K_CENTER_X = "centerX"
        private const val K_CENTER_Y = "centerY"
        private const val K_SCALE = "scale"
        private const val K_SOURCE_ASPECT_RATIO = "sourceAspectRatio"

        @JvmStatic
        fun default(): ThingCardAppearance {
            return ThingCardAppearance()
        }

        @JvmStatic
        fun isKnownPresentation(key: String?): Boolean {
            return key == PRESENTATION_THUMBNAIL
                    || key == PRESENTATION_SIDE_PANEL
                    || key == PRESENTATION_MEDIA_BACKGROUND
        }

        @JvmStatic
        fun fromLegacy(
            @Thing.ThingCardSpanMode spanMode: Int,
            @Thing.ThingCardImagePlacement imagePlacement: Int
        ): ThingCardAppearance {
            return ThingCardAppearance(
                spanMode = normalizeSpanMode(spanMode),
                imagePlacement = normalizeImagePlacement(imagePlacement)
            )
        }

        @JvmStatic
        fun isMediaSourceNone(mediaSourceKey: String?): Boolean {
            return mediaSourceKey == MEDIA_SOURCE_NONE
        }

        @JvmStatic
        fun fromJson(json: String?): ThingCardAppearance? {
            if (json.isNullOrEmpty()) return null
            try {
                val o = JSONObject(json)
                val sources = LinkedHashMap<String, SourceAppearance>()
                val sourcesJson = o.optJSONObject(K_SOURCES)
                if (sourcesJson != null) {
                    val keys = sourcesJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = SourceAppearance.fromJsonObject(
                            sourcesJson.optJSONObject(key)
                        )
                        if (value != null) sources[key] = value
                    }
                }
                val sourceKey = if (o.isNull(K_MEDIA_SOURCE_KEY)) {
                    null
                } else {
                    val rawSourceKey = o.optString(K_MEDIA_SOURCE_KEY, "")
                    if (rawSourceKey.isEmpty()) null else rawSourceKey
                }
                return ThingCardAppearance(
                    spanMode = normalizeSpanMode(
                        o.optInt(K_SPAN_MODE, Thing.THING_CARD_SPAN_NORMAL)
                    ),
                    imagePlacement = normalizeImagePlacement(
                        o.optInt(
                            K_IMAGE_PLACEMENT,
                            Thing.THING_CARD_IMAGE_PLACEMENT_DEFAULT
                        )
                    ),
                    sideMediaWidthPercent = normalizeSideMediaWidth(
                        o.optInt(K_SIDE_MEDIA_WIDTH, DEFAULT_SIDE_MEDIA_WIDTH_PERCENT)
                    ),
                    appearanceUpdateTime = max(0L, o.optLong(K_APPEARANCE_UPDATE_TIME, 0L)),
                    mediaSourceKey = sourceKey,
                    mediaBackgroundEnabled = o.optBoolean(
                        K_MEDIA_BACKGROUND_ENABLED,
                        false
                    ),
                    sources = sources
                )
            } catch (_: JSONException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
        }

        @Thing.ThingCardSpanMode
        @JvmStatic
        fun normalizeSpanMode(spanMode: Int): Int {
            return when (spanMode) {
                Thing.THING_CARD_SPAN_FULL -> Thing.THING_CARD_SPAN_FULL
                else -> Thing.THING_CARD_SPAN_NORMAL
            }
        }

        @Thing.ThingCardImagePlacement
        @JvmStatic
        fun normalizeImagePlacement(imagePlacement: Int): Int {
            return when (imagePlacement) {
                Thing.THING_CARD_IMAGE_PLACEMENT_TOP,
                Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM,
                Thing.THING_CARD_IMAGE_PLACEMENT_LEFT,
                Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT -> imagePlacement
                else -> Thing.THING_CARD_IMAGE_PLACEMENT_DEFAULT
            }
        }

        @JvmStatic
        fun normalizeSideMediaWidth(widthPercent: Int): Int {
            return max(
                MIN_SIDE_MEDIA_WIDTH_PERCENT,
                min(MAX_SIDE_MEDIA_WIDTH_PERCENT, widthPercent)
            )
        }

        private fun normalizeRatio(value: Double): Double {
            if (value.isNaN() || value.isInfinite()) return DEFAULT_CROP_CENTER
            return max(0.0, min(1.0, value))
        }

        private fun normalizeUserScale(value: Double): Double {
            if (value.isNaN() || value.isInfinite()) return DEFAULT_USER_SCALE
            return max(DEFAULT_USER_SCALE, value)
        }

        private fun normalizeMaskStrength(value: Double): Double {
            if (value.isNaN() || value.isInfinite()) return DEFAULT_MASK_STRENGTH
            return max(0.0, min(1.0, value))
        }

        private fun positiveOrNull(value: Double?): Double? {
            if (value == null || value.isNaN() || value.isInfinite() || value <= 0.0) {
                return null
            }
            return value
        }

        private fun nullableLong(o: JSONObject, key: String): Long? {
            return if (o.isNull(key)) null else o.optLong(key)
        }

        private fun nullableDouble(o: JSONObject, key: String): Double? {
            return if (o.isNull(key)) null else o.optDouble(key)
        }

        private fun putNullableLong(o: JSONObject, key: String, value: Long?) {
            if (value == null) {
                o.put(key, JSONObject.NULL)
            } else {
                o.put(key, value)
            }
        }

        private fun putNullableDouble(o: JSONObject, key: String, value: Double?) {
            if (value == null) {
                o.put(key, JSONObject.NULL)
            } else {
                o.put(key, value)
            }
        }
    }
}
