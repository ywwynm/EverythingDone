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
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingFolderCardPresentation
import com.ywwynm.everythingdone.model.ThingListEntry
import com.ywwynm.everythingdone.model.ThingListProjection
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
     * The limit for getting and controlling things from/in database.
     * Also means current state of UI in Activities.
     *
     * Should be one of:
     * [Def.LimitForGettingThings.ALL_UNDERWAY]
     * [Def.LimitForGettingThings.NOTE_UNDERWAY]
     * [Def.LimitForGettingThings.REMINDER_UNDERWAY]
     * [Def.LimitForGettingThings.HABIT_UNDERWAY]
     * [Def.LimitForGettingThings.GOAL_UNDERWAY]
     * [Def.LimitForGettingThings.ALL_FINISHED]
     * [Def.LimitForGettingThings.ALL_DELETED]
     */
    private var mLimit: Int = 0
    private var mProjection: ThingListProjection =
        ThingListProjection.root(Def.LimitForGettingThings.ALL_UNDERWAY)

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
        setLimit(Def.LimitForGettingThings.ALL_UNDERWAY, true)

        mHeaderId = mThings!![0]!!.id
    }

    open fun setLimit(limit: Int, loadThingsNow: Boolean) {
        mLimit = limit
        mProjection = mProjection.withLimit(limit)
        mAuthenticatedPrivateFolderIds.clear()
        mDao!!.setLimit(limit)
        if (loadThingsNow) {
            loadThings()
        }
    }

    open fun getUndoGoals(): MutableList<Reminder?>? {
        return mUndoGoals
    }

    open fun loadThings() {
        mThings = getThingsForCurrentProjection(null, 0).toMutableList()
        rebuildThingListEntries()

        // do self-check to prevent wrong display for normal and empty states.
        val size: Int = mThings!!.size
        val hasFolderEntries = hasFolderEntries()
        if (size == 1 && !hasFolderEntries) {
            create(Thing.generateNotifyEmpty(mLimit, getHeaderId(), mContext), false, true)
            rebuildThingListEntries()
        } else if (size > 2 || size == 2 && hasFolderEntries) {
            var pos: Int = -1
            var notifyEmpty: Thing? = null
            for (i in 1 until size) {
                val thing: Thing = mThings!![i]!!
                if (thing.type >= Thing.NOTIFY_EMPTY_UNDERWAY) {
                    pos = i
                    notifyEmpty = thing
                    break
                }
            }
            if (pos != -1) {
                updateState(notifyEmpty, pos, -1, Thing.UNDERWAY, Thing.DELETED_FOREVER, false, false)
                rebuildThingListEntries()
            }
        }
    }

    private fun getThingsForCurrentProjection(keyword: String?, color: Int): List<Thing?> {
        val currentFolderId = mProjection.currentFolderId
        if (mLimit == Def.LimitForGettingThings.ALL_DELETED
            && mFolderDao!!.isEffectivelyDeleted(currentFolderId)
        ) {
            return mDao!!.getThingsForEffectiveDeletedFolderProjection(
                currentFolderId,
                keyword,
                color
            )
        }
        return mDao!!.getThingsForProjection(
            mLimit,
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

    open fun hasFolderEntries(): Boolean {
        val entries = mThingListEntries ?: return false
        for (entry in entries) {
            if (entry is ThingListEntry.FolderEntry) return true
        }
        return false
    }

    open fun getVisibleChildCountForActivityHeader(): Int {
        val entries = mThingListEntries ?: return 0
        var count = 0
        for (entry in entries) {
            if (entry is ThingListEntry.ThingEntry) {
                val thing = entry.thing
                if (thing.type != Thing.HEADER && thing.type < Thing.NOTIFY_EMPTY_UNDERWAY) {
                    count++
                }
            } else if (entry is ThingListEntry.FolderEntry) {
                count++
            }
        }
        return count
    }

    open fun getProjection(): ThingListProjection {
        return mProjection
    }

    open fun getCurrentFolderPath(): List<ThingFolder> {
        return mFolderDao!!.getFolderPath(mProjection.currentFolderId)
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

    open fun openFolder(folderId: Long, authenticated: Boolean = false) {
        if (authenticated) {
            mAuthenticatedPrivateFolderIds.add(folderId)
        }
        mProjection = mProjection.openFolder(folderId)
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
        mixed.addAll(
            mFolderDao!!.getFolderEntriesForProjection(
                mLimit,
                mProjection.currentFolderId,
                keyword,
                color
            )
        )
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

    /**
     * Create a new thing.
     *
     * @param thingToCreate the thing to create.
     * @param handleNotifyEmpty whether we should handle deletion/creation of NOTIFY_EMPTYs.
     * @return `true` if we found a need-to-delete NOTIFY_EMPTY under current
     *          limit([mLimit]) and we deleted it indeed, which means
     *          [com.ywwynm.everythingdone.activities.ThingsActivity] need to call
     *          [com.ywwynm.everythingdone.adapters.ThingsAdapter.notifyItemChanged].
     *          `false` otherwise and should call ThingsAdapter#notifyItemInserted(1).
     */
    open fun create(thingToCreate: Thing?, handleNotifyEmpty: Boolean, addToThingsNow: Boolean): Boolean {
        // create in database at first
        thingToCreate!!.id = mHeaderId
        if (thingToCreate.type in Thing.NOTE..Thing.GOAL && thingToCreate.folderId == null) {
            thingToCreate.folderId = mProjection.currentFolderId
        }
        mDao!!.create(thingToCreate, true, false)
//        mExecutor.execute(new Runnable() {
//            @Override
//            public void run() {
//                mDao.create(thingToCreate, true, false);
//            }
//        });

        updateHeader(1)

        // see if we can delete a NOTIFY_EMPTY
        var deletedNEnow = false
        val type: Int = thingToCreate.type
        if (handleNotifyEmpty && !App.isSearching) {
            deletedNEnow = deleteNEnow(type, Thing.UNDERWAY)
        }

        if (addToThingsNow) {
            mThings!!.add(getPositionToInsertNewThing(), thingToCreate)
            rebuildThingListEntries()
        }

        if (type >= Thing.NOTE && type <= Thing.GOAL) {
            AutoNotifyHelper.createAutoNotify(thingToCreate, mContext)
        }

        mThingsCounts!!.handleCreation(type)

        return deletedNEnow
    }

    /**
     * This method will be called to update a [Thing]'s content(including [Thing.type],
     * [Thing.title], [Thing.content] and so on. NOT including [Thing.state]).
     * And this method will be only called when `updatedThing` is in [mThings].
     *
     * @param typeBefore old type of `updatedThing`
     * @param updatedThing thing whose content is updated.
     * @param position position `updatedThing`'s position in [mThings].
     * @param handleNotifyEmpty whether we should handle deletion/creation of NOTIFY_EMPTYs.
     * @return 0 if update really happens and [com.ywwynm.everythingdone.activities.ThingsActivity]
     *         should call [com.ywwynm.everythingdone.adapters.ThingsAdapter.notifyItemChanged].
     *
     *         1 if we updated a thing to a new type and created a NOTIFY_EMPTY for current limit
     *         ([mLimit]) so that ThingsActivity should call ThingsAdapter#notifyItemChanged(1).
     *
     *         2 if we updated a thing to a new type but didn't create a NOTIFY_EMPTY for current limit.
     *         In this situation, ThingsActivity should call ThingsAdapter#notifyItemRemoved(`position`).
     */
    open fun update(@Thing.Type typeBefore: Int, updatedThing: Thing?, position: Int,
                    handleNotifyEmpty: Boolean): Int {
        if (handleNotifyEmpty &&
                willCreateNEforOtherLimit(
                        updatedThing!!.id, typeBefore, updatedThing.state, false)) {
            updateHeader(1)
        }

        mExecutor!!.execute {
            mDao!!.update(typeBefore, updatedThing, true, false)
        }

        val state: Int     = updatedThing!!.state
        val typeAfter: Int = updatedThing.type
        mThingsCounts!!.handleUpdate(typeBefore, state, typeAfter, state, 1)

        if (handleNotifyEmpty && !App.isSearching) {
            deleteNEnow(typeAfter, state)
        }

        if (mLimit == Def.LimitForGettingThings.ALL_UNDERWAY ||
                Thing.sameType(typeBefore, typeAfter)) {
            // will not generate NOTIFY_EMPTY
            rebuildThingListEntries()
            return 0
        } else {
            mThings!!.removeAt(position)

            var createdNEnow = false
            if (handleNotifyEmpty) {
                createdNEnow = createNEnow(typeBefore, state, !App.isSearching)
            }

            rebuildThingListEntries()
            return if (createdNEnow) 1 else 2
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

        if (handleNotifyEmpty &&
                willCreateNEforOtherLimit(thingId, thingType, stateBefore, true)) {
            updateHeader(1)
        }

        val thingToUpdate = if (!toUndo) {
            Thing.getSameCheckStateThing(thing, stateBefore, stateAfter)
        } else thing

        mExecutor!!.execute {
            mDao!!.updateState(thingToUpdate, location, stateBefore, stateAfter,
                    handleNotifyEmpty, false, toUndo, true)
        }

        var deletedNEnow = false
        if (handleNotifyEmpty && !App.isSearching) {
            deletedNEnow = deleteNEnow(thingType, stateAfter)
        }

        if (toUndo) {
            mThings!!.add(position, thing)
        } else {
            // used to make sure that updated thing is at first location except header
            if (stateAfter != Thing.DELETED_FOREVER) {
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

        var createdNEnow = false
        if (handleNotifyEmpty) {
            createdNEnow = createNEnow(thingType, stateBefore, !App.isSearching)
        }
        rebuildThingListEntries()
        return deletedNEnow || createdNEnow
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

        mExecutor!!.execute {
            mDao!!.updateStates(clonedThings, null, stateBefore, stateAfter, false)
        }

        // things.get(0).getType() will lead us to current limit.
        var type: Int = things[0]!!.type
        if (!App.isSearching) {
            deleteNEnow(type, stateAfter)
        }

        /*
            We don't know how many NEs will be created then so we directly assume that the
            number is 6(except for current limit, which will be handled at last). As a result,
            we should update header id to be size+6.
            This is stupid but it's the only way to do the stuff. QAQ.
         */
        val size: Int = things.size
        updateHeader(size + 6)

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

        createNEnow(type, stateBefore, !App.isSearching)

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

        updateHeader(6)

        var type: Int = things[0]!!.type
        if (!App.isSearching) {
            deleteNEnow(type, stateAfter)
        }

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

        createNEnow(type, stateBefore, !App.isSearching)
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
        val folder = ThingFolder(
            id = folderId,
            parentFolderId = mProjection.currentFolderId,
            title = title,
            state = Thing.UNDERWAY,
            color = folderBackground.representativeColor(),
            location = second.location,
            isPrivate = false,
            createTime = now,
            updateTime = now
        )
        folder.setBackground(folderBackground)

        mFolderDao!!.create(folder)
        mDao!!.updateHeader(1)
        updateHeader(1)

        moveThingIntoFolder(first, folder.id, false)
        moveThingIntoFolder(second, folder.id, false)
        loadThings()
        return folder
    }

    private fun canCreateFolderMember(thing: Thing?): Boolean {
        if (thing == null) return false
        if (thing.type !in Thing.NOTE..Thing.GOAL) return false
        return thing.state == Thing.UNDERWAY
    }

    open fun moveThingIntoFolder(thing: Thing?, folderId: Long?, reload: Boolean = true) {
        if (thing == null || thing.type == Thing.HEADER) return
        thing.folderId = folderId
        mDao!!.updateFolderId(thing.id, folderId)
        if (reload) {
            loadThings()
        }
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
        for (thing in selectedThings) {
            if (!canMoveThingToFolder(thing)) continue
            if (thing!!.folderId == folderId) {
                thing.selected = false
                continue
            }
            thing.folderId = folderId
            thing.selected = false
            mDao!!.updateFolderId(thing.id, folderId)
            changed = true
        }
        if (changed) {
            loadThings()
        }
        return changed
    }

    private fun canMoveThingToFolder(thing: Thing?): Boolean {
        if (thing == null) return false
        if (thing.type !in Thing.NOTE..Thing.GOAL) return false
        if (thing.state != Thing.UNDERWAY) return false
        return thing.id != App.getDoingThingId()
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

    open fun moveFolderIntoFolder(folder: ThingFolder?, parentFolderId: Long?): Boolean {
        if (folder == null) return false
        if (parentFolderId == folder.id) return false
        if (mFolderDao!!.isDescendantOf(parentFolderId, folder.id)) return false
        folder.parentFolderId = parentFolderId
        mFolderDao!!.updateParent(folder.id, parentFolderId)
        loadThings()
        return true
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

    open fun updateFolderCardPresentation(
        folder: ThingFolder?,
        presentation: ThingFolderCardPresentation?
    ): Boolean {
        if (folder == null || presentation == null) return false
        folder.cardPresentation = presentation
        mFolderDao!!.updateCardPresentation(folder.id, presentation)
        loadThings()
        return true
    }

    open fun updateFolderPrivate(folder: ThingFolder?, isPrivate: Boolean): Boolean {
        if (folder == null) return false
        folder.isPrivate = isPrivate
        if (!isPrivate) {
            mAuthenticatedPrivateFolderIds.remove(folder.id)
        }
        mFolderDao!!.updatePrivate(folder.id, isPrivate)
        loadThings()
        return true
    }

    open fun deleteFolder(folder: ThingFolder?): Boolean {
        return updateFolderState(folder, Thing.DELETED)
    }

    open fun restoreFolder(folder: ThingFolder?): Boolean {
        return updateFolderState(folder, Thing.UNDERWAY)
    }

    private fun updateFolderState(folder: ThingFolder?, @Thing.State state: Int): Boolean {
        if (folder == null) return false
        folder.state = state
        mFolderDao!!.updateState(folder.id, state)
        loadThings()
        return true
    }

    open fun deleteFolderForever(folder: ThingFolder?): Boolean {
        if (folder == null) return false
        mFolderDao!!.deleteForever(folder.id)
        loadThings()
        return true
    }

    open fun toggleFolderSticky(folder: ThingFolder?): Boolean {
        if (folder == null) return false
        val newLocation = if (folder.isSticky()) {
            getMaxCurrentEntryLocation() + 1
        } else {
            getMinCurrentEntryLocation() - 1
        }
        folder.location = newLocation
        mFolderDao!!.updateLocations(arrayOf<Long?>(folder.id), arrayOf<Long?>(newLocation))
        loadThings()
        return true
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

    // added in thought of reusing code for shortcut action "checking upcoming thing" on 2016/10/22

    /**
     * This method will be only called when a thing with `type` and `state`
     * has been "deleted", which can occur when creating and updating.
     */
    private fun createNEnow(@Thing.Type type: Int, @Thing.State state: Int, addToThingsNow: Boolean): Boolean {
        val limits: IntArray = Thing.getLimits(type, state)
        for (limit in limits) {
            if (mLimit == limit) {
                if (mThings!!.size == 1) {
                    val notifyEmpty: Thing? = Thing.generateNotifyEmpty(limit, mHeaderId, mContext)
                    create(notifyEmpty, false, addToThingsNow)
                    return true
                }
            }
        }
        return false
    }

    private fun willCreateNEforOtherLimit(id: Long, @Thing.Type type: Int, @Thing.State state: Int,
                                          updateState: Boolean): Boolean {
        val limits: IntArray = Thing.getLimits(type, state)
        for (limit in limits) {
            if (mLimit != limit) {
                if (updateState || limit != Def.LimitForGettingThings.ALL_UNDERWAY) {
                    val cursor: Cursor = mDao!!.getThingsCursorForDisplay(limit, null, 0)
                    var id1: Long = -1
                    var count = 0
                    while (cursor.moveToNext()) {
                        count++
                        id1 = cursor.getLong(0)
                        if (count > 2) {
                            break
                        }
                    }
                    cursor.close()
                    if (count == 2 && id == id1) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * This method will be only called when a thing with `type` and `state`
     * has been "created", which can occur when creating and updating.
     */
    private fun deleteNEnow(@Thing.Type type: Int, @Thing.State state: Int): Boolean {
        val limits: IntArray = Thing.getLimits(type, state)
        for (limit in limits) {
            if (mLimit == limit) {
                val thing: Thing = mThings!![1]!!
                val NEtype: Int = thing.type
                if (NEtype >= Thing.NOTIFY_EMPTY_UNDERWAY) {
                    updateState(thing, 1, -1, Thing.UNDERWAY, Thing.DELETED_FOREVER, false, false)
                    return true
                }
            }
        }
        return false
    }

    /**
     * @return if [mThings] is "empty" for user-created things now.
     */
    open fun isThingsEmpty(): Boolean {
        return mThings!!.size < 2 || mThings!![1]!!.type >= Thing.NOTIFY_EMPTY_UNDERWAY
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

    open fun setSelectedTo(selected: Boolean) {
        val size: Int = mThings!!.size
        for (i in 1 until size) {
            mThings!![i]!!.selected = selected
        }
    }

    open fun getSelectedCount(): Int {
        var count = 0
        for (thing in mThings!!) {
            if (thing!!.isSelected()) count++
        }
        return count
    }

    open fun getThingById(id: Long): Thing? {
        for (thing in mThings!!) {
            if (thing!!.id == id) {
                return thing
            }
        }
        return null
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

        val minLocation: Long = mDao!!.getMinThingLocation()
        val newLocation = if (minLocation >= 0) {
            -1
        } else {
            minLocation - 1
        }
        val ids: Array<Long?> = arrayOfNulls(1)
        ids[0] = thing.id
        val locations: Array<Long?> = arrayOfNulls(1)
        locations[0] = newLocation

        thing.location = newLocation

        mDao!!.updateLocations(ids, locations)
        if (position >= 0 && position < mThings!!.size) {
            mThings!!.removeAt(position)
            mThings!!.add(1, thing)
        }
    }

    open fun cancelStickyThing(thing: Thing?, position: Int) {
        if (thing == null) return

        val maxLocation: Long = mDao!!.getMaxThingLocation() // this will always be >0
        updateHeader(2)
        mDao!!.updateHeader(2)
        val newLocation: Long = maxLocation + 1
        val ids: Array<Long?> = arrayOfNulls(1)
        ids[0] = thing.id
        val locations: Array<Long?> = arrayOfNulls(1)
        locations[0] = newLocation

        thing.location = newLocation

        mDao!!.updateLocations(ids, locations)
        if (position >= 0 && position < mThings!!.size) {
            mThings!!.removeAt(position)
            mThings!!.add(getPositionToInsertNewThing(), thing)
        }
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
