package com.ywwynm.everythingdone.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Environment;

import com.google.gson.Gson;
import com.orhanobut.logger.Logger;
import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.database.DoingRecordDAO;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.model.DoingRecord;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.model.HabitRecord;
import com.ywwynm.everythingdone.model.HabitReminder;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.FileUtil;

import org.joda.time.DateTime;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by ywwynm on 2016/3/20.
 * helper class used to backup and restore data
 */
public class BackupHelper {

    public static final String TAG = "BackupHelper";

    private BackupHelper() {}

    private static final boolean shouldLogJson = false;

    private static final String BACKUP_FILE_NAME   = "EverythingDone.bak";
    private static final String BACKUP_FOLDER_NAME = "EverythingDoneBackup";

    private static final String BACKUP2_DIR = "/backup";
    private static final String BACKUP2_FILE_NAME_PREFIX  = "ED_Backup_";
    private static final String BACKUP2_FILE_NAME_POSTFIX = ".bak2";

    private static final String NAME_THINGS_JSON          = "things.json";
    private static final String NAME_REMINDERS_JSON       = "reminders.json";
    private static final String NAME_HABITS_JSON          = "habits.json";
    private static final String NAME_HABIT_REMINDERS_JSON = "habitReminders.json";
    private static final String NAME_HABIT_RECORDS_JSON   = "habitRecords.json";
    private static final String NAME_DOING_RECORDS_JSON   = "doingRecords.json";

    private static final String NAME_METADATA_JSON       = "metadata.json";
    private static final String NAME_PREFERENCES_JSON    = "preferences.json";
    private static final String NAME_THINGS_COUNTS_JSON  = "things_counts.json";
    private static final String NAME_DOING_STRATEGY_JSON = "doing_strategy.json";

    public static final String SUCCESS = "success";

    @Deprecated
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
        Context context = App.getApp();
        SharedPreferences sp = context.getSharedPreferences(
                Def.Meta.META_DATA_NAME, Context.MODE_PRIVATE);
        long time = sp.getLong(Def.Meta.KEY_LAST_BACKUP_TIME, -1L);
        if (time == -1L) {
            File backupFile = new File(
                    Environment.getExternalStorageDirectory(), BACKUP_FILE_NAME);
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

    public static boolean backup2(Context context) {
        long curTime = System.currentTimeMillis();
        DateTime dt = new DateTime(curTime);
        String timeStr = dt.toString("yyyyMMddHHmmss");
        String backupDirName = BACKUP2_FILE_NAME_PREFIX + timeStr;
        File backupDir = new File(Def.Meta.APP_FILE_DIR + BACKUP2_DIR + "/" + backupDirName);
        if (!backupDir.exists()) {
            if (!backupDir.mkdirs()) {
                return false;
            }
        }

        SharedPreferences sp = context.getSharedPreferences(
                Def.Meta.META_DATA_NAME, Context.MODE_PRIVATE);
        sp.edit().putLong(Def.Meta.KEY_LAST_BACKUP_TIME, curTime).apply();

        Gson gson = new Gson();
        if (!backup2Db(context, backupDir, gson)) return false;
        if (!backup2Sp(context, backupDir, gson)) return false;

        File backupFile = new File(Def.Meta.APP_FILE_DIR + BACKUP2_DIR + "/"
                + backupDirName + BACKUP2_FILE_NAME_POSTFIX);
        if (FileUtil.zipDirectory(backupDir, backupFile) && FileUtil.deleteDirectory(backupDir)) {
            return true;
        } else {
            sp.edit().putLong(Def.Meta.KEY_LAST_BACKUP_TIME, -1L).apply();
            return false;
        }
    }

    public static boolean restore2(Context context, File backupFile) {
        backup2(context); // backup at first for data safety

        long curTime = System.currentTimeMillis();
        String unzippedDirName = Def.Meta.APP_FILE_DIR + BACKUP2_DIR + "/" + curTime;
        boolean unzipResult = FileUtil.unzip(backupFile.getAbsolutePath(), unzippedDirName);
        if (!unzipResult) return false;

        File unzippedDir = new File(unzippedDirName);
        if (!unzippedDir.exists()) return false;

        Gson gson = new Gson();
        if (!restore2Db(context, unzippedDir, gson)) return false;

        return true;
    }

    private static boolean backup2Db(Context context, File backupDir, Gson gson) {
        if (!backup2DbThings      (context, backupDir, gson)) return false;
        if (!backup2DbReminders   (context, backupDir, gson)) return false;
        if (!backup2DbHabits      (context, backupDir, gson)) return false;
        if (!backup2DbDoingRecords(context, backupDir, gson)) return false;

        return true;
    }

    private static boolean backup2DbThings(Context context, File backupDir, Gson gson) {
        ThingDAO dao = ThingDAO.getInstance(context);
        Cursor cursor = dao.getAllThingsCursor();
        List<Thing> things = new ArrayList<>();
        while (cursor.moveToNext()) {
            things.add(new Thing(cursor));
        }
        cursor.close();

        String json = gson.toJson(things);
        if (shouldLogJson) Logger.json(json);
        File file = new File(backupDir, NAME_THINGS_JSON);
        return FileUtil.writeToFile(json, file);
    }

    private static boolean backup2DbReminders(Context context, File backupDir, Gson gson) {
        ReminderDAO dao = ReminderDAO.getInstance(context);
        List<Reminder> reminders = dao.getAllReminders();
        String json = gson.toJson(reminders);
        if (shouldLogJson) Logger.json(json);
        File file = new File(backupDir, NAME_REMINDERS_JSON);
        return FileUtil.writeToFile(json, file);
    }

    private static boolean backup2DbHabits(Context context, File backupDir, Gson gson) {
        HabitDAO dao = HabitDAO.getInstance(context);
        List<Habit> habits = dao.getAllHabits();
        String json = gson.toJson(habits);
        if (shouldLogJson) Logger.json(json);
        File file = new File(backupDir, NAME_HABITS_JSON);
        if (!FileUtil.writeToFile(json, file)) {
            return false;
        }

        List<HabitReminder> habitReminders = dao.getAllHabitReminders();
        json = gson.toJson(habitReminders);
        if (shouldLogJson) Logger.json(json);
        file = new File(backupDir, NAME_HABIT_REMINDERS_JSON);
        if (!FileUtil.writeToFile(json, file)) {
            return false;
        }

        List<HabitRecord> habitRecords = dao.getAllHabitRecords();
        json = gson.toJson(habitRecords);
        if (shouldLogJson) Logger.json(json);
        file = new File(backupDir, NAME_HABIT_RECORDS_JSON);
        return FileUtil.writeToFile(json, file);
    }

    private static boolean backup2DbDoingRecords(Context context, File backupDir, Gson gson) {
        DoingRecordDAO dao = DoingRecordDAO.getInstance(context);
        List<DoingRecord> doingRecords = dao.getAllDoingRecords();
        String json = gson.toJson(doingRecords);
        if (shouldLogJson) Logger.json(json);
        File file = new File(backupDir, NAME_DOING_RECORDS_JSON);
        return FileUtil.writeToFile(json, file);
    }

    private static boolean backup2Sp(Context context, File backupDir, Gson gson) {
        if (!backup2Sp(context, backupDir, gson, Def.Meta.META_DATA_NAME, NAME_METADATA_JSON))
            return false;

        if (!backup2Sp(context, backupDir, gson, Def.Meta.PREFERENCES_NAME, NAME_PREFERENCES_JSON))
            return false;

        if (!backup2Sp(context, backupDir, gson, Def.Meta.THINGS_COUNTS_NAME, NAME_THINGS_COUNTS_JSON))
            return false;

        if (!backup2Sp(context, backupDir, gson, Def.Meta.DOING_STRATEGY_NAME, NAME_DOING_STRATEGY_JSON))
            return false;

        return true;
    }

    private static boolean backup2Sp(Context context, File backupDir, Gson gson, String spName, String fileName) {
        SharedPreferences sp = context.getSharedPreferences(spName, Context.MODE_PRIVATE);
        Map<String, ?> map = sp.getAll();
        String json = gson.toJson(map);
        if (shouldLogJson) Logger.json(json);
        File file = new File(backupDir, fileName);
        return FileUtil.writeToFile(json, file);
    }

    private static boolean restore2Db(Context context, File unzippedDir, Gson gson) {
        if (!restore2DbThings(context, unzippedDir, gson)) return false;

        return true;
    }

    private static boolean restore2DbThings(Context context, File unzippedDir, Gson gson) {
        File thingsJsonFile = new File(unzippedDir, NAME_THINGS_JSON);
        if (!thingsJsonFile.exists()) return false;

        return true;
    }

    private static boolean restore2Sp(Context context, File unzippedDir, Gson gson) {
        return true;
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