package com.ywwynm.everythingdone.views;

import android.content.Context;
import android.support.v7.widget.CardView;
import android.util.AttributeSet;
import android.view.MotionEvent;

/**
 * Created by ywwynm on 2015/9/18.
 * A CardView that can interrupt touch event so that inner RecyclerView cannot handle it.
 */
public class InterceptTouchCardView extends CardView {

    public InterceptTouchCardView(Context context) {
        super(context);
    }

    public InterceptTouchCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public InterceptTouchCardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * Intercept touch event so that inner views cannot receive it.
     *
     * If a ViewGroup contains a RecyclerView and has an OnTouchListener or something like that,
     * touch events will be directly delivered to inner RecyclerView and handled by it. As a result,
     * parent ViewGroup won't receive the touch event any longer.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;
    }
}
