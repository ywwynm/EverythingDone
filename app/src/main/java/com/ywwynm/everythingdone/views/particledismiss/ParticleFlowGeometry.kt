package com.ywwynm.everythingdone.views.particledismiss

import kotlin.math.*

/** 控件纹理尺寸与局部流动尺度分开；长面板由平滑重叠的局部区域覆盖。 */
internal data class ParticleFlowGeometry(
    val width: Double, val height: Double, val blend: Double, val vertical: Boolean
) {
    fun rotation(direction: Float): Double {
        val angle = Math.toRadians(direction.toDouble())
        return atan2(sin(angle) / height, cos(angle) / width) - PI / 2
    }

    /** 每条材料网格行或列只算一次锚点；权重在整个动画内固定。 */
    fun anchors(count: Int, cardWidth: Float, cardHeight: Float, direction: Float, phase: Float): DoubleArray {
        val span = min(cardWidth, cardHeight).toDouble()
        val length = if (vertical) cardHeight else cardWidth
        val angle = Math.toRadians(direction.toDouble())
        val crossX = sin(angle); val crossY = cos(angle)
        return DoubleArray(count * 5).also { out ->
            for (j in 0 until count) {
                val coordinate = ((j + .5) / count - .5) * length / (span * .96)
                val index = floor(coordinate); val fraction = coordinate - index
                out[j * 5 + 4] = fraction * fraction * (3 - 2 * fraction)
                for (k in 0..1) {
                    val along = (index + k) * span * .96 * blend
                    val across = span * .16 * blend * sin((index + k) * 2.4 + phase)
                    out[j * 5 + k * 2] = (if (vertical) 0.0 else along) + crossX * across
                    out[j * 5 + k * 2 + 1] = (if (vertical) along else 0.0) + crossY * across
                }
            }
        }
    }

    companion object {
        /** 触点在该方向可用背景中的相对远近；屏幕边界不限制粒子位置。 */
        fun touchStrength(x: Float, y: Float, width: Float, height: Float,
            left: Float, top: Float, right: Float, bottom: Float): Float {
            val dx = x.toDouble() - width * .5; val dy = y.toDouble() - height * .5
            val distance = hypot(dx, dy)
            if (!distance.isFinite() || distance < 1e-6 || min(width, height) <= 0) return 0f
            val ux = dx / distance; val uy = dy / distance
            val edge = min(width / (2 * max(abs(ux), 1e-9)), height / (2 * max(abs(uy), 1e-9)))
            var limit = Double.POSITIVE_INFINITY
            if (abs(ux) > 1e-9) limit = min(limit, ((if (ux > 0) right else left) - width * .5) / ux)
            if (abs(uy) > 1e-9) limit = min(limit, ((if (uy > 0) bottom else top) - height * .5) / uy)
            return ((distance - edge) / max(limit - edge, 1.0)).coerceIn(0.0, 1.0).toFloat()
        }

        fun from(width: Float, height: Float): ParticleFlowGeometry {
            val span = min(width, height).toDouble()
            val ratio = max(width, height) / span
            val effective = if (ratio <= 1.2) ratio else 1.2 + .15 * (1 - exp(-(ratio - 1.2) / .15))
            val t = ((ratio - 1.2) / .6).coerceIn(0.0, 1.0)
            return ParticleFlowGeometry(if (width > height) span * effective else width.toDouble(),
                if (height >= width) span * effective else height.toDouble(), t * t * (3 - 2 * t), height >= width)
        }

        /** 沿中心到触点的射线，测量边缘外距离；结果以控件短边为单位。 */
        fun touchGap(x: Float, y: Float, width: Float, height: Float): Float {
            val dx = x.toDouble() - width * .5; val dy = y.toDouble() - height * .5
            val distance = hypot(dx, dy)
            if (!distance.isFinite() || distance < 1e-6 || min(width, height) <= 0) return 0f
            val edge = min(width / (2 * max(abs(dx / distance), 1e-9)),
                height / (2 * max(abs(dy / distance), 1e-9)))
            return max(0.0, (distance - edge) / min(width, height)).toFloat()
        }
    }
}
