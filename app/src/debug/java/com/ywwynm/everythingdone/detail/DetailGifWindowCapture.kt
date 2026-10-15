package com.ywwynm.everythingdone.detail

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Window
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** 隔离回归专用：采样已提交到窗口的像素，补充 ImageView 的软件绘制记录。 */
internal class DetailGifWindowCapture(private val window: Window, private val output: File) {
    data class Region(val position: Int, val rect: Rect, val frame: Int, val drawable: Int,
                      val running: Boolean, val ready: Boolean)

    private val records = JSONArray()
    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private var active = true
    private var previous: Bitmap? = null
    private var spare: Bitmap? = null
    private var sequence = 0
    private var previousStage = ""
    private var saveNext = false
    private var saved = 0
    private var previousOffset = -1

    fun request(stage: String, regions: List<Region>, clip: Rect, offset: Int) {
        if (!active || pending || regions.isEmpty()) return
        val decor = window.decorView
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0) return
        val location = IntArray(2).also(decor::getLocationOnScreen)
        val captureWidth = 480
        val captureHeight = height * captureWidth / width
        val bitmap = spare?.takeIf { it.width == captureWidth && it.height == captureHeight }
            ?: Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
        if (spare !== bitmap) spare?.recycle()
        spare = null
        val requested = SystemClock.uptimeMillis()
        pending = true
        PixelCopy.request(window, Rect(0, 0, width, height), bitmap, { status ->
            val row = JSONObject().put("seq", ++sequence).put("stage", stage)
                .put("requested", requested).put("completed", SystemClock.uptimeMillis())
                .put("status", status).put("offset", offset)
            val measures = JSONArray()
            var suspect = false
            if (status == PixelCopy.SUCCESS) {
                for (region in regions) {
                    val rect = Rect(region.rect)
                    rect.inset((rect.width() * .12f).toInt(), (rect.height() * .2f).toInt())
                    if (!rect.intersect(clip) || rect.height() < 40 || rect.width() < 40) continue
                    var background = 0
                    var dark = 0
                    var count = 0
                    for (y in 0 until 16) for (x in 0 until 16) {
                        val px = ((rect.left - location[0] + (x + .5f) * rect.width() / 16) * captureWidth / width)
                            .toInt().coerceIn(0, bitmap.width - 1)
                        val py = ((rect.top - location[1] + (y + .5f) * rect.height() / 16) * captureHeight / height)
                            .toInt().coerceIn(0, bitmap.height - 1)
                        val color = bitmap.getPixel(px, py)
                        // 夹具的纯色记事背景；容许窗口颜色转换和缩放的少量误差。
                        if (abs(Color.red(color) - 45) <= 8 && abs(Color.green(color) - 112) <= 8 &&
                            abs(Color.blue(color) - 136) <= 8) background++
                        if (Color.red(color) + Color.green(color) + Color.blue(color) < 30) dark++
                        count++
                    }
                    measures.put(JSONObject().put("pos", region.position).put("frame", region.frame)
                        .put("drawable", region.drawable).put("running", region.running).put("ready", region.ready)
                        .put("background", background).put("dark", dark).put("pixels", count)
                        .put("rect", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))))
                    if (background > count / 10 && region.ready && previousStage == stage && previousOffset == offset) suspect = true
                }
                if (saveNext && saved < 36) save(bitmap, "window-$sequence-after")
                saveNext = false
                if ((suspect || previousStage != stage) && saved < 36) {
                    if (suspect) previous?.let { save(it, "window-$sequence-before") }
                    save(bitmap, "window-$sequence-${if (suspect) "suspect" else "stage"}")
                    saveNext = suspect
                }
                previousStage = stage
                previousOffset = offset
            }
            row.put("media", measures).put("suspect", suspect)
            records.put(row)
            spare = previous
            previous = bitmap
            pending = false
        }, handler)
    }

    fun isPending() = pending
    fun stop() { active = false }
    fun finish() {
        check(!pending)
        File(output, "window-pixels.json").writeText(records.toString())
        previous?.recycle()
        spare?.recycle()
        previous = null
        spare = null
    }
    private fun save(bitmap: Bitmap, name: String) {
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        saved++
    }
}
