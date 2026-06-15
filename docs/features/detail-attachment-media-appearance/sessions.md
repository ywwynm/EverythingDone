# Detail Attachment Media Appearance Sessions

## 2026-06-08 - Product and architecture grilling

- Created the Detail Attachment Media Appearance feature documentation area.
- Clarified that Detail attachment media presentation is separate from Thing
  Card Appearance persistence while reusing the same crop and target-ratio
  concepts.
- Recorded decisions for per-attachment source keys, separate `grid` and
  `fullSpan` presentations, source-level `fullSpanEnabled`, video frame
  selection, legacy layout preservation, reorder behavior, deletion cleanup,
  import/export, and non-Detail surface isolation.
- Added ADR 0004 to document the storage boundary and per-source presentation
  model.
- Added implementation and verification plans for the future build.

## 2026-06-08 - Initial implementation

- Added the `detail_attachment_media_appearance` Thing-owned JSON model and
  database v14 column.
- Wired the new field through `Thing`, parceling, cursor loading, DAO create
  and update paths, Detail no-update comparison, and backup/restore-by-database
  behavior.
- Updated `DetailActivity` and `ImageAttachmentAdapter` so legacy attachment
  layout is preserved until a current media source has saved Detail appearance.
- Added full-span first-media rendering, 1:1 grid crop rendering, crop matrix
  projection, source-level video frame thumbnails, attachment-tile edit entry,
  and undo/redo support.
- Added a Detail attachment appearance dialog that reuses the existing crop
  editor views, ratio ticks, App Chrome styling, video frame controls, and
  confirm/cancel semantics.
- Verified with `.\gradlew.bat :app:assembleDebug`; the final run completed
  successfully.

## 2026-06-08 - Debug update publish

- Updated `memory/debug-update-notes.md` with the Detail attachment media
  appearance implementation, product corrections, verification state, and
  testing focus.
- Published debug update `202606081117` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.
- Remote debug metadata:
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-08 - Width UI and crop-center correction

- Replaced the editor's temporary checkbox-style full-span control with a
  Thing Card Appearance-style pill row labelled `图片显示宽度`, with `正常` and
  `宽` choices.
- Changed the visible ratio-control label to `图片显示比例` and kept the
  technical `fullSpan` term out of visible UI.
- Updated the editor title to use the current Thing background/accent text
  treatment, and kept the dialog surface and text colors on App Chrome
  resources so dark mode remains resource-driven.
- Fixed Detail thumbnail crop rendering by handling Glide's custom-mode
  `onResourceReady` path inside `ImageAttachmentAdapter`, setting the drawable
  and applying the crop matrix before returning `true`.
- Verified the correction with
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606081231` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.

## 2026-06-08 - Crop persistence and render-request correction

- Increased the `图片显示宽度` label column to keep the prompt on one line on
  the user's device.
- Compared Detail attachment crop editing with the existing Thing Card
  Appearance crop pipeline.
- Fixed a persistence bug where Detail attachment appearance edits were written
  into `mThing.detailAttachmentMediaAppearance` before the save/no-update
  comparison, making a crop-only edit look unchanged and preventing the
  database update.
- Kept Detail attachment appearance as an independent draft until
  `createThing(...)` or `updateThing(...)` normalizes and writes the final value
  into `mThing`.
- Updated `ImageAttachmentAdapter` to mirror the card thumbnail render-request
  pattern: store load key, target dimensions, and crop on the `ImageView`, apply
  crop after Glide's drawable update, and reapply crop immediately when only the
  crop changes for an already-loaded source.
- Verified with
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606081259` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.

## 2026-06-08 - Auto-save draft persistence fix

- Confirmed a bug in `DetailActivity.saveAfterOnPause()`: the auto-save path
  wrote title, content, attachment, card span, placement, and color directly
  into `mThing`, then called `ThingManager.create/update`, but did not copy the
  Detail Attachment Media Appearance draft into
  `mThing.detailAttachmentMediaAppearance`.
- Added `applyDetailAttachmentMediaAppearanceDraftToThing(attachment)` so
  auto-save, `createThing(...)`, and `updateThing(...)` share the same
  normalize-and-write-back step before persistence.
- This keeps confirmed crop, full-span, ratio, and video-frame edits from being
  lost when the user backgrounds the app before an explicit save.
- Verified with
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606081353` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.

## 2026-06-08 - Video width-toggle loading fix

- Diagnosed a video editor loading-state bug when switching the Detail
  attachment presentation between `正常` and `宽`.
- The Detail editor reused the same `ThingCardVideoCropEditorView` and called
  `setCropVideo(...)` for the alternate presentation. The view turned loading
  on, but `preparePlayer(...)` returned early because a `MediaPlayer` already
  existed, so no prepared/seek/first-frame callback turned loading off.
- Updated `ThingCardVideoCropEditorView.setCropVideo(...)` to release and
  re-prepare only when the source path changes. For an already-prepared player
  on the same source, it now updates ratio/crop/frame, hides loading, and seeks
  without restarting preparation.
- Verified with
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606081448` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.

## 2026-06-14 - Media-specific dialog wording and spacing polish

- User requested that the Detail attachment appearance dialog no longer use the
  generic `附件外观` title. Image attachments now use `图片外观`; video
  attachments now use `视频外观`.
- The full-span ratio label now follows the media type: images use
  `图片显示比例`, while videos use `视频显示比例`.
- Increased the generated dialog title margin model to match the larger
  dialog convention: title start/end margins now use `24dp`, and the title top
  margin now uses `24dp` instead of a root-level `18dp` top padding.
- Tightened preview spacing: preview top margin changed from `16dp` to `10dp`,
  video-frame controls top margin from `12dp` to `8dp`, ratio controls top
  margin from `12dp` to `8dp`, and the action-row top margin from `20dp` to
  `16dp`.
- Added localized strings for the new image/video-specific title and ratio
  labels.
- Verification: the first
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  run exposed an Android string-resource apostrophe escaping issue in French;
  after escaping `l'image` as `l\'image`, the same assemble passed.
- Published debug update `202606141538` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.

## 2026-06-14 - Dialog spacing and video width label follow-up

- User reported that the Detail attachment appearance dialog preview still sat
  too far from nearby controls, and requested the home Thing Card Appearance
  precise crop dialog use the same spacing values.
- Confirmed the two dialogs are not the same dialog class. `DetailActivity`
  builds the Detail attachment appearance dialog, while `ThingsActivity` builds
  the Thing Card precise crop dialog. They do share
  `ThingCardCropEditorView` and `ThingCardVideoCropEditorView`.
- Tightened both dialogs' preview-adjacent spacing to the same values: preview
  top margin `6dp`, video-frame-controls top margin `6dp`, ratio-controls top
  margin `6dp`, title horizontal/top margins `24dp`, content horizontal margin
  `24dp`, and action-row top margin `16dp`.
- Changed the Detail video width prompt to `视频显示宽度` while keeping image
  attachments on `图片显示宽度`.
- Changed both dialogs' ratio prompt text colour from
  `app_chrome_on_surface_secondary` to `app_chrome_on_surface_hint` so ratio
  prompts match the width prompt weight in light and dark mode.
- Verification: `git diff --check` passed with CRLF warnings only, and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  completed successfully.
- Published debug update `202606150235` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.
- Published debug update `202606141559` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.

## 2026-06-15 - Shared media crop DialogFragment refactor

- User pointed out that the Detail attachment appearance editor should not be
  backed by a raw Activity-created `android.app.Dialog`, because the project
  prefers custom `DialogFragment` implementations under `fragments`.
- Replaced the initial multi-wrapper idea with a single
  `MediaCropAppearanceDialogFragment` shared by Detail attachment appearance
  and the home Thing Card precise crop editor.
- Kept the Detail-specific editor content and save callbacks in
  `DetailActivity`, but moved the dialog lifecycle, tag, width, and cleanup
  boundary to the shared custom DialogFragment path.
- Verification: `git diff --check` passed with CRLF warnings only, and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  completed successfully.
