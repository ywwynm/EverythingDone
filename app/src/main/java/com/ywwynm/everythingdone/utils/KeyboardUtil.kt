@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.utils

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager

/**
 * Created by ywwynm on 2015/7/27.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * show/hide keyboard and etc
 */
object KeyboardUtil {

    const val TAG: String = "KeyboardUtil"

    const val HIDE_DELAY: Int = 280

    @JvmStatic
    fun showKeyboard(view: View?) {
        if (view == null) {
            return
        }

        view.requestFocus()
        val imm: InputMethodManager = view.getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_FORCED)
    }

    @JvmStatic
    fun hideKeyboard(view: View?) {
        if (view == null) {
            return
        }
        view.clearFocus()

        val imm: InputMethodManager = view.getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (!imm.isActive()) {
            return
        }
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0)
    }

    @JvmStatic
    fun hideKeyboard(window: Window?) {
        window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    interface KeyboardCallback {
        fun onKeyboardShow(keyboardHeight: Int)
        fun onKeyboardHide()
    }

    @JvmStatic
    fun addKeyboardCallback(window: Window?, callback: KeyboardCallback?) {
        val decorView: View = window!!.getDecorView()
        decorView.getViewTreeObserver().addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {

                    private val r: Rect = Rect()
                    private var initialDiff: Int = -1
                    private val possibleKeyboardHeight: Float =
                            96 * DisplayUtil.getScreenDensity(decorView.getContext())
                    private var mKeyboardOpened: Boolean = false

                    override fun onGlobalLayout() {
                        // decor.getRoot.getHeight is always full height
                        val fullHeight: Int = decorView.getRootView().getHeight()

                        // r will be populated with the coordinates of your view that area still visible.
                        decorView.getWindowVisibleDisplayFrame(r)

                        // get the height diff as px
                        val heightDiff: Int = fullHeight - (r.bottom - r.top)
                        // set the initialDiff at the beginning.
                        if (initialDiff == -1) {
                            initialDiff = heightDiff
                        }

                        val diff: Int = heightDiff - initialDiff
                        // if it could be a keyboard add the padding to the view
                        if (diff > possibleKeyboardHeight) {
                            if (!mKeyboardOpened) {
                                mKeyboardOpened = true
                                if (callback != null) {
                                    callback.onKeyboardShow(diff)
                                }
                            }
                        } else if (diff == 0) {
                            if (mKeyboardOpened) {
                                if (callback != null) {
                                    callback.onKeyboardHide()
                                }
                                mKeyboardOpened = false
                            }
                        }
                    }
                })
    }
}
