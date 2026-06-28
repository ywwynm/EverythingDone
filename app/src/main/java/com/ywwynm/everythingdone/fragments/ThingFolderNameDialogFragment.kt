@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import com.ywwynm.everythingdone.utils.DisplayUtil
import com.ywwynm.everythingdone.utils.KeyboardUtil

class ThingFolderNameDialogFragment : BaseDialogFragment() {

    private var mAccentBackground: ThingBackground? = null
    private var mAccentColor: Int = 0
    @StringRes
    private var mTitleRes: Int = R.string.create_thing_folder_title
    private var mInitialTitle: String? = null
    private var mListener: Listener? = null
    private var mConfirmed: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        if (mAccentColor == 0) {
            mAccentColor = ContextCompat.getColor(
                activity!!, R.color.app_chrome_on_surface_strong
            )
        }

        val title: TextView = f(R.id.tv_title_thing_folder_name)!!
        val input: EditText = f(R.id.et_thing_folder_name)!!
        val cancel: TextView = f(R.id.tv_cancel_as_bt_thing_folder_name)!!
        val confirm: TextView = f(R.id.tv_confirm_as_bt_thing_folder_name)!!

        title.setText(mTitleRes)
        applyAccentText(title)
        applyAccentText(confirm)
        GradientRippleDrawable.applyAccentRipple(confirm, mAccentBackground, mAccentColor)

        input.hint = getString(R.string.thing_folder_name_hint)
        input.setText(
            mInitialTitle
                ?.ifEmpty { getString(R.string.default_thing_folder_name) }
                ?: getString(R.string.default_thing_folder_name)
        )
        input.setSelection(0, input.text.length)
        input.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            applyInputFocusState(v as EditText, hasFocus)
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                confirm.performClick()
                true
            } else {
                false
            }
        }

        cancel.setOnClickListener { dismissWithKeyboardHidden(input) }
        confirm.setOnClickListener {
            val folderTitle = input.text.toString().trim()
                .ifEmpty { getString(R.string.default_thing_folder_name) }
            mConfirmed = true
            KeyboardUtil.hideKeyboard(dialog?.window, input)
            mListener?.onThingFolderNameConfirmed(folderTitle)
            dismiss()
        }

        return mContentView
    }

    override fun onStart() {
        super.onStart()
        val input: EditText? = f(R.id.et_thing_folder_name)
        input?.post {
            KeyboardUtil.showKeyboard(dialog?.window, input)
        }
    }

    override fun getLayoutResource(): Int = R.layout.fragment_thing_folder_name

    override fun getDialogWindowWidthPx(): Int {
        return (DisplayUtil.getScreenDensity(activity) * 320).toInt()
    }

    override fun onDismiss(dialog: DialogInterface) {
        KeyboardUtil.hideKeyboard(this.dialog?.window, currentInputView())
        if (!mConfirmed) {
            mListener?.onThingFolderNameCanceled()
        }
        mListener = null
        super.onDismiss(dialog)
    }

    fun setAccentBackground(background: ThingBackground?) {
        mAccentBackground = background
        mAccentColor = background?.color ?: 0
    }

    fun setInitialTitle(title: String?) {
        mInitialTitle = title
    }

    fun setTitleRes(@StringRes titleRes: Int) {
        mTitleRes = titleRes
    }

    fun setListener(listener: Listener?) {
        mListener = listener
    }

    private fun applyAccentText(textView: TextView) {
        val background = mAccentBackground
        if (background != null) {
            BackgroundUtil.applyTextBackground(textView, background)
        } else {
            textView.setTextColor(mAccentColor)
        }
    }

    private fun applyInputFocusState(input: EditText, hasFocus: Boolean) {
        if (hasFocus) {
            val background = mAccentBackground
            val useGradientLine = background != null
                && background.mode === ThingBackground.Mode.GRADIENT
            if (useGradientLine) {
                BackgroundUtil.applyTextBackground(input, background)
                BackgroundUtil.applyEditTextUnderline(input, background)
                DisplayUtil.tintView(input, Color.TRANSPARENT)
            } else {
                clearTextShader(input)
                input.setTextColor(mAccentColor)
                BackgroundUtil.clearEditTextUnderline(input)
                DisplayUtil.tintView(input, mAccentColor)
            }
            input.highlightColor = DisplayUtil.getLightColor(mAccentColor, activity)
        } else {
            clearTextShader(input)
            input.setTextColor(
                ContextCompat.getColor(activity!!, R.color.app_chrome_on_surface_strong)
            )
            BackgroundUtil.clearEditTextUnderline(input)
            DisplayUtil.tintView(
                input,
                ContextCompat.getColor(activity!!, R.color.app_chrome_on_surface_hint)
            )
        }
    }

    private fun clearTextShader(textView: TextView) {
        if (textView.paint.shader != null) {
            textView.paint.setShader(null)
            textView.invalidate()
        }
    }

    private fun dismissWithKeyboardHidden(input: EditText?) {
        KeyboardUtil.hideKeyboard(dialog?.window, input)
        dismiss()
    }

    private fun currentInputView(): View? {
        return mContentView?.findViewById(R.id.et_thing_folder_name) ?: mContentView
    }

    interface Listener {
        fun onThingFolderNameConfirmed(title: String)
        fun onThingFolderNameCanceled()
    }

    companion object {
        const val TAG: String = "ThingFolderNameDialogFragment"
    }
}
