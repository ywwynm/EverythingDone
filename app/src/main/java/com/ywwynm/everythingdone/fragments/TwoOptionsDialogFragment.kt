@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.content.DialogInterface
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import com.ywwynm.everythingdone.utils.AppearanceUtil
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil
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
    private var mAccentBackground: ThingBackground? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        val tvStart: TextView = f(R.id.tv_action_start)!!
        val tvEnd: TextView   = f(R.id.tv_action_end)!!

        if (mIconResStart != 0) {
            tvStart.setCompoundDrawablesWithIntrinsicBounds(0, mIconResStart, 0, 0)
        }
        tintActionIconForAppearance(tvStart)
        if (mActionResStart != 0) {
            tvStart.setText(mActionResStart)
        }
        tvStart.setOnClickListener(mListenerStart)

        if (mIconResEnd != 0) {
            tvEnd.setCompoundDrawablesWithIntrinsicBounds(0, mIconResEnd, 0, 0)
        }
        tintActionIconForAppearance(tvEnd)
        if (mActionResEnd != 0) {
            tvEnd.setText(mActionResEnd)
        }
        tvEnd.setOnClickListener(mListenerEnd)

        applyActionRipple(tvStart)
        applyActionRipple(tvEnd)

        return mContentView
    }

    /** item 触摸 ripple 用传入的强调背景（未传则 accent+accent2 渐变）。 */
    private fun applyActionRipple(view: TextView) {
        val bg = mAccentBackground ?: App.defaultAccentBackground
        view.background = GradientRippleDrawable(bg, shapeOval = false, cornerRadiusPx = 0f)
    }

    private fun tintActionIconForAppearance(view: TextView) {
        val accent = mAccentBackground
        if (accent != null) {
            BackgroundUtil.applyTextBackground(view, accent)
            val drawables = view.compoundDrawables
            var changed = false
            for (i in drawables.indices) {
                val drawable = drawables[i]
                if (drawable != null) {
                    // 源 PNG 本身带透明度，用 opaque 版（重映射 alpha）避免 SRC_IN 保留半透明发虚。
                    drawables[i] = BackgroundUtil.tintDrawableOpaque(resources, drawable, accent)
                    changed = true
                }
            }
            if (changed) {
                view.setCompoundDrawablesWithIntrinsicBounds(
                    drawables[0], drawables[1], drawables[2], drawables[3]
                )
            }
            return
        }

        if (!AppearanceUtil.isDarkMode(activity!!)) return

        val tint = ContextCompat.getColor(activity!!, R.color.app_chrome_control_unchecked)
        val drawables = view.compoundDrawables
        var changed = false
        for (i in drawables.indices) {
            val drawable = drawables[i]
            if (drawable != null) {
                drawables[i] = DisplayUtil.opaqueTintDrawable(activity!!, drawable, tint)
                changed = true
            }
        }
        if (changed) {
            view.setCompoundDrawablesWithIntrinsicBounds(
                drawables[0], drawables[1], drawables[2], drawables[3]
            )
        }
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

    open fun setAccentBackground(background: ThingBackground?) {
        mAccentBackground = background
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
