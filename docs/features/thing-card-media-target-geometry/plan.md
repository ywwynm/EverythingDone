# Thing Card Media Target Geometry Plan

## Goal

Unify Thing Card media geometry around `Thing Card Media Target Aspect Ratio`
and per-presentation crop. This replaces the current split between top/bottom
cover ratio, left/right side width percent, media-background card height, and
AppWidget side-media display hints.

Execution checklist: `execution.md`.

## Confirmed Product Decisions

- User-facing controls should describe one concept: the shape of the media
  target. The existing cover-image ratio slider style becomes the common visual
  control for top/bottom thumbnails, side panels, and media backgrounds.
- The domain term is `Thing Card Media Target Aspect Ratio`. It determines the
  shape of the media target before crop is applied.
- Crop remains separate from target ratio. Crop stores the user's chosen center
  and user zoom inside the selected media target and does not modify the source
  image/video file.
- Target ratio and crop are stored per media source and per media presentation:
  foreground thumbnail, side panel, and media background.
- Media-background mask strength belongs to the media-background presentation.
- `videoFrameMs` remains per media source and is independent from target ratio
  and crop.
- The preferred target ratio should be respected as strongly as possible.
  Surface-specific guardrails exist only to keep Thing Card content readable and
  to stay within platform rendering limits. Guardrails adapt the current
  projection and must not rewrite the saved target ratio.
- Ratio slider min/max values are dynamic. They are derived from the active
  presentation's existing guardrails:
  - top/bottom: thumbnail min/max height constraints;
  - side panel: side-media width constraints and readable content width;
  - media background: background height constraints and natural content height.
- The slider ends represent the active min/max ratio for the current
  presentation. Preset ticks are shown only when they fall inside the active
  range, and endpoint labels should make the current min/max clear.
- Legacy fields are read for compatibility but are removed from newly confirmed
  Thing Card Appearance JSON where possible. This includes
  `sideMediaWidthPercent`, `mediaBackgroundHeightRatio`,
  `thumbnailCrop.sourceAspectRatio`, `sideMediaDisplayAspectRatioHint`, and the
  old flat media-background mask/crop fields after migration into the nested
  presentation entry.
- A confirmed Thing Card Appearance edit is the durable write point. Opening the
  panel, dragging controls, switching presentation, seeding missing values, or
  opening crop UI changes only the in-memory draft until the user confirms.
- Confirmation saves only presentation entries that already existed, were
  migrated from legacy data, or were touched/seeded in the current edit session.
  It should not eagerly write every possible presentation entry for every
  source.
- Presentation seeding is non-destructive. If the user switches to a presentation
  that has no values, seed from the previous presentation where possible, clamp
  the seeded target ratio to the new presentation's guardrails, preserve crop
  center, and raise crop zoom only if needed to keep cover rendering. Switching
  back before confirm restores the previous presentation's exact draft state.

## Proposed JSON Shape

The model remains under `ThingCardAppearance.sources[sourceKey]`, but geometry
is grouped by media presentation.

```json
{
  "version": 2,
  "spanMode": 1,
  "imagePlacement": 3,
  "appearanceUpdateTime": 0,
  "mediaSourceKey": null,
  "mediaBackgroundEnabled": false,
  "sources": {
    "0/path/to/image.jpg": {
      "fileSize": 123456,
      "lastModified": 1710000000000,
      "videoFrameMs": null,
      "presentations": {
        "thumbnail": {
          "targetAspectRatio": 1.7777777778,
          "crop": {
            "centerX": 0.5,
            "centerY": 0.5,
            "scale": 1.0
          }
        },
        "sidePanel": {
          "targetAspectRatio": 0.75,
          "crop": {
            "centerX": 0.5,
            "centerY": 0.5,
            "scale": 1.0
          }
        },
        "mediaBackground": {
          "targetAspectRatio": 1.2,
          "crop": {
            "centerX": 0.5,
            "centerY": 0.5,
            "scale": 1.0
          },
          "maskStrength": 0.45
        }
      }
    }
  }
}
```

## Legacy Mapping

- `thumbnailCrop.sourceAspectRatio` maps to `presentations.thumbnail.targetAspectRatio`.
- `thumbnailCrop.centerX`, `centerY`, and `scale` map to
  `presentations.thumbnail.crop`.
- `backgroundCrop` maps to `presentations.mediaBackground.crop`.
- `mediaBackgroundHeightRatio` maps to
  `presentations.mediaBackground.targetAspectRatio` as
  `targetAspectRatio = 1.0 / mediaBackgroundHeightRatio`.
- `mediaBackgroundMaskStrength` maps to
  `presentations.mediaBackground.maskStrength`.
- `sideMediaWidthPercent` maps lazily into
  `presentations.sidePanel.targetAspectRatio` using the current preview side
  panel projection when available. If measurement is unavailable, fall back to
  the old default side width and the existing side-panel minimum height.
- `sideMediaDisplayAspectRatioHint` is not carried forward. It was a widget
  projection hint; the new target ratio is the canonical source.

## Presentation Projection Rules

### Top And Bottom Thumbnail

- Width comes from the card/media content width.
- Desired height is `width / targetAspectRatio`.
- Existing thumbnail min/max height guardrails derive the active slider range.
- Crop is `presentations.thumbnail.crop`.
- If no saved presentation exists, default target ratio remains span-based:
  `16:9` for full-span cards and `4:3` for normal-span cards.

### Side Panel

- Side panels are full-height media targets next to the text/content column.
- Desired side width is `targetAspectRatio * sidePanelHeight`.
- Existing side-width min/max percentages remain guardrails, not stored user
  geometry.
- Home-card projection may need an iterative or measured pass because side width
  affects text wrapping, and text wrapping affects side-panel height.
- The source presentation state must not be rewritten when this projection is
  clamped.
- Crop is `presentations.sidePanel.crop`.

### Media Background

- Desired background target height is `cardWidth / targetAspectRatio`.
- Natural content height remains the minimum visible card height.
- Existing media-background maximum height remains a guardrail.
- A target ratio that implies a height below natural content height simply
  projects as natural content height for that surface.
- Crop and mask are read from `presentations.mediaBackground`.

## AppWidget Projection Rules

- AppWidgets render a `Thing Card Surface Projection` of the saved target ratio
  and crop. They should not use `sideMediaDisplayAspectRatioHint`.
- Single-Thing AppWidgets project side media from the saved side-panel target
  ratio and the widget height budget, then clamp to side-width and readable
  content guardrails for that fixed widget surface.
- Things List widget rows project side media from the saved side-panel target
  ratio and the row's measured or estimated content height. Rows may grow inside
  the collection view, subject to RemoteViews bitmap and launcher safety caps.
- Top/bottom AppWidget media uses the same target ratio as home cards, then
  applies widget-specific content-floor and bitmap-size clamps.
- Media-background AppWidget rendering uses the saved media-background target
  ratio, then clamps to widget surface size, natural content height, and
  RemoteViews safety limits.
- Clamp reasons should be explicit in implementation code so future debugging
  can distinguish user intent from surface projection limits.

## Editor UX

- Replace the separate side-width slider and card-height slider with the common
  ratio slider visual treatment.
- The UI can keep the user-facing "Cover image ratio" wording if that remains
  clearer, but code and docs should use `Thing Card Media Target Aspect Ratio`.
- The ratio slider binds to the active presentation:
  - top/bottom edits `thumbnail`;
  - left/right edits `sidePanel`;
  - background edits `mediaBackground`.
- Switching presentation restores that presentation's draft values if present.
  If absent, seed from the previous presentation into the target presentation
  only.
- Precise crop opens against the active presentation's target ratio and crop.
- Confirm persists the draft and normalises JSON to the new model. Cancel
  restores the original appearance and discards seeded draft entries.

## Verification Checklist

- [ ] Existing top/bottom cards migrate `thumbnailCrop.sourceAspectRatio` into
      thumbnail target ratio.
- [ ] Existing side cards migrate `sideMediaWidthPercent` into side-panel target
      ratio when the user confirms a new appearance edit.
- [ ] Existing media-background cards migrate background height, crop, and mask
      into the media-background presentation.
- [ ] Confirmed saves omit migrated legacy geometry fields.
- [ ] Switching thumbnail -> media background seeds ratio/crop without mutating
      thumbnail draft state.
- [ ] Switching media background -> side panel seeds ratio/crop and clamps only
      the side-panel copy.
- [ ] Returning to a previous presentation restores its exact previous draft
      state.
- [ ] Cancelling the panel discards all seeded entries.
- [ ] Confirming the panel saves touched/seeded entries only.
- [ ] Ratio slider endpoints reflect top/bottom thumbnail guardrails.
- [ ] Ratio slider endpoints reflect side-panel guardrails.
- [ ] Ratio slider endpoints reflect media-background guardrails.
- [ ] Precise crop uses the active presentation target ratio.
- [ ] AppWidget side media no longer uses `sideMediaDisplayAspectRatioHint`.
- [ ] 4x4 single-Thing widgets with left/right media project from saved target
      ratio and crop, then clamp only when widget guardrails require it.
- [ ] Things List widget rows project from saved target ratio and crop without
      stretching media non-uniformly.
- [ ] Missing, malformed, or legacy JSON remains safe to render.

## Out Of Scope

- Changing the underlying attachment identity scheme.
- Modifying original image/video files.
- Adding a widget-specific appearance setting.
- Reworking notification layouts beyond the standard BigPicture media support
  already implemented.
