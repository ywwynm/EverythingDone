package com.ywwynm.everythingdone.views.particledismiss

import android.os.Looper
import android.view.Choreographer

/** 共用 UI 显示节拍；专用 GL 线程绘制，并只保留一个最新的待绘制节拍。 */
internal object ParticlePlaybackClock {
    fun play(duration: Float, cancelled: () -> Boolean,
             draw: (Float, Long) -> Boolean): Boolean {
        val looper = checkNotNull(Looper.myLooper())
        val gl = android.os.Handler(looper)
        val main = android.os.Handler(Looper.getMainLooper())
        val stopped = java.util.concurrent.atomic.AtomicBoolean()
        val inFlight = java.util.concurrent.atomic.AtomicBoolean()
        val latest = java.util.concurrent.atomic.AtomicLong()
        // 跟随承载窗口的真实节拍；高刷新屏采样到 60 Hz，避免额外提交重复的缓存帧。
        val interval = (1e9 / 60.0).toLong()
        var started = 0L
        var previous = 0L
        var complete = false
        var error: Throwable? = null
        val render = object : Runnable {
            override fun run() {
                if (stopped.get() || cancelled()) { looper.quit(); return }
                val timestamp = latest.get()
                val progress = ((timestamp - started) / 1e9 / duration).toFloat().coerceIn(0f, 1f)
                try {
                    if (!draw(progress, timestamp)) { stopped.set(true); looper.quit() }
                    else if (progress >= 1f) { complete = true; stopped.set(true); looper.quit() }
                } catch (e: Throwable) { error = e; stopped.set(true); looper.quit() }
                finally { inFlight.set(false) }
                // GPU 稍慢于 16.7 ms 时，之前丢掉在途期间的通知后又等下一拍，
                // 会把约 18 ms 的工作放大为 33 ms。仅接着处理最新一拍，不补旧帧。
                if (!stopped.get() && latest.get() > timestamp && inFlight.compareAndSet(false, true)) {
                    gl.post(this)
                }
            }
        }
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (stopped.get() || cancelled()) { looper.quit(); return }
                // 先注册，不能等 GPU 提交之后再申请下一次垂直同步。
                Choreographer.getInstance().postFrameCallback(this)
                if (previous != 0L && frameTimeNanos - previous < interval - 500_000L) return
                if (started == 0L) started = frameTimeNanos
                previous = frameTimeNanos
                latest.set(frameTimeNanos)
                if (inFlight.compareAndSet(false, true)) gl.post(render)
            }
        }
        main.post { if (!stopped.get()) Choreographer.getInstance().postFrameCallback(callback) }
        Looper.loop()
        stopped.set(true)
        main.post { Choreographer.getInstance().removeFrameCallback(callback) }
        error?.let { throw it }
        return complete
    }
}
