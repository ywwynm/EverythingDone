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
    private val glBuildWindows = Array(GL_BUILD_NAMES.size) { FableSolMetricWindow(WINDOW_SIZE) }
    private val glSampleWindows = Array(GL_SAMPLE_NAMES.size) { FableSolMetricWindow(WINDOW_SIZE) }
    private val glIntervalWindow = FableSolMetricWindow(WINDOW_SIZE)
    private var lastGlFrameNs = 0L
    private var cachedThermalHeadroom = Double.NaN
    private var cachedThermalHeadroomNs = 0L
    @Volatile private var cachedRefreshRate = Double.NaN
    @Volatile private var cachedRenderRate = Double.NaN
    private var cachedRefreshRateNs = 0L
    private val percentileScratch = DoubleArray(3)
    private val vsyncWindow = FableSolMetricWindow(WINDOW_SIZE)
    private val armWindow = FableSolMetricWindow(WINDOW_SIZE)
    private var callbackCount = 0
    private var skipCount = 0
    private val workWindow = FableSolMetricWindow(WINDOW_SIZE)
    private val idleWindow = FableSolMetricWindow(WINDOW_SIZE)
    private val timelineWindows = Array(3) { FableSolMetricWindow(WINDOW_SIZE) }
    @Volatile private var timelineCount = 0
    @Volatile private var displayModeRefreshRate = Double.NaN
    private var frameCount = 0
    private var drawCount = 0
    private var glCount = 0
    private var window: Window? = null
    private var thread: HandlerThread? = null
    // 摘要与 HUD 的格式化全部搬到监控自己的线程。逐版累加读数后，publishHud 里
    // 已有约 44 次分位数计算（每次一趟 240 元素排序），logGlSummary 还要再来 30 次；
    // 这些原本全落在 GL 线程的关键路径上，是探针自伤。窗口的并发读写对调试探针
    // 无害（最坏是某次分位数取到半新半旧的样本），换来的是 GL 线程只剩一次 post。
    @Volatile private var reportHandler: Handler? = null

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
        // 本回调已经在监控线程上，直接同步汇总即可。
        if (frameCount % REPORT_EVERY_FRAMES == 0) logFrameSummary()
    }

    fun start(window: Window) {
        if (this.window != null) return
        val thread = HandlerThread("FableSolFrameMetrics").also { it.start() }
        this.thread = thread
        this.reportHandler = Handler(thread.looper)
        this.window = window
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        val display = window.decorView.display
        // DisplayInfo.toString() 在非系统 uid 下也会打印 renderFrameRate、每个 mode 的
        // fps/vsync/synthetic 等字段，是绕开 getRefreshRate() 版本差异、直接看清系统
        // 究竟给本应用分配了多少渲染速率的唯一公开通路。
        DebugFileLogger.log(
            LOG_FILE,
            buildString {
                append("start sdk=").append(Build.VERSION.SDK_INT)
                append(" rel=").append(Build.VERSION.RELEASE)
                append(" model=").append(Build.MODEL)
                append(" build=").append(Build.DISPLAY)
                append(" refreshHz=").append(format((display?.refreshRate ?: 0f).toDouble()))
                append(" thermalHeadroom=").append(format(thermalHeadroom()))
                append('\n').append(display?.toString())
            },
            DEBUG_PREFIX,
            startSession = true
        )
    }

    fun stop() {
        val currentWindow = window ?: return
        currentWindow.removeOnFrameMetricsAvailableListener(listener)
        window = null
        reportHandler = null
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
        composeNs: Long,
        sampleNs: Long,
        vertexNs: Long,
        sheenNs: Long,
        colorNs: Long,
        opticsNs: Long,
        rimNs: Long,
        starNs: Long,
        audioFrames: Int,
        audioEvents: Int,
        packetCount: Int,
        repairRows: Int,
        samplePrepNs: Long,
        sampleFieldNs: Long,
        sampleLimitNs: Long,
        sampleFairNs: Long,
        sampleSlopeNs: Long
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
        glBuildWindows[0].add(sampleNs / NS_PER_MS)
        glBuildWindows[1].add(vertexNs / NS_PER_MS)
        glBuildWindows[2].add(sheenNs / NS_PER_MS)
        glBuildWindows[3].add(colorNs / NS_PER_MS)
        glBuildWindows[4].add(opticsNs / NS_PER_MS)
        glBuildWindows[5].add(audioFrames.toDouble())
        glBuildWindows[6].add(audioEvents.toDouble())
        glBuildWindows[7].add(packetCount.toDouble())
        glBuildWindows[8].add(repairRows.toDouble())
        glBuildWindows[9].add(rimNs / NS_PER_MS)
        glBuildWindows[10].add(starNs / NS_PER_MS)
        glSampleWindows[0].add(samplePrepNs / NS_PER_MS)
        glSampleWindows[1].add(sampleFieldNs / NS_PER_MS)
        glSampleWindows[2].add(sampleLimitNs / NS_PER_MS)
        glSampleWindows[3].add(sampleFairNs / NS_PER_MS)
        glSampleWindows[4].add(sampleSlopeNs / NS_PER_MS)

        // 实际渲染帧率：用相邻 GL 帧的墙钟间隔滑动平均，供屏幕 HUD 直接显示。
        val nowNs = System.nanoTime()
        if (lastGlFrameNs != 0L) {
            val deltaMs = (nowNs - lastGlFrameNs) / NS_PER_MS
            if (deltaMs > 0.0 && deltaMs < 500.0) glIntervalWindow.add(deltaMs)
        }
        lastGlFrameNs = nowNs

        glCount++
        val report = reportHandler
        if (report != null) {
            if (glCount % REPORT_EVERY_FRAMES == 0) report.post(logGlSummaryTask)
            if (glCount % HUD_EVERY_FRAMES == 0) report.post(publishHudTask)
        }
    }

    /**
     * 帧回调级读数：相邻 vsync 时间戳之差（`vs`，等于系统实际派发周期）、
     * 回调相对 vsync 时刻的到达延迟（`arm`）、以及节拍器跳过的比例（`skip`）。
     * 三者合起来能一次性区分「系统没按 120Hz 派发」「回调到得太晚」「自己跳了帧」。
     */
    /** 一次帧回调的真实占用与两次回调之间的空转。work + idle 应约等于 vs。 */
    fun recordCallbackSpan(workNs: Long, idleNs: Long) {
        if (workNs in 0L..500_000_000L) workWindow.add(workNs / NS_PER_MS)
        if (idleNs in 0L..500_000_000L) idleWindow.add(idleNs / NS_PER_MS)
    }

    /**
     * SurfaceFlinger 给本连接的 vsync 栅格（`grid`）、预期呈现提前量（`lead`）与
     * 截止时刻（`dl`）。`grid ≈ 16.7` 说明系统就是按 60 服务我们，应用侧无解；
     * `grid ≈ 8.3` 说明 SF 按 120 排栅格，60 是在派发或接收侧丢的，有解。
     */
    fun recordVsyncTimeline(gridNs: Long, leadNs: Long, deadlineNs: Long, count: Int) {
        if (gridNs > 0L) timelineWindows[0].add(gridNs / NS_PER_MS)
        timelineWindows[1].add(leadNs / NS_PER_MS)
        timelineWindows[2].add(deadlineNs / NS_PER_MS)
        timelineCount = count
    }

    fun recordFrameCallback(vsyncDeltaNs: Long, armNs: Long, rendered: Boolean) {
        if (vsyncDeltaNs in 1L..500_000_000L) vsyncWindow.add(vsyncDeltaNs / NS_PER_MS)
        if (armNs >= 0L) armWindow.add(armNs / NS_PER_MS)
        callbackCount++
        if (!rendered) skipCount++
    }

    /** 物理显示模式的刷新率，与可能被系统下调的 `hz` 并列显示以便对照。 */
    fun setDisplayModeRefreshRate(rate: Double) {
        displayModeRefreshRate = rate
    }

    // 复用同一个 Runnable，避免每次 post 都产生短命对象。
    private val logGlSummaryTask = Runnable { logGlSummary() }
    private val publishHudTask = Runnable { publishHud() }

    /** HUD 回调（UI 线程）；只在 debug 构建由宿主注册。 */
    @Volatile var onHudUpdate: ((String) -> Unit)? = null

    private fun publishHud() {
        val listener = onHudUpdate ?: return
        val intervalMs = glIntervalWindow.percentile(50.0)
        val fps = if (intervalMs > 0.0 && intervalMs.isFinite()) 1000.0 / intervalMs else Double.NaN
        val text = buildString {
            append("fps ").append(format1(fps))
            append("  gl ").append(format1(glIntervalWindow.percentile(50.0))).append("ms")
            append(" p95 ").append(format1(glIntervalWindow.percentile(95.0)))
            // 面板运行时实际刷新率：系统可能在录音期间把高刷面板降到 60Hz，
            // 那样帧率上限与 CPU 耗时无关，必须能一眼区分。rr 是 DisplayInfo 的
            // renderFrameRate——真机复现过 refreshRate/mode 都报 120 而派发只有 60，
            // 它是「系统按多少速率服务本应用」在公开通路上的地面真值。
            append(" hz ").append(format1(currentRefreshRate()))
            append('/').append(format1(displayModeRefreshRate))
            append(" rr ").append(format1(cachedRenderRate))
            append('\n')
            append("vs ").append(format1(vsyncWindow.percentile(50.0)))
            // p05 是 vsync 周期的下确界：只要系统偶尔给过 8.3，这里就会出现 8.3。
            // 永远读不到 8.3，才能支持「系统根本不按 120 服务我们」。
            append('/').append(format1(vsyncWindow.percentile(5.0)))
            append(" arm ").append(format1(armWindow.percentile(50.0)))
            append(" p95 ").append(format1(armWindow.percentile(95.0)))
            append(" skip ").append(
                if (callbackCount > 0) format1(100.0 * skipCount / callbackCount) else "n/a"
            ).append('%')
            // 按 HUD 刷新窗口统计而非自启动累计：启动瞬间面板模式尚未稳定时的丢帧
            // 会永久污染累计值，掩盖当前是否真的还在丢。
            callbackCount = 0
            skipCount = 0
            append('\n')
            append("grid ").append(format1(timelineWindows[0].percentile(50.0)))
            append(" lead ").append(format1(timelineWindows[1].percentile(50.0)))
            append(" dl ").append(format1(timelineWindows[2].percentile(50.0)))
            append(" tl ").append(timelineCount)
            append(" work ").append(format1(workWindow.percentile(50.0)))
            append('/').append(format1(workWindow.percentile(95.0)))
            append(" idle ").append(format1(idleWindow.percentile(50.0)))
            append('\n')
            append("drain ").append(format1(glWindows[0].percentile(50.0)))
            append(" phys ").append(format1(glWindows[1].percentile(50.0)))
            append('/').append(format1(glWindows[1].percentile(95.0)))
            append(" build ").append(format1(glWindows[2].percentile(50.0)))
            append('/').append(format1(glWindows[2].percentile(95.0)))
            append(" draw ").append(format1(glWindows[3].percentile(50.0)))
            append(" swap ").append(format1(glWindows[4].percentile(50.0)))
            append('\n')
            append("sample ").append(format1(glBuildWindows[0].percentile(50.0)))
            append('/').append(format1(glBuildWindows[0].percentile(95.0)))
            append(" vtx ").append(format1(glBuildWindows[1].percentile(50.0)))
            append('/').append(format1(glBuildWindows[1].percentile(95.0)))
            append(" sheen ").append(format1(glBuildWindows[2].percentile(50.0)))
            append(" color ").append(format1(glBuildWindows[3].percentile(50.0)))
            append(" optics ").append(format1(glBuildWindows[4].percentile(50.0)))
            append('/').append(format1(glBuildWindows[4].percentile(95.0)))
            // 2026-07-25 掉帧排查：银丝法向距离场（rim）与星芒扫描（star）单列。
            append(" rim ").append(format1(glBuildWindows[9].percentile(50.0)))
            append('/').append(format1(glBuildWindows[9].percentile(95.0)))
            append(" star ").append(format1(glBuildWindows[10].percentile(50.0)))
            append('/').append(format1(glBuildWindows[10].percentile(95.0)))
            append('\n')
            append("prep ").append(format1(glSampleWindows[0].percentile(50.0)))
            append(" field ").append(format1(glSampleWindows[1].percentile(50.0)))
            append('/').append(format1(glSampleWindows[1].percentile(95.0)))
            // limit 段已并回 field 派发（C6），上报值恒为 0；字段与列位保留，
            // 避免牵动 recordGlStages 签名与 HUD 版式。
            append(" limit ").append(format1(glSampleWindows[2].percentile(50.0)))
            append('/').append(format1(glSampleWindows[2].percentile(95.0)))
            append(" fair ").append(format1(glSampleWindows[3].percentile(50.0)))
            append(" slope ").append(format1(glSampleWindows[4].percentile(50.0)))
            append('\n')
            append("steps ").append(format1(glPhysicsWindows[0].percentile(50.0)))
            append(" bc ").append(format1(glPhysicsWindows[2].percentile(50.0)))
            append(" wave ").append(format1(glPhysicsWindows[3].percentile(50.0)))
            append(" surf ").append(format1(glPhysicsWindows[4].percentile(50.0)))
            // compose = perFrame()，Hero/Ambient 的空间采样就在这里，是「帧率与录音
            // 相关」的来源；phys 减去 bc/wave/surf 后剩下的基本都在这一项。
            append(" comp ").append(format1(glPhysicsWindows[5].percentile(50.0)))
            append(" hop ").append(format1(glBuildWindows[5].percentile(50.0)))
            append(" pkt ").append(format1(glBuildWindows[7].percentile(50.0)))
            append(" rs ").append(format1(glBuildWindows[8].percentile(95.0)))
            // 热余量：1.0 表示已到限温。"开头满帧、很快掉到 60" 的另一个候选成因
            // 就是 DVFS/温控回落，必须能与算力不足区分开。
            append(" th ").append(format1(thermalHeadroom()))
            append(" drop ").append(format1(frameWindows[6].percentile(50.0)))
            append("  cpu ").append(FableSolRowParallel.activeWorkerCount + 1)
            append('/').append(FableSolRowParallel.visibleCoreCount)
        }
        listener(text)
    }

    /** 与 thermalHeadroom 同样按 1s 缓存：display 查询也走一次跨进程往返。 */
    private fun currentRefreshRate(): Double {
        val now = System.nanoTime()
        if (!cachedRefreshRate.isNaN() && now - cachedRefreshRateNs < THERMAL_CACHE_NANOS) {
            return cachedRefreshRate
        }
        val display = window?.decorView?.display
        val rate = display?.refreshRate?.toDouble() ?: Double.NaN
        cachedRenderRate = parseRenderFrameRate(display?.toString())
        cachedRefreshRate = rate
        cachedRefreshRateNs = now
        return rate
    }

    /**
     * `DisplayInfo.toString()` 是非系统 uid 读取 renderFrameRate 的唯一公开通路
     * （完整串已在 [start] 落盘）；这里按同一 1s 缓存节奏抽出数值供 HUD 并排显示。
     */
    private fun parseRenderFrameRate(info: String?): Double {
        val match = RENDER_RATE_PATTERN.find(info ?: return Double.NaN) ?: return Double.NaN
        return match.groupValues[1].toDoubleOrNull() ?: Double.NaN
    }

    private fun format1(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.1f", value) else "n/a"

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
            appendMetric("interval", glIntervalWindow)
            for (i in GL_STAGE_NAMES.indices) appendMetric(GL_STAGE_NAMES[i], glWindows[i])
            for (i in GL_PHYSICS_NAMES.indices) appendMetric(GL_PHYSICS_NAMES[i], glPhysicsWindows[i])
            for (i in GL_BUILD_NAMES.indices) appendMetric(GL_BUILD_NAMES[i], glBuildWindows[i])
            for (i in GL_SAMPLE_NAMES.indices) {
                appendMetric("s_" + GL_SAMPLE_NAMES[i], glSampleWindows[i])
            }
        }
        DebugFileLogger.log(LOG_FILE, summary, DEBUG_PREFIX)
    }

    private fun StringBuilder.appendMetric(name: String, window: FableSolMetricWindow) {
        window.percentiles(percentileScratch, 50.0, 95.0, 99.0)
        append(' ').append(name)
            .append("[p50=").append(format(percentileScratch[0]))
            .append(",p95=").append(format(percentileScratch[1]))
            .append(",p99=").append(format(percentileScratch[2])).append(']')
    }

    /**
     * `getThermalHeadroom` 是同步 binder 调用，而 [recordGlStages] 跑在 GL 线程上——
     * 逐帧摘要里直接调它相当于把一次跨进程往返放进渲染关键路径。这里按 1s 缓存；
     * 热状态本来就是秒级变量，系统对该接口的建议采样间隔也是 ≥1s。
     */
    private fun thermalHeadroom(): Double {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Double.NaN
        val now = System.nanoTime()
        val cached = cachedThermalHeadroom
        if (!cached.isNaN() && now - cachedThermalHeadroomNs < THERMAL_CACHE_NANOS) return cached
        val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return Double.NaN
        val value = power.getThermalHeadroom(15).toDouble()
        cachedThermalHeadroom = value
        cachedThermalHeadroomNs = now
        return value
    }

    private fun format(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.3f", value) else "n/a"

    private companion object {
        const val LOG_FILE = "fablesol_frame_perf.log"
        const val DEBUG_PREFIX = "[DEBUG-FABLESOL-PERF]"
        const val WINDOW_SIZE = 240
        const val REPORT_EVERY_FRAMES = 120
        // HUD 显示的是 240 样本分位数，几十帧内几乎不动；每 60 帧刷新一次即可，
        // 既降低 GL 线程上的格式化开销，也减少 UI 线程的 TextView measure/layout。
        const val HUD_EVERY_FRAMES = 60
        const val THERMAL_CACHE_NANOS = 1_000_000_000L
        const val NS_PER_MS = 1_000_000.0
        // 兼容 "renderFrameRate 120.0" 与 "renderFrameRate=120.0" 两种打印格式。
        val RENDER_RATE_PATTERN = Regex("renderFrameRate[^0-9]{0,4}([0-9]+(?:\\.[0-9]+)?)")
        val FRAME_METRIC_NAMES = arrayOf("draw", "sync", "command", "swap", "total", "gpu", "dropped")
        val DRAW_STAGE_NAMES = arrayOf("drain", "physics", "sample", "color", "mesh", "submit_optics")
        val GL_STAGE_NAMES = arrayOf("drain", "physics", "build", "draw", "swap")
        val GL_PHYSICS_NAMES = arrayOf("steps", "bcLayers", "bc", "waves", "surface", "compose")
        val GL_BUILD_NAMES = arrayOf(
            "sample", "vertex", "sheen", "color", "optics", "hops", "events", "packets",
            "repairRows", "rim", "star"
        )
        val GL_SAMPLE_NAMES = arrayOf("prep", "field", "limit", "fair", "slope")
    }
}
