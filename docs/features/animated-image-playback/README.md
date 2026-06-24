# 动图播放(Animated Image Playback)

让 **Animated Image**(GIF、动态 WebP)在更多界面真正逐帧播放,而不仅在详情页默认模式。

## 背景

此前全应用只有详情附件列表的默认(未定制裁切)模式按 Glide Drawable 加载,因此只有那里的 GIF 会动。其余界面都刻意解码成静态 Bitmap:卡片/详情定制/裁切器为套用自定义裁切走 `MediaCropBitmapRenderer` 烘焙;全屏预览为保留 HDR gain map 走 `asBitmap`(见 [ADR-0006](../../adr/0006-hdr-display-scoped-to-fullscreen-image-viewer.md))。

## 结论

按界面分级播放,见 [ADR-0007](../../adr/0007-animated-image-playback-scoped-per-surface.md) 与 [decisions.md](decisions.md)。核心:只在"源是 Animated Image"时开一条独立分支(静态烘焙路径不动),用自定义 Glide `Transformation`(`MediaCropTransformation`)逐帧套用既有裁切。

## 文档

- [decisions.md](decisions.md) — 各项设计决定与理由
- [plan.md](plan.md) — 设计概览与验收标准
- [execution.md](execution.md) — 分阶段实现/验证清单
- 术语 **Animated Image** / **Animated Playback** 见根目录 [CONTEXT.md](../../../CONTEXT.md)
