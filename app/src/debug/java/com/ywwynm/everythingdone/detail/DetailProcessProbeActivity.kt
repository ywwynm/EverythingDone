package com.ywwynm.everythingdone.detail

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.view.View
import android.widget.EditText
import androidx.core.view.doOnPreDraw
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.adapters.CheckListAdapter
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
import org.json.JSONObject
import java.io.File
import java.util.WeakHashMap

/** 独立测试任务：宿主脚本在真正停止后杀掉进程，随后恢复原任务；不在 instrumentation 内模拟。 */
class DetailProcessProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = File(getExternalFilesDir(null), "detail-process-probe.json")
        when (intent.getStringExtra("phase")) {
            "resume" -> {
                val saved = JSONObject(config.readText())
                (getSystemService(ACTIVITY_SERVICE) as ActivityManager).appTasks
                    .first { it.taskInfo.taskId == saved.getInt("task_id") }.moveToFront()
                finishAndRemoveTask()
            }
            "cleanup" -> {
                if (config.isFile) {
                    val saved = JSONObject(config.readText())
                    FrequentSettings.put(Def.Meta.KEY_AUTO_SAVE_EDITS, saved.getBoolean("previous_auto_save"))
                    DBHelper(this).writableDatabase.delete("things", "id=? AND create_time=?",
                        arrayOf(saved.getLong("id").toString(), "1780272000000"))
                    config.delete()
                    File(filesDir, "detail-process-probe.png").delete()
                    (getSystemService(ACTIVITY_SERVICE) as ActivityManager).appTasks
                        .firstOrNull { it.taskInfo.taskId == saved.optInt("task_id") }?.finishAndRemoveTask()
                }
                finishAndRemoveTask()
            }
            else -> {
                val run = intent.getStringExtra("run_id")!!
                require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                check(!config.exists()) { "先清理上一轮进程恢复夹具" }
                val directory = File(getExternalFilesDir(null), "detail-layout-regression/$run").apply { mkdirs() }
                val checklist = intent.getBooleanExtra("checklist", false)
                val save = intent.getBooleanExtra("auto_save", false)
                val db = DBHelper(this).writableDatabase
                val id = db.rawQuery("SELECT MAX(id)+1 FROM things", null).use { it.moveToFirst(); it.getLong(0) }
                val media = File(filesDir, "detail-process-probe.png")
                val bitmap = Bitmap.createBitmap(240, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
                media.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                val source = "0${media.absolutePath}"
                val content = if (checklist) CheckListHelper.toCheckListStr((0 until 40).map { "01进程恢复清单 $it" })
                    else "进程恢复正文\n".repeat(36)
                db.insertOrThrow("things", null, ContentValues().apply {
                    put("id", id); put("type", Thing.NOTE); put("state", Thing.UNDERWAY)
                    put("title", "详情进程恢复 · 临时数据"); put("content", content)
                    put("attachment", AttachmentHelper.SIGNAL + source); put("color", Color.rgb(35, 105, 95))
                    put("location", id); put("create_time", 1780272000000L); put("update_time", 1780272000000L); put("finish_time", 0)
                })
                config.writeText(JSONObject().put("id", id).put("run_id", run).put("phase", "prepare")
                    .put("previous_auto_save", FrequentSettings.getBoolean(Def.Meta.KEY_AUTO_SAVE_EDITS))
                    .put("auto_save", save).put("checklist", checklist).put("source", source).toString())
                FrequentSettings.put(Def.Meta.KEY_AUTO_SAVE_EDITS, save)
                startActivity(DetailActivity.getOpenIntentForUpdate(this, "detail-process", id, -1))
                finish()
            }
        }
    }
}

/** 调试进程每次启动时注册；仅在专用配置文件和明确夹具 id 同时匹配时记录。 */
class DetailProcessObserver : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val restored = WeakHashMap<Activity, Boolean>()
    override fun onCreate(): Boolean {
        (context!!.applicationContext as Application).registerActivityLifecycleCallbacks(this)
        return true
    }
    private fun config() = File(context!!.getExternalFilesDir(null), "detail-process-probe.json")
    private fun read(activity: Activity): JSONObject? {
        if (activity !is DetailActivity || !config().isFile) return null
        return JSONObject(config().readText()).takeIf { it.getLong("id") == activity.currentThingId() }
    }
    private fun output(saved: JSONObject) = File(context!!.getExternalFilesDir(null),
        "detail-layout-regression/${saved.getString("run_id")}")
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { restored[activity] = savedInstanceState != null }
    override fun onActivityResumed(activity: Activity) {
        val saved = read(activity) ?: return
        activity as DetailActivity
        activity.window.decorView.doOnPreDraw { root -> root.post {
            try {
                if (saved.getString("phase") == "prepare") {
                    val title: EditText = field(activity, "mEtTitle")
                    title.append("【未保存标题】"); title.setSelection(2, 5)
                    if (!saved.getBoolean("checklist")) {
                        val body: EditText = field(activity, "mEtContent")
                        body.append("【未保存正文】"); body.requestFocus(); body.setSelection(3, 7)
                    } else {
                        val adapter: CheckListAdapter = field(activity, "mCheckListAdapter")
                        adapter.getItems()!![8] = adapter.getItems()!![8] + "【未保存清单】"
                        adapter.notifyItemChanged(8)
                    }
                    invoke(activity, "changeBackground", ThingBackground.pure(Color.rgb(252, 224, 138)))
                    val appearance: DetailAttachmentMediaAppearance = field(activity, "mDetailAttachmentMediaAppearance")
                    val changed = appearance.withSource(saved.getString("source"), DetailAttachmentMediaAppearance.SourceAppearance(
                        fullSpanEnabled = true, presentations = mapOf("fullSpan" to DetailAttachmentMediaAppearance.MediaPresentationAppearance(targetAspectRatio = 0.75))))
                    DetailActivity::class.java.getDeclaredField("mDetailAttachmentMediaAppearance").apply { isAccessible = true }.set(activity, changed)
                    invoke(activity, "refreshImageAttachmentLayout", true)
                    saved.put("pid", Process.myPid()).put("task_id", activity.taskId).put("phase", "armed")
                    config().writeText(saved.toString())
                    root.postDelayed({
                        val before = state(activity).put("config", saved)
                        if (!saved.getBoolean("checklist") && (!before.getBoolean("body_focused") || before.getString("body_selection") != "3:7")) {
                            File(output(saved), "error.txt").writeText("改变附件高度后正文焦点/选区未保留：$before")
                        } else File(output(saved), "before.json").writeText(before.toString(2))
                    }, 300)
                } else if (saved.getString("phase") == "armed" && saved.getInt("pid") != Process.myPid()) {
                    File(output(saved), "after.json").writeText(state(activity)
                        .put("restored_bundle", restored[activity]).put("pid", Process.myPid()).toString(2))
                    saved.put("phase", "restored"); config().writeText(saved.toString())
                }
            } catch (failure: Throwable) { File(output(saved), "error.txt").writeText(failure.stackTraceToString()) }
        } }
    }
    private fun state(activity: DetailActivity): JSONObject {
        val thing = invoke(activity, "buildShareSnapshot") as Thing
        val title: EditText = field(activity, "mEtTitle")
        val body: EditText = field(activity, "mEtContent")
        return JSONObject().put("title", thing.title).put("content", thing.content)
            .put("background", thing.getBackground()!!.toJson()).put("appearance", thing.detailAttachmentMediaAppearance.toJson())
            .put("title_selection", "${title.selectionStart}:${title.selectionEnd}")
            .put("body_selection", "${body.selectionStart}:${body.selectionEnd}").put("body_focused", body.isFocused)
            .put("focused_view", activity.currentFocus?.id?.let { activity.resources.getResourceEntryName(it) } ?: "none")
    }
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {
        read(activity)?.takeIf { it.getString("phase") == "armed" }?.let {
            File(output(it), "saved-state.json").writeText(JSONObject().put("pid", Process.myPid())
                .put("keys", state.keySet().joinToString()).put("editor", state(activity as DetailActivity))
                .put("fixed_focus", state.getBundle("detail_fixed_views")?.getInt("focus")).toString())
        }
    }
    override fun onActivityStopped(activity: Activity) { read(activity)?.let { File(output(it), "stopped.txt").writeText(Process.myPid().toString()) } }
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityDestroyed(activity: Activity) { restored.remove(activity) }
    @Suppress("UNCHECKED_CAST") private fun <T> field(activity: DetailActivity, name: String): T =
        DetailActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
    private fun invoke(activity: DetailActivity, name: String, vararg args: Any?): Any? =
        DetailActivity::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
}
