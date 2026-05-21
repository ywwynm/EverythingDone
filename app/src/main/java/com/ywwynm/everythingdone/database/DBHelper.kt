package com.ywwynm.everythingdone.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.Thing

/**
 * Created by ywwynm on 2015/5/21.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Database layer.
 */
open class DBHelper(context: Context?) : SQLiteOpenHelper(context, Def.Meta.DATABASE_NAME, null, Def.Meta.DATABASE_VERSION) {

    private var mContext: Context? = context

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_TABLE_THINGS)

        db.execSQL(generateInsertInitialSQL(0, Thing.WELCOME_UNDERWAY,
                R.string.welcome_underway_title, R.string.welcome_underway_content))
        db.execSQL(generateInsertInitialSQL(1, Thing.WELCOME_NOTE,
                0, R.string.welcome_note_content))
        db.execSQL(generateInsertInitialSQL(2, Thing.WELCOME_REMINDER,
                0, R.string.welcome_reminder_content))
        db.execSQL(generateInsertInitialSQL(3, Thing.WELCOME_HABIT,
                0, R.string.welcome_habit_content))
        db.execSQL(generateInsertInitialSQL(4, Thing.WELCOME_GOAL,
                0, R.string.welcome_goal_content))
        db.execSQL(generateInsertInitialSQL(5, Thing.NOTIFY_EMPTY_FINISHED,
                0, R.string.empty_finished))
        db.execSQL(generateInsertInitialSQL(6, Thing.NOTIFY_EMPTY_DELETED,
                0, R.string.empty_deleted))

//        for (int i = 7; i < 607; i++) {
//            db.execSQL(generateTestSQL(i, i % 2 == 0 ? "" : "ywwynm", i - 6 + ""));
//        }

        db.execSQL(SQL_INSERT_HEADER)

        db.execSQL(SQL_CREATE_TABLE_REMINDERS)
        db.execSQL(SQL_CREATE_TABLE_HABITS)
        db.execSQL(SQL_CREATE_TABLE_HABIT_REMINDERS)
        db.execSQL(SQL_CREATE_TABLE_HABIT_RECORDS)
        db.execSQL(SQL_CREATE_TABLE_APP_WIDGET)
        db.execSQL(SQL_CREATE_TABLE_DOING_RECORDS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "database upgrade, old version: $oldVersion, new version: $newVersion")
//        db.execSQL(SQL_DROP_TABLE_THINGS);
//        db.execSQL(SQL_DROP_TABLE_REMINDERS);
//        db.execSQL(SQL_DROP_TABLE_HABITS);
//        db.execSQL(SQL_DROP_TABLE_HABIT_REMINDERS);
//        db.execSQL(SQL_DROP_TABLE_HABIT_RECORDS);
//        onCreate(db);
        when (oldVersion) {
            1 -> {
                // no table "app_widget" in first version
                db.execSQL(SQL_CREATE_TABLE_APP_WIDGET)
                db.execSQL(SQL_CREATE_TABLE_DOING_RECORDS)
                db.execSQL(SQL_ADD_COLUMN_TYPE_HABIT_RECORD)
            }
            2 -> {
                // Only for developing. I forget to create a column that describes widget's size
                db.execSQL(SQL_DROP_TABLE_APP_WIDGET)
                db.execSQL(SQL_CREATE_TABLE_APP_WIDGET)
            }
            3 -> {
                db.execSQL(SQL_ADD_COLUMN_ALPHA_APP_WIDGET)
                db.execSQL(SQL_ADD_COLUMN_STYLE_APP_WIDGET)
                db.execSQL(SQL_CREATE_TABLE_DOING_RECORDS)
                db.execSQL(SQL_ADD_COLUMN_TYPE_HABIT_RECORD)
            }
            4 -> {
                // Only for developing. Thing list widget now can optimize style
                db.execSQL(SQL_ADD_COLUMN_STYLE_APP_WIDGET)
            }
            5 -> {
                db.execSQL(SQL_CREATE_TABLE_DOING_RECORDS)
                db.execSQL(SQL_ADD_COLUMN_TYPE_HABIT_RECORD)
            }
            6 -> {
                db.execSQL(SQL_ADD_COLUMN_START_TYPE_DOING_RECORD)
                db.execSQL(SQL_ADD_COLUMN_SHOULD_ASM_DOING_RECORD)
                db.execSQL(SQL_ADD_COLUMN_TYPE_HABIT_RECORD)
            }
            7 -> {
                db.execSQL(SQL_ADD_COLUMN_TYPE_HABIT_RECORD)
                db.execSQL(SQL_ADD_COLUMN_BACKGROUND_THINGS)
            }
            8 -> {
                db.execSQL(SQL_ADD_COLUMN_BACKGROUND_THINGS)
            }
        }
        // released version should be 1, 3, 5, 6, 7, 8, 9.
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (newVersion < oldVersion) {
            onUpgrade(db, newVersion, oldVersion)
            db.version = oldVersion
        }
    }

    private fun generateInsertInitialSQL(id: Int, type: Int, titleRes: Int, contentRes: Int): String {
        // Phase 3+: roll a full ThingBackground (50/50 PURE vs GRADIENT) and
        // write both the legacy int colour column and the new background JSON
        // column. Previously this inserted only 11 values for a 12-column
        // table — broken on fresh installs since v9 added COLUMN_BACKGROUND_THINGS.
        val bg: com.ywwynm.everythingdone.model.ThingBackground =
                com.ywwynm.everythingdone.model.ThingBackground.fromRandom()!!
        return "insert into " + Def.Database.TABLE_THINGS + " values(" + "'" +
                id + "', '" +
                type + "', '" +
                Thing.UNDERWAY + "', '" +
                bg.representativeColor() + "', '" +
                (if (titleRes != 0) mContext!!.getString(titleRes) else "") + "', '" +
                mContext!!.getString(contentRes) + "', " +
                "''" + ", '" +
                id + "', '" +
                System.currentTimeMillis() + "', '" +
                System.currentTimeMillis() + "', " +
                "'0', '" +
                bg.toJson() + "')"
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

    companion object {
        const val TAG: String = "DBHelper"

        private const val SQL_CREATE_TABLE_THINGS: String = "create table if not exists " +
                Def.Database.TABLE_THINGS + " (" +
                    Def.Database.COLUMN_ID_THINGS          + " integer primary key, " +
                    Def.Database.COLUMN_TYPE_THINGS        + " integer not null, " +
                    Def.Database.COLUMN_STATE_THINGS       + " integer not null, " +
                    Def.Database.COLUMN_COLOR_THINGS       + " integer, " +
                    Def.Database.COLUMN_TITLE_THINGS       + " text, " +
                    Def.Database.COLUMN_CONTENT_THINGS     + " text, " +
                    Def.Database.COLUMN_ATTACHMENT_THINGS  + " text, " +
                    Def.Database.COLUMN_LOCATION_THINGS    + " integer, " +
                    Def.Database.COLUMN_CREATE_TIME_THINGS + " integer, " +
                    Def.Database.COLUMN_UPDATE_TIME_THINGS + " integer, " +
                    Def.Database.COLUMN_FINISH_TIME_THINGS + " integer, " +
                    Def.Database.COLUMN_BACKGROUND_THINGS  + " text" /* added in version 9 */ +
                ")"

        private const val SQL_CREATE_TABLE_REMINDERS: String = "create table if not exists " +
                Def.Database.TABLE_REMINDERS + " (" +
                    Def.Database.COLUMN_ID_REMINDERS            + " integer primary key, " +
                    Def.Database.COLUMN_NOTIFY_TIME_REMINDERS   + " integer, " +
                    Def.Database.COLUMN_STATE_REMINDERS         + " integer, " +
                    Def.Database.COLUMN_NOTIFY_MILLIS_REMINDERS + " integer, " +
                    Def.Database.COLUMN_CREATE_TIME_REMINDERS   + " integer, " +
                    Def.Database.COLUMN_UPDATE_TIME_REMINDERS   + " integer" +
                ")"

        private const val SQL_CREATE_TABLE_HABITS: String = "create table if not exists " +
                Def.Database.TABLE_HABITS + " (" +
                    Def.Database.COLUMN_ID_HABITS             + " integer primary key, " +
                    Def.Database.COLUMN_TYPE_HABITS           + " integer, " +
                    Def.Database.COLUMN_REMINDED_TIMES_HABITS + " integer, " +
                    Def.Database.COLUMN_DETAIL_HABITS         + " text, " +
                    Def.Database.COLUMN_RECORD_HABITS         + " text, " +
                    Def.Database.COLUMN_INTERVAL_INFO_HABITS  + " text, " +
                    Def.Database.COLUMN_CREATE_TIME_HABITS    + " integer, " +
                    Def.Database.COLUMN_FIRST_TIME_HABITS     + " integer" +
                ")"

        private const val SQL_CREATE_TABLE_HABIT_REMINDERS: String = "create table if not exists " +
                Def.Database.TABLE_HABIT_REMINDERS + " (" +
                    Def.Database.COLUMN_ID_HABIT_REMINDERS          + " integer primary key, " +
                    Def.Database.COLUMN_HABIT_ID_HABIT_REMINDERS    + " integer, " +
                    Def.Database.COLUMN_NOTIFY_TIME_HABIT_REMINDERS + " integer" +
                ")"

        private const val SQL_CREATE_TABLE_HABIT_RECORDS: String = "create table if not exists " +
                Def.Database.TABLE_HABIT_RECORDS + " (" +
                    Def.Database.COLUMN_ID_HABIT_RECORDS           + " integer primary key, " +
                    Def.Database.COLUMN_HABIT_ID_HABIT_RECORDS     + " integer, " +
                    Def.Database.COLUMN_HR_ID_HABIT_RECORDS        + " integer, " +
                    Def.Database.COLUMN_RECORD_TIME_HABIT_RECORDS  + " integer, " +
                    Def.Database.COLUMN_RECORD_YEAR_HABIT_RECORDS  + " integer, " +
                    Def.Database.COLUMN_RECORD_MONTH_HABIT_RECORDS + " integer, " +
                    Def.Database.COLUMN_RECORD_WEEK_HABIT_RECORDS  + " integer, " +
                    Def.Database.COLUMN_RECORD_DAY_HABIT_RECORDS   + " integer, " +
                    Def.Database.COLUMN_TYPE_HABIT_RECORDS + " integer not null default 0" +
                ")"

        private const val SQL_CREATE_TABLE_APP_WIDGET: String = "create table if not exists " +
                Def.Database.TABLE_APP_WIDGET + " (" +
                    Def.Database.COLUMN_ID_APP_WIDGET       + " integer primary key, " +
                    Def.Database.COLUMN_THING_ID_APP_WIDGET + " integer not null, " +
                    Def.Database.COLUMN_SIZE_APP_WIDGET     + " integer not null, " /* added in version 3 */ +
                    Def.Database.COLUMN_ALPHA_APP_WIDGET    + " integer not null default 100, " /* added in version 4 */ +
                    Def.Database.COLUMN_STYLE_APP_WIDGET    + " integer not null default 0, " /* added in version 5 */ +
                    "foreign key(" +
                        Def.Database.COLUMN_THING_ID_APP_WIDGET +
                    ") references " +
                    Def.Database.COLUMN_ID_THINGS + "(" +
                        Def.Database.TABLE_THINGS +
                    ")" +
                ")"

        // added on 2016/11/9
        private const val SQL_CREATE_TABLE_DOING_RECORDS: String = "create table if not exists " +
                Def.Database.TABLE_DOING_RECORDS + " (" +
                    Def.Database.COLUMN_ID_DOING                 + " integer primary key autoincrement, " +
                    Def.Database.COLUMN_THING_ID_DOING           + " integer not null, " +
                    Def.Database.COLUMN_THING_TYPE_DOING         + " integer not null, " +
                    Def.Database.COLUMN_ADD5_TIMES_DOING         + " integer not null, " +
                    Def.Database.COLUMN_PLAYED_TIMES_DOING       + " integer not null, " +
                    Def.Database.COLUMN_TOTAL_PLAY_TIME_DOING    + " integer not null, " +
                    Def.Database.COLUMN_PREDICT_DOING_TIME_DOING + " integer not null, " +
                    Def.Database.COLUMN_START_TIME_DOING         + " integer not null, " +
                    Def.Database.COLUMN_END_TIME_DOING           + " integer not null, " +
                    Def.Database.COLUMN_STOP_REASON_DOING        + " integer not null, " +
                    Def.Database.COLUMN_START_TYPE_DOING         + " integer not null default 0, " +
                    Def.Database.COLUMN_SHOULD_ASM_DOING         + " integer not null default 0" +
                ")"

        // Phase 3+: the things table grew a 12th column `background TEXT` (added
        // in DB v9). Fresh installs run onCreate() with the 12-column schema, so
        // this INSERT must provide 12 values — trailing NULL means Thing(Cursor)
        // falls back to ThingBackground.pure(color) for this header row, which
        // matches plan §4.1.8 ("header row's color = -14784871 is a valid PURE
        // colour, background field NULL is fine").
        private val SQL_INSERT_HEADER: String = "insert into " +
                Def.Database.TABLE_THINGS + " values(" +
                "'7', '" +
                Thing.HEADER +
                "', '" +
                Thing.UNDERWAY +
                "', '-14784871', 'Let this be my last words', 'I trust thy love', 'to QQ', '7', '" +
                System.currentTimeMillis() + "', '" +
                System.currentTimeMillis() + "', '0', NULL)"

        private const val SQL_ADD_COLUMN_ALPHA_APP_WIDGET: String = "alter table " +
                Def.Database.TABLE_APP_WIDGET +
                " add column " + Def.Database.COLUMN_ALPHA_APP_WIDGET + " integer not null default 100"

        private const val SQL_ADD_COLUMN_STYLE_APP_WIDGET: String = "alter table " +
                Def.Database.TABLE_APP_WIDGET +
                " add column " + Def.Database.COLUMN_STYLE_APP_WIDGET + " integer not null default 0"

        private const val SQL_ADD_COLUMN_START_TYPE_DOING_RECORD: String = "alter table " +
                Def.Database.TABLE_DOING_RECORDS +
                " add column " + Def.Database.COLUMN_START_TYPE_DOING + " integer not null default 0"

        private const val SQL_ADD_COLUMN_SHOULD_ASM_DOING_RECORD: String = "alter table " +
                Def.Database.TABLE_DOING_RECORDS +
                " add column " + Def.Database.COLUMN_SHOULD_ASM_DOING + " integer not null default 0"

        private const val SQL_ADD_COLUMN_TYPE_HABIT_RECORD: String = "alter table " +
                Def.Database.TABLE_HABIT_RECORDS +
                " add column " + Def.Database.COLUMN_TYPE_HABIT_RECORDS + " integer not null default 0"

        private const val SQL_ADD_COLUMN_BACKGROUND_THINGS: String = "alter table " +
                Def.Database.TABLE_THINGS +
                " add column " + Def.Database.COLUMN_BACKGROUND_THINGS + " text"

        private const val SQL_DROP_TABLE_THINGS: String = "drop table if exists " +
                Def.Database.TABLE_THINGS

        private const val SQL_DROP_TABLE_REMINDERS: String = "drop table if exists " +
                Def.Database.TABLE_REMINDERS

        private const val SQL_DROP_TABLE_HABITS: String = "drop table if exists " +
                Def.Database.TABLE_HABITS

        private const val SQL_DROP_TABLE_HABIT_REMINDERS: String = "drop table if exists " +
                Def.Database.TABLE_HABIT_REMINDERS

        private const val SQL_DROP_TABLE_HABIT_RECORDS: String = "drop table if exists " +
                Def.Database.TABLE_HABIT_RECORDS

        private const val SQL_DROP_TABLE_APP_WIDGET: String = "drop table if exists " +
                Def.Database.TABLE_APP_WIDGET

        private const val SQL_DROP_TABLE_DOING_RECORDS: String = "drop table if exists " +
                Def.Database.TABLE_DOING_RECORDS
    }
}
