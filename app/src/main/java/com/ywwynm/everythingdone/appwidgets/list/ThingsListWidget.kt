@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.appwidgets.list

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.database.AppWidgetDAO
import com.ywwynm.everythingdone.model.ThingWidgetInfo

/**
 * Created by ywwynm on 2016/8/7.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * App widget that shows a list of things
 */
open class ThingsListWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val appWidgetDAO: AppWidgetDAO = AppWidgetDAO.getInstance(context)!!
        for (appWidgetId in appWidgetIds) {
            val info: ThingWidgetInfo = appWidgetDAO.getThingWidgetInfoById(appWidgetId) ?: break

            // notify data set changed for things list
            // _(:3」∠)_, it seems this line should be written above next line....
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.lv_things_list)

            val limit: Int = -1 * info.thingId.toInt() - 1
            appWidgetManager.updateAppWidget(appWidgetId,
                    AppWidgetHelper.createRemoteViewsForThingsList(context, limit, appWidgetId))

        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val appWidgetDAO: AppWidgetDAO = AppWidgetDAO.getInstance(context)!!
        for (appWidgetId in appWidgetIds) {
            appWidgetDAO.delete(appWidgetId)
        }
    }

    companion object {
        const val TAG: String = "ThingsListWidget"
    }
}
