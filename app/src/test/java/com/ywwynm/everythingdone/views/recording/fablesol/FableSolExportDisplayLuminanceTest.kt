package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D82 之后，本机显示能力**只作诊断与观看参考**：默认母版亮度意图与导出设备无关（漫反射白
 * 固定为 D83 的标准 203 尼特）。这里因此只钉两件事：不可信读数一律当作"未声明"，以及诊断
 * 数字的格式。
 *
 * 原先的 7 个用例覆盖的是"由面板峰值、最大帧平均亮度与 HDR 强度推出漫反射白"的自动档
 * （D45）。该语义已被 D82 撤销，对应实现一并移除，测试也不再保留——保留会把一条已经作废的
 * 规则钉成回归门禁。
 */
class FableSolExportDisplayLuminanceTest {

    @Test
    fun implausibleReadingsAreTreatedAsUndeclared() {
        // 低于 300 / 200 尼特通常是占位符，高于一万尼特超出 PQ 定义域；两端都不作数。
        assertNull(FableSolExportDisplayLuminance.plausiblePeak(299f))
        assertNull(FableSolExportDisplayLuminance.plausiblePeak(10001f))
        assertNull(FableSolExportDisplayLuminance.plausiblePeak(Float.NaN))
        assertNull(FableSolExportDisplayLuminance.plausiblePeak(null))
        assertEquals(2000f, FableSolExportDisplayLuminance.plausiblePeak(2000f))

        assertNull(FableSolExportDisplayLuminance.plausibleAverage(100f))
        assertNull(FableSolExportDisplayLuminance.plausibleAverage(-1f))
        assertEquals(325f, FableSolExportDisplayLuminance.plausibleAverage(325f))
    }

    @Test
    fun derivationNumbersDropTrailingZeroOnlyForIntegers() {
        assertEquals("203", FableSolExportDisplayLuminance.formatDerivationNumber(203f))
        assertEquals("400", FableSolExportDisplayLuminance.formatDerivationNumber(400.02f))
        assertEquals("364.6", FableSolExportDisplayLuminance.formatDerivationNumber(364.583f))
    }

    /** 母版意图与导出设备无关：默认漫反射白就是 BT.2408 的名义 HDR 参考白（D82/D83）。 */
    @Test
    fun defaultDiffuseWhiteIsTheDeviceIndependentStandard() {
        assertEquals(203f, FableSolExportOptions.DEFAULT_PQ_WHITE_NITS)
    }
}
