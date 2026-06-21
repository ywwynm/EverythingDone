# Drawer Type Filter — Plan

## Intent

Replace the four navigation-destination type items (记录/提醒/习惯/目标) in the
Drawer with a multi-select type-filter icon row, and split the legacy `limit`
concept into `status` + `typeFilterMask` throughout the codebase.

## Scope

### A. Drawer UI
- Remove 记录/提醒/习惯/目标 as standalone navigation items
- Add a type-filter icon row (全部/记录/提醒/习惯/目标) as a new RecyclerView
  view type inside `DrawerNavigationView`
- Summary text above icons: "全部类型" / "记录" / "记录/提醒" etc.
- Icons use circular accent-color background on selection (like widget config)
- Type taps do NOT close the Drawer; 导航 taps do
- Dividers above and below the filter row (existing separator pattern)

### B. Backend: limit → status + typeFilterMask
- Remove `Def.LimitForGettingThings` (0-6)
- Introduce `STATUS_UNDERWAY = 0`, `STATUS_FINISHED = 1`, `STATUS_DELETED = 2`
- `ThingListProjection` stores `status` + `typeFilterMask` independently
- `ThingManager` holds `mStatus` + `mTypeFilterMask`; type filter applies to all
  status values
- DAO queries accept `status` + `typeFilterMask` instead of `limit`
- `Thing.getLimits()` removed; NOTIFY_EMPTY generation uses status directly
- `menu_drawer.xml`: remove 4 type item IDs; keep the rest
- `ThingWidgetInfo` stores `status` + `typeFilterMask` explicitly

## Approach

One-pass refactoring: backend first (foundation), then Drawer UI on top.

### Phase 1 — Define new constants and update data model
1. Add `STATUS_*` constants in `Def.kt`
2. Update `ThingListProjection` (status + typeFilterMask, drop limit)
3. Update `Thing.kt` (remove `getLimits()`, update NOTIFY_EMPTY generation)
4. Update `ThingWidgetInfo` (status field)
5. Update `ThingsCounts`

### Phase 2 — Update DAO and ThingManager
6. Update `ThingDAO` (status parameter, merge type-filter queries)
7. Update `ThingFolderDAO` (same)
8. Update `ThingManager` (mStatus + mTypeFilterMask, all affected methods)

### Phase 3 — Update Activities and Views
9. Update `ThingsActivity` (drawer building, click handling, changeToLimit removal)
10. Update `DrawerNavigationView` (TypeFilterHolder, new DrawerItem variant)
11. Update `DrawerHeader` (location text)
12. Update `ActivityHeader` (title)
13. Update other activities (DetailActivity, ShortcutActivity, StatisticActivity,
    DoingActivity, AuthenticationActivity)

### Phase 4 — Update Widget and remaining files
14. Update `AppWidgetHelper`, widget configuration, widget services
15. Update `ModeManager`, `App.kt`, `Snackbar`, `DateTimeDialogFragment`
16. Update `menu_drawer.xml` (remove type items)
17. Update string resources if needed

### Phase 5 — Compile, fix, verify
18. Compile with `assembleDebug`
19. Fix compilation errors
20. Verify APK installs and runs

## Key Design Points

### Type filter bitmask constants (reuse from ThingWidgetInfo)
```kotlin
const val TYPE_FILTER_ALL: Int = 0
const val TYPE_FILTER_NOTE: Int = 1
const val TYPE_FILTER_REMINDER: Int = 1 shl 1   // 2
const val TYPE_FILTER_HABIT: Int = 1 shl 2       // 4
const val TYPE_FILTER_GOAL: Int = 1 shl 3        // 8
```

### Status constants (new in Def.kt)
```kotlin
const val STATUS_UNDERWAY: Int = 0
const val STATUS_FINISHED: Int = 1
const val STATUS_DELETED: Int = 2
```

### Old limit → new mapping
| Old limit | status | typeFilterMask |
|---|---|---|
| ALL_UNDERWAY (0) | UNDERWAY | ALL (0) |
| NOTE_UNDERWAY (1) | UNDERWAY | NOTE (1) |
| REMINDER_UNDERWAY (2) | UNDERWAY | REMINDER (2) |
| HABIT_UNDERWAY (3) | UNDERWAY | HABIT (4) |
| GOAL_UNDERWAY (4) | UNDERWAY | GOAL (8) |
| ALL_FINISHED (5) | FINISHED | ALL (0) |
| ALL_DELETED (6) | DELETED | ALL (0) |

### NOTIFY_EMPTY mapping
| NOTIFY_EMPTY constant | status | typeFilterMask |
|---|---|---|
| NOTIFY_EMPTY_UNDERWAY (14) | UNDERWAY | ALL (0) |
| NOTIFY_EMPTY_NOTE (15) | UNDERWAY | NOTE (1) |
| NOTIFY_EMPTY_REMINDER (16) | UNDERWAY | REMINDER (2) |
| NOTIFY_EMPTY_HABIT (17) | UNDERWAY | HABIT (4) |
| NOTIFY_EMPTY_GOAL (18) | UNDERWAY | GOAL (8) |
| NOTIFY_EMPTY_FINISHED (19) | FINISHED | ALL (0) |
| NOTIFY_EMPTY_DELETED (20) | DELETED | ALL (0) |
