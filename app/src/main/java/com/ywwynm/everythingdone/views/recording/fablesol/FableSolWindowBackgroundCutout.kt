package com.ywwynm.everythingdone.views.recording.fablesol

import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

/**
 * 只在祖先背景绘制时留出海浪区域，不对已经包含前景控件的窗口执行 CLEAR。
 * 原生文字、按钮及粒子恢复遮盖仍由原窗口绘制；轮廓、阴影和输入保持原样。
 */
internal class FableSolWindowBackgroundCutout(private val source: View) {
    private data class Entry(val owner: View, val original: Drawable, val cutout: Cutout,
                             val installed: Drawable?)
    private val entries = mutableListOf<Entry>()
    private val sourceAt = IntArray(2)
    private val ownerAt = IntArray(2)
    private var active = false

    fun synchronize(enabled: Boolean) {
        if (!enabled) { restore(); return }
        active = true
        source.getLocationOnScreen(sourceAt)
        var owner: View? = source
        while (owner != null) {
            val view = owner
            var entry = entries.firstOrNull { it.owner === view }
            // 深浅色原地切换可替换背景，不能在关闭时恢复已经过期的 Drawable。
            if (entry != null && view.background !== entry.installed) {
                entries.remove(entry)
                entry = null
            }
            if (entry == null) view.background?.let { original ->
                val cutout = Cutout(original)
                view.background = cutout
                // View 替换旧背景会清掉旧 Drawable 的 callback，重新连接代理。
                original.callback = cutout
                entry = Entry(view, original, cutout, view.background).also(entries::add)
            }
            view.getLocationOnScreen(ownerAt)
            entry?.cutout?.setHole(sourceAt[0] - ownerAt[0], sourceAt[1] - ownerAt[1],
                source.width, source.height)
            owner = view.parent as? View
        }
    }

    fun restore() {
        if (!active && entries.isEmpty()) return
        active = false
        entries.forEach { entry ->
            if (entry.owner.background === entry.installed) entry.owner.background = entry.original
        }
        entries.clear()
    }

    fun diagnostics(): JSONObject = JSONObject().put("active", active)
        .put("policy", "background_clip_without_window_clear")
        .put("backgrounds", JSONArray(entries.map { entry ->
            JSONObject().put("owner", entry.owner.javaClass.simpleName)
                .put("ownerId", entry.owner.id).put("hole", entry.cutout.hole.toShortString())
                .put("installed", entry.owner.background === entry.installed)
        }))

    private class Cutout(original: Drawable) : DrawableWrapper(original) {
        val hole = Rect()

        fun setHole(left: Int, top: Int, width: Int, height: Int) {
            if (hole.left == left && hole.top == top && hole.width() == width && hole.height() == height) return
            hole.set(left, top, left + width, top + height)
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            try {
                if (!hole.isEmpty) canvas.clipOutRect(hole)
                super.draw(canvas)
            } finally { canvas.restoreToCount(save) }
        }

        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        // 不把背景的透明洞当作整个窗口的透明提示；洞中仍有原生前景控件。
        override fun getTransparentRegion(): Region? = null
    }
}
