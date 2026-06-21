# Drawer Type Filter — Decisions

Decisions from the grill session on 2026-06-21. Numbered in discussion order.

---

### 1. Type items become filters, not navigation targets

The four type items (记录/提醒/习惯/目标) change from mutually-exclusive
navigation destinations to a multi-select filter overlay on the current status
+ folder view. They combine with the folder selection above and the 已完成/已删除
status switches below.

### 2. Status switching: mutually exclusive three-way

正在进行 / 已完成 / 已删除 are mutually exclusive (single-select). Only one is
active at a time. This replaces the old limit-based routing where each type had
its own underway variant.

### 3. 正在进行 stays at the top

正在进行 remains an independent clickable item at the top of the Drawer, above
the folder tree. Clicking it navigates back to UNDERWAY status without resetting
the type filter.

### 4. Folder selection unchanged

The folder tree remains single-select and unchanged in behaviour. 正在进行 and
the folder section need no UI changes.

### 5. New Drawer structure (top to bottom)

```
Header
─────────────────
正在进行
─────────────────
[Folder tree]
───────────────── divider
[Summary text: "全部类型" / "记录/提醒" …]
[全部] [记录] [提醒] [习惯] [目标]
───────────────── divider
已完成 / 回收站
─────────────────
Settings / Help / About
```

### 6. Type icons rendered as a RecyclerView item (option A)

A new `ItemKey` variant and dedicated `TypeFilterHolder` inside
`DrawerNavigationView.DrawerAdapter`. Inflates a horizontal LinearLayout with 5
ImageViews, not a standalone header/footer view.

### 7. Type filter persists across status switches (option A)

Switching from 正在进行 to 已完成 to 已删除 keeps the type filter mask active.
e.g. selecting "提醒 + 习惯" then tapping 已完成 shows finished reminders and habits.

### 8. Visual style: Drawer row with separators (option C)

The type-filter row looks like a normal Drawer item but with divider lines above
and below, marking it as a distinct filter section. Icon tinting uses
`app_chrome_drawer_item_foreground` (light-on-dark Drawer background).

### 9. "All" icon semantics match widget config

Tapping 全部 resets to all types (mask = 0). When the last specific type is
deselected, auto-returns to 全部. 全部 is visually "selected" only when no
specific types are active.

### 10. Dividers: both above and below (option C)

The existing divider lines that separate the type section from folders above and
完成/删除 below are preserved, bracketing the type-filter row as a distinct area.

### 11. Summary text above icons

A label above the 5 icons shows the current selection: "全部类型" (none specific),
"记录" (single), "记录/提醒" (two), "记录/提醒/目标" (three), or
"记录/提醒/习惯/目标" (all four). Format matches the existing widget-config
pattern.

### 12. Type filter triggers data refresh via ThingManager

Tapping a type icon calls `ThingManager.setTypeFilterMask(mask)` and refreshes
the current active status query (正在进行/已完成/已删除 + current folder +
type mask). Reuses the existing `ThingDAO.getThingsForTypeFilterProjection()` path.

### 13. Method name: drop "Underway" qualifier

`setUnderwayTypeFilterMask` → `setTypeFilterMask` because the filter now applies
to all status values, not just UNDERWAY.

### 14. Type filter NOT persisted across app restarts

Resets to 全部 on each cold start. No database persistence for the Drawer's
type filter mask.

### 15. Type icon taps do NOT close the Drawer (option A)

Users can multi-select types without the Drawer closing after each tap. Only
导航 items (正在进行, folders, 已完成, 已删除, Settings, Help, About) close it.

### 16. ActivityHeader title: status or folder name, not type

The header shows the current status name or folder name. It does NOT include the
type filter selection. e.g. "工作" (folder) or "已完成" (status).

### 17. DrawerHeader location text: folder name > status name (option A)

If inside a folder, show the folder name. Otherwise show the status name
("正在进行" / "已完成" / "回收站"). Does not include type filter.

### 18. limit → status + typeFilterMask: in-scope, done in one pass

Remove `Def.LimitForGettingThings` constants (0-6). Replace with:
- `STATUS_UNDERWAY = 0`, `STATUS_FINISHED = 1`, `STATUS_DELETED = 2`
- `typeFilterMask` (Int bitmask, same constants as `ThingWidgetInfo`)

### 19. DAO layer: status + typeFilterMask both passed

New/modified DAO methods accept both `status` and `typeFilterMask`, replacing
the old `limit` parameter. `typeFilterMask` works for all status values including
FINISHED and DELETED.

### 20. NOTIFY_EMPTY: 7 variants kept, remapped

- UNDERWAY: 5 (generic + note/reminder/habit/goal type-specific)
- FINISHED: 1 (generic)
- DELETED: 1 (generic)
- `Thing.getLimits()` removed alongside `limit`. Call sites use status + mask directly.

### 21. ThingWidgetInfo: status + typeFilterMask

Widget info stores both fields explicitly instead of deriving status from `limit`.

### 22. ThingListProjection: status + typeFilterMask (option A)

`status: Int` and `typeFilterMask: Int` as independent fields. `typeFilterMask`
works for all status values (including FINISHED).

### 23. ThingManager: mStatus + mTypeFilterMask

`mLimit` field removed. `setLimit()` → `setStatus()`. `setUnderwayTypeFilterMask()`
→ `setTypeFilterMask()`.

### 24. menu_drawer.xml: remove 4 type item IDs

`drawer_note`, `drawer_reminder`, `drawer_habit`, `drawer_goal` menu IDs removed
(only referenced in ThingsActivity.kt and menu_drawer.xml; the drawable PNGs
referenced by StatisticActivity and settings layout stay). `drawer_underway`,
`drawer_finished`, `drawer_deleted` remain.

### 25. TypeFilterHolder: dedicated ViewHolder with LinearLayout

A new view type in `DrawerAdapter` that inflates a horizontal LinearLayout
containing 5 ImageViews + the summary TextView above. To avoid duplication,
reuse or extract the widget config's bitmask toggle logic.

### 26. Drawer close behaviour: only 导航 items

| Action | Closes Drawer |
|---|---|
| Type icon tap | No |
| 正在进行 / 已完成 / 回收站 | Yes |
| Folder tap | Yes |
| Settings / Help / About | Yes |

### 27. Type-filter empty states are projection-only placeholders

When a custom type filter is active, `NOTIFY_EMPTY` rows are not treated as
members of the selected type. The list projection checks each selected concrete
type separately:

- a direct visible Thing of that type counts as content;
- a visible child Folder whose descendants contain that type also counts as
  content;
- only when both checks are empty does the projection add a transient
  `NOTIFY_EMPTY_*` card for that type.

These cards are built in memory for the current projection and are not inserted
into the database. ActivityHeader counts continue to count only real Things and
matching Folder descendants, so empty placeholders do not inflate type counts.
