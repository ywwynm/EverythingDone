package com.ywwynm.everythingdone.helpers;

import android.app.Application;
import android.content.Context;
import android.os.Process;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.BuildConfig;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.utils.DeviceUtil;
import com.ywwynm.everythingdone.utils.FileUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Created by ywwynm on 2016/4/29.
 * helper for crash that caused by uncaught exceptions.
 */
public class CrashHelper implements Thread.UncaughtExceptionHandler {

    public static final String TAG = "CrashHelper";

    private static CrashHelper sCrashHelper;

    private Application mApplication;
    private Thread.UncaughtExceptionHandler mDefaultHandler;

    public static CrashHelper getInstance() {
        if (sCrashHelper == null) {
            synchronized (CrashHelper.class) {
                if (sCrashHelper == null) {
                    sCrashHelper = new CrashHelper();
                }
            }
        }
        return sCrashHelper;
    }

    private CrashHelper() { }

    public void init(Application application) {
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
        mApplication = application;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        saveCrashInfoToStorage(ex);
        createFileToShowFeedbackDialogNextLaunch();

        ex.printStackTrace();

        if (mDefaultHandler != null) {
            mDefaultHandler.uncaughtException(thread, ex);
        } else {
            Process.killProcess(Process.myPid());
        }
    }

    private void saveCrashInfoToStorage(Throwable ex) {
        String path = Def.Meta.APP_FILE_DIR + "/log";
        String time = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        String name = "crash_" + time + ".log";
        File file = FileUtil.createFile(path, name);
        if (file == null) {
            return;
        }

        try {
            PrintWriter writer = new PrintWriter(new FileWriter(file));
            writer.println(time);
            writer.print("APP Version:  ");
            writer.println(BuildConfig.VERSION_NAME + "_" + BuildConfig.VERSION_CODE);
            writer.println(DeviceUtil.getDeviceInfo());
            writer.println();
            ex.printStackTrace(writer);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void createFileToShowFeedbackDialogNextLaunch() {
        try {
            FileOutputStream fos = mApplication.openFileOutput(
                    Def.Meta.FEEDBACK_ERROR_FILE_NAME, Context.MODE_PRIVATE);
            fos.write(App.getApp().getString(R.string.qq_my_love).getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
