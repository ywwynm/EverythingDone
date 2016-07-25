/**
 * Created by yugy on 14/11/21.
 * visit https://github.com/kyze8439690/RevealLayout to get more supports.
 * <p/>
 * Changed by ywwynm on 15/5/20.
 */
package com.ywwynm.everythingdone.views.reveal;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.os.Build;
import android.support.annotation.NonNull;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * circularReview动画实现方式：
 * ObjectAnimator更改该View的clipRadius属性，加速器BakedBezierInterpolator设定了许多“状态”，每次动画
 * 执行到一个状态，均会调用setClipRadius方法，其中的invalidate方法重绘该View本身，同时导致其childView
 * 重绘，即调用drawChild方法。在drawChild方法中，canvas根据每次重绘时的clipRadius绘制圆形path以“切割”
 * 画布，因此能以圆形扩大的方式显示childView。
 */

public class RevealLayout extends FrameLayout {

    @SuppressWarnings("unused")
    private static final String TAG = "RevealLayout";

    private static final int DEFAULT_DURATION = 600;
    private final Path mClipPath;
    private float mClipRadius = 0;
    private int mClipCenterX, mClipCenterY = 0;
    private Animator mAnimator;

    public RevealLayout(Context context) {
        this(context, null);
    }

    public RevealLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RevealLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mClipPath = new Path();

        // clipPath()仅在4.3以上支持硬件加速
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mClipCenterX = w / 2;
        mClipCenterY = h / 2;
        mClipRadius = (float) (Math.sqrt(w * w + h * h) / 2);

        super.onSizeChanged(w, h, oldw, oldh);
    }


    @SuppressWarnings("unused")
    public float getClipRadius() {
        return mClipRadius;
    }

    @SuppressWarnings("unused")
    public void setClipRadius(float clipRadius) {
        mClipRadius = clipRadius;
        invalidate();
    }

    public void show(int x, int y) {
        show(x, y, DEFAULT_DURATION);
    }

    public void show(int x, int y, int duration) {
        if (x < 0 || x > getWidth() || y < 0 || y > getHeight()) {
            throw new RuntimeException("Center point out of range or call method " +
                    "when View is not initialed yet.");
        }

        mClipCenterX = x;
        mClipCenterY = y;

        float maxRadius = getMaxRadius(x, y);

        if (mAnimator != null && mAnimator.isRunning()) {
            mAnimator.cancel();
        }

        mAnimator = ObjectAnimator.ofFloat(this, "clipRadius", 0f, maxRadius);
        mAnimator.setInterpolator(new BakedBezierInterpolator());
        mAnimator.setDuration(duration);
        mAnimator.start();
    }

//    @Override
//    protected boolean fitSystemWindows(Rect insets) {
//        return super.fitSystemWindows(insets);
//    }

    private float getMaxRadius(int x, int y) {
        int h = Math.max(x, getWidth() - x);
        int v = Math.max(y, getHeight() - y);
        return (float) Math.sqrt(h * h + v * v);
    }

    @SuppressWarnings("unused")
    public void hide(int x, int y) {
        hide(x, y, DEFAULT_DURATION);
    }

    public void hide(int x, int y, int duration) {
        if (x < 0 || x > getWidth() || y < 0 || y > getHeight()) {
            throw new RuntimeException("Center point out of range or call method " +
                    "when View is not initialed yet.");
        }

        if (x != mClipCenterX || y != mClipCenterY) {
            mClipCenterX = x;
            mClipCenterY = y;
            mClipRadius = getMaxRadius(x, y);
        }

        if (mAnimator != null && mAnimator.isRunning()) {
            mAnimator.cancel();
        }

        mAnimator = ObjectAnimator.ofFloat(this, "clipRadius", 0f);
        mAnimator.setInterpolator(new BakedBezierInterpolator());
        mAnimator.setDuration(duration);
        mAnimator.start();
    }

    @Override
    protected boolean drawChild(@NonNull Canvas canvas, @NonNull View child, long drawingTime) {
        if (indexOfChild(child) == getChildCount() - 1) {
            boolean result;
            mClipPath.reset();
            mClipPath.addCircle(mClipCenterX, mClipCenterY, mClipRadius, Path.Direction.CW);

            canvas.save();
            canvas.clipPath(mClipPath);
            result = super.drawChild(canvas, child, drawingTime);
            canvas.restore();
            return result;
        } else {
            return super.drawChild(canvas, child, drawingTime);
        }
    }
}
