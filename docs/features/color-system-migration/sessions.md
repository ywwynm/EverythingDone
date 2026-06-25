# Color System Migration Sessions

## 2026-06-25 - accent 渐变背景统一配白色前景

- 需求：所有"根据颜色/渐变背景自适应前景明暗"的地方，当背景是 App 默认强调渐变
  （accent + accent2）时直接配白色前景。
- 根因与实现见 [decisions.md](decisions.md) 同日条目。核心是 `BackgroundUtil` 新增
  `isLight(ThingBackground)` / `onColor(ThingBackground, alpha)` / `isAccentGradient(bg)`，
  并把 `BaseThingsAdapter`、`ThingStatusSegmentedView`、`ThingFilterPanel`、
  `DrawerNavigationView`、`ThingsActivity`、`DetailActivity`、`BaseThingWidgetConfiguration`、
  `GradientOrientationDialogFragment`、`AppWidgetHelper` 等处的明暗判断从"先取代表色再判断"
  改为传完整背景。
- 关键认识：`isLight(Int)` 在调用前背景已被 `representativeColor()` 抹成单色，拿不到渐变两端，
  所以无法"只改一个函数"覆盖；必须新增接收 `ThingBackground` 的重载并把调用点改为传背景。

Verification:
- `:app:assembleDebug` 通过（无 error / warning）。
- 纯视觉调整，未在本会话做真机验证，交用户自测。

Publish:
- `publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/color-system-migration/debug-updates/update-20260625121506.md"`
  通过，发布 debug update `202606250415` 到
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`。
- 未创建 Git 提交。

## 2026-06-25 - 补修：根目录上下文操作栏在 accent 渐变下前景仍为黑

- 用户反馈上一版后，根目录长按进入选择模式时，上下文操作栏前景仍是黑色。
- 根因：`refreshContextualToolbarForeground`（由 menu-items-changed 回调触发，会覆盖
  `applyContextualStatusBarChrome` 设的前景）仍是旧逻辑——根目录（folderBackground 为空）时
  foreground 硬编码 `black_86p`。
- 修复：与 `applyContextualStatusBarChrome` 统一，`background = folderBackground ?:
  App.defaultAccentBackground`、`foreground = onColor(background, ON_ALPHA_PRIMARY)`，accent 渐变
  → 白。
- 顺带统一排查确认：`applyThingCardAppearanceSelectedPill` /
  `applyDetailAttachmentAppearanceSelectedPill` 的 else 分支不是 bug——accentBackground 为空时
  pill 背景是纯色代表色而非渐变，前景配黑自洽；FAB 根目录图标用深色是既有的有意设计，不在本
  次范围。

Verification:
- `:app:assembleDebug` 通过（无 error / warning）。交用户自测。

Publish:
- `publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/color-system-migration/debug-updates/update-20260625125751.md"`
  通过，发布 debug update `202606250458` 到
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`。
- 未创建 Git 提交。
