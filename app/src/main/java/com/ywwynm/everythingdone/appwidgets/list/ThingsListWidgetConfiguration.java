package com.ywwynm.everythingdone.appwidgets.list;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.view.Window;
import android.widget.RemoteViews;
import android.widget.TextView;

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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_things_list_widget_configuration);

        TextView tvTitle = (TextView) findViewById(R.id.tv_title_things_list_widget_configuration);
        if (tvTitle != null) {
            tvTitle.setTextColor(DisplayUtil.getRandomColor(this));
        }

        int appWidgetId = getIntent().getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        Intent intent = new Intent();
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        setResult(RESULT_CANCELED, intent);

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
        }
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
        Intent intent = getIntent();
        int appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        AppWidgetDAO appWidgetDAO = AppWidgetDAO.getInstance(this);
        appWidgetDAO.insert(appWidgetId, -limit - 1, ThingWidgetInfo.SIZE_MIDDLE);

        RemoteViews views = AppWidgetHelper.createRemoteViewsForThingsList(
                this, limit, appWidgetId);
        AppWidgetManager.getInstance(this).updateAppWidget(appWidgetId, views);

        intent = new Intent();
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        setResult(RESULT_OK, intent);
        finish();
    }
}
