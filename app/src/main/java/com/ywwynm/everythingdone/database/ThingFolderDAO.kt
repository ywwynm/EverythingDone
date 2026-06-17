package com.ywwynm.everythingdone.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingFolderCardPresentation
import com.ywwynm.everythingdone.model.ThingListEntry

open class ThingFolderDAO private constructor(context: Context?) {

    private var db: SQLiteDatabase? = null

    init {
        val helper = DBHelper(context)
        db = helper.writableDatabase
    }

    open fun create(folder: ThingFolder): Long {
        val values = ContentValues(11)
        putFolder(values, folder, includeId = true)
        return db!!.insert(Def.Database.TABLE_THING_FOLDERS, null, values)
    }

    open fun update(folder: ThingFolder) {
        folder.markUpdated()
        val values = ContentValues(10)
        putFolder(values, folder, includeId = false)
        db!!.update(Def.Database.TABLE_THING_FOLDERS, values, "id=${folder.id}", null)
    }

    open fun getFolderById(id: Long): ThingFolder? {
        val cursor = db!!.query(
            Def.Database.TABLE_THING_FOLDERS,
            null,
            "id=$id",
            null,
            null,
            null,
            null
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            return ThingFolder(it)
        }
    }

    open fun getRootFolders(): List<ThingFolder> {
        return getChildFolders(null)
    }

    open fun getAllFolders(): List<ThingFolder> {
        val folders = ArrayList<ThingFolder>()
        val cursor = db!!.query(
            Def.Database.TABLE_THING_FOLDERS,
            null,
            null,
            null,
            null,
            null,
            Def.Database.COLUMN_LOCATION_THING_FOLDERS + " desc"
        )
        cursor.use {
            while (it.moveToNext()) {
                folders.add(ThingFolder(it))
            }
        }
        return folders
    }

    open fun getChildFolders(parentFolderId: Long?): List<ThingFolder> {
        val folders = ArrayList<ThingFolder>()
        val cursor = db!!.query(
            Def.Database.TABLE_THING_FOLDERS,
            null,
            parentSelection(parentFolderId),
            null,
            null,
            null,
            Def.Database.COLUMN_LOCATION_THING_FOLDERS + " desc"
        )
        cursor.use {
            while (it.moveToNext()) {
                folders.add(ThingFolder(it))
            }
        }
        return folders
    }

    open fun getFolderEntriesForProjection(
        limit: Int,
        parentFolderId: Long?,
        keyword: String? = null,
        color: Int = 0
    ): List<ThingListEntry.FolderEntry> {
        val entries = ArrayList<ThingListEntry.FolderEntry>()
        for (folder in getChildFolders(parentFolderId)) {
            val effectiveDeleted = isEffectivelyDeleted(folder)
            if (effectiveDeleted && limit != Def.LimitForGettingThings.ALL_DELETED) {
                continue
            }
            val effectivePrivate = isEffectivelyPrivate(folder)
            val count = countDescendantThingsForProjection(folder, limit, keyword, color)
            if (count <= 0) continue
            val thumbnailEntries = getThumbnailEntriesForProjection(folder, limit, keyword, color)
            entries.add(
                ThingListEntry.FolderEntry(
                    folder = folder,
                    recursiveThingCount = count,
                    directFolderCount = countDirectChildFoldersForProjection(
                        folder, limit, keyword, color
                    ),
                    thumbnailEntries = thumbnailEntries.entries,
                    thumbnailEntryCount = thumbnailEntries.totalCount,
                    effectivePrivate = effectivePrivate,
                    effectiveDeleted = effectiveDeleted
                )
            )
        }
        return entries
    }

    open fun getFolderPath(folderId: Long?): List<ThingFolder> {
        if (folderId == null) return emptyList()
        val reversed = ArrayList<ThingFolder>()
        val visited = HashSet<Long>()
        var currentId: Long? = folderId
        while (currentId != null && visited.add(currentId)) {
            val folder = getFolderById(currentId) ?: break
            reversed.add(folder)
            currentId = folder.parentFolderId
        }
        return reversed.asReversed()
    }

    open fun updateParent(folderId: Long, parentFolderId: Long?) {
        if (parentFolderId == folderId || isDescendantOf(parentFolderId, folderId)) return
        val values = ContentValues(2)
        putNullableLong(values, Def.Database.COLUMN_PARENT_FOLDER_ID_THING_FOLDERS, parentFolderId)
        values.put(Def.Database.COLUMN_UPDATE_TIME_THING_FOLDERS, System.currentTimeMillis())
        db!!.update(Def.Database.TABLE_THING_FOLDERS, values, "id=$folderId", null)
    }

    open fun updateParentAndLocation(
        folderId: Long,
        parentFolderId: Long?,
        location: Long
    ) {
        if (parentFolderId == folderId || isDescendantOf(parentFolderId, folderId)) return
        val values = ContentValues(3)
        putNullableLong(values, Def.Database.COLUMN_PARENT_FOLDER_ID_THING_FOLDERS, parentFolderId)
        values.put(Def.Database.COLUMN_LOCATION_THING_FOLDERS, location)
        values.put(Def.Database.COLUMN_UPDATE_TIME_THING_FOLDERS, System.currentTimeMillis())
        db!!.update(Def.Database.TABLE_THING_FOLDERS, values, "id=$folderId", null)
    }

    open fun updateState(folderId: Long, @Thing.State state: Int) {
        val values = ContentValues(2)
        values.put(Def.Database.COLUMN_STATE_THING_FOLDERS, state)
        values.put(Def.Database.COLUMN_UPDATE_TIME_THING_FOLDERS, System.currentTimeMillis())
        db!!.update(Def.Database.TABLE_THING_FOLDERS, values, "id=$folderId", null)
    }

    open fun updatePrivate(folderId: Long, isPrivate: Boolean) {
        val values = ContentValues(2)
        values.put(Def.Database.COLUMN_IS_PRIVATE_THING_FOLDERS, if (isPrivate) 1 else 0)
        values.put(Def.Database.COLUMN_UPDATE_TIME_THING_FOLDERS, System.currentTimeMillis())
        db!!.update(Def.Database.TABLE_THING_FOLDERS, values, "id=$folderId", null)
    }

    open fun updateCardPresentation(
        folderId: Long,
        presentation: ThingFolderCardPresentation
    ) {
        val values = ContentValues(2)
        values.put(
            Def.Database.COLUMN_CARD_PRESENTATION_THING_FOLDERS,
            presentation.toJson()
        )
        values.put(Def.Database.COLUMN_UPDATE_TIME_THING_FOLDERS, System.currentTimeMillis())
        db!!.update(Def.Database.TABLE_THING_FOLDERS, values, "id=$folderId", null)
    }

    open fun updateLocations(ids: Array<Long?>?, locations: Array<Long?>?) {
        db!!.beginTransaction()
        try {
            val values = ContentValues(1)
            for (i in 0 until ids!!.size) {
                values.put(Def.Database.COLUMN_LOCATION_THING_FOLDERS, locations!![i])
                db!!.update(Def.Database.TABLE_THING_FOLDERS, values, "id=" + ids[i], null)
                values.clear()
            }
            db!!.setTransactionSuccessful()
        } finally {
            db!!.endTransaction()
        }
    }

    open fun getDescendantFolderIds(folderId: Long): List<Long> {
        val result = ArrayList<Long>()
        val queue = ArrayDeque<Long>()
        queue.add(folderId)
        while (!queue.isEmpty()) {
            val currentId = queue.removeFirst()
            if (result.contains(currentId)) continue
            result.add(currentId)
            val children = getChildFolders(currentId)
            for (child in children) {
                queue.add(child.id)
            }
        }
        return result
    }

    open fun countDescendantThings(folderId: Long, limit: Int): Int {
        return countDescendantThings(folderId, thingSelectionForLimit(limit))
    }

    open fun countAllDescendantThings(folderId: Long): Int {
        return countDescendantThings(folderId, userThingSelection())
    }

    open fun countDescendantThingsForProjection(
        folder: ThingFolder,
        limit: Int,
        keyword: String? = null,
        color: Int = 0
    ): Int {
        val effectiveDeleted = isEffectivelyDeleted(folder)
        if (limit == Def.LimitForGettingThings.ALL_DELETED && effectiveDeleted) {
            return countDescendantThings(folder.id, userThingSelection(), keyword, color)
        }
        if (effectiveDeleted) return 0
        return countDescendantThings(folder.id, thingSelectionForLimit(limit), keyword, color)
    }

    open fun isEffectivelyDeleted(folderId: Long?): Boolean {
        if (folderId == null) return false
        var currentId: Long? = folderId
        val visited = HashSet<Long>()
        while (currentId != null && visited.add(currentId)) {
            val folder = getFolderById(currentId) ?: return false
            if (folder.isDeleted()) return true
            currentId = folder.parentFolderId
        }
        return false
    }

    private fun isEffectivelyDeleted(folder: ThingFolder): Boolean {
        if (folder.isDeleted()) return true
        return isEffectivelyDeleted(folder.parentFolderId)
    }

    open fun isEffectivelyPrivate(folderId: Long?): Boolean {
        if (folderId == null) return false
        var currentId: Long? = folderId
        val visited = HashSet<Long>()
        while (currentId != null && visited.add(currentId)) {
            val folder = getFolderById(currentId) ?: return false
            if (folder.isPrivate) return true
            currentId = folder.parentFolderId
        }
        return false
    }

    private fun isEffectivelyPrivate(folder: ThingFolder): Boolean {
        if (folder.isPrivate) return true
        return isEffectivelyPrivate(folder.parentFolderId)
    }

    open fun isDescendantOf(folderId: Long?, possibleAncestorId: Long): Boolean {
        if (folderId == null) return false
        var currentId: Long? = folderId
        val visited = HashSet<Long>()
        while (currentId != null && visited.add(currentId)) {
            if (currentId == possibleAncestorId) return true
            currentId = getFolderById(currentId)?.parentFolderId
        }
        return false
    }

    open fun deleteForever(folderId: Long) {
        val folderIds = getDescendantFolderIds(folderId)
        if (folderIds.isEmpty()) return
        val idList = folderIds.joinToString(",")
        db!!.beginTransaction()
        try {
            db!!.delete(
                Def.Database.TABLE_THINGS,
                Def.Database.COLUMN_FOLDER_ID_THINGS + " in ($idList)",
                null
            )
            db!!.delete(
                Def.Database.TABLE_THING_FOLDERS,
                Def.Database.COLUMN_ID_THING_FOLDERS + " in ($idList)",
                null
            )
            db!!.setTransactionSuccessful()
        } finally {
            db!!.endTransaction()
        }
    }

    open fun deleteRecord(folderId: Long) {
        db!!.delete(
            Def.Database.TABLE_THING_FOLDERS,
            Def.Database.COLUMN_ID_THING_FOLDERS + "=$folderId",
            null
        )
    }

    open fun dissolve(folderId: Long) {
        val folder = getFolderById(folderId) ?: return
        val parentFolderId = folder.parentFolderId
        val directThings = getDirectUserThings(folderId)
        val directFolders = getChildFolders(folderId)
        db!!.beginTransaction()
        try {
            for (thing in directThings) {
                val newLocation = getFirstChildLocation(parentFolderId, thing.location < 0)
                val values = ContentValues(2)
                putNullableLong(values, Def.Database.COLUMN_FOLDER_ID_THINGS, parentFolderId)
                values.put(Def.Database.COLUMN_LOCATION_THINGS, newLocation)
                db!!.update(
                    Def.Database.TABLE_THINGS,
                    values,
                    Def.Database.COLUMN_ID_THINGS + "=" + thing.id,
                    null
                )
            }
            for (childFolder in directFolders) {
                val newLocation = getFirstChildLocation(parentFolderId, childFolder.isSticky())
                val values = ContentValues(3)
                putNullableLong(
                    values,
                    Def.Database.COLUMN_PARENT_FOLDER_ID_THING_FOLDERS,
                    parentFolderId
                )
                values.put(Def.Database.COLUMN_LOCATION_THING_FOLDERS, newLocation)
                values.put(
                    Def.Database.COLUMN_UPDATE_TIME_THING_FOLDERS,
                    System.currentTimeMillis()
                )
                db!!.update(
                    Def.Database.TABLE_THING_FOLDERS,
                    values,
                    Def.Database.COLUMN_ID_THING_FOLDERS + "=" + childFolder.id,
                    null
                )
            }
            db!!.delete(
                Def.Database.TABLE_THING_FOLDERS,
                Def.Database.COLUMN_ID_THING_FOLDERS + "=$folderId",
                null
            )
            db!!.setTransactionSuccessful()
        } finally {
            db!!.endTransaction()
        }
    }

    open fun isStructurallyEmpty(folderId: Long): Boolean {
        return !hasRows(
            Def.Database.TABLE_THINGS,
            directUserThingSelection(folderId)
        ) && !hasRows(
            Def.Database.TABLE_THING_FOLDERS,
            parentSelection(folderId)
        )
    }

    open fun getFirstChildLocation(parentFolderId: Long?, sticky: Boolean): Long {
        return if (sticky) {
            val minLocation = minDirectChildLocation(parentFolderId, sticky = true)
            if (minLocation == null || minLocation >= 0L) -1L else minLocation - 1L
        } else {
            val maxLocation = maxDirectChildLocation(parentFolderId, sticky = false)
            if (maxLocation == null || maxLocation < 0L) 1L else maxLocation + 1L
        }
    }

    private fun getDirectUserThings(parentFolderId: Long?): List<Thing> {
        val things = ArrayList<Thing>()
        val cursor = db!!.query(
            Def.Database.TABLE_THINGS,
            null,
            directUserThingSelection(parentFolderId),
            null,
            null,
            null,
            Def.Database.COLUMN_LOCATION_THINGS + " desc"
        )
        cursor.use {
            while (it.moveToNext()) {
                things.add(Thing(it))
            }
        }
        return things
    }

    private fun getThumbnailEntriesForProjection(
        folder: ThingFolder,
        limit: Int,
        keyword: String?,
        color: Int
    ): ThumbnailEntriesProjection {
        val maxCount = folder.cardPresentation.effectiveThumbnailPreviewLimit()
        val effectiveDeleted = isEffectivelyDeleted(folder)
        val selection = if (
            limit == Def.LimitForGettingThings.ALL_DELETED && effectiveDeleted
        ) {
            userThingSelection()
        } else {
            thingSelectionForLimit(limit)
        }
        val entries = ArrayList<ThingListEntry>()
        entries.addAll(getThumbnailFolderEntriesForProjection(folder, limit, keyword, color))
        for (thing in getDirectThumbnailThings(folder.id, selection, keyword, color)) {
            entries.add(ThingListEntry.ThingEntry(thing))
        }
        val sortedEntries = entries.sortedByDescending { it.location }
        return ThumbnailEntriesProjection(
            entries = sortedEntries.take(maxCount),
            totalCount = sortedEntries.size
        )
    }

    private fun getThumbnailFolderEntriesForProjection(
        folder: ThingFolder,
        limit: Int,
        keyword: String?,
        color: Int
    ): List<ThingListEntry.FolderEntry> {
        val entries = ArrayList<ThingListEntry.FolderEntry>()
        for (childFolder in getChildFolders(folder.id)) {
            if (!shouldIncludeFolderForProjection(childFolder, limit, keyword, color)) {
                continue
            }
            entries.add(
                ThingListEntry.FolderEntry(
                    folder = childFolder,
                    recursiveThingCount = countDescendantThingsForProjection(
                        childFolder, limit, keyword, color
                    ),
                    directFolderCount = countDirectChildFoldersForProjection(
                        childFolder, limit, keyword, color
                    ),
                    effectivePrivate = isEffectivelyPrivate(childFolder),
                    effectiveDeleted = isEffectivelyDeleted(childFolder)
                )
            )
        }
        return entries
    }

    private fun countDirectChildFoldersForProjection(
        folder: ThingFolder,
        limit: Int,
        keyword: String?,
        color: Int
    ): Int {
        var count = 0
        for (childFolder in getChildFolders(folder.id)) {
            if (shouldIncludeFolderForProjection(childFolder, limit, keyword, color)) {
                count++
            }
        }
        return count
    }

    private fun shouldIncludeFolderForProjection(
        folder: ThingFolder,
        limit: Int,
        keyword: String?,
        color: Int
    ): Boolean {
        val effectiveDeleted = isEffectivelyDeleted(folder)
        if (effectiveDeleted && limit != Def.LimitForGettingThings.ALL_DELETED) {
            return false
        }
        return countDescendantThingsForProjection(folder, limit, keyword, color) > 0
    }

    private fun getDirectThumbnailThings(
        folderId: Long,
        thingSelection: String,
        keyword: String?,
        color: Int
    ): List<Thing> {
        val things = ArrayList<Thing>()
        val cursor = db!!.query(
            Def.Database.TABLE_THINGS,
            null,
            directThingSelection(folderId, thingSelection, keyword),
            null,
            null,
            null,
            Def.Database.COLUMN_LOCATION_THINGS + " desc"
        )
        cursor.use {
            while (it.moveToNext()) {
                val thing = Thing(it)
                if (isEffectivelyPrivate(thing.folderId)) continue
                if (!matchesColorFilter(thing, color)) continue
                things.add(thing)
            }
        }
        return things
    }

    private fun countDescendantThings(
        folderId: Long,
        thingSelection: String,
        keyword: String? = null,
        color: Int = 0
    ): Int {
        val folderIds = getDescendantFolderIds(folderId)
        if (folderIds.isEmpty()) return 0
        val idList = folderIds.joinToString(",")
        if (keyword != null || hasColorFilter(color)) {
            var count = 0
            val cursor = db!!.query(
                Def.Database.TABLE_THINGS,
                null,
                descendantThingSelection(idList, thingSelection, keyword),
                null,
                null,
                null,
                null
            )
            cursor.use {
                while (it.moveToNext()) {
                    val thing = Thing(it)
                    if (matchesColorFilter(thing, color)) count++
                }
            }
            return count
        }
        val cursor = db!!.rawQuery(
            "select count(*) from " + Def.Database.TABLE_THINGS +
                " where " + Def.Database.COLUMN_FOLDER_ID_THINGS + " in ($idList)" +
                " and ($thingSelection)",
            null
        )
        cursor.use {
            if (!it.moveToFirst()) return 0
            return it.getInt(0)
        }
    }

    private fun descendantThingSelection(
        idList: String,
        thingSelection: String,
        keyword: String?
    ): String {
        val selection = StringBuilder()
        selection.append(Def.Database.COLUMN_FOLDER_ID_THINGS)
            .append(" in (").append(idList).append(") and (")
            .append(thingSelection).append(")")
        if (keyword != null) {
            val kw = keyword.replace("'".toRegex(), "''")
            selection.append(" and (")
                .append(Def.Database.COLUMN_TITLE_THINGS).append(" like '%").append(kw)
                .append("%' or ").append(Def.Database.COLUMN_CONTENT_THINGS)
                .append(" like '%").append(kw).append("%')")
        }
        return selection.toString()
    }

    private fun directThingSelection(
        folderId: Long,
        thingSelection: String,
        keyword: String?
    ): String {
        val selection = StringBuilder()
        selection.append(Def.Database.COLUMN_FOLDER_ID_THINGS)
            .append("=").append(folderId).append(" and (")
            .append(thingSelection).append(")")
        if (keyword != null) {
            val kw = keyword.replace("'".toRegex(), "''")
            selection.append(" and (")
                .append(Def.Database.COLUMN_TITLE_THINGS).append(" like '%").append(kw)
                .append("%' or ").append(Def.Database.COLUMN_CONTENT_THINGS)
                .append(" like '%").append(kw).append("%')")
        }
        return selection.toString()
    }

    private fun directUserThingSelection(parentFolderId: Long?): String {
        return userThingSelection() + " and " + thingParentSelection(parentFolderId)
    }

    private fun thingParentSelection(parentFolderId: Long?): String {
        return if (parentFolderId == null) {
            Def.Database.COLUMN_FOLDER_ID_THINGS + " is null"
        } else {
            Def.Database.COLUMN_FOLDER_ID_THINGS + "=$parentFolderId"
        }
    }

    private fun maxDirectChildLocation(parentFolderId: Long?, sticky: Boolean): Long? {
        return combineLocations(
            directChildThingLocation(parentFolderId, "max", sticky),
            directChildFolderLocation(parentFolderId, "max", sticky),
            { first, second -> maxOf(first, second) }
        )
    }

    private fun minDirectChildLocation(parentFolderId: Long?, sticky: Boolean): Long? {
        return combineLocations(
            directChildThingLocation(parentFolderId, "min", sticky),
            directChildFolderLocation(parentFolderId, "min", sticky),
            { first, second -> minOf(first, second) }
        )
    }

    private fun directChildThingLocation(
        parentFolderId: Long?,
        aggregate: String,
        sticky: Boolean
    ): Long? {
        return aggregateLocation(
            Def.Database.TABLE_THINGS,
            Def.Database.COLUMN_LOCATION_THINGS,
            directUserThingSelection(parentFolderId) + locationSectionSelection(
                Def.Database.COLUMN_LOCATION_THINGS,
                sticky
            ),
            aggregate
        )
    }

    private fun directChildFolderLocation(
        parentFolderId: Long?,
        aggregate: String,
        sticky: Boolean
    ): Long? {
        return aggregateLocation(
            Def.Database.TABLE_THING_FOLDERS,
            Def.Database.COLUMN_LOCATION_THING_FOLDERS,
            parentSelection(parentFolderId) + locationSectionSelection(
                Def.Database.COLUMN_LOCATION_THING_FOLDERS,
                sticky
            ),
            aggregate
        )
    }

    private fun locationSectionSelection(locationColumn: String, sticky: Boolean): String {
        return if (sticky) {
            " and $locationColumn<0"
        } else {
            " and $locationColumn>=0"
        }
    }

    private fun aggregateLocation(
        table: String,
        locationColumn: String,
        selection: String,
        aggregate: String
    ): Long? {
        val cursor = db!!.rawQuery(
            "select $aggregate($locationColumn) from $table where $selection",
            null
        )
        cursor.use {
            if (!it.moveToFirst() || it.isNull(0)) return null
            return it.getLong(0)
        }
    }

    private fun combineLocations(
        first: Long?,
        second: Long?,
        combine: (Long, Long) -> Long
    ): Long? {
        if (first == null) return second
        if (second == null) return first
        return combine(first, second)
    }

    private fun hasRows(table: String, selection: String): Boolean {
        val cursor = db!!.rawQuery("select 1 from $table where $selection limit 1", null)
        cursor.use {
            return it.moveToFirst()
        }
    }

    private fun hasColorFilter(color: Int): Boolean {
        return color != 0 && color != -1979711488
    }

    private fun matchesColorFilter(thing: Thing, color: Int): Boolean {
        if (!hasColorFilter(color)) return true
        val bucket = com.ywwynm.everythingdone.utils.BackgroundUtil.hueBucket(color)
        return com.ywwynm.everythingdone.utils.BackgroundUtil.matchesHueBucket(
            thing.getBackground(),
            bucket
        )
    }

    private fun userThingSelection(): String {
        return Def.Database.COLUMN_TYPE_THINGS + ">=" + Thing.NOTE +
            " and " + Def.Database.COLUMN_TYPE_THINGS + "<=" + Thing.GOAL
    }

    private fun thingSelectionForLimit(limit: Int): String {
        val typeColumn = Def.Database.COLUMN_TYPE_THINGS
        val stateColumn = Def.Database.COLUMN_STATE_THINGS
        val underway = stateColumn + "=" + Thing.UNDERWAY
        return when (limit) {
            Def.LimitForGettingThings.NOTE_UNDERWAY ->
                "$typeColumn=${Thing.NOTE} and $underway"
            Def.LimitForGettingThings.REMINDER_UNDERWAY ->
                "$typeColumn=${Thing.REMINDER} and $underway"
            Def.LimitForGettingThings.HABIT_UNDERWAY ->
                "$typeColumn=${Thing.HABIT} and $underway"
            Def.LimitForGettingThings.GOAL_UNDERWAY ->
                "$typeColumn=${Thing.GOAL} and $underway"
            Def.LimitForGettingThings.ALL_FINISHED ->
                userThingSelection() + " and " + stateColumn + "=" + Thing.FINISHED
            Def.LimitForGettingThings.ALL_DELETED ->
                userThingSelection() + " and " + stateColumn + "=" + Thing.DELETED
            else ->
                userThingSelection() + " and $underway"
        }
    }

    private fun putFolder(values: ContentValues, folder: ThingFolder, includeId: Boolean) {
        if (includeId) values.put(Def.Database.COLUMN_ID_THING_FOLDERS, folder.id)
        putNullableLong(
            values,
            Def.Database.COLUMN_PARENT_FOLDER_ID_THING_FOLDERS,
            folder.parentFolderId
        )
        values.put(Def.Database.COLUMN_TITLE_THING_FOLDERS, folder.title)
        values.put(Def.Database.COLUMN_STATE_THING_FOLDERS, folder.state)
        values.put(Def.Database.COLUMN_COLOR_THING_FOLDERS, folder.getColor())
        values.put(
            Def.Database.COLUMN_BACKGROUND_THING_FOLDERS,
            folder.getBackground()!!.toJson()
        )
        values.put(Def.Database.COLUMN_LOCATION_THING_FOLDERS, folder.location)
        values.put(Def.Database.COLUMN_IS_PRIVATE_THING_FOLDERS, if (folder.isPrivate) 1 else 0)
        values.put(Def.Database.COLUMN_CREATE_TIME_THING_FOLDERS, folder.createTime)
        values.put(Def.Database.COLUMN_UPDATE_TIME_THING_FOLDERS, folder.updateTime)
        values.put(
            Def.Database.COLUMN_CARD_PRESENTATION_THING_FOLDERS,
            folder.cardPresentation.toJson()
        )
    }

    private fun parentSelection(parentFolderId: Long?): String {
        return if (parentFolderId == null) {
            Def.Database.COLUMN_PARENT_FOLDER_ID_THING_FOLDERS + " is null"
        } else {
            Def.Database.COLUMN_PARENT_FOLDER_ID_THING_FOLDERS + "=$parentFolderId"
        }
    }

    private fun putNullableLong(values: ContentValues, key: String, value: Long?) {
        if (value == null) {
            values.putNull(key)
        } else {
            values.put(key, value)
        }
    }

    companion object {
        @JvmField
        var sThingFolderDAO: ThingFolderDAO? = null

        @JvmStatic
        fun getInstance(context: Context?): ThingFolderDAO? {
            if (sThingFolderDAO == null) {
                synchronized(ThingFolderDAO::class.java) {
                    if (sThingFolderDAO == null) {
                        sThingFolderDAO = ThingFolderDAO(context!!.applicationContext)
                    }
                }
            }
            return sThingFolderDAO
        }
    }

    private data class ThumbnailEntriesProjection(
        val entries: List<ThingListEntry>,
        val totalCount: Int
    )
}
