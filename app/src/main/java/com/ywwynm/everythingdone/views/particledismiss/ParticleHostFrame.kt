package com.ywwynm.everythingdone.views.particledismiss

import android.os.Handler
import android.os.Looper
import android.view.View

/** View 属性恢复后等待窗口提交；超时只用于结束交接，诊断中明确标为推定。 */
internal object ParticleHostFrame {
    private val main = Handler(Looper.getMainLooper())

    fun after(view: View, diagnostic: ParticlePresentationDiagnostics.Run?, stage: String, done: () -> Unit) {
        var finished = false
        lateinit var timeout: Runnable
        fun finish(committed: Boolean) {
            if (finished) return
            finished = true
            main.removeCallbacks(timeout)
            diagnostic?.event("gl_host_frame_boundary", mapOf("stage" to stage,
                "basis" to if (committed) "window_frame_commit" else "host_frame_timeout_estimate"))
            done()
        }
        timeout = Runnable { finish(false) }
        if (view.isAttachedToWindow && view.isHardwareAccelerated && view.viewTreeObserver.isAlive) {
            runCatching { view.viewTreeObserver.registerFrameCommitCallback { main.post { finish(true) } } }
            view.rootView.invalidate()
        }
        main.postDelayed(timeout, 150)
    }
}
