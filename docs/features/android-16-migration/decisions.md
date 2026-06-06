# Android 16 Migration Decisions

Migrated from global `memory/decisions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-05-27 - AppWidget collection click templates must be mutable
AppWidget collection rows that use `RemoteViews.setPendingIntentTemplate(...)`
plus `setOnClickFillInIntent(...)` need a mutable template `PendingIntent`.
The launcher/widget host supplies the row-specific fill-in intent at send time;
if the template is created with `FLAG_IMMUTABLE`, Android ignores that
additional intent data and row extras such as thing id and checklist position
never reach the app.

Keep ordinary direct widget click actions immutable. Use `FLAG_MUTABLE` only
for explicit-component templates whose behavior depends on collection row
fill-in extras.

## 2026-05-27 - AppWidget activity PendingIntents must opt in to BAL creator delegation
For widget clicks that launch an Activity, the app is the `PendingIntent`
creator and the launcher is the sender. With target SDK 35+ / Android 16-era
background activity launch hardening, the creator can no longer rely on the
launcher to contribute sender-side privileges. Widget `getActivity(...)`
PendingIntents should therefore be created with an `ActivityOptions` bundle
using `setPendingIntentCreatorBackgroundActivityStartMode(...)`.

Apply this only to widget Activity launches. Broadcast-only widget actions
that update app state in-place should remain normal broadcast PendingIntents.
