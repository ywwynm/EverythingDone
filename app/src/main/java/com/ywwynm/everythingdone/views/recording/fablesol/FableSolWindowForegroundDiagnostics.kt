package com.ywwynm.everythingdone.views.recording.fablesol

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Region
import android.view.PixelCopy
import android.view.View
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.R
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** 仅 debug 在交接阶段采样窗口缓冲，不读回屏幕、不记录文件名／计时文字或保存截图。 */
@TargetApi(34)
internal object FableSolWindowForegroundDiagnostics {
    private val controls = listOf(
        R.id.clock_record_audio, R.id.iv_record_main_action,
        R.id.clock_play_audio, R.id.iv_play_main_action, R.id.sb_audio_progress,
        R.id.iv_fablesol_tuning_pause, R.id.iv_fablesol_tuning_palette,
        R.id.sb_fablesol_tuning_hdr
    )

    fun sample(source: View, stage: String, current: () -> Boolean, record: (JSONObject) -> Unit) {
        if (!BuildConfig.DEBUG || !source.isAttachedToWindow || !current()) return
        val root = source.rootView
        if (root.width <= 0 || root.height <= 0) return
        val fields = JSONObject().put("stage", stage)
            .put("meaning", "window_buffer_only_not_final_screen")
            .put("windowSize", "${root.width}x${root.height}")
            .put("decorAlpha", root.alpha)
        val region = Region(0, 0, root.width, root.height)
        root.gatherTransparentRegion(region)
        fields.put("transparentHintEmpty", region.isEmpty)
            .put("transparentHintBounds", region.bounds.toShortString())
        val views = controls.mapNotNull { id -> root.findViewById<View>(id) }.map { view ->
            val at = IntArray(2).also(view::getLocationInWindow)
            val bounds = Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
            val entry = JSONObject().put("id", source.resources.getResourceEntryName(view.id))
                .put("visibility", view.visibility).put("shown", view.isShown)
                .put("alpha", effectiveAlpha(view)).put("bounds", bounds.toShortString())
                .put("layerType", view.layerType).put("z", view.z)
                .put("hintIntersects", Region(region).apply { op(bounds, Region.Op.INTERSECT) }.isEmpty.not())
            bounds to entry
        }
        fields.put("controls", JSONArray(views.map { it.second }))
        // 最大边 512，只保留各控件范围内的 alpha 数量，避免诊断反过来阻塞开场。
        val scale = minOf(1f, 512f / max(root.width, root.height))
        val bitmap = Bitmap.createBitmap((root.width * scale).roundToInt().coerceAtLeast(1),
            (root.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val request = PixelCopy.Request.Builder.ofWindow(source)
                .setSourceRect(Rect(0, 0, root.width, root.height))
                .setDestinationBitmap(bitmap).build()
            PixelCopy.request(request, source.context.mainExecutor) { result ->
                try {
                    fields.put("copyStatus", result.status).put("stillCurrent", current())
                        .put("decorAlphaAtCopy", root.alpha)
                    if (result.status == PixelCopy.SUCCESS) {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        views.forEach { (bounds, entry) ->
                            val left = floor(bounds.left * scale).toInt().coerceIn(0, bitmap.width)
                            val right = ceil(bounds.right * scale).toInt().coerceIn(left, bitmap.width)
                            val top = floor(bounds.top * scale).toInt().coerceIn(0, bitmap.height)
                            val bottom = ceil(bounds.bottom * scale).toInt().coerceIn(top, bitmap.height)
                            var nonTransparent = 0
                            var opaque = 0
                            for (y in top until bottom) for (x in left until right) {
                                val alpha = Color.alpha(pixels[y * bitmap.width + x])
                                if (alpha > 8) nonTransparent++
                                if (alpha >= 240) opaque++
                            }
                            entry.put("samplePixels", (right - left) * (bottom - top))
                                .put("nonTransparentPixels", nonTransparent).put("opaquePixels", opaque)
                        }
                    }
                    record(fields)
                } finally { bitmap.recycle() }
            }
        } catch (error: Exception) {
            bitmap.recycle()
            record(fields.put("error", error.toString()))
        }
    }

    private fun effectiveAlpha(view: View): Float {
        var alpha = 1f
        var next: View? = view
        while (next != null) { alpha *= next.alpha; next = next.parent as? View }
        return alpha
    }
}
