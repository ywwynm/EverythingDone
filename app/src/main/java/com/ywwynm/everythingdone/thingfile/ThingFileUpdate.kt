package com.ywwynm.everythingdone.thingfile

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.database.DBHelper
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.helpers.AlarmHelper
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import java.io.File

/** 更新现有记事：附件先准备，内容和提醒相关行在同一事务中替换，不分配新记事 id。 */
object ThingFileUpdate {
    enum class Settings { KEEP_CURRENT, USE_RECEIVED }

    class Prepared(val pending: PendingThing, val content: Thing, val files: List<File>,
        allFiles: List<File> = files) {
        private val stamps = allFiles.map { Triple(it, it.length(), it.lastModified()) }
        var isCommitted = false
            private set
        fun markCommitted() { isCommitted = true }
        /** 尚未用于一次成功更新的文件可以直接清理；成功后交给详情页的撤销历史管理。 */
        fun discard() { if (!isCommitted) files.forEach { it.delete() } }
        fun ensureFilesAvailable() {
            check(stamps.all { (file, size, time) -> file.isFile && file.length() == size && file.lastModified() == time }) {
                "更新前附件已变化"
            }
        }
    }

    /** 包含取消打卡等全部记录，不能使用只返回已完成记录的 DAO 查询作为撤销快照。 */
    class Alarms internal constructor(internal val rows: Map<String, List<ContentValues>>)

    class Written(val before: Thing, val after: Thing,
        internal val oldReminderIds: List<Long>?, val alarms: Alarms?)

    private val tables = listOf("reminders", "habits", "habit_reminders", "habit_records")

    /** 在工作线程调用；沿用普通导入的文件命名、防覆盖和附件外观映射。 */
    fun prepare(pending: PendingThing): Prepared {
        val moved = ThingFileImporter.moveAttachments(pending, System.currentTimeMillis(), 0)
        val content = Thing(pending.thing).apply {
            attachment = moved.attachment
            thingCardAppearance = thingCardAppearance.remapSources(moved.keyMapping)
            detailAttachmentMediaAppearance = detailAttachmentMediaAppearance.remapSources(moved.keyMapping)
        }
        return Prepared(pending, content, moved.files, moved.keyMapping.values.map { File(it.substring(1)) })
    }

    fun merge(current: Thing, incoming: Thing, settings: Settings): Thing = Thing(current).apply {
        title = (if (current.isPrivate()) Thing.PRIVATE_THING_PREFIX else "") + incoming.getTitleToDisplay()
        content = incoming.content
        attachment = incoming.attachment
        setBackground(incoming.getBackground())
        thingCardAppearance = incoming.thingCardAppearance
        detailAttachmentMediaAppearance = incoming.detailAttachmentMediaAppearance
        if (settings == Settings.USE_RECEIVED) type = incoming.type
        updateTime = System.currentTimeMillis()
    }

    fun captureAlarms(context: Context, id: Long): Alarms = withDatabase(context) { db ->
        Alarms(tables.associateWith { table -> readRows(db, table, id) })
    }

    /** 对方只提供定义；新习惯的记录、暂停状态、累计次数均从初始状态开始。 */
    fun receivedAlarms(pending: PendingThing, id: Long): Alarms {
        val rows = tables.associateWith { emptyList<ContentValues>() }.toMutableMap()
        pending.reminder?.let {
            val reminder = Reminder(id, it.notifyTime)
            if (reminder.notifyTime <= System.currentTimeMillis()) reminder.state = Reminder.EXPIRED
            rows["reminders"] = listOf(ContentValues().apply {
                put("id", id)
                put("notify_time", reminder.notifyTime)
                put("state", reminder.state)
                put("notify_millis", reminder.notifyMillis)
                put("create_time", reminder.createTime)
                put("update_time", reminder.updateTime)
            })
        }
        pending.habit?.let {
            val habit = Habit(id, it.type, 0, it.detail, "", "", System.currentTimeMillis(), 0)
            habit.initHabitReminders()
            rows["habits"] = listOf(ContentValues().apply {
                put("id", id)
                put("type", habit.type)
                put("reminded_times", 0)
                put("detail", habit.detail)
                put("record", "")
                put("interval_info", "")
                put("create_time", habit.createTime)
                put("first_time", habit.firstTime)
            })
            rows["habit_reminders"] = habit.habitReminders.orEmpty().mapIndexed { index, reminder ->
                ContentValues().apply {
                    put("id", index.toLong())
                    put("habit_id", id)
                    put("notify_time", reminder!!.notifyTime)
                }
            }
        }
        return Alarms(rows)
    }

    /**
     * [alarms] 为空表示保留本机设置，完全不碰打卡表和闹钟。
     * 非空时也用于撤销／重做。事务外才调整系统闹钟，避免写入失败后旧数据失去提醒。
     */
    fun write(context: Context, desired: Thing, alarms: Alarms?): Written {
        var oldReminderIds = emptyList<Long>()
        val written = withDatabase(context) { db ->
            db.beginTransaction()
            try {
                val before = db.query("things", null, "id=?", arrayOf(desired.id.toString()),
                    null, null, null).use { cursor ->
                    check(cursor.moveToFirst()) { "当前记事已不存在" }
                    Thing(cursor)
                }
                check(before.state == desired.state && before.state != Thing.DELETED &&
                    before.state != Thing.DELETED_FOREVER) { "当前记事的状态已改变" }
                // 身份、文件夹、位置、创建与完成时间以当前数据库为准，不回写陈旧快照。
                val after = Thing(before).apply {
                    type = desired.type
                    title = desired.title
                    content = desired.content
                    attachment = desired.attachment
                    setBackground(desired.getBackground())
                    thingCardAppearance = desired.thingCardAppearance
                    detailAttachmentMediaAppearance = desired.detailAttachmentMediaAppearance
                    updateTime = System.currentTimeMillis()
                }
                val values = ContentValues().apply {
                    put(Def.Database.COLUMN_TYPE_THINGS, after.type)
                    put(Def.Database.COLUMN_TITLE_THINGS, after.title)
                    put(Def.Database.COLUMN_CONTENT_THINGS, after.content)
                    put(Def.Database.COLUMN_ATTACHMENT_THINGS, after.attachment)
                    put(Def.Database.COLUMN_COLOR_THINGS, after.getColor())
                    put(Def.Database.COLUMN_BACKGROUND_THINGS, after.getBackground()!!.toJson())
                    put(Def.Database.COLUMN_THING_CARD_SPAN_MODE_THINGS, after.thingCardSpanMode)
                    put(Def.Database.COLUMN_THING_CARD_IMAGE_PLACEMENT_THINGS, after.thingCardImagePlacement)
                    put(Def.Database.COLUMN_THING_CARD_APPEARANCE_THINGS, after.thingCardAppearance.toJson())
                    put(Def.Database.COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS,
                        after.detailAttachmentMediaAppearance.toJson())
                    put(Def.Database.COLUMN_UPDATE_TIME_THINGS, after.updateTime)
                }
                check(db.update("things", values, "id=?", arrayOf(after.id.toString())) == 1)
                if (alarms != null) {
                    oldReminderIds = readRows(db, "habit_reminders", after.id).map { it.getAsLong("id") }
                    replaceAlarms(db, after.id, alarms)
                }
                db.setTransactionSuccessful()
                Written(before, after, if (alarms != null) oldReminderIds else null,
                    if (alarms != null) Alarms(tables.associateWith { readRows(db, it, after.id) }) else null)
            } finally {
                db.endTransaction()
            }
        }
        return written
    }

    /** 数据提交后单独同步系统副作用；调用方此时必须已经接管附件与撤销历史。 */
    fun synchronizeAlarms(context: Context, written: Written) {
        if (written.oldReminderIds != null) {
            HabitDAO.getInstance(context)!!.refreshIdsAfterContentUpdate()
            AlarmHelper.deleteReminderAlarm(context, written.after.id)
            written.oldReminderIds.forEach { AlarmHelper.deleteHabitReminderAlarm(context, it) }
            schedule(context, written.after)
        }
    }

    private fun replaceAlarms(db: SQLiteDatabase, id: Long, snapshot: Alarms) {
        tables.forEach { db.delete(it, "${ownerColumn(it)}=?", arrayOf(id.toString())) }
        listOf("reminders", "habits").forEach { table ->
            snapshot.rows[table].orEmpty().forEach { original ->
                db.insertOrThrow(table, null, ContentValues(original).apply { put("id", id) })
            }
        }
        // 历史期间别的习惯可能分配过 id，恢复时重新分配并同步重映射记录引用，绝不覆盖其它习惯。
        var nextReminder = maxId(db, "habit_reminders") + 1
        val reminderIds = HashMap<Long, Long>()
        snapshot.rows["habit_reminders"].orEmpty().forEach { original ->
            val newId = nextReminder++
            reminderIds[original.getAsLong("id")] = newId
            db.insertOrThrow("habit_reminders", null, ContentValues(original).apply {
                put("id", newId)
                put("habit_id", id)
            })
        }
        var nextRecord = maxId(db, "habit_records") + 1
        snapshot.rows["habit_records"].orEmpty().forEach { original ->
            db.insertOrThrow("habit_records", null, ContentValues(original).apply {
                put("id", nextRecord++)
                put("habit_id", id)
                reminderIds[getAsLong("habit_reminder_id")]?.let { put("habit_reminder_id", it) }
            })
        }
    }

    private fun schedule(context: Context, thing: Thing) {
        if (thing.state != Thing.UNDERWAY) return
        val rows = captureAlarms(context, thing.id).rows
        rows["reminders"].orEmpty().forEach { row ->
            if (row.getAsInteger("state") == Reminder.UNDERWAY &&
                row.getAsLong("notify_time") > System.currentTimeMillis()) {
                AlarmHelper.setReminderAlarm(context, thing.id, row.getAsLong("notify_time"))
            }
        }
        val habit = rows["habits"].orEmpty().firstOrNull() ?: return
        if (habit.getAsString("interval_info").endsWith(",")) return
        HabitDAO.getInstance(context)!!.updateHabitToLatest(thing.id, false, false)
    }

    private fun maxId(db: SQLiteDatabase, table: String): Long =
        DatabaseUtils.longForQuery(db, "SELECT COALESCE(MAX(id), -1) FROM $table", null)

    private fun ownerColumn(table: String) = if (table == "reminders" || table == "habits") "id" else "habit_id"

    private fun readRows(db: SQLiteDatabase, table: String, id: Long): List<ContentValues> =
        db.query(table, null, "${ownerColumn(table)}=?", arrayOf(id.toString()), null, null, "id").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(ContentValues().also { DatabaseUtils.cursorRowToContentValues(cursor, it) })
            }
        }

    private fun <T> withDatabase(context: Context, block: (SQLiteDatabase) -> T): T =
        DBHelper(context.applicationContext).use { block(it.writableDatabase) }
}
