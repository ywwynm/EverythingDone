package com.ywwynm.everythingdone.detail

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.activities.DoingActivity
import com.ywwynm.everythingdone.activities.StartDoingActivity
import com.ywwynm.everythingdone.activities.NoticeableNotificationActivity
import com.ywwynm.everythingdone.activities.ThingsActivity
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.DetailAttachmentPlaybackController
import com.ywwynm.everythingdone.helpers.DetailViewPreparation
import android.content.ContextWrapper
import com.ywwynm.everythingdone.helpers.ThingPrivacyResolver
import com.ywwynm.everythingdone.helpers.ThingDoingHelper
import com.ywwynm.everythingdone.helpers.VideoCoverPreviewManager
import com.ywwynm.everythingdone.fragments.ChooserDialogFragment
import com.ywwynm.everythingdone.model.DoingRecord
import com.ywwynm.everythingdone.services.DoingService
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.interaction.UiInteractions
import com.ywwynm.everythingdone.interaction.UiTransitionState
import com.ywwynm.everythingdone.model.DetailAutoplayMode
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingCardAppearance
import com.ywwynm.everythingdone.model.ThingListProjection
import com.ywwynm.everythingdone.model.ThingWidgetInfo
import com.ywwynm.everythingdone.views.ThingDetailTransition
import com.ywwynm.everythingdone.views.ThingDetailReturnTransition
import com.ywwynm.everythingdone.views.particledismiss.ParticleDismissController
import com.ywwynm.everythingdone.database.ThingFolderDAO
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingFolderCardPresentation
import com.ywwynm.everythingdone.utils.ThingAnimationPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * 仅 debug。真实首页卡片点击 → 真实详情，旁观窗口帧及生命周期。
 * 已有记录只读；写入和清理均限本次创建的 id + create_time，不更改认证设置。
 */
class ThingDetailTransitionProbe : Instrumentation() {
    private var args = Bundle()
    private lateinit var output: File
    private lateinit var home: ThingsActivity
    private lateinit var list: RecyclerView
    private lateinit var manager: ThingManager
    private lateinit var automation: UiAutomation
    private var detail: DetailActivity? = null
    private var resumed: Activity? = null
    private var baseRotation = 0
    private var baseOrientation = Configuration.ORIENTATION_PORTRAIT
    private var originalProjection: ThingListProjection? = null
    private val created = mutableListOf<Long>()
    private val createdFolders = mutableListOf<Long>()
    private val fixtureTime = System.currentTimeMillis()
    private val rowsBefore = linkedMapOf<Long, String>()
    private val samples = JSONArray()
    private var failures = 0
    @Volatile private var label = ""
    private var expectFrames = false
    private var frameTimes = mutableListOf<Long>()
    private var progressValues = mutableListOf<Float>()
    private var matches = 0
    private var mediaMatched = false
    private var enterToolbarDark: Boolean? = null
    private var detailCreates = 0
    private var detailCreatedAt = 0L
    private var detailResumedAt = 0L
    private var captureStage = 0
    private var sourceLightStatusBars = false
    private var statusBarHandoffCorrect = true
    private val writer = Executors.newSingleThreadExecutor()
    private val stackSamples = ConcurrentLinkedQueue<JSONObject>()
    private var sampler: java.util.concurrent.ScheduledExecutorService? = null
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity is DetailActivity) {
                detail = activity
                detailCreates++
                detailCreatedAt = SystemClock.elapsedRealtime()
                setField(activity, "dontSaveAfterOnPause", true)
            }
        }
        override fun onActivityResumed(activity: Activity) {
            resumed = activity
            if (activity is ThingsActivity && ::home.isInitialized && activity !== home) {
                home = activity
                list = field(activity, "mRecyclerView")
            }
            if (activity !is DetailActivity) return
            detailResumedAt = SystemClock.elapsedRealtime()
            setField(activity, "dontSaveAfterOnPause", true)
            val decor = activity.window.decorView
            decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    val transition = field<ThingDetailTransition?>(activity, "detailTransition")
                    if (transition?.isRunning == true) {
                        frameTimes += SystemClock.elapsedRealtimeNanos()
                        progressValues += transition.progress
                        matches = maxOf(matches, transition.matchedElementCount)
                        mediaMatched = mediaMatched || transition.hasMatchedMedia
                        if (transition.progress == 0f) {
                            statusBarHandoffCorrect = statusBarHandoffCorrect &&
                                WindowInsetsControllerCompat(activity.window, decor).isAppearanceLightStatusBars == sourceLightStatusBars
                        }
                        if (label == "image-1" && transition.progress > .9f) {
                            enterToolbarDark = field(field<Any>(activity, "mToolbarContrast"), "toolbarDark")
                        }
                        if (expectFrames && captureStage < 3 &&
                            transition.progress >= listOf(0f, .35f, .8f)[captureStage]) {
                            if (captureStage == 2 && label == "image-1") endpointPixels(transition, activity)
                            capture(activity, "$label-frame-${captureStage++}")
                        }
                    } else decor.post {
                        if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(this)
                    }
                }
            })
        }
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /** 真实 Activity 栈与正式通知 PendingIntent；写入、计时和清理只针对本轮夹具。 */
    private fun navigation() {
        fun finishDetail(page: DetailActivity) = page.javaClass.getDeclaredMethod("finishCommittedReturn")
            .apply { isAccessible = true }.invoke(page)
        fun noReturn(name: String, target: Activity, action: () -> Unit) {
            val stages = mutableListOf<String>()
            main { ThingDetailReturnTransition.diagnosticObserver = { stages += it }; action() }
            await("$name 返回目标") { target.hasWindowFocus() }
            await("$name 输入释放") { field<Any?>(home, "returnPresentation") == null && inputReady(target) }
            main { ThingDetailReturnTransition.diagnosticObserver = null }
            checkResult(name, stages.isEmpty() && main { !list.isLayoutSuppressed },
                JSONObject().put("stages", JSONArray(stages)).put("target", target.javaClass.simpleName))
        }
        fun openExternal(note: Thing, intent: Intent) {
            val before = detailCreates
            main { resumed!!.startActivity(intent) }
            await("外部入口详情显示") { detailCreates == before + 1 && resumed is DetailActivity && detail?.currentThingId() == note.id &&
                detail?.hasWindowFocus() == true }
            await("外部入口布局") { detail!!.findViewById<View>(R.id.fl_root_detail).isLaidOut }
        }
        val note = fixture("导航来源模拟", content = "用于开始做、通知和页面跳转的独立测试记录。")
        val other = fixture("导航目标模拟")
        runCase("nav-nested-open", note, keepOpen = true)
        val parent = detail!!
        openExternal(other, DetailActivity.getOpenIntentForUpdate(parent, null, other.id, -1))
        val child = detail!!
        noReturn("nav-other-detail-back-to-parent", parent) { finishDetail(child) }
        detail = parent
        returnCase("nav-parent-back-to-list")

        runCase("nav-copied-open", note, keepOpen = true)
        val original = detail!!
        // 保存过的返回 Intent 可被其他入口重用，同一记事也不能复用另一个窗口的动画资格。
        openExternal(note, Intent(original.intent))
        val copy = detail!!
        noReturn("nav-copied-intent-back-to-parent", original) { finishDetail(copy) }
        detail = original
        returnCase("nav-original-after-copied-intent")

        runCase("nav-other-page-open", note, keepOpen = true)
        val beforePage = detail!!
        main { beforePage.startActivity(Intent(beforePage, com.ywwynm.everythingdone.activities.SettingsActivity::class.java)) }
        await("设置页显示") { resumed is com.ywwynm.everythingdone.activities.SettingsActivity && resumed!!.hasWindowFocus() }
        val settings = resumed!!
        noReturn("nav-settings-back-to-detail", beforePage) { settings.finish() }
        detail = beforePage
        returnCase("nav-detail-after-settings")

        // 直接触发正式通知生成的 contentIntent，保留认证中转，不创建系统提醒或改动闹钟。
        val notification = main { com.ywwynm.everythingdone.utils.SystemNotificationUtil
            .newGeneralNotificationBuilder(home, com.ywwynm.everythingdone.receivers.ReminderReceiver.TAG,
                note.id, -1, note, false).build() }
        main { notification.contentIntent.send() }
        await("普通通知进入详情") { resumed is DetailActivity && detail?.currentThingId() == note.id &&
            detail?.hasWindowFocus() == true }
        noReturn("nav-notification-back-to-list", home) { finishDetail(detail!!) }
        detail = null
        notification.contentIntent.cancel()

        main { home.startActivity(NoticeableNotificationActivity.getOpenIntentForReminder(home, note.id, -1)) }
        await("突出通知显示") { resumed is NoticeableNotificationActivity &&
            field<RecyclerView?>(resumed!!, "mRvThing")?.let { it.hasWindowFocus() && it.childCount > 0 } == true }
        val notice = resumed!!
        main { descendants(field<RecyclerView>(notice, "mRvThing"))
            .first { it.id == R.id.cv_thing && it.isShown }.performClick() }
        await("突出通知进入详情") { resumed is DetailActivity && detail?.currentThingId() == note.id &&
            detail?.hasWindowFocus() == true }
        noReturn("nav-noticeable-back-to-list", home) { finishDetail(detail!!) }
        detail = null

        // 新 intent 替换当前入口时，原入口资格也必须释放。
        runCase("nav-new-intent-open", note, keepOpen = true)
        val reused = detail!!
        val previousIntent = reused.intent
        main { reused.startActivity(DetailActivity.getOpenIntentForUpdate(reused, null, note.id, -1)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        await("同窗口新入口完成") { reused.hasWindowFocus() && reused.intent !== previousIntent }
        noReturn("nav-new-intent-no-old-return", home) { finishDetail(reused) }
        detail = null
        checkResult("nav-new-intent-released-origin",
            main { listOf("origins", "detailOrigins").all { name ->
                (ThingDetailReturnTransition::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.get(null) as Map<*, *>).isEmpty() } })

        check(App.getDoingThingId() == -1L) { "设备有正在做的记事，停止计时测试以保护原任务" }
        runCase("nav-start-doing-open", note, keepOpen = true)
        val beforeDoing = detail!!
        main { beforeDoing.findViewById<View>(R.id.fl_start_doing_as_bt).performClick() }
        await("预计用时对话框") { resumed is StartDoingActivity }
        val start = resumed as StartDoingActivity
        await("开始做确认按钮") { start.supportFragmentManager.fragments.filterIsInstance<ChooserDialogFragment>()
            .firstOrNull()?.dialog?.findViewById<View>(R.id.tv_confirm_as_bt_fragment_chooser)?.isShown == true }
        main { start.supportFragmentManager.fragments.filterIsInstance<ChooserDialogFragment>().first()
            .dialog!!.findViewById<View>(R.id.tv_confirm_as_bt_fragment_chooser).performClick() }
        await("正在做页面") { resumed is DoingActivity && resumed!!.hasWindowFocus() && App.getDoingThingId() == note.id }
        checkResult("nav-doing-chooser-removed-before-back", main {
            (start.isFinishing || start.isDestroyed) &&
                com.ywwynm.everythingdone.views.particledismiss.ParticleDismissOverlay.activeIn(start).isEmpty()
        })
        await("原详情已退出") { beforeDoing.isDestroyed }
        detail = null
        val doing = resumed as DoingActivity
        await("正在做页面完成入场") { field<Thing?>(doing, "mThing")?.id == note.id &&
            field<RecyclerView?>(doing, "mRecyclerView")?.let { it.childCount > 0 && it.alpha >= .839f } == true }
        checkResult("nav-doing-visible", main { doing.findViewById<View>(R.id.clock_time_doing).alpha > 0f })
        screenshot("nav-doing-current")
        noReturn("nav-doing-back-to-list", home) { doing.onBackPressedDispatcher.onBackPressed() }
        await("正在做窗口已移除") { doing.isDestroyed && !doing.window.decorView.isAttachedToWindow &&
            list.itemAnimator?.isRunning != true && !list.isLayoutRequested }
        checkResult("nav-doing-continues-after-back", App.getDoingThingId() == note.id)
        screenshot("nav-doing-list")
        val position = main { manager.getListPositionForThingId(note.id) }
        main { (list.layoutManager as StaggeredGridLayoutManager).scrollToPositionWithOffset(position, 120) }
        await("正在做卡片显示") { list.findViewHolderForAdapterPosition(position)?.itemView
            ?.getTag(R.id.tag_thing_card_bound_stable_id) == note.id && !list.isLayoutRequested &&
            list.itemAnimator?.isRunning != true && inputReady(home) }
        val enterStages = mutableListOf<String>()
        main {
            ThingDetailTransition.diagnosticObserver = { enterStages += it }
            list.findViewHolderForAdapterPosition(position)!!.itemView.findViewById<View>(R.id.cv_thing).performClick()
        }
        await("列表重开正在做") { resumed is DoingActivity && resumed!!.hasWindowFocus() }
        checkResult("nav-doing-not-detail-transition", main {
            resumed!!.intent.getLongExtra(ThingDetailTransition.EXTRA_TOKEN, 0) == 0L &&
                !resumed!!.intent.hasExtra("thing_detail_return_origin") &&
                "captured" !in enterStages
        }, JSONObject().put("stages", JSONArray(enterStages)))
        main { ThingDetailTransition.diagnosticObserver = null }
        val reopened = resumed as DoingActivity
        await("重开正在做完成入场") { field<RecyclerView?>(reopened, "mRecyclerView")
            ?.let { it.childCount > 0 && it.alpha >= .839f } == true }
        noReturn("nav-reopened-doing-back-to-list", home) { reopened.onBackPressedDispatcher.onBackPressed() }
        await("重开正在做窗口已移除") { reopened.isDestroyed && !reopened.window.decorView.isAttachedToWindow }
        main { ThingDoingHelper.stopDoing(home, DoingRecord.STOP_REASON_CANCEL_USER) }
        await("测试计时停止") { App.getDoingThingId() == -1L }
        SystemClock.sleep(200)
    }

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }

    override fun onStart() {
        val run = args.getString("run_id", "transition")
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        output = File(targetContext.getExternalFilesDir(null), "detail-transition/$run").apply { mkdirs() }
        automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        if (args.getString("phase") in setOf("target-inspect", "target-readonly")) {
            targetReadOnly()
            return
        }
        val app = targetContext.applicationContext as App
        val result = JSONObject()
        val countPreferences = targetContext.getSharedPreferences(Def.Meta.THINGS_COUNTS_NAME, Context.MODE_PRIVATE)
        var originalCounts = countPreferences.all.mapValues { it.value as Int }
        try {
            if (args.getString("recover", "false") == "true") {
                result.put("recovered_fixtures", recoverFixtures())
                originalCounts = countPreferences.all.mapValues { it.value as Int }
            }
            snapshotRows().forEach { (id, hash) -> rowsBefore[id] = hash }
            if (args.getString("phase") == "cleanup") return
            app.registerActivityLifecycleCallbacks(callbacks)
            main { targetContext.startActivity(Intent(targetContext, ThingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            await("首页恢复到前台") { resumed is ThingsActivity && resumed!!.hasWindowFocus() }
            home = resumed as ThingsActivity
            manager = ThingManager.getInstance(targetContext)!!
            list = field(home, "mRecyclerView")
            originalProjection = main { manager.getProjection() }
            await("首页显示") { home.hasWindowFocus() && list.isLaidOut && list.layoutManager != null && list.adapter != null }
            baseRotation = main { home.window.decorView.display.rotation }
            baseOrientation = main { home.resources.configuration.orientation }
            if (args.getString("orientation") == "landscape") {
                check(baseOrientation == Configuration.ORIENTATION_LANDSCAPE) { "测试窗口未进入横屏" }
            }
            if (args.getString("phase") == "appearance-parsing") {
                appearanceParsing()
            } else if (args.getString("phase") == "appearance-cost") {
                appearanceCost()
            } else if (args.getString("phase") == "appearance-return") {
                if (args.getString("landscape") == "true") {
                    val rotationBefore = baseRotation
                    try {
                        automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
                        await("外观回归横屏布局") { home.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && !list.isLayoutRequested }
                        baseRotation = main { home.window.decorView.display.rotation }
                        baseOrientation = Configuration.ORIENTATION_LANDSCAPE
                        appearanceReturn()
                    } finally {
                        automation.setRotation(rotationBefore)
                        await("外观回归恢复方向") { home.window.decorView.display.rotation == rotationBefore }
                    }
                } else appearanceReturn()
            } else if (args.getString("phase") == "widget-return") {
                widgetReturns()
            } else if (args.getString("phase") == "quick-return") {
                if (args.getString("sample") == "true") {
                    sampler = Executors.newSingleThreadScheduledExecutor().apply {
                        scheduleAtFixedRate({
                            stackSamples += JSONObject().put("time_ns", SystemClock.elapsedRealtimeNanos())
                                .put("case", label).put("stack", Looper.getMainLooper().thread.stackTrace.joinToString("\n"))
                        }, 0, 8, TimeUnit.MILLISECONDS)
                    }
                }
                val note = fixture("快速返回专用模拟", content = "当前展开画面直接收回。")
                val rotationBefore = baseRotation
                try {
                    if (args.getString("landscape") == "true") {
                        automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
                        await("快速返回横屏布局") { home.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && !list.isLayoutRequested }
                        baseRotation = main { home.window.decorView.display.rotation }
                        baseOrientation = Configuration.ORIENTATION_LANDSCAPE
                    }
                    for (point in listOf(0f, .3f, .8f)) for (toolbar in listOf(false, true)) {
                        runCase("quick-$point-$toolbar", note, quickReturn = point, quickToolbar = toolbar)
                    }
                } finally {
                    automation.setRotation(rotationBefore)
                    await("快速返回恢复方向") { home.window.decorView.display.rotation == rotationBefore }
                }
            } else if (args.getString("phase") == "navigation") {
                navigation()
            } else if (args.getString("phase") == "window-change") {
                windowChanges()
            } else if (args.getString("phase") == "window-external") {
                externalWindowChanges()
            } else if (args.getString("phase") == "review-private-repeat") {
                repeat(2) { reviewPrivatePendingSave() }
            } else if (args.getString("phase") == "review") {
                reviewPendingSave()
                reviewInterleavedWrite()
                reviewPrivatePendingSave()
            } else if (args.getString("phase") == "review-create") {
                reviewConcurrentCreation()
            } else if (args.getString("phase") == "return-ghosting") {
                val note = fixture("返回残影检查", content = "当前详情收回到当前真实卡片。")
                repeat(2) {
                    runCase("ghost-short-$it-open", note, keepOpen = true)
                    returnCase("ghost-short-$it", visual = true)
                }
                val long = fixture("检查标题换行后的收回效果", content =
                    List(12) { "正文第 $it 行，检查不同宽度下的文字换行。" }.joinToString("\n"))
                runCase("ghost-long-open", long, keepOpen = true)
                returnCase("ghost-long", visual = true)
            } else if (args.getString("phase") == "return") {
                returnScenarios()
            } else if (args.getString("phase") == "return-perf") {
                val note = fixture("返回性能模拟", content = "关闭截图采集，检查真实运动帧间隔。")
                repeat(8) {
                    runCase("return-perf-open-$it", note, touch = true, keepOpen = true)
                    returnCase("return-perf-$it")
                }
            } else if (args.getString("phase") in setOf("video-return-perf", "video-return-visual")) {
                videoReturnPerformance()
            } else if (args.getString("phase") in setOf("source-return-perf", "source-return-visual")) {
                sourceReturnPerformance()
            } else if (args.getString("phase") == "return-drawer") {
                returnDrawerScenarios()
            } else if (args.getString("phase") == "compatibility") {
                compatibility()
            } else if (args.getString("phase") == "preparation") {
                preparation()
            } else if (args.getString("phase") == "perf") {
                if (args.getString("sample", "false") == "true") {
                    sampler = Executors.newSingleThreadScheduledExecutor().apply {
                        scheduleAtFixedRate({
                            stackSamples += JSONObject().put("time_ns", SystemClock.elapsedRealtimeNanos())
                                .put("case", label).put("stack", Looper.getMainLooper().thread.stackTrace.joinToString("\n"))
                        }, 0, 8, TimeUnit.MILLISECONDS)
                    }
                }
                val note = fixture("冷启动性能模拟", content = "实际窗口内的卡片展开，关闭图像采集。")
                repeat(6) { runCase("perf-$it", note, touch = it >= 3) }
            } else if (args.getString("fixtures", "true") == "true") fixtures()
            if (args.getString("existing", "true") == "true") {
                val db = DBHelper(targetContext).readableDatabase
                val notes = db.rawQuery("SELECT * FROM things WHERE type<>-1 ORDER BY state,folder_id,id", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(Thing(cursor)) }
                }
                val skipped = JSONArray()
                val onlyIds = args.getString("only_ids")?.split(",")?.map(String::toLong)?.toSet()
                notes.filter { it.id !in created && (onlyIds == null || it.id in onlyIds) }.forEach { thing ->
                    if (thing.id == App.getDoingThingId() || ThingPrivacyResolver.isEffectivelyPrivate(targetContext, thing)) {
                        skipped.put(thing.id)
                    } else runCase("existing-${thing.id}", thing, visual = false)
                }
                result.put("skipped_private_or_doing_ids", skipped)
            }
        } catch (error: Throwable) {
            failures++
            result.put("error", error.stackTraceToString())
        } finally {
            if (App.getDoingThingId() in created) {
                main { ThingDoingHelper.stopDoing(targetContext, DoingRecord.STOP_REASON_CANCEL_USER) }
                await("仅停止本次测试计时") { App.getDoingThingId() == -1L }
                SystemClock.sleep(200)
            }
            runCatching { close() }.onFailure { result.put("close_error", it.stackTraceToString()) }
            if (::manager.isInitialized) main {
                created.forEach(manager::clearThingPrivacyAuthenticated)
                if (App.isSearching && ::home.isInitialized) home.javaClass
                    .getDeclaredMethod("toggleSearching", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(home, false)
                originalProjection?.let {
                    manager.navigateToFolderPathIndex(-1, false)
                    it.currentFolderId?.let { id -> manager.openFolderPath(id, false, false) }
                    manager.setTypeFilterMask(it.typeFilterMask, false)
                    app.setStatus(it.status, true)
                }
            }
            val db = DBHelper(targetContext).writableDatabase
            var removed = 0
            created.forEach { id -> db.delete("reminders",
                "id IN (SELECT id FROM things WHERE id=? AND create_time=?)",
                arrayOf(id.toString(), fixtureTime.toString())) }
            created.forEach { id -> removed += db.delete("things", "id=? AND create_time=?",
                arrayOf(id.toString(), fixtureTime.toString())) }
            created.forEach { id -> db.delete("doing_records", "thing_id=? AND start_time>=?",
                arrayOf(id.toString(), fixtureTime.toString())) }
            createdFolders.forEach { id -> db.delete("thing_folders", "id=? AND create_time=?",
                arrayOf(id.toString(), fixtureTime.toString())) }
            if (::manager.isInitialized && ::list.isInitialized) main {
                manager.loadThings()
                list.adapter?.notifyDataSetChanged()
            }
            val after = snapshotRows()
            val changed = rowsBefore.filter { (id, hash) -> after[id] != hash }.keys
            val added = after.keys - rowsBefore.keys
            // 累计数量不能简单按当前行数重算；它可能包含历史已永久删除的记事。
            // 原数据一致时恢复测试前的统计缓存，避免临时夹具进入累计完成率。
            if (changed.isEmpty() && added.isEmpty()) {
                countPreferences.edit().clear().apply {
                    originalCounts.forEach { (key, value) -> putInt(key, value) }
                }.commit()
            }
            checkResult("original-database-unchanged", changed.isEmpty() && added.isEmpty(),
                JSONObject().put("changed_ids", JSONArray(changed)).put("unexpected_ids", JSONArray(added)))
            checkResult("original-count-cache-unchanged", countPreferences.all == originalCounts)
            app.unregisterActivityLifecycleCallbacks(callbacks)
            main { ThingDetailTransition.diagnosticObserver = null }
            main { ThingDetailReturnTransition.diagnosticObserver = null }
            sampler?.shutdownNow()
            if (stackSamples.isNotEmpty()) File(output, "main-stacks.json").writeText(JSONArray(stackSamples.toList()).toString())
            writer.shutdown()
            writer.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
            result.put("failures", failures).put("samples", samples).put("original_rows", rowsBefore.size)
                .put("fixtures_created", created.size).put("fixtures_removed", removed)
            File(output, "result.json").writeText(result.toString(2))
            finish(if (failures == 0) 0 else 1, Bundle().apply { putString("result", "$output/result.json failures=$failures") })
        }
    }

    /** 只读比较真实数据的外观结果及完整 Cursor 构建成本，不导出正文和外观 JSON。 */
    private fun appearanceParsing() {
        var expected: String? = null
        repeat(6) { round ->
            val submitted = SystemClock.elapsedRealtimeNanos()
            var queueMs = 0.0
            val (notes, elapsed) = main {
                val start = SystemClock.elapsedRealtimeNanos()
                queueMs = (start - submitted) / 1_000_000.0
                val loaded = DBHelper(targetContext).readableDatabase.rawQuery("SELECT * FROM things WHERE type>=0 ORDER BY id", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(Thing(cursor)) }
                }
                loaded to (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            }
            val digest = MessageDigest.getInstance("SHA-256")
            notes.forEach { note ->
                listOf(note.id.toString(), note.getBackground()?.toJson().orEmpty(),
                    note.thingCardAppearance.toJson(), note.detailAttachmentMediaAppearance.toJson()).forEach { value ->
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
                    digest.update(0.toByte()); digest.update(bytes)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (expected == null) expected = hash
            checkResult("appearance-read-$round", hash == expected,
                JSONObject().put("read_ms", elapsed).put("queue_ms", queueMs)
                    .put("rows", notes.size).put("semantic_digest", hash))
        }
    }

    /** 同一主线程、同一批只读 JSON，对照原解析器与缓存入口；计时不含排队和断言。 */
    @Suppress("UNCHECKED_CAST")
    private fun appearanceCost() {
        val columns = listOf(Def.Database.COLUMN_BACKGROUND_THINGS,
            Def.Database.COLUMN_THING_CARD_APPEARANCE_THINGS,
            Def.Database.COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS)
        val rows = DBHelper(targetContext).readableDatabase.rawQuery(
            "SELECT ${columns.joinToString(",")} FROM things WHERE type>=0 ORDER BY id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(columns.indices.map { cursor.getString(it) }) }
        }
        val caches = listOf(ThingBackground::class.java, ThingCardAppearance::class.java,
            DetailAttachmentMediaAppearance::class.java).map { type ->
            type.getDeclaredField("parsedJson").apply { isAccessible = true }.get(null)
        }
        // 仅调试探针读取已有解析函数，不给正式代码添加旁路开关。
        val uncached = caches.map { cache ->
            val decoder = cache.javaClass.getDeclaredField("decode").apply { isAccessible = true }
                .get(cache) as (String) -> Any?
            { value: String? -> if (value.isNullOrEmpty()) null else decoder(value) }
        }
        val cached: List<(String?) -> Any?> = listOf(ThingBackground::fromJson,
            ThingCardAppearance::fromJson, DetailAttachmentMediaAppearance::fromJson)
        main { rows.forEach { row -> columns.indices.forEach { cached[it](row[it]) } } }
        val capacity = JSONArray()
        caches.forEachIndexed { index, cache ->
            val entries = cache.javaClass.getDeclaredField("entries").apply { isAccessible = true }
                .get(cache) as Map<String, *>
            val unique = rows.mapNotNull { it[index] }.filter(String::isNotEmpty).toSet()
            synchronized(entries) {
                capacity.put(JSONObject().put("column", columns[index]).put("unique_inputs", unique.size)
                    .put("covered_inputs", unique.count(entries::containsKey)).put("entries", entries.size)
                    .put("retained_chars", entries.keys.sumOf(String::length)))
            }
        }
        checkResult("appearance-cache-capacity", capacity.length() == 3,
            JSONObject().put("rows", rows.size).put("capacity", capacity))
        repeat(8) { round ->
            val results = mutableMapOf<String, List<Any?>>()
            val times = JSONObject()
            val strategies = if (round % 2 == 0) listOf("uncached", "cached") else listOf("cached", "uncached")
            strategies.forEach { strategy ->
                val parse = if (strategy == "cached") cached else uncached
                // 模拟 Cursor 再次读取的新 String，避免只测试同一对象的预计算哈希。
                val input = rows.map { row -> row.map { it?.let { text -> String(text.toCharArray()) } } }
                val (values, elapsed) = main {
                    val start = SystemClock.elapsedRealtimeNanos()
                    val values = ArrayList<Any?>(rows.size * columns.size)
                    input.forEach { row -> columns.indices.forEach { values.add(parse[it](row[it])) } }
                    values to (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                }
                results[strategy] = values
                times.put("${strategy}_ms", elapsed)
            }
            checkResult("appearance-cost-$round", results["cached"] == results["uncached"], times)
        }
    }

    /** 只改模拟记事的外观列，故意保持 ID 和更新时间不变，验证缓存按真实内容识别变化。 */
    private fun appearanceReturn() {
        val image = File(output, "appearance.png")
        Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).drawColor(Color.CYAN)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val key = "0${image.absolutePath}"
        val initial = ThingCardAppearance(sources = mapOf(key to ThingCardAppearance.SourceAppearance(fileSize = image.length())))
        val note = fixture("外观更新模拟", media = listOf(key), appearance = initial)
        val sibling = fixture("外观隔离模拟", media = listOf(key), appearance = initial)
        runCase("appearance-old-open", note, keepOpen = true)
        val card = initial.withSpanMode(Thing.THING_CARD_SPAN_FULL).withImagePlacement(Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM)
        val detailAppearance = DetailAttachmentMediaAppearance(sources = mapOf(key to
            DetailAttachmentMediaAppearance.SourceAppearance(fullSpanEnabled = true).withPresentation("fullSpan",
                DetailAttachmentMediaAppearance.MediaPresentationAppearance(2.0,
                    DetailAttachmentMediaAppearance.DetailMediaCrop(.2, .7, 1.5)))))
        val background = ThingBackground.gradient(Color.BLUE, Color.GREEN, ThingBackground.Orientation.LB_RT)
        main {
            val values = ContentValues().apply {
                put(Def.Database.COLUMN_BACKGROUND_THINGS, background.toJson())
                put(Def.Database.COLUMN_THING_CARD_APPEARANCE_THINGS, card.toJson())
                put(Def.Database.COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS, detailAppearance.toJson())
            }
            check(note.id in created)
            check(DBHelper(targetContext).writableDatabase.update("things", values, "id=? AND create_time=?",
                arrayOf(note.id.toString(), fixtureTime.toString())) == 1)
            App.setJustNotifyAll(true)
        }
        returnCase("appearance-changed-return", visual = true)
        val fresh = main { manager.getThingById(note.id)!! }
        checkResult("appearance-latest-with-same-timestamp", fresh.updateTime == note.updateTime &&
            fresh.getBackground() == background && fresh.thingCardAppearance == card &&
            fresh.detailAttachmentMediaAppearance == detailAppearance)
        checkResult("appearance-other-note-isolated", main { manager.getThingById(sibling.id)!!.thingCardAppearance == initial })
        runCase("appearance-new-open", fresh, keepOpen = true, visual = true)
        checkResult("appearance-detail-latest", main {
            field<Thing>(detail!!, "mThing").detailAttachmentMediaAppearance == detailAppearance })
        returnCase("appearance-new-return", visual = true)
    }

    /** 功能复核：只延迟本次模拟记录的保存，确认取消动画不能把旧数据库值重新发布。 */
    private fun reviewPendingSave() {
        for (cancel in listOf(false, true)) {
            val case = if (cancel) "review-paused-save" else "review-timeout-save"
            val oldContent = "$case：修改前"
            val savedContent = "$case：已保存的新内容"
            val note = fixture(case, content = oldContent)
            runCase("$case-open", note, keepOpen = true)
            val page = detail!!
            val executor = field<java.util.concurrent.ExecutorService>(manager, "mExecutor")
            val gate = java.util.concurrent.CountDownLatch(1)
            val queued = java.util.concurrent.CountDownLatch(1)
            executor.execute { queued.countDown(); gate.await(12, TimeUnit.SECONDS) }
            check(queued.await(2, TimeUnit.SECONDS))
            var transition: ThingDetailReturnTransition? = null
            try {
                main {
                    check(field<Thing>(page, "mThing").id in created)
                    page.findViewById<TextView>(R.id.et_content).text = savedContent
                    page.onBackPressedDispatcher.onBackPressed()
                }
                await("返回等待保存") {
                    transition = field(home, "detailReturnTransition")
                    home.hasWindowFocus() && transition != null
                }
                if (cancel) main { home.moveTaskToBack(true) }
                await("返回动画超时或取消") { transition?.isRunning == false }
            } finally {
                gate.countDown()
                val drained = java.util.concurrent.CountDownLatch(1)
                executor.execute { drained.countDown() }
                check(drained.await(10, TimeUnit.SECONDS))
            }
            if (cancel) {
                targetContext.startActivity(Intent(targetContext, ThingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            await("保存后的列表交接完成") {
                home.hasWindowFocus() && field<Any?>(home, "returnPresentation") == null &&
                    !list.hasPendingAdapterUpdates() && !list.isLayoutRequested && list.itemAnimator?.isRunning != true
            }
            val stored = DBHelper(targetContext).readableDatabase.rawQuery(
                "SELECT content FROM things WHERE id=?", arrayOf(note.id.toString())).use {
                check(it.moveToFirst()); it.getString(0)
            }
            checkResult("$case-current-content", main { manager.getThingById(note.id)?.content == savedContent } &&
                stored == savedContent, JSONObject().put("database_saved", stored == savedContent)
                .put("manager_saved", main { manager.getThingById(note.id)?.content == savedContent })
                .put("reason", transition?.finishReason).put("input_released", main { inputReady(home) }))
            val card = main {
                list.findViewHolderForAdapterPosition(manager.getListPositionForThingId(note.id))
                    ?.itemView?.findViewById<View>(R.id.cv_thing)
            }
            check(card != null)
            main { card.performClick() }
            await("重新打开刚保存的模拟记录") {
                detail !== page && detail?.hasWindowFocus() == true &&
                    field<ThingDetailTransition?>(detail!!, "detailTransition")?.isRunning != true
            }
            checkResult("$case-reopened-content", main {
                field<Thing>(detail!!, "mThing").id == note.id &&
                    detail!!.findViewById<TextView>(R.id.et_content).text.toString() == savedContent
            })
            close()
        }
    }

    /** 写入完成已通知主线程，但主线程处理前又收到另一笔更新。 */
    private fun reviewInterleavedWrite() {
        val note = fixture("保存完成回调交错", content = "更新前")
        val updatedContent = "主线程收到完成回调之前到来的新内容"
        runCase("review-interleaved-open", note, keepOpen = true)
        val executor = field<java.util.concurrent.ExecutorService>(manager, "mExecutor")
        val initialGate = java.util.concurrent.CountDownLatch(1)
        val nextGate = java.util.concurrent.CountDownLatch(1)
        executor.execute { initialGate.await(12, TimeUnit.SECONDS) }
        var transition: ThingDetailReturnTransition? = null
        try {
            main {
                detail!!.javaClass.getDeclaredMethod("finishCommittedReturn")
                    .apply { isAccessible = true }.invoke(detail!!)
            }
            await("交错场景返回等待") {
                transition = field(home, "detailReturnTransition")
                home.hasWindowFocus() && transition != null
            }
            val posted = java.util.concurrent.CountDownLatch(1)
            executor.execute { posted.countDown() }
            main {
                initialGate.countDown()
                check(posted.await(2, TimeUnit.SECONDS))
                executor.execute { nextGate.await(12, TimeUnit.SECONDS) }
                val updated = manager.getThingById(note.id)!!
                check(updated.id in created)
                updated.content = updatedContent
                val position = manager.getPosition(note.id)
                manager.update(updated.type, updated, position, true)
                val result = Intent().putExtra(Def.Communication.KEY_THING, updated)
                    .putExtra(Def.Communication.KEY_TYPE_BEFORE, updated.type)
                    .putExtra(Def.Communication.KEY_POSITION, position)
                    .putExtra(Def.Communication.KEY_LIST_POSITION, manager.getListPositionForThingId(note.id))
                    .putExtra(Def.Communication.KEY_LIST_PROJECTION, manager.getProjection().key())
                home.javaClass.getDeclaredMethod("updateMainUi", Intent::class.java, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(home, result, Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME)
            }
            writer.execute { SystemClock.sleep(360); nextGate.countDown() }
            await("交错场景动画结束") { transition?.isRunning == false }
        } finally {
            initialGate.countDown()
            nextGate.countDown()
            val drained = java.util.concurrent.CountDownLatch(1)
            executor.execute { drained.countDown() }
            check(drained.await(10, TimeUnit.SECONDS))
        }
        await("交错场景展示提交") {
            field<Any?>(home, "returnPresentation") == null && !list.hasPendingAdapterUpdates() &&
                !list.isLayoutRequested && list.itemAnimator?.isRunning != true
        }
        val saved = DBHelper(targetContext).readableDatabase.rawQuery(
            "SELECT content FROM things WHERE id=?", arrayOf(note.id.toString())).use {
            it.moveToFirst() && it.getString(0) == updatedContent
        }
        checkResult("review-interleaved-current-content", saved && main { manager.getThingById(note.id)?.content == updatedContent },
            JSONObject().put("database_saved", saved).put("manager_saved", main {
                manager.getThingById(note.id)?.content == updatedContent
            }).put("reason", transition?.finishReason))
        close()
    }

    private fun reviewPrivatePendingSave() {
        val secretTitle = "保存等待期间的私密测试"
        val secretContent = "仅用于测试的私密正文"
        val note = fixture(Thing.PRIVATE_THING_PREFIX + secretTitle, content = secretContent)
        main { manager.markThingPrivacyAuthenticated(note.id) }
        runCase("review-private-pending-open", note, keepOpen = true)
        val executor = field<java.util.concurrent.ExecutorService>(manager, "mExecutor")
        val gate = java.util.concurrent.CountDownLatch(1)
        executor.execute { gate.await(12, TimeUnit.SECONDS) }
        try {
            main {
                check(field<Thing>(detail!!, "mThing").id in created)
                detail!!.findViewById<TextView>(R.id.et_content).text = "$secretContent，修改后"
                detail!!.onBackPressedDispatcher.onBackPressed()
            }
            await("私密返回开始") {
                home.hasWindowFocus() && field<ThingDetailReturnTransition?>(home, "detailReturnTransition") != null
            }
            main { home.moveTaskToBack(true) }
            await("后台清理模拟私密认证") { !manager.isThingPrivacyAuthenticated(note.id) }
            targetContext.startActivity(Intent(targetContext, ThingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            automation.setRotation(baseRotation)
            await("等待保存期间恢复列表") {
                home.hasWindowFocus() && home.resources.configuration.orientation == baseOrientation &&
                    !list.isLayoutRequested && !list.hasPendingAdapterUpdates()
            }
            // 部分桌面强制竖屏，重新进入会改变可见行；先检查可见内容，再把本次私密夹具滚入。
            checkResult("review-private-no-visible-secret", main {
                descendants(list).filterIsInstance<TextView>().none { it.isShown && it.text.contains(secretContent) }
            })
            val previousPositionId = main { list.findViewHolderForAdapterPosition(manager.getListPositionForThingId(note.id))
                ?.itemView?.getTag(R.id.tag_thing_card_bound_stable_id) }
            // 保存等待期间 adapter 固定的是旧展示版本，不能用已更新模型的 position 查找该卡片。
            val presentedPosition = main {
                val adapter = list.adapter as com.ywwynm.everythingdone.adapters.ThingsAdapter
                (0 until adapter.itemCount).single { adapter.presentedEntryAt(it)?.stableId == note.id }
            }
            main { (list.layoutManager as StaggeredGridLayoutManager).scrollToPositionWithOffset(presentedPosition, 120) }
            await("私密夹具重新可见") { !list.isLayoutRequested && !list.hasPendingAdapterUpdates() &&
                list.findViewHolderForAdapterPosition(presentedPosition)?.itemView?.let {
                    it.isShown && it.getTag(R.id.tag_thing_card_bound_stable_id) == note.id } == true }
            val masking = main {
                val card = list.findViewHolderForAdapterPosition(presentedPosition)?.itemView
                JSONObject().put("card_present", card != null)
                    .put("presentation_held", field<Any?>(home, "returnPresentation") != null)
                    .put("content_masked", card != null && descendants(card).filterIsInstance<TextView>().none {
                        it.isShown && it.text.contains(secretContent)
                    }).put("lock_visible", card?.findViewById<View>(R.id.iv_private_thing)?.isShown == true)
                    .put("input_held", !inputReady(home))
            }
            val masked = masking.keys().asSequence().all { masking.getBoolean(it) }
            if (!masked) main {
                val card = list.findViewHolderForAdapterPosition(presentedPosition)?.itemView
                masking.put("card_visibility", card?.visibility).put("card_alpha", card?.alpha)
                    .put("lock_visibility", card?.findViewById<View>(R.id.iv_private_thing)?.visibility)
                    .put("list_visibility", list.visibility).put("list_shown", list.isShown)
                    .put("card_shown", card?.isShown).put("card_tag", card?.getTag(R.id.tag_thing_card_bound_stable_id))
                    .put("ancestor_visibility", JSONArray(generateSequence(card) { it.parent as? View }
                        .map { "${it.javaClass.simpleName}:${it.visibility}:${it.alpha}" }.toList()))
                capture(home, "private-mask-${note.id}")
            }
            checkResult("review-private-masked-before-save", masked,
                masking.put("expected_id", note.id).put("model_position_id", previousPositionId))
        } finally {
            gate.countDown()
            val drained = java.util.concurrent.CountDownLatch(1)
            executor.execute { drained.countDown() }
            check(drained.await(10, TimeUnit.SECONDS))
        }
        await("私密保存完成") { field<Any?>(home, "returnPresentation") == null }
        checkResult("review-private-save-retained", main {
            manager.getThingById(note.id)?.content == "$secretContent，修改后" && inputReady(home)
        })
        close()
    }

    /** 动画完成后改变方向，返回只能使用新的窗口及真实列表布局。 */
    private fun windowChanges() {
        val initialRotation = baseRotation
        val initialOrientation = baseOrientation
        val image = File(output, "window-media.png")
        Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(40, 105, 150))
            Canvas(this).drawCircle(260f, 180f, 90f, Paint().apply { color = Color.rgb(230, 180, 60) })
            image.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        try {
            for (media in listOf(false, true)) for ((index, rotations) in
                listOf(listOf(0, 1), listOf(1, 0), listOf(1, 0, 1)).withIndex()) {
                baseRotation = rotations.first()
                baseOrientation = if (baseRotation % 2 == 0) Configuration.ORIENTATION_PORTRAIT
                    else Configuration.ORIENTATION_LANDSCAPE
                val name = "window-${if (media) "media" else "text"}-$index"
                val note = fixture("窗口改变后的返回 $name", content = List(32) { "窗口变化正文 $it。" }.joinToString("\n"),
                    media = if (media) listOf("0${image.absolutePath}") else emptyList())
                runCase("$name-open", note, keepOpen = true)
                val opened = detail!!
                for (rotation in rotations.drop(1)) {
                    automation.setRotation(rotation)
                    val orientation = if (rotation % 2 == 0) Configuration.ORIENTATION_PORTRAIT
                        else Configuration.ORIENTATION_LANDSCAPE
                    await("$name 详情窗口旋转完成") {
                        detail?.let { page -> page.hasWindowFocus() && page.resources.configuration.orientation == orientation &&
                            !page.window.decorView.isLayoutRequested &&
                            (page.window.decorView.width > page.window.decorView.height) ==
                                (orientation == Configuration.ORIENTATION_LANDSCAPE) } == true
                    }
                    waitForIdleSync()
                }
                checkResult("$name-current-window", main {
                    detail!!.findViewById<View>(R.id.fl_root_detail).alpha == 1f &&
                        field<ThingDetailTransition?>(detail!!, "detailTransition")?.isRunning != true
                }, JSONObject().put("same_detail", detail === opened)
                    .put("detail_width", detail!!.window.decorView.width).put("detail_height", detail!!.window.decorView.height)
                    .put("home_width", home.window.decorView.width).put("home_height", home.window.decorView.height))
                val saved = "仅修改本轮夹具，旋转后保存 $name。"
                val page = detail!!
                returnCase("$name-return", expectedTarget = null, visual = true, allowNative = true, action = {
                    check(note.id in created)
                    field<TextView>(page, "mEtContent").text = saved
                    page.onBackPressedDispatcher.onBackPressed()
                })
                checkResult("$name-saved", main { manager.getThingById(note.id)?.content == saved && inputReady(home) })
            }
        } finally {
            baseRotation = initialRotation
            baseOrientation = initialOrientation
            automation.setRotation(baseRotation)
        }
    }

    /** 由宿主脚本通过系统最近任务菜单改变现有窗口；不通过新 Intent 替换详情。 */
    private fun externalWindowChanges() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val note = fixture("小窗返回专用模拟", content = List(20) { "小窗调整后仍为当前记事 $it。" }.joinToString("\n"))
        runCase("window-external-open", note, keepOpen = true)
        val opened = detail!!
        File(output, "window-ready.json").writeText(JSONObject().put("task_id", opened.taskId)
            .put("thing_id", note.id).toString())
        fun nodeJson(node: AccessibilityNodeInfo): JSONObject {
            val bounds = Rect().also(node::getBoundsInScreen)
            return JSONObject().put("text", node.text?.toString() ?: "")
                .put("content-desc", node.contentDescription?.toString() ?: "")
                .put("resource-id", node.viewIdResourceName ?: "").put("clickable", node.isClickable.toString())
                .put("bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
                .put("children", JSONArray((0 until node.childCount).mapNotNull { node.getChild(it)?.let(::nodeJson) }))
        }
        for (step in 0..15) {
            val command = File(output, "window-command-$step.txt")
            val until = SystemClock.elapsedRealtime() + 600_000
            while (!command.exists() && SystemClock.elapsedRealtime() < until) {
                // UiAutomation 同时只能有一个连接；由当前 instrumentation 导出系统菜单节点。
                if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
                val roots = automation.windows.mapNotNull { it.root }.ifEmpty {
                    listOfNotNull(automation.rootInActiveWindow)
                }
                if (roots.isNotEmpty()) {
                    val temporary = File(output, "window-ui-pending.json")
                    temporary.writeText(JSONObject().put("children", JSONArray(roots.map(::nodeJson))).toString())
                    temporary.renameTo(File(output, "window-ui.json"))
                }
                SystemClock.sleep(250)
            }
            check(command.exists()) { "等待系统窗口操作超时 $step" }
            val action = command.readText().trim()
            if (action == "back") {
                val page = detail!!
                returnCase("window-external-return", expectedTarget = null, visual = true, allowNative = true,
                    action = { page.onBackPressedDispatcher.onBackPressed() })
                break
            }
            check(action.matches(Regex("[a-z0-9-]+")))
            var rotationAccepted: Boolean? = null
            if (action == "rotate-0" || action == "rotate-1") {
                rotationAccepted = automation.setRotation(action.last().digitToInt())
            }
            // 厂商的迷你小窗可见时仍可能让桌面持有焦点；采样不把这一系统状态误判成加载失败。
            await("系统窗口 $action 完成") { detail?.let {
                !it.window.decorView.isLayoutRequested && it.findViewById<View>(R.id.fl_root_detail).alpha == 1f } == true }
            waitForIdleSync()
            val window = main {
                val page = detail!!
                JSONObject().put("same_detail", page === opened).put("task_id", page.taskId)
                    .put("multiwindow", page.isInMultiWindowMode).put("width", page.window.decorView.width)
                    .put("height", page.window.decorView.height).put("rotation", page.window.decorView.display.rotation)
                    .put("focused", page.hasWindowFocus()).put("rotation_request_accepted", rotationAccepted)
                    .put("home_width", home.window.decorView.width).put("home_height", home.window.decorView.height)
                    .put("root_alpha", page.findViewById<View>(R.id.fl_root_detail).alpha)
            }
            screenshot("window-$step-$action")
            checkResult("window-$step-$action", true, window)
            File(output, "window-ack-$step.json").writeText(window.toString())
        }
    }

    /** 外部新建结果与当前详情返回重合时，不得在最终列表发布后再次通知插入。 */
    private fun reviewConcurrentCreation() {
        val note = fixture("返回期间接收另一条新建结果")
        runCase("review-create-overlap-open", note, keepOpen = true)
        val other = fixture("另一入口已创建的测试记事")
        val adapter = field<com.ywwynm.everythingdone.adapters.ThingsAdapterWrapper>(home, "mAdapter")
        try {
            main {
                manager.loadThings()
                val result = Intent(Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI)
                    .putExtra(Def.Communication.KEY_THING, other)
                    .putExtra(Def.Communication.KEY_CREATED_DONE, true)
                    .putExtra(Def.Communication.KEY_RESULT_CODE, Def.Communication.RESULT_CREATE_THING_DONE)
                field<android.content.BroadcastReceiver>(home, "mUpdateUiReceiver").onReceive(home, result)
            }
            returnCase("review-create-overlap-return", expectedTarget = null, during = {
                // 仅在探针中截留旧实现的多余通知，避免把错误插入交给 RecyclerView 导致崩溃。
                adapter.setShouldWaitNotify(true)
            })
            SystemClock.sleep(720)
            checkResult("review-create-no-late-notification", main {
                field<List<*>>(adapter, "mNotifyActions").isEmpty() &&
                    manager.getThingListEntries().orEmpty().count { it.stableId == other.id } == 1
            })
        } finally {
            main {
                adapter.clearNotify()
                adapter.setShouldWaitNotify(false)
                list.adapter?.notifyDataSetChanged()
            }
        }
        close()
    }

    private fun compatibility() {
        val note = fixture("重建与保存模拟")
        runCase("recreate-during-enter", note, recreate = true)
        runCase("edit-after-enter", note, keepOpen = true)
        val edited = "动画完成后的模拟修改，验证真实保存路径。"
        val activity = detail!!
        check(main { field<Thing>(activity, "mThing").id in created })
        main {
            activity.findViewById<TextView>(R.id.et_content).text = edited
            activity.onBackPressedDispatcher.onBackPressed()
        }
        await("模拟修改保存返回") { home.hasWindowFocus() }
        await("模拟保存交接完成") { field<Any?>(home, "returnPresentation") == null }
        detail = null
        val saved = DBHelper(targetContext).readableDatabase.rawQuery(
            "SELECT content FROM things WHERE id=? AND create_time=?",
            arrayOf(note.id.toString(), fixtureTime.toString())).use { it.moveToFirst() && it.getString(0) == edited }
        checkResult("edited-fixture-saved", saved)
        val preferences = ThingAnimationPreferences.preferences(targetContext)
        val key = ThingAnimationPreferences.CREATE_KEY
        val hadKey = preferences.contains(key)
        val oldMode = preferences.getInt(key, ThingAnimationPreferences.RIPPLE)
        try {
            main {
                manager.navigateToFolderPathIndex(-1, false)
                (targetContext.applicationContext as App).setStatus(Thing.UNDERWAY, true)
                home.javaClass.getDeclaredMethod("refreshExternalProjectionUi", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(home, false)
            }
            for (mode in ThingAnimationPreferences.RIPPLE..ThingAnimationPreferences.PARTICLE) {
                label = "creation-$mode"
                preferences.edit().putInt(key, mode).commit()
                val fab = main { home.findViewById<View>(R.id.fab_create) }
                await("新建按钮可用") { home.hasWindowFocus() && fab.isClickable && !list.isLayoutRequested }
                main { fab.performClick() }
                await("新建页面显示") { detail?.hasWindowFocus() == true }
                val page = detail!!
                val transition = main { field<Any?>(page, "creationTransition") }
                val started = transition != null && !main { field<Boolean>(transition, "closed") }
                await("新建动画结束") { transition == null || field<Boolean>(transition, "closed") }
                checkResult(label, started && main {
                    field<Int>(transition!!, "mode") == mode &&
                        field<ThingDetailTransition?>(page, "detailTransition") == null &&
                        page.findViewById<View>(R.id.fl_root_detail).let { it.alpha == 1f && it.scaleX == 1f && it.scaleY == 1f }
                })
                close()
            }
        } finally {
            preferences.edit().apply { if (hadKey) putInt(key, oldMode) else remove(key) }.commit()
        }
    }

    private fun preparation() {
        val note = fixture("空布局复用测试", content = "同一记事的缓存与同步布局必须一致。")
        await("空布局已准备") { field<Any?>(DetailViewPreparation, "prepared") != null }
        runCase("prepared-layout", note, keepOpen = true)
        checkResult("prepared-layout-consumed", samples.getJSONObject(samples.length() - 1).getBoolean("prepared_layout"))
        val cached = detail!!
        val editor = cached.findViewById<android.widget.EditText>(R.id.et_content)
        checkResult("context-rebound", main {
            var current: Context = editor.context
            while (current is ContextWrapper && current !== cached) current = current.baseContext
            current === cached
        })
        await("接收入口完成初始化") { !field<Boolean>(cached, "deferTapUpdateSetup") }
        checkResult("tap-entry-restored", main {
            val available = cached.javaClass.getDeclaredMethod("canTapUpdate").apply { isAccessible = true }
                .invoke(cached) as Boolean
            val toolbar = field<androidx.appcompat.widget.Toolbar>(cached, "mActionbar")
            toolbar.menu.findItem(R.id.act_tap_update)?.isVisible == available &&
                (!available || field<Any?>(cached, "tapUpdateReceiver") != null)
        })
        checkResult("snackbar-not-inflated", main { field<Any?>(cached, "mNormalSnackbar") == null })
        fun pixels(): Bitmap = main {
            val root = detail!!.findViewById<View>(R.id.fl_root_detail)
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        }
        SystemClock.sleep(180)
        val preparedPixels = pixels()
        close()
        runCase("unprepared-layout-fallback", note, keepOpen = true, preparationPolicy = 1)
        SystemClock.sleep(180)
        val normalPixels = pixels()
        val a = IntArray(preparedPixels.width * preparedPixels.height)
        val b = IntArray(normalPixels.width * normalPixels.height)
        preparedPixels.getPixels(a, 0, preparedPixels.width, 0, 0, preparedPixels.width, preparedPixels.height)
        normalPixels.getPixels(b, 0, normalPixels.width, 0, 0, normalPixels.width, normalPixels.height)
        var different = 0
        var maxDelta = 0
        if (a.size == b.size && !preparedPixels.sameAs(normalPixels)) for (index in a.indices) {
            var delta = 0
            for (shift in 0..24 step 8) delta = maxOf(delta,
                kotlin.math.abs((a[index] ushr shift and 255) - (b[index] ushr shift and 255)))
            if (delta > maxDelta) maxDelta = delta
            if (delta > 8) different++
        }
        checkResult("prepared-layout-pixel-parity", a.size == b.size && different == 0,
            JSONObject().put("different_pixels", different).put("max_channel_delta", maxDelta))
        preparedPixels.recycle(); normalPixels.recycle()
        close()
        await("配置校验前重新准备") { field<Any?>(DetailViewPreparation, "prepared") != null }
        runCase("configuration-mismatch-fallback", note, keepOpen = true, preparationPolicy = 2)
        main {
            val input = detail!!.findViewById<android.widget.EditText>(R.id.et_content)
            input.requestFocus()
            input.setSelection(input.length())
        }
        sendStringSync(" input-test")
        checkResult("editor-input-after-fallback", main {
            detail!!.findViewById<android.widget.EditText>(R.id.et_content).text.endsWith(" input-test")
        })
        close()
        await("输入测试前重新准备") { field<Any?>(DetailViewPreparation, "prepared") != null }
        runCase("prepared-editor-input", note, keepOpen = true)
        val picker = main { detail!!.quickRemindPicker!! }
        val selected = main { picker.getPickedIndex() }
        main { picker.show() }
        await("预创建提醒选择器显示") { picker.isShowing() }
        SystemClock.sleep(280)
        screenshot("prepared-quick-picker")
        main { picker.dismiss() }
        val pickerContent = com.ywwynm.everythingdone.views.pickers.PopupPicker::class.java
            .getDeclaredField("mContentView").apply { isAccessible = true }.get(picker) as View
        await("提醒选择器关闭") { !pickerContent.isAttachedToWindow }
        checkResult("prepared-picker-preserves-selection", main { picker.getPickedIndex() == selected })
        main {
            val input = detail!!.findViewById<android.widget.EditText>(R.id.et_content)
            input.requestFocus(); input.setSelection(input.length())
        }
        sendStringSync(" cached-input")
        checkResult("editor-input-after-preparation", main {
            detail!!.findViewById<android.widget.EditText>(R.id.et_content).text.endsWith(" cached-input")
        })
        close()
    }

    private fun fixtures() {
        val image = File(output, "geometry.png")
        Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (x in 0 until 8) for (y in 0 until 6) {
                paint.color = if ((x + y) % 2 == 0) Color.rgb(235, 157, 71) else Color.rgb(49, 130, 174)
                canvas.drawRect(x * 80f, y * 80f, (x + 1) * 80f, (y + 1) * 80f, paint)
            }
            paint.color = Color.WHITE; paint.textSize = 90f
            canvas.drawText("A → B", 165f, 270f, paint)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val imagePath = "0${image.absolutePath}"
        if (args.getString("phase") in setOf("crop", "return-crop")) {
            val source = ThingCardAppearance.SourceAppearance().withPresentation("thumbnail",
                ThingCardAppearance.MediaPresentationAppearance(targetAspectRatio = 1.0,
                    crop = ThingCardAppearance.ThingCardMediaCrop(.2, .8, 1.7)))
            val target = DetailAttachmentMediaAppearance.SourceAppearance(fullSpanEnabled = true)
                .withPresentation("fullSpan", DetailAttachmentMediaAppearance.MediaPresentationAppearance(
                    targetAspectRatio = 2.0, crop = DetailAttachmentMediaAppearance.DetailMediaCrop(.8, .2, 1.3)))
            runCase("different-crops", fixture("不同裁剪", media = listOf(imagePath),
                appearance = ThingCardAppearance(sources = mapOf(imagePath to source)),
                detailAppearance = DetailAttachmentMediaAppearance(sources = mapOf(imagePath to target))), visual = true)
            runCase("media-background-crop", fixture("背景裁剪", media = listOf(imagePath),
                appearance = ThingCardAppearance(mediaBackgroundEnabled = true,
                    sources = mapOf(imagePath to source.withPresentation("mediaBackground",
                        ThingCardAppearance.MediaPresentationAppearance(targetAspectRatio = .7,
                            crop = ThingCardAppearance.ThingCardMediaCrop(.8, .8, 1.9))))),
                detailAppearance = DetailAttachmentMediaAppearance(sources = mapOf(imagePath to target))), visual = true)
            return
        }
        val short = fixture("短正文", content = "从列表卡片展开到详情。\n第二行用于检查文字起点。")
        runCase("text", short, visual = true)
        runCase("repeat-open", short)
        runCase("double-click", short, doubleClick = true)
        runCase("early-finish", short, earlyFinish = true)
        runCase("actual-touch", short, touch = true)
        runCase("rotate-during-enter", short, rotate = true)
        runCase("back-during-enter", short, back = true)
        runCase("partial-card", short, partial = true, visual = true)
        runCase("long-text", fixture("长正文 ".repeat(12), content = List(160) { "长正文第 $it 行，中英文 baseline ABC 123。" }.joinToString("\n")))
        runCase("titleless", fixture("", content = "没有标题的记事"))
        runCase("checklist", fixture("长清单", content = CheckListHelper.toContentStr(List(180) { "清单项目 $it" })))
        runCase("gradient-full", fixture("渐变宽卡片", appearance = ThingCardAppearance(spanMode = Thing.THING_CARD_SPAN_FULL), gradient = true), visual = true)
        for (placement in 1..4) {
            runCase("image-$placement", fixture("封面位置 $placement", media = listOf(imagePath),
                appearance = ThingCardAppearance(imagePlacement = placement)), visual = placement == 1)
        }
        runCase("background-image", fixture("媒体背景", media = listOf(imagePath),
            appearance = ThingCardAppearance(mediaBackgroundEnabled = true)))
        val gif = File(output, "animated.gif")
        targetContext.assets.open("detail-regression/animated.gif").use { input -> gif.outputStream().use(input::copyTo) }
        val video = File(output, "video.mp4")
        targetContext.assets.open("detail-regression/video.mp4").use { input -> video.outputStream().use(input::copyTo) }
        fun playbackAppearance(key: String): DetailAttachmentMediaAppearance {
            if (baseOrientation != Configuration.ORIENTATION_LANDSCAPE) return DetailAttachmentMediaAppearance()
            // 横屏的默认大图可见面积可能不足既有 60% 起播阈值；播放回归使用能完整显示的独立夹具。
            val source = DetailAttachmentMediaAppearance.SourceAppearance(fullSpanEnabled = true)
                .withPresentation("fullSpan",
                    DetailAttachmentMediaAppearance.MediaPresentationAppearance(targetAspectRatio = 3.0))
            return DetailAttachmentMediaAppearance(sources = mapOf(key to source))
        }
        runCase("gif", fixture("动态 GIF", media = listOf("0${gif.absolutePath}"),
            detailAppearance = playbackAppearance("0${gif.absolutePath}")), playback = true, visual = true)
        runCase("video", fixture("视频预览", media = listOf("1${video.absolutePath}"),
            detailAppearance = playbackAppearance("1${video.absolutePath}")), playback = true)
        runCase("non-first-cover", fixture("非首张封面", media = listOf(imagePath, "0${gif.absolutePath}"),
            appearance = ThingCardAppearance(mediaSourceKey = "0${gif.absolutePath}")))
        val privateNote = fixture(Thing.PRIVATE_THING_PREFIX + "私密模拟")
        // 只为本次创建的模拟 id 注入认证完成状态，不读密码、不解锁真实私密记事。
        check(privateNote.id in created)
        main { manager.markThingPrivacyAuthenticated(privateNote.id) }
        runCase("private-simulated", privateNote)
        runCase("authentication-focus-handoff", privateNote, authHandoff = true)
        close()
        detail = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext,
            "transition-external-probe", short.id, -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
        await("外部入口显示") { detail?.hasWindowFocus() == true }
        checkResult("no-source-fallback", main { field<ThingDetailTransition?>(detail!!, "detailTransition") == null &&
            detail!!.findViewById<View>(R.id.fl_root_detail).alpha == 1f })
        if (args.getString("phase") == "return-existing") {
            main { detail!!.javaClass.getDeclaredMethod("finishCommittedReturn").apply { isAccessible = true }.invoke(detail) }
            await("无来源返回") { home.hasWindowFocus() }
            checkResult("no-source-return", main { field<Any?>(home, "returnPresentation") == null && inputReady(home) })
        }
        close()
    }

    private fun fixture(title: String, content: String = "专用动画测试，运行结束自动清理。",
                        media: List<String> = emptyList(), appearance: ThingCardAppearance = ThingCardAppearance(),
                        gradient: Boolean = false,
                        detailAppearance: DetailAttachmentMediaAppearance = DetailAttachmentMediaAppearance()): Thing {
        val db = DBHelper(targetContext).writableDatabase
        val id = db.rawQuery("SELECT MAX(id) FROM things", null).use { it.moveToFirst(); it.getLong(0) + 1 }
        val background = if (gradient) ThingBackground.gradient(Color.rgb(48, 79, 110), Color.rgb(147, 64, 75),
            ThingBackground.Orientation.LT_RB) else ThingBackground.pure(Color.rgb(42, 83, 103))
        // 与真实创建保持同一不变量：头部占位记录始终在新记录之后，避免状态保存推进时碰撞。
        db.execSQL("UPDATE things SET id=?, location=? WHERE type=-1", arrayOf(id + 1, id + 1))
        db.insertOrThrow("things", null, ContentValues().apply {
            put("id", id); put("type", Thing.NOTE); put("state", Thing.UNDERWAY)
            put("color", background.representativeColor()); put("background", background.toJson())
            put("title", title); put("content", content); put("attachment", AttachmentHelper.toAttachmentStr(media, emptyList()))
            put("location", id); put("create_time", fixtureTime); put("update_time", fixtureTime); put("finish_time", 0L)
            put("thing_card_appearance", appearance.toJson())
            put("detail_attachment_media_appearance", detailAppearance.toJson())
        })
        created += id
        File(output, "fixtures.json").writeText(JSONObject().put("create_time", fixtureTime)
            .put("ids", JSONArray(created)).toString(2))
        return db.rawQuery("SELECT * FROM things WHERE id=?", arrayOf(id.toString())).use { it.moveToFirst(); Thing(it) }
    }

    /** 独立 AppWidgetHost 只分配自己的 ID，走真实广播、保存及 RemoteViews 更新。 */
    private fun widgetReturns() {
        val video = File(output, "widget-source.mp4")
        targetContext.assets.open("detail-regression/video.mp4").use { input ->
            video.outputStream().use(input::copyTo)
        }
        VideoCoverPreviewManager.generateBlocking(targetContext, video.absolutePath, null)
        var note = fixture("小组件返回专用模拟", media = listOf("1${video.absolutePath}"))
        val db = DBHelper(targetContext).writableDatabase
        args.getString("source_id")?.let { sourceId ->
            val source = db.rawQuery("SELECT * FROM things WHERE id=?", arrayOf(sourceId)).use {
                check(it.moveToFirst()); Thing(it)
            }
            check(!ThingPrivacyResolver.isEffectivelyPrivate(targetContext, source))
            // 仅复制展示与附件引用；原文件、原预览及原记录保持只读。
            db.update("things", ContentValues().apply {
                put("attachment", source.attachment); put("color", source.getColor())
                put("background", source.getBackground()?.toJson())
                put("thing_card_appearance", source.thingCardAppearance.toJson())
                put("detail_attachment_media_appearance", source.detailAttachmentMediaAppearance.toJson())
            }, "id=? AND create_time=?", arrayOf(note.id.toString(), fixtureTime.toString()))
            note = com.ywwynm.everythingdone.database.ThingDAO.getInstance(targetContext)!!.getThingById(note.id)!!
        }
        val widgets = android.appwidget.AppWidgetManager.getInstance(targetContext)
        val dao = com.ywwynm.everythingdone.database.AppWidgetDAO.getInstance(targetContext)!!
        fun widgetSettings() = dao.getAllThingWidgetInfos().orEmpty().filterNotNull().sortedBy { it.id }.map {
            listOf(it.id, it.thingId, it.size, it.alpha, it.style, it.targetFolderId, it.typeFilterMask,
                it.displayMode, it.status).joinToString("|")
        }
        val originalWidgets = widgetSettings()
        val originalBindings = dao.getAllThingWidgetInfos().orEmpty().filterNotNull().associate {
            it.id to widgets.getAppWidgetInfo(it.id)?.provider
        }
        val provider = android.content.ComponentName(targetContext,
            com.ywwynm.everythingdone.appwidgets.single.ThingWidgetLarge::class.java)
        val widgetEvents = JSONArray()
        val host = main { object : android.appwidget.AppWidgetHost(targetContext, 210126) {
            override fun onCreateView(context: Context, widgetId: Int,
                info: android.appwidget.AppWidgetProviderInfo): android.appwidget.AppWidgetHostView =
                object : android.appwidget.AppWidgetHostView(context) {
                    override fun updateAppWidget(views: android.widget.RemoteViews?) {
                        super.updateAppWidget(views)
                        widgetEvents.put(JSONObject().put("layout", views?.layoutId)
                            .put("title", findViewById<TextView>(R.id.tv_thing_title)?.text?.toString())
                            .put("time", SystemClock.elapsedRealtime()))
                        File(output, "widget-events.json").writeText(widgetEvents.toString(2))
                        home.window.decorView.post { File(output, "widget-tree.txt").writeText(descendants(this)
                            .joinToString("\n") { "${it.javaClass.simpleName} ${it.id} ${(it as? TextView)?.text}" }) }
                    }
                }
        } }
        val id = main { host.allocateAppWidgetId() }
        val ownedWidgets = mutableSetOf(id)
        val widgetPreviews = mutableListOf<View>()
        fun completeConfiguration(widgetId: Int) {
            // Android 17 会在配置 Activity 返回成功前拒绝转换集合内容。测试也走真实宿主协议。
            main { host.startAppWidgetConfigureActivityForResult(home, widgetId, 0, 9129, null) }
            try {
                await("专用小组件配置页面") { resumed?.let {
                    it !== home &&
                        runCatching { field<Int>(it, "mAppWidgetId") == widgetId }.getOrDefault(false)
                } == true }
            } catch (error: Throwable) {
                screenshot("widget-config-timeout")
                main { File(output, "widget-config-state.json").writeText(JSONObject()
                    .put("class", resumed?.javaClass?.name).put("focus", resumed?.hasWindowFocus())
                    .put("intent_id", resumed?.intent?.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
                    .put("field_id", resumed?.let { runCatching { field<Int>(it, "mAppWidgetId") }.getOrNull() })
                    .put("expected", widgetId).toString()) }
                throw error
            }
            val configuration = resumed!!
            main {
                configuration.setResult(Activity.RESULT_OK, Intent().putExtra(
                    android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                configuration.finish()
            }
            await("专用小组件配置完成") { resumed === home && home.hasWindowFocus() }
        }
        File(output, "widget-fixture.json").writeText(JSONObject().put("host_id", 210126)
            .put("widget_id", id).put("thing_id", note.id).toString())
        try {
            automation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
            val bound = try { widgets.bindAppWidgetIdIfAllowed(id, provider) }
                finally { automation.dropShellPermissionIdentity() }
            check(bound) { "测试宿主绑定失败，不更改现有小组件" }
            dao.insert(id, note.id, ThingWidgetInfo.SIZE_LARGE, 100, ThingWidgetInfo.STYLE_NORMAL)
            completeConfiguration(id)
            val view = main {
                host.createView(targetContext, id, widgets.getAppWidgetInfo(id)).also { host.startListening() }
            }
            com.ywwynm.everythingdone.appwidgets.AppWidgetHelper.updateSingleThingAppWidgets(targetContext, note.id)
            await("测试小组件初始内容") { view.findViewById<TextView>(R.id.tv_thing_title)?.text?.toString() == note.title }
            repeat(args.getString("rounds", "6").toInt()) { index ->
                val current = com.ywwynm.everythingdone.database.ThingDAO.getInstance(targetContext)!!.getThingById(note.id)!!
                runCase("widget-$index-open", current, keepOpen = true, readyMedia = true)
                val page = detail!!
                val title = "小组件实际保存 $index"
                main { page.findViewById<TextView>(R.id.et_title).text = title }
                returnCase("widget-$index-return", action = { field<View>(page, "mIbBack").performClick() })
                await("小组件显示最新保存") { view.findViewById<TextView>(R.id.tv_thing_title)?.text?.toString() == title }
                checkResult("widget-$index-current-data",
                    com.ywwynm.everythingdone.database.ThingDAO.getInstance(targetContext)!!.getThingById(note.id)?.title == title)
            }
            if (args.getString("extended") == "true") {
                val queue = field<com.ywwynm.everythingdone.utils.SerialWriteQueue>(manager, "writeQueue")
                fun setTitle(title: String) = db.update("things", ContentValues().apply { put("title", title) },
                    "id=? AND create_time=?", arrayOf(note.id.toString(), fixtureTime.toString()))
                fun request() = com.ywwynm.everythingdone.appwidgets.AppWidgetHelper.updateSingleThingAppWidgets(targetContext, note.id)
                fun shown(title: String) = view.findViewById<TextView>(R.id.tv_thing_title)?.text?.toString() == title
                val releaseSave = java.util.concurrent.CountDownLatch(1)
                val saveStarted = java.util.concurrent.CountDownLatch(1)
                val rendered = java.util.concurrent.atomic.AtomicBoolean()
                com.ywwynm.everythingdone.appwidgets.AppWidgetUpdateWorker.diagnosticObserver = { widgetId, stage ->
                    if (widgetId == id && stage == "render") rendered.set(true)
                }
                try {
                    main { queue.execute { saveStarted.countDown(); releaseSave.await(5, TimeUnit.SECONDS); setTitle("延迟保存最新值") } }
                    check(saveStarted.await(2, TimeUnit.SECONDS))
                    request()
                    SystemClock.sleep(240)
                    checkResult("widget-waits-for-save", !rendered.get())
                } finally { releaseSave.countDown() }
                await("延迟保存后小组件更新") { shown("延迟保存最新值") }
                checkResult("widget-delayed-save-current", true)

                val renderReached = java.util.concurrent.CountDownLatch(1)
                val releaseRender = java.util.concurrent.CountDownLatch(1)
                val once = java.util.concurrent.atomic.AtomicBoolean()
                val offMain = java.util.concurrent.atomic.AtomicBoolean(true)
                com.ywwynm.everythingdone.appwidgets.AppWidgetUpdateWorker.diagnosticObserver = { widgetId, stage ->
                    if (widgetId == id) {
                        if (Looper.myLooper() == Looper.getMainLooper()) offMain.set(false)
                        if (stage == "rendered" && once.compareAndSet(false, true)) {
                            renderReached.countDown(); releaseRender.await(5, TimeUnit.SECONDS)
                        }
                    }
                }
                try {
                    setTitle("不应发布的过期值"); request()
                    check(renderReached.await(5, TimeUnit.SECONDS))
                    setTitle("连续编辑的最后值"); request()
                } finally { releaseRender.countDown() }
                await("连续编辑最后一次刷新") { shown("连续编辑的最后值") }
                checkResult("widget-latest-wins", (0 until widgetEvents.length()).none {
                    widgetEvents.getJSONObject(it).optString("title") == "不应发布的过期值"
                })
                checkResult("widget-renders-off-main", offMain.get())

                db.update("things", ContentValues().apply {
                    put("content", CheckListHelper.toCheckListStr(listOf(
                        CheckListHelper.makeItem('0', 1, "已保存清单甲"),
                        CheckListHelper.makeItem('0', 1, "已保存清单乙"))))
                }, "id=? AND create_time=?", arrayOf(note.id.toString(), fixtureTime.toString()))
                val factory = com.ywwynm.everythingdone.appwidgets.single.ChecklistWidgetService.ChecklistViewFactory(
                    targetContext, Intent().putExtra(Def.Communication.KEY_ID, note.id))
                factory.onCreate()
                checkResult("widget-checklist-reads-saved-data", factory.count == 2)
                setTitle(Thing.PRIVATE_THING_PREFIX + "模拟私密清单"); request()
                await("私密小组件隐藏内容") { !shown("连续编辑的最后值") }
                factory.onDataSetChanged()
                checkResult("widget-private-checklist-hidden", factory.count == 0)
                factory.onDestroy()

                val resized = java.util.concurrent.CountDownLatch(1)
                com.ywwynm.everythingdone.appwidgets.AppWidgetUpdateWorker.diagnosticObserver = { widgetId, stage ->
                    if (widgetId == id && stage == "published") resized.countDown()
                }
                widgets.updateAppWidgetOptions(id, Bundle().apply {
                    putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 240)
                    putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 360)
                    putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
                    putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300)
                })
                checkResult("widget-resize-updates", resized.await(5, TimeUnit.SECONDS))

                val folderId = db.rawQuery("SELECT COALESCE(MAX(id),0)+1 FROM thing_folders", null)
                    .use { check(it.moveToFirst()); it.getLong(0) }
                val folder = ThingFolder(folderId, null, "专用小组件文件夹", Thing.UNDERWAY, Color.BLUE,
                    fixtureTime, createTime = fixtureTime)
                check(ThingFolderDAO.getInstance(targetContext)!!.create(folder) != -1L)
                createdFolders += folderId
                db.update("things", ContentValues().apply { put("folder_id", folderId); put("title", "列表小组件最新记事") },
                    "id=? AND create_time=?", arrayOf(note.id.toString(), fixtureTime.toString()))
                val listTrace = JSONArray()
                com.ywwynm.everythingdone.appwidgets.AppWidgetUpdateWorker.diagnosticObserver = { widgetId, stage ->
                    listTrace.put(JSONObject().put("id", widgetId).put("stage", stage).put("time", SystemClock.elapsedRealtime()))
                    File(output, "list-worker.json").writeText(listTrace.toString(2))
                }
                val listId = main { host.allocateAppWidgetId() }.also(ownedWidgets::add)
                val listProvider = android.content.ComponentName(targetContext,
                    com.ywwynm.everythingdone.appwidgets.list.ThingsListWidget::class.java)
                automation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
                try { check(widgets.bindAppWidgetIdIfAllowed(listId, listProvider)) }
                finally { automation.dropShellPermissionIdentity() }
                dao.insert(listId, -1, ThingWidgetInfo.SIZE_LARGE, 100, ThingWidgetInfo.STYLE_NORMAL,
                    folderId, ThingWidgetInfo.TYPE_FILTER_ALL, ThingWidgetInfo.DISPLAY_MODE_LIST)
                completeConfiguration(listId)
                val listView = main { host.createView(targetContext, listId, widgets.getAppWidgetInfo(listId)).also {
                    // 宿主监听的是已注册 ID 集合；追加 ID 后重新登记，覆盖厂商宿主实现。
                    host.startListening()
                    widgetPreviews += it
                    (home.window.decorView as ViewGroup).addView(it, ViewGroup.LayoutParams(600, 900))
                } }
                com.ywwynm.everythingdone.appwidgets.AppWidgetHelper.updateThingsListAppWidget(targetContext, listId)
                await("列表小组件更新文件夹标题") {
                    listView.findViewById<TextView>(R.id.tv_things_list_title)?.text?.contains(folder.title!!) == true
                }
                val listFactory = com.ywwynm.everythingdone.appwidgets.list.ThingsListWidgetService.ThingsListViewFactory(
                    targetContext, Intent().putExtra(Def.Communication.KEY_WIDGET_ID, listId))
                listFactory.onCreate()
                checkResult("widget-list-projection", listFactory.count == 1)
                val row = main { listFactory.getViewAt(0)!!.apply(targetContext, android.widget.FrameLayout(targetContext)) }
                checkResult("widget-list-saved-title", main { descendants(row).filterIsInstance<TextView>()
                    .any { it.text.toString() == "列表小组件最新记事" } })
                listFactory.onDestroy()

                val deletionReached = java.util.concurrent.CountDownLatch(1)
                val releaseDeletion = java.util.concurrent.CountDownLatch(1)
                val publishedDeleted = java.util.concurrent.atomic.AtomicBoolean()
                com.ywwynm.everythingdone.appwidgets.AppWidgetUpdateWorker.diagnosticObserver = { widgetId, stage ->
                    if (widgetId == id) {
                        if (stage == "rendered") { deletionReached.countDown(); releaseDeletion.await(5, TimeUnit.SECONDS) }
                        if (stage == "published") publishedDeleted.set(true)
                    }
                }
                try {
                    request(); check(deletionReached.await(5, TimeUnit.SECONDS))
                    com.ywwynm.everythingdone.appwidgets.AppWidgetUpdateWorker.cancel(targetContext, id)
                    dao.delete(id)
                    main { host.deleteAppWidgetId(id) }
                    ownedWidgets.remove(id)
                } finally { releaseDeletion.countDown() }
                SystemClock.sleep(120)
                checkResult("widget-deleted-during-render", !publishedDeleted.get() && widgets.getAppWidgetInfo(id) == null)
            }
        } finally {
            main { widgetPreviews.forEach { (it.parent as? ViewGroup)?.removeView(it) } }
            runCatching {
                val state = JSONArray()
                for (ownedId in ownedWidgets) {
                    val jobs = androidx.work.WorkManager.getInstance(targetContext)
                        .getWorkInfosForUniqueWork("app-widget-$ownedId").get(5, TimeUnit.SECONDS)
                    state.put(JSONObject().put("id", ownedId).put("jobs", jobs.toString()))
                }
                File(output, "widget-work-state.json").writeText(state.toString(2))
            }
            com.ywwynm.everythingdone.appwidgets.AppWidgetUpdateWorker.diagnosticObserver = null
            main { host.stopListening() }
            for (ownedId in ownedWidgets) {
                com.ywwynm.everythingdone.appwidgets.AppWidgetUpdateWorker.cancel(targetContext, ownedId)
                main { host.deleteAppWidgetId(ownedId) }
                dao.delete(ownedId)
            }
            checkResult("original-widget-settings-unchanged", widgetSettings() == originalWidgets &&
                originalBindings.all { (widgetId, binding) -> widgets.getAppWidgetInfo(widgetId)?.provider == binding })
            VideoCoverPreviewManager.deletePreviewsForVideo(targetContext, video.absolutePath)
        }
    }

    /** 主设备定向诊断：不备份、不创建夹具，只查询指定标题和对应提醒。 */
    private fun targetReadOnly() {
        val app = targetContext.applicationContext as App
        val result = JSONObject()
        val db = DBHelper(targetContext).readableDatabase
        var sourceId = -1L
        var before: String? = null
        fun fingerprint(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for (table in listOf("things", "reminders")) {
                db.rawQuery("SELECT * FROM $table WHERE id=?", arrayOf(sourceId.toString())).use { cursor ->
                    while (cursor.moveToNext()) for (column in 0 until cursor.columnCount) {
                        digest.update((if (cursor.isNull(column)) "<null>" else cursor.getString(column)).toByteArray())
                        digest.update(0.toByte())
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        try {
            val source = db.rawQuery("SELECT * FROM things WHERE title=? AND type<>-1",
                arrayOf("新的测试君")).use { cursor ->
                check(cursor.count == 1) { "指定标题应唯一，实际 ${cursor.count}" }
                check(cursor.moveToFirst())
                Thing(cursor).also { note ->
                    sourceId = note.id
                    check(!ThingPrivacyResolver.isEffectivelyPrivate(targetContext, note) &&
                        note.id != App.getDoingThingId())
                    val fields = JSONObject()
                    for (column in 0 until cursor.columnCount) if (cursor.getColumnName(column) != "content") {
                        fields.put(cursor.getColumnName(column),
                            if (cursor.isNull(column)) JSONObject.NULL else cursor.getString(column))
                    }
                    File(output, "target.json").writeText(fields.toString(2))
                }
            }
            before = fingerprint()
            if (args.getString("phase") == "target-inspect") return
            app.registerActivityLifecycleCallbacks(callbacks)
            main { targetContext.startActivity(Intent(targetContext, ThingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            await("首页生命周期") { resumed is ThingsActivity }
            home = resumed as ThingsActivity
            manager = ThingManager.getInstance(targetContext)!!
            list = field(home, "mRecyclerView")
            originalProjection = main { manager.getProjection() }
            await("指定记事诊断首页") { home.hasWindowFocus() && list.isLaidOut && list.adapter != null }
            baseRotation = main { home.window.decorView.display.rotation }
            baseOrientation = main { home.resources.configuration.orientation }
            if (args.getString("sample") == "true") {
                sampler = Executors.newSingleThreadScheduledExecutor().apply {
                    scheduleAtFixedRate({
                        stackSamples += JSONObject().put("time_ns", SystemClock.elapsedRealtimeNanos())
                            .put("case", label).put("stack", Looper.getMainLooper().thread.stackTrace.joinToString("\n"))
                    }, 0, 4, TimeUnit.MILLISECONDS)
                }
            }
            repeat(args.getString("repeat", "6").toInt()) { iteration ->
                runCase("target-$iteration-open", source, keepOpen = true)
                SystemClock.sleep(args.getString("dwell_ms", "800").toLong())
                label = "target-$iteration-return"
                if (args.getString("button") == "true") {
                    val page = detail!!
                    main {
                        fun invoke(name: String): Any? = page.javaClass.getDeclaredMethod(name)
                            .apply { isAccessible = true }.invoke(page)
                        val attachment = invoke("currentAttachmentStr") as String
                        val appearance = page.javaClass.getDeclaredMethod(
                            "normalizedDetailAttachmentMediaAppearance", String::class.java)
                            .apply { isAccessible = true }.invoke(page, attachment) as DetailAttachmentMediaAppearance
                        val current = field<Thing>(page, "mThing")
                        check(current.id == sourceId && current.type == Thing.REMINDER &&
                            !App.isSomethingUpdatedSpecially() && field<Any?>(page, "mChangeBackgroundTo") == null)
                        check(Thing.noUpdate(current, invoke("getThingTitle") as String,
                            invoke("getThingContent") as String, attachment, invoke("getThingTypeAfter") as Int,
                            current.getBackground(), field(page, "mThingCardSpanMode"),
                            field(page, "mThingCardImagePlacement"), appearance))
                        val params = field<Any>(page, "rhParams")
                        val time = params.javaClass.getDeclaredMethod("getReminderTime").invoke(params) as Long
                        check(field<com.ywwynm.everythingdone.model.Reminder>(page, "mReminder").notifyTime == time)
                    }
                    // 逐项确认正式路径判定为无修改后，才点击实际返回按钮。
                    returnCase(label, visual = args.getString("visual") == "true",
                        action = {
                            if (args.getString("system_back") == "true") page.onBackPressedDispatcher.onBackPressed()
                            else field<View>(page, "mIbBack").performClick()
                        })
                } else returnCase(label, visual = args.getString("visual") == "true")
                checkResult("target-$iteration-unchanged", before == fingerprint())
            }
        } catch (error: Throwable) {
            failures++
            result.put("error", error.stackTraceToString())
        } finally {
            sampler?.shutdownNow()
            runCatching { close() }.onFailure { result.put("close_error", it.stackTraceToString()) }
            if (::manager.isInitialized) main {
                originalProjection?.let {
                    manager.navigateToFolderPathIndex(-1, false)
                    it.currentFolderId?.let { id -> manager.openFolderPath(id, false, false) }
                    manager.setTypeFilterMask(it.typeFilterMask, false)
                    app.setStatus(it.status, true)
                }
            }
            if (before != null) checkResult("target-row-and-reminder-unchanged", before == fingerprint(),
                JSONObject().put("before_sha256", before).put("after_sha256", fingerprint()))
            app.unregisterActivityLifecycleCallbacks(callbacks)
            main {
                ThingDetailTransition.diagnosticObserver = null
                ThingDetailReturnTransition.diagnosticObserver = null
            }
            writer.shutdown()
            writer.awaitTermination(15, TimeUnit.SECONDS)
            if (stackSamples.isNotEmpty()) File(output, "main-stacks.json")
                .writeText(JSONArray(stackSamples.toList()).toString())
            result.put("failures", failures).put("samples", samples).put("id", sourceId)
            File(output, "result.json").writeText(result.toString(2))
            finish(Activity.RESULT_OK, Bundle().apply { putString("result", result.toString()) })
        }
    }

    /** 用户提供的公开样本保持只读；完整按钮路径只操作具有本次创建时间的副本。 */
    private fun sourceReturnPerformance() {
        val visual = args.getString("phase") == "source-return-visual"
        val sourceId = requireNotNull(args.getString("source_id")).toLong()
        val db = DBHelper(targetContext).writableDatabase
        val source = db.rawQuery("SELECT * FROM things WHERE id=? AND type<>-1",
            arrayOf(sourceId.toString())).use { check(it.moveToFirst()); Thing(it) }
        check(!ThingPrivacyResolver.isEffectivelyPrivate(targetContext, source) && source.id != App.getDoingThingId())
        check(source.type == Thing.NOTE || Thing.isReminderType(source.type))
        val clone = fixture("真实视频返回测试")
        val values = db.rawQuery("SELECT * FROM things WHERE id=?", arrayOf(sourceId.toString())).use {
            check(it.moveToFirst())
            ContentValues().also { values -> android.database.DatabaseUtils.cursorRowToContentValues(it, values) }
        }
        values.remove("id"); values.remove("location"); values.remove("create_time"); values.remove("update_time")
        values.put("title", "测试副本·${source.title}")
        check(db.update("things", values, "id=? AND create_time=?",
            arrayOf(clone.id.toString(), fixtureTime.toString())) == 1)
        if (Thing.isReminderType(source.type)) {
            val reminder = db.rawQuery("SELECT * FROM reminders WHERE id=?", arrayOf(sourceId.toString())).use {
                check(it.moveToFirst())
                ContentValues().also { values -> android.database.DatabaseUtils.cursorRowToContentValues(it, values) }
            }
            reminder.put("id", clone.id)
            db.insertOrThrow("reminders", null, reminder)
        }
        val copy = db.rawQuery("SELECT * FROM things WHERE id=?", arrayOf(clone.id.toString()))
            .use { check(it.moveToFirst()); Thing(it) }
        // 附件仅作为共享只读素材，不能删除原文件、原预览或改写它们的时间。
        val cacheDir = File(targetContext.externalCacheDir ?: targetContext.cacheDir, "video-cover-previews")
        fun cachedFiles() = cacheDir.listFiles()?.filter { it.isFile && it.extension == "gif" }
            ?.associate { it.name to (it.length() to it.lastModified()) }.orEmpty()
        if (args.getString("sample", "false") == "true") {
            sampler = Executors.newSingleThreadScheduledExecutor().apply {
                scheduleAtFixedRate({
                    stackSamples += JSONObject().put("time_ns", SystemClock.elapsedRealtimeNanos())
                        .put("case", label).put("stack", Looper.getMainLooper().thread.stackTrace.joinToString("\n"))
                }, 0, 4, TimeUnit.MILLISECONDS)
            }
        }
        repeat(if (visual) 2 else 6) { iteration ->
            val note = if (iteration < (if (visual) 1 else 2)) source else copy
            runCase("real-$iteration-open", note, keepOpen = true, readyMedia = true, visual = visual)
            SystemClock.sleep(800)
            val before = cachedFiles()
            val page = detail!!
            label = "real-$iteration-return"
            if (note.id in created) {
                returnCase(label, visual = visual, action = { field<View>(page, "mIbBack").performClick() })
            } else returnCase(label, visual = visual)
            checkResult("real-$iteration-cache-unchanged", before == cachedFiles())
        }
    }

    /** 抽屉的真实展示结果：隐藏时合并刷新、可见时即时更新、运动中变化及取消均不丢失。 */
    private fun returnDrawerScenarios() {
        val db = DBHelper(targetContext).writableDatabase
        val folderId = db.rawQuery("SELECT COALESCE(MAX(id),0)+1 FROM thing_folders", null)
            .use { it.moveToFirst(); it.getLong(0) }
        val folder = ThingFolder(folderId, null, "返回抽屉测试", Thing.UNDERWAY, Color.BLUE,
            fixtureTime, createTime = fixtureTime)
        val dao = ThingFolderDAO.getInstance(targetContext)!!
        check(dao.create(folder) != -1L)
        createdFolders += folderId
        File(output, "folder-fixtures.json").writeText(JSONObject().put("create_time", fixtureTime)
            .put("ids", JSONArray(createdFolders)).toString())
        val note = fixture("返回抽屉验证")
        val drawerLayout = field<androidx.drawerlayout.widget.DrawerLayout>(home, "mDrawerLayout")
        val drawer = field<com.ywwynm.everythingdone.views.DrawerNavigationView>(home, "mDrawer")
        fun title() = field<List<com.ywwynm.everythingdone.views.DrawerNavigationView.DrawerItem>>(
            field<Any>(drawer, "adapter"), "items").firstOrNull {
            it.key == com.ywwynm.everythingdone.views.DrawerNavigationView.ItemKey.Folder(folderId)
        }?.title
        for (kind in listOf("hidden", "visible", "during", "cancel")) {
            runCase("drawer-$kind-open", note, keepOpen = true)
            val expected = "抽屉更新·$kind"
            main {
                folder.title = expected
                dao.update(folder)
                if (kind == "visible") drawerLayout.openDrawer(androidx.core.view.GravityCompat.START, false)
            }
            var expectedAfter = expected
            returnCase("drawer-$kind-return", expectCancellation = kind == "cancel",
                visual = kind == "hidden" || kind == "visible",
                during = if (kind == "during" || kind == "cancel") ({
                    expectedAfter = "$expected·运动中"
                    folder.title = expectedAfter
                    dao.update(folder)
                    home.javaClass.getDeclaredMethod("justNotifyAll", Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(home, false)
                    if (kind == "cancel") field<ThingDetailReturnTransition>(home, "detailReturnTransition")
                        .finish("probe-cancel")
                }) else null)
            main {
                drawerLayout.openDrawer(androidx.core.view.GravityCompat.START, false)
                val items = field<List<com.ywwynm.everythingdone.views.DrawerNavigationView.DrawerItem>>(
                    field<Any>(drawer, "adapter"), "items")
                val index = items.indexOfFirst {
                    it.key == com.ywwynm.everythingdone.views.DrawerNavigationView.ItemKey.Folder(folderId)
                }
                check(index >= 0)
                val headers = field<RecyclerView.Adapter<*>>(drawer, "headerAdapter").itemCount
                field<RecyclerView>(drawer, "recyclerView").scrollToPosition(index + headers)
            }
            await("抽屉更新可见") { drawerLayout.isDrawerVisible(androidx.core.view.GravityCompat.START) &&
                descendants(drawer).filterIsInstance<TextView>().any { it.text.toString() == expectedAfter } }
            checkResult("drawer-$kind-current-content", main {
                title() == expectedAfter && inputReady(home) && !field<Boolean>(home, "returnDrawerRefreshPending")
            })
            main { drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START, false) }
            await("抽屉关闭") { !drawerLayout.isDrawerVisible(androidx.core.view.GravityCompat.START) }
        }
    }

    /** 已缓存的视频派生 GIF；从实际返回按钮开始计时，不跳过校验、结果处理或保存。 */
    private fun videoReturnPerformance() {
        val visual = args.getString("phase") == "video-return-visual"
        val supplied = File(targetContext.getExternalFilesDir(null), "detail-transition/video-perf-source.mp4")
        val video = File(output, "cached-cover.mp4")
        if (supplied.isFile) supplied.copyTo(video) else
            targetContext.assets.open("detail-regression/video.mp4").use { input ->
                video.outputStream().use(input::copyTo)
            }
        // 计时之前生成完毕，之后每次检查同一个缓存文件未改变。
        val preview = checkNotNull(VideoCoverPreviewManager.generateBlocking(targetContext, video.absolutePath, null))
        val gif = File(output, "same-preview.gif").also { preview.copyTo(it) }
        val stamp = preview.lastModified() to preview.length()
        val videoKey = "1${video.absolutePath}"
        val gifKey = "0${gif.absolutePath}"
        fun appearance(key: String) = DetailAttachmentMediaAppearance(sources = mapOf(key to
            DetailAttachmentMediaAppearance.SourceAppearance(fullSpanEnabled = true).withPresentation("fullSpan",
                DetailAttachmentMediaAppearance.MediaPresentationAppearance(targetAspectRatio = 3.0))))
        val cases = listOf(
            "text" to fixture("返回等待纯文字对照"),
            "gif" to fixture("返回等待同源 GIF 对照", media = listOf(gifKey), detailAppearance = appearance(gifKey)),
            "video" to fixture("返回等待缓存视频封面", media = listOf(videoKey), detailAppearance = appearance(videoKey)),
            "video-background" to fixture("返回等待缓存视频背景", media = listOf(videoKey),
                appearance = ThingCardAppearance(mediaBackgroundEnabled = true, spanMode = Thing.THING_CARD_SPAN_FULL),
                detailAppearance = appearance(videoKey))
        )
        try {
            if (args.getString("sample", "false") == "true") {
                sampler = Executors.newSingleThreadScheduledExecutor().apply {
                    scheduleAtFixedRate({
                        stackSamples += JSONObject().put("time_ns", SystemClock.elapsedRealtimeNanos())
                            .put("case", label).put("stack", Looper.getMainLooper().thread.stackTrace.joinToString("\n"))
                    }, 0, 4, TimeUnit.MILLISECONDS)
                }
            }
            for ((kind, note) in cases) repeat(if (visual) 1 else 4) { iteration ->
                runCase("cached-$kind-$iteration-open", note, keepOpen = true, readyMedia = kind != "text")
                if (kind != "text") await("详情 GIF 已加载") {
                    descendants(detail!!.window.decorView).filterIsInstance<ImageView>().any { it.drawable is GifDrawable }
                }
                if (visual && kind != "text") verifyGifSnapshot("$kind-detail", detail!!.window.decorView)
                // 固定驻留，覆盖后台列表已经 onStop、GIF 生命周期已经暂停的常规返回。
                SystemClock.sleep(400)
                label = "cached-$kind-$iteration-return"
                check(note.id in created)
                val page = detail!!
                returnCase(label, visual = visual, action = { field<View>(page, "mIbBack").performClick() })
                if (visual && kind != "text") {
                    val card = main { descendants(list).first { it.getTag(R.id.tag_thing_card_bound_stable_id) == note.id } }
                    verifyGifSnapshot("$kind-card", card)
                    val frames = mutableSetOf<Int>()
                    repeat(16) {
                        SystemClock.sleep(35)
                        main { descendants(card).filterIsInstance<ImageView>().forEach {
                            (it.drawable as? GifDrawable)?.let { gif -> frames += gif.frameIndex }
                        } }
                    }
                    checkResult("$kind-return-playback", frames.size > 1)
                }
                checkResult("cached-$kind-$iteration-file-unchanged",
                    preview.lastModified() to preview.length() == stamp &&
                        VideoCoverPreviewManager.getReadyPreview(targetContext, video.absolutePath, null) == preview,
                    JSONObject().put("gif_bytes", stamp.second))
            }
        } finally {
            // 只清理由本次新建视频产生的派生缓存。
            VideoCoverPreviewManager.deletePreviewsForVideo(targetContext, video.absolutePath)
        }
    }

    /** 真实 GIF 控件固定当前帧，比较优化路径与原硬件快照；采样不混入性能计时。 */
    private fun verifyGifSnapshot(name: String, root: View) {
        val image = main { descendants(root).filterIsInstance<ImageView>().first { it.drawable is GifDrawable } }
        val gif = main { image.drawable as GifDrawable }
        val running = main { gif.isRunning.also { gif.stop() } }
        SystemClock.sleep(100)
        var reference: Bitmap? = null
        var actual: Bitmap? = null
        val latch = java.util.concurrent.CountDownLatch(2)
        main {
            ParticleDismissController.captureContent(image) { reference = it; latch.countDown() }
            ThingDetailReturnTransition.Companion.javaClass.getDeclaredMethod("captureMedia",
                ImageView::class.java, kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }
                .invoke(ThingDetailReturnTransition.Companion, image, { pixels: Bitmap? ->
                    actual = pixels; latch.countDown()
                })
        }
        check(latch.await(5, TimeUnit.SECONDS))
        try {
            val a = checkNotNull(actual)
            val b = checkNotNull(reference)
            check(a.width == b.width && a.height == b.height)
            val first = IntArray(a.width * a.height)
            val second = IntArray(first.size)
            a.getPixels(first, 0, a.width, 0, 0, a.width, a.height)
            b.getPixels(second, 0, b.width, 0, 0, b.width, b.height)
            var sum = 0L
            var max = 0
            first.indices.forEach { i -> for (shift in listOf(0, 8, 16, 24)) {
                val difference = kotlin.math.abs((first[i] ushr shift and 255) - (second[i] ushr shift and 255))
                max = maxOf(max, difference); sum += difference
            } }
            val mean = sum.toDouble() / (first.size * 4)
            checkResult("$name-gif-snapshot", max <= 2 && mean < .5,
                JSONObject().put("max_channel_error", max).put("mean_channel_error", mean)
                    .put("width", a.width).put("height", a.height))
            File(output, "$name-gif-snapshot.png").outputStream().use { a.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            actual?.recycle(); reference?.recycle()
            if (running) main { gif.start() }
        }
    }

    private fun runCase(name: String, thing: Thing, visual: Boolean = false,
                        doubleClick: Boolean = false, earlyFinish: Boolean = false,
                        partial: Boolean = false, playback: Boolean = false,
                        touch: Boolean = false, rotate: Boolean = false, back: Boolean = false,
                        authHandoff: Boolean = false, recreate: Boolean = false, keepOpen: Boolean = false,
                        preparationPolicy: Int = 0, readyMedia: Boolean = false,
                        quickReturn: Float? = null, quickToolbar: Boolean = false) {
        close()
        // 后台测试可能经过固定竖屏的系统桌面，每个样本重新确认所声明的基准方向。
        automation.setRotation(baseRotation)
        await("样本方向稳定") { home.resources.configuration.orientation == baseOrientation && !list.isLayoutRequested }
        label = name; expectFrames = visual; captureStage = 0; mediaMatched = false; enterToolbarDark = null
        statusBarHandoffCorrect = true
        sourceLightStatusBars = main { WindowInsetsControllerCompat(home.window, home.window.decorView).isAppearanceLightStatusBars }
        frameTimes = mutableListOf(); progressValues = mutableListOf(); matches = 0
        main {
            val projection = manager.getProjection()
            if (thing.id in created || projection.currentFolderId != thing.folderId ||
                projection.status != thing.state || projection.typeFilterMask != ThingWidgetInfo.TYPE_FILTER_ALL) {
                manager.navigateToFolderPathIndex(-1, false)
                thing.folderId?.let { manager.openFolderPath(it, false, false) }
                manager.setTypeFilterMask(ThingWidgetInfo.TYPE_FILTER_ALL, false)
                (targetContext.applicationContext as App).setStatus(thing.state, true)
                // 走首页实际投影刷新，先完成新投影的顶部布局，再滚到被测记事。
                home.javaClass.getDeclaredMethod("refreshExternalProjectionUi", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(home, false)
            }
        }
        await("$name 投影布局") { !list.isComputingLayout && !list.isLayoutRequested &&
            list.itemAnimator?.isRunning != true }
        val position = main { manager.getListPositionForThingId(thing.id) }
        if (position < 1) {
            checkResult(name, false, JSONObject().put("reason", "记录未出现在对应列表投影"))
            return
        }
        main {
            (list.layoutManager as StaggeredGridLayoutManager).scrollToPositionWithOffset(position, if (partial) -80 else 120)
        }
        await("$name 卡片绑定") {
            val card = list.findViewHolderForAdapterPosition(position)?.itemView
            card?.isShown == true && card.getTag(R.id.tag_thing_card_bound_stable_id) == thing.id &&
                !list.isLayoutRequested && list.itemAnimator?.isRunning != true && home.hasWindowFocus()
        }
        // 等待列表入场动画及封面加载，使用实际 View 状态，不靠屏幕坐标猜测。
        SystemClock.sleep(240)
        var card = main { list.findViewHolderForAdapterPosition(position)!!.itemView.findViewById<View>(R.id.cv_thing) }
        if (!partial) {
            main {
                val rect = Rect()
                card.getGlobalVisibleRect(rect)
                if (rect.height() < card.height) {
                    val location = IntArray(2).also(card::getLocationOnScreen)
                    val listLocation = IntArray(2).also(list::getLocationOnScreen)
                    list.scrollBy(0, location[1] - listLocation[1] - 100)
                }
            }
            await("$name 滚动布局") { !list.isComputingLayout && !list.isLayoutRequested &&
                list.itemAnimator?.isRunning != true }
            card = main { list.findViewHolderForAdapterPosition(position)!!.itemView.findViewById<View>(R.id.cv_thing) }
        }
        val sourceState = main {
            val nodes = JSONArray()
            var view: View? = card
            while (view != null) {
                val rect = Rect()
                val visible = view.getGlobalVisibleRect(rect)
                nodes.put(JSONObject().put("class", view.javaClass.simpleName).put("id", view.id)
                    .put("visibility", view.visibility).put("shown", view.isShown)
                    .put("global_visible", visible).put("rect", rect.toString()).put("alpha", view.alpha)
                    .put("top", view.top).put("height", view.height).put("translation_y", view.translationY))
                view = view.parent as? View
            }
            nodes
        }
        if (!main { card.getGlobalVisibleRect(Rect()) && card.isShown }) {
            File(output, "$name-invisible-source.json").writeText(sourceState.toString(2))
            screenshot("$name-invisible-source")
            main { card.requestRectangleOnScreen(Rect(0, 0, card.width, card.height), true) }
            await("$name 可见区布局") { !list.isComputingLayout && !list.isLayoutRequested &&
                card.getGlobalVisibleRect(Rect()) }
        }
        if (visual) screenshot("$name-source")
        if (preparationPolicy != 0) main {
            if (preparationPolicy == 1) DetailViewPreparation.clear()
            else field<DetailViewPreparation.Prepared?>(DetailViewPreparation, "prepared")?.let {
                it.configuration.fontScale += .1f
            }
        }
        await("$name 输入与当前卡片就绪") {
            inputReady(home) && !list.isLayoutRequested && list.itemAnimator?.isRunning != true &&
                list.findViewHolderForAdapterPosition(position)?.itemView?.let {
                    it.isShown && it.getTag(R.id.tag_thing_card_bound_stable_id) == thing.id } == true
        }
        card = main { list.findViewHolderForAdapterPosition(position)!!.itemView.findViewById<View>(R.id.cv_thing) }
        if (readyMedia) await("$name 首页 GIF 已加载") {
            descendants(card).filterIsInstance<ImageView>().any { it.drawable is GifDrawable }
        }
        val beforeCreates = detailCreates
        val start = SystemClock.elapsedRealtime()
        val stages = JSONArray()
        main {
            ThingDetailTransition.diagnosticObserver = { stage ->
                stages.put(JSONObject().put("stage", stage)
                    .put("ms", SystemClock.elapsedRealtimeNanos() / 1_000_000.0 - start))
            }
        }
        if (authHandoff) {
            check(thing.id in created)
            val dialog = main { Dialog(home).apply { setContentView(TextView(home).apply { text = "模拟认证完成" }); show() } }
            await("模拟认证窗口焦点") { !home.hasWindowFocus() }
            main { dialog.dismiss(); card.performClick() }
        } else if (touch) {
            val rect = main { Rect().also { check(card.getGlobalVisibleRect(it)) } }
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                check(automation.injectInputEvent(event, true))
                event.recycle()
                if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(48)
            }
        } else main { card.performClick(); if (doubleClick) card.performClick() }
        await("$name 详情创建") { detail?.let { !it.isFinishing && it.hasWindowFocus() } == true }
        val activity = detail!!
        if (quickReturn != null) {
            await("快速返回所需进度") {
                field<ThingDetailTransition?>(activity, "detailTransition")?.progress?.let { it >= quickReturn } == true
            }
            val entering = main { field<ThingDetailTransition>(activity, "detailTransition") }
            val layer = field<View>(entering, "layer")
            var pausedFrameRetained = false
            val application = activity.application
            val handoffObserver = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityPostPaused(page: Activity) {
                    if (page === activity) pausedFrameRetained = !entering.isRunning &&
                        layer.parent === activity.window.decorView && layer.visibility == View.VISIBLE &&
                        field<Bitmap?>(layer, "returnPixels") != null
                }
                override fun onActivityCreated(page: Activity, state: Bundle?) {}
                override fun onActivityStarted(page: Activity) {}
                override fun onActivityResumed(page: Activity) {}
                override fun onActivityPaused(page: Activity) {}
                override fun onActivityStopped(page: Activity) {}
                override fun onActivitySaveInstanceState(page: Activity, state: Bundle) {}
                override fun onActivityDestroyed(page: Activity) {}
            }
            application.registerActivityLifecycleCallbacks(handoffObserver)
            var at = 0f
            try {
                returnCase("$name-return", visual = args.getString("visual") == "true", action = {
                    at = entering.progress
                    if (quickToolbar) {
                        val rect = Rect().also { check(field<View>(activity, "mIbBack").getGlobalVisibleRect(it)) }
                        val down = SystemClock.uptimeMillis()
                        for (eventAction in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val event = MotionEvent.obtain(down, down, eventAction, rect.exactCenterX(), rect.exactCenterY(), 0)
                            event.source = InputDevice.SOURCE_TOUCHSCREEN
                            check(automation.injectInputEvent(event, false)); event.recycle()
                        }
                    }
                    else activity.onBackPressedDispatcher.onBackPressed()
                })
            } finally { application.unregisterActivityLifecycleCallbacks(handoffObserver) }
            checkResult("$name-during-enter", at < 1f, JSONObject().put("enter_progress", at))
            val geometry = main { layer.javaClass.getDeclaredMethod("returnGeometry").apply { isAccessible = true }
                .invoke(layer) as Pair<*, *> }
            val expected = geometry.first as android.graphics.RectF
            val capturedBounds = samples.getJSONObject(samples.length() - 2).optString("source_bounds")
            checkResult("$name-current-geometry", expected.toString() == capturedBounds,
                JSONObject().put("expected", expected.toString()).put("captured", capturedBounds))
            await("关闭窗口完成清理") { !activity.window.decorView.isAttachedToWindow && layer.parent == null }
            checkResult("$name-window-handoff", pausedFrameRetained && main { field<Bitmap?>(layer, "returnPixels") == null })
            return
        }
        if (recreate) {
            val wasRunning = main { field<ThingDetailTransition?>(activity, "detailTransition")?.isRunning == true }
            main { activity.recreate() }
            await("详情重建完成") { detail !== activity && detail?.hasWindowFocus() == true }
            checkResult(name, wasRunning && main {
                field<ThingDetailTransition?>(detail!!, "detailTransition") == null &&
                    field<Thing>(detail!!, "mThing").id == thing.id &&
                    detail!!.findViewById<View>(R.id.fl_root_detail).alpha == 1f
            })
            close()
            return
        }
        if (rotate) {
            val beforeRotation = main { field<ThingDetailTransition?>(activity, "detailTransition")?.isRunning == true }
            try {
                automation.setRotation((baseRotation + 1) % 4)
                await("打开期间旋转完成") { activity.resources.configuration.orientation != baseOrientation }
                checkResult(name, beforeRotation && main {
                    field<ThingDetailTransition?>(activity, "detailTransition")?.isRunning != true &&
                        activity.findViewById<View>(R.id.fl_root_detail).alpha == 1f
                })
                close()
            } finally {
                automation.setRotation(baseRotation)
            }
            await("首页恢复方向") { home.resources.configuration.orientation == baseOrientation && !list.isLayoutRequested }
            return
        }
        if (back) {
            val running = main { field<ThingDetailTransition?>(activity, "detailTransition")?.isRunning == true }
            main { activity.onBackPressedDispatcher.onBackPressed() }
            await("系统返回完成") { home.hasWindowFocus() }
            checkResult(name, running && detailCreates == beforeCreates + 1)
            detail = null
            return
        }
        if (earlyFinish) {
            main { activity.finish() }
            await("提前退出完成") { home.hasWindowFocus() }
            checkResult(name, detailCreates == beforeCreates + 1)
            detail = null
            return
        }
        await("$name 动画结束") { field<ThingDetailTransition?>(activity, "detailTransition")?.isRunning != true }
        val elapsed = SystemClock.elapsedRealtime() - start
        val root = main { activity.findViewById<View>(R.id.fl_root_detail) }
        val renderedThing = main { field<Thing>(activity, "mThing") }
        val values = main { progressValues.toList() }
        val allGaps = main { frameTimes.zipWithNext { a, b -> (b - a) / 1_000_000.0 } }
        val gaps = allGaps.filterIndexed { index, _ -> values[index] > 0f }
        val firstMotion = values.indexOfFirst { it > 0f }.takeIf { it >= 0 }?.let {
            main { frameTimes[it] / 1_000_000.0 - start }
        }
        val clickAt = (0 until stages.length()).map { stages.getJSONObject(it) }
            .firstOrNull { it.getString("stage") == "click" }?.getDouble("ms")
        val preparedLayout = (0 until stages.length()).any {
            stages.getJSONObject(it).getString("stage") == "prepared-layout-hit"
        }
        val passed = renderedThing.id == thing.id && main { root.alpha == 1f &&
            activity.resources.configuration.orientation == baseOrientation } &&
            detailCreates == beforeCreates + 1 && values.size >= 3 &&
            values.zipWithNext().all { (a, b) -> b >= a } && values.last() > .9f && statusBarHandoffCorrect
        checkResult(name, passed, JSONObject().put("frames", values.size).put("matched_elements", matches)
            .put("stages", stages)
            .put("prepared_layout", preparedLayout)
            .put("media_matched", mediaMatched)
            .put("status_bar_handoff", statusBarHandoffCorrect)
            .put("progress", JSONArray(values)).put("max_frame_gap_ms", gaps.maxOrNull() ?: 0)
            .put("max_preparation_frame_gap_ms", allGaps.maxOrNull() ?: 0).put("first_motion_ms", firstMotion)
            .put("click_to_motion_ms", if (firstMotion != null && clickAt != null) firstMotion - clickAt else null)
            .put("created_ms", detailCreatedAt - start).put("resumed_ms", detailResumedAt - start)
            .put("first_frame_ms", frameTimes.firstOrNull()?.let { it / 1_000_000.0 - start })
            .put("open_ms", elapsed).put("type", thing.type).put("state", thing.state)
            .put("rendered_id", renderedThing.id).put("source_width", card.width).put("source_height", card.height)
            .put("window_width", activity.window.decorView.width).put("window_height", activity.window.decorView.height)
            .put("fallback", activity.intent.getStringExtra(ThingDetailTransition.EXTRA_FALLBACK))
            .put("finish_reason", main { field<ThingDetailTransition?>(activity, "detailTransition")?.finishReason }))
        if (args.getString("phase") == "perf") File(output, "$name-frames.json")
            .writeText(JSONArray(frameTimes.toList()).toString())
        if (name == "image-1") {
            SystemClock.sleep(140)
            val afterColor = main { field<Boolean?>(field<Any>(activity, "mToolbarContrast"), "toolbarDark") }
            checkResult("toolbar-color-handoff", enterToolbarDark != null && enterToolbarDark == afterColor,
                JSONObject().put("during", enterToolbarDark).put("after", afterColor))
        }
        if (visual) screenshot("$name-target")
        if (playback) {
            main {
                val controller = field<DetailAttachmentPlaybackController>(activity, "mAttachmentPlaybackController")
                setField(controller, "mode", DetailAutoplayMode.ALL_LOOP)
                controller.onItemsChanged()
            }
            await("$name 播放恢复") { descendants(root).filterIsInstance<ImageView>()
                .any { (it.drawable as? GifDrawable)?.isRunning == true } }
            val frames = mutableSetOf<Int>()
            repeat(16) { SystemClock.sleep(35); main { descendants(root).filterIsInstance<ImageView>()
                .forEach { (it.drawable as? GifDrawable)?.let { gif -> frames += gif.frameIndex } } } }
            checkResult("$name-playback", frames.size > 1)
        }
        if (!keepOpen) {
            if (args.getString("phase") in setOf("return-existing", "return-crop"))
                returnCase("$name-return", visual = visual)
            else close()
        }
    }

    /** 已有数据只经过提交后的退出接口；编辑及实际保存一律限本次创建的记录。 */
    private fun returnCase(name: String, expectedTarget: Boolean? = true, visual: Boolean = false,
                           action: (() -> Unit)? = null, during: (() -> Unit)? = null,
                           expectCancellation: Boolean = false, allowNative: Boolean = false) {
        val page = detail!!
        var transition: ThingDetailReturnTransition? = null
        val times = mutableListOf<Long>()
        val values = mutableListOf<Float>()
        val stages = JSONArray()
        var captureIndex = 0
        var motionCaptured = false
        var injected = false
        var geometryStable = true
        var inputProtected = true
        var drawerRefreshDeferred = false
        var firstBarsCorrect = true
        val detailLightBars = main {
            WindowInsetsControllerCompat(page.window, page.window.decorView).isAppearanceLightStatusBars
        }
        var endpoint: android.graphics.RectF? = null
        val started = SystemClock.elapsedRealtimeNanos()
        val decor = home.window.decorView
        val listener = ViewTreeObserver.OnDrawListener {
            val current = field<ThingDetailReturnTransition?>(home, "detailReturnTransition")
            if (current != null && current.isRunning) {
                transition = current
                times += SystemClock.elapsedRealtimeNanos()
                values += current.progress
                inputProtected = inputProtected && !inputReady(home)
                drawerRefreshDeferred = drawerRefreshDeferred || field<Boolean>(home, "returnDrawerRefreshPending")
                if (current.progress == 0f) firstBarsCorrect = firstBarsCorrect &&
                    WindowInsetsControllerCompat(home.window, decor).isAppearanceLightStatusBars == detailLightBars
                if (current.progress > 0f && current.hasTarget) {
                    if (endpoint == null) endpoint = current.targetBounds
                    else geometryStable = geometryStable && endpoint == current.targetBounds
                }
                if (during != null && !injected && current.progress > .2f) {
                    injected = true
                    decor.post(during)
                }
                if (visual && captureIndex < 3 && current.progress >= listOf(0f, .4f, .9f)[captureIndex]) {
                    if (!motionCaptured && args.getString("phase") == "return-ghosting" && current.progress > 0f) {
                        motionCaptured = true
                        returnMotionPixels(current, name)
                    }
                    if (captureIndex == 2 && current.hasTarget) returnEndpointPixels(current, name)
                    capture(home, "$name-frame-${captureIndex++}")
                }
            }
        }
        main {
            ThingDetailReturnTransition.diagnosticObserver = { stage ->
                stages.put(JSONObject().put("stage", stage)
                    .put("ms", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0))
            }
            decor.viewTreeObserver.addOnDrawListener(listener)
            if (action != null) action() else page.javaClass.getDeclaredMethod("finishCommittedReturn")
                .apply { isAccessible = true }.invoke(page)
        }
        await("$name 首页恢复") { home.hasWindowFocus() }
        await("$name 返回完成") { field<ThingDetailReturnTransition?>(home, "detailReturnTransition")?.isRunning != true &&
            field<Any?>(home, "returnPresentation") == null }
        main { decor.viewTreeObserver.removeOnDrawListener(listener); ThingDetailReturnTransition.diagnosticObserver = null }
        detail = null
        val result = transition
        val inputReleased = main { inputReady(home) }
        val drawerRefreshComplete = main { !field<Boolean>(home, "returnDrawerRefreshPending") }
        val valid = if (allowNative && result == null) page.isFinishing && inputReleased &&
            !list.isLayoutSuppressed else result != null && !result.isRunning && geometryStable && firstBarsCorrect &&
            inputProtected && inputReleased && drawerRefreshComplete && !list.isLayoutSuppressed &&
            (expectedTarget == null || result.hasTarget == expectedTarget) &&
            (expectCancellation || result.finishReason == "finished" && values.any { it > .9f }) &&
            values.zipWithNext().all { (a, b) -> b >= a } &&
            (during == null || injected)
        val motion = values.indexOfFirst { it > 0f }
        val gaps = times.zipWithNext { a, b -> (b - a) / 1_000_000.0 }
            .filterIndexed { index, _ -> values[index] > 0f }
        checkResult(name, valid, JSONObject().put("target", result?.hasTarget)
            .put("source_bounds", result?.let { field<ThingDetailReturnTransition.Frame>(it, "source").rect.toString() })
            .put("started_ns", started)
            .put("native_fallback", result == null)
            .put("window_width", decor.width).put("window_height", decor.height)
            .put("matched_elements", result?.matchedElementCount).put("reason", result?.finishReason)
            .put("geometry_stable", geometryStable).put("frames", values.size).put("stages", stages)
            .put("first_status_bar_correct", firstBarsCorrect)
            .put("input_protected", inputProtected).put("input_released", inputReleased)
            .put("drawer_refresh_deferred", drawerRefreshDeferred).put("drawer_refresh_complete", drawerRefreshComplete)
            .put("first_motion_ms", if (motion >= 0) (times[motion] - started) / 1_000_000.0 else null)
            .put("max_frame_gap_ms", gaps.maxOrNull()).put("progress", JSONArray(values)))
        if (visual) screenshot("$name-actual")
    }

    private fun returnScenarios() {
        fun open(name: String, note: Thing, partial: Boolean = false) =
            runCase("$name-open", note, partial = partial, keepOpen = true)
        fun update(note: Thing, values: ContentValues) {
            check(note.id in created)
            check(DBHelper(targetContext).writableDatabase.update("things", values,
                "id=? AND create_time=?", arrayOf(note.id.toString(), fixtureTime.toString())) == 1)
        }
        fun refresh() {
            home.sendBroadcast(Intent(Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI)
                .setPackage(targetContext.packageName)
                .putExtra(Def.Communication.KEY_RESULT_CODE, Def.Communication.RESULT_JUST_NOTIFY_DATASET_CHANGED))
        }
        val text = fixture("返回短正文", content = "当前详情收回到当前真实卡片。")
        open("unchanged", text)
        returnCase("return-unchanged", visual = true)
        open("edit", text)
        val page = detail!!
        val edited = "返回前保存的新正文。".repeat(16)
        var savedOnlyOnce = false
        returnCase("return-edited", visual = true, action = {
            check(field<Thing>(page, "mThing").id in created)
            page.findViewById<TextView>(R.id.et_content).text = edited
            page.onBackPressedDispatcher.onBackPressed()
            val savedAt = field<Thing>(page, "mThing").updateTime
            SystemClock.sleep(2)
            page.onBackPressedDispatcher.onBackPressed()
            savedOnlyOnce = field<Thing>(page, "mThing").updateTime == savedAt
        })
        checkResult("return-edit-saved", manager.getThingById(text.id)?.content == edited)
        checkResult("return-double-back-saved-once", savedOnlyOnce)
        val other = fixture("其他记事", content = "这条也只用于测试。")
        open("other-change", text)
        main {
            update(other, ContentValues().apply { put("content", "其他记事变高后的正文。".repeat(80)) })
            App.setJustNotifyAll(true)
        }
        returnCase("return-other-change")
        val sticky = fixture("置顶与取消置顶模拟")
        repeat(2) { index ->
            open("sticky-$index", sticky)
            val stickyPage = detail!!
            returnCase("return-sticky-$index", expectedTarget = null, action = {
                stickyPage.javaClass.getDeclaredMethod("stickyOrCancel")
                    .apply { isAccessible = true }.invoke(stickyPage)
            })
            checkResult("return-sticky-state-$index", DBHelper(targetContext).readableDatabase.rawQuery(
                "SELECT location FROM things WHERE id=?", arrayOf(sticky.id.toString())).use {
                it.moveToFirst() && (it.getLong(0) < 0) == (index == 0)
            })
        }
        open("concurrent", text)
        returnCase("return-concurrent", visual = true, during = {
            update(text, ContentValues().apply { put("content", "动画中再次更新") })
            update(other, ContentValues().apply { put("content", "动画中其他记事再次更新") })
            refresh()
            refresh()
        })
        checkResult("return-concurrent-published", main {
            manager.getThingById(text.id)?.content == "动画中再次更新" &&
                manager.getThingById(other.id)?.content == "动画中其他记事再次更新"
        })
        open("partial", text, true)
        returnCase("return-partial", visual = true)
        open("offscreen", text)
        main { update(text, ContentValues().apply { put("location", 1L) }); App.setJustNotifyAll(true) }
        returnCase("return-offscreen", expectedTarget = false, visual = true)
        val deleted = fixture("删除后没有落点")
        open("deleted", deleted)
        val deletePage = detail!!
        returnCase("return-deleted", expectedTarget = false, action = {
            check(field<Thing>(deletePage, "mThing").id in created)
            deletePage.javaClass.getDeclaredMethod("returnToThingsActivity", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(deletePage, Thing.DELETED)
        })
        checkResult("return-delete-state", DBHelper(targetContext).readableDatabase.rawQuery(
            "SELECT state FROM things WHERE id=?", arrayOf(deleted.id.toString())).use {
            it.moveToFirst() && it.getInt(0) == Thing.DELETED
        })
        checkResult("return-delete-undo-retained", main {
            field<List<Thing>>(home, "mUndoThings").any { it.id == deleted.id }
        })
        val long = fixture("长详情", content = List(100) { "第 $it 行正文。" }.joinToString("\n"))
        open("scrolled", long)
        main {
            detail!!.findViewById<RecyclerView>(R.id.sv_detail)?.scrollBy(0, 1200)
        }
        returnCase("return-scrolled")
        open("private", fixture(Thing.PRIVATE_THING_PREFIX + "返回私密模拟").also {
            main { manager.markThingPrivacyAuthenticated(it.id) }
        })
        returnCase("return-private-simulated")

        val invalidatePrivate = fixture(Thing.PRIVATE_THING_PREFIX + "私密失效模拟")
        main { manager.markThingPrivacyAuthenticated(invalidatePrivate.id) }
        open("private-invalidated", invalidatePrivate)
        returnCase("return-private-invalidated", expectedTarget = null, expectCancellation = true, during = {
            manager.clearThingPrivacyAuthenticated(invalidatePrivate.id)
            refresh()
        })
        checkResult("return-private-auth-removed", !manager.isThingPrivacyAuthenticated(invalidatePrivate.id))

        val lifecycleNote = fixture("生命周期返回模拟")
        open("pause", lifecycleNote)
        returnCase("return-paused", expectedTarget = null, expectCancellation = true, during = {
            home.moveTaskToBack(true)
        })
        // 从应用外部恢复任务；不能从已暂停的首页再次发起受输入会话管理的自启动。
        targetContext.startActivity(Intent(targetContext, ThingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        await("后台返回前台") { home.hasWindowFocus() }

        // 搜索条件变化使用现有首页搜索入口，只有模拟记录拥有这个关键词。
        val search = fixture("return-only-query", content = "搜索移出模拟")
        open("search-initial", search)
        close()
        main {
            home.javaClass.getDeclaredMethod("toggleSearching", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(home, false)
            field<android.widget.EditText>(home, "mEtSearch").setText("return-only-query")
            com.ywwynm.everythingdone.utils.KeyboardUtil.hideKeyboard(home.currentFocus)
        }
        await("搜索匹配列表") { manager.getListPositionForThingId(search.id) >= 0 && !list.hasPendingAdapterUpdates() &&
            !list.isLayoutRequested && list.itemAnimator?.isRunning != true }
        val searchCard = main { list.findViewHolderForAdapterPosition(manager.getListPositionForThingId(search.id))!!.itemView }
        main { searchCard.performClick() }
        await("搜索详情") { detail?.hasWindowFocus() == true &&
            field<ThingDetailTransition?>(detail!!, "detailTransition")?.isRunning != true }
        main { update(search, ContentValues().apply { put("title", "不再匹配关键词") }); App.setJustNotifyAll(true) }
        returnCase("return-filter-removed", expectedTarget = false)
        checkResult("return-search-empty", main { manager.getListPositionForThingId(search.id) == -1 })
        main {
            if (App.isSearching) home.javaClass.getDeclaredMethod("toggleSearching", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(home, false)
        }

        val db = DBHelper(targetContext).writableDatabase
        val folderId = db.rawQuery("SELECT COALESCE(MAX(id),0)+1 FROM thing_folders", null)
            .use { it.moveToFirst(); it.getLong(0) }
        val folder = ThingFolder(folderId, null, "返回缩略图测试", Thing.UNDERWAY, Color.BLUE,
            fixtureTime, createTime = fixtureTime, cardPresentation = ThingFolderCardPresentation(
                mode = ThingFolderCardPresentation.MODE_THUMBNAILS))
        check(ThingFolderDAO.getInstance(targetContext)!!.create(folder) != -1L)
        createdFolders += folderId
        File(output, "folder-fixtures.json").writeText(JSONObject().put("create_time", fixtureTime)
            .put("ids", JSONArray(createdFolders)).toString())
        val child = fixture("缩略记事返回")
        main {
            update(child, ContentValues().apply { put("folder_id", folderId) })
            manager.navigateToFolderPathIndex(-1, false)
            (targetContext.applicationContext as App).setStatus(Thing.UNDERWAY, true)
            home.javaClass.getDeclaredMethod("refreshExternalProjectionUi", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(home, false)
        }
        await("文件夹卡片布局") { !list.isLayoutRequested && !list.hasPendingAdapterUpdates() && list.itemAnimator?.isRunning != true }
        main {
            (list.layoutManager as StaggeredGridLayoutManager).scrollToPositionWithOffset(
                manager.getListPositionForFolderId(folderId), 160)
        }
        fun thumbnail(): View? = descendants(list).firstOrNull {
            it.getTag(R.id.tag_thing_card_bound_stable_id) == child.id && it.isShown
        }?.findViewById(R.id.cv_thing)
        await("缩略记事可见") { thumbnail()?.getGlobalVisibleRect(Rect()) == true }
        main { thumbnail()!!.performClick() }
        await("缩略记事详情") { detail?.hasWindowFocus() == true &&
            field<ThingDetailTransition?>(detail!!, "detailTransition")?.isRunning != true }
        returnCase("return-thumbnail", visual = true)
        await("缩略记事恢复") { thumbnail()?.getGlobalVisibleRect(Rect()) == true }
        main { thumbnail()!!.performClick() }
        await("缩略记事再次详情") { detail?.hasWindowFocus() == true &&
            field<ThingDetailTransition?>(detail!!, "detailTransition")?.isRunning != true }
        main {
            folder.cardPresentation = ThingFolderCardPresentation()
            ThingFolderDAO.getInstance(targetContext)!!.update(folder)
            App.setJustNotifyAll(true)
        }
        returnCase("return-thumbnail-removed", expectedTarget = false, visual = true)
        open("folder-child", Thing(child).apply { this.folderId = folderId })
        main {
            update(child, ContentValues().apply { putNull("folder_id") })
            App.setJustNotifyAll(true)
        }
        returnCase("return-folder-removed", expectedTarget = false)

        val slow = fixture("等待保存完成")
        open("slow-save", slow)
        val slowPage = detail!!
        val writeGate = java.util.concurrent.CountDownLatch(1)
        field<java.util.concurrent.ExecutorService>(manager, "mExecutor").execute {
            writeGate.await(2, TimeUnit.SECONDS)
        }
        writer.execute { SystemClock.sleep(320); writeGate.countDown() }
        returnCase("return-slow-save", expectedTarget = false, action = {
            slowPage.javaClass.getDeclaredMethod("returnToThingsActivity", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(slowPage, Thing.FINISHED)
        })
        checkResult("return-slow-save-committed", DBHelper(targetContext).readableDatabase.rawQuery(
            "SELECT state FROM things WHERE id=?", arrayOf(slow.id.toString())).use {
            it.moveToFirst() && it.getInt(0) == Thing.FINISHED
        })

        val rotating = fixture("旋转中断返回")
        open("rotate-return", rotating)
        try {
            returnCase("return-rotated", expectedTarget = null, expectCancellation = true, during = {
                automation.setRotation((baseRotation + 1) % 4)
            })
            await("返回窗口旋转") { home.resources.configuration.orientation != baseOrientation }
        } finally {
            automation.setRotation(baseRotation)
        }
        await("返回窗口恢复") { home.resources.configuration.orientation == baseOrientation && !list.isLayoutRequested }
        open("after-cancel", rotating)
        returnCase("return-after-cancel")
    }

    /** 固定动画时刻调用真实绘制路径，检查运动中不透明卡片有没有提前透出最终列表。 */
    private fun returnMotionPixels(transition: ThingDetailReturnTransition, name: String) {
        val layer = field<View>(transition, "layer")
        val animator = field<android.animation.ValueAnimator>(transition, "animator")
        val originalTime = animator.currentPlayTime
        val originalProgress = field<Float>(layer, "progress")
        val frames = listOfNotNull(field<ThingDetailReturnTransition.Frame>(transition, "source"),
            field<ThingDetailReturnTransition.Frame?>(transition, "destination"))
        val source = frames.first()
        val end = frames.last()
        val originals = frames.map { it.pixels }
        val scene = field<Bitmap?>(transition, "listScene")
        val readableScene = if (scene?.config == Bitmap.Config.HARDWARE) scene.copy(Bitmap.Config.ARGB_8888, false) else scene
        val steps = JSONArray()
        var checkedPixels = 0
        var largestColorError = 0
        val body = field<List<Pair<ThingDetailReturnTransition.Part, ThingDetailReturnTransition.Part>>>(layer, "matches")
            .firstOrNull { it.first.role == 1 }
        val bodyPixels = body?.let { it.first.pixels to it.second.pixels }
        fun tint(pixels: Bitmap?, color: Int) = pixels?.let {
            Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888).also { tinted ->
                Canvas(tinted).drawBitmap(it, 0f, 0f, Paint().apply {
                    colorFilter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
                })
            }
        }
        val sourceInk = tint(bodyPixels?.first, Color.RED)
        val targetInk = tint(bodyPixels?.second, Color.GREEN)
        val ink = Bitmap.createBitmap(layer.width / 2, layer.height / 2, Bitmap.Config.ARGB_8888)
        val inkPixels = IntArray(ink.width * ink.height)
        var overlappingTextFrames = 0
        val drawPart = layer.javaClass.getDeclaredMethod("drawPart", Canvas::class.java,
            ThingDetailReturnTransition.Part::class.java, ThingDetailReturnTransition.Part::class.java)
            .apply { isAccessible = true }
        animator.pause()
        try {
            frames.forEach { frame -> if (frame.pixels?.config == Bitmap.Config.HARDWARE)
                frame.pixels = frame.pixels!!.copy(Bitmap.Config.ARGB_8888, false) }
            setField(transition, "listScene", readableScene)
            // 单独保存去除目标后的背景，排除背景捕获重复卡片。
            readableScene?.copy(Bitmap.Config.ARGB_8888, false)?.let { bitmap ->
                writer.execute {
                    File(output, "$name-background.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            for (time in listOf(36L, 72L, 108L, 135L, 162L, 198L, 225L, 255L, 285L, 300L)) {
                setField(layer, "progress", 0f)
                animator.currentPlayTime = time
                val p = transition.progress
                val bitmap = Bitmap.createBitmap(layer.width / 2, layer.height / 2, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.scale(.5f, .5f)
                layer.draw(canvas)
                val box = android.graphics.RectF(
                    source.rect.left + (end.rect.left - source.rect.left) * p,
                    source.rect.top + (end.rect.top - source.rect.top) * p,
                    source.rect.right + (end.rect.right - source.rect.right) * p,
                    source.rect.bottom + (end.rect.bottom - source.rect.bottom) * p)
                val step = JSONObject().put("time_ms", time).put("progress", p).put("box", box.toString())
                if (body != null && sourceInk != null && targetInk != null) {
                    // 使用真实两端排版，只改变墨色以识别同一帧是否叠画了两份不同换行的正文。
                    ink.eraseColor(Color.TRANSPARENT)
                    try {
                        body.first.pixels = sourceInk
                        body.second.pixels = targetInk
                        drawPart.invoke(layer, Canvas(ink).apply { scale(.5f, .5f) }, body.first, body.second)
                    } finally {
                        body.first.pixels = bodyPixels!!.first
                        body.second.pixels = bodyPixels.second
                    }
                    ink.getPixels(inkPixels, 0, ink.width, 0, 0, ink.width, ink.height)
                    val sourceVisible = inkPixels.any { Color.alpha(it) > 12 && Color.red(it) > 12 }
                    val targetVisible = inkPixels.any { Color.alpha(it) > 12 && Color.green(it) > 12 }
                    if (sourceVisible && targetVisible) overlappingTextFrames++
                    step.put("source_text_visible", sourceVisible).put("target_text_visible", targetVisible)
                }
                if (name.startsWith("ghost-short") && p > .82f && p < .995f) {
                    // 两端纯色相同；容器下部尚未收至真实卡片，且已经没有详情工具栏/文字。
                    val bottomOfPixels = box.top + end.rect.height() * box.width() / end.rect.width()
                    val top = maxOf(end.rect.bottom, bottomOfPixels)
                    if (box.bottom - top > 12f) {
                        val x = (box.centerX() / 2).toInt()
                        val y = ((box.bottom + top) / 4).toInt()
                        val actual = bitmap.getPixel(x, y)
                        val expected = Color.rgb(42, 83, 103)
                        val error = maxOf(kotlin.math.abs(Color.red(actual) - Color.red(expected)),
                            kotlin.math.abs(Color.green(actual) - Color.green(expected)),
                            kotlin.math.abs(Color.blue(actual) - Color.blue(expected)))
                        largestColorError = maxOf(largestColorError, error)
                        checkedPixels++
                        step.put("sample_x", x).put("sample_y", y).put("color_error", error)
                    }
                }
                steps.put(step)
                writer.execute {
                    File(output, "$name-motion-$time.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        } finally {
            frames.zip(originals).forEach { (frame, pixels) ->
                if (frame.pixels !== pixels) frame.pixels?.recycle()
                frame.pixels = pixels
            }
            setField(transition, "listScene", scene)
            if (readableScene !== scene) readableScene?.recycle()
            setField(layer, "progress", 0f)
            animator.currentPlayTime = originalTime
            setField(layer, "progress", originalProgress)
            animator.resume()
            sourceInk?.recycle(); targetInk?.recycle(); ink.recycle()
        }
        File(output, "$name-motion.json").writeText(steps.toString(2))
        if (name.startsWith("ghost-short")) checkResult("$name-opaque-moving-card",
            checkedPixels > 0 && largestColorError <= 4,
            JSONObject().put("checked_pixels", checkedPixels).put("max_color_error", largestColorError))
        checkResult("$name-single-text-layout", body != null && overlappingTextFrames == 0,
            JSONObject().put("overlapping_frames", overlappingTextFrames))
    }

    private fun returnEndpointPixels(transition: ThingDetailReturnTransition, name: String) {
        val layer = field<View>(transition, "layer")
        val originalProgress = field<Float>(layer, "progress")
        val frames = listOfNotNull(field<ThingDetailReturnTransition.Frame>(transition, "source"),
            field<ThingDetailReturnTransition.Frame?>(transition, "destination"))
        val originals = frames.map { it.pixels }
        val scene = field<Bitmap?>(transition, "listScene")
        val readableScene = if (scene?.config == Bitmap.Config.HARDWARE) scene.copy(Bitmap.Config.ARGB_8888, false) else scene
        val decor = home.window.decorView
        val end = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val actual = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        try {
            frames.forEach { frame -> if (frame.pixels?.config == Bitmap.Config.HARDWARE)
                frame.pixels = frame.pixels!!.copy(Bitmap.Config.ARGB_8888, false) }
            setField(transition, "listScene", readableScene)
            setField(layer, "progress", 1f)
            layer.draw(Canvas(end))
            layer.visibility = View.INVISIBLE
            decor.draw(Canvas(actual))
        } finally {
            frames.zip(originals).forEach { (frame, pixels) ->
                if (frame.pixels !== pixels) frame.pixels?.recycle()
                frame.pixels = pixels
            }
            setField(transition, "listScene", scene)
            if (readableScene !== scene) readableScene?.recycle()
            setField(layer, "progress", originalProgress)
            layer.visibility = View.VISIBLE
        }
        writer.execute {
            File(output, "$name-endpoint.png").outputStream().use { end.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(output, "$name-real.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            end.recycle(); actual.recycle()
        }
    }

    private fun close() {
        detail?.let { main { setField(it, "dontSaveAfterOnPause", true); it.finish() } }
        if (detail != null && ::home.isInitialized) await("返回首页") { home.hasWindowFocus() }
        detail = null
    }

    private fun checkResult(name: String, passed: Boolean, extra: JSONObject = JSONObject()) {
        if (!passed) failures++
        samples.put(extra.put("name", name).put("passed", passed))
        File(output, "progress.json").writeText(JSONObject().put("failures", failures).put("samples", samples).toString())
    }

    private fun snapshotRows(): Map<Long, String> {
        val db = DBHelper(targetContext).readableDatabase
        return db.rawQuery("SELECT * FROM things WHERE type<>-1 ORDER BY id", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    for (i in 0 until cursor.columnCount) {
                        digest.update((if (cursor.isNull(i)) "<null>" else cursor.getString(i)).toByteArray())
                        digest.update(0.toByte())
                    }
                    put(cursor.getLong(cursor.getColumnIndexOrThrow("id")), digest.digest().joinToString("") { "%02x".format(it) })
                }
            }
        }
    }

    private fun inputReady(activity: Activity): Boolean {
        val session = field<Map<Activity, Any>>(UiInteractions, "sessions")[activity] ?: return true
        val state = field<UiTransitionState>(session, "state")
        return state.canInteract(state.root)
    }

    private fun recoverFixtures(): Int {
        val file = File(targetContext.getExternalFilesDir(null), "detail-transition/recovery.json")
        val manifest = JSONObject(file.readText())
        require(manifest.getString("owner") in setOf("thing-detail-transition-final1", "thing-detail-return-expanded"))
        val rows = manifest.getJSONArray("rows")
        val hashes = snapshotRows()
        manifest.optJSONObject("baseline")?.let { baseline ->
            val fixtureIds = (0 until rows.length()).map { rows.getJSONObject(it).getLong("id") }.toSet()
            check(hashes.keys - fixtureIds == baseline.keys().asSequence().map(String::toLong).toSet())
            baseline.keys().forEach { id -> check(hashes[id.toLong()] == baseline.getString(id)) }
        }
        val db = DBHelper(targetContext).writableDatabase
        db.beginTransaction()
        try {
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val id = row.getLong("id")
                check(hashes[id] == row.getString("sha256")) { "恢复保护：记录 $id 已变化" }
                check(db.delete("things", "id=? AND create_time=?", arrayOf(id.toString(),
                    row.getLong("create_time").toString())) == 1)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        manifest.optJSONObject("counts")?.let { counts ->
            check(manifest.has("baseline"))
            targetContext.getSharedPreferences(Def.Meta.THINGS_COUNTS_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply { counts.keys().forEach { putInt(it, counts.getInt(it)) } }.commit()
        }
        return rows.length()
    }

    private fun screenshot(name: String) {
        automation.takeScreenshot()?.let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun capture(activity: Activity, name: String) {
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(activity.window, bitmap, { code ->
            if (code == PixelCopy.SUCCESS) writer.execute {
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            } else bitmap.recycle()
        }, Handler(Looper.getMainLooper()))
    }

    private fun endpointPixels(transition: ThingDetailTransition, activity: Activity) {
        val layer = field<View>(transition, "layer")
        val originalProgress = field<Float>(layer, "progress")
        val decor = activity.window.decorView
        val end = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val actual = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        // 仅视觉对照需要 CPU 像素。性能场景不执行此分支，生产转场保持硬件位图。
        val pending = field<Any>(transition, "pending")
        val scene = field<Bitmap?>(pending, "scene")
        val readable = if (scene?.config == Bitmap.Config.HARDWARE) scene.copy(Bitmap.Config.ARGB_8888, false) else scene
        try {
            setField(pending, "scene", readable)
            setField(layer, "progress", 1f)
            layer.draw(Canvas(end))
        } finally {
            setField(pending, "scene", scene)
            setField(layer, "progress", originalProgress)
            if (readable !== scene) readable?.recycle()
        }
        val content = activity.findViewById<View>(R.id.fl_root_detail)
        val xy = IntArray(2).also(content::getLocationInWindow)
        Canvas(actual).apply { translate(xy[0].toFloat(), xy[1].toFloat()); content.draw(this) }
        val targets = descendants(content).filterIsInstance<ImageView>()
            .filter { it.getTag(R.id.tag_detail_attachment_bound_source) != null }.toList()
        val infos = JSONArray(targets.map { view ->
            JSONObject().put("position", IntArray(2).also(view::getLocationInWindow).joinToString())
                .put("width", view.width).put("height", view.height).put("matrix", view.imageMatrix.toString())
                .put("drawable_bounds", view.drawable?.bounds?.toString())
        })
        writer.execute {
            File(output, "image-endpoint.png").outputStream().use { end.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(output, "image-actual.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(output, "image-geometry.json").writeText(infos.toString(2))
            end.recycle(); actual.recycle()
        }
    }

    private fun await(reason: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 16000
        var nextDialogCheck = SystemClock.uptimeMillis() + 1000
        while (!main(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "等待超时：$reason" }
            // 无障碍整树查询会打扰动画采样；仅在持续等待时检查遮挡弹窗。
            if (SystemClock.uptimeMillis() >= nextDialogCheck) {
                nextDialogCheck = SystemClock.uptimeMillis() + 1000
                val root = automation.rootInActiveWindow
                if (root?.findAccessibilityNodeInfosByText("Android 应用兼容性")?.isNotEmpty() == true) {
                    root.findAccessibilityNodeInfosByViewId("android:id/button2").firstOrNull()
                        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                if (root?.findAccessibilityNodeInfosByViewId("${targetContext.packageName}:id/tv_title_alert")
                        ?.any { it.text?.contains("QAQ") == true } == true) {
                    root.findAccessibilityNodeInfosByViewId("${targetContext.packageName}:id/tv_cancel_as_bt_alert")
                        .firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            SystemClock.sleep(16)
        }
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(instance: Any, name: String): T =
        generateSequence<Class<*>>(instance.javaClass) { it.superclass }
            .firstNotNullOf { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .apply { isAccessible = true }.get(instance) as T
    private fun setField(instance: Any, name: String, value: Any?) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }
}
