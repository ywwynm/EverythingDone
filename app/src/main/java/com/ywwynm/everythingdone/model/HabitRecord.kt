package com.ywwynm.everythingdone.model

import android.database.Cursor
import androidx.annotation.IntDef
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.WeekFields

/**
 * Created by ywwynm on 2016/2/11.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * model layer for table "habit_records"
 */
open class HabitRecord(
    var id: Long,
    var habitId: Long,
    var habitReminderId: Long,
    var recordTime: Long,
    var recordYear: Int,
    var recordMonth: Int,
    var recordWeek: Int,
    var recordDay: Int,
    var type: Int
) {

    constructor(habitId: Long, habitReminderId: Long) : this(
        0L, habitId, habitReminderId, System.currentTimeMillis(),
        0, 0, 0, 0, TYPE_FINISHED
    ) {
        val dt = Instant.ofEpochMilli(recordTime).atZone(ZoneId.systemDefault())
        recordYear = dt.getYear()
        recordMonth = dt.getMonthValue()
        recordWeek = dt.get(WeekFields.ISO.weekOfWeekBasedYear())
        recordDay = dt.getDayOfMonth()
    }

    constructor(habitId: Long, habitReminderId: Long, recordTime: Long) : this(
        0L, habitId, habitReminderId, recordTime,
        0, 0, 0, 0, TYPE_FINISHED
    ) {
        val dt = Instant.ofEpochMilli(recordTime).atZone(ZoneId.systemDefault())
        recordYear = dt.getYear()
        recordMonth = dt.getMonthValue()
        recordWeek = dt.get(WeekFields.ISO.weekOfWeekBasedYear())
        recordDay = dt.getDayOfMonth()
    }

    constructor(c: Cursor?) : this(
        c!!.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3),
        c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7), c.getInt(8)
    )

    @IntDef(0, 1, 2, 3)
    @Retention(AnnotationRetention.SOURCE)
    annotation class Type

    companion object {
        const val TYPE_FINISHED: Int = 0
        const val TYPE_CANCEL_FINISHED: Int = 1
        const val TYPE_FAKE_FINISHED: Int = 2
        const val TYPE_FAKE_CANCEL_FINISHED: Int = 3
    }
}
