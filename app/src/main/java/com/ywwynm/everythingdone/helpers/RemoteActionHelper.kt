package com.ywwynm.everythingdone.helpers

import android.content.Context
import android.content.Intent
import androidx.core.util.Pair
import android.util.Log
import android.widget.Toast

import com.google.gson.Gson
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DoingActivity
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.DoingRecord
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.services.DoingService
import com.ywwynm.everythingdone.utils.DateTimeUtil

import java.time.ZonedDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Created by ywwynm on 2016/9/4.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Helper class for execute actions that happen not in ThingsActivity or DetailActivity.
 * For example, action finish in a notification for Reminder will finally call method here.
 */
object RemoteActionHelper {

    const val TAG: String = "RemoteActionHelper"

    @JvmStatic
    fun finishReminder(context: Context?, thing: Thing?, position: Int) {
        var t: Thing = thing!!
        if (App.getDoingThingId() == t.id) {
            DoingService.sStopReason = DoingRecord.STOP_REASON_FINISH
            val intentJustFinish1 = Intent(DoingActivity.BROADCAST_ACTION_JUST_FINISH)
            intentJustFinish1.setPackage(context!!.packageName)
            context.sendBroadcast(intentJustFinish1)
            context.stopService(Intent(context, DoingService::class.java))
            App.setDoingThingId(-1L)
        }

        if (position == -1) {
            t = Thing.getSameCheckStateThing(t, Thing.UNDERWAY, Thing.FINISHED)!!
            val thingDAO: ThingDAO = ThingDAO.getInstance(context)!!
            thingDAO.updateState(t, t.location, Thing.UNDERWAY, Thing.FINISHED,
                    true,  /* handleNotifyEmpty  */
                    true,  /* handleCurrentLimit */
                    false, /* toUndo             */
                    true   /* shouldUpdateHeader */)
            Thing.tryToCancelOngoing(context, t.id)
        }
        updateUiEverywhere(context, t, position, t.type,
                Def.Communication.RESULT_UPDATE_THING_STATE_DIFFERENT)
    }

    @JvmStatic
    fun finishHabitOnce(
            context: Context?, thing: Thing?, position: Int, hrTime: Long): Boolean {
        val habitDAO: HabitDAO = HabitDAO.getInstance(context)!!
        val habit: Habit? = habitDAO.getHabitById(thing!!.id)
        val typeBefore: Int = thing.type
        if (habit == null) {
            correctIfNoHabit(context, thing, position, typeBefore)
            return false
        }

        val doing: Boolean = App.getDoingThingId() == thing.id
        if (doing) {
            DoingService.sStopReason = DoingRecord.STOP_REASON_FINISH
            val intentJustFinish2 = Intent(DoingActivity.BROADCAST_ACTION_JUST_FINISH)
            intentJustFinish2.setPackage(context!!.packageName)
            context.sendBroadcast(intentJustFinish2)
            context.stopService(Intent(context, DoingService::class.java))
            App.setDoingThingId(-1L)
        }

        val allowFinish: Boolean
        allowFinish = if (hrTime == -1L) {
            habit.allowFinish()
        } else {
            habit.allowFinish(hrTime)
        }
        if (allowFinish) {
            habitDAO.finishOneTime(habit)
            updateUiEverywhere(context, thing, position, typeBefore,
                    Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME)
            return true
        } else {
            PossibleMistakeHelper.outputNewMistakeInBackground(
                    possibleMistakeInfoForFinishingHabitOnce(thing, position, hrTime, doing, habit))

            if (habit.record!!.isEmpty() && habit.remindedTimes == 0) {
                Toast.makeText(context, R.string.alert_cannot_finish_habit_first_time,
                        Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, R.string.alert_cannot_finish_habit_more_times,
                        Toast.LENGTH_LONG).show()
            }
            return false
        }
    }

    private fun possibleMistakeInfoForFinishingHabitOnce(
            thing: Thing?, position: Int, hrTime: Long, doing: Boolean, habit: Habit): String {
        val gson = Gson()
        var dt: ZonedDateTime = ZonedDateTime.now()
        val curTimeStr: String = dt.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        var hrTimeStr = ""
        if (hrTime != -1L) {
            dt = Instant.ofEpochMilli(hrTime).atZone(ZoneId.systemDefault())
            hrTimeStr = dt.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        }
        val recordLength: Int = habit.record!!.length
        val remindedTimes: Int = habit.remindedTimes
        return "thing: " + gson.toJson(thing) + "\n\n" +

                "position: " + position + "\n\n" +

                "hrTime: " + hrTime + "\n" +
                "hrTimeStr: " + hrTimeStr + "\n\n" +

                "curTime: " + System.currentTimeMillis() + "\n" +
                "curTimeStr: " + curTimeStr + "\n\n" +

                "habit.type: " + habit.type + "\n" +
                "habit.isPaused: " + habit.isPaused() + "\n" +
                "habit.recordLength: " + recordLength + "\n" +
                "habit.remindedTimes: " + remindedTimes + "\n\n" +

                "doingThisThing: " + doing
    }

    @JvmStatic
    fun delay(context: Context?, thing: Thing?, position: Int, type: Int, time: Int) {
        val dao: ReminderDAO = ReminderDAO.getInstance(context)!!
        val reminder: Reminder? = dao.getReminderById(thing!!.id)
        val typeBefore: Int = thing.type
        if (reminder == null) {
            correctIfNoReminder(context, thing, position, typeBefore)
            return
        }

        thing.updateTime = System.currentTimeMillis()
        if (position == -1) {
            ThingDAO.getInstance(context)!!.update(typeBefore, thing, false, false)
        } else {
            ThingManager.getInstance(context)!!.update(typeBefore, thing, position, false)
        }
        val addMillis: Long = DateTimeUtil.getActualTimeAfterSomeTime(type, time) -
                System.currentTimeMillis()
        val oldNotifyTime: Long   = reminder.notifyTime
        val oldNotifyMillis: Long = reminder.notifyMillis
        reminder.notifyTime = System.currentTimeMillis() + addMillis
        reminder.notifyMillis = System.currentTimeMillis() - oldNotifyTime + oldNotifyMillis + addMillis
        reminder.state = Reminder.UNDERWAY
        reminder.updateTime = System.currentTimeMillis()
        dao.update(reminder)

        updateUiEverywhere(context, thing, position, typeBefore,
                Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME)
    }

    @JvmStatic
    fun toggleChecklistItem(context: Context?, id: Long, itemPos: Int) {
        val pair: Pair<Thing, Int> = App.getThingAndPosition(context, id, -1)!!
        val thing: Thing = pair.first ?: return
        val updatedContent: String? = CheckListHelper.toggleChecklistItem(thing.content, itemPos)
        thing.content = updatedContent
        val position: Int = pair.second!!
        val typeBefore: Int = thing.type
        if (position == -1) {
            ThingDAO.getInstance(context)!!.update(typeBefore, thing, false, false)
        } else {
            ThingManager.getInstance(context)!!.update(typeBefore, thing, position, false)
        }
        updateUiEverywhere(context, thing, position, typeBefore,
                Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME)
    }

    @JvmStatic
    fun doingOrCancel(context: Context?, thing: Thing?) {
        if (App.isSomethingUpdatedSpecially()) {
            App.tryToSetNotifyAllToTrue(thing, Def.Communication.RESULT_DOING_OR_CANCEL)
        } else {
            App.setSomethingUpdatedSpecially(true)
        }
        val broadcastIntent = Intent(
                Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI)
        broadcastIntent.putExtra(Def.Communication.KEY_RESULT_CODE,
                Def.Communication.RESULT_DOING_OR_CANCEL)
        broadcastIntent.putExtra(Def.Communication.KEY_THING, thing)
        broadcastIntent.setPackage(context!!.packageName)
        context.sendBroadcast(broadcastIntent)

        AppWidgetHelper.updateSingleThingAppWidgets(context, thing!!.id)
        AppWidgetHelper.updateThingsListAppWidgetsForType(context, thing.type)

        App.setLastUpdateUiIntent(broadcastIntent)
    }

    @JvmStatic
    fun correctIfNoReminder(
            context: Context?, thing: Thing?, position: Int, typeBefore: Int) {
        if (Thing.isReminderType(typeBefore)) {
            thing!!.type = Thing.NOTE
            if (position == -1) {
                ThingDAO.getInstance(context)!!.update(typeBefore, thing, true, true)
            }
            updateUiEverywhere(
                    context, thing, position, typeBefore,
                    Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_DIFFERENT)
        }
    }

    @JvmStatic
    fun correctIfNoHabit(
            context: Context?, thing: Thing?, position: Int, typeBefore: Int) {
        if (typeBefore == Thing.HABIT) {
            thing!!.type = Thing.NOTE
            if (position == -1) {
                ThingDAO.getInstance(context)!!.update(typeBefore, thing, true, true)
            }
            updateUiEverywhere(context, thing, position, typeBefore,
                    Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_DIFFERENT)
        }
    }

    /**
     * Update UI for ThingsActivity and app widgets if a remote action happened.
     * This method will also finish the action if it acts with a thing that under current limit,
     * which means it can be found in [ThingManager.mThings]. In this situation, we should
     * call methods in [ThingManager], get their returned values and put them into broadcast
     * [Intent]s, as a result of which, ThingsActivity can handle UI update correctly as well
     * as appropriately.
     *
     * @param context the context where the action happened.
     * @param thing the thing that the action act with.
     * @param position position of `thing` inside [ThingManager.mThings]. This can
     *                 be -1 if `thing` couldn't be found under current limit.
     * @param typeBefore used when we are updating `thing`'s type.
     * @param resultCode although this method can handle all possible resultCodes declared in
     *                   [com.ywwynm.everythingdone.Def.Communication], remote actions will
     *                   only produce following resultCodes for the time being:
     *                   1. RESULT_UPDATE_THING_STATE_DIFFERENT: for finishing a Reminder/Goal.
     *                   2. RESULT_UPDATE_THING_DONE_TYPE_SAME: for finishing a Habit once, or delay
     *                      a Reminder.
     *                   3. RESULT_UPDATE_THING_DONE_TYPE_DIFFERENT: for finding a wrong thing or
     *                      not finding a correct related Reminder/Habit instance.
     */
    @JvmStatic
    fun updateUiEverywhere(
            context: Context?, thing: Thing?, position: Int, typeBefore: Int, resultCode: Int) {
        Log.i(TAG, "updateUiEverywhere called")
        if (App.isSomethingUpdatedSpecially()) {
            Log.i(TAG, "App.isSomethingUpdatedSpecially is already true")
            App.tryToSetNotifyAllToTrue(thing, resultCode)
        } else {
            Log.i(TAG, "App.isSomethingUpdatedSpecially is false, set to true")
            App.setSomethingUpdatedSpecially(true)
        }

        val broadcastIntent = Intent(
                Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI)
        broadcastIntent.putExtra(Def.Communication.KEY_RESULT_CODE, resultCode)
        broadcastIntent.putExtra(Def.Communication.KEY_THING, thing)
        broadcastIntent.putExtra(Def.Communication.KEY_POSITION, position)
        broadcastIntent.putExtra(Def.Communication.KEY_TYPE_BEFORE, typeBefore)

        val thingManager: ThingManager = ThingManager.getInstance(context)!!
        if (resultCode == Def.Communication.RESULT_UPDATE_THING_STATE_DIFFERENT) {
            broadcastIntent.putExtra(Def.Communication.KEY_STATE_AFTER, Thing.FINISHED)
            if (position != -1) {
                val shouldCallChange: Boolean = thingManager.updateState(
                        thing, position, thing!!.location, Thing.UNDERWAY,
                        Thing.FINISHED, false, true)
                Log.d(TAG, "Updating state from remote action, shouldCallChange: $shouldCallChange")
                broadcastIntent.putExtra(Def.Communication.KEY_CALL_CHANGE, shouldCallChange)
            }
        } else if (resultCode == Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_DIFFERENT) {
            if (position != -1) {
                val shouldCallChange: Boolean =
                        thingManager.update(typeBefore, thing, position, true) == 1
                Log.d(TAG, "Updating type from remote action, shouldCallChange: $shouldCallChange")
                broadcastIntent.putExtra(Def.Communication.KEY_CALL_CHANGE, shouldCallChange)
            }
        }
        broadcastIntent.setPackage(context!!.packageName)
        context.sendBroadcast(broadcastIntent)

        AppWidgetHelper.updateSingleThingAppWidgets(context, thing!!.id)
        AppWidgetHelper.updateThingsListAppWidgetsForType(context, typeBefore)
        val typeAfter: Int = thing.type
        if (typeBefore != typeAfter) {
            AppWidgetHelper.updateThingsListAppWidgetsForType(context, typeAfter)
        }

        App.setLastUpdateUiIntent(broadcastIntent)
    }

}
