package com.ywwynm.everythingdone.views.recording.fablesol

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用自有 P010 颜色核心的数值门禁。
 *
 * 着色器在 JVM 上跑不了，而这里的每一步都是**可以算错且肉眼很难发现**的：矩阵系数、
 * limited range 端点、色度相位、闭环亮度修正、蓝噪声无偏性。着色器与本文件逐行对照，
 * 因此这些用例同时也是着色器的门禁。
 */
class FableSolExportP010MathTest {

    private val math = FableSolExportP010Math

    // ---- 矩阵与 limited range ----

    /** 白、黑与三原色的参考码值。系数写错时这几个数会立刻偏。 */
    @Test
    fun referenceVectorsMatchTheStandardMatrices() {
        val bt2020 = FableSolExportP010Math.ColorDefinition.BT2020_PQ
        val white = math.toYCbCr(bt2020, 1.0, 1.0, 1.0)
        assertEquals(1.0, white[0], 1e-12)
        assertEquals(0.0, white[1], 1e-12)
        assertEquals(0.0, white[2], 1e-12)
        assertEquals(940.0, math.lumaToCode(white[0]), 1e-9)
        assertEquals(512.0, math.chromaToCode(white[1]), 1e-9)

        val black = math.toYCbCr(bt2020, 0.0, 0.0, 0.0)
        assertEquals(64.0, math.lumaToCode(black[0]), 1e-9)
        assertEquals(512.0, math.chromaToCode(black[1]), 1e-9)

        // 纯红：Cr 按定义正好是 +0.5，落在色度上边界 960。
        val red = math.toYCbCr(bt2020, 1.0, 0.0, 0.0)
        assertEquals(0.2627, red[0], 1e-12)
        assertEquals(0.5, red[2], 1e-12)
        assertEquals(960.0, math.chromaToCode(red[2]), 1e-9)
        assertEquals(-0.2627 / 1.8814, red[1], 1e-12)

        // 纯蓝：Cb 同理正好 +0.5。BT.709 的系数与 BT.2020 不同，这里一并钉住。
        val bt709 = FableSolExportP010Math.ColorDefinition.BT709_SDR
        val blue = math.toYCbCr(bt709, 0.0, 0.0, 1.0)
        assertEquals(0.0722, blue[0], 1e-12)
        assertEquals(0.5, blue[1], 1e-12)
        assertEquals(1.8556, bt709.cbScale, 1e-12)
        assertEquals(1.5748, bt709.crScale, 1e-12)
        assertEquals(1.8814, bt2020.cbScale, 1e-12)
        assertEquals(1.4746, bt2020.crScale, 1e-12)
    }

    /** `toRgb` 必须是 `toYCbCr` 的精确逆，否则闭环修正的每一步都建在错的基上。 */
    @Test
    fun matrixRoundTripsExactly() {
        for (definition in FableSolExportP010Math.ColorDefinition.entries) {
            for (r in listOf(0.0, 0.13, 0.5, 0.87, 1.0)) {
                for (g in listOf(0.0, 0.29, 1.0)) {
                    for (b in listOf(0.0, 0.61, 1.0)) {
                        val ycc = math.toYCbCr(definition, r, g, b)
                        val rgb = math.toRgb(definition, ycc[0], ycc[1], ycc[2])
                        assertEquals(r, rgb[0], 1e-12)
                        assertEquals(g, rgb[1], 1e-12)
                        assertEquals(b, rgb[2], 1e-12)
                    }
                }
            }
        }
    }

    /** P010：10 位有效值在 16 位字的高位，低 6 位为 0。 */
    @Test
    fun p010WordPacksIntoTheHighBits() {
        assertEquals(0, math.toP010Word(0))
        assertEquals(64 shl 6, math.toP010Word(64))
        assertEquals(940 shl 6, math.toP010Word(940))
        assertEquals(1023 shl 6, math.toP010Word(1023))
        // 超界必须钳制而不是回绕：回绕会把最亮的样本变成最暗的。
        assertEquals(1023 shl 6, math.toP010Word(2000))
        assertEquals(0, math.toP010Word(-5))
        assertTrue(math.toP010Word(1023) <= 0xFFFF)
    }

    // ---- 色度相位 ----

    /**
     * 三种相位的抽头必须归一、对称，且中心落在**声明的位置**上。
     *
     * 共点相位用 H.Sup15 的短抽头 `f0 = [1/8, 6/8, 1/8]`；居中相位不能把三抽头硬套到半整数
     * 位置，改用同族的对称四抽头 `[1,3,3,1]/8`。
     */
    @Test
    fun downsampleTapsAreNormalisedAndCentredOnTheDeclaredPhase() {
        val coSited = math.downsampleTaps(0.0)
        assertEquals(listOf(-1, 0, 1), coSited.map { it.first })
        assertEquals(listOf(0.125, 0.75, 0.125), coSited.map { it.second })
        assertEquals(1.0, coSited.sumOf { it.second }, 1e-12)
        assertEquals(0.0, coSited.sumOf { it.first * it.second }, 1e-12)

        val centred = math.downsampleTaps(0.5)
        assertEquals(listOf(-1, 0, 1, 2), centred.map { it.first })
        assertEquals(1.0, centred.sumOf { it.second }, 1e-12)
        // 一阶矩落在 0.5，即两个亮度样本之间——这正是 Type 0/1 的垂直/水平"居中"。
        assertEquals(0.5, centred.sumOf { it.first * it.second }, 1e-12)

        val bottom = math.downsampleTaps(1.0)
        assertEquals(1.0, bottom.sumOf { it.second }, 1e-12)
        assertEquals(1.0, bottom.sumOf { it.first * it.second }, 1e-12)
    }

    /** 上采样必须与降采样同相位，否则"修正后的重建"与播放端看到的不是同一件事。 */
    @Test
    fun upsampleTapsMirrorTheDownsamplePhase() {
        // 共点：偶数亮度样本直接取该色度样本，奇数样本取相邻两个的平均。
        assertEquals(listOf(0 to 1.0), math.upsampleTaps(4, 0.0))
        assertEquals(listOf(0 to 0.5, 1 to 0.5), math.upsampleTaps(5, 0.0))
        // 居中：两侧权重 0.75 / 0.25，重心正好落回 0.5 相位。
        assertEquals(listOf(-1 to 0.25, 0 to 0.75), math.upsampleTaps(4, 0.5))
        assertEquals(listOf(0 to 0.75, 1 to 0.25), math.upsampleTaps(5, 0.5))
        for (phase in listOf(0.0, 0.5, 1.0)) {
            for (index in 0 until 8) {
                assertEquals(1.0, math.upsampleTaps(index, phase).sumOf { it.second }, 1e-12)
            }
        }
    }

    /** H.273 的六种位置与首选/兼容默认值。BT.2020/BT.2100 规定的是 Type 2。 */
    @Test
    fun chromaSitingPhasesFollowH273() {
        fun phase(value: FableSolExportP010Math.ChromaSiting) =
            value.horizontalPhase to value.verticalPhase

        assertEquals(0.0 to 0.5, phase(FableSolExportP010Math.ChromaSiting.TYPE_0))
        assertEquals(0.5 to 0.5, phase(FableSolExportP010Math.ChromaSiting.TYPE_1))
        assertEquals(0.0 to 0.0, phase(FableSolExportP010Math.ChromaSiting.TYPE_2))
        assertEquals(0.5 to 0.0, phase(FableSolExportP010Math.ChromaSiting.TYPE_3))
        assertEquals(
            FableSolExportP010Math.ChromaSiting.TYPE_2,
            FableSolExportP010Math.ChromaSiting.PREFERRED
        )
        assertEquals(
            FableSolExportP010Math.ChromaSiting.TYPE_0,
            FableSolExportP010Math.ChromaSiting.COMPATIBLE_DEFAULT
        )
        // 旧实现的 2×2 box average 等价于 Type 1；它不再是任何一条路径的首选。
        assertNotEquals(
            FableSolExportP010Math.ChromaSiting.TYPE_1,
            FableSolExportP010Math.ChromaSiting.PREFERRED
        )
    }

    /** 边界延拓按夹取索引完成：不能把首末色度样本挪到另一个采样位置上。 */
    @Test
    fun boundaryExtensionPreservesPhase() {
        assertEquals(0, math.clampIndex(-3, 16))
        assertEquals(15, math.clampIndex(99, 16))
        assertEquals(7, math.clampIndex(7, 16))
    }

    // ---- 传递函数与闭环 ----

    /** 三条传递函数的端点、单调性，以及解析导数与数值导数一致。 */
    @Test
    fun transferFunctionsAndTheirSlopesAgreeWithNumericalDifferentiation() {
        for (transfer in FableSolExportP010Math.Transfer.entries) {
            assertEquals(0.0, transfer.toLinear(0.0), 1e-12)
            assertTrue(transfer.toLinear(1.0) > 0.0)
            var previous = -1.0
            var value = 0.02
            while (value < 0.99) {
                val current = transfer.toLinear(value)
                assertTrue("$transfer 必须单调不减", current >= previous)
                previous = current
                val h = 1e-6
                val numerical = (transfer.toLinear(value + h) - transfer.toLinear(value - h)) /
                    (2.0 * h)
                val analytic = transfer.linearSlope(value)
                val tolerance = 1e-4 * kotlin.math.max(1.0, abs(numerical))
                assertEquals("$transfer 在 $value 处的导数", numerical, analytic, tolerance)
                value += 0.017
            }
        }
    }

    /**
     * **闭环亮度修正必须真的把误差降下来，且与迭代 oracle 一致。**
     *
     * 构造一条高饱和边缘：原始像素的色度被相邻像素"拉走"（这正是 4:2:0 降采样后重建出来的
     * 形态）。此时原样保留 Y′ 会让重建亮度偏离原始亮度，即 chroma leakage。
     */
    @Test
    fun closedFormLumaCorrectionApproachesTheIterativeOracle() {
        val samples = listOf(
            Triple(0.90, 0.20, 0.15),
            Triple(0.15, 0.75, 0.30),
            Triple(0.35, 0.35, 0.95),
            Triple(0.62, 0.48, 0.11)
        )
        for (definition in FableSolExportP010Math.ColorDefinition.entries) {
            for ((r, g, b) in samples) {
                val original = math.toYCbCr(definition, r, g, b)
                val target = math.linearLuminance(definition, r, g, b)
                // 邻居的色度：模拟降采样 + 重建之后落到本像素上的那一组。
                val neighbour = math.toYCbCr(definition, r * 0.75 + 0.1, g, b * 0.8 + 0.05)
                val cb = 0.5 * (original[1] + neighbour[1])
                val cr = 0.5 * (original[2] + neighbour[2])

                val corrected = math.correctLuma(definition, target, original[0], cb, cr)
                val oracle = math.correctLumaByBisection(definition, target, cb, cr)

                fun luminanceAt(luma: Double): Double {
                    val rgb = math.toRgb(definition, luma, cb, cr)
                    return math.linearLuminance(definition, rgb[0], rgb[1], rgb[2])
                }

                val beforeError = abs(luminanceAt(original[0]) - target)
                val afterError = abs(luminanceAt(corrected) - target)
                assertTrue(
                    "$definition ($r,$g,$b) 修正后的亮度误差必须不大于修正前",
                    afterError <= beforeError + 1e-15
                )
                // 单步闭式解不必与 oracle 完全相同，但方向与量级要一致。
                val maxStep = FableSolExportP010Math.MAX_LUMA_CORRECTION_CODES /
                    FableSolExportP010Math.LUMA_RANGE
                val oracleStep = (oracle - original[0]).coerceIn(-maxStep, maxStep)
                val closedStep = corrected - original[0]
                assertTrue(
                    "$definition 闭式解与 oracle 的方向必须一致",
                    closedStep * oracleStep >= -1e-12
                )
                assertTrue(
                    "$definition 闭式解与 oracle 的步长差不得超过一个码值量级",
                    abs(closedStep - oracleStep) <= 4.0 / FableSolExportP010Math.LUMA_RANGE
                )
            }
        }
    }

    /** 改变量必须有硬上限：数值不稳定的解不能把画面推出原有的明暗关系。 */
    @Test
    fun lumaCorrectionIsBoundedAndNeverAcceptsAWorseResult() {
        val definition = FableSolExportP010Math.ColorDefinition.BT2020_PQ
        val original = math.toYCbCr(definition, 0.5, 0.5, 0.5)
        // 给一个完全不可达的目标亮度，逼出最大步长。
        val corrected = math.correctLuma(definition, 1.0, original[0], 0.0, 0.0)
        val maxStep = FableSolExportP010Math.MAX_LUMA_CORRECTION_CODES /
            FableSolExportP010Math.LUMA_RANGE
        assertTrue(abs(corrected - original[0]) <= maxStep + 1e-12)
        // 目标就是当前值时不应发生任何改变。
        val target = math.linearLuminance(definition, 0.5, 0.5, 0.5)
        assertEquals(
            original[0],
            math.correctLuma(definition, target, original[0], 0.0, 0.0),
            1e-12
        )
    }

    // ---- 蓝噪声 ----

    /** 资源必须是 0…4095 的一个排列；坏表在画面上只表现为"抖动有点脏"，很难被归因。 */
    @Test
    fun blueNoiseAssetIsAValidPermutation() {
        val ranks = FableSolExportBlueNoise.decode(blueNoiseBytes())
        assertNotNull(ranks)
        assertEquals(FableSolExportBlueNoise.LEVELS, ranks!!.size)
        assertEquals(ranks.toSortedSet().size, ranks.size)
        assertEquals(0, ranks.min())
        assertEquals(FableSolExportBlueNoise.LEVELS - 1, ranks.max())
        // 截断、补零或重复的表一律拒收。
        assertNull(FableSolExportBlueNoise.decode(ByteArray(16)))
        assertNull(FableSolExportBlueNoise.decode(ByteArray(FableSolExportBlueNoise.LEVELS * 2)))
    }

    /**
     * **无偏性**：整块阈值图案上的平均量化结果必须回到原值。
     *
     * 这是抖动的全部意义所在——单个样本被推到上下码值之一，平均值不变，于是大面积缓变里的
     * 台阶被打散成噪声而不是色带。
     */
    @Test
    fun blueNoiseRoundingIsUnbiasedAndKeepsExactCodesExact() {
        val ranks = FableSolExportBlueNoise.decode(blueNoiseBytes())!!
        fun meanOf(value: Double): Double {
            var sum = 0.0
            for (rank in ranks) {
                sum += math.quantize(value, FableSolExportBlueNoise.thresholdOf(rank), 0.0, 1023.0)
            }
            return sum / ranks.size
        }
        // 小数部分落在 1/4096 栅格上时，整块图案的平均**精确**等于原值。
        for (fraction in listOf(0.125, 0.5, 0.75)) {
            val value = 300.0 + fraction
            assertEquals("平均量化结果必须精确回到原值", value, meanOf(value), 1e-12)
        }
        // 不在栅格上时残余偏差不超过一个阈值台阶，即 1/4096 个码值——远小于目标位深本身。
        for (fraction in listOf(0.9, 0.31, 0.6667)) {
            val value = 300.0 + fraction
            assertEquals(
                "残余偏差必须不超过一个阈值台阶",
                value, meanOf(value), 1.0 / FableSolExportBlueNoise.LEVELS
            )
        }
        // 恰好落在整数码值上的样本不得被推离：真黑、真白、中性色度与安全边界都靠这一条。
        for (code in listOf(64.0, 512.0, 940.0, 960.0)) {
            for (rank in listOf(0, 1234, 4095)) {
                assertEquals(
                    code.toInt(),
                    math.quantize(code, FableSolExportBlueNoise.thresholdOf(rank), 64.0, 960.0)
                )
            }
        }
        // 量化误差始终不足一个目标码值，不叠加多码值强度的噪声。
        for (rank in listOf(0, 2047, 4095)) {
            val quantized = math.quantize(
                700.4, FableSolExportBlueNoise.thresholdOf(rank), 64.0, 940.0
            )
            assertTrue(abs(quantized - 700.4) < 1.0)
        }
    }

    /** 三个量化器的相位偏移必须互不相同，否则它们会形成规则相关性。 */
    @Test
    fun blueNoisePhasesAreDistinctAndStatic() {
        val phases = FableSolExportBlueNoise.Phase.entries
        val offsets = phases.map { it.offsetX to it.offsetY }
        assertEquals(offsets.size, offsets.toSet().size)
        // 图案固定在画布坐标里：同一坐标任何时候都给同一个阈值，没有帧参数可传。
        assertTrue(
            FableSolExportBlueNoise::class.java.declaredMethods
                .none { it.name.contains("frame", ignoreCase = true) }
        )
    }

    private fun blueNoiseBytes(): ByteArray {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(directory, "shared/fablesol/bluenoise64.bin")
            if (candidate.isFile) return candidate.readBytes()
            directory = directory.parentFile ?: return@repeat
        }
        error("Cannot find shared/fablesol/bluenoise64.bin")
    }
}
