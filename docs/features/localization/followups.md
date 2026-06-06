# Localization Followups

Migrated from global `memory/followups.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## Detail colour sampling and information - Native review for machine-translated Simplified Chinese colour names (deferred 2026-05-28)

**Scope:** The bundled `color-name-list` 14.38.0 TSV now has a populated `zh`
column for Simplified Chinese translations of the upstream English colour
names.

**Current state:** All 31,902 colour-name rows were translated with Google
Translate on 2026-05-28. The first successful batches used
`translate.googleapis.com`; after that endpoint returned HTTP 429, the
remaining rows were translated through Google Translate's mobile web endpoint
with stable marker parsing. The runtime can now show Chinese colour names in
Chinese locales.

**Deferred verification:** Native-speaker and colour-domain review of machine
translations. Some upstream names are puns, brands, place names, or invented
labels, so Google Translate can produce literal or mixed English/Chinese names.

## Localization - Native-speaker review for new app languages (deferred 2026-05-27)

**Scope:** Japanese, Korean, Italian, Spanish, Russian, French, German, Hindi,
and Portuguese string resources, especially long Help/About copy.

**Current state:** Resources compile, visible Google-translation protection
tokens were removed, and the long Help strings were reworked from the
Simplified Chinese source instead of from the failed Google batch output.

**Deferred verification:** Have native speakers review terminology, tone, and
long-form Help readability. Also smoke-test Settings language switching on
device across a few screens whose Activities do not share the common base class.

**Reason deferred:** The current session could verify build/resource validity,
but not human-level localization quality or on-device locale switching visuals.
