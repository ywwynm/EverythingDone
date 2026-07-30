package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FableSolExportAttemptPlanTest {

    /**
     * D179：设置页选择的是严格输出帧率，不是允许向下回退的上限。120 fps 请求的任何候选都
     * 必须保持 120 fps；设备只能完成 60 fps 时，本次导出失败并由用户返回设置页重新选择。
     */
    @Test
    fun requestedFrameRateIsStrictAcrossTheWholeAttemptPlan() {
        val attempts = FableSolExportAttemptPlan.ordered(
            hdrFormats = listOf(
                FableSolExportHdrFormat.HDR10,
                FableSolExportHdrFormat.HLG
            ),
            requestedFrameRate = 120
        )

        assertTrue(attempts.isNotEmpty())
        assertTrue(attempts.all { it.frameRate == 120 })
    }

    @Test
    fun hdrRequestKeepsTheSelectedFrameRateAcrossHdrAndSdr() {
        assertEquals(
            listOf(
                attempt(FableSolExportHdrFormat.HDR10, 120),
                attempt(null, 120, tenBit = true),
                attempt(null, 120, tenBit = false)
            ),
            FableSolExportAttemptPlan.ordered(
                hdrFormats = listOf(FableSolExportHdrFormat.HDR10),
                requestedFrameRate = 120
            )
        )
    }

    /**
     * 多种 HDR 格式都可用时，保持选定帧率并按格式优先级依次生成；不得在切换格式前后插入
     * 其它帧率。
     */
    @Test
    fun everyHdrFormatKeepsTheExactRequestedFrameRate() {
        assertEquals(
            listOf(
                attempt(FableSolExportHdrFormat.HDR10, 120),
                attempt(FableSolExportHdrFormat.HLG, 120)
            ),
            FableSolExportAttemptPlan.ordered(
                hdrFormats = listOf(
                    FableSolExportHdrFormat.HDR10,
                    FableSolExportHdrFormat.HLG
                ),
                requestedFrameRate = 120,
                allowSdrFallback = false
            )
        )
    }

    @Test
    fun sdrRequestNeverCreatesAnHdrAttempt() {
        assertEquals(
            listOf(
                attempt(null, 60, tenBit = true),
                attempt(null, 60, tenBit = false)
            ),
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
                attempt(null, 60, tenBit = true),
                attempt(null, 60, tenBit = false)
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
                attempt(null, 60, tenBit = true),
                attempt(null, 60, tenBit = false)
            ),
            FableSolExportAttemptPlan.ordered(
                hdrFormats = listOf(FableSolExportHdrFormat.DOLBY_VISION_84),
                requestedFrameRate = 60
            )
        )
    }

    /**
     * **显式 HDR 格式失败即结束，不发布 SDR（D106）。**
     *
     * 只有"HDR 自动"与两种 SDR 模式才允许出现 SDR 规格；显式格式的失败提示只属于一次真实
     * 导出任务，不得靠悄悄换成 SDR 把任务"做成功"。
     */
    @Test
    fun anExplicitHdrFormatNeverFallsBackToSdr() {
        val attempts = FableSolExportAttemptPlan.ordered(
            hdrFormats = listOf(FableSolExportHdrFormat.HDR10_PLUS),
            requestedFrameRate = 120,
            allowSdrFallback = false
        )
        assertTrue(attempts.none { it.format == null })
        assertEquals(1, attempts.size)
    }

    /**
     * **位深是同规格内的一轴（D160）：自动先穷尽 10-bit，严格位深不跨位深后备。**
     *
     * 帧率是严格规格；位深候选不得额外生成另一帧率。
     */
    @Test
    fun sdrBitDepthIsExhaustedWithinTheSameSpec() {
        assertEquals(
            listOf(
                attempt(null, 120, tenBit = true)
            ),
            FableSolExportAttemptPlan.ordered(
                hdrFormats = emptyList(),
                requestedFrameRate = 120,
                sdrBitDepth = FableSolExportSdrBitDepth.TEN_BIT
            )
        )
        assertEquals(
            listOf(
                attempt(null, 120, tenBit = false)
            ),
            FableSolExportAttemptPlan.ordered(
                hdrFormats = emptyList(),
                requestedFrameRate = 120,
                sdrBitDepth = FableSolExportSdrBitDepth.EIGHT_BIT
            )
        )
    }

    /** HDR 一律 10-bit：不读取隐藏的 SDR 位深偏好，也不提供 8-bit HDR（D160）。 */
    @Test
    fun hdrIgnoresTheHiddenSdrBitDepthPreference() {
        val attempts = FableSolExportAttemptPlan.ordered(
            hdrFormats = FableSolExportHdrFormat.AUTO_ORDER,
            requestedFrameRate = 60,
            sdrBitDepth = FableSolExportSdrBitDepth.EIGHT_BIT,
            allowSdrFallback = false
        )
        assertTrue(attempts.all { it.tenBit })
        assertEquals(FableSolExportHdrFormat.AUTO_ORDER.size, attempts.size)
    }

    private fun attempt(
        format: FableSolExportHdrFormat?,
        frameRate: Int,
        tenBit: Boolean = true
    ) = FableSolExportModeAttempt(format = format, frameRate = frameRate, tenBit = tenBit)
}
