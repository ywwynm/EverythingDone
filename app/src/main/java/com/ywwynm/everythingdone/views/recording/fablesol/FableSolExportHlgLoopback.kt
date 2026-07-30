package com.ywwynm.everythingdone.views.recording.fablesol

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * HLG super-white 编码—解码回环的**测试图与判定规则**
 * （fablesol-video-export D139、D140）。
 *
 * 这里只有算术：色块怎么排、每块申请哪个码值、重建中位值满足什么条件才算"这段码值被保留
 * 下来了"。真正的编码、封装、解码与 CPU 读回在
 * [FableSolExportHlgVerification]，两边分开是为了让判定规则能在 JVM 上逐条测。
 *
 * ## 为什么按分量而不是按颜色测
 *
 * D140 要求分别得到 Y′、Cb、Cr 三个分量**各自**可靠保留的连续安全区间。若用真实的高饱和
 * 颜色去测，一块色块失败只说明三个分量里至少有一个越界，说不出是哪一个；而 `W_device(u)`
 * 的推导（D165）恰恰需要三条独立的区间。因此测试图沿每条轴单独走阶梯：亮度阶梯把色度钉在
 * 中性 512，色度阶梯把亮度钉在中位 512、另一个色度分量钉在 512。
 *
 * ## 判定为什么不能只看误差
 *
 * "重建值与申请值相差不超过容差"这一条单独不够用：编码器把 945 钳到 940 时误差只有 5，照样
 * 落在任何合理容差内。真正的判据是 D139 写明的那一条——名义 100% 以上的阶梯**没有全部塌缩
 * 到同一个端点**。因此每一级还要与名义端点的重建值拉开与其名义差成比例的距离。
 */
internal object FableSolExportHlgLoopback {

    /**
     * 回环实现契约版本。
     *
     * 测试图排布、阶梯档位或判定门禁改动时**必须**升级：缓存里存的是结论码值，看不出它是按
     * 哪套规则得出的（D138 要求签名覆盖"P010/Surface 路径实现版本"）。
     *
     * 版本 2（2026-07-30，D172 修订）：末级容差收紧为 `min(TOLERANCE_CODES, 档距 − 1)`。
     */
    const val CONTRACT_VERSION = 2

    /** 阶梯步长（10-bit 码值）。见 [DISTINCT_FRACTION] 对步长的要求。 */
    const val LADDER_STEP = 8

    /**
     * 重建中位值与申请码值的最大偏差。
     *
     * 平坦大色块在任何合理码控下都远好于这个数；它拦的是整体偏移与量化崩塌，不是逐像素噪声。
     *
     * **必须小于每一级的实际档距。** 否则编码器把某一级钳住时，紧邻的下一级只差一个档距，
     * 误差仍落在容差内而蒙混过关，安全上限会比真实上限高出整整一级——写出去的码值随后被
     * 编码器钳掉，而肩部还以为那段余量可用。整数倍档位的档距是 [LADDER_STEP]（6 < 8 成立）；
     * 末级单列后与前一级只差 3～4 个码值，因此判定时按级取
     * `min(TOLERANCE_CODES, 档距 − 1)`（D172 修订，2026-07-30）。
     */
    const val TOLERANCE_CODES = 6.0

    /**
     * 与名义端点必须拉开的相对距离。
     *
     * 取 0.5 是因为它同时排除两种失败：完全钳制（差值 0）与"压缩到一半"这种把阶梯挤在一起、
     * 实际已经丢掉大部分扩展余量的行为。[LADDER_STEP] 为 8 时，第一级要求的绝对差值是 4 个
     * 码值——远大于平坦块的重建噪声。
     */
    const val DISTINCT_FRACTION = 0.5

    /** 统计区相对色块边长的内缩比例；只统计中央一半，避开 4:2:0 与环路滤波污染的边界。 */
    const val INTERIOR_FRACTION = 0.5

    /** 统计区的最小边长（像素）。小于这个数说明画布放不下这么多色块。 */
    const val MIN_INTERIOR_PX = 8

    /** 中性色度码值；亮度阶梯与另一路色度阶梯都钉在这里。 */
    const val NEUTRAL_CHROMA_CODE = 512

    /** 色度阶梯用的中位亮度码值。 */
    const val MID_LUMA_CODE = 512

    /** 测试的分量与方向。每条轴各自求一段从名义端点向外延伸的连续区间。 */
    enum class Axis {
        LUMA_HIGH,
        CB_HIGH,
        CB_LOW,
        CR_HIGH,
        CR_LOW;

        /** 该轴的名义端点码值——阶梯的第 0 级，也是"塌缩"比较的基准。 */
        val nominalCode: Int
            get() = when (this) {
                LUMA_HIGH -> FableSolExportP010Math.NOMINAL_LUMA_MAX_CODE.toInt()
                CB_HIGH, CR_HIGH -> FableSolExportP010Math.NOMINAL_CHROMA_MAX_CODE.toInt()
                CB_LOW, CR_LOW -> FableSolExportP010Math.NOMINAL_CHROMA_MIN_CODE.toInt()
            }

        /** 该轴向外延伸到的视频数据范围端点。 */
        val limitCode: Int
            get() = when (this) {
                LUMA_HIGH, CB_HIGH, CR_HIGH ->
                    FableSolExportHlgDeviceRange.SafeCodes.VIDEO_MAX_CODE
                CB_LOW, CR_LOW -> FableSolExportHlgDeviceRange.SafeCodes.VIDEO_MIN_CODE
            }

        val ascending: Boolean get() = limitCode > nominalCode
    }

    /** 一块测试色块：整块填同一个 (Y′, Cb, Cr)。 */
    data class Patch(
        val axis: Axis,
        /** 本级在 [axis] 上申请的码值。 */
        val code: Int,
        val lumaCode: Int,
        val cbCode: Int,
        val crCode: Int
    ) {

        /** 本级是不是该轴的名义端点。 */
        val nominal: Boolean get() = code == axis.nominalCode
    }

    /** 一块色块的重建结果，三个分量的中位值（10-bit 码值域，可含小数）。 */
    data class Reading(
        val patch: Patch,
        val lumaMedian: Double,
        val cbMedian: Double,
        val crMedian: Double
    ) {

        /** 本块在其所属轴上的重建值。 */
        val axisMedian: Double
            get() = when (patch.axis) {
                Axis.LUMA_HIGH -> lumaMedian
                Axis.CB_HIGH, Axis.CB_LOW -> cbMedian
                Axis.CR_HIGH, Axis.CR_LOW -> crMedian
            }
    }

    /** 色块在画布上的矩形（左上原点，包含 [left]、不含 [right]）。 */
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val widthPx: Int get() = right - left
        val heightPx: Int get() = bottom - top
    }

    /**
     * 一条轴的阶梯：从名义端点起，按 [LADDER_STEP] 向外，末级恒为视频数据范围端点。
     *
     * 末级单列是因为 `1019 - 940 = 79` 不是步长的整数倍；漏掉它就永远验不出 `W_MAX` 能不能
     * 用满，而那正是这条轴最有价值的一级。
     */
    fun ladder(axis: Axis): List<Int> {
        val codes = mutableListOf<Int>()
        val step = if (axis.ascending) LADDER_STEP else -LADDER_STEP
        var code = axis.nominalCode
        while (if (axis.ascending) code < axis.limitCode else code > axis.limitCode) {
            codes.add(code)
            code += step
        }
        codes.add(axis.limitCode)
        return codes
    }

    /** 全部色块，顺序即它们在网格里的排布顺序。 */
    fun patches(): List<Patch> = Axis.entries.flatMap { axis ->
        ladder(axis).map { code ->
            when (axis) {
                Axis.LUMA_HIGH -> Patch(
                    axis, code, code, NEUTRAL_CHROMA_CODE, NEUTRAL_CHROMA_CODE
                )
                Axis.CB_HIGH, Axis.CB_LOW -> Patch(
                    axis, code, MID_LUMA_CODE, code, NEUTRAL_CHROMA_CODE
                )
                Axis.CR_HIGH, Axis.CR_LOW -> Patch(
                    axis, code, MID_LUMA_CODE, NEUTRAL_CHROMA_CODE, code
                )
            }
        }
    }

    /** 网格列数：尽量接近正方形，同时照顾画布宽高比。 */
    fun columnsFor(count: Int, widthPx: Int, heightPx: Int): Int {
        if (count <= 1) return 1
        val aspect = if (heightPx > 0) widthPx.toDouble() / heightPx else 1.0
        return ceil(sqrt(count * max(aspect, 1e-3))).toInt().coerceIn(1, count)
    }

    /**
     * 第 [index] 块色块的矩形。
     *
     * 所有边界都对齐到偶数：4:2:0 的一个色度样本覆盖 2×2 亮度样本，色块从奇数行列开始的话，
     * 边界那一列色度会同时取到两块的内容。
     */
    fun patchRect(index: Int, count: Int, widthPx: Int, heightPx: Int): Rect {
        val columns = columnsFor(count, widthPx, heightPx)
        val rows = ceil(count.toDouble() / columns).toInt().coerceAtLeast(1)
        val column = index % columns
        val row = index / columns
        return Rect(
            left = evenFloor(widthPx.toLong() * column / columns),
            top = evenFloor(heightPx.toLong() * row / rows),
            right = evenFloor(widthPx.toLong() * (column + 1) / columns),
            bottom = evenFloor(heightPx.toLong() * (row + 1) / rows)
        )
    }

    /**
     * 色块内部的统计区：中央 [INTERIOR_FRACTION] 见方。
     *
     * @return null 表示这块色块小到没有可靠的内部区域，本次回环因此不可判定。
     */
    fun interiorRect(patch: Rect): Rect? {
        val insetX = ((patch.widthPx * (1.0 - INTERIOR_FRACTION)) / 2.0).roundToInt()
        val insetY = ((patch.heightPx * (1.0 - INTERIOR_FRACTION)) / 2.0).roundToInt()
        val rect = Rect(
            left = evenCeil(patch.left + insetX),
            top = evenCeil(patch.top + insetY),
            right = evenFloor((patch.right - insetX).toLong()),
            bottom = evenFloor((patch.bottom - insetY).toLong())
        )
        if (rect.widthPx < MIN_INTERIOR_PX || rect.heightPx < MIN_INTERIOR_PX) return null
        return rect
    }

    /**
     * 由全部重建读数推出三个分量的连续安全区间。
     *
     * @return null 表示这次回环不可判定——任何一条轴的**名义端点**都没能正确重建时，其余读数
     *   也不值得相信，按 D135 使用名义范围 HLG。这与"设备不支持 HLG"是两回事。
     */
    fun deriveSafeCodes(
        readings: List<Reading>
    ): FableSolExportHlgDeviceRange.SafeCodes? {
        val bounds = mutableMapOf<Axis, Int>()
        for (axis in Axis.entries) {
            bounds[axis] = safeBound(axis, readings) ?: return null
        }
        return FableSolExportHlgDeviceRange.SafeCodes(
            lumaMaxCode = bounds.getValue(Axis.LUMA_HIGH),
            cbMinCode = bounds.getValue(Axis.CB_LOW),
            cbMaxCode = bounds.getValue(Axis.CB_HIGH),
            crMinCode = bounds.getValue(Axis.CR_LOW),
            crMaxCode = bounds.getValue(Axis.CR_HIGH)
        )
    }

    /**
     * 一条轴上从名义端点向外延伸的最远可靠码值。
     *
     * @return null 表示名义端点本身就重建不对，整份测量不可信。
     */
    fun safeBound(axis: Axis, readings: List<Reading>): Int? {
        val byCode = readings.filter { it.patch.axis == axis }.associateBy { it.patch.code }
        val ladder = ladder(axis)
        val base = byCode[axis.nominalCode] ?: return null
        if (abs(base.axisMedian - axis.nominalCode) > TOLERANCE_CODES) return null
        var bound = axis.nominalCode
        var previous = axis.nominalCode
        for (code in ladder.drop(1)) {
            val reading = byCode[code] ?: break
            // 容差不得吞掉与上一级的档距——"容差必须小于阶梯步长"的**逐级**形式（D172
            // 修订，2026-07-30）。末级与前一级只差 3～4 个码值（1019−1016、8−4），沿用整体
            // 容差 6 时，编码器恰在前一级钳制的重建值也落在末级容差内，安全上限被高报一级；
            // 收紧后误拒的代价只是区间止步于前一级，方向保守。
            val tolerance = minOf(TOLERANCE_CODES, abs(code - previous) - 1.0)
            if (abs(reading.axisMedian - code) > tolerance) break
            // 与名义端点的重建值必须按名义差成比例地拉开，方向也要对。整段塌缩到同一个端点
            // 时这一条立刻失败，而单看误差是看不出来的（D139）。
            val separation = (reading.axisMedian - base.axisMedian) *
                if (axis.ascending) 1.0 else -1.0
            if (separation < DISTINCT_FRACTION * abs(code - axis.nominalCode)) break
            bound = code
            previous = code
        }
        return bound
    }

    private fun evenFloor(value: Long): Int = ((value / 2) * 2).toInt()

    private fun evenCeil(value: Int): Int = ((value + 1) / 2) * 2
}
