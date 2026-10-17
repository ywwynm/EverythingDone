package com.ywwynm.everythingdone.views.recording.fablesol

import android.os.Build
import android.content.Context
import android.util.Log
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.Window
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.helpers.DebugFileLogger
import org.json.JSONArray
import org.json.JSONObject
import android.view.animation.AnimationUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File

/** 仅 debug／卓易通 Direct 创建。数值采样不写盘、不查询系统、不等待 GPU。 */
internal class FableSolCompositionPerformance(private val window: Window, source: View,
                                              private val scenario: String) {
    private val app = source.context.applicationContext
    private val values = FableSolPerfAccumulator()
    private val startNs = System.nanoTime()
    val session = "${System.currentTimeMillis()}-${System.identityHashCode(source)}"
    private val code = source.context.getString(R.string.debug_update_code)
    private val sourceId = System.identityHashCode(source)
    private val windowId = System.identityHashCode(window.decorView)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = true
    @Volatile private var state = "{}"
    @Volatile var latestInput: FableSolPerfInput? = null
        private set
    @Volatile private var capturedSequence = 0L
    @Volatile private var importedSequence = 0L
    @Volatile private var swappedSequence = 0L
    @Volatile private var retiredBuffersPending = 0
    @Volatile private var retirementAsync = true
    @Volatile private var importEntries = 0
    @Volatile private var importEntriesInUse = 0
    @Volatile private var importPeakEntries = 0
    @Volatile private var importReuseEnabled = true
    private val clockTrace = FableSolClockStampTracker() // 仅主线程更新，捕获后随帧不可变地交给 GL。
    val clockFrame: FableSolClockStamp? get() = clockTrace.drawn
    private var lastClockMorph: FableSolClockStamp? = null
    private var lastClockDraw: FableSolClockStamp? = null
    private var lastClockDrawNs = 0L
    private var captureRecording = false // 主线程，区分离屏捕获中的绘制与原窗口更新显示列表。
    private var lastClockCapture: FableSolClockStamp? = null
    private var lastClockCaptureNs = 0L
    private var lastClockSwap: FableSolClockStamp? = null
    private var lastClockSwapNs = 0L
    private var inputSequence = 0L // UI 线程
    private var lastPreDrawNs = 0L // UI 线程
    private var lastCaptureNs = 0L // UI 线程
    private var lastSwapNs = 0L // GL 线程
    private var lastNewForegroundNs = 0L // GL 线程
    private var lastCaptureAtSwap = 0L // GL 线程
    private var lastInputAtSwap = 0L // GL 线程
    private var lastWindowVsync = 0L // 统计线程
    private var lastReportNs = startNs // 统计线程
    private var lastCpuMs = Process.getElapsedCpuTime()
    private var lastGcCount: Long? = null
    private var lastGcTime: Long? = null
    private var reportIndex = 0L
    private var metricsRegistered = false
    private val originalCallback = window.callback
    private val inputCallback = object : Window.Callback by originalCallback {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (!active) return originalCallback.dispatchTouchEvent(event)
            val before = System.nanoTime()
            latestInput = FableSolPerfInput(++inputSequence, before)
            sample(FableSolPerfMetric.INPUT_DELIVERY,
                (SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0) * 1_000_000L)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> count(FableSolPerfCount.TOUCH_DOWN)
                MotionEvent.ACTION_MOVE -> count(FableSolPerfCount.TOUCH_MOVE)
                MotionEvent.ACTION_UP -> count(FableSolPerfCount.TOUCH_UP)
                MotionEvent.ACTION_CANCEL -> count(FableSolPerfCount.TOUCH_CANCEL)
            }
            return try { originalCallback.dispatchTouchEvent(event) }
                finally { sample(FableSolPerfMetric.INPUT_DISPATCH, System.nanoTime() - before) }
        }
    }
    private val frameListener = Window.OnFrameMetricsAvailableListener { _, frame, lost ->
        if (active) {
            count(FableSolPerfCount.WINDOW_FRAME)
            count(FableSolPerfCount.METRICS_CALLBACKS_LOST, lost.toLong())
            for (i in WINDOW_IDS.indices) sample(WINDOW_METRICS[i], frame.getMetric(WINDOW_IDS[i]))
            val total = frame.getMetric(FrameMetrics.TOTAL_DURATION)
            val deadline = frame.getMetric(FrameMetrics.DEADLINE)
            if (deadline > 0 && total >= deadline) count(FableSolPerfCount.WINDOW_OVER_DEADLINE)
            if (frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) count(FableSolPerfCount.WINDOW_FIRST_FRAME)
            val vsync = frame.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
            if (vsync > 0) {
                if (lastWindowVsync > 0 && vsync > lastWindowVsync)
                    sample(FableSolPerfMetric.WINDOW_VSYNC_INTERVAL, vsync - lastWindowVsync)
                sample(FableSolPerfMetric.WINDOW_CALLBACK_DELAY, System.nanoTime() - vsync)
                lastWindowVsync = vsync
            }
        }
    }
    private val pulsePending = AtomicBoolean()
    @Volatile private var pulseStart = 0L
    private val pulseAck = Runnable {
        sample(FableSolPerfMetric.MAIN_QUEUE, System.nanoTime() - pulseStart)
        pulsePending.set(false)
    }
    private val pulse = object : Runnable {
        override fun run() {
            if (!active) return
            if (pulsePending.compareAndSet(false, true)) {
                pulseStart = System.nanoTime()
                main.post(pulseAck)
            }
            worker.postDelayed(this, 250L)
        }
    }
    private val report = object : Runnable {
        override fun run() {
            if (!active) return
            report("interval")
            worker.postDelayed(this, 2000L)
        }
    }
    private data class Trace(val atNs: Long, val stage: String, val sequence: Long, val durationNs: Long)
    private data class Event(val atNs: Long, val name: String, val detail: String)
    private val traceLock = Any()
    private val traces = ArrayList<Trace>(12)
    private val events = ArrayList<Event>(12)

    init {
        check(BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 34)
        prepareDiagnosticLogs(source.context)
        live[session] = this
        window.callback = inputCallback
        runCatching {
            window.addOnFrameMetricsAvailableListener(frameListener, worker)
            metricsRegistered = true
        }.onFailure { event("frame_metrics_unavailable", it.javaClass.simpleName) }
        val displayInfo = source.display?.toString()
        worker.post {
            write(JSONObject().put("event", "start").put("sdk", Build.VERSION.SDK_INT)
                .put("model", Build.MODEL).put("systemBuild", Build.DISPLAY).put("hardware", Build.HARDWARE)
                .put("debugRuntime", true).put("display", displayInfo)
                .put("windowMetricsRegistered", metricsRegistered)
                .put("cpuCores", Runtime.getRuntime().availableProcessors())
                .put("sampleCapacity", 512).put("reportPeriodMs", 2000)
                .put("units", "durations_ms_monotonic_ns")
                .put("interpretation", "swap_is_submission_not_screen_present;window_gpu_excludes_wave_surface;lost_metrics_are_not_dropped_display_frames;static_foreground_reuse_is_normal;input_latency_uses_latest_captured_event_only"))
        }
        worker.post(pulse)
        worker.postDelayed(report, 2000L)
    }

    fun sample(metric: FableSolPerfMetric, nanos: Long) { if (active) values.sample(metric, nanos) }
    fun count(counter: FableSolPerfCount, amount: Long = 1) { if (active) values.increment(counter, amount) }

    fun preDraw() {
        val now = System.nanoTime()
        count(FableSolPerfCount.PRE_DRAW)
        if (lastPreDrawNs > 0) sample(FableSolPerfMetric.PRE_DRAW_INTERVAL, now - lastPreDrawNs)
        lastPreDrawNs = now
    }

    fun captureStarted(now: Long, sequence: Long) {
        capturedSequence = sequence
        count(FableSolPerfCount.CAPTURE_SUBMITTED)
        if (lastCaptureNs > 0) sample(FableSolPerfMetric.CAPTURE_INTERVAL, now - lastCaptureNs)
        lastCaptureNs = now
    }

    fun imported(sequence: Long) {
        importedSequence = sequence
        count(FableSolPerfCount.FOREGROUND_IMPORTED)
    }

    /** 仅成功 swap 统计；是应用提交率，不能当作物理显示 FPS。 */
    fun swapped(timing: FableSolGlRenderer.Timing, frameStart: Long, swapNs: Long,
                frame: FableSolForegroundFrame?) {
        val now = System.nanoTime()
        count(FableSolPerfCount.WAVE_SWAP_OK)
        count(FableSolPerfCount.AUDIO_HOPS, timing.audioFrames.toLong())
        count(FableSolPerfCount.AUDIO_EVENTS, timing.audioEvents.toLong())
        count(FableSolPerfCount.AUDIO_PACKETS, timing.packetCount.toLong())
        sample(FableSolPerfMetric.GL_FRAME, now - frameStart)
        sample(FableSolPerfMetric.GL_SWAP, swapNs)
        if (lastSwapNs > 0) sample(FableSolPerfMetric.GL_SWAP_INTERVAL, now - lastSwapNs)
        lastSwapNs = now
        sample(FableSolPerfMetric.GL_DRAIN, timing.drainNs)
        sample(FableSolPerfMetric.GL_PHYSICS, timing.physicsNs)
        sample(FableSolPerfMetric.GL_BUILD, timing.buildNs)
        sample(FableSolPerfMetric.GL_DRAW_CPU, timing.drawNs)
        sample(FableSolPerfMetric.GL_SAMPLE, timing.sampleNs)
        sample(FableSolPerfMetric.GL_VERTEX, timing.vertexNs)
        sample(FableSolPerfMetric.GL_OPTICS, timing.opticsNs)
        sample(FableSolPerfMetric.GL_RIM, timing.rimNs)
        sample(FableSolPerfMetric.GL_STAR, timing.starNs)
        slow("gl_frame", frame?.sequence ?: 0, now - frameStart)
        recordOutputSwap(now, frame)
    }

    fun foregroundSwapped(startNs: Long, swapNs: Long, frame: FableSolForegroundFrame?) {
        val now = System.nanoTime()
        count(FableSolPerfCount.FOREGROUND_ONLY_SWAP)
        sample(FableSolPerfMetric.FOREGROUND_PRESENT, now - startNs)
        sample(FableSolPerfMetric.FOREGROUND_PRESENT_SWAP, swapNs)
        recordOutputSwap(now, frame)
    }

    private var lastOutputSwapNs = 0L
    private fun recordOutputSwap(now: Long, frame: FableSolForegroundFrame?) {
        count(FableSolPerfCount.GL_SWAP_OK)
        if (lastOutputSwapNs > 0) sample(FableSolPerfMetric.OUTPUT_SWAP_INTERVAL, now - lastOutputSwapNs)
        lastOutputSwapNs = now
        if (frame == null || frame.captureStartedNs == 0L) return
        clockSwapped(frame, now)
        swappedSequence = frame.sequence
        sample(FableSolPerfMetric.FOREGROUND_AGE_AT_SWAP, now - frame.captureStartedNs)
        // 只作输入后 1 秒的辅助切片，不把静止复用误判为动画停滞。
        val inputNow = latestInput
        if (inputNow != null && now - inputNow.receivedNs < 1_000_000_000L)
            sample(FableSolPerfMetric.FOREGROUND_AGE_AFTER_INPUT, now - frame.captureStartedNs)
        if (lastCaptureAtSwap != frame.captureStartedNs) {
            count(FableSolPerfCount.NEW_FOREGROUND)
            sample(FableSolPerfMetric.CAPTURE_TO_FIRST_SWAP, now - frame.captureStartedNs)
            slow("capture_to_first_swap", frame.sequence, now - frame.captureStartedNs)
            if (lastNewForegroundNs > 0)
                sample(FableSolPerfMetric.NEW_FOREGROUND_INTERVAL, now - lastNewForegroundNs)
            lastNewForegroundNs = now
            lastCaptureAtSwap = frame.captureStartedNs
            frame.input?.takeIf { it.sequence > lastInputAtSwap }?.let {
                sample(FableSolPerfMetric.INPUT_TO_CAPTURED_SWAP, now - it.receivedNs)
                lastInputAtSwap = it.sequence
            }
        } else count(FableSolPerfCount.REUSED_FOREGROUND)
    }

    fun slow(stage: String, sequence: Long, nanos: Long) {
        if (!active || nanos < 33_333_333L) return
        synchronized(traceLock) {
            if (traces.size < 12) traces += Trace(System.nanoTime(), stage, sequence, nanos)
            else count(FableSolPerfCount.TRACE_OVERFLOW)
        }
    }

    fun event(name: String, detail: String = "") {
        if (!active) return
        synchronized(traceLock) {
            if (events.size < 12) events += Event(System.nanoTime(), name, detail.take(320))
            else count(FableSolPerfCount.TRACE_OVERFLOW)
        }
    }

    /** 由主线程既有轮询每 2 秒采样；只观察刷新率请求，不修改窗口或显示策略。 */
    fun state(snapshot: JSONObject) {
        val attributes = window.attributes
        val display = window.decorView.display
        val modes = JSONArray()
        display?.supportedModes?.forEach { mode ->
            modes.put(JSONObject().put("id", mode.modeId).put("hz", mode.refreshRate)
                .put("width", mode.physicalWidth).put("height", mode.physicalHeight))
        }
        snapshot.put("windowPreferredRefreshHz", attributes.preferredRefreshRate)
            .put("windowPreferredModeId", attributes.preferredDisplayModeId)
            .put("supportedDisplayModes", modes)
            .put("lastTouchAgeMs", latestInput?.let { (System.nanoTime() - it.receivedNs) / 1e6 })
        if (Build.VERSION.SDK_INT >= 35) {
            snapshot.put("windowFrameRatePowerSavingsBalanced", window.isFrameRatePowerSavingsBalanced)
        }
        state = snapshot.toString()
    }

    fun retirementState(pending: Int, asynchronous: Boolean) {
        retiredBuffersPending = pending
        retirementAsync = asynchronous
    }

    fun importCacheState(entries: Int, inUse: Int, peak: Int, reuseEnabled: Boolean) {
        importEntries = entries
        importEntriesInUse = inUse
        importPeakEntries = peak
        importReuseEnabled = reuseEnabled
    }

    fun clockMorphStep() {
        if (!active) return
        val now = System.nanoTime()
        // 动画阶段共享系统动画时钟；同一拍的多位数字不会被误计为多个画面。
        val vsync = AnimationUtils.currentAnimationTimeMillis() * 1_000_000L
        count(FableSolPerfCount.CLOCK_ANIMATOR_UPDATE)
        val next = clockTrace.morph(vsync, now) ?: return
        count(FableSolPerfCount.CLOCK_MORPH_FRAME)
        lastClockMorph?.takeIf { it.burst == next.burst }?.let {
            sample(FableSolPerfMetric.CLOCK_MORPH_INTERVAL, now - it.morphNs)
        }
        lastClockMorph = next
    }

    fun clockDraw(startNs: Long, endNs: Long, capturePending: Boolean) {
        if (!active) return
        count(FableSolPerfCount.CLOCK_DRAW)
        count(if (captureRecording) FableSolPerfCount.CLOCK_DRAW_IN_CAPTURE else FableSolPerfCount.CLOCK_DRAW_OUTSIDE_CAPTURE)
        if (!captureRecording) count(if (capturePending) FableSolPerfCount.CLOCK_DRAW_OUTSIDE_PENDING
            else FableSolPerfCount.CLOCK_DRAW_OUTSIDE_READY)
        sample(FableSolPerfMetric.CLOCK_DRAW_CPU, endNs - startNs)
        val drawn = clockTrace.draw() ?: return
        if (lastClockDraw?.sequence == drawn.sequence) return
        count(FableSolPerfCount.CLOCK_NEW_DRAW)
        sample(FableSolPerfMetric.CLOCK_MORPH_TO_DRAW, endNs - drawn.morphNs)
        if (lastClockDraw?.burst == drawn.burst)
            sample(FableSolPerfMetric.CLOCK_DRAW_INTERVAL, endNs - lastClockDrawNs)
        lastClockDraw = drawn
        lastClockDrawNs = endNs
    }

    fun beginCaptureRecording() { captureRecording = true }
    fun endCaptureRecording() { captureRecording = false }

    fun clockCaptured(clock: FableSolClockStamp?, fromRetry: Boolean) {
        if (!active || clock == null || lastClockCapture?.sequence == clock.sequence) return
        val now = System.nanoTime()
        count(FableSolPerfCount.CLOCK_NEW_CAPTURE)
        if (fromRetry) count(FableSolPerfCount.CLOCK_CAPTURE_FROM_RETRY)
        sample(FableSolPerfMetric.CLOCK_MORPH_TO_CAPTURE, now - clock.morphNs)
        if (lastClockCapture?.burst == clock.burst)
            sample(FableSolPerfMetric.CLOCK_CAPTURE_INTERVAL, now - lastClockCaptureNs)
        lastClockCapture = clock
        lastClockCaptureNs = now
    }

    private fun clockSwapped(frame: FableSolForegroundFrame, now: Long) {
        val clock = frame.clock ?: return
        if (lastClockSwap?.sequence == clock.sequence) return
        count(FableSolPerfCount.CLOCK_NEW_SWAP)
        sample(FableSolPerfMetric.CLOCK_MORPH_TO_SWAP, now - clock.morphNs)
        if (lastClockSwap?.burst == clock.burst)
            sample(FableSolPerfMetric.CLOCK_SWAP_INTERVAL, now - lastClockSwapNs)
        lastClockSwap = clock
        lastClockSwapNs = now
    }

    fun stop() {
        if (!active) return
        active = false
        if (window.callback === inputCallback) window.callback = originalCallback
        if (metricsRegistered) runCatching { window.removeOnFrameMetricsAvailableListener(frameListener) }
        worker.removeCallbacks(report)
        worker.removeCallbacks(pulse)
        main.removeCallbacks(pulseAck)
        worker.post { report("stop"); live.remove(session) }
    }

    private fun report(reason: String) {
        val now = System.nanoTime()
        val elapsed = (now - lastReportNs).coerceAtLeast(1)
        val snapshot = values.drain()
        val metrics = JSONObject()
        snapshot.metrics.forEach { (metric, data) ->
            metrics.put(metric.name.lowercase(), JSONObject().put("n", data.count)
                .put("retained", data.values.size).put("mean", data.sumNs / data.count / 1e6)
                .put("p50", data.percentile(.50) / 1e6).put("p95", data.percentile(.95) / 1e6)
                .put("p99", data.percentile(.99) / 1e6)
                .put("max", data.maxNs / 1e6).put("over16_7", data.above16Ms).put("over33_3", data.above33Ms))
        }
        val counts = JSONObject()
        FableSolPerfCount.values().forEach { counts.put(it.name.lowercase(), snapshot.counts[it.ordinal]) }
        val slow = JSONArray(); val transitions = JSONArray()
        synchronized(traceLock) {
            traces.forEach { slow.put(JSONObject().put("atMs", (it.atNs - startNs) / 1e6)
                .put("stage", it.stage).put("seq", it.sequence).put("durationMs", it.durationNs / 1e6)) }
            events.forEach { transitions.put(JSONObject().put("atMs", (it.atNs - startNs) / 1e6)
                .put("event", it.name).put("detail", it.detail)) }
            traces.clear(); events.clear()
        }
        val cpu = Process.getElapsedCpuTime()
        val gcCount = runCatching { Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() }.getOrNull()
        val gcTime = runCatching { Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() }.getOrNull()
        val runtime = Runtime.getRuntime()
        val power = runCatching { app.getSystemService(PowerManager::class.java) }.getOrNull()
        val system = JSONObject().put("processCpuMs", (cpu - lastCpuMs).coerceAtLeast(0))
            .put("processCpuOneCorePercent", (cpu - lastCpuMs).coerceAtLeast(0) * 1e8 / elapsed)
            .put("javaHeapBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("thermalStatus", runCatching { power?.currentThermalStatus }.getOrNull())
            .put("powerSaveMode", runCatching { power?.isPowerSaveMode }.getOrNull())
        if (gcCount != null && lastGcCount != null) system.put("gcCountDelta", (gcCount - lastGcCount!!).coerceAtLeast(0))
        if (gcTime != null && lastGcTime != null) system.put("gcTimeMsDelta", (gcTime - lastGcTime!!).coerceAtLeast(0))
        lastGcCount = gcCount; lastGcTime = gcTime; lastCpuMs = cpu
        write(JSONObject().put("event", "summary").put("reason", reason).put("index", ++reportIndex)
            .put("fromMs", (lastReportNs - startNs) / 1e6).put("elapsedMs", elapsed / 1e6)
            .put("state", JSONObject(state)).put("counts", counts).put("metricsMs", metrics)
            .put("waveSwapsPerSecond", snapshot.counts[FableSolPerfCount.WAVE_SWAP_OK.ordinal] * 1e9 / elapsed)
            .put("outputSwapsPerSecond", snapshot.counts[FableSolPerfCount.GL_SWAP_OK.ordinal] * 1e9 / elapsed)
            .put("foregroundOnlySwapsPerSecond", snapshot.counts[FableSolPerfCount.FOREGROUND_ONLY_SWAP.ordinal] * 1e9 / elapsed)
            .put("newForegroundPerSecond", snapshot.counts[FableSolPerfCount.NEW_FOREGROUND.ordinal] * 1e9 / elapsed)
            .put("windowFramesPerSecond", snapshot.counts[FableSolPerfCount.WINDOW_FRAME.ordinal] * 1e9 / elapsed)
            .put("capturedSeq", capturedSequence).put("importedSeq", importedSequence).put("swappedSeq", swappedSequence)
            .put("retiredBuffersPending", retiredBuffersPending).put("retirementAsync", retirementAsync)
            .put("foregroundImportCache", JSONObject().put("strategy", "buffer_identity_rebind")
                .put("entries", importEntries).put("inUse", importEntriesInUse)
                .put("peakEntries", importPeakEntries).put("reuseEnabled", importReuseEnabled))
            .put("slowSamples", slow).put("transitions", transitions).put("system", system)
            .put("summaryBuildMs", (System.nanoTime() - now) / 1e6))
        lastReportNs = now
    }

    private fun write(json: JSONObject) {
        DebugFileLogger.log(LOG_FILE, json.put("session", session).put("scenario", scenario)
            .put("debugUpdateCode", code).put("view", sourceId).put("window", windowId)
            .put("uptimeMs", SystemClock.uptimeMillis()).toString(), "[DEBUG-FABLESOL-COMPOSITION-PERF]")
    }

    companion object {
        const val LOG_FILE = "fablesol_composition_perf.log"
        private var preparedLogVersion: String? = null
        /** 先排入同一个写盘队列，后续性能记录不会被清理任务越过。仅 debug 调用。 */
        @Synchronized fun prepareDiagnosticLogs(context: Context) {
            if (!BuildConfig.DEBUG) return
            val version = context.getString(R.string.debug_update_code)
            if (preparedLogVersion == version) return
            preparedLogVersion = version
            val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "debug_logs")
            DebugFileLogger.afterPendingWrites {
                runCatching { FableSolDiagnosticLogFiles(directory).ensureVersion(version) }
                    .onFailure { Log.w("FableSolCompositionPerf", "诊断日志版本清理失败", it) }
            }
        }
        // 全进程共用一个惰性统计线程，关闭弹窗后取消其所有采样任务、释放窗口引用。
        private val worker by lazy { Handler(HandlerThread("FableSolCompositionPerf").apply { start() }.looper) }
        private val live = ConcurrentHashMap<String, FableSolCompositionPerformance>()
        fun flushAll(done: () -> Unit) {
            if (!BuildConfig.DEBUG) { done(); return }
            worker.post { live.values.forEach { it.report("export") }; done() }
        }
        private val WINDOW_IDS = intArrayOf(FrameMetrics.UNKNOWN_DELAY_DURATION, FrameMetrics.INPUT_HANDLING_DURATION,
            FrameMetrics.ANIMATION_DURATION, FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION,
            FrameMetrics.SYNC_DURATION, FrameMetrics.COMMAND_ISSUE_DURATION, FrameMetrics.SWAP_BUFFERS_DURATION,
            FrameMetrics.TOTAL_DURATION, FrameMetrics.GPU_DURATION, FrameMetrics.DEADLINE)
        private val WINDOW_METRICS = arrayOf(FableSolPerfMetric.WINDOW_UNKNOWN, FableSolPerfMetric.WINDOW_INPUT,
            FableSolPerfMetric.WINDOW_ANIMATION, FableSolPerfMetric.WINDOW_LAYOUT, FableSolPerfMetric.WINDOW_DRAW,
            FableSolPerfMetric.WINDOW_SYNC, FableSolPerfMetric.WINDOW_COMMAND, FableSolPerfMetric.WINDOW_SWAP,
            FableSolPerfMetric.WINDOW_TOTAL, FableSolPerfMetric.WINDOW_GPU, FableSolPerfMetric.WINDOW_DEADLINE)
    }
}
