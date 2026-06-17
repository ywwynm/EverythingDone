# Detail Attachment Media Appearance Decisions

## 2026-06-17 - Detail attachment custom crops are baked before ImageView display

Customized Detail attachment thumbnails should use the same offscreen bitmap
crop renderer as Thing Card media instead of expressing the final crop through
`ImageView.imageMatrix`. The customized Glide request still loads the selected
image or video frame, but the listener bakes the requested crop into a
target-sized `Bitmap` and sets that bitmap directly on the attachment
`ImageView`.

The customized attachment load key should include target width and height,
video frame time, and the crop fingerprint so crop changes force a fresh
render even when Glide can reuse the underlying decoded source.

## 2026-06-08 - Appearance settings are keyed by media attachment

Detail Attachment Media Appearance settings are stored per image/video
attachment source, keyed by the existing attachment `typePathName` string.

Crop center and crop user zoom follow the attachment itself. The first-position
full-span choice and media target aspect ratio also belong to that attachment
as its first-position presentation preference: when the attachment is displayed
first, those values apply; when it is displayed later, the attachment uses the
secondary 1:1 target while retaining its first-position values for later.

This avoids applying the previous first slot's crop to a different file after
attachment reordering, and it matches the existing Thing Card Appearance
per-source model without making Detail attachment presentation share Thing Card
Appearance state by default.

## 2026-06-08 - Store Detail attachment appearance separately from card appearance

Detail Attachment Media Appearance should be persisted in its own Thing-level
field, such as `detail_attachment_media_appearance`, instead of being embedded
inside `thing_card_appearance`.

The feature may reuse the same crop and media-target math as Thing Card
Appearance, but the stored state belongs to the Detail attachment list surface.
Changing a Thing Card cover must not unexpectedly change Detail attachment
presentation, and changing Detail attachment presentation must not unexpectedly
change card, widget, or notification rendering.

## 2026-06-08 - Detail attachment appearance follows the Detail edit lifecycle

Detail Attachment Media Appearance changes should participate in
`DetailActivity`'s normal edit lifecycle instead of being saved immediately.

Changing first-position span, media target aspect ratio, crop center, or crop
user zoom should update the in-memory edit state, support undo/redo, and become
durable only through the existing Detail save/update flow. This matches
attachment add, delete, and reorder behavior, and lets accidental crop edits be
rolled back through the Detail editing model.

## 2026-06-08 - Preserve legacy Detail attachment layout until customization

Existing Things with no saved Detail Attachment Media Appearance should keep the
current Detail attachment layout after upgrade. A single image/video attachment
continues to render as the current full-width 4:3 thumbnail, and multiple
image/video attachments continue to render with the current grid sizing rules.

The new appearance model becomes active incrementally: when the user first
customizes an attachment, the app writes explicit first-position presentation
and crop values for that attachment. This avoids an automatic visual migration
for existing Detail attachment lists.

## 2026-06-08 - Single media attachment keeps the current full-span behavior

When a Thing has only one image/video attachment in `DetailActivity`, that
single attachment should keep the current full-width presentation. The new
Detail Attachment Media Appearance work should not introduce a way for a single
media attachment to opt out of that full-span layout.

The single attachment may still gain target aspect-ratio and crop controls, but
its full-span state remains fixed by the one-attachment layout.

When a single media attachment is customized, the app creates that source's
`fullSpan` presentation and may persist `fullSpanEnabled = true`. If another
media attachment is later added, the previously customized first attachment can
continue to render full-span because the full-span preference now belongs to
that source.

## 2026-06-08 - Multi-attachment first media defaults to normal grid span

When a Thing has multiple image/video attachments in `DetailActivity`, the first
media attachment should keep the current grid span by default. It becomes
full-span only after the user explicitly enables the first-position full-span
presentation for that attachment.

This preserves existing multi-attachment density while allowing users to promote
the first attachment into a cover-like visual when that is desired.

## 2026-06-08 - Full-span first media owns the first row

When the first image/video attachment is displayed full-span in
`DetailActivity`, it should occupy the whole first row. Remaining image/video
attachments start on the next row, keep the current device/orientation grid
column count, and render as 1:1 media targets.

This means a phone portrait layout can show one full-width first media item
followed by the existing two-column square grid, while tablet and landscape
layouts continue to use their existing grid column counts for the secondary
attachments.

## 2026-06-08 - First full-span media supports ultra-wide target ratios

When the first image/video attachment is displayed full-span in
`DetailActivity`, its media target aspect ratio control should allow values
from `1:2` through `65:24` (`width / height`). The upper bound intentionally
allows ultra-wide presentations such as Hasselblad XPan-style framing.

The legacy no-appearance first full-span rendering remains the current `4:3`
single-attachment shape until the user customizes Detail Attachment Media
Appearance.

## 2026-06-08 - Store separate fullSpan and grid presentation crops

Each image/video attachment with customized Detail Attachment Media Appearance
should be able to store separate presentation entries for `fullSpan` and
`grid`.

The `fullSpan` presentation applies when the attachment is displayed in the
first Detail media position and uses a full-span target. It can store the target
aspect ratio, crop center, and crop user zoom. The `grid` presentation applies
to ordinary secondary grid cells, keeps a fixed 1:1 target, and stores crop
center plus crop user zoom.

The presentation key should be named `fullSpan`, not `first`, because the saved
state describes the display mode rather than the attachment order itself.

## 2026-06-08 - Seed fullSpan presentation from grid with a 1:1 target

When an attachment first receives a `fullSpan` presentation and it already has a
`grid` crop, seed `fullSpan` from `grid` by copying crop center and crop user
zoom. Initialise `fullSpan.targetAspectRatio` to `1:1`, matching the source
grid presentation's target shape, rather than forcing the legacy `4:3` shape.

If neither presentation has saved crop values, the first customized state uses
centered cover-fit defaults: `centerX = 0.5`, `centerY = 0.5`, `scale = 1.0`,
and `targetAspectRatio = 1:1` for `fullSpan`.

## 2026-06-08 - Edit entry lives on each media attachment tile

The Detail Attachment Media Appearance edit entry should be a small control on
each image/video attachment tile, positioned near the existing delete control.
It is shown only while `DetailActivity` is editable and not preparing a
screenshot; read-only Detail mode and screenshot capture hide the entry.

Opening the entry edits the attachment's Detail presentation. For the first
image/video attachment, the editor exposes full-span mode, full-span target
aspect ratio, and crop controls. For secondary image/video attachments, the
editor exposes only the fixed-1:1 grid crop controls.

## 2026-06-08 - Editor follows the Thing Card Appearance UI language

The Detail Attachment Media Appearance editor should feel close to the existing
home-list Thing Card Appearance customization UI. Reuse the same interaction
language where practical: App Chrome dialog/panel styling, ratio slider and
ticks, precise crop preview, drag/pinch crop behavior, and confirm/cancel
semantics.

The Detail editor should still stay scoped to Detail attachment presentation:
it should not expose card media source, card placement, media background, mask,
or other Thing Card Appearance-only controls.

## 2026-06-08 - Video frame selection is shared across Detail presentations

Video attachments should support Detail thumbnail frame selection inside the
Detail Attachment Media Appearance editor.

The selected `videoFrameMs` belongs to the attachment's Detail appearance source
state and is shared by its `fullSpan` and `grid` presentations. The
presentations keep separate crop and target-ratio values, but they do not choose
separate video frames. If no `videoFrameMs` is saved, Detail rendering continues
to use the current default video thumbnail frame behavior.

## 2026-06-08 - Delete removed media attachment appearance

When an image/video attachment is explicitly removed from a Thing, its
Detail Attachment Media Appearance source entry should be removed as well.

Undoing the attachment deletion should restore the removed source appearance
when that data is available from the edit action. This keeps the persisted JSON
from accumulating stale media-source entries and avoids applying old crop
settings if a later attachment reuses the same path-like source key.

## 2026-06-08 - Reorder keeps appearance with the attachment source

Reordering image/video attachments should not rewrite Detail Attachment Media
Appearance source entries. Each attachment keeps its own appearance settings by
`typePathName`; rendering chooses the applicable presentation from that source
based on the attachment's current position and display mode.

If the current first attachment is full-span and another attachment is moved to
the first position, the moved attachment does not inherit the previous first
attachment's full-span setting, target ratio, or crop. It renders from its own
source appearance: full-span only if that attachment already has full-span
enabled, otherwise with the default multi-attachment grid presentation. The
previous first attachment keeps its `fullSpan` settings while it is no longer
first, but renders with its `grid` presentation until it becomes first again.

## 2026-06-08 - Keep whole-tile long-press reordering

Detail image/video attachment reordering should keep the current whole-tile
long-press behavior, including for a full-span first attachment. Do not add a
separate drag handle in the first implementation.

The larger full-span tile remains draggable as an attachment tile. Existing
long-press friction is sufficient for accidental-drag prevention, and avoiding a
new handle keeps the attachment overlay controls from becoming crowded.

## 2026-06-08 - First grid media can edit grid crop and enable fullSpan

When the first image/video attachment is not displayed full-span, its Detail
Attachment Media Appearance editor should still allow editing the attachment's
`grid` crop. The same editor can expose the full-span toggle; enabling
full-span switches the editor to the attachment's `fullSpan` presentation and
reveals the target aspect-ratio control.

This keeps the first attachment editable as a normal 1:1 grid item until the
user explicitly promotes it to full-span.

## 2026-06-08 - Turning off full-span preserves fullSpan settings

Turning off an attachment's Detail full-span presentation should not delete its
saved `fullSpan` presentation values. The full-span enabled state changes, but
the attachment keeps its full-span target aspect ratio, crop center, and crop
user zoom for later reuse.

Only explicit attachment deletion removes the source appearance entry.

## 2026-06-08 - Store full-span enabled state on the source

The Detail full-span enabled state should be stored on the attachment source
entry, for example `fullSpanEnabled: true/false`, rather than inside the
`fullSpan` presentation.

The `fullSpan` presentation describes how the attachment should be rendered
when full-span is active: target aspect ratio, crop center, and crop user zoom.
The source-level enabled flag controls whether that presentation mode is used
while the attachment is in the first Detail media position.

## 2026-06-08 - Store target aspect ratio on all Detail presentations

The Detail Attachment Media Appearance JSON should store `targetAspectRatio` on
both `grid` and `fullSpan` presentations. The first implementation still renders
`grid` with a fixed 1:1 target and should initialise `grid.targetAspectRatio` to
`1.0`, but keeping the field in the model leaves room for future non-square grid
presentation without reshaping the JSON.

The initial JSON shape should stay close to Thing Card Appearance's
presentation model while remaining Detail-scoped:

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

## 2026-06-08 - Treat file metadata as weak validation

Detail Attachment Media Appearance source `fileSize` and `lastModified` values
are weak validation metadata, not hard invalidation rules.

If the same `typePathName` still points to an existing file but the file size or
modified time differs from the saved metadata, Detail rendering should keep and
apply the saved appearance settings. Explicit attachment deletion removes the
source entry, and missing files remain unavailable through the existing
attachment filtering behavior.

## 2026-06-08 - ImageViewer keeps original attachment behavior

Detail Attachment Media Appearance affects the Detail attachment list
presentation only. Opening an attachment in `ImageViewerActivity` should
continue to show the original image or video without applying Detail crop,
Detail target aspect ratio, full-span state, or selected Detail video frame.

This keeps viewing, playback, sharing, and export semantics tied to the original
attachment file rather than the Detail thumbnail presentation.

## 2026-06-08 - Export and import carry Detail attachment appearance

Detail Attachment Media Appearance is a Thing-owned presentation preference and
should be included when a Thing is exported or imported.

Import should remap source entries when attachment source keys are remapped by
the attachment import pipeline. Source entries that cannot be mapped to imported
or existing image/video attachments should be dropped instead of keeping stale
references.

## 2026-06-08 - Normalize no-media appearance to empty state

When a Thing has no image/video attachments, its
Detail Attachment Media Appearance should normalize to an empty default state
with no source entries.

The database field may store an explicit default JSON such as
`{ "version": 1, "sources": {} }` or an equivalent default representation, but
it should not retain source entries for attachments that are no longer present.

## 2026-06-08 - Detail save updates ordinary Thing update time

Persisting Detail Attachment Media Appearance through `DetailActivity`'s normal
save/update flow should update the Thing's ordinary `updateTime`.

Do not add a separate Detail attachment appearance timestamp in the first
implementation. This feature is edited as part of Detail content/edit state, not
as an appearance-only update path like Thing Card Appearance confirmation.

## 2026-06-08 - Non-Detail surfaces ignore Detail attachment appearance

Thing Card surfaces, AppWidgets, notifications, sharing, export file contents,
and `ImageViewerActivity` rendering should ignore
`detail_attachment_media_appearance` for visual presentation. Those surfaces
continue to use their existing models, especially `thing_card_appearance` for
Thing Card projections.

Detail Attachment Media Appearance only changes thumbnails shown inside
`DetailActivity`'s image/video attachment list.

## 2026-06-08 - Reproject saved crop onto current Detail targets

Detail Attachment Media Appearance should store presentation intent rather than
pixel dimensions. Each presentation stores target aspect ratio, crop center, and
crop user zoom; `DetailActivity` computes the actual target width and height
from the current device, orientation, and grid span.

Orientation changes, tablet/phone layout differences, and grid column count
changes should not rewrite the saved JSON. Rendering should recompute the
cover-fit crop matrix against the current target rectangle.

## 2026-06-08 - Do not cap full-span Detail media height

The first full-span Detail media target should not be capped by a viewport-height
or visible-area maximum. Its rendered height is derived directly from the
current target width and saved `fullSpan.targetAspectRatio`, including tall
ratios such as `1:2`.

This gives explicit user-selected Detail media framing full effect, even when a
tall first attachment occupies a large amount of vertical space.

## 2026-06-08 - Editor preview uses the active target ratio

The Detail Attachment Media Appearance editor preview should use the active
presentation's target aspect ratio, including ultra-wide `fullSpan` ratios such
as `65:24` and tall ratios such as `1:2`.

For video attachments, frame selection and crop preview should show the same
target frame that the Detail thumbnail will render. Full video playback remains
the responsibility of `ImageViewerActivity` and is not affected by Detail
thumbnail framing.

## 2026-06-08 - Animated images do not get a separate first implementation path

Animated image formats such as GIF should follow the existing image thumbnail
loading path in the first implementation. Detail Attachment Media Appearance can
store and apply crop/target values for them as image attachments, but the
feature should not add a separate animated crop-preview or playback model.

## 2026-06-08 - Do not add a Reset action in the first editor

The first Detail Attachment Media Appearance editor should not add a Reset
action. This keeps it aligned with the current Thing Card Appearance editor,
which also does not expose a reset command for the customized appearance state.
