package com.ywwynm.everythingdone.spatial

import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

/**
 * 为实时重投影生成仅用于渲染的深度副本。
 *
 * 单目深度在物体轮廓处可能从近景直接跳到远景。强视差会把这种跳变转换为逆向 UV
 * 折返，表现为人物边缘或细小物体被重复切片。这里分别计算不高于原深度的最大
 * Lipschitz 包络和不低于原深度的最小 Lipschitz 包络，再取二者中点；平坦区域保持
 * 原值，轮廓则形成斜率有界的连续过渡。
 *
 * 处理结果不会写回 Spatial Photo Derivative，也不参与默认强度计算。
 */
internal object SpatialRenderDepthStabilizer {

    /** 动态取景边距的硬上限；实际值由 [SpatialSourceLock] 按当前帧位移等比例计算。 */
    const val MAX_COVER_MARGIN = 0.09f
    // 2026-08-04 视角范围扩展：D55 退役（P3 组引导修正接管其职责）后模型层次
    // 完整还原，幅度 0.09→0.12；全帧刚性平移撤销（0.012→0，它不产生视差、只
    // 产生整帧滑动，却占取景边距——省下的边距抵消幅度增加的裁切代价，静止取景
    // 与 0.09 时代基本持平）。
    const val MIN_PARALLAX_AMPLITUDE = 0.024f
    const val MAX_PARALLAX_AMPLITUDE = 0.12f
    const val RIGID_PAN_AMPLITUDE = 0f

    /**
     * P0 渲染深度允许的最大归一化斜率（以满幅宽/高为 1 的单位），逐格允许的相邻差是
     * `MAX_RENDER_SLOPE / 网格宽（或高）`。`MAX_PARALLAX_AMPLITUDE × MAX_RENDER_SLOPE
     * = 0.96 < 1`，钳制后的深度在满幅视差下不可能出现 UV 折返。P1 不用这把尺子：
     * 其陡边按 [promoteSteepEdgesToCuts] 升格为断边，剩余连通边天然低于升格阈值。
     */
    const val MAX_RENDER_SLOPE = 8f

    const val RELIEF_GAIN = 0.18f
    const val MAX_RELIEF = 0.018f

    /**
     * 前向 splat 在最大相对视差下允许的连续表面局部应变。4% 是形状保持门槛：纹理可以
     * 整体移动，但相邻已知样本不能再像首轮实现那样产生肉眼可见的五官拉伸/压缩。
     * 显式遮挡 cut 不受此约束，由层间刚性深度差保留完整视差。
     */
    const val MAX_SPLAT_LOCAL_STRAIN = 0.04f

    fun stabilize(source: SpatialDepthData): SpatialDepthData {
        val width = source.width
        val height = source.height
        val deltaX = MAX_RENDER_SLOPE / width
        val deltaY = MAX_RENDER_SLOPE / height
        val lower = source.values.copyOf()
        val upper = source.values.copyOf()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (x > 0) {
                    lower[index] = min(lower[index], lower[index - 1] + deltaX)
                    upper[index] = max(upper[index], upper[index - 1] - deltaX)
                }
                if (y > 0) {
                    lower[index] = min(lower[index], lower[index - width] + deltaY)
                    upper[index] = max(upper[index], upper[index - width] - deltaY)
                }
            }
        }

        for (y in height - 1 downTo 0) {
            for (x in width - 1 downTo 0) {
                val index = y * width + x
                if (x + 1 < width) {
                    lower[index] = min(lower[index], lower[index + 1] + deltaX)
                    upper[index] = max(upper[index], upper[index + 1] - deltaX)
                }
                if (y + 1 < height) {
                    lower[index] = min(lower[index], lower[index + width] + deltaY)
                    upper[index] = max(upper[index], upper[index + width] - deltaY)
                }
            }
        }

        val stabilized = FloatArray(source.values.size) { index ->
            ((lower[index] + upper[index]) * 0.5f).coerceIn(0f, 1f)
        }
        return source.copy(values = stabilized)
    }


    /**
     * P1 连续表面的防扭曲处理：把「陡而未断」的连通边升格为断边，而不是钳成斜坡硬拉。
     *
     * 这些边绝大多数是深度上采样在真实遮挡轮廓两侧留下的过渡带；若按 D45 第一版钳成
     * 斜率有界的坡，满幅视差下坡上的局部形变仍高达
     * `MAX_PARALLAX_AMPLITUDE × MAX_RENDER_SLOPE = 0.81`，表现为高强度下的明显橡皮
     * 拉伸（2026-07-31 用户反馈）。升格为断边后，跳变交给背景层显露，连通面保持原始
     * 深度、不被包络磨平。
     *
     * 升格阈值 [promotedCutSlope] 由预算反推：留下的每条连通边在满幅视差下的单轴局部
     * 形变必然不超过 [SpatialWarpBudget.MAX_DISPLACEMENT_GRADIENT]，预算限幅对单轴
     * 陡度从此不再触发，只有双轴同陡的对角内容才会再收紧行程。斜率按量化后深度评估，
     * 与预算统计及 GPU 上传口径一致。
     */
    fun promoteSteepEdgesToCuts(geometry: SpatialLdiLiteGeometry): SpatialLdiLiteGeometry {
        val width = geometry.width
        val height = geometry.height
        val threshold = promotedCutSlope()
        val cutRight = geometry.cutRight.copyOf()
        val cutDown = geometry.cutDown.copyOf()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val value = quantized(geometry.surfaceDepth[index])
                if (x + 1 < width && !cutRight[y * (width - 1) + x]) {
                    val slope =
                        kotlin.math.abs(quantized(geometry.surfaceDepth[index + 1]) - value) *
                            width / MAX_DEPTH_BYTE
                    if (slope > threshold) cutRight[y * (width - 1) + x] = true
                }
                if (y + 1 < height && !cutDown[y * width + x]) {
                    val slope =
                        kotlin.math.abs(quantized(geometry.surfaceDepth[index + width]) - value) *
                            height / MAX_DEPTH_BYTE
                    if (slope > threshold) cutDown[y * width + x] = true
                }
            }
        }
        return geometry.copy(cutRight = cutRight, cutDown = cutDown)
    }

    /** 升格阈值（归一化斜率）：约 0.32 / 0.09 ≈ 3.56。 */
    fun promotedCutSlope(): Float =
        SpatialWarpBudget.MAX_DISPLACEMENT_GRADIENT / MAX_PARALLAX_AMPLITUDE

    private const val MAX_DEPTH_BYTE = 255f

    /** 与 [SpatialWarpBudget] 及 GL_LUMINANCE 上传保持同一量化口径。 */
    private fun quantized(value: Float): Int =
        (value.coerceIn(0f, 1f) * MAX_DEPTH_BYTE).toInt()
}
