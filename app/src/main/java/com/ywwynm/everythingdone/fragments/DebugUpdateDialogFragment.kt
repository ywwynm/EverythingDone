@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.DebugApkUpdateInfo
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.views.GradientRippleDrawable
import com.ywwynm.everythingdone.utils.EdgeEffectUtil

import kotlin.math.min

open class DebugUpdateDialogFragment : BaseDialogFragment() {

    private var mInfo: DebugApkUpdateInfo? = null
    private var mFormattedSize: String? = null
    private var mFormattedPublishedAt: String? = null
    private var mDownloadListener: OnDownloadClickListener? = null

    override fun getLayoutResource(): Int = R.layout.fragment_debug_update

    override fun getDialogWindowWidthPx(): Int {
        return (320 * resources.displayMetrics.density).toInt()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        val info = mInfo
        if (info == null) {
            dismiss()
            return mContentView
        }

        val title: TextView = f(R.id.tv_title_debug_update)!!
        val version: TextView = f(R.id.tv_debug_update_version)!!
        val build: TextView = f(R.id.tv_debug_update_build)!!
        val size: TextView = f(R.id.tv_debug_update_size)!!
        val published: TextView = f(R.id.tv_debug_update_published)!!
        val notesTitle: TextView = f(R.id.tv_debug_update_notes_title)!!
        val notes: TextView = f(R.id.tv_debug_update_notes)!!
        val cancel: TextView = f(R.id.tv_cancel_as_bt_debug_update)!!
        val download: TextView = f(R.id.tv_download_as_bt_debug_update)!!
        val scroll: ScrollView = f(R.id.sv_debug_update)!!
        val topSeparator: View = f(R.id.view_separator_1)!!
        val bottomSeparator: View = f(R.id.view_separator_2)!!

        val accentBackground = App.defaultAccentBackground
        val accentColor = accentBackground.representativeColor()
        BackgroundUtil.applyTextBackground(title, accentBackground)
        BackgroundUtil.applyTextBackground(download, accentBackground)
        GradientRippleDrawable.applyAccentRipple(download, accentBackground, accentColor)

        version.text = getString(R.string.debug_update_version_label, info.versionName ?: "")
        build.text = getString(R.string.debug_update_build_label, info.debugUpdateCode.toString())
        size.text = getString(R.string.debug_update_size_label, mFormattedSize ?: "")
        published.text = getString(
            R.string.debug_update_published_label, mFormattedPublishedAt ?: ""
        )

        val releaseNotes = info.releaseNotes?.trim()
        if (releaseNotes.isNullOrEmpty()) {
            notesTitle.visibility = View.GONE
            notes.visibility = View.GONE
        } else {
            notes.text = releaseNotes
        }

        cancel.setOnClickListener { dismiss() }
        download.setOnClickListener {
            mDownloadListener?.onDownloadClicked(info)
            dismiss()
        }

        limitScrollHeight(scroll, topSeparator, bottomSeparator, accentColor)

        return mContentView
    }

    override fun onDestroyView() {
        mDownloadListener = null
        super.onDestroyView()
    }

    private fun limitScrollHeight(
        scroll: ScrollView,
        topSeparator: View,
        bottomSeparator: View,
        accentColor: Int
    ) {
        scroll.post {
            val density = resources.displayMetrics.density
            val maxByReferenceDialog = (360 * density).toInt()
            val maxByScreen = (resources.displayMetrics.heightPixels * 0.48f).toInt()
            val maxHeight = min(maxByReferenceDialog, maxByScreen)
            if (scroll.height > maxHeight) {
                val lp = scroll.layoutParams
                lp.height = maxHeight
                scroll.layoutParams = lp
                scroll.post { installScrollSeparators(scroll, topSeparator, bottomSeparator, accentColor) }
            } else {
                installScrollSeparators(scroll, topSeparator, bottomSeparator, accentColor)
            }
        }
    }

    private fun installScrollSeparators(
        scroll: ScrollView,
        topSeparator: View,
        bottomSeparator: View,
        accentColor: Int
    ) {
        EdgeEffectUtil.forScrollView(scroll, accentColor)
        val canScroll = scroll.canScrollVertically(-1) || scroll.canScrollVertically(1)
        setScrollTopMargin(scroll, if (canScroll) 0 else 12)
        if (!canScroll) {
            topSeparator.visibility = View.GONE
            bottomSeparator.visibility = View.GONE
            return
        }
        scroll.viewTreeObserver.addOnScrollChangedListener {
            updateScrollSeparators(scroll, topSeparator, bottomSeparator)
        }
        updateScrollSeparators(scroll, topSeparator, bottomSeparator)
    }

    private fun setScrollTopMargin(scroll: ScrollView, marginDp: Int) {
        val lp = scroll.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val topMargin = (marginDp * resources.displayMetrics.density).toInt()
        if (lp.topMargin == topMargin) return
        lp.topMargin = topMargin
        scroll.layoutParams = lp
    }

    private fun updateScrollSeparators(
        scroll: ScrollView,
        topSeparator: View,
        bottomSeparator: View
    ) {
        if (!scroll.canScrollVertically(-1)) {
            topSeparator.visibility = View.INVISIBLE
            bottomSeparator.visibility = View.VISIBLE
        } else if (!scroll.canScrollVertically(1)) {
            topSeparator.visibility = View.VISIBLE
            bottomSeparator.visibility = View.INVISIBLE
        } else {
            topSeparator.visibility = View.VISIBLE
            bottomSeparator.visibility = View.VISIBLE
        }
    }

    open fun setUpdateInfo(
        info: DebugApkUpdateInfo,
        formattedSize: String,
        formattedPublishedAt: String
    ) {
        mInfo = info
        mFormattedSize = formattedSize
        mFormattedPublishedAt = formattedPublishedAt
    }

    open fun setOnDownloadClickListener(listener: OnDownloadClickListener?) {
        mDownloadListener = listener
    }

    interface OnDownloadClickListener {
        fun onDownloadClicked(info: DebugApkUpdateInfo)
    }

    companion object {
        const val TAG: String = "DebugUpdateDialogFragment"
    }
}
