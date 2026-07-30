package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 导出请求模型：用户**意图**的类型化表达（fablesol-video-export D62、D136～D137、
 * D145、D148～D151、D160）。
 *
 * 与"实际落点"必须分开。此前 [FableSolExportOptions] 把两者混在一起：`hdrEnabled` 既是
 * 用户的选择、又被当作"这次导出是不是 HDR"的结论，于是文件名、进度、完成态和诊断各自
 * 猜一遍落点，四处的说法可以不一致。现在意图归本文件，落点归
 * [FableSolExportResolvedCandidate]，两者只在候选解析这一处交汇。
 *
 * **持久化一律使用 [stableId]，不使用 Kotlin ordinal。** 序号会被新增枚举项破坏：早先
 * `HdrFormatPreference` 就因为只能往末尾追加，把"按规格排序"和"按存储序号排序"两件事绑死了。
 */
internal interface FableSolExportStableChoice {
    /** 固定英文标识；进 SharedPreferences 与能力缓存，不随 locale 或枚举顺序改变。 */
    val stableId: String
}

private fun <T> Array<T>.byStableId(value: String?, fallback: T): T
    where T : FableSolExportStableChoice =
    firstOrNull { it.stableId == value } ?: fallback

/**
 * 导出色彩模式：单一互斥选择器（D62），取代原先"HDR 开关 + HDR 格式"两处表达同一件事。
 *
 * 排列顺序即设置页胶囊顺序：两种 SDR、`HDR（自动）`、各具体 HDR 格式（与
 * [FableSolExportHdrFormat.AUTO_ORDER] 一致）。
 */
internal enum class FableSolExportColorMode(
    override val stableId: String,
    /** 显式请求的 HDR 格式；`null` 表示 SDR 或"自动"。 */
    val explicitFormat: FableSolExportHdrFormat?,
    /** 是否为"HDR 自动"：允许跨 HDR 格式尝试，全部失败后回退原生 SDR。 */
    val automaticHdr: Boolean
) : FableSolExportStableChoice {

    /** 关闭额外 HDR 高光后重新渲染，保持既有行为（D62 第 1 条）。 */
    SDR_NATIVE("sdr-native", null, false),

    /** 保留当前 HDR 强度渲染，再做 HDR→SDR 色调映射压入 SDR（D62 第 2 条）。 */
    SDR_TONE_MAPPED("sdr-tone-mapped", null, false),

    HDR_AUTO("hdr-auto", null, true),
    HDR10_PLUS("hdr10-plus", FableSolExportHdrFormat.HDR10_PLUS, false),
    DOLBY_VISION_84("dolby-vision-84", FableSolExportHdrFormat.DOLBY_VISION_84, false),
    HDR10("hdr10", FableSolExportHdrFormat.HDR10, false),
    HLG("hlg", FableSolExportHdrFormat.HLG, false);

    val isSdr: Boolean get() = this == SDR_NATIVE || this == SDR_TONE_MAPPED

    /** 显式 HDR 格式是严格请求：只穷尽该格式内部候选，失败即结束（D106）。 */
    val isExplicitHdr: Boolean get() = explicitFormat != null

    val requestsHdr: Boolean get() = automaticHdr || explicitFormat != null

    /** 该模式最终是否允许发布 SDR 产物：只有两种 SDR 与"自动"允许（D106）。 */
    val allowsSdrResult: Boolean get() = isSdr || automaticHdr

    companion object {
        val DEFAULT = HDR_AUTO

        fun fromStableId(value: String?): FableSolExportColorMode =
            entries.toTypedArray().byStableId(value, DEFAULT)

        /** 旧 `hdrEnabled` + `HdrFormatPreference` 序号的迁移目标（D62、D141）。 */
        fun migrateFromLegacy(hdrEnabled: Boolean, legacyFormatOrdinal: Int): FableSolExportColorMode {
            if (!hdrEnabled) return SDR_NATIVE
            // 旧枚举的声明顺序即持久化序号：AUTO, HDR10, HLG, HDR10_PLUS, DV84, DV81, DV5。
            return when (legacyFormatOrdinal) {
                1 -> HDR10
                2 -> HLG
                3 -> HDR10_PLUS
                // 杜比视界三档统一迁移为显式 8.4：保留"用户明确要杜比视界"的意图，
                // 不进一步改成自动（D141）。
                4, 5, 6 -> DOLBY_VISION_84
                else -> HDR_AUTO
            }
        }
    }
}

/** 保留高光 SDR 的映射方式（D65～D67、D71～D77）；只在 [FableSolExportColorMode.SDR_TONE_MAPPED] 下生效。 */
internal enum class FableSolExportSdrMapping(
    override val stableId: String
) : FableSolExportStableChoice {

    /** 由 HDR 强度直接定曲线，不预扫描全片（D66～D67）。 */
    STABLE("stable"),

    /** 只按 FableSol 真实超白峰值调整 `>1.0` 高光（D71、D73～D75）。 */
    DYNAMIC("dynamic");

    companion object {
        val DEFAULT = STABLE

        fun fromStableId(value: String?): FableSolExportSdrMapping =
            entries.toTypedArray().byStableId(value, DEFAULT)
    }
}

/**
 * SDR 产物**实际采用**的成片语义（D77、D78、D81）。
 *
 * 与 [FableSolExportColorMode] + [FableSolExportSdrMapping] 那一对**请求**值分开：运行时的
 * 两条降级都只改这里，不动用户偏好——动态统计通路失败退 [TONE_MAPPED_STABLE]（D77）、
 * FP16 扩展线性渲染不可用退 [NATIVE]（D78）。文件名短标签、完成 Dialog、通知与日志一律读
 * 本值，不得沿用失败尝试的标签。
 */
internal enum class FableSolExportSdrRender(
    override val stableId: String,
    /** 进文件名的短标签（D81）；`SDR` 沿用既有写法，保持旧文件命名兼容。 */
    val fileTag: String,
    /** 渲染时是否保留用户的 HDR 高光强度；为假即关闭额外高光重新渲染。 */
    val usesHdrSource: Boolean
) : FableSolExportStableChoice {

    NATIVE("sdr-native", "SDR", false),
    TONE_MAPPED_STABLE("sdr-tm-stable", "SDR-TM", true),
    TONE_MAPPED_DYNAMIC("sdr-tm-dynamic", "SDR-DTM", true);

    val toneMapped: Boolean get() = this != NATIVE

    val dynamic: Boolean get() = this == TONE_MAPPED_DYNAMIC

    companion object {

        /**
         * 由请求解析出本次**打算**采用的语义；运行时降级由导出侧另行改写。
         *
         * @return null 表示这次产物是 HDR，不适用 SDR 成片语义。
         */
        fun of(
            colorMode: FableSolExportColorMode,
            mapping: FableSolExportSdrMapping,
            hdrResult: Boolean
        ): FableSolExportSdrRender? = when {
            hdrResult -> null
            // 「HDR 自动」全部 HDR 候选失败后退到的是**原生** SDR：色调映射是显式的创作
            // 选择，不该由一次能力降级替用户做主。
            colorMode != FableSolExportColorMode.SDR_TONE_MAPPED -> NATIVE
            mapping == FableSolExportSdrMapping.DYNAMIC -> TONE_MAPPED_DYNAMIC
            else -> TONE_MAPPED_STABLE
        }

        fun fromStableId(value: String?): FableSolExportSdrRender =
            entries.toTypedArray().byStableId(value, NATIVE)
    }
}

/** 明确选择 SDR 时的视频位深意图（D160）。HDR 一律 10-bit，不读取本项。 */
internal enum class FableSolExportSdrBitDepth(
    override val stableId: String
) : FableSolExportStableChoice {

    /** 先穷尽同规格 10-bit，再进入带抖动的 8-bit。 */
    AUTO("auto"),

    /** 严格 10-bit：不能完成时本次真实导出失败，不静默改成 8-bit。 */
    TEN_BIT("ten-bit"),

    /** 严格 8-bit：不在后台先试 10-bit。 */
    EIGHT_BIT("eight-bit");

    /** 该意图允许生成哪些位深的候选，顺序即优先级。 */
    val candidateOrder: List<Boolean>
        get() = when (this) {
            AUTO -> listOf(true, false)
            TEN_BIT -> listOf(true)
            EIGHT_BIT -> listOf(false)
        }

    val isStrict: Boolean get() = this != AUTO

    companion object {
        val DEFAULT = AUTO

        fun fromStableId(value: String?): FableSolExportSdrBitDepth =
            entries.toTypedArray().byStableId(value, DEFAULT)
    }
}

/** HLG 系格式的信号范围意图（D136～D137、D144）。 */
internal enum class FableSolExportHlgSignalRange(
    override val stableId: String
) : FableSolExportStableChoice {

    /** 验证通过则使用 super-white，未通过自动使用名义范围（默认）。 */
    AUTO_ENHANCED("auto-enhanced"),

    /** 始终把非线性 HLG 信号限制在 100%，换取更可预测的下游兼容性。 */
    NOMINAL("nominal");

    companion object {
        val DEFAULT = AUTO_ENHANCED

        fun fromStableId(value: String?): FableSolExportHlgSignalRange =
            entries.toTypedArray().byStableId(value, DEFAULT)
    }
}

/**
 * HLG 系产物**实际**使用的信号范围（D135、D136、D144）。
 *
 * 与请求侧的 [FableSolExportHlgSignalRange] 是两套词汇，不能合并：`自动增强` 是一个意图，
 * 它的结果只能是"这次真的用上了 super-white"或"这次停在名义 100%"。完成 Dialog、通知与
 * 设备诊断只允许读这一份——报告用户申请的选项，会把一次未通过的验证说成扩展信号范围。
 */
internal enum class FableSolExportHlgRange(
    override val stableId: String
) : FableSolExportStableChoice {

    /** 至少一个颜色方向真的用上了名义 100% 以上的信号。 */
    EXTENDED("hlg-extended"),

    /** 全部方向止于名义 100%；仍是有效的 BT.2020/HLG/limited range HDR 视频。 */
    NOMINAL("hlg-nominal");

    val extended: Boolean get() = this == EXTENDED

    companion object {
        fun fromStableId(value: String?): FableSolExportHlgRange =
            entries.toTypedArray().byStableId(value, NOMINAL)
    }
}

/**
 * PQ 漫反射白的界面语义（D84）。
 *
 * 不再显示"自动/手动"：母版亮度意图与导出设备无关（D82），把固定的创作基准说成设备自适应
 * 结果会误导。本机屏幕的声明峰值只作诊断或用户主动采用的一次性参考值。
 */
internal enum class FableSolExportPqWhiteMode(
    override val stableId: String
) : FableSolExportStableChoice {

    /** `标准（203 尼特）`：ITU-R BT.2408 的名义 HDR Reference White（D83）。 */
    STANDARD("standard"),

    /** `自定义（N 尼特）`：200～800 尼特创作范围。 */
    CUSTOM("custom");

    companion object {
        val DEFAULT = STANDARD
    }
}

/** 用户可见的码控模式（D145）。CBR 不在此列，它只是编码器不支持 VBR 时的内部后备。 */
internal enum class FableSolExportRateControl(
    override val stableId: String
) : FableSolExportStableChoice {

    CONSTANT_QUALITY("cq"),
    TARGET_BITRATE("vbr");

    companion object {
        val DEFAULT = CONSTANT_QUALITY

        fun fromStableId(value: String?): FableSolExportRateControl =
            entries.toTypedArray().byStableId(value, DEFAULT)
    }
}

/**
 * 码控在 `MediaFormat` 上的**实际形态**（D145、D167）。
 *
 * [CONSTANT_QUALITY_WITH_BITRATE_HINT] 与 [CONSTANT_QUALITY] 是同一编码模式的两种兼容形态，
 * 不是"换成了 VBR"：部分 OMX 编码器在 configure 阶段要求必须携带码率键，缺失直接失败。
 */
internal enum class FableSolExportRateControlForm(
    override val stableId: String,
    val userVisibleMode: FableSolExportRateControl
) : FableSolExportStableChoice {

    CONSTANT_QUALITY("cq", FableSolExportRateControl.CONSTANT_QUALITY),
    CONSTANT_QUALITY_WITH_BITRATE_HINT("cq-hint", FableSolExportRateControl.CONSTANT_QUALITY),
    VARIABLE_BITRATE("vbr", FableSolExportRateControl.TARGET_BITRATE),

    /** 仅当实际编码器不支持 VBR 时使用；完成信息必须如实显示落在 CBR。 */
    CONSTANT_BITRATE("cbr", FableSolExportRateControl.TARGET_BITRATE);

    val isConstantQuality: Boolean get() = userVisibleMode == FableSolExportRateControl.CONSTANT_QUALITY

    /** 本形态是否要下发 `KEY_BIT_RATE`。 */
    val carriesBitrate: Boolean
        get() = this != CONSTANT_QUALITY

    companion object {
        fun fromStableId(value: String?): FableSolExportRateControlForm =
            entries.toTypedArray().byStableId(value, CONSTANT_QUALITY)

        /**
         * 按用户选择与该档位的实际能力解析出形态。
         *
         * CQ 先取**纯** CQ：D145 明确禁止默认同时下发质量与码率，兼容形态只在短探测按 D167
         * 的阶梯确认必要之后才使用。目标码率优先 VBR，只有编码器确实不支持 VBR 而支持 CBR
         * 时才落 CBR，并且完成信息必须如实显示。
         */
        fun resolve(
            options: FableSolExportOptions,
            tier: FableSolExportTier
        ): FableSolExportRateControlForm = when {
            options.prefersConstantQuality -> {
                require(tier.qualityRange != null) {
                    "The exact encoder tier does not support constant quality"
                }
                CONSTANT_QUALITY
            }
            !tier.supportsVbr && tier.supportsCbr -> CONSTANT_BITRATE
            tier.supportsVbr -> VARIABLE_BITRATE
            else -> throw IllegalArgumentException(
                "The exact encoder tier supports neither VBR nor CBR"
            )
        }
    }
}

/** 交给编码器的输入通路（D158）。 */
internal enum class FableSolExportInputPath(
    override val stableId: String
) : FableSolExportStableChoice {

    /** `MediaCodec.createInputSurface()` + EGL 窗口表面，RGB→YUV 由编码器完成。 */
    SURFACE("surface"),

    /** 应用自有 P010 字节缓冲：色度位置、降采样、闭环修正与量化全部由本应用控制。 */
    APP_P010("app-p010");

    companion object {
        fun fromStableId(value: String?): FableSolExportInputPath =
            entries.toTypedArray().byStableId(value, SURFACE)
    }
}

/** 最终码值量化前使用的抖动策略。 */
internal enum class FableSolExportDither(
    override val stableId: String
) : FableSolExportStableChoice {

    /** 编码器 Surface 输入：RGB→Y′CbCr 与最终量化都由厂商完成，应用不介入。 */
    NONE("none"),

    /** 现有的确定性三角哈希；D162 之后只作为蓝噪声资源不可用时的同格式后备。 */
    TRIANGULAR_HASH("triangular-hash"),

    /** 64×64 静态蓝噪声阈值舍入：应用自有 P010 在 Y′/Cb/Cr 码值域各自执行（D157）。 */
    BLUE_NOISE("blue-noise");

    companion object {
        fun fromStableId(value: String?): FableSolExportDither =
            entries.toTypedArray().byStableId(value, NONE)
    }
}
