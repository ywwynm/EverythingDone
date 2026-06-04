package com.ywwynm.everythingdone.model

import android.content.Context
import android.database.Cursor
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.DrawableRes
import androidx.annotation.IntDef
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.SystemNotificationUtil
import androidx.core.content.edit

/**
 * Created by ywwynm on 2015/5/21.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Model layer. Related to table things.
 */
open class Thing(
    var id: Long,
    var type: Int,
    var state: Int,
    color: Int,
    var title: String?,
    var content: String?,
    var attachment: String?,
    var location: Long,
    var createTime: Long,
    var updateTime: Long,
    var finishTime: Long,
    thingCardSpanMode: Int = THING_CARD_SPAN_NORMAL,
    thingCardImagePlacement: Int = THING_CARD_IMAGE_PLACEMENT_DEFAULT,
    thingCardAppearance: ThingCardAppearance? = null
) : Parcelable {

    private var _color: Int = color
    /**
     * Background appearance — PURE or GRADIENT.
     * Persisted as JSON in `things.background` (DB v9+). For rows written by
     * older versions this is reconstructed from [_color] via
     * [ThingBackground.pure] on `Cursor` load.
     */
    private var _background: ThingBackground? = ThingBackground.pure(color)

    var selected: Boolean = false

    var thingCardAppearance: ThingCardAppearance = thingCardAppearance
        ?: ThingCardAppearance.fromLegacy(thingCardSpanMode, thingCardImagePlacement)

    var thingCardSpanMode: Int
        @ThingCardSpanMode get() = thingCardAppearance.spanMode
        set(@ThingCardSpanMode value) {
            thingCardAppearance = thingCardAppearance.withSpanMode(value)
        }

    var thingCardImagePlacement: Int
        @ThingCardImagePlacement get() = thingCardAppearance.imagePlacement
        set(@ThingCardImagePlacement value) {
            thingCardAppearance = thingCardAppearance.withImagePlacement(value)
        }

    constructor(id: Long, type: Int, color: Int, location: Long) : this(
        id, type, UNDERWAY, color, "", "", "", location,
        System.currentTimeMillis(), System.currentTimeMillis(), 0L
    )

    constructor(thing: Thing) : this(
        thing.id, thing.type, thing.state, thing._color,
        thing.title, thing.content, thing.attachment, thing.location,
        thing.createTime, thing.updateTime, thing.finishTime,
        thing.thingCardSpanMode, thing.thingCardImagePlacement,
        thing.thingCardAppearance
    ) {
        _background = thing._background
        selected = thing.selected
    }

    constructor(`in`: Parcel) : this(
        `in`.readLong(),
        `in`.readInt(),
        `in`.readInt(),
        `in`.readInt(),
        `in`.readString(),
        `in`.readString(),
        `in`.readString(),
        `in`.readLong(),
        `in`.readLong(),
        `in`.readLong(),
        `in`.readLong()
    ) {
        // NEW (Phase 3): trailing background JSON.
        val bgJson = `in`.readString()
        val bg = ThingBackground.fromJson(bgJson)
        _background = bg ?: ThingBackground.pure(_color)
        thingCardSpanMode = if (`in`.dataAvail() > 0) {
            `in`.readInt()
        } else {
            THING_CARD_SPAN_NORMAL
        }
        thingCardImagePlacement = if (`in`.dataAvail() > 0) {
            `in`.readInt()
        } else {
            THING_CARD_IMAGE_PLACEMENT_DEFAULT
        }
        if (`in`.dataAvail() > 0) {
            val appearance = ThingCardAppearance.fromJson(`in`.readString())
            if (appearance != null) {
                thingCardAppearance = appearance
            }
        }
    }

    constructor(c: Cursor?) : this(
        c!!.getLong(0),
        c.getInt(1),
        c.getInt(2),
        c.getInt(3),
        c.getString(4),
        c.getString(5),
        c.getString(6),
        c.getLong(7),
        c.getLong(8),
        c.getLong(9),
        c.getLong(10)
    ) {
        val bgCol = c.getColumnIndex(Def.Database.COLUMN_BACKGROUND_THINGS)
        if (bgCol >= 0 && !c.isNull(bgCol)) {
            val bg = ThingBackground.fromJson(c.getString(bgCol))
            if (bg != null) this._background = bg
        }
        val spanModeCol = firstExistingColumnIndex(
            c,
            Def.Database.COLUMN_THING_CARD_SPAN_MODE_THINGS,
            Def.Database.COLUMN_LEGACY_HOME_CARD_SPAN_MODE_THINGS
        )
        if (spanModeCol >= 0 && !c.isNull(spanModeCol)) {
            thingCardSpanMode = c.getInt(spanModeCol)
        }
        val imagePlacementCol = firstExistingColumnIndex(
            c,
            Def.Database.COLUMN_THING_CARD_IMAGE_PLACEMENT_THINGS,
            Def.Database.COLUMN_LEGACY_HOME_CARD_IMAGE_PLACEMENT_THINGS
        )
        if (imagePlacementCol >= 0 && !c.isNull(imagePlacementCol)) {
            thingCardImagePlacement = c.getInt(imagePlacementCol)
        }
        val appearanceCol = c.getColumnIndex(Def.Database.COLUMN_THING_CARD_APPEARANCE_THINGS)
        if (appearanceCol >= 0 && !c.isNull(appearanceCol)) {
            val appearance = ThingCardAppearance.fromJson(c.getString(appearanceCol))
            if (appearance != null) {
                thingCardAppearance = appearance
            }
        }
    }

    private fun firstExistingColumnIndex(c: Cursor, vararg columnNames: String): Int {
        for (columnName in columnNames) {
            val index = c.getColumnIndex(columnName)
            if (index >= 0) return index
        }
        return -1
    }

    open fun getColor(): Int = _color

    open fun setColor(color: Int) {
        this._color = color
        // Keep background in lock-step for legacy code paths that only set the int.
        if (_background == null || _background!!.mode == ThingBackground.Mode.PURE) {
            this._background = ThingBackground.pure(color)
        }
    }

    /**
     * The thing's visual background. Never null after construction — defaults to
     * `ThingBackground.pure(color)` when not explicitly set or when loaded
     * from a pre-v9 DB row.
     */
    open fun getBackground(): ThingBackground? {
        if (_background == null) _background = ThingBackground.pure(_color)
        return _background
    }

    open fun setBackground(background: ThingBackground?) {
        var bg = background
        if (bg == null) bg = ThingBackground.pure(_color)
        this._background = bg
        // Keep the legacy single-int color column in sync with the representative.
        this._color = bg.representativeColor()
    }

    open fun getTitleToDisplay(): String? {
        if (isPrivate()) {
            return title!!.substring(PRIVATE_THING_PREFIX.length)
        }
        return title
    }

    open fun isPrivate(): Boolean {
        return title!!.startsWith(PRIVATE_THING_PREFIX)
    }

    open fun isSelected(): Boolean {
        return selected
    }

    open fun matchSearchRequirement(keyword: String?, color: Int): Boolean {
        // Phase 5/6: the int colour from the picker is now a hue-bucket hint, not
        // an exact match. Convert it to a bucket and ask the background whether
        // ANY of its stops falls into that bucket (so a red→blue gradient thing
        // appears under both the red and the blue search). -1979711488 and 0
        // keep their legacy "no filter" meaning.
        if (color != -1979711488 && color != 0) {
            val filterBucket = com.ywwynm.everythingdone.utils.BackgroundUtil.hueBucket(color)
            val bg: ThingBackground? = if (this._background != null)
                this._background
            else
                ThingBackground.pure(this._color)
            if (!com.ywwynm.everythingdone.utils.BackgroundUtil.matchesHueBucket(bg, filterBucket)) {
                return false
            }
        }

        var curContent = content
        if (CheckListHelper.isSignalContainsStrIgnoreCase(keyword)) {
            val sbRex = StringBuilder()
            for (i in 0 until CheckListHelper.CHECK_STATE_NUM) {
                sbRex.append(CheckListHelper.SIGNAL).append(i).append("|")
            }
            sbRex.deleteCharAt(sbRex.length - 1)
            curContent = curContent!!.replace(sbRex.toString().toRegex(), "")
        }
        return curContent!!.contains(keyword!!)
    }

    override fun equals(other: Any?): Boolean {
        return this === other || other != null && javaClass === other.javaClass && id == (other as Thing).id
    }

    override fun hashCode(): Int {
        return (id xor (id ushr 32)).toInt()
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(id)
        dest.writeInt(type)
        dest.writeInt(state)
        dest.writeInt(_color)
        dest.writeString(title)
        dest.writeString(content)
        dest.writeString(attachment)
        dest.writeLong(location)
        dest.writeLong(createTime)
        dest.writeLong(updateTime)
        dest.writeLong(finishTime)
        // NEW (Phase 3): trailing background JSON.
        dest.writeString(if (_background != null) _background!!.toJson() else null)
        dest.writeInt(thingCardSpanMode)
        dest.writeInt(thingCardImagePlacement)
        dest.writeString(thingCardAppearance.toJson())
    }

    @IntDef(-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
    @Retention(AnnotationRetention.SOURCE)
    annotation class Type

    @IntDef(0, 1, 2, 3)
    @Retention(AnnotationRetention.SOURCE)
    annotation class State

    @IntDef(0, 1)
    @Retention(AnnotationRetention.SOURCE)
    annotation class ThingCardSpanMode

    @IntDef(0, 1, 2, 3, 4)
    @Retention(AnnotationRetention.SOURCE)
    annotation class ThingCardImagePlacement

    companion object {
        const val HEADER: Int                = -1
        const val NOTE: Int                  = 0
        const val REMINDER: Int              = 1
        const val HABIT: Int                 = 2
        const val GOAL: Int                  = 3
        const val WELCOME_UNDERWAY: Int      = 4
        const val WELCOME_NOTE: Int          = 5
        const val WELCOME_REMINDER: Int      = 6
        const val WELCOME_HABIT: Int         = 7
        const val WELCOME_GOAL: Int          = 8
        const val NOTIFICATION_UNDERWAY: Int = 9
        const val NOTIFICATION_NOTE: Int     = 10
        const val NOTIFICATION_REMINDER: Int = 11
        const val NOTIFICATION_HABIT: Int    = 12
        const val NOTIFICATION_GOAL: Int     = 13

        const val NOTIFY_EMPTY_UNDERWAY: Int = 14
        const val NOTIFY_EMPTY_NOTE: Int     = 15
        const val NOTIFY_EMPTY_REMINDER: Int = 16
        const val NOTIFY_EMPTY_HABIT: Int    = 17
        const val NOTIFY_EMPTY_GOAL: Int     = 18
        const val NOTIFY_EMPTY_FINISHED: Int = 19
        const val NOTIFY_EMPTY_DELETED: Int  = 20

        const val UNDERWAY: Int        = 0
        const val FINISHED: Int        = 1
        const val DELETED: Int         = 2
        const val DELETED_FOREVER: Int = 3

        const val THING_CARD_SPAN_NORMAL: Int = 0
        const val THING_CARD_SPAN_FULL: Int = 1

        const val THING_CARD_IMAGE_PLACEMENT_DEFAULT: Int = 0
        const val THING_CARD_IMAGE_PLACEMENT_TOP: Int = 1
        const val THING_CARD_IMAGE_PLACEMENT_BOTTOM: Int = 2
        const val THING_CARD_IMAGE_PLACEMENT_LEFT: Int = 3
        const val THING_CARD_IMAGE_PLACEMENT_RIGHT: Int = 4

        @JvmField
        val PRIVATE_THING_PREFIX: String = App.getApp()!!.getString(R.string.base_signal) + "L"

        @JvmField
        val CREATOR: Parcelable.Creator<Thing> = object : Parcelable.Creator<Thing> {
            override fun createFromParcel(source: Parcel): Thing {
                return Thing(source)
            }

            override fun newArray(size: Int): Array<Thing?> {
                return arrayOfNulls(size)
            }
        }

        @JvmStatic
        fun getTypeStr(type: Int, context: Context?): String? {
            return when (type) {
                NOTE -> context!!.getString(R.string.note)
                REMINDER -> context!!.getString(R.string.reminder)
                HABIT -> context!!.getString(R.string.habit)
                GOAL -> context!!.getString(R.string.goal)
                else -> context!!.getString(R.string.thing)
            }
        }

        @JvmStatic
        @DrawableRes
        fun getTypeIconWhiteLarge(type: Int): Int {
            return when (type) {
                REMINDER -> R.drawable.ic_reminder_white_large
                HABIT -> R.drawable.ic_habit_white_large
                GOAL -> R.drawable.ic_goal_white_large
                else -> R.drawable.ic_note_white_large
            }
        }

        @JvmStatic
        fun getStateStr(state: Int, context: Context?): String? {
            return when (state) {
                UNDERWAY -> context!!.getString(R.string.underway)
                FINISHED -> context!!.getString(R.string.finished)
                DELETED -> context!!.getString(R.string.deleted)
                else -> context!!.getString(R.string.underway)
            }
        }

        @JvmStatic
        fun getLimits(type: Int, state: Int): IntArray {
            val limits: IntArray
            if (state == FINISHED || type == NOTIFY_EMPTY_FINISHED) {
                limits = intArrayOf(Def.LimitForGettingThings.ALL_FINISHED)
            } else if (state == DELETED || type == NOTIFY_EMPTY_DELETED) {
                limits = intArrayOf(Def.LimitForGettingThings.ALL_DELETED)
            } else {
                when (type) {
                    WELCOME_UNDERWAY, NOTIFICATION_UNDERWAY,
                    NOTIFY_EMPTY_UNDERWAY -> {
                        return intArrayOf(Def.LimitForGettingThings.ALL_UNDERWAY)
                    }

                    WELCOME_NOTE, NOTIFICATION_NOTE,
                    NOTIFY_EMPTY_NOTE -> {
                        return intArrayOf(Def.LimitForGettingThings.NOTE_UNDERWAY)
                    }

                    WELCOME_REMINDER, NOTIFICATION_REMINDER,
                    NOTIFY_EMPTY_REMINDER -> {
                        return intArrayOf(Def.LimitForGettingThings.REMINDER_UNDERWAY)
                    }

                    WELCOME_HABIT, NOTIFICATION_HABIT,
                    NOTIFY_EMPTY_HABIT -> {
                        return intArrayOf(Def.LimitForGettingThings.HABIT_UNDERWAY)
                    }

                    WELCOME_GOAL, NOTIFICATION_GOAL,
                    NOTIFY_EMPTY_GOAL -> {
                        return intArrayOf(Def.LimitForGettingThings.GOAL_UNDERWAY)
                    }

                    else -> {
                        limits = IntArray(2)
                        limits[0] = Def.LimitForGettingThings.ALL_UNDERWAY
                        limits[1] = when (type) {
                            REMINDER -> Def.LimitForGettingThings.REMINDER_UNDERWAY
                            HABIT -> Def.LimitForGettingThings.HABIT_UNDERWAY
                            GOAL -> Def.LimitForGettingThings.GOAL_UNDERWAY
                            else -> Def.LimitForGettingThings.NOTE_UNDERWAY
                        }
                    }
                }
            }
            return limits
        }

        @JvmStatic
        fun isTypeStateMatchLimit(type: Int, state: Int, limit: Int): Boolean {
            if (state == DELETED_FOREVER) {
                return false
            }
            val limits = getLimits(type, state)
            for (lim in limits) {
                if (limit == lim) {
                    return true
                }
            }
            return false
        }

        @JvmStatic
        fun getNotifyEmptyType(limit: Int): Int {
            return when (limit) {
                Def.LimitForGettingThings.ALL_UNDERWAY -> NOTIFY_EMPTY_UNDERWAY
                Def.LimitForGettingThings.NOTE_UNDERWAY -> NOTIFY_EMPTY_NOTE
                Def.LimitForGettingThings.REMINDER_UNDERWAY -> NOTIFY_EMPTY_REMINDER
                Def.LimitForGettingThings.HABIT_UNDERWAY -> NOTIFY_EMPTY_HABIT
                Def.LimitForGettingThings.GOAL_UNDERWAY -> NOTIFY_EMPTY_GOAL
                Def.LimitForGettingThings.ALL_FINISHED -> NOTIFY_EMPTY_FINISHED
                Def.LimitForGettingThings.ALL_DELETED -> NOTIFY_EMPTY_DELETED
                else -> NOTIFY_EMPTY_UNDERWAY
            }
        }

        @JvmStatic
        fun generateNotifyEmpty(limit: Int, headerId: Long, context: Context?): Thing? {
            val thing = Thing(headerId, getNotifyEmptyType(limit),
                    DisplayUtil.getRandomColor(context), headerId)
            when (limit) {
                Def.LimitForGettingThings.ALL_UNDERWAY -> {
                    thing.title = context!!.getString(R.string.congratulations)
                    thing.content = context.getString(R.string.empty_underway)
                }
                Def.LimitForGettingThings.NOTE_UNDERWAY -> {
                    thing.content = context!!.getString(R.string.empty_note)
                }
                Def.LimitForGettingThings.REMINDER_UNDERWAY -> {
                    thing.content = context!!.getString(R.string.empty_reminder)
                }
                Def.LimitForGettingThings.HABIT_UNDERWAY -> {
                    thing.title = context!!.getString(R.string.congratulations)
                    thing.content = context.getString(R.string.empty_habit)
                }
                Def.LimitForGettingThings.GOAL_UNDERWAY -> {
                    thing.content = context!!.getString(R.string.empty_goal)
                }
                Def.LimitForGettingThings.ALL_FINISHED -> {
                    thing.content = context!!.getString(R.string.empty_finished)
                }
                Def.LimitForGettingThings.ALL_DELETED -> {
                    thing.content = context!!.getString(R.string.empty_deleted)
                }
                else -> return null
            }
            return thing
        }

        @JvmStatic
        fun getSameCheckStateThing(thing: Thing?, stateBefore: Int, stateAfter: Int): Thing? {
            var result = thing
            if (stateBefore == UNDERWAY && stateAfter == FINISHED) {
                val content = thing!!.content
                if (content!!.contains(CheckListHelper.SIGNAL + 0)) {
                    result = Thing(thing)
                    result.content = content.replace(
                            (CheckListHelper.SIGNAL + 0).toRegex(), CheckListHelper.SIGNAL + 1)
                }
            } else if (stateBefore == FINISHED && stateAfter == UNDERWAY) {
                val content = thing!!.content
                if (content!!.contains(CheckListHelper.SIGNAL + 1)) {
                    result = Thing(thing)
                    result.content = content.replace(
                            (CheckListHelper.SIGNAL + 1).toRegex(), CheckListHelper.SIGNAL + 0)
                }
            }
            return result
        }

        /**
         * Whether the proposed new state of a thing is identical to its current state.
         */
        @JvmStatic
        fun noUpdate(thing: Thing?, title: String?, content: String?, attachment: String?,
                     type: Int, background: ThingBackground?, thingCardSpanMode: Int,
                     thingCardImagePlacement: Int): Boolean {
            return thing!!.title!! == title &&
                    thing.content!! == content &&
                    thing.attachment!! == attachment &&
                    thing.type == type &&
                    thing.getBackground()!! == background &&
                    thing.thingCardSpanMode == thingCardSpanMode &&
                    thing.thingCardImagePlacement == thingCardImagePlacement
        }

        @JvmStatic
        fun isImportantType(type: Int): Boolean {
            return type == HABIT || type == GOAL
        }

        @JvmStatic
        fun isReminderType(type: Int): Boolean {
            return type == REMINDER || type == GOAL
        }

        @JvmStatic
        fun isTypeReminder(type: Int): Boolean {
            return type == REMINDER || type == WELCOME_REMINDER ||
                    type == NOTIFICATION_REMINDER || type == NOTIFY_EMPTY_REMINDER
        }

        @JvmStatic
        fun isTypeHabit(type: Int): Boolean {
            return type == HABIT || type == WELCOME_HABIT ||
                    type == NOTIFICATION_HABIT || type == NOTIFY_EMPTY_HABIT
        }

        @JvmStatic
        fun isTypeGoal(type: Int): Boolean {
            return type == GOAL || type == WELCOME_GOAL ||
                    type == NOTIFICATION_GOAL || type == NOTIFY_EMPTY_GOAL
        }

        @JvmStatic
        fun sameType(type1: Int, type2: Int): Boolean {
            if (type1 == type2) return true
            if (type1 == WELCOME_UNDERWAY) return true
            if (type1 == WELCOME_REMINDER && type2 == REMINDER) {
                return true
            }
            if (type1 == WELCOME_HABIT && type2 == HABIT) {
                return true
            }
            if (type1 == WELCOME_GOAL && type2 == GOAL) {
                return true
            }
            return false
        }

        @JvmStatic
        fun tryToCancelOngoing(context: Context?, thingId: Long) {
            val K = Def.Meta.KEY_ONGOING_THING_ID
            val curOngoingId = FrequentSettings.getLong(K)
            if (curOngoingId == thingId) {
                SystemNotificationUtil.cancelThingOngoingNotification(context, thingId)
                context!!.getSharedPreferences(Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
                        .edit { putLong(K, -1L) }
                FrequentSettings.put(K, -1L)
            }
        }
    }
}
