@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.adapters

import android.content.Context
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable

/**
 * Created by qiizhang on 2016/11/21.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Copied from ChooserDialogFragment.ChooserFragmentAdapter
 */
open class RadioChooserAdapter(
    context: Context?, items: List<String?>?, accentColor: Int
) : SingleChoiceAdapter() {

    private var mInflater: LayoutInflater? = LayoutInflater.from(context)
    private var mItems: List<String?>? = items
    private var mAccentColor: Int = accentColor

    /** Phase 8: full accent so the picked item's text can render gradient.
     *  When null, the row falls back to plain [mAccentColor]. */
    private var mAccentBackground: ThingBackground? = null

    private var mOnItemClickListener: View.OnClickListener? = null

    /** Phase 8: accept a full [ThingBackground] for gradient text on the picked row. */
    open fun setAccentBackground(bg: ThingBackground?) {
        mAccentBackground = bg
        if (bg != null) mAccentColor = bg.color
        notifyDataSetChanged()
    }

    open fun setOnItemClickListener(onItemClickListener: View.OnClickListener?) {
        mOnItemClickListener = onItemClickListener
    }

    override fun pick(position: Int) {
        if (position < 0 || position >= itemCount) return
        val oldPosition = mPickedPosition
        if (oldPosition == position) {
            notifyItemChanged(position)
            return
        }
        mPickedPosition = position
        if (oldPosition >= 0 && oldPosition < itemCount) {
            notifyItemChanged(oldPosition, PAYLOAD_PICKED_CHANGED)
        }
        if (oldPosition >= 0) {
            notifyItemChanged(position, PAYLOAD_PICKED_CHANGED)
        } else {
            notifyItemChanged(position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return ChoiceHolder(mInflater!!.inflate(R.layout.rv_fragment_chooser, parent, false))
    }

    override fun onBindViewHolder(viewHolder: BaseViewHolder, position: Int) {
        bindViewHolder(viewHolder, position, animateIcon = false)
    }

    override fun onBindViewHolder(
        viewHolder: BaseViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        bindViewHolder(
            viewHolder,
            position,
            animateIcon = payloads.contains(PAYLOAD_PICKED_CHANGED)
        )
    }

    private fun bindViewHolder(
        viewHolder: BaseViewHolder,
        position: Int,
        animateIcon: Boolean
    ) {
        val holder = viewHolder as ChoiceHolder
        val item = mItems!![position]
        holder.tv!!.text = item
        val context = holder.tv.context
        val uncheckedColor = ContextCompat.getColor(context, R.color.app_chrome_control_unchecked)
        val picked = mPickedPosition == position
        val accentBackground = mAccentBackground ?: ThingBackground.pure(mAccentColor)
        val d = BackgroundUtil.createGradientRadioDrawable(
            context,
            accentBackground,
            uncheckedColor,
            picked,
            animateIcon
        )
        holder.tv.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null)
        // 每个 radio 选项触摸 ripple 用与「确定」按钮同源的强调色（整行直角矩形）。
        GradientRippleDrawable.applyAccentRowRipple(holder.tv, mAccentBackground, mAccentColor)
        if (picked) {
            holder.tv.contentDescription = context.getString(R.string.cd_chosen_item) + item
            if (mAccentBackground != null) {
                BackgroundUtil.applyTextBackground(
                    holder.tv,
                    mAccentBackground,
                    BackgroundUtil.CompoundDrawableGradientMode.NONE
                )
            } else {
                if (holder.tv.paint.shader != null) {
                    holder.tv.paint.setShader(null)
                }
                holder.tv.setTextColor(mAccentColor)
            }
        } else {
            holder.tv.contentDescription =
                context.getString(R.string.cd_not_chosen_item) + item
            if (holder.tv.paint.shader != null) {
                holder.tv.paint.setShader(null)
            }
            holder.tv.setTextColor(uncheckedColor)
        }
    }

    override fun getItemCount(): Int = mItems!!.size

    private inner class ChoiceHolder(itemView: View?) : BaseViewHolder(itemView) {

        val tv: TextView? = f(R.id.tv_rv_chooser_fragment)

        init {
            tv!!.setOnClickListener { v ->
                val position = adapterPosition
                if (position < 0 || position >= itemCount) return@setOnClickListener
                pick(position)
                if (mOnItemClickListener != null) {
                    mOnItemClickListener!!.onClick(v)
                }
            }
        }
    }

    companion object {
        const val TAG: String = "ChooserFragmentAdapter"
        private const val PAYLOAD_PICKED_CHANGED = "picked_changed"
    }
}
