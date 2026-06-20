package com.ywwynm.everythingdone.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.model.ThingWidgetInfo

import java.util.ArrayList

/**
 * Created by qiizhang on 2016/8/1.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * dao level for table "app_widget"
 */
open class AppWidgetDAO private constructor(context: Context?) {

    private var db: SQLiteDatabase? = null

    init {
        val helper = DBHelper(context)
        db = helper.writableDatabase
    }

    open fun getThingWidgetInfoById(appWidgetId: Int): ThingWidgetInfo? {
        var thingWidgetInfo: ThingWidgetInfo? = null
        val selection: String = Def.Database.COLUMN_ID_APP_WIDGET + "=" + appWidgetId
        val cursor: Cursor = db!!.query(Def.Database.TABLE_APP_WIDGET,
                null, selection, null, null, null, null)
        if (cursor.moveToFirst()) {
            thingWidgetInfo = ThingWidgetInfo(cursor)
        }
        cursor.close()
        return thingWidgetInfo
    }

    open fun getThingWidgetInfosByThingId(thingId: Long): List<ThingWidgetInfo?>? {
        val thingWidgetInfos: MutableList<ThingWidgetInfo?> = ArrayList()
        val selection: String = Def.Database.COLUMN_THING_ID_APP_WIDGET + "=" + thingId
        val cursor: Cursor = db!!.query(Def.Database.TABLE_APP_WIDGET,
                null, selection, null, null, null, null)
        while (cursor.moveToNext()) {
            thingWidgetInfos.add(ThingWidgetInfo(cursor))
        }
        cursor.close()
        return thingWidgetInfos
    }

    open fun getAllThingWidgetInfos(): List<ThingWidgetInfo?>? {
        val thingWidgetInfos: MutableList<ThingWidgetInfo?> = ArrayList()
        val cursor: Cursor = db!!.query(Def.Database.TABLE_APP_WIDGET,
                null, null, null, null, null, null)
        while (cursor.moveToNext()) {
            thingWidgetInfos.add(ThingWidgetInfo(cursor))
        }
        cursor.close()
        return thingWidgetInfos
    }

    open fun getThingsListWidgetInfos(): List<ThingWidgetInfo?> {
        val thingWidgetInfos: MutableList<ThingWidgetInfo?> = ArrayList()
        val selection = Def.Database.COLUMN_THING_ID_APP_WIDGET + "<0"
        val cursor: Cursor = db!!.query(
            Def.Database.TABLE_APP_WIDGET,
            null,
            selection,
            null,
            null,
            null,
            null
        )
        while (cursor.moveToNext()) {
            thingWidgetInfos.add(ThingWidgetInfo(cursor))
        }
        cursor.close()
        return thingWidgetInfos
    }

    open fun insert(appWidgetId: Int, thingId: Long, @ThingWidgetInfo.Size size: Int, alpha: Int,
                    @ThingWidgetInfo.Style style: Int): Boolean {
        return insert(
            appWidgetId,
            thingId,
            size,
            alpha,
            style,
            null,
            ThingWidgetInfo.TYPE_FILTER_ALL,
            ThingWidgetInfo.DISPLAY_MODE_LIST
        )
    }

    open fun insert(
        appWidgetId: Int,
        thingId: Long,
        @ThingWidgetInfo.Size size: Int,
        alpha: Int,
        @ThingWidgetInfo.Style style: Int,
        targetFolderId: Long?,
        typeFilterMask: Int,
        @ThingWidgetInfo.DisplayMode displayMode: Int
    ): Boolean {
        val values = ContentValues()
        values.put(Def.Database.COLUMN_ID_APP_WIDGET,       appWidgetId)
        values.put(Def.Database.COLUMN_THING_ID_APP_WIDGET, thingId)
        values.put(Def.Database.COLUMN_SIZE_APP_WIDGET,     size)
        values.put(Def.Database.COLUMN_ALPHA_APP_WIDGET,    alpha)
        values.put(Def.Database.COLUMN_STYLE_APP_WIDGET,    style)
        if (targetFolderId == null) {
            values.putNull(Def.Database.COLUMN_TARGET_FOLDER_ID_APP_WIDGET)
        } else {
            values.put(Def.Database.COLUMN_TARGET_FOLDER_ID_APP_WIDGET, targetFolderId)
        }
        values.put(
            Def.Database.COLUMN_TYPE_FILTER_MASK_APP_WIDGET,
            ThingWidgetInfo.normalizedTypeFilterMask(typeFilterMask)
        )
        values.put(Def.Database.COLUMN_DISPLAY_MODE_APP_WIDGET, displayMode)
        return db!!.insert(Def.Database.TABLE_APP_WIDGET, null, values) != -1L
    }

    open fun delete(appWidgetId: Int): Int {
        val where: String = Def.Database.COLUMN_ID_APP_WIDGET + "=" + appWidgetId
        return db!!.delete(Def.Database.TABLE_APP_WIDGET, where, null)
    }

    companion object {
        const val TAG: String = "AppWidgetDAO"

        @JvmField
        var sAppWidgetDAO: AppWidgetDAO? = null

        @JvmStatic
        fun getInstance(context: Context?): AppWidgetDAO? {
            if (sAppWidgetDAO == null) {
                synchronized(AppWidgetDAO::class.java) {
                    if (sAppWidgetDAO == null) {
                        sAppWidgetDAO = AppWidgetDAO(context!!.applicationContext)
                    }
                }
            }
            return sAppWidgetDAO
        }
    }
}
