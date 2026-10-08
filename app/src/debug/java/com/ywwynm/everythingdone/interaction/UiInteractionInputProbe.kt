package com.ywwynm.everythingdone.interaction

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.fragment.app.DialogFragment
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.activities.ParticleTuningActivity
import com.ywwynm.everythingdone.activities.SettingsActivity
import org.json.JSONObject
import java.io.File

/** 通过生产设置入口验证真实多指与已经投递的两个点击；不修改设置或记事。 */
class UiInteractionInputProbe : Instrumentation() {
    private var arguments = Bundle()
    private val created = mutableListOf<Activity>()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val mode = arguments.getString("mode") ?: "multi"
        val runId = arguments.getString("run_id") ?: mode
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val result = JSONObject().put("mode", mode)
        val app = targetContext.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) { created += activity }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        runOnMainSync { app.registerActivityLifecycleCallbacks(callbacks) }
        try {
            if (mode == "suite") {
                UiInteractionSuite(this, created, result, arguments.getString("from_case")).run()
            } else {
            val activity = startActivitySync(Intent(targetContext, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            val first = activity.findViewById<View>(R.id.rl_doing_digit_style_as_bt)
            val second = activity.findViewById<View>(R.id.rl_particle_tuning_as_bt)
            runOnMainSync { second.requestRectangleOnScreen(Rect(0, 0, second.width, second.height), true) }
            waitForIdleSync()
            val firstBounds = Rect()
            val secondBounds = Rect()
            runOnMainSync {
                check(first.getGlobalVisibleRect(firstBounds) && firstBounds.height() > 0)
                check(second.getGlobalVisibleRect(secondBounds) && secondBounds.height() > 0)
            }
            result.put("first_bounds", firstBounds.toShortString()).put("second_bounds", secondBounds.toShortString())
            when (mode) {
                "queued" -> runOnMainSync { first.performClick(); second.performClick() }
                "reverse" -> runOnMainSync { second.performClick(); first.performClick() }
                "multi" -> multiTap(firstBounds, secondBounds)
                else -> error("未知模式：$mode")
            }
            // 等待已受理事务落地；动画与字体初始化不作为此回归的计时指标。
            SystemClock.sleep(2200)
            runOnMainSync {
                val dialogs = activity.supportFragmentManager.fragments.count { it is DialogFragment && !it.isRemoving }
                val pages = created.count { it is ParticleTuningActivity }
                result.put("dialogs", dialogs).put("target_activities", pages)
                    .put("accepted", dialogs + pages).put("single_transition", dialogs + pages == 1)
            }
            }
        } catch (failure: Throwable) {
            result.put("error", failure.stackTraceToString())
        } finally {
            runOnMainSync {
                app.unregisterActivityLifecycleCallbacks(callbacks)
                created.asReversed().forEach { if (!it.isFinishing) it.finish() }
            }
        }
        val file = File(targetContext.getExternalFilesDir(null), "interaction-probe/$runId.json")
        file.parentFile!!.mkdirs()
        file.writeText(result.toString(2))
        finish(if (result.has("error")) 1 else 0, Bundle().apply { putString("result", result.toString()) })
    }

    private fun multiTap(first: Rect, second: Rect) {
        val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val downTime = SystemClock.uptimeMillis()
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coords = arrayOf(first, second).map { bounds -> MotionEvent.PointerCoords().apply {
            x = bounds.exactCenterX(); y = bounds.exactCenterY(); pressure = 1f; size = 1f
        } }.toTypedArray()
        fun send(action: Int, count: Int, sync: Boolean) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count,
                properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { check(automation.injectInputEvent(event, sync)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 1, true)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, true)
        SystemClock.sleep(60)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, false)
        send(MotionEvent.ACTION_UP, 1, false)
    }
}
