package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.Rect
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import java.time.Duration

/** 主线程只提交显示树；等待 GPU 和读回像素在独立线程完成，避免挡住 ripple。 */
@RequiresApi(29)
internal object ParticleHardwareSnapshot {
    private val main = Handler(Looper.getMainLooper())
    private val worker by lazy {
        Handler(HandlerThread("ParticleSnapshotCopy", Process.THREAD_PRIORITY_DISPLAY)
            .apply { start() }.looper)
    }

    /**
     * [clip] 非空时只画卡片露出来的那一块（坐标以卡片左上角为原点）。离屏节点连同
     * outline、阴影光源一起按它平移，得到的就是屏幕上那一块的像素，不必抓完再拷一次。
     *
     */
    fun capture(view: View, clip: Rect? = null, shadow: ParticleCardSnapshot? = null, done: (Bitmap?) -> Unit) =
        capture(view, clip, shadow, keepHardware = false, done = done)

    /** 粒子材料排除瞬态反馈；动态背景、恢复画面对照仍用完整 capture。 */
    fun captureMaterial(view: View, clip: Rect? = null, shadow: ParticleCardSnapshot? = null, done: (Bitmap?) -> Unit) =
        capture(view, clip, shadow, keepHardware = false, omitFeedback = true, done = done)

    /**
     * 飘散的新建页（API 31+）：不拷回内存，把 ImageReader 缓冲包成硬件位图，供
     * [ParticleDriftHardwareImport] 直接绑定纹理，或供首页承接层的硬件 ImageView 显示。
     * 调用方要保证不读取其像素，也不向软件 Canvas 绘制。
     */
    fun captureKeepingHardware(view: View, done: (Bitmap?) -> Unit) =
        capture(view, null, null, keepHardware = true, done = done)

    /** 安全重建普通控件投影；软件内容不会携带活 SurfaceView 或 Ripple 的 RenderNode。 */
    fun captureSoftwareShadow(view: View, shadow: ParticleCardSnapshot, done: (Bitmap?) -> Unit) {
        val pixels = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val restoreFeedback = ParticleTouchFeedback.omitFromSnapshot(view)
        val drawn = try { view.draw(android.graphics.Canvas(pixels)); true }
        catch (_: Exception) { false }
        finally { restoreFeedback() }
        if (!drawn) { pixels.recycle(); done(null); return }
        capture(view, shadow.bounds, shadow, keepHardware = false, softwareContent = pixels) {
            pixels.recycle(); done(it)
        }
    }

    /** 只渲染窗口根节点的投影，不重绘 Dialog 内容或独立 Surface。 */
    fun captureOuterShadow(view: View, shadow: ParticleCardSnapshot, done: (Bitmap?) -> Unit) {
        val began = System.nanoTime()
        val empty = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val outline = Outline().apply { alpha = 1f; view.outlineProvider?.getOutline(view, this) }
        ParticlePresentationDiagnostics.of(view)?.event("outer_shadow_allocation", mapOf(
            "elapsedMs" to (System.nanoTime() - began) / 1e6))
        capture(view, shadow.bounds, shadow, keepHardware = false, softwareContent = empty) { result ->
            empty.recycle()
            if (result != null) {
                val rect = Rect()
                if (!outline.getRect(rect)) rect.set(0, 0, view.width, view.height)
                val canvas = android.graphics.Canvas(result)
                canvas.translate(-shadow.bounds.left.toFloat(), -shadow.bounds.top.toFloat())
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                }
                canvas.drawRoundRect(android.graphics.RectF(rect), outline.radius.coerceAtLeast(0f),
                    outline.radius.coerceAtLeast(0f), paint)
            }
            done(result)
        }
    }

    private fun capture(view: View, clip: Rect?, shadow: ParticleCardSnapshot?, keepHardware: Boolean,
                        softwareContent: Bitmap? = null,
                        surfaceFrames: Map<View, Bitmap>? = null,
                        omitFeedback: Boolean = false,
                        done: (Bitmap?) -> Unit) {
        if (softwareContent == null && surfaceFrames == null && ParticlePresentationPolicy.of(view).unified &&
            ParticleSnapshot.hasVisibleSurface(view)) {
            ParticleSurfaceFrames.capture(view) { frames ->
                if (frames == null) done(null)
                else capture(view, clip, shadow, keepHardware, surfaceFrames = frames, omitFeedback = omitFeedback) { result ->
                    frames.values.forEach { it.recycle() }; done(result)
                }
            }
            return
        }
        ParticleCaptureDiagnostics.begin(if (keepHardware) "硬件离屏（零拷贝）" else "硬件离屏")
        val diagnostic = ParticlePresentationDiagnostics.of(view)
        val startedAt = System.nanoTime()
        var submittedAt = 0L
        var acquiredAt = 0L
        var copiedAt = 0L
        val clipLeft = clip?.left ?: 0
        val clipTop = clip?.top ?: 0
        val width = clip?.width() ?: view.width
        val height = clip?.height() ?: view.height
        val rotation = shadow?.rotation ?: 0
        val renderWidth = if (rotation % 2 != 0) height else width
        val renderHeight = if (rotation % 2 != 0) width else height
        val node = RenderNode("particleSnapshot")
        val cardNode = if (shadow != null) RenderNode("particleCardWithShadow") else node
        var capturedNodes = emptyList<RenderNode>()
        val borrowedViews = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<View, Boolean>())
        var reader: ImageReader? = null
        var renderer: HardwareRenderer? = null
        var completed = false // 只在主线程读写。
        lateinit var timeout: Runnable
        var restoreFeedback: (() -> Unit)? = null
        fun restoreFeedbackNow() {
            val restore = restoreFeedback
            restoreFeedback = null
            restore?.invoke()
        }
        fun complete(bitmap: Bitmap?) {
            restoreFeedbackNow()
            if (completed) { bitmap?.recycle(); return }
            completed = true
            main.removeCallbacks(timeout)
            diagnostic?.event("hardware_snapshot_timing", mapOf("shadow" to (shadow != null),
                "content" to (softwareContent == null), "ready" to (bitmap != null),
                "submitMs" to submittedAt.takeIf { it != 0L }?.let { (it - startedAt) / 1e6 },
                "acquireMs" to acquiredAt.takeIf { it != 0L }?.let { (it - startedAt) / 1e6 },
                "copyMs" to copiedAt.takeIf { it != 0L && acquiredAt != 0L }?.let { (it - acquiredAt) / 1e6 },
                "totalMs" to (System.nanoTime() - startedAt) / 1e6))
            val retireBegan = System.nanoTime()
            // 与 View.draw 的记录串行；释放后让原 View 检查被借用的子显示列表。
            runCatching { renderer?.destroy() }
            runCatching { node.discardDisplayList() }
            if (cardNode !== node) runCatching { cardNode.discardDisplayList() }
            capturedNodes.forEach { it.discardDisplayList() }
            borrowedViews.forEach(View::invalidate)
            borrowedViews.clear()
            diagnostic?.event("hardware_snapshot_retired", mapOf("elapsedMs" to (System.nanoTime() - retireBegan) / 1e6))
            worker.post { runCatching { reader?.close() } }
            done(bitmap)
        }
        // 失败也恢复真实内容；未发生提交时不能永久持有硬件缓冲。
        timeout = Runnable {
            ParticleCaptureDiagnostics.note("硬件离屏超时 1200ms（未收到可用图像）")
            complete(null)
        }
        try {
            val target = ImageReader.newInstance(renderWidth, renderHeight, PixelFormat.RGBA_8888, 2,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            reader = target
            target.setOnImageAvailableListener({ available ->
                acquiredAt = System.nanoTime()
                val bitmap = runCatching {
                    val image = available.acquireNextImage()
                    if (image == null) {
                        ParticleCaptureDiagnostics.note("硬件离屏 acquireNextImage 为空")
                        return@runCatching null
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            !image.fence.await(Duration.ofMillis(700))) {
                            ParticleCaptureDiagnostics.note("硬件离屏 fence 超时 700ms")
                            return@runCatching null
                        }
                        val buffer = image.hardwareBuffer
                        if (buffer == null) {
                            ParticleCaptureDiagnostics.note("硬件离屏 hardwareBuffer 为空")
                            return@runCatching null
                        }
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, null)
                        buffer.close()
                        if (hardware == null) ParticleCaptureDiagnostics.note("硬件离屏 wrapHardwareBuffer 为空")
                        if (keepHardware && rotation == 0) hardware
                        else try { hardware?.copy(Bitmap.Config.ARGB_8888, true) } finally { hardware?.recycle() }
                    } finally { image.close() }
                }.onFailure {
                    ParticleCaptureDiagnostics.note("硬件离屏读回异常=${it.javaClass.simpleName}")
                }.getOrNull()
                val oriented = if (rotation != 0 && bitmap != null) runCatching {
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                        android.graphics.Matrix().apply { postRotate(-90f * rotation) }, false)
                    if (rotated !== bitmap) bitmap.recycle()
                    rotated
                }.onFailure {
                    bitmap.recycle()
                    ParticleCaptureDiagnostics.note("硬件离屏转回窗口方向异常=${it.javaClass.simpleName}")
                }.getOrNull() else bitmap
                copiedAt = System.nanoTime()
                main.post { complete(oriented) }
            }, worker)
            val hardware = HardwareRenderer().also { renderer = it }
            hardware.setName("particleSnapshot")
            hardware.isOpaque = false
            hardware.setSurface(target.surface)
            node.setPosition(0, 0, renderWidth, renderHeight)
            if (shadow != null) {
                cardNode.setPosition(-clipLeft, -clipTop, view.width - clipLeft, view.height - clipTop)
                cardNode.elevation = shadow.elevation
                cardNode.setAmbientShadowColor(view.outlineAmbientShadowColor)
                cardNode.setSpotShadowColor(view.outlineSpotShadowColor)
            }
            // View.draw 会记录子树，但不会把根 View 自身 RenderNode 的裁剪属性带进来。
            // 媒体卡片使用自定义 Outline，不能从 CardView.radius 或背景形状推断它。
            if (view.clipToOutline || shadow != null) {
                // View.rebuildOutline 在调用 provider 之前把 alpha 初始化为 1。
                // CardView 的 provider 只写几何；直接用默认 alpha=0 会有圆角却没有阴影。
                val outline = Outline().apply { alpha = 1f }
                view.outlineProvider?.getOutline(view, outline)
                // outline 是卡片坐标系里的，节点原点已经挪到可见区左上角，跟着平移。
                if (shadow == null && (clipLeft != 0 || clipTop != 0)) outline.offset(-clipLeft, -clipTop)
                cardNode.setOutline(outline)
                cardNode.setClipToOutline(view.clipToOutline)
            }
            if (omitFeedback) restoreFeedback = ParticleTouchFeedback.omitFromSnapshot(view)
            val canvas = cardNode.beginRecording(if (shadow != null) view.width else width,
                if (shadow != null) view.height else height)
            if (shadow == null && (clipLeft != 0 || clipTop != 0)) canvas.translate(-clipLeft.toFloat(), -clipTop.toFloat())
            if (softwareContent != null) canvas.drawBitmap(softwareContent, 0f, 0f, null)
            else if (surfaceFrames != null) capturedNodes = ParticleViewCaptureTree.record(canvas, view, surfaceFrames,
                borrowedViews = borrowedViews)
            else {
                borrowedViews.add(view)
                ParticleForegroundSurface.withNativeDrawing(view) { view.draw(canvas) }
            }
            cardNode.endRecording()
            if (shadow != null) {
                val parentCanvas = node.beginRecording(renderWidth, renderHeight)
                when (rotation) {
                    1 -> parentCanvas.translate(height.toFloat(), 0f)
                    2 -> parentCanvas.translate(width.toFloat(), height.toFloat())
                    3 -> parentCanvas.translate(0f, width.toFloat())
                }
                parentCanvas.rotate(90f * rotation)
                parentCanvas.enableZ()
                parentCanvas.drawRenderNode(cardNode)
                parentCanvas.disableZ()
                node.endRecording()
            }
            hardware.setContentRoot(node)
            // 光源位置相对节点原点，而节点原点在屏幕上的位置是卡片左上角加上可见区偏移。
            if (shadow != null) {
                val lx = shadow.lightX - clipLeft
                val ly = shadow.lightY - clipTop
                val lightX = when (rotation) { 1 -> height - ly; 2 -> width - lx; 3 -> ly; else -> lx }
                val lightY = when (rotation) { 1 -> lx; 2 -> height - ly; 3 -> width - lx; else -> ly }
                hardware.setLightSourceGeometry(lightX, lightY,
                    shadow.lightZ, shadow.lightRadius)
                hardware.setLightSourceAlpha(shadow.ambientAlpha, shadow.spotAlpha)
            } else {
                ParticleWindowLighting.apply(hardware, view, clipLeft, clipTop)
            }
            hardware.start()
            main.postDelayed(timeout, 1200)
            // sync 完成后节点已交给 RenderThread，之后恢复真实 ripple 不会改变本次提交。
            val status = hardware.createRenderRequest().setWaitForPresent(false).syncAndDraw()
            submittedAt = System.nanoTime()
            if (status != HardwareRenderer.SYNC_OK && status != HardwareRenderer.SYNC_REDRAW_REQUESTED) {
                ParticleCaptureDiagnostics.note("硬件离屏 syncAndDraw=$status")
                complete(null)
            }
        } catch (error: Throwable) {
            ParticleCaptureDiagnostics.note("硬件离屏异常=${error.javaClass.simpleName}")
            complete(null)
        } finally {
            // 包含上面的 sync，但不跨越 Surface PixelCopy、阴影准备或 GPU 读回回调。
            restoreFeedbackNow()
        }
    }
}
