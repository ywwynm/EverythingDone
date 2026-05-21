package com.ywwynm.everythingdone.views

import android.content.res.Configuration
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.Toolbar
import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.LocaleUtil
import kotlin.math.abs

/**
 * Created by ywwynm on 2015/7/5.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A simple class combines actionbarShadow, activity title and completion rate together
 * and provides methods to update them.
 */
open class ActivityHeader(
        app: App,
        recyclerView: RecyclerView,
        actionbarShadow: View,
        relativeLayout: RelativeLayout,
        title: TextView,
        subtitle: TextView) {

    private val mApp: App = app
    private val mScreenDensity: Float = DisplayUtil.getScreenDensity(app)

    private var shouldListenToScroll: Boolean = true

    private var headerTranslationYFactor: Float = 0f
    private var titleShrinkFactor: Float = 0f

    private var actionbarShadowAlpha: Float = 0f

    private val mActionbarShadow: View = actionbarShadow
    private val mRelativeLayout: RelativeLayout = relativeLayout
    private val mTitle: TextView = title
    private val mSubtitle: TextView = subtitle

    private val mBindingRecyclerView: RecyclerView = recyclerView

    private var mModeManager: ModeManager? = null

    init {
        computeFactors(null)
        updateSubtitle()
    }

    fun setModeManager(modeManager: ModeManager) {
        mModeManager = modeManager
    }

    fun computeFactors(actionbar: Toolbar?) {
        headerTranslationYFactor = 65f / 90
        titleShrinkFactor = -1.0f / 540 / mScreenDensity
        val isTablet: Boolean = DisplayUtil.isTablet(mApp)
        val isLandscape: Boolean = mApp.resources!!.configuration!!.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
        if (isTablet) {
            headerTranslationYFactor = 62f / 90
            titleShrinkFactor = -1.0f / 540 / mScreenDensity
        } else if (isLandscape) {
            headerTranslationYFactor = 68f / 90
            titleShrinkFactor = -1.0f / 360 / mScreenDensity
        }

        if (actionbar != null) {
            val actionbarHeight: Int = actionbar.height
            if (near(actionbarHeight, (mScreenDensity * 48).toInt())) {
                headerTranslationYFactor = 68f / 90
                titleShrinkFactor = -1.0f / 360 / mScreenDensity
            } else if (near(actionbarHeight, (mScreenDensity * 56).toInt())) {
                headerTranslationYFactor = 65f / 90
                titleShrinkFactor = -1.0f / 540 / mScreenDensity
            } else if (near(actionbarHeight, (mScreenDensity * 64).toInt())) {
                headerTranslationYFactor = 62f / 90
                titleShrinkFactor = -1.0f / 540 / mScreenDensity
            }
        }
    }

    private fun near(h1: Int, h2: Int): Boolean {
        return abs(h1 - h2) < 8
    }

    fun updateAll(firstVisibleItemPosition: Int, anim: Boolean) {
        if (!shouldListenToScroll) {
            return
        }

        var actionbarShadowAlphaAfter = 0f
        var scrollY: Int = -mBindingRecyclerView.getChildAt(0)!!.top
        val titleAndShadowScrollY: Int = (mScreenDensity * 90).toInt()
        val shadowAppearCompletelyScrollY: Int = (mScreenDensity * 102).toInt()

        /*
         * Sometimes, especially when an item is removed or moved,
         * RecyclerView will give a wrong scroll distance much lower than 0,
         * use this if block to correct it.
         * Otherwise, since header's height is 102dp, all actions of this class
         * should only happen between 0 and this distance.
         */
        if (scrollY >= mScreenDensity * 102 || scrollY < 0) {
            scrollY = 0
        }

        if (firstVisibleItemPosition == 0) {
            if (scrollY <= titleAndShadowScrollY) {
                updateHeader(scrollY, anim)
            } else if (scrollY <= shadowAppearCompletelyScrollY) {
                actionbarShadowAlphaAfter = 1f / 12 / mScreenDensity * scrollY - 90f / 12
                updateHeader((90 * mScreenDensity).toInt(), anim)
            } else {
                updateHeader((90 * mScreenDensity).toInt(), anim)
                actionbarShadowAlphaAfter = 1.0f
            }
        } else {
            updateHeader((90 * mScreenDensity).toInt(), anim)
            actionbarShadowAlphaAfter = 1.0f
        }
        if (mModeManager!!.getCurrentMode() != ModeManager.SELECTING) {
            if (anim) {
                mActionbarShadow.animate()!!.alpha(actionbarShadowAlphaAfter).withLayer().setDuration(160)
            } else {
                mActionbarShadow.setAlpha(actionbarShadowAlphaAfter)
            }
        } else {
            actionbarShadowAlpha = actionbarShadowAlphaAfter
        }
    }

    fun updateText() {
        when (mApp.getLimit()) {
            Def.LimitForGettingThings.NOTE_UNDERWAY ->
                mTitle.setText(R.string.note)
            Def.LimitForGettingThings.REMINDER_UNDERWAY ->
                mTitle.setText(R.string.reminder)
            Def.LimitForGettingThings.HABIT_UNDERWAY ->
                mTitle.setText(R.string.habit)
            Def.LimitForGettingThings.GOAL_UNDERWAY ->
                mTitle.setText(R.string.goal)
            Def.LimitForGettingThings.ALL_FINISHED ->
                mTitle.setText(R.string.finished)
            Def.LimitForGettingThings.ALL_DELETED ->
                mTitle.setText(R.string.deleted)
            else ->
                mTitle.setText(R.string.underway)
        }
        updateSubtitle()
    }

    private fun updateSubtitle() {
        val thingsCount: Int = ThingManager.getInstance(mApp)!!.getThingsCounts()!!
                .getThingsCountForActivityHeader(mApp.getLimit())
        var subtitle: String = if (thingsCount == 0) mApp.getString(R.string.empty)!! else
                "" + thingsCount + " " + mApp.getString(R.string.a_thing)
        if (thingsCount > 1 && !LocaleUtil.isChinese(mApp)) {
            subtitle += "s"
        }
        mSubtitle.text = subtitle
    }

    fun hideActionbarShadow() {
        actionbarShadowAlpha = mActionbarShadow.alpha
        mActionbarShadow.animate()!!.alpha(0f).withLayer().setDuration(160)
    }

    fun showActionbarShadow() {
        showActionbarShadow(actionbarShadowAlpha)
    }

    fun showActionbarShadow(alpha: Float) {
        mActionbarShadow.animate()!!.alpha(alpha).withLayer()
    }

    fun hideTitles() {
        mRelativeLayout.visibility = View.INVISIBLE
    }

    fun reset(anim: Boolean) {
        mRelativeLayout.visibility = View.VISIBLE
        if (anim) {
            mRelativeLayout.animate()!!.translationY(0f)
            mTitle.animate()!!.scaleX(1.0f)
            mTitle.animate()!!.scaleY(1.0f)
            mSubtitle.animate()!!.alpha(1.0f)
            mActionbarShadow.animate()!!.alpha(0f)
        } else {
            mRelativeLayout.translationY = 0f
            mTitle.scaleX = 1.0f
            mTitle.scaleY = 1.0f
            mSubtitle.setAlpha(1.0f)
            mActionbarShadow.setAlpha(0f)
        }
    }

    fun setShouldListenToScroll(shouldListenToScroll: Boolean) {
        this.shouldListenToScroll = shouldListenToScroll
    }

    private fun updateHeader(scrollY: Int, anim: Boolean) {
        val scale: Float = titleShrinkFactor * scrollY + 1
        mTitle.pivotX = 1f
        mTitle.pivotY = 1f

        if (anim) {
            mRelativeLayout.animate()!!.translationY(-headerTranslationYFactor * scrollY)

            /*
             * Changing scaleX and scaleY of title is better than changing its textSize.
             * pivotX and pivotY should be remained as 1 so that title's location won't
             * be changed incorrectly.
             */

            mTitle.animate()!!.scaleX(scale).setDuration(160)
            mTitle.animate()!!.scaleY(scale).setDuration(160)
            mSubtitle.animate()!!.alpha(-1.0f / mScreenDensity / 90 * scrollY + 1).withLayer().setDuration(160)
        } else {
            mRelativeLayout.translationY = (-headerTranslationYFactor * scrollY).toInt().toFloat()
            mTitle.scaleX = scale
            mTitle.scaleY = scale
            mSubtitle.setAlpha(-1.0f / mScreenDensity / 90 * scrollY + 1)
        }
    }

    companion object {
        const val TAG: String = "ActivityHeader"
    }
}
