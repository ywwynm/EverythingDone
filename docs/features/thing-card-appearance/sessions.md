# Thing Card Appearance Sessions

Migrated from global `memory/sessions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-06-15 - Promote card appearance action in contextual toolbar

- User asked to move the card appearance action earlier in the long-press
  contextual menu, placing it where `Finish selected` currently sits and moving
  `Finish selected` later.
- Updated `menu_contextual_underway.xml` so `act_customize_card_appearance`
  appears before `act_finish_selected` and uses `showAsAction="always"` when
  `ModeManager` makes the action visible. If the action is hidden, `Finish
  selected` still occupies its former visible toolbar slot.
- Added `act_adjust_card_appearance.xml`, a 24dp card-and-sliders vector icon
  using the contextual toolbar's `black_54p` visual tier.
- Kept the existing action id for code compatibility, but changed the user-
  visible label from "Customize card appearance" to "Adjust thing card
  appearance" / `调整记事卡片外观`.
- Verification: `git diff --check` passed with the repository's existing
  LF/CRLF warnings, and
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606150256`.
- Follow-up: user reported that `Finish selected` still showed a toolbar icon
  and made the contextual toolbar too crowded, that the editor panel title still
  said only "Card appearance", and that the new card-appearance icon border
  should be slightly stronger. `act_finish_selected` was moved back to overflow
  with no icon, `thing_card_appearance_panel_title` was aligned with the entry
  label, and the card icon border stroke was increased from `1.6` to `1.8`.
- Follow-up verification: `git diff --check` passed with the repository's
  existing LF/CRLF warnings, and the debug publish task passed and published
  update `202606150309`.

## 2026-06-02 - Thing Card Appearance planning

User asked to design two related Thing Card features: adjustable image/video
thumbnail crop ratio and crop position, and using a chosen image/video thumbnail
as the Thing Card background while keeping existing title/content/reminder/habit
and status content layout readable.

Planning outcomes:
- Used `grill-with-docs` and updated `CONTEXT.md` with the new vocabulary:
  Thing Card Appearance, Thing Card Media, Thing Card Media Source, Thing Card
  Video Frame, Thing Card Media Crop, Thing Card Thumbnail Crop, Thing Card
  Media Background Crop, Thing Card Media Background, Thing Card Media
  Background Mask, and Thing Card Appearance Update Time.
- Confirmed the feature is a shared Thing Card presentation model, not
  home-list-only.
- Confirmed the editor should open from the thing-list contextual toolbar when
  exactly one eligible Thing is selected, using the real selected card as live
  preview and an in-list bottom editing panel.
- Confirmed live preview uses draft state, with confirm persisting and
  cancel/back restoring the original card appearance.
- Confirmed old Detail-side card appearance shortcuts should be removed once the
  unified editor exists.
- Confirmed thumbnail crop and media-background crop are separate per-source
  settings, keyed by existing `typePathName` attachment entries with optional
  file characteristics for validation.
- Confirmed video sources support frame selection by persisting the selected
  time point and generating/caching frame images on demand.
- Confirmed all card presentation state should migrate into a unified
  `thing_card_appearance` JSON column; old span/image-placement columns become
  semantic legacy fields.
- Confirmed `appearanceUpdateTime` belongs inside the appearance JSON and should
  not reuse Thing content `updateTime`.
- Created `docs/plans/THING_CARD_APPEARANCE_PLAN.md` with confirmed decisions,
  proposed JSON shape, rendering rules, editor UX, implementation checklist, and
  out-of-scope boundaries.
- Created `docs/adr/0002-unified-thing-card-appearance.md` recording the unified
  JSON model and list-side live-preview editor decision.

Verification:
- No code changes, build, or device verification were performed in this planning
  session.

## 2026-06-03 - Thing Card Appearance execution checklist

Finalized the Thing Card Appearance planning discussion into an execution
document for item-by-item implementation and conformance checks.

Changes:
- Added `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md` with scope lock,
  non-negotiable behavior, phased implementation checklist, rendering/crop math
  rules, editor tasks, cleanup work, localization/resources, and verification
  matrix.
- Linked the execution checklist from
  `docs/plans/THING_CARD_APPEARANCE_PLAN.md`.
- Corrected the media-background height wording in the plan so content natural
  height is the minimum and a per-source background height ratio may make the
  final card taller.
- Updated `docs/adr/0002-unified-thing-card-appearance.md` to capture the
  final layout-owned target model for top/bottom, side placement, and media
  background.
- Recorded that the v1 checklist is the authoritative execution document when
  older planning notes conflict with later 2026-06-03 height decisions.

Verification:
- Documentation-only update; no code changes, build, or device verification
  were performed.

## 2026-06-03 - Thing Card Appearance data model slice

Started implementation of Thing Card Appearance with the persistence and model
foundation.

Changes:
- Added `ThingCardAppearance.kt` with JSON parse/serialize, default values,
  per-source settings, thumbnail/background crop value objects, side media
  width, media background height ratio, mask strength, video frame time, and
  `appearanceUpdateTime`.
- Updated `Thing` so `thingCardSpanMode` and `thingCardImagePlacement` are
  compatibility facade properties backed by `ThingCardAppearance`.
- Added `thing_card_appearance` to the things table with database version 13.
- Updated fresh-install inserts and v13 migration to create JSON from existing
  `thing_card_span_mode` and `thing_card_image_placement`.
- Updated `ThingDAO` create/update/reinsert paths to write
  `thing_card_appearance` JSON instead of actively writing old span/placement
  columns.
- Added DAO and manager appearance-only update APIs that refresh
  `appearanceUpdateTime` without changing content `updateTime`.
- Added DAO comparison logic so content saves update `appearanceUpdateTime` only
  when presentation data actually changed, ignoring the timestamp itself.
- Updated left/right Thing Card side-image rendering to read
  `ThingCardAppearance.sideMediaWidthPercent` instead of the fixed 42% resource
  value. The default remains 42%, so existing rendering stays unchanged until
  the editor changes the value.
- Added `ThingCardMediaHelper` to resolve the effective image/video media source
  from `ThingCardAppearance.mediaSourceKey`, falling back to the first available
  image/video attachment when the explicit source is missing or unavailable.
- Updated Thing Card image binding to use the resolved Thing Card media source
  instead of directly using the first image/video attachment.
- Added raw media-source key extraction for saved attachment updates, so
  intentional image/video attachment deletion removes corresponding per-source
  appearance settings while temporary file absence does not clear settings.
- Updated top/bottom thumbnail height rendering to read
  `thumbnailCrop.sourceAspectRatio` from the effective media source, falling
  back to the existing 4:3 normal-span and 16:9 full-span defaults when no
  custom ratio exists.
- Added configurable top/bottom thumbnail height guard percentages in
  `values/config.xml`: normal min 12%, full-span min 18%, and max 72%, using
  the current RecyclerView available height when available.
- Checked completed Phase 1 items in
  `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md`.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed after the data
  model and migration work.
- Re-ran the same assemble after timestamp comparison tightening; it passed
  again.
- Re-ran the same assemble after side media width rendering was connected to
  the appearance model; it passed again.
- Re-ran the same assemble after effective media-source resolution and
  attachment-deletion cleanup were added; it passed again.
- Re-ran the same assemble after thumbnail source aspect ratio and height guard
  rendering were added; it passed again.
- No device/database upgrade smoke test or debug publish was performed for this
  incomplete foundational slice.

## 2026-06-03 - Thing Card Appearance editor entry slice

Added the first home-list editor entry and bottom-panel host for Thing Card
Appearance.

Changes:
- Added a hidden-by-default contextual toolbar action named "customize card
  appearance" to underway, finished, and deleted selection menus.
- Updated `ModeManager` so the action appears only for exactly one selected
  Thing with an effective image/video media source, excluding the current doing
  Thing and hidden private content.
- Preserved the existing long-press flow where a no-op drag release can enter
  SELECTING contextual toolbar mode.
- Added a bottom in-list `panel_thing_card_appearance` layout to host the
  upcoming draft editor and live card preview controls.
- Wired `ThingsActivity` to open the panel from the contextual action, bind the
  selected media source and current span/placement/background summary, keep the
  selected card scrollable above the panel, and restore RecyclerView padding on
  cancel, confirm, toolbar close, or back.
- Upgraded the panel from read-only summary to draft editing: media source
  popup selector, normal/full-span control, placement controls hidden in media
  background mode, continuous side media width slider for left/right placement,
  and a media-background toggle.
- Wired draft editing to mutate only the selected in-memory preview Thing;
  confirm persists through `ThingManager.updateThingCardAppearance`, while
  cancel/back/toolbar close restores the original appearance.
- Closed and restored the draft panel when selection changes while the panel is
  open.
- Updated thumbnail rendering to use stored `thumbnailCrop.centerX`,
  `centerY`, and `scale` with cover-fit crop math and pan clamping, so default
  values remain centerCrop-equivalent and customized crops cannot render black
  borders.
- Added a base media-background rendering layer to Thing cards: enabling the
  flag hides the separate thumbnail region, loads the selected media source
  behind the card content, applies the stored `backgroundCrop` with the same
  no-border cover-fit math, applies the per-source mask strength, and honors a
  saved background height ratio up to the home-list 96% surface cap.
- Added basic bottom-panel crop controls for the current visible media mode:
  crop center X/Y, crop zoom, and free top/bottom thumbnail shape/aspect ratio.
  The controls update per-source draft state and keep numeric zoom values out
  of the UI.
- Added media-background controls for mask strength and height. Height stores
  `mediaBackgroundHeightRatio`, with a reset-to-content action and live
  min/max clamping against the home-list 96% available-height cap.
- Added basic video frame selection: video sources show a duration-based frame
  slider, the selected time is stored as per-source `videoFrameMs`, and Thing
  Card image/background Glide loads request that video frame while preserving
  the current crop center and zoom.
- Removed the old Detail overflow full-span action and the old first-attachment
  image-placement button. The legacy IDs remain in `ids.xml` only so retained
  compatibility code compiles without recreating the UI entry.
- Tightened new model parsing/annotations to remove Kotlin warnings from the
  new `ThingCardAppearance` code.
- Added media-background bottom-status layout in Thing cards: an inline
  image/video count row, existing audio/reminder/habit rows, and a spacer that
  pushes status rows to the bottom when the card is taller than its natural
  content.
- Updated media-background foreground coloring so text, icons, checklist, habit
  record indicators, and separators use the masked-media foreground baseline
  instead of the original Thing color.
- Added default and Simplified Chinese strings for the card-appearance entry
  and panel controls.
- Updated `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md` to check the
  completed Phase 5 entry/panel/draft items and thumbnail crop rendering math,
  plus base media-background rendering, without checking precise crop UI, video
  frame selection, foreground-color adaptation, bottom-status reflow, or
  background height controls yet.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed after the
  contextual action and bottom-panel host were added.
- Re-ran the same assemble after draft editor controls and save/cancel preview
  semantics were added; it passed again.
- Re-ran the same assemble after thumbnail crop matrix rendering was added; it
  passed again.
- Re-ran the same assemble after the base media-background card layer was added;
  it passed again.
- Re-ran the same assemble after the bottom-panel crop controls were added; it
  first failed because `ThingsActivity` lacked `kotlin.math.max/min` imports,
  then passed after adding the imports.
- Re-ran the same assemble after media-background mask/height controls were
  added; it passed again.
- Re-ran the same assemble after video frame selection and Glide frame loading
  were added; it passed again.
- Re-ran the same assemble after old Detail-side entry cleanup; it initially
  failed because removing the last resource references also removed legacy
  `R.id` symbols, then passed after preserving those IDs in `ids.xml`.
- Re-ran the same assemble after cleaning new-code Kotlin warnings; it passed.
- Re-ran the same assemble after media-background bottom-status layout and
  foreground-color adaptation were added; it passed.

## 2026-06-03 - Thing Card Appearance surface and preset cleanup

- Confirmed `DoingActivity` and `NoticeableNotificationActivity` both reuse
  `BaseThingsAdapter`, so the shared card appearance renderer covers the first
  supported card surfaces.
- Changed the core card renderers to read span and placement from
  `ThingCardAppearance` directly instead of the legacy facade properties.
- Added a `BaseThingsAdapter` surface-height override and wired it from
  `DoingActivity` and `NoticeableNotificationActivity`, so thumbnail/background
  height caps use each single-card surface's available card area.
- Made media-background cards dim the background image along with foreground
  content in selecting/moving states.
- Added top/bottom thumbnail ratio preset buttons for 1:1, 3:4, 4:3, and 16:9
  in the Thing Card Appearance panel while keeping the free ratio slider.
- Added a video-frame preview image and small previous/next frame step buttons
  to the Thing Card Appearance panel's video source controls. Dragging the
  timeline updates the preview immediately, while stopping the drag persists
  the new draft frame and refreshes the card preview.
- Added Glide cache signatures for card media, media-background frames, and
  the editor's video-frame preview, keyed by path, file size, last modified
  time, selected frame time, and target dimensions.
- Added a lightweight precise crop editor launched from the Thing Card
  Appearance panel. It loads the selected image or selected video frame, keeps
  the current card target frame fixed, supports drag/pinch pan/zoom with
  no-empty-border clamping, and writes confirmed values back to the current
  visible crop mode while cancel leaves the draft unchanged.
- Extended the precise crop editor for top/bottom thumbnail modes with an
  in-editor aspect ratio slider plus 1:1, 3:4, 4:3, and 16:9 shortcuts. Confirm
  now writes the edited frame ratio back to `thumbnailCrop.sourceAspectRatio`
  together with crop center and zoom.
- Added conservative media-load failure fallback: failed thumbnail loads hide
  the separate media region without clearing saved settings, and failed media
  background loads hide the media/mask layers and recolor foreground content
  back to the Thing Background baseline.
- Added `config.xml` resource tokens for side media width default/min/max and
  default media-background mask strength, and changed the card renderer/editor
  control logic to read those tuning values from resources.
- Tightened follow-up behavior after the last compile: media-background fallback
  now also asks the checklist adapter to redraw after foreground recoloring,
  and crop-editor ratio preset taps keep the in-editor ratio SeekBar in sync.
- Updated `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md` to check the surface
  coverage, layout target, side-placement fallback/restore, preset-ratio, and
  rendering cleanup items implemented so far, plus the video-frame cache,
  preview, step-button, media-failure fallback, and precise-crop editor items.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed after these
  surface, renderer, and ratio-preset changes.
- Re-ran the same assemble after adding video-frame preview/step controls; it
  passed.
- Re-ran the same assemble after adding media cache signatures; it passed.
- Re-ran the same assemble after adding the precise crop editor; it passed.
- Re-ran the same assemble after adding top/bottom crop-editor ratio controls
  and media-load failure fallback; it passed.
- Re-ran the same assemble after resource-tokenizing side width and mask
  defaults; it passed.
- Re-ran the same assemble after the checklist redraw and crop-editor ratio
  SeekBar sync fixes; it passed.

## 2026-06-03 - Thing Card Appearance non-smoke completion

Completed the remaining non-smoke-test items for Thing Card Appearance v1.

Changes:
- Removed the remaining old Detail-side appearance entry leftovers:
  `ImageAttachmentAdapter` no longer has the first-attachment placement
  callback path, `DetailActivity` no longer exposes or handles the old
  full-span/image-placement shortcuts, and `ids.xml` no longer preserves the
  removed old UI IDs.
- Removed old localized labels and content descriptions for the removed
  Detail-side full-span and image-placement shortcuts.
- Added the new Thing Card Appearance strings to all supported locale
  `strings.xml` files. Simplified and Traditional Chinese resources use
  direct Chinese strings; non-Chinese locales currently use English fallback
  strings to avoid unreviewed machine translation.
- Updated `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md` so all
  implementation/checklist items outside the runtime smoke-test matrix are
  checked, while the `Verification Matrix` remains explicitly pending for
  database/device/UI smoke testing.
- Replaced `memory/debug-update-notes.md` with the current Thing Card
  Appearance debug update notes before attempting publication.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with LF/CRLF conversion warnings only.

Publish:
- Updated `memory/debug-update-notes.md` with the compact panel and
  ThingBackground-aware accent follow-up.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain`
  passed and published debug update `202606031158` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

Second panel UI follow-up:
- Added a trailing dropdown indicator to the Thing Card Appearance media source
  row and tinted it with App Chrome secondary foreground color for light/dark
  mode.
- Installed ripple feedback on clickable panel TextViews, the media-background
  CheckBox row, and generated precise-crop dialog buttons. Accent controls use
  the Thing representative ripple; cancel actions use App Chrome ripple.
- Changed media-background mask strength and card-height controls to compact
  horizontal rows.
- Renamed the UI-facing media-background height label to "Card height" and
  shortened the reset action to "Reset" across all supported locale string
  resources.
- Updated planning docs and decisions for source affordance, ripple feedback,
  compact rows, card-height naming, and dark-mode-safe colors.

Verification and publish:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with LF/CRLF conversion warnings only.
- Updated `memory/debug-update-notes.md` with this second panel UI follow-up.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain`
  passed and published debug update `202606031220` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

Publish status:
- Attempted to run
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain`,
  but the escalation reviewer rejected it because publishing uploads the
  private project APK and metadata to an external debug update channel.
- After the user explicitly approved the upload risk, re-ran
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain`.
- The publish task passed and published debug update `202606031135` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

Follow-up UI tightening:
- Removed the inline crop center X, crop center Y, and crop zoom sliders from
  the Thing Card Appearance bottom panel. Crop center and zoom are now edited
  through the precise crop editor only, which keeps the bottom panel shorter.
- Removed the now-unused crop slider string resources from all supported
  locale `strings.xml` files.
- Updated the Thing Card Appearance panel accent styling so selected choices,
  confirm, precise crop, reset, video frame step buttons, and precise crop
  editor action buttons use the selected Thing's full `ThingBackground` where
  gradient text can render.
- Updated `SeekBar` and `CheckBox` tints to use the selected Thing
  Background's representative color, matching platform single-tint limits.
- Changed the panel cancel action and precise-crop cancel action to use the
  app-chrome dialog cancel color instead of the Thing accent.
- Updated `docs/plans/THING_CARD_APPEARANCE_PLAN.md` and
  `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md` for the compact crop-entry
  and accent-color rules.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with LF/CRLF conversion warnings only.

## 2026-06-03 — Thing Card Appearance panel touch/ripple/layout fixes

- Fixed the Thing Card Appearance bottom panel so blank/non-interactive touches
  are consumed by the panel instead of leaking to the underlying Things list.
- Reworked the panel's media-background control into the same media-position
  row as Top/Bottom/Left/Right, with the user-facing label "Image/video
  position" and the short "Background" option.
- Removed the source summary line and old media-background checkbox from the
  panel.
- Shortened the precise crop action to "Crop" / "裁切" and kept it as a compact
  pill-shaped control.
- Changed panel and crop-dialog ripple feedback to App Chrome ripple colors so
  light mode uses black-tinted feedback and dark mode uses white-tinted
  feedback, while selected text/accent styling still follows the Thing
  Background.
- Aligned live-preview height constraints with the final home-list available
  height while the panel is open, fixing normal-span thumbnail ratio preview vs
  final rendering mismatch.
- Fixed full-span media-background card height by preventing sparse full-span
  min-height rules from overriding explicit media-background height.
- Updated `docs/plans/THING_CARD_APPEARANCE_PLAN.md`,
  `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md`, memory decisions, and debug
  update notes.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain` and
  `git diff --check` (only existing LF/CRLF warnings), then published debug
  update `202606031309`.

## 2026-06-03 - Thing Card Appearance panel spacing polish

- Added platform elevation to the shared `PopupPicker` surface and moved the
  Thing Card Appearance cover-image picker popup slightly above the source row
  so it no longer visually merges with the bottom panel.
- Reduced the fixed indicator-label width for the Cover image and Card width
  rows, added small horizontal margins between segmented pill options, and
  increased vertical spacing between label-plus-option groups.
- Renamed the bottom-left crop action from "Crop" / "裁切" to "Crop cover
  image" / "裁切封面图片", and adjusted its start offset so the button text
  aligns with the panel label column.
- Adjusted the bottom action row's right-side spacing to match the compact
  `DialogFragment` action rhythm more closely.
- Updated `docs/plans/THING_CARD_APPEARANCE_PLAN.md`,
  `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md`, and memory decisions with
  the popup elevation, segmented spacing, crop wording, and action-row spacing
  rules.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- No device visual smoke test was run for this polish pass.
- A debug publish was attempted after updating `memory/debug-update-notes.md`,
  but the escalation reviewer rejected the external upload because this turn
  lacked current explicit user authorization after the agent had said it would
  not publish unless asked.

## 2026-06-03 - Thing Card Appearance slider crash fix

- Diagnosed a reported crash from full-span media-background cards while
  dragging the Card height slider:
  `IllegalArgumentException: Called attach on a child which is not detached`
  inside `RecyclerView` / `StaggeredGridLayoutManager` layout during
  `ViewFlinger.run()`.
- Identified the risky path in `ThingsActivity.updateThingCardAppearanceDraft()`:
  every slider progress directly called `notifyItemChanged(selectedPosition)`
  and then posted panel-padding refresh work that could repeatedly call
  `smoothScrollToPosition(selectedPosition)`.
- Changed appearance live preview refreshes to coalesce to the next frame via
  `RecyclerView.postOnAnimation`, skip refresh while `RecyclerView` is computing
  layout, and rebind only the visible selected card holder instead of issuing
  per-progress adapter change notifications.
- Added `ThingsAdapterWrapper.rebindVisibleItem(...)`, temporarily disabling
  card appearing animations during manual preview rebinds so dragging sliders
  does not replay card entry animations.
- Updated panel padding refresh so unchanged padding no longer triggers
  repeated `setPadding` or `smoothScrollToPosition` calls.
- Removed a briefly-added `IllegalArgumentException` catch in
  `ThingsStaggeredLayoutManager` after the user correctly objected that this
  crash must not be hidden. The fix now relies on the caller update-path change;
  if the issue remains, it will still surface in crash logs.
- Updated `docs/plans/THING_CARD_APPEARANCE_PLAN.md`,
  `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md`, memory decisions,
  preferences, and debug update notes.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- `rg` confirmed no new `Called attach on a child...` catch remained in the
  touched RecyclerView/layout code.
- No device reproduction smoke test was run in this agent session.

Publish:
- After the user explicitly requested publishing, ran
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain`.
- The publish task passed and published debug update `202606031433` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

Follow-up ratio slider and background-height preview fixes:
- Reworked the cover-image ratio editor in the bottom panel from seven preset
  pills plus a fixed 0.5-3.0 slider into one dynamic slider. The slider endpoints
  now come from the current effective min/max ratio implied by card width and
  configured thumbnail height guards, so dragging to either endpoint produces a
  real visual change instead of hitting a hidden renderer clamp.
- Added `ThingCardRatioTicksView` to draw labeled tick marks for the preset
  1:2, 9:16, 3:4, 1:1, 4:3, 16:9, and 2:1 ratios when those ratios are within
  the current reachable range. Releasing the slider near a reachable tick snaps
  to that preset ratio.
- Applied the same single-slider/tick/snap design in the precise crop editor
  and removed the old crop-editor preset ratio buttons.
- Added a title to the precise crop editor and changed its preview view from a
  weighted 75%-height block to a fixed height based on the selected image/video
  frame aspect ratio, capped by screen height.
- Corrected the previous crash fix: layout-changing appearance previews now
  coalesce slider changes and issue a deferred adapter item change only when the
  RecyclerView is idle, not computing layout, and has no pending adapter updates.
  This restores full-span media-background Card height preview while still
  avoiding adapter changes during layout/fling.
- Updated media-background card binding to set `llContent.minimumHeight`
  directly from the current saved height preference on every bind, so decreasing
  height or resetting it no longer preserves an old larger minimum height.
- Updated plan docs, memory decisions, and debug update notes.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- No device visual smoke test was run yet for the new slider ticks or restored
  full-span media-background height preview.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- A debug publish was attempted after updating `memory/debug-update-notes.md`,
  but the escalation reviewer rejected the external upload because this turn
  lacked current explicit user authorization to publish.
- After the user explicitly requested publishing, ran
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain`.
- The publish task passed and published debug update `202606031530` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

Follow-up media-background height shrink preview fix:
- Investigated the remaining full-span media-background Card height issue where
  increasing height previewed live but decreasing height only appeared after
  confirming and allowing the home list to relayout.
- Found the retained-height path in
  `BaseThingsAdapter.updateThingCardMediaBackgroundBottomStatusLayout()`: posted
  bottom-status layout work was not tied to the current media-background bind
  token, so a previous taller bind could write the old expanded
  `llTextContent.height` after a newer shorter bind.
- The same code used `llContent.height` in its target-height `max(...)`; during
  shrink preview that value can still be the old laid-out larger height, so it
  prevented immediate shrinkage.
- Changed the bottom-status posted work to check the current
  `tag_thing_card_media_background_bind_token` before writing layout params, and
  changed the expanded-height calculation to use the current target
  `llContent.minimumHeight` plus natural `llTextContent.measuredHeight` instead
  of old `llContent.height`.
- Updated plan docs, memory decisions, and debug update notes.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- Source guard confirmed the media-background bottom-status posted height write
  now has a bind-token check and no longer uses `llContent.height` as its target
  height basis.
- No device visual smoke test was run for the shrink preview in this agent
  session.

2026-06-03 - Thing Card Appearance normal/full/background source audit before
commit:

- Audited the other appearance adjustment paths requested by the user:
  normal-span thumbnail placement/crop/ratio, full-span thumbnail
  placement/crop/ratio/side-width, and media-background card-height changes.
- Confirmed the bottom-panel draft updates all flow through the coalesced,
  deferred `notifyItemChanged` preview path, so size-changing preview updates do
  not directly mutate RecyclerView children while layout is computing.
- Confirmed top/bottom thumbnail ratio slider endpoints are derived from the
  same card-width and available-height clamps used by card rendering, so slider
  endpoints correspond to values that can actually render.
- Confirmed full-span left/right media width changes alter the image column and
  content-column widths, then the side-image height post is protected by
  `tag_thing_card_side_image_bind_token`.
- Confirmed media-background height shrink preview uses current
  `llContent.minimumHeight` plus natural text-content height and protects
  posted bottom-status layout work with
  `tag_thing_card_media_background_bind_token`.
- Added render-request tags for thumbnail and media-background image views so
  Glide resource callbacks apply the current crop/size request, not older values
  captured by a stale posted callback.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guard found no old `rebindVisibleItem`, no new `Called attach on a
  child...` catch, no old ratio preset-button members, and no `llContent.height`
  target-height use in the appearance media paths.
- No device visual smoke test was run in this agent session.

2026-06-03 - Undo mistaken Git commit and publish debug update:

- The user clarified that "submit" meant publishing a debug update, not creating
  a Git commit.
- Reverted the mistaken commit `69e294d` with `git reset --mixed HEAD~1`, keeping
  all code, resource, docs, and memory changes in the working tree for further
  testing.
- Recorded the preference that debug-cycle "submit" requests mean publish, and
  Git commits should only be created after an explicit commit request or after
  the user confirms the tested version has no obvious bugs.
- Ran
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain`.
- Publish passed and uploaded debug update `202606031600` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-04 - Thing Card Appearance crop-center confirmation fix

- User clarified that the crop-preview mismatch was not about image EXIF
  direction. The concrete failing case was a 16:9 source image, 1:1 cover ratio,
  crop center moved to the right in the precise crop dialog, and the card still
  appearing center-cropped after dialog confirmation.
- Diagnosed the crop-center path: `ThingCardCropEditorView` updates
  `centerX`/`centerY`, and the confirm button writes those values into the
  appearance draft, but the live card preview relied only on the deferred
  layout-changing `notifyItemChanged` path. That path can wait behind
  RecyclerView layout, pending adapter updates, scroll state, or item animator
  state, even though crop center/zoom changes do not need card remeasurement.
- Added `BaseThingsAdapter.applyThingCardMediaCropToBoundHolder(...)` to apply
  the current thumbnail or media-background crop matrix directly to an already
  bound visible card holder.
- The direct path updates the current thumbnail/background render-request tag
  before applying the matrix, so older Glide callbacks for the same load key
  keep using the latest crop rather than restoring the previous center crop.
- Added `ThingsAdapterWrapper.applyThingCardMediaCropToBoundHolder(...)` and
  `ThingsActivity.applyCurrentThingCardCropToVisiblePreview()`.
- Changed precise-crop confirmation so center/zoom-only confirmations apply the
  new crop matrix immediately to the selected visible card. If the confirmation
  changes the crop aspect ratio, the existing deferred `notifyItemChanged` path
  still handles the required card remeasure.
- Updated `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md`,
  `memory/decisions.md`, and `memory/debug-update-notes.md`.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guards confirmed the confirm button reads `cropView.getCropCenterX()`
  and `getCropCenterY()`, ratio-unchanged confirmations call the direct preview
  refresh, and no debug log or new framework-exception catch was added.

Publish:
- Ran
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain`.
- Publish passed and uploaded debug update `202606040101` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance final-card Glide crop fix

- User clarified that the crop-center mismatch was not related to
  `notifyItemChanged` timing. Even after exiting and reopening the app, the card
  still ignored the saved crop center, while reopening the precise crop dialog
  showed the correct saved center.
- Re-traced the final card render path and identified the real root cause:
  `setThingCardImageFrameSize()` reset the thumbnail `ImageView` to
  `CENTER_CROP`, then the final card loaded media through `Glide.into(ImageView)`.
  Glide can auto-apply a `centerCrop` transform from the target
  `ImageView.scaleType`, pre-cropping the drawable to the target dimensions
  before the app-owned crop matrix runs. Once the drawable is already target
  sized, saved `centerX` / `centerY` have no extra source area to pan.
- Added `.dontTransform()` to both `BaseThingsAdapter.loadThingCardImage()` and
  `BaseThingsAdapter.loadThingCardMediaBackground()` so Glide loads the image or
  video frame without auto center-cropping it.
- Final cover-fit, crop center, and user zoom are now owned by
  `applyThingCardMediaCrop()` for both separate thumbnails and media
  backgrounds.
- Updated `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md`,
  `memory/decisions.md`, and `memory/debug-update-notes.md`.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guard confirmed both final-card media Glide requests use
  `.dontTransform()` and both still flow through `applyThingCardMediaCrop()`.

Publish:
- Ran
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain`.
- Publish passed and uploaded debug update `202606040141` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance media-background height shrink fix

- User confirmed that the remaining issue was media-background Card height:
  adjusting the height slider was still hard to apply, especially when
  shrinking from a larger height, and asked to revert the previous transparent
  height-target `View` approach.
- Searched Android documentation for the relevant constraints: size changes
  need the normal measure/layout path, RecyclerView adapter updates must avoid
  layout-computing states, and ItemAnimator keeps/manages changed views during
  animations.
- Removed `view_thing_media_background_height_target` from `card_thing.xml`,
  removed the related `BaseThingViewHolder` field, and deleted the old
  `setThingCardMediaBackgroundHeightTarget` /
  `getThingCardMediaBackgroundHeightTarget` helpers.
- Changed media-background binding back to using `llContent.minimumHeight` as
  the current card-height target.
- Added explicit overlay-size handling: each media-background bind resets the
  background image and mask to `match_parent` before requesting layout, then
  after root-card pre-draw synchronizes both overlay children to the final
  measured root-card width/height before loading media and applying the crop
  matrix. Direct visible-holder crop refreshes use the same synchronization.
- Reset media-background overlay sizes when hiding the background or when a
  background load fails, preventing stale exact sizes from leaking across holder
  reuse.
- Changed the appearance-preview refresh path so it still defers while
  RecyclerView is computing layout, has pending adapter updates, or is
  scrolling, but ends any old item animator before issuing the current
  `notifyItemChanged` instead of waiting behind prior change animations.
- Updated `docs/plans/THING_CARD_APPEARANCE_PLAN.md`,
  `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md`, and `memory/decisions.md`
  to mark the transparent height-target approach as superseded.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- Source guard confirmed no remaining references to
  `view_thing_media_background_height_target`, the old height-target helpers,
  or `vMediaBackgroundHeightTarget`.
- No device visual smoke test was run in this agent session.
- Published debug update `202606040245` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance media-background parent-height feedback fix

- User reported that debug update `202606040718` did not fully fix the
  minimum-height media-background card growing after long-press selection. They
  observed the issue mainly for cards near the top or lower part of the list and
  suspected extra scrolling or animation.
- Checked official Android RecyclerView documentation. `notifyDataSetChanged()`
  forces visible views to rebind and relayout, `ItemAnimator` can keep old/new
  holder presentations during item changes, and LayoutManager item-animation
  behavior is involved in boundary/pre-layout handling. This supported the
  user's observation that list edges make the problem easier to trigger.
- Diagnosed the remaining root cause as a parent-height feedback loop in
  media-background rendering: bind computed the intended background height, but
  pre-draw then read `CardView.height` and wrote that value back into
  `iv_thing_media_background.layoutParams.height`. At RecyclerView boundaries,
  that parent height could still be stale.
- Changed `BaseThingsAdapter.loadThingCardMediaBackgroundAfterLayout(...)` so
  pre-draw uses `CardView.width` only for media loading/crop and keeps height
  from `max(saved media-background target height, natural content height)`.
- Changed media-background direct crop application to use the same effective
  target height instead of `holder.cv.height`, preventing stale parent height
  from affecting live crop preview.
- Kept the previous selection-mode animation cleanup and the media-count 44dp
  bottom reserve.
- Updated plan docs, memory decisions, and debug update notes.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guard confirmed no app-code `[DEBUG-...]` logs and no target-path
  media-background `card.height` / `holder.cv.height` height feedback remains.
- No device visual smoke test was run in this agent session.
- Published debug update `202606040744` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance media-background count icon

- User requested a small image-count icon to the left of the media-background
  image/video count, matching the audio-count row, with adaptive color.
- Replaced the root media-background count `TextView` in `card_thing.xml` with
  a horizontal `ll_thing_media_attachment_count` container holding
  `iv_thing_media_attachment_count` plus the existing count text.
- Updated `BaseThingsAdapter` so media-background count visibility and alpha are
  controlled on the container, while both the icon tint and count text use the
  same adaptive tertiary foreground color computed from the masked
  media-background card.
- Updated `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md`,
  `memory/decisions.md`, and `memory/debug-update-notes.md`.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed before publishing.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guard confirmed the container/icon references are present, the old
  direct `tvMediaCount.visibility` path is absent, and no app-code `[DEBUG-...]`
  logs were added.
- No device visual smoke test was run in this agent session.
- Published debug update `202606040849` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Video crop preview loading indicator and completion icon

- User reported that the video precise-crop preview starts blank while loading,
  and asked for a progress bar that adapts to the current Thing Background as a
  pure colour or gradient. User also reported that playback completion should
  switch the pause icon back to the play icon.
- Added a loading overlay to `ThingCardVideoCropEditorView`: the `TextureView`
  stays alive but transparent while `MediaPlayer.prepareAsync()` and the first
  decoded frame are pending, and a rounded App Chrome preview surface with a
  custom indeterminate progress arc is drawn on top.
- The loading indicator accepts the current `ThingBackground`: pure backgrounds
  draw a solid arc, while gradient backgrounds draw a `SweepGradient` arc.
- The loading overlay is hidden only after the first
  `onSurfaceTextureUpdated` callback so the user does not see the blank
  `TextureView` between prepare completion and first-frame rendering.
- Replaced immediate `MediaPlayer.isPlaying()`-only state with an explicit
  `playing` flag inside the video crop view. `onCompletion`, `pause`,
  `stopPlayback`, `onError`, and `release` set it false and notify controls.
- Updated `ThingsActivity` to pass the current appearance accent background to
  the video crop view and to update the play/pause button from the callback's
  boolean state.
- Updated `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md`,
  `memory/decisions.md`, and `memory/debug-update-notes.md`.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guards confirmed the `LoadingView`, `SweepGradient`,
  first-frame-based loading dismissal, explicit completion state, and
  `ThingBackground` handoff from `ThingsActivity`.
- No device visual smoke test was run in this agent session.
- Published debug update `202606041240` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Video crop completion fallback for play icon

- User reported that the video crop dialog's play/pause button still did not
  automatically switch back to the play icon after playback finished.
- Diagnosed that relying only on `MediaPlayer.OnCompletionListener` was not
  enough for the observed device/video path.
- Added `finishPlaybackIfNeeded()` to the video crop view's 80ms position
  ticker. It checks the current playback position and actual
  `MediaPlayer.isPlaying` state, then treats near-end playback or stopped-at-end
  playback as completion.
- Added a single `finishPlayback(...)` path shared by listener-based and
  ticker-based completion. It clears `pendingPlay` and the explicit `playing`
  flag, updates `currentFrameMs`, emits `onPositionChanged`, and dispatches
  `onPlayingChanged(false)`.
- Added `dispatchPlayingChanged(...)` so play-state callbacks are delivered on
  the main thread even if a media callback arrives elsewhere.
- Updated `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md`,
  `memory/decisions.md`, and `memory/debug-update-notes.md`.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guards confirmed `finishPlaybackIfNeeded()`, `finishPlayback(...)`,
  main-thread `dispatchPlayingChanged(...)`, and the existing
  `OnCompletionListener` both route to the same play-icon state.
- No device visual smoke test was run in this agent session.
- Published debug update `202606041251` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance auto media source label fix

- User asked what "Auto select" means in the Thing Card Appearance media source
  popup and reported that the filename shown after the auto row sometimes did
  not match the media actually selected after tapping it.
- Confirmed product semantics: `mediaSourceKey = null` means auto source, which
  resolves to the first available image/video source in the Thing attachment
  string order. It is not separately sorted by file modified time or latest
  added time.
- Found the bug in `ThingsActivity.showThingCardAppearanceSourceMenu()`:
  the auto row label used the current draft's effective media source, so when an
  explicit source B was selected it could display "Auto select · B"; tapping it
  cleared `mediaSourceKey`, and the renderer correctly switched to default
  source A, causing the label to update after the click.
- Fixed the auto row to resolve its label with
  `ThingCardMediaHelper.resolveEffectiveMediaSource(thing.attachment, null)`,
  matching the actual source selected by clicking the row.
- Updated `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md`,
  `memory/decisions.md`, and `memory/debug-update-notes.md`.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guards confirmed the auto row now resolves with a null source key and
  no longer reuses the current explicit draft source for its filename.
- No device visual smoke test was run in this agent session.
- Published debug update `202606041258` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance exact video cover frame fix

- User reported that pausing in the "Crop cover video" dialog could show a
  different frame from the final Thing Card cover, suggesting that the persisted
  cover used a closest I-frame/keyframe instead of the exact selected frame.
- User also reported a near-end case where confirming the final frame, then
  reopening the dialog, could leave the preview in the loading state even
  though pressing Play made the video render normally.
- Checked local Glide 4.16.0 source and confirmed
  `RequestOptions.frameOf(...)` defaults to
  `MediaMetadataRetriever.OPTION_CLOSEST_SYNC`.
- Updated `BaseThingsAdapter` thumbnail and media-background video Glide
  requests to set
  `VideoDecoder.FRAME_OPTION = MediaMetadataRetriever.OPTION_CLOSEST`.
- Updated the remaining ThingsActivity video-frame preview Glide path and the
  crop-dialog opening-time `MediaMetadataRetriever.getFrameAtTime(...)` path to
  use exact-frame `OPTION_CLOSEST` semantics.
- Added a shared `ThingsActivity` video-frame clamp so frame sliders and saved
  `videoFrameMs` avoid `duration - 1ms` metadata tail positions that some
  devices cannot render into a paused `TextureView`.
- Updated `ThingCardVideoCropEditorView` to use the same renderable end guard
  for playback completion, replay-from-end, seek clamping, and confirmed frame
  reads. `getCurrentFrameMs()` now reads `MediaPlayer.currentPosition`
  immediately when prepared instead of relying only on the 80ms ticker cache.
- Added a bounded first-frame fallback after `MediaPlayer.OnSeekComplete` so
  the loading overlay cannot remain visible forever if no
  `onSurfaceTextureUpdated` callback arrives for a near-end seek.
- Updated `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md`,
  `memory/decisions.md`, and `memory/debug-update-notes.md`.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed twice after the implementation and follow-up current-position read.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guard confirmed the relevant `frameOf(...)` paths now set
  `VideoDecoder.FRAME_OPTION`, `getFrameAtTime(...)` uses
  `MediaMetadataRetriever.OPTION_CLOSEST`, and no `OPTION_CLOSEST_SYNC` remains
  in the touched video-cover paths.
- No device video smoke test was run in this agent session.
- Published debug update `202606041334` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Video crop gesture preview feedback fix

- User reported that in the "Crop cover video" dialog, pinch zoom and dragging
  appeared to change the crop values, but the preview did not visibly show the
  current zoom level or crop position.
- Diagnosed the video crop view's preview path: it laid a fixed-size
  `TextureView` over the preview region and then tried to apply the crop matrix
  through `TextureView.setTransform(...)`. That path can fail to repaint
  immediately while paused or before the first surface frame is available.
- Changed `ThingCardVideoCropEditorView` so the `TextureView` itself is laid
  out as the scaled source-media rectangle, using the same `imageLeft`,
  `imageTop`, and scaled source dimensions already computed for the image crop
  editor geometry.
- Added a `FallbackFrameView` that draws the already decoded opening video
  frame under the same crop overlay while loading or when the first texture
  frame callback is missing.
- Kept the crop overlay visible whenever fallback preview is available, so the
  crop frame and dimmed non-selected region remain visible during initial
  loading and gesture changes.
- Adjusted the first-frame fallback behavior so a missing texture update hides
  only the spinner and keeps the fallback crop preview instead of switching to a
  potentially blank `TextureView`.
- Passed the opening decoded video frame from `ThingsActivity.openThingCardCropEditor()`
  into `ThingCardVideoCropEditorView.setCropVideo(...)`.
- Updated `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md`,
  `memory/decisions.md`, and `memory/debug-update-notes.md`.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guard confirmed `FallbackFrameView`, `showFallbackPreviewOnly()`,
  fallback bitmap handoff, and dynamic `TextureView.layout(...)` are present.
- No device video crop smoke test was run in this agent session.
- Published debug update `202606041347` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance feature commit preparation

- User requested one Git commit for the accumulated Thing Card Appearance
  feature work after several debug-update iterations.
- Staged the feature-related tracked changes plus new app source/resource files
  and the new `docs/adr/0002-unified-thing-card-appearance.md`,
  `docs/plans/THING_CARD_APPEARANCE_PLAN.md`, and
  `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md` documents.
- Left unrelated or temporary untracked files out of the commit, including
  `.claude/skills/`, `Everything-Android/`, `docs/analysis/`,
  generated memory inspection/logcat/sqlite outputs, debug publish logs, and
  translation helper scripts.
- The commit message should focus on the unified appearance model, card
  renderer, appearance panel/crop editors, media-background/video support, and
  persistence/data-model changes rather than enumerating every small bug fixed
  during device feedback.

## 2026-06-06 - Refresh home header after card appearance panel hides

- User requested recalculating the home header state after customising a Thing
  Card Appearance, then clarified that cancellation and every panel-hide path
  should do the same because the panel changes RecyclerView bottom padding.
- Updated `ThingsActivity.hideThingCardAppearancePanel()` to request an
  ActivityHeader state refresh after the panel is hidden and RecyclerView
  bottom padding is restored.
- The refresh is posted to the RecyclerView so the header is recalculated after
  panel removal, padding restoration, and any card relayout caused by
  appearance changes.
- Updated `ActivityHeader.updateAll()` to keep the actionbar shadow alpha cache
  in sync on every recalculation and to cancel an in-flight shadow animation
  before applying a non-animated recalculated alpha. This prevents an old
  selecting-mode shadow animation from overriding the recalculated shadow after
  the card appearance panel hides.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606060318` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
  --console=plain --no-configuration-cache`.
- Published debug update `202606060332` for the later panel-hide unification
  with the same publish task.
- Published debug update `202606060339` for the later actionbar shadow alpha
  synchronisation with the same publish task.

## 2026-06-03 — Thing Card Appearance panel wording and picker refinement

- Renamed the unfixed media source state from "Default source" to "Auto select"
  and added a "Cover image" indicator label to the source row.
- Replaced the platform `PopupMenu` source selector with a new App
  Chrome-styled `ThingCardAppearanceSourcePicker` based on `PopupPicker`,
  matching the DetailActivity quick-reminder popup style more closely.
- Added a "Card width" indicator label and changed span options to Normal/Wide
  (Simplified Chinese: 正常/宽).
- Changed segmented selected states for card width, media position, and
  cover-image ratio from accent text to a full Thing Background pill with
  adaptive foreground text.
- Renamed the thumbnail ratio label to "Cover image ratio" / "封面图片比例" and
  expanded shortcuts to 1:2, 9:16, 3:4, 1:1, 4:3, 16:9, and 2:1, with the
  slider sharing the second row.
- Moved the Crop action into the bottom action row on the left and adjusted
  Cancel/Confirm spacing to resemble compact DetailActivity dialog buttons.
- Updated indicator labels to use App Chrome hint colour for light/dark mode.
- Updated plan docs, memory decisions, and debug update notes.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain` and
  `git diff --check` (only existing LF/CRLF warnings), then published debug
  update `202606031350`.

## 2026-06-04 - Thing Card Appearance media-background height ImageView owner

- User reported that the previous media-background height shrink fix still did
  not solve the issue and asked whether the background `ImageView` could drive
  the height directly by changing `layoutParams.height`.
- Reworked the height owner accordingly. `BaseThingsAdapter.updateCardForMediaBackground()`
  no longer uses `llContent.minimumHeight` as the media-background height owner.
- Added direct overlay-height application for `iv_thing_media_background` and
  `view_thing_media_background_mask`. On bind, both receive the current target
  height before layout so the root `wrap_content` card measures against the real
  visible background layer. After root-card pre-draw, both are set to the final
  measured root-card height before media load/crop matrix application.
- Added content-expansion reset before each media-background bind so stale
  bottom-status text-content heights from a previous larger height do not
  survive into the next smaller-height pass.
- Added `BaseThingsAdapter.applyThingCardMediaBackgroundHeightToBoundHolder(...)`
  and `ThingsAdapterWrapper` forwarding.
- Changed `ThingsActivity.updateThingCardBackgroundHeight()` so height-slider
  changes update the draft, then directly apply the new background `ImageView`
  height to the currently visible selected holder and request layout on both
  the item and RecyclerView. It falls back to deferred `notifyItemChanged` only
  when the selected holder is not visible.
- Updated plan docs, memory decisions, and debug update notes to mark the
  previous `llContent.minimumHeight` owner as superseded.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- Source guard confirmed no old transparent height-target View references and
  confirmed the new direct height entry is connected through adapter wrapper
  and activity.
- No device visual smoke test was run in this agent session.
- Published debug update `202606040335` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance background-height slider minimum

- User reported that the media-background Card height slider still exposed a
  lower range that could not affect rendering when the selected card already
  had enough note content to require a larger natural content height.
- Changed the background-height slider to use the selected card's current
  natural content height as its dynamic minimum, converted to the same
  available-height percentage scale used by `mediaBackgroundHeightRatio`.
- `bindThingCardAppearanceBackgroundControls()` now sets
  `seek_thing_card_appearance_background_height.min` to that dynamic minimum
  and clamps the displayed saved value into `[dynamicMin, maxPercent]`.
- Added `getThingCardBackgroundHeightSliderMinPercent()` and
  `getThingCardBackgroundNaturalHeight()` to measure the current visible
  selected holder's text-content natural height.
- Changed `updateThingCardBackgroundHeight()` so values at or below the dynamic
  minimum save `mediaBackgroundHeightRatio = null`, matching the no-extra-height
  rendering state. Reset still clears the ratio and the UI rebinds the thumb to
  the dynamic minimum.
- Updated plan docs, memory decisions, and debug update notes.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- Source guard confirmed the dynamic min helper and SeekBar `min` assignment
  are present.
- No device visual smoke test was run in this agent session.
- Published debug update `202606040349` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance background-height natural measurement fix

- User reported that the dynamic minimum for the media-background Card height
  slider was still sometimes inaccurate.
- Diagnosed the remaining mismatch: the Activity measured the live
  `ll_thing_text_content` holder view, but media-background layout can already
  have expanded that view's height and enabled `view_thing_bottom_status_spacer`
  with `weight=1` to push bottom status to the bottom edge.
- Added `measureThingCardMediaBackgroundNaturalHeight(...)`, which temporarily
  restores `ll_thing_text_content` to `wrap_content`, disables the artificial
  bottom-status spacer, measures natural content height, then restores the live
  layout params and visibility.
- Reused this helper both for the background-height slider dynamic minimum and
  for media-background crop dialog target-height calculation, so both paths use
  the true natural content height instead of a previously expanded layout state.
- Updated plan docs, memory decisions, and debug update notes.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- Source guard confirmed both dynamic slider minimum and media-background crop
  target height use the new natural-height helper.
- No device visual smoke test was run in this agent session.
- Published debug update `202606040405` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance background UI and crop dialog polish

- User reported four remaining Thing Card Appearance issues: misaligned
  media-background mask/Card height sliders, unnecessary Card height Reset
  button, crop dialog preview showing only the crop frame instead of the whole
  image, crop dialog title/action chrome mismatch, and flashes of the original
  Thing Background while dragging media-background Card height.
- Aligned the media-background mask and Card height rows by using the same
  label width, removed the Card height Reset UI and code, and removed its
  localized string resources.
- Changed `ThingCardCropEditorView` to fit and draw the full oriented source
  bitmap, map the saved crop center/user scale to a crop rectangle, and dim
  only the non-selected editor regions.
- Polished the precise crop dialog to use the current Thing Background accent
  for the title, existing-dialog-like content/action margins, and fixed 36dp
  pill ripple surfaces for cancel/confirm actions.
- Changed media-background loading so same-source target-size changes reuse
  the current drawable immediately, reapply the crop matrix for the new target,
  and use that drawable as the Glide placeholder to reduce flashes during
  height dragging.
- Updated plan docs, memory decisions, and debug update notes.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guard confirmed no Reset UI/code references remain except removed
  string resources, and no new `[DEBUG-...]` logs were added in app code.
- No device visual smoke test was run in this agent session.
- Published debug update `202606040512` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance crop zoom and height drag fixes

- User reported five follow-up issues after testing debug update `202606040512`:
  crop zoom should keep the crop frame fixed and scale the image instead of
  resizing the crop frame; media-background sliders should sit closer to their
  labels; crop preview and crop frame should have rounded corners; dragging the
  Card height slider near the left end could suddenly make the card taller; and
  media-background image-count text flickered during height changes.
- Changed `ThingCardCropEditorView` back to a fixed-frame crop model. The crop
  frame is derived from the target aspect ratio; drag and pinch update the
  image matrix under that frame, matching the final card crop renderer.
- Added rounded clipping for the whole crop preview area and a rounded crop
  frame, with the dim overlay drawn as `previewPath - cropPath`.
- Reduced media-background label width from 112dp to 104dp so the mask and Card
  height sliders sit closer to their descriptive text while staying aligned.
- Cached the media-background height slider's dynamic minimum at panel binding
  time and changed continuous height updates to mutate the draft without
  rebinding the full panel on every progress event. This prevents live holder
  measurement churn from changing the left-end threshold while the user is
  dragging.
- Changed the direct visible-holder media-background height path to avoid a
  full content-expansion reset on every tick. It now measures natural content
  height transiently and applies the final text-content height directly.
- Moved `tv_thing_media_attachment_count` from `ll_thing_text_content` into a
  bottom Card overlay so the image-count label no longer participates in
  spacer-driven text-content re-layout.
- Updated plan docs, memory decisions, and debug update notes.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guard confirmed old crop editor source-rectangle fields, Reset button
  references, and app-code `[DEBUG-...]` logs are absent.
- No device visual smoke test was run in this agent session.
- Published debug update `202606040630` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance left-end height preview fix

- User reported three follow-up issues after testing `202606040630`: the
  media-background mask/Card height labels should be `wrap_content` instead of
  a fixed width; the media-background image-count overlay needed top spacing
  from text/reminder/status content; and the Card height slider's left-end
  preview still had a tiny range where dragging left made the card taller even
  though saved data and app-reopen rendering were correct.
- Changed the two background control labels in
  `panel_thing_card_appearance.xml` to `wrap_content` and added an 8dp start
  margin on the following SeekBars.
- Added an 8dp top margin to the media-background image-count overlay and
  changed `BaseThingsAdapter` to reserve 44dp in `view_thing_padding_bottom`
  while that overlay is visible, restoring the normal 16dp padding otherwise.
- Diagnosed the remaining left-end preview jump as a direct-preview issue:
  when `mediaBackgroundHeightRatio` became `null`, `targetMinHeight` was `0`,
  and passing that to `setThingCardMediaBackgroundOverlayHeight(...)` converted
  the background `ImageView` height to `MATCH_PARENT`. That could reuse stale
  parent height during live preview even though the saved value was correct.
- Changed `updateCardForMediaBackground(...)` to update media-count reserve
  before measuring natural content height, then compute
  `effectiveTargetHeight = max(targetMinHeight, naturalContentHeight)` and use
  that positive height for the background image, mask, bind token, and
  bottom-status expansion.
- Updated plan docs, memory decisions, and debug update notes.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guard confirmed old direct `targetMinHeight`/`0` overlay-height calls,
  fixed `104dp` labels, and app-code `[DEBUG-...]` logs are absent.
- No device visual smoke test was run in this agent session.
- Published debug update `202606040647` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance selection-mode minimum-height fix

- User reported that when a media-background Thing Card was at its minimum
  height, long-pressing it to enter selection mode made the card height grow
  again even though the saved database value was correct.
- Diagnosed this as a live selection-mode rebind problem rather than a
  persistence problem. Entering selection mode does a full card refresh for
  selected/unselected chrome, and the refresh could run while RecyclerView item
  change animations still held old measured card state.
- Changed `ModeManager.toSelectingMode(...)` to end running RecyclerView item
  animations and disable `SimpleItemAnimator.supportsChangeAnimations` before
  issuing the selection-mode `notifyDataSetChanged()` path.
- Kept the media-background image-count overlay's 44dp bottom reserve so the
  previous spacing fix for title/content/audio/reminder/habit rows was not
  regressed.
- Updated plan docs, memory decisions, and debug update notes.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guard confirmed no app-code `[DEBUG-...]` logs; the expected
  selection-mode `endAnimations()` / `supportsChangeAnimations = false` calls
  and the media-count 44dp reserve are present.
- No device visual smoke test was run in this agent session.
- Published debug update `202606040718` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance media-background natural-width measurement fix

- User shared a DeepSeek analysis for the remaining minimum-height
  media-background card growth after NORMAL -> MOVING -> SELECTING. The analysis
  identified inconsistent natural-height measurement widths: bind-time
  measurement could use stale `ll_thing_text_content.width` / `CardView.width`
  from a reused holder, while pre-draw measurement used the current laid-out
  width.
- Verified the code still preferred current view widths in
  `measureThingCardMediaBackgroundNaturalContentHeight(...)`, so the analysis
  was plausible and matched the user's top/bottom list-position observations.
- Changed `measureThingCardMediaBackgroundNaturalContentHeight(...)` to prefer
  the explicit current-bind content width from `llContent.layoutParams.width`,
  then fall back to laid-out text/card widths only if no explicit width is
  available.
- Changed `ModeManager.toMovingMode(...)` to use the same RecyclerView
  mode-rebind preparation as `toSelectingMode(...)`: end running item animations
  and disable change animations before the full mode refresh.
- Updated plan docs, memory decisions, and debug update notes.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed after fixing a nullable Kotlin width expression.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guard confirmed the width priority and moving/selecting mode rebind
  preparation are present and no app-code `[DEBUG-...]` logs were added.
- No device visual smoke test was run in this agent session.
- Published debug update `202606040806` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance background-height slider minimum width fix

- User shared a broader audit that confirmed the adapter-side
  media-background natural-height fix and identified a related stale-width path
  in `ThingsActivity.getThingCardBackgroundNaturalHeight()`.
- The issue affected the Thing Card Appearance panel's media-background Card
  height slider minimum: panel binding could use stale `llTextContent.width` or
  `CardView.width` from a recently rebound holder, making the slider minimum
  temporarily lower than the renderer's actual natural-height floor.
- Changed `ThingsActivity.getThingCardBackgroundNaturalHeight()` to prefer the
  selected holder's explicit current-bind `llContent.layoutParams.width`, then
  fall back to `getThingCardAppearancePreviewCardWidth()`.
- Updated plan docs, memory decisions, and debug update notes.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guard confirmed the slider-min natural-height path now reads
  `llContent.layoutParams.width` and no app-code `[DEBUG-...]` logs were added.
- No device visual smoke test was run in this agent session.
- Published debug update `202606040828` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance media-background count icon color and alignment fix

- User reported that the media-background image/video count icon from
  `202606040849` did not visually match the count text color and its left edge
  did not align with surrounding card content.
- Diagnosed the color issue by comparing against the audio-count implementation:
  audio count switches between black/white icon resources while the text uses
  tertiary foreground color. The image-count icon had been tinted with the exact
  tertiary text ARGB value, double-applying alpha because the PNG itself is
  already semi-transparent.
- Measured `ic_image_count.png`: the mdpi asset is 24dp x 24dp, but its visible
  bounds start at x=4dp and y=5dp. This transparent canvas caused the visual
  left edge to appear inset.
- Changed `card_thing.xml` so `iv_thing_media_attachment_count` is a 17dp x
  14dp matrix-scaled frame.
- Added `BaseThingsAdapter.applyThingCardMediaCountIcon(...)`, which clears the
  previous tint list, applies opaque black/white `PorterDuff.Mode.SRC_IN`
  according to the foreground side, and translates the image matrix by -4dp /
  -5dp to crop out transparent padding.
- Updated plan docs, decisions, and debug update notes to supersede the exact
  text-color tint rule with audio-like black-side/white-side icon semantics.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guard confirmed the new helper, matrix crop, and black/white color
  filter path are present, and the old
  `ColorStateList.valueOf(mediaCountColor)` icon tint path is absent.
- No device visual smoke test was run in this agent session.
- Published debug update `202606040946` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance media-background count icon opacity fix

- User reported that the media-background image/video count icon still looked
  lighter than the count text after debug update `202606040946`.
- Confirmed the remaining cause was the global `ic_image_count.png` asset: its
  mdpi source is 24dp x 24dp with max alpha `0x8A`, while the count text uses
  the 66% tertiary tier `0xA8`.
- Generated card-specific `card_image_attachment_count.png` and
  `card_image_attachment_count_black.png` resources for mdpi/hdpi/xhdpi/xxhdpi/
  xxxhdpi. The new resources crop to the visible icon bounds and raise max alpha
  to `0xA8`, without modifying the global `ic_image_count.png`.
- Updated `card_thing.xml` so `iv_thing_media_attachment_count` uses the new
  intrinsic-size resource with `wrap_content`, `adjustViewBounds`, and
  `centerCrop`.
- Updated `BaseThingsAdapter.applyThingCardMediaCountIcon(...)` to switch
  between the new black/white resources like the audio attachment count, while
  clearing any old tint/filter state.
- Updated plan docs, decisions, and debug update notes to record the final
  card-specific PNG resource rule.

Verification:
- New resource check confirmed mdpi `card_image_attachment_count*.png` is 17dp x
  14dp with max alpha `0xA8`; original `ic_image_count.png` max alpha remains
  `0x8A`.
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guard confirmed all 10 density resources exist and the old
  `mediaCountColor` icon tint / adapter `ic_image_count` / image-count matrix
  crop paths are absent.
- No device visual smoke test was run in this agent session.
- Published debug update `202606040957` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Slider track drawable and video attachment crash fix

- User reported that after the slider gradient/inactive-track update, only the
  thumb was visible and no track could be seen.
- User also provided a crash log from adding a video attachment in
  `DetailActivity`: `FileProvider.getUriForFile(...)` failed because the chosen
  camera video path under `/storage/emulated/0/DCIM/Camera/...` was outside the
  app provider's configured roots.
- Diagnosed the slider issue as an unreliable `LayerDrawable` /
  `InsetDrawable` / `ClipDrawable` progress drawable composition for the current
  `SeekBar` bounds and level handling.
- Replaced the slider track implementation in `DisplayUtil` with a custom
  `SeekBarProgressDrawable` that draws the inactive track across the full bounds
  and then draws the active segment from the drawable `level`. Gradient
  `ThingBackground`s are rendered through `LinearGradient`; thumbs remain
  representative-colour handles.
- Replaced `DetailActivity.getTypePathName(...)` video classification's
  `MediaPlayer` + `FileProvider` path with `MediaMetadataRetriever`, preferring
  the original picker `content://` URI and falling back to file paths only when
  no source URI exists. Ambiguous `mp4` / `3gp` files can still classify as
  audio when metadata confirms there is no video track.
- Updated `memory/decisions.md` and `memory/debug-update-notes.md`.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed after restoring the existing `java.io.File` import needed by the
  screenshot callback.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- No device visual smoke test was run in this agent session.
- Published debug update `202606041026` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Slider track disappears after dragging follow-up

- User reported that the slider track was visible before interaction, but after
  dragging once only the thumb remained visible.
- Checked local Android 36 SDK source for `ProgressBar.setVisualProgress(...)`.
  It updates the drawable level on the `LayerDrawable` child matching
  `android.R.id.progress` or `android.R.id.secondaryProgress`; if a layer is
  missing, it can fall back to the whole drawable.
- Superseded the single self-drawing `SeekBarProgressDrawable` approach in
  `DisplayUtil`.
- Rebuilt the SeekBar progress drawable as a standard three-layer
  `LayerDrawable`:
  `android.R.id.background` draws the complete inactive track,
  `android.R.id.secondaryProgress` is a transparent placeholder, and
  `android.R.id.progress` is a `ClipDrawable` around the active track.
- Kept gradient rendering inside `SeekBarTrackDrawable` and kept thumbs tinted
  with `ThingBackground.representativeColor()`.
- Updated `memory/decisions.md` and `memory/debug-update-notes.md`.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- Source guard confirmed the three layer IDs and `ClipDrawable` progress layer
  exist, and the old single `SeekBarProgressDrawable` symbol is gone.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- No device visual smoke test was run in this agent session.
- Published debug update `202606041130` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance hidden image/video media

- Added a persistent None source state for Thing Card Appearance media:
  `mediaSourceKey = null` remains Auto select, explicit source keys still pick a
  concrete image/video, and `ThingCardAppearance.MEDIA_SOURCE_NONE` hides all
  image/video media on the card.
- Preserved per-source crop, video-frame, background, and sizing settings while
  hidden, so selecting Auto or a concrete source later restores the previous
  media-specific appearance.
- Kept the Card width controls visible while media is hidden; only
  media-dependent controls such as position, crop, ratio, background mask, and
  background height are hidden.
- Changed the customize-card-appearance action gating to check available media
  attachments rather than the current effective media source, so users can
  reopen the panel after choosing None.
- Added an inline image/video count row for the hidden-media card state, using
  the same content-flow margins and sparse-card enlargement behavior as the
  audio attachment count row. Media-background cards still use their existing
  bottom overlay count row.
- Updated Simplified Chinese, Traditional Chinese, and default English strings
  for the new None source option.

Verification:
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606041437` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Hidden media appearance follow-up

- User corrected three details after the first hidden-media debug build:
  None should appear at the bottom of the source popup, hidden-media cards need
  a full card re-layout after side-media layouts, and ordinary no-image/video
  Things should also support Thing Card Appearance for Card width only.
- Moved the None source picker item after Auto and all concrete media sources.
- Changed the customize-card-appearance action to be available for any eligible
  real selected Thing, not only Things with image/video attachments.
- Let `openThingCardAppearancePanel()` open with an empty media-source list.
  The no-media panel hides the cover-source row and all media-dependent
  controls while keeping Card width and cancel/confirm actions.
- The normal appearance preview path still uses `notifyItemChanged`, so
  selecting None rebinds the holder and reruns `applyCardContentGeometry()`,
  clearing any stale side-media content widths/orientation.

Verification:
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606041447` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Hidden media attachment count row alignment

- User reported that the hidden image/video count row's bottom spacing looked
  large and that its icon was wider than the audio attachment-count icon, which
  shifted the image/video count text to the right relative to audio count text.
- Confirmed the audio-only card uses zero row bottom padding plus the standard
  `view_thing_padding_bottom` spacer (`THING_CARD_DEFAULT_PADDING_BOTTOM_DP`,
  16dp). Hidden image/video count should keep that same strategy.
- Confirmed icon intrinsic sizes differ substantially: `card_audio_attachment`
  is about `11.5x13dp`, while `card_image_attachment_count` is about
  `17x13.5dp`.
- Added fixed shared count-icon view dimensions for inline image/video and
  audio count rows: `12x14dp` normal and `14x16dp` large, with the existing
  `1dp` large-state top margin.
- Set the inline image/video count icon to `fitCenter`, so the wider PNG scales
  inside the shared icon view instead of shifting the text start.
- Explicitly resets hidden-media bottom spacer height to the default 16dp when
  the inline count row is visible.

Verification:
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606041501` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Hidden media count icon scale adjustment

- User rejected `centerCrop` for the hidden image/video count icon because it
  clipped the image-count indicator.
- Reverted the inline image/video count icon and runtime shared count-icon
  helper to `fitCenter`.
- Increased only the icon view width by 2dp as requested: normal count icons
  are now `14x14dp` and large count icons are now `16x16dp`; heights stayed at
  14dp and 16dp respectively.
- Kept the previous full-span sparse minimum-height skip for visible hidden
  media count rows.

Verification:
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings before publish.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606041520` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-14 - Home card appearance entry and search-back polish

- User requested that completed Things on the home screen should not expose the
  `Customize card appearance` action after long-press selection.
- Updated `ModeManager.canCustomizeSelectedThingCardAppearance()` so
  `Thing.FINISHED` is ineligible even if it is the only selected Thing and
  otherwise passes the existing doing/private checks.
- User also requested that pressing Back from the Thing Card Appearance panel
  while in search should return to the search context instead of exiting search.
- Changed the panel cancel/back path to keep selecting mode when
  `App.isSearching` is true. Outside search, closing the panel still exits
  selecting mode as before.
- Verification: `git diff --check` passed with only the repository's existing
  LF/CRLF warnings, `:app:assembleDebug` passed after the French string escape
  correction, and debug update `202606141538` was published.

## 2026-06-14 - Precise crop dialog spacing alignment

- User asked whether the home Thing Card Appearance precise crop dialog and the
  Detail attachment appearance dialog use the same class. Confirmed they are
  separate generated dialogs in `ThingsActivity` and `DetailActivity`, but
  share the underlying image/video crop editor views.
- Aligned the home precise crop dialog spacing with the Detail dialog: title
  start/end/top margins `24dp`, content horizontal margin `24dp`, preview top
  margin `6dp`, video-frame-controls top margin `6dp`, ratio-controls top
  margin `6dp`, and action-row top margin `16dp`.
- Changed the precise crop ratio label from secondary text colour to App Chrome
  hint text colour, matching the Detail width prompt and remaining resource-
  driven for light/dark mode.
- Added a video-specific precise-crop ratio label resource so video sources can
  show `封面视频比例` instead of image-specific copy.
- Verification: `git diff --check` passed with CRLF warnings only, and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  completed successfully.
- Published debug update `202606150235` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.
- Published debug update `202606141559` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.

## 2026-06-15 - Shared media crop DialogFragment refactor

- User pointed out that the home Thing Card precise crop editor should follow
  the project preference for custom `DialogFragment` implementations rather
  than using a raw Activity-created `android.app.Dialog`.
- Replaced the initial multi-wrapper idea with a single
  `MediaCropAppearanceDialogFragment` shared by Thing Card precise crop and
  Detail attachment appearance.
- Kept the Thing Card Appearance draft, crop, video-frame, ratio, and visible
  preview update logic in `ThingsActivity`, while moving the dialog lifecycle,
  tag, width, and cleanup boundary into the shared custom DialogFragment.
- Verification: `git diff --check` passed with CRLF warnings only, and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  completed successfully.
