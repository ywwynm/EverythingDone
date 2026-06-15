# Home Contextual Toolbar Sessions

## 2026-06-15 - Card appearance action order and icon

- User asked to promote the Thing Card Appearance contextual action so it no
  longer sits near the end of the overflow actions after long-press selection.
- Moved `act_customize_card_appearance` before `act_finish_selected` in the
  underway contextual menu and made it an always-show action when eligible.
- `Finish selected` now follows the appearance action, while still taking its
  former visible position when the appearance action is hidden.
- Added a dedicated card-and-sliders vector icon for the action and referenced
  it from all contextual menu definitions that contain the action.
- Verification: `git diff --check` passed with the repository's existing
  LF/CRLF warnings, and
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606150256`.
- Follow-up: user found the toolbar too crowded because the `Finish selected`
  icon still occupied an action slot after the card appearance action was
  promoted. `act_finish_selected` now uses `showAsAction="never"` and no longer
  declares an icon in the underway contextual menu.
- Follow-up verification: `git diff --check` passed with the repository's
  existing LF/CRLF warnings, and the debug publish task passed and published
  update `202606150309`.

## 2026-06-06 - Selection toolbar status-bar spacer

- User reported that long-pressing a Thing enters the selecting UI with a
  yellow contextual actionbar, but the bar appears shorter than the normal
  home actionbar.
- Diagnosed the layout difference: the normal home actionbar lives inside
  `fl_things`, which is offset below `view_status_bar`, while the contextual
  toolbar is a root-level overlay from `include_contextual_toolbar_things.xml`
  whose height was only `?attr/actionBarSize`.
- Initially tried to reserve the status-bar area inside the contextual toolbar
  wrapper, but that approach still treated the app content view as the owner of
  a region actually drawn by the Window status bar.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606061534` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
  --console=plain --no-configuration-cache`.

## 2026-06-06 - Contextual toolbar padding correction

- User reported that the `202606061534` fix was visually wrong: part of the
  underlying normal/search actionbar could be seen together with the contextual
  toolbar.
- Corrected the implementation from applying the status-bar height as
  `contextual_toolbar`'s top margin to applying it as `rl_contextual_toolbar`
  top padding.
- The wrapper remains painted with `@color/app_accent`, so the status-bar
  reserve is now part of the contextual toolbar's own background instead of a
  child-margin gap.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published corrected debug update `202606061539` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
  --console=plain --no-configuration-cache`.

## 2026-06-06 - System status-bar chrome correction

- User reported that the padding-based correction still did not cover the
  actual system statusbar, and that shifting or adding statusbar height made
  the contextual toolbar geometry look wrong.
- Reframed the issue: the contextual toolbar height should not include the
  statusbar. The system statusbar is Window chrome, so selecting mode should
  switch the Window statusbar colour rather than growing or shifting the
  toolbar view.
- Added a narrow contextual-toolbar visibility callback to `ModeManager`.
  `ThingsActivity` now sets `window.statusBarColor` to `app_accent` and forces
  dark statusbar icons when the contextual toolbar is shown, then restores
  `bg_statusbar_lollipop` and the normal light/dark icon appearance when it is
  hidden.
- Removed the contextual-toolbar wrapper padding; the toolbar keeps its
  standard height.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- User asked not to perform ADB/device visual verification in this agent turn;
  visual verification will be done by the user from the published debug update.
- Published corrected debug update `202606061548` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
  --console=plain --no-configuration-cache`.

## 2026-06-06 - Root overlay statusbar offset correction

- User reported that `202606061548` coloured the statusbar area, but the
  contextual toolbar height still looked identical to the original broken
  state: the visible toolbar content still started too high.
- User then found that `window.statusBarColor = app_accent` still did not turn
  the statusbar yellow on the target device, leaving the normal grey list
  background visible behind system statusbar icons.
- Checked Android's official Android 15 behaviour-change documentation:
  target-SDK-35+ apps are edge-to-edge by default, the statusbar is transparent,
  and `Window#setStatusBarColor` / `statusBarColor` have no effect on Android
  15. Custom statusbar background protection must be drawn by an app view
  behind `WindowInsets.Type.statusBars()`.
- Corrected the geometry model again: `view_status_bar` is the app-owned
  statusbar background and changes between `bg_activity_things` and
  `app_accent`; the entire contextual toolbar overlay starts below that same
  top inset via a root top margin. The `Toolbar` child itself remains
  `?attr/actionBarSize`.
- Extracted `updateStatusBarLayoutOffsets()` in `ThingsActivity` so the normal
  home content offset, statusbar placeholder height, and contextual overlay
  top margin use the same top inset. The inset comes from `WindowInsetsCompat`
  when available, with `DisplayUtil.getStatusbarHeight()` only as fallback.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- User remains responsible for device visual verification from the published
  debug update.
- Published corrected debug update `202606061606` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
  --console=plain --no-configuration-cache`.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- User remains responsible for device visual verification from the published
  debug update.
- Published corrected debug update `202606061554` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
  --console=plain --no-configuration-cache`.

## 2026-06-06 - V19 contextual-toolbar spacer removal

- User clarified that the remaining height mismatch was clearly more than
  1dp and not caused by the contextual-toolbar shadow.
- Rechecked the resource qualifiers and found that
  `layout-v19/include_contextual_toolbar_things.xml` still had its own
  fixed-height internal `view_status_bar` spacer. On API 19+ devices, that
  resource overrides the default layout, so the new outer statusbar placeholder
  and the old internal spacer were both contributing vertical space.
- Removed the internal v19 spacer and aligned the v19 contextual-toolbar layout
  with the default layout: the wrapper has the accent background, the toolbar
  starts at the wrapper top, and `activity_things.xml` remains the sole owner of
  the app-drawn statusbar background.
- Added a defensive fallback so `WindowInsetsCompat` top inset resolution uses
  `DisplayUtil.getStatusbarHeight()` when an early inset dispatch reports zero.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- User remains responsible for device visual verification from the published
  debug update.

## 2026-06-07 - Contextual statusbar overlay isolation

- User reported that the contextual toolbar height was now correct, but the
  whole home page background turned app-accent yellow while selecting.
- Diagnosed the cause: `activity_things.xml` `view_status_bar` is inside
  `DrawerLayout` as a content child. `DrawerLayout` measures content children
  as full content views, so changing that view from `bg_activity_things` to
  `app_accent` recoloured the page background rather than only the statusbar
  strip.
- Added a separate root-level `view_contextual_status_bar` overlay. Its height
  is synced to the same statusbar inset as the normal content offset, but it is
  only visible while the contextual toolbar is shown.
- Kept the original `view_status_bar` on `bg_activity_things` so it continues
  to provide the normal home background/statusbar protection without being
  recoloured for contextual state.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published corrected debug update `202606061614` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
  --console=plain --no-configuration-cache`.

## 2026-06-07 - Contextual toolbar animation root alignment

- User reported two remaining visual issues: entering selection mode made the
  statusbar turn yellow immediately while the contextual toolbar animated down
  separately, and the contextual toolbar shadow appeared yellow.
- Diagnosed both as consequences of splitting the contextual statusbar overlay
  away from `rl_contextual_toolbar` and painting the wrapper background yellow.
- Moved `view_contextual_status_bar` into both contextual-toolbar include
  resources so it shares the same root animation as the toolbar. The wrapper no
  longer uses a statusbar top margin; it starts at the window top and contains
  the inset-height statusbar strip followed by the standard actionbar-height
  toolbar.
- Removed the yellow background from `rl_contextual_toolbar`. Only the
  statusbar strip and toolbar children are yellow, so the transparent part of
  `actionbar_shadow` no longer reveals a yellow parent background.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published corrected debug update `202606061619` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
  --console=plain --no-configuration-cache`.

## 2026-06-07 - Contextual statusbar exit animation

- User reported that exiting selection mode still made the statusbar area
  flicker: the statusbar strip disappeared immediately instead of moving upward
  together with the contextual toolbar.
- Diagnosed that `applyHomeStatusBarChrome()` was setting
  `view_contextual_status_bar` to `INVISIBLE` as soon as the hide callback ran,
  while `rl_contextual_toolbar` was still responsible for the upward hide
  animation.
- Removed the child visibility change from the home-statusbar path. The
  contextual statusbar strip stays visible as a child, and only the parent
  contextual toolbar wrapper controls the enter/exit visibility and animation.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published corrected debug update `202606061623` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
  --console=plain --no-configuration-cache`.
