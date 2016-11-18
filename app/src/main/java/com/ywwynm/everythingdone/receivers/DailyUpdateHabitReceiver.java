package com.ywwynm.everythingdone.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper;
import com.ywwynm.everythingdone.model.Thing;

public class DailyUpdateHabitReceiver extends BroadcastReceiver {

    public static final String TAG = "DailyUpdateHabitReceiver";

    public DailyUpdateHabitReceiver() {
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        updateHabits(context);
        sendBroadcastToMainUI(context);
    }

    private void updateHabits(Context context) {
        ThingDAO thingDAO = ThingDAO.getInstance(context);
        HabitDAO habitDAO = HabitDAO.getInstance(context);
        Cursor cursor = thingDAO.getThingsCursor(
                "type=" + Thing.HABIT + " and state=" + Thing.UNDERWAY);
        while (cursor.moveToNext()) {
            long id = cursor.getLong(0);
            habitDAO.dailyUpdate(id);
            AppWidgetHelper.updateSingleThingAppWidgets(context, id);
        }
        AppWidgetHelper.updateThingsListAppWidgetsForType(context, Thing.HABIT);
        cursor.close();
    }

    private void sendBroadcastToMainUI(Context context) {
        App.setJustNotifyAll(true);
        Intent broadcastIntent = new Intent(
                Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI);
        broadcastIntent.putExtra(Def.Communication.KEY_RESULT_CODE,
                Def.Communication.RESULT_JUST_NOTIFY_DATASET_CHANGED);
        context.sendBroadcast(broadcastIntent);
    }
}
