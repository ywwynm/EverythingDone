package com.ywwynm.everythingdone.activities;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.widget.Toast;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.ThingsSorter;

import java.util.Collections;
import java.util.List;

/**
 * Created by ywwynm on 2016/10/22.
 * Used to handle shortcut actions.
 */
public class ShortcutActivity extends AppCompatActivity {

    public static final String TAG = "ShortcutActivity";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String action = getIntent().getAction();
        Intent openIntent = null;
        if (Def.Communication.SHORTCUT_ACTION_CREATE.equals(action)) {
            openIntent = DetailActivity.getOpenIntentForCreate(
                    this, TAG, App.newThingColor);
        } else if (Def.Communication.SHORTCUT_ACTION_CHECK_UPCOMING.equals(action)) {
            boolean canCheck = false;
            List<Thing> things = ThingDAO.getInstance(this)
                    .getThingsForDisplay(Def.LimitForGettingThings.ALL_UNDERWAY);
            Collections.sort(things, ThingsSorter.getThingComparatorByAlarmTime(true));
            Thing thing = things.get(1); // 0 is header
            @Thing.Type int thingType = thing.getType();
            if (thingType == Thing.HABIT) {
                canCheck = true;
            } else if (Thing.isReminderType(thingType)) {
                Reminder reminder = ReminderDAO.getInstance(this).getReminderById(thing.getId());
                if (reminder == null || reminder.getState() != Reminder.UNDERWAY) {
                    canCheck = false;
                } else {
                    canCheck = true;
                }
            }

            if (canCheck) {
                openIntent = AuthenticationActivity.getOpenIntent(
                        this, TAG, thing.getId(), -1,
                        Def.Communication.AUTHENTICATE_ACTION_VIEW,
                        getString(R.string.check_private_thing));
            } else {
                Toast.makeText(this, R.string.alert_shortcut_no_upcoming, Toast.LENGTH_LONG).show();
            }
        } else if (Def.Communication.SHORTCUT_ACTION_CHECK_STICKY.equals(action)) {
            List<Thing> things = ThingDAO.getInstance(this)
                    .getThingsForDisplay(Def.LimitForGettingThings.ALL_UNDERWAY);
            Thing thing = things.get(1);
            if (thing.getLocation() < 0) {
                openIntent = AuthenticationActivity.getOpenIntent(
                        this, TAG, thing.getId(), -1,
                        Def.Communication.AUTHENTICATE_ACTION_VIEW,
                        getString(R.string.check_private_thing));
            } else {
                Toast.makeText(this, R.string.alert_shortcut_no_sticky, Toast.LENGTH_LONG).show();
            }
        }

        if (openIntent != null) {
            startActivity(openIntent);
        }

        finish();
    }
}
