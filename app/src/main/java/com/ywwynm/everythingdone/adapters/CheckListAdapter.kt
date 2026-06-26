@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.adapters

import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import android.text.Editable
import android.text.TextWatcher
import android.text.util.Linkify
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.ChecklistCompletion
import com.ywwynm.everythingdone.helpers.LineSpacingHelper
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DeviceUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.KeyboardUtil
import com.ywwynm.everythingdone.utils.LocaleUtil

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

    private var mItems: MutableList<String?>? = items

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
        mItems = items
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
    private fun dark(): Boolean {
        if (mThingColor == 0) return false  // unset → keep legacy white behaviour
        return BackgroundUtil.isLight(mThingColor)
    }

    private fun textColorSecondary(): Int {
        return if (dark()) black_76p else white_76p
    }

    private fun textColorFinished(): Int {
        return if (dark()) black_50p else white_50p
    }

    /**
     * Tint a checklist-row image so that the white-pixel PNGs become visible on a light card.
     */
    private fun tintRowIcon(iv: ImageView?) {
        if (iv == null) return
        if (dark()) {
            androidx.core.widget.ImageViewCompat.setImageTintList(
                iv, android.content.res.ColorStateList.valueOf(android.graphics.Color.BLACK)
            )
        } else {
            androidx.core.widget.ImageViewCompat.setImageTintList(iv, null)
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

    private fun installIconRipple(iv: ImageView?) {
        val rippleColor = if (mThingColor == 0) {
            BackgroundUtil.RIPPLE_LIGHT
        } else {
            BackgroundUtil.thingRippleColor(mThingColor)
        }
        BackgroundUtil.installCircleRipple(iv, rippleColor)
    }

    private fun removeItemsForTextView() {
        if (mType == TEXTVIEW) {
            mItems!!.remove("2")
            mItems!!.remove("3")
            mItems!!.remove("4")

            if (FrequentSettings.getBoolean(Def.Meta.KEY_SIMPLE_FCLI)) {
                // 只折叠"分隔线以下"的底部已完成区，即从第一个已完成组根起。顶部就地完成的
                // 深层项不折叠。见 decisions.md（SIMPLE_FCLI）。
                var boundary = -1
                val size = mItems!!.size
                for (i in 0 until size) {
                    if (CheckListHelper.isFinished(mItems!![i])
                        && CheckListHelper.ownerIndexOf(mItems, i) == -1
                    ) {
                        boundary = i
                        break
                    }
                }
                if (boundary != -1) {
                    val finishedCount = size - boundary
                    for (i in boundary until size) {
                        mItems!!.removeAt(boundary)
                    }
                    val newItem = "1" + getFinishedItemsCountStr(finishedCount)
                    mItems!!.add(newItem)
                }
            }
        }
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
                val fixedTextSize = mFixedTextSize
                val baseSize: Float
                if (fixedTextSize != null) {
                    baseSize = fixedTextSize
                    params.setMargins(0, (2 * density).toInt(), 0, params.bottomMargin)
                } else if ((mMaxItemCount != -1 && size >= mMaxItemCount) || mMaxItemCount == -1) {
                    baseSize = 14f
                    params.setMargins(0, (2 * density).toInt(), 0, params.bottomMargin)
                } else {
                    baseSize = -4 * size / 7f + 130f / 7
                    val mt = -2 * baseSize / 3 + 34f / 3
                    params.setMargins(0, mt.toInt(), 0, params.bottomMargin)
                }
                holder.tv.textSize = baseSize * CheckListHelper.sizeRatioForLevel(level)

                holder.tv.text = CheckListHelper.textOf(stateContent)
                alignCardRow(holder, params.topMargin, params.bottomMargin, level)
                setCardItemIndent(holder, level)

                setEventForTextViewItem(holder)
            }
        } else {
            val holder = viewHolder as EditTextHolder
            holder.flSeparator!!.visibility = View.GONE
            holder.ivState!!.visibility = View.VISIBLE
            holder.ivState.isClickable = true
            holder.ivIndent!!.visibility = View.INVISIBLE
            holder.ivIndent.isClickable = false
            holder.ivOutdent!!.visibility = View.INVISIBLE
            holder.ivOutdent.isClickable = false
            holder.ivExpandShrink!!.visibility = View.GONE

            holder.et!!.isEnabled = true
            holder.et.visibility = View.VISIBLE
            holder.et.paint.textSkewX = 0f

            val flags = holder.et.paintFlags
            holder.et.paintFlags = flags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

            holder.et.textSize = 20f
            holder.et.hint = ""
            holder.resetIndent()

            val params = holder.et.layoutParams as LinearLayout.LayoutParams
            params.width = LinearLayout.LayoutParams.MATCH_PARENT
            params.topMargin = 0

            mWatchEditTextChange = false
            tintRowIcon(holder.ivState)
            tintRowIcon(holder.ivIndent)
            tintRowIcon(holder.ivOutdent)
            tintRowIcon(holder.ivExpandShrink)
            installIconRipple(holder.ivState)
            installIconRipple(holder.ivIndent)
            installIconRipple(holder.ivOutdent)
            installIconRipple(holder.ivExpandShrink)
            val stateContent: String = mItems!![position]!!
            val state = stateContent[0]
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
                holder.et.setText(CheckListHelper.textOf(stateContent))
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
                holder.et.paintFlags = flags or Paint.STRIKE_THRU_TEXT_FLAG
                holder.et.setText(CheckListHelper.textOf(stateContent))
                holder.applyLevelStyle(stateContent)
            } else if (state == '2') {
                params.topMargin = (density * 4).toInt()
                holder.ivState.setImageResource(R.drawable.checklist_add)
                val newItem = mContext!!.getString(R.string.hint_new_item)
                holder.ivState.contentDescription = newItem
                holder.et.hint = newItem
                holder.et.setHintTextColor(textColorFinished())
                holder.et.setText("")
            } else if (state == '3') {
                holder.ivState.visibility = View.GONE
                holder.ivIndent.visibility = View.GONE
                holder.ivOutdent.visibility = View.GONE
                holder.et.visibility = View.GONE
                holder.flSeparator.visibility = View.VISIBLE
                val sep: View? = holder.flSeparator.getChildAt(0)
                if (sep != null) {
                    val d: Drawable? = sep.background
                    if (d != null) {
                        val color = if (dark()) 0x42000000 else 0x42FFFFFF
                        d.mutate().setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
                    }
                }
            } else if (state == '4') {
                params.topMargin = (density * 6).toInt()
                holder.ivState.setImageResource(R.drawable.checklist_finished)
                holder.ivState.isClickable = false

                var finishedCount = 0
                for (item in mItems!!) if (item!![0] == '1') finishedCount++
                val finishedItemsCountStr = getFinishedItemsCountStr(finishedCount) + " "
                holder.ivState.contentDescription = finishedItemsCountStr
                params.width = LinearLayout.LayoutParams.WRAP_CONTENT

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

                holder.et.isEnabled = false
                holder.et.setText(finishedItemsCountStr)
                holder.et.setTextColor(textColorFinished())
                holder.et.textSize = 16f
                holder.et.paint.textSkewX = -0.20f
            }
            mWatchEditTextChange = true
        }
    }

    /**
     * 卡片各级在"图标相对基准"上的额外缩进(dp)，逐级独立、便于单独微调。
     * 想精细调首页卡片的 L2 / L3，直接改这里的 `2 -> Xf`（L2）和 `3 -> Yf`（L3）即可，互不影响。
     */
    private fun cardIndentExtraDp(level: Int): Float = when (level) {
        2 -> 4f
        3 -> 7.5f
        else -> 0f
    }

    /** 卡片（TextView）行的按级左缩进：基准随图标宽，叠加每级独立的额外缩进。 */
    private fun setCardItemIndent(holder: TextViewHolder, level: Int) {
        val ll = holder.llClickable ?: return
        val lp = ll.layoutParams as? LinearLayout.LayoutParams ?: return
        val iconW = holder.iv?.let { iv ->
            val w = iv.layoutParams?.width ?: 0
            if (w > 0) w else (iv.drawable?.intrinsicWidth ?: 0)
        } ?: 0
        val baseStep = if (iconW > 0) (iconW * 0.63f).toInt() else (12 * density).toInt()
        lp.leftMargin = (level - 1).coerceAtLeast(0) * baseStep +
                Math.round(cardIndentExtraDp(level) * density)
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
        val fm = tv.paint.fontMetrics
        // 从文字内容顶端到首行字形视觉中心的距离。
        val glyphCenter = tv.paddingTop + (-fm.top) + (fm.ascent + fm.descent) / 2f
        val d = iconH / 2f - glyphCenter   // 文字需比图标多下移的量
        return if (d >= 0) {
            Pair(baseTop, baseTop + d.toInt())
        } else {
            Pair(baseTop + (-d).toInt(), baseTop)
        }
    }

    private fun getFinishedItemsCountStr(finishedCount: Int): String {
        if (LocaleUtil.isChinese(mContext)) {
            val str = mContext!!.getString(R.string.some_checklist_items_finished)
            return String.format(str, finishedCount)
        } else {
            var str = "$finishedCount item"
            if (finishedCount > 1) str += "s"
            return "$str finished"
        }
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
                mTvItemClickCallback!!.onItemClick(holder.adapterPosition)
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
            val lp = ivState!!.layoutParams as RelativeLayout.LayoutParams
            lp.marginStart = 0
            lp.leftMargin = 0
            ivState.layoutParams = lp
        }

        /** 对一个真实项应用按级缩进、字号、颜色、与首行垂直居中。 */
        fun applyLevelStyle(item: String) {
            val level = CheckListHelper.levelOf(item)
            val finished = CheckListHelper.isFinished(item)

            // 字号 / 颜色（按级）。先设字号，lineHeight 才反映新字号。
            et!!.textSize = 20f * CheckListHelper.sizeRatioForLevel(level)
            val base = if (finished) textColorFinished() else textColorSecondary()
            et.setTextColor(CheckListHelper.colorForLevel(base, level, finished))

            val stateMargin = Math.round(detailIndentDp(level) * density)
            val stateH = ivState!!.layoutParams.height.takeIf { it > 0 } ?: (36 * density).toInt()

            // 状态图标与文字第一行"视觉中心"对齐（用字体度量，多行也只对齐第一行）。
            val baseTop = (3 * density).toInt()
            val (stateTopRaw, textTop) = firstLineAlign(et, stateH, baseTop)
            // 各级状态图标上下微调：一、二级 0，三级 −0.5dp。
            val iconNudgeDp = if (level >= 3) -0.5f else 0f
            val stateTop = (stateTopRaw + Math.round(iconNudgeDp * density)).coerceAtLeast(0)

            val sLp = ivState.layoutParams as RelativeLayout.LayoutParams
            sLp.marginStart = stateMargin
            sLp.leftMargin = stateMargin
            sLp.topMargin = stateTop
            ivState.layoutParams = sLp

            val etLp = et.layoutParams as LinearLayout.LayoutParams
            etLp.topMargin = textTop
            et.layoutParams = etLp

            // 缩进 / 反缩进箭头：跟随该级"清单项"颜色（已完成用已完成色，含分级透明度），与状态图标垂直居中。
            val arrowTint = CheckListHelper.colorForLevel(
                if (finished) textColorFinished() else textColorSecondary(), level, finished
            )
            val stateCenter = stateTop + stateH / 2

            val outLp = ivOutdent!!.layoutParams as RelativeLayout.LayoutParams
            val outW = ivOutdent.layoutParams.width.takeIf { it > 0 } ?: (28 * density).toInt()
            val outH = ivOutdent.layoutParams.height.takeIf { it > 0 } ?: (28 * density).toInt()
            val stateW = ivState.layoutParams.width.takeIf { it > 0 } ?: (36 * density).toInt()
            // 反缩进箭头与"上一级"状态图标列在 x 方向对齐：把箭头控件居中到父级状态图标控件列。
            val parentStateMargin = Math.round(detailIndentDp(level - 1) * density)
            val outMargin = (parentStateMargin + (stateW - outW) / 2).coerceAtLeast(0)
            outLp.marginStart = outMargin
            outLp.leftMargin = outMargin
            outLp.topMargin = (stateCenter - outH / 2).coerceAtLeast(0)
            ivOutdent.layoutParams = outLp
            androidx.core.widget.ImageViewCompat.setImageTintList(
                ivOutdent, android.content.res.ColorStateList.valueOf(arrowTint)
            )

            val inLp = ivIndent!!.layoutParams as RelativeLayout.LayoutParams
            val inH = ivIndent.layoutParams.height.takeIf { it > 0 } ?: (28 * density).toInt()
            inLp.topMargin = (stateCenter - inH / 2).coerceAtLeast(0)
            ivIndent.layoutParams = inLp
            androidx.core.widget.ImageViewCompat.setImageTintList(
                ivIndent, android.content.res.ColorStateList.valueOf(arrowTint)
            )

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
            val pos = adapterPosition
            if (pos == RecyclerView.NO_POSITION || pos >= mItems!!.size
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
            val pos = adapterPosition
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
                val pos = adapterPosition
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
                var pos = adapterPosition
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
                    mBefore = CheckListHelper.toCheckListStr(mItems)
                }

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                }

                override fun afterTextChanged(s: Editable) {
                    if (!mWatchEditTextChange) return
                    val pos = adapterPosition
                    if (pos == RecyclerView.NO_POSITION || pos >= mItems!!.size) return
                    val item = mItems!![pos] ?: return
                    if (CheckListHelper.isItem(item)) {
                        mItems!![pos] = CheckListHelper.withText(item, s.toString())
                    }
                    if (mActionCallback != null) {
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
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos < mItems!!.size && mItems!![pos]!![0] == '2') {
                        insertItem(CheckListHelper.toCheckListStr(mItems), v, pos, "")
                    } else if (pos != RecyclerView.NO_POSITION && pos < mItems!!.size) {
                        v.post {
                            refreshIndentOutdent()
                        }
                    }
                } else {
                    hideIndentOutdent()
                }
            }

            et.setOnKeyListener listener@ { v, keyCode, event ->
                val action = event.action
                val pos = adapterPosition
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

        const val TEXTVIEW: Int            = 0
        const val EDITTEXT_EDITABLE: Int   = 1
        const val EDITTEXT_UNEDITABLE: Int = 2

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
