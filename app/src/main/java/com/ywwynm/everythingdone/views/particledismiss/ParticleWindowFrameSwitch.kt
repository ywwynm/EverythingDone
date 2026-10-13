package com.ywwynm.everythingdone.views.particledismiss

import android.os.Handler
import android.os.Looper
import android.view.SurfaceControl
import android.view.View
import android.window.SurfaceSyncGroup
import androidx.annotation.RequiresApi

/** 暂停宿主取帧，将真实 View 与 SurfaceView 的公开属性变更纳入同一次窗口绘制。 */
@RequiresApi(34)
internal object ParticleWindowFrameSwitch {
    private val main = Handler(Looper.getMainLooper())

    /** 多窗口的原生投影与承接图像一起切换；窗口间不可用先显后撤叠加半透明投影。 */
    fun runTogether(views: List<View>, diagnostic: ParticlePresentationDiagnostics.Run?, stage: String,
                    change: () -> Unit, done: (Boolean) -> Unit) {
        val roots = views.mapNotNull { if (it.isAttachedToWindow) it.rootSurfaceControl else null }.distinct()
        if (roots.isEmpty()) { change(); done(false); return }
        val finish = ParticleOnceResult(done)
        val timeout = Runnable { diagnostic?.event("window_switch_timeout", mapOf("stage" to stage)); finish.finish(false) }
        main.postDelayed(timeout, 1200)
        val group = SurfaceSyncGroup("ParticleWindows-$stage")
        var changed = false
        try {
            val added = roots.all { group.add(it, null) }
            change(); changed = true
            android.view.SurfaceControl.Transaction().use { transaction ->
                transaction.addTransactionCommittedListener({ main.post(it) }) {
                    main.removeCallbacks(timeout); finish.finish(true)
                }
                group.addTransaction(transaction)
            }
            views.forEach { it.rootView.invalidate() }
            diagnostic?.event("window_shadow_switch", mapOf("stage" to stage, "windows" to roots.size, "added" to added))
        } catch (failure: Exception) {
            if (!changed) change()
            main.removeCallbacks(timeout); finish.finish(false)
            diagnostic?.event("window_switch_failed", mapOf("stage" to stage, "error" to failure.toString()))
        } finally {
            runCatching { group.markSyncReady() }.onFailure {
                main.removeCallbacks(timeout); finish.finish(false)
                diagnostic?.event("window_switch_failed", mapOf("stage" to stage, "error" to it.toString()))
            }
        }
    }

    fun run(view: View, diagnostic: ParticlePresentationDiagnostics.Run?, stage: String,
            change: () -> Unit, done: (Boolean) -> Unit) {
        val completion = ParticleOnceResult(done)
        val timeout = Runnable {
            diagnostic?.event("window_switch_timeout", mapOf("stage" to stage))
            completion.finish(false)
        }
        val root = view.rootSurfaceControl
        if (!view.isAttachedToWindow || root == null) { completion.finish(false); return }
        main.postDelayed(timeout, 1200)
        var changed = false
        var queued = false
        var group: SurfaceSyncGroup? = null
        try {
            val sync = SurfaceSyncGroup("ParticleHandoff-$stage").also { group = it }
            val added = sync.add(root) {
                change()
                changed = true
                SurfaceControl.Transaction().use { transaction ->
                    transaction.addTransactionCommittedListener({ main.post(it) }) {
                        main.removeCallbacks(timeout)
                        diagnostic?.event("window_switch_committed", mapOf("stage" to stage,
                            "meaning" to "transaction_committed_not_screen_verified"))
                        completion.finish(true)
                    }
                    queued = root.applyTransactionOnDraw(transaction)
                }
                view.rootView.invalidate()
            }
            diagnostic?.event("window_switch_requested", mapOf("stage" to stage,
                "groupAdded" to added, "changed" to changed, "queuedOnDraw" to queued))
            if (!added || !queued) { main.removeCallbacks(timeout); completion.finish(false) }
        } catch (failure: Exception) {
            main.removeCallbacks(timeout)
            diagnostic?.event("window_switch_failed", mapOf("stage" to stage, "changed" to changed,
                "error" to failure.toString()))
            completion.finish(false)
        } finally {
            runCatching { group?.markSyncReady() }.onFailure {
                main.removeCallbacks(timeout)
                diagnostic?.event("window_switch_failed", mapOf("stage" to stage, "error" to it.toString()))
                completion.finish(false)
            }
        }
    }
}
