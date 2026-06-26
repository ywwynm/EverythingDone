package com.ywwynm.everythingdone.views

import android.content.Context
import android.graphics.Canvas
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

    var maxMeasuredHeightPx: Int = 0
        set(value) {
            val coerced = value.coerceAtLeast(0)
            if (field == coerced) return
            field = coerced
            requestLayout()
        }

    init {
        orientation = VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        if (mode == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val measuredAvail = MeasureSpec.getSize(heightMeasureSpec)
        val avail = if (maxMeasuredHeightPx > 0) {
            minOf(measuredAvail, maxMeasuredHeightPx)
        } else {
            measuredAvail
        }

        // 先用不受高度限制的 spec 得到真实自然高度；否则父级 AT_MOST 会先把 wrap_content
        // 子项压缩，后续就无法可靠地只收缩中间滚动区。
        val naturalHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        super.onMeasure(widthMeasureSpec, naturalHeightSpec)

        // 子项自然总高（含纵向 margin 与容器 padding）。
        var total = paddingTop + paddingBottom
        var scroll: View? = null
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val lp = c.layoutParams as ViewGroup.MarginLayoutParams
            total += c.measuredHeight + lp.topMargin + lp.bottomMargin
            if (scroll == null && c is NestedScrollView) scroll = c
        }

        if (total <= avail || scroll == null) {
            val desiredHeight = if (mode == MeasureSpec.EXACTLY) avail else total.coerceAtMost(avail)
            setMeasuredDimension(measuredWidth, desiredHeight)
            return
        }

        val fixedHeight = total - scroll.measuredHeight
        val targetH = (avail - fixedHeight).coerceAtLeast(0)
        scroll.measure(
            MeasureSpec.makeMeasureSpec(scroll.measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(targetH, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(measuredWidth, fixedHeight + targetH)
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child !is NestedScrollView) return super.drawChild(canvas, child, drawingTime)

        val save = canvas.save()
        canvas.clipRect(child.left, child.top, child.right, child.bottom)
        val drawn = super.drawChild(canvas, child, drawingTime)
        canvas.restoreToCount(save)
        return drawn
    }
}
