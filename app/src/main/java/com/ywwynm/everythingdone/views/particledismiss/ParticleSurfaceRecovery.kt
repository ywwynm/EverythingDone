package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.PixelCopy
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import java.time.Duration
import java.util.concurrent.Executors

/** 自有生产者可以显式提交恢复帧；只解冻粒子捕获，不改变媒体自身播放状态。 */
interface ParticleSurfaceRedrawParticipant {
    fun redrawForParticleRecovery(beforeSubmit: () -> Unit, submitted: (Boolean) -> Unit)
    fun acceptsParticleRecoveryBuffer(bitmap: Bitmap): Boolean
}

/** 在独立 Surface 覆盖范围短暂保留最终合成图，直到真实窗口与生产者缓冲都就绪。 */
@RequiresApi(34)
internal class ParticleSurfaceRecovery private constructor(
    private val root: View,
    private val entries: List<Entry>,
    private val diagnostic: ParticlePresentationDiagnostics.Run?
) {
    private data class Entry(val surface: SurfaceView, val rect: Rect, val pixels: Bitmap,
                             val cover: BitmapDrawable) {
        var generation = 0
        val lifecycle = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { generation++ }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { generation++ }
            override fun surfaceDestroyed(holder: SurfaceHolder) { generation++ }
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private var shown = false
    private var closed = false
    private var finishing = false
    private var primed = false
    private val pendingFrames = BooleanArray(entries.size)
    private val readyFrames = BooleanArray(entries.size)
    private val frameGenerations = IntArray(entries.size)
    private var completion: (() -> Unit)? = null
    private var restorePending: (() -> Unit)? = null
    private val barrier = ParticleSurfaceReadyBarrier(entries.size) { finish("buffers_ready") }
    private val timeout = Runnable { finish("timeout_unverified") }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { close("window_detached") }
    }

    init {
        root.addOnAttachStateChangeListener(attachment)
        entries.forEach { it.surface.holder.addCallback(it.lifecycle) }
    }

    /** 粒子仍在播放时预备冻结态恢复帧，减少真实窗口恢复之后的生产者排队。 */
    fun prime() {
        if (closed || primed || !root.isAttachedToWindow) return
        primed = true
        diagnostic?.event("surface_recovery_prime_requested")
        entries.forEachIndexed { index, entry -> requestFrame(index, entry) }
    }

    fun reveal(restore: () -> Unit, resume: () -> Unit, done: () -> Unit) {
        if (closed || shown || !root.isAttachedToWindow) { restore(); resume(); done(); return }
        shown = true
        completion = done
        entries.forEach { root.overlay.add(it.cover) }
        diagnostic?.event("surface_recovery_cover_shown", mapOf("surfaces" to entries.size))
        main.postDelayed(timeout, 1800)
        var restored = false
        val restoreOnce = { if (!restored) { restored = true; restorePending = null; restore(); resume() } }
        restorePending = restoreOnce
        ParticleHostPresentation.afterChange(root, diagnostic, "dialog_restore_covered", restoreOnce) { presented ->
            if (closed || finishing) return@afterChange
            restoreOnce()
            entries.forEachIndexed { index, entry ->
                if (readyFrames[index] && (entry.generation != frameGenerations[index] ||
                        !entry.surface.holder.surface.isValid)) {
                    readyFrames[index] = false; pendingFrames[index] = false
                    barrier.surfaceReplaced(index)
                    diagnostic?.event("surface_recovery_recreated", mapOf("surface" to index))
                }
            }
            if (presented) barrier.windowReady()
            else if (Build.VERSION.SDK_INT == 34) {
                // Android 14 没有公开呈现 fence；用宿主提交回调衔接，不空等超时。
                ParticleHostFrame.after(root, diagnostic, "surface_recovery_api34_commit") {
                    if (!closed && !finishing) barrier.windowReady()
                }
            }
            else diagnostic?.event("surface_recovery_waiting", mapOf("reason" to "window_present_unverified"))
            entries.forEachIndexed { index, entry -> requestFrame(index, entry) }
        }
    }

    private fun requestFrame(index: Int, entry: Entry) {
        if (closed || finishing || pendingFrames[index] || readyFrames[index]) return
        val surface = entry.surface
        val currentRect = boundsInRoot(root, surface, diagnostic, if (shown) "restore" else "prime")
        if (currentRect != entry.rect) {
            diagnostic?.event("surface_recovery_unavailable", mapOf("surface" to index, "reason" to "geometry_changed"))
            frameFailed(index, "geometry_changed"); return
        }
        if (!surface.isAttachedToWindow || !surface.holder.surface.isValid) {
            diagnostic?.event("surface_recovery_waiting", mapOf("surface" to index, "reason" to "surface_invalid"))
            main.postDelayed({ requestFrame(index, entry) }, 32)
            return
        }
        pendingFrames[index] = true
        frameGenerations[index] = entry.generation
        val producer = surface as? ParticleSurfaceRedrawParticipant
        if (producer == null) {
            // 未接入生产者的表面只能验证现有缓冲，报告不把它标成恢复新帧。
            diagnostic?.event("surface_recovery_existing_buffer", mapOf("surface" to index))
            probeBuffer(index, entry, fresh = false)
            return
        }
        try {
            if (Build.VERSION.SDK_INT < 35) {
                producer.redrawForParticleRecovery({}) { submitted ->
                    diagnostic?.event("surface_recovery_redraw_submitted", mapOf("surface" to index,
                        "submitted" to submitted, "basis" to "producer_submit_without_present_fence"))
                    if (submitted) probeBuffer(index, entry, fresh = true)
                    else frameFailed(index, "redraw_failed")
                }
                return
            }
            val transferred = java.util.concurrent.atomic.AtomicBoolean()
            val transaction = SurfaceControl.Transaction().addTransactionCompletedListener(presentationWorker) { stats ->
                val presented = runCatching {
                    stats.presentFence.use { it.isValid && it.await(Duration.ofMillis(1000)) }
                }.getOrDefault(false)
                main.post {
                    if (!closed && !finishing) {
                        diagnostic?.event("surface_recovery_buffer_presented", mapOf("surface" to index,
                            "validAndSignaled" to presented))
                        if (presented) probeBuffer(index, entry, fresh = true)
                        else frameFailed(index, "present_unverified")
                    }
                }
            }
            // 此生产者由应用请求绘制；在请求前绑定下一张缓冲，避免选中旧帧。
            producer.redrawForParticleRecovery({
                try { surface.applyTransactionToFrame(transaction); transferred.set(true) }
                finally { transaction.close() }
            }) { submitted ->
                if (!transferred.get()) transaction.close()
                diagnostic?.event("surface_recovery_redraw_submitted", mapOf("surface" to index,
                    "submitted" to submitted))
                if (!submitted) frameFailed(index, "redraw_failed")
            }
        } catch (failure: Exception) {
            diagnostic?.event("surface_recovery_failed", mapOf("surface" to index, "error" to failure.toString()))
            frameFailed(index, "redraw_exception")
        }
    }

    private fun frameFailed(index: Int, reason: String) {
        pendingFrames[index] = false
        if (shown) finish(reason)
        else diagnostic?.event("surface_recovery_prime_failed", mapOf("surface" to index, "reason" to reason))
    }

    private fun probeBuffer(index: Int, entry: Entry, fresh: Boolean) {
        if (closed || finishing) return
        val surface = entry.surface
        val bitmap = Bitmap.createBitmap(surface.width.coerceAtLeast(1), surface.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surface, bitmap, { status ->
                try {
                    if (closed || finishing) return@request
                    val producer = surface as? ParticleSurfaceRedrawParticipant
                    val valid = status == PixelCopy.SUCCESS && surface.holder.surface.isValid &&
                        entry.generation == frameGenerations[index] &&
                        (producer?.acceptsParticleRecoveryBuffer(bitmap) ?: true)
                    diagnostic?.event("surface_recovery_buffer_checked", mapOf("surface" to index,
                        "status" to status, "accepted" to valid, "freshFrame" to fresh,
                        "width" to bitmap.width, "height" to bitmap.height,
                        "meaning" to "producer_buffer_not_screen_verified"))
                    if (valid) {
                        readyFrames[index] = true; pendingFrames[index] = false
                        ParticlePresentationDiagnostics.sample(diagnostic, "surface-recovery-$index", bitmap)
                        barrier.surfaceReady(index)
                    } else { pendingFrames[index] = false; main.postDelayed({ requestFrame(index, entry) }, 32) }
                } finally { bitmap.recycle() }
            }, main)
        } catch (failure: Exception) {
            bitmap.recycle()
            pendingFrames[index] = false
            diagnostic?.event("surface_recovery_copy_failed", mapOf("surface" to index, "error" to failure.toString()))
            main.postDelayed({ requestFrame(index, entry) }, 32)
        }
    }

    private fun finish(reason: String) {
        if (closed || finishing) return
        finishing = true
        barrier.close()
        main.removeCallbacks(timeout)
        diagnostic?.event("surface_recovery_ready", mapOf("reason" to reason,
            "verifiedBuffers" to (reason == "buffers_ready"),
            "presentationBasis" to if (Build.VERSION.SDK_INT >= 35) "present_fence" else "host_commit_and_producer_submit"))
        ParticleWindowFrameSwitch.run(root, diagnostic, "surface_cover_remove", {
            entries.forEach { root.overlay.remove(it.cover) }
        }) { _ -> close(reason) }
    }

    fun close(reason: String) {
        if (closed) return
        closed = true; barrier.close(); main.removeCallbacks(timeout)
        restorePending?.invoke(); restorePending = null
        root.removeOnAttachStateChangeListener(attachment)
        entries.forEach { it.surface.holder.removeCallback(it.lifecycle) }
        entries.forEach { root.overlay.remove(it.cover) }
        diagnostic?.event("surface_recovery_closed", mapOf("reason" to reason))
        // 等下一次宿主提交再释放位图，避免 RenderThread 尚持有旧显示列表。
        ParticleHostFrame.after(root, diagnostic, "surface_cover_cleanup") { entries.forEach { it.pixels.recycle() } }
        completion?.let { completion = null; it() }
    }

    companion object {
        private val presentationWorker = Executors.newSingleThreadExecutor { Thread(it, "ParticleSurfacePresent") }

        internal fun boundsInRoot(root: View, surface: SurfaceView,
                                  diagnostic: ParticlePresentationDiagnostics.Run?, stage: String): Rect? {
            val rect = Rect()
            if (surface.rootView !== root.rootView || !surface.getGlobalVisibleRect(rect)) {
                diagnostic?.event("surface_recovery_skipped", mapOf("stage" to stage, "reason" to "invisible_or_transformed"))
                return null
            }
            val global = rect.toShortString()
            val rootAt = IntArray(2).also(root::getLocationOnScreen)
            val windowAt = IntArray(2).also(surface.rootView::getLocationOnScreen)
            // global 是 ViewRoot 坐标；先补窗口屏幕偏移，再转到截图根节点局部。
            rect.offset(windowAt[0] - rootAt[0], windowAt[1] - rootAt[1])
            val local = rect.toShortString()
            val intersects = rect.intersect(0, 0, root.width, root.height)
            diagnostic?.event("surface_recovery_geometry", mapOf("stage" to stage, "global" to global,
                "windowScreen" to windowAt.toList(), "rootScreen" to rootAt.toList(), "local" to local,
                "clipped" to if (intersects) rect.toShortString() else "empty"))
            if (!intersects) diagnostic?.event("surface_recovery_skipped", mapOf("stage" to stage, "reason" to "outside_capture"))
            return rect.takeIf { intersects }
        }

        fun prepare(root: View, snapshot: Bitmap, diagnostic: ParticlePresentationDiagnostics.Run?): ParticleSurfaceRecovery? {
            if (snapshot.width != root.width || snapshot.height != root.height) {
                diagnostic?.event("surface_recovery_unavailable", mapOf("reason" to "snapshot_geometry")); return null
            }
            val surfaces = mutableListOf<SurfaceView>()
            fun visit(view: View) {
                if (view.visibility != View.VISIBLE || view is ParticleOverlaySurfaceView) return
                if (view is SurfaceView) surfaces += view
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            visit(root)
            diagnostic?.event("surface_recovery_discovered", mapOf("surfaces" to surfaces.size))
            val entries = mutableListOf<Entry>()
            try {
                for (surface in surfaces) {
                    val rect = boundsInRoot(root, surface, diagnostic, "prepare") ?: continue
                    val pixels = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
                    Canvas(pixels).drawBitmap(snapshot, rect, Rect(0, 0, pixels.width, pixels.height), null)
                    val cover = BitmapDrawable(root.resources, pixels).apply { bounds = rect; isFilterBitmap = false }
                    entries += Entry(surface, rect, pixels, cover)
                }
                if (entries.isEmpty()) {
                    diagnostic?.event("surface_recovery_unavailable", mapOf("reason" to "no_usable_region", "discovered" to surfaces.size))
                    return null
                }
                diagnostic?.event("surface_recovery_prepared", mapOf("surfaces" to entries.size))
                return ParticleSurfaceRecovery(root, entries, diagnostic)
            } catch (failure: Exception) {
                entries.forEach { it.pixels.recycle() }
                diagnostic?.event("surface_recovery_failed", mapOf("error" to failure.toString()))
                return null
            }
        }
    }
}
