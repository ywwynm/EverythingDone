# Drawer Type Filter

**Status:** planning (grill session completed 2026-06-21)

Replace the four navigation-destination type items (记录/提醒/习惯/目标) in the
Drawer with a multi-select type-filter icon row matching the widget-configuration
pattern (全部/记录/提醒/习惯/目标), and split the legacy `limit` concept into
`status` + `typeFilterMask`.

## Documents

- `decisions.md` — design decisions from the grill session.
- `plan.md` — implementation plan (todo).
- `execution.md` — phased execution log (todo).
- `followups.md` — deferred work (todo, if any).

## Related

- `docs/features/thing-folders/` — folder selection in the Drawer, which sits
  above the new type-filter row.
- `docs/features/app-chrome-polish/` — Drawer visual styling patterns.
- `docs/adr/` — may need an ADR for the `limit` → `status` + `typeFilterMask`
  split since it touches 24 files and is hard to reverse.
