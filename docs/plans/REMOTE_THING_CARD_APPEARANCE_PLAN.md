# Remote Thing Card Appearance Plan

## Goal

Port Thing Card Appearance to remote card surfaces:

- Single-Thing AppWidgets.
- Things List AppWidget rows.
- Standard system Thing notifications where the platform style can carry media.

This plan follows `docs/plans/THING_CARD_APPEARANCE_PLAN.md` and
`docs/adr/0002-unified-thing-card-appearance.md`. The previous v1 scope kept
AppWidget image regions and system notification big pictures unchanged; this
plan is the follow-up scope for those remote surfaces.

## Confirmed Product Decisions

- AppWidgets should aim for complete Thing Card Appearance support, even when
  implementation takes longer.
- AppWidgets should preserve existing interaction granularity. Keep structured
  RemoteViews for text, checklist rows, reminder/habit/action regions, Doing
  overlays, and row clicks. Pre-render only the imagery that RemoteViews cannot
  draw natively.
- Whole-card bitmap rendering is only a last-resort fallback.
- Both single-Thing AppWidgets and Things List AppWidget rows should aim for
  complete visual support.
- Things List AppWidget rows may spend more update work to preserve appearance,
  but they must still guard against RemoteViews bitmap/IPC limits.
- If a Things List AppWidget update would exceed RemoteViews limits, degrade per
  row instead of letting the whole widget update fail.
- AppWidgets follow Thing Card Appearance by default. Do not add a separate
  widget-specific "follow card appearance" setting.
- Existing AppWidget normal/simple style remains an information-density choice.
  It does not opt the widget out of Thing Card Appearance where that style still
  shows media.
- Thing Card Span Mode does not map directly to AppWidgets. Widget geometry is
  owned by the widget size class and the launcher's actual allocated size.
- Add launcher-visible AppWidget size presets for media-heavy cards. Existing
  providers remain resizable, but separate provider entries give launcher
  pickers better default shapes up to 6 cells for tablets and large-grid
  launchers. Single-Thing presets cover media-heavy shapes from 4x2 through
  6x6, excluding extra 1xN/Nx1 shapes; Things List presets cover larger list
  shapes from 4x4 through 6x6.
- AppWidget Size Preset labels should include the cell shape. Existing
  single-Thing providers should be relabeled 1x1, 2x2, 3x3, and 4x4; new
  provider labels should use their exact cell shapes. The existing Things List
  provider should be relabeled 3x3, and new Things List provider labels should
  use their exact cell shapes.
- Every AppWidget Size Preset provider should declare both Android 12+
  `targetCellWidth` / `targetCellHeight` and legacy `minWidth` / `minHeight`.
  `targetCell*` owns the intended launcher grid shape on Android 12+, while
  `minWidth` / `minHeight` remain fallbacks for Android 11 and below and for
  launchers that derive picker size from minimum dimensions.
- AppWidgets render a Thing Card Surface Projection of the saved Thing Card
  Appearance. Widget-specific media clamps are rendering adaptations only; they
  must not rewrite the Thing's saved appearance fields.
- AppWidget media-background rendering should preserve the Thing Card Media
  Background meaning: media behind the whole card/row, saved mask strength, and
  foreground colors adapted to the masked media background.
- Remote-surface video rendering should preserve exact-frame semantics for saved
  `videoFrameMs`.
- Standard system notifications should stay on standard notification styles in
  the first remote-surface pass.
- Standard Thing notifications and ongoing Thing notifications should respect
  Thing Card Media Source, Thing Card Video Frame, and Thing Card Thumbnail Crop.
- Doing notifications and quick-create notifications are out of scope because
  they are not Thing Card Media display surfaces.

## Platform Constraints

RemoteViews cannot reuse the normal Home list card layout directly:

- `card_thing.xml` uses custom views such as `InterceptTouchCardView` and
  `StablerRecyclerView`, which are not valid RemoteViews host views.
- RemoteViews run in the launcher/system process. The app cannot rely on
  runtime `ImageView.imageMatrix`, custom drawables, custom view painting, or
  post-layout callbacks inside the host.
- The project supports `minSdk 26`. Android 12/API 31 RemoteViews layout-sizing
  helpers can be optional improvements, but the base rendering path must work on
  API 26.
- RemoteViews updates carry bitmap payloads across process boundaries. A
  complete visual result must still use size caps and safe degradation.
- RemoteViews should not be treated as a general nested-scroll container.
  Things List widgets already use a collection view for top-level scrolling, and
  row-level scrolling would be launcher-dependent and fragile. Things List rows
  may grow inside that parent collection to honor saved top/bottom media ratios,
  subject to hard bitmap and launcher safety caps. Single-Thing widgets can be
  redesigned as collection widgets later, but that is a separate trade-off, not
  the default answer for media height.

Notifications have stricter constraints:

- The current Thing notification path uses `NotificationCompat.BigPictureStyle`,
  not custom notification RemoteViews.
- Modern Android templates limit custom notification height and wrap custom
  content in a standard notification shell.
- A standard BigPicture notification can carry selected/cropped media, but it
  cannot express the full Thing Card layout, left/right media placement, media
  background, mask, or card-height model.

Reference documentation:

- RemoteViews API: https://developer.android.com/reference/android/widget/RemoteViews.html
- Custom notification layouts: https://developer.android.com/develop/ui/views/notifications/custom-notification
- AppWidget sizing and `targetCellWidth` / `targetCellHeight`:
  https://developer.android.com/develop/ui/views/appwidgets/layouts

## Support Matrix

| Feature | Single-Thing AppWidget | Things List AppWidget row | Standard Thing notification |
| --- | --- | --- | --- |
| Thing Card Media Source | Full support | Full support | Full support |
| Media Source None / hide media | Full support | Full support | Hide BigPicture media |
| Exact video frame | Full support | Full support | Full support |
| Thumbnail crop center/zoom | Full support via pre-rendered bitmap | Full support via pre-rendered bitmap | Full support within notification target |
| Top/bottom placement | Full support with content-floor fixed-surface projection | Full support with collection-scrolling row projection | Not applicable |
| Left/right placement | Full-height side panel within widget projection | Full-height side panel within row projection | Not supported |
| Side media width | Full support target, subject to layout spike | Full support target, subject to layout spike | Not supported |
| Media background | Full support target | Full support target | Not supported |
| Media-background mask | Full support | Full support | Not supported |
| Media-background foreground adaptation | Full support | Full support | Not supported |
| Media-background height ratio | Desired ratio clamped within widget bounds | Desired ratio honored in collection row, subject to hard safety caps | Not supported |
| Thing Card Span Mode | Does not directly apply | Does not directly apply | Does not apply |
| Hidden private media | Do not expose media | Do not expose media | Keep current private notification behavior |
| Existing interactions | Preserve | Preserve | Preserve notification actions |

"Full support target" means the product target is full visual support. The
implementation may still need a compatibility spike for API 26-30 RemoteViews
layout mutation and may use safe per-row fallback when launcher limits are hit.

## Rendering Strategy

### Shared Remote Media Renderer

Create a shared remote media rendering path that can be called from
`AppWidgetHelper` and `SystemNotificationUtil`.

Responsibilities:

- Resolve the effective media source with `ThingCardMediaHelper`.
- Respect `ThingCardAppearance.MEDIA_SOURCE_NONE`.
- Decode image sources with orientation applied.
- Decode video sources at saved `videoFrameMs` using closest-frame semantics.
- Apply the same cover-fit crop formula used by `BaseThingsAdapter`:
  target rectangle first, then saved crop center, then user zoom.
- Render thumbnail bitmaps for top/bottom/widget image regions.
- Render media-background bitmaps with the saved mask strength already applied
  or paired with an explicit RemoteViews mask layer where practical.
- Render side-media visual regions when RemoteViews cannot express exact dynamic
  side widths natively on API 26-30.
- Return enough metadata for foreground adaptation, especially the masked media
  base color used by text/icon color selection.

Cache keys should include:

- Thing id.
- Thing update time.
- Thing Card Appearance update time.
- Effective media source key.
- Media file size and last-modified time.
- Target width and height.
- Thumbnail/background mode.
- Crop values.
- Side width percent when applicable.
- Mask strength when applicable.
- `videoFrameMs`.
- Widget alpha where it affects composited output.

### AppWidget Layout Strategy

Keep structured RemoteViews as the primary path:

- Preserve existing text/checklist/action widgets and click PendingIntents.
- Choose RemoteViews layout variants where static child order differs
  significantly, such as top vs bottom media or media-background mode.
- Reuse existing resource ids where possible. If new AppWidget-adjacent ids are
  unavoidable, include explicit launcher smoke testing because earlier resource
  table churn caused stale-looking widget data.
- Use pre-rendered bitmap layers for gradients, cropped media, side-media
  panels, and media backgrounds.
- Prefer API 26-compatible operations such as `setImageViewBitmap`,
  `setViewVisibility`, `setViewPadding`, `setTextViewText`, text colors, and
  image resources.
- Use API 31+ RemoteViews layout sizing APIs only as optional improvements, not
  as the only implementation path.

For media background:

- Hide the separate thumbnail region.
- Render the selected image/video frame behind the whole widget card or row.
- Apply the saved mask strength.
- Adapt text and icon colors to the masked media background.
- Keep image/video count as an inline status indicator where the widget style
  shows that information.

For left/right media:

- The product target is the saved side media width.
- The media target is a full-height side panel. Its height comes from the final
  visible widget card or list-row projection, not from the media's intrinsic
  aspect ratio.
- Implementation should first spike whether structured horizontal layout plus
  API-compatible RemoteViews operations can honor the exact width.
- If exact structured width is not reliable on API 26-30, use a composited
  side-media background region plus content padding so the visual side panel
  still honors the saved width while text/actions remain structured.

For top/bottom media:

- Render a cropped bitmap whose desired target ratio follows the saved thumbnail
  crop aspect ratio.
- For single-Thing AppWidgets, clamp the rendered media target height when the
  desired ratio would consume the widget's usable content area. Preserve crop
  center, user zoom, selected video frame, and placement semantics inside the
  clamped target, and do not write the clamped dimensions back to the Thing.
- Single-Thing AppWidgets use a content-floor-first budget. Reserve enough
  height for the widget card to remain identifiable as a Thing: title or private
  state, at least one line of body text or checklist content when present,
  required reminder/habit/state/action regions, and bottom padding. Media
  receives the remaining height; if necessary, reduce media to a small thumbnail
  target before sacrificing those content floors.
- Things List AppWidget rows do not use the content-floor clamp. They live inside
  the collection scroller, so top/bottom media may make a row taller in order to
  honor the saved desired ratio, subject only to hard RemoteViews bitmap/IPC and
  launcher-compatibility caps.
- Those safety caps are platform transport limits, not product row-height
  limits. First try to honor the saved ratio, then reduce or degrade only when
  the rendered bitmap would be too large to send safely through RemoteViews or
  too risky for launcher compatibility.
- Bottom placement should preserve content spacing and avoid overlapping
  reminder/habit/status blocks.

### Notification Strategy

Update `SystemNotificationUtil.newGeneralNotificationBuilder(...)`:

- Resolve the effective Thing Card Media Source instead of always using the
  first image/video attachment.
- Respect `MEDIA_SOURCE_NONE` by omitting BigPicture media.
- Respect hidden private behavior by keeping current private notification
  content and not exposing media.
- For video sources, decode the saved exact frame.
- Apply Thing Card Thumbnail Crop to the generated BigPicture bitmap.
- Use a notification-safe target rectangle. The saved crop intent is honored,
  but full card placement/background/height semantics are not transferred.
- Preserve existing title/content/action behavior and wearable fallback behavior
  when no safe BigPicture media exists.

This applies to:

- Reminder, habit, goal, note, and auto-notify Thing notifications that use the
  general notification builder.
- Ongoing Thing notification, because it reuses the same builder.

This does not apply to:

- Doing notifications.
- Quick-create notification.

## Degradation Rules

Degrade only when required by platform safety, missing media, permissions, or
bitmap/IPC limits.

Priority order:

1. Preserve text, state, click behavior, and actions.
2. Preserve media source and exact video frame if any media can be shown.
3. Preserve crop center/zoom.
4. Preserve media-background semantics.
5. Degrade media background to a regular thumbnail if needed.
6. Hide the media image if needed, while keeping image/video count where
   possible.
7. If no media can be rendered safely, fall back to the Thing Background without
   clearing stored Thing Card Appearance.

For Things List AppWidget rows, degradation is per row. A single oversized or
problematic row must not prevent the list widget from updating.

For notifications, unsupported full-card features are not treated as failures.
They are intentionally out of scope for the standard BigPicture path.

## Implementation Phases

### Phase 0 - Compatibility Spike

- Verify RemoteViews layout mutation capabilities on API 26 and current target
  devices/launchers.
- Test exact side-width rendering options:
  - structured horizontal layout,
  - layout variants,
  - composited side-media background plus content padding.
- Test top/bottom placement in both single-Thing and list-row layouts.
- Measure practical bitmap caps for single widgets and list widgets.
- Confirm failure behavior when a row bitmap is intentionally oversized.

### Phase 1 - Shared Remote Media Renderer

- Extract/recreate the app-owned crop math from `BaseThingsAdapter` into a
  renderer usable outside RecyclerView-bound `ImageView`s.
- Add image decode with orientation handling.
- Add video exact-frame decode with saved `videoFrameMs`.
- Add thumbnail render requests.
- Add media-background render requests with mask compositing.
- Add representative/masked foreground base-color output.
- Add cache keys and conservative bitmap size caps.

### Phase 2 - Standard Notification BigPicture Upgrade

- Replace first-attachment lookup with effective media source resolution.
- Apply saved video frame and thumbnail crop.
- Preserve private/missing-permission/no-media fallbacks.
- Verify ordinary and ongoing Thing notification paths.

### Phase 3 - Single-Thing AppWidget

- Add single-Thing provider entries for 4x2, 2x4, 4x3, 3x4, 5x2, 2x5, 5x3,
  3x5, 5x4, 4x5, 5x5, 6x2, 2x6, 6x3, 3x6, 6x4, 4x6, 6x5, 5x6, and 6x6 size
  presets. These are launcher-picker defaults for better media-heavy card fit;
  existing providers should remain resizable. Do not add extra 1xN/Nx1 media
  presets beyond the existing 1x1.
- Relabel existing single-Thing provider entries as 1x1, 2x2, 3x3, and 4x4, and
  label new entries with their exact cell shapes.
- Add `targetCellWidth` / `targetCellHeight` to existing and new single-Thing
  providers, while keeping `minWidth` / `minHeight` as fallback sizing.
- Use dedicated receiver classes for the new single-Thing size presets, but let
  those new providers share `BaseThingWidgetConfiguration`. The configuration
  activity should resolve the actual provider class from `AppWidgetManager` for
  the current `appWidgetId` before writing the saved widget `size`, so adding
  presets does not require one configuration Activity per size.
- Add layout selection or composited rendering for top, bottom, left, right, and
  media-background modes.
- Add fixed-surface media-height projection so top/bottom media cannot consume
  the entire widget and side media fills the final visible card height.
- Enforce the content-floor-first media budget for top/bottom media.
- Update `BaseThingWidgetConfiguration` in two parts:
  - the Thing candidate list should reuse home-list Thing Card rendering so
    wide cards, placement, media backgrounds, crops, and video frames are visible
    while choosing a Thing;
  - the post-selection preview should render the single-Thing AppWidget
    projection, including widget alpha, widget size/aspect, square widget chrome,
    RemoteViews-compatible media rendering, and fixed-surface media-height
    projection.
- Preserve existing widget click behavior and reminder/habit/checklist actions.
- Preserve normal/simple widget style semantics.
- Add media-background foreground adaptation.
- Add widget-specific safe caps and fallback behavior.

### Phase 4 - Things List AppWidget Rows

- Add Things List provider entries for 4x4, 5x4, 4x5, 5x5, 6x4, 4x6, 6x5, 5x6,
  and 6x6 so launcher pickers expose larger list widgets by default, while
  keeping the existing resizable list provider.
- Relabel the existing Things List provider as 3x3 and label new providers with
  their exact cell shapes.
- Add `targetCellWidth` / `targetCellHeight` to existing and new Things List
  providers, while keeping `minWidth` / `minHeight` as fallback sizing.
- Apply the same appearance renderer to each collection row.
- Allow top/bottom media to grow the row according to the saved desired ratio,
  while the parent collection view handles scrolling.
- Keep rows individually non-scrollable; do not add nested row scrolling.
- Add per-row degradation.
- Preserve row click templates and checklist/action behavior.
- Keep list updates resilient when one row has missing media or an oversized
  bitmap.

### Phase 5 - Verification And Polish

- Device-test launchers with single and list widgets.
- Test API 26-30 behavior separately from API 31+ behavior.
- Test light/dark media, gradients, and media-background mask strengths.
- Test private Things with private content hidden.
- Test image missing, media permission missing, deleted attachment, and changed
  media file cases.
- Test normal/simple widget styles.
- Test regular reminders, habits, goals, notes, auto-notify notifications, and
  ongoing Thing notification.

## Verification Checklist

- [ ] Single-Thing widget respects selected media source.
- [ ] Things List widget row respects selected media source.
- [ ] Notification BigPicture respects selected media source.
- [ ] Single-Thing widget respects `MEDIA_SOURCE_NONE`.
- [ ] Things List widget row respects `MEDIA_SOURCE_NONE`.
- [ ] Notification omits BigPicture for `MEDIA_SOURCE_NONE`.
- [ ] Image thumbnail crop matches saved center/zoom.
- [ ] Video thumbnail crop matches saved center/zoom.
- [ ] Saved video frame is exact enough to match the card renderer.
- [ ] Top placement works in single-Thing widget.
- [ ] Bottom placement works in single-Thing widget.
- [ ] Left placement works in single-Thing widget.
- [ ] Right placement works in single-Thing widget.
- [ ] Single-Thing 4x2 provider appears in launcher picker and renders wide
      media-heavy cards reasonably.
- [ ] Single-Thing 2x4 provider appears in launcher picker and renders tall
      media-heavy cards reasonably.
- [ ] Single-Thing 4x3 and 3x4 providers appear in launcher picker and render
      media-heavy cards reasonably.
- [ ] Single-Thing 5-cell and 6-cell providers appear in launcher picker on
      capable launchers and render tablet/large-grid media-heavy cards
      reasonably.
- [ ] Top placement works in Things List widget rows.
- [ ] Bottom placement works in Things List widget rows.
- [ ] Left placement works in Things List widget rows.
- [ ] Right placement works in Things List widget rows.
- [ ] Things List 4x4 provider appears in launcher picker and uses the same row
      appearance behavior as the existing list widget.
- [ ] Things List 5-cell and 6-cell providers appear in launcher picker on
      capable launchers and use the same row appearance behavior as the existing
      list widget.
- [ ] Side media width changes are visible in widget surfaces.
- [ ] Media background covers the whole single-Thing widget card.
- [ ] Media background covers the whole Things List widget row.
- [ ] Media-background mask strength is visible and saved settings are used.
- [ ] Widget foreground colors adapt to masked media backgrounds.
- [ ] Existing widget click behavior still works.
- [ ] Existing checklist row behavior still works.
- [ ] Existing reminder/habit widget actions still work.
- [ ] Per-row degradation does not break the whole list widget update.
- [ ] Hidden private widgets do not expose media.
- [ ] Hidden private notifications do not expose media.
- [ ] Missing media falls back without clearing stored appearance.
- [ ] Missing permission falls back without clearing stored appearance.
- [ ] Ongoing Thing notification uses the same BigPicture media behavior.
- [ ] Doing notification remains unchanged.
- [ ] Quick-create notification remains unchanged.

## Out Of Scope

- Fully custom system notification card layouts.
- Media background inside standard system notifications.
- Left/right Thing Card placement inside standard system notifications.
- Arbitrary-height media inside nested-scrolling AppWidget rows.
- Making Thing Card Span Mode behave like home-list normal/wide span in
  AppWidgets.
- Adding a widget-specific setting to opt in or out of Thing Card Appearance.
- Modifying original image/video attachment files.
- Creating a new attachment ID system.
