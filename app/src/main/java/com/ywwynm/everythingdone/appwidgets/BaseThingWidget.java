package com.ywwynm.everythingdone.appwidgets;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.support.v4.util.Pair;

import com.ywwynm.everythingdone.database.AppWidgetDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.helpers.AppWidgetHelper;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Thing;

import java.util.List;

/**
 * Created by qiizhang on 2016/8/1.
 * basic single thing widget
 */
public class BaseThingWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        ThingManager thingManager = ThingManager.getInstance(context);
        ThingDAO thingDAO = ThingDAO.getInstance(context);
        AppWidgetDAO appWidgetDAO = AppWidgetDAO.getInstance(context);
        for (int appWidgetId : appWidgetIds) {
            long id = appWidgetDAO.getThingIdByAppWidgetId(appWidgetId);
            Pair<Integer, Thing> pair = getThingAndPositionFromManager(thingManager, id);
            int position;
            Thing thing;
            if (pair == null) {
                position = -1;
                thing = thingDAO.getThingById(id);
                if (thing == null) {
                    return;
                }
            } else {
                position = pair.first;
                thing = pair.second;
            }
            appWidgetManager.updateAppWidget(appWidgetId,
                    AppWidgetHelper.createRemoteViewsForSingleThing(context, thing, position, appWidgetId));
        }
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
