package com.ywwynm.everythingdone.activities;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.StringRes;
import android.support.v4.util.Pair;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.LinearSmoothScroller;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.util.Log;
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
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter;
import com.ywwynm.everythingdone.adapters.CheckListAdapter;
import com.ywwynm.everythingdone.fragments.AlertDialogFragment;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.helpers.RemoteActionHelper;
import com.ywwynm.everythingdone.managers.ModeManager;
import com.ywwynm.everythingdone.model.DoingRecord;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.services.DoingService;
import com.ywwynm.everythingdone.utils.DeviceUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.views.FloatingActionButton;

import java.util.Collections;
import java.util.List;

import jp.wasabeef.blurry.Blurry;

/**
 * Created by qiizhang on 2016/10/31.
 * An Activity showing the thing that are currently doing
 */
public class DoingActivity extends EverythingDoneBaseActivity {

    public static final String TAG = "DoingActivity";

    public static final String KEY_RESUME = TAG + ".resume";

    public static final String BROADCAST_ACTION_JUST_FINISH = TAG + ".just_finish";

    public static Intent getOpenIntent(Context context, boolean resume) {
        return new Intent(context, DoingActivity.class).putExtra(KEY_RESUME, resume);
    }

    private static final long MINUTE_MILLIS = 60 * 1000L;
    private static final long HOUR_MILLIS   = 60 * MINUTE_MILLIS;

    private App mApp;

    private int mCardWidth;
    private int mRvMaxHeight;

    private DoingService.DoingBinder mDoingBinder;

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            mDoingBinder = (DoingService.DoingBinder) iBinder;
            initAfterBindService();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {

        }
    };

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (BROADCAST_ACTION_JUST_FINISH.equals(intent.getAction())) {
                finish();
            }
        }
    };

    private Thing mThing;

    private ImageView mIvBg;

    private TextView     mTvInfinity;
    private LinearLayout mLlHour;
    private LinearLayout mLlMinute;
    private LinearLayout mLlSecond;
    private TimelyView[] mTimelyViews;

    private RecyclerView mRecyclerView;

    private LinearLayout mLlBottom;
    private FrameLayout mFlAdd5Min;
    private FrameLayout mFlStrictMode;
    private FloatingActionButton mFabStrictMode;
    private FrameLayout mFlCancel;

    private Handler mInfinityHandler;

    private boolean mServiceUnbind = false;

    @Override
    protected int getLayoutResource() {
        return R.layout.activity_doing;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mDoingBinder != null) {
            mDoingBinder.setStartPlayTime(-1L);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop");
        if (!DeviceUtil.isScreenOn(this)) {
            Log.i(TAG, "onStop called because of closing screen");
        } else if (mDoingBinder != null && mDoingBinder.isInStrictMode()) {
            mDoingBinder.setPlayedTimes(mDoingBinder.getPlayedTimes() + 1);
            mDoingBinder.setStartPlayTime(System.currentTimeMillis());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mReceiver);
        if (mInfinityHandler != null) {
            mInfinityHandler.removeMessages(96);
            mInfinityHandler = null;
        }
        if (!mServiceUnbind) {
            mDoingBinder.setCountdownListener(null);
            unbindService(mServiceConnection);
        }
    }

    @Override
    protected void beforeInit() {
        Intent intent = new Intent(this, DoingService.class);
        bindService(intent, mServiceConnection, BIND_AUTO_CREATE);

        IntentFilter filter = new IntentFilter(BROADCAST_ACTION_JUST_FINISH);
        registerReceiver(mReceiver, filter);
    }

    @Override
    protected void initMembers() {
        mApp = App.getApp();

        int base = DisplayUtil.getThingCardWidth(mApp);
        mCardWidth   = (int) (base * 1.2f);
        mRvMaxHeight = (int) (mCardWidth * 3 / 4f);
    }

    @Override
    protected void findViews() {
        mIvBg = f(R.id.iv_bg_doing);

        mTvInfinity = f(R.id.tv_time_infinity_doing);
        mLlHour     = f(R.id.ll_hour_doing);
        mLlMinute   = f(R.id.ll_minute_doing);
        mLlSecond   = f(R.id.ll_second_doing);

        mRecyclerView = f(R.id.rv_thing_doing);

        mLlBottom      = f(R.id.ll_bottom_buttons_doing);
        mFlAdd5Min     = f(R.id.fl_add_5_min);
        mFlStrictMode  = f(R.id.fl_strict_mode);
        mFabStrictMode = f(R.id.fab_strict_mode);
        mFlCancel      = f(R.id.fl_cancel_doing);

        mTimelyViews = new TimelyView[6];
        int[] ids = { R.id.tv_hour_1, R.id.tv_hour_2, R.id.tv_minute_1, R.id.tv_minute_2,
                R.id.tv_second_1, R.id.tv_second_2 };
        for (int i = 0; i < mTimelyViews.length; i++) {
            mTimelyViews[i] = f(ids[i]);
        }
    }

    @Override
    protected void initUI() {
        DisplayUtil.expandLayoutToFullscreenAboveLollipop(this);

        initBackground();
        initBottomButtons();
    }

    private void initBackground() {
        WallpaperManager wm = WallpaperManager.getInstance(mApp);
        Drawable wallpaper = wm.getDrawable();
        if (wallpaper != null) {
            mIvBg.setImageDrawable(wallpaper);
        }
    }

    private void initBottomButtons() {
        if (DeviceUtil.hasKitKatApi() && DisplayUtil.hasNavigationBar(mApp)) {
            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) mLlBottom.getLayoutParams();
            flp.bottomMargin += DisplayUtil.getNavigationBarHeight(mApp);
            mLlBottom.requestLayout();
        }

        mFlAdd5Min.setScaleX(0);
        mFlAdd5Min.setScaleY(0);
        mFlStrictMode.setScaleX(0);
        mFlStrictMode.setScaleY(0);
        mFlCancel.setScaleX(0);
        mFlCancel.setScaleY(0);
    }

    @Override
    protected void setActionbar() { }

    @Override
    protected void setEvents() {
        setRecyclerViewEvent();
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

    private void initAfterBindService() {
        mThing = mDoingBinder.getThing();
        if (mThing == null) {
            Toast.makeText(this, R.string.doing_toast_pass_data_error, Toast.LENGTH_LONG).show();
            DoingService.sStopReason = DoingRecord.STOP_REASON_INIT_FAILED;
            finishWithStoppingService();
            return;
        }

        if (mDoingBinder.isInStrictMode()) {
            Toast.makeText(this, R.string.doing_toast_already_strict_mode, Toast.LENGTH_LONG).show();
        }

        playBackgroundAnimation();

        updateTimeViews();

        initRecyclerView();
        updateBottomButtons();

        if (mDoingBinder.getTimeInMillis() == -1) {
            mInfinityHandler = new Handler(new Handler.Callback() {
                @Override
                public boolean handleMessage(Message message) {
                    if (message.what == 96) {
                        mTvInfinity.animate().setDuration(3600).alpha(1 - mTvInfinity.getAlpha());
                        mInfinityHandler.sendEmptyMessageDelayed(96, 3600);
                    }
                    return false;
                }
            });
        }

        startCountdownAndPlayAnimations();
    }

    private void playBackgroundAnimation() {
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

    private void updateTimeViews() {
        if (mDoingBinder.getTimeInMillis() == -1) { // time is infinite
            mTvInfinity.setVisibility(View.VISIBLE);
            mLlHour.setVisibility(View.GONE);
            mLlMinute.setVisibility(View.GONE);
            mLlSecond.setVisibility(View.GONE);
        } else {
            setTimelyViewsVisibilities(mDoingBinder.getLeftTime());
        }
    }

    private void setTimelyViewsVisibilities(long leftTime) {
        float density = DisplayUtil.getScreenDensity(mApp);
        if (leftTime < HOUR_MILLIS) {
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

    private void initRecyclerView() {
        int p = (DisplayUtil.getScreenSize(mApp).x - mCardWidth) / 2;
        mRecyclerView.setPadding(p, 0, p, 0);

        final List<Thing> singleThing = Collections.singletonList(mThing);
        final BaseThingsAdapter adapter = new BaseThingsAdapter(this) {

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
                        mDoingBinder.setThing(thing);
                        notifyDataSetChanged();
                        RemoteActionHelper.toggleChecklistItem(mApp, thing.getId(), itemPos);
                    }

                    @Override
                    public void onItemSpaceClick(View v) {}
                });
            }
        };
        adapter.setCardWidth(mCardWidth);
        adapter.setShouldShowPrivateContent(true);
        adapter.setChecklistMaxItemCount(-1);
        mRecyclerView.setLayoutManager(new SlowScrollLinearLayoutManager(this));
        mRecyclerView.setAdapter(adapter);
    }

    private void updateBottomButtons() {
        if (mDoingBinder.getTimeInMillis() == -1) {
            mFlAdd5Min.setVisibility(View.GONE);
        }

        if (mDoingBinder.isInStrictMode()) {
            mFabStrictMode.setImageResource(R.drawable.ic_doing_strict_mode_on);
            mFabStrictMode.setContentDescription(getString(R.string.cd_doing_strict_mode_on));
        } else {
            mFabStrictMode.setImageResource(R.drawable.ic_doing_strict_mode_off);
            mFabStrictMode.setContentDescription(getString(R.string.cd_doing_strict_mode_off));
        }
    }

    private void startCountdownAndPlayAnimations() {
        // after 1760ms, background blur animation will finish
        final Intent intent = getIntent();
        final boolean resume = intent.getBooleanExtra(KEY_RESUME, false);
        mRecyclerView.postDelayed(new Runnable() {
            @Override
            public void run() {
                playEnterAnimations();

                mDoingBinder.setCountdownListener(new DoingService.DoingListener() {

                    @Override
                    public void onLeftTimeChanged(int[] numbersFrom, int[] numbersTo, long leftTimeBefore, long leftTimeAfter) {
                        playTimelyAnimation(numbersFrom, numbersTo);
                    }

                    @Override
                    public void onAdd5Min(long leftTime) {
                        setTimelyViewsVisibilities(leftTime);
                    }

                    @Override
                    public void onCountdownFailed() {
                        finish();
                    }

                    @Override
                    public void onCountdownEnd() {

                    }
                });
                mDoingBinder.startCountdown(resume);

                if (mDoingBinder.getTimeInMillis() == -1 && mInfinityHandler != null) {
                    mInfinityHandler.sendEmptyMessageDelayed(96, 1760);
                }
            }
        }, 1000);
    }

    private void playEnterAnimations() {
        mRecyclerView.postDelayed(new Runnable() {
            @Override
            public void run() {
                mTvInfinity.animate().setDuration(1600).alpha(0.76f);
                f(R.id.tv_swipe_to_finish_doing).animate().setDuration(1600).alpha(1);
                mRecyclerView.animate().setDuration(1600).alpha(0.84f);
                mRecyclerView.scrollBy(0, Integer.MAX_VALUE);
            }
        }, 160); // executed after 1160ms, animation ends at 2760ms
        mRecyclerView.postDelayed(new Runnable() {
            @Override
            public void run() {
                f(R.id.tv_separator_1_doing).animate().setDuration(360).alpha(0.86f);
                f(R.id.tv_separator_2_doing).animate().setDuration(360).alpha(0.76f);

                mRecyclerView.smoothScrollToPosition(0);

                OvershootInterpolator oi = new OvershootInterpolator();
                mFlAdd5Min.animate().setDuration(360).setInterpolator(oi).scaleX(1);
                mFlAdd5Min.animate().setDuration(360).setInterpolator(oi).scaleY(1);
                mFlStrictMode.animate().setDuration(360).setInterpolator(oi).scaleX(1);
                mFlStrictMode.animate().setDuration(360).setInterpolator(oi).scaleY(1);
                mFlCancel.animate().setDuration(360).setInterpolator(oi).scaleX(1);
                mFlCancel.animate().setDuration(360).setInterpolator(oi).scaleY(1);
            }
        }, 1200); // executed after 1360ms, animation ends at 1720ms
    }

    private void playTimelyAnimation(int[] from, int[] to) {
        for (int i = 0; i < from.length; i++) {
            if (from[i] != to[i]) {
                mTimelyViews[i].animate(from[i], to[i]).start();
            }
        }
    }

    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.fab_add_5_min: {
                if (mDoingBinder.canAdd5Min()) {
                    mDoingBinder.add5Min();
                }
                break;
            }
            case R.id.fab_strict_mode: {
                toggleStrictMode();
                break;
            }
            case R.id.fab_cancel_doing: {
                AlertDialogFragment adf = new AlertDialogFragment();
                adf.setConfirmColor(mThing.getColor());
                adf.setContent(getString(R.string.doing_alert_stop_doing_content));
                adf.setConfirmListener(new AlertDialogFragment.ConfirmListener() {
                    @Override
                    public void onConfirm() {
                        DoingService.sStopReason = DoingRecord.STOP_REASON_CANCEL_USER;
                        finishWithStoppingService();
                    }
                });
                adf.show(getFragmentManager(), AlertDialogFragment.TAG);
                break;
            }
            default:break;
        }
    }

    private void toggleStrictMode() {
        boolean inStrictMode = mDoingBinder.isInStrictMode();
        if (inStrictMode) {
            if (!mDoingBinder.hasTurnedStrictModeOff()) {
                mFabStrictMode.setImageResource(R.drawable.ic_doing_strict_mode_off);
                mFabStrictMode.setContentDescription(getString(R.string.cd_doing_strict_mode_off));
            } else {
                showAlertDialog(R.string.doing_alert_close_strict_twice_title,
                        R.string.doing_alert_close_strict_twice_content);
                return;
            }
        } else {
            if (!mDoingBinder.hasTurnedStrictModeOn()) {
                showAlertDialog(R.string.doing_alert_first_strict_mode_title,
                        R.string.doing_alert_first_strict_mode_content);
            }
            mFabStrictMode.setImageResource(R.drawable.ic_doing_strict_mode_on);
            mFabStrictMode.setContentDescription(getString(R.string.cd_doing_strict_mode_on));
        }
        mDoingBinder.setInStrictMode(!inStrictMode);
        mDoingBinder.setPlayedTimes(0);
        mDoingBinder.setStartPlayTime(-1L);
        mDoingBinder.setTotalPlayedTime(0);
    }

    private void showAlertDialog(@StringRes int titleRes, @StringRes int contentRes) {
        AlertDialogFragment adf = new AlertDialogFragment();
        adf.setTitleColor(mThing.getColor());
        adf.setConfirmColor(mThing.getColor());
        adf.setShowCancel(false);
        adf.setTitle(getString(titleRes));
        adf.setContent(getString(contentRes));
        adf.show(getFragmentManager(), AlertDialogFragment.TAG);
    }

    private void finishWithStoppingService() {
        App.setDoingThingId(-1L);
        unbindService(mServiceConnection);
        stopService(new Intent(this, DoingService.class));
        mServiceUnbind = true;
        finish();
    }

    private class SlowScrollLinearLayoutManager extends LinearLayoutManager {

        private LinearSmoothScroller mSmoothScroller;

        SlowScrollLinearLayoutManager(Context context) {
            super(context);
            mSmoothScroller = new LinearSmoothScroller(context) {
                @Override
                protected int calculateTimeForScrolling(int dx) {
                    return 360;
                }
            };
        }

        @Override
        public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state, int position) {
            mSmoothScroller.setTargetPosition(position);
            startSmoothScroll(mSmoothScroller);
        }
    }

    private class CardTouchCallback extends ItemTouchHelper.Callback {

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
            DoingService.sStopReason = DoingRecord.STOP_REASON_FINISH;
            if (mThing.getType() == Thing.HABIT) {
                if (!RemoteActionHelper.finishHabitOnce(
                        mApp, mThing, pair.second, DoingService.sHrTime)) {
                    DoingService.sStopReason = DoingRecord.STOP_REASON_CANCEL_USER;
                }
            } else {
                RemoteActionHelper.finishReminder(mApp, mThing, pair.second);
            }
            finishWithStoppingService();
        }

        @Override
        public void onChildDraw(Canvas c, RecyclerView recyclerView,
                                RecyclerView.ViewHolder viewHolder, float dX, float dY,
                                int actionState, boolean isCurrentlyActive) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                int displayWidth = DisplayUtil.getDisplaySize(mApp).x;
                View v = mRecyclerView;
                if (dX < 0) {
                    v.setAlpha(1.0f + dX / v.getRight());
                } else {
                    v.setAlpha(1.0f - dX / (displayWidth - v.getLeft()));
                }
            }
        }
    }
}
