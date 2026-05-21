package com.ywwynm.everythingdone.appwidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.ShortcutActivity

/**
 * Created by ywwynm on 2016/10/24.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * App Widget for checking upcoming thing
 */
open class CheckUpcomingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val remoteViews = RemoteViews(
                    context.packageName, R.layout.app_widget_simple)
            remoteViews.setImageViewResource(
                    R.id.iv_widget_simple, R.drawable.widget_check_upcoming_content)
            remoteViews.setContentDescription(
                    R.id.iv_widget_simple, context.getString(R.string.act_shortcut_check_upcoming))

            val contentIntent = Intent(context, ShortcutActivity::class.java)
            // Well, this is not very elegant but I don't want to change more code.
            // And today is programmer's day, who cares about this?
            contentIntent.setAction(Def.Communication.SHORTCUT_ACTION_CHECK_UPCOMING)
            val pendingIntent: PendingIntent = PendingIntent.getActivity(context,
                    appWidgetId, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            remoteViews.setOnClickPendingIntent(R.id.iv_widget_simple, pendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
        }
    }

    companion object {
        const val TAG: String = "CreateWidget"
    }
}
