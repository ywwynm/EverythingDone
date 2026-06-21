package com.ywwynm.everythingdone.model

import android.content.Context
import android.database.Cursor
import androidx.annotation.IntDef
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R

/**
 * Created by ywwynm on 2016/8/2.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * thing widget info
 */
open class ThingWidgetInfo(
    var id: Int,
    var thingId: Long,
    var size: Int,
    var alpha: Int,
    var style: Int,
    var targetFolderId: Long?,
    var typeFilterMask: Int,
    var displayMode: Int,
    var status: Int = Def.ThingStatus.UNDERWAY
) {

    constructor(cursor: Cursor?) : this(
        cursor!!.getInt(cursor.getColumnIndex(Def.Database.COLUMN_ID_APP_WIDGET)),
        cursor.getLong(cursor.getColumnIndex(Def.Database.COLUMN_THING_ID_APP_WIDGET)),
        cursor.getInt(cursor.getColumnIndex(Def.Database.COLUMN_SIZE_APP_WIDGET)),
        cursor.getInt(cursor.getColumnIndex(Def.Database.COLUMN_ALPHA_APP_WIDGET)),
        cursor.getInt(cursor.getColumnIndex(Def.Database.COLUMN_STYLE_APP_WIDGET)),
        getNullableLong(cursor, Def.Database.COLUMN_TARGET_FOLDER_ID_APP_WIDGET),
        getOptionalInt(
            cursor,
            Def.Database.COLUMN_TYPE_FILTER_MASK_APP_WIDGET,
            legacyTypeFilterMask(cursor.getLong(cursor.getColumnIndex(Def.Database.COLUMN_THING_ID_APP_WIDGET)))
        ),
        getOptionalInt(cursor, Def.Database.COLUMN_DISPLAY_MODE_APP_WIDGET, DISPLAY_MODE_LIST),
        getOptionalInt(cursor, Def.Database.COLUMN_STATUS_APP_WIDGET, Def.ThingStatus.UNDERWAY)
    )

    @IntDef(
        0, 1, 2, 3,
        4, 5, 6, 7,
        8, 9, 10, 11, 12, 13, 14,
        15, 16, 17, 18, 19, 20, 21, 22, 23
    )
    @Retention(AnnotationRetention.SOURCE)
    annotation class Size

    @IntDef(0, 1)
    @Retention(AnnotationRetention.SOURCE)
    annotation class Style

    @IntDef(0, 1)
    @Retention(AnnotationRetention.SOURCE)
    annotation class DisplayMode

    companion object {
        const val LIST_WIDGET_THING_ID: Long = -1L

        const val TYPE_FILTER_ALL: Int = 0
        const val TYPE_FILTER_NOTE: Int = 1
        const val TYPE_FILTER_REMINDER: Int = 1 shl 1
        const val TYPE_FILTER_HABIT: Int = 1 shl 2
        const val TYPE_FILTER_GOAL: Int = 1 shl 3
        const val TYPE_FILTER_SPECIFIC_MASK: Int = TYPE_FILTER_NOTE or
            TYPE_FILTER_REMINDER or TYPE_FILTER_HABIT or TYPE_FILTER_GOAL

        const val DISPLAY_MODE_LIST: Int = 0
        const val DISPLAY_MODE_GRID: Int = 1

        const val SIZE_TINY: Int   = 0
        const val SIZE_SMALL: Int  = 1
        const val SIZE_MIDDLE: Int = 2
        const val SIZE_LARGE: Int  = 3
        const val SIZE_4X2: Int    = 4
        const val SIZE_2X4: Int    = 5
        const val SIZE_4X3: Int    = 6
        const val SIZE_3X4: Int    = 7
        const val SIZE_5X2: Int    = 8
        const val SIZE_2X5: Int    = 9
        const val SIZE_5X3: Int    = 10
        const val SIZE_3X5: Int    = 11
        const val SIZE_5X4: Int    = 12
        const val SIZE_4X5: Int    = 13
        const val SIZE_5X5: Int    = 14
        const val SIZE_6X2: Int    = 15
        const val SIZE_2X6: Int    = 16
        const val SIZE_6X3: Int    = 17
        const val SIZE_3X6: Int    = 18
        const val SIZE_6X4: Int    = 19
        const val SIZE_4X6: Int    = 20
        const val SIZE_6X5: Int    = 21
        const val SIZE_5X6: Int    = 22
        const val SIZE_6X6: Int    = 23

        const val HEADER_ALPHA_0: Int = -19950129

        const val STYLE_NORMAL: Int = 0
        const val STYLE_SIMPLE: Int = 1

        fun normalizedTypeFilterMask(mask: Int): Int {
            val specificMask = mask and TYPE_FILTER_SPECIFIC_MASK
            return if (specificMask == 0) TYPE_FILTER_ALL else specificMask
        }

        fun isAllTypeFilter(mask: Int): Boolean {
            return normalizedTypeFilterMask(mask) == TYPE_FILTER_ALL
        }

        fun typeFilterMaskForThingType(type: Int): Int {
            return when (type) {
                Thing.NOTE -> TYPE_FILTER_NOTE
                Thing.REMINDER -> TYPE_FILTER_REMINDER
                Thing.HABIT -> TYPE_FILTER_HABIT
                Thing.GOAL -> TYPE_FILTER_GOAL
                else -> TYPE_FILTER_ALL
            }
        }

        fun statusForLegacyLimit(limit: Int): Int {
            return when (limit) {
                Def.LimitForGettingThings.ALL_FINISHED -> Def.ThingStatus.FINISHED
                Def.LimitForGettingThings.ALL_DELETED -> Def.ThingStatus.DELETED
                else -> Def.ThingStatus.UNDERWAY
            }
        }

        fun isSpecificTypeFilterMask(mask: Int): Boolean {
            return normalizedTypeFilterMask(mask) != TYPE_FILTER_ALL
        }

        fun getTypeFilterTitle(context: Context, mask: Int): String? {
            val normalized = normalizedTypeFilterMask(mask)
            if (normalized == TYPE_FILTER_ALL) return null
            val titles = ArrayList<String>(4)
            if (normalized and TYPE_FILTER_NOTE != 0) {
                titles.add(context.getString(R.string.note))
            }
            if (normalized and TYPE_FILTER_REMINDER != 0) {
                titles.add(context.getString(R.string.reminder))
            }
            if (normalized and TYPE_FILTER_HABIT != 0) {
                titles.add(context.getString(R.string.habit))
            }
            if (normalized and TYPE_FILTER_GOAL != 0) {
                titles.add(context.getString(R.string.goal))
            }
            return titles.joinToString("/")
        }

        private fun legacyTypeFilterMask(thingId: Long): Int {
            if (thingId >= 0L) return TYPE_FILTER_ALL
            val legacyLimit = (-thingId - 1).toInt()
            return when (legacyLimit) {
                Def.LimitForGettingThings.NOTE_UNDERWAY -> TYPE_FILTER_NOTE
                Def.LimitForGettingThings.REMINDER_UNDERWAY -> TYPE_FILTER_REMINDER
                Def.LimitForGettingThings.HABIT_UNDERWAY -> TYPE_FILTER_HABIT
                Def.LimitForGettingThings.GOAL_UNDERWAY -> TYPE_FILTER_GOAL
                else -> TYPE_FILTER_ALL
            }
        }

        private fun getOptionalInt(cursor: Cursor, columnName: String, defaultValue: Int): Int {
            val index = cursor.getColumnIndex(columnName)
            if (index < 0 || cursor.isNull(index)) return defaultValue
            return cursor.getInt(index)
        }

        private fun getNullableLong(cursor: Cursor, columnName: String): Long? {
            val index = cursor.getColumnIndex(columnName)
            if (index < 0 || cursor.isNull(index)) return null
            return cursor.getLong(index)
        }
    }
}
