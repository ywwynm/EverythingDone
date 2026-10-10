# Detail Attachment Media Appearance

状态：功能已实现。2026-10-10 先修复主线程解码阻塞，随后改为先根据尺寸展示弹窗，再异步填入媒体，图片加载环复用首页样式并支持记事渐变色；已随 debug `202610100704` 发布，验证与限制见[分阶段展示记录](../detail-checklist-performance/staged-preview-2026-10-10.md)。

2026-10-10 修复未设置外观的单媒体在横屏误用 `3:1` 的问题，统一采用编辑器的 `4:3` 默认比例；三机横竖屏验证通过，已发布 debug `202610101550`。见[诊断与验证记录](diagnosis-2026-10-10-default-ratio.md)。

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
