package com.ywwynm.everythingdone.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.ywwynm.everythingdone.Definitions;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;

import java.io.File;

/**
 * Created by ywwynm on 2015/9/25.
 * DialogFragment used to choose what kind of attachments to add.
 */
public class AddAttachmentDialogFragment extends NoTitleDialogFragment {

    public static final String TAG = "AddAttachmentDialogFragment";

    private DetailActivity mActivity;

    private TextView mTvTakePhotoAsBt;
    private TextView mTvShootVideoAsBt;
    private TextView mTvRecordAudioAsBt;
    private TextView mTvChooseMediaFilesAsBt;

    public static AddAttachmentDialogFragment newInstance() {
        Bundle args = new Bundle();
        AddAttachmentDialogFragment fragment = new AddAttachmentDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mActivity = (DetailActivity) getActivity();

        View contentView = inflater.inflate(R.layout.fragment_add_attachment, container);

        ((TextView) contentView.findViewById(R.id.tv_add_attachment_title))
                .setTextColor(mActivity.getAccentColor());

        mTvTakePhotoAsBt        = (TextView) contentView.findViewById(R.id.tv_take_photo_as_bt);
        mTvShootVideoAsBt       = (TextView) contentView.findViewById(R.id.tv_shoot_video_as_bt);
        mTvRecordAudioAsBt      = (TextView) contentView.findViewById(R.id.tv_record_audio_as_bt);
        mTvChooseMediaFilesAsBt = (TextView) contentView.findViewById(R.id.tv_choose_media_files_as_bt);

        setEvents();

        return contentView;
    }

    private void setEvents() {
        mTvTakePhotoAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (intent.resolveActivity(mActivity.getPackageManager()) != null) {
                    File file = AttachmentHelper.createAttachmentFile(AttachmentHelper.IMAGE);
                    if (file != null) {
                        mActivity.attachmentTypePathName = AttachmentHelper.IMAGE + file.getAbsolutePath();
                        intent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(file));
                        mActivity.startActivityForResult(intent,
                                Definitions.Communication.REQUEST_TAKE_PHOTO);
                    }
                    dismiss();
                }
            }
        });

        mTvShootVideoAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
                if (intent.resolveActivity(mActivity.getPackageManager()) != null) {
                    File file = AttachmentHelper.createAttachmentFile(AttachmentHelper.VIDEO);
                    if (file != null) {
                        mActivity.attachmentTypePathName = AttachmentHelper.VIDEO + file.getAbsolutePath();
                        intent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(file));
                        mActivity.startActivityForResult(intent,
                                Definitions.Communication.REQUEST_CAPTURE_VIDEO);
                    }
                    dismiss();
                }
            }
        });

        mTvRecordAudioAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                AudioRecordDialogFragment audioRecordDialogFragment = new AudioRecordDialogFragment();
                audioRecordDialogFragment.show(
                        mActivity.getFragmentManager(), AudioRecordDialogFragment.TAG);
            }
        });

        mTvChooseMediaFilesAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                mActivity.startActivityForResult(
                        Intent.createChooser(intent, mActivity.getString(R.string.act_choose_media_files)),
                        Definitions.Communication.REQUEST_CHOOSE_MEDIA_FILE);
                dismiss();
            }
        });
    }
}
