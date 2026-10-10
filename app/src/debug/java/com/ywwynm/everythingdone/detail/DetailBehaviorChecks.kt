package com.ywwynm.everythingdone.detail

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.ClipboardManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.text.style.URLSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.activities.ImageViewerActivity
import com.ywwynm.everythingdone.adapters.AudioAttachmentAdapter
import com.ywwynm.everythingdone.adapters.CheckListAdapter
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.DetailContentLayout
import com.ywwynm.everythingdone.helpers.DetailScreenshotRenderer
import com.ywwynm.everythingdone.helpers.DetailAttachmentPlaybackController
import com.ywwynm.everythingdone.fragments.AudioPlayDialogFragment
import com.ywwynm.everythingdone.fragments.AttachmentInfoDialogFragment
import com.ywwynm.everythingdone.fragments.MediaCropAppearanceDialogFragment
import com.ywwynm.everythingdone.fragments.DateTimeDialogFragment
import com.ywwynm.everythingdone.fragments.ThingDoingDialogFragment
import com.ywwynm.everythingdone.fragments.AddAttachmentDialogFragment
import com.ywwynm.everythingdone.fragments.ThingBackgroundEditorBottomSheet
import com.ywwynm.everythingdone.fragments.TwoOptionsDialogFragment
import com.ywwynm.everythingdone.utils.KeyboardUtil
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.database.ThingFolderDAO
import com.ywwynm.everythingdone.views.ThingCardCropEditorController
import com.ywwynm.everythingdone.views.ThingCardVideoCropEditorView
import com.ywwynm.everythingdone.views.RatioSlider
import com.ywwynm.everythingdone.views.ThingBackgroundEditor
import com.ywwynm.everythingdone.taptransfer.ui.share.TapShareOverlay
import com.ywwynm.everythingdone.model.DetailAutoplayMode
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
import com.ywwynm.everythingdone.model.ThingBackground
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 对真实详情控件执行操作并断言业务结果；不替代跨屏拖拽与系统选择器等手势验收。 */
internal class DetailBehaviorChecks(
    private val instrumentation: Instrumentation,
    private val activity: DetailActivity,
    private val output: File,
    private val name: String
) {
    private val results = JSONArray()
    private var pointerTrace = JSONArray()
    private val content: DetailContentLayout = field("mDetailContent")
    private val list = content.recyclerView
    private val checklist: CheckListAdapter? = field("mCheckListAdapter")
    private val images: ImageAttachmentAdapter? = field("mImageAttachmentAdapter")
    private val audios: AudioAttachmentAdapter? = field("mAudioAttachmentAdapter")

    fun cancelExport() = verify("生成长图期间退出详情会取消渲染并安全释放资源") {
        val finished = CountDownLatch(1)
        var produced: Bitmap? = null
        main {
            val renderer = DetailScreenshotRenderer(activity, content, activity.getAccentBackground()!!,
                field("mEditable"), checklist, images?.getItems(), field("mDetailAttachmentMediaAppearance"),
                field("mMaxSpanImage"), audios?.getItems(), field("mSpanAudio"), field("mShouldAutoLink"))
            renderer.render { produced = it; finished.countDown() }
            activity.finish()
        }
        check(finished.await(10, TimeUnit.SECONDS)) { "退出后的长图任务没有完成取消" }
        check(produced == null) { "页面退出后仍继续生成长图" }
        instrumentation.waitForIdleSync()
    }

    fun run(kind: String) {
        if (kind == "edge_checklist") { checklistEdges(); return }
        if (kind == "drag_constraints") { dragConstraints(); return }
        if (kind == "picker_success") { realPickerSuccess(); return }
        if (kind == "media_failure") { mediaFailures(); return }
        if (kind == "export_failure") { exportFailures(); return }
        if (kind == "recording") { recording(); return }
        if (kind == "camera_cancel") { cameraCancel(); return }
        if (kind == "camera_capture") { cameraCancel(capture = true); return }
        if (kind == "workflow") { workflows(); export(); return }
        if (kind == "navigation" || kind == "links_plain") {
            scrollChrome()
            if (kind == "navigation") inPlaceCompletion()
            links(kind == "links_plain")
            export()
            return
        }
        verify("统一列表有界且没有嵌套内容列表") {
            main {
                check(list.height <= activity.window.decorView.height)
                check(list.adapter === content.adapter)
                check(content.adapter.adapters.count { it is CheckListAdapter } == if (kind == "plain") 0 else 1)
                check(activity.findViewById<RecyclerView?>(R.id.rv_check_list) == null)
                check(list.childCount < 40)
            }
        }
        if (kind == "readonly") {
            verify("只读清单与底栏状态") {
                val row = row(0)
                main {
                    check(row.et!!.keyListener == null)
                    check(activity.findViewById<View>(R.id.bottom_bar_shadow).alpha == 0f)
                }
            }
            export()
            return
        }
        title()
        if (kind == "plain") text() else checklist()
        if (kind == "checklist") gestures()
        if (kind == "mixed") media()
        if (kind == "dynamic") dynamicMedia()
        if (kind == "mixed") appearanceChanges()
        colors()
        export()
    }

    private fun cameraCancel(capture: Boolean = false) {
        for ((label, action) in listOf("拍照" to R.id.tv_take_photo_as_bt, "录像" to R.id.tv_shoot_video_as_bt)) {
            verify("实际${label}${if (capture) "成功保存、解码与撤销" else "入口返回取消"}，清单草稿保持") {
                val editor = row(14).et!!
                main { editor.requestFocus(); editor.append("【${label}前草稿】"); editor.setSelection(2, 5) }
                val before = snapshot()
                val original = main { field<ImageAttachmentAdapter?>("mImageAttachmentAdapter")?.getItems()?.toList().orEmpty() }
                var uri: android.net.Uri? = null
                var file: File? = null
                fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes(); Unit }
                fun tap(node: android.view.accessibility.AccessibilityNodeInfo?) {
                    check(node != null) { "目标控件尚未出现" }
                    node.refresh()
                    val bounds = Rect(); node.getBoundsInScreen(bounds)
                    check(!bounds.isEmpty)
                    shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                }
                try {
                    menu(R.id.act_add_attachment)
                    await { dialogAppeared(activity.supportFragmentManager.findFragmentByTag(AddAttachmentDialogFragment.TAG) as? AddAttachmentDialogFragment) }
                    main {
                        activity.supportFragmentManager.findFragmentByTag(AddAttachmentDialogFragment.TAG)!!.requireView()
                            .findViewById<View>(action).performClick()
                    }
                    await(10000) { !activity.hasWindowFocus() }
                    var owner = ""
                    val deadline = SystemClock.uptimeMillis() + 10000
                    while (SystemClock.uptimeMillis() < deadline) {
                        val root = instrumentation.uiAutomation.rootInActiveWindow
                        owner = root?.packageName?.toString().orEmpty()
                        if (owner.contains("permissioncontroller")) {
                            root?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_allow_one_time_button")
                                ?.singleOrNull()?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                        } else if (owner == "android" && root?.findAccessibilityNodeInfosByText("相机")?.isNotEmpty() == true) {
                            root.findAccessibilityNodeInfosByText("打开").firstOrNull { it.text?.toString() == "打开" }?.let { button ->
                                runCatching { instrumentation.uiAutomation.waitForIdle(250, 2000) }
                                tap(button)
                            }
                        }
                        if (owner.contains("camera", true)) {
                            val nodes = JSONArray()
                            fun collect(node: android.view.accessibility.AccessibilityNodeInfo) {
                                val bounds = Rect(); node.getBoundsInScreen(bounds)
                                nodes.put(JSONObject().put("id", node.viewIdResourceName).put("text", node.text)
                                    .put("description", node.contentDescription).put("bounds", bounds.toShortString()).put("clickable", node.isClickable))
                                for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
                            }
                            collect(root!!)
                            File(output, "$name-$label-camera-nodes.json").writeText(nodes.toString(2))
                            break
                        }
                        SystemClock.sleep(100)
                    }
                    check(owner.contains("camera", true)) { "尚未进入相机，当前窗口：$owner" }
                    main {
                        uri = activity.cameraOutputUri
                        file = activity.attachmentTypePathName?.substring(1)?.let(::File)
                    }
                    check(uri != null && file != null) { "相机请求未创建输出" }
                    if (capture) {
                        val buttonId = when (owner) {
                            "com.sec.android.app.camera" -> "$owner:id/center_button_container"
                            "com.oplus.camera" -> "$owner:id/shutter_button"
                            "com.flyme.bxcamera" -> "$owner:id/shutter_button"
                            else -> error("本机相机拍摄控件尚未确认：$owner")
                        }
                        fun shutter() = instrumentation.uiAutomation.rootInActiveWindow?.apply { refresh() }
                            ?.findAccessibilityNodeInfosByViewId(buttonId)?.firstOrNull { it.isVisibleToUser && it.isEnabled }
                        val shutterDeadline = SystemClock.uptimeMillis() + 12000
                        while (SystemClock.uptimeMillis() < shutterDeadline) {
                            val button = shutter()
                            val description = button?.contentDescription?.toString().orEmpty()
                            if (button != null && (label == "拍照" || owner != "com.sec.android.app.camera" || description != "拍照")) break
                            SystemClock.sleep(100)
                        }
                        runCatching { instrumentation.uiAutomation.waitForIdle(300, 2000) }
                        if (owner == "com.oplus.camera") {
                            // 此平板相机自行切到横屏，首个辅助功能窗口仍可能是出现期间的竖屏布局。
                            val stableDeadline = SystemClock.uptimeMillis() + 12000
                            var ready = false
                            while (!ready && SystemClock.uptimeMillis() < stableDeadline) {
                                val activeRoot = instrumentation.uiAutomation.rootInActiveWindow?.apply { refresh() }
                                val rootBounds = Rect(); activeRoot?.getBoundsInScreen(rootBounds)
                                val buttonBounds = Rect(); shutter()?.apply { refresh(); getBoundsInScreen(buttonBounds) }
                                ready = rootBounds.width() > rootBounds.height() && buttonBounds.centerX() > rootBounds.width() * 3 / 4
                                File(output, "$name-camera-geometry.json").writeText(JSONObject().put("root", rootBounds.toShortString()).put("shutter", buttonBounds.toShortString()).put("ready", ready).toString())
                                if (!ready) SystemClock.sleep(100)
                            }
                            check(ready) { "相机横屏快门布局未稳定" }
                        }
                        val notices = java.util.Collections.synchronizedList(mutableListOf<String>())
                        instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
                            if (event.packageName?.toString() == owner &&
                                event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                                notices += event.text.joinToString()
                                File(output, "$name-camera-notices.json").writeText(JSONArray(notices.toList()).toString(2))
                            }
                        }
                        tap(shutter())
                        if (owner == "com.oplus.camera") {
                            SystemClock.sleep(600)
                            instrumentation.uiAutomation.takeScreenshot()?.let { shot ->
                                File(output, "$name-after-shutter.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                shot.recycle()
                            }
                        }
                        if (label == "录像") {
                            SystemClock.sleep(2200)
                            val seen = JSONArray()
                            fun findStop(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                                val description = node.contentDescription?.toString().orEmpty()
                                if (description.isNotEmpty()) seen.put("${node.viewIdResourceName}: $description")
                                if (node.isClickable && node.isEnabled && (description.contains("停止") || description.contains("Stop", true))) return node
                                for (index in 0 until node.childCount) node.getChild(index)?.let { findStop(it)?.let { found -> return found } }
                                return null
                            }
                            val stop = instrumentation.uiAutomation.rootInActiveWindow?.let(::findStop)
                            File(output, "$name-recording-controls.json").writeText(seen.toString(2))
                            tap(stop ?: shutter())
                        }
                        val saveDeadline = SystemClock.uptimeMillis() + 15000
                        val seen = linkedSetOf<String>()
                        while (!main { activity.hasWindowFocus() } && SystemClock.uptimeMillis() < saveDeadline) {
                            val root = instrumentation.uiAutomation.rootInActiveWindow
                            if (root?.packageName?.toString() == owner) {
                                fun findSave(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                                    val words = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                                    if (words.isNotEmpty() || node.isClickable) seen += "${node.viewIdResourceName}: ${words.joinToString()} [clickable=${node.isClickable}, enabled=${node.isEnabled}]"
                                    if (node.isClickable && node.isEnabled && (node.viewIdResourceName == "com.oplus.camera:id/done_button" ||
                                        words.any { it in listOf("确定", "完成", "保存", "确认", "使用照片", "使用视频", "OK", "Done", "Save") })) return node
                                    for (index in 0 until node.childCount) node.getChild(index)?.let { findSave(it)?.let { found -> return found } }
                                    return null
                                }
                                findSave(root)?.let(::tap)
                            }
                            File(output, "$name-$label-confirm-nodes.json").writeText(JSONArray(seen.toList()).toString(2))
                            SystemClock.sleep(100)
                        }
                        if (!main { activity.hasWindowFocus() }) {
                            instrumentation.uiAutomation.takeScreenshot()?.let { shot ->
                                File(output, "$name-camera-final.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                shot.recycle()
                            }
                            val size = runCatching { activity.contentResolver.openAssetFileDescriptor(uri!!, "r")?.use { it.length } }
                            File(output, "$name-camera-output.json").writeText(JSONObject().put("uri_bytes", size.getOrNull())
                                .put("read_error", size.exceptionOrNull()?.message).put("local_bytes", file!!.length()).toString())
                        }
                    } else shell("input keyevent 4")
                    await(10000) { activity.hasWindowFocus() }
                    idle()
                    check(snapshot() == before)
                    val returned = row(14).et!!
                    main {
                        check(returned.selectionStart == 2 && returned.selectionEnd == 5)
                        val current = field<ImageAttachmentAdapter?>("mImageAttachmentAdapter")?.getItems()?.toList().orEmpty()
                        if (capture) check(current.size == original.size + 1 && current.single { it !in original }?.substring(1) == file!!.absolutePath)
                        else check(current == original)
                    }
                    if (capture) {
                        check(file!!.length() > 0)
                        if (label == "拍照") {
                            val decoded = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeFile(file!!.absolutePath, decoded)
                            check(decoded.outWidth > 0 && decoded.outHeight > 0)
                        } else {
                            val metadata = android.media.MediaMetadataRetriever()
                            try { metadata.setDataSource(file!!.absolutePath); check(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() >= 1000) }
                            finally { metadata.release() }
                        }
                        menu(R.id.act_undo)
                        main { check(field<ImageAttachmentAdapter?>("mImageAttachmentAdapter")?.getItems()?.toList().orEmpty() == original) }
                    }
                } finally {
                    instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
                    repeat(3) {
                        if (!main { activity.hasWindowFocus() }) {
                            shell("input keyevent 4")
                            SystemClock.sleep(300)
                        }
                    }
                    // 只清理这次入口同步创建的目标，不触及相册中其它条目。
                    main {
                        if (uri == null) uri = activity.cameraOutputUri
                        if (file == null) file = activity.attachmentTypePathName?.substring(1)?.let(::File)
                    }
                    uri?.let { target ->
                        activity.contentResolver.query(target, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                            check(!it.moveToFirst() || it.getString(0) == file!!.name)
                        }
                        activity.contentResolver.delete(target, null, null)
                    }
                    file?.delete()
                    main { activity.cameraOutputUri = null; activity.attachmentTypePathName = null }
                }
            }
        }
    }

    private fun dialogAppeared(fragment: com.ywwynm.everythingdone.fragments.BaseDialogFragment?): Boolean =
        fragment?.view != null && com.ywwynm.everythingdone.fragments.BaseDialogFragment::class.java
            .getDeclaredField("appearanceSettled").apply { isAccessible = true }.getBoolean(fragment)

    private fun recording() {
        val service = com.ywwynm.everythingdone.services.AudioRecordingService
        val preferences = com.ywwynm.everythingdone.views.recording.AudioInputPreferences
        if (service.activeSession || preferences.stoppedSessionThingId(activity) != -1L ||
            ContextCompat.checkSelfPermission(activity, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            results.put(JSONObject().put("check", "实际录音保存与取消").put("skipped", "已有录音会话或尚未授予麦克风权限"))
            File(output, "$name-behavior.json").writeText(results.toString(2)); return
        }
        verify("录音入口取消、实际录音保存为附件、解码及草稿保持") {
            val settings = activity.getSharedPreferences(Def.Meta.PREFERENCES_NAME, 0)
            val modeKey = "audio_recording_input_mode"
            val confirmedKey = "audio_recording_input_mode_confirmed"
            val originalMode = settings.getString(modeKey, null)
            val hadConfirmed = settings.contains(confirmedKey)
            val originalConfirmed = settings.getBoolean(confirmedKey, false)
            val original = audios?.getItems()?.toList().orEmpty()
            val editor = row(14).et!!
            main { editor.requestFocus(); editor.append("【录音前草稿】"); editor.setSelection(2, 5) }
            val before = snapshot()
            var dialog: com.ywwynm.everythingdone.fragments.AudioRecordDialogFragment? = null
            var saved: File? = null
            fun openRecorder() {
                menu(R.id.act_add_attachment)
                await { dialogAppeared(activity.supportFragmentManager.findFragmentByTag(AddAttachmentDialogFragment.TAG) as? AddAttachmentDialogFragment) }
                main { activity.supportFragmentManager.findFragmentByTag(AddAttachmentDialogFragment.TAG)!!.requireView()
                    .findViewById<View>(R.id.tv_record_audio_as_bt).performClick() }
                await(10000) {
                    dialog = activity.supportFragmentManager.findFragmentByTag(com.ywwynm.everythingdone.fragments.AudioRecordDialogFragment.TAG)
                        as? com.ywwynm.everythingdone.fragments.AudioRecordDialogFragment
                    dialogAppeared(dialog)
                }
                await(10000) { dialog!!.javaClass.getDeclaredField("mRecordingBinder").apply { isAccessible = true }.get(dialog) != null }
            }
            fun ready(state: Int): Boolean {
                val target = dialog ?: return false
                val phase = target.javaClass.getDeclaredField("mState").apply { isAccessible = true }.getInt(target)
                val busy = target.javaClass.getDeclaredField("mRecorderTransitionInProgress").apply { isAccessible = true }.getBoolean(target)
                return phase == state && !busy
            }
            try {
                preferences.save(activity, com.ywwynm.everythingdone.views.recording.AudioInputMode.MICROPHONE)
                openRecorder()
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await { !dialog!!.isAdded }
                check(snapshot() == before)
                // 录音中取消也走真实返回键，确认临时 WAV 被丢弃且原附件不变。
                openRecorder()
                await { ready(com.ywwynm.everythingdone.fragments.AudioRecordDialogFragment.PREPARED) }
                main { dialog!!.requireView().findViewById<View>(R.id.iv_record_main_action).performClick() }
                await(10000) { ready(com.ywwynm.everythingdone.fragments.AudioRecordDialogFragment.RECORDING) }
                SystemClock.sleep(300)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await(10000) { !dialog!!.isAdded && !service.activeSession }
                check(snapshot() == before)
                main { check((field<AudioAttachmentAdapter?>("mAudioAttachmentAdapter")?.getItems()?.size ?: 0) == original.size) }
                openRecorder()
                await { ready(com.ywwynm.everythingdone.fragments.AudioRecordDialogFragment.PREPARED) }
                main { dialog!!.requireView().findViewById<View>(R.id.iv_record_main_action).performClick() }
                await(10000) { ready(com.ywwynm.everythingdone.fragments.AudioRecordDialogFragment.RECORDING) }
                SystemClock.sleep(1200)
                main { dialog!!.requireView().findViewById<View>(R.id.iv_record_main_action).performClick() }
                await(10000) { ready(com.ywwynm.everythingdone.fragments.AudioRecordDialogFragment.STOPPED) }
                val filename = "detail-recording-regression-${SystemClock.uptimeMillis()}"
                main {
                    dialog!!.requireView().findViewById<EditText>(R.id.et_audio_file_name).setText(filename)
                    dialog!!.requireView().findViewById<View>(R.id.iv_record_main_action).performClick()
                }
                await(10000) { !dialog!!.isAdded && field<AudioAttachmentAdapter?>("mAudioAttachmentAdapter")?.itemCount == original.size + 1 }
                val items = main { field<AudioAttachmentAdapter>("mAudioAttachmentAdapter").getItems()!!.toList() }
                saved = File(items.single { it !in original }!!.substring(1))
                check(saved!!.nameWithoutExtension == filename && saved!!.length() > 44)
                val metadata = android.media.MediaMetadataRetriever()
                try { metadata.setDataSource(saved!!.absolutePath); check(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() >= 1000) }
                finally { metadata.release() }
                check(snapshot() == before)
                val returned = row(14).et!!
                main { check(returned.selectionStart == 2 && returned.selectionEnd == 5) }
                menu(R.id.act_undo)
                main { check((field<AudioAttachmentAdapter?>("mAudioAttachmentAdapter")?.getItems()?.size ?: 0) == original.size) }
            } finally {
                main { if (dialog?.isAdded == true) dialog!!.dismiss() }
                saved?.let { check(it.name.startsWith("detail-recording-regression-")); it.delete() }
                settings.edit().apply {
                    if (originalMode == null) remove(modeKey) else putString(modeKey, originalMode)
                    if (hadConfirmed) putBoolean(confirmedKey, originalConfirmed) else remove(confirmedKey)
                }.commit()
            }
        }
    }

    private fun exportFailures() {
        val before = snapshot()
        verify("长图写入失败返回空结果并回收 Bitmap") {
            val testRoot = File(output, "blocked-output-root").apply { mkdirs() }
            val blocker = File(testRoot, "temp").apply { writeText("此文件不是目录") }
            val context = object : android.content.ContextWrapper(activity) {
                override fun getExternalFilesDir(type: String?): File = testRoot
            }
            val latch = CountDownLatch(1)
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            var file: File? = null
            main { com.ywwynm.everythingdone.helpers.ScreenshotHelper.saveScreenshot(context, bitmap,
                object : com.ywwynm.everythingdone.helpers.ScreenshotHelper.ScreenshotCallback {
                    override fun onTaskDone(result: File?) { file = result; latch.countDown() }
                }) }
            check(latch.await(5, TimeUnit.SECONDS))
            check(file == null) { "写入失败却返回了不存在或无效的文件：${file?.exists()}" }
            check(bitmap.isRecycled)
            check(blocker.delete()); check(testRoot.delete())
        }
        verify("长图缺失媒体返回失败并释放副本") {
            val missing = "0" + File(output, "nonexistent-export-image.png").absolutePath
            val latch = CountDownLatch(1)
            var bitmap: Bitmap? = null
            lateinit var renderer: DetailScreenshotRenderer
            main {
                renderer = DetailScreenshotRenderer(activity, content, activity.getAccentBackground()!!,
                    true, checklist, listOf(missing), DetailAttachmentMediaAppearance.default(), 1, null, 1, false)
                renderer.render { bitmap = it; latch.countDown() }
            }
            check(latch.await(12, TimeUnit.SECONDS))
            val unexpectedlySucceeded = bitmap != null
            bitmap?.recycle()
            check(!unexpectedlySucceeded) { "媒体加载失败却导出了空白媒体区域" }
            main { check(renderer.root.findViewById<RecyclerView>(R.id.rv_image_attachment).adapter == null) }
            check(snapshot() == before)
        }
        for (oversized in listOf(false, true)) verify(if (oversized) "超出 Bitmap 字节范围的长图在分配前返回失败" else "媒体持续加载超时后取消并释放副本") {
            val latch = CountDownLatch(1)
            var bitmap: Bitmap? = null
            lateinit var renderer: DetailScreenshotRenderer
            main {
                renderer = DetailScreenshotRenderer(activity, content, activity.getAccentBackground()!!,
                    true, null, null, DetailAttachmentMediaAppearance.default(), 1, null, 1, false)
                if (oversized) {
                    // 仅增加离屏副本的布局高度，不创建大量模型、View 或 Bitmap。
                    renderer.root.getChildAt(0).minimumHeight = 1 shl 23
                } else {
                    val imageList = renderer.root.findViewById<RecyclerView>(R.id.rv_image_attachment)
                    imageList.visibility = View.VISIBLE
                    imageList.layoutParams.height = 100
                    imageList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(activity)
                    imageList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                        override fun getItemCount() = 1
                        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder =
                            object : RecyclerView.ViewHolder(android.widget.ProgressBar(activity).apply {
                                id = R.id.pb_image_attachment
                                layoutParams = RecyclerView.LayoutParams(100, 100)
                            }) {}
                        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
                    }
                }
                renderer.render { bitmap = it; latch.countDown() }
            }
            check(latch.await(12, TimeUnit.SECONDS))
            bitmap?.recycle(); check(bitmap == null)
            main { check(renderer.root.findViewById<RecyclerView>(R.id.rv_image_attachment).adapter == null) }
            check(snapshot() == before)
        }
    }

    private fun mediaFailures() {
        val before = snapshot()
        verify("打开时滤除不存在附件、损坏图片回收不串图，正常附件仍可显示") {
            main { check(images!!.itemCount == 2 && audios!!.itemCount == 2) }
            for (position in listOf(1, 0, 1)) {
                val holder = image(position)
                await(10000) { holder.pbLoading!!.visibility != View.VISIBLE }
                main { check((holder.ivImage!!.drawable != null) == (position == 1)) }
                main { list.scrollToPosition(content.adapter.itemCount - 1) }; idle()
            }
            check(snapshot() == before)
        }
        verify("缺失或损坏音频不能进入播放状态，随后可播放正常文件") {
            repeat(2) { attempt ->
                if (attempt == 1) {
                    val broken = File(audios!!.getItems()!![0]!!.substring(1))
                    check(broken.name == "broken.wav"); check(broken.delete())
                }
                val holder = audio(0)
                main { holder.ivFirst!!.performClick(); check(audios!!.getPlayingIndex() == -1) }
            }
            val valid = audio(1)
            main { valid.ivFirst!!.performClick() }
            await { audios!!.getPlayingIndex() == 1 }
            main { (content.holder(audios!!, 1) as AudioAttachmentAdapter.AudioCardViewHolder).ivSecond!!.performClick() }
            check(main { audios!!.getPlayingIndex() == -1 })
            check(snapshot() == before)
        }
        verify("已授权 URI 的源文件消失时显示错误并保留草稿及附件") {
            val original = main { images!!.getItems()!!.toList() }
            val missing = File(original[0]!!.substring(1))
            check(missing.name == "broken.png"); check(missing.delete())
            check(!missing.exists())
            val uri = FileProvider.getUriForFile(activity, activity.packageName, missing)
            main { invoke("onActivityResult", Def.Communication.REQUEST_CHOOSE_MEDIA_FILE, Activity.RESULT_OK, Intent().setData(uri)) }
            val snackbar: com.ywwynm.everythingdone.views.Snackbar = field("mNormalSnackbar")
            await { snackbar.isShowing() }
            main {
                val message = snackbar.javaClass.getDeclaredField("mTvMessage").apply { isAccessible = true }.get(snackbar) as TextView
                check(message.text ==
                    activity.getString(R.string.error_cannot_add_from_network))
                check(images!!.getItems() == original); snackbar.dismiss()
            }
            check(snapshot() == before)
        }
    }

    private fun realPickerSuccess() = verify("真实系统选择器选中文件、读取授权、附件解码及草稿保持") {
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val fileName = "detail-regression-${SystemClock.uptimeMillis()}.png"
        val resolver = activity.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        try {
            val bitmap = Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val editor = row(14).et!!
            main { editor.requestFocus(); editor.append("【选择器前草稿】"); editor.setSelection(2, 5) }
            val before = snapshot()
            val original = main { images!!.getItems()!!.toList() }
            menu(R.id.act_add_attachment)
            var chooser: AddAttachmentDialogFragment? = null
            await {
                chooser = activity.supportFragmentManager.findFragmentByTag(AddAttachmentDialogFragment.TAG) as? AddAttachmentDialogFragment
                chooser?.view?.isShown == true && chooser?.dialog?.window?.decorView?.hasWindowFocus() == true
            }
            main { check(chooser!!.requireView().findViewById<View>(R.id.tv_choose_media_files_as_bt).performClick()) }
            val deadline = SystemClock.uptimeMillis() + 12000
            var clicked = false
            var navigationStage = 0
            val seen = linkedSetOf<String>()
            val owners = linkedSetOf<String>()
            fun click(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                var target = node
                while (target != null && !target.isClickable) target = target.parent
                return target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            while (!clicked && SystemClock.uptimeMillis() < deadline) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                root?.refresh()
                owners += root?.packageName?.toString().orEmpty()
                if (root?.packageName?.toString()?.endsWith("documentsui") == true) {
                    val matches = root.findAccessibilityNodeInfosByText(fileName)
                    File(output, "$name-picker-nodes.json").writeText(JSONArray(matches.map {
                        JSONObject().put("text", it.text).put("description", it.contentDescription)
                            .put("class", it.className).put("clickable", it.isClickable)
                            .put("bounds", Rect().apply { it.getBoundsInScreen(this) }.toString())
                    }).toString(2))
                    val match = matches.firstOrNull { it.text?.toString() == fileName }
                    if (match != null) {
                        val ancestry = JSONArray()
                        var ancestor: android.view.accessibility.AccessibilityNodeInfo? = match
                        repeat(6) {
                            val node = ancestor ?: return@repeat
                            ancestry.put(JSONObject().put("class", node.className).put("id", node.viewIdResourceName)
                                .put("clickable", node.isClickable).put("enabled", node.isEnabled)
                                .put("bounds", Rect().apply { node.getBoundsInScreen(this) }.toString()))
                            ancestor = node.parent
                        }
                        File(output, "$name-picker-ancestors.json").writeText(ancestry.toString(2))
                        clicked = click(match)
                        val box = Rect().apply { match.getBoundsInScreen(this) }
                        check(box.width() > 0 && box.height() > 0)
                        if (!clicked) {
                            automation.waitForIdle(250, 2000)
                            match.refresh()
                            match.getBoundsInScreen(box)
                            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                                "input tap ${box.centerX()} ${box.centerY()}" )).use { it.readBytes() }
                            clicked = true
                        }
                    } else {
                        fun collect(node: android.view.accessibility.AccessibilityNodeInfo) {
                            val text = node.text?.toString()
                            val description = node.contentDescription?.toString()
                            if (text in listOf("最近", "下载", "下载内容", "Downloads", "Recent")) seen += text!!
                            if (description in listOf("显示根目录", "Show roots")) seen += description!!
                            for (i in 0 until node.childCount) node.getChild(i)?.let(::collect)
                        }
                        collect(root)
                        if (navigationStage == 0) {
                            val roots = listOf("显示根目录", "Show roots").flatMap { root.findAccessibilityNodeInfosByText(it) }
                                .firstOrNull { it.contentDescription?.toString() in listOf("显示根目录", "Show roots") }
                            if (click(roots)) navigationStage = 1
                        } else if (navigationStage == 1) {
                            val downloads = listOf("下载", "下载内容", "Downloads").flatMap { root.findAccessibilityNodeInfosByText(it) }
                                .firstOrNull { it.text?.toString() in listOf("下载", "下载内容", "Downloads") }
                            if (click(downloads)) navigationStage = 2
                        } else if (navigationStage == 2) {
                            val search = listOf("搜索", "Search").flatMap { root.findAccessibilityNodeInfosByText(it) }
                                .firstOrNull { it.contentDescription?.toString() in listOf("搜索", "Search") }
                            if (click(search)) navigationStage = 3
                        } else if (navigationStage == 3) {
                            val input = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                            if (input?.isEditable == true) {
                                input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                                    android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, fileName) })
                                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
                                navigationStage = 4
                            }
                        }
                    }
                    if (!clicked && navigationStage == 0) {
                        // 魅族的选择器只暴露空的辅助功能根节点。以本测试刚生成的纯洋红缩略图定位；
                        // 必须是大块且几乎全满的单一矩形，点击后仍核对实际解码尺寸与像素。
                        val screen = instrumentation.uiAutomation.takeScreenshot()
                        if (screen != null) {
                            var left = screen.width; var top = screen.height; var right = -1; var bottom = -1; var count = 0
                            for (y in 0 until screen.height step 4) for (x in 0 until screen.width step 4) {
                                val color = screen.getPixel(x, y)
                                if (Color.red(color) >= 254 && Color.green(color) <= 1 && Color.blue(color) >= 254) {
                                    left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y); count++
                                }
                            }
                            screen.recycle()
                            File(output, "$name-picker-thumbnail.json").writeText(JSONObject().put("count", count)
                                .put("bounds", "$left,$top,$right,$bottom").put("matching_nodes", matches.size).toString())
                            if (count > 2500 && count * 16L > (right - left + 4L) * (bottom - top + 4L) * 90 / 100) {
                                val whenDown = SystemClock.uptimeMillis()
                                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                                    val event = MotionEvent.obtain(whenDown, SystemClock.uptimeMillis(), action,
                                        (left + right) / 2f, (top + bottom) / 2f, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                                    try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                                }
                                clicked = true
                            }
                        }
                    }
                }
                if (!clicked) SystemClock.sleep(150)
            }
            File(output, "$name-picker-visible.json").writeText(JSONArray(seen.toList()).toString(2))
            if (!clicked) instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                File(output, "$name-picker-failure.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            }
            check(clicked) { "系统选择器没有显示专用文件：stage=$navigationStage owners=$owners controls=$seen" }
            try { await(12000) { images!!.itemCount == original.size + 1 && activity.hasWindowFocus() } }
            catch (failure: Throwable) {
                automation.takeScreenshot()?.let { screen ->
                    File(output, "$name-picker-return-failure.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }; screen.recycle()
                }
                throw failure
            }
            val added = main { images!!.getItems()!!.single { it !in original }!! }
            check(added.startsWith("0"))
            val decoded = android.graphics.BitmapFactory.decodeFile(added.substring(1))!!
            check(decoded.width == 96 && decoded.height == 48 && decoded.getPixel(0, 0) == Color.MAGENTA)
            decoded.recycle()
            check(snapshot() == before)
            val returned = row(14).et!!
            main { check(returned.selectionStart == 2 && returned.selectionEnd == 5) }
            menu(R.id.act_undo)
            main { check(images!!.getItems() == original) }
            File(output, "$name-picker-result.json").writeText(JSONObject().put("selected", fileName)
                .put("decoded_width", 96).put("decoded_height", 48).put("draft_preserved", true).toString(2))
        } finally {
            repeat(3) {
                if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.endsWith("documentsui") == true) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); SystemClock.sleep(250)
                }
            }
            resolver.delete(uri, null, null)
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }

    private fun checklistEdges() {
        val adapter = checklist!!
        verify("文字中间回车拆分继承层级、聚焦新行及一次撤销重做") {
            val before = snapshot()
            val old = main { adapter.getItems()!![10]!! }
            val text = CheckListHelper.textOf(old)
            val current = row(10)
            main {
                current.et!!.requestFocus(); current.et.setSelection(3)
                check(current.et.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
            }
            await { (content.holder(adapter, 11) as? CheckListAdapter.EditTextHolder)?.et?.hasFocus() == true }
            main {
                check(CheckListHelper.textOf(adapter.getItems()!![10]) == text.take(3))
                check(CheckListHelper.textOf(adapter.getItems()!![11]) == text.drop(3))
                check(CheckListHelper.levelOf(adapter.getItems()!![11]) == CheckListHelper.levelOf(old))
            }
            val split = snapshot()
            menu(R.id.act_undo); check(snapshot() == before)
            menu(R.id.act_redo); check(snapshot() == split)
            menu(R.id.act_undo); check(snapshot() == before)
        }
        verify("删除最后一个实际清单项后仍可新增、空正文互转和焦点") {
            val saved = main { adapter.getItems()!!.toMutableList() }
            try {
                main { adapter.setItems(mutableListOf("01", "2")) }
                val only = row(0)
                main {
                    only.et!!.requestFocus(); only.et.setSelection(0)
                    check(only.et.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
                }
                await { adapter.getItems()!!.none(CheckListHelper::isItem) }
                menu(R.id.act_check_list)
                val body = content.view<EditText>(R.id.et_content)
                main { check(body.text.isEmpty() && body.visibility == View.VISIBLE) }
                menu(R.id.act_check_list)
                await { adapter.getItems()!!.count(CheckListHelper::isItem) == 1 &&
                    (content.holder(adapter, 0) as? CheckListAdapter.EditTextHolder)?.et?.hasFocus() == true }
                val added = row(0)
                main { added.et!!.append("空清单恢复输入") }
                main { check(CheckListHelper.textOf(adapter.getItems()!!.first()) == "空清单恢复输入") }
            } finally {
                main { activity.currentFocus?.clearFocus(); KeyboardUtil.hideKeyboard(activity.window); adapter.setItems(saved) }
            }
        }
        verify("全完成清单展开、完成区首项退格合并和撤销") {
            val saved = main { adapter.getItems()!!.toMutableList() }
            try {
                main { adapter.setItems(mutableListOf("2", "3", "4", "11完成甲", "11完成乙")); adapter.setExpanded(false) }
                val header = row(2)
                main { header.ivExpandShrink!!.performClick() }
                await { adapter.isExpanded() && adapter.itemCount == 5 }
                val before = snapshot()
                val first = row(3)
                main {
                    first.et!!.requestFocus(); first.et.setSelection(0)
                    check(first.et.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
                }
                await { (content.holder(adapter, 0) as? CheckListAdapter.EditTextHolder)?.et?.hasFocus() == true }
                main {
                    check(adapter.getItems()!!.filter(CheckListHelper::isItem).map(CheckListHelper::textOf) == listOf("完成甲", "完成乙"))
                    check(!CheckListHelper.isFinished(adapter.getItems()!!.first()))
                }
                menu(R.id.act_undo); check(snapshot() == before)
                val recovered = row(3)
                main { check(recovered.et!!.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG != 0) }
            } finally { main { activity.currentFocus?.clearFocus(); adapter.setItems(saved); adapter.setExpanded(false) } }
        }
    }

    private fun scrollChrome() = verify("顶部与底部阴影、媒体下工具栏透明度和点击回顶") {
        val toolbar = activity.findViewById<Toolbar>(R.id.actionbar)
        val topShadow = activity.findViewById<View>(R.id.actionbar_shadow)
        val bottomShadow = activity.findViewById<View>(R.id.bottom_bar_shadow)
        main {
            activity.currentFocus?.clearFocus()
            KeyboardUtil.hideKeyboard(activity.window)
            content.layoutManager.scrollToPositionWithOffset(0, 0)
        }
        idle()
        main {
            check(!list.canScrollVertically(-1))
            check(topShadow.alpha == 0f && bottomShadow.alpha == 1f)
            if (images != null) check((toolbar.background as android.graphics.drawable.ColorDrawable).alpha == 0)
            list.scrollToPosition(content.adapter.itemCount - 1)
        }
        idle()
        val endBefore = main {
            val last = content.layoutManager.findViewByPosition(content.adapter.itemCount - 1)!!
            last.bottom.also { list.scrollBy(0, list.height * 2) }
        }
        idle()
        main {
            val last = content.layoutManager.findViewByPosition(content.adapter.itemCount - 1)!!
            File(output, "$name-scroll-end.json").writeText(JSONObject()
                .put("last_bottom_before", endBefore).put("last_bottom_after", last.bottom)
                .put("viewport_bottom", list.height - list.paddingBottom)
                .put("estimated_can_scroll", list.canScrollVertically(1))
                .put("top_shadow", topShadow.alpha).put("bottom_shadow", bottomShadow.alpha).toString(2))
            check(last.bottom == endBefore && last.bottom <= list.height - list.paddingBottom)
            check(topShadow.alpha == 1f && bottomShadow.alpha == 0f)
            if (images != null) check((toolbar.background as android.graphics.drawable.ColorDrawable).alpha == 255)
            toolbar.performClick()
        }
        await(10000) { list.scrollState == RecyclerView.SCROLL_STATE_IDLE && !list.canScrollVertically(-1) }
        main { check(topShadow.alpha == 0f && bottomShadow.alpha == 1f) }
    }

    private fun inPlaceCompletion() = verify("顶部子项就地完成、回收后样式、取消级联及撤销") {
        val adapter = checklist!!
        val before = snapshot()
        val labels = main { adapter.getItems()!!.take(4).map(CheckListHelper::textOf) }
        val child = row(1)
        main { child.ivState!!.performClick() }
        idle()
        main {
            val items = adapter.getItems()!!
            check(items.take(4).map(CheckListHelper::textOf) == labels)
            check(!CheckListHelper.isFinished(items[0]) && CheckListHelper.isFinished(items[1]) &&
                CheckListHelper.isFinished(items[2]) && !CheckListHelper.isFinished(items[3]))
            check(!adapter.isExpanded())
            list.scrollToPosition(content.adapter.itemCount - 1)
        }
        idle()
        val returned = row(1)
        main {
            check(returned.et!!.text.toString() == labels[1])
            check(returned.et.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG != 0)
            returned.ivState!!.performClick()
        }
        idle()
        check(snapshot() == before)
        menu(R.id.act_undo)
        main { check(CheckListHelper.isFinished(adapter.getItems()!![1])) }
        menu(R.id.act_undo)
        check(snapshot() == before)
    }

    private fun links(plain: Boolean) = verify("${if (plain) "正文" else "清单"}链接离屏返回后触摸、操作弹窗和浏览请求") {
        val url = "https://example.com/detail"
        val prefix = "链接回归 $url${if (plain) "\n" else " "}"
        fun editor(): EditText {
            val toolbarBounds = bounds(activity.findViewById(R.id.actionbar))
            val listBounds = bounds(list)
            main {
                val position = if (plain) content.positionOf(R.id.et_content) else content.globalPosition(checklist!!, 0)
                content.layoutManager.scrollToPositionWithOffset(position, (toolbarBounds.bottom - listBounds.top).coerceAtLeast(0))
            }
            idle()
            return if (plain) content.view(R.id.et_content)
                else main { (content.holder(checklist!!, 0) as CheckListAdapter.EditTextHolder).et!! }
        }
        val first = editor()
        val original = main { first.text.toString() }
        main { first.setText(prefix + original); first.clearFocus(); list.scrollToPosition(content.adapter.itemCount - 1) }
        idle()
        val restored = editor()
        val point = main {
            val span = restored.text.getSpans(0, restored.length(), URLSpan::class.java).first { it.url == url }
            val offset = restored.text.getSpanStart(span) + 10
            val line = restored.layout.getLineForOffset(offset)
            val origin = IntArray(2).also(restored::getLocationOnScreen)
            val x = origin[0] + restored.totalPaddingLeft + restored.layout.getPrimaryHorizontal(offset) - restored.scrollX
            val y = origin[1] + restored.totalPaddingTop + (restored.layout.getLineTop(line) + restored.layout.getLineBottom(line)) / 2f - restored.scrollY
            x to y
        }
        check(bounds(restored).contains(point.first.toInt(), point.second.toInt()))
        var launched: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW || intent.dataString != url) return null
                launched = intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            val time = SystemClock.uptimeMillis()
            pointer(time, MotionEvent.ACTION_DOWN, point.first, point.second)
            pointer(time, MotionEvent.ACTION_UP, point.first, point.second)
            await { (activity.supportFragmentManager.findFragmentByTag(TwoOptionsDialogFragment.TAG) as? TwoOptionsDialogFragment)?.dialog?.isShowing == true }
            main {
                val dialog = activity.supportFragmentManager.findFragmentByTag(TwoOptionsDialogFragment.TAG) as TwoOptionsDialogFragment
                dialog.requireView().findViewById<View>(R.id.tv_action_start).performClick()
            }
            await { launched != null && activity.hasWindowFocus() }
            main { check(restored.text.toString() == prefix + original); restored.clearFocus() }
        } finally { instrumentation.removeMonitor(monitor) }
        menu(R.id.act_undo)
        val reverted = editor()
        main { check(reverted.text.toString() == original) }
    }

    private fun workflows() {
        val imageAdapter = images!!
        fun appearance() = field<DetailAttachmentMediaAppearance>("mDetailAttachmentMediaAppearance")
        fun dialog() = activity.supportFragmentManager.findFragmentByTag(MediaCropAppearanceDialogFragment.TAG)
            as? MediaCropAppearanceDialogFragment
        fun openEditor(position: Int, waitPreview: Boolean = true): View {
            val holder = image(position)
            main { holder.ivEditAppearance!!.performClick() }
            await { dialog()?.view?.width?.let { it > 0 } == true && dialog()?.dialog?.window?.decorView?.hasWindowFocus() == true }
            if (waitPreview) await { descendants(dialog()!!.requireView()).filterIsInstance<TextView>()
                .first { it.text.toString() == activity.getString(R.string.confirm) && it.isClickable }.isEnabled }
            return main { dialog()!!.requireView() }
        }
        fun textButton(root: View, id: Int) = descendants(root).filterIsInstance<TextView>()
            .first { it.text.toString() == activity.getString(id) && it.isClickable }
        verify("预览解码等待时按尺寸显示窗口、禁止确认、可取消且迟到结果不重开") {
            val loader = com.ywwynm.everythingdone.helpers.DetailCropPreviewLoader()
            main { DetailActivity::class.java.getDeclaredField("mDetailCropPreviewLoader")
                .apply { isAccessible = true }.set(activity, loader) }
            val executor = loader.javaClass.getDeclaredField("executor").apply { isAccessible = true }
                .get(loader) as java.util.concurrent.ExecutorService
            val started = CountDownLatch(1)
            val unblock = CountDownLatch(1)
            executor.submit { started.countDown(); check(unblock.await(15, TimeUnit.SECONDS)) }
            check(started.await(5, TimeUnit.SECONDS))
            val before = main { appearance().toJson() }
            try {
                val root = openEditor(0, false)
                main {
                    check(dialog()!!.detailPreviewMetadata!!.width > 0)
                    check(!textButton(root, R.string.confirm).isEnabled)
                    textButton(root, R.string.confirm).performClick()
                    check(appearance().toJson() == before && dialog() != null)
                    textButton(root, R.string.cancel).performClick()
                }
                await { dialog() == null }
            } finally { unblock.countDown() }
            val drained = CountDownLatch(1)
            executor.submit { drained.countDown() }
            check(drained.await(10, TimeUnit.SECONDS)); idle()
            main { check(dialog() == null && appearance().toJson() == before) }
        }
        verify("图片外观面板宽度、比例、双指缩放、取消和确认撤销") {
            val before = main { appearance().toJson() }
            var root = openEditor(0)
            main { textButton(root, R.string.detail_attachment_media_appearance_width_wide).performClick() }
            main { textButton(root, R.string.cancel).performClick() }
            await { dialog() == null }
            main { check(appearance().toJson() == before) }
            root = openEditor(0)
            main { textButton(root, R.string.detail_attachment_media_appearance_width_wide).performClick() }
            idle()
            val slider = main { descendants(root).filterIsInstance<RatioSlider>().single() }
            val seek = main { descendants(slider).filterIsInstance<SeekBar>().single() }
            seekToFraction(seek, 0.7f)
            val crop = main { descendants(root).first { it is ThingCardCropEditorController } }
            pinch(crop)
            val expectedRatio = main { (crop as ThingCardCropEditorController).getTargetAspectRatio() }
            val expectedScale = main { (crop as ThingCardCropEditorController).getCropUserScale() }
            check(expectedScale > 1.05) { "真实双指缩放没有改变裁剪比例" }
            main { textButton(root, R.string.confirm).performClick() }
            await { dialog() == null }
            main {
                val source = appearance().source(imageAdapter.getItems()!![0])!!
                check(source.fullSpanEnabled)
                val presentation = source.presentationOrDefault(DetailAttachmentMediaAppearance.PRESENTATION_FULL_SPAN)
                check(kotlin.math.abs(presentation.targetAspectRatio - expectedRatio) < 0.001)
                check(kotlin.math.abs(presentation.crop.scale - expectedScale) < 0.001)
            }
            val after = main { appearance().toJson() }
            menu(R.id.act_undo); main { check(appearance().toJson() == before) }
            menu(R.id.act_redo); main { check(appearance().toJson() == after) }
        }
        verify("视频外观面板代表帧、确认与离屏恢复") {
            val position = imageAdapter.getItems()!!.indexOfFirst { it?.startsWith("1") == true }
            check(position >= 0)
            val root = openEditor(position)
            val video = main { descendants(root).filterIsInstance<ThingCardVideoCropEditorView>().single() }
            val seek = main { descendants(root).filterIsInstance<SeekBar>().first { it.max > 1000 } }
            seekToFraction(seek, 0.65f)
            await { video.getCurrentFrameMs() > 500 }
            main { textButton(root, R.string.confirm).performClick() }
            await { dialog() == null }
            val frame = main { appearance().source(imageAdapter.getItems()!![position])!!.videoFrameMs!! }
            check(frame > 500)
            main { list.scrollToPosition(content.adapter.itemCount - 1) }; idle()
            val restored = image(position)
            await { restored.pbLoading!!.visibility != View.VISIBLE }
            main { check(restored.ivImage!!.drawable != null); check(appearance().source(imageAdapter.getItems()!![position])!!.videoFrameMs == frame) }
        }
        verify("附件查看器打开与返回保留详情外观") {
            val before = main { appearance().toJson() }
            val monitor = instrumentation.addMonitor(ImageViewerActivity::class.java.name, null, false)
            try {
                val holder = image(0)
                main { holder.fl!!.performClick() }
                val viewer = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                check(viewer is ImageViewerActivity)
                main { viewer.onBackPressedDispatcher.onBackPressed() }
                await { activity.hasWindowFocus() }
                main { check(appearance().toJson() == before) }
            } finally { instrumentation.removeMonitor(monitor) }
        }
        verify("文件选择结果添加图片和音频、取消及撤销") {
            for (adapter in listOf(imageAdapter, audios!!)) {
                val original = main { if (adapter === imageAdapter) imageAdapter.getItems()!!.toList() else audios!!.getItems()!!.toList() }
                val path = File(original[0]!!.substring(1))
                val added = File(path.parentFile, "regression-added.${path.extension}")
                path.copyTo(added, overwrite = true)
                val uri = FileProvider.getUriForFile(activity, "com.ywwynm.everythingdone", added)
                main { invoke("onActivityResult", Def.Communication.REQUEST_CHOOSE_MEDIA_FILE, Activity.RESULT_CANCELED, null) }
                main { check(adapter.itemCount == original.size) }
                main { invoke("onActivityResult", Def.Communication.REQUEST_CHOOSE_MEDIA_FILE, Activity.RESULT_OK, Intent().setData(uri)) }
                await { adapter.itemCount == original.size + 1 }
                menu(R.id.act_undo)
                main { check((if (adapter === imageAdapter) imageAdapter.getItems() else audios!!.getItems()) == original) }
            }
        }
        verify("系统文件选择器真实打开和取消后附件与详情恢复") {
            val original = main { imageAdapter.getItems()!!.toList() }
            menu(R.id.act_add_attachment)
            var chooser: AddAttachmentDialogFragment? = null
            await {
                chooser = activity.supportFragmentManager.findFragmentByTag(AddAttachmentDialogFragment.TAG) as? AddAttachmentDialogFragment
                chooser?.view?.isShown == true && chooser?.dialog?.window?.decorView?.hasWindowFocus() == true
            }
            val pickerButton = main { chooser!!.requireView().findViewById<View>(R.id.tv_choose_media_files_as_bt) }
            val pickerBounds = bounds(pickerButton)
            val pickerDown = SystemClock.uptimeMillis()
            // 跨窗口跳转必须来自真实弹窗触摸，让交互协调器识别来源窗口。
            pointer(pickerDown, MotionEvent.ACTION_DOWN, pickerBounds.exactCenterX(), pickerBounds.exactCenterY())
            pointer(pickerDown, MotionEvent.ACTION_UP, pickerBounds.exactCenterX(), pickerBounds.exactCenterY())
            val deadline = SystemClock.uptimeMillis() + 7000
            var externalPackage = ""
            while (SystemClock.uptimeMillis() < deadline) {
                externalPackage = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString().orEmpty()
                if (externalPackage.endsWith("documentsui")) break
                SystemClock.sleep(60)
            }
            check(externalPackage.endsWith("documentsui")) { "没有打开系统选择器：$externalPackage" }
            val transitions = JSONArray()
            // 系统选择器可能先返回到上级目录，再取消；每次返回都以仍在选择器为前提。
            for (attempt in 0 until 3) {
                if (main { activity.hasWindowFocus() }) break
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val owner = root?.packageName?.toString().orEmpty()
                transitions.put(JSONObject().put("package", owner).put("focused", root?.window?.isFocused))
                check(owner.endsWith("documentsui")) { "取消时出现非选择器窗口：$owner" }
                instrumentation.uiAutomation.waitForIdle(300, 5000)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                File(output, "$name-picker-before-back-$attempt.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                }
                val returnedBy = SystemClock.uptimeMillis() + 2000
                while (!main { activity.hasWindowFocus() } && SystemClock.uptimeMillis() < returnedBy) SystemClock.sleep(50)
            }
            File(output, "$name-picker-transitions.json").writeText(transitions.toString(2))
            await { activity.hasWindowFocus() }
            main { check(imageAdapter.getItems() == original) }
            File(output, "$name-system-picker.json").writeText(JSONObject().put("package", externalPackage).put("returned", true).toString(2))
        }
        verify("背景编辑器实时预览、取消、确认及撤销") {
            val original = main { activity.getAccentBackground()!!.toJson() }
            fun sheet() = activity.supportFragmentManager.findFragmentByTag(ThingBackgroundEditorBottomSheet.TAG) as? ThingBackgroundEditorBottomSheet
            menu(R.id.act_change_color)
            await { sheet()?.view?.isShown == true }
            main {
                sheet()!!.applyWorldColor(ThingBackgroundEditor.SLOT_PURE, Color.rgb(250, 225, 120))
                check(content.view<TextView>(R.id.et_title).currentTextColor and 0x00ffffff == 0)
                sheet()!!.requireView().findViewById<View>(R.id.tv_cancel_as_bt_tbe).performClick()
            }
            await { sheet() == null }
            main { check(activity.getAccentBackground()!!.toJson() == original) }
            menu(R.id.act_change_color)
            await { sheet()?.view?.isShown == true }
            main {
                sheet()!!.applyWorldColor(ThingBackgroundEditor.SLOT_PURE, Color.rgb(230, 230, 230))
                sheet()!!.requireView().findViewById<View>(R.id.tv_confirm_as_bt_tbe).performClick()
            }
            await { sheet() == null }
            main { check(activity.getAccentColor() == Color.rgb(230, 230, 230)) }
            menu(R.id.act_undo)
            main { check(activity.getAccentBackground()!!.toJson() == original) }
        }
        verify("文件夹路径、私密标题与时间信息离屏保持") {
            val thing: Thing = field("mThing")
            val originalFolder = thing.folderId
            val db = DBHelper(activity).writableDatabase
            val id = db.rawQuery("SELECT COALESCE(MAX(id),0)+1 FROM thing_folders", null).use { check(it.moveToFirst()); it.getLong(0) }
            val folder = ThingFolder(id, null, "详情回归专用文件夹", Thing.UNDERWAY, Color.BLUE, id,
                createTime = 1780272000000L)
            ThingFolderDAO.getInstance(activity)!!.create(folder)
            try {
                main { thing.folderId = id; invoke("updateThingFolderPath"); list.scrollToPosition(content.positionOf(R.id.tv_thing_folder_path)) }
                idle()
                main {
                    val path = content.view<TextView>(R.id.tv_thing_folder_path)
                    check(path.visibility == View.VISIBLE && path.text.toString() == folder.title)
                    check(path.compoundDrawablesRelative[0] != null)
                    check(content.view<TextView>(R.id.tv_update_time).text.isNotBlank())
                    invoke("setAsPrivateThingUiAndAddAction")
                    check(content.view<TextView>(R.id.et_title).compoundDrawables[0] != null)
                    list.scrollToPosition(0)
                }
                idle()
                main { check(content.view<TextView>(R.id.et_title).compoundDrawables[0] != null) }
                export("-folder")
                // 仅验证布局上的私密标记；不修改设备口令，也不绕过无口令时的菜单提示。
                main { invoke("cancelPrivateThingUiAndAddAction") }
                main { check(content.view<TextView>(R.id.et_title).compoundDrawables[0] == null) }
            } finally {
                main { thing.folderId = originalFolder; invoke("updateThingFolderPath") }
                db.delete("thing_folders", "id=? AND create_time=?", arrayOf(id.toString(), "1780272000000"))
            }
        }
        verify("提醒开关、时间选择器及习惯和进行中对话框入口") {
            main { activity.cbQuickRemind!!.performClick(); check(invoke("getThingTypeAfter") == Thing.REMINDER) }
            menu(R.id.act_undo)
            main { check(invoke("getThingTypeAfter") == Thing.NOTE); activity.findViewById<View>(R.id.fl_quick_remind_as_bt).performClick() }
            await { activity.quickRemindPicker!!.isShowing() }
            main { activity.quickRemindPicker!!.dismiss() }
            val date: DateTimeDialogFragment = field("mDateTimeDialogFragment")
            main { date.show(activity.supportFragmentManager, DateTimeDialogFragment.TAG) }
            await { date.dialog?.isShowing == true && date.view?.width?.let { it > 0 } == true }
            main { date.dismiss() }; idle()
            val doing = ThingDoingDialogFragment()
            main { doing.setThing(field("mThing")); doing.show(activity.supportFragmentManager, ThingDoingDialogFragment.TAG) }
            await { doing.dialog?.isShowing == true && doing.view?.width?.let { it > 0 } == true }
            main { doing.dismiss() }; idle()
        }
        verify("复制当前清单和取消分享后保留未保存内容与位置") {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val previous = main { clipboard.primaryClip }
            val before = snapshot()
            try {
                main { invoke("copyContent") }
                main { check(clipboard.primaryClip!!.getItemAt(0).text.toString() == CheckListHelper.toContentStr(before, "- [ ] ", "- [x] ")) }
                row(12)
                val first = main { content.layoutManager.findFirstVisibleItemPosition() }
                menu(R.id.act_share)
                await { field<TapShareOverlay?>("mTapShareOverlay") != null }
                main { field<TapShareOverlay>("mTapShareOverlay").close(null) }
                await { field<TapShareOverlay?>("mTapShareOverlay") == null && activity.hasWindowFocus() }
                main { check(content.layoutManager.findFirstVisibleItemPosition() == first) }
                check(snapshot() == before)
            } finally { main { if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip() } }
        }
        verify("真实长图流程输出带提醒信息的 JPEG 和可读分享 URI") {
            val before = snapshot()
            val first = main { content.layoutManager.findFirstVisibleItemPosition() }
            val ready = CountDownLatch(1)
            var share: Intent? = null
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action != Intent.ACTION_CHOOSER) return null
                    share = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    ready.countDown()
                    // 验证应用交出的完整分享请求，到此截断，不向任何接收方发送测试内容。
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
            instrumentation.addMonitor(monitor)
            try {
                main {
                    activity.cbQuickRemind!!.performClick()
                    check(invoke("getThingTypeAfter") == Thing.REMINDER)
                    invoke("shareThingInScreenshot")
                }
                check(ready.await(25, TimeUnit.SECONDS)) { "真实长图流程没有交出分享请求" }
                val request = checkNotNull(share)
                check(request.action == Intent.ACTION_SEND && request.type == "image/jpeg")
                check(request.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                val uri = checkNotNull(request.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
                val sharedFile = File(output, "$name-shared.jpg")
                activity.contentResolver.openInputStream(uri)!!.use { input -> sharedFile.outputStream().use(input::copyTo) }
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(sharedFile.absolutePath, options)
                check(options.outWidth == list.width && options.outHeight > list.height)
                File(output, "$name-share-jpeg.json").writeText(JSONObject().put("width", options.outWidth)
                    .put("height", options.outHeight).put("uri_readable", true).put("recipient_launch_blocked", true).toString(2))
                await { activity.hasWindowFocus() }
                main { check(content.layoutManager.findFirstVisibleItemPosition() == first) }
                check(snapshot() == before)
            } finally {
                instrumentation.removeMonitor(monitor)
                menu(R.id.act_undo)
            }
        }
    }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) addAll(descendants(root.getChildAt(i)))
    }

    private fun seekToFraction(seek: SeekBar, fraction: Float) {
        val rect = bounds(seek)
        val x = rect.left + seek.paddingLeft + (rect.width() - seek.paddingLeft - seek.paddingRight) * fraction
        val time = SystemClock.uptimeMillis()
        pointer(time, MotionEvent.ACTION_DOWN, x, rect.exactCenterY())
        pointer(time, MotionEvent.ACTION_UP, x, rect.exactCenterY())
        idle()
    }

    private fun pinch(view: View) {
        val rect = bounds(view)
        val time = SystemClock.uptimeMillis()
        fun event(action: Int, distance: Float, count: Int = 2) {
            val properties = Array(count) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply {
                x = rect.exactCenterX() + (if (index == 0) -distance else distance)
                y = rect.exactCenterY(); pressure = 1f; size = 1f
            } }
            val motion = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, count, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { instrumentation.sendPointerSync(motion) } finally { motion.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, rect.width() * 0.15f, 1)
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), rect.width() * 0.15f)
        repeat(15) { event(MotionEvent.ACTION_MOVE, rect.width() * (0.15f + 0.013f * (it + 1))); SystemClock.sleep(20) }
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), rect.width() * 0.345f)
        event(MotionEvent.ACTION_UP, rect.width() * 0.345f, 1)
        idle()
    }

    private fun title() = verify("标题离屏、选择区与撤销重做") {
        val title = content.view<EditText>(R.id.et_title)
        val before = main { title.text.toString() }
        main { title.append("【编辑】"); title.setSelection(2, 6) }
        main { list.scrollToPosition(content.adapter.itemCount - 1) }
        idle()
        main { list.scrollToPosition(content.positionOf(R.id.et_title)) }
        idle()
        main { check(title.text.toString() == before + "【编辑】"); check(title.selectionStart == 2 && title.selectionEnd == 6) }
        menu(R.id.act_undo)
        main { check(title.text.toString() == before) }
        menu(R.id.act_redo)
        main { check(title.text.toString() == before + "【编辑】") }
        menu(R.id.act_undo)
    }

    private fun text() {
        val editor = content.view<EditText>(R.id.et_content)
        verify("正文输入与撤销重做") {
            val original = main { editor.text.toString() }
            main { editor.append("末尾新内容") }
            menu(R.id.act_undo)
            main { check(editor.text.toString() == original) }
            menu(R.id.act_redo)
            main { check(editor.text.toString() == original + "末尾新内容") }
            menu(R.id.act_undo)
        }
        verify("正文与清单互转及撤销") {
            val original = main { editor.text.toString() }
            menu(R.id.act_check_list)
            main { check(content.adapter.adapters.any { it is CheckListAdapter }); check(editor.visibility == View.GONE) }
            menu(R.id.act_undo)
            main { check(editor.visibility == View.VISIBLE); check(editor.text.toString() == original) }
        }
    }

    private fun checklist() {
        val adapter = checklist!!
        verify("同一清单行重复绑定及换色复用四个独立 ripple，保留焦点和选区") {
            val holder = row(0)
            val before = snapshot()
            main {
                val buttons = listOf(holder.ivState!!, holder.ivIndent!!, holder.ivOutdent!!, holder.ivExpandShrink!!)
                val ripples = buttons.map { it.foreground as android.graphics.drawable.RippleDrawable }
                check(ripples.toSet().size == 4)
                val colorField = CheckListAdapter::class.java.getDeclaredField("mThingColor").apply { isAccessible = true }
                val originalColor = colorField.getInt(adapter)
                holder.et!!.requestFocus(); holder.et.setSelection(1, 3)
                try {
                    for (color in listOf(originalColor, originalColor, Color.WHITE, Color.rgb(35, 105, 95), originalColor)) {
                        adapter.setThingColor(color)
                        adapter.onBindViewHolder(holder, 0)
                        check(buttons.indices.all { buttons[it].foreground === ripples[it] })
                        check(holder.et.hasFocus() && holder.et.selectionStart == 1 && holder.et.selectionEnd == 3)
                    }
                } finally {
                    adapter.setThingColor(originalColor); adapter.onBindViewHolder(holder, 0)
                    holder.et.clearFocus()
                }
            }
            check(snapshot() == before)
        }
        verify("清单编辑与回收后的撤销重做") {
            val before = snapshot()
            val item = row(10)
            main { item.et!!.requestFocus(); item.et.append("【编辑】") }
            val after = snapshot()
            check(before != after)
            main { activity.currentFocus?.clearFocus(); list.scrollToPosition(content.adapter.itemCount - 1) }
            idle()
            row(0)
            check(snapshot() == after)
            menu(R.id.act_undo)
            check(snapshot() == before)
            menu(R.id.act_redo)
            check(snapshot() == after)
            menu(R.id.act_undo)
        }
        verify("聚焦清单行离屏返回保留选择区") {
            val original = snapshot()
            val focused = row(10)
            main { focused.et!!.requestFocus(); focused.et.setSelection(2, 5) }
            main { list.scrollToPosition(content.adapter.itemCount - 1) }
            idle()
            val restored = row(10)
            main {
                check(restored.et!!.hasFocus()) { "离屏返回后原编辑行没有恢复焦点" }
                check(restored.et!!.selectionStart == 2 && restored.et.selectionEnd == 5) {
                    "离屏返回后选择区变为 ${restored.et.selectionStart}..${restored.et.selectionEnd}"
                }
                activity.currentFocus?.clearFocus()
            }
            check(snapshot() == original)
        }
        verify("外观重绑、重复文字、重排与焦点切换不串行") {
            val original = snapshot()
            val ten = row(10)
            main { ten.et!!.requestFocus(); ten.et.setText("相同内容的两个清单项"); ten.et.setSelection(2, 5) }
            val eleven = row(11)
            main { eleven.et!!.requestFocus(); eleven.et.setText("相同内容的两个清单项"); eleven.et.setSelection(7, 9) }
            main { list.scrollToPosition(content.adapter.itemCount - 1) }
            idle()
            val restored = row(10)
            main { check(restored.et!!.selectionStart == 2 && restored.et.selectionEnd == 5); check(!restored.et.hasFocus()) }
            val active = row(11)
            main {
                check(active.et!!.selectionStart == 7 && active.et.selectionEnd == 9)
                check(active.et.hasFocus())
                adapter.notifyItemChanged(11)
            }
            idle()
            main { check(active.et!!.selectionStart == 7 && active.et.selectionEnd == 9) }
            main { invoke("moveChecklist", 11, 10) }
            idle()
            main { list.scrollToPosition(content.adapter.itemCount - 1) }
            idle()
            val moved = row(10)
            main { check(moved.et!!.selectionStart == 7 && moved.et.selectionEnd == 9) }
            val title = content.view<EditText>(R.id.et_title)
            main { list.scrollToPosition(content.positionOf(R.id.et_title)) }
            idle()
            main { title.requestFocus() }
            row(10)
            main { check(mFocusedChecklist() == null) { "切换到标题后旧行重新获得焦点" }; title.clearFocus() }
            // 两项的存储内容完全相同，交换不产生内容撤销，只撤销两次文字编辑。
            menu(R.id.act_undo); menu(R.id.act_undo)
            check(snapshot() == original)
        }
        verify("输入法组合文本离屏返回与继续提交") {
            val before = snapshot()
            val item = row(10)
            val prefix = main { item.et!!.text.toString() }
            main {
                item.et!!.requestFocus(); item.et.setSelection(item.et.length())
                val connection = item.et.onCreateInputConnection(EditorInfo())!!
                check(connection.setComposingText("测试", 1))
            }
            main { list.scrollToPosition(content.adapter.itemCount - 1) }
            idle()
            val restored = row(10)
            main {
                check(restored.et!!.text.toString() == prefix + "测试")
                check(BaseInputConnection.getComposingSpanStart(restored.et.text) == prefix.length) { "组合文本范围丢失" }
                val connection = restored.et.onCreateInputConnection(EditorInfo())!!
                check(connection.commitText("完成", 1))
                check(restored.et.text.toString() == prefix + "完成")
                restored.et.clearFocus()
            }
            menu(R.id.act_undo); menu(R.id.act_undo)
            check(snapshot() == before)
        }
        verify("回车插入、目标聚焦和退格合并") {
            val original = snapshot()
            val count = main { adapter.getItems()!!.size }
            val current = row(10)
            main {
                current.et!!.requestFocus()
                current.et.setSelection(current.et.length())
                check(current.et.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
            }
            await { adapter.getItems()!!.size == count + 1 &&
                (content.holder(adapter, 11) as? CheckListAdapter.EditTextHolder)?.et?.hasFocus() == true }
            val inserted = row(11)
            main {
                inserted.et!!.append("合并文本")
                inserted.et.setSelection(0)
                check(inserted.et.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
            }
            await { adapter.getItems()!!.size == count }
            main { check(CheckListHelper.textOf(adapter.getItems()!![10]).endsWith("合并文本")) }
            menu(R.id.act_undo)
            menu(R.id.act_undo)
            menu(R.id.act_undo)
            check(snapshot() == original)
        }
        verify("缩进、反缩进及内容保持") {
            val before = snapshot()
            val item = row(4)
            main { item.et!!.requestFocus(); item.refreshIndentOutdent(); check(item.ivIndent!!.isClickable); item.ivIndent.performClick() }
            await { CheckListHelper.levelOf(adapter.getItems()!![4]) == 2 }
            val indented = row(4)
            main { indented.et!!.requestFocus(); indented.refreshIndentOutdent(); indented.ivOutdent!!.performClick() }
            await { CheckListHelper.levelOf(adapter.getItems()!![4]) == 1 }
            check(snapshot() == before)
        }
        verify("父项级联完成与撤销") {
            val before = snapshot()
            val labels = main { adapter.getItems()!!.take(4).map(CheckListHelper::textOf) }
            val parent = row(0)
            main { parent.ivState!!.performClick() }
            idle()
            main { labels.forEach { label -> check(adapter.getItems()!!.any { CheckListHelper.textOf(it) == label && CheckListHelper.isFinished(it) }) } }
            menu(R.id.act_undo)
            check(snapshot() == before)
        }
        verify("完成区展开折叠与挂载数量") {
            val foldedCount = main { adapter.itemCount }
            val header = main { adapter.getItems()!!.indexOf("4") }
            val foldedHeader = row(header)
            main { foldedHeader.ivExpandShrink!!.performClick() }
            idle()
            main { check(adapter.isExpanded()); check(adapter.itemCount > foldedCount) }
            main { list.scrollToPosition(content.globalPosition(adapter, adapter.itemCount - 1)) }
            idle()
            main { check(list.childCount < 40) }
            val head = row(header)
            main { head.ivExpandShrink!!.performClick() }
            await { !adapter.isExpanded() && adapter.itemCount == foldedCount }
        }
    }

    private fun media() {
        val imageAdapter = images!!
        verify("媒体满宽与比例、撤销重做和恢复网格") {
            val key = imageAdapter.getItems()!![0]!!
            val before: DetailAttachmentMediaAppearance = field("mDetailAttachmentMediaAppearance")
            val changed = before.withSource(key, DetailAttachmentMediaAppearance.SourceAppearance(
                fullSpanEnabled = true, presentations = mapOf("fullSpan" to
                    DetailAttachmentMediaAppearance.MediaPresentationAppearance(targetAspectRatio = 2.0))))
            main { invoke("applyDetailAttachmentMediaAppearance", changed); list.scrollToPosition(0) }
            idle()
            main { check(imageAdapter.isFullSpanPosition(0)); check(content.holder(imageAdapter, 0)!!.itemView.width == list.width) }
            menu(R.id.act_undo)
            main { check(!imageAdapter.isFullSpanPosition(0)) }
            menu(R.id.act_redo)
            main { check(imageAdapter.isFullSpanPosition(0)) }
            menu(R.id.act_undo)
        }
        verify("图片与音频在各自分区重排并撤销") {
            for ((adapter, isImage) in listOf(imageAdapter to true, audios!! to false)) {
                val before = if (isImage) imageAdapter.getItems()!!.toList() else audios.getItems()!!.toList()
                main { invoke("moveAttachment", 0, 2, isImage) }
                idle()
                val after = if (isImage) imageAdapter.getItems() else audios.getItems()
                check(after!![2] == before[0])
                menu(R.id.act_undo)
                check((if (isImage) imageAdapter.getItems() else audios.getItems()) == before)
            }
        }
        verify("图片与音频长按拖拽手势及分区内重排") {
            for ((adapter, isImage) in listOf(imageAdapter to true, audios!! to false)) {
                val original = main { (if (isImage) imageAdapter.getItems() else audios.getItems())!!.toList() }
                main { content.layoutManager.scrollToPositionWithOffset(content.globalPosition(adapter, 0),
                    (64 * activity.resources.displayMetrics.density).toInt()) }
                idle()
                await { content.holder(adapter, 0) != null && content.holder(adapter, 1) != null }
                val start = bounds(main { content.holder(adapter, 0)!!.itemView })
                val target = bounds(main { content.holder(adapter, 1)!!.itemView })
                val time = SystemClock.uptimeMillis()
                pointer(time, MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY())
                SystemClock.sleep(650)
                main {
                    val helper: androidx.recyclerview.widget.ItemTouchHelper = field("mChecklistTouchHelper")
                    val selected = helper.javaClass.getDeclaredField("mSelected").apply { isAccessible = true }.get(helper)
                    check(selected != null) { "长按未选中附件；触摸分区=${field<Any?>("mTouchSection")}" }
                }
                // ItemTouchHelper 的换位判定要求越过目标边界，精确停在相同中心尚不构成跨越。
                val endX = target.exactCenterX() + kotlin.math.sign(target.exactCenterX() - start.exactCenterX()) * 12
                val endY = target.exactCenterY() + kotlin.math.sign(target.exactCenterY() - start.exactCenterY()) * 12
                drag(time, start.exactCenterX(), start.exactCenterY(), endX, endY)
                pointer(time, MotionEvent.ACTION_UP, endX, endY)
                await { (if (isImage) imageAdapter.getItems() else audios.getItems())!![1] == original[0] }
                menu(R.id.act_undo)
                main { check((if (isImage) imageAdapter.getItems() else audios.getItems()) == original) }
            }
        }
        verify("音频就地播放、离屏返回和停止") {
            val adapter = audios!!
            main { list.scrollToPosition(content.globalPosition(adapter, 0)) }
            await { content.holder(adapter, 0) != null }
            main { (content.holder(adapter, 0) as AudioAttachmentAdapter.AudioCardViewHolder).ivFirst!!.performClick() }
            main { check(adapter.getPlayingIndex() == 0); list.scrollToPosition(0) }
            idle()
            main { list.scrollToPosition(content.globalPosition(adapter, 0)) }
            await { content.holder(adapter, 0) != null }
            main {
                val holder = content.holder(adapter, 0) as AudioAttachmentAdapter.AudioCardViewHolder
                check(holder.ivSecond!!.contentDescription == activity.getString(R.string.cd_stop_play_audio_attachment))
                holder.ivSecond.performClick()
                check(adapter.getPlayingIndex() == -1)
            }
        }
        verify("重排其它音频时正在播放的附件身份保持") {
            val adapter = audios!!
            main { list.scrollToPosition(content.globalPosition(adapter, 0)) }
            await { content.holder(adapter, 0) != null }
            main {
                (content.holder(adapter, 0) as AudioAttachmentAdapter.AudioCardViewHolder).ivFirst!!.performClick()
                invoke("moveAttachment", 2, 1, false)
                check(adapter.getPlayingIndex() == 0)
                invoke("moveAttachment", 2, 0, false)
                check(adapter.getPlayingIndex() == 1)
                adapter.stopPlaying()
            }
            menu(R.id.act_undo); menu(R.id.act_undo)
        }
        verify("删除媒体到单项、空区与撤销恢复") {
            val original = imageAdapter.getItems()!!.toList()
            for (position in 2 downTo 0) {
                val holder = image(position)
                main { holder.ivDelete!!.performClick() }
                idle()
                main {
                    check(imageAdapter.itemCount == position)
                    if (position == 1) check(imageAdapter.getItemTargetSize(0)[0] == list.width)
                    if (position == 0) check(content.globalPosition(imageAdapter, 0) == RecyclerView.NO_POSITION)
                }
            }
            menu(R.id.act_undo); menu(R.id.act_undo); menu(R.id.act_undo)
            main { check(imageAdapter.getItems() == original); check(!imageAdapter.isFullSpanPosition(0)) }
        }
        verify("删除播放项前的音频、空区与撤销恢复") {
            val adapter = audios!!
            val original = adapter.getItems()!!.toList()
            val playing = audio(2)
            main { playing.ivFirst!!.performClick(); check(adapter.getPlayingIndex() == 2) }
            repeat(2) { index ->
                val first = audio(0)
                main { first.ivSecond!!.performClick(); check(adapter.getPlayingIndex() == 1 - index) }
                idle()
            }
            val last = audio(0)
            main { last.ivSecond!!.performClick() }
            idle()
            main { last.ivSecond!!.performClick() }
            idle()
            main { check(adapter.itemCount == 0); check(content.globalPosition(adapter, 0) == RecyclerView.NO_POSITION) }
            menu(R.id.act_undo); menu(R.id.act_undo); menu(R.id.act_undo)
            main { check(adapter.getItems() == original) }
        }
        verify("音频播放对话框、切曲、进度与附件信息") {
            val holder = audio(0)
            main { holder.cv!!.performClick() }
            await { activity.supportFragmentManager.findFragmentByTag(AudioPlayDialogFragment.TAG)?.view != null }
            val dialog = main { activity.supportFragmentManager.findFragmentByTag(AudioPlayDialogFragment.TAG) as AudioPlayDialogFragment }
            await { dialog.dialog?.window?.decorView?.hasWindowFocus() == true }
            await { dialog.view?.findViewById<View>(R.id.iv_play_next_audio)?.isEnabled == true }
            main { dialog.requireView().findViewById<View>(R.id.iv_play_next_audio).performClick() }
            await { dialog.requireView().findViewById<TextView>(R.id.tv_playing_audio_file_name).text.contains("layout-audio-1") }
            main { dialog.requireView().findViewById<View>(R.id.iv_play_previous_audio).performClick() }
            await { dialog.requireView().findViewById<TextView>(R.id.tv_playing_audio_file_name).text.contains("layout-audio-0") }
            val seek = main { dialog.requireView().findViewById<SeekBar>(R.id.sb_audio_progress) }
            await { seek.isEnabled && seek.max == 3000 }
            main { dialog.requireView().findViewById<View>(R.id.iv_play_main_action).performClick() }
            val rect = bounds(seek)
            val time = SystemClock.uptimeMillis()
            pointer(time, MotionEvent.ACTION_DOWN, rect.left + rect.width() * .7f, rect.exactCenterY())
            pointer(time, MotionEvent.ACTION_UP, rect.left + rect.width() * .7f, rect.exactCenterY())
            await { seek.progress > seek.max / 2 }
            main { dialog.dismiss() }
            await { activity.supportFragmentManager.findFragmentByTag(AudioPlayDialogFragment.TAG) == null }
            val infoHolder = audio(0)
            main { infoHolder.ivThird!!.performClick() }
            await { activity.supportFragmentManager.findFragmentByTag(AttachmentInfoDialogFragment.TAG)?.view != null }
            main { (activity.supportFragmentManager.findFragmentByTag(AttachmentInfoDialogFragment.TAG) as AttachmentInfoDialogFragment).dismiss() }
            idle()
        }
    }

    private fun appearanceChanges() = verify("详情打开期间切换明暗主题并保留编辑与附件状态") {
        val original = snapshot()
        val mode = AppCompatDelegate.getDefaultNightMode()
        val title = content.view<EditText>(R.id.et_title)
        val titleBefore = main { title.text.toString() }
        val mediaBefore = main { images!!.getItems()!!.toList() }
        try {
            for (dark in listOf(true, false)) {
                main { AppCompatDelegate.setDefaultNightMode(if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO) }
                await { (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO }
                val holder = audio(0)
                main {
                    check(holder.tvName!!.currentTextColor == ContextCompat.getColor(activity, R.color.app_chrome_on_surface_secondary))
                    check(title.text.toString() == titleBefore)
                    check(images!!.getItems() == mediaBefore)
                }
                check(snapshot() == original)
            }
        } finally { main { AppCompatDelegate.setDefaultNightMode(mode) }; idle() }
    }

    private fun dynamicMedia() {
        val adapter = images!!
        val controller: DetailAttachmentPlaybackController = field("mAttachmentPlaybackController")
        val previous = DetailAutoplayMode.current()
        fun mode(value: Int) = main { FrequentSettings.put(Def.Meta.KEY_AUTOPLAY_DETAIL_DYNAMIC, value); controller.onResume() }
        fun gif(position: Int): GifDrawable? = (content.holder(adapter, position) as? ImageAttachmentAdapter.ImageViewHolder)?.ivImage?.drawable as? GifDrawable
        try {
            verify("动态图关闭、循环、离屏停止与回收返回") {
                mode(DetailAutoplayMode.OFF)
                image(0)
                await {
                    val holder = content.holder(adapter, 0) as? ImageAttachmentAdapter.ImageViewHolder
                    val drawable = holder?.ivImage?.drawable
                    drawable != null && holder.pbLoading!!.visibility != View.VISIBLE &&
                        (drawable !is android.graphics.drawable.Animatable || !drawable.isRunning) && !controller.shouldPlay(0)
                }
                mode(DetailAutoplayMode.ALL_LOOP)
                await { gif(0)?.isRunning == true }
                val drawable = main { gif(0)!! }
                main { list.scrollToPosition(content.adapter.itemCount - 1) }
                idle()
                await { !controller.shouldPlay(0) && !drawable.isRunning }
                image(0)
                await { gif(0)?.isRunning == true }
            }
            verify("关闭自动播放时长按手动播放") {
                mode(DetailAutoplayMode.OFF)
                val holder = image(0)
                val rect = bounds(holder.ivImage!!)
                val time = SystemClock.uptimeMillis()
                pointer(time, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY())
                SystemClock.sleep(650)
                val playing = main { controller.shouldPlay(0) }
                pointer(time, MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY())
                check(playing)
                await { !controller.shouldPlay(0) }
            }
            verify("动态媒体快速离屏和重排后无错播") {
                mode(DetailAutoplayMode.ALL_LOOP)
                repeat(5) {
                    main { list.scrollToPosition(0) }; idle()
                    main { invoke("moveAttachment", 0, 2, true) }; idle()
                    main { list.scrollToPosition(content.adapter.itemCount - 1) }; idle()
                    main { check((0 until adapter.itemCount).none(controller::shouldPlay)) }
                    menu(R.id.act_undo)
                }
                image(0)
                await { gif(0)?.isRunning == true }
            }
            // 视频生成依赖设备存储条件，最后验证，避免阻断独立的 GIF 手动播放与重排检查。
            verify("视频派生预览、逐一播放和全部一次后停止") {
                image(1)
                await(45000) { adapter.isPlayableNow(1) }
                mode(DetailAutoplayMode.ONE_BY_ONE)
                await { controller.shouldPlay(0) }
                await(12000) { !controller.shouldPlay(0) && controller.shouldPlay(1) }
                await(12000) { !controller.shouldPlay(0) && !controller.shouldPlay(1) && !controller.shouldPlay(2) }
                mode(DetailAutoplayMode.ALL_ONCE)
                await { controller.shouldPlay(0) && controller.shouldPlay(1) }
                await(12000) { !controller.shouldPlay(0) && !controller.shouldPlay(1) && !controller.shouldPlay(2) }
            }
        } finally { mode(previous) }
    }

    private fun image(position: Int): ImageAttachmentAdapter.ImageViewHolder {
        main { list.scrollToPosition(content.globalPosition(images!!, position)) }; idle()
        await { content.holder(images!!, position) != null }
        return main { content.holder(images!!, position) as ImageAttachmentAdapter.ImageViewHolder }
    }

    private fun audio(position: Int): AudioAttachmentAdapter.AudioCardViewHolder {
        main { list.scrollToPosition(content.globalPosition(audios!!, position)) }; idle()
        await { content.holder(audios!!, position) != null }
        return main { content.holder(audios!!, position) as AudioAttachmentAdapter.AudioCardViewHolder }
    }

    private fun gestures() {
        val adapter = checklist!!
        verify("真实触摸拖动父项、子树恢复与一次撤销") {
            val before = snapshot()
            val block = main { adapter.getItems()!!.take(4) }
            main { content.view<View>(R.id.tv_move_checklist_as_bt).performClick() }
            check(main { adapter.isDragging() })
            val parent = row(0)
            val start = bounds(parent.ivState!!)
            val time = SystemClock.uptimeMillis()
            pointer(time, MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY())
            await { adapter.getItems()!!.size == 90 + 3 - 3 }
            val target = main { content.holder(adapter, 1)!!.itemView }
            val destination = bounds(target)
            drag(time, start.exactCenterX(), start.exactCenterY(), start.exactCenterX(), destination.bottom + start.height() / 2f)
            pointer(time, MotionEvent.ACTION_UP, start.exactCenterX(), destination.bottom + start.height() / 2f)
            await { field<Any?>("mDraggedChecklistVH") == null && adapter.getItems()!!.size == 93 }
            main {
                val moved = adapter.getItems()!!.indexOf(block[0])
                check(moved > 0) { "实际拖拽没有改变父项位置" }
                check(adapter.getItems()!!.subList(moved, moved + 4) == block)
            }
            menu(R.id.act_undo)
            check(snapshot() == before)
            main { content.view<View>(R.id.tv_move_checklist_as_bt).performClick() }
        }
        verify("真实跨屏拖拽与静止手指自动重排") {
            val before = snapshot()
            val block = main { adapter.getItems()!!.take(4) }
            main { content.view<View>(R.id.tv_move_checklist_as_bt).performClick() }
            val parent = row(0)
            val start = bounds(parent.ivState!!)
            val viewport = bounds(list)
            val endY = viewport.bottom - start.height() / 2f
            val time = SystemClock.uptimeMillis()
            pointer(time, MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY())
            await { adapter.getItems()!!.size == 90 }
            drag(time, start.exactCenterX(), start.exactCenterY(), start.exactCenterX(), endY)
            val initial = main { adapter.getItems()!!.indexOf(block[0]) }
            // 手指保持不动，只让应用自己的帧回调推动自动滚动和重排。
            SystemClock.sleep(1800)
            val afterHold = main { adapter.getItems()!!.indexOf(block[0]) }
            pointer(time, MotionEvent.ACTION_UP, start.exactCenterX(), endY)
            await { field<Any?>("mDraggedChecklistVH") == null && adapter.getItems()!!.size == 93 }
            main {
                check(afterHold > initial + 3) { "边缘静止时没有继续重排：$initial → $afterHold" }
                val moved = adapter.getItems()!!.indexOf(block[0])
                check(adapter.getItems()!!.subList(moved, moved + 4) == block)
                check(moved < adapter.getItems()!!.indexOf("3"))
            }
            menu(R.id.act_undo)
            check(snapshot() == before)
            main { content.view<View>(R.id.tv_move_checklist_as_bt).performClick() }
        }
    }

    private fun dragConstraints() {
        val adapter = checklist!!
        val original = main { adapter.getItems()!!.toList() }
        fun fixture(items: List<String?>) {
            main {
                if (adapter.isDragging()) content.view<View>(R.id.tv_move_checklist_as_bt).performClick()
                adapter.setExpanded(true)
                adapter.setItems(items.toMutableList())
                content.view<View>(R.id.tv_move_checklist_as_bt).performClick()
                list.scrollToPosition(content.globalPosition(adapter, 0))
            }
            idle()
        }
        fun ended() {
            await { field<Any?>("mDragBeforeContent") == null && field<Any?>("mDragScrollRunnable") == null &&
                field<Any?>("mCollapsedChildren") == null && field<Any?>("mCapsuleDrawable") == null }
            idle()
        }
        fun attempt(from: Int, to: Int) {
            val start = bounds(row(from).ivState!!)
            val destination = bounds(main { content.holder(adapter, to)!!.itemView })
            val time = SystemClock.uptimeMillis()
            pointer(time, MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY())
            await { field<Any?>("mDraggedChecklistVH") != null }
            val endY = if (to > from) destination.bottom + start.height() / 2f else destination.top - start.height() / 2f
            drag(time, start.exactCenterX(), start.exactCenterY(), start.exactCenterX(), endY)
            pointer(time, MotionEvent.ACTION_UP, start.exactCenterX(), endY)
            ended()
        }
        try {
            verify("图片和音频的真实长按拖拽不能进入清单或固定区域") {
                fixture(listOf("01分区边界清单", "2"))
                main { content.view<View>(R.id.tv_move_checklist_as_bt).performClick() }
                val before = snapshot()
                for ((adapterSection, isImage) in listOf(images!! to true, audios!! to false)) {
                    val entries = main { (if (isImage) images.getItems() else audios.getItems())!!.toList() }
                    val index = if (isImage) entries.lastIndex else 0
                    main { content.layoutManager.scrollToPositionWithOffset(content.globalPosition(adapterSection, index),
                        (if (isImage) 60 else 250) * activity.resources.displayMetrics.density.toInt()) }
                    idle()
                    val start = bounds(main { content.holder(adapterSection, index)!!.itemView })
                    val viewport = bounds(list)
                    val endY = (start.exactCenterY() + (if (isImage) 260 else -200) * activity.resources.displayMetrics.density)
                        .coerceIn(viewport.top + 40f, viewport.bottom - 40f)
                    val time = SystemClock.uptimeMillis()
                    pointer(time, MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY())
                    SystemClock.sleep(650)
                    main {
                        val helper: androidx.recyclerview.widget.ItemTouchHelper = field("mChecklistTouchHelper")
                        val selected = helper.javaClass.getDeclaredField("mSelected").apply { isAccessible = true }.get(helper) as? RecyclerView.ViewHolder
                        check(selected?.bindingAdapter === adapterSection) {
                            "跨分区拖拽未开始：image=$isImage bounds=$start window=${activity.hasWindowFocus()} touch=${field<Any?>("mTouchSection")} selected=$selected"
                        }
                    }
                    drag(time, start.exactCenterX(), start.exactCenterY(), start.exactCenterX(), endY)
                    pointer(time, MotionEvent.ACTION_UP, start.exactCenterX(), endY)
                    idle()
                    main { check((if (isImage) images.getItems() else audios.getItems()) == entries) }
                    check(snapshot() == before)
                }
            }
            verify("实际拖拽拒绝跨父节点及跨层级") {
                fixture(listOf("01父甲", "02甲子", "01父乙", "02乙子", "2"))
                val before = snapshot()
                attempt(1, 3)
                check(snapshot() == before) { "子项跨越了父节点" }
                attempt(1, 0)
                check(snapshot() == before) { "子项越过了父级边界" }
            }
            verify("实际拖拽不能跨越完成区边界") {
                fixture(listOf("01未完成", "2", "3", "4", "11已完成"))
                val before = snapshot()
                attempt(0, 4)
                check(snapshot() == before)
                attempt(4, 0)
                check(snapshot() == before)
            }
            verify("实际触摸取消恢复子树、轮廓和滚动任务") {
                fixture(listOf("01父项", "02子项", "03孙项", "01兄弟", "2"))
                val before = snapshot()
                val start = bounds(row(0).ivState!!)
                val time = SystemClock.uptimeMillis()
                pointer(time, MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY())
                await { adapter.getItems()!!.size == 3 }
                pointer(time, MotionEvent.ACTION_CANCEL, start.exactCenterX(), start.exactCenterY())
                ended()
                check(snapshot() == before)
                await { (0 until list.childCount).all { list.getChildAt(it).translationY == 0f } }
            }
            verify("向上跨屏拖拽在静止手指下继续移动且只需一次撤销") {
                fixture((0 until 65).map { "01向上拖拽 $it" } + "2")
                val before = snapshot()
                val label = main { adapter.getItems()!![45] }
                val start = bounds(row(45).ivState!!)
                val viewport = bounds(list)
                val endY = viewport.top + start.height() / 2f
                val time = SystemClock.uptimeMillis()
                pointer(time, MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY())
                await { field<Any?>("mDraggedChecklistVH") != null }
                drag(time, start.exactCenterX(), start.exactCenterY(), start.exactCenterX(), endY)
                val first = main { adapter.getItems()!!.indexOf(label) }
                SystemClock.sleep(1500)
                val second = main { adapter.getItems()!!.indexOf(label) }
                pointer(time, MotionEvent.ACTION_UP, start.exactCenterX(), endY)
                ended()
                check(second < first - 3) { "上边缘静止时没有继续移动：$first → $second" }
                menu(R.id.act_undo)
                check(snapshot() == before)
            }
            verify("离开页面时恢复拖拽子树，自动保存不能写入收束后的代理内容") {
                fixture(listOf("01离开父项", "02离开子项", "03离开孙项", "01兄弟", "2"))
                val before = snapshot()
                val previous = FrequentSettings.getBoolean(Def.Meta.KEY_AUTO_SAVE_EDITS)
                var cover: Activity? = null
                try {
                    main { FrequentSettings.put(Def.Meta.KEY_AUTO_SAVE_EDITS, true) }
                    val start = bounds(row(0).ivState!!)
                    val time = SystemClock.uptimeMillis()
                    pointer(time, MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY())
                    await { adapter.getItems()!!.size == 3 }
                    cover = instrumentation.startActivitySync(Intent(activity, DetailRegressionCoverActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    instrumentation.waitForIdleSync()
                    val saved = DBHelper(activity).readableDatabase.rawQuery("SELECT content FROM things WHERE id=?",
                        arrayOf(activity.currentThingId().toString())).use { check(it.moveToFirst()); it.getString(0) }
                    check(saved == before) { "后台自动保存丢失了拖动中收束的子树" }
                    main { cover?.finish() }; cover = null
                    idle(); ended()
                    check(snapshot() == before)
                } finally {
                    main { cover?.finish(); FrequentSettings.put(Def.Meta.KEY_AUTO_SAVE_EDITS, previous) }
                }
            }
        } finally {
            main {
                if (adapter.isDragging()) content.view<View>(R.id.tv_move_checklist_as_bt).performClick()
                adapter.setItems(original.toMutableList())
                adapter.setExpanded(false)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun bounds(view: View): Rect = main {
        Rect().also {
            check(view.getLocalVisibleRect(it) && it.width() > 0 && it.height() > 0)
            // 局部可见区域加控件的屏幕原点，同时覆盖普通页面、平板兼容窗口和独立 Dialog 窗口。
            val origin = IntArray(2)
            view.getLocationOnScreen(origin)
            it.offset(origin[0], origin[1])
        }
    }

    private fun pointer(downTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        if (action == MotionEvent.ACTION_DOWN) {
            pointerTrace = JSONArray()
            File(output, "$name-pointer.json").writeText(JSONObject().put("x", x).put("y", y)
                .put("window_focused", main { activity.hasWindowFocus() }).toString(2))
        }
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        if (name.endsWith("checklist")) {
            val state = main {
                val dragged: RecyclerView.ViewHolder? = field("mDraggedChecklistVH")
                JSONObject().put("action", action).put("x", x).put("y", y)
                    .put("dragged", dragged?.bindingAdapterPosition)
                    .put("first", content.layoutManager.findFirstVisibleItemPosition())
                    .put("top", dragged?.itemView?.top).put("translation", dragged?.itemView?.translationY)
                    .put("pointer", field<Int>("mLastTouchScreenY"))
            }
            pointerTrace.put(state)
            File(output, "$name-pointer-trace.json").writeText(pointerTrace.toString(2))
        }
    }

    private fun drag(time: Long, x1: Float, y1: Float, x2: Float, y2: Float) {
        repeat(24) {
            val progress = (it + 1) / 24f
            pointer(time, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * progress, y1 + (y2 - y1) * progress)
            SystemClock.sleep(16)
        }
    }

    private fun colors() = verify("改变背景后离屏控件及重新绑定行前景同步") {
        val previous = main { activity.getAccentBackground()!! }
        val title = content.view<EditText>(R.id.et_title)
        main { invoke("changeBackground", ThingBackground.pure(Color.rgb(250, 225, 120))) }
        main { check(title.currentTextColor and 0x00ffffff == 0) }
        main { invoke("changeBackground", ThingBackground.pure(Color.rgb(25, 40, 80))) }
        main { check(title.currentTextColor and 0x00ffffff == 0x00ffffff) }
        checklist?.let { adapter ->
            val first = row(0)
            main { check(first.et!!.currentTextColor and 0x00ffffff == 0x00ffffff) }
        }
        main { invoke("changeBackground", previous) }
    }

    private fun export(suffix: String = "") = verify("长图包含离屏内容且不改动编辑状态$suffix") {
        val original = checklist?.let { snapshot() }
        val focus = main { activity.currentFocus }
        val beforeCount = main { list.childCount }
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        lateinit var renderer: DetailScreenshotRenderer
        main {
            renderer = DetailScreenshotRenderer(activity, content, activity.getAccentBackground()!!,
                field("mEditable"), checklist, images?.getItems(), field("mDetailAttachmentMediaAppearance"),
                field("mMaxSpanImage"), audios?.getItems(), field("mSpanAudio"), field("mShouldAutoLink"))
            renderer.render { bitmap = it; latch.countDown() }
        }
        check(latch.await(15, TimeUnit.SECONDS)) { "长图渲染超时" }
        val image = checkNotNull(bitmap) { "长图渲染失败" }
        File(output, "$name$suffix-export.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 宽屏正文换行较少，不能用任意的“超过两屏”判断是否完整；检查内容真实高度与最后一行。
        main {
            val body = renderer.root.getChildAt(0)
            for (id in listOf(R.id.et_title, R.id.tv_thing_folder_path)) {
                val from = content.view<TextView>(id).compoundDrawablesRelative
                val to = renderer.root.findViewById<TextView>(id).compoundDrawablesRelative
                for (index in from.indices) {
                    val originalIcon = from[index] ?: continue
                    val copiedIcon = checkNotNull(to[index]) { "长图缺少文字旁的图标：$id/$index" }
                    check(originalIcon.bounds == copiedIcon.bounds) { "长图图标尺寸变化：$id/$index" }
                    fun pixels(icon: android.graphics.drawable.Drawable): Bitmap {
                        val size = icon.bounds
                        return Bitmap.createBitmap(size.width(), size.height(), Bitmap.Config.ARGB_8888).also {
                            val canvas = android.graphics.Canvas(it)
                            canvas.translate(-size.left.toFloat(), -size.top.toFloat())
                            icon.draw(canvas)
                        }
                    }
                    val a = pixels(originalIcon)
                    val b = pixels(copiedIcon)
                    check(a.sameAs(b)) { "长图图标像素变化：$id/$index" }
                    a.recycle(); b.recycle()
                }
            }
            check(image.width == list.width && image.height == body.height && image.height > list.height) {
                "导出 ${image.width}×${image.height}，视口 ${list.width}×${list.height}，内容高 ${body.height}"
            }
            val source = content.view<EditText>(R.id.et_content)
            if (source.visibility == View.VISIBLE) {
                val exported = renderer.root.findViewById<EditText>(R.id.et_content)
                check(exported.text.toString() == source.text.toString())
                check(exported.layout.getLineEnd(exported.lineCount - 1) == source.length())
                val lastBottom = exported.top + exported.totalPaddingTop + exported.layout.getLineBottom(exported.lineCount - 1)
                check(lastBottom <= image.height) { "正文末行被截断：$lastBottom > ${image.height}" }
            }
        }
        if (images != null && images.itemCount > 0) {
            val background = main { activity.getAccentBackground()!!.representativeColor() }
            check(image.getPixel(image.width / 4, image.width / 4) != background) {
                "长图媒体区域为空白，不能仅凭图片高度判为导出通过"
            }
        }
        image.recycle()
        if (original != null) check(snapshot() == original)
        main { check(activity.currentFocus === focus); check(list.childCount <= beforeCount + 2) }
    }

    private fun row(position: Int): CheckListAdapter.EditTextHolder {
        val adapter = checklist!!
        main { list.scrollToPosition(content.globalPosition(adapter, position)) }
        idle()
        var holder: CheckListAdapter.EditTextHolder? = null
        await {
            holder = content.holder(adapter, position) as? CheckListAdapter.EditTextHolder
            holder != null && !list.isLayoutRequested
        }
        return holder!!
    }

    private fun mFocusedChecklist(): View? = activity.currentFocus?.takeIf {
        list.findContainingViewHolder(it)?.bindingAdapter === checklist
    }

    private fun snapshot() = main { CheckListHelper.toCheckListStr(checklist!!.getItems()) }

    private fun menu(id: Int) {
        main { activity.onOptionsItemSelected(activity.findViewById<Toolbar>(R.id.actionbar).menu.findItem(id)) }
        idle()
    }

    private fun idle() {
        instrumentation.waitForIdleSync()
        await { !list.isComputingLayout && !list.isLayoutRequested }
    }

    private fun await(timeout: Long = 5000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (main(condition)) return
            SystemClock.sleep(30)
        }
        error("等待界面状态超时")
    }

    private fun verify(label: String, test: () -> Unit) {
        val item = JSONObject().put("check", label)
        try { test(); item.put("passed", true) }
        catch (error: Throwable) {
            item.put("passed", false).put("error", error.stackTraceToString())
            images?.let { adapter ->
                val diagnostics = main {
                    val controller: DetailAttachmentPlaybackController = field("mAttachmentPlaybackController")
                    JSONArray((0 until adapter.itemCount).map { index ->
                        val holder = content.holder(adapter, index) as? ImageAttachmentAdapter.ImageViewHolder
                        val drawable = holder?.ivImage?.drawable
                        JSONObject().put("position", index).put("attached", holder?.ivImage?.isAttachedToWindow)
                            .put("type", drawable?.javaClass?.name).put("playing", controller.shouldPlay(index))
                            .put("playable", adapter.isPlayableNow(index))
                            .put("running", (drawable as? GifDrawable)?.isRunning).put("mode", DetailAutoplayMode.current())
                            .put("load_key", holder?.ivImage?.getTag(R.id.tag_detail_attachment_image_load_key))
                            .put("request_key", holder?.ivImage?.getTag(R.id.tag_detail_attachment_image_request_key))
                    })
                }
                File(output, "$name-media-diagnostic.json").writeText(diagnostics.toString(2))
                val controllerState = main {
                    val controller: DetailAttachmentPlaybackController = field("mAttachmentPlaybackController")
                    JSONObject().apply {
                        for (key in listOf("mode", "visible", "playing", "disposed", "adapter")) {
                            put(key, controller.javaClass.getDeclaredField(key).apply { isAccessible = true }.get(controller).toString())
                        }
                    }
                }
                File(output, "$name-controller-diagnostic.json").writeText(controllerState.toString(2))
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(output, "$name-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            throw error
        }
        finally {
            results.put(item)
            File(output, "$name-behavior.json").writeText(results.toString(2))
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = DetailActivity::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }.get(activity) as T

    private fun invoke(name: String, vararg args: Any?): Any? = DetailActivity::class.java.declaredMethods
        .first { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(activity, *args)
}
