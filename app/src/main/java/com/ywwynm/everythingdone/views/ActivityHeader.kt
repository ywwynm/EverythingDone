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

    private var collapsedHeaderTranslationY: Float = 0f
    private var titleShrinkFactor: Float = 0f
    private var mActionbar: Toolbar? = null

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
        if (actionbar != null) {
            mActionbar = actionbar
        }

        val toolbar: Toolbar? = actionbar ?: mActionbar
        var headerTranslationYFactor = 65f / 90
        var collapsedTitleScale = 5f / 6f
        val isTablet: Boolean = DisplayUtil.isTablet(mApp)
        val isLandscape: Boolean = mApp.resources!!.configuration!!.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
        if (isTablet) {
            headerTranslationYFactor = 62f / 90
            collapsedTitleScale = 5f / 6f
        } else if (isLandscape) {
            headerTranslationYFactor = 68f / 90
            collapsedTitleScale = 0.75f
        }

        if (toolbar != null) {
            val actionbarHeight: Int = toolbar.height
            if (nearToolbarHeight(actionbarHeight, 48)) {
                headerTranslationYFactor = 68f / 90
                collapsedTitleScale = 0.75f
            } else if (nearToolbarHeight(actionbarHeight, 56)) {
                headerTranslationYFactor = 65f / 90
                collapsedTitleScale = 5f / 6f
            } else if (nearToolbarHeight(actionbarHeight, 64)) {
                headerTranslationYFactor = 62f / 90
                collapsedTitleScale = 5f / 6f
            }
        }

        val titleCollapseScrollY = getTitleCollapseScrollY()
        titleShrinkFactor = (collapsedTitleScale - 1f) / titleCollapseScrollY
        collapsedHeaderTranslationY = computeCollapsedHeaderTranslationY(
            toolbar, collapsedTitleScale, headerTranslationYFactor
        )
    }

    private fun nearToolbarHeight(height: Int, dp: Int): Boolean {
        return kotlin.math.abs(height - (mScreenDensity * dp).toInt()) < 8
    }

    private fun getTitleCollapseScrollY(): Float {
        return mScreenDensity * 90
    }

    private fun computeCollapsedHeaderTranslationY(
            actionbar: Toolbar?,
            collapsedTitleScale: Float,
            fallbackFactor: Float
    ): Float {
        val fallback = -fallbackFactor * getTitleCollapseScrollY()
        if (actionbar == null || actionbar.height == 0 || mRelativeLayout.height == 0
                || mTitle.height == 0) {
            return fallback
        }

        mTitle.pivotY = TITLE_SCALE_PIVOT
        val actionbarCenterY = actionbar.top + actionbar.height / 2f
        val titleCenterInHeader = mTitle.top + mTitle.pivotY +
                (mTitle.height / 2f - mTitle.pivotY) * collapsedTitleScale
        val titleCenterBeforeTranslation = mRelativeLayout.top + titleCenterInHeader
        return actionbarCenterY - titleCenterBeforeTranslation
    }

    fun updateAll(firstVisibleItemPosition: Int, anim: Boolean) {
        if (!shouldListenToScroll) {
            return
        }

        var actionbarShadowAlphaAfter = 0f
        var scrollY: Int = -mBindingRecyclerView.getChildAt(0)!!.top
        val titleAndShadowScrollY: Int = getTitleCollapseScrollY().toInt()
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
        mRelativeLayout.post { computeFactors(mActionbar) }
    }

    private fun updateSubtitle() {
        val thingsCount: Int = ThingManager.getInstance(mApp)!!.getThingsCounts()!!
                .getThingsCountForActivityHeader(mApp.getLimit())
        var subtitle: String = if (thingsCount == 0) mApp.getString(R.string.empty) else
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
        val progress: Float = scrollY / getTitleCollapseScrollY()
        val translationY = collapsedHeaderTranslationY * progress
        mTitle.pivotX = TITLE_SCALE_PIVOT
        mTitle.pivotY = TITLE_SCALE_PIVOT

        if (anim) {
            mRelativeLayout.animate()!!.translationY(translationY)

            /*
             * Changing scaleX and scaleY of title is better than changing its textSize.
             * pivotX and pivotY should be remained as 1 so that title's location won't
             * be changed incorrectly.
             */

            mTitle.animate()!!.scaleX(scale).setDuration(160)
            mTitle.animate()!!.scaleY(scale).setDuration(160)
            mSubtitle.animate()!!.alpha(1f - progress).withLayer().setDuration(160)
        } else {
            mRelativeLayout.translationY = translationY
            mTitle.scaleX = scale
            mTitle.scaleY = scale
            mSubtitle.setAlpha(1f - progress)
        }
    }

    companion object {
        const val TAG: String = "ActivityHeader"
        private const val TITLE_SCALE_PIVOT = 1f
    }
}
