package com.ywwynm.everythingdone.appwidgets.single;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.WallpaperManager;
import androidx.activity.OnBackPressedCallback;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.ActionBar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.appcompat.widget.Toolbar;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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

import java.util.Collections;
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
    private LinearLayout mLlConfig;
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

        DisplayUtil.applyBottomInsetAsMargin(mLlConfig);
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
        mLlConfig = f(R.id.ll_widget_ui_config);
    }

    @Override
    protected void initUI() {
        updateStatusBarAndBottomUi(true);
        DisplayUtil.expandLayoutToStatusBarAboveLollipop(this);
        DisplayUtil.expandStatusBarViewAboveKitkat(findViewById(R.id.view_status_bar));
        DisplayUtil.darkStatusBar(this);

        mLlConfig.setBackgroundColor(Color.parseColor("#66000000"));

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
                    PermissionUtil.getRequiredPermissionsForThings(mThings));
        } else {
            initRecyclerView();
        }
    }

    private void updateStatusBarAndBottomUi(boolean selecting) {
        final Window window = getWindow();
        FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) mLlConfig.getLayoutParams();

        if (selecting) {
            window.setStatusBarColor(ContextCompat.getColor(this, R.color.bg_statusbar_lollipop));
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            flp.bottomMargin = 0;
            flp.rightMargin = 0;
            mLlConfig.requestLayout();
        } else {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            DisplayUtil.applyBottomInsetAsMargin(mLlConfig);
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
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mFlPreviewAndConfig.getVisibility() == View.VISIBLE) {
                    endPreviewAppWidget();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

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
        try {
            WallpaperManager wm = WallpaperManager.getInstance(getApplicationContext());
            Drawable wallpaper = wm.getDrawable();
            if (wallpaper != null) {
                ivBackground.setImageDrawable(wallpaper);
            }
        } catch (SecurityException e) {
            ivBackground.setBackgroundColor(0xCC000000);
        }

        final List<Thing> singleThing = Collections.singletonList(new Thing(thing));
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
                int alpha = (int) (mWidgetAlpha / 100f * 255);
                // Phase 4.d: preview supports gradient backgrounds by re-tinting
                // both endpoints of the thing's ThingBackground with the same alpha,
                // then handing it to BackgroundUtil. PURE keeps single-int path.
                com.ywwynm.everythingdone.model.ThingBackground bg = thing.getBackground();
                int s = DisplayUtil.getTransparentColor(bg.color,    alpha);
                int e = DisplayUtil.getTransparentColor(bg.endColor, alpha);
                com.ywwynm.everythingdone.model.ThingBackground tinted =
                        bg.mode == com.ywwynm.everythingdone.model.ThingBackground.Mode.PURE
                                ? com.ywwynm.everythingdone.model.ThingBackground.pure(s)
                                : com.ywwynm.everythingdone.model.ThingBackground.gradient(s, e, bg.orientation);
                com.ywwynm.everythingdone.utils.BackgroundUtil.applyCardBackground(
                        holder.cv, tinted);
                holder.ivStickyOngoing.setImageAlpha(alpha);
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
        // Phase 8: gradient text for the "Done" label when the source thing
        // has a GRADIENT background. applyTextBackground handles both modes —
        // PURE just sets the text colour, GRADIENT installs a shader on the
        // TextPaint scoped to the rendered text width.
        com.ywwynm.everythingdone.utils.BackgroundUtil.applyTextBackground(
                btFinish, thing.getBackground());
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

        updateStatusBarAndBottomUi(true);
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

    class ThingsAdapter extends BaseThingsAdapter {

        ThingsAdapter() {
            super(BaseThingWidgetConfiguration.this);
        }

        @Override
        protected int getCurrentMode() {
            return ModeManager.NORMAL;
        }

        @Override
        protected List<Thing> getThings() {
            return mThings;
        }

        @Override
        public BaseThingViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(mInflater.inflate(R.layout.card_thing, parent, false));
        }

        @Override
        public void onBindViewHolder(BaseThingViewHolder holder, int position) {
            int m = (int) (mDensity * 6);

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
                        updateStatusBarAndBottomUi(false);
                        previewAppWidget(mThings.get(getAdapterPosition()));
                    }
                });
            }
        }

    }
}
