# Decisions

Global startup decision index only. Feature-specific decisions live in `docs/features/<kebab-case-feature-slug>/decisions.md`.

## 2026-06-06 - Memory files are lightweight global indexes

Canonical memory files should stay small and cross-feature. Detailed feature-scoped decisions, follow-ups, and session history belong under the relevant `docs/features/<kebab-case-feature-slug>/` directory. Agents should read global memory at session start, then read the relevant feature directory before working in that area.

## 2026-06-06 - Feature documentation directory structure

Feature-specific project documentation is organized under `docs/features/<kebab-case-feature-slug>/`, with one directory per feature, migration, review track, or substantial technical initiative. `CONTEXT.md`, `docs/adr/`, and canonical `memory/*.md` files remain global sources.

## Feature decision indexes

- `android-16-migration`: `docs/features/android-16-migration/decisions.md`
- `app-chrome-polish`: `docs/features/app-chrome-polish/decisions.md`
- `appwidget-platform-compat`: `docs/features/appwidget-platform-compat/decisions.md`
- `color-system-migration`: `docs/features/color-system-migration/decisions.md`
- `dark-mode`: `docs/features/dark-mode/decisions.md`
- `detail-color-sampling`: `docs/features/detail-color-sampling/decisions.md`
- `kotlin-migration`: `docs/features/kotlin-migration/decisions.md`
- `localization`: `docs/features/localization/decisions.md`
- `popup-picker-insets`: `docs/features/popup-picker-insets/decisions.md`
- `project-maintenance`: `docs/features/project-maintenance/decisions.md`
- `remote-thing-card-appearance`: `docs/features/remote-thing-card-appearance/decisions.md`
- `thing-card-image-placement`: `docs/features/thing-card-image-placement/decisions.md`
- `thing-card-media-target-geometry`: `docs/features/thing-card-media-target-geometry/decisions.md`
