package com.ywwynm.everythingdone.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.model.DoingRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by ywwynm on 2016/11/9.
 * dao layer between model {@link DoingRecord} and table "doing_records".
 */
public class DoingRecordDAO {

    public static final String TAG = "DoingRecordDAO";

    private static DoingRecordDAO sDoingRecordDAO;

    public static DoingRecordDAO getInstance(Context context) {
        if (sDoingRecordDAO == null) {
            synchronized (DoingRecordDAO.class) {
                if (sDoingRecordDAO == null) {
                    sDoingRecordDAO = new DoingRecordDAO(context.getApplicationContext());
                }
            }
        }
        return sDoingRecordDAO;
    }

    private SQLiteDatabase db;

    private DoingRecordDAO(Context context) {
        DBHelper helper = new DBHelper(context);
        db = helper.getWritableDatabase();
    }

    public List<DoingRecord> getAllDoingRecords() {
        List<DoingRecord> reminders = new ArrayList<>();
        Cursor cursor = db.query(Def.Database.TABLE_DOING_RECORDS, null, null, null, null, null, null);
        while (cursor.moveToNext()) {
            reminders.add(new DoingRecord(cursor));
        }
        cursor.close();
        return reminders;
    }

    public boolean insert(DoingRecord doingRecord) {
        ContentValues values = new ContentValues();
        values.put(Def.Database.COLUMN_THING_ID_DOING,           doingRecord.getThingId());
        values.put(Def.Database.COLUMN_THING_TYPE_DOING,         doingRecord.getThingType());
        values.put(Def.Database.COLUMN_ADD5_TIMES_DOING,         doingRecord.getAdd5Times());
        values.put(Def.Database.COLUMN_PLAYED_TIMES_DOING,       doingRecord.getPlayedTimes());
        values.put(Def.Database.COLUMN_TOTAL_PLAY_TIME_DOING,    doingRecord.getTotalPlayTime());
        values.put(Def.Database.COLUMN_PREDICT_DOING_TIME_DOING, doingRecord.getPredictDoingTime());
        values.put(Def.Database.COLUMN_START_TIME_DOING,         doingRecord.getStartTime());
        values.put(Def.Database.COLUMN_END_TIME_DOING,           doingRecord.getEndTime());
        values.put(Def.Database.COLUMN_STOP_REASON_DOING,        doingRecord.getStopReason());
        values.put(Def.Database.COLUMN_START_TYPE_DOING,         doingRecord.getStartType());
        values.put(Def.Database.COLUMN_SHOULD_ASM_DOING,         doingRecord.shouldAutoStrictMode() ? 1 : 0);
        return db.insert(Def.Database.TABLE_DOING_RECORDS, null, values) != -1;
    }

}
