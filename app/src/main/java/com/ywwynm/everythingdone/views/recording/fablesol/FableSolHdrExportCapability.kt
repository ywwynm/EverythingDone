package com.ywwynm.everythingdone.views.recording.fablesol

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.GLES30
import android.os.Build
import com.ywwynm.everythingdone.BuildConfig
import com.ywwynm.everythingdone.R
import java.io.File

/**
 * HDR 导出能力的实际探测。
 *
 * 不能只相信 codec 广告的 Main10 profile：厂商实现可能到 configure、10-bit HLG
 * EGL window surface、start 或输出格式阶段才拒绝。这里复用正式导出的编码器、EGL 和
 * 输出格式校验，编码一帧黑场与一小段静音；完整成功才允许设置页打开 HDR 开关。
 *
 * 结果按探测实现、App 版本和系统 build 缓存。成功结果随该签名长期有效；失败结果缓存
 * 24 小时，避免临时的 codec 资源占用既造成每次开 Dialog 都重试，又永久误判设备不支持。
 */
internal object FableSolHdrExportCapability {

    @Volatile
    private var processCache: CachedResult? = null

    /**
     * 最近一次探测失败停在哪一步；null 表示成功或尚未探测。设置页据此告诉用户原因。
     *
     * 保存的是**结构化**结论，不是拼好的句子：原因是设备事实，与 locale 无关，当前语言的
     * 说明在 [diagnostics] 展示时生成。
     */
    @Volatile
    var lastFailureReason: FableSolExportFailure? = null
        private set

    /**
     * 逐个候选档位的真实失败原因（格式 + 档位名 + 结构化原因）。
     *
     * 只给"没有编码器能编出一帧"这一句是不够的：三星那台机器色彩空间、Main10 编码器一应
     * 俱全却仍然失败，卡在哪一步只有异常原文本身说得清。
     */
    @Volatile
    var lastCandidateFailures: List<FableSolExportCandidateFailure> = emptyList()
        private set

    /**
     * 最近一次探测中**真的编出了一帧**的格式，按
     * [FableSolExportHdrFormat.SELECTABLE_ORDER] 排序。
     *
     * 设置页只允许用户选中这个列表里的格式。仅凭 `MediaCodecList` 广告是不够的：广告说有
     * Main10HDR10Plus 而 configure 时静默降成 Main10 的情况完全存在，那样界面上摆着一个
     * 名不副实的选项，比不给这个选项更糟。
     */
    @Volatile
    var lastSupportedFormats: List<FableSolExportHdrFormat> = emptyList()
        private set

    /**
     * 最近一次探测得出的 (格式 × 编码器族 × 帧率) 可行组合表。
     *
     * 设置页据此把不成立的组合置灰。这些数据本来就在探测过程里产生，此前一编成功就跳出
     * 循环，只留下"该格式可用"一个布尔量，于是既说不出落在哪个编码器上，也无从判断别的
     * 组合成不成立。
     */
    @Volatile
    var lastMatrix: FableSolExportCapabilityMatrix = FableSolExportCapabilityMatrix.EMPTY
        private set

    /** 可行组合表；会在必要时触发一次真实探测。 */
    fun matrix(context: Context): FableSolExportCapabilityMatrix {
        probe(context)
        return lastMatrix
    }

    @Volatile
    private var linearSceneCache: Boolean? = null

    @Volatile
    private var dynamicStatsCache: Boolean? = null

    /**
     * 本机能否用 `GL_RGBA16F` 合成场景，即算不算得出 `>1.0` 的超白值。
     *
     * 它是 `SDR（保留高光层次）` 的硬前提（D78），设置页据此置灰该选项。**只能在后台线程
     * 调用**：首次会建一个一次性的 pbuffer 上下文，结论按进程缓存——EGL 与 GL 扩展在进程
     * 生命周期内不会变。
     */
    fun linearSceneSupported(): Boolean = linearSceneCache
        ?: FableSolExportEgl.probe().linearSceneSupported.also { linearSceneCache = it }

    /** 已经得出的结论，不触发探测；未探过时返回 null，界面按"暂不置灰"处理。 */
    fun peekLinearSceneSupported(): Boolean? = linearSceneCache

    /**
     * 动态映射的逐帧峰值统计通路是否通过已知图探测（D77）。
     *
     * 与 [linearSceneSupported] 同一策略：**只能在后台线程调用**，首次建一次性 pbuffer
     * 上下文实测（同一次探测顺带回填 FP16 结论），按进程缓存。结论只驱动设置页把
     * "动态映射"置灰并说明原因，不改写用户偏好；正式导出仍以运行时结果为准（D77 后半）。
     */
    fun dynamicSdrStatsSupported(context: Context): Boolean = dynamicStatsCache
        ?: FableSolExportEgl.probe(context.applicationContext.assets).let {
            linearSceneCache = it.linearSceneSupported
            it.dynamicSdrStatsSupported
        }.also { dynamicStatsCache = it }

    /**
     * 已经得出的可行组合表，**不触发探测**；没有有效缓存时返回空表。
     *
     * 正式导出用这一个。一次完整探测要连续创建并释放几十个 MediaCodec 实例，放进导出的关键
     * 路径上等于让用户白等一次；而导出侧要它只为一件事——按 D154/D170 选色度相位，取不到就
     * 走 Type 0 兼容语义，不影响任何一档候选的成败。
     */
    fun cachedMatrix(context: Context): FableSolExportCapabilityMatrix {
        val signature = cacheSignature(context)
        val now = System.currentTimeMillis()
        val cached = processCache?.takeIf { it.isValid(signature, now) }
            ?: readPersisted(context)
                ?.takeIf { it.isValid(signature, now) }
                ?.also { processCache = it }
            ?: return FableSolExportCapabilityMatrix.EMPTY
        return FableSolExportCapabilityMatrix.decode(cached.matrix)
    }

    /**
     * 只读进程内结果，不触发 SharedPreferences 磁盘读取。设置页可据此立即恢复本进程已经
     * 得出的状态；首次进程启动后的持久化读取仍放在后台线程。
     */
    fun peekCachedResult(context: Context): Boolean? {
        val cached = processCache ?: return null
        return cached.takeIf { it.isValid(cacheSignature(context), System.currentTimeMillis()) }?.supported
    }

    /**
     * 同一进程内串行解析，避免 Dialog 重建时两组硬件编码器互相抢占而产生假阴性。
     * 先读进程/持久化缓存；只有缓存缺失、签名失效或失败结果过期时才真正编码。
     */
    @Synchronized
    fun probe(context: Context): Boolean {
        val now = System.currentTimeMillis()
        processCache
            ?.takeIf { it.isValid(cacheSignature(context), now) }
            ?.let {
                restoreDiagnostics(it)
                return it.supported
            }
        readPersisted(context)
            ?.takeIf { it.isValid(cacheSignature(context), now) }
            ?.let {
                processCache = it
                restoreDiagnostics(it)
                return it.supported
            }

        val supported = try {
            probeInternal(context)
        } catch (error: Throwable) {
            lastSupportedFormats = emptyList()
            lastCandidateFailures = emptyList()
            lastMatrix = FableSolExportCapabilityMatrix.EMPTY
            lastFailureReason = FableSolExportFailure(
                code = FableSolExportFailure.Code.PROBE_EXCEPTION,
                detail = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            )
            false
        }
        val resolved = CachedResult(
            signature = cacheSignature(context),
            supported = supported,
            checkedAtMs = now,
            formats = lastSupportedFormats.map { it.stableLabel },
            reason = lastFailureReason?.encode(),
            failures = lastCandidateFailures.map { it.encode() },
            matrix = lastMatrix.encode()
        )
        processCache = resolved
        persist(context, resolved)
        return supported
    }

    /**
     * 本机**实测**可用的 HDR 输出格式。设置页据此决定摆哪几个可选项：没编出过一帧的格式
     * 不出现，用户就不会选到一个其实不成立的东西。
     */
    fun supportedFormats(context: Context): List<FableSolExportHdrFormat> {
        probe(context)
        return lastSupportedFormats
    }

    /**
     * 设备实际提供了什么——把"为什么不支持"从一句话变成可核对的清单。
     *
     * 第一行给的是**实测**结论：哪几种格式真的编出了一帧、自动档会落到哪一种。下面几行
     * 是设备广告的编码器清单，只作对照——广告与实测不一致正是最值得看的信息。
     */
    fun diagnostics(context: Context): String {
        // 必须先等探测跑完再出报告。之前直接读缓存，而探测是延后 800ms 在后台跑的，
        // 报告永远停在"尚未探测"，最关键的失败原因一次都没显示出来。
        probe(context)
        val egl = FableSolExportEgl.probe()
        val lines = ArrayList<String>(8)
        val strength = FableSolTuning.hdrStrength(context)
        val rateControl = FableSolTuning.exportRateControl(context)
        val modeFormats = lastSupportedFormats.filter { format ->
            lastMatrix.hasUsable(
                format = FableSolExportCapabilityMatrix.FormatFilter.Exactly(format),
                rateControl = rateControl
            )
        }
        val supported = modeFormats.isNotEmpty()
        val automatic = lastMatrix.resolve(
            colorMode = if (strength <= FableSolHdrPolicy.STRENGTH_OFF) {
                FableSolExportColorMode.SDR_NATIVE
            } else {
                FableSolExportColorMode.HDR_AUTO
            },
            codec = FableSolExportOptions.CodecPreference.AUTO,
            frameRate = FableSolTuning.exportFrameRate(context),
            sdrBitDepth = FableSolTuning.exportSdrBitDepth(context),
            rateControl = rateControl
        )
        lines += if (supported) {
            // 只写"HDR10 通过"是不够的：它落在哪个编码器、哪个帧率上，正是这次要回答的
            // 问题。三星 Z Fold4 上"验证通过"的其实是软件 AV1 的 60fps，而界面看起来像是
            // HDR10 一切正常（2026-07-27）。
            "HDR 导出能力：可用；单帧编码与封装验证通过：" +
                modeFormats.joinToString("；") {
                    it.displayName(context) + "（" + combinationSummary(it, rateControl) + "）"
                } +
                "。当前严格帧率下的自动选择：" + (
                    automatic?.let {
                        (it.format?.displayName(context) ?: FableSolExportHdrFormat.SDR_LABEL) +
                            "（${it.frameRate} fps ${it.family.stableLabel} " +
                            (if (it.tenBit) "10-bit" else "8-bit") +
                            if (it.outcome.softwareOnly) "，软件编码）" else "，硬件编码）"
                    } ?: "无可行组合"
                    ) + "。"
        } else {
            "HDR 导出能力：不可用。" + (
                lastFailureReason?.let { " " + describe(context, it) } ?: ""
                )
        }
        for (record in lastCandidateFailures.take(MAX_REPORTED_FAILURES)) {
            lines += "  · " + describeCandidateFailure(context, record)
        }
        lines += "SDR 通路：" + combinationSummary(null, rateControl) + "。"
        lines += rejectedCombinationLines(context, rateControl)
        lines += sizeAndRateLines(context)
        val display = FableSolExportDisplayLuminance.read(context)
        // 这一行是**设备诊断与观看参考**，不参与母版意图：漫反射白按 D82/D83 固定为与导出
        // 设备无关的标准 203 尼特，或用户自己选定的自定义值。
        lines += "显示设备亮度能力（观看参考）：HDR 峰值 " +
            (display.peakNits?.let { "%.0f 尼特".format(it) } ?: "未声明") +
            "；最大帧平均亮度 " +
            (display.maxAverageNits?.let { "%.0f 尼特".format(it) } ?: "未声明") +
            "；当前 HDR 强度 %.2f×".format(strength) +
            "；本次导出漫反射白 %.0f 尼特".format(
                FableSolTuning.exportPqWhiteNits(context)
            ) +
            when (FableSolTuning.exportPqWhiteMode(context)) {
                FableSolExportPqWhiteMode.STANDARD -> "（标准值）。"
                FableSolExportPqWhiteMode.CUSTOM -> "（自定义值）。"
            }
        lines += "EGL 能力：FP16 场景缓冲 " + supportState(egl.linearSceneSupported) +
            "；BT.2020 PQ " + supportState(egl.bt2020PqSupported) +
            "；BT.2020 HLG " + supportState(egl.bt2020HlgSupported) +
            // 广告了色彩空间不代表建得起 10-bit 表面：华为平板两者都有，却整机卡在这一步。
            "；10-bit 窗口配置：" + (egl.tenBitWindowConfig ?: "未发现") +
            // recordable 决定缓冲带不带视频编码器用途位，少了它编码器消费不了这些缓冲。
            "（共 ${egl.tenBitWindowConfigCount} 个，其中带 recordable " +
            "${egl.tenBitRecordableConfigCount} 个）。"
        lines += "编码器能力：" + encoderSummary(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            "HEVC Main10",
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
        )
        lines += "编码器能力：" + encoderSummary(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            "HEVC HDR10+",
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
        )
        lines += "编码器能力：" + encoderSummary(
            MediaFormat.MIMETYPE_VIDEO_AV1,
            "AV1 Main10",
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
        )
        lines += tenBitSurfaceLines()
        // HLG super-white 是**逐编码通路**的结论，不是设备级布尔量（D140）：同一台机器上
        // P010 与 Surface、HEVC 与杜比视界都可能给出不同的余量。没有条目表示还没跑过验证。
        val hlgRanges = FableSolExportHlgVerification.diagnosticLines(context)
        lines += "HLG 扩展信号范围（已验证的编码通路）：" +
            if (hlgRanges.isEmpty()) "尚未验证。" else ""
        lines += hlgRanges
        val dolbyVisionName = context.getString(
            R.string.fablesol_export_hdr_brand_dolby_vision
        )
        lines += "编码器能力：" + encoderSummary(
            MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION,
            dolbyVisionName,
            0
        )
        dolbyVisionProfiles()?.let {
            lines += "  · $dolbyVisionName Profile：$it"
            vendorParameters(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION)?.let { params ->
                lines += "  · $dolbyVisionName 厂商参数：$params"
            }
        }
        // HDR10+ 走 surface 输入拿不到，但字节缓冲输入是另一条路（也是 AOSP CTS 用的那条）。
        // 只在它当前不可用时才探——已经能用就没有这个问题了。
        if (!modeFormats.contains(FableSolExportHdrFormat.HDR10_PLUS)) {
            lines += hdr10PlusByteBufferReport(context)
        }
        val runtimeAttempts = FableSolExportRuntimeDiagnostics.lines()
        lines += "最近一次正式导出尝试链：" +
            if (runtimeAttempts.isEmpty()) "当前进程内无记录。" else ""
        lines += runtimeAttempts.map { "  · $it" }
        return lines.joinToString(System.lineSeparator())
    }

    /**
     * 字节缓冲输入这条路上，HDR10+ 到底成不成立。
     *
     * 分别验证不提交动态元数据与提交 ST 2094-40 元数据两种条件，以区分基础编码通路能力
     * 与动态元数据提交能力。
     */
    private fun hdr10PlusByteBufferReport(context: Context): String {
        val plan = FableSolExportSpec.plan(context, FableSolExportSpec.MAX_CARD_WIDTH_DP)
        val result = try {
            FableSolHdr10PlusProbe.run(
                widthPx = FableSolExportTier.alignForEncoder(plan.canvasWidthPx, 2),
                heightPx = FableSolExportTier.alignForEncoder(plan.canvasHeightPx, 2),
                frameRate = FableSolExportOptions.FRAME_RATE_BASE
            )
        } catch (error: Throwable) {
            return "HDR10+ 字节缓冲验证：探测异常（${error.javaClass.simpleName}）。"
        }
        if (result.codecName == null) {
            return "HDR10+ 字节缓冲验证：${result.withoutMetadata}。"
        }
        return "HDR10+ 字节缓冲验证（${result.codecName}）：P010 输入" +
            supportState(result.p010Supported) +
            "；未提交动态元数据：${result.withoutMetadata}；提交 ST 2094-40 元数据：" +
            "${result.withMetadata}。"
    }

    /**
     * 直接问编码器它自己有哪些**厂商私有参数**（API 31 起的 `getSupportedVendorParameters`）。
     *
     * 之所以要问：杜比视界 8.1 卡在编码器把 PQ 基层改回 HLG，而 Android 的公开接口里没有
     * "选 8.1 还是 8.4"这样一个键。厂商文档抓不到（Qualcomm 的文档站是 JS 渲染的），与其
     * 照着猜键名，不如让设备把自己的旋钮列出来——有就试，没有就是真没有。
     *
     * 只留名字里带 dv / dolby / hdr / profile / color / transfer 的，否则会刷屏。
     */
    private fun vendorParameters(mime: String): String? {
        if (Build.VERSION.SDK_INT < 31) return null
        val name = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }?.name
        } catch (ignored: Throwable) {
            null
        } ?: return null
        var codec: android.media.MediaCodec? = null
        return try {
            codec = android.media.MediaCodec.createByCodecName(name)
            val interesting = codec.supportedVendorParameters.filter { parameter ->
                VENDOR_PARAMETER_HINTS.any { parameter.contains(it, ignoreCase = true) }
            }
            when {
                interesting.isNotEmpty() -> interesting.joinToString(", ")
                codec.supportedVendorParameters.isEmpty() -> "未声明"
                else -> "共 ${codec.supportedVendorParameters.size} 项，未发现与 HDR 相关的参数"
            }
        } catch (ignored: Throwable) {
            null
        } finally {
            try {
                codec?.release()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * 这一格式**全部**可用的编码器与各自能达到的最高帧率。
     *
     * 必须列全。只报第一个落点会漏掉真实存在的选择：OPPO 上 HLG 的 HEVC 与 AV1 两条路都
     * 成立，报告却只写了 HEVC（2026-07-27）。顺序即编码阶梯顺序，因此第一项就是自动档在
     * 不考虑软件实现时的落点。
     */
    private fun combinationSummary(
        format: FableSolExportHdrFormat?,
        rateControl: FableSolExportRateControl
    ): String {
        val reach = lastMatrix.reach(format, rateControl)
        if (reach.isEmpty()) return "无可用组合"
        return reach.joinToString("、") {
            // 写阶梯项名而不只是编码器族名：同一族里 10-bit 与 8-bit 是两个阶梯项，只写
            // “HEVC”就分不出这台机器是 10-bit 编不了，还是仅仅 HDR 信号编不了。
            "${it.displayLabel} ${it.frameRate} fps" +
                if (it.softwareOnly) "（软件编码）" else ""
        }
    }

    /**
     * 被淘汰的编码器组合，按失败原因归并。
     *
     * 这几行是这次最需要的东西。此前只有**整种格式都编不出来**时才留失败原因：三星 Z Fold4
     * 上 HDR10 靠软件 AV1 通过了，于是四个高通 HEVC 候选的报错被整批丢掉，SDR 那一列更是
     * 连报告都没有——而"H.264 也没能通过"恰恰是判断问题出在设备还是出在本应用的关键
     * （2026-07-27）。同一条原因通常横跨多个组合，所以按原因归并，避免刷屏。
     */
    private fun rejectedCombinationLines(
        context: Context,
        rateControl: FableSolExportRateControl
    ): List<String> {
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for (format in FableSolExportHdrFormat.SELECTABLE_ORDER + listOf(null)) {
            for (family in FableSolExportTier.familiesFor(format)) {
                for (tenBit in FableSolExportCapabilityMatrix.BIT_DEPTHS) {
                    if (!FableSolExportTier.supportsBitDepth(format, tenBit)) continue
                    // "有候选但没编出来"与"压根没有候选"必须都报，且要分得开：前者是编码器
                    // 拒绝了这份 MediaFormat，后者是它在这个画布尺寸或帧率上就没有候选。三星
                    // Z Fold4 上这两种情形的现象一模一样（编码器清单里有、却选不了），不加区分
                    // 就无从判断问题出在哪一步。
                    val outcomes = FableSolExportCapabilityMatrix.FRAME_RATES.mapNotNull { rate ->
                        lastMatrix.outcome(
                            format,
                            family,
                            rate,
                            tenBit,
                            rateControl
                        )?.takeIf { !it.usable }
                    }
                    if (outcomes.size < FableSolExportCapabilityMatrix.FRAME_RATES.size) continue
                    val failure = outcomes.mapNotNull { it.failure }
                        .firstOrNull { it.code != FableSolExportFailure.Code.NO_CANDIDATE }
                        ?: FableSolExportFailure.NO_CANDIDATE
                    val label = FableSolExportHdrFormat.localizeStableLabels(
                        context, FableSolExportCapabilityMatrix.labelOf(format)
                    ) + " / " + family.stableLabel +
                        if (tenBit) " 10-bit" else " 8-bit"
                    grouped.getOrPut(describe(context, failure)) { ArrayList(4) } += label
                }
            }
        }
        if (grouped.isEmpty()) return emptyList()
        val lines = ArrayList<String>(grouped.size + 1)
        lines += "未通过验证的编码器组合："
        for ((reason, labels) in grouped.entries.take(MAX_REPORTED_FAILURES)) {
            lines += "  · " + labels.joinToString("、") + "：" + reason
        }
        if (grouped.size > MAX_REPORTED_FAILURES) {
            lines += "  · 另有 ${grouped.size - MAX_REPORTED_FAILURES} 类原因未列出。"
        }
        return lines
    }

    /**
     * 每个编码器对**本次实际画布**的尺寸与帧率答复。
     *
     * 候选收集用 `areSizeAndRateSupported` 做第一道筛，筛掉的候选连一次编码都不会发生，于是
     * 在报告里与"编出来失败了"长得一模一样。三星 Z Fold4 上编码器清单里明明有四个高通 HEVC
     * 和若干 H.264，却一个都选不了，必须先分清是这道筛拦下的还是编码器自己拒绝的
     * （2026-07-27）。这里直接把这道筛的输入与输出摆出来。
     */
    private fun sizeAndRateLines(context: Context): List<String> {
        val plan = FableSolExportSpec.plan(context, FableSolExportSpec.MAX_CARD_WIDTH_DP)
        val lines = ArrayList<String>(8)
        lines += "编码尺寸与帧率支持（画布 ${plan.canvasWidthPx}×${plan.canvasHeightPx}）："
        var reported = 0
        for (mime in SIZE_REPORT_MIMES) {
            for (info in encodersFor(mime)) {
                if (reported >= MAX_REPORTED_SIZE_ROWS) {
                    lines += "  · 其余编码器未列出。"
                    return lines
                }
                val video = try {
                    info.getCapabilitiesForType(mime).videoCapabilities
                } catch (ignored: Throwable) {
                    null
                } ?: continue
                val width = FableSolExportTier.alignForEncoder(
                    plan.canvasWidthPx, video.widthAlignment
                )
                val height = FableSolExportTier.alignForEncoder(
                    plan.canvasHeightPx, video.heightAlignment
                )
                lines += "  · ${info.name}：对齐后 ${width}×$height；120 fps " +
                    sizeAndRateState(video, width, height, FableSolExportOptions.FRAME_RATE_HIGH) +
                    "；60 fps " +
                    sizeAndRateState(video, width, height, FableSolExportOptions.FRAME_RATE_BASE) +
                    "；" + sizeLimits(video)
                reported++
            }
        }
        return lines
    }

    /**
     * 编码器自己对"能不能吃一张 10 位 HDR 表面"的回答。
     *
     * **只作记录，不作门禁。** 这几位与真实编码结论对不上的情况是存在的，所以判定仍然只认
     * 真编一帧；但把它们摆出来，"HDR 编不出来"就从一句现象变成了可核对的结论。
     *
     * - `COLOR_Format32bitABGR2101010`：surface 模式下 10 位输入的像素格式。编码器不接受它，
     *   就意味着这条路从公开接口上根本不通。
     * - `hdr-editing` / `hlg-editing`：平台为"拿一张 10 位 HDR 表面来编码"这件事定义的能力位。
     */
    @SuppressLint("InlinedApi")
    private fun tenBitSurfaceLines(): List<String> {
        val lines = ArrayList<String>(4)
        for (mime in listOf(
            MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AV1
        )) {
            for (info in encodersFor(mime)) {
                val capabilities = try {
                    info.getCapabilitiesForType(mime)
                } catch (ignored: Throwable) {
                    continue
                }
                val tenBitSurface = capabilities.colorFormats.any {
                    it == MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR2101010
                }
                // 广告了 Main10 才值得报告；连 Main10 都没有的实现不在这条路上。
                if (capabilities.profileLevels.none { level ->
                        level.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                            level.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                    }
                ) continue
                lines += "  · ${info.name}：ABGR2101010 输入 " + supportState(tenBitSurface) +
                    "；HDR 编辑 " + featureState(capabilities, FEATURE_HDR_EDITING, 33) +
                    "；HLG 编辑 " + featureState(capabilities, FEATURE_HLG_EDITING, 34)
                if (lines.size >= MAX_REPORTED_SIZE_ROWS) break
            }
        }
        if (lines.isEmpty()) return emptyList()
        return listOf("10-bit 表面编码能力（仅作记录，判定仍以真实编码为准）：") + lines
    }

    private fun featureState(
        capabilities: MediaCodecInfo.CodecCapabilities,
        feature: String,
        minimumSdk: Int
    ): String = when {
        Build.VERSION.SDK_INT < minimumSdk -> "系统版本不适用"
        else -> try {
            supportState(capabilities.isFeatureSupported(feature))
        } catch (ignored: Throwable) {
            "查询异常"
        }
    }

    private fun encodersFor(mime: String): List<MediaCodecInfo> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    } catch (ignored: Throwable) {
        emptyList()
    }

    private fun sizeAndRateState(
        video: MediaCodecInfo.VideoCapabilities,
        width: Int,
        height: Int,
        frameRate: Int
    ): String = try {
        supportState(video.areSizeAndRateSupported(width, height, frameRate.toDouble()))
    } catch (error: Throwable) {
        // 这个查询本身抛异常时，候选收集会把它当成"不支持"静默吞掉。
        "查询异常（${error.javaClass.simpleName}）"
    }

    private fun sizeLimits(video: MediaCodecInfo.VideoCapabilities): String = try {
        "尺寸范围 ${video.supportedWidths.lower}–${video.supportedWidths.upper} × " +
            "${video.supportedHeights.lower}–${video.supportedHeights.upper}；对齐 " +
            "${video.widthAlignment}×${video.heightAlignment}"
    } catch (ignored: Throwable) {
        "尺寸范围未声明"
    }

    private fun supportState(value: Boolean): String = if (value) "支持" else "不支持"

    /**
     * 把结构化失败原因翻成**当前 locale** 的说明。
     *
     * 缓存里只有 [FableSolExportFailure] 这样的稳定标识与厂商原文；句子在展示的这一刻才
     * 生成，因此用户换了系统语言之后，同一份缓存仍然给出当前语言的说明。
     */
    private fun describe(context: Context, failure: FableSolExportFailure): String =
        when (failure.code) {
            FableSolExportFailure.Code.NO_CANDIDATE -> NO_CANDIDATE_REASON
            FableSolExportFailure.Code.MISSING_EGL_COLOR_SPACE ->
                "EGL 未提供目标格式所需的 ${transferCodeName(failure.requested)} " +
                    "窗口色彩空间，未执行编码验证"
            FableSolExportFailure.Code.TRANSFER_MISMATCH -> {
                val requested = failure.requested
                val actual = failure.actual
                "编码器输出的传递函数与请求值不一致（请求 ${transferCodeName(requested)}，" +
                    "实际 ${transferCodeName(actual)}），目标格式验证未通过"
            }
            FableSolExportFailure.Code.PROFILE_MISMATCH ->
                "编码器输出的 Profile 与请求值不一致（请求 ${failure.requested}，实际 " +
                    "${failure.actual}），目标格式验证未通过"
            FableSolExportFailure.Code.FINISH_TIMEOUT -> "编码与封装流程未在限定时间内完成"
            FableSolExportFailure.Code.NO_VIDEO_SAMPLES -> "编码器未产出任何视频样本，产物为空"
            FableSolExportFailure.Code.HDR10_PLUS_SEI_MISSING ->
                "输出码流未检测到 HDR10+ ST 2094-40 SEI"
            FableSolExportFailure.Code.HDR_VIVID_SEI_MISSING ->
                "输出码流没有逐访问单元携带 HDR Vivid T/UWA 005 动态元数据"
            FableSolExportFailure.Code.HDR_VIVID_CONTAINER_MISSING ->
                "短探测产物无法写入或验证 HDR Vivid cuvv 配置盒（" +
                    "${failure.detail.orEmpty()}）"
            FableSolExportFailure.Code.STATIC_METADATA_MISMATCH ->
                "短探测产物中的静态 HDR 元数据与应用生成的描述符冲突（" +
                    "${failure.detail.orEmpty()}）"
            FableSolExportFailure.Code.TEMPORARY_FILE ->
                "无法创建探测用临时文件（${failure.detail.orEmpty()}）"
            FableSolExportFailure.Code.MISSING_LINEAR_SCENE ->
                "GL 无法渲染 FP16 场景缓冲（缺 ${failure.detail.orEmpty()}）"
            FableSolExportFailure.Code.ALL_FORMATS_FAILED ->
                "所有 HDR 格式均未通过完整的单帧编码与封装验证。"
            FableSolExportFailure.Code.NO_FORMAT_CANDIDATE ->
                "未找到可执行完整 HDR 验证的格式或编码器候选。"
            FableSolExportFailure.Code.PROBE_EXCEPTION ->
                "HDR 能力探测异常（${failure.detail.orEmpty()}）"
            FableSolExportFailure.Code.ENCODER_ERROR -> {
                val parts = ArrayList<String>(4)
                failure.detail?.let {
                    parts += FableSolExportHdrFormat.localizeStableLabels(context, it)
                }
                failure.diagnosticInfo?.let { parts += "诊断信息 $it" }
                parts += failure.errorCode?.let { "错误码 $it" } ?: "错误码未提供"
                if (failure.transient) parts += "瞬时错误（编码器当时被占用）"
                if (failure.recoverable) parts += "可恢复错误"
                "编码候选验证未通过（技术详情：" + parts.joinToString("；") + "）"
            }
        }

    /** 逐候选失败明细的一行：格式、档位、帧率与原因。 */
    private fun describeCandidateFailure(
        context: Context,
        record: FableSolExportCandidateFailure
    ): String {
        val format = FableSolExportHdrFormat.localizeStableLabels(context, record.formatLabel)
        val tier = record.tierLabel?.let {
            FableSolExportHdrFormat.localizeStableLabels(context, it)
        }
        val head = if (tier == null) {
            format
        } else {
            "$format ${record.frameRate} fps $tier"
        }
        // 传递函数或 Profile 被改写时，补一句该格式的验证范围说明：光看"不一致"分不出这是
        // 编码器不做这种格式，还是我们的验证范围有遗漏。
        val followUp = when (record.failure.code) {
            FableSolExportFailure.Code.TRANSFER_MISMATCH,
            FableSolExportFailure.Code.PROFILE_MISMATCH ->
                FableSolExportHdrFormat.fromStableLabel(record.formatLabel)?.validationFollowUp
            else -> null
        }
        return "$head：" + describe(context, record.failure) +
            (followUp?.let { "；$it" } ?: "") + "。"
    }

    private fun transferCodeName(value: Int?): String = when (value) {
        MediaFormat.COLOR_TRANSFER_ST2084 -> "PQ / ST 2084 ($value)"
        MediaFormat.COLOR_TRANSFER_HLG -> "HLG ($value)"
        MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR 视频 ($value)"
        MediaFormat.COLOR_TRANSFER_LINEAR -> "线性传递函数 ($value)"
        null -> "未知"
        else -> value.toString()
    }

    /**
     * 杜比视界编码器广告的 profile。存在编码器不等于第三方能用——授权与逐帧动态映射
     * 元数据都不经由公开 API——但先把它支持哪些 profile 摆出来，判断才有依据。
     */
    private fun dolbyVisionProfiles(): String? = try {
        val profiles = LinkedHashSet<Int>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, ignoreCase = true)
                }
            ) continue
            info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION)
                .profileLevels.forEach { profiles += it.profile }
        }
        if (profiles.isEmpty()) null else profiles.joinToString(", ") { describeDolbyProfile(it) }
    } catch (ignored: Throwable) {
        null
    }

    /** 把 DV profile 位值翻成人看得懂的名字；`256 = dvhe.st` 就是 profile 8 那一档。 */
    private fun describeDolbyProfile(profile: Int): String = when (profile) {
        0x1 -> "1 (dvav.per)"
        0x2 -> "2 (dvav.pen)"
        0x4 -> "3 (dvhe.der)"
        0x8 -> "4 (dvhe.den)"
        0x10 -> "5 (dvhe.dtr)"
        0x20 -> "6 (dvhe.stn)"
        0x40 -> "7 (dvhe.dth)"
        0x80 -> "7 (dvhe.dtb)"
        0x100 -> "8 (dvhe.st)"
        0x200 -> "9 (dvav.se)"
        0x400 -> "10 (dvav.110)"
        else -> profile.toString()
    }

    // 曾经有一个"问 MediaMuxer 收不收 video/dolby-vision 轨"的探测，已删除：它不构成证据。
    // 一台连 DV 编码器都没有的三星同样答"接受"，可见 addTrack 只认 MIME、不校验其余任何
    // 东西。现在杜比视界与其余格式一样走真实编码 + 封装的完整探测，那才是决定性的。

    private fun encoderSummary(mime: String, label: String, profile: Int): String {
        val names = ArrayList<String>(2)
        try {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                if (profile != 0) {
                    val capabilities = info.getCapabilitiesForType(mime)
                    if (capabilities.profileLevels.none { it.profile == profile }) continue
                }
                names += info.name
            }
        } catch (ignored: Throwable) {
        }
        return if (names.isEmpty()) {
            "$label：未发现"
        } else {
            "$label：" + names.joinToString(", ")
        }
    }

    /** 把缓存里存下来的诊断细节放回可读字段，使命中缓存时设置页照样有原因可显示。 */
    private fun restoreDiagnostics(cached: CachedResult) {
        lastSupportedFormats = cached.formats.mapNotNull {
            FableSolExportHdrFormat.fromStableLabel(it)
        }
        lastFailureReason = FableSolExportFailure.decode(cached.reason)
        lastCandidateFailures = cached.failures.mapNotNull {
            FableSolExportCandidateFailure.decode(it)
        }
        lastMatrix = FableSolExportCapabilityMatrix.decode(cached.matrix)
    }

    private fun probeInternal(context: Context): Boolean {
        // **每一轮开头都要清空**：否则任何一条早退路径（或异常）都会让上一次的格式列表活
        // 下来，`supportedFormats()` 于是返回一份与本次结论无关的旧清单，缓存还会把它存进去。
        lastSupportedFormats = emptyList()
        lastCandidateFailures = emptyList()
        lastMatrix = FableSolExportCapabilityMatrix.EMPTY
        lastFailureReason = null
        val failures = ArrayList<FableSolExportCandidateFailure>(6)
        val matrix = FableSolExportCapabilityMatrix.Builder()
        val eglCapability = FableSolExportEgl.probe()
        linearSceneCache = eglCapability.linearSceneSupported
        if (!eglCapability.linearSceneSupported) {
            lastFailureReason = FableSolExportFailure(
                code = FableSolExportFailure.Code.MISSING_LINEAR_SCENE,
                detail = "GL_EXT_color_buffer_half_float"
            )
            return false
        }
        // 注意这里**不能**因为"两种 EGL 色彩空间都没有"就直接判死：走字节缓冲的档
        // （HDR10+）根本不用窗口色彩空间，PQ 编码由导出 shader 自己完成。

        // 编码模式是可行性的一条独立轴（D183）。CQ 与目标码率下发不同的 MediaFormat，
        // 必须分别短探测并同时写进一份矩阵；否则切换模式后只能继续使用上一模式的结论。
        val baseOptions = FableSolExportOptions(
            frameRate = FableSolExportOptions.FRAME_RATE_HIGH,
            colorMode = FableSolExportColorMode.HDR_AUTO,
            sdrMapping = FableSolExportSdrMapping.DEFAULT,
            sdrBitDepth = FableSolExportSdrBitDepth.AUTO,
            hlgSignalRange = FableSolExportHlgSignalRange.DEFAULT,
            rateControl = FableSolExportRateControl.DEFAULT,
            qualityBySignature = emptyMap(),
            pendingLegacyQuality = null,
            bitrateMbps = FableSolExportOptions.DEFAULT_BITRATE_MBPS,
            keyframeIntervalSeconds = FableSolExportOptions.DEFAULT_KEYFRAME_SECONDS
        )
        val basePlan = FableSolExportSpec.plan(
            context,
            FableSolExportSpec.MAX_CARD_WIDTH_DP
        )
        val availableTransfers = eglCapability.availableHdrTransfers()
        val supported = ArrayList<FableSolExportHdrFormat>(
            FableSolExportHdrFormat.SELECTABLE_ORDER.size
        )

        // 每一种格式都要**单独走完一遍真实编码**，不能一成功就收工：设置页要摆出全部可选
        // 项，而"HDR10 能编"完全不蕴含"HDR10+ 或杜比视界也能编"。SDR（列表末尾的 null）
        // 同样要探——关掉 HDR 之后编码器仍然要选，那一列不能是空的。
        val probeFormats: List<FableSolExportHdrFormat?> =
            FableSolExportHdrFormat.SELECTABLE_ORDER + listOf(null)
        for (format in probeFormats) {
            var formatOk = false
            val formatFailures = ArrayList<FableSolExportCandidateFailure>(4)
            for (rateControl in FableSolExportRateControl.entries) {
                if (
                    probeFormatMode(
                        context = context,
                        format = format,
                        rateControl = rateControl,
                        baseOptions = baseOptions,
                        basePlan = basePlan,
                        availableTransfers = availableTransfers,
                        matrix = matrix,
                        formatFailures = formatFailures
                    )
                ) {
                    formatOk = true
                }
            }
            if (format == null) continue
            if (formatOk) {
                supported += format
                continue
            }
            // 每种格式只留**第一条**失败原因：同一格式下各编码器的报错通常一模一样，
            // 全列出来会把另外三种格式的原因挤出可见范围，而那才是用户想看的。
            failures += formatFailures.firstOrNull() ?: FableSolExportCandidateFailure(
                formatLabel = format.stableLabel,
                tierLabel = null,
                frameRate = 0,
                failure = FableSolExportFailure.NO_CANDIDATE
            )
        }

        lastSupportedFormats = supported
        lastMatrix = matrix.build()
        if (supported.isNotEmpty()) {
            lastFailureReason = null
            // 有格式可用时仍然保留失败明细：知道 HDR10 通了而杜比视界卡在哪，比只知道
            // "有 HDR"有用得多。
            lastCandidateFailures = failures
            return true
        }
        lastCandidateFailures = failures
        lastFailureReason = FableSolExportFailure(
            code = if (failures.isEmpty()) {
                FableSolExportFailure.Code.NO_FORMAT_CANDIDATE
            } else {
                FableSolExportFailure.Code.ALL_FORMATS_FAILED
            }
        )
        return false
    }

    /**
     * 探测一种格式在一个用户可见编码模式下的全部精确组合。
     *
     * 编码模式不能只作为候选排序提示：CQ 档没有质量区间时必须判该五元组不可用，不能继续
     * 用同一编码器的 VBR 能力填表（D183）。
     */
    private fun probeFormatMode(
        context: Context,
        format: FableSolExportHdrFormat?,
        rateControl: FableSolExportRateControl,
        baseOptions: FableSolExportOptions,
        basePlan: FableSolExportPlan,
        availableTransfers: Collection<FableSolExportTransfer>,
        matrix: FableSolExportCapabilityMatrix.Builder,
        formatFailures: MutableList<FableSolExportCandidateFailure>
    ): Boolean {
        var modeOk = false
        // 编码器族与位深都是独立的轴：某一族能编不代表另一族也能，10-bit 通过也不蕴含
        // 8-bit 通过（反之亦然）。界面要按用户当前的选择逐项置灰，所以不能一成功就跳出。
        for (family in FableSolExportTier.familiesFor(format)) {
            // 重试只值得做一次。同一族里第一个候选重试之后仍然失败，就说明这不是编码器
            // 当时被占用，而是这条路本来就不通。
            var retryBudget = 1
            for (tenBit in FableSolExportCapabilityMatrix.BIT_DEPTHS) {
                if (!FableSolExportTier.supportsBitDepth(format, tenBit)) continue
                var familyWinner: FableSolExportTier? = null
                var familyWinnerSiting: FableSolExportChromaSiting.Result? = null
                var familyWinnerForm: FableSolExportRateControlForm? = null
                var familyWinnerComplexity: Boolean? = null
                for (frameRate in FableSolExportCapabilityMatrix.FRAME_RATES) {
                    val options = baseOptions.copy(
                        frameRate = frameRate,
                        rateControl = rateControl
                    )
                    val candidates = FableSolExportTier.candidatesForMode(
                        format = format,
                        widthPx = basePlan.canvasWidthPx,
                        heightPx = basePlan.canvasHeightPx,
                        frameRate = frameRate,
                        tenBit = tenBit,
                        preferConstantQuality = options.prefersConstantQuality,
                        family = family,
                        allowSoftware = true,
                        customBitrateMbps = options.bitrateMbps,
                        bFrames = options.bFramesEnabled,
                        complexFrameGuard = options.complexFrameGuardEnabled
                    )
                    // 高帧率通过蕴含低帧率通过，但只对**同一实现**成立（D179 修订，
                    // 2026-07-30）：先纯枚举本帧率的候选（不编码），首位与高帧率赢家是同一
                    // 编码器才承袭实测结论。分歧意味着本帧率有排序更优的实现——典型是硬件
                    // 编码器只支持到 60 fps、120 fps 由软件实现扛下的设备：60 fps 行若承袭
                    // 软件赢家，设置页与行内按 codecName 绑定的形态/复杂度结论都会指向错误
                    // 的实现——此时本帧率单独实测。
                    val settled = familyWinner
                    if (settled != null &&
                        candidates.firstOrNull()?.codecName == settled.codecName
                    ) {
                        matrix.put(
                            format,
                            family,
                            frameRate,
                            tenBit,
                            rateControl,
                            usable(
                                settled, familyWinnerSiting, familyWinnerForm,
                                familyWinnerComplexity
                            )
                        )
                        continue
                    }
                    if (settled != null) {
                        // 分歧成立：本行的结论只能来自本帧率的实测。清掉高帧率赢家，
                        // 否则本帧率所有候选都失败时，行尾的 familyWinner 兜底会把它
                        // 重新写进本行。它本身通常也在 candidates 里，会以普通候选身份
                        // 在本帧率被重新实测。
                        familyWinner = null
                        familyWinnerSiting = null
                        familyWinnerForm = null
                        familyWinnerComplexity = null
                    }
                    val usableCandidates = candidates.filter {
                        !it.requiresEglColorSpace || availableTransfers.contains(it.transfer)
                    }
                    if (usableCandidates.isEmpty()) {
                        val failure = if (candidates.isEmpty()) {
                            FableSolExportFailure.NO_CANDIDATE
                        } else {
                            FableSolExportFailure(
                                code = FableSolExportFailure.Code.MISSING_EGL_COLOR_SPACE,
                                requested = format?.transfer?.mediaFormatTransfer
                            )
                        }
                        if (failure != FableSolExportFailure.NO_CANDIDATE) {
                            formatFailures += FableSolExportCandidateFailure(
                                formatLabel = FableSolExportCapabilityMatrix.labelOf(format),
                                tierLabel = null,
                                frameRate = frameRate,
                                failure = failure
                            )
                        }
                        matrix.put(
                            format,
                            family,
                            frameRate,
                            tenBit,
                            rateControl,
                            unusable(failure)
                        )
                        continue
                    }
                    var firstFailure: FableSolExportFailure? = null
                    for (tier in usableCandidates) {
                        var failure: ProbeFailure? = null
                        // 两级阶梯，都是"同一档位的兼容形态"，不构成候选轴：
                        // 外层是 D149 的复杂度阶梯（带 KEY_COMPLEXITY=upper → 省略该键），
                        // 内层是 D167 的码控阶梯（纯 CQ → CQ+码率提示）。
                        ladder@ for (complexity in complexityLadder(tier)) {
                            for (form in formLadder(options, tier)) {
                                failure = probeCandidate(
                                    context, options, frameRate, tier, form, complexity
                                )
                                if (failure?.retryable == true && retryBudget > 0) {
                                    retryBudget--
                                    settle(RETRY_DELAY_MS)
                                    failure = probeCandidate(
                                        context,
                                        options,
                                        frameRate,
                                        tier,
                                        form,
                                        complexity
                                    )
                                }
                                if (failure == null) {
                                    lastProbeRateControlForm = form
                                    lastProbeHighComplexity = complexity
                                    break@ladder
                                }
                                settle(CANDIDATE_SETTLE_MS)
                            }
                        }
                        if (failure != null) settle(CANDIDATE_SETTLE_MS)
                        if (failure == null) {
                            familyWinner = tier
                            familyWinnerSiting = lastProbeChromaSiting
                            familyWinnerForm = lastProbeRateControlForm
                            familyWinnerComplexity = lastProbeHighComplexity
                            break
                        }
                        if (firstFailure == null) firstFailure = failure.failure
                        formatFailures += FableSolExportCandidateFailure(
                            formatLabel = FableSolExportCapabilityMatrix.labelOf(format),
                            tierLabel = tier.label,
                            frameRate = frameRate,
                            failure = failure.failure
                        )
                    }
                    val winner = familyWinner
                    if (winner == null) {
                        matrix.put(
                            format,
                            family,
                            frameRate,
                            tenBit,
                            rateControl,
                            unusable(firstFailure ?: FableSolExportFailure.NO_CANDIDATE)
                        )
                    } else {
                        matrix.put(
                            format,
                            family,
                            frameRate,
                            tenBit,
                            rateControl,
                            usable(
                                winner, familyWinnerSiting, familyWinnerForm,
                                familyWinnerComplexity
                            )
                        )
                        modeOk = true
                    }
                }
            }
        }
        return modeOk
    }

    /**
     * 把异常翻成一句真的有内容的失败原因。
     *
     * `MediaCodec.CodecException` 继承自 `IllegalStateException`，而它的 `message` 经常是空的
     * ——三星 Z Fold4 上诊断里那句 `IllegalStateException:` 后面什么都没有，等于什么也没说。
     * 真正有用的是它自带的 `diagnosticInfo`（厂商错误串）与 `errorCode`，以及"是否瞬时"这个
     * 判断：瞬时错误意味着编码器只是当时被占用，不是这台机器不支持。
     */
    private fun describeFailure(error: Throwable): FableSolExportFailure {
        val name = error.javaClass.simpleName
        // 平台的异常消息里带换行的情况不少，原样拼进报告会把版式撑散。
        val message = error.message
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val detail = if (message == null) name else "$name: $message"
        // 传递函数与 Profile 被改写是**结构化**结论，不该只留一句英文断言：设置页要按
        // "请求 X、实际 Y"分别说明，缓存里也得能在换语言之后重新生成那句话。
        message?.let { text ->
            TRANSFER_CHANGE_REGEX.find(text)?.let { match ->
                return FableSolExportFailure(
                    code = FableSolExportFailure.Code.TRANSFER_MISMATCH,
                    detail = detail,
                    requested = match.groupValues[1].toIntOrNull(),
                    actual = match.groupValues[2].toIntOrNull()
                )
            }
            PROFILE_CHANGE_REGEX.find(text)?.let { match ->
                return FableSolExportFailure(
                    code = FableSolExportFailure.Code.PROFILE_MISMATCH,
                    detail = detail,
                    requested = match.groupValues[1].toIntOrNull(),
                    actual = match.groupValues[2].toIntOrNull()
                )
            }
        }
        if (error is android.media.MediaCodec.CodecException) {
            return FableSolExportFailure(
                code = FableSolExportFailure.Code.ENCODER_ERROR,
                detail = detail,
                diagnosticInfo = error.diagnosticInfo.takeIf { it.isNotBlank() },
                errorCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    error.errorCode
                } else {
                    null
                },
                transient = error.isTransient,
                recoverable = error.isRecoverable
            )
        }
        return FableSolExportFailure(
            code = FableSolExportFailure.Code.ENCODER_ERROR,
            detail = detail
        )
    }

    /**
     * 这次失败是不是"编码器被夺走"而不是"这份 MediaFormat 不行"。
     *
     * `CodecException.isTransient` 是平台的正式信号，但**它覆盖不全**：三星 Z Fold4 上 HDR
     * 各档抛的是**普通** `IllegalStateException`，消息为
     * `Invalid to call at Released state; only valid in executing state` 与
     * `Pending dequeue output buffer request cancelled`——诊断里没有诊断串与错误码，说明它
     * 根本不是 `CodecException`，于是 `isTransient` 那条路一次都没走到（2026-07-27）。
     *
     * 这两句描述的都是**状态**问题：编码器在使用途中被释放、排队中的请求被取消。所以这里
     * 按消息里的状态特征补judgement。本项目自己的校验失败（`check` / `error` 抛出的那些）
     * 消息是另一套措辞，不会命中。
     */
    private fun isRetryableCodecError(error: Throwable): Boolean {
        if (error is android.media.MediaCodec.CodecException) {
            return error.isTransient || error.isRecoverable
        }
        if (error !is IllegalStateException) return false
        val message = error.message ?: return false
        return RETRYABLE_STATE_MARKERS.any { message.contains(it, ignoreCase = true) }
    }

    private fun unusable(failure: FableSolExportFailure) = FableSolExportCombinationOutcome(
        codecName = null,
        softwareOnly = false,
        failure = failure
    )

    private fun usable(
        tier: FableSolExportTier,
        chromaSiting: FableSolExportChromaSiting.Result? = null,
        rateControlForm: FableSolExportRateControlForm? = null,
        /** 探测通过时是否带着最高复杂度；null 表示探测没有尝试该键（D149）。 */
        highComplexity: Boolean? = null
    ) = FableSolExportCombinationOutcome(
        codecName = tier.codecName,
        softwareOnly = tier.softwareOnly,
        failure = null,
        profileLabel = tier.profileLabel,
        inputPathId = tier.inputPath.stableId,
        // 只记**码流真的声明了**的位置。未声明就该在导出时走 Type 0 兼容语义，把兼容默认值
        // 存成"探到的结论"会让两种情况再也分不开（D154 第 3 条）。
        chromaSitingId = chromaSiting?.takeIf { it.declared }?.siting?.stableId,
        // 正式导出必须与探测通过的**同一形态**（D167）：探一种、用另一种，等于没探。
        rateControlFormId = rateControlForm?.stableId,
        qualityLower = tier.qualityRange?.lower,
        qualityUpper = tier.qualityRange?.upper,
        // 复杂度阶梯的落点同理（D149）：只记真的尝试过该键的结论。探测基线固定高复杂度
        // 开启，因此"是否尝试过"只取决于编码器有没有公开复杂度区间。
        highComplexityFormId = when {
            tier.complexityRange == null -> null
            highComplexity == true -> FableSolExportCombinationOutcome.COMPLEXITY_UPPER
            highComplexity == false -> FableSolExportCombinationOutcome.COMPLEXITY_OMITTED
            else -> null
        }
    )

    /**
     * CQ 的同模式兼容阶梯（D167）：纯 CQ → CQ+码率提示。
     *
     * 非 CQ 档位只有一种形态。这里不把两种形态做成候选轴：它们的用户可见语义完全相同，
     * 做成候选会让排序、诊断和文件名都多出一条没有意义的分叉。
     */
    private fun formLadder(
        options: FableSolExportOptions,
        tier: FableSolExportTier
    ): List<FableSolExportRateControlForm> {
        val base = FableSolExportRateControlForm.resolve(options, tier)
        return if (base == FableSolExportRateControlForm.CONSTANT_QUALITY) {
            listOf(base, FableSolExportRateControlForm.CONSTANT_QUALITY_WITH_BITRATE_HINT)
        } else {
            listOf(base)
        }
    }

    /**
     * D149 的复杂度兼容阶梯：带 `KEY_COMPLEXITY = upper` → 省略该键。
     *
     * 只有编码器公开复杂度区间时第二级才有意义（否则该键本来就不会下发）；两级是同一档位
     * 的兼容形态，与 D167 的码控阶梯一样不构成候选轴。
     */
    private fun complexityLadder(tier: FableSolExportTier): List<Boolean> =
        if (tier.complexityRange != null) listOf(true, false) else listOf(true)

    /**
     * 一次候选验证的失败。
     *
     * [retryable] 来自 `MediaCodec.CodecException.isTransient` 或状态特征：编码器只是当时被
     * 占用，不代表这台机器不支持这一档。调用方据此重试而不是直接判死。
     */
    private data class ProbeFailure(
        val failure: FableSolExportFailure,
        val retryable: Boolean
    )

    /**
     * 最近一次成功探测读到的码流色度位置（D154、D170）。
     *
     * 探测本身是串行的（`probe` 上有 @Synchronized），所以用一个字段接住比把 `probeCandidate`
     * 的返回类型改成"失败或结论"更省事，也不会串。
     */
    private var lastProbeChromaSiting: FableSolExportChromaSiting.Result? = null

    /** 最近一次成功探测实际通过的码控形态（D167）；同上，探测串行，用字段接住即可。 */
    private var lastProbeRateControlForm: FableSolExportRateControlForm? = null

    /** 最近一次成功探测是否带着 `KEY_COMPLEXITY = upper`（D149）；同上串行接住。 */
    private var lastProbeHighComplexity: Boolean? = null

    private fun settle(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** @return null 表示这一档真的编出来了；否则是失败原因。 */
    private fun probeCandidate(
        context: Context,
        options: FableSolExportOptions,
        frameRate: Int,
        tier: FableSolExportTier,
        form: FableSolExportRateControlForm =
            FableSolExportRateControlForm.resolve(options, tier),
        /** 是否带 `KEY_COMPLEXITY = upper` 探测（D149 阶梯的当前级）。 */
        applyHighComplexity: Boolean = true
    ): ProbeFailure? {
        val temporary = try {
            File.createTempFile("fablesol-hdr-probe-", ".mp4", context.cacheDir)
        } catch (error: Throwable) {
            return ProbeFailure(
                FableSolExportFailure(
                    code = FableSolExportFailure.Code.TEMPORARY_FILE,
                    detail = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                ),
                retryable = false
            )
        }
        var encoder: FableSolExportEncoder? = null
        var egl: FableSolExportEgl? = null
        var bridge: FableSolExportP010Bridge? = null
        lastProbeChromaSiting = null
        return try {
            val hdrVivid = tier.hdrFormat == FableSolExportHdrFormat.HDR_VIVID
            val hdrVividPayload = if (hdrVivid) {
                // 短探测只验证结构能否贯通；纯黑帧用全零统计，四项值均符合 12-bit 语义。
                FableSolHdrVividMetadata.payload(
                    FableSolHdrVividStatistics(
                        minimumMaxRgbPq = 0,
                        averageMaxRgbPq = 0,
                        varianceMaxRgbPq = 0,
                        maximumMaxRgbPq = 0
                    )
                )
            } else {
                null
            }
            val muxer = MediaMuxer(
                temporary.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            encoder = FableSolExportEncoder(
                widthPx = tier.encodedWidthPx,
                heightPx = tier.encodedHeightPx,
                frameRate = frameRate,
                tier = tier,
                options = options,
                audioSampleRate = PROBE_AUDIO_SAMPLE_RATE,
                muxer = muxer,
                peakNits = FableSolHdrPolicy.MAX_STRENGTH *
                    FableSolExportTransfer.SDR_WHITE_NITS,
                // 探测帧是纯黑，内容亮度无从测起：用 D90 的理论回退，结构完整且不冒充实测。
                luminance = FableSolExportLuminanceStats.theoretical(
                    FableSolHdrPolicy.MAX_STRENGTH.toDouble()
                ),
                hdrVividInfo = hdrVividPayload?.let { payload ->
                    { _: Long -> payload }
                },
                form = form,
                applyHighComplexity = applyHighComplexity
            )
            // 输入通路必须按它正式导出时的样子探（D158）：应用自有 P010 与编码器 Surface
            // 是两条真正不同的链路，用其中一条去探另一条，探到的是另一件事。
            val byteBuffer = tier.usesAppP010
            val hdr10Plus = tier.hdrFormat == FableSolExportHdrFormat.HDR10_PLUS
            egl = FableSolExportEgl(
                if (byteBuffer) null else encoder.inputSurface,
                transfer = tier.transfer,
                // **必须跟着档位走。** 这里原本写死 true，于是 8-bit 档（H.264 与 HEVC Main）
                // 被拿一张 10-bit 输入表面去验证，而正式导出给它们的是 8-bit 表面——验的和
                // 用的不是同一条链路，验证结论也就不作数。
                tenBit = !tier.eightBit
            )
            encoder.start()

            // 能力探测只验证 HDR 编码链，不编译整套水体 shader 或分配完整场景缓冲；
            // FP16 扩展已经由 FableSolExportEgl.probe() 门控，正式导出仍会验证实际 scene targets。
            if (byteBuffer) {
                val active = FableSolExportP010Bridge(
                    assets = context.assets,
                    widthPx = tier.encodedWidthPx,
                    heightPx = tier.encodedHeightPx,
                    definition = FableSolExportP010Math.ColorDefinition.forTransfer(
                        tier.transfer
                    ),
                    // 探测这一帧的相位不重要（画面是纯黑），但它必须与正式导出走同一条代码
                    // 路径：相位是 uniform，走不通的是链路而不是某一个相位值。
                    chromaSiting = FableSolExportP010Math.ChromaSiting.COMPATIBLE_DEFAULT,
                    collectStats = hdr10Plus,
                    blueNoise = FableSolExportBlueNoise.load(context.assets)
                )
                bridge = active
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, active.presentFramebufferId)
                GLES30.glViewport(0, 0, tier.encodedWidthPx, tier.encodedHeightPx)
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                active.convert()
                val payload = if (hdr10Plus) {
                    check(active.stats() != null) {
                        "HDR10+ statistics unavailable: " +
                            (active.statsFailure ?: "unknown")
                    }
                    // 探测这一帧是纯黑，逐像素统计没有内容可测；用占位统计走一遍完整的载荷
                    // 构造与曲线求解，验证的是"这台机器能不能把 SEI 写进码流"这件事本身。
                    val probeStats = FableSolHdr10PlusStats.placeholder(
                        FableSolHdrPolicy.MAX_STRENGTH *
                            FableSolExportTransfer.SDR_WHITE_NITS /
                            FableSolExportTransfer.PQ_MAX_NITS
                    )
                    FableSolExportHdr10PlusMetadata.payload(
                        probeStats,
                        FableSolExportHdr10PlusCurve(
                            sourcePeakNits =
                                FableSolExportHdr10PlusCurve.sourcePeakNits(probeStats)
                        ).shapeForScene(probeStats),
                        FableSolExportHdr10PlusCurve.DEFAULT_TARGET_NITS
                    )
                } else {
                    null
                }
                encoder.queueVideoFrame(0L, payload) { buffer, layout ->
                    active.writeInto(buffer, layout)
                }
            } else {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, tier.encodedWidthPx, tier.encodedHeightPx)
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                check(egl.swapBuffers(0L)) { "HDR probe eglSwapBuffers failed" }
            }

            val silence = ShortArray(PROBE_AUDIO_SAMPLES)
            encoder.feedAudio(silence, silence.size)
            when {
                !encoder.finish(timeoutMs = PROBE_TIMEOUT_MS) -> ProbeFailure(
                    FableSolExportFailure(FableSolExportFailure.Code.FINISH_TIMEOUT),
                    retryable = false
                )
                // **拿到输出格式并走到 EOS 不等于编出了东西。** 华为平板上 10 位档一个样本都
                // 不产出，却照样"验证通过"，导出成 0 字节文件（2026-07-28）。
                encoder.videoSamplesWritten <= 0L -> ProbeFailure(
                    FableSolExportFailure(FableSolExportFailure.Code.NO_VIDEO_SAMPLES),
                    retryable = false
                )
                // 编出来了还不够：HDR10+ 要码流里真的带上那段 SEI 才算数。
                hdr10Plus && !encoder.hdr10PlusSeiSeen -> ProbeFailure(
                    FableSolExportFailure(
                        FableSolExportFailure.Code.HDR10_PLUS_SEI_MISSING
                    ),
                    retryable = false
                )
                hdrVivid && (
                    !encoder.hdrVividSeiSeen ||
                        encoder.hdrVividSeiSamples != encoder.videoSamplesWritten
                    ) -> ProbeFailure(
                    FableSolExportFailure(
                        code = FableSolExportFailure.Code.HDR_VIVID_SEI_MISSING,
                        detail = "${encoder.hdrVividSeiSamples}/" +
                            "${encoder.videoSamplesWritten}"
                    ),
                    retryable = false
                )
                else -> {
                    val vividContainerFailure = if (hdrVivid) {
                        try {
                            FableSolHdrVividMp4.patchInPlace(temporary)
                            null
                        } catch (error: Throwable) {
                            "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                        }
                    } else {
                        null
                    }
                    if (vividContainerFailure != null) {
                        ProbeFailure(
                            FableSolExportFailure(
                                code = FableSolExportFailure.Code.HDR_VIVID_CONTAINER_MISSING,
                                detail = vividContainerFailure
                            ),
                            retryable = false
                        )
                    } else {
                        // 静态元数据的逐字段回读核对定位在**短探测产物**上（D166）：这里是一个
                        // 单帧小文件，核对接近零成本；放到正式导出之后核对，一次失败就意味着把
                        // 整段渲染推倒重来，正是 D142 否定的行为。
                        val conflict = encoder.expectedStaticInfo()?.let {
                            FableSolExportStaticMetadataCheck.verify(temporary, it)
                        }
                        if (conflict != null) {
                            ProbeFailure(
                                FableSolExportFailure(
                                    code = FableSolExportFailure.Code.STATIC_METADATA_MISMATCH,
                                    detail = conflict
                                ),
                                retryable = false
                            )
                        } else {
                            // 这一档真的编出来了：把码流声明的色度位置带回去，正式导出据此选相位。
                            lastProbeChromaSiting = encoder.videoChromaSiting
                            null
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            // 这句就是我们要的东西：三星那台机器色彩空间与 Main10 编码器一应俱全却仍失败，
            // 只有异常本身能说清卡在 configure、EGL、start 还是输出格式核验。
            ProbeFailure(describeFailure(error), retryable = isRetryableCodecError(error))
        } finally {
            try {
                // bridge 的 GL 资源要在 EGL 上下文还在的时候释放。
                bridge?.release()
            } catch (ignored: Throwable) {
            }
            try {
                egl?.release()
            } catch (ignored: Throwable) {
            }
            try {
                encoder?.release()
            } catch (ignored: Throwable) {
            }
            temporary.delete()
        }
    }

    private fun readPersisted(context: Context): CachedResult? {
        return try {
            val preferences = context.applicationContext.getSharedPreferences(
                CACHE_PREFERENCES,
                Context.MODE_PRIVATE
            )
            val signature = preferences.getString(KEY_SIGNATURE, null)
            if (!preferences.contains(KEY_SUPPORTED) || signature == null) {
                null
            } else {
                CachedResult(
                    signature = signature,
                    supported = preferences.getBoolean(KEY_SUPPORTED, false),
                    checkedAtMs = preferences.getLong(KEY_CHECKED_AT_MS, 0L),
                    formats = preferences.getString(KEY_FORMATS, null)
                        ?.split(FAILURE_SEPARATOR)
                        ?.filter { it.isNotBlank() }
                        ?: emptyList(),
                    reason = preferences.getString(KEY_REASON, null),
                    failures = preferences.getString(KEY_FAILURES, null)
                        ?.split(FAILURE_SEPARATOR)
                        ?.filter { it.isNotBlank() }
                        ?: emptyList(),
                    matrix = preferences.getString(KEY_MATRIX, null)
                )
            }
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun persist(context: Context, result: CachedResult) {
        try {
            context.applicationContext.getSharedPreferences(
                CACHE_PREFERENCES,
                Context.MODE_PRIVATE
            ).edit()
                .putString(KEY_SIGNATURE, result.signature)
                .putBoolean(KEY_SUPPORTED, result.supported)
                .putLong(KEY_CHECKED_AT_MS, result.checkedAtMs)
                .putString(KEY_FORMATS, result.formats.joinToString(FAILURE_SEPARATOR))
                .putString(KEY_REASON, result.reason)
                .putString(KEY_FAILURES, result.failures.joinToString(FAILURE_SEPARATOR))
                .putString(KEY_MATRIX, result.matrix)
                .apply()
        } catch (ignored: Throwable) {
            // 缓存写入失败不改变本次实际探测结论；本进程仍会复用 processCache。
        }
    }

    /**
     * 缓存签名里**必须**有一个随每次 debug 发布自动变化的量。
     *
     * 原先用的是 `BuildConfig.VERSION_CODE`，但它在 `build.gradle` 里是写死的 43，两次
     * debug 发布之间根本不变——于是探测逻辑改了、旧结论却继续从缓存里读出来。2026-07-27
     * 实际发生过：删掉 `FEATURE_HlgEditing` 那道筛之后，设置页仍然显示改动前的
     * "HLG：没有编码器广告支持这个 profile"，连措辞都还是旧版的。
     *
     * `R.string.debug_update_code` 是发布任务生成的时间戳（未发布的本地构建为 "0"），
     * 每发一版就变一次，正是这里需要的东西。[PROBE_CONTRACT_VERSION] 保留，用来在本地
     * 反复构建时手动作废。
     */
    private fun cacheSignature(context: Context): String {
        val publishCode = try {
            context.applicationContext.getString(R.string.debug_update_code)
        } catch (ignored: Throwable) {
            "0"
        }
        // D183 起同一份矩阵同时包含 CQ 与目标码率两条轴，切换偏好不再使设备能力缓存失效。
        return "$PROBE_CONTRACT_VERSION|${BuildConfig.VERSION_CODE}|$publishCode|" +
            "${Build.VERSION.SDK_INT}|${Build.FINGERPRINT}"
    }

    /**
     * 缓存条目必须**连同诊断细节一起存**。
     *
     * 之前只存了 supported：命中缓存时 [probeInternal] 根本不执行，
     * [lastFailureReason] / [lastSupportedFormats] 也就永远是空的——设置页于是只能显示
     * 光秃秃的"可用 / 不可用"，最需要的那句原因一次都露不出来，而否定结果还有 24 小时
     * 有效期，等于这一天里都问不出所以然。
     */
    private data class CachedResult(
        val signature: String,
        val supported: Boolean,
        val checkedAtMs: Long,
        val formats: List<String> = emptyList(),
        val reason: String? = null,
        val failures: List<String> = emptyList(),
        /** 序列化后的可行组合表；见 [FableSolExportCapabilityMatrix.encode]。 */
        val matrix: String? = null
    ) {
        fun isValid(expectedSignature: String, nowMs: Long): Boolean {
            if (signature != expectedSignature) return false
            if (supported) return true
            val ageMs = nowMs - checkedAtMs
            return ageMs in 0..NEGATIVE_RESULT_TTL_MS
        }
    }

    /** 诊断行里最多列几条逐档失败原因；再多会把设置面板撑爆。 */
    private const val MAX_REPORTED_FAILURES = 4

    /** 尺寸与帧率答复最多列几行。 */
    private const val MAX_REPORTED_SIZE_ROWS = 6

    // 这两个能力位在 API 33 / 34 才有常量；只用于记录，不作门禁，所以按字面量读取。
    private const val FEATURE_HDR_EDITING = "hdr-editing"
    private const val FEATURE_HLG_EDITING = "hlg-editing"

    private val SIZE_REPORT_MIMES = listOf(
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaFormat.MIMETYPE_VIDEO_AV1
    )

    /**
     * `FableSolExportEncoder.validateVideoOutputFormat` 抛出的那两句英文断言。
     *
     * 命中即把失败归类为传递函数／Profile 被改写，并把申请值与实际值作为结构化字段存下来
     * ——诊断句子在展示时才按当前 locale 生成，缓存里不留整句。
     */
    private val PROFILE_CHANGE_REGEX = Regex("""changed profile (\d+) to (\d+)""")
    private val TRANSFER_CHANGE_REGEX =
        Regex("""changed color-transfer from (\d+) to (\d+)""")

    /** 厂商参数只留这些关键词命中的，否则一屏都放不下。 */
    private val VENDOR_PARAMETER_HINTS = listOf(
        "dv", "dolby", "hdr", "profile", "color", "transfer"
    )

    private const val PROBE_AUDIO_SAMPLE_RATE = 48_000
    private const val PROBE_AUDIO_SAMPLES = 2048
    private const val PROBE_TIMEOUT_MS = 3_000L
    /** 遇到"编码器被夺走"类错误后歇多久再重试一次，给硬件实例留出释放时间。 */
    private const val RETRY_DELAY_MS = 300L
    /** 任一候选失败后的沉降时间：失败往往把底层实例留在拆除中的状态。 */
    private const val CANDIDATE_SETTLE_MS = 80L

    /**
     * 消息里出现这些片段就说明编码器是被**释放/取消**掉的，不是这份 MediaFormat 不行。
     * 全部来自三星 Z Fold4 的实测（2026-07-27）。
     */
    private val RETRYABLE_STATE_MARKERS = listOf(
        "Released state",
        "request cancelled",
        "Insufficient resource",
        "reclaim"
    )
    // 2：缓存条目开始携带诊断细节；旧条目必须失效，否则依然问不出原因。
    // 3：色彩范围改为采纳编码器回报值，此前被误判为不支持的机器必须重新探测。
    // 4：改为逐格式探测（HDR10 / HDR10+ / HLG / 杜比视界各自编一帧），缓存结构随之改变。
    // 5：诊断改为结构化、正式措辞，并将格式稳定标识与本地化显示名称分离。
    // 6：改为逐 (格式 × 编码器族 × 帧率) 建可行组合表，SDR 一并纳入，并按用户实际编码模式验证。
    // 7：8-bit 档改用 8-bit 输入表面验证（此前写死 10-bit，验的与用的不是同一条链路），
    //    并对瞬时编码器错误重试一次；两者都会改变结论，旧缓存必须作废。
    // 8：记录通过验证的阶梯项名（区分 Main10 与 Main），并把"编码器被夺走"类错误纳入重试。
    // 9：EGL config 选择改为在 eglChooseConfig 落空后自行枚举，10-bit 档因此可能拿到带
    //    recordable 的 config，结论随之改变。
    // 10：加入"必须真的产出视频样本"这道门。此前只验到"拿到输出格式并走到 EOS"，一个样本都
    //     不产出的编码器照样判为通过（华为平板每一档 HEVC 都导出成 0 字节）。
    // 11：可行组合表加入位深轴（D160 的严格 10-bit / 8-bit 需要分别回答），杜比视界收敛为
    //     只剩 8.4（D141），失败原因改为结构化保存——整张表的结构与语义都变了。
    // 12：输入通路成为同一档位下的子候选（D158）——10-bit 先探应用自有 P010，再探同格式
    //     Surface；表里同时记下真正编出一帧的那一条通路与码流声明的色度位置（D154/D170）。
    //     探的链路变了，旧结论不能沿用。
    // 14：矩阵行新增 highComplexityFormId（D149 复杂度阶梯，评审 P20）。
    // 15：加入 HDR Vivid 显式格式；探测必须逐样本验证 T/UWA 005 SEI，并补写、回读 cuvv。
    // 16：双机实导证明 AOSP 软件 AV1 的 HDR VBR 即使 QP=1 仍会产生矩形块（D191）；
    //     有效能力不再包含 VBR，目标码率仅允许回退 CBR，旧矩阵不能继续复用。
    private const val PROBE_CONTRACT_VERSION = 16

    /** 结构上就没有候选时的固定说明；与编码器抛出的技术细节区分开。 */
    private const val NO_CANDIDATE_REASON =
        "未找到同时满足目标 Profile、画布尺寸和帧率要求的编码器候选"
    private const val NEGATIVE_RESULT_TTL_MS = 24L * 60L * 60L * 1_000L

    private const val CACHE_PREFERENCES = "fablesol_hdr_export_capability"
    private const val KEY_SIGNATURE = "signature"
    private const val KEY_SUPPORTED = "supported"
    private const val KEY_CHECKED_AT_MS = "checked_at_ms"
    private const val KEY_FORMATS = "formats"
    private const val KEY_REASON = "reason"
    private const val KEY_FAILURES = "failures"
    private const val KEY_MATRIX = "matrix"
    private const val FAILURE_SEPARATOR = "\u0001"
}
