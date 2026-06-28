@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.app.Activity
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
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import com.ywwynm.everythingdone.utils.DisplayUtil

/**
 * Created by ywwynm on 2016/2/5.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A subclass of DialogFragment to show alert information with three actions.
 */
open class ThreeActionsAlertDialogFragment : BaseDialogFragment() {

    private var mColors: IntArray = intArrayOf(0, 0, 0)
    /** Phase 8: optional ThingBackground for title / first / second buttons. */
    private var mTitleBg: ThingBackground? = null
    private var mContinueBg: ThingBackground? = null

    private var mTitle: String? = null
    private var mContent: String? = null
    private var mFirstAction: String? = null
    private var mSecondAction: String? = null

    interface OnClickListener {
        fun onFirstClicked()
        fun onSecondClicked()
        fun onThirdClicked()
    }
    private var mOnClickListener: OnClickListener? = null

    private var mContinued: Boolean = false

    override fun onResume() {
        super.onResume()
        val screenDensity: Float = DisplayUtil.getScreenDensity(activity)
        val window: Window? = dialog!!.window
        window?.setLayout((screenDensity * 320).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        val activity: Activity = activity!!
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

        val tvTitle: TextView      = f(R.id.tv_title_alert)!!
        val tvContent: TextView    = f(R.id.tv_content_alert)!!
        val tvFirstAsBt: TextView  = f(R.id.tv_first_as_bt_alert)!!
        val tvSecondAsBt: TextView = f(R.id.tv_second_as_bt_alert)!!
        val tvThirdAsBt: TextView  = f(R.id.tv_third_as_bt_alert)!!

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

        if (mFirstAction != null) {
            tvFirstAsBt.text = mFirstAction
            applyAccent(tvFirstAsBt, mContinueBg, mColors[2])
            GradientRippleDrawable.applyAccentRipple(tvFirstAsBt, mContinueBg, mColors[2])
            tvFirstAsBt.setOnClickListener {
                if (mOnClickListener != null) {
                    mOnClickListener!!.onFirstClicked()
                }
                mContinued = true
                dismiss()
            }
        } else {
            tvFirstAsBt.visibility = View.GONE
        }

        if (mSecondAction != null) {
            tvSecondAsBt.text = mSecondAction
            applyAccent(tvSecondAsBt, mContinueBg, mColors[2])
            GradientRippleDrawable.applyAccentRipple(tvSecondAsBt, mContinueBg, mColors[2])
            tvSecondAsBt.setOnClickListener {
                if (mOnClickListener != null) {
                    mOnClickListener!!.onSecondClicked()
                }
                mContinued = true
                dismiss()
            }
        } else {
            tvSecondAsBt.visibility = View.GONE
        }

        tvThirdAsBt.setOnClickListener {
            if (mOnClickListener != null) {
                mOnClickListener!!.onThirdClicked()
            }
            dismiss()
        }

        return mContentView
    }

    override fun getLayoutResource(): Int = R.layout.fragment_alert_three_actions

    override fun onDismiss(dialog: DialogInterface) {
        if (!mContinued && mOnClickListener != null) {
            mOnClickListener!!.onThirdClicked()
        }
        mOnClickListener = null
        super.onDismiss(dialog)
    }

    open fun setTitleColor(color: Int) {
        mColors[0] = color
        mTitleBg = null
    }

    open fun setContentColor(color: Int) {
        mColors[1] = color
    }

    open fun setContinueColor(color: Int) {
        mColors[2] = color
        mContinueBg = null
    }

    /** Phase 8: gradient-aware accent for the title. */
    open fun setTitleBackground(bg: ThingBackground?) {
        mTitleBg = bg
        if (bg != null) mColors[0] = bg.color
    }

    /** Phase 8: gradient-aware accent for the two "continue" buttons. */
    open fun setContinueBackground(bg: ThingBackground?) {
        mContinueBg = bg
        if (bg != null) mColors[2] = bg.color
    }

    private fun applyAccent(tv: TextView, bg: ThingBackground?, fallback: Int) {
        if (bg != null) {
            BackgroundUtil.applyTextBackground(tv, bg)
        } else {
            tv.setTextColor(fallback)
        }
    }

    open fun setTitle(title: String?) {
        mTitle = title
    }

    open fun setContent(content: String?) {
        mContent = content
    }

    open fun setFirstAction(first: String?) {
        mFirstAction = first
    }

    open fun setSecondAction(secondAction: String?) {
        mSecondAction = secondAction
    }

    open fun setOnClickListener(onClickListener: OnClickListener?) {
        mOnClickListener = onClickListener
    }

    companion object {
        const val TAG: String = "ThreeActionsAlertDialogFragment"
    }
}
