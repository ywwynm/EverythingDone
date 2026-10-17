package com.ywwynm.everythingdone.covergeneration

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.work.*
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.ywwynm.everythingdone.*
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.ThingsActivity
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter
import com.ywwynm.everythingdone.adapters.ThingsAdapter
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.helpers.*
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.managers.ThingListOverlayDragController
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.*
import com.ywwynm.everythingdone.utils.ThingAnimationPreferences
import com.ywwynm.everythingdone.views.particledismiss.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 仅隔离包：正式编码器在真实帧边界等待测试触摸；不改产品生成调度。 */
class CoverGenerationGateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    class Gate {
        val begin = CountDownLatch(1)
        val halfway = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val nearEnd = CountDownLatch(1)
        val publish = CountDownLatch(1)
        @Volatile var holdAtEnd = false
        @Volatile var percent = 0
    }
    override fun doWork(): Result {
        check(applicationContext.packageName == "com.ywwynm.everythingdone.covervalidation")
        val path = checkNotNull(inputData.getString("path"))
        val gate = gates.getValue(path)
        check(gate.begin.await(90, TimeUnit.SECONDS))
        val frame = inputData.getLong("frame", 0)
        var held = false
        val file = VideoCoverPreviewManager.generateBlocking(applicationContext, path, frame) { state ->
            gate.percent = state.percent
            if (state.phase == CoverGenerationState.Phase.GENERATING) {
                setProgressAsync(Data.Builder().putBoolean(VideoCoverPreviewManager.KEY_GENERATING, true)
                    .putString(VideoCoverPreviewManager.KEY_PHASE, state.phase.name)
                    .putInt(VideoCoverPreviewManager.KEY_ATTEMPT, runAttemptCount + 1)
                    .putInt(VideoCoverPreviewManager.KEY_PERCENT, state.percent).build()).get()
                if (!held && state.percent >= 50) {
                    held = true; gate.halfway.countDown()
                    check(gate.finish.await(90, TimeUnit.SECONDS))
                }
            }
        } ?: return Result.failure()
        if (gate.holdAtEnd) {
            // 原生编码器 close 仍需时间；完整编码后暂存产物，再受控发布到原缓存路径。
            val staged = File(file.path + ".visual-held")
            check(file.renameTo(staged))
            gate.nearEnd.countDown()
            check(gate.publish.await(90, TimeUnit.SECONDS))
            check(staged.renameTo(file))
        }
        VideoCoverPreviewManager.notifyGenerated(applicationContext, file.name, file)
        return Result.success()
    }
    companion object { val gates = ConcurrentHashMap<String, Gate>() }
}

/** 真实首页、真实手指事件和可控交付时刻。只写本隔离包的虚构数据。 */
class CoverGenerationInteractionProbe : Instrumentation() {
    private lateinit var activity: ThingsActivity
    private lateinit var list: RecyclerView
    private lateinit var manager: ThingManager
    private lateinit var mode: ModeManager
    private lateinit var automation: UiAutomation
    private lateinit var output: File
    private val checks = JSONArray()
    private val samples = JSONArray()
    private var scenario = "all"
    private var runId = ""
    private var videoStart = 0L
    private var videoLabel = ""
    private val events = JSONArray()
    private val visual get() = scenario.startsWith("visual")
    private val ids = (1..36).map { 9_200_000L + it }
    private val adapter get() = list.adapter as ThingsAdapter
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        scenario = arguments?.getString("case") ?: "all"
        runId = arguments?.getString("run_id") ?: System.currentTimeMillis().toString()
        require(runId.matches(Regex("[A-Za-z0-9_-]+")))
        start()
    }
    private fun command(value: String): String {
        val pipes = automation.executeShellCommandRwe("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write((value + "\n").toByteArray()) }
        val result = ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        ParcelFileDescriptor.AutoCloseInputStream(pipes[2]).use { it.readBytes() }
        return result
    }
    private fun event(label: String, dwell: Long = 0) {
        if (!visual) return
        events.put(JSONObject().put("video", videoLabel).put("ms", SystemClock.uptimeMillis() - videoStart).put("label", label))
        File(output, "events.json").writeText(events.toString(2))
        sample(label)
        if (dwell > 0) SystemClock.sleep(dwell)
    }
    private fun capture(label: String, body: () -> Unit) {
        videoLabel = label
        val video = File(output, "$label.mp4")
        videoStart = SystemClock.uptimeMillis()
        val pid = command("screenrecord --bit-rate 12000000 --time-limit 180 ${video.path} > ${File(output, "$label-recorder.txt").path} 2>&1 < /dev/null & echo \$!").toInt()
        try {
            SystemClock.sleep(600)
            event("recording-start", 400)
            body()
            event("recording-end", 1500)
        } finally {
            command("kill -2 $pid")
            SystemClock.sleep(700)
        }
    }
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { action() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }
    private fun field(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val f = runCatching { type!!.getDeclaredField(name) }.getOrNull()
            if (f != null) return f.apply { isAccessible = true }.get(target)
            type = type.superclass
        }
        error("字段不存在：$name")
    }
    private fun await(label: String, timeout: Long = 30000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false; main { ready = condition() }; if (ready) return
            val root = automation.rootInActiveWindow
            if (root?.findAccessibilityNodeInfosByText("应用兼容性")?.isNotEmpty() == true)
                root.findAccessibilityNodeInfosByViewId("android:id/button2").firstOrNull()
                    ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            if (scenario == "visual-navigation" && root?.packageName?.contains("permissioncontroller") == true) {
                // 只为隔离包的真实详情媒体权限提示操作；测试结束卸载隔离包即移除授权。
                root.findAccessibilityNodeInfosByText("全部允许").firstOrNull { it.isVisibleToUser }
                    ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            }
            SystemClock.sleep(24)
        }
        error("等待超时：$label")
    }
    private fun record(label: String, ok: Boolean) {
        checks.put(JSONObject().put("name", label).put("ok", ok))
        File(output, "checkpoint.json").writeText(checks.toString(2))
        check(ok) { label }
    }
    private fun pos(id: Long) = manager.getListPositionForThingId(id)
    private fun holder(id: Long) = list.findViewHolderForAdapterPosition(pos(id)) as? BaseThingsAdapter.BaseThingViewHolder
    private fun model(id: Long) = checkNotNull(manager.getThingAtListPosition(pos(id)))
    private fun idle() = await("首页布局稳定") { !list.isComputingLayout && !list.isLayoutRequested &&
        list.itemAnimator?.isRunning != true && list.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
        ParticleDismissOverlay.activeIn(activity).isEmpty() }
    private fun top() {
        main { list.stopScroll(); (list.layoutManager as StaggeredGridLayoutManager).scrollToPositionWithOffset(0, 0) }
        idle(); await("首卡显示") { holder(ids.first())?.itemView?.isShown == true }
    }
    private fun touch(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
    }
    private fun shot(label: String) {
        automation.takeScreenshot()?.let { image ->
            File(output, "$label.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
    private fun sample(label: String) {
        main {
            val cards = JSONArray()
            for (i in 0 until list.childCount) {
                val view = list.getChildAt(i)
                val h = list.getChildViewHolder(view) as? BaseThingsAdapter.BaseThingViewHolder ?: continue
                val id = view.getTag(R.id.tag_thing_card_bound_stable_id) as? Long ?: continue
                val phase = h.coverGeneration?.state
                cards.put(JSONObject().put("id", id).put("phase", phase?.phase).put("percent", phase?.percent)
                    .put("alpha", view.alpha).put("height", view.height).put("scale", view.scaleX)
                    .put("particle", h.cv?.particleAnimationRunning).put("drag", view.getTag(R.id.tag_thing_card_drag_active))
                    .put("text", (if (h.tvInlineMediaCount?.isShown == true) h.tvInlineMediaCount else h.tvImageCount)?.text))
                if (id in ids && id != ids.first()) check(view.alpha > 0f || list.itemAnimator?.isRunning == true) {
                    "非更新动画中的背景卡片透明：$id/$label"
                }
            }
            samples.put(JSONObject().put("label", label).put("video", videoLabel).put("ms", SystemClock.uptimeMillis() - videoStart).put("scroll", list.scrollState)
                .put("mode", mode.getCurrentMode()).put("overlays", ParticleDismissOverlay.activeIn(activity).size).put("cards", cards))
        }
        File(output, "samples.json").writeText(samples.toString(2))
    }
    private data class Work(val file: File, val gate: CoverGenerationGateWorker.Gate, val id: java.util.UUID)
    private fun work(label: String, frame: Long = 0): Work {
        val file = File(targetContext.cacheDir, "$label-${SystemClock.uptimeMillis()}.mp4")
        if (visual) fixture(if (label.contains("new") || label.endsWith("B")) "B" else "A").use { input -> file.outputStream().use(input::copyTo) }
        else targetContext.assets.open("selection-appearance/media.mp4").use { input -> file.outputStream().use(input::copyTo) }
        return gate(file, frame)
    }
    private fun fixture(label: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
        "cat /sdcard/Android/data/com.ywwynm.everythingdone.covervalidation/files/visual-fixtures/$label.mp4"))
    private fun gate(file: File, frame: Long = 0): Work {
        val method = VideoCoverPreviewManager.javaClass.getDeclaredMethod("previewFileFor", Context::class.java,
            String::class.java, java.lang.Long::class.java).apply { isAccessible = true }
        val out = method.invoke(VideoCoverPreviewManager, targetContext, file.path, frame) as File
        val gate = CoverGenerationGateWorker.Gate(); CoverGenerationGateWorker.gates[file.path] = gate
        val request = OneTimeWorkRequest.Builder(CoverGenerationGateWorker::class.java)
            .setInputData(Data.Builder().putString("path", file.path).putLong("frame", frame).build()).build()
        WorkManager.getInstance(targetContext).enqueueUniqueWork(out.name, ExistingWorkPolicy.KEEP, request).result.get()
        return Work(file, gate, request.id)
    }
    private fun applySource(file: File, all: Boolean = true) {
        main {
            for (id in if (all) ids else listOf(ids.first())) {
                val thing = model(id)
                thing.attachment = AttachmentHelper.SIGNAL + (if (file.extension == "mp4") AttachmentHelper.VIDEO else AttachmentHelper.IMAGE) + file.path
                thing.thingCardAppearance = ThingCardAppearance()
            }
            adapter.notifyDataSetChanged()
        }
        idle()
    }
    private fun half(work: Work) {
        work.gate.begin.countDown()
        check(work.gate.halfway.await(30, TimeUnit.SECONDS))
        await("实际帧进度到达卡片") { (0 until list.childCount).mapNotNull {
            (list.getChildViewHolder(list.getChildAt(it)) as? BaseThingsAdapter.BaseThingViewHolder)?.coverGeneration?.state
        }.any { it.phase == CoverGenerationState.Phase.GENERATING && it.percent >= 50 } }
    }
    private fun finish(work: Work) {
        event("release-completion-${work.file.name}")
        work.gate.begin.countDown(); work.gate.finish.countDown(); work.gate.publish.countDown()
        val deadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < deadline) {
            if (WorkManager.getInstance(targetContext).getWorkInfoById(work.id).get()?.state == WorkInfo.State.SUCCEEDED) {
                event("worker-completed-${work.file.name}"); return
            }
            SystemClock.sleep(20)
        }
        error("真实任务未完成")
    }
    private fun readyVisible(label: String) {
        await("$label 恢复 GIF 与数量") { (0 until list.childCount).mapNotNull {
            list.getChildViewHolder(list.getChildAt(it)) as? BaseThingsAdapter.BaseThingViewHolder
        }.any { (it.ivImageAttachment?.drawable is GifDrawable || it.ivMediaBackground?.drawable is GifDrawable) &&
            it.coverGeneration?.state?.phase.let { p -> p == null || p == CoverGenerationState.Phase.READY } } }
        sample(label); record(label, true)
    }
    private fun readyTarget(label: String) {
        await(label) {
            val h = holder(ids.first()) ?: return@await false
            val background = model(ids.first()).thingCardAppearance.mediaBackgroundEnabled
            val image = if (background) h.ivMediaBackground else h.ivImageAttachment
            val text = if (background) h.tvInlineMediaCount else h.tvImageCount
            image?.drawable is GifDrawable && text?.text.toString() ==
                AttachmentHelper.getImageAttachmentCountStr(model(ids.first()).attachment, targetContext)
        }
        record(label, true)
    }
    override fun onStart() {
        val result = JSONObject()
        try {
            check(targetContext.packageName == "com.ywwynm.everythingdone.covervalidation")
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            output = File(targetContext.getExternalFilesDir(null), if (visual) "cover-visual-$runId" else "cover-interactions-$scenario").apply { mkdirs() }
            main {
                FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_COVER_DYNAMIC, true)
                val edit = targetContext.getSharedPreferences(Def.Meta.META_DATA_NAME, 0).edit()
                listOf(Def.Meta.KEY_1_0_4_TO_1_0_5, Def.Meta.KEY_1_1_4_TO_1_1_5, Def.Meta.KEY_1_2_7_TO_1_3_0, Def.Meta.KEY_1_3_0_TO_1_3_1,
                    Def.Meta.KEY_1_3_3_TO_1_3_4).forEach { edit.putBoolean(it, true) }; edit.commit()
                DBHelper(targetContext).use { it.writableDatabase.execSQL("DELETE FROM things WHERE id BETWEEN 9200001 AND 9200036") }
                val dao = ThingDAO.getInstance(targetContext)!!
                ids.forEachIndexed { i, id -> check(!dao.create(Thing(id, Thing.NOTE,
                    if (i % 2 == 0) 0xffeedfa7.toInt() else 0xff2c82a9.toInt(), (i + 1).toLong()).apply {
                    location = (ids.size - i).toLong()
                    title = "并发回归 ${i + 1}"; content = (1..(i % 6 + 1)).joinToString("\n") { "虚构内容 $it" }
                }, false, false)) }
                ThingAnimationPreferences.preferences(targetContext).edit().putInt(ThingAnimationPreferences.SWIPE_KEY, 1).commit()
                ParticleTuning.setStyle(targetContext, ParticleStyle.of("drift"))
                App.getApp()!!.setStatus(Def.ThingStatus.UNDERWAY, true)
            }
            activity = startActivitySync(Intent(targetContext, ThingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ThingsActivity
            await("真实首页", 60000) {
                list = field(activity, "mRecyclerView") as RecyclerView
                manager = ThingManager.getInstance(activity)!!; mode = field(activity, "mModeManager") as ModeManager
                list.childCount > 2 && activity.hasWindowFocus()
            }
            main { adapter.setShouldThingsAnimWhenAppearing(false) }
            idle()
            if (scenario in listOf("all", "capture")) snapshotSpinner()
            if (scenario in listOf("all", "scroll")) { scrolling(false); scrolling(true) }
            if (scenario in listOf("all", "drag")) dragging()
            if (scenario in listOf("all", "particle")) { particles(false); particles(true) }
            if (scenario in listOf("all", "edit")) { editing(); motionAndFrameChanges() }
            if (scenario in listOf("visual", "visual-scroll")) capture("scroll") { visualScrolling() }
            if (scenario in listOf("visual", "visual-rest", "visual-drag")) capture("drag") { dragging() }
            if (scenario in listOf("visual", "visual-rest", "visual-after-drag", "visual-particle")) capture("particle") { particles(false); particles(true) }
            if (scenario in listOf("visual", "visual-rest", "visual-after-drag", "visual-edit")) {
                capture("edit") { editing() }
                capture("motion") { motionAndFrameChanges() }
            }
            if (scenario in listOf("visual", "visual-rest", "visual-after-drag", "visual-panel")) capture("panel") { visualPanel() }
            if (scenario == "visual-handoff") capture("handoff") { verifyMediaHandoff() }
            if (scenario == "visual-autoplay") capture("autoplay") { verifyAutoplayHandoff() }
            if (scenario == "visual-navigation") capture("navigation") { visualNavigation() }
            result.put("ok", true)
        } catch (t: Throwable) { result.put("ok", false).put("error", t.stackTraceToString()) }
        finally {
            CoverGenerationGateWorker.gates.values.forEach { it.begin.countDown(); it.finish.countDown(); it.publish.countDown() }
            result.put("checks", checks)
            if (::output.isInitialized) File(output, "result.json").writeText(result.toString(2))
            if (::activity.isInitialized) main { activity.finish() }
            finish(if (result.optBoolean("ok")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("result", result.toString()) })
        }
    }
    private fun scrolling(fling: Boolean) {
        top(); val work = work(if (fling) "fling" else "held"); applySource(work.file)
        if (fling) half(work)
        val bounds = Rect(); main { list.getGlobalVisibleRect(bounds) }
        val x = bounds.left + bounds.width() * .55f; val start = bounds.top + bounds.height() * .72f
        val down = SystemClock.uptimeMillis(); var y = start
        touch(down, MotionEvent.ACTION_DOWN, x, y)
        try {
            for (i in 1..12) {
                y = start - bounds.height() * .32f * i / 12
                touch(down, MotionEvent.ACTION_MOVE, x, y); SystemClock.sleep(if (fling) 6 else 22)
            }
            if (fling) {
                touch(down, MotionEvent.ACTION_UP, x, y)
                await("真实惯性滑动") { list.scrollState == RecyclerView.SCROLL_STATE_SETTLING }
                work.gate.finish.countDown(); sample("fling-release")
                var settlingChanges = false
                val until = SystemClock.uptimeMillis() + 6000
                while (SystemClock.uptimeMillis() < until) {
                    main { if (list.scrollState == RecyclerView.SCROLL_STATE_SETTLING && work.gate.percent > 50) settlingChanges = true }
                    if (WorkManager.getInstance(targetContext).getWorkInfoById(work.id).get()?.state?.isFinished == true) break
                    SystemClock.sleep(24)
                }
                record("fling 中真实帧进度变化", settlingChanges)
            } else {
                main { record("普通滑动保持 DRAGGING", list.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) }
                half(work); sample("finger-held-generating")
                main {
                    val h = (0 until list.childCount).mapNotNull {
                        list.getChildViewHolder(list.getChildAt(it)) as? BaseThingsAdapter.BaseThingViewHolder
                    }.first { (it.itemView.getTag(R.id.tag_thing_card_bound_stable_id) as? Long) in ids }
                    val id = h.itemView.getTag(R.id.tag_thing_card_bound_stable_id) as Long
                    model(id).content = "手指保持按住时更新内容\n第二行"
                    model(id).thingCardAppearance = model(id).thingCardAppearance.withContentMaxLines(false, 1)
                    adapter.notifyItemChanged(pos(id))
                }
                finish(work)
                main { record("完成时手指未松开且仍为 DRAGGING", list.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) }
                readyVisible("持续触摸时完成")
                shot("finger-held-ready")
            }
        } finally { if (!fling) touch(down, MotionEvent.ACTION_CANCEL, x, y) }
        finish(work); idle(); readyVisible(if (fling) "惯性滑动后完成" else "自然滑动后完成")
        top(); readyVisible("离屏返回-$fling")
    }
    private fun nearEnd(work: Work) {
        work.gate.holdAtEnd = true
        half(work); work.gate.finish.countDown()
        check(work.gate.nearEnd.await(30, TimeUnit.SECONDS))
        await("末帧进度到达界面") { holder(ids.first())?.coverGeneration?.state?.percent == 99 }
    }
    private fun visualScrolling() {
        // 自然移动、持续触摸及 fling 分开录制，避免把静止按住当作连续滑动。
        for (kind in listOf("natural", "held", "fling")) {
            top(); val work = work(kind); applySource(work.file)
            event("$kind-preparing", 1200)
            if (kind != "held") nearEnd(work)
            val r = Rect(); main { list.getGlobalVisibleRect(r) }
            val x = r.left + r.width() * .55f; val start = r.top + r.height() * .8f
            var y = start; val down = SystemClock.uptimeMillis()
            touch(down, MotionEvent.ACTION_DOWN, x, y)
            try {
                for (i in 1..30) {
                    y = start - r.height() * .55f * i / 30
                    touch(down, MotionEvent.ACTION_MOVE, x, y)
                    if (kind == "natural" && i == 5) { event("natural-release-during-move"); work.gate.publish.countDown() }
                    SystemClock.sleep(if (kind == "fling") 3 else 70)
                }
                event("$kind-gesture")
                if (kind == "fling") {
                    touch(down, MotionEvent.ACTION_UP, x, y)
                    await("录屏惯性滑动") { list.scrollState == RecyclerView.SCROLL_STATE_SETTLING }
                    finish(work)
                    await("惯性滑动期间 READY") {
                        list.scrollState == RecyclerView.SCROLL_STATE_SETTLING && (0 until list.childCount).any {
                            (list.getChildViewHolder(list.getChildAt(it)) as? BaseThingsAdapter.BaseThingViewHolder)
                                ?.coverGeneration?.state?.phase == CoverGenerationState.Phase.READY
                        }
                    }
                    event("fling-ready-while-settling", 2000)
                } else {
                    if (kind == "held") {
                        event("held-preparing-finger-down", 1200); half(work)
                        event("held-generating-finger-down", 1200)
                    }
                    finish(work); readyVisible("$kind-visible-ready")
                    event("$kind-ready-finger-down", 2000)
                    main { record("$kind 完成时仍在手指手势中", list.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) }
                }
            } finally { if (kind != "fling") touch(down, MotionEvent.ACTION_CANCEL, x, y) }
            idle(); top(); readyTarget("$kind 首卡离屏返回正确")
            event("$kind-restored", 1200)
        }
    }
    private fun snapshotSpinner() {
        top(); val work = work("capture"); applySource(work.file)
        await("等待图标附着") { holder(ids.first())!!.tvImageCount!!.compoundDrawablesRelative[0] != null }
        main {
            val h = holder(ids.first())!!
            val icon = h.tvImageCount!!.compoundDrawablesRelative[0]
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.translate(256f, 256f); icon.draw(canvas)
            val pixels = IntArray(512 * 512); bitmap.getPixels(pixels, 0, 512, 0, 0, 512, 512)
            File(output, "software-spinner.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val drawn = pixels.indices.filter { Color.alpha(pixels[it]) > 0 }
            val width = if (drawn.isEmpty()) 0 else drawn.maxOf { it % 512 } - drawn.minOf { it % 512 } + 1
            val height = if (drawn.isEmpty()) 0 else drawn.maxOf { it / 512 } - drawn.minOf { it / 512 } + 1
            record("软件快照保留可辨认的加载圈形态", width > icon.bounds.width() / 2 && height > icon.bounds.height() / 2)
        }
        finish(work)
    }
    private fun dragging() {
        top(); val work = work("drag"); applySource(work.file)
        event("drag-preparing", 1500)
        val bounds = Rect(); main { check(holder(ids.first())!!.itemView.getGlobalVisibleRect(bounds)) }
        var x = bounds.exactCenterX(); var y = bounds.exactCenterY()
        val down = SystemClock.uptimeMillis(); touch(down, MotionEvent.ACTION_DOWN, x, y)
        val controller = field(activity, "mOverlayDragController") as ThingListOverlayDragController
        try {
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 140L)
            await("真实长按拖动") { controller.isActive && mode.getCurrentMode() == ModeManager.MOVING }
            // 长按后立即移动，避免三星系统长按识屏接管仍静止的触摸。
            event("drag-floating-waiting")
            val viewport = Rect(); main { list.getGlobalVisibleRect(viewport) }
            val edge = viewport.bottom - 64 * targetContext.resources.displayMetrics.density
            for (i in 1..12) { touch(down, MotionEvent.ACTION_MOVE, x, y + (edge - y) * i / 12); SystemClock.sleep(16) }
            y = edge
            half(work); sample("drag-generating")
            event("drag-generating-with-background", 1500)
            main { record("生成中源卡保持占位", holder(ids.first())?.itemView?.alpha?.let { it < .01f } ?: true) }
            main {
                model(ids.first()).content = "拖动尚未结束时更新内容"
                model(ids.first()).thingCardAppearance = model(ids.first()).thingCardAppearance.copy(mediaBackgroundEnabled = true)
                adapter.notifyItemChanged(pos(ids.first()))
            }
            finish(work); sample("drag-ready"); shot("drag-ready")
            event("drag-completed-still-held", 1800)
            main { record("生成完成不终止真实拖动", controller.isActive && mode.getCurrentMode() == ModeManager.MOVING) }
            // 继续下行，覆盖源 holder 离屏后复用。
            val deadline = SystemClock.uptimeMillis() + 30000
            var end = false
            while (!end && SystemClock.uptimeMillis() < deadline) {
                touch(down, MotionEvent.ACTION_MOVE, x, y); sample("drag-edge")
                main { end = !list.canScrollVertically(1); check(controller.isActive) }
                SystemClock.sleep(120)
            }
            record("不松手跨越列表", end)
        } finally { touch(down, MotionEvent.ACTION_CANCEL, x, y) }
        await("拖动取消恢复") { !controller.isActive && mode.getCurrentMode() == ModeManager.NORMAL }
        top(); readyTarget("拖动源自身恢复最新 GIF 与数量")
        main {
            record("源卡恢复透明度与比例", holder(ids.first())!!.itemView.let { it.alpha == 1f && it.scaleX == 1f })
            record("拖动结束应用新内容与背景布局", holder(ids.first())!!.tvContent!!.text.toString().contains("拖动尚未结束") &&
                holder(ids.first())!!.ivMediaBackground!!.isShown)
        }
        event("drag-handoff-restored", 2000)
    }
    private fun particles(reveal: Boolean) {
        top(); val work = work(if (reveal) "reveal" else "swipe"); applySource(work.file)
        if (visual && reveal) nearEnd(work) else half(work)
        event(if (reveal) "reveal-before-start" else "particle-before-start", 1500)
        if (reveal) {
            main { ThingSwipeReturn(activity, list, alwaysParticles = true).start(pos(ids.first()), particles = true) }
            await("粒子拼合已开始") { ParticleDismissOverlay.activeIn(activity).isNotEmpty() }
            sample("reveal-before-complete")
            main {
                model(ids[1]).content = "背景卡在粒子期间发生内容更新"
                adapter.notifyItemChanged(pos(ids[1]))
            }
            finish(work); sample("reveal-complete")
            if (visual) {
                await("拼合仍显示时 READY 已交付") { ParticleDismissOverlay.activeIn(activity).isNotEmpty() &&
                    holder(ids.first())?.coverGeneration?.state?.phase == CoverGenerationState.Phase.READY }
                event("reveal-ready-with-active-particles")
            }
            idle(); readyTarget("粒子拼合目标自身交接后显示最新封面")
            event("reveal-handoff-restored", 2200)
        } else {
            val bounds = Rect(); main { holder(ids.first())!!.itemView.getGlobalVisibleRect(bounds) }
            val start = bounds.left + bounds.width() * .75f; val y = bounds.exactCenterY()
            val down = SystemClock.uptimeMillis(); var x = start
            touch(down, MotionEvent.ACTION_DOWN, x, y)
            try {
                for (i in 1..18) {
                    x = start - bounds.width() * .32f * i / 18
                    touch(down, MotionEvent.ACTION_MOVE, x, y); SystemClock.sleep(16)
                }
                await("手指控制粒子消散") { ParticleDismissOverlay.activeIn(activity).isNotEmpty() }
                sample("particle-held"); event("particle-held-generating", 1500)
                finish(work); sample("particle-complete"); shot("particle-complete")
                if (visual) await("消散仍显示时 READY 已交付") { holder(ids.first())?.coverGeneration?.state?.phase == CoverGenerationState.Phase.READY }
                event("particle-ready-still-held", 2000)
                main { record("真实粒子期间任务已完成", ParticleDismissOverlay.activeIn(activity).isNotEmpty()) }
            } finally { touch(down, MotionEvent.ACTION_CANCEL, x, y) }
            idle(); readyTarget("粒子取消目标自身回位后显示最新封面")
            main { record("粒子交接释放姿态锁", holder(ids.first())?.cv?.particleAnimationRunning == false) }
            event("particle-cancel-restored", 1800)
        }
    }
    private fun editing() {
        top(); val work = work("edit"); applySource(work.file); half(work)
        event("edit-before-appearance", 1500)
        val id = ids.first()
        main {
            val thing = model(id)
            thing.content = (1..20).joinToString("\n") { "生成过程中修改的内容 $it" }
            thing.setColor(0xff34445b.toInt())
            thing.thingCardAppearance = thing.thingCardAppearance.copy(spanMode = Thing.THING_CARD_SPAN_FULL,
                imagePlacement = Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT, contentMaxLinesFull = 2)
            val source = ThingCardMediaHelper.resolveEffectiveMediaSource(thing)!!
            thing.thingCardAppearance = thing.thingCardAppearance.copy(sources = mapOf(source.typePathName to
                ThingCardAppearance.SourceAppearance(presentations = mapOf(ThingCardAppearance.PRESENTATION_SIDE_PANEL to
                    ThingCardAppearance.MediaPresentationAppearance(targetAspectRatio = 1.0)))))
            adapter.notifyItemChanged(pos(id))
        }
        idle(); sample("edit-while-generating")
        event("edit-new-color-content-size-ratio", 2000)
        main { record("内容限制更新且进度订阅仍有效", holder(id)!!.tvContent!!.maxLines == 2 && holder(id)!!.coverGeneration != null) }
        var height = 0; main { height = holder(id)!!.itemView.height }
        finish(work); readyVisible("外观与内容更新后完成"); idle()
        main { record("完成不覆盖新比例与卡片高度", holder(id)!!.itemView.height == height && model(id).thingCardAppearance.imagePlacement == Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT) }
        event("edit-new-appearance-completed", 1800)
        val old = work("old-source"); applySource(old.file); half(old)
        event("source-A-generating", 1600)
        val next = work("new-source"); applySource(next.file, false)
        event("source-B-selected", 1600)
        finish(old)
        await("新来源保持准备") { holder(id)?.coverGeneration?.state?.phase == CoverGenerationState.Phase.PREPARING }
        main { record("旧完成不能结束新来源提示", holder(id)!!.coverGeneration!!.state.phase == CoverGenerationState.Phase.PREPARING) }
        event("source-A-finished-B-still-selected", 1800)
        half(next)
        event("source-B-generating", 1600)
        val still = File(targetContext.cacheDir, "race-still.png")
        val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        still.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        applySource(still, false); event("static-selected-before-B-completes", 1600); finish(next); idle()
        event("source-B-finished-static-retained", 1800)
        main { record("动态改静态后旧任务不恢复 GIF", holder(id)!!.ivImageAttachment!!.drawable !is GifDrawable &&
            holder(id)!!.coverGeneration?.state?.phase.let { it == null || it == CoverGenerationState.Phase.IDLE }) }
        val global = work("global"); applySource(global.file); half(global)
        main {
            ThingCardContentLimitsHelper.setGlobalContentMaxLines(targetContext, false, 1)
            model(id).content = "第一行\n第二行\n第三行"
            adapter.notifyDataSetChanged()
        }
        idle(); main { record("生成中修改全局显示限制", holder(id)!!.tvContent!!.maxLines == 1) }
        event("global-content-limit-generating", 1600)
        finish(global); readyVisible("全局选项更新后完成")
        event("global-content-limit-ready", 1800)
        shot("edited-ready")
    }
    private fun motionAndFrameChanges() {
        fun motion(label: String): Pair<File, Work> {
            val file = File(targetContext.cacheDir, "$label-${SystemClock.uptimeMillis()}.jpg")
            val image = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
            file.outputStream().use { stream ->
                image.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                if (visual) fixture(if (label.endsWith("B")) "B" else "A").use { it.copyTo(stream) }
                else targetContext.assets.open("selection-appearance/media.mp4").use { it.copyTo(stream) }
            }; image.recycle()
            val info = MotionPhotoDetector.detect(file.path)
            check(info.isMotionPhoto)
            val method = MotionPhotoCoverHelper.javaClass.getDeclaredMethod("prepareVideo", Context::class.java,
                String::class.java, Boolean::class.javaPrimitiveType, kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }
            val extracted = method.invoke(MotionPhotoCoverHelper, targetContext, file.path, false,
                { _: CoverGenerationState -> Unit }) as File
            return file to gate(extracted)
        }
        top(); val (photoA, a) = motion("motion-A"); applySource(photoA); half(a)
        event("motion-A-generating", 1500)
        val (photoB, b) = motion("motion-B"); applySource(photoB, false)
        event("motion-B-selected", 1500); finish(a)
        await("动态照片 B 接管订阅") { holder(ids.first())!!.coverGeneration?.state?.phase == CoverGenerationState.Phase.PREPARING }
        record("动态照片 A 完成不覆盖 B", true)
        half(b)
        event("motion-B-generating", 1500)
        main {
            val t = model(ids.first())
            t.thingCardAppearance = t.thingCardAppearance.copy(mediaBackgroundEnabled = true)
            val source = ThingCardMediaHelper.resolveEffectiveMediaSource(t)!!
            t.thingCardAppearance = t.thingCardAppearance.copy(sources = mapOf(source.typePathName to
                ThingCardAppearance.SourceAppearance(presentations = mapOf(ThingCardAppearance.PRESENTATION_MEDIA_BACKGROUND to
                    ThingCardAppearance.MediaPresentationAppearance(targetAspectRatio = 2.0)))))
            adapter.notifyItemChanged(pos(t.id))
        }
        idle(); var height = 0; main { height = holder(ids.first())!!.itemView.height }
        event("motion-B-new-background-ratio", 1800)
        finish(b)
        await("动态照片 B 背景 GIF") { holder(ids.first())!!.ivMediaBackground!!.drawable is GifDrawable }
        idle(); main { record("动态照片生成中改比例后完成保留新几何", height == holder(ids.first())!!.itemView.height) }
        event("motion-B-ready-new-ratio", 1800)
        val old = work("frame"); applySource(old.file); half(old)
        val changed = gate(old.file, 500)
        main {
            val t = model(ids.first()); val source = ThingCardMediaHelper.resolveEffectiveMediaSource(t)!!
            t.thingCardAppearance = t.thingCardAppearance.copy(sources = mapOf(source.typePathName to
                ThingCardAppearance.SourceAppearance(videoFrameMs = 500)))
            adapter.notifyItemChanged(pos(t.id))
        }
        idle(); finish(old)
        event("frame-500-selected-old-finished", 1800)
        await("新起始帧未完成仍显示准备") { holder(ids.first())!!.coverGeneration?.state?.phase == CoverGenerationState.Phase.PREPARING }
        record("旧视频帧任务不能覆盖新起始帧", true)
        half(changed)
        main {
            FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_COVER_DYNAMIC, false)
            adapter.notifyDataSetChanged()
        }
        idle(); finish(changed)
        event("autoplay-disabled-new-frame-completed", 1800)
        main { record("生成时关闭自动播放不被完成回调重新开启", holder(ids.first())!!.coverGeneration == null &&
            holder(ids.first())!!.ivImageAttachment!!.drawable !is GifDrawable) }
        main { FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_COVER_DYNAMIC, true); adapter.notifyDataSetChanged() }
        idle(); readyTarget("重新开启自动播放使用新帧缓存")
        main { record("新帧加载身份匹配 500ms", holder(ids.first())!!.ivImageAttachment!!
            .getTag(R.id.tag_thing_card_image_load_key).toString().contains(":500:")) }
        event("autoplay-enabled-new-frame-ready", 1800)
    }
    private fun tapUi(id: String? = null, contains: String? = null) {
        var found: android.view.accessibility.AccessibilityNodeInfo? = null
        var viewBounds: Rect? = null
        fun visit(n: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if (n.isVisibleToUser && (id == null || n.viewIdResourceName?.endsWith("/$id") == true) &&
                (contains == null || n.text?.contains(contains) == true)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it)?.let { hit -> return hit } }
            return null
        }
        // Accessibility 需要应用主线程响应请求，不能在 runOnMainSync 内等待同一个界面树。
        val until = SystemClock.uptimeMillis() + 10000
        while (found == null && viewBounds == null && SystemClock.uptimeMillis() < until) {
            found = automation.rootInActiveWindow?.let(::visit)
            if (found == null && id != null) main {
                // 粒子面板交接可暂时隐藏可访问性子树；仍按实际控件 ID 和屏幕矩形输入，禁止猜坐标。
                val resourceId = R.id::class.java.getField(id).getInt(null)
                val view = activity.findViewById<View>(resourceId)
                if (view?.isShown == true && view.isEnabled) {
                    val bounds = Rect()
                    if (view.getGlobalVisibleRect(bounds) && bounds.width() > 10 && bounds.height() > 10) viewBounds = bounds
                }
            }
            if (found == null && viewBounds == null) SystemClock.sleep(50)
        }
        check(found != null || viewBounds != null) { "未找到界面控件 $id / $contains" }
        val r = viewBounds ?: Rect().also { found!!.getBoundsInScreen(it) }; check(!r.isEmpty)
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, r.exactCenterX(), r.exactCenterY())
        touch(down, MotionEvent.ACTION_UP, r.exactCenterX(), r.exactCenterY())
        event("tap-${id ?: contains}", 500)
    }
    private fun verifyMediaHandoff() {
        top(); val work = work("handoff"); applySource(work.file); half(work)
        await("交接前静态封面全部显示") { (0 until list.childCount).all {
            val h = list.getChildViewHolder(list.getChildAt(it)) as? BaseThingsAdapter.BaseThingViewHolder
            h?.coverGeneration == null || h.ivImageAttachment?.drawable != null
        } }
        var blankFrames = 0
        var observedReady = false
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            for (i in 0 until list.childCount) {
                val h = list.getChildViewHolder(list.getChildAt(i)) as? BaseThingsAdapter.BaseThingViewHolder ?: continue
                if (h.coverGeneration?.state?.phase == CoverGenerationState.Phase.READY) {
                    observedReady = true
                    if (h.itemView.isShown && h.itemView.alpha > 0f && h.ivImageAttachment?.drawable == null) blankFrames++
                }
            }
            true
        }
        main { list.viewTreeObserver.addOnPreDrawListener(listener) }
        try {
            event("handoff-static-ready", 1200); finish(work); readyVisible("handoff-gif-visible")
            event("handoff-gif-ready", 1800)
        } finally { main { list.viewTreeObserver.removeOnPreDrawListener(listener) } }
        File(output, "handoff.json").writeText(JSONObject().put("blankFrames", blankFrames).put("observedReady", observedReady).toString(2))
        record("生成交接可绘制帧不清空封面", observedReady && blankFrames == 0)
    }
    private fun verifyAutoplayHandoff() {
        top(); val work = work("autoplay"); applySource(work.file); half(work)
        main {
            model(ids[1]).thingCardAppearance = model(ids[1]).thingCardAppearance.copy(mediaBackgroundEnabled = true)
            adapter.notifyItemChanged(pos(ids[1]))
        }
        idle()
        await("切换前缩略图和背景已有静态封面") {
            holder(ids.first())?.ivImageAttachment?.drawable != null && holder(ids[1])?.ivMediaBackground?.drawable != null
        }
        event("autoplay-generating-thumbnail-and-background", 1200)
        var disablingBlanks = 0
        val disablingListener = android.view.ViewTreeObserver.OnDrawListener {
            holder(ids.first())?.ivImageAttachment?.let { if (it.isShown && it.drawable == null) disablingBlanks++ }
            holder(ids[1])?.ivMediaBackground?.let { if (it.isShown && it.drawable == null) disablingBlanks++ }
        }
        main { list.viewTreeObserver.addOnDrawListener(disablingListener) }
        main { FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_COVER_DYNAMIC, false); adapter.notifyDataSetChanged() }
        idle(); finish(work)
        await("自动播放关闭后静态封面就绪") {
            holder(ids.first())?.ivImageAttachment?.drawable != null && holder(ids[1])?.ivMediaBackground?.drawable != null
        }
        event("autoplay-disabled-completed", 1200)
        main { list.viewTreeObserver.removeOnDrawListener(disablingListener) }
        val identities = JSONArray()
        main {
            @Suppress("UNCHECKED_CAST")
            val cache = field(adapter, "mThingCardMediaBitmapCache") as android.util.LruCache<String, Bitmap>
            identities.put(JSONObject().put("cacheKeys", JSONArray(cache.snapshot().keys.toList())))
        }
        var thumbnailBlanks = 0
        var backgroundBlanks = 0
        // 背景加载本身在 OnPreDraw 完成；在所有预绘制回调后采样，避免把同一帧的中间态算作闪空。
        val listener = android.view.ViewTreeObserver.OnDrawListener {
            holder(ids.first())?.ivImageAttachment?.let { if (it.isShown && it.drawable == null) thumbnailBlanks++ }
            holder(ids[1])?.ivMediaBackground?.let { if (it.isShown && it.drawable == null) {
                backgroundBlanks++
                identities.put(JSONObject().put("blankBackgroundKey", it.getTag(R.id.tag_thing_card_media_background_load_key)))
            } }
        }
        main { list.viewTreeObserver.addOnDrawListener(listener) }
        try {
            event("autoplay-enable")
            main { FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_COVER_DYNAMIC, true); adapter.notifyDataSetChanged() }
            await("缩略图与背景重新播放 GIF") {
                holder(ids.first())?.ivImageAttachment?.drawable is GifDrawable && holder(ids[1])?.ivMediaBackground?.drawable is GifDrawable
            }
            event("autoplay-both-ready", 1800)
        } finally { main { list.viewTreeObserver.removeOnDrawListener(listener) } }
        File(output, "autoplay.json").writeText(JSONObject().put("thumbnailBlanks", thumbnailBlanks)
            .put("backgroundBlanks", backgroundBlanks).put("disablingBlanks", disablingBlanks).put("identities", identities).toString(2))
        record("自动播放关闭及重开缩略图与背景不闪空", thumbnailBlanks == 0 && backgroundBlanks == 0 && disablingBlanks == 0)
    }
    private fun visualNavigation() {
        fun phase(expected: CoverGenerationState.Phase, label: String) {
            await(label) { activity.hasWindowFocus() && holder(ids.first())?.coverGeneration?.state?.phase == expected }
            record(label, true); event(label, 1200)
        }
        fun detail(): Activity {
            val monitor = addMonitor(DetailActivity::class.java.name, null, false)
            try {
                val rect = Rect(); main { holder(ids.first())!!.tvTitle!!.getGlobalVisibleRect(rect) }
                val down = SystemClock.uptimeMillis()
                touch(down, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY())
                touch(down, MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY())
                val opened = waitForMonitorWithTimeout(monitor, 10000) ?: error("未打开真实记事详情")
                await("详情已显示") { opened.hasWindowFocus() }
                return opened
            } finally { removeMonitor(monitor) }
        }
        fun back() {
            command("input keyevent 4")
            await("详情返回首页") { activity.hasWindowFocus() }
            idle()
        }
        fun background() {
            command("input keyevent 3")
            await("应用已退到后台") { !activity.hasWindowFocus() }
        }
        fun foreground() {
            command("am start -W -n ${targetContext.packageName}/${ThingsActivity::class.java.name} -f 0x10200000")
            await("应用已回到首页") { activity.hasWindowFocus() }
            idle()
        }
        top(); val detailWork = work("navigation-detail"); applySource(detailWork.file)
        main { ThingDAO.getInstance(targetContext)!!.update(Thing.NOTE, model(ids.first()), false, false) }
        phase(CoverGenerationState.Phase.PREPARING, "detail-before-preparing")
        detail(); event("detail-opened-during-preparing", 1600)
        back(); phase(CoverGenerationState.Phase.PREPARING, "detail-return-still-preparing")
        half(detailWork); event("detail-before-generating", 1200)
        detail(); event("detail-opened-during-generating", 1600)
        finish(detailWork); event("detail-completed-away", 1800)
        back(); readyTarget("详情中完成后返回显示 GIF 与数量"); event("detail-return-ready", 1800)

        val homeWork = work("navigation-home"); applySource(homeWork.file)
        phase(CoverGenerationState.Phase.PREPARING, "home-before-preparing")
        background(); event("home-left-during-preparing", 1400)
        foreground(); phase(CoverGenerationState.Phase.PREPARING, "home-return-still-preparing")
        background(); event("home-left-preparing-to-generate", 1200)
        homeWork.gate.begin.countDown(); check(homeWork.gate.halfway.await(30, TimeUnit.SECONDS))
        event("home-generated-half-away", 1200)
        foreground(); phase(CoverGenerationState.Phase.GENERATING, "home-return-generating")
        background(); event("home-left-during-generating", 1400)
        finish(homeWork); event("home-completed-away", 1600)
        foreground(); readyTarget("后台完成后返回显示 GIF 与数量"); event("home-return-ready", 1800)
        main { record("导航返回保留首卡身份与媒体来源", pos(ids.first()) >= 0 &&
            model(ids.first()).attachment?.contains(homeWork.file.path) == true && holder(ids.first())?.itemView?.isShown == true) }
    }
    private fun visualPanel() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        top(); val a = work("panel-A"); val b = work("panel-B")
        val still = File(targetContext.cacheDir, "STATIC-S.png")
        val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap); canvas.drawColor(0xff45a34e.toInt())
        canvas.drawText("S", 250f, 265f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 180f })
        still.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        applySource(a.file)
        main {
            val t = model(ids.first())
            t.attachment = listOf(a.file, b.file, still).joinToString("") { file ->
                AttachmentHelper.SIGNAL + (if (file.extension == "mp4") AttachmentHelper.VIDEO else AttachmentHelper.IMAGE) + file.path
            }
            adapter.notifyItemChanged(pos(t.id))
        }
        idle(); half(a); event("panel-A-generating", 1200)
        val r = Rect(); main { holder(ids.first())!!.itemView.getGlobalVisibleRect(r) }
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, r.exactCenterX(), r.exactCenterY())
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 180L)
        touch(down, MotionEvent.ACTION_UP, r.exactCenterX(), r.exactCenterY())
        // 正式应用的悬浮提醒可能覆盖工具栏。只触发隔离应用的真实菜单点击，不碰提醒。
        await("选择工具栏可用") { activity.findViewById<View>(R.id.act_customize_card_appearance)?.isShown == true }
        main { check(activity.findViewById<View>(R.id.act_customize_card_appearance).performClick()) }
        await("外观面板入场交接完成") {
            field(activity, "mThingCardAppearanceDraft") != null &&
                field(activity, "mAppearancePanelInteraction") == null &&
                activity.findViewById<View>(R.id.thing_card_appearance_panel).isShown &&
                ParticleDismissOverlay.activeIn(activity).isEmpty()
        }
        idle(); event("panel-opened-through-toolbar-click", 1200)
        tapUi("bt_thing_card_appearance_span_full")
        await("宽卡草稿已生效") { model(ids.first()).thingCardAppearance.spanMode == Thing.THING_CARD_SPAN_FULL }
        idle()
        tapUi("bt_thing_card_appearance_placement_right")
        await("右侧封面草稿已生效") { model(ids.first()).thingCardAppearance.imagePlacement == Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT }
        idle()
        event("panel-new-width-placement-generating", 1500)
        tapUi("tv_thing_card_appearance_source")
        tapUi(contains = b.file.name)
        await("面板已选 B") { model(ids.first()).thingCardAppearance.mediaSourceKey?.contains(b.file.name) == true }
        event("panel-B-selected", 1500)
        finish(a); event("panel-A-completed-B-retained", 1800)
        // 面板草稿也观察真实状态；最终状态通过保存后的首页再次检查。
        b.gate.begin.countDown(); check(b.gate.halfway.await(30, TimeUnit.SECONDS))
        event("panel-B-generating", 1800)
        tapUi("tv_thing_card_appearance_source")
        tapUi(contains = still.name)
        await("面板已选静态 S") { model(ids.first()).thingCardAppearance.mediaSourceKey?.contains(still.name) == true }
        event("panel-static-selected", 1500)
        finish(b); event("panel-B-completed-static-retained", 1800)
        tapUi("bt_confirm_thing_card_appearance")
        await("确认后面板与选择模式退出") {
            field(activity, "mThingCardAppearanceDraft") == null && mode.getCurrentMode() == ModeManager.NORMAL
        }
        idle(); top()
        main { record("面板保存静态来源与右侧宽卡布局", model(ids.first()).thingCardAppearance.let {
            it.mediaSourceKey?.contains(still.name) == true && it.spanMode == Thing.THING_CARD_SPAN_FULL &&
                it.imagePlacement == Thing.THING_CARD_IMAGE_PLACEMENT_RIGHT
        } && holder(ids.first())!!.ivImageAttachment!!.drawable !is GifDrawable) }
        event("panel-saved-static-new-appearance", 2000)
    }
}
