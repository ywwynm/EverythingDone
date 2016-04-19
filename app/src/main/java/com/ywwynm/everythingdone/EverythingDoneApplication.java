package com.ywwynm.everythingdone;

import android.app.Application;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.os.Environment;
import android.util.LruCache;

import com.ywwynm.everythingdone.activities.SettingsActivity;
import com.ywwynm.everythingdone.activities.ThingsActivity;
import com.ywwynm.everythingdone.helpers.AppUpdateHelper;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.managers.ModeManager;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.helpers.AlarmHelper;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.utils.FileUtil;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by ywwynm on 2015/6/24.
 * Application class of EverythingDone.
 * This class has many Managers to help app control database/UI in any classes
 * having a {@link android.content.Context} member.
 */
public class EverythingDoneApplication extends Application {

    public static final String TAG = "EverythingDoneApplication";

    public static final String APP_FILE_FOLDER =
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/EverythingDone";

    private ThingManager mThingManager;

    private List<Thing> mThingsToDeleteForever;
    private List<String> mAttachmentsToDeleteFile;

    /**
     * limit stands for collection of different types of {@link Thing} that should display
     * on same UI interface. For example, {@link Thing.NOTE} and {@link Thing.WELCOME_NOTE}
     * should display on "note".
     * Value should be one of those declared in
     * {@link com.ywwynm.everythingdone.Definitions.LimitForGettingThings}
     */
    private int mLimit;

    private ModeManager mModeManager;
    public static boolean isSearching = false;

    private LruCache<String, Bitmap> mBitmapLruCache =
            new LruCache<String, Bitmap>(((int) Runtime.getRuntime().maxMemory() / 512) / 6) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };

    private ExecutorService mExecutor;

    // Used to judge if there is a ThingsActivity instance.
    public static WeakReference<ThingsActivity> thingsActivityWR;

    public static void putThingsActivityInstance(ThingsActivity thingsActivity) {
        if (thingsActivityWR == null) {
            thingsActivityWR = new WeakReference<>(thingsActivity);
        } else {
            thingsActivityWR.clear();
            thingsActivityWR = new WeakReference<>(thingsActivity);
        }
    }

    private static List<Long> runningDetailActivities = new ArrayList<>();
    private boolean detailActivityRun = false;

    private static boolean somethingUpdatedSpecially = false;
    private static boolean justNotifyDataSetChanged = false;

    @Override
    public void onCreate() {
        super.onCreate();

        firstLaunch();

        AppUpdateHelper.getInstance(this).handleAppUpdate();

        File file = new File(getApplicationInfo().dataDir + "/files/" +
                Definitions.MetaData.CREATE_ALARMS_FILE_NAME);
        if (file.exists()) {
            AlarmHelper.createAllAlarms(this, false);
            FileUtil.deleteFile(file);
        }

        mThingManager = ThingManager.getInstance(this);
        SettingsActivity.initSystemRingtoneList(this);

        mThingsToDeleteForever = new ArrayList<>();
        mAttachmentsToDeleteFile = new ArrayList<>();

        mLimit = Definitions.LimitForGettingThings.ALL_UNDERWAY;

        mExecutor = Executors.newSingleThreadExecutor();

        AlarmHelper.createDailyUpdateHabitAlarm(this);
    }

    private void firstLaunch() {
        SharedPreferences metaData = getSharedPreferences(
                Definitions.MetaData.META_DATA_NAME, MODE_PRIVATE);
        if (metaData.getLong(Definitions.MetaData.KEY_START_USING_TIME, 0) == 0) {
            metaData.edit().putLong(Definitions.MetaData.KEY_START_USING_TIME,
                    System.currentTimeMillis()).apply();
        }
    }

    public List<Thing> getThingsToDeleteForever() {
        return mThingsToDeleteForever;
    }

    public int getLimit() {
        return mLimit;
    }

    public void setLimit(int limit, boolean loadThingsNow) {
        this.mLimit = limit;
        mThingManager.setLimit(limit, loadThingsNow);
    }

    public ModeManager getModeManager() {
        return mModeManager;
    }

    public void setModeManager(ModeManager modeManager) {
        this.mModeManager = modeManager;
    }

    public LruCache<String, Bitmap> getBitmapLruCache() {
        return mBitmapLruCache;
    }

    public ExecutorService getAppExecutor() {
        return mExecutor;
    }

    public static List<Long> getRunningDetailActivities() {
        return runningDetailActivities;
    }

    public static boolean isSomethingUpdatedSpecially() {
        return somethingUpdatedSpecially;
    }

    public static void setSomethingUpdatedSpecially(boolean somethingUpdatedSpecially) {
        EverythingDoneApplication.somethingUpdatedSpecially = somethingUpdatedSpecially;
    }

    public static boolean justNotifyDataSetChanged() {
        return justNotifyDataSetChanged;
    }

    public static void setShouldJustNotifyDataSetChanged(boolean justNotifyDataSetChanged) {
        EverythingDoneApplication.justNotifyDataSetChanged = justNotifyDataSetChanged;
    }

    public void setDetailActivityRun(boolean detailActivityRun) {
        this.detailActivityRun = detailActivityRun;
    }

    public boolean hasDetailActivityRun() {
        return detailActivityRun;
    }

    public void releaseResourcesAfterDeleteForever() {
        if (!mThingsToDeleteForever.isEmpty()) {
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    ReminderDAO dao = ReminderDAO.getInstance(EverythingDoneApplication.this);
                    for (Thing thing : mThingsToDeleteForever) {
                        String attachment = thing.getAttachment();
                        if (!attachment.isEmpty() && !attachment.equals("to QQ")) {
                            String[] attachments = attachment.split(AttachmentHelper.SIGNAL);
                            for (int i = 1; i < attachments.length; i++) {
                                String pathName = attachments[i].substring(1, attachments[i].length());
                                if (pathName.startsWith(APP_FILE_FOLDER)
                                        && !mAttachmentsToDeleteFile.contains(pathName)) {
                                    mAttachmentsToDeleteFile.add(pathName);
                                }
                            }
                        }
                        dao.delete(thing.getId());
                    }
                    mThingsToDeleteForever.clear();
                }
            };
            mExecutor.execute(r);
        }
    }

    public void addAttachmentsToDeleteFile(List<String> attachments) {
        for (String attachment : attachments) {
            if (!mAttachmentsToDeleteFile.contains(attachment)) {
                mAttachmentsToDeleteFile.add(attachment);
            }
        }
    }

    public void deleteAttachmentFiles() {
        if (!mAttachmentsToDeleteFile.isEmpty()) {
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    List<String> usedAttachments = new ArrayList<>();
                    ThingDAO dao = ThingDAO.getInstance(EverythingDoneApplication.this);
                    Cursor cursor = dao.getAllThingsCursor();
                    while (cursor.moveToNext()) {
                        String attachment = cursor.getString(cursor.getColumnIndex(
                                Definitions.Database.COLUMN_ATTACHMENT_THINGS));
                        if (!attachment.isEmpty() && !attachment.equals("to QQ")) {
                            String[] attachments = attachment.split(AttachmentHelper.SIGNAL);
                            for (int i = 1; i < attachments.length; i++) {
                                String pathName = attachments[i].substring(
                                        1, attachments[i].length());
                                if (pathName.startsWith(APP_FILE_FOLDER)
                                        && !usedAttachments.contains(pathName)) {
                                    usedAttachments.add(pathName);
                                }
                            }
                        }
                    }
                    cursor.close();
                    for (String path : mAttachmentsToDeleteFile) {
                        if (!usedAttachments.contains(path)) {
                            FileUtil.deleteFile(path);
                        }
                    }
                    mAttachmentsToDeleteFile.clear();
                }
            };
            mExecutor.execute(r);
        }
    }
}
