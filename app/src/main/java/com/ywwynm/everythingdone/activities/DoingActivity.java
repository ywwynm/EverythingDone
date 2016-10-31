package com.ywwynm.everythingdone.activities;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DisplayUtil;

import jp.wasabeef.blurry.Blurry;

/**
 * Created by qiizhang on 2016/10/31.
 * An Activity showing the thing that are currently doing
 */
public class DoingActivity extends EverythingDoneBaseActivity {

    public static final String TAG = "DoingActivity";

    public static Intent getOpenIntent(Context context, Thing thing, int time, int timeType) {
        Intent intent = new Intent(context, DoingActivity.class);
        intent.putExtra(Def.Communication.KEY_THING,     thing);
        intent.putExtra(Def.Communication.KEY_TIME,      time);
        intent.putExtra(Def.Communication.KEY_TIME_TYPE, timeType);
        return intent;
    }

    private Thing mThing;
    private int   mTime;
    private int   mTimeType;

    private ImageView mIvBg;

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
        final Intent intent = getIntent();
        mThing    = intent.getParcelableExtra(Def.Communication.KEY_THING);
        mTime     = intent.getIntExtra(Def.Communication.KEY_TIME, -1);
        mTimeType = intent.getIntExtra(Def.Communication.KEY_TIME_TYPE, -1);
    }

    @Override
    protected void findViews() {
        mIvBg = f(R.id.iv_bg_doing);
    }

    @Override
    protected void initUI() {
        DisplayUtil.expandLayoutToFullscreenAboveLollipop(this);

        setBackground();
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
        }, 360);
    }

    @Override
    protected void setActionbar() {

    }

    @Override
    protected void setEvents() {

    }

}
