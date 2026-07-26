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
        assertTrue(encoder.contains("Build.VERSION.SDK_INT >= 35"))
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
        assertTrue(capability.contains("peekCachedResult()"))
        assertTrue(capability.contains("CACHE_PREFERENCES"))
        assertTrue(capability.contains("NEGATIVE_RESULT_TTL_MS"))
        assertFalse(capability.contains("FableSolGlRenderer("))
        assertTrue(tuning.contains("FableSolHdrExportCapability.probe("))
        assertTrue(tuning.contains("setHdrExportSwitchState("))
        assertTrue(tuning.contains("postDelayed("))
        assertTrue(tuning.contains("Process.THREAD_PRIORITY_BACKGROUND"))
        assertTrue(tuning.contains("R.string.fablesol_tuning_hdr_unsupported"))
        assertTrue(tuning.contains("setExportHdrEnabled(appContext, false)"))
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
