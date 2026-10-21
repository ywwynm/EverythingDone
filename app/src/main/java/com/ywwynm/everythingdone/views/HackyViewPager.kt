package com.ywwynm.everythingdone.views

import android.content.Context
import androidx.viewpager.widget.ViewPager
import android.util.AttributeSet
import android.view.MotionEvent

/**
 * Created by ywwynm on 2015/10/11.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A subclass of [ViewPager] for [com.github.chrisbanes.photoview.PhotoView]
 */
open class HackyViewPager : ViewPager {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    // 独立 Surface 的硬件快照会查询子 View 顺序。未启用 PageTransformer 时，
    // ViewPager 没有初始化排序列表；此时与正常 dispatchDraw 一样使用原始子顺序。
    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int =
        if (isChildrenDrawingOrderEnabled) super.getChildDrawingOrder(childCount, drawingPosition)
        else drawingPosition

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onInterceptTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            false
        }
    }
}
