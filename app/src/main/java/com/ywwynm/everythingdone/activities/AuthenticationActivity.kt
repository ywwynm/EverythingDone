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
import com.ywwynm.everythingdone.helpers.AuthenticationHelper
import com.ywwynm.everythingdone.helpers.RemoteActionHelper
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.services.DoingService

/**
 * Created by ywwynm on 2016/6/21
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * An Activity used when user operated a private thing.
 */
open class AuthenticationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent: Intent = getIntent()

        val id = intent.getLongExtra(Def.Communication.KEY_ID, -1)
        if (App.getDoingThingId() > 0 && App.getDoingThingId() == id) {
            startActivity(DoingActivity.getOpenIntent(this, true))
            finish()
            return
        }

        val position = intent.getIntExtra(Def.Communication.KEY_POSITION, -1)

        val pair: Pair<Thing, Int> = App.getThingAndPosition(this, id, position)!!

        if (pair.first == null) {
            finish()
            return
        }

        tryToAuthenticate(pair.first, pair.second ?: -1)
    }

    private fun tryToAuthenticate(thing: Thing, position: Int) {
        val intent: Intent = getIntent()
        val action: String? = intent.action
        if (thing.isPrivate()) {
            val cp: String? = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
                .getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)
            if (cp == null) {
                // I hope this will never happen, directly act for the time being
                act(action, thing, position)
                return
            }

            val title: String? = intent.getStringExtra(Def.Communication.KEY_TITLE)
            AuthenticationHelper.authenticate(
                this, thing.getBackground(), title, cp,
                object : AuthenticationHelper.AuthenticationCallback {
                    override fun onAuthenticated() {
                        act(action, thing, position)
                    }

                    override fun onCancel() {
                        finish()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
                        } else {
                            overridePendingTransition(0, 0)
                        }
                    }
                }
            )
        } else {
            act(action, thing, position)
        }
    }

    private fun act(action: String?, thing: Thing, position: Int) {
        if (Def.Communication.AUTHENTICATE_ACTION_FINISH.equals(action)) {
            actFinish(thing, position)
        } else if (Def.Communication.AUTHENTICATE_ACTION_DELAY.equals(action)) {
            actDelay(thing, position)
        } else if (Def.Communication.AUTHENTICATE_ACTION_START_DOING.equals(action)) {
            actStartDoing(thing, position)
        } else {
            actView()
        }
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }

    private fun actFinish(thing: Thing, position: Int) {
        if (thing.type != Thing.HABIT) { // reminder or goal
            RemoteActionHelper.finishReminder(this, thing, position)
        } else {
            val time = getIntent().getLongExtra(Def.Communication.KEY_TIME, -1)
            RemoteActionHelper.finishHabitOnce(this, thing, position, time)
        }
    }

    private fun actDelay(thing: Thing, position: Int) {
        val intent = DelayReminderActivity.getOpenIntent(
            this, thing.id, position, thing.getBackground()
        )
        startActivity(intent)
    }

    private fun actStartDoing(thing: Thing, position: Int) {
        val hrTime = getIntent().getLongExtra(Def.Communication.KEY_TIME, -1)
        val intent = StartDoingActivity.getOpenIntent(
            this, thing.id, position, thing.getBackground(),
            DoingService.START_TYPE_ALARM, hrTime
        )
        startActivity(intent)
    }

    private fun actView() {
        val intent: Intent = getIntent()
        val id = intent.getLongExtra(Def.Communication.KEY_ID, -1)
        val broadcastIntent = Intent(Def.Communication.BROADCAST_ACTION_FINISH_DETAILACTIVITY)
        broadcastIntent.putExtra(Def.Communication.KEY_ID, id)
        broadcastIntent.setPackage(packageName)
        sendBroadcast(broadcastIntent)

        intent.setClass(this, DetailActivity::class.java)
        startActivity(intent)
    }

    companion object {
        @JvmStatic
        fun getOpenIntent(
            context: Context?, senderName: String?, id: Long, position: Int,
            action: String?, actionTitle: String?
        ): Intent {
            if (App.getDoingThingId() == id) {
                return DoingActivity.getOpenIntent(context, true)
            } else {
                val intent = Intent(context, AuthenticationActivity::class.java)
                intent.setAction(action)
                intent.putExtra(Def.Communication.KEY_SENDER_NAME, senderName)
                intent.putExtra(Def.Communication.KEY_DETAIL_ACTIVITY_TYPE,
                    DetailActivity.UPDATE)
                intent.putExtra(Def.Communication.KEY_ID, id)
                intent.putExtra(Def.Communication.KEY_POSITION, position)
                intent.putExtra(Def.Communication.KEY_TITLE, actionTitle)
                return intent
            }
        }
    }
}
