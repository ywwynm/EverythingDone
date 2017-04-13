package com.ywwynm.everythingdone.activities;

import android.Manifest;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.design.widget.NavigationView;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.graphics.drawable.DrawerArrowDrawable;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.support.v7.widget.Toolbar;
import android.support.v7.widget.Toolbar.OnMenuItemClickListener;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.FrequentSettings;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter;
import com.ywwynm.everythingdone.adapters.ThingsAdapter;
import com.ywwynm.everythingdone.adapters.ThingsAdapterWrapper;
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.fragments.AlertDialogFragment;
import com.ywwynm.everythingdone.fragments.LongTextDialogFragment;
import com.ywwynm.everythingdone.fragments.ThreeActionsAlertDialogFragment;
import com.ywwynm.everythingdone.helpers.AlarmHelper;
import com.ywwynm.everythingdone.helpers.AppUpdateHelper;
import com.ywwynm.everythingdone.helpers.AuthenticationHelper;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.helpers.SendInfoHelper;
import com.ywwynm.everythingdone.helpers.ThingDoingHelper;
import com.ywwynm.everythingdone.helpers.ThingExporter;
import com.ywwynm.everythingdone.managers.ModeManager;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.DoingRecord;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.HabitRecord;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.model.ThingsCounts;
import com.ywwynm.everythingdone.permission.PermissionUtil;
import com.ywwynm.everythingdone.permission.SimplePermissionCallback;
import com.ywwynm.everythingdone.services.DoingService;
import com.ywwynm.everythingdone.utils.DeviceUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.EdgeEffectUtil;
import com.ywwynm.everythingdone.utils.FileUtil;
import com.ywwynm.everythingdone.utils.KeyboardUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;
import com.ywwynm.everythingdone.views.ActivityHeader;
import com.ywwynm.everythingdone.views.DrawerHeader;
import com.ywwynm.everythingdone.views.FloatingActionButton;
import com.ywwynm.everythingdone.views.InterceptTouchCardView;
import com.ywwynm.everythingdone.views.Snackbar;
import com.ywwynm.everythingdone.views.ThingsStaggeredLayoutManager;
import com.ywwynm.everythingdone.views.pickers.ColorPicker;
import com.ywwynm.everythingdone.views.reveal.RevealLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

public final class ThingsActivity extends EverythingDoneBaseActivity {

    public static final String TAG = "ThingsActivity";

    private App mApp;

    private ThingManager mThingManager;

    private RevealLayout mRevealLayout;
    private View         mViewToReveal;
    private TextView     mTvNoResult;

    private Toolbar     mActionbar;
    private View        mViewInsideActionbar;

    private EditText    mEtSearch;
    private ColorPicker mColorPicker;

    private ModeManager mModeManager;

    private DrawerLayout   mDrawerLayout;
    private NavigationView mDrawer;
    private DrawerHeader   mDrawerHeader;
    private MenuItem       mPreviousItem;

    private ActivityHeader mActivityHeader;

    private FloatingActionButton mFab;

    private RecyclerView                  mRecyclerView;
    private ThingsAdapterWrapper mAdapter;
    private ItemTouchHelper               mThingsTouchHelper;
    private ThingsStaggeredLayoutManager mStaggeredGridLayoutManager;

    private int mSpan;

    private Snackbar          mNormalSnackbar;
    private Snackbar          mUndoSnackbar;
    private Snackbar          mHabitSnackbar;
    private List<Thing>       mUndoThings;
    private List<Integer>     mUndoPositions;
    private List<Long>        mUndoLocations;
    private List<HabitRecord> mUndoHabitRecords;
    private HashSet<Long>     mThingsIdsToUpdateWidget;
    private boolean           mUndoAll;
    private int               mStateToUndoFrom;

    /**
     * Used to know whether scrolling of {@link ThingsActivity.mRecyclerView}
     * is caused by swipe-to-dismiss or user's touch event.
     * See {@link ThingsActivity#setRecyclerViewEvents()} for details.
     */
    private boolean mScrollCausedByFinger = true;

    /**
     * Used to know whether reveal animation for entering {@link DetailActivity} with type
     * {@link DetailActivity.CREATE} is playing or not. Guarantee that kind of animation won't
     * be annoyed by other operations which can influence UI.
     */
    private boolean mIsRevealAnimPlaying = false;

    private boolean mCanSeeUi = false;
    private boolean mUpdateMainUiInOnResume = true;

    private Runnable initRecyclerViewRunnable = new Runnable() {
        @Override
        public void run() {
            if (mAdapter.getItemCount() <=
                    ThingsCounts.getInstance(mApp).getThingsCountForActivityHeader(mApp.getLimit())) {
                mThingManager.loadThings();
            }
            mAdapter.setShouldThingsAnimWhenAppearing(true);
            mAdapter.attachToRecyclerView(mRecyclerView);
            mStaggeredGridLayoutManager = new ThingsStaggeredLayoutManager(
                    mSpan, StaggeredGridLayoutManager.VERTICAL);
            mRecyclerView.setLayoutManager(mStaggeredGridLayoutManager);
        }
    };

    private Intent mRemoteIntent;

    private BroadcastReceiver mUpdateUiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, final Intent intent) {
            String mRemoteIntentInfo = "mRemoteIntent[null]";
            if (mRemoteIntent != null) {
                mRemoteIntentInfo = "mRemoteIntent.resultCode[" +
                        mRemoteIntent.getIntExtra(Def.Communication.KEY_RESULT_CODE,
                                Def.Communication.RESULT_NO_UPDATE) + "]";
            }
            Log.i(TAG, "UPDATE_MAIN_UI broadcast received, "
                    + "canSeeThingsActivity[" + mCanSeeUi + "], "
                    + mRemoteIntentInfo);
            if (!mCanSeeUi) {
                if (mRemoteIntent != null) {
                    updateMainUi(mRemoteIntent);
                    mDrawerLayout.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mRemoteIntent = intent;
                        }
                    }, 600);
                } else {
                    mRemoteIntent = intent;
                }
                return;
            }
            updateMainUi(intent);
        }
    };

    private boolean mShouldCloseDrawer = false;

    private boolean mDontPickSearchColor = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setDrawer();

        IntentFilter filter = new IntentFilter(
                Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI);
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
        App.setSomethingUpdatedSpecially(false);
        App.setJustNotifyAll(false);
        if (App.isSearching) {
            App.isSearching = false; // maybe we searched in multi-window mode and quit it later
        }

        AppUpdateHelper.getInstance(this).showInfo(this);

        tryToShowFeedbackErrorDialog();

        Intent intent = getIntent();
        if (intent != null) {
            int limit = intent.getIntExtra(Def.Communication.KEY_LIMIT, -1);
            if (limit != -1 && limit != App.getApp().getLimit()) {
                App.getApp().setLimit(limit, true);
            }
        }
    }

    private void tryToShowFeedbackErrorDialog() {
        File file = new File(getApplicationInfo().dataDir + "/files/" +
                Def.Meta.FEEDBACK_ERROR_FILE_NAME);
        if (file.exists()) {
            AlertDialogFragment adf = new AlertDialogFragment();
            int color = DisplayUtil.getRandomColor(this);
            adf.setTitleColor(color);
            adf.setConfirmColor(color);
            adf.setTitle(getString(R.string.app_crash_title));
            adf.setContent(getString(R.string.app_crash_content));
            adf.setConfirmText(getString(R.string.app_crash_send_now));
            adf.setConfirmListener(new AlertDialogFragment.ConfirmListener() {
                @Override
                public void onConfirm() {
                    tryToFeedbackError();
                }
            });
            adf.setCancelListener(new AlertDialogFragment.CancelListener() {
                @Override
                public void onCancel() {
                    deleteFeedbackFile();
                }
            });
            adf.show(getFragmentManager(), AlertDialogFragment.TAG);
        }
    }

    private void tryToFeedbackError() {
        doWithPermissionChecked(
                new SimplePermissionCallback(this) {
                    @Override
                    public void onGranted() {
                        SendInfoHelper.sendFeedback(ThingsActivity.this, true);
                        deleteFeedbackFile();
                    }
                },
                Def.Communication.REQUEST_PERMISSION_SEND_ERROR_FEEDBACK,
                Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    private void deleteFeedbackFile() {
        File file = new File(getApplicationInfo().dataDir + "/files/" +
                Def.Meta.FEEDBACK_ERROR_FILE_NAME);
        FileUtil.deleteFile(file);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        checkIfReminderHabitsCorrect();
    }

    private void checkIfReminderHabitsCorrect() {
        if (App.getApp().getLimit() >= Def.LimitForGettingThings.ALL_FINISHED) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                App app = App.getApp();
                List<Thing> things = ThingManager.getInstance(app).getThings();
                ReminderDAO reminderDAO = ReminderDAO.getInstance(app);
                HabitDAO habitDAO = HabitDAO.getInstance(app);
                // 在某些启用对齐唤醒的设备上，闹钟的时间可能会有偏差，这是正常的，不是状态错误，不需要提醒
                final long CHANCE = 6 * 60 * 1000L;
                for (Thing thing : things) {
                    long id = thing.getId();
                    @Thing.Type int type = thing.getType();
                    if (Thing.isReminderType(type)) {
                        Reminder reminder = reminderDAO.getReminderById(id);
                        if (reminder != null
                                && reminder.getNotifyTime() + CHANCE < System.currentTimeMillis()
                                && reminder.getState() == Reminder.UNDERWAY) {
                            alertReminderHabitIncorrect();
                            return;
                        }
                    } else if (type == Thing.HABIT) {
                        Habit habit = habitDAO.getHabitById(id);
                        if (habit != null
                                && habit.getMinHabitReminderTime() + CHANCE < System.currentTimeMillis()) {
                            alertReminderHabitIncorrect();
                            return;
                        }
                    }
                }
                // No Reminders/Habits/Goals are in "wrong state" but maybe we should still create
                // all alarms again since we can't know if alarms of things underway are active.
                AlarmHelper.createAllAlarms(app, false);
            }
        }).start();
    }

    private void alertReminderHabitIncorrect() {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                int color = DisplayUtil.getRandomColor(App.getApp());
                if (!LocaleUtil.isChinese(App.getApp())) {
                    LongTextDialogFragment ltdf = new LongTextDialogFragment();
                    ltdf.setAccentColor(color);
                    ltdf.setShowCancel(false);
                    ltdf.setTitle(getString(R.string.title_incorrect_reminder_habit));
                    ltdf.setContent(getString(R.string.content_incorrect_reminder_habit));
                    ltdf.setConfirmText(getString(R.string.act_reset_alarms));
                    ltdf.setConfirmListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            AlarmHelper.createAllAlarms(App.getApp(), true);
                            if (mAdapter != null) {
                                mAdapter.notifyDataSetChanged();
                            }
                        }
                    });
                    if (mCanSeeUi) {
                        ltdf.show(getFragmentManager(), LongTextDialogFragment.TAG);
                    }
                } else {
                    AlertDialogFragment adf = new AlertDialogFragment();
                    adf.setTitleColor(color);
                    adf.setConfirmColor(color);
                    adf.setShowCancel(false);
                    adf.setTitle(getString(R.string.title_incorrect_reminder_habit));
                    adf.setContent(getString(R.string.content_incorrect_reminder_habit));
                    adf.setConfirmText(getString(R.string.act_reset_alarms));
                    adf.setConfirmListener(new AlertDialogFragment.ConfirmListener() {
                        @Override
                        public void onConfirm() {
                            AlarmHelper.createAllAlarms(App.getApp(), true);
                            if (mAdapter != null) {
                                mAdapter.notifyDataSetChanged();
                            }
                        }
                    });
                    if (mCanSeeUi) {
                        adf.show(getFragmentManager(), AlertDialogFragment.TAG);
                    }
                }
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        mCanSeeUi = true;
        mAdapter.setShouldWaitNotify(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTaskDescription();

        String mRemoteIntentInfo = "mRemoteIntent[null]";
        if (mRemoteIntent != null) {
            mRemoteIntentInfo = "mRemoteIntent.resultCode[" +
                    mRemoteIntent.getIntExtra(Def.Communication.KEY_RESULT_CODE,
                            Def.Communication.RESULT_NO_UPDATE) + "]";
        }
        Log.i(TAG, "onResume called, mUpdateMainUiInOnResume[" + mUpdateMainUiInOnResume + "], "
                + "justNotifyAll[" + App.justNotifyAll() + "], "
                + mRemoteIntentInfo);

        if (mUpdateMainUiInOnResume) {
            if (App.justNotifyAll()) {
                mRecyclerView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        justNotifyAll();
                        mRemoteIntent = null;
                    }
                }, 540);
            } else if (mRemoteIntent != null) {
                updateMainUi(mRemoteIntent);
            } else {
                mAdapter.tryToNotify();
            }
        }

        mFab.setRippleColor(App.newThingColor);
        mActivityHeader.updateText();

        KeyboardUtil.hideKeyboard(getCurrentFocus());
    }

    @Override
    protected void onPause() {
        super.onPause();
        dismissSnackbars();
        mScrollCausedByFinger = false;

        KeyboardUtil.hideKeyboard(getCurrentFocus());
    }

    @Override
    protected void onStop() {
        super.onStop();
        mCanSeeUi = false;
        mAdapter.setShouldWaitNotify(true);
        mApp.deleteAttachmentFiles();
        if (mShouldCloseDrawer) {
            mDrawerLayout.closeDrawer(GravityCompat.START, false);
            mShouldCloseDrawer = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mUpdateUiReceiver);
        mApp.setDetailActivityRun(false);
        updateTaskDescription();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // launched from things list widget
        int newLimit = intent.getIntExtra(Def.Communication.KEY_LIMIT, -1);
        if (newLimit != -1 && mApp.getLimit() != newLimit) {
            if (mModeManager.getCurrentMode() != ModeManager.NORMAL) {
                mModeManager.backNormalMode(0);
            }
            if (App.isSearching) {
                toggleSearching(false);
            }
            changeToLimit(newLimit, true);
            KeyboardUtil.hideKeyboard(getWindow());
        }
    }

    private void updateTaskDescription() {
        if (DeviceUtil.hasLollipopApi()) {
            BitmapDrawable bmd = (BitmapDrawable) getDrawable(R.mipmap.ic_launcher);
            if (bmd != null) {
                Bitmap bm = bmd.getBitmap();
                setTaskDescription(new ActivityManager.TaskDescription(
                        getString(R.string.everythingdone), bm,
                        ContextCompat.getColor(this, R.color.bg_activity_things)));
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (App.isSearching) {
            getMenuInflater().inflate(R.menu.menu_search, menu);
            mColorPicker.setAnchor(menu.findItem(R.id.act_select_color).getIcon());
            mColorPicker.updateAnchor();
            return true;
        }

        int limit = mApp.getLimit();
        if (limit <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
            getMenuInflater().inflate(R.menu.menu_things_underway, menu);
            if (limit == Def.LimitForGettingThings.NOTE_UNDERWAY) {
                menu.findItem(R.id.act_sort_by_alarm).setVisible(false);
            }
        } else if (limit == Def.LimitForGettingThings.ALL_FINISHED) {
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
                toggleSearching(true);
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
            case R.id.act_sort_by_alarm:
                mRecyclerView.scrollToPosition(0);
                mActivityHeader.reset(true);
                mFab.showFromBottom();
                mThingManager.updateLocationsByAlarmTime();
                mAdapter.setShouldThingsAnimWhenAppearing(true);
                mAdapter.notifyDataSetChanged();
                AppWidgetHelper.updateAllThingsListAppWidgets(mApp);
                break;
            case R.id.act_select_color:
                dismissSnackbars();
                mColorPicker.show();
                break;
            default:break;
        }
        return super.onOptionsItemSelected(item);
    }

    private long lastClickBack = -1;

    @Override
    public void onBackPressed() {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
            mDrawerLayout.closeDrawer(GravityCompat.START);
        } else {
            if (mModeManager.getCurrentMode() == ModeManager.SELECTING) {
                mModeManager.backNormalMode(0);
                return;
            } else if (App.isSearching) {
                toggleSearching(true);
                return;
            }

            if (!FrequentSettings.getBoolean(Def.Meta.KEY_TWICE_BACK)) {
                mApp.setLimit(Def.LimitForGettingThings.ALL_UNDERWAY, true);
                super.onBackPressed();
            } else {
                if (lastClickBack == -1 || System.currentTimeMillis() - lastClickBack > 1600) {
                    lastClickBack = System.currentTimeMillis();
                    Toast.makeText(this, R.string.press_again_to_exit, Toast.LENGTH_SHORT).show();
                } else {
                    lastClickBack = -1;
                    mApp.setLimit(Def.LimitForGettingThings.ALL_UNDERWAY, true);
                    super.onBackPressed();
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        if (requestCode == Def.Communication.REQUEST_ACTIVITY_DETAIL) {
            updateMainUi(data, resultCode);
        } else if (requestCode == Def.Communication.REQUEST_ACTIVITY_SETTINGS) {
            if (resultCode == Def.Communication.RESULT_UPDATE_DRAWER_HEADER_DONE) {
                mDrawerHeader.updateDrawerHeader();
            }
        }
    }

    private void updateMainUi(Intent data) {
        int resultCode = data.getIntExtra(Def.Communication.KEY_RESULT_CODE,
                Def.Communication.RESULT_NO_UPDATE);
        updateMainUi(data, resultCode);
    }

    private void updateMainUi(final Intent data, int resultCode) {
        Log.i(TAG, "updateMainUi called, resultCode[" + resultCode + "]");
        mUpdateMainUiInOnResume = false;
        dismissSnackbars();
        switch (resultCode) {
            case Def.Communication.RESULT_JUST_NOTIFY_DATASET_CHANGED:
                mDrawerLayout.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        justNotifyAll();
                        mUpdateMainUiInOnResume = true;
                        mRemoteIntent = null;
                    }
                }, 560);
                break;
            case Def.Communication.RESULT_CREATE_THING_DONE:
                updateMainUiForCreateDone(data);
                break;
            case Def.Communication.RESULT_CREATE_BLANK_THING:
                mDrawerLayout.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mNormalSnackbar.setMessage(R.string.sb_cannot_be_blank);
                        mNormalSnackbar.show();
                        mUpdateMainUiInOnResume = true;
                        if (mCanSeeUi) {
                            App.setSomethingUpdatedSpecially(false);
                        }
                        mRemoteIntent = null;
                    }
                }, 560);
                break;
            case Def.Communication.RESULT_ABANDON_NEW_THING:
                mDrawerLayout.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mNormalSnackbar.setMessage(R.string.sb_abandon_new_thing);
                        mNormalSnackbar.show();
                        mUpdateMainUiInOnResume = true;
                        if (mCanSeeUi) {
                            App.setSomethingUpdatedSpecially(false);
                        }
                        mRemoteIntent = null;
                    }
                }, 560);
                break;
            case Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME:
                updateMainUiForUpdateSameType(data);
                break;
            case Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_DIFFERENT:
                updateMainUiForUpdateDifferentType(data);
                break;
            case Def.Communication.RESULT_UPDATE_THING_STATE_DIFFERENT:
                updateMainUiForUpdateDifferentState(data);
                break;
            case Def.Communication.RESULT_STICKY_THING_OR_CANCEL:
                updateMainUiForStickyOrCancel(data);
                break;
            case Def.Communication.RESULT_DOING_OR_CANCEL:
                updateMainUiForDoingOrCancel(data);
                break;
            case Def.Communication.RESULT_NO_UPDATE:
            default:
                if (mRemoteIntent == null) {
                    mUpdateMainUiInOnResume = true;
                    if (mCanSeeUi) {
                        App.setSomethingUpdatedSpecially(false);
                        App.setJustNotifyAll(false);
                    }
                } else {
                    int mRemoteIntentResultCode = mRemoteIntent.getIntExtra(
                            Def.Communication.KEY_RESULT_CODE, Def.Communication.RESULT_NO_UPDATE);
                    if (mRemoteIntentResultCode == Def.Communication.RESULT_NO_UPDATE) {
                        mRecyclerView.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                mAdapter.tryToNotify();
                                mUpdateMainUiInOnResume = true;
                                if (mCanSeeUi) {
                                    App.setSomethingUpdatedSpecially(false);
                                }
                                mRemoteIntent = null;
                            }
                        }, 540);
                    } else {
                        updateMainUi(mRemoteIntent, mRemoteIntentResultCode);
                    }
                }
                break;
        }
    }

    private void updateMainUiForCreateDone(final Intent data) {
        if (App.isSearching) {
            toggleSearching(false);
        }

        if (mApp.getLimit() != Def.LimitForGettingThings.ALL_UNDERWAY) {
            mFab.spread();
            mApp.setLimit(Def.LimitForGettingThings.ALL_UNDERWAY, true);
            invalidateOptionsMenu();
            mRecyclerView.scrollToPosition(0);
            mAdapter.setShouldThingsAnimWhenAppearing(false);
            mAdapter.notifyDataSetChanged();
            mActivityHeader.reset(false);
        }

        MenuItem underway = mDrawer.getMenu().getItem(0);
        mPreviousItem.setChecked(false);
        underway.setChecked(true);
        mPreviousItem = underway;

        final boolean createdDone = data.getBooleanExtra(
                Def.Communication.KEY_CREATED_DONE, false);
        final boolean justNotifyAll = App.justNotifyAll();
        final Thing thingToCreate = data.getParcelableExtra(
                Def.Communication.KEY_THING);

//        if (createdDone) {
//            mRecyclerView.postDelayed(new Runnable() {
//                @Override
//                public void run() {
//                    justNotifyAll();
//                    afterUpdateMainUiForCreateDone();
//                }
//            }, 560);
//            return;
//        }

        /**
         * Changed behavior here at 2016/7/26 because of multi create-type DetailActivities
         * entered from ongoing notification.
         *
         * <b>There must be 2 postDelays.</b>
         *
         * If the outside one was removed, which means {@link ThingManager#create(Thing, boolean, boolean)}
         * was called without any delay, and if {@link App#mLimit} is not
         * {@link Def.LimitForGettingThings.ALL_UNDERWAY}, we will call
         * {@link ThingsAdapter#notifyDataSetChanged()} very early(see code above, head part of this
         * function) so all created things will display before {@link #justNotifyAll()}
         * (I don't know why a thing can display because of notifyDataSetChanged called before its
         * creation). In this case, since we should call {@link #justNotifyAll()} indeed,
         * we can see {@link mRecyclerView} refreshes twice but without any visible change of things.
         *
         * If the inside one was removed and merged with outside one into a postDelayed(Runnable, 600),
         * since {@link #justNotifyAll()} called {@link ThingManager#loadThings()},
         * which get all things from database, and {@link ThingManager#create(Thing, boolean, boolean)}
         * will create a new thread to write a thing to database, which is not very reliable at
         * time/order, sometimes we cannot see all things even after
         * {@link ThingsAdapter#notifyDataSetChanged()} but they are truly existed.
         *
         * Sometimes I may think that I can remove outside postDelay and add a flag for that if we
         * have already called notifyDataSetChanged at head(If the flag is true, we won't call
         * justNotifyAll later). But we need item add animation of RecyclerView
         * ({@link ThingsAdapter#notifyItemInserted(int)}), as a result, I give up that thought.
         */
        mDrawerLayout.postDelayed(new Runnable() {
            @Override
            public void run() {
                final boolean change;
                if (createdDone) {
                    change = data.getBooleanExtra(Def.Communication.KEY_CALL_CHANGE, false);
                } else {
                    change = mThingManager.create(thingToCreate, true, true);
                }
                mRecyclerView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (justNotifyAll) {
                            justNotifyAll();
                        } else {
                            if (change) {
                                mAdapter.notifyItemChanged(1);
                            } else {
                                mAdapter.notifyItemInserted(
                                        mThingManager.getPositionToInsertNewThing());
                            }
                        }
                        afterUpdateMainUiForCreateDone();
                    }
                }, 300);
            }
        }, 300);
    }

    private void afterUpdateMainUiForCreateDone() {
        if (mModeManager.getCurrentMode() == ModeManager.SELECTING) {
            updateSelectingUi(false);
        }
        mActivityHeader.updateText();
        mDrawerHeader.updateTexts();
        mUpdateMainUiInOnResume = true;
        if (mCanSeeUi) {
            App.setSomethingUpdatedSpecially(false);
        }
        mRemoteIntent = null;
    }

    private void updateMainUiForUpdateSameType(final Intent data) {
        @Thing.Type final int typeBefore = data.getIntExtra(
                Def.Communication.KEY_TYPE_BEFORE, Thing.NOTE);
        final Thing contentUpdatedThing = data.getParcelableExtra(Def.Communication.KEY_THING);
        final boolean justNotifyAll = App.justNotifyAll();
        Log.i(TAG, "updateMainUiForUpdateSameType called, "
                + "typeBefore[" + typeBefore + "], "
                + "justNotifyAll[" + justNotifyAll + "]");
        mDrawerLayout.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "updateMainUiForUpdateSameType: delayed Runnable started.");
                @Thing.State int thingState = Thing.UNDERWAY;
                if (contentUpdatedThing != null) {
                    // update a finished thing is also possible -> update Reminder's state
                    thingState = contentUpdatedThing.getState();
                }
                if (justNotifyAll) {
                    justNotifyAll();
                } else if (Thing.isTypeStateMatchLimit(typeBefore, thingState, mApp.getLimit())) {
                    int pos = data.getIntExtra(
                            Def.Communication.KEY_POSITION, 1);
                    Log.i(TAG, "type and state match current limit, "
                            + "thing's position[" + pos + "], "
                            + "isSearching[" + App.isSearching + "]");
                    if (!App.isSearching) {
                        mAdapter.notifyItemChanged(pos);
                    } else {
                        List<Thing> things = mThingManager.getThings();
                        if (pos > 0 && pos < things.size()) {
                            Thing thing = things.get(pos);
                            if (thing.matchSearchRequirement(
                                    mEtSearch.getText().toString(),
                                    mColorPicker.getPickedColor())) {
                                mAdapter.notifyItemChanged(pos);
                            } else {
                                things.remove(pos);
                                mAdapter.notifyItemRemoved(pos);
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
                if (mCanSeeUi) {
                    App.setSomethingUpdatedSpecially(false);
                }
                mRemoteIntent = null;
            }
        }, 560);
    }

    private void updateMainUiForUpdateDifferentType(final Intent data) {
        final Thing thing = data.getParcelableExtra(
                Def.Communication.KEY_THING);
        @Thing.Type final int typeBefore = data.getIntExtra(
                Def.Communication.KEY_TYPE_BEFORE, Thing.NOTE);

        final boolean justNotifyAll = App.justNotifyAll();
        mDrawerLayout.postDelayed(new Runnable() {
            @Override
            public void run() {
                final int type = thing.getType();
                final int curLimit = mApp.getLimit();
                boolean limitMatched = Thing.isTypeStateMatchLimit(type, Thing.UNDERWAY, curLimit);

                if (justNotifyAll || limitMatched) {
                    justNotifyAll();
                } else if (Thing.isTypeStateMatchLimit(typeBefore, Thing.UNDERWAY, curLimit)) {
                    if (App.isSearching) {
                        mAdapter.notifyItemRemoved(data.getIntExtra(
                                Def.Communication.KEY_POSITION, 1));
                        handleSearchResults();
                    } else {
                        final boolean change = data.getBooleanExtra(
                                Def.Communication.KEY_CALL_CHANGE, false);
                        if (change) {
                            mAdapter.notifyItemChanged(1);
                        } else {
                            mAdapter.notifyItemRemoved(data.getIntExtra(
                                    Def.Communication.KEY_POSITION, 1));
                        }
                    }
                    if (mModeManager.getCurrentMode() == ModeManager.SELECTING) {
                        updateSelectingUi(false);
                    }
                }

                mDrawerHeader.updateCompletionRate();
                mUpdateMainUiInOnResume = true;
                if (mCanSeeUi) {
                    App.setSomethingUpdatedSpecially(false);
                }
                mRemoteIntent = null;
            }
        }, 560);
    }

    private void updateMainUiForUpdateDifferentState(Intent data) {
        final Thing thing = data.getParcelableExtra(
                Def.Communication.KEY_THING);
        @Thing.State final int stateAfter = data.getIntExtra(
                Def.Communication.KEY_STATE_AFTER, Thing.UNDERWAY);
        final int position = data.getIntExtra(
                Def.Communication.KEY_POSITION, 1);
        final boolean changed = data.getBooleanExtra(
                Def.Communication.KEY_CALL_CHANGE, false);
        final boolean justNotifyAll = App.justNotifyAll();
        Log.i(TAG, "updateMainUiForUpdateDifferentState called, "
                + "stateAfter[" + stateAfter + "], "
                + "position[" + position + "], "
                + "call change[" + changed + "], "
                + "justNotifyAll[" + justNotifyAll + "]");

        if (mStateToUndoFrom != stateAfter) {
            dismissSnackbars();
        }

        celebrateHabitGoalFinish(thing, thing.getState(), stateAfter);

        mDrawerLayout.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "updateMainUiForUpdateDifferentState: delayed Runnable started.");
                final int type = thing.getType();
                final int curLimit = mApp.getLimit();
                boolean limitMatched = Thing.isTypeStateMatchLimit(type, stateAfter, curLimit);
                Log.i(TAG, "type[" + type + "], "
                        + "curLimit[" + curLimit + "], "
                        + "limitMatched[" + limitMatched + "]");
                if (justNotifyAll || limitMatched) {
                    justNotifyAll();
                } else if (Thing.isTypeStateMatchLimit(type, thing.getState(), curLimit)) {
                    mUndoThings.add(thing);
                    mThingsIdsToUpdateWidget.add(thing.getId());
                    mUndoPositions.add(position);
                    mUndoLocations.add(thing.getLocation());
                    mStateToUndoFrom = stateAfter;
                    if (changed) {
                        mAdapter.notifyItemChanged(1);
                        updateUIAfterStateUpdated(stateAfter,
                                mRecyclerView.getItemAnimator().getChangeDuration(), false);
                    } else {
                        mAdapter.notifyItemRemoved(position);
                        updateUIAfterStateUpdated(stateAfter,
                                mRecyclerView.getItemAnimator().getRemoveDuration(), false);
                    }
                }

                mUpdateMainUiInOnResume = true;
                if (mCanSeeUi) {
                    App.setSomethingUpdatedSpecially(false);
                }
                mRemoteIntent = null;
            }
        }, 560);
    }

    private void updateMainUiForStickyOrCancel(Intent data) {
        final Thing thing = data.getParcelableExtra(
                Def.Communication.KEY_THING);
        final boolean isStickyBefore = thing.getLocation() > 0; // just used for log
        final int oldPosition = data.getIntExtra(
                Def.Communication.KEY_POSITION, -1);
        final int newPosition = mThingManager.getPosition(thing.getId());
        final boolean justNotifyAll = App.justNotifyAll();
        Log.i(TAG, "updateMainUiForStickyOrCancel called, "
                + "isStickyBefore[" + isStickyBefore + "], "
                + "oldPosition[" + oldPosition + "], "
                + "newPosition[" + newPosition + "], "
                + "justNotifyAll[" + justNotifyAll + "]");

        mDrawerLayout.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "updateMainUiForStickyOrCancel: delayed Runnable started.");
                if (justNotifyAll) {
                    justNotifyAll();
                } else if (oldPosition != -1 && newPosition != -1) {
                    mAdapter.notifyItemMoved(oldPosition, newPosition);
                    mDrawerLayout.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mAdapter.notifyItemChanged(newPosition);
                        }
                    }, mRecyclerView.getItemAnimator().getMoveDuration());
                }

                mDrawerHeader.updateCompletionRate();
                mUpdateMainUiInOnResume = true;
                if (mCanSeeUi) {
                    App.setSomethingUpdatedSpecially(false);
                }
                mRemoteIntent = null;
            }
        }, 560);
    }

    private void updateMainUiForDoingOrCancel(Intent data) {
        Log.i(TAG, "updateMainUiForDoingOrCancel called");
        final Thing thing = data.getParcelableExtra(Def.Communication.KEY_THING);
        final boolean justNotifyAll = App.justNotifyAll();
        mDrawerLayout.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "updateMainUiForDoingOrCancel: delayed Runnable started.");
                if (justNotifyAll) {
                    justNotifyAll();
                } else {
                    int position = mThingManager.getPosition(thing.getId());
                    if (position != -1) {
                        mAdapter.notifyItemChanged(position);
                    }
                    mUpdateMainUiInOnResume = true;
                    mRemoteIntent = null;
                    if (mCanSeeUi) {
                        App.setSomethingUpdatedSpecially(false);
                    }
                }
            }
        }, 560);
    }

    private void justNotifyAll() {
        if (App.isSearching) {
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

        mAdapter.setShouldThingsAnimWhenAppearing(true);
        mAdapter.notifyDataSetChanged();

        if (mCanSeeUi) {
            App.setSomethingUpdatedSpecially(false);
            App.setJustNotifyAll(false);
        }

        mUpdateMainUiInOnResume = true;
    }

    @Override
    protected void initMembers() {
        mApp = (App) getApplication();
        mThingManager = ThingManager.getInstance(mApp);

        mUndoThings              = new ArrayList<>();
        mUndoPositions           = new ArrayList<>();
        mUndoLocations           = new ArrayList<>();
        mUndoHabitRecords        = new ArrayList<>();
        mThingsIdsToUpdateWidget = new HashSet<>();
    }

    @Override
    protected void findViews() {
        mRevealLayout = f(R.id.reveal_layout);
        mViewToReveal = f(R.id.view_to_reveal);
        mTvNoResult   = f(R.id.tv_no_result);

        mActionbar = f(R.id.actionbar);
        mViewInsideActionbar = f(R.id.view_inside_actionbar);
        mEtSearch = f(R.id.et_search);
        Toolbar contextualToolbar = f(R.id.contextual_toolbar);
        contextualToolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.black_54p));
        RelativeLayout rlContextualToolbar = f(R.id.rl_contextual_toolbar);
        mColorPicker = new ColorPicker(this, getWindow().getDecorView(),
                Def.PickerType.COLOR_HAVE_ALL);

        mDrawerLayout = f(R.id.drawer_layout);
        mDrawer       = f(R.id.drawer);

        View dhView = mDrawer.getHeaderView(0);
        mDrawerHeader = new DrawerHeader(mApp,
                (ImageView) f(dhView, R.id.iv_drawer_header),
                (TextView)  f(dhView, R.id.tv_dh_location),
                (TextView)  f(dhView, R.id.tv_dh_completion_rate));

        mFab = f(R.id.fab_create);

        mRecyclerView  = f(R.id.rv_things);
        ThingsAdapter adapter = new ThingsAdapter(mApp, new OnThingTouchedListener());
        mAdapter = new ThingsAdapterWrapper(adapter);

        mActivityHeader = new ActivityHeader(mApp, mRecyclerView,
                f(R.id.actionbar_shadow),
                (RelativeLayout) f(R.id.rl_header),
                (TextView)       f(R.id.tv_header_title),
                (TextView)       f(R.id.tv_header_subtitle));

        FrameLayout fl  = f(R.id.fl_things);
        mNormalSnackbar = new Snackbar(mApp, Snackbar.NORMAL, fl, mFab);
        mUndoSnackbar   = new Snackbar(mApp, Snackbar.UNDO,   fl, mFab);
        mHabitSnackbar  = new Snackbar(mApp, Snackbar.UNDO,   fl, mFab);

        mModeManager = new ModeManager(mApp, mDrawerLayout, mFab, mActivityHeader,
                rlContextualToolbar, contextualToolbar, new OnNavigationIconClickedListener(),
                new OnContextualMenuClickedListener(), mRecyclerView, adapter);
        adapter.setModeManager(mModeManager);
        mActivityHeader.setModeManager(mModeManager);
    }

    @Override
    protected void initUI() {
        DisplayUtil.darkStatusBar(this);

        if (DeviceUtil.hasKitKatApi()) {
            View statusbar = f(R.id.view_status_bar);
            DrawerLayout.LayoutParams dlp1 = (DrawerLayout.LayoutParams)
                    statusbar.getLayoutParams();
            dlp1.height = DisplayUtil.getStatusbarHeight(this);
            statusbar.requestLayout();

            FrameLayout fl = f(R.id.fl_things);
            DrawerLayout.LayoutParams dlp2 = (DrawerLayout.LayoutParams) fl.getLayoutParams();
            dlp2.setMargins(0, dlp1.height, 0, 0);
            fl.requestLayout();

            // These two lines can make layout expand into statusbar on Kitkat and will not
            // influence the ui above Lollipop
            mDrawerLayout.setFitsSystemWindows(false);
            mDrawer.setFitsSystemWindows(false);
        }

        mDrawerLayout.setScrimColor(Color.parseColor("#84000000"));

        if (DeviceUtil.hasNougatApi()) {
            final View decor = getWindow().getDecorView();
            decor.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            initRecyclerViewUi();
                            mActivityHeader.computeFactors(mActionbar);
                            decor.getViewTreeObserver().removeOnPreDrawListener(this);
                            return true;
                        }
                    });
        } else {
            initRecyclerViewUi();
        }

        MenuItem item = mDrawer.getMenu().getItem(mApp.getLimit());
        item.setCheckable(true);
        item.setChecked(true);
        mPreviousItem = item;

        mActivityHeader.updateText();
        if (mModeManager.getCurrentMode() == ModeManager.SELECTING) {
            mModeManager.showContextualToolbar(false);
            mFab.shrinkWithoutAnim();
        }

        if (!DeviceUtil.hasLollipopApi()) {
            DisplayUtil.setSelectionHandlersColor(mEtSearch, -1979711488);
        }
    }

    private void initRecyclerViewUi() {
        computeSpanCount();

        if (!PermissionUtil.hasStoragePermission(this)
                && PermissionUtil.shouldRequestPermissionWhenLoadingThings(mThingManager.getThings())) {
            doWithPermissionChecked(
                    new SimplePermissionCallback(this) {
                        @Override
                        public void onGranted() {
                            mRecyclerView.postDelayed(initRecyclerViewRunnable, 240);
                        }

                        @Override
                        public void onDenied() {
                            super.onDenied();
                            finish();
                        }
                    },
                    Def.Communication.REQUEST_PERMISSION_LOAD_THINGS,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE);
        } else {
            // post here to make sure that animation plays well and completely
            mRecyclerView.postDelayed(initRecyclerViewRunnable, 240);
        }
    }

    /**
     * Focus on change of screen orientation.
     */
    @Override
    public void onConfigurationChanged(final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        dismissSnackbars();
        mColorPicker.dismiss();

        mActivityHeader.computeFactors(mActionbar);
        if (!App.isSearching) {
            mActivityHeader.reset(false);
        }

        mRecyclerView.setVisibility(View.INVISIBLE);
        mAdapter.setShouldThingsAnimWhenAppearing(true);
        mRecyclerView.setVisibility(View.VISIBLE);
        computeSpanCount();
        if (mStaggeredGridLayoutManager != null) {
            mStaggeredGridLayoutManager.setSpanCount(mSpan);
        }
        if (mThingManager.getThings().size() > 1) {
            mRecyclerView.scrollToPosition(0);
        }
        mAdapter.notifyDataSetChanged();

        mModeManager.updateTitleTextSize();
        if (mModeManager.getCurrentMode() != ModeManager.SELECTING && !App.isSearching
                && mApp.getLimit() <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
            mFab.showFromBottom();
        }
    }

    private void computeSpanCount() {
        mSpan = DisplayUtil.isTablet(this) ? 3 : 2;
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (DisplayUtil.isInMultiWindow(this)) {
                View decor = getWindow().getDecorView();
                Point display = DisplayUtil.getDisplaySize(this);
                if (decor.getWidth() != display.x) {
                    mSpan++;
                }
            } else {
                mSpan++;
            }
        }
    }

    @Override
    protected void setActionbar() {
        setSupportActionBar(mActionbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(null);
            actionBar.setHomeButtonEnabled(true);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    protected void setEvents() {
        mActionbar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mScrollCausedByFinger = true;
                mRecyclerView.smoothScrollToPosition(0);
            }
        });

        mDrawer.getHeaderView(0).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(ThingsActivity.this, StatisticActivity.class);
                startActivity(intent);
                mShouldCloseDrawer = true;
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
                mFab.getLocationInWindow(location);
                location[0] += mFab.getWidth() / 2;
                location[1] += mFab.getHeight() / 2;
                if (!DeviceUtil.hasKitKatApi()) {
                    location[1] -= statusBarHeight;
                }

                mViewToReveal.setBackgroundColor(App.newThingColor);
                mViewToReveal.setVisibility(View.VISIBLE);
                mRevealLayout.setVisibility(View.VISIBLE);

                mRevealLayout.show(location[0], location[1]);

                final Intent intent = DetailActivity.getOpenIntentForCreate(
                        ThingsActivity.this, TAG, App.newThingColor);
                mRevealLayout.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        startActivityForResult(
                                intent, Def.Communication.REQUEST_ACTIVITY_DETAIL);
                        overridePendingTransition(0, 0);
                    }
                }, 600);

                // change this value to prevent from flashing.
                final int delay = mApp.hasDetailActivityRun() ? 960 : 1600;
                mRevealLayout.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mRecyclerView.scrollToPosition(0);
                        mActivityHeader.reset(false);
                        mIsRevealAnimPlaying = false;
                        mFab.showFromBottom();
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
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_MOVE) {
                    if (!mScrollCausedByFinger) {
                        mScrollCausedByFinger = true;
                    }
                    if (mAdapter.shouldThingsAnimWhenAppearing()) {
                        mAdapter.setShouldThingsAnimWhenAppearing(false);
                    }
                }
                return false;
            }
        });
        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {

            final int edgeColor = EdgeEffectUtil.getEdgeColorDark();

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (mScrollCausedByFinger) {
                    dismissSnackbars();
                    int[] positions = new int[mSpan];
                    mStaggeredGridLayoutManager.findFirstVisibleItemPositions(positions);
                    mActivityHeader.updateAll(positions[0], false);
                }
            }

            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                EdgeEffectUtil.forRecyclerView(recyclerView, edgeColor);
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    Glide.with(mApp).resumeRequests();
                } else { // dragging or settling
                    Glide.with(mApp).pauseRequests();
                }
            }
        });

        mThingsTouchHelper = new ItemTouchHelper(new ThingsTouchCallback());
        mThingsTouchHelper.attachToRecyclerView(mRecyclerView);
    }

    private void setUndoSnackbarEvents() {
        mUndoSnackbar.setUndoListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mUndoThings.isEmpty()) { // occurs when click button very quickly
                    return;
                }

                int stateAfter = mUndoThings.get(0).getState();
                mScrollCausedByFinger = false;

                if (mUndoAll) {
                    mThingManager.undoUpdateStates(mUndoThings, mUndoPositions, mUndoLocations,
                            mStateToUndoFrom, stateAfter);
                    mUndoThings.clear();
                    mUndoPositions.clear();
                    mUndoLocations.clear();
                    if (mStateToUndoFrom == Thing.DELETED_FOREVER) {
                        mApp.getThingsToDeleteForever().clear();
                    }

                    mAdapter.notifyDataSetChanged();
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
                        mApp.getThingsToDeleteForever().remove(thing);
                    }

                    boolean change = mThingManager.updateState(
                            thing, position, location, mStateToUndoFrom, stateAfter, true, true);

                    if (change) {
                        mAdapter.notifyItemChanged(1);
                        updateUIAfterStateUpdated(stateAfter,
                                mRecyclerView.getItemAnimator().getChangeDuration(), true);
                    } else {
                        mAdapter.notifyItemInserted(position);
                        mRecyclerView.smoothScrollToPosition(position);
                        updateUIAfterStateUpdated(stateAfter,
                                mRecyclerView.getItemAnimator().getAddDuration(), true);
                    }
                }
                if (App.isSearching) {
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
                    mThingsIdsToUpdateWidget.remove(hr.getHabitId());
                    mUndoHabitRecords.remove(size - 1);
                    mUndoPositions.remove(size - 1);

                    HabitDAO.getInstance(mApp).undoFinishOneTime(hr);

                    if (!mUndoThings.isEmpty()) {
                        size = mUndoThings.size();
                        Thing thing = mUndoThings.get(size - 1);
                        mUndoThings.remove(size - 1);
                        mThingsIdsToUpdateWidget.remove(thing.getId());
                        mThingManager.update(thing.getType(), thing, position, false);
                        mThingManager.getThings().set(position, thing);
                    }

                    mAdapter.notifyItemChanged(position);
                    mDrawerHeader.updateCompletionRate();

                    if (mUndoHabitRecords.isEmpty()) {
                        mHabitSnackbar.dismiss();
                        for (Long id : mThingsIdsToUpdateWidget) {
                            AppWidgetHelper.updateSingleThingAppWidgets(ThingsActivity.this, id);
                        }
                        AppWidgetHelper.updateThingsListAppWidgetsForType(mApp, Thing.HABIT);
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

        mEtSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                beginSearchThings();
            }
        });

        mEtSearch.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    KeyboardUtil.hideKeyboard(mEtSearch);
                    beginSearchThings();
                    return true;
                }
                return false;
            }
        });

        KeyboardUtil.addKeyboardCallback(getWindow(), new KeyboardUtil.KeyboardCallback() {

            @Override
            public void onKeyboardShow(int keyboardHeight) {
                updateSearchNoResult(keyboardHeight);
            }

            @Override
            public void onKeyboardHide() {
                updateSearchNoResult(0);
            }
        });

        mColorPicker.setPickedListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mRecyclerView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
                getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                KeyboardUtil.hideKeyboard(mEtSearch);
                searchThings();
            }
        });
    }

    private void beginSearchThings() {
        if (mColorPicker.getPickedIndex() < 0) {
            if (mDontPickSearchColor) {
                mDontPickSearchColor = false;
            } else {
                mColorPicker.pickForUI(0);
            }
        }
        mRecyclerView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        searchThings();
    }

    private void updateSearchNoResult(int keyboardHeight) {
        if (DeviceUtil.hasKitKatApi()) {
            mRevealLayout.setPadding(0, 0, 0, keyboardHeight);
        }
        if (keyboardHeight != 0) {
            mTvNoResult.append("...");
            mTvNoResult.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        } else {
            mTvNoResult.setText(R.string.no_result);
            mTvNoResult.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.img_no_result, 0, 0);
        }
    }

    private void searchThings() {
        mThingManager.searchThings(mEtSearch.getText().toString(), mColorPicker.getPickedColor());
        mAdapter.setShouldThingsAnimWhenAppearing(false);
        mAdapter.notifyDataSetChanged();
        handleSearchResults();
    }

    private void setDrawer() {
        mDrawerHeader.updateTexts();
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, mDrawerLayout,
                R.string.cd_open_drawer, R.string.cd_close_drawer) {
            @Override
            public void onDrawerOpened(View drawerView) {
                super.onDrawerOpened(drawerView);
                dismissSnackbars();
            }
        };
        mDrawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        mActionbar.setNavigationOnClickListener(new OnNavigationIconClickedListener());
        mDrawer.setNavigationItemSelectedListener(new NavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {
                if (!mPreviousItem.equals(menuItem)) {
                    int newLimit;
                    int id = menuItem.getItemId();
                    if (id == R.id.drawer_underway) {
                        newLimit = Def.LimitForGettingThings.ALL_UNDERWAY;
                    } else if (id == R.id.drawer_note) {
                        newLimit = Def.LimitForGettingThings.NOTE_UNDERWAY;
                    } else if (id == R.id.drawer_reminder) {
                        newLimit = Def.LimitForGettingThings.REMINDER_UNDERWAY;
                    } else if (id == R.id.drawer_habit) {
                        newLimit = Def.LimitForGettingThings.HABIT_UNDERWAY;
                    } else if (id == R.id.drawer_goal) {
                        newLimit = Def.LimitForGettingThings.GOAL_UNDERWAY;
                    } else if (id == R.id.drawer_finished) {
                        newLimit = Def.LimitForGettingThings.ALL_FINISHED;
                    } else if (id == R.id.drawer_deleted) {
                        newLimit = Def.LimitForGettingThings.ALL_DELETED;
                    } else {
                        if (id == R.id.drawer_settings) {
                            doWithPermissionChecked(
                                    new SimplePermissionCallback(ThingsActivity.this) {
                                        @Override
                                        public void onGranted() {
                                            Intent intent = new Intent(
                                                    ThingsActivity.this, SettingsActivity.class);
                                            startActivityForResult(intent,
                                                    Def.Communication.REQUEST_ACTIVITY_SETTINGS);
                                        }
                                    },
                                    Def.Communication.REQUEST_PERMISSION_OPEN_SETTINGS,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE);
                        } else if (id == R.id.drawer_help) {
                            startActivity(new Intent(ThingsActivity.this, HelpActivity.class));
                        } else if (id == R.id.drawer_about) {
                            startActivity(new Intent(ThingsActivity.this, AboutActivity.class));
                        }
                        mShouldCloseDrawer = true;
                        return true;
                    }

                    mDrawerLayout.closeDrawer(GravityCompat.START);
                    checkDrawerItem(menuItem);

                    changeToLimit(newLimit, false);
                }
                return true;
            }
        });
    }

    private void changeToLimit(int newLimit, boolean updateDrawerItem) {
        if (updateDrawerItem) {
            MenuItem menuItem = mDrawer.getMenu().getItem(newLimit);
            checkDrawerItem(menuItem);
        }

        mRecyclerView.setVisibility(View.INVISIBLE);
        mApp.setLimit(newLimit, false);
        invalidateOptionsMenu();
        mRecyclerView.scrollToPosition(0);
        mActivityHeader.reset(true);

        mRecyclerView.postDelayed(new Runnable() {
            @Override
            public void run() {
                mRecyclerView.setVisibility(View.VISIBLE);
                mAdapter.setShouldThingsAnimWhenAppearing(true);
                mThingManager.loadThings();
                mAdapter.notifyDataSetChanged();
            }
        }, 360);

        mActivityHeader.updateText();
        mDrawerHeader.updateTexts();
        if (newLimit <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
            mFab.spread();
        } else {
            mFab.shrink();
        }
    }

    private void checkDrawerItem(MenuItem menuItem) {
        menuItem.setCheckable(true);
        menuItem.setChecked(true);
        mPreviousItem.setChecked(false);
        mPreviousItem = menuItem;
    }

    private void handleUpdateStates(int stateAfter) {
        if (mThingManager.isThingsEmpty()) {
            return;
        }
        dismissSnackbars();

        List<Thing> things = mThingManager.getThings();
        List<Thing> thingsToDeleteForever = new ArrayList<>();
        if (stateAfter == Thing.DELETED_FOREVER) {
            thingsToDeleteForever = mApp.getThingsToDeleteForever();
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
                    //thing.setSelected(false);
                    SystemNotificationUtil.cancelNotification(thing.getId(), type, mApp);
                    mUndoThings.add(thing);
                    mThingsIdsToUpdateWidget.add(thing.getId());
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
                long thingId = thing.getId();
                if (App.getDoingThingId() != thingId) {
                    SystemNotificationUtil.cancelNotification(thingId, type, mApp);
                    mUndoThings.add(thing);
                    mThingsIdsToUpdateWidget.add(thing.getId());
                    mUndoLocations.add(thing.getLocation());
                    if (stateAfter == Thing.DELETED_FOREVER) {
                        thingsToDeleteForever.add(new Thing(thing));
                    }
                }
            }
        }

        int stateBefore = things.get(1).getState();
        if (containsHabitOrGoal && stateBefore == Thing.UNDERWAY && mUndoThings.size() > 1) {
            // if mUntoThing.size == 1, it means that it is user's decision
            alertForHabitGoal(stateBefore, stateAfter);
        } else {
            for (Thing undoThing : mUndoThings) {
                undoThing.setSelected(false);
            }
            for (Thing thingToDelete : thingsToDeleteForever) {
                thingToDelete.setSelected(false);
            }
            handleUpdateStates(stateBefore, stateAfter);
        }
    }

    private void alertForHabitGoal(final int stateBefore, final int stateAfter) {
        ThreeActionsAlertDialogFragment df = new ThreeActionsAlertDialogFragment();
        int color = DisplayUtil.getRandomColor(this);
        df.setTitleColor(color);
        df.setContinueColor(color);
        df.setTitle(getString(R.string.alert_continue));
        df.setContent(getString(R.string.alert_find_habit_goal));
        df.setFirstAction(getString(R.string.continue_get_rid_of_habit_goal));
        df.setSecondAction(getString(R.string.continue_for_alert));
        df.setOnClickListener(new ThreeActionsAlertDialogFragment.OnClickListener() {
            @Override
            public void onFirstClicked() {
                List<Thing> thingsToDelete = mApp.getThingsToDeleteForever();
                Iterator<Thing> iterator = mUndoThings.iterator();
                while (iterator.hasNext()) {
                    Thing thing = iterator.next();
                    thing.setSelected(false);
                    if (Thing.isImportantType(thing.getType())) {
                        iterator.remove();
                        mThingsIdsToUpdateWidget.remove(thing.getId());
                        mUndoLocations.remove(thing.getLocation());
                        thingsToDelete.remove(thing);
                    }
                }
                handleUpdateStates(stateBefore, stateAfter);
            }

            @Override
            public void onSecondClicked() {
                for (Thing undoThing : mUndoThings) {
                    undoThing.setSelected(false);
                }
                handleUpdateStates(stateBefore, stateAfter);
            }

            @Override
            public void onThirdClicked() {
                List<Thing> thingsToDelete = mApp.getThingsToDeleteForever();
                for (Thing undoThing : mUndoThings) {
                    thingsToDelete.remove(undoThing);
                }
                mUndoThings.clear();
                mThingsIdsToUpdateWidget.clear();
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
        mUndoPositions = mThingManager.updateStates(mUndoThings, stateBefore, stateAfter);
        mAdapter.notifyDataSetChanged();
        mUndoAll = true;
        if (!mUndoThings.isEmpty()) {
            updateUIAfterStateUpdated(stateAfter,
                    mRecyclerView.getItemAnimator().getRemoveDuration(), true);
        }
        if (App.isSearching) {
            handleSearchResults();
        }
    }

    private void updateUIAfterStateUpdated(int stateAfter, long timeDelay, boolean shouldForceBackNormalMode) {
        mActivityHeader.updateText();
        mDrawerHeader.updateCompletionRate();

        mScrollCausedByFinger = false;
        mRecyclerView.postDelayed(new Runnable() {
            @Override
            public void run() {
                int[] positions = new int[mSpan];
                mStaggeredGridLayoutManager.findFirstVisibleItemPositions(positions);
                mActivityHeader.updateAll(positions[0], true);
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
                if (App.isSearching) {
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
                finished + " " + LocaleUtil.getTimesStr(mApp, mUndoHabitRecords.size()));
        mHabitSnackbar.setUndoText(R.string.act_sb_undo_finish);
        mHabitSnackbar.show();
    }

    private String[] getUndoMessages(int stateAfter, int count) {
        StringBuilder sb = new StringBuilder();
        String updateStr = "";
        String undoStr = "";
        int limit = mApp.getLimit();
        switch (stateAfter) {
            case Thing.UNDERWAY:
                updateStr = getString(R.string.sb_underway);
                if (limit <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
                    updateStr = getString(R.string.sb_finish);
                    undoStr = getString(R.string.act_sb_undo_finish);
                } else if (limit == Def.LimitForGettingThings.ALL_FINISHED) {
                    undoStr = getString(R.string.act_sb_undo_underway);
                } else {
                    undoStr = getString(R.string.act_sb_undo);
                }
                break;
            case Thing.FINISHED:
                updateStr = getString(R.string.sb_finish);
                if (limit <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
                    undoStr = getString(R.string.act_sb_undo_finish);
                } else {
                    undoStr = getString(R.string.act_sb_undo);
                }
                break;
            case Thing.DELETED:
                updateStr = getString(R.string.sb_delete);
                undoStr = getString(R.string.act_sb_undo);
                break;
            case Thing.DELETED_FOREVER:
                updateStr = getString(R.string.sb_delete_forever);
                undoStr = getString(R.string.act_sb_undo);
                break;
            default:break;
        }
        if (LocaleUtil.isChinese(mApp)) {
            sb.append(updateStr).append(" ").append(count)
                    .append(" ").append(getString(R.string.a_thing));
        } else {
            String str = mApp.getString(R.string.a_thing);
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

        for (Long id : mThingsIdsToUpdateWidget) {
            AppWidgetHelper.updateSingleThingAppWidgets(this, id);
        }
        if (!mThingsIdsToUpdateWidget.isEmpty()) {
            // update all things list widget since it's hard to get limits for things to update widget.
            AppWidgetHelper.updateAllThingsListAppWidgets(this);
        }

        mUndoThings.clear();
        mThingsIdsToUpdateWidget.clear();
        mUndoPositions.clear();
        mUndoLocations.clear();
        mUndoHabitRecords.clear();
        mUndoAll = false;
        mThingManager.clearLists();

        mApp.releaseResourcesAfterDeleteForever();
    }

    private void toggleSearching(boolean shouldThingsAnimWhenAppearing) {
        dismissSnackbars();
        final boolean toNormal = App.isSearching;
        if (toNormal) {
            mActionbar.setNavigationContentDescription(R.string.cd_open_drawer);
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
            if (mApp.getLimit() <= Def.LimitForGettingThings.NOTE_UNDERWAY) {
                mFab.spread();
            }
            mApp.setLimit(mApp.getLimit(), true);
            mActivityHeader.updateText();
            mDrawerHeader.updateCompletionRate();
            mAdapter.setShouldThingsAnimWhenAppearing(shouldThingsAnimWhenAppearing);
            handleSearchResults();
            if (!DeviceUtil.hasKitKatApi()) {
                getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
                mDrawerLayout.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        DisplayUtil.playDrawerToggleAnim((DrawerArrowDrawable) mActionbar.getNavigationIcon());
                    }
                }, 160);
            } else {
                DisplayUtil.playDrawerToggleAnim((DrawerArrowDrawable) mActionbar.getNavigationIcon());
            }
        } else {
            mActionbar.setNavigationContentDescription(R.string.cd_quit_searching);
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

            if (!DeviceUtil.hasKitKatApi()) {
                mDrawerLayout.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        getWindow().setSoftInputMode(
                                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                    }
                }, 300);
            }
            DisplayUtil.playDrawerToggleAnim((DrawerArrowDrawable) mActionbar.getNavigationIcon());
        }
        mAdapter.notifyDataSetChanged();
        App.isSearching = !toNormal;
        invalidateOptionsMenu();
        mDontPickSearchColor = true;
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
                Habit habit = HabitDAO.getInstance(mApp).getHabitById(id);
                content = habit.getCelebrationText(mApp);
            } else {
                Reminder reminder = ReminderDAO.getInstance(mApp).getReminderById(id);
                content = reminder.getCelebrationText(mApp);
            }
            adf.setContent(content);
            adf.show(getFragmentManager(), AlertDialogFragment.TAG);
        }
    }

    class OnNavigationIconClickedListener implements View.OnClickListener {

        @Override
        public void onClick(View v) {
            if (App.isSearching) {
                toggleSearching(true);
            } else {
                mDrawerLayout.openDrawer(GravityCompat.START);
                dismissSnackbars();
            }
        }
    }

    class OnThingTouchedListener implements ThingsAdapter.OnItemTouchedListener {
        @Override
        public boolean onItemTouch(View v, MotionEvent event) {
            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_MOVE) {
                if (!mScrollCausedByFinger) {
                    mScrollCausedByFinger = true;
                }
                if (mAdapter.shouldThingsAnimWhenAppearing()) {
                    mAdapter.setShouldThingsAnimWhenAppearing(false);
                }
            }
            return false;
        }

        @Override
        public void onItemClick(final View v, final int position) {
            if (position <= 0 || mIsRevealAnimPlaying) {
                return;
            }
            dismissSnackbars();
            KeyboardUtil.hideKeyboard(getCurrentFocus());

            List<Thing> things = mThingManager.getThings();
            if (position >= things.size()) {
                return;
            }

            if (mModeManager.getCurrentMode() != ModeManager.SELECTING) {
                if (mRecyclerView.getItemAnimator().isRunning()) {
                    return;
                }

                final Thing thing = things.get(position);
                if (thing.isPrivate()) {
                    ThingsActivity activity = ThingsActivity.this;
                    SharedPreferences sp = getSharedPreferences(
                            Def.Meta.PREFERENCES_NAME, MODE_PRIVATE);
                    String cp = sp.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null);

                    AuthenticationHelper.authenticate(
                            activity, thing.getColor(),
                            getString(R.string.check_private_thing), cp,
                            new AuthenticationHelper.AuthenticationCallback() {
                                @Override
                                public void onAuthenticated() {
                                    openDetailActivityForUpdate(thing, position, v);
                                }

                                @Override
                                public void onCancel() {

                                }
                            });
                } else {
                    openDetailActivityForUpdate(thing, position, v);
                }
            } else {
                Thing thing = things.get(position);
                if (App.getDoingThingId() != thing.getId()) {
                    thing.setSelected(!thing.isSelected());
                    mAdapter.notifyItemChanged(position);
                    mModeManager.updateSelectedCount();
                    mModeManager.updateMenuItems();
                }
            }
        }

        private void openDetailActivityForUpdate(Thing thing, int position, View v) {
            final Intent intent = DetailActivity.getOpenIntentForUpdate(
                    ThingsActivity.this, TAG, thing.getId(), position);
            ActivityOptionsCompat transition = ActivityOptionsCompat.makeScaleUpAnimation(
                    v, 0, 0, v.getWidth(), v.getHeight());
            ActivityCompat.startActivityForResult(
                    ThingsActivity.this, intent, Def.Communication.REQUEST_ACTIVITY_DETAIL,
                    transition.toBundle());
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

            Thing thing = things.get(position);
            if (mModeManager.getCurrentMode() == ModeManager.NORMAL
                    && thing.getType() <= Thing.NOTIFICATION_GOAL
                    && App.getDoingThingId() != thing.getId()) {
                mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
                if (mApp.getLimit() <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
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
            if (canMove(from, to)) {
                mThingManager.move(from, to);
                mAdapter.notifyItemMoved(from, to);
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

        private boolean canMove(int from, int to) {
            List<Thing> things = mThingManager.getThings();
            final int size = things.size();
            if (from < 0 || from >= size || to < 0 || to >= size) {
                return false;
            }

            Thing t1 = things.get(from);
            Thing t2 = things.get(to);
            if (t1.getType() == Thing.HEADER || t2.getType() == Thing.HEADER) {
                return false;
            }

            long loc1 = t1.getLocation();
            long loc2 = t2.getLocation();
            if (loc1 < 0 && loc2 < 0) { // both are sticky things
                return true;
            } else if (loc1 >=0 && loc2 >= 0) { // both are not sticky things
                return true;
            } else return false;
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return mApp.getLimit() <= Def.LimitForGettingThings.GOAL_UNDERWAY
                    && mModeManager.getCurrentMode() != ModeManager.SELECTING
                    && !mThingManager.isThingsEmpty();
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
            int position = viewHolder.getAdapterPosition();
            if (position <= 0) {
                return;
            }

            List<Thing> things = mThingManager.getThings();
            if (position >= things.size()) {
                return;
            }

            final Thing thingToSwipe = things.get(position);
            long id = thingToSwipe.getId();
            @Thing.Type int thingType = thingToSwipe.getType();
            if (thingType > Thing.NOTIFICATION_GOAL) {
                // without this line, the item will disappear and leave a white space...
                mAdapter.notifyItemChanged(position);
                return;
            }

            prepareBeforeSwipingThing(id, thingType);

            if (direction == ItemTouchHelper.START) {
                if (App.getDoingThingId() == id) {
                    DoingService.sSendBroadcastToUpdateMainUi = false;
                }
                if (thingType == Thing.HABIT) {
                    tryToFinishHabitOnceBySwiping(thingToSwipe, position);
                } else {
                    tryToFinishOtherBySwiping(thingToSwipe, position);
                }
            } else {
                if (thingType == Thing.HABIT) {
                    Habit habit = HabitDAO.getInstance(mApp).getHabitById(id);
                    if (habit != null && habit.isPaused()) {
                        mNormalSnackbar.setMessage(R.string.alert_habit_paused);
                        mNormalSnackbar.show();
                        return;
                    }
                }
                mAdapter.notifyItemChanged(position);
                if (App.getDoingThingId() == thingToSwipe.getId()) {
                    Toast.makeText(ThingsActivity.this, R.string.start_doing_doing_this_thing,
                            Toast.LENGTH_LONG).show();
                } else {
                    if (thingToSwipe.isPrivate()) {
                        String cp = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
                                .getString(Def.Meta.KEY_PRIVATE_PASSWORD, null);
                        AuthenticationHelper.authenticate(ThingsActivity.this, thingToSwipe.getColor(),
                                getString(R.string.start_doing_full_title), cp,
                                new AuthenticationHelper.AuthenticationCallback() {
                                    @Override
                                    public void onAuthenticated() {
                                        ThingDoingHelper helper = new ThingDoingHelper(
                                                ThingsActivity.this, thingToSwipe);
                                        helper.tryToOpenStartDoingActivityUser();
                                    }

                                    @Override
                                    public void onCancel() {
                                    }
                                });
                    } else {
                        ThingDoingHelper helper = new ThingDoingHelper(
                                ThingsActivity.this, thingToSwipe);
                        helper.tryToOpenStartDoingActivityUser();
                    }
                }
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
            hasSwipedRight = false;
        }

        boolean hasSwipedRight = false;

        @Override
        public void onChildDraw(Canvas c, RecyclerView recyclerView,
                                RecyclerView.ViewHolder viewHolder, float dX, float dY,
                                int actionState, boolean isCurrentlyActive) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                int displayWidth = DisplayUtil.getDisplaySize(mApp).x;
                View v = viewHolder.itemView;
                final BaseThingsAdapter.BaseThingViewHolder holder =
                        (BaseThingsAdapter.BaseThingViewHolder) viewHolder;
                int position = viewHolder.getAdapterPosition();
                List<Thing> things = mThingManager.getThings();
                if (position < 0 || position >= things.size()) {
                    return;
                }

                Thing thing = things.get(position);
                if (dX < 0) {
                    holder.flDoing.setAlpha(1.0f);
                    if (App.getDoingThingId() != thing.getId()) {
                        holder.flDoing.setVisibility(View.GONE);
                    }
                    v.setAlpha(1.0f + dX / v.getRight());
                } else if (dX > 0) {
                    if (App.getDoingThingId() == thing.getId()) {
                        swiped = true;
                        return;
                    }

                    holder.flDoing.setVisibility(View.VISIBLE);
                    if (!hasSwipedRight) {
                        InterceptTouchCardView.LayoutParams lp = (InterceptTouchCardView.LayoutParams)
                                holder.flDoing.getLayoutParams();
                        lp.width  = holder.cv.getWidth();
                        lp.height = holder.cv.getHeight();
                        holder.flDoing.requestLayout();
                        hasSwipedRight = true;
                    }

                    float alpha = dX / (displayWidth - v.getLeft()) * 2;
                    if (alpha > 1.0f) alpha = 1.0f;
                    holder.flDoing.setAlpha(alpha);
                } else {
                    v.setAlpha(1.0f);
                    holder.flDoing.setAlpha(1.0f);
                    if (App.getDoingThingId() != thing.getId()) {
                        holder.flDoing.setVisibility(View.GONE);
                    }
                }
                swiped = dX != 0;
            }
        }
    }

    private void prepareBeforeSwipingThing(long id, @Thing.Type int thingType) {
        if (mNormalSnackbar.isShowing()) {
            mNormalSnackbar.dismiss();
        }

        if (mUndoSnackbar.isShowing()) {
            if (mStateToUndoFrom != Thing.FINISHED || thingType == Thing.HABIT) {
                dismissSnackbars();
            }
        }

        SystemNotificationUtil.cancelNotification(id, thingType, mApp);
        mThingsIdsToUpdateWidget.add(id);
    }

    private void tryToFinishHabitOnceBySwiping(Thing thingToSwipe, int position) {
        long id = thingToSwipe.getId();
        if (App.getDoingThingId() == id) {
            DoingService.sStopReason = DoingRecord.STOP_REASON_FINISH;
        }

        @Thing.Type int thingType = thingToSwipe.getType();
        HabitDAO habitDAO = HabitDAO.getInstance(mApp);
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
                    mThingManager.getThings().set(position, thing);
                }
                mUndoHabitRecords.add(habitDAO.finishOneTime(habit));
                mUndoPositions.add(position);
                showHabitSnackbar();
            } else {
                dismissSnackbars();
                if (habit.isPaused()) {
                    mNormalSnackbar.setMessage(R.string.alert_habit_paused);
                } else if (habit.getRecord().isEmpty() && habit.getRemindedTimes() == 0) {
                    mNormalSnackbar.setMessage(R.string.alert_cannot_finish_habit_first_time);
                } else {
                    mNormalSnackbar.setMessage(R.string.alert_cannot_finish_habit_more_times);
                }
                mNormalSnackbar.show();
                if (App.getDoingThingId() == id) {
                    DoingService.sStopReason = DoingRecord.STOP_REASON_CANCEL_USER;
                }
            }
        }

        if (App.getDoingThingId() == id) {
            sendBroadcast(new Intent(DoingActivity.BROADCAST_ACTION_JUST_FINISH));
            stopService(new Intent(ThingsActivity.this, DoingService.class));
            App.setDoingThingId(-1L);
        }

        mAdapter.notifyItemChanged(position);
        mDrawerHeader.updateCompletionRate();
    }

    private void tryToFinishOtherBySwiping(Thing thingToSwipe, int position) {
        if (mHabitSnackbar.isShowing()) {
            dismissSnackbars();
        }

        @Thing.State int state = thingToSwipe.getState();
        long location = thingToSwipe.getLocation();
        mUndoThings.add(thingToSwipe);
        mUndoPositions.add(position);
        mUndoLocations.add(location);
        mStateToUndoFrom = Thing.FINISHED;

        if (App.getDoingThingId() == thingToSwipe.getId()) {
            DoingService.sStopReason = DoingRecord.STOP_REASON_FINISH;
            sendBroadcast(new Intent(DoingActivity.BROADCAST_ACTION_JUST_FINISH));
            stopService(new Intent(ThingsActivity.this, DoingService.class));
            App.setDoingThingId(-1L);
        }

        boolean changed = mThingManager.updateState(thingToSwipe, position, location,
                state, Thing.FINISHED, false, true);
        mScrollCausedByFinger = false;

        if (App.isSearching) {
            if (mThingManager.getThings().size() == 1) {
                handleSearchResults();
            }
        }

        celebrateHabitGoalFinish(thingToSwipe, state, Thing.FINISHED);
        if (changed) {
            mAdapter.notifyItemChanged(position);
            updateUIAfterStateUpdated(Thing.FINISHED,
                    mRecyclerView.getItemAnimator().getChangeDuration(), true);
        } else {
            mAdapter.notifyItemRemoved(position);
            updateUIAfterStateUpdated(Thing.FINISHED,
                    mRecyclerView.getItemAnimator().getRemoveDuration(), true);
        }
    }

    class OnContextualMenuClickedListener implements OnMenuItemClickListener {
        @Override
        public boolean onMenuItemClick(MenuItem item) {
            switch (item.getItemId()) {
                case R.id.act_select_all:
                    if (mThingManager.getSelectedCount() == mThingManager.getThings().size() - 1) {
                        mThingManager.setSelectedTo(false);
                    } else {
                        mThingManager.setSelectedTo(true);
                    }
                    mAdapter.notifyDataSetChanged();
                    mModeManager.updateMenuItems();
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
                case R.id.act_sticky:
                    final int oldPosition = mThingManager.getSingleSelectedPosition();
                    if (oldPosition == -1) break;
                    mModeManager.backNormalMode(oldPosition);
                    mRecyclerView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (oldPosition >= mThingManager.getThings().size()) return;
                            Thing thing = mThingManager.getThings().get(oldPosition);
                            final int newPosition;
                            if (thing.getLocation() < 0) {
                                mThingManager.cancelStickyThing(thing, oldPosition);
                                newPosition = mThingManager.getPositionToInsertNewThing();
                            } else {
                                mThingManager.stickyThingOnTop(thing, oldPosition);
                                newPosition = 1;
                            }
                            mAdapter.notifyItemMoved(oldPosition, newPosition);
                            // notifyItemMoved will not call adapter.bindViewHolder again
                            mRecyclerView.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    mAdapter.notifyItemChanged(newPosition);
                                }
                            }, mRecyclerView.getItemAnimator().getMoveDuration());
                        }
                    }, 160); // TODO: 2016/10/23 check if 160 is enough
                    break;
                case R.id.act_export:
                    doWithPermissionChecked(new SimplePermissionCallback(ThingsActivity.this) {
                        @Override
                        public void onGranted() {
                            int accentColor = DisplayUtil.getRandomColor(App.getApp());
                            ThingExporter.startExporting(ThingsActivity.this, accentColor,
                                    mThingManager.getSelectedThings());
                            mModeManager.backNormalMode(0);
                        }
                    }, Def.Communication.REQUEST_PERMISSION_EXPORT_MAIN,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE);
                    break;
                default:break;
            }
            mModeManager.updateSelectedCount();
            return false;
        }
    }
}