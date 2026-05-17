package com.ywwynm.everythingdone.appwidgets;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DetailActivity;

/**
 * Created by ywwynm on 2016/7/27.
 * App Widget for creating new thing
 */
public class CreateWidget extends AppWidgetProvider {

    public static final String TAG = "CreateWidget";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            RemoteViews remoteViews = new RemoteViews(
                    context.getPackageName(), R.layout.app_widget_simple);
            remoteViews.setImageViewResource(
                    R.id.iv_widget_simple, R.drawable.widget_create_content);
            remoteViews.setContentDescription(
                    R.id.iv_widget_simple, context.getString(R.string.title_create_thing));

            // Phase 7: prefer ThingBackground for GRADIENT support.
            Intent contentIntent = DetailActivity.getOpenIntentForCreate(context, TAG,
                    App.newThingBackground != null
                            ? App.newThingBackground
                            : com.ywwynm.everythingdone.model.ThingBackground.pure(App.newThingColor));
            PendingIntent pendingIntent = PendingIntent.getActivity(context,
                    appWidgetId, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            remoteViews.setOnClickPendingIntent(R.id.iv_widget_simple, pendingIntent);
            appWidgetManager.updateAppWidget(appWidgetId, remoteViews);
        }
    }
}
