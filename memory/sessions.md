# Sessions

Global startup session index only. Detailed feature history lives in `docs/features/<kebab-case-feature-slug>/sessions.md`.

## 2026-06-06 - Memory documentation reorganization

- Moved feature-scoped preference, decision, follow-up, and session history from global `memory/*.md` into `docs/features/*/` files.
- Rewrote `memory/preferences.md`, `memory/decisions.md`, `memory/followups.md`, and `memory/sessions.md` as lightweight startup indexes.
- Updated `AGENTS.md`, `CLAUDE.md`, and `docs/features/README.md` so new rules point agents to global memory first and then relevant feature directories.

## Feature session indexes

- `android-16-migration`: `docs/features/android-16-migration/sessions.md`
- `dark-mode`: `docs/features/dark-mode/sessions.md`
- `debug-update-channel`: `docs/features/debug-update-channel/sessions.md`
- `detail-attachment-media-appearance`: `docs/features/detail-attachment-media-appearance/sessions.md`
- `documentation-organization`: `docs/features/documentation-organization/sessions.md`
- `home-card-span-mode`: `docs/features/home-card-span-mode/sessions.md`
- `home-contextual-toolbar`: `docs/features/home-contextual-toolbar/sessions.md`
- `home-new-item-animation`: `docs/features/home-new-item-animation/sessions.md`
- `kotlin-migration`: `docs/features/kotlin-migration/sessions.md`
- `localization`: `docs/features/localization/sessions.md`
- `popup-picker-insets`: `docs/features/popup-picker-insets/sessions.md`
- `remote-thing-card-appearance`: `docs/features/remote-thing-card-appearance/sessions.md`
- `share-screenshot`: `docs/features/share-screenshot/sessions.md`
- `system-bar-insets`: `docs/features/system-bar-insets/sessions.md`
- `thing-card-appearance`: `docs/features/thing-card-appearance/sessions.md`
- `thing-card-image-placement`: `docs/features/thing-card-image-placement/sessions.md`
- `thing-card-media-target-geometry`: `docs/features/thing-card-media-target-geometry/sessions.md`

## Update rule

Add a substantive-work entry to this file only when the work is cross-feature or changes the memory/documentation system itself. Otherwise write the session note to the relevant feature directory.
