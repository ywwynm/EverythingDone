package com.ywwynm.everythingdone.activities;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.IBinder;
import android.support.v4.util.Pair;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.LinearSmoothScroller;
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
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter;
import com.ywwynm.everythingdone.adapters.CheckListAdapter;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.helpers.RemoteActionHelper;
import com.ywwynm.everythingdone.managers.ModeManager;
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

    public static final String KEY_RESUME = "resume";

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
            DoingActivity.super.init();
            startCountdownAndPlayAnimations();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {

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
    private ImageView mIvForbidPhoneBt;
    private ImageView mIvAdd5MinBt;
    private FloatingActionButton mFabCancel;

    private int mPausedTimes = 0;
    private boolean mForbidPhoneUsage = false;

    private boolean mServiceUnbind = false;


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
    protected void onDestroy() {
        super.onDestroy();
        if (!mServiceUnbind) {
            unbindService(mServiceConnection);
        }
    }

    @Override
    protected void init() {
        Intent intent = new Intent(this, DoingService.class);
        bindService(intent, mServiceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void initMembers() {
        mApp = App.getApp();

        int base = DisplayUtil.getThingCardWidth(mApp);
        mCardWidth   = (int) (base * 1.2f);
        mRvMaxHeight = (int) (mCardWidth * 3 / 4f);

        mThing = mDoingBinder.getThing();
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
        if (mDoingBinder.getTimeInMillis() == -1) { // time is infinite
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

        setTimelyViewsVisibilities(mDoingBinder.getLeftTime());
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
            protected int getChecklistMaxItemCount() {
                return -1;
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
                holder.tvContent.setMaxLines(Integer.MAX_VALUE);
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
                        mDoingBinder.setThing(thing);
                        notifyDataSetChanged();
                        RemoteActionHelper.toggleChecklistItem(mApp, thing.getId(), itemPos);
                    }
                });
            }
        };
        mRecyclerView.setLayoutManager(new SlowScrollLinearLayoutManager(this));
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
        mFabCancel.setScaleX(0);
        mFabCancel.setScaleY(0);
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

    private void startCountdownAndPlayAnimations() {
        final Intent intent = getIntent();
        final boolean resume = intent.getBooleanExtra(KEY_RESUME, false);
        mTimelyViews[5].postDelayed(new Runnable() {
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
                    public void onCountdownEnd() {

                    }
                });
                mDoingBinder.startCountdown(resume);
            }
        }, 1000);
    }

    private void playEnterAnimations() {
        mTimelyViews[5].postDelayed(new Runnable() {
            @Override
            public void run() {
                mTvInfinity.animate().setDuration(1600).alpha(1);
                f(R.id.tv_swipe_to_finish_doing).animate().setDuration(1600).alpha(1);
                mRecyclerView.animate().setDuration(1600).alpha(0.8f);
                mRecyclerView.scrollBy(0, Integer.MAX_VALUE);
            }
        }, 160);
        mTimelyViews[5].postDelayed(new Runnable() {
            @Override
            public void run() {
                f(R.id.tv_separator_1_doing).animate().setDuration(360).alpha(1);
                f(R.id.tv_separator_2_doing).animate().setDuration(360).alpha(1);
                mRecyclerView.smoothScrollToPosition(0);
                OvershootInterpolator oi = new OvershootInterpolator();
                mIvForbidPhoneBt.animate().setDuration(360).setInterpolator(oi).scaleX(1);
                mIvForbidPhoneBt.animate().setDuration(360).setInterpolator(oi).scaleY(1);
                mIvAdd5MinBt.animate().setDuration(360).setInterpolator(oi).scaleX(1);
                mIvAdd5MinBt.animate().setDuration(360).setInterpolator(oi).scaleY(1);
                mFabCancel.animate().setDuration(360).setInterpolator(oi).scaleX(1);
                mFabCancel.animate().setDuration(360).setInterpolator(oi).scaleY(1);
            }
        }, 1200);
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
            case R.id.fab_cancel_doing: {
                finishWithStoppingService();
                break;
            }
            case R.id.iv_forbid_phone_as_bt_doing: {
                toggleForbidPhotoUsage();
                break;
            }
            case R.id.iv_add_5_min_as_bt_doing: {
                if (mDoingBinder.canAdd5Min()) {
                    mDoingBinder.add5Min();
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

    private void finishWithStoppingService() {
        App.setDoingThingId(-1L);
        unbindService(mServiceConnection);
        stopService(new Intent(this, DoingService.class));
        mServiceUnbind = true;
        finish();
    }

    class SlowScrollLinearLayoutManager extends LinearLayoutManager {

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
            finishWithStoppingService();
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
