# Feature Documentation

Feature-specific planning, execution, review, and analysis documents live under
`docs/features/<kebab-case-feature-slug>/`.

Use one directory per feature, migration, review track, or substantial technical
initiative. Do not add new feature plans to `docs/plans/`; that directory is
kept only as a legacy location if old untracked notes still exist locally.

## Directory Shape

Each feature directory should use these names when the document type exists:

- `README.md` - status, document map, related ADRs, and important memory links.
- `preferences.md` - feature-specific user or workflow preferences.
- `decisions.md` - feature-specific decisions that do not need a global ADR.
- `followups.md` - deferred or resolved feature-specific follow-up work.
- `sessions.md` - feature-specific session history and implementation notes.
- `plan.md` - intent, scope, product decisions, implementation approach, and
  acceptance criteria.
- `execution.md` - implementation checklist or phased execution log when a
  plan needs one.
- `review.md` or `analysis.md` - audits, investigations, or review reports.
- `debug-updates.md` - archived debug update notes when the notes belong to the
  feature history.

Small features may only need `README.md` and `plan.md`. Large features may add
extra narrowly named files, but keep them inside the feature directory.

## Global Documents

Keep cross-feature sources global:

- `CONTEXT.md` defines project domain language.
- `docs/adr/` stores durable architecture decisions.
- `memory/profile.md`, `memory/preferences.md`, `memory/decisions.md`,
  `memory/sessions.md`, and `memory/followups.md` are lightweight startup
  indexes for cross-feature memory.
- `.agents/rules/` stores operational toolchain rules.

Feature directories may link to those global documents, but should not duplicate
or move their authority. Feature-scoped preferences, decisions, follow-ups, and
session history should live in the feature directory instead of the global
memory files.

## Current Feature Directories

- `android-16-migration/`
- `app-chrome-polish/`
- `appwidget-platform-compat/`
- `color-system-migration/`
- `dark-mode/`
- `debug-update-channel/`
- `detail-color-sampling/`
- `documentation-organization/`
- `home-card-span-mode/`
- `home-contextual-toolbar/`
- `home-new-item-animation/`
- `keep-alive-strategy/`
- `kotlin-migration/`
- `localization/`
- `popup-picker-insets/`
- `project-maintenance/`
- `remote-thing-card-appearance/`
- `system-bar-insets/`
- `thing-folders/`
- `thing-card-appearance/`
- `thing-card-image-placement/`
- `thing-card-media-target-geometry/`
