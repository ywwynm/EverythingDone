# Color System Migration Preferences

Migrated from global `memory/preferences.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## Color migration & UI gradients

**Principle: "If it can render gradient, make it render gradient."**
When migrating UI elements to the `ThingBackground` model, propagate
the full `ThingBackground` signal (not just `representativeColor()`)
to any view whose Android API permits a `Drawable`, `Shader`, or
custom-painted background. Only fall back to `representativeColor()`
when the platform API strictly accepts a single int (PorterDuff tints,
`RippleDrawable` `ColorStateList`, `EdgeEffect.setColor`,
`Notification.setColor`, FAB `setBackgroundTintList`, `setHighlightColor`,
cursor tint, `ProgressBar` tint).

**Ripple waveform** is an accepted single-int compromise:
`RippleDrawable` `ColorStateList` cannot hold a gradient — the
"water-ripple color" itself stays representative. The fake-ripple
alternative (`onTouch` + manual `GradientDrawable` scale animation)
is on the backlog as a follow-up iteration, not current scope.

## Material FAB → fake-FAB

When a Material `FloatingActionButton` blocks gradient rendering
(`setBackgroundTintList` is single-int only), replace it with the
"fake-FAB" pattern used in `ColorPicker.FabViewHolder` / `color_picker_fab.xml`:
clipped-to-oval `FrameLayout` + inner background `View` carrying a
`GradientDrawable` + `setForeground(BackgroundUtil.circularRipple(...))`.
Outline and clipping installed in code via `setOutlineProvider`.
