package com.ywwynm.everythingdone.covergeneration

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.PixelCopy
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter
import com.ywwynm.everythingdone.adapters.ThingsAdapter
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CoverGenerationState
import com.ywwynm.everythingdone.helpers.MotionPhotoCoverHelper
import com.ywwynm.everythingdone.helpers.MotionPhotoDetector
import com.ywwynm.everythingdone.helpers.VideoCoverPreviewManager
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingCardAppearance
import com.ywwynm.everythingdone.model.ThingListEntry
import com.ywwynm.everythingdone.views.CoverGenerationBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/** 仅在隔离包运行；不修改用户记事或媒体。 */
class CoverGenerationProbeActivity : Activity() {
    lateinit var content: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == "com.ywwynm.everythingdone.covervalidation")
        content = FrameLayout(this)
        content.setPadding(24, 24, 24, 24)
        setContentView(content)
    }
}

class CoverGenerationProbe : Instrumentation() {
    private lateinit var output: File
    private lateinit var activity: CoverGenerationProbeActivity
    private val checks = JSONArray()
    private var testCase: String? = null
    override fun onCreate(arguments: Bundle?) {
        testCase = arguments?.getString("case")
        super.onCreate(arguments); start()
    }
    private fun main(action: () -> Unit) {
        var error: Throwable? = null
        runOnMainSync { try { action() } catch (t: Throwable) { error = t } }
        error?.let { throw it }
    }
    private fun await(label: String, timeout: Long = 45000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(32)
        }
        error("等待超时：$label")
    }
    private fun record(name: String, ok: Boolean) {
        checks.put(JSONObject().put("name", name).put("ok", ok))
        if (::output.isInitialized) File(output, "checkpoint.json").writeText(checks.toString(2))
        check(ok) { name }
    }
    private fun screenshot(name: String, view: View, hardware: Boolean = false) {
        if (hardware) {
            val location = IntArray(2)
            main { view.getLocationInWindow(location) }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val latch = java.util.concurrent.CountDownLatch(1)
            var status = -1
            main {
                PixelCopy.request(activity.window, android.graphics.Rect(location[0], location[1],
                    location[0] + view.width, location[1] + view.height), bitmap,
                    { code -> status = code; latch.countDown() }, android.os.Handler(activity.mainLooper))
            }
            check(latch.await(5, java.util.concurrent.TimeUnit.SECONDS) && status == PixelCopy.SUCCESS)
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            return
        }
        main {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    override fun onStart() {
        super.onStart()
        val result = JSONObject()
        try {
            check(targetContext.packageName == "com.ywwynm.everythingdone.covervalidation")
            output = File(targetContext.getExternalFilesDir(null), "cover-generation-checks").apply { mkdirs() }
            FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_COVER_DYNAMIC, true)
            activity = startActivitySync(Intent(targetContext, CoverGenerationProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CoverGenerationProbeActivity
            SystemClock.sleep(300)
            await("测试活动获得焦点", 60000) { activity.hasWindowFocus() }
            val source = File(targetContext.cacheDir, "cover-probe-${SystemClock.uptimeMillis()}.mp4")
            targetContext.assets.open("selection-appearance/media.mp4").use { input ->
                source.outputStream().use(input::copyTo)
            }
            if (testCase in listOf("list", "baseline")) {
                sourceChangeAndScroll(source, testCase == "baseline")
                result.put("ok", true)
                return
            }
            actualGeneration(source, false)
            val motion = File(targetContext.cacheDir, "cover-motion-${SystemClock.uptimeMillis()}.jpg")
            val poster = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
            motion.outputStream().use { stream ->
                poster.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                source.inputStream().use { it.copyTo(stream) }
            }
            poster.recycle()
            record("动态照片夹具被真实检测器识别", MotionPhotoDetector.detect(motion.path).isMotionPhoto)
            actualGeneration(motion, true)
            sharedMotionRetry(motion)
            layoutAndInput(source)
            retryFailure(source)
            sourceChangeAndScroll(source)
            visibilityGuards(source)
            result.put("ok", true)
        } catch (t: Throwable) {
            result.put("ok", false).put("error", t.stackTraceToString())
        } finally {
            result.put("checks", checks)
            if (::output.isInitialized) File(output, "result.json").writeText(result.toString(2))
            if (::activity.isInitialized) main { activity.finish() }
            finish(if (result.optBoolean("ok")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("result", result.toString()) })
        }
    }

    private fun thing(source: File, background: Boolean = false, placement: Int = Thing.THING_CARD_IMAGE_PLACEMENT_TOP) =
        Thing(900001L, Thing.NOTE, 0xffeedfa7.toInt(), 1L).apply {
            title = "动态封面生成测试"
            content = "状态占用原数量行，卡片高度保持不变。"
            attachment = AttachmentHelper.SIGNAL +
                (if (source.extension == "mp4") AttachmentHelper.VIDEO else AttachmentHelper.IMAGE) + source.path
            thingCardAppearance = thingCardAppearance.copy(mediaBackgroundEnabled = background,
                imagePlacement = placement, spanMode = Thing.THING_CARD_SPAN_FULL)
        }

    private fun adapter(model: Thing) = object : BaseThingsAdapter(targetContext.applicationContext) {
        override fun getCurrentMode() = ModeManager.NORMAL
        override fun getThings(): List<Thing> = listOf(model)
        override fun isFullSpanThingCard(thing: Thing) = thing.thingCardSpanMode == Thing.THING_CARD_SPAN_FULL
    }.apply { setFullSpanCardWidth(targetContext.resources.displayMetrics.widthPixels - 48) }

    private fun actualGeneration(source: File, motion: Boolean) {
        val model = thing(source, background = motion)
        val adapter = adapter(model)
        lateinit var holder: BaseThingsAdapter.BaseThingViewHolder
        val states = mutableListOf<CoverGenerationState>()
        var observer: Closeable? = null
        main {
            activity.content.removeAllViews()
            holder = adapter.onCreateViewHolder(activity.content, 0)
            adapter.onBindViewHolder(holder, 0)
            activity.content.addView(holder.itemView)
            observer = if (motion) MotionPhotoCoverHelper.observeGeneration(targetContext, source.path,
                false, { states.add(it) }) {} else VideoCoverPreviewManager.observePreview(targetContext,
                source.path, null, false, { states.add(it) }) {}
        }
        await("真实帧生成进度") { holder.coverGeneration?.state?.let {
            it.phase == CoverGenerationState.Phase.GENERATING && it.percent in 30..98
        } == true }
        screenshot(if (motion) "motion-generating" else "video-generating", holder.itemView)
        main { activity.content.removeView(holder.itemView) }
        main { activity.content.addView(holder.itemView) }
        await("生成完成并换装 GIF") {
            (if (motion) holder.ivMediaBackground else holder.ivImageAttachment)?.drawable is GifDrawable
        }
        main {
            val text = if (motion) holder.tvInlineMediaCount else holder.tvImageCount
            record("${if (motion) "动态照片" else "视频"}成功恢复数量", text?.text.toString() ==
                AttachmentHelper.getImageAttachmentCountStr(model.attachment, targetContext))
            record("真实任务包含等待与确定进度-$motion", states.any { it.phase == CoverGenerationState.Phase.WAITING } &&
                states.any { it.phase == CoverGenerationState.Phase.GENERATING && it.percent in 1..99 })
            record("离屏重新挂载后仍播放-$motion",
                ((if (motion) holder.ivMediaBackground else holder.ivImageAttachment)?.drawable as GifDrawable).isRunning)
            observer?.close()
            activity.content.removeView(holder.itemView)
            adapter.onViewRecycled(holder)
            record("回收释放订阅-$motion", holder.coverGeneration == null)
        }
    }

    /** 人工状态仅用于可重复的布局／点击边界检查；真实生成另由上面的 WorkManager 路径验证。 */
    private fun layoutAndInput(source: File) {
        for (layout in 0..4) {
            val placement = listOf(Thing.THING_CARD_IMAGE_PLACEMENT_TOP, Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM,
                Thing.THING_CARD_IMAGE_PLACEMENT_LEFT, Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT,
                Thing.THING_CARD_IMAGE_PLACEMENT_TOP)[layout]
            val model = thing(source, layout == 4, placement)
            val adapter = adapter(model)
            lateinit var holder: BaseThingsAdapter.BaseThingViewHolder
            lateinit var text: TextView
            lateinit var binding: CoverGenerationBinding
            var retries = 0
            var clicks = 0
            var longClicks = 0
            var normal = true
            main {
                activity.content.removeAllViews()
                holder = adapter.onCreateViewHolder(activity.content, 0)
                adapter.onBindViewHolder(holder, 0)
                activity.content.addView(holder.itemView)
                text = (if (layout == 4) holder.tvInlineMediaCount else holder.tvImageCount)!!
                holder.cv!!.setOnClickListener { clicks++ }
                holder.cv!!.setOnLongClickListener { longClicks++; true }
            }
            await("卡片布局") { holder.itemView.height > 0 && !holder.itemView.isLayoutRequested }
            var height = 0
            var rowHeight = 0
            main {
                height = holder.itemView.height; rowHeight = text.height
                if (layout in 2..3) {
                    record("布局$layout-确为左右侧图", holder.llContent!!.orientation == LinearLayout.HORIZONTAL &&
                        (holder.flImageAttachment!!.left < holder.llTextContent!!.left) == (layout == 2))
                }
                binding = CoverGenerationBinding(holder.cv!!, text,
                    if (layout == 4) holder.ivInlineMediaCount else null, text.text,
                    { retry, _, _ -> if (retry) retries++; Closeable {} }, { normal }, {})
            }
            for (state in listOf(CoverGenerationState.WAITING, CoverGenerationState.PREPARING,
                CoverGenerationState.framesProcessed(27, 75), CoverGenerationState.FAILED, CoverGenerationState.READY)) {
                main { binding.showState(state) }
                waitForIdleSync()
                if (state == CoverGenerationState.WAITING && layout in listOf(0, 4)) {
                    SystemClock.sleep(250)
                    screenshot("spinner-$layout", holder.itemView, hardware = true)
                    main {
                        val icon = if (layout == 4) holder.ivInlineMediaCount!!.drawable else text.compoundDrawables[0]
                        record("布局$layout-转圈正在运行", (icon as? android.graphics.drawable.Animatable)?.isRunning == true)
                    }
                }
                main {
                    record("布局$layout-${state.phase}不增高", holder.itemView.height == height && text.height == rowHeight)
                }
            }
            main { binding.showState(CoverGenerationState.framesProcessed(36, 75)) }
            screenshot("layout-$layout", holder.itemView)
            main { binding.showState(CoverGenerationState.FAILED) }
            tap(holder, text)
            main { record("布局$layout-失败行点击重试", retries == 1 && clicks == 0) }
            normal = false
            tap(holder, text)
            main { record("布局$layout-非普通模式保留卡片点击", retries == 1 && clicks == 1) }
            normal = true
            tap(holder, text, longPress = true)
            main {
                record("布局$layout-长按不重试", retries == 1 && longClicks == 1)
                binding.close()
                activity.content.removeView(holder.itemView)
                adapter.onViewRecycled(holder)
            }
        }
    }

    private fun tap(holder: BaseThingsAdapter.BaseThingViewHolder, text: TextView, longPress: Boolean = false) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int) = main {
            val rect = android.graphics.Rect()
            text.getDrawingRect(rect)
            holder.cv!!.offsetDescendantRectToMyCoords(text, rect)
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                rect.exactCenterX(), rect.exactCenterY(), 0).also {
                holder.cv!!.dispatchTouchEvent(it); it.recycle()
            }
        }
        event(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(if (longPress) android.view.ViewConfiguration.getLongPressTimeout() + 100L else 64)
        event(MotionEvent.ACTION_UP)
        waitForIdleSync()
    }

    private fun retryFailure(source: File) {
        val broken = File(targetContext.cacheDir, "cover-broken-${SystemClock.uptimeMillis()}.mp4")
        source.copyTo(broken)
        // 预置真正的终态失败 Work，避免等待四轮解码退避；仍由正式请求读取／重试它。
        val method = VideoCoverPreviewManager::class.java.getDeclaredMethod("previewFileFor",
            android.content.Context::class.java, String::class.java, java.lang.Long::class.java).apply { isAccessible = true }
        val out = method.invoke(VideoCoverPreviewManager, targetContext, broken.path, null) as File
        val data = androidx.work.Data.Builder()
            .putString(com.ywwynm.everythingdone.services.VideoCoverPreviewWorker.KEY_VIDEO_PATH, broken.path + ".missing")
            .putString(com.ywwynm.everythingdone.services.VideoCoverPreviewWorker.KEY_OUTPUT_NAME, out.name).build()
        val work = androidx.work.OneTimeWorkRequest.Builder(com.ywwynm.everythingdone.services.VideoCoverPreviewWorker::class.java)
            .setInputData(data).build()
        val manager = androidx.work.WorkManager.getInstance(targetContext)
        manager.enqueueUniqueWork(out.name, androidx.work.ExistingWorkPolicy.KEEP, work).result.get()
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!manager.getWorkInfoById(work.id).get().state.isFinished && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(32)
        val model = thing(broken)
        val adapter = adapter(model)
        lateinit var holder: BaseThingsAdapter.BaseThingViewHolder
        main {
            activity.content.removeAllViews()
            holder = adapter.onCreateViewHolder(activity.content, 0)
            adapter.onBindViewHolder(holder, 0)
            holder.cv!!.setOnClickListener { error("失败提示不应打开详情") }
            activity.content.addView(holder.itemView)
        }
        await("终态失败保留") { holder.coverGeneration?.state?.phase == CoverGenerationState.Phase.FAILED }
        record("重新显示不自动重试", manager.getWorkInfosForUniqueWork(out.name).get().single().id == work.id)
        manager.pruneWork().result.get()
        main { adapter.onBindViewHolder(holder, 0) }
        await("任务历史清理后失败仍保留") { holder.coverGeneration?.state?.phase == CoverGenerationState.Phase.FAILED }
        record("历史清理不抹去失败", manager.getWorkInfosForUniqueWork(out.name).get().isEmpty())
        await("失败行布局") { holder.tvImageCount!!.width > 0 && !holder.itemView.isLayoutRequested }
        tap(holder, holder.tvImageCount!!)
        await("点击失败提示后换装 GIF") { holder.ivImageAttachment!!.drawable is GifDrawable }
        main {
            record("点击重试成功恢复数量", holder.tvImageCount!!.text.toString() ==
                AttachmentHelper.getImageAttachmentCountStr(model.attachment, targetContext))
            activity.content.removeView(holder.itemView)
            adapter.onViewRecycled(holder)
        }
    }

    private fun sourceChangeAndScroll(source: File, baseline: Boolean = false) {
        if (baseline) FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_COVER_DYNAMIC, false)
        val video = File(targetContext.cacheDir, "cover-reuse-${SystemClock.uptimeMillis()}.mp4")
        source.copyTo(video)
        val still = File(targetContext.cacheDir, "cover-still.jpg")
        val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        still.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        val first = thing(video)
        val rows = (0 until 24).map { index ->
            (if (index == 0) first else thing(if (index < 4) video else still)).apply {
                id = 910000L + index
                thingCardSpanMode = Thing.THING_CARD_SPAN_NORMAL
            }
        }
        lateinit var adapter: ThingsAdapter
        lateinit var recycler: RecyclerView
        main {
            adapter = object : ThingsAdapter(targetContext.applicationContext as App, null) {
                override fun getCurrentMode() = ModeManager.NORMAL
                override fun getThings(): List<Thing> = rows
                override fun getEntries(): List<ThingListEntry> = rows.map { ThingListEntry.ThingEntry(it) }
            }
            activity.content.removeAllViews()
            recycler = RecyclerView(activity).apply {
                layoutManager = StaggeredGridLayoutManager(2, RecyclerView.VERTICAL)
                this.adapter = adapter
            }
            activity.content.addView(recycler, FrameLayout.LayoutParams(-1, -1))
        }
        record("列表加入窗口", true)
        await("列表初次布局完成") { recycler.findViewHolderForAdapterPosition(0) != null && !recycler.isLayoutRequested }
        record("列表初次布局完成", true)
        if (!baseline) await("列表真实生成状态") {
            (recycler.findViewHolderForAdapterPosition(0) as? BaseThingsAdapter.BaseThingViewHolder)
                ?.coverGeneration?.state?.phase == CoverGenerationState.Phase.GENERATING
        }
        record("列表开始切换来源", true)
        main {
            first.attachment = AttachmentHelper.SIGNAL + AttachmentHelper.IMAGE + still.path
            adapter.notifyItemChanged(0)
        }
        // 与首页实际编辑返回流程一致，等此次内容更新完成布局，再触发滚动。
        await("切换来源完成布局") {
            val current = recycler.findViewHolderForAdapterPosition(0) as? BaseThingsAdapter.BaseThingViewHolder
            return@await !recycler.isLayoutRequested && recycler.itemAnimator?.isRunning != true &&
                current?.tvImageCount?.text.toString() == AttachmentHelper.getImageAttachmentCountStr(first.attachment, targetContext)
        }
        main { recycler.scrollToPosition(23) }
        await("列表滚动到底部") { recycler.findViewHolderForAdapterPosition(23) != null }
        main { recycler.scrollToPosition(0) }
        await("列表滚回顶部") { recycler.findViewHolderForAdapterPosition(0) != null }
        if (baseline) {
            main { activity.content.removeView(recycler); recycler.adapter = null }
            FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_COVER_DYNAMIC, true)
            return
        }
        await("同源任务完成") { VideoCoverPreviewManager.getReadyPreview(targetContext, video.path, null) != null }
        await("同源卡片完成换装") {
            (recycler.findViewHolderForAdapterPosition(1) as? BaseThingsAdapter.BaseThingViewHolder)
                ?.ivImageAttachment?.drawable is GifDrawable
        }
        main {
            val current = recycler.findViewHolderForAdapterPosition(0) as BaseThingsAdapter.BaseThingViewHolder
            record("切换到普通图片后不被旧任务覆盖", current.tvImageCount!!.text.toString() ==
                AttachmentHelper.getImageAttachmentCountStr(first.attachment, targetContext) &&
                current.ivImageAttachment!!.drawable !is GifDrawable)
            record("滚动复用后无错误失败提示", current.coverGeneration?.state?.phase != CoverGenerationState.Phase.FAILED)
            val other = recycler.findViewHolderForAdapterPosition(1) as BaseThingsAdapter.BaseThingViewHolder
            record("同源另一卡片完成且持续播放", (other.ivImageAttachment!!.drawable as GifDrawable).isRunning)
            activity.content.removeView(recycler)
            recycler.adapter = null
        }
    }

    private fun sharedMotionRetry(source: File) {
        val motion = File(targetContext.cacheDir, "motion-preparation-retry-${SystemClock.uptimeMillis()}.jpg")
        source.copyTo(motion)
        val info = MotionPhotoDetector.detect(motion.path)
        val method = MotionPhotoCoverHelper::class.java.getDeclaredMethod("extractedFileFor",
            android.content.Context::class.java, String::class.java, MotionPhotoDetector.MotionPhotoInfo::class.java)
            .apply { isAccessible = true }
        val extracted = method.invoke(MotionPhotoCoverHelper, targetContext, motion.path, info) as File
        targetContext.getSharedPreferences("motion_cover_preparation_failures", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(extracted.name, true).commit()
        val states = arrayOf(CoverGenerationState.IDLE, CoverGenerationState.IDLE)
        val subscriptions = arrayOfNulls<Closeable>(2)
        main {
            for (i in 0..1) subscriptions[i] = MotionPhotoCoverHelper.observeGeneration(targetContext,
                motion.path, false, { states[i] = it }) {}
        }
        await("同源准备失败保留") { states.all { it.phase == CoverGenerationState.Phase.FAILED } }
        record("同源动态照片保留准备失败", true)
        main {
            subscriptions[0]?.close()
            subscriptions[0] = MotionPhotoCoverHelper.observeGeneration(targetContext,
                motion.path, true, { states[0] = it }) {}
        }
        await("同源准备失败重试同步") { states.all { it.phase == CoverGenerationState.Phase.READY } }
        record("一处重试后同源卡片全部恢复", true)
        main { subscriptions.forEach { it?.close() } }
    }

    private fun visibilityGuards(source: File) {
        val fresh = File(targetContext.cacheDir, "cover-hidden-${SystemClock.uptimeMillis()}.mp4")
        source.copyTo(fresh)
        for (kind in 0..2) {
            val model = thing(fresh).apply {
                if (kind == 1) title = Thing.PRIVATE_THING_PREFIX + title
                if (kind == 2) thingCardAppearance = thingCardAppearance.copy(
                    mediaSourceKey = ThingCardAppearance.MEDIA_SOURCE_NONE)
            }
            val adapter = adapter(model)
            if (kind == 0) FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_COVER_DYNAMIC, false)
            main {
                val holder = adapter.onCreateViewHolder(activity.content, 0)
                adapter.onBindViewHolder(holder, 0)
                record("关闭播放／私密／隐藏封面不订阅-$kind", holder.coverGeneration == null)
                adapter.onViewRecycled(holder)
            }
            FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_COVER_DYNAMIC, true)
        }
    }
}
