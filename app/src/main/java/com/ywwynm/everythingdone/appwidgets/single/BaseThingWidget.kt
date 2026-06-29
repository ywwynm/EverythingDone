@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.appwidgets.single

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.util.Pair
import android.util.Log

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.database.AppWidgetDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.helpers.RemoteActionHelper
import com.ywwynm.everythingdone.helpers.ThingPrivacyResolver
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingWidgetInfo

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
        for (appWidgetId in appWidgetIds) {
            updateSingleThingAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        Log.i(getTag(), "onAppWidgetOptionsChanged is called, appWidgetId[$appWidgetId]")
        updateSingleThingAppWidget(context, appWidgetManager, appWidgetId)
    }

    private fun updateSingleThingAppWidget(
            context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val thingManager: ThingManager = ThingManager.getInstance(context)!!
        val thingDAO: ThingDAO = ThingDAO.getInstance(context)!!
        val appWidgetDAO: AppWidgetDAO = AppWidgetDAO.getInstance(context)!!
        updateSingleThingAppWidget(
                thingManager, thingDAO, appWidgetDAO, appWidgetManager, context, appWidgetId)
    }

    private fun updateSingleThingAppWidget(
            thingManager: ThingManager, thingDAO: ThingDAO, appWidgetDAO: AppWidgetDAO,
            appWidgetManager: AppWidgetManager, context: Context, appWidgetId: Int) {
        val TAG: String = getTag()!!
        Log.i(TAG, "updateSingleThingAppWidget is called, appWidgetId[$appWidgetId]")
        val thingWidgetInfo: ThingWidgetInfo? = appWidgetDAO.getThingWidgetInfoById(appWidgetId)
        if (thingWidgetInfo == null) {
            Log.e(TAG, "updateSingleThingAppWidget but thingWidgetInfo is null")
            return
        }

        val pair: Pair<Int, Thing>? = getThingAndPositionFromManager(
                thingManager, thingWidgetInfo.thingId)
        val position: Int
        val thing: Thing
        if (pair == null) {
            position = -1
            val t: Thing? = thingDAO.getThingById(thingWidgetInfo.thingId)
            if (t == null) {
                Log.e(TAG, "updateSingleThingAppWidget but thing is null")
                return
            }
            thing = t
        } else {
            position = pair.first!!
            thing = pair.second!!
        }

        // This line is necessary if there is a checklist
        // _(:3」∠)_, it seems this line should be written above next line....
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.lv_thing_check_list)

        Log.e(TAG, "updateSingleThingAppWidget, thing.content[" + thing.content + "]")
        // 经集中解析：处于私密文件夹内、自身无前缀的记事也按私密渲染（隐藏内容、显示锁），
        // 避免单一小部件在桌面明文泄露（见 ADR 0011）。
        val safeThing = ThingPrivacyResolver.resolveForPresentation(context, thing)
        appWidgetManager.updateAppWidget(appWidgetId,
                AppWidgetHelper.createRemoteViewsForSingleThing(
                        context, safeThing, position, appWidgetId, this::class.java))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val appWidgetDAO: AppWidgetDAO = AppWidgetDAO.getInstance(context)!!
        for (appWidgetId in appWidgetIds) {
            appWidgetDAO.delete(appWidgetId)
        }
    }

    private fun getThingAndPositionFromManager(thingManager: ThingManager, thingId: Long): Pair<Int, Thing>? {
        var pair: Pair<Int, Thing>? = null
        val things: List<Thing?> = thingManager.getThings()!!
        val size: Int = things.size
        for (i in 0 until size) {
            val thing: Thing = things[i]!!
            if (thing.id == thingId) {
                pair = Pair(i, thing)
            }
        }
        return pair
    }

}
