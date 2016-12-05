package com.ywwynm.everythingdone.managers;

import android.animation.ObjectAnimator;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.widget.CardView;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SimpleItemAnimator;
import android.support.v7.widget.Toolbar;
import android.support.v7.widget.Toolbar.OnMenuItemClickListener;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.RelativeLayout;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.ThingsAdapter;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.views.ActivityHeader;
import com.ywwynm.everythingdone.views.FloatingActionButton;

import java.util.List;

/**
 * Created by ywwynm on 2015/7/17.
 * A manager class for changing mode in ThingsActivity
 */
public class ModeManager {

    public static final String TAG = "ModeManager";

    public static final int NORMAL    = 0;
    public static final int MOVING    = 1;
    public static final int SELECTING = 2;

    private int beforeMode;
    private int currentMode;

    private App mApp;
    private ThingManager mThingManager;
    private float screenDensity;

    //private WeakReference<DrawerLayout> mWrDrawerLayout;
    private DrawerLayout mDrawerLayout;

    private FloatingActionButton mFab;
    //private WeakReference<FloatingActionButton> mWrFab;

    private ActivityHeader mHeader;
    //private WeakReference<ActivityHeader> mWrActivityHeader;

    private RelativeLayout mRlContextualToolbar;
    //private WeakReference<RelativeLayout> mWrRlContextualToolbar;

    private Toolbar mContextualToolbar;
    //private WeakReference<Toolbar> mWrContextualToolbar;

    private View.OnClickListener mNavigationListener;
    //private WeakReference<View.OnClickListener> mWrNavigationIconListener;

    private Toolbar.OnMenuItemClickListener mContextualListener;
    //private WeakReference<Toolbar.OnMenuItemClickListener> mWrContextualMenuListener;

    private Animation showContextualToolbar;
    private Animation hideContextualToolbar;

    private RecyclerView mRecyclerView;
    //private WeakReference<RecyclerView> mWrRecyclerView;

    private ThingsAdapter mAdapter;
    //private WeakReference<ThingsAdapter> mWrAdapter;

    private View.OnClickListener backNormalModeListener;

    private Runnable notifyDataSetRunnable;
    private Runnable hideActionBarShadowRunnable;

    public ModeManager(App app,
                       DrawerLayout drawerLayout,
                       FloatingActionButton fab, ActivityHeader header,
                       RelativeLayout rlContextualToolbar, Toolbar toolbar,
                       View.OnClickListener nListener,
                       OnMenuItemClickListener listener,
                       RecyclerView recyclerView, ThingsAdapter adapter) {
        beforeMode = NORMAL;
        currentMode = NORMAL;

        mApp = app;
        mThingManager = ThingManager.getInstance(mApp);
        screenDensity = DisplayUtil.getScreenDensity(mApp);

        mDrawerLayout = drawerLayout;

        mFab = fab;
        mHeader = header;

        mRlContextualToolbar = rlContextualToolbar;
        mContextualToolbar = toolbar;

        mNavigationListener = nListener;
        mContextualListener = listener;

        showContextualToolbar = AnimationUtils.loadAnimation(mApp,
                R.anim.contextual_toolbar_show);
        hideContextualToolbar = AnimationUtils.loadAnimation(mApp,
                R.anim.contextual_toolbar_hide);

        mRecyclerView = recyclerView;
        mAdapter = adapter;

        notifyDataSetRunnable = new Runnable() {
            @Override
            public void run() {
                mAdapter.notifyDataSetChanged();
            }
        };

        backNormalModeListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ModeManager.this.backNormalMode(0);
            }
        };

        hideActionBarShadowRunnable = new Runnable() {
            @Override
            public void run() {
                mHeader.hideActionbarShadow();
            }
        };
    }

    public int getCurrentMode() {
        return currentMode;
    }

    public void toMovingMode(int position) {
        if (position < 0 || position > mThingManager.getThings().size() - 1) {
            return;
        }
        beforeMode = currentMode;
        currentMode = MOVING;
        notifyThingsSelected(position);
    }

    public void toSelectingMode(final int position) {
        if (position < 0) {
            return;
        }
        updateSelectedCount();
        showContextualToolbar(true);
        beforeMode = currentMode;
        currentMode = SELECTING;

        RecyclerView rv = mRecyclerView;
        if (beforeMode == NORMAL) {
            notifyThingsSelected(position);
        } else {
            RecyclerView.ViewHolder holder = rv.
                    findViewHolderForAdapterPosition(position);
            if (holder != null) {
                CardView cv = (CardView) holder.itemView;
                ObjectAnimator.ofFloat(cv, "cardElevation", 2 * screenDensity).setDuration(96).start();
                ObjectAnimator.ofFloat(cv, "scaleX", 1.0f).setDuration(96).start();
                ObjectAnimator.ofFloat(cv, "scaleY", 1.0f).setDuration(96).start();
            }
        }
        ((SimpleItemAnimator) rv.getItemAnimator())
                .setSupportsChangeAnimations(false);
        updateMenuItems();
    }

    public void backNormalMode(final int position) {
        boolean isSearching = App.isSearching;
        if (!isSearching) {
            mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        }
        beforeMode = currentMode;
        currentMode = NORMAL;
        if (beforeMode == SELECTING) {
            hideContextualToolbar();
            ThingsAdapter adapter = mAdapter;
            adapter.setShouldThingsAnimWhenAppearing(false);
            adapter.notifyDataSetChanged();
        } else {
            RecyclerView.ViewHolder holder = mRecyclerView.
                    findViewHolderForAdapterPosition(position);
            if (holder != null) {
                CardView cv = (CardView) holder.itemView;
                ObjectAnimator.ofFloat(cv, "CardElevation", 2 * screenDensity).
                        setDuration(96).start();
                cv.animate().scaleX(1.0f).setDuration(96);
                cv.animate().scaleY(1.0f).withEndAction(notifyDataSetRunnable).setDuration(96);
            }
        }
        if (mApp.getLimit() <= Def.LimitForGettingThings.GOAL_UNDERWAY
                && !isSearching) {
            mFab.spread();
        }
        mThingManager.setSelectedTo(false);
        ((SimpleItemAnimator) mRecyclerView.getItemAnimator())
                .setSupportsChangeAnimations(true);
    }

    private void notifyThingsSelected(final int position) {
        mFab.shrink();
        List<Thing> things = mThingManager.getThings();
        if (position >= 0 && position < things.size()) {
            things.get(position).setSelected(true);
        }
        mAdapter.notifyDataSetChanged();
    }

    public void showContextualToolbar(boolean anim) {
        Toolbar tb = mContextualToolbar;
        tb.setTitleTextAppearance(mApp, R.style.ContextualToolbarText);
        tb.setNavigationIcon(R.drawable.act_close);
        tb.setNavigationOnClickListener(backNormalModeListener);
        tb.setOnMenuItemClickListener(mContextualListener);
        int limit = mApp.getLimit();
        if (limit <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
            tb.inflateMenu(R.menu.menu_contextual_underway);
        } else if (limit == Def.LimitForGettingThings.ALL_FINISHED) {
            tb.inflateMenu(R.menu.menu_contextual_finished);
        } else {
            tb.inflateMenu(R.menu.menu_contextual_deleted);
        }

        RelativeLayout rl = mRlContextualToolbar;
        rl.setVisibility(View.VISIBLE);
        if (anim) {
            rl.setAnimation(showContextualToolbar);
            showContextualToolbar.startNow();
        }
        mRecyclerView.postDelayed(hideActionBarShadowRunnable, 200);
    }

    private void hideContextualToolbar() {
        mHeader.showActionbarShadow();

        Toolbar tb = mContextualToolbar;
        tb.setNavigationOnClickListener(mNavigationListener);
        tb.setOnMenuItemClickListener(null);

        RelativeLayout rl = mRlContextualToolbar;
        rl.setAnimation(hideContextualToolbar);
        hideContextualToolbar.start();
        rl.setVisibility(View.INVISIBLE);
        tb.getMenu().clear();
    }

    public void updateSelectedCount() {
        int selectedCount = mThingManager.getSelectedCount();
        Toolbar toolbar = mContextualToolbar;
        toolbar.setTitle(selectedCount + " / "
                + (mThingManager.getThings().size() - 1));
    }

    public void updateMenuItems() {
        updateMenuItemSelectAll();
        if (mApp.getLimit() <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
            updateMenuItemStickyOnTop();
        }
    }

    private void updateMenuItemSelectAll() {
        MenuItem item = mContextualToolbar.getMenu().findItem(R.id.act_select_all);
        if (item == null) {
            return;
        }
        if (mThingManager.getSelectedCount() == mThingManager.getThings().size() - 1) {
            item.setIcon(R.drawable.act_deselect_all);
            item.setTitle(R.string.act_deselect_all);
        } else {
            item.setIcon(R.drawable.act_select_all);
            item.setTitle(R.string.act_select_all);
        }
    }

    private void updateMenuItemStickyOnTop() {
        MenuItem item = mContextualToolbar.getMenu().findItem(R.id.act_sticky);
        if (item == null) {
            return;
        }
        if (mThingManager.getSelectedCount() != 1) {
            item.setVisible(false);
        } else {
            item.setVisible(true);
            Thing thing = mThingManager.getSelectedThings()[0];
            if (thing.getLocation() < 0) {
                item.setIcon(R.drawable.act_cancel_sticky);
                item.setTitle(R.string.act_cancel_sticky);
            } else {
                item.setIcon(R.drawable.act_sticky_on_top);
                item.setTitle(R.string.act_sticky_on_top);
            }
        }
    }

    public void updateTitleTextSize() {
        Toolbar toolbar = mContextualToolbar;
        toolbar.setTitleTextAppearance(mApp, R.style.ContextualToolbarText);
        toolbar.invalidate();
    }
}
