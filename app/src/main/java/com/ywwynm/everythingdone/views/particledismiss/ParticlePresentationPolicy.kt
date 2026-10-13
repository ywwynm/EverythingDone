package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.view.View
import com.ywwynm.everythingdone.BuildConfig

/** 按绘制能力选择共同实现；不识别设备品牌。旧方案仅供 debug 对照。 */
internal object ParticlePresentationPolicy {
    private val unified = ParticlePresentationDiagnostics.Options(dialogMode = ParticlePresentationDiagnostics.DialogMode.CONTENT,
        images = false, glForeground = true, surfaceRecovery = true, opaqueGlHandoff = true,
        fastOpaqueHandoff = true, unified = true, fixture = false)
    private val original = ParticlePresentationDiagnostics.Options(images = false)
    var originalForDebug = false
        set(value) { field = BuildConfig.DEBUG && value }
    fun of(context: Context) = ParticlePresentationDiagnostics.of(context)?.options
        ?: if (originalForDebug) original else unified
    fun of(view: View) = ParticlePresentationDiagnostics.of(view)?.options ?: of(view.context)
    fun debugOptions(originalMode: Boolean, images: Boolean = false) =
        (if (originalMode) original else unified).copy(images = images, fixture = false)
}
