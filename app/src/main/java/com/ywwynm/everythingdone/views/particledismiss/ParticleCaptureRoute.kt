package com.ywwynm.everythingdone.views.particledismiss

/**
 * 消散/出现抓图走哪条路的规则。抽成纯逻辑是为了能用 JVM 单测钉住——这里的判断在
 * 2026-09-15 的弱设备排查里出过两次事，不能只靠阅读代码保证。
 *
 * 两条路：
 * - **离屏硬件抓图**（[ParticleHardwareSnapshot]）：把弹窗的显示树重录一遍离屏画出来。
 *   不依赖窗口缓冲。弹窗里有可见 `SurfaceView` 时，它留下的透明洞由框架的 `punchHole`
 *   记进 `RecordingCanvas`，各 Surface 的像素随后逐个 `PixelCopy` 合成回去。
 * - **窗口 PixelCopy**（[ParticleSnapshot] 里对 `Window` 的那条）：从合成器里复制窗口
 *   当前的缓冲，是「屏幕上现在长什么样」最直接的来源。
 */
internal object ParticleCaptureRoute {

    /**
     * 首选离屏硬件抓图吗？
     *
     * **消散一律是**（[dismissal]）。原先只有「没有可见独立 Surface」才走离屏，有 Surface
     * 的弹窗先动窗口缓冲；而在魅族 20 Infinity（Flyme / Android 15）上，一次失败的窗口
     * `PixelCopy` 会连带把弹窗自己闪一下——录屏逐帧看，点外部关闭录音弹窗时有**连续两帧
     * 把弹窗裁成直角的一块**（内容位置一个像素没动，只是可见区域被裁小到 974×1357），
     * 整屏亮度掉约 10 个灰阶。这两帧出现在消散受理前后，**早于任何遮盖层或动画层存在**，
     * 且把窗口重试预算从 700 ms 收到 48 ms 之后照旧出现，因此不是重试次数的问题，也不是
     * 暗层交接乱序（差异严格落在弹窗的 bounding box 内，窗外页面逐像素相同）。
     *
     * 消散这一侧有一条经过量化验证的等价备路（离屏重录 + 逐个 Surface 合成：与同一时刻
     * 的系统截屏逐通道绝对差均值 0.07、p99 = 2），没有理由继续先动窗口缓冲。
     *
     * **出现动画不跟着改**：它的末帧要与随后揭开的真实弹窗逐像素相同，窗口缓冲是那条契约
     * 最直接的来源，而且两台设备上一直好用，不在本次报告的范围内。
     *
     * [forced] 是回退用的：首选那条已经失败过一次，这一次一律走离屏。
     */
    fun preferOffscreen(
        hasVisibleSurface: Boolean, dismissal: Boolean = false, forced: Boolean = false
    ): Boolean = forced || dismissal || !hasVisibleSurface

    /**
     * 首选那条路没拿到图之后，要不要用**另一条**再试一次，而不是直接放弃整场动画。
     *
     * [contentUsable] 表示弹窗与宿主这一刻还在（decor 仍附着、Activity 没在结束）——
     * 已经不可用时再试一次只是白等一个超时。
     *
     * 两条方向都允许，各自只发生一次：离屏失败 → 窗口 `PixelCopy`；窗口 `PixelCopy` 失败
     * → 离屏重录 + 逐个 Surface 合成。当初排除后一个方向的理由是「离屏重录画不出
     * `SurfaceView` 的真实透明洞」，那只对软件 `Canvas` 成立，[ParticleHardwareSnapshot]
     * 用的是 `RenderNode.beginRecording()` 的 `RecordingCanvas`，洞是真的。
     */
    fun shouldRetryOtherRoute(snapshotMissing: Boolean, contentUsable: Boolean): Boolean =
        snapshotMissing && contentUsable
}
