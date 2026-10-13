package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.view.View

/** 正式默认与原实现的对照开关只编译进 debug；没有应用内菜单入口。 */
internal object ParticlePresentationPolicy {
    private val unified = ParticlePresentationDiagnostics.Options(dialogMode = ParticlePresentationDiagnostics.DialogMode.CONTENT,
        images = false, glForeground = true, surfaceRecovery = true, opaqueGlHandoff = true,
        fastOpaqueHandoff = true, unified = true, fixture = false)
    private val original = ParticlePresentationDiagnostics.Options(images = false)
    var originalForDebug = false
    fun of(context: Context) = ParticlePresentationDiagnostics.of(context)?.options
        ?: if (originalForDebug) original else unified
    fun of(view: View) = ParticlePresentationDiagnostics.of(view)?.options ?: of(view.context)
    fun debugOptions(originalMode: Boolean, images: Boolean = false) =
        (if (originalMode) original else unified).copy(images = images, fixture = false)
}
