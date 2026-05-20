package com.ywwynm.everythingdone.model

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.utils.DateTimeUtil

/**
 * Created by ywwynm on 2016/7/1.
 * params of reminder and habit
 */
open class ReminderHabitParams() {

    var reminderInMillis: Long = -1
    var reminderAfterTime: IntArray? = null
    var habitType: Int = -1
    var habitDetail: String? = null

    constructor(anotherParams: ReminderHabitParams) : this() {
        reminderInMillis = anotherParams.reminderInMillis
        if (anotherParams.reminderAfterTime != null) {
            reminderAfterTime = IntArray(2)
            System.arraycopy(anotherParams.reminderAfterTime!!, 0,
                    reminderAfterTime!!, 0, reminderAfterTime!!.size)
        } else {
            reminderAfterTime = null
        }
        habitType = anotherParams.habitType
        habitDetail = anotherParams.habitDetail
    }

    open fun getReminderTime(): Long {
        if (reminderInMillis != -1L) {
            return reminderInMillis
        }
        if (reminderAfterTime != null) {
            return DateTimeUtil.getActualTimeAfterSomeTime(reminderAfterTime)
        }
        return -1
    }

    open fun reset() {
        reminderInMillis = -1
        reminderAfterTime = null
        habitType = -1
        habitDetail = null
    }

    open fun getDateTimeStr(): String? {
        val context = App.getApp()
        if (reminderInMillis != -1L) {
            return DateTimeUtil.getDateTimeStrAt(reminderInMillis, context, false)
        } else if (reminderAfterTime != null) {
            return DateTimeUtil.getDateTimeStrAfter(
                    reminderAfterTime!![0], reminderAfterTime!![1], context)
        } else {
            return DateTimeUtil.getDateTimeStrRec(context, habitType, habitDetail)
        }
    }
}
