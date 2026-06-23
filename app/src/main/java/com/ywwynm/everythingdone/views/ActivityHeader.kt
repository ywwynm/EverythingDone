package com.ywwynm.everythingdone.views

import android.content.res.Configuration
import android.text.TextUtils
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.managers.ModeManager
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingWidgetInfo
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import kotlin.math.min

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
    private var headerCollapseProgress: Float = 0f
    private var collapsedTitleScale: Float = 5f / 6f
    private var headerTranslationYFallbackFactor: Float = 65f / 90
    private var mActionbar: Toolbar? = null

    private var actionbarShadowAlpha: Float = 0f

    private val mActionbarShadow: View = actionbarShadow
    private val mRelativeLayout: RelativeLayout = relativeLayout
    private val mTitle: TextView = title
    private val mSubtitle: TextView = subtitle

    private val mBindingRecyclerView: RecyclerView = recyclerView

    private var mModeManager: ModeManager? = null
    private var mFolderPathClickListener: FolderPathClickListener? = null
    private var mHeaderSpacerHeightListener: ((Int) -> Unit)? = null
    private var mLastHeaderSpacerHeight: Int = 0
    private var mExpandedHeaderSpacerRefreshPosted: Boolean = false
    private var mInFolderProjection: Boolean = false

    init {
        computeFactors(null)
        updateSubtitle()
    }

    fun setModeManager(modeManager: ModeManager) {
        mModeManager = modeManager
    }

    fun setFolderPathClickListener(listener: FolderPathClickListener?) {
        mFolderPathClickListener = listener
    }

    fun setHeaderSpacerHeightListener(listener: ((Int) -> Unit)?) {
        mHeaderSpacerHeightListener = listener
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

        this.collapsedTitleScale = collapsedTitleScale
        headerTranslationYFallbackFactor = headerTranslationYFactor
        collapsedHeaderTranslationY = computeCollapsedHeaderTranslationY(
            toolbar, collapsedTitleScale, headerTranslationYFactor
        )
        updateTitleLayoutForProgress(headerCollapseProgress)
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
        val titleHeight = getCollapsedTitleVisualHeight()
        val titleCenterInHeader = mTitle.top + mTitle.pivotY +
                (titleHeight / 2f - mTitle.pivotY) * collapsedTitleScale
        val titleCenterBeforeTranslation = mRelativeLayout.top + titleCenterInHeader
        return actionbarCenterY - titleCenterBeforeTranslation
    }

    private fun getCollapsedTitleVisualHeight(): Float {
        if (mTitle.lineHeight <= 0) {
            return mTitle.height.toFloat()
        }
        val lineCount = getCollapsedTitleLineCount()
        val collapsedHeight = lineCount * mTitle.lineHeight
        if (shouldUseCompactCollapsedFolderTitle()) {
            return collapsedHeight.toFloat()
        }
        return collapsedHeight.coerceAtMost(mTitle.height).toFloat()
    }

    private fun getCollapsedTitleLineCount(): Int {
        if (shouldUseCompactCollapsedFolderTitle()) {
            return COLLAPSED_FOLDER_TITLE_MAX_LINES
        }
        return if (mTitle.lineCount > 0) {
            min(mTitle.lineCount, COLLAPSED_FOLDER_TITLE_MAX_LINES)
        } else {
            1
        }
    }

    fun updateAll(firstVisibleItemPosition: Int, anim: Boolean) {
        if (!shouldListenToScroll) {
            return
        }

        var actionbarShadowAlphaAfter = 0f
        val firstChild = mBindingRecyclerView.getChildAt(0) ?: return
        var scrollY: Int = -firstChild.top
        val titleAndShadowScrollY: Int = getTitleCollapseScrollY().toInt()
        val shadowAppearCompletelyScrollY: Int = getHeaderSpacerScrollY()

        /*
         * Sometimes, especially when an item is removed or moved,
         * RecyclerView will give a wrong scroll distance much lower than 0,
         * or slightly beyond the still-visible header spacer. Clamp instead
         * of resetting so the collapsed header state stays continuous.
         */
        scrollY = scrollY.coerceIn(0, shadowAppearCompletelyScrollY)

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
        actionbarShadowAlpha = actionbarShadowAlphaAfter
        if (mModeManager!!.getCurrentMode() != ModeManager.SELECTING) {
            if (anim) {
                mActionbarShadow.animate()!!.alpha(actionbarShadowAlphaAfter).withLayer().setDuration(160)
            } else {
                mActionbarShadow.animate()!!.cancel()
                mActionbarShadow.setAlpha(actionbarShadowAlphaAfter)
            }
        }
    }

    fun updateText() {
        val rootTitle = getRootTitle()
        val manager = ThingManager.getInstance(mApp)
        val folderPath = manager?.getCurrentFolderPath() ?: emptyList()
        val currentFolder = folderPath.lastOrNull()
        mInFolderProjection = folderPath.isNotEmpty()
        headerCollapseProgress = 0f
        resetTitleTextStyle()
        // The header title shows only the current status name (at root scope) or
        // the current folder name (inside a folder). It never appends the type
        // filter text — type filtering is expressed by the Drawer capsule row.
        val folderTitle = currentFolder?.title?.ifEmpty {
            mApp.getString(R.string.default_thing_folder_name)
        }
        mTitle.text = if (currentFolder == null) {
            rootTitle
        } else {
            folderTitle ?: rootTitle
        }
        applyFolderTitleStyle(currentFolder)
        updateTitleLayoutForProgress(0f)
        mTitle.requestLayout()
        mRelativeLayout.requestLayout()
        updateSubtitle()
        mRelativeLayout.post {
            updateTitleLayoutForProgress(headerCollapseProgress)
            computeFactors(mActionbar)
            requestExpandedHeaderSpacerRefresh()
        }
    }

    private fun getRootTitle(): String {
        return when (mApp.getStatus()) {
            Def.ThingStatus.FINISHED ->
                mApp.getString(R.string.finished)
            Def.ThingStatus.DELETED ->
                mApp.getString(R.string.deleted)
            else ->
                mApp.getString(R.string.underway)
        }
    }

    private fun resetTitleTextStyle() {
        mTitle.movementMethod = null
        mTitle.isClickable = false
        mTitle.paint.isUnderlineText = false
        mTitle.paint.shader = null
        mTitle.setTextColor(ContextCompat.getColor(mApp, R.color.app_chrome_on_surface_medium))
        mTitle.ellipsize = TextUtils.TruncateAt.END
        mTitle.invalidate()
    }

    private fun applyFolderTitleStyle(folder: ThingFolder?) {
        folder ?: return
        val background = folder.getBackground() ?: ThingBackground.pure(folder.getColor())
        if (background.mode === ThingBackground.Mode.GRADIENT) {
            BackgroundUtil.applyTextBackground(mTitle, background)
        } else {
            mTitle.setTextColor(background.color)
        }
    }

    private fun updateSubtitle() {
        val manager = ThingManager.getInstance(mApp)!!
        val childCounts = manager.getVisibleChildCountsForActivityHeader()
        mSubtitle.text = getFolderChildCountText(childCounts[0], childCounts[1])
    }

    private fun getFolderChildCountText(folderCount: Int, thingCount: Int): String {
        return when {
            folderCount > 0 && thingCount > 0 -> mApp.getString(
                R.string.thing_folder_count_folders_things,
                folderCount,
                thingCount
            )
            folderCount > 0 -> mApp.getString(R.string.thing_folder_count_folders, folderCount)
            thingCount > 0 -> mApp.getString(R.string.thing_folder_count, thingCount)
            else -> mApp.getString(R.string.empty)
        }
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

    private fun cancelHeaderAnimations() {
        mRelativeLayout.animate()!!.cancel()
        mTitle.animate()!!.cancel()
        mSubtitle.animate()!!.cancel()
        mActionbarShadow.animate()!!.cancel()
    }

    fun hideTitles() {
        mRelativeLayout.visibility = View.INVISIBLE
    }

    fun reset(anim: Boolean) {
        mRelativeLayout.visibility = View.VISIBLE
        headerCollapseProgress = 0f
        updateTitleLayoutForProgress(0f)
        if (anim) {
            mRelativeLayout.animate()!!.translationY(0f)
            mTitle.animate()!!.scaleX(1.0f)
            mTitle.animate()!!.scaleY(1.0f)
            mSubtitle.animate()!!.alpha(1.0f)
            mActionbarShadow.animate()!!.alpha(0f)
        } else {
            cancelHeaderAnimations()
            mRelativeLayout.translationY = 0f
            mTitle.scaleX = 1.0f
            mTitle.scaleY = 1.0f
            mSubtitle.setAlpha(1.0f)
            mActionbarShadow.setAlpha(0f)
        }
        requestExpandedHeaderSpacerRefresh()
    }

    fun setShouldListenToScroll(shouldListenToScroll: Boolean) {
        this.shouldListenToScroll = shouldListenToScroll
    }

    private fun updateHeader(scrollY: Int, anim: Boolean) {
        val progress: Float = (scrollY / getTitleCollapseScrollY()).coerceIn(0f, 1f)
        headerCollapseProgress = progress
        updateTitleLayoutForProgress(progress)
        val targetScale = getCollapsedTitleScaleForCurrentLayout()
        val scale: Float = 1f + (targetScale - 1f) * progress
        collapsedHeaderTranslationY = computeCollapsedHeaderTranslationY(
            mActionbar,
            targetScale,
            headerTranslationYFallbackFactor
        )
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
            cancelHeaderAnimations()
            mRelativeLayout.translationY = translationY
            mTitle.scaleX = scale
            mTitle.scaleY = scale
            mSubtitle.setAlpha(1f - progress)
        }
    }

    private fun getCollapsedTitleScaleForCurrentLayout(): Float {
        if (!shouldUseCompactCollapsedFolderTitle()) {
            return collapsedTitleScale
        }
        return min(collapsedTitleScale, COMPACT_TWO_LINE_FOLDER_TITLE_SCALE)
    }

    private fun shouldUseCompactCollapsedFolderTitle(): Boolean {
        val titleText = mTitle.text?.toString() ?: return false
        if (titleText.isEmpty()) return false
        return mTitle.paint.measureText(titleText) > getCollapsedTitleMaxWidth()
    }

    private fun updateTitleLayoutForProgress(progress: Float) {
        val maxLines = if (progress >= FOLDER_TITLE_COLLAPSED_LINE_PROGRESS) {
            COLLAPSED_FOLDER_TITLE_MAX_LINES
        } else {
            EXPANDED_FOLDER_TITLE_MAX_LINES
        }
        if (mTitle.maxLines != maxLines) {
            mTitle.maxLines = maxLines
        }

        val expandedWidth = getExpandedTitleMaxWidth()
        val collapsedWidth = getCollapsedTitleMaxWidth()
        val maxWidth = (expandedWidth + (collapsedWidth - expandedWidth) * progress)
            .toInt()
            .coerceAtLeast((mScreenDensity * MIN_TITLE_WIDTH_DP).toInt())
        if (mTitle.maxWidth != maxWidth) {
            mTitle.maxWidth = maxWidth
        }
    }

    private fun getExpandedTitleMaxWidth(): Int {
        val screenWidth = getAvailableHeaderWidth()
        val titleLeft = getTitleAbsoluteLeft()
        val endInset = (mScreenDensity * EXPANDED_TITLE_END_INSET_DP).toInt()
        return (screenWidth - titleLeft - endInset)
            .coerceAtLeast((mScreenDensity * MIN_TITLE_WIDTH_DP).toInt())
    }

    private fun getCollapsedTitleMaxWidth(): Int {
        val toolbar = mActionbar
        val titleLeft = getTitleAbsoluteLeft()
        val actionMenuLeft = if (toolbar != null && toolbar.width > 0) {
            findActionMenuLeft(toolbar)
        } else {
            0
        }
        val rightBound = if (actionMenuLeft > titleLeft) {
            actionMenuLeft
        } else {
            getAvailableHeaderWidth() - (mScreenDensity * COLLAPSED_ACTION_FALLBACK_INSET_DP).toInt()
        }
        val gap = (mScreenDensity * COLLAPSED_TITLE_ACTION_GAP_DP).toInt()
        return (rightBound - titleLeft - gap)
            .coerceAtLeast((mScreenDensity * MIN_TITLE_WIDTH_DP).toInt())
    }

    private fun findActionMenuLeft(toolbar: Toolbar): Int {
        var left = Int.MAX_VALUE
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i)
            if (child is ActionMenuView && child.childCount > 0) {
                for (j in 0 until child.childCount) {
                    val actionChild = child.getChildAt(j)
                    if (actionChild.visibility == View.VISIBLE) {
                        left = min(left, child.left + actionChild.left)
                    }
                }
            }
        }
        return if (left == Int.MAX_VALUE) 0 else left
    }

    private fun getAvailableHeaderWidth(): Int {
        if (mBindingRecyclerView.width > 0) return mBindingRecyclerView.width
        return mApp.resources.displayMetrics.widthPixels
    }

    private fun getTitleAbsoluteLeft(): Int {
        val fallbackHeaderLeft = (mScreenDensity * HEADER_START_MARGIN_DP).toInt()
        val headerLeft = if (mRelativeLayout.left > 0) {
            mRelativeLayout.left
        } else {
            fallbackHeaderLeft
        }
        return headerLeft + mTitle.left
    }

    private fun updateHeaderSpacerHeight() {
        if (App.isSearching) return
        val toolbarBottom = mActionbar?.bottom ?: (mScreenDensity * DEFAULT_ACTIONBAR_HEIGHT_DP).toInt()
        val bottomClearance = (mScreenDensity * HEADER_BOTTOM_CLEARANCE_DP).toInt()
        val height = (mRelativeLayout.top - toolbarBottom + mRelativeLayout.height + bottomClearance)
            .coerceAtLeast((mScreenDensity * DEFAULT_HEADER_SPACER_HEIGHT_DP).toInt())
        if (height == mLastHeaderSpacerHeight) return
        mLastHeaderSpacerHeight = height
        mHeaderSpacerHeightListener?.invoke(height)
    }

    private fun getHeaderSpacerScrollY(): Int {
        val defaultHeight = (mScreenDensity * DEFAULT_HEADER_SPACER_HEIGHT_DP).toInt()
        return mLastHeaderSpacerHeight.coerceAtLeast(defaultHeight)
    }

    private fun requestExpandedHeaderSpacerRefresh() {
        if (App.isSearching || mExpandedHeaderSpacerRefreshPosted) return
        mExpandedHeaderSpacerRefreshPosted = true
        mRelativeLayout.post {
            mRelativeLayout.requestLayout()
            mRelativeLayout.post {
                mExpandedHeaderSpacerRefreshPosted = false
                if (headerCollapseProgress != 0f) return@post
                updateHeaderSpacerHeight()
                computeFactors(mActionbar)
            }
        }
    }

    interface FolderPathClickListener {
        fun onFolderPathSegmentClicked(folderPathIndex: Int)
    }

    companion object {
        const val TAG: String = "ActivityHeader"
        private const val TITLE_SCALE_PIVOT = 1f
        private const val HEADER_START_MARGIN_DP = 72
        private const val DEFAULT_ACTIONBAR_HEIGHT_DP = 56
        private const val DEFAULT_HEADER_SPACER_HEIGHT_DP = 102
        private const val EXPANDED_TITLE_END_INSET_DP = 40
        private const val COLLAPSED_ACTION_FALLBACK_INSET_DP = 192
        private const val COLLAPSED_TITLE_ACTION_GAP_DP = 8
        private const val HEADER_BOTTOM_CLEARANCE_DP = 24
        private const val MIN_TITLE_WIDTH_DP = 96
        private const val EXPANDED_FOLDER_TITLE_MAX_LINES = 4
        private const val COLLAPSED_FOLDER_TITLE_MAX_LINES = 2
        private const val FOLDER_TITLE_COLLAPSED_LINE_PROGRESS = 0.68f
        private const val COMPACT_TWO_LINE_FOLDER_TITLE_SCALE = 0.76f
    }
}
