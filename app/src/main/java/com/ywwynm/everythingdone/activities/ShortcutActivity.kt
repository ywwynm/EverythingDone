package com.ywwynm.everythingdone.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingWidgetInfo
import com.ywwynm.everythingdone.utils.LocaleUtil
import com.ywwynm.everythingdone.utils.ThingsSorter

import java.util.ArrayList
import java.util.Collections

/**
 * Created by ywwynm on 2016/10/22.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Used to handle shortcut actions.
 */
open class ShortcutActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleUtil.attachBaseContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action: String? = intent.action
        var openIntent: Intent? = null
        if (Def.Communication.SHORTCUT_ACTION_CREATE == action) {
            openIntent = DetailActivity.getOpenIntentForCreate(
                this, TAG,
                if (App.newThingBackground != null)
                    App.newThingBackground
                else ThingBackground.pure(App.newThingColor)
            )
            val folderId = intent.getLongExtra(Def.Communication.KEY_FOLDER_ID, Long.MIN_VALUE)
            if (folderId != Long.MIN_VALUE) {
                openIntent.putExtra(Def.Communication.KEY_FOLDER_ID, folderId)
            }
        } else if (Def.Communication.SHORTCUT_ACTION_CHECK_UPCOMING == action) {
            var canCheck = false
            val things: MutableList<Thing?> = ArrayList(ThingDAO.getInstance(this)!!
                .getThingsForDisplay(Def.ThingStatus.UNDERWAY, ThingWidgetInfo.TYPE_FILTER_ALL)!!)
            Collections.sort(things, ThingsSorter.getThingComparatorByAlarmTime(true))
            val thing: Thing = things[1]!! // 0 is header
            @Thing.Type val thingType = thing.type
            if (thingType == Thing.HABIT) {
                canCheck = true
            } else if (Thing.isReminderType(thingType)) {
                val reminder: Reminder? = ReminderDAO.getInstance(this)!!.getReminderById(thing.id)
                canCheck = !(reminder == null || reminder.state != Reminder.UNDERWAY)
            }

            if (canCheck) {
                openIntent = AuthenticationActivity.getOpenIntent(
                    this, TAG, thing.id, -1,
                    Def.Communication.AUTHENTICATE_ACTION_VIEW,
                    getString(R.string.check_private_thing)
                )
            } else {
                Toast.makeText(this, R.string.alert_shortcut_no_upcoming, Toast.LENGTH_LONG).show()
            }
        } else if (Def.Communication.SHORTCUT_ACTION_CHECK_STICKY == action) {
            val things: MutableList<Thing?> = ArrayList(ThingDAO.getInstance(this)!!
                .getThingsForDisplay(Def.ThingStatus.UNDERWAY, ThingWidgetInfo.TYPE_FILTER_ALL)!!)
            val thing: Thing = things[1]!!
            if (thing.location < 0) {
                openIntent = AuthenticationActivity.getOpenIntent(
                    this, TAG, thing.id, -1,
                    Def.Communication.AUTHENTICATE_ACTION_VIEW,
                    getString(R.string.check_private_thing)
                )
            } else {
                Toast.makeText(this, R.string.alert_shortcut_no_sticky, Toast.LENGTH_LONG).show()
            }
        }

        if (openIntent != null) {
            startActivity(openIntent)
        }

        finish()
    }

    companion object {
        const val TAG: String = "ShortcutActivity"
    }
}
