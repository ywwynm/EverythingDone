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

import com.ywwynm.everythingdone.Definitions;
import com.ywwynm.everythingdone.EverythingDoneApplication;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.ThingsAdapter;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.views.ActivityHeader;
import com.ywwynm.everythingdone.views.FloatingActionButton;

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

    private EverythingDoneApplication mApplication;
    private ThingManager mThingManager;
    private float screenDensity;

    private DrawerLayout bindingDrawerLayout;

    private FloatingActionButton bindingFab;
    private ActivityHeader bindingHeader;

    private RelativeLayout bindingRlContextualToolbar;
    private Toolbar bindingContextualToolbar;
    private View.OnClickListener navigationIconClickedListener;
    private Toolbar.OnMenuItemClickListener mOnContextualMenuClickedListener;
    private Animation showContextualToolbar;
    private Animation hideContextualToolbar;

    private RecyclerView bindingRecyclerView;
    private ThingsAdapter bindingAdapter;

    private Runnable notifyDataSetRunnable;
    private View.OnClickListener backNormalModeListener;
    private Runnable hideActionBarShadowRunnable;

    public ModeManager(EverythingDoneApplication application,
                       DrawerLayout drawerLayout,
                       FloatingActionButton fab, ActivityHeader header,
                       RelativeLayout rlContextualToolbar, Toolbar toolbar,
                       View.OnClickListener nListener,
                       OnMenuItemClickListener listener,
                       RecyclerView recyclerView, ThingsAdapter adapter) {
        beforeMode = NORMAL;
        currentMode = NORMAL;

        mApplication = application;
        mThingManager = ThingManager.getInstance(mApplication);
        screenDensity = DisplayUtil.getScreenDensity(mApplication);

        bindingDrawerLayout = drawerLayout;

        bindingFab = fab;
        bindingHeader = header;

        bindingRlContextualToolbar = rlContextualToolbar;
        bindingContextualToolbar = toolbar;
        navigationIconClickedListener = nListener;
        mOnContextualMenuClickedListener = listener;
        showContextualToolbar = AnimationUtils.loadAnimation(mApplication,
                R.anim.contextual_toolbar_show);
        hideContextualToolbar = AnimationUtils.loadAnimation(mApplication,
                R.anim.contextual_toolbar_hide);

        bindingRecyclerView = recyclerView;
        bindingAdapter = adapter;

        notifyDataSetRunnable = new Runnable() {
            @Override
            public void run() {
                bindingAdapter.notifyDataSetChanged();
            }
        };
        backNormalModeListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                backNormalMode(0);
            }
        };
        hideActionBarShadowRunnable = new Runnable() {
            @Override
            public void run() {
                bindingHeader.hideActionbarShadow();
            }
        };
    }

    public int getCurrentMode() {
        return currentMode;
    }

    public void toMovingMode(int position) {
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
        if (beforeMode == NORMAL) {
            notifyThingsSelected(position);
        } else {
            CardView cv = (CardView) bindingRecyclerView.
                    findViewHolderForAdapterPosition(position).itemView;
            ObjectAnimator.ofFloat(cv, "cardElevation", 2 * screenDensity).setDuration(96).start();
            ObjectAnimator.ofFloat(cv, "scaleX", 1.0f).setDuration(96).start();
            ObjectAnimator.ofFloat(cv, "scaleY", 1.0f).setDuration(96).start();
        }
        ((SimpleItemAnimator) bindingRecyclerView.getItemAnimator())
                .setSupportsChangeAnimations(false);
    }

    public void backNormalMode(final int position) {
        boolean isSearching = EverythingDoneApplication.isSearching;
        if (!isSearching) {
            bindingDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        }
        beforeMode = currentMode;
        currentMode = NORMAL;
        if (beforeMode == SELECTING) {
            hideContextualToolbar();
            bindingAdapter.setShouldThingsAnimWhenAppearing(false);
            bindingAdapter.notifyDataSetChanged();
        } else {
            CardView cv = (CardView) bindingRecyclerView.
                    findViewHolderForAdapterPosition(position).itemView;
            ObjectAnimator.ofFloat(cv, "CardElevation", 2 * screenDensity).
                    setDuration(96).start();
            cv.animate().scaleX(1.0f).setDuration(96);
            cv.animate().scaleY(1.0f).withEndAction(notifyDataSetRunnable).setDuration(96);
        }
        if (mApplication.getLimit() <= Definitions.LimitForGettingThings.GOAL_UNDERWAY
                && !isSearching) {
            bindingFab.spread();
        }
        mThingManager.setSelectedTo(false);
        ((SimpleItemAnimator) bindingRecyclerView.getItemAnimator())
                .setSupportsChangeAnimations(true);
    }

    public void notifyThingsSelected(final int position) {
        bindingFab.shrink();
        mThingManager.getThings().get(position).setSelected(true);
        bindingAdapter.notifyDataSetChanged();
    }

    public void showContextualToolbar(boolean anim) {
        bindingContextualToolbar.setTitleTextAppearance(mApplication, R.style.ContextualToolbarText);
        bindingContextualToolbar.setNavigationIcon(R.mipmap.act_close);
        bindingContextualToolbar.setNavigationOnClickListener(backNormalModeListener);
        bindingContextualToolbar.setOnMenuItemClickListener(mOnContextualMenuClickedListener);
        int limit = mApplication.getLimit();
        if (limit <= Definitions.LimitForGettingThings.GOAL_UNDERWAY) {
            bindingContextualToolbar.inflateMenu(R.menu.menu_contextual_underway);
        } else if (limit == Definitions.LimitForGettingThings.ALL_FINISHED) {
            bindingContextualToolbar.inflateMenu(R.menu.menu_contextual_finished);
        } else {
            bindingContextualToolbar.inflateMenu(R.menu.menu_contextual_deleted);
        }
        bindingRlContextualToolbar.setVisibility(View.VISIBLE);
        if (anim) {
            bindingRlContextualToolbar.setAnimation(showContextualToolbar);
            showContextualToolbar.startNow();
        }
        bindingRecyclerView.postDelayed(hideActionBarShadowRunnable, 200);
    }

    public void hideContextualToolbar() {
        bindingHeader.showActionbarShadow();
        bindingContextualToolbar.setNavigationOnClickListener(navigationIconClickedListener);
        bindingContextualToolbar.setOnMenuItemClickListener(null);
        bindingRlContextualToolbar.setAnimation(hideContextualToolbar);
        hideContextualToolbar.start();
        bindingRlContextualToolbar.setVisibility(View.INVISIBLE);
        bindingContextualToolbar.getMenu().clear();
    }

    public void updateSelectedCount() {
        int selectedCount = mThingManager.getSelectedCount();
        bindingContextualToolbar.setTitle(selectedCount + " / "
                + (mThingManager.getThings().size() - 1));
        MenuItem item = bindingContextualToolbar.getMenu().findItem(R.id.act_share);
        if (item == null) {
            return;
        }
        item.setVisible(selectedCount == 1);
    }

    public void updateTitleTextSize() {
        bindingContextualToolbar.setTitleTextAppearance(mApplication, R.style.ContextualToolbarText);
        bindingContextualToolbar.invalidate();
    }
}
