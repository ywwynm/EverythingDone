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

    /**
     * 飘散的新建页（API 31+）：不拷回内存，把 ImageReader 缓冲包成硬件位图，供
     * [ParticleDriftHardwareImport] 直接绑定纹理，或供首页承接层的硬件 ImageView 显示。
     * 调用方要保证不读取其像素，也不向软件 Canvas 绘制。
     */
    fun captureKeepingHardware(view: View, done: (Bitmap?) -> Unit) =
        capture(view, null, null, keepHardware = true, done = done)

    private fun capture(view: View, clip: Rect?, shadow: ParticleCardSnapshot?, keepHardware: Boolean,
                        done: (Bitmap?) -> Unit) {
        ParticleCaptureDiagnostics.begin(if (keepHardware) "硬件离屏（零拷贝）" else "硬件离屏")
        val clipLeft = clip?.left ?: 0
        val clipTop = clip?.top ?: 0
        val width = clip?.width() ?: view.width
        val height = clip?.height() ?: view.height
        val rotation = shadow?.rotation ?: 0
        val renderWidth = if (rotation % 2 != 0) height else width
        val renderHeight = if (rotation % 2 != 0) width else height
        val node = RenderNode("particleSnapshot")
        val cardNode = if (shadow != null) RenderNode("particleCardWithShadow") else node
        var reader: ImageReader? = null
        var renderer: HardwareRenderer? = null
        var completed = false // 只在主线程读写。
        lateinit var timeout: Runnable
        fun complete(bitmap: Bitmap?) {
            if (completed) { bitmap?.recycle(); return }
            completed = true
            main.removeCallbacks(timeout)
            // destroy 同样可能等待 RenderThread；与读回在同一后台队列串行释放。
            worker.post {
                runCatching { renderer?.destroy() }
                runCatching { node.discardDisplayList() }
                if (cardNode !== node) runCatching { cardNode.discardDisplayList() }
                runCatching { reader?.close() }
            }
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
            val canvas = cardNode.beginRecording(if (shadow != null) view.width else width,
                if (shadow != null) view.height else height)
            if (shadow == null && (clipLeft != 0 || clipTop != 0)) canvas.translate(-clipLeft.toFloat(), -clipTop.toFloat())
            ParticleForegroundSurface.withNativeDrawing(view) { view.draw(canvas) }
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
            val location = IntArray(2); view.getLocationOnScreen(location)
            val metrics = view.resources.displayMetrics
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
                hardware.setLightSourceGeometry(
                    metrics.widthPixels / 2f - (location[0] + clipLeft),
                    -(location[1] + clipTop).toFloat(), 600f * metrics.density, 800f * metrics.density)
                hardware.setLightSourceAlpha(.039f, .19f)
            }
            hardware.start()
            main.postDelayed(timeout, 1200)
            // sync 完成后节点已交给 RenderThread，之后恢复真实 ripple 不会改变本次提交。
            val status = hardware.createRenderRequest().setWaitForPresent(false).syncAndDraw()
            if (status != HardwareRenderer.SYNC_OK && status != HardwareRenderer.SYNC_REDRAW_REQUESTED) {
                ParticleCaptureDiagnostics.note("硬件离屏 syncAndDraw=$status")
                complete(null)
            }
        } catch (error: Throwable) {
            ParticleCaptureDiagnostics.note("硬件离屏异常=${error.javaClass.simpleName}")
            complete(null)
        }
    }
}
