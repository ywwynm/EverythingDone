package com.ywwynm.everythingdone.appwidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.support.v4.util.Pair;
import android.widget.RemoteViews;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Thing;

import java.util.List;

/**
 * Created by ywwynm on 2016/7/27.
 * App Widget for showing a single thing
 */
public class ThingWidget extends AppWidgetProvider {

    public static final String TAG = "ThingWidget";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        ThingManager thingManager = ThingManager.getInstance(context);
        ThingDAO thingDAO = ThingDAO.getInstance(context);
        for (int appWidgetId : appWidgetIds) {
            Pair<Integer, Thing> pair = getThingAndPositionFromManager(thingManager, appWidgetId);
            int position;
            Thing thing;
            if (pair == null) {
                position = -1;
                thing = thingDAO.getThingById(appWidgetId);
                if (thing == null) continue;
            } else {
                position = pair.first;
                thing = pair.second;
            }
            appWidgetManager.updateAppWidget(appWidgetId,
                    createRemoteViews(context, thing, position));
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

    private RemoteViews createRemoteViews(Context context, Thing thing, int position) {
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), R.layout.widget_thing);
        final Intent contentIntent = DetailActivity.getOpenIntentForUpdate(
                context, TAG, thing.getId(), position);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, (int) thing.getId(), contentIntent, 0);
        return remoteViews;
    }
}
