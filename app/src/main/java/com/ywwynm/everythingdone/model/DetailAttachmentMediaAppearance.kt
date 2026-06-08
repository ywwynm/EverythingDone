package com.ywwynm.everythingdone.model

import org.json.JSONException
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

data class DetailAttachmentMediaAppearance(
    val sources: Map<String, SourceAppearance> = emptyMap()
) {

    fun toJson(): String {
        try {
            val o = JSONObject()
            o.put(K_VERSION, VERSION)
            val sourcesJson = JSONObject()
            for ((key, value) in sources) {
                sourcesJson.put(key, value.toJsonObject())
            }
            o.put(K_SOURCES, sourcesJson)
            return o.toString()
        } catch (_: JSONException) {
            return DEFAULT_JSON
        }
    }

    fun isDefault(): Boolean = sources.isEmpty()

    fun source(key: String?): SourceAppearance? {
        if (key.isNullOrEmpty()) return null
        return sources[key]
    }

    fun withSource(key: String, source: SourceAppearance?): DetailAttachmentMediaAppearance {
        if (key.isEmpty()) return this
        val newSources = LinkedHashMap(sources)
        if (source == null) {
            newSources.remove(key)
        } else {
            newSources[key] = source
        }
        return copy(sources = newSources)
    }

    fun removeSource(key: String?): DetailAttachmentMediaAppearance {
        if (key.isNullOrEmpty() || sources.isEmpty()) return this
        val newSources = LinkedHashMap(sources)
        newSources.remove(key)
        return copy(sources = newSources)
    }

    fun retainSources(availableKeys: Set<String>): DetailAttachmentMediaAppearance {
        if (sources.isEmpty()) return this
        val kept = LinkedHashMap<String, SourceAppearance>()
        for ((key, value) in sources) {
            if (availableKeys.contains(key)) kept[key] = value
        }
        return copy(sources = kept)
    }

    data class SourceAppearance(
        val fileSize: Long? = null,
        val lastModified: Long? = null,
        val fullSpanEnabled: Boolean = false,
        val videoFrameMs: Long? = null,
        val presentations: Map<String, MediaPresentationAppearance> = emptyMap()
    ) {

        fun toJsonObject(): JSONObject {
            val o = JSONObject()
            putNullableLong(o, K_FILE_SIZE, fileSize)
            putNullableLong(o, K_LAST_MODIFIED, lastModified)
            o.put(K_FULL_SPAN_ENABLED, fullSpanEnabled)
            putNullableLong(o, K_VIDEO_FRAME_MS, videoFrameMs)
            val presentationsJson = JSONObject()
            for ((key, value) in presentations) {
                if (isKnownPresentation(key)) {
                    presentationsJson.put(key, value.toJsonObject(key))
                }
            }
            o.put(K_PRESENTATIONS, presentationsJson)
            return o
        }

        fun presentation(key: String): MediaPresentationAppearance? {
            return presentations[key]
        }

        fun presentationOrDefault(key: String): MediaPresentationAppearance {
            return presentation(key) ?: MediaPresentationAppearance.defaultFor(key)
        }

        fun withPresentation(
            key: String,
            presentation: MediaPresentationAppearance?
        ): SourceAppearance {
            if (!isKnownPresentation(key)) return this
            val newPresentations = LinkedHashMap(presentations)
            if (presentation == null) {
                newPresentations.remove(key)
            } else {
                newPresentations[key] = presentation.normalizedFor(key)
            }
            return copy(presentations = newPresentations)
        }

        fun withFullSpanEnabled(enabled: Boolean): SourceAppearance {
            return copy(fullSpanEnabled = enabled)
        }

        fun withVideoFrameMs(frameMs: Long?): SourceAppearance {
            return copy(videoFrameMs = frameMs?.let { max(0L, it) })
        }

        fun ensurePresentation(key: String, seedFromKey: String? = null): SourceAppearance {
            if (!isKnownPresentation(key) || presentations.containsKey(key)) return this
            val seedCrop = seedFromKey?.let { presentation(it)?.crop }
            return withPresentation(
                key,
                MediaPresentationAppearance(
                    targetAspectRatio = defaultTargetAspectRatio(key),
                    crop = seedCrop ?: DetailMediaCrop()
                )
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
                            presentationsJson.optJSONObject(key),
                            key
                        )
                        if (value != null && isKnownPresentation(key)) {
                            presentations[key] = value
                        }
                    }
                }
                return SourceAppearance(
                    fileSize = nullableLong(o, K_FILE_SIZE),
                    lastModified = nullableLong(o, K_LAST_MODIFIED),
                    fullSpanEnabled = o.optBoolean(K_FULL_SPAN_ENABLED, false),
                    videoFrameMs = nullableLong(o, K_VIDEO_FRAME_MS)?.let { max(0L, it) },
                    presentations = presentations
                )
            }
        }
    }

    data class MediaPresentationAppearance(
        val targetAspectRatio: Double = DEFAULT_TARGET_ASPECT_RATIO,
        val crop: DetailMediaCrop = DetailMediaCrop()
    ) {

        fun toJsonObject(presentationKey: String): JSONObject {
            val o = JSONObject()
            o.put(
                K_TARGET_ASPECT_RATIO,
                normalizeTargetAspectRatio(presentationKey, targetAspectRatio)
            )
            o.put(K_CROP, crop.toJsonObject())
            return o
        }

        fun normalizedFor(presentationKey: String): MediaPresentationAppearance {
            return copy(
                targetAspectRatio = normalizeTargetAspectRatio(
                    presentationKey,
                    targetAspectRatio
                ),
                crop = crop.normalized()
            )
        }

        companion object {
            fun defaultFor(presentationKey: String): MediaPresentationAppearance {
                return MediaPresentationAppearance(
                    targetAspectRatio = defaultTargetAspectRatio(presentationKey),
                    crop = DetailMediaCrop()
                )
            }

            fun fromJsonObject(
                o: JSONObject?,
                presentationKey: String
            ): MediaPresentationAppearance? {
                if (o == null) return null
                return MediaPresentationAppearance(
                    targetAspectRatio = normalizeTargetAspectRatio(
                        presentationKey,
                        o.optDouble(K_TARGET_ASPECT_RATIO, defaultTargetAspectRatio(presentationKey))
                    ),
                    crop = DetailMediaCrop.fromJsonObject(o.optJSONObject(K_CROP))
                        ?: DetailMediaCrop()
                )
            }
        }
    }

    data class DetailMediaCrop(
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

        fun normalized(): DetailMediaCrop {
            return copy(
                centerX = normalizeRatio(centerX),
                centerY = normalizeRatio(centerY),
                scale = normalizeUserScale(scale)
            )
        }

        companion object {
            fun fromJsonObject(o: JSONObject?): DetailMediaCrop? {
                if (o == null) return null
                return DetailMediaCrop(
                    centerX = normalizeRatio(o.optDouble(K_CENTER_X, DEFAULT_CROP_CENTER)),
                    centerY = normalizeRatio(o.optDouble(K_CENTER_Y, DEFAULT_CROP_CENTER)),
                    scale = normalizeUserScale(o.optDouble(K_SCALE, DEFAULT_USER_SCALE))
                )
            }
        }
    }

    companion object {
        const val VERSION: Int = 1
        const val PRESENTATION_GRID: String = "grid"
        const val PRESENTATION_FULL_SPAN: String = "fullSpan"
        const val DEFAULT_TARGET_ASPECT_RATIO: Double = 1.0
        const val MIN_FULL_SPAN_TARGET_ASPECT_RATIO: Double = 0.5
        const val MAX_FULL_SPAN_TARGET_ASPECT_RATIO: Double = 65.0 / 24.0
        const val DEFAULT_CROP_CENTER: Double = 0.5
        const val DEFAULT_USER_SCALE: Double = 1.0

        const val DEFAULT_JSON: String = "{\"version\":1,\"sources\":{}}"

        private const val K_VERSION = "version"
        private const val K_SOURCES = "sources"
        private const val K_FILE_SIZE = "fileSize"
        private const val K_LAST_MODIFIED = "lastModified"
        private const val K_FULL_SPAN_ENABLED = "fullSpanEnabled"
        private const val K_VIDEO_FRAME_MS = "videoFrameMs"
        private const val K_PRESENTATIONS = "presentations"
        private const val K_TARGET_ASPECT_RATIO = "targetAspectRatio"
        private const val K_CROP = "crop"
        private const val K_CENTER_X = "centerX"
        private const val K_CENTER_Y = "centerY"
        private const val K_SCALE = "scale"

        @JvmStatic
        fun default(): DetailAttachmentMediaAppearance {
            return DetailAttachmentMediaAppearance()
        }

        @JvmStatic
        fun fromJson(json: String?): DetailAttachmentMediaAppearance? {
            if (json.isNullOrEmpty()) return null
            try {
                val o = JSONObject(json)
                val sources = LinkedHashMap<String, SourceAppearance>()
                val sourcesJson = o.optJSONObject(K_SOURCES)
                if (sourcesJson != null) {
                    val keys = sourcesJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = SourceAppearance.fromJsonObject(sourcesJson.optJSONObject(key))
                        if (value != null) sources[key] = value
                    }
                }
                return DetailAttachmentMediaAppearance(sources = sources)
            } catch (_: JSONException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
        }

        @JvmStatic
        fun isKnownPresentation(key: String?): Boolean {
            return key == PRESENTATION_GRID || key == PRESENTATION_FULL_SPAN
        }

        @JvmStatic
        fun defaultTargetAspectRatio(presentationKey: String): Double {
            return when (presentationKey) {
                PRESENTATION_FULL_SPAN,
                PRESENTATION_GRID -> DEFAULT_TARGET_ASPECT_RATIO
                else -> DEFAULT_TARGET_ASPECT_RATIO
            }
        }

        @JvmStatic
        fun normalizeTargetAspectRatio(presentationKey: String, value: Double): Double {
            val positive = positiveOrNull(value) ?: defaultTargetAspectRatio(presentationKey)
            return if (presentationKey == PRESENTATION_FULL_SPAN) {
                min(MAX_FULL_SPAN_TARGET_ASPECT_RATIO, max(MIN_FULL_SPAN_TARGET_ASPECT_RATIO, positive))
            } else {
                positive
            }
        }

        private fun normalizeRatio(value: Double): Double {
            if (value.isNaN() || value.isInfinite()) return DEFAULT_CROP_CENTER
            return max(0.0, min(1.0, value))
        }

        private fun normalizeUserScale(value: Double): Double {
            if (value.isNaN() || value.isInfinite()) return DEFAULT_USER_SCALE
            return max(DEFAULT_USER_SCALE, value)
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

        private fun putNullableLong(o: JSONObject, key: String, value: Long?) {
            if (value == null) {
                o.put(key, JSONObject.NULL)
            } else {
                o.put(key, value)
            }
        }
    }
}
