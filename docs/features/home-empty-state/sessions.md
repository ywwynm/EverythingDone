# Home Empty State Sessions

## 2026-06-21 - Replace legacy placeholder Things with view-layer empty states

- Added `PLAN.md` and `EXECUTION.md` from the confirmed discussion before
  implementation.
- Added durable Home Empty State first-use history, initialized from existing
  real Things, existing Thing Folders, and legacy `ThingsCounts.ALL` creation
  counts before deleting old placeholder rows.
- Stopped fresh installs and normal mutation paths from creating `WELCOME_*` or
  `NOTIFY_EMPTY_*` Things; legacy constants now remain only for cleanup and
  compatibility checks.
- Added the home empty-state view beside the RecyclerView and implemented
  guidance selection for welcome, operation-result, ordinary empty, and opened
  Empty Thing Folder states.
- Removed transient/generated `NOTIFY_EMPTY` entries from `ThingManager`,
  replaced placeholder-change adapter paths with ordinary list refresh plus
  empty-state refresh, and kept search/color no-result UI separate.
- Updated Thing Folder handling so structurally empty Folders remain valid
  visible containers instead of being auto-deleted after moves.
- Verified with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-21 - Follow-up audit against confirmed decisions

- Rechecked the implementation against the 16 confirmed Home Empty State
  decisions.
- Removed the now-unused `DBHelper.generateInsertInitialSQL` helper and its
  stale `ThingBackground.fromRandom` comment reference so legacy placeholder
  creation code no longer remains as dead code.
- Verified again with `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606210807` to
  `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606210807.apk`.
  Remote `latest.json` reports SHA-256
  `7d4bf7d6175d9abe0ddb256e7ef1048cdf090e6d5e5381cdd57d0f6240312dd5`.

## 2026-06-21 - Align Empty Folder visibility with filtered projections

- Clarified that Empty Thing Folders remain valid containers, but the home list
  should hide a Folder Card when the current status/type projection has no
  matching Things in that Folder subtree.
- Verified the implementation with `.\gradlew.bat :app:assembleDebug`.
