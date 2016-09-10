package com.ywwynm.everythingdone.helpers;

import android.content.Context;
import android.os.Environment;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.FileUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by ywwynm on 2016/3/20.
 * helper class used to backup and restore data
 */
public class BackupHelper {

    public static final String TAG = "BackupHelper";

    private BackupHelper() {}

    private static final String BACKUP_FILE_NAME   = "EverythingDone.bak";
    private static final String BACKUP_FOLDER_NAME = "EverythingDoneBackup";

    public static final String SUCCESS = "success";

    public static boolean backup(Context context) {
        File src = new File(context.getApplicationInfo().dataDir);
        File dst = FileUtil.createFile(
                Environment.getExternalStorageDirectory().getAbsolutePath(), BACKUP_FILE_NAME);
        return FileUtil.zipDirectory(src, dst, getExcludePaths(context));
    }

    public static String restore(Context context) {
        String sdcard = Environment.getExternalStorageDirectory().getAbsolutePath();
        File bakFile = new File(sdcard, BACKUP_FILE_NAME);
        if (!bakFile.exists()) {
            return context.getString(R.string.restore_file_not_exist);
        }
        boolean success = FileUtil.unzip(bakFile.getAbsolutePath(),
                sdcard + File.separator + BACKUP_FOLDER_NAME);
        String failed = context.getString(R.string.restore_failed_other);
        if (success) {
            try {
                FileUtil.copyDirectory(sdcard + File.separator + BACKUP_FOLDER_NAME,
                        context.getApplicationInfo().dataDir);
                FileUtil.deleteFile(sdcard + File.separator + BACKUP_FOLDER_NAME);
                return SUCCESS;
            } catch (IOException e) {
                e.printStackTrace();
                return failed;
            }
        } else {
            return failed;
        }
    }

    public static String getLastBackupTimeString() {
        File backupFile = new File(
                Environment.getExternalStorageDirectory(), BACKUP_FILE_NAME);
        Context context = App.getApp();
        if (backupFile.exists()) {
            StringBuilder sb = new StringBuilder(context.getString(R.string.last_backup));
            long time = backupFile.lastModified();
            sb.append(" ").append(DateTimeUtil.getDateTimeStrAt(time, context, false));
            return sb.toString();
        } else {
            return context.getString(R.string.no_backup_before);
        }
    }

    private static String[] getExcludePaths(Context context) {
        String base = context.getApplicationInfo().dataDir;
        List<String> list = new ArrayList<>();
        list.add(base + "/cache");
        list.add(base + "/code_cache");
        list.add(base + "/app_webview");
        list.add(base + "/files/instant-run");
        list.add(base + "/shared_prefs/WebViewChromiumPrefs.xml");
        list.add(base + "/lib");
        final int size = list.size();
        String[] arr = new String[size];
        for (int i = 0; i < size; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }
}