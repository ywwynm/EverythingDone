package com.ywwynm.everythingdone.helpers;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.model.Thing;

/**
 * Created by ywwynm on 2016/7/27.
 * helper class for app widgets, especially for showing thing
 */
public class AppWidgetHelper {

    public static final String TAG = "AppWidgetHelper";

    private AppWidgetHelper() {}

    public static RemoteViews createRemoteViewsForSingleThing(
            Context context, Thing thing, int position) {
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), R.layout.widget_thing);
        setAppearance(remoteViews, thing);
        final Intent contentIntent = DetailActivity.getOpenIntentForUpdate(
                context, TAG, thing.getId(), position);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, (int) thing.getId(), contentIntent, 0);
        remoteViews.setOnClickPendingIntent(R.id.ll_widget_thing, pendingIntent);
        return remoteViews;
    }

    public static void setAppearance(RemoteViews remoteViews, Thing thing) {

    }

}
