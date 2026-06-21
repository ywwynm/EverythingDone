@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.core.util.Pair
import android.util.Log


import com.ywwynm.everythingdone.database.ReminderDAO
import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.helpers.AlarmHelper
import com.ywwynm.everythingdone.helpers.AppUpdateHelper
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.helpers.CrashHelper
import com.ywwynm.everythingdone.helpers.FingerprintHelper
import com.ywwynm.everythingdone.managers.ThingManager
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.model.ThingListProjection
import com.ywwynm.everythingdone.services.AlarmHealthWorker
import com.ywwynm.everythingdone.services.PullAliveJobService
import com.ywwynm.everythingdone.utils.FileUtil
import com.ywwynm.everythingdone.utils.AppearanceUtil
import com.ywwynm.everythingdone.utils.LocaleUtil
import com.ywwynm.everythingdone.utils.SystemNotificationUtil

import java.io.File
import java.util.ArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Created by ywwynm on 2015/6/24.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Application class of EverythingDone.
 * This class has many Managers to help app control database/UI in any classes
 * having a [android.content.Context] member.
 */
open class App : Application() {

    private var mThingManager: ThingManager? = null

    private var mThingsToDeleteForever: MutableList<Thing?>? = null
    private var mAttachmentsToDeleteFile: MutableList<String?>? = null

    /**
     * Current status projection shown by the main Things UI.
     */
    private var mStatus: Int = Def.ThingStatus.UNDERWAY

    private var mExecutor: ExecutorService? = null

    private var detailActivityRun: Boolean = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleUtil.getContextForLanguage(base))
    }

    override fun onCreate() {
        super.onCreate()

        app = this
        AppearanceUtil.applyDefaultNightMode()

        createNotificationChannels()

        CrashHelper.getInstance()!!.init(this)

        firstLaunch()

        AppUpdateHelper.getInstance(this)!!.handleAppUpdate()

        val file = File(filesDir, Def.Meta.RESTORE_DONE_FILE_NAME)
        if (file.exists()) {
            AlarmHelper.createAllAlarms(this, false)
            val fingerprintHelper: FingerprintHelper = FingerprintHelper.getInstance()!!
            if (fingerprintHelper.isFingerprintEnabledInEverythingDone()
                    && fingerprintHelper.isFingerprintReady()) {
                fingerprintHelper.createFingerprintKeyForEverythingDone()
            }
            FileUtil.deleteFile(file)
        }

        mThingManager = ThingManager.getInstance(this)

        SystemNotificationUtil.tryToCreateQuickCreateNotification(this)
        SystemNotificationUtil.tryToCreateThingOngoingNotification(this)

        mThingsToDeleteForever   = ArrayList()
        mAttachmentsToDeleteFile = ArrayList()

        mStatus = Def.ThingStatus.UNDERWAY

        updateNewThingColor()

        mExecutor = Executors.newSingleThreadExecutor()

        AlarmHelper.createDailyUpdateHabitAlarm(this)

        startPullAliveJob()
        AlarmHealthWorker.schedule(this)

        selfHealAlarmsIfStale()
    }

    /**
     * Recover alarms after the app is force-stopped (system Settings, "彻底清理"
     * on aggressive ROMs, etc) — those code paths cancel every alarm registered
     * by this package without notice. The DB is intact, so we just rebuild from
     * it. Throttled to [ALARM_SELF_HEAL_INTERVAL_MS] via SharedPreferences
     * so normal launches don't burn cycles, and run on a worker thread so it
     * doesn't slow down cold starts.
     */
    private fun selfHealAlarmsIfStale() {
        val sp: SharedPreferences = getSharedPreferences(
                Def.Meta.PREFERENCES_NAME, MODE_PRIVATE)
        val lastRebuild: Long = sp.getLong(Def.Meta.KEY_LAST_ALARM_REBUILD, 0L)
        val now: Long = System.currentTimeMillis()
        if (now - lastRebuild < ALARM_SELF_HEAL_INTERVAL_MS) {
            return
        }
        Log.i(TAG, "Self-healing alarms (last rebuild " +
                (if (lastRebuild == 0L) "never" else ((now - lastRebuild) / 60000).toString() + " min ago") + ")")
        Thread({
            try {
                AlarmHelper.createAllAlarms(this@App, false)
                sp.edit().putLong(Def.Meta.KEY_LAST_ALARM_REBUILD,
                        System.currentTimeMillis()).apply()
            } catch (t: Throwable) {
                Log.e(TAG, "Self-heal alarm rebuild failed", t)
            }
        }, "alarm-self-heal").start()
    }

    private fun createNotificationChannels() {
        val nm: NotificationManager = getSystemService(NotificationManager::class.java)

        val reminderChannel = NotificationChannel(
                "reminder", getString(R.string.channel_reminder),
                NotificationManager.IMPORTANCE_HIGH)
        reminderChannel.enableVibration(true)
        reminderChannel.enableLights(true)

        val habitChannel = NotificationChannel(
                "habit", getString(R.string.channel_habit),
                NotificationManager.IMPORTANCE_HIGH)
        habitChannel.enableVibration(true)
        habitChannel.enableLights(true)

        val goalChannel = NotificationChannel(
                "goal", getString(R.string.channel_goal),
                NotificationManager.IMPORTANCE_HIGH)
        goalChannel.enableVibration(true)
        goalChannel.enableLights(true)

        val doingChannel = NotificationChannel(
                "doing", getString(R.string.channel_doing),
                NotificationManager.IMPORTANCE_LOW)

        val quickCreateChannel = NotificationChannel(
                "quick_create", getString(R.string.channel_quick_create),
                NotificationManager.IMPORTANCE_MIN)

        val ongoingChannel = NotificationChannel(
                "ongoing", getString(R.string.channel_ongoing),
                NotificationManager.IMPORTANCE_LOW)

        val autoNotifyChannel = NotificationChannel(
                "auto_notify", getString(R.string.channel_auto_notify),
                NotificationManager.IMPORTANCE_DEFAULT)

        nm.createNotificationChannels(
            listOf(
                reminderChannel, habitChannel, goalChannel,
                doingChannel, quickCreateChannel, ongoingChannel,
                autoNotifyChannel
            )
        )
    }

    private fun firstLaunch() {
        val metaData: SharedPreferences = getSharedPreferences(
                Def.Meta.META_DATA_NAME, MODE_PRIVATE)
        if (metaData.getLong(Def.Meta.KEY_START_USING_TIME, 0) == 0L) {
            metaData.edit().putLong(Def.Meta.KEY_START_USING_TIME,
                    System.currentTimeMillis()).apply()
        }
    }

    private fun startPullAliveJob() {
        val componentName = ComponentName(this, PullAliveJobService::class.java)
        val builder: JobInfo.Builder = JobInfo.Builder(Int.MAX_VALUE, componentName)
        //builder.setPeriodic(10 * 1000);
        builder.setPeriodic((30 * 60 * 1000).toLong()) // half an hour
        builder.setPersisted(true)
        val jobScheduler: JobScheduler = getSystemService(JOB_SCHEDULER_SERVICE) as JobScheduler
        jobScheduler.schedule(builder.build())
    }

    open fun getThingsToDeleteForever(): MutableList<Thing?>? {
        return mThingsToDeleteForever
    }

    open fun getStatus(): Int = mStatus

    open fun setStatus(status: Int, loadThingsNow: Boolean) {
        val normalizedStatus = ThingListProjection.normalizeStatus(status)
        mStatus = normalizedStatus
        mThingManager!!.setStatus(normalizedStatus, loadThingsNow)
    }

    open fun setDetailActivityRun(detailActivityRun: Boolean) {
        this.detailActivityRun = detailActivityRun
    }

    open fun hasDetailActivityRun(): Boolean {
        return detailActivityRun
    }

    open fun releaseResourcesAfterDeleteForever() {
        if (!mThingsToDeleteForever!!.isEmpty()) {
            val r = Runnable {
                val appDir: String = Def.getAppFileDir(this@App)!!
                val oldAppDir: String = Environment.getExternalStorageDirectory().absolutePath + "/EverythingDone"
                val dao: ReminderDAO = ReminderDAO.getInstance(this@App)!!
                    for (thing in mThingsToDeleteForever!!) {
                        val attachment: String = thing!!.attachment!!
                        if (AttachmentHelper.isValidForm(attachment)) {
                            val attachments: Array<String> = attachment.split(AttachmentHelper.SIGNAL.toRegex()).toTypedArray()
                            for (i in 1 until attachments.size) {
                                val pathName: String = attachments[i].substring(1, attachments[i].length)
                                if ((pathName.startsWith(appDir) || pathName.startsWith(oldAppDir))
                                        && !mAttachmentsToDeleteFile!!.contains(pathName)) {
                                    mAttachmentsToDeleteFile!!.add(pathName)
                                }
                            }
                        }
                        dao.delete(thing.id)
                    }
                    mThingsToDeleteForever!!.clear()
            }
            mExecutor!!.execute(r)
        }
    }

    open fun addAttachmentsToDeleteFile(attachments: List<String?>?) {
        for (attachment in attachments!!) {
            if (!mAttachmentsToDeleteFile!!.contains(attachment)) {
                mAttachmentsToDeleteFile!!.add(attachment)
            }
        }
    }

    open fun deleteAttachmentFiles() {
        if (!mAttachmentsToDeleteFile!!.isEmpty()) {
            val r = Runnable {
                val appDir: String = Def.getAppFileDir(this@App)!!
                val oldAppDir: String = Environment.getExternalStorageDirectory().absolutePath + "/EverythingDone"
                val usedAttachments: MutableList<String?> = ArrayList()
                val dao: ThingDAO = ThingDAO.getInstance(this@App)!!
                    val cursor: Cursor = dao.getAllThingsCursor()!!
                    while (cursor.moveToNext()) {
                        val attachment: String = cursor.getString(cursor.getColumnIndexOrThrow(
                                Def.Database.COLUMN_ATTACHMENT_THINGS))
                        if (AttachmentHelper.isValidForm(attachment)) {
                            val attachments: Array<String> = attachment.split(AttachmentHelper.SIGNAL.toRegex()).toTypedArray()
                            for (i in 1 until attachments.size) {
                                val pathName: String = attachments[i].substring(
                                        1, attachments[i].length)
                                if ((pathName.startsWith(appDir) || pathName.startsWith(oldAppDir))
                                        && !usedAttachments.contains(pathName)) {
                                    usedAttachments.add(pathName)
                                }
                            }
                        }
                    }
                    cursor.close()
                    for (path in mAttachmentsToDeleteFile!!) {
                        if (!usedAttachments.contains(path)) {
                            FileUtil.deleteFile(path)
                        }
                    }
                    mAttachmentsToDeleteFile!!.clear()
            }
            mExecutor!!.execute(r)
        }
    }

    companion object {
        const val TAG: String = "EverythingDone"

        private var app: App? = null

        @JvmField
        var isSearching: Boolean = false

        @JvmField
        var runningDetailActivities: MutableList<Long?> = ArrayList()

        private var visibleDetailActivities: MutableList<Long?> = ArrayList()

        private var somethingUpdatedSpecially: Boolean = false
        private var justNotifyAll: Boolean = false

        /**
         * The randomly-chosen background for the next new thing the user creates.
         *
         * Phase 2 of the color-system migration introduces this [ThingBackground]
         * field alongside the legacy [newThingColor] int (kept in sync). Phase 3
         * will switch new-thing creation to draw from this field; Phase 4 will let it
         * randomly be a GRADIENT background.
         */
        @JvmField
        var newThingBackground: ThingBackground? = null

        /**
         * Legacy single-int companion of [newThingBackground] — kept in sync
         * as `newThingBackground.representativeColor()` so existing call sites
         * that just need an int (intent extras, FAB ripple, etc.) keep working
         * unchanged through Phase 2 / 3.
         */
        @JvmField
        var newThingColor: Int = 0

        private var doingThingId: Long = -1

        private const val ALARM_SELF_HEAL_INTERVAL_MS: Long = 6L * 60 * 60 * 1000

        @JvmStatic
        fun getApp(): App? {
            return app
        }

        @JvmStatic
        fun getRunningDetailActivities(): MutableList<Long?> {
            return runningDetailActivities
        }

        @JvmStatic
        fun setDetailActivityVisible(id: Long, visible: Boolean) {
            visibleDetailActivities.remove(id)
            if (visible) {
                visibleDetailActivities.add(id)
            }
        }

        @JvmStatic
        fun isDetailActivityVisible(id: Long): Boolean {
            return visibleDetailActivities.contains(id)
        }

        @JvmStatic
        fun isSomethingUpdatedSpecially(): Boolean {
            return somethingUpdatedSpecially
        }

        @JvmStatic
        fun setSomethingUpdatedSpecially(somethingUpdatedSpecially: Boolean) {
            this.somethingUpdatedSpecially = somethingUpdatedSpecially
        }

        @JvmStatic
        fun justNotifyAll(): Boolean {
            return justNotifyAll
        }

        @JvmStatic
        fun setJustNotifyAll(justNotifyAll: Boolean) {
            this.justNotifyAll = justNotifyAll
        }

        private var sLastUpdateUiIntent: Intent? = null

        @JvmStatic
        fun setLastUpdateUiIntent(lastUpdateUiIntent: Intent?) {
            sLastUpdateUiIntent = lastUpdateUiIntent
        }

        @JvmStatic
        fun tryToSetNotifyAllToTrue(thing: Thing?, resultCode: Int) {
            if (shouldSetNotifyAllToTrue(thing, resultCode)) {
                justNotifyAll = true
            }
        }

        private fun shouldSetNotifyAllToTrue(thing: Thing?, resultCode: Int): Boolean {
            if (sLastUpdateUiIntent == null) {
                Log.i(TAG, "should set notifyAll to true because sLastUpdateUiIntent is null")
                return true
            }

            val thingBefore: Thing? = sLastUpdateUiIntent!!.getParcelableExtra(Def.Communication.KEY_THING)
            if (thingBefore == null) {
                Log.i(TAG, "should set notifyAll to true because thingBefore is null")
                return true
            }

            if (thingBefore.id != thing!!.id) {
                Log.i(TAG, "should set notifyAll to true because ids are different")
                return true
            }

            val resultCodeBefore: Int = sLastUpdateUiIntent!!.getIntExtra(
                    Def.Communication.KEY_RESULT_CODE, Def.Communication.RESULT_NO_UPDATE)
            if (resultCode == resultCodeBefore) {
                Log.i(TAG, "should not set notifyAll to true because resultCodes are same")
                return false
            }

            if (resultCodeBefore == Def.Communication.RESULT_UPDATE_THING_DONE_TYPE_SAME) {
                Log.i(TAG, "should not set notifyAll to true because resultCodeBefore is UPDATE_TYPE_SAME")
                return false
            }

            Log.i(TAG, "should set notifyAll to true")
            return true
        }

        @JvmStatic
        fun getDoingThingId(): Long {
            return doingThingId
        }

        @JvmStatic
        fun setDoingThingId(doingThingId: Long) {
            this.doingThingId = doingThingId
        }

        @JvmStatic
        fun killMeAndRestart(context: Context?, toLaunch: Class<*>?, time: Long) {
            val intent = if (toLaunch == null) {
                context!!.packageManager.getLaunchIntentForPackage(
                    context.packageName
                )!!
            } else {
                Intent(context, toLaunch)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pendingIntent: PendingIntent = PendingIntent.getActivity(context,
                    0, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val am: AlarmManager = context!!.getSystemService(ALARM_SERVICE) as AlarmManager
            AlarmHelper.setExactAllowWhileIdleSafe(
                    am, System.currentTimeMillis() + time + 100, pendingIntent)
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                exitProcess(0)
            }, time)
        }

        @JvmStatic
        fun getThingAndPosition(context: Context?, id: Long, knownPos: Int): Pair<Thing, Int> {
            val thingManager: ThingManager = ThingManager.getInstance(context)!!
            val thingDAO: ThingDAO = ThingDAO.getInstance(context)!!
            var thing: Thing? = null
            var correctPos: Int = knownPos
            if (knownPos == -1) {
                correctPos = thingManager.getPosition(id)
                thing = if (correctPos == -1) {
                    thingDAO.getThingById(id)
                } else {
                    thingManager.getThings()!![correctPos]
                }
            } else {
                val things: List<Thing?> = thingManager.getThings()!!
                val size: Int = things.size
                if (knownPos >= size || things[knownPos]!!.id != id) {
                    for (i in 0 until size) {
                        val temp: Thing = things[i]!!
                        if (temp.id == id) {
                            thing = temp
                            correctPos = i
                            break
                        }
                    }
                    if (thing == null) {
                        thing = thingDAO.getThingById(id)
                        correctPos = -1
                    }
                } else {
                    thing = things[knownPos]
                }
            }
            return Pair(thing, correctPos)
        }

        /**
         * Roll a random background for the next new-thing creation.
         *
         * Phase 3 of color migration:
         * - Generates a PURE-mode [ThingBackground] with completely random RGB
         *   (per Q5 of docs/features/color-system-migration/plan.md: full
         *   spectrum, no HSL clamp).
         * - Avoids landing on the same color as the previous [newThingColor],
         *   or any of the colors in the up-to-4 neighbouring rows around the
         *   prospective insertion point (so a new card doesn't sit next to a
         *   perceptually-identical sibling).
         *
         * Phase 4 will extend this to a 50/50 PURE-vs-GRADIENT roll.
         *
         * Method name kept as-is for source compat — the field it ultimately writes
         * is now [newThingBackground] (with [newThingColor] kept as the
         * representative-int companion).
         */
        @JvmStatic
        fun updateNewThingColor() {
            var bg: ThingBackground = rollBackground()

            // Phase 3 logic preserved: try not to repeat the previous new-thing colour
            // and any of the up-to-4 neighbouring cards by RGB-distance on the
            // representative.
            var representative: Int = bg.representativeColor()
            while (ThingManager.isTotallyInitialized() && app!!.mThingManager != null
                    && app!!.mStatus == Def.ThingStatus.UNDERWAY) {
                val things: MutableList<Thing?> = app!!.mThingManager!!.getThings() ?: break

                val size: Int = things.size
                if (size <= 1) {
                    break
                }

                val index: Int = app!!.mThingManager!!.getPositionToInsertNewThing()
                val existedColors = IntArray(4)
                var start: Int = index - 2
                var end: Int = index + 1
                while (start < 1) {
                    start++
                    end++
                }
                if (start in 1..<size) {
                    var i: Int = start
                    var j = 0
                    while (i <= end) {
                        if (i < size) {
                            val temp: Thing? = things[i]
                            if (temp != null) {
                                existedColors[j++] = temp.getBackground()!!.representativeColor()
                            }
                        }
                        i++
                    }
                }

                var spins = 0
                while ((isInsideNear(existedColors, representative) || tooClose(representative, newThingColor))
                        && spins++ < 32) {
                    bg             = rollBackground()
                    representative = bg.representativeColor()
                }

                break
            }

            newThingBackground = bg
            newThingColor      = representative
        }

        /**
         * Phase 4.e: 50/50 PURE vs two-colour linear-gradient background, matching
         * Everything-Android's reference. Random RGB throughout (per Q5 of the plan —
         * no HSL clamp).
         */
        private fun rollBackground(): ThingBackground {
            if (sRandom.nextBoolean()) {
                return ThingBackground.pure(randomColor())
            }
            val s: Int = randomColor()
            var e: Int = randomColor()
            // Avoid degenerate gradient stops feeling identical — re-roll the end colour
            // a few times if it's too close to the start.
            var i = 0
            while (i < 4 && tooClose(s, e)) {
                e = randomColor()
                i++
            }
            val orientations: Array<ThingBackground.Orientation> =
                ThingBackground.Orientation.entries.toTypedArray()
            val o: ThingBackground.Orientation = orientations[sRandom.nextInt(orientations.size)]
            return ThingBackground.gradient(s, e, o)
        }

        /** Full-spectrum random RGB — matches Everything-Android's `newRandomColor()`. */
        private fun randomColor(): Int {
            return android.graphics.Color.rgb(
                    sRandom.nextInt(256), sRandom.nextInt(256), sRandom.nextInt(256))
        }

        /** Perceptual-ish RGB distance threshold for "feels like the same color". */
        private const val NEAR_THRESHOLD: Int = 60

        private fun tooClose(a: Int, b: Int): Boolean {
            if (a == 0 || b == 0) return false // 0 used as "unset" sentinel
            val dr: Int = android.graphics.Color.red(a)   - android.graphics.Color.red(b)
            val dg: Int = android.graphics.Color.green(a) - android.graphics.Color.green(b)
            val db: Int = android.graphics.Color.blue(a)  - android.graphics.Color.blue(b)
            // Cheap approximation: sum of |Δ| rather than Euclidean — fine for "avoid duplicates".
            return abs(dr) + abs(dg) + abs(db) < NEAR_THRESHOLD
        }

        private fun isInsideNear(arr: IntArray, color: Int): Boolean {
            for (c in arr) {
                if (tooClose(color, c)) return true
            }
            return false
        }

        private val sRandom: java.util.Random = java.util.Random()

        private fun isInside(arr: IntArray, value: Int): Boolean {
            for (elem in arr) {
                if (elem == value) {
                    return true
                }
            }
            return false
        }
    }

}
