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
        assertFalse(encoder.contains("isFeatureSupported("))
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
    fun hdrSwitchUsesARealOneFrameEncoderProbeAndExporterKeepsHdrAcrossFpsFallback() {
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
        // 设置页只摆一处 HDR 入口：格式胶囊，第一项就是「关闭」。单独的开关已删除——
        // 开关与格式选择说的是同一件事，摆两处只会让人问"关掉开关但选了 HDR10 会怎样"。
        assertTrue(tuning.contains("FableSolHdrExportCapability.supportedFormats("))
        assertTrue(tuning.contains("R.string.fablesol_export_hdr_format_off"))
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
     * 此前这里固定 CBR，理由是"能力结论不能随偏好漂移"；代价是恒定质量档下发的是另一套
     * `KEY_BITRATE_MODE` + `KEY_QUALITY`、候选排序也不同，于是设置页验过的 MediaFormat 与
     * 真正导出的并不是同一份。"设置页说能编、真编时全档失败"就是这么来的。既然结论依赖
     * 这个偏好，它就必须进缓存签名。
     */
    @Test
    fun capabilityProbeUsesTheSameEncodingModeAsTheExportAndKeysTheCacheOnIt() {
        val capability = projectFile("FableSolHdrExportCapability.kt")

        assertTrue(capability.contains("constantQuality = constantQuality"))
        assertTrue(capability.contains("FableSolTuning.exportConstantQuality(context)"))
        assertFalse(capability.contains("constantQuality = false,"))
        // 签名里要有编码模式，否则换了模式仍会读出上一份结论。
        assertTrue(capability.contains("|\$mode\""))
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
        assertTrue(capability.contains("FableSolExportHdrFormat.AUTO_ORDER + listOf(null)"))
    }

    /**
     * 自动档**不使用软件编码器**，用户显式选中某个族时才允许。
     *
     * 本项目的导出画布接近两百万像素，软件编码与硬件编码耗时差一到两个数量级。让软件编码
     * 器充当静默退路，等于在用户毫不知情的情况下把一次导出拖长几十倍——三星 Z Fold4 上
     * 一次 120fps 的 HDR 导出实际落到了软件 AV1 的 60fps 上。
     */
    @Test
    fun automaticLadderExcludesSoftwareEncodersAndHonoursThePinnedCodec() {
        val encoder = projectFile("FableSolExportEncoder.kt")
        val exporter = projectFile("FableSolVideoExporter.kt")
        val options = projectFile("FableSolExportOptions.kt")

        assertTrue(encoder.contains("if (softwareOnly && !allowSoftware) continue"))
        // 硬件优先必须是**第一**排序键，落在编码模式之前。
        assertTrue(encoder.contains("{ it.softwareOnly },"))
        assertTrue(exporter.contains("family = options.codec.family"))
        assertTrue(exporter.contains("allowSoftware = options.codec.allowsSoftware"))
        assertTrue(options.contains("val allowsSoftware: Boolean get() = family != null"))
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
     * 每一处推导出来的文字都必须在**当前编码器选择下**解析。
     *
     * 探测给出的 `autoFormat` 是"编码器也取自动"时的答案。OPPO 上把编码器钉成 AV1 之后，
     * 格式胶囊已经正确地只留下 HDR10 与 HLG，说明文字却仍写着「当前为 HDR10+」，而 AV1
     * 根本编不出 HDR10+（2026-07-27）。帧率同理：面板上的那一项是上限，达不到时导出会自己
     * 降级，估算与提示语必须按降级后的帧率算。
     */
    @Test
    fun everyDerivedLabelResolvesUnderTheCurrentCodecAndAchievableFrameRate() {
        val tuning = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "FableSolTuningDialogFragment.kt"
        )
        val capability = projectFile("FableSolHdrExportCapability.kt")

        assertFalse(tuning.contains("FableSolHdrExportCapability.autoFormat"))
        assertTrue(tuning.contains("matrix.autoFormat(codec.family"))
        // 帧率与编码器在同一次遍历里解出：分两处各算一遍迟早会给出一对互不相容的答案。
        assertTrue(tuning.contains("mResolvedExportFrameRate = resolved?.first"))
        assertTrue(tuning.contains("mResolvedExportCodec = resolved?.second"))
        assertTrue(
            tuning.contains(
                "mResolvedExportFrameRate ?: FableSolTuning.exportFrameRateCap(ctx)"
            )
        )
        // 能力报告要列全部可用组合，不是第一个落点。
        assertTrue(capability.contains("lastMatrix.reach(format)"))
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
     * 三条轴（帧率、HDR 格式、编码器）必须互相约束。
     *
     * 帧率此前是"上限，不行就自己降"，于是 Z Fold4 上选了 120fps 仍可选 AV1，点下去帧率被
     * 悄悄改成 60；HDR10 与 HLG 也照样摆着，而它们在 120fps 下根本没有通路
     * （用户 2026-07-28 指出）。现在每一条轴的可选性都以另外两条的现值为前提。
     */
    @Test
    fun theThreeExportAxesConstrainEachOther() {
        val tuning = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "FableSolTuningDialogFragment.kt"
        )

        assertTrue(tuning.contains("fun feasible("))
        assertTrue(tuning.contains("enum class Axis { FORMAT, CODEC, RATE }"))
        // 格式与编码器的可选性都要带上当前帧率。
        assertTrue(tuning.contains("formatEnabled(choice, codec, rate)"))
        assertTrue(tuning.contains("codecEnabled(choice, formatPreference, rate)"))
        assertTrue(tuning.contains("rateOrder.map { feasible(formatPreference, codec, it) }"))
        // 帧率行也要接进同一套联动，而不是各写各的。
        assertTrue(tuning.contains("reconcile(Axis.RATE)"))
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
        assertTrue(encoder.contains("if (track == videoTrack) videoSamplesWritten++"))
        assertTrue(capability.contains("encoder.videoSamplesWritten <= 0L"))
        assertTrue(exporter.contains("check(encoder.videoSamplesWritten > 0L)"))
        // 样本计数正常但落盘仍为空时，也不能把它留在图库里。
        assertTrue(exporter.contains("request.sink.fileSizeBytes() <= 0L"))
    }

    /**
     * 恒定质量与编码器是绑在一起的：支持 CQ 却编不了本次画布的编码器不能代表本机能力。
     *
     * 三星 Z Fold4 上支持 CQ 的只有 `c2.qti.hevc.encoder.cq`，尺寸上限 512×512，本项目画布
     * 根本轮不到它。此前不查尺寸，设置里摆着恒定质量档，导出时静默换成恒定码率
     * （2026-07-28）。
     */
    @Test
    fun constantQualityRangeComesFromAnEncoderThatCanTakeThisCanvas() {
        val options = projectFile("FableSolExportOptions.kt")

        assertTrue(options.contains("fun settingsQualityRange(context: Context)"))
        assertTrue(options.contains("areSizeAndRateSupported("))
        assertTrue(options.contains("FableSolExportTier.alignForEncoder("))
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

    @Test
    fun automaticDiffuseWhiteUsesDisplayLimitsAndTracksHdrStrengthInSettings() {
        val luminance = projectFile("FableSolExportDisplayLuminance.kt")
        val tuning = projectFile("FableSolTuning.kt")
        val dialog = projectRelative(
            "app/src/main/java/com/ywwynm/everythingdone/fragments/" +
                "FableSolTuningDialogFragment.kt"
        )
        val chineseStrings = projectRelative(
            "app/src/main/res/values-zh-rCN/strings.xml"
        )

        assertTrue(luminance.contains("desiredMaxLuminance"))
        assertTrue(luminance.contains("desiredMaxAverageLuminance"))
        assertTrue(luminance.contains("it * CONTENT_PEAK_ALLOWANCE / strength"))
        assertTrue(luminance.contains("AUTO_WHITE_MAX_NITS"))
        // 手动拖过后必须退出自动档；恢复默认会清除此键，再重新按设备能力计算。
        assertTrue(tuning.contains("!prefs(context).contains(KEY_EXPORT_PQ_WHITE)"))
        assertTrue(tuning.contains(".remove(KEY_EXPORT_PQ_WHITE)"))
        // 强度滑杆拖动期间，自动白锚和下方推导公式都要即时刷新。
        assertTrue(dialog.contains("mRefreshExportDerivedInfo?.invoke(strength)"))
        assertTrue(dialog.contains("FableSolTuning.isExportPqWhiteAutomatic(ctx)"))
        assertTrue(dialog.contains("R.string.fablesol_export_estimate_white_auto_formula"))
        assertTrue(dialog.contains("recommendation.panelPeakNits"))
        assertTrue(dialog.contains("recommendation.panelMaxAverageNits"))
        assertTrue(dialog.contains("constraintFormula(recommendation)"))
        assertTrue(chineseStrings.contains("显示设备 HDR 亮度能力"))
        assertTrue(chineseStrings.contains("未声明（不参与计算）"))
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
        assertTrue(exporter.contains("tier.hdrFormat?.displayName(context)"))
        assertTrue(chineseStrings.contains(
            """name="fablesol_export_hdr_format_name_dolby_vision_84">杜比视界 8.4"""
        ))
        assertFalse(chineseStrings.contains(">Dolby Vision"))
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
