package com.ywwynm.everythingdone.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.ywwynm.everythingdone.Def;

/**
 * Created by qiizhang on 2016/8/1.
 * dao level for table "app_widget"
 */
public class AppWidgetDAO {

    public static final String TAG = "AppWidgetDAO";

    private Context mContext;

    private SQLiteDatabase db;

    private static AppWidgetDAO sAppWidgetDAO;

    private AppWidgetDAO(Context context) {
        mContext = context;
        DBHelper helper = new DBHelper(context);
        db = helper.getWritableDatabase();
    }

    public static AppWidgetDAO getInstance(Context context) {
        if (sAppWidgetDAO == null) {
            synchronized (AppWidgetDAO.class) {
                if (sAppWidgetDAO == null) {
                    sAppWidgetDAO = new AppWidgetDAO(context);
                }
            }
        }
        return sAppWidgetDAO;
    }

    public long getThingIdByAppWidgetId(int appWidgetId) {
        long thingId = -1;
        String selection = Def.Database.COLUMN_ID_APP_WIDGET + "=" + appWidgetId;
        Cursor cursor = db.query(Def.Database.TABLE_APP_WIDGET,
                null, selection, null, null, null, null);
        if (cursor.moveToFirst()) {
            thingId = cursor.getLong(cursor.getColumnIndex(Def.Database.COLUMN_THING_ID_APP_WIDGET));
        }
        cursor.close();
        return thingId;
    }

    public boolean insert(int appWidgetId, long thingId) {
        ContentValues values = new ContentValues();
        values.put(Def.Database.COLUMN_ID_APP_WIDGET,       appWidgetId);
        values.put(Def.Database.COLUMN_THING_ID_APP_WIDGET, thingId);
        return db.insert(Def.Database.TABLE_APP_WIDGET, null, values) != -1;
    }

    public int delete(int appWidgetId) {
        String where = Def.Database.COLUMN_ID_APP_WIDGET + "=" + appWidgetId;
        return db.delete(Def.Database.TABLE_APP_WIDGET, where, null);
    }

}
