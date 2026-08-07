package com.ywwynm.everythingdone.spatial

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 深度主导 + alpha 边缘带细化（matting PoC 第二轮定案公式的渲染侧实现）。
 *
 * 只在断边近侧（前景侧）的窄带内用 matte 接管表面层的显示 alpha，其余像素保持
 * 不透明——表面网格是全幅的，带外置透明会让背景板错误顶替真实内容。带内逐格做
 * 「matting 活性」门控（格内 matte 峰值 ≥ 0.5 才启用）：MODNet 在暗衣躯干的
 * 塌零区 matte 均匀趋 0、无梯度，被门控自动排除，不会蚀掉衣缘。
 */
object SpatialAlphaFusion {

    /** v19 的 600 长边网格用 4 格，约对应 1080 宽原图上 9–10 px。 */
    private const val REFERENCE_BAND_RADIUS_CELLS = 4
    private const val REFERENCE_MESH_LONG_EDGE = 600

    /** 带内格启用 matte 的最低峰值。 */
    private const val ACTIVE_CELL_MIN_PEAK = 0.5f

    /**
     * 生成表面层显示 alpha 平面（8-bit，255 = 不透明），尺寸 [targetWidth]×[targetHeight]
     * （背景板分辨率）。没有任何活性格时返回 null（跳过落盘与上传，行为与无 matting 一致）。
     */
    fun buildDisplayAlpha(
        geometry: SpatialLdiLiteGeometry,
        matte: SpatialAlphaData,
        targetWidth: Int,
        targetHeight: Int
    ): ByteArray? {
        require(targetWidth > 0 && targetHeight > 0)
        val meshWidth = geometry.width
        val meshHeight = geometry.height
        val bandRadiusCells = bandRadiusCells(meshWidth, meshHeight)

        // 1. 断边近侧格（深度更大的一侧，与 carveForegroundRim 同判据）。
        val nearSide = BooleanArray(meshWidth * meshHeight)
        for (y in 0 until meshHeight) {
            for (x in 0 until meshWidth - 1) {
                if (!geometry.cutRight[y * (meshWidth - 1) + x]) continue
                val left = y * meshWidth + x
                if (geometry.surfaceDepth[left] > geometry.surfaceDepth[left + 1]) {
                    nearSide[left] = true
                } else {
                    nearSide[left + 1] = true
                }
            }
        }
        for (y in 0 until meshHeight - 1) {
            for (x in 0 until meshWidth) {
                if (!geometry.cutDown[y * meshWidth + x]) continue
                val top = y * meshWidth + x
                if (geometry.surfaceDepth[top] > geometry.surfaceDepth[top + meshWidth]) {
                    nearSide[top] = true
                } else {
                    nearSide[top + meshWidth] = true
                }
            }
        }

        // 2. 保持近似固定像素宽度的近侧带（显示专用，可跨断边扩散）。
        // vNext 网格长边为 256；若沿用 4 格会把真实软化带扩大约 2.3 倍。
        val band = BooleanArray(nearSide.size)
        for (y in 0 until meshHeight) {
            for (x in 0 until meshWidth) {
                if (!nearSide[y * meshWidth + x]) continue
                val top = max(0, y - bandRadiusCells)
                val bottom = min(meshHeight - 1, y + bandRadiusCells)
                val left = max(0, x - bandRadiusCells)
                val right = min(meshWidth - 1, x + bandRadiusCells)
                for (targetY in top..bottom) {
                    for (targetX in left..right) {
                        band[targetY * meshWidth + targetX] = true
                    }
                }
            }
        }

        // 3. 逐格 matte 峰值（在 matte 分辨率上一趟统计）。
        val cellPeak = FloatArray(nearSide.size)
        for (matteY in 0 until matte.height) {
            val meshY = (matteY * meshHeight / matte.height).coerceIn(0, meshHeight - 1)
            for (matteX in 0 until matte.width) {
                val meshX = (matteX * meshWidth / matte.width).coerceIn(0, meshWidth - 1)
                val index = meshY * meshWidth + meshX
                val value = matte.values[matteY * matte.width + matteX]
                if (value > cellPeak[index]) cellPeak[index] = value
            }
        }

        var anyActive = false
        val active = BooleanArray(nearSide.size) { index ->
            (band[index] && cellPeak[index] >= ACTIVE_CELL_MIN_PEAK).also {
                if (it) anyActive = true
            }
        }
        if (!anyActive) return null

        // 4. 目标分辨率平面：活性格内取双线性 matte，其余 255。
        val plane = ByteArray(targetWidth * targetHeight)
        for (targetY in 0 until targetHeight) {
            val meshY = (targetY * meshHeight / targetHeight).coerceIn(0, meshHeight - 1)
            val matteYPosition = (targetY + 0.5f) * matte.height / targetHeight - 0.5f
            val matteY0 = matteYPosition.toInt().coerceIn(0, matte.height - 1)
            val matteY1 = (matteY0 + 1).coerceAtMost(matte.height - 1)
            val fractionY = (matteYPosition - matteY0).coerceIn(0f, 1f)
            for (targetX in 0 until targetWidth) {
                val meshX = (targetX * meshWidth / targetWidth).coerceIn(0, meshWidth - 1)
                val index = targetY * targetWidth + targetX
                if (!active[meshY * meshWidth + meshX]) {
                    plane[index] = 0xff.toByte()
                    continue
                }
                val matteXPosition = (targetX + 0.5f) * matte.width / targetWidth - 0.5f
                val matteX0 = matteXPosition.toInt().coerceIn(0, matte.width - 1)
                val matteX1 = (matteX0 + 1).coerceAtMost(matte.width - 1)
                val fractionX = (matteXPosition - matteX0).coerceIn(0f, 1f)
                val top = lerp(
                    matte.values[matteY0 * matte.width + matteX0],
                    matte.values[matteY0 * matte.width + matteX1],
                    fractionX
                )
                val bottom = lerp(
                    matte.values[matteY1 * matte.width + matteX0],
                    matte.values[matteY1 * matte.width + matteX1],
                    fractionX
                )
                val alpha = lerp(top, bottom, fractionY).coerceIn(0f, 1f)
                plane[index] = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return plane
    }

    internal fun bandRadiusCells(meshWidth: Int, meshHeight: Int): Int =
        (max(meshWidth, meshHeight).toFloat() *
            REFERENCE_BAND_RADIUS_CELLS / REFERENCE_MESH_LONG_EDGE)
            .roundToInt()
            .coerceAtLeast(1)

    private fun lerp(first: Float, second: Float, fraction: Float): Float =
        first + (second - first) * fraction
}
