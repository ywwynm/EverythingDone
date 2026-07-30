package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 一次候选验证失败的**结构化**记录。
 *
 * 能力缓存只允许保存这种结构：稳定的失败标识、平台/厂商原文，以及可核对的数值字段。此前
 * 缓存里存的是已经拼好的中文句子，一旦用户换了系统语言，缓存命中就会把上一种语言的整句
 * 原样显示出来；而"原因"本身是设备事实，与 locale 无关。当前 locale 的说明改为在
 * [FableSolHdrExportCapability.diagnostics] 展示时生成。
 *
 * [detail] / [diagnosticInfo] 一律保存**原文**，不翻译、不改写：厂商错误串是排查的一手材料，
 * 任何加工都可能把真正有用的那半句丢掉。
 */
internal data class FableSolExportFailure(
    val code: Code,
    /** 平台异常原文（类名 + message），或本应用校验抛出的英文断言原文。 */
    val detail: String? = null,
    /** `MediaCodec.CodecException.diagnosticInfo` 的厂商原串。 */
    val diagnosticInfo: String? = null,
    /** `MediaCodec.CodecException.errorCode`；不可读时为 null。 */
    val errorCode: Int? = null,
    /** 申请值（传递函数码、Profile 常量等）。 */
    val requested: Int? = null,
    /** 编码器实际回报的值。 */
    val actual: Int? = null,
    val transient: Boolean = false,
    val recoverable: Boolean = false
) {

    enum class Code(val stableId: String) {
        /** 结构上就没有候选：Profile、画布尺寸或帧率在这台机器上不成立。 */
        NO_CANDIDATE("no-candidate"),

        /** EGL 未提供该格式所需的窗口色彩空间，未执行编码验证。 */
        MISSING_EGL_COLOR_SPACE("missing-egl-color-space"),

        /** 编码器输出的传递函数与请求值不一致。 */
        TRANSFER_MISMATCH("transfer-mismatch"),

        /** 编码器输出的 Profile 与请求值不一致。 */
        PROFILE_MISMATCH("profile-mismatch"),

        /** 编码与封装流程未在限定时间内完成。 */
        FINISH_TIMEOUT("finish-timeout"),

        /** 走到了流结束，却一个视频样本都没写进容器（D61）。 */
        NO_VIDEO_SAMPLES("no-video-samples"),

        /** 输出码流未检测到 ST 2094-40 SEI。 */
        HDR10_PLUS_SEI_MISSING("hdr10plus-sei-missing"),

        /** 短探测产物里的静态 HDR 元数据与应用生成的描述符冲突（D91、D166）。 */
        STATIC_METADATA_MISMATCH("static-metadata-mismatch"),

        /** 无法创建探测用的临时文件。 */
        TEMPORARY_FILE("temporary-file"),

        /** GL 无法渲染 FP16 扩展场景缓冲，整机 HDR 通路不成立。 */
        MISSING_LINEAR_SCENE("missing-linear-scene"),

        /** 全部 HDR 格式都没有通过完整的单帧编码与封装验证。 */
        ALL_FORMATS_FAILED("all-formats-failed"),

        /** 连一个可执行完整验证的格式或编码器候选都没有。 */
        NO_FORMAT_CANDIDATE("no-format-candidate"),

        /** 探测过程本身抛出异常。 */
        PROBE_EXCEPTION("probe-exception"),

        /** 其余：编码器在 configure、EGL、start 或输出格式核验阶段抛出的异常。 */
        ENCODER_ERROR("encoder-error");

        companion object {
            fun fromStableId(value: String?): Code =
                entries.firstOrNull { it.stableId == value } ?: ENCODER_ERROR
        }
    }

    /** 缓存序列化：字段以 [FIELD_SEPARATOR] 分隔，原文里的分隔符先清掉。 */
    fun encode(): String = listOf(
        code.stableId,
        sanitize(detail),
        sanitize(diagnosticInfo),
        errorCode?.toString().orEmpty(),
        requested?.toString().orEmpty(),
        actual?.toString().orEmpty(),
        if (transient) "1" else "0",
        if (recoverable) "1" else "0"
    ).joinToString(FIELD_SEPARATOR)

    companion object {

        /** 与 [FableSolExportCapabilityMatrix] 的行/列分隔符互不重叠。 */
        const val FIELD_SEPARATOR = "\u0003"

        /** 逐候选失败明细内部的字段分隔符。 */
        const val RECORD_SEPARATOR = "\u0004"

        val NO_CANDIDATE = FableSolExportFailure(Code.NO_CANDIDATE)

        fun decode(text: String?): FableSolExportFailure? {
            if (text.isNullOrEmpty()) return null
            val fields = text.split(FIELD_SEPARATOR)
            return FableSolExportFailure(
                code = Code.fromStableId(fields.getOrNull(0)),
                detail = fields.getOrNull(1)?.takeIf { it.isNotEmpty() },
                diagnosticInfo = fields.getOrNull(2)?.takeIf { it.isNotEmpty() },
                errorCode = fields.getOrNull(3)?.toIntOrNull(),
                requested = fields.getOrNull(4)?.toIntOrNull(),
                actual = fields.getOrNull(5)?.toIntOrNull(),
                transient = fields.getOrNull(6) == "1",
                recoverable = fields.getOrNull(7) == "1"
            )
        }

        /** 分隔符不能出现在字段里：厂商原文是任意文本，进缓存之前先清掉这四个控制字符。 */
        internal fun sanitize(value: String?): String {
            var result = value.orEmpty()
            for (separator in RESERVED_SEPARATORS) {
                result = result.replace(separator, " ")
            }
            return result
        }

        private val RESERVED_SEPARATORS = listOf(
            "\u0001", "\u0002", FIELD_SEPARATOR, RECORD_SEPARATOR
        )
    }
}

/**
 * 一条逐候选失败明细：哪种格式、哪个档位、什么原因。
 *
 * 格式与档位都用**稳定标识**，展示时才本地化——同一份缓存在切换系统语言后仍然可读。
 */
internal data class FableSolExportCandidateFailure(
    /** 格式稳定标识，SDR 用 [FableSolExportHdrFormat.SDR_LABEL]。 */
    val formatLabel: String,
    /** 稳定档位名，例如 “HEVC Main10 SDR”；结构上没有候选时为 null。 */
    val tierLabel: String?,
    val frameRate: Int,
    val failure: FableSolExportFailure
) {

    fun encode(): String = listOf(
        FableSolExportFailure.sanitize(formatLabel),
        FableSolExportFailure.sanitize(tierLabel),
        frameRate.toString(),
        failure.encode()
    ).joinToString(FableSolExportFailure.RECORD_SEPARATOR)

    companion object {

        fun decode(text: String?): FableSolExportCandidateFailure? {
            if (text.isNullOrEmpty()) return null
            val fields = text.split(FableSolExportFailure.RECORD_SEPARATOR)
            if (fields.size < 4) return null
            val failure = FableSolExportFailure.decode(fields[3]) ?: return null
            return FableSolExportCandidateFailure(
                formatLabel = fields[0],
                tierLabel = fields[1].takeIf { it.isNotEmpty() },
                frameRate = fields[2].toIntOrNull() ?: 0,
                failure = failure
            )
        }
    }
}
