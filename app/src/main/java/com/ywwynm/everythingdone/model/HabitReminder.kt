package com.ywwynm.everythingdone.model

import android.database.Cursor

/**
 * Created by ywwynm on 2016/1/29.
 * model layer. related to table "habit_reminders".
 */
open class HabitReminder(
    var id: Long,
    var habitId: Long,
    var notifyTime: Long
) {

    constructor(c: Cursor?) : this(c!!.getLong(0), c.getLong(1), c.getLong(2))
}
