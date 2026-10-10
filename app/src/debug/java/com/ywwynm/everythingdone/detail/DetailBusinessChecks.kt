package com.ywwynm.everythingdone.detail

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.adapters.CheckListAdapter
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.fragments.DateTimeDialogFragment
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.DetailContentLayout
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.utils.SystemNotificationUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime

/** 业务闭环只操作本轮分配的专用记事；清理同时限定 id、标题前缀和创建时间。 */
internal class DetailBusinessChecks(private val probe: Instrumentation, private val output: File) {
    private val context = probe.targetContext
    private val db = DBHelper(context).writableDatabase
    private val created = linkedMapOf<Long, Long>()
    private val checks = JSONArray()
    private var current: DetailActivity? = null
    private val title = "详情业务回归 · 临时数据"
    private val manifest = File(output.parentFile, "business-fixtures.json")
    private val autoSaveBefore = FrequentSettings.getBoolean(Def.Meta.KEY_AUTO_SAVE_EDITS)

    fun run(externalOnly: Boolean = false) {
        try {
            recoverFixtures()
            FrequentSettings.put(Def.Meta.KEY_AUTO_SAVE_EDITS, false)
            if (externalOnly) { external(); return }
            verify("整条记事完成、恢复、删除、再恢复及完成时间展示") {
                val id = create()
                val original = saved(id).content
                for ((menuId, expected) in listOf(R.id.act_finish to Thing.FINISHED, R.id.act_restore to Thing.UNDERWAY,
                    R.id.act_delete to Thing.DELETED, R.id.act_restore to Thing.UNDERWAY)) {
                    val page = open(id)
                    menu(page, menuId)
                    await { page.isFinishing }
                    await { saved(id).state == expected }
                    val expectedContent = if (expected == Thing.FINISHED)
                        original!!.replace(CheckListHelper.SIGNAL + "0", CheckListHelper.SIGNAL + "1") else original
                    check(saved(id).content == expectedContent)
                    val reopened = open(id)
                    if (expected == Thing.FINISHED) main {
                        check(content(reopened).view<TextView>(R.id.tv_finish_time).visibility == View.VISIBLE)
                        check(content(reopened).view<TextView>(R.id.tv_finish_time).text.isNotEmpty())
                    }
                    close()
                }
            }
            verify("置顶、取消置顶与重新打开") {
                val id = create()
                for (sticky in listOf(true, false)) {
                    val page = open(id); menu(page, R.id.act_sticky)
                    await { page.isFinishing }
                    check((saved(id).location < 0) == sticky)
                    val reopened = open(id)
                    main { check(reopened.findViewById<Toolbar>(R.id.actionbar).menu.findItem(R.id.act_sticky).title ==
                        context.getString(if (sticky) R.string.act_cancel_sticky else R.string.act_sticky_on_top)) }
                    close()
                }
            }
            if (FrequentSettings.getLong(Def.Meta.KEY_ONGOING_THING_ID) == -1L) {
                verify("设置与取消正在进行通知") {
                    val id = create()
                    try {
                        var page = open(id); menu(page, R.id.act_ongoing_thing)
                        await { page.isFinishing }
                        check(FrequentSettings.getLong(Def.Meta.KEY_ONGOING_THING_ID) == id)
                        page = open(id); menu(page, R.id.act_ongoing_thing)
                        await { page.isFinishing }
                        check(FrequentSettings.getLong(Def.Meta.KEY_ONGOING_THING_ID) == -1L)
                    } finally {
                        main {
                            SystemNotificationUtil.cancelThingOngoingNotification(context, id)
                            FrequentSettings.put(Def.Meta.KEY_ONGOING_THING_ID, -1L)
                            context.getSharedPreferences(Def.Meta.PREFERENCES_NAME, 0).edit()
                                .putLong(Def.Meta.KEY_ONGOING_THING_ID, -1L).commit()
                        }
                        close()
                    }
                }
            } else checks.put(JSONObject().put("check", "正在进行通知").put("skipped", "保留用户已有正在进行通知"))
            verify("提醒时间实际编辑、清单草稿、保存重开及完成状态") {
                val id = create()
                var selected = ZonedDateTime.now().plusDays(2).withSecond(0).withNano(0)
                repeat(2) { round ->
                    val page = open(id)
                    val dialog = dateDialog(page)
                    main {
                        val pager: ViewPager = field(dialog, "mVpDateTime"); pager.setCurrentItem(0, false)
                    }
                    await { main { field<Array<EditText?>>(dialog, "mEtsAt").all { it != null } } }
                    main {
                        val inputs: Array<EditText?> = field(dialog, "mEtsAt")
                        listOf(selected.year, selected.monthValue, selected.dayOfMonth, selected.hour, selected.minute)
                            .forEachIndexed { index, value -> inputs[index]!!.setText(value.toString()) }
                        dialog.requireView().findViewById<View>(R.id.tv_confirm_as_bt).performClick()
                    }
                    await { !dialog.isAdded }
                    main {
                        check(page.rhParams.reminderInMillis == selected.toInstant().toEpochMilli())
                        check(page.cbQuickRemind!!.isChecked)
                        content(page).view<EditText>(R.id.et_title).append("【提醒$round】")
                        field<CheckListAdapter>(page, "mCheckListAdapter").getItems()!![0] = CheckListHelper.makeItem('0', 1, "提醒编辑后清单 $round")
                    }
                    save(page)
                    check(saved(id).type == Thing.REMINDER)
                    check(saved(id).title!!.endsWith("【提醒$round】"))
                    check(saved(id).content!!.contains("提醒编辑后清单 $round"))
                    check(ReminderDAO.getInstance(context)!!.getReminderById(id)!!.notifyTime == selected.toInstant().toEpochMilli())
                    val reopened = open(id)
                    main { check(reopened.rhParams.reminderInMillis == selected.toInstant().toEpochMilli()); check(reopened.cbQuickRemind!!.isChecked) }
                    close(); selected = selected.plusDays(1)
                }
                val page = open(id); menu(page, R.id.act_finish); await { page.isFinishing }
                check(saved(id).state == Thing.FINISHED)
            }
            verify("习惯实际编辑保存、完成一次、暂停与恢复") {
                val id = create()
                var page = open(id)
                val dialog = dateDialog(page)
                main { field<ViewPager>(dialog, "mVpDateTime").setCurrentItem(2, false) }
                await { main { field<RecyclerView?>(dialog, "mRvTimeOfDay")?.findViewHolderForAdapterPosition(0) != null } }
                val time = ZonedDateTime.now().plusHours(6)
                main {
                    val list: RecyclerView = field(dialog, "mRvTimeOfDay")
                    val holder = list.findViewHolderForAdapterPosition(0)!!
                    val hour: EditText = field(holder, "etHour"); val minute: EditText = field(holder, "etMinute")
                    hour.setText(time.hour.toString()); minute.setText(time.minute.toString())
                    dialog.requireView().findViewById<View>(R.id.tv_confirm_as_bt).performClick()
                }
                await { !dialog.isAdded }
                val detail = page.rhParams.habitDetail
                check(!detail.isNullOrEmpty())
                save(page)
                check(saved(id).type == Thing.HABIT)
                val dao = HabitDAO.getInstance(context)!!
                check(dao.getHabitById(id)!!.detail == detail)
                page = open(id)
                main { check(page.rhParams.habitDetail == detail); check(page.cbQuickRemind!!.isChecked) }
                val recordsBefore = dao.getHabitRecordsByHabitId(id)!!.size
                menu(page, R.id.act_finish_this_time_habit); await { page.isFinishing }
                check(dao.getHabitRecordsByHabitId(id)!!.size == recordsBefore + 1)
                check(dao.getHabitById(id)!!.record!!.endsWith("1"))
                for (paused in listOf(true, false)) {
                    page = open(id); menu(page, R.id.act_pause_resume_habit)
                    await { page.isFinishing }; check(dao.isPaused(id) == paused)
                    page = open(id)
                    main { check(page.findViewById<Toolbar>(R.id.actionbar).menu.findItem(R.id.act_pause_resume_habit).title ==
                        context.getString(if (paused) R.string.act_resume_habit else R.string.act_pause_habit)) }
                    close()
                }
            }
        } finally {
            if (com.ywwynm.everythingdone.App.getDoingThingId() in created.keys) {
                main { com.ywwynm.everythingdone.helpers.ThingDoingHelper.stopDoing(context,
                    com.ywwynm.everythingdone.model.DoingRecord.STOP_REASON_CANCEL_USER) }
                await { com.ywwynm.everythingdone.App.getDoingThingId() == -1L }
            }
            close()
            main {
                for ((id, time) in created) {
                    val thing = saved(id)
                    check(thing.title!!.startsWith(title) && thing.createTime == time)
                    ReminderDAO.getInstance(context)!!.delete(id)
                    HabitDAO.getInstance(context)!!.deleteHabit(id)
                    db.delete("doing_records", "thing_id=?", arrayOf(id.toString()))
                    db.delete("things", "id=? AND create_time=?", arrayOf(id.toString(), time.toString()))
                }
                FrequentSettings.put(Def.Meta.KEY_AUTO_SAVE_EDITS, autoSaveBefore)
            }
            File(output, "business.json").writeText(checks.toString(2))
            if (created.isNotEmpty() && created.keys.none { ThingDAO.getInstance(context)!!.getThingById(it) != null }) manifest.delete()
        }
    }

    private fun recoverFixtures() {
        if (!manifest.exists()) return
        val previous = JSONArray(manifest.readText())
        for (index in 0 until previous.length()) {
            val entry = previous.getJSONObject(index)
            val id = entry.getLong("id")
            val thing = ThingDAO.getInstance(context)!!.getThingById(id) ?: continue
            check(thing.createTime == entry.getLong("time") && thing.title!!.startsWith(title))
            check(com.ywwynm.everythingdone.App.getDoingThingId() != id) { "崩溃遗留的专用计时仍在运行" }
            ReminderDAO.getInstance(context)!!.delete(id)
            HabitDAO.getInstance(context)!!.deleteHabit(id)
            db.delete("doing_records", "thing_id=?", arrayOf(id.toString()))
            db.delete("things", "id=? AND create_time=?", arrayOf(id.toString(), thing.createTime.toString()))
        }
        manifest.delete()
    }

    private fun external() {
        verify("记事文件实际导出、ZIP 内容和成功反馈") {
            val id = create()
            val page = open(id)
            val directory = File(context.getExternalFilesDir(null), "export")
            val existing = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
            menu(page, R.id.act_export)
            var alert: com.ywwynm.everythingdone.fragments.AlertDialogFragment? = null
            try {
                await {
                    alert = page.supportFragmentManager.findFragmentByTag(com.ywwynm.everythingdone.fragments.AlertDialogFragment.TAG)
                        as? com.ywwynm.everythingdone.fragments.AlertDialogFragment
                    alert?.view != null
                }
                main { check(alert!!.requireView().findViewById<TextView>(R.id.tv_title_alert).text == context.getString(R.string.export_success_title)) }
                val generated = directory.listFiles()!!.filter { it.name !in existing }
                check(generated.size == 1)
                val file = generated.single()
                check(file.name.startsWith(title.take(10)) && file.extension == "zip")
                java.util.zip.ZipFile(file).use { zip ->
                    val entries = zip.entries().asSequence().filter { it.name.endsWith(".txt") }.toList()
                    check(entries.size == 1)
                    val text = zip.getInputStream(entries.single()).bufferedReader().use { it.readText() }
                    check(text.contains(title) && text.contains("业务验证清单 0") && text.contains("业务验证清单 60"))
                }
            } finally {
                main { alert?.dismiss() }
                directory.listFiles()?.filter { it.name !in existing && it.name.startsWith(title.take(10)) && it.extension == "zip" }
                    ?.forEach { check(it.delete()) }
                close()
            }
        }
        if (com.ywwynm.everythingdone.App.getDoingThingId() != -1L) {
            checks.put(JSONObject().put("check", "实际计时开始与返回").put("skipped", "保留用户正在进行的计时"))
            return
        }
        for (rapid in listOf(true, false)) verify(if (rapid) "从详情开始后立即取消计时，无延迟崩溃且自动保存草稿" else "实际运行计时后取消并重新打开详情") {
            val id = create()
            val page = open(id)
            val draft = "$title【计时返回草稿】"
            FrequentSettings.put(Def.Meta.KEY_AUTO_SAVE_EDITS, true)
            main { content(page).view<EditText>(R.id.et_title).setText(draft) }
            val startMonitor = probe.addMonitor(com.ywwynm.everythingdone.activities.StartDoingActivity::class.java.name, null, false)
            val doingMonitor = probe.addMonitor(com.ywwynm.everythingdone.activities.DoingActivity::class.java.name, null, false)
            var started: com.ywwynm.everythingdone.activities.StartDoingActivity? = null
            var doing: com.ywwynm.everythingdone.activities.DoingActivity? = null
            try {
                main { page.findViewById<View>(R.id.fl_start_doing_as_bt).performClick() }
                started = probe.waitForMonitorWithTimeout(startMonitor, 7000) as? com.ywwynm.everythingdone.activities.StartDoingActivity
                check(started != null)
                var chooser: com.ywwynm.everythingdone.fragments.ChooserDialogFragment? = null
                await {
                    chooser = started!!.supportFragmentManager.findFragmentByTag(com.ywwynm.everythingdone.fragments.ChooserDialogFragment.TAG)
                        as? com.ywwynm.everythingdone.fragments.ChooserDialogFragment
                    chooser?.view != null
                }
                main { chooser!!.requireView().findViewById<View>(R.id.tv_confirm_as_bt_fragment_chooser).performClick() }
                doing = probe.waitForMonitorWithTimeout(doingMonitor, 7000) as? com.ywwynm.everythingdone.activities.DoingActivity
                check(doing != null)
                await { field<Any?>(doing!!, "mDoingBinder") != null && doing!!.findViewById<View>(R.id.fab_cancel_doing)?.isShown == true }
                check(com.ywwynm.everythingdone.App.getDoingThingId() == id)
                if (!rapid) SystemClock.sleep(1500)
                main { doing!!.findViewById<View>(R.id.fab_cancel_doing).performClick() }
                var alert: com.ywwynm.everythingdone.fragments.AlertDialogFragment? = null
                await {
                    alert = doing!!.supportFragmentManager.findFragmentByTag(com.ywwynm.everythingdone.fragments.AlertDialogFragment.TAG)
                        as? com.ywwynm.everythingdone.fragments.AlertDialogFragment
                    alert?.view != null
                }
                main { alert!!.requireView().findViewById<View>(R.id.tv_confirm_as_bt_alert).performClick() }
                try { await { com.ywwynm.everythingdone.App.getDoingThingId() == -1L && doing!!.isFinishing } }
                catch (failure: Throwable) {
                    File(output, "doing-return.json").writeText(main { JSONObject().put("doing_id", com.ywwynm.everythingdone.App.getDoingThingId())
                        .put("page_finishing", page.isFinishing).put("page_destroyed", page.isDestroyed).put("page_focus", page.hasWindowFocus())
                        .put("doing_finishing", doing!!.isFinishing).put("start_finishing", started!!.isFinishing).toString(2) })
                    throw failure
                }
                SystemClock.sleep(1500) // 超过原 1 秒延迟启动时限，核对快速取消后不会异步崩溃。
                // 原业务收到开始计时广播后会关闭详情；取消后重新打开核对，而非要求已销毁页面重新获焦。
                check(page.isFinishing)
                val reopened = open(id)
                main { check(content(reopened).view<EditText>(R.id.et_title).text.toString() == draft) }
                db.rawQuery("SELECT COUNT(*) FROM doing_records WHERE thing_id=?", arrayOf(id.toString())).use {
                    check(it.moveToFirst()); check(it.getInt(0) == 1)
                }
                check(saved(id).state == Thing.UNDERWAY)
            } finally {
                probe.removeMonitor(startMonitor); probe.removeMonitor(doingMonitor)
                if (com.ywwynm.everythingdone.App.getDoingThingId() == id) {
                    main { com.ywwynm.everythingdone.helpers.ThingDoingHelper.stopDoing(context,
                        com.ywwynm.everythingdone.model.DoingRecord.STOP_REASON_CANCEL_USER) }
                    await { com.ywwynm.everythingdone.App.getDoingThingId() == -1L }
                }
                main { doing?.finish(); started?.finish() }
                FrequentSettings.put(Def.Meta.KEY_AUTO_SAVE_EDITS, false)
            }
        }
    }

    private fun create(): Long {
        val manager = ThingManager.getInstance(context)!!
        val id = manager.getHeaderId()
        val time = System.currentTimeMillis()
        val bg = ThingBackground.pure(Color.rgb(45, 114, 130))
        val items = (0..60).map { CheckListHelper.makeItem('0', 1, "业务验证清单 $it") }
        val thing = Thing(id, Thing.NOTE, Thing.UNDERWAY, bg.representativeColor(), title,
            CheckListHelper.toCheckListStr(items), "", id, time, time, 0L)
        // 与应用新建相同，使用头部预留编号并同步推进数据库和内存头部，禁止占用 MAX(id)+1。
        main {
            ThingDAO.getInstance(context)!!.create(thing, false, false)
            ThingManager::class.java.getDeclaredMethod("updateHeader", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(manager, 1)
        }
        created[id] = time
        manifest.writeText(JSONArray(created.map { (key, value) -> JSONObject().put("id", key).put("time", value) }).toString())
        return id
    }

    private fun saved(id: Long) = ThingDAO.getInstance(context)!!.getThingById(id)!!
    private fun open(id: Long): DetailActivity {
        close()
        val page = probe.startActivitySync(DetailActivity.getOpenIntentForUpdate(context, "detail-business-regression", id, -1)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DetailActivity
        current = page
        await { page.hasWindowFocus() && !page.findViewById<View>(R.id.sv_detail).isLayoutRequested }
        return page
    }
    private fun close() { main { current?.finish() }; probe.waitForIdleSync(); current = null }
    private fun menu(page: DetailActivity, id: Int) = main {
        val item = page.findViewById<Toolbar>(R.id.actionbar).menu.findItem(id)
        check(item != null && item.isVisible) { "缺少业务菜单 $id" }; page.onOptionsItemSelected(item)
    }
    private fun dateDialog(page: DetailActivity): DateTimeDialogFragment {
        val dialog: DateTimeDialogFragment = field(page, "mDateTimeDialogFragment")
        main { dialog.show(page.supportFragmentManager, DateTimeDialogFragment.TAG) }
        await { dialog.view != null && dialog.dialog?.window?.decorView?.hasWindowFocus() == true }
        return dialog
    }
    private fun save(page: DetailActivity) {
        main { field<View>(page, "mIbBack").performClick() }
        await { page.isFinishing }
        probe.waitForIdleSync()
    }
    private fun content(page: DetailActivity): DetailContentLayout = field(page, "mDetailContent")
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T
    private fun await(test: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 7000
        while (SystemClock.uptimeMillis() < deadline) { if (main(test)) return; SystemClock.sleep(40) }
        error("等待业务界面超时")
    }
    private fun verify(label: String, block: () -> Unit) {
        val item = JSONObject().put("check", label); checks.put(item)
        try { block(); item.put("passed", true) }
        catch (error: Throwable) { item.put("passed", false).put("error", error.stackTraceToString()); throw error }
        finally { File(output, "business.json").writeText(checks.toString(2)) }
    }
    private fun <T> main(block: () -> T): T {
        // 调用方的轮询已经位于主线程时，直接求值，避免 Instrumentation 的嵌套同步调用。
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        probe.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
