package com.ywwynm.everythingdone.services;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.ywwynm.everythingdone.helpers.AlarmHelper;

import java.util.concurrent.TimeUnit;

/**
 * Periodic safety net that re-creates every active alarm. Complements
 * {@link PullAliveJobService} (legacy 30-min JobScheduler entry) with the
 * AndroidX way of doing the same thing — WorkManager has its own persistence
 * layer separate from JobScheduler / AlarmManager and is what Google
 * recommends for deferrable background work on modern Android.
 *
 * <p>Why both? AlarmHelper.setAlarmClock is the primary delivery mechanism
 * and survives Doze / battery savers. The two health-check layers exist for
 * the rare cases that drop alarm registrations entirely:
 * <ul>
 *     <li>force-stop (Settings → Force stop, "彻底清理" on aggressive ROMs)</li>
 *     <li>OEM background kills that bypass the standard alarm guarantees</li>
 * </ul>
 * They are idempotent — running them on a healthy schedule simply re-registers
 * the same alarms with the same trigger times, so the cost is a few
 * AlarmManager API calls every 4 hours.
 */
public class AlarmHealthWorker extends Worker {

    public static final String TAG = "AlarmHealthWorker";

    private static final String UNIQUE_WORK_NAME = "alarm-health-check";

    public AlarmHealthWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            AlarmHelper.createAllAlarms(getApplicationContext(), false);
            Log.i(TAG, "Alarm health check rebuilt all alarms.");
            return Result.success();
        } catch (Throwable t) {
            Log.e(TAG, "Alarm health check failed", t);
            return Result.retry();
        }
    }

    /**
     * Idempotently schedule the periodic worker. Safe to call on every
     * App.onCreate — KEEP policy preserves an already-scheduled instance
     * rather than restarting its interval.
     */
    public static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                AlarmHealthWorker.class, 4, TimeUnit.HOURS)
                .addTag(UNIQUE_WORK_NAME)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request);
    }
}
