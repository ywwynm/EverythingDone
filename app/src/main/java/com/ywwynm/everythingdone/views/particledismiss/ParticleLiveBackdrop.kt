package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import java.time.Duration

/** 与宿主绘制更新的背景；复用渲染器与硬件缓冲，不冻结其它 View、动图或媒体。 */
@RequiresApi(34)
internal class ParticleLiveBackdrop(private val root: ViewGroup, private val excluded: Set<View>,
    private val window: ParticleWindowSurface, private val layer: ParticleGlBitmapLayer,
    private val diagnostic: ParticlePresentationDiagnostics.Run?, private val onFailure: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val node = RenderNode("particleLiveBackground")
    private val renderer = HardwareRenderer()
    private val reader = ImageReader.newInstance(root.width, root.height, PixelFormat.RGBA_8888, 3,
        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
    private var nodes = emptyList<RenderNode>()
    private val contentCache = ParticleViewCaptureTree.Cache()
    private var closed = false
    private var active = false
    private var rendering = false
    private var first: ((Boolean) -> Unit)? = null
    private var waitingFresh: ((Boolean) -> Unit)? = null
    private var frames = 0
    private var requestedNanos = 0L
    private val retainedImages = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var readerClosing = false
    private val timeout = Runnable { fail() }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (active && !rendering && !closed) capture()
        true
    }

    init {
        renderer.setName("particleLiveBackground")
        renderer.isOpaque = false
        renderer.setSurface(reader.surface)
        node.setPosition(0, 0, root.width, root.height)
        renderer.setContentRoot(node)
        ParticleWindowLighting.apply(renderer, root)
        reader.setOnImageAvailableListener({ images ->
            var acquired: android.media.Image? = null
            val bitmap = runCatching {
                images.acquireLatestImage()?.let { image ->
                    acquired = image
                    retainedImages.incrementAndGet()
                    if (Build.VERSION.SDK_INT >= 33 && !image.fence.await(Duration.ofMillis(700))) null
                    else image.hardwareBuffer?.use { Bitmap.wrapHardwareBuffer(it, null) }
                }
            }.getOrNull()
            val held = acquired
            val release = {
                if (held != null) {
                    held.close()
                    if (retainedImages.decrementAndGet() == 0 && readerClosing) worker.post { reader.close() }
                    main.post { if (!closed && !rendering && first != null) capture() }
                }
                Unit
            }
            main.post {
                if (closed) { bitmap?.recycle(); release() }
                else if (bitmap == null) { release(); fail() }
                else window.uploadGlImage(layer, bitmap, 0f, 0f, 1f, release) { ready ->
                    rendering = false; main.removeCallbacks(timeout)
                    if (!closed) {
                        if (!ready) fail()
                        else {
                            frames++
                            if (frames == 1 || frames % 30 == 0) diagnostic?.event("live_backdrop_frame",
                                mapOf("frames" to frames, "captureToTextureMs" to (System.nanoTime() - requestedNanos) / 1e6,
                                    "meaning" to "texture_ready_not_screen_verified"))
                            first?.let { first = null; it(true) }
                            waitingFresh?.let { waitingFresh = null; first = it; capture() }
                        }
                    }
                }
            }
        }, worker)
        renderer.start()
    }

    fun prepare(done: (Boolean) -> Unit) { first = done; capture() }
    /** 返回交接必须取调用之后的新背景，不能复用入场时的缓存。 */
    fun fresh(done: (Boolean) -> Unit) {
        if (closed) { done(false); return }
        if (rendering) waitingFresh = done else { first = done; capture() }
    }
    fun start() {
        if (closed || active) return
        active = true; root.viewTreeObserver.addOnPreDrawListener(preDraw); root.invalidate()
    }
    fun stop() {
        active = false
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(preDraw)
    }
    private fun capture() {
        if (closed || rendering || retainedImages.get() >= 2) return
        if (!root.isAttachedToWindow || root.width != reader.width || root.height != reader.height) { fail(); return }
        rendering = true; main.postDelayed(timeout, 1200)
        requestedNanos = System.nanoTime()
        ParticleSurfaceFrames.capture(root, excluded) { pixels ->
            if (closed) { pixels?.values?.forEach { it.recycle() }; return@capture }
            if (pixels == null) { fail(); return@capture }
            try {
                val previous = nodes
                val began = System.nanoTime()
                val canvas = node.beginRecording(root.width, root.height)
                try { nodes = ParticleViewCaptureTree.record(canvas, root, pixels, excluded, contentCache) }
                finally { node.endRecording() }
                val recorded = System.nanoTime()
                val status = renderer.createRenderRequest().setWaitForPresent(false).syncAndDraw()
                if (frames < 3 || frames % 30 == 0) diagnostic?.event("live_backdrop_capture_cost",
                    mapOf("recordMs" to (recorded - began) / 1e6,
                        "syncMs" to (System.nanoTime() - recorded) / 1e6, "frames" to frames))
                // RenderThread 已同步本次树；位图内容在对应显示列表中持有原生引用。
                pixels.values.forEach { it.recycle() }
                previous.forEach { it.discardDisplayList() }
                if (status != HardwareRenderer.SYNC_OK && status != HardwareRenderer.SYNC_REDRAW_REQUESTED) fail()
            } catch (failure: Exception) {
                pixels.values.forEach { if (!it.isRecycled) it.recycle() }
                diagnostic?.event("live_backdrop_failed", mapOf("error" to failure.toString())); fail()
            }
        }
    }
    private fun fail() {
        val callback = first ?: waitingFresh; first = null; waitingFresh = null
        close()
        if (callback != null) callback(false) else onFailure()
    }
    fun close() {
        if (closed) return
        closed = true; stop(); main.removeCallbacks(timeout)
        diagnostic?.event("live_backdrop_closed", mapOf("frames" to frames))
        val captured = nodes; nodes = emptyList()
        // View.draw 借用真实 View 的 RenderNode。销毁也必须与主线程记录串行，
        // 否则 RecyclerView 换绑时，后台清理可能删除刚生成的显示列表。
        val began = System.nanoTime()
        renderer.destroy()
        node.discardDisplayList()
        captured.forEach { it.discardDisplayList() }
        contentCache.close()
        diagnostic?.event("live_backdrop_retired", mapOf("elapsedMs" to (System.nanoTime() - began) / 1e6))
        worker.post {
            readerClosing = true
            if (retainedImages.get() == 0) reader.close()
        }
    }
    companion object {
        private val worker by lazy { Handler(HandlerThread("ParticleLiveBackgroundCopy").apply { start() }.looper) }
    }
}
