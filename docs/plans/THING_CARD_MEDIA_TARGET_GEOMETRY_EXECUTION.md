# Thing Card Media Target Geometry Execution Checklist

## Purpose

Track the implementation of the per-source, per-presentation target ratio and
crop model described in `THING_CARD_MEDIA_TARGET_GEOMETRY_PLAN.md` and
`docs/adr/0003-thing-card-media-target-presentation-geometry.md`.

## Phase 0 - Preflight

- [x] Re-read `CONTEXT.md`, ADR 0003, and the geometry plan.
- [x] Inspect `ThingCardAppearance.kt` JSON parsing and compatibility paths.
- [x] Inspect `ThingsActivity.kt` panel binding, ratio slider, side-width
      slider, background-height slider, crop dialog, and confirmation path.
- [x] Inspect `BaseThingsAdapter.kt` target-size and crop rendering paths for
      top/bottom, side, and media background presentations.
- [x] Inspect `RemoteThingCardMediaRenderer.kt` and `AppWidgetHelper.kt` remote
      projection logic, especially side media and media backgrounds.
- [x] Capture the current legacy field names and default values before editing.

## Phase 1 - Model

- [x] Add a `MediaPresentation` enum/string key model for `thumbnail`,
      `sidePanel`, and `mediaBackground`.
- [x] Add a common crop value object that stores `centerX`, `centerY`, and
      `scale`.
- [x] Add a presentation value object that stores `targetAspectRatio`, optional
      crop, and media-background-only `maskStrength`.
- [x] Add `presentations` to `ThingCardAppearance.SourceAppearance`.
- [x] Keep `videoFrameMs`, `fileSize`, and `lastModified` at source level.
- [x] Keep legacy fields readable during parsing.
- [x] Ensure new serialization writes the nested `presentations` object and
      omits migrated legacy geometry fields after confirmation.
- [x] Update equality / presentation comparison logic so appearance-only updates
      still work.

## Phase 2 - Legacy Migration

- [x] Map legacy thumbnail ratio/crop into `thumbnail` presentation.
- [x] Map legacy background crop/height/mask into `mediaBackground`
      presentation.
- [x] Lazily derive `sidePanel` presentation from legacy `sideMediaWidthPercent`
      when a side presentation is first needed.
- [x] Drop `sideMediaDisplayAspectRatioHint` from the new serialized JSON.
- [x] Preserve old-data rendering when the user never opens or confirms the
      appearance editor.
- [x] Add malformed/partial JSON guards for mixed old/new source records.

## Phase 3 - Draft And Seeding

- [x] Track which presentation entries existed before editing.
- [x] Track which presentation entries were touched or seeded during the current
      edit session.
- [x] On presentation switch, restore an existing draft entry exactly.
- [x] If the target presentation has no draft entry, seed from the previous
      presentation without mutating the source entry.
- [x] Clamp seeded target ratio to the target presentation's active min/max.
- [x] Preserve seeded crop center.
- [x] Preserve seeded crop zoom unless cover rendering requires raising it.
- [x] Cancel discards all seeded/touched draft changes.
- [x] Confirm saves only existing, migrated, touched, or seeded presentation
      entries.

## Phase 4 - Ratio Slider UI

- [x] Replace side-width UI binding with target-ratio binding for `sidePanel`.
- [x] Add a side-panel-only cover image width slider under the target-ratio
      slider. It displays projected media width as a percent of card width, but
      still writes `sidePanel.targetAspectRatio` as the canonical value.
- [x] Replace background-height UI binding with target-ratio binding for
      `mediaBackground`.
- [x] Keep the common ratio slider/ticks visual style.
- [x] Compute top/bottom ratio min/max from thumbnail height guardrails.
- [x] Compute side-panel ratio min/max from side-width guardrails and bounded
      content measurements instead of the live side-media View height.
- [x] Compute media-background ratio min/max from natural content height and
      background height guardrails.
- [x] Show only preset ticks that are reachable inside the current range.
- [x] Keep endpoint labels limited to existing preset ticks. Dynamic min/max
      endpoints are intentionally unlabeled when they are not common ratios.
- [x] Retint ticks, active track, thumb, and labels from the current Thing
      Background as existing ratio controls do.
- [x] Avoid per-progress full adapter rebinds; keep existing coalesced preview
      refresh behavior.

## Phase 5 - Home Card Rendering

- [x] Render top/bottom media from `thumbnail.targetAspectRatio` and
      `thumbnail.crop`.
- [x] Render left/right media from `sidePanel.targetAspectRatio` and
      `sidePanel.crop`.
- [x] Render media backgrounds from `mediaBackground.targetAspectRatio`,
      `mediaBackground.crop`, and `mediaBackground.maskStrength`.
- [x] Implement side-panel projection so desired ratio is tried first and
      existing side-width/readability guardrails clamp only when needed.
- [x] Ensure side-panel projection does not rewrite saved ratio after content
      remeasurement.
- [x] Ensure media-background projection uses natural content height as minimum.
- [x] Ensure crop matrix code takes the active presentation crop, not a shared
      thumbnail/background legacy object.

## Phase 6 - Precise Crop

- [x] Open crop editor with the active presentation target ratio.
- [x] Show the crop editor ratio slider for every presentation mode. For video
      sources, show video-frame controls first and the ratio slider below them.
- [x] Persist crop edits into the active presentation entry in the draft.
- [x] Ensure thumbnail, side-panel, and media-background crop edits do not
      overwrite each other.
- [x] Ensure video-frame changes remain source-level and do not duplicate across
      presentations.
- [x] Verify seeded crop values are a starting point only and do not mutate the
      source presentation.

## Phase 7 - AppWidget Projection

- [x] Remove reads/writes of `sideMediaDisplayAspectRatioHint`.
- [x] Update `RemoteThingCardMediaRenderer` to accept explicit presentation
      target ratio/crop inputs or resolve them from the new model.
- [x] Update single-Thing AppWidget side media to project from
      `sidePanel.targetAspectRatio`.
- [x] Update Things List widget side media to project from
      `sidePanel.targetAspectRatio`.
- [x] Update AppWidget top/bottom media to read `thumbnail` presentation.
- [x] Update AppWidget media backgrounds to read `mediaBackground` presentation.
- [x] Keep Things List widget media-background bitmaps under a collection-row
      pixel budget and degrade to a normal widget background if rendering fails.
- [x] Make Things List widget media-background rows reserve the projected
      media-background target height so pre-rendered background bitmaps are not
      stretched into shorter content-driven rows.
- [x] Preserve RemoteViews bitmap safety caps and per-row degradation.
- [x] Add debug-friendly clamp reason boundaries where practical.

## Phase 8 - Cleanup

- [x] Remove old side-width string resources and replace with unified ratio
      wording where appropriate.
- [x] Remove obsolete background-height row wiring once ratio UI replaces it.
- [x] Remove legacy model writer fields that should no longer serialize.
- [x] Update docs/plans/THING_CARD_APPEARANCE_PLAN.md if it still describes the
      old geometry as canonical.
- [x] Update debug update notes before publishing any debug build.

## Verification

- [x] `git diff --check`
- [x] `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
- [ ] Old JSON with only top/bottom thumbnail settings renders safely.
- [ ] Old JSON with side media width renders safely before confirmation.
- [ ] Old JSON with media background height/mask/crop renders safely before
      confirmation.
- [ ] Confirming an edit rewrites JSON into nested presentations.
- [ ] Confirming an edit removes legacy geometry fields from new JSON.
- [ ] Top/bottom ratio slider has correct endpoint range and ticks.
- [ ] Side-panel ratio slider has correct endpoint range and ticks.
- [ ] Media-background ratio slider has correct endpoint range and ticks.
- [ ] Switching presentations seeds missing values and preserves source state.
- [ ] Cancelling restores exact pre-edit card appearance.
- [ ] Single-Thing 4x4 widget left/right media respects saved target ratio
      projection and crop within guardrails.
- [ ] Things List widget side media respects saved target ratio projection and
      crop within guardrails.
