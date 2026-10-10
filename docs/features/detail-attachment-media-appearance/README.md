# Detail Attachment Media Appearance

状态：功能已实现。2026-10-10 先修复主线程解码阻塞，随后改为先根据尺寸展示弹窗，再异步填入媒体，图片加载环复用首页样式并支持记事渐变色；已随 debug `202610100704` 发布，验证与限制见[分阶段展示记录](../detail-checklist-performance/staged-preview-2026-10-10.md)。

## Scope

This feature covers how image and video attachments are presented inside
`DetailActivity`'s attachment list. It is separate from Thing Card Appearance
unless a later decision explicitly reuses shared model pieces.

## Related Context

- `CONTEXT.md` defines Thing Card Appearance, Thing Card Media Target Aspect
  Ratio, and Thing Card Media Crop for card surfaces.
- ADR: `docs/adr/0004-detail-attachment-media-appearance.md`.
- `docs/features/thing-card-appearance/` and
  `docs/features/thing-card-media-target-geometry/` describe the existing card
  media customization model that this feature may borrow from.
- Current `DetailActivity` attachment thumbnails are rendered by
  `ImageAttachmentAdapter` and `AttachmentHelper.calculateImageSize`.
