package com.ywwynm.everythingdone.appwidgets.single

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.Thing

/**
 * Created by qiizhang on 2016/8/1.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * adapter service for checklist in a thing
 */
open class ChecklistWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return ChecklistViewFactory(getApplicationContext(), intent)
    }

    class ChecklistViewFactory internal constructor(context: Context?, intent: Intent?) : RemoteViewsFactory {

        private var mContext: Context? = context
        private var mIntent: Intent? = intent

        private var mThing: Thing? = null
        private var mItems: MutableList<String?>? = null

        override fun onCreate() {
            init()
        }

        private fun init() {
            Log.i(TAG, "init()")
            val id: Long = mIntent!!.getLongExtra(Def.Communication.KEY_ID, -1)
            val thingManager: ThingManager = ThingManager.getInstance(mContext)!!
            mThing = thingManager.getThingById(id)
            if (mThing == null) {
                val thingDAO: ThingDAO = ThingDAO.getInstance(mContext)!!
                mThing = thingDAO.getThingById(id)
                if (mThing == null) {
                    Log.i(TAG, "thing is null!")
                    return
                }
            }

            Log.i(TAG, "mThing.content[" + mThing!!.content + "]")
            mItems = CheckListHelper.toCheckListItems(mThing!!.content, false)
            mItems!!.remove("2")
            mItems!!.remove("3")
            mItems!!.remove("4")
        }

        override fun onDataSetChanged() {
            Log.i(TAG, "onDataSetChanged()")
            init()
        }

        override fun onDestroy() {

        }

        override fun getCount(): Int {
            return mItems!!.size
        }

        override fun getViewAt(position: Int): RemoteViews? {
            val count: Int = getCount()
            if (position < 0 || position >= count) {
                return null
            }
            val rv: RemoteViews = AppWidgetHelper.createRemoteViewsForChecklistItem(
                    mContext, mItems!!.get(position), count, true, mThing)
            setupEvents(rv, position)
            return rv
        }

        private fun setupEvents(rv: RemoteViews, position: Int) {
            if (mThing!!.state == Thing.UNDERWAY) {
                val intent: Intent = Intent(Def.Communication.BROADCAST_ACTION_UPDATE_CHECKLIST)
                intent.putExtra(Def.Communication.KEY_ID, mThing!!.id)
                intent.putExtra(Def.Communication.KEY_POSITION, position)
                rv.setOnClickFillInIntent(LL_CHECK_LIST, intent)
            }
        }

        override fun getLoadingView(): RemoteViews? {
            return null
        }

        override fun getViewTypeCount(): Int {
            return 1
        }

        override fun getItemId(position: Int): Long {
            val count: Int = getCount()
            if (position < 0 || position >= count) {
                return -1L
            }
            return mItems!!.get(position)!!.hashCode().toLong()
        }

        override fun hasStableIds(): Boolean {
            return false
        }
    }

    companion object {
        const val TAG: String = "ChecklistWidgetService"

        private val LL_CHECK_LIST: Int = R.id.ll_check_list_tv
    }

}
