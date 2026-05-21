@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.fragments

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.DetailActivity
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.permission.SimplePermissionCallback
import com.ywwynm.everythingdone.utils.BackgroundUtil

import java.io.File

/**
 * Created by ywwynm on 2015/9/25.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * DialogFragment used to choose what kind of attachments to add.
 */
open class AddAttachmentDialogFragment : BaseDialogFragment() {

    private var mActivity: DetailActivity? = null

    private var mTvTakePhotoAsBt: TextView? = null
    private var mTvShootVideoAsBt: TextView? = null
    private var mTvRecordAudioAsBt: TextView? = null
    private var mTvChooseMediaFilesAsBt: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        mActivity = activity as DetailActivity

        val tvTitle: TextView = f(R.id.tv_add_attachment_title)!!
        val bg: ThingBackground? = mActivity!!.getAccentBackground()
        if (bg != null) {
            BackgroundUtil.applyTextBackground(tvTitle, bg)
        } else {
            tvTitle.setTextColor(mActivity!!.getAccentColor())
        }

        mTvTakePhotoAsBt        = f(R.id.tv_take_photo_as_bt)
        mTvShootVideoAsBt       = f(R.id.tv_shoot_video_as_bt)
        mTvRecordAudioAsBt      = f(R.id.tv_record_audio_as_bt)
        mTvChooseMediaFilesAsBt = f(R.id.tv_choose_media_files_as_bt)

        setEvents()

        return mContentView
    }

    override fun getLayoutResource(): Int = R.layout.fragment_add_attachment

    private fun setEvents() {
        mTvTakePhotoAsBt!!.setOnClickListener {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            if (intent.resolveActivity(mActivity!!.packageManager) == null) {
                mActivity!!.showNormalSnackbar(R.string.error_activity_not_found)
                dismiss()
                return@setOnClickListener
            }
            startTakePhoto()
        }

        mTvShootVideoAsBt!!.setOnClickListener {
            val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            if (intent.resolveActivity(mActivity!!.packageManager) == null) {
                mActivity!!.showNormalSnackbar(R.string.error_activity_not_found)
                dismiss()
                return@setOnClickListener
            }
            startShootVideo()
        }

        mTvRecordAudioAsBt!!.setOnClickListener {
            mActivity!!.doWithPermissionChecked(
                object : SimplePermissionCallback(mActivity) {
                    override fun onGranted() {
                        showRecordAudioDialog()
                    }
                },
                Def.Communication.REQUEST_PERMISSION_RECORD_AUDIO,
                Manifest.permission.RECORD_AUDIO
            )
        }

        mTvChooseMediaFilesAsBt!!.setOnClickListener {
            startChooseMediaFile()
        }
    }

    open fun startTakePhoto() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val file: File? = AttachmentHelper.createAttachmentFile(AttachmentHelper.IMAGE)
        if (file != null) {
            mActivity!!.attachmentTypePathName = AttachmentHelper.IMAGE.toString() + file.absolutePath
            mActivity!!.cameraOutputUri = null
            val contentValues = ContentValues()
            contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EverythingDone")
            val imageUri: Uri? = mActivity!!.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
            mActivity!!.cameraOutputUri = imageUri
            mActivity!!.startActivityForResult(intent,
                Def.Communication.REQUEST_TAKE_PHOTO)
        }
        dismiss()
    }

    open fun startShootVideo() {
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        val file: File? = AttachmentHelper.createAttachmentFile(AttachmentHelper.VIDEO)
        if (file != null) {
            mActivity!!.attachmentTypePathName = AttachmentHelper.VIDEO.toString() + file.absolutePath
            mActivity!!.cameraOutputUri = null
            val contentValues = ContentValues()
            contentValues.put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/EverythingDone")
            val videoUri: Uri? = mActivity!!.contentResolver
                .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, videoUri)
            mActivity!!.cameraOutputUri = videoUri
            mActivity!!.startActivityForResult(intent,
                Def.Communication.REQUEST_CAPTURE_VIDEO)
        }
        dismiss()
    }

    open fun showRecordAudioDialog() {
        val audioRecordDialogFragment = AudioRecordDialogFragment()
        audioRecordDialogFragment.show(
            mActivity!!.fragmentManager, AudioRecordDialogFragment.TAG
        )
        dismiss()
    }

    open fun startChooseMediaFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
            arrayOf("image/*", "video/*", "audio/*"))
        mActivity!!.startActivityForResult(
            Intent.createChooser(intent,
                mActivity!!.getString(R.string.act_choose_media_files)),
            Def.Communication.REQUEST_CHOOSE_MEDIA_FILE
        )
        dismiss()
    }

    companion object {
        const val TAG: String = "AddAttachmentDialogFragment"

        @JvmStatic
        fun newInstance(): AddAttachmentDialogFragment {
            val args = Bundle()
            val fragment = AddAttachmentDialogFragment()
            fragment.arguments = args
            return fragment
        }
    }
}
