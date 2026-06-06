# App Chrome Polish Decisions

Migrated from global `memory/decisions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-05-29 - NoticeableNotificationActivity keeps its embedded Thing row square

`NoticeableNotificationActivity` is a Hybrid Chrome Surface: the outer
dialog-like App Chrome shell may be rounded, but the embedded full-row Thing
card inside the dialog is still Thing Background content and should remain a
square full-row surface. Do not let normal home-card corner radius or App Chrome
dialog corner clipping leak into that embedded card.

## 2026-05-18 - Recurrence picker NORMAL cells: Material FAB → fake-FAB
`RecurrencePickerAdapter`'s `NormalViewHolder` swapped Material FAB
for a fake-FAB (FrameLayout + bg View + RippleDrawable foreground,
mirroring `color_picker_fab.xml`). This lets picked cells carry a real
OVAL `GradientDrawable` instead of being flattened to representative
via `setBackgroundTintList`. The ripple waveform itself remains
single-int representative (Android `RippleDrawable` `ColorStateList`
limit) — assessed acceptable; "real-gradient ripple via custom touch
animation" is a follow-up, not Phase 8 scope.

## 2026-05-18 - Plan §4.7.4 "FAB tint must be single int" is overruled where fake-FAB is feasible
The color-system migration plan classification of FAB as "single-int only"
was too conservative — the fake-FAB pattern bypasses the API
restriction by replacing the widget. Plan §4.7.4 still applies to
genuine Android-API single-int seams (Notification.setColor,
PorterDuff tints, EdgeEffect.setColor, RippleDrawable ColorStateList).
