package com.ywwynm.everythingdone.views

import android.content.Context
import androidx.cardview.widget.CardView
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.ywwynm.everythingdone.R

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

    // 缓存"正在做"蒙层引用，避免每次测量都 findViewById 遍历子树（滚动时测量频繁）。
    private var mDoingCover: View? = null

    fun setShouldInterceptTouchEvent(shouldInterceptTouchEvent: Boolean) {
        mShouldInterceptTouchEvent = shouldInterceptTouchEvent
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // "正在做"蒙层是 wrap_content 卡片下的 match_parent 子 View。本 RecyclerView 下卡片常拿到
        // EXACTLY 测量规格，FrameLayout 不一定跑第二趟 match_parent 测量，蒙层就退化成内容大小、
        // 铺不满卡片。卡片自身尺寸已由其它内容在 super 中确定，这里再把可见的蒙层按卡片最终内容区
        // 强制重测，使其铺满且内部图文居中。因为发生在 super 之后，不影响卡片高度（不会撑高）。
        if (mDoingCover == null) {
            mDoingCover = findViewById(R.id.fl_thing_doing_cover)
        }
        val cover = mDoingCover ?: return
        if (cover.visibility == View.GONE) return
        val w = measuredWidth - paddingLeft - paddingRight
        val h = measuredHeight - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return
        if (cover.measuredWidth != w || cover.measuredHeight != h) {
            cover.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
        }
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
