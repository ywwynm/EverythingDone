package com.ywwynm.everythingdone.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

import androidx.core.widget.NestedScrollView

/**
 * 竖向容器：默认按内容自适应高度（全部展开，不滚动）；当所有子项总高超过父级给定的可用高度
 * （例如软键盘弹出导致空间变小）时，只把其中第一个可见的 [NestedScrollView] 子项收缩，使其内部
 * 滚动，其余子项（标题、分割线、取消/确定按钮）保持固定、不被裁切、不与滚动内容重叠。
 *
 * 用作 Thing Background 编辑器在详情页对话框与首页面板里的承载容器。
 */
class ScrollAwareColumn @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        orientation = VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val mode = MeasureSpec.getMode(heightMeasureSpec)
        if (mode == MeasureSpec.UNSPECIFIED) return
        val avail = MeasureSpec.getSize(heightMeasureSpec)

        // 子项自然总高（含纵向 margin 与自身 padding）。
        var total = paddingTop + paddingBottom
        var scroll: View? = null
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val lp = c.layoutParams as ViewGroup.MarginLayoutParams
            total += c.measuredHeight + lp.topMargin + lp.bottomMargin
            if (scroll == null && c is NestedScrollView) scroll = c
        }

        if (total <= avail || scroll == null) return

        // 溢出：把可滚动子项收缩 (total - avail)，容器高度收到 avail。
        val overflow = total - avail
        val targetH = (scroll.measuredHeight - overflow).coerceAtLeast(0)
        scroll.measure(
            MeasureSpec.makeMeasureSpec(scroll.measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(targetH, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(measuredWidth, avail)
    }
}
