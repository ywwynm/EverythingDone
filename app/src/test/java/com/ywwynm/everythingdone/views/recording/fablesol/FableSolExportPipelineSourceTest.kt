package com.ywwynm.everythingdone.views.recording.fablesol

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 本地 JVM 无法实例化真实 MediaCodec/MediaMuxer/Service；这些源码契约用于钉住最容易
 * 被“整理代码”重新引入的事务顺序和降级门。项目已有同类 GL/HDR source tests。
 */
class FableSolExportPipelineSourceTest {

    @Test
    fun muxerIsFinalizedBeforeSinkIsPublished() {
        val exporter = projectFile("FableSolVideoExporter.kt")
        val encoder = projectFile("FableSolExportEncoder.kt")

        val finish = exporter.indexOf("encoder.finish(listener::isCancelled)")
        val commit = exporter.indexOf("request.sink.commit(listener::isCancelled)")
        assertTrue(finish >= 0)
        assertTrue(commit > finish)
        assertTrue(encoder.contains("finalizeMuxer()"))
        assertTrue(encoder.contains("muxer.stop()"))
    }

    @Test
    fun codecFallbackValidatesActualHdrAndKeepsUniversalAvcFallbacks() {
        val encoder = projectFile("FableSolExportEncoder.kt")
        val exporter = projectFile("FableSolVideoExporter.kt")

        assertTrue(encoder.contains("MediaFormat.KEY_LEVEL"))
        assertTrue(encoder.contains("validateVideoOutputFormat(outputFormat)"))
        assertTrue(encoder.contains("AVCProfileMain"))
        assertTrue(encoder.contains("AVCProfileBaseline"))
        // AV1 进 MP4 到 API 34 才正式支持，这道门要留着。
        assertTrue(encoder.contains("Build.VERSION.SDK_INT < 34"))
        // 但 FEATURE_HlgEditing 那道筛**不能回来**：三星 S23 Ultra 的高通编码器一个都不
        // 广告这个能力位，加回去就会把 API 35 上的 HLG 候选整批筛光（2026-07-27 实测）。
        // 每一档最后都要真编一帧才算数，不需要再拿一个语义不对的广告位提前否决。
        // 不能只搜 "FEATURE_HlgEditing" 四个字：源码里那条注释正是在讲它为什么被删掉，
        // 删掉注释反而会丢掉这段教训。判据因此落在**实际的查询调用**上——能力位只允许用在
        // 它自己描述的那件事上。FEATURE_QpBounds 就是 D151 要查的东西（编码器支不支持 QP
        // 上下限），不是拿来给格式当门禁；这里逐个点名，多出一处新的 isFeatureSupported
        // 就会让这条断言失败，逼着下一个人先说清它问的是什么。
        val featureQueries = Regex("isFeatureSupported\\(\\s*\\n?\\s*MediaCodecInfo\\.CodecCapabilities\\.(\\w+)")
            .findAll(encoder)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(listOf("FEATURE_QpBounds"), featureQueries)
        assertTrue(exporter.contains("renderer.isHdrContentEnabled()"))
        assertTrue(exporter.contains("setOfflineFixedDt(1.0 / frameRate)"))
    }

    @Test
    fun muxedVideoRejectsAnyOneSidedVisibleCrop() {
        val encoder = projectFile("FableSolExportEncoder.kt")

        assertTrue(encoder.contains("validateFullFrameCrop(format)"))
        assertTrue(encoder.contains("CROP_LEFT_KEY"))
        assertTrue(encoder.contains("CROP_RIGHT_KEY"))
        assertTrue(encoder.contains("widthPx - 1"))
        assertTrue(encoder.contains("heightPx - 1"))
    }

    @Test
    fun legacyOutputIsPublicUniqueAndScannerConfirmed() {
        val sink = projectFile("FableSolExportSink.kt")

        assertTrue(sink.contains("getExternalStoragePublicDirectory"))
        assertTrue(sink.contains("candidate.createNewFile()"))
        assertTrue(sink.contains("CountDownLatch"))
        assertFalse(sink.contains("context.getExternalFilesDir("))
    }

    @Test
    fun serviceCancellationAndTimeoutAreJobScopedAndTerminal() {
        val service = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/services/" +
                "FableSolVideoExportService.kt"
        )
        val exporter = projectFile("FableSolVideoExporter.kt")

        assertTrue(service.contains("private fun cancelJob(jobId: Long)"))
        assertTrue(service.contains("State.Cancelled(jobId)"))
        assertTrue(service.contains("current.job.id, timeoutMessage"))
        assertTrue(service.contains("stopSelfResult(latestStartId)"))
    }

    @Test
    fun completionNotificationUsesTheSamePublishedResultAsTheDialog() {
        val service = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/services/" +
                "FableSolVideoExportService.kt"
        )

        assertTrue(service.contains("FableSolVideoExportBus.post(state)"))
        assertTrue(service.contains("notifyResult(jobId, effectiveResult, state)"))
        assertTrue(service.contains("R.string.fablesol_export_dialog_done"))
        assertTrue(service.contains("done.fileSizeBytes"))
        assertTrue(service.contains("done.displayLocation"))
        assertTrue(service.contains("State.Done)?.uri"))
    }

    @Test
    fun exportIconUsesMaterialVideoFrameSaveWithCompleteTopAndLeftFrame() {
        val icon = projectRelative(
            "app/src/main/res/drawable/act_fablesol_export_video.xml"
        )

        assertTrue(icon.contains("Material Symbols Outlined"))
        assertTrue(icon.contains("video_frame_save"))
        assertTrue(icon.contains("android:viewportWidth=\"960\""))
        assertTrue(icon.contains("M380,660L380,300L660,480"))
        assertTrue(icon.contains("M760,840L600,680"))
        assertTrue(icon.contains("M320,160L640,160L640,240L320,240"))
        assertTrue(icon.contains("M80,400L160,400L160,560L80,560"))
        assertFalse(icon.contains("strokeLineCap"))
        assertFalse(icon.contains("水波"))
    }

    @Test
    fun exportDialogActionsCenterLabelsInsideTheirRippleBounds() {
        val layout = projectRelative(
            "app/src/main/res/layout/dialog_fablesol_export_progress.xml"
        )

        assertEquals(
            2,
            Regex("""android:gravity="center"""").findAll(layout).count()
        )
    }

    @Test
    fun playDialogUsesGradientExportInkAndLargerMainTransportIcon() {
        val fragment = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "AudioPlayDialogFragment.kt"
        )
        val layout = projectRelative(
            "app/src/main/res/layout/fragment_play_audio.xml"
        )
        val mainAction = layout.substringAfter("iv_play_main_action")
            .substringBefore("/>")

        assertTrue(fragment.contains("BackgroundUtil.tintDrawable("))
        assertTrue(fragment.contains("button.imageTintList = null"))
        assertTrue(mainAction.contains("android:scaleType=\"fitCenter\""))
        assertTrue(mainAction.contains("android:padding=\"12dp\""))
    }

    @Test
    fun hdrSwitchUsesARealOneFrameEncoderProbeAndExporterUsesTheAttemptPlan() {
        val capability = projectFile("FableSolHdrExportCapability.kt")
        val tuning = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "FableSolTuningDialogFragment.kt"
        )
        val exporter = projectFile("FableSolVideoExporter.kt")

        assertTrue(capability.contains("FableSolExportEncoder("))
        assertTrue(capability.contains("FableSolExportEgl("))
        assertTrue(capability.contains("encoder.finish("))
        assertTrue(capability.contains("temporary.delete()"))
        assertTrue(capability.contains("peekCachedResult("))
        assertTrue(capability.contains("CACHE_PREFERENCES"))
        assertTrue(capability.contains("NEGATIVE_RESULT_TTL_MS"))
        // 缓存签名必须含随每次 debug 发布变化的量。只靠 BuildConfig.VERSION_CODE 不行——
        // 它在 build.gradle 里写死为 43，两次发布之间不变，于是探测逻辑改了、旧结论却
        // 继续从缓存里读出来（2026-07-27 实际发生过：HLG 那一行连措辞都还是旧版的）。
        assertTrue(capability.contains("R.string.debug_update_code"))
        assertFalse(capability.contains("FableSolGlRenderer("))
        // 设置页只摆一处色彩入口：单一互斥的「导出色彩模式」选择器（D62）。单独的 HDR
        // 开关已删除——开关与格式选择说的是同一件事，摆两处只会让人问"关掉开关但选了
        // HDR10 会怎样"。前两项是两种 SDR，它们表达的是两种创作意图，不是一个开关的两态。
        assertTrue(tuning.contains("FableSolHdrExportCapability.supportedFormats("))
        assertTrue(tuning.contains("R.string.fablesol_param_export_color_mode"))
        assertTrue(tuning.contains("R.string.fablesol_export_color_mode_sdr_native"))
        assertTrue(tuning.contains("R.string.fablesol_export_color_mode_sdr_tone_mapped"))
        assertFalse(tuning.contains("R.string.fablesol_export_hdr_format_off"))
        assertTrue(tuning.contains("Process.THREAD_PRIORITY_BACKGROUND"))
        // **能力结论绝不能回写偏好。** 这里曾经断言相反的事：设备编不出 HDR 时写一次
        // `setExportHdrEnabled(appContext, false)`。那个写入不可逆——偏好一旦为 false，
        // 之后即便探测重新通过，界面仍以偏好为准落在「关闭」上，于是又写一次 false，永久
        // 粘住。三星 Z Fold4 上实际发生过：设备能编 HDR10 与 HLG，默认却停在关闭
        // （2026-07-27）。现在能力只影响本次显示，写偏好一律要求 fromUser。
        assertFalse(tuning.contains("setExportHdrEnabled(appContext, false)"))
        assertTrue(tuning.contains("fromUser: Boolean"))
        assertTrue(tuning.contains("if (fromUser) {"))
        assertFalse(tuning.contains("makeExportSwitchRow("))
        assertTrue(exporter.contains("FableSolExportAttemptPlan.ordered("))
    }

    /**
     * 探测必须用**用户实际会用的那份编码参数**验证。
     *
     * CQ 与目标码率必须分别使用正式导出的 MediaFormat 探测，并同时保存在一份五维矩阵中。
     * 切换模式后不应重新借用上一模式的结果，也不需要让偏好参与设备能力缓存签名。
     */
    @Test
    fun capabilityProbeRecordsBothEncodingModesInTheSameMatrix() {
        val capability = projectFile("FableSolHdrExportCapability.kt")
        val matrix = projectFile("FableSolExportCapabilityMatrix.kt")

        assertTrue(
            capability.contains("for (rateControl in FableSolExportRateControl.entries)")
        )
        assertTrue(capability.contains("rateControl = rateControl"))
        assertTrue(matrix.contains("val rateControlId: String"))
        assertFalse(capability.contains("|\$mode\""))
    }

    /**
     * 三条轴（HDR 格式 × 编码器族 × 帧率）的可行组合必须整表留下来。
     *
     * 此前每种格式一编成功就跳出循环，只留下"该格式可用"一个布尔量——既说不出它落在哪个
     * 编码器、哪个帧率上，也无从判断别的组合成不成立。三星 Z Fold4 上"验证通过"的其实是
     * 软件 AV1 的 60fps，而设置页看起来像是 HDR10 一切正常（2026-07-27）。
     */
    @Test
    fun capabilityProbeRecordsEveryCombinationInsteadOfASingleBoolean() {
        val capability = projectFile("FableSolHdrExportCapability.kt")

        assertTrue(capability.contains("FableSolExportCapabilityMatrix.Builder()"))
        assertTrue(capability.contains("FableSolExportTier.familiesFor(format)"))
        assertTrue(capability.contains("lastMatrix = matrix.build()"))
        // 缓存要连整张表一起存，否则命中缓存时置灰规则就没有依据。
        assertTrue(capability.contains("KEY_MATRIX"))
        assertTrue(capability.contains("FableSolExportCapabilityMatrix.decode(cached.matrix)"))
        // SDR 也要进表：关掉 HDR 之后编码器仍然要选，那一列不能是空的。
        assertTrue(capability.contains("FableSolExportHdrFormat.SELECTABLE_ORDER + listOf(null)"))
    }

    /**
     * **同规格内先穷尽硬件实现，软件实现是最后回退**（D53 修订 + D161）。
     *
     * D53 原先完全排除软件编码器；三星 Z Fold4 的实测（D58）证明该机的 HDR 只能由软件 AV1
     * 承担，于是"自动档宁可落 SDR 也不用软件编码"反而丢掉了设备真有的规格。现在的规则是：
     * 输出规格先于编码器实现，同规格内 `硬件 HEVC → 硬件 AV1 → 硬件 AVC → 软件 HEVC →
     * 软件 AV1 → 软件 AVC`，速度与发热的代价由信息栏说明。
     */
    @Test
    fun sameSpecCandidatesPreferHardwareThenFallBackToSoftware() {
        val encoder = projectFile("FableSolExportEncoder.kt")
        val exporter = projectFile("FableSolVideoExporter.kt")

        assertTrue(encoder.contains("if (softwareOnly && !allowSoftware) continue"))
        // 硬件优先是**第一**排序键，编码器族次之，Profile 阶梯再次之。
        val comparator = encoder.substringAfter("return collected.sortedWith(")
            .substringBefore(").map { it.second }")
        val software = comparator.indexOf("tier.softwareOnly")
        val family = comparator.indexOf("tier.family.ordinal")
        val ladder = comparator.indexOf("{ (index, _) -> index }")
        assertTrue(software in 0 until family)
        assertTrue(family < ladder)
        assertTrue(exporter.contains("family = options.codec.family"))
        // 显式格式的严格失败语义：不允许悄悄发布 SDR。
        assertTrue(
            exporter.contains("allowSdrFallback = options.colorMode.allowsSdrResult")
        )
    }

    /**
     * 完成态必须写出**实际用了哪个编码器**。
     *
     * 降级阶梯会在格式、帧率与编码器三条轴上依次退让，退到哪里此前完全看不出来：完成提示
     * 只说了"HDR10，60 fps"，而那次导出用的是软件 AV1。
     */
    @Test
    fun completionSurfacesTheCodecThatWasActuallyUsed() {
        val service = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/services/" +
                "FableSolVideoExportService.kt"
        )
        val dialog = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "FableSolExportProgressDialogFragment.kt"
        )
        val specText = projectFile("FableSolExportSpecText.kt")

        assertTrue(specText.contains("fun specification("))
        assertTrue(specText.contains("fablesol_export_codec_software_suffix"))
        assertTrue(service.contains("FableSolExportSpecText.specification("))
        assertTrue(dialog.contains("FableSolExportSpecText.specification("))
    }

    /**
     * 格式、编码器、位深与帧率必须由同一次精确组合查询解析。
     *
     * OPPO 上把编码器钉成 AV1 后，格式说明曾继续显示 HDR10+；Z Fold4 上选择 120 fps 后，
     * 自动摘要又借用了仅在 60 fps 成立的 HDR10／AV1。两类错误都来自各轴分别推导或把帧率
     * 当作上限。现在设置页只能使用当前精确帧率的同一个解析结果。
     */
    @Test
    fun everyDerivedLabelResolvesFromOneExactCapabilityTuple() {
        val tuning = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "FableSolTuningDialogFragment.kt"
        )
        val capability = projectFile("FableSolHdrExportCapability.kt")
        val service = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/services/" +
                "FableSolVideoExportService.kt"
        )
        val exporter = projectFile("FableSolVideoExporter.kt")

        assertFalse(tuning.contains("FableSolHdrExportCapability.autoFormat"))
        assertTrue(tuning.contains("return matrix.resolve("))
        assertTrue(tuning.contains("val rate = rateOrder[rateIndex]"))
        assertTrue(tuning.contains("mResolvedExportFrameRate = resolved?.frameRate"))
        assertTrue(tuning.contains("mResolvedExportCodecFamily = resolved?.family"))
        assertTrue(tuning.contains("rateControl = rateControl"))
        // 编码模式的置灰同样带着当前的格式、编码器与精确帧率一起求值。
        assertTrue(
            tuning.contains("modeOrder.map { feasible(formatPreference, codec, rate, it) }")
        )
        assertTrue(
            tuning.contains(
                "mResolvedExportFrameRate ?: FableSolTuning.exportFrameRate(ctx)"
            )
        )
        assertFalse(tuning.contains("FRAME_RATES.filter"))
        assertFalse(tuning.contains("exportFrameRateCap"))
        assertTrue(service.contains("matrix.resolve("))
        assertTrue(service.contains("fablesol_export_no_exact_specification"))
        assertTrue(exporter.contains("R.string.fablesol_export_no_exact_specification"))
        assertFalse(
            exporter.contains("No encoder supports the requested output specification")
        )
        assertTrue(service.contains("targetSpec = resolved?.let"))
        assertTrue(service.contains("targetSpec = activeJob.targetSpec"))
        // 能力报告要列全部可用组合，不是第一个落点。
        assertTrue(capability.contains("lastMatrix.reach(format, rateControl)"))
    }

    /**
     * 能力探测必须验证**正式导出真正会用的那条链路**，并且失败时说得出所以然。
     *
     * 三条都是三星 Z Fold4 上实测暴露的（2026-07-27）：
     *
     * - 输入表面位深写死 10-bit，于是 8-bit 档（H.264、HEVC Main）验的与用的不是同一条链路；
     * - `MediaCodec.CodecException` 继承自 `IllegalStateException` 且 message 常为空，诊断里
     *   只剩一句 `IllegalStateException:`，真正有用的 `diagnosticInfo` 与 `errorCode` 全丢了；
     * - 一次完整探测会连续创建释放几十个 MediaCodec，硬件编码器实例有限，被占用时报的是
     *   瞬时错误，不能记成"这台机器不支持"。
     */
    @Test
    fun capabilityProbeMatchesTheExportPathAndExplainsItsFailures() {
        val capability = projectFile("FableSolHdrExportCapability.kt")

        assertTrue(capability.contains("tenBit = !tier.eightBit"))
        assertFalse(capability.contains("tenBit = true"))
        assertTrue(capability.contains("error.diagnosticInfo"))
        assertTrue(capability.contains("error.errorCode"))
        assertTrue(capability.contains("error.isTransient"))
        assertTrue(capability.contains("failure?.retryable == true"))
        // `isTransient` 覆盖不全：三星 Z Fold4 上 HDR 各档抛的是**普通**
        // IllegalStateException（Released state / request cancelled），不是 CodecException，
        // 于是那条路一次都没走到。按状态特征补判断的那张表不能删。
        assertTrue(capability.contains("RETRYABLE_STATE_MARKERS"))
        assertTrue(capability.contains("\"Released state\""))
        assertTrue(capability.contains("\"request cancelled\""))
        // 被这道尺寸/帧率筛拦下的候选连一次编码都不会发生，报告里必须与"编失败了"分得开。
        assertTrue(capability.contains("areSizeAndRateSupported(width, height"))
        assertTrue(capability.contains("fun rejectedCombinationLines("))
    }

    /**
     * `EGL_RECORDABLE_ANDROID` **不是"给驱动的提示"**，它决定分配出来的缓冲带不带视频编码器
     * 用途位；少了它，交给 `MediaCodec.createInputSurface()` 的缓冲编码器根本消费不了。
     *
     * 三星 Z Fold4 上 10-bit 各档一律以编码器"已被释放"告终，而 8-bit 档走的是带 recordable
     * 的那一档，一切正常（2026-07-28）。而这个属性是厂商扩展，部分驱动不把它纳入
     * `eglChooseConfig` 的匹配却在 `eglGetConfigAttrib` 里如实回报——所以匹配落空之后必须
     * 自己枚举全部 config 逐个核对，不能直接退到不带 recordable 的那一档。
     */
    @Test
    fun tenBitConfigSelectionEnumeratesInsteadOfTrustingEglChooseConfig() {
        val egl = projectFile("FableSolExportEgl.kt")

        assertTrue(egl.contains("enumerateConfig(display, variant, offscreen)"))
        assertTrue(egl.contains("EGL14.eglGetConfigs("))
        assertTrue(egl.contains("EGL14.eglGetConfigAttrib("))
        assertTrue(egl.contains("attribute(display, config, EGL_RECORDABLE_ANDROID) != 1"))
        assertTrue(egl.contains("fun tenBitConfigCensus("))
        // 那句"recordable 只是给驱动的一个提示"是错的判断，不能回来。
        assertFalse(egl.contains("recordable 只是给驱动的一个提示"))
    }

    /**
     * 设备能力报告要能整段复制。此前只能靠截图往外传，一屏放不下就得截好几张，还没法搜索。
     */
    @Test
    fun exportReportCanBeCopiedInOneGesture() {
        val tuning = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "FableSolTuningDialogFragment.kt"
        )

        assertTrue(tuning.contains("fun copyExportReport("))
        assertTrue(tuning.contains("ClipData.newPlainText("))
        assertTrue(tuning.contains("setOnLongClickListener(copyReport)"))
        // 指示性文字与诊断两段一起复制：分两次长按才拿全没有意义。
        assertTrue(tuning.contains("listOf(estimate.text, diagnostics.text)"))
        assertTrue(tuning.contains("R.string.fablesol_export_diagnostics_copy_hint"))
    }

    /**
     * 四条轴（帧率、HDR 格式、编码器、编码模式）必须互相约束。
     *
     * 帧率此前是"上限，不行就自己降"，于是 Z Fold4 上选了 120fps 仍可选 AV1，点下去帧率被
     * 悄悄改成 60；HDR10 与 HLG 也照样摆着，而它们在 120fps 下根本没有通路
     * （用户 2026-07-28 指出）。现在每一条轴的可选性都以其余各条的现值为前提。
     *
     * 编码模式（恒定质量／目标码率）是 D183 加进矩阵的那一轴，此前只在 `feasible` 里当默认
     * 参数用，没进 `reconcile` 的枚举——等于把它当成了硬约束，见下一个测试。
     */
    @Test
    fun theFourExportAxesConstrainEachOther() {
        val tuning = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "FableSolTuningDialogFragment.kt"
        )

        assertTrue(tuning.contains("fun feasible("))
        assertTrue(tuning.contains("enum class Axis { FORMAT, CODEC, RATE, MODE }"))
        // 格式与编码器的可选性都要带上当前帧率。
        assertTrue(tuning.contains("formatEnabled(choice, codec, rate)"))
        assertTrue(tuning.contains("codecEnabled(choice, formatPreference, rate)"))
        assertTrue(tuning.contains("rateOrder.map { feasible(formatPreference, codec, it) }"))
        assertTrue(
            tuning.contains("modeOrder.map { feasible(formatPreference, codec, rate, it) }")
        )
        // 帧率行与编码模式行都要接进同一套联动，而不是各写各的。
        assertTrue(tuning.contains("reconcile(Axis.RATE)"))
        assertTrue(tuning.contains("reconcile(Axis.MODE)"))
        // 求解读的是界面下标，不是偏好——只写偏好会让这条轴的显示与求解分家。
        assertTrue(tuning.contains("rateControl: FableSolExportRateControl = modeOrder[modeIndex]"))
        assertTrue(tuning.contains("val rateControl = modeOrder[modeIndex]"))
    }

    /**
     * 冲突时的让步顺序：编码模式 → 编码器族 → 输出格式 → 帧率。
     *
     * 此前编码模式压根不在 `reconcile` 的枚举里，`feasible` 拿它当固定前提，于是"120 fps 上
     * 没有恒定质量通路"表现为**恢复默认后掉到 60 fps 的恒定质量**：拿一项真实的规格损失换了
     * 一项本可无损替代的偏好——恒定质量编不出来时，把目标码率调高同样能提升画质
     * （用户 2026-07-30 指出）。同时帧率的保护权重此前低于格式，与 D179"帧率固定，再保持
     * 格式与位深，最后换编码器族"相反，而 D179 要求设置页与运行时建议共用同一份顺序。
     *
     * 判据落在权重的**相对大小**上：它就是让步顺序本身。
     */
    @Test
    fun conflictsGiveUpTheEncodingModeBeforeTheFrameRate() {
        val tuning = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "FableSolTuningDialogFragment.kt"
        )

        val cost = tuning.substringAfter(".minByOrNull { (format, codec, rate, mode) ->")
            .substringBefore("}")
        val weights = Regex("""!= (\w+)Index\) cost \+= (\d+)""")
            .findAll(cost)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals(setOf("rate", "format", "codec", "mode"), weights.keys)
        // 帧率最难被改动，编码模式最先让步。
        assertTrue(weights.getValue("rate") > weights.getValue("format"))
        assertTrue(weights.getValue("format") > weights.getValue("codec"))
        assertTrue(weights.getValue("codec") > weights.getValue("mode"))
        // 四条轴都要真的进枚举，否则再怎么排权重也轮不到它让步。
        assertTrue(tuning.contains("if (changed == Axis.MODE && mode != modeIndex) continue"))
        assertTrue(tuning.contains("rateControlChips?.select?.invoke(bestMode)"))
        assertTrue(tuning.contains("applyMode(bestMode, fromUser = true)"))
    }

    /**
     * 选项不存在时，它那行说明必须跟着收起。
     *
     * 复杂帧质量保护只作用于目标码率（D151），恒定质量下那一行是 GONE 的；D174 定的"说明常显"
     * 只针对开关的开／关，不针对选项在不在。此前这段说明无条件写入，于是恒定质量下界面上留着
     * 一段没有归属的文字（用户 2026-07-30 指出）。
     *
     * 判据不搜"有没有出现过这个字符串"——它在解释性注释里也会出现；而是要求它出现在一个以
     * `prefersConstantQuality` 为条件的分支里。
     */
    @Test
    fun anOptionNoteDisappearsWithItsOwnRow() {
        val tuning = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "FableSolTuningDialogFragment.kt"
        )

        val note = tuning.substringAfter("qpGuardNote.setNote(").substringBefore("complexityNote")
        assertTrue(note.contains("if (current.prefersConstantQuality)"))
        assertTrue(note.contains("R.string.fablesol_export_desc_qp_guard"))
        // 行的显隐与说明的显隐必须同源，否则两处判据迟早分家。
        assertTrue(tuning.contains("qpGuardRow.visibility = if (constant) View.GONE else View.VISIBLE"))
        // 其余只在特定色彩模式下出现的行，说明早就是按条件写空串收起的。
        for (conditional in listOf("mappingNote", "bitDepthNote", "signalRangeNote")) {
            val block = tuning.substringAfter("$conditional.setNote(").substringBefore("\n            )")
            assertTrue(conditional, block.contains("\"\""))
        }
    }

    /**
     * 编码器胶囊下面那段说明要写三件事：这一档的特点、当前实际落到哪个编码器、置灰的含义。
     *
     * 此前它是一段固定文字。选「自动」时用户看不出这台机器上究竟挑中了谁、是硬件还是软件，
     * 而各编码器之间的取舍本来就该像 HDR 格式那样讲清楚（用户 2026-07-28 指出）。
     */
    @Test
    fun codecDescriptionNamesTheResolvedEncoderAndItsTradeOffs() {
        val tuning = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "FableSolTuningDialogFragment.kt"
        )

        assertTrue(tuning.contains("fun codecChoiceDescription("))
        assertTrue(tuning.contains("R.string.fablesol_export_codec_desc_auto"))
        assertTrue(tuning.contains("R.string.fablesol_export_codec_desc_hevc"))
        assertTrue(tuning.contains("R.string.fablesol_export_codec_desc_av1"))
        assertTrue(tuning.contains("R.string.fablesol_export_codec_desc_avc"))
        assertTrue(tuning.contains("R.string.fablesol_export_codec_desc_resolved"))
        // 由 notifyResolved 统一写，才能带上随格式与帧率变化的实际落点。
        assertTrue(
            tuning.contains("codecBlock.description.text = codecChoiceDescription(")
        )
    }

    /**
     * 一次已经成功的导出，绝不能死在发通知这一步。
     *
     * `Intent.createChooser` 带着 `EXTRA_STREAM` 时，`PendingIntent.getActivity` 会当场走一遍
     * URI 授权，被拒就抛 SecurityException。华为平板（EMUI，Android 12）上实测：视频已写完
     * 并入库，进程却在这一行崩掉（2026-07-28）。授权失败的原因在厂商实现里，我们改不了，
     * 但少一个分享按钮远好过崩溃。
     */
    @Test
    fun aSuccessfulExportSurvivesAnyNotificationFailure() {
        val service = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/services/" +
                "FableSolVideoExportService.kt"
        )

        assertTrue(service.contains("private fun shareIntent(uri: Uri, jobId: Long): PendingIntent?"))
        assertTrue(service.contains("shareIntent(uri, jobId)?.let"))
        // 打开与发送两条路径都要兜住，通知本身发不出去也一样。
        assertTrue(service.contains("manager.notify(RESULT_NOTIFICATION_ID, builder.build())"))
        val notifyResult = service.substringAfter("private fun notifyResult(")
            .substringBefore("private fun shareIntent(")
        // 打开产物的 PendingIntent 与 notify() 各一道；分享那一道在 shareIntent 里。
        assertEquals(2, Regex("""catch \(ignored: Throwable\)""").findAll(notifyResult).count())
        val share = service.substringAfter("private fun shareIntent(")
            .substringBefore("private fun notifyFailureText(")
        assertTrue(share.contains("catch (ignored: Throwable)"))
    }

    /**
     * **"拿到输出格式并走到 EOS" 不等于 "编出了东西"。**
     *
     * `INFO_OUTPUT_FORMAT_CHANGED` 一来就能 `addTrack` 并启动 muxer，随后即便一个实际样本都
     * 没有，`finish()` 依然成功返回，产物是 0 字节的文件。华为平板（Kirin，
     * `OMX.hisi.video.encoder.hevc`）上每一档 HEVC 都如此——10 位输入表面拿不到带
     * `EGL_RECORDABLE_ANDROID` 的 config，编码器既不报错也不产出；只有 8 位的 H.264 有数据
     * （2026-07-28）。能力探测因此把它判成"验证通过"，导出也照发不误。
     *
     * 所以样本计数是发布前的硬门禁，探测与正式导出两边都要有；落盘大小再兜一道。
     */
    @Test
    fun anEncoderThatProducesNoSamplesIsNeverAccepted() {
        val encoder = projectFile("FableSolExportEncoder.kt")
        val exporter = projectFile("FableSolVideoExporter.kt")
        val capability = projectFile("FableSolHdrExportCapability.kt")

        assertTrue(encoder.contains("var videoSamplesWritten = 0L"))
        assertTrue(encoder.contains("if (videoSample) videoSamplesWritten++"))
        assertTrue(capability.contains("encoder.videoSamplesWritten <= 0L"))
        assertTrue(exporter.contains("check(encoder.videoSamplesWritten > 0L)"))
        // 样本计数正常但落盘仍为空时，也不能把它留在图库里。
        assertTrue(exporter.contains("request.sink.fileSizeBytes() <= 0L"))
    }

    /**
     * 恒定质量区间属于当前完整规格实际通过的编码器，不能使用设备上另一条路径的代表值。
     *
     * 三星 Z Fold4 上支持 CQ 的只有 `c2.qti.hevc.encoder.cq`，尺寸上限 512×512，本项目画布
     * 根本轮不到它。此前不查尺寸，设置里显示恒定质量档，导出时实际改用目标码率
     * （2026-07-28）。
     */
    @Test
    fun constantQualityRangeComesFromTheExactProbedCombination() {
        val options = projectFile("FableSolExportOptions.kt")
        val encoder = projectFile("FableSolExportEncoder.kt")
        val matrix = projectFile("FableSolExportCapabilityMatrix.kt")
        val tuning = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "FableSolTuningDialogFragment.kt"
        )

        assertFalse(options.contains("settingsQualityRange"))
        assertTrue(encoder.contains("qualityRange == null"))
        assertTrue(matrix.contains("val qualityLower: Int?"))
        assertTrue(matrix.contains("val qualityUpper: Int?"))
        assertTrue(tuning.contains("mResolvedExportQualityRange = resolved?.outcome?.qualityRange"))
    }

    /**
     * 编码器没在 outputFormat 里回报 profile **不等于**它把 profile 改掉了。
     *
     * 不少 OMX 编码器压根不写 `KEY_PROFILE`。此前把 null 判成降档，一台已经产出 10-bit HDR
     * 输出的华为平板被自己的校验否掉，报的是 `changed profile 2 to null`（2026-07-28）。
     */
    @Test
    fun aMissingOutputProfileIsNotTreatedAsADowngrade() {
        val encoder = projectFile("FableSolExportEncoder.kt")

        assertTrue(
            encoder.contains("actualProfile == null || tier.acceptsTenBitProfile(actualProfile)")
        )
    }

    /**
     * **母版亮度意图与导出设备无关**（D82/D83）。
     *
     * 曾经默认漫反射白由面板峰值、最大帧平均亮度与 HDR 强度共同推出（D45），于是同一份创作
     * 参数在两台设备上会得到不同的 PQ 像素与不同的静态元数据。现在默认固定为 BT.2408 的
     * 名义 HDR 参考白 203 尼特，本机显示能力只作诊断与观看参考。
     */
    @Test
    fun diffuseWhiteIsDeviceIndependentAndOnlyReportsDisplayCapabilityAsReference() {
        val luminance = projectFile("FableSolExportDisplayLuminance.kt")
        val options = projectFile("FableSolExportOptions.kt")
        val tuning = projectFile("FableSolTuning.kt")
        val dialog = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "FableSolTuningDialogFragment.kt"
        )
        val chineseStrings = projectRelative(
            "app/src/main/res/values-zh-rCN/strings.xml"
        )

        // 设备能力仍然读，但只作参考；按设备反推白锚的那套公式必须消失。
        assertTrue(luminance.contains("desiredMaxLuminance"))
        assertTrue(luminance.contains("desiredMaxAverageLuminance"))
        assertFalse(luminance.contains("CONTENT_PEAK_ALLOWANCE"))
        assertFalse(luminance.contains("AUTO_WHITE_MAX_NITS"))
        assertTrue(options.contains("const val DEFAULT_PQ_WHITE_NITS = 203f"))
        // 存过这个键就是自定义；恢复默认清除它，回到标准值。
        assertTrue(tuning.contains("prefs(context).contains(KEY_EXPORT_PQ_WHITE)"))
        assertTrue(tuning.contains(".remove(KEY_EXPORT_PQ_WHITE)"))
        // 强度滑杆拖动期间只重算峰值那一行，不再改写白锚。
        assertTrue(dialog.contains("mRefreshExportDerivedInfo?.invoke(strength)"))
        assertTrue(dialog.contains("FableSolTuning.exportPqWhiteMode(ctx)"))
        assertTrue(dialog.contains("R.string.fablesol_export_estimate_white_standard"))
        assertTrue(dialog.contains("R.string.fablesol_export_estimate_white_custom"))
        assertTrue(chineseStrings.contains("未声明（不参与计算）"))
        assertFalse(chineseStrings.contains("自动漫反射白推导"))
        assertFalse(chineseStrings.contains("min（"))
    }

    @Test
    fun automaticHdrDescriptionStatesTheActualHighestSpecOrdering() {
        val format = projectFile("FableSolExportHdrFormat.kt")
        val chineseStrings = projectRelative(
            "app/src/main/res/values-zh-rCN/strings.xml"
        )

        assertTrue(chineseStrings.contains("按 HDR 规格与画质能力由高到低"))
        assertFalse(chineseStrings.contains("按兼容性优先的顺序"))
        val order = format.substringAfter("val AUTO_ORDER = listOf(")
            .substringBefore(")")
            .split(",")
            .map { it.trim() }
        assertTrue(
            order.indexOf("DOLBY_VISION_84") < order.indexOf("HDR10")
        )
    }

    @Test
    fun hdrDisplayNamesAreLocalizedWithoutChangingStableCacheKeys() {
        val format = projectFile("FableSolExportHdrFormat.kt")
        val exporter = projectFile("FableSolVideoExporter.kt")
        val dialog = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "FableSolTuningDialogFragment.kt"
        )
        val chineseStrings = projectRelative(
            "app/src/main/res/values-zh-rCN/strings.xml"
        )

        assertTrue(format.contains("val stableLabel: String"))
        assertTrue(format.contains("fun displayName(context: Context)"))
        assertTrue(format.contains("fun fromStableLabel("))
        assertTrue(format.contains("fun localizeStableLabels("))
        assertTrue(dialog.contains("format.displayName(ctx)"))
        assertTrue(exporter.contains("tier.displayLabel(context)"))
        // 完成态的格式名来自**已解析候选**，不再由各处各自推一遍。
        assertTrue(exporter.contains("resolved.formatLabel(context)"))
        assertTrue(chineseStrings.contains(
            """name="fablesol_export_hdr_format_name_dolby_vision_84">杜比视界 8.4"""
        ))
        assertFalse(chineseStrings.contains(">Dolby Vision"))
    }

    @Test
    fun hdrVividIsTheLastColorModeAndExposesItsCurveControls() {
        val format = projectFile("FableSolExportHdrFormat.kt")
        val resolved = projectFile("FableSolExportResolvedCandidate.kt")
        val dialog = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "FableSolTuningDialogFragment.kt"
        )

        val selectable = format.substringAfter("val SELECTABLE_ORDER =")
            .substringAfter("listOf(")
            .substringBefore(")")
            .split(",")
            .map { it.trim() }
        assertEquals("HDR_VIVID", selectable.last())

        val choices = dialog.substringAfter(
            "val formatChoices = ArrayList<FableSolExportColorMode>"
        ).substringBefore("// 整机一个组合都编不出来")
        assertTrue(
            choices.indexOf(
                "formatChoices += FableSolExportColorMode.SDR_TONE_MAPPED"
            ) < choices.lastIndexOf(
                "formatChoices += FableSolExportColorMode.HDR_VIVID"
            )
        )
        assertTrue(dialog.contains("format?.usesAuthoredToneMappingCurve == true"))
        assertTrue(dialog.contains("pqFormat?.usesAuthoredToneMappingCurve == true"))
        assertTrue(resolved.contains("tier.hdrFormat?.usesAuthoredToneMappingCurve == true"))
    }

    @Test
    fun hdrDiagnosticsUseFormalFactualLanguage() {
        val capability = projectFile("FableSolHdrExportCapability.kt")
        val hdr10PlusProbe = projectFile("FableSolHdr10PlusProbe.kt")
        val format = projectFile("FableSolExportHdrFormat.kt")
        val visibleHdrText = capability + hdr10PlusProbe + format

        listOf(
            "编码器把基层的亮度曲线改回去了",
            "那一档还有机会",
            "不打算产出",
            "没有别的办法",
            "裸通路",
            "带元数据",
            "编出来了，但",
            "无消息"
        ).forEach { phrase ->
            assertFalse("HDR 文案仍含口语化表述：$phrase", visibleHdrText.contains(phrase))
        }
        assertTrue(capability.contains("请求 \${transferCodeName(requested)}"))
        assertTrue(capability.contains("实际 \${transferCodeName(actual)}"))
        assertTrue(capability.contains("目标格式验证未通过"))
        assertTrue(capability.contains("单帧编码与封装验证通过"))
        assertTrue(capability.contains("提交 ST 2094-40 元数据"))
    }

    /**
     * 产物必须复现**界面实际的**外观。Service 拿到的 Application Context 两样都不对：主题是
     * 平台默认的浅色主题（`<application>` 没有 android:theme），配置也读不到 AppCompat 对
     * Activity 的夜间覆写。于是深色模式下画框已经变黑、卡片却仍是白的。
     */
    @Test
    fun exportResolvesCardColourFromTheAppliedAppearanceNotTheServiceTheme() {
        val exporter = projectFile("FableSolVideoExporter.kt")
        val appearance = projectFile("FableSolExportAppearance.kt")

        assertTrue(exporter.contains("FableSolExportAppearance.themedContext(baseContext)"))
        assertTrue(appearance.contains("AppearanceUtil.isDarkModeApplied("))
        assertTrue(appearance.contains("R.style.EverythingDoneTheme_Dialog"))
        assertTrue(appearance.contains("createConfigurationContext(configuration)"))
        assertTrue(appearance.contains("UI_MODE_NIGHT_MASK.inv()"))
    }

    /** 倾斜可关；关掉后走的就是"这份录音没有轨迹"那条竖直渲染路径，不另立第二种表达。 */
    @Test
    fun tiltPlaybackIsOptionalAndFallsBackToTheVerticalPath() {
        val exporter = projectFile("FableSolVideoExporter.kt")
        val options = projectFile("FableSolExportOptions.kt")
        val tuning = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "FableSolTuningDialogFragment.kt"
        )

        assertTrue(exporter.contains("if (options.tiltEnabled) {"))
        assertTrue(exporter.contains("FableSolGravityTrack.readFrom(File(request.audioPath))"))
        assertTrue(options.contains("tiltEnabled = FableSolTuning.exportTiltEnabled(context)"))
        assertTrue(tuning.contains("FableSolTuning.setExportTiltEnabled(ctx, checked)"))
        assertTrue(tuning.contains("GradientRippleDrawable.applyCheckboxRipple(checkBox"))
    }

    /**
     * 勾选框两种状态与触摸涟漪都必须吃完整强调背景：未选中不许退回中性描边。漏掉
     * `uncheckedGradient` 不会报错，只会在下一次换色时悄悄变灰，所以按"每一次
     * `applyCheckboxAccent` 都必须带着它"来钉，而不是数某个固定次数。
     */
    @Test
    fun everyCheckboxKeepsTheAccentInBothStates() {
        val tuning = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "FableSolTuningDialogFragment.kt"
        )
        val settings = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/activities/SettingsActivity.kt"
        )
        val background = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/utils/BackgroundUtil.kt"
        )

        for (source in listOf(tuning, settings)) {
            val applied = Regex("""applyCheckboxAccent\(""").findAll(source).count()
            assertTrue(applied > 0)
            assertEquals(
                applied,
                Regex("""uncheckedGradient = true""").findAll(source).count()
            )
        }
        // 未选中描边不降 alpha；对号按填充色明暗自适应，不再固定白色。
        assertTrue(background.contains("strokePaint.alpha = fade.toInt()"))
        assertTrue(background.contains("color = onColor(background, 1f)"))
    }

    @Test
    fun completedAudioPlaybackRestartsItsDecoderWhenTheUserSeeks() {
        val player = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/views/recording/" +
                "FableSolAudioFilePlayer.kt"
        )

        assertTrue(player.contains("FableSolPlaybackRestartPolicy"))
        assertTrue(player.contains("restartFrom("))
        assertTrue(player.contains("completedNaturally"))
        assertTrue(player.contains("initialSeekUs = positionMs * 1000L"))
        assertTrue(player.contains("sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)"))
    }

    /** HDR10+ VOD 按场景生成元数据，正式编码循环不得再逐帧重算统计或曲线（D177）。 */
    @Test
    fun hdr10PlusRepeatsOneScenePayloadAcrossTheWholeAnimation() {
        val exporter = projectFile("FableSolVideoExporter.kt")
        val payloadBuild = exporter.indexOf(
            "hdr10PlusPayload = FableSolExportHdr10PlusMetadata.payload("
        )
        val encodingLoop = exporter.indexOf("while (frameIndex < totalFrames)")
        assertTrue(payloadBuild >= 0)
        assertTrue(payloadBuild < encodingLoop)
        assertTrue(exporter.contains("FableSolExportHdr10PlusSceneAccumulator("))
        assertTrue(exporter.contains("shapeForScene(stats)"))
        assertTrue(exporter.contains("collectStats = false"))

        val loopEnd = exporter.indexOf("// 输出格式验证", encodingLoop)
        assertTrue(loopEnd > encodingLoop)
        val loopBody = exporter.substring(encodingLoop, loopEnd)
        assertTrue(loopBody.contains("checkNotNull(hdr10PlusPayload)"))
        assertFalse(loopBody.contains("activeBridge.stats()"))
        assertFalse(loopBody.contains("FableSolExportHdr10PlusMetadata.payload("))
    }

    /** HDR Vivid 必须按呈现时间取逐场景载荷，不能让 B 帧的编码输出顺序冒充画面顺序。 */
    @Test
    fun hdrVividBuildsASceneTimelineAndSelectsPayloadsByOutputPts() {
        val exporter = projectFile("FableSolVideoExporter.kt")
        val encoder = projectFile("FableSolExportEncoder.kt")

        assertTrue(exporter.contains("FableSolHdrVividTimeline.Builder("))
        assertTrue(exporter.contains("timeline.payloadAt(presentationTimeUs)"))
        assertTrue(encoder.contains("invoke(info.presentationTimeUs)"))
        assertFalse(exporter.contains("var hdrVividPayload: ByteArray?"))
    }

    private fun projectFile(name: String): String {
        return projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/views/recording/" +
                "fablesol/$name"
        )
    }

    private fun projectRelative(relativePath: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) {
                return candidate.readText(Charsets.UTF_8)
                    .replace("\r\n", "\n")
            }
            directory = directory.parentFile ?: return@repeat
        }
        error("Cannot find $relativePath")
    }
}
