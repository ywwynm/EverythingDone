# Drawer Type Filter — Sessions

## 2026-06-22 - Phase E：记事列表 Widget 支持状态维度

`:app:assembleDebug` BUILD SUCCESSFUL。含 DB 迁移 v18→v19。

- DB：`DATABASE_VERSION` 18→19；`app_widget` 表新增 `status` 列（建表 SQL + `migrateAppWidgetStatusColumn` + `SQL_ADD_COLUMN_STATUS_APP_WIDGET`，默认 0=UNDERWAY）。`ThingWidgetInfo.status` 早已能容错读取该列。
- `AppWidgetDAO.insert`：新增 `status` 参数（默认 UNDERWAY），写入时夹取为 UNDERWAY/FINISHED。
- `ThingsListWidgetService`：用 `info.status`（夹取 UNDERWAY/FINISHED，不含回收站）查询 things 和 folder entries，替换原先硬编码的 UNDERWAY。
- `ThingFolderDAO.getFolderEntriesForWidgetProjection`：新增 `status` 参数（默认 UNDERWAY）。
- 配置 UI：在 `activity_things_list_widget_configuration.xml` 显示模式行之上新增“记事状态”行（正在进行/已完成），沿用显示模式的标签+文本选项+pill ripple 样式；`ThingsListWidgetConfiguration` 增 `mStatus`、`setupStatus`/`updateStatusButtons`，从 `info.status` 回显并传入 insert。新增字符串 `widget_status_label`（en/zh-rCN）。

范围限定：Widget 只支持正在进行/已完成，不含回收站（与 use-cases W3 一致）。完整复用 Drawer 的 `ThingFilterPanel`/分段控件留待后续（配置界面目前用自有 XML 类型 UI + 这个状态行）。

## 2026-06-22 - Phase B：胶囊筛选组件 + Drawer 重构

`:app:assembleDebug` BUILD SUCCESSFUL。

新增可复用组件 `views/ThingFilterPanel.kt`：左列状态胶囊（正在进行/已完成，单选），右列类型胶囊（全部类型独占一行，记录/提醒、习惯/目标各一行；全部类型排他、四类多选、清空自动回全部类型）。胶囊含 icon+文本；选中实心填充（根 Scope 用 accent→accent2 渐变，文件夹 Scope 用文件夹纯色/渐变），未选中透明+轮廓，前景按填充亮度自适应黑/白，未选中前景按抽屉表面亮度偏黑/偏白；含 ripple。暴露 `setScopeBackground`/`setSelection`/`onStatusChange`/`onTypeFilterChange`，供 Widget 配置后续复用。

`DrawerNavigationView.kt`：`ItemKey.TypeFilter` → `ItemKey.FilterPanel`；移除旧 `TypeFilterHolder`/`createTypeFilterView`（圆形 icon + summary 文本），改为托管 `ThingFilterPanel` 的 `FilterPanelHolder`；`DrawerItem` 增加 `status`、`scopeBackground`；新增 `setOnStatusFilterChangeListener`。

`ThingsActivity.kt`：Drawer 结构改为 全部记事 Scope 根（`drawer_all_things`）+ 文件夹树 / 胶囊筛选面板 / 回收站行（`drawer_deleted`）/ Settings·Help·About。选中态：状态==DELETED 高亮回收站行，否则高亮当前 Scope 行（全部记事/文件夹）；状态与类型由面板内部独立呈现。状态胶囊点击关闭抽屉并 `changeToStatus`（保留 Scope/类型）；类型胶囊不关抽屉；全部记事行回根（`navigateToFolderPathIndex(-1)`，保留状态/类型）；选文件夹保留状态/类型（移除旧的强制 UNDERWAY + 重置类型逻辑）。

`menu_drawer.xml`：移除 `drawer_underway`、`drawer_finished` 行，新增 `drawer_all_things`，保留 `drawer_deleted`。Scope 根 UI 复用既有字符串 `all_things`（中文“所有记事”）。

状态图标暂用 `drawer_all`（正在进行）/`drawer_finished`（已完成），与类型“全部类型”同用 `drawer_all`，后续可换更贴切的状态图标。

## 2026-06-22 - 文件夹范围和回收站投影语义同步

- 同步最新文件夹语义：Drawer 文件夹范围区域仍显示所有未进入回收站的文件夹；回收站内容列表按投影路径显示包含回收站内容的文件夹，不显示完全没有命中内容的空文件夹。
- 明确文件夹本身进入回收站和文件夹内内容进入回收站是两种情况：前者会让文件夹从正常范围选择器消失，后者只让该文件夹在回收站投影中作为路径容器出现。

## 2026-06-21 - Follow-up: per-type empty placeholders for Drawer filters

Fixed a Drawer type-filter mismatch where selecting all four concrete types
could surface the generic UNDERWAY empty card. `ThingDAO.getThingsCursorForDisplay`
now only includes database `NOTIFY_EMPTY` rows for all-types projections, while
`ThingManager.rebuildThingListEntries()` adds projection-only empty cards for
custom type filters.

For UNDERWAY custom filters, each selected concrete type is evaluated
independently. A visible direct Thing of that type or a visible child Folder
with descendants of that type suppresses the empty placeholder; otherwise a
transient `NOTIFY_EMPTY_NOTE` / `REMINDER` / `HABIT` / `GOAL` card is added.
The transient card is not written to the database and ActivityHeader counts
continue to ignore `NOTIFY_EMPTY` cards.

Verification: `E:\projects\EverythingDone\gradlew.bat :app:assembleDebug`
completed with `BUILD SUCCESSFUL`; `git diff --check` reported only the
repository's existing LF/CRLF warnings. Published debug update `202606210410`
and verified remote `latest.json` points at
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606210410.apk`
with SHA-256 `bf09d62e6fdb084b0b4d17798f707b61b7f96cf3829694117242197e2c37009e`.

## 2026-06-21 — Implementation: back-end refactoring + Drawer UI + debug publish

Completed the full implementation in one pass:

### Back-end: limit → status + typeFilterMask
- `Def.kt`: Added `ThingStatus` (UNDERWAY/FINISHED/DELETED), deprecated `LimitForGettingThings`.
- `ThingListProjection.kt`: `limit` → `status` + `typeFilterMask`.
- `Thing.kt`: Removed `getLimits()`, `isTypeStateMatchLimit`; added `getStatusForState()`, `isStateMatchStatus()`, updated `getNotifyEmptyType()` and `generateNotifyEmpty()`.
- `ThingWidgetInfo.kt`: Added `status` field, `COLUMN_STATUS_APP_WIDGET`, removed `typeFilterMaskForLimit` / `limitForTypeFilterMask`.
- `ThingsCounts.kt`: `getThingsCountForActivityHeader` → `getThingsCountForStatus`, `getCompletionRate` updated.
- `ThingDAO.kt`: `mLimit` → `mStatus` + `mTypeFilterMask`; `setLimit` → `setProjection`; `getThingsCursorForDisplay` rewritten for status+mask; removed `getThingsForTypeFilterProjection`; updated `deleteNotifyEmpty` / `createNotifyEmpty`.
- `ThingFolderDAO.kt`: `thingSelectionForLimit` + `thingSelectionForTypeFilterMask` → `thingSelectionForStatusAndTypeFilter`; added legacy bridge `statusAndMaskForLegacyLimit`.
- `ThingManager.kt`: `mLimit` → `mStatus`/`mTypeFilterMask` via `mProjection`; `setLimit`/`setUnderwayTypeFilterMask` → `setStatus`/`setTypeFilterMask`; updated `createNEnow`/`deleteNEnow`/`willCreateNEforOtherProjection`.
- `App.kt`: `mLimit` → `mStatus`; `getLimit()`/`setLimit()` kept as compatibility wrappers; added `getStatus()`/`setStatus()`.
- Various files updated: `AppWidgetHelper.kt`, `ThingsListWidgetService.kt`, `BaseThingWidgetConfiguration.kt`, `ShortcutActivity.kt`, `StatisticActivity.kt`, `ModeManager.kt`, `ActivityHeader.kt`, `DrawerHeader.kt`, `Snackbar.kt`, `CreateWidget.kt`, `DetailActivity.kt`, `DoingActivity.kt`, `AuthenticationActivity.kt`.

### Drawer UI: type filter icon row
- `DrawerNavigationView.kt`: Added `ItemKey.TypeFilter`, `typeFilterMask` field on `DrawerItem`, `TypeFilterHolder` with 5 ImageViews + summary text, `setOnTypeFilterChangeListener`.
- `ThingsActivity.kt`: Replaced 4 type items with `TypeFilter` item; wired `typeFilterChangeListener` to `setTypeFilterMask` + refresh; renamed `changeToLimit` → `changeToStatus`; updated `handleDrawerDestinationClick` for status-based routing.
- `menu_drawer.xml`: removed `drawer_note`/`drawer_reminder`/`drawer_habit`/`drawer_goal`.
- `strings.xml`: added `all_types` (English: "All types", Chinese: "全部类型").

### Publish
- `assembleDebug`: BUILD SUCCESSFUL.
- `publishDebugUpdate`: Published `202606201706` to Alibaba Cloud.
- APK: `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606201706.apk`
- SHA-256: `3cc64b749ea758aa7ad03a6047c253b698bc697c904853ca9816426c1b7f8dd3`

## 2026-06-21 - Review: limit removal semantic audit

Reviewed recent commits `087dc14a`, `29eb87a4`, `7b375674`, and `863b2b75` for the `limit` to `status + typeFilterMask` migration. `:app:assembleDebug` still builds successfully, but the audit found several semantic mismatches: type filters are reset by status switches, legacy `KEY_LIMIT` can bypass the compatibility mapper in `onNewIntent()`, deleted folders are filtered out by the unified type-filter folder projection, ActivityHeader still exposes type-filter text despite the design decision, widget status is only partially modeled, and FINISHED/DELETED count logic is narrower than the DAO selection. Follow-ups are tracked in `followups.md`.

## 2026-06-21 - Follow-up: remove active limit protocol and fix remaining semantic issues

Removed active `limit` protocol usage from app code by deleting `Def.LimitForGettingThings`, replacing `KEY_LIMIT` with `KEY_STATUS`, removing `App.getLimit()` / `App.setLimit()`, and clearing the unused Detail/DateTime argument chain. Restored DELETED-folder projection semantics under type filtering and aligned FINISHED/DELETED all-type counts with DAO selection. The user explicitly deferred the type-filter persistence, ActivityHeader title, and widget status-design issues.

Published debug update `202606210341` with release notes for the active limit-protocol cleanup and semantic fixes. Remote latest metadata was verified after publish; APK SHA-256 is `23bf6b018f7d5015f0986b87fcf4442aee2804f16a9625d4e1c80614a9c19b86`.
