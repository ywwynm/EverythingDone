package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 一次编码尝试所属的**输出规格**：格式、帧率、位深。
 *
 * 规格先于编码器实现：同一个规格内部才比较硬件／软件与编码器族（D161）。帧率是严格输出
 * 规格（D179），不得作为运行时降级轴。
 *
 * [format] 为 null 表示 SDR——SDR 不是一种"HDR 格式"，不该混进那个枚举里。
 */
internal data class FableSolExportModeAttempt(
    val format: FableSolExportHdrFormat?,
    val frameRate: Int,
    /** 输出位深；HDR 一律 10-bit，SDR 由 [FableSolExportSdrBitDepth] 决定候选顺序。 */
    val tenBit: Boolean = true
) {
    val transfer: FableSolExportTransfer
        get() = format?.transfer ?: FableSolExportTransfer.SDR

    val hdr: Boolean get() = format != null
}

internal object FableSolExportAttemptPlan {

    /**
     * 生成有序的输出规格候选。
     *
     * 顺序规则（D106、D160、D161）：
     *
     * 1. **格式为主轴**：每种 HDR 格式把自己的位深走完，再换下一种格式；
     * 2. **帧率是严格规格**：全部候选保持用户选择的帧率；
     * 3. **位深在同规格内**：自动位深先穷尽 10-bit 再进入 8-bit，严格位深不跨位深后备；
     * 4. SDR 排在全部 HDR 格式之后，且只有"HDR 自动"与两种 SDR 模式才允许出现 SDR 规格。
     *
     * @param hdrFormats 本次允许尝试的 HDR 输出格式，按规格/画质能力排序；空表示不请求 HDR。
     * @param sdrBitDepth 明确选择 SDR 时的位深意图；HDR 规格不读取该值（D160）。
     * @param allowSdrFallback 显式 HDR 格式失败后不得发布 SDR，此时传 false（D106）。
     */
    fun ordered(
        hdrFormats: List<FableSolExportHdrFormat>,
        requestedFrameRate: Int,
        sdrBitDepth: FableSolExportSdrBitDepth = FableSolExportSdrBitDepth.AUTO,
        allowSdrFallback: Boolean = true
    ): List<FableSolExportModeAttempt> {
        val frameRate = requestedFrameRate.coerceIn(
            FableSolExportOptions.FRAME_RATE_BASE,
            FableSolExportOptions.FRAME_RATE_HIGH
        )
        val formats: List<FableSolExportHdrFormat?> = if (allowSdrFallback) {
            hdrFormats + listOf(null)
        } else {
            hdrFormats
        }
        return formats.flatMap { format ->
            // HDR 一律 10-bit：D160 明确不提供 8-bit HDR，也不读取隐藏的 SDR 位深偏好。
            val bitDepths = if (format == null) {
                sdrBitDepth.candidateOrder
            } else {
                listOf(true)
            }
            bitDepths
                .filter { FableSolExportTier.supportsBitDepth(format, it) }
                .map { tenBit ->
                    FableSolExportModeAttempt(
                        format = format,
                        frameRate = frameRate,
                        tenBit = tenBit
                    )
                }
        }
    }
}
