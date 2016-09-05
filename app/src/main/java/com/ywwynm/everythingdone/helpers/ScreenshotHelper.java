package com.ywwynm.everythingdone.helpers;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.StringRes;
import android.support.v4.widget.NestedScrollView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.fragments.LoadingDialogFragment;
import com.ywwynm.everythingdone.utils.BitmapUtil;
import com.ywwynm.everythingdone.utils.FileUtil;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Created by qiizhang on 2016/9/5.
 * Helper class to get screenshot, especially when there is a scrollable view.
 */
public class ScreenshotHelper {

    public static final String TAG = "ScreenshotHelper";

    private static List<File> sGeneratedFiles;

    public interface ScreenshotCallback {
        void onTaskDone(File file);
    }

    public static class ShareCallback implements ScreenshotCallback {

        private WeakReference<Context> mWrContext;
        private WeakReference<LoadingDialogFragment> mWrLdf;
        private String mShareTitle;

        public ShareCallback(Context context, LoadingDialogFragment ldf, String shareTitle) {
            mWrContext  = new WeakReference<>(context);
            mWrLdf      = new WeakReference<>(ldf);
            mShareTitle = shareTitle;
        }

        @Override
        public void onTaskDone(File file) {
            if (mWrLdf != null) {
                LoadingDialogFragment ldf = mWrLdf.get();
                if (ldf != null) {
                    ldf.dismiss();
                }
            }

            if (mWrContext == null) return;
            Context context = mWrContext.get();
            if (context == null) return;

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/jpeg");
            intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file));
            context.startActivity(Intent.createChooser(intent, mShareTitle));
        }
    }

    public static void startScreenshot(View view, ScreenshotCallback callback) {
        startScreenshot(view, 0, callback);
    }

    public static void startScreenshot(View view, int color, ScreenshotCallback callback) {
        if (sGeneratedFiles == null) {
            sGeneratedFiles = new ArrayList<>();
        }
        new ScreenshotTask(callback).execute(view, color);
    }

    public static void clearGeneratedScreenshots() {
        if (sGeneratedFiles != null) {
            for (File generatedFile : sGeneratedFiles) {
                FileUtil.deleteFile(generatedFile);
            }
            sGeneratedFiles.clear();
        }
    }

    private static File getScreenshot(Object... params) {
        if (params == null || params.length == 1) {
            return null;
        }
        View view = (View) params[0];
        if (view == null) {
            return null;
        }
        int color = (int) params[1];
        return getScreenshot(view, color);
    }

    private static File getScreenshot(View view, int color) {
        if (view instanceof ScrollView) {
            return getScreenShotForScrollViews((ScrollView) view, color);
        } else if (view instanceof NestedScrollView) {
            return getScreenShotForScrollViews((NestedScrollView) view, color);
        }
        return null;
    }

    private static File getScreenShotForScrollViews(FrameLayout scrollView, int color) {
        final int count = scrollView.getChildCount();
        int height = 0;
        for (int i = 0; i < count; i++) {
            View view = scrollView.getChildAt(i);
            height += view.getHeight();
        }

        // get screen shot bitmap
        Bitmap bitmap = Bitmap.createBitmap(scrollView.getWidth(), height,
                Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(color);
        scrollView.draw(canvas);

        String name = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        name += ".jpeg";
        return BitmapUtil.saveBitmapToStorage(FileUtil.TEMP_PATH, name, bitmap);
    }

    private static class ScreenshotTask extends AsyncTask<Object, Object, File> {

        private ScreenshotCallback mCallback;

        ScreenshotTask(ScreenshotCallback callback) {
            mCallback = callback;
        }

        @Override
        protected File doInBackground(Object... params) {
            // sleep this thread for 1.6s to make sure that possibly-existed scrollbar have been
            // drawn completely. As a result, we can get screenshot of view by view.draw(Canvas).
            // Otherwise, we may get Exception and crash because we draw something in worker thread.
            try {
                Thread.sleep(1600);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return getScreenshot(params);
        }

        @Override
        protected void onPostExecute(File file) {
//            mLdf.dismiss();
//            mSharedFiles.add(file);
//            Intent intent = new Intent(Intent.ACTION_SEND);
//            intent.setType("image/jpeg");
//            intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file));
//            startActivity(Intent.createChooser(
//                    intent, getString(R.string.share_statistic)));
            sGeneratedFiles.add(file);
            if (mCallback != null) {
                mCallback.onTaskDone(file);
            }
        }
    }

}
