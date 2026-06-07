# System Bar Insets Decisions

## 2026-06-07 - First pass uses existing DisplayUtil inset helpers

The first implementation pass should stay conservative and build on
EverythingDone's existing `DisplayUtil` runtime-inset helpers rather than
importing Everything-Android's `BaseActivity.latestWindowInsets` architecture.

Reasoning:

- EverythingDone already has a decor-view inset chain with IME animation
  handling for bottom padding, bottom margin, and scroll padding.
- The current problems are localized: remaining runtime-dependent layout code
  still reads `getStatusbarHeight(...)`, popup top placement needs a top-inset
  helper, and bottom-margin inset registration should be idempotent.
- Moving inset ownership into `EverythingDoneBaseActivity` would create a new
  app-wide lifecycle contract and could collide with existing helper-based
  padding and margin updates.

Everything-Android remains useful as a reference for keeping the latest
insets available, but this pass should not replace the current activity base
class or route all inset work through Activity overrides.

## 2026-06-07 - First pass scope excludes popup top-placement changes

The first code pass should cover low-risk runtime-inset cleanup only:

- make `DisplayUtil` inset helpers safe for repeated registration;
- add a runtime top-inset helper that can fall back to the legacy resource
  height only before real insets are available;
- replace obvious runtime-dependent `getStatusbarHeight(...)` reads in
  `DetailActivity`, `StatisticActivity`, and `ImageViewerActivity`;
- fix repeated bottom-margin inset registration in
  `BaseThingWidgetConfiguration`.

Top-gravity popup placement in `ColorPicker` and non-`AFTER_TIME`
`DateTimePicker` remains a candidate but is excluded from the first pass. Popup
positioning has existing multi-window and window-relative coordinate decisions
under `popup-picker-insets`, so it should be changed in a focused follow-up
after the simpler App Chrome surfaces are stable.

## 2026-06-07 - Delete legacy system-bar resource helpers where possible

Legacy system-bar resource helpers should be deleted when all call sites can be
moved to runtime `WindowInsetsCompat` reads. `getNavigationBarHeight(...)` and
`hasNavigationBar(...)` have no current EverythingDone callers and should be
removed. `getStatusbarHeight(...)` should also be removed after replacing
runtime layout call sites, including top-gravity popup placement, with an
explicit current top-inset helper.

This updates the earlier popup deferral: popup top placement may be included in
the first pass only as a narrow substitution from legacy status-bar resource
height to the current window top inset. Do not change the existing
window-relative coordinate model or the multi-window positioning decisions from
`popup-picker-insets`.
