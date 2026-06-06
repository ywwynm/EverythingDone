# Color System Migration Decisions

Migrated from global `memory/decisions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-05-27 - Widget card icons must be luminance-adaptive like card text

RemoteViews do not inherit the normal `BaseThingsAdapter` icon tint pipeline.
Every widget card icon that sits directly on a Thing background should therefore
be set explicitly from the Thing representative colour: black-side assets or a
black color filter on light backgrounds, white-side assets or a white color
filter on dark backgrounds. This covers checklist state, private lock,
sticky/ongoing, reminder/goal, habit, habit record, audio attachment, and
finished/deleted state icons.
