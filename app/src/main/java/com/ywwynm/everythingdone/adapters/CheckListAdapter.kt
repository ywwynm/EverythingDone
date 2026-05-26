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
import android.widget.TextView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.CheckListHelper
import com.ywwynm.everythingdone.helpers.LineSpacingHelper
import com.ywwynm.everythingdone.utils.DeviceUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.KeyboardUtil
import com.ywwynm.everythingdone.utils.LocaleUtil

/**
 * Created by ywwynm on 2015/9/17.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
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

    /** True when the foreground should be drawn black-side rather than white-side. */
    private fun dark(): Boolean {
        if (mThingColor == 0) return false  // unset → keep legacy white behaviour
        return com.ywwynm.everythingdone.utils.BackgroundUtil.isLight(mThingColor)
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

    private fun removeItemsForTextView() {
        if (mType == TEXTVIEW) {
            mItems!!.remove("2")
            mItems!!.remove("3")
            mItems!!.remove("4")

            if (FrequentSettings.getBoolean(Def.Meta.KEY_SIMPLE_FCLI)) {
                var firstFinishedIndex = -1
                val size = mItems!!.size
                for (i in 0 until size) {
                    if (mItems!![i]!!.startsWith("1")) {
                        firstFinishedIndex = i
                        break
                    }
                }
                if (firstFinishedIndex != -1) {
                    val finishedCount = size - firstFinishedIndex
                    for (i in firstFinishedIndex until size) {
                        mItems!!.removeAt(firstFinishedIndex)
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
                holder.tv.textSize = 18f
                holder.tv.setTextColor(textColorSecondary())
                holder.tv.text = "..."
                holder.tv.contentDescription =
                    mContext!!.getString(R.string.cd_checklist_more_items)
                params.setMargins((density * 8).toInt(), 0, 0, params.bottomMargin)
                setEventForTextViewItemMore(holder)
            } else {
                holder.iv!!.visibility = View.VISIBLE
                val flag = holder.tv.paintFlags
                val stateContent: String = mItems!![position]!!
                val state = stateContent[0]
                if (state == '0') {
                    holder.iv.setImageResource(
                        if (dark)
                            R.drawable.checklist_unchecked_card_black
                        else R.drawable.checklist_unchecked_card
                    )
                    holder.iv.contentDescription =
                        mContext!!.getString(R.string.cd_checklist_unfinished_item)
                    holder.tv.setTextColor(textColorSecondary())
                    holder.tv.paintFlags = flag and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                } else if (state == '1') {
                    holder.iv.setImageResource(
                        if (dark)
                            R.drawable.checklist_checked_card_black
                        else R.drawable.checklist_checked_card
                    )
                    holder.iv.contentDescription =
                        mContext!!.getString(R.string.cd_checklist_finished_item)
                    holder.tv.setTextColor(textColorFinished())
                    if (FrequentSettings.getBoolean(Def.Meta.KEY_SIMPLE_FCLI)) {
                        holder.tv.paintFlags = flag and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    } else {
                        holder.tv.paintFlags = flag or Paint.STRIKE_THRU_TEXT_FLAG
                    }
                }

                val size = mItems!!.size
                if ((mMaxItemCount != -1 && size >= mMaxItemCount) || mMaxItemCount == -1) {
                    holder.tv.textSize = 14f
                    params.setMargins(0, (2 * density).toInt(), 0, params.bottomMargin)
                } else {
                    val textSize = -4 * size / 7f + 130f / 7
                    holder.tv.textSize = textSize
                    val mt = -2 * textSize / 3 + 34f / 3
                    params.setMargins(0, mt.toInt(), 0, params.bottomMargin)
                }

                holder.tv.text = stateContent.substring(1, stateContent.length)
                params.setMargins(0, params.topMargin, 0, params.bottomMargin)

                setEventForTextViewItem(holder)
            }
        } else {
            val holder = viewHolder as EditTextHolder
            holder.flSeparator!!.visibility = View.GONE
            holder.ivState!!.visibility = View.VISIBLE
            holder.ivState.isClickable = true
            holder.ivDelete!!.visibility = View.INVISIBLE
            holder.ivExpandShrink!!.visibility = View.GONE

            holder.et!!.isEnabled = true
            holder.et.visibility = View.VISIBLE
            holder.et.paint.textSkewX = 0f

            val flags = holder.et.paintFlags
            holder.et.paintFlags = flags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

            holder.et.textSize = 20f
            holder.et.hint = ""

            val params = holder.et.layoutParams as LinearLayout.LayoutParams
            params.width = LinearLayout.LayoutParams.MATCH_PARENT
            params.topMargin = (density * 3).toInt()

            mWatchEditTextChange = false
            tintRowIcon(holder.ivState)
            tintRowIcon(holder.ivDelete)
            tintRowIcon(holder.ivExpandShrink)
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
                holder.et.setTextColor(textColorSecondary())
                holder.et.setText(stateContent.substring(1, stateContent.length))
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
                holder.et.setTextColor(textColorFinished())
                holder.et.paintFlags = flags or Paint.STRIKE_THRU_TEXT_FLAG
                holder.et.setText(stateContent.substring(1, stateContent.length))
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
                holder.ivDelete.visibility = View.GONE
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

        val firstFinishedItemIndex = CheckListHelper.getFirstFinishedItemIndex(mItems)
        val collapsedHeaderIndex = mItems!!.indexOf("4")
        return if (firstFinishedItemIndex != -1 && collapsedHeaderIndex in 0 until firstFinishedItemIndex) {
            firstFinishedItemIndex
        } else {
            size
        }
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
        val ivDelete: ImageView?      = f(R.id.iv_check_list_delete)
        val ivExpandShrink: ImageView? = f(R.id.iv_check_list_expand_shrink)

        init {
            if (mType == EDITTEXT_EDITABLE) {
                if (!DeviceUtil.isMiuiButNotV5()) {
                    DisplayUtil.setSelectionHandlersColor(et, ContextCompat.getColor(
                        mContext!!, R.color.app_accent))
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
                val before: String = CheckListHelper.toCheckListStr(mItems)

                var pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION || pos >= mItems!!.size) return@setOnClickListener
                val posAfter: Int

                val item: String = mItems!![pos]!!
                var state = item[0]
                if (mDragging && state != '2') {
                    return@setOnClickListener
                }

                if (state == '2') {
                    insertItem(CheckListHelper.toCheckListStr(mItems), it, pos, "")
                    return@setOnClickListener
                }

                KeyboardUtil.hideKeyboard(et)
                if (state == '0') {
                    state = '1'
                    val size = mItems!!.size
                    val firstFinishedItemIndex = CheckListHelper.getFirstFinishedItemIndex(mItems)
                    if (firstFinishedItemIndex == -1) {
                        mItems!!.add(size, "3")
                        mItems!!.add(size + 1, "4")
                        posAfter = size + 1
                    } else {
                        posAfter = firstFinishedItemIndex - 1
                    }
                } else if (state == '1') {
                    state = '0'
                    posAfter = 0
                    if (CheckListHelper.onlyOneFinishedItem(mItems)) {
                        val size = mItems!!.size
                        mItems!!.removeAt(size - 2)
                        mItems!!.removeAt(size - 2)
                        pos = size - 3
                    }
                } else {
                    return@setOnClickListener
                }

                val itemAfter = state + item.substring(1, item.length)

                mWatchEditTextChange = false
                mItems!!.removeAt(pos)
                mItems!!.add(posAfter, itemAfter)
                mWatchEditTextChange = true

                notifyChecklistStructureChanged()

                if (mActionCallback != null) {
                    mActionCallback!!.onAction(
                        before, CheckListHelper.toCheckListStr(mItems)
                    )
                }

                if (mExpandShrinkCallback != null) {
                    mExpandShrinkCallback!!.updateChecklistHeight(mExpanded, mItems, false)
                }
            }

            ivDelete!!.setOnClickListener { v ->
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < mItems!!.size) {
                    removeItem(v, pos, true)
                }
            }

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
                    val state = mItems!![pos]!![0]
                    if (state == '0' || state == '1') {
                        mItems!![pos] = state + s.toString()
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
                            ivDelete!!.isClickable = true
                            ivDelete.visibility = View.VISIBLE
                        }
                    }
                } else {
                    ivDelete!!.isClickable = false
                    ivDelete.visibility = View.INVISIBLE
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
                            val newCurrent = current.substring(0, cursorPos + 1)
                            val next = current.substring(cursorPos + 1, etLength + 1)
                            mItems!![pos] = newCurrent
                            notifyItemChanged(pos)
                            insertItem(before, v, pos, next)
                        }
                        return@listener true
                    } else if (keyCode == KeyEvent.KEYCODE_DEL) {
                        if ((pos != 0 && et.selectionEnd == 0)
                            || (pos == 0 && mItems!![0]!!.length == 1)
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
         */
        private fun insertItem(before: String?, v: View, pos: Int, preset: String) {
            val state = mItems!![pos]!![0]
            if (state == '2') {
                mItems!![pos] = "0"
                mItems!!.add(pos + 1, "2")
            } else {
                mItems!!.add(pos + 1, state + preset)
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
                            if (current.length != 1) {
                                mItems!!.add(0, "0")
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
                val length = itemToFocus.length
                cursorPos = if (length == 1) 0 else length - 1
                if (!deleteByClick && posToFocus != -1) {
                    val append = current.substring(1, current.length)
                    mItems!![posToFocus] = itemToFocus + append
                }
            }

            mItems!!.removeAt(pos)
            if (mItems!!.isNotEmpty() && mItems!![mItems!!.size - 1]!! == "4") {
                mItems!!.remove("3")
                mItems!!.remove("4")
            }
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

            appAccent = ContextCompat.getColor(App.getApp()!!, R.color.app_accent)
            cursorWidth = (1.5 * density).toInt()
            normalLineCursorHeightVary = (-2 * density).toInt()
            lastLineCursorHeightVary = (-1 * density).toInt()
        }
    }
}
