package com.ywwynm.everythingdone.detail

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.adapters.CheckListAdapter
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.DetailCropPreviewLoader
import com.ywwynm.everythingdone.fragments.MediaCropAppearanceDialogFragment
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.AppearanceUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** 旧新版本共用的重建差分探针；只记录事实，不把已有的状态丢失误标成通过。 */
class DetailRecreationProbe : Instrumentation() {
    private var args = Bundle()
    private var current: DetailActivity? = null
    private val ids = mutableListOf<Long>()
    private lateinit var output: File
    private val fixtureTitle = "详情重建回归 · 临时数据"

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }

    override fun onStart() {
        // Instrumentation 线程可能先于 Application.onCreate 完成；先等主线程初始化。
        waitForIdleSync()
        val run = args.getString("run_id") ?: "recreation"
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        output = File(targetContext.getExternalFilesDir(null), "detail-layout-regression/$run").apply { mkdirs() }
        val report = JSONObject()
        val records = JSONArray()
        val previous = FrequentSettings.getBoolean(Def.Meta.KEY_AUTO_SAVE_EDITS)
        try {
            if (args.getString("test_case") == "preview_recreation") previewRecreation(records)
            else if (args.getString("suite") == "theme") themeChecks(records)
            else for (save in listOf(false, true)) for (checklist in listOf(false, true)) {
                FrequentSettings.put(Def.Meta.KEY_AUTO_SAVE_EDITS, save)
                val id = fixture(checklist)
                val activity = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext,
                    "detail-recreation", id, -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
                current = activity
                idle(activity)
                val title = field<EditText>(activity, "mEtTitle")
                main {
                    title.append("【未保存标题】")
                    invoke(activity, "changeBackground", ThingBackground.pure(Color.rgb(252, 224, 138)))
                }
                val editor = if (checklist) {
                    val adapter = field<CheckListAdapter>(activity, "mCheckListAdapter")
                    reveal(activity, adapter, 8)
                    val row = main { holder(activity, adapter, 8) }
                    row.et!!
                } else field(activity, "mEtContent")
                main {
                    editor.requestFocus(); editor.append("【未保存内容】"); editor.setSelection(2, 5)
                    title.setSelection(3, 7)
                }
                idle(activity)
                val before = state(activity, checklist)
                val monitor = addMonitor(DetailActivity::class.java.name, null, false)
                try {
                    main { activity.recreate() }
                    val recreated = waitForMonitorWithTimeout(monitor, 12000) as? DetailActivity
                        ?: error("Activity 没有完成重建")
                    current = recreated
                    idle(recreated)
                    if (checklist) reveal(recreated, field(recreated, "mCheckListAdapter"), 8)
                    val after = state(recreated, checklist)
                    val changes = JSONArray(before.keys().asSequence().filter { before[it].toString() != after[it].toString() }.toList())
                    records.put(JSONObject().put("auto_save", save).put("checklist", checklist)
                        .put("before", before).put("after", after).put("changed_fields", changes))
                    uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(output, "save-$save-checklist-$checklist.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                } finally { removeMonitor(monitor) }
                main { current?.finish() }; waitForIdleSync(); current = null
            }
            report.put("completed", true)
        } catch (failure: Throwable) { report.put("error", failure.stackTraceToString()) }
        finally {
            main { current?.finish(); FrequentSettings.put(Def.Meta.KEY_AUTO_SAVE_EDITS, previous) }
            waitForIdleSync()
            val db = DBHelper(targetContext).writableDatabase
            ids.forEach { db.delete("things", "id=? AND create_time=?", arrayOf(it.toString(), "1780272000000")) }
            report.put("records", records).put("fixture_ids", JSONArray(ids))
            File(output, "result.json").writeText(report.toString(2))
            finish(if (report.has("error")) 1 else 0, Bundle().apply { putString("error", report.optString("error")) })
        }
    }

    private fun previewRecreation(records: JSONArray) {
        val prefs = targetContext.getSharedPreferences(Def.Meta.PREFERENCES_NAME, 0)
        val key = Def.Meta.KEY_DIALOG_PARTICLE_ANIMATION
        val hadMode = prefs.contains(key)
        val oldMode = prefs.getInt(key, 3)
        fun await(condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 12000
            while (!main(condition)) { check(SystemClock.uptimeMillis() < end); SystemClock.sleep(30) }
        }
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun dialog(activity: DetailActivity) = activity.supportFragmentManager.findFragmentByTag(MediaCropAppearanceDialogFragment.TAG)
            as? MediaCropAppearanceDialogFragment
        fun confirm(view: View) = descendants(view).filterIsInstance<TextView>().first {
            it.text.toString() == targetContext.getString(R.string.confirm) && it.isClickable
        }
        try {
            for (video in listOf(false, true)) for (mode in listOf(0, 3)) {
                prefs.edit().putInt(key, mode).commit()
                val file = File(output, if (video) "restore.mp4" else "restore.jpg")
                if (video) targetContext.assets.open("detail-regression/video.mp4").use { input -> file.outputStream().use(input::copyTo) }
                else {
                    val bitmap = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(60, 160, 180)) }
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
                }
                val id = fixture(false)
                val background = if (mode == 3) ThingBackground.gradient(
                    Color.rgb(36, 65, 100), Color.rgb(196, 55, 108), ThingBackground.Orientation.LT_RB
                ) else ThingBackground.pure(Color.rgb(35, 105, 95))
                DBHelper(targetContext).writableDatabase.update("things", ContentValues().apply {
                    put("color", background.representativeColor()); put("background", background.toJson())
                    put("attachment", com.ywwynm.everythingdone.helpers.AttachmentHelper.toAttachmentStr(
                        listOf("${if (video) 1 else 0}${file.path}"), emptyList()))
                }, "id=?", arrayOf(id.toString()))
                val activity = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext,
                    "preview-recreation", id, -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
                current = activity; idle(activity)
                val loader = DetailCropPreviewLoader()
                main { DetailActivity::class.java.getDeclaredField("mDetailCropPreviewLoader").apply { isAccessible = true }.set(activity, loader) }
                val executor = loader.javaClass.getDeclaredField("executor").apply { isAccessible = true }.get(loader) as java.util.concurrent.ExecutorService
                val gate = java.util.concurrent.CountDownLatch(1)
                executor.submit { check(gate.await(20, java.util.concurrent.TimeUnit.SECONDS)) }
                val monitor = addMonitor(DetailActivity::class.java.name, null, false)
                try {
                    main { invoke(activity, "showDetailAttachmentMediaAppearanceEditor", 0) }
                    await { dialog(activity)?.view?.width?.let { it > 0 } == true }
                    val metadata = main {
                        val dialog = dialog(activity)!!
                        check(!confirm(dialog.requireView()).isEnabled)
                        val attempted = dialog.dialog!!.javaClass.getDeclaredField("condenseAttempted").apply { isAccessible = true }
                        check(attempted.getBoolean(dialog.dialog) == (mode == 3))
                        dialog.detailPreviewMetadata!!
                    }
                    // 给粒子出现完成留出条件等待，再取一次加载中的真实画面。
                    await { com.ywwynm.everythingdone.fragments.BaseDialogFragment::class.java.getDeclaredField("appearanceSettled")
                        .apply { isAccessible = true }.getBoolean(dialog(activity)) }
                    uiAutomation.takeScreenshot()?.let { image ->
                        File(output, "video-$video-mode-$mode-loading.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                    }
                    main { activity.recreate() }
                    val recreated = waitForMonitorWithTimeout(monitor, 12000) as? DetailActivity ?: error("预览宿主没有重建")
                    current = recreated
                    gate.countDown()
                    await { dialog(recreated)?.view?.let { it.width > 0 && confirm(it).isEnabled } == true }
                    main {
                        val restored = dialog(recreated)!!
                        check(restored.detailPreviewMetadata == metadata)
                        check(!restored.dialog!!.javaClass.getDeclaredField("condenseAttempted").apply { isAccessible = true }.getBoolean(restored.dialog))
                        restored.dismiss()
                    }
                    await { dialog(recreated) == null }
                    records.put(JSONObject().put("video", video).put("particle_mode", mode)
                        .put("background", background.toJson()).put("restored_without_replaying_appearance", true))
                } finally { gate.countDown(); removeMonitor(monitor) }
                main { current?.finish() }; waitForIdleSync(); current = null
                file.delete()
            }
        } finally {
            prefs.edit().apply { if (hadMode) putInt(key, oldMode) else remove(key) }.commit()
        }
    }

    private fun themeChecks(records: JSONArray) {
        val follow = FrequentSettings.getBoolean(Def.Meta.KEY_FOLLOW_SYSTEM_DARK_MODE)
        val force = FrequentSettings.getBoolean(Def.Meta.KEY_FORCE_DARK_MODE)
        val mode = AppCompatDelegate.getDefaultNightMode()
        val systemMode = shell("cmd uimode night").substringAfter("Night mode: ").trim()
        require(systemMode in listOf("yes", "no", "auto", "custom")) { "未知系统明暗模式：$systemMode" }
        File(output, "system-night-before.txt").writeText(systemMode)
        try {
            main {
                FrequentSettings.put(Def.Meta.KEY_FOLLOW_SYSTEM_DARK_MODE, true)
                FrequentSettings.put(Def.Meta.KEY_FORCE_DARK_MODE, false)
                AppearanceUtil.applyDefaultNightMode()
            }
            for (checklist in listOf(false, true)) {
                val activity = startActivitySync(DetailActivity.getOpenIntentForUpdate(targetContext,
                    "detail-recreation", fixture(checklist), -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
                current = activity; idle(activity)
                if (checklist) reveal(activity, field(activity, "mCheckListAdapter"), 8)
                for (dark in listOf(true, false)) {
                    val editor = if (checklist) main { holder(activity, field(activity, "mCheckListAdapter"), 8).et!! }
                        else field<EditText>(activity, "mEtContent")
                    main { editor.requestFocus(); editor.setSelection(2, 5) }
                    val before = state(activity, checklist)
                    shell("cmd uimode night ${if (dark) "yes" else "no"}")
                    val deadline = SystemClock.uptimeMillis() + 6000
                    while (main { activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK } !=
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO) {
                        check(SystemClock.uptimeMillis() < deadline) { "详情没有跟随真实系统主题" }
                        SystemClock.sleep(30)
                    }
                    idle(activity)
                    for (lightBackground in listOf(false, true)) {
                        main { invoke(activity, "changeBackground", ThingBackground.pure(
                            if (lightBackground) Color.rgb(252, 224, 138) else Color.rgb(35, 105, 95))) }
                        idle(activity)
                        val name = "checklist-$checklist-night-$dark-lightbg-$lightBackground"
                        val record = state(activity, checklist).put("case", name).put("before", before)
                        main {
                            val currentEditor = if (checklist) holder(activity, field(activity, "mCheckListAdapter"), 8).et!! else editor
                            record.put("editor", appearance(currentEditor, "$name-editor"))
                            record.put("title", appearance(field(activity, "mEtTitle"), "$name-title"))
                            if (checklist) record.put("pressed_background", drawable(
                                holder(activity, field(activity, "mCheckListAdapter"), 8).ivState!!.background,
                                "$name-pressed", true))
                        }
                        records.put(record)
                        uiAutomation.takeScreenshot()?.let { bitmap ->
                            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            bitmap.recycle()
                        }
                    }
                }
                main { activity.finish() }; waitForIdleSync(); current = null
            }
        } finally {
            shell("cmd uimode night $systemMode")
            main {
                FrequentSettings.put(Def.Meta.KEY_FOLLOW_SYSTEM_DARK_MODE, follow)
                FrequentSettings.put(Def.Meta.KEY_FORCE_DARK_MODE, force)
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }
    }

    private fun appearance(editor: EditText, name: String) = JSONObject()
        .put("foreground", editor.currentTextColor).put("hint", editor.currentHintTextColor)
        .put("link", editor.linkTextColors.getColorForState(editor.drawableState, editor.linkTextColors.defaultColor))
        .put("highlight", editor.highlightColor).put("selection", "${editor.selectionStart}:${editor.selectionEnd}")
        .put("focused", editor.isFocused)
        .put("cursor", drawable(editor.textCursorDrawable, "$name-cursor"))
        .put("handle_left", drawable(editor.textSelectHandleLeft, "$name-left"))
        .put("handle_right", drawable(editor.textSelectHandleRight, "$name-right"))
        .put("handle_center", drawable(editor.textSelectHandle, "$name-center"))

    private fun drawable(source: android.graphics.drawable.Drawable?, name: String, pressed: Boolean = false): String {
        if (source == null) return "none"
        val copy = source.constantState?.newDrawable()?.mutate() ?: error("不能独立采集 ${source.javaClass}")
        val size = (36 * targetContext.resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        copy.setBounds(0, 0, size, size)
        copy.state = if (pressed) intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed) else intArrayOf(android.R.attr.state_enabled)
        copy.setHotspot(size / 2f, size / 2f)
        copy.jumpToCurrentState()
        copy.draw(Canvas(bitmap))
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        File(output, "$name.png").writeBytes(bytes); bitmap.recycle()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }

    private fun fixture(checklist: Boolean): Long {
        val db = DBHelper(targetContext).writableDatabase
        db.beginTransaction()
        try {
        val id = db.rawQuery("SELECT MAX(id)+1 FROM things", null).use { it.moveToFirst(); it.getLong(0) }
        val content = if (checklist) CheckListHelper.toCheckListStr((0 until 35).map {
            CheckListHelper.makeItem(if (it < 24) '0' else '1', 1, "重建清单第 $it 项")
        }) else "重建正文第一段\n" + "多行正文用于重建后检查内容、选择区与草稿。\n".repeat(24)
        db.insertOrThrow("things", null, ContentValues().apply {
            put("id", id); put("type", Thing.NOTE); put("state", Thing.UNDERWAY)
            put("color", Color.rgb(35, 105, 95)); put("title", fixtureTitle); put("content", content)
            put("attachment", ""); put("location", id); put("create_time", 1780272000000L)
            put("update_time", 1780272000000L); put("finish_time", 0)
        })
        ids += id
        db.setTransactionSuccessful()
        return id
        } finally { db.endTransaction() }
    }

    private fun state(activity: DetailActivity, checklist: Boolean): JSONObject = main {
        val thing = invoke(activity, "buildShareSnapshot") as Thing
        val title = field<EditText>(activity, "mEtTitle")
        val editor = if (checklist) holder(activity, field(activity, "mCheckListAdapter"), 8).et!!
            else field<EditText>(activity, "mEtContent")
        JSONObject().put("title", thing.title).put("content", thing.content)
            .put("background", thing.getBackground()!!.toJson())
            .put("title_selection", "${title.selectionStart}:${title.selectionEnd}")
            .put("editor_selection", "${editor.selectionStart}:${editor.selectionEnd}")
            .put("focused_editor", editor.hasFocus())
            .put("checklist_expanded", if (checklist) field<CheckListAdapter>(activity, "mCheckListAdapter").isExpanded() else false)
    }

    private fun reveal(activity: DetailActivity, adapter: CheckListAdapter, position: Int) {
        main {
            when (val root = activity.findViewById<View>(R.id.sv_detail)) {
                is RecyclerView -> root.scrollToPosition((root.adapter as ConcatAdapter).adapters
                    .takeWhile { it !== adapter }.sumOf { it.itemCount } + position)
                is NestedScrollView -> {
                    val list = activity.findViewById<RecyclerView>(R.id.rv_check_list)
                    root.scrollTo(0, list.top + list.findViewHolderForAdapterPosition(position)!!.itemView.top)
                }
            }
        }
        idle(activity)
    }

    private fun holder(activity: DetailActivity, adapter: CheckListAdapter, position: Int): CheckListAdapter.EditTextHolder {
        val root = activity.findViewById<View>(R.id.sv_detail)
        val list = if (root is RecyclerView) root else activity.findViewById<RecyclerView>(R.id.rv_check_list)
        return (0 until list.childCount).map { list.getChildViewHolder(list.getChildAt(it)) }
            .first { it.bindingAdapter === adapter && it.bindingAdapterPosition == position } as CheckListAdapter.EditTextHolder
    }

    private fun idle(activity: DetailActivity) {
        waitForIdleSync()
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (main { activity.window.decorView.width > 0 && !activity.window.decorView.isLayoutRequested }) return
            SystemClock.sleep(30)
        }
        error("重建后的界面没有稳定布局")
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    @Suppress("UNCHECKED_CAST") private fun <T> field(activity: DetailActivity, name: String): T =
        DetailActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
    private fun invoke(activity: DetailActivity, name: String, vararg args: Any?): Any? =
        DetailActivity::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)
}
