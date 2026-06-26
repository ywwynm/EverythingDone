package com.ywwynm.everythingdone.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
import com.ywwynm.everythingdone.model.HomeEmptyStateHistory
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingCardAppearance
import com.ywwynm.everythingdone.model.ThingsCounts

/**
 * Created by ywwynm on 2015/5/21.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Database layer.
 */
open class DBHelper(context: Context?) : SQLiteOpenHelper(context, Def.Meta.DATABASE_NAME, null, Def.Meta.DATABASE_VERSION) {

    private var mContext: Context? = context

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_TABLE_THINGS)
        db.execSQL(SQL_CREATE_TABLE_THING_FOLDERS)
        db.execSQL(SQL_CREATE_INDEX_THINGS_FOLDER_ID)
        db.execSQL(SQL_CREATE_INDEX_THING_FOLDERS_PARENT_FOLDER_ID)

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

        HomeEmptyStateHistory.initialize(mContext, false, emptySet())
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
        if (oldVersion < 9 && !columnExists(
                db, Def.Database.TABLE_THINGS, Def.Database.COLUMN_BACKGROUND_THINGS
            )) {
            db.execSQL(SQL_ADD_COLUMN_BACKGROUND_THINGS)
        }
        if (oldVersion < 13) {
            migrateThingCardSettingsColumns(db)
            migrateThingCardAppearanceColumn(db)
        }
        if (oldVersion < 14) {
            migrateDetailAttachmentMediaAppearanceColumn(db)
        }
        if (oldVersion < 15) {
            migrateThingFolders(db)
        }
        if (oldVersion < 16) {
            migrateAppWidgetProjectionColumns(db)
        }
        if (oldVersion < 17) {
            migrateHomeEmptyState(db)
        }
        if (oldVersion < 18) {
            migrateStateBeforeDeleteColumn(db)
        }
        if (oldVersion < 19) {
            migrateAppWidgetStatusColumn(db)
        }
        if (oldVersion < 20) {
            migrateFoldersToSkeletonModel(db)
        }
        if (oldVersion < 21) {
            migrateChecklistItemLevels(db)
        }
        // released version should be 1, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21.
    }

    fun ensureHomeEmptyStateData(db: SQLiteDatabase) {
        initializeHomeEmptyStateHistoryIfNeeded(db)
        deleteLegacyPlaceholderThings(db)
    }

    private fun migrateHomeEmptyState(db: SQLiteDatabase) {
        initializeHomeEmptyStateHistoryIfNeeded(db)
        deleteLegacyPlaceholderThings(db)
    }

    private fun initializeHomeEmptyStateHistoryIfNeeded(db: SQLiteDatabase) {
        if (HomeEmptyStateHistory.isInitialized(mContext)) return

        val createdTypes = HashSet<Int>()
        var hasUserContent = false
        db.query(
            Def.Database.TABLE_THINGS,
            arrayOf(Def.Database.COLUMN_TYPE_THINGS),
            Def.Database.COLUMN_TYPE_THINGS + ">=" + Thing.NOTE +
                " and " + Def.Database.COLUMN_TYPE_THINGS + "<=" + Thing.GOAL,
            null,
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getInt(0)
                createdTypes.add(type)
                hasUserContent = true
            }
        }

        if (tableExists(db, Def.Database.TABLE_THING_FOLDERS)) {
            db.rawQuery(
                "select count(*) from " + Def.Database.TABLE_THING_FOLDERS,
                null
            ).use { cursor ->
                if (cursor.moveToFirst() && cursor.getInt(0) > 0) {
                    hasUserContent = true
                }
            }
        }

        val counts = mContext!!.getSharedPreferences(
            Def.Meta.THINGS_COUNTS_NAME,
            Context.MODE_PRIVATE
        )
        var type = Thing.NOTE
        while (type <= Thing.GOAL) {
            if (counts.getInt(type.toString() + "_" + ThingsCounts.ALL, 0) > 0) {
                createdTypes.add(type)
                hasUserContent = true
            }
            type++
        }

        HomeEmptyStateHistory.initialize(mContext, hasUserContent, createdTypes)
    }

    private fun deleteLegacyPlaceholderThings(db: SQLiteDatabase) {
        db.delete(
            Def.Database.TABLE_THINGS,
            "(" + Def.Database.COLUMN_TYPE_THINGS + ">=" + Thing.WELCOME_UNDERWAY +
                " and " + Def.Database.COLUMN_TYPE_THINGS + "<=" + Thing.WELCOME_GOAL +
                ") or (" + Def.Database.COLUMN_TYPE_THINGS + ">=" + Thing.NOTIFY_EMPTY_UNDERWAY +
                " and " + Def.Database.COLUMN_TYPE_THINGS + "<=" + Thing.NOTIFY_EMPTY_DELETED + ")",
            null
        )
    }

    private fun migrateThingCardSettingsColumns(db: SQLiteDatabase) {
        val hasLegacySpanMode = columnExists(
            db, Def.Database.TABLE_THINGS,
            Def.Database.COLUMN_LEGACY_HOME_CARD_SPAN_MODE_THINGS
        )
        val hasLegacyImagePlacement = columnExists(
            db, Def.Database.TABLE_THINGS,
            Def.Database.COLUMN_LEGACY_HOME_CARD_IMAGE_PLACEMENT_THINGS
        )

        if (!columnExists(
                db, Def.Database.TABLE_THINGS,
                Def.Database.COLUMN_THING_CARD_SPAN_MODE_THINGS
            )) {
            db.execSQL(SQL_ADD_COLUMN_THING_CARD_SPAN_MODE_THINGS)
        }
        if (!columnExists(
                db, Def.Database.TABLE_THINGS,
                Def.Database.COLUMN_THING_CARD_IMAGE_PLACEMENT_THINGS
            )) {
            db.execSQL(SQL_ADD_COLUMN_THING_CARD_IMAGE_PLACEMENT_THINGS)
        }

        if (hasLegacySpanMode) {
            db.execSQL(SQL_COPY_THING_CARD_SPAN_MODE_FROM_LEGACY)
        }
        if (hasLegacyImagePlacement) {
            db.execSQL(SQL_COPY_THING_CARD_IMAGE_PLACEMENT_FROM_LEGACY)
        }
    }

    private fun migrateThingCardAppearanceColumn(db: SQLiteDatabase) {
        if (!columnExists(
                db, Def.Database.TABLE_THINGS,
                Def.Database.COLUMN_THING_CARD_APPEARANCE_THINGS
            )) {
            db.execSQL(SQL_ADD_COLUMN_THING_CARD_APPEARANCE_THINGS)
        }
        db.execSQL(SQL_MIGRATE_THING_CARD_APPEARANCE_FROM_LEGACY_COLUMNS)
    }

    private fun migrateDetailAttachmentMediaAppearanceColumn(db: SQLiteDatabase) {
        if (!columnExists(
                db, Def.Database.TABLE_THINGS,
                Def.Database.COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS
            )) {
            db.execSQL(SQL_ADD_COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS)
        }
        db.execSQL(SQL_MIGRATE_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_DEFAULT)
    }

    private fun migrateThingFolders(db: SQLiteDatabase) {
        if (!columnExists(
                db, Def.Database.TABLE_THINGS,
                Def.Database.COLUMN_FOLDER_ID_THINGS
            )) {
            db.execSQL(SQL_ADD_COLUMN_FOLDER_ID_THINGS)
        }
        db.execSQL(SQL_CREATE_TABLE_THING_FOLDERS)
        db.execSQL(SQL_CREATE_INDEX_THINGS_FOLDER_ID)
        db.execSQL(SQL_CREATE_INDEX_THING_FOLDERS_PARENT_FOLDER_ID)
    }

    private fun migrateStateBeforeDeleteColumn(db: SQLiteDatabase) {
        if (!columnExists(
                db, Def.Database.TABLE_THINGS,
                Def.Database.COLUMN_STATE_BEFORE_DELETE_THINGS
            )) {
            db.execSQL(SQL_ADD_COLUMN_STATE_BEFORE_DELETE_THINGS)
        }
    }

    /**
     * 多级清单项迁移（v21，ADR-0010）：旧清单串每个真实项是 `<状态位><文本>`，没有层级位。
     * 扫描所有内容为清单串的 Thing，把每个真实项插入层级位 `1`（全部置一级），改写为
     * `<状态位><层级位><文本>`。纯字符串变换，逐行重写 content 列。
     */
    private fun migrateChecklistItemLevels(db: SQLiteDatabase) {
        val toUpdate = ArrayList<Pair<Long, String>>()
        db.query(
            Def.Database.TABLE_THINGS,
            arrayOf(Def.Database.COLUMN_ID_THINGS, Def.Database.COLUMN_CONTENT_THINGS),
            null, null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val content = cursor.getString(1) ?: continue
                if (!CheckListHelper.isCheckListStr(content)) continue
                val migrated = CheckListHelper.migrateToLeveledFormat(content)
                if (migrated != content) {
                    toUpdate.add(cursor.getLong(0) to migrated)
                }
            }
        }
        for ((id, migrated) in toUpdate) {
            try {
                val cv = ContentValues()
                cv.put(Def.Database.COLUMN_CONTENT_THINGS, migrated)
                db.update(
                    Def.Database.TABLE_THINGS, cv,
                    Def.Database.COLUMN_ID_THINGS + "=?", arrayOf(id.toString())
                )
            } catch (e: Exception) {
                Log.e(TAG, "migrateChecklistItemLevels failed for id=$id", e)
            }
        }
    }

    private fun migrateAppWidgetStatusColumn(db: SQLiteDatabase) {
        if (!columnExists(
                db, Def.Database.TABLE_APP_WIDGET,
                Def.Database.COLUMN_STATUS_APP_WIDGET
            )) {
            db.execSQL(SQL_ADD_COLUMN_STATUS_APP_WIDGET)
        }
    }

    /**
     * Pure-skeleton model migration (v20): folders no longer carry a deletion state.
     * Convert every previously soft-deleted folder into the content-level model —
     * trash the Things that were only effectively deleted by a deleted ancestor
     * folder (recording their pre-trash state), then clear every folder's DELETED
     * state back to underway so folders become a pure structural skeleton.
     */
    private fun migrateFoldersToSkeletonModel(db: SQLiteDatabase) {
        val parentOf = HashMap<Long, Long?>()
        val stateOf = HashMap<Long, Int>()
        val cursor = db.query(
            Def.Database.TABLE_THING_FOLDERS,
            arrayOf(
                Def.Database.COLUMN_ID_THING_FOLDERS,
                Def.Database.COLUMN_PARENT_FOLDER_ID_THING_FOLDERS,
                Def.Database.COLUMN_STATE_THING_FOLDERS
            ),
            null, null, null, null, null
        )
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                parentOf[id] = if (it.isNull(1)) null else it.getLong(1)
                stateOf[id] = it.getInt(2)
            }
        }
        if (stateOf.values.none { it == Thing.DELETED }) return

        // Folders whose subtree Things were hidden by a deleted ancestor (or self).
        val effectivelyDeleted = HashSet<Long>()
        for (id in stateOf.keys) {
            var cur: Long? = id
            val visited = HashSet<Long>()
            while (cur != null && visited.add(cur)) {
                if (stateOf[cur] == Thing.DELETED) {
                    effectivelyDeleted.add(id)
                    break
                }
                cur = parentOf[cur]
            }
        }

        if (effectivelyDeleted.isNotEmpty()) {
            val idList = effectivelyDeleted.joinToString(",")
            db.execSQL(
                "update " + Def.Database.TABLE_THINGS +
                    " set " + Def.Database.COLUMN_STATE_BEFORE_DELETE_THINGS + "=" +
                    Def.Database.COLUMN_STATE_THINGS + ", " +
                    Def.Database.COLUMN_STATE_THINGS + "=" + Thing.DELETED +
                    " where " + Def.Database.COLUMN_STATE_THINGS + " in (" +
                    Thing.UNDERWAY + "," + Thing.FINISHED + ")" +
                    " and " + Def.Database.COLUMN_FOLDER_ID_THINGS + " in (" + idList + ")"
            )
        }
        db.execSQL(
            "update " + Def.Database.TABLE_THING_FOLDERS +
                " set " + Def.Database.COLUMN_STATE_THING_FOLDERS + "=" + Thing.UNDERWAY +
                " where " + Def.Database.COLUMN_STATE_THING_FOLDERS + "=" + Thing.DELETED
        )
    }

    private fun migrateAppWidgetProjectionColumns(db: SQLiteDatabase) {
        if (!columnExists(
                db,
                Def.Database.TABLE_APP_WIDGET,
                Def.Database.COLUMN_TARGET_FOLDER_ID_APP_WIDGET
            )) {
            db.execSQL(SQL_ADD_COLUMN_TARGET_FOLDER_ID_APP_WIDGET)
        }
        if (!columnExists(
                db,
                Def.Database.TABLE_APP_WIDGET,
                Def.Database.COLUMN_TYPE_FILTER_MASK_APP_WIDGET
            )) {
            db.execSQL(SQL_ADD_COLUMN_TYPE_FILTER_MASK_APP_WIDGET)
        }
        if (!columnExists(
                db,
                Def.Database.TABLE_APP_WIDGET,
                Def.Database.COLUMN_DISPLAY_MODE_APP_WIDGET
            )) {
            db.execSQL(SQL_ADD_COLUMN_DISPLAY_MODE_APP_WIDGET)
        }
        db.execSQL(SQL_MIGRATE_APP_WIDGET_LIST_NOTE_MASK)
        db.execSQL(SQL_MIGRATE_APP_WIDGET_LIST_REMINDER_MASK)
        db.execSQL(SQL_MIGRATE_APP_WIDGET_LIST_HABIT_MASK)
        db.execSQL(SQL_MIGRATE_APP_WIDGET_LIST_GOAL_MASK)
        db.execSQL(SQL_MIGRATE_APP_WIDGET_LIST_ALL_MASK)
    }

    private fun columnExists(db: SQLiteDatabase, tableName: String, columnName: String): Boolean {
        db.rawQuery("pragma table_info($tableName)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return false

            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == columnName) return true
            }
        }
        return false
    }

    private fun tableExists(db: SQLiteDatabase, tableName: String): Boolean {
        db.rawQuery(
            "select name from sqlite_master where type='table' and name=?",
            arrayOf(tableName)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (newVersion < oldVersion) {
            onUpgrade(db, newVersion, oldVersion)
            db.version = oldVersion
        }
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
                    Def.Database.COLUMN_BACKGROUND_THINGS  + " text, " /* added in version 9 */ +
                    Def.Database.COLUMN_THING_CARD_SPAN_MODE_THINGS +
                        " integer not null default 0, " /* renamed in version 12 */ +
                    Def.Database.COLUMN_THING_CARD_IMAGE_PLACEMENT_THINGS +
                        " integer not null default 0, " /* renamed in version 12 */ +
                    Def.Database.COLUMN_THING_CARD_APPEARANCE_THINGS +
                        " text, " /* added in version 13 */ +
                    Def.Database.COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS +
                        " text, " /* added in version 14 */ +
                    Def.Database.COLUMN_FOLDER_ID_THINGS +
                        " integer default null, " /* added in version 15 */ +
                    Def.Database.COLUMN_STATE_BEFORE_DELETE_THINGS +
                        " integer default null" /* added in version 18 */ +
                ")"

        private const val SQL_CREATE_TABLE_THING_FOLDERS: String =
            "create table if not exists " + Def.Database.TABLE_THING_FOLDERS + " (" +
                Def.Database.COLUMN_ID_THING_FOLDERS + " integer primary key, " +
                Def.Database.COLUMN_PARENT_FOLDER_ID_THING_FOLDERS + " integer default null, " +
                Def.Database.COLUMN_TITLE_THING_FOLDERS + " text not null, " +
                Def.Database.COLUMN_STATE_THING_FOLDERS + " integer not null, " +
                Def.Database.COLUMN_COLOR_THING_FOLDERS + " integer, " +
                Def.Database.COLUMN_BACKGROUND_THING_FOLDERS + " text, " +
                Def.Database.COLUMN_LOCATION_THING_FOLDERS + " integer, " +
                Def.Database.COLUMN_IS_PRIVATE_THING_FOLDERS + " integer not null default 0, " +
                Def.Database.COLUMN_CREATE_TIME_THING_FOLDERS + " integer, " +
                Def.Database.COLUMN_UPDATE_TIME_THING_FOLDERS + " integer, " +
                Def.Database.COLUMN_CARD_PRESENTATION_THING_FOLDERS + " text" +
            ")"

        private const val SQL_CREATE_INDEX_THINGS_FOLDER_ID: String =
            "create index if not exists idx_things_folder_id on " +
                Def.Database.TABLE_THINGS + " (" + Def.Database.COLUMN_FOLDER_ID_THINGS + ")"

        private const val SQL_CREATE_INDEX_THING_FOLDERS_PARENT_FOLDER_ID: String =
            "create index if not exists idx_thing_folders_parent_folder_id on " +
                Def.Database.TABLE_THING_FOLDERS + " (" +
                Def.Database.COLUMN_PARENT_FOLDER_ID_THING_FOLDERS + ")"

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
                    Def.Database.COLUMN_TARGET_FOLDER_ID_APP_WIDGET + " integer default null, " /* added in version 16 */ +
                    Def.Database.COLUMN_TYPE_FILTER_MASK_APP_WIDGET + " integer not null default 0, " /* added in version 16 */ +
                    Def.Database.COLUMN_DISPLAY_MODE_APP_WIDGET + " integer not null default 0, " /* added in version 16 */ +
                    Def.Database.COLUMN_STATUS_APP_WIDGET + " integer not null default 0, " /* added in version 19 */ +
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

        // Phase 3+: the things table grew extra trailing columns after the
        // original schema. Fresh installs run onCreate() with the latest schema,
        // so this INSERT must provide values for all columns. The NULL background
        // means Thing(Cursor)
        // falls back to ThingBackground.pure(color) for this header row, which
        // matches plan §4.1.8 ("header row's color = -14784871 is a valid PURE
        // colour, background field NULL is fine").
        private val SQL_INSERT_HEADER: String = "insert into " +
                Def.Database.TABLE_THINGS + " values(" +
                "'0', '" +
                Thing.HEADER +
                "', '" +
                Thing.UNDERWAY +
                "', '-14784871', 'Let this be my last words', 'I trust thy love', 'to QQ', '0', '" +
                System.currentTimeMillis() + "', '" +
                System.currentTimeMillis() + "', '0', NULL, '" +
                Thing.THING_CARD_SPAN_NORMAL + "', '" +
                Thing.THING_CARD_IMAGE_PLACEMENT_DEFAULT + "', '" +
                ThingCardAppearance.default().toJson() + "', '" +
                DetailAttachmentMediaAppearance.default().toJson() + "', NULL)"

        private const val SQL_ADD_COLUMN_ALPHA_APP_WIDGET: String = "alter table " +
                Def.Database.TABLE_APP_WIDGET +
                " add column " + Def.Database.COLUMN_ALPHA_APP_WIDGET + " integer not null default 100"

        private const val SQL_ADD_COLUMN_STYLE_APP_WIDGET: String = "alter table " +
                Def.Database.TABLE_APP_WIDGET +
                " add column " + Def.Database.COLUMN_STYLE_APP_WIDGET + " integer not null default 0"

        private const val SQL_ADD_COLUMN_TARGET_FOLDER_ID_APP_WIDGET: String =
            "alter table " + Def.Database.TABLE_APP_WIDGET +
                " add column " + Def.Database.COLUMN_TARGET_FOLDER_ID_APP_WIDGET +
                " integer default null"

        private const val SQL_ADD_COLUMN_TYPE_FILTER_MASK_APP_WIDGET: String =
            "alter table " + Def.Database.TABLE_APP_WIDGET +
                " add column " + Def.Database.COLUMN_TYPE_FILTER_MASK_APP_WIDGET +
                " integer not null default 0"

        private const val SQL_ADD_COLUMN_DISPLAY_MODE_APP_WIDGET: String =
            "alter table " + Def.Database.TABLE_APP_WIDGET +
                " add column " + Def.Database.COLUMN_DISPLAY_MODE_APP_WIDGET +
                " integer not null default 0"

        private const val SQL_MIGRATE_APP_WIDGET_LIST_ALL_MASK: String =
            "update " + Def.Database.TABLE_APP_WIDGET +
                " set " + Def.Database.COLUMN_TYPE_FILTER_MASK_APP_WIDGET + "=0 " +
                "where " + Def.Database.COLUMN_THING_ID_APP_WIDGET + "=-1"

        private const val SQL_MIGRATE_APP_WIDGET_LIST_NOTE_MASK: String =
            "update " + Def.Database.TABLE_APP_WIDGET +
                " set " + Def.Database.COLUMN_TYPE_FILTER_MASK_APP_WIDGET + "=1 " +
                "where " + Def.Database.COLUMN_THING_ID_APP_WIDGET + "=-2"

        private const val SQL_MIGRATE_APP_WIDGET_LIST_REMINDER_MASK: String =
            "update " + Def.Database.TABLE_APP_WIDGET +
                " set " + Def.Database.COLUMN_TYPE_FILTER_MASK_APP_WIDGET + "=2 " +
                "where " + Def.Database.COLUMN_THING_ID_APP_WIDGET + "=-3"

        private const val SQL_MIGRATE_APP_WIDGET_LIST_HABIT_MASK: String =
            "update " + Def.Database.TABLE_APP_WIDGET +
                " set " + Def.Database.COLUMN_TYPE_FILTER_MASK_APP_WIDGET + "=4 " +
                "where " + Def.Database.COLUMN_THING_ID_APP_WIDGET + "=-4"

        private const val SQL_MIGRATE_APP_WIDGET_LIST_GOAL_MASK: String =
            "update " + Def.Database.TABLE_APP_WIDGET +
                " set " + Def.Database.COLUMN_TYPE_FILTER_MASK_APP_WIDGET + "=8 " +
                "where " + Def.Database.COLUMN_THING_ID_APP_WIDGET + "=-5"

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

        private const val SQL_ADD_COLUMN_THING_CARD_SPAN_MODE_THINGS: String = "alter table " +
                Def.Database.TABLE_THINGS +
                " add column " + Def.Database.COLUMN_THING_CARD_SPAN_MODE_THINGS +
                " integer not null default 0"

        private const val SQL_ADD_COLUMN_THING_CARD_IMAGE_PLACEMENT_THINGS: String = "alter table " +
                Def.Database.TABLE_THINGS +
                " add column " + Def.Database.COLUMN_THING_CARD_IMAGE_PLACEMENT_THINGS +
                " integer not null default 0"

        private const val SQL_ADD_COLUMN_THING_CARD_APPEARANCE_THINGS: String = "alter table " +
                Def.Database.TABLE_THINGS +
                " add column " + Def.Database.COLUMN_THING_CARD_APPEARANCE_THINGS + " text"

        private const val SQL_ADD_COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS: String =
            "alter table " + Def.Database.TABLE_THINGS +
                " add column " +
                Def.Database.COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS + " text"

        private const val SQL_ADD_COLUMN_FOLDER_ID_THINGS: String =
            "alter table " + Def.Database.TABLE_THINGS +
                " add column " + Def.Database.COLUMN_FOLDER_ID_THINGS + " integer default null"

        private const val SQL_ADD_COLUMN_STATE_BEFORE_DELETE_THINGS: String =
            "alter table " + Def.Database.TABLE_THINGS +
                " add column " + Def.Database.COLUMN_STATE_BEFORE_DELETE_THINGS + " integer default null"

        private const val SQL_ADD_COLUMN_STATUS_APP_WIDGET: String =
            "alter table " + Def.Database.TABLE_APP_WIDGET +
                " add column " + Def.Database.COLUMN_STATUS_APP_WIDGET + " integer not null default 0"

        private const val SQL_COPY_THING_CARD_SPAN_MODE_FROM_LEGACY: String = "update " +
                Def.Database.TABLE_THINGS +
                " set " + Def.Database.COLUMN_THING_CARD_SPAN_MODE_THINGS + " = " +
                Def.Database.COLUMN_LEGACY_HOME_CARD_SPAN_MODE_THINGS +
                " where " + Def.Database.COLUMN_LEGACY_HOME_CARD_SPAN_MODE_THINGS + " is not null"

        private const val SQL_COPY_THING_CARD_IMAGE_PLACEMENT_FROM_LEGACY: String = "update " +
                Def.Database.TABLE_THINGS +
                " set " + Def.Database.COLUMN_THING_CARD_IMAGE_PLACEMENT_THINGS + " = " +
                Def.Database.COLUMN_LEGACY_HOME_CARD_IMAGE_PLACEMENT_THINGS +
                " where " + Def.Database.COLUMN_LEGACY_HOME_CARD_IMAGE_PLACEMENT_THINGS +
                " is not null"

        private const val SQL_MIGRATE_THING_CARD_APPEARANCE_FROM_LEGACY_COLUMNS: String = "update " +
                Def.Database.TABLE_THINGS +
                " set " + Def.Database.COLUMN_THING_CARD_APPEARANCE_THINGS + " = " +
                "'{\"version\":1,\"spanMode\":' || " +
                "coalesce(" + Def.Database.COLUMN_THING_CARD_SPAN_MODE_THINGS + ",0) || " +
                "',\"imagePlacement\":' || " +
                "coalesce(" + Def.Database.COLUMN_THING_CARD_IMAGE_PLACEMENT_THINGS + ",0) || " +
                "',\"sideMediaWidthPercent\":42,\"appearanceUpdateTime\":0," +
                "\"mediaSourceKey\":null,\"mediaBackgroundEnabled\":false,\"sources\":{}}' " +
                " where " + Def.Database.COLUMN_THING_CARD_APPEARANCE_THINGS + " is null " +
                "or trim(" + Def.Database.COLUMN_THING_CARD_APPEARANCE_THINGS + ") = ''"

        private const val SQL_MIGRATE_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_DEFAULT: String =
            "update " + Def.Database.TABLE_THINGS +
                " set " + Def.Database.COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS +
                " = '" + DetailAttachmentMediaAppearance.DEFAULT_JSON + "' " +
                " where " + Def.Database.COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS +
                " is null or trim(" +
                Def.Database.COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS + ") = ''"

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
