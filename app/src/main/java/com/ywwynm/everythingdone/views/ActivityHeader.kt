package com.ywwynm.everythingdone.views

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ImageSpan
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
import kotlin.math.ceil
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
    // 状态栏占位 View（view_status_bar）：与 actionbar 连体——同为不透明 surface、一起随
    // Home Chrome Retraction 上移/回落。进入选择模式时与 actionbar 一并隐藏。
    private var mStatusBarView: View? = null
    // 沉浸态状态栏保护罩（view_status_bar_scrim）：固定不随 chrome 收起，靠层叠顺序（在 chrome
    // 之下）只在顶栏完全收起后露出。它本身不平移；只需在进入选择模式时随 home chrome 一并隐藏，
    // 避免 contextual 滑入过程中因 view_status_bar 被隐藏而短暂露出。
    private var mStatusBarScrim: View? = null

    private var actionbarShadowAlpha: Float = 0f

    // Home Chrome Retraction (Immersive Thing List): how far the top App Chrome
    // (actionbar + collapsed title + shadow) is retracted upward, in pixels.
    // 0 = fully shown, getMaxRetractionPx() = fully hidden above the screen.
    // Driven by scroll DIRECTION after the header is fully collapsed, independent
    // of the collapse itself (driven by scroll POSITION). retractionOffsetPx is
    // added on top of headerCollapseTranslationY when transforming the title.
    private var retractionOffsetPx: Float = 0f
    private var headerCollapseTranslationY: Float = 0f
    private var mRetractionAnimator: ValueAnimator? = null

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

    // 当前私密文件夹标题：带"开/闭锁"内联图标的版本（mFolderLockTitle）与纯文字版本（mFolderPlainTitle）。
    // 锁用 ImageSpan 内联在标题开头，天然与第一行文字对齐（多行时不会跑到行间），且随标题换行/缩放一起走。
    // 折叠到 actionbar 附近时切到纯文字版（隐藏锁），避免锁挤进 actionbar 区、影响标题归位。
    private var mFolderLockTitle: CharSequence? = null
    private var mFolderPlainTitle: CharSequence? = null
    private var mFolderLockShown: Boolean = false

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
        // 折叠滚动距离 = header spacer 高度 - 标题归位余量(TITLE_DOCK_RESIDUAL_DP=8dp)。第一张卡片
        // 在滚动到 spacer 高度处其上边距(8dp)顶到 actionbar；让标题恰在其前 8dp 完成折叠，于是"标题
        // 归位"时首卡到 actionbar 的间距恒为 8dp(余量)+8dp(卡片上边距)=16dp，与卡片间距、搜索态一致。
        // spacer 随多行标题增高，折叠距离同步增大，标题归位与卡片归位始终对齐。单行/根标题时 spacer
        // 为默认 102dp，折叠距离为 94dp（102-8）。
        return getHeaderSpacerScrollY() - TITLE_DOCK_RESIDUAL_DP * mScreenDensity
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
        // Immersive Thing List: rv reserves SB+AB as top padding (clipToPadding=false),
        // so the first child rests at paddingTop rather than 0. Offset by paddingTop so
        // collapse progress still starts at the first downward pixel.
        var scrollY: Int = mBindingRecyclerView.paddingTop - firstChild.top
        val titleAndShadowScrollY: Int = getTitleCollapseScrollY().toInt()
        val shadowAppearCompletelyScrollY: Int = getHeaderSpacerScrollY()
        // "折叠完成"哨兵值：用 ceil 保证 progress 取到 1（多行时折叠距离含小数，toInt 下取整会
        // 让 progress 差一点点到不了 1，isFullyCollapsed 便一直为 false、沉浸式 retraction 失效）。
        val fullyCollapsedScrollY: Int = ceil(getTitleCollapseScrollY()).toInt()

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
                // 阴影在折叠完成后（scrollY 超过折叠距离）的余量区间内淡入，恰好在卡片贴到
                // actionbar（scrollY 抵达 spacer 高度）时充满。原公式把折叠点硬编码成 90dp；改用
                // 动态折叠点 titleAndShadowScrollY，多行标题下同样从折叠完成处起、到卡片归位淡满。
                actionbarShadowAlphaAfter =
                    ((scrollY - titleAndShadowScrollY) / (TITLE_DOCK_RESIDUAL_DP * mScreenDensity))
                        .coerceIn(0f, 1f)
                updateHeader(fullyCollapsedScrollY, anim)
            } else {
                updateHeader(fullyCollapsedScrollY, anim)
                actionbarShadowAlphaAfter = 1.0f
            }
        } else {
            updateHeader(fullyCollapsedScrollY, anim)
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
        mFolderLockTitle = null
        mFolderPlainTitle = null
        mFolderLockShown = false
        mTitle.setCompoundDrawables(null, null, null, null)
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
        // 私密文件夹：在标题开头内联一把锁（纯锁）作为"当前文件夹是私密"的正向标识；本会话已鉴权
        // （进入即已认证）画开锁、否则闭锁。用 CenteredLockSpan 内联，故与标题首行文字垂直居中对齐
        // （多行标题也只在第一行，不会像 compound drawable 那样在整段高度上居中）。
        if (folder.isPrivate) {
            val authed =
                ThingManager.getInstance(mApp)?.isFolderPrivacyAuthenticated(folder.id) == true
            val lockRes = if (authed) R.drawable.ic_lock_open else R.drawable.ic_locked_small
            val lock = ContextCompat.getDrawable(mApp, lockRes)?.mutate()
            val baseTitle = mTitle.text
            if (lock != null && baseTitle != null) {
                lock.setTint(background.representativeColor())
                // 尺寸跟随标题字号，与标题视觉等高（随标题缩放一并变小）。
                val size = mTitle.textSize.toInt().coerceAtLeast((mScreenDensity * 16).toInt())
                lock.setBounds(0, 0, size, size)
                val sb = SpannableStringBuilder()
                sb.append("￼") // 占位符，由 CenteredLockSpan 替换为锁
                sb.setSpan(CenteredLockSpan(lock), 0, 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                sb.append(" ") // 锁与标题之间留半字宽间距
                sb.append(baseTitle)
                mFolderLockTitle = sb
                mFolderPlainTitle = baseTitle
            } else {
                mFolderLockTitle = null
                mFolderPlainTitle = null
            }
        } else {
            mFolderLockTitle = null
            mFolderPlainTitle = null
        }
        mFolderLockShown = false
        applyFolderLockForProgress(headerCollapseProgress)
    }

    /**
     * 折叠时管理标题开头的私密锁：标题较展开时用"带锁"文本，滑动到 actionbar 附近（折叠进度过阈值）
     * 切到"纯文字"文本（隐藏锁），避免锁挤进 actionbar 区、影响标题缩小 / 换行 / 最终归位。仅在显示态
     * 变化时改文本，避免每帧重设。非私密文件夹（mFolderLockTitle 为空）不处理。
     */
    private fun applyFolderLockForProgress(progress: Float) {
        val lockedTitle = mFolderLockTitle ?: return
        val shouldShow = progress < FOLDER_LOCK_HIDE_PROGRESS
        if (shouldShow == mFolderLockShown) return
        mFolderLockShown = shouldShow
        mTitle.text = if (shouldShow) lockedTitle else mFolderPlainTitle
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

    fun hideActionbarShadow(anim: Boolean = true) {
        actionbarShadowAlpha = mActionbarShadow.alpha
        mActionbarShadow.animate()!!.cancel()
        if (anim) {
            mActionbarShadow.animate()!!.alpha(0f).withLayer().setDuration(160)
        } else {
            mActionbarShadow.alpha = 0f
        }
    }

    fun showActionbarShadow() {
        showActionbarShadow(actionbarShadowAlpha)
    }

    fun showActionbarShadow(alpha: Float) {
        mActionbarShadow.animate()!!.alpha(alpha).withLayer()
    }

    fun canContextualToolbarCoverHomeChrome(): Boolean {
        return mActionbar?.visibility == View.VISIBLE
                && mStatusBarView?.visibility == View.VISIBLE
                && retractionOffsetPx <= 0.5f
                && mActionbarShadow.alpha > 0.01f
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
        // reset 语义为"完全展开、显示"：一并恢复 home 顶部 chrome 可见（覆盖退出选择等路径）。
        mActionbar?.visibility = View.VISIBLE
        mStatusBarView?.visibility = View.VISIBLE
        mStatusBarScrim?.visibility = View.VISIBLE
        headerCollapseProgress = 0f
        headerCollapseTranslationY = 0f
        // Home Chrome Retraction: any reset (mode change, search exit, scope switch,
        // rotation) restores the top App Chrome to fully shown.
        mRetractionAnimator?.cancel()
        mRetractionAnimator = null
        retractionOffsetPx = 0f
        updateTitleLayoutForProgress(0f)
        if (anim) {
            mRelativeLayout.animate()!!.translationY(0f)
            mTitle.animate()!!.scaleX(1.0f)
            mTitle.animate()!!.scaleY(1.0f)
            mSubtitle.animate()!!.alpha(1.0f)
            mActionbarShadow.animate()!!.alpha(0f)
            mActionbar?.animate()?.translationY(0f)
            mStatusBarView?.animate()?.translationY(0f)
            mActionbarShadow.animate()!!.translationY(0f)
        } else {
            cancelHeaderAnimations()
            mRelativeLayout.translationY = 0f
            mTitle.scaleX = 1.0f
            mTitle.scaleY = 1.0f
            mSubtitle.setAlpha(1.0f)
            mActionbarShadow.setAlpha(0f)
            mActionbar?.translationY = 0f
            mStatusBarView?.translationY = 0f
            mActionbarShadow.translationY = 0f
        }
        requestExpandedHeaderSpacerRefresh()
    }

    fun setShouldListenToScroll(shouldListenToScroll: Boolean) {
        this.shouldListenToScroll = shouldListenToScroll
    }

    private fun updateHeader(scrollY: Int, anim: Boolean) {
        val progress: Float = (scrollY / getTitleCollapseScrollY()).coerceIn(0f, 1f)
        headerCollapseProgress = progress
        // 不变量：Home Chrome Retraction 只允许在完全折叠（progress==1）时非零。任何原因导致
        // 未完全折叠（上滑回顶、原地删项使列表变短等），都把 retraction 清零，避免"顶栏已隐藏但
        // 标题已展开"的错乱。这也让"原地改单项保留沉浸态"在列表被改短时安全退回。
        if (progress < 1f && retractionOffsetPx != 0f) {
            mRetractionAnimator?.cancel()
            mRetractionAnimator = null
            retractionOffsetPx = 0f
        }
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

        headerCollapseTranslationY = translationY

        if (anim) {
            // Retraction is 0 in every anim-path caller (mode changes / resets), so
            // the title animates to its plain collapse translation; actionbar/shadow
            // follow retraction directly.
            mRelativeLayout.animate()!!.translationY(translationY - retractionOffsetPx)

            /*
             * Changing scaleX and scaleY of title is better than changing its textSize.
             * pivotX and pivotY should be remained as 1 so that title's location won't
             * be changed incorrectly.
             */

            mTitle.animate()!!.scaleX(scale).setDuration(160)
            mTitle.animate()!!.scaleY(scale).setDuration(160)
            mSubtitle.animate()!!.alpha(1f - progress).withLayer().setDuration(160)
            mActionbar?.translationY = -retractionOffsetPx
            mStatusBarView?.translationY = -retractionOffsetPx
            mActionbarShadow.translationY = -retractionOffsetPx
        } else {
            cancelHeaderAnimations()
            applyRetractionTransforms()
            mTitle.scaleX = scale
            mTitle.scaleY = scale
            mSubtitle.setAlpha(1f - progress)
        }
    }

    /**
     * Home Chrome Retraction current offset in pixels (0 = shown).
     */
    fun getRetractionOffset(): Float = retractionOffsetPx

    /**
     * Fully-hidden retraction distance = the actionbar's bottom edge in fl_things
     * coordinates (fl_things fills [0..screenH]), i.e. `SB + AB`. Translating the
     * chrome up by this amount moves the actionbar bottom to y=0, off the top.
     */
    fun getMaxRetractionPx(): Float {
        val ab = mActionbar
        if (ab != null && ab.bottom > 0) {
            return ab.bottom.toFloat()
        }
        return mScreenDensity * DEFAULT_ACTIONBAR_HEIGHT_DP
    }

    fun isFullyCollapsed(): Boolean = headerCollapseProgress >= 1f

    /**
     * Set the Home Chrome Retraction offset. `anim` snaps to the value with a
     * short animation (used on finger release); otherwise applies immediately
     * (used per scroll frame). Any in-flight snap is cancelled first.
     */
    fun setRetractionOffset(px: Float, anim: Boolean) {
        val clamped = px.coerceIn(0f, getMaxRetractionPx())
        mRetractionAnimator?.cancel()
        mRetractionAnimator = null
        if (!anim) {
            retractionOffsetPx = clamped
            applyRetractionTransforms()
            return
        }
        if (clamped == retractionOffsetPx) return
        val animator = ValueAnimator.ofFloat(retractionOffsetPx, clamped)
        animator.duration = RETRACTION_SNAP_DURATION_MS
        animator.addUpdateListener {
            retractionOffsetPx = it.animatedValue as Float
            applyRetractionTransforms()
        }
        mRetractionAnimator = animator
        animator.start()
    }

    private fun applyRetractionTransforms() {
        mRelativeLayout.translationY = headerCollapseTranslationY - retractionOffsetPx
        mActionbar?.translationY = -retractionOffsetPx
        mStatusBarView?.translationY = -retractionOffsetPx
        mActionbarShadow.translationY = -retractionOffsetPx
    }

    fun setStatusBarView(view: View?) {
        mStatusBarView = view
    }

    fun setStatusBarScrim(view: View?) {
        mStatusBarScrim = view
    }

    /**
     * 进入 / 退出选择模式时整体显隐 home 顶部 chrome（状态栏占位＋actionbar＋折叠标题）。
     * 选择模式由独立的 contextual toolbar（自带状态栏占位）承载，从顶部滑入；隐藏 home chrome
     * 后，滑入过程中不再露出 home actionbar，做到"直接显示 contextual"。actionbar 阴影另由
     * ModeManager 管理，这里不动。
     */
    fun setHomeChromeVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.INVISIBLE
        mStatusBarView?.visibility = visibility
        mStatusBarScrim?.visibility = visibility
        mActionbar?.visibility = visibility
        mRelativeLayout.visibility = visibility
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
        applyFolderLockForProgress(progress)
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

    /**
     * 把锁图标内联进标题文本、并与所在行（标题首行）垂直居中的 ImageSpan。默认 ImageSpan 按基线对齐，
     * 多行时还会跑到整段中部；这里改成按该行文字的视觉中线居中，使锁与第一行文字对齐。
     */
    private class CenteredLockSpan(drawable: Drawable) : ImageSpan(drawable) {
        override fun getSize(
            paint: Paint, text: CharSequence?, start: Int, end: Int,
            fm: Paint.FontMetricsInt?
        ): Int = drawable.bounds.width()

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint
        ) {
            val d = drawable
            val metrics = paint.fontMetricsInt
            val transY = y + (metrics.descent + metrics.ascent) / 2 - d.bounds.height() / 2
            canvas.save()
            canvas.translate(x, transY.toFloat())
            d.draw(canvas)
            canvas.restore()
        }
    }

    interface FolderPathClickListener {
        fun onFolderPathSegmentClicked(folderPathIndex: Int)
    }

    companion object {
        const val TAG: String = "ActivityHeader"
        // Home Chrome Retraction 松手吸附动画时长（ms），与 FAB 隐现节奏一致。
        private const val RETRACTION_SNAP_DURATION_MS = 200L
        private const val TITLE_SCALE_PIVOT = 1f
        private const val HEADER_START_MARGIN_DP = 72
        private const val DEFAULT_ACTIONBAR_HEIGHT_DP = 56
        private const val DEFAULT_HEADER_SPACER_HEIGHT_DP = 102
        // 折叠完成（标题归位 actionbar）时，第一张卡片顶到 actionbar 底的滚动余量（dp）；也是
        // actionbar 阴影淡入的区间长度。折叠距离 = spacer 高 - 该余量。取 8dp（= thing_card_outer_spacing）：
        // 卡片自带 8dp 上边距，加这 8dp 预留，标题归位时首卡到 actionbar 的间距为 16dp——与卡片之间的
        // 间距（8+8）、以及搜索态首卡到 actionbar 的间距一致。默认 spacer 102dp 时折叠距离为 94dp（102-8）。
        private const val TITLE_DOCK_RESIDUAL_DP = 8
        private const val EXPANDED_TITLE_END_INSET_DP = 40
        private const val COLLAPSED_ACTION_FALLBACK_INSET_DP = 192
        private const val COLLAPSED_TITLE_ACTION_GAP_DP = 8
        private const val HEADER_BOTTOM_CLEARANCE_DP = 24
        private const val MIN_TITLE_WIDTH_DP = 96
        private const val EXPANDED_FOLDER_TITLE_MAX_LINES = 4
        private const val COLLAPSED_FOLDER_TITLE_MAX_LINES = 2
        private const val FOLDER_TITLE_COLLAPSED_LINE_PROGRESS = 0.68f
        // 折叠进度超过此值（滑动到 actionbar 附近）即隐藏标题前的私密文件夹图标，避免影响标题归位。
        private const val FOLDER_LOCK_HIDE_PROGRESS = 0.6f
        private const val COMPACT_TWO_LINE_FOLDER_TITLE_SCALE = 0.76f
    }
}
