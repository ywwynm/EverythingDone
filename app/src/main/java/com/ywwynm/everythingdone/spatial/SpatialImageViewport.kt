package com.ywwynm.everythingdone.spatial

import kotlin.math.roundToInt

/** 把动态放大的空间网格限制在参考图的固定矩形画框内。 */
internal object SpatialImageViewport {

    data class ScissorRect(
        val left: Int,
        val bottom: Int,
        val width: Int,
        val height: Int
    )

    fun scissor(
        surfaceWidth: Int,
        surfaceHeight: Int,
        scaleX: Float,
        scaleY: Float
    ): ScissorRect {
        require(surfaceWidth > 0 && surfaceHeight > 0)
        require(scaleX.isFinite() && scaleY.isFinite())
        val width = (surfaceWidth * scaleX.coerceIn(0f, 1f))
            .roundToInt()
            .coerceIn(1, surfaceWidth)
        val height = (surfaceHeight * scaleY.coerceIn(0f, 1f))
            .roundToInt()
            .coerceIn(1, surfaceHeight)
        return ScissorRect(
            left = (surfaceWidth - width) / 2,
            bottom = (surfaceHeight - height) / 2,
            width = width,
            height = height
        )
    }
}
