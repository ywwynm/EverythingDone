package com.ywwynm.everythingdone.activities;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.StringRes;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.util.Pair;
import android.support.v4.widget.NestedScrollView;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SimpleItemAnimator;
import android.support.v7.widget.Toolbar;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.AudioAttachmentAdapter;
import com.ywwynm.everythingdone.adapters.CheckListAdapter;
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter;
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper;
import com.ywwynm.everythingdone.appwidgets.CreateWidget;
import com.ywwynm.everythingdone.collections.ThingActionsList;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.fragments.AddAttachmentDialogFragment;
import com.ywwynm.everythingdone.fragments.AlertDialogFragment;
import com.ywwynm.everythingdone.fragments.DateTimeDialogFragment;
import com.ywwynm.everythingdone.fragments.HabitDetailDialogFragment;
import com.ywwynm.everythingdone.fragments.LoadingDialogFragment;
import com.ywwynm.everythingdone.fragments.TwoOptionsDialogFragment;
import com.ywwynm.everythingdone.helpers.AppUpdateHelper;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.helpers.AuthenticationHelper;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.helpers.ScreenshotHelper;
import com.ywwynm.everythingdone.helpers.SendInfoHelper;
import com.ywwynm.everythingdone.helpers.ThingExporter;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.ReminderHabitParams;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.model.ThingAction;
import com.ywwynm.everythingdone.permission.PermissionUtil;
import com.ywwynm.everythingdone.permission.SimplePermissionCallback;
import com.ywwynm.everythingdone.receivers.AutoNotifyReceiver;
import com.ywwynm.everythingdone.receivers.HabitReceiver;
import com.ywwynm.everythingdone.receivers.ReminderReceiver;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.DeviceUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.FileUtil;
import com.ywwynm.everythingdone.utils.KeyboardUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;
import com.ywwynm.everythingdone.utils.UriPathConverter;
import com.ywwynm.everythingdone.views.Snackbar;
import com.ywwynm.everythingdone.views.pickers.ColorPicker;
import com.ywwynm.everythingdone.views.pickers.DateTimePicker;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("NewApi")
public final class DetailActivity extends EverythingDoneBaseActivity {

    public static final String TAG = "DetailActivity";

    public static final int CREATE = 0;
    public static final int UPDATE = 1;

    public static Intent getOpenIntentForCreate(Context context, String senderName, int color) {
        final Intent intent = new Intent(context, DetailActivity.class);
        intent.putExtra(Def.Communication.KEY_SENDER_NAME, senderName);
        intent.putExtra(Def.Communication.KEY_DETAIL_ACTIVITY_TYPE,
                DetailActivity.CREATE);
        intent.putExtra(Def.Communication.KEY_COLOR, color);
        return intent;
    }

    public static Intent getOpenIntentForUpdate(
            Context context, String senderName, long id, int position) {
        final Intent intent = new Intent(context, DetailActivity.class);
        intent.putExtra(Def.Communication.KEY_SENDER_NAME, senderName);
        intent.putExtra(Def.Communication.KEY_DETAIL_ACTIVITY_TYPE,
                DetailActivity.UPDATE);
        intent.putExtra(Def.Communication.KEY_ID, id);
        intent.putExtra(Def.Communication.KEY_POSITION, position);
        return intent;
    }

    private static int createActivitiesCount = 0;

    public float screenDensity;

    // type + path + name of attachment to add
    public String attachmentTypePathName;

    public ReminderHabitParams rhParams = new ReminderHabitParams();

    public DateTimePicker quickRemindPicker;
    public CheckBox       cbQuickRemind;
    public TextView       tvQuickRemind;

    private String mSenderName;
    private int    mType;

    private App mApp;

    private boolean mEditable;

    private Thing    mThing;
    private int      mPosition;
    private Reminder mReminder;
    private Habit    mHabit;

    private boolean mHabitFinishedThisTime = false;

    private int mMaxSpanImage;
    private int mSpanAudio;

    private DateTimeDialogFragment mDateTimeDialogFragment;

    private FrameLayout mFlRoot;
    private ColorPicker mColorPicker;
    private View        mStatusBar;
    private Toolbar     mActionbar;
    private ImageButton mIbBack;
    private View        mActionBarShadow;
    private View        mImageCover;

    private RecyclerView           mRvImageAttachment;
    private ImageAttachmentAdapter mImageAttachmentAdapter;
    private GridLayoutManager      mImageLayoutManager;

    private NestedScrollView mScrollView;
    private EditText         mEtTitle;
    private EditText         mEtContent;
    private TextView         mTvUpdateTime;

    private RecyclerView        mRvCheckList;
    private CheckListAdapter    mCheckListAdapter;
    private LinearLayoutManager mLlmCheckList;
    private LinearLayout        mLlMoveChecklist;
    private TextView            mTvMoveChecklistAsBt;
    private ItemTouchHelper     mChecklistTouchHelper;

    private RecyclerView           mRvAudioAttachment;
    private AudioAttachmentAdapter mAudioAttachmentAdapter;
    private GridLayoutManager      mAudioLayoutManager;

    private FrameLayout mFlQuickRemindAsBt;

    private Snackbar      mNormalSnackbar;

    private ExecutorService mExecutor;

    private boolean changingColor = false;

    private Runnable mShowNormalSnackbar;

    private boolean mRemoveDetailActivityInstance = false;
    private boolean mMinusCreateActivitiesCount   = false;

    private HashMap<View, Integer> mTouchMovedCountMap = new HashMap<>();
    private HashMap<View, Boolean> mOnLongClickedMap   = new HashMap<>();

    /**
     * This {@link android.view.View.OnTouchListener} will listen to click events that should
     * be handled by link/phoneNum/email/maps in {@link mEtContent} and other {@link EditText}s
     * so that we can handle them with different intents and not lose ability to edit them.
     */
    View.OnTouchListener mSpannableTouchListener = new View.OnTouchListener() {

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int action = event.getAction();
            Boolean onLongClicked = mOnLongClickedMap.get(v);
            if (onLongClicked != null && onLongClicked) {
                return false;
            }

            Log.d(TAG, "action: " + action);
            if (action == MotionEvent.ACTION_UP) {
                Integer touchMovedCount = mTouchMovedCountMap.get(v);
                if (touchMovedCount != null && touchMovedCount >= 3) {
                    Log.d(TAG, "touchMoved: " + touchMovedCount);
                    mTouchMovedCountMap.put(v, 0);
                    return false;
                }

                final EditText et = (EditText) v;
                final Spannable sContent = Spannable.Factory.getInstance()
                        .newSpannable(et.getText());

                int x = (int) event.getX();
                int y = (int) event.getY();

                x -= et.getTotalPaddingLeft();
                y -= et.getTotalPaddingTop();
                x += et.getScrollX();
                y += et.getScrollY();

                Layout layout = et.getLayout();
                int line = layout.getLineForVertical(y);
                int offset = layout.getOffsetForHorizontal(line, x);

                // place cursor of EditText to correct position.
                et.requestFocus();
                if (offset > 0) {
                    if (x > layout.getLineMax(line)) {
                        et.setSelection(offset);
                    } else et.setSelection(offset - 1);
                }

                ClickableSpan[] link = sContent.getSpans(offset, offset, ClickableSpan.class);
                if (link.length != 0) {
                    final URLSpan urlSpan = (URLSpan) link[0];

                    if (!mEditable) {
                        urlSpan.onClick(et);
                        return true;
                    }

                    String url = urlSpan.getURL();
                    final TwoOptionsDialogFragment df = new TwoOptionsDialogFragment();
                    df.setViewToFocusAfterDismiss(et);

                    View.OnClickListener startListener = new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            df.dismiss();
                            KeyboardUtil.hideKeyboard(getWindow());
                            try {
                                urlSpan.onClick(et);
                            } catch (ActivityNotFoundException e) {
                                mNormalSnackbar.setMessage(R.string.error_activity_not_found);
                                mFlRoot.postDelayed(mShowNormalSnackbar,
                                        KeyboardUtil.HIDE_DELAY);
                            }
                        }
                    };
                    View.OnClickListener endListener = new View.OnClickListener() {
                        @Override
                        public void onClick(View v1) {
                            df.setShouldShowKeyboardAfterDismiss(true);
                            df.dismiss();
                        }
                    };

                    if (url.startsWith("tel")) {
                        df.setStartAction(R.drawable.act_dial, R.string.act_dial,
                                startListener);
                    } else if (url.startsWith("mailto")) {
                        df.setStartAction(R.drawable.act_send_email,
                                R.string.act_send_email, startListener);
                    } else if (url.startsWith("http") || url.startsWith("https")) {
                        df.setStartAction(R.drawable.act_open_in_browser,
                                R.string.act_open_in_browser, startListener);
                    } else if (url.startsWith("map")) {
                        df.setStartAction(R.drawable.act_open_in_map,
                                R.string.act_open_in_map, startListener);
                    }
                    df.setEndAction(R.drawable.act_edit, R.string.act_edit, endListener);
                    df.show(getFragmentManager(), TwoOptionsDialogFragment.TAG);
                    return true;
                }
            } else if (action == MotionEvent.ACTION_MOVE) {
                Integer touchMovedCount = mTouchMovedCountMap.get(v);
                mTouchMovedCountMap.put(v, touchMovedCount == null ? 1 : touchMovedCount + 1);
            }
            return false;
        }
    };

    private View.OnClickListener mEtContentClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            mTouchMovedCountMap.put(v, 0);
            mOnLongClickedMap.put(v, false);
        }
    };
    private View.OnLongClickListener mEtContentLongClickListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            mTouchMovedCountMap.put(v, 0);
            mOnLongClickedMap.put(v, true);
            return false;
        }
    };

    private ThingActionsList mActionList;
    public boolean shouldAddToActionList = false;

    public ThingActionsList getActionList() {
        return mActionList;
    }

    public int getType() {
        return mType;
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.activity_detail;
    }

    @Override
    protected void initMembers() {
        mApp = (App) getApplication();
        mApp.setDetailActivityRun(true);

        screenDensity = DisplayUtil.getScreenDensity(this);

        Intent intent = getIntent();
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            mSenderName = "intent";
            mType = CREATE;
        } else {
            mSenderName = intent.getStringExtra(Def.Communication.KEY_SENDER_NAME);
            mType = intent.getIntExtra(Def.Communication.KEY_DETAIL_ACTIVITY_TYPE, UPDATE);
        }

        long id = intent.getLongExtra(Def.Communication.KEY_ID, -1);

        mPosition = intent.getIntExtra(Def.Communication.KEY_POSITION, 1);

        ThingManager thingManager = ThingManager.getInstance(mApp);
        if (mType == CREATE) {
            createActivitiesCount++;

            long newId = thingManager.getHeaderId();
            App.getRunningDetailActivities().add(newId);

            int color = intent.getIntExtra(Def.Communication.KEY_COLOR, App.newThingColor);
            mThing = new Thing(newId, Thing.NOTE, color, newId);

            // this can change App.newThingColor to a new random color
            SystemNotificationUtil.tryToCreateQuickCreateNotification(this);

            if ("intent".equals(mSenderName)) {
                setupThingFromIntent();
            }
        } else {
            App.getRunningDetailActivities().add(id);
            if (mPosition == -1) {
                updateThingAndItsPosition(id);
            } else {
                List<Thing> things = thingManager.getThings();
                if (mPosition >= things.size()) {
                    updateThingAndItsPosition(id);
                } else {
                    mThing = thingManager.getThings().get(mPosition);
                    if (mThing.getId() != id) { // something was updated specially.
                        updateThingAndItsPosition(id);
                    }
                }
            }
            if (mThing == null) {
                finish();
                return;
            }
            mReminder = ReminderDAO.getInstance(mApp).getReminderById(id);
            if (mThing.getType() == Thing.HABIT) {
                mHabit = HabitDAO.getInstance(mApp).getHabitById(id);
            }
            SystemNotificationUtil.cancelNotification(id, mThing.getType(), mApp);
        }

        mEditable = mThing.getType()  != Thing.HEADER
                &&  mThing.getType()  <  Thing.NOTIFICATION_UNDERWAY
                &&  mThing.getState() == Thing.UNDERWAY;
        if (mEditable) {
            mShowNormalSnackbar = new Runnable() {
                @Override
                public void run() {
                    mNormalSnackbar.show();
                }
            };
        }

        setSpans();

        if (mEditable) {
            int limit = intent.getIntExtra(Def.Communication.KEY_LIMIT, -1);
            if (limit == -1) {
                limit = mApp.getLimit();
            }
            mDateTimeDialogFragment = DateTimeDialogFragment.newInstance(mThing, limit);
        }
        mExecutor = Executors.newSingleThreadExecutor();

        mActionList = new ThingActionsList();
        mActionList.setAddActionCallback(new ThingActionsList.AddActionCallback() {
            @Override
            public void onAddAction() {
                updateUndoRedoActionButtonState();
            }
        });
    }

    private void setupThingFromIntent() {
        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();
        if (Intent.ACTION_SEND.equals(action)) {
            if (type.contains("image/") || type.contains("video/")
                    || type.contains("audio/")) {
                Uri data = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                String pathName = UriPathConverter.getLocalPathName(this, data);
                if (pathName != null) {
                    mThing.setAttachment(AttachmentHelper.SIGNAL + getTypePathName(pathName));
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> datas = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            StringBuilder sb = new StringBuilder();
            for (Uri data : datas) {
                String pathName = UriPathConverter.getLocalPathName(this, data);
                if (pathName != null) {
                    String typePathName = getTypePathName(pathName);
                    if (typePathName != null) {
                        sb.append(AttachmentHelper.SIGNAL).append(typePathName);
                    }
                }
            }
            mThing.setAttachment(sb.toString());
        }
        String title = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        if (title != null) {
            mThing.setTitle(title);
        }
        String content = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (content != null) {
            mThing.setContent(content);
        }
    }

    private void updateThingAndItsPosition(long oriId) {
        ThingManager manager = ThingManager.getInstance(mApp);
        if (mType == CREATE) {
            long newId = manager.getHeaderId();
            List<Long> runningDetailActivities = App.getRunningDetailActivities();
            int index = runningDetailActivities.lastIndexOf(oriId);
            runningDetailActivities.set(index, newId);
            mThing = new Thing(newId, Thing.NOTE, 0, newId);
            return;
        }

        List<Thing> things = manager.getThings();
        final int size = things.size();
        int i;
        for (i = 0; i < size; i++) {
            Thing temp = things.get(i);
            if (temp.getId() == oriId) {
                mThing = temp;
                mPosition = i;
                break;
            }
        }
        if (i == size) {
            mThing = ThingDAO.getInstance(mApp).getThingById(oriId);
            mPosition = -1;
        }
    }

    @Override
    protected void findViews() {
        mFlRoot = f(R.id.fl_root_detail);

        mStatusBar       = f(R.id.view_status_bar);
        mActionbar       = f(R.id.actionbar);
        mIbBack          = f(R.id.ib_back);
        mActionBarShadow = f(R.id.actionbar_shadow);
        mImageCover      = f(R.id.view_image_cover);

        mRvImageAttachment = f(R.id.rv_image_attachment);
        mRvImageAttachment.setNestedScrollingEnabled(false);

        mScrollView   = f(R.id.sv_detail);
        mEtTitle      = f(R.id.et_title);
        mEtContent    = f(R.id.et_content);
        mTvUpdateTime = f(R.id.tv_update_time);

        mRvCheckList = f(R.id.rv_check_list);
        mRvCheckList.setItemAnimator(null);
        mRvCheckList.setNestedScrollingEnabled(false);

        mRvAudioAttachment = f(R.id.rv_audio_attachment);
        mRvAudioAttachment.setNestedScrollingEnabled(false);
        ((SimpleItemAnimator) mRvAudioAttachment.getItemAnimator())
                .setSupportsChangeAnimations(false);

        mFlQuickRemindAsBt = f(R.id.fl_quick_remind_as_bt);
        cbQuickRemind      = f(R.id.cb_quick_remind);
        tvQuickRemind      = f(R.id.tv_quick_remind);

        mNormalSnackbar = new Snackbar(mApp, Snackbar.NORMAL, mFlRoot, null);
        if (mEditable) {
            mLlMoveChecklist     = f(R.id.ll_move_checklist);
            mTvMoveChecklistAsBt = f(R.id.tv_move_checklist_as_bt);

            View decorView = getWindow().getDecorView();
            mColorPicker = new ColorPicker(this, decorView, Def.PickerType.COLOR_NO_ALL);
            quickRemindPicker = new DateTimePicker(this, decorView,
                    Def.PickerType.AFTER_TIME, mThing.getColor());
            quickRemindPicker.setAnchor(tvQuickRemind);
        }
    }

    @Override
    protected void initUI() {
        DisplayUtil.expandLayoutAboveLollipop(this);
        DisplayUtil.expandStatusBarAboveKitkat(mStatusBar);

        int color = mThing.getColor();
        if (mEditable) {
            AppUpdateHelper.updateFrom1_1_4To1_1_5(this, color);
        }

        int thingType = mThing.getType();
        int thingState = mThing.getState();

        if (thingType == Thing.REMINDER || thingType == Thing.WELCOME_REMINDER) {
            mIbBack.setImageResource(R.drawable.act_back_reminder);
            mIbBack.setContentDescription(getString(R.string.cd_back_reminder));
        } else if (thingType == Thing.HABIT || thingType == Thing.WELCOME_HABIT) {
            mIbBack.setImageResource(R.drawable.act_back_habit);
            mIbBack.setContentDescription(getString(R.string.cd_back_habit));
        } else if (thingType == Thing.GOAL || thingType == Thing.WELCOME_GOAL) {
            mIbBack.setImageResource(R.drawable.act_back_goal);
            mIbBack.setContentDescription(getString(R.string.cd_back_goal));
        } else {
            mIbBack.setImageResource(R.drawable.act_back_note);
            mIbBack.setContentDescription(getString(R.string.cd_back_note));
        }

        mFlRoot.setBackgroundColor(color);

        if (!DeviceUtil.hasLollipopApi()) {
            if (mEditable) {
                int appAccentColor = ContextCompat.getColor(this, R.color.app_accent);
                DisplayUtil.setSelectionHandlersColor(mEtTitle, appAccentColor);
                DisplayUtil.setSelectionHandlersColor(mEtContent, appAccentColor);
            }
        }

        if (!mEditable) {
            mEtTitle.setKeyListener(null);
            mEtContent.setKeyListener(null);
            cbQuickRemind.setEnabled(thingState == Thing.UNDERWAY);
        } else {
            mColorPicker.pickForUI(DisplayUtil.getColorIndex(mThing.getColor(), this));
        }

        mEtTitle.setText(mThing.getTitleToDisplay());
        if (mThing.isPrivate()) {
            setAsPrivateThingUiAndAddAction();
        }

        String content = mThing.getContent();
        if (mType == CREATE) {
            mEtContent.requestFocus();
            setScrollViewMarginTop(true);
            mEtContent.setText(content);
            mEtContent.setSelection(content.length());
        } else {
            if (CheckListHelper.isCheckListStr(content)) {
                mEtContent.setVisibility(View.GONE);
                mRvCheckList.setVisibility(View.VISIBLE);
                if (mEditable) {
                    mLlMoveChecklist.setVisibility(View.VISIBLE);
                }

                List<String> items = CheckListHelper.toCheckListItems(content, false);
                if (!mEditable) {
                    int state = mThing.getState();
                    items.remove("2");
                    if (state == Thing.FINISHED) {
                        items.remove("3");
                        items.remove("4");
                    } else if (items.get(0).equals("2")) {
                        items.remove("3");
                        items.remove("4");
                    }
                    mCheckListAdapter = new CheckListAdapter(
                            this, CheckListAdapter.EDITTEXT_UNEDITABLE, items);
                } else {
                    mCheckListAdapter = new CheckListAdapter(
                            this, CheckListAdapter.EDITTEXT_EDITABLE, items);
                    mCheckListAdapter.setEtTouchListener(mSpannableTouchListener);
                    mCheckListAdapter.setEtClickListener(mEtContentClickListener);
                    mCheckListAdapter.setEtLongClickListener(mEtContentLongClickListener);
                    mCheckListAdapter.setItemsChangeCallback(new CheckListItemsChangeCallback());
                    mCheckListAdapter.setActionCallback(new CheckListActionCallback());
                }

                setMoveChecklistEvent();

                mLlmCheckList = new LinearLayoutManager(this);
                mRvCheckList.setAdapter(mCheckListAdapter);
                mRvCheckList.setLayoutManager(mLlmCheckList);
            } else {
                mEtContent.setVisibility(View.VISIBLE);
                mEtContent.setText(content);
            }
        }

        final String attachment = mThing.getAttachment();
        if (AttachmentHelper.isValidForm(attachment)) {
            if (!PermissionUtil.hasStoragePermission(this)) {
                // make ui normal before asking for permission
                setScrollViewMarginTop(true);
            }
            doWithPermissionChecked(
                    new SimplePermissionCallback(this) {
                        @Override
                        public void onGranted() {
                            Pair<List<String>, List<String>> items =
                                    AttachmentHelper.toAttachmentItems(attachment);
                            if (!items.first.isEmpty()) {
                                initImageAttachmentUI(items.first);
                            } else {
                                setScrollViewMarginTop(true);
                            }

                            if (!items.second.isEmpty()) {
                                initAudioAttachmentUI(items.second);
                            }
                        }
                        @Override
                        public void onDenied() {
                            super.onDenied();
                            finish();
                        }

                    },
                    Def.Communication.REQUEST_PERMISSION_LOAD_THING,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE);

        } else {
            setScrollViewMarginTop(true);
        }

        mTvUpdateTime.getPaint().setTextSkewX(-0.25f);

        if (mType == CREATE) {
            mTvUpdateTime.setText("");
            quickRemindPicker.pickForUI(8);
            rhParams.setReminderAfterTime(quickRemindPicker.getPickedTimeAfter());
        } else {
            if (mThing.getCreateTime() == mThing.getUpdateTime()) {
                mTvUpdateTime.setText(R.string.create_at);
            } else {
                mTvUpdateTime.setText(R.string.update_at);
            }
            if (!LocaleUtil.isChinese(this)) {
                mTvUpdateTime.append(" ");
            }
            mTvUpdateTime.append(DateTimeUtil.getDateTimeStrAt(mThing.getUpdateTime(), this, true));

            if (mReminder != null) {
                cbQuickRemind.setChecked(mReminder.getState() == Reminder.UNDERWAY);

                if (mEditable) {
                    quickRemindPicker.pickForUI(9);
                }

                long reminderInMillis = mReminder.getNotifyTime();
                tvQuickRemind.setText(DateTimeUtil.getDateTimeStrAt(reminderInMillis, this, false));
                rhParams.setReminderInMillis(reminderInMillis);
                int state = mReminder.getState();
                if (state != Reminder.UNDERWAY || thingState != Reminder.UNDERWAY) {
                    tvQuickRemind.append(", " + Reminder.getStateDescription(thingState, state, this));
                }
            } else if (mHabit != null) {
                cbQuickRemind.setChecked(mEditable);
                if (mEditable) {
                    quickRemindPicker.pickForUI(9);
                }
                int habitType = mHabit.getType();
                String habitDetail = mHabit.getDetail();
                tvQuickRemind.setText(DateTimeUtil.getDateTimeStrRec(
                        mApp, habitType, habitDetail));
                rhParams.setHabitType(habitType);
                rhParams.setHabitDetail(habitDetail);
            } else {
                if (mEditable) {
                    quickRemindPicker.pickForUI(8);
                    rhParams.setReminderAfterTime(quickRemindPicker.getPickedTimeAfter());
                } else {
                    f(R.id.ll_quick_remind).setVisibility(View.GONE);
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                            mScrollView.getLayoutParams();
                    params.setMargins(0, params.topMargin, 0, 0);
                }
            }
            cbQuickRemind.setContentDescription(
                    getString(R.string.remind_me) + tvQuickRemind.getText());
        }

        updateDescriptions(mThing.getColor());
    }

    @Override
    protected void setActionbar() {
        setSupportActionBar(mActionbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(null);
        }
        mIbBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                returnToThingsActivity(true, true);
            }
        });
    }

    @Override
    protected void setEvents() {
        setScrollEvents();

        final Window window = getWindow();

        // set keyboard events.
        if (mEditable) {
            KeyboardUtil.addKeyboardCallback(window, new KeyboardUtil.KeyboardCallback() {
                @Override
                public void onKeyboardShow(int keyboardHeight) {
                    updateQuickRemindShadow();
                }

                @Override
                public void onKeyboardHide() {
                    updateQuickRemindShadow();
                    quickRemindPicker.dismiss();
                }
            });
            if (DeviceUtil.hasKitKatApi()) {
                KeyboardUtil.addKeyboardCallback(window, new KeyboardUtil.KeyboardCallback() {

                    @Override
                    public void onKeyboardShow(int keyboardHeight) {
                        if (mFlRoot.getPaddingBottom() == 0) {
                            //set the padding of the contentView for the keyboard
                            mFlRoot.setPadding(0, 0, 0, keyboardHeight);
                        }
                    }

                    @Override
                    public void onKeyboardHide() {
                        if (mFlRoot.getPaddingBottom() != 0) {
                            //reset the padding of the contentView
                            mFlRoot.setPadding(0, 0, 0, 0);
                        }
                    }
                });
            }
        }

        mEtContent.setOnClickListener(mEtContentClickListener);
        mEtContent.setOnLongClickListener(mEtContentLongClickListener);
        mEtContent.setOnTouchListener(mSpannableTouchListener);

        if (mEditable) {
            setEditTextWatchers();
            setColorPickerEvent();
            setQuickRemindEvents();
        }

        shouldAddToActionList = true;
    }

    private void setEditTextWatchers() {
        mEtTitle.addTextChangedListener(new ActionTextWatcher(ThingAction.UPDATE_TITLE));
        mEtContent.addTextChangedListener(new ActionTextWatcher(ThingAction.UPDATE_CONTENT));
    }

    private void setMoveChecklistEvent() {
        if (!mEditable) return;

        if (mChecklistTouchHelper == null) {
            mChecklistTouchHelper = new ItemTouchHelper(new CheckListTouchCallback());
        }

        mTvMoveChecklistAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean isDragging = mCheckListAdapter.isDragging();
                boolean above17 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1;
                if (!isDragging) {
                    mTvMoveChecklistAsBt.setText(R.string.act_back_from_move_checklist);
                    if (above17) {
                        mTvMoveChecklistAsBt.setCompoundDrawablesRelativeWithIntrinsicBounds(
                                R.drawable.act_back_from_move_checklist, 0, 0, 0);
                    } else {
                        mTvMoveChecklistAsBt.setCompoundDrawablesWithIntrinsicBounds(
                                R.drawable.act_back_from_move_checklist, 0, 0, 0);
                    }
                    mCheckListAdapter.setDragging(true);
                    mChecklistTouchHelper.attachToRecyclerView(mRvCheckList);
                } else {
                    mTvMoveChecklistAsBt.setText(R.string.act_move_check_list);
                    if (above17) {
                        mTvMoveChecklistAsBt.setCompoundDrawablesRelativeWithIntrinsicBounds(
                                R.drawable.act_move_checklist, 0, 0, 0);
                    } else {
                        mTvMoveChecklistAsBt.setCompoundDrawablesWithIntrinsicBounds(
                                R.drawable.act_move_checklist, 0, 0, 0);
                    }
                    mCheckListAdapter.setDragging(false);
                    mChecklistTouchHelper.attachToRecyclerView(null);
                }
                mCheckListAdapter.notifyDataSetChanged();
            }
        });

        mCheckListAdapter.setIvStateTouchCallback(new CheckListAdapter.IvStateTouchCallback() {
            @Override
            public void onTouch(int pos) {
                mChecklistTouchHelper.startDrag(
                        mRvCheckList.findViewHolderForAdapterPosition(pos));
            }
        });
    }

    public void updateDescriptions(int color) {
        String title;
        if (mType == CREATE) {
            title = getString(R.string.title_create_thing);
        } else {
            if (mEditable) {
                title = getString(R.string.title_edit_thing);
            } else {
                title = "";
            }
            if (!LocaleUtil.isChinese(mApp)) {
                title += " ";
            }
            title += Thing.getTypeStr(getThingTypeAfter(), mApp);
        }
        mFlRoot.setContentDescription(title);
        if (DeviceUtil.hasLollipopApi()) {
            BitmapDrawable bmd = (BitmapDrawable) getDrawable(R.mipmap.ic_launcher);
            if (bmd != null) {
                Bitmap bm = bmd.getBitmap();
                try {
                    setTaskDescription(new ActivityManager.TaskDescription(title, bm, color));
                } catch (Exception ignored) {
                }
            }
        }
    }

    private @Thing.Type int getThingTypeAfter() {
        if (mHabitFinishedThisTime) return Thing.HABIT;
        long time = rhParams.getReminderTime();
        if (cbQuickRemind.isChecked()) {
            if (mReminder != null && mReminder.getNotifyTime() == time) {
                return mThing.getType();
            } else {
                if (rhParams.getHabitDetail() != null) {
                    return Thing.HABIT;
                } else return Reminder.getType(rhParams.getReminderTime(), System.currentTimeMillis());
            }
        } else {
            int typeBefore = mThing.getType();
            if (typeBefore == Thing.REMINDER || typeBefore == Thing.GOAL) {
                int reminderState = mReminder.getState();
                if ((reminderState == Reminder.REMINDED || reminderState == Reminder.EXPIRED)
                        && mReminder.getNotifyTime() == time) {
                    return typeBefore;
                } else {
                    return Thing.NOTE;
                }
            } else if (typeBefore == Thing.HABIT) {
                return Thing.NOTE;
            } else {
                return typeBefore;
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        @Thing.Type int thingType = mThing.getType();
        if (thingType == Thing.HEADER || thingType >= Thing.NOTIFICATION_UNDERWAY) {
            return true;
        }
        MenuInflater inflater = getMenuInflater();
        if (mType == CREATE) {
            inflater.inflate(R.menu.menu_detail_create, menu);
        } else {
            int state = mThing.getState();
            if (state == Thing.UNDERWAY) {
                if (thingType == Thing.HABIT) {
                    Habit habit = HabitDAO.getInstance(getApplicationContext())
                            .getHabitById(mThing.getId());
                    if (habit.allowFinish()) {
                        inflater.inflate(R.menu.menu_detail_habit_allow_finish, menu);
                    } else {
                        inflater.inflate(R.menu.menu_detail_habit_normal, menu);
                    }
                } else {
                    inflater.inflate(R.menu.menu_detail_underway, menu);
                }
                if (CheckListHelper.isCheckListStr(mThing.getContent())) {
                    toggleCheckListActionItem(menu, true);
                }
                togglePrivateThingActionItem(menu, !mThing.isPrivate());
            } else if (state == Thing.FINISHED) {
                inflater.inflate(R.menu.menu_detail_finished, menu);
                if (thingType != Thing.HABIT) {
                    menu.findItem(R.id.act_check_habit_detail).setVisible(false);
                }
            } else {
                inflater.inflate(R.menu.menu_detail_deleted, menu);
                if (thingType != Thing.HABIT) {
                    menu.findItem(R.id.act_check_habit_detail).setVisible(false);
                }
            }
        }
        updateUndoRedoActionButtonState();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.act_add_attachment:
                AddAttachmentDialogFragment.newInstance().show(getFragmentManager(),
                        AddAttachmentDialogFragment.TAG);
                break;
            case R.id.act_check_list:
                toggleCheckList();
                break;
            case R.id.act_change_color:
                mColorPicker.show();
                break;
            case R.id.act_set_as_private_thing:
                togglePrivateThing();
                break;
            case R.id.act_undo:
                undoOrRedo(mActionList.undo(), true);
                break;
            case R.id.act_redo:
                undoOrRedo(mActionList.redo(), false);
                break;
            case R.id.act_check_habit_detail:
                HabitDetailDialogFragment hddf = HabitDetailDialogFragment.newInstance();
                hddf.setHabit(mHabit);
                hddf.show(getFragmentManager(), HabitDetailDialogFragment.TAG);
                break;
            case R.id.act_share:
                chooseHowToShareThing();
                break;
            case R.id.act_finish_this_time_habit:
                HabitDAO.getInstance(mApp).finishOneTime(mHabit);
                mHabitFinishedThisTime = true;
                rhParams.setHabitType(mHabit.getType());
                rhParams.setHabitDetail(mHabit.getDetail());
                returnToThingsActivity(true, false);
                break;
            case R.id.act_finish:
                returnToThingsActivity(Thing.FINISHED);
                break;
            case R.id.act_delete:
                returnToThingsActivity(Thing.DELETED);
                break;
            case R.id.act_restore:
                returnToThingsActivity(Thing.UNDERWAY);
                break;
            case R.id.act_copy_content:
                copyContent();
                break;
            case R.id.act_export:
                doWithPermissionChecked(
                        new SimplePermissionCallback(DetailActivity.this) {
                            @Override
                            public void onGranted() {
                                ThingExporter.startExporting(
                                        DetailActivity.this, getAccentColor(), mThing);
                            }
                        }, Def.Communication.REQUEST_PERMISSION_EXPORT_DETAIL,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE);
                break;
            case R.id.act_abandon_new_thing:
                int resultCode = Def.Communication.RESULT_ABANDON_NEW_THING;
                Intent intent = new Intent(Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI);
                intent.putExtra(Def.Communication.KEY_RESULT_CODE, resultCode);
                if (shouldSendBroadCast()) {
                    sendBroadCastToUpdateMainUI(intent, resultCode);
                } else {
                    setResult(resultCode, intent);
                }
                finish();
                break;
            default:break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void finish() {
        List<Long> detailActivities = App.getRunningDetailActivities();
        detailActivities.remove(mThing.getId());
        mRemoveDetailActivityInstance = true;

        if (mType == CREATE) {
            createActivitiesCount--;
            mMinusCreateActivitiesCount = true;
        }
        super.finish();
    }

    private void toggleCheckListActionItem(Menu menu, boolean toDisable) {
        MenuItem item = menu.findItem(R.id.act_check_list);
        if (toDisable) {
            item.setIcon(R.drawable.act_disable_check_list);
            item.setTitle(getString(R.string.act_disable_check_list));
        } else {
            item.setIcon(R.drawable.act_enable_check_list);
            item.setTitle(getString(R.string.act_enable_check_list));
        }
    }

    private void togglePrivateThingActionItem(Menu menu, boolean set) {
        MenuItem item = menu.findItem(R.id.act_set_as_private_thing);
        if (set) {
            item.setTitle(R.string.act_set_as_private_thing);
        } else {
            item.setTitle(R.string.act_cancel_private_thing);
        }
    }

    private void toggleCheckList() {
        String before;
        if (mRvCheckList.getVisibility() == View.VISIBLE) {
            before = CheckListHelper.toCheckListStr(mCheckListAdapter.getItems());
            toggleCheckListActionItem(mActionbar.getMenu(), false);
            mEtContent.setVisibility(View.VISIBLE);
            mRvCheckList.setVisibility(View.GONE);
            if (mLlMoveChecklist != null) {
                // don't know why this is possible but some user's log showed that this can happen
                mLlMoveChecklist.setVisibility(View.GONE);
            }
            mChecklistTouchHelper.attachToRecyclerView(null);

            String contentStr = CheckListHelper.toContentStr(mCheckListAdapter.getItems());
            boolean temp = shouldAddToActionList;
            shouldAddToActionList = false;
            mEtContent.setText(contentStr);
            shouldAddToActionList = temp;

            if (contentStr.isEmpty()) {
                KeyboardUtil.showKeyboard(mEtContent);
            } else {
                KeyboardUtil.hideKeyboard(getCurrentFocus());
            }
        } else {
            toggleCheckListActionItem(mActionbar.getMenu(), true);
            mRvCheckList.setVisibility(View.VISIBLE);
            mEtContent.setVisibility(View.GONE);
            if (mLlmCheckList != null) {
                mLlMoveChecklist.setVisibility(View.VISIBLE);
            }

            String content = mEtContent.getText().toString();
            before = content;
            List<String> items = CheckListHelper.toCheckListItems(content, true);
            boolean focusFirst = false;
            if (items.size() == 2 && items.get(0).equals("0")) {
                focusFirst = true;
            }

            if (mCheckListAdapter == null) {
                mCheckListAdapter = new CheckListAdapter(
                        this, CheckListAdapter.EDITTEXT_EDITABLE, items);
                mCheckListAdapter.setEtTouchListener(mSpannableTouchListener);
                mCheckListAdapter.setEtClickListener(mEtContentClickListener);
                mCheckListAdapter.setEtLongClickListener(mEtContentLongClickListener);
                mLlmCheckList = new LinearLayoutManager(this);
                mCheckListAdapter.setItemsChangeCallback(new CheckListItemsChangeCallback());
                mCheckListAdapter.setActionCallback(new CheckListActionCallback());
            } else {
                mCheckListAdapter.setItems(items);
            }
            mRvCheckList.setAdapter(mCheckListAdapter);
            mRvCheckList.setLayoutManager(mLlmCheckList);

            setMoveChecklistEvent();

            if (focusFirst) {
                mRvCheckList.post(new Runnable() {
                    @Override
                    public void run() {
                        CheckListAdapter.EditTextHolder holder = (CheckListAdapter.EditTextHolder)
                                mRvCheckList.findViewHolderForAdapterPosition(0);
                        KeyboardUtil.showKeyboard(holder.et);
                    }
                });
            } else {
                KeyboardUtil.hideKeyboard(getCurrentFocus());
            }
        }
        if (shouldAddToActionList) {
            mActionList.addAction(new ThingAction(
                    ThingAction.TOGGLE_CHECKLIST, before, null));
        }
    }

    private boolean cannotSetAsPrivateThing() {
        return mEtTitle.getText().toString().isEmpty();
    }

    private boolean isPrivateThing() {
        Drawable start = mEtTitle.getCompoundDrawables()[0];
        return start != null;
    }

    private void togglePrivateThing() {
        SharedPreferences sp = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE);
        String pwd = sp.getString(Def.Meta.KEY_PRIVATE_PASSWORD, null);
        if (pwd == null) {
            warnNoPassword();
        } else {
            boolean isPrivateThing = isPrivateThing();
            if (isPrivateThing) {
                tryToCancelPrivateThing();
            } else {
                setAsPrivateThingUiAndAddAction();
                togglePrivateThingActionItem(mActionbar.getMenu(), false);
            }
        }
    }

    private void warnNoPassword() {
        final AlertDialogFragment adf = new AlertDialogFragment();
        adf.setShowCancel(false);

        int color = getAccentColor();
        adf.setTitleColor(color);
        adf.setConfirmColor(color);

        adf.setTitle(getString(R.string.cannot_set_as_private_thing_title));
        adf.setContent(getString(R.string.warning_should_set_password_first));
        adf.show(getFragmentManager(), AlertDialogFragment.TAG);
    }

    private void tryToCancelPrivateThing() {
        if (!mThing.isPrivate()) {
            // the thing isn't private before, user just want to see what will happen
            // by clicking "set as/cancel private thing" button.
            cancelPrivateThingUiAndAddAction();
            if (shouldAddToActionList) {
                mActionList.addAction(new ThingAction(
                        ThingAction.TOGGLE_PRIVATE, null, null));
            }
            return;
        }

        String cp = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
                .getString(Def.Meta.KEY_PRIVATE_PASSWORD, null);
        final boolean shouldAddToActionList = this.shouldAddToActionList;
        AuthenticationHelper.authenticate(
                this, getAccentColor(), getString(R.string.act_cancel_private_thing), cp,
                new AuthenticationHelper.AuthenticationCallback() {
                    @Override
                    public void onAuthenticated() {
                        cancelPrivateThingUiAndAddAction();
                        if (shouldAddToActionList) {
                            mActionList.addAction(new ThingAction(
                                    ThingAction.TOGGLE_PRIVATE, null, null));
                        }
                    }

                    @Override
                    public void onCancel() { }
                });
    }

    private void cancelPrivateThingUiAndAddAction() {
        togglePrivateThingActionItem(mActionbar.getMenu(), true);

        mEtTitle.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);

        int paddingSide = (int) (screenDensity * 20);
        mEtTitle.setPadding(paddingSide, mEtTitle.getPaddingTop(), paddingSide, 0);
    }

    private void setAsPrivateThingUiAndAddAction() {
        mEtTitle.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_locked_small, 0, 0, 0);

        int paddingSide = (int) (screenDensity * 16);
        mEtTitle.setPadding(paddingSide, mEtTitle.getPaddingTop(), paddingSide, 0);

        if (shouldAddToActionList) {
            mActionList.addAction(
                    new ThingAction(ThingAction.TOGGLE_PRIVATE, null, null));
        }
    }

    private void alertNoTitleWhenSetPrivateThing() {
        AlertDialogFragment adf = new AlertDialogFragment();
        adf.setShowCancel(false);

        int color = getAccentColor();
        adf.setTitleColor(color);
        adf.setConfirmColor(color);

        adf.setTitle(getString(R.string.cannot_set_as_private_thing_title));
        adf.setContent(getString(R.string.warning_title_should_not_be_empty));
        adf.show(getFragmentManager(), AlertDialogFragment.TAG);
    }

    private void updateUndoRedoActionButtonState() {
        MenuItem undoItem = mActionbar.getMenu().findItem(R.id.act_undo);
        if (undoItem == null) {
            return;
        }
        undoItem.setEnabled(mActionList.canUndo());

        MenuItem redoItem = mActionbar.getMenu().findItem(R.id.act_redo);
        redoItem.setEnabled(mActionList.canRedo());
    }

    private void undoOrRedo(ThingAction action, boolean undo) {
        shouldAddToActionList = false;
        Object to = undo ? action.getBefore() : action.getAfter();
        switch (action.getType()) {
            case ThingAction.UPDATE_TITLE:
                undoOrRedoTitleContent(action, undo, mEtTitle);
                break;
            case ThingAction.UPDATE_CONTENT:
                undoOrRedoTitleContent(action, undo, mEtContent);
                break;
            case ThingAction.TOGGLE_CHECKLIST: {
                toggleCheckList();
                String from = (String) action.getBefore();
                if (CheckListHelper.isCheckListStr(from) && undo) {
                    mCheckListAdapter.setItems(CheckListHelper.toCheckListItems(from, false));
                }
                break;
            }
            case ThingAction.UPDATE_CHECKLIST:
                mCheckListAdapter.setItems(CheckListHelper.toCheckListItems((String) to, false));
                break;
            case ThingAction.MOVE_CHECKLIST: {
                Object from = undo ? action.getAfter() : action.getBefore();
                moveChecklist((int) from, (int) to);
                break;
            }
            case ThingAction.UPDATE_COLOR:
                mColorPicker.pickForUI(DisplayUtil.getColorIndex((int) to, mApp));
                changeColor((int) to);
                break;
            case ThingAction.ADD_ATTACHMENT:
                // before: attachmentTypePathName, after: position
                undoOrRedoAddAttachment(action, undo);
                break;
            case ThingAction.DELETE_ATTACHMENT:
                // before:position, after:attachmentTypePathName
                undoOrRedoDeleteAttachment(action, undo);
                break;
            case ThingAction.MOVE_ATTACHMENT:
                Object from = undo ? action.getAfter() : action.getBefore();
                moveAttachment((int) from, (int) to,
                        action.getExtras().getBoolean(ThingAction.KEY_ATTACHMENT_TYPE));
                break;
            case ThingAction.TOGGLE_REMINDER_OR_HABIT:
                cbQuickRemind.toggle();
                break;
            case ThingAction.UPDATE_REMINDER_OR_HABIT:
                undoOrRedoReminderHabit(action, undo);
                break;
            case ThingAction.TOGGLE_PRIVATE:
                togglePrivateThing();
                break;
            default:break;
        }
        updateUndoRedoActionButtonState();
        shouldAddToActionList = true;
    }

    private void undoOrRedoTitleContent(ThingAction action, boolean undo, EditText et) {
        Object to = undo ? action.getBefore() : action.getAfter();
        int cursorPos;
        if (undo) {
            cursorPos = action.getExtras().getInt(ThingAction.KEY_CURSOR_POS_BEFORE);
        } else {
            cursorPos = action.getExtras().getInt(ThingAction.KEY_CURSOR_POS_AFTER);
        }
        et.setText(to.toString());
        et.setSelection(cursorPos);
    }

    private void undoOrRedoAddAttachment(ThingAction action, boolean undo) {
        // before: attachmentTypePathName, after: position
        String atpn  = (String) action.getBefore();
        int position = (int) action.getAfter();
        if (undo) {
            if (atpn.startsWith(String.valueOf(AttachmentHelper.AUDIO))) {
                notifyAudioAttachmentsChanged(false, position);
            } else {
                notifyImageAttachmentsChanged(false, position);
            }
        } else {
            attachmentTypePathName = atpn;
            addAttachment(position);
        }
    }

    private void undoOrRedoDeleteAttachment(ThingAction action, boolean undo) {
        // before:position, after:attachmentTypePathName
        int position = (int) action.getBefore();
        String atpn  = (String) action.getAfter();
        if (undo) {
            attachmentTypePathName = atpn;
            addAttachment(position);
        } else {
            if (atpn.startsWith(String.valueOf(AttachmentHelper.AUDIO))) {
                notifyAudioAttachmentsChanged(false, position);
            } else {
                notifyImageAttachmentsChanged(false, position);
            }
        }
    }

    private void undoOrRedoReminderHabit(ThingAction action, boolean undo) {
        ReminderHabitParams to = (ReminderHabitParams) (undo ? action.getBefore() : action.getAfter());
        rhParams = new ReminderHabitParams(to);

        boolean isCheckedBefore = action.getExtras().getBoolean(ThingAction.KEY_CHECKBOX_STATE);
        if (!isCheckedBefore) {
            // Means we checked it later. Now that we are doing undo, we should toggle it back.
            cbQuickRemind.toggle();
        }
        tvQuickRemind.setText(rhParams.getDateTimeStr());

        if (undo) {
            int pickedBefore = action.getExtras().getInt(ThingAction.KEY_PICKED_BEFORE);
            quickRemindPicker.pickForUI(pickedBefore);
        } else {
            int pickedAfter = action.getExtras().getInt(ThingAction.KEY_PICKED_AFTER);
            quickRemindPicker.pickForUI(pickedAfter);
        }
        updateDescriptions(getAccentColor());
        updateBackImage();
    }

    private void chooseHowToShareThing() {
        final TwoOptionsDialogFragment todf = new TwoOptionsDialogFragment();
        todf.setStartAction(R.drawable.act_share_text_image, R.string.act_share_thing_text_image,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        todf.dismiss();
                        SendInfoHelper.shareThing(DetailActivity.this, mThing);
                    }
                });
        todf.setEndAction(R.drawable.act_take_long_screenshot, R.string.act_share_thing_screenshot,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        todf.dismiss();
                        shareThingInScreenshot();
                    }
                });
        todf.show(getFragmentManager(), TwoOptionsDialogFragment.TAG);
    }

    private void shareThingInScreenshot() {
        if (!canShareThingInScreenshot()) {
            mNormalSnackbar.setMessage("fuck you");
            mFlRoot.postDelayed(mShowNormalSnackbar, KeyboardUtil.HIDE_DELAY);
            return;
        }

        final LoadingDialogFragment ldf = new LoadingDialogFragment();
        int color = getAccentColor();
        ldf.setAccentColor(color);
        ldf.setTitle(getString(R.string.please_wait));
        ldf.setContent(getString(R.string.generating_screenshot));
        ldf.show(getFragmentManager(), LoadingDialogFragment.TAG);

        final View typeInfoLayout   = f(R.id.ll_type_info_screenshot);
        final List<Integer> didList = prepareForScreenshot(typeInfoLayout);
        ScreenshotHelper.startScreenshot(mScrollView, color,
                new ScreenshotHelper.ShareCallback(
                        this, ldf, SendInfoHelper.getShareThingTitle(this, mThing)) {
                    @Override
                    public void onTaskDone(File file) {
                        super.onTaskDone(file);
                        ScreenshotHelper.hideTypeInfo(typeInfoLayout);
                        ScreenshotHelper.updateThingUiAfterScreenshot(didList,
                                mEtTitle, mEtContent,
                                mRvCheckList, mCheckListAdapter, mLlMoveChecklist,
                                mImageAttachmentAdapter,
                                mRvAudioAttachment, mAudioAttachmentAdapter);
                    }
                });
    }

    private List<Integer> prepareForScreenshot(View typeInfoLayout) {
        ScreenshotHelper.showTypeInfo(typeInfoLayout, mThing.getId(),
                getThingTypeAfter(), mThing.getState(), rhParams);
        // I also hate this method for its long parameters but what can I do?
        return ScreenshotHelper.updateThingUiBeforeScreenshot(
                mEditable, mEtTitle, mEtContent,
                mRvCheckList, mCheckListAdapter, mLlMoveChecklist,
                mRvImageAttachment, mImageAttachmentAdapter,
                mRvAudioAttachment, mAudioAttachmentAdapter);
    }

    private boolean canShareThingInScreenshot() {
        boolean canShare = true;
        if (mEtTitle.getText().toString().isEmpty()
                && mRvImageAttachment.getVisibility() != View.VISIBLE
                && mRvAudioAttachment.getVisibility() != View.VISIBLE) {
            if (mEtContent.getVisibility() == View.VISIBLE) {
                if (mEtContent.getText().toString().isEmpty()) {
                    canShare = false;
                }
            } else if (mRvCheckList.getVisibility() == View.VISIBLE) {
                if (mEditable && mCheckListAdapter.getItemCount() == 1) {
                    canShare = false;
                }
            }
        }
        return canShare;
    }

    private void copyContent() {
        String content = "";
        if (mEtContent.getVisibility() == View.VISIBLE) {
            content = mEtContent.getText().toString();
        } else if (mRvCheckList != null && mRvCheckList.getVisibility() == View.VISIBLE
                && mCheckListAdapter != null) {
            content = CheckListHelper.toContentStr(
                    CheckListHelper.toCheckListStr(mCheckListAdapter.getItems()), "X  ", "√  ");
        }

        ClipboardManager clipboardManager = (ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        ClipData clipData = ClipData.newPlainText(
                getString(R.string.act_copy_content), content);
        clipboardManager.setPrimaryClip(clipData);
        Toast.makeText(this, R.string.success_clipboard,
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mEditable) {
            mColorPicker.dismiss();
            quickRemindPicker.dismiss();
            mNormalSnackbar.dismiss();
        }

        setSpans();

        if (mRvImageAttachment.getVisibility() == View.VISIBLE) {
            int size = mImageAttachmentAdapter.getItemCount();
            AttachmentHelper.setImageRecyclerViewHeight(mRvImageAttachment, size, mMaxSpanImage);
            mImageLayoutManager.setSpanCount(size < mMaxSpanImage ? size : mMaxSpanImage);
            mImageAttachmentAdapter.notifyDataSetChanged();
        }

        if (mRvAudioAttachment.getVisibility() == View.VISIBLE) {
            AttachmentHelper.setAudioRecyclerViewHeight(mRvAudioAttachment,
                    mAudioAttachmentAdapter.getItemCount(), mSpanAudio);
            mAudioLayoutManager.setSpanCount(mSpanAudio);
            mAudioAttachmentAdapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!mRemoveDetailActivityInstance) {
            App.getRunningDetailActivities().remove(mThing.getId());
        }
        if (mType == CREATE && !mMinusCreateActivitiesCount) {
            createActivitiesCount--;
        }
    }

    private void setSpans() {
        boolean isTablet = DisplayUtil.isTablet(this);
        mMaxSpanImage = isTablet ? 3 : 2;
        mSpanAudio = isTablet ? 2 : 1;
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mMaxSpanImage += 2;
            mSpanAudio++;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            if (requestCode == Def.Communication.REQUEST_CHOOSE_MEDIA_FILE) {
                String pathName = UriPathConverter.getLocalPathName(this, data.getData());
                if (pathName == null) {
                    mNormalSnackbar.setMessage(R.string.error_cannot_add_from_network);
                    mFlRoot.postDelayed(mShowNormalSnackbar, KeyboardUtil.HIDE_DELAY);
                    return;
                }
                attachmentTypePathName = getTypePathName(pathName);
                if (attachmentTypePathName == null) {
                    mNormalSnackbar.setMessage(R.string.error_unsupported_file_type);
                    mFlRoot.postDelayed(mShowNormalSnackbar, KeyboardUtil.HIDE_DELAY);
                    return;
                }
            }
            addAttachment(0);
        } else if (resultCode == Def.Communication.RESULT_UPDATE_IMAGE_DONE) {
            List<String> items = data.getStringArrayListExtra(
                    Def.Communication.KEY_TYPE_PATH_NAME);
            mImageAttachmentAdapter.setItems(items);
            int sizeAfter = items.size();
            if (sizeAfter == 0) {
                mRvImageAttachment.setVisibility(View.GONE);
                setScrollViewMarginTop(true);
            } else {
                int spanAfter = sizeAfter < mMaxSpanImage ? sizeAfter : mMaxSpanImage;
                AttachmentHelper.setImageRecyclerViewHeight(
                        mRvImageAttachment, items.size(), mMaxSpanImage);
                mImageLayoutManager.setSpanCount(spanAfter);
                mImageAttachmentAdapter.notifyDataSetChanged();
            }
        }
    }

    @Override
    public void onBackPressed() {
        returnToThingsActivity(true, true);
    }

    public void showNormalSnackbar(int stringRes) {
        mNormalSnackbar.setMessage(stringRes);
        mNormalSnackbar.show();
    }

    private String getTypePathName(String pathName) {
        String postfix = FileUtil.getPostfix(pathName);
        if (AttachmentHelper.isImageFile(postfix)) {
            return AttachmentHelper.IMAGE + pathName;
        } else if (AttachmentHelper.isVideoFile(postfix)) {
            File file = new File(pathName);
            MediaPlayer player = MediaPlayer.create(this, Uri.fromFile(file));
            String ret = null;
            if (player.getVideoHeight() != 0) {
                ret = AttachmentHelper.VIDEO + pathName;
            } else if (AttachmentHelper.isAudioFile(postfix)) {
                ret = AttachmentHelper.AUDIO + pathName;
            }
            player.reset();
            player.release();
            return ret;
        } else if (AttachmentHelper.isAudioFile(postfix)) {
            return AttachmentHelper.AUDIO + pathName;
        }
        return null;
    }

    public void addAttachment(int position) {
        if (attachmentTypePathName == null) {
            Log.e(TAG, "adding attachment while attachmentTypePathName is null!");
            return;
        }
        if (!attachmentTypePathName.startsWith(String.valueOf(AttachmentHelper.AUDIO))) {
            if (mImageAttachmentAdapter == null) {
                initImageAttachmentUI(
                        new ArrayList<>(Collections.singletonList(attachmentTypePathName)));
            } else {
                notifyImageAttachmentsChanged(true, position);
            }
        } else {
            if (mAudioAttachmentAdapter == null) {
                initAudioAttachmentUI(
                        new ArrayList<>(Collections.singletonList(attachmentTypePathName)));
            } else {
                notifyAudioAttachmentsChanged(true, position);
            }
        }
        if (shouldAddToActionList) {
            mActionList.addAction(new ThingAction(
                    ThingAction.ADD_ATTACHMENT, attachmentTypePathName, position));
        }
    }

    /**
     * Set margins to {@link mScrollView}. If there is an image, marginTop will be set to 0.
     */
    private void setScrollViewMarginTop(boolean hasMarginTop) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mScrollView.getLayoutParams();
        if (hasMarginTop) {
            float mt = screenDensity * 56;
            if (DeviceUtil.hasKitKatApi()) {
                mt += DisplayUtil.getStatusbarHeight(this);
            }
            params.setMargins(0, (int) mt, 0, params.bottomMargin);
        } else {
            params.setMargins(0, 0, 0, params.bottomMargin);
        }
    }

    private void initImageAttachmentUI(List<String> items) {
        setImageCover();

        int size = items.size();
        mRvImageAttachment.setVisibility(View.VISIBLE);
        AttachmentHelper.setImageRecyclerViewHeight(mRvImageAttachment, size, mMaxSpanImage);
        setScrollViewMarginTop(false);

        mImageAttachmentAdapter = new ImageAttachmentAdapter(this, mEditable,
                items, new ImageAttachmentClickCallback(),
                mEditable ? new ImageAttachmentRemoveCallback() : null);
        mImageLayoutManager = new GridLayoutManager(this, size < mMaxSpanImage ? size : mMaxSpanImage);
        mRvImageAttachment.setAdapter(mImageAttachmentAdapter);
        mRvImageAttachment.setLayoutManager(mImageLayoutManager);

        if (mEditable) {
            new ItemTouchHelper(new AttachmentTouchCallback(true))
                    .attachToRecyclerView(mRvImageAttachment);
        }
    }

    private void notifyImageAttachmentsChanged(boolean add, int position) {
        List<String> items = mImageAttachmentAdapter.getItems();

        int sizeBefore = items.size();
        int spanBefore = sizeBefore < mMaxSpanImage ? sizeBefore : mMaxSpanImage;
        int sizeAfter = add ? sizeBefore + 1 : sizeBefore - 1;
        int spanAfter = sizeAfter < mMaxSpanImage ? sizeAfter : mMaxSpanImage;

        if (add) {
            if (mRvImageAttachment.getVisibility() != View.VISIBLE) {
                setImageCover();
                mRvImageAttachment.setVisibility(View.VISIBLE);
                setScrollViewMarginTop(false);
            }
            items.add(position, attachmentTypePathName);
            AttachmentHelper.setImageRecyclerViewHeight(mRvImageAttachment, sizeAfter, mMaxSpanImage);
            if (spanAfter == spanBefore) {
                if (sizeBefore == mMaxSpanImage) {
                    mImageAttachmentAdapter.notifyDataSetChanged();
                } else {
                    mImageAttachmentAdapter.notifyItemInserted(position);
                }
            } else {
                mImageLayoutManager.setSpanCount(spanAfter);
                mImageAttachmentAdapter.notifyDataSetChanged();
            }
        } else {
            items.remove(position);
            if (sizeAfter == 0) {
                mImageCover.setVisibility(View.GONE);
                mRvImageAttachment.setVisibility(View.GONE);
                setScrollViewMarginTop(true);
                return;
            }
            AttachmentHelper.setImageRecyclerViewHeight(mRvImageAttachment, sizeAfter, mMaxSpanImage);
            if (spanAfter == spanBefore) {
                if (sizeBefore == mMaxSpanImage + 1) {
                    mImageAttachmentAdapter.notifyDataSetChanged();
                } else {
                    mImageAttachmentAdapter.notifyItemRemoved(position);
                }
            } else {
                mImageLayoutManager.setSpanCount(spanAfter);
                mImageAttachmentAdapter.notifyDataSetChanged();
            }
        }
    }

    private void initAudioAttachmentUI(List<String> items) {
        mRvAudioAttachment.setVisibility(View.VISIBLE);
        AttachmentHelper.setAudioRecyclerViewHeight(mRvAudioAttachment, items.size(), mSpanAudio);
        mAudioAttachmentAdapter = new AudioAttachmentAdapter(this, getAccentColor(), mEditable, items,
                mEditable ? new AudioAttachmentRemoveCallback() : null);
        mAudioLayoutManager = new GridLayoutManager(this, mSpanAudio);
        mRvAudioAttachment.setAdapter(mAudioAttachmentAdapter);
        mRvAudioAttachment.setLayoutManager(mAudioLayoutManager);

        if (mEditable) {
            new ItemTouchHelper(new AttachmentTouchCallback(false))
                    .attachToRecyclerView(mRvAudioAttachment);
        }
    }

    private void notifyAudioAttachmentsChanged(boolean add, int position) {
        List<String> items = mAudioAttachmentAdapter.getItems();
        int sizeBefore = items.size();
        int sizeAfter = add ? sizeBefore + 1 : sizeBefore - 1;

        if (add) {
            if (mRvAudioAttachment.getVisibility() != View.VISIBLE) {
                mRvAudioAttachment.setVisibility(View.VISIBLE);
            }

            int index = mAudioAttachmentAdapter.getPlayingIndex();
            if (index != -1 && index > position) {
                mAudioAttachmentAdapter.setPlayingIndex(index + 1);
            }

            items.add(position, attachmentTypePathName);
            AttachmentHelper.setAudioRecyclerViewHeight(mRvAudioAttachment, sizeAfter, mSpanAudio);
            if (sizeAfter == 1) {
                mAudioAttachmentAdapter.notifyDataSetChanged();
            } else {
                mAudioAttachmentAdapter.notifyItemInserted(position);
            }
        } else {
            items.remove(position);
            if (sizeAfter == 0) {
                mRvAudioAttachment.setVisibility(View.GONE);
                return;
            }
            AttachmentHelper.setAudioRecyclerViewHeight(mRvAudioAttachment, sizeAfter, mSpanAudio);
            mAudioAttachmentAdapter.notifyItemRemoved(position);
        }
    }

    private void setImageCover() {
        FrameLayout.LayoutParams fl = (FrameLayout.LayoutParams) mImageCover.getLayoutParams();
        fl.height = (int) (66 * screenDensity);
        if (DeviceUtil.hasKitKatApi()) {
            fl.height += DisplayUtil.getStatusbarHeight(this);
        }
        mImageCover.setVisibility(View.VISIBLE);
    }

    public int getAccentColor() {
        return ((ColorDrawable) mFlRoot.getBackground()).getColor();
    }

    private void setScrollEvents() {
        mActionbar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mScrollView.smoothScrollTo(0, 0);
            }
        });

        final int barsHeight = (int) (screenDensity * 56);
        final int statusBarHeight = DisplayUtil.getStatusbarHeight(this);

        final int statusBarOffset; // if is in translucent mode, we should also consider height of status bar.
        if (!DeviceUtil.hasKitKatApi()) {
            statusBarOffset = 0;
        } else {
            statusBarOffset = statusBarHeight;
        }

        mScrollView.setOnScrollChangeListener(new NestedScrollView.OnScrollChangeListener() {
            @Override
            public void onScrollChange(NestedScrollView v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                int imageHeight = 0;
                if (mRvImageAttachment.getVisibility() == View.VISIBLE) {
                    imageHeight = mRvImageAttachment.getHeight();
                }

                // the scrollY that action bar shadow should begin to appear
                float shadowAY;
                // the scrollY that action bar shadow should totally appear
                float shadowTY;
                if (imageHeight == 0) {
                    shadowAY = screenDensity * 14;
                } else {
                    shadowAY = imageHeight -
                            barsHeight - statusBarOffset + screenDensity * 20;
                }
                shadowTY = shadowAY + screenDensity * 20;
                if (scrollY >= shadowTY) {
                    mActionBarShadow.setAlpha(1.0f);
                } else if (scrollY <= shadowAY) {
                    mActionBarShadow.setAlpha(0);
                } else {
                    float progress = scrollY - shadowAY;
                    mActionBarShadow.setAlpha(progress / (shadowTY - shadowAY));
                }

                if (imageHeight != 0) {
                    float abAY = shadowAY - screenDensity * 12;
                    float abTY = abAY + screenDensity * 16;
                    int abAlpha;
                    if (scrollY <= abAY) {
                        abAlpha = 0;
                    } else if (scrollY >= abTY) {
                        abAlpha = 255;
                    } else {
                        float progress = (scrollY - abAY) / (abTY - abAY);
                        abAlpha = (int) (progress * 255);
                    }

                    int color = getAccentColor();
                    color = DisplayUtil.getTransparentColor(color, abAlpha);
                    mStatusBar.setBackgroundColor(color);
                    mActionbar.setBackgroundColor(color);
                }
            }
        });

        if (f(R.id.ll_quick_remind).getVisibility() == View.VISIBLE) {
            ViewTreeObserver observer = mScrollView.getViewTreeObserver();
            observer.addOnScrollChangedListener(new ViewTreeObserver.OnScrollChangedListener() {
                @Override
                public void onScrollChanged() {
                    updateQuickRemindShadow();
                }
            });
            observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    updateQuickRemindShadow();
                }
            });
        }
    }

    private void updateQuickRemindShadow() {
        Rect windowRect = new Rect();
        getWindow().getDecorView().getWindowVisibleDisplayFrame(windowRect);

        final View quickRemindShadow = f(R.id.quick_remind_shadow);
        final int quickRemindHeight = (int) (screenDensity * 56);
        final int statusBarHeight = DisplayUtil.getStatusbarHeight(this);

        int scrollY = mScrollView.getScrollY();
        int childHeight = mScrollView.getChildAt(0).getHeight();
        int marginTop = statusBarHeight;
        if (mRvImageAttachment.getVisibility() != View.VISIBLE) {
            marginTop += quickRemindHeight;
        } else if (DeviceUtil.hasKitKatApi()) {
            marginTop -= statusBarHeight;
        }

        float aY = childHeight - windowRect.bottom
                + quickRemindHeight + marginTop - screenDensity * 40;
        float tY = aY + screenDensity * 24;
        if (scrollY <= aY) {
            quickRemindShadow.setAlpha(1.0f);
        } else if (scrollY >= tY) {
            quickRemindShadow.setAlpha(0);
        } else {
            float progress = tY - scrollY;
            quickRemindShadow.setAlpha(progress / (tY - aY));
        }
    }

    private void setColorPickerEvent() {
        mColorPicker.setPickedListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int colorFrom = getAccentColor();
                int colorTo   = mColorPicker.getPickedColor();
                if (colorFrom == colorTo) {
                    return;
                }
                changeColor(mColorPicker.getPickedColor());
                if (shouldAddToActionList) {
                    mActionList.addAction(new ThingAction(
                            ThingAction.UPDATE_COLOR, colorFrom, colorTo));
                }
            }
        });
    }

    private void changeColor(int colorTo) {
        changingColor = true;

        int colorFrom = ((ColorDrawable) mFlRoot.getBackground()).getColor();
        quickRemindPicker.setAccentColor(colorTo);
        quickRemindPicker.pickForUI(quickRemindPicker.getPickedIndex());
        ObjectAnimator.ofObject(mFlRoot, "backgroundColor",
                new ArgbEvaluator(), colorFrom, colorTo).setDuration(600).start();

        updateDescriptions(colorTo);

        colorFrom = ((ColorDrawable) mActionbar.getBackground()).getColor();
        int alpha = Color.alpha(colorFrom);
        colorTo = DisplayUtil.getTransparentColor(colorTo, alpha);
        ObjectAnimator.ofObject(mActionbar, "backgroundColor",
                new ArgbEvaluator(), colorFrom, colorTo).setDuration(600).start();
        ObjectAnimator.ofObject(mStatusBar, "backgroundColor",
                new ArgbEvaluator(), colorFrom, colorTo).setDuration(600).start();

        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(666);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                changingColor = false;
            }
        });
    }

    private void setQuickRemindEvents() {
        cbQuickRemind.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                updateDescriptions(getAccentColor());
                updateBackImage();
                cbQuickRemind.setContentDescription(
                        getString(R.string.remind_me) + tvQuickRemind.getText());
                if (shouldAddToActionList) {
                    mActionList.addAction(new ThingAction(
                            ThingAction.TOGGLE_REMINDER_OR_HABIT, null, null));
                }
                tryToNotifyKeepAlarms();
            }
        });
        if (mThing.getState() == Thing.UNDERWAY) {
            mFlQuickRemindAsBt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    quickRemindPicker.show();
                }
            });
        }
        quickRemindPicker.setPickedListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int pickedBefore = quickRemindPicker.getPreviousIndex();
                int pickedAfter  = quickRemindPicker.getPickedIndex();
                if (pickedAfter == 9) {
                    mDateTimeDialogFragment.setPickedBefore(pickedBefore);
                    mDateTimeDialogFragment.show(getFragmentManager(), DateTimeDialogFragment.TAG);
                } else {
                    ReminderHabitParams before = new ReminderHabitParams(rhParams);
                    boolean isChecked = cbQuickRemind.isChecked();
                    rhParams.reset();
                    rhParams.setReminderAfterTime(quickRemindPicker.getPickedTimeAfter());
                    if (cbQuickRemind.isChecked()) {
                        updateDescriptions(getAccentColor());
                        updateBackImage();
                    } else {
                        boolean temp = shouldAddToActionList;
                        shouldAddToActionList = false;
                        cbQuickRemind.setChecked(true);
                        shouldAddToActionList = temp;
                    }

                    if (shouldAddToActionList) {
                        ThingAction action = new ThingAction(
                                ThingAction.UPDATE_REMINDER_OR_HABIT, before,
                                new ReminderHabitParams(rhParams));
                        action.getExtras().putBoolean(
                                ThingAction.KEY_CHECKBOX_STATE, isChecked);
                        action.getExtras().putInt(ThingAction.KEY_PICKED_BEFORE, pickedBefore);
                        action.getExtras().putInt(ThingAction.KEY_PICKED_AFTER,  pickedAfter);
                        mActionList.addAction(action);
                    }
                }
            }
        });
    }

    private void tryToNotifyKeepAlarms() {
//        SharedPreferences sp = getSharedPreferences(Def.Meta.META_DATA_NAME, MODE_PRIVATE);
//        if (sp.getBoolean(Def.Meta.KEY_NOTIFY_KEEP_ALARMS, true)) {
//            sp.edit().putBoolean(
//                    Def.Meta.KEY_NOTIFY_KEEP_ALARMS, false).apply();
//        }
    }

    private void alertCancel(@StringRes int titleRes, @StringRes int contentRes,
                             AlertDialogFragment.CancelListener cancelListener) {
        final AlertDialogFragment adf = new AlertDialogFragment();
        int color = getAccentColor();
        adf.setTitleColor(color);
        adf.setConfirmColor(color);
        adf.setTitle(getString(titleRes));
        adf.setContent(getString(contentRes));
        adf.setConfirmListener(new AlertDialogFragment.ConfirmListener() {
            @Override
            public void onConfirm() {
                returnToThingsActivity(true, false);
            }
        });
        adf.setCancelListener(cancelListener);
        adf.show(getFragmentManager(), AlertDialogFragment.TAG);
    }

    private void alertForCancellingHabit() {
        alertCancel(R.string.alert_cancel_habit_title, R.string.alert_cancel_habit_content,
                new AlertDialogFragment.CancelListener() {
                    @Override
                    public void onCancel() {
                        mIbBack.setImageResource(R.drawable.act_back_habit);
                        mIbBack.setContentDescription(getString(R.string.cd_back_habit));
                        cbQuickRemind.setChecked(true);
                        quickRemindPicker.pickForUI(9);
                        rhParams.reset();
                        int habitType = mHabit.getType();
                        String habitDetail = mHabit.getDetail();
                        rhParams.setHabitType(habitType);
                        rhParams.setHabitDetail(habitDetail);
                        tvQuickRemind.setText(DateTimeUtil.getDateTimeStrRec(
                                mApp, habitType, habitDetail));
                    }
                });
    }

    private void alertForCancellingGoal() {
        alertCancel(R.string.alert_cancel_goal_title, R.string.alert_cancel_goal_content,
                new AlertDialogFragment.CancelListener() {
                    @Override
                    public void onCancel() {
                        mIbBack.setImageResource(R.drawable.act_back_goal);
                        mIbBack.setContentDescription(getString(R.string.cd_back_goal));
                        cbQuickRemind.setChecked(true);
                        quickRemindPicker.pickForUI(9);
                        rhParams.reset();
                        long reminderInMillis = mReminder.getNotifyTime();
                        rhParams.setReminderInMillis(reminderInMillis);
                        tvQuickRemind.setText(DateTimeUtil.getDateTimeStrAt(
                                reminderInMillis, DetailActivity.this, false));
                    }
                });
    }

    private void createHabit(long id, HabitDAO habitDAO) {
        Habit habit = new Habit(id, rhParams.getHabitType(), 0, rhParams.getHabitDetail(),
                "", "", System.currentTimeMillis(), 0);
        habit.initHabitReminders();
        habitDAO.createHabit(habit);
    }

    private boolean prepareForReturnNormally() {
        if (changingColor) return false;

        if (mAudioAttachmentAdapter != null && mAudioAttachmentAdapter.getPlayingIndex() != -1) {
            mAudioAttachmentAdapter.stopPlaying();
        }

        if (!mEditable) {
            setResult(Def.Communication.RESULT_NO_UPDATE);
            finish();
            return false;
        }

        KeyboardUtil.hideKeyboard(getCurrentFocus());
        mNormalSnackbar.dismiss();

        if (App.isSomethingUpdatedSpecially()) {
            updateThingAndItsPosition(mThing.getId());
        }

        return true;
    }

    private void returnToThingsActivity(boolean alertForPrivateThing, boolean alertForCancelling) {
        if (!prepareForReturnNormally()) {
            return;
        }

        if (alertForPrivateThing && isPrivateThing() && cannotSetAsPrivateThing()) {
            alertNoTitleWhenSetPrivateThing();
            return;
        }

        long reminderTime = rhParams.getReminderTime();

        int typeBefore = mThing.getType();
        int typeAfter = getThingTypeAfter();
        boolean isReminderBefore = Thing.isReminderType(typeBefore);
        boolean isReminderAfter = Thing.isReminderType(typeAfter);
        boolean isHabitBefore = typeBefore == Thing.HABIT;
        boolean isHabitAfter = typeAfter == Thing.HABIT;

        if (cbQuickRemind.isChecked() && rhParams.getHabitDetail() == null
                && reminderTime <= System.currentTimeMillis()) {
            mNormalSnackbar.setMessage(R.string.error_later);
            mFlRoot.postDelayed(mShowNormalSnackbar, 120);
            return;
        }

        String title      = getThingTitle();
        String content    = getThingContent();
        String attachment = getThingAttachment();

        if (mType == CREATE && title.isEmpty() && content.isEmpty() && attachment.isEmpty()) {
            createEmptyThing();
            return;
        }

        Boolean reminderUpdated = setOrUpdateReminder(isReminderBefore, isReminderAfter,
                isHabitBefore, alertForCancelling, reminderTime, typeBefore, typeAfter);
        if (reminderUpdated == null) {
            return;
        }

        Boolean habitUpdated = setOrUpdateHabit(isHabitBefore, isHabitAfter, alertForCancelling);
        if (habitUpdated == null) {
            return;
        }

        int color = getAccentColor();
        Intent intent = new Intent();

        Integer resultCode = createOrUpdateThing(title, content, attachment,
                typeBefore, typeAfter, color, reminderUpdated, habitUpdated, intent);
        if (resultCode == null) {
            return;
        }

        afterCreateOrUpdateThing(intent, resultCode);
    }

    private Integer createOrUpdateThing(
            String title, String content, String attachment,
            @Thing.Type int typeBefore, @Thing.Type int typeAfter, int color,
            boolean reminderUpdated, boolean habitUpdated, Intent intent) {
        Integer resultCode = Def.Communication.RESULT_NO_UPDATE;
        if (mType == CREATE) {
            resultCode = createThing(title, content, attachment, typeAfter, color, intent);
        } else {
            boolean noUpdate = Thing.noUpdate(mThing, title, content, attachment, typeAfter, color)
                    && !reminderUpdated && !habitUpdated && !mHabitFinishedThisTime;
            if (noUpdate) {
                setResult(resultCode);
            } else {
                if (title.isEmpty() && content.isEmpty() && attachment.isEmpty()) {
                    returnToThingsActivity(Thing.DELETED_FOREVER);
                    resultCode = null;
                } else {
                    resultCode = updateThing(title, content, attachment,
                            typeBefore, typeAfter, color, intent);
                }
            }
        }
        return resultCode;
    }

    private void afterCreateOrUpdateThing(Intent intent, int resultCode) {
        if (App.isSomethingUpdatedSpecially()
                && resultCode != Def.Communication.RESULT_NO_UPDATE) {
            App.setShouldJustNotifyDataSetChanged(true);
        }

        if (shouldSendBroadCast()) {
            sendBroadCastToUpdateMainUI(intent, resultCode);
        }
        AppWidgetHelper.updateSingleThingAppWidgets(this, mThing.getId());
        AppWidgetHelper.updateThingsListAppWidgetsForType(this, mThing.getType());

        finish();
    }

    private void returnToThingsActivity(int stateAfter) {
        if (mAudioAttachmentAdapter != null && mAudioAttachmentAdapter.getPlayingIndex() != -1) {
            mAudioAttachmentAdapter.stopPlaying();
        }
        KeyboardUtil.hideKeyboard(getCurrentFocus());

        ThingManager manager = ThingManager.getInstance(mApp);
        Intent intent = new Intent();
        intent.putExtra(Def.Communication.KEY_THING, mThing);

        if (App.isSomethingUpdatedSpecially()) {
            updateThingAndItsPosition(mThing.getId());
            App.setShouldJustNotifyDataSetChanged(true);
        }

        intent.putExtra(Def.Communication.KEY_POSITION, mPosition);
        intent.putExtra(Def.Communication.KEY_STATE_AFTER, stateAfter);

        if (mPosition == -1) {
            int stateBefore = mThing.getState();
            mThing = Thing.getSameCheckStateThing(mThing, stateBefore, stateAfter);
            ThingDAO dao = ThingDAO.getInstance(mApp);
            dao.updateState(mThing, mThing.getLocation(), stateBefore, stateAfter, true, true,
                    false, dao.getHeaderId(), true);

            long id = mThing.getId();
            int type = mThing.getType();
            if (type == Thing.GOAL && stateAfter == Thing.UNDERWAY) {
                ReminderDAO reminderDAO = ReminderDAO.getInstance(mApp);
                Reminder goal = reminderDAO.getReminderById(id);
                ThingManager.getInstance(mApp).getUndoGoals().add(goal);
                reminderDAO.resetGoal(goal);
            }
            if (type == Thing.HABIT) {
                HabitDAO habitDAO = HabitDAO.getInstance(mApp);
                long curTime = System.currentTimeMillis();
                if (stateAfter == Thing.UNDERWAY) {
                    habitDAO.updateHabitToLatest(id, true, true);
                    habitDAO.addHabitIntervalInfo(id, curTime + ";");
                } else {
                    habitDAO.addHabitIntervalInfo(id, curTime + ",");
                }
            }
        } else {
            intent.putExtra(Def.Communication.KEY_CALL_CHANGE,
                    manager.updateState(
                            mThing, mPosition, mThing.getLocation(),
                            mThing.getState(), stateAfter, false, true));
        }

        int resultCode = Def.Communication.RESULT_UPDATE_THING_STATE_DIFFERENT;
        if (shouldSendBroadCast()) {
            sendBroadCastToUpdateMainUI(intent, resultCode);
        } else {
            setResult(resultCode, intent);
        }
        AppWidgetHelper.updateSingleThingAppWidgets(this, mThing.getId());
        AppWidgetHelper.updateThingsListAppWidgetsForType(this, mThing.getType());
        finish();
    }

    private String getThingTitle() {
        String title = mEtTitle.getText().toString();
        if (isPrivateThing()) {
            title = Thing.PRIVATE_THING_PREFIX + title;
        }
        return title;
    }

    private String getThingContent() {
        String content;
        if (mRvCheckList.getVisibility() == View.VISIBLE) {
            content = CheckListHelper.toCheckListStr(mCheckListAdapter.getItems());
            if (mHabitFinishedThisTime) {
                content = content.replaceAll(CheckListHelper.SIGNAL + "1",
                        CheckListHelper.SIGNAL + "0");
            }
        } else {
            content = mEtContent.getText().toString();
        }
        return content;
    }

    private String getThingAttachment() {
        List<String> imageItems = null, audioItems = null;
        if (mRvImageAttachment.getVisibility() == View.VISIBLE) {
            imageItems = mImageAttachmentAdapter.getItems();
        }
        if (mRvAudioAttachment.getVisibility() == View.VISIBLE) {
            audioItems = mAudioAttachmentAdapter.getItems();
        }
        String attachment = AttachmentHelper.toAttachmentStr(imageItems, audioItems);
        List<String> attachmentsToDelete = AttachmentHelper
                .getAttachmentsToDelete(mThing.getAttachment(), attachment);
        if (attachmentsToDelete != null && !attachmentsToDelete.isEmpty()) {
            mApp.addAttachmentsToDeleteFile(attachmentsToDelete);
        }
        return attachment;
    }

    private void createEmptyThing() {
        int resultCode = Def.Communication.RESULT_CREATE_BLANK_THING;
        setResult(resultCode);
        if (App.isSomethingUpdatedSpecially()) {
            App.setShouldJustNotifyDataSetChanged(true);
        }
        if (shouldSendBroadCast()) {
            sendBroadCastToUpdateMainUI(new Intent(), resultCode);
        }
        finish();
    }

    private Boolean setOrUpdateReminder(
            boolean isReminderBefore, boolean isReminderAfter, boolean isHabitBefore,
            boolean alertForCancelling, long reminderTime,
            @Thing.Type int typeBefore, @Thing.Type int typeAfter) {
        Boolean reminderUpdated = true;
        ReminderDAO rDao = ReminderDAO.getInstance(mApp);
        if (!isReminderBefore && isReminderAfter) {
            if (!isHabitBefore || !alertForCancelling) {
                rDao.create(new Reminder(mThing.getId(), reminderTime));
            }
        } else if (isReminderBefore && !isReminderAfter) {
            if (typeBefore == Thing.GOAL && alertForCancelling) {
                alertForCancellingGoal();
                reminderUpdated = null;
            } else {
                rDao.delete(mThing.getId());
            }
        } else if (isReminderBefore) {
            if (mReminder.getNotifyTime() == reminderTime && typeBefore == typeAfter) {
                reminderUpdated = false;
            } else {
                if (typeBefore == Thing.GOAL && alertForCancelling) {
                    alertForCancellingGoal();
                    reminderUpdated = null;
                } else {
                    mReminder.setNotifyTime(reminderTime);
                    mReminder.setState(Reminder.UNDERWAY);
                    mReminder.initNotifyMinutes();
                    mReminder.setUpdateTime(System.currentTimeMillis());
                    rDao.update(mReminder);
                }
            }
        } else {
            reminderUpdated = false;
        }
        return reminderUpdated;
    }

    private Boolean setOrUpdateHabit(
            boolean isHabitBefore, boolean isHabitAfter, boolean alertForCancelling) {
        HabitDAO hDao = HabitDAO.getInstance(mApp);
        Boolean habitUpdated = true;
        long id = mThing.getId();
        if (!isHabitBefore && isHabitAfter) {
            createHabit(id, hDao);
        } else if (isHabitBefore && !isHabitAfter) {
            if (alertForCancelling) {
                alertForCancellingHabit();
                habitUpdated = null;
            } else {
                hDao.deleteHabit(id);
            }
        } else if (isHabitBefore) {
            if (Habit.noUpdate(mHabit, rhParams.getHabitType(), rhParams.getHabitDetail())) {
                habitUpdated = false;
            } else {
                if (alertForCancelling) {
                    alertForCancellingHabit();
                    habitUpdated = null;
                } else {
                    for (;;) if (hDao.deleteHabit(id)) {
                        // ensure the old habit is deleted successfully
                        break;
                    }
                    createHabit(id, hDao);
                }
            }
        } else {
            habitUpdated = false;
        }
        return habitUpdated;
    }

    private int createThing(String title, String content, String attachment,
                             @Thing.Type int typeAfter, int color, Intent intent) {
        mThing.setTitle(title);
        mThing.setContent(content);
        mThing.setAttachment(attachment);
        mThing.setType(typeAfter);
        mThing.setColor(color);

        long currentTime = System.currentTimeMillis();
        mThing.setCreateTime(currentTime);
        mThing.setUpdateTime(currentTime);

        intent.putExtra(Def.Communication.KEY_THING, mThing);
        int resultCode = Def.Communication.RESULT_CREATE_THING_DONE;

        // Create thing here directly to database. Solve the problem that if ThingsActivity
        // is destroyed while user share something from other apps to EverythingDone. In that
        // case, ThingsActivity won't receive broadcast to handle creation and that thing
        // will be missed.
        // Another case is that there are more than 1 create-type DetailActivity instance.
        if (shouldSendBroadCast() || createActivitiesCount > 1) {
            boolean change = ThingManager.getInstance(mApp).create(mThing, true, true);
            intent.putExtra(Def.Communication.KEY_CALL_CHANGE,  change);
            intent.putExtra(Def.Communication.KEY_CREATED_DONE, true);
        } else {
            setResult(resultCode, intent);
        }

//        WeakReference<ThingsActivity> wr = App.thingsActivityWR;
//        if (wr == null || wr.get() == null || createActivitiesCount > 1) {
//            ThingManager.getInstance(mApp).create(mThing, true, true);
//            intent.putExtra(Def.Communication.KEY_CREATED_DONE, true);
//        } else if (!shouldSendBroadCast()) {
//            setResult(resultCode, intent);
//        }

        return resultCode;
    }

    private int updateThing(
            String title, String content, String attachment,
            @Thing.Type int typeBefore, @Thing.Type int typeAfter,
            int color, Intent intent) {
        mThing.setTitle(title);
        mThing.setContent(content);
        mThing.setAttachment(attachment);
        mThing.setType(typeAfter);
        mThing.setColor(color);
        mThing.setUpdateTime(System.currentTimeMillis());

        intent.putExtra(Def.Communication.KEY_TYPE_BEFORE, typeBefore);
        boolean sameType = mApp.getLimit() ==
                Def.LimitForGettingThings.ALL_UNDERWAY
                || Thing.sameType(typeBefore, typeAfter);
        int resultCode;
        if (sameType) {
            resultCode = Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME;
        } else {
            intent.putExtra(Def.Communication.KEY_THING, mThing);
            resultCode = Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_DIFFERENT;
        }

        if (mPosition != -1) {
            intent.putExtra(Def.Communication.KEY_POSITION, mPosition);
            int updateResult = ThingManager.getInstance(mApp).update(
                    typeBefore, mThing, mPosition, true);
            if (updateResult != 0) {
                intent.putExtra(Def.Communication.KEY_CALL_CHANGE, updateResult == 1);
            }
        } else {
            ThingDAO.getInstance(mApp).update(typeBefore, mThing, true, true);
        }

        if (!shouldSendBroadCast()) {
            setResult(resultCode, intent);
        }

        return resultCode;
    }

    private boolean shouldSendBroadCast() {
        return mSenderName.equals(ReminderReceiver.TAG)
            || mSenderName.equals(HabitReceiver.TAG)
            || mSenderName.equals(AutoNotifyReceiver.TAG)
            || mSenderName.equals("intent")
            || mSenderName.equals(App.class.getName())
            || mSenderName.equals(CreateWidget.TAG)
            || mSenderName.equals(AppWidgetHelper.TAG);
    }

    private void sendBroadCastToUpdateMainUI(Intent intent, int resultCode) {
        List<Long> runningDetailActivities = App.getRunningDetailActivities();
        int size = runningDetailActivities.size();
        if (size > 1 && resultCode != Def.Communication.RESULT_NO_UPDATE) {
            // A DetailActivity has been opened before and this is another one opened from notification.
            App.setSomethingUpdatedSpecially(true);
        }

        intent.putExtra(Def.Communication.KEY_RESULT_CODE, resultCode);
        intent.setAction(Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI);
        sendBroadcast(intent);
    }

    public void updateBackImage() {
        if (cbQuickRemind.isChecked()) {
            if (rhParams.getHabitDetail() != null) {
                mIbBack.setImageResource(R.drawable.act_back_habit);
                mIbBack.setContentDescription(getString(R.string.cd_back_habit));
            } else {
                if (Reminder.getType(rhParams.getReminderTime(),
                        System.currentTimeMillis()) == Thing.GOAL) {
                    mIbBack.setImageResource(R.drawable.act_back_goal);
                    mIbBack.setContentDescription(getString(R.string.cd_back_goal));
                } else {
                    mIbBack.setImageResource(R.drawable.act_back_reminder);
                    mIbBack.setContentDescription(getString(R.string.cd_back_reminder));
                }
            }
        } else {
            int thingType = mThing.getType();
            if (Thing.isTypeReminder(thingType)) {
                mIbBack.setImageResource(R.drawable.act_back_reminder);
                mIbBack.setContentDescription(getString(R.string.cd_back_reminder));
            } else if (Thing.isTypeHabit(thingType)) {
                mIbBack.setImageResource(R.drawable.act_back_habit);
                mIbBack.setContentDescription(getString(R.string.cd_back_habit));
            } else if (Thing.isTypeGoal(thingType)) {
                mIbBack.setImageResource(R.drawable.act_back_goal);
                mIbBack.setContentDescription(getString(R.string.cd_back_goal));
            } else {
                mIbBack.setImageResource(R.drawable.act_back_note);
                mIbBack.setContentDescription(getString(R.string.cd_back_note));
            }
        }
    }

    class ActionTextWatcher implements TextWatcher {

        private int mActionType;

        private String mBefore;
        private int mCursorPosBefore;

        private EditText mEditText;

        ActionTextWatcher(int actionType) {
            mActionType = actionType;
            mEditText = (mActionType == ThingAction.UPDATE_TITLE ? mEtTitle : mEtContent);
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            mBefore = s.toString();
            mCursorPosBefore = mEditText.getSelectionEnd();
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) { }

        @Override
        public void afterTextChanged(Editable s) {
            if (shouldAddToActionList) {
                ThingAction action = new ThingAction(mActionType, mBefore, s.toString());
                action.getExtras().putInt(
                        ThingAction.KEY_CURSOR_POS_BEFORE, mCursorPosBefore);
                action.getExtras().putInt(
                        ThingAction.KEY_CURSOR_POS_AFTER, mEditText.getSelectionStart());
                mActionList.addAction(action);
            }
        }
    }

    class CheckListActionCallback implements CheckListAdapter.ActionCallback {

        @Override
        public void onAction(String before, String after) {
            if (shouldAddToActionList) {
                mActionList.addAction(
                        new ThingAction(ThingAction.UPDATE_CHECKLIST, before, after));
            }
        }
    }

    class CheckListItemsChangeCallback implements CheckListAdapter.ItemsChangeCallback {

        @Override
        public void onInsert(int position) {
            CheckListAdapter.EditTextHolder holder = (CheckListAdapter.EditTextHolder)
                    mRvCheckList.findViewHolderForAdapterPosition(position);
            if (holder != null) {
                KeyboardUtil.showKeyboard(holder.et);
            }
        }

        @Override
        public void onRemove(int position, String item, int cursorPos) {
            if (item == null) {
                CheckListAdapter.EditTextHolder holder = (CheckListAdapter.EditTextHolder)
                        mRvCheckList.findViewHolderForAdapterPosition(position);
                if (holder == null) return;
                if (position != -1) {
                    holder.et.requestFocus();
                    holder.et.setSelection(cursorPos);
                } else {
                    KeyboardUtil.hideKeyboard(getCurrentFocus());
                }
            } else {
                KeyboardUtil.hideKeyboard(getCurrentFocus());
            }
        }
    }

    class ImageAttachmentClickCallback implements ImageAttachmentAdapter.ClickCallback {

        @Override
        public void onClick(View v, int pos) {
            Intent intent = new Intent(DetailActivity.this, ImageViewerActivity.class);
            intent.putExtra(Def.Communication.KEY_COLOR, getAccentColor());
            intent.putExtra(Def.Communication.KEY_EDITABLE, mEditable);
            intent.putExtra(Def.Communication.KEY_TYPE_PATH_NAME,
                    (ArrayList) mImageAttachmentAdapter.getItems());
            intent.putExtra(Def.Communication.KEY_POSITION, pos);

            int w = v.getWidth();
            int startX = 0, startY = 0;
            int startWidth = w, startHeight = v.getHeight();
            if (w == DisplayUtil.getDisplaySize(App.getApp()).x) {
                startX = w / 2;
                startY = startHeight / 2;
                startWidth = startHeight = 0;
            }

            ActivityOptionsCompat transition = ActivityOptionsCompat.makeScaleUpAnimation(
                    v, startX, startY, startWidth, startHeight);
            ActivityCompat.startActivityForResult(DetailActivity.this, intent,
                    Def.Communication.REQUEST_ACTIVITY_IMAGE_VIEWER, transition.toBundle());
        }
    }

    class ImageAttachmentRemoveCallback implements ImageAttachmentAdapter.RemoveCallback {

        @Override
        public void onRemove(int pos) {
            String item = mImageAttachmentAdapter.getItems().get(pos);
            notifyImageAttachmentsChanged(false, pos);

            KeyboardUtil.hideKeyboard(getCurrentFocus());

            if (shouldAddToActionList) {
                mActionList.addAction(new ThingAction(
                        ThingAction.DELETE_ATTACHMENT, pos, item));
            }
        }
    }

    class AudioAttachmentRemoveCallback implements AudioAttachmentAdapter.RemoveCallback {

        @Override
        public void onRemoved(int pos) {
            String item = mAudioAttachmentAdapter.getItems().get(pos);
            notifyAudioAttachmentsChanged(false, pos);

            KeyboardUtil.hideKeyboard(getCurrentFocus());

            int index = mAudioAttachmentAdapter.getPlayingIndex();
            if (pos < index) {
                mAudioAttachmentAdapter.setPlayingIndex(index - 1);
            }

            if (shouldAddToActionList) {
                mActionList.addAction(new ThingAction(
                        ThingAction.DELETE_ATTACHMENT, pos, item));
            }
        }
    }

    private boolean moveChecklist(int from, int to) {
        List<String> items = mCheckListAdapter.getItems();
        int pos2 = items.indexOf("2");
        int fromPos2 = from - pos2;
        int toPos2 = to - pos2;
        if (fromPos2 * toPos2 <= 0) {
            return false;
        }

        int pos3 = items.indexOf("3");
        if (pos3 != -1) { // there are finished items
            int pos4 = pos3 + 1;
            if ((from <= pos3 && to >= pos3) || (from >= pos3 && to <= pos3)) {
                return false;
            }
            if ((from <= pos4 && to >= pos4) || (from >= pos4 && to <= pos4)) {
                return false;
            }
        }

        String item = items.remove(from);
        items.add(to, item);
        mCheckListAdapter.notifyItemMoved(from, to);

        if (shouldAddToActionList) {
            mActionList.addAction(new ThingAction(
                    ThingAction.MOVE_CHECKLIST, from, to));
        }

        return true;
    }

    class CheckListTouchCallback extends ItemTouchHelper.Callback {

        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
            return makeMovementFlags(dragFlags, 0);
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder,
                              RecyclerView.ViewHolder target) {
            final int from = viewHolder.getAdapterPosition();
            final int to   = target.getAdapterPosition();
            return moveChecklist(from, to);
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) { }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return false;
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }
    }

    private void moveAttachment(int from, int to, boolean isImageAttachment) {
        List<String> items;
        if (isImageAttachment) {
            items = mImageAttachmentAdapter.getItems();
        } else {
            items = mAudioAttachmentAdapter.getItems();
        }
        String typePathName = items.remove(from);
        items.add(to, typePathName);

        if (isImageAttachment) {
            mImageAttachmentAdapter.notifyItemMoved(from, to);
        } else {
            mAudioAttachmentAdapter.notifyItemMoved(from, to);
            if (mAudioAttachmentAdapter.getPlayingIndex() != -1) {
                mAudioAttachmentAdapter.setPlayingIndex(items.indexOf(typePathName));
            }
        }

        if (shouldAddToActionList) {
            ThingAction action = new ThingAction(
                    ThingAction.MOVE_ATTACHMENT, from, to);
            action.getExtras().putBoolean(
                    ThingAction.KEY_ATTACHMENT_TYPE, isImageAttachment);
            mActionList.addAction(action);
        }
    }

    class AttachmentTouchCallback extends ItemTouchHelper.Callback {

        boolean isImageAttachmentAdapter;

        AttachmentTouchCallback(boolean isImageAttachmentAdapter) {
            this.isImageAttachmentAdapter = isImageAttachmentAdapter;
        }

        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN |
                    ItemTouchHelper.START | ItemTouchHelper.END;
            return makeMovementFlags(dragFlags, 0);
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder,
                              RecyclerView.ViewHolder target) {
            final int from = viewHolder.getAdapterPosition();
            final int to = target.getAdapterPosition();

            moveAttachment(from, to, isImageAttachmentAdapter);
            return true;
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) { }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return false;
        }

        @Override
        public boolean isLongPressDragEnabled() {
            if (isImageAttachmentAdapter) {
                return mImageAttachmentAdapter.getItemCount() > 1;
            } else {
                return mAudioAttachmentAdapter.getItemCount() > 1;
            }
        }
    }
}
