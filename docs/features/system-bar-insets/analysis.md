# System Bar Insets Analysis

## 2026-06-07 - Initial audit

The current project has a mixed inset model.

Already dynamic:

- `DisplayUtil.chainDecorInsetsCallback(...)` centralizes decor-view inset
  dispatch for bottom margin, bottom padding, and scroll-container padding.
- `ThingsActivity` now sizes its normal and contextual status-bar strips from
  `WindowInsetsCompat.Type.systemBars() | displayCutout()`, with
  `DisplayUtil.getStatusbarHeight(...)` only as a zero-inset fallback.
- Simple App Chrome pages such as Settings, Help, About, Detail, Doing, and the
  single Thing widget configuration already use bottom-inset helpers for sticky
  controls or scroll containers.
- `Snackbar`, `DateTimePicker` bottom-gravity placement, and
  `ThingCardAppearanceSourcePicker` read runtime bottom system-bar insets.

Remaining candidates:

- `DetailActivity` still uses `DisplayUtil.getStatusbarHeight(...)` in its
  scroll margin, image cover height, actionbar shadow, and bottom-bar shadow
  calculations. These should use the same runtime top inset as the visible
  status-bar spacer when the visual depends on the current window.
- `StatisticActivity` uses `DisplayUtil.getStatusbarHeight(...)` in scroll
  thresholds even though its status-bar spacer is dynamically sized.
- `ImageViewerActivity` sets the actionbar top margin from the status-bar
  resource height instead of the current top inset.
- `ColorPicker` and non-`AFTER_TIME` `DateTimePicker` top-gravity popup
  placement still subtract or use `getStatusbarHeight(...)`; these may need a
  runtime top-inset helper, but popup positioning should preserve the existing
  window-relative model from `popup-picker-insets`.
- `BaseThingWidgetConfiguration` may register bottom-margin inset callbacks
  repeatedly during configuration changes and preview-mode transitions. Because
  `applyBottomInsetAsMargin(...)` captures the current margin at registration
  time, repeated registration can double-count or repeatedly overwrite inset
  margin state.
- `DisplayUtil.getNavigationBarHeight(...)` and `hasNavigationBar(...)` remain
  as legacy resource/display-size helpers. Current runtime layout code should
  prefer `WindowInsetsCompat`; legacy helpers can remain only as explicit
  fallbacks for pre-dispatch or non-window contexts.

Open scope question:

- Decide whether the first implementation pass should be a conservative
  App Chrome runtime-inset cleanup, or a broader architectural migration toward
  an Everything-Android-style base activity with `latestWindowInsets`.

Resolved:

- Use a conservative first pass that strengthens the existing `DisplayUtil`
  inset helpers instead of importing Everything-Android's base-activity
  architecture.
- Limit the first code pass to low-risk App Chrome surfaces and helper
  idempotence. Defer top-gravity popup placement changes to a focused follow-up.
- Delete legacy system-bar resource helpers where possible. Because
  `getStatusbarHeight(...)` deletion requires every call site to move, popup
  top placement is back in the first pass as a narrow current-top-inset
  substitution only.

Deferred first-pass candidate:

- Broader popup positioning redesign remains deferred; this pass may only
  replace legacy status-bar resource height with current runtime top inset.

Implemented first pass:

- `DisplayUtil.getStatusbarHeight(...)`, `getNavigationBarHeight(...)`, and
  `hasNavigationBar(...)` were removed from EverythingDone.
- Runtime-dependent App Chrome and popup call sites now use current
  `WindowInsetsCompat`-derived top inset through `DisplayUtil`.
- Bottom inset helper registration is keyed and therefore idempotent for the
  same target/helper pair.
