package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.view.FrameMetrics
import android.view.Window
import com.ywwynm.everythingdone.helpers.DebugFileLogger
import java.util.Locale

/**
 * Stage 0 临时性能探针。验收结束后应连同调用点整体删除。
 * 日志写入应用文件目录的 debug_logs/fablesol_frame_perf.log。
 */
internal class FableSolPerformanceMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val frameWindows = Array(FRAME_METRIC_NAMES.size) { FableSolMetricWindow(WINDOW_SIZE) }
    private val drawWindows = Array(DRAW_STAGE_NAMES.size) { FableSolMetricWindow(WINDOW_SIZE) }
    private val glWindows = Array(GL_STAGE_NAMES.size) { FableSolMetricWindow(WINDOW_SIZE) }
    private val glPhysicsWindows = Array(GL_PHYSICS_NAMES.size) { FableSolMetricWindow(WINDOW_SIZE) }
    private var frameCount = 0
    private var drawCount = 0
    private var glCount = 0
    private var window: Window? = null
    private var thread: HandlerThread? = null

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        addFrameMetric(0, metrics.getMetric(FrameMetrics.DRAW_DURATION))
        addFrameMetric(1, metrics.getMetric(FrameMetrics.SYNC_DURATION))
        addFrameMetric(2, metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION))
        addFrameMetric(3, metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION))
        addFrameMetric(4, metrics.getMetric(FrameMetrics.TOTAL_DURATION))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addFrameMetric(5, metrics.getMetric(FrameMetrics.GPU_DURATION))
        }
        frameWindows[6].add(dropped.toDouble())
        frameCount++
        if (frameCount % REPORT_EVERY_FRAMES == 0) logFrameSummary()
    }

    fun start(window: Window) {
        if (this.window != null) return
        val thread = HandlerThread("FableSolFrameMetrics").also { it.start() }
        this.thread = thread
        this.window = window
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        val refreshRate = window.decorView.display?.refreshRate ?: 0f
        DebugFileLogger.log(
            LOG_FILE,
            "start refreshHz=${format(refreshRate.toDouble())} thermalHeadroom=${format(thermalHeadroom())}",
            DEBUG_PREFIX,
            startSession = true
        )
    }

    fun stop() {
        val currentWindow = window ?: return
        currentWindow.removeOnFrameMetricsAvailableListener(listener)
        window = null
        thread?.quitSafely()
        thread = null
        DebugFileLogger.log(LOG_FILE, "stop", DEBUG_PREFIX)
    }

    fun recordDrawStages(
        drainNs: Long,
        physicsNs: Long,
        samplingNs: Long,
        colorNs: Long,
        assemblyNs: Long,
        submitAndOpticsNs: Long
    ) {
        drawWindows[0].add(drainNs / NS_PER_MS)
        drawWindows[1].add(physicsNs / NS_PER_MS)
        drawWindows[2].add(samplingNs / NS_PER_MS)
        drawWindows[3].add(colorNs / NS_PER_MS)
        drawWindows[4].add(assemblyNs / NS_PER_MS)
        drawWindows[5].add(submitAndOpticsNs / NS_PER_MS)
        drawCount++
        if (drawCount % REPORT_EVERY_FRAMES == 0) logDrawSummary()
    }

    fun recordGlStages(
        drainNs: Long,
        physicsNs: Long,
        buildNs: Long,
        drawNs: Long,
        swapNs: Long,
        physicsSubsteps: Int,
        boundaryLayers: Int,
        boundaryNs: Long,
        waveNs: Long,
        surfaceNs: Long,
        composeNs: Long
    ) {
        glWindows[0].add(drainNs / NS_PER_MS)
        glWindows[1].add(physicsNs / NS_PER_MS)
        glWindows[2].add(buildNs / NS_PER_MS)
        glWindows[3].add(drawNs / NS_PER_MS)
        glWindows[4].add(swapNs / NS_PER_MS)
        glPhysicsWindows[0].add(physicsSubsteps.toDouble())
        glPhysicsWindows[1].add(boundaryLayers.toDouble())
        glPhysicsWindows[2].add(boundaryNs / NS_PER_MS)
        glPhysicsWindows[3].add(waveNs / NS_PER_MS)
        glPhysicsWindows[4].add(surfaceNs / NS_PER_MS)
        glPhysicsWindows[5].add(composeNs / NS_PER_MS)
        glCount++
        if (glCount % REPORT_EVERY_FRAMES == 0) logGlSummary()
    }

    private fun addFrameMetric(index: Int, valueNs: Long) {
        if (valueNs >= 0L) frameWindows[index].add(valueNs / NS_PER_MS)
    }

    private fun logFrameSummary() {
        val summary = buildString {
            append("frame")
            for (i in FRAME_METRIC_NAMES.indices) appendMetric(FRAME_METRIC_NAMES[i], frameWindows[i])
            append(" thermalHeadroom=").append(format(thermalHeadroom()))
        }
        DebugFileLogger.log(LOG_FILE, summary, DEBUG_PREFIX)
    }

    private fun logDrawSummary() {
        val summary = buildString {
            append("onDraw")
            for (i in DRAW_STAGE_NAMES.indices) appendMetric(DRAW_STAGE_NAMES[i], drawWindows[i])
        }
        DebugFileLogger.log(LOG_FILE, summary, DEBUG_PREFIX)
    }

    private fun logGlSummary() {
        val summary = buildString {
            append("glFrame")
            for (i in GL_STAGE_NAMES.indices) appendMetric(GL_STAGE_NAMES[i], glWindows[i])
            for (i in GL_PHYSICS_NAMES.indices) appendMetric(GL_PHYSICS_NAMES[i], glPhysicsWindows[i])
        }
        DebugFileLogger.log(LOG_FILE, summary, DEBUG_PREFIX)
    }

    private fun StringBuilder.appendMetric(name: String, window: FableSolMetricWindow) {
        append(' ').append(name)
            .append("[p50=").append(format(window.percentile(50.0)))
            .append(",p95=").append(format(window.percentile(95.0)))
            .append(",p99=").append(format(window.percentile(99.0))).append(']')
    }

    private fun thermalHeadroom(): Double {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Double.NaN
        val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return Double.NaN
        return power.getThermalHeadroom(15).toDouble()
    }

    private fun format(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.3f", value) else "n/a"

    private companion object {
        const val LOG_FILE = "fablesol_frame_perf.log"
        const val DEBUG_PREFIX = "[DEBUG-FABLESOL-PERF]"
        const val WINDOW_SIZE = 240
        const val REPORT_EVERY_FRAMES = 120
        const val NS_PER_MS = 1_000_000.0
        val FRAME_METRIC_NAMES = arrayOf("draw", "sync", "command", "swap", "total", "gpu", "dropped")
        val DRAW_STAGE_NAMES = arrayOf("drain", "physics", "sample", "color", "mesh", "submit_optics")
        val GL_STAGE_NAMES = arrayOf("drain", "physics", "build", "draw", "swap")
        val GL_PHYSICS_NAMES = arrayOf("steps", "bcLayers", "bc", "waves", "surface", "compose")
    }
}
