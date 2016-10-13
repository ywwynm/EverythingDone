package com.ywwynm.everythingdone.appwidgets.list;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.AppCompatCheckBox;
import android.view.View;
import android.view.Window;
import android.widget.RemoteViews;
import android.widget.SeekBar;
import android.widget.TextView;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper;
import com.ywwynm.everythingdone.database.AppWidgetDAO;
import com.ywwynm.everythingdone.model.ThingWidgetInfo;
import com.ywwynm.everythingdone.utils.DisplayUtil;

/**
 * Created by qiizhang on 2016/8/10.
 * Configuration Activity for things list widget
 */
public class ThingsListWidgetConfiguration extends AppCompatActivity {

    public static final String TAG = "ThingsListWidgetConfiguration";

    private SeekBar mSbAlpha;
    private AppCompatCheckBox mCbSimpleView;

    private int mAppWidgetId;

    private boolean mIsSetting = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_things_list_widget_configuration);

        int color = DisplayUtil.getRandomColor(getApplicationContext());
        TextView tvTitle = (TextView) findViewById(R.id.tv_title_things_list_widget_configuration);
        if (tvTitle != null) {
            tvTitle.setTextColor(color);
        }

        mSbAlpha = (SeekBar) findViewById(R.id.sb_app_widget_alpha);
        mSbAlpha.setMax(100);
        DisplayUtil.setSeekBarColor(mSbAlpha, color);

        mCbSimpleView = (AppCompatCheckBox) findViewById(R.id.cb_simple_view);
        DisplayUtil.setCheckBoxColor(mCbSimpleView, color);

        findViewById(R.id.rl_simple_view_as_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCbSimpleView.toggle();
            }
        });

        Intent intent = getIntent();
        mAppWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        int appWidgetId2 = intent.getIntExtra(Def.Communication.KEY_WIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        intent = new Intent();
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
        setResult(RESULT_CANCELED, intent);

        if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            mAppWidgetId = appWidgetId2;
            if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                finish();
            } else {
                mIsSetting = true;
            }
        }

        AppWidgetDAO dao = AppWidgetDAO.getInstance(getApplicationContext());
        ThingWidgetInfo info = dao.getThingWidgetInfoById(mAppWidgetId);
        int alpha = 100;
        if (info != null) {
            alpha = info.getAlpha();
            mCbSimpleView.setChecked(info.getStyle() == ThingWidgetInfo.STYLE_SIMPLE);
        }
        mSbAlpha.setProgress(alpha);
    }

    public void onClick(View view) {
        int limit = 0;
        switch (view.getId()) {
            case R.id.tv_underway_widget_config:
                limit = 0;
                break;
            case R.id.tv_note_widget_config:
                limit = 1;
                break;
            case R.id.tv_reminder_widget_config:
                limit = 2;
                break;
            case R.id.tv_habit_widget_config:
                limit = 3;
                break;
            case R.id.tv_goal_widget_config:
                limit = 4;
                break;
            default:break;
        }
        App app = App.getApp();
        AppWidgetDAO appWidgetDAO = AppWidgetDAO.getInstance(app);
        if (mIsSetting) {
            appWidgetDAO.delete(mAppWidgetId);
        }

        @ThingWidgetInfo.Style int style = ThingWidgetInfo.STYLE_NORMAL;
        if (mCbSimpleView.isChecked()) {
            style = ThingWidgetInfo.STYLE_SIMPLE;
        }
        appWidgetDAO.insert(mAppWidgetId, -limit - 1, ThingWidgetInfo.SIZE_MIDDLE,
                mSbAlpha.getProgress(), style);

        if (!mIsSetting) {
            RemoteViews views = AppWidgetHelper.createRemoteViewsForThingsList(
                    this, limit, mAppWidgetId);
            AppWidgetManager.getInstance(app).updateAppWidget(mAppWidgetId, views);
            Intent intent = new Intent();
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
            setResult(RESULT_OK, intent);
        } else {
            AppWidgetHelper.updateThingsListAppWidget(app, mAppWidgetId);
        }
        finish();
    }
}
