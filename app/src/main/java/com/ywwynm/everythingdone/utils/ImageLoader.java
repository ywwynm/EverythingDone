package com.ywwynm.everythingdone.utils;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.AsyncTask;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.ywwynm.everythingdone.helpers.AttachmentHelper;

import java.lang.ref.WeakReference;

import uk.co.senab.photoview.PhotoViewAttacher;

import static com.ywwynm.everythingdone.helpers.AttachmentHelper.IMAGE;
import static com.ywwynm.everythingdone.helpers.AttachmentHelper.VIDEO;

/**
 * Created by ywwynm on 2015/9/25.
 * Using {@link AsyncTask} to load large images from SD-card.
 */

public class ImageLoader extends AsyncTask<Object, Integer, Bitmap> {

    public static final String TAG = "EverythingDone$ImageLoader";

    private int mType;
    private boolean mEditable = true;
    private boolean mKeepOriginalShape = false;
    private boolean mFillWithBlack = false;
    private WeakReference<LruCache<String, Bitmap>> mBitmapCacheReference;

    private WeakReference<ImageView> mIvImageWRF;
    private WeakReference<ImageView> mIvVideoSignalWRF;
    private WeakReference<ImageView> mIvDeleteWRF;
    private WeakReference<ProgressBar> mPbLoadingWRF;

    private WeakReference<PhotoViewAttacher> mAttacherWRF;

    public ImageLoader(int type, LruCache<String, Bitmap> cache,
                       ImageView ivImage, ProgressBar pbLoading) {
        mType = type;
        mBitmapCacheReference = new WeakReference<>(cache);

        mIvImageWRF   = new WeakReference<>(ivImage);
        mPbLoadingWRF = new WeakReference<>(pbLoading);
    }

    public ImageLoader(int type, LruCache<String, Bitmap> cache,
                       ImageView ivImage, ImageView ivVideoSignal, ImageView ivDelete, ProgressBar pbLoading) {
        mType = type;
        mEditable = ivDelete.getVisibility() == View.VISIBLE;
        mFillWithBlack = true;
        mBitmapCacheReference = new WeakReference<>(cache);

        mIvImageWRF       = new WeakReference<>(ivImage);
        mIvVideoSignalWRF = new WeakReference<>(ivVideoSignal);
        mIvDeleteWRF      = new WeakReference<>(ivDelete);
        mPbLoadingWRF     = new WeakReference<>(pbLoading);
    }

    public ImageLoader(int type, boolean keepOriginalShape, LruCache<String, Bitmap> cache,
                       ImageView ivImage, ImageView ivVideoSignal, ProgressBar pbLoading,
                       PhotoViewAttacher attacher) {
        mType = type;
        mKeepOriginalShape = keepOriginalShape;
        mFillWithBlack = true;
        mBitmapCacheReference = new WeakReference<>(cache);

        mIvImageWRF       = new WeakReference<>(ivImage);
        mIvVideoSignalWRF = new WeakReference<>(ivVideoSignal);
        mPbLoadingWRF     = new WeakReference<>(pbLoading);

        mAttacherWRF = new WeakReference<>(attacher);
    }

    @Override
    protected void onProgressUpdate(Integer... values) {
        toggleLoadingUI(true);
    }

    @Override
    protected Bitmap doInBackground(Object... params) {
        String key = (String) params[0];
        String pathName = key.substring(key.indexOf(AttachmentHelper.SIZE_SEPARATOR) + 1, key.length());

        Bitmap bitmap = null;
        int reqWidth  = (int) params[1];
        int reqHeight = (int) params[2];

        if (mType == IMAGE) {
            if (mKeepOriginalShape) {
                bitmap = BitmapUtil.decodeFileFitsSize(pathName, reqWidth, reqHeight);
            } else {
                bitmap = BitmapUtil.decodeFileWithRequiredSize(pathName, reqWidth, reqHeight);
            }
        } else if (mType == VIDEO) {
            Bitmap src = AttachmentHelper.getImageFromVideo(pathName);
            if (mKeepOriginalShape) {
                bitmap = BitmapUtil.createScaledBitmap(src, reqWidth, reqHeight, true);
            } else {
                bitmap = BitmapUtil.createCroppedBitmap(src, reqWidth, reqHeight);
            }
        }

        if (mBitmapCacheReference != null) {
            LruCache<String, Bitmap> cache = mBitmapCacheReference.get();
            if (cache != null) {
                cache.put(key, bitmap);
            }
        }
        return bitmap;
    }

    @Override
    protected void onPostExecute(Bitmap bitmap) {
        toggleLoadingUI(false);
        if (mIvImageWRF != null && bitmap != null) {
            final ImageView iv = mIvImageWRF.get();
            if (iv != null) {
                iv.setImageBitmap(bitmap);
                if (mFillWithBlack) {
                    iv.setBackgroundColor(Color.BLACK);
                }
            }
            if (mAttacherWRF != null) {
                final PhotoViewAttacher attacher = mAttacherWRF.get();
                if (attacher != null && attacher.getImageView() == iv) {
                    attacher.update();
                }
            }
        }
    }

    private void toggleLoadingUI(boolean toLoading) {
        if (mType == VIDEO && mIvVideoSignalWRF != null) {
            ImageView ivVideoSignal = mIvVideoSignalWRF.get();
            if (ivVideoSignal != null) {
                ivVideoSignal.setVisibility(toLoading ? View.GONE : View.VISIBLE);
            }
        }
        if (mIvDeleteWRF != null) {
            ImageView ivDelete = mIvDeleteWRF.get();
            if (ivDelete != null) {
                ivDelete.setVisibility(toLoading ?
                        View.GONE : mEditable ? View.VISIBLE : View.GONE);
            }
        }
        if (mPbLoadingWRF != null) {
            ProgressBar pbLoading = mPbLoadingWRF.get();
            if (pbLoading != null) {
                pbLoading.setVisibility(toLoading ? View.VISIBLE : View.GONE);
            }
        }
    }
}