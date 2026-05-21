@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.activities

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.util.Pair
import androidx.appcompat.app.AppCompatActivity

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.fragments.ChooserDialogFragment
import com.ywwynm.everythingdone.helpers.RemoteActionHelper
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.DisplayUtil

import java.util.ArrayList
import java.util.Calendar

/**
 * Created by ywwynm on 2016/10/21.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * An Activity used to select time to delay an alarm for Reminder
 */
open class DelayReminderActivity : AppCompatActivity() {

    private val mTypes: IntArray = intArrayOf(
        Calendar.MINUTE,
        Calendar.MINUTE,
        Calendar.MINUTE,
        Calendar.MINUTE,
        Calendar.MINUTE,
        Calendar.HOUR_OF_DAY,
        Calendar.HOUR_OF_DAY,
        Calendar.HOUR_OF_DAY,
        Calendar.DATE
    )
    private val mTimes: IntArray = intArrayOf(5, 10, 15, 30, 45, 1, 2, 6, 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent: Intent = getIntent()
        val id = intent.getLongExtra(Def.Communication.KEY_ID, -1)
        val pos = intent.getIntExtra(Def.Communication.KEY_POSITION, -1)
        val pair: Pair<Thing, Int> = App.getThingAndPosition(applicationContext, id, pos)
        if (pair.first == null) {
            finish()
            return
        }

        val color = intent.getIntExtra(Def.Communication.KEY_COLOR, DisplayUtil.getRandomColor(this))
        val bgJson = intent.getStringExtra(Def.Communication.KEY_BACKGROUND)
        var accent: ThingBackground? = ThingBackground.fromJson(bgJson)
        if (accent == null) accent = ThingBackground.pure(color)

        val cdf = ChooserDialogFragment()
        cdf.setAccentBackground(accent)
        cdf.setShouldShowMore(false)
        cdf.setTitle(getString(R.string.delay_reminder))
        cdf.setItems(getItems())
        cdf.setInitialIndex(0)
        cdf.setConfirmListener {
            val index = cdf.getPickedIndex()
            RemoteActionHelper.delay(
                applicationContext, pair.first, pair.second ?: -1,
                mTypes[index], mTimes[index]
            )
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

    /**
     * 5  minutes
     * 10 minutes
     * 15 minutes
     * 30 minutes
     * 45 minutes
     * 1  hour
     * 2  hours
     * 6  hours
     * 1  day
     */
    private fun getItems(): MutableList<String?> {
        val items: MutableList<String?> = ArrayList()
        for (i in mTypes.indices) {
            items.add(DateTimeUtil.getDateTimeStr(mTypes[i], mTimes[i], this))
        }
        return items
    }

    companion object {
        const val TAG: String = "DelayReminderActivity"

        @JvmStatic
        fun getOpenIntent(context: Context?, thingId: Long, position: Int, color: Int): Intent {
            return getOpenIntent(context, thingId, position, ThingBackground.pure(color))
        }

        /**
         * Phase 8: ThingBackground-aware open intent. Carries both KEY_COLOR (int)
         * and KEY_BACKGROUND (JSON), so the chooser-dialog accent can render the
         * gradient when the source thing has one.
         */
        @JvmStatic
        fun getOpenIntent(
            context: Context?, thingId: Long, position: Int, bg: ThingBackground?
        ): Intent {
            val intent = Intent(context, DelayReminderActivity::class.java)
            intent.putExtra(Def.Communication.KEY_ID, thingId)
            intent.putExtra(Def.Communication.KEY_POSITION, position)
            if (bg != null) {
                intent.putExtra(Def.Communication.KEY_COLOR, bg.representativeColor())
                intent.putExtra(Def.Communication.KEY_BACKGROUND, bg.toJson())
            }
            return intent
        }
    }
}
