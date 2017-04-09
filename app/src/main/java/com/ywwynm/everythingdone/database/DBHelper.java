package com.ywwynm.everythingdone.database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DisplayUtil;

/**
 * Created by ywwynm on 2015/5/21.
 * Database layer.
 */
public class DBHelper extends SQLiteOpenHelper {

    public static final String TAG = "DBHelper";

    private static final String SQL_CREATE_TABLE_THINGS = "create table if not exists "
            + Def.Database.TABLE_THINGS + " ("
                + Def.Database.COLUMN_ID_THINGS          + " integer primary key, "
                + Def.Database.COLUMN_TYPE_THINGS        + " integer not null, "
                + Def.Database.COLUMN_STATE_THINGS       + " integer not null, "
                + Def.Database.COLUMN_COLOR_THINGS       + " integer, "
                + Def.Database.COLUMN_TITLE_THINGS       + " text, "
                + Def.Database.COLUMN_CONTENT_THINGS     + " text, "
                + Def.Database.COLUMN_ATTACHMENT_THINGS  + " text, "
                + Def.Database.COLUMN_LOCATION_THINGS    + " integer, "
                + Def.Database.COLUMN_CREATE_TIME_THINGS + " integer, "
                + Def.Database.COLUMN_UPDATE_TIME_THINGS + " integer, "
                + Def.Database.COLUMN_FINISH_TIME_THINGS + " integer"
            + ")";

    private static final String SQL_CREATE_TABLE_REMINDERS = "create table if not exists "
            + Def.Database.TABLE_REMINDERS + " ("
                + Def.Database.COLUMN_ID_REMINDERS            + " integer primary key, "
                + Def.Database.COLUMN_NOTIFY_TIME_REMINDERS   + " integer, "
                + Def.Database.COLUMN_STATE_REMINDERS         + " integer, "
                + Def.Database.COLUMN_NOTIFY_MILLIS_REMINDERS + " integer, "
                + Def.Database.COLUMN_CREATE_TIME_REMINDERS   + " integer, "
                + Def.Database.COLUMN_UPDATE_TIME_REMINDERS   + " integer"
            + ")";

    private static final String SQL_CREATE_TABLE_HABITS = "create table if not exists "
            + Def.Database.TABLE_HABITS + " ("
                + Def.Database.COLUMN_ID_HABITS             + " integer primary key, "
                + Def.Database.COLUMN_TYPE_HABITS           + " integer, "
                + Def.Database.COLUMN_REMINDED_TIMES_HABITS + " integer, "
                + Def.Database.COLUMN_DETAIL_HABITS         + " text, "
                + Def.Database.COLUMN_RECORD_HABITS         + " text, "
                + Def.Database.COLUMN_INTERVAL_INFO_HABITS  + " text, "
                + Def.Database.COLUMN_CREATE_TIME_HABITS    + " integer, "
                + Def.Database.COLUMN_FIRST_TIME_HABITS     + " integer"
            +")";

    private static final String SQL_CREATE_TABLE_HABIT_REMINDERS = "create table if not exists "
            + Def.Database.TABLE_HABIT_REMINDERS + " ("
                + Def.Database.COLUMN_ID_HABIT_REMINDERS          + " integer primary key, "
                + Def.Database.COLUMN_HABIT_ID_HABIT_REMINDERS    + " integer, "
                + Def.Database.COLUMN_NOTIFY_TIME_HABIT_REMINDERS + " integer"
            +")";

    private static final String SQL_CREATE_TABLE_HABIT_RECORDS = "create table if not exists "
            + Def.Database.TABLE_HABIT_RECORDS + " ("
                + Def.Database.COLUMN_ID_HABIT_RECORDS           + " integer primary key, "
                + Def.Database.COLUMN_HABIT_ID_HABIT_RECORDS     + " integer, "
                + Def.Database.COLUMN_HR_ID_HABIT_RECORDS        + " integer, "
                + Def.Database.COLUMN_RECORD_TIME_HABIT_RECORDS  + " integer, "
                + Def.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS  + " integer, "
                + Def.Database.COLUMN_RECORD_MONTH_HABIT_RECORDS + " integer, "
                + Def.Database.COLUMN_RECORD_WEEK_HABIT_RECORDS  + " integer, "
                + Def.Database.COLUMN_RECORD_DAY_HABIT_RECORDS   + " integer, "
                + Def.Database.COLUMN_TYPE_HABIT_RECORDS + " integer not null default 0"
            +")";

    private static final String SQL_CREATE_TABLE_APP_WIDGET = "create table if not exists "
            + Def.Database.TABLE_APP_WIDGET + " ("
                + Def.Database.COLUMN_ID_APP_WIDGET       + " integer primary key, "
                + Def.Database.COLUMN_THING_ID_APP_WIDGET + " integer not null, "
                + Def.Database.COLUMN_SIZE_APP_WIDGET     + " integer not null, " /* added in version 3 */
                + Def.Database.COLUMN_ALPHA_APP_WIDGET    + " integer not null default 100, " /* added in version 4 */
                + Def.Database.COLUMN_STYLE_APP_WIDGET    + " integer not null default 0, " /* added in version 5 */
                + "foreign key("
                    + Def.Database.COLUMN_THING_ID_APP_WIDGET
                + ") references "
                + Def.Database.COLUMN_ID_THINGS + "("
                    + Def.Database.TABLE_THINGS
                + ")"
            + ")";

    // added on 2016/11/9
    private static final String SQL_CREATE_TABLE_DOING_RECORDS = "create table if not exists "
            + Def.Database.TABLE_DOING_RECORDS + " ("
                + Def.Database.COLUMN_ID_DOING                 + " integer primary key autoincrement, "
                + Def.Database.COLUMN_THING_ID_DOING           + " integer not null, "
                + Def.Database.COLUMN_THING_TYPE_DOING         + " integer not null, "
                + Def.Database.COLUMN_ADD5_TIMES_DOING         + " integer not null, "
                + Def.Database.COLUMN_PLAYED_TIMES_DOING       + " integer not null, "
                + Def.Database.COLUMN_TOTAL_PLAY_TIME_DOING    + " integer not null, "
                + Def.Database.COLUMN_PREDICT_DOING_TIME_DOING + " integer not null, "
                + Def.Database.COLUMN_START_TIME_DOING         + " integer not null, "
                + Def.Database.COLUMN_END_TIME_DOING           + " integer not null, "
                + Def.Database.COLUMN_STOP_REASON_DOING        + " integer not null, "
                + Def.Database.COLUMN_START_TYPE_DOING         + " integer not null default 0, "
                + Def.Database.COLUMN_SHOULD_ASM_DOING         + " integer not null default 0"
            + ")";

    private static final String SQL_INSERT_HEADER = "insert into "
            + Def.Database.TABLE_THINGS + " values("
            + "'7', '"
            + Thing.HEADER
            + "', '"
            + Thing.UNDERWAY
            + "', '-14784871', 'Let this be my last words', 'I trust thy love', 'to QQ', '7', '"
            + System.currentTimeMillis() + "', '"
            + System.currentTimeMillis() + "', '0')";

    private static final String SQL_ADD_COLUMN_ALPHA_APP_WIDGET = "alter table "
            + Def.Database.TABLE_APP_WIDGET
            + " add column " + Def.Database.COLUMN_ALPHA_APP_WIDGET + " integer not null default 100";

    private static final String SQL_ADD_COLUMN_STYLE_APP_WIDGET = "alter table "
            + Def.Database.TABLE_APP_WIDGET
            + " add column " + Def.Database.COLUMN_STYLE_APP_WIDGET + " integer not null default 0";

    private static final String SQL_ADD_COLUMN_START_TYPE_DOING_RECORD = "alter table "
            + Def.Database.TABLE_DOING_RECORDS
            + " add column " + Def.Database.COLUMN_START_TYPE_DOING + " integer not null default 0";

    private static final String SQL_ADD_COLUMN_SHOULD_ASM_DOING_RECORD = "alter table "
            + Def.Database.TABLE_DOING_RECORDS
            + " add column " + Def.Database.COLUMN_SHOULD_ASM_DOING + " integer not null default 0";

    private static final String SQL_ADD_COLUMN_TYPE_HABIT_RECORD = "alter table "
            + Def.Database.TABLE_HABIT_RECORDS
            + " add column " + Def.Database.COLUMN_TYPE_HABIT_RECORDS + " integer not null default 0";

    private static final String SQL_DROP_TABLE_THINGS = "drop table if exists "
            + Def.Database.TABLE_THINGS;

    private static final String SQL_DROP_TABLE_REMINDERS = "drop table if exists "
            + Def.Database.TABLE_REMINDERS;

    private static final String SQL_DROP_TABLE_HABITS = "drop table if exists "
            + Def.Database.TABLE_HABITS;

    private static final String SQL_DROP_TABLE_HABIT_REMINDERS = "drop table if exists "
            + Def.Database.TABLE_HABIT_REMINDERS;

    private static final String SQL_DROP_TABLE_HABIT_RECORDS = "drop table if exists "
            + Def.Database.TABLE_HABIT_RECORDS;

    private static final String SQL_DROP_TABLE_APP_WIDGET = "drop table if exists "
            + Def.Database.TABLE_APP_WIDGET;

    private static final String SQL_DROP_TABLE_DOING_RECORDS = "drop table if exists "
            + Def.Database.TABLE_DOING_RECORDS;

    private Context mContext;

    public DBHelper(Context context) {
        super(context, Def.Meta.DATABASE_NAME, null, Def.Meta.DATABASE_VERSION);
        mContext = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_TABLE_THINGS);

        db.execSQL(generateInsertInitialSQL(0, Thing.WELCOME_UNDERWAY,
                R.string.welcome_underway_title, R.string.welcome_underway_content));
        db.execSQL(generateInsertInitialSQL(1, Thing.WELCOME_NOTE,
                0, R.string.welcome_note_content));
        db.execSQL(generateInsertInitialSQL(2, Thing.WELCOME_REMINDER,
                0, R.string.welcome_reminder_content));
        db.execSQL(generateInsertInitialSQL(3, Thing.WELCOME_HABIT,
                0, R.string.welcome_habit_content));
        db.execSQL(generateInsertInitialSQL(4, Thing.WELCOME_GOAL,
                0, R.string.welcome_goal_content));
        db.execSQL(generateInsertInitialSQL(5, Thing.NOTIFY_EMPTY_FINISHED,
                0, R.string.empty_finished));
        db.execSQL(generateInsertInitialSQL(6, Thing.NOTIFY_EMPTY_DELETED,
                0, R.string.empty_deleted));

//        for (int i = 7; i < 607; i++) {
//            db.execSQL(generateTestSQL(i, i % 2 == 0 ? "" : "ywwynm", i - 6 + ""));
//        }

        db.execSQL(SQL_INSERT_HEADER);

        db.execSQL(SQL_CREATE_TABLE_REMINDERS);
        db.execSQL(SQL_CREATE_TABLE_HABITS);
        db.execSQL(SQL_CREATE_TABLE_HABIT_REMINDERS);
        db.execSQL(SQL_CREATE_TABLE_HABIT_RECORDS);
        db.execSQL(SQL_CREATE_TABLE_APP_WIDGET);
        db.execSQL(SQL_CREATE_TABLE_DOING_RECORDS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.i(TAG, "database upgrade, old version: " + oldVersion + ", new version: " + newVersion);
//        db.execSQL(SQL_DROP_TABLE_THINGS);
//        db.execSQL(SQL_DROP_TABLE_REMINDERS);
//        db.execSQL(SQL_DROP_TABLE_HABITS);
//        db.execSQL(SQL_DROP_TABLE_HABIT_REMINDERS);
//        db.execSQL(SQL_DROP_TABLE_HABIT_RECORDS);
//        onCreate(db);
        if (oldVersion == 1) {
            // no table "app_widget" in first version
            db.execSQL(SQL_CREATE_TABLE_APP_WIDGET);
            db.execSQL(SQL_CREATE_TABLE_DOING_RECORDS);
            db.execSQL(SQL_ADD_COLUMN_TYPE_HABIT_RECORD);
        } else if (oldVersion == 2) {
            // Only for developing. I forget to create a column that describes widget's size
            db.execSQL(SQL_DROP_TABLE_APP_WIDGET);
            db.execSQL(SQL_CREATE_TABLE_APP_WIDGET);
        } else if (oldVersion == 3) {
            db.execSQL(SQL_ADD_COLUMN_ALPHA_APP_WIDGET);
            db.execSQL(SQL_ADD_COLUMN_STYLE_APP_WIDGET);
            db.execSQL(SQL_CREATE_TABLE_DOING_RECORDS);
            db.execSQL(SQL_ADD_COLUMN_TYPE_HABIT_RECORD);
        } else if (oldVersion == 4) {
            // Only for developing. Thing list widget now can optimize style
            db.execSQL(SQL_ADD_COLUMN_STYLE_APP_WIDGET);
        } else if (oldVersion == 5) {
            db.execSQL(SQL_CREATE_TABLE_DOING_RECORDS);
            db.execSQL(SQL_ADD_COLUMN_TYPE_HABIT_RECORD);
        } else if (oldVersion == 6) {
            db.execSQL(SQL_ADD_COLUMN_START_TYPE_DOING_RECORD);
            db.execSQL(SQL_ADD_COLUMN_SHOULD_ASM_DOING_RECORD);
            db.execSQL(SQL_ADD_COLUMN_TYPE_HABIT_RECORD);
        } else if (oldVersion == 7) {
            db.execSQL(SQL_ADD_COLUMN_TYPE_HABIT_RECORD);
        }
        // released version should be 1, 3, 5, 6, 7, 8.
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (newVersion < oldVersion) {
            onUpgrade(db, newVersion, oldVersion);
            db.setVersion(oldVersion);
        }
    }

    private String generateInsertInitialSQL(int id, int type, int titleRes, int contentRes) {
        return "insert into " + Def.Database.TABLE_THINGS + " values(" + "'"
                + id + "', '"
                + type + "', '"
                + Thing.UNDERWAY + "', '"
                + DisplayUtil.getRandomColor(mContext) + "', '"
                + (titleRes != 0 ? mContext.getString(titleRes) : "") + "', '"
                + mContext.getString(contentRes) + "', "
                + "''" + ", '"
                + id + "', '"
                + System.currentTimeMillis() + "', '"
                + System.currentTimeMillis() + "', "
                + "'0')";
    }

//    private String generateTestSQL(int id, String title, String content) {
//        return "insert into " + Def.Database.TABLE_THINGS + " values(" + "'"
//                + id + "', '"
//                + Thing.NOTE + "', '"
//                + Thing.UNDERWAY + "', '"
//                + DisplayUtil.getRandomColor(mContext) + "', '"
//                + title + "', '"
//                + content + "', "
//                + "''" + ", '"
//                + id + "', '"
//                + System.currentTimeMillis() + "', '"
//                + System.currentTimeMillis() + "', "
//                + "'0')";
//    }
}
