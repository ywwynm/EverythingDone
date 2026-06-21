# Drawer Type Filter — Sessions

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
