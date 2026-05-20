package com.ywwynm.everythingdone.helpers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.util.Pair
import android.text.TextUtils
import android.widget.Toast

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DoingActivity
import com.ywwynm.everythingdone.activities.StartDoingActivity
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.model.DoingRecord
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.services.DoingService
import com.ywwynm.everythingdone.utils.DateTimeUtil

import java.util.ArrayList
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * Created by ywwynm on 2016/11/22.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A helper class to restore and get thing's doing strategy
 */
open class ThingDoingHelper(context: Context?, thing: Thing?) {

    private var mContext: Context? = context
    private var mThing: Thing? = thing

    private var mSpStartDoing: SharedPreferences? = context!!.getSharedPreferences(
            Def.Meta.DOING_STRATEGY_NAME, Context.MODE_PRIVATE)
    private var mSpSettings: SharedPreferences? = context!!.getSharedPreferences(
            Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)

    open fun startDoing(timeInMillis: Long, @DoingService.StartType startType: Int, hrTime: Long,
                        outsideActivity: Boolean) {
        var hr: Long = hrTime
        if (mThing == null) {
            return
        }

        if (hr == -1L) hr = calculateHrTimeForHabit()

        App.setDoingThingId(mThing!!.id)
        val serviceIntent: Intent = DoingService.getOpenIntent(
                mContext, mThing, System.currentTimeMillis(), timeInMillis, startType, hr)!!
        mContext!!.startForegroundService(serviceIntent)

        val activityIntent: Intent = DoingActivity.getOpenIntent(mContext, false)!!
        if (outsideActivity || mContext !is Activity) {
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        mContext!!.startActivity(activityIntent)

        RemoteActionHelper.doingOrCancel(mContext, mThing)
    }

    private fun calculateHrTimeForHabit(): Long {
        var hrTime: Long = -1
        if (mThing!!.type == Thing.HABIT) {
            val habit: Habit? = HabitDAO.getInstance(mContext)!!.getHabitById(mThing!!.id)
            if (habit != null) {
                val remindedTimes: Int = habit.remindedTimes
                val recordedTimes: Int = habit.record!!.length
                if (remindedTimes == recordedTimes) {
                    // user want to do this thing for upcoming habit reminder.
                    hrTime = habit.getMinHabitReminderTime()
                } else if (remindedTimes > recordedTimes) {
                    // habit reminder is notified but user hasn't finished it yet.
                    val maxTime: Long = habit.getFinalHabitReminder()!!.notifyTime
                    hrTime = DateTimeUtil.getHabitReminderTime(habit.type, maxTime, -1)
                } else { // remindedTimes < recordedTimes
                    // user finished habit some times in advance, now he decided to enlarge
                    // the advantage
                    hrTime = habit.getMinHabitReminderTime()
                }
            }
        }
        return hrTime
    }

    open fun tryToOpenStartDoingActivityUser() {
        tryToOpenStartDoingActivityUser(null)
    }

    /**
     * Phase 8: open StartDoingActivity carrying the caller's pending accent
     * (a ThingBackground) so the resulting dialog's title / confirm respect
     * any colour picked-but-not-yet-saved in DetailActivity. `null` falls
     * back to the saved `mThing.getBackground()`.
     */
    open fun tryToOpenStartDoingActivityUser(
            accent: com.ywwynm.everythingdone.model.ThingBackground?) {
        val hrTime: Long = calculateHrTimeForHabit()
        val bg: com.ywwynm.everythingdone.model.ThingBackground? =
                if (accent != null) accent else mThing!!.getBackground()
        mContext!!.startActivity(StartDoingActivity.getOpenIntent(
                mContext, mThing!!.id, -1, bg,
                DoingService.START_TYPE_USER, hrTime))
    }

    open fun startDoingAlarm(timeInMillis: Long, hrTime: Long) {
        startDoing(timeInMillis, DoingService.START_TYPE_ALARM, hrTime, false)
    }

    open fun startDoingAuto(shouldEndTime: Long, hrTime: Long) {
        Toast.makeText(mContext, R.string.auto_start_doing_start, Toast.LENGTH_LONG).show()
        startDoing(getAutoStartDoingTime(shouldEndTime), DoingService.START_TYPE_AUTO, hrTime, true)
    }

    open fun startDoingUser(timeInMillis: Long, hrTime: Long) {
        startDoing(timeInMillis, DoingService.START_TYPE_USER, hrTime, false)
    }

    /**
     * Get auto start doing strategy for a thing with given id
     * @return auto start doing strategy for this thing, should be one of:
     *      [AUTO_START_DOING_STRATEGY_FOLLOW_GENERAL],
     *      [AUTO_START_DOING_STRATEGY_DISABLED],
     *      [AUTO_START_DOING_STRATEGY_ENABLED].
     */
    open fun getAutoStartDoingStrategy(): Int {
        val key: String = mThing!!.id.toString() + "_" + KEY_INDEX_AUTO_START_DOING
        return mSpStartDoing!!.getInt(key, AUTO_START_DOING_STRATEGY_FOLLOW_GENERAL)
    }

    open fun setAutoStartDoingStrategy(strategy: Int) {
        val key: String = mThing!!.id.toString() + "_" + KEY_INDEX_AUTO_START_DOING
        mSpStartDoing!!.edit().putInt(key, strategy).apply()
    }

    /**
     * Judge if user should start doing the thing automatically when its alarm ring.
     * This method will consider both general settings and unique settings for that thing.
     * @return `true` if user should start doing the thing when its alarm ring.
     *         `false` otherwise.
     */
    open fun shouldAutoStartDoing(): Boolean {
        val strategy: Int = getAutoStartDoingStrategy()
        if (strategy == AUTO_START_DOING_STRATEGY_FOLLOW_GENERAL) {
            val sysStrategy: Int = mSpSettings!!.getInt(Def.Meta.KEY_AUTO_START_DOING,
                    SYS_AUTO_START_DOING_STRATEGY_DISABLED)
            if (sysStrategy == SYS_AUTO_START_DOING_STRATEGY_DISABLED) {
                return false
            } else if (sysStrategy == SYS_AUTO_START_DOING_STRATEGY_ALL) {
                // suppose that this method will only be called for Reminder or Habit
                return true
            } else {
                if (mThing == null) {
                    return false
                }
                @Thing.Type val thingType: Int = mThing!!.type
                if (thingType == Thing.REMINDER && sysStrategy == SYS_AUTO_START_DOING_STRATEGY_REMINDER) {
                    return true
                } else if (thingType == Thing.HABIT && sysStrategy == SYS_AUTO_START_DOING_STRATEGY_HABIT) {
                    return true
                } else return false
            }
        } else if (strategy == AUTO_START_DOING_STRATEGY_ENABLED) {
            return true
        } else { // AUTO_START_DOING_STRATEGY_DISABLED
            return false
        }
    }

    open fun getAutoStartDoingDesc(): String? {
        val strategy: Int = getAutoStartDoingStrategy()
        if (strategy == AUTO_START_DOING_STRATEGY_FOLLOW_GENERAL) {
            return getAutoStartDoingFollowGeneralStr()
        } else if (strategy == AUTO_START_DOING_STRATEGY_ENABLED) {
            return mContext!!.getString(R.string.enabled)
        } else {
            return mContext!!.getString(R.string.disabled)
        }
    }


    /**
     * Get auto strict mode strategy for the thing with given id
     * @return auto start doing strategy for this thing, should be one of:
     *      [AUTO_STRICT_MODE_STRATEGY_FOLLOW_GENERAL],
     *      [AUTO_STRICT_MODE_STRATEGY_DISABLED],
     *      [AUTO_STRICT_MODE_STRATEGY_ENABLED].
     */
    open fun getAutoStrictModeStrategy(): Int {
        val key: String = mThing!!.id.toString() + "_" + KEY_INDEX_AUTO_STRICT_MODE
        return mSpStartDoing!!.getInt(key, AUTO_STRICT_MODE_STRATEGY_FOLLOW_GENERAL)
    }

    open fun setAutoStrictModeStrategy(strategy: Int) {
        val key: String = mThing!!.id.toString() + "_" + KEY_INDEX_AUTO_STRICT_MODE
        mSpStartDoing!!.edit().putInt(key, strategy).apply()
    }

    /**
     * Judge if strict mode should be turned on automatically when user starts doing the thing.
     * This method will consider both general settings and unique settings for that thing.
     * @return `true` if strict mode should be turned on automatically when user starts doing
     *         the thing. `false` otherwise.
     */
    open fun shouldAutoStrictMode(): Boolean {
        val strategy: Int = getAutoStrictModeStrategy()
        if (strategy == AUTO_STRICT_MODE_STRATEGY_FOLLOW_GENERAL) {
            val sysStrategy: Int = mSpSettings!!.getInt(Def.Meta.KEY_AUTO_STRICT_MODE,
                    SYS_AUTO_STRICT_MODE_STRATEGY_DISABLED)
            if (sysStrategy == SYS_AUTO_STRICT_MODE_STRATEGY_DISABLED) {
                return false
            } else if (sysStrategy == SYS_AUTO_STRICT_MODE_STRATEGY_ALL) {
                // suppose that this method will only be called for Reminder or Habit
                return true
            } else {
                if (mThing == null) {
                    return false
                }
                @Thing.Type val thingType: Int = mThing!!.type
                if (thingType == Thing.REMINDER && sysStrategy == SYS_AUTO_STRICT_MODE_STRATEGY_REMINDER) {
                    return true
                } else if (thingType == Thing.HABIT && sysStrategy == SYS_AUTO_STRICT_MODE_STRATEGY_HABIT) {
                    return true
                } else return false
            }
        } else if (strategy == AUTO_STRICT_MODE_STRATEGY_ENABLED) {
            return true
        } else { // AUTO_STRICT_MODE_STRATEGY_DISABLED
            return false
        }
    }

    open fun getAutoStrictModeDesc(): String? {
        val strategy: Int = getAutoStrictModeStrategy()
        if (strategy == AUTO_STRICT_MODE_STRATEGY_FOLLOW_GENERAL) {
            return getAutoStrictModeFollowGeneralStr()
        } else if (strategy == AUTO_STRICT_MODE_STRATEGY_ENABLED) {
            return mContext!!.getString(R.string.enabled)
        } else {
            return mContext!!.getString(R.string.disabled)
        }
    }


    open fun getAutoDoingTimeStrategy(): String? {
        val key: String = mThing!!.id.toString() + "_" + KEY_INDEX_AUTO_START_DOING_TIME
        return mSpStartDoing!!.getString(key, START_DOING_TIME_FOLLOW_GENERAL_PICKED)
    }

    open fun setAutoDoingTimeStrategy(index: Int) {
        val key: String = mThing!!.id.toString() + "_" + KEY_INDEX_AUTO_START_DOING_TIME
        val pair: Pair<List<Int>, List<Int>> = getStartDoingTypeTimes(true)
        val strategy: String = pair.first!!.get(index).toString() + "," + pair.second!!.get(index)
        mSpStartDoing!!.edit().putString(key, strategy).apply()
    }

    open fun getAutoDoingTimeDesc(): String? {
        val doingTimeStr: String = getAutoDoingTimeStrategy()!!
        if (START_DOING_TIME_FOLLOW_GENERAL_PICKED.equals(doingTimeStr)) {
            return getAutoStartDoingTimeFollowGeneralStr()
        } else if (START_DOING_TIME_NOT_SURE_PICKED.equals(doingTimeStr)) {
            return mContext!!.getString(R.string.start_doing_time_not_sure)
        } else {
            val arr: Array<String> = doingTimeStr.split(",".toRegex()).toTypedArray()
            val type: Int = arr[0].toInt()
            val time: Int = arr[1].toInt()
            return DateTimeUtil.getDateTimeStr(type, time, mContext)
        }
    }

    open fun getAutoStartDoingTime(endTimeForNextHabitReminder: Long): Long {
        var key: String = mThing!!.id.toString() + "_" + KEY_INDEX_AUTO_START_DOING_TIME
        var doingTimeStr: String = mSpStartDoing!!.getString(
                key, START_DOING_TIME_FOLLOW_GENERAL_PICKED)!!
        if (START_DOING_TIME_FOLLOW_GENERAL_PICKED.equals(doingTimeStr)) {
            if (mThing!!.type == Thing.REMINDER) {
                key = Def.Meta.KEY_ASD_TIME_REMINDER
            } else {
                key = Def.Meta.KEY_ASD_TIME_HABIT
            }
            doingTimeStr = mSpSettings!!.getString(key, START_DOING_TIME_NOT_SURE_PICKED)!!
        }

        if (START_DOING_TIME_NOT_SURE_PICKED.equals(doingTimeStr)) {
            return -1
        }

        val arr: Array<String> = doingTimeStr.split(",".toRegex()).toTypedArray()
        val type: Int = arr[0].toInt()
        val time: Int = arr[1].toInt()
        var doingTime: Long = DateTimeUtil.getActualTimeAfterSomeTime(0, type, time)
        if (mThing!!.type == Thing.HABIT) {
            val habit: Habit? = HabitDAO.getInstance(mContext)!!.getHabitById(mThing!!.id)
            if (habit != null) {
                val calendar: GregorianCalendar = GregorianCalendar()
                val habitType: Int = habit.type
                val ct: Int = calendar.get(habitType)
                calendar.setTimeInMillis(System.currentTimeMillis() + doingTime
                        + TIME_BEFORE_NEXT_T)
                while (ct < calendar.get(habitType)) {
                    doingTime -= TUNING_TIME_STEP
                    calendar.setTimeInMillis(System.currentTimeMillis() + doingTime
                            + TIME_BEFORE_NEXT_T)
                }
            }

            if (endTimeForNextHabitReminder != -1L) {
                while (System.currentTimeMillis() + doingTime + TIME_BEFORE_NEXT_HABIT_REMINDER
                        > endTimeForNextHabitReminder) {
                    doingTime -= TUNING_TIME_STEP
                }
            }

            if (doingTime < MIN_DOING_TIME) {
                doingTime = -1
            }
        }

        return doingTime
    }

    open fun getAutoStartDoingFollowGeneralStr(): String? {
        val part1: String = mContext!!.getString(R.string.auto_start_doing_follow_general)
        val part2: String
        val enabled: String = mContext!!.getString(R.string.enabled)
        val disabled: String = mContext!!.getString(R.string.disabled)
        val sysStrategy: Int = mSpSettings!!.getInt(Def.Meta.KEY_AUTO_START_DOING,
                SYS_AUTO_START_DOING_STRATEGY_DISABLED)
        if (sysStrategy == SYS_AUTO_START_DOING_STRATEGY_DISABLED) {
            part2 = disabled
        } else if (sysStrategy == SYS_AUTO_START_DOING_STRATEGY_ALL) {
            part2 = enabled
        } else {
            if (mThing == null) {
                part2 = disabled
            } else {
                @Thing.Type val thingType: Int = mThing!!.type
                if (thingType == Thing.REMINDER && sysStrategy == SYS_AUTO_START_DOING_STRATEGY_REMINDER) {
                    part2 = enabled
                } else if (thingType == Thing.HABIT && sysStrategy == SYS_AUTO_START_DOING_STRATEGY_HABIT) {
                    part2 = enabled
                } else part2 = disabled
            }
        }
        return part1 + " (" + part2 + ")"
    }

    open fun getAutoStartDoingTimeFollowGeneralStr(): String? {
        val part1: String = mContext!!.getString(R.string.auto_start_doing_follow_general)
        val part2: String
        val key: String
        if (mThing!!.type == Thing.REMINDER) {
            key = Def.Meta.KEY_ASD_TIME_REMINDER
        } else {
            key = Def.Meta.KEY_ASD_TIME_HABIT
        }
        val doingTimeStr: String = mSpSettings!!.getString(key, START_DOING_TIME_NOT_SURE_PICKED)!!
        if (START_DOING_TIME_NOT_SURE_PICKED.equals(doingTimeStr)) {
            part2 = mContext!!.getString(R.string.start_doing_time_not_sure)
        } else {
            val arr: Array<String> = doingTimeStr.split(",".toRegex()).toTypedArray()
            val type: Int = arr[0].toInt()
            val time: Int = arr[1].toInt()
            part2 = DateTimeUtil.getDateTimeStr(type, time, mContext)!!
        }
        return part1 + " (" + part2 + ")"
    }

    open fun getAutoStrictModeFollowGeneralStr(): String? {
        val part1: String = mContext!!.getString(R.string.auto_start_doing_follow_general)
        val part2: String
        val enabled: String = mContext!!.getString(R.string.enabled)
        val disabled: String = mContext!!.getString(R.string.disabled)
        val sysStrategy: Int = mSpSettings!!.getInt(Def.Meta.KEY_AUTO_STRICT_MODE,
                SYS_AUTO_STRICT_MODE_STRATEGY_DISABLED)
        if (sysStrategy == SYS_AUTO_STRICT_MODE_STRATEGY_DISABLED) {
            part2 = disabled
        } else if (sysStrategy == SYS_AUTO_STRICT_MODE_STRATEGY_ALL) {
            part2 = enabled
        } else {
            if (mThing == null) {
                part2 = disabled
            } else {
                @Thing.Type val thingType: Int = mThing!!.type
                if (thingType == Thing.REMINDER && sysStrategy == SYS_AUTO_STRICT_MODE_STRATEGY_REMINDER) {
                    part2 = enabled
                } else if (thingType == Thing.HABIT && sysStrategy == SYS_AUTO_STRICT_MODE_STRATEGY_HABIT) {
                    part2 = enabled
                } else part2 = disabled
            }
        }
        return part1 + " (" + part2 + ")"
    }

    companion object {
        const val TAG: String = "ThingDoingHelper"

        @JvmField
        val TIME_BEFORE_NEXT_HABIT_REMINDER: Long = if (BuildConfig.DEBUG) 0L else 5L * 60 * 1000
        @JvmField
        val TIME_BEFORE_NEXT_T: Long = if (BuildConfig.DEBUG) 0L else 5L * 60 * 1000
        const val TUNING_TIME_STEP: Long = 5L * 60 * 1000
        const val MIN_DOING_TIME: Long = 5L * 60 * 1000

        @JvmField
        var KEY_INDEX_AUTO_START_DOING: Int               = 0

        @JvmField
        var AUTO_START_DOING_STRATEGY_FOLLOW_GENERAL: Int = 0
        @JvmField
        var AUTO_START_DOING_STRATEGY_ENABLED: Int        = 1
        @JvmField
        var AUTO_START_DOING_STRATEGY_DISABLED: Int       = 2

        @JvmField
        var SYS_AUTO_START_DOING_STRATEGY_DISABLED: Int   = 0
        @JvmField
        var SYS_AUTO_START_DOING_STRATEGY_REMINDER: Int   = 1
        @JvmField
        var SYS_AUTO_START_DOING_STRATEGY_HABIT: Int      = 2
        @JvmField
        var SYS_AUTO_START_DOING_STRATEGY_ALL: Int        = 3


        @JvmField
        var KEY_INDEX_AUTO_STRICT_MODE: Int               = 1

        @JvmField
        var AUTO_STRICT_MODE_STRATEGY_FOLLOW_GENERAL: Int = 0
        @JvmField
        var AUTO_STRICT_MODE_STRATEGY_ENABLED: Int        = 1
        @JvmField
        var AUTO_STRICT_MODE_STRATEGY_DISABLED: Int       = 2

        @JvmField
        var SYS_AUTO_STRICT_MODE_STRATEGY_DISABLED: Int   = 0
        @JvmField
        var SYS_AUTO_STRICT_MODE_STRATEGY_REMINDER: Int   = 1
        @JvmField
        var SYS_AUTO_STRICT_MODE_STRATEGY_HABIT: Int      = 2
        @JvmField
        var SYS_AUTO_STRICT_MODE_STRATEGY_ALL: Int        = 3


        @JvmField
        var KEY_INDEX_AUTO_START_DOING_TIME: Int          = 2


        const val START_DOING_TIME_FOLLOW_GENERAL_PICKED: String = "-2,-2"
        const val START_DOING_TIME_NOT_SURE_PICKED: String       = "-1,-1"

        @JvmStatic
        fun getStartDoingTypeTimes(
                addFollowGeneral: Boolean): Pair<List<Int>, List<Int>> {
            val types: MutableList<Int> = ArrayList()
            val times: MutableList<Int> = ArrayList()

            if (addFollowGeneral) {
                types.add(-2)
                times.add(-2)
            }

            types.add(-1)
            times.add(-1)

            if (BuildConfig.DEBUG) {
                types.add(Calendar.SECOND)
                times.add(6)
            }

            types.add(Calendar.MINUTE)
            types.add(Calendar.MINUTE)
            types.add(Calendar.MINUTE)
            types.add(Calendar.HOUR_OF_DAY)
            types.add(Calendar.MINUTE)
            types.add(Calendar.HOUR_OF_DAY)
            types.add(Calendar.HOUR_OF_DAY)
            types.add(Calendar.HOUR_OF_DAY)

            times.add(15)
            times.add(30)
            times.add(45)
            times.add(1)
            times.add(90)
            times.add(2)
            times.add(3)
            times.add(4)

            return Pair(types, times)
        }

        /**
         * Not sure
         * 6  seconds(DEBUG)
         * 15 minutes
         * 30 minutes
         * 45 minutes
         * 1  hour
         * 90 minutes
         * 2  hours
         * 3  hours
         * 4  hours
         */
        @JvmStatic
        fun getStartDoingTimeItems(context: Context?): List<String?>? {
            val typeTimes: Pair<List<Int>, List<Int>> = getStartDoingTypeTimes(false)
            val items: MutableList<String?> = ArrayList()
            items.add(context!!.getString(R.string.start_doing_time_not_sure))
            val size: Int = typeTimes.first!!.size
            for (i in 1 until size) {
                items.add(DateTimeUtil.getDateTimeStr(
                        typeTimes.first!!.get(i), typeTimes.second!!.get(i), context))
            }
            return items
        }

        @JvmStatic
        fun getStartDoingTimeIndex(picked: String?, hasFollowGeneral: Boolean): Int {
            if (TextUtils.isEmpty(picked)) {
                return 0
            }
            val pair: Pair<List<Int>, List<Int>> = getStartDoingTypeTimes(hasFollowGeneral)
            val size: Int = pair.first!!.size
            for (i in 0 until size) {
                val str: String = pair.first!!.get(i).toString() + "," + pair.second!!.get(i)
                if (str.equals(picked)) {
                    return i
                }
            }
            return 0
        }

        @JvmStatic
        fun getStartDoingTimePickedStr(
                index: Int, hasFollowGeneral: Boolean): String? {
            val pair: Pair<List<Int>, List<Int>> = getStartDoingTypeTimes(hasFollowGeneral)
            return pair.first!!.get(index).toString() + "," + pair.second!!.get(index)
        }

        @JvmStatic
        fun stopDoing(context: Context?, @DoingRecord.StopReason stopReason: Int) {
            val intent: Intent = Intent(DoingActivity.BROADCAST_ACTION_JUST_FINISH)
            intent.setPackage(context!!.getPackageName())
            context.sendBroadcast(intent)
            DoingService.sStopReason = stopReason
            context.stopService(Intent(context, DoingService::class.java))
        }
    }

}
