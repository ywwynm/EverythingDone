package com.ywwynm.everythingdone.views.particledismiss

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** 飘散的模拟坐标与绘制工作面几何，供渲染器和离屏回归共同使用。 */
internal object ParticleDriftCanvas {
    data class Layout(
        val simulationWidth: Int,
        val simulationHeight: Int,
        val simulationLeft: Float,
        val simulationTop: Float,
        val workLeft: Float,
        val workTop: Float,
        val workWidth: Int,
        val workHeight: Int
    ) {
        val workRight get() = workLeft + workWidth * 2f
        val workBottom get() = workTop + workHeight * 2f
        val particleOffsetX get() = (simulationLeft - workLeft) * .5f
        val particleOffsetY get() = (simulationTop - workTop) * .5f
    }

    fun layout(viewportWidth: Int, viewportHeight: Int, snapshotWidth: Int, snapshotHeight: Int,
               originX: Float, originY: Float, expansion: FloatArray? = null): Layout {
        // 旧扩展尺寸仍决定模拟中的绝对坐标与湍流取样，但不再限制粒子可见范围。
        val width = (expansion?.get(0) ?: max(min(viewportWidth.toFloat(), 2100f), snapshotWidth.toFloat())).toInt()
        val height = (expansion?.get(1) ?: max(min(viewportHeight * .85f, 2400f), snapshotHeight.toFloat())).toInt()
        val left = originX + snapshotWidth * .5f - width * .5f
        val top = originY + snapshotHeight * .5f - height * .5f
        // 扩展尺寸仅保留为模拟基准。工作面覆盖完整窗口，不再围绕面板设置内部裁剪边界。
        // 每个工作面像素对应两个设备像素；相对于旧原点扩展整格，保持原有取样相位。
        val workLeft = left + floor(-left * .5f) * 2f
        val workTop = top + floor(-top * .5f) * 2f
        val workWidth = max(1, ceil((viewportWidth - workLeft) * .5f).toInt())
        val workHeight = max(1, ceil((viewportHeight - workTop) * .5f).toInt())
        return Layout(width, height, left, top, workLeft, workTop, workWidth, workHeight)
    }
}
