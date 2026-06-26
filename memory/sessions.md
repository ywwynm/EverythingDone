# Sessions

Global startup session index only. Detailed feature history lives in `docs/features/<kebab-case-feature-slug>/sessions.md`.

## 2026-06-26 - 多级清单项设计追问与文档

- 为“最多三级的多级清单项”做了一轮 grill-with-docs，敲定数据模型、存储编码、完成语义、缩进/拖拽
  交互、分级排版与各显示界面。新增功能目录 `docs/features/multi-level-checklist/`、ADR
  `docs/adr/0010-checklist-item-level-encoding.md`，并在根 `CONTEXT.md` 补入清单相关术语。
- 主干设计已定、尚未实现；细节见 `docs/features/multi-level-checklist/`。

## 2026-06-24 - Widget 媒体、单个 Widget 文件夹选择和详情单媒体默认比例

- 完成一次跨 feature 小修复：记事列表 Widget 网格卡片媒体背景改按 slot 宽度投影；单个记事 Widget 配置隐藏空文件夹；详情页单媒体 full-span 默认比例改为 `4:3`，并与用户确认过的设置分离。
- 细节分别记录在 `docs/features/thing-card-media-target-geometry/sessions.md`、`docs/features/thing-folders/sessions.md` 和 `docs/features/detail-attachment-media-appearance/sessions.md`。

## 2026-06-20 - Icon spacing and Folder return restore follow-up

- Completed a follow-up across Thing Folders, Thing Card Appearance, and
  AppWidget presentation: create-Thing vector stroke strength was increased,
  then tuned down; currently-doing icon-to-label spacing was tightened to 4dp;
  and parent Folder return restore now disables the ordinary Things appearing
  animation while applying the saved RecyclerView state before draw.
- Details are recorded in `docs/features/thing-folders/sessions.md`,
  `docs/features/thing-card-appearance/sessions.md`, and
  `docs/features/appwidget-platform-compat/sessions.md`.

## 2026-06-20 - Folder move/privacy polish and media overlay colour fix

- Completed a follow-up slice across Thing Folders, Dark Mode, and Thing Card
  Appearance: Folder move dialogs now show disabled forbidden subtrees, private
  Folder expansion auth is surface-local, Home Folder overflow chrome refreshes
  correctly, and media overlay count colour stays fixed.
- Details are recorded in `docs/features/thing-folders/sessions.md`,
  `docs/features/dark-mode/sessions.md`, and
  `docs/features/thing-card-appearance/sessions.md`.

## 2026-06-18 - Switched shared keyboard helper to WindowInsets

- Replaced the shared keyboard show/hide implementation with direct
  `WindowInsetsCompat.Type.ime()` control after researching the current Android
  guidance for the Folder naming dialog issue.
- Kept the detailed feature context under `docs/features/thing-folders/`.

## 2026-06-06 - Memory documentation reorganization

- Moved feature-scoped preference, decision, follow-up, and session history from global `memory/*.md` into `docs/features/*/` files.
- Rewrote `memory/preferences.md`, `memory/decisions.md`, `memory/followups.md`, and `memory/sessions.md` as lightweight startup indexes.
- Updated `AGENTS.md`, `CLAUDE.md`, and `docs/features/README.md` so new rules point agents to global memory first and then relevant feature directories.

## Feature session indexes

- `android-16-migration`: `docs/features/android-16-migration/sessions.md`
- `appwidget-platform-compat`: `docs/features/appwidget-platform-compat/sessions.md`
- `dark-mode`: `docs/features/dark-mode/sessions.md`
- `debug-update-channel`: `docs/features/debug-update-channel/sessions.md`
- `detail-attachment-media-appearance`: `docs/features/detail-attachment-media-appearance/sessions.md`
- `documentation-organization`: `docs/features/documentation-organization/sessions.md`
- `home-card-span-mode`: `docs/features/home-card-span-mode/sessions.md`
- `home-contextual-toolbar`: `docs/features/home-contextual-toolbar/sessions.md`
- `home-new-item-animation`: `docs/features/home-new-item-animation/sessions.md`
- `kotlin-migration`: `docs/features/kotlin-migration/sessions.md`
- `localization`: `docs/features/localization/sessions.md`
- `popup-picker-insets`: `docs/features/popup-picker-insets/sessions.md`
- `ratio-slider`: `docs/features/ratio-slider/sessions.md`
- `remote-thing-card-appearance`: `docs/features/remote-thing-card-appearance/sessions.md`
- `share-screenshot`: `docs/features/share-screenshot/sessions.md`
- `system-bar-insets`: `docs/features/system-bar-insets/sessions.md`
- `thing-folders`: `docs/features/thing-folders/sessions.md`
- `thing-card-appearance`: `docs/features/thing-card-appearance/sessions.md`
- `thing-card-image-placement`: `docs/features/thing-card-image-placement/sessions.md`
- `thing-card-media-target-geometry`: `docs/features/thing-card-media-target-geometry/sessions.md`

## Update rule

Add a substantive-work entry to this file only when the work is cross-feature or changes the memory/documentation system itself. Otherwise write the session note to the relevant feature directory.
