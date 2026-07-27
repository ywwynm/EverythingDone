package com.ywwynm.everythingdone.views.recording.fablesol

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 这条规则是从三星 S23 Ultra 上真实的整机失效倒推出来的：高通编码器一律回报 full range
 * （1），而我们申请的是 limited（2）。当时的校验对色彩范围也按"不一致就抛"处理，于是
 * **每一档 HDR 候选都抛 IllegalStateException**，一台色彩空间与 Main10 编码器全都具备的
 * 旗舰被判成"设备不支持 HDR 导出"。
 */
class FableSolExportColorRangeTest {

    /** 回归点：编码器说 full 就写 full，绝不能因此判这一档不可用。 */
    @Test
    fun encoderReportedRangeWins() {
        assertEquals(
            MediaFormat.COLOR_RANGE_FULL,
            FableSolExportColorRange.resolveForMuxer(MediaFormat.COLOR_RANGE_FULL)
        )
        assertEquals(
            MediaFormat.COLOR_RANGE_LIMITED,
            FableSolExportColorRange.resolveForMuxer(MediaFormat.COLOR_RANGE_LIMITED)
        )
    }

    /**
     * 有些编码器压根不把色彩键回显到 outputFormat。这时必须补上我们请求的 limited，
     * 否则 MP4 不带范围标记，播放端只能猜。
     */
    @Test
    fun silentEncoderStillGetsTheRequestedRange() {
        assertEquals(
            MediaFormat.COLOR_RANGE_LIMITED,
            FableSolExportColorRange.resolveForMuxer(null)
        )
    }
}
