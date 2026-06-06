# Remote Thing Card Appearance

Status: implemented and debug-published in the first remote-surface pass, with
manual launcher verification still useful.

## Documents

- `plan.md` - AppWidget and notification projection plan for Thing Card
  Appearance.

## Related Global Records

- ADRs: `docs/adr/0002-unified-thing-card-appearance.md` and
  `docs/adr/0003-thing-card-media-target-presentation-geometry.md`.
- Domain language: `CONTEXT.md` entries for Thing Card Appearance, Thing Card
  Surface Projection, AppWidget Size Preset, and Thing Card Media Target Aspect
  Ratio.
- Memory: see `memory/decisions.md`, `memory/sessions.md`, and
  `memory/followups.md` entries around 2026-06-05.

## Notes

Remote surfaces project saved Thing Card Appearance into platform-constrained
RemoteViews and notification surfaces. Surface clamps must not rewrite the
Thing's saved appearance.
