package com.ywwynm.everythingdone.taptransfer.ui.particles

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 圆角矩形的周长参数化：按弧长取点与切线。沿屏幕四边流动的粒子边框、卡片轮廓上的粒子都靠它
 * 定位。
 *
 * 弧长从上边左端（左上圆角结束处）开始，顺时针增加（屏幕坐标 y 向下）。向内的法线是切线顺时针
 * 转 90°：(−ty, tx)。
 */
class RoundRectPerimeter {

    var left: Float = 0f
        private set
    var top: Float = 0f
        private set
    var right: Float = 0f
        private set
    var bottom: Float = 0f
        private set
    var radius: Float = 0f
        private set

    /** 周长。 */
    var length: Float = 0f
        private set

    private var straightW = 0f
    private var straightH = 0f
    private var arc = 0f

    fun set(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
        val r = radius.coerceIn(0f, min(right - left, bottom - top) / 2f)
        this.radius = r
        straightW = (right - left - 2f * r).coerceAtLeast(0f)
        straightH = (bottom - top - 2f * r).coerceAtLeast(0f)
        arc = (PI / 2.0).toFloat() * r
        length = 2f * straightW + 2f * straightH + 4f * arc
    }

    /**
     * 取弧长 [s] 处的点，写进 [out]：`out[0..1]` 为位置，`out[2..3]` 为单位切线。[s] 可以是任意
     * 实数，按周长取模。
     */
    fun pointAt(s: Float, out: FloatArray) {
        if (length <= 0f) {
            out[0] = left; out[1] = top; out[2] = 1f; out[3] = 0f
            return
        }
        var d = s % length
        if (d < 0f) {
            d += length
        }
        val r = radius
        // 上边
        if (d < straightW) {
            line(left + r + d, top, 1f, 0f, out)
            return
        }
        d -= straightW
        if (d < arc) {
            corner(right - r, top + r, -PI / 2.0, d, out)
            return
        }
        d -= arc
        // 右边
        if (d < straightH) {
            line(right, top + r + d, 0f, 1f, out)
            return
        }
        d -= straightH
        if (d < arc) {
            corner(right - r, bottom - r, 0.0, d, out)
            return
        }
        d -= arc
        // 下边
        if (d < straightW) {
            line(right - r - d, bottom, -1f, 0f, out)
            return
        }
        d -= straightW
        if (d < arc) {
            corner(left + r, bottom - r, PI / 2.0, d, out)
            return
        }
        d -= arc
        // 左边
        if (d < straightH) {
            line(left, bottom - r - d, 0f, -1f, out)
            return
        }
        d -= straightH
        corner(left + r, top + r, PI, d.coerceAtMost(arc), out)
    }

    private fun line(x: Float, y: Float, tx: Float, ty: Float, out: FloatArray) {
        out[0] = x; out[1] = y; out[2] = tx; out[3] = ty
    }

    /** 从 [startAngle] 起顺时针（屏幕坐标里角度增加）走过弧长 [d]。 */
    private fun corner(cx: Float, cy: Float, startAngle: Double, d: Float, out: FloatArray) {
        val r = radius
        if (r <= 0f) {
            out[0] = cx; out[1] = cy
            out[2] = cos(startAngle + PI / 2.0).toFloat(); out[3] = sin(startAngle + PI / 2.0).toFloat()
            return
        }
        val a = startAngle + d / r
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        out[0] = cx + ca * r
        out[1] = cy + sa * r
        out[2] = -sa
        out[3] = ca
    }
}
