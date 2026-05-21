package com.ywwynm.everythingdone.views

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.widget.ImageView
import android.widget.TextView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.SettingsActivity
import com.ywwynm.everythingdone.model.ThingsCounts
import com.ywwynm.everythingdone.permission.PermissionUtil
import com.ywwynm.everythingdone.utils.BitmapUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.LocaleUtil

import java.io.File

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
        val D: String = SettingsActivity.DEFAULT_DRAWER_HEADER

        val sp: SharedPreferences = mApp.getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, Context.MODE_PRIVATE)!!
        val header: String = sp.getString(Def.Meta.KEY_DRAWER_HEADER, D)!!
        if (D == header) {
            mIvHeader.setImageResource(R.drawable.drawer_header)
        } else {
            if (!File(header).exists()) {
                mIvHeader.setImageResource(R.drawable.drawer_header)
                sp.edit().putString(Def.Meta.KEY_DRAWER_HEADER, D).apply()
                return
            }

            if (!PermissionUtil.hasImagePermission(mApp)) {
                // sometimes after re-installing the app by Android Studio, old data remains.
                mIvHeader.setImageResource(R.drawable.drawer_header)
                return
            }

            val width: Int = (320 * DisplayUtil.getScreenDensity(mApp)).toInt()
            val bm: Bitmap? = BitmapUtil.decodeFileWithRequiredSize(
                    header, width, width * 9 / 16)
            mIvHeader.setImageBitmap(bm)
        }
    }

    fun updateTexts() {
        when (mApp.getLimit()) {
            Def.LimitForGettingThings.ALL_UNDERWAY,
            Def.LimitForGettingThings.ALL_FINISHED,
            Def.LimitForGettingThings.ALL_DELETED ->
                mTvLocation.setText(R.string.completion_rate_all)
            Def.LimitForGettingThings.NOTE_UNDERWAY ->
                mTvLocation.setText(R.string.completion_rate_note)
            Def.LimitForGettingThings.REMINDER_UNDERWAY ->
                mTvLocation.setText(R.string.completion_rate_reminder)
            Def.LimitForGettingThings.HABIT_UNDERWAY ->
                mTvLocation.setText(R.string.completion_rate_habit)
            Def.LimitForGettingThings.GOAL_UNDERWAY ->
                mTvLocation.setText(R.string.completion_rate_goal)
            else -> { }
        }

        updateCompletionRate()
    }

    fun updateCompletionRate() {
        mTvCompletionRate.text = ThingsCounts.getInstance(mApp)!!.getCompletionRate(mApp.getLimit())
    }

    companion object {
        const val TAG: String = "DrawerHeader"
    }
}
