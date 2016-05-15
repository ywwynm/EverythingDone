package com.ywwynm.everythingdone.activities;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.StringRes;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.util.Pair;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SimpleItemAnimator;
import android.support.v7.widget.Toolbar;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.text.Layout;
import android.text.Spannable;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.util.Log;
import android.util.LruCache;
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
import android.widget.ScrollView;
import android.widget.TextView;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.AudioAttachmentAdapter;
import com.ywwynm.everythingdone.adapters.CheckListAdapter;
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.fragments.AddAttachmentDialogFragment;
import com.ywwynm.everythingdone.fragments.AlertDialogFragment;
import com.ywwynm.everythingdone.fragments.DateTimeDialogFragment;
import com.ywwynm.everythingdone.fragments.HabitDetailDialogFragment;
import com.ywwynm.everythingdone.fragments.TwoOptionsDialogFragment;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.helpers.SendInfoHelper;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;
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
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DetailActivity extends EverythingDoneBaseActivity {

    public static final String TAG = "DetailActivity";

    public static final int CREATE = 0;
    public static final int UPDATE = 1;

    private static final int UNDO_CHECKLIST = 0;
    private static final int UNDO_IMAGE = 1;
    private static final int UNDO_AUDIO = 2;

    public float screenDensity;
    // type + path + name of attachment to add
    public String attachmentTypePathName;

    public long reminderInMillis;
    public int[] reminderAfterTime;
    public int habitType;
    public String habitDetail;

    public DateTimePicker quickRemindPicker;
    public CheckBox cbQuickRemind;
    public TextView tvQuickRemind;

    private String mSenderName;
    private int mType;

    private App mApplication;

    private boolean mEditable;

    private Thing mThing;
    private int mPosition;
    private Reminder mReminder;
    private Habit mHabit;

    private boolean mHabitFinishedThisTime = false;

    private int mMaxSpanImage;
    private int mSpanAudio;

    private AddAttachmentDialogFragment mAddAttachmentDialogFragment;
    private DateTimeDialogFragment mDateTimeDialogFragment;
    private HabitDetailDialogFragment mHabitDetailDialogFragment;

    private FrameLayout mFlBackground;
    private ColorPicker mColorPicker;
    private View mStatusBar;
    private Toolbar mActionbar;
    private ImageButton mIbBack;
    private View mActionBarShadow;
    private View mImageCover;

    private RecyclerView mRvImageAttachment;
    private ImageAttachmentAdapter mImageAttachmentAdapter;
    private GridLayoutManager mImageLayoutManager;

    private ScrollView mScrollView;
    private EditText mEtTitle;
    private EditText mEtContent;
    private TextView mTvUpdateTime;

    private RecyclerView mRvCheckList;
    private CheckListAdapter mCheckListAdapter;
    private LinearLayoutManager mLlmCheckList;
    private LinearLayout mLlMoveChecklist;
    private TextView mTvMoveChecklistAsBt;
    private ItemTouchHelper mChecklistTouchHelper;

    private RecyclerView mRvAudioAttachment;
    private AudioAttachmentAdapter mAudioAttachmentAdapter;
    private GridLayoutManager mAudioLayoutManager;

    private FrameLayout mFlQuickRemindAsBt;

    private Snackbar mNormalSnackbar;
    private Snackbar mUndoSnackbar;
    private int mUndoType;
    private List<String> mUndoItems;
    private List<Integer> mUndoPositions;

    private ExecutorService mExecutor;

    private boolean changingColor = false;

    private Runnable mShowNormalSnackbar;
    private Runnable mShowUndoSnackbar;

    private boolean mRemoveDetailActivityInstance = false;

    private HashMap<View, Integer> mTouchMovedCountMap = new HashMap<>();
    private HashMap<View, Boolean> mOnLongClickedMap   = new HashMap<>();

    /**
     * This {@link android.view.View.OnTouchListener} will listen to click events that should
     * be handled by link/phoneNum/email/maps in {@link mEtContent} and other {@link EditText}s
     * so that we can handle them with different intents and not lose ability to edit them.
     */
    private View.OnTouchListener mSpannableTouchListener = new View.OnTouchListener() {

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (mUndoSnackbar != null) {
                mUndoSnackbar.dismiss();
            }

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
                                mFlBackground.postDelayed(mShowNormalSnackbar,
                                        KeyboardUtil.HIDE_DELAY);
                            }
                        }
                    };
                    View.OnClickListener endListener = new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            df.setShouldShowKeyboardAfterDismiss(true);
                            df.dismiss();
                        }
                    };

                    if (url.startsWith("tel")) {
                        df.setStartAction(R.mipmap.act_dial, R.string.act_dial,
                                startListener);
                    } else if (url.startsWith("mailto")) {
                        df.setStartAction(R.mipmap.act_send_email,
                                R.string.act_send_email, startListener);
                    } else if (url.startsWith("http") || url.startsWith("https")) {
                        df.setStartAction(R.mipmap.act_open_in_browser,
                                R.string.act_open_in_browser, startListener);
                    } else if (url.startsWith("map")) {
                        df.setStartAction(R.mipmap.act_open_in_map,
                                R.string.act_open_in_map, startListener);
                    }
                    df.setEndAction(R.mipmap.act_edit, R.string.act_edit, endListener);
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

    public int getType() {
        return mType;
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.activity_detail;
    }

    @Override
    protected void initMembers() {
        mApplication = (App) getApplication();
        mApplication.setDetailActivityRun(true);

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

        ThingManager thingManager = ThingManager.getInstance(mApplication);
        if (mType == CREATE) {
            long newId = thingManager.getHeaderId();
            App.getRunningDetailActivities().add(newId);
            mThing = new Thing(newId, Thing.NOTE,
                    intent.getIntExtra(Def.Communication.KEY_COLOR,
                            DisplayUtil.getRandomColor(this)), newId);
            if ("intent".equals(mSenderName)) {
                setupThingFromIntent();
            }
        } else {
            App.getRunningDetailActivities().add(id);
            if (mPosition == -1) {
                mThing = ThingDAO.getInstance(mApplication).getThingById(id);
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
            mReminder = ReminderDAO.getInstance(mApplication).getReminderById(id);
            if (mThing.getType() == Thing.HABIT) {
                mHabit = HabitDAO.getInstance(mApplication).getHabitById(id);
                mHabitDetailDialogFragment = HabitDetailDialogFragment.newInstance();
                mHabitDetailDialogFragment.setHabit(mHabit);
            }
            SystemNotificationUtil.cancelNotification(id, mThing.getType(), mApplication);
        }

        mEditable = mThing.getType() < Thing.NOTIFICATION_UNDERWAY
                && mThing.getState() == Thing.UNDERWAY;
        if (mEditable) {
            mShowNormalSnackbar = new Runnable() {
                @Override
                public void run() {
                    mNormalSnackbar.show();
                }
            };
            mShowUndoSnackbar = new Runnable() {
                @Override
                public void run() {
                    mUndoSnackbar.show();
                }
            };
        }

        setSpans();

        if (mEditable) {
            mAddAttachmentDialogFragment = AddAttachmentDialogFragment.newInstance();
            mDateTimeDialogFragment = DateTimeDialogFragment.newInstance(mThing);
        }
        mExecutor = Executors.newSingleThreadExecutor();
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

    private void updateThingAndItsPosition(long id) {
        ThingManager manager = ThingManager.getInstance(mApplication);
        if (mType == CREATE) {
            mThing = new Thing(manager.getHeaderId(), Thing.NOTE, 0, id);
            return;
        }
        List<Thing> things = manager.getThings();
        final int size = things.size();
        int i;
        for (i = 0; i < size; i++) {
            Thing temp = things.get(i);
            if (temp.getId() == id) {
                mThing = temp;
                mPosition = i;
                break;
            }
        }
        if (i == size) {
            mThing = ThingDAO.getInstance(mApplication).getThingById(id);
            mPosition = -1;
        }
    }

    @Override
    protected void findViews() {
        mFlBackground = f(R.id.fl_background);

        mStatusBar = f(R.id.view_status_bar);
        mActionbar = f(R.id.actionbar);
        mIbBack = f(R.id.ib_back);
        mActionBarShadow = f(R.id.actionbar_shadow);
        mImageCover = f(R.id.view_image_cover);

        mRvImageAttachment = f(R.id.rv_image_attachment);
        mRvImageAttachment.setNestedScrollingEnabled(false);

        mScrollView = f(R.id.sv_detail);
        mEtTitle = f(R.id.et_title);
        mEtContent = f(R.id.et_content);
        mTvUpdateTime = f(R.id.tv_update_time);

        mRvCheckList = f(R.id.rv_check_list);
        mRvCheckList.setItemAnimator(null);
        mRvCheckList.setNestedScrollingEnabled(false);

        mRvAudioAttachment = f(R.id.rv_audio_attachment);
        mRvAudioAttachment.setNestedScrollingEnabled(false);
        ((SimpleItemAnimator) mRvAudioAttachment.getItemAnimator())
                .setSupportsChangeAnimations(false);

        mFlQuickRemindAsBt = f(R.id.fl_quick_remind_as_bt);
        cbQuickRemind = f(R.id.cb_quick_remind);
        tvQuickRemind = f(R.id.tv_quick_remind);

        View decorView = getWindow().getDecorView();
        mNormalSnackbar = new Snackbar(mApplication, Snackbar.NORMAL, decorView, null);
        if (mEditable) {
            mLlMoveChecklist     = f(R.id.ll_move_checklist);
            mTvMoveChecklistAsBt = f(R.id.tv_move_checklist_as_bt);

            mColorPicker = new ColorPicker(this, decorView, Def.PickerType.COLOR_NO_ALL);
            mColorPicker.setIsLastIcon(mType == CREATE);
            quickRemindPicker = new DateTimePicker(this, decorView,
                    Def.PickerType.AFTER_TIME, mThing.getColor());
            quickRemindPicker.setAnchor(tvQuickRemind);

            mUndoSnackbar = new Snackbar(mApplication, Snackbar.UNDO, decorView, null);
            mUndoSnackbar.setUndoText(R.string.sb_undo);

            mUndoItems = new ArrayList<>();
            mUndoPositions = new ArrayList<>();
        }
    }

    @Override
    protected void initUI() {
        DisplayUtil.coverStatusBar(f(R.id.view_status_bar_cover));

        if (DeviceUtil.hasKitKatApi()) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) mStatusBar.getLayoutParams();
            params.height = DisplayUtil.getStatusbarHeight(this);
            mStatusBar.requestLayout();
        }

        int color = mThing.getColor();
        int thingType = mThing.getType();
        int thingState = mThing.getState();

        if (thingType == Thing.REMINDER || thingType == Thing.WELCOME_REMINDER) {
            mIbBack.setImageResource(R.mipmap.act_back_reminder);
        } else if (thingType == Thing.HABIT || thingType == Thing.WELCOME_HABIT) {
            mIbBack.setImageResource(R.mipmap.act_back_habit);
        } else if (thingType == Thing.GOAL || thingType == Thing.WELCOME_GOAL) {
            mIbBack.setImageResource(R.mipmap.act_back_goal);
        } else {
            mIbBack.setImageResource(R.mipmap.act_back_note);
        }

        mFlBackground.setBackgroundColor(color);

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

        mEtTitle.setText(mThing.getTitle());

        if (mType == CREATE) {
            mEtContent.requestFocus();
            setScrollViewMarginTop(true);
            mEtContent.setText(mThing.getContent());
        } else {
            String content = mThing.getContent();
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

        String attachment = mThing.getAttachment();
        if (!attachment.isEmpty()) {
            Pair<List<String>, List<String>> items = AttachmentHelper.toAttachmentItems(attachment);
            if (!items.first.isEmpty()) {
                initImageAttachmentUI(items.first);
            } else {
                setScrollViewMarginTop(true);
            }

            if (!items.second.isEmpty()) {
                initAudioAttachmentUI(items.second);
            }
        } else {
            setScrollViewMarginTop(true);
        }

        mTvUpdateTime.getPaint().setTextSkewX(-0.25f);

        if (mType == CREATE) {
            mTvUpdateTime.setText("");
            quickRemindPicker.pickForUI(8);
            reminderAfterTime = quickRemindPicker.getPickedTimeAfter();
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

                reminderInMillis = mReminder.getNotifyTime();
                tvQuickRemind.setText(DateTimeUtil.getDateTimeStrAt(reminderInMillis, this, false));
                int state = mReminder.getState();
                if (state != Reminder.UNDERWAY || thingState != Reminder.UNDERWAY) {
                    tvQuickRemind.append(", " + Reminder.getStateDescription(thingState, state, this));
                }
            } else if (mHabit != null) {
                cbQuickRemind.setChecked(mEditable);
                if (mEditable) {
                    quickRemindPicker.pickForUI(9);
                }
                habitType = mHabit.getType();
                habitDetail = mHabit.getDetail();
                tvQuickRemind.setText(DateTimeUtil.getDateTimeStrRec(
                        mApplication, habitType, habitDetail));
            } else {
                if (mEditable) {
                    quickRemindPicker.pickForUI(8);
                    reminderAfterTime = quickRemindPicker.getPickedTimeAfter();
                } else {
                    f(R.id.ll_quick_remind).setVisibility(View.GONE);
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                            mScrollView.getLayoutParams();
                    params.setMargins(0, params.topMargin, 0, 0);
                }
            }
        }

        updateTaskDescription(mThing.getColor());
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
                returnToThingsActivity(true);
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
        }
        if (DeviceUtil.hasKitKatApi()) {
            KeyboardUtil.addKeyboardCallback(window, new KeyboardUtil.KeyboardCallback() {

                View contentView = f(R.id.fl_background);

                @Override
                public void onKeyboardShow(int keyboardHeight) {
                    if (contentView.getPaddingBottom() == 0) {
                        //set the padding of the contentView for the keyboard
                        contentView.setPadding(0, 0, 0, keyboardHeight);
                    }
                }

                @Override
                public void onKeyboardHide() {
                    if (contentView.getPaddingBottom() != 0) {
                        //reset the padding of the contentView
                        contentView.setPadding(0, 0, 0, 0);
                    }
                }
            });
        }

        mEtContent.setOnClickListener(mEtContentClickListener);
        mEtContent.setOnLongClickListener(mEtContentLongClickListener);
        mEtContent.setOnTouchListener(mSpannableTouchListener);

        if (mEditable) {
            setColorPickerEvent();
            setQuickRemindEvents();
            setSnackbarEvents();
        }
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
                if (!isDragging) {
                    mTvMoveChecklistAsBt.setText(R.string.act_back_from_move_checklist);
                    mTvMoveChecklistAsBt.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            R.mipmap.act_back_from_move_checklist, 0, 0, 0);
                    mCheckListAdapter.setDragging(true);
                    mChecklistTouchHelper.attachToRecyclerView(mRvCheckList);
                } else {
                    mTvMoveChecklistAsBt.setText(R.string.act_move_check_list);
                    mTvMoveChecklistAsBt.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            R.mipmap.act_move_checklist, 0, 0, 0);
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

    public void updateTaskDescription(int color) {
        if (DeviceUtil.hasLollipopApi()) {
            String title;
            if (mType == CREATE) {
                title = getString(R.string.title_create_thing);
            } else {
                if (mEditable) {
                    title = getString(R.string.title_edit_thing);
                } else {
                    title = "";
                }
                if (!LocaleUtil.isChinese(mApplication)) {
                    title += " ";
                }
                title += Thing.getTypeStr(getThingTypeAfter(), mApplication);
            }
            BitmapDrawable bmd = (BitmapDrawable) getDrawable(R.mipmap.ic_launcher);
            Bitmap bm = bmd.getBitmap();
            try {
                setTaskDescription(new ActivityManager.TaskDescription(title, bm, color));
            } catch (Exception ignored) {
            }
        }
    }

    private int getThingTypeAfter() {
        if (mHabitFinishedThisTime) return Thing.HABIT;
        long time = getReminderTime();
        if (cbQuickRemind.isChecked()) {
            if (mReminder != null && mReminder.getNotifyTime() == time) {
                return mThing.getType();
            } else {
                if (habitDetail != null) {
                    return Thing.HABIT;
                } else return Reminder.getType(getReminderTime(), System.currentTimeMillis());
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

    private long getReminderTime() {
        if (reminderInMillis != 0) {
            return reminderInMillis;
        }
        if (reminderAfterTime != null) {
            return DateTimeUtil.getActualTimeAfterSomeTime(reminderAfterTime);
        }
        return -1;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (mThing.getType() >= Thing.NOTIFICATION_UNDERWAY) {
            return true;
        }
        MenuInflater inflater = getMenuInflater();
        if (mType == CREATE) {
            inflater.inflate(R.menu.menu_detail_create, menu);
        } else {
            int state = mThing.getState();
            if (state == Thing.UNDERWAY) {
                if (mThing.getType() == Thing.HABIT) {
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
            } else if (state == Thing.FINISHED) {
                inflater.inflate(R.menu.menu_detail_finished, menu);
                if (mThing.getType() != Thing.HABIT) {
                    menu.findItem(R.id.act_check_habit_detail).setVisible(false);
                }
            } else {
                inflater.inflate(R.menu.menu_detail_deleted, menu);
                if (mThing.getType() != Thing.HABIT) {
                    menu.findItem(R.id.act_check_habit_detail).setVisible(false);
                }
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mUndoSnackbar != null) {
            mUndoSnackbar.dismiss();
        }
        switch (item.getItemId()) {
            case R.id.act_add_attachment:
                mAddAttachmentDialogFragment.show(getFragmentManager(),
                        AddAttachmentDialogFragment.TAG);
                break;
            case R.id.act_check_list:
                toggleCheckList();
                break;
            case R.id.act_change_color:
                mColorPicker.show();
                break;
            case R.id.act_check_habit_detail:
                mHabitDetailDialogFragment.show(getFragmentManager(), HabitDetailDialogFragment.TAG);
                break;
            case R.id.act_share:
                SendInfoHelper.shareThing(this, mThing);
                break;
            case R.id.act_finish_this_time_habit:
                HabitDAO.getInstance(mApplication).finishOneTime(mHabit);
                mHabitFinishedThisTime = true;
                habitType = mHabit.getType();
                habitDetail = mHabit.getDetail();
                returnToThingsActivity(false);
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
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void finish() {
        List<Long> detailActivities = App.getRunningDetailActivities();
        detailActivities.remove(mThing.getId());
        mRemoveDetailActivityInstance = true;
        super.finish();
    }

    private void toggleCheckListActionItem(Menu menu, boolean toDisable) {
        MenuItem item = menu.findItem(R.id.act_check_list);
        if (toDisable) {
            item.setIcon(R.mipmap.act_disable_check_list);
            item.setTitle(getString(R.string.act_disable_check_list));
        } else {
            item.setIcon(R.mipmap.act_enable_check_list);
            item.setTitle(getString(R.string.act_enable_check_list));
        }
    }

    private void toggleCheckList() {
        String content = mEtContent.getText().toString();
        if (mRvCheckList.getVisibility() == View.VISIBLE) {
            toggleCheckListActionItem(mActionbar.getMenu(), false);
            mEtContent.setVisibility(View.VISIBLE);
            mRvCheckList.setVisibility(View.GONE);
            mLlMoveChecklist.setVisibility(View.GONE);
            mChecklistTouchHelper.attachToRecyclerView(null);

            String contentStr = CheckListHelper.toContentStr(mCheckListAdapter.getItems());
            mEtContent.setText(contentStr);

            if (contentStr.isEmpty()) {
                KeyboardUtil.showKeyboard(mEtContent);
            } else {
                KeyboardUtil.hideKeyboard(getCurrentFocus());
            }
        } else {
            toggleCheckListActionItem(mActionbar.getMenu(), true);
            mRvCheckList.setVisibility(View.VISIBLE);
            mEtContent.setVisibility(View.GONE);
            mLlMoveChecklist.setVisibility(View.VISIBLE);

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
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mEditable) {
            mColorPicker.dismiss();
            quickRemindPicker.dismiss();
            mNormalSnackbar.dismiss();
            mUndoSnackbar.dismiss();
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
                    mFlBackground.postDelayed(mShowNormalSnackbar, KeyboardUtil.HIDE_DELAY);
                    return;
                }
                attachmentTypePathName = getTypePathName(pathName);
                if (attachmentTypePathName == null) {
                    mNormalSnackbar.setMessage(R.string.error_unsupported_file_type);
                    mFlBackground.postDelayed(mShowNormalSnackbar, KeyboardUtil.HIDE_DELAY);
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
        returnToThingsActivity(true);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        final int G = PackageManager.PERMISSION_GRANTED;
        for (int grantResult : grantResults) {
            if (grantResult != G) {
                showNormalSnackbar(R.string.error_permission_denied);
                return;
            }
        }
        if (requestCode == Def.Communication.REQUEST_PERMISSION_TAKE_PHOTO) {
            mAddAttachmentDialogFragment.startTakePhoto();
        } else if (requestCode == Def.Communication.REQUEST_PERMISSION_SHOOT_VIDEO) {
            mAddAttachmentDialogFragment.startShootVideo();
        } else if (requestCode == Def.Communication.REQUEST_PERMISSION_RECORD_AUDIO) {
            mAddAttachmentDialogFragment.showRecordAudioDialog();
        } else if (requestCode == Def.Communication.REQUEST_PERMISSION_CHOOSE_MEDIA_FILE) {
            mAddAttachmentDialogFragment.startChooseMediaFile();
        }
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
        return ((ColorDrawable) mFlBackground.getBackground()).getColor();
    }

    public LruCache<String, Bitmap> getBitmapLruCache() {
        return mApplication.getBitmapLruCache();
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

        final ViewTreeObserver observer = mScrollView.getViewTreeObserver();
        observer.addOnScrollChangedListener(
                new ViewTreeObserver.OnScrollChangedListener() {
                    @Override
                    public void onScrollChanged() {
                        int scrollY = mScrollView.getScrollY();

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
            observer.addOnScrollChangedListener(
                    new ViewTreeObserver.OnScrollChangedListener() {
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
                changingColor = true;

                int colorFrom = ((ColorDrawable) mFlBackground.getBackground()).getColor();
                int colorTo = mColorPicker.getPickedColor();
                quickRemindPicker.setAccentColor(colorTo);
                quickRemindPicker.pickForUI(quickRemindPicker.getPickedIndex());
                ObjectAnimator.ofObject(mFlBackground, "backgroundColor",
                        new ArgbEvaluator(), colorFrom, colorTo).setDuration(600).start();

                updateTaskDescription(colorTo);

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
        });
    }

    private void setQuickRemindEvents() {
        cbQuickRemind.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                updateTaskDescription(getAccentColor());
                updateBackImage();
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
                if (quickRemindPicker.getPickedIndex() == 9) {
                    mDateTimeDialogFragment.show(getFragmentManager(), DateTimeDialogFragment.TAG);
                } else {
                    habitType = -1;
                    habitDetail = null;
                    reminderAfterTime = quickRemindPicker.getPickedTimeAfter();
                    reminderInMillis = 0;
                    if (cbQuickRemind.isChecked()) {
                        updateTaskDescription(getAccentColor());
                        updateBackImage();
                    } else {
                        cbQuickRemind.setChecked(true);
                    }
                }
            }
        });
    }

    private void setSnackbarEvents() {
        Snackbar.DismissCallback callback = new Snackbar.DismissCallback() {
            @Override
            public void onDismiss() {
                mUndoItems.clear();
                mUndoPositions.clear();
            }
        };
        mUndoSnackbar.setDismissCallback(callback);

        mUndoSnackbar.setUndoListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int size = mUndoItems.size();
                String str = mUndoItems.get(size - 1);
                int pos = mUndoPositions.get(size - 1);
                mUndoItems.remove(size - 1);
                mUndoPositions.remove(size - 1);
                updateUndoMessage();

                if (mUndoType == UNDO_CHECKLIST) {
                    mCheckListAdapter.addItem(pos, str);
                } else {
                    attachmentTypePathName = str;
                    addAttachment(pos);
                }

                if (mUndoItems.isEmpty()) {
                    mUndoSnackbar.dismiss();
                }
            }
        });
    }

    private void updateUndoMessage() {
        boolean isChinese = LocaleUtil.isChinese(this);
        String deleted = getString(R.string.sb_delete);
        String typeStr;
        if (mUndoType == UNDO_CHECKLIST) {
            typeStr = getString(R.string.item);
        } else {
            typeStr = getString(R.string.attachment);
        }

        int size = mUndoItems.size();
        if (size > 1 && !isChinese) {
            typeStr += "s";
        }
        if (isChinese) {
            mUndoSnackbar.setMessage(deleted + " " + size + " " + typeStr);
        } else {
            mUndoSnackbar.setMessage(size + " " + typeStr + " " + deleted);
        }
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
                returnToThingsActivity(false);
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
                        mIbBack.setImageResource(R.mipmap.act_back_habit);
                        cbQuickRemind.setChecked(true);
                        quickRemindPicker.pickForUI(9);
                        reminderAfterTime = null;
                        reminderInMillis = 0;
                        habitType = mHabit.getType();
                        habitDetail = mHabit.getDetail();
                        tvQuickRemind.setText(DateTimeUtil.getDateTimeStrRec(
                                mApplication, habitType, habitDetail));
                    }
                });
    }

    private void alertForCancellingGoal() {
        alertCancel(R.string.alert_cancel_goal_title, R.string.alert_cancel_goal_content,
                new AlertDialogFragment.CancelListener() {
                    @Override
                    public void onCancel() {
                        mIbBack.setImageResource(R.mipmap.act_back_goal);
                        cbQuickRemind.setChecked(true);
                        quickRemindPicker.pickForUI(9);
                        reminderInMillis = mReminder.getNotifyTime();
                        reminderAfterTime = null;
                        habitType = -1;
                        habitDetail = null;
                        tvQuickRemind.setText(DateTimeUtil.getDateTimeStrAt(
                                reminderInMillis, DetailActivity.this, false));
                    }
                });
    }

    private void createHabit(long id, HabitDAO habitDAO) {
        Habit habit = new Habit(id, habitType, 0, habitDetail, "", "", System.currentTimeMillis(), 0);
        habit.initHabitReminders();
        habitDAO.createHabit(habit);
    }

    private void returnToThingsActivity(boolean alertForCancelling) {
        if (changingColor) return;

        if (mAudioAttachmentAdapter != null && mAudioAttachmentAdapter.getPlayingIndex() != -1) {
            mAudioAttachmentAdapter.stopPlaying();
        }

        if (!mEditable) {
            setResult(Def.Communication.RESULT_NO_UPDATE);
            finish();
            return;
        }

        KeyboardUtil.hideKeyboard(getCurrentFocus());
        mNormalSnackbar.dismiss();

        long id = mThing.getId();
        if (App.isSomethingUpdatedSpecially()) {
            updateThingAndItsPosition(id);
        }

        boolean reminderUpdated = true, habitUpdated = true;
        long reminderTime = getReminderTime();

        int typeBefore = mThing.getType();
        int typeAfter = getThingTypeAfter();
        boolean isReminderBefore = Thing.isReminderType(typeBefore);
        boolean isReminderAfter = Thing.isReminderType(typeAfter);
        boolean isHabitBefore = typeBefore == Thing.HABIT;
        boolean isHabitAfter = typeAfter == Thing.HABIT;

        if (cbQuickRemind.isChecked() && habitDetail == null && reminderTime <= System.currentTimeMillis()) {
            mNormalSnackbar.setMessage(R.string.error_later);
            mFlBackground.postDelayed(mShowNormalSnackbar, 120);
            return;
        }

        String title = mEtTitle.getText().toString();
        String content;
        int color = getAccentColor();

        if (mRvCheckList.getVisibility() == View.VISIBLE) {
            content = CheckListHelper.toCheckListStr(mCheckListAdapter.getItems());
            if (mHabitFinishedThisTime) {
                content = content.replaceAll(CheckListHelper.SIGNAL + "1",
                        CheckListHelper.SIGNAL + "0");
            }
        } else {
            content = mEtContent.getText().toString();
        }

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
            mApplication.addAttachmentsToDeleteFile(attachmentsToDelete);
        }

        Intent intent = new Intent();
        int resultCode = Def.Communication.RESULT_NO_UPDATE;
        if (mType == CREATE && title.isEmpty() && content.isEmpty() && attachment.isEmpty()) {
            resultCode = Def.Communication.RESULT_CREATE_BLANK_THING;
            setResult(resultCode);
            if (App.isSomethingUpdatedSpecially()) {
                App.setShouldJustNotifyDataSetChanged(true);
            }
            if (shouldSendBroadCast()) {
                sendBroadCastToUpdateMainUI(intent, resultCode);
            }
            finish();
            return;
        }

        ReminderDAO rDao = ReminderDAO.getInstance(mApplication);
        if (!isReminderBefore && isReminderAfter) {
            if (!isHabitBefore || !alertForCancelling) {
                rDao.create(new Reminder(mThing.getId(), reminderTime));
            }
        } else if (isReminderBefore && !isReminderAfter) {
            if (typeBefore == Thing.GOAL && alertForCancelling) {
                alertForCancellingGoal();
                return;
            } else {
                rDao.delete(id);
            }
        } else if (isReminderBefore) {
            if (mReminder.getNotifyTime() == reminderTime && typeBefore == typeAfter) {
                reminderUpdated = false;
            } else {
                if (typeBefore == Thing.GOAL && alertForCancelling) {
                    alertForCancellingGoal();
                    return;
                }
                mReminder.setNotifyTime(reminderTime);
                mReminder.setState(Reminder.UNDERWAY);
                mReminder.initNotifyMinutes();
                mReminder.setUpdateTime(System.currentTimeMillis());
                rDao.update(mReminder);
            }
        } else {
            reminderUpdated = false;
        }

        HabitDAO hDao = HabitDAO.getInstance(mApplication);
        if (!isHabitBefore && isHabitAfter) {
            createHabit(id, hDao);
        } else if (isHabitBefore && !isHabitAfter) {
            if (alertForCancelling) {
                alertForCancellingHabit();
                return;
            } else {
                hDao.deleteHabit(id);
            }
        } else if (isHabitBefore) {
            if (Habit.noUpdate(mHabit, habitType, habitDetail)) {
                habitUpdated = false;
            } else {
                if (alertForCancelling) {
                    alertForCancellingHabit();
                    return;
                } else {
                    hDao.deleteHabit(id);
                    createHabit(id, hDao);
                }
            }
        } else {
            habitUpdated = false;
        }

        if (mType == CREATE) {
            mThing.setTitle(title);
            mThing.setContent(content);
            mThing.setAttachment(attachment);
            mThing.setType(typeAfter);
            mThing.setColor(color);

            long currentTime = System.currentTimeMillis();
            mThing.setCreateTime(currentTime);
            mThing.setUpdateTime(currentTime);

            intent.putExtra(Def.Communication.KEY_THING, mThing);
            resultCode = Def.Communication.RESULT_CREATE_THING_DONE;

            // Create thing here directly to database. Solve the problem that if ThingsActivity
            // is destroyed while user share something from other apps to EverythingDone. In that
            // case, ThingsActivity won't receive broadcast to handle creation and that thing
            // will be missed.
            WeakReference<ThingsActivity> wr = App.thingsActivityWR;
            if (wr == null || wr.get() == null) {
                ThingManager.getInstance(mApplication).create(mThing, true, true);
                intent.putExtra(Def.Communication.KEY_CREATED_DONE, true);
            } else if (!shouldSendBroadCast()) {
                setResult(resultCode, intent);
            }
        } else {
            boolean noUpdate = Thing.noUpdate(mThing, title, content, attachment, typeAfter, color)
                    && !reminderUpdated && !habitUpdated && !mHabitFinishedThisTime;
            if (noUpdate) {
                setResult(resultCode);
            } else {
                if (title.isEmpty() && content.isEmpty() && attachment.isEmpty()) {
                    returnToThingsActivity(Thing.DELETED_FOREVER);
                    return;
                } else {
                    mThing.setTitle(title);
                    mThing.setContent(content);
                    mThing.setAttachment(attachment);
                    mThing.setType(typeAfter);
                    mThing.setColor(color);
                    mThing.setUpdateTime(System.currentTimeMillis());

                    intent.putExtra(Def.Communication.KEY_TYPE_BEFORE, typeBefore);
                    boolean sameType = mApplication.getLimit() ==
                            Def.LimitForGettingThings.ALL_UNDERWAY
                            || Thing.sameType(typeBefore, typeAfter);
                    if (sameType) {
                        resultCode = Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME;
                    } else {
                        intent.putExtra(Def.Communication.KEY_THING, mThing);
                        resultCode = Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_DIFFERENT;
                    }

                    if (mPosition != -1) {
                        intent.putExtra(Def.Communication.KEY_POSITION, mPosition);
                        int updateResult = ThingManager.getInstance(mApplication).update(
                                typeBefore, mThing, mPosition, true);
                        if (updateResult != 0) {
                            intent.putExtra(Def.Communication.KEY_CALL_CHANGE, updateResult == 1);
                        }
                    } else {
                        ThingDAO.getInstance(mApplication).update(typeBefore, mThing, true, true);
                    }

                    if (!shouldSendBroadCast()) {
                        setResult(resultCode, intent);
                    }
                }
            }
        }

        if (App.isSomethingUpdatedSpecially()
                && resultCode != Def.Communication.RESULT_NO_UPDATE) {
            App.setShouldJustNotifyDataSetChanged(true);
        }

        if (shouldSendBroadCast()) {
            sendBroadCastToUpdateMainUI(intent, resultCode);
        }
        finish();
    }

    private void returnToThingsActivity(int stateAfter) {
        if (mAudioAttachmentAdapter != null && mAudioAttachmentAdapter.getPlayingIndex() != -1) {
            mAudioAttachmentAdapter.stopPlaying();
        }
        KeyboardUtil.hideKeyboard(getCurrentFocus());

        ThingManager manager = ThingManager.getInstance(mApplication);
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
            ThingDAO dao = ThingDAO.getInstance(mApplication);
            dao.updateState(mThing, mThing.getLocation(), stateBefore, stateAfter, true, true,
                    false, dao.getHeaderId(), true);

            long id = mThing.getId();
            int type = mThing.getType();
            if (type == Thing.GOAL && stateAfter == Thing.UNDERWAY) {
                ReminderDAO reminderDAO = ReminderDAO.getInstance(mApplication);
                Reminder goal = reminderDAO.getReminderById(id);
                ThingManager.getInstance(mApplication).getUndoGoals().add(goal);
                reminderDAO.resetGoal(goal);
            }
            if (type == Thing.HABIT) {
                HabitDAO habitDAO = HabitDAO.getInstance(mApplication);
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
        finish();
    }

    private boolean shouldSendBroadCast() {
        return mSenderName.equals(ReminderReceiver.TAG) || mSenderName.equals(HabitReceiver.TAG)
                || mSenderName.equals(AutoNotifyReceiver.TAG) || "intent".equals(mSenderName);
    }

    private void sendBroadCastToUpdateMainUI(Intent intent, int resultCode) {
        if (App.getRunningDetailActivities().size() > 1
                && resultCode != Def.Communication.RESULT_NO_UPDATE) {
            // A DetailActivity has been opened before and this is another one opened from notification.
            App.setSomethingUpdatedSpecially(true);
        }

        intent.putExtra(Def.Communication.KEY_RESULT_CODE, resultCode);
        intent.setAction(Def.Communication.BROADCAST_ACTION_UPDATE_MAIN_UI);
        sendBroadcast(intent);
    }

    public void updateBackImage() {
        if (cbQuickRemind.isChecked()) {
            if (habitDetail != null) {
                mIbBack.setImageResource(R.mipmap.act_back_habit);
            } else {
                if (Reminder.getType(getReminderTime(), System.currentTimeMillis()) == Thing.GOAL) {
                    mIbBack.setImageResource(R.mipmap.act_back_goal);
                } else {
                    mIbBack.setImageResource(R.mipmap.act_back_reminder);
                }
            }
        } else {
            int thingType = mThing.getType();
            if (Thing.isTypeReminder(thingType)) {
                mIbBack.setImageResource(R.mipmap.act_back_reminder);
            } else if (Thing.isTypeHabit(thingType)) {
                mIbBack.setImageResource(R.mipmap.act_back_habit);
            } else if (Thing.isTypeGoal(thingType)) {
                mIbBack.setImageResource(R.mipmap.act_back_goal);
            } else {
                mIbBack.setImageResource(R.mipmap.act_back_note);
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
                if (mUndoType != UNDO_CHECKLIST) {
                    mUndoSnackbar.dismiss();
                }
                mUndoType = UNDO_CHECKLIST;
                mUndoItems.add(item);
                mUndoPositions.add(position);
                updateUndoMessage();

                mRvCheckList.postDelayed(mShowUndoSnackbar, KeyboardUtil.HIDE_DELAY);
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
            ActivityOptionsCompat transition = ActivityOptionsCompat.makeScaleUpAnimation(
                    v, v.getWidth() >> 1, v.getHeight() >> 1, 0, 0);
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
            if (mUndoType != UNDO_IMAGE) {
                mUndoSnackbar.dismiss();
            }
            mUndoType = UNDO_IMAGE;
            mUndoItems.add(item);
            mUndoPositions.add(pos);
            updateUndoMessage();
            mRvImageAttachment.postDelayed(mShowUndoSnackbar, KeyboardUtil.HIDE_DELAY);
        }
    }

    class AudioAttachmentRemoveCallback implements AudioAttachmentAdapter.RemoveCallback {

        @Override
        public void onRemoved(int pos) {
            String item = mAudioAttachmentAdapter.getItems().get(pos);
            notifyAudioAttachmentsChanged(false, pos);

            KeyboardUtil.hideKeyboard(getCurrentFocus());
            if (mUndoType != UNDO_AUDIO) {
                mUndoSnackbar.dismiss();
            }
            mUndoType = UNDO_AUDIO;
            mUndoItems.add(item);
            mUndoPositions.add(pos);
            updateUndoMessage();
            mRvImageAttachment.postDelayed(mShowUndoSnackbar, KeyboardUtil.HIDE_DELAY);

            int index = mAudioAttachmentAdapter.getPlayingIndex();
            if (pos < index) {
                mAudioAttachmentAdapter.setPlayingIndex(index - 1);
            }
        }
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
            return false;
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

            List<String> items;
            if (isImageAttachmentAdapter) {
                items = mImageAttachmentAdapter.getItems();
            } else {
                items = mAudioAttachmentAdapter.getItems();
            }
            String typePathName = items.remove(from);
            items.add(to, typePathName);

            if (isImageAttachmentAdapter) {
                mImageAttachmentAdapter.notifyItemMoved(from, to);
            } else {
                mAudioAttachmentAdapter.notifyItemMoved(from, to);
                if (mAudioAttachmentAdapter.getPlayingIndex() != -1) {
                    mAudioAttachmentAdapter.setPlayingIndex(items.indexOf(typePathName));
                }
            }
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
