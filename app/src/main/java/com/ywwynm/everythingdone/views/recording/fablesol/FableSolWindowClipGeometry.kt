package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.roundToInt

/** 窗口轮廓转换为 Surface 缓冲坐标，预览的底边仍可保持平直。 */
internal data class FableSolWindowClipGeometry(
    val left: Int, val top: Int, val right: Int, val bottom: Int, val radius: Float
) {
    companion object {
        fun of(windowLeft: Int, windowTop: Int, windowRight: Int, windowBottom: Int,
               surfaceLeft: Int, surfaceTop: Int, viewWidth: Int, viewHeight: Int,
               bufferWidth: Int, bufferHeight: Int, radius: Float): FableSolWindowClipGeometry? {
            if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0 ||
                windowRight <= windowLeft || windowBottom <= windowTop || !radius.isFinite() || radius <= 0f) return null
            val sx = bufferWidth.toFloat() / viewWidth
            val sy = bufferHeight.toFloat() / viewHeight
            // 原生圆角半径只有一个值；非等比 Surface 不强行套用，避免椭圆变形。
            if (kotlin.math.abs(sx - sy) > .001f) return null
            return FableSolWindowClipGeometry(
                ((windowLeft - surfaceLeft) * sx).roundToInt(),
                ((windowTop - surfaceTop) * sy).roundToInt(),
                ((windowRight - surfaceLeft) * sx).roundToInt(),
                ((windowBottom - surfaceTop) * sy).roundToInt(), radius * sx)
        }
    }
}
