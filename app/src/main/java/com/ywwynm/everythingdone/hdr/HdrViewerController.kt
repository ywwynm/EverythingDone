package com.ywwynm.everythingdone.hdr

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.annotation.RequiresApi
import com.github.chrisbanes.photoview.PhotoView
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.taptransfer.transport.wifi.TapGroupRolePolicy
import java.io.File
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicReference

/** 当前静图的 PQ 输出。PhotoView 仍拥有 Drawable、触摸与矩阵，失败或暂停时它始终可用。 */
@RequiresApi(34)
internal class HdrViewerController(
    private val activity: Activity,
    private val onDetected: (PhotoView, Boolean) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val io = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "hdr-viewer-decode") }.apply {
        removeOnCancelPolicy = true
    }
    private var pending: Future<*>? = null
    private var pendingSnapshot: AtomicReference<Bitmap?>? = null
    private var generation = 0
    private var closed = false
    private var photo: PhotoView? = null
    private var path: String? = null
    private var data: HdrGpuData? = null
    private var loaded = false
    private var active = false
    private var boost = true
    private var videoPlaying = false
    private var windowFrozen = false
    private var surface: HdrProbeSurface? = null
    private var readyTimeout: Runnable? = null
    private var targetStrength = Float.NaN
    private var awaitingPhoto: PhotoView? = null
    private var awaitingProgress: ProgressBar? = null
    private var revealStarted = 0L
    private var revealTimeout: Runnable? = null
    private val values = FloatArray(9)
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        syncTransform()
        true
    }

    init {
        HdrViewerDiagnostics.event(activity, "VIEWER_OPEN", "manufacturer=${Build.MANUFACTURER}; model=${Build.MODEL}; " +
            "display=${Build.DISPLAY}; sdk=${Build.VERSION.SDK_INT}; environmentOverride=${HdrViewerDiagnostics.forceCompatibility}; " +
            "gainHeadroom=4; requestedHeadroom=0; referenceWhiteNits=203")
    }

    /** 在主线程调用；离开页面使旧解码结果失效，短暂暂停保留当前图 GPU 数据。 */
    fun update(view: PhotoView?, source: String?, enabled: Boolean, boostEnabled: Boolean = true,
               prepareWhileInactive: Boolean = false, playingVideo: Boolean = false) {
        if (closed || windowFrozen) return
        if (photo !== view || path != source) {
            removeSurface("page_changed")
            generation++
            cancelPreparation()
            photo = view; path = source; data = null; loaded = false
        }
        active = enabled && view != null && source != null
        boost = boostEnabled
        if (!boost) {
            if (surface?.isFrameReady == false) removeSurface("forced_sdr_before_first_frame")
            revealPhoto("forced_sdr")
        }
        if (videoPlaying != playingVideo) {
            videoPlaying = playingVideo
            HdrViewerDiagnostics.event(activity, "VIEWER_VIDEO_HANDOFF", "playing=$playingVideo; " +
                "surfaceRetained=${surface != null}; ready=${surface?.isFrameReady == true}")
        }
        if (!active) {
            removeSurface("suspended")
            if (!prepareWhileInactive) { revealPhoto("suspended"); return }
        }
        if (data != null) {
            attachSurface()
            animateBoost()
        }
        else if (!loaded && pending == null && view?.drawable is BitmapDrawable) prepare(view, source!!)
    }

    fun isAwaitingFirstFrame(view: PhotoView): Boolean = awaitingPhoto === view

    /** 保留原加载态，直到选定的输出路径有首帧，避免原生图先亮、PQ 的 SDR 初帧又变暗。 */
    private fun awaitFirstFrame(view: PhotoView) {
        if (!boost || awaitingPhoto != null) return
        val progress = (view.parent as? ViewGroup)?.findViewById<ProgressBar>(R.id.pb_image_attachment) ?: return
        if (progress.visibility != View.VISIBLE) return // 已显示的页面及 HDR 开关沿用原有连续过渡。
        awaitingPhoto = view; awaitingProgress = progress
        revealStarted = android.os.SystemClock.uptimeMillis()
        view.alpha = 0f // Drawable 和 PhotoView 矩阵仍保留；不改写 Glide 的共享位图。
        HdrViewerDiagnostics.event(activity, "VIEWER_FIRST_FRAME_WAIT", "nativeGainmap=${(view.drawable as? BitmapDrawable)?.bitmap?.hasGainmap() == true}")
        revealTimeout = Runnable {
            if (awaitingPhoto !== view || closed || windowFrozen) return@Runnable
            generation++; cancelPreparation(); loaded = true; data = null
            removeSurface("reveal_timeout")
            revealPhoto("reveal_timeout")
        }.also { main.postDelayed(it, 5000L) }
    }

    private fun revealPhoto(reason: String) {
        val view = awaitingPhoto ?: return
        revealTimeout?.let(main::removeCallbacks); revealTimeout = null
        awaitingPhoto = null
        view.alpha = if (reason == "pq_ready") 0f else 1f
        awaitingProgress?.visibility = View.GONE; awaitingProgress = null
        HdrViewerDiagnostics.event(activity, "VIEWER_FIRST_FRAME_REVEALED", "reason=$reason; " +
            "waitMs=${android.os.SystemClock.uptimeMillis() - revealStarted}; pqReady=${surface?.isFrameReady == true}")
    }

    private fun prepare(view: PhotoView, source: String, sourceChecked: Boolean = false) {
        val token = generation
        awaitFirstFrame(view)
        val drawable = view.drawable
        // 与原查看器的解码清晰度保持一致，不使用测试页固定的 1280 像素缩略图。
        val maxEdge = maxOf(drawable.intrinsicWidth, drawable.intrinsicHeight).coerceIn(1, 4096)
        val started = System.nanoTime()
        val sourceBitmap = (drawable as BitmapDrawable).bitmap
        if (!sourceChecked && !sourceBitmap.hasGainmap()) {
            // 小范围后台预检先排除普通 JPEG，避免为 SDR 复制整张位图及重复解码。
            pending = io.submit {
                val needsInspection = HdrViewerSource.needsHdrInspection(File(source))
                main.post {
                    if (closed || generation != token || photo !== view) return@post
                    pending = null
                    if (needsInspection) prepare(view, source, sourceChecked = true)
                    else {
                        loaded = true
                        revealPhoto("sdr_jpeg")
                        onDetected(view, false)
                    }
                }
            }
            return
        }
        val sourceSize = "${sourceBitmap.width}x${sourceBitmap.height}"
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        val pixelBudget = minOf(8_000_000L, (available - 32L * 1024 * 1024) / 32)
        val snapshot = AtomicReference<Bitmap?>(null)
        var snapshotStatus = "budget_fallback"
        try {
            val snapshotBytes = sourceBitmap.byteCount.toLong() * (if (HdrProbeImage.supportsCompactBase(sourceBitmap)) 3 else 6) +
                (sourceBitmap.gainmap?.gainmapContents?.byteCount?.toLong() ?: 0L) * 3 + 16L * 1024 * 1024
            if (sourceBitmap.width.toLong() * sourceBitmap.height <= 8_000_000L &&
                maxOf(sourceBitmap.width, sourceBitmap.height) <= 4096 && snapshotBytes < available - 32L * 1024 * 1024) {
                snapshot.set(HdrProbeImage.copyForViewer(sourceBitmap))
                snapshotStatus = "copied"
            }
        } catch (failure: Exception) { snapshotStatus = "copy_fallback_${failure.javaClass.simpleName}" }
        catch (_: OutOfMemoryError) { snapshotStatus = "copy_memory_fallback" }
        val snapshotMs = (System.nanoTime() - started) / 1_000_000
        pendingSnapshot = snapshot
        pending = io.submit {
            var image: HdrProbeImage? = null
            var gpu: HdrGpuData? = null
            var hasHdr = false
            try {
                if (Thread.currentThread().isInterrupted) return@submit
                check(pixelBudget >= 262144) { "memory_budget" }
                val owned = snapshot.getAndSet(null)
                image = if (owned != null) HdrProbeImage.fromOwnedBitmap(File(source), owned)
                    else HdrProbeImage.decode(File(source), maxEdge, hashSource = false, maxPixels = pixelBudget)
                if (owned != null && !image.hasHdrSurfaceData && !Thread.currentThread().isInterrupted) {
                    // 个别格式的 Glide 解码器可能没有保留 Gainmap；仍保留原先 ImageDecoder 的识别范围。
                    image.close(); image = null
                    image = HdrProbeImage.decode(File(source), maxEdge, hashSource = false, maxPixels = pixelBudget)
                }
                val decodedAt = System.nanoTime()
                hasHdr = image.hasHdrSurfaceData
                check(HdrApp5.compatible(drawable.intrinsicWidth, drawable.intrinsicHeight,
                    image.base.width, image.base.height)) { "decoded_geometry_mismatch" }
                if (hasHdr && !Thread.currentThread().isInterrupted) gpu = image.gpuData(pq = true, sdr = false, compactBase = true)
                HdrViewerDiagnostics.event(activity, "VIEWER_SOURCE", "${image.description}\n${image.app5Diagnostics}\n" +
                    "photoBitmap=$sourceSize; " +
                    "hasHdrSurfaceData=$hasHdr; snapshot=$snapshotStatus; snapshotMs=$snapshotMs; decodeMs=${(decodedAt - started) / 1_000_000}; " +
                    "gpuMs=${(System.nanoTime() - decodedAt) / 1_000_000}; elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            } catch (failure: Exception) {
                HdrViewerDiagnostics.event(activity, "VIEWER_DECODE_FALLBACK", "type=${failure.javaClass.simpleName}; fallback=PhotoView")
            } catch (_: OutOfMemoryError) {
                HdrViewerDiagnostics.event(activity, "VIEWER_DECODE_FALLBACK", "reason=memory_budget; fallback=PhotoView")
            } finally {
                image?.close()
                snapshot.getAndSet(null)?.let(HdrProbeImage::recycleOwnedBitmap)
            }
            val prepared = gpu
            val detected = hasHdr
            val postedAt = System.nanoTime()
            main.post {
                if (closed || generation != token || photo !== view) return@post
                pending = null; pendingSnapshot = null; loaded = true; data = prepared
                HdrViewerDiagnostics.event(activity, "VIEWER_DATA_READY", "prepareMs=${(postedAt - started) / 1_000_000}; " +
                    "mainQueueMs=${(System.nanoTime() - postedAt) / 1_000_000}; active=$active; video=$videoPlaying")
                onDetected(view, detected)
                if (active && prepared != null) attachSurface()
                if (prepared == null) revealPhoto("no_pq_data")
            }
        }
    }

    private fun cancelPreparation() {
        pending?.cancel(true); pending = null
        // 任务尚未开始时由此回收；已开始时 getAndSet 保证只由后台任务释放。
        pendingSnapshot?.getAndSet(null)?.let(HdrProbeImage::recycleOwnedBitmap)
        pendingSnapshot = null
    }

    private fun attachSurface() {
        val view = photo ?: return
        val parent = view.parent as? FrameLayout ?: return
        val gpu = data ?: return
        if (surface != null || !active || !boost || !view.isAttachedToWindow) return
        try {
            val layerToken = Any()
            val layer = HdrProbeSurface(activity, gpu, HdrProbeSurface.Output.PQ, 4f, 0f) { name, details, _ ->
                HdrViewerDiagnostics.event(activity, name, details)
                // 解码和 EGL 都不在主线程；回调必须校验它属于当前层。
                main.post {
                    val current = surface
                    if (current == null || current.tag !== layerToken) return@post
                    when (name) {
                        "EGL_REDRAW_READY" -> {
                            readyTimeout?.let(main::removeCallbacks); readyTimeout = null
                            photo?.alpha = 0f // PQ 显示期间保留触摸和矩阵，避免原生 Gainmap 同时参与窗口绘制。
                            revealPhoto("pq_ready")
                            if (active) animateBoost()
                            HdrViewerDiagnostics.event(activity, "VIEWER_PQ_VISIBLE", "readyCallbackQueueMs=${android.os.SystemClock.uptimeMillis() - current.readyUptimeMs}; " +
                                "source=${gpu.description.substringAfter("hdrSurfaceSource=").lineSequence().first()}")
                        }
                        "EGL_FAILED" -> {
                            data = null // 本页不重复失败重建，保留静图及缩放位置。
                            removeSurface("egl_failed")
                        }
                    }
                }
            }
            layer.tag = layerToken
            // 初次揭示直接来自 PQ 的 SDR 基图，不先显示另一条路径的原生图。
            layer.setHdrStrength(0f)
            layer.isClickable = false
            layer.isFocusable = false
            layer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            surface = layer
            animateBoost() // 先登记目标，Callback2 完成后由 EGL 线程直接开始过渡。
            // SurfaceView 默认在窗口下方；在 PhotoView 后绘制透明区域，徽标与视频仍位于其上方。
            parent.addView(layer, parent.indexOfChild(view) + 1, FrameLayout.LayoutParams(-1, -1))
            view.viewTreeObserver.addOnPreDrawListener(preDraw)
            syncTransform()
            readyTimeout = Runnable {
                if (surface === layer && !layer.isFrameReady) {
                    data = null
                    removeSurface("first_frame_timeout")
                }
            }.also { main.postDelayed(it, 5000L) }
        } catch (failure: Exception) {
            data = null
            removeSurface("surface_setup_failed")
            HdrViewerDiagnostics.event(activity, "VIEWER_SURFACE_FALLBACK", "type=${failure.javaClass.simpleName}; fallback=PhotoView")
        } catch (_: OutOfMemoryError) {
            data = null
            removeSurface("surface_memory_budget")
        }
    }

    private fun animateBoost() {
        val layer = surface ?: return
        if (!active) return
        val target = if (boost && !videoPlaying) 1f else 0f
        if (targetStrength == target) return
        targetStrength = target
        HdrViewerDiagnostics.event(activity, "VIEWER_GAIN_TRANSITION", "from=${layer.currentHdrStrength}; to=$target; " +
            "durationMs=180; reuseSurface=true; scheduler=egl_worker")
        layer.animateHdrStrength(target, 180L)
    }

    private fun syncTransform() {
        val view = photo ?: return
        val layer = surface ?: return
        val drawable = view.drawable ?: return
        if (view.width <= 0 || view.height <= 0 || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return
        view.imageMatrix.getValues(values)
        layer.setImageTransform(HdrViewerGeometry.transform(values, drawable.intrinsicWidth, drawable.intrinsicHeight,
            view.width, view.height))
    }

    private fun removeSurface(reason: String) {
        if (reason != "suspended" && reason != "freeze_before_first_frame") revealPhoto(reason)
        surface?.freezeHdrStrength(); targetStrength = Float.NaN
        readyTimeout?.let(main::removeCallbacks); readyTimeout = null
        val layer = surface ?: return
        surface = null // 在 removeView 触发生命周期回调前使迟到消息失效。
        if (reason != "freeze_before_first_frame") photo?.alpha = 1f
        photo?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        (layer.parent as? ViewGroup)?.removeView(layer)
        HdrViewerDiagnostics.event(activity, "VIEWER_PQ_REMOVED", "reason=$reason; fallback=PhotoView")
    }

    /** 暂停不等于窗口已消失：退出动画期间保持最后一帧，不改变增益或撤销输出层。 */
    fun freezeWindow() {
        if (closed || windowFrozen) return
        windowFrozen = true; active = false
        surface?.freezeHdrStrength(); targetStrength = Float.NaN
        if (surface?.isFrameReady != true) removeSurface("freeze_before_first_frame")
        HdrViewerDiagnostics.event(activity, "VIEWER_WINDOW_FROZEN", "ready=${surface?.isFrameReady == true}; " +
            "strength=${surface?.lastSubmittedStrength}; release=onStop")
    }

    fun resumeWindow() { windowFrozen = false }

    /** 系统确认窗口不再可见后释放 Surface；当前页的数据可供前台恢复复用。 */
    fun stopWindow() { removeSurface("window_stopped") }

    fun close() {
        if (closed) return
        closed = true; generation++
        removeSurface("destroyed")
        cancelPreparation()
        io.shutdownNow()
        photo = null; path = null; data = null
    }

    companion object {
        fun supportedEnvironment(): Boolean = Build.VERSION.SDK_INT >= 34 &&
            (HdrViewerDiagnostics.forceCompatibility || TapGroupRolePolicy.isKnownCompatibilityEnvironment(Build.MANUFACTURER, Build.DISPLAY))
    }
}
