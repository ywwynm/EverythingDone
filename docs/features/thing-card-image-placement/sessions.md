# Thing Card Image Placement Sessions

Migrated from global `memory/sessions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-05-31 - Home card image placement implementation

Implemented the first version of persistent Home Card Image Placement after a
grill-with-docs planning pass.

Planning and docs:
- Added the **Home Card Image Placement** glossary term to `CONTEXT.md`.
- Recorded the confirmed design in `memory/decisions.md`.
- Added `docs/plans/HOME_CARD_IMAGE_PLACEMENT_PLAN.md`.

Change:
- Added Thing-level `homeCardImagePlacement` and SQLite
  `home_card_image_placement` with database version 11 migration and first
  install schema support.
- Added Detail image-tile placement entry on the first image/video attachment,
  plus tap-to-apply chooser dialog, undo/redo action, save lifecycle support,
  and Snackbar/Toast feedback.
- Added localized labels/messages for default, top, bottom, left, and right
  placement.
- Restructured `card_thing.xml` into image and text content containers.
- Implemented normal-span top/bottom placement and full-span top/bottom/left/right
  placement. Side images use a 42% resource ratio, a 180dp min-height resource,
  physical left/right semantics, and `centerCrop` fill behavior.
- Hidden-private home cards continue to ignore image placement.

Verification:
- `git diff --check` passed with CRLF conversion warnings only.
- `:app:assembleDebug` passed.

## 2026-05-29 - Home image-card width correction

User reported that image attachments on home Thing cards no longer filled the
full card width, and asked whether the earlier hidden-private-card width fix had
caused it. Diagnosis confirmed the regression was in the dynamic card-width
refresh added by that fix: `refreshCardWidthFromRecyclerView()` first subtracted
RecyclerView left/right padding, then reused the full-screen spacing formula
with `(spanCount + 1)`, double-counting the outer spacing. `updateCardForImageAttachment()`
uses `mCardWidth` directly, so image surfaces became narrower than the actual
card.

Change:
- Updated `BaseThingsAdapter.refreshCardWidthFromRecyclerView()` so the dynamic
  path subtracts only per-item margins after RecyclerView padding has already
  been removed: `(width - spacing * 2 * spanCount) / spanCount`.

Verification:
- `git diff --check` passed with CRLF conversion warnings only.
- `:app:publishDebugUpdate` passed and published debug update `202605290623` to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-05-31 - Home card image placement numeric tuning

User requested final numeric tuning for the placement chooser and full-span
side-image cards.

Change:
- `ChooserDialogFragment` no-action chooser bottom margin was changed from 8dp
  to 12dp.
- `thing_card_full_span_side_image_min_height` was changed from 144dp to 128dp.

Verification:
- `git diff --check` passed with CRLF conversion warnings only.
- `:app:assembleDebug` passed.
- `:app:publishDebugUpdate` passed and published debug update `202605310249`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-05-31 - Home card side-image Glide reload fix

User reported that full-span home cards with images placed on the left or right
still appeared to reload through Glide while scrolling, even when card size and
image placement did not change.

Diagnosis:
- The home-card image load key was stored on `ImageView.tag`, which Glide 4 also
  uses for its request object. After `.into()`, the business load key could be
  overwritten, so later binds did not hit the skip path.
- Side-image cards reset the image container height to the 128dp minimum on each
  bind and then corrected it after layout from the measured text-column height.
  Rebinding during scroll could therefore flip the load key between minimum and
  measured heights.

Change:
- Added keyed tag ids for the home-card image load key and side-image bind token.
- Moved the image load key to `R.id.tag_home_card_image_load_key`, avoiding the
  Glide request tag.
- Cached measured side-image heights per Thing/signature so unchanged side-image
  cards reuse the previous measured height during scroll rebinding.
- Avoided resetting image and cover layout params when width/height are already
  unchanged.

Verification:
- `git diff --check` passed with CRLF conversion warnings only.
- `:app:assembleDebug` passed.
- `:app:publishDebugUpdate` passed and published debug update `202605310850`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-05-31 - Home card image progress and count placement polish

User reported that full-span side-image cards could still briefly show the
loading spinner while scrolling, including image-only full-span cards. User also
requested the image/video count label to appear at the bottom-right when the
image is placed on the right, and bottom-left otherwise.

Diagnosis:
- RecyclerView scroll reuse can bind the same Thing to a different `ImageView`.
  Glide still needs to bind the image to that new view; that is not necessarily
  redundant decoding or disk loading.
- The visible problem was the progress indicator: once the same image path and
  target size has already loaded successfully, later binds should not show a
  spinner while Glide fills the new view from cache.

Change:
- Added an adapter-level loaded-image-key set. Previously loaded home-card
  image path/size pairs no longer show `pbLoading` on later binds.
- Hide `pbLoading` on matching load failure as well, preventing a stuck spinner.
- Added `dontAnimate()` to home-card image requests to reduce cache-fill visual
  flicker.
- Updated the image/video count label gravity: right-side images use
  bottom-right, all other placements use bottom-left.

Verification:
- `git diff --check` passed with CRLF conversion warnings only.
- `:app:assembleDebug` passed.
- `:app:publishDebugUpdate` passed and published debug update `202605310901`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-05-31 - Home card image count label margin tuning

User noticed that the Thing Home Card image/video count label used different
horizontal and vertical margins. User requested both directions use the larger
existing value.

Change:
- Updated `tv_thing_image_attachment_count` bottom margin from 8dp to 12dp,
  matching the existing left and right margins.

Verification:
- `git diff --check` passed with CRLF conversion warnings only.
- `:app:assembleDebug` passed.
- `:app:publishDebugUpdate` passed and published debug update `202605310908`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-05-31 - Home card image count label margin retuning

User felt the unified 12dp margin for the Thing Home Card image/video count
label was slightly too large.

Change:
- Updated `tv_thing_image_attachment_count` left, right, and bottom margins to
  10dp.

Verification:
- `git diff --check` passed with CRLF conversion warnings only.
- `:app:assembleDebug` passed.
- `:app:publishDebugUpdate` passed and published debug update `202605310910`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-05-31 - Detail image attachment action icon polish

User reported that the Detail image-placement icon looked brighter than the
delete icon, and that both image attachment action icons still showed square
press feedback.

Change:
- Changed `ic_home_card_image_placement` from opaque white fills to
  `white_76p`, matching the existing delete image attachment icon's effective
  opacity.
- Added `ripple_attachment_icon_circle_light`, a light ripple drawable with an
  oval mask.
- Updated the Detail image placement and delete attachment buttons to use that
  shared circular ripple.

Verification:
- `git diff --check` passed with CRLF conversion warnings only.
- `:app:assembleDebug` passed.
- `:app:publishDebugUpdate` passed and published debug update `202605310922`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-05-31 - Detail image attachment ripple inset tuning

User reported that the circular ripple on the Detail image-placement and delete
attachment buttons still touched the bottom edge of the image tile.

Change:
- Kept the 40dp button/touch target unchanged.
- Inset the circular ripple mask by 2dp on all sides, making the visible ripple
  slightly smaller and leaving space from the image edge.

Verification:
- `git diff --check` passed with CRLF conversion warnings only.
- `:app:assembleDebug` passed.
- `:app:publishDebugUpdate` passed and published debug update `202605310927`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- `:app:publishDebugUpdate` passed and published debug update `202605310155`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-05-31 - Home card image placement layout corrections

User reported three layout issues after the first Home Card Image Placement
build:
- bottom image placement had no gap between the preceding content and the image
  in both normal-span and full-span cards;
- full-span left/right placement should constrain side-image height by the
  measured non-image content height, keep width at the configured ratio, crop
  inside that panel, and keep the image count UI inside the image panel;
- switching between normal-span and full-span could leave stale image ratio or
  size, likely from recycled holder state and Glide target sizing.

Change:
- `BaseThingsAdapter` now gives bottom image placement a 16dp top gap when the
  card has preceding content, while still hiding the final bottom spacer.
- Full-span side image layout now fixes the image column width first, then
  synchronizes image container height after layout from the measured content
  column height and side-image minimum height.
- Side image children remain `MATCH_PARENT` inside the fixed side panel, so the
  image count label stays inside `fl_thing_image`.
- Image binding now clears the old Glide request and reloads with
  `override(width, height)` for the current placement/span target size.

Verification:
- `git diff --check` passed with CRLF conversion warnings only.
- `:app:assembleDebug` passed.
- `:app:publishDebugUpdate` passed and published debug update `202605310218`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-05-31 - Home card image placement polish corrections

User reported three polish/performance issues:
- the tap-to-apply image placement dialog's final option touched the dialog
  bottom because the action row was hidden;
- full-span side-image cards with short content still had too much empty space
  under the content column;
- home-card image binding should not clear/reload Glide on every bind when the
  image path and target size have not changed.

Change:
- `ChooserDialogFragment` now adds an 8dp bottom margin to the chooser list when
  the action row is hidden.
- `thing_card_full_span_side_image_min_height` was lowered from 180dp to 144dp.
- `BaseThingsAdapter.loadHomeCardImage()` now uses `path + width + height` as a
  load key and skips `clear()` / Glide reload when that target is unchanged.

Verification:
- `git diff --check` passed with CRLF conversion warnings only.
- `:app:assembleDebug` passed.
- `:app:publishDebugUpdate` passed and published debug update `202605310228`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- `:app:publishDebugUpdate` passed and published debug update `202605291935`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
