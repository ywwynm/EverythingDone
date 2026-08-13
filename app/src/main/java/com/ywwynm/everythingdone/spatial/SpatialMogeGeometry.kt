package com.ywwynm.everythingdone.spatial

import kotlin.math.roundToInt

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 从 MoGe-2 的 point map 恢复**米制深度与相机内参**。
 *
 * 这是端上做**真透视重投影**的前提。现有的 `depth_anything_*` 只给相对深度、没有内参，
 * 因此几何只能退化成屏幕空间位移场——用户 2026-08-12 的原话是"不像空间照片，像是根据
 * 传感器数据直接对图片做 warp"（D204）。
 *
 * MoGe 的 ONNX 只吐 `points / normal / mask / scale`，**内参不在图里**：官方
 * `model.infer()` 里那步 `recover_focal_shift` 是 Python 侧用 `scipy.optimize.least_squares`
 * 做的，端上没有 scipy。但那个问题的结构很好：
 *
 *     min_shift  Σ | f(shift) · xy/(z+shift) − uv |²
 *     其中      f(shift) = Σ(xy_proj·uv) / Σ|xy_proj|²      ← 对给定 shift 有**闭式**内解
 *
 * 于是只剩一个一维标量优化，用**黄金分割搜索**即可：无导数、无依赖、确定性。
 * 桌面对拍（00 场景、ViT-S）：fx 633.9 vs PyTorch 634.7（**0.124%**），
 * 逐像素 Z 相对误差中位 **0.232%**、p99 1.370%（D205）。
 *
 * **单位**：MoGe 的 `focal` 在 normalized view plane 上——u 的跨度是
 * `2·aspect/√(1+aspect²)` 且对应整幅宽度，所以 1 单位 u = **半对角线**像素数。
 * 桌面第一版按整条对角线换算，结果整整大一倍（1267.8 vs 634.7），与参考实现对拍才查出来。
 */
internal object SpatialMogeGeometry {

    /** ViT patch 边长。MoGe 的两边都必须是它的倍数。 */
    const val PATCH = 14

    private fun alignDownToPatch(value: Int): Int =
        (value / PATCH).coerceAtLeast(2) * PATCH

    /**
     * 在**保持长宽比**的前提下把两边都对齐到 patch 的倍数。
     *
     * 各自向下取整会引入各向异性：540×720 按长边 518 缩放后是 388.5×518，宽被截到 378，
     * 等价于把图横向多压了 2.7%——而 MoGe 假定 `fx == fy`，这份形变会原样进到内参里。
     * 这里在候选格点里挑长宽比误差最小的一组（同误差时取面积大的，不白扔分辨率）。
     */
    fun alignToPatchPreservingAspect(
        sourceWidth: Int,
        sourceHeight: Int,
        longEdgeLimit: Int
    ): Pair<Int, Int> {
        val scale = minOf(1f, longEdgeLimit.toFloat() / maxOf(sourceWidth, sourceHeight))
        val targetAspect = sourceWidth.toDouble() / sourceHeight
        var best: Pair<Int, Int>? = null
        var bestError = Double.MAX_VALUE
        // 每边在理论值附近各试几个格点：±1 个 patch 足以覆盖取整带来的偏差
        val baseWidth = alignDownToPatch((sourceWidth * scale).roundToInt())
        val baseHeight = alignDownToPatch((sourceHeight * scale).roundToInt())
        for (dw in -1..1) {
            for (dh in -1..1) {
                val w = baseWidth + dw * PATCH
                val h = baseHeight + dh * PATCH
                if (w < PATCH * 2 || h < PATCH * 2) continue
                if (maxOf(w, h) > longEdgeLimit) continue
                val error = kotlin.math.abs(w.toDouble() / h / targetAspect - 1.0)
                if (error < bestError - 1e-9 ||
                    (kotlin.math.abs(error - bestError) <= 1e-9 &&
                        best != null && w.toLong() * h > best!!.first.toLong() * best!!.second)
                ) {
                    bestError = error
                    best = w to h
                }
            }
        }
        return best ?: (baseWidth to baseHeight)
    }


    /** 下采样网格边长，与官方 `recover_focal_shift` 的 `downsample_size` 一致。 */
    const val SAMPLE_GRID = 64

    /** 黄金分割迭代次数。4096 点上 60 次即收敛到远小于模型自身误差。 */
    const val ITERATIONS = 60

    data class Recovered(
        /** 像素单位内参。MoGe 假定主点在画面中心、方形像素，因此 fx == fy。 */
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
        /** 米制深度 Z（米）。 */
        val depth: FloatArray,
        val shift: Float,
        val scale: Float,
        /** 参与求解的有效点数；过少时结果不可信。 */
        val sampleCount: Int
    )

    /**
     * 与 MoGe `normalized_view_plane_uv_numpy` 同式：按对角线归一、画面中心为原点。
     * 返回长度 2·width·height 的交错数组（u0,v0,u1,v1,…）。
     */
    fun normalizedViewPlaneUv(width: Int, height: Int): FloatArray {
        require(width > 1 && height > 1)
        val aspect = width.toFloat() / height
        val norm = sqrt(1f + aspect * aspect)
        val spanX = aspect / norm
        val spanY = 1f / norm
        val out = FloatArray(2 * width * height)
        for (y in 0 until height) {
            val v = lerpSpan(spanY, y, height)
            val row = y * width
            for (x in 0 until width) {
                val i = 2 * (row + x)
                out[i] = lerpSpan(spanX, x, width)
                out[i + 1] = v
            }
        }
        return out
    }

    /** `linspace(-span·(1−1/n), span·(1−1/n), n)` 的第 i 项。 */
    private fun lerpSpan(span: Float, index: Int, count: Int): Float {
        val edge = span * (1f - 1f / count)
        if (count == 1) return 0f
        return -edge + 2f * edge * index / (count - 1)
    }

    /** 给定 shift 时 focal 的闭式最优解。 */
    fun focalForShift(uv: FloatArray, xy: FloatArray, z: FloatArray, shift: Float): Float {
        var num = 0.0
        var den = 0.0
        for (i in z.indices) {
            val d = z[i] + shift
            if (abs(d) < 1e-6f) continue
            val px = xy[2 * i] / d
            val py = xy[2 * i + 1] / d
            num += px.toDouble() * uv[2 * i] + py.toDouble() * uv[2 * i + 1]
            den += px.toDouble() * px + py.toDouble() * py
        }
        return if (den > 1e-12) (num / den).toFloat() else 0f
    }

    private fun residual(uv: FloatArray, xy: FloatArray, z: FloatArray, shift: Float): Double {
        val f = focalForShift(uv, xy, z, shift)
        var sum = 0.0
        for (i in z.indices) {
            val d = z[i] + shift
            if (abs(d) < 1e-6f) continue
            val ex = f * (xy[2 * i] / d) - uv[2 * i]
            val ey = f * (xy[2 * i + 1] / d) - uv[2 * i + 1]
            sum += ex.toDouble() * ex + ey.toDouble() * ey
        }
        return sum
    }

    /**
     * 黄金分割搜索 shift。搜索区间 `[-0.9·z_min, +4·z 跨度]` 覆盖 MoGe 实际的 shift 量级；
     * 残差在该区间上单峰，无导数即可收敛。
     */
    fun solveFocalShift(uv: FloatArray, xy: FloatArray, z: FloatArray): Pair<Float, Float> {
        if (z.isEmpty()) return 0f to 0f
        var zMin = Float.MAX_VALUE
        for (v in z) if (v < zMin) zMin = v
        val sorted = z.clone()
        sorted.sort()
        val span = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)] -
            sorted[sorted.size * 5 / 100]
        var a = -0.9f * zMin
        var b = a + 4f * (span + 1e-3f)
        val phi = 0.6180339887f
        var c = b - phi * (b - a)
        var d = a + phi * (b - a)
        var fc = residual(uv, xy, z, c)
        var fd = residual(uv, xy, z, d)
        repeat(ITERATIONS) {
            if (fc < fd) {
                b = d; d = c; fd = fc
                c = b - phi * (b - a)
                fc = residual(uv, xy, z, c)
            } else {
                a = c; c = d; fc = fd
                d = a + phi * (b - a)
                fd = residual(uv, xy, z, d)
            }
        }
        val shift = (a + b) / 2f
        return focalForShift(uv, xy, z, shift) to shift
    }

    /**
     * 从 point map 恢复内参与米制深度。
     *
     * @param points 交错的 [x,y,z]，长度 3·width·height，与 ONNX `points` 输出同序
     * @param mask   有效掩码（ONNX `mask` 经 sigmoid 后 > 0.5）
     * @param scale  ONNX `scale` 输出；米制 Z = (z + shift) · scale
     */
    fun recover(
        points: FloatArray,
        mask: BooleanArray,
        scale: Float,
        width: Int,
        height: Int
    ): Recovered {
        require(points.size == 3 * width * height) { "point map 尺寸不符" }
        require(mask.size == width * height) { "有效掩码尺寸不符" }

        val uvFull = normalizedViewPlaneUv(width, height)
        // 按官方语义在 64×64 网格上取样，只取有效点
        val stepY = maxOf(1, height / SAMPLE_GRID)
        val stepX = maxOf(1, width / SAMPLE_GRID)
        val uvList = ArrayList<Float>(2 * SAMPLE_GRID * SAMPLE_GRID)
        val xyList = ArrayList<Float>(2 * SAMPLE_GRID * SAMPLE_GRID)
        val zList = ArrayList<Float>(SAMPLE_GRID * SAMPLE_GRID)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val i = y * width + x
                val zz = points[3 * i + 2]
                if (mask[i] && zz.isFinite()) {
                    uvList.add(uvFull[2 * i]); uvList.add(uvFull[2 * i + 1])
                    xyList.add(points[3 * i]); xyList.add(points[3 * i + 1])
                    zList.add(zz)
                }
                x += stepX
            }
            y += stepY
        }
        val z = zList.toFloatArray()
        val uv = uvList.toFloatArray()
        val xy = xyList.toFloatArray()
        val (focal, shift) = if (z.size >= 16) {
            solveFocalShift(uv, xy, z)
        } else {
            0f to 0f
        }

        val diagonal = sqrt((width.toFloat() * width + height.toFloat() * height).toDouble()).toFloat()
        // 1 单位 u = 半对角线像素数（见类文档里的单位说明）
        val focalPx = focal * diagonal / 2f
        val depth = FloatArray(width * height)
        for (i in depth.indices) {
            depth[i] = (points[3 * i + 2] + shift) * scale
        }
        return Recovered(
            fx = focalPx,
            fy = focalPx,
            cx = width / 2f,
            cy = height / 2f,
            depth = depth,
            shift = shift,
            scale = scale,
            sampleCount = z.size
        )
    }
}
