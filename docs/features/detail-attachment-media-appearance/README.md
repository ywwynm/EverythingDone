# Detail Attachment Media Appearance

2026-10-15 已将同一图片/视频附件上的标识和图标按钮改为统一黑白配色，综合各元素下方内容且每个元素等权；37 项 JVM 回归和两台设备横竖屏 140 项检查通过。debug `202610150857` 已发布、校验并安装到两台指定设备，用户已确认提交本轮改动。见[本轮实施记录](unified-overlay-2026-10-15.md)。

2026-10-11 已实现附件标记与图标按钮的局部对比度配色：按静态封面逐个选择半透明黑白，裁剪、比例或选帧变化后更新，播放期间保持不变，不增加装饰。三机横竖屏 238 个检查项通过，覆盖 19 条已有记事；已发布并安装 debug `202610101718`。见[设计记录](adaptive-overlay-contrast-2026-10-11.md)与[实施、验证记录](implementation-overlay-contrast-2026-10-11.md)。

状态：功能已实现。2026-10-10 先修复主线程解码阻塞，随后改为先根据尺寸展示弹窗，再异步填入媒体，图片加载环复用首页样式并支持记事渐变色；已随 debug `202610100704` 发布，验证与限制见[分阶段展示记录](../detail-checklist-performance/staged-preview-2026-10-10.md)。

2026-10-10 修复未设置外观的单媒体在横屏误用 `3:1` 的问题，统一采用编辑器的 `4:3` 默认比例；三机横竖屏验证通过，已发布 debug `202610101550`。见[诊断与验证记录](diagnosis-2026-10-10-default-ratio.md)。

2026-10-11 修复首次异步加载后内部比例滑条未恢复启用的问题，并按用户要求将外观弹窗分为固定标题、中间滚动内容和固定操作区；上下分割线随内容范围正确显示，滚动内容不会覆盖固定区。三机横竖屏共 48 次比例拖动、48 次裁剪手势通过，19 项单测通过，已发布 debug `202610101618`。见[诊断与验证记录](diagnosis-2026-10-11-first-open-slider.md)。

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
