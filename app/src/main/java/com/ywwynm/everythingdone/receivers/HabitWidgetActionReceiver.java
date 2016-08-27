package com.ywwynm.everythingdone.receivers;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;
import android.widget.Toast;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.HabitRecord;
import com.ywwynm.everythingdone.model.Thing;

import java.util.List;

/**
 * Created by ywwynm on 2016/8/25.
 * habit widget action BroadcastReceiver
 */
@SuppressLint("LongLogTag")
public class HabitWidgetActionReceiver extends BroadcastReceiver {

    public static final String TAG = "HabitWidgetActionReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Def.Communication.WIDGET_ACTION_FINISH.equals(intent.getAction())) {
            int position = intent.getIntExtra(Def.Communication.KEY_POSITION, -1);

            HabitDAO habitDAO = HabitDAO.getInstance(context);
            long id = intent.getLongExtra(Def.Communication.KEY_ID, -1);
            if (id == -1) {
                Log.e(TAG, "user wants to finish habit once while id is empty or -1!");
                return;
            }
            Habit habit = habitDAO.getHabitById(id);
            if (habit == null) {
                Log.e(TAG, "user wants to finish habit once while habit is null!");
                return;
            }


            if (position == -1) {
                position = ThingManager.getInstance(context).getPosition(id);
            }

            if (habit.allowFinish()) {
                HabitRecord habitRecord = habitDAO.finishOneTime(habit);
                sendBroadCastToUpdateMainUI(context, id, position);
                AppWidgetHelper.updateSingleThingAppWidgets(context, id);
                AppWidgetHelper.updateThingsListAppWidgetsForType(context, Thing.HABIT);
                NotificationManagerCompat.from(context).cancel((int) habitRecord.getHabitReminderId());
            } else {
                if (habit.getRecord().isEmpty() && habit.getRemindedTimes() == 0) {
                    Toast.makeText(context, R.string.sb_cannot_finish_habit_first_time,
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(context, R.string.sb_cannot_finish_habit_more_times,
                            Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void sendBroadCastToUpdateMainUI(Context context, long id, int position) {
        Thing thing;
        if (position == -1) {
            thing = ThingDAO.getInstance(context).getThingById(id);
        } else {
            ThingManager thingManager = ThingManager.getInstance(context);
            List<Thing> things = thingManager.getThings();
            final int size = things.size();
            thing = null;
            if (position > size - 1 || things.get(position).getId() != id) {
                for (int i = 0; i < size; i++) {
                    thing = things.get(i);
                    if (thing.getId() == id) {
                        position = i;
                        break;
                    }
                }
            } else {
                thing = things.get(position);
            }
        }

        if (thing == null) {
            Log.e(TAG, "sendBroadCastToUpdateMainUI but thing is null");
            return;
        }

        App.setSomethingUpdatedSpecially(true);
        Intent broadcastIntent = new Intent(
                Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI);
        broadcastIntent.putExtra(Def.Communication.KEY_RESULT_CODE,
                Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME);
        broadcastIntent.putExtra(Def.Communication.KEY_THING, thing);
        broadcastIntent.putExtra(Def.Communication.KEY_POSITION, position);
        broadcastIntent.putExtra(Def.Communication.KEY_TYPE_BEFORE, thing.getType());
        context.sendBroadcast(broadcastIntent);
    }
}
