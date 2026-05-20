package com.ywwynm.everythingdone.model

import android.content.Context
import android.database.Cursor
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.LocaleUtil
import java.util.Calendar

/**
 * Created by ywwynm on 2015/8/4.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Model layer. Related to table "reminders".
 *
 *
 * if you want to change the determined time for goal, you should change it in
 * [getType], [com.ywwynm.everythingdone.database.ReminderDAO.resetGoal],
 */
open class Reminder(
    var id: Long,
    var notifyTime: Long,
    var state: Int,
    var notifyMillis: Long,
    var createTime: Long,
    var updateTime: Long
) {

    constructor(id: Long, notifyTime: Long) : this(
        id, notifyTime, 0, 0, System.currentTimeMillis(), System.currentTimeMillis()
    ) {
        initNotifyMinutes()
    }

    constructor(c: Cursor?) : this(
        c!!.getLong(0), c.getLong(1), c.getInt(2),
        c.getLong(3), c.getLong(4), c.getLong(5)
    )

    open fun initNotifyMinutes() {
        notifyMillis = notifyTime - System.currentTimeMillis()
        notifyMillis += 10 // make it a whole number, not 1999999 but 2000000
    }

    open fun getFinishType(thingFinishTime: Long, isGoal: Boolean): Int {
        if (thingFinishTime == -1L || thingFinishTime == 0L) {
            return -1 // unfinished yet
        }
        if (!isGoal) {
            if (thingFinishTime < notifyTime) {
                return 0
            } else if (thingFinishTime == notifyTime) {
                return 1
            } else {
                return 2
            }
        } else {
            val finishDays = DateTimeUtil.calculateTimeGap(updateTime, thingFinishTime, Calendar.DATE)
            val goalDays   = DateTimeUtil.calculateTimeGap(updateTime, notifyTime, Calendar.DATE)
            if (finishDays < goalDays) {
                return 0
            } else if (finishDays == goalDays) {
                return 1
            } else {
                return 2
            }
        }
    }

    open fun getCelebrationText(context: Context?): String? {
        val part1 = context!!.getString(R.string.celebration_goal_part_1)
        var day = context.getString(R.string.days)
        val part2 = context.getString(R.string.celebration_goal_part_2)
        val part3 = context.getString(R.string.celebration_goal_part_3)
        val gap = DateTimeUtil.calculateTimeGap(updateTime, System.currentTimeMillis(), Calendar.DATE)
        val gapStr: String
        if (gap == 0) {
            gapStr = "<1"
        } else {
            gapStr = gap.toString()
        }

        val isChinese = LocaleUtil.isChinese(context)
        if (gap > 1 && !isChinese) {
            day += "s"
        }
        return part1 + " " + gapStr + " " + day + (if (isChinese) "" else " ") + part2 + "\n" + part3
    }

    companion object {
        const val UNDERWAY: Int = 0
        const val REMINDED: Int = 2
        const val EXPIRED: Int  = 3

        const val GOAL_DAYS: Int   = 28
        const val GOAL_MILLIS: Long = GOAL_DAYS * 24 * 60 * 60 * 1000L

        @JvmStatic
        fun noUpdate(reminder: Reminder?, notifyTime: Long, state: Int): Boolean {
            return reminder!!.notifyTime == notifyTime && reminder.state == state
        }

        @JvmStatic
        fun getType(notifyTime: Long, createTime: Long): Int {
            if (DateTimeUtil.calculateTimeGap(createTime, notifyTime, Calendar.DATE) > GOAL_DAYS) {
                return Thing.GOAL
            } else return Thing.REMINDER
        }

        @JvmStatic
        fun getStateDescription(thingState: Int, reminderState: Int, context: Context?): String? {
            val result: String?
            if (reminderState == REMINDED) {
                result = context!!.getString(R.string.reminder_reminded)
            } else if (reminderState == EXPIRED) {
                result = context!!.getString(R.string.reminder_expired)
            } else {
                if (thingState == Thing.UNDERWAY) {
                    result = ""
                } else if (thingState == Thing.FINISHED) {
                    result = context!!.getString(R.string.reminder_needless)
                } else {
                    result = context!!.getString(R.string.reminder_unavailable)
                }
            }
            return result
        }
    }
}
