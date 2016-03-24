package com.ywwynm.everythingdone.database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.ywwynm.everythingdone.Definitions;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DisplayUtil;

/**
 * Created by ywwynm on 2015/5/21.
 * Database layer.
 */
public class EverythingDoneSQLiteOpenHelper extends SQLiteOpenHelper {

    public static final String TAG = "EverythingDoneSQLiteOpenHelper";

    private static final String DATABASE_NAME = "EverythingDoneData.db";
    private static final String SQL_CREATE_TABLE_THINGS = "create table if not exists "
            + Definitions.Database.TABLE_THINGS + " ("
            + Definitions.Database.COLUMN_ID_THINGS
            + " int primary key, "
            + Definitions.Database.COLUMN_TYPE_THINGS
            + " int not null, "
            + Definitions.Database.COLUMN_STATE_THINGS
            + " int not null, "
            + Definitions.Database.COLUMN_COLOR_THINGS
            + " int, "
            + Definitions.Database.COLUMN_TITLE_THINGS
            + " text, "
            + Definitions.Database.COLUMN_CONTENT_THINGS
            + " text, "
            + Definitions.Database.COLUMN_ATTACHMENT_THINGS
            + " text, "
            + Definitions.Database.COLUMN_LOCATION_THINGS
            + " int, "
            + Definitions.Database.COLUMN_CREATE_TIME_THINGS
            + " int, "
            + Definitions.Database.COLUMN_UPDATE_TIME_THINGS
            + " int, "
            + Definitions.Database.COLUMN_FINISH_TIME_THINGS
            + " int)";

    private static final String SQL_CREATE_TABLE_REMINDERS = "create table if not exists "
            + Definitions.Database.TABLE_REMINDERS + " ("
            + Definitions.Database.COLUMN_ID_REMINDERS
            + " int primary key, "
            + Definitions.Database.COLUMN_NOTIFY_TIME_REMINDERS
            + " int, "
            + Definitions.Database.COLUMN_STATE_REMINDERS
            + " int, "
            + Definitions.Database.COLUMN_GOAL_DAYS_REMINDERS
            + " int, "
            + Definitions.Database.COLUMN_CREATE_TIME_REMINDERS
            + " int, "
            + Definitions.Database.COLUMN_UPDATE_TIME_REMINDERS
            + " int)";

    private static final String SQL_CREATE_TABLE_HABITS = "create table if not exists "
            + Definitions.Database.TABLE_HABITS + " ("
            + Definitions.Database.COLUMN_ID_HABITS
            + " int primary key, "
            + Definitions.Database.COLUMN_TYPE_HABITS
            + " int, "
            + Definitions.Database.COLUMN_REMINDED_TIMES_HABITS
            + " int, "
            + Definitions.Database.COLUMN_DETAIL_HABITS
            + " text, "
            + Definitions.Database.COLUMN_RECORD_HABITS
            + " text, "
            + Definitions.Database.COLUMN_INTERVAL_INFO_HABITS
            + " text, "
            + Definitions.Database.COLUMN_CREATE_TIME_HABITS
            + " int, "
            + Definitions.Database.COLUMN_FIRST_TIME_HABITS
            + " int)";

    private static final String SQL_CREATE_TABLE_HABIT_REMINDERS = "create table if not exists "
            + Definitions.Database.TABLE_HABIT_REMINDERS + " ("
            + Definitions.Database.COLUMN_ID_HABIT_REMINDERS
            + " int primary key, "
            + Definitions.Database.COLUMN_HABIT_ID_HABIT_REMINDERS
            + " int, "
            + Definitions.Database.COLUMN_NOTIFY_TIME_HABIT_REMINDERS
            + " int)";

    private static final String SQL_CREATE_TABLE_HABIT_RECORDS = "create table if not exists "
            + Definitions.Database.TABLE_HABIT_RECORDS + " ("
            + Definitions.Database.COLUMN_ID_HABIT_RECORDS
            + " int primary key, "
            + Definitions.Database.COLUMN_HABIT_ID_HABIT_RECORDS
            + " int, "
            + Definitions.Database.COLUMN_HR_ID_HABIT_RECORDS
            + " int, "
            + Definitions.Database.COLUMN_RECORD_TIME_HABIT_RECORDS
            + " int, "
            + Definitions.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS
            + " int, "
            + Definitions.Database.COLUMN_RECORD_MONTH_HABIT_RECORDS
            + " int, "
            + Definitions.Database.COLUMN_RECORD_WEEK_HABIT_RECORDS
            + " int, "
            + Definitions.Database.COLUMN_RECORD_DAY_HABIT_RECORDS
            + " int)";

    private static final String SQL_INSERT_HEADER = "insert into "
            + Definitions.Database.TABLE_THINGS + " values("
            + "'7', '"
            + Thing.HEADER
            + "', '"
            + Thing.UNDERWAY
            + "', '-14784871', 'Let this be my last words', 'I trust thy love', 'to QQ', '7', '"
            + System.currentTimeMillis() + "', '"
            + System.currentTimeMillis() + "', '0')";

    private static final String SQL_DROP_TABLE_THINGS = "drop table if exists "
            + Definitions.Database.TABLE_THINGS;

    private static final String SQL_DROP_TABLE_REMINDERS = "drop table if exists "
            + Definitions.Database.TABLE_REMINDERS;

    private static final String SQL_DROP_TABLE_HABITS = "drop table if exists "
            + Definitions.Database.TABLE_HABITS;

    private static final String SQL_DROP_TABLE_HABIT_REMINDERS = "drop table if exists "
            + Definitions.Database.TABLE_HABIT_REMINDERS;

    private static final String SQL_DROP_TABLE_HABIT_RECORDS = "drop table if exists "
            + Definitions.Database.TABLE_HABIT_RECORDS;

    private Context mContext;

    public EverythingDoneSQLiteOpenHelper(Context context) {
        super(context, DATABASE_NAME, null, Definitions.MetaData.DATABASE_VERSION);
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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL(SQL_DROP_TABLE_THINGS);
        db.execSQL(SQL_DROP_TABLE_REMINDERS);
        db.execSQL(SQL_DROP_TABLE_HABITS);
        db.execSQL(SQL_DROP_TABLE_HABIT_REMINDERS);
        db.execSQL(SQL_DROP_TABLE_HABIT_RECORDS);
        onCreate(db);
    }

    private String generateInsertInitialSQL(int id, int type, int titleRes, int contentRes) {
        return "insert into " + Definitions.Database.TABLE_THINGS + " values(" + "'"
                + id + "', '"
                + type + "', '"
                + Thing.UNDERWAY + "', '"
                + DisplayUtil.getRandomColor(mContext) + "', '"
                + (titleRes != 0 ? mContext.getString(titleRes) : "") + "', '"
                + mContext.getString(contentRes) + "', '', '"
                + id + "', '"
                + System.currentTimeMillis() + "', '"
                + System.currentTimeMillis() + "', '0')";
    }

//    private String generateTestSQL(int id, String title, String content) {
//        return "insert into " + Definitions.Database.TABLE_THINGS + " values(" + "'"
//                + id + "', '"
//                + Thing.NOTE + "', '"
//                + Thing.UNDERWAY + "', '"
//                + DisplayUtil.getRandomColor(mContext) + "', '"
//                + title + "', '"
//                + content + "', '', '"
//                + id + "', '"
//                + System.currentTimeMillis() + "', '"
//                + System.currentTimeMillis() + "')";
//    }
}
