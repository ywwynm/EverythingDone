@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.content.DialogInterface
import android.os.Bundle
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil

/**
 * Created by ywwynm on 2015/10/9.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A subclass of DialogFragment to show alert information.
 */
open class AlertDialogFragment : BaseDialogFragment() {

    private var mColors: IntArray = intArrayOf(0, 0, 0)

    private var mTitle: String? = null
    private var mContent: String? = null

    private var mConfirmText: String? = null
    private var mCancelText: String? = null

    private var mShowCancel: Boolean = true
    private var mConfirmListener: ConfirmListener? = null
    private var mCancelListener: CancelListener? = null
    private var mConfirmed: Boolean = false

    /** Phase 8: backing fields for the ThingBackground-typed setters. */
    private var mTitleBg: ThingBackground? = null
    private var mConfirmBg: ThingBackground? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        val activity = activity!!
        if (mColors[0] == 0) {
            mColors[0] = ContextCompat.getColor(activity, R.color.app_chrome_on_surface_strong)
        }
        if (mColors[1] == 0) {
            val contentColor = ContextCompat.getColor(activity, R.color.app_chrome_on_surface_secondary)
            mColors[1] = contentColor
        }
        if (mColors[2] == 0) {
            mColors[2] = ContextCompat.getColor(activity, R.color.app_chrome_on_surface_strong)
        }

        val tvTitle: TextView       = f(R.id.tv_title_alert)!!
        val tvContent: TextView     = f(R.id.tv_content_alert)!!
        val tvConfirmAsBt: TextView = f(R.id.tv_confirm_as_bt_alert)!!
        val tvCancelAsBt: TextView  = f(R.id.tv_cancel_as_bt_alert)!!

        if (mTitle != null) {
            tvTitle.text = mTitle
            applyAccent(tvTitle, mTitleBg, mColors[0])
        } else {
            tvTitle.visibility = View.GONE
        }

        if (mContent != null) {
            tvContent.setTextColor(mColors[1])
            tvContent.text = mContent
        } else {
            tvContent.visibility = View.GONE
        }

        if (mConfirmText != null) {
            tvConfirmAsBt.text = mConfirmText
        }
        applyAccent(tvConfirmAsBt, mConfirmBg, mColors[2])
        tvConfirmAsBt.setOnClickListener {
            if (mConfirmListener != null) {
                mConfirmListener!!.onConfirm()
            }
            mConfirmed = true
            dismiss()
        }

        if (mShowCancel) {
            if (mCancelText != null) {
                tvCancelAsBt.text = mCancelText
            }
            tvCancelAsBt.setOnClickListener { dismiss() }
        } else {
            tvCancelAsBt.visibility = View.GONE
        }

        return mContentView
    }

    override fun getLayoutResource(): Int = R.layout.fragment_alert

    override fun onResume() {
        super.onResume()
        val screenDensity: Float = DisplayUtil.getScreenDensity(activity)
        val window: Window? = dialog!!.window
        window?.setLayout((screenDensity * 320).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (!mConfirmed && mCancelListener != null) {
            mCancelListener!!.onCancel()
        }
        mConfirmListener = null
        mCancelListener  = null
        super.onDismiss(dialog)
    }

    /** Phase 8: paint `tv` with `bg` if non-null; falls back to `fallbackColor` otherwise. */
    private fun applyAccent(tv: TextView, bg: ThingBackground?, fallbackColor: Int) {
        if (bg != null) {
            BackgroundUtil.applyTextBackground(tv, bg)
        } else {
            tv.setTextColor(fallbackColor)
        }
    }

    open fun setTitleBackground(bg: ThingBackground?) {
        mTitleBg = bg
        if (bg != null) mColors[0] = bg.representativeColor()
    }

    open fun setConfirmBackground(bg: ThingBackground?) {
        mConfirmBg = bg
        if (bg != null) mColors[2] = bg.representativeColor()
    }

    open fun setTitleColor(color: Int) {
        mTitleBg = null
        mColors[0] = color
    }

    open fun setContentColor(color: Int) {
        mColors[1] = color
    }

    open fun setConfirmColor(color: Int) {
        mConfirmBg = null
        mColors[2] = color
    }

    open fun setTitle(title: String?) {
        mTitle = title
    }

    open fun setContent(content: String?) {
        mContent = content
    }

    open fun setConfirmText(confirmText: String?) {
        mConfirmText = confirmText
    }

    open fun setConfirmListener(listener: ConfirmListener?) {
        mConfirmListener = listener
    }

    open fun setCancelText(cancelText: String?) {
        mCancelText = cancelText
    }

    open fun setCancelListener(listener: CancelListener?) {
        mCancelListener = listener
    }

    open fun setShowCancel(showCancel: Boolean) {
        mShowCancel = showCancel
    }

    interface ConfirmListener {
        fun onConfirm()
    }

    interface CancelListener {
        fun onCancel()
    }

    companion object {
        const val TAG: String = "AlertDialogFragment"
    }
}
