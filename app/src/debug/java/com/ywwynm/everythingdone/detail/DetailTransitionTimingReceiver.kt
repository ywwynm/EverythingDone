package com.ywwynm.everythingdone.detail

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.activity.OnBackPressedCallback
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.activities.ThingsActivity
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.helpers.ThingPrivacyResolver
import com.ywwynm.everythingdone.interaction.UiInteractions
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.views.ThingDetailTransition
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** 普通应用进程的旁观计时，不启动 Instrumentation；需 adb shell 的 DUMP 权限显式启用。 */
class DetailTransitionTimingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as Application
        callbacks?.let(app::unregisterActivityLifecycleCallbacks)
        callbacks = null
        ThingDetailTransition.diagnosticObserver = null
        val run = intent.getStringExtra("run") ?: return
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val directory = File(context.getExternalFilesDir(null), "detail-transition/$run").apply { mkdirs() }
        callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is DetailActivity) {
                    // 普通进程测量同样只读：不允许因测试打开/退出更新原有记事。
                    DetailActivity::class.java.getDeclaredField("dontSaveAfterOnPause")
                        .apply { isAccessible = true }.setBoolean(activity, true)
                }
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                if (activity is DetailActivity) activity.onBackPressedDispatcher.addCallback(activity,
                    object : OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() { activity.finish() }
                    })
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }.also(app::registerActivityLifecycleCallbacks)
        var start = 0L
        var stages = JSONArray()
        var sequence = 0
        ThingDetailTransition.diagnosticObserver = { stage ->
            val now = SystemClock.elapsedRealtimeNanos()
            if (stage == "click") { start = now; stages = JSONArray() }
            if (start != 0L) {
                stages.put(JSONObject().put("stage", stage).put("ms", (now - start) / 1_000_000.0))
                if (stage == "finished") {
                    val text = stages.toString(2)
                    val file = File(directory, "click-${sequence++}.json")
                    writer.execute { file.writeText(text) }
                    start = 0L
                }
            }
        }
        // 可选：只滚动到当前投影内指定的公开原记事，输出真实边界，随后由 adb 注入触摸。
        // 不改投影、不修改记录、不注入私密认证。
        if (intent.hasExtra("thing_id")) {
            val id = intent.getLongExtra("thing_id", -1)
            val thing = DBHelper(context).readableDatabase.rawQuery(
                "SELECT * FROM things WHERE id=? AND type<>-1", arrayOf(id.toString())).use {
                if (it.moveToFirst()) Thing(it) else null
            } ?: return
            if (id == App.getDoingThingId() || ThingPrivacyResolver.isEffectivelyPrivate(context, thing)) return
            val sessions = UiInteractions::class.java.getDeclaredField("sessions")
                .apply { isAccessible = true }.get(UiInteractions) as Map<*, *>
            val home = sessions.keys.filterIsInstance<ThingsActivity>().firstOrNull { it.hasWindowFocus() } ?: return
            var owner: Class<*>? = home.javaClass
            while (owner != null && owner.declaredFields.none { it.name == "mRecyclerView" }) owner = owner.superclass
            val list = owner!!.getDeclaredField("mRecyclerView")
                .apply { isAccessible = true }.get(home) as RecyclerView
            val position = ThingManager.getInstance(context)!!.getListPositionForThingId(id)
            if (position < 1) return
            (list.layoutManager as StaggeredGridLayoutManager).scrollToPositionWithOffset(position, 120)
            val deadline = SystemClock.uptimeMillis() + 5000
            val ready = object : Runnable {
                override fun run() {
                    val item = list.findViewHolderForAdapterPosition(position)?.itemView
                    val card = item?.findViewById<View>(R.id.cv_thing)
                    val bounds = Rect()
                    if (!list.isLayoutRequested && item?.getTag(R.id.tag_thing_card_bound_stable_id) == id &&
                        card?.getGlobalVisibleRect(bounds) == true) {
                        File(directory, "source.json").writeText(JSONObject()
                            .put("id", id).put("x", bounds.centerX()).put("y", bounds.centerY()).toString())
                    } else if (SystemClock.uptimeMillis() < deadline) list.postDelayed(this, 50)
                }
            }
            list.postDelayed(ready, 500)
        }
    }

    companion object {
        private var callbacks: Application.ActivityLifecycleCallbacks? = null
        private val writer = Executors.newSingleThreadExecutor()
    }
}
