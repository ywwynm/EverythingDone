package com.ywwynm.everythingdone.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.helpers.ThingCardMediaHelper
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingCardAppearance
import com.ywwynm.everythingdone.model.ThingsCounts
import com.ywwynm.everythingdone.utils.ThingsSorter

import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import kotlin.math.max

/**
 * Created by ywwynm on 2015/9/6.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * DAO layer between model [Thing] and database table "things". Provides easily-used APIs
 * for controller [com.ywwynm.everythingdone.managers.ThingManager].
 *
 * There are so many fuckable methods. It is my lost youth.
 */
open class ThingDAO private constructor(context: Context?) {

    private var mContext: Context? = context!!.applicationContext

    private var mLimit: Int = Def.LimitForGettingThings.ALL_UNDERWAY

    private var db: SQLiteDatabase? = null

    init {
        val helper = DBHelper(context)
        db = helper.writableDatabase
        recreateHeader()
    }

    private fun recreateHeader() {
        var cursor: Cursor = db!!.query(Def.Database.TABLE_THINGS,
                null, null, null, null, null, "id desc")
        cursor.moveToFirst()
        val maxId: Long = cursor.getLong(0)
        cursor.close()

        cursor = db!!.query(Def.Database.TABLE_THINGS,
                null, null, null, null, null, "location desc")
        cursor.moveToFirst()
        val maxLocation: Long = cursor.getLong(
                cursor.getColumnIndex(Def.Database.COLUMN_LOCATION_THINGS))
        cursor.close()

        db!!.delete(Def.Database.TABLE_THINGS, "type=" + Thing.HEADER, null)

        val idAndLocation: Long = max(maxId, maxLocation) + 1
        createHeader(idAndLocation)

//        cursor = db.query(Def.Database.TABLE_THINGS, null,
//                "type=" + Thing.HEADER, null, null, null, null);
//        if (!cursor.moveToFirst()) { // Header does not exist. Interesting situation.
//            createHeader(maxId + 1);
//            cursor.close();
//        } else {
//            cursor.close();
//            cursor = db.query(Def.Database.TABLE_THINGS,
//                    null, null, null, null, null, "id desc");
//            cursor.moveToFirst();
//            int type = cursor.getInt(cursor.getColumnIndex(Def.Database.COLUMN_TYPE_THINGS));
//            if (type != Thing.HEADER) {
//                updateHeader(cursor.getLong(0) + 1);
//            }
//            cursor.close();
//        }
    }

    private fun createHeader(idAndLocation: Long) {
        val values = ContentValues()
        values.put(Def.Database.COLUMN_ID_THINGS, idAndLocation)
        values.put(Def.Database.COLUMN_TYPE_THINGS, Thing.HEADER)
        values.put(Def.Database.COLUMN_STATE_THINGS, Thing.UNDERWAY)
        values.put(Def.Database.COLUMN_COLOR_THINGS, -14784871)
        values.put(Def.Database.COLUMN_TITLE_THINGS, "Let this be my last words")
        values.put(Def.Database.COLUMN_CONTENT_THINGS, "I trust thy love")
        values.put(Def.Database.COLUMN_ATTACHMENT_THINGS, "to QQ")
        values.put(Def.Database.COLUMN_LOCATION_THINGS, idAndLocation)
        values.put(Def.Database.COLUMN_CREATE_TIME_THINGS, System.currentTimeMillis())
        values.put(Def.Database.COLUMN_UPDATE_TIME_THINGS, System.currentTimeMillis())
        values.put(Def.Database.COLUMN_FINISH_TIME_THINGS, 0)
        values.put(
            Def.Database.COLUMN_THING_CARD_APPEARANCE_THINGS,
            ThingCardAppearance.default().toJson()
        )
        values.put(
            Def.Database.COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS,
            DetailAttachmentMediaAppearance.default().toJson()
        )
        putFolderId(values, null)

        db!!.insert(Def.Database.TABLE_THINGS, null, values)
    }

    open fun setLimit(limit: Int) {
        mLimit = limit
    }

    open fun getThingById(id: Long): Thing? {
        val cursor: Cursor = db!!.query(Def.Database.TABLE_THINGS, null,
            "id=$id", null, null, null, null)
        val moved: Boolean = cursor.moveToFirst()
        if (!moved) {
            return null
        }
        val thing = Thing(cursor)
        cursor.close()
        return thing
    }

    open fun getHeaderId(): Long {
        val cursor: Cursor = db!!.query(Def.Database.TABLE_THINGS, null,
                "type=" + Thing.HEADER, null, null, null, null)
        if (cursor.moveToFirst()) {
            val id: Long = cursor.getLong(0)
            cursor.close()
            return id
        } else {
            recreateHeader()
            return getHeaderId()
        }
    }

    open fun getThingsForDisplay(limit: Int): List<Thing?>? {
        return getThingsForDisplay(limit, null, 0)
    }

    open fun getThingsForDisplay(limit: Int, keyword: String?, color: Int): List<Thing?>? {
        // Phase 5: ColorPicker still hands us an int colour value, but with random
        // background colours possible since Phase 3/4 the old SQL "color=<int>"
        // exact match would almost never hit. Re-interpret the int as a "hue
        // bucket hint": classify it once, then post-filter the result rows on
        // their representative-colour's hue bucket.
        var filterBucket: Int = com.ywwynm.everythingdone.utils.BackgroundUtil.HUE_BUCKET_NONE
        if (color != 0 && color != -1979711488 /* legacy "all colors" sentinel */) {
            filterBucket = com.ywwynm.everythingdone.utils.BackgroundUtil.hueBucket(color)
        }

        // Pass 0 to the cursor so the SQL no longer applies the now-defunct
        // colour=<int> WHERE; we'll do the bucket filter in-memory below.
        val cursor: Cursor = getThingsCursorForDisplay(limit, keyword, 0)
        val things: MutableList<Thing?> = ArrayList()
        while (cursor.moveToNext()) {
            val t = Thing(cursor)
            // Hue-bucket filtering applies to every coloured row — i.e. anything
            // except HEADER and the NOTIFY_EMPTY_* placeholders (which have no
            // user-set colour). This includes WELCOME_*, NOTIFICATION_*, etc.
            if (filterBucket != com.ywwynm.everythingdone.utils.BackgroundUtil.HUE_BUCKET_NONE
                    && t.type != Thing.HEADER
                    && t.type <  Thing.NOTIFY_EMPTY_UNDERWAY) {
                // Gradient things expose BOTH stops to the bucket filter so
                // e.g. a red→blue thing matches the red and the blue search.
                if (!com.ywwynm.everythingdone.utils.BackgroundUtil.matchesHueBucket(
                        t.getBackground(), filterBucket)) {
                    continue
                }
            }
            things.add(t)
        }
        cursor.close()
        Collections.sort(things, object : Comparator<Thing?> {
            override fun compare(thing1: Thing?, thing2: Thing?): Int {
                if (thing1!!.type == Thing.HEADER) return -1
                if (thing2!!.type == Thing.HEADER) return 1
                return ThingsSorter.compareByLocationAndSticky(
                        thing1.location, thing2.location)
            }
        })
        return things
    }

    open fun getThingsForProjection(
        limit: Int,
        folderId: Long?,
        keyword: String?,
        color: Int
    ): List<Thing?> {
        val things = getThingsForDisplay(limit, keyword, color) ?: return emptyList()
        val filtered = ArrayList<Thing?>()
        for (thing in things) {
            if (belongsToProjectionFolder(thing, folderId)) {
                filtered.add(thing)
            }
        }
        return filtered
    }

    open fun getThingsForEffectiveDeletedFolderProjection(
        folderId: Long?,
        keyword: String?,
        color: Int
    ): List<Thing?> {
        var filterBucket: Int = com.ywwynm.everythingdone.utils.BackgroundUtil.HUE_BUCKET_NONE
        if (color != 0 && color != -1979711488 /* legacy "all colors" sentinel */) {
            filterBucket = com.ywwynm.everythingdone.utils.BackgroundUtil.hueBucket(color)
        }

        val selection = StringBuilder()
        selection.append("(type=").append(Thing.HEADER).append(" or (type>=")
            .append(Thing.NOTE).append(" and type<=").append(Thing.GOAL)
            .append(" and ")
        if (folderId == null) {
            selection.append(Def.Database.COLUMN_FOLDER_ID_THINGS).append(" is null")
        } else {
            selection.append(Def.Database.COLUMN_FOLDER_ID_THINGS).append("=").append(folderId)
        }
        if (keyword != null) {
            val kw = keyword.replace("'".toRegex(), "''")
            selection.append(" and (title like '%").append(kw)
                .append("%' or content like '%").append(kw).append("%')")
        }
        selection.append("))")

        val cursor = db!!.query(
            Def.Database.TABLE_THINGS,
            null,
            selection.toString(),
            null,
            null,
            null,
            Def.Database.COLUMN_LOCATION_THINGS + " desc"
        )
        val things: MutableList<Thing?> = ArrayList()
        cursor.use {
            while (it.moveToNext()) {
                val thing = Thing(it)
                if (filterBucket != com.ywwynm.everythingdone.utils.BackgroundUtil.HUE_BUCKET_NONE
                    && thing.type != Thing.HEADER
                    && !com.ywwynm.everythingdone.utils.BackgroundUtil.matchesHueBucket(
                        thing.getBackground(),
                        filterBucket
                    )
                ) {
                    continue
                }
                things.add(thing)
            }
        }
        Collections.sort(things, object : Comparator<Thing?> {
            override fun compare(thing1: Thing?, thing2: Thing?): Int {
                if (thing1!!.type == Thing.HEADER) return -1
                if (thing2!!.type == Thing.HEADER) return 1
                return ThingsSorter.compareByLocationAndSticky(
                    thing1.location,
                    thing2.location
                )
            }
        })
        return things
    }

    private fun belongsToProjectionFolder(thing: Thing?, folderId: Long?): Boolean {
        if (thing == null) return false
        if (thing.type == Thing.HEADER) return true
        if (thing.type >= Thing.NOTIFY_EMPTY_UNDERWAY) return folderId == null
        return thing.folderId == folderId
    }

    /**
     * @return `true` if there was a SQLiteConstraintException thrown.
     */
    open fun create(thing: Thing?, handleNotifyEmpty: Boolean, handleCurrentLimit: Boolean): Boolean {
        updateHeader(1)

        val type: Int = thing!!.type
        val state: Int = thing.state
        if (handleNotifyEmpty) {
            deleteNotifyEmpty(type, state, handleCurrentLimit)
        }

        val values = ContentValues(15)
        values.put(Def.Database.COLUMN_ID_THINGS,          thing.id)
        values.put(Def.Database.COLUMN_TYPE_THINGS,        type)
        values.put(Def.Database.COLUMN_STATE_THINGS,       state)
        values.put(Def.Database.COLUMN_COLOR_THINGS,       thing.getColor())
        values.put(Def.Database.COLUMN_TITLE_THINGS,       thing.title)
        values.put(Def.Database.COLUMN_CONTENT_THINGS,     thing.content)
        values.put(Def.Database.COLUMN_ATTACHMENT_THINGS,  thing.attachment)
        values.put(Def.Database.COLUMN_LOCATION_THINGS,    thing.location)
        values.put(Def.Database.COLUMN_CREATE_TIME_THINGS, thing.createTime)
        values.put(Def.Database.COLUMN_UPDATE_TIME_THINGS, thing.updateTime)
        values.put(Def.Database.COLUMN_FINISH_TIME_THINGS, thing.finishTime)
        values.put(Def.Database.COLUMN_BACKGROUND_THINGS,  thing.getBackground()!!.toJson())
        values.put(
            Def.Database.COLUMN_THING_CARD_APPEARANCE_THINGS,
            thing.thingCardAppearance.toJson()
        )
        values.put(
            Def.Database.COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS,
            thing.detailAttachmentMediaAppearance.toJson()
        )
        putFolderId(values, thing.folderId)

        try {
            db!!.insert(Def.Database.TABLE_THINGS, null, values)
            return false
        } catch (e: SQLiteConstraintException) {
            e.printStackTrace()
            create(thing, handleNotifyEmpty, handleCurrentLimit)
            return true
        }
    }

    open fun update(@Thing.Type typeBefore: Int, updatedThing: Thing?, handleNotifyEmpty: Boolean,
                    handleCurrentLimit: Boolean) {
        if (updatedThing == null) {
            return
        }

        val typeAfter: Int = updatedThing.type
        val state: Int = updatedThing.state
        if (handleNotifyEmpty) {
            deleteNotifyEmpty(typeAfter, state, handleCurrentLimit)
        }

        prepareAppearanceForContentUpdate(updatedThing)

        val values = ContentValues()
        values.put(Def.Database.COLUMN_TYPE_THINGS, typeAfter)
        values.put(Def.Database.COLUMN_COLOR_THINGS, updatedThing.getColor())
        values.put(Def.Database.COLUMN_BACKGROUND_THINGS, updatedThing.getBackground()!!.toJson())
        values.put(
            Def.Database.COLUMN_THING_CARD_APPEARANCE_THINGS,
            updatedThing.thingCardAppearance.toJson()
        )
        values.put(
            Def.Database.COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS,
            updatedThing.detailAttachmentMediaAppearance.toJson()
        )
        values.put(Def.Database.COLUMN_TITLE_THINGS, updatedThing.title)
        values.put(Def.Database.COLUMN_CONTENT_THINGS, updatedThing.content)
        values.put(Def.Database.COLUMN_ATTACHMENT_THINGS, updatedThing.attachment)
        values.put(Def.Database.COLUMN_UPDATE_TIME_THINGS, updatedThing.updateTime)
        putFolderId(values, updatedThing.folderId)

        db!!.update(Def.Database.TABLE_THINGS, values, "id=" + updatedThing.id, null)

        // true only this method is called separately without ThingManager#update called.
        // for example, called in receivers.
        if (handleCurrentLimit) {
            ThingsCounts.getInstance(mContext)!!.handleUpdate(typeBefore, state, typeAfter, state, 1)
        }

        if (handleNotifyEmpty) {
            createNotifyEmpty(typeBefore, state, handleCurrentLimit)
        }
    }

    open fun updateState(thing: Thing?, location: Long,
                         @Thing.State stateBefore: Int, @Thing.State stateAfter: Int,
                         handleNotifyEmpty: Boolean, handleCurrentLimit: Boolean,
                         toUndo: Boolean, shouldUpdateHeader: Boolean) {
        val id: Long = thing!!.id
        @Thing.Type val type: Int = thing.type
        // never give a chance to update header's state
        if (type == Thing.HEADER) return
        val values = ContentValues()

        if (stateBefore == Thing.DELETED_FOREVER) {
            if (handleNotifyEmpty) {
                deleteNotifyEmpty(type, stateAfter, handleCurrentLimit)
            }

            values.put(Def.Database.COLUMN_ID_THINGS, id)
            values.put(Def.Database.COLUMN_TYPE_THINGS, type)
            values.put(Def.Database.COLUMN_STATE_THINGS, stateAfter)
            values.put(Def.Database.COLUMN_COLOR_THINGS, thing.getColor())
            values.put(Def.Database.COLUMN_BACKGROUND_THINGS, thing.getBackground()!!.toJson())
            values.put(Def.Database.COLUMN_TITLE_THINGS, thing.title)
            values.put(Def.Database.COLUMN_CONTENT_THINGS, thing.content)
            values.put(Def.Database.COLUMN_ATTACHMENT_THINGS, thing.attachment)
            values.put(Def.Database.COLUMN_LOCATION_THINGS, thing.location)
            values.put(Def.Database.COLUMN_CREATE_TIME_THINGS, thing.createTime)
            values.put(Def.Database.COLUMN_UPDATE_TIME_THINGS, thing.updateTime)
            values.put(Def.Database.COLUMN_FINISH_TIME_THINGS, thing.finishTime)
            values.put(
                Def.Database.COLUMN_THING_CARD_APPEARANCE_THINGS,
                thing.thingCardAppearance.toJson()
            )
            values.put(
                Def.Database.COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS,
                thing.detailAttachmentMediaAppearance.toJson()
            )
            putFolderId(values, thing.folderId)

            db!!.insert(Def.Database.TABLE_THINGS, null, values)
        } else {
            if (stateAfter != Thing.DELETED_FOREVER) {
                if (handleNotifyEmpty) {
                    deleteNotifyEmpty(type, stateAfter, handleCurrentLimit)
                }

                // we should keep newest finished thing at the first place in Finished page.
                if (!toUndo) {
                    val maxLocation: Long = getMaxThingLocation()
                    if (shouldUpdateHeader) { // this is always true if called by other classes
                        updateHeader(1)
                    }
                    values.put(Def.Database.COLUMN_LOCATION_THINGS, maxLocation)
                    if (stateAfter == Thing.FINISHED) {
                        values.put(Def.Database.COLUMN_FINISH_TIME_THINGS, System.currentTimeMillis())
                    }
                } else {
                    values.put(Def.Database.COLUMN_LOCATION_THINGS, location)
                }

                values.put(Def.Database.COLUMN_CONTENT_THINGS, thing.content)
                values.put(Def.Database.COLUMN_STATE_THINGS, stateAfter)
                db!!.update(Def.Database.TABLE_THINGS, values, "id=$id", null)
            } else {
                val temp: Thing? = getThingById(id)
                if (temp != null && temp.type == Thing.HEADER) return

                db!!.delete(Def.Database.TABLE_THINGS, "id=$id", null)
            }

            if (handleNotifyEmpty) {
                createNotifyEmpty(type, stateBefore, handleCurrentLimit)
            }
        }

        if (handleCurrentLimit) {
            ThingsCounts.getInstance(mContext)!!.handleUpdate(type, stateBefore, type, stateAfter, 1)
        }
    }

    open fun updateStates(things: List<Thing?>?, locations: List<Long?>?,
                          @Thing.State stateBefore: Int, @Thing.State stateAfter: Int,
                          toUndo: Boolean) {
        db!!.beginTransaction()
        try {
            val size: Int = things!!.size
            if (!toUndo) {
                updateHeader(size)
                for (i in size - 1 downTo 0) {
                    updateState(
                        things[i], -1, stateBefore, stateAfter, false, false,
                            false, false)
                }
            } else {
                for (i in size - 1 downTo 0) {
                    updateState(
                        things[i], locations!![i]!!, stateBefore, stateAfter,
                            false, false, true, false)
                }
            }

            val thingsCounts: ThingsCounts = ThingsCounts.getInstance(mContext)!!
            val currentLimit: Int = mLimit
            var neCreated = 0
            var limit: Int = Def.LimitForGettingThings.ALL_UNDERWAY
            while (limit <= Def.LimitForGettingThings.ALL_DELETED) {
                if (currentLimit == limit) { limit++; continue }

                var cursor: Cursor = getThingsCursorForDisplay(limit, null, 0)
                var count = 0
                while (cursor.moveToNext()) {
                    count++
                    if (count == 3) break
                }

                if (count == 1) {
                    val ne: Thing? = Thing.generateNotifyEmpty(limit, getHeaderId(), mContext)
                    if (ne != null) {
                        create(ne, false, false)
                        thingsCounts.handleCreation(ne.type)
                        neCreated++
                    }
                } else if (count == 3) {
                    val NEtype: Int = Thing.getNotifyEmptyType(limit)
                    cursor.close()
                    cursor = db!!.query(Def.Database.TABLE_THINGS, null,
                        "type=$NEtype", null, null, null, null)
                    if (cursor.count != 0) {
                        db!!.delete(Def.Database.TABLE_THINGS, "type=$NEtype", null)
                        thingsCounts.handleUpdate(NEtype, Thing.UNDERWAY,
                                NEtype, Thing.DELETED_FOREVER, 1)
                    }
                }
                cursor.close()
                limit++
            }

            updateHeader(6 - neCreated)

            db!!.setTransactionSuccessful()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            db!!.endTransaction()
        }
    }

    open fun updateFolderId(thingId: Long, folderId: Long?) {
        val values = ContentValues(1)
        putFolderId(values, folderId)
        db!!.update(Def.Database.TABLE_THINGS, values, "id=$thingId", null)
    }

    open fun updateFolderIdAndLocation(thingId: Long, folderId: Long?, location: Long) {
        val values = ContentValues(2)
        putFolderId(values, folderId)
        values.put(Def.Database.COLUMN_LOCATION_THINGS, location)
        db!!.update(Def.Database.TABLE_THINGS, values, "id=$thingId", null)
    }

    open fun getThingFolderId(thingId: Long): Long? {
        val cursor = db!!.query(
            Def.Database.TABLE_THINGS,
            arrayOf(Def.Database.COLUMN_FOLDER_ID_THINGS),
            "id=$thingId",
            null,
            null,
            null,
            null
        )
        cursor.use {
            if (!it.moveToFirst() || it.isNull(0)) return null
            return it.getLong(0)
        }
    }

    open fun updateLocations(ids: Array<Long?>?, locations: Array<Long?>?) {
        db!!.beginTransaction()
        try {
            val values = ContentValues()
            for (i in 0 until ids!!.size) {
                values.put(Def.Database.COLUMN_LOCATION_THINGS, locations!![i])
                db!!.update(Def.Database.TABLE_THINGS, values, "id=" + ids[i], null)
                values.clear()
            }
            db!!.setTransactionSuccessful()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            db!!.endTransaction()
        }
    }

    open fun updateThingCardAppearance(thing: Thing?) {
        if (thing == null) return
        val appearance = thing.thingCardAppearance.withAppearanceUpdateTime(System.currentTimeMillis())
        thing.thingCardAppearance = appearance
        val values = ContentValues(1)
        values.put(Def.Database.COLUMN_THING_CARD_APPEARANCE_THINGS, appearance.toJson())
        db!!.update(Def.Database.TABLE_THINGS, values, "id=" + thing.id, null)
    }

    private fun prepareAppearanceForContentUpdate(updatedThing: Thing) {
        val oldThing = getThingById(updatedThing.id) ?: return
        if (oldThing.attachment != updatedThing.attachment) {
            val availableKeys = ThingCardMediaHelper.getMediaSourceKeysFromAttachment(
                updatedThing.attachment
            )
            updatedThing.thingCardAppearance = updatedThing.thingCardAppearance
                .retainSources(availableKeys)
            updatedThing.detailAttachmentMediaAppearance =
                updatedThing.detailAttachmentMediaAppearance.retainSources(availableKeys)
        }
        if (!oldThing.thingCardAppearance.hasSamePresentationAs(updatedThing.thingCardAppearance)) {
            updatedThing.thingCardAppearance = updatedThing.thingCardAppearance
                .withAppearanceUpdateTime(System.currentTimeMillis())
        }
    }

    private fun putFolderId(values: ContentValues, folderId: Long?) {
        if (folderId == null) {
            values.putNull(Def.Database.COLUMN_FOLDER_ID_THINGS)
        } else {
            values.put(Def.Database.COLUMN_FOLDER_ID_THINGS, folderId)
        }
    }

    open fun getMinThingLocation(): Long {
        val cursor: Cursor = db!!.query(Def.Database.TABLE_THINGS,
                null, null, null, null, null, Def.Database.COLUMN_LOCATION_THINGS)
        cursor.moveToFirst()
        val minLocation: Long = cursor.getLong(
                cursor.getColumnIndex(Def.Database.COLUMN_LOCATION_THINGS))
        cursor.close()
        return minLocation
    }

    open fun getMaxThingLocation(): Long {
        val cursor: Cursor = db!!.query(Def.Database.TABLE_THINGS,
                null, null, null, null, null, Def.Database.COLUMN_LOCATION_THINGS + " desc")
        cursor.moveToFirst()
        val maxLocation: Long = cursor.getLong(
                cursor.getColumnIndex(Def.Database.COLUMN_LOCATION_THINGS))
        cursor.close()
        return maxLocation
    }

    /**
     * A fucking method to get cursor of things for display.
     *
     * Why do I say it's fucking? Because it's very ugly and there is no human intelligence
     * shinning in this method, only stupid switch-cases and exhaustions.
     * Well, dear @author ywwynm, you will feel ashamed next time you look upon this block of
     * code after some decades.
     */
    // Fuck this method! I'm so damn fuck stupid!
    open fun getThingsCursorForDisplay(limit: Int, keyword: String?, color: Int): Cursor {
        var kw: String? = keyword
        val limitSb: StringBuilder = StringBuilder()
        when (limit) {
            Def.LimitForGettingThings.ALL_UNDERWAY ->
                limitSb.append("((((type>=").append(Thing.NOTE)
                        .append(" and type<=").append(Thing.WELCOME_UNDERWAY)
                        .append(") or type=").append(Thing.NOTIFICATION_UNDERWAY)
                        .append(") and state=").append(Thing.UNDERWAY)
                        .append(") or type=").append(Thing.NOTIFY_EMPTY_UNDERWAY)
                        .append(")")
            Def.LimitForGettingThings.NOTE_UNDERWAY ->
                limitSb.append("(((type=").append(Thing.NOTE)
                        .append(" or type=").append(Thing.WELCOME_NOTE)
                        .append(" or type=").append(Thing.NOTIFICATION_NOTE)
                        .append(") and state=").append(Thing.UNDERWAY)
                        .append(") or type=").append(Thing.NOTIFY_EMPTY_NOTE)
                        .append(")")
            Def.LimitForGettingThings.REMINDER_UNDERWAY ->
                limitSb.append("(((type=").append(Thing.REMINDER)
                        .append(" or type=").append(Thing.WELCOME_REMINDER)
                        .append(" or type=").append(Thing.NOTIFICATION_REMINDER)
                        .append(") and state=").append(Thing.UNDERWAY)
                        .append(") or type=").append(Thing.NOTIFY_EMPTY_REMINDER)
                        .append(")")
            Def.LimitForGettingThings.HABIT_UNDERWAY ->
                limitSb.append("(((type=").append(Thing.HABIT)
                        .append(" or type=").append(Thing.WELCOME_HABIT)
                        .append(" or type=").append(Thing.NOTIFICATION_HABIT)
                        .append(") and state=").append(Thing.UNDERWAY)
                        .append(") or type=").append(Thing.NOTIFY_EMPTY_HABIT)
                        .append(")")
            Def.LimitForGettingThings.GOAL_UNDERWAY ->
                limitSb.append("(((type=").append(Thing.GOAL)
                        .append(" or type=").append(Thing.WELCOME_GOAL)
                        .append(" or type=").append(Thing.NOTIFICATION_GOAL)
                        .append(") and state=").append(Thing.UNDERWAY)
                        .append(") or type=").append(Thing.NOTIFY_EMPTY_GOAL)
                        .append(")")
            Def.LimitForGettingThings.ALL_FINISHED ->
                limitSb.append("((type>=").append(Thing.NOTE)
                        .append(" and type<=").append(Thing.NOTIFICATION_GOAL)
                        .append(" and state=").append(Thing.FINISHED)
                        .append(") or type=").append(Thing.NOTIFY_EMPTY_FINISHED)
                        .append(")")
            Def.LimitForGettingThings.ALL_DELETED ->
                limitSb.append("((type>=").append(Thing.NOTE)
                        .append(" and type<=").append(Thing.NOTIFICATION_GOAL)
                        .append(" and state=").append(Thing.DELETED)
                        .append(") or type=").append(Thing.NOTIFY_EMPTY_DELETED)
                        .append(")")
            else -> {}
        }
        // Phase 5: SQL "color=<int>" exact match is gone — random RGB colours
        // since Phase 3/4 wouldn't have hit it anyway. Hue-bucket filtering is
        // applied post-cursor in getThingsForDisplay above. The parameter is kept
        // for source compat with the (Cursor-returning) call sites that pass 0.
        if (kw != null) {
            kw = kw.replace("'".toRegex(), "''")
            limitSb.append(" and (title like '%").append(kw)
                    .append("%' or content like '%").append(kw).append("%')")
        }
        limitSb.append(" or type=").append(Thing.HEADER)
        return db!!.query(Def.Database.TABLE_THINGS,
                null, limitSb.toString(), null, null, null, "location desc")
    }

    open fun getAllThingsCursor(): Cursor? {
        return db!!.query(Def.Database.TABLE_THINGS, null, null, null, null, null, null)
    }

    open fun getThingsCursor(selection: String?): Cursor? {
        return db!!.query(Def.Database.TABLE_THINGS, null, selection, null, null, null, null)
    }

    /**
     * Every time user creates a new thing, id and location of header should plus 1.
     * By doing this, EverythingDone can use old id/location of header as that of
     * the new thing created by user(like a new [Thing.NOTE])
     * or app itself(like a new [Thing.NOTIFY_EMPTY_NOTE]).
     */
    open fun updateHeader(addSize: Int) {
        val SQL: String = "update " + Def.Database.TABLE_THINGS +
                " set id=id+" + addSize + ",location=location+" + addSize +
                " where type=" + Thing.HEADER
        try {
            db!!.execSQL(SQL)
        } catch (e: SQLiteConstraintException) {
            e.printStackTrace()
            updateHeader(addSize)
        }
    }

    private fun deleteNotifyEmpty(@Thing.Type type: Int, @Thing.State state: Int, handleCurrentLimit: Boolean) {
        val limits: IntArray = Thing.getLimits(type, state)
        val currentLimit: Int = mLimit
        val thingsCounts: ThingsCounts = ThingsCounts.getInstance(mContext)!!
        if (handleCurrentLimit) {
            for (limit in limits) {
                val NEtype: Int = Thing.getNotifyEmptyType(limit)
                val cursor: Cursor = db!!.query(Def.Database.TABLE_THINGS, null,
                    "type=$type", null, null, null, null)
                if (cursor.count != 0) {
                    db!!.delete(Def.Database.TABLE_THINGS, "type=$NEtype", null)
                    thingsCounts.handleUpdate(NEtype, Thing.UNDERWAY, NEtype, Thing.DELETED_FOREVER, 1)
                }
                cursor.close()
            }
        } else {
            for (limit in limits) {
                if (currentLimit != limit) {
                    val NEtype: Int = Thing.getNotifyEmptyType(limit)
                    val cursor: Cursor = db!!.query(Def.Database.TABLE_THINGS, null,
                        "type=$NEtype", null, null, null, null)
                    if (cursor.count != 0) {
                        db!!.delete(Def.Database.TABLE_THINGS, "type=$NEtype", null)
                        thingsCounts.handleUpdate(NEtype, Thing.UNDERWAY, NEtype, Thing.DELETED_FOREVER, 1)
                    }
                    cursor.close()
                }
            }
        }
    }

    private fun createNotifyEmpty(@Thing.Type type: Int, @Thing.State state: Int, handleCurrentLimit: Boolean) {
        val limits: IntArray = Thing.getLimits(type, state)
        val currentLimit: Int = mLimit
        val thingsCounts: ThingsCounts = ThingsCounts.getInstance(mContext)!!
        var cursor: Cursor
        var ne: Thing?

        if (handleCurrentLimit) {
            for (limit in limits) {
                cursor = getThingsCursorForDisplay(limit, null, 0)
                if (cursor.count == 1) {
                    ne = Thing.generateNotifyEmpty(limit, getHeaderId(), mContext)
                    if (ne != null) {
                        create(ne, false, false)
                        thingsCounts.handleCreation(ne.type)
                    }
                }
                cursor.close()
            }
        } else {
            for (limit in limits) {
                if (currentLimit != limit) {
                    cursor = getThingsCursorForDisplay(limit, null, 0)
                    if (cursor.count == 1) {
                        ne = Thing.generateNotifyEmpty(limit, getHeaderId(), mContext)
                        if (ne != null) {
                            create(ne, false, false)
                            thingsCounts.handleCreation(ne.type)
                        }
                    }
                    cursor.close()
                }
            }
        }

    }

    companion object {
        const val TAG: String = "ThingDAO"

        @JvmField
        var sThingDAO: ThingDAO? = null

        // Singleton class
        @JvmStatic
        fun getInstance(context: Context?): ThingDAO? {
            if (sThingDAO == null) {
                synchronized(ThingDAO::class.java) {
                    if (sThingDAO == null) {
                        sThingDAO = ThingDAO(context!!.applicationContext)
                    }
                }
            }
            return sThingDAO
        }
    }
}
