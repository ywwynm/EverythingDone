@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.app.Dialog
import android.app.DialogFragment
import android.app.FragmentManager
import android.os.Bundle
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window

/**
 * Created by ywwynm on 2015/9/29.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A subclass of [DialogFragment] without dialog title
 */
abstract class BaseDialogFragment : DialogFragment() {

    @JvmField
    protected var mContentView: View? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        mContentView = inflater.inflate(getLayoutResource(), container, false)
        return mContentView
    }

    @LayoutRes
    protected abstract fun getLayoutResource(): Int

    @Suppress("UNCHECKED_CAST")
    protected fun <T : View?> f(view: View?, @IdRes id: Int): T {
        return view!!.findViewById<View>(id) as T
    }

    protected fun <T : View?> f(@IdRes id: Int): T {
        return f(mContentView, id)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog: Dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun show(manager: FragmentManager, tag: String?) {
        if (!isAdded) {
            try {
                super.show(manager, tag)
            } catch (ignored: IllegalStateException) {
                // ignore this
            }
        }
    }

    override fun dismiss() {
        super.dismissAllowingStateLoss()
    }
}
