package com.ywwynm.everythingdone.appwidgets.single;

import android.Manifest;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.CardView;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.support.v7.widget.Toolbar;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.SeekBar;

import com.bumptech.glide.Glide;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.EverythingDoneBaseActivity;
import com.ywwynm.everythingdone.adapters.BaseThingsAdapter;
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper;
import com.ywwynm.everythingdone.database.AppWidgetDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.managers.ModeManager;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.model.ThingWidgetInfo;
import com.ywwynm.everythingdone.permission.PermissionUtil;
import com.ywwynm.everythingdone.permission.SimplePermissionCallback;
import com.ywwynm.everythingdone.utils.DeviceUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.EdgeEffectUtil;

import java.util.ArrayList;
import java.util.List;

public class BaseThingWidgetConfiguration extends EverythingDoneBaseActivity {

    protected Class getSenderClass() {
        return BaseThingWidget.class;
    }

    private Toolbar      mActionBar;
    private RecyclerView mRecyclerView;

    private ThingsAdapter mAdapter;
    private List<Thing> mThings;
    private StaggeredGridLayoutManager mStaggeredGridLayoutManager;

    private int mSpanCount;

    private int mAppWidgetId;

    private FrameLayout mFlPreviewAndConfig;
    private int mWidgetAlpha = 100;

    @Override
    protected int getLayoutResource() {
        return R.layout.activity_thing_widget_configuration;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        mSpanCount = DisplayUtil.isTablet(this) ? 3 : 2;
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mSpanCount++;
        }
        mStaggeredGridLayoutManager.setSpanCount(mSpanCount);

        if (mThings.size() > 1) {
            mRecyclerView.scrollToPosition(0);
        }
        mAdapter.notifyDataSetChanged();
    }

    @Override
    protected void initMembers() {
        Intent intent = getIntent();
        mAppWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
        setResult(RESULT_CANCELED, intent);

        if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        mSpanCount = DisplayUtil.isTablet(this) ? 3 : 2;
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mSpanCount++;
        }

        mThings = ThingDAO.getInstance(this)
                .getThingsForDisplay(Def.LimitForGettingThings.ALL_UNDERWAY);
        mThings.remove(0); // no header
        mAdapter = new ThingsAdapter();
    }

    @Override
    protected void findViews() {
        mActionBar    = f(R.id.actionbar);
        mRecyclerView = f(R.id.rv_things);

        mFlPreviewAndConfig = f(R.id.fl_app_widget_preview_and_ui_config);
    }

    @Override
    protected void initUI() {
        DisplayUtil.expandLayoutAboveLollipop(this);
        DisplayUtil.expandStatusBarAboveKitkat(findViewById(R.id.view_status_bar));
        DisplayUtil.darkStatusBar(this);

        if (!PermissionUtil.hasStoragePermission(this)
                && PermissionUtil.shouldRequestPermissionWhenLoadingThings(mThings)) {
            doWithPermissionChecked(new SimplePermissionCallback(this) {
                @Override
                public void onGranted() {
                    initRecyclerView();
                }

                @Override
                public void onDenied() {
                    super.onDenied();
                    finish();
                }
            }, Def.Communication.REQUEST_PERMISSION_LOAD_THINGS_2,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE);
        } else {
            initRecyclerView();
        }
    }

    private void initRecyclerView() {
        mStaggeredGridLayoutManager = new StaggeredGridLayoutManager(
                mSpanCount, StaggeredGridLayoutManager.VERTICAL);
        mRecyclerView.setLayoutManager(mStaggeredGridLayoutManager);
        mRecyclerView.setAdapter(mAdapter);
    }

    @Override
    protected void setActionbar() {
        setSupportActionBar(mActionBar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        mActionBar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    @Override
    protected void setEvents() {
        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {

            final int edgeColor = EdgeEffectUtil.getEdgeColorDark();
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                EdgeEffectUtil.forRecyclerView(recyclerView, edgeColor);
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    Glide.with(BaseThingWidgetConfiguration.this).resumeRequests();
                } else { // dragging or settling
                    Glide.with(BaseThingWidgetConfiguration.this).pauseRequests();
                }
            }
        });
    }

    private void previewAppWidget(final Thing thing) {
        mFlPreviewAndConfig.setVisibility(View.VISIBLE);
        mActionBar.setVisibility(View.GONE);
        mRecyclerView.setVisibility(View.GONE);
        DisplayUtil.cancelDarkStatusBar(this);

        ImageView ivBackground = f(R.id.iv_app_widget_preview_background);
        WallpaperManager wm = WallpaperManager.getInstance(getApplicationContext());
        Drawable wallpaper = wm.getDrawable();
        if (wallpaper != null) {
            ivBackground.setImageDrawable(wallpaper);
        }

        final BaseThingsAdapter adapter = new BaseThingsAdapter(this) {

            @Override
            protected int getCurrentMode() {
                return ModeManager.NORMAL;
            }

            @Override
            protected List<Thing> getThings() {
                List<Thing> things = new ArrayList<>(1);
                things.add(new Thing(thing));
                return things;
            }

            @Override
            public void onBindViewHolder(BaseThingViewHolder holder, int position) {
                super.onBindViewHolder(holder, position);
                CardView cv = (CardView) holder.itemView;
                if (cv == null) return;
                cv.setRadius(0);
                cv.setCardElevation(0);
                int alphaColor = DisplayUtil.getTransparentColor(
                        thing.getColor(), (int) (mWidgetAlpha / 100f * 255));
                cv.setCardBackgroundColor(alphaColor);
                //cv.setAlpha(mWidgetAlpha / 100f);
            }
        };
        final RecyclerView rvPreview = f(R.id.rv_app_widget_preview);
        FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) rvPreview.getLayoutParams();
        flp.width = DisplayUtil.getThingCardWidth(this);
        rvPreview.requestLayout();
        rvPreview.setAdapter(adapter);
        rvPreview.setLayoutManager(new LinearLayoutManager(this));
        rvPreview.setOnTouchListener(new View.OnTouchListener() {
            private int mDx, mDy;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                final int rawX = (int) event.getRawX();
                final int rawY = (int) event.getRawY();
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN: {
                        FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams)
                                rvPreview.getLayoutParams();
                        mDx = rawX - flp.leftMargin;
                        mDy = rawY - flp.topMargin;
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams)
                                rvPreview.getLayoutParams();
                        flp.leftMargin = rawX - mDx;
                        flp.topMargin  = rawY - mDy;
                        rvPreview.requestLayout();
                        return true;
                    }
                }
                return false;
            }
        });

        SeekBar sbAlpha = f(R.id.sb_app_widget_alpha);
        sbAlpha.setMax(100);
        sbAlpha.setProgress(100);
        DisplayUtil.setSeekBarColor(sbAlpha, ContextCompat.getColor(this, R.color.app_accent));
        sbAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    mWidgetAlpha = progress;
                    adapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        Button btFinish = f(R.id.bt_finish_set_alpha_app_widget);
        DisplayUtil.setButtonColor(btFinish, Color.WHITE);
        btFinish.setTextColor(thing.getColor());
        btFinish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                endSelectThing(thing);
            }
        });
    }

    private void endPreviewAppWidget() {
        mFlPreviewAndConfig.setVisibility(View.GONE);
        mActionBar.setVisibility(View.VISIBLE);
        mRecyclerView.setVisibility(View.VISIBLE);
        DisplayUtil.darkStatusBar(this);
    }

    private void endSelectThing(Thing thing) {
        Class clazz = getSenderClass();
        AppWidgetDAO.getInstance(this).insert(mAppWidgetId, thing.getId(),
                AppWidgetHelper.getSizeByProviderClass(clazz), mWidgetAlpha,
                ThingWidgetInfo.STYLE_NORMAL);

        RemoteViews views = AppWidgetHelper.createRemoteViewsForSingleThing(
                this, thing, -1, mAppWidgetId, clazz);
        AppWidgetManager.getInstance(this).updateAppWidget(mAppWidgetId, views);

        Intent intent = new Intent();
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
        setResult(RESULT_OK, intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (mFlPreviewAndConfig.getVisibility() == View.VISIBLE) {
            endPreviewAppWidget();
        } else {
            super.onBackPressed();
        }
    }

    class ThingsAdapter extends BaseThingsAdapter {

        ThingsAdapter() {
            super(BaseThingWidgetConfiguration.this);
        }

        @Override
        protected List<Thing> getThings() {
            return mThings;
        }

        @Override
        protected int getCurrentMode() {
            return ModeManager.NORMAL;
        }

        @Override
        public BaseThingViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(mInflater.inflate(R.layout.card_thing, parent, false));
        }

        @Override
        public void onBindViewHolder(BaseThingViewHolder holder, int position) {
            int m = (int) (mScreenDensity * 4);
            if (DeviceUtil.hasLollipopApi()) {
                m = (int) (mScreenDensity * 6);
            }

            StaggeredGridLayoutManager.LayoutParams lp =
                    (StaggeredGridLayoutManager.LayoutParams) holder.itemView.getLayoutParams();
            lp.setMargins(m, m, m, m);

            super.onBindViewHolder(holder, position);
        }

        class Holder extends BaseThingViewHolder {

            Holder(View item) {
                super(item);

                cv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        //endSelectThing(mThings.get(getAdapterPosition()));
                        previewAppWidget(mThings.get(getAdapterPosition()));
                    }
                });
            }
        }

    }
}
