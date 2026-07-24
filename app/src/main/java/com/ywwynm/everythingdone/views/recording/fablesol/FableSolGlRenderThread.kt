package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.Surface
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.helpers.DebugFileLogger
import com.ywwynm.everythingdone.model.ThingBackground
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** EGL 生命周期、帧合并和渲染器线程归属。 */
internal class FableSolGlRenderThread(
    context: Context,
    density: Double,
    private val onHdrStatus: (Boolean, String) -> Unit,
    private val onFatalError: (String) -> Unit
) {

    private val renderer = FableSolGlRenderer(context, density)
    @Volatile private var acceptingFrames = false
    @Volatile private var animating = false
    // 帧节拍由 GL 线程自己的 Choreographer 驱动。原先在 UI 线程收 vsync、再
    // `handler.post` 到 GL 线程，每帧要多付一次跨线程唤醒与调度延迟；真机实测
    // 分段合计只有 6.4ms、120Hz 预算 8.33ms 仍系统性稳定在两个 vsync，
    // 这一跳是最可能的成因。以下三个字段只在 GL 线程访问。
    private var choreographer: Choreographer? = null
    private var frameCallbackPosted = false
    private var lastVsyncNs = 0L
    private var lastCallbackEndNs = 0L
    // 观测到的 vsync 派发间隔 EMA（新值权重 1/4，约 10 帧收敛）；0 表示循环未运行。
    // UI 线程的降档看门狗读它判断系统是否只按低速率服务本应用
    // （见 WaveVisualizerFableSolGl.maybeRecoverFrameRate）。
    @Volatile private var smoothedVsyncIntervalNs = 0L
    // 节拍器固定按上限 120 计。Choreographer 本来就只在 vsync 触发，60Hz 面板上
    // 自然只有 60 次回调；节拍器唯一的职责是在 >120Hz 面板上封顶，因此它不该、
    // 也不需要跟随当前显示模式——跟随会在面板掉档时把自己也锁到低帧率。
    private val framePacer = FableSolFramePacer(MAX_TARGET_FPS)
    private val adpf = FableSolAdpf(context)
    @Volatile private var monitor: FableSolPerformanceMonitor? = null
    @Volatile private var lastLoggedHdrSdrRatio = Float.NaN
    @Volatile private var lastHdrRecordingRequested = false
    @Volatile private var lastLoggedHdrStrength = Float.NaN
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var egl: FableSolEglSession? = null
    private var rendererInitialized = false

    /**
     * vsync 回调直接落在 GL 线程；**必须先申请下一拍，再渲染**。
     *
     * `Choreographer.postFrameCallback` 在回调体内调用时，`doFrame` 已经把
     * `mFrameScheduled` 清掉，因此会**当场**执行 `scheduleVsyncLocked()` 向
     * SurfaceFlinger 发一次性 vsync 请求；SF 给的是「请求时刻之后的第一个唤醒点」。
     * 若放在约 8ms 的渲染之后才申请，申请时刻已越过下一个唤醒点，只能拿到再下一拍。
     */
    private fun onFrameTick(frameTimeNanos: Long) {
        frameCallbackPosted = false
        if (!(animating && acceptingFrames)) return
        postFrameCallback()
        val enterNs = System.nanoTime()
        val armNs = enterNs - frameTimeNanos
        // idle = 上一次回调结束到本次回调进入之间的空转。它和 work 加起来应约等于 vs；
        // 若 work≈8.5 而 idle≈8.0，说明我们每帧只被叫醒一次、在干等系统，而不是算不过来。
        val idleNs = if (lastCallbackEndNs != 0L) enterNs - lastCallbackEndNs else 0L
        val vsyncDeltaNs = if (lastVsyncNs != 0L) frameTimeNanos - lastVsyncNs else 0L
        lastVsyncNs = frameTimeNanos
        if (vsyncDeltaNs in 1L..500_000_000L) {
            val previous = smoothedVsyncIntervalNs
            smoothedVsyncIntervalNs =
                if (previous == 0L) vsyncDeltaNs else (previous * 3 + vsyncDeltaNs) shr 2
        }
        val rendering = framePacer.shouldRender(frameTimeNanos)
        monitor?.recordFrameCallback(vsyncDeltaNs, armNs, rendering)
        if (rendering) {
            adpf.onFrameStart()
            renderFrame(frameTimeNanos)
        }
        val endNs = System.nanoTime()
        monitor?.recordCallbackSpan(endNs - enterNs, idleNs)
        if (rendering) adpf.reportWork(endNs - enterNs)
        lastCallbackEndNs = endNs
    }

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        onFrameTick(frameTimeNanos)
    }

    /**
     * API 33+ 的 vsync 探针：`FrameData.getFrameTimelines()` 里相邻两条 timeline 的
     * 预期呈现时刻之差，就是 SurfaceFlinger 给**本连接**的 vsync 周期（该周期已把
     * render rate 除数与 per-uid override 都算进去）。这是唯一能公开读到「系统认为
     * 我们该跑多快」的数值。
     *
     * 必须是独立类、且只在版本判断内实例化：SAM/匿名类会在构造期解析
     * `Choreographer$VsyncCallback`，在 API 26–32 上一构造就 NoClassDefFoundError。
     */
    @androidx.annotation.RequiresApi(33)
    private class VsyncProbe(private val owner: FableSolGlRenderThread) :
        Choreographer.VsyncCallback {
        override fun onVsync(data: Choreographer.FrameData) {
            // FrameData / FrameTimeline 出了回调即失效，必须在这里把 long 取干净。
            val timelines = data.frameTimelines
            val count = timelines.size
            val gridNs = if (count >= 2) {
                timelines[1].expectedPresentationTimeNanos -
                    timelines[0].expectedPresentationTimeNanos
            } else {
                -1L
            }
            val frameTimeNanos = data.frameTimeNanos
            val preferred = data.preferredFrameTimeline
            val leadNs = preferred.expectedPresentationTimeNanos - frameTimeNanos
            val deadlineNs = preferred.deadlineNanos - frameTimeNanos
            owner.onFrameTick(frameTimeNanos)
            owner.monitor?.recordVsyncTimeline(gridNs, leadNs, deadlineNs, count)
        }
    }

    // 字段类型必须是 Any?，不能直接写 Choreographer.VsyncCallback。
    private var vsyncProbe: Any? = null

    private fun postFrameCallback() {
        if (frameCallbackPosted) return
        val current = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        frameCallbackPosted = true
        if (Build.VERSION.SDK_INT >= 33) {
            val probe = vsyncProbe ?: VsyncProbe(this).also { vsyncProbe = it }
            current.postVsyncCallback(probe as Choreographer.VsyncCallback)
        } else {
            current.postFrameCallback(frameCallback)
        }
    }

    /** 统一的移除入口：两种回调注册方式必须对称撤销。 */
    private fun removeFrameCallback() {
        val current = choreographer
        if (current != null) {
            val probe = vsyncProbe
            if (Build.VERSION.SDK_INT >= 33 && probe != null) {
                current.removeVsyncCallback(probe as Choreographer.VsyncCallback)
            } else {
                current.removeFrameCallback(frameCallback)
            }
        }
        frameCallbackPosted = false
        lastVsyncNs = 0L
        lastCallbackEndNs = 0L
        smoothedVsyncIntervalNs = 0L
    }

    /** 供降档看门狗读取：观测 vsync 派发间隔（EMA，纳秒）；0 表示未知。 */
    fun observedVsyncIntervalNs(): Long = smoothedVsyncIntervalNs

    private fun renderFrame(frameTimeNanos: Long) {
        try {
            val session = egl
            if (acceptingFrames && frameTimeNanos != 0L && session != null) {
                val timing = renderer.render(frameTimeNanos)
                val swapStart = System.nanoTime()
                check(session.swapBuffers(frameTimeNanos)) { "eglSwapBuffers failed" }
                val swapNs = System.nanoTime() - swapStart
                monitor?.recordGlStages(
                        timing.drainNs,
                        timing.physicsNs,
                        timing.buildNs,
                        timing.drawNs,
                        swapNs,
                        timing.physicsSubsteps,
                        timing.boundaryLayers,
                        timing.boundaryNs,
                        timing.waveNs,
                        timing.surfaceNs,
                        timing.composeNs,
                        timing.sampleNs,
                        timing.vertexNs,
                        timing.sheenNs,
                        timing.colorNs,
                        timing.opticsNs,
                        timing.audioFrames,
                        timing.audioEvents,
                        timing.packetCount,
                        timing.repairRows,
                        timing.samplePrepNs,
                        timing.sampleFieldNs,
                        timing.sampleLimitNs,
                        timing.sampleFairNs,
                    timing.sampleSlopeNs
                )
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    fun attach(
        surface: Surface,
        width: Int,
        height: Int,
        preferHdr: Boolean,
        initialHdrSdrRatio: Float
    ) {
        detachBlocking()
        // DISPLAY 优先级（与系统 RenderThread 同档）：默认优先级的连续渲染线程
        // 在大小核调度下会被放到慢核（2026-07-17 OPD2515 实测 build 18ms、~30fps，
        // 手机上同代码 60fps），提档后调度器才把逐帧关键路径放上大核。
        val thread = HandlerThread(
            "FableSolGles",
            android.os.Process.THREAD_PRIORITY_DISPLAY
        ).also { it.start() }
        val handler = Handler(thread.looper)
        this.thread = thread
        this.handler = handler
        acceptingFrames = true
        handler.post {
            try {
                renderer.setDisplayHdrSdrRatio(initialHdrSdrRatio)
                val session = FableSolEglSession(surface, preferHdr)
                egl = session
                renderer.initialize(session.isHdrOutput)
                rendererInitialized = true
                renderer.resize(width, height)
                val hdrContent = session.isHdrOutput && renderer.isHdrContentEnabled()
                val diagnostic = if (session.isHdrOutput && !hdrContent) {
                    "${session.diagnostic}; rgba16f-scene-fallback"
                } else {
                    session.diagnostic
                }
                if (BuildConfig.DEBUG) {
                    DebugFileLogger.log(
                        HDR_LOG_FILE,
                        "active=$hdrContent ratio=$initialHdrSdrRatio $diagnostic",
                        HDR_DEBUG_PREFIX,
                        startSession = true
                    )
                }
                onHdrStatus(hdrContent, diagnostic)
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    fun resize(width: Int, height: Int) {
        handler?.post {
            if (egl != null) renderer.resize(width, height)
        }
    }

    /**
     * 开/关自驱帧循环。View 只在可见性、附着状态变化时调用一次，不再逐帧参与。
     */
    fun setAnimating(enabled: Boolean) {
        handler?.post {
            if (animating == enabled) return@post
            animating = enabled
            if (enabled) {
                framePacer.reset()
                postFrameCallback()
            } else {
                removeFrameCallback()
            }
        }
    }

    fun setThingBackground(background: ThingBackground) {
        renderer.setThingBackground(background)
    }

    fun setGravity(x: Float, y: Float, z: Float) {
        renderer.setGravity(x, y, z)
    }

    fun onAudioFrames(frames: List<FableSolFeatureFrame>, events: List<FableSolEvent>) {
        renderer.onAudioFrames(frames, events)
    }

    fun setTuningValue(key: String, value: Double) {
        renderer.setTuningValue(key, value)
    }

    fun beginBackgroundTransition(background: ThingBackground) {
        renderer.beginBackgroundTransition(background)
    }

    fun setContentVerticalOffsetDp(offsetDp: Float) {
        renderer.setContentVerticalOffsetDp(offsetDp)
    }

    fun setBottomCornerRadiusPx(radiusPx: Float) {
        renderer.setBottomCornerRadiusPx(radiusPx)
    }

    fun setSimulationPaused(paused: Boolean) {
        renderer.setSimulationPaused(paused)
    }

    fun setPerformanceMonitor(monitor: FableSolPerformanceMonitor?) {
        this.monitor = monitor
    }

    fun setPresentationAlpha(alpha: Float) {
        renderer.setPresentationAlpha(alpha)
    }

    fun setHdrRecordingRequested(requested: Boolean) {
        renderer.setHdrRecordingRequested(requested)
        if (BuildConfig.DEBUG && requested != lastHdrRecordingRequested) {
            lastHdrRecordingRequested = requested
            DebugFileLogger.log(
                HDR_LOG_FILE,
                "recording=$requested",
                HDR_DEBUG_PREFIX,
                startSession = true
            )
        }
    }

    fun setDisplayHdrSdrRatio(ratio: Float) {
        renderer.setDisplayHdrSdrRatio(ratio)
        if (BuildConfig.DEBUG && shouldLogHdrRatio(ratio)) {
            lastLoggedHdrSdrRatio = ratio
            DebugFileLogger.log(
                HDR_LOG_FILE,
                "display-ratio=$ratio",
                HDR_DEBUG_PREFIX,
                startSession = true
            )
        }
    }

    fun setHdrStrength(strength: Float) {
        renderer.setHdrStrength(strength)
        if (BuildConfig.DEBUG && strength != lastLoggedHdrStrength) {
            lastLoggedHdrStrength = strength
            DebugFileLogger.log(
                HDR_LOG_FILE,
                "strength=$strength",
                HDR_DEBUG_PREFIX,
                startSession = true
            )
        }
    }

    private fun shouldLogHdrRatio(ratio: Float): Boolean {
        val previous = lastLoggedHdrSdrRatio
        if (!previous.isFinite()) return ratio.isFinite()
        if (!ratio.isFinite()) return true
        return abs(previous - ratio) >= 0.05f ||
            (previous <= 1.01f) != (ratio <= 1.01f)
    }

    fun detachBlocking() {
        val currentHandler = handler ?: return
        acceptingFrames = false
        animating = false
        val latch = CountDownLatch(1)
        currentHandler.post {
            removeFrameCallback()
            vsyncProbe = null
            choreographer = null
            releaseOnGlThread()
            latch.countDown()
        }
        latch.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun fail(error: Throwable) {
        acceptingFrames = false
        val message = error.message ?: error.javaClass.simpleName
        if (BuildConfig.DEBUG) {
            DebugFileLogger.log(
                PERF_LOG_FILE,
                "fatal $message",
                DEBUG_PREFIX,
                startSession = true
            )
        }
        releaseOnGlThread()
        onFatalError(message)
    }

    private fun releaseOnGlThread() {
        adpf.release()
        try {
            if (rendererInitialized) {
                renderer.release()
                rendererInitialized = false
            }
        } finally {
            egl?.release()
            egl = null
        }
    }

    private companion object {
        const val RELEASE_TIMEOUT_MS = 750L
        const val MAX_TARGET_FPS = 120.0
        const val PERF_LOG_FILE = "fablesol_frame_perf.log"
        const val DEBUG_PREFIX = "[DEBUG-FABLESOL-GL]"
        const val HDR_LOG_FILE = "fablesol_hdr.log"
        const val HDR_DEBUG_PREFIX = "[DEBUG-FABLESOL-HDR]"
    }
}
