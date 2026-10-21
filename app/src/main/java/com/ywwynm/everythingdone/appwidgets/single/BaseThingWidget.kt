@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.appwidgets.single

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.appwidgets.AppWidgetUpdateWorker
import com.ywwynm.everythingdone.database.AppWidgetDAO
import com.ywwynm.everythingdone.helpers.RemoteActionHelper

/**
 * Created by qiizhang on 2016/8/1.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * basic single thing widget
 */
abstract class BaseThingWidget : AppWidgetProvider() {

    protected abstract fun getTag(): String?

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(getTag(), "onReceive(context, intent) is called, action[" + intent.action + "]")
        if (Def.Communication.BROADCAST_ACTION_UPDATE_CHECKLIST == intent.action) {
            val id: Long = intent.getLongExtra(Def.Communication.KEY_ID, -1)
            val itemPos: Int = intent.getIntExtra(Def.Communication.KEY_POSITION, 0)
            RemoteActionHelper.toggleChecklistItem(context, id, itemPos)
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        AppWidgetUpdateWorker.enqueue(context, appWidgetIds, goAsync())
    }

    override fun onAppWidgetOptionsChanged(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        Log.i(getTag(), "onAppWidgetOptionsChanged is called, appWidgetId[$appWidgetId]")
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

}
