package com.ywwynm.everythingdone.views

import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R

/**
 * Created by ywwynm on 2015/7/4.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A simple Snackbar inspired by Material Design based on PopupWindow.
 *
 * updated on 2016/8/30
 * Change the implementation to [ViewGroup.addView] instead
 * of PopupWindow, fixed problems when there is a NavigationBar and window has translucent flags.
 * Besides, this implementation will also be compatible with multi-window announced in Android Nougat.
 * Now, the animation and behavior is like official [com.google.android.material.snackbar.Snackbar],
 * but this one suits Material Design better than that.
 */
open class Snackbar(
        app: App,
        type: Int,
        targetParent: ViewGroup,
        bindingFab: FloatingActionButton?) {

    private val mApp: App = app
    private val mType: Int = type
    private val mHeight: Float
    /** True between [show] and [dismiss]. Replaces the
     *  old translation-based check after the hidden-rest position became
     *  dynamic (depends on the current bottom system-bar inset). */
    private var mIsShowing: Boolean = false

    private var mHideThread: Thread? = null

    private val mContentView: View
    private val mTvMessage: TextView
    private var mBtUndo: Button? = null
    private val mTargetParent: ViewGroup = targetParent

    private val mBindingFab: FloatingActionButton? = bindingFab

    init {
        if (mType == NORMAL) {
            mHideThread = object : Thread() {
                override fun run() {
                    if (isShowing()) {
                        dismiss()
                    }
                }
            }
        }

        mContentView = LayoutInflater.from(targetParent.getContext())
                .inflate(R.layout.snackbar_undo, null)!!
        mTvMessage = mContentView.findViewById<View>(R.id.tv_message) as TextView
        if (mType == UNDO) {
            mBtUndo = mContentView.findViewById<View>(R.id.bt_undo) as Button
            mBtUndo!!.setVisibility(View.VISIBLE)
        }

        mHeight = mApp.getResources()!!.getDimension(R.dimen.sb_height)
    }

    fun show() {
        if (isShowing()) {
            return
        }

        if (mBindingFab != null &&
                mApp.getLimit() <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
            mBindingFab.showFromBottom()
            mBindingFab.raise(mHeight)
        }

        // Edge-to-edge: read the current gesture / 3-button nav-bar inset and
        // both push the Snackbar up by that amount (so it sits above the nav
        // bar) and extend the hidden-state offset by it (so dismiss really
        // slides off-screen, not just down to the nav bar where a strip would
        // peek through).
        val insetBottom: Int = readBottomSystemInset()
        if (mContentView.getParent() == null) {
            val flp: FrameLayout.LayoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, mHeight.toInt())
            flp.gravity = Gravity.BOTTOM
            flp.bottomMargin = insetBottom
            mTargetParent.addView(mContentView, flp)
        } else {
            // Re-attached / re-shown — re-sync the bottom margin in case the
            // inset changed (rotation, multi-window, gesture-bar toggle).
            val lp: ViewGroup.LayoutParams? = mContentView.getLayoutParams()
            if (lp is FrameLayout.LayoutParams) {
                lp.bottomMargin = insetBottom
                mContentView.setLayoutParams(lp)
            }
        }

        mContentView.setTranslationY(mHeight + insetBottom)
        mContentView.animate()!!.translationY(0f).setDuration(200).start()
        mIsShowing = true

        if (mType == NORMAL) {
            mTargetParent.postDelayed(mHideThread, 1200 + 160L)
        }
    }

    fun dismiss() {
        try {
            mIsShowing = false
            val insetBottom: Int = readBottomSystemInset()
            mContentView.animate()!!.translationY(mHeight + insetBottom)
                    .setDuration(200).start()
            //mPopupWindow.dismiss();
            if (mType == NORMAL && mHideThread != null) {
                mHideThread!!.interrupt()
            }
            if (mBindingFab != null &&
                    mApp.getLimit() <= Def.LimitForGettingThings.GOAL_UNDERWAY) {
                mBindingFab.fall()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isShowing(): Boolean {
        // Use the explicit flag rather than translationY: the hidden rest
        // position is now dynamic (mHeight + bottom inset), so a plain
        // numeric comparison would mis-fire on inset changes.
        return mIsShowing && mContentView.getParent() != null
    }

    /** Read the current bottom system-bar inset from the target parent's
     *  attached root window. Returns 0 before the view is attached or when
     *  the platform reports no insets (rare, mostly older OEMs). */
    private fun readBottomSystemInset(): Int {
        val insets: WindowInsetsCompat = ViewCompat.getRootWindowInsets(mTargetParent) ?: return 0
        return insets.getInsets(WindowInsetsCompat.Type.systemBars()
                or WindowInsetsCompat.Type.displayCutout()).bottom
    }

    fun setUndoListener(onClickListener: View.OnClickListener?) {
        if (mType == NORMAL) {
            throw IllegalStateException("Type must be Snackbar.UNDO")
        }
        mBtUndo!!.setOnClickListener(onClickListener)
    }

    fun setMessage(@StringRes stringRes: Int) {
        mTvMessage.setText(mApp.getString(stringRes))
    }

    fun setMessage(msg: String) {
        mTvMessage.setText(msg)
    }

    fun setUndoText(@StringRes stringRes: Int) {
        setUndoText(mApp.getString(stringRes)!!)
    }

    fun setUndoText(text: String) {
        if (mType == NORMAL) {
            throw IllegalStateException("Type must be Snackbar.UNDO")
        }
        mBtUndo!!.setText(text)
    }

    fun getHeight(): Float {
        return mHeight
    }

    companion object {
        const val TAG: String = "Snackbar"

        const val NORMAL: Int = 0
        const val UNDO: Int = 1
    }
}
