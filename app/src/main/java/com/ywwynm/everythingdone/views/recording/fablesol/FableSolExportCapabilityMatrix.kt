package com.ywwynm.everythingdone.views.recording.fablesol

import android.util.Range

/**
 * 一次能力探测得出的**可行组合表**：
 * (HDR 格式 × 编码器族 × 帧率 × 位深 × 编码模式) 五元组各自能不能真的编出来。
 *
 * 之所以要整张表而不是一句"HDR 可用"：这几个轴互相约束，而约束关系是设备相关的。HDR 各
 * 格式的阶梯里没有 AVC，杜比视界只有一个 MIME，AV1 在某些机器上只有软件实现且撑不到
 * 120fps。界面要按用户当前的选择把不成立的选项置灰，就必须知道整张表，而不只是"至少有一
 * 种组合成立"。
 *
 * **位深是独立的一轴**（D160）：用户可以严格要求 10-bit 或 8-bit，因此"这台机器的 SDR 能
 * 用 HEVC"不足以回答"它的 10-bit SDR 能不能用 HEVC"——8-bit 通过而 10-bit 失败的设备是真
 * 实存在的（三星 Z Fold4）。
 *
 * 失败原因一律以 [FableSolExportFailure] 结构化保存，展示时才本地化：原因是设备事实，
 * 与 locale 无关。
 */
internal data class FableSolExportCombinationOutcome(
    /** 通过验证的具体编码器实现名；null 表示这一组合不成立。 */
    val codecName: String?,
    /** 该实现是否为纯软件编码器；它属于公开规格字段，界面必须明确标注。 */
    val softwareOnly: Boolean,
    /** 未通过时的结构化原因；结构上就不存在候选时为 [FableSolExportFailure.NO_CANDIDATE]。 */
    val failure: FableSolExportFailure?,
    /**
     * 通过验证的阶梯项名，例如 “HEVC Main10”“HEVC Main SDR”。
     *
     * 只记编码器族是不够的：同一族里 10-bit 与 8-bit 是两个阶梯项，分不出来就无法判断
     * 一台机器是 10-bit 编不了，还是仅仅 HDR 信号编不了（三星 Z Fold4 上正卡在这个岔口）。
     */
    val profileLabel: String? = null,
    /**
     * 通过验证的输入通路稳定标识（D158）：应用自有 P010 还是编码器 Surface。
     *
     * 10-bit 的两条通路是同一档位下的子候选，探测按 P010 优先的顺序试，这里记的是真正编出
     * 一帧的那一条。旧缓存里没有这个字段。
     */
    val inputPathId: String? = null,
    /**
     * 该编码器实际码流里声明的 4:2:0 色度位置稳定标识（D154、D170）；null 表示未声明。
     *
     * 相位必须在第一帧渲染之前定下来，而码流要到编码开始之后才有——因此正式导出只能读这份
     * 探测结论。取不到时按 Type 0 兼容语义，不影响导出成败。
     */
    val chromaSitingId: String? = null,
    /**
     * 该组合实际通过验证的码控形态稳定标识（D167）；旧缓存里没有这个字段。
     *
     * CQ 有纯 CQ 与 CQ+码率提示两种同模式形态。正式导出必须用**探测通过的那一种**——探一种
     * 用另一种，等于这次探测什么也没证明。
     */
    val rateControlFormId: String? = null,
    /** CQ 质量原值的实际编码器区间；只在该精确组合的 CQ 探测通过时存在（D146、D183）。 */
    val qualityLower: Int? = null,
    val qualityUpper: Int? = null,
    /**
     * 探测通过时 `KEY_COMPLEXITY = upper` 的实际形态（D149）：[COMPLEXITY_UPPER] 表示带着
     * 最高复杂度通过，[COMPLEXITY_OMITTED] 表示该键被拒、省略后才通过；null 表示探测没有
     * 尝试该键（区间不可用），或旧缓存没有这个字段。正式导出必须与探测通过的形态同源。
     */
    val highComplexityFormId: String? = null
) {
    val usable: Boolean get() = codecName != null

    val qualityRange: Range<Int>?
        get() {
            val lower = qualityLower ?: return null
            val upper = qualityUpper ?: return null
            return if (upper > lower) Range(lower, upper) else null
        }

    companion object {
        /** [highComplexityFormId]：带 `KEY_COMPLEXITY = upper` 通过。 */
        const val COMPLEXITY_UPPER = "upper"

        /** [highComplexityFormId]：该键被拒，省略后才通过（D149 的阶梯落点）。 */
        const val COMPLEXITY_OMITTED = "omitted"
    }
}

internal class FableSolExportCapabilityMatrix private constructor(
    private val rows: Map<Key, FableSolExportCombinationOutcome>
) {

    internal data class Key(
        val formatLabel: String,
        val familyLabel: String,
        val frameRate: Int,
        val tenBit: Boolean,
        val rateControlId: String
    )

    /**
     * 格式轴的通配值。
     *
     * 不能直接用可空枚举：`null` 在格式轴上已经表示 SDR 了，"不限"必须是第三种取值。
     */
    sealed class FormatFilter {

        /** 任何格式，含 SDR。 */
        object Unrestricted : FormatFilter()

        /** 只看 HDR，不含 SDR。 */
        object AnyHdr : FormatFilter()

        /** 只看这一种；[format] 为 null 表示 SDR。 */
        data class Exactly(val format: FableSolExportHdrFormat?) : FormatFilter()

        internal fun matches(label: String): Boolean = when (this) {
            Unrestricted -> true
            AnyHdr -> label != FableSolExportHdrFormat.SDR_LABEL
            is Exactly -> label == labelOf(format)
        }
    }

    val isEmpty: Boolean get() = rows.isEmpty()

    fun outcome(
        format: FableSolExportHdrFormat?,
        family: FableSolExportCodecFamily,
        frameRate: Int,
        tenBit: Boolean,
        rateControl: FableSolExportRateControl = FableSolExportRateControl.DEFAULT
    ): FableSolExportCombinationOutcome? =
        rows[
            Key(
                labelOf(format),
                family.stableLabel,
                frameRate,
                tenBit,
                rateControl.stableId
            )
        ]

    /**
     * 该组合在任一位深下的最佳结论：10-bit 优先（画质优先的自动位深顺序），都不成立时返回
     * 10-bit 那一条的失败原因。
     */
    fun bestOutcome(
        format: FableSolExportHdrFormat?,
        family: FableSolExportCodecFamily,
        frameRate: Int,
        rateControl: FableSolExportRateControl = FableSolExportRateControl.DEFAULT
    ): Pair<Boolean, FableSolExportCombinationOutcome>? {
        for (tenBit in BIT_DEPTHS) {
            val outcome = outcome(format, family, frameRate, tenBit, rateControl) ?: continue
            if (outcome.usable) return tenBit to outcome
        }
        for (tenBit in BIT_DEPTHS) {
            val outcome = outcome(format, family, frameRate, tenBit, rateControl) ?: continue
            return tenBit to outcome
        }
        return null
    }

    /** 通配查询：[family] / [frameRate] / [tenBit] 传 null 即"这一轴不限"。 */
    fun hasUsable(
        format: FormatFilter = FormatFilter.Unrestricted,
        family: FableSolExportCodecFamily? = null,
        frameRate: Int? = null,
        tenBit: Boolean? = null,
        rateControl: FableSolExportRateControl? = null,
        allowSoftware: Boolean = true
    ): Boolean = rows.any { (key, outcome) ->
        outcome.usable &&
            (allowSoftware || !outcome.softwareOnly) &&
            format.matches(key.formatLabel) &&
            (family == null || key.familyLabel == family.stableLabel) &&
            (frameRate == null || key.frameRate == frameRate) &&
            (tenBit == null || key.tenBit == tenBit) &&
            (rateControl == null || key.rateControlId == rateControl.stableId)
    }

    /**
     * 自动档在这一格式与帧率下会落到哪个编码器族。
     *
     * 顺序与导出建议一致：先比较全部硬件编码器族，再比较软件实现；公开规格发生变化时，
     * 正式导出仍须按 D179 请求确认。
     */
    fun autoFamily(
        format: FableSolExportHdrFormat?,
        frameRate: Int,
        rateControl: FableSolExportRateControl = FableSolExportRateControl.DEFAULT,
        tenBit: Boolean? = null
    ): FableSolExportCodecFamily? = FableSolExportCodecFamily.entries
        .mapNotNull { family ->
            val best = if (tenBit == null) {
                bestOutcome(format, family, frameRate, rateControl)
            } else {
                outcome(format, family, frameRate, tenBit, rateControl)?.let { tenBit to it }
            }
            best?.takeIf { it.second.usable }?.let { family to it.second }
        }
        .minWithOrNull(
            compareBy<Pair<FableSolExportCodecFamily, FableSolExportCombinationOutcome>>(
                { it.second.softwareOnly },
                { it.first.ordinal }
            )
        )
        ?.first

    /**
     * 「自动」格式在**给定编码器约束下**的落点；[family] 为 null 表示编码器也取自动。
     *
     * 这条约束不能省。把编码器钉成 AV1 之后，格式胶囊已经正确地只留下 AV1 编得出的那几种，
     * 而说明文字若仍用不带编码器约束的全局答案，就会写出「当前为 HDR10+」这种 AV1 根本
     * 交付不了的结论（OPPO 上实际出现过，2026-07-27）。顺序与导出阶梯一致。
     */
    fun autoFormat(
        family: FableSolExportCodecFamily?,
        frameRate: Int,
        rateControl: FableSolExportRateControl = FableSolExportRateControl.DEFAULT
    ): FableSolExportHdrFormat? = FableSolExportHdrFormat.AUTO_ORDER.firstOrNull { format ->
        hasUsable(
            format = FormatFilter.Exactly(format),
            family = family,
            frameRate = frameRate,
            rateControl = rateControl
        )
    }

    /**
     * 设置页三轴的唯一解析入口（D179）。
     *
     * [frameRate] 是严格规格；格式说明、编码器说明、置灰状态和导出可用性都必须读取本方法的
     * 同一份结论。格式优先于编码器实现；同格式、同位深内先比较全部硬件族，再比较软件族。
     */
    data class ResolvedSelection(
        val format: FableSolExportHdrFormat?,
        val family: FableSolExportCodecFamily,
        val frameRate: Int,
        val tenBit: Boolean,
        val outcome: FableSolExportCombinationOutcome
    )

    fun resolve(
        colorMode: FableSolExportColorMode,
        codec: FableSolExportOptions.CodecPreference,
        frameRate: Int,
        sdrBitDepth: FableSolExportSdrBitDepth,
        rateControl: FableSolExportRateControl = FableSolExportRateControl.DEFAULT
    ): ResolvedSelection? {
        val formats: List<FableSolExportHdrFormat?> = when {
            colorMode.isSdr -> listOf(null)
            colorMode.explicitFormat != null -> listOf(colorMode.explicitFormat)
            else -> FableSolExportHdrFormat.AUTO_ORDER + listOf(null)
        }
        val families = codec.family?.let(::listOf)
            ?: FableSolExportCodecFamily.entries.toList()
        for (format in formats) {
            val bitDepths = if (format == null) {
                sdrBitDepth.candidateOrder
            } else {
                listOf(true)
            }
            for (tenBit in bitDepths) {
                val best = families
                    .mapNotNull { family ->
                        outcome(format, family, frameRate, tenBit, rateControl)
                            ?.takeIf { it.usable }
                            ?.let { family to it }
                    }
                    .minWithOrNull(
                        compareBy<
                            Pair<FableSolExportCodecFamily, FableSolExportCombinationOutcome>
                        >(
                            { it.second.softwareOnly },
                            { it.first.ordinal }
                        )
                    )
                    ?: continue
                return ResolvedSelection(
                    format = format,
                    family = best.first,
                    frameRate = frameRate,
                    tenBit = tenBit,
                    outcome = best.second
                )
            }
        }
        return null
    }

    /** 某个编码器族在该格式下的落点：能达到的最高帧率、位深，以及它是不是软件实现。 */
    data class FamilyReach(
        val family: FableSolExportCodecFamily,
        val frameRate: Int,
        val softwareOnly: Boolean,
        /** 通过验证的阶梯项名，例如 “HEVC Main10 SDR”；旧缓存里可能缺失。 */
        val profileLabel: String?,
        val tenBit: Boolean
    ) {
        /** 报告里用的名字：有阶梯项名就用它，它比编码器族名多出位深这一层信息。 */
        val displayLabel: String get() = profileLabel ?: family.stableLabel

        /** 面向用户的简短写法：编码器族加位深，例如 “HEVC 10-bit”。 */
        val compactLabel: String
            get() = family.stableLabel + if (tenBit) " 10-bit" else " 8-bit"
    }

    /**
     * 该格式下**全部**可用的编码器族，各自取能达到的最高帧率；顺序即编码阶梯顺序。
     *
     * 必须给全。只报第一个落点会漏掉真实存在的选择：OPPO 上 HLG 的 HEVC 与 AV1 两条路都
     * 成立，能力报告却只写了 HEVC（2026-07-27）。
     */
    fun reach(
        format: FableSolExportHdrFormat?,
        rateControl: FableSolExportRateControl = FableSolExportRateControl.DEFAULT
    ): List<FamilyReach> {
        val result = ArrayList<FamilyReach>(3)
        for (family in FableSolExportTier.familiesFor(format)) {
            // FRAME_RATES 从高到低，第一个成立的就是该编码器能达到的最高帧率。
            for (frameRate in FRAME_RATES) {
                val best = bestOutcome(format, family, frameRate, rateControl) ?: continue
                val (tenBit, outcome) = best
                if (!outcome.usable) continue
                result += FamilyReach(
                    family, frameRate, outcome.softwareOnly, outcome.profileLabel, tenBit
                )
                break
            }
        }
        return result
    }

    /** 这一格式与编码器族下真正能达到的最高帧率；都不成立时为 null。 */
    fun bestFrameRate(
        format: FableSolExportHdrFormat?,
        family: FableSolExportCodecFamily?,
        rateControl: FableSolExportRateControl = FableSolExportRateControl.DEFAULT
    ): Int? = FRAME_RATES.firstOrNull { rate ->
        hasUsable(
            format = FormatFilter.Exactly(format),
            family = family,
            frameRate = rate,
            rateControl = rateControl
        )
    }

    fun encode(): String = rows.entries.joinToString(ROW_SEPARATOR) { (key, outcome) ->
        listOf(
            key.formatLabel,
            key.familyLabel,
            key.frameRate.toString(),
            if (key.tenBit) "1" else "0",
            key.rateControlId,
            outcome.codecName.orEmpty(),
            if (outcome.softwareOnly) "1" else "0",
            outcome.failure?.encode().orEmpty(),
            outcome.profileLabel.orEmpty(),
            outcome.inputPathId.orEmpty(),
            outcome.chromaSitingId.orEmpty(),
            outcome.rateControlFormId.orEmpty(),
            outcome.qualityLower?.toString().orEmpty(),
            outcome.qualityUpper?.toString().orEmpty(),
            outcome.highComplexityFormId.orEmpty()
        ).joinToString(FIELD_SEPARATOR) { sanitize(it) }
    }

    /**
     * 该组合上一次真的编出一帧时，码流声明的色度位置。
     *
     * 只有编码器名字对得上才采纳：换了实现就换了码流，沿用上一个实现的声明等于凭空猜相位。
     * 取不到时返回 null，调用方按 Type 0 兼容语义处理（D154 第 3 条、D170）。
     */
    fun chromaSiting(
        format: FableSolExportHdrFormat?,
        family: FableSolExportCodecFamily,
        frameRate: Int,
        tenBit: Boolean,
        codecName: String,
        rateControl: FableSolExportRateControl = FableSolExportRateControl.DEFAULT
    ): FableSolExportP010Math.ChromaSiting? {
        val row = outcome(format, family, frameRate, tenBit, rateControl) ?: return null
        if (row.codecName != codecName) return null
        val id = row.chromaSitingId ?: return null
        return FableSolExportP010Math.ChromaSiting.entries.firstOrNull { it.stableId == id }
    }

    /**
     * 该组合上一次真的编出一帧时，实际通过的码控形态（D167）。
     *
     * 与 [chromaSiting] 同一条规则：编码器名字对不上就不采纳，取不到时返回 null，调用方按
     * 当前档位能力自行解析（也就是从纯 CQ 重新开始那条阶梯）。
     */
    fun rateControlForm(
        format: FableSolExportHdrFormat?,
        family: FableSolExportCodecFamily,
        frameRate: Int,
        tenBit: Boolean,
        codecName: String,
        rateControl: FableSolExportRateControl = FableSolExportRateControl.DEFAULT
    ): FableSolExportRateControlForm? {
        val row = outcome(format, family, frameRate, tenBit, rateControl) ?: return null
        if (row.codecName != codecName) return null
        val id = row.rateControlFormId ?: return null
        return FableSolExportRateControlForm.entries.firstOrNull { it.stableId == id }
    }

    /**
     * 该组合探测通过时是否带着 `KEY_COMPLEXITY = upper`（D149）。
     *
     * @return null 表示没有结论（编码器不同、旧缓存或探测未尝试该键），调用方按请求下发；
     *   false 表示探测确认该编码器拒绝最高复杂度，正式导出必须省略该键。
     */
    fun highComplexityAccepted(
        format: FableSolExportHdrFormat?,
        family: FableSolExportCodecFamily,
        frameRate: Int,
        tenBit: Boolean,
        codecName: String,
        rateControl: FableSolExportRateControl = FableSolExportRateControl.DEFAULT
    ): Boolean? {
        val row = outcome(format, family, frameRate, tenBit, rateControl) ?: return null
        if (row.codecName != codecName) return null
        return when (row.highComplexityFormId) {
            FableSolExportCombinationOutcome.COMPLEXITY_UPPER -> true
            FableSolExportCombinationOutcome.COMPLEXITY_OMITTED -> false
            else -> null
        }
    }

    class Builder {

        private val rows = LinkedHashMap<Key, FableSolExportCombinationOutcome>()

        fun put(
            format: FableSolExportHdrFormat?,
            family: FableSolExportCodecFamily,
            frameRate: Int,
            tenBit: Boolean,
            outcome: FableSolExportCombinationOutcome
        ) {
            put(
                format,
                family,
                frameRate,
                tenBit,
                FableSolExportRateControl.DEFAULT,
                outcome
            )
        }

        fun put(
            format: FableSolExportHdrFormat?,
            family: FableSolExportCodecFamily,
            frameRate: Int,
            tenBit: Boolean,
            rateControl: FableSolExportRateControl,
            outcome: FableSolExportCombinationOutcome
        ) {
            rows[
                Key(
                    labelOf(format),
                    family.stableLabel,
                    frameRate,
                    tenBit,
                    rateControl.stableId
                )
            ] = outcome
        }

        fun build(): FableSolExportCapabilityMatrix = FableSolExportCapabilityMatrix(rows)
    }

    companion object {

        /** 帧率轴从高到低；[bestFrameRate] 依赖这个顺序。 */
        val FRAME_RATES = listOf(
            FableSolExportOptions.FRAME_RATE_HIGH,
            FableSolExportOptions.FRAME_RATE_BASE
        )

        /** 位深轴：10-bit 优先，与自动位深的候选顺序一致（D160）。 */
        val BIT_DEPTHS = listOf(true, false)

        val EMPTY = FableSolExportCapabilityMatrix(emptyMap())

        internal fun labelOf(format: FableSolExportHdrFormat?): String =
            format?.stableLabel ?: FableSolExportHdrFormat.SDR_LABEL

        fun decode(text: String?): FableSolExportCapabilityMatrix {
            if (text.isNullOrEmpty()) return EMPTY
            val rows = LinkedHashMap<Key, FableSolExportCombinationOutcome>()
            for (row in text.split(ROW_SEPARATOR)) {
                if (row.isBlank()) continue
                val fields = row.split(FIELD_SEPARATOR)
                if (fields.size < 8) continue
                val frameRate = fields[2].toIntOrNull() ?: continue
                val rateControl = FableSolExportRateControl.entries
                    .firstOrNull { it.stableId == fields[4] }
                    ?: continue
                rows[
                    Key(
                        fields[0],
                        fields[1],
                        frameRate,
                        fields[3] == "1",
                        rateControl.stableId
                    )
                ] =
                    FableSolExportCombinationOutcome(
                        codecName = fields[5].takeIf { it.isNotEmpty() },
                        softwareOnly = fields[6] == "1",
                        failure = FableSolExportFailure.decode(fields[7]),
                        profileLabel = fields.getOrNull(8)?.takeIf { it.isNotEmpty() },
                        inputPathId = fields.getOrNull(9)?.takeIf { it.isNotEmpty() },
                        chromaSitingId = fields.getOrNull(10)?.takeIf { it.isNotEmpty() },
                        rateControlFormId = fields.getOrNull(11)?.takeIf { it.isNotEmpty() },
                        qualityLower = fields.getOrNull(12)?.toIntOrNull(),
                        qualityUpper = fields.getOrNull(13)?.toIntOrNull(),
                        highComplexityFormId =
                            fields.getOrNull(14)?.takeIf { it.isNotEmpty() }
                    )
            }
            return FableSolExportCapabilityMatrix(rows)
        }

        /** 分隔符不能出现在字段里：异常信息是任意文本，进表之前先清掉这两个控制字符。 */
        private fun sanitize(value: String): String =
            value.replace(ROW_SEPARATOR, " ").replace(FIELD_SEPARATOR, " ")

        private val ROW_SEPARATOR = 1.toChar().toString()
        private val FIELD_SEPARATOR = 2.toChar().toString()
    }
}
