package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 一次能力探测得出的**可行组合表**：(HDR 格式 × 编码器族 × 帧率) 三元组各自能不能真的编出来。
 *
 * 之所以要整张表而不是一句"HDR 可用"：这三个轴互相约束，而约束关系是设备相关的。HDR 各
 * 格式的阶梯里没有 AVC，杜比视界只有一个 MIME，AV1 在某些机器上只有软件实现且撑不到
 * 120fps。界面要按用户当前的选择把不成立的选项置灰，就必须知道整张表，而不只是"至少有一
 * 种组合成立"。
 *
 * 这些数据本来就在探测过程中产生。此前每种格式一旦编成一帧就跳出循环，只留下"该格式可用"
 * 这一个布尔量，于是既说不出它落在哪个编码器、哪个帧率上，也无从判断别的组合成不成立。
 */
internal data class FableSolExportCombinationOutcome(
    /** 通过验证的具体编码器实现名；null 表示这一组合不成立。 */
    val codecName: String?,
    /** 该实现是否为纯软件编码器。自动档不使用软件编码器，界面需要标出来。 */
    val softwareOnly: Boolean,
    /** 未通过时的原因；结构上就不存在候选时给出固定说明。 */
    val failure: String?,
    /**
     * 通过验证的阶梯项名，例如 “HEVC Main10”“HEVC Main SDR”。
     *
     * 只记编码器族是不够的：同一族里 10-bit 与 8-bit 是两个阶梯项，分不出来就无法判断
     * 一台机器是 10-bit 编不了，还是仅仅 HDR 信号编不了（三星 Z Fold4 上正卡在这个岔口）。
     */
    val profileLabel: String? = null,
    /**
     * 通过验证的档位是不是 10 位。
     *
     * 位深要露出来：本项目的 SDR 阶梯首选也是 10 位（大面积缓变的水体最怕色带），但 10 位
     * HEVC 的分享兼容性明显差于 8 位，用户有权知道自己拿到的是哪一种。
     */
    val tenBit: Boolean = false
) {
    val usable: Boolean get() = codecName != null
}

internal class FableSolExportCapabilityMatrix private constructor(
    private val rows: Map<Key, FableSolExportCombinationOutcome>
) {

    internal data class Key(
        val formatLabel: String,
        val familyLabel: String,
        val frameRate: Int
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
        frameRate: Int
    ): FableSolExportCombinationOutcome? =
        rows[Key(labelOf(format), family.stableLabel, frameRate)]

    /** 通配查询：[family] / [frameRate] 传 null 即"这一轴不限"。 */
    fun hasUsable(
        format: FormatFilter = FormatFilter.Unrestricted,
        family: FableSolExportCodecFamily? = null,
        frameRate: Int? = null,
        allowSoftware: Boolean = true
    ): Boolean = rows.any { (key, outcome) ->
        outcome.usable &&
            (allowSoftware || !outcome.softwareOnly) &&
            format.matches(key.formatLabel) &&
            (family == null || key.familyLabel == family.stableLabel) &&
            (frameRate == null || key.frameRate == frameRate)
    }

    /**
     * 自动档在这一格式与帧率下会落到哪个编码器族。
     *
     * 顺序即 [FableSolExportCodecFamily] 的声明顺序（HEVC、AV1、H.264），与导出阶梯一致；
     * 软件编码器不参与自动档。
     */
    fun autoFamily(
        format: FableSolExportHdrFormat?,
        frameRate: Int
    ): FableSolExportCodecFamily? = FableSolExportCodecFamily.entries.firstOrNull { family ->
        outcome(format, family, frameRate)?.let { it.usable && !it.softwareOnly } == true
    }

    /**
     * 「自动」格式在**给定编码器约束下**的落点；[family] 为 null 表示编码器也取自动。
     *
     * 这条约束不能省。把编码器钉成 AV1 之后，格式胶囊已经正确地只留下 AV1 编得出的那几种，
     * 而说明文字若仍用不带编码器约束的全局答案，就会写出「当前为 HDR10+」这种 AV1 根本
     * 交付不了的结论（OPPO 上实际出现过，2026-07-27）。顺序与导出阶梯一致。
     */
    fun autoFormat(
        family: FableSolExportCodecFamily?,
        allowSoftware: Boolean
    ): FableSolExportHdrFormat? = FableSolExportHdrFormat.AUTO_ORDER.firstOrNull { format ->
        hasUsable(
            format = FormatFilter.Exactly(format),
            family = family,
            allowSoftware = allowSoftware
        )
    }

    /** 某个编码器族在该格式下的落点：能达到的最高帧率，以及它是不是软件实现。 */
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
    fun reach(format: FableSolExportHdrFormat?): List<FamilyReach> {
        val result = ArrayList<FamilyReach>(3)
        for (family in FableSolExportTier.familiesFor(format)) {
            // FRAME_RATES 从高到低，第一个成立的就是该编码器能达到的最高帧率。
            for (frameRate in FRAME_RATES) {
                val outcome = outcome(format, family, frameRate) ?: continue
                if (!outcome.usable) continue
                result += FamilyReach(
                    family, frameRate, outcome.softwareOnly, outcome.profileLabel,
                    outcome.tenBit
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
        allowSoftware: Boolean
    ): Int? = FRAME_RATES.firstOrNull { rate ->
        hasUsable(
            format = FormatFilter.Exactly(format),
            family = family,
            frameRate = rate,
            allowSoftware = allowSoftware
        )
    }

    fun encode(): String = rows.entries.joinToString(ROW_SEPARATOR) { (key, outcome) ->
        listOf(
            key.formatLabel,
            key.familyLabel,
            key.frameRate.toString(),
            outcome.codecName.orEmpty(),
            if (outcome.softwareOnly) "1" else "0",
            outcome.failure.orEmpty(),
            outcome.profileLabel.orEmpty(),
            if (outcome.tenBit) "1" else "0"
        ).joinToString(FIELD_SEPARATOR) { sanitize(it) }
    }

    class Builder {

        private val rows = LinkedHashMap<Key, FableSolExportCombinationOutcome>()

        fun put(
            format: FableSolExportHdrFormat?,
            family: FableSolExportCodecFamily,
            frameRate: Int,
            outcome: FableSolExportCombinationOutcome
        ) {
            rows[Key(labelOf(format), family.stableLabel, frameRate)] = outcome
        }

        fun build(): FableSolExportCapabilityMatrix = FableSolExportCapabilityMatrix(rows)
    }

    companion object {

        /** 帧率轴从高到低；[bestFrameRate] 依赖这个顺序。 */
        val FRAME_RATES = listOf(
            FableSolExportOptions.FRAME_RATE_HIGH,
            FableSolExportOptions.FRAME_RATE_BASE
        )

        val EMPTY = FableSolExportCapabilityMatrix(emptyMap())

        internal fun labelOf(format: FableSolExportHdrFormat?): String =
            format?.stableLabel ?: FableSolExportHdrFormat.SDR_LABEL

        fun decode(text: String?): FableSolExportCapabilityMatrix {
            if (text.isNullOrEmpty()) return EMPTY
            val rows = LinkedHashMap<Key, FableSolExportCombinationOutcome>()
            for (row in text.split(ROW_SEPARATOR)) {
                if (row.isBlank()) continue
                val fields = row.split(FIELD_SEPARATOR)
                if (fields.size < 6) continue
                val frameRate = fields[2].toIntOrNull() ?: continue
                rows[Key(fields[0], fields[1], frameRate)] = FableSolExportCombinationOutcome(
                    codecName = fields[3].takeIf { it.isNotEmpty() },
                    softwareOnly = fields[4] == "1",
                    failure = fields[5].takeIf { it.isNotEmpty() },
                    profileLabel = fields.getOrNull(6)?.takeIf { it.isNotEmpty() },
                    tenBit = fields.getOrNull(7) == "1"
                )
            }
            return FableSolExportCapabilityMatrix(rows)
        }

        /** 分隔符不能出现在字段里：异常信息是任意文本，进表之前先清掉这两个控制字符。 */
        private fun sanitize(value: String): String =
            value.replace(ROW_SEPARATOR, " ").replace(FIELD_SEPARATOR, " ")

        private const val ROW_SEPARATOR = "\u0001"
        private const val FIELD_SEPARATOR = "\u0002"
    }
}
