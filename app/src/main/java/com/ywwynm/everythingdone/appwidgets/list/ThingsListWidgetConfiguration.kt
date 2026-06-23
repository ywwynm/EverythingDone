package com.ywwynm.everythingdone.appwidgets.list

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.ywwynm.everythingdone.views.DrawerNavigationView

import kotlin.math.abs

/**
 * Created by qiizhang on 2016/8/10.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Configuration Activity for things list widget
 */
open class ThingsListWidgetConfiguration : AppCompatActivity() {

    private var mScopeAdapter: ScopeAdapter? = null

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
    private var mTypeButtons: List<Pair<Int, ImageView>> = emptyList()

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

        val tvTitle: TextView? = findViewById(R.id.tv_title_things_list_widget_configuration)
        BackgroundUtil.applyTextBackground(tvTitle, mAccentBackground)
        val tvConfirm: TextView? = findViewById(R.id.tv_confirm_as_bt_things_list_config)
        BackgroundUtil.applyTextBackground(tvConfirm, mAccentBackground)
        BackgroundUtil.installAppChromeDialogActionButton(tvConfirm, this)

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
        DisplayUtil.setSeekBarBackground(mSbAlpha, mAccentBackground)

        mCbSimpleView = findViewById(R.id.cb_simple_view)!!
        BackgroundUtil.applyCheckboxAccent(mCbSimpleView!!, mAccentBackground)
        mCbSimpleView!!.isChecked = simpleView
        findViewById<View>(R.id.rl_simple_view_as_bt).setOnClickListener {
            mCbSimpleView!!.toggle()
        }

        mCbAlphaHeader = findViewById(R.id.cb_alpha_header)!!
        BackgroundUtil.applyCheckboxAccent(mCbAlphaHeader!!, mAccentBackground)
        mCbAlphaHeader!!.isChecked = alphaHeader
        findViewById<View>(R.id.rl_alpha_header_as_bt).setOnClickListener {
            mCbAlphaHeader!!.toggle()
        }

        if (alpha == ThingWidgetInfo.HEADER_ALPHA_0) {
            mSbAlpha!!.progress = 0
        } else {
            mSbAlpha!!.progress = abs(alpha)
        }

        val cancelButton = findViewById<TextView>(R.id.tv_cancel_as_bt_things_list_config)
        val confirmButton = findViewById<TextView>(R.id.tv_confirm_as_bt_things_list_config)
        BackgroundUtil.installAppChromeDialogActionButton(cancelButton, this)
        BackgroundUtil.installAppChromeDialogActionButton(confirmButton, this)
        if (mIsSetting) {
            cancelButton.visibility = View.VISIBLE
            cancelButton.setOnClickListener { finish() }
        }
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

    private fun setupTypeFilters() {
        mTvTypeFilterSummary = findViewById(R.id.tv_widget_type_filter_summary)
        mTypeButtons = listOf(
            ThingWidgetInfo.TYPE_FILTER_ALL to findViewById(R.id.iv_widget_type_all),
            ThingWidgetInfo.TYPE_FILTER_NOTE to findViewById(R.id.iv_widget_type_note),
            ThingWidgetInfo.TYPE_FILTER_REMINDER to findViewById(R.id.iv_widget_type_reminder),
            ThingWidgetInfo.TYPE_FILTER_HABIT to findViewById(R.id.iv_widget_type_habit),
            ThingWidgetInfo.TYPE_FILTER_GOAL to findViewById(R.id.iv_widget_type_goal)
        )
        for ((mask, button) in mTypeButtons) {
            BackgroundUtil.installAppChromeCircleRipple(button, this)
            button.setOnClickListener { toggleTypeFilter(mask) }
        }
        updateTypeFilterButtons()
    }

    private fun setupStatus() {
        mTvStatusUnderway = findViewById(R.id.tv_widget_status_underway)
        mTvStatusFinished = findViewById(R.id.tv_widget_status_finished)
        listOf(mTvStatusUnderway, mTvStatusFinished).forEach { button ->
            BackgroundUtil.installAppChromePillRipple(button, this)
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
            BackgroundUtil.installAppChromePillRipple(button, this)
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
        val normalColor = ContextCompat.getColor(this, R.color.black_54p)
        val selectedAll = ThingWidgetInfo.isAllTypeFilter(mTypeFilterMask)
        updateTypeFilterSummary()
        for ((mask, button) in mTypeButtons) {
            val selected = if (mask == ThingWidgetInfo.TYPE_FILTER_ALL) {
                selectedAll
            } else {
                !selectedAll && mTypeFilterMask and mask != 0
            }
            setTypeButtonBackground(button, selected)
            if (selected) {
                button.clearColorFilter()
                button.setImageDrawable(
                    BackgroundUtil.tintDrawable(resources, button.drawable, mAccentBackground)
                )
            } else {
                button.setColorFilter(normalColor)
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
        button.background = if (selected) {
            BackgroundUtil.makeTranslucentGradient(mAccentBackground, 40).apply {
                shape = GradientDrawable.OVAL
            }
        } else {
            null
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
        button.background = if (selected) {
            BackgroundUtil.makeTranslucentGradient(mAccentBackground, 40).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = resources.displayMetrics.density * 16f
            }
        } else {
            null
        }
        button.setPaddingRelative(paddingStart, paddingTop, paddingEnd, paddingBottom)
        if (selected) {
            BackgroundUtil.applyTextBackground(button, mAccentBackground)
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
            BackgroundUtil.installAppChromeCircleRipple(expand, parent.context)
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
            if (selected) {
                holder.root.background = BackgroundUtil.makeTranslucentGradient(
                    mAccentBackground,
                    40
                )
            } else {
                holder.root.setBackgroundResource(R.drawable.selectable_item_background)
            }
            if (selected) {
                BackgroundUtil.applyTextBackground(holder.title, mAccentBackground)
            } else {
                holder.title.paint.shader = null
                holder.title.setTextColor(
                    ContextCompat.getColor(
                        this@ThingsListWidgetConfiguration,
                        R.color.black_54p
                    )
                )
            }
            holder.title.typeface = if (selected) {
                android.graphics.Typeface.DEFAULT_BOLD
            } else {
                android.graphics.Typeface.DEFAULT
            }
            if (folder == null) {
                holder.icon.setImageResource(R.drawable.drawer_all)
                if (selected) {
                    holder.icon.clearColorFilter()
                    holder.icon.setImageDrawable(
                        BackgroundUtil.tintDrawable(
                            resources,
                            ContextCompat.getDrawable(
                                this@ThingsListWidgetConfiguration,
                                R.drawable.drawer_all
                            ),
                            mAccentBackground
                        )
                    )
                } else {
                    holder.icon.setColorFilter(
                        ContextCompat.getColor(
                            this@ThingsListWidgetConfiguration,
                            R.color.black_54p
                        )
                    )
                }
                holder.title.setText(R.string.underway)
                holder.expand.visibility = View.INVISIBLE
                holder.expand.isClickable = false
                holder.expand.isFocusable = false
                holder.expand.setOnClickListener(null)
                holder.root.setOnClickListener {
                    mSelectedFolderId = null
                    notifyDataSetChanged()
                }
            } else {
                val bg = folder.getBackground() ?: ThingBackground.pure(folder.getColor())
                holder.icon.clearColorFilter()
                holder.icon.setImageDrawable(
                    DrawerNavigationView.FolderIconDrawable(bg, folder.isPrivate)
                )
                holder.title.text = folder.title.ifEmpty { getString(R.string.default_thing_folder_name) }
                val hasChildren = !childrenByParent[folder.id].isNullOrEmpty()
                holder.expand.visibility = if (hasChildren) View.VISIBLE else View.INVISIBLE
                holder.expand.isClickable = hasChildren
                holder.expand.isFocusable = hasChildren
                holder.expand.rotation = if (expandedFolderIds.contains(folder.id)) 180f else 0f
                holder.expand.setColorFilter(ContextCompat.getColor(this@ThingsListWidgetConfiguration, R.color.black_54p))
                holder.expand.contentDescription = getString(
                    if (expandedFolderIds.contains(folder.id)) {
                        R.string.cd_collapse_thing_folder
                    } else {
                        R.string.cd_expand_thing_folder
                    }
                )
                holder.root.setOnClickListener {
                    selectFolder(folder)
                }
                if (hasChildren) {
                    holder.expand.setOnClickListener {
                        toggleFolderExpanded(folder)
                    }
                } else {
                    holder.expand.setOnClickListener(null)
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
                }
                return
            }
            mSelectedFolderId = folder.id
            notifyDataSetChanged()
            updateScopeScrollSeparatorsSoon()
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
