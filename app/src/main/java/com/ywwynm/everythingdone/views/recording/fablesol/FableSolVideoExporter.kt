package com.ywwynm.everythingdone.views.recording.fablesol

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 一次 FableSol 可视化视频导出。
 *
 * 每一个编码候选都是完整事务：重新打开音频、创建 codec/muxer/EGL、初始化渲染器、编码到
 * EOS、成功执行 `MediaMuxer.stop()`，最后才发布文件。任一步失败都会清理半成品并从零尝试
 * 下一档，避免“configure 成功但首帧、输出格式或 muxer.addTrack 失败”直接终止整个导出。
 */
internal class FableSolVideoExporter(
    baseContext: Context,
    private val request: Request,
    private val listener: Listener
) {

    /**
     * 全流程只认这一个 Context。Service 传进来的是 Application Context——它的主题是平台
     * 默认的浅色主题、配置也读不到应用自己的夜间模式，直接用会导出一张与屏上不同外观的
     * 卡片（见 [FableSolExportAppearance]）。
     */
    private val context: Context = FableSolExportAppearance.themedContext(baseContext)

    internal data class Request(
        val audioPath: String,
        val sink: FableSolExportSink,
        val waterBackground: ThingBackground,
        val accentBackground: ThingBackground,
        val cardWidthDp: Double,
        /** 任务入队时冻结的用户意图；等待确认期间设置页变化不得改写本任务。 */
        val options: FableSolExportOptions,
        val hdrStrength: Float,
        /** 设置页初始解析或用户后续确认后，本次运行应处理的唯一公开规格。 */
        val targetSpec: FableSolExportPublicSpec? = null,
        val failedSpecs: Set<FableSolExportPublicSpec> = emptySet(),
        /** 本次运行开始前已经实际尝试过的公开规格数量。 */
        val attemptedSpecCount: Int = 0,
        /** 最近一次经用户确认的规格调整；成功与进度状态继续显示。 */
        val retryNotice: FableSolExportRetryNotice? = null
    )

    internal interface Listener {
        fun onProgress(framesDone: Int, framesTotal: Int, etaMs: Long)

        /**
         * 正式渲染开始前的一件准备工作（D138）；[stageId] 见 [FableSolVideoExportBus]。
         *
         * 默认空实现：准备阶段是提示，不是导出结论，不应该逼着每个调用方都实现它。
         */
        fun onPreparing(stageId: String) = Unit

        /**
         * 六项公开规格不变，但上一内部候选已经产生可见进度，需要从头重新处理。
         */
        fun onRetryingSameSpec(notice: FableSolExportRetryNotice) = Unit

        fun isCancelled(): Boolean
    }

    internal sealed class Result {
        data class Success(
            val sink: FableSolExportSink,
            val tierLabel: String,
            val hdr: Boolean,
            /** 当前 locale 的用户可见格式名，例如“HDR10+”“杜比视界 8.4”“SDR”。 */
            val formatLabel: String,
            /** 实际使用的编码器族，例如“HEVC”“AV1”“H.264”。 */
            val codecLabel: String,
            /** 实际使用的编码器实现是否为纯软件；完成态要标出来。 */
            val softwareCodec: Boolean,
            val frameRate: Int,
            val frames: Int,
            val elapsedMs: Long,
            /** 漫反射白（尼特）；0 表示这次不是 PQ 系，完成态不显示这一行。 */
            val pqWhiteNits: Double = 0.0,
            /** 峰值 = 漫反射白 × HDR 强度。 */
            val peakNits: Double = 0.0,
            /** 高光起点百分位；0 表示不是 HDR10+，该项不适用。 */
            val highlightStartPercent: Int = 0,
            /** 高光起点百分位查询所得的膝点亮度（尼特）；0 表示不适用（D115）。 */
            val hdr10PlusRequestedKneeNits: Double = 0.0,
            /** 实际采用的膝点亮度（尼特）；与请求值不同即发生了可行域调整（D115）。 */
            val hdr10PlusKneeNits: Double = 0.0,
            /** FBP 因代理帧缺失写了规范零值（未计算，D109）；完成信息必须说明。 */
            val hdr10PlusFbpUnavailable: Boolean = false,
            /** 仅 HDR10+ 有意义：携带 ST 2094-40 SEI 的视频样本数（D91 第 4 条修订）。 */
            val hdr10PlusSeiSamples: Long = 0L,
            /** 仅 HDR10+ 有意义：视频样本总数；0 < SEI 数 < 总数时完成信息如实说明。 */
            val hdr10PlusSeiTotal: Long = 0L,
            /**
             * SDR 语义降级的**本地化**说明（D77、D78）；null 表示本次没有发生降级。
             * 完成 Dialog 与通知在 detail 末尾追加，如实说明"动态统计不可用，实际采用
             * 稳定映射"或"FP16 不可用，实际采用原生 SDR"。
             */
            val sdrFallbackNotice: String? = null,
            /**
             * 正式产物封装成功后的诊断性解析在容器里发现的静态元数据冲突摘要（D166）；
             * null 表示一致、容器未携带（承载为码流 SEI）或本次不是 PQ 系。只进完成信息的
             * 补充说明与诊断，绝不影响导出结果本身（D142）。
             */
            val staticMetadataConflict: String? = null,
            /**
             * HDR10+ 的曲线是否全片恒等（场景源峰值未超过参考显示峰值，D177）；null 表示这次
             * 不是 HDR10+。恒等时动态层在画面上没有作用，完成信息必须如实说明。
             */
            val hdr10PlusIdentity: Boolean? = null,
            /**
             * 本次写入的全片静态亮度统计；null 表示这次不是 PQ 系，没有这组元数据。
             *
             * 完成态必须能区分"全片实测"与"理论上界/未知"（D90）：把回退值显示成实测结果，
             * 等于告诉用户一个我们并没有测过的数。
             */
            val luminance: FableSolExportLuminanceStats? = null,
            /**
             * HLG 系产物**实际**使用的信号范围；null 表示不是 HLG 系（D135、D136、D144）。
             *
             * 报告的是产物而不是用户申请的选项：`自动增强` 在验证未通过时产出的就是名义范围。
             */
            val hlgRange: FableSolExportHlgRange? = null,
            /** 本次实际下发的码控形态与编码工具（D145、D148～D151）。 */
            val encoding: FableSolExportSpecText.Encoding? = null,
            val attemptedSpecCount: Int = 1,
            val retryNotice: FableSolExportRetryNotice? = null
        ) : Result()

        object Cancelled : Result()
        data class OutOfSpace(val estimatedBytes: Long) : Result()
        data class NeedsConfirmation(
            val failedSpec: FableSolExportPublicSpec,
            val reason: FableSolExportRetryReason,
            val suggestedSpec: FableSolExportPublicSpec,
            /** 已经失败的公开规格，供确认后继续时排除。 */
            val failedSpecs: Set<FableSolExportPublicSpec>,
            val attemptedSpecCount: Int,
            /** 已本地化的补充说明；确认文案在分类行之后追加显示（D115）。 */
            val detail: String? = null
        ) : Result()

        data class Failure(
            /** 仅供无法结构化的终端错误使用；候选失败不直接向用户展示本字段。 */
            val message: String,
            val failedSpec: FableSolExportPublicSpec? = null,
            val reason: FableSolExportRetryReason? = null,
            val attemptedSpecCount: Int = 0,
            /** 已本地化的补充说明；终态失败文案在分类行之后追加显示（D115）。 */
            val detail: String? = null
        ) : Result()
    }

    private var currentAttemptHasVisibleProgress = false
    private var currentAttemptPhase = AttemptPhase.PREPARING
    /**
     * 本次尝试的纯 CQ 是否来自"无探测结论"的回退（D186）：只有它为真时，编码器初始化失败
     * 才允许同一候选改以 CQ+码率提示重试。缓存有结论时探测结论是唯一权威。
     */
    private var currentAttemptFormFallbackPureCq = false

    fun run(): Result {
        if (request.attemptedSpecCount == 0) {
            FableSolExportRuntimeDiagnostics.beginExport()
        } else {
            FableSolExportRuntimeDiagnostics.record(
                "继续导出尝试链；此前已尝试 ${request.attemptedSpecCount} 个公开规格。"
            )
        }
        val exportStartedAt = SystemClock.elapsedRealtime()
        return try {
            val capability = FableSolExportEgl.probe()
            val options = request.options
            val strength = request.hdrStrength
            // 显式 HDR 格式是严格请求（D106）：HDR 强度是**内容**参数，强度为 1.0 时产物
            // 仍应是用户点名的那种 HDR 容器，只是里面没有额外高光。自动档才允许"没有高光
            // 就不必上 HDR"这条捷径。FP16 扩展场景是所有 HDR 的硬前提，两者都要。
            val wantHdr = options.colorMode.requestsHdr &&
                capability.linearSceneSupported &&
                (options.colorMode.isExplicitHdr || strength > FableSolHdrPolicy.STRENGTH_OFF)
            val basePlan = FableSolExportSpec.plan(context, request.cardWidthDp)
            val requestedFrameRate = options.frameRate.coerceIn(
                FableSolExportOptions.FRAME_RATE_BASE,
                FableSolExportOptions.FRAME_RATE_HIGH
            )

            val metadata = readAudioMetadata()
            val durationSeconds = metadata.durationUs / 1_000_000.0
            val spaceResult = checkInitialSpace(
                options, requestedFrameRate, durationSeconds, basePlan
            )
            if (spaceResult != null) return spaceResult

            // 用户可以钉死某一种格式；「HDR 自动」按 AUTO_ORDER 的规格/画质能力顺序尝试。
            // 这里不查探测缓存：缓存可能过期，而降级阶梯本来就会把真正编不出来的档一个个
            // 淘汰掉。EGL 色彩空间的粗筛已经下沉到**候选**一级——它是 Surface 输入通路的
            // 要求，应用自有 P010 用不到它（D158）。
            val availableTransfers = capability.availableHdrTransfers()
            val hdrFormats = when {
                !wantHdr -> emptyList()
                options.colorMode.explicitFormat != null ->
                    listOfNotNull(options.colorMode.explicitFormat)
                else -> FableSolExportHdrFormat.AUTO_ORDER
            }
            val attempts = FableSolExportAttemptPlan.ordered(
                hdrFormats = hdrFormats,
                requestedFrameRate = requestedFrameRate,
                sdrBitDepth = options.sdrBitDepth,
                // 显式 HDR 格式失败即结束，不发布 SDR（D106）。
                allowSdrFallback = options.colorMode.allowsSdrResult
            )
            val groups = LinkedHashMap<
                FableSolExportPublicSpec,
                MutableList<CandidatePlan>
            >()
            for (attempt in attempts) {
                val candidates = FableSolExportTier.candidatesForMode(
                    format = attempt.format,
                    basePlan.canvasWidthPx,
                    basePlan.canvasHeightPx,
                    attempt.frameRate,
                    tenBit = attempt.tenBit,
                    preferConstantQuality = options.prefersConstantQuality,
                    // 用户钉死某个编码器族时只走那一族；自动档在同规格内先穷尽硬件实现，
                    // 硬件都不可用时才允许软件实现作为最后回退（D53 修订）。软件编码在本项目
                    // 接近两百万像素的画布上要慢一到两个数量级，信息栏已就此明确说明。
                    family = options.codec.family,
                    allowSoftware = true,
                    // 码率与 Level 在候选生成时就要解析（D147、D152）：自动值依赖对齐后的
                    // 实际宽高、帧率、族与位深，而 Level 的码率分量又依赖码率。
                    customBitrateMbps = options.bitrateMbps,
                    bFrames = options.bFramesEnabled,
                    complexFrameGuard = options.complexFrameGuardEnabled
                ).filter {
                    // Surface 输入才需要窗口色彩空间；P010 子候选不受这道筛影响。
                    !it.requiresEglColorSpace || availableTransfers.contains(it.transfer)
                }
                for (tier in candidates) {
                    val plan = basePlan.withCanvasSize(
                        tier.encodedWidthPx, tier.encodedHeightPx
                    )
                    val spec = FableSolExportPublicSpec(
                        format = attempt.format,
                        family = tier.family,
                        tenBit = !tier.eightBit,
                        softwareOnly = tier.softwareOnly,
                        frameRate = attempt.frameRate,
                        rateControl = options.rateControl
                    )
                    groups.getOrPut(spec, ::ArrayList).add(
                        CandidatePlan(attempt, tier, plan)
                    )
                }
            }

            val orderedSpecs = groups.keys.toList()
            val targetSpec = request.targetSpec?.takeIf {
                it.frameRate == requestedFrameRate
            } ?: FableSolExportRetryPolicy.nextSuggestion(
                ordered = orderedSpecs,
                failed = request.failedSpecs,
                strictFrameRate = requestedFrameRate
            )
            if (targetSpec == null) {
                // 这已经是正式本地化文案：同时放进 detail，服务端据此直达用户，
                // 而不是换成笼统的"内部错误"（D183"以正式本地化文案阻止导出"、D105）。
                val noSpec = context.getString(
                    R.string.fablesol_export_no_exact_specification,
                    requestedFrameRate
                )
                return Result.Failure(
                    message = noSpec,
                    attemptedSpecCount = request.attemptedSpecCount,
                    detail = noSpec
                )
            }
            val currentAttemptNumber = request.attemptedSpecCount + 1
            val group = groups[targetSpec]
            if (group == null) {
                FableSolExportRuntimeDiagnostics.record(
                    "公开规格 #$currentAttemptNumber 不在当前候选表中：" +
                        FableSolExportRuntimeDiagnostics.specification(targetSpec)
                )
                return resultAfterPublicSpecFailure(
                    failedSpec = targetSpec,
                    reason = FableSolExportRetryReason.ENCODER_INITIALIZATION,
                    orderedSpecs = orderedSpecs,
                    attemptedSpecCount = currentAttemptNumber
                )
            }
            FableSolExportRuntimeDiagnostics.record(
                "开始公开规格 #$currentAttemptNumber：" +
                    FableSolExportRuntimeDiagnostics.specification(targetSpec) +
                    "；内部候选 ${group.size} 个。"
            )

            var lastReason = FableSolExportRetryReason.ENCODING_PATH
            // 已本地化的补充说明与"连带判失败"的规格集合；仅规格无关的配置级失败使用
            // （当前只有参考显示峰值不可行，D115）。
            var lastDetail: String? = null
            var alsoFailedSpecs: Collection<FableSolExportPublicSpec> = emptyList()
            candidates@ for ((candidateIndex, candidate) in group.withIndex()) {
                if (listener.isCancelled()) return Result.Cancelled
                val tier = candidate.tier
                val attempt = candidate.attempt
                FableSolExportRuntimeDiagnostics.record(
                    "内部候选 ${candidateIndex + 1}/${group.size}：codecName=${tier.codecName}；" +
                        "input=${tier.inputPath.stableId}；profile=${tier.profile}；level=${tier.level}。"
                )
                currentAttemptHasVisibleProgress = false
                currentAttemptPhase = AttemptPhase.PREPARING
                var render = FableSolExportSdrRender.of(
                    colorMode = options.colorMode,
                    mapping = options.sdrMapping,
                    hdrResult = tier.hdr
                )
                // 本候选内发生过的 SDR 语义降级的**本地化**说明（D77、D78）；完成信息在
                // detail 末尾追加。两级降级连续发生时保留最终那一句。
                var sdrFallbackNotice: String? = null
                // 复杂度按探测结论同源下发（D149）；B 帧在初始化被拒后同一候选以 0 B 帧
                // 重试一次（D148），属于同规格内部重试（D179）。
                val applyHighComplexity = resolveHighComplexity(
                    options, tier, attempt.frameRate
                )
                var applyBFrames = true
                // D186 运行时形态阶梯：无探测结论回退的纯 CQ 在初始化失败后，同一候选改以
                // CQ+码率提示重试一次。非 null 即已退让。
                var formOverride: FableSolExportRateControlForm? = null
                while (true) {
                    // SDR 成片语义有两条运行时降级（D77 动态统计失败、D78 FP16 不可用），
                    // 两条都要求"丢弃该次尝试并**从第 1 帧**改用较低语义重新导出"，而不是
                    // 换编码档位。因此它们在同一个 tier 上重来，不消耗降级阶梯。
                    try {
                        val result = runAttempt(
                            exportStartedAt = exportStartedAt,
                            capability = capability,
                            options = options,
                            strength = strength,
                            durationSeconds = durationSeconds,
                            frameRate = attempt.frameRate,
                            tier = tier,
                            plan = candidate.plan,
                            sdrRender = render,
                            applyHighComplexity = applyHighComplexity,
                            applyBFrames = applyBFrames,
                            rateControlFormOverride = formOverride
                        )
                        return if (result is Result.Success) {
                            FableSolExportRuntimeDiagnostics.record(
                                "公开规格 #$currentAttemptNumber 导出成功：" +
                                    FableSolExportRuntimeDiagnostics.specification(targetSpec)
                            )
                            result.copy(
                                attemptedSpecCount = currentAttemptNumber,
                                retryNotice = request.retryNotice,
                                sdrFallbackNotice = sdrFallbackNotice
                            )
                        } else {
                            result
                        }
                    } catch (degrade: SdrDegradeRestart) {
                        lastReason = FableSolExportRetryReason.HDR_RENDER_PATH
                        FableSolExportRuntimeDiagnostics.record(
                            "同一公开规格内重启 SDR 渲染语义；" +
                                "降级到=${degrade.fallback.stableId}；" +
                                "原因=${degrade.detail ?: "未提供"}。"
                        )
                        // 完成信息的降级说明（D77"导出完成信息……必须显示'动态统计不可用，
                        // 实际采用稳定映射'"、D78 同理）；内部英文原因只进上面的诊断。
                        sdrFallbackNotice = context.getString(
                            if (degrade.fallback == FableSolExportSdrRender.NATIVE) {
                                R.string.fablesol_export_result_sdr_native_fallback
                            } else {
                                R.string.fablesol_export_result_sdr_dynamic_fallback
                            }
                        )
                        if (currentAttemptHasVisibleProgress) {
                            listener.onRetryingSameSpec(
                                sameSpecNotice(
                                    targetSpec, lastReason, currentAttemptNumber
                                )
                            )
                        }
                        currentAttemptHasVisibleProgress = false
                        currentAttemptPhase = AttemptPhase.PREPARING
                        render = degrade.fallback
                        continue
                    } catch (outOfSpace: OutOfSpaceFailure) {
                        FableSolExportRuntimeDiagnostics.record(
                            "存储空间不足；预计需要 ${outOfSpace.requiredBytes} 字节。"
                        )
                        return Result.OutOfSpace(outOfSpace.requiredBytes)
                    } catch (infeasible: ReferencePeakInfeasible) {
                        // 参考显示峰值低于场景需求（S > 10T，D115）。该结论只取决于场景 V8
                        // 与用户参数，与编码器族、软硬件和输入通路无关：同格式的其余公开
                        // 规格必然得到同一结果，一并判定失败，不逐个确认、不重复整段预分析。
                        lastReason = FableSolExportRetryReason.REFERENCE_PEAK_INFEASIBLE
                        lastDetail = infeasible.localizedDetail
                        alsoFailedSpecs = orderedSpecs.filter {
                            it.format == targetSpec.format
                        }
                        FableSolExportRuntimeDiagnostics.record(
                            "参考显示峰值不可行；同格式公开规格一并判定失败" +
                                "（${alsoFailedSpecs.size} 个）：${infeasible.localizedDetail}"
                        )
                        break@candidates
                    } catch (publish: PublishFailure) {
                        FableSolExportRuntimeDiagnostics.record(
                            "产物发布失败：" +
                                FableSolExportRuntimeDiagnostics.failure(publish)
                        )
                        // 发布失败与 codec 档位无关，重新渲染整段只会重复同一个错误。
                        return Result.Failure(
                            publish.message ?: "Failed to publish the exported video",
                            failedSpec = targetSpec,
                            reason = FableSolExportRetryReason.ENCODING_PATH,
                            attemptedSpecCount = currentAttemptNumber
                        )
                    } catch (error: Exception) {
                        // B 帧路径建不起来时以 0 个 B 帧完成原格式导出（D148）：同一候选
                        // 重试一次，属于零进度的同规格内部重试（D179），不消耗候选阶梯。
                        if (applyBFrames && options.bFramesEnabled &&
                            tier.supportsBFrames && Build.VERSION.SDK_INT >= 29 &&
                            currentAttemptPhase == AttemptPhase.ENCODER_INITIALIZATION
                        ) {
                            applyBFrames = false
                            FableSolExportRuntimeDiagnostics.record(
                                "编码器初始化在申请 B 帧时失败；同一候选改以 0 个 B 帧重试。" +
                                    FableSolExportRuntimeDiagnostics.failure(error)
                            )
                            currentAttemptHasVisibleProgress = false
                            currentAttemptPhase = AttemptPhase.PREPARING
                            continue
                        }
                        // 无探测结论回退到纯 CQ 的初始化失败：同一候选改以 CQ+码率提示重试
                        // 一次（D186）。两种形态的用户可见语义相同（D167），不构成公开规格
                        // 变化；零进度同规格内部重试（D179）。成功后不回写矩阵——矩阵只归
                        // 探测写，下次进设置页重探归位。
                        if (formOverride == null && currentAttemptFormFallbackPureCq &&
                            currentAttemptPhase == AttemptPhase.ENCODER_INITIALIZATION
                        ) {
                            formOverride = FableSolExportRateControlForm
                                .CONSTANT_QUALITY_WITH_BITRATE_HINT
                            FableSolExportRuntimeDiagnostics.record(
                                "编码器初始化在纯 CQ（无探测结论回退）下失败；" +
                                    "同一候选改以 CQ+码率提示重试。" +
                                    FableSolExportRuntimeDiagnostics.failure(error)
                            )
                            currentAttemptHasVisibleProgress = false
                            currentAttemptPhase = AttemptPhase.PREPARING
                            continue
                        }
                        lastReason = classifyFailure()
                        Log.w(
                            TAG,
                            "Candidate failed: spec=$targetSpec, " +
                                "codec=${tier.codecName}, input=${tier.inputPath.stableId}",
                            error
                        )
                        FableSolExportRuntimeDiagnostics.record(
                            "内部候选失败；阶段=$currentAttemptPhase；" +
                                "分类=$lastReason；codecName=${tier.codecName}；" +
                                "input=${tier.inputPath.stableId}；" +
                                FableSolExportRuntimeDiagnostics.failure(error)
                        )
                        if (
                            currentAttemptHasVisibleProgress &&
                            candidateIndex < group.lastIndex
                        ) {
                            listener.onRetryingSameSpec(
                                sameSpecNotice(
                                    targetSpec, lastReason, currentAttemptNumber
                                )
                            )
                        }
                        break
                    }
                }
            }
            resultAfterPublicSpecFailure(
                failedSpec = targetSpec,
                reason = lastReason,
                orderedSpecs = orderedSpecs,
                attemptedSpecCount = currentAttemptNumber,
                additionallyFailed = alsoFailedSpecs,
                detail = lastDetail
            )
        } catch (error: Throwable) {
            FableSolExportRuntimeDiagnostics.record(
                "导出器产生未结构化异常：" +
                    FableSolExportRuntimeDiagnostics.failure(error)
            )
            Result.Failure(
                FableSolExportHdrFormat.localizeStableLabels(
                    context,
                    error.message ?: error.javaClass.simpleName
                )
            )
        }
    }

    private fun sameSpecNotice(
        spec: FableSolExportPublicSpec,
        reason: FableSolExportRetryReason,
        attemptedSpecCount: Int
    ) = FableSolExportRetryNotice(
        failedSpec = spec,
        reason = reason,
        currentSpec = spec,
        attemptedSpecCount = attemptedSpecCount,
        sameSpec = true
    )

    private fun resultAfterPublicSpecFailure(
        failedSpec: FableSolExportPublicSpec,
        reason: FableSolExportRetryReason,
        orderedSpecs: List<FableSolExportPublicSpec>,
        attemptedSpecCount: Int,
        /** 与失败规格一同判定失败的其余规格；仅规格无关的配置级失败使用（D115）。 */
        additionallyFailed: Collection<FableSolExportPublicSpec> = emptyList(),
        /** 已本地化的补充说明；确认与终态文案在分类行之后追加显示。 */
        detail: String? = null
    ): Result {
        val failed = request.failedSpecs + failedSpec + additionallyFailed
        val suggested = FableSolExportRetryPolicy.nextSuggestion(
            ordered = orderedSpecs,
            failed = failed,
            strictFrameRate = failedSpec.frameRate
        )
        FableSolExportRuntimeDiagnostics.record(
            "公开规格失败：${FableSolExportRuntimeDiagnostics.specification(failedSpec)}；" +
                "分类=$reason；" +
                if (suggested != null) {
                    "建议=${FableSolExportRuntimeDiagnostics.specification(suggested)}。"
                } else {
                    "没有其它符合严格约束的公开规格。"
                }
        )
        return if (suggested != null) {
            Result.NeedsConfirmation(
                failedSpec = failedSpec,
                reason = reason,
                suggestedSpec = suggested,
                failedSpecs = failed,
                attemptedSpecCount = attemptedSpecCount,
                detail = detail
            )
        } else {
            Result.Failure(
                message = "All candidates for the requested output specification failed",
                failedSpec = failedSpec,
                reason = reason,
                attemptedSpecCount = attemptedSpecCount,
                detail = detail
            )
        }
    }

    private fun classifyFailure(): FableSolExportRetryReason = when (currentAttemptPhase) {
        AttemptPhase.ENCODER_INITIALIZATION ->
            FableSolExportRetryReason.ENCODER_INITIALIZATION
        AttemptPhase.ENCODING ->
            FableSolExportRetryReason.ENCODING_INTERRUPTED
        AttemptPhase.HDR_SCENE_ANALYSIS ->
            FableSolExportRetryReason.HDR_SCENE_ANALYSIS
        AttemptPhase.HDR_RENDER_PATH ->
            FableSolExportRetryReason.HDR_RENDER_PATH
        AttemptPhase.PREPARING ->
            FableSolExportRetryReason.ENCODING_PATH
    }

    private fun runAttempt(
        exportStartedAt: Long,
        capability: FableSolExportEgl.Capability,
        options: FableSolExportOptions,
        strength: Float,
        durationSeconds: Double,
        frameRate: Int,
        tier: FableSolExportTier,
        plan: FableSolExportPlan,
        /** 本次尝试的 SDR 成片语义；HDR 候选为 null。 */
        sdrRender: FableSolExportSdrRender?,
        /** 是否下发 `KEY_COMPLEXITY = upper`；按探测结论同源解析（D149）。 */
        applyHighComplexity: Boolean = true,
        /** 是否下发 `KEY_MAX_B_FRAMES = 1`；初始化被拒后的重试为假（D148）。 */
        applyBFrames: Boolean = true,
        /**
         * D186 运行时阶梯的形态指定：非 null 表示上一次尝试以"无探测结论回退的纯 CQ"在
         * 初始化失败，本次改用该形态（CQ+码率提示）。null 走正常解析。
         */
        rateControlFormOverride: FableSolExportRateControlForm? = null
    ): Result {
        val attemptStartedAt = SystemClock.elapsedRealtime()
        var audio: FableSolExportAudioSource? = null
        var encoder: FableSolExportEncoder? = null
        var egl: FableSolExportEgl? = null
        var renderer: FableSolGlRenderer? = null
        var presenter: FableSolExportPresenter? = null
        var clock: FableSolExportClock? = null
        var bridge: FableSolExportP010Bridge? = null
        // 当前连续动画只有一个场景：预分析结束后只求解一次，并把同一份载荷写入每一帧。
        var hdrCurve: FableSolExportHdr10PlusCurve? = null
        var hdr10PlusShape: FableSolExportHdr10PlusCurve.Shape? = null
        var hdr10PlusFbpUnavailable = false
        var hdr10PlusPayload: ByteBuffer? = null
        var published = false
        try {
            audio = FableSolExportAudioSource(request.audioPath)
            val sampleRate = audio.sampleRate
            val totalFrames = if (durationSeconds > 0.0) {
                ceil(durationSeconds * frameRate).toInt().coerceAtLeast(1)
            } else {
                Int.MAX_VALUE
            }
            emitProgress(0, totalFrames, -1L)

            // PQ 是绝对亮度，漫反射白钉在哪儿由用户定；HLG 是相对亮度，用不到这个数。
            val whiteNits = if (tier.transfer == FableSolExportTransfer.PQ) {
                options.pqWhiteNits.toDouble()
            } else {
                FableSolExportTransfer.SDR_WHITE_NITS
            }
            val peakNits = strength * whiteNits
            // 码控形态读**已经得出**的探测结论（D167）：纯 CQ 与 CQ+码率提示是同一模式的两种
            // 形态，探一种、用另一种等于那次探测什么也没证明。取不到就从纯 CQ 重新开始阶梯，
            // 初始化失败时由候选循环按 D186 以 CQ+码率提示同候选重试一次。
            // 必须先于 HLG 计划解析：super-white 回环验证的编码器要用同一份形态（D139）。
            val cachedForm = cachedRateControlForm(options, tier, frameRate)
            val rateControlForm = rateControlFormOverride
                ?: cachedForm
                ?: FableSolExportRateControlForm.resolve(options, tier)
            currentAttemptFormFallbackPureCq = rateControlFormOverride == null &&
                cachedForm == null &&
                rateControlForm == FableSolExportRateControlForm.CONSTANT_QUALITY
            // HLG 系（含杜比视界 8.4 的 HLG 基层）的信号范围必须在**第一帧渲染之前**定下来：
            // 它同时决定肩部容量与 P010 的量化边界，渲染到一半再换等于把已编好的帧作废
            // （D138）。验证不通过只是退到名义范围，不是导出失败（D135）。
            currentAttemptPhase = AttemptPhase.HDR_RENDER_PATH
            var hlgPlan = resolveHlgPlan(
                options = options,
                tier = tier,
                frameRate = frameRate,
                strength = strength,
                form = rateControlForm
            )
            if (listener.isCancelled()) return Result.Cancelled
            // **实际落点只解析一次。** 文件名、进度、完成态与诊断此后全部读这一份，不再
            // 各自推一遍——那样任何一条轴的降级都可能只被其中一两处察觉。唯一的后续修正是
            // FP16 呈现中间面落空时 HLG 信号范围降为名义（见 bridge 构造处），修正走 copy，
            // 所有读者仍然只有这一份。
            var resolved = FableSolExportResolvedCandidate.of(
                options = options,
                tier = tier,
                frameRate = frameRate,
                widthPx = plan.canvasWidthPx,
                heightPx = plan.canvasHeightPx,
                pqWhiteNits = whiteNits,
                peakNits = peakNits,
                sdrRender = sdrRender,
                hlgRange = hlgPlan?.range,
                rateControlForm = rateControlForm,
                // 完成信息显示的是**本次真的申请了没有**（D148、D149）。
                applyBFrames = applyBFrames,
                applyHighComplexity = applyHighComplexity
            )
            // 旧版全局 CQ 原值在这里一次性归属到实际解析出的候选签名（延迟绑定），之后不再
            // 扩散到其它编码器或输入路径。
            FableSolTuning.bindLegacyQualityValue(context, resolved.qualitySignature)

            val hdr10Plus = tier.hdrFormat?.usesByteBufferInput == true
            var hdr10PlusSceneStats: FableSolHdr10PlusStats? = null

            // **静态元数据要的是实测统计，不是参数。** HDR10/HDR10+ 在配置编码器之前先跑一次
            // 全片亮度预分析（D85）；成功结果按完整渲染指纹缓存，同一份录音与同一套渲染条件
            // 不必重跑（D92）。HDR10+ 还要在这次预分析里累计整个连续场景的精确统计（D177）；
            // 这组大直方图不进轻量缓存，因此 HDR10+ 即使命中静态亮度缓存也仍需跑预分析。
            // 杜比视界 8.4 是 HLG 基层，不走 PQ 静态元数据这条路。
            var luminance = FableSolExportLuminanceStats.theoretical(strength.toDouble())
            var progressTotal = totalFrames
            if (tier.hdrFormat?.writesStaticMetadata == true) {
                currentAttemptPhase = AttemptPhase.HDR_SCENE_ANALYSIS
                val fingerprint = FableSolExportLuminanceCache.fingerprint(
                    context = context,
                    audioPath = request.audioPath,
                    strength = strength,
                    widthPx = plan.canvasWidthPx,
                    heightPx = plan.canvasHeightPx,
                    frameRate = frameRate,
                    tiltEnabled = options.tiltEnabled,
                    darkAppearance = FableSolExportAppearance.isDark(context),
                    backdropColor = plan.backdropColor,
                    rimColor = plan.rimColor
                )
                val cached = if (hdr10Plus) {
                    null
                } else {
                    FableSolExportLuminanceCache.read(context, fingerprint)
                }
                if (cached != null) {
                    luminance = cached
                } else {
                    // 预分析与正式编码各跑一遍全片，进度条按两段合计算，免得从 0 走到 100 两次。
                    progressTotal = totalFrames * 2
                    val measured = analyseLuminance(
                        capability = capability,
                        options = options,
                        strength = strength,
                        totalFrames = totalFrames,
                        frameRate = frameRate,
                        plan = plan,
                        progressTotal = progressTotal,
                        collectHdr10PlusScene = hdr10Plus
                    )
                    if (listener.isCancelled()) return Result.Cancelled
                    if (measured != null) {
                        luminance = measured.luminance
                        hdr10PlusSceneStats = measured.hdr10PlusStats
                        // 只写完整成功的实测结果；取消、部分结果与 D90 回退都不进缓存。
                        FableSolExportLuminanceCache.write(
                            context, fingerprint, measured.luminance
                        )
                    } else if (hdr10Plus) {
                        error("HDR10+ scene statistics unavailable")
                    }
                }
            }

            // 应用自有 P010 走字节缓冲输入（D158）；此时没有 input surface，EGL 走离屏。
            // HDR10+ 必须如此——它的动态元数据只能逐帧交给编码器，而那个接口在 surface 输入
            // 模式下被系统禁止；其余 10-bit 档位是"优先"，失败后由候选阶梯退到同格式 Surface。
            val byteBuffer = tier.usesAppP010
            if (hdr10Plus) {
                val stats = checkNotNull(hdr10PlusSceneStats) {
                    "HDR10+ scene statistics were not produced"
                }
                // FBP 的规范零值（未计算）必须在完成信息里说明（D109）。计算成功的 FBP
                // 恒 ≥ 0.001（最亮代理像素总在全权重区），因此载荷零值与"未计算"无歧义；
                // 场景统计的 proxyAverageLuminance 为 null 正是该状态的等价信号。
                hdr10PlusFbpUnavailable = stats.proxyAverageLuminance == null
                if (hdr10PlusFbpUnavailable) {
                    FableSolExportRuntimeDiagnostics.record(
                        "HDR10+ FractionBrightPixels 未计算（代理帧缺失），按 D109 写入" +
                            "规范零值；其余动态元数据不受影响。"
                    )
                }
                val sourcePeakNits = FableSolExportHdr10PlusCurve.sourcePeakNits(stats)
                val targetNits = options.referenceDisplayPeakNits.toDouble()
                // 可行性由完整场景统计确定。先转换为当前语言的正式用户提示，避免曲线层的
                // 英文诊断文本直接进入失败对话框；专用异常携带该说明，交由候选层按
                // REFERENCE_PEAK_INFEASIBLE 分类并整组跳过同格式规格（D115、D179）。
                FableSolExportHdr10PlusCurve.unsupportedReason(
                    sourcePeakNits, targetNits
                )?.let {
                    throw ReferencePeakInfeasible(
                        context.getString(
                            R.string.fablesol_export_reference_peak_infeasible,
                            sourcePeakNits.roundToInt(),
                            FableSolExportHdr10PlusCurve.minimumTargetNits(sourcePeakNits)
                        )
                    )
                }
                // 参考显示峰值是**用户创作参数**（D94），不再由导出设备在后台静默决定：
                // 本机屏幕声明的峰值只作诊断或用户主动采用的一次性参考值（D82、D93）。
                //
                // 曲线横轴按**场景 V8**归一化，失效时回退场景 MaxSCL（D113、D177）；
                // MDCV 母版峰值不属于 ST 2094-40 §8.7.4 允许的两个横轴基准。
                hdrCurve = FableSolExportHdr10PlusCurve(
                    sourcePeakNits = sourcePeakNits,
                    targetNits = targetNits,
                    highlightStartPercent = options.highlightStartPercent
                )
                val curve = hdrCurve.shapeForScene(stats)
                hdr10PlusShape = curve
                // 膝点被可行域调整（下移到最高可行值，或被最低有效膝点抬起）时，如实记录
                // 请求值与实际采用值（D115）；完成信息一侧由 SpecText.detail 按同一对数值
                // 披露，不把调整后的值伪装成原始百分位结果。
                if (kotlin.math.abs(curve.kneeNits - curve.requestedKneeNits) >= 0.05) {
                    FableSolExportRuntimeDiagnostics.record(
                        "HDR10+ 膝点由请求的 %.1f 尼特调整为 %.1f 尼特（参考显示峰值保持不变）。"
                            .format(curve.requestedKneeNits, curve.kneeNits)
                    )
                }
                hdr10PlusPayload = FableSolExportHdr10PlusMetadata.payload(
                    stats, curve, targetNits
                )
            }

            // 文件名里要带格式，而格式是降级阶梯定下来才知道的；sink 在建 muxer 那一刻
            // 才真正落名，所以这里补标签正好赶得上。
            currentAttemptPhase = AttemptPhase.ENCODER_INITIALIZATION
            request.sink.tagFormat(resolved.fileTag)
            encoder = FableSolExportEncoder(
                plan.canvasWidthPx,
                plan.canvasHeightPx,
                frameRate,
                tier,
                options,
                sampleRate,
                request.sink.createMuxer(),
                peakNits = peakNits,
                diffuseWhiteNits = whiteNits,
                luminance = luminance,
                form = rateControlForm,
                applyHighComplexity = applyHighComplexity,
                applyBFrames = applyBFrames
            )
            egl = FableSolExportEgl(
                if (byteBuffer) null else encoder.inputSurface,
                transfer = tier.transfer,
                tenBit = !tier.eightBit
            )
            encoder.start()
            currentAttemptPhase = AttemptPhase.HDR_RENDER_PATH
            if (byteBuffer) {
                fun buildBridge(hlg: FableSolExportHlgPlan?) = FableSolExportP010Bridge(
                    assets = context.assets,
                    widthPx = plan.canvasWidthPx,
                    heightPx = plan.canvasHeightPx,
                    definition = FableSolExportP010Math.ColorDefinition.forTransfer(
                        tier.transfer
                    ),
                    chromaSiting = resolveChromaSiting(options, tier, frameRate),
                    // HDR10+ 的精确统计已在离线场景预分析完成；正式编码只重复同一份场景载荷，
                    // 不再把每一帧重新定义成一个场景（D177）。
                    collectStats = false,
                    diffuseWhiteNits = whiteNits,
                    blueNoise = FableSolExportBlueNoise.load(context.assets),
                    // super-white 的量化边界与肩部容量必须同源：着色器把最亮的方向恰好推到
                    // 各自的上限，量化再按名义 940/960 钳一次，那个上限就白算了（D134）。
                    signalRange = hlg?.signalRange
                        ?: FableSolExportP010Math.SignalRange.NOMINAL
                )
                var built = buildBridge(hlgPlan)
                // 扩展信号范围的前提除了回环验证，还有 FP16 呈现中间面**真的建了出来**：
                // D153 的 RGB10_A2 后备是归一化定点，在名义峰值截断——肩部为 100%～109%
                // 设计的顶端会变成硬剪切，放宽的量化边界永远收不到名义以上的输入，完成信息
                // 却仍会标"扩展"。此时把本次降为名义范围（D135 允许的路径），肩部容量、
                // 量化边界与完成信息同步如实。
                val extendedPlan = hlgPlan
                if (extendedPlan?.extended == true && !built.presentHighPrecision) {
                    FableSolExportRuntimeDiagnostics.record(
                        "HLG 扩展信号范围验证已通过，但 FP16 呈现中间面不可用（退 " +
                            "RGB10_A2 兼容面）；本次按名义范围导出（D135、D153）。"
                    )
                    built.release()
                    val nominalPlan = FableSolExportHlgPlan.nominal(strength.toDouble())
                    hlgPlan = nominalPlan
                    resolved = resolved.copy(hlgRange = nominalPlan.range)
                    built = buildBridge(nominalPlan)
                }
                bridge = built
            }
            clock = FableSolExportClock(context, plan, request.accentBackground)
            // 保留高光 SDR 与 HDR 一样需要用户的强度渲染出 >1.0 的超白内容（D63）；原生 SDR
            // 关掉额外高光重新渲染，不做任何色调映射（D62 第 1 条、D68）。
            val hdrSource = tier.hdr || sdrRender?.usesHdrSource == true
            presenter = FableSolExportPresenter(
                context.assets,
                plan,
                clock,
                FableSolExportPresenter.shaderTransfer(tier.transfer),
                dither = tier.eightBit,
                whiteNits = whiteNits.toFloat(),
                sdrRender = sdrRender,
                hdrStrength = if (hdrSource) strength else FableSolHdrPolicy.STRENGTH_OFF,
                frameIntervalSeconds = 1.0 / frameRate,
                // 蓝噪声只有 8-bit Surface 那条路用得到（D162）；10-bit 的码值域抖动在
                // FableSolExportP010Bridge 里，各持一份表，避免共享实例双重删除纹理。
                blueNoise = if (tier.eightBit) {
                    FableSolExportBlueNoise.load(context.assets)
                } else {
                    null
                },
                hlgPlan = hlgPlan
            )
            bridge?.let { presenter.targetFramebufferId = it.presentFramebufferId }
            renderer = FableSolGlRenderer(context, plan.density)
            renderer.setOfflineTimebase(true)
            renderer.setScenePresenter(presenter)
            renderer.initialize(capability.linearSceneSupported)
            renderer.resize(plan.cardWidthPx, plan.cardHeightPx)
            // resize() 才会真正创建 FP16 scene targets。渲染器若静默退到 RGBA8，HLG 档必须
            // 失败并走 SDR 重渲染，不能把 8-bit SDR 场景标成 HDR 成功。
            check(!tier.hdr || renderer.isHdrContentEnabled()) {
                "FP16 scene targets are unavailable"
            }
            // FP16 扩展显示线性是保留高光 SDR 的硬前提；分配不出来就从第 1 帧改用原生 SDR，
            // 并如实标注实际产物，不改写用户偏好（D78）。这一步在首帧之前，重来不浪费渲染。
            if (sdrRender?.toneMapped == true && !renderer.isHdrContentEnabled()) {
                throw SdrDegradeRestart(
                    FableSolExportSdrRender.NATIVE,
                    "FP16 scene targets are unavailable"
                )
            }
            if (sdrRender?.dynamic == true) {
                presenter.dynamicStatsFailure?.let { cause ->
                    throw SdrDegradeRestart(
                        FableSolExportSdrRender.TONE_MAPPED_STABLE, cause
                    )
                }
            }
            renderer.setThingBackground(request.waterBackground)
            renderer.setPresentationAlpha(1f)
            renderer.primeHdrForExport(
                if (hdrSource) strength else FableSolHdrPolicy.STRENGTH_OFF
            )
            renderer.primeFrameTime(TIMEBASE_ORIGIN_NANOS)
            renderer.setOfflineFixedDt(1.0 / frameRate)

            // 用户关掉倾斜时连读都不读：与"这份录音本来就没有轨迹"走同一条竖直渲染路径，
            // 不需要第二种表达方式。
            val analyzer = FableSolRealtimeAnalyzer(
                sampleRate,
                FableSolCaptureProfile.PHONE_CAPTURE_V1
            )
            FableSolFrontEndTuning().also { tuning ->
                FableSolTuning.applyFrontEndStored(context, tuning)
                tuning.applyTo(analyzer)
            }
            analyzer.skipStartupGate()
            // 逐帧驱动与预分析共用同一个状态机，两条循环因此逐帧喂同样的音频与重力。
            val drive = Drive(
                audio = audio,
                analyzer = analyzer,
                renderer = renderer,
                presenter = presenter,
                gravityTrack = if (options.tiltEnabled) {
                    FableSolGravityTrack.readFrom(File(request.audioPath))
                } else {
                    null
                },
                frameRate = frameRate,
                sampleRate = sampleRate
            )
            // 预分析已经跑掉一遍全片时，编码这一遍从进度条的一半开始。
            val progressBase = progressTotal - totalFrames
            var frameIndex = 0

            currentAttemptPhase = AttemptPhase.ENCODING
            while (frameIndex < totalFrames) {
                if (listener.isCancelled()) return Result.Cancelled
                checkDynamicSpace(options, frameIndex)
                if (!drive.advance(frameIndex) { buffer, count ->
                        encoder.feedAudio(buffer, count)
                    }
                ) {
                    break
                }
                // 动态统计在正式导出期间失败：丢弃这次尝试，从第 1 帧改用稳定映射（D77）。
                // 不再往下退到原生 SDR——FP16 场景加固定曲线仍然保得住高光层次。
                presenter.dynamicStatsFailure?.let { cause ->
                    throw SdrDegradeRestart(
                        FableSolExportSdrRender.TONE_MAPPED_STABLE, cause
                    )
                }
                val presentationTimeUs = frameIndex.toLong() * 1_000_000L / frameRate
                val activeBridge = bridge
                if (activeBridge == null) {
                    check(egl.swapBuffers(presentationTimeUs * 1_000L)) {
                        "eglSwapBuffers failed at frame $frameIndex"
                    }
                } else {
                    // 转换（色度 → 亮度闭环）在 GPU 上完成；HDR10+ 的场景元数据仍必须在每帧
                    // 入队之前设置，但载荷在整个连续场景内逐位相同（D177）。
                    activeBridge.convert()
                    val payload = if (hdr10Plus) {
                        checkNotNull(hdr10PlusPayload)
                    } else {
                        null
                    }
                    encoder.queueVideoFrame(presentationTimeUs, payload) { buffer, layout ->
                        activeBridge.writeInto(buffer, layout)
                    }
                }
                // 输出格式验证与 muxer.addTrack 都在 drain 内；它们失败会清理并重试下一档。
                encoder.drain(endOfStream = false)
                if (
                    frameIndex >= frameRate * MUXER_START_DEADLINE_SECONDS &&
                    !encoder.hasStartedMuxer
                ) {
                    error("Encoder produced no usable track format")
                }

                frameIndex++
                if (frameIndex % PROGRESS_INTERVAL_FRAMES == 0) {
                    reportProgress(attemptStartedAt, progressBase + frameIndex, progressTotal)
                }
            }

            if (listener.isCancelled()) return Result.Cancelled
            // 音频尾巴（不足一帧的那一段）也要进编码器，否则音轨会比画面短一帧。
            if (!drive.drainRemainingAudio(
                    onAudio = { buffer, count -> encoder.feedAudio(buffer, count) },
                    cancelled = listener::isCancelled
                )
            ) {
                return Result.Cancelled
            }
            checkDynamicSpace(options, frameIndex, force = true)
            if (!encoder.finish(listener::isCancelled)) return Result.Cancelled
            if (listener.isCancelled()) return Result.Cancelled
            // **发布前的最后一道门**：探测通过不代表这一次真的写进去了。编码器若在运行中
            // 丢掉动态元数据，我们就会发布一个文件名与完成提示都标着 HDR10+、实际却是普通
            // Main10 的产物——那等于骗用户。这里抛出去，降级阶梯会换下一档（HDR10）重来。
            check(!hdr10Plus || encoder.hdr10PlusSeiSeen) {
                "HDR10+ metadata never reached the bitstream"
            }
            // 覆盖率如实记录（D91 第 4 条修订）：输入侧每帧都注入，部分覆盖意味着编码器
            // 丢了部分帧的 SEI。诊断始终记录；0 < N < M 时完成信息另加一行说明，不改报失败。
            if (hdr10Plus) {
                FableSolExportRuntimeDiagnostics.record(
                    "HDR10+ SEI 覆盖：${encoder.hdr10PlusSeiSamples}/" +
                        "${encoder.videoSamplesWritten} 个视频样本。"
                )
            }
            // **同一道门的另一半：编码器有没有真的产出东西。** `INFO_OUTPUT_FORMAT_CHANGED`
            // 一来就能 addTrack 并启动 muxer，随后一个样本都没有，`finish()` 照样成功返回，
            // 产物是 0 字节。华为平板上每一档 HEVC 都这样（10 位输入表面拿不到带 recordable
            // 的 config），只有 8 位的 H.264 有数据（2026-07-28）。抛出去让阶梯换下一档。
            check(encoder.videoSamplesWritten > 0L) {
                "Encoder produced no video samples for ${tier.label}"
            }

            // finish() 已完成并验证 muxer.stop()/release()；现在才允许关闭 PFD、清 pending 或
            // 触发 MediaScanner。发布失败仍属于整次候选失败。
            val committed = request.sink.commit(listener::isCancelled)
            // 旧系统的 MediaScanner 回调最多会等待数秒；这期间若发生系统超时，仍删除刚发布
            // 的文件，让即时 Failed 终态与磁盘结果保持一致。
            if (listener.isCancelled()) return Result.Cancelled
            if (!committed) {
                throw PublishFailure("Failed to publish the exported video")
            }
            // 兜底：样本计数正常但落盘仍是空文件时，绝不能把它留在图库里。这一步失败也算
            // 本候选失败，阶梯会换下一档重来。
            if (request.sink.fileSizeBytes() <= 0L) {
                error("Published file is empty for ${tier.label}")
            }
            published = true
            // D166 第三条：正式产物封装成功后再解析一遍静态元数据。结论只进诊断与完成信息
            // 的补充说明，不删除、不重编码、不改报失败（D142）。
            var staticMetadataConflict: String? = null
            if (tier.hdrFormat?.writesStaticMetadata == true) {
                val expectedInfo = encoder.expectedStaticInfo()
                val productUri = request.sink.contentUri()
                if (expectedInfo != null && productUri != null) {
                    val outcome = FableSolExportStaticMetadataCheck.inspect(
                        context, productUri, expectedInfo
                    )
                    FableSolExportRuntimeDiagnostics.record(
                        "正式产物静态元数据：" + when (outcome) {
                            is FableSolExportStaticMetadataCheck.Outcome.Match ->
                                "容器与预期一致。"
                            is FableSolExportStaticMetadataCheck.Outcome.AbsentFromContainer ->
                                "容器未携带，实际承载为码流 SEI。"
                            is FableSolExportStaticMetadataCheck.Outcome.Conflict ->
                                "容器与预期不一致（${outcome.detail}）。"
                            null -> "解析未完成。"
                        }
                    )
                    staticMetadataConflict =
                        (outcome as? FableSolExportStaticMetadataCheck.Outcome.Conflict)?.detail
                }
            }
            reportProgress(attemptStartedAt, frameIndex, frameIndex)
            return Result.Success(
                request.sink,
                tier.displayLabel(context),
                resolved.isHdr,
                resolved.formatLabel(context),
                // 位深要写出来：10 位 HEVC 的分享兼容性明显差于 8 位。
                resolved.codecLabel,
                resolved.softwareOnly,
                resolved.frameRate,
                frameIndex,
                SystemClock.elapsedRealtime() - exportStartedAt,
                // 只在真正生效时才带出去：HLG 系没有绝对锚点，非 HDR10+ 没有色调映射曲线。
                pqWhiteNits = resolved.pqWhiteNits,
                peakNits = resolved.peakNits,
                highlightStartPercent = resolved.highlightStartPercent,
                hdr10PlusRequestedKneeNits = hdr10PlusShape?.requestedKneeNits ?: 0.0,
                hdr10PlusKneeNits = hdr10PlusShape?.kneeNits ?: 0.0,
                hdr10PlusFbpUnavailable = hdr10PlusFbpUnavailable,
                hdr10PlusSeiSamples = if (hdr10Plus) encoder.hdr10PlusSeiSamples else 0L,
                hdr10PlusSeiTotal = if (hdr10Plus) encoder.videoSamplesWritten else 0L,
                staticMetadataConflict = staticMetadataConflict,
                // 读的是**这次真的用了的场景曲线**，不再用理论母版峰值另行推断。
                hdr10PlusIdentity = hdrCurve?.identityMapping,
                luminance = luminance.takeIf {
                    tier.hdrFormat?.writesStaticMetadata == true
                },
                hlgRange = resolved.hlgRange,
                encoding = FableSolExportSpecText.Encoding(
                    rateControl = resolved.rateControl,
                    bFrames = resolved.bFramesRequested,
                    highComplexity = resolved.highComplexityRequested,
                    qpGuard = resolved.qpGuardRequested
                )
            )
        } finally {
            renderer?.setScenePresenter(null)
            safely { presenter?.release() }
            safely { renderer?.release() }
            // bridge 的 GL 资源必须在 EGL 上下文还在的时候释放。
            safely { bridge?.release() }
            safely { clock?.release() }
            safely { egl?.release() }
            safely { encoder?.release() }
            safely { audio?.release() }
            if (!published) safely { request.sink.discard() }
        }
    }

    /**
     * 本次 P010 转换使用的色度相位（D154、D170）。
     *
     * 相位必须在第一帧渲染之前定下来，而码流要到编码开始之后才有——因此这里只读**已经得出
     * 的**探测结论，绝不在导出关键路径上现探（一次完整探测要连续创建几十个 MediaCodec）。
     * 没有结论就按 HEVC 生态的 Type 0 兼容语义走：那正是解码端在 VUI 缺失时的解释方式，
     * 比继续生成 Type 1 数据再让解码端按别的位置解释要正确。
     */
    private fun resolveChromaSiting(
        options: FableSolExportOptions,
        tier: FableSolExportTier,
        frameRate: Int
    ): FableSolExportP010Math.ChromaSiting = try {
        FableSolHdrExportCapability.cachedMatrix(context).chromaSiting(
            format = tier.hdrFormat,
            family = tier.family,
            frameRate = frameRate,
            tenBit = !tier.eightBit,
            rateControl = options.rateControl,
            codecName = tier.codecName
        ) ?: FableSolExportP010Math.ChromaSiting.COMPATIBLE_DEFAULT
    } catch (ignored: Throwable) {
        FableSolExportP010Math.ChromaSiting.COMPATIBLE_DEFAULT
    }

    /**
     * 本次 HLG 输出变换的肩部与信号范围（D126～D140）；非 HLG 系候选返回 null。
     *
     * 三条判断按顺序：
     *
     * 1. **只有 HLG 系用得到。** 杜比视界 8.4 的兼容基层就是 BT.2020 HLG，因此与普通 HLG
     *    共用同一条变换与同一份验证结论（D143）；PQ 系与 SDR 不进这条路。
     * 2. **super-white 只能建立在应用自有 P010 上。** RGB Surface 即便编码成功，也证明不了
     *    100% 以上的信号没有被 EGL、Surface 或编码器自己的 RGB→YUV 阶段钳到名义峰值
     *    （D134）；这条路一律用名义范围。
     * 3. 用户显式选择"名义范围"时不为本次导出触发任何验证（D138 第 3 条）。
     */
    /**
     * 本次是否下发 `KEY_COMPLEXITY = upper`（D149）。
     *
     * 与码控形态同一条纪律：只读**已经得出**的探测结论（探测阶梯确认被拒 → 省略该键），
     * 缓存里没有结论时按请求下发——那正是阶梯的第一级，真拒绝的编码器会在初始化失败后
     * 交由候选处理。
     */
    private fun resolveHighComplexity(
        options: FableSolExportOptions,
        tier: FableSolExportTier,
        frameRate: Int
    ): Boolean = try {
        FableSolHdrExportCapability.cachedMatrix(context).highComplexityAccepted(
            format = tier.hdrFormat,
            family = tier.family,
            frameRate = frameRate,
            tenBit = !tier.eightBit,
            codecName = tier.codecName,
            rateControl = options.rateControl
        ) ?: true
    } catch (ignored: Throwable) {
        true
    }

    private fun resolveHlgPlan(
        options: FableSolExportOptions,
        tier: FableSolExportTier,
        frameRate: Int,
        strength: Float,
        /** 本次实际下发的码控形态；回环验证的编码器必须与正式导出同源（D139、D167）。 */
        form: FableSolExportRateControlForm
    ): FableSolExportHlgPlan? {
        if (tier.transfer != FableSolExportTransfer.HLG) return null
        val nominal = FableSolExportHlgPlan.nominal(strength.toDouble())
        if (!tier.usesAppP010) return nominal
        val requested = options.effectiveHlgSignalRange(tier.hdrFormat)
        if (requested != FableSolExportHlgSignalRange.AUTO_ENHANCED) return nominal
        listener.onPreparing(FableSolVideoExportBus.STAGE_HLG_RANGE)
        return try {
            // 结论按完整签名缓存（D138）：同一套设置的首次导出不能因为后台探测完成时机不同
            // 而随机走上不同画质路径。失败原因也一并留在缓存里，供设备诊断读取。
            val outcome = FableSolExportHlgVerification.resolve(
                context = context,
                tier = tier,
                options = options,
                frameRate = frameRate,
                form = form
            )
            FableSolExportHlgPlan.of(strength.toDouble(), outcome.safe)
        } catch (error: Throwable) {
            // 受控异常与超时同样只表示"这次验证不出来"，不是 HLG 编码失败（D138）。
            nominal
        }
    }

    /**
     * 本次码控形态里**来自探测结论**的那一部分（D145、D167）。
     *
     * 只读**已经得出**的探测结论，绝不在导出关键路径上现探——与色度位置同一条规矩（D154）。
     * 返回 null 表示缓存没有本组合的结论（从未探测、编码器名不匹配或契约版本失配）：调用方
     * 按当前档位能力从纯 CQ 开始（D167 阶梯第一级），初始化失败时按 D186 的运行时阶梯以
     * CQ+码率提示同候选重试一次。
     */
    private fun cachedRateControlForm(
        options: FableSolExportOptions,
        tier: FableSolExportTier,
        frameRate: Int
    ): FableSolExportRateControlForm? = try {
        FableSolHdrExportCapability.cachedMatrix(context).rateControlForm(
            format = tier.hdrFormat,
            family = tier.family,
            frameRate = frameRate,
            tenBit = !tier.eightBit,
            rateControl = options.rateControl,
            codecName = tier.codecName
        )?.takeIf {
            // 缓存里的形态是**上一次用户选择**下探出来的；用户此后改了编码模式，那条结论
            // 就不适用了。只有同一个用户可见模式才复用。
            it.userVisibleMode == options.rateControl
        }
    } catch (ignored: Throwable) {
        null
    }

    private fun readAudioMetadata(): AudioMetadata {
        var source: FableSolExportAudioSource? = null
        return try {
            source = FableSolExportAudioSource(request.audioPath)
            AudioMetadata(source.durationUs.coerceAtLeast(0L))
        } finally {
            source?.release()
        }
    }

    private fun checkInitialSpace(
        options: FableSolExportOptions,
        frameRate: Int,
        durationSeconds: Double,
        plan: FableSolExportPlan
    ): Result.OutOfSpace? {
        if (options.prefersConstantQuality || durationSeconds <= 0.0) {
            return if (
                FableSolExportSink.hasMinimumFreeSpace(MIN_FREE_SPACE_BYTES)
            ) {
                null
            } else {
                Result.OutOfSpace(MIN_FREE_SPACE_BYTES)
            }
        }
        // 这一步在解析出候选**之前**，拿不到实际编码器族与位深。空间检查宁可高估：按 D147
        // 模型里系数最大的组合（AVC、10-bit、HDR）算，少预留才会让导出跑到一半没空间。
        val estimatedBitrate = options.bitrateMbps
            ?.let { FableSolExportBitrateModel.customBitrateBps(it) }
            ?: FableSolExportBitrateModel.autoBitrateBps(
                widthPx = plan.canvasWidthPx,
                heightPx = plan.canvasHeightPx,
                frameRate = frameRate,
                family = FableSolExportCodecFamily.AVC,
                tenBit = true,
                hdr = true
            )
        val combinedBitrate = estimatedBitrate.toLong() + AUDIO_BITRATE_BPS
        val estimated = (durationSeconds * combinedBitrate / 8.0).toLong() +
            ESTIMATE_HEADROOM_BYTES
        return if (FableSolExportSink.hasRoomFor(estimated)) {
            null
        } else {
            Result.OutOfSpace(estimated)
        }
    }

    private fun checkDynamicSpace(
        options: FableSolExportOptions,
        frameIndex: Int,
        force: Boolean = false
    ) {
        if (!options.prefersConstantQuality) return
        if (!force && frameIndex % SPACE_CHECK_INTERVAL_FRAMES != 0) return
        if (!FableSolExportSink.hasMinimumFreeSpace(MIN_FREE_SPACE_BYTES)) {
            throw OutOfSpaceFailure(MIN_FREE_SPACE_BYTES)
        }
    }

    private fun reportProgress(startedAt: Long, done: Int, total: Int) {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val eta = if (done > 0 && total > 0 && total != Int.MAX_VALUE) {
            ((elapsed.toDouble() / done) * (total - done)).roundToInt().toLong()
        } else {
            -1L
        }
        emitProgress(done, total, eta)
    }

    private fun emitProgress(done: Int, total: Int, etaMs: Long) {
        if (done > 0) currentAttemptHasVisibleProgress = true
        listener.onProgress(done, total, etaMs)
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 一次导出与它的全片亮度预分析**共用**的逐帧驱动。
     *
     * 两条循环必须喂同样的音频、同样的重力、同样的时间戳，否则统计描述的就不是最终产物那段
     * 画面（D85、D92 的缓存也就无从谈起）。把这段状态机放在唯一一处，是保证这一点最省事的
     * 办法——分成两份写，迟早有一份被改动而另一份没有。
     */
    private class Drive(
        private val audio: FableSolExportAudioSource,
        private val analyzer: FableSolRealtimeAnalyzer,
        private val renderer: FableSolGlRenderer,
        private val presenter: FableSolExportPresenter,
        private val gravityTrack: FableSolGravityTrack?,
        private val frameRate: Int,
        private val sampleRate: Int
    ) {

        private val pcm = ShortArray(ANALYZER_BATCH)
        private val mono = DoubleArray(ANALYZER_BATCH)
        private val gravity = FloatArray(3)
        private var samplesFed = 0L

        var audioExhausted = false
            private set

        /**
         * 推进到第 [frameIndex] 帧并渲染。
         *
         * @param onAudio 编码路径用它把 PCM 交给音频编码器；预分析传 null，不编码音频。
         * @return false 表示音频已经放完，本帧不再渲染。
         */
        fun advance(frameIndex: Int, onAudio: ((ShortArray, Int) -> Unit)?): Boolean {
            // 第 i 帧只消费 i/fps 之前的音频，不读取未来样本。
            val targetSamples = frameIndex.toLong() * sampleRate / frameRate
            while (!audioExhausted && samplesFed < targetSamples) {
                val want = minOf(ANALYZER_BATCH.toLong(), targetSamples - samplesFed).toInt()
                val read = audio.read(pcm, want)
                if (read <= 0) {
                    audioExhausted = true
                    break
                }
                for (index in 0 until read) {
                    mono[index] = pcm[index] / 32768.0
                }
                val (frames, events) = analyzer.feed(mono, read)
                if (frames.isNotEmpty() || events.isNotEmpty()) {
                    renderer.onAudioFrames(frames, events)
                }
                onAudio?.invoke(pcm, read)
                samplesFed += read
            }
            if (audioExhausted && samplesFed <= targetSamples) return false

            if (gravityTrack != null) {
                gravityTrack.sampleAt(frameIndex.toDouble() / frameRate, gravity)
            } else {
                gravity[0] = 0f
                gravity[1] = 1f
                gravity[2] = 0f
            }
            renderer.setGravity(gravity[0], gravity[1], gravity[2])

            presenter.elapsedMs = frameIndex.toLong() * 1000L / frameRate
            renderer.render(
                TIMEBASE_ORIGIN_NANOS +
                    (frameIndex + 1).toLong() * 1_000_000_000L / frameRate
            )
            return true
        }

        /** 收尾：不足一帧的那段音频尾巴也要进编码器，否则音轨会比画面短一帧。 */
        fun drainRemainingAudio(
            onAudio: (ShortArray, Int) -> Unit,
            cancelled: () -> Boolean
        ): Boolean {
            while (!audioExhausted) {
                val read = audio.read(pcm, ANALYZER_BATCH)
                if (read <= 0) {
                    audioExhausted = true
                    break
                }
                onAudio(pcm, read)
                if (cancelled()) return false
            }
            return true
        }
    }

    /**
     * 全片 HDR 预分析（D85、D86、D177）。
     *
     * 用确定性离线渲染跑完整段动画，读最终可见合成的线性 BT.2020 画面。普通 HDR10 只归约
     * `MaxCLL` / `MaxFALL`；HDR10+ 则逐帧精确测量后累计成一个连续场景，同时得到静态亮度。
     * 这一步不做任何视频或音频编码。
     *
     * @return null 表示 FP16 中间面或静态归约不可用；普通 HDR10 按 D90 使用理论回退，
     *   HDR10+ 因缺少规范场景统计而判当前候选失败。
     */
    private fun analyseLuminance(
        capability: FableSolExportEgl.Capability,
        options: FableSolExportOptions,
        strength: Float,
        totalFrames: Int,
        frameRate: Int,
        plan: FableSolExportPlan,
        progressTotal: Int,
        collectHdr10PlusScene: Boolean
    ): HdrPreanalysis? {
        var audio: FableSolExportAudioSource? = null
        var egl: FableSolExportEgl? = null
        var renderer: FableSolGlRenderer? = null
        var presenter: FableSolExportPresenter? = null
        var clock: FableSolExportClock? = null
        var reducer: FableSolExportLuminanceReducer? = null
        var sceneTarget: FableSolExportPresentTarget? = null
        var sceneBackend: FableSolExportHdr10PlusStatsBackend? = null
        val sceneAccumulator = if (collectHdr10PlusScene) {
            FableSolExportHdr10PlusSceneAccumulator(options.pqWhiteNits.toDouble())
        } else {
            null
        }
        try {
            audio = FableSolExportAudioSource(request.audioPath)
            // 没有编码器，也就没有 input surface：EGL 走离屏 pbuffer，只为持有 GL 上下文。
            egl = FableSolExportEgl(null, transfer = FableSolExportTransfer.SDR, tenBit = false)
            if (collectHdr10PlusScene) {
                sceneTarget = FableSolExportPresentTarget.createHighPrecision(
                    plan.canvasWidthPx, plan.canvasHeightPx
                ) ?: return null
                sceneBackend = FableSolExportHdr10PlusStatsBackend(
                    assets = context.assets,
                    widthPx = plan.canvasWidthPx,
                    heightPx = plan.canvasHeightPx,
                    diffuseWhiteNits = options.pqWhiteNits.toDouble()
                )
                // 已知图自检（D104、D109、D169）：核心统计算错时先降到 GLES 3.0 兼容后端，
                // 兼容后端也不过才判统计通路不可用；FBP 链单独不过只按 D109 写规范零值。
                sceneBackend.verifyKnownImage()
                FableSolExportRuntimeDiagnostics.record(
                    "HDR10+ 统计已知图自检：核心=" +
                        (sceneBackend.failure?.let { "未通过（$it）" }
                            ?: "通过（${sceneBackend.stableLabel}）") +
                        "；FBP=" +
                        (sceneBackend.fbpKnownImageFailure?.let { "未通过（$it）" } ?: "通过") +
                        "。"
                )
                if (sceneBackend.failure != null) return null
            } else {
                reducer = FableSolExportLuminanceReducer.create(
                    assets = context.assets,
                    widthPx = plan.canvasWidthPx,
                    heightPx = plan.canvasHeightPx,
                    maxValue = strength.coerceAtLeast(1f)
                ) ?: return null
            }
            clock = FableSolExportClock(context, plan, request.accentBackground)
            presenter = FableSolExportPresenter(
                context.assets,
                plan,
                clock,
                FableSolExportPresenter.TRANSFER_LINEAR_BT2020,
                dither = false,
                whiteNits = options.pqWhiteNits,
                hdrStrength = strength,
                frameIntervalSeconds = 1.0 / frameRate
            )
            presenter.targetFramebufferId =
                sceneTarget?.framebufferId ?: checkNotNull(reducer).presentFramebufferId
            renderer = FableSolGlRenderer(context, plan.density)
            renderer.setOfflineTimebase(true)
            renderer.setScenePresenter(presenter)
            renderer.initialize(capability.linearSceneSupported)
            renderer.resize(plan.cardWidthPx, plan.cardHeightPx)
            // 场景缓冲退到 RGBA8 就算不出 >1.0 的内容，统计会系统性低报；这属于统计不可用，
            // 交给 D90 回退，而不是发布一个错误的实测值。
            if (!renderer.isHdrContentEnabled()) return null
            renderer.setThingBackground(request.waterBackground)
            renderer.setPresentationAlpha(1f)
            renderer.primeHdrForExport(strength)
            renderer.primeFrameTime(TIMEBASE_ORIGIN_NANOS)
            renderer.setOfflineFixedDt(1.0 / frameRate)

            val analyzer = FableSolRealtimeAnalyzer(
                audio.sampleRate,
                FableSolCaptureProfile.PHONE_CAPTURE_V1
            )
            FableSolFrontEndTuning().also { tuning ->
                FableSolTuning.applyFrontEndStored(context, tuning)
                tuning.applyTo(analyzer)
            }
            analyzer.skipStartupGate()
            val drive = Drive(
                audio = audio,
                analyzer = analyzer,
                renderer = renderer,
                presenter = presenter,
                gravityTrack = if (options.tiltEnabled) {
                    FableSolGravityTrack.readFrom(File(request.audioPath))
                } else {
                    null
                },
                frameRate = frameRate,
                sampleRate = audio.sampleRate
            )

            var frameIndex = 0
            val startedAt = SystemClock.elapsedRealtime()
            while (frameIndex < totalFrames) {
                // 取消也要立刻生效：预分析可能与正式编码一样长。
                if (listener.isCancelled()) return null
                if (!drive.advance(frameIndex, onAudio = null)) break
                if (collectHdr10PlusScene) {
                    val target = checkNotNull(sceneTarget)
                    val backend = checkNotNull(sceneBackend)
                    val stats = backend.measure(target.textureId)
                        ?: error(
                            "HDR10+ statistics unavailable: " +
                                (backend.failure ?: "unknown")
                        )
                    checkNotNull(sceneAccumulator).add(stats)
                } else {
                    checkNotNull(reducer).accumulate()
                    if (reducer.failure != null) return null
                }
                frameIndex++
                if (frameIndex % PROGRESS_INTERVAL_FRAMES == 0) {
                    reportProgress(startedAt, frameIndex, progressTotal)
                }
            }
            if (collectHdr10PlusScene) {
                val scene = checkNotNull(sceneAccumulator).result() ?: return null
                return HdrPreanalysis(scene.luminance, scene.stats)
            }
            val luminance = checkNotNull(reducer).result() ?: return null
            return HdrPreanalysis(luminance, hdr10PlusStats = null)
        } catch (ignored: OutOfMemoryError) {
            // 统计通路的资源问题按 D90 处理；渲染本身的错误仍会作为异常向上抛。
            return null
        } finally {
            renderer?.setScenePresenter(null)
            safely { presenter?.release() }
            safely { renderer?.release() }
            safely { sceneBackend?.release() }
            safely { reducer?.release() }
            safely { sceneTarget?.release() }
            safely { clock?.release() }
            safely { egl?.release() }
            safely { audio?.release() }
        }
    }

    private data class HdrPreanalysis(
        val luminance: FableSolExportLuminanceStats,
        val hdr10PlusStats: FableSolHdr10PlusStats?
    )

    private data class CandidatePlan(
        val attempt: FableSolExportModeAttempt,
        val tier: FableSolExportTier,
        val plan: FableSolExportPlan
    )

    private enum class AttemptPhase {
        PREPARING,
        HDR_SCENE_ANALYSIS,
        ENCODER_INITIALIZATION,
        HDR_RENDER_PATH,
        ENCODING
    }

    private data class AudioMetadata(val durationUs: Long)

    private class OutOfSpaceFailure(val requiredBytes: Long) : RuntimeException()
    private class PublishFailure(message: String) : RuntimeException(message)

    /**
     * 参考显示峰值低于场景实测峰值的可行下限（S > 10T，D115）。
     *
     * [localizedDetail] 是**已本地化**的用户说明（含场景峰值与最低可行参考峰值两个数），
     * 不是内部英文诊断；候选层据此按 REFERENCE_PEAK_INFEASIBLE 分类并整组跳过同格式规格。
     */
    private class ReferencePeakInfeasible(
        val localizedDetail: String
    ) : RuntimeException(localizedDetail)

    /**
     * SDR 成片语义的运行时降级（D77、D78）。
     *
     * 它**不是**候选失败：编码档位一切正常，只是渲染侧的某个内部质量工具不可用。因此在同一个
     * tier 上从第 1 帧重来，不消耗降级阶梯，也不改写用户偏好；完成态按 [fallback] 如实标注。
     *
     * @param detail 内部英文诊断原文（如动态统计的具体失败原因）；只进设备诊断，不进用户
     *   界面（D178）。完成信息的本地化降级说明按 [fallback] 另行生成（D77、D78）。
     */
    private class SdrDegradeRestart(
        val fallback: FableSolExportSdrRender,
        val detail: String? = null
    ) : RuntimeException(detail)

    private companion object {
        const val TAG = "FableSolVideoExporter"
        const val ANALYZER_BATCH = 512
        const val TIMEBASE_ORIGIN_NANOS = 1_000_000_000L
        const val PROGRESS_INTERVAL_FRAMES = 30
        const val SPACE_CHECK_INTERVAL_FRAMES = 30
        const val MUXER_START_DEADLINE_SECONDS = 2
        const val AUDIO_BITRATE_BPS = 192_000L
        const val ESTIMATE_HEADROOM_BYTES = 4L * 1024L * 1024L
        const val MIN_FREE_SPACE_BYTES = 64L * 1024L * 1024L
    }
}
