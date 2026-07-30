package com.ywwynm.everythingdone.views.recording.fablesol

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出状态机的源码契约（fablesol-video-export 批次 8 的集成门禁）。
 *
 * 这里钉的全是**只在出错时才会走到**的分支：取消、候选重启、失败清理、提交、以及成功之后
 * 的收尾动作。它们在正常导出里一次都不执行，因此重构时最容易被顺手改坏而没人察觉——
 * 华为平板上那次"导出成功却崩在通知栏分享按钮上"（D59）正是这一类。
 *
 * JVM 起不了真实的 `MediaMuxer`/`MediaStore`/`Service`，所以按源码结构验证，与项目里已有的
 * `FableSolExportPipelineSourceTest` 同一套做法。
 */
class FableSolExportStateMachineTest {

    @Test
    fun everyUncommittedAttemptDiscardsItsPendingProduct() {
        val exporter = source("FableSolVideoExporter.kt")
        // 成功提交之前的任何一条出口（取消、候选失败、SDR 语义重启、异常）都要经过同一个
        // finally；靠每条 return 各写一次清理，迟早漏掉一条。
        assertTrue(exporter.contains("var published = false"))
        assertTrue(exporter.contains("published = true"))
        assertTrue(exporter.contains("if (!published) safely { request.sink.discard() }"))
        // 清理动作本身必须可失败：它跑在 finally 里，抛出去会盖掉真正的失败原因。
        assertTrue(exporter.contains("private inline fun safely(block: () -> Unit)"))
    }

    @Test
    fun restartingOnTheSameTierStartsFromAFreshProduct() {
        val sink = source("FableSolExportSink.kt")
        // D77/D78 的两条运行时降级在**同一个 tier** 上从第 1 帧重来，中间会走一次 discard。
        // 因此 discard 必须把 uri 清掉、createMuxer 必须能再插一行：少了任一条，重启就会
        // 写进一个已经被删掉的 MediaStore 行，或者在相册里留下一条孤儿 pending 记录。
        assertTrue(sink.contains("resolver.delete(target, null, null)"))
        assertTrue(sink.contains("uri = null"))
        assertTrue(sink.contains("MediaStore.Video.Media.IS_PENDING, 1"))
        val exporter = source("FableSolVideoExporter.kt")
        assertTrue(exporter.contains("catch (degrade: SdrDegradeRestart)"))
        assertTrue(exporter.contains("render = degrade.fallback"))
    }

    @Test
    fun commitIsPartOfTheSuccessPathAndItsResultIsChecked() {
        val exporter = source("FableSolVideoExporter.kt")
        val sink = source("FableSolExportSink.kt")
        // 提交失败意味着产物仍挂在 IS_PENDING 上、相册里看不见；此时报成功就是在骗人。
        assertTrue(exporter.contains("val committed = request.sink.commit(listener::isCancelled)"))
        assertTrue(sink.contains("resolver.update(target, values, null, null) > 0"))
        // 提交**不能**放进 finally 当收尾动作。
        assertFalse(exporter.contains("safely { request.sink.commit"))
    }

    @Test
    fun decorativeTailActionsCanEachFailOnTheirOwn() {
        val service = source("../../../services/FableSolVideoExportService.kt")
        // D59：华为平板上产物已经落盘入库，却因为通知栏那个分享按钮的 PendingIntent 在
        // 构建当时执行 URI 授权失败而整次导出崩掉。收尾阶段的每个装饰性动作各自兜底。
        assertTrue(service.contains("private fun shareIntent(uri: Uri, jobId: Long): PendingIntent? = try {"))
        assertTrue(service.contains("manager.notify(RESULT_NOTIFICATION_ID, builder.build())"))
        // notify 必须被 try 包住：它紧跟在终态已经发给 Bus 之后。
        val notifyIndex = service.indexOf("manager.notify(RESULT_NOTIFICATION_ID")
        val guardIndex = service.lastIndexOf("try {", notifyIndex)
        assertTrue(guardIndex in 0 until notifyIndex)
        assertTrue(notifyIndex - guardIndex < 120)
    }

    @Test
    fun cancellationIsCheckedAtEveryStageBoundary() {
        val exporter = source("FableSolVideoExporter.kt")
        // 取消要在每一段可能长时间运行的工作**之前**再查一次：候选切换、全片预分析、
        // HLG 回环验证、逐帧循环、收尾。少查一处，用户点了取消之后还要再等一整段。
        val checks = Regex("listener\\.isCancelled\\(\\)").findAll(exporter).count()
        assertTrue("取消检查太少，只有 $checks 处", checks >= 6)
        assertTrue(exporter.contains("return Result.Cancelled"))
        // 取消不是失败：它有自己的终态，不进失败通知，也不该被当成候选失败去试下一档。
        assertFalse(exporter.contains("Result.Failure(\"cancelled\")"))
    }

    @Test
    fun successfulProductsSurviveEveryPostCommitDiagnostic() {
        val encoder = source("FableSolExportEncoder.kt")
        val check = source("FableSolExportStaticMetadataCheck.kt")
        // D142/D166：静态元数据的逐字段回读核对定位在**短探测产物**上，正式产物封装成功后
        // 的一切附加解析只作诊断。这里守的是"核对没有被搬回正式导出路径"。
        val capability = source("FableSolHdrExportCapability.kt")
        assertTrue(capability.contains("FableSolExportStaticMetadataCheck.verify(temporary, it)"))
        val exporter = source("FableSolVideoExporter.kt")
        assertFalse(exporter.contains("FableSolExportStaticMetadataCheck.verify"))
        // D166 第三条：正式产物封装成功后 exporter 只跑纯诊断入口（inspect），且必须在
        // sink.commit 之后——产物已是最终形态，解析结论无从影响它。
        val inspectAt = exporter.indexOf("FableSolExportStaticMetadataCheck.inspect(")
        assertTrue(inspectAt > exporter.indexOf("request.sink.commit("))
        // configure 阶段的注入必须保留：旧系统上实际承载静态元数据的是编码器生成的码流 SEI。
        assertTrue(encoder.contains("setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, staticInfo())"))
        // 核对读的是编码器自己那一份描述符，不在探测侧另算一遍。
        assertTrue(encoder.contains("fun expectedStaticInfo()"))
        assertTrue(check.contains("容器完全没有携带") || check.contains("未携带"))
    }

    @Test
    fun lowFrameRateRowsInheritOnlyFromTheSameImplementation() {
        // D185：低帧率行承袭高帧率赢家前，必须先纯枚举本帧率候选并比对实现（codecName）；
        // 分歧时清空族赢家状态——否则本帧率实测全败时，行尾的 familyWinner 兜底会把
        // 高帧率赢家重新写进这一行。
        val capability = source("FableSolHdrExportCapability.kt")
        assertTrue(
            capability.contains("candidates.firstOrNull()?.codecName == settled.codecName")
        )
        val divergenceReset = capability.indexOf("familyWinner = null")
        assertTrue(divergenceReset >= 0)
        // 清空必须发生在本帧率的实测候选循环之前。
        assertTrue(divergenceReset < capability.indexOf("for (tier in usableCandidates)"))
    }

    @Test
    fun pureCqFallbackRetriesTheSameCandidateWithABitrateHint() {
        // D186：正式导出侧的 D167 运行时阶梯只对"无探测结论、回退到纯 CQ"的初始化失败开放
        // ——缓存有结论时探测结论是唯一权威；重试是零进度同规格内部重试，不回写矩阵。
        val exporter = source("FableSolVideoExporter.kt")
        assertTrue(
            exporter.contains("currentAttemptFormFallbackPureCq = rateControlFormOverride == null &&")
        )
        val retryAt = exporter.indexOf(
            "formOverride = FableSolExportRateControlForm"
        )
        assertTrue(retryAt >= 0)
        // 重试判据必须带初始化阶段门：编码中途失败不得拉去换形态。
        val gate = exporter.lastIndexOf(
            "currentAttemptPhase == AttemptPhase.ENCODER_INITIALIZATION", retryAt
        )
        assertTrue(gate in 0 until retryAt && retryAt - gate < 400)
    }

    @Test
    fun capabilityProbeNeverWritesUserPreferences() {
        // D50：能力探测只允许影响当次显示。三星 Z Fold4 上一次探测为空就把"关闭 HDR"写进
        // 了用户偏好，而那个写入不可逆——之后即便探测重新通过，界面仍以偏好为准。
        val capability = source("FableSolHdrExportCapability.kt")
        assertFalse(Regex("FableSolTuning\\.setExport").containsMatchIn(capability))
        // 探测也不得弹一次"导出失败"：它不是一次用户发起的导出。
        assertFalse(capability.contains("notifyFailure"))
    }

    @Test
    fun publicSpecificationChangesPauseForExplicitConfirmation() {
        val exporter = source("FableSolVideoExporter.kt")
        val service = source("../../../services/FableSolVideoExportService.kt")

        assertTrue(exporter.contains("data class NeedsConfirmation("))
        assertTrue(exporter.contains("for ((candidateIndex, candidate) in group.withIndex())"))
        assertTrue(exporter.contains("resultAfterPublicSpecFailure("))
        assertTrue(service.contains("current.waiting = confirmation"))
        assertTrue(service.contains("State.AwaitingConfirmation("))
        // 等待状态保留 active 任务；不能落入通用完成分支后自动 dispatchNext。
        val waiting = service.indexOf("current.waiting = confirmation")
        val returnIndex = service.indexOf("return", waiting)
        val clearIndex = service.indexOf("active = null", waiting)
        assertTrue(returnIndex in (waiting + 1) until clearIndex)
    }

    @Test
    fun confirmationNotificationUsesOneIdempotentDirectServiceCommand() {
        val service = source("../../../services/FableSolVideoExportService.kt")
        val dialog = source("../../../fragments/FableSolExportProgressDialogFragment.kt")

        assertTrue(service.contains("private const val ACTION_ACCEPT_SUGGESTED"))
        assertTrue(service.contains(".setContentIntent(acceptPending)"))
        assertTrue(service.contains("getString(R.string.fablesol_export_use_suggested_spec)"))
        assertTrue(service.contains("val confirmation = current.waiting ?: return"))
        assertTrue(service.contains("current.waiting = null"))
        assertTrue(dialog.contains("State.AwaitingConfirmation"))
        assertTrue(dialog.contains("FableSolVideoExportService.acceptSuggested("))

        val branchStart = dialog.indexOf(
            "is FableSolVideoExportBus.State.AwaitingConfirmation ->"
        )
        val branchEnd = dialog.indexOf(
            "is FableSolVideoExportBus.State.Queued",
            branchStart
        )
        val confirmationBranch = dialog.substring(branchStart, branchEnd)
        assertFalse(confirmationBranch.contains("fablesol_export_run_in_background"))
    }

    @Test
    fun referencePeakInfeasibilityFailsTheWholeFormatWithItsOwnCategory() {
        val exporter = source("FableSolVideoExporter.kt")
        // 配置级无解（S > 10T）有自己的正式失败分类，不得冒充"场景统计无法完成"（D115、D179）。
        assertTrue(exporter.contains("catch (infeasible: ReferencePeakInfeasible)"))
        assertTrue(
            exporter.contains(
                "lastReason = FableSolExportRetryReason.REFERENCE_PEAK_INFEASIBLE"
            )
        )
        // 该结论与编码器无关：同格式公开规格一并判失败并整组跳出候选循环，建议链不得
        // 让用户逐个确认注定失败的同格式规格、逐个重跑全片预分析。
        assertTrue(exporter.contains("it.format == targetSpec.format"))
        assertTrue(exporter.contains("break@candidates"))
        // 含两个可行数值的本地化说明必须进入确认与终态文案，不再只进设备诊断。
        val specText = source("FableSolExportSpecText.kt")
        assertTrue(specText.contains(".appendDetail(notice.detail)"))
        assertTrue(specText.contains(".appendDetail(detail)"))
    }

    @Test
    fun waitingForConfirmationReleasesAttemptResourcesAndWakeLock() {
        val service = source("../../../services/FableSolVideoExportService.kt")
        val exporter = source("FableSolVideoExporter.kt")

        val confirmation = service.indexOf(
            "val confirmation = completion.result as? " +
                "FableSolVideoExporter.Result.NeedsConfirmation"
        )
        val state = service.indexOf("State.AwaitingConfirmation(", confirmation)
        assertTrue(service.indexOf("releaseWakeLock(current)", confirmation) in confirmation until state)
        assertTrue(service.indexOf("completion.sink?.discard()", confirmation) in confirmation until state)
        assertTrue(exporter.contains("safely { renderer?.release() }"))
        assertTrue(exporter.contains("safely { encoder?.release() }"))
        assertTrue(exporter.contains("if (!published) safely { request.sink.discard() }"))
    }

    private fun source(name: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val relative =
            "app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/$name"
        repeat(8) {
            val candidate = File(directory, relative)
            if (candidate.isFile) {
                return candidate.readText(Charsets.UTF_8).replace("\r\n", "\n")
            }
            directory = directory.parentFile ?: return@repeat
        }
        throw AssertionError("找不到源文件 $name")
    }
}
