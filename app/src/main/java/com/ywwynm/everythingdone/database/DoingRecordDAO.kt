package com.ywwynm.everythingdone.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.model.DoingRecord

import java.util.ArrayList

/**
 * Created by ywwynm on 2016/11/9.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * dao layer between model [DoingRecord] and table "doing_records".
 */
open class DoingRecordDAO private constructor(context: Context?) {

    private var db: SQLiteDatabase? = null

    init {
        val helper: DBHelper = DBHelper(context)
        db = helper.getWritableDatabase()
    }

    open fun getAllDoingRecords(): List<DoingRecord?>? {
        val reminders: MutableList<DoingRecord?> = ArrayList()
        val cursor: Cursor = db!!.query(Def.Database.TABLE_DOING_RECORDS, null, null, null, null, null, null)
        while (cursor.moveToNext()) {
            reminders.add(DoingRecord(cursor))
        }
        cursor.close()
        return reminders
    }

    open fun insert(doingRecord: DoingRecord?): Boolean {
        val values: ContentValues = ContentValues()
        values.put(Def.Database.COLUMN_THING_ID_DOING,           doingRecord!!.thingId)
        values.put(Def.Database.COLUMN_THING_TYPE_DOING,         doingRecord.thingType)
        values.put(Def.Database.COLUMN_ADD5_TIMES_DOING,         doingRecord.add5Times)
        values.put(Def.Database.COLUMN_PLAYED_TIMES_DOING,       doingRecord.playedTimes)
        values.put(Def.Database.COLUMN_TOTAL_PLAY_TIME_DOING,    doingRecord.totalPlayTime)
        values.put(Def.Database.COLUMN_PREDICT_DOING_TIME_DOING, doingRecord.predictDoingTime)
        values.put(Def.Database.COLUMN_START_TIME_DOING,         doingRecord.startTime)
        values.put(Def.Database.COLUMN_END_TIME_DOING,           doingRecord.endTime)
        values.put(Def.Database.COLUMN_STOP_REASON_DOING,        doingRecord.stopReason)
        values.put(Def.Database.COLUMN_START_TYPE_DOING,         doingRecord.startType)
        values.put(Def.Database.COLUMN_SHOULD_ASM_DOING,         if (doingRecord.shouldAutoStrictMode) 1 else 0)
        return db!!.insert(Def.Database.TABLE_DOING_RECORDS, null, values) != -1L
    }

    companion object {
        const val TAG: String = "DoingRecordDAO"

        @JvmField
        var sDoingRecordDAO: DoingRecordDAO? = null

        @JvmStatic
        fun getInstance(context: Context?): DoingRecordDAO? {
            if (sDoingRecordDAO == null) {
                synchronized(DoingRecordDAO::class.java) {
                    if (sDoingRecordDAO == null) {
                        sDoingRecordDAO = DoingRecordDAO(context!!.getApplicationContext())
                    }
                }
            }
            return sDoingRecordDAO
        }
    }
}
