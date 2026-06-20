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
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingFolder
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil
import com.ywwynm.everythingdone.views.DrawerNavigationView

open class MoveToThingFolderDialogFragment : BaseDialogFragment() {

    interface Listener {
        fun onMoveTargetConfirmed(targetFolderId: Long?)
        fun shouldAuthenticateBeforeExpand(folder: ThingFolder): Boolean
        fun onAuthenticateFolderExpand(folder: ThingFolder, onAuthenticated: () -> Unit)
    }

    private var mFolders: List<ThingFolder> = emptyList()
    private var mForbiddenFolderIds: Set<Long> = emptySet()
    private var mSelectedFolderId: Long? = null
    private var mAccentBackground: ThingBackground? = null
    private var mListener: Listener? = null
    private var mAdapter: FolderTreeAdapter? = null
    private var mRows: List<Row> = emptyList()
    private val mExpandedFolderIds = HashSet<Long>()
    private val mAuthenticatedExpandedPrivateFolderIds = HashSet<Long>()
    private var mRootExpanded = true
    private var mRecyclerView: RecyclerView? = null
    private var mTopSeparator: View? = null
    private var mBottomSeparator: View? = null

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

    fun setAccentBackground(background: ThingBackground?) {
        mAccentBackground = background
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
        applyAccentText(title)

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
        applyAccentText(confirm)
        confirm.setOnClickListener {
            val selected = mSelectedFolderId
            if (selected != null && mForbiddenFolderIds.contains(selected)) return@setOnClickListener
            mListener?.onMoveTargetConfirmed(selected)
            dismiss()
        }

        return mContentView
    }

    override fun onDestroyView() {
        mRecyclerView = null
        mTopSeparator = null
        mBottomSeparator = null
        super.onDestroyView()
    }

    private fun rebuildRows() {
        val childrenByParent = HashMap<Long?, MutableList<ThingFolder>>()
        for (folder in mFolders) {
            childrenByParent.getOrPut(folder.parentFolderId) { ArrayList() }.add(folder)
        }
        for (children in childrenByParent.values) {
            children.sortWith(
                compareByDescending<ThingFolder> { it.location }
                    .thenBy { it.title.lowercase() }
            )
        }

        val rows = ArrayList<Row>()
        rows.add(
            Row(
                folder = null,
                level = 0,
                hasChildren = hasChildren(null, childrenByParent),
                expanded = mRootExpanded,
                selectable = true
            )
        )
        if (mRootExpanded) {
            appendFolderRows(null, 1, childrenByParent, rows)
        }
        mRows = rows
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
            EdgeEffectUtil.forRecyclerView(recyclerView, accentBackground().representativeColor())
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

    private fun applyAccentText(textView: TextView) {
        val background = mAccentBackground
        if (background != null) {
            BackgroundUtil.applyTextBackground(textView, background)
        } else {
            textView.setTextColor(ContextCompat.getColor(activity!!, R.color.app_accent))
        }
    }

    private fun accentBackground(): ThingBackground {
        return mAccentBackground ?: ThingBackground.pure(
            ContextCompat.getColor(activity!!, R.color.app_accent)
        )
    }

    private fun selectedBackground(): GradientDrawable {
        return BackgroundUtil.makeTranslucentGradient(accentBackground(), 0x22).apply {
            cornerRadius = 8 * resources.displayMetrics.density
        }
    }

    private fun rowBackground(selected: Boolean): Drawable {
        val pressedColor = ContextCompat.getColor(activity!!, R.color.app_chrome_ripple)
        val content: Drawable = if (selected) {
            selectedBackground()
        } else {
            ColorDrawable(Color.TRANSPARENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return RippleDrawable(
                ColorStateList.valueOf(pressedColor),
                content,
                ColorDrawable(Color.WHITE)
            )
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(pressedColor))
            addState(intArrayOf(), content)
        }
    }

    private fun toggleExpanded(row: Row) {
        val folder = row.folder
        if (folder == null) {
            mRootExpanded = !mRootExpanded
            rebuildRows()
            return
        }
        if (row.expanded) {
            mExpandedFolderIds.remove(folder.id)
            rebuildRows()
            return
        }
        val listener = mListener
        if (listener?.shouldAuthenticateBeforeExpand(folder) == true &&
            !mAuthenticatedExpandedPrivateFolderIds.contains(folder.id)
        ) {
            listener.onAuthenticateFolderExpand(folder) {
                mAuthenticatedExpandedPrivateFolderIds.add(folder.id)
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
            title.setTextColor(ContextCompat.getColor(parent.context, R.color.app_chrome_on_surface_secondary))
            row.addView(
                title,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (16 * density).toInt()
                    marginEnd = (8 * density).toInt()
                }
            )
            expand.setImageResource(R.drawable.ic_dropdown)
            expand.setColorFilter(ContextCompat.getColor(parent.context, R.color.app_chrome_drawer_item_foreground))
            expand.scaleType = ImageView.ScaleType.CENTER
            val padding = (8 * density).toInt()
            expand.setPadding(padding, padding, padding, padding)
            expand.isClickable = true
            expand.isFocusable = true
            BackgroundUtil.installAppChromeCircleRipple(expand, parent.context)
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
            row.background = rowBackground(selected)
            bindIcon(rowItem)
            bindTitle(rowItem)
            bindExpand(rowItem)
            row.setOnClickListener(if (rowItem.selectable) {
                View.OnClickListener { selectRow(rowItem) }
            } else {
                null
            })
        }

        private fun bindIcon(rowItem: Row) {
            val folder = rowItem.folder
            if (folder == null) {
                icon.setImageDrawable(
                    DisplayUtil.opaqueTintDrawable(
                        itemView.context,
                        ContextCompat.getDrawable(itemView.context, R.drawable.drawer_all),
                        ContextCompat.getColor(
                            itemView.context,
                            R.color.app_chrome_drawer_item_foreground
                        )
                    )
                )
                icon.clearColorFilter()
                return
            }
            icon.setImageDrawable(
                DrawerNavigationView.FolderIconDrawable(
                    folder.getBackground() ?: ThingBackground.pure(folder.getColor()),
                    folder.isPrivate
                )
            )
            if (rowItem.selectable) {
                icon.clearColorFilter()
            } else {
                icon.setColorFilter(
                    ContextCompat.getColor(
                        itemView.context,
                        R.color.app_chrome_on_surface_disabled
                    )
                )
            }
        }

        private fun bindTitle(rowItem: Row) {
            val folder = rowItem.folder
            if (folder == null) {
                title.text = getString(R.string.underway)
            } else {
                title.text = folder.title.ifEmpty { getString(R.string.default_thing_folder_name) }
            }
            title.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (rowItem.selectable) {
                        R.color.app_chrome_on_surface_secondary
                    } else {
                        R.color.app_chrome_on_surface_disabled
                    }
                )
            )
            title.typeface = if (rowItem.selectable && (
                rowItem.folder?.id == mSelectedFolderId ||
                    rowItem.folder == null && mSelectedFolderId == null
            )
            ) {
                Typeface.DEFAULT_BOLD
            } else {
                Typeface.DEFAULT
            }
        }

        private fun bindExpand(rowItem: Row) {
            expand.visibility = if (rowItem.hasChildren) View.VISIBLE else View.INVISIBLE
            expand.rotation = if (rowItem.expanded) 180f else 0f
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
    }
}
