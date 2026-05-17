package com.ywwynm.everythingdone.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import androidx.core.util.Pair;
import androidx.appcompat.app.AppCompatActivity;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.helpers.AuthenticationHelper;
import com.ywwynm.everythingdone.helpers.RemoteActionHelper;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.services.DoingService;

/**
 * Created by ywwynm on 2016/6/21
 * An Activity used when user operated a private thing.
 */
public class AuthenticationActivity extends AppCompatActivity {

    public static Intent getOpenIntent(
            Context context, String senderName, long id, int position,
            String action, String actionTitle) {
        if (App.getDoingThingId() == id) {
            return DoingActivity.getOpenIntent(context, true);
        } else {
            final Intent intent = new Intent(context, AuthenticationActivity.class);
            intent.setAction(action);
            intent.putExtra(Def.Communication.KEY_SENDER_NAME, senderName);
            intent.putExtra(Def.Communication.KEY_DETAIL_ACTIVITY_TYPE,
                    DetailActivity.UPDATE);
            intent.putExtra(Def.Communication.KEY_ID, id);
            intent.putExtra(Def.Communication.KEY_POSITION, position);
            intent.putExtra(Def.Communication.KEY_TITLE, actionTitle);
            return intent;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();

        long id = intent.getLongExtra(Def.Communication.KEY_ID, -1);
        if (App.getDoingThingId() == id) {
            startActivity(DoingActivity.getOpenIntent(this, true));
            finish();
            return;
        }

        int position = intent.getIntExtra(Def.Communication.KEY_POSITION, -1);

        Pair<Thing, Integer> pair = App.getThingAndPosition(this, id, position);

        if (pair.first == null) {
            finish();
            return;
        }

        tryToAuthenticate(pair.first, pair.second);
    }

    private void tryToAuthenticate(final Thing thing, final int position) {
        Intent intent = getIntent();
        final String action = intent.getAction();
        if (thing.isPrivate()) {
            String cp = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
                    .getString(Def.Meta.KEY_PRIVATE_PASSWORD, null);
            if (cp == null) {
                // I hope this will never happen, directly act for the time being
                act(action, thing, position);
                return;
            }

            String title = intent.getStringExtra(Def.Communication.KEY_TITLE);
            // Phase 8: pass full ThingBackground so the pattern-lock fallback
            // renders gradient title / right-button on a GRADIENT thing.
            AuthenticationHelper.authenticate(
                    this, thing.getBackground(), title, cp,
                    new AuthenticationHelper.AuthenticationCallback() {
                        @Override
                        public void onAuthenticated() {
                            act(action, thing, position);
                        }

                        @Override
                        public void onCancel() {
                            finish();
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
                            } else {
                                overridePendingTransition(0, 0);
                            }
                        }
                    });
        } else {
            act(action, thing, position);
        }
    }

    private void act(String action, Thing thing, int position) {
        if (Def.Communication.AUTHENTICATE_ACTION_FINISH.equals(action)) {
            actFinish(thing, position);
        } else if (Def.Communication.AUTHENTICATE_ACTION_DELAY.equals(action)) {
            actDelay(thing, position);
        } else if (Def.Communication.AUTHENTICATE_ACTION_START_DOING.equals(action)) {
            actStartDoing(thing, position);
        } else {
            actView();
        }
        finish();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
        } else {
            overridePendingTransition(0, 0);
        }
    }

    private void actFinish(Thing thing, int position) {
        if (thing.getType() != Thing.HABIT) { // reminder or goal
            RemoteActionHelper.finishReminder(this, thing, position);
        } else {
            long time = getIntent().getLongExtra(Def.Communication.KEY_TIME, -1);
            RemoteActionHelper.finishHabitOnce(this, thing, position, time);
        }
    }

    private void actDelay(Thing thing, int position) {
        // Phase 8: pass full ThingBackground so delay-reminder dialog renders gradient.
        Intent intent = DelayReminderActivity.getOpenIntent(
                this, thing.getId(), position, thing.getBackground());
        startActivity(intent);
    }

    private void actStartDoing(Thing thing, int position) {
        long hrTime = getIntent().getLongExtra(Def.Communication.KEY_TIME, -1);
        // Phase 8: pass full ThingBackground so start-doing dialog renders gradient.
        Intent intent = StartDoingActivity.getOpenIntent(
                this, thing.getId(), position, thing.getBackground(),
                DoingService.START_TYPE_ALARM, hrTime);
        startActivity(intent);
    }

    private void actView() {
        Intent intent = getIntent();
        long id = intent.getLongExtra(Def.Communication.KEY_ID, -1);
        Intent broadcastIntent = new Intent(Def.Communication.BROADCAST_ACTION_FINISH_DETAILACTIVITY);
        broadcastIntent.putExtra(Def.Communication.KEY_ID, id);
        broadcastIntent.setPackage(getPackageName());
        sendBroadcast(broadcastIntent);

        intent.setClass(this, DetailActivity.class);
        startActivity(intent);
    }
}
