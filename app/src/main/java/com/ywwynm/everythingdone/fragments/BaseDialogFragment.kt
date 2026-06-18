@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.app.Dialog
import android.app.DialogFragment
import android.app.FragmentManager
import android.graphics.Outline
import android.os.Bundle
import android.view.ContextThemeWrapper
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.utils.BackgroundUtil

/**
 * Created by ywwynm on 2015/9/29.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A subclass of [DialogFragment] without dialog title
 */
abstract class BaseDialogFragment : DialogFragment() {

    @JvmField
    protected var mContentView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.EverythingDoneTheme_Dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val themedInflater = inflater.cloneInContext(
            dialog?.context ?: ContextThemeWrapper(activity!!, R.style.EverythingDoneTheme_Dialog)
        )
        mContentView = themedInflater.inflate(getLayoutResource(), container, false)
        installRoundedOutline(mContentView)
        installCompactDialogButtonRipples(mContentView)
        return mContentView
    }

    @LayoutRes
    protected abstract fun getLayoutResource(): Int

    protected open fun getDialogWindowWidthPx(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

    @Suppress("UNCHECKED_CAST")
    protected fun <T : View?> f(view: View?, @IdRes id: Int): T {
        return view!!.findViewById<View>(id) as T
    }

    protected fun <T : View?> f(@IdRes id: Int): T {
        return f(mContentView, id)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog ?: return
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(dialog.context, R.drawable.bg_app_chrome_surface_elevated_rounded)
        )
        installRoundedOutline(dialog.window?.decorView)
        dialog.window?.setLayout(
            getDialogWindowWidthPx(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun installRoundedOutline(view: View?) {
        view ?: return
        val radius = view.resources.getDimension(R.dimen.app_chrome_dialog_popup_corner_radius)
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
    }

    private fun installCompactDialogButtonRipples(view: View?) {
        view ?: return
        if (view is TextView && isCompactDialogButton(view)) {
            BackgroundUtil.installAppChromeDialogActionButton(view, view.context)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                installCompactDialogButtonRipples(view.getChildAt(i))
            }
        }
    }

    private fun isCompactDialogButton(view: TextView): Boolean {
        if (view.id == View.NO_ID) return false
        val entryName = try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: Exception) {
            return false
        }
        if (!entryName.contains("_as_bt")) return false

        val lp = view.layoutParams ?: return false
        if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) return false

        return true
    }

    override fun show(manager: FragmentManager, tag: String?) {
        if (!isAdded) {
            try {
                super.show(manager, tag)
            } catch (_: IllegalStateException) {
                // ignore this
            }
        }
    }

    override fun dismiss() {
        super.dismissAllowingStateLoss()
    }
}
