# System Bar Insets Sessions

## 2026-06-07 - Runtime inset helper cleanup

- User preferred deleting legacy system-bar resource helpers when feasible.
- Removed `DisplayUtil.getStatusbarHeight(...)`,
  `DisplayUtil.getNavigationBarHeight(...)`, and
  `DisplayUtil.hasNavigationBar(...)` from EverythingDone.
- Added runtime `DisplayUtil` helpers for current top system inset, status-bar
  spacer height application, top inset margin, idempotent bottom inset margin,
  bottom inset padding, and scroll padding.
- Reworked `DisplayUtil.chainDecorInsetsCallback(...)` from a list of callbacks
  to a keyed map so repeated helper registration replaces the previous callback
  for the same target/helper pair instead of accumulating duplicate inset
  writers.
- Updated `DetailActivity` and `StatisticActivity` to cache their current
  status-bar top inset from the status-bar spacer callback and use that value in
  scroll-threshold and shadow calculations.
- Updated `ImageViewerActivity` to apply the actionbar top margin through the
  runtime top-inset helper.
- Updated `ThingsActivity` status-bar fallback reads to use the current window
  top inset instead of the deleted resource helper.
- Updated `BaseThingWidgetConfiguration` to clear the bottom-inset margin helper
  when leaving the widget preview/config state and removed the configuration
  change re-registration.
- Replaced the remaining `ColorPicker` and non-`AFTER_TIME` `DateTimePicker`
  top popup placement reads with current runtime top inset, preserving the
  existing window-relative positioning model.
- Verification: `rg` found no remaining `getStatusbarHeight`,
  `getNavigationBarHeight`, `hasNavigationBar`, `status_bar_height`, or
  `navigation_bar_height` references under `app/src/main`.
- Verification: sandboxed `:app:assembleDebug` failed because Kotlin daemon
  access to the user temp directory was blocked; elevated
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- Verification: `git diff --check` passed with CRLF conversion warnings only.
- Published debug update `202606070204` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.
  Remote metadata:
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
