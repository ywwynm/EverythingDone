package com.ywwynm.everythingdone;

import android.app.Application;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.os.Environment;
import android.util.LruCache;

import com.ywwynm.everythingdone.activities.SettingsActivity;
import com.ywwynm.everythingdone.bean.Thing;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.managers.ModeManager;
import com.ywwynm.everythingdone.managers.ThingManager;
import com.ywwynm.everythingdone.helpers.AlarmHelper;
import com.ywwynm.everythingdone.helpers.AttachmentHelper;
import com.ywwynm.everythingdone.utils.FileUtil;

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
    public static boolean thingsActivityCreated = false;

    private LruCache<String, Bitmap> mBitmapLruCache =
            new LruCache<String, Bitmap>(((int) Runtime.getRuntime().maxMemory() / 512) / 6) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };

    private ExecutorService mExecutor;

    private static List<Long> runningDetailActivities = new ArrayList<>();
    private static boolean somethingUpdatedSpecially = false;
    private static boolean justNotifyDataSetChanged = false;

    @Override
    public void onCreate() {
        super.onCreate();

        File file = new File(getApplicationInfo().dataDir + "/files/" +
                Definitions.MetaData.CREATE_ALARMS_FILE_NAME);
        if (file.exists()) {
            AlarmHelper.createAllAlarms(this);
            FileUtil.deleteFile(file);
        }

        mThingManager = ThingManager.getInstance(this);
        SettingsActivity.initSystemRingtoneList(this);

        mThingsToDeleteForever = new ArrayList<>();
        mAttachmentsToDeleteFile = new ArrayList<>();

        mLimit = Definitions.LimitForGettingThings.ALL_UNDERWAY;

        mExecutor = Executors.newSingleThreadExecutor();
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

    public void releaseResourcesAfterDeleteForever() {
        if (!mThingsToDeleteForever.isEmpty()) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    ReminderDAO dao = ReminderDAO.getInstance(EverythingDoneApplication.this);
                    String attachment, pathName;
                    String[] attachments;
                    for (Thing thing : mThingsToDeleteForever) {
                        attachment = thing.getAttachment();
                        if (!attachment.isEmpty() && !attachment.equals("to QQ")) {
                            attachments = attachment.split(AttachmentHelper.SIGNAL);
                            for (int i = 1; i < attachments.length; i++) {
                                pathName = attachments[i].substring(1, attachments[i].length());
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
            });
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
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    List<String> usedAttachments = new ArrayList<>();
                    String attachment, pathName;
                    String[] attachments;
                    ThingDAO dao = ThingDAO.getInstance(EverythingDoneApplication.this);
                    Cursor cursor = dao.getAllThingsCursor();
                    while (cursor.moveToNext()) {
                        attachment = cursor.getString(cursor.getColumnIndex(
                                Definitions.Database.COLUMN_ATTACHMENT_THINGS));
                        if (!attachment.isEmpty() && !attachment.equals("to QQ")) {
                            attachments = attachment.split(AttachmentHelper.SIGNAL);
                            for (int i = 1; i < attachments.length; i++) {
                                pathName = attachments[i].substring(1, attachments[i].length());
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
            });
        }
    }
}
