package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用自有 P010 **整条通路**的门禁：编码器输入排布、两种呈现中间面的精度差、以及闭环亮度
 * 修正在真实降采样—量化—上采样序列上的效果。
 *
 * 单点数学在 [FableSolExportP010MathTest] 里已经钉住；这里钉的是把它们串起来之后仍然成立
 * 的性质——着色器一次跑的正是这条串。
 */
class FableSolExportP010PathTest {

    private val math = FableSolExportP010Math

    // ---- 编码器输入排布 ----

    /** 0 与缺失都是"没告诉我"，不能当真值：它们会让色度偏移和入队长度一起变成 0。 */
    @Test
    fun missingOrZeroStrideFallsBackToTheComputedLayout() {
        val computed = FableSolExportP010Layout.of(widthPx = 640, heightPx = 480)
        assertEquals(1280, computed.lumaRowStride)
        assertEquals(1280, computed.chromaRowStride)
        assertEquals(480, computed.sliceHeight)
        assertEquals(0, computed.lumaOffset)
        assertEquals(1280 * 480, computed.chromaOffset)
        assertEquals(1280 * 480 * 3 / 2, computed.frameBytes)

        val zeros = FableSolExportP010Layout.of(
            widthPx = 640, heightPx = 480, reportedStride = 0, reportedSliceHeight = 0
        )
        assertEquals(computed, zeros)
    }

    /** 回报的行距与平面高度大于最小值时一律采纳：对齐要求是编码器说了算的。 */
    @Test
    fun reportedStrideAndSliceHeightAreHonoured() {
        val layout = FableSolExportP010Layout.of(
            widthPx = 638, heightPx = 478, reportedStride = 1408, reportedSliceHeight = 480
        )
        assertEquals(1408, layout.lumaRowStride)
        assertEquals(480, layout.sliceHeight)
        assertEquals(1408 * 480, layout.chromaOffset)
        assertEquals(1408 * 480 * 3 / 2, layout.frameBytes)
        // 最后一行有效样本必须写得下。
        assertTrue(layout.requiredBytes >= 1408 * 480 + 1408 * 239 + 638 * 2)
    }

    /** crop 原点只有在行距与平面高度确实容得下时才生效，且垂直原点必须落在偶数行。 */
    @Test
    fun cropOriginAppliesOnlyWhenTheReportedGeometryFitsIt() {
        val fits = FableSolExportP010Layout.of(
            widthPx = 320, heightPx = 240,
            reportedStride = 704, reportedSliceHeight = 256,
            cropLeft = 16, cropTop = 9
        )
        assertEquals(16, fits.originXPx)
        // 9 向下取到 8：色度按 2×2 分组，奇数原点会让整帧色度相位偏半个亮度样本。
        assertEquals(8, fits.originYPx)
        assertEquals(8 * 704 + 32, fits.lumaOffset)
        assertEquals(704 * 256 + 4 * 704 + 32, fits.chromaOffset)

        val doesNotFit = FableSolExportP010Layout.of(
            widthPx = 320, heightPx = 240,
            reportedStride = 640, reportedSliceHeight = 240,
            cropLeft = 16, cropTop = 8
        )
        assertEquals(0, doesNotFit.originXPx)
        assertEquals(0, doesNotFit.originYPx)
    }

    /** `Image` 的平面行距比 KEY_STRIDE 权威，但只在容得下一行有效样本时才采纳。 */
    @Test
    fun planeRowStridesRefineTheLayoutOnlyWhenTheyAreUsable() {
        val base = FableSolExportP010Layout.of(
            widthPx = 320, heightPx = 240, reportedStride = 640, reportedSliceHeight = 240
        )
        val refined = base.withPlaneRowStrides(luma = 768, chroma = 800)
        assertEquals(768, refined.lumaRowStride)
        assertEquals(800, refined.chromaRowStride)
        assertEquals(768 * 240, refined.chromaOffset)

        // 报得比一行有效样本还窄：这不是"更权威"，是坏数据。
        assertEquals(base, base.withPlaneRowStrides(luma = 320, chroma = null))
        assertEquals(base, base.withPlaneRowStrides(luma = null, chroma = null))
    }

    // ---- 两种呈现中间面 ----

    /**
     * `RGB10_A2` 兼容中间面比 `RGBA16F` 多一次 10-bit 量化（D153）。
     *
     * 这一条钉的是"多出来的误差有多大"：单个亮度样本上不超过半个输出码值，因此该后备可以
     * 无提示启用；但它确实不为零，所以首选仍是 FP16——色度那边要把 16 个抽头加权平均，
     * 系统误差会在平滑渐变上累成可见的台阶。
     */
    @Test
    fun compatibleIntermediateCostsLessThanHalfAnOutputCode() {
        val definition = FableSolExportP010Math.ColorDefinition.BT2020_PQ
        var worst = 0.0
        var differed = false
        for (step in 0..2000) {
            val value = step / 2000.0
            val exact = math.lumaToCode(
                math.toYCbCr(definition, value, value * 0.5, value * 0.25)[0]
            )
            val quantised = quantiseTo10Bit(value)
            val compatible = math.lumaToCode(
                math.toYCbCr(
                    definition,
                    quantised,
                    quantiseTo10Bit(value * 0.5),
                    quantiseTo10Bit(value * 0.25)
                )[0]
            )
            val error = abs(exact - compatible)
            if (error > 1e-9) differed = true
            worst = maxOf(worst, error)
        }
        assertTrue("兼容中间面的额外误差 $worst 超过半个码值", worst < 0.5)
        assertTrue("两条中间面不该给出完全相同的结果", differed)
    }

    // ---- 闭环亮度修正的整条序列 ----

    /**
     * 高饱和硬边上跑完整条 4:2:0 序列：降采样 → 量化 → 参考上采样 → 逐像素重建。
     *
     * 这正是播放端看到的东西。闭环修正必须让重建后的线性亮度误差**整体下降**，而不只是在
     * 挑出来的单个像素上成立。
     */
    @Test
    fun closedLoopReducesReconstructedLuminanceErrorAcrossASaturatedEdge() {
        val definition = FableSolExportP010Math.ColorDefinition.BT2020_PQ
        val siting = FableSolExportP010Math.ChromaSiting.TYPE_2
        val width = 16
        val height = 16
        val image = Array(height) { y ->
            Array(width) { x ->
                // 左半纯红、右半纯蓝，中间夹一列白：色度相邻样本差到最大，chroma leakage
                // 在这种边上最明显。
                when {
                    x < width / 2 - 1 -> doubleArrayOf(0.92, 0.06, 0.05)
                    x == width / 2 - 1 -> doubleArrayOf(0.88, 0.88, 0.88)
                    else -> doubleArrayOf(0.04, 0.07, 0.95)
                }
            }
        }

        val chroma = downsample(image, definition, siting, width, height)
        var plainError = 0.0
        var correctedError = 0.0
        var worstShift = 0.0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rgb = image[y][x]
                val target = math.linearLuminance(definition, rgb[0], rgb[1], rgb[2])
                val original = math.toYCbCr(definition, rgb[0], rgb[1], rgb[2])[0]
                val reconstructed = upsample(chroma, siting, x, y, width, height)
                val corrected = math.correctLuma(
                    definition, target, original, reconstructed[0], reconstructed[1]
                )
                plainError += abs(
                    luminanceOf(definition, original, reconstructed) - target
                )
                correctedError += abs(
                    luminanceOf(definition, corrected, reconstructed) - target
                )
                worstShift = maxOf(worstShift, abs(corrected - original))
                assertTrue(
                    "修正后的 Y′ 越出合法信号范围",
                    math.lumaToCode(corrected) >= 0.0 &&
                        math.lumaToCode(corrected) <= FableSolExportP010Math.MAX_CODE
                )
            }
        }
        assertTrue(
            "闭环没有降低重建亮度误差：$correctedError >= $plainError",
            correctedError < plainError
        )
        assertTrue(
            "改变量超过 ${FableSolExportP010Math.MAX_LUMA_CORRECTION_CODES} 码值",
            worstShift <= FableSolExportP010Math.MAX_LUMA_CORRECTION_CODES /
                FableSolExportP010Math.LUMA_RANGE + 1e-12
        )
        assertNotEquals("这组素材应当真的触发修正", 0.0, worstShift, 1e-12)
    }

    /** 平坦区域没有色度泄漏可修，闭环必须一动不动——它不是锐化或对比度工具。 */
    @Test
    fun closedLoopLeavesFlatAreasUntouched() {
        val definition = FableSolExportP010Math.ColorDefinition.BT709_SDR
        val rgb = doubleArrayOf(0.42, 0.42, 0.42)
        val yCbCr = math.toYCbCr(definition, rgb[0], rgb[1], rgb[2])
        val target = math.linearLuminance(definition, rgb[0], rgb[1], rgb[2])
        val corrected = math.correctLuma(definition, target, yCbCr[0], yCbCr[1], yCbCr[2])
        assertEquals(yCbCr[0], corrected, 1e-9)
    }

    /** 输出定义由本次传递函数决定，不由"这是不是 HDR10+"决定（D158 第 3 条）。 */
    @Test
    fun colorDefinitionFollowsTheActualTransfer() {
        assertEquals(
            FableSolExportP010Math.ColorDefinition.BT709_SDR,
            FableSolExportP010Math.ColorDefinition.forTransfer(FableSolExportTransfer.SDR)
        )
        assertEquals(
            FableSolExportP010Math.ColorDefinition.BT2020_PQ,
            FableSolExportP010Math.ColorDefinition.forTransfer(FableSolExportTransfer.PQ)
        )
        assertEquals(
            FableSolExportP010Math.ColorDefinition.BT2020_HLG,
            FableSolExportP010Math.ColorDefinition.forTransfer(FableSolExportTransfer.HLG)
        )
    }

    /** 名义信号范围的四个边界，量化与钳制都读这一份。 */
    @Test
    fun nominalSignalRangeMatchesTheLimitedRangeEndpoints() {
        val range = FableSolExportP010Math.SignalRange.NOMINAL
        assertEquals(64.0, range.lumaMinCode, 0.0)
        assertEquals(940.0, range.lumaMaxCode, 0.0)
        // Cb 与 Cr 分开保存（D140 要求分别求安全区间），名义范围下两者相同。
        assertEquals(64.0, range.cbMinCode, 0.0)
        assertEquals(960.0, range.cbMaxCode, 0.0)
        assertEquals(64.0, range.crMinCode, 0.0)
        assertEquals(960.0, range.crMaxCode, 0.0)
        assertEquals(range.lumaMinCode, math.lumaToCode(0.0), 1e-9)
        assertEquals(range.lumaMaxCode, math.lumaToCode(1.0), 1e-9)
        assertEquals(range.cbMaxCode, math.chromaToCode(0.5), 1e-9)
    }

    // ---- 参考实现：与两个着色器逐行对应 ----

    private fun quantiseTo10Bit(value: Double): Double =
        (value.coerceIn(0.0, 1.0) * 1023.0).roundToInt() / 1023.0

    private fun luminanceOf(
        definition: FableSolExportP010Math.ColorDefinition,
        luma: Double,
        chroma: DoubleArray
    ): Double {
        val rgb = math.toRgb(definition, luma, chroma[0], chroma[1])
        return math.linearLuminance(definition, rgb[0], rgb[1], rgb[2])
    }

    /** 有相位的可分离低通 + 2:1 抽取，再按名义范围量化——与 `p010_chroma.frag` 同一步。 */
    private fun downsample(
        image: Array<Array<DoubleArray>>,
        definition: FableSolExportP010Math.ColorDefinition,
        siting: FableSolExportP010Math.ChromaSiting,
        width: Int,
        height: Int
    ): Array<Array<DoubleArray>> {
        val horizontal = math.downsampleTaps(siting.horizontalPhase)
        val vertical = math.downsampleTaps(siting.verticalPhase)
        return Array(height / 2) { cy ->
            Array(width / 2) { cx ->
                val rgb = DoubleArray(3)
                for ((dy, wy) in vertical) {
                    val sy = math.clampIndex(cy * 2 + dy, height)
                    for ((dx, wx) in horizontal) {
                        val sx = math.clampIndex(cx * 2 + dx, width)
                        val weight = wx * wy
                        for (channel in 0..2) rgb[channel] += image[sy][sx][channel] * weight
                    }
                }
                val yCbCr = math.toYCbCr(definition, rgb[0], rgb[1], rgb[2])
                // 量化后再反量化：闭环必须基于**真正写出去的**码值，不是理想色度。
                doubleArrayOf(
                    math.codeToChroma(quantiseCb(yCbCr[1])),
                    math.codeToChroma(quantiseCr(yCbCr[2]))
                )
            }
        }
    }

    private fun quantiseCb(value: Double): Double {
        val range = FableSolExportP010Math.SignalRange.NOMINAL
        return math.quantize(
            math.chromaToCode(value), 0.5, range.cbMinCode, range.cbMaxCode
        ).toDouble()
    }

    private fun quantiseCr(value: Double): Double {
        val range = FableSolExportP010Math.SignalRange.NOMINAL
        return math.quantize(
            math.chromaToCode(value), 0.5, range.crMinCode, range.crMaxCode
        ).toDouble()
    }

    /** 与降采样同一相位的参考上采样——与 `p010_luma.frag` 同一步。 */
    private fun upsample(
        chroma: Array<Array<DoubleArray>>,
        siting: FableSolExportP010Math.ChromaSiting,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): DoubleArray {
        val horizontal = math.upsampleTaps(x, siting.horizontalPhase)
        val vertical = math.upsampleTaps(y, siting.verticalPhase)
        val result = DoubleArray(2)
        for ((dy, wy) in vertical) {
            val cy = math.clampIndex(y / 2 + dy, height / 2)
            for ((dx, wx) in horizontal) {
                val cx = math.clampIndex(x / 2 + dx, width / 2)
                val weight = wx * wy
                result[0] += chroma[cy][cx][0] * weight
                result[1] += chroma[cy][cx][1] * weight
            }
        }
        return result
    }
}
