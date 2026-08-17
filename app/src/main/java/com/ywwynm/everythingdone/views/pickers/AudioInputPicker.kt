package com.ywwynm.everythingdone.views.pickers

import android.app.Activity
import android.graphics.Rect
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.BaseViewHolder
import com.ywwynm.everythingdone.adapters.SingleChoiceAdapter
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import com.ywwynm.everythingdone.views.recording.AudioInputMode
import kotlin.math.max
import kotlin.math.min

/** 使用项目 PopupPicker surface 的双行音频输入选择器。 */
class AudioInputPicker(
    activity: Activity,
    parent: View,
    private val items: List<Item>,
    pickedMode: AudioInputMode,
    private val accentBackground: ThingBackground?,
    private val onPicked: (Item) -> Unit
) : PopupPicker(activity, parent, 0) {

    data class Item(
        val mode: AudioInputMode,
        val title: String,
        val summary: String,
        val enabled: Boolean = true
    )

    private val accentColor = accentBackground?.representativeColor()
        ?: App.defaultAccentBackground.representativeColor()
    private val rippleBackground = accentBackground ?: ThingBackground.pure(accentColor)
    private val adapter = InputAdapter()
    private var pickPending = false

    init {
        installContentSurfaceScaleTransition(pivotXFraction = 0.5f, pivotYFraction = 0f)
        mPopupWindow.isClippingEnabled = true
        val displaySize = DisplayUtil.getDisplaySize(activity)
        mRecyclerView.layoutParams = mRecyclerView.layoutParams.apply {
            width = (mScreenDensity * POPUP_WIDTH_DP).toInt()
            height = min(
                displaySize.y - (mScreenDensity * 96f).toInt(),
                (mScreenDensity * min(POPUP_MAX_HEIGHT_DP, items.size * ITEM_HEIGHT_DP + 16f)).toInt()
            )
        }
        mRecyclerView.layoutManager = LinearLayoutManager(mActivity)
        mRecyclerView.adapter = adapter
        adapter.pick(items.indexOfFirst { it.mode == pickedMode }.coerceAtLeast(0))
        mRecyclerView.addOnScrollListener(
            object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(
                    recyclerView: androidx.recyclerview.widget.RecyclerView,
                    newState: Int
                ) {
                    EdgeEffectUtil.forRecyclerView(recyclerView, accentColor)
                }
            }
        )
    }

    override fun updateAnchor() = Unit

    override fun show() {
        val anchor = mAnchor ?: return
        if (items.isEmpty()) return
        val picked = getPickedIndex()
        if (picked >= 0) mRecyclerView.scrollToPosition(picked)

        val visible = Rect()
        mParent.getWindowVisibleDisplayFrame(visible)
        val margin = (mScreenDensity * POPUP_SCREEN_MARGIN_DP).toInt()
        val gap = (mScreenDensity * POPUP_ANCHOR_GAP_DP).toInt()
        val popupWidth = min(
            (mScreenDensity * POPUP_WIDTH_DP).toInt(),
            (visible.width() - margin * 2).coerceAtLeast(1)
        )
        mRecyclerView.layoutParams = mRecyclerView.layoutParams.apply { width = popupWidth }
        val popupHeight = getPopupContentHeightForPositioning()
        mPopupWindow.width = popupWidth
        mPopupWindow.height = popupHeight

        val anchorPosition = IntArray(2)
        anchor.getLocationInWindow(anchorPosition)
        val anchorCenterX = anchorPosition[0] + anchor.width / 2
        val minX = visible.left + margin
        val maxX = (visible.right - margin - popupWidth).coerceAtLeast(minX)
        val x = (anchorCenterX - popupWidth / 2)
            .coerceIn(minX, maxX)
        val below = anchorPosition[1] + anchor.height + gap
        val above = anchorPosition[1] - popupHeight - gap
        val y = if (below + popupHeight <= visible.bottom - margin) {
            below
        } else {
            max(visible.top + margin, above)
        }
        mPopupWindow.showAtLocation(mParent, Gravity.TOP or Gravity.START, x, y)
    }

    override fun pickForUI(index: Int) {
        adapter.pick(index)
    }

    override fun getPickedIndex(): Int = adapter.getPickedPosition()

    fun pickMode(mode: AudioInputMode) {
        val index = items.indexOfFirst { it.mode == mode }
        if (index >= 0) pickForUI(index)
    }

    private inner class InputAdapter : SingleChoiceAdapter() {
        private val inflater = LayoutInflater.from(mActivity)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder =
            InputViewHolder(inflater.inflate(R.layout.audio_input_picker_item, parent, false))

        override fun onBindViewHolder(viewHolder: BaseViewHolder, position: Int) {
            val holder = viewHolder as InputViewHolder
            val item = items[position]
            // 选中语义只注入无障碍节点（TalkBack 播报"已选择"），不调 View.setSelected——
            // 后者会改 drawableState，可能波及前景 GradientRippleDrawable 的状态表现。
            val pickedNow = mPickedPosition == position
            androidx.core.view.ViewCompat.setAccessibilityDelegate(
                holder.container,
                object : androidx.core.view.AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.isSelected = pickedNow
                    }
                }
            )
            val margin8 = (mScreenDensity * 8f).toInt()
            val params = holder.container.layoutParams as androidx.recyclerview.widget.RecyclerView.LayoutParams
            params.setMargins(
                0,
                if (position == 0) margin8 else 0,
                0,
                if (position == itemCount - 1) margin8 else 0
            )
            holder.title.text = item.title
            holder.summary.text = item.summary
            holder.container.isEnabled = item.enabled
            holder.container.isClickable = item.enabled
            holder.container.background = null

            if (!item.enabled) {
                holder.title.typeface = Typeface.DEFAULT
                holder.title.paint.shader = null
                holder.title.setTextColor(
                    ContextCompat.getColor(mActivity, R.color.app_chrome_on_surface_disabled)
                )
                holder.summary.setTextColor(
                    ContextCompat.getColor(mActivity, R.color.app_chrome_on_surface_disabled)
                )
                (holder.container.foreground as? GradientRippleDrawable)?.stopAnimations()
                holder.container.foreground = null
            } else {
                val existingRipple = holder.container.foreground as? GradientRippleDrawable
                if (existingRipple != null) {
                    existingRipple.updateBackground(rippleBackground)
                } else {
                    holder.container.foreground = GradientRippleDrawable(
                        rippleBackground,
                        shapeOval = false,
                        cornerRadiusPx = 0f
                    )
                }
                holder.summary.setTextColor(
                    ContextCompat.getColor(mActivity, R.color.app_chrome_on_surface_hint)
                )
                if (mPickedPosition == position) {
                    holder.title.typeface = Typeface.DEFAULT_BOLD
                    if (accentBackground != null) {
                        BackgroundUtil.applyTextBackground(holder.title, accentBackground)
                    } else {
                        holder.title.paint.shader = null
                        holder.title.setTextColor(accentColor)
                    }
                } else {
                    holder.title.typeface = Typeface.DEFAULT
                    holder.title.paint.shader = null
                    holder.title.setTextColor(
                        ContextCompat.getColor(
                            mActivity,
                            R.color.app_chrome_on_surface_secondary
                        )
                    )
                }
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onViewRecycled(holder: BaseViewHolder) {
            val inputHolder = holder as InputViewHolder
            (inputHolder.container.foreground as? GradientRippleDrawable)?.stopAnimations()
            super.onViewRecycled(holder)
        }
    }

    private inner class InputViewHolder(itemView: View) : BaseViewHolder(itemView) {
        val container: LinearLayout = f(R.id.ll_audio_input_picker_item)
        val title: TextView = f(R.id.tv_audio_input_picker_title)
        val summary: TextView = f(R.id.tv_audio_input_picker_summary)

        init {
            container.setOnClickListener {
                val position = bindingAdapterPosition
                if (pickPending || position !in items.indices || !items[position].enabled) {
                    return@setOnClickListener
                }
                pickPending = true
                val item = items[position]
                pickForUI(position)
                container.postDelayed({
                    pickPending = false
                    dismiss()
                    onPicked(item)
                }, PICK_FEEDBACK_DELAY_MS)
            }
        }
    }

    private companion object {
        const val POPUP_WIDTH_DP = 272f
        const val POPUP_MAX_HEIGHT_DP = 360f
        const val ITEM_HEIGHT_DP = 108f
        const val POPUP_SCREEN_MARGIN_DP = 16f
        const val POPUP_ANCHOR_GAP_DP = 6f
        const val PICK_FEEDBACK_DELAY_MS = 90L
    }
}
