package com.ywwynm.everythingdone.appwidgets.single;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.support.v4.util.Pair;
import android.util.Log;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper;
import com.ywwynm.everythingdone.database.AppWidgetDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.helpers.RemoteActionHelper;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.model.ThingWidgetInfo;

import java.util.List;

/**
 * Created by qiizhang on 2016/8/1.
 * basic single thing widget
 */
public abstract class BaseThingWidget extends AppWidgetProvider {

    protected abstract String getTag();

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(getTag(), "onReceive(context, intent) is called, action[" + intent.getAction() + "]");
        if (Def.Communication.BROADCAST_ACTION_UPDATE_CHECKLIST.equals(intent.getAction())) {
            long id = intent.getLongExtra(Def.Communication.KEY_ID, -1);
            int itemPos = intent.getIntExtra(Def.Communication.KEY_POSITION, 0);
            RemoteActionHelper.toggleChecklistItem(context, id, itemPos);
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        ThingManager thingManager = ThingManager.getInstance(context);
        ThingDAO thingDAO = ThingDAO.getInstance(context);
        AppWidgetDAO appWidgetDAO = AppWidgetDAO.getInstance(context);
        for (int appWidgetId : appWidgetIds) {
            updateSingleThingAppWidget(
                    thingManager, thingDAO, appWidgetDAO, appWidgetManager, context, appWidgetId);
        }
    }

    private void updateSingleThingAppWidget(
            ThingManager thingManager, ThingDAO thingDAO, AppWidgetDAO appWidgetDAO,
            AppWidgetManager appWidgetManager, Context context, int appWidgetId) {
        final String TAG = getTag();
        Log.i(TAG, "updateSingleThingAppWidget is called, appWidgetId[" + appWidgetId + "]");
        ThingWidgetInfo thingWidgetInfo = appWidgetDAO.getThingWidgetInfoById(appWidgetId);
        if (thingWidgetInfo == null) {
            Log.e(TAG, "updateSingleThingAppWidget but thingWidgetInfo is null");
            return;
        }

        Pair<Integer, Thing> pair = getThingAndPositionFromManager(
                thingManager, thingWidgetInfo.getThingId());
        int position;
        Thing thing;
        if (pair == null) {
            position = -1;
            thing = thingDAO.getThingById(thingWidgetInfo.getThingId());
            if (thing == null) {
                Log.e(TAG, "updateSingleThingAppWidget but thing is null");
                return;
            }
        } else {
            position = pair.first;
            thing = pair.second;
        }

        // This line is necessary if there is a checklist
        // _(:3」∠)_, it seems this line should be written above next line....
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.lv_thing_check_list);

        Log.e(TAG, "updateSingleThingAppWidget, thing.content[" + thing.getContent() + "]");
        appWidgetManager.updateAppWidget(appWidgetId,
                AppWidgetHelper.createRemoteViewsForSingleThing(
                        context, thing, position, appWidgetId, getClass()));
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        AppWidgetDAO appWidgetDAO = AppWidgetDAO.getInstance(context);
        for (int appWidgetId : appWidgetIds) {
            appWidgetDAO.delete(appWidgetId);
        }
    }

    private Pair<Integer, Thing> getThingAndPositionFromManager(ThingManager thingManager, long thingId) {
        Pair<Integer, Thing> pair = null;
        List<Thing> things = thingManager.getThings();
        final int size = things.size();
        for (int i = 0; i < size; i++) {
            Thing thing = things.get(i);
            if (thing.getId() == thingId) {
                pair = new Pair<>(i, thing);
            }
        }
        return pair;
    }

}
