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

    private var backNormalModeListener: View.OnClickListener? = null

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
        if (position < 0 || position > mThingManager!!.getThings()!!.size - 1) {
            return
        }
        beforeMode = currentMode
        currentMode = MOVING
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
        (rv.itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
        updateMenuItems()
    }

    open fun backNormalMode(position: Int) {
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
        if (mApp!!.getLimit() <= Def.LimitForGettingThings.GOAL_UNDERWAY
                && !isSearching) {
            mFab!!.spread()
        }
        mThingManager!!.setSelectedTo(false)
        (mRecyclerView!!.itemAnimator as SimpleItemAnimator).supportsChangeAnimations = true
    }

    private fun notifyThingsSelected(position: Int) {
        mFab!!.shrink()
        val things: List<Thing?> = mThingManager!!.getThings()!!
        if (position >= 0 && position < things.size) {
            things[position]!!.selected = true
        }
        mAdapter!!.notifyDataSetChanged()
    }

    open fun showContextualToolbar(anim: Boolean) {
        val tb: Toolbar = mContextualToolbar!!
        tb.setTitleTextAppearance(mApp!!, R.style.ContextualToolbarText)
        tb.setNavigationIcon(R.drawable.act_close)
        tb.setNavigationOnClickListener(backNormalModeListener)
        tb.setOnMenuItemClickListener(mContextualListener)
        val limit: Int = mApp!!.getLimit()
        if (limit <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
            tb.inflateMenu(R.menu.menu_contextual_underway)
        } else if (limit == Def.LimitForGettingThings.ALL_FINISHED) {
            tb.inflateMenu(R.menu.menu_contextual_finished)
        } else {
            tb.inflateMenu(R.menu.menu_contextual_deleted)
        }

        val rl: RelativeLayout = mRlContextualToolbar!!
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
        tb.getMenu().clear()
    }

    open fun updateSelectedCount() {
        val selectedCount: Int = mThingManager!!.getSelectedCount()
        val toolbar: Toolbar = mContextualToolbar!!
        toolbar.setTitle(selectedCount.toString() + " / " +
                (mThingManager!!.getThings()!!.size - 1))
    }

    open fun updateMenuItems() {
        updateMenuItemSelectAll()
        if (mApp!!.getLimit() <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
            updateMenuItemStickyOnTop()
        }
    }

    private fun updateMenuItemSelectAll() {
        val item: MenuItem = mContextualToolbar!!.getMenu().findItem(R.id.act_select_all) ?: return
        if (mThingManager!!.getSelectedCount() == mThingManager!!.getThings()!!.size - 1) {
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
            val thing: Thing = mThingManager!!.getSelectedThings()!![0]!!
            if (thing.location < 0) {
                item.setIcon(R.drawable.act_cancel_sticky)
                item.setTitle(R.string.act_cancel_sticky)
            } else {
                item.setIcon(R.drawable.act_sticky_on_top)
                item.setTitle(R.string.act_sticky_on_top)
            }
        }
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
