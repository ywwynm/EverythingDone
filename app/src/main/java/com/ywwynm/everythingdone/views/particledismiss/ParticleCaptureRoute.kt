package com.ywwynm.everythingdone.views.particledismiss

/**
 * 消散抓图走哪条路的规则。抽成纯逻辑是为了能用 JVM 单测钉住——这里的两条判断都在
 * 2026-09-15 的弱设备排查里出过事，不能只靠阅读代码保证。
 *
 * 两条路：
 * - **离屏硬件抓图**（[ParticleHardwareSnapshot]）：把弹窗的显示树重录一遍离屏画出来。
 *   不依赖窗口缓冲，出现动画与「点按钮关闭」一直走这条。
 * - **窗口 PixelCopy**（[ParticleSnapshot] 里对 `Window` 的那条）：从合成器里复制窗口
 *   当前的缓冲。弹窗里有可见的独立 Surface 时只有它能拿到真实的透明洞，各 Surface
 *   的像素才合成得回去。
 */
internal object ParticleCaptureRoute {

    /**
     * 首选离屏硬件抓图吗？
     *
     * 弹窗里没有可见的独立 Surface 时一律是。此前只有「有涟漪」（点按钮关闭）才走离屏，
     * 返回键与点外部退回窗口 PixelCopy；而窗口 PixelCopy 在魅族 20 Infinity
     * （Flyme / Android 15）上对「调整颜色」面板整个 700 ms 重试窗口都返回
     * `ERROR_SOURCE_NO_DATA`，消散整场不播。
     */
    fun preferOffscreen(hasVisibleSurface: Boolean): Boolean = !hasVisibleSurface

    /**
     * 离屏那条路没拿到图之后，要不要再用窗口 PixelCopy 试一次。
     *
     * [contentUsable] 表示弹窗与宿主这一刻还在（decor 仍附着、Activity 没在结束）——
     * 已经不可用时再试一次只是白等一个超时。
     *
     * 反方向（窗口 PixelCopy 失败之后回退到离屏）**不做**：有可见 Surface 时离屏重录
     * 画不出真实的透明洞，合成出来会在画面上留一块空洞，比退回系统默认退出更糟。
     */
    fun shouldFallbackToWindowCopy(
        snapshotMissing: Boolean, hasVisibleSurface: Boolean, contentUsable: Boolean
    ): Boolean = snapshotMissing && !hasVisibleSurface && contentUsable
}
