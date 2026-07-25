@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.helpers

import androidx.fragment.app.FragmentActivity
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import androidx.annotation.StringRes

import com.ywwynm.everythingdone.Def.Meta.KEY_1_0_3_TO_1_0_4
import com.ywwynm.everythingdone.Def.Meta.KEY_1_0_4_TO_1_0_5
import com.ywwynm.everythingdone.Def.Meta.KEY_1_1_4_TO_1_1_5
import com.ywwynm.everythingdone.Def.Meta.KEY_1_2_7_TO_1_3_0
import com.ywwynm.everythingdone.Def.Meta.KEY_1_3_0_TO_1_3_1
import com.ywwynm.everythingdone.Def.Meta.KEY_1_3_3_TO_1_3_4
import com.ywwynm.everythingdone.Def.Meta.META_DATA_NAME
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.fragments.LongTextDialogFragment
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.utils.DisplayUtil

/**
 * Created by ywwynm on 2016/4/19.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * handle update from old version to a new version
 */
open class AppUpdateHelper private constructor(context: Context?) {

    private var mContext: Context? = context

    open fun handleAppUpdate() {
        val sp: SharedPreferences = mContext!!.getSharedPreferences(
                META_DATA_NAME, Context.MODE_PRIVATE)

        updateFrom1_0_3To1_0_4(sp)
    }

    open fun showInfo(activity: FragmentActivity?) {
        val sp: SharedPreferences = mContext!!.getSharedPreferences(
                META_DATA_NAME, Context.MODE_PRIVATE)

        showFrom1_3_3To1_3_4(sp, activity)
    }

    private fun updateFrom1_0_3To1_0_4(sp: SharedPreferences) {
        val updated: Boolean = sp.getBoolean(KEY_1_0_3_TO_1_0_4, false)
        if (updated) {
            return
        }

        // transfer some reminders to goals
        val thingDAO: ThingDAO = ThingDAO.getInstance(mContext)!!
        val reminderDAO: ReminderDAO = ReminderDAO.getInstance(mContext)!!
        val cursor: Cursor = thingDAO.getThingsCursor("type=" + Thing.REMINDER)!!
        while (cursor.moveToNext()) {
            val thing = Thing(cursor)
            val id: Long = thing.id
            val reminder: Reminder = reminderDAO.getReminderById(id)!!
            val millis: Long = reminder.notifyMillis
            if (millis < Reminder.GOAL_MILLIS) continue

            thing.type = Thing.GOAL
            thingDAO.update(Thing.REMINDER, thing, true, true)
        }

        sp.edit().putBoolean(KEY_1_0_3_TO_1_0_4, true).apply()
    }

    private fun showFrom1_0_4To1_0_5(sp: SharedPreferences, activity: FragmentActivity?): Boolean {
        val updated: Boolean = sp.getBoolean(KEY_1_0_4_TO_1_0_5, false)
        if (updated) {
            return false
        }

        val ltdf: LongTextDialogFragment = createLongTextDialog(
                R.string.title_important_alert, R.string.content_important_reminder_permission)
        ltdf.show(activity!!.supportFragmentManager, LongTextDialogFragment.TAG)

        sp.edit().putBoolean(KEY_1_0_4_TO_1_0_5, true).apply()
        return true
    }

    private fun showFrom1_2_7To1_3_0(sp: SharedPreferences, activity: FragmentActivity?): Boolean {
        val updated: Boolean = sp.getBoolean(KEY_1_2_7_TO_1_3_0, false)
        if (updated) {
            return false
        }

        val ltdf: AlertDialogFragment = createDialog(
                R.string.from_1_2_7_to_1_3_0_title, R.string.from_1_2_7_to_1_3_0_content)
        ltdf.show(activity!!.supportFragmentManager, AlertDialogFragment.TAG)

        sp.edit().putBoolean(KEY_1_2_7_TO_1_3_0, true).apply()
        return true
    }

    private fun showFrom1_3_0To1_3_1(sp: SharedPreferences, activity: FragmentActivity?): Boolean {
        val updated: Boolean = sp.getBoolean(KEY_1_3_0_TO_1_3_1, false)
        if (updated) {
            return false
        }

        val ltdf: AlertDialogFragment = createDialog(
                R.string.from_1_3_0_to_1_3_1_title, R.string.from_1_3_0_to_1_3_1_content)
        ltdf.show(activity!!.supportFragmentManager, AlertDialogFragment.TAG)

        sp.edit().putBoolean(KEY_1_3_0_TO_1_3_1, true).apply()
        return true
    }

    private fun showFrom1_3_3To1_3_4(sp: SharedPreferences, activity: FragmentActivity?): Boolean {
        val updated: Boolean = sp.getBoolean(KEY_1_3_3_TO_1_3_4, false)
        if (updated) {
            return false
        }

        val ltdf: AlertDialogFragment = createDialog(
                R.string.from_1_3_3_to_1_3_4_title, R.string.from_1_3_3_to_1_3_4_content)
        ltdf.show(activity!!.supportFragmentManager, AlertDialogFragment.TAG)

        sp.edit().putBoolean(KEY_1_3_3_TO_1_3_4, true).apply()
        return true
    }

    private fun createDialog(@StringRes titleRes: Int, @StringRes contentRes: Int): AlertDialogFragment {
        val adf = AlertDialogFragment()
        val color: Int = DisplayUtil.getRandomColor(mContext)
        adf.setTitleColor(color)
        adf.setConfirmColor(color)
        adf.setShowCancel(false)
        adf.setTitle(mContext!!.getString(titleRes))
        adf.setContent(mContext!!.getString(contentRes))
        adf.setConfirmText(mContext!!.getString(R.string.act_get_it))

        return adf
    }

    private fun createLongTextDialog(@StringRes titleRes: Int, @StringRes contentRes: Int): LongTextDialogFragment {
        val ltdf = LongTextDialogFragment()
        ltdf.setAccentColor(DisplayUtil.getRandomColor(mContext))
        ltdf.setTitle(mContext!!.getString(titleRes))
        ltdf.setContent(mContext!!.getString(contentRes))
        return ltdf
    }

    companion object {
        const val TAG: String = "AppUpdateHelper"

        @JvmField
        var sInstance: AppUpdateHelper? = null

        @JvmStatic
        fun getInstance(context: Context?): AppUpdateHelper? {
            if (sInstance == null) {
                synchronized(AppUpdateHelper::class.java) {
                    if (sInstance == null) {
                        sInstance = AppUpdateHelper(context!!.applicationContext)
                    }
                }
            }
            return sInstance
        }

        @JvmStatic
        fun updateFrom1_1_4To1_1_5(activity: FragmentActivity?, color: Int): Boolean {
            val sp: SharedPreferences = activity!!.getSharedPreferences(
                    META_DATA_NAME, Context.MODE_PRIVATE)
            val updated: Boolean = sp.getBoolean(KEY_1_1_4_TO_1_1_5, false)
            if (updated) {
                return false
            }

            val adf = AlertDialogFragment()
            adf.setShowCancel(false)
            adf.setTitleColor(color)
            adf.setConfirmColor(color)
            adf.setTitle(activity.getString(R.string.from_1_1_4_to_1_1_5_title))
            adf.setContent(activity.getString(R.string.from_1_1_4_to_1_1_5_content))
            adf.show(activity.supportFragmentManager, AlertDialogFragment.TAG)

            sp.edit().putBoolean(KEY_1_1_4_TO_1_1_5, true).apply()

            return true
        }
    }
}
