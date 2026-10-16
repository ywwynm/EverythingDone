package com.ywwynm.everythingdone.hdr

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Gainmap
import android.graphics.Paint
import android.graphics.ColorSpace
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.ViewPager
import com.github.chrisbanes.photoview.PhotoView
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.ImageViewerActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 只使用测试缓存文件，直接打开真实查看器；不写用户记事或持久化兼容开关。 */
class HdrViewerRegressionProbe : Instrumentation() {
    private var activity: ImageViewerActivity? = null
    private lateinit var output: File
    private val checks = JSONArray()
    private val captures = mutableListOf<Bitmap>()
    private var captureScreenshots = false
    private var transitionsOnly = false
    private var pixelsOnly = false
    private var lifecycleOnly = false
    private var latencyOnly = false
    private var openingOnly = false
    private var openingFrames = JSONArray()
    private var openingBlock: java.util.concurrent.CountDownLatch? = null
    private var observeExit = false
    private val exitFrames = JSONArray()
    private var openStarted = 0L
    private var firstBaseMs = -1L
    private var firstVisibleMs = -1L
    private var firstPqMs = -1L

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        super.callActivityOnCreate(activity, icicle)
        if (activity is ImageViewerActivity) openingBlock?.let { blocker ->
            val controller = field<HdrViewerController?>(activity, "mPqController")
            controller?.let { field<java.util.concurrent.ScheduledThreadPoolExecutor>(it, "io") }
                ?.submit { blocker.await(8, java.util.concurrent.TimeUnit.SECONDS) }
        }
        if (activity is ImageViewerActivity && (transitionsOnly || lifecycleOnly || latencyOnly || openingOnly)) {
            val observedStart = openStarted
            activity.window.decorView.viewTreeObserver.addOnPreDrawListener {
                // 上一个窗口的退出动画可能仍在绘制，不能污染下一次打开的首帧计时。
                if (observedStart != openStarted) return@addOnPreDrawListener true
                val page = activity.findViewById<ViewPager>(R.id.vp_image_viewer).currentItem
                val photo = field<List<View>>(activity, "mTabs").getOrNull(page)?.findViewById<PhotoView>(R.id.iv_image_attachment)
                val parent = photo?.parent as? ViewGroup
                if (photo?.drawable != null && firstBaseMs < 0) firstBaseMs = SystemClock.uptimeMillis() - openStarted
                if (openingOnly && photo?.drawable != null && parent != null) {
                    val s = (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<HdrProbeSurface>().firstOrNull()
                    openingFrames.put(JSONObject().put("ms", SystemClock.uptimeMillis() - openStarted)
                        .put("photoAlpha", photo.alpha).put("nativeGainmap", (photo.drawable as? BitmapDrawable)?.bitmap?.hasGainmap() == true)
                        .put("progress", parent.findViewById<View>(R.id.pb_image_attachment).visibility == View.VISIBLE)
                        .put("layer", s?.let(System::identityHashCode) ?: 0).put("ready", s?.isFrameReady == true)
                        .put("strength", s?.lastSubmittedStrength?.takeIf { it.isFinite() } ?: -1f))
                }
                if (parent != null && firstPqMs < 0) {
                    (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<HdrProbeSurface>()
                        .firstOrNull { it.isFrameReady }?.let { firstPqMs = it.readyUptimeMs - openStarted }
                }
                if (firstVisibleMs < 0 && (firstPqMs >= 0 || photo?.drawable != null && photo.alpha > 0f)) {
                    firstVisibleMs = SystemClock.uptimeMillis() - openStarted
                }
                true
            }
        }
    }

    override fun callActivityOnPause(activity: Activity) {
        if (observeExit && activity is ImageViewerActivity) exitFrame("before_pause")
        super.callActivityOnPause(activity)
        if (observeExit && activity is ImageViewerActivity) exitFrame("after_pause")
    }

    override fun callActivityOnStop(activity: Activity) {
        if (observeExit && activity is ImageViewerActivity) exitFrame("before_stop")
        super.callActivityOnStop(activity)
        if (observeExit && activity is ImageViewerActivity) exitFrame("after_stop")
    }

    private fun exitFrame(phase: String) {
        val surface = layer()
        exitFrames.put(JSONObject().put("phase", phase).put("t", SystemClock.uptimeMillis())
            .put("ready", surface?.isFrameReady == true).put("attached", surface?.isAttachedToWindow == true)
            .put("strength", surface?.lastSubmittedStrength?.takeIf { it.isFinite() } ?: -1f))
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        captureScreenshots = arguments?.getString("screenshots") == "true"
        transitionsOnly = arguments?.getString("transitions") == "true"
        pixelsOnly = arguments?.getString("pixels") == "true"
        lifecycleOnly = arguments?.getString("lifecycle") == "true"
        latencyOnly = arguments?.getString("latency") == "true"
        openingOnly = arguments?.getString("opening") == "true"
        start()
    }

    override fun onStart() {
        output = File(targetContext.getExternalFilesDir(null), "hdr-viewer-regression").apply { mkdirs() }
        val result = JSONObject()
        try {
            HdrViewerDiagnostics.recordingEnabled = true
            val input = File(targetContext.cacheDir, "hdr-viewer-regression").apply { mkdirs() }
            if (pixelsOnly) {
                pixelBench(input, result)
                result.put("status", "passed")
                return
            }
            val native = File(input, "native.jpg").also(HdrProbeImage::builtin)
            val sdr = File(input, "sdr.jpg")
            val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(30, 140, 80)) }
            sdr.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
            val motion = File(input, "motion.jpg")
            motion.outputStream().use { out -> native.inputStream().use { it.copyTo(out) }; File(input, "video.mp4").inputStream().use { it.copyTo(out) } }
            val files = arrayListOf("0${native.path}", "0${File(input, "app5-first.jpg").path}",
                "0${File(input, "app5-second.jpg").path}", "0${sdr.path}", "0${File(input, "animated.gif").path}",
                "0${motion.path}", "1${File(input, "video.mp4").path}")

            if (openingOnly) {
                opening(files, result)
                result.put("status", "passed")
                return
            }

            if (transitionsOnly) {
                transitions(files, result)
                result.put("status", "passed")
                return
            }
            if (lifecycleOnly) {
                lifecycle(files, result)
                result.put("status", "passed")
                return
            }
            if (latencyOnly) {
                latency(files, result)
                result.put("status", "passed")
                return
            }

            HdrViewerDiagnostics.forceCompatibility = false
            open(files)
            await("normal_native_loaded") { photo().drawable != null && current().findViewById<View>(R.id.tv_hdr_badge).visibility == View.VISIBLE }
            record("normal_android_unchanged", ui { field<Any?>(current(), "mPqController") == null &&
                current().window.colorMode == ActivityInfo.COLOR_MODE_HDR && layer() == null })
            closeActivity()

            HdrViewerDiagnostics.forceCompatibility = true
            open(files)
            awaitLayer()
            val nativeSize = ui { (photo().drawable as BitmapDrawable).bitmap.let { it.width to it.height } }
            checks.put(JSONObject().put("nativePhotoWidth", nativeSize.first).put("nativePhotoHeight", nativeSize.second))
            record("small_image_not_upscaled", nativeSize.first <= 960 && nativeSize.second <= 640)
            record("native_pq_visible", true)
            capture("01-native-pq")
            val original = ui { RectF(photo().displayRect) }
            doubleTap()
            await("double_tap_zoom") { kotlin.math.abs(photo().scale - 3f) < 0.0001f }
            awaitTransform()
            val zoom = ui { RectF(photo().displayRect) }
            record("zoom_keeps_geometry", zoom.width() > original.width() * 2.9f)
            capture("02-native-zoom")
            pan()
            awaitTransform()
            val panned = ui { RectF(photo().displayRect) }
            record("pan_changes_position", kotlin.math.abs(panned.left - zoom.left) > 5f || kotlin.math.abs(panned.top - zoom.top) > 5f)
            ui { current().findViewById<View>(R.id.tv_hdr_badge).performClick() }
            val sameLayer = ui { layer() }
            await("hdr_off") { layer()?.lastSubmittedStrength == 0f }
            record("hdr_off_reuses_surface", ui { layer() === sameLayer })
            record("hdr_off_preserves_zoom", sameRect(panned, ui { RectF(photo().displayRect) }))
            ui { current().findViewById<View>(R.id.tv_hdr_badge).performClick() }
            awaitLayer()
            await("hdr_on") { layer()?.lastSubmittedStrength == 1f }
            record("hdr_on_preserves_zoom", sameRect(panned, ui { RectF(photo().displayRect) }))

            // 在真实控制器中注入不可上传的纹理尺寸，覆盖无首帧时的 Callback2 清理与 SDR 回退。
            val controller = ui { field<HdrViewerController>(current(), "mPqController") }
            val validData = ui { field<HdrGpuData>(controller, "data") }
            ui {
                controller.javaClass.getDeclaredMethod("removeSurface", String::class.java).apply { isAccessible = true }.invoke(controller, "regression_failure")
                setField(controller, "data", validData.copy(width = -1))
                invoke(current(), "applyHdrStateForCurrentPage")
            }
            await("failed_surface_fallback") { layer() == null && field<HdrGpuData?>(controller, "data") == null }
            record("egl_failure_keeps_original_photo", ui { !(photo().drawable as BitmapDrawable).bitmap.isRecycled } && sameRect(panned, ui { RectF(photo().displayRect) }))
            ui { setField(controller, "data", validData); invoke(current(), "applyHdrStateForCurrentPage") }
            awaitLayer()

            for (index in 1..2) {
                page(index)
                awaitLayer()
                awaitTransform()
                record("app5_$index", ui { field<HdrGpuData>(layer()!!, "data").description.contains("APP5_EXPERIMENTAL") })
                capture("0${index + 2}-app5")
            }
            ui { current().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("landscape") { current().resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && photo().width > photo().height }
            awaitTransform()
            capture("05-landscape")
            ui { current().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await("portrait") { current().resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT && photo().height > photo().width }
            awaitTransform()
            record("rotation_preserves_alignment", true)

            page(3)
            await("sdr_loaded") { photo().drawable != null && layer() == null && current().findViewById<View>(R.id.tv_hdr_badge).visibility == View.GONE }
            record("sdr_unchanged", true)
            page(4)
            await("gif_running") { (photo().drawable as? Animatable)?.isRunning == true && layer() == null }
            record("gif_animated", true)

            page(5)
            await("motion_autoplay", 10000) { field<Any?>(current(), "mMotionPlayer") != null &&
                tab().findViewById<TextureView>(R.id.tv_motion_surface).alpha == 1f && layer()?.isFrameReady == true &&
                layer()?.lastSubmittedStrength == 0f }
            record("motion_autoplay_pq_ready_below_video", true)
            await("motion_finished", 12000) { field<Any?>(current(), "mMotionPlayer") == null && layer()?.isFrameReady == true }
            doubleTap()
            await("motion_zoom") { kotlin.math.abs(photo().scale - 3f) < 0.0001f }
            val motionRect = ui { RectF(photo().displayRect) }
            val point = location()
            val down = SystemClock.uptimeMillis()
            pointer(down, down, MotionEvent.ACTION_DOWN, point.first, point.second)
            await("motion_hold") { field<Boolean>(current(), "mMotionHoldToPlay") && layer()?.isFrameReady == true &&
                tab().findViewById<TextureView>(R.id.tv_motion_surface).alpha == 1f }
            pointer(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, point.first, point.second)
            awaitLayer()
            val afterMotion = ui { RectF(photo().displayRect) }
            checks.put(JSONObject().put("motion_before", motionRect.toString()).put("motion_after", afterMotion.toString()))
            record("motion_hold_restores_zoom", sameRect(motionRect, afterMotion))
            page(6)
            await("video_autoplay", 10000) { field<Any?>(current(), "mMotionPlayer") != null && layer() == null }
            record("video_unaffected", true)
            page(0)
            awaitLayer()

            // 真实窗口交接，验证后台释放和回来重建，不模拟系统回调。
            val diagnostic = startActivitySync(Intent(targetContext, HdrDiagnosticsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HdrDiagnosticsActivity
            await("paused_release") { field<Boolean>(current(), "mViewerResumed").not() && layer() == null }
            ui { diagnostic.finish() }
            awaitLayer()
            record("pause_resume_recreates", true)

            // 验证空间模式的公共状态入口会撤掉 PQ，退出后恢复；不启动模型推理。
            ui { setField(current(), "mSpatialMode", true); invoke(current(), "applyHdrStateForCurrentPage") }
            record("spatial_suspends", ui { layer() == null && current().findViewById<View>(R.id.tv_hdr_badge).visibility == View.GONE })
            ui { setField(current(), "mSpatialMode", false); invoke(current(), "applyHdrStateForCurrentPage") }
            awaitLayer()
            record("spatial_exit_restores", true)
            val export = File(output, "viewer-export.txt").apply { writeText("HDR 查看器实机验证\n") }
            HdrViewerDiagnostics.appendSnapshot(targetContext, export)
            record("diagnostic_export", export.readText().contains("VIEWER_PQ_VISIBLE") && export.readText().contains("APP5_EXPERIMENTAL"))
            closeActivity()
            open(files, 2)
            awaitLayer()
            record("initial_nonzero_page", ui { pager().currentItem == 2 && field<HdrGpuData>(layer()!!, "data").description.contains("APP5_EXPERIMENTAL") })
            result.put("status", "passed")
        } catch (error: Throwable) {
            result.put("status", "failed").put("error", error.stackTraceToString())
        } finally {
            openingBlock?.countDown(); openingBlock = null
            closeActivity()
            HdrViewerDiagnostics.forceCompatibility = false
            captures.forEach { it.recycle() }
            result.put("checks", checks)
            File(output, "result.json").writeText(result.toString(2))
            HdrViewerDiagnostics.recordingEnabled = false
            finish(if (result.optString("status") == "passed") 0 else 1, Bundle().apply { putString("result", result.toString()) })
        }
    }

    /** 首次揭示必须来自连续的 PQ 输出；逐帧观察真实页面，包含 7 页中的非零起始页。 */
    private fun opening(files: ArrayList<String>, result: JSONObject) {
        HdrViewerDiagnostics.forceCompatibility = true
        val samples = JSONArray(); result.put("opening", samples)
        var noEarlyPhoto = true
        var noDuplicateLayer = true
        var noNativePixels = true
        val large = File(targetContext.cacheDir, "hdr-viewer-regression/opening-large.jpg")
        val largeBase = Bitmap.createBitmap(4368, 5824, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(170, 170, 170)); setHasAlpha(false)
        }
        val largeGain = Bitmap.createBitmap(1092, 1456, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(160, 160, 160)) }
        largeBase.gainmap = Gainmap(largeGain).apply { setRatioMax(4f, 4f, 4f); displayRatioForFullHdr = 4f }
        large.outputStream().use { check(largeBase.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        largeGain.recycle(); largeBase.recycle()
        val sources = files.take(3) + "0${large.path}"
        for (index in sources.indices) repeat(2) { run ->
            openingFrames = JSONArray()
            val pages = ArrayList(files).apply { this[1] = sources[index] }
            open(pages, 1)
            awaitLayer()
            await("opening_full_gain") { layer()?.lastSubmittedStrength == 1f }
            val frames = ui { JSONArray(openingFrames.toString()) }
            val observations = (0 until frames.length()).map { frames.getJSONObject(it) }
            val early = observations.count { !it.getBoolean("ready") && it.getDouble("photoAlpha") > 0.0 }
            val layers = observations.map { it.getInt("layer") }.filter { it != 0 }.distinct()
            samples.put(JSONObject().put("image", index).put("run", run).put("earlyPhotoFrames", early)
                .put("layers", JSONArray(layers)).put("frames", frames).put("loadedMs", firstBaseMs).put("visibleMs", firstVisibleMs))
            noEarlyPhoto = noEarlyPhoto && early == 0
            noDuplicateLayer = noDuplicateLayer && layers.size == 1
            noNativePixels = noNativePixels && ui { photo().alpha == 0f && tab().findViewById<View>(R.id.pb_image_attachment).visibility == View.GONE }
            closeActivity()
        }
        record("opening_single_surface", noDuplicateLayer)
        record("opening_no_native_photo_before_pq", noEarlyPhoto)
        record("opening_pq_owns_pixels_photoview_keeps_drawable", noNativePixels)

        open(arrayListOf(files[3]))
        await("sdr_revealed") { photo().drawable != null && photo().alpha == 1f && tab().findViewById<View>(R.id.pb_image_attachment).visibility == View.GONE }
        record("opening_sdr_no_gpu_or_pixel_copy", ui { layer() == null && field<Any?>(field<HdrViewerController>(current(), "mPqController"), "pendingSnapshot") == null })
        closeActivity()

        // 使用不同路径避免 Glide 内存命中，阻塞真实准备队列来覆盖首帧前的用户操作及超时。
        for (timeout in listOf(false, true)) {
            val delayed = File(large.parentFile, if (timeout) "opening-timeout.jpg" else "opening-forced-sdr.jpg")
            large.copyTo(delayed, overwrite = true)
            openingBlock = java.util.concurrent.CountDownLatch(1)
            open(arrayListOf("0${delayed.path}"))
            await("opening_waiting") { photo().drawable != null && photo().alpha == 0f }
            if (!timeout) ui { current().findViewById<View>(R.id.tv_hdr_badge).performClick() }
            await("opening_fallback", 7000) { photo().alpha == 1f && tab().findViewById<View>(R.id.pb_image_attachment).visibility == View.GONE }
            openingBlock?.countDown(); openingBlock = null
            await("opening_worker_finished") { field<Boolean>(field<HdrViewerController>(current(), "mPqController"), "loaded") }
            SystemClock.sleep(250)
            record(if (timeout) "opening_timeout_ignores_late_data" else "opening_forced_sdr_during_prepare", ui {
                layer() == null && photo().alpha == 1f && !(photo().drawable as BitmapDrawable).bitmap.isRecycled
            })
            closeActivity()
        }
    }

    /** 真实自动播放、长按松手和返回生命周期；检查交接过程，不能只验证最终画面恢复。 */
    private fun lifecycle(files: ArrayList<String>, result: JSONObject) {
        HdrViewerDiagnostics.forceCompatibility = true
        val findings = linkedMapOf<String, Boolean>()
        open(arrayListOf(files[5]))
        await("motion_auto_visible", 10000) { field<Any?>(current(), "mMotionPlayer") != null &&
            tab().findViewById<TextureView>(R.id.tv_motion_surface).alpha == 1f }
        // 留出后台准备的时间，随后要求静图输出在视频下方已准备好。
        SystemClock.sleep(350)
        val duringAuto = ui { layer() }
        findings["motion_prepares_pq_under_video"] = duringAuto?.isFrameReady == true
        val samples = JSONArray(); result.put("motionFrames", samples)
        var stoppedAt = 0L
        val until = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < until) {
            val done = ui {
                val now = SystemClock.uptimeMillis()
                val playing = field<Any?>(current(), "mMotionPlayer") != null
                if (!playing && stoppedAt == 0L) stoppedAt = now
                val s = layer()
                samples.put(JSONObject().put("t", now).put("playing", playing)
                    .put("videoAlpha", tab().findViewById<TextureView>(R.id.tv_motion_surface).alpha)
                    .put("surface", s?.let { System.identityHashCode(it) } ?: 0).put("ready", s?.isFrameReady == true)
                    .put("strength", s?.lastSubmittedStrength?.takeIf { it.isFinite() } ?: -1f))
                stoppedAt > 0 && now - stoppedAt > 350
            }
            if (done) break
            SystemClock.sleep(8)
        }
        awaitLayer()
        findings["motion_completion_keeps_surface"] = duringAuto != null && ui { layer() === duringAuto }
        findings["motion_completion_has_no_unready_frame"] = stoppedAt > 0 &&
            (0 until samples.length()).map { samples.getJSONObject(it) }.filter { !it.getBoolean("playing") }
                .all { it.getBoolean("ready") }
        val beforeHold = ui { layer() }
        val point = location(); val down = SystemClock.uptimeMillis()
        pointer(down, down, MotionEvent.ACTION_DOWN, point.first, point.second)
        await("hold_video_visible") { field<Boolean>(current(), "mMotionHoldToPlay") &&
            tab().findViewById<TextureView>(R.id.tv_motion_surface).alpha == 1f }
        findings["motion_hold_keeps_surface"] = ui { layer() === beforeHold && layer()?.isFrameReady == true }
        pointer(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, point.first, point.second)
        findings["motion_release_keeps_surface"] = ui { layer() === beforeHold && layer()?.isFrameReady == true }
        awaitLayer()
        await("boost_restored") { layer()?.lastSubmittedStrength == 1f }
        observeExit = true
        ui { current().onBackPressedDispatcher.onBackPressed() }
        await("viewer_stopped") { (0 until exitFrames.length()).any { exitFrames.getJSONObject(it).optString("phase") == "after_stop" } }
        observeExit = false
        result.put("exitFrames", exitFrames)
        findings["back_keeps_last_hdr_frame_through_pause"] = (0 until exitFrames.length())
            .map { exitFrames.getJSONObject(it) }.firstOrNull { it.optString("phase") == "after_pause" }
            ?.let { it.getBoolean("ready") && it.getBoolean("attached") && it.getDouble("strength") == 1.0 } == true
        findings["stop_releases_output"] = ui { layer() == null }
        activity = null

        // 自动播放中接管为长按、播放时切换 HDR，不应使旧播放器回调或增益动画揭示错误画面。
        open(arrayListOf(files[5]))
        await("takeover_auto") { field<Any?>(current(), "mMotionPlayer") != null && layer()?.isFrameReady == true &&
            tab().findViewById<TextureView>(R.id.tv_motion_surface).alpha == 1f }
        val takeoverLayer = ui { layer() }
        val oldPlayer = ui { field<Any?>(current(), "mMotionPlayer") }
        val takeoverPoint = location(); val takeoverDown = SystemClock.uptimeMillis()
        pointer(takeoverDown, takeoverDown, MotionEvent.ACTION_DOWN, takeoverPoint.first, takeoverPoint.second)
        await("hold_took_over") { field<Boolean>(current(), "mMotionHoldToPlay") &&
            field<Any?>(current(), "mMotionPlayer") !== oldPlayer && layer()?.lastSubmittedStrength == 0f }
        findings["autoplay_to_hold_reuses_surface"] = ui { layer() === takeoverLayer }
        ui { current().findViewById<View>(R.id.tv_hdr_badge).performClick() }
        ui { current().findViewById<View>(R.id.tv_hdr_badge).performClick() }
        SystemClock.sleep(250)
        findings["video_stays_sdr_during_hdr_toggle"] = ui { layer() === takeoverLayer && layer()?.lastSubmittedStrength == 0f &&
            field<Any?>(current(), "mMotionPlayer") != null }
        ui { current().findViewById<View>(R.id.tv_hdr_badge).performClick() }
        pointer(takeoverDown, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, takeoverPoint.first, takeoverPoint.second)
        await("forced_sdr_release") { field<Any?>(current(), "mMotionPlayer") == null }
        SystemClock.sleep(220)
        findings["forced_sdr_kept_after_motion"] = ui { layer() === takeoverLayer && layer()?.lastSubmittedStrength == 0f }
        closeActivity()
        findings.forEach { (name, passed) -> checks.put(JSONObject().put("name", name).put("passed", passed)) }
        check(findings.values.all { it }) { "lifecycle_handoff: ${findings.filterValues { !it }.keys}" }
    }

    /** 真实徽标点击与首帧计时：只改变 HDR 开关时不能销毁已显示的 Surface。 */
    private fun latency(files: ArrayList<String>, result: JSONObject) {
        HdrViewerDiagnostics.forceCompatibility = true
        val samples = JSONArray(); result.put("latency", samples)
        for (index in 0..2) repeat(2) { run ->
            open(arrayListOf(files[index]))
            awaitLayer()
            val s = ui { layer()!! }
            await("full_hdr_frame") { s.fullGainUptimeMs > 0 && firstBaseMs >= 0 }
            samples.put(JSONObject().put("image", index).put("run", run).put("baseMs", firstBaseMs)
                .put("visibleMs", firstVisibleMs)
                .put("pqMs", s.readyUptimeMs - openStarted).put("gainMs", s.firstGainUptimeMs - openStarted)
                .put("fullMs", s.fullGainUptimeMs - openStarted)
                .put("readyToGainMs", s.firstGainUptimeMs - s.readyUptimeMs)
                .put("readyToFullMs", s.fullGainUptimeMs - s.readyUptimeMs))
            closeActivity()
        }
        open(arrayListOf(files[0])); awaitLayer()
        val s = ui { layer()!! }
        await("initial_gain") { s.lastSubmittedStrength == 1f }
        ui { current().findViewById<View>(R.id.tv_hdr_badge).performClick() }
        await("sdr_before_busy_ui") { s.lastSubmittedStrength == 0f }
        val progressed = ui {
            current().findViewById<View>(R.id.tv_hdr_badge).performClick()
            // 模拟启动期间真实存在的主线程长任务，检查 GPU 过渡是否仍被该队列阻塞。
            SystemClock.sleep(120)
            s.lastSubmittedStrength
        }
        result.put("gainDuringBusyMain", progressed)
        record("gain_progresses_during_busy_main", progressed > 0.2f)
    }

    /** 真实徽标点击与首帧计时：只改变 HDR 开关时不能销毁已显示的 Surface。 */
    private fun transitions(files: ArrayList<String>, result: JSONObject) {
        HdrViewerDiagnostics.forceCompatibility = true
        val samples = JSONArray()
        result.put("transitions", samples)
        var allReused = true
        for (index in 0..2) repeat(2) { run ->
            val started = SystemClock.uptimeMillis()
            open(arrayListOf(files[index]))
            await("base_loaded", 15000) { photo().drawable != null }
            awaitLayer()
            await("visible_frame") { firstPqMs >= 0 }
            val baseMs = firstBaseMs
            val readyMs = firstPqMs
            val original = ui { layer() }
            val entry = JSONObject().put("image", index).put("run", run)
                .put("baseMs", baseMs).put("pqMs", readyMs).put("afterBaseMs", readyMs - baseMs)
            ui { (photo().drawable as BitmapDrawable).bitmap.let { entry.put("photoWidth", it.width).put("photoHeight", it.height) } }
            samples.put(entry)
            val gaps = JSONArray()
            await("initial_boost") { original?.lastSubmittedStrength == 1f }
            repeat(4) {
                ui { current().findViewById<View>(R.id.tv_hdr_badge).performClick() }
                val until = SystemClock.uptimeMillis() + 340
                var missing = 0
                var changed = false
                val strengths = mutableListOf<Float>()
                while (SystemClock.uptimeMillis() < until) {
                    ui {
                        if (layer()?.isFrameReady != true) missing++
                        if (layer() !== original) changed = true
                        layer()?.lastSubmittedStrength?.takeIf { it.isFinite() }?.let {
                            if (strengths.lastOrNull() != it) strengths.add(it)
                        }
                    }
                    SystemClock.sleep(8)
                }
                val target = if (it % 2 == 0) 0f else 1f
                val monotonic = strengths.zipWithNext().all { (a, b) -> if (target == 0f) b <= a else b >= a }
                gaps.put(JSONObject().put("missingSamples", missing).put("surfaceChanged", changed)
                    .put("strengthFrames", JSONArray(strengths)).put("monotonic", monotonic))
                allReused = allReused && !changed && missing == 0
                check(strengths.count { it > 0f && it < 1f } >= 3 && strengths.last() == target && monotonic) { "gain_transition_discontinuous" }
            }
            entry.put("toggles", gaps)
            repeat(6) {
                ui { current().findViewById<View>(R.id.tv_hdr_badge).performClick(); check(layer() === original) }
                SystemClock.sleep(25)
            }
            await("rapid_reversal_finishes") { layer() === original && layer()?.lastSubmittedStrength == 1f }
            closeActivity()
        }
        record("toggle_keeps_presented_surface", allReused)
        record("toggle_submits_intermediate_gain_frames", true)
        record("rapid_reversal_reuses_surface_and_finishes", true)
    }

    /** 固定屏幕解码上限，隔离原图像素、准备算法及上传字节，不把样本生成计入耗时。 */
    private fun pixelBench(input: File, result: JSONObject) {
        val samples = JSONArray(); result.put("pixels", samples)
        for ((width, height) in listOf(960 to 640, 2048 to 1536, 4096 to 3072, 5824 to 4368)) {
            val file = File(input, "pixels-${width}x$height.jpg")
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
            val gain = Bitmap.createBitmap(width / 4, height / 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(160, 160, 160)) }
            val canvas = Canvas(bitmap); val paint = Paint()
            for (i in 0..15) {
                paint.color = Color.rgb(i * 15, 200 - i * 10, 100 + i * 8)
                canvas.drawRect(i * width / 16f, 0f, (i + 1) * width / 16f, height.toFloat(), paint)
            }
            bitmap.gainmap = Gainmap(gain).apply { setRatioMax(4f, 4f, 4f); displayRatioForFullHdr = 4f }
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            gain.recycle(); bitmap.recycle()
            val entry = JSONObject().put("encodedWidth", width).put("encodedHeight", height).put("fileBytes", file.length())
            samples.put(entry)
            val old = JSONArray(); val optimized = JSONArray()
            entry.put("independentDecode", old).put("reuseDecodedBitmap", optimized)
            repeat(2) {
                // 两路都在已有 SDR 位图之后计额外工作；显式 GC 仅用于测试隔离，不进入产品代码。
                System.gc()
                val source = HdrProbeImage.decode(file, 2640, false, 8_000_000)
                try {
                    entry.put("decodedWidth", source.base.width).put("decodedHeight", source.base.height)
                    val oldStart = System.nanoTime()
                    val duplicate = HdrProbeImage.decode(file, 2640, false, 8_000_000)
                    val decoded = System.nanoTime()
                    try {
                        val data = duplicate.gpuData(true, false)
                        old.put(JSONObject().put("decodeMs", (decoded - oldStart) / 1e6)
                            .put("totalMs", (System.nanoTime() - oldStart) / 1e6).put("baseBytes", data.base.remaining()))
                    } finally { duplicate.close() }
                    val start = System.nanoTime()
                    val snapshot = HdrProbeImage.copyForViewer(source.bitmap)
                    val copied = System.nanoTime()
                    check(snapshot.hasGainmap() && snapshot.gainmap!!.gainmapContents !== source.bitmap.gainmap!!.gainmapContents)
                    val image = HdrProbeImage.fromOwnedBitmap(file, snapshot)
                    try {
                        val data = image.gpuData(true, false, true)
                        optimized.put(JSONObject().put("snapshotMs", (copied - start) / 1e6)
                            .put("totalMs", (System.nanoTime() - start) / 1e6).put("baseBytes", data.base.remaining()))
                    } finally { image.close() }
                    check(!source.bitmap.isRecycled && !source.bitmap.gainmap!!.gainmapContents.isRecycled)
                } finally { source.close() }
            }
        }
        record("snapshot_owns_gainmap_and_preserves_source", true)
        record("pixel_budget_and_upload_cases", samples.length() == 4)
    }

    private fun open(files: ArrayList<String>, position: Int = 0) {
        openStarted = SystemClock.uptimeMillis(); firstBaseMs = -1; firstPqMs = -1; firstVisibleMs = -1
        activity = startActivitySync(Intent(targetContext, ImageViewerActivity::class.java)
            .putStringArrayListExtra(Def.Communication.KEY_TYPE_PATH_NAME, files)
            .putExtra(Def.Communication.KEY_POSITION, position)
            .putExtra(Def.Communication.KEY_EDITABLE, false)
            .putExtra(Def.Communication.KEY_COLOR, Color.BLUE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ImageViewerActivity
    }
    private fun closeActivity() { activity?.let { ui { it.finish() }; waitForIdleSync() }; activity = null }
    private fun current() = checkNotNull(activity)
    private fun pager() = current().findViewById<ViewPager>(R.id.vp_image_viewer)
    private fun tab() = field<List<View>>(current(), "mTabs")[pager().currentItem]
    private fun photo() = tab().findViewById<PhotoView>(R.id.iv_image_attachment)
    private fun layer(): HdrProbeSurface? = (tab() as ViewGroup).let { group ->
        (0 until group.childCount).map { group.getChildAt(it) }.filterIsInstance<HdrProbeSurface>().firstOrNull()
    }
    private fun page(index: Int) { ui { pager().setCurrentItem(index, true) }; await("page_$index") { pager().currentItem == index && field<Boolean>(current(), "mPagerIdle") } }
    private fun awaitLayer() = await("pq_frame", 15000) { layer()?.isFrameReady == true }
    private fun awaitTransform() = await("matrix") {
        val values = FloatArray(9); photo().imageMatrix.getValues(values)
        val expected = HdrViewerGeometry.transform(values, photo().drawable.intrinsicWidth, photo().drawable.intrinsicHeight, photo().width, photo().height)
        val actual = layer()?.let { field<FloatArray?>(it, "imageTransform") }
        actual != null && expected.indices.all { kotlin.math.abs(expected[it] - actual[it]) < 0.001f }
    }
    private fun <T> ui(block: () -> T): T { var result: Result<T>? = null; runOnMainSync { result = runCatching(block) }; return result!!.getOrThrow() }
    private fun await(name: String, timeout: Long = 6000, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < until) { if (ui(condition)) return; SystemClock.sleep(40) }
        error("Timeout: $name")
    }
    private fun record(name: String, passed: Boolean) { checks.put(JSONObject().put("name", name).put("passed", passed)); check(passed) { name } }
    @Suppress("UNCHECKED_CAST") private fun <T> field(target: Any, name: String): T = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T
    private fun setField(target: Any, name: String, value: Any) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    private fun invoke(target: Any, name: String) = target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target)
    private fun sameRect(a: RectF, b: RectF) = kotlin.math.abs(a.left - b.left) < 1f && kotlin.math.abs(a.top - b.top) < 1f && kotlin.math.abs(a.width() - b.width()) < 1f
    private fun location(): Pair<Float, Float> = ui { val xy = IntArray(2); photo().getLocationOnScreen(xy); xy[0] + photo().width * 0.5f to xy[1] + photo().height * 0.5f }
    private fun pointer(down: Long, time: Long, action: Int, x: Float, y: Float) { val event = MotionEvent.obtain(down, time, action, x, y, 0); try { sendPointerSync(event) } finally { event.recycle() } }
    private fun doubleTap() { val p = location(); repeat(2) { val t = SystemClock.uptimeMillis(); pointer(t, t, 0, p.first, p.second); pointer(t, t + 20, 1, p.first, p.second); SystemClock.sleep(65) } }
    private fun pan() { val p = location(); val t = SystemClock.uptimeMillis(); pointer(t, t, 0, p.first, p.second)
        for (i in 1..8) { SystemClock.sleep(20); pointer(t, SystemClock.uptimeMillis(), 2, p.first + i * 15, p.second + i * 8) }
        pointer(t, SystemClock.uptimeMillis(), 1, p.first + 120, p.second + 64)
        // 等待 PhotoView 的惯性平移收敛。
        var last = ui { RectF(photo().displayRect) }; var stable = 0
        repeat(100) { SystemClock.sleep(40); val next = ui { RectF(photo().displayRect) }; stable = if (sameRect(last, next)) stable + 1 else 0; last = next; if (stable >= 4) return }
    }
    private fun capture(name: String) {
        if (!captureScreenshots) return
        // 旋转时系统会短暂保留旧方向的过渡截图；等实际合成稳定后再记录。
        var bitmap = uiAutomation.takeScreenshot() ?: error("Screenshot failed")
        var stable = 0
        val until = SystemClock.uptimeMillis() + 2500
        while (SystemClock.uptimeMillis() < until && stable < 2) {
            SystemClock.sleep(120)
            val next = uiAutomation.takeScreenshot() ?: continue
            stable = if (bitmap.sameAs(next)) stable + 1 else 0
            bitmap.recycle(); bitmap = next
        }
        captures.add(bitmap)
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
