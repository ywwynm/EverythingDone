@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.views

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import android.view.View
import android.widget.EditText
import android.widget.TextView

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil

/**
 * Created by ywwynm on 2015/8/22.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Contains a TextView as Floating Label and an EditText
 */
open class InputLayout(
        context: Context,
        textView: TextView,
        editText: EditText,
        accentColor: Int) {

    private val black_26p: Int

    private val mContext: Context = context
    private val mScreenDensity: Float = DisplayUtil.getScreenDensity(context)

    private val mTextView: TextView = textView
    private val mEditText: EditText = editText

    private var mAccentColor: Int = accentColor
    /** Phase 8: full accent so the floating label and focused EditText text
     *  can render gradient. When null, focused colours fall back to the plain
     *  int [mAccentColor]. Highlight tint, cursor / selection-handle
     *  tint and the EditText underline tint always stay int (PorterDuff API
     *  limit) — they consume [mAccentColor] via representative. */
    private var mAccentBackground: ThingBackground? = null
    private var mOnFocusChangeListener: View.OnFocusChangeListener? = null

    private var raised: Boolean = false

    init {
        black_26p = ContextCompat.getColor(mContext, R.color.black_26p)

        setColors(black_26p)

        mEditText.setSelectAllOnFocus(true)
        mEditText.onFocusChangeListener = View.OnFocusChangeListener { v: View?, hasFocus: Boolean ->
            if (hasFocus) {
                raiseLabel(true)
                setColors(mAccentColor)
                mTextView.setTypeface(Typeface.DEFAULT_BOLD)
            } else {
                if (mEditText.getText().toString().isEmpty()) {
                    this@InputLayout.fallLabel()
                }
                setColors(black_26p)
                mTextView.setTypeface(Typeface.DEFAULT)
            }
            mOnFocusChangeListener?.onFocusChange(v, hasFocus)
        }
        DisplayUtil.setSelectionHandlersColor(mEditText, accentColor)
    }

    /** Phase 8: upgrade the accent signal to a full [ThingBackground].
     *  Refreshes selection handler tint to the new representative colour and,
     *  if the EditText is already focused, repaints the label/text right away
     *  so the gradient shader picks up the new stops. */
    fun setAccentBackground(bg: ThingBackground?) {
        mAccentBackground = bg
        if (bg != null) {
            mAccentColor = bg.representativeColor()
            DisplayUtil.setSelectionHandlersColor(mEditText, mAccentColor)
            if (mEditText.hasFocus()) {
                setColors(mAccentColor)
            }
        }
    }

    fun setOnFocusChangeListenerForEditText(listener: View.OnFocusChangeListener) {
        mOnFocusChangeListener = listener
    }

    fun getTextFromEditText(): String {
        return mEditText.getText().toString()
    }

    fun setTextForEditText(text: String) {
        mEditText.setText(text)
        raiseLabel(false)
    }

    fun getEditText(): EditText {
        return mEditText
    }

    fun raiseLabel(anim: Boolean) {
        if (raised) {
            return
        }
        mTextView.pivotX = 1f
        mTextView.pivotY = 1f

        if (anim) {
            mTextView.animate()!!.scaleX(0.75f).setDuration(96)
            mTextView.animate()!!.scaleY(0.75f).setDuration(96)
            mTextView.animate()!!.translationY(-mScreenDensity * 24).setDuration(96)
        } else {
            mTextView.scaleX = 0.75f
            mTextView.scaleY = 0.75f
            mTextView.translationY = -mScreenDensity * 24
        }
        raised = true
    }

    fun fallLabel() {
        if (!raised) {
            return
        }
        mTextView.pivotX = 1f
        mTextView.pivotY = 1f
        mTextView.animate()!!.scaleX(1.0f).setDuration(96)
        mTextView.animate()!!.scaleY(1.0f).setDuration(96)
        mTextView.animate()!!.translationY(0f).setDuration(96)
        raised = false
    }

    fun setColors(colorTo: Int) {
        val black_54p: Int = ContextCompat.getColor(mContext, R.color.black_54p)
        val useGradientLine: Boolean = colorTo != black_26p
                && mAccentBackground != null
                && mAccentBackground!!.mode == ThingBackground.Mode.GRADIENT
        if (colorTo == black_26p) {
            // Unfocused: plain int colours. Clear any leftover gradient shader
            // from a previous focused bind so the label/text revert to grey.
            clearShader(mTextView)
            clearShader(mEditText)
            mTextView.setTextColor(colorTo)
            mEditText.setTextColor(black_54p)
            mEditText.highlightColor = black_26p
            BackgroundUtil.clearEditTextUnderline(mEditText)
        } else {
            // Focused: label + EditText text adopt the accent. Use the gradient
            // shader when we have a ThingBackground; otherwise plain int.
            if (useGradientLine) {
                BackgroundUtil.applyTextBackground(mTextView, mAccentBackground)
                BackgroundUtil.applyTextBackground(mEditText, mAccentBackground)
                BackgroundUtil.applyEditTextUnderline(mEditText, mAccentBackground)
            } else {
                clearShader(mTextView)
                clearShader(mEditText)
                mTextView.setTextColor(colorTo)
                mEditText.setTextColor(colorTo)
                BackgroundUtil.clearEditTextUnderline(mEditText)
            }
            mEditText.highlightColor = DisplayUtil.getLightColor(colorTo, mContext)
        }
        // Native underline tint: transparent when the foreground gradient strip
        // is taking over, otherwise show the native single-int underline (grey
        // when blurred, accent when focused + PURE).
        DisplayUtil.tintView(mEditText, if (useGradientLine) android.graphics.Color.TRANSPARENT else colorTo)
    }

    companion object {
        const val TAG: String = "InputLayout"

        private fun clearShader(tv: TextView) {
            if (tv.paint.shader != null) {
                tv.paint.setShader(null)
                tv.invalidate()
            }
        }
    }
}
