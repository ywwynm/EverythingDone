# 详情页动态内容播放（Detail Animated Playback）

让**记事详情页**的动态内容（GIF / 动态 WebP、Live Photo、视频）由一个**四档设置**统一管控自动播放；并给**全屏预览**里的视频补上"自动播一遍 + 长按继续看"。

## 背景

此前详情附件网格的行为是分裂的：GIF 与 Live Photo **无条件无限循环**（Live Photo 走派生 GIF），而视频**从不**请求派生 GIF、永远停在单帧——用户在卡片上看到视频封面在动，点进详情反而不动了。同时详情页完全没有用户控制入口，`Cover Autoplay` 只管 Thing Card 面（见 [animated-video-cover](../animated-video-cover/README.md)）。

## 结论

新增一个独立的四档 **Detail Autoplay** 设置（关闭 / 逐一播放 / 同时播放一次 / 同时循环播放，默认**同时循环播放**），统一管控详情附件网格里 GIF、Live Photo、视频的自动播放，全部档位按**滚动视口**生效。全屏预览不受该设置管控，但视频新增"翻到该页自动播关键帧起 3 秒真视频 → 回静帧；长按从头播、松手回静帧、再长按续播"。

机制与架构定级见 [ADR-0017](../../adr/0017-detail-animated-playback-modes.md)，修订 [ADR-0007](../../adr/0007-animated-image-playback-scoped-per-surface.md) / [ADR-0012](../../adr/0012-thing-card-video-preview-derived-animated-image.md) / [ADR-0014](../../adr/0014-motion-photo-as-image-capability.md)。

## 文档

- [decisions.md](decisions.md) — 各项设计决定与理由（D1–D16）
- 术语 **Detail Autoplay** 见根目录 [CONTEXT.md](../../../CONTEXT.md)

## 状态

设计定稿并首版实现（2026-07-25）。已发布阿里云 debug `202607251240`，尚未真机验证；验收清单见 [plan.md](plan.md) 第四节，性能护栏（同时档并发上限）留待实测后再定（[decisions.md](decisions.md) D13）。
