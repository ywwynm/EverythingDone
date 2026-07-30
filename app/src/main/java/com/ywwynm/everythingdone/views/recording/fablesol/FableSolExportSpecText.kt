package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import com.ywwynm.everythingdone.R
import java.util.Locale

/**
 * 完成态要补的那一行色彩规格。
 *
 * 单独放一处是因为**完成对话框与通知必须给出同一个说法**——两边各写一遍，迟早会漂移成
 * 两个版本。
 *
 * 三个数只在真正生效时才出现：漫反射白与峰值只对 PQ 系格式成立（HLG 系是相对亮度，没有
 * 绝对锚点），高光起点只对 HDR10+ 与 HDR Vivid 的应用创作曲线成立。不生效还写出来，
 * 等于告诉用户一个不影响产物的数。
 */
internal object FableSolExportSpecText {

    /**
     * 公开输出规格的统一写法。
     *
     * 六项字段必须同时出现：输出格式、编码器族、位深、软硬件实现、帧率与编码模式。失败说明、确认
     * 对话框和通知共用本方法，避免任何界面只显示其中一部分。
     */
    fun publicSpecification(
        context: Context,
        spec: FableSolExportPublicSpec
    ): String {
        val format = spec.format?.displayName(context)
            ?: FableSolExportHdrFormat.SDR_LABEL
        val bitDepth = context.getString(
            if (spec.tenBit) {
                R.string.fablesol_export_bit_depth_ten
            } else {
                R.string.fablesol_export_bit_depth_eight
            }
        )
        val formatAndCodec = specification(
            context = context,
            formatLabel = format,
            codecLabel = "${spec.family.stableLabel} $bitDepth",
            softwareCodec = spec.softwareOnly
        )
        return context.getString(
            R.string.fablesol_export_public_specification,
            formatAndCodec,
            spec.frameRate,
            context.getString(
                if (spec.rateControl == FableSolExportRateControl.CONSTANT_QUALITY) {
                    R.string.fablesol_export_rate_control_cq
                } else {
                    R.string.fablesol_export_rate_control_vbr
                }
            )
        )
    }

    /**
     * 失败原因包含完整失败规格，不能退化为“该规格”或底层异常原文。
     */
    fun retryReason(
        context: Context,
        reason: FableSolExportRetryReason,
        failedSpec: FableSolExportPublicSpec
    ): String {
        val specification = publicSpecification(context, failedSpec)
        return context.getString(
            when (reason) {
                FableSolExportRetryReason.ENCODER_INITIALIZATION ->
                    R.string.fablesol_export_retry_reason_encoder_initialization
                FableSolExportRetryReason.ENCODING_INTERRUPTED ->
                    R.string.fablesol_export_retry_reason_encoding_interrupted
                FableSolExportRetryReason.HDR_SCENE_ANALYSIS ->
                    R.string.fablesol_export_retry_reason_hdr_scene_analysis
                FableSolExportRetryReason.HDR_RENDER_PATH ->
                    R.string.fablesol_export_retry_reason_hdr_render_path
                FableSolExportRetryReason.ENCODING_PATH ->
                    R.string.fablesol_export_retry_reason_encoding_path
                FableSolExportRetryReason.REFERENCE_PEAK_INFEASIBLE ->
                    R.string.fablesol_export_retry_reason_reference_peak_infeasible
            },
            specification
        )
    }

    /** 等待用户确认时，对话框与通知共用的完整说明。 */
    fun retryConfirmation(
        context: Context,
        notice: FableSolExportRetryNotice
    ): String = context.getString(
        R.string.fablesol_export_retry_confirmation,
        publicSpecification(context, notice.failedSpec),
        retryReason(context, notice.reason, notice.failedSpec),
        publicSpecification(context, notice.currentSpec),
        notice.attemptedSpecCount
    ).appendDetail(notice.detail)

    /**
     * 编码继续后保留的最近一次调整说明；同规格内部重试与经确认的公开规格调整分别表述。
     */
    fun retrySummary(
        context: Context,
        notice: FableSolExportRetryNotice
    ): String = context.getString(
        if (notice.sameSpec) {
            R.string.fablesol_export_retry_summary_same_spec
        } else {
            R.string.fablesol_export_retry_summary_changed_spec
        },
        publicSpecification(context, notice.failedSpec),
        retryReason(context, notice.reason, notice.failedSpec),
        publicSpecification(context, notice.currentSpec),
        notice.attemptedSpecCount
    )

    /** 将最近一次重试说明附加到准备、进度或完成信息后。 */
    fun appendRetrySummary(
        context: Context,
        text: String,
        notice: FableSolExportRetryNotice?
    ): String = if (notice == null) {
        text
    } else {
        "$text\n\n${retrySummary(context, notice)}"
    }

    /** 所有可行公开规格均失败后的结构化终态说明。 */
    fun terminalFailure(
        context: Context,
        failedSpec: FableSolExportPublicSpec,
        reason: FableSolExportRetryReason,
        attemptedSpecCount: Int,
        /** 已本地化的补充说明（可行数值、可操作建议等，D115）；null 则不追加。 */
        detail: String? = null
    ): String = context.getString(
        R.string.fablesol_export_retry_terminal_failure,
        publicSpecification(context, failedSpec),
        retryReason(context, reason, failedSpec),
        attemptedSpecCount
    ).appendDetail(detail)

    /** 分类行之后追加已本地化的补充说明；它是资源字符串，不是底层异常原文（D178、D179）。 */
    private fun String.appendDetail(detail: String?): String =
        if (detail.isNullOrEmpty()) this else this + "\n" + detail

    /**
     * 完成态“规格”那一栏：输出格式加实际使用的编码器。
     *
     * 编码器必须写出来。降级阶梯会在格式、帧率与编码器三条轴上依次退让，退到哪里此前完全
     * 看不出来——三星 Z Fold4 上一次 HDR 导出实际落在软件 AV1 的 60fps 上，而完成提示只说
     * 了"HDR10，60 fps"（2026-07-27）。软件编码耗时与硬件差一到两个数量级，更要标出来。
     *
     * @param codecLabel 编码器族的固定标识，例如 “HEVC”“AV1”“H.264”。
     */
    fun specification(
        context: Context,
        formatLabel: String,
        codecLabel: String,
        softwareCodec: Boolean
    ): String {
        // 软硬件**两种都要写出来**。只在软件时加后缀，看到没有后缀的人无从判断那是"硬件"
        // 还是"这一项没做"（用户 2026-07-28 指出）。
        val codec = codecLabel + context.getString(
            if (softwareCodec) {
                R.string.fablesol_export_codec_software_suffix
            } else {
                R.string.fablesol_export_codec_hardware_suffix
            }
        )
        return context.getString(
            R.string.fablesol_export_spec_format_codec, formatLabel, codec
        )
    }

    /**
     * @param whiteNits 漫反射白；≤0 表示这次导出不是 PQ 系，整行不出现。
     * @param peakNits 峰值 = 漫反射白 × HDR 强度。
     * @param highlightStartPercent 高光起点百分位；≤0 表示不使用应用创作曲线，该项不出现。
     * @param hdr10PlusIdentity HDR10+ 的场景曲线是否退化成全片恒等（场景源峰值未超过参考
     *   显示峰值，D177）；null 表示这次不是 HDR10+。恒等时高光起点**没有生效**，因此那一项不
     *   显示，改为如实说明画面与 HDR10 一致——不得把它写成画质提升。
     * @return 可直接拼在完成文案后面的一段（含换行）；不适用时为空串。
     */
    fun detail(
        context: Context,
        whiteNits: Double,
        peakNits: Double,
        highlightStartPercent: Int,
        hdr10PlusIdentity: Boolean? = null,
        /** 全片静态亮度统计；null 表示不是 PQ 系，那一行不出现。 */
        luminance: FableSolExportLuminanceStats? = null,
        /**
         * HLG 系产物**实际**使用的信号范围；null 表示不是 HLG 系。
         *
         * 读的必须是产物而不是用户申请的选项：`自动增强` 在回环验证未通过时产出的是名义范围，
         * 把申请值写进完成信息就等于报了一个没发生的画质路径（D136）。
         */
        hlgRange: FableSolExportHlgRange? = null,
        /** 本次实际下发的编码策略；null 表示调用方不展示这一行。 */
        encoding: Encoding? = null,
        /** 高光起点百分位查询所得的膝点亮度（尼特）；0 表示不适用（D115）。 */
        hdr10PlusRequestedKneeNits: Double = 0.0,
        /** 实际采用的膝点亮度（尼特）；整数尼特与请求值不同才显示调整说明（D115）。 */
        hdr10PlusKneeNits: Double = 0.0,
        /** FBP 因代理帧缺失写了规范零值（未计算，D109）；完成信息必须说明。 */
        hdr10PlusFbpUnavailable: Boolean = false,
        /** 仅 HDR10+：携带 SEI 的视频样本数与总数；0 < N < M 时如实说明（D91 修订）。 */
        hdr10PlusSeiSamples: Long = 0L,
        hdr10PlusSeiTotal: Long = 0L,
        /** SDR 语义降级的本地化说明（D77、D78）；null 表示没有发生降级。 */
        sdrFallbackNotice: String? = null,
        /**
         * 正式产物的诊断性解析发现的容器静态元数据冲突摘要（D166）；null 表示一致、
         * 未携带或不是 PQ 系，那一行不出现。
         */
        staticMetadataConflict: String? = null
    ): String {
        if (whiteNits <= 0.0) {
            return hlgRangeLine(context, hlgRange) + encodingLine(context, encoding) +
                sdrFallbackNotice.orEmpty() +
                staticMetadataLine(context, staticMetadataConflict)
        }
        val builder = StringBuilder(
            context.getString(
                R.string.fablesol_export_detail_hdr,
                String.format(Locale.US, "%.0f", whiteNits),
                String.format(Locale.US, "%.0f", peakNits)
            )
        )
        // 场景曲线恒等时高光起点不产生任何压缩，写出来就是给了一个不影响产物的数（D177）。
        if (highlightStartPercent > 0 && hdr10PlusIdentity != true) {
            builder.append(
                context.getString(
                    R.string.fablesol_export_detail_highlight,
                    highlightStartPercent
                )
            )
        }
        // 膝点被可行域调整（下移到最高可行值，或被最低有效膝点抬起）时，如实给出请求值与
        // 实际采用值，不把调整后的值伪装成原始百分位结果（D115）。恒等场景没有压缩，不适用；
        // 整数尼特相同视为未调整，不显示。
        if (hdr10PlusIdentity != true &&
            hdr10PlusRequestedKneeNits > 0.0 && hdr10PlusKneeNits > 0.0
        ) {
            val requested = Math.round(hdr10PlusRequestedKneeNits).toInt()
            val actual = Math.round(hdr10PlusKneeNits).toInt()
            if (requested != actual) {
                builder.append(
                    context.getString(
                        R.string.fablesol_export_detail_knee_adjusted, requested, actual
                    )
                )
            }
        }
        // FBP 的规范零值严格表示"未计算"，不表示画面没有亮像素；成功任务不因此改报失败，
        // 但必须说明（D109）。
        if (hdr10PlusFbpUnavailable) {
            builder.append(
                context.getString(R.string.fablesol_export_detail_fbp_unavailable)
            )
        }
        // SEI 部分覆盖（D91 第 4 条修订）：输入侧每帧都注入，0 < N < M 说明编码器丢了部分
        // 帧的 SEI。发布门禁只要求确实携带，这里如实说明，不改报失败。
        if (hdr10PlusSeiTotal > 0L && hdr10PlusSeiSamples in 1L until hdr10PlusSeiTotal) {
            builder.append(
                context.getString(
                    R.string.fablesol_export_detail_hdr10plus_sei_partial,
                    hdr10PlusSeiSamples,
                    hdr10PlusSeiTotal
                )
            )
        }
        if (hdr10PlusIdentity == true) {
            builder.append(
                context.getString(R.string.fablesol_export_detail_hdr10plus_identity)
            )
        }
        // 实测与理论/未知必须分得开（D90）：两者都是合法产物，但只有一种是真的量过的。
        luminance?.let {
            val maxContent = it.maxContentLightLevel(whiteNits)
            builder.append(
                if (it.measured) {
                    context.getString(
                        R.string.fablesol_export_detail_light_level_measured,
                        maxContent,
                        it.maxFrameAverageLightLevel(whiteNits)
                    )
                } else {
                    context.getString(
                        R.string.fablesol_export_detail_light_level_theoretical,
                        maxContent
                    )
                }
            )
        }
        builder.append(hlgRangeLine(context, hlgRange))
        builder.append(encodingLine(context, encoding))
        sdrFallbackNotice?.let { builder.append(it) }
        builder.append(staticMetadataLine(context, staticMetadataConflict))
        return builder.toString()
    }

    /**
     * 容器静态元数据冲突的补充行（D166）。只在冲突时出现——"核对一致"没有信息量，
     * 完成 Dialog 的空间留给有用的内容。冲突也不改变成功终态：码流 SEI 携带的仍是
     * 应用生成的正确值，这一行是提醒，不是错误报告。
     */
    private fun staticMetadataLine(context: Context, conflict: String?): String =
        if (conflict == null) {
            ""
        } else {
            context.getString(
                R.string.fablesol_export_detail_static_metadata_conflict, conflict
            )
        }

    /**
     * 完成信息里的编码策略（D145、D148～D151）。
     *
     * 全部是"本次真的向编码器申请了什么"，不是用户开关的原样回声，也不宣称厂商必然启用了
     * 某个内部工具——Android 的这几个键都只是请求。
     */
    data class Encoding(
        val rateControl: FableSolExportRateControlForm,
        val bFrames: Boolean,
        val highComplexity: Boolean,
        val qpGuard: Boolean
    )

    private fun encodingLine(context: Context, encoding: Encoding?): String {
        if (encoding == null) return ""
        val mode = context.getString(
            when (encoding.rateControl) {
                // 两种 CQ 形态的用户可见语义完全相同，措辞不因兼容形态改变（D167）。
                FableSolExportRateControlForm.CONSTANT_QUALITY,
                FableSolExportRateControlForm.CONSTANT_QUALITY_WITH_BITRATE_HINT ->
                    R.string.fablesol_export_rate_control_cq
                FableSolExportRateControlForm.VARIABLE_BITRATE ->
                    R.string.fablesol_export_rate_control_vbr
                // CBR 只是内部后备，但必须如实显示落在了 CBR（D145）。
                FableSolExportRateControlForm.CONSTANT_BITRATE ->
                    R.string.fablesol_export_rate_control_cbr
            }
        )
        val tools = buildList {
            if (encoding.highComplexity) {
                add(context.getString(R.string.fablesol_export_tool_high_complexity))
            }
            if (encoding.qpGuard) {
                add(context.getString(R.string.fablesol_export_tool_qp_guard))
            }
            add(
                context.getString(
                    if (encoding.bFrames) {
                        R.string.fablesol_export_tool_b_frames
                    } else {
                        R.string.fablesol_export_tool_no_b_frames
                    }
                )
            )
        }
        return context.getString(
            R.string.fablesol_export_detail_encoding,
            mode,
            tools.joinToString(context.getString(R.string.fablesol_export_tool_separator))
        )
    }

    /** 「HLG 基层信号范围：扩展信号范围／名义范围」那一行；不是 HLG 系时为空串。 */
    private fun hlgRangeLine(context: Context, range: FableSolExportHlgRange?): String =
        when (range) {
            null -> ""
            FableSolExportHlgRange.EXTENDED -> context.getString(
                R.string.fablesol_export_detail_hlg_range,
                context.getString(R.string.fablesol_export_hlg_result_extended)
            )
            FableSolExportHlgRange.NOMINAL -> context.getString(
                R.string.fablesol_export_detail_hlg_range,
                context.getString(R.string.fablesol_export_hlg_result_nominal)
            )
        }

    /**
     * 导出准备阶段的状态文字（D138）。
     *
     * 不认识的阶段标识退回通用的"正在准备"：状态是跨进程传过来的稳定字符串，旧版服务遇到新
     * 阶段时应当继续显示得体，而不是把一个内部代号摆给用户看。
     */
    fun preparingStage(context: Context, stageId: String): String = context.getString(
        when (stageId) {
            FableSolVideoExportBus.STAGE_HLG_RANGE ->
                R.string.fablesol_export_preparing_hlg_range
            else -> R.string.fablesol_export_preparing
        }
    )
}
