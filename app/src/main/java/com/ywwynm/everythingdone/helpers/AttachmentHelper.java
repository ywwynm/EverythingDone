package com.ywwynm.everythingdone.helpers;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Environment;
import android.support.v7.widget.RecyclerView;
import android.util.Pair;
import android.widget.LinearLayout;

import com.ywwynm.everythingdone.EverythingDoneApplication;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;
import com.ywwynm.everythingdone.utils.VersionUtil;

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

    public static final String SIGNAL = "`启q琼";
    public static final String SIZE_SEPARATOR = "`";

    public static final int IMAGE  = 0;
    public static final int VIDEO  = 1;
    public static final int AUDIO  = 2;

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
        if (attachment.isEmpty() || attachment.equals("to QQ")) {
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
        if (attachment.isEmpty() || attachment.equals("to QQ")) {
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
            } else if (videoCount != 0 && imageCount == 0) {
                return videoCount + " " + videos;
            } else {
                return imageCount + " " + images + ", " + videoCount + " " + videos;
            }
        }
    }

    public static String getAudioAttachmentCountStr(String attachment, Context context) {
        if (attachment.isEmpty() || attachment.equals("to QQ")) {
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

    public static String generateKeyForCache(String pathName, int width, int height) {
        return width + "*" + height + SIZE_SEPARATOR + pathName;
    }

    public static File createTempFile(String postfix) {
        File dir = new File(Environment.getExternalStorageDirectory().getAbsolutePath()
                + "/EverythingDone/temp");
        if (!dir.exists()) {
            boolean parentCreated = dir.mkdirs();
            if (!parentCreated) {
                return null;
            }
        }

        String timeStamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        return new File(dir, timeStamp + postfix);
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

        File dir = new File(Environment.getExternalStorageDirectory().getAbsolutePath()
                + "/EverythingDone/" + folderName);
        if (!dir.exists()) {
            boolean parentCreated = dir.mkdirs();
            if (!parentCreated) {
                return null;
            }
        }

        String timeStamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        return new File(dir, timeStamp + fileType);
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
            try {
                retriever.release();
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
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
        if (VersionUtil.hasLollipopApi()) {
            itemHeight += density * 8;
        }

        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) recyclerView.getLayoutParams();
        params.height = itemHeight * rows;
        recyclerView.requestLayout();
    }

    public static List<String> getAttachmentsToDelete(String attachmentBefore, String attachmentAfter) {
        if (attachmentBefore.equals(attachmentAfter)) {
            return null;
        }
        List<String> attachmentsToDelete = new ArrayList<>();
        String myPath = EverythingDoneApplication.APP_FILE_FOLDER;
        String pathName;
        String[] attachmentsBefore = attachmentBefore.split(SIGNAL);
        for (int i = 1; i < attachmentsBefore.length; i++) {
            pathName = attachmentsBefore[i].substring(1, attachmentsBefore[i].length());
            if (pathName.startsWith(myPath) && !attachmentAfter.contains(attachmentsBefore[i])) {
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

    private static boolean isInsideArray(String[] array, String value) {
        for (String s : array) {
            if (value.equals(s)) {
                return true;
            }
        }
        return false;
    }
}
