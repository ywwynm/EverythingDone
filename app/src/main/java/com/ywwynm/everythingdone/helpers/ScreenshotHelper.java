package com.ywwynm.everythingdone.helpers;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.DrawableRes;
import android.support.v4.content.ContextCompat;
import android.support.v4.widget.NestedScrollView;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.adapters.AudioAttachmentAdapter;
import com.ywwynm.everythingdone.adapters.CheckListAdapter;
import com.ywwynm.everythingdone.adapters.ImageAttachmentAdapter;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.fragments.LoadingDialogFragment;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.ReminderHabitParams;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.BitmapUtil;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
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

    private ScreenshotHelper() {}

    private static List<File> sScreenshotFiles;

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
        if (sScreenshotFiles == null) {
            sScreenshotFiles = new ArrayList<>();
        }
        new ScreenshotTask(callback).execute(view, color);
    }

    public static void clearGeneratedScreenshots() {
        if (sScreenshotFiles != null) {
            for (File generatedFile : sScreenshotFiles) {
                FileUtil.deleteFile(generatedFile);
            }
            sScreenshotFiles.clear();
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

        String name = "screenshot_";
        name += new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
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
            sScreenshotFiles.add(file);
            if (mCallback != null) {
                mCallback.onTaskDone(file);
            }
        }
    }


    // ---------- helper constants/methods/classes for screenshot in DetailActivity ---------- //

    public static final int UPDATE_TITLE            = 0;
    public static final int UPDATE_TITLE_PADDING    = 1;
    public static final int UPDATE_CONTENT          = 2;
    public static final int UPDATE_CONTENT_MARGIN   = 3;
    public static final int UPDATE_CHECKLIST        = 4;
    public static final int UPDATE_CHECKLIST_MARGIN = 5;
    public static final int UPDATE_IMAGE            = 6;
    public static final int UPDATE_AUDIO            = 7;
    public static final int UPDATE_AUDIO_MARGIN     = 8;

    private static final float density = DisplayUtil.getScreenDensity(App.getApp());

    public static void showTypeInfo(
            View layout, long thingId, @Thing.Type int type, @Thing.State int thingState,
            ReminderHabitParams rhParams) {
        ImageView ivIcon = (ImageView) layout.findViewById(R.id.iv_icon_type_info);
        Context context = ivIcon.getContext();
        @DrawableRes int iconRes = Thing.getTypeIconWhiteLarge(type);
        Drawable d1 = ContextCompat.getDrawable(ivIcon.getContext(), iconRes);
        Drawable d2 = d1.mutate();
        d2.setColorFilter(ContextCompat.getColor(context, R.color.white_66p), PorterDuff.Mode.SRC_IN);
        ivIcon.setImageDrawable(d2);

        TextView tvInfo = (TextView) layout.findViewById(R.id.tv_type_info);
        LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) tvInfo.getLayoutParams();
        if (Thing.isReminderType(type)) {
            long reminderInMillis = rhParams.getReminderInMillis();
            if (reminderInMillis == -1) {
                int[] timeAfterType = rhParams.getReminderAfterTime();
                reminderInMillis = DateTimeUtil.getActualTimeAfterSomeTime(timeAfterType);
            }
            String dateTimeStrAt = DateTimeUtil.getDateTimeStrAt(reminderInMillis, context, false);
            if (dateTimeStrAt.startsWith("on ")) {
                dateTimeStrAt = dateTimeStrAt.substring(3, dateTimeStrAt.length());
            }
            tvInfo.append(dateTimeStrAt);
            Reminder reminder = ReminderDAO.getInstance(context).getReminderById(thingId);
            if (reminder != null && reminder.getState() != Reminder.UNDERWAY) {
                tvInfo.append(", " + Reminder.getStateDescription(
                        thingState, reminder.getState(), context));
            }
            if (type == Thing.REMINDER) {
                llp.topMargin = (int) (density * 0.5);
            } else {
                llp.topMargin = (int) (density * 1.5);
            }
        } else if (type == Thing.HABIT) {
            String dateTimeStrRec = DateTimeUtil.getDateTimeStrRec(
                    context, rhParams.getHabitType(), rhParams.getHabitDetail());
            if (dateTimeStrRec != null && dateTimeStrRec.startsWith("at ")) {
                dateTimeStrRec = dateTimeStrRec.substring(3, dateTimeStrRec.length());
            }
            tvInfo.append(dateTimeStrRec);
            llp.topMargin = (int) (density * 0.5);
        } else { // note
            layout.setVisibility(View.GONE);
            return; // don't show layout
        }

        tvInfo.requestLayout();
        layout.setVisibility(View.VISIBLE);
    }

    public static void hideTypeInfo(View layout) {
        layout.setVisibility(View.GONE);
    }

    public static List<Integer> updateThingUiBeforeScreenshot(
            boolean editable,
            EditText etTitle, EditText etContent,
            RecyclerView rvChecklist, CheckListAdapter checkListAdapter,
            LinearLayout llMoveChecklist,
            RecyclerView rvImage, ImageAttachmentAdapter imageAdapter,
            RecyclerView rvAudio, AudioAttachmentAdapter audioAdapter) {
        List<Integer> didList = new ArrayList<>();
        boolean noTitle = etTitle.getText().toString().isEmpty();
        boolean noImage = rvImage == null || rvImage.getVisibility() != View.VISIBLE
                || imageAdapter == null;
        if (noTitle) {
            etTitle.setVisibility(View.GONE);
            didList.add(UPDATE_TITLE);
        } else if (noImage) {
            float top = density * 20;
            etTitle.setPadding(etTitle.getPaddingLeft(), (int) top, etTitle.getPaddingRight(), 0);
            etTitle.requestLayout();
            didList.add(UPDATE_TITLE_PADDING);
        }

        if (etContent.getVisibility() == View.VISIBLE &&
                etContent.getText().toString().isEmpty()) {
            etContent.setVisibility(View.GONE);
            didList.add(UPDATE_CONTENT);
        } else if (!noImage) {
            LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams)
                    etContent.getLayoutParams();
            llp.topMargin = (int) (density * 8);
            etContent.requestLayout();
            didList.add(UPDATE_CONTENT_MARGIN);
        }

        if (editable) {
            if (rvChecklist != null && rvChecklist.getVisibility() == View.VISIBLE
                    && checkListAdapter != null && llMoveChecklist.getVisibility() == View.VISIBLE) {
                // 5 possible situations for finished/unfinished items
                List<String> items = checkListAdapter.getItems();
                boolean unfinishedExisted = CheckListHelper.getLastUnfinishedItemIndex(items) != -1;
                items.remove("2");
                if (!unfinishedExisted) {
                    items.remove("3");
                }
                checkListAdapter.notifyDataSetChanged();
                llMoveChecklist.setVisibility(View.GONE);
                didList.add(UPDATE_CHECKLIST);

                if (noTitle && !noImage) {
                    LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams)
                            rvChecklist.getLayoutParams();
                    if (!unfinishedExisted) {
                        llp.topMargin = (int) (density * -4);
                    } else {
                        llp.topMargin = (int) (density * 8);
                    }
                    rvChecklist.requestLayout();
                    didList.add(UPDATE_CHECKLIST_MARGIN);
                }
            }
            if (!noImage) {
                imageAdapter.setTakingScreenshot(true);
                didList.add(UPDATE_IMAGE);
            }
        }
        if (rvAudio != null && rvAudio.getVisibility() == View.VISIBLE && audioAdapter != null) {
            audioAdapter.setTakingScreenshot(true);
            didList.add(UPDATE_AUDIO);
            boolean noContent = etContent.getVisibility() != View.VISIBLE ||
                    etContent.getText().toString().isEmpty();
            if (noTitle && noContent) {
                LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) rvAudio.getLayoutParams();
                if (noImage) {
                    llp.topMargin = (int) (density * 20);
                } else {
                    llp.topMargin = (int) (density * 8);
                }
                rvAudio.requestLayout();
                didList.add(UPDATE_AUDIO_MARGIN);
            }
        }
        return didList;
    }

    public static void updateThingUiAfterScreenshot(
            List<Integer> didList,
            EditText etTitle, EditText etContent,
            RecyclerView rvChecklist, CheckListAdapter checkListAdapter,
            LinearLayout llMoveChecklist,
            ImageAttachmentAdapter imageAdapter,
            RecyclerView rvAudio, AudioAttachmentAdapter audioAdapter) {
        for (int did : didList) {
            if (did == UPDATE_TITLE) {
                etTitle.setVisibility(View.VISIBLE);
            } else if (did == UPDATE_TITLE_PADDING) {
                float top = density * 12;
                etTitle.setPadding(etTitle.getPaddingLeft(), (int) top, etTitle.getPaddingRight(), 0);
                etTitle.requestLayout();
            } else if (did == UPDATE_CONTENT) {
                etContent.setVisibility(View.VISIBLE);
            } else if (did == UPDATE_CONTENT_MARGIN) {
                LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams)
                        etContent.getLayoutParams();
                llp.topMargin = (int) (density * 20);
                etContent.requestLayout();
            } else if (did == UPDATE_CHECKLIST) { // must be editable if go here
                List<String> items = checkListAdapter.getItems();
                int index = CheckListHelper.getLastUnfinishedItemIndex(items) + 1;
                items.add(index, "2");
                if (!items.get(index + 1).equals("3")) {
                    items.add(index + 1, "3");
                }
                checkListAdapter.notifyDataSetChanged();
                llMoveChecklist.setVisibility(View.VISIBLE);
            } else if (did == UPDATE_CHECKLIST_MARGIN) {
                LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams)
                        rvChecklist.getLayoutParams();
                llp.topMargin = (int) (density * 20);
                rvChecklist.requestLayout();
            } else if (did == UPDATE_IMAGE) {
                imageAdapter.setTakingScreenshot(false);
            } else if (did == UPDATE_AUDIO) {
                audioAdapter.setTakingScreenshot(false);
            } else if (did == UPDATE_AUDIO_MARGIN) {
                LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) rvAudio.getLayoutParams();
                llp.topMargin = (int) (density * 32);
                rvAudio.requestLayout();
            }
        }
    }

    // ---------- end helper things for DetailActivity ---------- //

}
