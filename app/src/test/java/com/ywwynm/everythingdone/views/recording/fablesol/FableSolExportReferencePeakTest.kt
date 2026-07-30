package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HDR10+ 参考显示峰值滑杆的刻度（fablesol-video-export D94、D116）。
 *
 * 档距不均匀，所以滑杆的 `progress` 是**档位下标**而不是尼特值。下标与尼特的换算只有这一处，
 * 但它同时被滑杆、快捷值、"采用本机值"和持久化四条路径使用——错一档，界面显示的数与写进
 * 载荷的数就会不一致。
 */
class FableSolExportReferencePeakTest {

    private val scale = FableSolExportReferencePeak

    /** 三段档距与端点：`300～1000` 每 25、`1000～4000` 每 100、`4000～10000` 每 500。 */
    @Test
    fun theScaleHasThreeStrideBandsAndExactEndpoints() {
        assertEquals(
            FableSolExportOptions.MIN_REFERENCE_PEAK_NITS, scale.nitsAt(0), 0f
        )
        assertEquals(
            FableSolExportOptions.MAX_REFERENCE_PEAK_NITS,
            scale.nitsAt(FableSolExportReferencePeak.STEPS),
            0f
        )
        // 低段：25 尼特一档，第 28 档正好是 1000。
        assertEquals(325f, scale.nitsAt(1), 0f)
        assertEquals(1000f, scale.nitsAt(28), 0f)
        // 中段：100 尼特一档，第 58 档正好是 4000。
        assertEquals(1100f, scale.nitsAt(29), 0f)
        assertEquals(4000f, scale.nitsAt(58), 0f)
        // 高段：500 尼特一档。
        assertEquals(4500f, scale.nitsAt(59), 0f)
        assertEquals(10000f, scale.nitsAt(70), 0f)
        // 越界不抛，钳到端点。
        assertEquals(300f, scale.nitsAt(-5), 0f)
        assertEquals(10000f, scale.nitsAt(999), 0f)
    }

    /** 刻度严格递增，不存在两档同值——否则滑杆会有"拖不动"的死区。 */
    @Test
    fun everyStepIsStrictlyIncreasing() {
        var previous = -1f
        for (step in 0..FableSolExportReferencePeak.STEPS) {
            val value = scale.nitsAt(step)
            assertTrue("step $step must increase", value > previous)
            previous = value
        }
    }

    /** 下标与尼特互为逆运算；这是"界面显示的数 = 写进载荷的数"的依据。 */
    @Test
    fun indexAndNitsRoundTrip() {
        for (step in 0..FableSolExportReferencePeak.STEPS) {
            assertEquals(step, scale.indexOf(scale.nitsAt(step)))
        }
    }

    /**
     * 取**最近**档而不是向下取整。
     *
     * "采用本机值"读回来的数往往落在两档之间：1600 在中段正好是刻度上的值，而 1650 不是——
     * 向下取整会让用户刚刚看到的 1650 变成 1600，多出一次说不清的偏差。
     */
    @Test
    fun snappingPicksTheNearestStopNotTheFloor() {
        assertEquals(1600f, scale.snap(1600f), 0f)
        assertEquals(1700f, scale.snap(1660f), 0f)
        assertEquals(1600f, scale.snap(1640f), 0f)
        // 低段：312 更靠近 312.5 的上邻 325？不——最近的是 300 与 325 中的 325 距 13、300 距 12。
        assertEquals(300f, scale.snap(312f), 0f)
        assertEquals(325f, scale.snap(315f), 0f)
        // 越界值仍落在合法端点上。
        assertEquals(300f, scale.snap(0f), 0f)
        assertEquals(10000f, scale.snap(99999f), 0f)
    }

    /** 默认值与全部快捷值都必须精确落在刻度上，否则选中态会闪一下又跳开。 */
    @Test
    fun theDefaultAndEveryShortcutSitExactlyOnAStop() {
        assertEquals(
            FableSolExportOptions.DEFAULT_REFERENCE_PEAK_NITS,
            scale.snap(FableSolExportOptions.DEFAULT_REFERENCE_PEAK_NITS),
            0f
        )
        for (shortcut in FableSolExportReferencePeak.SHORTCUTS) {
            assertEquals(
                shortcut.toFloat(), scale.snap(shortcut.toFloat()), 0f
            )
        }
        // D94 指定的五个参考值。
        assertEquals(
            listOf(400, 600, 1000, 2000, 4000),
            FableSolExportReferencePeak.SHORTCUTS.toList()
        )
    }

    /** 刻度范围与 D94/D116 的滑杆范围一致，且默认仍是 1000。 */
    @Test
    fun theRangeMatchesTheDecisions() {
        assertEquals(300f, FableSolExportOptions.MIN_REFERENCE_PEAK_NITS, 0f)
        assertEquals(10000f, FableSolExportOptions.MAX_REFERENCE_PEAK_NITS, 0f)
        assertEquals(1000f, FableSolExportOptions.DEFAULT_REFERENCE_PEAK_NITS, 0f)
    }
}
