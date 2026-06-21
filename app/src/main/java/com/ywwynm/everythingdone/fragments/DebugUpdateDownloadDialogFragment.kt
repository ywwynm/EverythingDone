@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil

open class DebugUpdateDownloadDialogFragment : BaseDialogFragment() {

    private var mCancelListener: OnCancelDownloadListener? = null
    private var mProgress: ProgressBar? = null
    private var mTvProgress: TextView? = null
    private var mTvSpeed: TextView? = null
    private var mDownloaded: Long = 0L
    private var mTotal: Long = 0L
    private var mFormattedDownloaded: String = ""
    private var mFormattedTotal: String = ""
    private var mFormattedSpeed: String = ""
    private var mAccentBackground: ThingBackground? = null

    override fun getLayoutResource(): Int = R.layout.fragment_debug_update_download

    override fun getDialogWindowWidthPx(): Int {
        return (320 * resources.displayMetrics.density).toInt()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        val title: TextView = f(R.id.tv_title_debug_update_download)!!
        val cancel: TextView = f(R.id.tv_cancel_as_bt_debug_update_download)!!
        mProgress = f(R.id.pb_debug_update_download)
        mTvProgress = f(R.id.tv_debug_update_download_progress)
        mTvSpeed = f(R.id.tv_debug_update_download_speed)

        val accentBackground = mAccentBackground ?: App.defaultAccentBackground
        BackgroundUtil.applyTextBackground(title, accentBackground)
        BackgroundUtil.applyProgressBarGradient(mProgress!!, accentBackground)
        cancel.setOnClickListener { mCancelListener?.onCancelDownload() }

        isCancelable = false
        dialog!!.setCanceledOnTouchOutside(false)
        renderProgress()

        return mContentView
    }

    override fun onDestroyView() {
        mCancelListener = null
        mProgress = null
        mTvProgress = null
        mTvSpeed = null
        super.onDestroyView()
    }

    open fun updateProgress(
        downloaded: Long,
        total: Long,
        formattedDownloaded: String,
        formattedTotal: String,
        formattedSpeed: String
    ) {
        mDownloaded = downloaded
        mTotal = total
        mFormattedDownloaded = formattedDownloaded
        mFormattedTotal = formattedTotal
        mFormattedSpeed = formattedSpeed
        renderProgress()
    }

    private fun renderProgress() {
        val total = mTotal
        val downloaded = mDownloaded
        val percent = if (total > 0) ((downloaded * 100L) / total).toInt() else 0
        val progress = if (total > 0) ((downloaded * 10000L) / total).toInt() else 0
        mProgress?.progress = progress.coerceIn(0, 10000)
        mTvProgress?.text = getString(
            R.string.debug_update_download_progress,
            mFormattedDownloaded,
            mFormattedTotal,
            percent.coerceIn(0, 100)
        )
        mTvSpeed?.text = getString(R.string.debug_update_download_speed, mFormattedSpeed)
    }

    open fun setOnCancelDownloadListener(listener: OnCancelDownloadListener?) {
        mCancelListener = listener
    }

    open fun setAccentBackground(background: ThingBackground?) {
        mAccentBackground = background
    }

    interface OnCancelDownloadListener {
        fun onCancelDownload()
    }

    companion object {
        const val TAG: String = "DebugUpdateDownloadDialogFragment"
    }
}
