package com.ywwynm.everythingdone.views.recording.fablesol

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

    /** 最近一次探测失败停在哪一步；null 表示成功或尚未探测。设置页据此告诉用户原因。 */
    @Volatile
    var lastFailureReason: String? = null
        private set

    /**
     * 逐个候选档位的真实失败原因（档位名 → 异常信息）。
     *
     * 只给"没有编码器能编出一帧"这一句是不够的：三星那台机器色彩空间、Main10 编码器一应
     * 俱全却仍然失败，卡在哪一步只有异常信息本身说得清。
     */
    @Volatile
    var lastCandidateFailures: List<String> = emptyList()
        private set

    /**
     * 最近一次探测中**真的编出了一帧**的格式，按 [FableSolExportHdrFormat.AUTO_ORDER] 排序。
     *
     * 设置页只允许用户选中这个列表里的格式。仅凭 `MediaCodecList` 广告是不够的：广告说有
     * Main10HDR10Plus 而 configure 时静默降成 Main10 的情况完全存在，那样界面上摆着一个
     * 名不副实的选项，比不给这个选项更糟。
     */
    @Volatile
    var lastSupportedFormats: List<FableSolExportHdrFormat> = emptyList()
        private set

    /** 「自动」当前会落到哪一种格式；没有可用格式时为 null。 */
    val autoFormat: FableSolExportHdrFormat?
        get() = lastSupportedFormats.firstOrNull()

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
            lastFailureReason = "HDR 能力探测异常（${error.javaClass.simpleName}：${
                error.message ?: "未提供详细信息"
            }）"
            false
        }
        val resolved = CachedResult(
            signature = cacheSignature(context),
            supported = supported,
            checkedAtMs = now,
            formats = lastSupportedFormats.map { it.stableLabel },
            reason = lastFailureReason,
            failures = lastCandidateFailures
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
        val supported = probe(context)
        val egl = FableSolExportEgl.probe()
        val lines = ArrayList<String>(8)
        lines += if (supported) {
            "HDR 导出能力：可用；单帧编码与封装验证通过：" +
                lastSupportedFormats.joinToString(" / ") { it.displayName(context) } +
                "；自动选择：" + (autoFormat?.displayName(context) ?: "—") + "。"
        } else {
            "HDR 导出能力：不可用。" + (
                lastFailureReason?.let {
                    " " + FableSolExportHdrFormat.localizeStableLabels(context, it)
                } ?: ""
                )
        }
        for (failure in lastCandidateFailures.take(MAX_REPORTED_FAILURES)) {
            lines += "  · " + FableSolExportHdrFormat.localizeStableLabels(
                context,
                failure
            )
        }
        val strength = FableSolTuning.hdrStrength(context)
        val white = FableSolExportDisplayLuminance.autoWhiteRecommendation(context, strength)
        lines += "显示设备亮度能力：HDR 峰值 " +
            (white.panelPeakNits?.let { "%.0f 尼特".format(it) } ?: "未声明") +
            "；最大帧平均亮度 " +
            (white.panelMaxAverageNits?.let { "%.0f 尼特".format(it) } ?: "未声明") +
            "；当前 HDR 强度 %.2f×".format(white.hdrStrength) +
            "；建议漫反射白 %.0f 尼特".format(white.whiteNits) +
            if (white.fallbackUsed) "（采用安全回退值）。" else "。"
        lines += "EGL 能力：FP16 场景缓冲 " + supportState(egl.linearSceneSupported) +
            "；BT.2020 PQ " + supportState(egl.bt2020PqSupported) +
            "；BT.2020 HLG " + supportState(egl.bt2020HlgSupported) +
            // 广告了色彩空间不代表建得起 10-bit 表面：华为平板两者都有，却整机卡在这一步。
            "；10-bit 窗口配置：" + (egl.tenBitWindowConfig ?: "未发现") + "。"
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
        if (!lastSupportedFormats.contains(FableSolExportHdrFormat.HDR10_PLUS)) {
            lines += hdr10PlusByteBufferReport(context)
        }
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

    private fun moreFailures(all: List<*>): String =
        if (all.size > 1) "；另有 ${all.size - 1} 个编码候选未通过验证" else ""

    private fun supportState(value: Boolean): String = if (value) "支持" else "不支持"

    private fun transferMismatchDescription(detail: String): String {
        val values = TRANSFER_CHANGE_REGEX.find(detail)
        return if (values == null) {
            "编码器输出的传递函数与请求值不一致，目标格式验证未通过"
        } else {
            val requested = values.groupValues[1].toIntOrNull()
            val actual = values.groupValues[2].toIntOrNull()
            "编码器输出的传递函数与请求值不一致（请求 ${transferCodeName(requested)}，" +
                "实际 ${transferCodeName(actual)}），目标格式验证未通过"
        }
    }

    private fun profileMismatchDescription(detail: String): String {
        val values = PROFILE_CHANGE_REGEX.find(detail)
        return if (values == null) {
            "编码器输出的 Profile 与请求值不一致，目标格式验证未通过"
        } else {
            "编码器输出的 Profile 与请求值不一致（请求 ${values.groupValues[1]}，实际 " +
                "${values.groupValues[2]}），目标格式验证未通过"
        }
    }

    private fun transferCodeName(value: Int?): String = when (value) {
        MediaFormat.COLOR_TRANSFER_ST2084 -> "PQ / ST 2084 ($value)"
        MediaFormat.COLOR_TRANSFER_HLG -> "HLG ($value)"
        MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR 视频 ($value)"
        MediaFormat.COLOR_TRANSFER_LINEAR -> "线性传递函数 ($value)"
        null -> "未知"
        else -> value.toString()
    }

    private fun transferDisplayName(transfer: FableSolExportTransfer): String = when (transfer) {
        FableSolExportTransfer.PQ -> "BT.2020 PQ"
        FableSolExportTransfer.HLG -> "BT.2020 HLG"
        FableSolExportTransfer.SDR -> "SDR"
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
        lastFailureReason = cached.reason
        lastCandidateFailures = cached.failures
    }

    private fun probeInternal(context: Context): Boolean {
        // **每一轮开头都要清空**：否则任何一条早退路径（或异常）都会让上一次的格式列表活
        // 下来，`supportedFormats()` 于是返回一份与本次结论无关的旧清单，缓存还会把它存进去。
        lastSupportedFormats = emptyList()
        lastCandidateFailures = emptyList()
        lastFailureReason = null
        val failures = ArrayList<String>(6)
        val eglCapability = FableSolExportEgl.probe()
        if (!eglCapability.linearSceneSupported) {
            lastFailureReason = "GL 无法渲染 FP16 场景缓冲（缺 GL_EXT_color_buffer_half_float）"
            return false
        }
        // 注意这里**不能**因为"两种 EGL 色彩空间都没有"就直接判死：走字节缓冲的档
        // （HDR10+）根本不用窗口色彩空间，PQ 编码由导出 shader 自己完成。

        // 能力结论不能随用户当前 CQ/码率偏好漂移。固定用正式默认 CBR 参数，分别尝试
        // 120/60fps；正式导出仍会按用户设置自行走完整降级阶梯。
        val options = FableSolExportOptions(
            frameRateCap = FableSolExportOptions.FRAME_RATE_HIGH,
            constantQuality = false,
            qualityValue = FableSolExportOptions.UNSET_QUALITY,
            bitrateMbps = FableSolExportOptions.DEFAULT_BITRATE_MBPS,
            keyframeIntervalSeconds = FableSolExportOptions.DEFAULT_KEYFRAME_SECONDS,
            hdrEnabled = true
        )
        val basePlan = FableSolExportSpec.plan(
            context,
            FableSolExportSpec.MAX_CARD_WIDTH_DP
        )
        val availableTransfers = eglCapability.availableHdrTransfers()
        val supported = ArrayList<FableSolExportHdrFormat>(2)

        // 每一种格式都要**单独走完一遍真实编码**，不能一成功就收工：设置页要摆出全部可选
        // 项，而"HDR10 能编"完全不蕴含"HDR10+ 或杜比视界也能编"。
        for (format in FableSolExportHdrFormat.AUTO_ORDER) {
            if (format.requiresEglColorSpace &&
                !availableTransfers.contains(format.transfer)
            ) {
                failures += "${format.stableLabel}：EGL 未提供目标格式所需的 " +
                    "${transferDisplayName(format.transfer)} 窗口色彩空间，未执行编码验证。"
                continue
            }
            val attempts = FableSolExportAttemptPlan.ordered(
                hdrFormats = listOf(format),
                requestedFrameRate = FableSolExportOptions.FRAME_RATE_HIGH
            ).takeWhile { it.hdr }
            var formatOk = false
            val formatFailures = ArrayList<String>(4)
            for (attempt in attempts) {
                if (formatOk) break
                val candidates = FableSolExportTier.candidatesForMode(
                    format = format,
                    widthPx = basePlan.canvasWidthPx,
                    heightPx = basePlan.canvasHeightPx,
                    frameRate = attempt.frameRate,
                    preferConstantQuality = options.constantQuality
                )
                for (tier in candidates) {
                    val failure = probeCandidate(context, options, attempt.frameRate, tier)
                    if (failure == null) {
                        formatOk = true
                        break
                    }
                    // 档位名本身已经带上格式，这里不再重复前缀。
                    formatFailures += "${attempt.frameRate} fps ${tier.label}：$failure"
                }
            }
            if (formatOk) {
                supported += format
                continue
            }
            // 每种格式只留**第一条**失败原因：同一格式下各编码器的报错通常一模一样，
            // 全列出来会把另外三种格式的原因挤出可见范围，而那才是用户想看的。
            val first = formatFailures.firstOrNull()
            failures += when {
                first == null ->
                    "${format.stableLabel}：未找到同时满足目标 Profile、画布尺寸和帧率要求的" +
                        "编码器候选。"
                first.contains(TRANSFER_DOWNGRADE_MARKER) ->
                    "${format.stableLabel}：${transferMismatchDescription(first)}" +
                        (format.validationFollowUp?.let { "；$it" } ?: "") +
                        moreFailures(formatFailures) + "。"
                first.contains(PROFILE_DOWNGRADE_MARKER) ->
                    "${format.stableLabel}：${profileMismatchDescription(first)}" +
                        (format.validationFollowUp?.let { "；$it" } ?: "") +
                        moreFailures(formatFailures) + "。"
                else ->
                    "${format.stableLabel}：编码候选验证未通过（技术详情：$first）" +
                        moreFailures(formatFailures) + "。"
            }
        }

        lastSupportedFormats = supported
        if (supported.isNotEmpty()) {
            lastFailureReason = null
            // 有格式可用时仍然保留失败明细：知道 HDR10 通了而杜比视界卡在哪，比只知道
            // "有 HDR"有用得多。
            lastCandidateFailures = failures
            return true
        }
        lastCandidateFailures = failures
        lastFailureReason = if (failures.isEmpty()) {
            "未找到可执行完整 HDR 验证的格式或编码器候选。"
        } else {
            "所有 HDR 格式均未通过完整的单帧编码与封装验证。"
        }
        return false
    }

    /** @return null 表示这一档真的编出来了；否则是失败原因。 */
    private fun probeCandidate(
        context: Context,
        options: FableSolExportOptions,
        frameRate: Int,
        tier: FableSolExportTier
    ): String? {
        val temporary = try {
            File.createTempFile("fablesol-hdr-probe-", ".mp4", context.cacheDir)
        } catch (error: Throwable) {
            return "无法创建临时文件：${error.message ?: error.javaClass.simpleName}"
        }
        var encoder: FableSolExportEncoder? = null
        var egl: FableSolExportEgl? = null
        var bridge: FableSolExportP010Bridge? = null
        return try {
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
                    FableSolExportTransfer.SDR_WHITE_NITS
            )
            // HDR10+ 必须按它正式导出时的样子探：字节缓冲输入 + 逐帧元数据。用 surface
            // 那条路去探它，探到的是另一件事。
            val byteBuffer = tier.hdrFormat?.usesByteBufferInput == true
            egl = FableSolExportEgl(
                if (byteBuffer) null else encoder.inputSurface,
                transfer = tier.transfer,
                tenBit = true
            )
            encoder.start()

            // 能力探测只验证 HDR 编码链，不编译整套水体 shader 或分配完整场景缓冲；
            // FP16 扩展已经由 FableSolExportEgl.probe() 门控，正式导出仍会验证实际 scene targets。
            if (byteBuffer) {
                val active = FableSolExportP010Bridge(
                    context.assets, tier.encodedWidthPx, tier.encodedHeightPx
                )
                bridge = active
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, active.presentFramebufferId)
                GLES30.glViewport(0, 0, tier.encodedWidthPx, tier.encodedHeightPx)
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                active.convert()
                val probeStats = active.stats()
                val payload = FableSolExportHdr10PlusMetadata.payload(
                    probeStats,
                    FableSolExportHdr10PlusCurve(
                        masteringPeakNits = FableSolHdrPolicy.MAX_STRENGTH *
                            FableSolExportTransfer.SDR_WHITE_NITS
                    ).next(probeStats, 1.0 / frameRate)
                )
                encoder.queueVideoFrame(0L, payload) { buffer, stride, slice ->
                    active.writeInto(buffer, stride, slice)
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
                !encoder.finish(timeoutMs = PROBE_TIMEOUT_MS) ->
                    "编码与封装流程未在限定时间内完成"
                // 编出来了还不够：HDR10+ 要码流里真的带上那段 SEI 才算数。
                byteBuffer && !encoder.hdr10PlusSeiSeen ->
                    "输出码流未检测到 HDR10+ ST 2094-40 SEI"
                else -> null
            }
        } catch (error: Throwable) {
            // 这句就是我们要的东西：三星那台机器色彩空间与 Main10 编码器一应俱全却仍失败，
            // 只有异常本身能说清卡在 configure、EGL、start 还是输出格式核验。
            "${error.javaClass.simpleName}: ${error.message ?: "未提供详细信息"}"
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
                        ?: emptyList()
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
        val failures: List<String> = emptyList()
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

    /** `FableSolExportEncoder.validateVideoOutputFormat` 抛出的那两句话里的固定片段。 */
    private const val PROFILE_DOWNGRADE_MARKER = "changed profile"
    private const val TRANSFER_DOWNGRADE_MARKER = "changed color-transfer"
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
    // 2：缓存条目开始携带诊断细节；旧条目必须失效，否则依然问不出原因。
    // 3：色彩范围改为采纳编码器回报值，此前被误判为不支持的机器必须重新探测。
    // 4：改为逐格式探测（HDR10 / HDR10+ / HLG / 杜比视界各自编一帧），缓存结构随之改变。
    // 5：诊断改为结构化、正式措辞，并将格式稳定标识与本地化显示名称分离。
    private const val PROBE_CONTRACT_VERSION = 5
    private const val NEGATIVE_RESULT_TTL_MS = 24L * 60L * 60L * 1_000L

    private const val CACHE_PREFERENCES = "fablesol_hdr_export_capability"
    private const val KEY_SIGNATURE = "signature"
    private const val KEY_SUPPORTED = "supported"
    private const val KEY_CHECKED_AT_MS = "checked_at_ms"
    private const val KEY_FORMATS = "formats"
    private const val KEY_REASON = "reason"
    private const val KEY_FAILURES = "failures"
    private const val FAILURE_SEPARATOR = "\u0001"
}
