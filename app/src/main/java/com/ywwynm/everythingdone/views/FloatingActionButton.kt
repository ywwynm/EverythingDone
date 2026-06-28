package com.ywwynm.everythingdone.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.recyclerview.widget.RecyclerView
import android.util.AttributeSet
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil

/**
 * Created by ywwynm on 2015/8/16.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A custom FloatingActionButton with more animation for appearing/disappearing.
 * It can also attach to a RecyclerView and appear/disappear according to scrolling.
 * Based on FloatingActionButton in Material Design Library.
 */
open class FloatingActionButton : com.google.android.material.floatingactionbutton.FloatingActionButton {

    private var mAccelerateDecelerateInterpolator: AccelerateDecelerateInterpolator? = null
    private var mAccelerateInterpolator: AccelerateInterpolator? = null
    private var mOvershootInterpolator: OvershootInterpolator? = null

    private var mOnScreen: Boolean = true
    private var mShrunk: Boolean = false

    private var mSnackbars: Array<Snackbar?>? = null
    private var mThingBackground: ThingBackground? = null
    private val mThingBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mThingBackgroundBounds = RectF()

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init()
    }

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    private fun init() {
        mAccelerateDecelerateInterpolator = AccelerateDecelerateInterpolator()
        mAccelerateInterpolator = AccelerateInterpolator()
        mOvershootInterpolator = OvershootInterpolator()
    }

    fun setThingBackground(background: ThingBackground?, fallbackColor: Int) {
        if (background == null) {
            mThingBackground = null
            backgroundTintList = ColorStateList.valueOf(fallbackColor)
            invalidate()
            return
        }

        if (background.mode === ThingBackground.Mode.PURE) {
            mThingBackground = null
            backgroundTintList = ColorStateList.valueOf(background.color)
        } else {
            mThingBackground = background
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        }
        invalidate()
    }

    fun setThingBackgroundWithAdaptiveIcon(background: ThingBackground?, fallbackColor: Int) {
        setThingBackground(background, fallbackColor)
        // 前景明暗以完整背景为准：accent+accent2 渐变按深色处理 → 偏白图标 / 波纹（而非按代表色误判为浅色）。
        val light = if (background != null) {
            BackgroundUtil.isLight(background)
        } else {
            BackgroundUtil.isLight(fallbackColor)
        }
        val iconColor = onColorForLight(light, if (light) 0.54f else 0.86f)
        val ripple = onColorForLight(light, 0.24f)
        imageTintList = ColorStateList.valueOf(iconColor)
        rippleColor = ripple
        setForegroundRippleColor(ripple)
    }

    private fun onColorForLight(light: Boolean, alpha: Float): Int {
        val rgb = if (light) 0x000000 else 0xFFFFFF
        val a = Math.round(alpha.coerceIn(0f, 1f) * 255f)
        return (a shl 24) or rgb
    }

    fun setForegroundRippleColor(color: Int) {
        foreground = BackgroundUtil.circularRipple(color)
    }

    override fun onDraw(canvas: Canvas) {
        val background = mThingBackground
        if (background != null && width > 0 && height > 0) {
            mThingBackgroundBounds.set(0f, 0f, width.toFloat(), height.toFloat())
            mThingBackgroundPaint.shader = BackgroundUtil.createLinearGradient(
                background,
                width.toFloat(),
                height.toFloat()
            )
            canvas.drawOval(mThingBackgroundBounds, mThingBackgroundPaint)
            mThingBackgroundPaint.shader = null
        }
        super.onDraw(canvas)
    }

    private fun getMarginBottom(): Int {
        // Read the live LayoutParams instead of the XML-time 16dp constant —
        // DisplayUtil.applyBottomInsetAsMargin adds the system-bar inset to
        // the FAB's bottomMargin at runtime, so on devices with a gesture /
        // 3-button nav bar the real margin is 16dp + insets.bottom. Using
        // the constant caused hideToBottom() to under-translate by the inset
        // height, leaving a sliver of FAB peeking above the nav bar.
        val lp: android.view.ViewGroup.LayoutParams? = layoutParams
        if (lp is android.view.ViewGroup.MarginLayoutParams) {
            return lp.bottomMargin
        }
        return (16 * resources!!.displayMetrics!!.density).toInt()
    }

    fun bindSnackbars(vararg snackbars: Snackbar?) {
        @Suppress("UNCHECKED_CAST")
        mSnackbars = snackbars as Array<Snackbar?>
    }

    fun attachToRecyclerView(recyclerView: RecyclerView) {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) {
                    hideToBottom()
                } else {
                    showFromBottom()
                }
            }
        })
    }

    fun raise(y: Float) {
        animate()!!.translationY(-y).setInterpolator(null).setDuration(200)
    }

    fun fall() {
        animate()!!.translationY(0f).setInterpolator(null).setDuration(200)
    }

    fun spread() {
        showFromBottom()
        if (mShrunk) {
            animate()!!.scaleX(1.0f).setInterpolator(mOvershootInterpolator).setDuration(200)
            animate()!!.scaleY(1.0f).setInterpolator(mOvershootInterpolator).setDuration(200)
            isClickable = true
            mShrunk = false
        }
    }

    fun shrink() {
        if (!mShrunk) {
            isClickable = false
            animate()!!.scaleX(0f).setInterpolator(mAccelerateInterpolator).setDuration(160)
            animate()!!.scaleY(0f).setInterpolator(mAccelerateInterpolator).setDuration(160)
            mShrunk = true
        }
    }

    fun shrinkWithoutAnim() {
        if (!mShrunk) {
            isClickable = false
            scaleX = 0f
            scaleY = 0f
            mShrunk = true
        }
    }

    fun showFromBottom() {
        if (!mOnScreen) {
            animate()!!.translationY(if (isSnackbarShowing()) -mSnackbars!![0]!!.getHeight() else 0f)
                    .setInterpolator(mAccelerateDecelerateInterpolator).setDuration(200)
            isClickable = true
            mOnScreen = true
        }
    }

    fun hideToBottom() {
        if (mOnScreen) {
            isClickable = false
            animate()!!.translationY((height + getMarginBottom()).toFloat())
                    .setInterpolator(mAccelerateDecelerateInterpolator).setDuration(200)
            mOnScreen = false
        }
    }

    private fun isSnackbarShowing(): Boolean {
        val snackbars: Array<Snackbar?> = mSnackbars ?: return false
        for (snackbar in snackbars) {
            if (snackbar != null && snackbar.isShowing()) {
                return true
            }
        }
        return false
    }

    companion object {
        const val TAG: String = "FloatingActionButton"
    }
}
