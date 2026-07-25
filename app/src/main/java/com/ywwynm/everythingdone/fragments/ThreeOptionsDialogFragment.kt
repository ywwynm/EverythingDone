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

/**
 * A three-cell action picker, mirroring [TwoOptionsDialogFragment] but with an
 * optional middle / end action. Any cell whose action string is not set is
 * hidden, so the same dialog serves the two-action and three-action cases (used
 * by the Drawer Header Image entry: 默认 / 选择图片 / 调整裁切).
 */
open class ThreeOptionsDialogFragment : BaseDialogFragment() {

    private var mIconResStart: Int = 0
    private var mIconResMiddle: Int = 0
    private var mIconResEnd: Int = 0
    private var mActionResStart: Int = 0
    private var mActionResMiddle: Int = 0
    private var mActionResEnd: Int = 0
    private var mListenerStart: View.OnClickListener? = null
    private var mListenerMiddle: View.OnClickListener? = null
    private var mListenerEnd: View.OnClickListener? = null
    private var mAccentBackground: ThingBackground? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        bindCell(f(R.id.tv_action_start), mIconResStart, mActionResStart, mListenerStart)
        bindCell(f(R.id.tv_action_middle), mIconResMiddle, mActionResMiddle, mListenerMiddle)
        bindCell(f(R.id.tv_action_end), mIconResEnd, mActionResEnd, mListenerEnd)

        return mContentView
    }

    private fun bindCell(
        tv: TextView,
        @DrawableRes iconRes: Int,
        @StringRes actionRes: Int,
        listener: View.OnClickListener?
    ) {
        if (actionRes == 0) {
            tv.visibility = View.GONE
            return
        }
        if (iconRes != 0) {
            tv.setCompoundDrawablesWithIntrinsicBounds(0, iconRes, 0, 0)
        }
        tintActionIconForAppearance(tv)
        tv.setText(actionRes)
        tv.setOnClickListener(listener)
        applyActionRipple(tv)
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
                    drawables[i] = BackgroundUtil.tintDrawable(resources, drawable, accent)
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

    override fun getLayoutResource(): Int = R.layout.fragment_three_action_picker

    open fun setStartAction(
        @DrawableRes iconRes: Int, @StringRes actionRes: Int,
        listener: View.OnClickListener?
    ) {
        mIconResStart = iconRes
        mActionResStart = actionRes
        mListenerStart = listener
    }

    open fun setMiddleAction(
        @DrawableRes iconRes: Int, @StringRes actionRes: Int,
        listener: View.OnClickListener?
    ) {
        mIconResMiddle = iconRes
        mActionResMiddle = actionRes
        mListenerMiddle = listener
    }

    open fun setEndAction(
        @DrawableRes iconRes: Int, @StringRes actionRes: Int,
        listener: View.OnClickListener?
    ) {
        mIconResEnd = iconRes
        mActionResEnd = actionRes
        mListenerEnd = listener
    }

    open fun setAccentBackground(background: ThingBackground?) {
        mAccentBackground = background
    }

    override fun onDismiss(dialog: DialogInterface) {
        mListenerStart = null
        mListenerMiddle = null
        mListenerEnd = null
        super.onDismiss(dialog)
    }

    companion object {
        const val TAG: String = "ThreeOptionsDialogFragment"
    }
}
