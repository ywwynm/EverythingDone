@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.adapters

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil

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
        if (bg != null) mAccentColor = bg.representativeColor()
        notifyDataSetChanged()
    }

    open fun setOnItemClickListener(onItemClickListener: View.OnClickListener?) {
        mOnItemClickListener = onItemClickListener
    }

    override fun pick(position: Int) {
        notifyItemChanged(mPickedPosition)
        mPickedPosition = position
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return ChoiceHolder(mInflater!!.inflate(R.layout.rv_fragment_chooser, parent, false))
    }

    override fun onBindViewHolder(viewHolder: BaseViewHolder, position: Int) {
        val holder = viewHolder as ChoiceHolder
        val item = mItems!![position]
        holder.tv!!.text = item
        val context = holder.tv.context
        val uncheckedColor = ContextCompat.getColor(context, R.color.black_54)
        val d: Drawable?
        if (mPickedPosition == position) { // -15310698
            val srcChecked: Drawable? = ContextCompat.getDrawable(
                context, R.drawable.ic_radiobutton_checked
            )
            if (mAccentBackground != null) {
                d = BackgroundUtil.tintDrawable(
                    context.resources, srcChecked, mAccentBackground
                )
            } else {
                d = srcChecked!!.mutate()
                d.setColorFilter(mAccentColor, PorterDuff.Mode.SRC_ATOP)
            }
            holder.tv.contentDescription = context.getString(R.string.cd_chosen_item) + item
            if (mAccentBackground != null) {
                BackgroundUtil.applyTextBackground(holder.tv, mAccentBackground)
            } else {
                if (holder.tv.paint.shader != null) {
                    holder.tv.paint.setShader(null)
                }
                holder.tv.setTextColor(mAccentColor)
            }
        } else {
            d = ContextCompat.getDrawable(context, R.drawable.ic_radiobutton_unchecked)
            d!!.mutate().setColorFilter(uncheckedColor, PorterDuff.Mode.SRC_ATOP)
            holder.tv.contentDescription =
                context.getString(R.string.cd_not_chosen_item) + item
            if (holder.tv.paint.shader != null) {
                holder.tv.paint.setShader(null)
            }
            holder.tv.setTextColor(uncheckedColor)
        }
        holder.tv.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null)
    }

    override fun getItemCount(): Int = mItems!!.size

    private inner class ChoiceHolder(itemView: View?) : BaseViewHolder(itemView) {

        val tv: TextView? = f(R.id.tv_rv_chooser_fragment)

        init {
            tv!!.setOnClickListener { v ->
                pick(adapterPosition)
                notifyItemChanged(mPickedPosition)
                if (mOnItemClickListener != null) {
                    mOnItemClickListener!!.onClick(v)
                }
            }
        }
    }

    companion object {
        const val TAG: String = "ChooserFragmentAdapter"
    }
}
