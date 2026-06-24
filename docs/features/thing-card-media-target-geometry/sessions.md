# Thing Card Media Target Geometry Sessions

Migrated from global `memory/sessions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-06-24 - 列表 Widget 网格媒体背景宽度修正

- 修正 Things List Widget 网格模式中普通宽度卡片的媒体投影：`AppWidgetHelper.createRemoteViewsForThingsListGridRow` 现在按 slot 实际内容宽度向下传递 `contentWidthOverride`，媒体背景、顶部/底部缩略图和 side media 都使用该宽度计算目标尺寸。
- 全宽卡片仍按整行宽度投影；单个记事 Widget、列表 Widget 整行模式和持久化的 Thing Card Appearance 均不受影响。
- 同次 debug update 还包含单个记事 Widget 配置隐藏空文件夹、Detail 单媒体 full-span 默认比例改为 `4:3`。
- 已运行 `:app:assembleDebug --console=plain --no-configuration-cache`，结果 `BUILD SUCCESSFUL`；随后通过 `:app:publishDebugUpdate -PdebugUpdateNotesFile=docs/features/thing-card-media-target-geometry/debug-updates/update-20260624211357.md --console=plain --no-configuration-cache` 发布到阿里云 debug 通道，更新码 `202606241314`，远端 `latest.json` 为 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。

## 2026-06-17 - Replace local ImageView matrix crops with baked media bitmaps

- After the Folder thumbnail top/bottom video issue was fixed by baking a
  target-sized cropped bitmap, the user asked to remove the remaining local
  `ImageView.imageMatrix` crop display dependencies and use pre-cropped
  bitmaps everywhere practical.
- Added shared `MediaCropBitmapRenderer` for offscreen target-sized crop
  rendering. The helper centralizes crop center, user zoom, source/target
  aspect-ratio normalization, drawable-to-bitmap conversion, and final Canvas
  drawing.
- Migrated local Thing Card foreground thumbnails, side-panel media, and media
  backgrounds in `BaseThingsAdapter` to bake the final crop in Glide's
  `onResourceReady` callback. The `ImageView` now receives a target-sized
  bitmap and is reset to `CENTER_CROP`; replay no longer computes or writes an
  `ImageView` matrix.
- Folded the previous Folder-preview-only video bake hook into the default
  Thing Card media path. Folder preview replay now checks whether the current
  load key matches the final measured target geometry and reloads/re-bakes when
  it does not.
- Extended media load/cache keys with the crop fingerprint so changed crop
  center, user scale, source aspect ratio, target size, or video frame time
  cannot reuse a stale bitmap.
- Updated `RemoteThingCardMediaRenderer` to reuse the shared renderer instead
  of carrying a duplicate crop calculation.
- Verified with `git diff --check`, then reran
  `.\gradlew.bat :app:assembleDebug --console=plain` outside the sandbox after
  the in-sandbox run was blocked by `.gradle/configuration-cache.lock`.
- Published debug update `202606171256` to the Aliyun debug channel and
  verified remote `latest.json` points to
  `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606171256.apk`.

## 2026-06-07 - Home-list Thing Card media bitmap reuse cache

- User reported that home-list Thing Cards with loaded images can show an empty
  media region again after scrolling away and back, especially after the recent
  multi-ratio cover-image work.
- Reviewed the supplied code review and confirmed the high-risk path:
  `BaseThingsAdapter.loadThingCardImage()` clears a recycled `ImageView` before
  Glide refills it, so a previously loaded card can still flash blank while the
  new target waits behind Glide's request lifecycle.
- Kept the existing `dontTransform()` requests because final Thing Card media
  crop is owned by the app's matrix renderer; removing it would break custom
  crop center / zoom behavior.
- Added an adapter-scoped, bounded `LruCache<String, Bitmap>` for Thing Card
  media bitmaps. The cache key includes source path, file size, last-modified
  time, target width/height, and video frame time; media backgrounds add a
  `background:` prefix.
- Updated foreground thumbnails and media-background loads to synchronously
  refill from that adapter cache before clearing/restarting Glide. Cache misses
  keep the existing same-source placeholder path and normal Glide loading.
- Left `ThingsActivity`'s scroll-time `pauseRequests()` behavior unchanged for
  this first pass because the target fix is repeat display after a successful
  load, not first-load preloading.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Attempted `:app:publishDebugUpdate`, but the escalation reviewer rejected the
  external upload because this turn did not explicitly request publishing.
- After the user explicitly requested publishing, published debug update
  `202606071039` with
  `.\gradlew.bat :app:publishDebugUpdate
  "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain
  --no-configuration-cache`.

## 2026-06-06 - Suppress appearing animation during appearance preview after rotation

- User reported that opening the Thing Card Appearance panel and adjusting media
  ratio normally does not play `thingsAppearingAnimation`, but after rotating
  the screen, adjusting the same card makes only that card play the upward
  appearing animation.
- Diagnosed the cause in `ThingsActivity.onConfigurationChanged()`: rotation
  reset `shouldThingsAnimWhenAppearing` to `true`, and subsequent live-preview
  changes call `notifyItemChanged()` only for the selected appearance-preview
  card.
- Kept the existing rotation behavior for normal list use, but when the
  appearance panel is already showing, configuration changes leave the adapter
  appearing-animation flag disabled.
- Added a second guard in `refreshThingCardAppearancePreviewNow()` so every
  appearance preview rebind disables list appearing animation before notifying
  the selected item.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606061322` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
  --console=plain --no-configuration-cache`.

## 2026-06-06 - Tablet width cap for appearance panel and crop dialog

- User reported that the Thing Card Appearance UI and precise cover crop dialog
  use too much horizontal space on tablets when they expand to match-parent
  width.
- Added `thing_card_appearance_max_width` at `480dp`.
- Constrained the bottom appearance panel to `min(available width, 480dp)`,
  centered it at the bottom, and recompute its width when opened or when
  configuration changes.
- Constrained the precise crop editor dialog to the same width cap and reused
  the constrained width for crop-preview height calculation.

## 2026-06-06 - Cover preview flicker fix

- Implemented the ordinary thumbnail / side-panel cover-image half of the
  previous flicker analysis.
- Updated `BaseThingsAdapter.loadThingCardImage()` so same-source target-size
  changes reuse the current drawable immediately, reapply the current crop
  matrix for the new target size, and pass that drawable to Glide as a
  placeholder instead of clearing the `ImageView` first.
- Hid the loading spinner during same-source placeholder reuse so rapid slider
  changes keep showing the existing cover image instead of swapping to progress
  chrome for every new target size.
- Kept the existing exact-load-key fast path and current render-request tag
  guard, so identical requests still avoid new Glide work and stale Glide
  callbacks still apply only the latest crop/size request.
- Strengthened side-panel post-measure bind tokens with the projected cover
  width/height, crop, and video frame. This prevents older same-source posted
  corrections from issuing a second image load after a newer slider tick has
  rebound the card.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain
  --no-configuration-cache`; the first sandboxed run failed while compiling the
  `swirl` module because Javac could not create output directories, and the
  elevated rerun passed.
- Attempted to run `:app:publishDebugUpdate` after updating
  `memory/debug-update-notes.md`, but the escalation reviewer rejected the
  external upload because it would publish APK, metadata, and notes outside the
  sandbox. Publishing is waiting for explicit user approval after that risk is
  stated.
- After the user explicitly approved the external upload, published debug
  update `202606060912` with
  `.\gradlew.bat :app:publishDebugUpdate
  "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain
  --no-configuration-cache` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-06 - Cover preview flicker analysis

- User reported that dragging cover-image ratio or side-panel cover width in
  the Thing Card Appearance panel can make the live preview cover image flicker
  and briefly expose the underlying Thing Background.
- Read the appearance/media-target feature docs, ADR 0002, and the current
  `ThingsActivity` / `BaseThingsAdapter` preview and media-loading paths.
- Found that ordinary cover thumbnails still clear the current Glide target
  whenever the size-based load key changes. Ratio and side-width dragging
  continuously changes `override(width, height)`, so `loadThingCardImage()`
  clears the `ImageView` before the new drawable and crop matrix are ready.
- Compared this with media-background loading, which already reuses the current
  same-source drawable as a placeholder during target-size changes. That
  mitigation was not applied to ordinary thumbnail / side-panel cover images.
- Noted an additional side-panel risk: the post-measure side-image bind token
  only contains Thing id, placement, and media source, so same-source width or
  target-ratio edits do not invalidate older posted correction work.
- No business code was changed in this analysis pass.

## 2026-06-04 - Thing Card Appearance crop, ratio slider, and background-height fixes

- Read the Thing Card Appearance plan, execution checklist, ADR, memory files,
  and toolchain rules before changing code.
- Fixed the cover-image ratio tick layout by drawing ticks inside the slider
  region and alternating dense labels above and below the track, avoiding
  collisions around 1:2 / 9:16 and 16:9 / 2:1.
- Changed both the bottom-panel ratio slider and the precise-crop ratio slider
  to snap during drag when close to a reachable preset ratio, with release-time
  normalization still in place.
- Made the precise crop editor apply EXIF orientation to decoded image previews
  through `BitmapUtil.tryToGetRotatedBitmap()`, aligning the crop UI with the
  final Glide-rendered card orientation.
- Changed media-background crop target ratio calculation to use the current
  draft's card width, background height ratio, available-height cap, and natural
  content height instead of trusting a potentially stale `CardView.height`.
- Added `view_thing_media_background_height_target` to `card_thing.xml` and
  changed `BaseThingsAdapter` so media-background card height is driven by that
  transparent card-level target view while the background image and mask remain
  `match_parent`.
- Moved media-background loading/crop application to a pre-draw path guarded by
  the current media-background bind token, so image matrices use the final root
  card size after the current layout pass.
- Added an item-animator idle guard before the deferred appearance-preview
  `notifyItemChanged`, reducing high-frequency slider layout conflicts without
  catching or hiding RecyclerView framework exceptions.
- Updated `docs/plans/THING_CARD_APPEARANCE_PLAN.md`,
  `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md`, `memory/decisions.md`, and
  `memory/debug-update-notes.md` with the bug-fix decisions and publish notes.

Verification:
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- Source guards confirmed there is no new catch for
  `Called attach on a child which is not detached`, both ratio sliders use the
  drag-time snap helper, and media-background height is driven by the new
  transparent target view.

Publish:
- Ran
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain`.
- Publish passed and uploaded debug update `202606032319` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance video crop frame controls

- User asked for the Thing Card Appearance panel title to follow the current
  Thing Background as pure-colour or gradient text and to have more top spacing.
- User also asked to make video sources use "Crop cover video" /
  "裁切封面视频", move video frame selection from the bottom appearance panel
  into the precise crop dialog, and provide playback/pause, stop, and frame
  slider controls below the crop preview.
- Increased `panel_thing_card_appearance.xml` top padding from 16dp to 22dp and
  applied `applyThingCardAppearanceAccentText(...)` to the panel title.
- Hid the old bottom-panel video frame block and stopped showing it during panel
  binding.
- Added video-specific crop strings in default English and Simplified Chinese,
  and switched both the panel crop action and crop dialog title based on the
  current media source type.
- Added `ThingCardCropEditorView.setSourceBitmap(...)` so the crop editor can
  swap video frames while preserving crop center, scale, and target ratio.
- Added play/pause/stop vector icons and dialog-local video frame controls:
  circular App Chrome ripple icon buttons, adaptive icon tint, and an accent
  SeekBar. Frame decoding runs on a single executor with sequence guards so stale
  decoded frames cannot overwrite the newest slider/playback position.
- Confirming the crop dialog now saves `videoFrameMs` together with crop values
  in one source appearance update; cancelling releases the retriever/executor and
  leaves the draft unchanged.
- Updated `docs/plans/THING_CARD_APPEARANCE_PLAN.md`,
  `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md`, `memory/decisions.md`, and
  `memory/debug-update-notes.md`.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guard confirmed the title accent call, video crop labels, dialog video
  frame controller, `setSourceBitmap(...)`, play/pause/stop icons, and
  `videoFrameMs` save path.
- No device visual smoke test was run in this agent session.
- Published debug update `202606041207` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-04 - Thing Card Appearance real video crop playback

- User rejected the previous video crop preview because `22dp` top padding felt
  arbitrary and the dialog did not truly play the selected video; the preview
  frame did not move.
- Changed `panel_thing_card_appearance.xml` top padding from 22dp to 24dp.
- Added `ThingCardCropEditorController` so the bitmap crop editor and the new
  video crop editor share crop center, user scale, and target-ratio APIs.
- Added `ThingCardVideoCropEditorView`, a real `TextureView` + `MediaPlayer`
  preview surface with the existing rounded crop overlay and pan/zoom gesture
  model. The view applies the same cover-scale, crop-center, and user-scale
  transform formula used by final Thing Card rendering.
- Rewired `ThingsActivity.openThingCardCropEditor()` so image sources continue
  to use `ThingCardCropEditorView`, while video sources use
  `ThingCardVideoCropEditorView`.
- Replaced the previous `MediaMetadataRetriever` executor-based simulated
  playback loop in dialog controls with real `MediaPlayer.start()`, `pause()`,
  stop-to-zero, and slider seek control.
- Kept `MediaMetadataRetriever` only for reading one initial video frame before
  the dialog opens, so the preview height can still be sized from the media
  aspect ratio.
- Added `currentFrameMs` tracking inside the video crop view so confirming
  immediately after slider seek saves the user's selected time even if
  `MediaPlayer.seekTo()` has not completed asynchronously.
- Updated `docs/plans/THING_CARD_APPEARANCE_PLAN.md`,
  `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md`,
  `memory/decisions.md`, and `memory/debug-update-notes.md`.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- Source guards confirmed the 24dp padding, `ThingCardVideoCropEditorView`,
  `MediaPlayer` playback path, and `currentFrameMs` seek-save guard.
- No device visual smoke test was run in this agent session.
- Published debug update `202606041226` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-05 - Thing Card media target geometry planning

- User invoked `grill-with-docs` to stress-test replacing the Thing Card
  Appearance side image width and media-background card height controls with the
  same ratio-style UI used by cover image ratio.
- Resolved terminology in `CONTEXT.md`: the canonical concept is
  **Thing Card Media Target Aspect Ratio**, not crop ratio, side width percent,
  or card height percent. Target ratio determines the media target shape; crop
  determines crop center and user zoom inside that target.
- Resolved that target ratio and crop are stored per media source and per media
  presentation: foreground thumbnail, side panel, and media background.
- Resolved that presentation seeding is non-destructive: switching to a
  presentation without saved values may seed from the previous presentation and
  clamp to the new presentation's guardrails, but switching back before confirm
  restores the previous presentation's exact draft state.
- Resolved that confirmation is the only durable write point. Confirmed saves
  should normalise JSON into nested presentation entries, omit migrated legacy
  geometry fields, and save only existing/migrated/touched/seeded presentation
  entries.
- Resolved that `sideMediaDisplayAspectRatioHint` should be removed because the
  new target ratio becomes the canonical value for AppWidget projection.
- Committed the already implemented remote AppWidget/notification appearance
  work before starting the new model implementation:
  `de8bdd6 Port Thing Card Appearance to AppWidgets and notifications / 将记事卡片外观移植到小组件和通知`.
- Created `docs/adr/0003-thing-card-media-target-presentation-geometry.md`.
- Created `docs/plans/THING_CARD_MEDIA_TARGET_GEOMETRY_PLAN.md`.
- Created `docs/plans/THING_CARD_MEDIA_TARGET_GEOMETRY_EXECUTION.md`.
- Ran `git diff --check` for the new docs and related context/memory files; it
  passed with only the repository's existing LF/CRLF warnings.

## 2026-06-06 - AppWidget media clamp reason logs

- Added structured debug logs around AppWidget media projection guardrails in
  `AppWidgetHelper`.
- Media-background logs now distinguish content-width bitmap dimension limits,
  list media-background target-height floors/caps, list media-background bitmap
  dimension caps, and list media-background pixel-budget caps.
- Side-panel logs now distinguish invalid/normalised target ratios, min/max
  side-media width guardrails, and best-effort list side-media projection.
- Marked the execution checklist item for debug-friendly clamp reason
  boundaries as complete.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606060201` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
  --console=plain --no-configuration-cache`.

## 2026-06-06 - Cover image width string resource cleanup

- Renamed the Thing Card Appearance cover-width string resource from the legacy
  `thing_card_appearance_side_width_format` key to
  `thing_card_appearance_cover_image_width_format` across all locale resource
  files.
- Updated `ThingsActivity` to reference the new string resource key.
- Marked the Phase 8 execution checklist item for removing old side-width
  string resources as complete.

## 2026-06-06 - Things List media-background row height projection

- User reported that the same media-background Thing preserved crop center in a
  4x2 single-Thing AppWidget but appeared stretched in a Things List AppWidget
  row.
- Diagnosed the cause as a surface mismatch: single-Thing widgets render the
  media-background bitmap to the fixed widget surface, while Things List rows
  previously rendered a ratio-projected bitmap but let the actual row height be
  driven only by text content. The row then compressed the bitmap through
  `fitXY`.
- Updated `AppWidgetHelper` so Things List media-background rows compute an
  effective row target height from the saved media-background target ratio and
  estimated natural content height, then set that as the row root
  `minimumHeight` through RemoteViews.
- Kept the list media-background bitmap pixel-budget clamp. The pre-rendered
  bitmap is still downscaled for RemoteViews safety, but keeps the same target
  aspect ratio as the reserved row surface.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606060130` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
  --console=plain --no-configuration-cache`.

## 2026-06-04 - Thing Card Appearance slider gradient and inactive track fix

- User asked whether sliders could support gradients and confirmed the first
  version can keep the thumb on the representative color while making the active
  track gradient-capable.
- User also reported that inactive tracks were effectively invisible in both
  light and dark modes.
- Diagnosed `DisplayUtil.setSeekBarColor(...)`: it only applied
  `progressTintList` and thumb color, leaving inactive/background track rendering
  to the platform/theme default.
- Reworked `DisplayUtil.setSeekBarColor(...)` to delegate to a new
  `setSeekBarBackground(...)` helper that owns the whole `progressDrawable`.
  It builds a `LayerDrawable` with App Chrome hint color for inactive track and
  a `ClipDrawable` progress layer for active track.
- The active track uses a solid `GradientDrawable` color for pure
  `ThingBackground`s and a same-orientation gradient for gradient
  `ThingBackground`s. The thumb remains tinted to `representativeColor()`.
- Updated the Thing Card Appearance panel sliders and the crop dialog ratio
  slider to pass the full selected `ThingBackground`; old single-color callers
  still call `setSeekBarColor(...)` and now also get a visible inactive track.
- Updated plan docs, decisions, and debug update notes.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- Source guard confirmed `DisplayUtil` now constructs `LayerDrawable` /
  `ClipDrawable`, uses `app_chrome_on_surface_hint` for inactive track, and the
  Thing Card Appearance panel/crop-dialog sliders call `setSeekBarBackground(...)`.
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- No device visual smoke test was run in this agent session.
- Published debug update `202606041009` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  to `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-05 - Things List widget side media geometry fix

- User provided a root-cause analysis for left/right media stretching in Things
  List widgets:
  - list widget rows were still using an old 320dp fallback width in some
    cases, so `sideMediaWidthPercent` could become too large for smaller
    widgets;
  - `getThingsListWidgetSideMediaSlotTargetHeight()` fell back to thumbnail
    source aspect ratio, which is appropriate for top/bottom thumbnails but not
    for full-height side panels;
  - side `ImageView`s used `fitXY`, so any bitmap/layout mismatch became
    non-uniform media stretching.
- Adjusted the diagnosis slightly: `RemoteViewsFactory.getViewAt()` cannot
  access the parent collection row's measured width directly, so the
  implementation resolves the concrete list widget provider from `appWidgetId`
  and uses launcher options first, then provider preset defaults as the width
  fallback.
- Updated `AppWidgetHelper.kt`:
  - resolves the actual Things List provider class in
    `createRemoteViewsForThingsListItem()` instead of hardcoding
    `ThingsListWidget`;
  - derives default widget width/height from both single-Thing and Things List
    provider cell spans;
  - replaces the Things List side-media no-hint height fallback with a content
    projection estimate based on title/content/checklist/audio/reminder/habit/
    state rows, then clamps only to the RemoteViews hard bitmap safety cap;
  - keeps the saved side-media display aspect hint path when it exists.
- Updated `app_widget_item_thing.xml` and `app_widget_thing.xml` so left/right
  side media `ImageView`s use `centerCrop` instead of `fitXY`.
- Recorded the side-media fallback geometry decision in `memory/decisions.md`.

Verification:
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- The first sandboxed
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  attempt failed because the sandbox blocked Kotlin daemon temp-file access
  under `C:\Users\ywwynm\AppData\Local\kotlin\daemon`.
- Re-ran the same assemble command with elevated permissions. It passed.

Publish:
- Ran
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  with elevated permissions. It first published debug update `202606051259`.
- Corrected `memory/debug-update-notes.md` to remove a misleading pending
  publish line, then re-ran the same publish task. It passed and published
  debug update `202606051300` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-05 - Thing Card media target geometry implementation

- Implemented `ThingCardAppearance` version 2 nested media presentations:
  `thumbnail`, `sidePanel`, and `mediaBackground`, each with target aspect ratio
  and optional crop; media background also carries mask strength.
- Kept legacy JSON fields readable, but new serialization omits migrated
  geometry writer fields including `sideMediaWidthPercent`,
  `thumbnailCrop.sourceAspectRatio`, `mediaBackgroundHeightRatio`,
  `backgroundCrop`, `mediaBackgroundMaskStrength`, and
  `sideMediaDisplayAspectRatioHint`.
- Updated the Thing Card Appearance panel so the existing ratio slider binds to
  the active presentation. The old side-width row is hidden, the old background
  height row is hidden, and background mask remains available.
- Added non-destructive presentation seeding when switching modes. Missing
  target presentations seed from the previous presentation and clamp to the new
  presentation's range without mutating the source presentation.
- Updated home-card, remote renderer, and AppWidget media projection to read
  presentation target ratios and crops. Single-Thing and Things List AppWidget
  side media no longer use `sideMediaDisplayAspectRatioHint`.
- Updated docs to mark old side-width/background-height geometry plans as
  superseded by ADR 0003 and the media target geometry plan.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain
  --no-configuration-cache` and `git diff --check`; both passed, with only
  existing LF/CRLF warnings from Git.

## 2026-06-05 - Thing Card media target geometry review fixes and debug publish

- Fixed the review finding where confirmation called transition seeding with
  the same draft as both source and target. Confirmation now materializes the
  active presentation and legacy side-panel presentation explicitly.
- Fixed first-time side-panel seeding for upgraded users by preserving legacy
  `sideMediaWidthPercent` as a `sidePanel.targetAspectRatio` before new JSON
  drops the legacy field.
- Renamed the misleading media-background helper from
  `getThingCardMediaBackgroundTargetMinHeight` to
  `getThingCardMediaBackgroundClampedTargetHeight` and clarified the effective
  height parameter naming.
- Verified the fixes with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606051514` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
  --console=plain --no-configuration-cache`.

## 2026-06-05 - Side-panel target-ratio projection stabilization

- Diagnosed the side-panel feedback loop where changing
  `sidePanel.targetAspectRatio` changes media width, which changes text column
  width and wrapping, which changes card height and then media width again.
- Updated `BaseThingsAdapter` so side-panel home cards calculate a deterministic
  projection containing media width, content width, and media height, then apply
  all three together during initial layout and post-measure correction.
- Removed the old side-image height cache and `getSideImageWidth()` height
  back-projection path so side-panel rendering no longer depends on the current
  live side-media View height.
- Updated `ThingsActivity` so side-panel ratio slider ranges are derived from
  side-width guardrails plus measured content height at the min/max widths.
  Main-panel and crop-dialog ratio sliders now freeze their active range while
  dragging to avoid tick/progress remapping during preview refreshes.
- Updated `AppWidgetHelper` so Things List widget side media uses a finite
  projection loop over the existing RemoteViews row-height estimator.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606051547` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
  --console=plain --no-configuration-cache`.

## 2026-06-05 - Side-panel cover image width slider

- Added a side-panel-only "cover image width" control under the target-ratio
  slider in the Thing Card Appearance panel.
- Reused the old side-width row and seekbar, but changed its behavior so it no
  longer writes legacy `sideMediaWidthPercent`. Width changes are converted
  through the side-panel projection into `sidePanel.targetAspectRatio`.
- Added `ThingsActivity` side-panel projection helpers so the ratio slider and
  width slider can update each other from the same bounded content measurement.
- Kept slider dragging stable by sharing the frozen active ratio range while
  either side-panel slider is being dragged.
- Updated side-width strings from side-image wording to cover-image width
  wording across available locale resource files.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606051559` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
  --console=plain --no-configuration-cache`.

## 2026-06-06 - Crop dialog ratio slider and list-widget background bitmap guard

- Updated `ThingsActivity` crop editor so the ratio slider is shown for every
  active presentation, including `sidePanel` and `mediaBackground`.
- Preserved video crop dialog ordering: video-frame controls appear first, then
  the target-ratio slider below them.
- Removed the old crop-editor `canResizeThingCardCropEditorFrame()` gate and
  now always save the active presentation target ratio when confirming crop.
- Diagnosed Things List AppWidget row disappearance after a media-background
  item as likely oversized RemoteViews bitmap pressure: the previous list media
  background cap could produce multi-megabyte per-row bitmaps on high-density
  screens.
- Updated `AppWidgetHelper` to clamp list-widget media-background bitmaps to a
  row pixel budget and to degrade to ordinary widget background rendering on
  `OutOfMemoryError` or render exceptions.
- Added debug logs for media-background clamp/skip decisions so launcher checks
  can confirm whether bitmap limits were hit.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606051623` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
  --console=plain --no-configuration-cache`.

## 2026-06-24 - 修复 DoingActivity 单卡媒体背景过宽

- 排查普通宽度、图片/视频作为背景的正在做记事，从通知、记事列表小组件、单一记事小组件进入 `DoingActivity` 时卡片过宽的问题。
- 确认首页列表入口与通知/小组件入口最终都会打开当前正在做记事的 `DoingActivity`，问题不是缺少宽度类 Intent extra。
- 定位到 `card_thing.xml` 根 `CardView` 为 `wrap_content`，媒体背景和遮罩是直接子 View 且使用 `MATCH_PARENT`，旧逻辑又用已被撑宽的 `CardView.width` 作为图片加载目标宽度，导致普通单卡在首次测量时被背景层撑宽。
- 更新 `BaseThingsAdapter`：单卡媒体背景和遮罩使用内容目标宽度设置尺寸；背景 bitmap 加载、实时裁切刷新也改用内容目标宽度，而不是可能已经异常的卡片实测宽度。
- 已运行 `git diff --check` 和 `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`；本次未使用 adb，也未做设备视觉验证。
- 已发布 debug update `202606241457`，发布日志为 `docs/features/thing-card-media-target-geometry/debug-updates/update-20260624225716.md`。

## 2026-06-24 - 修复 DoingActivity 单卡偶发左贴边

- 排查正在做 Thing 从通知栏或 AppWidget 打开 `DoingActivity` 时，含图片/视频的卡片偶发贴到屏幕最左侧的问题。
- 定位到 `DoingActivity.initUI()` 在设置居中 padding 之前调用 `DisplayUtil.applyBottomInsetAsScrollPadding(mRecyclerView)`；该工具把当时左右 padding 的原始值记为 `0`，后续系统重新分发 window insets 时会清掉 `initRecyclerView()` 后来写入的左右居中 padding。
- 将底部 inset scroll padding 的安装时机移到 `initRecyclerView()` 之后，使工具保存已经计算好的左右居中 padding，并只在底部追加系统栏安全区。
- 本次未使用 adb；设备视觉验证仍需后续手动确认。
- 已运行 `git diff --check`、`.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`，并发布 debug update `202606241507`，发布日志为 `docs/features/thing-card-media-target-geometry/debug-updates/update-20260624230624.md`。
