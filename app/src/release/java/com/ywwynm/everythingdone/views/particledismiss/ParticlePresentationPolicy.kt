package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.view.View

/** 正式包固定使用已验收的共同方案，不包含原实现对照开关。 */
@Suppress("UNUSED_PARAMETER")
internal object ParticlePresentationPolicy {
    private val unified = ParticlePresentationDiagnostics.Options(dialogMode = ParticlePresentationDiagnostics.DialogMode.CONTENT,
        images = false, glForeground = true, surfaceRecovery = true, opaqueGlHandoff = true,
        fastOpaqueHandoff = true, unified = true, fixture = false)

    fun of(context: Context) = unified
    fun of(view: View) = unified
}
