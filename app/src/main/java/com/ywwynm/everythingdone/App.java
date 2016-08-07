package com.ywwynm.everythingdone;

import android.app.Application;
import android.content.SharedPreferences;
import android.database.Cursor;

import com.ywwynm.everythingdone.activities.SettingsActivity;
import com.ywwynm.everythingdone.activities.ThingsActivity;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.helpers.AlarmHelper;
import com.ywwynm.everythingdone.helpers.AppUpdateHelper;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.helpers.CrashHelper;
import com.ywwynm.everythingdone.helpers.FingerprintHelper;
import com.ywwynm.everythingdone.managers.ModeManager;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.utils.DeviceUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.FileUtil;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;

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
public class App extends Application {

    public static final String TAG = "EverythingDone";

    private static App app;

    private ThingManager mThingManager;

    private List<Thing> mThingsToDeleteForever;
    private List<String> mAttachmentsToDeleteFile;

    /**
     * limit stands for collection of different types of {@link Thing} that should display
     * on same UI interface. For example, {@link Thing#NOTE} and {@link Thing#WELCOME_NOTE}
     * should display on "note".
     * Value should be one of those declared in
     * {@link Def.LimitForGettingThings}
     */
    private int mLimit;

    private ModeManager mModeManager;
    public static boolean isSearching = false;

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

    public static int newThingColor;

    @Override
    public void onCreate() {
        super.onCreate();

        app = this;

        CrashHelper.getInstance().init(this);

        newThingColor = DisplayUtil.getRandomColor(this);

        firstLaunch();

        AppUpdateHelper.getInstance(this).handleAppUpdate();

        File file = new File(getApplicationInfo().dataDir + "/files/" +
                Def.Meta.RESTORE_DONE_FILE_NAME);
        if (file.exists()) {
            AlarmHelper.createAllAlarms(this, false);
            if (DeviceUtil.hasMarshmallowApi()) {
                FingerprintHelper fingerprintHelper = FingerprintHelper.getInstance();
                if (fingerprintHelper.isFingerprintEnabledInEverythingDone()
                        && fingerprintHelper.isFingerprintReady()) {
                /*
                    Fix bug: if restored, fingerprint will not work unless user disable/enable
                    fingerprint in SettingsActivity
                 */
                    fingerprintHelper.createFingerprintKeyForEverythingDone();
                }
            }
            FileUtil.deleteFile(file);
        }

        mThingManager = ThingManager.getInstance(this);
        SettingsActivity.initSystemRingtoneList();

        SystemNotificationUtil.tryToCreateQuickCreateNotification(this);

        mThingsToDeleteForever = new ArrayList<>();
        mAttachmentsToDeleteFile = new ArrayList<>();

        mLimit = Def.LimitForGettingThings.ALL_UNDERWAY;

        mExecutor = Executors.newSingleThreadExecutor();

        AlarmHelper.createDailyUpdateHabitAlarm(this);
    }

    private void firstLaunch() {
        SharedPreferences metaData = getSharedPreferences(
                Def.Meta.META_DATA_NAME, MODE_PRIVATE);
        if (metaData.getLong(Def.Meta.KEY_START_USING_TIME, 0) == 0) {
            metaData.edit().putLong(Def.Meta.KEY_START_USING_TIME,
                    System.currentTimeMillis()).apply();
        }
    }

    public static App getApp() {
        return app;
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

    public static List<Long> getRunningDetailActivities() {
        return runningDetailActivities;
    }

    public static boolean isSomethingUpdatedSpecially() {
        return somethingUpdatedSpecially;
    }

    public static void setSomethingUpdatedSpecially(boolean somethingUpdatedSpecially) {
        App.somethingUpdatedSpecially = somethingUpdatedSpecially;
    }

    public static boolean justNotifyDataSetChanged() {
        return justNotifyDataSetChanged;
    }

    public static void setShouldJustNotifyDataSetChanged(boolean justNotifyDataSetChanged) {
        App.justNotifyDataSetChanged = justNotifyDataSetChanged;
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
                    String appDir = Def.Meta.APP_FILE_DIR;
                    ReminderDAO dao = ReminderDAO.getInstance(App.this);
                    for (Thing thing : mThingsToDeleteForever) {
                        String attachment = thing.getAttachment();
                        if (AttachmentHelper.isValidForm(attachment)) {
                            String[] attachments = attachment.split(AttachmentHelper.SIGNAL);
                            for (int i = 1; i < attachments.length; i++) {
                                String pathName = attachments[i].substring(1, attachments[i].length());
                                if (pathName.startsWith(appDir)
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
                    String appDir = Def.Meta.APP_FILE_DIR;
                    List<String> usedAttachments = new ArrayList<>();
                    ThingDAO dao = ThingDAO.getInstance(App.this);
                    Cursor cursor = dao.getAllThingsCursor();
                    while (cursor.moveToNext()) {
                        String attachment = cursor.getString(cursor.getColumnIndex(
                                Def.Database.COLUMN_ATTACHMENT_THINGS));
                        if (AttachmentHelper.isValidForm(attachment)) {
                            String[] attachments = attachment.split(AttachmentHelper.SIGNAL);
                            for (int i = 1; i < attachments.length; i++) {
                                String pathName = attachments[i].substring(
                                        1, attachments[i].length());
                                if (pathName.startsWith(appDir)
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
