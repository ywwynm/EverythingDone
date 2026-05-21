@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.content.DialogInterface
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.utils.KeyboardUtil

/**
 * Created by ywwynm on 2015/9/20.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Shown when user clicks [android.text.style.URLSpan]s in
 * [com.ywwynm.everythingdone.activities.DetailActivity].
 */
open class TwoOptionsDialogFragment : BaseDialogFragment() {

    private var mShouldShowKeyboardAfterDismiss: Boolean = false
    private var mViewToFocusAfterDismiss: View? = null

    private var mIconResStart: Int = 0
    private var mIconResEnd: Int = 0
    private var mActionResStart: Int = 0
    private var mActionResEnd: Int = 0
    private var mListenerStart: View.OnClickListener? = null
    private var mListenerEnd: View.OnClickListener? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        val tvStart: TextView = f(R.id.tv_action_start)!!
        val tvEnd: TextView   = f(R.id.tv_action_end)!!

        if (mIconResStart != 0) {
            tvStart.setCompoundDrawablesWithIntrinsicBounds(0, mIconResStart, 0, 0)
        }
        if (mActionResStart != 0) {
            tvStart.setText(mActionResStart)
        }
        tvStart.setOnClickListener(mListenerStart)

        if (mIconResEnd != 0) {
            tvEnd.setCompoundDrawablesWithIntrinsicBounds(0, mIconResEnd, 0, 0)
        }
        if (mActionResEnd != 0) {
            tvEnd.setText(mActionResEnd)
        }
        tvEnd.setOnClickListener(mListenerEnd)

        return mContentView
    }

    override fun getLayoutResource(): Int = R.layout.fragment_two_action_picker

    open fun setStartAction(
        @DrawableRes iconRes: Int, @StringRes actionRes: Int,
        listener: View.OnClickListener?
    ) {
        mIconResStart   = iconRes
        mActionResStart = actionRes
        mListenerStart  = listener
    }

    open fun setEndAction(
        @DrawableRes iconRes: Int, @StringRes actionRes: Int,
        listener: View.OnClickListener?
    ) {
        mIconResEnd   = iconRes
        mActionResEnd = actionRes
        mListenerEnd  = listener
    }

    open fun setShouldShowKeyboardAfterDismiss(shouldShowKeyboardAfterDismiss: Boolean) {
        mShouldShowKeyboardAfterDismiss = shouldShowKeyboardAfterDismiss
    }

    open fun setViewToFocusAfterDismiss(viewToFocusAfterDismiss: View?) {
        mViewToFocusAfterDismiss = viewToFocusAfterDismiss
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (mShouldShowKeyboardAfterDismiss && mViewToFocusAfterDismiss != null) {
            mViewToFocusAfterDismiss!!.postDelayed({
                KeyboardUtil.showKeyboard(mViewToFocusAfterDismiss)
            }, 60)
        }
        mListenerStart = null
        mListenerEnd   = null
        super.onDismiss(dialog)
    }

    companion object {
        const val TAG: String = "TwoOptionsDialogFragment"
    }
}
