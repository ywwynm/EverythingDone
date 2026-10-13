package com.ywwynm.everythingdone.views.particledismiss

import android.content.Context
import android.graphics.Bitmap
import android.view.View

/**
 * 正式渲染调用点的空诊断接口。没有注册、事件收集、图像复制或报告导出能力。
 * 类型签名与 debug 对应实现保持一致；运行时始终没有诊断会话。
 */
@Suppress("UNUSED_PARAMETER")
internal object ParticlePresentationDiagnostics {
    enum class DialogMode { ORIGINAL, EARLY_WINDOW, CONTENT }
    data class Options(val rootForeground: Boolean = false,
                       val dialogMode: DialogMode = DialogMode.ORIGINAL,
                       val images: Boolean = true,
                       val glForeground: Boolean = false,
                       val synchronizedGlHandoff: Boolean = false,
                       val surfaceRecovery: Boolean = false,
                       val opaqueGlHandoff: Boolean = false,
                       val fastOpaqueHandoff: Boolean = false,
                       val unified: Boolean = false,
                       val fixture: Boolean = true)

    interface Run {
        val options: Options
        fun event(name: String, values: Map<String, Any?> = emptyMap())
        fun claimImage(name: String): Boolean
        fun image(name: String, producer: () -> Bitmap?)
    }

    fun of(context: Context?): Run? = null
    fun of(view: View): Run? = null
    fun sample(run: Run?, name: String, bitmap: Bitmap) = Unit

    const val ROOT_CARD_LAYER = Int.MAX_VALUE - 128
    fun rootForegroundLayer(order: Int): Int {
        require(order in 1..64)
        return ROOT_CARD_LAYER + order
    }
}
