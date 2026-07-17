package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.helpers.DebugFileLogger
import com.ywwynm.everythingdone.model.ThingBackground
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** EGL 生命周期、帧合并和渲染器线程归属。 */
internal class FableSolGlRenderThread(
    context: Context,
    density: Double,
    private val onHdrStatus: (Boolean, String) -> Unit,
    private val onFatalError: (String) -> Unit
) {

    private val renderer = FableSolGlRenderer(context, density)
    private val latestFrameTime = AtomicLong(0L)
    private val renderQueued = AtomicBoolean(false)
    @Volatile private var acceptingFrames = false
    @Volatile private var monitor: FableSolPerformanceMonitor? = null
    @Volatile private var lastLoggedHdrSdrRatio = Float.NaN
    @Volatile private var lastHdrRecordingRequested = false
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var egl: FableSolEglSession? = null
    private var rendererInitialized = false

    private val renderRunnable = object : Runnable {
        override fun run() {
            try {
                val frameTime = latestFrameTime.getAndSet(0L)
                val session = egl
                if (acceptingFrames && frameTime != 0L && session != null) {
                    val timing = renderer.render(frameTime)
                    val swapStart = System.nanoTime()
                    check(session.swapBuffers(frameTime)) { "eglSwapBuffers failed" }
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
                        timing.composeNs
                    )
                }
            } catch (error: Throwable) {
                fail(error)
            } finally {
                renderQueued.set(false)
                if (acceptingFrames && latestFrameTime.get() != 0L &&
                    renderQueued.compareAndSet(false, true)
                ) {
                    handler?.post(this)
                }
            }
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
        val thread = HandlerThread("FableSolGles").also { it.start() }
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

    fun requestRender(frameTimeNanos: Long) {
        if (!acceptingFrames) return
        latestFrameTime.set(frameTimeNanos)
        if (renderQueued.compareAndSet(false, true)) handler?.post(renderRunnable)
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
        latestFrameTime.set(0L)
        val latch = CountDownLatch(1)
        currentHandler.post {
            releaseOnGlThread()
            latch.countDown()
        }
        latch.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        thread?.quitSafely()
        thread = null
        handler = null
        renderQueued.set(false)
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
        const val PERF_LOG_FILE = "fablesol_frame_perf.log"
        const val DEBUG_PREFIX = "[DEBUG-FABLESOL-GL]"
        const val HDR_LOG_FILE = "fablesol_hdr.log"
        const val HDR_DEBUG_PREFIX = "[DEBUG-FABLESOL-HDR]"
    }
}
