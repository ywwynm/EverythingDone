package com.ywwynm.everythingdone.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.model.ThingWidgetInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by qiizhang on 2016/8/1.
 * dao level for table "app_widget"
 */
public class AppWidgetDAO {

    public static final String TAG = "AppWidgetDAO";

    private SQLiteDatabase db;

    private static AppWidgetDAO sAppWidgetDAO;

    private AppWidgetDAO(Context context) {
        DBHelper helper = new DBHelper(context);
        db = helper.getWritableDatabase();
    }

    public static AppWidgetDAO getInstance(Context context) {
        if (sAppWidgetDAO == null) {
            synchronized (AppWidgetDAO.class) {
                if (sAppWidgetDAO == null) {
                    sAppWidgetDAO = new AppWidgetDAO(context.getApplicationContext());
                }
            }
        }
        return sAppWidgetDAO;
    }

    public ThingWidgetInfo getThingWidgetInfoById(int appWidgetId) {
        ThingWidgetInfo thingWidgetInfo = null;
        String selection = Def.Database.COLUMN_ID_APP_WIDGET + "=" + appWidgetId;
        Cursor cursor = db.query(Def.Database.TABLE_APP_WIDGET,
                null, selection, null, null, null, null);
        if (cursor.moveToFirst()) {
            thingWidgetInfo = new ThingWidgetInfo(cursor);
        }
        cursor.close();
        return thingWidgetInfo;
    }

    public List<ThingWidgetInfo> getThingWidgetInfosByThingId(long thingId) {
        List<ThingWidgetInfo> thingWidgetInfos = new ArrayList<>();
        String selection = Def.Database.COLUMN_THING_ID_APP_WIDGET + "=" + thingId;
        Cursor cursor = db.query(Def.Database.TABLE_APP_WIDGET,
                null, selection, null, null, null, null);
        while (cursor.moveToNext()) {
            thingWidgetInfos.add(new ThingWidgetInfo(cursor));
        }
        cursor.close();
        return thingWidgetInfos;
    }

    public List<ThingWidgetInfo> getAllThingWidgetInfos() {
        List<ThingWidgetInfo> thingWidgetInfos = new ArrayList<>();
        Cursor cursor = db.query(Def.Database.TABLE_APP_WIDGET,
                null, null, null, null, null, null);
        while (cursor.moveToNext()) {
            thingWidgetInfos.add(new ThingWidgetInfo(cursor));
        }
        cursor.close();
        return thingWidgetInfos;
    }

    public boolean insert(int appWidgetId, long thingId, @ThingWidgetInfo.Size int size, int alpha,
                          @ThingWidgetInfo.Style int style) {
        ContentValues values = new ContentValues();
        values.put(Def.Database.COLUMN_ID_APP_WIDGET,       appWidgetId);
        values.put(Def.Database.COLUMN_THING_ID_APP_WIDGET, thingId);
        values.put(Def.Database.COLUMN_SIZE_APP_WIDGET,     size);
        values.put(Def.Database.COLUMN_ALPHA_APP_WIDGET,    alpha);
        values.put(Def.Database.COLUMN_STYLE_APP_WIDGET,    style);
        return db.insert(Def.Database.TABLE_APP_WIDGET, null, values) != -1;
    }

    public int delete(int appWidgetId) {
        String where = Def.Database.COLUMN_ID_APP_WIDGET + "=" + appWidgetId;
        return db.delete(Def.Database.TABLE_APP_WIDGET, where, null);
    }

}
