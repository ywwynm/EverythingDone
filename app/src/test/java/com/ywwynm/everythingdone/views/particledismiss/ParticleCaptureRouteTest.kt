package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-15 弱设备排查，魅族 20 Infinity 上的两件事：
 * 「调整颜色」面板点外部关闭时，窗口 PixelCopy 整个 700 ms 重试窗口都返回
 * ERROR_SOURCE_NO_DATA，消散整场不播；随后发现录音弹窗（有可见 Surface，只能走窗口
 * PixelCopy）同样不播，而且那一次失败的捕获会把弹窗闪成直角两帧。
 * 这里钉住最终的选路规则。
 */
class ParticleCaptureRouteTest {

    @Test fun `没有可见的独立 Surface 时一律首选离屏硬件抓图`() {
        assertTrue(ParticleCaptureRoute.preferOffscreen(hasVisibleSurface = false))
    }

    @Test fun `出现动画在有可见 Surface 时仍首选窗口 PixelCopy`() {
        // 它的末帧要与随后揭开的真实弹窗逐像素相同，窗口缓冲是那条契约最直接的来源。
        assertFalse(ParticleCaptureRoute.preferOffscreen(hasVisibleSurface = true))
    }

    @Test fun `消散即便有可见 Surface 也首选离屏`() {
        // 一次失败的窗口 PixelCopy 会把弹窗自己闪成直角两帧（魅族录屏逐帧实测）。
        assertTrue(ParticleCaptureRoute.preferOffscreen(
            hasVisibleSurface = true, dismissal = true))
    }

    @Test fun `消散没有可见 Surface 时同样首选离屏`() {
        assertTrue(ParticleCaptureRoute.preferOffscreen(
            hasVisibleSurface = false, dismissal = true))
    }

    @Test fun `回退时一律强制走离屏`() {
        assertTrue(ParticleCaptureRoute.preferOffscreen(
            hasVisibleSurface = true, dismissal = false, forced = true))
    }

    @Test fun `首选那条没拿到图且弹窗还在时换另一条再试一次`() {
        assertTrue(ParticleCaptureRoute.shouldRetryOtherRoute(
            snapshotMissing = true, contentUsable = true))
    }

    @Test fun `拿到图就不再重试`() {
        assertFalse(ParticleCaptureRoute.shouldRetryOtherRoute(
            snapshotMissing = false, contentUsable = true))
    }

    @Test fun `弹窗或宿主已经不可用时不再白等一个超时`() {
        assertFalse(ParticleCaptureRoute.shouldRetryOtherRoute(
            snapshotMissing = true, contentUsable = false))
    }
}
