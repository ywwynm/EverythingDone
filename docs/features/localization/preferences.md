# Localization Preferences

Migrated from global `memory/preferences.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## Localization

When adding or revising translations, use `values-zh-rCN/strings.xml` as the
source of truth. Do not use Google Translate for this project unless the user
explicitly re-authorizes it. Prefer direct agent-authored translations over
API-generated batches, especially for long Help/About text.

When a new user-visible Settings key is added or revised for a feature, update
the same key in every currently supported non-default locale in the same pass
when the UI is expected to be localised. For feature dialogs, include the nearby
mode labels used by the same dialog so the screen does not become partially
localised.

Exception authorized on 2026-05-27: Google Translate may be used for bulk
Simplified Chinese translation of the `meodai/color-names` colour-name dataset.
This exception is scoped to fine-grained colour-name labels only. English colour
names should keep the upstream source wording, and non-Chinese app locales may
fall back to English until explicitly translated later.
