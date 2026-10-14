package com.ywwynm.everythingdone.model

import org.json.JSONException
import org.json.JSONObject

/** 同一张做事背景分别保存横竖屏取景；比例由当前显示区域决定。 */
data class DoingBackgroundCrops(
    val portrait: Frame = Frame(),
    val landscape: Frame = Frame()
) {
    data class Frame(val centerX: Double = 0.5, val centerY: Double = 0.5, val scale: Double = 1.0) {
        fun normalized() = Frame(
            if (centerX.isFinite()) centerX.coerceIn(0.0, 1.0) else 0.5,
            if (centerY.isFinite()) centerY.coerceIn(0.0, 1.0) else 0.5,
            if (scale.isFinite()) scale.coerceIn(1.0, 3.0) else 1.0
        )

        internal fun toJson() = normalized().let {
            JSONObject().put("centerX", it.centerX).put("centerY", it.centerY).put("scale", it.scale)
        }
    }

    fun forSize(width: Int, height: Int): Frame = forOrientation(width > height)
    fun forOrientation(isLandscape: Boolean): Frame = (if (isLandscape) landscape else portrait).normalized()
    fun withFrame(isLandscape: Boolean, frame: Frame): DoingBackgroundCrops =
        if (isLandscape) copy(landscape = frame.normalized()) else copy(portrait = frame.normalized())

    fun toJson(): String = JSONObject().put("portrait", portrait.toJson())
        .put("landscape", landscape.toJson()).toString()

    companion object {
        fun aspectRatio(width: Int, height: Int, isLandscape: Boolean): Double {
            val shortSide = minOf(width, height).coerceAtLeast(1).toDouble()
            val longSide = maxOf(width, height).coerceAtLeast(1).toDouble()
            return if (isLandscape) longSide / shortSide else shortSide / longSide
        }

        fun fromJson(value: String?): DoingBackgroundCrops {
            if (value.isNullOrBlank()) return DoingBackgroundCrops()
            return try {
                val json = JSONObject(value)
                fun frame(key: String): Frame {
                    val item = json.optJSONObject(key) ?: return Frame()
                    return Frame(item.optDouble("centerX", 0.5), item.optDouble("centerY", 0.5),
                        item.optDouble("scale", 1.0)).normalized()
                }
                DoingBackgroundCrops(frame("portrait"), frame("landscape"))
            } catch (_: JSONException) {
                DoingBackgroundCrops()
            }
        }
    }
}
