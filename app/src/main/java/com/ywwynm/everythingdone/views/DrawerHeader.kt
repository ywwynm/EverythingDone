package com.ywwynm.everythingdone.views

import android.widget.ImageView
import android.widget.TextView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.DrawerHeaderHelper
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.ThingsCounts
import com.ywwynm.everythingdone.model.ThingWidgetInfo
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.LocaleUtil

/**
 * Created by ywwynm on 2015/8/1.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A class to provide operations for DrawerHeader
 */
open class DrawerHeader(
        app: App,
        ivHeader: ImageView,
        tvLocation: TextView,
        tvCompletionRate: TextView) {

    private val mApp: App = app

    private val mIvHeader: ImageView = ivHeader
    private val mTvLocation: TextView = tvLocation
    private val mTvCompletionRate: TextView = tvCompletionRate

    init {
        updateDrawerHeader()

        if (LocaleUtil.isChinese(mApp)) {
            mTvLocation.textSize = 16f
            mTvCompletionRate.textSize = 28f
        } else {
            val width: Int = DisplayUtil.getScreenSize(mApp).x
            if (width <= 720) {
                mTvLocation.textSize = 12f
            } else if (width <= 1080) {
                mTvLocation.textSize = 13f
            } else {
                mTvLocation.textSize = 14f
            }
            mTvCompletionRate.textSize = 24f
        }
    }

    fun updateDrawerHeader() {
        val width: Int = (320 * DisplayUtil.getScreenDensity(mApp)).toInt()
        when (val header = DrawerHeaderHelper.resolve(mApp)) {
            is DrawerHeaderHelper.Header.Custom ->
                DrawerHeaderHelper.loadCustomInto(mIvHeader, header.path, header.crop, width)
            DrawerHeaderHelper.Header.Default ->
                mIvHeader.setImageResource(R.drawable.drawer_header)
        }
    }

    fun updateTexts() {
        mTvLocation.setText(R.string.completion_rate_things)
        updateCompletionRate()
    }

    fun updateCompletionRate() {
        val manager = ThingManager.getInstance(mApp)
        mTvCompletionRate.text = ThingsCounts.getInstance(mApp)!!.getCompletionRate(
            mApp.getStatus(),
            manager?.getActiveTypeFilterMask() ?: ThingWidgetInfo.TYPE_FILTER_ALL
        )
        BackgroundUtil.applyTextBackground(mTvCompletionRate, App.defaultAccentBackground)
    }

    companion object {
        const val TAG: String = "DrawerHeader"
    }
}
