package com.ywwynm.everythingdone.views

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.FrequentSettings
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.adapters.CheckListAdapter
import com.ywwynm.everythingdone.helpers.CheckListHelper

import kotlin.math.min

/**
 * 记事卡片上的清单：一个竖向 [LinearLayout] 逐行放 `check_list_tv`，**不用 RecyclerView**。
 *
 * 换掉 RecyclerView 是为了修一个渲染缺陷：它的子行不会因为自己变宽而重新测量、重新折行。
 * 实测侧边封面下 RecyclerView 已经 803 px 宽，子行却仍停留在上一次的 426 px，于是文本按
 * 342 px 的可用宽度折行，明明放得下也折成两行，末尾的条目和省略行被挤出卡片。而侧边封面的
 * 宽度又要靠内容高度反解（媒体全高、用户设定形状），预测量同样拿不到按新宽度的高度，两层
 * 叠在一起。[LinearLayout] 的子项测量是同步的，这两层问题一并消失。
 *
 * 卡片上最多显示十几条，本来就不需要回收；行视图在这里按需增减并复用。
 * 见 docs/features/thing-card-content-limits/followups.md。
 */
class ThingCardChecklistView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 与 `CheckListAdapter.TvItemClickCallback` 同义：勾选某一项 / 点击行尾空白。 */
    interface ItemClickCallback {
        fun onItemClick(itemPos: Int)
        fun onItemSpaceClick(v: View?)
    }

    private val inflater = LayoutInflater.from(context)
    private var items: List<String?> = emptyList()

    init {
        orientation = VERTICAL
    }

    /** 当前渲染出的条目数（不含省略行），供外部按需了解。 */
    var visibleItemCount: Int = 0
        private set

    /** 状态图标的缩放；绑定前设好。 */
    var fixedIconScale: Float? = null

    /** 勾选与点击行尾空白的回调；绑定前设好，null 表示整份清单不可点。 */
    var itemClickCallback: ItemClickCallback? = null

    /**
     * 字号与行距按这么多条算，null 表示按实际显示的条数；绑定前设好。卡片放不下、条数被删减时
     * （分享界面抬起的卡片），字号仍按首页那张卡片的条数，不因为条数变少而变大。
     */
    var sizeBasisItemCount: Int? = null

    fun bind(
        items: List<String?>,
        maxItemCount: Int,
        thingColor: Int,
        fixedTextSize: Float?
    ) {
        val fixedIconScale = this.fixedIconScale
        val callback = itemClickCallback
        this.items = items
        val size = items.size
        val visible = if (maxItemCount < 0) size else min(size, maxItemCount)
        val hasMore = maxItemCount >= 0 && size > maxItemCount
        visibleItemCount = visible
        val rowCount = visible + if (hasMore) 1 else 0

        while (childCount < rowCount) {
            addView(inflater.inflate(R.layout.check_list_tv, this, false))
        }
        while (childCount > rowCount) {
            removeViewAt(childCount - 1)
        }

        val density = resources.displayMetrics.density
        val sizeBasis = sizeBasisItemCount ?: visible
        val baseSize = CheckListAdapter.cardBaseTextSizeSp(sizeBasis, fixedTextSize)
        val baseTop = CheckListAdapter.cardItemTopMarginPx(sizeBasis, fixedTextSize, density)
        val dark = CheckListAdapter.isDarkForeground(thingColor)

        for (i in 0 until rowCount) {
            val row = getChildAt(i)
            val holder = RowViews(row)
            if (hasMore && i == visible) {
                bindMoreRow(holder, thingColor, fixedTextSize, density, callback)
            } else {
                bindItemRow(
                    holder, i, items[i], baseSize, baseTop, dark, thingColor,
                    fixedIconScale, density, callback
                )
            }
        }
    }

    private fun bindItemRow(
        holder: RowViews,
        position: Int,
        stateContent: String?,
        baseSize: Float,
        baseTop: Int,
        dark: Boolean,
        thingColor: Int,
        fixedIconScale: Float?,
        density: Float,
        callback: ItemClickCallback?
    ) {
        val iv = holder.icon ?: return
        val tv = holder.text ?: return
        val content = stateContent ?: return
        iv.visibility = View.VISIBLE
        val flag = tv.paintFlags
        val state = content[0]
        val level = CheckListHelper.levelOf(content)
        if (state == '0') {
            iv.setImageResource(
                if (dark) R.drawable.checklist_unchecked_card_black
                else R.drawable.checklist_unchecked_card
            )
            iv.contentDescription = context.getString(R.string.cd_checklist_unfinished_item)
            tv.setTextColor(
                CheckListHelper.colorForLevel(
                    CheckListAdapter.cardTextColorSecondary(thingColor), level, false
                )
            )
            tv.paintFlags = flag and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        } else if (state == '1') {
            iv.setImageResource(
                if (dark) R.drawable.checklist_checked_card_black
                else R.drawable.checklist_checked_card
            )
            iv.contentDescription = context.getString(R.string.cd_checklist_finished_item)
            tv.setTextColor(
                CheckListHelper.colorForLevel(
                    CheckListAdapter.cardTextColorFinished(thingColor), level, true
                )
            )
            tv.paintFlags = if (FrequentSettings.getBoolean(Def.Meta.KEY_SIMPLE_FCLI)) {
                flag and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            } else {
                flag or Paint.STRIKE_THRU_TEXT_FLAG
            }
        }
        applyIconScale(iv, fixedIconScale)

        val lp = tv.layoutParams as LayoutParams
        lp.setMargins(0, baseTop, 0, lp.bottomMargin)
        tv.textSize = baseSize * CheckListHelper.sizeRatioForLevel(level)
        tv.text = CheckListHelper.textOf(content)
        alignRow(holder, lp, level, density)
        setItemEvents(holder, position, callback)
    }

    private fun bindMoreRow(
        holder: RowViews,
        thingColor: Int,
        fixedTextSize: Float?,
        density: Float,
        callback: ItemClickCallback?
    ) {
        val iv = holder.icon ?: return
        val tv = holder.text ?: return
        iv.visibility = View.GONE
        tv.textSize = fixedTextSize ?: MORE_ROW_TEXT_SIZE_SP
        tv.setTextColor(CheckListAdapter.cardTextColorSecondary(thingColor))
        tv.text = "..."
        tv.contentDescription = context.getString(R.string.cd_checklist_more_items)
        tv.paintFlags = tv.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        val lp = tv.layoutParams as LayoutParams
        lp.setMargins((density * MORE_ROW_TEXT_MARGIN_START_DP).toInt(), 0, 0, lp.bottomMargin)
        tv.layoutParams = lp
        setIndent(holder, 1, density)
        setMoreEvents(holder, callback)
    }

    /** 状态图标与首行文字的视觉中心对齐；多行也只对齐第一行。 */
    private fun alignRow(holder: RowViews, tvLp: LayoutParams, level: Int, density: Float) {
        val tv = holder.text ?: return
        val iv = holder.icon ?: return
        val iconH = (iv.layoutParams?.height?.takeIf { it > 0 })
            ?: (iv.drawable?.intrinsicHeight ?: 0)
        val (iconTopRaw, textTop) = CheckListAdapter.firstLineAlign(
            tv.paint.fontMetrics, tv.paddingTop, iconH, tvLp.topMargin
        )
        // 二、三级状态图标上移 0.5dp，与 CheckListAdapter 的卡片行一致。
        val iconTop = if (level >= 2) {
            (iconTopRaw - Math.round(0.5f * density)).coerceAtLeast(0)
        } else {
            iconTopRaw
        }
        (iv.layoutParams as? LayoutParams)?.let {
            it.topMargin = iconTop
            iv.layoutParams = it
        }
        tvLp.topMargin = textTop
        tv.layoutParams = tvLp
        setIndent(holder, level, density)
    }

    private fun setIndent(holder: RowViews, level: Int, density: Float) {
        val clickable = holder.clickable ?: return
        val lp = clickable.layoutParams as? LayoutParams ?: return
        val iconWidth = holder.icon?.let { iv ->
            val w = iv.layoutParams?.width ?: 0
            if (w > 0) w else (iv.drawable?.intrinsicWidth ?: 0)
        } ?: 0
        lp.leftMargin = CheckListAdapter.cardItemIndentPx(level, iconWidth, density)
        clickable.layoutParams = lp
    }

    private fun applyIconScale(iv: ImageView, iconScale: Float?) {
        val scale = iconScale ?: return
        val drawable = iv.drawable ?: return
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return
        val lp = iv.layoutParams ?: return
        lp.width = (drawable.intrinsicWidth * scale).toInt().coerceAtLeast(1)
        lp.height = (drawable.intrinsicHeight * scale).toInt().coerceAtLeast(1)
        iv.layoutParams = lp
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private fun setItemEvents(holder: RowViews, position: Int, callback: ItemClickCallback?) {
        val clickable = holder.clickable ?: return
        val space = holder.space
        if (callback == null) {
            clickable.isClickable = false
            clickable.setBackgroundResource(0)
            space?.isClickable = false
            return
        }
        clickable.isClickable = true
        clickable.setBackgroundResource(R.drawable.selectable_item_background_light)
        clickable.setOnClickListener { callback.onItemClick(position) }
        space?.isClickable = true
        space?.setOnClickListener { v -> callback.onItemSpaceClick(v) }
    }

    private fun setMoreEvents(holder: RowViews, callback: ItemClickCallback?) {
        val clickable = holder.clickable ?: return
        val space = holder.space
        clickable.setBackgroundResource(0)
        if (callback == null) {
            clickable.isClickable = false
            space?.isClickable = false
            return
        }
        clickable.isClickable = true
        clickable.setOnClickListener { v -> callback.onItemSpaceClick(v) }
        space?.isClickable = true
        space?.setOnClickListener { v -> callback.onItemSpaceClick(v) }
    }

    private class RowViews(row: View) {
        val clickable: LinearLayout? = row.findViewById(R.id.ll_check_list_tv)
        val icon: ImageView? = row.findViewById(R.id.iv_check_list_state)
        val text: TextView? = row.findViewById(R.id.tv_check_list)
        val space: TextView? = row.findViewById(R.id.space_checklist_item_tv)
    }

    companion object {
        /** 「...」行在没有固定字号时的字号（sp），与 CheckListAdapter 的卡片行一致。 */
        private const val MORE_ROW_TEXT_SIZE_SP = 18f

        /** 「...」行文字的左边距（dp）。 */
        private const val MORE_ROW_TEXT_MARGIN_START_DP = 8f
    }
}
