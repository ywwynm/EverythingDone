package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 由回环验证得到的 Y′/Cb/Cr 连续安全区间，推导每个颜色方向的设备上限 `W_device(u)`
 * （fablesol-video-export D140、D165）。
 *
 * D140 明确禁止两件事：把红/绿/蓝三个测试结果直接切成离散颜色档，以及在正式像素路径里逐通道
 * 硬截断。因此换算只能整体做——但 `方向 → 量化后 Y′CbCr 是否落在安全区间` 这个映射经过
 * HLG OETF、BT.2020 NCL 矩阵与量化，没有闭式反解，D165 据此定为**方向域网格 + 二分**。
 *
 * 可行性判定覆盖整段 super-white 区间，而不只看端点：沿该方向按固定 `W` 步长取 `1.0` 到候选
 * `W` 的全部采样，全部合格才算可行。固定步长使较大 `W` 的检查点集合**包含**较小 `W` 的集合，
 * 可行性因此对 `W` 单调，二分才成立。
 */
internal object FableSolExportHlgDeviceRange {

    /** 方向域网格密度（每个立方体面）；属实现门禁参数，由回归测试固定。 */
    const val GRID_SIZE = 32

    /** 可行性检查沿 `W` 的固定步长（D165 首版 0.005）。 */
    const val SIGNAL_STEP = 0.005

    /**
     * 单个分量的连续安全区间，单位是 10-bit 码值。
     *
     * 名义范围是 [NOMINAL]。回环验证只会**放宽**边界：证明不了的方向保持名义值，而不是乐观
     * 地假定整段视频数据范围都被保留。
     */
    data class SafeCodes(
        val lumaMaxCode: Int,
        val cbMinCode: Int,
        val cbMaxCode: Int,
        val crMinCode: Int,
        val crMaxCode: Int
    ) {

        /** 至少有一个分量真的超出名义范围，才谈得上"扩展信号范围"。 */
        val extended: Boolean
            get() = lumaMaxCode > NOMINAL.lumaMaxCode ||
                cbMinCode < NOMINAL.cbMinCode ||
                cbMaxCode > NOMINAL.cbMaxCode ||
                crMinCode < NOMINAL.crMinCode ||
                crMaxCode > NOMINAL.crMaxCode

        fun toSignalRange(): FableSolExportP010Math.SignalRange =
            FableSolExportP010Math.SignalRange(
                lumaMinCode = FableSolExportP010Math.LUMA_MIN_CODE,
                lumaMaxCode = lumaMaxCode.toDouble(),
                cbMinCode = cbMinCode.toDouble(),
                cbMaxCode = cbMaxCode.toDouble(),
                crMinCode = crMinCode.toDouble(),
                crMaxCode = crMaxCode.toDouble()
            )

        fun encode(): String =
            "$lumaMaxCode,$cbMinCode,$cbMaxCode,$crMinCode,$crMaxCode"

        companion object {

            val NOMINAL = SafeCodes(
                lumaMaxCode = FableSolExportP010Math.NOMINAL_LUMA_MAX_CODE.toInt(),
                cbMinCode = FableSolExportP010Math.NOMINAL_CHROMA_MIN_CODE.toInt(),
                cbMaxCode = FableSolExportP010Math.NOMINAL_CHROMA_MAX_CODE.toInt(),
                crMinCode = FableSolExportP010Math.NOMINAL_CHROMA_MIN_CODE.toInt(),
                crMaxCode = FableSolExportP010Math.NOMINAL_CHROMA_MAX_CODE.toInt()
            )

            /** 10-bit 视频数据范围（BT.2100 Table 9）：4～1019，超出即非法码值。 */
            const val VIDEO_MIN_CODE = 4
            const val VIDEO_MAX_CODE = 1019

            fun decode(text: String?): SafeCodes? {
                val fields = text?.split(',') ?: return null
                if (fields.size != 5) return null
                val numbers = fields.map { it.trim().toIntOrNull() ?: return null }
                return SafeCodes(
                    lumaMaxCode = numbers[0],
                    cbMinCode = numbers[1],
                    cbMaxCode = numbers[2],
                    crMinCode = numbers[3],
                    crMaxCode = numbers[4]
                )
            }
        }
    }

    /**
     * 方向域上的 `W_device` 查表。
     *
     * [values] 按 `face * GRID_SIZE * GRID_SIZE + j * GRID_SIZE + i` 排列，`i`/`j` 是
     * [FableSolExportHlgTransform.directionAt] 的两个面内坐标；shader 按面选择再做双线性插值。
     */
    class Grid(val gridSize: Int, val values: FloatArray) {

        /** 全表恒为同一个值时可以退化成一个 uniform，省掉一张纹理与逐像素采样。 */
        val uniformCeiling: Double?
            get() {
                val first = values.firstOrNull() ?: return FableSolExportHlgTransform.SIGNAL_NOMINAL
                return if (values.all { it == first }) first.toDouble() else null
            }

        /** 该表实际允许的最高信号；完成信息与诊断读这一份。 */
        val peakCeiling: Double
            get() = values.maxOrNull()?.toDouble()
                ?: FableSolExportHlgTransform.SIGNAL_NOMINAL

        /** 与 shader 相同的双线性读取，供参考实现与测试使用。 */
        fun ceilingFor(u: DoubleArray): Double {
            val scale = max(max(u[0], u[1]), u[2])
            if (scale <= 0.0) return FableSolExportHlgTransform.SIGNAL_NOMINAL
            val r = u[0] / scale
            val g = u[1] / scale
            val b = u[2] / scale
            val face: Int
            val a: Double
            val second: Double
            when {
                r >= g && r >= b -> {
                    face = 0
                    a = g
                    second = b
                }
                g >= b -> {
                    face = 1
                    a = r
                    second = b
                }
                else -> {
                    face = 2
                    a = r
                    second = g
                }
            }
            val last = gridSize - 1
            val x = (a.coerceIn(0.0, 1.0) * last)
            val y = (second.coerceIn(0.0, 1.0) * last)
            val x0 = floor(x).toInt().coerceIn(0, last)
            val y0 = floor(y).toInt().coerceIn(0, last)
            val x1 = min(x0 + 1, last)
            val y1 = min(y0 + 1, last)
            val fx = x - x0
            val fy = y - y0
            val v00 = at(face, x0, y0)
            val v10 = at(face, x1, y0)
            val v01 = at(face, x0, y1)
            val v11 = at(face, x1, y1)
            val top = v00 + (v10 - v00) * fx
            val bottom = v01 + (v11 - v01) * fx
            return top + (bottom - top) * fy
        }

        private fun at(face: Int, i: Int, j: Int): Double =
            values[face * gridSize * gridSize + j * gridSize + i].toDouble()
    }

    /**
     * 构建方向域查表。
     *
     * @return null 表示所有方向的 `W_device` 都停在名义 100%，此时不建表、按 D135 使用名义
     *   范围 HLG。
     */
    fun buildGrid(safe: SafeCodes, gridSize: Int = GRID_SIZE): Grid? {
        if (!safe.extended) return null
        // 采样点沿 W 固定步长，从名义 100% 一路到标准上限；所有方向共用同一组场景线性容量。
        val ceilings = signalSamples()
        val capacities = DoubleArray(ceilings.size) {
            FableSolExportHlgTransform.hlgInverseOetf(ceilings[it])
        }
        val values = FloatArray(3 * gridSize * gridSize)
        var extended = false
        val last = (gridSize - 1).coerceAtLeast(1)
        for (face in 0 until 3) {
            for (j in 0 until gridSize) {
                val second = j.toDouble() / last
                for (i in 0 until gridSize) {
                    val a = i.toDouble() / last
                    val direction = FableSolExportHlgTransform.directionAt(face, a, second)
                    val ceiling = solveCeiling(direction, safe, ceilings, capacities)
                    if (ceiling > FableSolExportHlgTransform.SIGNAL_NOMINAL) extended = true
                    values[face * gridSize * gridSize + j * gridSize + i] = ceiling.toFloat()
                }
            }
        }
        return if (extended) Grid(gridSize, values) else null
    }

    /** `1.0` 起、按 [SIGNAL_STEP] 递增、不超过标准上限的固定采样点。 */
    fun signalSamples(): DoubleArray {
        val span = FableSolExportHlgTransform.SIGNAL_MAX -
            FableSolExportHlgTransform.SIGNAL_NOMINAL
        val steps = floor(span / SIGNAL_STEP + 1e-9).toInt()
        return DoubleArray(steps + 1) {
            FableSolExportHlgTransform.SIGNAL_NOMINAL + it * SIGNAL_STEP
        }
    }

    /**
     * 该方向能安全使用的最高信号。
     *
     * D165 的可行性是**累计**语义：候选 `W` 可行 = `1.0` 至 `W` 的**全部**采样都落在安全
     * 区间内；检查点集合随 `W` 包含式增长，可行性因此结构性单调。答案就是第一个不合格
     * 采样点之前的最高档——线性扫描即是该定义的直接实现。
     *
     * 曾经的写法对**逐点**判定数组做二分、末点合格还直接返回最大档：单点可行性的单调没有
     * 任何结构保证（名义区间内的色度轨迹已存在约 11.7 码值的非单调回落，越界轨迹不回落
     * 只是当前矩阵与 OETF 的几何巧合），色彩变换一变（D134 明文预留）就会静默给错上限。
     */
    private fun solveCeiling(
        direction: DoubleArray,
        safe: SafeCodes,
        ceilings: DoubleArray,
        capacities: DoubleArray
    ): Double {
        val scene = FableSolExportHlgTransform.sceneDirection(direction)
        if (scene[0] <= 0.0 && scene[1] <= 0.0 && scene[2] <= 0.0) {
            return FableSolExportHlgTransform.SIGNAL_NOMINAL
        }
        // 名义 100% 必然合格（信号落在 [0,1] 内即在名义码值范围内）；万一不合格，说明该方向
        // 连名义范围都保不住，保守停在 100%，交由 D135 的名义范围路径处理。
        if (!representable(scene, capacities[0], safe)) {
            return FableSolExportHlgTransform.SIGNAL_NOMINAL
        }
        var highest = ceilings[0]
        for (index in 1 until ceilings.size) {
            if (!representable(scene, capacities[index], safe)) break
            highest = ceilings[index]
        }
        return highest
    }

    /**
     * 该方向在这一场景线性容量下的 Y′CbCr 码值是否落在安全区间内。
     *
     * 量化按 D157 的阈值舍入：阈值在 `(0, 1)` 内，因此结果只可能是 `floor(v)` 或
     * `floor(v) + 1`。两个候选都必须合格——否则蓝噪声的某些像素会被钳制，画面上就是一片
     * 高饱和高光里冒出规则的色斑。
     */
    fun representable(
        sceneDirection: DoubleArray,
        capacity: Double,
        safe: SafeCodes
    ): Boolean {
        val definition = FableSolExportP010Math.ColorDefinition.BT2020_HLG
        val r = FableSolExportHlgTransform.hlgOetf(sceneDirection[0] * capacity)
        val g = FableSolExportHlgTransform.hlgOetf(sceneDirection[1] * capacity)
        val b = FableSolExportHlgTransform.hlgOetf(sceneDirection[2] * capacity)
        val ycc = FableSolExportP010Math.toYCbCr(definition, r, g, b)
        val luma = FableSolExportP010Math.lumaToCode(ycc[0])
        val cb = FableSolExportP010Math.chromaToCode(ycc[1])
        val cr = FableSolExportP010Math.chromaToCode(ycc[2])
        return within(luma, FableSolExportP010Math.LUMA_MIN_CODE, safe.lumaMaxCode.toDouble()) &&
            within(cb, safe.cbMinCode.toDouble(), safe.cbMaxCode.toDouble()) &&
            within(cr, safe.crMinCode.toDouble(), safe.crMaxCode.toDouble())
    }

    /**
     * `floor(v + θ)` 在 `θ ∈ (0, 1)` 上的全部可能取值都落在区间内。
     *
     * 只有 `v` 带小数时才可能进位——`floor(n + θ) == n` 对任何 `θ < 1` 成立。少了这个判断，
     * 恰好落在名义端点上的中性白（亮度码值正好 940）会被判成不可表示，`W = 1.0` 这个必然
     * 合格的采样点反而先失败。
     */
    private fun within(value: Double, lower: Double, upper: Double): Boolean {
        val low = floor(value)
        val high = if (value > low) low + 1.0 else low
        return low >= lower && high <= upper
    }
}
