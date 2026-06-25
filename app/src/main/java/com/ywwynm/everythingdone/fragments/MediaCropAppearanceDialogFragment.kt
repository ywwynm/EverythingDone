@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

import com.ywwynm.everythingdone.R

open class MediaCropAppearanceDialogFragment : BaseDialogFragment() {

    interface Host {
        fun getMediaCropAppearanceDialogWidthPx(
            fragment: MediaCropAppearanceDialogFragment,
            requestKey: String,
            position: Int
        ): Int

        fun createMediaCropAppearanceDialogContent(
            fragment: MediaCropAppearanceDialogFragment,
            requestKey: String,
            position: Int
        ): Content?
    }

    data class Content(
        val view: View,
        val onDestroyView: () -> Unit = {}
    )

    private var mContentCleanup: (() -> Unit)? = null

    override fun getLayoutResource(): Int = R.layout.fragment_media_crop_appearance_dialog

    override fun getDialogWindowWidthPx(): Int {
        val host = activity as? Host ?: return ViewGroup.LayoutParams.WRAP_CONTENT
        return host.getMediaCropAppearanceDialogWidthPx(
            this,
            getRequestKey(),
            getPosition()
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        val host = activity as? Host
        val content = host?.createMediaCropAppearanceDialogContent(
            this,
            getRequestKey(),
            getPosition()
        )
        if (content == null) {
            dismiss()
            return mContentView
        }

        val contentContainer: FrameLayout = f(R.id.fl_media_crop_appearance_dialog_content)!!
        contentContainer.removeAllViews()
        contentContainer.addView(
            content.view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        mContentCleanup = content.onDestroyView
        return mContentView
    }

    override fun onDestroyView() {
        val cleanup = mContentCleanup
        mContentCleanup = null
        cleanup?.invoke()
        super.onDestroyView()
    }

    private fun getRequestKey(): String {
        return arguments?.getString(ARG_REQUEST_KEY).orEmpty()
    }

    private fun getPosition(): Int {
        return arguments?.getInt(ARG_POSITION, -1) ?: -1
    }

    companion object {
        const val TAG: String = "MediaCropAppearanceDialogFragment"
        const val REQUEST_DETAIL_ATTACHMENT: String = "detail_attachment"
        const val REQUEST_THING_CARD_CROP: String = "thing_card_crop"
        const val REQUEST_DRAWER_HEADER: String = "drawer_header"

        private const val ARG_REQUEST_KEY = "request_key"
        private const val ARG_POSITION = "position"

        fun newInstance(
            requestKey: String,
            position: Int = -1
        ): MediaCropAppearanceDialogFragment {
            val fragment = MediaCropAppearanceDialogFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putInt(ARG_POSITION, position)
            }
            return fragment
        }
    }
}
