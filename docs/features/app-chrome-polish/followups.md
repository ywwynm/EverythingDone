# App Chrome Polish Followups

Migrated from global `memory/followups.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## Color migration - Real-gradient ripple waveform (deferred 2026-05-18)

**Scope:** `RecurrencePickerAdapter` NORMAL fake-FAB cells (picked +
unpicked ripple) and any other place the ripple itself should render
gradient instead of a single representative int.

**Current state:** Fake-FAB renders a real OVAL `GradientDrawable` for
picked cells; the ripple **water-pulse** uses representative single int
(`RippleDrawable`'s `ColorStateList` strictly takes one int — Android API
limit). Visually: the press feedback is a flat circular waveform in the
gradient's middle colour, not a true gradient pulse.

**Path to real gradient:** Drop `RippleDrawable` for these cells.
Implement an `onTouchListener` (or `View.PRESSED` state listener) that
runs a scale + alpha `ValueAnimator` over a circular `GradientDrawable`
to mimic the ripple effect. Hand-roll cancel/recycle so RecyclerView
scrolling doesn't leave half-finished animations behind.

**Risk:** RecyclerView scroll-perf, animation cancel/cleanup on view
recycle, multi-touch, accessibility (ripple announces "selected"
implicitly via state — custom animation needs explicit
`onTouchExplorationStateChanged` handling).
