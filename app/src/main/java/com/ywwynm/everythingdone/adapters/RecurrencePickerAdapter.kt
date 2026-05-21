@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.adapters

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import androidx.core.content.ContextCompat
import androidx.cardview.widget.CardView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.LocaleUtil

import java.util.ArrayList

/**
 * Created by ywwynm on 2015/9/16.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Adapter for RecyclerView used to pick time for recurrence.
 */
open class RecurrencePickerAdapter(
    context: Context?, type: Int, accentColor: Int
) : MultiChoiceAdapter() {

    private var mContext: Context? = context

    private var mInflater: LayoutInflater? = LayoutInflater.from(context)

    private var mType: Int = type

    private var mCdPicked: String? = null
    private var mCdUnpicked: String? = null

    private var mItems: Array<String?>? = null
    private var mCds: Array<String?>? = null

    private var mAccentColor: Int = accentColor
    /** Phase 8: full accent so picked cells render real gradient. */
    private var mAccentBackground: ThingBackground? = null

    private var mScreenDensity: Float = DisplayUtil.getScreenDensity(context)

    private var mOnPickListener: View.OnClickListener? = null

    open fun setOnPickListener(onPickListener: View.OnClickListener?) {
        mOnPickListener = onPickListener
    }

    /** Phase 8: upgrade the accent signal to a full [ThingBackground]. */
    open fun setAccentBackground(bg: ThingBackground?) {
        mAccentBackground = bg
        if (bg != null) {
            mAccentColor = bg.representativeColor()
            notifyDataSetChanged()
        }
    }

    init {
        mCdPicked = mContext!!.getString(R.string.cd_picked)
        mCdUnpicked = mContext!!.getString(R.string.cd_unpicked)

        if (type == Def.PickerType.DAY_OF_WEEK) {
            mItems = mContext!!.resources.getStringArray(R.array.day_of_week) as Array<String?>
            mCds = mContext!!.resources.getStringArray(R.array.day_of_week) as Array<String?>
            if (LocaleUtil.isChinese(mContext)) {
                for (i in mItems!!.indices) {
                    mItems!![i] = mItems!![i]!!.substring(1, 2)
                }
            } else {
                for (i in mItems!!.indices) {
                    mItems!![i] = mItems!![i]!!.substring(0, 3)
                }
            }
        } else if (type == Def.PickerType.DAY_OF_MONTH) {
            mItems = arrayOfNulls(28)
            mCds = arrayOfNulls(28)
            for (i in 0 until 27) {
                mItems!![i] = (i + 1).toString()
            }
            val day = mContext!!.getString(R.string.cd_day)
            if (LocaleUtil.isChinese(mContext)) {
                for (i in 0 until 27) {
                    mCds!![i] = (i + 1).toString() + day
                }
            } else {
                for (i in 0 until 27) {
                    mCds!![i] = day + (i + 1).toString()
                }
            }
            mItems!![27] = mContext!!.getString(R.string.end_of_month)
            mCds!![27] = mItems!![27]
        } else {
            mItems = mContext!!.resources.getStringArray(R.array.month_of_year) as Array<String?>
            mCds = mContext!!.resources.getStringArray(R.array.month_of_year) as Array<String?>
            if (!LocaleUtil.isChinese(mContext)) {
                for (i in mItems!!.indices) {
                    mItems!![i] = mItems!![i]!!.substring(0, 3)
                }
            }
        }
        mPicked = BooleanArray(mItems!!.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return if (viewType == NORMAL) {
            NormalViewHolder(mInflater!!.inflate(
                R.layout.recurrence_picker_normal, parent, false))
        } else {
            EndOfMonthViewHolder(mInflater!!.inflate(
                R.layout.recurrence_picker_end_of_month, parent, false))
        }
    }

    override fun onBindViewHolder(viewHolder: BaseViewHolder, position: Int) {
        val unPickerColor = ContextCompat.getColor(mContext!!, R.color.bg_unpicked)
        val black_54 = ContextCompat.getColor(mContext!!, R.color.black_54)
        if (getItemViewType(position) == END_OF_MONTH) {
            val holder = viewHolder as EndOfMonthViewHolder
            val pill = GradientDrawable()
            pill.shape = GradientDrawable.RECTANGLE
            pill.cornerRadius = holder.cv!!.radius
            if (mPicked!![position]) {
                if (mAccentBackground != null
                    && mAccentBackground!!.mode === ThingBackground.Mode.GRADIENT
                ) {
                    pill.orientation = toGdOrientation(mAccentBackground!!.orientation)
                    pill.colors = intArrayOf(
                        mAccentBackground!!.color, mAccentBackground!!.endColor
                    )
                } else {
                    pill.setColor(mAccentColor)
                }
                holder.cv!!.background = pill
                holder.cv!!.contentDescription = mCdPicked + mCds!![position] + ","
                DisplayUtil.setRippleColorForCardView(holder.cv, unPickerColor)
                holder.tv!!.setTextColor(Color.WHITE)
            } else {
                pill.setColor(unPickerColor)
                holder.cv!!.background = pill
                holder.cv!!.contentDescription = mCdUnpicked + mCds!![position] + ","
                DisplayUtil.setRippleColorForCardView(holder.cv, mAccentColor)
                holder.tv!!.setTextColor(black_54)
            }
        } else {
            val holder = viewHolder as NormalViewHolder
            if (mType == Def.PickerType.DAY_OF_MONTH) {
                val params = holder.cell!!.layoutParams as FrameLayout.LayoutParams
                params.width = (mScreenDensity * 36).toInt()
                params.height = params.width
                holder.cell!!.invalidateOutline()
            }
            holder.tvDate!!.text = mItems!![position]
            if (mPicked!![position]) {
                if (mAccentBackground != null
                    && mAccentBackground!!.mode === ThingBackground.Mode.GRADIENT
                ) {
                    val gd = GradientDrawable(
                        toGdOrientation(mAccentBackground!!.orientation),
                        intArrayOf(mAccentBackground!!.color, mAccentBackground!!.endColor)
                    )
                    gd.shape = GradientDrawable.RECTANGLE
                    holder.bg!!.background = gd
                } else {
                    holder.bg!!.background = null
                    holder.bg!!.setBackgroundColor(mAccentColor)
                }
                setRippleColor(holder.cell, unPickerColor)
                holder.tvDate!!.setTextColor(Color.WHITE)
                holder.cell!!.contentDescription = mCdPicked + mCds!![position] + ","
            } else {
                holder.bg!!.background = null
                holder.bg!!.setBackgroundColor(unPickerColor)
                setRippleColor(holder.cell, mAccentColor)
                holder.tvDate!!.setTextColor(black_54)
                holder.cell!!.contentDescription = mCdUnpicked + mCds!![position] + ","
            }
        }
        viewHolder.itemView.contentDescription =
            (if (mPicked!![position]) mCdPicked else mCdUnpicked) + mCds!![position] + ","
    }

    override fun getItemCount(): Int = mItems!!.size

    override fun getItemViewType(position: Int): Int {
        return if (mType == Def.PickerType.DAY_OF_MONTH && position == 27) {
            END_OF_MONTH
        } else NORMAL
    }

    open fun getPickedIndexes(): List<Int?>? {
        val list: MutableList<Int?> = ArrayList()
        for (i in mPicked!!.indices) {
            if (mPicked!![i]) {
                list.add(i)
            }
        }
        return list
    }

    open fun getPickedCount(): Int {
        var count = 0
        for (b in mPicked!!) {
            if (b) count++
        }
        return count
    }

    private inner class NormalViewHolder(itemView: View?) : BaseViewHolder(itemView) {

        /** Outer 48dp clipped-to-oval cell — owns click + ripple foreground. */
        val cell: FrameLayout? = f(R.id.fab_recurrence_picker)
        /** Inner View carrying the picked-state gradient or unpicked grey. */
        val bg: View? = f(R.id.v_recurrence_picker_bg)
        val tvDate: TextView? = f(R.id.tv_recurrence_picker)

        init {
            cell!!.clipToOutline = true
            cell.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setOval(0, 0, v.width, v.height)
                }
            }
            cell.foreground = BackgroundUtil.circularRipple()

            cell.setOnClickListener { v ->
                togglePick(adapterPosition)
                if (mOnPickListener != null) {
                    mOnPickListener!!.onClick(v)
                }
            }
        }
    }

    private inner class EndOfMonthViewHolder(itemView: View?) : BaseViewHolder(itemView) {

        val cv: CardView? = f(R.id.cv_end_of_month_rec)
        val tv: TextView? = f(R.id.tv_end_of_month_rec)

        init {
            cv!!.setOnClickListener { v ->
                togglePick(adapterPosition)
                if (mOnPickListener != null) {
                    mOnPickListener!!.onClick(v)
                }
            }
        }
    }

    companion object {
        const val TAG: String = "RecurrencePickerAdapter"

        private const val NORMAL = 0
        private const val END_OF_MONTH = 1

        /** Update the ripple foreground's tint without re-allocating the drawable. */
        @JvmStatic
        private fun setRippleColor(cell: View?, color: Int) {
            if (cell!!.foreground is RippleDrawable) {
                (cell.foreground as RippleDrawable)
                    .setColor(ColorStateList.valueOf(color))
            }
        }

        /** Map [ThingBackground.Orientation] → platform GradientDrawable orientation. */
        @JvmStatic
        private fun toGdOrientation(
            o: ThingBackground.Orientation?
        ): GradientDrawable.Orientation {
            return when (o) {
                ThingBackground.Orientation.L_R   -> GradientDrawable.Orientation.LEFT_RIGHT
                ThingBackground.Orientation.T_B   -> GradientDrawable.Orientation.TOP_BOTTOM
                ThingBackground.Orientation.LT_RB -> GradientDrawable.Orientation.TL_BR
                ThingBackground.Orientation.RT_LB -> GradientDrawable.Orientation.TR_BL
                ThingBackground.Orientation.LB_RT -> GradientDrawable.Orientation.BL_TR
                ThingBackground.Orientation.RB_LT -> GradientDrawable.Orientation.BR_TL
                ThingBackground.Orientation.R_L   -> GradientDrawable.Orientation.RIGHT_LEFT
                ThingBackground.Orientation.B_T   -> GradientDrawable.Orientation.BOTTOM_TOP
                else -> GradientDrawable.Orientation.LEFT_RIGHT
            }
        }
    }
}
