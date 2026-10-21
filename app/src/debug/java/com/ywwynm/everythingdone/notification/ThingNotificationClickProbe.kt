@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.notification

import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.AuthenticationActivity
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.activities.NoticeableNotificationActivity
import com.ywwynm.everythingdone.activities.SettingsActivity
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.fragments.ChooserDialogFragment
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.receivers.HabitReceiver
import com.ywwynm.everythingdone.receivers.ReminderReceiver
import com.ywwynm.everythingdone.utils.SystemNotificationUtil
import com.ywwynm.everythingdone.views.particledismiss.ParticleDismissOverlay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 只操作带本轮时间戳的虚构记事；设置逐键恢复，校验其余业务数据未改变。 */
class ThingNotificationClickProbe : Instrumentation() {
    private var args = Bundle()
    private val fixtureTime = System.currentTimeMillis()
    private val ids = mutableListOf<Long>()
    private val posted = mutableSetOf<Int>()
    private val samples = JSONArray()
    private val activities = mutableListOf<Activity>()
    @Volatile private var resumed: Activity? = null
    private lateinit var output: File
    private val prefs by lazy { targetContext.getSharedPreferences(Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE) }
    private val db by lazy { DBHelper(targetContext).writableDatabase }
    private val nm by lazy { targetContext.getSystemService(NotificationManager::class.java) }
    private var failures = 0

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun callActivityOnResume(activity: Activity) {
        super.callActivityOnResume(activity)
        resumed = activity
        if (!activities.contains(activity)) activities.add(activity)
    }

    override fun onStart() {
        val run = args.getString("run_id") ?: "candidate"
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        output = File(targetContext.getExternalFilesDir(null), "notification-click/$run").apply { mkdirs() }
        val keys = listOf(Def.Meta.KEY_NOTIFICATION_OPEN_NOTICEABLE, Def.Meta.KEY_DIALOG_PARTICLE_ANIMATION,
            Def.Meta.KEY_CLOSE_NOTIFICATION_LATER)
        val saved = prefs.all.filterKeys { it in keys }
        // 首次初始化会重建应用内部的 header 行，应先初始化再记录业务数据基线。
        App.getThingAndPosition(targetContext, -1L, -1)
        val before = digest()
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val result = JSONObject()
        try {
            prefs.edit().remove(Def.Meta.KEY_NOTIFICATION_OPEN_NOTICEABLE)
                .putInt(Def.Meta.KEY_DIALOG_PARTICLE_ANIMATION, 0)
                .putBoolean(Def.Meta.KEY_CLOSE_NOTIFICATION_LATER, false).commit()
            val note = fixture(Thing.NOTE)
            val reminder = fixture(Thing.REMINDER)
            val habit = fixture(Thing.HABIT)
            val privateNote = fixture(Thing.NOTE, private = true)
            val hrId = maxOf(nextId("habit_reminders"), nextId("things")) + 100
            db.insertOrThrow("habit_reminders", null, ContentValues().apply {
                put("id", hrId); put("habit_id", habit); put("notify_time", fixtureTime)
            })

            checkCase("default-detail") { route(notification(reminder), reminder.toInt(), false, false) }
            val alreadyPosted = notification(note)
            nm.notify(note.toInt(), alreadyPosted); posted += note.toInt()
            checkCase("settings-confirm-cancel-persistence") { settings() }
            // 定向补测允许跳过设置用例；通知仍在切换设置之前创建。
            prefs.edit().putBoolean(Def.Meta.KEY_NOTIFICATION_OPEN_NOTICEABLE, true).commit()
            checkCase("existing-notification-shade-particle") {
                prefs.edit().putInt(Def.Meta.KEY_DIALOG_PARTICLE_ANIMATION, 3).commit()
                route(alreadyPosted, note.toInt(), true, true, shade = true)
            }
            checkCase("reminder-without-particle") {
                prefs.edit().putInt(Def.Meta.KEY_DIALOG_PARTICLE_ANIMATION, 0).commit()
                route(notification(reminder), reminder.toInt(), true, false)
            }
            checkCase("habit-event-context") {
                val n = notification(habit, hrId)
                val other = notification(habit, hrId + 1)
                check(n.contentIntent != other.contentIntent) { "不同习惯提醒共用了点击入口" }
                val activity = route(n, hrId.toInt(), true, false)
                check(activity.intent.getLongExtra(Def.Communication.KEY_ID, -1) == hrId)
                check(activity.intent.getLongExtra(Def.Communication.KEY_TIME, -1) == fixtureTime)
                check(texts((activity as NoticeableNotificationActivity).findViewById(R.id.ll_root_noticeable_notification))
                    .contains(TITLE))
            }
            checkCase("ongoing-note-preserved") {
                val n = notification(note, ongoing = true)
                val activity = route(n, note.toInt(), true, false)
                close(activity)
                check(nm.activeNotifications.any { it.id == note.toInt() }) { "关闭弹窗移除了常驻通知" }
            }
            checkCase("ongoing-habit") {
                val activity = route(notification(habit, ongoing = true), habit.toInt(), true, false)
                val icon = activity.findViewById<View>(R.id.iv_1_noticeable_notification_as_bt)
                check(icon.contentDescription == activity.getString(R.string.act_finish_once_habit))
            }
            checkCase("private-content-hidden") {
                val activity = route(notification(privateNote), privateNote.toInt(), true, false)
                check(!texts(activity.findViewById(R.id.ll_root_noticeable_notification)).contains(SECRET))
            }
            checkCase("widget-view-unchanged") {
                resumed = null
                targetContext.startActivity(AuthenticationActivity.getOpenIntent(
                    targetContext, "AppWidgetHelper", note, -1, Def.Communication.AUTHENTICATE_ACTION_VIEW,
                    targetContext.getString(R.string.check_private_thing)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                await { resumed is DetailActivity }
            }
            checkCase("switch-back-existing-intent") {
                prefs.edit().putBoolean(Def.Meta.KEY_NOTIFICATION_OPEN_NOTICEABLE, false).commit()
                route(alreadyPosted, note.toInt(), false, false)
            }
            checkCase("stale-thing-exits") {
                val stale = fixture(Thing.NOTE)
                val n = notification(stale)
                db.delete("things", "id=? AND create_time=?", arrayOf("$stale", "$fixtureTime"))
                resumed = null
                n.contentIntent.send()
                await { main { activities.filterIsInstance<AuthenticationActivity>().all { it.isDestroyed } } }
                check(resumed !is NoticeableNotificationActivity && resumed !is DetailActivity)
            }
        } catch (failure: Throwable) {
            failures++
            result.put("error", failure.stackTraceToString())
        } finally {
            closeAll()
            posted.forEach(nm::cancel)
            ids.forEach { id ->
                db.delete("habit_reminders", "habit_id=?", arrayOf("$id"))
                db.delete("habit_records", "habit_id=?", arrayOf("$id"))
                db.delete("habits", "id=? AND create_time=?", arrayOf("$id", "$fixtureTime"))
                db.delete("reminders", "id=? AND create_time=?", arrayOf("$id", "$fixtureTime"))
                db.delete("things", "id=? AND create_time=?", arrayOf("$id", "$fixtureTime"))
            }
            val editor = prefs.edit()
            keys.forEach { editor.remove(it) }
            saved.forEach { (key, value) -> when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
            } }
            editor.commit()
            val unchanged = before == digest()
            if (!unchanged) failures++
            result.put("samples", samples).put("failures", failures)
                .put("original_data_unchanged", unchanged).put("fixtures_cleaned", ids.size)
                .put("settings_restored", keys.all { prefs.all[it] == saved[it] })
            File(output, "result.json").writeText(result.toString(2))
            finish(if (failures == 0) 0 else 1, Bundle().apply {
                putString("result", "failures=$failures samples=${samples.length()} path=$output/result.json")
            })
        }
    }

    private fun checkCase(name: String, block: () -> Unit) {
        val selected = args.getString("cases")?.split(',')
        if (selected != null && name !in selected) return
        try {
            block()
            samples.put(JSONObject().put("case", name).put("passed", true))
        } catch (failure: Throwable) {
            failures++
            screenshot("failure-$name")
            samples.put(JSONObject().put("case", name).put("passed", false).put("error", failure.stackTraceToString()))
        } finally {
            ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("cmd statusbar collapse"))
                .use { it.readBytes() }
            closeAll()
        }
        File(output, "progress.json").writeText(samples.toString(2))
    }

    private fun settings() {
        val activity = startActivitySync(Intent(targetContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        fun chooser(): ChooserDialogFragment {
            main { activity.findViewById<ScrollView>(R.id.sv_settings).scrollTo(0, 0) }
            tap(activity.findViewById(R.id.rl_notification_open_as_bt))
            await { main { activity.supportFragmentManager.findFragmentByTag(ChooserDialogFragment.TAG)?.view?.isShown == true } }
            return main { activity.supportFragmentManager.findFragmentByTag(ChooserDialogFragment.TAG) as ChooserDialogFragment }
        }
        val initial = chooser()
        check(main { initial.getPickedIndex() } == 0)
        tap(main { initial.dialog!!.findViewById<RecyclerView>(R.id.rv_fragment_chooser)
            .findViewHolderForAdapterPosition(1)!!.itemView })
        tap(main { initial.dialog!!.findViewById(R.id.tv_confirm_as_bt_fragment_chooser) })
        await { prefs.getBoolean(Def.Meta.KEY_NOTIFICATION_OPEN_NOTICEABLE, false) }
        await { main { activity.supportFragmentManager.findFragmentByTag(ChooserDialogFragment.TAG) == null } }
        check(main { activity.findViewById<TextView>(R.id.tv_notification_open_value).text.toString() } ==
            activity.getString(R.string.notification_open_noticeable))
        screenshot("settings-selected")
        val cancel = chooser()
        check(main { cancel.getPickedIndex() } == 1)
        screenshot("settings-chooser")
        tap(main { cancel.dialog!!.findViewById<RecyclerView>(R.id.rv_fragment_chooser)
            .findViewHolderForAdapterPosition(0)!!.itemView })
        tap(main { cancel.dialog!!.findViewById(R.id.tv_cancel_as_bt_fragment_chooser) })
        await { main { activity.supportFragmentManager.findFragmentByTag(ChooserDialogFragment.TAG) == null } }
        check(prefs.getBoolean(Def.Meta.KEY_NOTIFICATION_OPEN_NOTICEABLE, false))
        close(activity)
        val reopened = startActivitySync(Intent(targetContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        check(main { reopened.findViewById<TextView>(R.id.tv_notification_open_value).text.toString() } ==
            reopened.getString(R.string.notification_open_noticeable))
    }

    private fun notification(id: Long, hrId: Long = -1L, ongoing: Boolean = false): Notification {
        val thing = ThingDAO.getInstance(targetContext)!!.getThingById(id)!!
        return SystemNotificationUtil.newGeneralNotificationBuilder(targetContext,
            if (ongoing) App::class.java.name else if (hrId >= 0) HabitReceiver.TAG else ReminderReceiver.TAG,
            id, -1, thing, false, hrId, if (hrId >= 0) fixtureTime else -1L, ongoing)
            .setContentTitle(TITLE).setContentText("仅用于通知点击验证")
            .setSilent(true).setOngoing(ongoing).setAutoCancel(!ongoing).build()
    }

    private fun route(n: Notification, id: Int, noticeable: Boolean, particle: Boolean, shade: Boolean = false): Activity {
        resumed = null
        posted += id
        nm.notify(id, n)
        await { nm.activeNotifications.any { it.id == id } }
        if (shade) {
            posted.filter { it != id }.forEach(nm::cancel)
            ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("cmd statusbar expand-notifications"))
                .use { it.readBytes() }
            var target: AccessibilityNodeInfo? = null
            await {
                target = uiAutomation.windows.asSequence().mapNotNull { it.root }
                    .mapNotNull { findNotification(it) }.firstOrNull()
                target != null
            }
            // 等通知栏展开完成，再重新读取层级坐标；部分系统的无障碍点击只播放反馈。
            uiAutomation.waitForIdle(300, 5_000)
            target = uiAutomation.windows.asSequence().mapNotNull { it.root }
                .mapNotNull { findNotification(it) }.firstOrNull()
            val bounds = Rect().also { checkNotNull(target).getBoundsInScreen(it) }
            File(output, "shade-target.json").writeText(JSONObject()
                .put("id", target!!.viewIdResourceName).put("bounds", bounds.toShortString()).toString(2))
            tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        } else n.contentIntent.send()
        await { if (noticeable) resumed is NoticeableNotificationActivity else resumed is DetailActivity }
        val activity = resumed!!
        if (noticeable) {
            var seen = false
            await {
                main {
                    seen = seen || ParticleDismissOverlay.activeIn(activity).any { !it.isDisappearance && it.isAttachedToWindow }
                    val dialog = (activity as NoticeableNotificationActivity).supportFragmentManager
                        .findFragmentByTag("activity-content-dialog") as? DialogFragment
                    dialog?.dialog?.window?.attributes?.alpha == 1f &&
                        activity.findViewById<View>(R.id.rv_thing_noticeable_notification)?.isShown == true &&
                        ParticleDismissOverlay.activeIn(activity).isEmpty()
                }
            }
            check(seen == particle) { "进入粒子动画：期望=$particle 实际=$seen" }
            if (shade) screenshot("notification-particle-finished")
        }
        return activity
    }

    private fun fixture(type: Int, private: Boolean = false): Long {
        val id = nextId("things")
        db.insertOrThrow("things", null, ContentValues().apply {
            put("id", id); put("type", type); put("state", Thing.UNDERWAY)
            put("color", 0xff226655.toInt()); put("title", (if (private) Thing.PRIVATE_THING_PREFIX else "") + TITLE)
            put("content", if (private) SECRET else "通知打开方式验证\n".repeat(24)); put("attachment", "")
            put("location", id); put("create_time", fixtureTime); put("update_time", fixtureTime); put("finish_time", 0)
        })
        ids += id
        if (type == Thing.REMINDER) db.insertOrThrow("reminders", null, ContentValues().apply {
            put("id", id); put("notify_time", fixtureTime); put("state", 2); put("notify_millis", 0)
            put("create_time", fixtureTime); put("update_time", fixtureTime)
        })
        if (type == Thing.HABIT) db.insertOrThrow("habits", null, ContentValues().apply {
            put("id", id); put("type", Calendar.DATE); put("reminded_times", 1); put("detail", "9:0")
            put("record", "0"); put("interval_info", ""); put("create_time", fixtureTime); put("first_time", fixtureTime)
        })
        return id
    }

    private fun nextId(table: String): Long = db.rawQuery("SELECT COALESCE(MAX(id),0)+1 FROM $table", null)
        .use { it.moveToFirst(); it.getLong(0) }

    private fun digest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (table in listOf("things", "thing_folders", "reminders", "habits", "habit_reminders", "habit_records")) {
            db.rawQuery("SELECT * FROM $table ORDER BY id", null).use { c ->
                while (c.moveToNext()) for (index in 0 until c.columnCount) {
                    digest.update((c.getString(index) ?: "<null>").toByteArray())
                    digest.update(0)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun close(activity: Activity) {
        main { if (!activity.isDestroyed) activity.finish() }
        await { activity.isDestroyed }
    }

    private fun closeAll() {
        main { activities.toList() }.asReversed().forEach { if (!it.isDestroyed) close(it) }
    }

    private fun await(test: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (!test()) { check(SystemClock.uptimeMillis() < end) { "等待界面超时" }; SystemClock.sleep(16) }
    }

    private fun tap(view: View) {
        await { main { view.isShown && view.hasWindowFocus() } }
        val bounds = main {
            Rect().also {
                check(view.getLocalVisibleRect(it))
                val location = IntArray(2).also(view::getLocationOnScreen)
                it.offset(location[0], location[1])
            }
        }
        tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private fun tapAt(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to now, MotionEvent.ACTION_UP to now + 64)) {
            val event = MotionEvent.obtain(now, time, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        waitForIdleSync()
    }

    private fun texts(view: View?): String = when (view) {
        is TextView -> if (view.isShown) view.text.toString() else ""
        is ViewGroup -> (0 until view.childCount).joinToString("\n") { texts(view.getChildAt(it)) }
        else -> ""
    }

    private fun findNotification(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.text?.contains(TITLE) == true || node.contentDescription?.contains(TITLE) == true) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findNotification(it)?.let { found -> return found } }
        }
        return null
    }

    private fun screenshot(name: String) {
        uiAutomation.waitForIdle(300, 5_000)
        resumed?.takeUnless { it.isDestroyed }?.let { activity ->
            val frames = CountDownLatch(1)
            main { activity.window.decorView.postOnAnimation { activity.window.decorView.postOnAnimation { frames.countDown() } } }
            frames.await(3, TimeUnit.SECONDS)
        }
        uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    companion object {
        private const val TITLE = "通知打开验证"
        private const val SECRET = "不应显示的测试私密正文"
    }
}
