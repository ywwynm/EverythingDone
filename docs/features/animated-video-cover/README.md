# 动态视频封面（Animated Video Cover）

让视频封面也能在 Thing Card 上自动循环播放——从视频派生一个 GIF 动图预览（**Thing Card Video Preview**），喂进既有动图管线，由一个默认开的 **Cover Autoplay** 设置统一管控 GIF 与视频封面的自动播放。

## 背景

此前视频封面在卡片上只显示单帧（Thing Card Video Frame）；GIF / 动态 WebP 封面已能逐帧播放（见 [animated-image-playback](../animated-image-playback/README.md)）。本特性把视频也接入动图播放，但视频本身仍不是 Animated Image，而是派生一个预览产物。

## 结论

机制与架构定级见 [ADR-0012](../../adr/0012-thing-card-video-preview-derived-animated-image.md)（修订 [ADR-0007](../../adr/0007-animated-image-playback-scoped-per-surface.md)）。核心：派生 GIF（Square `gifencoder`）→ 既有 `GifDrawable` + `MediaCropTransformation` + Glide 生命周期；Lazy 生成 + `cacheDir` 1GB LRU 持久缓存；一个默认开的 Cover Autoplay 开关统一管控全部应用内 Thing Card 封面面。

## 文档

- [decisions.md](decisions.md) — 各项设计决定与理由（D1–D9）
- [plan.md](plan.md) — 设计概览、各界面行为与验收标准
- [execution.md](execution.md) — 分阶段实现/验证清单
- [followups.md](followups.md) — 后续项（Eager 预热等）
- [sessions.md](sessions.md) — 会话记录
- 术语 **Thing Card Video Preview** / **Cover Autoplay** 见根目录 [CONTEXT.md](../../../CONTEXT.md)

## 状态

已实现并提交（2026-06-30，commit `d4cc8429`）。经多轮真机调试定稿：原生 NDK 编码器、WorkManager 健壮后台、坏 GIF 回退静态帧、尾帧不跳变等。完整历程见 [sessions.md](sessions.md)，已知小优化见 [followups.md](followups.md)。
