# Detail Attachment Media Appearance Execution

## Implementation Checklist

- [x] Add `DetailAttachmentMediaAppearance` model with JSON parse/serialize,
      defaults, normalization, source retention/removal, and presentation helper
      methods.
- [x] Add crop/presentation value objects or reuse shared media-crop value
      objects where doing so does not couple the model to Thing Card Appearance
      persistence.
- [x] Extract the cover-fit crop matrix math currently used by
      `BaseThingsAdapter` into a shared helper, or add an equivalent focused
      helper for Detail attachment thumbnails.
- [x] Add `detail_attachment_media_appearance` to the fresh database schema.
- [x] Add a database migration that initializes existing rows to default empty
      Detail appearance.
- [x] Add the field to `Thing`, `Parcelable`, cursor construction, DAO insert,
      DAO update, and comparison paths.
- [x] Update import/export paths to carry the new field and remap source keys
      where attachment import remaps files.
- [x] Update `DetailActivity` state to keep an editable
      `DetailAttachmentMediaAppearance` draft alongside the existing attachment
      list.
- [x] Add undo/redo action support for Detail attachment appearance edits.
- [x] Extend attachment deletion undo data so a removed source appearance can be
      restored when deletion is undone.
- [x] Normalize appearance sources after attachment save/delete so only current
      image/video sources remain.
- [x] Update `ImageAttachmentAdapter` to accept Detail appearance state and
      determine each item's active presentation.
- [x] Replace the current one-size attachment sizing path with a span-aware
      path that preserves legacy behavior until customization.
- [x] Add `GridLayoutManager.SpanSizeLookup` so a full-span first item occupies
      all columns and secondary items start on the next row.
- [x] Update RecyclerView height calculation for mixed full-span plus grid rows.
- [x] Apply image/video thumbnail crop using target dimensions, selected
      presentation crop, and source-level `videoFrameMs`.
- [x] Keep existing `ImageViewerActivity` launch behavior unchanged.
- [x] Add the attachment-tile appearance edit button and hide it in read-only
      mode or screenshot mode.
- [x] Build the Detail attachment appearance editor using the Thing Card
      Appearance UI language.
- [x] Add first-item full-span toggle, full-span target-ratio slider from `1:2`
      to `65:24`, crop preview, and video frame selection.
- [x] Keep secondary-item editor scoped to `grid` crop and video frame
      selection only.
- [x] Make editor confirm update Detail edit state and cancel discard the draft.
- [x] Ensure changing appearance marks Detail as changed and updates ordinary
      `updateTime` when saved.
- [x] Update localized strings, using `values-zh-rCN/strings.xml` as the
      Simplified Chinese source of truth where applicable.
- [x] Verify no non-Detail renderer reads `detail_attachment_media_appearance`.

## 2026-06-08 Implementation Notes

- Added the new `DetailAttachmentMediaAppearance` model with per-source `grid`
  and `fullSpan` presentations.
- Added database v14 schema/migration and wired the field through `Thing`,
  `Parcelable`, `ThingDAO`, and Detail no-update comparison.
- Updated `ImageAttachmentAdapter` and `DetailActivity` to preserve legacy
  layout until customization, then render full-span/grid targets with saved
  crop and video frame values.
- Added a Detail attachment appearance editor that reuses the existing crop
  editor interaction model, ratio ticks, video frame controls, confirm/cancel
  semantics, and undo/redo integration.
- Confirmed the app's backup/restore path carries the full SQLite database, so
  the new v14 column is included automatically. `ThingExporter` remains a
  one-way user-readable txt/zip export and does not have an attachment remap
  import pipeline.
- Confirmed no non-Detail renderer reads `detail_attachment_media_appearance`.

## Verification Matrix

- [ ] Existing Thing with one image and no appearance still renders full-width
      with the current legacy 4:3 shape.
- [ ] Existing Thing with multiple images/videos and no appearance still renders
      in the current grid layout.
- [ ] Single media attachment cannot opt out of full-width layout.
- [ ] First media in a multi-media list defaults to ordinary grid span.
- [ ] Enabling full-span on the first media makes it occupy the whole first row.
- [ ] Secondary media starts on the next row after a full-span first item.
- [ ] Secondary media remains 1:1 and supports crop center/user zoom.
- [ ] Full-span ratio supports `1:2`, `1:1`, `4:3`, `16:9`, and `65:24`.
- [ ] Full-span `1:2` is not viewport-height capped.
- [ ] Full-span ultra-wide `65:24` preview matches Detail rendering.
- [ ] Turning full-span off preserves the source's `fullSpan` settings.
- [ ] Re-enabling full-span restores the previous full-span ratio and crop.
- [ ] Moving another attachment into position 0 does not inherit the old first
      attachment's full-span state.
- [ ] Moving the old full-span first attachment away makes it use its `grid`
      presentation while preserving `fullSpan`.
- [ ] Moving that attachment back to position 0 restores its full-span
      presentation.
- [ ] Deleting an attachment removes its source appearance.
- [ ] Undoing deletion restores the attachment and its source appearance.
- [ ] Reordering attachments does not rewrite source entries.
- [ ] Video frame selection affects Detail thumbnail rendering.
- [ ] The same video frame is used for both `grid` and `fullSpan` presentations.
- [ ] `grid` and `fullSpan` keep separate crop settings.
- [ ] ImageViewer still opens original image/video behavior without Detail crop.
- [ ] Export/import preserves Detail appearance for mapped attachments.
- [ ] Import drops source entries that cannot be mapped.
- [ ] A Thing with no image/video attachments normalizes to empty appearance.
- [ ] Screenshot mode hides the appearance edit control.
- [ ] Read-only Detail mode hides the appearance edit control.
- [ ] Whole-tile long-press reordering still works for normal and full-span
      media.
- [ ] Orientation changes reproject crop without rewriting JSON.
- [ ] Tablet and landscape column counts still work with full-span first media.
- [ ] Thing Cards, widgets, notifications, sharing, and exported file contents
      do not change because of Detail attachment appearance.

## Build Verification

- [x] 2026-06-08: `.\gradlew.bat :app:assembleDebug` completed successfully.
- [x] 2026-06-08: `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
      completed successfully and published debug update `202606081117`.
- [x] 2026-06-08: `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
      completed successfully after the width UI and crop-center correction.
- [x] 2026-06-08: `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
      completed successfully and published debug update `202606081231`.
- [x] 2026-06-08: `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
      completed successfully after fixing Detail crop persistence and
      render-request reapplication.
- [x] 2026-06-08: `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
      completed successfully and published debug update `202606081259`.
- [x] 2026-06-08: `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
      completed successfully after fixing `saveAfterOnPause()` auto-save
      persistence for the Detail appearance draft.
- [x] 2026-06-08: `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
      completed successfully and published debug update `202606081353`.
- [x] 2026-06-08: `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
      completed successfully after fixing video preview loading when switching
      between normal and wide width modes.
- [x] 2026-06-08: `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
      completed successfully and published debug update `202606081448`.

## Manual Verification Still Needed

- Detail UI behavior with real image/video attachments still needs device or
  emulator visual testing, especially drag/reorder around a full-span first
  item, video frame selection, and ultra-wide/tall full-span ratios.

## Risk Notes

- RecyclerView height calculation is the most likely layout risk because the
  current helper assumes uniform image item sizing per attachment list state.
- Drag/drop onto or away from a full-span first item must be tested carefully
  because span changes can require a full adapter refresh after item movement.
- Undo/redo support should be designed before editor implementation so source
  appearance restoration for deletion is not bolted on later.
- Crop math should be shared or kept byte-for-byte equivalent with card crop
  behavior to avoid visible mismatch between editor preview and Detail
  thumbnails.
