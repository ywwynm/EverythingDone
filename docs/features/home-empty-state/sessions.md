# Home Empty State Sessions

## 2026-06-27 - 修复搜索有文件夹结果时误显示 no-result

- 诊断用户反馈的“明明有搜索结果但仍显示‘找不到对象’”问题，确认原因是 `ThingsActivity.handleSearchResults()` 只用 `mThingManager.getThings().size == 1` 判空；`mThings` 只包含 header 和记事，不包含搜索命中的文件夹。
- 将搜索空结果判断改为 `ThingManager.hasVisibleProjectionContent()`，按混合列表里的可见 Thing Card 和 Thing Folder Card 共同判断。
- 补充 `ThingManager` 的当前搜索关键词和颜色过滤状态，确保搜索态下后续列表重建仍按搜索范围过滤文件夹。
- 验证：`git diff --check` 通过，仅有既有 LF/CRLF 提示；`:app:assembleDebug --console=plain --no-configuration-cache` BUILD SUCCESSFUL。
- 随 `thing-folders` debug 更新发布，更新码 `202606270601`，远端 `latest.json` 指向 `app-debug-202606270601.apk`，SHA-256 为 `0604f87b9760fab3616d3b14e9786e73b0bb894f7beef58610770f3fe0485dfc`。

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
