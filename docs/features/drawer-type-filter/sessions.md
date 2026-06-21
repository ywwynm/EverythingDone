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
