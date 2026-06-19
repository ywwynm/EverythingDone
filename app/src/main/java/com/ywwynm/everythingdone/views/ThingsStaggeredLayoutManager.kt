package com.ywwynm.everythingdone.views

import android.content.Context
import android.graphics.PointF
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import android.util.AttributeSet

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.utils.DisplayUtil

/**
 * Created by qiizhang on 2016/9/19.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A subclass of [StaggeredGridLayoutManager] that ignore inconsistency detection in
 * its [onLayoutChildren] method.
 */
open class ThingsStaggeredLayoutManager : StaggeredGridLayoutManager {

    private var mSmoothScroller: ThingsSmoothScroller? = null
    private var suppressPredictiveAnimationsForNextLayout: Boolean = false

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int)
            : super(context, attrs, defStyleAttr, defStyleRes) {
        init(context)
    }

    constructor(spanCount: Int, orientation: Int) : super(spanCount, orientation) {
        init(App.getApp()!!)
    }

    private fun init(context: Context) {
        mSmoothScroller = ThingsSmoothScroller(context)
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (_: IndexOutOfBoundsException) {
        }
    }

    override fun onLayoutCompleted(state: RecyclerView.State) {
        super.onLayoutCompleted(state)
        suppressPredictiveAnimationsForNextLayout = false
    }

    override fun supportsPredictiveItemAnimations(): Boolean {
        return !suppressPredictiveAnimationsForNextLayout &&
            super.supportsPredictiveItemAnimations()
    }

    fun prepareForOverlayReorderAnimation() {
        suppressPredictiveAnimationsForNextLayout = true
        requestSimpleAnimationsInNextLayout()
        invalidateSpanAssignments()
    }

    override fun smoothScrollToPosition(recyclerView: RecyclerView, state: RecyclerView.State, position: Int) {
        val context: Context = recyclerView.context!!
        val screenHeight: Int = DisplayUtil.getDisplaySize(context).y
        mSmoothScroller!!.setScreenHeight(screenHeight)
        mSmoothScroller!!.targetPosition = position
        startSmoothScroll(mSmoothScroller)
    }

    private inner class ThingsSmoothScroller(context: Context) : LinearSmoothScroller(context) {

        private var mScreenHeight: Int = 0

        fun setScreenHeight(screenHeight: Int) {
            mScreenHeight = screenHeight
        }

        override fun calculateTimeForScrolling(dx: Int): Int {
            var d: Int = dx
            if (d > 2 * mScreenHeight) {
                d = 2 * mScreenHeight
            }
            return super.calculateTimeForScrolling(d)
        }

        override fun computeScrollVectorForPosition(targetPosition: Int): PointF? {
            return this@ThingsStaggeredLayoutManager
                    .computeScrollVectorForPosition(targetPosition)
        }
    }
}
