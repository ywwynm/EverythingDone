@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.activities

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.util.Pair
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.fragments.AlertDialogFragment
import com.ywwynm.everythingdone.fragments.ChooserDialogFragment
import com.ywwynm.everythingdone.helpers.ThingDoingHelper
import com.ywwynm.everythingdone.model.DoingRecord
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.services.DoingService
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.DisplayUtil

import java.util.GregorianCalendar

/**
 * Created by ywwynm on 2016/10/27.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * An Activity mainly used to select time will be spent to do something
 */
open class StartDoingActivity : AppCompatActivity() {

    private var mThing: Thing? = null
    @DoingService.StartType
    private var mStartType: Int = 0
    /** Phase 8: accent decoded from the intent. */
    private var mAccentBackground: ThingBackground? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent: Intent = getIntent()
        val id = intent.getLongExtra(Def.Communication.KEY_ID, -1)
        val pos = intent.getIntExtra(Def.Communication.KEY_POSITION, -1)
        val pair: Pair<Thing, Int> = App.getThingAndPosition(applicationContext, id, pos)!!
        mThing = pair.first
        if (mThing == null) {
            finish()
            return
        }
        mStartType = intent.getIntExtra(DoingService.KEY_START_TYPE, DoingService.START_TYPE_ALARM)

        val color = intent.getIntExtra(Def.Communication.KEY_COLOR, DisplayUtil.getRandomColor(this))
        val bgJson = intent.getStringExtra(Def.Communication.KEY_BACKGROUND)
        mAccentBackground = ThingBackground.fromJson(bgJson)
        if (mAccentBackground == null) mAccentBackground = ThingBackground.pure(color)

        val cdf = ChooserDialogFragment()
        cdf.setAccentBackground(mAccentBackground)
        cdf.setShouldShowMore(false)
        cdf.setTitle(getString(R.string.start_doing_estimated_time))
        cdf.setItems(ThingDoingHelper.getStartDoingTimeItems(this) as MutableList<String?>?)
        cdf.setInitialIndex(0)
        cdf.setShouldDismissAfterConfirm(false)
        cdf.setConfirmText(getString(R.string.start_doing_confirm))
        cdf.setConfirmListener {
            val doingId = App.getDoingThingId()
            if (doingId == -1L) {
                tryToStartDoingAlarmUser(cdf)
            } else if (doingId != mThing!!.id) {
                // doing another thing
                tryToStopAnotherDoingAndStartThis(cdf)
            } else {
                // TODO: 2016/11/27 is doing this thing impossible here?
            }
        }
        cdf.setOnDismissListener(object : ChooserDialogFragment.OnDismissListener {
            override fun onDismiss() {
                finish()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
                } else {
                    overridePendingTransition(0, 0)
                }
            }
        })
        cdf.show(fragmentManager, ChooserDialogFragment.TAG)
    }

    private fun tryToStopAnotherDoingAndStartThis(cdf: ChooserDialogFragment) {
        val adf = AlertDialogFragment()
        adf.setTitleBackground(mAccentBackground)
        adf.setConfirmBackground(mAccentBackground)
        adf.setTitle(getString(R.string.start_doing_stop_another_title))
        adf.setContent(getString(R.string.start_doing_stop_another_content))
        adf.setConfirmText(getString(R.string.yes))
        adf.setCancelText(getString(R.string.no))
        adf.setConfirmListener(object : AlertDialogFragment.ConfirmListener {
            override fun onConfirm() {
                tryToStartDoingAlarmUser(cdf)
            }
        })
        adf.show(fragmentManager, AlertDialogFragment.TAG)
    }

    private fun tryToStartDoingAlarmUser(cdf: ChooserDialogFragment) {
        val index = cdf.getPickedIndex()
        var canStartDoing = true
        var timeInMillis: Long
        if (index == 0) {
            timeInMillis = -1
        } else {
            val typeTimes: Pair<List<Int>, List<Int>> =
                ThingDoingHelper.getStartDoingTypeTimes(false)!!
            val etc: Long = DateTimeUtil.getActualTimeAfterSomeTime(
                typeTimes.first.get(index), typeTimes.second.get(index)
            )
            if (mThing!!.type == Thing.HABIT) {
                val habit: Habit? = HabitDAO.getInstance(this)!!.getHabitById(mThing!!.id)
                if (habit != null) {
                    val calendar = GregorianCalendar()
                    val ct = calendar.get(habit.type) // current t
                    calendar.timeInMillis = etc + ThingDoingHelper.TIME_BEFORE_NEXT_T
                    if (calendar.get(habit.type) != ct) {
                        Toast.makeText(
                            this,
                            R.string.start_doing_time_long_t, Toast.LENGTH_LONG
                        ).show()
                        canStartDoing = false
                    } else {
                        val nextTime = habit.getDoingEndLimitTime()
                        if (etc >= nextTime - ThingDoingHelper.TIME_BEFORE_NEXT_HABIT_REMINDER) {
                            Toast.makeText(
                                this,
                                R.string.start_doing_time_long_alarm, Toast.LENGTH_LONG
                            ).show()
                            canStartDoing = false
                        }
                    }
                }
            }
            timeInMillis = DateTimeUtil.getActualTimeAfterSomeTime(
                0, typeTimes.first.get(index), typeTimes.second.get(index)
            )
        }
        if (canStartDoing) {
            cdf.dismiss()
            val doingId = App.getDoingThingId()
            if (doingId != -1L && doingId != mThing!!.id) {
                DoingService.sResetDoingIdInOnDestroy = false
                ThingDoingHelper.stopDoing(this, DoingRecord.STOP_REASON_CANCEL_USER)
            }

            val helper = ThingDoingHelper(this, mThing)
            val hrTime = getIntent().getLongExtra(Def.Communication.KEY_TIME, -1L)
            if (mStartType == DoingService.START_TYPE_ALARM) {
                helper.startDoingAlarm(timeInMillis, hrTime)
            } else {
                helper.startDoingUser(timeInMillis, hrTime)
            }
        }
    }

    companion object {
        const val TAG: String = "StartDoingActivity"

        @JvmStatic
        fun getOpenIntent(
            context: Context?, thingId: Long, position: Int, color: Int,
            @DoingService.StartType startType: Int, hrTime: Long
        ): Intent {
            return getOpenIntent(
                context, thingId, position,
                ThingBackground.pure(color), startType, hrTime
            )
        }

        /**
         * Phase 8: full ThingBackground-aware open intent.
         */
        @JvmStatic
        fun getOpenIntent(
            context: Context?, thingId: Long, position: Int, bg: ThingBackground?,
            @DoingService.StartType startType: Int, hrTime: Long
        ): Intent {
            val intent = Intent(context, StartDoingActivity::class.java)
            intent.putExtra(Def.Communication.KEY_ID, thingId)
            intent.putExtra(Def.Communication.KEY_POSITION, position)
            if (bg != null) {
                intent.putExtra(Def.Communication.KEY_COLOR, bg.representativeColor())
                intent.putExtra(Def.Communication.KEY_BACKGROUND, bg.toJson())
            }
            intent.putExtra(DoingService.KEY_START_TYPE, startType)
            intent.putExtra(Def.Communication.KEY_TIME, hrTime)
            return intent
        }
    }
}
