package com.ywwynm.everythingdone.views.pickers

import android.app.Activity
import android.graphics.Point
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.BaseViewHolder
import com.ywwynm.everythingdone.adapters.SingleChoiceAdapter
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil
import kotlin.math.max
import kotlin.math.min

class ThingCardAppearanceSourcePicker(
        activity: Activity,
        parent: View,
        private val items: List<Item>,
        pickedIndex: Int,
        private val accentBackground: ThingBackground?,
        private val onPicked: (Item) -> Unit
) : PopupPicker(activity, parent, R.style.QuickRemindPickerAnimation) {

    data class Item(
            val label: String,
            val sourceKey: String?
    )

    private val accentColor: Int =
            accentBackground?.representativeColor()
                    ?: ContextCompat.getColor(activity, R.color.app_accent)
    private val adapter = SourceAdapter()

    init {
        val params = mRecyclerView.layoutParams!!
        val displayWidth = DisplayUtil.getDisplaySize(activity).x
        params.width = min(
                displayWidth - (mScreenDensity * 48).toInt(),
                (mScreenDensity * 320).toInt()
        )
        params.height = getRecyclerViewHeight()
        mRecyclerView.setHasFixedSize(true)
        mRecyclerView.layoutManager = LinearLayoutManager(mActivity)
        mRecyclerView.adapter = adapter
        adapter.pick(max(0, min(items.size - 1, pickedIndex)))
        mRecyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(
                    recyclerView: androidx.recyclerview.widget.RecyclerView,
                    newState: Int
            ) {
                super.onScrollStateChanged(recyclerView, newState)
                EdgeEffectUtil.forRecyclerView(recyclerView, accentColor)
            }
        })
    }

    override fun updateAnchor() {
    }

    override fun show() {
        if (mAnchor == null || items.isEmpty()) return

        val pickedIndex = getPickedIndex()
        if (pickedIndex >= 0) {
            mRecyclerView.scrollToPosition(pickedIndex)
        }

        val params = mRecyclerView.layoutParams!!
        params.height = getRecyclerViewHeight()
        mRecyclerView.layoutParams = params

        val display: Point = DisplayUtil.getDisplaySize(mActivity)
        val anchor = mAnchor!!
        val pos = IntArray(2)
        anchor.getLocationInWindow(pos)

        val margin = (mScreenDensity * 8).toInt()
        var x = pos[0] - (mScreenDensity * 8).toInt()
        x = max(margin, min(x, display.x - params.width - margin))

        val insets = ViewCompat.getRootWindowInsets(mParent)
        val navBottom = insets?.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
        )?.bottom ?: 0
        val y = mParent.height - navBottom - pos[1] + margin
        mPopupWindow.showAtLocation(mParent, Gravity.BOTTOM or Gravity.START, x, y)
    }

    override fun pickForUI(index: Int) {
        adapter.pick(index)
    }

    override fun getPickedIndex(): Int {
        return adapter.getPickedPosition()
    }

    private fun getRecyclerViewHeight(): Int {
        return min(
                (mScreenDensity * 260).toInt(),
                (mScreenDensity * (items.size * 48 + 16)).toInt()
        )
    }

    private inner class SourceAdapter : SingleChoiceAdapter() {

        private val inflater = LayoutInflater.from(mActivity)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
            return SourceViewHolder(
                    inflater.inflate(
                            R.layout.thing_card_appearance_source_picker_item,
                            parent,
                            false
                    )
            )
        }

        override fun onBindViewHolder(viewHolder: BaseViewHolder, position: Int) {
            val holder = viewHolder as SourceViewHolder
            holder.text.text = items[position].label
            if (mPickedPosition == position) {
                holder.text.setTypeface(Typeface.DEFAULT_BOLD)
                if (accentBackground != null) {
                    BackgroundUtil.applyTextBackground(holder.text, accentBackground)
                } else {
                    holder.text.paint.shader = null
                    holder.text.setTextColor(accentColor)
                }
            } else {
                holder.text.setTypeface(Typeface.DEFAULT)
                holder.text.paint.shader = null
                holder.text.setTextColor(
                        ContextCompat.getColor(
                                mActivity,
                                R.color.app_chrome_on_surface_secondary
                        )
                )
            }
        }

        override fun getItemCount(): Int {
            return items.size
        }

        private inner class SourceViewHolder(itemView: View) : BaseViewHolder(itemView) {

            val text: TextView = f(R.id.tv_thing_card_appearance_source_picker_item)

            init {
                text.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position < 0 || position >= items.size) return@setOnClickListener
                    mPopupWindow.dismiss()
                    pickForUI(position)
                    onPicked(items[position])
                }
            }
        }
    }
}
