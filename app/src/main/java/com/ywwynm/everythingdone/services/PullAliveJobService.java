package com.ywwynm.everythingdone.services;

import android.annotation.TargetApi;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.ywwynm.everythingdone.helpers.AlarmHelper;
import com.ywwynm.everythingdone.utils.SystemNotificationUtil;

/**
 * Created by ywwynm on 2016/9/15.
 * This is used to create alarms so that they can still ring and not be destroyed, especially
 * on some third-party roms such as EMUI, MIUI and etc.
 * However, I don't know if this is useful, just a try.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class PullAliveJobService extends JobService {

    public static final String TAG = "PullAliveJobService";

    private Thread mThread;

    @Override
    public boolean onStartJob(final JobParameters params) {
        Log.i(TAG, "Pull Alive job is starting by JobScheduler.");
        if (mThread == null) {
            mThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Context context = getApplicationContext();

                    AlarmHelper.createAllAlarms(context, true);
                    Log.i(TAG, "Alarms set.");

                    SystemNotificationUtil.tryToCreateQuickCreateNotification(context);
                    Log.i(TAG, "Quick Create Notification created.");

                    if (!Thread.interrupted()) {
                        jobFinished(params, false);
                        Log.i(TAG, "Everything Done for pull alive job.");
                    }
                }
            });
        }
        if (!mThread.isAlive()) {
            mThread.start();
        }
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (mThread != null && !mThread.isInterrupted()) {
            mThread.interrupt();
            mThread = null;
        }
        return true;
    }
}
