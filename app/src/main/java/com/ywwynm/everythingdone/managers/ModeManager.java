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

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.ThingsAdapter;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.views.ActivityHeader;
import com.ywwynm.everythingdone.views.FloatingActionButton;

import java.lang.ref.WeakReference;

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

    private WeakReference<DrawerLayout> mWrDrawerLayout;
    //private DrawerLayout bindingDrawerLayout;

    //private FloatingActionButton bindingFab;
    private WeakReference<FloatingActionButton> mWrFab;

    //private ActivityHeader bindingHeader;
    private WeakReference<ActivityHeader> mWrActivityHeader;

    //private RelativeLayout bindingRlContextualToolbar;
    private WeakReference<RelativeLayout> mWrRlContextualToolbar;

    //private Toolbar bindingContextualToolbar;
    private WeakReference<Toolbar> mWrContextualToolbar;

    //private View.OnClickListener navigationIconClickedListener;
    private WeakReference<View.OnClickListener> mWrNavigationIconListener;

    //private Toolbar.OnMenuItemClickListener mOnContextualMenuClickedListener;
    private WeakReference<Toolbar.OnMenuItemClickListener> mWrContextualMenuListener;

    private Animation showContextualToolbar;
    private Animation hideContextualToolbar;

    //private RecyclerView bindingRecyclerView;
    private WeakReference<RecyclerView> mWrRecyclerView;

    //private ThingsAdapter bindingAdapter;
    private WeakReference<ThingsAdapter> mWrAdapter;

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

        mWrDrawerLayout = new WeakReference<>(drawerLayout);

        mWrFab = new WeakReference<>(fab);
        mWrActivityHeader = new WeakReference<>(header);

        mWrRlContextualToolbar = new WeakReference<>(rlContextualToolbar);
        mWrContextualToolbar = new WeakReference<>(toolbar);

        mWrNavigationIconListener = new WeakReference<>(nListener);
        mWrContextualMenuListener = new WeakReference<>(listener);

        showContextualToolbar = AnimationUtils.loadAnimation(mApp,
                R.anim.contextual_toolbar_show);
        hideContextualToolbar = AnimationUtils.loadAnimation(mApp,
                R.anim.contextual_toolbar_hide);

        mWrRecyclerView = new WeakReference<>(recyclerView);
        mWrAdapter = new WeakReference<>(adapter);

        notifyDataSetRunnable = new Runnable() {
            @Override
            public void run() {
                mWrAdapter.get().notifyDataSetChanged();
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
                mWrActivityHeader.get().hideActionbarShadow();
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

        RecyclerView rv = mWrRecyclerView.get();
        if (beforeMode == NORMAL) {
            notifyThingsSelected(position);
        } else {
            CardView cv = (CardView) rv.
                    findViewHolderForAdapterPosition(position).itemView;
            ObjectAnimator.ofFloat(cv, "cardElevation", 2 * screenDensity).setDuration(96).start();
            ObjectAnimator.ofFloat(cv, "scaleX", 1.0f).setDuration(96).start();
            ObjectAnimator.ofFloat(cv, "scaleY", 1.0f).setDuration(96).start();
        }
        ((SimpleItemAnimator) rv.getItemAnimator())
                .setSupportsChangeAnimations(false);
        updateSelectAllButton();
    }

    public void backNormalMode(final int position) {
        boolean isSearching = App.isSearching;
        if (!isSearching) {
            mWrDrawerLayout.get().setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        }
        beforeMode = currentMode;
        currentMode = NORMAL;
        if (beforeMode == SELECTING) {
            hideContextualToolbar();
            ThingsAdapter adapter = mWrAdapter.get();
            adapter.setShouldThingsAnimWhenAppearing(false);
            adapter.notifyDataSetChanged();
        } else {
            CardView cv = (CardView) mWrRecyclerView.get().
                    findViewHolderForAdapterPosition(position).itemView;
            ObjectAnimator.ofFloat(cv, "CardElevation", 2 * screenDensity).
                    setDuration(96).start();
            cv.animate().scaleX(1.0f).setDuration(96);
            cv.animate().scaleY(1.0f).withEndAction(notifyDataSetRunnable).setDuration(96);
        }
        if (mApp.getLimit() <= Def.LimitForGettingThings.GOAL_UNDERWAY
                && !isSearching) {
            mWrFab.get().spread();
        }
        mThingManager.setSelectedTo(false);
        ((SimpleItemAnimator) mWrRecyclerView.get().getItemAnimator())
                .setSupportsChangeAnimations(true);
    }

    public void notifyThingsSelected(final int position) {
        mWrFab.get().shrink();
        mThingManager.getThings().get(position).setSelected(true);
        mWrAdapter.get().notifyDataSetChanged();
    }

    public void showContextualToolbar(boolean anim) {
        Toolbar toolbar = mWrContextualToolbar.get();
        toolbar.setTitleTextAppearance(mApp, R.style.ContextualToolbarText);
        toolbar.setNavigationIcon(R.drawable.act_close);
        toolbar.setNavigationOnClickListener(backNormalModeListener);
        toolbar.setOnMenuItemClickListener(mWrContextualMenuListener.get());
        int limit = mApp.getLimit();
        if (limit <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
            toolbar.inflateMenu(R.menu.menu_contextual_underway);
        } else if (limit == Def.LimitForGettingThings.ALL_FINISHED) {
            toolbar.inflateMenu(R.menu.menu_contextual_finished);
        } else {
            toolbar.inflateMenu(R.menu.menu_contextual_deleted);
        }

        RelativeLayout rl = mWrRlContextualToolbar.get();
        rl.setVisibility(View.VISIBLE);
        if (anim) {
            rl.setAnimation(showContextualToolbar);
            showContextualToolbar.startNow();
        }
        mWrRecyclerView.get().postDelayed(hideActionBarShadowRunnable, 200);
    }

    public void hideContextualToolbar() {
        mWrActivityHeader.get().showActionbarShadow();

        Toolbar toolbar = mWrContextualToolbar.get();
        toolbar.setNavigationOnClickListener(mWrNavigationIconListener.get());
        toolbar.setOnMenuItemClickListener(null);

        RelativeLayout rl = mWrRlContextualToolbar.get();
        rl.setAnimation(hideContextualToolbar);
        hideContextualToolbar.start();
        rl.setVisibility(View.INVISIBLE);
        toolbar.getMenu().clear();
    }

    public void updateSelectedCount() {
        int selectedCount = mThingManager.getSelectedCount();
        Toolbar toolbar = mWrContextualToolbar.get();
        toolbar.setTitle(selectedCount + " / "
                + (mThingManager.getThings().size() - 1));
    }

    public void updateSelectAllButton() {
        MenuItem item = mWrContextualToolbar.get().getMenu().findItem(R.id.act_select_all);
        if (item == null) {
            return;
        }
        if (mThingManager.getSelectedCount() == mThingManager.getThings().size() - 1) {
            item.setIcon(R.drawable.act_deselect_all);
        } else {
            item.setIcon(R.drawable.act_select_all);
        }
    }

    public void updateTitleTextSize() {
        Toolbar toolbar = mWrContextualToolbar.get();
        toolbar.setTitleTextAppearance(mApp, R.style.ContextualToolbarText);
        toolbar.invalidate();
    }
}
