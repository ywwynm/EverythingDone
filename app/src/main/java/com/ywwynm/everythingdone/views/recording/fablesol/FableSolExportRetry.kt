package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 用户可见、需要确认的输出规格（D179）。
 *
 * 具体 `codecName` 与 P010／Surface 输入通路有意不在这里：它们是同一公开规格内的内部候选，
 * 可以自动切换。其余六项中的任一项变化都必须获得用户确认。
 */
internal data class FableSolExportPublicSpec(
    val format: FableSolExportHdrFormat?,
    val family: FableSolExportCodecFamily,
    val tenBit: Boolean,
    val softwareOnly: Boolean,
    val frameRate: Int,
    /** 用户可见编码模式；CQ 与目标码率不得在候选重试中自动互换（D183）。 */
    val rateControl: FableSolExportRateControl = FableSolExportRateControl.DEFAULT
)

/** 面向用户的稳定失败分类；底层异常原文仅进入诊断。 */
internal enum class FableSolExportRetryReason {
    ENCODER_INITIALIZATION,
    ENCODING_INTERRUPTED,
    HDR_SCENE_ANALYSIS,
    HDR_RENDER_PATH,
    ENCODING_PATH,

    /**
     * 参考显示峰值低于场景实测峰值的可行下限，无法生成 HDR10+ 曲线（D115）。
     *
     * 该失败只取决于场景 V8 与用户选择的参考显示峰值，与编码器族、软硬件类型和输入通路
     * 无关；同格式的其余公开规格必然得到同一结论，一并判定失败，不逐个重跑全片预分析。
     */
    REFERENCE_PEAK_INFEASIBLE
}

/**
 * 最近一次重试说明。主界面只保留这一条；完整内部尝试链另行写入设备诊断。
 */
internal data class FableSolExportRetryNotice(
    val failedSpec: FableSolExportPublicSpec,
    val reason: FableSolExportRetryReason,
    /** 等待确认时是建议规格；已确认后是当前正在使用的规格。 */
    val currentSpec: FableSolExportPublicSpec,
    /** 已经实际开始过的公开规格数量；同规格内部候选不增加。 */
    val attemptedSpecCount: Int,
    /** true 表示六项公开规格未变，不需要确认。 */
    val sameSpec: Boolean,
    /**
     * 已本地化的补充说明；确认与终态文案在分类行之后追加显示（D115"给出本帧 S/T、
     * 最低可行参考峰值以及可操作建议"）。它是面向用户的资源字符串，不是底层异常原文。
     */
    val detail: String? = null
)

internal object FableSolExportRetryPolicy {

    /** 保持首次出现顺序，并把同一公开规格的内部候选归为一组。 */
    fun orderedSpecs(candidates: Iterable<FableSolExportPublicSpec>): List<FableSolExportPublicSpec> =
        LinkedHashSet<FableSolExportPublicSpec>().apply { addAll(candidates) }.toList()

    /**
     * 当前公开规格失败后的下一建议。已失败规格不会再次出现；帧率不同的候选一律排除。
     */
    fun nextSuggestion(
        ordered: List<FableSolExportPublicSpec>,
        failed: Set<FableSolExportPublicSpec>,
        strictFrameRate: Int
    ): FableSolExportPublicSpec? = ordered.firstOrNull {
        it.frameRate == strictFrameRate && it !in failed
    }
}
