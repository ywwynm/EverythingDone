# Thing Card Image Placement Plan

## Goal

Allow users to choose where the first image/video attachment appears inside a
Thing Card.

## Scope

- Add persistent Thing-level state named `thingCardImagePlacement`.
- Store it in SQLite as `thing_card_image_placement`.
- Supported values:
  - `0 = DEFAULT`
  - `1 = TOP`
  - `2 = BOTTOM`
  - `3 = LEFT`
  - `4 = RIGHT`
- Normal-span cards expose `DEFAULT`, `TOP`, and `BOTTOM`.
- Full-span cards expose `DEFAULT`, `TOP`, `BOTTOM`, `LEFT`, and `RIGHT`.
- `DEFAULT` renders like `TOP` in the first implementation.
- `LEFT` and `RIGHT` are physical directions, not layout-direction start/end.

## Out Of Scope

- Choosing which attachment is used by the thing card.
- Crop ratio, crop focus, crop region, or focal-point editing.
- Multi-image gallery or carousel layout.
- User-adjustable side-image/content ratio.
- Pre-setting image placement before an image exists.

## Data Model

- Add `thingCardImagePlacement` to `Thing`.
- Add `thing_card_image_placement INTEGER NOT NULL DEFAULT 0` to both database
  creation and upgrade paths.
- Existing rows migrate to `DEFAULT`, except version 11 databases copy from
  the legacy `home_card_image_placement` column during the version 12 rename.
- Keep placement after all image attachments are removed; the value remains
  dormant until another image/video attachment is available.
- Include the field in DAO insert/update/copy paths, Parcelable handling, and
  equality checks used by Detail update logic.

## Detail Editing

- Show a small placement icon only on the first image/video attachment tile.
- Place the icon immediately left of the existing delete icon.
- Show it only when Detail is editable.
- Hide it while Detail is preparing screenshots.
- Tap opens an App Chrome dialog.
- Dialog options are tap-to-apply and close immediately.
- The selected value participates in the normal Detail edit lifecycle:
  undo/redo action, changed-state tracking, save/update persistence, and
  `updateTime` refresh on save.
- If a normal-span Thing has stored `LEFT` or `RIGHT`, show
  `默认（图片在上）` as selected in the normal-span dialog without rewriting the
  stored value unless the user taps an available option.

## Chinese UI Text

Dialog labels:

- `默认（图片在上）`
- `图片在上`
- `图片在下`
- `图片在左`
- `图片在右`

Feedback messages:

- `已将图片位置设为记事卡片默认位置`
- `已将图片放置于记事卡片上方`
- `已将图片放置于记事卡片下方`
- `已将图片放置于记事卡片左侧`
- `已将图片放置于记事卡片右侧`

Prefer Snackbar; fall back to Toast if no Snackbar host is available.

## Thing Card Rendering

- Hidden-private thing cards ignore image placement and keep the existing lock
  presentation.
- Top/bottom placement keeps the existing image sizing strategy for the current
  span mode.
- `BOTTOM` places the image after title, content/checklist, audio,
  reminder/goal, and habit.
- `BOTTOM` hides the normal bottom spacer so the image reaches the card bottom.
- Full-span `LEFT`/`RIGHT` uses a side-image layout:
  - image column around 42% of card content width;
  - content column around 58%;
  - ratio, min height, and dimension constraints live in resources;
  - image touches the card outer side, top, and bottom edges;
  - content column keeps normal content padding;
  - card height is driven by the larger of content height and side-image minimum
    height, not by the source image aspect ratio;
  - image uses `centerCrop` and fills the side panel height.
- Video thumbnails use the same placement, sizing, and crop rules as images.

## Implementation Notes

- Restructure the thing-card layout around an explicit image container and
  content container.
- Vertical placement can reorder image/content containers.
- Full-span side placement can use a horizontal parent with fixed
  image/content ratio.
- Keep existing card width ownership rules: hidden-private rendering and
  embedded single-card surfaces must not leak one surface's width into unrelated
  image sizing.

## Verification

- Build `:app:assembleDebug`.
- Verify first-install schema and migration path include
  `thing_card_image_placement`.
- Verify Detail create/update retains placement through save, undo, redo, and
  result return.
- Verify normal-span image top/default/bottom rendering.
- Verify full-span top/default/bottom/left/right rendering.
- Verify hidden-private thing card ignores image placement.
- Verify deleting, adding, and reordering image attachments updates the visible
  placement icon correctly.
- Verify screenshot mode hides the placement icon.
