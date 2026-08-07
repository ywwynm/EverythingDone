package com.ywwynm.everythingdone.spatial

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Spatial Photo Derivative v2 的纯几何部分。
 *
 * 深度值越大表示越靠近相机。cutRight/cutDown 是一等数据：任何上采样、正则化或网格构建都不得
 * 跨过这些连接断边。
 */
data class SpatialLdiLiteGeometry(
    val width: Int,
    val height: Int,
    val surfaceDepth: FloatArray,
    val backgroundDepth: FloatArray,
    val cutRight: BooleanArray,
    val cutDown: BooleanArray,
    val hiddenBackgroundMask: BooleanArray,
    val motionBasis: SpatialScreenSpaceMotionBasis? = null,
    val backgroundMotionBasis: SpatialScreenSpaceMotionBasis? = null
) {
    init {
        require(width > 1 && height > 1)
        require(surfaceDepth.size == width * height)
        require(backgroundDepth.size == width * height)
        require(cutRight.size == height * (width - 1))
        require(cutDown.size == (height - 1) * width)
        require(hiddenBackgroundMask.size == width * height)
        require(motionBasis == null ||
            (motionBasis.width == width && motionBasis.height == height))
        require(backgroundMotionBasis == null ||
            (backgroundMotionBasis.width == width && backgroundMotionBasis.height == height))
    }

    val hiddenBackgroundRatio: Float
        get() = hiddenBackgroundMask.count { it }.toFloat() /
            hiddenBackgroundMask.size
}

/**
 * 不依赖 Android API，便于在 JVM 测试中验证拓扑与形变预算。
 */
object SpatialLdiLiteGeometryBuilder {

    /**
     * 备料结果：上采样、（按需）引导滤波与边缘吸附之后、断边判定之前的中间态。
     * P2（design-2026-08-03）把构建拆成两段，让 ownership 关系解析可以在断边判定
     * 之前基于 [surface] 完成，再以运动归组门控断边。
     */
    data class Prepared(
        val colorPixels: IntArray,
        val width: Int,
        val height: Int,
        val surface: FloatArray,
        val rawInverse: FloatArray?,
        val maximumParallaxAmplitude: Float
    )

    fun prepare(
        colorPixels: IntArray,
        width: Int,
        height: Int,
        sourceDepth: SpatialDepthData,
        maximumParallaxAmplitude: Float =
            SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE
    ): Prepared {
        require(colorPixels.size == width * height)
        require(width > 1 && height > 1)
        val upsampled = upsampleDepth(sourceDepth, width, height)
        // 引导滤波是给糊边深度模型做图像边缘对齐的补偿；锐边模型（D53）的深度已贴合
        // 真实轮廓，再按亮度滤一遍反而在低对比区把边缘糊开又吸附回偏移位置，跳过。
        val guided = if (sourceDepth.sharpEdges) {
            upsampled
        } else {
            guidedFilterDepth(colorPixels, width, height, upsampled)
        }
        // 深度边缘锐化：上采样与引导滤波会把真实遮挡边摊成逐格差值低于断边阈值的
        // 过渡带（低 RGB 对比时尤甚），随后的断边判定漏检、正则化再把它磨成大范围
        // 宽坡——高强度下的画面扭曲与显露碎屑由此而来。先把短而陡的单调过渡带吸附
        // 回锐利台阶，断边判定与背景扩展就能按真实轮廓工作（D47）。
        val snapped = snapDepthEdges(guided, width, height)
        // 遮挡判定的信号选择（D56/D62）：depth 型模型用原始输出的尺度不变比值，
        // 它不代表真实米制；归一化视差只服务渲染幅度。
        val rawInverse = sourceDepth.rawInverseDepth?.let {
            upsampleGrid(it, sourceDepth.width, sourceDepth.height, width, height)
        }
        return Prepared(
            colorPixels = colorPixels,
            width = width,
            height = height,
            surface = snapped,
            rawInverse = rawInverse,
            maximumParallaxAmplitude = maximumParallaxAmplitude
        )
    }

    fun build(
        colorPixels: IntArray,
        width: Int,
        height: Int,
        sourceDepth: SpatialDepthData,
        maximumParallaxAmplitude: Float =
            SpatialRenderDepthStabilizer.MAX_PARALLAX_AMPLITUDE,
        ownershipGroups: ByteArray? = null
    ): SpatialLdiLiteGeometry = finish(
        prepared = prepare(
            colorPixels, width, height, sourceDepth, maximumParallaxAmplitude
        ),
        ownershipGroups = ownershipGroups
    )

    /**
     * P2 断边双门控（design-2026-08-03）：[ownershipGroups]（0 = 连续场景，非 0 =
     * 实例/装配组）参与断边判定——同一非 0 组内部无条件禁断（颈颚、手袖类内部撕裂
     * 机制性消失，内部浮雕保留），其余边沿用深度比遮挡判据。
     */
    fun finish(
        prepared: Prepared,
        ownershipGroups: ByteArray? = null
    ): SpatialLdiLiteGeometry {
        val colorPixels = prepared.colorPixels
        val width = prepared.width
        val height = prepared.height
        val snapped = prepared.surface
        val maximumParallaxAmplitude = prepared.maximumParallaxAmplitude
        require(ownershipGroups == null || ownershipGroups.size == width * height)
        // P3（design-2026-08-03）：断边判定前按实例归属修正深度。深度模型的边缘与
        // 真实轮廓存在 1–2 格错位（halo/渗色，发缘彩色碎屑根源），mask 归属比深度
        // 值更可信：边界带内像素回归本组邻居中位数，实例内部做同组保边抑噪。
        // 归一化场与原始逆深度场用同一邻居集合同步修正，保证比值判据看到的是
        // 修正后的几何。
        if (ownershipGroups != null) {
            refineDepthWithGroups(
                snapped, prepared.rawInverse, ownershipGroups, width, height
            )
        }
        val cuts = buildEdgeGraph(
            colorPixels, width, height, snapped, prepared.rawInverse, ownershipGroups
        )
        SpatialCutGraphCleaner.healSmallIslands(
            width = width,
            height = height,
            depth = snapped,
            cutRight = cuts.first,
            cutDown = cuts.second
        )
        // P4：SubjectLayer.protect 退役——归组门控（P2）与组引导修正（P3）已覆盖
        // 其"主体内部不被切碎"的职责，且不再整块钳平主体深度。
        val renderSource = snapped
        val renderCutRight = cuts.first
        val renderCutDown = cuts.second
        // D76：实例内残差软限幅。对象级伸长 = 幅度 × 物体内部深度跨度；满幅 0.12
        // 下大跨度物体可达 ~11% 长度变化（用户可辨的"形状改变"）。限幅只作用于
        // 实例组（人物/杯盘等有形状身份的对象）：组均值保留（层间弹出不变），
        // 残差过膝点按斜率压缩，满幅伸长回到 ~4.5%。连续场景（组 0，地板/墙面）
        // 不限幅——其延展读作透视，且承载全场景深扫掠。首版按连通分量限幅被真机
        // 证伪：手臂-桌面比值 <1.2 连通成巨型分量，均值吸走主体，全场跨度 126→56px。
        if (ownershipGroups != null) {
            limitInstanceResiduals(renderSource, ownershipGroups)
        }
        val surface = regularize(
            source = renderSource,
            width = width,
            height = height,
            cutRight = renderCutRight,
            cutDown = renderCutDown,
            maximumParallaxAmplitude = maximumParallaxAmplitude
        )
        val background = extendBackgroundDepth(
            surface,
            width,
            height,
            renderCutRight,
            renderCutDown,
            maximumParallaxAmplitude
        )
        return SpatialLdiLiteGeometry(
            width = width,
            height = height,
            surfaceDepth = surface,
            backgroundDepth = background.depth,
            cutRight = renderCutRight,
            cutDown = renderCutDown,
            hiddenBackgroundMask = background.hidden
        )
    }

    private fun upsampleDepth(
        source: SpatialDepthData,
        targetWidth: Int,
        targetHeight: Int
    ): FloatArray = upsampleGrid(source.values, source.width, source.height, targetWidth, targetHeight)

    private fun upsampleGrid(
        values: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): FloatArray {
        val result = FloatArray(targetWidth * targetHeight)
        for (targetY in 0 until targetHeight) {
            val sourceY = (targetY + 0.5f) * sourceHeight / targetHeight - 0.5f
            val y0 = kotlin.math.floor(sourceY).toInt().coerceIn(0, sourceHeight - 1)
            val y1 = (y0 + 1).coerceAtMost(sourceHeight - 1)
            val fy = (sourceY - y0).coerceIn(0f, 1f)
            for (targetX in 0 until targetWidth) {
                val sourceX = (targetX + 0.5f) * sourceWidth / targetWidth - 0.5f
                val x0 = kotlin.math.floor(sourceX).toInt().coerceIn(0, sourceWidth - 1)
                val x1 = (x0 + 1).coerceAtMost(sourceWidth - 1)
                val fx = (sourceX - x0).coerceIn(0f, 1f)
                val top = lerp(
                    values[y0 * sourceWidth + x0],
                    values[y0 * sourceWidth + x1],
                    fx
                )
                val bottom = lerp(
                    values[y1 * sourceWidth + x0],
                    values[y1 * sourceWidth + x1],
                    fx
                )
                result[targetY * targetWidth + targetX] = lerp(top, bottom, fy)
            }
        }
        return result
    }

    private fun guidedFilterDepth(
        colorPixels: IntArray,
        width: Int,
        height: Int,
        source: FloatArray
    ): FloatArray {
        val guidance = FloatArray(source.size)
        val guidanceSquared = FloatArray(source.size)
        val cross = FloatArray(source.size)
        for (index in source.indices) {
            val color = colorPixels[index]
            val red = ((color ushr 16) and 0xff) / 255f
            val green = ((color ushr 8) and 0xff) / 255f
            val blue = (color and 0xff) / 255f
            val luminance = 0.299f * red + 0.587f * green + 0.114f * blue
            guidance[index] = luminance
            guidanceSquared[index] = luminance * luminance
            cross[index] = luminance * source[index]
        }
        val meanGuidance = boxMean(guidance, width, height, GUIDED_RADIUS)
        val meanSource = boxMean(source, width, height, GUIDED_RADIUS)
        val meanGuidanceSquared = boxMean(
            guidanceSquared, width, height, GUIDED_RADIUS
        )
        val meanCross = boxMean(cross, width, height, GUIDED_RADIUS)
        val coefficientA = FloatArray(source.size)
        val coefficientB = FloatArray(source.size)
        for (index in source.indices) {
            val variance = meanGuidanceSquared[index] -
                meanGuidance[index] * meanGuidance[index]
            val covariance = meanCross[index] -
                meanGuidance[index] * meanSource[index]
            coefficientA[index] = covariance / (variance + GUIDED_EPSILON)
            coefficientB[index] = meanSource[index] -
                coefficientA[index] * meanGuidance[index]
        }
        val meanA = boxMean(coefficientA, width, height, GUIDED_RADIUS)
        val meanB = boxMean(coefficientB, width, height, GUIDED_RADIUS)
        return FloatArray(source.size) { index ->
            (meanA[index] * guidance[index] + meanB[index]).coerceIn(0f, 1f)
        }
    }

    private fun boxMean(
        source: FloatArray,
        width: Int,
        height: Int,
        radius: Int
    ): FloatArray {
        val stride = width + 1
        val integral = DoubleArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0.0
            for (x in 0 until width) {
                rowSum += source[y * width + x]
                integral[(y + 1) * stride + x + 1] =
                    integral[y * stride + x + 1] + rowSum
            }
        }
        val result = FloatArray(source.size)
        for (y in 0 until height) {
            val top = (y - radius).coerceAtLeast(0)
            val bottom = (y + radius + 1).coerceAtMost(height)
            for (x in 0 until width) {
                val left = (x - radius).coerceAtLeast(0)
                val right = (x + radius + 1).coerceAtMost(width)
                val sum = integral[bottom * stride + right] -
                    integral[top * stride + right] -
                    integral[bottom * stride + left] +
                    integral[top * stride + left]
                result[y * width + x] = (
                    sum / ((right - left) * (bottom - top))
                    ).toFloat()
            }
        }
        return result
    }

    /**
     * 把「短而陡的单调过渡带」吸附成锐利台阶：带内逐格差同向且 ≥ [SNAP_MIN_STEP]、
     * 长度 ≤ [SNAP_MAX_BAND]、总落差 ≥ [HARD_DEPTH_EDGE] 时，以带内最陡的一条边为
     * 分界，两侧分别吸附到带端点的深度。真实的缓坡（逐格差低于下限）与长距离陡坡
     * （超过带长上限）都不受影响。行、列两个方向先后处理。
     */
    private fun snapDepthEdges(
        source: FloatArray,
        width: Int,
        height: Int
    ): FloatArray {
        val result = source.copyOf()
        for (y in 0 until height) {
            snapLine(result, offset = y * width, stride = 1, count = width)
        }
        for (x in 0 until width) {
            snapLine(result, offset = x, stride = width, count = height)
        }
        return result
    }

    private fun snapLine(values: FloatArray, offset: Int, stride: Int, count: Int) {
        var start = 0
        while (start < count - 1) {
            val firstDelta = values[offset + (start + 1) * stride] -
                values[offset + start * stride]
            if (abs(firstDelta) < SNAP_MIN_STEP) {
                start++
                continue
            }
            val ascending = firstDelta > 0f
            var end = start + 1
            while (end < count - 1) {
                val delta = values[offset + (end + 1) * stride] -
                    values[offset + end * stride]
                if (abs(delta) < SNAP_MIN_STEP || (delta > 0f) != ascending) break
                end++
            }
            val length = end - start
            val total = abs(
                values[offset + end * stride] - values[offset + start * stride]
            )
            if (length in 2..SNAP_MAX_BAND && total >= HARD_DEPTH_EDGE) {
                var steepest = start
                var steepestDelta = 0f
                for (i in start until end) {
                    val delta = abs(
                        values[offset + (i + 1) * stride] - values[offset + i * stride]
                    )
                    if (delta > steepestDelta) {
                        steepestDelta = delta
                        steepest = i
                    }
                }
                val low = values[offset + start * stride]
                val high = values[offset + end * stride]
                for (i in start + 1..steepest) values[offset + i * stride] = low
                for (i in steepest + 1 until end) values[offset + i * stride] = high
            }
            start = end
        }
    }

    /**
     * D76：按实例组保留均值、软限幅残差（组 0 = 连续场景，不处理）。
     */
    private fun limitInstanceResiduals(
        depth: FloatArray,
        groups: ByteArray
    ) {
        val sums = DoubleArray(256)
        val counts = IntArray(256)
        for (index in depth.indices) {
            val group = groups[index].toInt() and 0xff
            if (group == 0) continue
            sums[group] += depth[index]
            counts[group]++
        }
        val means = FloatArray(256)
        for (group in 1 until 256) {
            if (counts[group] > 0) means[group] = (sums[group] / counts[group]).toFloat()
        }
        for (index in depth.indices) {
            val group = groups[index].toInt() and 0xff
            if (group == 0 || counts[group] == 0) continue
            val mean = means[group]
            val residual = depth[index] - mean
            val magnitude = abs(residual)
            if (magnitude <= RESIDUAL_KNEE) continue
            val limited = RESIDUAL_KNEE +
                (magnitude - RESIDUAL_KNEE) * RESIDUAL_EXCESS_SLOPE
            depth[index] = (mean + if (residual > 0f) limited else -limited)
                .coerceIn(0f, 1f)
        }
    }

    /**
     * P3 组引导深度修正。带宽 [GROUP_BAND_RADIUS] 内（任一 4 邻域跨组即为边界格）：
     * 逐格取同组 5×5 邻居的中位数（两场共用同一邻居集合）；实例内部（非 0 组、
     * 带外）做同组 3×3 中位抑噪。只依赖归属与深度，不读图片内容。
     */
    private fun refineDepthWithGroups(
        normalized: FloatArray,
        rawInverse: FloatArray?,
        groups: ByteArray,
        width: Int,
        height: Int
    ) {
        val size = width * height
        val band = BooleanArray(size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val group = groups[index]
                val boundary =
                    (x + 1 < width && groups[index + 1] != group) ||
                        (y + 1 < height && groups[index + width] != group)
                if (!boundary) continue
                val top = max(0, y - GROUP_BAND_RADIUS)
                val bottom = min(height - 1, y + GROUP_BAND_RADIUS)
                val left = max(0, x - GROUP_BAND_RADIUS)
                val right = min(width - 1, x + GROUP_BAND_RADIUS)
                for (bandY in top..bottom) {
                    for (bandX in left..right) {
                        band[bandY * width + bandX] = true
                    }
                }
            }
        }

        fun medianAssign(index: Int, radius: Int) {
            val x = index % width
            val y = index / width
            val group = groups[index]
            val neighborIndices = ArrayList<Int>((2 * radius + 1) * (2 * radius + 1))
            for (nY in max(0, y - radius)..min(height - 1, y + radius)) {
                for (nX in max(0, x - radius)..min(width - 1, x + radius)) {
                    val neighbor = nY * width + nX
                    if (groups[neighbor] == group) neighborIndices.add(neighbor)
                }
            }
            if (neighborIndices.size < 3) return
            val normalizedValues = FloatArray(neighborIndices.size) {
                normalized[neighborIndices[it]]
            }
            normalizedValues.sort()
            normalized[index] = normalizedValues[normalizedValues.size / 2]
            if (rawInverse != null) {
                val rawValues = FloatArray(neighborIndices.size) {
                    rawInverse[neighborIndices[it]]
                }
                rawValues.sort()
                rawInverse[index] = rawValues[rawValues.size / 2]
            }
        }

        repeat(GROUP_BAND_PASSES) {
            for (index in 0 until size) {
                if (band[index]) medianAssign(index, GROUP_BAND_RADIUS)
            }
        }
        for (index in 0 until size) {
            if (!band[index] && groups[index] != ZERO_GROUP) {
                medianAssign(index, 1)
            }
        }
    }

    private fun buildEdgeGraph(
        colors: IntArray,
        width: Int,
        height: Int,
        depth: FloatArray,
        rawInverse: FloatArray? = null,
        ownershipGroups: ByteArray? = null
    ): Pair<BooleanArray, BooleanArray> {
        val cutRight = BooleanArray(height * (width - 1))
        val cutDown = BooleanArray((height - 1) * width)
        fun edgeCut(first: Int, second: Int): Boolean {
            // P2 双门控（design-2026-08-03）：同一实例/装配组内部无条件连续——
            // 内部撕裂机制性消失，内部浮雕保留；其余边沿用深度比遮挡判据。
            if (ownershipGroups != null) {
                val firstGroup = ownershipGroups[first]
                if (firstGroup != ZERO_GROUP && firstGroup == ownershipGroups[second]) {
                    return false
                }
            }
            return if (rawInverse != null) {
                isRawDepthOcclusion(rawInverse[first], rawInverse[second])
            } else {
                val difference = abs(depth[first] - depth[second])
                difference >= HARD_DEPTH_EDGE ||
                    (
                        difference >= GUIDED_DEPTH_EDGE &&
                            colorDifference(colors[first], colors[second]) >=
                            RGB_EDGE_THRESHOLD
                        )
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width - 1) {
                val leftIndex = y * width + x
                cutRight[y * (width - 1) + x] = edgeCut(leftIndex, leftIndex + 1)
            }
        }
        for (y in 0 until height - 1) {
            for (x in 0 until width) {
                val topIndex = y * width + x
                cutDown[y * width + x] = edgeCut(topIndex, topIndex + width)
            }
        }
        return cutRight to cutDown
    }

    /**
     * 原始 depth 比值遮挡判据（D56/D62）：远/近深度比 ≥ [OCCLUSION_DEPTH_RATIO]。
     * 比值消除全局尺度，但不把模型输出宣称为 metric depth。
     * 逆深度上 z_far/z_near = invNear/invFar；0（无穷远）对任何有限深度都是遮挡。
     */
    private fun isRawDepthOcclusion(first: Float, second: Float): Boolean {
        val near = max(first, second)
        val far = min(first, second)
        if (near <= METRIC_INFINITY_EPSILON) return false
        if (far <= METRIC_INFINITY_EPSILON) return true
        return near / far >= OCCLUSION_DEPTH_RATIO
    }

    private fun regularize(
        source: FloatArray,
        width: Int,
        height: Int,
        cutRight: BooleanArray,
        cutDown: BooleanArray,
        maximumParallaxAmplitude: Float
    ): FloatArray {
        val lower = source.copyOf()
        val upper = source.copyOf()
        val maximumDelta = MAXIMUM_JACOBIAN_DELTA /
            (maximumParallaxAmplitude * max(width, height))
        repeat(REGULARIZATION_PASSES) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    if (x > 0 && !cutRight[y * (width - 1) + x - 1]) {
                        lower[index] = min(lower[index], lower[index - 1] + maximumDelta)
                        upper[index] = max(upper[index], upper[index - 1] - maximumDelta)
                    }
                    if (y > 0 && !cutDown[(y - 1) * width + x]) {
                        lower[index] = min(lower[index], lower[index - width] + maximumDelta)
                        upper[index] = max(upper[index], upper[index - width] - maximumDelta)
                    }
                }
            }
            for (y in height - 1 downTo 0) {
                for (x in width - 1 downTo 0) {
                    val index = y * width + x
                    if (x + 1 < width && !cutRight[y * (width - 1) + x]) {
                        lower[index] = min(lower[index], lower[index + 1] + maximumDelta)
                        upper[index] = max(upper[index], upper[index + 1] - maximumDelta)
                    }
                    if (y + 1 < height && !cutDown[y * width + x]) {
                        lower[index] = min(lower[index], lower[index + width] + maximumDelta)
                        upper[index] = max(upper[index], upper[index + width] - maximumDelta)
                    }
                }
            }
        }
        return FloatArray(source.size) { index ->
            ((lower[index] + upper[index]) * 0.5f).coerceIn(0f, 1f)
        }
    }

    private fun extendBackgroundDepth(
        depth: FloatArray,
        width: Int,
        height: Int,
        cutRight: BooleanArray,
        cutDown: BooleanArray,
        maximumParallaxAmplitude: Float
    ): BackgroundExtension {
        val result = depth.copyOf()
        val hidden = BooleanArray(depth.size)
        val bestDistance = IntArray(depth.size) { Int.MAX_VALUE }

        // rampFraction=0 为带内（写入远深度并标记隐藏、交补图）；>0 为带端渐坡——
        // 深度从远逐格过渡回目标自身深度，不标记隐藏。带端不再是 1 格硬跳，
        // 背景网格在带缘的三角形不会把颜色抹成整条拖影（D51）。
        fun offer(
            targetX: Int,
            targetY: Int,
            sourceX: Int,
            sourceY: Int,
            distance: Int,
            rampFraction: Float
        ) {
            if (targetX !in 0 until width || targetY !in 0 until height ||
                sourceX !in 0 until width || sourceY !in 0 until height
            ) {
                return
            }
            val target = targetY * width + targetX
            val source = sourceY * width + sourceX
            val candidate = lerp(depth[source], depth[target], rampFraction)
            val minimumGap = if (rampFraction > 0f) 0.002f else GUIDED_DEPTH_EDGE
            if (candidate >= depth[target] - minimumGap) return
            if (candidate > result[target] - 0.002f) {
                if (candidate >= result[target] + 0.002f ||
                    distance >= bestDistance[target]
                ) {
                    return
                }
            }
            result[target] = candidate
            bestDistance[target] = distance
            if (rampFraction <= 0f) hidden[target] = true
        }

        fun offerLine(
            band: Int,
            place: (distance: Int, rampFraction: Float) -> Unit
        ) {
            val total = band + BACKGROUND_SEAM_RAMP_CELLS
            repeat(total) { distance ->
                val fraction = if (distance < band) {
                    0f
                } else {
                    (distance - band + 1f) / (BACKGROUND_SEAM_RAMP_CELLS + 1f)
                }
                place(distance, fraction)
            }
        }

        for (y in 0 until height) {
            for (x in 0 until width - 1) {
                if (!cutRight[y * (width - 1) + x]) continue
                val left = depth[y * width + x]
                val right = depth[y * width + x + 1]
                val difference = abs(left - right)
                if (difference < HARD_DEPTH_EDGE) continue
                val band = max(
                    2,
                    ceil(
                        maximumParallaxAmplitude * width * difference *
                            BACKGROUND_BAND_SAFETY
                    ).toInt() + 2
                )
                if (left > right) {
                    offerLine(band) { distance, fraction ->
                        offer(x - distance, y, x + 1, y, distance, fraction)
                    }
                } else {
                    offerLine(band) { distance, fraction ->
                        offer(x + 1 + distance, y, x, y, distance, fraction)
                    }
                }
            }
        }
        for (y in 0 until height - 1) {
            for (x in 0 until width) {
                if (!cutDown[y * width + x]) continue
                val top = depth[y * width + x]
                val bottom = depth[(y + 1) * width + x]
                val difference = abs(top - bottom)
                if (difference < HARD_DEPTH_EDGE) continue
                val band = max(
                    2,
                    ceil(
                        maximumParallaxAmplitude * height * difference *
                            BACKGROUND_BAND_SAFETY
                    ).toInt() + 2
                )
                if (top > bottom) {
                    offerLine(band) { distance, fraction ->
                        offer(x, y - distance, x, y + 1, distance, fraction)
                    }
                } else {
                    offerLine(band) { distance, fraction ->
                        offer(x, y + 1 + distance, x, y, distance, fraction)
                    }
                }
            }
        }
        return BackgroundExtension(result, hidden)
    }

    private fun colorDifference(first: Int, second: Int): Float {
        val red = abs(((first ushr 16) and 0xff) - ((second ushr 16) and 0xff))
        val green = abs(((first ushr 8) and 0xff) - ((second ushr 8) and 0xff))
        val blue = abs((first and 0xff) - (second and 0xff))
        return (red + green + blue) / (3f * 255f)
    }

    private fun lerp(first: Float, second: Float, fraction: Float): Float =
        first + (second - first) * fraction

    private data class BackgroundExtension(
        val depth: FloatArray,
        val hidden: BooleanArray
    )

    private const val GUIDED_RADIUS = 6
    private const val GUIDED_EPSILON = 0.0025f
    private const val HARD_DEPTH_EDGE = 0.050f
    private const val GUIDED_DEPTH_EDGE = 0.025f
    private const val RGB_EDGE_THRESHOLD = 0.080f
    private const val MAXIMUM_JACOBIAN_DELTA = 0.18f
    private const val REGULARIZATION_PASSES = 3
    private const val BACKGROUND_BAND_SAFETY = 1.75f

    /** 参与吸附的过渡带逐格最小差值；低于它视为平缓表面，不吸附。 */
    private const val SNAP_MIN_STEP = 0.004f

    /**
     * 原始 depth 比值判据（D56/D62）：远侧比近侧远 20% 以上才算分离物体。连续人体/物体结构
     * （颈颚、手袖、特写物件）的相对深度差 ≲15%，分离物体（人-墙、人-人、车-树）
     * ≳30%；1.2 取中，五张测试图交叉验证分类正确。尺度不变，与场景内容无关。
     */
    private const val OCCLUSION_DEPTH_RATIO = 1.2f

    private const val ZERO_GROUP: Byte = 0

    /**
     * D76 残差膝点：分量内 ±0.09 的层次原样保留（面部/常规物体的内部跨度量级），
     * 超出部分按 [RESIDUAL_EXCESS_SLOPE] 压缩。极端残差 0.5 → 0.09+0.41×0.24≈0.19，
     * 满幅 0.12 下对象伸长 ≈ 2×0.19×0.12 ≈ 4.5%。
     */
    private const val RESIDUAL_KNEE = 0.09f

    /** D76 膝点外压缩斜率。 */
    private const val RESIDUAL_EXCESS_SLOPE = 0.24f

    /** P3 边界带半径（格）：深度-mask 错位通常 1–2 格，带宽取 2。 */
    private const val GROUP_BAND_RADIUS = 2

    /** P3 边界带中位数修正迭代次数。 */
    private const val GROUP_BAND_PASSES = 2

    /** 逆深度低于该值视为无穷远（引擎把非正深度写作 0）。 */
    private const val METRIC_INFINITY_EPSILON = 1e-6f

    /** 隐藏带端的深度渐坡长度（格）：把带缘的深度过渡摊到多格，避免整条拖影。 */
    private const val BACKGROUND_SEAM_RAMP_CELLS = 6

    /**
     * 吸附的过渡带最大长度（格）。引导滤波对系数再做一次 box 均值，等效两次
     * 半径 [GUIDED_RADIUS] 的模糊，halo 最宽约 4r+1 格；取 4r+2 覆盖。
     */
    private const val SNAP_MAX_BAND = 4 * GUIDED_RADIUS + 2
}
