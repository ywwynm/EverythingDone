package com.ywwynm.everythingdone;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.util.Pair;

import com.bumptech.glide.Glide;
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
import com.ywwynm.everythingdone.services.PullAliveJobService;
import com.ywwynm.everythingdone.utils.DeviceUtil;
import com.ywwynm.everythingdone.utils.DisplayUtil;
import com.ywwynm.everythingdone.utils.FileUtil;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;

import java.io.File;
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

        /*
            Force initialization within app's domain.
            If this line was removed, things list widget may not load images because of checking
            network state without corresponding permission.
            {@see https://github.com/bumptech/glide/issues/1405} for more details.
         */
        Glide.with(this);

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

        SystemNotificationUtil.tryToCreateQuickCreateNotification(this);

        mThingsToDeleteForever   = new ArrayList<>();
        mAttachmentsToDeleteFile = new ArrayList<>();

        mLimit = Def.LimitForGettingThings.ALL_UNDERWAY;

        mExecutor = Executors.newSingleThreadExecutor();

        AlarmHelper.createDailyUpdateHabitAlarm(this);

        if (DeviceUtil.hasLollipopApi()) {
            startPullAliveJob();
        }
    }

    private void firstLaunch() {
        SharedPreferences metaData = getSharedPreferences(
                Def.Meta.META_DATA_NAME, MODE_PRIVATE);
        if (metaData.getLong(Def.Meta.KEY_START_USING_TIME, 0) == 0) {
            metaData.edit().putLong(Def.Meta.KEY_START_USING_TIME,
                    System.currentTimeMillis()).apply();
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void startPullAliveJob() {
        ComponentName componentName = new ComponentName(this, PullAliveJobService.class);
        JobInfo.Builder builder = new JobInfo.Builder(Integer.MAX_VALUE, componentName);
        //builder.setPeriodic(10 * 1000);
        builder.setPeriodic(30 * 60 * 1000); // half an hour
        builder.setPersisted(true);
        JobScheduler jobScheduler = (JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
        jobScheduler.schedule(builder.build());
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
            Runnable r;
            r = new Runnable() {
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
            Runnable r;
            r = new Runnable() {
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

    public static void killMeAndRestart(Context context, Class toLaunch, long time) {
        Intent intent;
        if (toLaunch == null) {
            intent = context.getPackageManager().getLaunchIntentForPackage(
                    context.getPackageName());
        } else {
            intent = new Intent(context, toLaunch);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(context,
                0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + time + 100, pendingIntent);
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                System.exit(0);
            }
        }, time);
    }

    public static Pair<Thing, Integer> getThingAndPosition(Context context, long id, int knownPos) {
        ThingManager thingManager = ThingManager.getInstance(context);
        ThingDAO thingDAO = ThingDAO.getInstance(context);
        Thing thing = null;
        int correctPos = knownPos;
        if (knownPos == -1) {
            correctPos = thingManager.getPosition(id);
            if (correctPos == -1) {
                thing = thingDAO.getThingById(id);
            } else {
                thing = thingManager.getThings().get(correctPos);
            }
        } else {
            List<Thing> things = thingManager.getThings();
            final int size = things.size();
            if (knownPos >= size || things.get(knownPos).getId() != id) {
                for (int i = 0; i < size; i++) {
                    Thing temp = things.get(i);
                    if (temp.getId() == id) {
                        thing = temp;
                        correctPos = i;
                        break;
                    }
                }
                if (thing == null) {
                    thing = thingDAO.getThingById(id);
                    correctPos = -1;
                }
            } else {
                thing = things.get(knownPos);
            }
        }
        return new Pair<>(thing, correctPos);
    }

}
