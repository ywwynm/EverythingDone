@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.content.DialogInterface
import android.os.Bundle
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.AuthenticationHelper
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.views.PatternLockView

/**
 * Created by ywwynm on 2016/5/23.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A dialog fragment provides pattern lock/unlock user interface including set and validate
 * pattern.
 */
open class PatternLockDialogFragment : BaseDialogFragment() {

    private var mType: Int = 0

    private var mCorrectPassword: String? = null
    private var mPassword: String? = null

    private var mAccentColor: Int = 0
    /** Phase 8: full ThingBackground for gradient title / right-button text. */
    private var mAccentBackground: ThingBackground? = null
    private var mValidateTitle: String? = null

    private var mTvTitle: TextView? = null
    private var mTvContent: TextView? = null
    private var mLockView: PatternLockView? = null
    private var mTvLeftAsBt: TextView? = null
    private var mTvRightAsBt: TextView? = null

    private var mPasswordSetDoneListener: View.OnClickListener? = null

    private var mAuthenticationCallback: AuthenticationHelper.AuthenticationCallback? = null

    private var mValidated: Boolean = false

    override fun getLayoutResource(): Int = R.layout.fragment_pattern_lock

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        findViews()
        initUI()
        setEvents()

        return mContentView
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (mType == TYPE_VALIDATE && !mValidated && mAuthenticationCallback != null) {
            mAuthenticationCallback!!.onCancel()
        }
        mPasswordSetDoneListener = null
        mAuthenticationCallback  = null
    }

    private fun findViews() {
        mTvTitle     = f(R.id.tv_title_pattern_lock)
        mTvContent   = f(R.id.tv_content_pattern_lock)
        mLockView    = f(R.id.pattern_lock_view)
        mTvLeftAsBt  = f(R.id.tv_left_as_bt_pattern)
        mTvRightAsBt = f(R.id.tv_right_as_bt_pattern)
    }

    private fun initUI() {
        if (mAccentBackground != null) {
            BackgroundUtil.applyTextBackground(mTvTitle, mAccentBackground)
            BackgroundUtil.applyTextBackground(mTvRightAsBt, mAccentBackground)
        } else {
            mTvTitle!!.setTextColor(mAccentColor)
            mTvRightAsBt!!.setTextColor(mAccentColor)
        }
        mLockView!!.setPathColor(ContextCompat.getColor(activity!!, R.color.black_54))
        // PatternLockView's correct-state stroke uses a single Paint — int only.
        mLockView!!.setCorrectColor(mAccentColor)

        if (mType == TYPE_SET) {
            initUiSet()
        } else if (mType == TYPE_VALIDATE) {
            initUiValidate()
        }
    }

    private fun initUiSet() {
        mTvTitle!!.setText(R.string.set_app_password)
        updateUiSetStep1()
    }

    private fun updateUiSetStep1() {
        mTvContent!!.setText(R.string.draw_pattern)
        mTvLeftAsBt!!.setText(R.string.cancel)
        mTvRightAsBt!!.setText(R.string.continue_for_alert)
    }

    private fun updateUiSetStep2() {
        mTvContent!!.setText(R.string.draw_pattern_again)
        mTvLeftAsBt!!.setText(R.string.last)
        mTvRightAsBt!!.setText(R.string.done)
    }

    private fun initUiValidate() {
        mTvTitle!!.text = mValidateTitle
        mTvContent!!.setText(R.string.confirm_pattern)
        f<View>(R.id.rl_pattern_lock_control).visibility = View.GONE
    }

    private fun setEvents() {
        if (mType == TYPE_SET) {
            setEventsSetStep1()
        } else if (mType == TYPE_VALIDATE) {
            setEventsValidate()
        }
    }

    private fun setEventsSetStep1() {
        mLockView!!.setOnPatternListener(object : PatternLockView.OnPatternListener() {

            override fun onPatternCellAdded(pattern: List<PatternLockView.Cell>, simplePattern: String) {
                mTvContent!!.setText(R.string.relax_finger_when_finish)
            }

            override fun onPatternDetected(pattern: List<PatternLockView.Cell>, simplePattern: String) {
                mLockView!!.setDisplayMode(PatternLockView.DisplayMode.Correct)
                mPassword = simplePattern
                mTvContent!!.setText(R.string.draw_pattern_finished)
            }
        })

        mTvLeftAsBt!!.setOnClickListener { dismiss() }
        mTvRightAsBt!!.setOnClickListener {
            if (mLockView!!.getSimplePattern().isEmpty()) {
                return@setOnClickListener
            }
            mLockView!!.clearPattern()
            updateUiSetStep2()
            setEventsSetStep2()
        }
    }

    private fun setEventsSetStep2() {
        mLockView!!.setOnPatternListener(object : PatternLockView.OnPatternListener() {
            override fun onPatternCellAdded(pattern: List<PatternLockView.Cell>, simplePattern: String) {
                mTvContent!!.setText(R.string.relax_finger_when_finish)
            }

            override fun onPatternDetected(pattern: List<PatternLockView.Cell>, simplePattern: String) {
                if (!mPassword.equals(simplePattern)) {
                    mTvContent!!.setText(R.string.pattern_not_same)
                    mLockView!!.setDisplayMode(PatternLockView.DisplayMode.Wrong)
                } else {
                    mTvContent!!.setText(R.string.draw_pattern_finished)
                    mLockView!!.setDisplayMode(PatternLockView.DisplayMode.Correct)
                }
            }
        })

        mTvLeftAsBt!!.setOnClickListener {
            mLockView!!.clearPattern()
            updateUiSetStep1()
            setEventsSetStep1()
        }
        mTvRightAsBt!!.setOnClickListener { v ->
            val password: String = mLockView!!.getSimplePattern()
            if (!mPassword.equals(password)) {
                return@setOnClickListener
            }

            dismiss()
            if (mPasswordSetDoneListener != null) {
                mPasswordSetDoneListener!!.onClick(v)
            }
        }
    }

    private fun setEventsValidate() {
        mLockView!!.setOnPatternListener(object : PatternLockView.OnPatternListener() {
            override fun onPatternCellAdded(pattern: List<PatternLockView.Cell>, simplePattern: String) {
                mTvContent!!.setText(R.string.confirm_pattern)
            }

            override fun onPatternDetected(pattern: List<PatternLockView.Cell>, simplePattern: String) {
                if (!mCorrectPassword.equals(simplePattern)) {
                    mTvContent!!.setText(R.string.wrong_pattern)
                    mLockView!!.setDisplayMode(PatternLockView.DisplayMode.Wrong)
                } else {
                    mValidated = true
                    dismiss()
                    if (mAuthenticationCallback != null) {
                        mAuthenticationCallback!!.onAuthenticated()
                    }
                }
            }
        })
    }

    open fun setType(type: Int) {
        mType = type
    }

    open fun setAccentColor(accentColor: Int) {
        mAccentColor = accentColor
        mAccentBackground = null
    }

    /** Phase 8: full ThingBackground accent. */
    open fun setAccentBackground(bg: ThingBackground?) {
        mAccentBackground = bg
        if (bg != null) mAccentColor = bg.representativeColor()
    }

    open fun setCorrectPassword(correctPassword: String?) {
        mCorrectPassword = correctPassword
    }

    open fun setValidateTitle(validateTitle: String?) {
        mValidateTitle = validateTitle
    }

    open fun setPasswordSetDoneListener(passwordSetDoneListener: View.OnClickListener?) {
        mPasswordSetDoneListener = passwordSetDoneListener
    }

    open fun setAuthenticationCallback(
        authenticationCallback: AuthenticationHelper.AuthenticationCallback?
    ) {
        mAuthenticationCallback = authenticationCallback
    }

    open fun getPassword(): String? = mPassword

    companion object {
        const val TAG: String = "PatternLockDialogFragment"

        const val TYPE_SET: Int      = 0
        const val TYPE_VALIDATE: Int = 1
    }
}
