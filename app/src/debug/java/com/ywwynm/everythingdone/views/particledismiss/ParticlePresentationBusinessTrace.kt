package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import java.util.WeakHashMap

/** 用户主动开启的整应用对照；只记动画与窗口时间，不改设置、不采集真实记事内容。 */
internal object ParticlePresentationBusinessTrace : Application.ActivityLifecycleCallbacks {
    private var installed = false
    private val main = Handler(Looper.getMainLooper())
    private val reports = WeakHashMap<Activity, ParticlePresentationReports.Report>()
    private val listeners = WeakHashMap<Activity, Window.OnFrameMetricsAvailableListener>()
    fun enable(application: Application, original: Boolean) {
        ParticlePresentationPolicy.originalForDebug = original
        if (!installed) { installed = true; application.registerActivityLifecycleCallbacks(this) }
    }
    private fun ignore(activity: Activity) = activity.javaClass.name.contains(".views.particledismiss.")
    override fun onActivityResumed(activity: Activity) {
        if (ignore(activity)) return
        reports.remove(activity)?.save()
        listeners.remove(activity)?.let { activity.window.removeOnFrameMetricsAvailableListener(it) }
        val original = ParticlePresentationPolicy.originalForDebug
        val report = ParticlePresentationReports.start(activity, "实际页面 ${activity.javaClass.simpleName}",
            if (original) "原实现" else "统一实现", ParticlePresentationPolicy.debugOptions(original), "整应用主动诊断，不采内容")
        reports[activity] = report
        ParticlePresentationDiagnostics.bind(activity, report)
        val listener = Window.OnFrameMetricsAvailableListener { _, frame, dropped ->
            report.event("window_frame", mapOf("intendedVsync" to frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                "durationNanos" to frame.getMetric(FrameMetrics.TOTAL_DURATION), "droppedCallbacks" to dropped,
                "drawNanos" to frame.getMetric(FrameMetrics.DRAW_DURATION),
                "layoutNanos" to frame.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                "inputNanos" to frame.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
                "animationNanos" to frame.getMetric(FrameMetrics.ANIMATION_DURATION),
                "syncNanos" to frame.getMetric(FrameMetrics.SYNC_DURATION),
                "commandNanos" to frame.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                "gpuNanos" to if (android.os.Build.VERSION.SDK_INT >= 31) frame.getMetric(FrameMetrics.GPU_DURATION) else -1,
                "meaning" to "window_only_not_particle_surface"))
        }
        listeners[activity] = listener
        activity.window.addOnFrameMetricsAvailableListener(listener, main)
    }
    override fun onActivityPaused(activity: Activity) { reports[activity]?.save() }
    override fun onActivityDestroyed(activity: Activity) {
        reports.remove(activity)?.save()
        listeners.remove(activity)?.let { activity.window.removeOnFrameMetricsAvailableListener(it) }
        ParticlePresentationDiagnostics.unbind(activity)
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
