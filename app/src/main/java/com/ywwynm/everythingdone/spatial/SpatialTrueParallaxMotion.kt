package com.ywwynm.everythingdone.spatial

import kotlin.math.abs
import kotlin.math.max

/**
 * 由**米制深度 + 相机内参**直接构造真透视视差的屏幕空间位移基。
 *
 * 网页端一直用的是这套公式（D146 验证过）：
 *
 *     X = (u − cx)·Z / fx,   Y = (v − cy)·Z / fy
 *     u' = fx·(X − tx)/Z + cx + fx·tx/Z0
 *     v' = fy·(Y − ty)/Z + cy + fy·ty/Z0
 *
 * 把 X 代回去，中间项全部消掉：
 *
 *     u' = u + fx·tx·(1/Z0 − 1/Z)
 *     v' = v + fy·ty·(1/Z0 − 1/Z)
 *
 * **所以真透视视差本身就是一个屏幕空间位移场**，与 [SpatialScreenSpaceMotionBasis] 的
 * 形式完全兼容——只要系数是上面那个，且满足两条：
 *
 * 1. **交叉项恒为零**：水平视点位移只产生水平像素位移；
 * 2. **两个主项同源**，都正比于同一个标量场 `1/Z0 − 1/Z`。
 *
 * 用户 2026-08-12 反馈端上"不像空间照片、像直接对图片做 warp"，实测那份运动基两条都不
 * 满足（交叉项跨度 0.26/0.30 不为零，主项跨度 0.273 vs 0.476 不同源）——那是局部刚性
 * 拟合的产物，不是视差（D204）。这里不做任何拟合与正则，直接按公式给。
 *
 * **amplitude 的单位随之变成米**（物理基线），与网页端的滑杆口径一致；
 * 而不是原来那个归一化的 0.16。
 */
internal object SpatialTrueParallaxMotion {

    /** 逆深度的数值下限，避免 Z→0 处炸开（无效像素在上游已被判到远平面）。 */
    private const val MIN_DEPTH_METERS = 0.05f

    data class Result(
        val basis: SpatialScreenSpaceMotionBasis,
        /** 支点深度（米）：该深度处位移恒为零，绕它转。 */
        val pivotDepth: Float,
        /** 在给定基线下，全图最大与最小水平位移之差（像素），即前后景相对视差。 */
        val relativeParallaxPixels: Float,
        /** 逐像素的 `1/Z0 − 1/Z`（单位 1/米）。这是位移的全部内容，两轴共用。 */
        val scalarField: FloatArray,
        /**
         * `sqrt(fx·fy)`：把 [scalarField] 换算成网格像素位移的系数。
         *
         * 两轴本应各用各的 `f`，但 surfel 渲染路径每个点只带**一个**标量
         * （shader：`targetUv -= uParallaxMotion * aMotionScalar * uScalarToUv`，
         * `uScalarToUv` 逐轴但与标量无关）。取几何平均，两轴各承担一半误差。
         *
         * 该误差**只来自** MoGe 输入的 patch-14 对齐把图各向异性地压了 2.7%
         * （见 followups）；fx、fy 在 MoGe 自己的坐标系里本来严格相等。修掉那处对齐后
         * `fx·scaleX == fy·scaleY`，几何平均即精确值。当前残差 ±1.4%/轴。
         */
        val pixelsPerScalar: Float
    )

    /**
     * 生成期算好的取景内缩比例：任意视点方向、给定幅度下，画面内容最多向外走多远。
     *
     * 运行时的 `SpatialSourceLock.coverMargin(amplitude)` 假定幅度是**归一化位移**，
     * 而真透视档的幅度单位是**米**，代进去得到的数没有意义。这里直接按落盘的位移场算：
     * 视点在单位圆内，最坏情况是某个方向上 `|vx·hX| + |vy·vY|` 取到最大，
     * 由柯西–施瓦茨，其上界是 `sqrt(hX² + vY²)` 的逐像素最大值。
     *
     * @param amplitudeMeters 幅度上限（米）。运行时强度只会把它调小，所以按上限算是安全的。
     * @param samplingGuard   采样保护：网格边缘的双线性取样需要的额外余量。
     */
    fun coverMarginFraction(
        result: Result,
        amplitudeMeters: Float,
        samplingGuard: Float = 0.012f,
        maximum: Float = 0.20f
    ): Float {
        var worst = 0f
        val hx = result.basis.horizontalX
        val vy = result.basis.verticalY
        for (i in hx.indices) {
            val h = hx[i] * amplitudeMeters
            val v = vy[i] * amplitudeMeters
            val reach = kotlin.math.sqrt(h * h + v * v)
            if (reach > worst) worst = reach
        }
        return (worst + samplingGuard).coerceIn(samplingGuard, maximum)
    }

    /**
     * 把 [Result] 换算成 surfel 路径的逐点标量。
     *
     * shader 是 `targetUv = aSourceUv − uParallaxMotion * aMotionScalar * uScalarToUv`，
     * `uScalarToUv = 1/(边长 · P)`，`uParallaxMotion = 视点 · 幅度`。要让它等于
     * 网页端的 `Δu = fx·tx·(1/Z0 − 1/Z)`（幅度即 tx，单位米）：
     *
     *     −scalar/(W·P) = fx/W   ⇒   scalar = −P · fx · s
     *
     * **负号不能省**：shader 那个减号是 V13 定下的（近景标量为正、向视点反向移动）；
     * 而 `s = 1/Z0 − 1/Z` 在近景为负，两边符号正好相反。
     */
    fun surfelScalars(result: Result, requestedMaximumParallax: Float): FloatArray {
        require(requestedMaximumParallax > 0f)
        val k = -requestedMaximumParallax * result.pixelsPerScalar
        return FloatArray(result.scalarField.size) { result.scalarField[it] * k }
    }

    /**
     * @param depth   米制 Z，逐像素，长度 width·height
     * @param fx, fy  像素单位内参（与 width/height 同一分辨率）
     * @param subject 主体掩码；支点取主体深度中位数，没有就取全图中位数（与网页端同口径）
     * @param baselineMeters 用于报告相对视差的基线；不影响基系数本身
     */
    fun build(
        depth: FloatArray,
        width: Int,
        height: Int,
        fx: Float,
        fy: Float,
        subject: BooleanArray? = null,
        baselineMeters: Float = 0.045f
    ): Result {
        require(depth.size == width * height) { "深度尺寸不符" }
        require(width > 1 && height > 1)
        require(fx > 0f && fy > 0f) { "内参无效" }

        val pivot = pivotDepth(depth, subject)
        val invPivot = 1f / max(pivot, MIN_DEPTH_METERS)

        val size = width * height
        val horizontalX = FloatArray(size)
        val horizontalY = FloatArray(size)   // 恒为 0
        val verticalX = FloatArray(size)     // 恒为 0
        val verticalY = FloatArray(size)
        val scalarField = FloatArray(size)
        var minH = Float.MAX_VALUE
        var maxH = -Float.MAX_VALUE
        for (i in 0 until size) {
            val z = depth[i]
            val invZ = if (z.isFinite() && z > MIN_DEPTH_METERS) 1f / z else invPivot
            val scalar = invPivot - invZ
            scalarField[i] = scalar
            // 基是「每米基线的归一化位移」；amplitude 传米，两者相乘即归一化位移
            horizontalX[i] = fx * scalar / width
            verticalY[i] = fy * scalar / height
            if (horizontalX[i] < minH) minH = horizontalX[i]
            if (horizontalX[i] > maxH) maxH = horizontalX[i]
        }
        return Result(
            basis = SpatialScreenSpaceMotionBasis(
                width = width,
                height = height,
                horizontalX = horizontalX,
                horizontalY = horizontalY,
                verticalX = verticalX,
                verticalY = verticalY
            ),
            pivotDepth = pivot,
            relativeParallaxPixels = (maxH - minH) * baselineMeters * width,
            scalarField = scalarField,
            pixelsPerScalar = kotlin.math.sqrt(fx * fy)
        )
    }

    /**
     * 支点：主体深度的中位数；没有主体掩码时取全图中位数。与网页端
     * `export_moge_geometry.py` 的 `pivot = median(z[matte > 0.5])` 同口径——支点处位移
     * 为零，所以它决定"什么东西钉在屏幕上不动"。
     */
    fun pivotDepth(depth: FloatArray, subject: BooleanArray?): Float {
        val pool = ArrayList<Float>(depth.size / 4 + 1)
        if (subject != null) {
            require(subject.size == depth.size)
            for (i in depth.indices) {
                if (subject[i] && depth[i].isFinite() && depth[i] > MIN_DEPTH_METERS) {
                    pool.add(depth[i])
                }
            }
        }
        if (pool.isEmpty()) {
            for (v in depth) if (v.isFinite() && v > MIN_DEPTH_METERS) pool.add(v)
        }
        if (pool.isEmpty()) return 1f
        pool.sort()
        return pool[pool.size / 2]
    }

    /**
     * 把 matting 的连续 alpha 化成网格尺度的主体掩码，供 [pivotDepth] 取主体中位数。
     * 与网页端 `export_moge_geometry.py` 的 `matte > 0.5` 同阈值、同语义。
     *
     * **用最近邻取样**：这里要的是"哪些网格点属于主体"，双线性会在主体轮廓外圈造出
     * 0.5 附近的过渡值，把一圈背景像素并进主体，支点随之被背景拉偏。
     */
    fun subjectMaskFrom(
        alpha: FloatArray,
        alphaWidth: Int,
        alphaHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        threshold: Float = 0.5f
    ): BooleanArray {
        require(alpha.size == alphaWidth * alphaHeight)
        require(targetWidth > 0 && targetHeight > 0)
        val out = BooleanArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sy = ((y.toLong() * alphaHeight) / targetHeight).toInt()
                .coerceIn(0, alphaHeight - 1)
            for (x in 0 until targetWidth) {
                val sx = ((x.toLong() * alphaWidth) / targetWidth).toInt()
                    .coerceIn(0, alphaWidth - 1)
                out[y * targetWidth + x] = alpha[sy * alphaWidth + sx] > threshold
            }
        }
        return out
    }

    /**
     * 把米制深度重采样到渲染网格尺度。**用最近邻**：深度断崖处线性插值会造出介于前后景
     * 之间的假深度，那正是断边判定与显露带的依据所在，不能被插值抹平。
     */
    fun resampleDepth(
        depth: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): FloatArray {
        require(depth.size == sourceWidth * sourceHeight)
        if (sourceWidth == targetWidth && sourceHeight == targetHeight) return depth.copyOf()
        val out = FloatArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sy = ((y.toLong() * sourceHeight) / targetHeight).toInt()
                .coerceIn(0, sourceHeight - 1)
            for (x in 0 until targetWidth) {
                val sx = ((x.toLong() * sourceWidth) / targetWidth).toInt()
                    .coerceIn(0, sourceWidth - 1)
                out[y * targetWidth + x] = depth[sy * sourceWidth + sx]
            }
        }
        return out
    }

    /**
     * 把「目标视差（px@720）」换算成物理基线（米）——网页端就是这么把滑杆值变成基线的，
     * 各深度模型给出的绝对米制尺度不同（D148：同一场景 L/B/S 三档差 43%），
     * **幅度必须按目标视差归一，不能写死物理基线**。
     */
    fun baselineForTargetDisparity(
        result: Result,
        targetDisparityPx720: Float,
        longEdgePixels: Int
    ): Float {
        val perMeter = result.relativeParallaxPixels / max(0.045f, 1e-6f)
        if (abs(perMeter) < 1e-6f) return 0f
        val targetPx = targetDisparityPx720 * longEdgePixels / 720f
        return targetPx / perMeter
    }
}
