package com.ywwynm.everythingdone.views.particledismiss

import android.os.Build
import android.view.SurfaceControl
import android.view.View

/** Vulkan 会预旋转窗口缓冲；离屏阴影须采用相同方向，OpenGL 保持原方向。 */
internal object ParticleWindowLighting {
    private val usesVulkan by lazy {
        ParticleMaterialNative.available && runCatching { nativeUsesVulkan() }.getOrDefault(false)
    }

    fun rotation(view: View): Int {
        if (!usesVulkan) return 0
        if (Build.VERSION.SDK_INT >= 35) {
            val hint = view.rootSurfaceControl?.bufferTransformHint
            if (hint != null) return when (hint) {
                SurfaceControl.BUFFER_TRANSFORM_ROTATE_90 -> 1
                SurfaceControl.BUFFER_TRANSFORM_ROTATE_180 -> 2
                SurfaceControl.BUFFER_TRANSFORM_ROTATE_270 -> 3
                else -> 0
            }
        }
        // 较早系统在根 Surface 尚未就绪时查询 hint 会出错，使用显示方向。
        return view.display?.rotation ?: 0
    }

    private external fun nativeUsesVulkan(): Boolean
}
