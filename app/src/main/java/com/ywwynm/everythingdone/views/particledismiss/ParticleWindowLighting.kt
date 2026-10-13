package com.ywwynm.everythingdone.views.particledismiss

import android.os.Build
import android.view.SurfaceControl
import android.view.View
import android.graphics.HardwareRenderer
import android.util.DisplayMetrics
import androidx.annotation.RequiresApi
import kotlin.math.min

/** Vulkan 会预旋转窗口缓冲；离屏阴影须采用相同方向，OpenGL 保持原方向。 */
internal object ParticleWindowLighting {
    data class Source(val x: Float, val y: Float, val z: Float, val radius: Float,
                      val ambient: Float, val spot: Float)

    /** 与窗口 ThreadedRenderer 一样读取主题光源，并按真实显示尺寸校正高度。 */
    @Suppress("DEPRECATION")
    fun source(view: View): Source {
        val metrics = DisplayMetrics().also { view.display?.getRealMetrics(it) }
        if (metrics.density <= 0f) metrics.setTo(view.resources.displayMetrics)
        val density = metrics.density
        val names = arrayOf("lightY", "lightZ", "lightRadius", "ambientShadowAlpha", "spotShadowAlpha")
        val ids = names.map { view.resources.getIdentifier(it, "attr", "android") }.toIntArray()
        // 私有光源属性与公开 alpha 属性的 ID 混排；TypedArray 要求按 ID 升序。
        val sortedIds = ids.filter { it != 0 }.sorted().toIntArray()
        val values = view.context.obtainStyledAttributes(sortedIds)
        try {
            fun dimension(index: Int, fallback: Float): Float {
                val slot = sortedIds.indexOf(ids[index])
                return if (slot >= 0 && values.hasValue(slot)) values.getDimension(slot, fallback) else fallback
            }
            fun fraction(index: Int, fallback: Float): Float {
                val slot = sortedIds.indexOf(ids[index])
                return if (slot >= 0 && values.hasValue(slot)) values.getFloat(slot, fallback) else fallback
            }
            val adjustment = if (Build.VERSION.SDK_INT >= 34)
                (min(metrics.widthPixels, metrics.heightPixels) / (450f * density) + 2f) / 3f else 1f
            return Source(metrics.widthPixels / 2f, dimension(0, 0f),
                dimension(1, 600f * density) * adjustment, dimension(2, 800f * density),
                fraction(3, .039f), fraction(4, .19f))
        } finally { values.recycle() }
    }

    @RequiresApi(29)
    fun apply(renderer: HardwareRenderer, view: View, clipLeft: Int = 0, clipTop: Int = 0) {
        val source = source(view)
        val at = IntArray(2).also(view::getLocationOnScreen)
        renderer.setLightSourceGeometry(source.x - at[0] - clipLeft, source.y - at[1] - clipTop,
            source.z, source.radius)
        renderer.setLightSourceAlpha(source.ambient, source.spot)
    }

    private val usesVulkan by lazy {
        ParticleMaterialNative.available && runCatching { nativeUsesVulkan() }.getOrDefault(false)
    }

    fun rotation(view: View): Int {
        val rotation = if (Build.VERSION.SDK_INT >= 35) {
            val hint = view.rootSurfaceControl?.bufferTransformHint
            if (hint != null) when (hint) {
                SurfaceControl.BUFFER_TRANSFORM_ROTATE_90 -> 1
                SurfaceControl.BUFFER_TRANSFORM_ROTATE_180 -> 2
                SurfaceControl.BUFFER_TRANSFORM_ROTATE_270 -> 3
                else -> 0
            } else view.display?.rotation ?: 0
        } else view.display?.rotation ?: 0
        // 较早系统在根 Surface 尚未就绪时查询 hint 会出错，使用显示方向。
        // 不旋转时两个后端完全相同，不能为这个恒等变换阻塞在本地库首次加载上。
        return if (rotation == 0 || !usesVulkan) 0 else rotation
    }

    private external fun nativeUsesVulkan(): Boolean
}
