package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 应用自有 P010 通路的**颜色数学**，与 `p010_luma.frag` / `p010_chroma.frag` 一一对应。
 *
 * 为什么要有这份 CPU 侧实现：着色器在 JVM 上跑不了，而这里的每一步——矩阵、limited range
 * 量化、色度相位、闭环亮度修正、蓝噪声阈值舍入——都是**可以算错且肉眼很难发现**的东西。
 * 把它写成纯 Kotlin，就能用参考向量和迭代 oracle 钉住；着色器只需与本文件逐行对照。
 *
 * 三种输出定义（D158 第 3 条）各自完整：色度矩阵、传递函数与闭环参考显示都不同，不能让
 * 10-bit SDR 借用 HDR10+ 那一套。
 */
internal object FableSolExportP010Math {

    /** limited-range 10-bit 的亮度端点：黑 64、白 940。 */
    const val LUMA_MIN_CODE = 64.0
    const val LUMA_RANGE = 876.0

    /** limited-range 10-bit 的色度：中点 512，上下各 448（即 64…960）。 */
    const val CHROMA_MID_CODE = 512.0
    const val CHROMA_RANGE = 896.0

    const val MAX_CODE = 1023.0

    /**
     * 名义信号范围的合法码值边界（D157 第 6 条）。
     *
     * 抖动之后仍要钳制到这里：蓝噪声阈值本身不会把落在整数码值上的样本推离该码值，但闭环
     * 修正与滤波的残差可能在边界外半个码值处产生越界值。HLG／杜比视界 8.4 的 super-white
     * 自动增强会在 D139～D140 验证后放宽上界，因此这四个数是**传入着色器的参数**而不是
     * 写死在里面的常量。
     */
    const val NOMINAL_LUMA_MAX_CODE = 940.0
    const val NOMINAL_CHROMA_MIN_CODE = 64.0
    const val NOMINAL_CHROMA_MAX_CODE = 960.0

    /**
     * 闭环亮度修正的最大改变量（10-bit 码值）。
     *
     * 色度泄漏对 Y′ 的实际影响通常在百分之几以内；24 码值约为 limited-range 亮度跨度的
     * 2.7%，足够覆盖高饱和细边缘，又不至于让数值不稳定的解把画面推出原本的明暗关系。
     * 该边界是**版本化常量**，按固定回归素材调整，不开放为用户设置（D155）。
     */
    const val MAX_LUMA_CORRECTION_CODES = 24.0

    /**
     * 输出定义：色度矩阵与传递函数成套出现。
     *
     * `kr`/`kb` 既是 NCL Y′ 的系数，也是同一组 primaries 的线性亮度权重——非恒定亮度系统
     * 里这两件事按定义就是同一组数，闭环修正因此可以直接复用。
     */
    enum class ColorDefinition(
        val kr: Double,
        val kb: Double,
        val transfer: Transfer
    ) {
        /** 10-bit SDR：BT.709 primaries/transfer/NCL 矩阵，闭环参考显示为 BT.1886（D159）。 */
        BT709_SDR(0.2126, 0.0722, Transfer.BT1886),

        /** HDR10 / HDR10+：BT.2020 primaries、PQ、BT.2020 NCL（D155）。 */
        BT2020_PQ(0.2627, 0.0593, Transfer.PQ),

        /** HLG / 杜比视界 8.4 基层：BT.2020 primaries、HLG、BT.2020 NCL（D156）。 */
        BT2020_HLG(0.2627, 0.0593, Transfer.HLG);

        val kg: Double get() = 1.0 - kr - kb

        /** `Cb = (B' - Y') / cbScale`。BT.2020 为 1.8814，BT.709 为 1.8556。 */
        val cbScale: Double get() = 2.0 * (1.0 - kb)

        /** `Cr = (R' - Y') / crScale`。BT.2020 为 1.4746，BT.709 为 1.5748。 */
        val crScale: Double get() = 2.0 * (1.0 - kr)

        companion object {

            /**
             * 输出定义由本次实际传递函数决定（D158 第 3 条），不由"这是不是 HDR10+"决定。
             *
             * 10-bit SDR 借用 HDR10+ 那一套矩阵会得到系统性错误的色度：BT.709 与 BT.2020 的
             * 亮度系数不同，同一组 R'G'B' 解出来的 Cb/Cr 差一大截。
             */
            fun forTransfer(transfer: FableSolExportTransfer): ColorDefinition =
                when (transfer) {
                    FableSolExportTransfer.SDR -> BT709_SDR
                    FableSolExportTransfer.PQ -> BT2020_PQ
                    FableSolExportTransfer.HLG -> BT2020_HLG
                }
        }
    }

    /**
     * 闭环修正用的传递函数。
     *
     * 三者的**目标域**不同，这一点不能混：PQ 比较显示线性亮度，HLG 比较场景线性亮度
     * （播放端的 OOTF 随显示条件变化，不能钉在某个峰值上），SDR 比较经 BT.1886 参考显示
     * 得到的显示线性亮度。
     */
    enum class Transfer(
        /** `p010_luma.frag` 的 `uTransfer` 分支号；改这里必须同时改着色器。 */
        val shaderCode: Int
    ) {
        /** ST 2084 EOTF，归一化到 [0,1]（1.0 = 10000 尼特）。 */
        PQ(1),

        /** BT.2100 HLG inverse OETF，得到相对场景线性。 */
        HLG(2),

        /** BT.1886 参考 EOTF，黑位 0、白位只作归一化尺度、幂指数 2.4。 */
        BT1886(0);

        /**
         * 非线性信号 → 线性光。
         *
         * **域名义限制（2026-07-30 裁定）**：输入夹到名义 [0,1]，闭环只在名义域内评估与
         * 修正。super-white 扩展域（≤109%）里求值饱和、改善判据两边相等，`correctLuma`
         * 自然退化为保留原始 Y′——方向安全，该区域的亚码值亮度误差不做修正。与呈现侧
         * `hlgOetfChannel` 有意工作在扩展域的口径不同：闭环修的是名义域内色度量化的亮度
         * 误差，呈现侧管的是扩展信号的生成，二者各自正确。shader（p010_luma.frag）与
         * 本函数逐位对应，不得单侧放开。
         */
        fun toLinear(value: Double): Double {
            val v = value.coerceIn(0.0, 1.0)
            return when (this) {
                PQ -> pqToLinear(v)
                HLG -> hlgToLinear(v)
                BT1886 -> v.pow(BT1886_GAMMA)
            }
        }

        /** `toLinear` 的导数；闭环修正的单步局部线性化要用它。 */
        fun linearSlope(value: Double): Double {
            val v = value.coerceIn(0.0, 1.0)
            return when (this) {
                PQ -> pqSlope(v)
                HLG -> hlgSlope(v)
                BT1886 -> BT1886_GAMMA * v.pow(BT1886_GAMMA - 1.0)
            }
        }
    }

    /** H.273 的 4:2:0 色度位置。相位以左上亮度样本为原点，单位是亮度样本间距。 */
    enum class ChromaSiting(
        override val stableId: String,
        /** 水平相位：0 = 与偶数列共点，0.5 = 位于两列之间。 */
        val horizontalPhase: Double,
        /** 垂直相位：0 = 与偶数行共点，0.5 = 居中，1.0 = 与奇数行共点。 */
        val verticalPhase: Double
    ) : FableSolExportStableChoice {

        /** Type 0：水平共点、垂直居中。HEVC 生态在 VUI 缺失时的默认解读。 */
        TYPE_0("type-0", 0.0, 0.5),

        /** Type 1：水平垂直都居中。现有 2×2 box average 等价于这一档。 */
        TYPE_1("type-1", 0.5, 0.5),

        /** Type 2：水平垂直都共点。BT.2020/BT.2100 的规定位置，也是首选。 */
        TYPE_2("type-2", 0.0, 0.0),

        /** Type 3：水平居中、垂直共点。 */
        TYPE_3("type-3", 0.5, 0.0),

        /** Type 4：水平共点、垂直落在奇数行。 */
        TYPE_4("type-4", 0.0, 1.0),

        /** Type 5：水平居中、垂直落在奇数行。 */
        TYPE_5("type-5", 0.5, 1.0);

        companion object {
            val PREFERRED = TYPE_2

            /** 码流未显式声明时的兼容语义（D154 第 3 条、D170）。 */
            val COMPATIBLE_DEFAULT = TYPE_0

            fun fromTypeCode(value: Int): ChromaSiting? = entries.getOrNull(value)

            fun fromStableId(value: String?): ChromaSiting =
                entries.firstOrNull { it.stableId == value } ?: PREFERRED
        }
    }

    /**
     * 本次实际使用的信号范围码值边界。
     *
     * 名义范围是 [NOMINAL]；HLG／杜比视界 8.4 的 super-white 自动增强在 D139～D140 的回环
     * 验证通过后放宽上界。量化与钳制都读这一份，着色器也只按传入的四个数工作——把边界写死
     * 在着色器里，super-white 就只能靠再写一套着色器来表达。
     */
    data class SignalRange(
        val lumaMinCode: Double,
        val lumaMaxCode: Double,
        /**
         * Cb 与 Cr **分开**给。
         *
         * D140 要求分别求出 Y′、Cb、Cr 的连续安全区间，而两个色度分量在实际编解码路径上未必
         * 一样宽；合并成一条会让较窄的那个分量在边缘像素上越界，或者把较宽的那个白白截掉。
         */
        val cbMinCode: Double,
        val cbMaxCode: Double,
        val crMinCode: Double,
        val crMaxCode: Double
    ) {
        companion object {
            val NOMINAL = SignalRange(
                lumaMinCode = LUMA_MIN_CODE,
                lumaMaxCode = NOMINAL_LUMA_MAX_CODE,
                cbMinCode = NOMINAL_CHROMA_MIN_CODE,
                cbMaxCode = NOMINAL_CHROMA_MAX_CODE,
                crMinCode = NOMINAL_CHROMA_MIN_CODE,
                crMaxCode = NOMINAL_CHROMA_MAX_CODE
            )
        }
    }

    /**
     * 一条轴上的降采样抽头：相对于 `2 * 输出下标` 的偏移与权重。
     *
     * 基础滤波器取 ITU-T H.Sup15 的短抽头 `f0 = [1/8, 6/8, 1/8]`。共点相位直接用它；居中
     * 相位不能把三抽头硬套到半整数位置上，改用同族的对称四抽头 `[1,3,3,1]/8`——它同样归一、
     * 同样以目标位置为对称中心，不引入锐化或振铃。
     */
    fun downsampleTaps(phase: Double): List<Pair<Int, Double>> = when {
        phase == 0.5 -> listOf(-1 to 0.125, 0 to 0.375, 1 to 0.375, 2 to 0.125)
        else -> {
            val center = phase.toInt() // 0 或 1：与偶数行/奇数行共点
            listOf(center - 1 to 0.125, center to 0.75, center + 1 to 0.125)
        }
    }

    /**
     * 参考上采样抽头：给定全分辨率下标 `n`，返回参与重建的色度下标偏移与权重。
     *
     * 偏移相对 `n / 2`（向下取整）。闭环修正必须用**与降采样同一相位**的上采样器，否则
     * "修正后的重建"与播放端看到的不是同一件事。
     */
    fun upsampleTaps(index: Int, phase: Double): List<Pair<Int, Double>> {
        val even = index % 2 == 0
        return when (phase) {
            0.0 -> if (even) listOf(0 to 1.0) else listOf(0 to 0.5, 1 to 0.5)
            1.0 -> if (even) listOf(-1 to 0.5, 0 to 0.5) else listOf(0 to 1.0)
            else -> if (even) listOf(-1 to 0.25, 0 to 0.75) else listOf(0 to 0.75, 1 to 0.25)
        }
    }

    // ---- 矩阵 ----

    /** 非线性 R′G′B′ → 非线性 Y′CbCr（NCL）。三个分量都在 [0,1] / [-0.5,0.5]。 */
    fun toYCbCr(
        definition: ColorDefinition,
        r: Double,
        g: Double,
        b: Double
    ): DoubleArray {
        val y = definition.kr * r + definition.kg * g + definition.kb * b
        return doubleArrayOf(y, (b - y) / definition.cbScale, (r - y) / definition.crScale)
    }

    /** 非线性 Y′CbCr → 非线性 R′G′B′；[toYCbCr] 的精确逆。 */
    fun toRgb(
        definition: ColorDefinition,
        y: Double,
        cb: Double,
        cr: Double
    ): DoubleArray {
        val r = y + cr * definition.crScale
        val b = y + cb * definition.cbScale
        val g = (y - definition.kr * r - definition.kb * b) / definition.kg
        return doubleArrayOf(r, g, b)
    }

    /** 线性光亮度：与 NCL 系数同源，因此直接复用 kr/kg/kb。 */
    fun linearLuminance(definition: ColorDefinition, r: Double, g: Double, b: Double): Double {
        val transfer = definition.transfer
        return definition.kr * transfer.toLinear(r) +
            definition.kg * transfer.toLinear(g) +
            definition.kb * transfer.toLinear(b)
    }

    // ---- limited range 量化 ----

    fun lumaToCode(value: Double): Double = LUMA_MIN_CODE + value * LUMA_RANGE

    fun chromaToCode(value: Double): Double = CHROMA_MID_CODE + value * CHROMA_RANGE

    fun codeToLuma(code: Double): Double = (code - LUMA_MIN_CODE) / LUMA_RANGE

    fun codeToChroma(code: Double): Double = (code - CHROMA_MID_CODE) / CHROMA_RANGE

    /**
     * 蓝噪声阈值舍入（D157）。
     *
     * `floor(value + threshold)`，`threshold ∈ (0,1)`：期望值等于 `value`，量化误差始终不足
     * 一个目标码值。恰好落在整数码值上的样本（真黑、真白、中性色度、安全边界）不会被推离
     * 该码值——`floor(n + t) == n` 对任何 `t < 1` 成立。
     */
    fun quantize(value: Double, threshold: Double, lower: Double, upper: Double): Int {
        val rounded = floor(value + threshold)
        return rounded.coerceIn(lower, upper).toInt()
    }

    /** P010：10 位有效值放在 16 位字的高位。 */
    fun toP010Word(code: Int): Int = (code.coerceIn(0, 1023) shl 6) and 0xFFFF

    // ---- 闭环亮度修正 ----

    /**
     * 单步闭式（局部线性化）亮度修正（D155/D156/D159）。
     *
     * 输入是原始全分辨率画面的目标线性亮度，以及**本帧实际写出的**量化色度经参考上采样得到
     * 的 Cb/Cr。求一个 Y′，使它与这组色度重建后的线性亮度尽量接近目标。
     *
     * 只在四个条件同时成立时采用修正值，否则原样保留合法的原始 Y′：
     *
     * 1. 斜率有限且非零（解稳定）；
     * 2. 改变量不超过 [MAX_LUMA_CORRECTION_CODES]；
     * 3. 修正后的线性亮度误差确实小于原始 Y′ 的误差；
     * 4. 不引入新的 R′G′B′ 非法值——原来就在合法范围内的像素，修正后仍须在范围内。
     *
     * @return 修正后的 Y′（信号域 [0,1] 之外由调用方按码值范围钳制）。
     */
    fun correctLuma(
        definition: ColorDefinition,
        targetLuminance: Double,
        originalLuma: Double,
        reconstructedCb: Double,
        reconstructedCr: Double
    ): Double {
        val transfer = definition.transfer
        val originalRgb = toRgb(definition, originalLuma, reconstructedCb, reconstructedCr)
        val originalLegal = isLegal(originalRgb)
        val originalLuminance = linearLuminance(
            definition, originalRgb[0], originalRgb[1], originalRgb[2]
        )
        val slope = definition.kr * transfer.linearSlope(originalRgb[0]) +
            definition.kg * transfer.linearSlope(originalRgb[1]) +
            definition.kb * transfer.linearSlope(originalRgb[2])
        if (!slope.isFinite() || slope <= SLOPE_EPSILON) return originalLuma
        val step = (targetLuminance - originalLuminance) / slope
        if (!step.isFinite()) return originalLuma
        val maxStep = MAX_LUMA_CORRECTION_CODES / LUMA_RANGE
        val bounded = step.coerceIn(-maxStep, maxStep)
        val candidateLuma = originalLuma + bounded
        val candidateRgb = toRgb(definition, candidateLuma, reconstructedCb, reconstructedCr)
        if (originalLegal && !isLegal(candidateRgb)) return originalLuma
        val candidateLuminance = linearLuminance(
            definition, candidateRgb[0], candidateRgb[1], candidateRgb[2]
        )
        val improved = kotlin.math.abs(candidateLuminance - targetLuminance) <
            kotlin.math.abs(originalLuminance - targetLuminance)
        return if (improved) candidateLuma else originalLuma
    }

    /**
     * 高精度迭代解，**只作测试 oracle**（D155 明确要求不进正式逐帧路径）。
     *
     * 在合法 Y′ 区间上二分：线性亮度对 Y′ 单调不减，二分因此稳定。
     */
    fun correctLumaByBisection(
        definition: ColorDefinition,
        targetLuminance: Double,
        reconstructedCb: Double,
        reconstructedCr: Double,
        iterations: Int = 40
    ): Double {
        var low = 0.0
        var high = 1.0
        repeat(iterations) {
            val mid = 0.5 * (low + high)
            val rgb = toRgb(definition, mid, reconstructedCb, reconstructedCr)
            val luminance = linearLuminance(definition, rgb[0], rgb[1], rgb[2])
            if (luminance < targetLuminance) low = mid else high = mid
        }
        return 0.5 * (low + high)
    }

    private fun isLegal(rgb: DoubleArray): Boolean =
        rgb.all { it >= -LEGAL_EPSILON && it <= 1.0 + LEGAL_EPSILON }

    // ---- 传递函数实现 ----

    private const val PQ_M1 = 2610.0 / 16384.0
    private const val PQ_M2 = 2523.0 / 4096.0 * 128.0
    private const val PQ_C1 = 3424.0 / 4096.0
    private const val PQ_C2 = 2413.0 / 4096.0 * 32.0
    private const val PQ_C3 = 2392.0 / 4096.0 * 32.0

    private const val HLG_A = 0.17883277
    private val HLG_B = 1.0 - 4.0 * HLG_A
    private val HLG_C = 0.5 - HLG_A * ln(4.0 * HLG_A)

    private const val BT1886_GAMMA = 2.4

    private const val SLOPE_EPSILON = 1e-9
    private const val LEGAL_EPSILON = 1e-6
    private const val TRANSFER_EPSILON = 1e-9

    private fun pqToLinear(value: Double): Double {
        if (value <= 0.0) return 0.0
        val p = value.pow(1.0 / PQ_M2)
        val numerator = max(p - PQ_C1, 0.0)
        val denominator = PQ_C2 - PQ_C3 * p
        if (denominator <= TRANSFER_EPSILON) return 1.0
        return (numerator / denominator).pow(1.0 / PQ_M1)
    }

    private fun pqSlope(value: Double): Double {
        if (value <= TRANSFER_EPSILON) return 0.0
        val p = value.pow(1.0 / PQ_M2)
        val numerator = p - PQ_C1
        if (numerator <= 0.0) return 0.0
        val denominator = PQ_C2 - PQ_C3 * p
        if (denominator <= TRANSFER_EPSILON) return 0.0
        val ratio = numerator / denominator
        // d(ratio)/dp = (den + c3 * num) / den^2；dp/dV = (1/m2) V^(1/m2 - 1)。
        val dRatio = (denominator + PQ_C3 * numerator) / (denominator * denominator)
        val dP = value.pow(1.0 / PQ_M2 - 1.0) / PQ_M2
        return ratio.pow(1.0 / PQ_M1 - 1.0) / PQ_M1 * dRatio * dP
    }

    private fun hlgToLinear(value: Double): Double = if (value <= 0.5) {
        value * value / 3.0
    } else {
        (exp((value - HLG_C) / HLG_A) + HLG_B) / 12.0
    }

    private fun hlgSlope(value: Double): Double = if (value <= 0.5) {
        2.0 * value / 3.0
    } else {
        exp((value - HLG_C) / HLG_A) / (12.0 * HLG_A)
    }

    /** 供着色器与测试共用的边界延拓：保持相位，不把首末样本挪到另一个采样位置。 */
    fun clampIndex(index: Int, size: Int): Int = min(max(index, 0), size - 1)
}
