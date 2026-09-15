package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
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

    fun capture(view: View, done: (Bitmap?) -> Unit) {
        ParticleCaptureDiagnostics.begin("硬件离屏")
        val node = RenderNode("particleSnapshot")
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
            val target = ImageReader.newInstance(view.width, view.height, PixelFormat.RGBA_8888, 2,
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
                        try { hardware?.copy(Bitmap.Config.ARGB_8888, true) } finally { hardware?.recycle() }
                    } finally { image.close() }
                }.onFailure {
                    ParticleCaptureDiagnostics.note("硬件离屏读回异常=${it.javaClass.simpleName}")
                }.getOrNull()
                main.post { complete(bitmap) }
            }, worker)
            val hardware = HardwareRenderer().also { renderer = it }
            hardware.setName("particleSnapshot")
            hardware.isOpaque = false
            hardware.setSurface(target.surface)
            node.setPosition(0, 0, view.width, view.height)
            // View.draw 会记录子树，但不会把根 View 自身 RenderNode 的裁剪属性带进来。
            // 媒体卡片使用自定义 Outline，不能从 CardView.radius 或背景形状推断它。
            if (view.clipToOutline) {
                val outline = Outline()
                view.outlineProvider?.getOutline(view, outline)
                node.setOutline(outline)
                node.setClipToOutline(true)
            }
            val canvas = node.beginRecording(view.width, view.height)
            view.draw(canvas)
            node.endRecording()
            hardware.setContentRoot(node)
            val location = IntArray(2); view.getLocationOnScreen(location)
            val metrics = view.resources.displayMetrics
            hardware.setLightSourceGeometry(metrics.widthPixels / 2f - location[0],
                -location[1].toFloat(), 600f * metrics.density, 800f * metrics.density)
            hardware.setLightSourceAlpha(.039f, .19f)
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
