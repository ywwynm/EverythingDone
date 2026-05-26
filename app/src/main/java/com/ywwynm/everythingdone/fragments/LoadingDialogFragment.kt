@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.graphics.PorterDuff
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat

import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil

/**
 * Created by ywwynm on 2016/3/21.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * dialog for loading
 */
open class LoadingDialogFragment : BaseDialogFragment() {

    private var mAccentColor: Int = 0
    /** Phase 8: full ThingBackground for gradient title text. */
    private var mAccentBackground: ThingBackground? = null

    private var mTitle: String? = null
    private var mContent: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        val tvTitle: TextView      = f(R.id.tv_title_loading)!!
        val tvContent: TextView    = f(R.id.tv_content_loading)!!
        val pbLoading: ProgressBar = f(R.id.pb_loading_fragment)!!

        if (mAccentColor == 0 && mAccentBackground == null) {
            mAccentColor = ContextCompat.getColor(
                activity!!, R.color.app_chrome_on_surface_strong
            )
        }

        if (mTitle != null) {
            tvTitle.text = mTitle
        }
        if (mAccentBackground != null) {
            BackgroundUtil.applyTextBackground(tvTitle, mAccentBackground)
        } else {
            tvTitle.setTextColor(mAccentColor)
        }

        if (mContent != null) {
            tvContent.text = mContent
        }

        // PorterDuff tint is single-channel — collapse to representative int
        // even when GRADIENT is supplied.
        pbLoading.indeterminateDrawable.setColorFilter(mAccentColor, PorterDuff.Mode.SRC_IN)

        isCancelable = false
        dialog!!.setCanceledOnTouchOutside(false)

        return mContentView
    }

    override fun getLayoutResource(): Int = R.layout.fragment_loading

    open fun setAccentColor(accentColor: Int) {
        mAccentColor = accentColor
        mAccentBackground = null
    }

    /** Phase 8: gradient-aware accent for the title. */
    open fun setAccentBackground(bg: ThingBackground?) {
        mAccentBackground = bg
        if (bg != null) mAccentColor = bg.representativeColor()
    }

    open fun setTitle(title: String?) {
        mTitle = title
    }

    open fun setContent(content: String?) {
        mContent = content
    }

    companion object {
        const val TAG: String = "LoadingDialogFragment"
    }
}
