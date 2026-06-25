package com.ywwynm.everythingdone.managers

import android.content.Context
import android.database.Cursor
import android.util.SparseIntArray

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.database.ThingFolderDAO
import com.ywwynm.everythingdone.helpers.AutoNotifyHelper
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.model.Reminder
import com.ywwynm.everythingdone.model.HomeEmptyStateHistory
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingFolderCardPresentation
import com.ywwynm.everythingdone.model.ThingListEntry
import com.ywwynm.everythingdone.model.ThingListProjection
import com.ywwynm.everythingdone.model.ThingWidgetInfo
import com.ywwynm.everythingdone.model.ThingsCounts
import com.ywwynm.everythingdone.utils.SystemNotificationUtil
import com.ywwynm.everythingdone.utils.ThingsSorter

import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Created by ywwynm on 2015/9/6.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Controller for [Thing].
 * Containing a [ThingDAO] member [mDao] to operate database.
 *
 * Please notice that we cannot use lambda in this class to replace "new Runnable".
 */
open class ThingManager private constructor(context: Context?) {

    private var mContext: Context? = context!!.applicationContext

    private var mDao: ThingDAO? = ThingDAO.getInstance(context)
    private var mFolderDao: ThingFolderDAO? = ThingFolderDAO.getInstance(context)

    /**
     * Current projection with [status] and [typeFilterMask] that controls
     * which things are fetched from the database and displayed in the UI.
     */
    private var mProjection: ThingListProjection =
        ThingListProjection.root(Def.ThingStatus.UNDERWAY)
    private val mStatus: Int get() = mProjection.status
    private val mTypeFilterMask: Int get() = mProjection.typeFilterMask

    private var mThings: MutableList<Thing?>? = null
    private var mThingListEntries: MutableList<ThingListEntry>? = null
    private var mThingsCounts: ThingsCounts? = ThingsCounts.getInstance(context)
    private val mAuthenticatedPrivateFolderIds = HashSet<Long>()

    /**
     * Used to ensure that id/location of thing in [mThings] is same as that in database.
     *
     * Every time [com.ywwynm.everythingdone.activities.DetailActivity]
     * creates a new thing and inserts it into the database, we needs to do the job
     * to [mThings], too. So keeping their latest joined ones as the same is essential,
     * especially for thing's ID/location.
     *
     * Not an elegant design for EverythingDone but it works fine, at least right now.
     */
    private var mHeaderId: Long = 0

    private var mExecutor: ExecutorService? = Executors.newSingleThreadExecutor()

    private var mIsHandlingUndo: Boolean = false
    private var mUndoHabits: MutableList<Long?>? = ArrayList()
    private var mUndoGoals: MutableList<Reminder?>? = ArrayList()

    init {
        setStatus(Def.ThingStatus.UNDERWAY, true)

        mHeaderId = mThings!![0]!!.id
    }

    open fun setStatus(status: Int, loadThingsNow: Boolean) {
        val normalizedStatus = ThingListProjection.normalizeStatus(status)
        // Switching status preserves the current Thing Scope (folder path) and the
        // current type filter. If the current folder cannot be entered under the
        // new status (a trashed folder under a non-DELETED status, or a folder that
        // no longer exists), fall back toward the "全部记事" root.
        mProjection = mProjection.withStatus(normalizedStatus)
        trimProjectionToVisibleFolders()
        trimAuthenticatedPrivateFoldersToProjection()
        mDao!!.setProjection(normalizedStatus, mTypeFilterMask)
        if (loadThingsNow) {
            loadThings()
        }
    }

    open fun setTypeFilterMask(typeFilterMask: Int, loadThingsNow: Boolean) {
        mProjection = mProjection.withTypeFilterMask(typeFilterMask)
        mDao!!.setProjection(mStatus, mTypeFilterMask)
        if (loadThingsNow) {
            loadThings()
        }
    }

    open fun getActiveTypeFilterMask(): Int {
        return ThingWidgetInfo.normalizedTypeFilterMask(mTypeFilterMask)
    }

    open fun hasCustomTypeFilter(): Boolean {
        return ThingWidgetInfo.isSpecificTypeFilterMask(mTypeFilterMask)
    }

    open fun getUndoGoals(): MutableList<Reminder?>? {
        return mUndoGoals
    }

    open fun loadThings() {
        mThings = getThingsForCurrentProjection(null, 0).toMutableList()
        rebuildThingListEntries()
    }

    private fun getThingsForCurrentProjection(keyword: String?, color: Int): List<Thing?> {
        val currentFolderId = mProjection.currentFolderId
        if (mStatus == Def.ThingStatus.DELETED
            && mFolderDao!!.isEffectivelyDeleted(currentFolderId)
        ) {
            return mDao!!.getThingsForEffectiveDeletedFolderProjection(
                currentFolderId,
                keyword,
                color
            )
        }
        return mDao!!.getThingsForProjection(
            mStatus,
            mTypeFilterMask,
            currentFolderId,
            keyword,
            color
        )
    }

    open fun getThings(): MutableList<Thing?>? {
        return mThings
    }

    open fun getThingListEntries(): MutableList<ThingListEntry>? {
        return mThingListEntries
    }

    open fun getThingListEntry(position: Int): ThingListEntry? {
        val entries = mThingListEntries ?: return null
        if (position < 0 || position >= entries.size) return null
        return entries[position]
    }

    open fun getThingAtListPosition(position: Int): Thing? {
        val entries = mThingListEntries
        if (entries != null) {
            if (position < 0 || position >= entries.size) return null
            val entry = entries[position]
            return if (entry is ThingListEntry.ThingEntry) entry.thing else null
        }
        val things = mThings ?: return null
        if (position < 0 || position >= things.size) return null
        return things[position]
    }

    open fun getThingIndexForListPosition(position: Int): Int {
        val entries = mThingListEntries
        if (entries != null) {
            if (position < 0 || position >= entries.size) return -1
            val entry = entries[position]
            return if (entry is ThingListEntry.ThingEntry) {
                mThings?.indexOf(entry.thing) ?: -1
            } else {
                -1
            }
        }
        val things = mThings ?: return -1
        return if (position in 0..<things.size) position else -1
    }

    open fun getListPositionForThingId(thingId: Long): Int {
        val entries = mThingListEntries ?: return getPosition(thingId)
        for (i in entries.indices) {
            val entry = entries[i]
            if (entry is ThingListEntry.ThingEntry && entry.thing.id == thingId) {
                return i
            }
        }
        return -1
    }

    open fun getListPositionForFolderId(folderId: Long): Int {
        val entries = mThingListEntries ?: return -1
        for (i in entries.indices) {
            val entry = entries[i]
            if (entry is ThingListEntry.FolderEntry && entry.folder.id == folderId) {
                return i
            }
        }
        return -1
    }

    open fun getListPositionForStableId(stableId: Long): Int {
        val entries = mThingListEntries ?: return -1
        for (i in entries.indices) {
            if (entries[i].stableId == stableId) return i
        }
        return -1
    }

    open fun hasFolderEntries(): Boolean {
        val entries = mThingListEntries ?: return false
        for (entry in entries) {
            if (entry is ThingListEntry.FolderEntry) return true
        }
        return false
    }

    open fun getVisibleChildCountForActivityHeader(): Int {
        val counts = getVisibleChildCountsForActivityHeader()
        return counts[0] + counts[1]
    }

    open fun getVisibleChildCountsForActivityHeader(): IntArray {
        val entries = mThingListEntries ?: return intArrayOf(0, 0)
        val counts = intArrayOf(0, 0)
        for (entry in entries) {
            if (entry is ThingListEntry.ThingEntry) {
                val thing = entry.thing
                if (thing.type != Thing.HEADER && Thing.isVisibleListThingType(thing.type)) {
                    counts[1]++
                }
            } else if (entry is ThingListEntry.FolderEntry) {
                counts[0]++
                counts[1] += entry.recursiveThingCount
            }
        }
        return counts
    }

    open fun getProjection(): ThingListProjection {
        return mProjection
    }

    open fun getCurrentFolderPath(): List<ThingFolder> {
        return mFolderDao!!.getFolderPath(mProjection.currentFolderId)
    }

    open fun getFolderPath(folderId: Long?): List<ThingFolder> {
        return mFolderDao!!.getFolderPath(folderId)
    }

    open fun getDrawerFolders(): List<ThingFolder> {
        val folders = ArrayList<ThingFolder>()
        for (folder in mFolderDao!!.getAllFolders()) {
            if (!mFolderDao!!.isEffectivelyDeleted(folder.id)) {
                folders.add(folder)
            }
        }
        return folders
    }

    /** Whether any Thing Folder row exists in the database, including trashed ones. */
    open fun hasAnyFolder(): Boolean {
        return mFolderDao!!.hasAnyFolder()
    }

    open fun getFolderThumbnailPreviewEntries(folder: ThingFolder): List<ThingListEntry> {
        return mFolderDao!!.getThumbnailEntriesForTypeFilterPreview(
            folder,
            mStatus,
            mTypeFilterMask
        )
    }

    open fun isCurrentFolderEffectivelyPrivate(): Boolean {
        return mFolderDao!!.isEffectivelyPrivate(mProjection.currentFolderId)
    }

    open fun isCurrentFolderPrivacyAuthenticated(): Boolean {
        return isFolderPrivacyAuthenticated(mProjection.currentFolderId)
    }

    open fun isFolderPrivacyAuthenticated(folderId: Long?): Boolean {
        if (folderId == null) return false
        val path = mFolderDao!!.getFolderPath(folderId)
        for (folder in path) {
            if (folder.isPrivate && mAuthenticatedPrivateFolderIds.contains(folder.id)) {
                return true
            }
        }
        return false
    }

    open fun markFolderPrivacyAuthenticated(folderId: Long?) {
        if (folderId == null) return
        val path = mFolderDao!!.getFolderPath(folderId)
        for (folder in path) {
            if (folder.isPrivate) {
                mAuthenticatedPrivateFolderIds.add(folder.id)
            }
        }
    }

    open fun openFolder(folderId: Long, authenticated: Boolean = false) {
        if (authenticated) {
            markFolderPrivacyAuthenticated(folderId)
        }
        mProjection = mProjection.openFolder(folderId)
        trimAuthenticatedPrivateFoldersToProjection()
        loadThings()
    }

    open fun openFolderPath(folderId: Long, authenticated: Boolean = false) {
        val path = mFolderDao!!.getFolderPath(folderId)
        if (path.isEmpty()) return
        if (authenticated) {
            markFolderPrivacyAuthenticated(folderId)
        }
        mProjection = mProjection.copy(folderPath = path.map { it.id })
        trimAuthenticatedPrivateFoldersToProjection()
        loadThings()
    }

    open fun navigateToFolderPathIndex(index: Int) {
        mProjection = mProjection.navigateToPathIndex(index)
        trimAuthenticatedPrivateFoldersToProjection()
        loadThings()
    }

    open fun openParentFolder(): Boolean {
        if (mProjection.isRoot()) return false
        mProjection = mProjection.parent()
        trimAuthenticatedPrivateFoldersToProjection()
        loadThings()
        return true
    }

    private fun trimAuthenticatedPrivateFoldersToProjection() {
        if (mAuthenticatedPrivateFolderIds.isEmpty()) return
        val pathIds = mProjection.folderPath.toHashSet()
        mAuthenticatedPrivateFolderIds.retainAll(pathIds)
    }

    open fun getThingsCounts(): ThingsCounts? {
        return mThingsCounts
    }

    open fun getHeaderId(): Long {
        return mHeaderId
    }

    open fun searchThings(keyword: String?, color: Int) {
        val things: List<Thing?> = getThingsForCurrentProjection(keyword, color)
        val PTP: String = Thing.PRIVATE_THING_PREFIX
        var containsPtp = false
        var i = 0
        while (i < PTP.length && !containsPtp) {
            if (keyword!!.contains(PTP[i].toString())) {
                containsPtp = true
            }
            i++
        }

        if (CheckListHelper.isSignalContainsStrIgnoreCase(keyword) || containsPtp) {
            mThings!!.clear()
            mThings!!.add(mDao!!.getThingById(mDao!!.getHeaderId()))
            for (thing in things) {
                if (thing!!.type == Thing.HEADER) continue

                val sbRegex: StringBuilder = StringBuilder()
                for (j in 0 until CheckListHelper.CHECK_STATE_NUM) {
                    sbRegex.append(CheckListHelper.SIGNAL).append(j).append("|")
                }
                sbRegex.deleteCharAt(sbRegex.length - 1)
                val content: String = thing.content!!.replace(sbRegex.toString().toRegex(), "")
                val title: String   = thing.title!!.replace(PTP.toRegex(), "")

                if (content.contains(keyword!!) || title.contains(keyword)) {
                    mThings!!.add(thing)
                }
            }
        } else {
            mThings = things.toMutableList()
        }
        rebuildThingListEntries(keyword, color)
    }

    private fun rebuildThingListEntries(keyword: String? = null, color: Int = 0) {
        val things = mThings ?: return
        val entries = ArrayList<ThingListEntry>()
        val mixed = ArrayList<ThingListEntry>()
        for (thing in things) {
            if (thing == null) continue
            val entry = ThingListEntry.ThingEntry(thing)
            if (thing.type == Thing.HEADER) {
                entries.add(entry)
            } else {
                mixed.add(entry)
            }
        }
        val folderEntries = mFolderDao!!.getFolderEntriesForTypeFilterProjection(
            mStatus,
            mTypeFilterMask,
            mProjection.currentFolderId,
            keyword,
            color
        )
        mixed.addAll(folderEntries)
        Collections.sort(mixed, object : Comparator<ThingListEntry> {
            override fun compare(entry1: ThingListEntry, entry2: ThingListEntry): Int {
                val result = ThingsSorter.compareByLocationAndSticky(
                    entry1.location,
                    entry2.location
                )
                if (result != 0) return result
                return entry1.stableId.compareTo(entry2.stableId)
            }
        })
        entries.addAll(mixed)
        mThingListEntries = entries
    }

    private fun entryHasVisibleProjectionContent(entry: ThingListEntry): Boolean {
        return when (entry) {
            is ThingListEntry.FolderEntry -> true
            is ThingListEntry.ThingEntry -> {
                val type = entry.thing.type
                type != Thing.HEADER && Thing.isVisibleListThingType(type)
            }
        }
    }

    /**
     * Create a new thing.
     *
     * @param thingToCreate the thing to create.
     * @param handleNotifyEmpty kept for older call sites; legacy empty placeholders are no longer created.
     * @return always `false`; callers should refresh the list and empty-state UI normally.
     */
    open fun create(thingToCreate: Thing?, handleNotifyEmpty: Boolean, addToThingsNow: Boolean): Boolean {
        // create in database at first
        thingToCreate!!.id = mHeaderId
        if (Thing.isRealThingType(thingToCreate.type) && thingToCreate.folderId == null) {
            thingToCreate.folderId = mProjection.currentFolderId
        }
        mDao!!.create(thingToCreate, false, false)
//        mExecutor.execute(new Runnable() {
//            @Override
//            public void run() {
//                mDao.create(thingToCreate, true, false);
//            }
//        });

        updateHeader(1)
        val type: Int = thingToCreate.type

        if (addToThingsNow) {
            mThings!!.add(getPositionToInsertNewThing(), thingToCreate)
            rebuildThingListEntries()
        }

        if (Thing.isRealThingType(type)) {
            HomeEmptyStateHistory.markThingCreated(mContext, type)
            AutoNotifyHelper.createAutoNotify(thingToCreate, mContext)
            mThingsCounts!!.handleCreation(type)
        }

        return false
    }

    /**
     * This method will be called to update a [Thing]'s content(including [Thing.type],
     * [Thing.title], [Thing.content] and so on. NOT including [Thing.state]).
     * And this method will be only called when `updatedThing` is in [mThings].
     *
     * @param typeBefore old type of `updatedThing`
     * @param updatedThing thing whose content is updated.
     * @param position position `updatedThing`'s position in [mThings].
     * @param handleNotifyEmpty kept for older call sites; legacy empty placeholders are no longer created.
     * @return 0 if update really happens and [com.ywwynm.everythingdone.activities.ThingsActivity]
     *         should call [com.ywwynm.everythingdone.adapters.ThingsAdapter.notifyItemChanged].
     *         2 if the updated Thing leaves the current projection.
     */
    open fun update(@Thing.Type typeBefore: Int, updatedThing: Thing?, position: Int,
                    handleNotifyEmpty: Boolean): Int {
        mExecutor!!.execute {
            mDao!!.update(typeBefore, updatedThing, false, false)
        }

        val state: Int     = updatedThing!!.state
        val typeAfter: Int = updatedThing.type
        mThingsCounts!!.handleUpdate(typeBefore, state, typeAfter, state, 1)
        if (Thing.isRealThingType(typeAfter)) {
            HomeEmptyStateHistory.markThingCreated(mContext, typeAfter)
        }

        if (mStatus == Def.ThingStatus.UNDERWAY ||
                Thing.sameType(typeBefore, typeAfter)) {
            rebuildThingListEntries()
            return 0
        } else {
            mThings!!.removeAt(position)
            rebuildThingListEntries()
            return 2
        }
    }

    /**
     * Updates thing's state.
     * This method will be only called when `thing` is in [mThings].
     */
    open fun updateState(thing: Thing?, position: Int, location: Long,
                         @Thing.State stateBefore: Int, @Thing.State stateAfter: Int,
                         toUndo: Boolean, handleNotifyEmpty: Boolean): Boolean {
        val thingId: Long = thing!!.id
        Thing.tryToCancelOngoing(mContext, thingId)

        @Thing.Type val thingType: Int = thing.type
        if (thingType == Thing.HEADER) return false

        val thingToUpdate = if (!toUndo) {
            Thing.getSameCheckStateThing(thing, stateBefore, stateAfter)
        } else thing
        val locationToUpdate = if (!toUndo && stateAfter != Thing.DELETED_FOREVER) {
            getLocationForStateChange(thing)
        } else {
            location
        }
        mExecutor!!.execute {
            mDao!!.updateState(thingToUpdate, locationToUpdate, stateBefore, stateAfter,
                    false, false, toUndo, true)
        }

        if (toUndo) {
            mThings!!.add(position, thing)
        } else {
            // used to make sure that updated thing is at first location except header
            if (stateAfter != Thing.DELETED_FOREVER && thing.location >= 0) {
                updateHeader(1)
            }
            if (mThings!!.indexOf(thing) == position && position != -1) {
                mThings!!.removeAt(position)
            }
        }

        if (thingType == Thing.GOAL) {
            val rDao: ReminderDAO = ReminderDAO.getInstance(mContext)!!
            if (!toUndo && stateAfter == Thing.UNDERWAY) {
                val goal: Reminder? = rDao.getReminderById(thingId)
                mUndoGoals!!.add(goal)
                rDao.resetGoal(goal)
            } else if (toUndo && stateBefore == Thing.UNDERWAY) {
                mIsHandlingUndo = true
                rDao.update(mUndoGoals!!.removeAt(mUndoGoals!!.size - 1)) // undo reset goal
                mIsHandlingUndo = false
            }
        }
        if (thingType == Thing.HABIT) {
            val habitDAO: HabitDAO = HabitDAO.getInstance(mContext)!!
            if (!toUndo) {
                val curTime: Long = System.currentTimeMillis()
                if (stateAfter == Thing.UNDERWAY) {
                    habitDAO.updateHabitToLatest(thingId, true, true)
                    habitDAO.addHabitIntervalInfo(thingId, "$curTime;")
                } else {
                    if (habitDAO.isPaused(thingId)) {
                        habitDAO.addHabitIntervalInfo(thingId, "$curTime;")
                    }
                    habitDAO.addHabitIntervalInfo(thingId, "$curTime,")
                }
            } else {
                habitDAO.removeLastHabitIntervalInfo(thingId)
            }
        }

        mThingsCounts!!.handleUpdate(thingType, stateBefore, thingType, stateAfter, 1)

        rebuildThingListEntries()
        return false
    }

    /**
     * Update states for a number of things with same old state.
     */
    open fun updateStates(
            things: List<Thing?>?, @Thing.State stateBefore: Int, @Thing.State stateAfter: Int): List<Int?>? {
        val ONGOING_K: String = Def.Meta.KEY_ONGOING_THING_ID
        val curOngoingId: Long = FrequentSettings.getLong(ONGOING_K)
        var shouldCancelOngoing = false

        val clonedThings: MutableList<Thing?> = ArrayList()
        var temp: Thing?
        for (thing in things!!) {
            if (thing!!.id == curOngoingId) {
                shouldCancelOngoing = true
            }
            temp = Thing.getSameCheckStateThing(thing, stateBefore, stateAfter)
            clonedThings.add(temp)
        }

        if (shouldCancelOngoing) {
            SystemNotificationUtil.cancelThingOngoingNotification(mContext, curOngoingId)
            mContext!!.getSharedPreferences(Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(ONGOING_K, -1L).apply()
            FrequentSettings.put(ONGOING_K, -1L)
        }

        val stateChangeLocations = getLocationsForStateChange(things)

        mExecutor!!.execute {
            mDao!!.updateStates(clonedThings, stateChangeLocations, stateBefore, stateAfter, false)
        }

        var type: Int = things[0]!!.type

        val size: Int = things.size
        val nonStickyCount = things.count { it != null && it.location >= 0 }
        updateHeader(nonStickyCount)

        val rDao: ReminderDAO = ReminderDAO.getInstance(mContext)!!
        val positions: MutableList<Int?> = ArrayList(size)
        val updateCounts = SparseIntArray()
        //HashMap<Integer, Integer> updateCounts = new HashMap<>();
        for (thing in things) {
            val pos: Int = mThings!!.indexOf(thing)
            positions.add(pos)

            mThings!!.remove(thing)

            val id: Long = thing!!.id
            type = thing.type
            if (type == Thing.HABIT) {
                mUndoHabits!!.add(id)
            } else if (type == Thing.GOAL && stateAfter == Thing.UNDERWAY) {
                mUndoGoals!!.add(rDao.getReminderById(id))
            }

            updateCounts.put(type, updateCounts.get(type) + 1)
        }

        val updateCountsSize: Int = updateCounts.size()
        for (i in 0 until updateCountsSize) {
            val t: Int = updateCounts.keyAt(i)
            val c: Int = updateCounts.valueAt(i)
            mThingsCounts!!.handleUpdate(t, stateBefore, t, stateAfter, c)
        }

//        for (Map.Entry<Integer, Integer> entry : updateCounts.entrySet()) {
//            int t = entry.getKey();
//            int v = entry.getValue();
//            mThingsCounts.handleUpdate(t, stateBefore, t, stateAfter, v);
//        }

        mExecutor!!.execute {
            for (goal in mUndoGoals!!) {
                rDao.resetGoal(goal)
            }

            val habitDAO: HabitDAO = HabitDAO.getInstance(mContext)!!
            val curTime: Long = System.currentTimeMillis()
            if (stateAfter == Thing.UNDERWAY) {
                for (habitId in mUndoHabits!!) {
                    habitDAO.updateHabitToLatest(habitId!!, true, true)
                    habitDAO.addHabitIntervalInfo(habitId, "$curTime;")
                }
            } else {
                for (habitId in mUndoHabits!!) {
                    if (habitDAO.isPaused(habitId!!)) {
                        habitDAO.addHabitIntervalInfo(habitId, "$curTime;")
                    }
                    habitDAO.addHabitIntervalInfo(habitId, "$curTime,")
                }
            }
        }

        rebuildThingListEntries()
        return positions
    }

    /**
     * undo update for states of a number of things.
     */
    open fun undoUpdateStates(things: List<Thing?>?, positions: List<Int?>?,
                              locations: List<Long?>?,
                              @Thing.State stateBefore: Int,
                              @Thing.State stateAfter: Int) {
        val clonedThings: MutableList<Thing?> = ArrayList()
        for (thing in things!!) {
            clonedThings.add(thing)
        }
        val clonedLocations: MutableList<Long?> = ArrayList()
        for (location in locations!!) {
            clonedLocations.add(location)
        }

        mExecutor!!.execute {
            mDao!!.updateStates(clonedThings, clonedLocations, stateBefore, stateAfter,
                    true)
        }

        var type: Int = things[0]!!.type

        val size: Int = things.size
        val updateCounts = SparseIntArray()
        //HashMap<Integer, Integer> updateCounts = new HashMap<>();
        var thing: Thing
        for (i in size - 1 downTo 0) {
            thing = things[i]!!
            type = thing.type
            mThings!!.add(positions!![i]!!, thing)
            updateCounts.put(type, updateCounts.get(type) + 1)
        }

        val updateCountsSize: Int = updateCounts.size()
        for (i in 0 until updateCountsSize) {
            val t: Int = updateCounts.keyAt(i)
            val c: Int = updateCounts.valueAt(i)
            mThingsCounts!!.handleUpdate(t, stateBefore, t, stateAfter, c)
        }

//        for (Map.Entry<Integer, Integer> entry : updateCounts.entrySet()) {
//            int t = entry.getKey();
//            int v = entry.getValue();
//            mThingsCounts.handleUpdate(t, stateBefore, t, stateAfter, v);
//        }

        mExecutor!!.execute {
            mIsHandlingUndo = true
            val reminderDAO: ReminderDAO = ReminderDAO.getInstance(mContext)!!
            for (goal in mUndoGoals!!) {
                reminderDAO.update(goal)
            }
            mUndoGoals!!.clear()

            val habitDAO: HabitDAO = HabitDAO.getInstance(mContext)!!
            for (habitId in mUndoHabits!!) {
                habitDAO.removeLastHabitIntervalInfo(habitId!!)
            }
            mUndoHabits!!.clear()
            mIsHandlingUndo = false
        }

        rebuildThingListEntries()
    }

    open fun clearLists() {
        if (mIsHandlingUndo) {
            return
        }
        mUndoGoals!!.clear()
        mUndoHabits!!.clear()
    }

    /**
     * move a visible list entry from one position to another.
     *
     * Please be careful that moving isn't atomic operation. As a result, when user
     * drags an entry and moves it to a new position, this method will be called several times.
     * That's why we need [updateLocations] to truly update its
     * location in database and keep stability.
     */
    open fun move(from: Int, to: Int) {
        val entries = mThingListEntries
        if (entries != null && from in entries.indices && to in entries.indices) {
            val temp = entries.removeAt(from)
            entries.add(to, temp)
            rebuildThingsFromListEntries()
            return
        }

        val temp: Thing? = mThings!![from]
        mThings!!.removeAt(from)
        mThings!!.add(to, temp)
    }

    /**
     * This method will only be called after all [move]s
     * have been already called for better performance.
     */
    open fun updateLocations(from: Int, to: Int) {
        val entries = mThingListEntries
        if (entries != null && from in entries.indices && to in entries.indices) {
            updateMixedEntryLocations(entries, from, to)
            return
        }

        val start: Int = if (from < to) from else to
        val end: Int = if (to > from) to else from
        val ids: Array<Long?> = arrayOfNulls(end - start + 1)
        val locations: Array<Long?> = arrayOfNulls(end - start + 1)

        for (i in start..end) {
            ids[i - start] = mThings!![i]!!.id
            locations[i - start] = mThings!![i]!!.location
        }

        // moving between sticky things
        if (locations[0]!! < 0) {
            Arrays.sort(locations)
        } else {
            Arrays.sort(locations, Collections.reverseOrder())
        }

        var i: Int = start
        var j = 0
        while (i <= end) {
            mThings!![i]!!.location = locations[j]!!
            i++
            j++
        }
        mExecutor!!.execute {
            mDao!!.updateLocations(ids, locations)
        }
    }

    open fun canMoveListEntry(from: Int, to: Int): Boolean {
        val entries = mThingListEntries ?: return false
        if (from == to || from <= 0 || to <= 0) return false
        if (from !in entries.indices || to !in entries.indices) return false

        val entry1 = entries[from]
        val entry2 = entries[to]
        if (entry1 is ThingListEntry.ThingEntry && entry1.thing.type == Thing.HEADER) {
            return false
        }
        if (entry2 is ThingListEntry.ThingEntry && entry2.thing.type == Thing.HEADER) {
            return false
        }

        val loc1 = entry1.location
        val loc2 = entry2.location
        return loc1 < 0 && loc2 < 0 || loc1 >= 0 && loc2 >= 0
    }

    private fun updateMixedEntryLocations(
        entries: MutableList<ThingListEntry>,
        from: Int,
        to: Int
    ) {
        val start: Int = if (from < to) from else to
        val end: Int = if (to > from) to else from
        val locations: Array<Long?> = arrayOfNulls(end - start + 1)
        for (i in start..end) {
            locations[i - start] = entries[i].location
        }

        if (locations[0]!! < 0) {
            Arrays.sort(locations)
        } else {
            Arrays.sort(locations, Collections.reverseOrder())
        }

        val thingIds = ArrayList<Long?>()
        val thingLocations = ArrayList<Long?>()
        val folderIds = ArrayList<Long?>()
        val folderLocations = ArrayList<Long?>()
        var j = 0
        for (i in start..end) {
            val entry = entries[i]
            val location = locations[j++]!!
            if (entry is ThingListEntry.ThingEntry) {
                entry.thing.location = location
                thingIds.add(entry.thing.id)
                thingLocations.add(location)
            } else if (entry is ThingListEntry.FolderEntry) {
                entry.folder.location = location
                folderIds.add(entry.folder.id)
                folderLocations.add(location)
            }
        }
        rebuildThingsFromListEntries()

        mExecutor!!.execute {
            if (thingIds.isNotEmpty()) {
                mDao!!.updateLocations(thingIds.toTypedArray(), thingLocations.toTypedArray())
            }
            if (folderIds.isNotEmpty()) {
                mFolderDao!!.updateLocations(folderIds.toTypedArray(), folderLocations.toTypedArray())
            }
        }
    }

    private fun rebuildThingsFromListEntries() {
        val entries = mThingListEntries ?: return
        val things = ArrayList<Thing?>()
        for (entry in entries) {
            if (entry is ThingListEntry.ThingEntry) {
                things.add(entry.thing)
            }
        }
        mThings = things
    }

    open fun updateThingCardAppearance(thing: Thing?) {
        if (thing == null) return
        mExecutor!!.execute {
            mDao!!.updateThingCardAppearance(thing)
        }
    }

    open fun createFolderFromThings(
        title: String,
        firstThing: Thing?,
        secondThing: Thing?,
        background: ThingBackground? = null
    ): ThingFolder? {
        val first = firstThing ?: return null
        val second = secondThing ?: return null
        if (!canCreateFolderMember(firstThing) || !canCreateFolderMember(secondThing)) return null
        if (first.id == second.id) return null

        val folderId = mHeaderId
        val now = System.currentTimeMillis()
        val folderBackground = background ?: ThingBackground.fromRandom()
        val folderSticky = first.location < 0 || second.location < 0
        val folderLocation = mFolderDao!!.getFirstChildLocation(
            mProjection.currentFolderId,
            sticky = folderSticky
        )
        val folder = ThingFolder(
            id = folderId,
            parentFolderId = mProjection.currentFolderId,
            title = title,
            state = Thing.UNDERWAY,
            color = folderBackground.representativeColor(),
            location = folderLocation,
            isPrivate = false,
            createTime = now,
            updateTime = now
        )
        folder.setBackground(folderBackground)

        mFolderDao!!.create(folder)
        HomeEmptyStateHistory.markFolderCreated(mContext)
        mDao!!.updateHeader(1)
        updateHeader(1)

        moveThingIntoFolder(first, folder.id, false)
        moveThingIntoFolder(second, folder.id, false)
        loadThings()
        return folder
    }

    open fun cancelCreatedFolder(
        folder: ThingFolder?,
        thingIds: LongArray?,
        parentFolderIds: Array<Long?>? = null,
        locations: LongArray? = null
    ): Boolean {
        if (folder == null || thingIds == null || thingIds.isEmpty()) return false

        val restoreSnapshots = HashMap<Long, Pair<Long?, Long>>()
        if (parentFolderIds != null && locations != null) {
            val count = minOf(thingIds.size, parentFolderIds.size, locations.size)
            for (i in 0 until count) {
                restoreSnapshots[thingIds[i]] = parentFolderIds[i] to locations[i]
            }
        }

        val createdFolderMembers = ArrayList<Thing>()
        for (thingId in thingIds.toSet()) {
            val thing = mDao!!.getThingById(thingId) ?: continue
            if (thing.folderId == folder.id) {
                createdFolderMembers.add(thing)
            }
        }
        if (createdFolderMembers.isEmpty()) return false
        if (mFolderDao!!.countAllDescendantThings(folder.id) != createdFolderMembers.size) {
            return false
        }

        for (i in createdFolderMembers.indices.reversed()) {
            val thing = createdFolderMembers[i]
            val restoreSnapshot = restoreSnapshots[thing.id]
            if (restoreSnapshot != null) {
                thing.folderId = restoreSnapshot.first
                thing.location = restoreSnapshot.second
                mDao!!.updateFolderIdAndLocation(
                    thing.id,
                    restoreSnapshot.first,
                    restoreSnapshot.second
                )
            } else {
                moveThingIntoFolderInternal(
                    thing,
                    folder.parentFolderId,
                    cleanUpEmptySource = false
                )
            }
        }
        mFolderDao!!.deleteRecord(folder.id)
        trimProjectionToExistingFolders()
        loadThings()
        return true
    }

    private fun canCreateFolderMember(thing: Thing?): Boolean {
        if (thing == null) return false
        if (thing.type !in Thing.NOTE..Thing.GOAL) return false
        return thing.state == Thing.UNDERWAY
    }

    open fun moveThingIntoFolder(thing: Thing?, folderId: Long?, reload: Boolean = true) {
        if (thing == null || thing.type == Thing.HEADER) return
        val changed = moveThingIntoFolderInternal(thing, folderId)
        if (changed && reload) {
            loadThings()
        }
    }

    private fun moveThingIntoFolderInternal(
        thing: Thing,
        folderId: Long?,
        cleanUpEmptySource: Boolean = true
    ): Boolean {
        val sourceFolderId = thing.folderId
        if (sourceFolderId == folderId) return false
        val newLocation = mFolderDao!!.getFirstChildLocation(folderId, thing.location < 0)
        thing.folderId = folderId
        thing.location = newLocation
        mDao!!.updateFolderIdAndLocation(thing.id, folderId, newLocation)
        trimProjectionToExistingFolders()
        return true
    }

    open fun getThingMoveTargetFolders(): List<ThingFolder> {
        val candidates = ArrayList<ThingFolder>()
        for (candidate in mFolderDao!!.getAllFolders()) {
            if (mFolderDao!!.isEffectivelyDeleted(candidate.id)) continue
            candidates.add(candidate)
        }
        return candidates
    }

    open fun moveSelectedThingsIntoFolder(folderId: Long?): Boolean {
        val selectedThings = getSelectedThings() ?: return false
        var changed = false
        for (i in selectedThings.indices.reversed()) {
            val thing = selectedThings[i]
            if (!canMoveThingToFolder(thing)) continue
            if (thing!!.folderId == folderId) {
                thing.selected = false
                continue
            }
            thing.selected = false
            if (moveThingIntoFolderInternal(thing, folderId)) {
                changed = true
            }
        }
        if (changed) {
            loadThings()
        }
        return changed
    }

    private fun canMoveThingToFolder(thing: Thing?): Boolean {
        if (thing == null) return false
        if (thing.type !in Thing.NOTE..Thing.GOAL) return false
        return thing.state == Thing.UNDERWAY
    }

    open fun getFolderMoveCandidates(folder: ThingFolder?): List<ThingFolder> {
        if (folder == null) return emptyList()
        val candidates = ArrayList<ThingFolder>()
        for (candidate in mFolderDao!!.getAllFolders()) {
            if (candidate.id == folder.id) continue
            if (mFolderDao!!.isDescendantOf(candidate.id, folder.id)) continue
            if (mFolderDao!!.isEffectivelyDeleted(candidate.id)) continue
            candidates.add(candidate)
        }
        return candidates
    }

    open fun isFolderEffectivelyPrivate(folderId: Long?): Boolean {
        return mFolderDao!!.isEffectivelyPrivate(folderId)
    }

    open fun isFolderDescendantOf(folderId: Long?, possibleAncestorId: Long): Boolean {
        return mFolderDao!!.isDescendantOf(folderId, possibleAncestorId)
    }

    open fun moveFolderIntoFolder(folder: ThingFolder?, parentFolderId: Long?): Boolean {
        if (folder == null) return false
        if (parentFolderId == folder.id) return false
        if (mFolderDao!!.isDescendantOf(parentFolderId, folder.id)) return false
        val sourceParentFolderId = folder.parentFolderId
        if (sourceParentFolderId == parentFolderId) return false
        val newLocation = mFolderDao!!.getFirstChildLocation(parentFolderId, folder.isSticky())
        folder.parentFolderId = parentFolderId
        folder.location = newLocation
        mFolderDao!!.updateParentAndLocation(folder.id, parentFolderId, newLocation)
        trimProjectionToExistingFolders()
        loadThings()
        return true
    }

    private fun trimProjectionToExistingFolders() {
        if (mProjection.folderPath.isEmpty()) return
        val existingPath = ArrayList<Long>()
        for (folderId in mProjection.folderPath) {
            if (mFolderDao!!.getFolderById(folderId) == null) break
            existingPath.add(folderId)
        }
        if (existingPath.size != mProjection.folderPath.size) {
            mProjection = mProjection.copy(folderPath = existingPath)
            trimAuthenticatedPrivateFoldersToProjection()
        }
    }

    private fun trimProjectionToVisibleFolders() {
        if (mProjection.folderPath.isEmpty()) return
        val visiblePath = ArrayList<Long>()
        for (folderId in mProjection.folderPath) {
            if (mFolderDao!!.getFolderById(folderId) == null) break
            val effectivelyDeleted = mFolderDao!!.isEffectivelyDeleted(folderId)
            // New model: a non-deleted folder is a valid Scope under any status
            // (under DELETED it shows its trashed content as a Projection Folder).
            // A trashed (effectively deleted) folder is a valid Scope only under
            // the DELETED status; under UNDERWAY/FINISHED the path falls back here.
            if (effectivelyDeleted && mStatus != Def.ThingStatus.DELETED) break
            visiblePath.add(folderId)
        }
        if (visiblePath.size != mProjection.folderPath.size) {
            mProjection = mProjection.copy(folderPath = visiblePath)
            trimAuthenticatedPrivateFoldersToProjection()
        }
    }

    open fun renameFolder(folder: ThingFolder?, title: String): Boolean {
        if (folder == null) return false
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return false
        folder.title = cleanTitle
        mFolderDao!!.update(folder)
        loadThings()
        return true
    }

    open fun updateFolderAppearance(
        folder: ThingFolder?,
        title: String,
        presentation: ThingFolderCardPresentation?
    ): Boolean {
        if (folder == null || presentation == null) return false
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return false
        folder.title = cleanTitle
        folder.cardPresentation = if (folder.isPrivate) {
            ThingFolderCardPresentation.default()
        } else {
            presentation
        }
        mFolderDao!!.update(folder)
        loadThings()
        return true
    }

    open fun updateFolderCardPresentation(
        folder: ThingFolder?,
        presentation: ThingFolderCardPresentation?
    ): Boolean {
        if (folder == null || presentation == null) return false
        val confirmedPresentation = if (folder.isPrivate) {
            ThingFolderCardPresentation.default()
        } else {
            presentation
        }
        folder.cardPresentation = confirmedPresentation
        mFolderDao!!.updateCardPresentation(folder.id, confirmedPresentation)
        loadThings()
        return true
    }

    open fun updateFolderPrivate(folder: ThingFolder?, isPrivate: Boolean): Boolean {
        if (folder == null) return false
        folder.isPrivate = isPrivate
        if (isPrivate) {
            folder.cardPresentation = ThingFolderCardPresentation.default()
            mFolderDao!!.update(folder)
        } else {
            mAuthenticatedPrivateFolderIds.remove(folder.id)
            mFolderDao!!.updatePrivate(folder.id, false)
        }
        loadThings()
        return true
    }

    open fun getCurrentFolder(): ThingFolder? {
        val currentFolderId = mProjection.currentFolderId ?: return null
        return mFolderDao!!.getFolderById(currentFolderId)
    }

    open fun deleteFolder(folder: ThingFolder?): Boolean {
        return updateFolderState(folder, Thing.DELETED)
    }

    open fun restoreFolder(folder: ThingFolder?): Boolean {
        if (folder == null) return false
        // Restore the whole physical subtree (decision 2026-06-22, option b):
        // the folder container, every nested trashed folder, and every descendant
        // Thing that was independently trashed all come back. Things return to
        // their own pre-trash state; descendants that were only effectively
        // deleted by this folder revert automatically through their own state.
        folder.state = Thing.UNDERWAY
        mFolderDao!!.updateState(folder.id, Thing.UNDERWAY)
        for (descendantId in mFolderDao!!.getDescendantFolderIds(folder.id)) {
            if (descendantId == folder.id) continue
            val descendant = mFolderDao!!.getFolderById(descendantId) ?: continue
            if (descendant.isDeleted()) {
                mFolderDao!!.updateState(descendantId, Thing.UNDERWAY)
            }
        }
        val independentlyTrashed = mFolderDao!!.getDescendantThingsForProjection(
            folder.id, Def.ThingStatus.DELETED, ThingWidgetInfo.TYPE_FILTER_ALL
        )
        restoreThingsToPreTrashState(independentlyTrashed)
        trimProjectionToVisibleFolders()
        loadThings()
        return true
    }

    private fun restoreThingsToPreTrashState(things: List<Thing>) {
        if (things.isEmpty()) return
        val toUnderway = ArrayList<Thing>()
        val toFinished = ArrayList<Thing>()
        for (thing in things) {
            if (mDao!!.getStateBeforeDelete(thing.id) == Thing.FINISHED) {
                toFinished.add(thing)
            } else {
                toUnderway.add(thing)
            }
        }
        changeFolderSubtreeContentState(toUnderway, Thing.DELETED, Thing.UNDERWAY)
        changeFolderSubtreeContentState(toFinished, Thing.DELETED, Thing.FINISHED)
    }

    private fun updateFolderState(folder: ThingFolder?, @Thing.State state: Int): Boolean {
        if (folder == null) return false
        folder.state = state
        mFolderDao!!.updateState(folder.id, state)
        trimProjectionToVisibleFolders()
        loadThings()
        return true
    }

    open fun deleteFolderForever(folder: ThingFolder?): Boolean {
        if (folder == null) return false
        mFolderDao!!.deleteForever(folder.id)
        trimProjectionToExistingFolders()
        loadThings()
        return true
    }

    open fun dissolveFolder(folder: ThingFolder?): Boolean {
        if (folder == null) return false
        mAuthenticatedPrivateFolderIds.remove(folder.id)
        mFolderDao!!.dissolve(folder.id)
        trimProjectionToExistingFolders()
        loadThings()
        return true
    }

    /**
     * Number of Things in [folder]'s subtree that match the given status and the
     * current type filter. Used for confirmation dialog impact summaries.
     */
    open fun countFolderContentForProjection(folder: ThingFolder?, status: Int): Int {
        folder ?: return 0
        return mFolderDao!!.getDescendantThingsForProjection(folder.id, status, mTypeFilterMask).size
    }

    /** The stored pre-trash state of a Thing (see [ThingDAO.getStateBeforeDelete]). */
    open fun getStateBeforeDelete(thingId: Long): Int {
        return mDao!!.getStateBeforeDelete(thingId)
    }

    /**
     * Total user Things in [folder]'s whole physical subtree, of every state
     * including those already in the recycle bin. Use for permanent delete, which
     * destroys everything.
     */
    open fun countAllDescendantThings(folder: ThingFolder?): Int {
        folder ?: return 0
        return mFolderDao!!.countAllDescendantThings(folder.id)
    }

    /**
     * Every user Thing in [folder]'s subtree, of any state. Used by confirmation
     * dialogs to detect whether a folder operation reaches content beyond the
     * current status/type filter.
     */
    open fun getAllDescendantThings(folder: ThingFolder?): List<Thing> {
        folder ?: return emptyList()
        return mFolderDao!!.getAllDescendantThings(folder.id)
    }

    /** Number of descendant folders below [folder] (excluding the folder itself), any state. */
    open fun countDescendantFolders(folder: ThingFolder?): Int {
        folder ?: return 0
        return (mFolderDao!!.getDescendantFolderIds(folder.id).size - 1).coerceAtLeast(0)
    }

    /**
     * Descendant Things in [folder]'s subtree that are NOT already in the recycle
     * bin (own state underway or finished). Use for delete-to-bin / dissolve
     * confirmations, where already-trashed content is not newly affected.
     */
    open fun countNonDeletedDescendantThings(folder: ThingFolder?): Int {
        folder ?: return 0
        return mFolderDao!!.countDescendantThings(folder.id, Def.ThingStatus.UNDERWAY) +
            mFolderDao!!.countDescendantThings(folder.id, Def.ThingStatus.FINISHED)
    }

    /** Descendant folders below [folder] that are not themselves in the recycle bin. */
    open fun countNonDeletedDescendantFolders(folder: ThingFolder?): Int {
        folder ?: return 0
        var count = 0
        for (descendantId in mFolderDao!!.getDescendantFolderIds(folder.id)) {
            if (descendantId == folder.id) continue
            val descendant = mFolderDao!!.getFolderById(descendantId) ?: continue
            if (!descendant.isDeleted()) count++
        }
        return count
    }

    /**
     * Recursively finish the underway Things in [folder]'s subtree that match the
     * current type filter. The folder container itself has no finished state.
     * Returns the number of affected Things.
     */
    open fun finishFolderContent(folder: ThingFolder?): Int {
        folder ?: return 0
        val things = mFolderDao!!.getDescendantThingsForProjection(
            folder.id, Def.ThingStatus.UNDERWAY, mTypeFilterMask
        )
        return changeFolderSubtreeContentState(things, Thing.UNDERWAY, Thing.FINISHED)
    }

    /**
     * Recursively restore the finished Things in [folder]'s subtree that match the
     * current type filter back to underway. Returns the number of affected Things.
     */
    open fun restoreFolderContentToUnderway(folder: ThingFolder?): Int {
        folder ?: return 0
        val things = mFolderDao!!.getDescendantThingsForProjection(
            folder.id, Def.ThingStatus.FINISHED, mTypeFilterMask
        )
        return changeFolderSubtreeContentState(things, Thing.FINISHED, Thing.UNDERWAY)
    }

    /** Whether [type] passes the currently active type filter (true when "全部类型"). */
    private fun matchesActiveTypeFilter(type: Int): Boolean {
        val mask = getActiveTypeFilterMask()
        if (mask == ThingWidgetInfo.TYPE_FILTER_ALL) return true
        return ThingWidgetInfo.typeFilterMaskForThingType(type) and mask != 0
    }

    /**
     * Every underway Thing in the given scope that matches the active type filter,
     * used by the recursive "完成文件夹中所有记事" / home "全部完成" action. The
     * operation follows the current type filter: a specific filter narrows it to
     * that type only; "全部类型" affects every type. [folder] = null means the whole
     * tree from root, excluding things under a recycle-bin (deleted) folder.
     */
    open fun getUnderwayThingsInScope(folder: ThingFolder?): List<Thing> {
        if (folder != null) {
            return mFolderDao!!.getDescendantThingsForProjection(
                folder.id, Def.ThingStatus.UNDERWAY, getActiveTypeFilterMask()
            )
        }
        return mDao!!.getAllUserThingsByState(Thing.UNDERWAY).filterNotNull().filter {
            !mFolderDao!!.isEffectivelyDeleted(it.folderId) && matchesActiveTypeFilter(it.type)
        }
    }

    /** Finish the given Things (underway -> finished), recursive-safe, then reload. */
    open fun finishThings(things: List<Thing>): Int {
        return changeFolderSubtreeContentState(things, Thing.UNDERWAY, Thing.FINISHED)
    }

    /**
     * Every finished Thing in the given scope that matches the active type filter,
     * for the recursive "恢复文件夹中所有记事为正在进行" / "全部删除" actions. Follows
     * the current type filter. [folder] = null means root.
     */
    open fun getFinishedThingsInScope(folder: ThingFolder?): List<Thing> {
        if (folder != null) {
            return mFolderDao!!.getDescendantThingsForProjection(
                folder.id, Def.ThingStatus.FINISHED, getActiveTypeFilterMask()
            )
        }
        return mDao!!.getAllUserThingsByState(Thing.FINISHED).filterNotNull().filter {
            !mFolderDao!!.isEffectivelyDeleted(it.folderId) && matchesActiveTypeFilter(it.type)
        }
    }

    /** Restore the given finished Things back to underway, then reload. */
    open fun unfinishThings(things: List<Thing>): Int {
        return changeFolderSubtreeContentState(things, Thing.FINISHED, Thing.UNDERWAY)
    }

    /**
     * Move the given finished Things to the recycle bin (finished -> deleted),
     * recording each one's pre-trash state, then reload. Used by the recursive
     * "全部删除" toolbar action in the Finished view.
     */
    open fun trashThings(things: List<Thing>): Int {
        return changeFolderSubtreeContentState(things, Thing.FINISHED, Thing.DELETED)
    }

    /**
     * Every non-deleted Thing (own state underway or finished) in [folder]'s subtree
     * that matches the active type filter, for the "删除文件夹中所有记事" action. In the
     * pure-skeleton model, "deleting a folder" trashes its content rather than the
     * container. Follows the current type filter. [folder] = null returns empty.
     */
    open fun getNonDeletedThingsInScope(folder: ThingFolder?): List<Thing> {
        folder ?: return emptyList()
        val mask = getActiveTypeFilterMask()
        return mFolderDao!!.getDescendantThingsForProjection(
            folder.id, Def.ThingStatus.UNDERWAY, mask
        ) + mFolderDao!!.getDescendantThingsForProjection(
            folder.id, Def.ThingStatus.FINISHED, mask
        )
    }

    /**
     * Move the given non-deleted Things to the recycle bin, recording each one's
     * pre-trash state. Splits by current state so underway and finished Things keep
     * the correct state to return to on restore. Then reload.
     */
    open fun trashThingsPreservingState(things: List<Thing>): Int {
        if (things.isEmpty()) return 0
        val underway = things.filter { it.state == Thing.UNDERWAY }
        val finished = things.filter { it.state == Thing.FINISHED }
        changeFolderSubtreeContentState(underway, Thing.UNDERWAY, Thing.DELETED)
        changeFolderSubtreeContentState(finished, Thing.FINISHED, Thing.DELETED)
        return things.size
    }

    /**
     * Permanently delete the given trashed Things (deleted -> deleted forever),
     * then reload. Used by the recursive "全部永久删除" toolbar action in the
     * recycle bin.
     */
    open fun deleteThingsForever(things: List<Thing>): Int {
        return changeFolderSubtreeContentState(things, Thing.DELETED, Thing.DELETED_FOREVER)
    }

    /**
     * Trashed Things (own state DELETED) in the given folder's subtree that match
     * the active type filter, for the recycle-bin recursive "恢复文件夹中所有记事" /
     * "全部永久删除" actions. Follows the current type filter. [folder] = null means
     * the whole tree from root.
     */
    open fun getTrashedThingsInScope(folder: ThingFolder?): List<Thing> {
        if (folder != null) {
            return mFolderDao!!.getDescendantThingsForProjection(
                folder.id, Def.ThingStatus.DELETED, getActiveTypeFilterMask()
            )
        }
        return mDao!!.getAllUserThingsByState(Thing.DELETED).filterNotNull().filter {
            matchesActiveTypeFilter(it.type)
        }
    }

    /** Restore the given trashed Things to their pre-trash state, then reload. */
    open fun restoreTrashedThings(things: List<Thing>): Int {
        if (things.isEmpty()) return 0
        restoreThingsToPreTrashState(things)
        loadThings()
        return things.size
    }

    private fun changeFolderSubtreeContentState(
        things: List<Thing>,
        @Thing.State stateBefore: Int,
        @Thing.State stateAfter: Int
    ): Int {
        if (things.isEmpty()) return 0

        // 兜底：Doing 记事永不被任何成批 / 范围状态变更触及（与调用点的扣除互为双保险）。
        val doingId = App.getDoingThingId()
        val effectiveThings = if (doingId != -1L) things.filterNot { it.id == doingId } else things
        if (effectiveThings.isEmpty()) return 0

        val ongoingKey = Def.Meta.KEY_ONGOING_THING_ID
        val curOngoingId = FrequentSettings.getLong(ongoingKey)
        var shouldCancelOngoing = false
        val clonedThings: MutableList<Thing?> = ArrayList()
        for (thing in effectiveThings) {
            if (thing.id == curOngoingId) shouldCancelOngoing = true
            clonedThings.add(Thing.getSameCheckStateThing(thing, stateBefore, stateAfter))
        }
        if (shouldCancelOngoing) {
            SystemNotificationUtil.cancelThingOngoingNotification(mContext, curOngoingId)
            mContext!!.getSharedPreferences(Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putLong(ongoingKey, -1L).apply()
            FrequentSettings.put(ongoingKey, -1L)
        }

        val stateChangeLocations = getLocationsForStateChange(effectiveThings)
        mDao!!.updateStates(clonedThings, stateChangeLocations, stateBefore, stateAfter, false)

        val rDao = ReminderDAO.getInstance(mContext)!!
        val updateCounts = SparseIntArray()
        val habitIds = ArrayList<Long>()
        val goals = ArrayList<Reminder?>()
        for (thing in effectiveThings) {
            val type = thing.type
            if (type == Thing.HABIT) {
                habitIds.add(thing.id)
            } else if (type == Thing.GOAL && stateAfter == Thing.UNDERWAY) {
                goals.add(rDao.getReminderById(thing.id))
            }
            if (stateAfter != Thing.UNDERWAY) {
                SystemNotificationUtil.cancelNotification(thing.id, type, mContext)
            }
            updateCounts.put(type, updateCounts.get(type) + 1)
        }
        val countsSize = updateCounts.size()
        for (i in 0 until countsSize) {
            val t = updateCounts.keyAt(i)
            val c = updateCounts.valueAt(i)
            mThingsCounts!!.handleUpdate(t, stateBefore, t, stateAfter, c)
        }

        val habitDAO = HabitDAO.getInstance(mContext)!!
        val curTime = System.currentTimeMillis()
        for (goal in goals) {
            rDao.resetGoal(goal)
        }
        if (stateAfter == Thing.UNDERWAY) {
            for (habitId in habitIds) {
                habitDAO.updateHabitToLatest(habitId, true, true)
                habitDAO.addHabitIntervalInfo(habitId, "$curTime;")
            }
        } else {
            for (habitId in habitIds) {
                if (habitDAO.isPaused(habitId)) {
                    habitDAO.addHabitIntervalInfo(habitId, "$curTime;")
                }
            }
        }

        loadThings()
        return effectiveThings.size
    }

    open fun toggleFolderSticky(folder: ThingFolder?): Boolean {
        if (folder == null) return false
        val newLocation = mFolderDao!!.getFirstChildLocation(
            folder.parentFolderId,
            !folder.isSticky()
        )
        folder.location = newLocation
        mFolderDao!!.updateLocations(arrayOf<Long?>(folder.id), arrayOf<Long?>(newLocation))
        loadThings()
        return true
    }

    open fun getLocationForStateChange(thing: Thing?): Long {
        thing ?: return mDao!!.getMaxThingLocation()
        if (thing.location >= 0) return thing.location
        return mFolderDao!!.getFirstChildLocation(thing.folderId, sticky = true)
    }

    private fun getLocationsForStateChange(things: List<Thing?>): List<Long?> {
        val nextStickyLocations = HashMap<Long?, Long>()
        val locations = ArrayList<Long?>(things.size)
        for (thing in things) {
            if (thing == null || thing.location >= 0) {
                locations.add(thing?.location)
                continue
            }
            val parentFolderId = thing.folderId
            val location = nextStickyLocations.getOrPut(parentFolderId) {
                mFolderDao!!.getFirstChildLocation(parentFolderId, sticky = true)
            }
            locations.add(location)
            nextStickyLocations[parentFolderId] = location - 1
        }
        return locations
    }

    private fun getMinCurrentEntryLocation(): Long {
        val entries = mThingListEntries ?: return mDao!!.getMinThingLocation()
        var minLocation = Long.MAX_VALUE
        for (entry in entries) {
            if (entry.location < minLocation) minLocation = entry.location
        }
        return if (minLocation == Long.MAX_VALUE) mDao!!.getMinThingLocation() else minLocation
    }

    private fun getMaxCurrentEntryLocation(): Long {
        val entries = mThingListEntries ?: return mDao!!.getMaxThingLocation()
        var maxLocation = Long.MIN_VALUE
        for (entry in entries) {
            if (entry.location > maxLocation) maxLocation = entry.location
        }
        return if (maxLocation == Long.MIN_VALUE) mDao!!.getMaxThingLocation() else maxLocation
    }

    /**
     * update locations of [mThings] according to Reminder and Habits' alarms time.
     * This method will put the thing that is related to most urgent alarm in front of things list.
     * created on 2016/10/10
     */
    open fun updateLocationsByAlarmTime() {
        Collections.sort(mThings!!, ThingsSorter.getThingComparatorByAlarmTime(false))
        val size: Int = mThings!!.size
        val ids: Array<Long?> = arrayOfNulls(size)
        val locationsList: MutableList<Long?> = ArrayList(size)
        for (i in 0 until size) {
            val thing: Thing = mThings!![i]!!
            ids[i] = thing.id
            locationsList.add(thing.location)
        }

        Collections.sort(locationsList,
            Comparator<Long?> { l1, l2 -> ThingsSorter.compareByLocationAndSticky(l1!!, l2!!) })
        // if there are sticky things, locations[0] will be <0 while first thing is header, so
        // I should assign max location to header by myself
        var maxLocation: Long = Long.MIN_VALUE
        var maxPos: Int = -1
        for (i in 0 until size) {
            val location: Long = locationsList[i]!!
            if (location > maxLocation) {
                maxLocation = location
                maxPos = i
            }
        }
        locationsList.removeAt(maxPos)
        locationsList.add(0, maxLocation)

        for (i in 0 until size) {
            mThings!![i]!!.location = locationsList[i]!!
        }
        mExecutor!!.execute {
            val locationsArray: Array<Long?> = locationsList.toTypedArray()
            mDao!!.updateLocations(ids, locationsArray)
        }
    }

    /**
     * @return if [mThings] is "empty" for user-created things now.
     */
    open fun isThingsEmpty(): Boolean {
        val things = mThings ?: return true
        for (thing in things) {
            if (thing != null && thing.type != Thing.HEADER && Thing.isVisibleListThingType(thing.type)) {
                return false
            }
        }
        return true
    }

    open fun isProjectionEmptyForHomeState(): Boolean {
        return !hasVisibleProjectionContent()
    }

    open fun hasVisibleProjectionContent(): Boolean {
        val entries = mThingListEntries ?: return false
        for (entry in entries) {
            if (entryHasVisibleProjectionContent(entry)) return true
        }
        return false
    }

    open fun getSelectedThings(): Array<Thing?>? {
        val selectedThings: MutableList<Thing?> = ArrayList()
        for (thing in mThings!!) {
            if (thing!!.isSelected()) {
                selectedThings.add(thing)
            }
        }
        return selectedThings.toTypedArray()
    }

    open fun getSelectedFolders(): Array<ThingFolder> {
        val selectedFolders: MutableList<ThingFolder> = ArrayList()
        val entries = mThingListEntries ?: return emptyArray()
        for (entry in entries) {
            if (entry is ThingListEntry.FolderEntry && entry.folder.isSelected()) {
                selectedFolders.add(entry.folder)
            }
        }
        return selectedFolders.toTypedArray()
    }

    open fun getSingleSelectedEntry(): ThingListEntry? {
        if (getSelectedCount() != 1) return null
        val entries = mThingListEntries
        if (entries != null) {
            for (entry in entries) {
                if (entry is ThingListEntry.ThingEntry && entry.thing.isSelected()) {
                    return entry
                }
                if (entry is ThingListEntry.FolderEntry && entry.folder.isSelected()) {
                    return entry
                }
            }
        } else {
            for (thing in mThings!!) {
                if (thing!!.isSelected()) return ThingListEntry.ThingEntry(thing)
            }
        }
        return null
    }

    open fun setSelectedTo(selected: Boolean) {
        val entries = mThingListEntries
        if (entries != null) {
            for (entry in entries) {
                when (entry) {
                    is ThingListEntry.ThingEntry -> {
                        if (isSelectableThing(entry.thing)) {
                            entry.thing.selected = selected
                        }
                    }
                    is ThingListEntry.FolderEntry -> entry.folder.selected = selected
                }
            }
            return
        }

        for (i in 1 until mThings!!.size) {
            val thing = mThings!![i]!!
            if (isSelectableThing(thing)) {
                thing.selected = selected
            }
        }
    }

    open fun getSelectedCount(): Int {
        var count = 0
        for (thing in mThings!!) {
            if (thing!!.isSelected()) count++
        }
        val entries = mThingListEntries
        if (entries != null) {
            for (entry in entries) {
                if (entry is ThingListEntry.FolderEntry && entry.folder.isSelected()) count++
            }
        }
        return count
    }

    open fun getSelectedThingCount(): Int {
        var count = 0
        for (thing in mThings!!) {
            if (thing!!.isSelected()) count++
        }
        return count
    }

    open fun getSelectedFolderCount(): Int {
        var count = 0
        val entries = mThingListEntries ?: return 0
        for (entry in entries) {
            if (entry is ThingListEntry.FolderEntry && entry.folder.isSelected()) count++
        }
        return count
    }

    open fun getSelectableEntryCount(): Int {
        val entries = mThingListEntries
        if (entries != null) {
            var count = 0
            for (entry in entries) {
                if (entry is ThingListEntry.FolderEntry) {
                    count++
                } else if (entry is ThingListEntry.ThingEntry &&
                    isSelectableThing(entry.thing)
                ) {
                    count++
                }
            }
            return count
        }
        var count = 0
        for (i in 1 until mThings!!.size) {
            if (isSelectableThing(mThings!![i])) count++
        }
        return count
    }

    open fun setListEntrySelected(position: Int, selected: Boolean) {
        val entry = getThingListEntry(position) ?: return
        when (entry) {
            is ThingListEntry.ThingEntry -> {
                if (isSelectableThing(entry.thing)) {
                    entry.thing.selected = selected
                }
            }
            is ThingListEntry.FolderEntry -> entry.folder.selected = selected
        }
    }

    open fun toggleListEntrySelected(position: Int) {
        val entry = getThingListEntry(position) ?: return
        when (entry) {
            is ThingListEntry.ThingEntry -> {
                if (isSelectableThing(entry.thing)) {
                    entry.thing.selected = !entry.thing.isSelected()
                }
            }
            is ThingListEntry.FolderEntry -> {
                entry.folder.selected = !entry.folder.isSelected()
            }
        }
    }

    private fun isSelectableThing(thing: Thing?): Boolean {
        if (thing == null) return false
        if (thing.type == Thing.HEADER) return false
        return Thing.isVisibleListThingType(thing.type)
    }

    open fun getThingById(id: Long): Thing? {
        for (thing in mThings!!) {
            if (thing!!.id == id) {
                return thing
            }
        }
        return null
    }

    open fun getFolderById(id: Long): ThingFolder? {
        return mFolderDao!!.getFolderById(id)
    }

    open fun getPosition(id: Long): Int {
        val size: Int = mThings!!.size
        for (i in 0 until size) {
            if (mThings!![i]!!.id == id) {
                return i
            }
        }
        return -1
    }

    private fun updateHeader(addSize: Int) {
        mHeaderId += addSize
        val header: Thing? = mThings!![0]
        if (header != null && header.type == Thing.HEADER) {
            header.id = mHeaderId
            header.location = mDao!!.getMaxThingLocation() + addSize
        }
    }

    open fun getSingleSelectedPosition(): Int {
        val size: Int = mThings!!.size
        for (i in 0 until size) {
            if (mThings!![i]!!.isSelected()) {
                return i
            }
        }
        return -1
    }

    open fun stickyThingOnTop(thing: Thing?, position: Int) {
        if (thing == null) return

        val newLocation = mFolderDao!!.getFirstChildLocation(thing.folderId, sticky = true)
        val ids: Array<Long?> = arrayOfNulls(1)
        ids[0] = thing.id
        val locations: Array<Long?> = arrayOfNulls(1)
        locations[0] = newLocation

        thing.location = newLocation

        mDao!!.updateLocations(ids, locations)
        rebuildThingListEntries()
    }

    open fun cancelStickyThing(thing: Thing?, position: Int) {
        if (thing == null) return

        val newLocation = mFolderDao!!.getFirstChildLocation(thing.folderId, sticky = false)
        val ids: Array<Long?> = arrayOfNulls(1)
        ids[0] = thing.id
        val locations: Array<Long?> = arrayOfNulls(1)
        locations[0] = newLocation

        thing.location = newLocation

        mDao!!.updateLocations(ids, locations)
        rebuildThingListEntries()
    }

    open fun getPositionToInsertNewThing(): Int {
        val size: Int = mThings!!.size
        for (i in 1 until size) {
            if (mThings!![i]!!.location >= 0) {
                return i
            }
        }
        return size
    }

    companion object {
        const val TAG: String = "ThingManager"

        @JvmField
        var sThingManager: ThingManager? = null

        @JvmStatic
        fun isTotallyInitialized(): Boolean {
            if (sThingManager != null) {
                if (sThingManager!!.mThings != null) {
                    if (sThingManager!!.mThings!!.size > 1) {
                        return true
                    }
                }
            }
            return false
        }

        @JvmStatic
        fun getInstance(context: Context?): ThingManager? {
            if (sThingManager == null) {
                synchronized(ThingManager::class.java) {
                    if (sThingManager == null) {
                        sThingManager = ThingManager(context!!.applicationContext)
                    }
                }
            }
            return sThingManager
        }
    }
}
