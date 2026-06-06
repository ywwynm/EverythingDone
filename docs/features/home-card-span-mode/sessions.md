# Home Card Span Mode Sessions

Migrated from global `memory/sessions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-05-30 - Home Card Span Mode implementation

Implemented the first full-span home-card slice after the planning/grilling
session. The feature stores a Thing-level `homeCardSpanMode` with DB column
`home_card_span_mode`; `0` is normal span and `1` is full span.

Changes:
- Bumped the database to v10, added the new Things table column, and covered
  fresh installs, initial rows, header rows, v9 upgrades, and older restored
  database upgrades with column-exists guarded migration.
- Added `homeCardSpanMode` to `Thing`, cursor mapping, parceling, copying,
  create/update/updateState DAO paths, and `Thing.noUpdate(...)`.
- Added Detail overflow actions for editable underway Things: "放大记事卡片" /
  "缩小记事卡片". The toggle participates in the Detail undo/redo stack and
  normal save/update lifecycle.
- Updated `ThingsAdapter` so only real home-list Things can become full span;
  shared `BaseThingsAdapter` defaults to normal span for embedded cards,
  widgets, and widget configuration previews.
- Added conservative full-span rendering in home/search cards: full content
  width, bounded image height, increased checklist visible rows, larger hidden
  private lock icon, and adjustable sparse-card minimum height.
- Localized the new action labels across all existing `strings.xml` locales
  and added the relevant `dimens.xml` tokens.

Verification:
- `git diff --check` passed with CRLF conversion warnings only.
- `:app:assembleDebug` passed.

## 2026-05-30 - Home Card Span Mode feedback

User asked for immediate feedback after tapping "放大记事卡片" or "缩小记事卡片",
because the actual visual result is only visible back on the home list.

Changes:
- `DetailActivity.toggleHomeCardSpanMode()` now shows feedback immediately
  after changing the edit-state span mode.
- Added `showHomeCardSpanModeFeedback(...)`, using DetailActivity's normal
  Snackbar when available and falling back to Toast if it is not initialized.
- Added localized messages for all existing app locales. Simplified Chinese is
  "已放大记事卡片" and "已缩小记事卡片".

Verification:
- `git diff --check` passed with CRLF conversion warnings only.
- `:app:assembleDebug` passed.
- `:app:publishDebugUpdate` passed and published debug update `202605300306`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-05-30 - Home card fixed-width ownership correction

User reported that the manually adjusted hidden-private-card width still
affected image widths on image Thing cards. Device package was not installed
under the expected app id, so the feedback loop was code-path audit plus debug
build/publish rather than direct UI reproduction.

Diagnosis:
- Hidden private cards and image cards were using two separate fixed-width
  mechanisms on recycled `card_thing.xml` holders.
- Hidden private cards set `llContent.minimumWidth`, while image cards set
  `flImageAttachment.layoutParams.width`.
- That split left width responsibility shared between the parent content
  container and the image child container, making holder reuse sensitive to the
  previously bound card state.

Change:
- `BaseThingsAdapter.applyCardContentGeometry()` now decides whether the card
  needs fixed content width in one place.
- Full-span cards, hidden private cards, and image cards all fix
  `llContent.layoutParams.width` to the current card content width.
- The image attachment container is reset to `MATCH_PARENT`, so image cards fill
  the parent content width instead of carrying their own separate width state.

Verification:
- `git diff --check` passed with CRLF conversion warnings only.
- `:app:assembleDebug` passed.
- `:app:publishDebugUpdate` passed and published debug update `202605300353`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
