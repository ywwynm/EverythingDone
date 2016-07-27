package com.ywwynm.everythingdone.appwidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.utils.DisplayUtil;

/**
 * Created by ywwynm on 2016/7/27.
 * App Widget for creating new thing
 */
public class CreateWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_create);

            Intent contentIntent = new Intent(context, DetailActivity.class);
            contentIntent.putExtra(Def.Communication.KEY_SENDER_NAME, App.class.getName());
            contentIntent.putExtra(Def.Communication.KEY_DETAIL_ACTIVITY_TYPE,
                    DetailActivity.CREATE);

            int color = DisplayUtil.getRandomColor(context);
            while (color == App.newThingColor) {
                color = DisplayUtil.getRandomColor(context);
            }
            App.newThingColor = color;
            contentIntent.putExtra(Def.Communication.KEY_COLOR, color);

            PendingIntent pendingIntent = PendingIntent.getActivity(context,
                    appWidgetId, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            views.setOnClickPendingIntent(R.id.tv_widget_create_as_bt, pendingIntent);
            appWidgetManager.updateAppWidget(appWidgetId, views);
        }
    }
}
