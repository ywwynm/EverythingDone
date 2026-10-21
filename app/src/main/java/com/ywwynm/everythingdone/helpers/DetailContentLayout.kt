package com.ywwynm.everythingdone.helpers

import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.EditText
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.AudioAttachmentAdapter
import com.ywwynm.everythingdone.adapters.CheckListAdapter
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter
import kotlin.math.roundToInt

/** 详情各区域共用一个有界列表；固定编辑区保留 View，重复内容由各 Adapter 正常回收。 */
class DetailContentLayout(val recyclerView: RecyclerView, preparedSections: LinearLayout? = null) {
    private val template = preparedSections ?: (LayoutInflater.from(recyclerView.context)
        .inflate(R.layout.detail_content_sections, null, false) as LinearLayout)
    private val views = mutableMapOf<Int, View>()
    private val fixed = mutableMapOf<Int, FixedViewAdapter>()
    private var focusedFixedView: View? = null
    val adapter = ConcatAdapter()
    val layoutManager = object : GridLayoutManager(recyclerView.context, 1) {
        private var laidOutSpanCount = 1
        // 子树收束和代理移动可发生在同一动画周期；预测布局会把仍被拖动的代理判为消失项。
        // 保留 ItemAnimator 的真实增删与位移动画，仅关闭离屏位置的预测布局。
        override fun supportsPredictiveItemAnimations() = false

        override fun onLayoutCompleted(state: RecyclerView.State?) {
            super.onLayoutCompleted(state)
            laidOutSpanCount = spanCount
        }

        override fun onFocusSearchFailed(focused: View, direction: Int, recycler: RecyclerView.Recycler,
            state: RecyclerView.State): View? {
            // 配置变更会先重建输入连接、查询下一个编辑器，再执行新列数布局。
            // 此时 GridLayoutManager 内部行数组仍是旧列数，禁止通过焦点查询提前填充。
            if (laidOutSpanCount != spanCount) return null
            return super.onFocusSearchFailed(focused, direction, recycler, state)
        }
    }
    private var images: ImageAttachmentAdapter? = null
    private var checklist: CheckListAdapter? = null
    private var audios: AudioAttachmentAdapter? = null
    private var audioColumns = 1
    var checklistTopMargin = dp(20)
    var audioTopMargin = dp(32)

    init {
        fun collect(view: View) {
            if (view.id != View.NO_ID) views[view.id] = view
            if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
        }
        collect(template)
        recyclerView.viewTreeObserver.addOnGlobalFocusChangeListener { _, next ->
            if (next is EditText) focusedFixedView = views.values.firstOrNull { it === next }
        }
        while (template.childCount > 0) {
            val child = template.getChildAt(0)
            template.removeViewAt(0)
            fixed[child.id] = FixedViewAdapter(child)
        }
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter
        recyclerView.itemAnimator = null
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val (section, local) = sectionAt(position) ?: return layoutManager.spanCount
                return when {
                    section === images && images?.isFullSpanPosition(local) == false ->
                        layoutManager.spanCount / images!!.gridSpanCount()
                    section === audios -> layoutManager.spanCount / audioColumns
                    else -> layoutManager.spanCount
                }
            }
        }
        recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val holder = parent.getChildViewHolder(view)
                val local = holder.bindingAdapterPosition
                if (local == RecyclerView.NO_POSITION) return
                when (holder.bindingAdapter) {
                    checklist -> {
                        out.left = dp(8); out.right = dp(8)
                        if (local == 0) out.top = checklistTopMargin
                    }
                    images -> {
                        val image = images ?: return
                        val columns = image.gridSpanCount()
                        val start = if (image.isFullSpanPosition(0)) 1 else 0
                        val group = if (local < start) -1 else (local - start) / columns
                        val lastGroup = if (image.itemCount <= start) -1 else (image.itemCount - start - 1) / columns
                        if (group == lastGroup) out.bottom = dp(12)
                        if (local >= start) {
                            val column = (local - start) % columns
                            val original = DetailGridGeometry.borders(parent.width, columns)
                            val combined = DetailGridGeometry.borders(parent.width, layoutManager.spanCount)
                            out.left = original[column] - combined[column * layoutManager.spanCount / columns]
                            out.right = -out.left
                        }
                    }
                    audios -> {
                        val column = local % audioColumns
                        // 与旧音频容器左右 12dp padding 下的网格位置相同；卡片自身宽度由 Adapter 设置。
                        val original = DetailGridGeometry.borders(parent.width - 2 * dp(12), audioColumns)
                        val combined = DetailGridGeometry.borders(parent.width, layoutManager.spanCount)
                        out.left = dp(12) + original[column] - combined[column * layoutManager.spanCount / audioColumns]
                        if (local < audioColumns) out.top = audioTopMargin
                        val rows = ((audios?.itemCount ?: 0) + audioColumns - 1) / audioColumns
                        if (local / audioColumns == rows - 1) {
                            val density = parent.resources.displayMetrics.density
                            val oldRow = (56 * density).toInt() + (8 * density).toInt()
                            val margins = parent.resources.getDimensionPixelSize(R.dimen.card_audio_attachment_margin) * 2
                            out.bottom = rows * (oldRow - dp(56) - margins)
                        }
                    }
                }
            }
        })
        update(null, null, false, null, 1)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : View> view(id: Int): T = requireNotNull(views[id]) { "详情区域缺少 View：$id" } as T

    /** RecyclerView 只保存自身状态，固定编辑区需显式保留原先的 View 状态恢复行为。 */
    fun saveFixedViewState(): Bundle = Bundle().apply {
        val hierarchy = SparseArray<Parcelable>()
        fixed.values.forEach { it.content.saveHierarchyState(hierarchy) }
        putSparseParcelableArray("hierarchy", hierarchy)
        putInt("focus", views.values.firstOrNull { it.isFocused }?.id ?: focusedFixedView?.id ?: View.NO_ID)
    }

    @Suppress("DEPRECATION")
    fun restoreFixedViewState(state: Bundle) {
        state.getSparseParcelableArray<Parcelable>("hierarchy")?.let { hierarchy ->
            fixed.values.forEach { it.content.restoreHierarchyState(hierarchy) }
        }
        focusedFixedView = views[state.getInt("focus", View.NO_ID)]
        focusedFixedView?.let(::restoreFixedFocus)
    }

    private fun restoreFixedFocus(view: View) {
        val editor = view as? EditText
        val start = editor?.selectionStart ?: -1
        val end = editor?.selectionEnd ?: -1
        view.requestFocus()
        // EditText 再次获取焦点可能自动把选择区移至文末，保留离屏前的选区。
        if (editor != null && start >= 0 && end >= 0) editor.setSelection(start, end)
    }

    fun update(
        images: ImageAttachmentAdapter?, checklist: CheckListAdapter?, checklistVisible: Boolean,
        audios: AudioAttachmentAdapter?, audioColumns: Int
    ) {
        this.images = images
        this.checklist = checklist.takeIf { checklistVisible }
        this.audios = audios
        this.audioColumns = audioColumns.coerceAtLeast(1)
        layoutManager.spanCount = (images?.gridSpanCount() ?: 1) * this.audioColumns
        val desired = buildList<RecyclerView.Adapter<out RecyclerView.ViewHolder>> {
            images?.let { add(it) }
            add(fixed.getValue(R.id.et_title))
            add(fixed.getValue(R.id.et_content))
            this@DetailContentLayout.checklist?.let { add(it) }
            add(fixed.getValue(R.id.ll_move_checklist))
            audios?.let { add(it) }
            add(fixed.getValue(R.id.ll_type_info_screenshot))
            add(fixed.getValue(R.id.tv_update_time))
            add(fixed.getValue(R.id.tv_finish_time))
            add(fixed.getValue(R.id.tv_thing_folder_path))
        }
        adapter.adapters.filter { it !in desired }.forEach { adapter.removeAdapter(it) }
        desired.forEachIndexed { index, child ->
            if (child !in adapter.adapters) adapter.addAdapter(index, child)
        }
        recyclerView.invalidateItemDecorations()
    }

    fun globalPosition(section: RecyclerView.Adapter<*>, position: Int): Int {
        if (position !in 0 until section.itemCount) return RecyclerView.NO_POSITION
        var offset = 0
        for (child in adapter.adapters) {
            if (child === section) return offset + position
            offset += child.itemCount
        }
        return RecyclerView.NO_POSITION
    }

    fun holder(section: RecyclerView.Adapter<*>, position: Int): RecyclerView.ViewHolder? {
        val global = globalPosition(section, position)
        if (global == RecyclerView.NO_POSITION) return null
        return recyclerView.findViewHolderForAdapterPosition(global)
            ?.takeIf { it.bindingAdapter === section }
    }

    fun positionOf(viewId: Int): Int = globalPosition(fixed.getValue(viewId), 0)

    fun sectionAt(position: Int): Pair<RecyclerView.Adapter<*>, Int>? {
        if (position < 0) return null
        var local = position
        for (section in adapter.adapters) {
            if (local < section.itemCount) return section to local
            local -= section.itemCount
        }
        return null
    }

    private fun dp(value: Int): Int = (value * recyclerView.resources.displayMetrics.density).roundToInt()

    /** 固定区域只有一个逻辑条目。View 生命周期独立于包装 holder，避免离屏重建标题/正文输入连接。 */
    private inner class FixedViewAdapter(val content: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            return object : RecyclerView.ViewHolder(container) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val container = holder.itemView as LinearLayout
            if (content.parent === container) return
            (content.parent as? ViewGroup)?.removeView(content)
            container.removeAllViews()
            container.addView(content)
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            super.onViewAttachedToWindow(holder)
            val focus = focusedFixedView ?: return
            if ((focus === content || content.findViewById<View>(focus.id) === focus) &&
                recyclerView.rootView.findFocus() !is EditText) restoreFixedFocus(focus)
        }
    }
}
