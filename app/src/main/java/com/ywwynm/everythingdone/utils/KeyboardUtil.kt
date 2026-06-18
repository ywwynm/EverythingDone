@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

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
        showKeyboard(findWindow(view), view)
    }

    @JvmStatic
    fun showKeyboard(window: Window?, view: View?) {
        view ?: return

        view.requestFocus()
        val actualWindow = window ?: findWindow(view) ?: return
        WindowCompat.getInsetsController(actualWindow, view)
                .show(WindowInsetsCompat.Type.ime())
    }

    @JvmStatic
    fun hideKeyboard(view: View?) {
        hideKeyboard(findWindow(view), view)
    }

    @JvmStatic
    fun hideKeyboard(window: Window?) {
        hideKeyboard(window, window?.decorView)
    }

    @JvmStatic
    fun hideKeyboard(window: Window?, view: View?) {
        val anchor = view ?: window?.decorView ?: return
        anchor.clearFocus()
        val actualWindow = window ?: findWindow(anchor) ?: return
        WindowCompat.getInsetsController(actualWindow, anchor)
                .hide(WindowInsetsCompat.Type.ime())
    }

    private fun findWindow(view: View?): Window? {
        return findActivity(view?.context)?.window
    }

    private fun findActivity(context: Context?): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) {
                return current
            }
            val baseContext = current.baseContext
            if (baseContext === current) {
                return null
            }
            current = baseContext
        }
        return null
    }

    interface KeyboardCallback {
        fun onKeyboardShow(keyboardHeight: Int)
        fun onKeyboardHide()
    }

    @JvmStatic
    fun addKeyboardCallback(window: Window?, callback: KeyboardCallback?) {
        val decorView: View = window!!.decorView
        decorView.getViewTreeObserver().addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {

                    private val r: Rect = Rect()
                    private var initialDiff: Int = -1
                    private val possibleKeyboardHeight: Float =
                            96 * DisplayUtil.getScreenDensity(decorView.context)
                    private var mKeyboardOpened: Boolean = false

                    override fun onGlobalLayout() {
                        // decor.getRoot.getHeight is always full height
                        val fullHeight: Int = decorView.getRootView().height

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
                                callback?.onKeyboardShow(diff)
                            }
                        } else if (diff == 0) {
                            if (mKeyboardOpened) {
                                callback?.onKeyboardHide()
                                mKeyboardOpened = false
                            }
                        }
                    }
                })
    }
}
