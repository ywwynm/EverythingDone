package com.ywwynm.everythingdone.spatial

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class SpatialMpiPlane(
    val depth: Float,
    val bitmap: Bitmap
)

/**
 * 从既有 Spatial Photo Derivative v2 构建小型 MPI（多平面图像）。实验路径，见
 * research-2026-07-31-mpi-3dgs-feasibility.md。
 *
 * 不运行任何模型：逐像素把表面层与隐藏背景层指派到最近的前平行平面。每个贡献保持
 * 不透明且只出现一次；不做跨层 alpha 羽化、空间盒模糊或预写身后颜色，避免渲染时
 * 重复 over 造成发灰、重影和清晰度损失。
 *
 * 返回列表按深度从远到近排序，直接按序 alpha 合成即可。
 */
object SpatialMpiBuilder {

    const val PLANE_COUNT = 10
    const val LONG_EDGE = 1200

    fun build(
        source: Bitmap,
        ldiLite: SpatialLdiLiteData
    ): List<SpatialMpiPlane> {
        val geometry = ldiLite.geometry
        val scale = min(1f, LONG_EDGE.toFloat() / max(source.width, source.height))
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val foreground = if (source.width == width && source.height == height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
        val background = Bitmap.createScaledBitmap(
            ldiLite.backgroundBitmap, width, height, true
        )
        try {
            val pixelCount = width * height
            val foregroundPixels = IntArray(pixelCount)
            foreground.getPixels(foregroundPixels, 0, width, 0, 0, width, height)
            val backgroundPixels = IntArray(pixelCount)
            background.getPixels(backgroundPixels, 0, width, 0, 0, width, height)

            // 每个像素每层至多保存一个不透明贡献。表面先写；隐藏背景若量化到同一层，
            // 不得覆盖已知原图。
            val planes = Array(PLANE_COUNT) { IntArray(pixelCount) }
            for (y in 0 until height) {
                val gy = (y + 0.5f) * geometry.height / height - 0.5f
                for (x in 0 until width) {
                    val gx = (x + 0.5f) * geometry.width / width - 0.5f
                    val index = y * width + x
                    val surfaceDepth = bilinear(
                        geometry.surfaceDepth, geometry.width, geometry.height, gx, gy
                    )
                    assignOpaque(planes, index, foregroundPixels[index], surfaceDepth)
                    if (nearestHidden(geometry, gx, gy)) {
                        val backgroundDepth = bilinear(
                            geometry.backgroundDepth,
                            geometry.width,
                            geometry.height,
                            gx,
                            gy
                        )
                        assignOpaque(
                            planes, index, backgroundPixels[index], backgroundDepth
                        )
                    }
                }
            }
            return List(PLANE_COUNT) { plane ->
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(planes[plane], 0, width, 0, 0, width, height)
                SpatialMpiPlane(
                    depth = plane.toFloat() / (PLANE_COUNT - 1),
                    bitmap = bitmap
                )
            }
        } finally {
            if (foreground !== source) foreground.recycle()
            background.recycle()
        }
    }

    private fun assignOpaque(
        planes: Array<IntArray>,
        index: Int,
        color: Int,
        depth: Float
    ) {
        val plane = (depth.coerceIn(0f, 1f) * (PLANE_COUNT - 1)).roundToInt()
        val target = planes[plane]
        if (target[index] == 0) {
            target[index] = color or OPAQUE_ALPHA
        }
    }

    private fun bilinear(
        values: FloatArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float
    ): Float {
        val x0 = kotlin.math.floor(x).toInt().coerceIn(0, width - 1)
        val y0 = kotlin.math.floor(y).toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val top = values[y0 * width + x0] * (1f - fx) + values[y0 * width + x1] * fx
        val bottom = values[y1 * width + x0] * (1f - fx) + values[y1 * width + x1] * fx
        return top * (1f - fy) + bottom * fy
    }

    private fun nearestHidden(
        geometry: SpatialLdiLiteGeometry,
        x: Float,
        y: Float
    ): Boolean {
        val gx = (x + 0.5f).toInt().coerceIn(0, geometry.width - 1)
        val gy = (y + 0.5f).toInt().coerceIn(0, geometry.height - 1)
        return geometry.hiddenBackgroundMask[gy * geometry.width + gx]
    }

    private const val OPAQUE_ALPHA = -0x1000000
}
