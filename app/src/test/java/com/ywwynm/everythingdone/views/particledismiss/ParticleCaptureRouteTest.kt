package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-15 弱设备排查：魅族 20 Infinity 上「调整颜色」面板点外部关闭时，
 * 窗口 PixelCopy 整个 700 ms 重试窗口都返回 ERROR_SOURCE_NO_DATA，消散整场不播。
 * 这里钉住改后的两条规则。
 */
class ParticleCaptureRouteTest {

    @Test fun `没有可见的独立 Surface 时一律首选离屏硬件抓图`() {
        assertTrue(ParticleCaptureRoute.preferOffscreen(hasVisibleSurface = false))
    }

    @Test fun `有可见的独立 Surface 时仍走窗口 PixelCopy`() {
        // 离屏重录画不出 SurfaceView 的真实透明洞，各 Surface 的像素就合成不回去。
        assertFalse(ParticleCaptureRoute.preferOffscreen(hasVisibleSurface = true))
    }

    @Test fun `离屏没拿到图且弹窗还在时再用窗口 PixelCopy 试一次`() {
        assertTrue(ParticleCaptureRoute.shouldFallbackToWindowCopy(
            snapshotMissing = true, hasVisibleSurface = false, contentUsable = true))
    }

    @Test fun `拿到图就不再重试`() {
        assertFalse(ParticleCaptureRoute.shouldFallbackToWindowCopy(
            snapshotMissing = false, hasVisibleSurface = false, contentUsable = true))
    }

    @Test fun `有可见的独立 Surface 时不往离屏的反方向回退`() {
        // 这一格回退会在画面上留一块空洞，比退回系统默认退出更糟。
        assertFalse(ParticleCaptureRoute.shouldFallbackToWindowCopy(
            snapshotMissing = true, hasVisibleSurface = true, contentUsable = true))
    }

    @Test fun `弹窗或宿主已经不可用时不再白等一个超时`() {
        assertFalse(ParticleCaptureRoute.shouldFallbackToWindowCopy(
            snapshotMissing = true, hasVisibleSurface = false, contentUsable = false))
    }
}
