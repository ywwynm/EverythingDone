/**
 * Created by yugy on 14/11/21.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * visit https://github.com/kyze8439690/RevealLayout to get more supports.
 *
 * Changed by ywwynm on 15/5/20.
 */
@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.views.reveal

import android.animation.Animator
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.sqrt

/**
 * circularReview动画实现方式：
 * ObjectAnimator更改该View的clipRadius属性，加速器BakedBezierInterpolator设定了许多"状态"，每次动画
 * 执行到一个状态，均会调用setClipRadius方法，其中的invalidate方法重绘该View本身，同时导致其childView
 * 重绘，即调用drawChild方法。在drawChild方法中，canvas根据每次重绘时的clipRadius绘制圆形path以"切割"
 * 画布，因此能以圆形扩大的方式显示childView。
 */

open class RevealLayout : FrameLayout {

    private val mClipPath: Path = Path()
    private var mClipRadius: Float = 0f
    private var mClipCenterX: Int = 0
    private var mClipCenterY: Int = 0
    private var mAnimator: Animator? = null

    constructor(context: Context) : this(context, null)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {

        // clipPath()仅在4.3以上支持硬件加速
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        mClipCenterX = w / 2
        mClipCenterY = h / 2
        mClipRadius = sqrt((w * w + h * h).toDouble()).toFloat() / 2

        super.onSizeChanged(w, h, oldw, oldh)
    }

    @Suppress("unused")
    fun getClipRadius(): Float {
        return mClipRadius
    }

    @Suppress("unused")
    fun setClipRadius(clipRadius: Float) {
        mClipRadius = clipRadius
        invalidate()
    }

    fun show(x: Int, y: Int) {
        show(x, y, DEFAULT_DURATION)
    }

    fun show(x: Int, y: Int, duration: Int) {
        if (x < 0 || x > width || y < 0 || y > height) {
            throw RuntimeException("Center point out of range or call method " +
                    "when View is not initialed yet.")
        }

        mClipCenterX = x
        mClipCenterY = y

        val maxRadius: Float = getMaxRadius(x, y)

        val animator: Animator? = mAnimator
        if (animator != null && animator.isRunning) {
            animator.cancel()
        }

        mAnimator = ObjectAnimator.ofFloat(this, "clipRadius", 0f, maxRadius).apply {
            interpolator = BakedBezierInterpolator()
            setDuration(duration.toLong())
            start()
        }
    }

    private fun getMaxRadius(x: Int, y: Int): Float {
        val h: Int = max(x, width - x)
        val v: Int = max(y, height - y)
        return sqrt((h * h + v * v).toDouble()).toFloat()
    }

    @Suppress("unused")
    fun hide(x: Int, y: Int) {
        hide(x, y, DEFAULT_DURATION)
    }

    fun hide(x: Int, y: Int, duration: Int) {
        if (x < 0 || x > width || y < 0 || y > height) {
            throw RuntimeException("Center point out of range or call method " +
                    "when View is not initialed yet.")
        }

        if (x != mClipCenterX || y != mClipCenterY) {
            mClipCenterX = x
            mClipCenterY = y
            mClipRadius = getMaxRadius(x, y)
        }

        val animator: Animator? = mAnimator
        if (animator != null && animator.isRunning) {
            animator.cancel()
        }

        mAnimator = ObjectAnimator.ofFloat(this, "clipRadius", 0f).apply {
            interpolator = BakedBezierInterpolator()
            setDuration(duration.toLong())
            start()
        }
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (indexOfChild(child) == childCount - 1) {
            mClipPath.reset()
            mClipPath.addCircle(mClipCenterX.toFloat(), mClipCenterY.toFloat(), mClipRadius, Path.Direction.CW)

            canvas.save()
            canvas.clipPath(mClipPath)
            val result: Boolean = super.drawChild(canvas, child, drawingTime)
            canvas.restore()
            return result
        } else {
            return super.drawChild(canvas, child, drawingTime)
        }
    }

    companion object {
        @Suppress("unused")
        private const val TAG: String = "RevealLayout"

        private const val DEFAULT_DURATION: Int = 600
    }
}
