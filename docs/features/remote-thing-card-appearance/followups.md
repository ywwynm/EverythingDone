# Remote Thing Card Appearance Followups

Migrated from global `memory/followups.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## Remote Thing Card Appearance - Single-Thing AppWidget collection-widget scrolling (deferred 2026-06-05)

**Scope:** Redesign the single-Thing AppWidget as a collection widget so the
widget could expose top-level scrolling for long content or very tall
top/bottom media projections.

**Current fallback:** The current remote-surface direction keeps the
single-Thing AppWidget as a fixed card surface. Thing Card Appearance is
projected into the widget's available height, and oversized media targets are
clamped without rewriting saved Thing Card Appearance fields.

**Reason deferred:** AppWidget scrolling is reliable through collection views,
not arbitrary nested `ScrollView` content inside a normal widget. Rebuilding
the single-Thing widget as a collection widget would change widget structure,
adapter lifecycle, update behavior, and testing scope. It remains possible, but
it is not the default fix for the current appearance-height problems.

## Remote Thing Card Appearance - Fully custom system notification card layout (deferred 2026-06-05)

**Scope:** Reproduce the full Thing Card Appearance model inside system
notifications, including left/right media placement, media background, saved
mask strength, foreground adaptation, and card-height semantics.

**Current fallback:** The remote-surface plan keeps system notifications on the
standard notification style path. Ordinary Thing notifications and ongoing Thing
notifications should upgrade `BigPictureStyle` to respect Thing Card Media
Source, Thing Card Video Frame, and Thing Card Thumbnail Crop, but they do not
try to reproduce the full card layout or media-background model.

**Reason deferred:** Modern Android custom notifications are RemoteViews-based,
height-limited, and wrapped by system templates for target SDK 31+. Fully custom
card visuals would be fragile across devices and notification surfaces, and
could conflict with notification readability expectations.

## AppWidget verification - Real launcher widget click smoke test (deferred 2026-05-27)

**Scope:** Single Thing widgets with checklist rows, Things List widget row
clicks, header/settings/create buttons, Create widget, Check Upcoming widget,
and direct reminder/habit widget action buttons.

**Current state:** Source-level guards verify the Android 16-sensitive
contracts: collection templates are mutable, all AppWidget Activity
PendingIntents go through the BAL creator-opt-in helper, create actions resolve
the new-thing background at click time, and widget card icons are explicitly
luminance-adaptive. `:app:assembleDebug` also passes.

**Deferred verification:** Install the APK on an emulator or explicitly
approved test device, place fresh widgets on the launcher, and click each
button path while watching logcat for `ActivityTaskManager` background-activity
launch blocks and app receiver/action logs.
Also visually check light and dark Thing backgrounds for every card subtype
(note, reminder, habit, goal, private, checklist, attachment-only, finished /
deleted) and verify long reminder/habit text is visible or ellipsized next to
its icon.

**Reason deferred:** No emulator was attached in the 2026-05-27 session; only a
physical device was listed by ADB, so the agent did not take over the user's
launcher state for widget placement and manual-click verification.
