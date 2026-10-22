package com.ywwynm.everythingdone.detail

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.Window
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.viewpager.widget.ViewPager
import com.github.chrisbanes.photoview.PhotoView
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.activities.ImageViewerActivity
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.hdr.HdrProbeImage
import com.ywwynm.everythingdone.hdr.HdrViewerDiagnostics
import com.ywwynm.everythingdone.hdr.HdrProbeSurface
import com.ywwynm.everythingdone.hdr.HdrGpuData
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.DetailContentLayout
import com.ywwynm.everythingdone.helpers.MotionPhotoDetector
import com.ywwynm.everythingdone.helpers.MediaPreviewThumbnail
import com.ywwynm.everythingdone.helpers.AnimatedMediaCropDrawable
import com.ywwynm.everythingdone.helpers.DetailAttachmentPlaybackController
import com.ywwynm.everythingdone.helpers.MediaCropBitmapRenderer
import com.ywwynm.everythingdone.helpers.HdrImageDetector
import com.ywwynm.everythingdone.helpers.ThingPrivacyResolver
import com.ywwynm.everythingdone.interaction.UiInteractions
import com.ywwynm.everythingdone.interaction.UiTransitionState
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.DetailAutoplayMode
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
import com.ywwynm.everythingdone.views.MediaPreviewTransition
import com.ywwynm.everythingdone.views.MediaPreviewDismissGesture
import com.ywwynm.everythingdone.views.ThingDetailTransition
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** 真实详情附件回调与真实查看器；原记事只读，修改和删除仅作用于本次夹具。 */
class MediaPreviewTransitionProbe : Instrumentation() {
    private var args = Bundle()
    private lateinit var output: File
    private var detail: DetailActivity? = null
    @Volatile private var viewer: ImageViewerActivity? = null
    private val created = mutableListOf<Long>()
    private val stamp = System.currentTimeMillis()
    private val checks = JSONArray()
    private val runs = JSONArray()
    private val resolutions = JSONArray()
    private val events = CopyOnWriteArrayList<String>()
    private val timeline = JSONArray()
    private val continuity = JSONArray()
    private val previewCopies = JSONArray()
    private val motionSnapshots = JSONArray()
    private val motionEdges = JSONArray()
    private val videoStartups = JSONArray()
    private val pqHandoffFrames = JSONArray()
    private val pqHandoffs = JSONArray()
    private val interactiveFrames = JSONArray()
    private var pqPhase = "opening"
    private val pqObservers = mutableMapOf<Activity, android.view.Choreographer.FrameCallback>()
    private var requirePlayingReturn = false
    private var continuityKey: String? = null
    private val continuityDraw = android.view.ViewTreeObserver.OnPreDrawListener {
        detail?.let { page ->
            val image = page.findMediaPreviewSource(continuityKey)?.findViewById<ImageView>(R.id.iv_image_attachment)
            val contrast = field<Any?>(page, "mToolbarContrast")
            continuity.put(JSONObject().put("name", label).put("time", System.nanoTime())
                .put("dark", contrast?.let { field<Boolean?>(it, "toolbarDark") })
                .put("alpha", image?.alpha).put("parentAlpha", (image?.parent as? View)?.alpha)
                .put("drawable", image?.drawable?.let { System.identityHashCode(it) })
                .put("gifFrame", (image?.drawable as? com.bumptech.glide.load.resource.gif.GifDrawable)?.frameIndex)
                .put("gifFrameCount", (image?.drawable as? com.bumptech.glide.load.resource.gif.GifDrawable)?.frameCount)
                .put("width", image?.drawable?.intrinsicWidth).put("height", image?.drawable?.intrinsicHeight))
        }
        true
    }
    private val windowFrames = CopyOnWriteArrayList<JSONArray>()
    private val captureWriters = CopyOnWriteArrayList<Thread>()
    private val measuredWindows = mutableMapOf<Window, Window.OnFrameMetricsAvailableListener>()
    private var metricsThread: HandlerThread? = null
    private var sourceRect: RectF? = null
    private var targetRect: RectF? = null
    private var label = ""
    private var captureIndex = 0
    private var screenshots = false
    private var failures = 0
    private var mediaDeferred = true
    private var requestedNs = 0L
    private var expectedPreviewCorners: IntArray? = null
    private var forceEnterTimeout = false
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is ImageViewerActivity) viewer = activity
            if (activity is ImageViewerActivity && args.getString("case") == "real-pq-handoff") observePqHandoff(activity)
            if (activity is DetailActivity) set(activity, "dontSaveAfterOnPause", true)
            metricsThread?.let { thread ->
                if (activity is ImageViewerActivity || activity is DetailActivity) {
                    val listener = Window.OnFrameMetricsAvailableListener { _, frame, dropped ->
                        windowFrames += JSONArray(listOf(activity.javaClass.simpleName,
                            frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                            frame.getMetric(FrameMetrics.TOTAL_DURATION),
                            if (android.os.Build.VERSION.SDK_INT >= 31) frame.getMetric(FrameMetrics.DEADLINE) else -1L,
                            frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME), dropped))
                    }
                    measuredWindows[activity.window] = listener
                    activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                }
            }
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            pqObservers.remove(activity)?.let(android.view.Choreographer.getInstance()::removeFrameCallback)
            measuredWindows.remove(activity.window)?.let(activity.window::removeOnFrameMetricsAvailableListener)
        }
    }

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }

    override fun callActivityOnCreate(activity: Activity, state: Bundle?) {
        super.callActivityOnCreate(activity, state)
        if (activity is ImageViewerActivity && args.getString("case") == "real-pq-handoff") {
            val delay = args.getString("pqPrepareDelay", "0").toLong()
            if (delay > 0) field<Any?>(activity, "mPqController")?.let { controller ->
                field<java.util.concurrent.ScheduledThreadPoolExecutor>(controller, "io").submit { SystemClock.sleep(delay) }
            }
        }
    }

    override fun onStart() {
        val real = args.getString("case")?.startsWith("real") == true
        output = File(targetContext.getExternalFilesDir(null), if (real) "media-preview-transition-real" else "media-preview-transition").apply { mkdirs() }
        if (!real) recoverFixtures()
        screenshots = args.getString("screenshots") == "true"
        val report = JSONObject()
        val baseline = rows()
        File(output, "baseline.json").writeText(JSONObject(baseline.mapKeys { it.key.toString() }).toString())
        val app = targetContext.applicationContext as Application
        if (real) metricsThread = HandlerThread("media-transition-frames").apply { start() }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            val files = if (real) emptyList() else media()
            main {
                MediaPreviewThumbnail.diagnosticCopy = { width, height, micros ->
                    previewCopies.put(JSONObject().put("sourceWidth", width).put("sourceHeight", height).put("micros", micros))
                }
                HdrViewerDiagnostics.forceCompatibility = args.getString("compatibility") == "true"
                MediaPreviewTransition.diagnosticObserver = { event ->
                    events += event
                    timeline.put(JSONArray(listOf(event, System.nanoTime())))
                    if (event in listOf("return-capture", "return-interactive-start") && requirePlayingReturn) {
                        check("$label 捕获发起时仍在播放动态", motionIsPlaying())
                    }
                    if (event == "enter-attached" && (args.getString("case") == "timeout" || forceEnterTimeout)) {
                        viewer?.window?.decorView?.post {
                            transition()?.let { transition -> field<Any?>(transition, "animation")?.let {
                                field<Runnable>(it, "timeout").run()
                            } }
                        }
                    }
                    if (event == "enter-prepared") expectedPreviewCorners?.let { colors ->
                        val bitmap = (photo().drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        check("$label 内嵌预览方向与构图正确", bitmap != null && cornersMatch(bitmap, colors))
                        check("$label 内嵌预览不标记为高清", photo() !in field<Set<PhotoView>>(viewer!!, "mFullResolutionImages"))
                    }
                    if (event.startsWith("enter-progress:") || event.startsWith("return-progress:")) {
                        if (event.startsWith("enter-progress:")) viewer?.let {
                            mediaDeferred = mediaDeferred && field<Any?>(it, "mMotionPlayer") == null &&
                                it.window.colorMode != ActivityInfo.COLOR_MODE_HDR
                        }
                        val progress = event.substringAfter(':').toFloat()
                        if (screenshots && captureIndex < 3 && progress >= listOf(.05f, .45f, .9f)[captureIndex]) {
                            val host = if (event.startsWith("enter") || events.contains("return-interactive-start")) viewer else detail
                            host?.let { capture(it, "$label-${captureIndex++}") }
                        }
                    }
                }
                MediaPreviewTransition.diagnosticGeometry = { _, from, to ->
                    sourceRect = from?.let(::RectF); targetRect = to?.let(::RectF)
                }
                MediaPreviewTransition.diagnosticImageGeometry = { entering, width, height, from, to ->
                    val prefix = if (entering) "enter" else "return"
                    events += "$prefix-image:$width,$height,$from,$to"
                    check("$label 完整图像两端等比", kotlin.math.abs(from.width() / from.height() - to.width() / to.height()) < .01f)
                }
                MediaPreviewTransition.diagnosticInteractiveFrame = { progress, elapsedMs, image, clip ->
                    interactiveFrames.put(JSONObject().put("name", label).put("timeNs", System.nanoTime())
                        .put("progress", progress).put("elapsedMs", elapsedMs).put("x", image.centerX()).put("y", image.centerY())
                        .put("width", image.width()).put("height", image.height())
                        .put("clip", JSONArray(listOf(clip.left, clip.top, clip.right, clip.bottom))))
                }
            }
            val only = args.getString("case")
            if (real) realCases(report, only == "real-inventory")
            if (only == "generic") genericCases()
            if (only == "animated-webp") animatedWebpCases()
            if (only == "embedded") embeddedCases(files)
            if (only == "dismiss") dismissCases(files)
            if (only == "timeout") {
                for ((index, name) in listOf(0 to "sdr-timeout", 4 to "hdr-timeout")) {
                    openDetail(listOf(files[index]))
                    reset(name)
                    main { source(0).performClick() }
                    await("$name 准备超时") { events.contains("enter-timeout") && viewer?.hasWindowFocus() == true }
                    check("$name 超时释放输入", main { inputReady(viewer!!) })
                    check("$name 超时解除媒体阻塞", main { !field<Boolean>(viewer!!, "mPreviewTransitionBlocked") })
                    verifyResolution(name, files[index])
                    closePreview(name)
                    closeDetail()
                }
            }
            if (only == "cropped") {
                openDetail(listOf(files[4]), cropped = true)
                openPreview(0, "cropped")
                closePreview("cropped")
                closeDetail()
            }
            for ((index, name) in listOf("image", "gif", "video", "motion", "hdr").withIndex()) {
                if (only != null && only != name) continue
                openDetail(listOf(files[index]))
                openPreview(0, name)
                val current = viewer!!
                if (name in listOf("image", "motion", "hdr")) verifyResolution(name, files[index])
                when (name) {
                    "gif" -> await("GIF 播放恢复") { (photo().drawable as? Animatable)?.isRunning == true }
                    "video", "motion" -> {
                        await("$name 自动播放") { field<Any?>(current, "mMotionPlayer") != null && field<Boolean>(current, "mMotionRevealed") }
                        check("$name 播放晚于展开", events.indexOf("enter-finished") >= 0)
                    }
                    "hdr" -> if (args.getString("compatibility") != "true") {
                        await("原生 HDR 恢复") { current.window.colorMode == ActivityInfo.COLOR_MODE_HDR }
                    }
                }
                if (name == "image") main { photo().setScale(3f, false) }
                closePreview(name)
                closeDetail()
            }
            if (only == null || only == "edge" || only == "remaining") edgeCases(files)
            if (only == "resolution") {
                val still = File(files[0].substring(1))
                val motion = File(still.parentFile, "motion-sdr.jpg")
                if (!motion.exists()) motion.outputStream().use { out ->
                    still.inputStream().use { it.copyTo(out) }
                    File(files[2].substring(1)).inputStream().use { it.copyTo(out) }
                }
                check("普通动态照片夹具不含 HDR", !HdrImageDetector.detect(motion.path) && MotionPhotoDetector.detect(motion.path).isMotionPhoto)
                for ((name, key) in listOf("sdr-motion" to "0${motion.path}", "hdr-motion" to files[3], "hdr" to files[4])) {
                    openDetail(listOf(key))
                    repeat(2) { iteration ->
                        val run = "$name-$iteration"
                        openPreview(0, run)
                        verifyResolution(run, key)
                        if (name.contains("motion")) {
                            await("$run 自动播放") { field<Boolean>(viewer!!, "mMotionRevealed") }
                            await("$run 播放结束") { field<Any?>(viewer!!, "mMotionPlayer") == null }
                            verifyResolution("$run-after-playback", key)
                        }
                        main { photo().setScale(2f, false) }
                        check("$run 图片可缩放", main { photo().scale > 1.9f })
                        closePreview(run)
                    }
                    closeDetail()
                }
            }
            if (only == null || only == "pq" || only == "remaining") {
                main { HdrViewerDiagnostics.forceCompatibility = true }
                openDetail(listOf(files[4]))
                openPreview(0, "pq-hdr")
                await("PQ HDR 首帧") { field<Any?>(viewer!!, "mPqController")?.let {
                    field<HdrProbeSurface?>(it, "surface")?.isFrameReady
                } == true }
                verifyResolution("pq-hdr", files[4])
                closePreview("pq-hdr")
                closeDetail()
                main { HdrViewerDiagnostics.forceCompatibility = false }
            }
        } catch (failure: Throwable) {
            failures++
            report.put("error", failure.stackTraceToString())
        } finally {
            main {
                MediaPreviewThumbnail.diagnosticCopy = null
                MediaPreviewTransition.diagnosticObserver = null
                MediaPreviewTransition.diagnosticGeometry = null
                MediaPreviewTransition.diagnosticImageGeometry = null
                MediaPreviewTransition.diagnosticInteractiveFrame = null
                HdrViewerDiagnostics.forceCompatibility = false
                pqObservers.values.forEach(android.view.Choreographer.getInstance()::removeFrameCallback)
                pqObservers.clear()
                viewer?.let { set(it, "mPreviewTransition", null); it.finish() }
                detail?.let { set(it, "dontSaveAfterOnPause", true); it.finish() }
                measuredWindows.forEach { (window, listener) -> window.removeOnFrameMetricsAvailableListener(listener) }
                measuredWindows.clear()
            }
            waitForIdleSync()
            captureWriters.forEach { it.join(5000) }
            uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
            uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
            val db = DBHelper(targetContext).writableDatabase
            for (id in created) db.delete("things", "id=? AND create_time=?", arrayOf(id.toString(), stamp.toString()))
            check("原有记事逐行摘要不变", baseline == rows())
            app.unregisterActivityLifecycleCallbacks(callbacks)
            saveRun()
            report.put("checks", checks).put("runs", runs).put("failures", failures).put("originalRows", baseline.size)
                .put("resolutions", resolutions)
                .put("continuity", continuity)
                .put("previewCopies", previewCopies)
                .put("motionSnapshots", motionSnapshots)
                .put("motionEdges", motionEdges)
                .put("videoStartups", videoStartups)
                .put("pqHandoffs", pqHandoffs).put("pqHandoffFrames", pqHandoffFrames)
                .put("interactiveFrames", interactiveFrames)
                .put("events", JSONArray(events)).put("device", android.os.Build.MODEL)
                .put("windowFrameColumns", JSONArray(listOf("window", "intendedVsyncNs", "totalNs", "deadlineNs", "firstDraw", "droppedCallbacks")))
                .put("windowFrames", JSONArray(windowFrames.toList()))
            metricsThread?.quitSafely()
            File(output, "result.json").writeText(report.toString(2))
            finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("result", report.toString()) })
        }
    }

    private fun edgeCases(files: List<String>) {
        openDetail(listOf(files[0], files[1], files[2], files[4]))
        openPreview(0, "page-switch")
        main { viewer!!.findViewById<ViewPager>(R.id.vp_image_viewer).setCurrentItem(1, false) }
        await("翻页 GIF 就绪") { (photo().drawable as? Animatable)?.isRunning == true }
        closePreview("page-switch", expectedKey = files[1])
        // 删除使用真实菜单回调的业务入口；只在本次模拟记事内进行。
        openPreview(0, "delete")
        main {
            val page = viewer!!
            val toolbar = page.findViewById<androidx.appcompat.widget.Toolbar>(R.id.actionbar)
            page.onOptionsItemSelected(toolbar.menu.findItem(R.id.act_delete_attachment))
        }
        await("删除确认框就绪") { (viewer!!.supportFragmentManager.findFragmentByTag(AlertDialogFragment.TAG)
            as? AlertDialogFragment)?.dialog?.findViewById<View>(R.id.tv_confirm_as_bt_alert)?.isShown == true }
        main { (viewer!!.supportFragmentManager.findFragmentByTag(AlertDialogFragment.TAG) as AlertDialogFragment)
            .dialog!!.findViewById<View>(R.id.tv_confirm_as_bt_alert).performClick() }
        await("删除完成并恢复输入") { field<ArrayList<String>>(viewer!!, "mTypePathNames").size == 3 && inputReady(viewer!!) }
        main { viewer!!.findViewById<ViewPager>(R.id.vp_image_viewer).setCurrentItem(2, false) }
        verifyResolution("delete-next-hdr", files[4])
        closePreview("delete", expectedKey = files[4], expectStableResource = false)
        check("删除后详情附件结果正确", main { field<ImageAttachmentAdapter>(detail!!, "mImageAttachmentAdapter").itemCount == 3 })
        closeDetail()

        openDetail(listOf(files[0]))
        reset("quick")
        main { source(0).performClick() }
        await("快速返回进入窗口") { viewer?.hasWindowFocus() == true && transition()?.isRunning == true }
        main {
            val toolbar = viewer!!.findViewById<androidx.appcompat.widget.Toolbar>(R.id.actionbar)
            val back = (0 until toolbar.childCount).map { toolbar.getChildAt(it) }.filterIsInstance<android.widget.ImageButton>().first()
            val rect = android.graphics.Rect(); back.getGlobalVisibleRect(rect)
            val now = SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, rect.centerX().toFloat(), rect.centerY().toFloat(), 0)
                viewer!!.window.callback.dispatchTouchEvent(event); event.recycle()
            }
        }
        await("快速返回完成") { detail?.hasWindowFocus() == true && inputReady(detail!!) }
        check("快速返回保留自定义收回", events.contains("return-prepared") && events.contains("return-finished"))
        closeDetail()

        openDetail(listOf(files[4]))
        uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
        await("详情横屏") { detail!!.resources.configuration.orientation == 2 && detail!!.isMediaPreviewLayoutReady() }
        openPreview(0, "landscape")
        check("横屏展开", main { viewer!!.window.decorView.width > viewer!!.window.decorView.height })
        closePreview("landscape")
        closeDetail()
        uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)

        openDetail(listOf(files[4]), cropped = true)
        openPreview(0, "cropped")
        closePreview("cropped")
        closeDetail()

        openDetail(listOf(files[0]))
        openPreview(0, "rotate-after-open")
        uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
        await("预览旋转完成") { viewer!!.resources.configuration.orientation == 2 && viewer!!.window.decorView.width > viewer!!.window.decorView.height }
        closePreview("rotate-after-open")
        closeDetail()
        uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)

        val offscreen = List(12) { index ->
            val copy = File(File(files[0].substring(1)).parentFile, "offscreen-$index.jpg")
            File(files[0].substring(1)).copyTo(copy, overwrite = true)
            "0${copy.path}"
        }
        openDetail(offscreen)
        openPreview(0, "offscreen")
        main { field<DetailContentLayout>(detail!!, "mDetailContent").recyclerView.scrollBy(0, 100000) }
        closePreview("offscreen", expectTarget = false)
        closeDetail()
    }

    private fun openDetail(media: List<String>, cropped: Boolean = false) {
        val db = DBHelper(targetContext).writableDatabase
        val id = db.rawQuery("SELECT MAX(id) FROM things", null).use { it.moveToFirst(); it.getLong(0) + 1 }
        db.execSQL("UPDATE things SET id=?, location=? WHERE type=-1", arrayOf(id + 1, id + 1))
        db.insertOrThrow("things", null, ContentValues().apply {
            put("id", id); put("type", Thing.NOTE); put("state", Thing.UNDERWAY)
            put("color", Color.rgb(48, 79, 110)); put("title", "媒体转场专用模拟")
            put("content", "仅用于附件预览回归，结束后自动清理。")
            put("attachment", AttachmentHelper.toAttachmentStr(media, emptyList()))
            if (cropped) put("detail_attachment_media_appearance", DetailAttachmentMediaAppearance().withSource(media[0],
                DetailAttachmentMediaAppearance.SourceAppearance(fullSpanEnabled = true, presentations = mapOf(
                    DetailAttachmentMediaAppearance.PRESENTATION_FULL_SPAN to DetailAttachmentMediaAppearance.MediaPresentationAppearance(
                        targetAspectRatio = 1.2, crop = DetailAttachmentMediaAppearance.DetailMediaCrop(centerX = .72, centerY = .35, scale = 1.8))
                ))).toJson())
            put("location", id); put("create_time", stamp); put("update_time", stamp); put("finish_time", 0L)
        })
        created += id
        File(output, "fixtures.json").writeText(JSONObject().put("stamp", stamp).put("ids", JSONArray(created)).toString())
        viewer = null
        detail = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext,
            "media-preview-probe", id, -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
        await("详情附件就绪") { detail!!.hasWindowFocus() && sourceOrNull(0)?.findViewById<ImageView>(R.id.iv_image_attachment)?.drawable != null }
    }

    private fun reset(name: String) = main {
        saveRun()
        label = name; captureIndex = 0; events.clear(); sourceRect = null; targetRect = null; mediaDeferred = true
        while (timeline.length() > 0) timeline.remove(timeline.length() - 1)
        timeline.put(JSONArray(listOf("requested", System.nanoTime())))
        requestedNs = timeline.getJSONArray(0).getLong(1)
    }

    private fun saveRun() = main {
        if (label.isNotEmpty()) runs.put(JSONObject().put("name", label).put("events", JSONArray(events))
            .put("timeline", JSONArray(timeline.toString())))
    }

    private data class RealMedia(val id: Long, val index: Int, val count: Int, val key: String,
                                 val info: JSONObject)

    /** 只读扫描真实附件，不导出标题、正文和文件名；保留用户原始外观及播放配置。 */
    private fun realCases(report: JSONObject, inventoryOnly: Boolean) {
        val candidates = mutableListOf<RealMedia>()
        DBHelper(targetContext).readableDatabase.rawQuery(
            "SELECT * FROM things WHERE type BETWEEN 0 AND 3 AND state<2 AND attachment<>'' ORDER BY id", null).use { cursor ->
            while (cursor.moveToNext()) {
                val thing = Thing(cursor)
                if (thing.id == App.getDoingThingId() || ThingPrivacyResolver.isEffectivelyPrivate(targetContext, thing)) continue
                val items = AttachmentHelper.toAttachmentItems(thing.attachment).first.orEmpty().filterNotNull()
                for ((index, key) in items.withIndex()) {
                    val file = File(key.substring(1))
                    if (!file.isFile || file.path.contains("/media-preview-transition/") || file.path.contains("/detail-regression/")) continue
                    val video = key.startsWith("1")
                    val gif = file.extension.equals("gif", true)
                    val webp = file.extension.equals("webp", true) && !MediaPreviewThumbnail.isStaticImage(file.path)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    if (!video) BitmapFactory.decodeFile(file.path, bounds)
                    val info = JSONObject().put("id", thing.id).put("index", index).put("attachmentCount", items.size)
                        .put("bytes", file.length()).put("video", video).put("gif", gif).put("animatedWebp", webp)
                        .put("width", bounds.outWidth).put("height", bounds.outHeight)
                        .put("motion", !video && !gif && MotionPhotoDetector.detect(file.path).isMotionPhoto)
                        .put("hdr", !video && !gif && HdrImageDetector.detect(file.path))
                    candidates += RealMedia(thing.id, index, items.size, key, info)
                    if (thing.title == "新的测试君") info.put("namedSample", true)
                    if (thing.title == args.getString("noteTitle")) info.put("targetNote", true)
                }
            }
        }
        val selected = linkedMapOf<String, RealMedia>()
        report.put("realAnimatedWebpCount", candidates.count { it.info.getBoolean("animatedWebp") })
        fun select(name: String, value: RealMedia?) { value?.let { selected[name] = it } }
        fun List<RealMedia>.largest() = maxByOrNull { it.info.getLong("bytes") }
        select("largest-image", candidates.filter { !it.info.getBoolean("video") && !it.info.getBoolean("gif") }.largest())
        select("largest-pixels", candidates.filter { !it.info.getBoolean("video") }.maxByOrNull {
            it.info.getLong("width") * it.info.getLong("height") })
        select("largest-sdr", candidates.filter { !it.info.getBoolean("video") && !it.info.getBoolean("gif") &&
            !it.info.getBoolean("hdr") }.largest())
        if (args.getString("sample")?.contains("no-preview") == true || args.getString("case") == "real-pressure") {
            select("largest-no-preview", candidates.filter { !it.info.getBoolean("video") && !it.info.getBoolean("gif") }
                .sortedByDescending { it.info.getLong("width") * it.info.getLong("height") }.firstOrNull {
                    MediaPreviewThumbnail.decode(it.key.substring(1))?.let { preview -> preview.recycle(); false } ?: true
                })
        }
        select("largest-video", candidates.filter { it.info.getBoolean("video") }.largest())
        for (type in listOf("gif", "motion", "hdr")) select("largest-$type", candidates.filter { it.info.getBoolean(type) }.largest())
        select("hdr-motion", candidates.filter { it.info.getBoolean("hdr") && it.info.getBoolean("motion") }.largest())
        select("named-video", candidates.firstOrNull { it.info.optBoolean("namedSample") && it.info.getBoolean("video") })
        val many = candidates.maxByOrNull { it.count }
        select("many-first", many?.let { sample -> candidates.first { it.id == sample.id } })
        select("many-last", many?.let { sample -> candidates.last { it.id == sample.id } })
        candidates.filter { it.info.optBoolean("targetNote") && !it.info.getBoolean("video") }
            .forEach { select("target-photo-${it.index}", it) }
        val samples = selected.entries
            .filter { args.getString("sample") == null || it.key in args.getString("sample")!!.split(',') }
            .filter { args.getString("noteTitle") == null || it.value.info.optBoolean("targetNote") }
            .distinctBy { it.value.id to it.value.index }
        for ((_, sample) in samples) if (!sample.info.getBoolean("video") && !sample.info.getBoolean("gif")) {
            val started = System.nanoTime()
            runCatching {
                val exif = androidx.exifinterface.media.ExifInterface(sample.key.substring(1))
                val thumbnail = exif.thumbnailBitmap
                sample.info.put("embeddedPreviewWidth", thumbnail?.width).put("embeddedPreviewHeight", thumbnail?.height)
                    .put("orientation", exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1))
                thumbnail?.recycle()
            }.onFailure { sample.info.put("embeddedPreviewError", it.javaClass.simpleName) }
            sample.info.put("embeddedPreviewMs", (System.nanoTime() - started) / 1_000_000.0)
        }
        for ((_, sample) in samples) if (sample.info.getBoolean("video")) {
            runCatching {
                android.media.MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(sample.key.substring(1))
                    sample.info.put("width", retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull())
                        .put("height", retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull())
                        .put("durationMs", retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
                }
            }.onFailure { sample.info.put("metadataError", it.javaClass.simpleName) }
        }
        val summary = JSONObject().put("availableAttachments", candidates.size)
            .put("availableNotes", candidates.map { it.id }.distinct().size)
            .put("samples", JSONArray(samples.map { (name, sample) -> JSONObject(sample.info.toString()).put("name", name) }))
        report.put("realMedia", summary)
        File(output, "inventory.json").writeText(summary.toString(2))
        check("真实附件样本非空", samples.isNotEmpty())
        if (inventoryOnly) return
        if (args.getString("case") == "real-pressure") {
            val original = selected.getValue("largest-pixels")
            val source = File(original.key.substring(1))
            val before = fileDigest(source)
            try {
                val copy = File(targetContext.cacheDir, "media-preview-transition/no-thumbnail-large.jpg")
                copy.parentFile!!.mkdirs()
                source.copyTo(copy, overwrite = true)
                removeThumbnailDirectory(copy)
                val key = "0${copy.path}"
                check("派生压力样本没有内嵌预览", MediaPreviewThumbnail.decode(copy.path) == null)
                check("派生压力样本保持 HDR", HdrImageDetector.detect(copy.path) == original.info.getBoolean("hdr"))
                report.put("pressureSource", JSONObject(original.info.toString()).put("derived", true))
                for (cropped in listOf(false, true)) {
                    openDetail(listOf(key), cropped)
                    val run = "pressure-${if (cropped) "crop" else "default"}"
                    openPreview(0, run)
                    verifyResolution(run, key)
                    if (args.getString("quality") == "true" && original.info.getBoolean("hdr")) verifyHdrReady(run, key)
                    closePreview(run, key); closeDetail()
                }
            } finally { check("派生压力样本原附件 SHA-256 不变", fileDigest(source) == before) }
            return
        }
        val signatures = samples.associate { (_, sample) -> sample.key to fileDigest(File(sample.key.substring(1))) }
        try {
            for ((name, sample) in samples) {
                viewer = null
                detail = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext,
                    "media-preview-real", sample.id, -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
                await("真实详情就绪") { detail!!.hasWindowFocus() && detail!!.isMediaPreviewLayoutReady() }
                main {
                    val content = field<DetailContentLayout>(detail!!, "mDetailContent")
                    val adapter = field<ImageAttachmentAdapter>(detail!!, "mImageAttachmentAdapter")
                    require(adapter.getItems()?.get(sample.index) == sample.key)
                    content.layoutManager.scrollToPositionWithOffset(content.globalPosition(adapter, sample.index), 0)
                }
                await("真实附件就绪") { sourceOrNull(sample.index)?.let {
                    it.isShown && it.width > 0 && (it.findViewById<ImageView>(R.id.iv_image_attachment)?.drawable?.intrinsicWidth ?: 0) > 0 &&
                        detail!!.isMediaPreviewLayoutReady()
                } == true }
                repeat(args.getString("repeat", "2").toInt()) { iteration ->
                    val run = "real-$name-$iteration"
                    pqPhase = "opening"
                    openPreview(sample.index, run)
                    if (args.getString("case") == "real-pq-handoff") {
                        require(sample.info.getBoolean("hdr"))
                        verifyPqHandoff(run, sample.key, sample.info.getBoolean("motion"))
                        closePreview(run, expectedKey = sample.key)
                        check("$run 真实记事不变", baselineRow(sample.id) == rows()[sample.id])
                        return@repeat
                    }
                    if (args.getString("case") == "real-video-startup") {
                        require(sample.info.getBoolean("video"))
                        repeat(args.getString("startupRepeats", "1").toInt()) { playback -> verifyVideoStartup("$run-play$playback") }
                        closePreview(run, expectedKey = sample.key)
                        check("$run 真实记事不变", baselineRow(sample.id) == rows()[sample.id])
                        return@repeat
                    }
                    await("真实预览正常清晰度就绪") {
                        val image = photo()
                        val original = maxOf(sample.info.optInt("width"), sample.info.optInt("height"))
                        val required = minOf(original, maxOf(image.width, image.height) / 2)
                        image.drawable?.let { maxOf(it.intrinsicWidth, it.intrinsicHeight) >= required && required > 0 } == true
                    }
                    main { photo().drawable?.let { sample.info.put("decodedWidth", it.intrinsicWidth).put("decodedHeight", it.intrinsicHeight) } }
                    if (!sample.info.getBoolean("video") && !sample.info.getBoolean("gif")) verifyResolution(run, sample.key)
                    if (args.getString("case") == "real-dismiss") {
                        verifyInteractiveDismiss(run, sample.key, extensive = false)
                        check("$run 真实记事不变", baselineRow(sample.id) == rows()[sample.id])
                        return@repeat
                    }
                    if (args.getString("case") == "real-motion-edge") {
                        require(sample.info.getBoolean("motion") && sample.info.getBoolean("hdr"))
                        verifyMotionEdgeLifecycle(run, sample.key, iteration)
                        requirePlayingReturn = true
                        closePreview(run, expectedKey = sample.key)
                        requirePlayingReturn = false
                        check("$run 真实记事不变", baselineRow(sample.id) == rows()[sample.id])
                        return@repeat
                    }
                    if (args.getString("case") == "real-motion-return") {
                        require(sample.info.getBoolean("motion"))
                        if (iteration % 2 == 1) main {
                            viewer!!.javaClass.getDeclaredMethod("startMotionPlaybackForCurrentPage")
                                .apply { isAccessible = true }.invoke(viewer)
                        }
                        await("$run 动态正在播放") { motionIsPlaying() }
                        if (iteration % 2 == 1) main { photo().setScale(2f, false) }
                        verifyMotionSnapshot(run)
                        requirePlayingReturn = true
                        closePreview(run, expectedKey = sample.key)
                        requirePlayingReturn = false
                        check("$run 真实记事不变", baselineRow(sample.id) == rows()[sample.id])
                        return@repeat
                    }
                    if (sample.info.getBoolean("gif")) await("真实 GIF 播放") { (photo().drawable as? Animatable)?.isRunning == true }
                    if (sample.info.getBoolean("video") || sample.info.getBoolean("motion")) {
                        // 只等待真实用户配置允许的自动播放，不改全局偏好。
                        SystemClock.sleep(args.getString("dwell", "700").split(',').let { it[iteration % it.size].toLong() })
                        sample.info.put("playbackObserved", main { field<Boolean>(viewer!!, "mMotionRevealed") })
                    }
                    if (sample.info.getBoolean("hdr")) {
                        SystemClock.sleep(200)
                        sample.info.put("hdrWindowObserved", main { viewer!!.window.colorMode == ActivityInfo.COLOR_MODE_HDR })
                        if (args.getString("quality") == "true") verifyHdrReady(run, sample.key)
                    }
                    closePreview(run, expectedKey = sample.key)
                    check("$run 真实记事不变", baselineRow(sample.id) == rows()[sample.id])
                }
                closeDetail()
            }
        } finally {
            check("真实附件文件 SHA-256 不变", signatures.all { (key, digest) -> fileDigest(File(key.substring(1))) == digest })
            // 保存播放/HDR 观测，不记录原内容。
            summary.put("samples", JSONArray(samples.map { (name, sample) -> JSONObject(sample.info.toString()).put("name", name) }))
        }
    }

    private fun baselineRow(id: Long): String = JSONObject(File(output, "baseline.json").readText()).getString(id.toString())

    /** 仅断开副本的 IFD1 链，文件长度与其它段偏移不变，保留 HDR 增益图和 EXIF 主目录。 */
    private fun removeThumbnailDirectory(file: File) {
        java.io.RandomAccessFile(file, "rw").use { data ->
            require(data.readUnsignedShort() == 0xffd8)
            while (data.filePointer < data.length()) {
                val marker = data.readUnsignedShort()
                if (marker == 0xffda || marker == 0xffd9) break
                val length = data.readUnsignedShort()
                val start = data.filePointer
                if (marker == 0xffe1 && length >= 16) {
                    val header = ByteArray(6); data.readFully(header)
                    if (header.contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))) {
                        val tiff = start + 6
                        val little = data.readUnsignedShort() == 0x4949
                        fun short(): Int = data.readUnsignedShort().let { if (little) java.lang.Short.reverseBytes(it.toShort()).toInt() and 0xffff else it }
                        fun int(): Int = data.readInt().let { if (little) Integer.reverseBytes(it) else it }
                        require(short() == 42)
                        val directory = tiff + int()
                        require(directory in tiff until start + length - 2)
                        data.seek(directory)
                        val end = directory + 2 + short() * 12L
                        require(end + 4 <= start + length - 2)
                        data.seek(end); data.writeInt(0)
                        return
                    }
                }
                data.seek(start + length - 2)
            }
        }
    }

    private fun genericCases() {
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        for ((name, format) in listOf("jpeg" to Bitmap.CompressFormat.JPEG, "png" to Bitmap.CompressFormat.PNG,
            "webp" to Bitmap.CompressFormat.WEBP_LOSSLESS)) {
            val original = embeddedFixture(1)
            val bitmap = BitmapFactory.decodeFile(original.substring(1))
            val file = File(targetContext.cacheDir, "media-preview-transition/generic.$name")
            file.outputStream().use { bitmap.compress(format, 95, it) }; bitmap.recycle()
            val key = "0${file.path}"
            check("$name 没有内嵌图", MediaPreviewThumbnail.decode(file.path) == null)
            for (cropped in listOf(false, true)) {
                openDetail(listOf(key), cropped)
                repeat(2) { iteration ->
                    val run = "generic-$name-$cropped-$iteration"
                    expectedPreviewCorners = colors
                    openPreview(0, run)
                    check("$run 复用详情完整构图且不重新解码", events.any { it.startsWith("image-0-detail-preview-ready:") } &&
                        events.none { it == "image-0-preview-request" })
                    expectedPreviewCorners = null
                    verifyResolution(run, key)
                    check("$run 高清四角完整", main { cornersMatch((photo().drawable as android.graphics.drawable.BitmapDrawable).bitmap, colors) })
                    closePreview(run, key)
                }
                closeDetail()
            }
        }
        val rotated = embeddedFixture(6)
        removeThumbnailDirectory(File(rotated.substring(1)))
        openDetail(listOf(rotated), cropped = true)
        expectedPreviewCorners = intArrayOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN)
        openPreview(0, "generic-rotated")
        check("旋转图片复用完整预览", events.any { it.startsWith("image-0-detail-preview-ready:") })
        verifyResolution("generic-rotated", rotated)
        expectedPreviewCorners = null
        closePreview("generic-rotated", rotated); closeDetail()

        val animated = animatedWebpFixture()
        check("动态 WebP 不进入静图分支", !MediaPreviewThumbnail.isStaticImage(animated.path))

        val file = File(targetContext.cacheDir, "media-preview-transition/generic.jpeg")
        val bitmap = BitmapFactory.decodeFile(file.path)
        val identity = MediaPreviewThumbnail.source(file.path)
        MediaPreviewThumbnail.remember(identity, bitmap)
        val retained = MediaPreviewThumbnail.prepare(file.path).get()!!
        bitmap.recycle(); MediaPreviewThumbnail.clear()
        check("详情资源与缓存回收不影响查看器副本", !retained.bitmap.isRecycled && cornersMatch(retained.bitmap, colors))
        check("缓存副本限制为最长边 512", maxOf(retained.bitmap.width, retained.bitmap.height) <= 512)
        val reloaded = BitmapFactory.decodeFile(file.path)
        file.appendBytes(byteArrayOf(0))
        MediaPreviewThumbnail.remember(identity, reloaded)
        check("旧请求不写入文件更新后的缓存", MediaPreviewThumbnail.prepare(file.path).get() == null)
        MediaPreviewThumbnail.remember(MediaPreviewThumbnail.source(file.path), reloaded)
        file.appendBytes(byteArrayOf(0))
        check("文件更新后不复用旧预览", MediaPreviewThumbnail.prepare(file.path).get() == null)
        reloaded.recycle(); retained.bitmap.recycle()
        MediaPreviewThumbnail.clear()
    }

    private fun animatedWebpFixture(): File = File(targetContext.cacheDir, "media-preview-transition/animated.webp").also { file ->
        if (!file.exists()) targetContext.assets.open("detail-regression/animated.webp").use { input -> file.outputStream().use(input::copyTo) }
    }

    @androidx.annotation.RequiresApi(28)
    private fun animatedWebpCases() {
        val oldMode = DetailAutoplayMode.current()
        fun imageOrNull(index: Int): ImageView? {
            val page = detail ?: return null
            val list = field<DetailContentLayout>(page, "mDetailContent").recyclerView
            val adapter = field<ImageAttachmentAdapter>(page, "mImageAttachmentAdapter")
            return (0 until list.childCount).map { list.getChildViewHolder(list.getChildAt(it)) }
                .firstOrNull { it.bindingAdapter === adapter && it.bindingAdapterPosition == index }
                ?.itemView?.findViewById(R.id.iv_image_attachment)
        }
        fun image(index: Int = 0) = checkNotNull(imageOrNull(index))
        fun controller() = field<DetailAttachmentPlaybackController>(detail!!, "mAttachmentPlaybackController")
        fun mode(value: Int) = main {
            FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_DETAIL_DYNAMIC, value)
            detail?.let { controller().onResume() }
        }
        fun loaded(index: Int = 0): Boolean = imageOrNull(index)?.getTag(R.id.tag_detail_attachment_image_load_key) != null
        fun running(index: Int = 0) = (image(index).drawable as? Animatable)?.isRunning == true
        fun frame(view: ImageView): Bitmap = main {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(android.graphics.Canvas(it))
            }
        }
        fun differentFrames(name: String, view: ImageView) {
            fun windowFrame(): Bitmap {
                val rect = android.graphics.Rect()
                val window = main {
                    val point = IntArray(2); view.getLocationInWindow(point)
                    val host = viewer?.takeUnless { it.isFinishing } ?: detail!!
                    rect.set(point[0], point[1], point[0]+view.width, point[1]+view.height)
                    rect.intersect(0, 0, host.window.decorView.width, host.window.decorView.height)
                    host.window
                }
                val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
                val latch = java.util.concurrent.CountDownLatch(1)
                var status = -1
                PixelCopy.request(window, rect, bitmap, { status = it; latch.countDown() }, Handler(android.os.Looper.getMainLooper()))
                check(latch.await(3, java.util.concurrent.TimeUnit.SECONDS) && status == PixelCopy.SUCCESS)
                return bitmap
            }
            val first = windowFrame()
            var changed = false
            val deadline = SystemClock.uptimeMillis() + 1800
            while (!changed && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(80)
                val next = windowFrame()
                changed = !first.sameAs(next); next.recycle()
            }
            first.recycle()
            check("$name 实际帧像素持续变化", changed)
        }
        try {
            mode(DetailAutoplayMode.ALL_LOOP)
            for (fileName in listOf("animated.webp", "animated-large.webp", "animated-alpha.webp")) {
                val file = File(targetContext.cacheDir, "media-preview-transition/$fileName")
                targetContext.assets.open("detail-regression/$fileName").use { input -> file.outputStream().use(input::copyTo) }
                val key = "0${file.path}"
                for (cropped in listOf(false, true)) {
                    val name = "$fileName-${if (cropped) "cropped" else "default"}"
                    openDetail(listOf(key), cropped)
                    await("$name 详情动态资源就绪") { loaded() && running() }
                    val drawable = main { image().drawable as AnimatedMediaCropDrawable }
                    differentFrames("$name 详情", main { image() })
                    main { controller().setTransitionPaused(true) }
                    check("$name 转场暂停不误报完成", main { !running() && controller().usesAnimatedResource(0) })
                    val paused = frame(main { image() })
                    SystemClock.sleep(100)
                    val still = frame(main { image() })
                    check("$name 暂停时画面稳定", paused.sameAs(still)); paused.recycle(); still.recycle()
                    main { controller().setTransitionPaused(false) }
                    await("$name 暂停后恢复") { running() }

                    main { drawable.stop(); drawable.start(); drawable.stop() }
                    val animatedFirst = frame(main { image() })

                    // 关闭播放必须回到首帧；用独立原图解码验证裁剪，与动画资源不是同一请求。
                    mode(DetailAutoplayMode.OFF)
                    await("$name 首帧就绪") { loaded() && image().drawable is android.graphics.drawable.BitmapDrawable }
                    val sourceBitmap = BitmapFactory.decodeFile(file.path)
                    val crop = if (cropped) MediaCropBitmapRenderer.Crop(.72, .35, 1.8)
                        else MediaCropBitmapRenderer.Crop(.5, .5, 1.0)
                    val actual = frame(main { image() })
                    val expected = MediaCropBitmapRenderer.renderCrop(sourceBitmap, actual.width, actual.height, crop)!!
                    fun sample(bitmap: Bitmap, x: Int, y: Int): Int {
                        val cx = bitmap.width*x/10; val cy = bitmap.height*y/10
                        var r = 0; var g = 0; var b = 0; var a = 0; var n = 0
                        for (dy in -4..4) for (dx in -4..4) {
                            val pixel = bitmap.getPixel((cx+dx).coerceIn(0,bitmap.width-1), (cy+dy).coerceIn(0,bitmap.height-1))
                            r += Color.red(pixel); g += Color.green(pixel); b += Color.blue(pixel); a += Color.alpha(pixel); n++
                        }
                        return Color.argb(a/n, r/n, g/n, b/n)
                    }
                    fun near(a: Int, b: Int) = kotlin.math.abs(Color.red(a)-Color.red(b)) < 30 &&
                        kotlin.math.abs(Color.green(a)-Color.green(b)) < 30 &&
                        kotlin.math.abs(Color.blue(a)-Color.blue(b)) < 30 &&
                        kotlin.math.abs(Color.alpha(a)-Color.alpha(b)) < 30
                    var near = 0; var count = 0
                    var animatedNear = 0
                    for (y in 1..9) for (x in 1..9) {
                        val b = sample(expected,x,y)
                        if (near(sample(actual,x,y),b)) near++
                        if (near(sample(animatedFirst,x,y),b)) animatedNear++
                        count++
                    }
                    check("$name 静态首帧裁剪像素一致", near >= count * .85)
                    check("$name 动画首帧裁剪像素一致", animatedNear >= count * .85)
                    actual.recycle(); expected.recycle(); sourceBitmap.recycle(); animatedFirst.recycle()
                    mode(DetailAutoplayMode.ALL_LOOP)
                    await("$name 重新循环") { loaded() && running() }
                    // 同一加载身份的重应用不得重新解码或重置正在播放的资源。
                    val rebound = main { image().drawable }
                    repeat(5) { main { field<ImageAttachmentAdapter>(detail!!, "mImageAttachmentAdapter").refreshAttachedPlayback() } }
                    check("$name 调度重入保留动画实例", main { image().drawable === rebound && running() })
                    openPreview(0, name)
                    await("$name 全屏播放") { (photo().drawable as? Animatable)?.isRunning == true }
                    val full = main { photo().drawable }
                    check("$name 全屏独立解码", full !== drawable.animation && full !is AnimatedMediaCropDrawable)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    check("$name 全屏完整原始比例", kotlin.math.abs(full.intrinsicWidth.toDouble()/full.intrinsicHeight - bounds.outWidth.toDouble()/bounds.outHeight) < .005)
                    val metrics = targetContext.resources.displayMetrics
                    val fit = minOf(1.0, metrics.widthPixels.toDouble()/bounds.outWidth, metrics.heightPixels.toDouble()/bounds.outHeight)
                    check("$name 全屏尺寸足够", full.intrinsicWidth >= (bounds.outWidth*fit).toInt() && full.intrinsicHeight >= (bounds.outHeight*fit).toInt())
                    resolutions.put(JSONObject().put("name", name).put("source", JSONArray(listOf(bounds.outWidth,bounds.outHeight)))
                        .put("decoded", JSONArray(listOf(full.intrinsicWidth,full.intrinsicHeight))))
                    differentFrames("$name 全屏", main { photo() })
                    main { photo().setScale(2f, false) }
                    check("$name 放大保持完整解码资源", main { photo().drawable === full && photo().scale >= 1.99f })
                    if (screenshots) main { capture(viewer!!, "$name-zoom") }
                    closePreview(name)
                    await("$name 返回继续播放") { loaded() && running() }
                    differentFrames("$name 返回后", main { image() })
                    closeDetail()
                }
            }
            // 相同文件的两个真实 holder：覆盖平台 Resource.get() 共用实例导致的互相干扰。
            val key = "0${File(targetContext.cacheDir, "media-preview-transition/animated-large.webp").path}"
            for (value in listOf(DetailAutoplayMode.ALL_LOOP, DetailAutoplayMode.ALL_ONCE, DetailAutoplayMode.ONE_BY_ONE, DetailAutoplayMode.OFF)) {
                mode(value)
                openDetail(listOf(key, key))
                await("双附件布局就绪") { loaded(0) && loaded(1) }
                if (value == DetailAutoplayMode.ALL_LOOP) {
                    await("双 WebP 同时循环") { running(0) && running(1) }
                    val a = main { image(0).drawable as AnimatedMediaCropDrawable }
                    val b = main { image(1).drawable as AnimatedMediaCropDrawable }
                    check("两个 holder 解码器独立", a.animation !== b.animation)
                    main { a.stop() }
                    check("停止一个附件不影响另一个", main { !a.isRunning && b.isRunning })
                    main { field<ImageAttachmentAdapter>(detail!!, "mImageAttachmentAdapter").refreshAttachedPlayback() }
                    await("重新调度双动画") { running(0) && running(1) }
                    differentFrames("重复文件第二个附件", main { image(1) })
                    openPreview(0, "webp-duplicate")
                    val pages = main { field<List<View>>(viewer!!, "mTabs").map { it.findViewById<PhotoView>(R.id.iv_image_attachment) } }
                    await("重复 WebP 全屏两页就绪") { pages.all { it.drawable is android.graphics.drawable.AnimatedImageDrawable } }
                    check("全屏重复文件解码器独立", main { pages[0].drawable !== pages[1].drawable })
                    main { viewer!!.findViewById<ViewPager>(R.id.vp_image_viewer).setCurrentItem(1, false)
                        (pages[0].drawable as Animatable).stop() }
                    check("全屏停止前页不影响当前页", main { (pages[1].drawable as Animatable).isRunning })
                    closePreview("webp-duplicate", expectedKey = key)
                } else if (value != DetailAutoplayMode.OFF) {
                    val seen = mutableSetOf<Int>()
                    var exclusive = true
                    val deadline = SystemClock.uptimeMillis() + 8000
                    while (SystemClock.uptimeMillis() < deadline) {
                        val playing = main { (0..1).filter { running(it) } }
                        seen += playing
                        if (value == DetailAutoplayMode.ONE_BY_ONE && playing.size > 1) exclusive = false
                        if (seen.size == 2 && main { (0..1).all { loaded(it) && image(it).drawable is android.graphics.drawable.BitmapDrawable } }) break
                        SystemClock.sleep(20)
                    }
                    check("模式 $value 两个附件都播放一轮", seen.size == 2)
                    check("模式 $value 播完回静态首帧", main { (0..1).all { loaded(it) && image(it).drawable is android.graphics.drawable.BitmapDrawable } })
                    check("模式 $value 逐一不并发", exclusive)
                    SystemClock.sleep(300)
                    check("模式 $value 可见区不重复排队", main { !running(0) && !running(1) })
                } else {
                    check("关闭自动播放显示静态帧", main { (0..1).all { image(it).drawable is android.graphics.drawable.BitmapDrawable } })
                    main { controller().javaClass.getDeclaredMethod("onManualPlay", Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(controller(), 1) }
                    await("关闭档手动播放") { running(1) }
                    await("手动一轮后回首帧") { loaded(1) && image(1).drawable is android.graphics.drawable.BitmapDrawable }
                }
                closeDetail()
            }
            mode(DetailAutoplayMode.ALL_LOOP)
            openDetail(List(12) { key })
            await("滚动前 WebP 播放") { loaded() && running() }
            val leaving = main { image().drawable as Animatable }
            main { field<DetailContentLayout>(detail!!, "mDetailContent").recyclerView.scrollBy(0, 100000) }
            await("离屏 WebP 停止解码") { !leaving.isRunning }
            main { field<DetailContentLayout>(detail!!, "mDetailContent").recyclerView.scrollBy(0, -100000) }
            await("滚回后 WebP 重新播放") { loaded() && running() }
            differentFrames("滚回后 WebP", main { image() })
            closeDetail()
        } finally { mode(oldMode) }
    }

    /** 使用当前真实查看器的逐刷新状态，包含详情展开和完整图升级过程。 */
    private fun observePqHandoff(activity: ImageViewerActivity) {
        val run = label.removeSuffix("-enter")
        val observer = object : android.view.Choreographer.FrameCallback {
            private var frozePreview = false
            override fun doFrame(frameTimeNanos: Long) {
                if (activity.isDestroyed || pqObservers[activity] !== this) return
                val pager = activity.findViewById<ViewPager>(R.id.vp_image_viewer)
                val image = field<List<View>?>(activity, "mTabs")?.getOrNull(pager?.currentItem ?: -1)
                    ?.findViewById<PhotoView>(R.id.iv_image_attachment)
                if (image != null) {
                    val controller = field<Any?>(activity, "mPqController")
                    val surface = controller?.let { field<HdrProbeSurface?>(it, "surface") }
                    val retainedPreview = controller?.javaClass?.declaredFields?.firstOrNull { it.name == "preview" }
                        ?.apply { isAccessible = true }?.get(controller) != null
                    if (args.getString("pqAction") == "resume" && !frozePreview && retainedPreview &&
                        controller != null && field<Boolean>(controller, "previewRevealing")) {
                        frozePreview = true
                        controller.javaClass.getMethod("freezeWindow").invoke(controller)
                        Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (!activity.isDestroyed && !activity.isFinishing) {
                                controller.javaClass.getMethod("resumeWindow").invoke(controller)
                                activity.javaClass.getDeclaredMethod("applyHdrStateForCurrentPage").apply { isAccessible = true }.invoke(activity)
                            }
                        }, 40)
                    }
                    val texture = (image.parent as ViewGroup).findViewById<android.view.TextureView>(R.id.tv_motion_surface)
                    pqHandoffFrames.put(JSONObject().put("name", run).put("phase", pqPhase)
                        .put("uptimeMs", SystemClock.uptimeMillis()).put("elapsedMs", SystemClock.elapsedRealtime()).put("vsyncNs", frameTimeNanos)
                        .put("photoAlpha", image.alpha).put("drawable", image.drawable?.let(System::identityHashCode))
                        .put("width", image.drawable?.intrinsicWidth).put("height", image.drawable?.intrinsicHeight)
                        .put("full", image in field<Set<PhotoView>>(activity, "mFullResolutionImages"))
                        .put("blocked", field<Boolean>(activity, "mPreviewTransitionBlocked"))
                        .put("surface", surface?.let(System::identityHashCode)).put("ready", surface?.isFrameReady == true)
                        .put("retainedPreview", retainedPreview)
                        .put("previewFrozenForTest", frozePreview)
                        .put("strength", surface?.lastSubmittedStrength?.takeIf { it.isFinite() } ?: -1f)
                        .put("player", field<Any?>(activity, "mMotionPlayer") != null).put("videoAlpha", texture.alpha)
                        .put("backdrop", (image.parent as ViewGroup).findViewById<View>(R.id.v_motion_backdrop).visibility == View.VISIBLE))
                }
                android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }
        pqObservers[activity] = observer
        android.view.Choreographer.getInstance().postFrameCallback(observer)
    }

    private fun verifyPqHandoff(name: String, key: String, motion: Boolean) {
        val current = viewer!!
        fun invoke(method: String) = main { current.javaClass.getDeclaredMethod(method).apply { isAccessible = true }.invoke(current) }
        fun surface(): HdrProbeSurface? = field<Any?>(current, "mPqController")?.let { field(it, "surface") }
        fun frames(phase: String) = main { (0 until pqHandoffFrames.length()).map { pqHandoffFrames.getJSONObject(it) }
            .filter { it.getString("name") == name && it.getString("phase") == phase } }
        val action = args.getString("pqAction")
        if (action in listOf("sdr", "timeout", "close")) {
            await("$name 首帧准备中保留预览") { surface()?.isFrameReady != true && photo().alpha == 0f &&
                field<Any?>(field<Any>(current, "mPqController"), "preview") != null }
            if (action == "close") return
            if (action == "sdr") main { current.findViewById<View>(R.id.tv_hdr_badge).performClick() }
            await("$name 首帧回退恢复完整原图") { surface() == null && photo().alpha == 1f &&
                field<Any?>(field<Any>(current, "mPqController"), "preview") == null }
            // 超时或关闭 HDR 后，迟到解码不得再次盖住已经恢复的静图。
            SystemClock.sleep(args.getString("pqPrepareDelay", "0").toLong())
            check("$name 迟到准备不覆盖回退静图", main { surface() == null && photo().alpha == 1f &&
                field<Any?>(field<Any>(current, "mPqController"), "preview") == null })
            verifyResolution("$name-fallback", key, expectPhotoFallback = true)
            return
        }
        verifyHdrReady(name, key)
        await("$name PQ 交接完成并释放预览") {
            val controller = field<Any?>(current, "mPqController")
            controller?.javaClass?.declaredFields?.firstOrNull { it.name == "preview" }
                ?.apply { isAccessible = true }?.get(controller) == null
        }
        val opening = frames("opening")
        val early = opening.filter { it.getBoolean("full") && !it.getBoolean("blocked") &&
            !it.getBoolean("ready") && it.getDouble("photoAlpha") > 0 && !it.getBoolean("backdrop") }
        val record = JSONObject().put("name", name).put("motion", motion)
            .put("earlyFullPhotoFrames", early.size).put("earlyFullPhotoUptimes", JSONArray(early.map { it.getLong("uptimeMs") }))
        pqHandoffs.put(record)
        check("$name PQ 就绪前不暴露完整原图", early.isEmpty())
        check("$name PQ 准备期间保留可见预览", opening.filter { it.getBoolean("full") && !it.getBoolean("blocked") &&
            !it.getBoolean("ready") && !it.getBoolean("backdrop") }.all { it.getBoolean("retainedPreview") })
        if (action == "resume") check("$name 冻结后重新完成预览交接", opening.any { it.getBoolean("previewFrozenForTest") })
        for (enabled in listOf(false, true)) {
            main { pqPhase = "toggle-$enabled"; current.findViewById<View>(R.id.tv_hdr_badge).performClick() }
            await("$name 手动 HDR 开关完成") { surface()?.lastSubmittedStrength == if (enabled) 1f else 0f }
            val values = frames("toggle-$enabled").map { it.getDouble("strength") }
            check("$name 手动 HDR 切换保持渐变", values.any { it > 0 && it < 1 } &&
                values.zipWithNext().all { (a, b) -> if (enabled) b >= a else b <= a })
        }
        if (motion) for (mode in listOf("auto", "hold")) {
            main { pqPhase = mode }
            invoke(if (mode == "auto") "maybeAutoplayCurrentMotionPage" else "startMotionPlaybackForCurrentPage")
            await("$name-$mode 正常播放") { motionIsPlaying() }
            val original = main { surface() }
            SystemClock.sleep(250)
            if (mode == "hold") invoke("stopMotionPlayback")
            else await("$name-$mode 自动自然结束") { field<Any?>(current, "mMotionPlayer") == null }
            verifyHdrReady("$name-after-$mode", key)
            SystemClock.sleep(80)
            val visible = frames(mode).filter { !it.getBoolean("player") && !it.getBoolean("backdrop") }
            val waiting = frames(mode).filter { it.getBoolean("player") && !it.getBoolean("backdrop") }
            record.put("${mode}FirstStillStrength", visible.firstOrNull()?.optDouble("strength"))
                .put("${mode}LowGainStillFrames", visible.count { it.getDouble("strength") < .999 })
            check("$name-$mode 同一 PQ 层恢复静图", main { surface() === original })
            check("$name-$mode 视频实际揭示前不提前降低静图增益", waiting.all { it.getDouble("strength") >= .999 })
            val recovery = visible.dropWhile { it.getDouble("strength") >= .999 }.map { it.getDouble("strength") }
            check("$name-$mode 恢复 HDR 增益不反向跳变", recovery.zipWithNext().all { (a, b) -> b >= a })
        }
        main { pqPhase = "complete" }
    }

    private fun verifyHdrReady(name: String, key: String) {
        await("$name 静图恢复完整 HDR") {
            val controller = field<Any?>(viewer!!, "mPqController")
            field<Any?>(viewer!!, "mMotionPlayer") == null && if (controller == null)
                viewer!!.window.colorMode == ActivityInfo.COLOR_MODE_HDR
            else controller.let { field<HdrProbeSurface?>(it, "surface") }
                ?.let { it.isFrameReady && it.lastSubmittedStrength >= .999f } == true
        }
        val hdrReadyMs = (System.nanoTime() - requestedNs) / 1_000_000.0
        check("$name 最终完整 HDR 已就绪", true)
        verifyResolution("$name-hdr-restored", key)
        resolutions.getJSONObject(resolutions.length() - 1).put("clickToHdrFullMs", hdrReadyMs)
    }

    private fun motionIsPlaying(): Boolean = viewer?.let { current ->
        field<android.media.MediaPlayer?>(current, "mMotionPlayer")?.let { runCatching { it.isPlaying }.getOrDefault(false) } == true &&
            field<android.view.TextureView?>(current, "mMotionTexture")?.alpha == 1f &&
            field<Boolean>(current, "mMotionRevealed")
    } == true

    /** 仅测试时延迟真实播放器向目标 Surface 交付画面，原文件与生产计时逻辑保持。 */
    private fun verifyVideoStartup(name: String) {
        val current = viewer!!
        fun invoke(method: String) = main { current.javaClass.getDeclaredMethod(method).apply { isAccessible = true }.invoke(current) }
        invoke("cancelScheduledAutoplay")
        invoke("stopMotionPlayback")
        val delay = args.getString("frameDelay", "0").toLong()
        val suppressInfo = args.getString("suppressInfo") == "true"
        val autoplay = args.getString("autoplay") == "true"
        val cancelWaiting = args.getString("cancelWaiting") == "true"
        val record = JSONObject().put("name", name).put("frameDelayMs", delay).put("suppressInfo", suppressInfo)
            .put("autoplay", autoplay).put("cancelWaiting", cancelWaiting)
        val prepared = java.util.concurrent.CountDownLatch(1)
        val firstFrame = java.util.concurrent.CountDownLatch(1)
        val texture = main { (photo().parent as ViewGroup).findViewById<android.view.TextureView>(R.id.tv_motion_surface) }
        val backdrop = main { (photo().parent as ViewGroup).findViewById<View>(R.id.v_motion_backdrop) }
        val listener = main { texture.surfaceTextureListener }
        val originalTimestamp = main { texture.surfaceTexture?.timestamp }
        val started = SystemClock.elapsedRealtime()
        var firstFrameMs = -1L
        var shownMs = -1L
        var heldTexture: android.graphics.SurfaceTexture? = null
        var heldSurface: android.view.Surface? = null
        var deliver: Runnable? = null
        var lateInfo: (() -> Unit)? = null
        val draw = android.view.ViewTreeObserver.OnPreDrawListener {
            if (shownMs < 0 && texture.alpha > .99f && backdrop.visibility == View.VISIBLE) {
                shownMs = SystemClock.elapsedRealtime() - started
            }
            true
        }
        fun screen(stage: String): Double {
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            val rect = main { RectF(photo().displayRect).apply {
                val at = IntArray(2); photo().getLocationOnScreen(at); offset(at[0].toFloat(), at[1].toFloat())
            } }
            var light = 0; var count = 0
            for (y in rect.top.toInt().coerceAtLeast(0) until rect.bottom.toInt().coerceAtMost(bitmap.height) step 8) {
                for (x in rect.left.toInt().coerceAtLeast(0) until rect.right.toInt().coerceAtMost(bitmap.width) step 8) {
                    val c = bitmap.getPixel(x, y)
                    if (maxOf(Color.red(c), Color.green(c), Color.blue(c)) > 16) light++
                    count++
                }
            }
            val fraction = light.toDouble() / count.coerceAtLeast(1)
            record.put("${stage}LightFraction", fraction)
            if (screenshots) saveBitmap(bitmap, "$name-startup-$stage") else bitmap.recycle()
            return fraction
        }
        try {
            screen("cover")
            main {
                texture.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(s: android.graphics.SurfaceTexture, w: Int, h: Int) { listener?.onSurfaceTextureAvailable(s, w, h) }
                    override fun onSurfaceTextureSizeChanged(s: android.graphics.SurfaceTexture, w: Int, h: Int) { listener?.onSurfaceTextureSizeChanged(s, w, h) }
                    override fun onSurfaceTextureDestroyed(s: android.graphics.SurfaceTexture): Boolean = listener?.onSurfaceTextureDestroyed(s) ?: true
                    override fun onSurfaceTextureUpdated(s: android.graphics.SurfaceTexture) {
                        if (firstFrameMs < 0 && s.timestamp != originalTimestamp) {
                            firstFrameMs = SystemClock.elapsedRealtime() - started
                            record.put("alphaAtFirstUpdate", texture.alpha)
                            firstFrame.countDown()
                        }
                        listener?.onSurfaceTextureUpdated(s)
                    }
                }
                texture.viewTreeObserver.addOnPreDrawListener(draw)
                if (autoplay) {
                    val pager = current.findViewById<ViewPager>(R.id.vp_image_viewer)
                    current.javaClass.getDeclaredMethod("postAutoplay", Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
                        .invoke(current, pager.currentItem, 0, 0L)
                    field<Runnable>(current, "mAutoplayRunnable").let { pager.removeCallbacks(it); it.run() }
                } else current.javaClass.getDeclaredMethod("startVideoPlaybackForCurrentPage").apply { isAccessible = true }.invoke(current)
                val player = checkNotNull(field<android.media.MediaPlayer?>(current, "mMotionPlayer"))
                val onPrepared = field<android.media.MediaPlayer.OnPreparedListener>(player, "mOnPreparedListener")
                val onInfo = field<android.media.MediaPlayer.OnInfoListener>(player, "mOnInfoListener")
                lateInfo = { onInfo.onInfo(player, android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0) }
                player.setOnInfoListener { mp, what, extra ->
                    if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) record.put("infoMs", SystemClock.elapsedRealtime() - started)
                    if (suppressInfo && what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) false else onInfo.onInfo(mp, what, extra)
                }
                player.setOnPreparedListener { mp ->
                    if (delay > 0) {
                        heldTexture = android.graphics.SurfaceTexture(false)
                        heldSurface = android.view.Surface(heldTexture)
                        mp.setSurface(heldSurface)
                    }
                    record.put("preparedMs", SystemClock.elapsedRealtime() - started)
                    onPrepared.onPrepared(mp)
                    prepared.countDown()
                    if (delay > 0) {
                        deliver = Runnable {
                            if (field<Any?>(current, "mMotionPlayer") === mp) {
                                record.put("deliveredMs", SystemClock.elapsedRealtime() - started)
                                mp.setSurface(field<android.view.Surface>(current, "mMotionSurface"))
                            }
                        }.also { texture.postDelayed(it, delay) }
                    }
                }
            }
            check("$name 播放器准备完成", prepared.await(5, java.util.concurrent.TimeUnit.SECONDS))
            if (delay > 0) {
                SystemClock.sleep(delay / 2)
                screen("waiting")
                check("$name 首帧未到时保留预览", main { texture.alpha == 0f && backdrop.visibility != View.VISIBLE })
                if (cancelWaiting) {
                    invoke("stopMotionPlayback")
                    main {
                        lateInfo?.invoke()
                        listener?.onSurfaceTextureUpdated(checkNotNull(texture.surfaceTexture))
                    }
                    SystemClock.sleep(delay)
                    check("$name 取消后迟到通知不揭示", main { field<Any?>(current, "mMotionPlayer") == null &&
                        texture.alpha == 0f && backdrop.visibility != View.VISIBLE })
                    screen("cancelled")
                    return
                }
            }
            check("$name 实际视频纹理到达", firstFrame.await(5, java.util.concurrent.TimeUnit.SECONDS))
            await("$name 视频已揭示") { texture.alpha == 1f && backdrop.visibility == View.VISIBLE }
            SystemClock.sleep(100)
            screen("playing")
            check("$name 不早于实际视频纹理揭示", shownMs >= firstFrameMs && firstFrameMs >= 0)
            check("$name 视频正常播放", motionIsPlaying())
        } finally {
            main {
                deliver?.let(texture::removeCallbacks)
                texture.viewTreeObserver.removeOnPreDrawListener(draw)
                texture.surfaceTextureListener = listener
                record.put("firstFrameMs", firstFrameMs).put("shownMs", shownMs)
                videoStartups.put(record)
            }
            heldSurface?.release(); heldTexture?.release()
        }
    }

    private fun verifyMotionEdgeLifecycle(name: String, key: String, iteration: Int) {
        val current = viewer!!
        fun invoke(method: String) = main { current.javaClass.getDeclaredMethod(method).apply { isAccessible = true }.invoke(current) }
        fun pqSurface(): HdrProbeSurface? = field<Any?>(current, "mPqController")?.let { field(it, "surface") }
        invoke("stopMotionPlayback")
        verifyHdrReady(name, key)
        val surface = main { pqSurface() }
        val bitmap = main { (photo().drawable as android.graphics.drawable.BitmapDrawable).bitmap }
        val backdrop = main { (photo().parent as ViewGroup).findViewById<View>(R.id.v_motion_backdrop) }
        check("$name 静态显示不被黑底覆盖", main { backdrop.visibility != View.VISIBLE })
        if (iteration % 2 == 1) main { photo().setScale(2f, photo().width * .8f, photo().height * .5f, false) }
        for (mode in listOf("auto", "hold")) {
            invoke(if (mode == "auto") "maybeAutoplayCurrentMotionPage" else "startMotionPlaybackForCurrentPage")
            await("$name-$mode 动态正在播放") { motionIsPlaying() }
            check("$name-$mode 视频揭示时下层遮挡已就绪", main { backdrop.visibility == View.VISIBLE })
            check("$name-$mode 保留同一 HDR 输出层", main { pqSurface() === surface })
            verifyMotionEdge("$name-$mode")
            if (mode == "hold") {
                verifyMotionSnapshot("$name-$mode")
                invoke("stopMotionPlayback")
            } else await("$name 自动播放自然结束") { field<Any?>(current, "mMotionPlayer") == null }
            verifyHdrReady("$name-after-$mode", key)
            check("$name-$mode 停止后移除遮挡", main { backdrop.visibility != View.VISIBLE })
            check("$name-$mode 保留同一高清位图", main { (photo().drawable as android.graphics.drawable.BitmapDrawable).bitmap === bitmap })
            check("$name-$mode 恢复原 HDR 输出层", main { pqSurface() === surface })
            check("$name-$mode 保留缩放", main { kotlin.math.abs(photo().scale - if (iteration % 2 == 1) 2f else 1f) < .001f })
        }
        invoke("startMotionPlaybackForCurrentPage")
        await("$name 播放中返回就绪") { motionIsPlaying() }
    }

    /** 冻结实际视频，在其后插入黑底；播放画面若改变，说明仍有下层静图暴露。 */
    private fun verifyMotionEdge(name: String) {
        val current = viewer!!
        val player = main { checkNotNull(field<android.media.MediaPlayer?>(current, "mMotionPlayer")).also { it.pause() } }
        SystemClock.sleep(160)
        val texture = main { checkNotNull(field<android.view.TextureView?>(current, "mMotionTexture")) }
        val parent = texture.parent as ViewGroup
        val root = current.window.decorView
        fun captureScreen(): Bitmap {
            // Window PixelCopy 不包含下方独立 PQ Surface；边缘必须检查系统合成后的屏幕。
            val screenshot = checkNotNull(uiAutomation.takeScreenshot())
            require(screenshot.width == root.width && screenshot.height == root.height)
            return checkNotNull(screenshot.copy(Bitmap.Config.ARGB_8888, false)).also { screenshot.recycle() }
        }
        val original = captureScreen()
        val imageRect = main { RectF(photo().displayRect).apply {
            val location = IntArray(2); photo().getLocationInWindow(location)
            offset(location[0].toFloat(), location[1].toFloat())
        } }
        val videoRect = main { current.motionPreviewBounds(photo()) }
        val mask = main { View(current).apply {
            setBackgroundColor(Color.BLACK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            parent.addView(this, parent.indexOfChild(texture), ViewGroup.LayoutParams(-1, -1))
        } }
        try {
            SystemClock.sleep(100)
            val masked = captureScreen()
            val rowChanges = JSONArray()
            var total = 0; var interior = 0; var maxDifference = 0
            val visible = RectF(imageRect).apply { intersect(0f, 0f, root.width.toFloat(), root.height.toFloat()) }
            for (y in (visible.top.toInt() - 4).coerceAtLeast(0) until (visible.bottom.toInt() + 5).coerceAtMost(root.height)) {
                var rowCount = 0
                for (x in (visible.left.toInt() + 2).coerceAtLeast(0) until (visible.right.toInt() - 2).coerceAtMost(root.width)) {
                    val a = original.getPixel(x, y); val b = masked.getPixel(x, y)
                    val difference = maxOf(kotlin.math.abs(Color.red(a) - Color.red(b)),
                        kotlin.math.abs(Color.green(a) - Color.green(b)), kotlin.math.abs(Color.blue(a) - Color.blue(b)))
                    maxDifference = maxOf(maxDifference, difference)
                    if (difference > 3) { rowCount++; total++; if (y > visible.top + 8 && y < visible.bottom - 8) interior++ }
                }
                if (rowCount > 0) rowChanges.put(JSONArray(listOf(y, rowCount)))
            }
            motionEdges.put(JSONObject().put("name", name).put("photoRect", imageRect.toShortString())
                .put("videoRect", videoRect?.toShortString()).put("videoWidth", player.videoWidth).put("videoHeight", player.videoHeight)
                .put("underlayChangedPixels", total).put("interiorChangedPixels", interior).put("maxDifference", maxDifference)
                .put("changedRows", rowChanges).put("compatibility", field<Any?>(current, "mPqController") != null))
            check("$name 冻结视频内部稳定", interior == 0)
            check("$name 播放画面不露出下层静图", total == 0)
            if (screenshots || args.getString("edgeScreens") == "true") {
                saveBitmap(original, "$name-edge-original"); saveBitmap(masked, "$name-edge-masked")
            }
            else { original.recycle(); masked.recycle() }
        } finally {
            main { parent.removeView(mask); player.start() }
        }
        await("$name 边缘检查后恢复播放") { motionIsPlaying() }
    }

    /** 冻结同一视频帧，对比实际窗口与返回动画使用的捕获入口；随后恢复播放再真正返回。 */
    private fun verifyMotionSnapshot(name: String) {
        val current = viewer!!
        val player = main { checkNotNull(field<android.media.MediaPlayer?>(current, "mMotionPlayer")).also { it.pause() } }
        SystemClock.sleep(160) // 只用于诊断，让已排队的视频帧显示完毕。
        val root = current.window.decorView
        val reference = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        var referenceStatus = -1
        val referenceReady = java.util.concurrent.CountDownLatch(1)
        main { PixelCopy.request(current.window, reference, { referenceStatus = it; referenceReady.countDown() }, Handler(android.os.Looper.getMainLooper())) }
        check("$name 冻结帧窗口捕获成功", referenceReady.await(3, java.util.concurrent.TimeUnit.SECONDS) && referenceStatus == PixelCopy.SUCCESS)
        var captured: Bitmap? = null
        val snapshotReady = java.util.concurrent.CountDownLatch(1)
        main { com.ywwynm.everythingdone.views.particledismiss.ParticleDismissController.captureContent(root) {
            captured = it; snapshotReady.countDown()
        } }
        check("$name 冻结帧转场捕获成功", snapshotReady.await(3, java.util.concurrent.TimeUnit.SECONDS) && captured != null)
        val state = main {
            val texture = checkNotNull(field<android.view.TextureView?>(current, "mMotionTexture"))
            val transform = FloatArray(9).also { texture.getTransform(null).getValues(it) }
            JSONObject().put("name", name).put("videoWidth", player.videoWidth).put("videoHeight", player.videoHeight)
                .put("textureWidth", texture.width).put("textureHeight", texture.height)
                .put("textureTransform", JSONArray(transform.toList()))
                .put("viewScaleX", texture.scaleX).put("viewScaleY", texture.scaleY)
                .put("compatibility", field<Any?>(current, "mPqController") != null)
        }
        val bounds = main { checkNotNull(current.motionPreviewBounds(photo())) }.apply {
            intersect(0f, 0f, root.width.toFloat(), root.height.toFloat()); inset(8f, 8f)
        }
        captured?.let { snapshot ->
            var sum = 0L; var count = 0; var mismatched = 0
            var sumA = 0.0; var sumB = 0.0; var sumAA = 0.0; var sumBB = 0.0; var sumAB = 0.0
            for (y in bounds.top.toInt() until bounds.bottom.toInt() step 8) for (x in bounds.left.toInt() until bounds.right.toInt() step 8) {
                val a = reference.getPixel(x, y); val b = snapshot.getPixel(x, y)
                val difference = kotlin.math.abs(Color.red(a) - Color.red(b)) +
                    kotlin.math.abs(Color.green(a) - Color.green(b)) + kotlin.math.abs(Color.blue(a) - Color.blue(b))
                sum += difference; count++; if (difference > 72) mismatched++
                val grayA = (Color.red(a) + Color.green(a) + Color.blue(a)) / 3.0
                val grayB = (Color.red(b) + Color.green(b) + Color.blue(b)) / 3.0
                sumA += grayA; sumB += grayB; sumAA += grayA * grayA; sumBB += grayB * grayB; sumAB += grayA * grayB
            }
            val error = sum.toDouble() / (count * 3).coerceAtLeast(1)
            // 原生 HDR 窗口的 SDR PixelCopy 可能整体压暗；几何验收对亮度归一化，不能把色调映射当位移。
            val correlation = (count * sumAB - sumA * sumB) /
                kotlin.math.sqrt((count * sumAA - sumA * sumA) * (count * sumBB - sumB * sumB))
            state.put("meanChannelError", error).put("mismatchFraction", mismatched.toDouble() / count.coerceAtLeast(1))
                .put("spatialCorrelation", correlation.takeIf { it.isFinite() })
            check("$name 转场视频像素与屏幕同位置一致", correlation.isFinite() && correlation > .99)
            if (screenshots) saveBitmap(snapshot, "$name-frozen-capture") else snapshot.recycle()
        }
        motionSnapshots.put(state)
        if (screenshots) saveBitmap(reference, "$name-frozen-window") else reference.recycle()
        main { player.start() }
        await("$name 恢复动态后返回") { motionIsPlaying() }
    }

    private fun saveBitmap(bitmap: Bitmap, name: String) {
        Thread {
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }.also { captureWriters += it; it.start() }
    }

    private fun cornersMatch(bitmap: Bitmap, expected: IntArray): Boolean {
        val actual = listOf(.25f to .25f, .75f to .25f, .25f to .75f, .75f to .75f)
            .map { (x, y) -> bitmap.getPixel((bitmap.width * x).toInt(), (bitmap.height * y).toInt()) }
        return actual.indices.all { i ->
            kotlin.math.abs(Color.red(actual[i]) - Color.red(expected[i])) < 30 &&
                kotlin.math.abs(Color.green(actual[i]) - Color.green(expected[i])) < 30 &&
                kotlin.math.abs(Color.blue(actual[i]) - Color.blue(expected[i])) < 30
        }
    }

    /** 四象限 JPEG 与真实 EXIF IFD1；验证业务加载接缝，不用测试替身代替解码器。 */
    private fun embeddedFixture(orientation: Int, previewWidth: Int = 240, previewHeight: Int = 160): String {
        val bitmap = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply {
            val paint = android.graphics.Paint()
            for ((index, color) in listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).withIndex()) {
                paint.color = color
                val left = (index % 2) * 480f; val top = (index / 2) * 320f
                drawRect(left, top, left + 480, top + 320, paint)
            }
        }
        fun jpeg(image: Bitmap) = java.io.ByteArrayOutputStream().apply { image.compress(Bitmap.CompressFormat.JPEG, 95, this) }.toByteArray()
        val original = jpeg(bitmap)
        val preview = Bitmap.createScaledBitmap(bitmap, previewWidth, previewHeight, true)
        val thumbnail = jpeg(preview); preview.recycle(); bitmap.recycle()
        val tiff = java.nio.ByteBuffer.allocate(68 + thumbnail.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        tiff.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        tiff.putShort(1).putShort(0x112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0).putInt(26)
        tiff.putShort(3)
        tiff.putShort(0x103).putShort(3).putInt(1).putShort(6).putShort(0)
        tiff.putShort(0x201).putShort(4).putInt(1).putInt(68)
        tiff.putShort(0x202).putShort(4).putInt(1).putInt(thumbnail.size)
        tiff.putInt(0).put(thumbnail)
        val exif = byteArrayOf(69, 120, 105, 102, 0, 0) + tiff.array()
        val path = File(targetContext.cacheDir, "media-preview-transition/embedded-$orientation-$previewWidth-$previewHeight.jpg")
        path.outputStream().use { output ->
            val header = java.io.DataOutputStream(output)
            header.writeShort(0xffd8); header.writeShort(0xffe1); header.writeShort(exif.size + 2)
            header.write(exif); header.write(original, 2, original.size - 2)
        }
        return "0${path.path}"
    }

    private fun embeddedCases(files: List<String>) {
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val orientations = listOf(listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2))
        for (orientation in 1..8) {
            val key = embeddedFixture(orientation)
            openDetail(listOf(key))
            await("详情静态准备完成") { detail!!.isMediaPreviewLayoutReady(key) }
            MediaPreviewThumbnail.clear()
            expectedPreviewCorners = orientations[orientation - 1].map { colors[it] }.toIntArray()
            openPreview(0, "embedded-$orientation")
            check("EXIF $orientation 使用内嵌预览", events.any { it.startsWith("image-0-embedded-ready:") })
            verifyResolution("embedded-$orientation", key)
            check("EXIF $orientation 高清方向与预览一致", main {
                val bitmap = (photo().drawable as android.graphics.drawable.BitmapDrawable).bitmap
                cornersMatch(bitmap, expectedPreviewCorners!!)
            })
            expectedPreviewCorners = null
            closePreview("embedded-$orientation", key); closeDetail()
        }
        for ((name, key) in listOf("aspect-mismatch" to embeddedFixture(1, 240, 240),
            "too-small" to embeddedFixture(1, 60, 40), "missing" to files[0])) {
            openDetail(listOf(key))
            await("详情静态准备完成") { detail!!.isMediaPreviewLayoutReady(key) }
            MediaPreviewThumbnail.clear()
            openPreview(0, name)
            check("$name 回退原图预览", events.contains("image-0-embedded-unavailable"))
            verifyResolution(name, key); closePreview(name, key); closeDetail()
        }
        val key = embeddedFixture(1)
        openDetail(listOf(key))
        await("详情静态准备完成") { detail!!.isMediaPreviewLayoutReady(key) }
        MediaPreviewThumbnail.clear()
        val release = java.util.concurrent.CountDownLatch(1)
        val occupied = java.util.concurrent.CountDownLatch(1)
        field<java.util.concurrent.Executor>(MediaPreviewThumbnail, "io").execute {
            occupied.countDown(); release.await(12, java.util.concurrent.TimeUnit.SECONDS)
        }
        check("内嵌预览读取可控延后", occupied.await(2, java.util.concurrent.TimeUnit.SECONDS))
        try {
            forceEnterTimeout = true; reset("embedded-late-timeout")
            main { source(0).performClick() }
            await("内嵌预览超时回退") { events.contains("enter-timeout") && viewer?.hasWindowFocus() == true }
            forceEnterTimeout = false
            verifyResolution("embedded-late-timeout", key)
            val fullBitmap = main { (photo().drawable as android.graphics.drawable.BitmapDrawable).bitmap }
            release.countDown()
            await("迟到的小预览被丢弃") { events.contains("image-0-embedded-discarded") }
            check("迟到的小预览不会覆盖高清", main { (photo().drawable as android.graphics.drawable.BitmapDrawable).bitmap === fullBitmap })
            closePreview("embedded-late-timeout", key)
        } finally { release.countDown(); forceEnterTimeout = false; closeDetail() }
    }

    /** 检查实际显示的 PQ 纹理，不能仅以 PhotoView 已收到大图作为清晰度验收。 */
    private fun verifyResolution(name: String, key: String, expectPhotoFallback: Boolean = false) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(key.substring(1), bounds)
        val hdr = HdrImageDetector.detect(key.substring(1))
        val started = SystemClock.uptimeMillis()
        var state = JSONObject()
        var decoded = false
        var presented = false
        do {
            main {
                val image = photo()
                val bitmap = (image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                val controller = field<Any?>(viewer!!, "mPqController")
                val gpu = controller?.let { field<HdrGpuData?>(it, "data") }
                val surface = controller?.let { field<HdrProbeSurface?>(it, "surface") }
                val edge = maxOf(bitmap?.width ?: 0, bitmap?.height ?: 0)
                val required = minOf(maxOf(bounds.outWidth, bounds.outHeight), maxOf(image.width, image.height))
                // PQ 依原有预算一次性准备，缩放只更新矩阵；最低尺寸按未放大的显示区域验证。
                // 同时保留实际缩放尺寸，播放专项另核对前后同一高清位图和同一输出层。
                val displayed = image.displayRect?.let { maxOf(it.width(), it.height()) } ?: 0f
                val pqRequired = minOf(edge.toFloat(), displayed / image.scale.coerceAtLeast(1f))
                val full = image in field<Set<PhotoView>>(viewer!!, "mFullResolutionImages")
                decoded = full && required > 0 && edge >= required * .95f
                presented = if (controller != null && hdr && !expectPhotoFallback) surface?.isFrameReady == true && gpu != null &&
                    pqRequired > 0 && maxOf(gpu.width, gpu.height) >= pqRequired * .95f
                else image.alpha == 1f && surface == null
                state = JSONObject().put("name", name).put("hdr", hdr).put("compatibility", controller != null)
                    .put("expectedPhotoFallback", expectPhotoFallback)
                    .put("sourceWidth", bounds.outWidth).put("sourceHeight", bounds.outHeight)
                    .put("bitmapWidth", bitmap?.width).put("bitmapHeight", bitmap?.height)
                    .put("fullRequestReady", full).put("bitmapColorSpace", bitmap?.colorSpace?.name)
                    .put("bitmapGainmap", android.os.Build.VERSION.SDK_INT >= 34 && bitmap?.hasGainmap() == true)
                    .put("requiredPqEdge", pqRequired)
                    .put("displayedEdge", displayed).put("photoScale", image.scale)
                    .put("pqWidth", gpu?.width).put("pqHeight", gpu?.height)
                    .put("pqReady", surface?.isFrameReady).put("photoAlpha", image.alpha)
                    .put("deferredLoads", field<List<*>>(viewer!!, "mDeferredPreviewLoads").size)
                    .put("transitionBlocked", field<Boolean>(viewer!!, "mPreviewTransitionBlocked"))
                    .put("elapsedMs", SystemClock.uptimeMillis() - started)
                    .put("clickToReadyMs", (System.nanoTime() - requestedNs) / 1_000_000.0)
                    .put("pqStrength", surface?.lastSubmittedStrength?.takeIf { it.isFinite() })
                    .put("pqGainWidth", gpu?.gainWidth).put("pqGainHeight", gpu?.gainHeight)
                    .put("pqRatioMin", gpu?.ratioMin?.let { JSONArray(it.toList()) })
                    .put("pqRatioMax", gpu?.ratioMax?.let { JSONArray(it.toList()) })
                    .put("pqGamma", gpu?.gamma?.let { JSONArray(it.toList()) })
                    .put("pqMinimum", gpu?.minimum).put("pqFull", gpu?.full)
            }
            if (decoded && presented) break
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() - started < 8000)
        resolutions.put(state)
        main {
            val bitmap = (photo().drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bitmap != null) {
                val pixels = java.nio.ByteBuffer.allocate(bitmap.byteCount)
                bitmap.copyPixelsToBuffer(pixels)
                state.put("bitmapSha256", MessageDigest.getInstance("SHA-256").digest(pixels.array()).joinToString("") { "%02x".format(it) })
            }
        }
        check("$name 最终位图恢复正常清晰度", decoded)
        check("$name 实际显示层使用正常清晰度", presented)
        if (screenshots) {
            // Window PixelCopy 不包含独立的 PQ Surface；这里采集系统合成后的画面。
            // 播放器释放回调早于最后视频帧退场，先让修改后的视图提交到系统合成。
            val rendered = java.util.concurrent.CountDownLatch(1)
            main { viewer!!.window.decorView.let { view ->
                view.postOnAnimation { view.postOnAnimation { rendered.countDown() } }
            } }
            check("$name 截图前视图已提交", rendered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val screen = uiAutomation.takeScreenshot()
            check("$name 系统画面可捕获", screen != null)
            if (screen != null) Thread {
                File(output, "$name-full-resolution.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screen.recycle()
            }.also { captureWriters += it; it.start() }
        }
    }
    private fun fileDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun openPreview(index: Int, name: String) {
        reset("$name-enter")
        main { source(index).performClick() }
        await("$name 展开完成") { viewer?.hasWindowFocus() == true && events.contains("enter-finished") }
        check("$name 使用自定义展开", events.contains("enter-prepared"))
        check("$name 展开具有目标图片几何", sourceRect != null && targetRect != null)
        check("$name 展开进度单调", monotonic("enter"))
        check("$name 动画期间不提前播放或提亮", mediaDeferred)
        check("$name 查看器输入恢复", main { inputReady(viewer!!) })
        check("$name 展开后可见图片", main { photo().drawable != null && photo().width > 0 })
        if (args.getString("latencyLimit") != null) {
            val prepared = main { (0 until timeline.length()).map { timeline.getJSONArray(it) }.firstOrNull { it.getString(0) == "enter-prepared" } }
            check("$name 点击到展开不超过 ${args.getString("latencyLimit")} ms",
                prepared != null && (prepared.getLong(1) - requestedNs) / 1_000_000 <= args.getString("latencyLimit")!!.toLong())
        }
    }

    private fun closePreview(name: String, expectedKey: String? = null, expectTarget: Boolean = true,
                             expectStableResource: Boolean = true, trigger: (() -> Unit)? = null) {
        val key = expectedKey ?: main { field<ArrayList<String>>(viewer!!, "mTypePathNames")[viewer!!.findViewById<ViewPager>(R.id.vp_image_viewer).currentItem] }
        val playbackBounds = main { viewer!!.motionPreviewBounds(photo())?.apply {
            intersect(com.ywwynm.everythingdone.views.ThingDetailTransition.bounds(photo(), viewer!!.window.decorView))
            intersect(0f, 0f, viewer!!.window.decorView.width.toFloat(), viewer!!.window.decorView.height.toFloat())
        } }
        reset("$name-return")
        if (args.getString("continuity") == "true") main {
            continuityKey = key
            detail!!.window.decorView.viewTreeObserver.addOnPreDrawListener(continuityDraw)
            if (screenshots) capture(viewer!!, "$label-before")
        }
        if (trigger != null) trigger() else main { viewer!!.onBackPressedDispatcher.onBackPressed() }
        await("$name 返回完成") { detail!!.hasWindowFocus() && inputReady(detail!!) && viewer!!.isFinishing }
        check("$name 返回类型正确", events.contains(if (expectTarget) "return-prepared" else "return-fade"))
        check("$name 返回完成并释放", events.contains("return-finished"))
        check("$name 返回进度单调", monotonic("return"))
        if (trigger != null) {
            check("$name 松手直接进入连续运动", events.contains("return-interactive-start") && !events.contains("return-capture"))
            val times = (0 until timeline.length()).map { timeline.getJSONArray(it) }
            val start = times.firstOrNull { it.getString(0) == "return-interactive-start" }?.getLong(1)
            val moved = times.firstOrNull { it.getString(0).startsWith("return-progress:") && it.getString(0).substringAfter(':').toFloat() > 0f }?.getLong(1)
            check("$name 首个运动帧在 48 ms 内", start != null && moved != null && (moved - start) / 1_000_000 < 48)
            val frames = (0 until interactiveFrames.length()).map { interactiveFrames.getJSONObject(it) }.filter { it.getString("name") == label }
            check("$name 连续运动保持等比", frames.isNotEmpty() && frames.map { it.getDouble("width") / it.getDouble("height") }.let { it.max() - it.min() < .001 })
            val finished = times.firstOrNull { it.getString(0) == "return-finished" }?.getLong(1)
            check("$name 松手后 408 ms 内运动到位", start != null && finished != null && (finished - start) / 1_000_000 <= 408)
            val density = detail!!.resources.displayMetrics.density.toDouble()
            val first = frames.first()
            val last = frames.last()
            for ((axis, extent) in listOf("x" to detail!!.window.decorView.width, "y" to detail!!.window.decorView.height)) {
                val minimum = minOf(first.getDouble(axis), last.getDouble(axis))
                val maximum = maxOf(first.getDouble(axis), last.getDouble(axis))
                val limit = minOf(36 * density, extent * .04)
                check("$name $axis 外移受视口与 36dp 约束", frames.all { it.getDouble(axis) in (minimum - limit - 1)..(maximum + limit + 1) })
            }
            val minWidth = minOf(first.getDouble("width"), last.getDouble("width"))
            val widthLimit = minOf(36 * density, minWidth * .06)
            check("$name 尺寸惯性不额外塌缩", frames.all { it.getDouble("width") >= minWidth - widthLimit - 1 })
            if (name.contains("fast-")) {
                // 同一显示周期内可能先收到不足 1 ms 的零进度回调，检查真正开始运动的第一帧。
                val firstMoving = frames.firstOrNull { it.getDouble("progress") > 0 }
                val direction = if (name.contains("fast-down")) 1 else -1
                check("$name 快甩接续手指方向", firstMoving != null && direction * (firstMoving.getDouble("y") - first.getDouble("y")) > 0)
                val distance = kotlin.math.abs(first.getDouble("y") - last.getDouble("y"))
                val body = frames.firstOrNull { kotlin.math.abs(it.getDouble("y") - last.getDouble("y")) <= maxOf(distance * .01, density) }
                check("$name 324 ms 内完成主要回收", body != null && body.getDouble("elapsedMs") <= 324)
            }
        }
        if (playbackBounds != null) {
            check("$name 返回使用实际视频比例", sourceRect != null && rectNear(sourceRect!!, playbackBounds))
        }
        if (expectTarget) {
            val bounds = main { detail!!.findMediaPreviewSource(key)?.let { v ->
                val visible = android.graphics.Rect(); v.getGlobalVisibleRect(visible)
                val at = IntArray(2); detail!!.window.decorView.getLocationOnScreen(at)
                RectF(visible).apply { offset(-at[0].toFloat(), -at[1].toFloat()) }
            } }
            check("$name 返回当前附件位置", bounds != null && targetRect != null && rectNear(bounds, targetRect!!))
        }
        if (args.getString("continuity") == "true") {
            for (delay in listOf(0L, 32L, 96L, 300L, 500L)) {
                SystemClock.sleep(delay)
                main { if (screenshots) capture(detail!!, "$label-after-$delay") }
            }
            main { detail!!.window.decorView.viewTreeObserver.removeOnPreDrawListener(continuityDraw) }
            val prepared = main { (0 until timeline.length()).map { timeline.getJSONArray(it) } }
                .firstOrNull { it.getString(0) == "return-prepared" }?.getLong(1) ?: 0L
            val finished = main { (0 until timeline.length()).map { timeline.getJSONArray(it) } }
                .firstOrNull { it.getString(0) == "return-finished" }?.getLong(1) ?: Long.MAX_VALUE
            val handoff = (0 until continuity.length()).map { continuity.getJSONObject(it) }
                .filter { it.getString("name") == label && it.getLong("time") in prepared..(finished + 64_000_000) }
            val colors = handoff.filter { it.has("dark") }.map { it.getBoolean("dark") }
            check("$name 返回顶栏配色稳定", colors.distinct().size <= 1)
            val resources = (0 until continuity.length()).map { continuity.getJSONObject(it) }
                .filter { it.getString("name") == label && it.getLong("time") in prepared..(finished + 64_000_000) && it.has("drawable") }
                .map { it.getInt("drawable") }.distinct()
            if (expectStableResource) check("$name 返回不重复替换已就绪资源", resources.size <= 1)
            val frames = (0 until continuity.length()).map { continuity.getJSONObject(it) }
                .filter { it.getString("name") == label && it.has("gifFrame") }
            val initial = frames.firstOrNull()?.optInt("gifFrame", -1) ?: -1
            val frameCount = frames.firstOrNull()?.optInt("gifFrameCount", 0) ?: 0
            val next = frames.firstOrNull { it.optInt("gifFrame", -1) != initial }?.optInt("gifFrame", -1)
            if (expectStableResource && initial > 0 && initial < frameCount - 2 && next != null) {
                check("$name 返回接续播放帧", next > initial)
            }
        }
        viewer = null
    }

    private fun dismissCases(files: List<String>) {
        val samples = listOf("image", "gif", "video", "motion", "hdr").zip(files) +
            ("webp" to "0${animatedWebpFixture().path}")
        for ((name, key) in samples) {
            openDetail(listOf(key))
            openPreview(0, "dismiss-$name")
            verifyInteractiveDismiss("dismiss-$name", key, extensive = name == "image")
            if (name == "image") {
                for ((fastName, direction) in listOf("dismiss-fast-down" to 1f, "dismiss-fast-up" to -1f)) {
                openPreview(0, fastName)
                verifyResolution(fastName, key)
                val density = viewer!!.resources.displayMetrics.density
                closePreview(fastName, key, trigger = { main {
                    val bounds = MediaPreviewTransition.fullPhotoRect(photo(), viewer!!.window.decorView)!!
                    val now = SystemClock.uptimeMillis()
                    // 固定事件间隔，避免 runOnMainSync 的调度延迟把快速手势变成慢拖。
                    for ((index, offset) in listOf(0f, 8f, 30f, 60f, 90f).withIndex()) {
                        val action = when (index) { 0 -> MotionEvent.ACTION_DOWN; 4 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                        val event = MotionEvent.obtain(now, now + index * 8L, action, bounds.centerX(), bounds.centerY() + direction * offset * density, 0)
                        viewer!!.dispatchTouchEvent(event)
                        event.recycle()
                    }
                } })
                }
            }
            closeDetail()
        }
        main { HdrViewerDiagnostics.forceCompatibility = true }
        openDetail(listOf(files[4]))
        openPreview(0, "dismiss-pq")
        verifyResolution("dismiss-pq", files[4])
        verifyInteractiveDismiss("dismiss-pq", files[4], extensive = false)
        closeDetail()
        main { HdrViewerDiagnostics.forceCompatibility = false }
    }

    /** 真机 Activity 的完整触摸分发链；坐标取实际媒体区域，事件按真实时间推进。 */
    private inner class PreviewFinger {
        private val activity = viewer!!
        private val down = SystemClock.uptimeMillis()
        private val point = main {
            val image = photo()
            val bounds = activity.motionPreviewBounds(image) ?: MediaPreviewTransition.fullPhotoRect(image, activity.window.decorView)!!
            bounds.intersect(ThingDetailTransition.bounds(image, activity.window.decorView))
            bounds.centerX() to bounds.centerY()
        }
        var dy = 0f
        init { event(MotionEvent.ACTION_DOWN) }
        fun move(y: Float, millis: Long = 32, x: Float = 0f) {
            SystemClock.sleep(millis)
            dy = y
            event(MotionEvent.ACTION_MOVE, x)
        }
        fun up(millis: Long = 160) { SystemClock.sleep(millis); event(MotionEvent.ACTION_UP) }
        fun cancel() = event(MotionEvent.ACTION_CANCEL)
        private fun event(action: Int, dx: Float = 0f) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.first + dx, point.second + dy, 0)
            main { activity.dispatchTouchEvent(event) }
            event.recycle()
        }
    }

    private fun verifyInteractiveDismiss(name: String, key: String, extensive: Boolean) {
        val activity = viewer!!
        val density = activity.resources.displayMetrics.density
        val page = main { photo().parent as View }
        fun active() = field<MediaPreviewDismissGesture>(activity, "mPreviewDismiss").isActive
        fun invoke(method: String) = main { activity.javaClass.getDeclaredMethod(method).apply { isAccessible = true }.invoke(activity) }
        fun restored() = await("$name 回弹完成") { !active() && page.scaleX == 1f && page.translationY == 0f }
        fun screen(suffix: String) {
            if (screenshots) {
                waitForIdleSync()
                SystemClock.sleep(64)
                uiAutomation.takeScreenshot()?.let { saveBitmap(it, "$name-$suffix") }
            }
        }
        val motion = key.first() == '1' || MotionPhotoDetector.detect(key.substring(1)).isMotionPhoto
        if (key.first() != '1' && key.substring(1).endsWith(".jpg", true)) verifyResolution(name, key)
        if (key.endsWith(".gif", true) || key.endsWith(".webp", true)) {
            await("$name 动图加载完成") { (photo().drawable as? Animatable)?.isRunning == true }
        }
        if (motion) {
            invoke("cancelScheduledAutoplay"); invoke("stopMotionPlayback")
            invoke("scheduleAutoplayForCurrentPage")
            await("$name 动态自动播放就绪") { motionIsPlaying() }
        }
        val drawable = main { photo().drawable }
        val player = main { field<android.media.MediaPlayer?>(activity, "mMotionPlayer") }
        val startPosition = main { player?.currentPosition ?: -1 }
        val animation = drawable as? Animatable
        val wasAnimating = main { animation?.isRunning == true }
        val short = 36f * density
        val finger = PreviewFinger()
        finger.move(short, 48)
        check("$name 单指接管并等比缩小", main { active() && page.scaleX < 1f && page.scaleX == page.scaleY })
        check("$name 保持媒体实例", main { photo().drawable === drawable && field<Any?>(activity, "mMotionPlayer") === player })
        if (player != null) check("$name 拖动时继续播放", main { player.isPlaying && player.currentPosition >= startPosition })
        if (wasAnimating) check("$name 拖动时动图继续播放", main { animation!!.isRunning })
        val smallScale = main { page.scaleX }
        finger.move(80f * density, 48)
        check("$name 外拖进一步缩小", main { page.scaleX < smallScale })
        screen("drag")
        finger.move(short, 80)
        check("$name 回拖恢复尺寸", main { page.scaleX >= smallScale - .001f })
        finger.up()
        restored()
        check("$name 取消不退出且不替换媒体", main { !activity.isFinishing && photo().drawable === drawable })
        if (player != null) check("$name 回弹不重建播放器或重置进度", main {
            field<Any?>(activity, "mMotionPlayer") === player && player.isPlaying && player.currentPosition >= startPosition
        })
        if (wasAnimating) check("$name 回弹后动图继续播放", main { animation!!.isRunning })
        screen("restored")

        if (motion) {
            invoke("cancelScheduledAutoplay"); invoke("stopMotionPlayback")
            val still = PreviewFinger()
            still.move(-short, 48); still.up(); restored()
            check("$name 原本停止的媒体保持停止", main { field<Any?>(activity, "mMotionPlayer") == null })
        }
        if (extensive) {
            // 放大后实际平移到上边缘，接续拖动；回弹保留接管时的倍率与图像矩阵。
            val zoomScale = main {
                val image = photo()
                val scale = (image.height / image.displayRect.height() + 1f).coerceAtLeast(3f)
                image.maximumScale = maxOf(image.maximumScale, scale)
                image.setScale(scale, false)
                scale
            }
            val zoom = PreviewFinger()
            val distance = main { (-photo().displayRect.top).coerceAtLeast(0f) }
            if (distance > 20f * density) {
                zoom.move(distance / 2, 80)
                check("$name 放大后先平移", main { !active() && kotlin.math.abs(photo().scale - zoomScale) < .01f })
            }
            zoom.move(distance + short, 80)
            check("$name 到边缘才接管", main { active() && kotlin.math.abs(photo().displayRect.top) < 2f })
            val edge = main { RectF(photo().displayRect) }
            zoom.up(); restored()
            check("$name 回弹保留放大倍率及边缘位置", main { kotlin.math.abs(photo().scale - zoomScale) < .01f && rectNear(photo().displayRect, edge) })
            main { photo().setScale(1f, false) }
            val sideways = PreviewFinger()
            sideways.move(2f, 64, 100f * density)
            check("$name 横向手势不接管", main { !active() })
            sideways.cancel()
            // 模式门控在真实 Activity 校验；空间渲染自身不在此处伪造。
            main { set(activity, "mSpatialMode", true) }
            val spatial = PreviewFinger()
            spatial.move(short, 64)
            check("$name 空间模式关闭返回手势", main { !active() })
            spatial.cancel()
            main { set(activity, "mSpatialMode", false) }
        }

        // 再次启动动态，确认松手提交前仍显示真实视频帧；长按播放也只在确认返回后停止。
        if (motion) {
            invoke(if (key.first() == '1') "startVideoPlaybackForCurrentPage" else "startMotionPlaybackForCurrentPage")
            await("$name 返回前动态播放") { motionIsPlaying() }
        }
        val commit = PreviewFinger()
        commit.move(-40f * density, 48)
        val distance = main { (page.height * .24f).coerceAtMost(230f * density) }
        commit.move(-distance, 100)
        check("$name 上滑达到返回距离", main { active() && page.translationY < 0f })
        val from = main {
            val root = activity.window.decorView
            (activity.motionPreviewBounds(photo()) ?: MediaPreviewTransition.fullPhotoRect(photo(), root))?.apply {
                intersect(ThingDetailTransition.bounds(photo(), root))
                intersect(0f, 0f, root.width.toFloat(), root.height.toFloat())
            }
        }
        screen("commit")
        requirePlayingReturn = motion
        closePreview(name, expectedKey = key, trigger = { commit.up() })
        requirePlayingReturn = false
        check("$name 从拖动位置接续返回", sourceRect != null && from != null && rectNear(sourceRect!!, from))
        check("$name 返回后释放播放器", main { field<Any?>(activity, "mMotionPlayer") == null })
    }

    private fun closeDetail() { main { detail?.finish() }; waitForIdleSync(); detail = null }
    private fun transition() = viewer?.let { field<MediaPreviewTransition?>(it, "mPreviewTransition") }
    private fun photo(): PhotoView {
        val current = viewer!!
        return field<List<View>>(current, "mTabs")[current.findViewById<ViewPager>(R.id.vp_image_viewer).currentItem].findViewById(R.id.iv_image_attachment)
    }
    private fun sourceOrNull(index: Int): View? {
        val current = detail ?: return null
        val adapter = field<ImageAttachmentAdapter>(current, "mImageAttachmentAdapter")
        val key = adapter.getItems()?.getOrNull(index)
        return current.findMediaPreviewSource(key)
    }
    private fun source(index: Int) = checkNotNull(sourceOrNull(index))
    private fun monotonic(direction: String): Boolean = events.filter { it.startsWith("$direction-progress:") }
        .map { it.substringAfter(':').toFloat() }.let { values -> values.size >= 3 && values.zipWithNext().all { it.second >= it.first } }
    private fun rectNear(a: RectF, b: RectF) = listOf(a.left - b.left, a.top - b.top, a.right - b.right, a.bottom - b.bottom).all { kotlin.math.abs(it) < 2 }
    private fun check(name: String, passed: Boolean) = main {
        checks.put(JSONObject().put("name", name).put("passed", passed)); if (!passed) failures++
    }
    private fun await(name: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < deadline) { if (main(condition)) return; SystemClock.sleep(32) }
        throw AssertionError("等待失败：$name；事件=$events")
    }
    private fun capture(activity: Activity, name: String) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(activity.window, bitmap, { status ->
            if (status == PixelCopy.SUCCESS) Thread {
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }.also { captureWriters += it; it.start() } else bitmap.recycle()
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }
    private fun media(): List<String> {
        val dir = File(targetContext.cacheDir, "media-preview-transition").apply { mkdirs() }
        val still = File(dir, "image.jpg")
        val bitmap = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(35, 150, 120)) }
        if (!still.exists()) still.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
        val gif = File(dir, "animated.gif"); val video = File(dir, "video.mp4")
        listOf(gif, video).filterNot(File::exists).forEach { file -> targetContext.assets.open("detail-regression/${file.name}").use { input -> file.outputStream().use(input::copyTo) } }
        val hdr = File(dir, "hdr.jpg").also { if (!it.exists()) HdrProbeImage.builtin(it) }
        val motion = File(dir, "motion.jpg")
        if (!motion.exists()) motion.outputStream().use { out -> hdr.inputStream().use { it.copyTo(out) }; video.inputStream().use { it.copyTo(out) } }
        check("动态照片夹具包含有效内嵌视频", MotionPhotoDetector.detect(motion.path).isMotionPhoto)
        return listOf("0${still.path}", "0${gif.path}", "1${video.path}", "0${motion.path}", "0${hdr.path}")
    }
    private fun rows(): Map<Long, String> = DBHelper(targetContext).readableDatabase.rawQuery("SELECT * FROM things WHERE type<>-1 ORDER BY id", null).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val digest = MessageDigest.getInstance("SHA-256")
                for (i in 0 until cursor.columnCount) { digest.update((cursor.getString(i) ?: "<null>").toByteArray()); digest.update(0.toByte()) }
                put(cursor.getLong(cursor.getColumnIndexOrThrow("id")), digest.digest().joinToString("") { "%02x".format(it) })
            }
        }
    }
    private fun recoverFixtures() {
        val manifest = File(output, "fixtures.json")
        if (!manifest.exists()) return
        val saved = JSONObject(manifest.readText())
        val ids = saved.getJSONArray("ids")
        val db = DBHelper(targetContext).writableDatabase
        for (i in 0 until ids.length()) db.delete("things", "id=? AND create_time=? AND title=?",
            arrayOf(ids.getLong(i).toString(), saved.getLong("stamp").toString(), "媒体转场专用模拟"))
    }
    private fun inputReady(activity: Activity): Boolean {
        val session = field<Map<Activity, Any>>(UiInteractions, "sessions")[activity] ?: return true
        val state = field<UiTransitionState>(session, "state")
        return state.canInteract(state.root)
    }
    private fun <T> main(action: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return action()
        var result: T? = null
        runOnMainSync { result = action() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun <T> field(instance: Any, name: String): T { @Suppress("UNCHECKED_CAST") return instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T }
    private fun set(instance: Any, name: String, value: Any?) { instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value) }
}
