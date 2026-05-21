@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.adapters

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.KeyboardUtil

import java.util.ArrayList
import java.util.Collections

/**
 * Created by ywwynm on 2016/1/27.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Time of day recurrence adapter
 */
open class TimeOfDayRecAdapter(
    context: Context?, accentColor: Int
) : RecyclerView.Adapter<BaseViewHolder>() {

    private val mIcons: IntArray = intArrayOf(
        R.drawable.ic_reminder_1, R.drawable.ic_reminder_2,
        R.drawable.ic_reminder_3, R.drawable.ic_reminder_4
    )

    private var mContext: Context? = context
    private var mInflater: LayoutInflater? = LayoutInflater.from(context)

    private var mAccentColor: Int = accentColor
    /** Phase 8: full accent so the focused EditText's text can render gradient. */
    private var mAccentBackground: ThingBackground? = null
    private var black_26p: Int = ContextCompat.getColor(mContext!!, R.color.black_26p)
    private var black_54p: Int = ContextCompat.getColor(mContext!!, R.color.black_54p)

    private var mItems: MutableList<Int?>? = null

    interface OnItemChangeCallback {
        fun onItemInserted()
        fun onItemRemoved()
    }

    private var mOnItemChangeCallback: OnItemChangeCallback? = null

    open fun setOnItemChangeCallback(onItemChangeCallback: OnItemChangeCallback?) {
        mOnItemChangeCallback = onItemChangeCallback
    }

    /** Phase 8: upgrade the accent signal to a full [ThingBackground]. */
    open fun setAccentBackground(bg: ThingBackground?) {
        mAccentBackground = bg
        if (bg != null) {
            mAccentColor = bg.representativeColor()
            notifyDataSetChanged()
        }
    }

    open fun setItems(items: MutableList<Int?>?) {
        mItems = items
        if (mItems!!.size < 7) {
            mItems!!.add(96)
        }
    }

    open fun getFinalItems(): List<Int?>? {
        val items: MutableList<Int?> = ArrayList()
        items.addAll(mItems!!)
        items.remove(Integer.valueOf(96))
        val strs: MutableList<String> = ArrayList()
        var i = 0
        while (i < items.size) {
            var hour = items[i].toString()
            if (hour.length == 1) {
                hour = "0$hour"
            }
            var minute = items[i + 1].toString()
            if (minute.length == 1) {
                minute = "0$minute"
            }
            strs.add("$hour:$minute")
            i += 2
        }
        strs.sort()
        items.clear()
        for (str in strs) {
            val times = str.split(":".toRegex()).toTypedArray()
            val hour = Integer.parseInt(times[0])
            val minute = Integer.parseInt(times[1])
            items.add(hour)
            items.add(minute)
        }
        return items
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return if (viewType == TEXTVIEW) {
            TextViewHolder(mInflater!!.inflate(R.layout.time_of_day_rec_tv, parent, false))
        } else {
            EditTextHolder(mInflater!!.inflate(R.layout.time_of_day_rec_et, parent, false))
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(viewHolder: BaseViewHolder, position: Int) {
        if (getItemViewType(position) == EDITTEXT) {
            val holder = viewHolder as EditTextHolder
            DisplayUtil.tintView(holder.etHour, black_26p)
            DisplayUtil.tintView(holder.etMinute, black_26p)
            holder.ivReminder!!.setImageResource(mIcons[position])
            holder.ivReminder!!.contentDescription =
                mContext!!.getString(R.string.cd_reminder_time) + (position + 1)
            val hour = mItems!![2 * position]!!
            if (hour == -1) {
                holder.etHour!!.setText("")
                holder.etMinute!!.setText("")
            } else {
                holder.etHour!!.setText("" + hour)
                val minute = mItems!![2 * position + 1]!!
                if (minute < 10) {
                    holder.etMinute!!.setText("0$minute")
                } else {
                    holder.etMinute!!.setText("" + minute)
                }
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        val item = mItems!![2 * position]!!
        return if (item == 96) {
            TEXTVIEW
        } else EDITTEXT
    }

    open fun getTimeCount(): Int {
        val size = mItems!!.size
        return if (size == 8) 4 else (size - 1) / 2
    }

    override fun getItemCount(): Int {
        val size = mItems!!.size
        return if (size % 2 == 0) {
            size / 2
        } else {
            size / 2 + 1
        }
    }

    private inner class EditTextHolder(itemView: View?) : BaseViewHolder(itemView) {

        var ivReminder: ImageView? = f(R.id.iv_reminder_rec_day)
        var etHour: EditText? = f(R.id.et_hour_rec_day)
        var etMinute: EditText? = f(R.id.et_minute_rec_day)
        var ivDelete: ImageView? = f(R.id.iv_delete_reminder_as_bt_rec_day)

        init {
            DisplayUtil.setSelectionHandlersColor(etHour, mAccentColor)
            DisplayUtil.setSelectionHandlersColor(etMinute, mAccentColor)

            val focusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                val et = v as EditText
                if (hasFocus) {
                    val useGradientLine = mAccentBackground != null
                            && mAccentBackground!!.mode === ThingBackground.Mode.GRADIENT
                    if (useGradientLine) {
                        BackgroundUtil.applyTextBackground(et, mAccentBackground)
                        BackgroundUtil.applyEditTextUnderline(et, mAccentBackground)
                        DisplayUtil.tintView(v, android.graphics.Color.TRANSPARENT)
                    } else {
                        if (et.paint.shader != null) {
                            et.paint.setShader(null)
                            et.invalidate()
                        }
                        et.setTextColor(mAccentColor)
                        BackgroundUtil.clearEditTextUnderline(et)
                        DisplayUtil.tintView(v, mAccentColor)
                    }
                    et.highlightColor =
                        DisplayUtil.getLightColor(mAccentColor, mContext)
                } else {
                    DisplayUtil.tintView(v, black_26p)
                    if (et.paint.shader != null) {
                        et.paint.setShader(null)
                        et.invalidate()
                    }
                    et.setTextColor(black_54p)
                    BackgroundUtil.clearEditTextUnderline(et)
                    if (v == etHour) {
                        DateTimeUtil.limitHourForEditText(etHour)
                    } else {
                        DateTimeUtil.formatLimitMinuteForEditText(et)
                    }
                }
            }
            etHour!!.onFocusChangeListener = focusChangeListener
            etMinute!!.onFocusChangeListener = focusChangeListener

            etHour!!.addTextChangedListener(TimeTextWatcher(HOUR))
            etMinute!!.addTextChangedListener(TimeTextWatcher(MINUTE))

            etHour!!.setOnEditorActionListener(TextView.OnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_NEXT) {
                    etMinute!!.requestFocus()
                    return@OnEditorActionListener true
                }
                false
            })
            etMinute!!.setOnEditorActionListener(TextView.OnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    KeyboardUtil.hideKeyboard(v)
                    v.clearFocus()
                    return@OnEditorActionListener true
                }
                false
            })

            ivDelete!!.setOnClickListener {
                val pos = adapterPosition
                mItems!!.removeAt(2 * pos)
                mItems!!.removeAt(2 * pos)
                val size = mItems!!.size
                if (size < 7 && mItems!![size - 1] != 96) {
                    mItems!!.add(96)
                }
                notifyItemRemoved(pos)
                notifyItemRangeChanged(pos, (size + 1) / 2)
                if (mOnItemChangeCallback != null) {
                    mOnItemChangeCallback!!.onItemRemoved()
                }
            }
        }

        inner class TimeTextWatcher(private val mType: Int) : TextWatcher {

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable) {
                val position = adapterPosition
                val pos = if (mType == HOUR) position * 2 else position * 2 + 1
                val numStr = s.toString()
                if (numStr.isEmpty()) {
                    mItems!![pos] = -1
                } else {
                    mItems!![pos] = Integer.valueOf(numStr)
                }
            }
        }
    }

    private inner class TextViewHolder(itemView: View?) : BaseViewHolder(itemView) {

        var tvNewReminder: TextView? = f(R.id.tv_new_reminder_as_bt_rec_day)

        init {
            tvNewReminder!!.setOnClickListener {
                val size = mItems!!.size
                val pos = adapterPosition
                mItems!![size - 1] = -1
                mItems!!.add(-1)
                notifyItemChanged(pos)
                if (size < 7) {
                    mItems!!.add(96)
                    notifyItemInserted(pos + 1)
                }
                if (mOnItemChangeCallback != null) {
                    mOnItemChangeCallback!!.onItemInserted()
                }
            }
        }
    }

    companion object {
        const val TAG: String = "TimeOfDayRecAdapter"

        private const val EDITTEXT = 0
        private const val TEXTVIEW = 1

        private const val HOUR: Int = 0
        private const val MINUTE: Int = 1
    }
}
