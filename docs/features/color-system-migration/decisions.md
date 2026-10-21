# Color System Migration Decisions

## 2026-10-21 - 背景 JSON 解析复用

完全不可变的 `ThingBackground` 按完整 JSON 使用有界内存缓存，与卡片、详情附件外观采用同一机制。纯色、渐变、未知方向及旧颜色回退保持原行为；不改变颜色计算、数据库结构或写入。详见 [详情转场解析优化](../thing-detail-transition/appearance-parsing-2026-10-21.md)。

Migrated from global `memory/decisions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-05-27 - Widget card icons must be luminance-adaptive like card text

RemoteViews do not inherit the normal `BaseThingsAdapter` icon tint pipeline.
Every widget card icon that sits directly on a Thing background should therefore
be set explicitly from the Thing representative colour: black-side assets or a
black color filter on light backgrounds, white-side assets or a white color
filter on dark backgrounds. This covers checklist state, private lock,
sticky/ongoing, reminder/goal, habit, habit record, audio attachment, and
finished/deleted state icons.

## 2026-06-25 - accent 默认强调渐变统一配白色前景

App 默认强调渐变（accent #F66048 → accent2 #FFAE36）的代表色（两端 RGB 平均 ≈ #FA873F）
Rec.601 亮度约 161，刚好高于 `isLight` 的 150 阈值，会被判成浅色背景而配深色前景，但它视觉上
更适合白色前景。

为此在 `BackgroundUtil` 增加接收完整 `ThingBackground` 的 `isLight(ThingBackground)` 与
`onColor(ThingBackground, alpha)` 重载，并加 `isAccentGradient(bg)`：当背景是 accent ↔ accent2
两色渐变（起止顺序不限）时，`isLight` 直接返回 false（按深色背景处理）→ 前景走白；其余背景仍按
代表色亮度判断。必须传完整 `ThingBackground` 而非先 `representativeColor()`——代表色会抹平渐变
两端，无法识别 accent 渐变。

各处"根据背景自适应前景明暗"的调用改为传 `ThingBackground`：首页/文件夹卡片
（`BaseThingsAdapter`）、抽屉与筛选条选中态（`ThingStatusSegmentedView` / `ThingFilterPanel` /
`DrawerNavigationView`）、上下文操作栏与状态栏（`ThingsActivity`，其前景从原来硬编码黑色改为
`onColor(background)`）、详情页前景与菜单图标（`DetailActivity.applyForegroundColors` 等，`color`
仍保留用于 ripple / checklist 强调色）、记事卡片外观与详情附件外观弹窗文字、桌面 widget
（`AppWidgetHelper` 新增 `isThingBackgroundLight(thing)`）。纯色及非 accent 渐变背景行为不变。
