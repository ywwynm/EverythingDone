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
import androidx.annotation.RequiresApi

/** 主线程只提交显示树；等待 GPU 和读回像素在独立线程完成，避免挡住 ripple。 */
@RequiresApi(29)
internal object ParticleHardwareSnapshot {
    private val main = Handler(Looper.getMainLooper())
    private val worker by lazy {
        Handler(HandlerThread("ParticleSnapshotCopy", android.os.Process.THREAD_PRIORITY_DISPLAY)
            .apply { start() }.looper)
    }

    fun capture(view: View, done: (Bitmap?) -> Unit) {
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
        timeout = Runnable { complete(null) }
        try {
            val target = ImageReader.newInstance(view.width, view.height, PixelFormat.RGBA_8888, 2,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            reader = target
            target.setOnImageAvailableListener({ available ->
                val bitmap = runCatching {
                    val image = available.acquireNextImage() ?: return@runCatching null
                    try {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            !image.fence.await(java.time.Duration.ofMillis(700))) return@runCatching null
                        val buffer = image.hardwareBuffer ?: return@runCatching null
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, null)
                        buffer.close()
                        try { hardware?.copy(Bitmap.Config.ARGB_8888, true) } finally { hardware?.recycle() }
                    } finally { image.close() }
                }.getOrNull()
                main.post { complete(bitmap) }
            }, worker)
            val hardware = HardwareRenderer().also { renderer = it }
            hardware.setName("particleSnapshot")
            hardware.isOpaque = false
            hardware.setSurface(target.surface)
            node.setPosition(0, 0, view.width, view.height)
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
            if (status != HardwareRenderer.SYNC_OK && status != HardwareRenderer.SYNC_REDRAW_REQUESTED) complete(null)
        } catch (_: Throwable) { complete(null) }
    }
}
