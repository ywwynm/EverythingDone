# Remote Thing Card Appearance Decisions

Migrated from global `memory/decisions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-06-05 - Remote Thing Card Appearance scope

When porting Thing Card Appearance to remote surfaces, AppWidgets should aim
for complete visual support where practical, even if that requires app-side
bitmap pre-rendering for media crop, video frame selection, media backgrounds,
and other RemoteViews-limited features.

System notifications should first stay on standard notification styles and
upgrade the existing BigPictureStyle path to respect Thing Card Media Source,
Thing Card Video Frame, and Thing Card Thumbnail Crop. They should not try to
fully reproduce the Thing Card layout, left/right media placement, or media
background model in the first remote-surface pass, because custom notification
RemoteViews are height-limited, system-templated on modern Android, and more
fragile for readability.
This notification scope applies to ordinary Thing notifications and the ongoing
Thing notification path that reuses the general Thing notification builder. It
does not apply to Doing notifications or quick-create notifications, because
those notification forms are not Thing Card Media display surfaces.

Thing Card Span Mode should not be copied directly into AppWidgets as the home
list's normal-vs-wide span behavior. AppWidget geometry is owned by the widget
size class and the launcher's actual allocated cell size. Widget rendering
should use the widget's current target area, while media source, placement,
crop, background, mask, and related Thing Card Appearance preferences may still
apply. Span Mode may be used only as a secondary default/aspect reference, or
ignored where the widget surface already provides clearer geometry.

AppWidget rendering should preserve the existing interaction granularity rather
than replacing the whole widget card with one pre-rendered card bitmap. The
primary implementation path should keep structured RemoteViews for text,
checklist rows, reminder/habit/action regions, Doing overlays, and row clicks,
while pre-rendering only the media/background imagery that RemoteViews cannot
draw natively. Whole-card bitmap rendering is only a last-resort fallback for a
surface or feature that cannot be expressed safely through structured
RemoteViews.

Both single-Thing AppWidgets and Things List AppWidget rows should aim to
support Thing Card Appearance fully. The Things List widget should not
intentionally omit media backgrounds, left/right placement, side media width, or
crop/video-frame behavior merely to reduce ordinary update cost. Implementation
may still need hard safety guards for RemoteViews bitmap/IPC limits and launcher
compatibility, but the product direction is complete visual support for both
widget classes.

When a Things List AppWidget update would exceed RemoteViews bitmap/IPC limits
or otherwise fail because of complete media rendering, degrade per row rather
than allowing the whole widget update to fail. Preserve text, click behavior,
state, and action regions first. If necessary, degrade a row's media background
to a regular thumbnail; if that is still unsafe, hide the media image while
keeping the image/video attachment count indicator where possible.

AppWidgets should follow Thing Card Appearance by default without adding a new
widget-specific "follow card appearance" setting. Existing AppWidget normal vs
simple style remains an information-density choice for the widget surface; it
does not opt the widget out of rendering visible media according to Thing Card
Appearance where the widget style still shows media.

AppWidget media-background rendering should preserve the Thing Card Media
Background meaning. When media background is enabled and safe to render, the
selected image/video frame is drawn behind the whole widget card or list row,
with the saved mask strength applied and widget text/icons adapted to the
masked media background. It should not be treated merely as a larger thumbnail
region.

Remote-surface video rendering should preserve Thing Card Video Frame exact
frame semantics. AppWidgets and standard notification BigPicture media should
decode the saved `videoFrameMs` with the same closest-frame behavior used by the
Thing Card Appearance renderer, rather than falling back to sync-frame-only
thumbnails.

## 2026-06-05 - Remote AppWidget side media sizing

AppWidget left/right media placement should express the saved
`sideMediaWidthPercent` through the pre-rendered bitmap's intrinsic width rather
than depending on API 31+ RemoteViews layout-sizing methods. The widget XML
uses `wrap_content` media slots and a weighted text column, so the rendered
bitmap width determines the side panel width while the existing text/checklist
and action RemoteViews remain interactive on API 26+.

Superseding the intrinsic-height part of that approach, left/right Thing Card
Media is a full-height side media panel. It should span the final visible
Thing Card content height in home-list cards, Things List AppWidget rows, and
single-Thing AppWidgets. Single-Thing AppWidgets cannot freely grow, so side
media height there must be derived from the fixed widget height budget. Things
List AppWidget rows live inside the widget collection scroller, so a row may
grow with its projected content and the side media panel should fill that final
row height.

## 2026-05-30 - Full-span home-card entry lives in Detail overflow

Home Card Span Mode should be toggled from DetailActivity's overflow menu for
real editable Things. The create screen should not expose the action in the
initial implementation; users can create the Thing first, then change its home
card span after it has a stable persisted identity.

The Simplified Chinese menu labels should be "放大记事卡片" for switching from
normal span to full span, and "缩小记事卡片" for switching from full span back to
normal span.

Use `homeCardSpanMode` naming consistently for this state. Do not use the
broader name `style`, because the value only controls card span and should not
imply image placement, typography, or a full layout style.
The database column follows existing schema style as `home_card_span_mode`,
while Kotlin properties and helpers use `homeCardSpanMode`.

Changing Home Card Span Mode should participate in DetailActivity's normal
edit lifecycle and undo/redo stack. It should not behave like sticky or
ongoing, which write immediately and finish the Detail screen. The overflow
label updates immediately after the toggle, while persistence happens through
the existing create/update return flow.

Clicking the Detail overflow action to enlarge or shrink a Home Card should
show immediate feedback because the visual result is only visible after
returning to the home list. Use DetailActivity's normal Snackbar when it is
available; fall back to Toast only if the Snackbar has not been initialized.
The Simplified Chinese messages are "已放大记事卡片" and "已缩小记事卡片".

Changing Home Card Span Mode does not change a Thing's `location` or business
ordering. Returning from Detail should treat the change as an update to the
same item; the home list may relayout the waterfall spans, but it should not
delete/reinsert the Thing or replay the whole-list appearing animation.

Implement the first iteration as reliable full-span behavior plus conservative
width adaptation. Keep the existing card content order: image, title, private
lock, content/checklist, audio, reminder/habit, padding, and doing cover. Do
not include magazine-style text centering, artistic typography, or manual image
placement in the first implementation.

Full-span image cards keep the existing top-image and `centerCrop` behavior in
the first iteration. Normal cards keep the existing `cardWidth * 3 / 4` image
height. Full-span cards should bound image height, for example around
`min(fullCardWidth * 9 / 16, screenHeight * 0.36)` with a reasonable dp
minimum, so wide cards do not become excessively tall. This visual ratio can be
revisited later.

Full-span text cards should keep the first iteration's normal card typography:
left-aligned title/content, existing length-based content text-size formula,
and existing Thing Foreground colour logic. Do not add centred text, artistic
fonts, or a separate text-poster layout yet. The first iteration may increase
the content `maxLines` for full-span cards so wider cards do not truncate too
aggressively.

Full-span checklist cards keep the existing single-column checklist rendering
and existing card-level checklist toggle behavior. Normal span keeps the
current maximum of 8 visible checklist rows; full span may raise the visible
maximum to 12. Do not introduce a two-column checklist or a full-span-specific
checklist layout in the first iteration.

Full-span audio, reminder, goal, and habit sections keep their existing
vertical block structure in the first iteration. Audio-only cards may continue
using the existing enlarged audio layout. Reminder/goal and habit metadata
should gain horizontal room from the wider card, but should not become a
separate horizontal information bar yet. The doing cover continues to cover the
final measured card bounds.

Full-span hidden private cards still hide all private content. They should use
the full-span width, keep the existing title behavior, and enlarge the lock
icon so the card does not read as an overly wide, shallow strip. The first
iteration should not reveal any image, content, checklist, audio, reminder, or
habit metadata for locked private Things.

Home card fixed-width ownership should stay on the card content container
(`llContent`) rather than being split across content minimum width and image
child width. Full-span cards, hidden private cards, and image cards all need a
known content width; set that on `llContent.layoutParams.width`, reset stale
minimums during bind, and let the image container use `MATCH_PARENT` inside the
content container. This prevents recycled hidden-private holders from affecting
image-card image measurement.

Full-span cards with sparse visible content should have an adjustable minimum
content height so they do not become overly wide, shallow strips. Apply this to
hidden private cards, title-only or short-text cards, and audio-only cards.
Avoid forcing the minimum height onto image, checklist, reminder, habit, or
long-text cards. Keep the height value in a resource token rather than hardcoding
it in adapter logic.

Home Card Span Mode affects only the ThingsActivity home list in the first
iteration. It should not change DoingActivity's embedded cards,
NoticeableNotificationActivity's embedded Thing row, single-Thing widgets,
Things List widgets, or widget configuration previews. Shared card binding code
may support full-span sizing, but the decision to apply full span belongs to
the home-list adapter.
Search results are still a filtered home list, so they should respect each
Thing's Home Card Span Mode.

Existing Things and newly created Things default to normal span. Database
upgrade should add `home_card_span_mode` with default `0`, and no migration
should automatically promote existing rows to full span.

Toggling Home Card Span Mode updates the Thing's `updateTime`, matching other
Detail visual edits such as changing the Thing Background.

Implementation must handle both upgrade and fresh-install database paths. The
schema version should increase, old databases should receive
`home_card_span_mode integer not null default 0`, fresh installs should create
the column directly, and every fixed-column initial insert/header insert path
must provide the new normal-span value.

The v10 migration should guard new trailing Thing columns with a column-exists
check before executing `ALTER TABLE`. This keeps v9 -> v10 simple while also
allowing older restored databases that skipped an intermediate app version to
receive any missing trailing columns without duplicate-column failures.

Backup and restore do not need a separate format layer for this feature,
because backups copy the database file. Restored older databases should be
handled by the normal SQLiteOpenHelper upgrade path, which adds
`home_card_span_mode` with default normal span.

## 2026-05-27 - Background DetailActivity refreshes from storage after remote widget actions

Launcher widget and notification actions should only be blocked when the
matching `DetailActivity` is actually visible in the foreground. A stopped but
still alive Detail screen, such as one left by pressing Home, must not prevent
the remote action from writing the database.

`DetailActivity` should keep a rendered Thing snapshot and, when returning to
the foreground, compare that snapshot with the latest Thing from the manager or
DAO. If the same Thing was changed externally while Detail was stopped, rebuild
the screen instead of calling `initUI()` directly. `initUI()` is not a safe
standalone refresh entry point because it assumes freshly initialised views,
adapters, watchers, and undo/redo state.

## 2026-05-27 - Widget create actions should resolve the new-thing colour at click time

Launcher widget PendingIntents must not keep using the same precomputed
`App.newThingBackground` forever. The new-thing background changes when
`DetailActivity` opens in CREATE mode, while widget RemoteViews may keep the
same PendingIntent for a long time.

Correction after device testing: the standalone Create widget should mirror the
Things List widget create action, not open `DetailActivity` directly. The direct
`DetailActivity` plus standalone-widget refresh attempt still allowed repeated
colour/task staleness after abandoning an empty created thing and pressing Home.

Both create-widget entry points should go through `ShortcutActivity` with
`SHORTCUT_ACTION_CREATE`. The list widget carries its selected limit; the
standalone Create widget carries `KEY_LIMIT = ALL_UNDERWAY`. This keeps the
background resolved at click time and follows the entry path the user verified
as repeatedly opening the create page correctly.
