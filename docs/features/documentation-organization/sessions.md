# Documentation Organization Sessions

Migrated from global `memory/sessions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-06-06 - Feature documentation reorganization

- Reorganized tracked project planning, review, analysis, execution, and debug
  note documents under `docs/features/<kebab-case-feature-slug>/`.
- Added `docs/features/README.md` plus per-feature `README.md` files for
  Android 16 migration, color system migration, dark mode, keep-alive strategy,
  Kotlin migration, Thing Card Image Placement, Thing Card Appearance, Remote
  Thing Card Appearance, and Thing Card Media Target Geometry.
- Moved existing tracked docs with `git mv`, preserving history where the files
  were already tracked. The previously untracked dark-mode review was moved into
  `docs/features/dark-mode/review-deepseek-2026-05-26.md`.
- Archived accumulated Thing Card debug update notes into
  `docs/features/thing-card-media-target-geometry/debug-updates.md` and reset
  `memory/debug-update-notes.md` to a short current-publish template.
- Updated `AGENTS.md`, `CLAUDE.md`, `memory/preferences.md`, and
  `memory/decisions.md` so future feature-specific docs go under
  `docs/features/<kebab-case-feature-slug>/` instead of `docs/plans/`.
- Updated live cross-links and code comments that pointed at old plan file names
  or old `docs/plans/` paths. Historical `memory/sessions.md` path mentions were
  intentionally left unchanged.

Verification:
- Ran the requested stale-reference search for
  `docs/plans|docs/analysis|COLOR_MIGRATION_PLAN|KOTLIN_MIGRATION_PLAN`.
  Remaining hits are intentional rule text, append-only session history, or an
  ignored screenshot-baseline note.
- `git status --short` shows the expected file moves plus new README/template
  files, alongside pre-existing unrelated untracked workspace files.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.

## 2026-06-06 - Memory documentation reorganization

- Moved feature-scoped preference, decision, follow-up, and session history out
  of global `memory/*.md` files and into per-feature files under
  `docs/features/<slug>/`.
- Rewrote `memory/preferences.md`, `memory/decisions.md`,
  `memory/followups.md`, and `memory/sessions.md` as lightweight startup
  indexes.
- Added new feature directories for documentation organization, debug update
  channel, localization, detail color sampling, App Chrome polish,
  AppWidget platform compatibility, popup/inset work, home-card span mode, and
  project maintenance where older memory entries needed a clearer home.
- Updated `AGENTS.md`, `CLAUDE.md`, and `docs/features/README.md` so agents now
  read global memory first, then the relevant feature directory, and write new
  feature-specific records to the feature directory instead of global memory.
- Corrected preference placement after the mechanical split: debug update
  preferences live in `docs/features/debug-update-channel/preferences.md`,
  remote widget preferences live in
  `docs/features/remote-thing-card-appearance/preferences.md`, color-system
  preferences live in `docs/features/color-system-migration/preferences.md`,
  and button-like ripple preferences live in
  `docs/features/app-chrome-polish/preferences.md`.
