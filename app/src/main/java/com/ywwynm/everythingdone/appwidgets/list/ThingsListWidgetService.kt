package com.ywwynm.everythingdone.appwidgets.list

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.database.AppWidgetDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.database.ThingFolderDAO
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingListEntry
import com.ywwynm.everythingdone.model.ThingWidgetInfo
import com.ywwynm.everythingdone.utils.ThingsSorter

import java.util.ArrayList

/**
 * Created by qiizhang on 2016/8/8.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * adapter service for things list
 */
open class ThingsListWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return ThingsListViewFactory(applicationContext, intent)
    }

    class ThingsListViewFactory internal constructor(context: Context?, intent: Intent?) : RemoteViewsFactory {

        private var mContext: Context? = context
        private var mIntent: Intent? = intent

        private var mAppWidgetId: Int = 0

        private var mItems: MutableList<ThingsListWidgetItem>? = null

        override fun onCreate() {
            init()
        }

        private fun init() {
            mAppWidgetId = mIntent!!.getIntExtra(Def.Communication.KEY_WIDGET_ID, 0)
            val appWidgetDAO = AppWidgetDAO.getInstance(mContext)!!
            val info = appWidgetDAO.getThingWidgetInfoById(mAppWidgetId)
            val entries = loadProjectionEntries(info)
            mItems = if (info?.displayMode == ThingWidgetInfo.DISPLAY_MODE_GRID) {
                packGridRows(entries).toMutableList()
            } else {
                entries.toMutableList()
            }
        }

        private fun loadProjectionEntries(info: ThingWidgetInfo?): List<ThingsListWidgetItem> {
            val context = mContext!!
            val typeFilterMask = ThingWidgetInfo.normalizedTypeFilterMask(
                info?.typeFilterMask ?: ThingWidgetInfo.TYPE_FILTER_ALL
            )
            // Things-list widgets support 正在进行 / 已完成 only (never 回收站).
            val status = if (info?.status == Def.ThingStatus.FINISHED) {
                Def.ThingStatus.FINISHED
            } else {
                Def.ThingStatus.UNDERWAY
            }
            val targetFolder = AppWidgetHelper.resolveThingsListTargetFolder(context, info)
            val targetFolderId = targetFolder?.id
            val thingDAO: ThingDAO = ThingDAO.getInstance(context)!!
            val folderDAO: ThingFolderDAO = ThingFolderDAO.getInstance(context)!!
            val things = thingDAO.getThingsForProjection(
                status,
                ThingWidgetInfo.TYPE_FILTER_ALL,
                targetFolderId,
                null,
                0
            )
            val entries = ArrayList<ThingsListWidgetItem>()
            for (thing in things) {
                if (thing == null || thing.type == Thing.HEADER) continue
                if (Thing.isLegacyPlaceholderType(thing.type)) continue
                if (!matchesTypeFilter(thing.type, typeFilterMask)) continue
                entries.add(ThingsListWidgetItem.ThingItem(protectThingIfNeeded(thing, folderDAO)))
            }
            val folderEntries = folderDAO.getFolderEntriesForWidgetProjection(
                targetFolderId,
                typeFilterMask,
                status
            )
            for (folderEntry in folderEntries) {
                entries.add(ThingsListWidgetItem.FolderItem(folderEntry))
            }
            return entries.sortedWith { item1, item2 ->
                val result = ThingsSorter.compareByLocationAndSticky(
                    item1.location,
                    item2.location
                )
                if (result != 0) result else item1.stableId.compareTo(item2.stableId)
            }
        }

        private fun protectThingIfNeeded(thing: Thing, folderDAO: ThingFolderDAO): Thing {
            val copy = Thing(thing)
            if (folderDAO.isEffectivelyPrivate(copy.folderId) && !copy.isPrivate()) {
                copy.title = Thing.PRIVATE_THING_PREFIX + (copy.title ?: "")
            }
            return copy
        }

        private fun matchesTypeFilter(type: Int, typeFilterMask: Int): Boolean {
            val mask = ThingWidgetInfo.normalizedTypeFilterMask(typeFilterMask)
            if (mask == ThingWidgetInfo.TYPE_FILTER_ALL) return true
            return when (type) {
                Thing.NOTE -> mask and ThingWidgetInfo.TYPE_FILTER_NOTE != 0
                Thing.REMINDER -> mask and ThingWidgetInfo.TYPE_FILTER_REMINDER != 0
                Thing.HABIT -> mask and ThingWidgetInfo.TYPE_FILTER_HABIT != 0
                Thing.GOAL -> mask and ThingWidgetInfo.TYPE_FILTER_GOAL != 0
                else -> false
            }
        }

        private fun packGridRows(entries: List<ThingsListWidgetItem>): List<ThingsListWidgetItem> {
            val columns = AppWidgetHelper.getThingsListWidgetGridColumnCount(mContext, mAppWidgetId)
            if (columns <= 1) {
                return entries.mapIndexed { index, item ->
                    ThingsListWidgetItem.GridRow(listOf(item), 1, Long.MIN_VALUE + index)
                }
            }
            val rows = ArrayList<ThingsListWidgetItem>()
            val currentSlots = ArrayList<ThingsListWidgetItem?>()
            fun flushNormalRow() {
                if (currentSlots.isEmpty()) return
                while (currentSlots.size < columns) currentSlots.add(null)
                rows.add(
                    ThingsListWidgetItem.GridRow(
                        ArrayList(currentSlots),
                        columns,
                        Long.MIN_VALUE + rows.size
                    )
                )
                currentSlots.clear()
            }
            for (entry in entries) {
                if (AppWidgetHelper.isThingsListWidgetEntryFullSpan(entry)) {
                    flushNormalRow()
                    rows.add(
                        ThingsListWidgetItem.GridRow(
                            listOf(entry),
                            1,
                            Long.MIN_VALUE + rows.size
                        )
                    )
                    continue
                }
                currentSlots.add(entry)
                if (currentSlots.size == columns) {
                    flushNormalRow()
                }
            }
            flushNormalRow()
            return rows
        }

        override fun onDataSetChanged() {
            init()
        }

        override fun onDestroy() { }

        override fun getCount(): Int {
            return mItems!!.size
        }

        override fun getViewAt(position: Int): RemoteViews? {
            if (position < 0 || position >= count) {
                return null
            }
            return AppWidgetHelper.createRemoteViewsForThingsListEntry(
                mContext,
                mItems!![position],
                mAppWidgetId
            )
        }

        override fun getLoadingView(): RemoteViews? {
            return null
        }

        override fun getViewTypeCount(): Int {
            return 3
        }

        override fun getItemId(position: Int): Long {
            if (position < 0 || position > mItems!!.size - 1) {
                return -1L
            }
            return mItems!![position].stableId
        }

        override fun hasStableIds(): Boolean {
            return true
        }
    }
}
