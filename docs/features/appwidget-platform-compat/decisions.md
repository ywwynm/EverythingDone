# AppWidget Platform Compatibility Decisions

Migrated from global `memory/decisions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-05-27 - Do not add new AppWidget-adjacent resource ids for animation bookkeeping
An attempted fix for duplicate-looking home-card update animation added
`res/values/ids.xml` and keyed view tags for `ThingsAdapter` appearing
animation bookkeeping. That build immediately caused existing AppWidget
RemoteViews to display incorrect/stale-looking checklist and Things-list data
after install. The change was rolled back.

For AppWidget regressions, avoid fixes that add new resource ids or perturb the
resource table unless the AppWidget update/install lifecycle is explicitly
smoke-tested on device. Keep future animation fixes inside existing code paths
or existing resources.

## 2026-05-27 - Background full-list refresh should not replay Things appearing animation

`ThingsActivity.justNotifyAll()` remains the conservative full-list reload path
for stale or coalesced remote updates, but the `onResume()` path that consumes a
background `App.justNotifyAll()` should call it without enabling the
`things_show` first-bind animation. Returning from a launcher widget update is a
data catch-up, not a fresh list presentation, and replaying the bottom-up card
appearance reads as a second update animation.
