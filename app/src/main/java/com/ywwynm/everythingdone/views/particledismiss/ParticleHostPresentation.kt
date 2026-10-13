package com.ywwynm.everythingdone.views.particledismiss

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.SurfaceControl
import android.view.View
import androidx.annotation.RequiresApi
import java.time.Duration
import java.util.concurrent.Executors

/** 交接时明确记录呈现 fence，不能把 committed 或无效 fence 当成已显示。 */
@RequiresApi(34)
internal object ParticleHostPresentation {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ParticlePresentFence") }

    fun afterChange(view: View, diagnostic: ParticlePresentationDiagnostics.Run?, stage: String,
                    change: () -> Unit, done: (Boolean) -> Unit) {
        val once = ParticleOnceResult(done)
        val timeout = Runnable {
            diagnostic?.event("host_present_timeout", mapOf("stage" to stage))
            once.finish(false)
        }
        main.postDelayed(timeout, 1500)
        try {
            change()
            val root = view.rootSurfaceControl
            if (Build.VERSION.SDK_INT < 35 || !view.isAttachedToWindow || root == null) {
                main.removeCallbacks(timeout)
                diagnostic?.event("host_present_unavailable", mapOf("stage" to stage))
                once.finish(false); return
            }
            SurfaceControl.Transaction().use { transaction ->
                transaction.addTransactionCompletedListener(worker) { stats ->
                    val result = runCatching {
                        stats.presentFence.use { fence -> fence.isValid && fence.await(Duration.ofMillis(1000)) }
                    }.getOrDefault(false)
                    main.post {
                        main.removeCallbacks(timeout)
                        diagnostic?.event("host_present_fence", mapOf("stage" to stage, "validAndSignaled" to result))
                        once.finish(result)
                    }
                }
                val queued = root.applyTransactionOnDraw(transaction)
                diagnostic?.event("host_present_requested", mapOf("stage" to stage, "queuedOnDraw" to queued))
                if (!queued) { main.removeCallbacks(timeout); once.finish(false) }
            }
            view.rootView.invalidate()
        } catch (failure: Exception) {
            main.removeCallbacks(timeout)
            diagnostic?.event("host_present_failed", mapOf("stage" to stage, "error" to failure.toString()))
            once.finish(false)
        }
    }
}
