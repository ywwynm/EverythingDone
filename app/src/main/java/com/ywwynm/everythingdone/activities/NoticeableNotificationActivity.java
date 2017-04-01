package com.ywwynm.everythingdone.activities;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.util.Pair;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.FrequentSettings;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.managers.ModeManager;
import com.ywwynm.everythingdone.model.HabitReminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.receivers.HabitNotificationActionReceiver;
import com.ywwynm.everythingdone.receivers.ReminderNotificationActionReceiver;
import com.ywwynm.everythingdone.utils.DeviceUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.EdgeEffectUtil;

import org.joda.time.DateTime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by qiizhang on 2016/11/10.
 * An Activity to provide more noticeable notification for Reminders/Habits
 */
public class NoticeableNotificationActivity extends EverythingDoneBaseActivity {

    public static final String TAG = "NoticeableNotificationActivity";

    public static final String BROADCAST_ACTION_JUST_FINISH = TAG + ".action.just_finish";

    public static Intent getOpenIntentForReminder(Context context, long thingId, int position) {
        return new Intent(context, NoticeableNotificationActivity.class)
                .putExtra(Def.Communication.KEY_ID, thingId)
                .putExtra(Def.Communication.KEY_POSITION, position);
    }

    private static final String KEY_IS_HABIT = TAG + ".key.is_habit";

    public static Intent getOpenIntentForHabit(Context context, long hrId, int position, long hrTime) {
        return new Intent(context, NoticeableNotificationActivity.class)
                .putExtra(Def.Communication.KEY_ID, hrId)
                .putExtra(Def.Communication.KEY_POSITION, position)
                .putExtra(Def.Communication.KEY_TIME, hrTime)
                .putExtra(KEY_IS_HABIT, true);
    }

    private int mDialogWidth;

    private boolean mIsHabit;

    private Thing mThing;
    private int mPosition;

    private long mHrId;
    private long mHrTime;

    private List<Integer> mActionsTexts;
    private List<Integer> mActionsIcons;
    private List<View.OnClickListener> mActions;

    private ImageView mIvTitle;
    private TextView mTvTitle;

    private RecyclerView mRvThing;

    private FrameLayout[] mFlActions;
    private ImageView[] mIvActions;

    private FrameLayout mFlCancelAsBt;

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BROADCAST_ACTION_JUST_FINISH.equals(intent.getAction())) {
                long thingId = intent.getLongExtra(Def.Communication.KEY_ID, -1L);
                if (thingId == mThing.getId()) {
                    finish();
                }
            }
        }
    };

    @Override
    protected void init() {
        IntentFilter intentFilter = new IntentFilter(BROADCAST_ACTION_JUST_FINISH);
        registerReceiver(mReceiver, intentFilter);

        initMembers();
        if (mThing != null) {
            findViews();
            initUI();
            setActionbar();
            setEvents();
        } else {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mReceiver);
        NotificationManagerCompat nmc = NotificationManagerCompat.from(this);
        if (mIsHabit) {
            nmc.cancel((int) mHrId);
        } else {
            nmc.cancel((int) mThing.getId());
        }
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.activity_noticeable_notification;
    }

    @Override
    protected void initMembers() {
        mDialogWidth = (int) (DisplayUtil.getScreenDensity(this) * 280);

        Intent intent = getIntent();
        mIsHabit = intent.getBooleanExtra(KEY_IS_HABIT, false);
        long thingId;
        if (mIsHabit) {
            mHrId = intent.getLongExtra(Def.Communication.KEY_ID, -1L);
            HabitReminder habitReminder = HabitDAO.getInstance(this).getHabitReminderById(mHrId);
            if (habitReminder == null) return;
            thingId = habitReminder.getHabitId();
            mHrTime = intent.getLongExtra(Def.Communication.KEY_TIME, -1L);
        } else {
            thingId = intent.getLongExtra(Def.Communication.KEY_ID, -1L);
        }

        int position = intent.getIntExtra(Def.Communication.KEY_POSITION, -1);
        Pair<Thing, Integer> pair = App.getThingAndPosition(this, thingId, position);
        mThing = pair.first;
        mPosition = pair.second;

        if (mThing != null) {
            initMemberActions();
        }
    }

    private void initMemberActions() {
        mActionsTexts = new ArrayList<>();
        mActionsIcons = new ArrayList<>();
        mActions = new ArrayList<>();
        @Thing.Type int thingType = mThing.getType();
        if (Thing.isReminderType(thingType)) {
            initMemberActionsReminder();
        } else if (thingType == Thing.HABIT) {
            initMemberActionsHabit();
        }
    }

    private void initMemberActionsReminder() {
        mActionsTexts.add(R.string.act_finish);
        mActionsIcons.add(R.drawable.act_finish);
        mActions.add(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendBroadcastForReminderAndLeave(Def.Communication.NOTIFICATION_ACTION_FINISH);
            }
        });

        if (mThing.getType() == Thing.REMINDER) {
            mActionsTexts.add(R.string.act_start_doing);
            mActionsIcons.add(R.drawable.act_start_doing);
            mActions.add(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sendBroadcastForReminderAndLeave(Def.Communication.NOTIFICATION_ACTION_START_DOING);
                }
            });

            mActionsTexts.add(R.string.act_delay);
            mActionsIcons.add(R.drawable.act_delay);
            mActions.add(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sendBroadcastForReminderAndLeave(Def.Communication.NOTIFICATION_ACTION_DELAY);
                }
            });
        }
    }

    private void initMemberActionsHabit() {
        mActionsTexts.add(R.string.act_finish_this_time_habit);
        mActionsIcons.add(R.drawable.act_finish);
        mActions.add(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(NoticeableNotificationActivity.this,
                        HabitNotificationActionReceiver.class);
                intent.setAction(Def.Communication.NOTIFICATION_ACTION_FINISH);
                intent.putExtra(Def.Communication.KEY_ID, mHrId);
                intent.putExtra(Def.Communication.KEY_POSITION, mPosition);
                intent.putExtra(Def.Communication.KEY_TIME, mHrTime);
                sendBroadcast(intent);
                finish();
            }
        });

        mActionsTexts.add(R.string.act_start_doing);
        mActionsIcons.add(R.drawable.act_start_doing);
        mActions.add(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(NoticeableNotificationActivity.this,
                        HabitNotificationActionReceiver.class);
                intent.setAction(Def.Communication.NOTIFICATION_ACTION_START_DOING);
                intent.putExtra(Def.Communication.KEY_ID, mHrId);
                intent.putExtra(Def.Communication.KEY_POSITION, mPosition);
                intent.putExtra(Def.Communication.KEY_TIME, mHrTime);
                sendBroadcast(intent);
                finish();
            }
        });
    }

    private void sendBroadcastForReminderAndLeave(String action) {
        Intent intent = new Intent(this, ReminderNotificationActionReceiver.class);
        intent.setAction(action);
        intent.putExtra(Def.Communication.KEY_ID, mThing.getId());
        intent.putExtra(Def.Communication.KEY_POSITION, mPosition);
        sendBroadcast(intent);
        finish();
    }

    @Override
    protected void findViews() {
        mTvTitle = f(R.id.tv_noticeable_notification_title);
        mIvTitle = f(R.id.iv_noticeable_notification_title);

        mRvThing = f(R.id.rv_thing_noticeable_notification);

        mFlActions = new FrameLayout[3];
        mFlActions[0] = f(R.id.fl_1_noticeable_notification_as_bt);
        mFlActions[1] = f(R.id.fl_2_noticeable_notification_as_bt);
        mFlActions[2] = f(R.id.fl_3_noticeable_notification_as_bt);

        mIvActions = new ImageView[3];
        mIvActions[0] = f(R.id.iv_1_noticeable_notification_as_bt);
        mIvActions[1] = f(R.id.iv_2_noticeable_notification_as_bt);
        mIvActions[2] = f(R.id.iv_3_noticeable_notification_as_bt);

        mFlCancelAsBt = f(R.id.fl_noticeable_notification_cancel_as_bt);
    }

    @Override
    protected void initUI() {
        initTitleUI();
        initRvThing();
        initActionsUI();
    }

    @SuppressLint("SetTextI18n")
    private void initTitleUI() {
        @Thing.Type int thingType = mThing.getType();
        int iconRes = Thing.getTypeIconWhiteLarge(thingType);
        Drawable d1 = ContextCompat.getDrawable(this, iconRes);
        Drawable d2 = d1.mutate();
        d2.setColorFilter(mThing.getColor(), PorterDuff.Mode.SRC_ATOP);
        mIvTitle.setImageDrawable(d2);

        String typeStr = Thing.getTypeStr(thingType, this);
        mIvTitle.setContentDescription(typeStr);

        String timeStr = new DateTime().toString("HH:mm");
        String title = typeStr + " • " + timeStr;
        SpannableStringBuilder ssb = new SpannableStringBuilder(title);
        ForegroundColorSpan colorSpan1 = new ForegroundColorSpan(mThing.getColor());
        ForegroundColorSpan colorSpan2 = new ForegroundColorSpan(Color.parseColor("#66000000"));
        int index = title.indexOf('•');
        ssb.setSpan(colorSpan1, 0, index - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(colorSpan2, index - 1, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        mTvTitle.setText(ssb);
    }

    private void initRvThing() {
        final List<Thing> singleThing = Collections.singletonList(new Thing(mThing));
        BaseThingsAdapter adapter = new BaseThingsAdapter(this) {
            @Override
            protected int getCurrentMode() {
                return ModeManager.NORMAL;
            }

            @Override
            protected List<Thing> getThings() {
                return singleThing;
            }

            @Override
            public void onBindViewHolder(BaseThingViewHolder holder, int position) {
                super.onBindViewHolder(holder, position);

                int color = DisplayUtil.getTransparentColor(mThing.getColor(), 16);
                if (!DeviceUtil.hasLollipopApi()) {
                    holder.cv.setBackgroundColor(color);
                } else {
                    holder.cv.setCardBackgroundColor(color);
                }
                holder.cv.setRadius(0);
                holder.cv.setCardElevation(0);

                holder.tvTitle.setMaxLines(Integer.MAX_VALUE);
                holder.tvContent.setMaxLines(Integer.MAX_VALUE);
                holder.rlReminder.setVisibility(View.GONE);
                holder.rlHabit.setVisibility(View.GONE);
                holder.vReminderSeparator.setVisibility(View.GONE);
                holder.vHabitSeparator1.setVisibility(View.GONE);
                holder.flDoing.setVisibility(View.GONE);
                holder.ivStickyOngoing.setVisibility(View.GONE);

                if (holder.ivPrivateThing.getVisibility() == View.VISIBLE) {
                    holder.ivPrivateThing.setVisibility(View.GONE);
                    holder.tvContent.setVisibility(View.VISIBLE);
                    holder.tvContent.setText(R.string.notification_private_thing_content);
                    holder.tvContent.setTextSize(20);
                    holder.tvContent.setTextColor(ContextCompat.getColor(
                            getApplicationContext(), R.color.black_76p));
                    int p = (int) (mDensity * 16);
                    holder.tvContent.setPadding(p, p, p, 0);
                }

                holder.cv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        final Intent intent = DetailActivity.getOpenIntentForUpdate(
                                NoticeableNotificationActivity.this, NoticeableNotificationActivity.TAG,
                                mThing.getId(), mPosition);
                        startActivity(intent);
                        finish();
                    }
                });
            }
        };
        adapter.setCardWidth(mDialogWidth);
        adapter.setStyle(BaseThingsAdapter.STYLE_BLACK);
        adapter.setChecklistMaxItemCount(-1);
        mRvThing.setLayoutManager(new LinearLayoutManager(this));
        mRvThing.setAdapter(adapter);
    }

    private void initActionsUI() {
//        int actionColor = DisplayUtil.getTransparentColor(mThing.getColor(), 224);
        int actionColor = ContextCompat.getColor(this, R.color.black_54p);
//        int iconColor = mThing.getColor();
        final int size = mActions.size();
        for (int i = 0; i < size; i++) {
            mFlActions[i].setVisibility(View.VISIBLE);
            Drawable drawable = ContextCompat.getDrawable(this, mActionsIcons.get(i));
            drawable = drawable.mutate();
            drawable.setColorFilter(actionColor, PorterDuff.Mode.SRC_ATOP);
            mIvActions[i].setImageDrawable(drawable);
            mFlActions[i].setOnClickListener(mActions.get(i));
            mIvActions[i].setContentDescription(getString(mActionsTexts.get(i)));
        }

        if (FrequentSettings.getBoolean(Def.Meta.KEY_CLOSE_NOTIFICATION_LATER)) {
            for (int i = 0; i < size; i++) {
                mFlActions[i].setAlpha(0);
                mFlActions[i].setVisibility(View.GONE);
            }
            mFlCancelAsBt.setAlpha(0);
            mFlCancelAsBt.setVisibility(View.GONE);

            if (DeviceUtil.isScreenOn(this)) {
                animateActionsVisible();
            } else {
                mShouldShowActionsInOnResume = true;
            }
        }
    }

    private void animateActionsVisible() {
        mTvTitle.postDelayed(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < mActions.size(); i++) {
                    mFlActions[i].setVisibility(View.VISIBLE);
                    mFlActions[i].animate().alpha(1).setDuration(360).start();
                }
                mFlCancelAsBt.setVisibility(View.VISIBLE);
                mFlCancelAsBt.animate().alpha(1).setDuration(360).start();
            }
        }, 2000);
    }

    private boolean mShouldShowActionsInOnResume = false;

    @Override
    protected void onResume() {
        super.onResume();

        if (DeviceUtil.isScreenOn(this) && mShouldShowActionsInOnResume) {
            animateActionsVisible();
            mShouldShowActionsInOnResume = false;
        }
    }

    @Override
    protected void setActionbar() {}

    @Override
    protected void setEvents() {
        mRvThing.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    boolean shrunk = false;
                    @Override
                    public void onGlobalLayout() {
                        int height = mRvThing.getHeight();
                        if (height > mDialogWidth * 1.2f) {
                            ViewGroup.LayoutParams vlp = mRvThing.getLayoutParams();
                            vlp.height = (int) (mDialogWidth * 1.2f);
                            mRvThing.requestLayout();
                            mRvThing.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
                            shrunk = true;
                        } else if (!shrunk) {
                            mRvThing.setOverScrollMode(View.OVER_SCROLL_NEVER);
                        }
                        // setFinishOnTouchOutside(true);
                    }
                });

        if (!DeviceUtil.hasLollipopApi()) {
            mRvThing.addOnScrollListener(new RecyclerView.OnScrollListener() {

                final int edgeColor = ContextCompat.getColor(
                        getApplicationContext(), R.color.black_26p);

                @Override
                public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                    super.onScrollStateChanged(recyclerView, newState);
                    EdgeEffectUtil.forRecyclerView(mRvThing, edgeColor);
                }
            });
        }

        mFlCancelAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }
}
