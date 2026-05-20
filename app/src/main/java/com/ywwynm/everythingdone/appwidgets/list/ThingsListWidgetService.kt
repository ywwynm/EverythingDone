package com.ywwynm.everythingdone.appwidgets.list

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.Thing

import java.util.ArrayList

/**
 * Created by qiizhang on 2016/8/8.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * adapter service for things list
 */
open class ThingsListWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return ThingsListViewFactory(getApplicationContext(), intent)
    }

    class ThingsListViewFactory internal constructor(context: Context?, intent: Intent?) : RemoteViewsFactory {

        private var mContext: Context? = context
        private var mIntent: Intent? = intent

        private var mAppWidgetId: Int = 0

        private var mThings: MutableList<Thing?>? = null

        override fun onCreate() {
            init()
        }

        private fun init() {
            val limit: Int = mIntent!!.getIntExtra(Def.Communication.KEY_LIMIT, 0)
            val things: List<Thing?>
            if (limit == App.getApp()!!.getLimit() && !App.isSearching) {
                val thingManager: ThingManager = ThingManager.getInstance(mContext)!!
                things = thingManager.getThings()!!
            } else {
                val thingDAO: ThingDAO = ThingDAO.getInstance(mContext)!!
                things = thingDAO.getThingsForDisplay(limit)!!
            }

            mThings = ArrayList()
            for (thing in things) {
                if (thing!!.type != Thing.HEADER) {
                    mThings!!.add(Thing(thing))
                }
            }

            mAppWidgetId = mIntent!!.getIntExtra(Def.Communication.KEY_WIDGET_ID, 0)
        }

        override fun onDataSetChanged() {
            init()
        }

        override fun onDestroy() { }

        override fun getCount(): Int {
            return mThings!!.size
        }

        override fun getViewAt(position: Int): RemoteViews? {
            if (position < -1 || position >= getCount()) {
                return null
            }
            val thing: Thing = mThings!!.get(position)!!
            val rv: RemoteViews = AppWidgetHelper.createRemoteViewsForThingsListItem(
                    mContext, thing, mAppWidgetId)
            val intent: Intent = Intent()
            intent.putExtra(Def.Communication.KEY_ID, thing.id)
            intent.putExtra(Def.Communication.KEY_POSITION, position + 1)
            rv.setOnClickFillInIntent(R.id.root_widget_thing, intent)
            return rv
        }

        override fun getLoadingView(): RemoteViews? {
            return null
        }

        override fun getViewTypeCount(): Int {
            return 1
        }

        override fun getItemId(position: Int): Long {
            if (position < 0 || position > mThings!!.size - 1) {
                return -1L
            }
            return mThings!!.get(position)!!.id
        }

        override fun hasStableIds(): Boolean {
            return false
        }
    }
}
