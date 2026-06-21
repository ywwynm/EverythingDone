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
        if (mThingManager!!.getSelectedCount() != 1) {
            item.isVisible = false
        } else {
            item.isVisible = true
            val entry = mThingManager!!.getSingleSelectedEntry()
            val sticky = when (entry) {
                is ThingListEntry.ThingEntry -> entry.thing.location < 0
                is ThingListEntry.FolderEntry -> entry.folder.location < 0
                else -> false
            }
            if (sticky) {
                item.setIcon(R.drawable.act_cancel_sticky)
                item.setTitle(R.string.act_cancel_sticky)
            } else {
                item.setIcon(R.drawable.act_sticky_on_top)
                item.setTitle(R.string.act_sticky_on_top)
            }
        }
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
        if (thing.state == Thing.FINISHED) {
            return false
        }

        return true
    }

    private fun updateMenuItemPrivate() {
        val item: MenuItem = mContextualToolbar!!.getMenu()
                .findItem(R.id.act_set_as_private_thing) ?: return
        val entry = mThingManager!!.getSingleSelectedEntry()
        val limitIsUnderway = mApp!!.getStatus() == Def.ThingStatus.UNDERWAY
        when (entry) {
            is ThingListEntry.ThingEntry -> {
                val thing = entry.thing
                item.isVisible = limitIsUnderway && thing.id != App.getDoingThingId()
                item.setTitle(
                        if (thing.isPrivate()) {
                            R.string.act_cancel_private_thing
                        } else {
                            R.string.act_set_as_private_thing
                        }
                )
            }
            is ThingListEntry.FolderEntry -> {
                val folder = entry.folder
                item.isVisible = limitIsUnderway
                item.setTitle(
                        if (folder.isPrivate) {
                            R.string.cancel_thing_folder_private
                        } else {
                            R.string.set_thing_folder_private
                        }
                )
            }
            else -> {
                item.isVisible = false
            }
        }
    }

    private fun updateMenuItemsForFolderSelection() {
        val selectedFolderCount = mThingManager!!.getSelectedFolderCount()
        val selectedThingCount = mThingManager!!.getSelectedThingCount()
        val hasSelectedFolder = selectedFolderCount > 0
        val singleFolderOnly = selectedFolderCount == 1 && selectedThingCount == 0
        val selectedFolder = (mThingManager!!.getSingleSelectedEntry()
                as? ThingListEntry.FolderEntry)?.folder

        setMenuItemVisible(R.id.act_finish_selected, !hasSelectedFolder)
        setMenuItemVisible(R.id.act_delete_selected, !hasSelectedFolder)
        setMenuItemVisible(R.id.act_delete_selected_forever, !hasSelectedFolder)
        val limitIsUnderway = mApp!!.getStatus() == Def.ThingStatus.UNDERWAY
        val moveToFolderVisible = limitIsUnderway && (!hasSelectedFolder ||
                (singleFolderOnly && selectedFolder?.isDeleted() != true))
        setMenuItemVisible(R.id.act_move_to_thing_folder, moveToFolderVisible)
        setMenuItemVisible(R.id.act_export, !hasSelectedFolder)

        val restoreVisible = if (hasSelectedFolder) {
            singleFolderOnly && selectedFolder?.isDeleted() == true
        } else {
            true
        }
        setMenuItemVisible(R.id.act_restore_selected, restoreVisible)

        val dissolveItem = mContextualToolbar!!.menu
                .findItem(R.id.act_dissolve_thing_folder)
        dissolveItem?.isVisible = singleFolderOnly && limitIsUnderway

        val deleteFolderItem = mContextualToolbar!!.menu
                .findItem(R.id.act_delete_thing_folder)
        if (deleteFolderItem != null) {
            deleteFolderItem.isVisible = singleFolderOnly
            val permanentlyDelete = selectedFolder?.isDeleted() == true ||
                    mApp!!.getStatus() == Def.ThingStatus.DELETED
            deleteFolderItem.setTitle(
                    if (permanentlyDelete) {
                        R.string.delete_thing_folder_forever
                    } else {
                        R.string.delete_thing_folder
                    }
            )
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
