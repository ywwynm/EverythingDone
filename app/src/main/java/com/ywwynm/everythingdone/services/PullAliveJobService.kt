package com.ywwynm.everythingdone.services

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.os.Build
import android.util.Log

import com.ywwynm.everythingdone.helpers.AlarmHelper
import com.ywwynm.everythingdone.utils.SystemNotificationUtil

/**
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 *
 * Periodically re-creates alarms as a best-effort safety net for OEM ROMs that
 * aggressively kill background apps (EMUI / MIUI / etc). On stock Android the
 * exact alarms scheduled via [AlarmHelper] survive without help; this job
 * only meaningfully matters on the ROMs covered by the autostart hint UI.
 *
 * Android 16 (API 36) tightens JobScheduler quotas — including jobs that
 * continue after the app leaves the top state and jobs that run alongside a
 * foreground service. Treat this job as best-effort only; reminder reliability
 * comes from `setAlarmClock` + [AlarmHelper.createAllAlarms], not from this job.
 */
open class PullAliveJobService : JobService() {

    private var mWorker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        Log.i(TAG, "Pull Alive job is starting by JobScheduler.")
        // Always create a fresh Thread per job run. The previous implementation
        // cached mThread and called start() on it again on subsequent jobs,
        // which throws IllegalThreadStateException on a finished thread — so
        // every run after the first was a silent no-op.
        mWorker = Thread(object : Runnable {
            override fun run() {
                val context: Context = applicationContext
                try {
                    AlarmHelper.createAllAlarms(context, true)
                    Log.i(TAG, "Alarms set.")

                    SystemNotificationUtil.tryToCreateQuickCreateNotification(context)
                    Log.i(TAG, "Quick Create Notification created.")
                } catch (t: Throwable) {
                    Log.e(TAG, "Pull alive worker crashed", t)
                } finally {
                    if (!Thread.interrupted()) {
                        // Not asking for reschedule — the next periodic fire is
                        // already booked and immediate retry burns quota.
                        jobFinished(params, false)
                        Log.i(TAG, "Everything Done for pull alive job.")
                    }
                }
            }
        })
        mWorker!!.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.w(TAG, "Pull alive job stopped by system. stopReason=" +
                    params.stopReason
            )
        } else {
            Log.w(TAG, "Pull alive job stopped by system.")
        }
        if (mWorker != null && mWorker!!.isAlive) {
            mWorker!!.interrupt()
        }
        mWorker = null
        // Don't ask for reschedule — Android 16 enforces stricter retry quotas
        // and the next periodic fire is already booked.
        return false
    }

    companion object {
        const val TAG: String = "PullAliveJobService"
    }
}
