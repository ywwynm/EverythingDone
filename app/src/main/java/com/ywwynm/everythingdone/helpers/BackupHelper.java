package com.ywwynm.everythingdone.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.FileUtil;

import java.time.ZonedDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by ywwynm on 2016/3/20.
 * helper class used to backup and restore data
 */
public class BackupHelper {

    public static final String TAG = "BackupHelper";

    private BackupHelper() {}

    private static final String BACKUP_FILE_NAME_OLD = "EverythingDone.bak";
    private static final String BACKUP_FILE_POSTFIX = "bak";

    private static final String BACKUP_DIR = "/backup";
    private static final String BACKUP_FILE_NAME_PREFIX = "ED_backup_";

    public static boolean backup(Context context, Uri outputUri) {
        File src = new File(context.getApplicationInfo().dataDir);
        String tempDirPath = Def.getAppFileDir(context) + BACKUP_DIR;
        long curTime = System.currentTimeMillis();
        ZonedDateTime dt = Instant.ofEpochMilli(curTime).atZone(ZoneId.systemDefault());
        String timeStr = dt.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String backupFileName = BACKUP_FILE_NAME_PREFIX + timeStr + "." + BACKUP_FILE_POSTFIX;
        File dst = FileUtil.createFile(tempDirPath, backupFileName);
        if (dst == null) return false;

        if (!FileUtil.zipDirectory(src, dst, false, getBackupFilePaths(context))) {
            FileUtil.deleteFile(dst);
            return false;
        }

        try {
            copyFileToUri(context, dst, outputUri);
            SharedPreferences sp = context.getSharedPreferences(
                    Def.Meta.META_DATA_NAME, Context.MODE_PRIVATE);
            sp.edit().putLong(Def.Meta.KEY_LAST_BACKUP_TIME, curTime).apply();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            FileUtil.deleteFile(dst);
        }
    }

    public static String getLastBackupTimeString() {
        Context context = App.getApp();
        SharedPreferences sp = context.getSharedPreferences(
                Def.Meta.META_DATA_NAME, Context.MODE_PRIVATE);
        long time = sp.getLong(Def.Meta.KEY_LAST_BACKUP_TIME, -1L);
        if (time == -1L) {
            File backupFile = new File(
                    Environment.getExternalStorageDirectory(), BACKUP_FILE_NAME_OLD);
            if (backupFile.exists()) {
                time = backupFile.lastModified();
            }
        }

        if (time != -1L) {
            return context.getString(R.string.last_backup) + " "
                    + DateTimeUtil.getDateTimeStrAt(time, context, false);
        } else {
            return context.getString(R.string.no_backup_before);
        }
    }

    public static boolean restore(Context context, Uri inputUri) {
        long curTime = System.currentTimeMillis();
        String tempDirPath = Def.getAppFileDir(context) + BACKUP_DIR;
        File tempFile = new File(tempDirPath, "restore_" + curTime + "." + BACKUP_FILE_POSTFIX);

        try {
            File parent = tempFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            copyUriToFile(context, inputUri, tempFile);
        } catch (IOException e) {
            e.printStackTrace();
            FileUtil.deleteFile(tempFile);
            return false;
        }

        String unzippedDirPathName = tempDirPath + "/" + curTime;
        boolean unzipResult = FileUtil.unzip(tempFile.getAbsolutePath(), unzippedDirPathName);
        FileUtil.deleteFile(tempFile);

        if (!unzipResult) return false;

        try {
            FileUtil.copyFilesInDirTo(unzippedDirPathName, context.getApplicationInfo().dataDir);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            FileUtil.deleteFile(unzippedDirPathName);
        }
    }

    public static boolean isSupportedBackupFilePostfix(String postfix) {
        return postfix.equals(BACKUP_FILE_POSTFIX);
    }

    private static void copyFileToUri(Context context, File src, Uri dstUri) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = context.getContentResolver().openOutputStream(dstUri)) {
            if (out == null) throw new IOException("Cannot open output stream for " + dstUri);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    private static void copyUriToFile(Context context, Uri srcUri, File dst) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(srcUri);
             FileOutputStream out = new FileOutputStream(dst)) {
            if (in == null) throw new IOException("Cannot open input stream for " + srcUri);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    private static String[] getBackupFilePaths(Context context) {
        String base = context.getApplicationInfo().dataDir;
        String dbDir = base + "/databases/";
        String spDir = base + "/shared_prefs/";
        String xmlPostFix = ".xml";
        List<String> list = new ArrayList<>();
        list.add(dbDir + Def.Meta.DATABASE_NAME);
        list.add(spDir + Def.Meta.META_DATA_NAME      + xmlPostFix);
        list.add(spDir + Def.Meta.THINGS_COUNTS_NAME  + xmlPostFix);
        list.add(spDir + Def.Meta.PREFERENCES_NAME    + xmlPostFix);
        list.add(spDir + Def.Meta.DOING_STRATEGY_NAME + xmlPostFix);
        return list.toArray(new String[list.size()]);
    }
}
