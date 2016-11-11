package com.ywwynm.everythingdone.helpers;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.support.v4.util.Pair;
import android.support.v7.widget.RecyclerView;
import android.widget.LinearLayout;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.fragments.AttachmentInfoDialogFragment;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.DeviceUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.FileUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;

import org.joda.time.DateTime;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Created by ywwynm on 2015/9/23.
 * Utils for attachment
 */
public class AttachmentHelper {

    public static final String TAG = "AttachmentHelper";

    private AttachmentHelper() {}

    public static final String SIGNAL = App.getApp().getString(R.string.base_signal);
    public static final String SIZE_SEPARATOR = "`";

    public static final int IMAGE  = 0;
    public static final int VIDEO  = 1;
    public static final int AUDIO  = 2;
    
    public static boolean isValidForm(String attachment) {
        return !attachment.isEmpty() && !attachment.equals("to QQ");
    }

    public static List<File> getOriginalFiles(String attachmentStr) {
        if (attachmentStr == null || !attachmentStr.contains(SIGNAL)) {
            return null;
        }
        List<File> files = new ArrayList<>();
        String[] typePathNames = attachmentStr.split(SIGNAL);
        for (int i = 1; i < typePathNames.length; i++) {
            String pathName = typePathNames[i].substring(1, typePathNames[i].length());
            File file = new File(pathName);
            if (file.exists()) {
                files.add(file);
            }
        }
        return files;
    }

    public static Pair<List<String>, List<String>> toAttachmentItems(String attachmentStr) {
        List<String> imageItems = new ArrayList<>();
        List<String> audioItems = new ArrayList<>();

        String[] typePathNames = attachmentStr.split(SIGNAL);
        for (int i = 1; i < typePathNames.length; i++) {
            String pathName = typePathNames[i].substring(1, typePathNames[i].length());
            File file = new File(pathName);

            // if user delete an attachment directly through deleting original file, we should
            // find it out.
            if (file.exists()) {
                if (!typePathNames[i].startsWith(String.valueOf(AUDIO))) {
                    imageItems.add(typePathNames[i]);
                } else {
                    audioItems.add(typePathNames[i]);
                }
            }
        }

        return new Pair<>(imageItems, audioItems);
    }

    public static String toAttachmentStr(List<String> imageItems, List<String> audioItems) {
        StringBuilder sb = new StringBuilder();
        if (imageItems != null) {
            for (String typePathName : imageItems) {
                sb.append(SIGNAL).append(typePathName);
            }
        }
        if (audioItems != null) {
            for (String typePathName : audioItems) {
                sb.append(SIGNAL).append(typePathName);
            }
        }
        return sb.toString();
    }

    public static String getFirstImageTypePathName(String attachment) {
        if (!isValidForm(attachment)) {
            return null;
        }
        String[] typePathNames = attachment.split(SIGNAL);
        for (int i = 1; i < typePathNames.length; i++) {
            if (!typePathNames[i].startsWith(String.valueOf(AUDIO))) {
                String pathName = typePathNames[i].substring(1, typePathNames[i].length());
                if (new File(pathName).exists()) {
                    return typePathNames[i];
                }
            }
        }
        return null;
    }

    public static String getImageAttachmentCountStr(String attachment, Context context) {
        if (!isValidForm(attachment)) {
            return null;
        }

        String[] typePathNames = attachment.split(SIGNAL);
        int imageCount = 0, videoCount = 0;
        for (int i = 1; i < typePathNames.length; i++) {
            File file = new File(typePathNames[i].substring(1, typePathNames[i].length()));
            if (file.exists()) {
                if (typePathNames[i].startsWith(String.valueOf(IMAGE))) {
                    imageCount++;
                } else if (typePathNames[i].startsWith(String.valueOf(VIDEO))) {
                    videoCount++;
                }
            }
        }

        if (imageCount == 0 && videoCount == 0) {
            return null;
        } else {
            String images = context.getString(R.string.images);
            String videos = context.getString(R.string.videos);
            if (!LocaleUtil.isChinese(context)) {
                if (imageCount > 1) {
                    images += "s";
                }
                if (videoCount > 1) {
                    videos += "s";
                }
            }

            if (imageCount != 0 && videoCount == 0) {
                return imageCount + " " + images;
            } else if (imageCount == 0) { // && videoCount != 0
                return videoCount + " " + videos;
            } else {
                return imageCount + " " + images + ", " + videoCount + " " + videos;
            }
        }
    }

    public static String getAudioAttachmentCountStr(String attachment, Context context) {
        if (!isValidForm(attachment)) {
            return null;
        }
        String[] typePathNames = attachment.split(SIGNAL);
        int count = 0;
        for (int i = 1; i < typePathNames.length; i++) {
            File file = new File(typePathNames[i].substring(1, typePathNames[i].length()));
            if (file.exists() && typePathNames[i].startsWith(String.valueOf(AUDIO))) {
                count++;
            }
        }

        if (count == 0) {
            return null;
        } else {
            String audios = context.getString(R.string.audios);
            if (!LocaleUtil.isChinese(context) && count > 1) {
                audios += "s";
            }
            return count + " " + audios;
        }
    }

    public static File createAttachmentFile(int type) {
        String folderName, fileType;
        if (type == IMAGE) {
            folderName = "images";
            fileType = ".jpg";
        } else if (type == VIDEO) {
            folderName = "videos";
            fileType = ".mp4";
        } else {
            folderName = "audios";
            fileType = ".wav";
        }

        String fileName = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + fileType;
        return FileUtil.createFile(Def.Meta.APP_FILE_DIR + "/" + folderName, fileName);
    }

    public static int[] calculateImageSize(Context context, int itemSize) {
        int[] size = new int[2];
        Resources res = context.getResources();
        boolean isTablet = DisplayUtil.isTablet(context);
        int orientation = res.getConfiguration().orientation;
        int displayWidth = res.getDisplayMetrics().widthPixels;

        if (isTablet) {
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                if (itemSize < 5) {
                    size[0] = displayWidth / itemSize;
                    size[1] = displayWidth / 3;
                } else {
                    size[0] = displayWidth / 5;
                    size[1] = size[0];
                }
            } else {
                if (itemSize == 1) {
                    size[0] = displayWidth;
                    size[1] = displayWidth * 3 / 4;
                } else if (itemSize == 2) {
                    size[0] = displayWidth / 2;
                    size[1] = displayWidth * 3 / 4;
                } else {
                    size[0] = displayWidth / 3;
                    size[1] = size[0];
                }
            }
        } else {
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                if (itemSize < 4) {
                    size[0] = displayWidth / itemSize;
                    size[1] = displayWidth / 3;
                } else {
                    size[0] = displayWidth / 4;
                    size[1] = size[0];
                }
            } else {
                if (itemSize == 1) {
                    size[0] = displayWidth;
                    size[1] = size[0] * 3 / 4;
                } else {
                    size[0] = displayWidth / 2;
                    size[1] = size[0];
                }
            }
        }
        return size;
    }

    public static Bitmap getImageFromVideo(String pathName) {
        Bitmap bitmap = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(pathName);
            bitmap = retriever.getFrameAtTime();
        } catch(Exception e) {
            e.printStackTrace();
        } finally {
            retriever.release();
        }
        return bitmap;
    }

    public static void setImageRecyclerViewHeight(RecyclerView recyclerView, int itemSize, int maxSpan) {
        int height;
        int itemHeight = calculateImageSize(recyclerView.getContext(), itemSize)[1];
        if (itemSize <= maxSpan) {
            height = itemHeight;
        } else {
            height = itemHeight * (itemSize / maxSpan);
            if (itemSize % maxSpan != 0) {
                height += itemHeight;
            }
        }

        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) recyclerView.getLayoutParams();
        params.height = height;
        recyclerView.requestLayout();
    }

    public static void setAudioRecyclerViewHeight(RecyclerView recyclerView, int itemSize, int span) {
        float density = recyclerView.getContext().getResources().getDisplayMetrics().density;

        int rows = itemSize / span;
        if (itemSize % span != 0) {
            rows++;
        }
        int itemHeight = (int) (density * 56);
        if (DeviceUtil.hasLollipopApi()) {
            itemHeight += density * 8;
        }

        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) recyclerView.getLayoutParams();
        params.height = itemHeight * rows;
        recyclerView.requestLayout();
    }

    public static void showAttachmentInfoDialog(Activity activity, int accentColor, String typePathName) {
        AttachmentInfoDialogFragment aidf = new AttachmentInfoDialogFragment();
        aidf.setAccentColor(accentColor);
        aidf.setItems(getAttachmentInfo(activity, typePathName));
        aidf.show(activity.getFragmentManager(), AttachmentInfoDialogFragment.TAG);
    }

    private static List<Pair<String, String>> getAttachmentInfo(Context context, String typePathName) {
        char type = typePathName.charAt(0);
        String pathName = typePathName.substring(1, typePathName.length());

        List<Pair<String, String>> list = new ArrayList<>();
        File file = new File(pathName);
        String fst = context.getString(R.string.file_path);
        if (!file.exists()) {
            String sec = context.getString(R.string.file_path_not_existed);
            list.add(new Pair<>(fst, sec));
            return list;
        }

        String sec = file.getAbsolutePath();
        list.add(new Pair<>(fst, sec));

        fst = context.getString(R.string.file_size);
        sec = FileUtil.getFileSizeStr(file);
        list.add(new Pair<>(fst, sec));

        if (type == '0') {
            return getAttachmentInfoImage(list, context, pathName);
        } else if (type == '1') {
            return getAttachmentInfoVideo(list, context, pathName);
        } else {
            return getAttachmentInfoAudio(list, context, pathName);
        }
    }

    private static List<Pair<String, String>> getAttachmentInfoImage(
            List<Pair<String, String>> list, Context context, String pathName) {
        String fst = context.getString(R.string.image_size);
        int[] size = FileUtil.getImageSize(pathName);
        String sec = size[0] + " * " + size[1];
        list.add(new Pair<>(fst, sec));

        DateTime dateTime = FileUtil.getImageCreateTime(pathName);
        if (dateTime == null) {
            fst = context.getString(R.string.file_last_modify_time);
            File file = new File(pathName);
            sec = DateTimeUtil.getGeneralDateTimeStr(context, file.lastModified());
        } else {
            fst = context.getString(R.string.image_create_time);
            sec = dateTime.toString(DateTimeUtil.getGeneralDateTimeFormatPattern(context));
        }
        list.add(new Pair<>(fst, sec));

        return list;
    }

    private static List<Pair<String, String>> getAttachmentInfoVideo(
            List<Pair<String, String>> list, Context context, String pathName) {
        int[] size = FileUtil.getVideoSize(pathName);
        if (size == null) {
            return null;
        }

        String fst = context.getString(R.string.video_size);
        String sec = size[0] + " * " + size[1];
        list.add(new Pair<>(fst, sec));

        fst = context.getString(R.string.video_duration);
        long duration = FileUtil.getMediaDuration(pathName);
        sec = DateTimeUtil.getDurationBriefStr(duration);
        list.add(new Pair<>(fst, sec));

        fst = context.getString(R.string.video_create_time);
        DateTime dateTime = FileUtil.getVideoCreateTime(pathName);
        if (dateTime == null || dateTime.compareTo(new DateTime(1970, 1, 1, 0, 0)) < 0) {
            fst = context.getString(R.string.file_last_modify_time);
            File file = new File(pathName);
            sec = DateTimeUtil.getGeneralDateTimeStr(context, file.lastModified());
        } else {
            sec = dateTime.toString(DateTimeUtil.getGeneralDateTimeFormatPattern(context));
        }
        list.add(new Pair<>(fst, sec));

        return list;
    }

    private static List<Pair<String, String>> getAttachmentInfoAudio(
            List<Pair<String, String>> list, Context context, String pathName) {
        String fst = context.getString(R.string.file_last_modify_time);
        File file = new File(pathName);
        String sec = DateTimeUtil.getGeneralDateTimeStr(context, file.lastModified());
        list.add(new Pair<>(fst, sec));

        fst = context.getString(R.string.audio_duration);
        long duration = FileUtil.getMediaDuration(pathName);
        sec = DateTimeUtil.getDurationBriefStr(duration);
        list.add(new Pair<>(fst, sec));

        fst = context.getString(R.string.audio_bitrate);
        int bitrate = FileUtil.getAudioBitrate(pathName);
        sec = bitrate + " Kbps";
        list.add(new Pair<>(fst, sec));

        fst = context.getString(R.string.audio_sample_rate);
        int sampleRate = FileUtil.getAudioSampleRate(pathName);
        sec = sampleRate + " Hz";
        list.add(new Pair<>(fst, sec));

        return list;
    }

    public static List<String> getAttachmentsToDelete(String attachmentBefore, String attachmentAfter) {
        if (attachmentBefore.equals(attachmentAfter)) {
            return null;
        }
        List<String> attachmentsToDelete = new ArrayList<>();
        String appDir = Def.Meta.APP_FILE_DIR;
        String pathName;
        String[] attachmentsBefore = attachmentBefore.split(SIGNAL);
        for (int i = 1; i < attachmentsBefore.length; i++) {
            pathName = attachmentsBefore[i].substring(1, attachmentsBefore[i].length());
            if (pathName.startsWith(appDir) && !attachmentAfter.contains(attachmentsBefore[i])) {
                attachmentsToDelete.add(pathName);
            }
        }
        return attachmentsToDelete;
    }

    public static boolean isImageFile(String postfix) {
        String[] postfixes = new String[] { "png", "jpg", "jpeg", "gif", "bmp", "webp" };
        return isInsideArray(postfixes, postfix);
    }

    public static boolean isVideoFile(String postfix) {
        String[] postfixes = new String[] { "3gp", "mp4", "webm", "mkv" };
        return isInsideArray(postfixes, postfix);
    }

    public static boolean isAudioFile(String postfix) {
        String[] postfixes = new String[] { "wav", "mp3", "3gp", "mp4", "aac", "flac", "mid", "xmf",
                "mxmf", "rtttl", "rtx", "ota", "imy", "ogg", "mkv" };
        return isInsideArray(postfixes, postfix);
    }

    public static ArrayList<Uri> toUriList(String attachment) {
        if (attachment == null || attachment.isEmpty()) {
            return null;
        }
        String[] typePathNames = attachment.split(SIGNAL);
        ArrayList<Uri> ret = new ArrayList<>();
        for (String typePathName : typePathNames) {
            if (typePathName.isEmpty()) continue;
            String pathName = typePathName.substring(1, typePathName.length());
            Uri uri = Uri.fromFile(new File(pathName));
            ret.add(uri);
        }
        return ret;
    }

    public static boolean isAllImage(String attachment) {
        if (attachment == null || attachment.isEmpty()) {
            return false;
        }
        return !attachment.contains(SIGNAL + VIDEO) && !attachment.contains(SIGNAL + AUDIO);
    }

    public static boolean isAllAudio(String attachment) {
        if (attachment == null || attachment.isEmpty()) {
            return false;
        }
        return !attachment.contains(SIGNAL + IMAGE) && !attachment.contains(SIGNAL + VIDEO);
    }

    private static boolean isInsideArray(String[] array, String value) {
        for (String s : array) {
            if (value.equals(s)) {
                return true;
            }
        }
        return false;
    }
}
