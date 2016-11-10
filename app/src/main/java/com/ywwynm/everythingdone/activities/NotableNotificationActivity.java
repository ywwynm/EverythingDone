package com.ywwynm.everythingdone.activities;

import android.content.Context;
import android.content.Intent;
import android.support.v4.content.ContextCompat;
import android.support.v4.util.Pair;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.managers.ModeManager;
import com.ywwynm.everythingdone.model.HabitReminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.receivers.HabitNotificationActionReceiver;
import com.ywwynm.everythingdone.receivers.ReminderNotificationActionReceiver;
import com.ywwynm.everythingdone.utils.DisplayUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by qiizhang on 2016/11/10.
 * An Activity to provide more noticeable(this word is longer than notable so I decided not to use
 * it as part of class name. Another reason is that I didn't find most appropriate word to translate
 * "<b>突出的</b>提醒" due to my poor English...) notification for Reminders/Habits
 */
public class NotableNotificationActivity extends EverythingDoneBaseActivity {

    public static final String TAG = "NotableNotificationActivity";

    public static Intent getOpenIntentForReminder(Context context, long thingId, int position) {
        return new Intent(context, NotableNotificationActivity.class)
                .putExtra(Def.Communication.KEY_ID, thingId)
                .putExtra(Def.Communication.KEY_POSITION, position);
    }

    private static final String KEY_IS_HABIT = TAG + ".is_habit";

    public static Intent getOpenIntentForHabit(Context context, long hrId, int position, long hrTime) {
        return new Intent(context, NotableNotificationActivity.class)
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
    private List<View.OnClickListener> mActions;

    private RecyclerView mRvThing;

    @Override
    protected void init() {
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
    protected int getLayoutResource() {
        return R.layout.activity_notable_notification;
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
        mActions.add(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendBroadcastForReminderAndLeave(Def.Communication.NOTIFICATION_ACTION_FINISH);
            }
        });

        if (mThing.getType() == Thing.REMINDER) {
            mActionsTexts.add(R.string.act_start_doing);
            mActions.add(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sendBroadcastForReminderAndLeave(Def.Communication.NOTIFICATION_ACTION_START_DOING);
                }
            });

            mActionsTexts.add(R.string.act_delay);
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
        mActions.add(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(NotableNotificationActivity.this,
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
        mActions.add(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(NotableNotificationActivity.this,
                        HabitNotificationActionReceiver.class);
                intent.setAction(Def.Communication.NOTIFICATION_ACTION_START_DOING);
                intent.putExtra(Def.Communication.KEY_ID, mHrId);
                intent.putExtra(Def.Communication.KEY_POSITION, mPosition);
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
        mRvThing = f(R.id.rv_thing_notable_notification);
    }

    @Override
    protected void initUI() {
        WindowManager.LayoutParams wlp = getWindow().getAttributes();
        wlp.width = mDialogWidth;
        getWindow().setAttributes(wlp);

        f(R.id.ll_root_notable_notification).setBackgroundColor(mThing.getColor());

        initRvThing();
        initActionsUI();
    }

    private void initRvThing() {
        final List<Thing> singleThing = Collections.singletonList(new Thing(mThing));
        BaseThingsAdapter adapter = new BaseThingsAdapter(this) {
            @Override
            protected int getCurrentMode() {
                return ModeManager.NORMAL;
            }

            @Override
            protected boolean shouldShowPrivateContent() {
                return false;
            }

            @Override
            protected int getChecklistMaxItemCount() {
                return -1;
            }

            @Override
            protected int getCardWidth() {
                return mDialogWidth;
            }

            @Override
            protected List<Thing> getThings() {
                return singleThing;
            }

            @Override
            public void onBindViewHolder(BaseThingViewHolder holder, int position) {
                super.onBindViewHolder(holder, position);
                holder.cv.setRadius(0);
                holder.cv.setCardElevation(0);
                holder.tvTitle.setMaxLines(Integer.MAX_VALUE);
                holder.tvContent.setMaxLines(Integer.MAX_VALUE);
                holder.rlReminder.setVisibility(View.GONE);
                holder.rlHabit.setVisibility(View.GONE);
                holder.vReminderSeparator.setVisibility(View.GONE);
                holder.vHabitSeparator1.setVisibility(View.GONE);
                holder.flDoing.setVisibility(View.GONE);
            }
        };
        mRvThing.setLayoutManager(new LinearLayoutManager(this));
        mRvThing.setAdapter(adapter);
    }

    private void initActionsUI() {
        float density = DisplayUtil.getScreenDensity(this);
        int p12 = (int) (density * 12);
        int p8 = (int) (density * 8);
        int textColor = ContextCompat.getColor(this, R.color.app_accent);
        final int size = mActions.size();
        LinearLayout ll = f(R.id.ll_actions_notable_notification);
        for (int i = 0; i < size; i++) {
            TextView tvAsBt = new TextView(this);
            tvAsBt.setPaddingRelative(p12, p8, p12, p8);
            tvAsBt.setText(mActionsTexts.get(i));
            tvAsBt.setTextColor(textColor);
            tvAsBt.setOnClickListener(mActions.get(i));
            tvAsBt.setBackgroundResource(R.drawable.selectable_item_background_light);
            ll.addView(tvAsBt);
        }
    }

    @Override
    protected void setActionbar() {}

    @Override
    protected void setEvents() {
        mRvThing.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {

                    @Override
                    public void onGlobalLayout() {
                        int height = mRvThing.getHeight();
                        if (height > mDialogWidth * 1.2f) {
                            ViewGroup.LayoutParams vlp = mRvThing.getLayoutParams();
                            vlp.height = (int) (mDialogWidth * 1.2f);
                            mRvThing.requestLayout();
                        }
                    }
                });
    }
}
