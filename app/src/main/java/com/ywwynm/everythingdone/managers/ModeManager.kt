package com.ywwynm.everythingdone.managers

import android.animation.ObjectAnimator
import androidx.drawerlayout.widget.DrawerLayout
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.widget.Toolbar.OnMenuItemClickListener
import android.view.MenuItem
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.RelativeLayout

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.ThingsAdapter
import com.ywwynm.everythingdone.helpers.HomeActionWordingHelper
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingListEntry
import com.ywwynm.everythingdone.views.ActivityHeader
import com.ywwynm.everythingdone.views.FloatingActionButton

/**
 * Created by ywwynm on 2015/7/17.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A manager class for changing mode in ThingsActivity
 */
open class ModeManager(app: App?,
                       drawerLayout: DrawerLayout?,
                       fab: FloatingActionButton?, header: ActivityHeader?,
                       rlContextualToolbar: RelativeLayout?, toolbar: Toolbar?,
                       nListener: View.OnClickListener?,
                       listener: OnMenuItemClickListener?,
                       recyclerView: RecyclerView?, adapter: ThingsAdapter?) {

    private var beforeMode: Int = NORMAL
    private var currentMode: Int = NORMAL

    private var mApp: App? = app
    private var mThingManager: ThingManager? = ThingManager.getInstance(app)

    //private WeakReference<DrawerLayout> mWrDrawerLayout;
    private var mDrawerLayout: DrawerLayout? = drawerLayout

    private var mFab: FloatingActionButton? = fab
    //private WeakReference<FloatingActionButton> mWrFab;

    private var mHeader: ActivityHeader? = header
    //private WeakReference<ActivityHeader> mWrActivityHeader;

    private var mRlContextualToolbar: RelativeLayout? = rlContextualToolbar
    //private WeakReference<RelativeLayout> mWrRlContextualToolbar;

    private var mContextualToolbar: Toolbar? = toolbar
    //private WeakReference<Toolbar> mWrContextualToolbar;

    private var mNavigationListener: View.OnClickListener? = nListener
    //private WeakReference<View.OnClickListener> mWrNavigationIconListener;

    private var mContextualListener: OnMenuItemClickListener? = listener
    //private WeakReference<Toolbar.OnMenuItemClickListener> mWrContextualMenuListener;

    private var showContextualToolbar: Animation? = AnimationUtils.loadAnimation(mApp,
            R.anim.contextual_toolbar_show)
    private var hideContextualToolbar: Animation? = AnimationUtils.loadAnimation(mApp,
            R.anim.contextual_toolbar_hide)

    private var mRecyclerView: RecyclerView? = recyclerView
    //private WeakReference<RecyclerView> mWrRecyclerView;

    private var mAdapter: ThingsAdapter? = adapter
    //private WeakReference<ThingsAdapter> mWrAdapter;
    private var mShouldShowPrivateContent: Boolean = false

    private var backNormalModeListener: View.OnClickListener? = null
    private var backNormalModeCallback: (() -> Unit)? = null
    private var contextualToolbarVisibilityCallback: ((Boolean) -> Unit)? = null
    private var menuItemsChangedCallback: (() -> Unit)? = null

    private var notifyDataSetRunnable: Runnable? = null
    private var hideActionBarShadowRunnable: Runnable? = null

    init {
        notifyDataSetRunnable = Runnable {
            mAdapter!!.notifyDataSetChanged()
        }

        backNormalModeListener = View.OnClickListener {
            this@ModeManager.backNormalMode(0)
        }

        hideActionBarShadowRunnable = Runnable {
            mHeader!!.hideActionbarShadow()
        }
    }

    open fun getCurrentMode(): Int {
        return currentMode
    }

    open fun toMovingMode(position: Int) {
        val entry = mThingManager!!.getThingListEntry(position)
        if (position < 0 || entry == null) {
            return
        }
        if (entry is ThingListEntry.ThingEntry && entry.thing.type == Thing.HEADER) {
            return
        }
        beforeMode = currentMode
        currentMode = MOVING
        prepareRecyclerViewForModeRebind()
        notifyThingsSelected(position)
    }

    open fun toSelectingMode(position: Int) {
        if (position < 0) {
            return
        }
        updateSelectedCount()
        showContextualToolbar(true)
        beforeMode = currentMode
        currentMode = SELECTING

        val rv: RecyclerView = mRecyclerView!!
        prepareRecyclerViewForModeRebind()
        if (beforeMode == NORMAL) {
            notifyThingsSelected(position)
        } else {
            val holder: RecyclerView.ViewHolder? = rv.findViewHolderForAdapterPosition(position)
            if (holder != null) {
                val cv: CardView = holder.itemView as CardView
                ObjectAnimator.ofFloat(
                    cv, "cardElevation",
                    mApp!!.resources.getDimension(R.dimen.thing_card_normal_elevation)
                ).setDuration(96).start()
                ObjectAnimator.ofFloat(cv, "scaleX", 1.0f).setDuration(96).start()
                ObjectAnimator.ofFloat(cv, "scaleY", 1.0f).setDuration(96).start()
            }
        }
        updateMenuItems()
    }

    private fun prepareRecyclerViewForModeRebind() {
        val rv: RecyclerView = mRecyclerView!!
        rv.itemAnimator?.endAnimations()
        (rv.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    open fun setBackNormalModeCallback(callback: (() -> Unit)?) {
        backNormalModeCallback = callback
    }

    open fun setContextualToolbarVisibilityCallback(callback: ((Boolean) -> Unit)?) {
        contextualToolbarVisibilityCallback = callback
    }

    open fun setMenuItemsChangedCallback(callback: (() -> Unit)?) {
        menuItemsChangedCallback = callback
    }

    open fun backNormalMode(position: Int) {
        backNormalModeCallback?.invoke()
        val isSearching: Boolean = App.isSearching
        if (!isSearching) {
            mDrawerLayout!!.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        }
        beforeMode = currentMode
        currentMode = NORMAL
        if (beforeMode == SELECTING) {
            hideContextualToolbar()
            val adapter: ThingsAdapter = mAdapter!!
            adapter.setShouldThingsAnimWhenAppearing(false)
            adapter.notifyDataSetChanged()
        } else {
            val holder: RecyclerView.ViewHolder? = mRecyclerView!!.findViewHolderForAdapterPosition(position)
            if (holder != null) {
                val cv: CardView = holder.itemView as CardView
                ObjectAnimator.ofFloat(
                    cv, "cardElevation",
                    mApp!!.resources.getDimension(R.dimen.thing_card_normal_elevation)
                )
                        .setDuration(96).start()
                cv.animate().scaleX(1.0f).setDuration(96)
                cv.animate().scaleY(1.0f).withEndAction(notifyDataSetRunnable).setDuration(96)
            }
        }
        if (mApp!!.getStatus() == Def.ThingStatus.UNDERWAY
                && !isSearching) {
            mFab!!.spread()
        }
        mThingManager!!.setSelectedTo(false)
        (mRecyclerView!!.itemAnimator as SimpleItemAnimator).supportsChangeAnimations = true
    }

    open fun finishMovingModeWithoutListRefresh() {
        backNormalModeCallback?.invoke()
        val isSearching: Boolean = App.isSearching
        if (!isSearching) {
            mDrawerLayout!!.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        }
        beforeMode = currentMode
        currentMode = NORMAL
        if (mApp!!.getStatus() == Def.ThingStatus.UNDERWAY && !isSearching) {
            mFab!!.spread()
        }
        mThingManager!!.setSelectedTo(false)
        (mRecyclerView!!.itemAnimator as SimpleItemAnimator).supportsChangeAnimations = true
    }

    private fun notifyThingsSelected(position: Int) {
        mFab!!.shrink()
        mThingManager!!.setListEntrySelected(position, true)
        mAdapter!!.notifyDataSetChanged()
    }

    open fun showContextualToolbar(anim: Boolean) {
        val tb: Toolbar = mContextualToolbar!!
        tb.setTitleTextAppearance(mApp!!, R.style.ContextualToolbarText)
        tb.setNavigationIcon(R.drawable.act_close)
        tb.setNavigationOnClickListener(backNormalModeListener)
        tb.setOnMenuItemClickListener(mContextualListener)
        when (mApp!!.getStatus()) {
            Def.ThingStatus.UNDERWAY ->
                tb.inflateMenu(R.menu.menu_contextual_underway)
            Def.ThingStatus.FINISHED ->
                tb.inflateMenu(R.menu.menu_contextual_finished)
            else ->
                tb.inflateMenu(R.menu.menu_contextual_deleted)
        }

        val rl: RelativeLayout = mRlContextualToolbar!!
        contextualToolbarVisibilityCallback?.invoke(true)
        rl.visibility = View.VISIBLE
        if (anim) {
            rl.setAnimation(showContextualToolbar)
            showContextualToolbar!!.startNow()
        }
        mRecyclerView!!.postDelayed(hideActionBarShadowRunnable, 200)
    }

    private fun hideContextualToolbar() {
        mHeader!!.showActionbarShadow()

        val tb: Toolbar = mContextualToolbar!!
        tb.setNavigationOnClickListener(mNavigationListener)
        tb.setOnMenuItemClickListener(null)

        val rl: RelativeLayout = mRlContextualToolbar!!
        rl.setAnimation(hideContextualToolbar)
        hideContextualToolbar!!.start()
        rl.visibility = View.INVISIBLE
        contextualToolbarVisibilityCallback?.invoke(false)
        tb.getMenu().clear()
    }

    open fun updateSelectedCount() {
        val selectedCount: Int = mThingManager!!.getSelectedCount()
        val selectableCount: Int = mThingManager!!.getSelectableEntryCount()
        val toolbar: Toolbar = mContextualToolbar!!
        toolbar.title = "$selectedCount / $selectableCount"
    }

    open fun updateMenuItems() {
        updateMenuItemSelectAll()
        updateMenuItemCustomizeCardAppearance()
        updateMenuItemPrivate()
        updateMenuItemsForFolderSelection()
        if (mApp!!.getStatus() == Def.ThingStatus.UNDERWAY) {
            updateMenuItemStickyOnTop()
        }
        menuItemsChangedCallback?.invoke()
    }

    open fun setShouldShowPrivateContent(shouldShowPrivateContent: Boolean) {
        mShouldShowPrivateContent = shouldShowPrivateContent
        updateMenuItems()
    }

    private fun updateMenuItemSelectAll() {
        val item: MenuItem = mContextualToolbar!!.getMenu().findItem(R.id.act_select_all) ?: return
        val selectableCount = mThingManager!!.getSelectableEntryCount()
        if (selectableCount > 0 && mThingManager!!.getSelectedCount() == selectableCount) {
            item.setIcon(R.drawable.act_deselect_all)
            item.setTitle(R.string.act_deselect_all)
        } else {
            item.setIcon(R.drawable.act_select_all)
            item.setTitle(R.string.act_select_all)
        }
    }

    private fun updateMenuItemStickyOnTop() {
        val item: MenuItem = mContextualToolbar!!.getMenu().findItem(R.id.act_sticky) ?: return
        val things = mThingManager!!.getSelectedThings()?.filterNotNull() ?: emptyList()
        val folders = mThingManager!!.getSelectedFolders().toList()
        if (things.isEmpty() && folders.isEmpty()) {
            item.isVisible = false
            return
        }
        item.isVisible = true
        // Smart-set direction: only flip to "cancel" when every selected item is sticky.
        val allSticky = things.all { it.location < 0 } && folders.all { it.location < 0 }
        if (allSticky) {
            item.setIcon(R.drawable.act_cancel_sticky)
        } else {
            item.setIcon(R.drawable.act_sticky_on_top)
        }
        item.title = HomeActionWordingHelper.stickyTitle(mApp!!, allSticky)
    }

    private fun updateMenuItemCustomizeCardAppearance() {
        val item: MenuItem = mContextualToolbar!!.getMenu()
                .findItem(R.id.act_customize_card_appearance) ?: return
        val entry = mThingManager!!.getSingleSelectedEntry()
        if (entry is ThingListEntry.FolderEntry) {
            val limitOk = mApp!!.getStatus() == Def.ThingStatus.UNDERWAY
            item.isVisible = limitOk
            if (limitOk) {
                item.setTitle(R.string.act_customize_folder_card_appearance)
            }
            return
        }
        item.setTitle(R.string.act_customize_card_appearance)
        item.isVisible = canCustomizeSelectedThingCardAppearance()
    }

    private fun canCustomizeSelectedThingCardAppearance(): Boolean {
        if (mThingManager!!.getSelectedCount() != 1) {
            return false
        }

        val selectedThings = mThingManager!!.getSelectedThings() ?: return false
        if (selectedThings.isEmpty()) {
            return false
        }

        val thing: Thing = selectedThings[0] ?: return false
        if (thing.id == App.getDoingThingId()) {
            return false
        }
        // Card appearance is an Underway-only editor (hidden in Finished and the
        // recycle bin, matching sticky and privacy).
        if (mApp!!.getStatus() != Def.ThingStatus.UNDERWAY) {
            return false
        }

        return true
    }

    private fun updateMenuItemPrivate() {
        val item: MenuItem = mContextualToolbar!!.getMenu()
                .findItem(R.id.act_set_as_private_thing) ?: return
        val limitIsUnderway = mApp!!.getStatus() == Def.ThingStatus.UNDERWAY
        val things = mThingManager!!.getSelectedThings()?.filterNotNull() ?: emptyList()
        val folders = mThingManager!!.getSelectedFolders().toList()
        if (!limitIsUnderway || (things.isEmpty() && folders.isEmpty())) {
            item.isVisible = false
            return
        }
        item.isVisible = true
        // Smart-set direction: only flip to "cancel" when every selected item is private.
        val allPrivate = things.all { it.isPrivate() } && folders.all { it.isPrivate }
        item.title = HomeActionWordingHelper.privateTitle(mApp!!, allPrivate)
    }

    private fun updateMenuItemsForFolderSelection() {
        val folderCount = mThingManager!!.getSelectedFolderCount()
        val thingCount = mThingManager!!.getSelectedThingCount()
        val hasFolder = folderCount > 0
        val hasThing = thingCount > 0
        val anySelected = hasFolder || hasThing
        val singleFolderOnly = folderCount == 1 && thingCount == 0
        val status = mApp!!.getStatus()
        val underway = status == Def.ThingStatus.UNDERWAY
        val deleted = status == Def.ThingStatus.DELETED

        // Unified state verbs cover Things, Folders, and mixed selections. The
        // label adapts to composition; the handler maps each member to its own
        // type's operation (Thing state change vs Folder content op).
        setStateVerb(
            R.id.act_finish_selected, anySelected, selectionTarget(hasThing, hasFolder),
            status, Thing.FINISHED
        )
        setStateVerb(
            R.id.act_delete_selected, anySelected, selectionTarget(hasThing, hasFolder),
            status, Thing.DELETED
        )
        setStateVerb(
            R.id.act_restore_selected, anySelected, selectionTarget(hasThing, hasFolder),
            status, Thing.UNDERWAY
        )
        setStateVerb(
            R.id.act_delete_selected_forever, anySelected, selectionTarget(hasThing, hasFolder),
            status, Thing.DELETED_FOREVER
        )

        setMenuItemVisible(R.id.act_move_to_thing_folder, underway && anySelected)
        // Export only handles Things; keep it for Thing-only selections.
        setMenuItemVisible(R.id.act_export, hasThing && !hasFolder)

        // Retired in selecting mode: the unified verbs above also cover the
        // single-folder content ops, so these dedicated items stay hidden.
        setMenuItemVisible(R.id.act_finish_thing_folder, false)
        setMenuItemVisible(R.id.act_restore_thing_folder_content, false)
        setMenuItemVisible(R.id.act_delete_thing_folder_content, false)

        // Dissolve stays single-folder only.
        setMenuItemVisible(R.id.act_dissolve_thing_folder, singleFolderOnly && underway)
        // Recycle bin: structural permanent delete — destroys the selected folder
        // containers and their entire subtree (all states/types), plus selected
        // Things. Distinct from the content-only "永久删除…中的记事" above.
        val deleteFolderItem = mContextualToolbar!!.menu.findItem(R.id.act_delete_thing_folder)
        if (deleteFolderItem != null) {
            deleteFolderItem.isVisible = hasFolder && deleted
            if (hasFolder && deleted) {
                deleteFolderItem.setTitle(
                    HomeActionWordingHelper.structuralActionTitle(
                        mApp!!,
                        HomeActionWordingHelper.StructuralAction.DELETE_FOLDER_FOREVER,
                        when {
                            singleFolderOnly -> HomeActionWordingHelper.StructuralTarget.SELECTED_FOLDER
                            !hasThing -> HomeActionWordingHelper.StructuralTarget.SELECTED_FOLDERS
                            else -> HomeActionWordingHelper.StructuralTarget.SELECTED_ITEMS
                        }
                    )
                )
            }
        }
    }

    private fun setStateVerb(
        itemId: Int,
        visible: Boolean,
        target: HomeActionWordingHelper.StateTarget,
        status: Int,
        stateAfter: Int
    ) {
        val item: MenuItem = mContextualToolbar!!.menu.findItem(itemId) ?: return
        item.isVisible = visible
        if (!visible) return
        item.title = HomeActionWordingHelper.stateActionTitle(mApp!!, status, stateAfter, target)
    }

    private fun selectionTarget(
        hasThing: Boolean,
        hasFolder: Boolean
    ): HomeActionWordingHelper.StateTarget {
        return when {
            !hasFolder -> HomeActionWordingHelper.StateTarget.SELECTED_THINGS
            !hasThing -> HomeActionWordingHelper.StateTarget.SELECTED_FOLDERS
            else -> HomeActionWordingHelper.StateTarget.SELECTED_ITEMS
        }
    }

    private fun setMenuItemVisible(itemId: Int, visible: Boolean) {
        mContextualToolbar!!.menu.findItem(itemId)?.isVisible = visible
    }

    open fun updateTitleTextSize() {
        val toolbar: Toolbar = mContextualToolbar!!
        toolbar.setTitleTextAppearance(mApp!!, R.style.ContextualToolbarText)
        toolbar.invalidate()
    }

    companion object {
        const val TAG: String = "ModeManager"

        const val NORMAL: Int    = 0
        const val MOVING: Int    = 1
        const val SELECTING: Int = 2
    }
}
