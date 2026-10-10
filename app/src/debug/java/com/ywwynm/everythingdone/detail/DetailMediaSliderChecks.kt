package com.ywwynm.everythingdone.detail

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.fragments.MediaCropAppearanceDialogFragment
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.DetailContentLayout
import com.ywwynm.everythingdone.helpers.DetailCropPreviewLoader
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
import com.ywwynm.everythingdone.views.RatioSlider
import com.ywwynm.everythingdone.views.ThingCardCropEditorController
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** 加载队列可控，使用真实触摸比较首次加载和缓存重开的比例编辑能力。 */
internal class DetailMediaSliderChecks(
    private val instrumentation: Instrumentation,
    private val output: File,
    private val createFixture: (String) -> Long,
    private val record: (String, JSONObject, Boolean) -> Unit,
    private val setCurrent: (DetailActivity?) -> Unit
) {
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    fun run() {
        val image = File(output, "slider-image.png")
        Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(45, 112, 136))
            image.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val video = File(output, "slider-video.mp4")
        instrumentation.targetContext.assets.open("detail-regression/video.mp4").use { input ->
            video.outputStream().use(input::copyTo)
        }
        val second = File(output, "slider-second.png").apply { image.copyTo(this, overwrite = true) }
        for ((type, key) in listOf("image" to "0$image", "video" to "1$video")) {
            for (multiple in listOf(false, true)) runCase(type, key,
                if (multiple) key + AttachmentHelper.SIGNAL + "0$second" else key, multiple)
        }
    }

    private fun runCase(type: String, key: String, attachment: String, multiple: Boolean) {
        val activity = instrumentation.startActivitySync(DetailActivity.getOpenIntentForUpdate(
            instrumentation.targetContext, "detail-slider-regression", createFixture(attachment), -1
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
        setCurrent(activity)
        val adapter = field<ImageAttachmentAdapter>(activity, "mImageAttachmentAdapter")
        val content = field<DetailContentLayout>(activity, "mDetailContent")
        val loader = main {
            DetailCropPreviewLoader().also {
                DetailActivity::class.java.getDeclaredField("mDetailCropPreviewLoader")
                    .apply { isAccessible = true }.set(activity, it)
            }
        }
        val executor = loader.javaClass.getDeclaredField("executor").apply { isAccessible = true }
            .get(loader) as ExecutorService
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.submit { started.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
        check(started.await(5, TimeUnit.SECONDS))
        try {
            await { content.holder(adapter, 0)?.itemView?.width?.let { it > 0 } == true }
            for (cached in listOf(false, true)) {
                main { content.holder(adapter, 0)!!.itemView.findViewById<View>(R.id.iv_edit_image_attachment_appearance).performClick() }
                await { dialog(activity)?.view?.width?.let { it > 0 } == true }
                val fragment = main { dialog(activity)!! }
                val root = main { fragment.requireView() }
                val slider = main { descendants(root).filterIsInstance<RatioSlider>().single() }
                val seek = main { descendants(slider).filterIsInstance<SeekBar>().single() }
                val crop = main { descendants(root).filterIsInstance<ThingCardCropEditorController>().single() }
                val sample = JSONObject().put("cached_preview", fragment.preparedDetailPreview != null)
                if (!cached) {
                    main {
                        check(!button(activity, root, R.string.confirm).isEnabled)
                        check(button(activity, root, R.string.cancel).isEnabled)
                        check(!slider.isEnabled && !seek.isEnabled)
                        check(!activity.isFinishing)
                    }
                    sample.put("loading_controls_disabled", true)
                    release.countDown()
                } else check(fragment.preparedDetailPreview != null) { "重新打开没有命中预览缓存" }
                await {
                    button(activity, root, R.string.confirm).isEnabled &&
                        fragment.dialog?.window?.decorView?.let { it.hasWindowFocus() &&
                            it.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS } == true
                }
                val scroll = main { descendants(root).filterIsInstance<ScrollView>().single() }
                val sampleName = "slider-$type-${if (multiple) "first-of-two" else "single"}-${if (cached) "cached" else "cold"}"
                val states = JSONArray()
                sample.put("scroll_states", states)
                states.put(checkScrollState(activity, root, scroll, "initial"))
                check(main { scroll.scrollY == 0 }) { "首次展示未从内容顶部开始" }
                if (multiple) main {
                    button(activity, root, R.string.detail_attachment_media_appearance_width_wide).performClick()
                }
                await { slider.isShown && seek.width > 0 && !root.isLayoutRequested }
                checkCropGesture(crop as View, scroll)
                sample.put("crop_gesture_preserved", true)
                checkScrolling(activity, root, scroll, sampleName, states, !cached && multiple)
                if (multiple) {
                    main { button(activity, root, R.string.detail_attachment_media_appearance_width_normal).performClick() }
                    await { !slider.isShown && !root.isLayoutRequested }
                    states.put(checkScrollState(activity, root, scroll, "hide-ratio"))
                    main { button(activity, root, R.string.detail_attachment_media_appearance_width_wide).performClick() }
                    await { slider.isShown && !root.isLayoutRequested }
                    states.put(checkScrollState(activity, root, scroll, "show-ratio"))
                    main { scroll.scrollTo(0, scroll.getChildAt(0).height) }
                    instrumentation.waitForIdleSync()
                }
                val before = main { crop.getTargetAspectRatio() }
                val parentEnabled = main { slider.isEnabled }
                val childEnabled = main { seek.isEnabled }
                val scrollBeforeDrag = main { scroll.scrollY }
                main {
                    val position = IntArray(2).also(seek::getLocationOnScreen)
                    val visible = Rect()
                    sample.put("seek_visible", seek.getGlobalVisibleRect(visible))
                        .put("seek_x", position[0]).put("seek_y", position[1])
                        .put("seek_width", seek.width).put("seek_height", seek.height)
                        .put("seek_visible_rect", visible.toShortString())
                        .put("screen_width", activity.resources.displayMetrics.widthPixels)
                        .put("screen_height", activity.resources.displayMetrics.heightPixels)
                }
                File(output, "$sampleName-input.json").writeText(sample.toString(2))
                try { drag(seek) } catch (error: Throwable) { capture(sampleName); throw error }
                val after = main { crop.getTargetAspectRatio() }
                val sliderRatio = main { slider.getRatio() }
                check(main { scroll.scrollY == scrollBeforeDrag }) { "比例拖动误触发了内容滚动" }
                if (abs(after - before) <= 0.2) capture(sampleName)
                record(sampleName,
                    sample.put("slider_enabled", parentEnabled).put("seekbar_enabled", childEnabled)
                        .put("ratio_before", before).put("ratio_after", after).put("slider_ratio", sliderRatio),
                    parentEnabled && childEnabled && abs(after - before) > 0.2 && abs(after - sliderRatio) < 0.00001)
                main { button(activity, root, if (cached) R.string.confirm else R.string.cancel).performClick() }
                await { dialog(activity) == null && activity.window.decorView.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
                val appearance = main { field<DetailAttachmentMediaAppearance>(activity, "mDetailAttachmentMediaAppearance") }
                if (cached) {
                    val saved = appearance.source(key)!!
                    check(saved.fullSpanEnabled)
                    check(abs(saved.presentationOrDefault("fullSpan").targetAspectRatio - after) < 0.00001)
                } else check(appearance.isDefault()) { "取消首次编辑仍保存了外观" }
            }
        } finally {
            release.countDown()
            main { activity.finish() }
            instrumentation.waitForIdleSync()
            setCurrent(null)
        }
    }

    private fun checkCropGesture(crop: View, scroll: ScrollView) {
        val before = main { scroll.scrollY }
        val visible = main {
            screenBounds(crop).apply { check(intersect(screenBounds(scroll))) }
        }
        swipe(visible.exactCenterX(), visible.top + visible.height() * 0.7f,
            visible.exactCenterX(), visible.top + visible.height() * 0.3f)
        check(main { scroll.scrollY == before }) { "裁剪手势被外层滚动区截断" }
    }

    private fun checkScrolling(activity: DetailActivity, root: View, scroll: ScrollView,
        name: String, states: JSONArray, screenshots: Boolean) {
        val fixedViews = main { listOf((scroll.parent as ViewGroup).getChildAt(0),
            button(activity, root, R.string.cancel), button(activity, root, R.string.confirm)) }
        val fixedBounds = main { fixedViews.map(::screenBounds) }
        fun state(label: String) {
            instrumentation.waitForIdleSync()
            states.put(checkScrollState(activity, root, scroll, label))
            check(main { fixedViews.map(::screenBounds) == fixedBounds }) { "标题或操作按钮随内容滚动" }
            if (screenshots) capture("$name-$label")
        }
        main { scroll.scrollTo(0, 0) }
        state("top")
        val range = main { scrollRange(scroll) }
        if (range > 1) {
            val bounds = main { screenBounds(scroll) }
            val gutterX = bounds.left + activity.resources.displayMetrics.density * 8
            swipe(gutterX, bounds.top + bounds.height() * 0.8f,
                gutterX, bounds.top + bounds.height() * 0.2f)
            check(main { scroll.scrollY > 0 }) { "真实纵向手势未滚动内容" }
            state("gesture")
            main { scroll.scrollTo(0, range / 2) }
            state("middle")
        }
        main { scroll.scrollTo(0, range) }
        state("bottom")
    }

    private fun checkScrollState(activity: DetailActivity, root: View, scroll: ScrollView, label: String): JSONObject {
        await {
            !root.isLayoutRequested &&
                (root.findViewById<View>(R.id.view_separator_1).visibility == View.VISIBLE) == (scroll.scrollY > 0) &&
                (root.findViewById<View>(R.id.view_separator_2).visibility == View.VISIBLE) == (scroll.scrollY < scrollRange(scroll))
        }
        return main {
            val range = scrollRange(scroll)
            check(scroll.scrollY in 0..range) { "内容高度改变后未修正滚动边界" }
            val fixed = listOf((scroll.parent as ViewGroup).getChildAt(0),
                button(activity, root, R.string.cancel), button(activity, root, R.string.confirm))
            fixed.forEach { view ->
                val rect = Rect()
                check(view.getLocalVisibleRect(rect) && rect.top == 0 && rect.bottom == view.height) {
                    "标题或操作按钮被窗口裁掉：${screenBounds(view)} local=$rect"
                }
            }
            JSONObject().put("position", label).put("y", scroll.scrollY).put("range", range)
                .put("viewport_height", scroll.height)
                .put("top_separator", root.findViewById<View>(R.id.view_separator_1).visibility == View.VISIBLE)
                .put("bottom_separator", root.findViewById<View>(R.id.view_separator_2).visibility == View.VISIBLE)
        }
    }

    private fun scrollRange(scroll: ScrollView) =
        (scroll.getChildAt(0).height - scroll.height + scroll.paddingTop + scroll.paddingBottom).coerceAtLeast(0)

    private fun screenBounds(view: View): Rect {
        val location = IntArray(2).also(view::getLocationOnScreen)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun capture(name: String) {
        val bitmap = automation.takeScreenshot() ?: return
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun drag(seek: SeekBar) {
        val position = IntArray(2)
        val geometry = main {
            seek.getLocationOnScreen(position)
            val width = seek.width - seek.paddingLeft - seek.paddingRight
            floatArrayOf(position[0] + seek.paddingLeft + width * seek.progress / seek.max.toFloat(),
                position[0] + seek.paddingLeft + width * 0.92f, position[1] + seek.height / 2f)
        }
        swipe(geometry[0], geometry[2], geometry[1], geometry[2])
    }

    private fun swipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                startX + (endX - startX) * fraction, startY + (endY - startY) * fraction, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(automation.injectInputEvent(event, true)) {
                "输入注入失败：action=$action start=($startX,$startY) end=($endX,$endY)"
            } }
            finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        repeat(12) { index -> SystemClock.sleep(16); send(MotionEvent.ACTION_MOVE, (index + 1) / 12f) }
        repeat(4) { SystemClock.sleep(16); send(MotionEvent.ACTION_MOVE, 1f) }
        send(MotionEvent.ACTION_UP, 1f)
        instrumentation.waitForIdleSync()
    }

    private fun dialog(activity: DetailActivity) = activity.supportFragmentManager
        .findFragmentByTag(MediaCropAppearanceDialogFragment.TAG) as? MediaCropAppearanceDialogFragment

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun button(activity: DetailActivity, root: View, id: Int) = descendants(root)
        .filterIsInstance<TextView>().first { it.isClickable && it.text.toString() == activity.getString(id) }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12000
        while (!main(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "等待比例编辑器超时" }
            SystemClock.sleep(40)
        }
        instrumentation.waitForIdleSync()
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: DetailActivity, name: String): T =
        DetailActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
}
