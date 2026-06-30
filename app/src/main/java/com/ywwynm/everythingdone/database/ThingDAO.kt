package com.ywwynm.everythingdone.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.helpers.ThingCardMediaHelper
import com.ywwynm.everythingdone.helpers.ThingSearchHelper
import com.ywwynm.everythingdone.model.DetailAttachmentMediaAppearance
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingCardAppearance
import com.ywwynm.everythingdone.model.ThingWidgetInfo
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

    private var mStatus: Int = Def.ThingStatus.UNDERWAY
    private var mTypeFilterMask: Int = ThingWidgetInfo.TYPE_FILTER_ALL

    private var db: SQLiteDatabase? = null

    init {
        val helper = DBHelper(context)
        db = helper.writableDatabase
        helper.ensureHomeEmptyStateData(db!!)
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

    open fun setProjection(status: Int, typeFilterMask: Int) {
        mStatus = status
        mTypeFilterMask = ThingWidgetInfo.normalizedTypeFilterMask(typeFilterMask)
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

    open fun getThingsForDisplay(status: Int, typeFilterMask: Int): List<Thing?>? {
        return getThingsForDisplay(status, typeFilterMask, null, 0)
    }

    open fun getThingsForDisplay(
        status: Int, typeFilterMask: Int, keyword: String?, color: Int
    ): List<Thing?>? {
        val cursor: Cursor = getThingsCursorForDisplay(status, typeFilterMask, null, 0)
        val things: MutableList<Thing?> = ArrayList()
        while (cursor.moveToNext()) {
            val t = Thing(cursor)
            if (!ThingSearchHelper.matches(t, keyword, color)) continue
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
        status: Int,
        typeFilterMask: Int,
        folderId: Long?,
        keyword: String?,
        color: Int
    ): List<Thing?> {
        val things = getThingsForDisplay(status, typeFilterMask, keyword, color) ?: return emptyList()
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
        val selection = StringBuilder()
        selection.append("(type=").append(Thing.HEADER).append(" or (type>=")
            .append(Thing.NOTE).append(" and type<=").append(Thing.GOAL)
            .append(" and ")
        if (folderId == null) {
            selection.append(Def.Database.COLUMN_FOLDER_ID_THINGS).append(" is null")
        } else {
            selection.append(Def.Database.COLUMN_FOLDER_ID_THINGS).append("=").append(folderId)
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
                if (!ThingSearchHelper.matches(thing, keyword, color)) continue
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
        return thing.folderId == folderId
    }

    /**
     * @return `true` if there was a SQLiteConstraintException thrown.
     */
    open fun create(thing: Thing?, handleNotifyEmpty: Boolean, handleCurrentStatus: Boolean): Boolean {
        updateHeader(1)

        val type: Int = thing!!.type
        val state: Int = thing.state

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
            create(thing, handleNotifyEmpty, handleCurrentStatus)
            return true
        }
    }

    open fun update(@Thing.Type typeBefore: Int, updatedThing: Thing?, handleNotifyEmpty: Boolean,
                    handleCurrentStatus: Boolean) {
        if (updatedThing == null) {
            return
        }

        val typeAfter: Int = updatedThing.type
        val state: Int = updatedThing.state

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
        if (handleCurrentStatus) {
            ThingsCounts.getInstance(mContext)!!.handleUpdate(typeBefore, state, typeAfter, state, 1)
        }

    }

    open fun updateState(thing: Thing?, location: Long,
                         @Thing.State stateBefore: Int, @Thing.State stateAfter: Int,
                         handleNotifyEmpty: Boolean, handleCurrentStatus: Boolean,
                         toUndo: Boolean, shouldUpdateHeader: Boolean) {
        val id: Long = thing!!.id
        @Thing.Type val type: Int = thing.type
        // never give a chance to update header's state
        if (type == Thing.HEADER) return
        val values = ContentValues()

        if (stateBefore == Thing.DELETED_FOREVER) {
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
                if (!toUndo) {
                    val newLocation = if (thing.location < 0) {
                        location
                    } else {
                        val maxLocation: Long = getMaxThingLocation()
                        if (shouldUpdateHeader) { // this is always true if called by other classes
                            updateHeader(1)
                        }
                        maxLocation
                    }
                    values.put(Def.Database.COLUMN_LOCATION_THINGS, newLocation)
                    if (stateAfter == Thing.FINISHED) {
                        values.put(Def.Database.COLUMN_FINISH_TIME_THINGS, System.currentTimeMillis())
                    }
                } else {
                    values.put(Def.Database.COLUMN_LOCATION_THINGS, location)
                }

                values.put(Def.Database.COLUMN_CONTENT_THINGS, thing.content)
                values.put(Def.Database.COLUMN_STATE_THINGS, stateAfter)
                if (stateAfter == Thing.DELETED && stateBefore != Thing.DELETED) {
                    // Remember the pre-trash state so restore can return the Thing
                    // to underway or finished as it was, instead of always underway.
                    values.put(Def.Database.COLUMN_STATE_BEFORE_DELETE_THINGS, stateBefore)
                }
                db!!.update(Def.Database.TABLE_THINGS, values, "id=$id", null)
            } else {
                val temp: Thing? = getThingById(id)
                if (temp != null && temp.type == Thing.HEADER) return

                db!!.delete(Def.Database.TABLE_THINGS, "id=$id", null)
                // 永久删除（物理移除）记事：刷新其所属文件夹的 updateTime（借鉴目录 mtime）。这是所有永久删除
                // 入口的统一物理点——批量删所选、详情页清空删除（含 DAO 直写）、回收站永久删除都经此 db.delete；
                // 删到回收站走上面的 update 分支、folderId 不变，不经此处。
                val folderId = thing.folderId
                if (folderId != null) {
                    ThingFolderDAO.getInstance(mContext)?.touchUpdateTime(folderId)
                }
            }
        }

        if (handleCurrentStatus) {
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
                val nonStickyCount = things.count { it == null || it.location >= 0 }
                updateHeader(nonStickyCount)
                for (i in size - 1 downTo 0) {
                    val location = locations?.getOrNull(i) ?: -1L
                    updateState(
                        things[i], location, stateBefore, stateAfter, false, false,
                            false, false)
                }
            } else {
                for (i in size - 1 downTo 0) {
                    updateState(
                        things[i], locations!![i]!!, stateBefore, stateAfter,
                            false, false, true, false)
                }
            }

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

    /**
     * The stored pre-trash state of a Thing (the state it had before being moved
     * to the Deleted state). Returns [Thing.UNDERWAY] when nothing was recorded
     * (legacy deleted Things, or a Thing that was never deleted).
     */
    open fun getStateBeforeDelete(thingId: Long): Int {
        val cursor = db!!.query(
            Def.Database.TABLE_THINGS,
            arrayOf(Def.Database.COLUMN_STATE_BEFORE_DELETE_THINGS),
            "id=$thingId",
            null,
            null,
            null,
            null
        )
        cursor.use {
            if (!it.moveToFirst() || it.isNull(0)) return Thing.UNDERWAY
            val state = it.getInt(0)
            return if (state == Thing.UNDERWAY || state == Thing.FINISHED) state else Thing.UNDERWAY
        }
    }

    /** All real user Things (NOTE..GOAL) whose own state equals [state]. */
    open fun getAllUserThingsByState(@Thing.State state: Int): List<Thing> {
        val selection = Def.Database.COLUMN_STATE_THINGS + "=" + state +
            " and " + Def.Database.COLUMN_TYPE_THINGS + ">=" + Thing.NOTE +
            " and " + Def.Database.COLUMN_TYPE_THINGS + "<=" + Thing.GOAL
        val result = ArrayList<Thing>()
        val cursor = db!!.query(
            Def.Database.TABLE_THINGS, null, selection, null, null, null, null
        )
        cursor.use {
            while (it.moveToNext()) {
                result.add(Thing(it))
            }
        }
        return result
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
        val values = ContentValues(3)
        values.put(Def.Database.COLUMN_COLOR_THINGS, thing.getColor())
        values.put(Def.Database.COLUMN_BACKGROUND_THINGS, thing.getBackground()!!.toJson())
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
    open fun getThingsCursorForDisplay(
        status: Int, typeFilterMask: Int, keyword: String?, color: Int
    ): Cursor {
        var kw: String? = keyword
        val mask = ThingWidgetInfo.normalizedTypeFilterMask(typeFilterMask)
        val state = when (status) {
            Def.ThingStatus.FINISHED -> Thing.FINISHED
            Def.ThingStatus.DELETED -> Thing.DELETED
            else -> Thing.UNDERWAY
        }
        val contentCondition: String
        if (mask == ThingWidgetInfo.TYPE_FILTER_ALL) {
            contentCondition = if (state == Thing.UNDERWAY) {
                "((type>=${Thing.NOTE} and type<=${Thing.GOAL}) " +
                    "or type=${Thing.NOTIFICATION_UNDERWAY})"
            } else {
                "(type>=${Thing.NOTE} and type<=${Thing.GOAL})"
            }
        } else {
            // Specific type filter
            val typeConditions = mutableListOf<String>()
            if (mask and ThingWidgetInfo.TYPE_FILTER_NOTE != 0) {
                if (state == Thing.UNDERWAY) {
                    typeConditions.add("(type=${Thing.NOTE} or type=${Thing.NOTIFICATION_NOTE})")
                } else {
                    typeConditions.add("type=${Thing.NOTE}")
                }
            }
            if (mask and ThingWidgetInfo.TYPE_FILTER_REMINDER != 0) {
                if (state == Thing.UNDERWAY) {
                    typeConditions.add("(type=${Thing.REMINDER} or type=${Thing.NOTIFICATION_REMINDER})")
                } else {
                    typeConditions.add("type=${Thing.REMINDER}")
                }
            }
            if (mask and ThingWidgetInfo.TYPE_FILTER_HABIT != 0) {
                if (state == Thing.UNDERWAY) {
                    typeConditions.add("(type=${Thing.HABIT} or type=${Thing.NOTIFICATION_HABIT})")
                } else {
                    typeConditions.add("type=${Thing.HABIT}")
                }
            }
            if (mask and ThingWidgetInfo.TYPE_FILTER_GOAL != 0) {
                if (state == Thing.UNDERWAY) {
                    typeConditions.add("(type=${Thing.GOAL} or type=${Thing.NOTIFICATION_GOAL})")
                } else {
                    typeConditions.add("type=${Thing.GOAL}")
                }
            }
            contentCondition = typeConditions.joinToString(" or ")
        }

        val sb = StringBuilder()
        sb.append("((").append(contentCondition).append(") and state=").append(state).append(")")
        if (kw != null) {
            kw = kw.replace("'".toRegex(), "''")
            sb.append(" and (title like '%").append(kw)
                .append("%' or content like '%").append(kw).append("%')")
        }
        sb.append(" or type=").append(Thing.HEADER)
        return db!!.query(Def.Database.TABLE_THINGS,
                null, sb.toString(), null, null, null, "location desc")
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
     * the new thing created by user(like a new [Thing.NOTE]).
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
