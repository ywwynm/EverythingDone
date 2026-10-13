package com.ywwynm.everythingdone.views.particledismiss

import android.graphics.Rect
import android.os.Build
import android.view.View
import com.ywwynm.everythingdone.R
import kotlin.math.ceil
import kotlin.math.max

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
            val start = System.nanoTime()
            val light = ParticleWindowLighting.source(view)
            val lit = System.nanoTime()
            val lightZ = light.z
            val radius = light.radius
            val at = IntArray(2)
            val parent = view.parent as? View
            if (parent != null) {
                parent.getLocationOnScreen(at)
                at[0] += view.left - parent.scrollX
                at[1] += view.top - parent.scrollY
            } else view.getLocationOnScreen(at)
            val x = light.x - at[0]
            val y = light.y - at[1]
            val z = elevation.coerceAtLeast(0f)
            val ratio = z / max(lightZ - z, z)
            val rotation = ParticleWindowLighting.rotation(view)
            val rotated = System.nanoTime()
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
            ParticlePresentationDiagnostics.of(view)?.event("shadow_geometry_timing", mapOf(
                "lightMs" to (lit - start) / 1e6, "rotationMs" to (rotated - lit) / 1e6,
                "boundsMs" to (System.nanoTime() - rotated) / 1e6))
            if (bounds.isEmpty) return null
            val body = Rect(bounds)
            if (!body.intersect(0, 0, view.width, view.height)) return null
            val areaScale = bounds.width().toFloat() * bounds.height() / (body.width().toFloat() * body.height())
            return ParticleCardSnapshot(bounds, z, x, y, lightZ, radius, light.ambient, light.spot, areaScale,
                rotation)
        }
    }
}
