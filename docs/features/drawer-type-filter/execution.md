# Drawer Type Filter — Execution Checklist

## Phase 1: Data model foundation

- [x] `Def.kt`: Add `STATUS_UNDERWAY`, `STATUS_FINISHED`, `STATUS_DELETED` in `ThingStatus`
- [x] `ThingListProjection.kt`: Replace `limit` with `status` + `typeFilterMask`
- [x] `Thing.kt`: Remove `getLimits()`, update `generateNotifyEmpty()` for status+mask
- [x] `ThingWidgetInfo.kt`: Add `status` field; keep type-filter bitmask constants
- [x] `ThingsCounts.kt`: Update to use status + typeFilterMask

## Phase 2: DAO and ThingManager

- [x] `ThingDAO.kt`: Replace `limit` parameter with `status` + `typeFilterMask`
- [x] `ThingFolderDAO.kt`: Add `thingSelectionForStatusAndTypeFilter`; update all type-filter methods with `status` param
- [x] `ThingManager.kt`: Replace `mLimit` with `mStatus` + `mTypeFilterMask`;
      rename `setUnderwayTypeFilterMask` → `setTypeFilterMask`;
      update all internal call sites

## Phase 3: Activities and Views

- [x] `ThingsActivity.kt`:
  - Replace `changeToLimit()` with `changeToStatus()` + type-filter handling
  - Update `handleDrawerDestinationClick()` for status-based routing
  - Update `updateDrawerFolderItems()`: remove type items, add type-filter row
  - Wire type-filter toggle to `ThingManager.setTypeFilterMask()`
  - Update drawer click listener: type filter does not close drawer
- [x] `DrawerNavigationView.kt`:
  - Add `ItemKey.TypeFilter` variant
  - Add `TypeFilterHolder` ViewHolder with 5 ImageViews + summary text
  - Toggle logic (XOR toggle, normalized mask)
  - Add `drawer_selected_bg` color for selected background circle
- [x] `DrawerHeader.kt`: Unified location text to `completion_rate_things` (no type)
- [x] `ActivityHeader.kt`: Title adaptation (maxLines/maxWidth/spacer) now universal
- [x] `ShortcutActivity.kt`: Updated `getThingsForDisplay` call
- [x] `StatisticActivity.kt`: Updated `getCompletionRate` calls
- [x] `DoingActivity.kt`: No limit references — unchanged
- [ ] `DetailActivity.kt`: Uses `mApp!!.getLimit()` compat wrapper at line 450 and 3743 (functional, not migrated)
- [ ] `AuthenticationActivity.kt`: Uses `Def.LimitForGettingThings.ALL_UNDERWAY` at line 108 (functional, not migrated)

## Phase 4: Widget and remaining files

- [x] `AppWidgetHelper.kt`: Removed `getLimits`/`limitForTypeFilterMask` usage
- [x] `ThingsListWidgetConfiguration.kt`: Added `status`; removed icon alpha dimming; summary format aligned with drawer
- [x] `ThingsListWidgetService.kt`: Updated `getThingsForProjection` call
- [x] `BaseThingWidgetConfiguration.kt`: Updated `getThingsForProjection` call
- [ ] `CreateWidget.kt`: Uses `Def.LimitForGettingThings.ALL_UNDERWAY` at line 34 (functional, not migrated)
- [ ] `ModeManager.kt`: 9 references to `getLimit()` + `LimitForGettingThings` — works via compat, largest remaining migration target
- [x] `App.kt`: Added `getStatus()`/`setStatus()`; kept `getLimit()`/`setLimit()` as deprecated compat wrappers
- [ ] `Snackbar.kt`: 2 references to `getLimit()` + `GOAL_UNDERWAY` (functional, not migrated)
- [ ] `DateTimeDialogFragment.kt`: References `HABIT_UNDERWAY`/`GOAL_UNDERWAY` (functional, not migrated)
- [ ] `menu_drawer.xml`: Still contains `drawer_note`/`drawer_reminder`/`drawer_habit`/`drawer_goal` IDs — no longer referenced by any code, safe to remove
- [x] String resources: Added `all_types`, `completion_rate_things`, `drawer_selected_bg`; i18n done for all 13 locales

## Phase 5: Compile and fix

- [x] `assembleDebug` — BUILD SUCCESSFUL
- [ ] Verify APK installs on device
- [ ] Run app, test Drawer interactions (type filter toggle, folder thumbnails, ActivityHeader counts)
- [x] Incremental debug updates published throughout the session (latest: `202606210301`)

## Pending follow-ups

### Low priority (works via deprecated compat layer)
These files still reference `Def.LimitForGettingThings` and/or `App.getLimit()` but function correctly via the compatibility wrappers in `App.kt`. They can be migrated incrementally:

| File | Ref count | Notes |
|---|---|---|
| `ModeManager.kt` | 9 | `getLimit() <= GOAL_UNDERWAY` → `getStatus() == UNDERWAY` |
| `DetailActivity.kt` | 2 | `getLimit()` for state checks |
| `Snackbar.kt` | 2 | `getLimit() <= GOAL_UNDERWAY` |
| `DateTimeDialogFragment.kt` | 2 | `HABIT_UNDERWAY` / `GOAL_UNDERWAY` constants |
| `CreateWidget.kt` | 1 | `ALL_UNDERWAY` constant |
| `AuthenticationActivity.kt` | 1 | `ALL_UNDERWAY` constant |

### XML cleanup
- [ ] `menu_drawer.xml`: Remove the 4 unused `<item>` entries for `drawer_note`/`drawer_reminder`/`drawer_habit`/`drawer_goal` and their wrapping `<group>`.
