package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * HLG 输出变换的**颜色数学**，与 `export_present.frag` 的 HLG 分支一一对应
 * （fablesol-video-export D126～D134、D164）。
 *
 * 与旧实现的根本区别是**源语义**（D132）：FableSol 的共用 FP16 内容是扩展**显示线性**图形
 * 渲染，不是摄像机场景光。旧代码直接把显示线性值乘 `E_ref` 再套 OETF，等于让参考 HLG 显示器
 * 再施加一次约 1.2 的 OOTF——`0.5×` 参考白从应有的 101.5 尼特掉到 88.4，`2.0×` 参考白从
 * 406 涨到 466。正确顺序是：
 *
 * ```text
 * 显示线性 Rec.709 → 显示线性 BT.2020 → 按 D_ref 归一为参考显示光 → 逆 OOTF
 * → 场景线性方向肩部 → 共同 RGB 增益 → HLG OETF
 * ```
 *
 * 肩部是**逐颜色方向**的（D129、D133）：中性色在参考显示器上有约 `4.92×` 参考白的显示线性
 * 容量，Rec.709 蓝方向经 super-white 后约 `5.50×`——用最不利颜色的一条全局曲线会让仍放得下
 * 的高光提前压缩。方向相关性可以完全归一化掉：把肩部除以 `q(u)` 之后，起点与端点都是整段
 * 常数，唯一随方向变化的形状量是归一化容量 `C_n(u) = C_S(u) / q(u)`（D164）。
 */
internal object FableSolExportHlgTransform {

    /** 参考 HLG 显示器的 system gamma（BT.2100，1000 尼特参考显示条件）。 */
    const val GAMMA = 1.2

    /** `1 / γ`；逆 OOTF 对正比例缩放的齐次次数。 */
    const val INVERSE_GAMMA = 1.0 / GAMMA

    /**
     * SDR 参考白对应的 HLG **场景线性**值（BT.2408）。
     *
     * 它同时定出 75% 信号：`hlgOetf(0.26497) ≈ 0.75`。FableSol 的显示线性 `1.0` 固定映射
     * 到这里（D126），不提供绝对白点或目标峰值选项。
     */
    const val REFERENCE_WHITE_SCENE = 0.26497

    /** 参考白的**显示线性**归一化值 `D_ref = E_ref^γ ≈ 0.203159`。 */
    val REFERENCE_WHITE_DISPLAY: Double = REFERENCE_WHITE_SCENE.pow(GAMMA)

    /** 肩部起点：FableSol 扩展显示线性源空间的 `2.0 × HDR Reference White`（D130）。 */
    const val KNEE_DISPLAY = 2.0

    /** 归一化肩部起点 `K_n = K_D^(1/γ) ≈ 1.7818`，整段常数。 */
    val KNEE_NORMALIZED: Double = KNEE_DISPLAY.pow(INVERSE_GAMMA)

    /**
     * 窄范围 10-bit 亮度的 super-white 上限对应的非线性 HLG 信号（D134）。
     *
     * 名义峰值码值 940，视频数据范围延伸到 1019：`(1019 - 64) / 876 ≈ 1.09`。按当前
     * Rec.709 源色域，红/绿/蓝方向分别只需 `103.30% / 100.77% / 107.65%`，因此这道上限是
     * 防御性钳制，不会实际触发。
     */
    const val SIGNAL_MAX = 1.09

    /** 名义范围上限：所有方向都止于 100% 信号（D135）。 */
    const val SIGNAL_NOMINAL = 1.0

    /** BT.2020 非恒定亮度权重；同时是逆 OOTF 用的亮度分量。 */
    const val LUMA_R = 0.2627
    const val LUMA_G = 0.6780
    const val LUMA_B = 0.0593

    /** 一维肩部参数表的样本数；键是 `C_n`，表项是 `ξ = (H_n - K_n) / A_n`。 */
    const val SHOULDER_TABLE_SIZE = 144

    /** `ξ` 小于这个数时肩部与恒等映射的差别已在浮点噪声以内。 */
    const val SHOULDER_XI_EPSILON = 1e-4

    /** `ξ` 的求解上界；再大等于把端点压回膝点，属于容量几乎为零的退化情形。 */
    const val SHOULDER_XI_MAX = 64.0

    private const val HLG_A = 0.17883277
    private val HLG_B = 1.0 - 4.0 * HLG_A
    private val HLG_C = 0.5 - HLG_A * ln(4.0 * HLG_A)

    private const val EPSILON = 1e-12

    /**
     * Rec.709 → BT.2020 的线性矩阵；与 `export_present.frag` 的 `bt709ToBt2020` 逐位一致。
     *
     * 三行各自求和为 1（非负），因此中性色方向变换后仍是中性色，`max(v) ≤ max(u)`。
     */
    val BT709_TO_BT2020: Array<DoubleArray> = arrayOf(
        doubleArrayOf(0.62740390, 0.32928304, 0.04331307),
        doubleArrayOf(0.06909729, 0.91954040, 0.01136231),
        doubleArrayOf(0.01639144, 0.08801331, 0.89559525)
    )

    fun bt709ToBt2020(r: Double, g: Double, b: Double): DoubleArray = doubleArrayOf(
        BT709_TO_BT2020[0][0] * r + BT709_TO_BT2020[0][1] * g + BT709_TO_BT2020[0][2] * b,
        BT709_TO_BT2020[1][0] * r + BT709_TO_BT2020[1][1] * g + BT709_TO_BT2020[1][2] * b,
        BT709_TO_BT2020[2][0] * r + BT709_TO_BT2020[2][1] * g + BT709_TO_BT2020[2][2] * b
    )

    fun luminance(v: DoubleArray): Double =
        LUMA_R * v[0] + LUMA_G * v[1] + LUMA_B * v[2]

    /**
     * BT.2100 HLG OETF。**上界不钳到 1.0**：super-white 区间正是靠这条曲线在名义峰值以上
     * 的自然延拓表达的（D134），钳死就永远产不出 100% 以上的信号。
     */
    fun hlgOetf(e: Double): Double {
        val value = max(e, 0.0)
        return if (value <= 1.0 / 12.0) {
            sqrt(3.0 * value)
        } else {
            HLG_A * ln(12.0 * value - HLG_B) + HLG_C
        }
    }

    /** [hlgOetf] 的精确逆。 */
    fun hlgInverseOetf(signal: Double): Double {
        val value = max(signal, 0.0)
        return if (value <= 0.5) {
            value * value / 3.0
        } else {
            (exp((value - HLG_C) / HLG_A) + HLG_B) / 12.0
        }
    }

    /**
     * BT.2100 逆 OOTF：把参考显示光换回场景线性。
     *
     * `E_S = D · Y_D^((1 - γ) / γ)`，黑色保持零。对正比例缩放具有 `1/γ` 次齐次性，方向相关
     * 的所有推导都建立在这条性质上。
     */
    fun inverseOotf(displayLight: DoubleArray): DoubleArray {
        val y = luminance(displayLight)
        if (y <= EPSILON) return doubleArrayOf(0.0, 0.0, 0.0)
        val scale = y.pow((1.0 - GAMMA) / GAMMA)
        return doubleArrayOf(
            displayLight[0] * scale,
            displayLight[1] * scale,
            displayLight[2] * scale
        )
    }

    /**
     * 扩展显示线性 Rec.709 → 场景线性 BT.2020（D128 第 1、2 步）。
     */
    fun sceneLinear(r: Double, g: Double, b: Double): DoubleArray {
        val wide = bt709ToBt2020(max(r, 0.0), max(g, 0.0), max(b, 0.0))
        return inverseOotf(
            doubleArrayOf(
                wide[0] * REFERENCE_WHITE_DISPLAY,
                wide[1] * REFERENCE_WHITE_DISPLAY,
                wide[2] * REFERENCE_WHITE_DISPLAY
            )
        )
    }

    /**
     * 颜色方向的场景线性尺度 `q(u) = max(inverseOotf(D_ref · rec709ToBt2020(u)))`（D129）。
     *
     * 展开后 `q(u) = E_ref · max(v) · Y_v^((1-γ)/γ)`。实际像素在场景线性域的 maxRGB 因此是
     * `m = q(u) · s^(1/γ)`，其中 `s = max(P_D)`。
     *
     * @param u 单位颜色方向（`max(u) = 1`）；传入任意正比例的向量也可以，函数按 `max` 归一。
     */
    fun directionScale(u: DoubleArray): Double {
        val scale = max(max(u[0], u[1]), u[2])
        if (scale <= EPSILON) return 0.0
        val scene = sceneLinear(u[0] / scale, u[1] / scale, u[2] / scale)
        return max(max(scene[0], scene[1]), scene[2])
    }

    /**
     * 该方向的**单位场景线性方向**（最大分量恰为 1）。
     *
     * 回环验证要沿这条方向逐点求 Y′CbCr 码值（D165），因此必须与逐像素路径同源。
     */
    fun sceneDirection(u: DoubleArray): DoubleArray {
        val scene = sceneLinear(u[0], u[1], u[2])
        val peak = max(max(scene[0], scene[1]), scene[2])
        if (peak <= EPSILON) return doubleArrayOf(0.0, 0.0, 0.0)
        return doubleArrayOf(scene[0] / peak, scene[1] / peak, scene[2] / peak)
    }

    /**
     * 该方向恰好匹配参考 HLG 显示器名义峰值所需的场景线性容量 `C_match(u)`（D134）。
     *
     * 闭式为 `(max(v) / Y_v)^((γ-1)/γ)`，与 `s_peak(u) = 1 / (D_ref · max(v))` 的推导等价，
     * 且对 `u` 的正比例缩放不变。
     */
    fun matchCapacity(u: DoubleArray): Double {
        val wide = bt709ToBt2020(max(u[0], 0.0), max(u[1], 0.0), max(u[2], 0.0))
        val peak = max(max(wide[0], wide[1]), wide[2])
        val y = luminance(wide)
        if (peak <= EPSILON || y <= EPSILON) return 1.0
        return (peak / y).pow((GAMMA - 1.0) / GAMMA)
    }

    /** 标准上限 `W_standard(u) = min(hlgOetf(C_match(u)), W_MAX)`（D134）。 */
    fun standardCeiling(u: DoubleArray): Double =
        kotlin.math.min(hlgOetf(matchCapacity(u)), SIGNAL_MAX)

    // ---- 归一化肩部（D131、D164）----

    /**
     * `g(ξ) = (1 - exp(-ξ)) / ξ`，严格递减、从 1 降到 0。
     *
     * 肩部方程 `A_n(1 - exp(-(H_n - K_n)/A_n)) = C_n - K_n` 用 `ξ = (H_n - K_n)/A_n` 改写后
     * 就是 `g(ξ) = (C_n - K_n) / (H_n - K_n)`。这个参数化把 `A_n → ∞`（容量几乎够用）变成
     * `ξ → 0`，表在这一端不再发散，线性插值才有意义。
     */
    fun shoulderShape(xi: Double): Double =
        if (xi < 1e-6) 1.0 - xi * 0.5 else (1.0 - exp(-xi)) / xi

    /**
     * 解出该归一化容量对应的 `ξ`。
     *
     * @return 0 表示不需要肩部（容量已经覆盖整个 `0～H_D`）；[SHOULDER_XI_MAX] 表示容量退化
     *   到膝点，等效于在膝点处硬钳。
     */
    fun shoulderXi(normalizedCapacity: Double, normalizedHeadroom: Double): Double {
        val span = normalizedHeadroom - KNEE_NORMALIZED
        if (span <= EPSILON) return 0.0
        val excess = normalizedCapacity - KNEE_NORMALIZED
        if (excess >= span) return 0.0
        if (excess <= EPSILON) return SHOULDER_XI_MAX
        val target = excess / span
        var low = SHOULDER_XI_EPSILON
        var high = SHOULDER_XI_MAX
        if (shoulderShape(high) >= target) return SHOULDER_XI_MAX
        repeat(BISECTION_STEPS) {
            val mid = 0.5 * (low + high)
            if (shoulderShape(mid) > target) low = mid else high = mid
        }
        return 0.5 * (low + high)
    }

    /**
     * 归一化肩部 `F_n(m_n)`：`m_n ≤ K_n` 恒等，之上按指数肩部收敛到 `C_n`。
     *
     * `F(K) = K`、`F'(K) = 1`、`F(H) = C`，局部斜率始终位于 `0～1`——膝点没有一阶折角，
     * 也不放大局部对比度（D131）。
     */
    fun shoulder(
        normalizedValue: Double,
        normalizedHeadroom: Double,
        normalizedCapacity: Double,
        xi: Double
    ): Double {
        if (normalizedValue <= KNEE_NORMALIZED) return normalizedValue
        if (normalizedCapacity <= KNEE_NORMALIZED) {
            return kotlin.math.min(normalizedValue, normalizedCapacity)
        }
        if (xi <= SHOULDER_XI_EPSILON) return normalizedValue
        val span = normalizedHeadroom - KNEE_NORMALIZED
        if (span <= EPSILON) return normalizedValue
        val a = span / xi
        val mapped = KNEE_NORMALIZED +
            a * (1.0 - exp(-(normalizedValue - KNEE_NORMALIZED) / a))
        // 上限钳位只为浮点舍入安全；端点本来就由 A_n 的解精确落在 C_n 上（D131）。
        return kotlin.math.min(mapped, normalizedCapacity)
    }

    /**
     * 一维肩部参数表：键 `C_n`，值 `ξ`。
     *
     * 表在导出开始时按当前固定的 HDR 强度生成一次，shader 只做确定性线性插值——不得逐像素
     * 迭代求根，也不得用最近点查表造成颜色渐变中的阶梯（D131）。
     */
    class ShoulderTable(
        val lowCapacity: Double,
        val highCapacity: Double,
        val normalizedHeadroom: Double,
        val values: FloatArray
    ) {

        /** 与 shader 完全相同的插值：先夹进表域，再线性插值。 */
        fun xiAt(normalizedCapacity: Double): Double {
            val span = highCapacity - lowCapacity
            if (span <= EPSILON || values.size < 2) return values.firstOrNull()?.toDouble() ?: 0.0
            val t = ((normalizedCapacity - lowCapacity) / span)
                .coerceIn(0.0, 1.0) * (values.size - 1)
            val index = t.toInt().coerceIn(0, values.size - 2)
            val frac = t - index
            return values[index] + (values[index + 1] - values[index]) * frac
        }
    }

    /**
     * 该方向域上 `C_n` 的取值范围。
     *
     * 下界是名义范围下的最小容量（`1 / q_max`），上界是标准 super-white 下的最大容量。表域
     * 取实际可达区间而不是 `[K_n, ∞)`：靠近 `K_n` 的表项 `ξ` 会发散，把它们塞进表里只会
     * 拉低真正用得到那一段的插值精度。
     */
    fun capacityBounds(gridSize: Int = CAPACITY_SCAN_GRID): DoubleArray {
        var low = Double.MAX_VALUE
        var high = 0.0
        forEachDirection(gridSize) { u ->
            val q = directionScale(u)
            if (q > EPSILON) {
                val nominal = SIGNAL_NOMINAL / q
                val standard = hlgInverseOetf(standardCeiling(u)) / q
                if (nominal < low) low = nominal
                if (standard > high) high = standard
            }
        }
        if (low > high) return doubleArrayOf(KNEE_NORMALIZED, KNEE_NORMALIZED + 1.0)
        // 留一点余量，避免边界方向恰好落在表外被夹掉最后一档。
        return doubleArrayOf(
            max(low - CAPACITY_MARGIN, KNEE_NORMALIZED),
            high + CAPACITY_MARGIN
        )
    }

    /**
     * 按当前 HDR 强度生成肩部参数表。
     *
     * @param strength 用户的 HDR 高光强度 `H_D`（显示线性倍数）。
     */
    fun buildShoulderTable(
        strength: Double,
        size: Int = SHOULDER_TABLE_SIZE
    ): ShoulderTable {
        val bounds = capacityBounds()
        val headroom = max(strength, 1.0).pow(INVERSE_GAMMA)
        val values = FloatArray(size)
        val span = bounds[1] - bounds[0]
        for (index in 0 until size) {
            val capacity = bounds[0] + span * index / (size - 1).coerceAtLeast(1)
            values[index] = shoulderXi(capacity, headroom).toFloat()
        }
        return ShoulderTable(bounds[0], bounds[1], headroom, values)
    }

    /**
     * 遍历方向域：`max(u) = 1` 的三个立方体面（D165）。
     *
     * 面与面共享边上的网格点由同一方向求得同一值，因此插值天然连续。
     */
    inline fun forEachDirection(gridSize: Int, action: (DoubleArray) -> Unit) {
        val last = (gridSize - 1).coerceAtLeast(1)
        for (face in 0 until 3) {
            for (i in 0 until gridSize) {
                val a = i.toDouble() / last
                for (j in 0 until gridSize) {
                    val b = j.toDouble() / last
                    action(directionAt(face, a, b))
                }
            }
        }
    }

    /**
     * 面号与两个面内坐标 → 单位颜色方向。
     *
     * 面 0 的最大分量是 R，坐标是 (G, B)；面 1 是 G，坐标 (R, B)；面 2 是 B，坐标 (R, G)。
     * shader 按同一约定选面与取坐标，两处必须一致。
     */
    fun directionAt(face: Int, a: Double, b: Double): DoubleArray = when (face) {
        0 -> doubleArrayOf(1.0, a, b)
        1 -> doubleArrayOf(a, 1.0, b)
        else -> doubleArrayOf(a, b, 1.0)
    }

    /**
     * 完整的逐像素映射，**测试与回归的参考实现**。
     *
     * @param deviceCeiling 该方向经 D139～D140 验证后的设备上限；名义范围传 [SIGNAL_NOMINAL]。
     * @return 非线性 HLG 信号 R′G′B′；最大分量恰好落在实际使用的上限上。
     */
    fun mapDisplayLinear(
        r: Double,
        g: Double,
        b: Double,
        strength: Double,
        table: ShoulderTable,
        deviceCeiling: (DoubleArray) -> Double = { SIGNAL_MAX }
    ): DoubleArray {
        val headroom = max(strength, 1.0)
        val red = r.coerceIn(0.0, headroom)
        val green = g.coerceIn(0.0, headroom)
        val blue = b.coerceIn(0.0, headroom)
        val scale = max(max(red, green), blue)
        if (scale <= EPSILON) return doubleArrayOf(0.0, 0.0, 0.0)
        val direction = doubleArrayOf(red / scale, green / scale, blue / scale)
        val scene = sceneLinear(red, green, blue)
        val m = max(max(scene[0], scene[1]), scene[2])
        if (m <= EPSILON) return doubleArrayOf(0.0, 0.0, 0.0)
        // 逆 OOTF 的 1/γ 次齐次性：`m = q(u) · s^(1/γ)`。直接由本像素反解 q，比再跑一遍
        // 方向的完整变换更省，也保证两个量精确同源——shader 走的正是这条路。
        val normalized = scale.pow(INVERSE_GAMMA)
        val q = m / normalized
        if (q <= EPSILON) return doubleArrayOf(0.0, 0.0, 0.0)
        // `matchCapacity` 对正比例缩放不变，因此可以直接读像素本身的颜色方向。
        val ceiling = kotlin.math.min(
            kotlin.math.min(hlgOetf(matchCapacity(direction)), SIGNAL_MAX),
            deviceCeiling(direction)
        )
        val capacity = hlgInverseOetf(ceiling) / q
        val mapped = shoulder(normalized, table.normalizedHeadroom, capacity, table.xiAt(capacity))
        val gain = mapped / normalized
        return doubleArrayOf(
            hlgOetf(scene[0] * gain),
            hlgOetf(scene[1] * gain),
            hlgOetf(scene[2] * gain)
        )
    }

    private const val BISECTION_STEPS = 80
    private const val CAPACITY_SCAN_GRID = 33
    private const val CAPACITY_MARGIN = 0.02
}
