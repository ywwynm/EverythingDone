package com.ywwynm.everythingdone.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.widget.Toast;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.helpers.AuthenticationHelper;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;

import java.util.List;

/**
 * Created by ywwynm on 2016/6/21
 * An Activity used when user operated a private thing.
 */
public class AuthenticationActivity extends AppCompatActivity {

    public static Intent getOpenIntent(
            Context context, String senderName, long id, int position,
            String action, String actionTitle) {
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

    private App mApp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_authentication);

        mApp = App.getApp();
        Intent intent = getIntent();

        long id = intent.getLongExtra(Def.Communication.KEY_ID, -1);
        int position = intent.getIntExtra(Def.Communication.KEY_POSITION, -1);
        ThingManager thingManager = ThingManager.getInstance(mApp);
        ThingDAO thingDAO = ThingDAO.getInstance(mApp);
        Thing thing = null;
        if (position == -1) {
            position = thingManager.getPosition(id);
            if (position == -1) {
                thing = thingDAO.getThingById(id);
            } else {
                thing = thingManager.getThings().get(position);
            }
        } else {
            List<Thing> things = thingManager.getThings();
            final int size = things.size();
            if (position >= size || things.get(position).getId() != id) {
                for (int i = 0; i < size; i++) {
                    Thing temp = things.get(i);
                    if (temp.getId() == id) {
                        thing = temp;
                        position = i;
                        break;
                    }
                }
                if (thing == null) {
                    thing = thingDAO.getThingById(id);
                    position = -1;
                }
            } else {
                thing = things.get(position);
            }
        }

        if (thing == null) {
            finish();
            return;
        }

        tryToAuthenticate(thing, position);
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
            int color = thing.getColor();
            AuthenticationHelper.authenticate(
                    this, color, title, cp,
                    new AuthenticationHelper.AuthenticationCallback() {
                        @Override
                        public void onAuthenticated() {
                            act(action, thing, position);
                        }

                        @Override
                        public void onCancel() {
                            finish();
                            overridePendingTransition(0, 0);
                        }
                    });
        } else {
            act(action, thing, position);
        }
    }

    private void act(String action, Thing thing, int position) {
        switch (action) {
            case Def.Communication.AUTHENTICATE_ACTION_FINISH:
                actFinish(thing, position);
                break;
            case Def.Communication.AUTHENTICATE_ACTION_DELAY:
                actDelay(thing, position);
                break;
            case Def.Communication.AUTHENTICATE_ACTION_VIEW:
            default:
                actView();
                break;
        }
        finish();
        overridePendingTransition(0, 0);
    }

    private void actFinish(Thing thing, int position) {
        if (thing.getType() != Thing.HABIT) { // reminder or goal
            actFinishReminder(thing, position);
        } else {
            actFinishHabit(thing, position);
        }
    }

    private void actFinishReminder(Thing thing, int position) {
        if (position == -1) {
            thing = Thing.getSameCheckStateThing(thing, Thing.UNDERWAY, Thing.FINISHED);
            ThingDAO thingDAO = ThingDAO.getInstance(mApp);
            long hId = thingDAO.getHeaderId();
            thingDAO.updateState(thing, thing.getLocation(), Thing.UNDERWAY, Thing.FINISHED,
                    true,  /* handleNotifyEmpty  */
                    true,  /* handleCurrentLimit */
                    false, /* toUndo             */
                    hId,
                    true   /* shouldUpdateHeader */);
        }
        App.setSomethingUpdatedSpecially(true);
        sendBroadCastToUpdateUiEverywhere(thing, position,
                Def.Communication.RESULT_UPDATE_THING_STATE_DIFFERENT);
    }

    private void actFinishHabit(Thing thing, int position) {
        long time = getIntent().getLongExtra(Def.Communication.KEY_TIME, -1);
        if (time == -1) {
            return;
        }
        HabitDAO habitDAO = HabitDAO.getInstance(mApp);
        long id = thing.getId();
        Habit habit = habitDAO.getHabitById(id);
        if (habit.allowFinish(time)) {
            habitDAO.finishOneTime(habit);
            sendBroadCastToUpdateUiEverywhere(thing, position,
                    Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME);
        } else {
            Toast.makeText(this, R.string.error_cannot_finish_habit_this_time,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void actDelay(Thing thing, int position) {
        long id = thing.getId();
        thing.setUpdateTime(System.currentTimeMillis());
        if (position == -1) {
            ThingDAO.getInstance(mApp).update(thing.getType(), thing, false, false);
        } else {
            ThingManager.getInstance(mApp).update(thing.getType(), thing, position, false);
        }
        ReminderDAO dao = ReminderDAO.getInstance(mApp);
        Reminder reminder = dao.getReminderById(id);
        reminder.setNotifyTime(System.currentTimeMillis() + 10 * 60 * 1000);
        reminder.setNotifyMillis(System.currentTimeMillis() - reminder.getNotifyTime()
                + reminder.getNotifyMillis() + 10 * 60 * 1000);
        reminder.setState(Reminder.UNDERWAY);
        reminder.setUpdateTime(System.currentTimeMillis());
        dao.update(reminder);

        App.setSomethingUpdatedSpecially(true);
        sendBroadCastToUpdateUiEverywhere(thing, position,
                Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME);
    }

    private void actView() {
        Intent intent = getIntent();
        intent.setClass(this, DetailActivity.class);
        startActivity(intent);
    }

    private void sendBroadCastToUpdateUiEverywhere(Thing thing, int position, int resultCode) {
        Intent broadcastIntent = new Intent(
                Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI);
        broadcastIntent.putExtra(Def.Communication.KEY_RESULT_CODE, resultCode);
        broadcastIntent.putExtra(Def.Communication.KEY_THING, thing);
        broadcastIntent.putExtra(Def.Communication.KEY_POSITION, position);
        if (resultCode == Def.Communication.RESULT_UPDATE_THING_STATE_DIFFERENT) {
            broadcastIntent.putExtra(Def.Communication.KEY_STATE_AFTER, Thing.FINISHED);
            if (position != -1) {
                ThingManager thingManager = ThingManager.getInstance(mApp);
                broadcastIntent.putExtra(Def.Communication.KEY_CALL_CHANGE,
                        thingManager.updateState(thing, position, thing.getLocation(), Thing.UNDERWAY,
                                Thing.FINISHED, false, true));
            }
        } else {
            broadcastIntent.putExtra(Def.Communication.KEY_TYPE_BEFORE, thing.getType());
        }
        sendBroadcast(broadcastIntent);

        AppWidgetHelper.updateSingleThingAppWidgets(mApp, thing.getId());
        AppWidgetHelper.updateThingsListAppWidgetsForType(mApp, thing.getType());
    }
}
