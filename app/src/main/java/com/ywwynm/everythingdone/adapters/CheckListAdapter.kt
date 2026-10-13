@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.adapters

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import android.text.Editable
import android.text.TextWatcher
import android.text.util.Linkify
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.utils.LocalizedText
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.ChecklistCompletion
import com.ywwynm.everythingdone.helpers.ChecklistEditorItems
import com.ywwynm.everythingdone.helpers.LineSpacingHelper
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DeviceUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.KeyboardUtil
import com.ywwynm.everythingdone.utils.LocaleUtil

import kotlin.math.min

/**
 * Created by ywwynm on 2015/9/17.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Multi-level support added by ywwynm and Claude Opus 4.8 on 2026/6/26.
 * Adapter for check list.
 */
open class CheckListAdapter(
    context: Context?, type: Int, items: MutableList<String?>?
) : RecyclerView.Adapter<BaseViewHolder>() {

    private var mMaxItemCount: Int = 0

    private var mWatchEditTextChange: Boolean = true
    private var mDragging: Boolean = false

    interface ItemsChangeCallback {
        fun onInsert(position: Int)
        fun onRemove(position: Int, item: String?, cursorPos: Int)
    }
    private var mItemsChangeCallback: ItemsChangeCallback? = null

    interface IvStateTouchCallback {
        fun onTouch(pos: Int)
    }
    private var mIvStateTouchCallback: IvStateTouchCallback? = null

    interface ActionCallback {
        fun onAction(before: String?, after: String?)
    }
    private var mActionCallback: ActionCallback? = null

    interface ExpandShrinkCallback {
        fun updateChecklistHeight(
            expand: Boolean, items: MutableList<String?>?, isClickingExpandOrShrink: Boolean
        )
    }
    private var mExpandShrinkCallback: ExpandShrinkCallback? = null
    private var mExpanded: Boolean = true

    private var mEtTouchListener: View.OnTouchListener? = null
    private var mEtClickListener: View.OnClickListener? = null
    private var mEtLongClickListener: View.OnLongClickListener? = null

    interface TvItemClickCallback {
        fun onItemClick(itemPos: Int)
        fun onItemSpaceClick(v: View?) // added on 2017/2/11
    }
    private var mTvItemClickCallback: TvItemClickCallback? = null

    private var mContext: Context? = context

    private var mInflater: LayoutInflater? = LayoutInflater.from(context)

    private var mType: Int = type

    private var mItems: MutableList<String?>? = editableItems(items)
    private var mFocusedEntry: ChecklistEditorItems.Entry? = null
    private var mEditorRecyclerView: RecyclerView? = null
    private val mEditorFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, focus ->
        if (focus is EditText && mEditorRecyclerView?.findContainingViewHolder(focus)?.bindingAdapter !== this) {
            mFocusedEntry?.finishComposition()
            mFocusedEntry = null
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        if (mType == EDITTEXT_EDITABLE) {
            mEditorRecyclerView = recyclerView
            recyclerView.viewTreeObserver.addOnGlobalFocusChangeListener(mEditorFocusListener)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(mEditorFocusListener)
        mEditorRecyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private fun editableItems(items: MutableList<String?>?): MutableList<String?>? =
        if (mType == EDITTEXT_EDITABLE && items != null) ChecklistEditorItems(items) else items

    /** 拖拽使用同一行身份移动，文字重复也不会交换光标状态。 */
    fun moveItem(from: Int, to: Int) {
        val items = mItems ?: return
        if (items is ChecklistEditorItems) items.move(from, to) else items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    private var mShouldAutoLink: Boolean = false

    /**
     * The owning thing's color. Set by [BaseThingsAdapter] per-bind so the
     * checklist's text and checkbox icons can adapt to the background luminance.
     * 0 = unset → keep the default white-on-card behaviour.
     */
    private var mThingColor: Int = 0
    private var mFixedTextSize: Float? = null
    private var mFixedIconScale: Float? = null

    init {
        removeItemsForTextView()
    }

    open fun setDragging(dragging: Boolean) {
        mDragging = dragging
    }

    open fun isDragging(): Boolean = mDragging

    open fun setIvStateTouchCallback(ivStateTouchCallback: IvStateTouchCallback?) {
        mIvStateTouchCallback = ivStateTouchCallback
    }

    open fun setActionCallback(actionCallback: ActionCallback?) {
        mActionCallback = actionCallback
    }

    open fun setEtTouchListener(etTouchListener: View.OnTouchListener?) {
        mEtTouchListener = etTouchListener
    }

    open fun setEtClickListener(etClickListener: View.OnClickListener?) {
        mEtClickListener = etClickListener
    }

    open fun setEtLongClickListener(etLongClickListener: View.OnLongClickListener?) {
        mEtLongClickListener = etLongClickListener
    }

    open fun setTvItemClickCallback(tvItemClickCallback: TvItemClickCallback?) {
        mTvItemClickCallback = tvItemClickCallback
    }

    open fun setExpandShrinkCallback(expandShrinkCallback: ExpandShrinkCallback?) {
        mExpandShrinkCallback = expandShrinkCallback
    }

    open fun setExpanded(expanded: Boolean) {
        mExpanded = expanded
    }

    open fun isExpanded(): Boolean = mExpanded

    open fun setMaxItemCount(maxItemCount: Int) {
        mMaxItemCount = maxItemCount
    }

    open fun setItems(items: MutableList<String?>?) {
        mFocusedEntry = null
        mItems = editableItems(items)
        removeItemsForTextView()
        notifyDataSetChanged()
    }

    open fun getItems(): MutableList<String?>? = mItems

    open fun setItemsChangeCallback(itemsChangeCallback: ItemsChangeCallback?) {
        mItemsChangeCallback = itemsChangeCallback
    }

    open fun setShouldAutoLink(shouldAutoLink: Boolean) {
        mShouldAutoLink = shouldAutoLink
    }

    open fun setThingColor(thingColor: Int) {
        mThingColor = thingColor
    }

    open fun setFixedTextSize(textSize: Float?) {
        mFixedTextSize = textSize
    }

    open fun setFixedIconScale(iconScale: Float?) {
        mFixedIconScale = iconScale
    }

    /** True when the foreground should be drawn black-side rather than white-side. */
    private fun dark(): Boolean = isDarkForeground(mThingColor)

    private fun textColorSecondary(): Int = cardTextColorSecondary(mThingColor)

    private fun textColorFinished(): Int = cardTextColorFinished(mThingColor)

    /**
     * Tint a checklist-row image so that the white-pixel PNGs become visible on a light card.
     */
    private fun tintRowIcon(iv: ImageView?) {
        if (iv == null) return
        setIconTint(iv, if (dark()) Color.BLACK else null)
    }

    private fun setIconTint(iv: ImageView, color: Int?) {
        val current = ImageViewCompat.getImageTintList(iv)
        if (current?.defaultColor != color) {
            ImageViewCompat.setImageTintList(iv, color?.let(ColorStateList::valueOf))
        }
    }

    private fun applyFixedIconScale(iv: ImageView?) {
        val iconScale = mFixedIconScale ?: return
        val drawable = iv?.drawable ?: return
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return
        val lp = iv.layoutParams ?: return
        lp.width = (drawable.intrinsicWidth * iconScale).toInt().coerceAtLeast(1)
        lp.height = (drawable.intrinsicHeight * iconScale).toInt().coerceAtLeast(1)
        iv.layoutParams = lp
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private fun updateIconRipples(holder: EditTextHolder) {
        val rippleColor = if (mThingColor == 0) {
            BackgroundUtil.RIPPLE_LIGHT
        } else {
            BackgroundUtil.thingRippleColor(mThingColor)
        }
        if (holder.rippleColor == rippleColor) return
        for (iv in arrayOf(holder.ivState, holder.ivIndent, holder.ivOutdent, holder.ivExpandShrink)) {
            val ripple = iv?.foreground as? RippleDrawable
            if (ripple == null) BackgroundUtil.installCircleRipple(iv, rippleColor)
            else ripple.setColor(ColorStateList.valueOf(rippleColor))
        }
        holder.rippleColor = rippleColor
    }

    private fun removeItemsForTextView() {
        if (mType != TEXTVIEW) return
        prepareCardItems(mContext, mItems)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return if (mType == TEXTVIEW) {
            TextViewHolder(mInflater!!.inflate(R.layout.check_list_tv, parent, false))
        } else {
            EditTextHolder(mInflater!!.inflate(R.layout.check_list_et, parent, false))
        }
    }

    override fun onBindViewHolder(viewHolder: BaseViewHolder, position: Int) {
        if (mType == TEXTVIEW) {
            val holder = viewHolder as TextViewHolder
            val params = holder.tv!!.layoutParams as LinearLayout.LayoutParams
            val dark = dark()
            if (mMaxItemCount != -1 && position == mMaxItemCount) {
                holder.iv!!.visibility = View.GONE
                holder.tv.textSize = mFixedTextSize ?: 18f
                holder.tv.setTextColor(textColorSecondary())
                holder.tv.text = "..."
                holder.tv.contentDescription =
                    mContext!!.getString(R.string.cd_checklist_more_items)
                params.setMargins((density * 8).toInt(), 0, 0, params.bottomMargin)
                setCardItemIndent(holder, 1)
                setEventForTextViewItemMore(holder)
            } else {
                holder.iv!!.visibility = View.VISIBLE
                val flag = holder.tv.paintFlags
                val stateContent: String = mItems!![position]!!
                val state = stateContent[0]
                val level = CheckListHelper.levelOf(stateContent)
                val finished = state == '1'
                if (state == '0') {
                    holder.iv.setImageResource(
                        if (dark)
                            R.drawable.checklist_unchecked_card_black
                        else R.drawable.checklist_unchecked_card
                    )
                    holder.iv.contentDescription =
                        mContext!!.getString(R.string.cd_checklist_unfinished_item)
                    holder.tv.setTextColor(
                        CheckListHelper.colorForLevel(textColorSecondary(), level, false)
                    )
                    holder.tv.paintFlags = flag and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                } else if (state == '1') {
                    holder.iv.setImageResource(
                        if (dark)
                            R.drawable.checklist_checked_card_black
                        else R.drawable.checklist_checked_card
                    )
                    holder.iv.contentDescription =
                        mContext!!.getString(R.string.cd_checklist_finished_item)
                    holder.tv.setTextColor(
                        CheckListHelper.colorForLevel(textColorFinished(), level, true)
                    )
                    if (FrequentSettings.getBoolean(Def.Meta.KEY_SIMPLE_FCLI)) {
                        holder.tv.paintFlags = flag and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    } else {
                        holder.tv.paintFlags = flag or Paint.STRIKE_THRU_TEXT_FLAG
                    }
                }
                applyFixedIconScale(holder.iv)

                val size = mItems!!.size
                // 字号的自变量是"卡片上实际渲染出几条"，不是总条数，更不能把上限本身当输入。
                // 上限变成可调项之后，原式在调大和调小两个方向上都会让字变小（5 项的卡片上限
                // 由 8 调到 3：15.71sp → 14sp；10 项的卡片上限由 8 调到 16：14sp → 12.86sp），
                // 而且它今天就已经让宽卡片上 10 项清单的字号小于同内容的普通卡片。
                // COMPACT_TEXT_SIZE_ITEM_COUNT 是纯排版常量，与用户设定的上限无关。
                // 见 docs/features/thing-card-content-limits/decisions.md。
                val visible = if (mMaxItemCount < 0) size else min(size, mMaxItemCount)
                val baseSize = cardBaseTextSizeSp(visible, mFixedTextSize)
                params.setMargins(
                    0, cardItemTopMarginPx(visible, mFixedTextSize, density), 0, params.bottomMargin
                )
                holder.tv.textSize = baseSize * CheckListHelper.sizeRatioForLevel(level)

                holder.tv.text = CheckListHelper.textOf(stateContent)
                alignCardRow(holder, params.topMargin, params.bottomMargin, level)
                setCardItemIndent(holder, level)

                setEventForTextViewItem(holder)
            }
        } else {
            val holder = viewHolder as EditTextHolder
            val entry = (mItems as? ChecklistEditorItems)?.entry(position)
            holder.saveEditorState()
            if (holder.boundEntry !== entry) {
                holder.detaching = true
                holder.et!!.clearFocus()
                holder.detaching = false
                holder.boundEntry = entry
            }
            val stateContent: String = mItems!![position]!!
            val state = stateContent[0]
            val realItem = state == '0' || state == '1'
            holder.flSeparator!!.visibility = if (state == '3') View.VISIBLE else View.GONE
            holder.ivState!!.visibility = if (state == '3') View.GONE else View.VISIBLE
            holder.ivState.isClickable = state != '3' && state != '4'
            holder.ivExpandShrink!!.visibility = if (state == '4') View.VISIBLE else View.GONE
            if (!realItem) {
                holder.hideIndentOutdent()
                holder.resetIndent()
            }

            holder.et!!.isEnabled = state != '4'
            holder.et.visibility = if (state == '3') View.GONE else View.VISIBLE
            holder.et.paint.textSkewX = if (state == '4') -0.20f else 0f

            val flags = holder.et.paintFlags
            val finalFlags = if (state == '1') flags or Paint.STRIKE_THRU_TEXT_FLAG
                else flags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            if (flags != finalFlags) holder.et.paintFlags = finalFlags
            if (!realItem) {
                holder.et.textSize = if (state == '4') 16f else 20f
                holder.et.setTextColor(if (state == '4') textColorFinished() else textColorSecondary())
            }
            if (state != '2' && !holder.et.hint.isNullOrEmpty()) holder.et.hint = ""

            val params = holder.et.layoutParams as LinearLayout.LayoutParams
            val finalWidth = if (state == '4') LinearLayout.LayoutParams.WRAP_CONTENT
                else LinearLayout.LayoutParams.MATCH_PARENT
            val finalTop = if (realItem) params.topMargin else when (state) {
                '2' -> (density * 4).toInt()
                '4' -> (density * 6).toInt()
                else -> 0
            }
            if (params.width != finalWidth || params.topMargin != finalTop) {
                params.width = finalWidth
                params.topMargin = finalTop
                holder.et.layoutParams = params
            }

            mWatchEditTextChange = false
            tintRowIcon(holder.ivState)
            if (!realItem) {
                tintRowIcon(holder.ivIndent)
                tintRowIcon(holder.ivOutdent)
            }
            tintRowIcon(holder.ivExpandShrink)
            updateIconRipples(holder)
            if (state == '0') {
                if (!mDragging) {
                    holder.ivState.setImageResource(R.drawable.checklist_unchecked_detail)
                    holder.ivState.contentDescription =
                        mContext!!.getString(R.string.cd_checklist_unfinished_item_clickable)
                } else {
                    holder.ivState.setImageResource(R.drawable.checklist_move_76)
                    holder.ivState.contentDescription =
                        mContext!!.getString(R.string.cd_checklist_move)
                }
                if (holder.et.text.toString() != CheckListHelper.textOf(stateContent)) {
                    holder.et.setText(CheckListHelper.textOf(stateContent))
                }
                holder.applyLevelStyle(stateContent)
            } else if (state == '1') {
                if (!mDragging) {
                    holder.ivState.setImageResource(R.drawable.checklist_checked_detail)
                    holder.ivState.contentDescription =
                        mContext!!.getString(R.string.cd_checklist_finished_item_clickable)
                } else {
                    holder.ivState.setImageResource(R.drawable.checklist_move_50)
                    holder.ivState.contentDescription =
                        mContext!!.getString(R.string.cd_checklist_move)
                }
                if (holder.et.text.toString() != CheckListHelper.textOf(stateContent)) {
                    holder.et.setText(CheckListHelper.textOf(stateContent))
                }
                holder.applyLevelStyle(stateContent)
            } else if (state == '2') {
                holder.ivState.setImageResource(R.drawable.checklist_add)
                val newItem = mContext!!.getString(R.string.hint_new_item)
                holder.ivState.contentDescription = newItem
                holder.et.hint = newItem
                holder.et.setHintTextColor(textColorFinished())
                if (holder.et.length() != 0) holder.et.setText("")
            } else if (state == '3') {
                holder.ivState.visibility = View.GONE
                holder.ivIndent!!.visibility = View.GONE
                holder.ivOutdent!!.visibility = View.GONE
                holder.et.visibility = View.GONE
                holder.flSeparator.visibility = View.VISIBLE
                val sep: View? = holder.flSeparator.getChildAt(0)
                if (sep != null) {
                    val d: Drawable? = sep.background
                    if (d != null) {
                        val color = if (dark()) 0x42000000 else 0x42FFFFFF
                        d.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN)
                    }
                }
            } else if (state == '4') {
                holder.ivState.setImageResource(R.drawable.checklist_finished)
                holder.ivState.isClickable = false

                // 只统计分隔线以下的底部已完成区（'4' 头部之后才是已完成区）；顶部就地完成的二/三级项
                // 状态位也是 '1'，但不计入。见 decisions.md（已完成头部只统计底部已完成区）。
                var finishedCount = 0
                val headerIdx = mItems!!.indexOf("4")
                if (headerIdx != -1) {
                    for (i in (headerIdx + 1) until mItems!!.size) {
                        if (CheckListHelper.isFinished(mItems!![i])) finishedCount++
                    }
                }
                val finishedItemsCountStr = getFinishedItemsCountStr(finishedCount) + " "
                holder.ivState.contentDescription = finishedItemsCountStr

                holder.ivExpandShrink.rotation = 0f
                if (mExpanded) {
                    holder.ivExpandShrink.setImageResource(R.drawable.act_shrink_checklist_finished_items)
                    holder.ivExpandShrink.contentDescription =
                        mContext!!.getString(R.string.cd_checklist_shrink_finished_items)
                } else {
                    holder.ivExpandShrink.setImageResource(R.drawable.act_expand_checklist_finished_items)
                    holder.ivExpandShrink.contentDescription =
                        mContext!!.getString(R.string.cd_checklist_expand_finished_items)
                }
                holder.ivExpandShrink.visibility = View.VISIBLE

                holder.et.setText(finishedItemsCountStr)
            }
            entry?.let {
                if (it.selectionStart >= 0 && it.selectionEnd >= 0) {
                    holder.et.setSelection(it.selectionStart.coerceAtMost(holder.et.length()),
                        it.selectionEnd.coerceAtMost(holder.et.length()))
                }
            }
            mWatchEditTextChange = true
        }
    }

    override fun onViewDetachedFromWindow(holder: BaseViewHolder) {
        (holder as? EditTextHolder)?.let {
            it.saveEditorState()
            it.detaching = true
        }
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewAttachedToWindow(holder: BaseViewHolder) {
        super.onViewAttachedToWindow(holder)
        (holder as? EditTextHolder)?.let {
            it.detaching = false
            val entry = it.boundEntry
            if (entry != null && entry === mFocusedEntry) {
                // attach 发生在 RecyclerView 布局内部，holder 此时可能仍保留复用前的坐标。
                // TextView 获焦会立即请求滚动；等本轮布局结束后再恢复，避免按旧位置滚走。
                it.itemView.post restore@{
                    if (!it.itemView.isAttachedToWindow || it.bindingAdapter !== this ||
                        it.boundEntry !== entry || mFocusedEntry !== entry) return@restore
                    val focus = it.itemView.rootView.findFocus()
                    // 用户已经进入另一个编辑框时，旧行重新出现不能抢回焦点。
                    if (focus !is EditText || focus === it.et) {
                        it.et!!.requestFocus()
                        if (entry.selectionStart >= 0 && entry.selectionEnd >= 0) {
                            it.et.setSelection(entry.selectionStart.coerceAtMost(it.et.length()),
                                entry.selectionEnd.coerceAtMost(it.et.length()))
                        }
                        if (entry.composingStart >= 0 && entry.composingEnd <= it.et.length()) {
                            // Android 的组合标记是 NoCopySpan，复制 Editable 不会保留，必须经输入连接恢复范围。
                            it.et.onCreateInputConnection(EditorInfo())?.setComposingRegion(
                                entry.composingStart, entry.composingEnd)
                        }
                    }
                }
            }
        }
    }

    override fun onViewRecycled(holder: BaseViewHolder) {
        (holder as? EditTextHolder)?.let {
            it.saveEditorState()
            it.detaching = true
            it.et!!.clearFocus()
            it.boundEntry = null
        }
        super.onViewRecycled(holder)
    }

    /**
     * 卡片各级在"图标相对基准"上的额外缩进(dp)，逐级独立、便于单独微调。
     * 想精细调首页卡片的 L2 / L3，直接改这里的 `2 -> Xf`（L2）和 `3 -> Yf`（L3）即可，互不影响。
     */

    /** 卡片（TextView）行的按级左缩进：基准随图标宽，叠加每级独立的额外缩进。 */
    private fun setCardItemIndent(holder: TextViewHolder, level: Int) {
        val ll = holder.llClickable ?: return
        val lp = ll.layoutParams as? LinearLayout.LayoutParams ?: return
        val iconW = holder.iv?.let { iv ->
            val w = iv.layoutParams?.width ?: 0
            if (w > 0) w else (iv.drawable?.intrinsicWidth ?: 0)
        } ?: 0
        lp.leftMargin = cardItemIndentPx(level, iconW, density)
        ll.layoutParams = lp
    }

    /** 卡片行：状态图标与文字第一行视觉中心对齐（多行也只对齐第一行）。 */
    private fun alignCardRow(holder: TextViewHolder, baseTop: Int, bottom: Int, level: Int) {
        val tv = holder.tv ?: return
        val iv = holder.iv ?: return
        val iconH = (iv.layoutParams?.height?.takeIf { it > 0 }) ?: (iv.drawable?.intrinsicHeight ?: 0)
        val (iconTopRaw, textTop) = firstLineAlign(tv, iconH, baseTop)
        // 二、三级状态图标上移 0.5dp（T2/T3 −0.5dp）。
        val iconTop = if (level >= 2) (iconTopRaw - Math.round(0.5f * density)).coerceAtLeast(0) else iconTopRaw
        (iv.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.topMargin = iconTop
            iv.layoutParams = it
        }
        (tv.layoutParams as LinearLayout.LayoutParams).let {
            it.topMargin = textTop
            it.bottomMargin = bottom
            tv.layoutParams = it
        }
    }

    /**
     * 让一个高 [iconH] 的图标与 [tv] 第一行文字的**视觉中心**（按字体度量，而非行高几何中点）
     * 在 y 方向对齐，二者至少留 [baseTop] 的上边距。返回 (图标上边距, 文字上边距)。
     */
    private fun firstLineAlign(tv: TextView, iconH: Int, baseTop: Int): Pair<Int, Int> {
        return firstLineAlign(tv.paint.fontMetrics, tv.paddingTop, iconH, baseTop)
    }

    private fun getFinishedItemsCountStr(finishedCount: Int): String {
        return finishedItemsCountStr(mContext, finishedCount)
    }

    private fun setEventForTextViewItem(holder: TextViewHolder) {
        if (mTvItemClickCallback == null) {
            holder.llClickable!!.isClickable = false
            holder.spaceClickable!!.isClickable = false
            holder.llClickable.setBackgroundResource(0)
        } else {
            holder.llClickable!!.isClickable = true
            holder.llClickable.setBackgroundResource(R.drawable.selectable_item_background_light)
            holder.llClickable.setOnClickListener {
                mTvItemClickCallback!!.onItemClick(holder.bindingAdapterPosition)
            }
            holder.spaceClickable!!.isClickable = true
            holder.spaceClickable.setOnClickListener { v ->
                mTvItemClickCallback!!.onItemSpaceClick(v)
            }
        }
    }

    private fun setEventForTextViewItemMore(holder: TextViewHolder) {
        holder.llClickable!!.isClickable = true
        holder.llClickable.setBackgroundResource(0)
        holder.llClickable.setOnClickListener { v ->
            mTvItemClickCallback!!.onItemSpaceClick(v)
        }
        holder.spaceClickable!!.isClickable = true
        holder.spaceClickable.setOnClickListener { v ->
            mTvItemClickCallback!!.onItemSpaceClick(v)
        }
    }

    override fun getItemCount(): Int {
        val size = mItems!!.size
        return if (mType == TEXTVIEW) {
            if (mMaxItemCount == -1) {
                size
            } else {
                if (size <= mMaxItemCount) size else mMaxItemCount + 1
            }
        } else {
            getVisibleEditTextItemCount(size)
        }
    }

    private fun getVisibleEditTextItemCount(size: Int): Int {
        if (mExpanded) return size
        // 收起时显示到"已完成头部"(4)为止，隐藏底部已完成区的全部项。
        val headerIndex = mItems!!.indexOf("4")
        return if (headerIndex != -1) headerIndex + 1 else size
    }

    private fun notifyChecklistStructureChanged() {
        notifyDataSetChanged()
    }

    private class TextViewHolder(itemView: View?) : BaseViewHolder(itemView) {

        val llClickable: LinearLayout? = f(R.id.ll_check_list_tv)
        val spaceClickable: View? = f(R.id.space_checklist_item_tv)
        val iv: ImageView? = f(R.id.iv_check_list_state)
        val tv: TextView?  = f(R.id.tv_check_list)
    }

    open inner class EditTextHolder internal constructor(itemView: View?) : BaseViewHolder(itemView) {

        internal var boundEntry: ChecklistEditorItems.Entry? = null
        internal var detaching = false
        internal var rippleColor: Int? = null

        internal fun saveEditorState() {
            if (detaching) return
            val entry = boundEntry ?: return
            val editor = et ?: return
            entry.selectionStart = editor.selectionStart
            entry.selectionEnd = editor.selectionEnd
            entry.composingStart = BaseInputConnection.getComposingSpanStart(editor.text)
            entry.composingEnd = BaseInputConnection.getComposingSpanEnd(editor.text)
        }

        val flSeparator: FrameLayout? = f(R.id.fl_check_list_separator)
        val ivState: ImageView?       = f(R.id.iv_check_list_state)
        @JvmField val et: EditText?   = f(R.id.et_check_list)
        val ivIndent: ImageView?      = f(R.id.iv_check_list_indent)
        val ivOutdent: ImageView?     = f(R.id.iv_check_list_outdent)
        val ivExpandShrink: ImageView? = f(R.id.iv_check_list_expand_shrink)

        init {
            if (mType == EDITTEXT_EDITABLE) {
                if (!DeviceUtil.isMiuiButNotV5()) {
                    DisplayUtil.setSelectionHandlersColor(et,
                        App.defaultAccentBackground.representativeColor())
                }
                setupIvListeners()
                setupEtListeners()
                if (!DeviceUtil.isFlyme()) {
                    LineSpacingHelper.setTextCursorDrawable(
                        et, appAccent, cursorWidth,
                        normalLineCursorHeightVary, lastLineCursorHeightVary
                    )
                }
            } else {
                et!!.keyListener = null
            }

            if (mShouldAutoLink) {
                et!!.autoLinkMask = Linkify.ALL
            } else {
                et!!.autoLinkMask = 0
            }
        }

        /** 把状态图标的左缩进清零（控制标记行用）。 */
        fun resetIndent() {
            updateIconPosition(ivState!!, 0, 0)
            updateIconPosition(ivIndent!!, 0, 0)
            updateIconPosition(ivOutdent!!, 0, 0)
        }

        private fun updateIconPosition(view: ImageView, start: Int, top: Int) {
            val lp = view.layoutParams as RelativeLayout.LayoutParams
            if (lp.marginStart != start || lp.leftMargin != start || lp.topMargin != top) {
                lp.marginStart = start
                lp.leftMargin = start
                lp.topMargin = top
                view.layoutParams = lp
            }
        }

        /** 对一个真实项应用按级缩进、字号、颜色、与首行垂直居中。 */
        fun applyLevelStyle(item: String) {
            val level = CheckListHelper.levelOf(item)
            val finished = CheckListHelper.isFinished(item)

            // 字号 / 颜色（按级）。先设字号，lineHeight 才反映新字号。
            et!!.textSize = 20f * CheckListHelper.sizeRatioForLevel(level)
            val base = if (finished) textColorFinished() else textColorSecondary()
            val textColor = CheckListHelper.colorForLevel(base, level, finished)
            if (et.currentTextColor != textColor) et.setTextColor(textColor)

            val stateMargin = Math.round(detailIndentDp(level) * density)
            val stateH = ivState!!.layoutParams.height.takeIf { it > 0 } ?: (36 * density).toInt()

            // 状态图标与文字第一行"视觉中心"对齐（用字体度量，多行也只对齐第一行）。
            val baseTop = (3 * density).toInt()
            val (stateTopRaw, textTop) = firstLineAlign(et, stateH, baseTop)
            // 各级状态图标上下微调：一、二级 0，三级 −0.5dp。
            val iconNudgeDp = if (level >= 3) -0.5f else 0f
            val stateTop = (stateTopRaw + Math.round(iconNudgeDp * density)).coerceAtLeast(0)

            updateIconPosition(ivState, stateMargin, stateTop)

            val etLp = et.layoutParams as LinearLayout.LayoutParams
            if (etLp.topMargin != textTop) {
                etLp.topMargin = textTop
                et.layoutParams = etLp
            }

            // 缩进 / 反缩进箭头：跟随该级"清单项"颜色（已完成用已完成色，含分级透明度），与状态图标垂直居中。
            val arrowTint = CheckListHelper.colorForLevel(
                if (finished) textColorFinished() else textColorSecondary(), level, finished
            )
            val stateCenter = stateTop + stateH / 2

            val outW = ivOutdent!!.layoutParams.width.takeIf { it > 0 } ?: (28 * density).toInt()
            val outH = ivOutdent.layoutParams.height.takeIf { it > 0 } ?: (28 * density).toInt()
            val stateW = ivState.layoutParams.width.takeIf { it > 0 } ?: (36 * density).toInt()
            // 反缩进箭头与"上一级"状态图标列在 x 方向对齐：把箭头控件居中到父级状态图标控件列。
            val parentStateMargin = Math.round(detailIndentDp(level - 1) * density)
            val outMargin = (parentStateMargin + (stateW - outW) / 2).coerceAtLeast(0)
            updateIconPosition(ivOutdent, outMargin, (stateCenter - outH / 2).coerceAtLeast(0))
            setIconTint(ivOutdent, arrowTint)

            val inLp = ivIndent!!.layoutParams as RelativeLayout.LayoutParams
            val inH = ivIndent.layoutParams.height.takeIf { it > 0 } ?: (28 * density).toInt()
            val inTop = (stateCenter - inH / 2).coerceAtLeast(0)
            if (inLp.topMargin != inTop) {
                inLp.topMargin = inTop
                ivIndent.layoutParams = inLp
            }
            setIconTint(ivIndent, arrowTint)

            // 聚焦中的行（含缩进后保持焦点、或重新绑定时）刷新缩进/反缩进按钮的可见性。
            if (et.isFocused) refreshIndentOutdent() else hideIndentOutdent()
        }

        /** 详情页各级缩进（dp），逐级独立、便于单独微调。 */
        private fun detailIndentDp(level: Int): Float = when (level) {
            2 -> 36.5f
            3 -> 72f
            else -> 0f
        }

        /** 聚焦时按层级/边界刷新缩进、反缩进按钮的可见性。 */
        fun refreshIndentOutdent() {
            val pos = bindingAdapterPosition
            // 获焦时投递的刷新可能在插入新行、焦点已转移后执行，不能重新显示旧行按钮。
            if (et?.isFocused != true || pos == RecyclerView.NO_POSITION || pos >= mItems!!.size
                || !CheckListHelper.isItem(mItems!![pos]) || mDragging
            ) {
                hideIndentOutdent()
                return
            }
            val canIndent = CheckListHelper.canIndent(mItems, pos)
            ivIndent!!.visibility = if (canIndent) View.VISIBLE else View.INVISIBLE
            ivIndent.isClickable = canIndent
            val canOutdent = CheckListHelper.canOutdent(mItems, pos)
            ivOutdent!!.visibility = if (canOutdent) View.VISIBLE else View.INVISIBLE
            ivOutdent.isClickable = canOutdent
        }

        fun hideIndentOutdent() {
            ivIndent!!.visibility = View.INVISIBLE
            ivIndent.isClickable = false
            ivOutdent!!.visibility = View.INVISIBLE
            ivOutdent.isClickable = false
        }

        private fun changeLevel(delta: Int) {
            val pos = bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || pos >= mItems!!.size) return
            val item = mItems!![pos] ?: return
            if (!CheckListHelper.isItem(item)) return
            if (delta > 0 && !CheckListHelper.canIndent(mItems, pos)) return
            if (delta < 0 && !CheckListHelper.canOutdent(mItems, pos)) return

            val before = CheckListHelper.toCheckListStr(mItems)
            val snapshot = ArrayList(mItems!!)
            val newLevel = (CheckListHelper.levelOf(item) + delta).coerceIn(1, CheckListHelper.MAX_LEVEL)
            mItems!![pos] = CheckListHelper.withLevel(item, newLevel)
            // 反缩进父项等会让子项需要重新归一化层级（消除跳空/孤儿）。
            CheckListHelper.normalizeLevels(mItems!!)
            // 缩进把未完成项移到已完成项下时，已完成祖先链改回未完成（维持完成态不变量）。
            CheckListHelper.normalizeCompletion(mItems!!)

            // 当前项以外是否有项被改动：有则整列重绘，没有则只更新当前行以保住焦点（缩进的常见情况）。
            var structural = mItems!!.size != snapshot.size
            if (!structural) {
                for (i in mItems!!.indices) {
                    if (i != pos && mItems!![i] != snapshot[i]) { structural = true; break }
                }
            }
            if (structural) {
                notifyChecklistStructureChanged()
            } else {
                applyLevelStyle(mItems!![pos]!!)
                refreshIndentOutdent()
            }
            mActionCallback?.onAction(before, CheckListHelper.toCheckListStr(mItems))
        }

        private fun setupIvListeners() {
            ivState!!.setOnTouchListener { _, event ->
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION || pos >= mItems!!.size) return@setOnTouchListener false
                val item: String = mItems!![pos]!!
                if (event.action == MotionEvent.ACTION_DOWN && mDragging
                    && item != "2" && item != "3" && item != "4"
                ) {
                    if (mIvStateTouchCallback != null) {
                        mIvStateTouchCallback!!.onTouch(pos)
                    }
                    return@setOnTouchListener true
                }
                false
            }

            ivState.setOnClickListener {
                var pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION || pos >= mItems!!.size) return@setOnClickListener

                val item: String = mItems!![pos]!!
                val state = item[0]
                if (mDragging && state != '2') {
                    return@setOnClickListener
                }

                if (state == '2') {
                    insertItem(CheckListHelper.toCheckListStr(mItems), it, pos, "")
                    return@setOnClickListener
                }

                if (!CheckListHelper.isItem(item)) return@setOnClickListener

                KeyboardUtil.hideKeyboard(et)

                // 组感知完成：委托给 ChecklistCompletion（规则 3 的唯一真理来源）。
                val before: String = CheckListHelper.toCheckListStr(mItems)
                val real = ArrayList<String?>(mItems!!)
                real.remove("2"); real.remove("3"); real.remove("4")
                var realPos = 0
                for (i in 0 until pos) if (CheckListHelper.isItem(mItems!![i])) realPos++
                ChecklistCompletion.toggle(real, realPos)
                val after: String = CheckListHelper.toCheckListStr(real)
                val rebuilt = CheckListHelper.toCheckListItems(after, false)

                mWatchEditTextChange = false
                mItems!!.clear()
                mItems!!.addAll(rebuilt)
                mWatchEditTextChange = true

                notifyChecklistStructureChanged()

                if (mActionCallback != null) {
                    mActionCallback!!.onAction(before, after)
                }
                if (mExpandShrinkCallback != null) {
                    mExpandShrinkCallback!!.updateChecklistHeight(mExpanded, mItems, false)
                }
            }

            ivIndent!!.setOnClickListener { changeLevel(+1) }
            ivOutdent!!.setOnClickListener { changeLevel(-1) }

            ivExpandShrink!!.setOnClickListener {
                mExpanded = !mExpanded
                if (mExpandShrinkCallback != null) {
                    mExpandShrinkCallback!!.updateChecklistHeight(mExpanded, mItems, true)
                }
                notifyChecklistStructureChanged()
                if (mExpanded) {
                    ivExpandShrink.contentDescription =
                        mContext!!.getString(R.string.cd_checklist_shrink_finished_items)
                } else {
                    ivExpandShrink.contentDescription =
                        mContext!!.getString(R.string.cd_checklist_expand_finished_items)
                }
            }
        }

        private fun setupEtListeners() {
            if (mEtTouchListener != null) {
                et!!.setOnTouchListener(mEtTouchListener)
            }
            if (mEtClickListener != null) {
                et!!.setOnClickListener(mEtClickListener)
            }
            if (mEtLongClickListener != null) {
                et!!.setOnLongClickListener(mEtLongClickListener)
            }

            et!!.addTextChangedListener(object : TextWatcher {
                private var mBefore: String? = null
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                    if (!mWatchEditTextChange || bindingAdapterPosition == RecyclerView.NO_POSITION) {
                        mBefore = null
                        return
                    }
                    mBefore = CheckListHelper.toCheckListStr(mItems)
                }

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                }

                override fun afterTextChanged(s: Editable) {
                    if (!mWatchEditTextChange) return
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION || pos >= mItems!!.size) return
                    val item = mItems!![pos] ?: return
                    if (CheckListHelper.isItem(item)) {
                        mItems!![pos] = CheckListHelper.withText(item, s.toString())
                    }
                    if (mActionCallback != null && mBefore != null) {
                        mActionCallback!!.onAction(
                            mBefore, CheckListHelper.toCheckListStr(mItems)
                        )
                    }

                    if (mExpandShrinkCallback != null) {
                        et.post {
                            mExpandShrinkCallback!!.updateChecklistHeight(mExpanded, mItems, false)
                        }
                    }
                }
            })

            et.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    mFocusedEntry?.takeIf { it !== boundEntry }?.finishComposition()
                    mFocusedEntry = boundEntry
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos < mItems!!.size && mItems!![pos]!![0] == '2') {
                        insertItem(CheckListHelper.toCheckListStr(mItems), v, pos, "")
                    } else if (pos != RecyclerView.NO_POSITION && pos < mItems!!.size) {
                        v.post {
                            refreshIndentOutdent()
                        }
                    }
                } else {
                    saveEditorState()
                    if (!detaching && et.isAttachedToWindow && mFocusedEntry === boundEntry) {
                        mFocusedEntry = null
                    }
                    hideIndentOutdent()
                }
            }

            et.setOnKeyListener listener@ { v, keyCode, event ->
                val action = event.action
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION || pos >= mItems!!.size) return@listener false
                if (action == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_ENTER) {
                        val cursorPos = et.selectionEnd
                        val etLength = et.text.length
                        if (cursorPos == etLength) {
                            insertItem(CheckListHelper.toCheckListStr(mItems), v, pos, "")
                        } else {
                            val before: String = CheckListHelper.toCheckListStr(mItems)
                            val current: String = mItems!![pos]!!
                            // current = 状态位 + 层级位 + 文本；光标 cursorPos 在文本内。
                            val newCurrent = current.substring(0, cursorPos + 2)
                            val next = current.substring(cursorPos + 2)
                            mItems!![pos] = newCurrent
                            notifyItemChanged(pos)
                            insertItem(before, v, pos, next)
                        }
                        return@listener true
                    } else if (keyCode == KeyEvent.KEYCODE_DEL) {
                        if ((pos != 0 && et.selectionEnd == 0)
                            || (pos == 0 && CheckListHelper.isEmptyItem(mItems!![0]))
                        ) {
                            removeItem(v, pos, false)
                            return@listener true
                        }
                    }
                }
                false
            }
        }

        /**
         * Inserting occurs in three ways: click ImageView "add", click EditText "new item"
         * and press enter when focus is on any EditTexts.
         * 新项继承当前行层级；"添加新项"行(2)新建一级项。
         */
        private fun insertItem(before: String?, v: View, pos: Int, preset: String) {
            val cur = mItems!![pos]!!
            val state = cur[0]
            if (state == '2') {
                mItems!![pos] = CheckListHelper.makeItem('0', 1, "")
                mItems!!.add(pos + 1, "2")
            } else {
                val level = CheckListHelper.levelOf(cur)
                mItems!!.add(pos + 1, CheckListHelper.makeItem(state, level, preset))
            }
            notifyChecklistStructureChanged()
            if (mItemsChangeCallback != null) {
                v.post {
                    mItemsChangeCallback!!.onInsert(if (state == '2') pos else pos + 1)
                }
            }

            if (mActionCallback != null) {
                mActionCallback!!.onAction(
                    before, CheckListHelper.toCheckListStr(mItems)
                )
            }

            if (mExpandShrinkCallback != null) {
                v.post {
                    mExpandShrinkCallback!!.updateChecklistHeight(mExpanded, mItems, false)
                }
            }
        }

        private fun removeItem(v: View, posIn: Int, deleteByClick: Boolean) {
            val before: String = CheckListHelper.toCheckListStr(mItems)
            val current: String = mItems!![posIn]!!
            var pos = posIn
            val posToFocus: Int
            if (pos != 0) {
                if (mItems!![pos - 1]!! == "4") { // delete first finished item.
                    if (pos - 4 == -1) { // there is no unfinished item.
                        if (!deleteByClick) { // user used keyboard to delete this item.
                            if (!CheckListHelper.isEmptyItem(current)) {
                                mItems!!.add(0, CheckListHelper.makeItem('0', 1, ""))
                                pos++
                                posToFocus = 0
                            } else {
                                posToFocus = -1
                            }
                        } else {
                            posToFocus = -1
                        }
                    } else {
                        posToFocus = pos - 4
                    }
                } else {
                    posToFocus = pos - 1
                }
            } else {
                posToFocus = -1
            }

            val cursorPos: Int
            if (pos == 0) {
                cursorPos = -1
            } else {
                val itemToFocus: String = mItems!![if (posToFocus == -1) 0 else posToFocus]!!
                cursorPos = CheckListHelper.textOf(itemToFocus).length
                if (!deleteByClick && posToFocus != -1) {
                    val append = CheckListHelper.textOf(current)
                    mItems!![posToFocus] = CheckListHelper.withText(
                        itemToFocus, CheckListHelper.textOf(itemToFocus) + append
                    )
                }
            }

            mItems!!.removeAt(pos)
            if (mItems!!.isNotEmpty() && mItems!![mItems!!.size - 1]!! == "4") {
                mItems!!.remove("3")
                mItems!!.remove("4")
            }
            // 删除中间项可能让其子项失去直接上一级父项，归一化层级消除跳空/孤儿；并维持完成态不变量。
            CheckListHelper.normalizeLevels(mItems!!)
            CheckListHelper.normalizeCompletion(mItems!!)
            notifyChecklistStructureChanged()

            if (mItemsChangeCallback != null) {
                if (deleteByClick) {
                    mItemsChangeCallback!!.onRemove(pos, current, -1)
                } else {
                    v.post {
                        mItemsChangeCallback!!.onRemove(posToFocus, null, cursorPos)
                    }
                }
            }

            if (mActionCallback != null) {
                mActionCallback!!.onAction(
                    before, CheckListHelper.toCheckListStr(mItems)
                )
            }

            if (mExpandShrinkCallback != null) {
                v.post {
                    mExpandShrinkCallback!!.updateChecklistHeight(mExpanded, mItems, false)
                }
            }
        }
    }

    companion object {
        const val TAG: String = "CheckListAdapter"

        /**
         * 只读展示形态。记事卡片上的清单已改用
         * [com.ywwynm.everythingdone.views.ThingCardChecklistView]（RecyclerView 的子行不
         * 跟随自身宽度重新折行，在侧边封面下会把末尾条目挤出卡片），因此这一形态目前**没有
         * 使用点**，属于待清理的死代码；本类当前只服务详情页的 EDITTEXT 形态。
         * 见 docs/features/thing-card-content-limits/followups.md。
         */
        const val TEXTVIEW: Int            = 0
        const val EDITTEXT_EDITABLE: Int   = 1
        const val EDITTEXT_UNEDITABLE: Int = 2

        /** 卡片上渲染出这么多条及以上时，清单项字号收到最小的 14sp，不再随条数放大。 */
        private const val COMPACT_TEXT_SIZE_ITEM_COUNT = 8

        /**
         * 卡片上渲染 [visible] 条时的基准字号（sp）。
         *
         * 与行上边距、按级缩进、首行对齐一起构成卡片清单的排版规则，由
         * [com.ywwynm.everythingdone.views.ThingCardChecklistView] 与本适配器共用——
         * 两处各写一遍，迟早会在某次改动后不一致。
         */
        @JvmStatic
        fun cardBaseTextSizeSp(visible: Int, fixedTextSize: Float?): Float {
            if (fixedTextSize != null) return fixedTextSize
            if (visible >= COMPACT_TEXT_SIZE_ITEM_COUNT) return 14f
            return -4 * visible / 7f + 130f / 7
        }

        /** 与 [cardBaseTextSizeSp] 配套的行上边距（px）。 */
        @JvmStatic
        fun cardItemTopMarginPx(visible: Int, fixedTextSize: Float?, density: Float): Int {
            if (fixedTextSize != null || visible >= COMPACT_TEXT_SIZE_ITEM_COUNT) {
                return (2 * density).toInt()
            }
            val baseSize = cardBaseTextSizeSp(visible, null)
            return (-2 * baseSize / 3 + 34f / 3).toInt()
        }

        /** 第 [level] 级行相对行首的左缩进（px）；[iconWidthPx] 为状态图标的实际宽度。 */
        @JvmStatic
        fun cardItemIndentPx(level: Int, iconWidthPx: Int, density: Float): Int {
            val baseStep = if (iconWidthPx > 0) {
                (iconWidthPx * 0.63f).toInt()
            } else {
                (12 * density).toInt()
            }
            return (level - 1).coerceAtLeast(0) * baseStep +
                    Math.round(cardIndentExtraDp(level) * density)
        }

        private fun cardIndentExtraDp(level: Int): Float = when (level) {
            2 -> 4f
            3 -> 7.5f
            else -> 0f
        }

        /**
         * 让一个高 [iconH] 的图标与首行文字的视觉中心在 y 方向对齐，二者至少留 [baseTop]
         * 的上边距。返回 (图标上边距, 文字上边距)。
         */
        @JvmStatic
        fun firstLineAlign(
            fm: Paint.FontMetrics,
            textPaddingTop: Int,
            iconH: Int,
            baseTop: Int
        ): Pair<Int, Int> {
            val glyphCenter = textPaddingTop + (-fm.top) + (fm.ascent + fm.descent) / 2f
            val d = iconH / 2f - glyphCenter   // 文字需比图标多下移的量
            return if (d >= 0) {
                Pair(baseTop, baseTop + d.toInt())
            } else {
                Pair(baseTop + (-d).toInt(), baseTop)
            }
        }

        /**
         * 卡片上清单的条目预处理：去掉控制行；开启「折叠已完成」时把底部已完成区收成一行
         * 汇总。原地修改并返回同一个列表。
         *
         * 渲染卡片清单的 [com.ywwynm.everythingdone.views.ThingCardChecklistView] 与本适配器
         * 共用这一份，避免两处各写一遍。
         */
        @JvmStatic
        fun prepareCardItems(context: Context?, items: MutableList<String?>?): MutableList<String?>? {
            if (items == null) return null
            items.remove("2")
            items.remove("3")
            items.remove("4")
            if (!FrequentSettings.getBoolean(Def.Meta.KEY_SIMPLE_FCLI)) return items
            // 只折叠"分隔线以下"的底部已完成区，即从第一个已完成组根起。顶部就地完成的
            // 深层项不折叠。见 decisions.md（SIMPLE_FCLI）。
            var boundary = -1
            val size = items.size
            for (i in 0 until size) {
                if (CheckListHelper.isFinished(items[i])
                    && CheckListHelper.ownerIndexOf(items, i) == -1
                ) {
                    boundary = i
                    break
                }
            }
            if (boundary != -1) {
                val finishedCount = size - boundary
                for (i in boundary until size) {
                    items.removeAt(boundary)
                }
                // 汇总行用新格式：状态位 '1'(已完成) + 层级位 '1' + 文本；否则渲染时 textOf 的
                // substring(2) 会吃掉文本首字符，且首字符若是 1/2/3 还会被误当层级。
                items.add("11" + finishedItemsCountStr(context, finishedCount))
            }
            return items
        }

        @JvmStatic
        fun finishedItemsCountStr(context: Context?, finishedCount: Int): String {
            if (context == null) return finishedCount.toString()
            return LocalizedText.quantity(context, R.plurals.l10n_checklist_finished, finishedCount)
        }

        /** 卡片上未完成条目的文字色；[thingColor] 为 0 时沿用浅色卡片的白系前景。 */
        @JvmStatic
        fun cardTextColorSecondary(thingColor: Int): Int {
            return if (isDarkForeground(thingColor)) black_76p else white_76p
        }

        /** 卡片上已完成条目的文字色。 */
        @JvmStatic
        fun cardTextColorFinished(thingColor: Int): Int {
            return if (isDarkForeground(thingColor)) black_50p else white_50p
        }

        @JvmStatic
        fun isDarkForeground(thingColor: Int): Boolean {
            if (thingColor == 0) return false
            return BackgroundUtil.isLight(thingColor)
        }

        private var white_76p: Int = 0
        private var white_50p: Int = 0
        private var black_76p: Int = 0
        private var black_50p: Int = 0
        private var density: Float = 0f

        private var appAccent: Int = 0
        private var cursorWidth: Int = 0
        private var normalLineCursorHeightVary: Int = 0
        private var lastLineCursorHeightVary: Int = 0

        init {
            val context: Context = App.getApp()!!
            white_76p = ContextCompat.getColor(context, R.color.white_76p)
            white_50p = ContextCompat.getColor(context, R.color.white_50p)
            black_76p = ContextCompat.getColor(context, R.color.black_76p)
            black_50p = ContextCompat.getColor(context, R.color.black_50p)
            density = DisplayUtil.getScreenDensity(context)

            appAccent = App.defaultAccentBackground.representativeColor()
            cursorWidth = (1.5 * density).toInt()
            normalLineCursorHeightVary = (-2 * density).toInt()
            lastLineCursorHeightVary = (-1 * density).toInt()
        }
    }
}
