package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 一次编码尝试所属的输出格式与帧率。HDR 请求下先穷尽 HDR 帧率降级，再进入 SDR，
 * 避免 120fps HDR 不可用时抢先返回 120fps SDR、漏掉可用的 60fps HDR。
 *
 * [format] 为 null 表示 SDR——SDR 不是一种"HDR 格式"，不该混进那个枚举里。
 */
internal data class FableSolExportModeAttempt(
    val format: FableSolExportHdrFormat?,
    val frameRate: Int
) {
    val transfer: FableSolExportTransfer
        get() = format?.transfer ?: FableSolExportTransfer.SDR

    val hdr: Boolean get() = format != null
}

internal object FableSolExportAttemptPlan {

    /**
     * @param hdrFormats 本机可用的 HDR 输出格式，按偏好排序。空表示只能出 SDR。
     */
    fun ordered(
        hdrFormats: List<FableSolExportHdrFormat>,
        requestedFrameRate: Int
    ): List<FableSolExportModeAttempt> {
        val frameRates = if (requestedFrameRate > FableSolExportOptions.FRAME_RATE_BASE) {
            listOf(requestedFrameRate, FableSolExportOptions.FRAME_RATE_BASE)
        } else {
            listOf(FableSolExportOptions.FRAME_RATE_BASE)
        }
        // 先把每一种 HDR 格式的帧率降级走完，再落到 SDR——否则 120fps HDR 不可用时
        // 会抢先返回 120fps SDR，漏掉本可用的 60fps HDR。
        val formats: List<FableSolExportHdrFormat?> = hdrFormats + listOf(null)
        return formats.flatMap { format ->
            frameRates.map { frameRate ->
                FableSolExportModeAttempt(format = format, frameRate = frameRate)
            }
        }
    }
}
