# Thing Card Appearance Execution Checklist

## Purpose

Use this document as the implementation ledger for Thing Card Appearance v1.
Check items off only when the behavior has been implemented and verified against
the product decisions in `THING_CARD_APPEARANCE_PLAN.md` and the architecture
decision in `docs/adr/0002-unified-thing-card-appearance.md`.

## Scope Lock

Thing Card Appearance v1 implements two related but separately persisted media
features:

- Custom thumbnail crop: the user can choose the image/video media source,
  select a video frame when needed, adjust the crop center, adjust user zoom,
  and, for top/bottom thumbnail placement, freely adjust the thumbnail crop
  shape/aspect ratio.
- Media background: the user can use the selected image/video frame as the
  Thing Card's media background, with a separate background crop, mask strength,
  and optional background target height preference.

Both features live in one Thing Card Appearance editor because the real card
layout, selected media source, span mode, placement, crop, background mode, and
foreground readability all affect the final preview.

## Non-Negotiable Behavior

- [x] Thing Card Appearance applies to supported Thing Card surfaces, not only
      the home list.
- [x] The first supported surfaces are the home list, `DoingActivity`, and
      `NoticeableNotificationActivity`.
- [x] AppWidget image regions and system notification big-picture behavior stay
      unchanged in v1.
- [x] Hidden private cards never expose image/video media or open the editor
      unless private content is already visible or the user has authenticated.
- [x] The editor opens from the thing-list contextual toolbar only when exactly
      one eligible Thing is selected.
- [x] The selected Thing must have image/video attachments and must not be the
      current doing Thing.
- [x] The editor uses draft state with live preview. Confirm persists; cancel
      and back restore the original appearance.
- [x] The old Detail-side full-span action and first-attachment placement button
      are removed after the unified editor exists.
- [x] Crop never creates black or empty borders.
- [x] Crop edits never modify the underlying image or video attachment file.

## Phase 0 - Preflight

- [x] Re-read `CONTEXT.md`, `THING_CARD_APPEARANCE_PLAN.md`, and
      `0002-unified-thing-card-appearance.md` before changing code.
- [x] Inspect current card binding and measurement in `BaseThingsAdapter.kt`,
      especially image height, placement, span mode, load-key, count badge,
      hidden-private, doing-cover, selecting, and moving paths.
- [x] Inspect current Thing persistence paths: model, Parcelable, DAO create,
      update, state restore, no-update comparison, export/import if applicable,
      and database migration helpers.
- [x] Inspect current contextual toolbar mode flow in `ThingsActivity` and
      `ModeManager`, including MOVING to SELECTING after a no-op drag.
- [x] Inspect current image/video attachment resolution and thumbnail loading,
      including `AttachmentHelper.getImageFromVideo()` and Glide video loading.
- [x] Capture current default top/bottom dimensions before changing behavior:
      normal-span thumbnails default to 4:3; full-span top/bottom thumbnails
      default to 16:9.

## Phase 1 - Data Model And Migration

- [x] Add a `ThingCardAppearance` value model with parse, serialize, copy,
      equality, validation, and default construction.
- [x] Add value objects for `ThingCardThumbnailCrop`,
      `ThingCardMediaBackgroundCrop`, and per-source appearance settings.
- [x] Store crop center as normalized coordinates and user zoom as a multiplier
      relative to the minimum cover-fit scale.
- [x] Store `thumbnailCrop.sourceAspectRatio` only for top/bottom thumbnail
      target height.
- [x] Do not store `sourceAspectRatio` in background crop or side placement
      crop.
- [x] Add `sideMediaWidthPercent`, with a continuous valid range from 30% to
      60% and default 42%.
- [x] Add `mediaBackgroundHeightRatio` per media source. `null` means the media
      background does not add height beyond content natural height.
- [x] Add `mediaBackgroundMaskStrength` per media source.
- [x] Add `videoFrameMs` per video media source.
- [x] Add `appearanceUpdateTime` inside the appearance JSON.
- [x] Keep Thing content `updateTime` as content update time.
- [x] If only appearance changes, update only `appearanceUpdateTime`.
- [x] If content and appearance both change in one save operation, update both
      timestamps.
- [x] Add `things.thing_card_appearance` as a JSON text column.
- [x] Migrate existing `thing_card_span_mode` and
      `thing_card_image_placement` values into the JSON model.
- [x] Runtime reads prefer JSON and fall back to old columns only when JSON is
      missing.
- [x] Runtime writes use JSON as the source of truth and stop updating old
      span/image-placement columns as active appearance state.
- [x] Fresh installs create the JSON column as the appearance source.
- [x] Upgraded databases may physically keep old columns.
- [x] Per-source settings are keyed by existing `typePathName`.
- [x] Save optional file size and last-modified data for source validation, but
      do not create a new attachment ID system.
- [x] Intentional saved attachment deletion removes that source's per-source
      appearance settings.
- [x] Temporary file absence, permission failure, or media load failure does
      not clear stored appearance settings.

## Phase 2 - Media Source And Video Frame Pipeline

- [x] Resolve the effective media source from `mediaSourceKey`.
- [x] If `mediaSourceKey` is missing or invalid, fall back to the first
      available image/video attachment.
- [x] Expose a source selector with a "default" source plus explicit media
      attachment choices.
- [x] Do not add a "no media" or "hide all media" source in v1.
- [x] New media sources default to centered `centerCrop`-equivalent behavior:
      `centerX = 0.5`, `centerY = 0.5`, `scale = 1.0`.
- [x] New top/bottom thumbnail source aspect ratio defaults to 4:3 for
      normal-span and 16:9 for full-span until the user customizes it.
- [x] Once a source has a customized thumbnail aspect ratio, preserve it across
      span switches.
- [x] Implement video frame selection in v1.
- [x] Persist selected video frame time as `videoFrameMs`.
- [x] Decode/generate frame images on demand.
- [x] Cache frame images by video identity/path, file modified time, frame
      time, and target size.
- [x] Changing video frame keeps the current crop center and user zoom as the
      initial crop intent for the new frame.

## Phase 3 - Layout Targets And Crop Math

- [x] Before rendering media, determine the Thing Card Media Target rectangle.
- [x] For top/bottom placement, the target width fills the card media width and
      the target height is derived from `thumbnailCrop.sourceAspectRatio`.
- [x] Top/bottom thumbnail target height is guarded against the current
      surface's available content height, not raw physical screen height.
- [x] Initial top/bottom guards are normal-span min 12%, full-span min 18%, and
      max 72% for both span modes.
- [x] Preset ratios are shortcuts only; the user can freely draw another
      top/bottom crop shape.
- [x] For left/right placement, side media remains full-span-only.
- [x] If a normal-span card stores left/right placement, render an allowed
      fallback placement without clearing saved side settings.
- [x] Restore left/right placement and side width when the card returns to
      full-span.
- [x] For left/right placement, side width controls the media/content column
      split. Crop center and zoom affect only image coverage inside the side
      panel.
- [x] Side placement height remains layout-derived in v1: content column
      remeasurement and existing side minimums determine final card height.
- [x] For media background, hide the separate thumbnail region.
- [x] For media background, content natural height is the minimum card height.
- [x] If `mediaBackgroundHeightRatio` is set, final height is
      `max(contentNaturalHeight, cardWidth * mediaBackgroundHeightRatio)`,
      subject to surface-specific maximums.
- [x] Home-list media background height may reach 96% of the list surface's
      available height.
- [x] Single-card surfaces cap media background height against their available
      card area.
- [x] Height percentages use the current surface's available content height:
      visible RecyclerView/list area for the home list, the area above bottom
      controls in Doing, and the dialog/screen available area in Noticeable.
- [x] When media background adds extra height, primary content stays
      top-aligned.
- [x] When media background adds extra height, bottom status rows align to the
      bottom and stack upward.
- [x] Bottom status rows include image/video count, audio count, reminder/goal
      time, and habit status/record information.
- [x] Reminder/goal and habit status sit closer to the bottom edge than
      attachment count rows.
- [x] In media-background mode, image/video count renders as an inline status
      indicator similar to audio count, not as the current thumbnail corner
      badge.
- [x] Crop rendering computes cover-fit first:
      `coverScale = max(targetWidth / sourceWidth, targetHeight / sourceHeight)`.
- [x] Effective scale is `coverScale * userScale`, where `userScale >= 1.0`.
- [x] `scale = 1.0` means "exactly cover this target", not "fit entire source"
      and not "draw at original size".
- [x] Clamp pan/center so the effective image always covers the full target.
- [x] Content updates do not rewrite crop center, user zoom, side width, or
      top/bottom source aspect ratio.
- [x] After content updates, re-render against the new target rectangle and
      clamp only at render time.
- [x] Do not warn the user merely because later content updates make the saved
      crop look less ideal.

## Phase 4 - Card Rendering

- [x] Replace card rendering reads of `thingCardSpanMode` and
      `thingCardImagePlacement` with the appearance model.
- [x] Preserve old behavior through fallback defaults when JSON is missing.
- [x] Render separate top/bottom thumbnail crop with the selected source frame
      and `thumbnailCrop`.
- [x] Render left/right side media with selected source frame and
      `thumbnailCrop` center/zoom, ignoring but preserving `sourceAspectRatio`.
- [x] Render media background with selected source frame and `backgroundCrop`.
- [x] Apply media-background mask before choosing Thing Foreground colors.
- [x] Foreground text/icons adapt to the masked media background when media
      background is enabled.
- [x] Thing Background remains the identity, fallback, loading, error, and
      editor accent source.
- [x] Media load failures fall back to Thing Background and safe foreground
      colors without clearing settings.
- [x] Hidden private cards keep the existing lock presentation.
- [x] Doing overlay covers the whole final card after media, mask, content, and
      status layout are applied.
- [x] Selection and moving visual feedback stays above media appearance.
- [x] In media-background mode, selecting/moving dimming applies to the whole
      final card.
- [x] Existing image/video count behavior remains for separate thumbnail
      regions, including correct placement inside side media panels.
- [x] Binding resets stale recycled layout params for span, placement,
      background mode, target sizes, and count indicator shape.
- [x] Avoid unnecessary Glide reloads when the effective source and target size
      did not change.

## Phase 5 - Editor Entry, Draft State, And Bottom Panel

- [x] Add the contextual toolbar action named "customize card appearance".
- [x] Show the action only when exactly one eligible Thing is selected.
- [x] Preserve the existing long-press behavior: long-press enters MOVING, and
      no-op drag release can enter SELECTING contextual toolbar mode.
- [x] Block the action for the current doing Thing.
- [x] Block the action for hidden private content until private content is
      visible or authenticated.
- [x] Open an in-list bottom panel rather than a Detail-side editor.
- [x] Keep the selected card visible above the panel.
- [x] Temporarily adjust RecyclerView bottom padding so the panel does not hide
      the live preview card.
- [x] Consume bottom-panel blank/non-interactive touches so they do not leak to
      the underlying Thing list.
- [x] Render the selected preview card with the final home-list available-height
      basis while the panel is open.
- [x] Coalesce high-frequency slider preview refreshes to the next frame and
      issue deferred adapter item changes only when RecyclerView is idle, not
      computing layout, and has no pending adapter updates. This preserves
      correct layout remeasurement without issuing per-progress updates during
      layout or fling.
- [x] Media-background bottom-status posted layout work is guarded by the
      current bind token and computes shrink/grow height from current target
      minimum height plus natural content height, not the previous laid-out card
      height.
- [x] Media-background card height is driven by the visible background
      `ImageView` height. The background image and mask receive the current
      target height before layout, so the root card measures against the real
      background layer rather than a hidden target child or content minimum.
- [x] Maintain a draft appearance object separate from persisted Thing state.
- [x] Update only the selected preview card while editing.
- [x] Confirm persists the draft through an appearance-only update path.
- [x] Cancel/back discards the draft and restores the original card rendering.
- [x] Bottom panel includes media source selector labeled "Cover image", using
      "Auto select" for the default/unfixed source state, with a trailing
      dropdown affordance and an App Chrome popup-picker with visible elevation
      and a small separation from the panel.
- [x] Bottom panel includes span mode control labeled "Card width", with Normal
      and Wide options.
- [x] Bottom panel includes a media position row labeled "Image/video position"
      with Top, Bottom, Left, Right, and Background options.
- [x] Bottom panel includes continuous side media width control for left/right
      placement.
- [x] Bottom panel omits inline crop center and zoom sliders; crop center and
      zoom for the current visible media mode are edited through the precise
      crop editor.
- [x] Bottom panel includes a free top/bottom thumbnail shape control.
- [x] Bottom panel includes compact one-row card height control and same-row
      Reset action when media background is enabled. Reset clears only the saved
      per-source media-background height ratio.
- [x] Bottom panel cover-image ratio editing uses one slider whose endpoints
      match the current effective min/max ratio from card width and height
      guards, with labeled tick marks and snap points for the preset 1:2, 9:16,
      3:4, 1:1, 4:3, 16:9, and 2:1 ratios when those ratios are reachable. The
      tick labels are drawn inside the slider region on alternating rows, and
      dragging near a reachable tick snaps immediately.
- [x] Bottom panel includes a compact pill-shaped "Crop cover image" action in
      the bottom action row to open the precise crop editor.
- [x] Bottom panel includes video frame selector entry for video media sources.
- [x] Bottom panel includes compact one-row mask strength slider when media
      background is enabled.
- [x] All clickable bottom-panel controls and generated precise-crop dialog
      controls have ripple feedback.
- [x] Bottom panel segmented selections use the selected Thing's full Thing
      Background as a pill background with adaptive foreground text. Command
      buttons can use the Thing Background as text accent; single-tint platform
      controls use the representative color, and cancel actions follow the
      app-chrome dialog cancel color.
- [x] Segmented pill options have small horizontal gaps, label-plus-options
      groups have enough vertical separation, compact indicator labels do not
      push following controls too far away, and the bottom action row follows
      the app's compact dialog action spacing.
- [x] Bottom panel controls use App Chrome foreground/ripple colors and
      representative Thing colors so the panel remains dark-mode safe; ripple
      itself follows the App Chrome light/dark surface rather than the selected
      Thing color.
- [x] Height controls enforce min/max while dragging so live preview and saved
      rendering match.

## Phase 6 - Precise Crop Editor And Frame Selector

- [x] Crop editor shows the selected image or selected/generated video frame.
- [x] Crop editor has an explicit title and sizes its preview view from the
      selected image/video frame aspect ratio, with a screen-height cap.
- [x] Image crop-editor decoding applies EXIF orientation before presenting the
      preview bitmap, matching the final Glide-rendered card orientation.
- [x] Media-background crop-editor target ratio is computed from the current
      draft's final background target height and content natural height instead
      of trusting a possibly stale `CardView.height`.
- [x] Crop editor edits only the currently visible media mode.
- [x] If media background is enabled, crop action edits `backgroundCrop`.
- [x] If media background is disabled, crop action edits `thumbnailCrop`.
- [x] Top/bottom thumbnail crop editor supports resizing the crop frame.
- [x] Top/bottom thumbnail crop editor supports free aspect ratio and preset
      ratio tick snapping on the same slider design as the bottom panel.
- [x] Left/right and media-background crop editor keeps the target frame fixed
      and lets the user pan/zoom media inside it.
- [x] Crop editor does not need to display a numeric zoom multiplier.
- [x] Crop editor clamps pan and zoom so no black/empty border appears during
      editing.
- [x] Crop editor confirm returns changes to the bottom-panel draft.
- [x] Crop editor cancel returns without changing the bottom-panel draft crop.
- [x] Video frame selector appears before crop editing for video sources.
- [x] Video frame selector uses a timeline slider from 0 to video duration.
- [x] Video frame selector shows the current decoded frame preview.
- [x] Video frame selector supports small previous/next step buttons.
- [x] Do not implement a filmstrip timeline in v1.

## Phase 7 - Cleanup And Compatibility

- [x] Remove the old Detail overflow full-span action once the new editor is
      available.
- [x] Remove the old first-attachment image-placement button once the new editor
      is available.
- [x] Remove or redirect old localized labels/messages that only describe the
      removed Detail-side shortcuts.
- [x] Ensure backups/restores using database files still upgrade through the
      normal SQLite migration path.
- [x] Ensure missing JSON, malformed JSON, and unknown future JSON versions fall
      back safely without crashing card rendering.
- [x] Ensure appearance JSON serialization is stable enough for equality and
      no-update comparisons.
- [x] Ensure appearance-only saves do not trigger content-update semantics.
- [x] Ensure old-column fallback remains read-only compatibility behavior.

## Phase 8 - Localization And Resources

- [x] Add Simplified Chinese strings first in `values-zh-rCN/strings.xml`.
- [x] Add corresponding strings for all supported locales.
- [x] Keep user-facing naming broad: "card appearance" rather than only
      "media" because the entry also controls span, placement, and full-span.
- [x] Add resource tokens for top/bottom height guard percentages.
- [x] Add resource tokens for background height maximums where practical.
- [x] Add resource tokens for side width default/min/max.
- [x] Add resource tokens for mask strength defaults.
- [x] Keep tuning values easy to adjust after visual testing.

## Verification Matrix

These items remain unchecked until explicit database, device, or UI smoke
testing is performed. The implementation checklist above is complete for the
non-smoke-test scope.

- [ ] Database upgrade from a build with old span/image-placement columns creates
      valid `thing_card_appearance` JSON.
- [ ] Fresh install creates Things with default JSON/fallback appearance.
- [ ] JSON read fallback works when JSON is missing.
- [ ] Malformed JSON does not crash card binding.
- [ ] Appearance-only update changes `appearanceUpdateTime` but not content
      `updateTime`.
- [ ] Home-list normal-span top thumbnail uses default 4:3 before customization.
- [ ] Home-list full-span top/bottom thumbnail uses default 16:9 before
      customization.
- [ ] Customized top/bottom ratio persists across span switches.
- [ ] Top/bottom crop can freely choose non-preset ratios.
- [ ] Top/bottom height guards use available surface height and enforce 12%,
      18%, and 72% initial limits as configured.
- [ ] Left/right placement is unavailable in normal-span controls.
- [ ] Normal-span rendering falls back safely when stored placement is
      left/right.
- [ ] Returning to full-span restores stored left/right placement and side
      width.
- [ ] Side media width changes continuously from 30% to 60% and live preview
      remeasures content.
- [ ] Media background works in normal-span cards.
- [ ] Media background works in full-span cards.
- [ ] Media background hides the separate thumbnail region.
- [ ] Card height defaults to content natural height when the media-background
      height ratio is null.
- [ ] Card height control can increase card height up to the surface cap.
- [ ] Card height reset clears the per-source media-background height ratio.
- [ ] Extra background height keeps primary content at top and bottom status at
      bottom.
- [ ] Image/video count appears as inline status in media-background mode.
- [ ] Audio count participates in the same bottom-status family.
- [ ] Reminder/goal/habit rows remain lower priority-order correct near the
      bottom.
- [ ] Uniform mask strength improves readability and is saved per source.
- [ ] Foreground colors adapt to masked media background.
- [ ] Image crop never shows black borders.
- [ ] Video frame crop never shows black borders.
- [ ] Changing Thing title/content/checklist/reminder/habit after crop keeps
      saved crop center and user zoom, then re-renders against the new target.
- [ ] No automatic warning appears after content updates change the visible crop.
- [ ] Missing selected media falls back without clearing settings.
- [ ] Explicit deletion of a saved media attachment removes its per-source
      settings.
- [ ] Hidden private cards do not expose media in normal, selecting, moving, or
      background modes.
- [ ] Current doing Thing does not expose the appearance editor action.
- [ ] Doing overlay covers the final card correctly in media-background mode.
- [ ] Selection and moving feedback apply above media appearance.
- [ ] `DoingActivity` respects Thing Card Appearance with safe single-card caps.
- [ ] `NoticeableNotificationActivity` respects Thing Card Appearance with safe
      dialog/screen caps.
- [ ] AppWidget image behavior remains unchanged.
- [ ] System notification big-picture behavior remains unchanged.

## Pre-Commit Source Audit - 2026-06-03

- [x] Normal-span top/bottom thumbnail ratio editing uses one dynamic slider
      whose endpoints come from the same card width, available home-list height,
      and thumbnail height guards used by `BaseThingsAdapter.getImageHeight()`.
- [x] Normal-span crop center and user zoom are saved in the per-source
      `thumbnailCrop`, then rendered through cover-scale matrix cropping without
      black borders.
- [x] Full-span top/bottom thumbnail ratio and crop use the same dynamic range
      and crop pipeline as normal-span, with full-span-specific height guards.
- [x] Full-span left/right media width changes update the image column and
      content column, then side-image height synchronization ignores stale
      posted work through `tag_thing_card_side_image_bind_token`.
- [x] Full-span left/right crop center and user zoom are saved per source and
      rendered into the fixed side-media target rectangle.
- [x] Media-background Card height preview no longer reads the old laid-out
      `llContent.height`; it uses the current `llContent.minimumHeight` and
      natural content height, with stale bottom-status posts guarded by
      `tag_thing_card_media_background_bind_token`.
- [x] Thumbnail and media-background Glide callbacks now apply the current
      render request tag before writing a crop matrix, so stale image-load posts
      cannot overwrite rapid ratio, side-width, crop, or background-height
      edits.
- [x] Source guards found no old visible-holder-only `rebindVisibleItem`, no new
      RecyclerView `Called attach on a child...` catch, no old ratio preset
      button members, and no old `llContent.height` target-height use in the
      appearance media paths.
- [x] `.\gradlew.bat :app:assembleDebug --console=plain` passed after the audit.
- [x] `git diff --check` passed with only the repository's existing LF/CRLF
      warnings.

## Bug Fix Source Audit - 2026-06-04

- [x] Ratio tick labels are drawn inside the slider region and alternate above
      and below the track, fixing dense-label collisions such as 1:2 / 9:16 and
      16:9 / 2:1.
- [x] The bottom-panel ratio slider and the precise-crop ratio slider both snap
      during drag and still normalize once more on release.
- [x] The precise crop editor uses an EXIF-oriented preview bitmap for image
      sources and computes media-background target ratio from current draft
      geometry, reducing preview/final-card mismatch.
- [x] Media-background height uses `iv_thing_media_background.layoutParams.height`
      as the current card-height target, with the mask height kept in sync.
      The live height slider applies this directly to the currently visible
      selected card holder instead of waiting only for deferred adapter change
      animation.
- [x] Media-background height slider minimum is set dynamically from the current
      selected card's natural content height on the same percentage scale used
      for stored background height ratios. The slider no longer exposes lower
      values that cannot affect rendering, and values at or below that minimum
      clear the saved extra-height ratio.
- [x] Media-background natural-height measurement temporarily removes the
      expanded `ll_thing_text_content` height and bottom-status spacer weight
      written by previous background layout passes, so slider minimums and crop
      dialog target geometry use true natural content height.
- [x] Media-background image loading is scheduled from pre-draw after the root
      card has its final measured width and height for the current bind token.
- [x] Appearance preview `notifyItemChanged` waits for RecyclerView idle state,
      no pending adapter updates, and no layout computation. If an old item
      animator is still running at that safe point, the preview refresh ends it
      before issuing the current item change instead of deferring indefinitely.
- [x] `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
      passed after these bug fixes.
- [x] Source guards found no new catch for `Called attach on a child which is
      not detached`.
- [x] Crop-center-only confirmations now apply the updated crop matrix directly
      to the visible selected card holder instead of waiting only for the
      deferred layout-changing preview refresh path. The direct refresh updates
      the current thumbnail/media-background render-request tag before applying
      the matrix, so an older Glide callback for the same load key cannot
      restore the previous centered crop.
- [x] Final card media Glide requests use `dontTransform()` for both separate
      thumbnails and media backgrounds. Glide must not auto-apply `centerCrop`
      from the target `ImageView.scaleType`, because that pre-crops the drawable
      to the target size and leaves no extra source area for the app-owned crop
      matrix to pan with saved `centerX` / `centerY`.
- [x] Media-background mask and Card height control rows now share the same
      label width and slider column; the separate Card height Reset button and
      its localized string resources were removed because the dynamic minimum
      already represents the reset/no-extra-height state.
- [x] The precise crop editor now draws the whole oriented source bitmap,
      maps the saved crop center/user scale to a visible crop rectangle, and
      applies the dim overlay only outside that selected crop rectangle.
- [x] The precise crop dialog follows existing compact dialog chrome more
      closely: the title uses the current Thing Background accent, content and
      action-row margins match existing dialogs, and cancel/confirm buttons use
      fixed 36dp pill ripple surfaces.
- [x] Media-background image loading now reuses the current drawable when the
      source and video frame are unchanged but the target size changes. The
      existing drawable is immediately re-matrixed for the new height and used
      as the Glide placeholder, reducing flashes of the underlying Thing
      Background while dragging Card height.
- [x] The precise crop editor's zoom behaviour was corrected back to a fixed
      crop frame with the image matrix scaling underneath it. The full preview
      area and the crop frame now both render with rounded corners.
- [x] Media-background mask and Card height sliders were moved closer to their
      labels while preserving aligned slider columns.
- [x] Continuous Card height dragging now uses the slider minimum captured when
      the panel was bound, and updates the draft without rebinding the whole
      panel on every progress change. This prevents near-left-end drags from
      remeasuring a changed live holder and jumping the card taller.
- [x] The direct media-background height preview path no longer performs a full
      content-expansion reset on every slider tick. It measures natural content
      height transiently, then applies the final text-content height directly.
- [x] The media-background image-count text moved out of the text-content flow
      into a bottom overlay on the Card root, so it no longer participates in
      spacer-driven bottom-status re-layout during Card height dragging.
- [x] Media-background mask and Card height labels were changed from a fixed
      104dp width to `wrap_content`, with an 8dp start margin on the following
      SeekBar.
- [x] Media-background image-count overlay now has an explicit 8dp top margin,
      and the text-content bottom spacer reserves 44dp when the overlay is
      visible so other card content does not crowd it.
- [x] Media-background image/video count now uses an icon-plus-text row like the
      audio attachment count. The count text uses the adaptive tertiary
      foreground color, while the icon follows the same black-side/white-side
      foreground family without reusing the exact text ARGB value.
- [x] Media-background image/video count icon now uses card-specific cropped
      black/white PNG resources whose max alpha is raised to the 66% text tier.
      This removes the global 24dp `ic_image_count` transparent canvas padding
      and prevents the icon from looking lighter than the count text.
- [x] SeekBar-style controls now build their progress drawable explicitly:
      inactive track uses App Chrome hint colour, active track uses a solid
      accent for pure backgrounds or a gradient drawable for gradient Thing
      Backgrounds, and the thumb keeps the representative accent colour.
- [x] Media-background direct height preview now computes an
      `effectiveTargetHeight = max(targetMinHeight, naturalContentHeight)` and
      passes that positive value to the background `ImageView` and mask. The
      no-extra-height / left-end slider state no longer routes through `0`,
      which previously became `MATCH_PARENT` inside the overlay-height setter
      and could make preview height jump taller even though saved data was
      correct.
- [x] Entering selection mode now ends any running RecyclerView item animations
      and disables change animations before the full selection-mode rebind. This
      keeps minimum-height media-background cards from reusing an old animated
      card height when the saved height ratio is already correct.
- [x] Media-background rendering no longer feeds `CardView.height` back into the
      background `ImageView` height during pre-draw. The effective height is now
      computed from `max(saved media-background target height, natural content
      height)` for bind, pre-draw loading, and direct crop application, so stale
      parent height at RecyclerView top/bottom boundaries cannot inflate a
      minimum-height card.
- [x] Media-background natural-height measurement now prefers the current
      bind's explicit `llContent.layoutParams.width` over stale laid-out holder
      widths. This keeps the bind-time and pre-draw natural-height calculations
      on the same width when a ViewHolder is reused during MOVING/SELECTING
      transitions.
- [x] The media-background Card height slider's dynamic minimum now uses the
      same explicit content-width rule in
      `ThingsActivity.getThingCardBackgroundNaturalHeight()`, so panel binding
      cannot expose a lower-than-renderable minimum after a stale-width preview
      rebind.
- [x] `toMovingMode()` now uses the same RecyclerView mode-rebind preparation
      as `toSelectingMode()`: end running item animations and disable change
      animations before the mode's full `notifyDataSetChanged()` path.
- [x] The Thing Card Appearance panel title now uses the current Thing
      Background as pure-colour or gradient accent text, and the panel top
      padding has been increased to give the title more vertical breathing
      room.
- [x] Video frame selection has moved from the bottom appearance panel into the
      precise crop dialog. Video sources use "Crop cover video" /
      "裁切封面视频", and the dialog's crop preview displays the selected video
      frame directly. Playback/pause, stop, and frame SeekBar controls below
      the preview update the crop source frame; confirming saves `videoFrameMs`
      in the same source-appearance update as crop center/scale/ratio.
- [x] Video crop preview playback now uses a real `TextureView` + `MediaPlayer`
      surface instead of periodically decoding still frames. The video crop view
      draws the same rounded crop overlay and applies the same cover-scale,
      crop-center, and user-scale matrix math as final card rendering. The
      dialog still decodes one initial frame only to size the preview before the
      playback surface is ready.
- [x] Video crop preview loading now hides the blank `TextureView` behind a
      rounded loading overlay until the first `onSurfaceTextureUpdated` frame is
      rendered. The loading indicator uses the current Thing Background as a
      solid or sweep-gradient progress arc. Playback state is tracked explicitly
      so completion switches the play/pause control back to the play icon.
- [x] Video playback completion now has a ticker-based fallback in addition to
      `MediaPlayer.OnCompletionListener`. When playback reaches the end or the
      media player reports not-playing near the end, the crop view runs the same
      `finishPlayback()` path to clear `playing` and dispatch the play-icon
      state on the main thread.
- [x] The media-source popup's "Auto select" row now displays the same default
      source that it will actually select: `mediaSourceKey = null` resolves to
      the first available image/video source in attachment-string order. The
      auto row no longer reuses the currently explicit draft media source for
      its filename.
- [x] Video cover-frame selection now uses exact-frame semantics in all
      rendering paths. Final card Glide requests set
      `VideoDecoder.FRAME_OPTION = MediaMetadataRetriever.OPTION_CLOSEST`
      instead of relying on Glide's default closest-sync frame option, and the
      crop dialog's opening-time `MediaMetadataRetriever` decode uses
      `OPTION_CLOSEST` too. The video frame controls and `MediaPlayer` crop
      preview also clamp to a small renderable end guard rather than
      `duration - 1ms`, and seek-complete has a bounded fallback so the loading
      overlay cannot remain visible forever when the selected frame is near the
      end of the video.
- [x] Video precise-crop gestures now have visible preview feedback while
      paused and while the first `TextureView` frame is still pending. The
      video crop view lays out the `TextureView` itself as the scaled source
      media rectangle instead of relying on a fixed-size `TextureView`
      transform, so panning and pinch zoom update the visible video geometry
      immediately. During loading or missing first-frame callbacks, the
      already-decoded opening frame is drawn as a fallback preview under the
      same crop overlay, so users can still see zoom level and crop position.

## Out Of Scope For V1

- [x] Do not add AppWidget crop/background support.
- [x] Do not add system notification big-picture crop/background support.
- [x] Do not add a "hide all card media" source.
- [x] Do not create a new attachment ID system.
- [x] Do not modify original image/video attachment files.
- [x] Do not replace Thing Background with media.
- [x] Do not build a video filmstrip timeline.
- [x] Do not add a separate side-media height preference unless a later product
      decision explicitly expands v1 beyond side width control.
