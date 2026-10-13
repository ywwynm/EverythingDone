package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.bumptech.glide.load.resource.gif.GifDrawable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.IdentityHashMap

/** 媒体负载归因专用，不改变偏好、记事和缓存。只能由 debug 自动化探针调用。 */
internal class ParticleMediaLoadProbe(private val root: View) {
    private val paused = IdentityHashMap<Animatable, Boolean>()

    fun inspect(stopRunning: Boolean = false): JSONObject {
        val seen = IdentityHashMap<Drawable, Boolean>()
        val samples = JSONArray()
        val images = JSONArray()
        fun drawable(value: Drawable?) {
            if (value == null || seen.put(value, true) != null) return
            if (value is Animatable) {
                samples.put(JSONObject().put("type", value.javaClass.simpleName)
                    .put("running", value.isRunning).put("width", value.intrinsicWidth)
                    .put("height", value.intrinsicHeight).also {
                        if (value is GifDrawable) it.put("frames", value.frameCount).put("frameIndex", value.frameIndex)
                    })
                if (stopRunning && value.isRunning) { paused[value] = true; value.stop() }
            }
        }
        fun visit(view: View) {
            if (view.visibility != View.VISIBLE) return
            drawable(view.background)
            if (view is ImageView) {
                drawable(view.drawable)
                images.put(JSONObject().put("id", runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull())
                    .put("type", view.drawable?.javaClass?.simpleName).put("width", view.drawable?.intrinsicWidth)
                    .put("height", view.drawable?.intrinsicHeight).put("shown", view.isShown))
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        return JSONObject().put("animated", samples).put("images", images).put("pausedForProbe", paused.size)
    }

    fun restore() { paused.keys.forEach { it.start() }; paused.clear() }

    companion object {
        /** Linux CPU ticks：在手势前后读取，只汇总线程耗时，不采集路径或内容。 */
        fun cpu(): Map<String, Pair<String, Long>> = File("/proc/self/task").listFiles().orEmpty().mapNotNull { dir ->
            runCatching {
                val stat = File(dir, "stat").readText().substringAfterLast(") ").split(' ')
                dir.name to (File(dir, "comm").readText().trim() to (stat[11].toLong() + stat[12].toLong()))
            }.getOrNull()
        }.toMap()

        fun cpuDelta(before: Map<String, Pair<String, Long>>): JSONArray {
            val grouped = mutableMapOf<String, Long>()
            cpu().forEach { (id, current) ->
                val previous = before[id] ?: return@forEach
                if (current.first == previous.first) grouped[current.first] =
                    (grouped[current.first] ?: 0L) + (current.second - previous.second).coerceAtLeast(0)
            }
            return JSONArray().also { list -> grouped.entries.sortedByDescending { it.value }.forEach {
                if (it.value > 0) list.put(JSONObject().put("thread", it.key).put("ticks", it.value))
            } }
        }
    }
}
