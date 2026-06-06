# Dark Mode Preferences

Migrated from global `memory/preferences.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## Dark mode dialog polish

When adding dark mode, do not stop at background resources. Dialogs,
popups, pickers, snackbars, and dialog-like activities need explicit
review of text, icons, ripple/pressed states, dividers, edit fields,
progress indicators, and disabled states so their foreground UI adapts
correctly in dark mode.

For app-owned long-running flows such as debug update downloads, prefer a
project `DialogFragment` that follows App Chrome light/dark styling and exposes
live progress details over a detached system-style progress surface.
