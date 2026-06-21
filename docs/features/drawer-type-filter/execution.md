# Drawer Type Filter - Execution Checklist

## Phase 1: Data model foundation

- [x] `Def.kt`: Add `ThingStatus` constants for UNDERWAY, FINISHED, and DELETED.
- [x] `ThingListProjection.kt`: Replace the old projection field with `status` + `typeFilterMask`.
- [x] `Thing.kt`: Remove old empty-state projection helpers and update `generateNotifyEmpty()` for status + mask.
- [x] `ThingWidgetInfo.kt`: Add `status` field and keep type-filter bitmask constants.
- [x] `ThingsCounts.kt`: Update count and completion-rate APIs to use status + typeFilterMask.

## Phase 2: DAO and ThingManager

- [x] `ThingDAO.kt`: Replace projection parameter with `status` + `typeFilterMask`.
- [x] `ThingFolderDAO.kt`: Add `thingSelectionForStatusAndTypeFilter`; update type-filter methods with `status`.
- [x] `ThingManager.kt`: Replace projection state with `mStatus` + `mTypeFilterMask`; rename the type-filter setter and update internal call sites.

## Phase 3: Activities and Views

- [x] `ThingsActivity.kt`: Replace status routing and Drawer handling with status + type-filter projection.
- [x] `DrawerNavigationView.kt`: Add `ItemKey.TypeFilter`, `TypeFilterHolder`, type-filter summary text, and type-filter change listener.
- [x] `DrawerHeader.kt`: Unified location text to `completion_rate_things`.
- [x] `ActivityHeader.kt`: Title adaptation now works across status/folder projections.
- [x] `ShortcutActivity.kt`: Updated display-query call sites and removed obsolete projection extra forwarding.
- [x] `StatisticActivity.kt`: Updated `getCompletionRate` calls.
- [x] `DoingActivity.kt`: No legacy projection references; unchanged.
- [x] `DetailActivity.kt`: Removed the unused legacy projection argument passed into `DateTimeDialogFragment`.
- [x] `AuthenticationActivity.kt`: Forwards `KEY_STATUS` when opening authenticated Folder projections.

## Phase 4: Widget and remaining files

- [x] `AppWidgetHelper.kt`: Removed active legacy projection protocol usage and sends `KEY_STATUS`.
- [x] `ThingsListWidgetConfiguration.kt`: Added `status`; removed icon alpha dimming; summary format aligned with Drawer.
- [x] `ThingsListWidgetService.kt`: Updated `getThingsForProjection` call.
- [x] `BaseThingWidgetConfiguration.kt`: Updated `getThingsForProjection` call.
- [x] `CreateWidget.kt`: Removed obsolete projection extra.
- [x] `ModeManager.kt`: Uses `getStatus()` directly.
- [x] `App.kt`: Keeps only `getStatus()`/`setStatus()`; deprecated compatibility wrappers removed.
- [x] `Snackbar.kt`: Uses `getStatus()` directly.
- [x] `DateTimeDialogFragment.kt`: Removed obsolete projection argument.
- [x] `menu_drawer.xml`: Removed `drawer_note`/`drawer_reminder`/`drawer_habit`/`drawer_goal`.
- [x] String resources: Added `all_types`, `completion_rate_things`, `drawer_selected_bg`; i18n done for all locales touched by the implementation.

## Phase 5: Compile and fix

- [x] `assembleDebug` - BUILD SUCCESSFUL after initial implementation.
- [x] `assembleDebug` - BUILD SUCCESSFUL after active legacy projection cleanup.
- [ ] Verify APK installs on device.
- [ ] Run app and test Drawer interactions, type-filter toggles, Folder thumbnails, and ActivityHeader counts.
- [x] Incremental debug updates published during the implementation session.

## Pending follow-ups

No active legacy projection compatibility layer remains in app code. Deferred review items are tracked in `followups.md`.
