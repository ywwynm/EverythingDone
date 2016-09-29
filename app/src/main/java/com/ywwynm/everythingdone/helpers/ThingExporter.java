package com.ywwynm.everythingdone.helpers;

import android.app.Activity;
import android.content.Context;
import android.os.AsyncTask;

import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.fragments.AlertDialogFragment;
import com.ywwynm.everythingdone.fragments.LoadingDialogFragment;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.FileUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Created by ywwynm on 2016/6/28.
 * A helper class to export a {@link Thing} to a txt/zip file
 */
public class ThingExporter {

    public static final String TAG = "ThingExporter";

    private ThingExporter() {}

    public static void startExporting(Activity activity, int accentColor, Thing... things) {
        new ExportTask(activity, accentColor).execute(things);
    }

    private static class ExportTask extends AsyncTask<Thing, Object ,Integer> {

        private WeakReference<Activity> mWrActivity;
        private WeakReference<LoadingDialogFragment> mWrLdf;
        private int mAccentColor;
        private int mParamsLength;

        ExportTask(Activity activity, int accentColor) {
            mWrActivity = new WeakReference<>(activity);
            mAccentColor = accentColor;
        }

        @Override
        protected void onPreExecute() {
            Activity activity = mWrActivity.get();
            if (activity == null) {
                return;
            }

            LoadingDialogFragment ldf = new LoadingDialogFragment();
            ldf.setAccentColor(mAccentColor);
            ldf.setTitle(activity.getString(R.string.export_loading_title));
            ldf.setContent(activity.getString(R.string.export_loading_content));

            mWrLdf = new WeakReference<>(ldf);
            ldf.show(activity.getFragmentManager(), LoadingDialogFragment.TAG);
        }

        @Override
        protected Integer doInBackground(Thing... params) {
            mParamsLength = params.length;
            int successTimes = 0;
            for (int i = 0; i < mParamsLength; i++) {
                if (mWrActivity == null) {
                    return null;
                }

                Activity activity = mWrActivity.get();
                if (activity == null) {
                    continue;
                }

                if (export(activity, params[i])) {
                    successTimes++;
                }
            }
            return successTimes;
        }

        @Override
        protected void onPostExecute(Integer count) {
            if (count == null) {
                return;
            }

            if (mWrActivity == null || mWrLdf == null) {
                return;
            }

            LoadingDialogFragment ldf = mWrLdf.get();
            if (ldf == null) {
                return;
            }

            ldf.dismiss();

            Activity activity = mWrActivity.get();
            if (activity == null) {
                return;
            }

            AlertDialogFragment adf = new AlertDialogFragment();
            adf.setShowCancel(false);
            adf.setTitleColor(mAccentColor);
            adf.setConfirmColor(mAccentColor);

            if (count >= 1) {
                adf.setTitle(activity.getString(R.string.export_success_title));
                String content1 = activity.getString(R.string.export_success_content_part_1);
                String content = String.format(content1, count);
                if (count > 1 && !LocaleUtil.isChinese(activity)) {
                    content += "s";
                }
                content += activity.getString(R.string.export_success_content_part_2);
                if (count > 1 && !LocaleUtil.isChinese(activity)) {
                    content += "s.";
                }
                adf.setContent(content);
            } else {
                adf.setTitle(activity.getString(R.string.export_failed_title));
                adf.setContent(activity.getString(R.string.export_failed_content));
            }

            adf.show(activity.getFragmentManager(), AlertDialogFragment.TAG);
        }
    }

    /**
     * Export a thing to a file thus user can view on backup it on PC.
     * If the thing only contains text, it will be saved as a txt file.
     * Otherwise, the method will bundle text and attachments(of course, there will be a file size
     * check to prevent suffering from big file) into a zip.
     *
     * @param thing the thing to export
     * @return {@code true} if export successfully, {@code false} otherwise.
     */
    public static boolean export(Context context, Thing thing) {
        String thingFileName = getFileName(context, thing);
        // should be like "hello-world-Note-20160630143306"

        final String parentPath = Def.Meta.APP_FILE_DIR + "/temp/" + thingFileName;
        File txtFile = thingToTxtFile(context, thing, parentPath);
        List<File> attachmentFiles = AttachmentHelper.getOriginalFiles(thing.getAttachment());

        if (txtFile == null && (attachmentFiles == null || attachmentFiles.isEmpty())) {
            // the thing contains neither text content nor attachments
            return false;
        }

        if (attachmentFiles != null && !attachmentFiles.isEmpty()) {
            for (File attachmentFile : attachmentFiles) {
                String name = attachmentFile.getName();
                File dstFile = FileUtil.createFile(parentPath, name);
                try {
                    FileUtil.copyFile(attachmentFile, dstFile);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        File dir = new File(parentPath);
        File zippedFile = FileUtil.createFile(
                Def.Meta.APP_FILE_DIR + "/export", thingFileName + ".zip");
        if (zippedFile == null) {
            return false;
        }

        boolean zipped = FileUtil.zipDirectory(dir, zippedFile);
        if (!zipped) {
            FileUtil.deleteDirectory(dir);
            return false;
        }

        FileUtil.deleteDirectory(dir);
        return true;
    }

    private static File thingToTxtFile(Context context, Thing thing, String parentPath) {
        File file = FileUtil.createFile(parentPath, getFileName(context, thing) + ".txt");
        if (file == null) {
            return null;
        }

        String fileContent = SendInfoHelper.getThingShareInfo(context, thing);
        if (fileContent == null) {
            return null;
        }
        int lastN = fileContent.lastIndexOf('\n');
        if (lastN == -1) {
            return null;
        }

        fileContent = fileContent.substring(0, lastN); // without "from everythingdone"
        writeToFile(file, fileContent);
        return file;
    }

    private static String getFileName(Context context, Thing thing) {
        StringBuilder sb = new StringBuilder();
        String title = thing.getTitleToDisplay();
        boolean useContent = true;
        if (!title.isEmpty()) {
            title = title.trim();
            if (title.length() > 10) {
                title = title.substring(0, 10);
            }
            if (FileUtil.isAppropriateAsFileName(title)) {
                sb.append(title).append("-");
                useContent = false;
            }
        }

        String content = thing.getContent();
        if (useContent && !content.isEmpty()) {
            if (CheckListHelper.isCheckListStr(content)) {
                content = CheckListHelper.toContentStr(content, "", "");
            }

            content = removeReturns(content.trim());

            if (content.length() > 10) {
                content = content.substring(0, 10);
                int rIndex = content.indexOf('\n');
                if (rIndex != -1) { // such as a\n\nb
                    content = content.substring(0, rIndex);
                }
            }
            if (FileUtil.isAppropriateAsFileName(content)) {
                sb.append(content).append("-");
            }
        }

        sb.append(Thing.getTypeStr(thing.getType(), context)).append("-");
        sb.append(new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date()));

        return sb.toString();
    }

    private static String removeReturns(String str) {
        final int count = str.length();
        int start = 0, last = count - 1;
        int end = last;
        while ((start <= end) && (str.charAt(start) <= '\n')) {
            start++;
        }
        while ((end >= start) && (str.charAt(end) <= '\n')) {
            end--;
        }
        if (start == 0 && end == last) {
            return str;
        }
        return str.substring(start, end - start + 1);
    }

    private static void writeToFile(File file, String str) {
        try {
            FileWriter fw = new FileWriter(file);
            BufferedWriter bw = new BufferedWriter(fw);
            bw.write(str);
            bw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
