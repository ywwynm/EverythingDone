package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup

/** 对同一可见树并行取得独立生产者的内容；迟到结果只回收，不再次交付。 */
internal object ParticleSurfaceFrames {
    private val main = Handler(Looper.getMainLooper())
    fun capture(root: View, excluded: Set<View> = emptySet(), done: (Map<SurfaceView, Bitmap>?) -> Unit) {
        val sources = mutableListOf<SurfaceView>()
        fun visit(view: View) {
            if (view in excluded || view.visibility != View.VISIBLE || view is ParticleOverlaySurfaceView) return
            if (view is SurfaceView && view.width > 0 && view.height > 0 && view.getGlobalVisibleRect(Rect())) sources += view
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        if (sources.isEmpty()) { done(emptyMap()); return }
        val copies = LinkedHashMap<SurfaceView, Bitmap>()
        var remaining = sources.size
        var closed = false
        val start = SystemClock.uptimeMillis()
        val diagnostic = ParticlePresentationDiagnostics.of(root)
        var attempts = 0
        var lastStatus = -1
        fun record(ready: Boolean) {
            if (ready && attempts == sources.size && SystemClock.uptimeMillis() - start < 50) return
            diagnostic?.event("surface_capture_timing", mapOf("ready" to ready,
                "count" to sources.size, "attempts" to attempts, "status" to lastStatus,
                "elapsedMs" to SystemClock.uptimeMillis() - start))
        }
        lateinit var timeout: Runnable
        fun fail() {
            if (closed) return
            closed = true; main.removeCallbacks(timeout)
            copies.values.forEach { it.recycle() }; copies.clear(); record(false); done(null)
        }
        timeout = Runnable { fail() }; main.postDelayed(timeout, 900)
        sources.forEach { source ->
            val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            fun request() {
                if (closed) { bitmap.recycle(); return }
                if (!source.isAttachedToWindow) { bitmap.recycle(); fail(); return }
                try {
                    attempts++
                    PixelCopy.request(source, bitmap, { status ->
                        lastStatus = status
                        if (closed) bitmap.recycle()
                        else if (status == PixelCopy.SUCCESS) {
                            copies[source] = bitmap
                            if (--remaining == 0) {
                                closed = true; main.removeCallbacks(timeout); record(true); done(copies)
                            }
                        // 复用本次捕获已有的 900 ms 截止时间，避免首帧仍在正常准备时提前 200 ms 放弃。
                        } else if (status == PixelCopy.ERROR_SOURCE_NO_DATA && SystemClock.uptimeMillis() - start < 900)
                            main.postDelayed({ request() }, 16)
                        else { bitmap.recycle(); fail() }
                    }, main)
                } catch (failure: IllegalArgumentException) { bitmap.recycle(); fail() }
            }
            request()
        }
    }
}
