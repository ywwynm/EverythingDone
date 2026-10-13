package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.RequiresApi

/** Dialog 外部投影单独随主体淡入淡出，不扩大粒子材料、更不改变卡片阴影。 */
@RequiresApi(29)
internal class ParticleDialogShadow private constructor(
    private val host: ViewGroup, private val source: View, private val pixels: Bitmap,
    geometry: ParticleCardSnapshot, private val elevation: Float, private val translationZ: Float
) {
    private val diagnostic = ParticlePresentationDiagnostics.of(source)
    private val image = ImageView(host.context).apply {
        setImageBitmap(pixels)
        val from = IntArray(2).also(source::getLocationOnScreen)
        val to = IntArray(2).also(host::getLocationOnScreen)
        val x = from[0] - to[0] + geometry.bounds.left
        val y = from[1] - to[1] + geometry.bounds.top
        layout(x, y, x + pixels.width, y + pixels.height)
        alpha = 0f
    }
    private var closed = false
    init {
        host.overlay.add(image)
        diagnostic?.event("dialog_outer_shadow_prepared", mapOf("elevation" to elevation,
            "translationZ" to translationZ, "width" to pixels.width, "height" to pixels.height))
        ParticlePresentationDiagnostics.sample(diagnostic, "dialog-outer-shadow-${System.identityHashCode(this)}", pixels)
    }

    fun hideNative() { if (!closed) { source.elevation = 0f; source.translationZ = 0f } }

    fun enter(done: () -> Unit) = switch("shadow_enter", {
        hideNative(); image.alpha = 1f
    }, done)

    fun progress(value: Float, appearance: Boolean) {
        if (closed) return
        // 主体消失后不保留空的投影轮廓；出现时在主体形成阶段逐渐建立投影。
        val amount = (if (appearance) (value - .65f) / .35f else 1f - value / .35f).coerceIn(0f, 1f)
        image.alpha = amount * amount * (3f - 2f * amount)
    }

    fun close(restore: Boolean = true) {
        if (closed) return
        closed = true
        switch("shadow_restore", {
            if (restore) { source.elevation = elevation; source.translationZ = translationZ }
            host.overlay.remove(image)
        }) {
            image.setImageDrawable(null); pixels.recycle()
        }
    }

    private fun switch(stage: String, change: () -> Unit, done: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 34 && host.isAttachedToWindow && source.isAttachedToWindow) {
            ParticleWindowFrameSwitch.runTogether(listOf(host, source), diagnostic, stage, change) { done() }
        } else {
            change()
            ParticleDismissController.runAfterShown(host, 100) { done() }
            host.invalidate(); source.invalidate()
        }
    }

    companion object {
        fun prepare(host: ViewGroup, source: View, elevation: Float, translationZ: Float,
                    done: (ParticleDialogShadow?) -> Unit) {
            val began = System.nanoTime()
            if (elevation + translationZ <= 0f) { done(null); return }
            val geometry = ParticleCardSnapshot.of(source, elevation + translationZ)
            ParticlePresentationDiagnostics.of(source)?.event("outer_shadow_geometry", mapOf(
                "elapsedMs" to (System.nanoTime() - began) / 1e6))
            if (geometry == null) { done(null); return }
            ParticleHardwareSnapshot.captureOuterShadow(source, geometry) { bitmap ->
                if (bitmap == null || !host.isAttachedToWindow || !source.isAttachedToWindow) {
                    bitmap?.recycle(); done(null)
                } else done(ParticleDialogShadow(host, source, bitmap, geometry, elevation, translationZ))
            }
        }
    }
}
