package com.ywwynm.everythingdone.services

import android.content.Context
import android.util.Log

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

import com.ywwynm.everythingdone.helpers.AlarmHelper

import java.util.concurrent.TimeUnit

/**
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 *
 * Periodic safety net that re-creates every active alarm. Complements
 * [PullAliveJobService] (legacy 30-min JobScheduler entry) with the
 * AndroidX way of doing the same thing — WorkManager has its own persistence
 * layer separate from JobScheduler / AlarmManager and is what Google
 * recommends for deferrable background work on modern Android.
 *
 * Why both? AlarmHelper.setAlarmClock is the primary delivery mechanism
 * and survives Doze / battery savers. The two health-check layers exist for
 * the rare cases that drop alarm registrations entirely:
 * - force-stop (Settings → Force stop, "彻底清理" on aggressive ROMs)
 * - OEM background kills that bypass the standard alarm guarantees
 *
 * They are idempotent — running them on a healthy schedule simply re-registers
 * the same alarms with the same trigger times, so the cost is a few
 * AlarmManager API calls every 4 hours.
 */
open class AlarmHealthWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        try {
            AlarmHelper.createAllAlarms(getApplicationContext(), false)
            Log.i(TAG, "Alarm health check rebuilt all alarms.")
            return Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Alarm health check failed", t)
            return Result.retry()
        }
    }

    companion object {
        const val TAG: String = "AlarmHealthWorker"

        private const val UNIQUE_WORK_NAME: String = "alarm-health-check"

        /**
         * Idempotently schedule the periodic worker. Safe to call on every
         * App.onCreate — KEEP policy preserves an already-scheduled instance
         * rather than restarting its interval.
         */
        @JvmStatic
        fun schedule(context: Context?) {
            val request: PeriodicWorkRequest = PeriodicWorkRequest.Builder(
                    AlarmHealthWorker::class.java, 4, TimeUnit.HOURS)
                    .addTag(UNIQUE_WORK_NAME)
                    .build()
            WorkManager.getInstance(context!!).enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request)
        }
    }
}
