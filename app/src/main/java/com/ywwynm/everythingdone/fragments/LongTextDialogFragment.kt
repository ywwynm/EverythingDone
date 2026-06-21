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
import android.widget.ScrollView
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.EdgeEffectUtil

/**
 * Created by ywwynm on 2016/4/23.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * long text dialog fragment
 */
open class LongTextDialogFragment : BaseDialogFragment() {

    private var mAccentColor: Int = 0
    /** Phase 8: full ThingBackground for gradient title / confirm. */
    private var mAccentBackground: ThingBackground? = null

    private var mTitle: String? = null
    private var mContent: String? = null
    private var mConfirmText: String? = null

    private var mShowCancel: Boolean = false

    private var mConfirmListener: View.OnClickListener? = null
    private var mCancelListener: View.OnClickListener? = null

    private var mConfirmed: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        if (mAccentColor == 0 && mAccentBackground == null) {
            mAccentColor = ContextCompat.getColor(
                activity!!, R.color.app_chrome_on_surface_strong
            )
        }

        val tvTitle: TextView       = f(R.id.tv_title_long_text)!!
        val tvContent: TextView     = f(R.id.tv_content_long_text)!!
        val tvCancelAsBt: TextView  = f(R.id.tv_cancel_as_bt_long_text)!!
        val tvConfirmAsBt: TextView = f(R.id.tv_confirm_as_bt_long_text)!!

        if (mTitle != null) {
            tvTitle.text = mTitle
            if (mAccentBackground != null) {
                BackgroundUtil.applyTextBackground(tvTitle, mAccentBackground)
            } else {
                tvTitle.setTextColor(mAccentColor)
            }
        } else {
            tvTitle.visibility = View.GONE
        }

        if (mContent != null) {
            tvContent.text = mContent
        } else {
            tvContent.visibility = View.GONE
        }

        if (mShowCancel) {
            tvCancelAsBt.visibility = View.VISIBLE
            tvCancelAsBt.setOnClickListener { dismiss() }
        }

        if (mConfirmText != null) {
            tvConfirmAsBt.text = mConfirmText
        }
        if (mAccentBackground != null) {
            BackgroundUtil.applyTextBackground(tvConfirmAsBt, mAccentBackground)
        } else {
            tvConfirmAsBt.setTextColor(mAccentColor)
        }
        tvConfirmAsBt.setOnClickListener { v ->
            if (mConfirmListener != null) {
                mConfirmListener!!.onClick(v)
            }
            mConfirmed = true
            dismiss()
        }

        val v1: View = f(R.id.view_separator_1)!!
        val v2: View = f(R.id.view_separator_2)!!
        val sv: ScrollView = f(R.id.sv_long_text)!!
        sv.viewTreeObserver.addOnScrollChangedListener {
            val scrollY = sv.scrollY
            if (scrollY <= 0) {
                v1.visibility = View.INVISIBLE
                v2.visibility = View.VISIBLE
            } else if (scrollY >= sv.getChildAt(0).height - sv.height) {
                v1.visibility = View.VISIBLE
                v2.visibility = View.INVISIBLE
            } else {
                v1.visibility = View.VISIBLE
                v2.visibility = View.VISIBLE
            }
        }
        EdgeEffectUtil.forScrollView(sv, mAccentColor)

        return mContentView
    }

    override fun getLayoutResource(): Int = R.layout.fragment_long_text

    override fun onResume() {
        super.onResume()
        val screenDensity: Float = DisplayUtil.getScreenDensity(activity)
        val window: Window? = dialog!!.window
        window?.setLayout((screenDensity * 320).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (!mConfirmed && mCancelListener != null) {
            mCancelListener!!.onClick(f(R.id.tv_cancel_as_bt_long_text))
        }
        mConfirmListener = null
        mCancelListener  = null
        super.onDismiss(dialog)
    }

    open fun setAccentColor(accentColor: Int) {
        mAccentColor = accentColor
        mAccentBackground = null
    }

    /** Phase 8: gradient-aware accent. */
    open fun setAccentBackground(bg: ThingBackground?) {
        mAccentBackground = bg
        if (bg != null) mAccentColor = bg.color
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

    open fun setShowCancel(showCancel: Boolean) {
        mShowCancel = showCancel
    }

    open fun setConfirmListener(confirmListener: View.OnClickListener?) {
        mConfirmListener = confirmListener
    }

    open fun setCancelListener(cancelListener: View.OnClickListener?) {
        mCancelListener = cancelListener
    }

    companion object {
        const val TAG: String = "LongTextDialogFragment"
    }
}
