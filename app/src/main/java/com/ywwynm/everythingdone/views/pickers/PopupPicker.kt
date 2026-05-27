package com.ywwynm.everythingdone.views.pickers

import android.app.Activity
import android.graphics.Outline
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
     * based on this view's location relative to [mParent]: the same
     * "compute anchor's screen coordinates, derive popup offset" path applies
     * to ColorPicker (right-aligned to a toolbar menu item) and DateTimePicker
     * (above a bottom-bar text button). The chosen Gravity flags and any
     * fine-tuning constants stay subclass-private; mAnchor is just the "where".
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
