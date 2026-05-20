@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.services

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.PowerManager
import androidx.annotation.IntDef
import androidx.core.app.NotificationCompat
import android.util.Log
import android.widget.Toast

import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.database.DoingRecordDAO
import com.ywwynm.everythingdone.database.HabitDAO
import com.ywwynm.everythingdone.helpers.RemoteActionHelper
import com.ywwynm.everythingdone.helpers.ThingDoingHelper
import com.ywwynm.everythingdone.model.DoingRecord
import com.ywwynm.everythingdone.model.Habit
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.utils.DateTimeUtil
import com.ywwynm.everythingdone.utils.SystemNotificationUtil

import java.util.GregorianCalendar

/**
 * Created by qiizhang on 2016/11/2.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * A Service to control countdown of a thing that user is currently doing
 */
open class DoingService : Service() {

    @IntDef(0, 1, 2)
    @Retention(AnnotationRetention.SOURCE)
    annotation class State

    @IntDef(0, 1, 2)
    @Retention(AnnotationRetention.SOURCE)
    annotation class StartType

    interface DoingListener {
        fun onLeftTimeChanged(numbersFrom: IntArray?, numbersTo: IntArray?, leftTimeBefore: Long, leftTimeAfter: Long)
        fun onAdd5Min(leftTime: Long)
        fun onCountdownFailed()
        fun onCountdownEnd()
    }
    private var mDoingListener: DoingListener? = null

    private var mBinder: DoingBinder? = null

    @StartType private var mStartType: Int = 0
    private var mShouldAutoStrictMode: Boolean = false

    private var mThing: Thing? = null
    private var mHabit: Habit? = null

    private var mTimeInMillis: Long = 0
    private var mPredictDoingTime: Long = 0
    private var mStartTime: Long = 0
    private var mLeftTime: Long = 0
    private var mEndTime: Long = 0

    private val mTimeNumbers: IntArray = intArrayOf(-1, -1, -1, -1, -1, -1)

    private var mAdd5MinTimes: Int = 0
    private var mTotalAdd5MinTimes: Int = 0

    private var mInStrictMode: Boolean = false
    private var mPlayedTimes: Int = 0
    private var mStartPlayTime: Long = -1L
    private var mTotalPlayedTime: Long = 0
    private var mHasTurnedStrictModeOn: Boolean = false
    private var mHasTurnedStrictModeOff: Boolean = false

    private var mCarelessWarned: Boolean = false

    private var mStartHighlighted: Boolean = false
    private var mEndHighlighted: Boolean = false

    private var mWakeLock: PowerManager.WakeLock? = null

    private var mHandler: Handler? = Handler(object : Handler.Callback {
        override fun handleMessage(message: Message): Boolean {
            if (message.what == 96) {
                Log.i(TAG, "User is doing something, counting down, " +
                        "mAdd5MinTimes[" + mAdd5MinTimes + "], " +
                        "mLeftTimeBefore[" + mLeftTime + "], " +
                        "mTimeInMillisBefore[" + mTimeInMillis + "]")

                val leftTimeBefore: Long = mLeftTime
                handleAdd5Min()

                if (mLeftTime > 0) {
                    handleLeftTimeChange(leftTimeBefore)
                }

                if (mStartPlayTime != -1L) {
                    mTotalPlayedTime += 1000
                }

                val carelessCon1: Boolean = mPlayedTimes >= 3
                val carelessCon2: Boolean = mTotalPlayedTime >= 5 * MINUTE_MILLIS
                val careless: Boolean = carelessCon1 || carelessCon2
                @State val doingState: Int = if (careless) STATE_FAILED_CARELESS else STATE_DOING

                val notification: Notification = SystemNotificationUtil.createDoingNotification(
                        this@DoingService, mThing, doingState, getLeftTimeStr(), sHrTime,
                        getHighlightStrategy(careless))!!
                mStartHighlighted = true
                startForeground(mThing!!.id.toInt(), notification)

                Log.i(TAG, "mLeftTimeAfter[" + mLeftTime + "], " +
                        "mTimeInMillisAfter[" + mTimeInMillis + "], " +
                        "leftTimeStr[" + getLeftTimeStr() + "], " +
                        "doingState[" + doingState + "], " +
                        "mStartPlayTime[" + DateTimeUtil.getGeneralDateTimeStr(this@DoingService, mStartPlayTime) + "], " +
                        "mPlayedTimes[" + mPlayedTimes + "], " +
                        "mTotalPlayedTime[" + mTotalPlayedTime + "]")

                if (careless) {
                    handleCareless()
                }

                if (mLeftTime == 0L) {
                    handleCountdownEnd()
                }

                if (doingState == STATE_DOING) {
                    mHandler!!.sendEmptyMessageDelayed(96, 1000)
                    if (mWakeLock != null && !mWakeLock!!.isHeld()) {
                        mWakeLock!!.acquire()
                    }
                } else if (mWakeLock != null && mWakeLock!!.isHeld()) {
                    mWakeLock!!.release()
                }
                return true
            }
            return false
        }
    })

    private fun handleAdd5Min() {
        if (mAdd5MinTimes != 0 && mLeftTime == 0L) {
            // Countdown stopped but we want to add 5 more minutes. Current numbers are all 0
            // and we want to start from 05:00, as a result, we should add another 1 second.
            mLeftTime += 1000

            // also reset mEndHighlighted
            mEndHighlighted = false
        }
        for (i in 1..mAdd5MinTimes) {
            mLeftTime += 5 * MINUTE_MILLIS
            mTimeInMillis += 5 * MINUTE_MILLIS
        }
        if (mAdd5MinTimes != 0 && mDoingListener != null) {
            mDoingListener!!.onAdd5Min(mLeftTime)
        }
        mAdd5MinTimes = 0
    }

    private fun handleLeftTimeChange(leftTimeBefore: Long) {
        val from: IntArray = IntArray(6)
        System.arraycopy(mTimeNumbers, 0, from, 0, 6)
        mLeftTime -= 1000
        calculateTimeNumbers(mLeftTime)
        if (mDoingListener != null) {
            mDoingListener!!.onLeftTimeChanged(from, mTimeNumbers, leftTimeBefore, mLeftTime)
        }
    }

    private fun handleCareless() {
        App.setDoingThingId(-1L)
        RemoteActionHelper.doingOrCancel(this@DoingService, mThing)
        mEndTime = System.currentTimeMillis()
        sStopReason = DoingRecord.STOP_REASON_CANCEL_CARELESS

        if (!mCarelessWarned) {
            Toast.makeText(this@DoingService, R.string.doing_failed_careless,
                    Toast.LENGTH_LONG).show()
            mCarelessWarned = true
        }
        if (mDoingListener != null) {
            mDoingListener!!.onCountdownFailed()
        }
    }

    private fun handleCountdownEnd() {
        mEndHighlighted = true
        if (mDoingListener != null) {
            mDoingListener!!.onCountdownEnd()
        }
    }

    private fun getHighlightStrategy(careless: Boolean): Int {
        if (mStartType == START_TYPE_AUTO && !mStartHighlighted) {
            return 1
        }
        if (careless && !mCarelessWarned) {
            return 2
        }
        if (mLeftTime == 0L && !mEndHighlighted) {
            return 2
        }
        return 0
    }

    override fun onBind(intent: Intent): IBinder? {
        if (mBinder == null) {
            mBinder = DoingBinder()
        }
        return mBinder
    }

    override fun onUnbind(intent: Intent): Boolean {
        mDoingListener = null
        return super.onUnbind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand() start")

        // Foreground-service contract: when started via startForegroundService(),
        // we MUST call startForeground() within ~5s or the system kills the
        // whole process with ForegroundServiceDidNotStartInTimeException. Even
        // on early-return paths (null intent from a sticky restart, missing
        // KEY_THING extra) we have to honor the contract before bailing out.
        val thing: Thing? = if (intent != null)
                intent.getParcelableExtra(Def.Communication.KEY_THING) else null
        if (intent == null || thing == null) {
            Log.w(TAG, "onStartCommand without a usable Thing — " +
                    "promoting placeholder + stopping. intent=" + intent)
            promoteToForegroundPlaceholder()
            stopForeground(true)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val pm: PowerManager = getSystemService(POWER_SERVICE) as PowerManager
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EverythingDone:DoingService")

        mThing = Thing(thing)

        if (mThing!!.type == Thing.HABIT) {
            mHabit = HabitDAO.getInstance(getApplicationContext())!!.getHabitById(mThing!!.id)
        }

        mTimeInMillis = intent.getLongExtra(KEY_TIME_IN_MILLIS, -1L)
        mPredictDoingTime = mTimeInMillis
        mStartTime = intent.getLongExtra(KEY_START_TIME, -1L)
        mEndTime = -1

        if (mTimeInMillis == -1L) {
            mLeftTime = -1
        } else {
            if (System.currentTimeMillis() - mStartTime < 6 * 1000L) {
                mLeftTime = mTimeInMillis / 1000L * 1000L
            } else {
                mLeftTime = (mStartTime + mTimeInMillis - System.currentTimeMillis()) / 1000L * 1000L
            }
        }

        sHrTime = intent.getLongExtra(Def.Communication.KEY_TIME, -1L)

        mAdd5MinTimes = 0
        mTotalAdd5MinTimes = 0

        mStartType = intent.getIntExtra(KEY_START_TYPE, START_TYPE_ALARM)
        val helper: ThingDoingHelper = ThingDoingHelper(this, mThing)
        mShouldAutoStrictMode = helper.shouldAutoStrictMode()

        Log.i(TAG, "start counting down, mPredictDoingTime[" + mPredictDoingTime + "], " +
                "mStartTime[" + DateTimeUtil.getGeneralDateTimeStr(this, mStartTime) + "], " +
                "mThing.type[" + mThing!!.type + "], " +
                "sHrTime[" + DateTimeUtil.getGeneralDateTimeStr(this, sHrTime) + "], " +
                "mStartType[" + mStartType + "], " +
                "mShouldAutoStrictMode[" + mShouldAutoStrictMode + "]")

        mInStrictMode = mShouldAutoStrictMode
        mPlayedTimes = 0
        mStartPlayTime = -1L

        mCarelessWarned = false

        sStopReason = DoingRecord.STOP_REASON_CANCEL_USER
        sSendBroadcastToUpdateMainUi = true
        sResetDoingIdInOnDestroy = true

        val initialNotification: Notification = SystemNotificationUtil.createDoingNotification(
                this, mThing, STATE_DOING, getInitialLeftTimeStr(), sHrTime, 0)!!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(mThing!!.id.toInt(), initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(mThing!!.id.toInt(), initialNotification)
        }

        Log.i(TAG, "onStartCommand() end")

        // Don't auto-restart with a stale/null intent — the early-return path
        // above only exists because the OS occasionally delivers a sticky
        // restart we don't want to handle.
        return START_NOT_STICKY
    }

    /**
     * Last-resort foreground promotion used when [onStartCommand] can't
     * complete its work (e.g. sticky restart with null intent).
     */
    private fun promoteToForegroundPlaceholder() {
        val placeholder: Notification = NotificationCompat.Builder(this, "doing")
                .setSmallIcon(R.drawable.act_create_white)
                .setContentTitle(getString(R.string.title_activity_doing))
                .setOngoing(false)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(0, placeholder,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(0, placeholder)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy() start")

        if (sResetDoingIdInOnDestroy) {
            App.setDoingThingId(-1L)
        }
        mHandler!!.removeMessages(96)
        stopForeground(true)

        if (mEndTime == -1L) {
            mEndTime = System.currentTimeMillis()
        }

        if (mThing != null) {
            val doingRecord: DoingRecord = DoingRecord(-1, mThing!!.id, mThing!!.type,
                    mTotalAdd5MinTimes, mPlayedTimes, mTotalPlayedTime,
                    mPredictDoingTime, mStartTime, mEndTime, sStopReason,
                    mStartType, mShouldAutoStrictMode)
            DoingRecordDAO.getInstance(this)!!.insert(doingRecord)

            if (sSendBroadcastToUpdateMainUi) {
                RemoteActionHelper.doingOrCancel(this, mThing)
            }
        }

        sHrTime = -1

        mThing = null
        mHandler = null
        mDoingListener = null

        if (mWakeLock != null && mWakeLock!!.isHeld()) {
            mWakeLock!!.release()
        }
        mWakeLock = null

        Log.i(TAG, "onDestroy() end")
    }

    private fun setDoingListener(listener: DoingListener?) {
        mDoingListener = listener
    }

    private fun startCountDown(resume: Boolean) {
        if (resume) {
            mHandler!!.removeMessages(96)
            for (i in 0 until mTimeNumbers.size) {
                mTimeNumbers[i] = -1
            }
            if (mLeftTime == 0L) {
                // countdown stopped but we resumed DoingActivity, so at least play animation to
                // show timely views
                mLeftTime = 1000
            }
        } else if (mTimeInMillis != -1L) {
            mLeftTime += 1000
        }
        mAdd5MinTimes = 0
        mHandler!!.sendEmptyMessageDelayed(96, 1000)
    }

    private fun getThing(): Thing? {
        return mThing
    }

    private fun setThing(thing: Thing?) {
        mThing = thing
    }

    private fun getLeftTime(): Long {
        return mLeftTime
    }

    private fun getTimeInMillis(): Long {
        return mTimeInMillis
    }

    private fun calculateTimeNumbers(leftTime: Long) {
        var lt: Long = leftTime
        var hours: Long = lt / HOUR_MILLIS
        if (hours > 99) hours = 99
        mTimeNumbers[0] = (hours / 10).toInt()
        mTimeNumbers[1] = (hours % 10).toInt()

        lt %= HOUR_MILLIS
        val minutes: Long = lt / MINUTE_MILLIS
        mTimeNumbers[2] = (minutes / 10).toInt()
        mTimeNumbers[3] = (minutes % 10).toInt()

        lt %= MINUTE_MILLIS
        val seconds: Long = lt / 1000
        mTimeNumbers[4] = (seconds / 10).toInt()
        mTimeNumbers[5] = (seconds % 10).toInt()
    }

    private fun add5Min() {
        mAdd5MinTimes++
        mTotalAdd5MinTimes++
    }

    private fun canAdd5Min(): Boolean {
        if (mTimeInMillis == -1L) {
            return false // Your time is already infinite, why would you like 5 more minutes?
        }

        val leftTime: Long = mLeftTime + 5 * MINUTE_MILLIS * (mAdd5MinTimes + 1)
        if (leftTime / HOUR_MILLIS > 99) {
            Toast.makeText(this, R.string.doing_toast_add5_above99, Toast.LENGTH_LONG).show()
            return false
        }

        if (mHabit != null) {
            val etc: Long
            if (mLeftTime == 0L) { // countdown is over
                etc = System.currentTimeMillis() + 5 * MINUTE_MILLIS * (mAdd5MinTimes + 1)
            } else {
                etc = mStartTime + mTimeInMillis + 5 * MINUTE_MILLIS * (mAdd5MinTimes + 1)
            }
            val habitType: Int = mHabit!!.type
            val calendar: GregorianCalendar = GregorianCalendar()
            val ct: Int = calendar.get(habitType) // current t
            calendar.setTimeInMillis(etc)
            if (calendar.get(habitType) != ct) {
                Toast.makeText(this, R.string.doing_toast_add5_time_long_t,
                        Toast.LENGTH_LONG).show()
                return false
            } else {
                val nextTime: Long = mHabit!!.getDoingEndLimitTime()
                if (etc >= nextTime - ThingDoingHelper.TIME_BEFORE_NEXT_HABIT_REMINDER) {
                    Toast.makeText(this, R.string.doing_toast_add5_time_long_alarm,
                            Toast.LENGTH_LONG).show()
                    return false
                }
            }
        }
        return true
    }

    private fun isInStrictMode(): Boolean {
        return mInStrictMode
    }

    private fun setInStrictMode(inStrictMode: Boolean) {
        mInStrictMode = inStrictMode
    }

    private fun getPlayedTimes(): Int {
        return mPlayedTimes
    }

    private fun setPlayedTimes(playedTimes: Int) {
        mPlayedTimes = playedTimes
    }

    private fun getStartPlayTime(): Long {
        return mStartPlayTime
    }

    private fun setStartPlayTime(startPlayTime: Long) {
        mStartPlayTime = startPlayTime
    }

    private fun setTotalPlayedTime(totalPlayedTime: Long) {
        mTotalPlayedTime = totalPlayedTime
    }

    private fun hasTurnedStrictModeOn(): Boolean {
        return mHasTurnedStrictModeOn
    }

    private fun hasTurnedStrictModeOff(): Boolean {
        return mHasTurnedStrictModeOff
    }

    private fun getLeftTimeStr(): String? {
        if (mTimeInMillis == -1L) {
            return getString(R.string.infinity)
        } else {
            if (mTimeNumbers[0] == -1) {
                return "00:00:00"
            } else {
                return mTimeNumbers[0].toString() + "" + mTimeNumbers[1] + ":" +
                        mTimeNumbers[2] + "" + mTimeNumbers[3] + ":" +
                        mTimeNumbers[4] + "" + mTimeNumbers[5]
            }
        }
    }

    private fun getInitialLeftTimeStr(): String? {
        if (mLeftTime == -1L) {
            return getString(R.string.infinity)
        }
        val totalSecs: Long = mLeftTime / 1000
        val hours: Long = totalSecs / 3600
        val minutes: Long = (totalSecs % 3600) / 60
        val seconds: Long = totalSecs % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    open inner class DoingBinder : Binder() {

        open fun setCountdownListener(listener: DoingListener?) {
            this@DoingService.setDoingListener(listener)
        }

        open fun startCountdown(resume: Boolean) {
            this@DoingService.startCountDown(resume)
        }

        open fun getThing(): Thing? {
            return this@DoingService.getThing()
        }

        open fun setThing(thing: Thing?) {
            this@DoingService.setThing(thing)
        }

        open fun getLeftTime(): Long {
            return this@DoingService.getLeftTime()
        }

        open fun getTimeInMillis(): Long {
            return this@DoingService.getTimeInMillis()
        }

        open fun canAdd5Min(): Boolean {
            return this@DoingService.canAdd5Min()
        }

        open fun add5Min() {
            this@DoingService.add5Min()
        }

        open fun isInStrictMode(): Boolean {
            return this@DoingService.isInStrictMode()
        }

        open fun setInStrictMode(inStrictMode: Boolean) {
            if (!inStrictMode) {
                mHasTurnedStrictModeOff = true
            } else {
                mHasTurnedStrictModeOn = true
            }
            this@DoingService.setInStrictMode(inStrictMode)
        }

        open fun getPlayedTimes(): Int {
            return this@DoingService.getPlayedTimes()
        }

        open fun setPlayedTimes(playedTimes: Int) {
            this@DoingService.setPlayedTimes(playedTimes)
        }

        open fun getStartPlayTime(): Long {
            return this@DoingService.getStartPlayTime()
        }

        open fun setStartPlayTime(startPlayTime: Long) {
            this@DoingService.setStartPlayTime(startPlayTime)
        }

        open fun setTotalPlayedTime(totalPlayedTime: Long) {
            this@DoingService.setTotalPlayedTime(totalPlayedTime)
        }

        open fun hasTurnedStrictModeOn(): Boolean {
            return this@DoingService.hasTurnedStrictModeOn()
        }

        open fun hasTurnedStrictModeOff(): Boolean {
            return this@DoingService.hasTurnedStrictModeOff()
        }
    }

    companion object {
        const val TAG: String = "DoingService"

        const val STATE_DOING: Int             = 0
        const val STATE_FAILED_CARELESS: Int   = 1
        const val STATE_FAILED_NEXT_ALARM: Int = 2

        @JvmField
        @DoingRecord.StopReason
        var sStopReason: Int = DoingRecord.STOP_REASON_CANCEL_USER

        @JvmField
        var sSendBroadcastToUpdateMainUi: Boolean = true

        @JvmField
        var sResetDoingIdInOnDestroy: Boolean = true

        const val KEY_START_TIME: String     = "start_time"
        const val KEY_TIME_IN_MILLIS: String = "time_in_millis"
        const val KEY_START_TYPE: String     = "start_type"

        const val START_TYPE_ALARM: Int = 0
        const val START_TYPE_AUTO: Int  = 1
        const val START_TYPE_USER: Int  = 2

        private const val MINUTE_MILLIS: Long = 60 * 1000L
        private const val HOUR_MILLIS: Long   = 60 * MINUTE_MILLIS

        @JvmField
        var sHrTime: Long = -1

        @JvmStatic
        fun getOpenIntent(
                context: Context?, thing: Thing?, startTime: Long, timeInMillis: Long,
                @StartType startType: Int, hrTime: Long): Intent? {
            return Intent(context, DoingService::class.java)
                    .putExtra(Def.Communication.KEY_THING, thing)
                    .putExtra(KEY_START_TIME, startTime)
                    .putExtra(KEY_TIME_IN_MILLIS, timeInMillis)
                    .putExtra(KEY_START_TYPE, startType)
                    .putExtra(Def.Communication.KEY_TIME, hrTime)
        }
    }
}
