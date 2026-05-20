package com.ywwynm.everythingdone.utils

import androidx.collection.LongSparseArray

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing

import java.util.Comparator

/**
 * Created by ywwynm on 2016/10/23.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * utils for sorting list
 */
object ThingsSorter {

    const val TAG: String = "ThingsSorter"

    /**
     * Get a [Comparator] used to sort a list of [Thing]s by their alarm time.
     * @param ignoreSticky if `true`, then the first thing in the result list sorted by this
     *                     comparator will be the thing that are closest to ring an alarm, even if
     *                     it's not sticky and there are other sticky things.
     *                     if `false`, then the first thing will be sticky even if there are
     *                     other things that have closer alarm to ring. Of course, if there are no
     *                     sticky things, then the sorted list will be same as that sorted with
     *                     `ignoreSticky` is false.
     */
    @JvmStatic
    fun getThingComparatorByAlarmTime(ignoreSticky: Boolean): Comparator<Thing?>? {
        val rDao: ReminderDAO = ReminderDAO.getInstance(App.getApp())!!
        val hDao: HabitDAO = HabitDAO.getInstance(App.getApp())!!
        return object : Comparator<Thing?> {

            private val shouldCompareMap: LongSparseArray<Boolean?> = LongSparseArray()
            private val timeMap: LongSparseArray<Long?> = LongSparseArray()

            override fun compare(thing1: Thing?, thing2: Thing?): Int {
                // header is on top
                if (thing1!!.type == Thing.HEADER) return -1
                if (thing2!!.type == Thing.HEADER) return 1

                if (ignoreSticky) {
                    return compareByAlarmTime(thing1, thing2)
                } else {
                    // sort by location at first(which means considering sticky), then by alarm time
                    val loc1: Long = thing1.location
                    val loc2: Long = thing2.location
                    if (loc1 < 0 && loc2 >= 0) {
                        return -1
                    } else if (loc1 >= 0 && loc2 < 0) {
                        return 1
                    } else {
                        return compareByAlarmTime(thing1, thing2)
                    }
                }
            }

            private fun compareByAlarmTime(thing1: Thing, thing2: Thing): Int {
                @Thing.Type val type1: Int = thing1.type
                @Thing.Type val type2: Int = thing2.type
                val id1: Long = thing1.id
                val shouldCompare1: Boolean = shouldCompare(id1, type1)
                val id2: Long = thing2.id
                val shouldCompare2: Boolean = shouldCompare(id2, type2)

                if (!shouldCompare1 && !shouldCompare2) {
                    return compareByLocationAndSticky(thing1.location, thing2.location)
                } else if (shouldCompare1 && !shouldCompare2) {
                    return -1
                } else if (!shouldCompare1 && shouldCompare2) {
                    return 1
                } else {
                    val time1: Long = getAlarmTime(thing1.id, type1)
                    val time2: Long = getAlarmTime(thing2.id, type2)
                    if (time1 < time2) {
                        return -1
                    } else if (time1 == time2) {
                        return 0
                    } else return 1
                }
            }

            private fun shouldCompare(id: Long, @Thing.Type type: Int): Boolean {
                var shouldCompare: Boolean? = shouldCompareMap.get(id)
                if (shouldCompare == null) {
                    if (Thing.isReminderType(type)) {
                        val reminder: Reminder? = rDao.getReminderById(id)
                        shouldCompare = reminder != null && reminder.state == Reminder.UNDERWAY
                    } else if (type == Thing.HABIT) {
                        val habit: Habit? = hDao.getHabitById(id)
                        shouldCompare = habit != null
                    } else {
                        shouldCompare = false
                    }
                    shouldCompareMap.put(id, shouldCompare)
                }
                return shouldCompare
            }

            private fun getAlarmTime(id: Long, @Thing.Type type: Int): Long {
                var time: Long? = timeMap.get(id)
                if (time == null) {
                    time = Long.MAX_VALUE
                    if (Thing.isReminderType(type)) {
                        val r1: Reminder? = rDao.getReminderById(id)
                        if (r1 != null && r1.state == Reminder.UNDERWAY) {
                            time = r1.notifyTime
                        }
                    } else {
                        val h1: Habit? = hDao.getHabitById(id)
                        if (h1 != null) {
                            time = h1.getMinHabitReminderTime()
                        }
                    }
                    timeMap.put(id, time)
                }
                return time!!
            }
        }
    }

    @JvmStatic
    fun compareByLocationAndSticky(loc1: Long, loc2: Long): Int {
        if (loc1 < 0 && loc2 >= 0) {
            return -1
        } else if (loc1 >= 0 && loc2 < 0) {
            return 1
        } else if (loc1 >= 0 && loc2 >= 0) {
            if (loc1 > loc2)       return -1
            else if (loc1 == loc2) return 0
            else                   return 1
        } else { // both are <0, both are sticky on top
            // locations are -3 and -2, the -3 one will be in front of the -2 one.
            if (loc1 < loc2)       return -1
            else if (loc1 == loc2) return 0
            else                   return 1
        }
    }

}
