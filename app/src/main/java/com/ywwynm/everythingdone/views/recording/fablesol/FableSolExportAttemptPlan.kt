package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 一次编码尝试所属的信号类型与帧率。HDR 请求下先穷尽 HDR 帧率降级，再进入 SDR，
 * 避免 120fps HDR 不可用时抢先返回 120fps SDR、漏掉可用的 60fps HDR。
 */
internal data class FableSolExportModeAttempt(
    val hdr: Boolean,
    val frameRate: Int
)

internal object FableSolExportAttemptPlan {

    fun ordered(
        wantHdr: Boolean,
        requestedFrameRate: Int
    ): List<FableSolExportModeAttempt> {
        val frameRates = if (requestedFrameRate > FableSolExportOptions.FRAME_RATE_BASE) {
            listOf(requestedFrameRate, FableSolExportOptions.FRAME_RATE_BASE)
        } else {
            listOf(FableSolExportOptions.FRAME_RATE_BASE)
        }
        val signalModes = if (wantHdr) listOf(true, false) else listOf(false)
        return signalModes.flatMap { hdr ->
            frameRates.map { frameRate ->
                FableSolExportModeAttempt(hdr = hdr, frameRate = frameRate)
            }
        }
    }
}
