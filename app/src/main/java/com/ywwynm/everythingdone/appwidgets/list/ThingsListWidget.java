package com.ywwynm.everythingdone.appwidgets.list;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper;
import com.ywwynm.everythingdone.database.AppWidgetDAO;
import com.ywwynm.everythingdone.model.ThingWidgetInfo;

/**
 * Created by ywwynm on 2016/8/7.
 * App widget that shows a list of things
 */
public class ThingsListWidget extends AppWidgetProvider {

    public static final String TAG = "ThingsListWidget";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.onUpdate(context, appWidgetManager, appWidgetIds);
        AppWidgetDAO appWidgetDAO = AppWidgetDAO.getInstance(context);
        for (int appWidgetId : appWidgetIds) {
            ThingWidgetInfo info = appWidgetDAO.getThingWidgetInfoById(appWidgetId);
            if (info == null) {
                break;
            }

            // notify data set changed for things list
            // _(:3」∠)_, it seems this line should be written above next line....
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.lv_things_list);

            int limit = -1 * (int) info.getThingId() - 1;
            appWidgetManager.updateAppWidget(appWidgetId,
                    AppWidgetHelper.createRemoteViewsForThingsList(context, limit, appWidgetId));

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
}
