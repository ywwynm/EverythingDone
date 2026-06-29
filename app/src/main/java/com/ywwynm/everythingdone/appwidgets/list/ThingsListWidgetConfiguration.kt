package com.ywwynm.everythingdone.appwidgets.list

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.appwidgets.AppWidgetHelper
import com.ywwynm.everythingdone.database.AppWidgetDAO
import com.ywwynm.everythingdone.database.ThingFolderDAO
import com.ywwynm.everythingdone.helpers.AuthenticationHelper
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.model.ThingWidgetInfo
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.LocaleUtil
import com.ywwynm.everythingdone.utils.ThingsSorter
import com.ywwynm.everythingdone.views.ColorConstants
import com.ywwynm.everythingdone.views.DrawerNavigationView
import com.ywwynm.everythingdone.views.GradientRippleDrawable

import kotlin.math.abs

/**
 * Created by qiizhang on 2016/8/10.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Configuration Activity for things list widget
 */
open class ThingsListWidgetConfiguration : AppCompatActivity() {

    private var mScopeAdapter: ScopeAdapter? = null

    private var mTvTitle: TextView? = null
    private var mTvConfirm: TextView? = null
    private var mRlSimpleView: View? = null
    private var mRlAlphaHeader: View? = null
    private var mSbAlpha: SeekBar? = null
    private var mCbAlphaHeader: AppCompatCheckBox? = null
    private var mCbSimpleView: AppCompatCheckBox? = null
    private var mScopeRecyclerView: RecyclerView? = null
    private var mScopeTopSeparator: View? = null
    private var mScopeBottomSeparator: View? = null
    private var mTvTypeFilterSummary: TextView? = null
    private var mTvDisplayList: TextView? = null
    private var mTvDisplayGrid: TextView? = null
    private var mTvStatusUnderway: TextView? = null
    private var mTvStatusFinished: TextView? = null
    // (类型掩码, 图标 ImageView, 图标资源)；图标资源用于每次从原始 drawable 重新着色（与 Drawer 一致）。
    private var mTypeButtons: List<Triple<Int, ImageView, Int>> = emptyList()

    private var mAppWidgetId: Int = 0
    private var mIsSetting: Boolean = false
    private var mSelectedFolderId: Long? = null
    private var mTypeFilterMask: Int = ThingWidgetInfo.TYPE_FILTER_ALL
    private var mDisplayMode: Int = ThingWidgetInfo.DISPLAY_MODE_LIST
    private var mStatus: Int = Def.ThingStatus.UNDERWAY
    private val mAccentBackground: ThingBackground = App.defaultAccentBackground

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleUtil.attachBaseContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_things_list_widget_configuration)

        mTvTitle = findViewById(R.id.tv_title_things_list_widget_configuration)
        mTvConfirm = findViewById(R.id.tv_confirm_as_bt_things_list_config)
        BackgroundUtil.installAppChromeDialogActionButton(mTvConfirm, this)

        readAppWidgetIdOrFinish()
        if (isFinishing) return
        val dao: AppWidgetDAO = AppWidgetDAO.getInstance(applicationContext)!!
        val info: ThingWidgetInfo? = dao.getThingWidgetInfoById(mAppWidgetId)
        var alpha = 100
        var simpleView = false
        var alphaHeader = false
        if (info != null) {
            alpha = info.alpha
            simpleView = info.style == ThingWidgetInfo.STYLE_SIMPLE
            alphaHeader = alpha < 0
            mSelectedFolderId = info.targetFolderId
            mTypeFilterMask = ThingWidgetInfo.normalizedTypeFilterMask(info.typeFilterMask)
            mDisplayMode = info.displayMode
            mStatus = if (info.status == Def.ThingStatus.FINISHED) {
                Def.ThingStatus.FINISHED
            } else {
                Def.ThingStatus.UNDERWAY
            }
        }

        setupScopePicker()
        setupTypeFilters()
        setupStatus()
        setupDisplayMode()

        mSbAlpha = findViewById(R.id.sb_app_widget_alpha)!!
        mSbAlpha!!.max = 100

        mCbSimpleView = findViewById(R.id.cb_simple_view)!!
        mCbSimpleView!!.isChecked = simpleView
        mRlSimpleView = findViewById(R.id.rl_simple_view_as_bt)
        mRlSimpleView!!.setOnClickListener {
            mCbSimpleView!!.toggle()
        }

        mCbAlphaHeader = findViewById(R.id.cb_alpha_header)!!
        mCbAlphaHeader!!.isChecked = alphaHeader
        mRlAlphaHeader = findViewById(R.id.rl_alpha_header_as_bt)
        mRlAlphaHeader!!.setOnClickListener {
            mCbAlphaHeader!!.toggle()
        }

        if (alpha == ThingWidgetInfo.HEADER_ALPHA_0) {
            mSbAlpha!!.progress = 0
        } else {
            mSbAlpha!!.progress = abs(alpha)
        }

        val cancelButton = findViewById<TextView>(R.id.tv_cancel_as_bt_things_list_config)
        BackgroundUtil.installAppChromeDialogActionButton(cancelButton, this)
        if (mIsSetting) {
            cancelButton.visibility = View.VISIBLE
            cancelButton.setOnClickListener { finish() }
        }

        // 标题 / 确定按钮（文字 + ripple）/ 透明度滑条 / 两个 checkbox（含其 item、checkbox 自身 ripple）
        // 颜色，随文件夹列表区域所选范围（文件夹色 / 根目录 accent 渐变）实时设置。
        updateScopeChrome()
    }

    private fun readAppWidgetIdOrFinish() {
        var intent: Intent = intent
        mAppWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val appWidgetId2: Int = intent.getIntExtra(
            Def.Communication.KEY_WIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
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
    }

    private fun setupScopePicker() {
        val rv: RecyclerView = findViewById(R.id.rv_scope_things_list_widget_config)
        mScopeRecyclerView = rv
        mScopeTopSeparator = findViewById(R.id.view_separator_scope_things_list_widget_config_top)
        mScopeBottomSeparator = findViewById(R.id.view_separator_scope_things_list_widget_config_bottom)
        mScopeAdapter = ScopeAdapter()
        rv.adapter = mScopeAdapter
        rv.layoutManager = LinearLayoutManager(this)
        updateScopePickerHeight()
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateScopeScrollSeparators()
            }
        })
        // Scroll to the selected folder so it is visible when the config opens
        // from an existing widget (re-configuration flow).
        rv.post {
            updateScopePickerHeight()
            val pos = mScopeAdapter!!.findSelectedPosition()
            if (pos >= 0) {
                (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, rv.height / 3)
            }
            updateScopeScrollSeparators()
        }
    }

    private fun updateScopePickerHeight() {
        val rv = mScopeRecyclerView ?: return
        val adapter = mScopeAdapter ?: return
        val rowCount = adapter.visibleItemCount()
        val targetHeight = (rowCount.coerceAtLeast(1) * dp(SCOPE_ROW_HEIGHT_DP))
            .coerceAtMost(dp(SCOPE_MAX_VISIBLE_ROWS * SCOPE_ROW_HEIGHT_DP))
        val params = rv.layoutParams
        if (params.height != targetHeight) {
            params.height = targetHeight
            rv.layoutParams = params
        }
    }

    private fun updateScopeScrollSeparatorsSoon() {
        val rv = mScopeRecyclerView ?: return
        rv.post { updateScopeScrollSeparators() }
    }

    private fun updateScopeScrollSeparators() {
        val rv = mScopeRecyclerView ?: return
        val topSeparator = mScopeTopSeparator ?: return
        val bottomSeparator = mScopeBottomSeparator ?: return
        val canScrollUp = rv.canScrollVertically(-1)
        // Content overflows container → list is inherently scrollable even when
        // scrolled all the way to the bottom (where canScrollDown == false).
        val contentScrollable = rv.canScrollVertically(-1) || rv.canScrollVertically(1)
        topSeparator.visibility = if (canScrollUp) View.VISIBLE else View.INVISIBLE
        bottomSeparator.visibility = if (contentScrollable) View.VISIBLE else View.INVISIBLE
    }

    /** 当前所选范围（文件夹）的颜色；根目录（未选文件夹）用 accent+accent2 渐变。 */
    private fun currentScopeBackground(): ThingBackground {
        val id = mSelectedFolderId ?: return mAccentBackground
        val folder = ThingFolderDAO.getInstance(this)?.getFolderById(id)
        return folder?.let { it.getBackground() ?: ThingBackground.pure(it.getColor()) } ?: mAccentBackground
    }

    /** 选中态背景：底色填充（半透明）+ 按底色明暗自适应的触摸波纹，按形状裁剪。 */
    private fun selectedFillRipple(
        bg: ThingBackground, cornerRadiusPx: Float, oval: Boolean
    ): RippleDrawable {
        val fill = BackgroundUtil.makeTranslucentGradient(bg, 40).apply {
            if (oval) shape = GradientDrawable.OVAL
            else { shape = GradientDrawable.RECTANGLE; cornerRadius = cornerRadiusPx }
        }
        val mask = GradientDrawable().apply {
            if (oval) shape = GradientDrawable.OVAL
            else { shape = GradientDrawable.RECTANGLE; cornerRadius = cornerRadiusPx }
            setColor(Color.WHITE)
        }
        return RippleDrawable(
            ColorStateList.valueOf(BackgroundUtil.adaptiveRippleColor(bg)), fill, mask
        )
    }

    /**
     * 文件夹列表行背景，与 Drawer 一致：未选中 → 文件夹色渐变波纹（直角整条）；
     * 选中 → 文件夹实色填充（纯色 / 渐变，非透明度版本）+ 按明暗自适应的触摸波纹。
     */
    private fun scopeRowBackground(bg: ThingBackground, selected: Boolean): Drawable {
        if (!selected) {
            return GradientRippleDrawable(bg, shapeOval = false, cornerRadiusPx = 0f)
        }
        return RippleDrawable(
            ColorStateList.valueOf(ColorConstants.FolderList.selectedRipple(bg)),
            BackgroundUtil.fillDrawable(bg),
            ColorDrawable(Color.WHITE)
        )
    }

    /** 范围 / 类型 / 状态 / 显示模式切换后，刷新依赖范围颜色的触摸 ripple。 */
    private fun refreshScopeDependentChrome() {
        updateScopeChrome()
        updateTypeFilterButtons()
        updateStatusButtons()
        updateDisplayModeButtons()
    }

    /**
     * 标题、确定按钮（文字 + 胶囊 ripple）、透明度滑条、两个 checkbox（box + 自身圆形 ripple）及其所在
     * item 的触摸 ripple，全部随文件夹列表区域所选范围（文件夹色 / 根目录 accent 渐变）着色。
     */
    private fun updateScopeChrome() {
        val bg = currentScopeBackground()
        mTvTitle?.let { BackgroundUtil.applyTextBackground(it, bg) }
        mTvConfirm?.let {
            BackgroundUtil.applyTextBackground(it, bg)
            // 确定按钮触摸 ripple 用范围色（胶囊，与其它 dialog 确定按钮一致），覆盖默认中性 ripple。
            it.foreground = GradientRippleDrawable(bg, shapeOval = false, cornerRadiusPx = -1f)
        }
        DisplayUtil.setSeekBarBackground(mSbAlpha, bg)
        mCbSimpleView?.let {
            BackgroundUtil.applyCheckboxAccent(it, bg)
            GradientRippleDrawable.applyCheckboxRipple(it, bg)
        }
        mCbAlphaHeader?.let {
            BackgroundUtil.applyCheckboxAccent(it, bg)
            GradientRippleDrawable.applyCheckboxRipple(it, bg)
        }
        mRlSimpleView?.background = GradientRippleDrawable(bg, shapeOval = false, cornerRadiusPx = 0f)
        mRlAlphaHeader?.background = GradientRippleDrawable(bg, shapeOval = false, cornerRadiusPx = 0f)
    }

    private fun setupTypeFilters() {
        mTvTypeFilterSummary = findViewById(R.id.tv_widget_type_filter_summary)
        mTypeButtons = listOf(
            Triple(ThingWidgetInfo.TYPE_FILTER_ALL, findViewById(R.id.iv_widget_type_all), R.drawable.drawer_all),
            Triple(ThingWidgetInfo.TYPE_FILTER_NOTE, findViewById(R.id.iv_widget_type_note), R.drawable.drawer_note),
            Triple(ThingWidgetInfo.TYPE_FILTER_REMINDER, findViewById(R.id.iv_widget_type_reminder), R.drawable.drawer_reminder),
            Triple(ThingWidgetInfo.TYPE_FILTER_HABIT, findViewById(R.id.iv_widget_type_habit), R.drawable.drawer_habit),
            Triple(ThingWidgetInfo.TYPE_FILTER_GOAL, findViewById(R.id.iv_widget_type_goal), R.drawable.drawer_goal)
        )
        for ((mask, button, _) in mTypeButtons) {
            button.setOnClickListener { toggleTypeFilter(mask) }
        }
        updateTypeFilterButtons()
    }

    private fun setupStatus() {
        mTvStatusUnderway = findViewById(R.id.tv_widget_status_underway)
        mTvStatusFinished = findViewById(R.id.tv_widget_status_finished)
        listOf(mTvStatusUnderway, mTvStatusFinished).forEach { button ->
            button?.includeFontPadding = false
        }
        mTvStatusUnderway?.setOnClickListener {
            mStatus = Def.ThingStatus.UNDERWAY
            updateStatusButtons()
        }
        mTvStatusFinished?.setOnClickListener {
            mStatus = Def.ThingStatus.FINISHED
            updateStatusButtons()
        }
        updateStatusButtons()
    }

    private fun updateStatusButtons() {
        applyDisplayModeButtonState(mTvStatusUnderway, mStatus == Def.ThingStatus.UNDERWAY)
        applyDisplayModeButtonState(mTvStatusFinished, mStatus == Def.ThingStatus.FINISHED)
    }

    private fun setupDisplayMode() {
        mTvDisplayList = findViewById(R.id.tv_widget_display_list)
        mTvDisplayGrid = findViewById(R.id.tv_widget_display_grid)
        listOf(mTvDisplayList, mTvDisplayGrid).forEach { button ->
            button?.includeFontPadding = false
        }
        mTvDisplayList?.setOnClickListener {
            mDisplayMode = ThingWidgetInfo.DISPLAY_MODE_LIST
            updateDisplayModeButtons()
        }
        mTvDisplayGrid?.setOnClickListener {
            mDisplayMode = ThingWidgetInfo.DISPLAY_MODE_GRID
            updateDisplayModeButtons()
        }
        updateDisplayModeButtons()
    }

    private fun toggleTypeFilter(mask: Int) {
        if (mask == ThingWidgetInfo.TYPE_FILTER_ALL) {
            mTypeFilterMask = ThingWidgetInfo.TYPE_FILTER_ALL
        } else {
            mTypeFilterMask = if (mTypeFilterMask == ThingWidgetInfo.TYPE_FILTER_ALL) {
                mask
            } else {
                mTypeFilterMask xor mask
            }
            mTypeFilterMask = ThingWidgetInfo.normalizedTypeFilterMask(mTypeFilterMask)
        }
        updateTypeFilterButtons()
    }

    private fun updateTypeFilterButtons() {
        // 未选中类型 icon 用主题自适应的抽屉项前景色（亮色偏黑、暗色偏白），与 Drawer 一致，
        // 不再用恒定的 black_54p（暗色模式下不可见）。
        val unselectedColor = ContextCompat.getColor(this, R.color.app_chrome_drawer_item_foreground)
        val selectedAll = ThingWidgetInfo.isAllTypeFilter(mTypeFilterMask)
        updateTypeFilterSummary()
        for ((mask, button, iconRes) in mTypeButtons) {
            val selected = if (mask == ThingWidgetInfo.TYPE_FILTER_ALL) {
                selectedAll
            } else {
                !selectedAll && mTypeFilterMask and mask != 0
            }
            setTypeButtonBackground(button, selected)
            // 每次从原始 drawable 重新着色（与 Drawer 的 ThingFilterPanel 一致），避免反复着色累积。
            val src = AppCompatResources.getDrawable(this, iconRes)
            button.clearColorFilter()
            if (selected) {
                button.setImageDrawable(
                    BackgroundUtil.tintDrawable(resources, src, currentScopeBackground())
                )
            } else {
                button.setImageDrawable(
                    DisplayUtil.opaqueTintDrawable(this, src, unselectedColor)
                )
            }
        }
    }

    private fun updateTypeFilterSummary() {
        val typeTitle = if (ThingWidgetInfo.isAllTypeFilter(mTypeFilterMask)) {
            getString(R.string.all_types)
        } else {
            ThingWidgetInfo.getTypeFilterTitle(this, mTypeFilterMask) ?: getString(R.string.all_types)
        }
        mTvTypeFilterSummary?.text = typeTitle
    }

    private fun setTypeButtonBackground(button: ImageView, selected: Boolean) {
        val paddingStart = button.paddingStart
        val paddingTop = button.paddingTop
        val paddingEnd = button.paddingEnd
        val paddingBottom = button.paddingBottom
        val bg = currentScopeBackground()
        // 未选中：范围颜色波纹（圆形）；选中：范围颜色填充 + 自适应波纹。
        button.background = if (selected) {
            selectedFillRipple(bg, 0f, oval = true)
        } else {
            GradientRippleDrawable(bg, shapeOval = true)
        }
        button.setPaddingRelative(paddingStart, paddingTop, paddingEnd, paddingBottom)
    }

    private fun updateDisplayModeButtons() {
        applyDisplayModeButtonState(
            mTvDisplayList,
            mDisplayMode == ThingWidgetInfo.DISPLAY_MODE_LIST
        )
        applyDisplayModeButtonState(
            mTvDisplayGrid,
            mDisplayMode == ThingWidgetInfo.DISPLAY_MODE_GRID
        )
    }

    private fun applyDisplayModeButtonState(button: TextView?, selected: Boolean) {
        if (button == null) return
        val paddingStart = button.paddingStart
        val paddingTop = button.paddingTop
        val paddingEnd = button.paddingEnd
        val paddingBottom = button.paddingBottom
        val bg = currentScopeBackground()
        // 未选中：范围颜色波纹（胶囊）；选中：范围颜色填充 + 自适应波纹。
        button.background = if (selected) {
            selectedFillRipple(bg, resources.displayMetrics.density * 16f, oval = false)
        } else {
            GradientRippleDrawable(bg, shapeOval = false, cornerRadiusPx = -1f)
        }
        button.setPaddingRelative(paddingStart, paddingTop, paddingEnd, paddingBottom)
        if (selected) {
            BackgroundUtil.applyTextBackground(button, bg)
        } else {
            button.paint.shader = null
            button.setTextColor(ContextCompat.getColor(this, R.color.app_chrome_on_surface_secondary))
        }
        button.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    open fun onConfirmClicked(view: View?) {
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
        appWidgetDAO.insert(
            mAppWidgetId,
            ThingWidgetInfo.LIST_WIDGET_THING_ID,
            ThingWidgetInfo.SIZE_MIDDLE,
            alpha,
            style,
            mSelectedFolderId,
            mTypeFilterMask,
            mDisplayMode,
            mStatus
        )

        if (!mIsSetting) {
            val views: RemoteViews = AppWidgetHelper.createRemoteViewsForThingsList(this, mAppWidgetId)
            AppWidgetManager.getInstance(app).updateAppWidget(mAppWidgetId, views)
            val intent = Intent()
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId)
            setResult(RESULT_OK, intent)
        } else {
            AppWidgetHelper.updateThingsListAppWidget(app, mAppWidgetId)
        }
        finish()
    }

    private fun dp(value: Int): Int {
        return (resources.displayMetrics.density * value).toInt()
    }

    private inner class ScopeAdapter : RecyclerView.Adapter<ScopeViewHolder>() {
        private val folderDao: ThingFolderDAO = ThingFolderDAO.getInstance(this@ThingsListWidgetConfiguration)!!
        private val expandedFolderIds = HashSet<Long>()
        private val authenticatedPrivateFolderIds = HashSet<Long>()
        private val childrenByParent = HashMap<Long?, List<ThingFolder>>()
        private val visibleItems = ArrayList<ScopeItem>()
        // Root scope label switches to "All content" once any Thing Folder row exists
        // in the database (including trashed ones), mirroring the Drawer and dialog.
        private val hasAnyFolder: Boolean = folderDao.hasAnyFolder()

        init {
            val allFolders = folderDao.getAllFolders()
                .filter { !folderDao.isEffectivelyDeleted(it.id) }
            val mutableChildren = HashMap<Long?, MutableList<ThingFolder>>()
            for (folder in allFolders) {
                mutableChildren.getOrPut(folder.parentFolderId) { ArrayList() }.add(folder)
            }
            for ((parent, children) in mutableChildren) {
                childrenByParent[parent] = children.sortedWith(folderLocationComparator())
            }
            val selectedId = mSelectedFolderId
            if (selectedId != null) {
                val path = folderDao.getFolderPath(selectedId)
                for (folder in path.dropLast(1)) {
                    if (shouldAuthenticateFolder(folder.id)) break
                    expandedFolderIds.add(folder.id)
                }
            }
            rebuildVisibleItems()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScopeViewHolder {
            val row = LinearLayout(parent.context)
            row.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            )
            row.gravity = Gravity.CENTER_VERTICAL
            row.orientation = LinearLayout.HORIZONTAL
            row.setBackgroundResource(R.drawable.selectable_item_background)

            val icon = ImageView(parent.context)
            icon.layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            row.addView(icon)

            val title = TextView(parent.context)
            title.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = dp(2)
                leftMargin = dp(2)
            }
            title.gravity = Gravity.CENTER_VERTICAL
            title.setSingleLine(true)
            title.ellipsize = android.text.TextUtils.TruncateAt.END
            title.textSize = 14f
            row.addView(title)

            val expand = ImageView(parent.context)
            expand.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            expand.scaleType = ImageView.ScaleType.CENTER_INSIDE
            expand.isClickable = true
            expand.isFocusable = true
            // 展开按钮 ripple 在 onBindViewHolder 里按所属文件夹色 / 选中态自适应设置。
            expand.setImageResource(R.drawable.ic_dropdown)
            row.addView(expand)
            return ScopeViewHolder(row, icon, title, expand)
        }

        override fun onBindViewHolder(holder: ScopeViewHolder, position: Int) {
            val item = visibleItems[position]
            val folder = item.folder
            val selected = if (folder == null) {
                mSelectedFolderId == null
            } else {
                mSelectedFolderId == folder.id
            }
            holder.root.setPadding(dp(16 + item.level * 16), 0, dp(4), 0)
            val rowBg = folder?.let { it.getBackground() ?: ThingBackground.pure(it.getColor()) }
                ?: mAccentBackground
            // 跟 Drawer 一致：未选中行触摸 ripple 用所属文件夹色（直角整条）；选中行铺其实色 + 自适应波纹。
            holder.root.background = scopeRowBackground(rowBg, selected)

            // 前景色：选中 → 对比色（onColor）；未选中 → 抽屉项前景色（与 Drawer 统一，去掉渐变文字）。
            val fgColor = if (selected) {
                ColorConstants.FolderList.selectedForeground(rowBg)
            } else {
                ColorConstants.FolderList.unselectedForeground(this@ThingsListWidgetConfiguration)
            }
            holder.title.paint.shader = null
            holder.title.setTextColor(fgColor)
            holder.title.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            if (folder == null) {
                // 「全部 / 根目录」图标：满不透明，选中用对比色、未选中用抽屉项前景色（与 Drawer 一致）。
                holder.icon.clearColorFilter()
                holder.icon.setImageDrawable(
                    DisplayUtil.opaqueTintDrawable(
                        this@ThingsListWidgetConfiguration,
                        ContextCompat.getDrawable(
                            this@ThingsListWidgetConfiguration, R.drawable.drawer_all
                        ),
                        fgColor
                    )
                )
                holder.title.setText(
                    if (hasAnyFolder) R.string.all_content else R.string.all_things
                )
                // 根目录「全部」不显示展开/收缩按钮，顶层文件夹恒展开。
                holder.expand.visibility = View.INVISIBLE
                holder.expand.isClickable = false
                holder.expand.isFocusable = false
                holder.expand.setOnClickListener(null)
                holder.root.setOnClickListener {
                    mSelectedFolderId = null
                    notifyDataSetChanged()
                    refreshScopeDependentChrome()
                }
            } else {
                // 文件夹图标：未选中用文件夹自身颜色；选中行已铺其色 → 改用对比色保持可见（与 Drawer 一致）。
                val iconBg = if (selected) {
                    ThingBackground.pure(ColorConstants.FolderList.selectedForeground(rowBg))
                } else {
                    rowBg
                }
                holder.icon.clearColorFilter()
                holder.icon.setImageDrawable(
                    DrawerNavigationView.FolderIconDrawable(
                        iconBg, folder.isPrivate,
                        // 配置页是独立本地会话：已认证私密文件夹按本地认证集画开锁。
                        isFolderPrivacyAuthenticated(folder.id)
                    )
                )
                holder.title.text = folder.title.ifEmpty { getString(R.string.default_thing_folder_name) }
                val hasChildren = !childrenByParent[folder.id].isNullOrEmpty()
                bindExpandButton(
                    holder, rowBg, selected,
                    expanded = expandedFolderIds.contains(folder.id),
                    visible = hasChildren,
                    onToggle = if (hasChildren) {
                        { toggleFolderExpanded(folder) }
                    } else {
                        null
                    }
                )
                holder.root.setOnClickListener {
                    selectFolder(folder)
                }
            }
        }

        override fun getItemCount(): Int {
            return visibleItems.size
        }

        fun visibleItemCount(): Int {
            return visibleItems.size
        }

        private fun rebuildVisibleItems() {
            visibleItems.clear()
            visibleItems.add(ScopeItem(null, 0))
            addChildren(null, 1)
        }

        private fun addChildren(parentFolderId: Long?, level: Int) {
            val children = childrenByParent[parentFolderId] ?: return
            for (folder in children) {
                visibleItems.add(ScopeItem(folder, level))
                if (expandedFolderIds.contains(folder.id) && canShowFolderChildren(folder)) {
                    addChildren(folder.id, level + 1)
                }
            }
        }

        fun findSelectedPosition(): Int {
            for (i in visibleItems.indices) {
                val item = visibleItems[i]
                if (item.folder?.id == mSelectedFolderId) {
                    return i
                }
                if (item.folder == null && mSelectedFolderId == null) {
                    return i // "All" item
                }
            }
            return 0
        }

        private fun selectFolder(folder: ThingFolder) {
            if (shouldAuthenticateFolder(folder.id)) {
                authenticateFolder(folder, R.string.open_private_thing_folder) {
                    mSelectedFolderId = folder.id
                    notifyDataSetChanged()
                    updateScopeScrollSeparatorsSoon()
                    refreshScopeDependentChrome()
                }
                return
            }
            mSelectedFolderId = folder.id
            notifyDataSetChanged()
            updateScopeScrollSeparatorsSoon()
            refreshScopeDependentChrome()
        }

        private fun toggleFolderExpanded(folder: ThingFolder) {
            val expanding = !expandedFolderIds.contains(folder.id)
            if (expanding) {
                if (shouldAuthenticateFolder(folder.id)) {
                    authenticateFolder(folder, R.string.expand_private_thing_folder) {
                        expandedFolderIds.add(folder.id)
                        performToggleFolderExpanded(folder, expanding = true)
                    }
                    return
                }
                expandedFolderIds.add(folder.id)
            } else {
                expandedFolderIds.remove(folder.id)
            }
            performToggleFolderExpanded(folder, expanding)
        }

        private fun performToggleFolderExpanded(folder: ThingFolder, expanding: Boolean) {
            val folderPos = visibleItems.indexOfFirst { it.folder?.id == folder.id }
            if (folderPos < 0) return

            // Animate the expand arrow on the ViewHolder that is showing this folder
            val holder = mScopeRecyclerView
                ?.findViewHolderForAdapterPosition(folderPos) as? ScopeViewHolder
            holder?.expand?.animate()?.rotation(if (expanding) 180f else 0f)
                ?.setDuration(220)?.start()

            val childCount = countVisibleChildren(folder.id)
            rebuildVisibleItems()
            updateScopePickerHeight()

            if (expanding) {
                notifyItemRangeInserted(folderPos + 1, childCount)
            } else {
                notifyItemRangeRemoved(folderPos + 1, childCount)
            }
            updateScopeScrollSeparatorsSoon()
        }

        /**
         * 展开/收缩按钮统一样式（文件夹行与根目录行共用）：未选中用所属色波纹 + 抽屉项前景色箭头；
         * 选中行已铺其色 → 满不透明对比箭头 + 自适应波纹。旋转角直接按 [expanded] 落定（切换时的旋转
         * 动画由各自的 toggle 方法单独触发）。
         */
        private fun bindExpandButton(
            holder: ScopeViewHolder,
            rowBg: ThingBackground,
            selected: Boolean,
            expanded: Boolean,
            visible: Boolean,
            onToggle: (() -> Unit)?
        ) {
            holder.expand.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            holder.expand.isClickable = visible
            holder.expand.isFocusable = visible
            holder.expand.rotation = if (expanded) 180f else 0f
            if (selected) {
                holder.expand.clearColorFilter()
                holder.expand.setImageDrawable(
                    DisplayUtil.opaqueTintDrawable(
                        this@ThingsListWidgetConfiguration,
                        AppCompatResources.getDrawable(
                            this@ThingsListWidgetConfiguration, R.drawable.ic_dropdown
                        ),
                        ColorConstants.FolderList.selectedExpandIcon(rowBg)
                    )
                )
            } else {
                holder.expand.setImageResource(R.drawable.ic_dropdown)
                holder.expand.setColorFilter(
                    ColorConstants.FolderList.unselectedForeground(this@ThingsListWidgetConfiguration)
                )
            }
            holder.expand.background = if (selected) {
                BackgroundUtil.circularRipple(ColorConstants.FolderList.selectedRipple(rowBg))
            } else {
                GradientRippleDrawable(rowBg, shapeOval = true)
            }
            holder.expand.contentDescription = getString(
                if (expanded) R.string.cd_collapse_thing_folder else R.string.cd_expand_thing_folder
            )
            holder.expand.setOnClickListener(
                if (visible && onToggle != null) {
                    View.OnClickListener { onToggle() }
                } else {
                    null
                }
            )
        }

        private fun countVisibleChildren(parentFolderId: Long?): Int {
            val children = childrenByParent[parentFolderId] ?: return 0
            var count = 0
            for (child in children) {
                count++
                if (expandedFolderIds.contains(child.id) && canShowFolderChildren(child)) {
                    count += countVisibleChildren(child.id)
                }
            }
            return count
        }

        private fun canShowFolderChildren(folder: ThingFolder): Boolean {
            return !shouldAuthenticateFolder(folder.id)
        }

        private fun folderLocationComparator(): Comparator<ThingFolder> {
            return Comparator { folder1, folder2 ->
                val result = ThingsSorter.compareByLocationAndSticky(
                    folder1.location,
                    folder2.location
                )
                if (result != 0) {
                    result
                } else {
                    folder1.title.lowercase().compareTo(folder2.title.lowercase())
                }
            }
        }

        private fun shouldAuthenticateFolder(folderId: Long): Boolean {
            return folderDao.isEffectivelyPrivate(folderId) &&
                !isFolderPrivacyAuthenticated(folderId)
        }

        private fun isFolderPrivacyAuthenticated(folderId: Long): Boolean {
            val path = folderDao.getFolderPath(folderId)
            for (folder in path) {
                if (folder.isPrivate && authenticatedPrivateFolderIds.contains(folder.id)) {
                    return true
                }
            }
            return false
        }

        private fun markFolderPrivacyAuthenticated(folderId: Long) {
            val path = folderDao.getFolderPath(folderId)
            for (folder in path) {
                if (folder.isPrivate) {
                    authenticatedPrivateFolderIds.add(folder.id)
                }
            }
        }

        private fun authenticateFolder(
            folder: ThingFolder,
            titleRes: Int,
            onAuthenticated: () -> Unit
        ) {
            val password = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
                .getString(Def.Meta.KEY_PRIVATE_PASSWORD, null)
            AuthenticationHelper.authenticate(
                this@ThingsListWidgetConfiguration,
                folder.getBackground() ?: ThingBackground.pure(folder.getColor()),
                getString(titleRes),
                password,
                object : AuthenticationHelper.AuthenticationCallback {
                    override fun onAuthenticated() {
                        markFolderPrivacyAuthenticated(folder.id)
                        onAuthenticated()
                    }

                    override fun onCancel() { }
                }
            )
        }
    }

    private data class ScopeItem(
        val folder: ThingFolder?,
        val level: Int
    )

    private class ScopeViewHolder(
        val root: LinearLayout,
        val icon: ImageView,
        val title: TextView,
        val expand: ImageView
    ) : RecyclerView.ViewHolder(root)

    companion object {
        const val TAG: String = "ThingsListWidgetConfiguration"
        private const val SCOPE_ROW_HEIGHT_DP = 44
        private const val SCOPE_MAX_VISIBLE_ROWS = 4
    }
}
