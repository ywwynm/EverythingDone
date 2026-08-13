# Followups / Deferred Items

Global startup follow-up index only. Feature-specific follow-ups live in `docs/features/<kebab-case-feature-slug>/followups.md`.

## Feature follow-up indexes

- `animated-video-cover`: `docs/features/animated-video-cover/followups.md`
- `app-chrome-polish`: `docs/features/app-chrome-polish/followups.md`
- `cloud-sync`: `docs/features/cloud-sync/followups.md`
- `dark-mode`: `docs/features/dark-mode/followups.md`
- `debug-update-channel`: `docs/features/debug-update-channel/followups.md`
- `detail-color-sampling`: `docs/features/detail-color-sampling/followups.md`
- `home-card-span-mode`: `docs/features/home-card-span-mode/followups.md`
- `home-new-item-animation`: `docs/features/home-new-item-animation/followups.md`
- `kotlin-migration`: `docs/features/kotlin-migration/followups.md`
- `localization`: `docs/features/localization/followups.md`
- `popup-picker-insets`: `docs/features/popup-picker-insets/followups.md`
- `remote-thing-card-appearance`: `docs/features/remote-thing-card-appearance/followups.md`
- `share-screenshot`: `docs/features/share-screenshot/followups.md`
- `system-bar-insets`: `docs/features/system-bar-insets/followups.md`
- `thing-card-media-target-geometry`: `docs/features/thing-card-media-target-geometry/followups.md`
- `thing-folders`: `docs/features/thing-folders/followups.md`
- `timely-digit-typography`: `docs/features/timely-digit-typography/followups.md`

## Update rule

Add a deferred item to this file only when it is cross-feature or does not have a clear feature home. Otherwise write it to the feature directory and keep this file as an index.

## 未归属功能的待办

- 2026-08-05：从系统 DocumentsUI 选择 Downloads 下 `raw:/storage/...` 文档时，
  `UriPathConverter.getPathName` 会把非数字 document ID 传给 `Long.valueOf`，触发
  `NumberFormatException`。本轮空间照片真机测试用 Pictures 原始文件绕过；后续应先补
  `raw:` 与非数字 Downloads ID 回归，再修复通用附件 URI 解析。
