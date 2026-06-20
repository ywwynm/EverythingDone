package com.ywwynm.everythingdone.views.pickers

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.graphics.Outline
import android.os.Build
import android.transition.TransitionValues
import android.transition.Visibility
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.PopupWindow

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.utils.DisplayUtil

/**
 * Created by ywwynm on 2015/8/18.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Simple Picker for EverythingDone using a PopupWindow to show contents.
 */
abstract class PopupPicker(activity: Activity, parent: View, popupAnimStyle: Int) {

    @JvmField protected var mActivity: Activity = activity
    @JvmField protected var mScreenDensity: Float = DisplayUtil.getScreenDensity(activity)

    @JvmField protected var mPopupWindow: PopupWindow
    @JvmField protected var mParent: View = parent
    /**
     * The on-screen view this picker anchors to. Subclasses position the popup
     * from their own view-specific anchor point in window coordinates. The
     * chosen Gravity flags, transition pivot, and inset compensation stay
     * subclass-private; mAnchor is just the "where".
     */
    @JvmField protected var mAnchor: View? = null
    @JvmField protected var mContentView: View =
        LayoutInflater.from(activity).inflate(R.layout.rv_popup_picker, null)!!
    @JvmField protected var mRecyclerView: RecyclerView = mContentView.findViewById<View>(R.id.rv_popup_picker) as RecyclerView

    init {
        mPopupWindow = PopupWindow(mContentView,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        mPopupWindow.setBackgroundDrawable(
            ContextCompat.getDrawable(activity, R.drawable.bg_app_chrome_surface_elevated_rounded)
        )
        mContentView.background =
            ContextCompat.getDrawable(activity, R.drawable.bg_app_chrome_surface_elevated_rounded)
        installRoundedOutline(mContentView)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val popupElevation = 12f * mScreenDensity
            mPopupWindow.elevation = popupElevation
            mContentView.elevation = popupElevation
        }
        mContentView.setOnKeyListener(object : View.OnKeyListener {
            override fun onKey(v: View, keyCode: Int, event: KeyEvent): Boolean {
                if (keyCode == KeyEvent.KEYCODE_BACK
                        && event.repeatCount == 1) {
                    if (mPopupWindow.isShowing) {
                        mPopupWindow.dismiss()
                        return true
                    }
                }
                return false
            }
        })
        mPopupWindow.animationStyle = popupAnimStyle
        mPopupWindow.isOutsideTouchable = true
        mPopupWindow.isFocusable = true
        // Keep the soft keyboard visible when this popup is shown on top of
        // an active EditText. INPUT_METHOD_NOT_NEEDED tells the framework
        // "this popup doesn't participate in IME — don't change the IME's
        // visibility because of me." Without this, setFocusable(true)
        // forces the IME to hide as the popup grabs window focus, which on
        // an edge-to-edge activity also re-triggers the bottom-bar inset
        // chain and produces a visible flicker (IME drops, bottom bar
        // drops, popup auto-dismisses mid-show). Matches the pre-edge-to-
        // edge behaviour where the IME and these pickers happily coexisted.
        mPopupWindow.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
    }

    private fun installRoundedOutline(view: View) {
        val radius = view.resources.getDimension(R.dimen.app_chrome_dialog_popup_corner_radius)
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
    }

    protected fun getPopupContentWidthForPositioning(): Int {
        measurePopupContentForPositioning()
        val recyclerWidth: Int = mRecyclerView.layoutParams?.width ?: 0
        return maxOf(
                mContentView.measuredWidth,
                if (recyclerWidth > 0) recyclerWidth else 0
        )
    }

    protected fun getPopupContentHeightForPositioning(): Int {
        measurePopupContentForPositioning()
        val recyclerHeight: Int = mRecyclerView.layoutParams?.height ?: 0
        return maxOf(
                mContentView.measuredHeight,
                if (recyclerHeight > 0) recyclerHeight else 0
        )
    }

    private fun measurePopupContentForPositioning() {
        mContentView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
    }

    protected fun installContentSurfaceScaleTransition(
            pivotXFraction: Float,
            pivotYFraction: Float
    ) {
        mPopupWindow.animationStyle = 0
        mPopupWindow.isClippingEnabled = false
        mPopupWindow.setBackgroundDrawable(null)
        mPopupWindow.enterTransition = PopupSurfaceScaleTransition(
                pivotXFraction, pivotYFraction, appearing = true)
        mPopupWindow.exitTransition = PopupSurfaceScaleTransition(
                pivotXFraction, pivotYFraction, appearing = false)
    }

    fun setAnchor(anchor: View) {
        mAnchor = anchor
    }

    abstract fun updateAnchor()

    abstract fun show()

    abstract fun pickForUI(index: Int)

    abstract fun getPickedIndex(): Int

    fun dismiss() {
        if (mPopupWindow.isShowing) {
            mPopupWindow.dismiss()
        }
    }

    fun isShowing(): Boolean {
        return mPopupWindow.isShowing
    }

    companion object {
        @JvmField var TAG: String = "PopupPicker"
    }
}

private class PopupSurfaceScaleTransition(
        private val pivotXFraction: Float,
        private val pivotYFraction: Float,
        private val appearing: Boolean
) : Visibility() {

    init {
        duration = POPUP_SURFACE_ANIMATION_MS
        interpolator = if (appearing) {
            DecelerateInterpolator()
        } else {
            AccelerateInterpolator()
        }
    }

    override fun onAppear(
            sceneRoot: ViewGroup,
            view: View,
            startValues: TransitionValues?,
            endValues: TransitionValues?
    ): Animator? {
        return createAnimator(view, fromScale = 0f, toScale = 1f)
    }

    override fun onDisappear(
            sceneRoot: ViewGroup,
            view: View,
            startValues: TransitionValues?,
            endValues: TransitionValues?
    ): Animator? {
        return createAnimator(view, fromScale = 1f, toScale = 0f)
    }

    private fun createAnimator(view: View, fromScale: Float, toScale: Float): Animator {
        view.pivotX = view.width * pivotXFraction
        view.pivotY = view.height * pivotYFraction
        view.scaleX = fromScale
        view.scaleY = fromScale
        return AnimatorSet().apply {
            playTogether(
                    ObjectAnimator.ofFloat(view, View.SCALE_X, fromScale, toScale),
                    ObjectAnimator.ofFloat(view, View.SCALE_Y, fromScale, toScale)
            )
        }
    }

    companion object {
        private const val POPUP_SURFACE_ANIMATION_MS = 160L
    }
}
