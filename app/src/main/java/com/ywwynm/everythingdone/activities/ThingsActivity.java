package com.ywwynm.everythingdone.activities;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.support.design.widget.NavigationView;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.graphics.drawable.DrawerArrowDrawable;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.support.v7.widget.Toolbar;
import android.support.v7.widget.Toolbar.OnMenuItemClickListener;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.ywwynm.everythingdone.Definitions;
import com.ywwynm.everythingdone.EverythingDoneApplication;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.ThingsAdapter;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.fragments.AlertDialogFragment;
import com.ywwynm.everythingdone.fragments.ThreeActionsAlertDialogFragment;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.helpers.SendInfoHelper;
import com.ywwynm.everythingdone.managers.ModeManager;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.HabitRecord;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.EdgeEffectUtil;
import com.ywwynm.everythingdone.utils.KeyboardUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;
import com.ywwynm.everythingdone.utils.VersionUtil;
import com.ywwynm.everythingdone.views.ActivityHeader;
import com.ywwynm.everythingdone.views.DrawerHeader;
import com.ywwynm.everythingdone.views.FloatingActionButton;
import com.ywwynm.everythingdone.views.RevealLayout.RevealLayout;
import com.ywwynm.everythingdone.views.Snackbar;
import com.ywwynm.everythingdone.views.pickers.ColorPicker;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class ThingsActivity extends EverythingDoneBaseActivity {

    public static final String TAG = "ThingsActivity";

    private EverythingDoneApplication mApplication;

    private ThingManager mThingManager;

    private RevealLayout mRevealLayout;
    private View         mViewToReveal;
    private TextView     mTvNoResult;

    private Toolbar     mActionbar;
    private View        mViewInsideActionbar;

    private EditText    mEtSearch;
    private ColorPicker mColorPicker;

    private ModeManager mModeManager;

    private OnNavigationIconClickedListener mNavigationIconClickedListener;
    private OnContextualMenuClickedListener mOnContextualMenuClickedListener;

    private DrawerLayout   mDrawerLayout;
    private NavigationView mDrawer;
    private DrawerHeader   mDrawerHeader;
    private MenuItem       mPreviousItem;

    private ActivityHeader mActivityHeader;

    private FloatingActionButton mFab;
    private int                  mFabRippleColor;

    private RecyclerView               mRecyclerView;
    private ThingsAdapter              mThingsAdapter;
    private ItemTouchHelper            mThingsTouchHelper;
    private StaggeredGridLayoutManager mStaggeredGridLayoutManager;

    private int mSpan;

    private Snackbar          mNormalSnackbar;
    private Snackbar          mUndoSnackbar;
    private Snackbar          mHabitSnackbar;
    private List<Thing>       mUndoThings;
    private List<Integer>     mUndoPositions;
    private List<Long>        mUndoLocations;
    private List<HabitRecord> mUndoHabitRecords;
    private boolean           mUndoAll;
    private int               mStateToUndoFrom;

    /**
     * Used to know whether scrolling of {@link ThingsActivity.mRecyclerView}
     * is caused by swipe-to-dismiss or user's touch event.
     * See {@link ThingsActivity#setRecyclerViewEvents()} for details.
     */
    private boolean mScrollNotCausedByFinger = false;

    /**
     * Used to know whether reveal animation for entering {@link DetailActivity} with type
     * {@link DetailActivity.CREATE} is playing or not. Guarantee that kind of animation won't
     * be annoyed by other operations which can influence UI.
     */
    private boolean mIsRevealAnimPlaying = false;

    private boolean mUpdateMainUiInOnResume = true;

    private Runnable initRecyclerViewRunnable = new Runnable() {
        @Override
        public void run() {
            mRecyclerView.setAdapter(mThingsAdapter);
        }
    };

    private Intent mBroadCastIntent;

    private BroadcastReceiver mUpdateUiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (EverythingDoneApplication.getRunningDetailActivities().size() > 0) {
                mBroadCastIntent = intent;
                return;
            }
            int resultCode = intent.getIntExtra(Definitions.Communication.KEY_RESULT_CODE,
                    Definitions.Communication.RESULT_NO_UPDATE);
            updateMainUi(intent, resultCode);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setDrawer();

        IntentFilter filter = new IntentFilter(
                Definitions.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI);
        registerReceiver(mUpdateUiReceiver, filter);
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.activity_things;
    }

    @Override
    protected void beforeInit() {
        // this will be only called in onCreate(), which means this Activity has unregistered
        // receiver. So these two boolean values are useless now.
        EverythingDoneApplication.setSomethingUpdatedSpecially(false);
        EverythingDoneApplication.setShouldJustNotifyDataSetChanged(false);

        EverythingDoneApplication.thingsActivityWR = new WeakReference<>(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mUpdateMainUiInOnResume && EverythingDoneApplication.justNotifyDataSetChanged()) {
            justNotifyDataSetChanged();
        }

        if (VersionUtil.hasLollipopApi()) {
            setTaskDescription(new ActivityManager.TaskDescription(
                    getString(R.string.app_name)));
        }

        int color = DisplayUtil.getRandomColor(mApplication);
        while (color == mFabRippleColor) {
            color = DisplayUtil.getRandomColor(mApplication);
        }
        mFabRippleColor = color;
        mFab.setRippleColor(mFabRippleColor);
        mActivityHeader.updateText();
    }

    @Override
    protected void onPause() {
        super.onPause();
        dismissSnackbars();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mApplication.deleteAttachmentFiles();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mUpdateUiReceiver);
        mApplication.setDetailActivityRun(false);

        EverythingDoneApplication.thingsActivityWR.clear();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (EverythingDoneApplication.isSearching) {
            getMenuInflater().inflate(R.menu.menu_search, menu);
            mColorPicker.setAnchor(menu.findItem(R.id.act_select_color).getIcon());
            mColorPicker.updateAnchor();
            return true;
        }

        if (mApplication.getLimit() <= Definitions.LimitForGettingThings.GOAL_UNDERWAY) {
            getMenuInflater().inflate(R.menu.menu_things_underway, menu);
        } else if (mApplication.getLimit() == Definitions.LimitForGettingThings.ALL_FINISHED) {
            getMenuInflater().inflate(R.menu.menu_things_finished, menu);
        } else {
            getMenuInflater().inflate(R.menu.menu_things_deleted, menu);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int size = mThingManager.getThings().size();
        switch (item.getItemId()) {
            case R.id.act_search:
                toggleSearching();
                break;
            case R.id.act_finish_all:
                if (size != 1) {
                    handleUpdateStates(Thing.FINISHED);
                }
                break;
            case R.id.act_delete_all:
                if (size != 1) {
                    handleUpdateStates(Thing.DELETED);
                }
                break;
            case R.id.act_delete_all_forever:
                if (size != 1) {
                    handleUpdateStates(Thing.DELETED_FOREVER);
                }
                break;
            case R.id.act_select_color:
                dismissSnackbars();
                mColorPicker.show();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
            mDrawerLayout.closeDrawer(GravityCompat.START);
        } else {
            if (mModeManager.getCurrentMode() == ModeManager.SELECTING) {
                mModeManager.backNormalMode(0);
                return;
            } else if (EverythingDoneApplication.isSearching) {
                toggleSearching();
                return;
            }
            mApplication.setLimit(Definitions.LimitForGettingThings.ALL_UNDERWAY, true);
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        if (requestCode == Definitions.Communication.REQUEST_ACTIVITY_DETAIL) {
            updateMainUi(data, resultCode);
        }
    }

    private void updateMainUi(final Intent data, int resultCode) {
        mUpdateMainUiInOnResume = false;
        dismissSnackbars();
        switch (resultCode) {
            case Definitions.Communication.RESULT_JUST_NOTIFY_DATASET_CHANGED:
                mDrawerLayout.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        justNotifyDataSetChanged();
                        mUpdateMainUiInOnResume = true;
                        mBroadCastIntent = null;
                    }
                }, 560);
                break;
            case Definitions.Communication.RESULT_CREATE_THING_DONE:
                updateMainUiForCreateDone(data);
                break;
            case Definitions.Communication.RESULT_CREATE_BLANK_THING:
                mDrawerLayout.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mNormalSnackbar.setMessage(R.string.sb_cannot_be_blank);
                        mNormalSnackbar.show();
                        mUpdateMainUiInOnResume = true;
                        EverythingDoneApplication.setSomethingUpdatedSpecially(false);
                        mBroadCastIntent = null;
                    }
                }, 560);
                break;
            case Definitions.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME:
                updateMainUiForUpdateSameType(data);
                break;
            case Definitions.Communication.RESULT_UPDATE_THING_DONE_TYPE_DIFFERENT:
                updateMainUiForUpdateDifferentType(data);
                break;
            case Definitions.Communication.RESULT_UPDATE_THING_STATE_DIFFERENT:
                updateMainUiForUpdateDifferentState(data);
                break;
            default:
                if (mBroadCastIntent == null) {
                    mUpdateMainUiInOnResume = true;
                    EverythingDoneApplication.setSomethingUpdatedSpecially(false);
                } else {
                    updateMainUi(mBroadCastIntent, mBroadCastIntent.getIntExtra(
                            Definitions.Communication.KEY_RESULT_CODE, 0));
                }
                break;
        }
    }

    private void updateMainUiForCreateDone(Intent data) {
        if (EverythingDoneApplication.isSearching) {
            toggleSearching();
        } else {
            mApplication.setLimit(Definitions.LimitForGettingThings.ALL_UNDERWAY, true);
            mThingsAdapter.setShouldThingsAnimWhenAppearing(false);
            mThingsAdapter.notifyDataSetChanged();
        }

        MenuItem underway = mDrawer.getMenu().getItem(0);
        mPreviousItem.setChecked(false);
        underway.setChecked(true);
        mPreviousItem = underway;

        final boolean justNotifyDataSetChanged = EverythingDoneApplication.justNotifyDataSetChanged();
        final Thing thingToCreate = data.getParcelableExtra(
                Definitions.Communication.KEY_THING);
        mRecyclerView.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean change = mThingManager.create(thingToCreate, true);
                if (justNotifyDataSetChanged) {
                    justNotifyDataSetChanged();
                } else {
                    if (change) {
                        mThingsAdapter.notifyItemChanged(1);
                    } else {
                        mThingsAdapter.notifyItemInserted(1);
                    }
                }
                if (mModeManager.getCurrentMode() == ModeManager.SELECTING) {
                    updateSelectingUi(false);
                }
                mActivityHeader.updateText();
                mDrawerHeader.updateAll();
                mUpdateMainUiInOnResume = true;// TODO: 2016/3/28 mUpdateMainUiOnResume = false?
                EverythingDoneApplication.setSomethingUpdatedSpecially(false);
                mBroadCastIntent = null;
            }
        }, 560);
    }

    private void updateMainUiForUpdateSameType(final Intent data) {
        final int typeBefore = data.getIntExtra(
                Definitions.Communication.KEY_TYPE_BEFORE, Thing.NOTE);
        final boolean justNotifyDataSetChanged = EverythingDoneApplication.justNotifyDataSetChanged();
        mRecyclerView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (justNotifyDataSetChanged) {
                    justNotifyDataSetChanged();
                } else if (Thing.isTypeStateMatchLimit(typeBefore,
                        Thing.UNDERWAY, mApplication.getLimit())) {
                    int pos = data.getIntExtra(
                            Definitions.Communication.KEY_POSITION, 1);
                    if (!EverythingDoneApplication.isSearching) {
                        mThingsAdapter.notifyItemChanged(pos);
                    } else {
                        List<Thing> things = mThingManager.getThings();
                        if (pos > 0 && pos < things.size()) {
                            Thing thing = things.get(pos);
                            if (thing.matchSearchRequirement(
                                    mEtSearch.getText().toString(),
                                    mColorPicker.getPickedColor())) {
                                mThingsAdapter.notifyItemChanged(pos);
                            } else {
                                things.remove(pos);
                                mThingsAdapter.notifyItemRemoved(pos);
                            }
                            handleSearchResults();
                        }
                    }
                    if (mModeManager.getCurrentMode() == ModeManager.SELECTING) {
                        updateSelectingUi(false);
                    }
                }
                mDrawerHeader.updateCompletionRate();
                mUpdateMainUiInOnResume = true;
                EverythingDoneApplication.setSomethingUpdatedSpecially(false);
                mBroadCastIntent = null;
            }
        }, 560);
    }

    private void updateMainUiForUpdateDifferentType(final Intent data) {
        final Thing thing = data.getParcelableExtra(
                Definitions.Communication.KEY_THING);
        final int typeBefore = data.getIntExtra(
                Definitions.Communication.KEY_TYPE_BEFORE, Thing.NOTE);

        final boolean justNotifyDataSetChanged = EverythingDoneApplication.justNotifyDataSetChanged();
        mRecyclerView.postDelayed(new Runnable() {
            @Override
            public void run() {
                final int type = thing.getType();
                final int curLimit = mApplication.getLimit();
                boolean limitMatched = Thing.isTypeStateMatchLimit(type, Thing.UNDERWAY, curLimit);

                if (justNotifyDataSetChanged || limitMatched) {
                    justNotifyDataSetChanged();
                } else if (Thing.isTypeStateMatchLimit(typeBefore, Thing.UNDERWAY, curLimit)) {
                    if (EverythingDoneApplication.isSearching) {
                        mThingsAdapter.notifyItemRemoved(data.getIntExtra(
                                Definitions.Communication.KEY_POSITION, 1));
                        handleSearchResults();
                    } else {
                        final boolean change = data.getBooleanExtra(
                                Definitions.Communication.KEY_CALL_CHANGE, false);
                        if (change) {
                            mThingsAdapter.notifyItemChanged(1);
                        } else {
                            mThingsAdapter.notifyItemRemoved(data.getIntExtra(
                                    Definitions.Communication.KEY_POSITION, 1));
                        }
                    }
                    if (mModeManager.getCurrentMode() == ModeManager.SELECTING) {
                        updateSelectingUi(false);
                    }
                }

                mDrawerHeader.updateCompletionRate();
                mUpdateMainUiInOnResume = true;
                EverythingDoneApplication.setSomethingUpdatedSpecially(false);
                mBroadCastIntent = null;
            }
        }, 560);
    }

    private void updateMainUiForUpdateDifferentState(Intent data) {
        final Thing thing = data.getParcelableExtra(
                Definitions.Communication.KEY_THING);
        final int stateAfter = data.getIntExtra(
                Definitions.Communication.KEY_STATE_AFTER, Thing.UNDERWAY);
        final int position = data.getIntExtra(
                Definitions.Communication.KEY_POSITION, 1);
        final boolean changed = data.getBooleanExtra(
                Definitions.Communication.KEY_CALL_CHANGE, false);

        if (mStateToUndoFrom != stateAfter) {
            dismissSnackbars();
        }

        celebrateHabitGoalFinish(thing, thing.getState(), stateAfter);

        final boolean justNotifyDataSetChanged = EverythingDoneApplication.justNotifyDataSetChanged();
        mRecyclerView.postDelayed(new Runnable() {
            @Override
            public void run() {
                final int type = thing.getType();
                final int curLimit = mApplication.getLimit();
                boolean limitMatched = Thing.isTypeStateMatchLimit(type, stateAfter, curLimit);
                if (justNotifyDataSetChanged || limitMatched) {
                    justNotifyDataSetChanged();
                } else if (Thing.isTypeStateMatchLimit(type, thing.getState(), curLimit)) {
                    mUndoThings.add(thing);
                    mUndoPositions.add(position);
                    mUndoLocations.add(thing.getLocation());
                    mStateToUndoFrom = stateAfter;
                    if (changed) {
                        mThingsAdapter.notifyItemChanged(1);
                        updateUIAfterStateUpdated(stateAfter,
                                mRecyclerView.getItemAnimator().getChangeDuration(), false);
                    } else {
                        mThingsAdapter.notifyItemRemoved(position);
                        updateUIAfterStateUpdated(stateAfter,
                                mRecyclerView.getItemAnimator().getRemoveDuration(), false);
                    }
                }

                mDrawerHeader.updateCompletionRate();
                mUpdateMainUiInOnResume = true;
                EverythingDoneApplication.setSomethingUpdatedSpecially(false);
                mBroadCastIntent = null;
            }
        }, 560);
    }

    private void justNotifyDataSetChanged() {
        if (EverythingDoneApplication.isSearching) {
            mThingManager.searchThings(mEtSearch.getText().toString(), mColorPicker.getPickedColor());
            handleSearchResults();
        } else {
            mThingManager.loadThings();
        }

        mActivityHeader.updateText();
        mDrawerHeader.updateCompletionRate();

        if (mModeManager.getCurrentMode() == ModeManager.SELECTING) {
            updateSelectingUi(false);
        }

        mThingsAdapter.setShouldThingsAnimWhenAppearing(true);
        mThingsAdapter.notifyDataSetChanged();

        EverythingDoneApplication.setSomethingUpdatedSpecially(false);
        EverythingDoneApplication.setShouldJustNotifyDataSetChanged(false);
    }

    /**
     * Focus on change of screen orientation.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        dismissSnackbars();
        mColorPicker.dismiss();

        mSpan = DisplayUtil.isTablet(this) ? 3 : 2;
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mSpan++;
        }
        mStaggeredGridLayoutManager.setSpanCount(mSpan);
        if (mThingManager.getThings().size() > 1) {
            mRecyclerView.scrollToPosition(0);
        }

        if (!EverythingDoneApplication.isSearching) {
            mActivityHeader.reset(false);
        }

        mThingsAdapter.notifyDataSetChanged();

        mModeManager.updateTitleTextSize();
        if (mModeManager.getCurrentMode() != ModeManager.SELECTING && !EverythingDoneApplication.isSearching
                && mApplication.getLimit() <= Definitions.LimitForGettingThings.GOAL_UNDERWAY &&
                !mFab.isOnScreen()) {
            mFab.showFromBottom();
        }
    }

    @Override
    protected void initMembers() {
        mApplication  = (EverythingDoneApplication) getApplication();
        mThingManager = ThingManager.getInstance(mApplication);

        mNavigationIconClickedListener   = new OnNavigationIconClickedListener();
        mOnContextualMenuClickedListener = new OnContextualMenuClickedListener();

        mUndoThings         = new ArrayList<>();
        mUndoPositions      = new ArrayList<>();
        mUndoLocations      = new ArrayList<>();
        mUndoHabitRecords = new ArrayList<>();
    }

    @Override
    protected void findViews() {
        mRevealLayout = (RevealLayout) findViewById(R.id.reveal_layout);
        mViewToReveal = findViewById(R.id.view_to_reveal);
        mTvNoResult   = (TextView) findViewById(R.id.tv_no_result);

        mActionbar = (Toolbar) findViewById(R.id.actionbar);
        mViewInsideActionbar = findViewById(R.id.view_inside_actionbar);
        mEtSearch = (EditText) findViewById(R.id.et_search);
        Toolbar contextualToolbar = (Toolbar) findViewById(R.id.contextual_toolbar);
        contextualToolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.black_54p));
        RelativeLayout rlContextualToolbar = (RelativeLayout) findViewById(R.id.rl_contextual_toolbar);
        mColorPicker = new ColorPicker(this, getWindow().getDecorView(),
                Definitions.PickerType.COLOR_HAVE_ALL);

        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        mDrawer       = (NavigationView) findViewById(R.id.drawer);

        View drawerHeaderView = mDrawer.getHeaderView(0);
        mDrawerHeader = new DrawerHeader(mApplication,
                (TextView) drawerHeaderView.findViewById(R.id.tv_dh_location),
                (TextView) drawerHeaderView.findViewById(R.id.tv_dh_completion_rate));

        mFab = (FloatingActionButton) findViewById(R.id.fab_create);

        mRecyclerView  = (RecyclerView) findViewById(R.id.rv_things);
        mThingsAdapter = new ThingsAdapter(mApplication, new OnThingTouchedListener());

        mActivityHeader = new ActivityHeader(mApplication, mRecyclerView,
                findViewById(R.id.actionbar_shadow),
                (RelativeLayout) findViewById(R.id.rl_header),
                (TextView) findViewById(R.id.tv_header_title),
                (TextView) findViewById(R.id.tv_header_subtitle));

        View decor = getWindow().getDecorView();
        mNormalSnackbar = new Snackbar(mApplication, Snackbar.NORMAL, decor, mFab);
        mUndoSnackbar   = new Snackbar(mApplication, Snackbar.UNDO,   decor, mFab);
        mHabitSnackbar  = new Snackbar(mApplication, Snackbar.UNDO,   decor, mFab);

        mModeManager = new ModeManager(mApplication, mDrawerLayout, mFab, mActivityHeader,
                rlContextualToolbar, contextualToolbar, mNavigationIconClickedListener,
                mOnContextualMenuClickedListener, mRecyclerView, mThingsAdapter);
        mApplication.setModeManager(mModeManager);
    }

    @Override
    protected void initUI() {
        DisplayUtil.darkStatusBarForMIUI(this);

        if (VersionUtil.hasKitKatApi()) {
            View statusbar = findViewById(R.id.view_status_bar);
            DrawerLayout.LayoutParams dlp1 = (DrawerLayout.LayoutParams)
                    statusbar.getLayoutParams();
            dlp1.height = DisplayUtil.getStatusbarHeight(this);
            statusbar.requestLayout();

            FrameLayout fl = (FrameLayout) findViewById(R.id.fl_things);
            DrawerLayout.LayoutParams dlp2 = (DrawerLayout.LayoutParams) fl.getLayoutParams();
            dlp2.setMargins(0, dlp1.height, 0, 0);
            fl.requestLayout();

            mDrawerLayout.setFitsSystemWindows(false);
            mDrawer.setFitsSystemWindows(false);
        }

        mDrawerLayout.setScrimColor(Color.parseColor("#60000000"));

        mSpan = DisplayUtil.isTablet(this) ? 3 : 2;
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mSpan++;
        }

        mStaggeredGridLayoutManager = new StaggeredGridLayoutManager(
                mSpan, StaggeredGridLayoutManager.VERTICAL);
        mRecyclerView.setLayoutManager(mStaggeredGridLayoutManager);
        // post here to make sure that animation plays well and completely
        mRecyclerView.postDelayed(initRecyclerViewRunnable, 240);

        MenuItem itemAll = mDrawer.getMenu().findItem(R.id.drawer_underway);
        itemAll.setCheckable(true);
        itemAll.setChecked(true);
        mPreviousItem = itemAll;

        mActivityHeader.updateText();
        if (mModeManager.getCurrentMode() == ModeManager.SELECTING) {
            mModeManager.showContextualToolbar(false);
            mFab.shrinkWithoutAnim();
        }

        if (!VersionUtil.hasLollipopApi()) {
            DisplayUtil.setSelectionHandlersColor(mEtSearch, -1979711488);
        }
    }

    @Override
    protected void setActionbar() {
        setSupportActionBar(mActionbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(null);
            getSupportActionBar().setHomeButtonEnabled(true);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    protected void setEvents() {
        mViewInsideActionbar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    mRecyclerView.smoothScrollToPosition(0);
                    return true;
                }
                return false;
            }
        });

        mDrawer.getHeaderView(0).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(ThingsActivity.this, StatisticActivity.class);
                startActivity(intent);
                mDrawerLayout.closeDrawer(GravityCompat.START);
            }
        });

        setFabEvents();
        setRecyclerViewEvents();
        setUndoSnackbarEvents();
        setSearchEvents();
    }

    private void setFabEvents() {
        mFab.attachToRecyclerView(mRecyclerView);
        mFab.bindSnackbars(mNormalSnackbar, mUndoSnackbar, mHabitSnackbar);
        final int statusBarHeight = DisplayUtil.getStatusbarHeight(this);
        mFab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mIsRevealAnimPlaying = true;
                dismissSnackbars();
                mFab.setClickable(false);
                int[] location = new int[2];
                mFab.getLocationOnScreen(location);
                location[0] += mFab.getWidth() / 2;
                location[1] += mFab.getHeight() / 2;
                if (!VersionUtil.hasKitKatApi()) {
                    location[1] -= statusBarHeight; // I don't know why but 25 is good.
                }

                final Intent intent = new Intent(ThingsActivity.this, DetailActivity.class);
                intent.putExtra(Definitions.Communication.KEY_SENDER_NAME,
                        ThingsActivity.class.getName());
                intent.putExtra(Definitions.Communication.KEY_DETAIL_ACTIVITY_TYPE,
                        DetailActivity.CREATE);
                intent.putExtra(Definitions.Communication.KEY_ID, -1L);
                intent.putExtra(Definitions.Communication.KEY_COLOR, mFabRippleColor);

                mViewToReveal.setBackgroundColor(mFabRippleColor);
                mViewToReveal.setVisibility(View.VISIBLE);
                mRevealLayout.setVisibility(View.VISIBLE);

                mRevealLayout.show(location[0], location[1]);
                mFab.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        startActivityForResult(intent, Definitions.Communication.REQUEST_ACTIVITY_DETAIL);
                        overridePendingTransition(0, 0);
                    }
                }, 600);

                // change this value to prevent from flashing.
                final int delay = mApplication.hasDetailActivityRun() ? 960 : 1600;
                mFab.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        /**
                         * When user creates a new thing and returns ThingsActivity, he can see
                         * the result at once.
                         */
                        mRecyclerView.smoothScrollToPosition(0);
                        mIsRevealAnimPlaying = false;
                        mFab.setClickable(true);
                        mRevealLayout.setVisibility(View.INVISIBLE);
                        mViewToReveal.setVisibility(View.INVISIBLE);
                    }
                }, delay);
            }
        });
    }

    private void setRecyclerViewEvents() {
        mRecyclerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    if (mScrollNotCausedByFinger) {
                        mScrollNotCausedByFinger = false;
                    }
                    if (mThingsAdapter.isShouldThingsAnimWhenAppearing()) {
                        mThingsAdapter.setShouldThingsAnimWhenAppearing(false);
                    }
                    return true;
                }
                return false;
            }
        });
        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (!mScrollNotCausedByFinger) {
                    dismissSnackbars();
                    int[] positions = new int[mSpan];
                    mStaggeredGridLayoutManager.findFirstVisibleItemPositions(positions);
                    mActivityHeader.updateAll(positions[0], false);
                }
            }

            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                EdgeEffectUtil.forRecyclerView(recyclerView, Color.parseColor("#56000000"));
            }
        });

        mThingsTouchHelper = new ItemTouchHelper(new ThingsTouchCallback());
        mThingsTouchHelper.attachToRecyclerView(mRecyclerView);
    }

    private void setUndoSnackbarEvents() {
        mUndoSnackbar.setUndoListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int stateAfter = mUndoThings.get(0).getState();
                mScrollNotCausedByFinger = true;

                if (mUndoAll) {
                    mThingManager.undoUpdateStates(mUndoThings, mUndoPositions, mUndoLocations,
                            mStateToUndoFrom, stateAfter, true);
                    mUndoThings.clear();
                    mUndoPositions.clear();
                    mUndoLocations.clear();
                    if (mStateToUndoFrom == Thing.DELETED_FOREVER) {
                        mApplication.getThingsToDeleteForever().clear();
                    }

                    mThingsAdapter.notifyDataSetChanged();
                    mUndoAll = false;
                    updateUIAfterStateUpdated(stateAfter,
                            mRecyclerView.getItemAnimator().getChangeDuration(), true);
                } else if (!mUndoThings.isEmpty()) {
                    int size = mUndoThings.size();
                    Thing thing = mUndoThings.get(size - 1);
                    int position = mUndoPositions.get(size - 1);
                    long location = mUndoLocations.get(size - 1);
                    mUndoThings.remove(size - 1);
                    mUndoPositions.remove(size - 1);
                    mUndoLocations.remove(size - 1);

                    if (mStateToUndoFrom == Thing.DELETED_FOREVER) {
                        mApplication.getThingsToDeleteForever().remove(thing);
                    }

                    boolean change = mThingManager.updateState(
                            thing, position, location, mStateToUndoFrom, stateAfter, true, true);

                    if (change) {
                        mThingsAdapter.notifyItemChanged(1);
                        updateUIAfterStateUpdated(stateAfter,
                                mRecyclerView.getItemAnimator().getChangeDuration(), true);
                    } else {
                        mThingsAdapter.notifyItemInserted(position);
                        mRecyclerView.smoothScrollToPosition(position);
                        updateUIAfterStateUpdated(stateAfter,
                                mRecyclerView.getItemAnimator().getAddDuration(), true);
                    }
                }
                if (EverythingDoneApplication.isSearching) {
                    handleSearchResults();
                }
                if (mUndoThings.isEmpty()) {
                    dismissSnackbars();
                }
            }
        });
        mHabitSnackbar.setUndoListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mUndoHabitRecords.isEmpty()) {
                    int size = mUndoHabitRecords.size();
                    HabitRecord hr = mUndoHabitRecords.get(size - 1);
                    int position = mUndoPositions.get(size - 1);
                    mUndoHabitRecords.remove(size - 1);
                    mUndoPositions.remove(size - 1);

                    HabitDAO.getInstance(mApplication).undoFinishOneTime(hr);

                    if (!mUndoThings.isEmpty()) {
                        size = mUndoThings.size();
                        Thing thing = mUndoThings.get(size - 1);
                        mUndoThings.remove(size - 1);
                        mThingManager.update(thing.getType(), thing, position, false);
                        mThingManager.getThings().set(position, thing);
                    }

                    mThingsAdapter.notifyItemChanged(position);
                    mDrawerHeader.updateCompletionRate();

                    if (mUndoHabitRecords.isEmpty()) {
                        mHabitSnackbar.dismiss();
                    }
                }
            }
        });
    }

    private void setSearchEvents() {
        mEtSearch.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                dismissSnackbars();
            }
        });
        mEtSearch.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    if (mColorPicker.getPickedIndex() < 0) {
                        mColorPicker.pickForUI(0);
                    }
                    mRecyclerView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
                    KeyboardUtil.hideKeyboard(mEtSearch);
                    searchThings();
                    return true;
                }
                return false;
            }
        });
        mColorPicker.setPickedListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mRecyclerView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
                getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                mEtSearch.clearFocus();
                searchThings();
            }
        });
    }

    private void searchThings() {
        mThingManager.searchThings(mEtSearch.getText().toString(), mColorPicker.getPickedColor());
        mThingsAdapter.setShouldThingsAnimWhenAppearing(true);
        mThingsAdapter.notifyDataSetChanged();
        handleSearchResults();
    }

    private void setDrawer() {
        mDrawerHeader.updateAll();
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, mDrawerLayout,
                R.string.app_name, R.string.app_name) {
            @Override
            public void onDrawerOpened(View drawerView) {
                super.onDrawerOpened(drawerView);
                dismissSnackbars();
            }
        };
        mDrawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        mActionbar.setNavigationOnClickListener(mNavigationIconClickedListener);
        mDrawer.setNavigationItemSelectedListener(new NavigationView.OnNavigationItemSelectedListener() {

            @Override
            public boolean onNavigationItemSelected(MenuItem menuItem) {
                mDrawerLayout.closeDrawer(GravityCompat.START);
                if (!mPreviousItem.equals(menuItem)) {
                    int newLimit;
                    int id = menuItem.getItemId();
                    if (id == R.id.drawer_underway) {
                        newLimit = Definitions.LimitForGettingThings.ALL_UNDERWAY;
                    } else if (id == R.id.drawer_note) {
                        newLimit = Definitions.LimitForGettingThings.NOTE_UNDERWAY;
                    } else if (id == R.id.drawer_reminder) {
                        newLimit = Definitions.LimitForGettingThings.REMINDER_UNDERWAY;
                    } else if (id == R.id.drawer_habit) {
                        newLimit = Definitions.LimitForGettingThings.HABIT_UNDERWAY;
                    } else if (id == R.id.drawer_goal) {
                        newLimit = Definitions.LimitForGettingThings.GOAL_UNDERWAY;
                    } else if (id == R.id.drawer_finished) {
                        newLimit = Definitions.LimitForGettingThings.ALL_FINISHED;
                    } else if (id == R.id.drawer_deleted) {
                        newLimit = Definitions.LimitForGettingThings.ALL_DELETED;
                    } else {
                        Intent intent = null;
                        if (id == R.id.drawer_settings) {
                            intent = new Intent(ThingsActivity.this, SettingsActivity.class);
                        } else if (id == R.id.drawer_about) {
                            intent = new Intent(ThingsActivity.this, AboutActivity.class);
                        }
                        startActivity(intent);
                        return true;
                    }
                    menuItem.setCheckable(true);
                    menuItem.setChecked(true);
                    mPreviousItem.setChecked(false);
                    mPreviousItem = menuItem;
                    mRecyclerView.setVisibility(View.INVISIBLE);
                    mApplication.setLimit(newLimit, false);
                    invalidateOptionsMenu();
                    mRecyclerView.scrollToPosition(0);
                    mActivityHeader.reset(true);

                    mRecyclerView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mRecyclerView.setVisibility(View.VISIBLE);
                            mThingsAdapter.setShouldThingsAnimWhenAppearing(true);
                            mThingManager.loadThings();
                            mThingsAdapter.notifyDataSetChanged();
                        }
                    }, 360);

                    mActivityHeader.updateText();
                    mDrawerHeader.updateAll();
                    if (mApplication.getLimit() <= Definitions.LimitForGettingThings.GOAL_UNDERWAY) {
                        mFab.spread();
                    } else {
                        mFab.shrink();
                    }
                }
                return true;
            }
        });
    }

    private void handleUpdateStates(int stateAfter) {
        if (mThingManager.isThingsEmpty()) {
            return;
        }
        dismissSnackbars();

        List<Thing> things = mThingManager.getThings();
        List<Thing> thingsToDeleteForever = new ArrayList<>();
        if (stateAfter == Thing.DELETED_FOREVER) {
            thingsToDeleteForever = mApplication.getThingsToDeleteForever();
        }
        boolean containsHabitOrGoal = false;
        Thing thing;
        int size = things.size();
        if (mModeManager.getCurrentMode() == ModeManager.SELECTING) {
            for (int i = 1; i < size; i++) {
                thing = things.get(i);
                if (thing.isSelected()) {
                    int type = thing.getType();
                    if (Thing.isImportantType(type)) {
                        containsHabitOrGoal = true;
                    }
                    thing.setSelected(false);
                    SystemNotificationUtil.cancelNotification(thing.getId(), type, mApplication);
                    mUndoThings.add(thing);
                    mUndoLocations.add(thing.getLocation());
                    if (stateAfter == Thing.DELETED_FOREVER) {
                        thingsToDeleteForever.add(new Thing(thing));
                    }
                }
            }
        } else {
            for (int i = 1; i < size; i++) {
                thing = things.get(i);
                int type = thing.getType();
                if (Thing.isImportantType(type)) {
                    containsHabitOrGoal = true;
                }
                SystemNotificationUtil.cancelNotification(thing.getId(), type, mApplication);
                mUndoThings.add(thing);
                mUndoLocations.add(thing.getLocation());
                if (stateAfter == Thing.DELETED_FOREVER) {
                    thingsToDeleteForever.add(new Thing(thing));
                }
            }
        }

        int stateBefore = things.get(1).getState();
        if (containsHabitOrGoal && stateBefore == Thing.UNDERWAY && mUndoThings.size() > 1) {
            // if mUntoThing.size == 1, it means that it is user's decision
            alertForHabitGoal(stateBefore, stateAfter);
        } else {
            handleUpdateStates(stateBefore, stateAfter);
        }
    }

    private void alertForHabitGoal(final int stateBefore, final int stateAfter) {
        ThreeActionsAlertDialogFragment df = new ThreeActionsAlertDialogFragment();
        df.setTitleColor(mFabRippleColor);
        df.setContinueColor(mFabRippleColor);
        df.setTitle(getString(R.string.alert_continue));
        df.setContent(getString(R.string.alert_find_habit_goal));
        df.setFirstAction(getString(R.string.continue_get_rid_of_habit_goal));
        df.setSecondAction(getString(R.string.continue_for_alert));
        df.setOnClickListener(new ThreeActionsAlertDialogFragment.OnClickListener() {
            @Override
            public void onFirstClicked() {
                List<Thing> thingsToDelete = mApplication.getThingsToDeleteForever();
                Iterator<Thing> iterator = mUndoThings.iterator();
                while (iterator.hasNext()) {
                    Thing thing = iterator.next();
                    if (Thing.isImportantType(thing.getType())) {
                        iterator.remove();
                        mUndoLocations.remove(thing.getLocation());
                        thingsToDelete.remove(thing);
                    }
                }
                handleUpdateStates(stateBefore, stateAfter);
            }

            @Override
            public void onSecondClicked() {
                handleUpdateStates(stateBefore, stateAfter);
            }

            @Override
            public void onThirdClicked() {
                List<Thing> thingsToDelete = mApplication.getThingsToDeleteForever();
                for (Thing undoThing : mUndoThings) {
                    thingsToDelete.remove(undoThing);
                }
                mUndoThings.clear();
                mUndoLocations.clear();
            }
        });
        df.show(getFragmentManager(), ThreeActionsAlertDialogFragment.TAG);
    }

    private void handleUpdateStates(int stateBefore, int stateAfter) {
        if (mUndoThings.size() == 0) {
            return;
        }
        mStateToUndoFrom = stateAfter;
        mUndoPositions = mThingManager.updateStates(mUndoThings, stateBefore, stateAfter, true);
        mThingsAdapter.notifyDataSetChanged();
        mUndoAll = true;
        if (!mUndoThings.isEmpty()) {
            updateUIAfterStateUpdated(stateAfter,
                    mRecyclerView.getItemAnimator().getRemoveDuration(), true);
        }
        if (EverythingDoneApplication.isSearching) {
            handleSearchResults();
        }
    }

    private void updateUIAfterStateUpdated(int stateAfter, long timeDelay, boolean shouldForceBackNormalMode) {
        mActivityHeader.updateText();
        mDrawerHeader.updateCompletionRate();

        mScrollNotCausedByFinger = true;
        mRecyclerView.postDelayed(new Runnable() {
            @Override
            public void run() {
                int[] positions = new int[mSpan];
                mStaggeredGridLayoutManager.findFirstVisibleItemPositions(positions);
                mActivityHeader.updateAll(positions[0], true);
                mScrollNotCausedByFinger = false;
            }
        }, timeDelay);

        if (mModeManager.getCurrentMode() == ModeManager.SELECTING) {
            updateSelectingUi(shouldForceBackNormalMode);
            if (shouldForceBackNormalMode) {
                showUndoSnackbar(stateAfter);
            }
        } else {
            showUndoSnackbar(stateAfter);
        }
    }

    private void updateSelectingUi(boolean shouldForceToBackNormalMode) {
        if (shouldForceToBackNormalMode) {
            mModeManager.backNormalMode(0);
        } else {
            List<Thing> things = mThingManager.getThings();
            if (things.size() == 1) {
                if (EverythingDoneApplication.isSearching) {
                    mModeManager.backNormalMode(0);
                }
            } else {
                if (things.get(1).getType() >= Thing.NOTIFICATION_UNDERWAY) {
                    mModeManager.backNormalMode(0);
                }
                mModeManager.updateSelectedCount();
            }
        }
    }

    private void showUndoSnackbar(int stateAfter) {
        String[] messages = getUndoMessages(stateAfter, mUndoThings.size());
        mUndoSnackbar.setMessage(messages[0]);
        mUndoSnackbar.setUndoText(messages[1]);
        mUndoSnackbar.show();
    }

    private void showHabitSnackbar() {
        String finished = getString(R.string.sb_finish_habit);
        mHabitSnackbar.setMessage(
                finished + " " + LocaleUtil.getTimesStr(mApplication, mUndoHabitRecords.size()));
        mHabitSnackbar.setUndoText(R.string.sb_undo_finish);
        mHabitSnackbar.show();
    }

    private String[] getUndoMessages(int stateAfter, int count) {
        StringBuilder sb = new StringBuilder();
        String updateStr = "";
        String undoStr = "";
        int limit = mApplication.getLimit();
        switch (stateAfter) {
            case Thing.UNDERWAY:
                updateStr = getString(R.string.sb_underway);
                if (limit <= Definitions.LimitForGettingThings.GOAL_UNDERWAY) {
                    updateStr = getString(R.string.sb_finish);
                    undoStr = getString(R.string.sb_undo_finish);
                } else if (limit == Definitions.LimitForGettingThings.ALL_FINISHED) {
                    undoStr = getString(R.string.sb_undo_underway);
                } else {
                    undoStr = getString(R.string.sb_undo);
                }
                break;
            case Thing.FINISHED:
                updateStr = getString(R.string.sb_finish);
                if (limit <= Definitions.LimitForGettingThings.GOAL_UNDERWAY) {
                    undoStr = getString(R.string.sb_undo_finish);
                } else {
                    undoStr = getString(R.string.sb_undo);
                }
                break;
            case Thing.DELETED:
                updateStr = getString(R.string.sb_delete);
                undoStr = getString(R.string.sb_undo);
                break;
            case Thing.DELETED_FOREVER:
                updateStr = getString(R.string.sb_delete_forever);
                undoStr = getString(R.string.sb_undo);
                break;
        }
        if (LocaleUtil.isChinese(mApplication)) {
            sb.append(updateStr).append(" ").append(count)
                    .append(" ").append(getString(R.string.a_thing));
        } else if (LocaleUtil.isEnglish(mApplication)) {
            String str = mApplication.getString(R.string.a_thing);
            if (count > 1) {
                str += "s";
            }
            sb.append(count).append(" ").append(str).append(" ").append(updateStr);
        }
        return new String[] { sb.toString(), undoStr };
    }

    private void dismissSnackbars() {
        if (mNormalSnackbar.isShowing()) {
            mNormalSnackbar.dismiss();
        }
        if (mUndoSnackbar.isShowing()) {
            mUndoSnackbar.dismiss();
        }
        if (mHabitSnackbar.isShowing()) {
            mHabitSnackbar.dismiss();
        }

        mUndoThings.clear();
        mUndoPositions.clear();
        mUndoLocations.clear();
        mUndoHabitRecords.clear();
        mUndoAll = false;
        mThingManager.clearLists();

        mApplication.releaseResourcesAfterDeleteForever();
    }

    private void toggleSearching() {
        dismissSnackbars();
        final boolean toNormal = EverythingDoneApplication.isSearching;
        if (toNormal) {
            mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
            mViewInsideActionbar.setVisibility(View.VISIBLE);
            mColorPicker.pickForUI(-1);

            mEtSearch.setEnabled(false);
            mEtSearch.animate().alpha(0).setDuration(160);

            mRecyclerView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
            mRecyclerView.scrollToPosition(0);

            mActivityHeader.reset(false);
            mRecyclerView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mActivityHeader.setShouldListenToScroll(true);
                }
            }, 160);
            if (mApplication.getLimit() <= Definitions.LimitForGettingThings.NOTE_UNDERWAY) {
                mFab.spread();
            }
            mApplication.setLimit(mApplication.getLimit(), true);
            mActivityHeader.updateText();
            mDrawerHeader.updateCompletionRate();
            mThingsAdapter.setShouldThingsAnimWhenAppearing(true);
            handleSearchResults();
        } else {
            mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
            mViewInsideActionbar.setVisibility(View.GONE);
            mEtSearch.setEnabled(true);
            mEtSearch.setText("");
            KeyboardUtil.showKeyboard(mEtSearch);
            mEtSearch.animate().alpha(1.0f).setDuration(160);

            mRecyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
            mRecyclerView.scrollBy(0, Integer.MIN_VALUE);

            mActivityHeader.setShouldListenToScroll(false);
            mActivityHeader.hideTitles();
            mActivityHeader.showActionbarShadow(1.0f);
            mFab.shrink();

            mThingManager.getThings().clear();
        }
        mThingsAdapter.notifyDataSetChanged();
        EverythingDoneApplication.isSearching = !toNormal;
        DisplayUtil.playDrawerToggleAnim((DrawerArrowDrawable) mActionbar.getNavigationIcon());
        invalidateOptionsMenu();
    }

    private void handleSearchResults() {
        if (mThingManager.getThings().size() == 1) {
            mTvNoResult.setVisibility(View.VISIBLE);
            mRevealLayout.setVisibility(View.VISIBLE);
            mTvNoResult.animate().alpha(1f).setDuration(360);
        } else {
            mTvNoResult.animate().alpha(0).setDuration(160);
            mRevealLayout.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mRevealLayout.setVisibility(View.INVISIBLE);
                    mTvNoResult.setVisibility(View.INVISIBLE);
                }
            }, 160);
        }
    }

    private void celebrateHabitGoalFinish(Thing thing, int stateBefore, int stateAfter) {
        if (stateBefore != Thing.UNDERWAY || stateAfter != Thing.FINISHED) {
            return;
        }
        int type = thing.getType();
        if (type == Thing.HABIT || type == Thing.GOAL) {
            long id = thing.getId();
            int color = thing.getColor();
            final AlertDialogFragment adf = new AlertDialogFragment();
            adf.setShowCancel(false);
            adf.setTitle(getString(R.string.congratulations));
            adf.setTitleColor(color);
            adf.setConfirmColor(color);
            String content;
            if (type == Thing.HABIT) {
                Habit habit = HabitDAO.getInstance(mApplication).getHabitById(id);
                content = habit.getCelebrationText(mApplication);
            } else {
                Reminder reminder = ReminderDAO.getInstance(mApplication).getReminderById(id);
                content = reminder.getCelebrationText(mApplication);
            }
            adf.setContent(content);
            adf.show(getFragmentManager(), AlertDialogFragment.TAG);
        }
    }

    class OnNavigationIconClickedListener implements View.OnClickListener {

        @Override
        public void onClick(View v) {
            if (EverythingDoneApplication.isSearching) {
                toggleSearching();
            } else {
                mDrawerLayout.openDrawer(GravityCompat.START);
                dismissSnackbars();
            }
        }
    }

    class OnThingTouchedListener implements ThingsAdapter.OnItemTouchedListener {
        @Override
        public boolean onItemTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (mScrollNotCausedByFinger) {
                    mScrollNotCausedByFinger = false;
                }
                if (mThingsAdapter.isShouldThingsAnimWhenAppearing()) {
                    mThingsAdapter.setShouldThingsAnimWhenAppearing(false);
                }
            }
            return false;
        }

        @Override
        public void onItemClick(View v, int position) {
            if (position <= 0 || mIsRevealAnimPlaying) {
                return;
            }
            dismissSnackbars();

            List<Thing> things = mThingManager.getThings();
            if (position >= things.size()) {
                return;
            }

            if (mModeManager.getCurrentMode() != ModeManager.SELECTING) {
                if (mRecyclerView.getItemAnimator().isRunning()) {
                    return;
                }

                final Intent intent = new Intent(ThingsActivity.this, DetailActivity.class);
                intent.putExtra(Definitions.Communication.KEY_SENDER_NAME,
                        ThingsActivity.class.getName());
                intent.putExtra(Definitions.Communication.KEY_DETAIL_ACTIVITY_TYPE,
                        DetailActivity.UPDATE);
                intent.putExtra(Definitions.Communication.KEY_ID,
                        things.get(position).getId());
                intent.putExtra(Definitions.Communication.KEY_POSITION, position);

                ActivityOptionsCompat transition = ActivityOptionsCompat.makeScaleUpAnimation(
                        v, v.getWidth() / 2, v.getHeight() / 2, 0, 0);
                ActivityCompat.startActivityForResult(
                        ThingsActivity.this, intent, Definitions.Communication.REQUEST_ACTIVITY_DETAIL,
                        transition.toBundle());
            } else {
                Thing thing = things.get(position);
                thing.setSelected(!thing.isSelected());
                mThingsAdapter.notifyItemChanged(position);
                mModeManager.updateSelectedCount();
            }
        }

        @Override
        public boolean onItemLongClick(View v, final int position) {
            if (position == 0) {
                return false;
            }
            dismissSnackbars();

            List<Thing> things = mThingManager.getThings();
            if (position >= things.size()) {
                return false;
            }

            if (mModeManager.getCurrentMode() == ModeManager.NORMAL &&
                    things.get(position).getType() <= Thing.NOTIFICATION_GOAL) {
                mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
                if (mApplication.getLimit() <= Definitions.LimitForGettingThings.GOAL_UNDERWAY) {
                    mModeManager.toMovingMode(position);
                    mRecyclerView.post(new Runnable() {
                        @Override
                        public void run() {
                            mThingsTouchHelper.startDrag(
                                    mRecyclerView.findViewHolderForAdapterPosition(position));
                        }
                    });
                } else {
                    things.get(position).setSelected(true);
                    mModeManager.toSelectingMode(position);
                }
            } else {
                mModeManager.backNormalMode(position);
            }
            return true;
        }
    }

    class ThingsTouchCallback extends ItemTouchHelper.Callback {

        private boolean swiped = false;
        private boolean moved = false;
        private boolean firstMove = true;
        private int finalFrom, finalTo;

        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN |
                    ItemTouchHelper.START | ItemTouchHelper.END;
            int swipeFlags = ItemTouchHelper.START | ItemTouchHelper.END;
            return makeMovementFlags(dragFlags, swipeFlags);
        }

        @Override
        public boolean onMove(RecyclerView recyclerView,
                              RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
            final int from = viewHolder.getAdapterPosition();
            final int to = target.getAdapterPosition();
            if (from != 0 && to != 0) {
                mThingManager.move(from, to);
                mThingsAdapter.notifyItemMoved(from, to);
                if (firstMove) {
                    finalFrom = from;
                    firstMove = false;
                }
                finalTo = to;
                moved = true;
                return true;
            }
            return false;
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return mApplication.getLimit() <= Definitions.LimitForGettingThings.GOAL_UNDERWAY
                    && mModeManager.getCurrentMode() != ModeManager.SELECTING
                    && !mThingManager.isThingsEmpty();
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
            int position = viewHolder.getAdapterPosition();
            if (position < 0) {
                return;
            }

            List<Thing> things = mThingManager.getThings();
            if (position >= things.size()) {
                return;
            }

            Thing thingToSwipe = things.get(position);
            int thingType = thingToSwipe.getType();
            if (thingType > Thing.NOTIFICATION_GOAL) {
                return;
            }

            if (mUndoSnackbar.isShowing()) {
                if (mStateToUndoFrom != Thing.FINISHED || thingType == Thing.HABIT) {
                    dismissSnackbars();
                }
            }

            long id = thingToSwipe.getId();
            SystemNotificationUtil.cancelNotification(id, thingType, mApplication);

            if (thingType == Thing.HABIT) {
                HabitDAO habitDAO = HabitDAO.getInstance(mApplication);
                Habit habit = habitDAO.getHabitById(id);
                if (habit != null) {
                    if (habit.allowFinish()) {
                        if (!mUndoHabitRecords.isEmpty()) {
                            HabitRecord hr = mUndoHabitRecords.get(mUndoHabitRecords.size() - 1);
                            if (hr.getHabitId() != id) {
                                dismissSnackbars();
                            }
                        }
                        String thingContent = thingToSwipe.getContent();
                        if (CheckListHelper.isCheckListStr(thingContent)) {
                            mUndoThings.add(thingToSwipe);
                            Thing thing = new Thing(thingToSwipe);
                            thing.setContent(thingContent.replaceAll(
                                    CheckListHelper.SIGNAL + "1",
                                    CheckListHelper.SIGNAL + "0"));
                            mThingManager.update(thingType, thing, position, false);
                            things.set(position, thing);
                        }
                        mUndoHabitRecords.add(habitDAO.finishOneTime(habit));
                        mUndoPositions.add(position);
                        showHabitSnackbar();
                    } else {
                        dismissSnackbars();
                        if (habit.getRecord().isEmpty() && habit.getRemindedTimes() == 0) {
                            mNormalSnackbar.setMessage(R.string.sb_cannot_finish_habit_first_time);
                        } else {
                            mNormalSnackbar.setMessage(R.string.sb_cannot_finish_habit_more_times);
                        }
                        mNormalSnackbar.show();
                    }
                }
                mThingsAdapter.notifyItemChanged(position);
                mDrawerHeader.updateCompletionRate();
                return;
            }

            int state = thingToSwipe.getState();
            long location = thingToSwipe.getLocation();
            mUndoThings.add(thingToSwipe);
            mUndoPositions.add(position);
            mUndoLocations.add(location);
            mStateToUndoFrom = Thing.FINISHED;

            boolean changed = mThingManager.updateState(thingToSwipe, position, location,
                    state, Thing.FINISHED, false, true);
            mScrollNotCausedByFinger = true;

            if (EverythingDoneApplication.isSearching) {
                if (things.size() == 1) {
                    handleSearchResults();
                }
            }

            celebrateHabitGoalFinish(thingToSwipe, state, Thing.FINISHED);
            if (changed) {
                mThingsAdapter.notifyItemChanged(position);
                updateUIAfterStateUpdated(Thing.FINISHED,
                        mRecyclerView.getItemAnimator().getChangeDuration(), true);
            } else {
                mThingsAdapter.notifyItemRemoved(position);
                updateUIAfterStateUpdated(Thing.FINISHED,
                        mRecyclerView.getItemAnimator().getRemoveDuration(), true);
            }
            swiped = true;
        }

        @Override
        public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            int position = viewHolder.getAdapterPosition();
            if (moved) {
                mThingManager.updateLocations(finalFrom, finalTo);
                moved = false;
                firstMove = true;
                if (finalFrom == finalTo) {
                    mModeManager.toSelectingMode(position);
                } else {
                    mModeManager.backNormalMode(position);
                }
            } else {
                if (!swiped) {
                    mModeManager.toSelectingMode(position);
                } else {
                    swiped = false;
                }
            }
        }

        @Override
        public void onChildDraw(Canvas c, RecyclerView recyclerView,
                                RecyclerView.ViewHolder viewHolder, float dX, float dY,
                                int actionState, boolean isCurrentlyActive) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                int displayWidth = DisplayUtil.getDisplaySize(mApplication).x;
                View v = viewHolder.itemView;
                if (dX < 0) {
                    v.setAlpha(1.0f + dX / v.getRight());
                } else {
                    v.setAlpha(1.0f - dX / (displayWidth - v.getLeft()));
                }
                swiped = dX != 0;
            }
        }
    }

    class OnContextualMenuClickedListener implements OnMenuItemClickListener {
        @Override
        public boolean onMenuItemClick(MenuItem item) {
            switch (item.getItemId()) {
                case R.id.act_share:
                    SendInfoHelper.shareThing(
                            ThingsActivity.this, mThingManager.getSelectedThing());
                    break;
                case R.id.act_select_all:
                    mThingManager.setSelectedTo(true);
                    mThingsAdapter.notifyDataSetChanged();
                    break;
                case R.id.act_delete_selected:
                    handleUpdateStates(Thing.DELETED);
                    break;
                case R.id.act_finish_selected:
                    handleUpdateStates(Thing.FINISHED);
                    break;
                case R.id.act_restore_selected:
                    handleUpdateStates(Thing.UNDERWAY);
                    break;
                case R.id.act_delete_selected_forever:
                    handleUpdateStates(Thing.DELETED_FOREVER);
                    break;
            }
            mModeManager.updateSelectedCount();
            return false;
        }
    }
}