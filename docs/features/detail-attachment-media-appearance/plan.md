# Detail Attachment Media Appearance Plan

## Goal

Add Detail-screen presentation controls for image and video attachments. The
feature lets users adjust how attachments are shown in `DetailActivity` without
modifying the original attachment files and without changing Thing Card,
AppWidget, notification, sharing, export-file-content, or `ImageViewerActivity`
presentation.

The first image/video attachment can be promoted to a full-span Detail media
target with adjustable target aspect ratio, crop center, crop user zoom, and
video frame when applicable. Ordinary grid attachments keep a 1:1 target in the
first implementation while still allowing crop center, crop user zoom, and video
frame selection.

## Confirmed Decisions

- Detail Attachment Media Appearance is a Detail-specific Thing-owned
  presentation preference.
- Store it separately from `thing_card_appearance`, in a new Thing-level field
  such as `detail_attachment_media_appearance`.
- Reuse the same media target aspect-ratio and crop math concepts as Thing Card
  Appearance, but do not share persisted state.
- Settings are keyed per image/video attachment source using the existing
  `typePathName` string.
- `fileSize` and `lastModified` are weak validation metadata. If the file still
  exists, keep applying saved appearance even when metadata differs.
- Explicit attachment deletion removes that attachment's Detail appearance
  source entry. Undoing deletion should restore the removed source appearance
  when the edit action has it available.
- Reordering attachments does not rewrite appearance entries. The moved
  attachment keeps its own settings.
- If a full-span first attachment is moved away from the first position, it
  renders with its `grid` presentation while keeping its `fullSpan` presentation
  for later.
- If another attachment is moved into the first position, it does not inherit
  the previous first attachment's full-span state, ratio, or crop.
- Detail appearance edits follow `DetailActivity`'s normal edit lifecycle:
  update in-memory edit state, support undo/redo, and persist through the
  existing save/update flow.
- Persisting Detail appearance updates the Thing's ordinary `updateTime`.
- No separate Detail attachment appearance timestamp is needed in the first
  implementation.
- Existing Things with no saved Detail Attachment Media Appearance keep the
  current Detail attachment layout.
- A single image/video attachment keeps the current full-width layout and cannot
  opt out of full-span presentation.
- Multiple image/video attachments default to the current grid layout. The first
  item becomes full-span only when its own source has `fullSpanEnabled = true`.
- A full-span first media item owns the first row. Remaining media attachments
  start on the next row and use the existing device/orientation grid column
  count.
- Grid targets render as 1:1 in the first implementation.
- Store target aspect ratio on both `grid` and `fullSpan` presentations, even
  though `grid.targetAspectRatio` is fixed to `1.0` for now.
- Full-span target aspect ratio supports values from `1:2` through `65:24`
  (`width / height`).
- Full-span Detail media height is not capped by viewport height.
- When first creating `fullSpan` from existing `grid` values, seed crop center
  and crop user zoom from `grid`, and initialise `fullSpan.targetAspectRatio` to
  `1.0`.
- Turning off full-span preserves the saved `fullSpan` presentation values.
- `fullSpanEnabled` lives on the source entry, not inside the `fullSpan`
  presentation.
- Video attachments support selecting `videoFrameMs`. That value is shared
  across `grid` and `fullSpan`; crop and target ratio remain presentation
  specific.
- Animated images such as GIFs follow the existing image thumbnail path in the
  first implementation.
- The editor has no Reset action in the first implementation.
- Export/import should carry Detail Attachment Media Appearance and remap source
  entries when attachment source keys are remapped.
- Source entries that cannot be mapped during import are dropped.
- When a Thing has no image/video attachments, normalize Detail Attachment Media
  Appearance to an empty default state.

## Data Model

Add a new model, likely `DetailAttachmentMediaAppearance`, with JSON
parse/serialize helpers similar to `ThingCardAppearance` but scoped to Detail
attachment rendering.

Initial JSON shape:

```json
{
  "version": 1,
  "sources": {
    "0/path/to/image.jpg": {
      "fileSize": 123456,
      "lastModified": 1710000000000,
      "fullSpanEnabled": false,
      "videoFrameMs": null,
      "presentations": {
        "grid": {
          "targetAspectRatio": 1.0,
          "crop": { "centerX": 0.5, "centerY": 0.5, "scale": 1.0 }
        },
        "fullSpan": {
          "targetAspectRatio": 1.0,
          "crop": { "centerX": 0.5, "centerY": 0.5, "scale": 1.0 }
        }
      }
    }
  }
}
```

Suggested constants:

- `PRESENTATION_GRID = "grid"`
- `PRESENTATION_FULL_SPAN = "fullSpan"`
- `DEFAULT_TARGET_ASPECT_RATIO = 1.0`
- `MIN_FULL_SPAN_TARGET_ASPECT_RATIO = 0.5`
- `MAX_FULL_SPAN_TARGET_ASPECT_RATIO = 65.0 / 24.0`
- `DEFAULT_CROP_CENTER = 0.5`
- `DEFAULT_USER_SCALE = 1.0`

## Rendering Rules

Legacy mode:

- If a Thing has no saved Detail Attachment Media Appearance, keep the current
  `AttachmentHelper.calculateImageSize` and `setImageRecyclerViewHeight`
  behavior.
- This preserves current single-media full-width 4:3 rendering and current
  multi-media grid sizing.

Customized mode:

- Resolve each source by `typePathName`.
- For each item, choose presentation:
  - single media item: full-width layout; use the source's `fullSpan`
    presentation when customized, while preserving the current legacy 4:3 shape
    before customization;
  - first item in a multi-media list: use `fullSpan` only if that source has
    `fullSpanEnabled = true`; otherwise use `grid`;
  - secondary items: use `grid`.
- `grid` renders as a 1:1 target in the first implementation.
- `fullSpan` height is `targetWidth / targetAspectRatio`, with no viewport cap.
- Crop rendering uses cover-fit plus user zoom, then normalized crop center,
  clamped so no empty borders appear.
- Orientation, tablet layout, and grid column count changes reproject saved
  crop intent onto the current target rectangle and must not rewrite JSON.
- Full-span first item spans all grid columns; following items start on the next
  row and keep the normal grid column count.

## Editor UX

- Add a small Detail appearance edit control on each image/video attachment tile
  near the existing delete control.
- Show the control only while `DetailActivity` is editable and not taking a
  screenshot.
- Read-only Detail mode and screenshot capture hide the control.
- Keep current whole-tile long-press reordering, including for full-span media.
  Do not add a drag handle.
- The editor should feel close to the existing Thing Card Appearance UI:
  App Chrome styling, ratio slider/ticks, precise crop preview, drag/pinch crop
  behavior, video frame control, and confirm/cancel semantics.
- For the first media item:
  - show full-span toggle;
  - when full-span is on, edit the `fullSpan` presentation and show the target
    aspect-ratio control;
  - when full-span is off, edit the `grid` crop while still allowing full-span
    to be enabled.
- For secondary media items:
  - edit `grid` crop only;
  - do not show full-span controls.
- For video sources:
  - expose frame selection and crop preview in the same editor;
  - save `videoFrameMs` at source level.
- The editor preview must use the active target ratio, including `1:2` and
  `65:24`.
- Confirm applies the draft to Detail edit state and creates an undoable action.
- Cancel discards the editor draft.

## Persistence and Integrations

- Add `detail_attachment_media_appearance` to the `things` table and fresh
  install schema.
- Add migration for existing databases with a default empty appearance JSON.
- Add the field to `Thing`, `Parcelable`, DAO create/update/read paths, and
  no-update comparison logic.
- Include it in Detail save/update flows so ordinary `updateTime` changes when
  appearance changes.
- Include it in create/update result extras if the existing Detail return path
  requires the whole Thing to be current.
- Update undo/redo action data for Detail appearance edits and attachment
  deletion restoration.
- Update attachment deletion normalization so stale source entries are removed.
- Update import/export to carry and remap Detail appearance source entries.
- Keep Thing Card, AppWidget, notification, share, export file content, and
  `ImageViewerActivity` rendering independent from this field.

## Out Of Scope

- Sharing Detail appearance state with Thing Card Appearance.
- Applying Detail crop or video frame to `ImageViewerActivity`.
- Applying Detail crop to sharing, exported files, widgets, notifications, or
  Thing Cards.
- Non-square secondary grid targets in the first implementation.
- A Reset command in the first editor.
- Dedicated animated-image playback/crop preview behavior.
- Replacing the existing whole-tile long-press reorder gesture with a drag
