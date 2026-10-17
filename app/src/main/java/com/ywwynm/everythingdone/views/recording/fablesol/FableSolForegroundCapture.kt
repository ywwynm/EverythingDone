package com.ywwynm.everythingdone.views.recording.fablesol

import android.annotation.TargetApi
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareBufferRenderer
import android.graphics.PorterDuff
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import com.ywwynm.everythingdone.BuildConfig
import org.json.JSONObject
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 所有权交给 GL 队列；释放前不得让 HWUI 重用此缓冲。 */
internal class FableSolForegroundFrame(val buffer: HardwareBuffer, val sequence: Long,
                                      val captureStartedNs: Long = 0L,
                                      val input: FableSolPerfInput? = null,
                                      val clock: FableSolClockStamp? = null,
                                      private val consumed: () -> Unit = {},
                                      private val released: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val consumptionNotified = AtomicBoolean()
    /** 由现有 GL 导入回调在主线程通知；不释放纹理或硬件缓冲。 */
    fun notifyConsumed() { if (consumptionNotified.compareAndSet(false, true)) consumed() }
    override fun close() { if (closed.compareAndSet(false, true)) released() }
}

/** 三个 sRGB 缓冲供当前采样、GPU 退休、下一次 HWUI 绘制周转；被阻塞的窗口更新在资源就绪后补捕获。 */
@TargetApi(34)
internal class FableSolForegroundCapture(
    private val owner: FableSolForegroundLayout,
    private val drawChildren: (Canvas) -> Unit,
    private val onFrame: (FableSolForegroundFrame) -> Unit,
    private val onFailure: (String) -> Unit
) : AutoCloseable {
    private class Slot(val buffer: HardwareBuffer, val renderer: HardwareBufferRenderer) {
        var busy = false
        var retired = false
        var busyStartedNs = 0L
        var performance: FableSolCompositionPerformance? = null
        fun close() { renderer.close(); buffer.close() }
    }
    private val main = Handler(Looper.getMainLooper())
    private val node = RenderNode("FableSolNativeForeground").apply { setClipToBounds(false) }
    private val slots = mutableListOf<Slot>()
    private var closed = false
    private var pending = false
    val renderPending: Boolean get() = pending
    private val delivery = FableSolForegroundDelivery()
    private var deferredAtNs = 0L
    private var retryQueuedNs = 0L
    private val retry = FableSolCaptureRetry(
        post = {
            retryQueuedNs = if (owner.performance != null) System.nanoTime() else 0L
            main.post(it)
        },
        remove = { main.removeCallbacks(it) },
        canRetry = {
            !closed && !pending && owner.isAttachedToWindow && !owner.isLayoutRequested &&
                owner.width > 0 && owner.height > 0
        },
        captureLatest = {
            owner.performance?.let {
                it.count(FableSolPerfCount.CAPTURE_RETRY_ATTEMPT)
                it.sample(FableSolPerfMetric.CAPTURE_RETRY_QUEUE, System.nanoTime() - retryQueuedNs)
            }
            draw(fromRetry = true)
        }
    )
    private var sequence = 0L
    private var completed = 0L
    private var lastFenceMs = 0.0
    private var alphaProbe: JSONObject? = null
    private val timeout = Runnable {
        if (!closed && pending) onFailure("foreground_capture_timeout:$sequence")
    }

    fun draw(fromRetry: Boolean = false) {
        if (closed || owner.width < 1 || owner.height < 1 || !owner.isAttachedToWindow) return
        val perf = owner.performance
        if (pending) {
            perf?.count(if (fromRetry) FableSolPerfCount.CAPTURE_RETRY_PENDING else FableSolPerfCount.CAPTURE_PENDING)
            defer(); return
        }
        // 前一份前景等待 GL 不占用本次 HWUI；是否可写仍由 pending 和真实缓冲所有权决定。
        val captureStart = if (perf != null) System.nanoTime() else 0L
        val input = perf?.latestInput
        var submitting: Slot? = null
        try {
            val width = owner.width; val height = owner.height
            slots.filter { it.buffer.width != width || it.buffer.height != height }.toList().forEach {
                it.retired = true
                slots.remove(it)
                if (!it.busy) it.close()
                else retiredSlots += it
            }
            val slot = slots.firstOrNull { !it.busy } ?: run {
                if (slots.size >= BUFFER_LIMIT) {
                    perf?.count(if (fromRetry) FableSolPerfCount.CAPTURE_RETRY_NO_BUFFER else FableSolPerfCount.CAPTURE_NO_BUFFER)
                    defer(); return
                }
                val allocationStart = if (perf != null) System.nanoTime() else 0L
                val buffer = HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1,
                    HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
                val renderer = try { HardwareBufferRenderer(buffer) }
                    catch (error: Exception) { buffer.close(); throw error }
                renderer.setContentRoot(node)
                renderer.setLightSourceAlpha(.039f, .19f)
                val density = owner.resources.displayMetrics.density
                renderer.setLightSourceGeometry(width / 2f, -height.toFloat(), 600f * density, 800f * density)
                Slot(buffer, renderer).also {
                    slots += it
                    perf?.count(FableSolPerfCount.BUFFER_CREATED)
                    perf?.sample(FableSolPerfMetric.CAPTURE_ALLOCATE, System.nanoTime() - allocationStart)
                }
            }
            // 已有遍历先拿到缓冲时取消待执行补偿；两条入口共用同一串行捕获和三缓冲约束。
            retry.captured()
            if (deferredAtNs > 0L) {
                perf?.sample(FableSolPerfMetric.CAPTURE_DEFERRED_WAIT, System.nanoTime() - deferredAtNs)
                deferredAtNs = 0L
            }
            val recordStart = if (perf != null) System.nanoTime() else 0L
            node.setPosition(0, 0, width, height)
            val canvas = node.beginRecording(width, height)
            try {
                // HardwareBufferRenderer 默认保留上帧，必须只清空自己的离屏缓冲。
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                perf?.beginCaptureRecording()
                drawChildren(canvas)
            } finally { perf?.endCaptureRecording(); node.endRecording() }
            perf?.sample(FableSolPerfMetric.CAPTURE_RECORD, System.nanoTime() - recordStart)
            val clock = perf?.clockFrame // drawChildren 已更新真实控件显示列表，不能在绘制前取此标记。
            perf?.clockCaptured(clock, fromRetry)
            val id = ++sequence
            perf?.captureStarted(captureStart, id)
            slot.busy = true
            slot.busyStartedNs = captureStart
            slot.performance = perf
            submitting = slot
            pending = true
            if (fromRetry) perf?.count(FableSolPerfCount.CAPTURE_RETRY_SUBMITTED)
            val submitStart = if (perf != null) System.nanoTime() else 0L
            slot.renderer.obtainRenderRequest().setColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                .draw(worker) { result ->
                    val start = System.nanoTime()
                    perf?.sample(FableSolPerfMetric.HWUI_CALLBACK, start - submitStart)
                    val ready = runCatching {
                        result.fence.use { fence ->
                            result.status == HardwareBufferRenderer.RenderResult.SUCCESS &&
                                (!fence.isValid || fence.await(Duration.ofMillis(700)))
                        }
                    }.getOrDefault(false)
                    val fenceNs = System.nanoTime() - start
                    val waited = fenceNs / 1e6
                    perf?.sample(FableSolPerfMetric.CAPTURE_FENCE, fenceNs)
                    // 仅 debug 的两次稀疏检查，证明离屏缓冲里确实有前景像素；不保存图像。
                    val probe = if (ready && BuildConfig.DEBUG && (id == 1L || id == 16L)) {
                        val probeStart = System.nanoTime()
                        runCatching { sampleAlpha(slot.buffer, id) }.getOrNull().also {
                            perf?.sample(FableSolPerfMetric.ALPHA_PROBE, System.nanoTime() - probeStart)
                        }
                    } else null
                    val queuedAt = if (perf != null) System.nanoTime() else 0L
                    main.post {
                        perf?.sample(FableSolPerfMetric.CAPTURE_MAIN_QUEUE, System.nanoTime() - queuedAt)
                        main.removeCallbacks(timeout)
                        pending = false
                        lastFenceMs = waited
                        if (probe != null) alphaProbe = probe
                        if (closed || !ready) {
                            retry.close()
                            release(slot)
                            if (!closed) {
                                perf?.count(FableSolPerfCount.CAPTURE_FAILED)
                                onFailure("foreground_render_or_fence:$id status=${result.status}")
                            }
                        } else {
                            completed = id
                            perf?.count(FableSolPerfCount.CAPTURE_READY)
                            perf?.sample(FableSolPerfMetric.CAPTURE_READY, System.nanoTime() - captureStart)
                            delivery.submitted(id)
                            val deliveryStartedNs = if (perf != null) System.nanoTime() else 0L
                            onFrame(FableSolForegroundFrame(slot.buffer, id, captureStart, input, clock,
                                consumed = { completeDelivery(id, deliveryStartedNs, perf, consumed = true) }) {
                                val releaseAt = if (perf != null) System.nanoTime() else 0L
                                main.post {
                                    perf?.sample(FableSolPerfMetric.BUFFER_RELEASE_QUEUE, System.nanoTime() - releaseAt)
                                    release(slot)
                                    // 拒收／丢弃没有消费通知，单独记录；不影响后来 HWUI 的状态或超时。
                                    completeDelivery(id, deliveryStartedNs, perf, consumed = false)
                                }
                            })
                            retry.resourceAvailable()
                        }
                    }
                }
            perf?.sample(FableSolPerfMetric.CAPTURE_SUBMIT, System.nanoTime() - submitStart)
            main.postDelayed(timeout, 2000L)
        } catch (error: Throwable) {
            perf?.count(FableSolPerfCount.CAPTURE_FAILED)
            pending = false
            submitting?.busy = false
            retry.close()
            onFailure("foreground_capture:$error")
        }
    }

    private fun defer() {
        if (deferredAtNs == 0L && owner.performance != null) deferredAtNs = System.nanoTime()
        retry.defer()
    }

    private fun completeDelivery(id: Long, startedNs: Long, perf: FableSolCompositionPerformance?, consumed: Boolean) {
        if (!delivery.consumedOrReturned(id)) return
        perf?.count(if (consumed) FableSolPerfCount.CAPTURE_CONSUMED else FableSolPerfCount.CAPTURE_RETURNED_BEFORE_CONSUMED)
        if (startedNs > 0L) perf?.sample(
            if (consumed) FableSolPerfMetric.CAPTURE_CONSUMER_WAIT else FableSolPerfMetric.CAPTURE_UNCONSUMED_RETURN_WAIT,
            System.nanoTime() - startedNs)
    }

    private fun release(slot: Slot) {
        slot.performance?.sample(FableSolPerfMetric.BUFFER_HOLD, System.nanoTime() - slot.busyStartedNs)
        slot.performance?.count(FableSolPerfCount.BUFFER_RELEASED)
        slot.performance = null
        slot.busy = false
        if (closed || slot.retired) { slots.remove(slot); retiredSlots.remove(slot); slot.close() }
        else retry.resourceAvailable()
        cleanupNode()
    }

    // 尺寸变化时旧 GL 帧仍持有缓冲，必须等其归还后再释放，不得提前清理共用显示列表。
    private val retiredSlots = mutableListOf<Slot>()
    private fun cleanupNode() {
        if (closed && slots.isEmpty() && retiredSlots.isEmpty()) {
            node.discardDisplayList()
            owner.invalidateNativeTree()
        }
    }

    fun diagnostics() = JSONObject().put("strategy", "native_ui_same_output_buffer")
        .put("submitted", sequence).put("completed", completed).put("pending", pending)
        .put("buffers", slots.size).put("fenceWaitMs", lastFenceMs).put("alphaProbe", alphaProbe)
        .put("bufferLimit", BUFFER_LIMIT).put("retirement", "gpu_fence_async")
        .put("retirementFence", "after_last_foreground_sample")
        .put("captureRetry", "latest_after_resource_ready")
        .put("retryWaiting", retry.waiting).put("retryQueued", retry.queued)
        .put("captureFlow", "overlap_capture_and_consumer")
        .put("awaitingConsumerSequence", delivery.awaitingSequence)
        .put("awaitingConsumerCount", delivery.pendingCount)

    override fun close() {
        if (closed) return
        closed = true
        retry.close()
        main.removeCallbacks(timeout)
        slots.filter { !it.busy }.toList().forEach { slots.remove(it); it.close() }
        cleanupNode()
    }

    companion object {
        const val BUFFER_LIMIT = 3
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "FableSolForegroundFence") }

        private fun sampleAlpha(buffer: HardwareBuffer, id: Long): JSONObject {
            val hardware = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)))
            val pixels = try { checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false)) }
                finally { hardware.recycle() }
            try {
                var samples = 0; var visible = 0; var opaque = 0
                for (y in 0 until pixels.height step maxOf(1, pixels.height / 96)) {
                    for (x in 0 until pixels.width step maxOf(1, pixels.width / 96)) {
                        samples++
                        val alpha = pixels.getPixel(x, y) ushr 24
                        if (alpha > 8) visible++
                        if (alpha >= 240) opaque++
                    }
                }
                return JSONObject().put("sequence", id).put("samples", samples)
                    .put("visible", visible).put("opaque", opaque)
            } finally { pixels.recycle() }
        }
    }
}
