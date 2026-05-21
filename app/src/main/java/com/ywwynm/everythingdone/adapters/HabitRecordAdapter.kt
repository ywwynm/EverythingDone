package com.ywwynm.everythingdone.adapters

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

import com.ywwynm.everythingdone.R

/**
 * Created by ywwynm on 2016/3/8.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Habit record adapter for habit detail dialog fragment.
 */
@Suppress("DEPRECATION")
open class HabitRecordAdapter(
    context: Context?, record: String?, editable: Boolean
) : RecyclerView.Adapter<HabitRecordAdapter.ImageViewHolder>() {

    private var mInflater: LayoutInflater? = LayoutInflater.from(context)
    private var mRecord: String? = record
    private var mOriRecord: String? = record

    private var mEditable: Boolean = editable

    open fun getRecord(): String? = mRecord

    open fun hasRecordEdited(): Boolean = !mRecord!!.equals(mOriRecord)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        return ImageViewHolder(mInflater!!.inflate(R.layout.rv_habit_record, parent, false))
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val len = mRecord!!.length
        val correctPos: Int = if (len <= 30) {
            position
        } else {
            len - 30 + position
        }

        if (correctPos >= len) {
            holder.iv!!.setImageResource(R.drawable.ic_habit_record_unknown)
            holder.iv!!.setOnClickListener(null)
        } else {
            val s = mRecord!![correctPos]
            if (s == '0') {
                holder.iv!!.setImageResource(R.drawable.ic_habit_record_unfinished)
            } else if (s == '1') {
                holder.iv!!.setImageResource(R.drawable.ic_habit_record_finished)
            }

            if (mEditable && (len - correctPos <= 6) && (s == '0' || s == '1')) {
                holder.iv!!.setOnClickListener {
                    val cur = mRecord!![correctPos]
                    val sb = StringBuilder(mRecord!!)
                    if (cur == '0') {
                        sb.setCharAt(correctPos, '1')
                    } else if (cur == '1') {
                        sb.setCharAt(correctPos, '0')
                    }
                    mRecord = sb.toString()
                    notifyItemChanged(holder.adapterPosition)
                }
            }
        }
    }

    override fun getItemCount(): Int = 30

    class ImageViewHolder internal constructor(itemView: View?) : BaseViewHolder(itemView) {

        val iv: ImageView? = f(R.id.iv_habit_record)
    }

    companion object {
        const val TAG: String = "HabitRecordAdapter"
    }
}
