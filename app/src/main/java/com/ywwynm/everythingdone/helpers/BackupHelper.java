package com.ywwynm.everythingdone.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.FileUtil;

import org.joda.time.DateTime;

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

    private static final String BACKUP_FILE_NAME_OLD = "EverythingDone.bak";
    private static final String BACKUP_FILE_POSTFIX = "bak";

    private static final String BACKUP_DIR = "/backup";
    private static final String BACKUP_FILE_NAME_PREFIX = "ED_backup_";

    public static boolean backup(Context context) {
        /*
            Before 1.3.7(40), we backup data dir except for some files.
            After 1.3.7, we backup data dir only for some files.
         */
        File src = new File(context.getApplicationInfo().dataDir);
        String backupDirPath = Def.Meta.APP_FILE_DIR + BACKUP_DIR;
        long curTime = System.currentTimeMillis();
        DateTime dt = new DateTime(curTime);
        String timeStr = dt.toString("yyyyMMddHHmmss");
        String backupFileName = BACKUP_FILE_NAME_PREFIX + timeStr + "." + BACKUP_FILE_POSTFIX;
        File dst = FileUtil.createFile(backupDirPath, backupFileName);

        if (FileUtil.zipDirectory(src, dst, false, getBackupFilePaths(context))) {
            SharedPreferences sp = context.getSharedPreferences(
                    Def.Meta.META_DATA_NAME, Context.MODE_PRIVATE);
            sp.edit().putLong(Def.Meta.KEY_LAST_BACKUP_TIME, curTime).apply();
            return true;
        } else return false;
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

    public static boolean restore(Context context, File backupFile) {
        long curTime = System.currentTimeMillis();
        String unzippedDirPathName = Def.Meta.APP_FILE_DIR + BACKUP_DIR + "/" + curTime;
        boolean unzipResult = FileUtil.unzip(backupFile.getAbsolutePath(), unzippedDirPathName);
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