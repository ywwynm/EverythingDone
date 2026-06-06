# Dark Mode

Status: implemented in multiple passes, with visual QA follow-ups still useful
for dialogs, popups, and device-specific surfaces.

## Documents

- `plan.md` - Appearance Mode and App Chrome dark-mode implementation plan.
- `review-deepseek-2026-05-26.md` - external review notes from the 2026-05-26
  dark-mode pass.

## Related Global Records

- ADRs: none dedicated.
- Domain language: `CONTEXT.md` entries for App Chrome, Appearance Mode, Thing
  Background Surface, and Hybrid Chrome Surface.
- Memory: see `memory/decisions.md`, `memory/sessions.md`, and
  `memory/followups.md` entries around 2026-05-26 through 2026-05-27.

## Notes

Thing-owned surfaces continue to use Thing Background / Thing Foreground rules;
this directory is about App Chrome dark-mode behavior.
