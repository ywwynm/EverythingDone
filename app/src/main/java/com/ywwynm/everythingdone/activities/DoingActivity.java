package com.ywwynm.everythingdone.activities;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import com.github.adnansm.timelytextview.TimelyView;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter;
import com.ywwynm.everythingdone.managers.ModeManager;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DisplayUtil;

import java.util.Collections;
import java.util.List;

import jp.wasabeef.blurry.Blurry;

/**
 * Created by qiizhang on 2016/10/31.
 * An Activity showing the thing that are currently doing
 */
public class DoingActivity extends EverythingDoneBaseActivity {

    public static final String TAG = "DoingActivity";

    public static final String KEY_TIME_TYPE = "time_type";
    public static final String KEY_END_TIME  = "end_time";

    public static Intent getOpenIntent(Context context, Thing thing, int time, int timeType) {
        Intent intent = new Intent(context, DoingActivity.class);
        intent.putExtra(Def.Communication.KEY_THING, thing);
        intent.putExtra(Def.Communication.KEY_TIME,  time);
        intent.putExtra(KEY_TIME_TYPE, timeType);
        return intent;
    }

    private int mRvWidth;
    private int mRvMaxHeight;

    private Thing mThing;
    private int   mTime;
    private int   mTimeType;

    private ImageView mIvBg;

    private TimelyView mTimelyView;

    private RecyclerView mRecyclerView;

    @Override
    protected int getLayoutResource() {
        return R.layout.activity_doing;
    }

    @Override
    protected void init() {
        initMembers();
        findViews();
        initUI();
        setActionbar();
        setEvents();
    }

    @Override
    protected void initMembers() {
        int base = DisplayUtil.getThingCardWidth(this);
        mRvWidth     = (int) (base * 1.2f);
        mRvMaxHeight = (int) (mRvWidth * 3 / 4f);

        final Intent intent = getIntent();
        mThing    = intent.getParcelableExtra(Def.Communication.KEY_THING);
        mTime     = intent.getIntExtra(Def.Communication.KEY_TIME, -1);
        mTimeType = intent.getIntExtra(KEY_TIME_TYPE, -1);
    }

    @Override
    protected void findViews() {
        mIvBg = f(R.id.iv_bg_doing);

        mTimelyView = f(R.id.timely_view);

        mRecyclerView = f(R.id.rv_thing_doing);
    }

    @Override
    protected void initUI() {
        DisplayUtil.expandLayoutToFullscreenAboveLollipop(this);

        setBackground();
        setRecyclerView();

        mTimelyView.postDelayed(new Runnable() {
            @Override
            public void run() {
                mTimelyView.animate(9, 6).setDuration(3000).start();
            }
        }, 500);
    }

    private void setBackground() {
        WallpaperManager wm = WallpaperManager.getInstance(getApplicationContext());
        Drawable wallpaper = wm.getDrawable();
        if (wallpaper != null) {
            mIvBg.setImageDrawable(wallpaper);
        }
        mIvBg.postDelayed(new Runnable() {
            @Override
            public void run() {
                Blurry.with(getApplicationContext())
                        .radius(16)
                        .sampling(8)
                        .color(Color.parseColor("#16000000"))
                        .animate(1600)
                        .onto((ViewGroup) f(R.id.fl_bg_cover_doing));
            }
        }, 160);
    }

    private void setRecyclerView() {
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
                return mRvWidth;
            }

            @Override
            protected List<Thing> getThings() {
                return singleThing;
            }

            @Override
            public void onBindViewHolder(BaseThingViewHolder holder, int position) {
                super.onBindViewHolder(holder, position);
                holder.rlReminder.setVisibility(View.GONE);
                holder.rlHabit.setVisibility(View.GONE);
                holder.vReminderSeparator.setVisibility(View.GONE);
                holder.vHabitSeparator1.setVisibility(View.GONE);
            }
        };
        ViewGroup.LayoutParams vlp = mRecyclerView.getLayoutParams();
        vlp.width = mRvWidth;
        mRecyclerView.requestLayout();
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mRecyclerView.setAdapter(adapter);
    }

    @Override
    protected void setActionbar() {

    }

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
    }

}
