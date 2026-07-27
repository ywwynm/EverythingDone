package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Test

class FableSolExportAttemptPlanTest {

    @Test
    fun hdrRequestTriesSixtyFpsHdrBeforeAnySdrFallback() {
        assertEquals(
            listOf(
                attempt(FableSolExportHdrFormat.HDR10, 120),
                attempt(FableSolExportHdrFormat.HDR10, 60),
                attempt(null, 120),
                attempt(null, 60)
            ),
            FableSolExportAttemptPlan.ordered(
                hdrFormats = listOf(FableSolExportHdrFormat.HDR10),
                requestedFrameRate = 120
            )
        )
    }

    /**
     * 多种 HDR 格式都可用时，必须把**第一种的帧率降级走完**再换第二种，最后才落 SDR。
     * 否则 120fps HDR10 不可用会抢先返回 120fps HLG，而 60fps HDR10（余量更大）本来可用。
     */
    @Test
    fun everyHdrFormatExhaustsItsFrameRatesBeforeTheNextOne() {
        assertEquals(
            listOf(
                attempt(FableSolExportHdrFormat.HDR10, 120),
                attempt(FableSolExportHdrFormat.HDR10, 60),
                attempt(FableSolExportHdrFormat.HLG, 120),
                attempt(FableSolExportHdrFormat.HLG, 60),
                attempt(null, 120),
                attempt(null, 60)
            ),
            FableSolExportAttemptPlan.ordered(
                hdrFormats = listOf(
                    FableSolExportHdrFormat.HDR10,
                    FableSolExportHdrFormat.HLG
                ),
                requestedFrameRate = 120
            )
        )
    }

    @Test
    fun sdrRequestNeverCreatesAnHdrAttempt() {
        assertEquals(
            listOf(attempt(null, 60)),
            FableSolExportAttemptPlan.ordered(
                hdrFormats = emptyList(),
                requestedFrameRate = 60
            )
        )
    }

    /**
     * 只有 HLG 的设备照样要拿到 HDR 尝试。此前整条通路只认 HLG 扩展，反过来只有 PQ 的
     * 设备会被整个挡掉；两个方向都必须成立。
     */
    @Test
    fun aDeviceWithOnlyOneColorSpaceStillGetsHdrAttempts() {
        assertEquals(
            listOf(
                attempt(FableSolExportHdrFormat.HLG, 60),
                attempt(null, 60)
            ),
            FableSolExportAttemptPlan.ordered(
                hdrFormats = listOf(FableSolExportHdrFormat.HLG),
                requestedFrameRate = 60
            )
        )
    }

    /** 杜比视界与 HDR10+ 同样只是格式表里的一项，排序规则对它们一视同仁。 */
    @Test
    fun dolbyVisionIsJustAnotherFormatInTheLadder() {
        assertEquals(
            listOf(
                attempt(FableSolExportHdrFormat.DOLBY_VISION_84, 60),
                attempt(null, 60)
            ),
            FableSolExportAttemptPlan.ordered(
                hdrFormats = listOf(FableSolExportHdrFormat.DOLBY_VISION_84),
                requestedFrameRate = 60
            )
        )
    }

    private fun attempt(format: FableSolExportHdrFormat?, frameRate: Int) =
        FableSolExportModeAttempt(format = format, frameRate = frameRate)
}
