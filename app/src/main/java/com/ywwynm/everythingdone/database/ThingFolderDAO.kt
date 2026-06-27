package com.ywwynm.everythingdone.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.helpers.ThingSearchHelper
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingFolderCardPresentation
import com.ywwynm.everythingdone.model.ThingListEntry
import com.ywwynm.everythingdone.model.ThingWidgetInfo
import com.ywwynm.everythingdone.utils.ThingsSorter

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
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                folders.add(ThingFolder(it))
            }
        }
        folders.sortWith(folderLocationComparator())
        return folders
    }

    /** Whether the thing_folders table holds any row at all, including trashed folders. */
    open fun hasAnyFolder(): Boolean {
        val cursor = db!!.rawQuery(
            "SELECT COUNT(*) FROM ${Def.Database.TABLE_THING_FOLDERS}",
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getLong(0) > 0
            }
        }
        return false
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
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                folders.add(ThingFolder(it))
            }
        }
        folders.sortWith(folderLocationComparator())
        return folders
    }

    open fun getFolderEntriesForProjection(
        status: Int,
        parentFolderId: Long?,
        keyword: String? = null,
        color: Int = 0
    ): List<ThingListEntry.FolderEntry> {
        val entries = ArrayList<ThingListEntry.FolderEntry>()
        for (folder in getChildFolders(parentFolderId)) {
            val effectiveDeleted = isEffectivelyDeleted(folder)
            if (effectiveDeleted && status != Def.ThingStatus.DELETED) {
                continue
            }
            val effectivePrivate = isEffectivelyPrivate(folder)
            val count = countDescendantThingsForProjection(folder, status, keyword, color)
            val folderMatchesEntryFilter = folderMatchesEntryFilter(folder, keyword, color)
            if (count <= 0 && hasActiveEntryFilter(keyword, color) && !folderMatchesEntryFilter) {
                continue
            }
            val thumbnailEntries =
                if (folder.effectiveCardPresentation().mode ==
                    ThingFolderCardPresentation.MODE_THUMBNAILS
                ) {
                    getThumbnailEntriesForProjection(folder, status, keyword, color)
                } else {
                    ThumbnailEntriesProjection(emptyList(), 0)
                }
            entries.add(
                ThingListEntry.FolderEntry(
                    folder = folder,
                    recursiveThingCount = count,
                    directFolderCount = countDirectChildFoldersForProjection(
                        folder, status, keyword, color
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

    open fun getFolderEntriesForTypeFilterProjection(
        status: Int,
        typeFilterMask: Int,
        parentFolderId: Long?,
        keyword: String? = null,
        color: Int = 0
    ): List<ThingListEntry.FolderEntry> {
        val entries = ArrayList<ThingListEntry.FolderEntry>()
        for (folder in getChildFolders(parentFolderId)) {
            val effectiveDeleted = isEffectivelyDeleted(folder)
            if (effectiveDeleted && status != Def.ThingStatus.DELETED) continue
            val effectivePrivate = isEffectivelyPrivate(folder)
            val count = countDescendantThingsForTypeFilterProjection(
                folder,
                status,
                typeFilterMask,
                keyword,
                color
            )
            val folderMatchesEntryFilter = folderMatchesEntryFilter(folder, keyword, color)
            if (count <= 0 && !folderMatchesEntryFilter) continue
            val thumbnailEntries =
                if (folder.effectiveCardPresentation().mode ==
                    ThingFolderCardPresentation.MODE_THUMBNAILS
                ) {
                    getThumbnailEntriesForTypeFilterProjection(
                        folder,
                        status,
                        typeFilterMask,
                        keyword,
                        color
                    )
                } else {
                    ThumbnailEntriesProjection(emptyList(), 0)
                }
            entries.add(
                ThingListEntry.FolderEntry(
                    folder = folder,
                    recursiveThingCount = count,
                    directFolderCount = countDirectChildFoldersForTypeFilterProjection(
                        folder,
                        status,
                        typeFilterMask,
                        keyword,
                        color
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

    open fun getFolderEntriesForWidgetProjection(
        parentFolderId: Long?,
        typeFilterMask: Int,
        status: Int = Def.ThingStatus.UNDERWAY
    ): List<ThingListEntry.FolderEntry> {
        val entries = ArrayList<ThingListEntry.FolderEntry>()
        for (folder in getChildFolders(parentFolderId)) {
            val effectiveDeleted = isEffectivelyDeleted(folder)
            if (effectiveDeleted) continue
            val count = countDescendantThings(
                folder.id,
                thingSelectionForStatusAndTypeFilter(status, typeFilterMask)
            )
            if (count <= 0) continue
            entries.add(
                ThingListEntry.FolderEntry(
                    folder = folder,
                    recursiveThingCount = count,
                    directFolderCount = countDirectChildFoldersForWidgetProjection(
                        folder,
                        typeFilterMask
                    ),
                    effectivePrivate = isEffectivelyPrivate(folder),
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

    /**
     * Returns every Thing in the subtree rooted at [folderId] (the folder itself
     * and all descendant folders) whose own state and type match the given
     * status + type filter. Used by folder content state operations such as
     * "完成当前筛选下的内容" / "恢复当前筛选下的内容为正在进行".
     */
    open fun getDescendantThingsForProjection(
        folderId: Long,
        status: Int,
        typeFilterMask: Int
    ): List<Thing> {
        val folderIds = getDescendantFolderIds(folderId)
        if (folderIds.isEmpty()) return emptyList()
        val idList = folderIds.joinToString(",")
        val selection = Def.Database.COLUMN_FOLDER_ID_THINGS + " in ($idList) and (" +
            thingSelectionForStatusAndTypeFilter(status, typeFilterMask) + ")"
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

    /**
     * Every user Thing in the subtree rooted at [folderId], of any state
     * (underway, finished or in the recycle bin). Used to decide whether a folder
     * operation reaches content outside the current status/type filter.
     */
    open fun getAllDescendantThings(folderId: Long): List<Thing> {
        val folderIds = getDescendantFolderIds(folderId)
        if (folderIds.isEmpty()) return emptyList()
        val idList = folderIds.joinToString(",")
        val selection = Def.Database.COLUMN_FOLDER_ID_THINGS + " in ($idList) and (" +
            userThingSelection() + ")"
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

    open fun countDescendantThings(folderId: Long, status: Int): Int {
        return countDescendantThings(folderId, thingSelectionForStatusAndTypeFilter(status, ThingWidgetInfo.TYPE_FILTER_ALL))
    }

    open fun countAllDescendantThings(folderId: Long): Int {
        return countDescendantThings(folderId, userThingSelection())
    }

    open fun countDescendantThingsForProjection(
        folder: ThingFolder,
        status: Int,
        keyword: String? = null,
        color: Int = 0
    ): Int {
        val effectiveDeleted = isEffectivelyDeleted(folder)
        if (status == Def.ThingStatus.DELETED && effectiveDeleted) {
            return countDescendantThings(folder.id, userThingSelection(), keyword, color)
        }
        if (effectiveDeleted) return 0
        return countDescendantThings(folder.id, thingSelectionForStatusAndTypeFilter(status, ThingWidgetInfo.TYPE_FILTER_ALL), keyword, color)
    }

    open fun countDescendantThingsForTypeFilterProjection(
        folder: ThingFolder,
        status: Int,
        typeFilterMask: Int,
        keyword: String? = null,
        color: Int = 0
    ): Int {
        val effectiveDeleted = isEffectivelyDeleted(folder)
        if (status == Def.ThingStatus.DELETED) {
            return if (effectiveDeleted) {
                // Trashed folder: every descendant Thing matching the type is in
                // the recycle bin, whatever its own state.
                countDescendantThings(
                    folder.id,
                    thingSelectionForTypeFilter(typeFilterMask),
                    keyword,
                    color
                )
            } else {
                // Projection Folder (not itself trashed): count descendants that
                // are effectively deleted — own state DELETED, OR inside a deleted
                // subfolder. The latter is why deleting a subfolder must still make
                // its ancestors appear in the recycle bin.
                countDescendantThings(
                    folder.id,
                    trashedDescendantThingSelection(folder.id, typeFilterMask),
                    keyword,
                    color
                )
            }
        }
        if (effectiveDeleted) return 0
        return countDescendantThings(
            folder.id,
            thingSelectionForStatusAndTypeFilter(status, typeFilterMask),
            keyword,
            color
        )
    }

    /**
     * SQL selection for Things in [folderId]'s subtree that are effectively in the
     * recycle bin: own state DELETED, or located inside a descendant folder that is
     * itself (effectively) deleted.
     */
    private fun trashedDescendantThingSelection(folderId: Long, typeFilterMask: Int): String {
        val typeSelection = thingSelectionForTypeFilter(typeFilterMask)
        val deletedFolderIds = getDescendantFolderIds(folderId).filter { isEffectivelyDeleted(it) }
        return if (deletedFolderIds.isEmpty()) {
            "($typeSelection) and " + Def.Database.COLUMN_STATE_THINGS + "=" + Thing.DELETED
        } else {
            "($typeSelection) and (" + Def.Database.COLUMN_STATE_THINGS + "=" + Thing.DELETED +
                " or " + Def.Database.COLUMN_FOLDER_ID_THINGS +
                " in (" + deletedFolderIds.joinToString(",") + "))"
        }
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
        status: Int,
        keyword: String?,
        color: Int
    ): ThumbnailEntriesProjection {
        val maxCount = folder.effectiveCardPresentation().effectiveThumbnailPreviewLimit()
        val effectiveDeleted = isEffectivelyDeleted(folder)
        val selection = if (
            status == Def.ThingStatus.DELETED && effectiveDeleted
        ) {
            userThingSelection()
        } else {
            thingSelectionForStatusAndTypeFilter(status, ThingWidgetInfo.TYPE_FILTER_ALL)
        }
        val entries = ArrayList<ThingListEntry>()
        entries.addAll(getThumbnailFolderEntriesForProjection(folder, status, keyword, color))
        for (thing in getDirectThumbnailThings(folder.id, selection, keyword, color)) {
            entries.add(ThingListEntry.ThingEntry(thing))
        }
        val sortedEntries = entries.sortedWith { entry1, entry2 ->
            val result = ThingsSorter.compareByLocationAndSticky(
                entry1.location,
                entry2.location
            )
            if (result != 0) result else entry1.stableId.compareTo(entry2.stableId)
        }
        return ThumbnailEntriesProjection(
            entries = sortedEntries.take(maxCount),
            totalCount = sortedEntries.size
        )
    }

    open fun getThumbnailEntriesForPreview(
        folder: ThingFolder,
        status: Int,
        keyword: String? = null,
        color: Int = 0
    ): List<ThingListEntry> {
        return getThumbnailEntriesForProjection(folder, status, keyword, color).entries
    }

    open fun getThumbnailEntriesForTypeFilterPreview(
        folder: ThingFolder,
        status: Int,
        typeFilterMask: Int,
        keyword: String? = null,
        color: Int = 0
    ): List<ThingListEntry> {
        return getThumbnailEntriesForTypeFilterProjection(
            folder,
            status,
            typeFilterMask,
            keyword,
            color
        ).entries
    }

    private fun getThumbnailEntriesForTypeFilterProjection(
        folder: ThingFolder,
        status: Int,
        typeFilterMask: Int,
        keyword: String?,
        color: Int
    ): ThumbnailEntriesProjection {
        val maxCount = folder.effectiveCardPresentation().effectiveThumbnailPreviewLimit()
        val effectiveDeleted = isEffectivelyDeleted(folder)
        if (effectiveDeleted && status != Def.ThingStatus.DELETED) {
            return ThumbnailEntriesProjection(emptyList(), 0)
        }
        val selection = if (status == Def.ThingStatus.DELETED && effectiveDeleted) {
            thingSelectionForTypeFilter(typeFilterMask)
        } else {
            thingSelectionForStatusAndTypeFilter(status, typeFilterMask)
        }
        val entries = ArrayList<ThingListEntry>()
        entries.addAll(
            getThumbnailFolderEntriesForTypeFilterProjection(
                folder,
                status,
                typeFilterMask,
                keyword,
                color
            )
        )
        for (thing in getDirectThumbnailThings(folder.id, selection, keyword, color)) {
            entries.add(ThingListEntry.ThingEntry(thing))
        }
        val sortedEntries = entries.sortedWith { entry1, entry2 ->
            val result = ThingsSorter.compareByLocationAndSticky(
                entry1.location,
                entry2.location
            )
            if (result != 0) result else entry1.stableId.compareTo(entry2.stableId)
        }
        return ThumbnailEntriesProjection(
            entries = sortedEntries.take(maxCount),
            totalCount = sortedEntries.size
        )
    }

    private fun getThumbnailFolderEntriesForProjection(
        folder: ThingFolder,
        status: Int,
        keyword: String?,
        color: Int
    ): List<ThingListEntry.FolderEntry> {
        val entries = ArrayList<ThingListEntry.FolderEntry>()
        for (childFolder in getChildFolders(folder.id)) {
            if (!shouldIncludeFolderForProjection(childFolder, status, keyword, color)) {
                continue
            }
            entries.add(
                ThingListEntry.FolderEntry(
                    folder = childFolder,
                    recursiveThingCount = countDescendantThingsForProjection(
                        childFolder, status, keyword, color
                    ),
                    directFolderCount = countDirectChildFoldersForProjection(
                        childFolder, status, keyword, color
                    ),
                    effectivePrivate = isEffectivelyPrivate(childFolder),
                    effectiveDeleted = isEffectivelyDeleted(childFolder)
                )
            )
        }
        return entries
    }

    private fun getThumbnailFolderEntriesForTypeFilterProjection(
        folder: ThingFolder,
        status: Int,
        typeFilterMask: Int,
        keyword: String?,
        color: Int
    ): List<ThingListEntry.FolderEntry> {
        val entries = ArrayList<ThingListEntry.FolderEntry>()
        for (childFolder in getChildFolders(folder.id)) {
            if (!shouldIncludeFolderForTypeFilterProjection(
                    childFolder,
                    status,
                    typeFilterMask,
                    keyword,
                    color
                )
            ) {
                continue
            }
            entries.add(
                ThingListEntry.FolderEntry(
                    folder = childFolder,
                    recursiveThingCount = countDescendantThingsForTypeFilterProjection(
                        childFolder,
                        status,
                        typeFilterMask,
                        keyword,
                        color
                    ),
                    directFolderCount = countDirectChildFoldersForTypeFilterProjection(
                        childFolder,
                        status,
                        typeFilterMask,
                        keyword,
                        color
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
        status: Int,
        keyword: String?,
        color: Int
    ): Int {
        var count = 0
        for (childFolder in getChildFolders(folder.id)) {
            if (shouldIncludeFolderForProjection(childFolder, status, keyword, color)) {
                count++
            }
        }
        return count
    }

    private fun countDirectChildFoldersForWidgetProjection(
        folder: ThingFolder,
        typeFilterMask: Int
    ): Int {
        var count = 0
        for (childFolder in getChildFolders(folder.id)) {
            if (shouldIncludeFolderForWidgetProjection(childFolder, typeFilterMask)) {
                count++
            }
        }
        return count
    }

    private fun countDirectChildFoldersForTypeFilterProjection(
        folder: ThingFolder,
        status: Int,
        typeFilterMask: Int,
        keyword: String?,
        color: Int
    ): Int {
        var count = 0
        for (childFolder in getChildFolders(folder.id)) {
            if (shouldIncludeFolderForTypeFilterProjection(
                    childFolder,
                    status,
                    typeFilterMask,
                    keyword,
                    color
                )
            ) {
                count++
            }
        }
        return count
    }

    private fun shouldIncludeFolderForProjection(
        folder: ThingFolder,
        status: Int,
        keyword: String?,
        color: Int
    ): Boolean {
        val effectiveDeleted = isEffectivelyDeleted(folder)
        if (effectiveDeleted && status != Def.ThingStatus.DELETED) {
            return false
        }
        if (!hasActiveEntryFilter(keyword, color)) return true
        return folderMatchesEntryFilter(folder, keyword, color) ||
            countDescendantThingsForProjection(folder, status, keyword, color) > 0
    }

    private fun shouldIncludeFolderForWidgetProjection(
        folder: ThingFolder,
        typeFilterMask: Int
    ): Boolean {
        if (isEffectivelyDeleted(folder)) return false
        return countDescendantThings(
            folder.id,
            thingSelectionForStatusAndTypeFilter(Def.ThingStatus.UNDERWAY, typeFilterMask)
        ) > 0
    }

    private fun shouldIncludeFolderForTypeFilterProjection(
        folder: ThingFolder,
        status: Int,
        typeFilterMask: Int,
        keyword: String?,
        color: Int
    ): Boolean {
        val effectiveDeleted = isEffectivelyDeleted(folder)
        if (effectiveDeleted && status != Def.ThingStatus.DELETED) return false
        if (folderMatchesEntryFilter(folder, keyword, color)) return true
        return countDescendantThingsForTypeFilterProjection(
            folder,
            status,
            typeFilterMask,
            keyword,
            color
        ) > 0
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
            directThingSelection(folderId, thingSelection),
            null,
            null,
            null,
            Def.Database.COLUMN_LOCATION_THINGS + " desc"
        )
        cursor.use {
            while (it.moveToNext()) {
                val thing = Thing(it)
                if (isEffectivelyPrivate(thing.folderId)) continue
                if (!ThingSearchHelper.matches(thing, keyword, color)) continue
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
        if (!keyword.isNullOrEmpty() || hasColorFilter(color)) {
            var count = 0
            val cursor = db!!.query(
                Def.Database.TABLE_THINGS,
                null,
                descendantThingSelection(idList, thingSelection),
                null,
                null,
                null,
                null
            )
            cursor.use {
                while (it.moveToNext()) {
                    val thing = Thing(it)
                    if (ThingSearchHelper.matches(thing, keyword, color)) count++
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
        thingSelection: String
    ): String {
        val selection = StringBuilder()
        selection.append(Def.Database.COLUMN_FOLDER_ID_THINGS)
            .append(" in (").append(idList).append(") and (")
            .append(thingSelection).append(")")
        return selection.toString()
    }

    private fun directThingSelection(
        folderId: Long,
        thingSelection: String
    ): String {
        val selection = StringBuilder()
        selection.append(Def.Database.COLUMN_FOLDER_ID_THINGS)
            .append("=").append(folderId).append(" and (")
            .append(thingSelection).append(")")
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
        return ThingSearchHelper.hasColorFilter(color)
    }

    private fun hasActiveEntryFilter(keyword: String?, color: Int): Boolean {
        return !keyword.isNullOrEmpty() || hasColorFilter(color)
    }

    private fun folderMatchesEntryFilter(folder: ThingFolder, keyword: String?, color: Int): Boolean {
        if (!hasActiveEntryFilter(keyword, color)) return false
        return matchesFolderKeywordFilter(folder, keyword) && matchesFolderColorFilter(folder, color)
    }

    private fun matchesFolderKeywordFilter(folder: ThingFolder, keyword: String?): Boolean {
        if (keyword.isNullOrEmpty()) return true
        return folder.title.contains(keyword, ignoreCase = true)
    }

    private fun matchesFolderColorFilter(folder: ThingFolder, color: Int): Boolean {
        if (!hasColorFilter(color)) return true
        val bucket = com.ywwynm.everythingdone.utils.BackgroundUtil.hueBucket(color)
        return com.ywwynm.everythingdone.utils.BackgroundUtil.matchesHueBucket(
            folder.getBackground(),
            bucket
        )
    }

    private fun userThingSelection(): String {
        return Def.Database.COLUMN_TYPE_THINGS + ">=" + Thing.NOTE +
            " and " + Def.Database.COLUMN_TYPE_THINGS + "<=" + Thing.GOAL
    }

    private fun thingSelectionForTypeFilter(typeFilterMask: Int): String {
        val typeColumn = Def.Database.COLUMN_TYPE_THINGS
        val mask = ThingWidgetInfo.normalizedTypeFilterMask(typeFilterMask)
        if (mask == ThingWidgetInfo.TYPE_FILTER_ALL) {
            return userThingSelection()
        }
        val types = ArrayList<Int>(4)
        if (mask and ThingWidgetInfo.TYPE_FILTER_NOTE != 0) types.add(Thing.NOTE)
        if (mask and ThingWidgetInfo.TYPE_FILTER_REMINDER != 0) types.add(Thing.REMINDER)
        if (mask and ThingWidgetInfo.TYPE_FILTER_HABIT != 0) types.add(Thing.HABIT)
        if (mask and ThingWidgetInfo.TYPE_FILTER_GOAL != 0) types.add(Thing.GOAL)
        return "$typeColumn in (${types.joinToString(",")})"
    }

    private fun thingSelectionForStatusAndTypeFilter(status: Int, typeFilterMask: Int): String {
        val typeColumn = Def.Database.COLUMN_TYPE_THINGS
        val stateColumn = Def.Database.COLUMN_STATE_THINGS
        val state = when (status) {
            Def.ThingStatus.FINISHED -> Thing.FINISHED
            Def.ThingStatus.DELETED -> Thing.DELETED
            else -> Thing.UNDERWAY
        }
        val stateCondition = "$stateColumn=$state"
        val mask = ThingWidgetInfo.normalizedTypeFilterMask(typeFilterMask)
        if (mask == ThingWidgetInfo.TYPE_FILTER_ALL) {
            return userThingSelection() + " and $stateCondition"
        }
        val types = ArrayList<Int>(4)
        if (mask and ThingWidgetInfo.TYPE_FILTER_NOTE != 0) types.add(Thing.NOTE)
        if (mask and ThingWidgetInfo.TYPE_FILTER_REMINDER != 0) types.add(Thing.REMINDER)
        if (mask and ThingWidgetInfo.TYPE_FILTER_HABIT != 0) types.add(Thing.HABIT)
        if (mask and ThingWidgetInfo.TYPE_FILTER_GOAL != 0) types.add(Thing.GOAL)
        return "$typeColumn in (${types.joinToString(",")}) and $stateCondition"
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

    private fun folderLocationComparator(): Comparator<ThingFolder> {
        return Comparator { folder1, folder2 ->
            val result = ThingsSorter.compareByLocationAndSticky(
                folder1.location,
                folder2.location
            )
            if (result != 0) result else folder1.id.compareTo(folder2.id)
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
