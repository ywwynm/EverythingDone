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
        // 设备一种 HDR 格式都编不出来时必须把偏好一并关掉，否则导出会去走一条走不通的路。
        assertTrue(tuning.contains("setExportHdrEnabled(appContext, false)"))
        assertFalse(tuning.contains("makeExportSwitchRow("))
        assertTrue(exporter.contains("FableSolExportAttemptPlan.ordered("))
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
