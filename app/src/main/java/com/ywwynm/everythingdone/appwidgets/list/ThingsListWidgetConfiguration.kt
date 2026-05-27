package com.ywwynm.everythingdone.appwidgets.list

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import android.widget.RemoteViews
import android.widget.SeekBar
import android.widget.TextView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.BaseViewHolder
import com.ywwynm.everythingdone.adapters.RadioChooserAdapter
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.database.AppWidgetDAO
import com.ywwynm.everythingdone.model.ThingWidgetInfo
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.LocaleUtil

import java.util.ArrayList
import kotlin.math.abs

/**
 * Created by qiizhang on 2016/8/10.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Configuration Activity for things list widget
 */
open class ThingsListWidgetConfiguration : AppCompatActivity() {

    private var mAdapter: RadioChooserAdapter? = null

    private var mSbAlpha: SeekBar? = null
    private var mCbAlphaHeader: AppCompatCheckBox? = null
    private var mCbSimpleView: AppCompatCheckBox? = null

    private var mAppWidgetId: Int = 0

    private var mIsSetting: Boolean = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleUtil.attachBaseContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_things_list_widget_configuration)

        val color: Int = DisplayUtil.getRandomColor(applicationContext)
        val tvTitle: TextView? = findViewById(R.id.tv_title_things_list_widget_configuration)
        tvTitle?.setTextColor(color)
        val tvConfirm: TextView? = findViewById(R.id.tv_confirm_as_bt_things_list_config)
        tvConfirm?.setTextColor(color)

        val items: MutableList<String?> = ArrayList(5)
        items.add(getString(R.string.all))
        items.add(getString(R.string.note))
        items.add(getString(R.string.reminder))
        items.add(getString(R.string.habit))
        items.add(getString(R.string.goal))

        val p: Int = (DisplayUtil.getScreenDensity(this) * 8).toInt()
        val rv: RecyclerView = findViewById(R.id.rv_types_list_things_list_widget_config)
        mAdapter = object : RadioChooserAdapter(this@ThingsListWidgetConfiguration, items, color) {
            override fun onBindViewHolder(viewHolder: BaseViewHolder, position: Int) {
                super.onBindViewHolder(viewHolder, position)
                viewHolder.itemView.setPadding(p, 0, p, 0)
            }
        }
        mAdapter!!.pick(0)
        rv.setAdapter(mAdapter)
        rv.setLayoutManager(LinearLayoutManager(this))

        mSbAlpha = findViewById(R.id.sb_app_widget_alpha)!!
        mSbAlpha!!.setMax(100)
        DisplayUtil.setSeekBarColor(mSbAlpha, color)

        mCbSimpleView = findViewById(R.id.cb_simple_view)!!
        DisplayUtil.setCheckBoxColor(mCbSimpleView, color)
        findViewById<View>(R.id.rl_simple_view_as_bt).setOnClickListener { _ ->
            mCbSimpleView!!.toggle()
        }

        mCbAlphaHeader = findViewById(R.id.cb_alpha_header)!!
        DisplayUtil.setCheckBoxColor(mCbAlphaHeader, color)
        findViewById<View>(R.id.rl_alpha_header_as_bt).setOnClickListener { _ ->
            mCbAlphaHeader!!.toggle()
        }

        var intent: Intent = getIntent()
        mAppWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val appWidgetId2: Int = intent.getIntExtra(Def.Communication.KEY_WIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID)
        intent = Intent()
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId)
        setResult(RESULT_CANCELED, intent)

        if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            mAppWidgetId = appWidgetId2
            if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                finish()
            } else {
                mIsSetting = true
            }
        }

        val dao: AppWidgetDAO = AppWidgetDAO.getInstance(applicationContext)!!
        val info: ThingWidgetInfo? = dao.getThingWidgetInfoById(mAppWidgetId)
        var alpha = 100
        if (info != null) {
            alpha = info.alpha
            val limit: Int = (-info.thingId - 1).toInt()
            if (limit >= 0 && limit < items.size) {
                mAdapter!!.pick(limit)
            }
            mCbSimpleView!!.setChecked(info.style == ThingWidgetInfo.STYLE_SIMPLE)
            mCbAlphaHeader!!.setChecked(alpha < 0)
        }
        if (alpha == ThingWidgetInfo.HEADER_ALPHA_0) {
            mSbAlpha!!.progress = 0
        } else {
            mSbAlpha!!.progress = abs(alpha)
        }
    }

    open fun onConfirmClicked(view: View?) {
        val limit: Int = mAdapter!!.getPickedPosition()
        val app: App = App.getApp()!!
        val appWidgetDAO: AppWidgetDAO = AppWidgetDAO.getInstance(app)!!
        if (mIsSetting) {
            appWidgetDAO.delete(mAppWidgetId)
        }

        @ThingWidgetInfo.Style var style: Int = ThingWidgetInfo.STYLE_NORMAL
        if (mCbSimpleView!!.isChecked) {
            style = ThingWidgetInfo.STYLE_SIMPLE
        }
        var alpha: Int = mSbAlpha!!.progress
        if (mCbAlphaHeader!!.isChecked) {
            alpha = if (alpha != 0) {
                -alpha
            } else {
                ThingWidgetInfo.HEADER_ALPHA_0
            }
        }
        appWidgetDAO.insert(mAppWidgetId, (-limit - 1).toLong(), ThingWidgetInfo.SIZE_MIDDLE,
                alpha, style)

        if (!mIsSetting) {
            val views: RemoteViews = AppWidgetHelper.createRemoteViewsForThingsList(
                    this, limit, mAppWidgetId)
            AppWidgetManager.getInstance(app).updateAppWidget(mAppWidgetId, views)
            val intent = Intent()
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId)
            setResult(RESULT_OK, intent)
        } else {
            AppWidgetHelper.updateThingsListAppWidget(app, mAppWidgetId)
        }
        finish()
    }

    companion object {
        const val TAG: String = "ThingsListWidgetConfiguration"
    }
}
