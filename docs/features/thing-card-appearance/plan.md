# Thing Card Appearance Plan

> Superseded geometry note: side media width, thumbnail source aspect ratio,
> and media-background height ratio were the original v1 geometry controls.
> New implementation work should use
> `docs/features/thing-card-media-target-geometry/plan.md` and
> `docs/adr/0003-thing-card-media-target-presentation-geometry.md` as the
> canonical model for media target aspect ratio and per-presentation crop.

## Goal

Build a unified Thing Card Appearance editor for card presentation settings,
including span mode, media placement, media source, thumbnail crop, media
background, media-background crop, side media width, mask strength, and video
frame selection.

Execution checklist: `execution.md`.

## Confirmed Product Decisions

- Thing Card Appearance is a shared Thing Card presentation model, not a
  home-list-only setting.
- The editor opens from the thing list contextual toolbar when exactly one
  eligible Thing is selected.
- On underway lists, the existing long-press behavior remains: long-press starts
  MOVING for sorting, and if the drag ends without a real move the existing flow
  enters SELECTING contextual toolbar mode.
- Show the contextual "customize card appearance" action only when exactly one
  Thing is selected, the Thing has image/video attachments, and the Thing is not
  the current doing Thing.
- Hidden private Thing Cards must not expose media or open the appearance editor
  unless private content is already visible or the user has authenticated.
- The editor stays inside the thing list as a bottom panel. The real selected
  Thing Card is the live preview target.
- The bottom panel edits fast appearance controls. A larger crop editor handles
  precise free cropping and returns to the bottom panel.
- Live preview uses draft state. Confirm persists the draft; cancel/back restores
  the original appearance.
- Slider-driven live preview refreshes should be frame-coalesced and should
  avoid per-progress adapter notify calls or repeated smooth-scroll requests.
- Thing Card Media Source chooses which image/video attachment provides Thing
  Card Media. The default source is the first available image/video attachment.
- Per-source settings are keyed by the existing `typePathName` attachment item
  string, with optional file size / last-modified validation.
- Do not introduce a new attachment ID system in the first implementation.
- Thumbnail crop and media-background crop are separate settings.
- Left/right side media width is a separate appearance setting and is not
  derived from crop ratio.
- Left/right side media width is freely adjustable within a guarded valid range.
  It is a layout control, so changing it may change content wrapping and card
  height.
- The first side media width range is 30% to 60%, defaulting to 42%.
- Left/right media placement remains full-span-only. Normal-span rendering falls
  back to an allowed placement without clearing saved side settings.
- Media background is allowed in both normal-span and full-span cards; its
  target is the measured card rectangle for the current span.
- In media-background mode, content natural height is the minimum card height.
  The user can increase the background media target height, and final card
  height is `max(contentNaturalHeight, backgroundMediaHeightPreference)`.
- Store media-background height preference as a ratio relative to current card
  width, not as a fixed dp height. Store it per media source.
- `mediaBackgroundHeightRatio = null` means media background does not add extra
  height; final card height follows content natural height.
- When media-background height adds extra vertical space, keep primary content
  top-aligned and align reminder/goal/habit status blocks to the bottom, with
  flexible space between them.
- The bottom status block includes audio attachment count, reminder/goal time
  information, and habit status/record information. Primary content includes
  title, plain text, and checklist.
- Media-background mode still shows image/video attachment count, but as an
  inline status indicator similar to audio attachment count rather than the
  current thumbnail corner badge.
- In the bottom status block, reminder/goal and habit status rows sit closer to
  the bottom edge than image/video and audio attachment count rows.
- The doing overlay keeps current behavior and covers the whole final card above
  media background, mask, content, and status layout.
- Selection and moving-mode feedback applies above media appearance. In
  media-background mode, dimming/feedback should affect the whole final card.
- Crop editing stores a user-selected crop center and zoom/scale against the
  current media target. The effective visible source region is recomputed when
  the target rectangle changes.
- Crop scale is an extra zoom multiplier on top of the minimum cover-fit scale.
  It must never allow black or empty borders.
- The renderer must compute cover-fit from source size and target size before
  applying user zoom. Stored `scale = 1.0` means "exactly cover this target",
  not "draw the source at original size" or "fit the whole source inside".
- Both image and video media support source-region crop without modifying the
  original attachment file.
- Video media supports selecting the video frame that provides the card media.
  Persist the selected time point and generate/cache frame images on demand.
- When media background is enabled, the separate thumbnail region does not
  render. Existing card content layout stays in place above the media background.
- Media background crop is rendered against the real measured card height.
- In media-background mode, content natural height is the minimum card height.
  The media background covers the final measured card rectangle, which may be
  taller when the user saves a background height preference.
- Media background includes a readable mask. Provide an automatic baseline and a
  user-adjustable mask strength per media source.
- The first mask implementation is a uniform single-colour overlay, not a
  gradient mask.
- Thing Foreground colors adapt to the masked media background when media
  background is enabled.
- Media load failures, permission gaps, or temporarily missing files fall back to
  Thing Background without clearing stored appearance settings.
- Explicit saved attachment deletion removes that attachment's per-source
  appearance settings.
- The first implementation affects Thing Card surfaces only: home list,
  DoingActivity, and NoticeableNotificationActivity. AppWidget image regions and
  system notification big pictures keep current behavior.
- Remove old Detail-side card appearance shortcuts once the unified editor
  exists.

## Data Model

- Add `things.thing_card_appearance` as a JSON text column.
- Migrate the existing `thing_card_span_mode` and
  `thing_card_image_placement` values into the JSON model.
- Treat old span/image-placement columns as semantically deprecated. Upgraded
  databases may keep the old columns physically, but runtime writes should use
  `thing_card_appearance`.
- Runtime reads should prefer `thing_card_appearance` and fall back to old
  columns only when JSON is missing.
- Fresh installs should use `thing_card_appearance` as the source of card
  appearance state.
- Store `appearanceUpdateTime` inside the appearance JSON, not in a separate
  SQLite column.
- Thing content `updateTime` remains the content update timestamp. If content
  and appearance both change in one save operation, update both timestamps.

Proposed JSON shape:

```json
{
  "version": 1,
  "spanMode": 0,
  "imagePlacement": 0,
  "sideMediaWidthPercent": 42,
  "appearanceUpdateTime": 0,
  "mediaSourceKey": null,
  "mediaBackgroundEnabled": false,
  "sources": {
    "0/path/to/image.jpg": {
      "fileSize": 123456,
      "lastModified": 1710000000000,
      "mediaBackgroundMaskStrength": 0.45,
      "mediaBackgroundHeightRatio": null,
      "thumbnailCrop": {
        "centerX": 0.5,
        "centerY": 0.5,
        "scale": 1.0,
        "sourceAspectRatio": null
      },
      "backgroundCrop": {
        "centerX": 0.5,
        "centerY": 0.5,
        "scale": 1.0
      },
      "videoFrameMs": null
    }
  }
}
```

## Rendering Rules

- Resolve effective media source from `mediaSourceKey`; if missing or invalid,
  fall back to the first available image/video attachment.
- A media source without saved appearance settings defaults to centered
  `centerCrop`-equivalent behavior: `centerX = 0.5`, `centerY = 0.5`, and
  cover-fit user zoom `scale = 1.0`.
- Default top/bottom `sourceAspectRatio` preserves current behavior: 4:3 for
  normal-span and 16:9 for full-span.
- Span changes use those defaults only before the user customizes the source's
  thumbnail crop. Once customized, preserve the user's `sourceAspectRatio`.
- If the effective media cannot be loaded, render the normal Thing Background.
- In non-background mode, first determine the media target from image placement,
  then render the selected media according to the source's thumbnail crop center
  and zoom/scale.
- For top/bottom thumbnail placement, card width determines thumbnail width and
  the selected source crop area's aspect ratio determines thumbnail height,
  subject to min/max constraints. Preset ratios are shortcuts; the user can
  freely draw another source crop-area ratio.
- Persist that freely chosen ratio as `sourceAspectRatio`; it is not limited to
  the preset ratio values.
- Only top/bottom thumbnail crop uses `sourceAspectRatio`. Background crop and
  left/right side crop use the measured target ratio and store center plus user
  zoom only.
- One `thumbnailCrop` record is shared across top/bottom and left/right
  placements. Left/right ignores `sourceAspectRatio` but preserves it.
- For the same media source, missing thumbnail/background crop can initialize
  from the other mode's center and user zoom. Do not copy `sourceAspectRatio`
  into background crop.
- Top/bottom min/max thumbnail-height constraints should be separate for
  normal-span and full-span cards.
- For left/right thumbnail placement, side panel geometry determines media
  column width and content column width. Crop center and user zoom only affect
  how the media covers the already-sized side panel.
- In media-background mode, hide the separate thumbnail region and render the
  selected media behind the existing content layout.
- Apply media-background crop center and zoom/scale after the card has a
  measured target size.
- When Thing content updates change the media target rectangle, keep crop center
  and zoom/scale, then recompute the effective visible source region for the new
  target. The visible crop aspect ratio may change because the target rectangle
  changed.
- Clamp crop center/pan so the selected media always fully covers the target
  rectangle.
- Content updates must not rewrite saved crop center, user zoom, side media
  width, or top/bottom source crop aspect ratio. Re-render against the new target
  rectangle and clamp only at render time.
- Do not warn the user after content updates merely because the resulting crop
  may look less ideal.
- Apply mask before choosing Thing Foreground colors.
- Keep hidden-private cards on the existing lock presentation.

## Editor UX

- Enter from the contextual toolbar action named "customize card appearance".
- Scroll or keep the selected preview card visible above the bottom panel.
- Temporarily adjust RecyclerView bottom padding so the panel does not hide the
  preview card.
- The bottom panel itself consumes touches on non-interactive/blank areas so
  touches never leak to the underlying Thing list.
- While the panel is open, the selected preview card renders with the same
  available-height basis it will have after the panel closes, so thumbnail
  ratio and card-height clamps preview the persisted result.
- Bottom panel controls:
  - media source selector labeled "Cover image", with "Auto select" for the
    unfixed/default source state, explicit attachment choices, a trailing
    dropdown indicator, and an App Chrome popup-picker with visible elevation
    and a small separation from the panel rather than a platform popup menu;
  - span mode toggle labeled "Card width", with Normal and Wide options;
  - media position selector labeled "Image/video position", with Top, Bottom,
    Left, Right, and Background options in one mutually exclusive row;
  - side media width control for left/right placement;
  - compact one-row mask strength control in media-background mode;
  - compact one-row card height control in media-background mode, with a
    same-row Reset action that clears only the saved per-source height ratio;
  - cover-image ratio slider whose endpoints match the current effective
    min/max thumbnail ratio allowed by card width and height guards, with tick
    marks for the preset 1:2, 9:16, 3:4, 1:1, 4:3, 16:9, and 2:1 ratios when
    those ratios are inside the current effective range; draw those ticks inside
    the slider region, alternate dense labels above and below the track, and
    snap while the user drags near a reachable tick rather than waiting only for
    release;
  - open precise crop editor action labeled "Crop cover image" in the bottom
    action row;
  - no inline crop center or crop zoom sliders; crop center and zoom are edited
    only through the precise crop editor so the bottom panel stays compact;
  - video frame selector for video sources;
  - mask strength slider for the current media source in media-background mode;
  - confirm and cancel actions.
- Bottom panel accent styling follows the selected Thing's full Thing
  Background. Selected segmented options render the Thing Background as a pill
  background and use adaptive black-side/white-side foreground text. Command
  text buttons can still use the full background as text accent where a gradient
  can render. Platform controls that only accept one tint use the representative
  color. Cancel actions keep the app-chrome dialog cancel color.
- Bottom panel indicator labels should stay compact enough that following
  controls do not feel detached. Segmented pill options should have small
  horizontal spacing between pills, and label-plus-options groups should keep
  enough vertical breathing room to read as separate rows.
- The bottom action row should visually follow the app's compact dialog action
  spacing: the left crop button's text aligns with the panel's label column,
  while Cancel/Confirm align to the same right edge rhythm as other
  `DialogFragment` actions.
- Every clickable panel and precise-crop dialog control should expose ripple
  feedback. Ripple itself belongs to the App Chrome surface, so light mode uses
  the black-tinted App Chrome ripple and dark mode uses the white-tinted App
  Chrome ripple.
- Precise crop editor:
  - shows the selected image or generated video frame;
  - supports free crop;
  - supports preset ratio constraints;
  - lets top/bottom thumbnail editing resize the crop frame;
  - keeps the target frame fixed for left/right side media and media-background
    editing, letting the user pan/zoom the media instead;
  - does not need to display a numeric zoom multiplier;
  - confirms back to the bottom panel draft;
  - cancel returns without changing the draft crop.
- Height controls should enforce min/max ranges while dragging so preview and
  persisted rendering match.
- High-frequency slider controls should coalesce preview refreshes and issue a
  deferred adapter item change only when `RecyclerView` is not computing layout,
  has no pending adapter updates, and is idle. This lets layout-changing edits
  such as background card height, thumbnail ratio, and side width remeasure the
  card without mutating the child set during layout or fling.
- Media-background bottom-status layout work should be tied to the current
  media-background bind token. When card height decreases, stale posted work
  from a previous taller bind must not write the old height back, and the next
  expanded content height should be computed from the current target minimum
  height plus natural content height, not from the old laid-out card height.
- Media-background card height should be driven by the visible background
  `ImageView` itself. On each bind or live height-slider change, set the
  background image and mask `layoutParams.height` to the current target height
  before requesting layout, so the root `wrap_content` card measures against the
  real background layer instead of an indirect content minimum or hidden target
  child. After root-card pre-draw, set those overlay heights to the final
  measured root-card height and render the crop matrix against that same final
  size.
- The media-background height slider's minimum value should be the selected
  card's current natural content height expressed as the same available-height
  percentage scale. Users should not be able to drag through a lower range that
  cannot change the rendered card because content layout already requires more
  height. Dragging to that minimum, or pressing Reset, clears the saved
  per-source height ratio.
- Natural content-height measurement must ignore media-background expansion
  state previously written for bottom-status alignment. Before measuring
  `ll_thing_text_content` for slider minimums or crop target geometry,
  temporarily restore its layout height to `wrap_content` and disable the
  artificial bottom-status spacer, then restore the live layout params.
- The precise crop editor should have an explicit title. Its preview view should
  size itself primarily from the selected image/video frame aspect ratio, with a
  screen-height cap, while the crop frame inside the view still represents the
  actual target crop ratio.
- The precise crop editor's preview bitmap and target rectangle must match the
  final card renderer. Image decoding should apply EXIF orientation before
  presenting the crop UI, and media-background crop target ratio should be
  derived from the current draft's final card target height rather than a stale
  already-laid-out `CardView.height`.
- Video frame selector:
  - shows a timeline slider from 0 to video duration;
  - shows the current decoded frame preview;
  - supports small previous/next time step buttons;
  - does not use a filmstrip timeline in the first implementation.
- For video sources, select the video frame before crop editing. Changing the
  frame keeps the existing crop center and user zoom as the initial crop intent
  for the new frame.
- The crop action edits only the currently visible media mode: background crop
  while media background is enabled, thumbnail crop while media background is
  disabled.
- The precise crop editor should show the whole oriented source bitmap inside
  the editor. The selected crop area remains unmasked; all non-selected editor
  regions are covered by a dim overlay.
- The precise crop editor's crop frame is fixed for the current target aspect
  ratio. Drag and pinch gestures move and scale the image under that fixed crop
  frame, matching the final card renderer's cover-scale crop matrix.
- Media-background mask and Card height controls should use aligned slider
  columns. Card height has no separate Reset button; dragging to the dynamic
  minimum is the reset/no-extra-height state.
- Media-background mask and Card height control labels should be measured by
  content (`wrap_content`) instead of a fixed dp width; the sliders sit after a
  small start margin.
- Media-background height changes must not blank the current background image
  when only the target size changes for the same source and video frame. Reuse
  the current drawable as the immediate preview/placeholder and update its crop
  matrix against the new target size before any async reload completes.
- Media-background height preview must never pass `0` to the background
  `ImageView` height owner. A `null` stored height ratio means natural content
  height, so the preview target is `max(saved-extra-height, natural-height)`.
- Entering selection mode must end any running RecyclerView item animations and
  disable change animations before the full selection-mode rebind. The saved
  media-background height can be correct while a live selection transition still
  reuses an old measured card height; the renderer must not let that animation
  state inflate the minimum-height card.
- Media-background rendering must not use `CardView.height` as the source of
  truth for the background `ImageView` height. The parent card height can be
  stale during RecyclerView boundary relayout, pre-layout, or selection-mode
  transitions. The background height source of truth is
  `max(saved media-background target height, natural content height)`, and
  pre-draw only supplies the final card width for media loading/crop.
- Media-background natural-height measurement must use the explicit content
  width written during the current bind (`llContent.layoutParams.width`) before
  falling back to already-laid-out view widths. Reused holders can still carry a
  stale `ll_thing_text_content.width` / `CardView.width`; using those first can
  make bind-time and pre-draw measurements disagree and inflate minimum-height
  cards during moving/selection transitions.
- The media-background Card height slider's dynamic minimum uses the same width
  rule. It should measure natural content height from the current holder's
  explicit content width before any laid-out text/card width, so the UI minimum
  cannot be temporarily lower than the renderer's effective minimum after a
  recent preview rebind.
- Media-background image-count text is bottom overlay chrome and needs reserved
  content bottom space plus a top margin so it does not crowd title/content,
  audio, reminder, or habit status rows.
- Media-background image-count chrome should match the existing audio-count
  pattern: icon on the left and count text on the right. The icon should adapt
  to the same black-side/white-side foreground family as the text, but does not
  need to use the exact text ARGB value because the image-count PNG carries its
  own alpha like the audio-count assets.
- Media-background image-count icon uses card-specific cropped black/white PNG
  resources. The global `ic_image_count` asset has a lower baked-in alpha and
  transparent 24dp canvas padding, so direct tinting or matrix-cropping it in the
  card can leave the icon visually lighter or misaligned.
- SeekBar-style controls should render an explicit inactive track instead of
  relying on platform/theme defaults. The inactive track uses App Chrome hint
  colour so it stays visible in light and dark themes. The active track can
  render the selected Thing Background: solid colour for pure backgrounds and a
  `GradientDrawable` for gradient backgrounds; the thumb stays on the
  representative colour for a stable touch target.

## Implementation Todo

- [ ] Add a `ThingCardAppearance` model with JSON parse/serialize, defaults,
      equality, and old-column fallback construction.
- [ ] Add crop value objects for thumbnail crop and media-background crop.
- [ ] Represent crop as center plus zoom/scale, with top/bottom thumbnail crop
      also carrying the source crop area's aspect ratio for target height.
- [ ] Add normal-span and full-span min/max guards for top/bottom thumbnail
      height. The permissive 96% home-list maximum is for media-background
      height only, not top/bottom separate thumbnails. Top/bottom separate
      thumbnails cap at 72% of the current surface's available height for both
      normal-span and full-span, and their minimum heights should use the same
      available-height basis.
      Initial tuning values: normal-span min 12%, full-span min 18%, max 72% for
      both. These values are configurable and can be adjusted later.
- [ ] Add continuous media-background height control with content-height minimum
      and surface-specific maximum guards. Home-list cards may reach 96% of the
      list surface's available height; single-card surfaces must cap against
      available space.
- [ ] Add side media width to the appearance model and left/right rendering.
- [ ] Add per-source appearance records keyed by `typePathName`.
- [ ] Add database column `thing_card_appearance` and migration from old
      `thing_card_span_mode` / `thing_card_image_placement`.
- [ ] Update `Thing`, Parcelable, DAO create/update/state-restore paths, and
      no-update comparisons to use `ThingCardAppearance`.
- [ ] Stop writing old span/image-placement columns as the runtime source of
      truth.
- [ ] Add a DAO/update path for appearance-only updates that does not change
      content `updateTime`.
- [ ] Remove deleted media sources from appearance JSON when attachment changes
      are saved.
- [ ] Replace current card rendering reads of `thingCardSpanMode` and
      `thingCardImagePlacement` with the appearance model.
- [ ] Add effective media-source resolution and fallback.
- [ ] Implement cropped image/video-frame rendering for the separate thumbnail
      region.
- [ ] Implement media-background rendering, measured-height crop application,
      mask overlay, and foreground color adaptation.
- [ ] Implement video frame extraction by persisted time point.
- [ ] Add frame-image cache generation and invalidation keyed by video identity,
      file modified time, frame time, and target size.
- [ ] Add the list contextual toolbar appearance action with eligibility checks.
- [ ] Add the in-list bottom appearance panel with draft state and live preview.
- [ ] Add precise crop editor with free crop and preset aspect ratios.
- [ ] Add video frame selector UI for video media sources.
- [ ] Remove Detail-side full-span and image-placement shortcut entries.
- [ ] Update localized strings, preferring `values-zh-rCN/strings.xml` as the
      Simplified Chinese source of truth.
- [ ] Verify home-list normal/full-span cards across thumbnail and background
      modes.
- [ ] Verify DoingActivity and NoticeableNotificationActivity use the same
      appearance model and safe fallbacks.
- [ ] Verify hidden private cards do not expose media.
- [ ] Verify missing files, permission gaps, attachment deletion, and source
      fallback behavior.
- [ ] Verify AppWidget and system notification image behavior remains unchanged.

## 2026-06-04 Refinements

- The panel title is accent-owned text: it renders the current Thing Background
  as a pure colour or gradient, and the panel keeps extra top padding so the
  title has adequate breathing room.
- Video cover-frame selection is part of the precise crop dialog, not a
  separate bottom-panel block. For video sources, the crop action and dialog
  title use the video-specific label, the crop preview shows the selected video
  frame, and playback/pause, stop, and frame SeekBar controls below the preview
  update that same crop source. Confirming the dialog saves `videoFrameMs`
  together with crop center/scale/ratio; cancelling leaves the draft unchanged.
- Video crop preview playback uses a real `TextureView` + `MediaPlayer`
  surface. Still-frame extraction is only an initial sizing fallback and the
  final selected `videoFrameMs` is driven by the actual playback/seek position.

## Out Of Scope

- AppWidget image crop/background support.
- System notification big-picture crop/background support.
- A no-media / hide-all-card-media source option.
- A new attachment ID system.
- Modifying original image/video attachment files.
- Replacing Thing Background with media.
