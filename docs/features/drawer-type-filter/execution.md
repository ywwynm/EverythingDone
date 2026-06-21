# Drawer Type Filter — Execution Checklist

## Phase 1: Data model foundation

- [ ] `Def.kt`: Add `STATUS_UNDERWAY`, `STATUS_FINISHED`, `STATUS_DELETED`
- [ ] `ThingListProjection.kt`: Replace `limit` with `status` + `typeFilterMask`
- [ ] `Thing.kt`: Remove `getLimits()`, update `generateNotifyEmpty()` for status+mask
- [ ] `ThingWidgetInfo.kt`: Add `status` field; keep type-filter bitmask constants
- [ ] `ThingsCounts.kt`: Update to use status

## Phase 2: DAO and ThingManager

- [ ] `ThingDAO.kt`: Replace `limit` parameter with `status` + `typeFilterMask`
- [ ] `ThingFolderDAO.kt`: Same
- [ ] `ThingManager.kt`: Replace `mLimit` with `mStatus` + `mTypeFilterMask`;
      rename `setUnderwayTypeFilterMask` → `setTypeFilterMask`;
      update all internal call sites

## Phase 3: Activities and Views

- [ ] `ThingsActivity.kt`:
  - Replace `changeToLimit()` with `changeToStatus()` + type-filter handling
  - Update `handleDrawerDestinationClick()` for status-based routing
  - Update `updateDrawerFolderItems()`: remove type items, add type-filter row
  - Wire type-filter toggle to `ThingManager.setTypeFilterMask()`
  - Update drawer click listener: type filter does not close drawer
- [ ] `DrawerNavigationView.kt`:
  - Add `ItemKey.TypeFilter` variant
  - Add `TypeFilterHolder` ViewHolder with 5 ImageViews + summary text
  - Toggle logic (reuse/extract from `ThingsListWidgetConfiguration`)
- [ ] `DrawerHeader.kt`: Update location text mapping (status + folder, no type)
- [ ] `ActivityHeader.kt`: Update title mapping (status/folder names)
- [ ] Other activities: `DetailActivity`, `ShortcutActivity`, `StatisticActivity`,
      `DoingActivity`, `AuthenticationActivity`

## Phase 4: Widget and remaining files

- [ ] `AppWidgetHelper.kt`: Update to use status + typeFilterMask
- [ ] `ThingsListWidgetConfiguration.kt`: Update to store status
- [ ] `ThingsListWidgetService.kt`: Update filter logic
- [ ] `BaseThingWidgetConfiguration.kt`: Update
- [ ] `CreateWidget.kt`: Update
- [ ] `ModeManager.kt`: Update limit references
- [ ] `App.kt`: Update limit references
- [ ] `Snackbar.kt`: Update limit references
- [ ] `DateTimeDialogFragment.kt`: Update limit references
- [ ] `menu_drawer.xml`: Remove 4 type item entries
- [ ] String resources: add type-filter summary strings if needed

## Phase 5: Compile and fix

- [ ] `assembleDebug` — fix all compilation errors
- [ ] Verify APK installs
- [ ] Run app, test Drawer interactions
