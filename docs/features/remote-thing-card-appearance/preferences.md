# Remote Thing Card Appearance Preferences

Migrated from global `memory/preferences.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## Remote visual support scope

When evaluating whether to port Thing Card Appearance behavior to remote
surfaces such as AppWidgets and notifications, prefer complete visual support
over a shorter implementation timeline, as long as the added complexity has a
clear path to correctness and maintainability.

For AppWidget size preset expansion, prefer broader launcher-visible default
size coverage, including larger presets up to 6 cells for tablets and large-grid
launchers, over minimizing the number of widget picker entries. The added
provider entries are acceptable when they can share the existing AppWidget
rendering and update infrastructure.

## Widget appearance follow-up discussion

2026-06-05: For follow-up issues around widget preview geometry and media
aspect handling, discuss constraints and design options first instead of
rushing into implementation.
