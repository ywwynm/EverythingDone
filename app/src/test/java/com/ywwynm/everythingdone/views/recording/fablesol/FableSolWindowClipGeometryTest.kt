package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FableSolWindowClipGeometryTest {
    @Test fun `录音海浪铺满窗口时保留四角`() {
        assertEquals(FableSolWindowClipGeometry(0, 0, 720, 1260, 48f),
            FableSolWindowClipGeometry.of(120, 300, 840, 1560, 120, 300, 720, 1260, 720, 1260, 48f))
    }

    @Test fun `设置预览的裁剪底边仍取整个弹窗以保留平直拼接`() {
        assertEquals(FableSolWindowClipGeometry(0, 0, 720, 1800, 48f),
            FableSolWindowClipGeometry.of(120, 200, 840, 2000, 120, 200, 720, 540, 720, 540, 48f))
    }

    @Test fun `有内边距的海浪使用窗口在缓冲中的实际相对位置`() {
        assertEquals(FableSolWindowClipGeometry(-24, -60, 696, 1200, 48f),
            FableSolWindowClipGeometry.of(120, 300, 840, 1560, 144, 360, 672, 1200, 672, 1200, 48f))
    }

    @Test fun `缓冲等比缩放时圆角和坐标一起缩放`() {
        assertEquals(FableSolWindowClipGeometry(0, 0, 360, 630, 24f),
            FableSolWindowClipGeometry.of(120, 300, 840, 1560, 120, 300, 720, 1260, 360, 630, 48f))
    }

    @Test fun `未布局无效轮廓和非等比缓冲不提交错误裁剪`() {
        assertNull(FableSolWindowClipGeometry.of(0, 0, 720, 1260, 0, 0, 0, 0, 720, 1260, 48f))
        assertNull(FableSolWindowClipGeometry.of(0, 0, 720, 1260, 0, 0, 720, 1260, 720, 1260, Float.NaN))
        assertNull(FableSolWindowClipGeometry.of(0, 0, 720, 1260, 0, 0, 720, 1260, 360, 1260, 48f))
        assertNull(FableSolWindowClipGeometry.of(0, 0, 0, 1260, 0, 0, 720, 1260, 720, 1260, 48f))
    }
}
