package com.ywwynm.everythingdone.appwidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity

/**
 * Created by ywwynm on 2016/7/27.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * App Widget for creating new thing
 */
open class CreateWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val remoteViews = RemoteViews(
                    context.packageName, R.layout.app_widget_simple)
            remoteViews.setImageViewResource(
                    R.id.iv_widget_simple, R.drawable.widget_create_content)
            remoteViews.setContentDescription(
                    R.id.iv_widget_simple, context.getString(R.string.title_create_thing))

            // Phase 7: prefer ThingBackground for GRADIENT support.
            val contentIntent: Intent = DetailActivity.getOpenIntentForCreate(context, TAG,
                    if (App.newThingBackground != null)
                            App.newThingBackground
                    else com.ywwynm.everythingdone.model.ThingBackground.pure(App.newThingColor))!!
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
