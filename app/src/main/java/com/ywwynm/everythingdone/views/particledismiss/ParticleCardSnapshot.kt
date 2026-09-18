package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.View
import com.ywwynm.everythingdone.R
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 真实卡片的投影光源与外扩取景范围，坐标均以未平移的卡片左上角为原点。 */
internal data class ParticleCardSnapshot private constructor(
    val bounds: Rect,
    val elevation: Float,
    val lightX: Float,
    val lightY: Float,
    val lightZ: Float,
    val lightRadius: Float,
    val ambientAlpha: Float,
    val spotAlpha: Float,
    val materialAreaScale: Float,
    val rotation: Int
) {
    fun clippedTo(bounds: Rect): ParticleCardSnapshot = copy(bounds = Rect(bounds))

    companion object {
        @Suppress("DEPRECATION")
        fun of(view: View, elevation: Float = view.resources.getDimension(R.dimen.thing_card_normal_elevation)): ParticleCardSnapshot? {
            if (Build.VERSION.SDK_INT < 29 || view.width <= 0 || view.height <= 0) return null
            val metrics = DisplayMetrics().also { view.display?.getRealMetrics(it) }
            if (metrics.density <= 0f) metrics.setTo(view.resources.displayMetrics)
            val density = metrics.density
            val names = arrayOf("lightY", "lightZ", "lightRadius", "ambientShadowAlpha", "spotShadowAlpha")
            val ids = names.map { view.resources.getIdentifier(it, "attr", "android") }.toIntArray()
            // Android 的样式查找要求资源 ID 升序；私有 Lighting 属性与公开 alpha 属性混排会漏读。
            val sortedIds = ids.filter { it != 0 }.sorted().toIntArray()
            val values = view.context.obtainStyledAttributes(sortedIds)
            val lightY: Float
            val lightZ: Float
            val radius: Float
            val ambient: Float
            val spot: Float
            try {
                fun dimension(index: Int, fallback: Float): Float {
                    val slot = sortedIds.indexOf(ids[index])
                    return if (slot >= 0 && values.hasValue(slot)) values.getDimension(slot, fallback) else fallback
                }
                fun fraction(index: Int, fallback: Float): Float {
                    val slot = sortedIds.indexOf(ids[index])
                    return if (slot >= 0 && values.hasValue(slot)) values.getFloat(slot, fallback) else fallback
                }
                lightY = dimension(0, 0f)
                val adjustment = if (Build.VERSION.SDK_INT >= 34)
                    (min(metrics.widthPixels, metrics.heightPixels) / (450f * density) + 2f) / 3f else 1f
                lightZ = dimension(1, 600f * density) * adjustment
                radius = dimension(2, 800f * density)
                ambient = fraction(3, .039f)
                spot = fraction(4, .19f)
            } finally { values.recycle() }
            val at = IntArray(2)
            val parent = view.parent as? View
            if (parent != null) {
                parent.getLocationOnScreen(at)
                at[0] += view.left - parent.scrollX
                at[1] += view.top - parent.scrollY
            } else view.getLocationOnScreen(at)
            val x = metrics.widthPixels / 2f - at[0]
            val y = lightY - at[1]
            val z = elevation.coerceAtLeast(0f)
            val ratio = z / max(lightZ - z, z)
            val rotation = ParticleWindowLighting.rotation(view)
            // 点光源投影的偏移加软边半径；环境光的模糊余量也必须保留。
            val ambientInset = z * 1.5f + 2f
            // 旋转后的点光源软边会跨原来的轴，取四个方向的最大支持范围，避免裁出硬边。
            val rotatedInset = if (rotation == 0) 0f else
                (max(max(x, view.width - x), max(y, view.height - y)) + radius) * ratio + 2f
            fun inset(projected: Float) = ceil(max(max(ambientInset, rotatedInset), projected + 2f)).toInt()
            val expanded = Rect(
                -inset((x + radius) * ratio),
                -inset((y + radius) * ratio),
                view.width + inset((view.width - x + radius) * ratio),
                view.height + inset((view.height - y + radius) * ratio))
            val bounds = ParticleCardClip.visibleRect(view, expanded) ?: expanded
            if (bounds.isEmpty) return null
            val body = Rect(bounds)
            if (!body.intersect(0, 0, view.width, view.height)) return null
            val areaScale = bounds.width().toFloat() * bounds.height() / (body.width().toFloat() * body.height())
            return ParticleCardSnapshot(bounds, z, x, y, lightZ, radius, ambient, spot, areaScale,
                rotation)
        }
    }
}
