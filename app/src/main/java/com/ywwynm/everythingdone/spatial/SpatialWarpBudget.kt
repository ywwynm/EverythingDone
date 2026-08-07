package com.ywwynm.everythingdone.spatial

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * 根据渲染深度的梯度分布，为当前视差位移计算保形上限。
 *
 * 当前重投影可写作 `p' = p + m * d(p)`，其局部雅可比矩阵为
 * `I + m ⊗ ∇d`。附加项的算子范数不超过 `|m| * |∇d|`；把该值限制在
 * [MAX_DISPLACEMENT_GRADIENT] 内，可以同时约束局部拉伸、压缩和剪切，又不会改变
 * 用户视点移动的方向。常量深度或平缓深度不会被削弱。
 *
 * 统计量取 [BUDGET_PERCENTILE] 高分位而不是全图最大值：孤立坏点（深度噪声、极窄
 * halo）不再吞掉整图行程；分位以上的零星边表现为局部轻微拉伸，属于可接受代价
 * （见 research-2026-07-31-parallax-uplift.md）。折返（|m|·|∇d| ≥ 1）由
 * [SpatialRenderDepthStabilizer] 的逐格斜率钳制兜底，与本预算无关。
 */
internal object SpatialWarpBudget {

    /**
     * 局部映射相对恒等变换允许的最大偏差。0.32 对应约 0.68～1.32 局部尺度范围。
     */
    // 2026-08-04：0.32→0.36。P3 组引导修正消除了边界带伪陡后，p99.5 梯度反映的
    // 是真实褶皱坡；配合幅度 0.12（2.94 梯度下限幅 0.36/2.94=0.122 > 0.12，满幅
    // 不受钳制）。
    const val MAX_DISPLACEMENT_GRADIENT = 0.36f

    /** 预算统计的梯度高分位。 */
    const val BUDGET_PERCENTILE = 0.995f

    fun analyze(depth: SpatialDepthData): Profile {
        val width = depth.width
        val height = depth.height
        if (width <= 0 || height <= 0 || depth.values.isEmpty()) {
            return Profile(gradientNorm = 0f)
        }

        val horizontal = FloatArray(height * (width - 1).coerceAtLeast(0))
        val vertical = FloatArray((height - 1).coerceAtLeast(0) * width)
        var horizontalCount = 0
        var verticalCount = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val value = quantizedDepth(depth.values[index])
                if (x + 1 < width) {
                    val gradient =
                        abs(quantizedDepth(depth.values[index + 1]) - value) *
                            width / MAX_DEPTH_BYTE
                    if (gradient.isFinite()) horizontal[horizontalCount++] = gradient
                }
                if (y + 1 < height) {
                    val gradient =
                        abs(quantizedDepth(depth.values[index + width]) - value) *
                            height / MAX_DEPTH_BYTE
                    if (gradient.isFinite()) vertical[verticalCount++] = gradient
                }
            }
        }

        // 分别取两个方向的分位值再合成，略保守，但不会漏掉斜向运动的最坏组合。
        return Profile(
            gradientNorm = hypot(
                percentile(horizontal, horizontalCount),
                percentile(vertical, verticalCount)
            )
        )
    }

    /**
     * P1 的显式断边不应参与连续表面形变预算：这些位置没有跨边三角形，视差由背景层负责显露。
     * 只分析仍连接的边，既保留 P1 的有效视差，也继续约束每个连续表面内部的拉伸。
     */
    fun analyze(geometry: SpatialLdiLiteGeometry): Profile {
        val width = geometry.width
        val height = geometry.height
        val horizontal = FloatArray(height * (width - 1))
        val vertical = FloatArray((height - 1) * width)
        var horizontalCount = 0
        var verticalCount = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val value = quantizedDepth(geometry.surfaceDepth[index])
                if (x + 1 < width && !geometry.cutRight[y * (width - 1) + x]) {
                    val gradient =
                        abs(quantizedDepth(geometry.surfaceDepth[index + 1]) - value) *
                            width / MAX_DEPTH_BYTE
                    if (gradient.isFinite()) horizontal[horizontalCount++] = gradient
                }
                if (y + 1 < height && !geometry.cutDown[y * width + x]) {
                    val gradient =
                        abs(quantizedDepth(geometry.surfaceDepth[index + width]) - value) *
                            height / MAX_DEPTH_BYTE
                    if (gradient.isFinite()) vertical[verticalCount++] = gradient
                }
            }
        }
        return Profile(
            gradientNorm = hypot(
                percentile(horizontal, horizontalCount),
                percentile(vertical, verticalCount)
            )
        )
    }

    private fun percentile(values: FloatArray, count: Int): Float {
        if (count <= 0) return 0f
        Arrays.sort(values, 0, count)
        return values[((count - 1) * BUDGET_PERCENTILE).toInt()]
    }

    internal data class Profile(
        val gradientNorm: Float
    ) {
        fun limitMotion(requestedX: Float, requestedY: Float): Motion {
            if (!requestedX.isFinite() || !requestedY.isFinite()) {
                return Motion(x = 0f, y = 0f, scale = 0f)
            }
            val magnitude = hypot(requestedX, requestedY)
            val risk = magnitude * gradientNorm
            if (magnitude <= MIN_NON_ZERO || risk <= MAX_DISPLACEMENT_GRADIENT) {
                return Motion(x = requestedX, y = requestedY, scale = 1f)
            }

            val scale = min(1f, MAX_DISPLACEMENT_GRADIENT / risk)
            return Motion(
                x = requestedX * scale,
                y = requestedY * scale,
                scale = scale
            )
        }
    }

    internal data class Motion(
        val x: Float,
        val y: Float,
        val scale: Float
    )

    private const val MIN_NON_ZERO = 0.000001f
    private const val MAX_DEPTH_BYTE = 255f

    /**
     * 必须与 [SpatialPhotoRenderer.createDepthTexture] 的 GL_LUMINANCE 上传规则一致。
     * 若直接分析浮点深度，8-bit 截断产生的局部梯度会漏出形变预算。
     */
    private fun quantizedDepth(value: Float): Int =
        (value.coerceIn(0f, 1f) * MAX_DEPTH_BYTE).toInt()
}
