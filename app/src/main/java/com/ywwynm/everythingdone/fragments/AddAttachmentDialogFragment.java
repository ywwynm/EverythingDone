package com.ywwynm.everythingdone.fragments;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.core.content.FileProvider;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.permission.SimplePermissionCallback;

import java.io.File;

/**
 * Created by ywwynm on 2015/9/25.
 * DialogFragment used to choose what kind of attachments to add.
 */
public class AddAttachmentDialogFragment extends BaseDialogFragment {

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
        super.onCreateView(inflater, container, savedInstanceState);

        mActivity = (DetailActivity) getActivity();

        // Phase 8: feed the full ThingBackground so a GRADIENT thing's title
        // renders as gradient text rather than collapsing to its representative
        // int colour. Falls back to int when no background was supplied (e.g.
        // legacy callers).
        TextView tvTitle = f(R.id.tv_add_attachment_title);
        com.ywwynm.everythingdone.model.ThingBackground bg = mActivity.getAccentBackground();
        if (bg != null) {
            com.ywwynm.everythingdone.utils.BackgroundUtil.applyTextBackground(tvTitle, bg);
        } else {
            tvTitle.setTextColor(mActivity.getAccentColor());
        }

        mTvTakePhotoAsBt        = f(R.id.tv_take_photo_as_bt);
        mTvShootVideoAsBt       = f(R.id.tv_shoot_video_as_bt);
        mTvRecordAudioAsBt      = f(R.id.tv_record_audio_as_bt);
        mTvChooseMediaFilesAsBt = f(R.id.tv_choose_media_files_as_bt);

        setEvents();

        return mContentView;
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.fragment_add_attachment;
    }

    private void setEvents() {
        // Camera capture writes to MediaStore (API 24+) or our own FileProvider, neither of
        // which requires READ_MEDIA_* on Android 13+. SAF picking via ACTION_OPEN_DOCUMENT
        // also needs no read permission. Only audio recording still needs RECORD_AUDIO.
        mTvTakePhotoAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (intent.resolveActivity(mActivity.getPackageManager()) == null) {
                    mActivity.showNormalSnackbar(R.string.error_activity_not_found);
                    dismiss();
                    return;
                }
                startTakePhoto();
            }
        });

        mTvShootVideoAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
                if (intent.resolveActivity(mActivity.getPackageManager()) == null) {
                    mActivity.showNormalSnackbar(R.string.error_activity_not_found);
                    dismiss();
                    return;
                }
                startShootVideo();
            }
        });

        mTvRecordAudioAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mActivity.doWithPermissionChecked(
                        new SimplePermissionCallback(mActivity) {
                            @Override
                            public void onGranted() {
                                showRecordAudioDialog();
                            }
                        },
                        Def.Communication.REQUEST_PERMISSION_RECORD_AUDIO,
                        Manifest.permission.RECORD_AUDIO);
            }
        });

        mTvChooseMediaFilesAsBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startChooseMediaFile();
            }
        });
    }

    public void startTakePhoto() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        File file = AttachmentHelper.createAttachmentFile(AttachmentHelper.IMAGE);
        if (file != null) {
            mActivity.attachmentTypePathName = AttachmentHelper.IMAGE + file.getAbsolutePath();
            mActivity.cameraOutputUri = null;
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, file.getName());
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EverythingDone");
            Uri imageUri = mActivity.getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
            mActivity.cameraOutputUri = imageUri;
            mActivity.startActivityForResult(intent,
                    Def.Communication.REQUEST_TAKE_PHOTO);
        }
        dismiss();
    }

    public void startShootVideo() {
        Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        File file = AttachmentHelper.createAttachmentFile(AttachmentHelper.VIDEO);
        if (file != null) {
            mActivity.attachmentTypePathName = AttachmentHelper.VIDEO + file.getAbsolutePath();
            mActivity.cameraOutputUri = null;
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.Video.Media.DISPLAY_NAME, file.getName());
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/EverythingDone");
            Uri videoUri = mActivity.getContentResolver()
                    .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, videoUri);
            mActivity.cameraOutputUri = videoUri;
            mActivity.startActivityForResult(intent,
                    Def.Communication.REQUEST_CAPTURE_VIDEO);
        }
        dismiss();
    }

    public void showRecordAudioDialog() {
        AudioRecordDialogFragment audioRecordDialogFragment = new AudioRecordDialogFragment();
        audioRecordDialogFragment.show(
                mActivity.getFragmentManager(), AudioRecordDialogFragment.TAG);
        dismiss();
    }

    public void startChooseMediaFile() {
        // Use ACTION_GET_CONTENT + an explicit chooser so the user can pick
        // their preferred gallery app — vendor galleries (OPPO 相册, MIUI 相册,
        // Samsung Gallery, ...) expose "Favorites" / "我喜欢" folders that the
        // system Photo Picker / SAF DocumentsUI does not. ACTION_GET_CONTENT
        // returns a content:// URI without any storage/media permission, and
        // DetailActivity.onActivityResult already copies the bytes into app-
        // private storage via FileUtil.copyUriToFile.
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[] { "image/*", "video/*", "audio/*" });
        mActivity.startActivityForResult(
                Intent.createChooser(intent,
                        mActivity.getString(R.string.act_choose_media_files)),
                Def.Communication.REQUEST_CHOOSE_MEDIA_FILE);
        dismiss();
    }
}
