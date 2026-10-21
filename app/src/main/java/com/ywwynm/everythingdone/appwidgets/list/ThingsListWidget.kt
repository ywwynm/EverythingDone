@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.appwidgets.list

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.util.Log

import com.ywwynm.everythingdone.appwidgets.AppWidgetUpdateWorker
import com.ywwynm.everythingdone.database.AppWidgetDAO

/**
 * Created by ywwynm on 2016/8/7.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * App widget that shows a list of things
 */
open class ThingsListWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        AppWidgetUpdateWorker.enqueue(context, appWidgetIds, goAsync())
    }

    override fun onAppWidgetOptionsChanged(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        Log.i(TAG, "onAppWidgetOptionsChanged is called, appWidgetId[$appWidgetId]")
        AppWidgetUpdateWorker.enqueue(context, intArrayOf(appWidgetId), goAsync())
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val appWidgetDAO: AppWidgetDAO = AppWidgetDAO.getInstance(context)!!
        for (appWidgetId in appWidgetIds) {
            AppWidgetUpdateWorker.cancel(context, appWidgetId)
            appWidgetDAO.delete(appWidgetId)
        }
    }

    companion object {
        const val TAG: String = "ThingsListWidget"
    }
}
