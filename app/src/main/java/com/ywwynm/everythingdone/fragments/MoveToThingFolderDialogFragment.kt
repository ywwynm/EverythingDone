@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.utils.BackgroundUtil
import androidx.appcompat.content.res.AppCompatResources
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil
import com.ywwynm.everythingdone.utils.ThingsSorter
import com.ywwynm.everythingdone.views.ColorConstants
import com.ywwynm.everythingdone.views.DrawerNavigationView
import com.ywwynm.everythingdone.views.GradientRippleDrawable

open class MoveToThingFolderDialogFragment : BaseDialogFragment() {

    interface Listener {
        fun onMoveTargetConfirmed(targetFolderId: Long?)
        fun shouldAuthenticateBeforeExpand(folder: ThingFolder): Boolean
        fun onAuthenticateFolderExpand(folder: ThingFolder, onAuthenticated: () -> Unit)
    }

    private var mFolders: List<ThingFolder> = emptyList()
    private var mForbiddenFolderIds: Set<Long> = emptySet()
    private var mSelectedFolderId: Long? = null
    private var mListener: Listener? = null
    private var mAdapter: FolderTreeAdapter? = null
    private var mRows: List<Row> = emptyList()
    private val mExpandedFolderIds = HashSet<Long>()
    private var mHasAnyFolder = false
    private var mRecyclerView: RecyclerView? = null
    private var mTopSeparator: View? = null
    private var mBottomSeparator: View? = null
    private var mTitleView: TextView? = null
    private var mConfirmView: TextView? = null

    fun setFolders(
        folders: List<ThingFolder>,
        selectedFolderId: Long?,
        forbiddenFolderIds: Set<Long> = emptySet()
    ) {
        mFolders = folders
        mSelectedFolderId = selectedFolderId
        mForbiddenFolderIds = forbiddenFolderIds
        expandAncestorsOf(selectedFolderId)
        rebuildRows()
    }

    /** 保留以兼容调用方；移动到文件夹 dialog 现按自身逻辑上色（各文件夹色 / 根目录 accent），忽略此值。 */
    @Suppress("UNUSED_PARAMETER")
    fun setAccentBackground(background: ThingBackground?) {
    }

    /** When true, the root target node is labelled "All content" instead of "All things". */
    fun setHasAnyFolder(value: Boolean) {
        mHasAnyFolder = value
    }

    fun setListener(listener: Listener?) {
        mListener = listener
    }

    override fun getLayoutResource(): Int = R.layout.fragment_move_to_thing_folder

    override fun getDialogWindowWidthPx(): Int {
        return (320 * resources.displayMetrics.density).toInt()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        val title: TextView = f(R.id.tv_title_move_to_thing_folder)!!
        mTitleView = title

        val recyclerView: RecyclerView = f(R.id.rv_move_to_thing_folder)!!
        mRecyclerView = recyclerView
        mTopSeparator = f(R.id.view_separator_move_to_thing_folder_top)
        mBottomSeparator = f(R.id.view_separator_move_to_thing_folder_bottom)
        recyclerView.layoutManager = LinearLayoutManager(activity)
        mAdapter = FolderTreeAdapter()
        recyclerView.adapter = mAdapter
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateScrollSeparators()
            }
        })
        rebuildRows()

        f<TextView>(R.id.tv_cancel_as_bt_move_to_thing_folder)!!.setOnClickListener {
            dismiss()
        }
        val confirm: TextView = f(R.id.tv_confirm_as_bt_move_to_thing_folder)!!
        mConfirmView = confirm
        confirm.setOnClickListener {
            val selected = mSelectedFolderId
            if (selected != null && mForbiddenFolderIds.contains(selected)) return@setOnClickListener
            mListener?.onMoveTargetConfirmed(selected)
            dismiss()
        }
        // 标题 / 确定按钮颜色、确定 ripple 跟随当前选中目标文件夹的颜色。
        updateAccentChrome()

        return mContentView
    }

    override fun onDestroyView() {
        mRecyclerView = null
        mTopSeparator = null
        mBottomSeparator = null
        mTitleView = null
        mConfirmView = null
        super.onDestroyView()
    }

    private fun rebuildRows() {
        val childrenByParent = HashMap<Long?, MutableList<ThingFolder>>()
        for (folder in mFolders) {
            childrenByParent.getOrPut(folder.parentFolderId) { ArrayList() }.add(folder)
        }
        for (children in childrenByParent.values) {
            children.sortWith(folderLocationComparator())
        }

        val rows = ArrayList<Row>()
        // 根目录「全部」不显示展开/收缩按钮，顶层文件夹恒展开。
        rows.add(
            Row(
                folder = null,
                level = 0,
                hasChildren = false,
                expanded = false,
                selectable = true
            )
        )
        appendFolderRows(null, 1, childrenByParent, rows)
        mRows = rows
        updatePickerHeight()
        mAdapter?.notifyDataSetChanged()
        updateScrollChrome()
    }

    private fun appendFolderRows(
        parentFolderId: Long?,
        level: Int,
        childrenByParent: Map<Long?, List<ThingFolder>>,
        rows: MutableList<Row>
    ) {
        val children = childrenByParent[parentFolderId] ?: return
        for (folder in children) {
            val forbidden = mForbiddenFolderIds.contains(folder.id)
            val hasChildren = hasChildren(folder.id, childrenByParent)
            val expanded = mExpandedFolderIds.contains(folder.id)
            rows.add(
                Row(
                    folder = folder,
                    level = level,
                    hasChildren = hasChildren,
                    expanded = expanded,
                    selectable = !forbidden
                )
            )
            if (expanded) {
                appendFolderRows(folder.id, level + 1, childrenByParent, rows)
            }
        }
    }

    private fun hasChildren(
        parentFolderId: Long?,
        childrenByParent: Map<Long?, List<ThingFolder>>
    ): Boolean {
        return !childrenByParent[parentFolderId].isNullOrEmpty()
    }

    private fun updateScrollChrome() {
        val recyclerView = mRecyclerView ?: return
        recyclerView.post {
            if (activity == null || mRecyclerView == null) return@post
            EdgeEffectUtil.forRecyclerView(recyclerView, App.defaultAccentBackground.color)
            updateScrollSeparators()
        }
    }

    private fun updateScrollSeparators() {
        val recyclerView = mRecyclerView ?: return
        val topSeparator = mTopSeparator ?: return
        val bottomSeparator = mBottomSeparator ?: return
        val canScrollUp = recyclerView.canScrollVertically(-1)
        val canScrollDown = recyclerView.canScrollVertically(1)
        if (!canScrollUp && !canScrollDown) {
            topSeparator.visibility = View.INVISIBLE
            bottomSeparator.visibility = View.INVISIBLE
        } else if (!canScrollUp) {
            topSeparator.visibility = View.INVISIBLE
            bottomSeparator.visibility = View.VISIBLE
        } else if (!canScrollDown) {
            topSeparator.visibility = View.VISIBLE
            bottomSeparator.visibility = View.INVISIBLE
        } else {
            topSeparator.visibility = View.VISIBLE
            bottomSeparator.visibility = View.VISIBLE
        }
    }

    /**
     * Size the folder list to its content: grow row by row up to a capped height,
     * matching the note-list widget configuration's scope picker. Beyond the cap the
     * list scrolls and the top/bottom separators (license-dialog style) hint at it.
     */
    private fun updatePickerHeight() {
        val recyclerView = mRecyclerView ?: return
        val density = resources.displayMetrics.density
        val rowHeight = (PICKER_ROW_HEIGHT_DP * density).toInt()
        val target = (mRows.size.coerceAtLeast(1) * rowHeight)
            .coerceAtMost(PICKER_MAX_VISIBLE_ROWS * rowHeight)
        val params = recyclerView.layoutParams
        if (params.height != target) {
            params.height = target
            recyclerView.layoutParams = params
        }
    }

    private fun expandAncestorsOf(folderId: Long?) {
        if (folderId == null) return
        val byId = mFolders.associateBy { it.id }
        var current = byId[folderId]?.parentFolderId
        val visited = HashSet<Long>()
        while (current != null && visited.add(current)) {
            mExpandedFolderIds.add(current)
            current = byId[current]?.parentFolderId
        }
    }

    /** 当前选中目标文件夹自身颜色；选「全部」(根) 时用 accent+accent2 渐变。不再理会外部传入的记事色。 */
    private fun selectedFolderBackground(): ThingBackground {
        val id = mSelectedFolderId ?: return App.defaultAccentBackground
        val folder = mFolders.firstOrNull { it.id == id } ?: return App.defaultAccentBackground
        return folder.getBackground() ?: ThingBackground.pure(folder.getColor())
    }

    /** 标题 / 确定按钮文字颜色与确定 ripple 实时跟随当前选中目标文件夹颜色。 */
    private fun updateAccentChrome() {
        val bg = selectedFolderBackground()
        mTitleView?.let { BackgroundUtil.applyTextBackground(it, bg) }
        mConfirmView?.let {
            BackgroundUtil.applyTextBackground(it, bg)
            GradientRippleDrawable.applyAccentRipple(it, bg, bg.representativeColor())
        }
    }

    private fun folderLocationComparator(): Comparator<ThingFolder> {
        return Comparator { folder1, folder2 ->
            val result = ThingsSorter.compareByLocationAndSticky(
                folder1.location,
                folder2.location
            )
            if (result != 0) result else folder1.title.lowercase().compareTo(folder2.title.lowercase())
        }
    }

    /** 每行所属目标文件夹自身的颜色；根目录（无文件夹）行用 accent。 */
    private fun folderBackgroundFor(rowItem: Row): ThingBackground {
        val folder = rowItem.folder ?: return App.defaultAccentBackground
        return folder.getBackground() ?: ThingBackground.pure(folder.getColor())
    }

    /**
     * 跟 Drawer 一致：未选中行触摸 ripple 用目标文件夹自身颜色；选中行铺其「实色」（纯色 / 渐变，
     * 非透明度版本）+ 触摸 ripple 按其明暗自适应。颜色取自该行文件夹，而非被移动的记事/文件夹。
     */
    private fun rowBackground(bg: ThingBackground, selected: Boolean): Drawable {
        val radius = 8 * resources.displayMetrics.density
        if (selected) {
            val fill = (BackgroundUtil.fillDrawable(bg) as GradientDrawable).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
            }
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(Color.WHITE)
            }
            return RippleDrawable(
                ColorStateList.valueOf(ColorConstants.FolderList.selectedRipple(bg)), fill, mask
            )
        }
        return GradientRippleDrawable(bg, shapeOval = false, cornerRadiusPx = radius)
    }

    private fun toggleExpanded(row: Row) {
        val folder = row.folder ?: return
        if (row.expanded) {
            mExpandedFolderIds.remove(folder.id)
            rebuildRows()
            return
        }
        val listener = mListener
        if (listener?.shouldAuthenticateBeforeExpand(folder) == true) {
            // P1：是否需要认证由 listener（→ ThingManager 共享会话集）判定；本会话已认证过则
            // 直接展开。认证成功由 listener 侧统一写入共享集，这里只负责展开与重建。
            listener.onAuthenticateFolderExpand(folder) {
                mExpandedFolderIds.add(folder.id)
                rebuildRows()
            }
        } else {
            mExpandedFolderIds.add(folder.id)
            rebuildRows()
        }
    }

    private fun selectRow(row: Row) {
        if (!row.selectable) return
        mSelectedFolderId = row.folder?.id
        rebuildRows()
        updateAccentChrome()
    }

    private inner class FolderTreeAdapter : RecyclerView.Adapter<FolderTreeHolder>() {

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long {
            return mRows[position].folder?.id ?: Long.MIN_VALUE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderTreeHolder {
            return FolderTreeHolder(parent)
        }

        override fun getItemCount(): Int = mRows.size

        override fun onBindViewHolder(holder: FolderTreeHolder, position: Int) {
            holder.bind(mRows[position])
        }
    }

    private inner class FolderTreeHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LinearLayout(parent.context).apply {
            val density = resources.displayMetrics.density
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * density).toInt()
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (48 * density).toInt()
            isClickable = true
            isFocusable = true
        }
    ) {
        private val row: LinearLayout = itemView as LinearLayout
        private val icon = ImageView(parent.context)
        private val title = TextView(parent.context)
        private val expand = ImageView(parent.context)

        init {
            val density = parent.resources.displayMetrics.density
            row.addView(
                icon,
                LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt())
            )
            title.maxLines = 1
            title.ellipsize = TextUtils.TruncateAt.END
            title.textSize = 15f
            title.setTextColor(ColorConstants.FolderList.unselectedForeground(parent.context))
            row.addView(
                title,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (16 * density).toInt()
                    marginEnd = (8 * density).toInt()
                }
            )
            // 展开/收缩图标的图像与着色在 bindExpand 里按选中态设置（选中用满不透明）。
            expand.scaleType = ImageView.ScaleType.CENTER
            val padding = (8 * density).toInt()
            expand.setPadding(padding, padding, padding, padding)
            expand.isClickable = true
            expand.isFocusable = true
            // 展开/收缩按钮的 ripple 在 bindExpand 里按所属文件夹色 / 选中态自适应设置。
            row.addView(
                expand,
                LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
            )
        }

        fun bind(rowItem: Row) {
            val density = itemView.resources.displayMetrics.density
            val selected = rowItem.selectable && (
                rowItem.folder?.id == mSelectedFolderId ||
                    rowItem.folder == null && mSelectedFolderId == null
            )
            row.setPadding(
                (16 * density + rowItem.level * 16 * density).toInt(),
                0,
                (8 * density).toInt(),
                0
            )
            row.alpha = 1.0f
            row.isClickable = rowItem.selectable
            row.isFocusable = rowItem.selectable
            val rowBg = folderBackgroundFor(rowItem)
            row.background = rowBackground(rowBg, selected)
            bindIcon(rowItem, rowBg, selected)
            bindTitle(rowItem, rowBg, selected)
            bindExpand(rowItem, rowBg, selected)
            row.setOnClickListener(if (rowItem.selectable) {
                View.OnClickListener { selectRow(rowItem) }
            } else {
                null
            })
        }

        private fun bindIcon(rowItem: Row, rowBg: ThingBackground, selected: Boolean) {
            val folder = rowItem.folder
            if (folder == null) {
                // 「全部」图标：选中行已铺色 → 用对比色；否则默认抽屉前景色。
                val color = if (selected) {
                    ColorConstants.FolderList.selectedForeground(rowBg)
                } else {
                    ColorConstants.FolderList.unselectedForeground(itemView.context)
                }
                icon.setImageDrawable(
                    DisplayUtil.opaqueTintDrawable(
                        itemView.context,
                        ContextCompat.getDrawable(itemView.context, R.drawable.drawer_all),
                        color
                    )
                )
                icon.clearColorFilter()
                return
            }
            val ownBg = folder.getBackground() ?: ThingBackground.pure(folder.getColor())
            // 选中行已铺文件夹实色：文件夹图标改用对比色以保持可见（跟 Drawer 一致）。
            val iconBg = if (selected) {
                ThingBackground.pure(ColorConstants.FolderList.selectedForeground(rowBg))
            } else {
                ownBg
            }
            icon.setImageDrawable(
                DrawerNavigationView.FolderIconDrawable(
                    iconBg,
                    folder.isPrivate,
                    // 本会话已鉴权的私密文件夹画开锁。读共享单例会话集（与认证判定同源）。
                    com.ywwynm.everythingdone.managers.ThingManager
                        .getInstance(itemView.context)?.isFolderPrivacyAuthenticated(folder.id) == true
                )
            )
            if (rowItem.selectable) {
                icon.clearColorFilter()
            } else {
                icon.setColorFilter(ColorConstants.FolderList.disabledForeground(itemView.context))
            }
        }

        private fun bindTitle(rowItem: Row, rowBg: ThingBackground, selected: Boolean) {
            val folder = rowItem.folder
            if (folder == null) {
                title.text = getString(
                    if (mHasAnyFolder) R.string.all_content else R.string.all_things
                )
            } else {
                title.text = folder.title.ifEmpty { getString(R.string.default_thing_folder_name) }
            }
            // 选中行已铺文件夹实色：名称改用对比色；未选中用抽屉项前景色（跟 Drawer 统一）。
            val color = when {
                !rowItem.selectable -> ColorConstants.FolderList.disabledForeground(itemView.context)
                selected -> ColorConstants.FolderList.selectedForeground(rowBg)
                else -> ColorConstants.FolderList.unselectedForeground(itemView.context)
            }
            title.setTextColor(color)
            title.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        private fun bindExpand(rowItem: Row, rowBg: ThingBackground, selected: Boolean) {
            expand.visibility = if (rowItem.hasChildren) View.VISIBLE else View.INVISIBLE
            expand.rotation = if (rowItem.expanded) 180f else 0f
            if (selected) {
                // 选中行已铺文件夹实色：箭头用满不透明对比色（ic_dropdown 源 PNG 仅 ~54% alpha，
                // 用 opaqueTintDrawable 重映射 alpha，SRC_IN/ATOP 提不上去）。
                expand.clearColorFilter()
                expand.setImageDrawable(
                    DisplayUtil.opaqueTintDrawable(
                        expand.context,
                        AppCompatResources.getDrawable(expand.context, R.drawable.ic_dropdown),
                        ColorConstants.FolderList.selectedExpandIcon(rowBg)
                    )
                )
            } else {
                expand.setImageResource(R.drawable.ic_dropdown)
                expand.setColorFilter(ColorConstants.FolderList.unselectedForeground(expand.context))
            }
            // 未选中：展开/收缩按钮 ripple 用目标文件夹色；选中行已铺其色，改按明暗自适应。
            expand.background = if (selected) {
                BackgroundUtil.circularRipple(ColorConstants.FolderList.selectedRipple(rowBg))
            } else {
                GradientRippleDrawable(rowBg, shapeOval = true)
            }
            expand.setOnClickListener {
                if (rowItem.hasChildren) toggleExpanded(rowItem)
            }
        }
    }

    private data class Row(
        val folder: ThingFolder?,
        val level: Int,
        val hasChildren: Boolean,
        val expanded: Boolean,
        val selectable: Boolean
    )

    companion object {
        const val TAG: String = "MoveToThingFolderDialogFragment"
        // Row height matches FolderTreeHolder's fixed 48dp item height; the list grows
        // up to PICKER_MAX_VISIBLE_ROWS rows, then caps and scrolls.
        private const val PICKER_ROW_HEIGHT_DP = 48
        private const val PICKER_MAX_VISIBLE_ROWS = 6
    }
}
