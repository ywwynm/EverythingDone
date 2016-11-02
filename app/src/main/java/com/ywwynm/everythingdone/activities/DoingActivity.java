package com.ywwynm.everythingdone.activities;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.support.v4.util.Pair;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.github.adnansm.timelytextview.TimelyView;
import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter;
import com.ywwynm.everythingdone.adapters.CheckListAdapter;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.helpers.RemoteActionHelper;
import com.ywwynm.everythingdone.managers.ModeManager;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.DeviceUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.views.FloatingActionButton;

import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;

import jp.wasabeef.blurry.Blurry;

/**
 * Created by qiizhang on 2016/10/31.
 * An Activity showing the thing that are currently doing
 */
public class DoingActivity extends EverythingDoneBaseActivity {

    public static final String TAG = "DoingActivity";

    public static final String KEY_TIME_TYPE  = "time_type";
    public static final String KEY_START_TIME = "start_time";

    public static Intent getOpenIntent(Context context, Thing thing, int time, int timeType, long startTime) {
        Intent intent = new Intent(context, DoingActivity.class);
        intent.putExtra(Def.Communication.KEY_THING, thing);
        intent.putExtra(Def.Communication.KEY_TIME,  time);
        intent.putExtra(KEY_TIME_TYPE, timeType);
        intent.putExtra(KEY_START_TIME, startTime);
        return intent;
    }

    private static final long HOUR_MILLIS   = 60 * 60 * 1000L;
    private static final long MINUTE_MILLIS = 60 * 1000L;

    private App mApp;

    private int mCardWidth;
    private int mRvMaxHeight;

    private Thing mThing;
    private Habit mHabit;

    // 30 minutes
    private int  mAfterTime;    // 30
    private int  mTimeType;     // minute
    private long mTimeInMillis; // 30 * 60 * 1000
    private long mStartTime;
    private long mLeftTime;

    private ImageView mIvBg;

    private TextView     mTvInfinity;
    private LinearLayout mLlHour;
    private LinearLayout mLlMinute;
    private LinearLayout mLlSecond;
    private TimelyView[] mTimelyViews;

    private int[] mTimeNumbers = { -1, -1, -1, -1, -1, -1 };
    private Handler mTimelyHandler;

    private RecyclerView mRecyclerView;

    private LinearLayout mLlBottom;
    private ImageView mIvForbidPhoneBt;
    private ImageView mIvAdd5MinBt;
    private FloatingActionButton mFabCancel;

    private int mPausedTimes = 0;
    private boolean mForbidPhoneUsage = false;
    private boolean mAdd5Min = false;


    @Override
    protected int getLayoutResource() {
        return R.layout.activity_doing;
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause");
        if (!DeviceUtil.isScreenOn(this)) {
            Log.i(TAG, "onPause called because of closing screen");
        } else {
            mPausedTimes++;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPausedTimes == 3 && mForbidPhoneUsage) {
            // TODO: 2016/11/1 failed at doing this thing
            Toast.makeText(this, "fuck", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void init() {
        initMembers();
        // TODO: 2016/11/1 mStartTime + mTimeInMillis < current time
        if (mLeftTime < -1) {

        }
        findViews();
        initUI();
        setActionbar();
        setEvents();
    }

    @Override
    protected void initMembers() {
        mApp = App.getApp();

        int base = DisplayUtil.getThingCardWidth(mApp);
        mCardWidth   = (int) (base * 1.2f);
        mRvMaxHeight = (int) (mCardWidth * 3 / 4f);

        final Intent intent = getIntent();
        mThing        = intent.getParcelableExtra(Def.Communication.KEY_THING);
        mAfterTime    = intent.getIntExtra(Def.Communication.KEY_TIME, -1);
        mTimeType     = intent.getIntExtra(KEY_TIME_TYPE, -1);

        if (mThing.getType() == Thing.HABIT) {
            mHabit = HabitDAO.getInstance(mApp).getHabitById(mThing.getId());
        }

        if (mAfterTime != -1 && mTimeType != -1) {
            mTimeInMillis = DateTimeUtil.getActualTimeAfterSomeTime(0, mTimeType, mAfterTime);
        } else {
            mTimeInMillis = -1;
        }

        mStartTime = intent.getLongExtra(KEY_START_TIME, -1L);
        if (mTimeInMillis == -1) {
            mLeftTime = -1;
        } else {
            if (System.currentTimeMillis() - mStartTime < 6 * 1000L) {
                mLeftTime = mTimeInMillis / 1000L * 1000L;
            } else {
                mLeftTime = (mStartTime + mTimeInMillis - System.currentTimeMillis()) / 1000L * 1000L;
            }
        }

        mTimelyHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(Message message) {
                if (message.what == 96) {
                    if (mAdd5Min) {
                        mLeftTime += 5 * MINUTE_MILLIS;
                        mTimeInMillis += 5 * MINUTE_MILLIS;
                        setTimelyViewsVisibilities();
                        mAdd5Min = false;
                    }
                    playTimelyAnimation();
                    mLeftTime -= 1000;
                    if (mLeftTime > 0) {
                        mTimelyHandler.sendEmptyMessageDelayed(96, 1000);
                    } else {

                    }
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    protected void findViews() {
        mIvBg = f(R.id.iv_bg_doing);

        mTvInfinity = f(R.id.tv_time_infinity_doing);
        mLlHour     = f(R.id.ll_hour_doing);
        mLlMinute   = f(R.id.ll_minute_doing);
        mLlSecond   = f(R.id.ll_second_doing);

        mRecyclerView = f(R.id.rv_thing_doing);

        mLlBottom        = f(R.id.ll_bottom_buttons_doing);
        mIvForbidPhoneBt = f(R.id.iv_forbid_phone_as_bt_doing);
        mIvAdd5MinBt     = f(R.id.iv_add_5_min_as_bt_doing);
        mFabCancel       = f(R.id.fab_cancel_doing);
    }

    @Override
    protected void initUI() {
        DisplayUtil.expandLayoutToFullscreenAboveLollipop(this);

        setBackground();
        setTimeViews();
        setRecyclerView();

        setBottomButtons();

        playEnterAnimations();
    }

    private void setBackground() {
        WallpaperManager wm = WallpaperManager.getInstance(mApp);
        Drawable wallpaper = wm.getDrawable();
        if (wallpaper != null) {
            mIvBg.setImageDrawable(wallpaper);
        }
        mIvBg.postDelayed(new Runnable() {
            @Override
            public void run() {
                Blurry.with(mApp)
                        .radius(16)
                        .sampling(4)
                        .color(Color.parseColor("#36000000"))
                        .animate(1600)
                        .onto((ViewGroup) f(R.id.fl_bg_cover_doing));
            }
        }, 160);
    }

    private void setTimeViews() {
        if (mTimeInMillis == -1) { // time is infinite
            mTvInfinity.setVisibility(View.VISIBLE);
            mLlHour.setVisibility(View.GONE);
            mLlMinute.setVisibility(View.GONE);
            mLlSecond.setVisibility(View.GONE);
            return;
        }

        mTimelyViews = new TimelyView[6];
        int[] ids = { R.id.tv_hour_1, R.id.tv_hour_2, R.id.tv_minute_1, R.id.tv_minute_2,
                R.id.tv_second_1, R.id.tv_second_2 };
        for (int i = 0; i < mTimelyViews.length; i++) {
            mTimelyViews[i] = f(ids[i]);
        }

        setTimelyViewsVisibilities();

        mLeftTime += 1000;
        mTimelyHandler.sendEmptyMessageDelayed(96, 1000);
    }

    private void setTimelyViewsVisibilities() {
        float density = DisplayUtil.getScreenDensity(mApp);
        if (mLeftTime < HOUR_MILLIS) {
            mLlHour.setVisibility(View.GONE);
            for (int i = 2; i < mTimelyViews.length; i++) {
                ViewGroup.LayoutParams vlp = mTimelyViews[i].getLayoutParams();
                vlp.width = (int) (density * 72);
                mTimelyViews[i].requestLayout();
            }
        } else {
            mLlHour.setVisibility(View.VISIBLE);
            for (TimelyView mTimelyView : mTimelyViews) {
                ViewGroup.LayoutParams vlp = mTimelyView.getLayoutParams();
                vlp.width = (int) (density * 56);
                mTimelyView.requestLayout();
            }
        }
    }

    private void calculateFromNumbers(long leftTime) {
        long hours = leftTime / HOUR_MILLIS;
        if (hours > 100) hours = 99;
        mTimeNumbers[0] = (int) (hours / 10);
        mTimeNumbers[1] = (int) (hours % 10);

        leftTime %= HOUR_MILLIS;
        long minutes = leftTime / MINUTE_MILLIS;
        mTimeNumbers[2] = (int) (minutes / 10);
        mTimeNumbers[3] = (int) (minutes % 10);

        leftTime %= MINUTE_MILLIS;
        long seconds = leftTime / 1000;
        mTimeNumbers[4] = (int) (seconds / 10);
        mTimeNumbers[5] = (int) (seconds % 10);
    }

    private void playTimelyAnimation() {
        int[] fromNumbers = new int[6];
        System.arraycopy(mTimeNumbers, 0, fromNumbers, 0, 6);
        calculateFromNumbers(mLeftTime - 1000);
        for (int i = 0; i < fromNumbers.length; i++) {
            if (fromNumbers[i] != mTimeNumbers[i]) {
                mTimelyViews[i].animate(fromNumbers[i], mTimeNumbers[i]).start();
            }
        }
    }

    private void setRecyclerView() {
        int p = (DisplayUtil.getScreenSize(mApp).x - mCardWidth) / 2;
        mRecyclerView.setPadding(p, 0, p, 0);

        final List<Thing> singleThing = Collections.singletonList(new Thing(mThing));
        final BaseThingsAdapter adapter = new BaseThingsAdapter(this) {

            @Override
            protected int getCurrentMode() {
                return ModeManager.NORMAL;
            }

            @Override
            protected boolean shouldShowPrivateContent() {
                return true;
            }

            @Override
            protected int getCardWidth() {
                return mCardWidth;
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
                holder.rlReminder.setVisibility(View.GONE);
                holder.rlHabit.setVisibility(View.GONE);
                holder.vReminderSeparator.setVisibility(View.GONE);
                holder.vHabitSeparator1.setVisibility(View.GONE);
            }

            @Override
            protected void onChecklistAdapterInitialized(
                    final BaseThingViewHolder holder, final CheckListAdapter adapter, final Thing thing) {
                super.onChecklistAdapterInitialized(holder, adapter, thing);
                holder.cv.setShouldInterceptTouchEvent(false);
                adapter.setTvItemClickCallback(new CheckListAdapter.TvItemClickCallback() {
                    @Override
                    public void onItemClick(int itemPos) {
                        String updatedContent = CheckListHelper.toggleChecklistItem(
                                thing.getContent(), itemPos);
                        thing.setContent(updatedContent);
                        notifyDataSetChanged();
                        RemoteActionHelper.toggleChecklistItem(mApp, thing.getId(), itemPos);
                    }
                });
            }
        };
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mRecyclerView.setAdapter(adapter);
    }

    private void setBottomButtons() {
        if (DeviceUtil.hasKitKatApi() && DisplayUtil.hasNavigationBar(mApp)) {
            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) mLlBottom.getLayoutParams();
            flp.bottomMargin = DisplayUtil.getNavigationBarHeight(mApp);
            mLlBottom.requestLayout();
        }

        mIvForbidPhoneBt.setScaleX(0);
        mIvForbidPhoneBt.setScaleY(0);
        mIvAdd5MinBt.setScaleX(0);
        mIvAdd5MinBt.setScaleY(0);

        mFabCancel.shrinkWithoutAnim();
    }

    private void playEnterAnimations() {
        mTimelyHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mTvInfinity.animate().setDuration(1600).alpha(1);
                f(R.id.tv_separator_1_doing).animate().setDuration(1600).alpha(1);
                f(R.id.tv_separator_2_doing).animate().setDuration(1600).alpha(1);
                f(R.id.tv_swipe_to_finish_doing).animate().setDuration(1600).alpha(1);
                mRecyclerView.animate().setDuration(1600).alpha(0.8f);
                mRecyclerView.scrollBy(0, Integer.MAX_VALUE);
            }
        }, 160);
        mTimelyHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mRecyclerView.smoothScrollToPosition(0);
                OvershootInterpolator oi = new OvershootInterpolator();
                mIvForbidPhoneBt.animate().setDuration(160).setInterpolator(oi).scaleX(1);
                mIvForbidPhoneBt.animate().setDuration(160).setInterpolator(oi).scaleY(1);
                mIvAdd5MinBt.animate().setDuration(160).setInterpolator(oi).scaleX(1);
                mIvAdd5MinBt.animate().setDuration(160).setInterpolator(oi).scaleY(1);
                mFabCancel.spread();
            }
        }, 1400);
    }

    @Override
    protected void setActionbar() { }

    @Override
    protected void setEvents() {
        setRecyclerViewEvent();

        helpDifferNormalPressState(mIvForbidPhoneBt, 1f, 0.56f);
        helpDifferNormalPressState(mIvAdd5MinBt,     1f, 0.56f);
    }

    private void setRecyclerViewEvent() {
        mRecyclerView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {

                    @Override
                    public void onGlobalLayout() {
                        int height = mRecyclerView.getHeight();
                        if (height > mRvMaxHeight) {
                            ViewGroup.LayoutParams vlp = mRecyclerView.getLayoutParams();
                            vlp.height = mRvMaxHeight;
                            mRecyclerView.requestLayout();
                        }
                    }
                });
        ItemTouchHelper helper = new ItemTouchHelper(new CardTouchCallback());
        helper.attachToRecyclerView(mRecyclerView);
    }

    private void helpDifferNormalPressState(
            View view, final float normalAlpha, final float pressedAlpha) {
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                switch (motionEvent.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        view.setAlpha(pressedAlpha);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                         view.setAlpha(normalAlpha);
                         break;
                }
                return false;
            }
        });
    }

    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.fab_cancel_doing: {
                finish();
                break;
            }
            case R.id.iv_forbid_phone_as_bt_doing: {
                toggleForbidPhotoUsage();
                break;
            }
            case R.id.iv_add_5_min_as_bt_doing: {
                if (canAdd5Min()) {
                    mAdd5Min = true;
                }
                break;
            }
            default:break;
        }
    }

    private void toggleForbidPhotoUsage() {
        if (mForbidPhoneUsage) {
            mIvForbidPhoneBt.setImageResource(R.drawable.ic_forbid_phone_off);
        } else {
            mIvForbidPhoneBt.setImageResource(R.drawable.ic_forbid_phone_on);
        }
        mForbidPhoneUsage = !mForbidPhoneUsage;
        mPausedTimes = 0;
    }

    private boolean canAdd5Min() {
        if (mLeftTime <= 0) {
            return false;
        }
        if (mHabit != null) {
            long etc = mStartTime + mTimeInMillis + 5 * MINUTE_MILLIS;
            int habitType = mHabit.getType();
            GregorianCalendar calendar = new GregorianCalendar();
            int ct = calendar.get(habitType); // current t
            calendar.setTimeInMillis(etc);
            if (calendar.get(habitType) != ct) {
                Toast.makeText(getApplicationContext(),
                        R.string.start_doing_time_long_t, Toast.LENGTH_LONG).show();
                return false;
            } else {
                long nextTime = mHabit.getMinHabitReminderTime();
                if (etc >= nextTime - 6 * MINUTE_MILLIS) {
                    Toast.makeText(getApplicationContext(),
                            R.string.start_doing_time_long_alarm, Toast.LENGTH_LONG).show();
                }
                return false;
            }
        }
        return true;
    }

    @Override
    public void onBackPressed() {
//        AlertDialogFragment adf = new AlertDialogFragment();
//        adf.setTitleColor(mThing.getColor());
//        adf.setConfirmColor(mThing.getColor());
//        adf.setConfirmListener(new AlertDialogFragment.ConfirmListener() {
//            @Override
//            public void onConfirm() {
//                finish();
//            }
//        });
//        adf.show(getFragmentManager(), AlertDialogFragment.TAG);
        super.onBackPressed();
    }

    class CardTouchCallback extends ItemTouchHelper.Callback {

        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            int swipeFlags = ItemTouchHelper.START | ItemTouchHelper.END;
            return makeMovementFlags(0, swipeFlags);
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder,
                              RecyclerView.ViewHolder target) {
            return false;
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
            Pair<Thing, Integer> pair = App.getThingAndPosition(mApp, mThing.getId(), -1);
            if (mThing.getType() == Thing.HABIT) {
                RemoteActionHelper.finishHabitOnce(mApp, mThing, pair.second, System.currentTimeMillis());
            } else {
                RemoteActionHelper.finishReminder(mApp, mThing, pair.second);
            }
            finish();
        }

        @Override
        public void onChildDraw(Canvas c, RecyclerView recyclerView,
                                RecyclerView.ViewHolder viewHolder, float dX, float dY,
                                int actionState, boolean isCurrentlyActive) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                int displayWidth = DisplayUtil.getDisplaySize(mApp).x;
                View v = viewHolder.itemView;
                if (dX < 0) {
                    v.setAlpha(1.0f + dX / v.getRight());
                } else {
                    v.setAlpha(1.0f - dX / (displayWidth - v.getLeft()));
                }
            }
        }
    }
}
