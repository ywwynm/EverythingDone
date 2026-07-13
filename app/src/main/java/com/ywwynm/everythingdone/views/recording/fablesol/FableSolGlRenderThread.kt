package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.helpers.DebugFileLogger
import com.ywwynm.everythingdone.model.ThingBackground
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** EGL 生命周期、帧合并和渲染器线程归属。 */
internal class FableSolGlRenderThread(
    context: Context,
    density: Double,
    private val onFatalError: (String) -> Unit
) {

    private val renderer = FableSolGlRenderer(context, density)
    private val latestFrameTime = AtomicLong(0L)
    private val renderQueued = AtomicBoolean(false)
    @Volatile private var acceptingFrames = false
    @Volatile private var monitor: FableSolPerformanceMonitor? = null
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

    fun attach(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        detachBlocking()
        val thread = HandlerThread("FableSolGles").also { it.start() }
        val handler = Handler(thread.looper)
        this.thread = thread
        this.handler = handler
        acceptingFrames = true
        handler.post {
            try {
                egl = FableSolEglSession(surfaceTexture)
                renderer.initialize()
                rendererInitialized = true
                renderer.resize(width, height)
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

    fun setPerformanceMonitor(monitor: FableSolPerformanceMonitor?) {
        this.monitor = monitor
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
    }
}
