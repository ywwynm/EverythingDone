# 详情页动态内容播放（Detail Animated Playback）

让**记事详情页**的动态内容（GIF / 动态 WebP、Live Photo、视频）由一个**四档设置**统一管控自动播放；并给**全屏预览**里的视频补上"自动播一遍 + 长按继续看"。

## 背景

此前详情附件网格的行为是分裂的：GIF 与 Live Photo **无条件无限循环**（Live Photo 走派生 GIF），而视频**从不**请求派生 GIF、永远停在单帧——用户在卡片上看到视频封面在动，点进详情反而不动了。同时详情页完全没有用户控制入口，`Cover Autoplay` 只管 Thing Card 面（见 [animated-video-cover](../animated-video-cover/README.md)）。

## 结论

新增一个独立的四档 **Detail Autoplay** 设置（关闭 / 逐一播放 / 同时播放一次 / 同时循环播放，默认**同时循环播放**），统一管控详情附件网格里 GIF、Live Photo、视频的自动播放，全部档位按**滚动视口**生效。全屏预览不受该设置管控，但视频新增"翻到该页自动播关键帧起 3 秒真视频 → 回静帧；长按从头播、松手回静帧、再长按续播"。

机制与架构定级见 [ADR-0017](../../adr/0017-detail-animated-playback-modes.md)，修订 [ADR-0007](../../adr/0007-animated-image-playback-scoped-per-surface.md) / [ADR-0012](../../adr/0012-thing-card-video-preview-derived-animated-image.md) / [ADR-0014](../../adr/0014-motion-photo-as-image-capability.md)。

## 文档

- [decisions.md](decisions.md) — 各项设计决定与理由（D1–D16）
- [2026-10-20 重播崩溃排查](crash-investigation-2026-10-20.md) — GIF 共享解码状态、双请求复现与独立播放修复
- [独立解码的内存与真实首页复核](owner-performance-2026-10-20.md) — 三机 A/B、同源多实例开销，以及追加发现的首页大 GIF 输入流 OOM
- [2026-10-15 闪烁排查](flicker-investigation-2026-10-15.md) — 三机对照、首页历史核对与验证边界
- [逐一播放、键盘与复杂页面](sequential-ime-investigation-2026-10-15.md) — 快速甩动、大 GIF、静态占位和批量队列调度
- [大 GIF 循环交界专项](loop-boundary-investigation-2026-10-15.md) — 混排、实际窗口像素与循环／播完交接对齐
- [闪烁集成回归](checks/README.md) — 隔离包执行方法与机器可读结果
- 术语 **Detail Autoplay** 见根目录 [CONTEXT.md](../../../CONTEXT.md)

## 状态

首版于 2026-07-25 实现。2026-07-31 修复 Glide 清理与重复启动崩溃；2026-10-15 已在三台指定设备复现详情滑动返回的静态主图闪回和重载空白，修复循环资源选择及有界占位，并补查已播放缓存和高分辨率通栏。连续循环结束单独露出底色暂未捕获，不能认定与已复现问题完全同因，详见排查记录。性能护栏（同时档并发上限）仍见 [decisions.md](decisions.md) D13。

同日追加「逐一 + IME + 快速甩动」测试，复杂夹具生成约 32–35 MB 的 GIF，证实静态待播图回收后缺失占位，以及批量离屏中途推进队列的问题；分别补齐有界静态占位和整批可见性更新。继续检查同时循环发现大 GIF 的整文件数组读取会导致堆分配失败，本地动态请求改为 File 映射加载。最终三机复杂通栏快滑共 40,472 条绘制记录无空白、无误并发、无 OOM，仍保留按可见区间播放一次的语义。

进一步的混排窗口专项累计 1497 次循环回绕仍未单独捕获交界露底，但复现跨模式返回的静动占位键缺口并修复；三机冷缓存对照通过，debug `202610151555` 已发布。窗口采样缺失与验证范围单列在专项记录中，不把未采到解释为任何环境都不存在。
