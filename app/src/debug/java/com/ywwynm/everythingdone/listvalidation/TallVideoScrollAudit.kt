package com.ywwynm.everythingdone.listvalidation

import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.ThingsActivity
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingCardAppearance
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** 检查真实 GIF、高卡片、按住滚动及离屏复用；失败时不重绑、不主动 invalidate。 */
internal object TallVideoScrollAudit {
    fun decorate(context: Context, thing: Thing, background: Boolean): File {
        check(context.packageName == "com.ywwynm.everythingdone.batchvalidation")
        val video = File(context.filesDir, "tall-video.mp4")
        context.assets.open("selection-appearance/media.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
        val key = "${AttachmentHelper.VIDEO}${video.absolutePath}"
        thing.title = ""; thing.content = ""
        thing.attachment = AttachmentHelper.SIGNAL + key
        val metrics = context.resources.displayMetrics
        val ratio = (metrics.widthPixels - 32 * metrics.density) / (metrics.heightPixels * .83)
        val source = ThingCardAppearance.SourceAppearance(fileSize = video.length(), lastModified = video.lastModified())
            .withPresentation(ThingCardAppearance.PRESENTATION_THUMBNAIL,
                ThingCardAppearance.MediaPresentationAppearance(targetAspectRatio = ratio))
            .withPresentation(ThingCardAppearance.PRESENTATION_MEDIA_BACKGROUND,
                ThingCardAppearance.MediaPresentationAppearance(targetAspectRatio = ratio))
        thing.thingCardAppearance = thing.thingCardAppearance.copy(spanMode = Thing.THING_CARD_SPAN_FULL,
            mediaBackgroundEnabled = background, sources = mapOf(key to source))
        return video
    }

    fun run(activity: ThingsActivity, list: RecyclerView, mode: ModeManager, id: Long,
            args: Bundle, output: File, automation: UiAutomation, main: (() -> Unit) -> Unit): JSONObject {
        val samples = JSONArray()
        var gifSamples = 0
        var videoSamples = 0
        val visited = mutableSetOf<Int>()
        val modes = mutableSetOf<Int>()
        val lateBind = args.getString("late_bind") == "true"
        val density = activity.resources.displayMetrics.density
        fun save(bitmap: Bitmap, name: String) {
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun inspect(index: Int, attempt: Int = 0) {
            val rows = JSONArray()
            main {
                for (i in 0 until list.childCount) {
                    val card = list.getChildAt(i)
                    if (list.getChildAdapterPosition(card) <= 0) continue
                    val bound = card.getTag(R.id.tag_thing_card_bound_stable_id) as? Long ?: continue
                    val media = card.findViewById<ImageView>(R.id.iv_thing_image)?.takeIf { it.isShown }
                        ?: card.findViewById<ImageView>(R.id.iv_thing_media_background)?.takeIf { it.isShown }
                    val target = if (bound == id) media ?: card else card
                    val rect = Rect(); if (!target.getGlobalVisibleRect(rect)) continue
                    rect.top = max(rect.top, (120 * density).toInt())
                    rect.bottom = min(rect.bottom, activity.resources.displayMetrics.heightPixels - (40 * density).toInt())
                    if (rect.height() < 50 * density || rect.width() < 40 * density) continue
                    val drawable = media?.drawable
                    if (bound == id) {
                        videoSamples++
                        visited.add(System.identityHashCode(card))
                        if (drawable is GifDrawable) gifSamples++
                    }
                    rows.put(JSONObject().put("id", bound).put("isVideo", bound == id).put("view", System.identityHashCode(card))
                        .put("alpha", card.alpha).put("visibility", card.visibility).put("height", card.height)
                        .put("media", drawable?.javaClass?.simpleName).put("gifRunning", (drawable as? GifDrawable)?.isRunning)
                        .put("frame", (drawable as? GifDrawable)?.frameIndex)
                        .put("rect", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))))
                }
            }
            val bitmap = checkNotNull(automation.takeScreenshot())
            var failed = false
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val rect = row.getJSONArray("rect")
                val left = rect.getInt(0); val top = rect.getInt(1)
                val width = rect.getInt(2) - left; val height = rect.getInt(3) - top
                var colored = 0; var count = 0; var videoPatternPixels = 0
                val colorBins = mutableSetOf<Int>()
                // 排除阴影、圆角和底部浮动按钮；测试视频是彩色测试图，普通夹具统一蓝色。
                for (y in 2..8) for (x in 2..8) {
                    val pixel = bitmap.getPixel((left + width * x / 10).coerceIn(0, bitmap.width - 1),
                        (top + height * y / 10).coerceIn(0, bitmap.height - 1))
                    val rgb = intArrayOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    colorBins.add((rgb[0] / 32 shl 8) or (rgb[1] / 32 shl 4) or (rgb[2] / 32))
                    if (rgb.maxOrNull()!! - rgb.minOrNull()!! > 40) colored++
                    // 彩色视频含黄／红／绿／深蓝色块；青蓝卡片底色与白色文字不符合这些条件。
                    // 高卡片被裁切后可能只剩黄蓝两色，不能以颜色种类数判断封面是否出现。
                    if (rgb[0] > rgb[2] + 40 || rgb[1] > rgb[2] + 40 || rgb[2] > rgb[1] + 60) videoPatternPixels++
                    count++
                }
                row.put("colored", colored).put("count", count).put("colorBins", colorBins.size)
                    .put("videoPatternPixels", videoPatternPixels)
                if (colored < 3 || row.getBoolean("isVideo") && videoPatternPixels < 3) failed = true
            }
            samples.put(JSONObject().put("index", index).put("attempt", attempt).put("rows", rows))
            if (index == -1 || failed || index % 12 == 0) save(bitmap, if (failed) "video-suspect-$index-$attempt" else "video-sample-$index-$attempt")
            bitmap.recycle()
            File(output, "video-samples.json").writeText(samples.toString(2))
            if (failed && attempt < 2) {
                // 只等待正常绘制，不修改 View、重绑数据或重新加载，区分首帧尚未呈现和持续缺失。
                SystemClock.sleep(350)
                inspect(index, attempt + 1)
                return
            }
            check(!failed) { "第 $index 轮卡片或媒体像素持续缺失" }
        }
        main {
            val position = ThingManager.getInstance(activity)!!.getListPositionForThingId(id)
            (list.layoutManager as StaggeredGridLayoutManager).scrollToPositionWithOffset(
                if (lateBind) (position - 4).coerceAtLeast(1) else position, (120 * density).toInt())
        }
        val readyUntil = SystemClock.uptimeMillis() + 20000
        var ready = false
        while (!lateBind && !ready && SystemClock.uptimeMillis() < readyUntil) {
            main {
                ready = (0 until list.childCount).map { list.getChildAt(it) }.any { card ->
                    card.getTag(R.id.tag_thing_card_bound_stable_id) == id &&
                        listOf(R.id.iv_thing_image, R.id.iv_thing_media_background).any { key ->
                            card.findViewById<ImageView>(key)?.let { it.isShown && it.drawable is GifDrawable } == true
                        }
                }
            }
            SystemClock.sleep(32)
        }
        check(lateBind || ready) { "测试前没有就绪的 GIF，不能把静态封面当成动图验证" }
        if (lateBind) {
            // 不把首次定位尚未提交到屏幕的画面与新布局坐标相比较；首次审计在实际滚动结束后。
            SystemClock.sleep(450)
        } else inspect(-1)
        val rounds = args.getString("rounds")?.toInt() ?: 36
        for (index in 0 until rounds) {
            val direction = if (index % 4 < 2) -1 else 1
            var x = 0f; var y = 0f; var distance = 0f
            main {
                val rect = Rect(); check(list.getGlobalVisibleRect(rect))
                val desired = if (direction < 0) rect.height() * .72f else rect.height() * .3f
                val card = (0 until list.childCount).map { list.getChildAt(it) }.filter {
                    it.isShown && it.bottom > 180 * density && it.top < rect.height() - 100 * density &&
                        it.getTag(R.id.tag_thing_card_bound_stable_id) != null
                }.minByOrNull { kotlin.math.abs((it.top + it.bottom) / 2f - desired) } ?: error("没有可拖动卡片")
                val at = IntArray(2); card.getLocationOnScreen(at)
                x = at[0] + card.width * .45f
                val top = max(at[1] + 16 * density, 180 * density)
                val bottom = min(at[1] + card.height - 16 * density, rect.bottom - 100 * density)
                y = (rect.top + desired).coerceIn(top, max(top, bottom))
                distance = if (direction < 0) -min(rect.height() * .42f, y - 150 * density)
                    else min(rect.height() * .42f, rect.bottom - 80 * density - y)
            }
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int, yy: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, yy, 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                val accepted = automation.injectInputEvent(event, true)
                event.recycle()
                check(accepted) { "输入被系统拒绝：轮次=$index action=$action x=$x y=$yy" }
            }
            touch(MotionEvent.ACTION_DOWN, y)
            SystemClock.sleep(longArrayOf(0, 90, 210, 350, 430, 560)[index % 6])
            for (step in 1..24) {
                touch(MotionEvent.ACTION_MOVE, y + distance * step / 24)
                main { modes.add(mode.getCurrentMode()) }
                SystemClock.sleep(12)
            }
            touch(MotionEvent.ACTION_UP, y + distance)
            SystemClock.sleep(450)
            main {
                // 长按拖到另一张卡上可能正常触发新建文件夹；取消它，避免把弹窗遮挡计为卡片空白。
                activity.supportFragmentManager.fragments.filterIsInstance<androidx.fragment.app.DialogFragment>()
                    .filter { it.dialog?.isShowing == true }.forEach { it.dialog?.cancel() }
                if (mode.getCurrentMode() != ModeManager.NORMAL) mode.backNormalMode(-1)
            }
            val deadline = SystemClock.uptimeMillis() + 5000
            var settled = false
            while (!settled && SystemClock.uptimeMillis() < deadline) {
                main { settled = activity.hasWindowFocus() && list.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                    !list.isComputingLayout && list.itemAnimator?.isRunning != true &&
                    com.ywwynm.everythingdone.views.particledismiss.ParticleDismissOverlay.activeIn(activity).isEmpty() &&
                    androidx.core.view.ViewCompat.getRootWindowInsets(list)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true &&
                    activity.supportFragmentManager.fragments.none { it is androidx.fragment.app.DialogFragment && it.dialog?.isShowing == true } }
                SystemClock.sleep(32)
            }
            check(settled) { "拖动后的弹窗或列表未稳定" }
            SystemClock.sleep(120)
            inspect(index)
        }
        check(videoSamples >= 6 && gifSamples >= 6) { "高 GIF 卡片的实际可见样本不足" }
        return JSONObject().put("rounds", rounds).put("videoSamples", videoSamples).put("gifSamples", gifSamples)
            .put("videoHolderCount", visited.size).put("modes", JSONArray(modes.toList())).put("lateBind", lateBind)
    }
}
