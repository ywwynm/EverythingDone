package com.ywwynm.everythingdone.views

import android.content.Context
import androidx.cardview.widget.CardView
import android.util.AttributeSet
import android.view.MotionEvent

/**
 * Created by ywwynm on 2015/9/18.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A CardView that can interrupt touch event so that inner RecyclerView cannot handle it.
 *
 * updated on 2016/9/6
 * Because we want to let user have the ability to finish/unfinish checklist item directly on
 * thing card, we now can set if we should intercept touch events here.
 */
open class InterceptTouchCardView : CardView {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private var mShouldInterceptTouchEvent: Boolean = true

    fun setShouldInterceptTouchEvent(shouldInterceptTouchEvent: Boolean) {
        mShouldInterceptTouchEvent = shouldInterceptTouchEvent
    }

    /**
     * if [mShouldInterceptTouchEvent] is `true`, then we will intercept touch event
     * so that inner views cannot receive it. Otherwise, inner views can still handle their own
     * touch events.
     *
     * If a ViewGroup contains a RecyclerView and has an OnTouchListener or something like that,
     * touch events will be directly delivered to inner RecyclerView and handled by it. As a result,
     * parent ViewGroup won't receive the touch event any longer. So this class is created to solve
     * this problem.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return mShouldInterceptTouchEvent
    }
}
