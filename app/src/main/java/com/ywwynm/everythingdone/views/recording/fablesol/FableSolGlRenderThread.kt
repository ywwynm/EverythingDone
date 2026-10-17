package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import androidx.annotation.RequiresApi
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.helpers.DebugFileLogger
import com.ywwynm.everythingdone.model.ThingBackground
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** EGL 生命周期、帧合并和渲染器线程归属。 */
internal class FableSolGlRenderThread(
    context: Context,
    density: Double,
    private val onHdrStatus: (Boolean, String, Boolean, Long) -> Unit,
    private val onFatalError: (String) -> Unit
) {

    private val renderer = FableSolGlRenderer(context, density)
    @Volatile private var acceptingFrames = false
    @Volatile private var animating = false
    private val singleFramePending = AtomicBoolean()
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 一次性的「下一帧已上屏」回调：GL 线程置空取走，主线程执行。 */
    private val onNextPresented = AtomicReference<(() -> Unit)?>(null)
    private var renderWidth = 0
    private var renderHeight = 0
    // 仅 GL 线程读写：同一 surface 周期内的瞬态 swap 失败计数，成功一帧即清零。
    private var transientSwapFailures = 0
    private var submittedFrames = 0L
    private var lastOutputTimestampNs = 0L
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
    @Volatile private var compositionPerformance: FableSolCompositionPerformance? = null
    @Volatile private var lastLoggedHdrSdrRatio = Float.NaN
    @Volatile private var lastHdrRecordingRequested = false
    @Volatile private var lastLoggedHdrStrength = Float.NaN
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var egl: FableSolEglSession? = null
    private var rendererInitialized = false
    private val foreground = FableSolForegroundTexture()
    private var needsForeground = false
    private var directBufferReady: ((FableSolScreenOutput) -> Unit)? = null
    private data class PendingForeground(val frame: FableSolForegroundFrame, val done: () -> Unit,
                                         val perf: FableSolCompositionPerformance?, val queuedAt: Long)
    private val foregroundMailbox = FableSolForegroundMailbox<PendingForeground> {
        it.perf?.count(FableSolPerfCount.FOREGROUND_MAILBOX_DISCARDED)
        it.frame.close()
    }
    private val foregroundDrainPosted = AtomicBoolean()
    private val beforePresentation: () -> Unit = {
        if (consumeForeground()) compositionPerformance?.count(FableSolPerfCount.FOREGROUND_TAKEN_BEFORE_PRESENT)
    }
    private val foregroundDrain = Runnable {
        foregroundDrainPosted.set(false)
        if (acceptingFrames && rendererInitialized) {
            try {
                if (consumeForeground()) {
                    if (directBufferReady != null) finishDirectBuffers()
                    else if (!animating || !presentForegroundOnly()) renderFrame(System.nanoTime())
                }
            } catch (error: Throwable) { fail(error) }
        }
    }
    private var foregroundPollPosted = false
    private val foregroundPoll = Runnable {
        foregroundPollPosted = false
        if (egl != null && rendererInitialized) {
            try {
                foreground.pollRetiredBuffers()
                scheduleForegroundPoll()
            } catch (error: Throwable) { fail(error) }
        }
    }

    /** 独立于海浪帧循环；暂停时也及时归还 HWUI 缓冲，且只保留一个待执行查询。 */
    private fun scheduleForegroundPoll() {
        if (!foregroundPollPosted && foreground.hasRetiredBuffers) {
            foregroundPollPosted = handler?.postDelayed(foregroundPoll, 4L) == true
        }
    }

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
        compositionPerformance?.let { perf ->
            perf.count(FableSolPerfCount.GL_CALLBACK)
            if (!rendering) perf.count(FableSolPerfCount.GL_PACER_SKIP)
            if (vsyncDeltaNs > 0) perf.sample(FableSolPerfMetric.GL_CALLBACK_INTERVAL, vsyncDeltaNs)
            perf.sample(FableSolPerfMetric.GL_CALLBACK_LATE, armNs)
        }
        monitor?.recordFrameCallback(vsyncDeltaNs, armNs, rendering)
        if (rendering) {
            adpf.onFrameStart()
            renderFrame(frameTimeNanos)
        }
        val endNs = System.nanoTime()
        monitor?.recordCallbackSpan(endNs - enterNs, idleNs)
        compositionPerformance?.sample(FableSolPerfMetric.GL_CALLBACK_WORK, endNs - enterNs)
        if (lastCallbackEndNs > 0) compositionPerformance?.sample(FableSolPerfMetric.GL_CALLBACK_IDLE, idleNs)
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
    @RequiresApi(33)
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
            owner.compositionPerformance?.let { perf ->
                perf.sample(FableSolPerfMetric.GL_TIMELINE_GRID, gridNs)
                perf.sample(FableSolPerfMetric.GL_TIMELINE_LEAD, leadNs)
                perf.sample(FableSolPerfMetric.GL_TIMELINE_DEADLINE, deadlineNs)
            }
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

    /**
     * 下一帧真正 `eglSwapBuffers` 成功之后，在主线程回调一次（只回调一次，随即清掉）。
     *
     * 换缓冲格式要整条重建 EGL 会话，旧缓冲在这一刻就没了；只有等新会话交出第一帧，
     * 遮盖层才能撤。用 `View` 侧的 `registerFrameCommitCallback` 证明不了这件事——
     * 那证明的是宿主窗口画了一帧，不是这条 Surface 上有了内容。
     */
    fun notifyOnNextPresented(action: () -> Unit) {
        onNextPresented.set(action)
    }

    private fun presentedOnce() {
        val action = onNextPresented.getAndSet(null) ?: return
        mainHandler.post(action)
    }

    private fun renderFrame(frameTimeNanos: Long) {
        try {
            val session = egl
            if (acceptingFrames && frameTimeNanos != 0L && session != null) {
                val perf = compositionPerformance
                val frameStart = if (perf != null) System.nanoTime() else 0L
                if (needsForeground) foreground.pollRetiredBuffers()
                renderer.setForegroundTexture(foreground.texture())
                val timing = renderer.render(frameTimeNanos, if (needsForeground) beforePresentation else null)
                if (needsForeground) foreground.markSampled()
                // 主线程的 surfaceDestroyed→detachBlocking 与本帧并发时，render 期间
                // acceptingFrames 已被清掉，surface 随时失效——放弃 swap，等 detach 消息收尾。
                if (!acceptingFrames) return
                val swapStart = System.nanoTime()
                if (!swapOutput(session, frameTimeNanos)) {
                    perf?.count(FableSolPerfCount.GL_SWAP_FAILED)
                    handleSwapFailure()
                    return
                }
                transientSwapFailures = 0
                submittedFrames++
                presentedOnce()
                val swapNs = System.nanoTime() - swapStart
                perf?.swapped(timing, frameStart, swapNs, foreground.frame)
                if (needsForeground) foreground.pollRetiredBuffers()
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
                        timing.rimNs,
                        timing.starNs,
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

    /**
     * swap 失败按 EGL 错误分级。EGL_BAD_SURFACE / EGL_BAD_NATIVE_WINDOW 是 surface 瞬态
     * 失效（宿主 stop、切桌面、窗口重建都会销毁 surface，在途帧的 swap 恰好撞上）——
     * 停下帧循环等下一次 attach 即可，surfaceCreated 会带新 surface 回来。此前一次
     * BAD_SURFACE 就永久降级 Canvas 软件渲染：主线程被水面模拟+绘制打满（OPD2515 实测
     * 帧时间 150-200ms、100% janky），而且回退状态随复用的 Dialog 一直存在。只有
     * 非 surface 类错误或短窗口内反复失败才按真实 GL 故障回退。
     */
    private fun handleSwapFailure() {
        val eglError = EGL14.eglGetError()
        val surfaceTransient = eglError == EGL14.EGL_BAD_SURFACE ||
            eglError == EGL14.EGL_BAD_NATIVE_WINDOW
        transientSwapFailures++
        if (surfaceTransient && transientSwapFailures <= MAX_TRANSIENT_SWAP_FAILURES) {
            if (BuildConfig.DEBUG) {
                Log.w(
                    "FableSolSurfProbe",
                    "transient swap failure eglError=0x${Integer.toHexString(eglError)} " +
                        "streak=$transientSwapFailures; waiting for next surface"
                )
            }
            acceptingFrames = false
            removeFrameCallback()
        } else {
            fail(
                IllegalStateException(
                    "eglSwapBuffers failed (eglError=0x${Integer.toHexString(eglError)}, " +
                        "streak=$transientSwapFailures)"
                )
            )
        }
    }

    fun attach(
        surface: Surface,
        width: Int,
        height: Int,
        preferHdr: Boolean,
        initialHdrSdrRatio: Float,
        preferPq: Boolean = false,
        sessionId: Long = 0L,
        directReady: ((FableSolScreenOutput) -> Unit)? = null,
        requireForeground: Boolean = false
    ) {
        if (BuildConfig.DEBUG) {
            Log.i(
                "FableSolSurfProbe",
                "attach ${width}x$height preferHdr=$preferHdr surfaceValid=${surface.isValid}"
            )
        }
        detachBlocking()
        // DISPLAY 优先级（与系统 RenderThread 同档）：默认优先级的连续渲染线程
        // 在大小核调度下会被放到慢核（2026-07-17 OPD2515 实测 build 18ms、~30fps，
        // 手机上同代码 60fps），提档后调度器才把逐帧关键路径放上大核。
        val thread = HandlerThread(
            "FableSolGles",
            Process.THREAD_PRIORITY_DISPLAY
        ).also { it.start() }
        val handler = Handler(thread.looper)
        this.thread = thread
        this.handler = handler
        transientSwapFailures = 0
        submittedFrames = 0L
        lastOutputTimestampNs = 0L
        foregroundMailbox.open()
        acceptingFrames = true
        handler.post {
            try {
                needsForeground = requireForeground
                renderer.setDisplayHdrSdrRatio(initialHdrSdrRatio)
                val session = FableSolEglSession(surface, preferHdr, preferPq)
                egl = session
                renderer.setScreenOutput(session.output)
                renderer.initialize(session.isHdrOutput)
                rendererInitialized = true
                renderer.resize(width, height)
                renderWidth = width; renderHeight = height
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
                onHdrStatus(hdrContent, diagnostic, preferHdr && preferPq, sessionId)
                if (directReady != null) {
                    directBufferReady = directReady
                    finishDirectBuffers()
                }
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    fun resize(width: Int, height: Int) {
        handler?.post {
            if (egl != null && (width != renderWidth || height != renderHeight)) {
                renderer.resize(width, height)
                renderWidth = width; renderHeight = height
            }
        }
    }

    /** 直接图层保留旧缓冲；同一渲染队列重建输出，完成两次提交后才允许交接。 */
    fun replaceDirectSurface(surface: Surface, width: Int, height: Int, preferPq: Boolean,
                             ratio: Float, sessionId: Long,
                             ready: (FableSolScreenOutput) -> Unit) {
        val current = handler ?: return
        current.post {
            try {
                animating = false
                removeFrameCallback()
                releaseOnGlThread(retainForeground = true)
                acceptingFrames = true
                transientSwapFailures = 0
                val session = FableSolEglSession(surface, preferPq, preferPq)
                egl = session
                renderer.setDisplayHdrSdrRatio(ratio)
                renderer.setScreenOutput(session.output)
                renderer.initialize(session.isHdrOutput)
                rendererInitialized = true
                renderer.resize(width, height)
                renderWidth = width; renderHeight = height
                renderer.resetFrameTimeAnchor()
                onHdrStatus(session.isHdrOutput && renderer.isHdrContentEnabled(), session.diagnostic,
                    preferPq, sessionId)
                directBufferReady = ready
                finishDirectBuffers()
            } catch (error: Throwable) { fail(error) }
        }
    }

    /** 海浪与原生控件两者都就绪后才显示候选，换色彩空间时重用最后一份前景。 */
    private fun finishDirectBuffers() {
        val ready = directBufferReady ?: return
        val session = egl ?: return
        if (needsForeground) consumeForeground()
        val buffer = foreground.frame?.buffer
        if (needsForeground && (buffer == null || buffer.width != renderWidth || buffer.height != renderHeight)) return
        val before = submittedFrames
        renderFrame(System.nanoTime())
        renderFrame(System.nanoTime())
        check(submittedFrames >= before + 2) { "direct_first_buffers_missing" }
        val finishStart = if (compositionPerformance != null) System.nanoTime() else 0L
        android.opengl.GLES30.glFinish()
        compositionPerformance?.sample(FableSolPerfMetric.FIRST_BUFFERS_FINISH, System.nanoTime() - finishStart)
        directBufferReady = null
        mainHandler.post { ready(session.output) }
    }

    fun submitForeground(frame: FableSolForegroundFrame, done: () -> Unit) {
        val perf = compositionPerformance
        val queuedAt = if (perf != null) System.nanoTime() else 0L
        val current = handler
        if (current == null || !acceptingFrames ||
            !foregroundMailbox.offer(PendingForeground(frame, done, perf, queuedAt))) {
            perf?.count(FableSolPerfCount.FOREGROUND_REJECTED)
            frame.close()
            return
        }
        if (foregroundDrainPosted.compareAndSet(false, true) && !current.post(foregroundDrain)) {
            foregroundDrainPosted.set(false)
            foregroundMailbox.close()
        }
    }

    /** 同一个 GL 线程可以在消息回调或最终合成前取帧，不再被下一轮重建海浪挡住。 */
    private fun consumeForeground(): Boolean {
        val next = foregroundMailbox.take() ?: return false
        val perf = next.perf
        perf?.sample(FableSolPerfMetric.FOREGROUND_GL_QUEUE, System.nanoTime() - next.queuedAt)
        val start = if (perf != null) System.nanoTime() else 0L
        foreground.update(next.frame)
        renderer.setForegroundTexture(foreground.texture())
        scheduleForegroundPoll()
        perf?.sample(FableSolPerfMetric.FOREGROUND_REPLACE, System.nanoTime() - start)
        perf?.imported(next.frame.sequence)
        val acknowledgedAt = if (perf != null) System.nanoTime() else 0L
        mainHandler.post {
            perf?.sample(FableSolPerfMetric.FOREGROUND_CONSUMED_CALLBACK_QUEUE, System.nanoTime() - acknowledgedAt)
            next.frame.notifyConsumed()
            next.done()
        }
        return true
    }

    private fun presentForegroundOnly(): Boolean {
        if (!needsForeground || !acceptingFrames) return false
        val session = egl ?: return false
        val perf = compositionPerformance
        val start = System.nanoTime()
        if (!renderer.presentCachedScene()) return false
        foreground.markSampled()
        if (!acceptingFrames) return true
        val swapStart = System.nanoTime()
        if (!swapOutput(session, swapStart)) {
            perf?.count(FableSolPerfCount.GL_SWAP_FAILED)
            handleSwapFailure()
            return true
        }
        transientSwapFailures = 0
        submittedFrames++
        presentedOnce()
        perf?.foregroundSwapped(start, System.nanoTime() - swapStart, foreground.frame)
        foreground.pollRetiredBuffers()
        return true
    }

    private fun swapOutput(session: FableSolEglSession, requestedNs: Long): Boolean {
        // UI 合成可以落在两个海浪 vsync 之间，避免下一帧的旧时间戳倒退。
        val timestamp = if (needsForeground) maxOf(requestedNs, lastOutputTimestampNs + 1) else requestedNs
        return session.swapBuffers(timestamp).also { if (it) lastOutputTimestampNs = timestamp }
    }

    /** 只供自有图层使用：GPU 释放确认后才回收 Surface，避免主线程等待或过早释放。 */
    fun detachDirect(released: () -> Unit) {
        val current = handler
        val worker = thread
        acceptingFrames = false
        foregroundMailbox.close()
        animating = false
        onNextPresented.set(null)
        if (current == null) { released(); return }
        current.post {
            try {
                removeFrameCallback()
                vsyncProbe = null
                choreographer = null
                releaseOnGlThread()
            } finally {
                worker?.quitSafely()
                mainHandler.post {
                    if (handler === current) { handler = null; thread = null }
                    released()
                }
            }
        }
    }

    /** 快照在本渲染队列内读同一帧，完成后才交回主线程；不会改变屏幕的颜色空间。 */
    fun copySdrSnapshot(done: (Bitmap?) -> Unit) {
        val currentHandler = handler
        if (currentHandler == null) { mainHandler.post { done(null) }; return }
        if (!currentHandler.post {
                val perf = compositionPerformance
                val start = if (perf != null) System.nanoTime() else 0L
                val snapshot = try {
                    if (acceptingFrames && rendererInitialized && egl != null) {
                        renderer.copySdrSnapshot() ?: run {
                            renderFrame(System.nanoTime())
                            renderer.copySdrSnapshot()
                        }
                    } else null
                } catch (error: Exception) {
                    Log.w("FableSolSurfProbe", "SDR snapshot failed", error)
                    null
                }
                perf?.sample(FableSolPerfMetric.PARTICLE_SNAPSHOT, System.nanoTime() - start)
                mainHandler.post { done(snapshot) }
            }) mainHandler.post { done(null) }
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
                // 循环停过就必然有时间间隔，`lastFrameTimeNanos` 已经是过期的锚。不复位的话
                // 恢复的第一帧 dt = 整段间隔，被 MAX_DT_SECONDS 夹住之后仍是常规步长的 6 倍，
                // 是一次可见跳动。冻结解除、切后台回来、surface 重建走的都是这里，统一在
                // 这一处复位——比在各个发起点各补一次可靠（发起点里至少 setFrozen(false)
                // 那一次会在 handler 已被 detachBlocking 清空时整个丢掉）。
                renderer.resetFrameTimeAnchor()
                postFrameCallback()
            } else {
                removeFrameCallback()
            }
        }
    }

    /**
     * 完全冻结的渲染器一侧。帧循环的启停由 [setAnimating] 负责，两者由
     * `WaveVisualizerFableSolGl.shouldAnimate()` 那道门统一编排；解冻后第一帧的时间锚也在
     * [setAnimating] 里复位，不在这里补——后台解冻时 handler 已被 `detachBlocking()` 清空，
     * 在这里 post 会被整个丢掉。
     */
    fun setFrozen(frozen: Boolean) {
        renderer.setFrozen(frozen)
    }

    /**
     * 冻结态下补画一帧。SurfaceView 的 surface 在窗口不可见时会被销毁，回到前台重建出来的
     * surface 一帧都没画过；不补这一帧，水体位置就是一块空白。
     *
     * 只在帧循环确实停着时才画：循环在跑时下一拍自然会覆盖，重复渲染只是白付一帧。
     */
    fun renderSingleFrame() {
        val current = handler ?: return
        // 首次 attach/resize/可见性会连续请求同一冻结画面，只保留一次待执行绘制。
        if (!singleFramePending.compareAndSet(false, true)) return
        if (!current.post {
            singleFramePending.set(false)
            if (animating || !acceptingFrames) return@post
            renderFrame(System.nanoTime())
        }) singleFramePending.set(false)
    }

    /** 在生产者自己的队列内绑定恢复事务并提交同一帧，不覆盖 HDR 等已有首帧监听器。 */
    fun renderParticleRecoveryFrame(beforeSubmit: () -> Unit, submitted: (Boolean) -> Unit) {
        val current = handler
        if (current == null) { mainHandler.post { submitted(false) }; return }
        val accepted = current.post {
            val result = runCatching {
                if (!acceptingFrames || egl == null) false
                else {
                    beforeSubmit()
                    val previous = submittedFrames
                    renderFrame(System.nanoTime())
                    submittedFrames > previous
                }
            }.onFailure { Log.w("FableSolSurfProbe", "粒子转场恢复帧提交失败", it) }.getOrDefault(false)
            mainHandler.post { submitted(result) }
        }
        if (!accepted) mainHandler.post { submitted(false) }
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

    fun clearPendingAudio() {
        renderer.clearPendingAudio()
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

    fun setCompositionPerformance(performance: FableSolCompositionPerformance?) {
        compositionPerformance = performance
        foreground.performance = performance
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
        foregroundMailbox.close()
        val currentHandler = handler ?: return
        if (BuildConfig.DEBUG) Log.i("FableSolSurfProbe", "detachBlocking")
        acceptingFrames = false
        animating = false
        singleFramePending.set(false)
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
        foregroundMailbox.close()
        val eglError = EGL14.eglGetError()
        val message = error.message ?: error.javaClass.simpleName
        if (BuildConfig.DEBUG) {
            Log.e(
                "FableSolSurfProbe",
                "fatal $message eglError=0x${Integer.toHexString(eglError)}",
                error
            )
            DebugFileLogger.log(
                PERF_LOG_FILE,
                "fatal $message eglError=0x${Integer.toHexString(eglError)}",
                DEBUG_PREFIX,
                startSession = true
            )
        }
        releaseOnGlThread()
        onFatalError(message)
    }

    private fun releaseOnGlThread(retainForeground: Boolean = false) {
        if (!retainForeground) foregroundMailbox.close()
        handler?.removeCallbacks(foregroundDrain)
        foregroundDrainPosted.set(false)
        handler?.removeCallbacks(foregroundPoll)
        foregroundPollPosted = false
        adpf.release()
        directBufferReady = null
        try {
            if (retainForeground) foreground.releaseContext() else foreground.close()
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
        // 同一 surface 周期内允许的瞬态 swap 失败上限；正常的销毁竞争一个周期只会出现一次。
        const val MAX_TRANSIENT_SWAP_FAILURES = 6
        const val PERF_LOG_FILE = "fablesol_frame_perf.log"
        const val DEBUG_PREFIX = "[DEBUG-FABLESOL-GL]"
        const val HDR_LOG_FILE = "fablesol_hdr.log"
        const val HDR_DEBUG_PREFIX = "[DEBUG-FABLESOL-HDR]"
    }
}
