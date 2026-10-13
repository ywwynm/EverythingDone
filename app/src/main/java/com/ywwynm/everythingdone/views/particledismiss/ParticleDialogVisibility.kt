package com.ywwynm.everythingdone.views.particledismiss

import android.view.View
import android.view.Window
import java.util.WeakHashMap

/** Dialog 的显示状态：隐藏与控制器共享同一份原值，收尾只恢复一次。 */
internal object ParticleDialogVisibility {
    private val leases = WeakHashMap<Window, Lease>()

    fun prepareEarly(window: Window) {
        val run = ParticlePresentationDiagnostics.of(window.context) ?: return
        if (run.options.dialogMode != ParticlePresentationDiagnostics.DialogMode.EARLY_WINDOW) return
        acquire(window, run).hide()
    }

    fun acquire(window: Window, run: ParticlePresentationDiagnostics.Run?): Lease =
        leases[window] ?: Lease(window, run).also { leases[window] = it }

    fun restore(window: Window?, reason: String) { window?.let { leases[it]?.restore(reason) } }
    fun elevation(window: Window) = leases[window]?.originalElevation ?: window.decorView.elevation
    fun translationZ(window: Window) = leases[window]?.originalTranslationZ ?: window.decorView.translationZ

    class Lease internal constructor(private val window: Window,
                                     private val run: ParticlePresentationDiagnostics.Run?) {
        private val decor = window.decorView
        val originalWindowAlpha = window.attributes.alpha
        private val originalDecorAlpha = decor.alpha
        val originalElevation = decor.elevation
        val originalTranslationZ = decor.translationZ
        private var restored = false
        private val mode = ParticlePresentationPolicy.of(window.context).dialogMode
        val hidesContent = mode == ParticlePresentationDiagnostics.DialogMode.CONTENT
        private val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { restore("detach") }
        }

        init { decor.addOnAttachStateChangeListener(attachment) }

        fun hide() {
            if (restored) return
            window.attributes = window.attributes.apply { alpha = 0f }
            if (hidesContent) {
                decor.alpha = 0f
                decor.elevation = 0f
                decor.translationZ = 0f
            }
            record("dialog_hidden")
        }

        fun record(event: String) = run?.event(event, mapOf(
            "window" to System.identityHashCode(window), "mode" to mode.name,
            "windowAlpha" to window.attributes.alpha, "decorAlpha" to decor.alpha,
            "elevation" to decor.elevation, "windowAnimations" to window.attributes.windowAnimations,
            "attached" to decor.isAttachedToWindow))

        fun restore(reason: String) {
            if (restored) return
            restored = true
            decor.removeOnAttachStateChangeListener(attachment)
            if (leases[window] === this) leases.remove(window)
            window.attributes = window.attributes.apply { alpha = originalWindowAlpha }
            if (hidesContent) {
                decor.alpha = originalDecorAlpha
                decor.elevation = originalElevation
                decor.translationZ = originalTranslationZ
            }
            record("dialog_restored:$reason")
        }
    }
}
