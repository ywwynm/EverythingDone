@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.views.pickers

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.BaseViewHolder
import com.ywwynm.everythingdone.adapters.SingleChoiceAdapter
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil
import com.ywwynm.everythingdone.utils.LocaleUtil

import java.util.Calendar

/**
 * Created by ywwynm on 2015/8/19.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Picker for picking time for quick remind in DetailActivity
 * and picking time type in DateTimeDialogFragment
 */
open class DateTimePicker(
        activity: Activity, parent: View, type: Int, accentColor: Int
) : PopupPicker(activity, parent, if (type == Def.PickerType.AFTER_TIME)
        R.style.QuickRemindPickerAnimation else R.style.TimeTypePickerAnimation) {

    private val mType: Int = type
    private var mAccentColor: Int = accentColor
    /** Phase 8: full ThingBackground for gradient text on the picked-item row.
     *  When null, the adapter falls back to the legacy int [mAccentColor]. */
    private var mAccentBackground: com.ywwynm.everythingdone.model.ThingBackground? = null
    private val mItems: Array<String>
    private var mOnClickListener: View.OnClickListener? = null
    private val mAdapter: DateTimePickerAdapter
    private var mPreviousIndex: Int = 8

    init {
        val params: ViewGroup.LayoutParams = mRecyclerView.layoutParams!!
        if (mType == Def.PickerType.AFTER_TIME) {
            params.width = (mScreenDensity * 168).toInt()
            mItems = mActivity.resources!!.getStringArray(R.array.quick_remind)!!
            if (BuildConfig.DEBUG) {
                mItems[0] = "6 " + activity.getString(R.string.second)
            }
        } else if (mType == Def.PickerType.TIME_TYPE_HAVE_HOUR_MINUTE) {
            params.width = (mScreenDensity * 120).toInt()
            mItems = mActivity.resources!!.getStringArray(R.array.time_type)!!
            if (LocaleUtil.isChinese(mActivity)) {
                mItems[2] = mActivity.getString(R.string.days)!!
            }
        } else {
            params.width = (mScreenDensity * 98).toInt()
            val items: Array<String> = mActivity.resources!!.getStringArray(R.array.time_type)!!
            mItems = arrayOfNulls<String>(4).let {
                System.arraycopy(items, 2, it, 0, 4)
                @Suppress("UNCHECKED_CAST")
                it as Array<String>
            }
            if (LocaleUtil.isChinese(mActivity)) {
                mItems[0] = mActivity.getString(R.string.days)!!
            }
        }
        params.height = getRecyclerViewHeight()
        mRecyclerView.setHasFixedSize(true)
        val layoutManager = LinearLayoutManager(mActivity)
        mRecyclerView.setLayoutManager(layoutManager)
        mAdapter = DateTimePickerAdapter()
        mRecyclerView.setAdapter(mAdapter)

        mRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                EdgeEffectUtil.forRecyclerView(recyclerView, mAccentColor)
            }
        })
    }

    fun setAccentColor(accentColor: Int) {
        mAccentColor = accentColor
        mAccentBackground = null
    }

    /**
     * Phase 8: set the accent as a full [com.ywwynm.everythingdone.model.ThingBackground]
     * so the picked-item row can render a gradient. Keeps [mAccentColor] in
     * sync with the representative int for the EdgeEffect and other int-only paths.
     */
    fun setAccentBackground(bg: com.ywwynm.everythingdone.model.ThingBackground?) {
        mAccentBackground = bg
        if (bg != null) mAccentColor = bg.representativeColor()
    }

    @SuppressLint("SetTextI18n")
    override fun updateAnchor() {
        val index: Int = getPickedIndex()
        if (index < 0 || index >= mItems.size) {
            return
        }
        val anchor: TextView = mAnchor as TextView
        if (index != 9) {
            if (mType == Def.PickerType.AFTER_TIME) {
                val after: String = mActivity.getString(R.string.after)!!
                if (LocaleUtil.isChinese(mActivity)) {
                    anchor.text = mItems[index]
                    anchor.append(after)
                } else {
                    anchor.text = "$after "
                    anchor.append(mItems[index])
                }
            } else {
                val offset: Int = if (mType == Def.PickerType.TIME_TYPE_HAVE_HOUR_MINUTE) 0 else 1
                if (!LocaleUtil.isChinese(mActivity)) {
                    anchor.text = mItems[index].lowercase()
                    if (offset == 0) {
                        anchor.append(" ")
                    }
                } else {
                    val b1: Boolean = mType == Def.PickerType.TIME_TYPE_HAVE_HOUR_MINUTE
                            && (index == 1 || index == 3 || index == 4)
                    val b2: Boolean = mType == Def.PickerType.TIME_TYPE_NO_HOUR_MINUTE
                            && (index == 1 || index == 2)
                    if (b1 || b2) {
                        anchor.text = mActivity.getString(R.string.description_a) + mItems[index]
                    } else {
                        anchor.text = mItems[index]
                    }
                }
                if (mType == Def.PickerType.TIME_TYPE_HAVE_HOUR_MINUTE) {
                    anchor.append(mActivity.getString(R.string.later))
                }
            }
        }
    }

    override fun show() {
        if (mAnchor == null) {
            return
        }
        mRecyclerView.scrollToPosition(getPickedIndex())

        val display: Point = DisplayUtil.getDisplaySize(mActivity)!!
        val displayHeight: Int = display.y

        val params: ViewGroup.LayoutParams = mRecyclerView.layoutParams!!
        val recyclerViewHeight: Int = getRecyclerViewHeight()
        val orientation: Int = mActivity.resources!!.configuration!!.orientation
        val isInLandscape: Boolean = orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isInLandscape) {
            val window = Rect()
            mParent.getWindowVisibleDisplayFrame(window)
            if (displayHeight - window.bottom >= 96 * mScreenDensity) { // Keyboard is showing.
                if (window.bottom - mScreenDensity * 48 >= recyclerViewHeight) {
                    params.height = recyclerViewHeight
                } else {
                    params.height = window.bottom - (mScreenDensity * 48).toInt()
                }
            } else {
                params.height = recyclerViewHeight
            }
        } else {
            params.height = recyclerViewHeight
        }

        val pos = IntArray(2)
        val anchor: View = mAnchor as View
        anchor.getLocationInWindow(pos)
        if (mType == Def.PickerType.AFTER_TIME) {
            // popup bottom lands at the anchor's vertical centre.
            //
            // Why subtract navBottom: PopupWindow is created without
            // FLAG_LAYOUT_IN_SCREEN, so WindowManager insets the popup's
            // own window by the bottom system bars (gesture / 3-button
            // nav) — meaning the popup's reference "bottom" is
            // `mParent.getHeight() - navBottom`, not `mParent.getHeight()`.
            // Without the subtraction, the popup ends up navBottom px
            // higher than intended (popup.bottom lands at anchor.top
            // instead of anchor.center). Legacy non-edge-to-edge windows
            // had `mParent.getHeight() == display.height - navbar`
            // already, so the old formula happened to land in the right
            // spot; in edge-to-edge `mParent.getHeight() == display.height`
            // and we have to compensate explicitly.
            val insets: androidx.core.view.WindowInsetsCompat? =
                    androidx.core.view.ViewCompat.getRootWindowInsets(mParent)
            var navBottom = 0
            if (insets != null) {
                navBottom = insets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.systemBars()
                                or androidx.core.view.WindowInsetsCompat.Type
                                        .displayCutout()).bottom
            }
            mPopupWindow.showAtLocation(mParent, Gravity.BOTTOM or Gravity.START,
                    (pos[0] - mScreenDensity * 16).toInt(),
                    mParent.height - navBottom - pos[1] - anchor.height / 2)
        } else {
            mPopupWindow.showAtLocation(mParent, Gravity.TOP or Gravity.START,
                    pos[0] - DisplayUtil.getStatusbarHeight(mActivity),
                    (pos[1] - mScreenDensity * 56).toInt())
        }

    }

    fun setPickedListener(listener: View.OnClickListener) {
        mOnClickListener = listener
    }

    override fun pickForUI(index: Int) {
        mPreviousIndex = getPickedIndex()
        mAdapter.pick(index)
        updateAnchor()
    }

    fun pickPreviousForUI() {
        pickForUI(mPreviousIndex)
    }

    fun getPreviousIndex(): Int {
        return mPreviousIndex
    }

    override fun getPickedIndex(): Int {
        return mAdapter.getPickedPosition()
    }

    fun getPickedTimeAfter(): IntArray? {
        val index: Int = getPickedIndex()
        if (index == 9) return null
        val time: IntArray = intArrayOf(1, 1, 1, 2, 1, 2, 1, 30, 15)
        val type: IntArray = intArrayOf(Calendar.YEAR, Calendar.MONTH, Calendar.WEEK_OF_YEAR,
                Calendar.DATE, Calendar.DATE, Calendar.HOUR_OF_DAY,
                Calendar.HOUR, Calendar.MINUTE, Calendar.MINUTE)
        if (BuildConfig.DEBUG) {
            time[0] = 6
            type[0] = Calendar.SECOND
        }
        return intArrayOf(type[index], time[index])
    }

    fun getPickedTimeType(): Int {
        if (mType == Def.PickerType.AFTER_TIME) return -1
        val types: IntArray = intArrayOf(Calendar.MINUTE, Calendar.HOUR_OF_DAY, Calendar.DATE,
                Calendar.WEEK_OF_YEAR, Calendar.MONTH, Calendar.YEAR)
        return if (mType == Def.PickerType.TIME_TYPE_HAVE_HOUR_MINUTE)
                types[getPickedIndex()] else types[getPickedIndex() + 2]
    }

    private fun getRecyclerViewHeight(): Int {
        return if (mType == Def.PickerType.AFTER_TIME) {
            (mScreenDensity * 228).toInt()
        } else {
            (mScreenDensity * 180).toInt()
        }
    }

    private inner class DateTimePickerAdapter : SingleChoiceAdapter() {

        private val mInflater: LayoutInflater = LayoutInflater.from(mActivity)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
            return DateTimeViewHolder(mInflater.inflate(R.layout.datetime_picker_bt, parent, false))
        }

        override fun onBindViewHolder(viewHolder: BaseViewHolder, position: Int) {
            val holder: DateTimeViewHolder = viewHolder as DateTimeViewHolder
            val m8: Int = (mScreenDensity * 8).toInt()
            val params: RecyclerView.LayoutParams = holder.bt.layoutParams as RecyclerView.LayoutParams
            if (position == 0) {
                params.setMargins(0, m8, 0, 0)
            } else if (position == itemCount - 1) {
                params.setMargins(0, 0, 0, m8)
            } else {
                params.setMargins(0, 0, 0, 0)
            }
            holder.bt.text = mItems[position]
            if (mPickedPosition == position) {
                holder.bt.setTypeface(Typeface.DEFAULT_BOLD)
                // Phase 8: gradient text on the picked row when the accent is
                // a GRADIENT background. Falls back to plain accent int.
                if (mAccentBackground != null) {
                    com.ywwynm.everythingdone.utils.BackgroundUtil.applyTextBackground(
                            holder.bt, mAccentBackground)
                } else {
                    if (holder.bt.paint.shader != null) {
                        holder.bt.paint.setShader(null)
                    }
                    holder.bt.setTextColor(mAccentColor)
                }
                holder.bt.isClickable = position == 9
            } else {
                holder.bt.setTypeface(Typeface.DEFAULT)
                // Clear any shader left from a previous bind to this view holder
                // before painting an unselected row in its plain colour.
                if (holder.bt.paint.shader != null) {
                    holder.bt.paint.setShader(null)
                }
                holder.bt.setTextColor(ContextCompat.getColor(mActivity, R.color.black_54p))
                holder.bt.isClickable = true
            }
        }

        override fun getItemCount(): Int {
            return when (mType) {
                Def.PickerType.AFTER_TIME -> 10
                Def.PickerType.TIME_TYPE_NO_HOUR_MINUTE -> 4
                Def.PickerType.TIME_TYPE_HAVE_HOUR_MINUTE -> 6
                else -> 0
            }
        }

        inner class DateTimeViewHolder(itemView: View) : BaseViewHolder(itemView) {

            val bt: Button = f(R.id.bt_pick_after_time)

            init {
                bt.setOnClickListener(object : View.OnClickListener {
                    override fun onClick(v: View) {
                        mPopupWindow.dismiss()
                        pickForUI(adapterPosition)
                        if (mOnClickListener != null) {
                            mOnClickListener!!.onClick(v)
                        }
                    }
                })
            }
        }
    }

    companion object {
        const val TAG: String = "DateTimePicker"
    }
}
