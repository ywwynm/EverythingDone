# Thing Folders Decisions

## 2026-06-20 - Folder-scoped AppWidget create-return uses one refresh path

When a create flow launched from a Folder-scoped Things-list AppWidget returns
to the home list, `ThingsActivity` should treat it as an external Folder
projection refresh, not as a same-list item insertion. Opening the target Folder
already loads the projection and notifies the adapter, so the create-return path
must not also run the ordinary new-Thing `notifyItemInserted` or one-shot
created-card animation. The projection refresh may still use the ordinary
Things appearing animation, because that animation belongs to the single
projection rebind and does not add a second adapter insertion signal.

## 2026-06-20 - Merge Folder AppWidget behavior into Things-list AppWidgets

The folder widget concept should be implemented by extending the existing
Things-list AppWidget family rather than by adding a separate dedicated Folder
AppWidget provider. A Things-list AppWidget can target either a built-in root
projection or a selected Thing Folder projection, and it should offer both List
and Grid display modes where the AppWidget platform supports them.

The existing settings button in the Things-list AppWidget header remains the
configuration entry point. Reopening settings should allow users to change the
target projection, including which Thing Folder is selected, and switch between
List and Grid display modes for that same AppWidget instance.

The Things-list AppWidget configuration model should keep three independent
choices: target scope, type filters, and display mode. Target scope is root or
a selected Thing Folder. Type filters are represented by a horizontal row of
All, Note, Reminder, Habit, and Goal icons. All is exclusive, while the
specific type icons support multi-select combinations such as Reminder plus
Habit. Display mode is List or Grid. This preserves the same folder projection
semantics as the home list and supports widgets such as "all Things in this
Folder" and "Reminders and Habits in this Folder" without adding a separate
Folder-specific widget type.

The type filter must always resolve to a valid non-empty selection. If the user
deselects every specific type icon, the configuration should return to All
automatically rather than saving an intentionally empty AppWidget.

Grid display mode should derive its column count from the AppWidget width
rather than exposing a separate column-count setting. Narrow widgets can use one
column, medium widgets two columns, and wide widgets three columns, with exact
thresholds tuned during launcher/device testing.

Grid display mode should preserve existing Thing Card Span Mode where possible:
Things configured as full-span Thing Cards should occupy a full widget grid row.
This likely requires rendering Grid mode as a row-oriented RemoteViews
collection rather than as a plain AppWidget `GridView`, because individual
`GridView` items do not reliably span multiple columns. Thing Folder entries
remain summary Folder Cards rather than large Folder previews.

Grid-mode rows should preserve card content rather than clipping. When a row
contains multiple normal-span cards, each card keeps its own wrap-content
height, the row height follows the tallest card, and shorter cards stay
top-aligned rather than being stretched to match.

Grid-mode item packing should preserve the current mixed-list order. Normal
cards fill the current row in order. A full-span card first closes any
partially filled normal row, then occupies a full row by itself before normal
row packing resumes. Thing Folder summary cards participate in this packing
according to their Folder Card span mode.

Grid display mode should preserve rich Thing Card information as far as the
RemoteViews platform permits. It should not intentionally collapse Things into
minimal tiles when a supported RemoteViews approximation of the normal widget
Thing Card can carry title, content, checklist, media, reminder, habit, state,
sticky, doing, and privacy surfaces. Thing Folder entries are the exception:
inside Things-list AppWidgets, Folders should render as summary Folder Cards
instead of reproducing the in-app large thumbnail Folder Card with nested
previews.

Thing Folder entries inside a desktop Things-list AppWidget should open the
app at the corresponding Thing Folder projection when tapped. The AppWidget
itself should not maintain an internal Folder navigation stack, change its
header to the tapped Folder, or implement an AppWidget-local back/up affordance.

The Things-list AppWidget header should open the app at the widget's full
configured projection, including the target scope and built-in type filter. The
existing settings button in that header remains the way to reopen
configuration and does not share the header's navigation behavior.

Things-list AppWidget privacy should follow home-list semantics while keeping
the desktop surface conservative. Private Thing Folder entries show the stored
Folder name, Folder icon, and lock affordance, but no child previews. Private
Things and Things under a private ancestor use the existing protected Thing
widget presentation. Opening a private Folder projection from an AppWidget, or
selecting one in the configuration flow, requires the existing private Folder
authentication path.

The first folder-aware Things-list AppWidget slice keeps state fixed to
Underway. The configuration should not add Finished or Deleted projection
choices yet; those states can be added later as a separate AppWidget expansion.

Folder-aware Things-list AppWidget configuration should use explicit database
fields instead of extending the legacy negative `thing_id` encoding. The
`app_widget` record should keep enough independent data to represent root or
Folder target scope, selected type-filter mask, and List/Grid display mode
while preserving existing alpha and style settings. Existing Things-list widget
records should migrate to root scope, their current single built-in limit as
the equivalent type filter, and List display mode.

When migrating existing Things-list AppWidgets, legacy `ALL_UNDERWAY` should
map to the exclusive All type filter rather than to all current specific type
icons. This preserves the open-ended "all underway Things" meaning.

Things-list AppWidget item ordering should follow the same mixed Thing/Folder
ordering as the corresponding home projection, including sticky Things and
sticky Thing Folders. List and Grid display modes change layout only; they do
not introduce a separate widget-specific sort order.

The Things-list AppWidget configuration Folder picker should show all
non-deleted Thing Folders rather than filtering the tree by the current type
icon selection. Folder scope and type filters are independent choices, so an
otherwise valid Folder should not disappear while the user changes type icons.

The create button in a Things-list AppWidget should not force the new Thing
type from the widget's type filter. Creation should use the existing Detail
create flow, where the Thing's final type is determined by the reminder time,
repeat settings, and other fields the user sets while creating it.

When a Things-list AppWidget targets a Thing Folder, its create button should
open creation in that Folder scope. The Folder scope affects where the new Thing
is created, while the widget's type filter still does not force the Thing type.

Things-list AppWidget header titles should show the configured scope and type
filters directly. Root + All shows the Underway title. Root + multiple specific
types joins the selected type names with `/`, for example `Reminder/Habit`.
Folder + All shows the Folder name. Folder + specific type filters shows
`Folder name · Reminder/Habit` using the selected type names.

Things-list AppWidget headers use the app accent for root scopes and the
selected Thing Folder's pure colour or gradient for Folder scopes. Header
foreground should adapt to the rendered header background, and the existing
header transparency setting still applies.

In Grid mode, each card slot inside a row should own its click fill-in intent.
The row is only a RemoteViews container and should not handle item clicks
itself. Thing slots open Detail; Folder slots open the app at that Folder
projection.

Grid rows should keep stable column widths by filling incomplete rows with
transparent, non-clickable empty slots.

The Things-list AppWidget header keeps its settings and create buttons in both
List and Grid display modes. The configuration does not need a live AppWidget
preview in this slice. The List/Grid display-mode choice should not use radio
controls; it should follow the Thing Card appearance panel's compact row
pattern with a left label and two text options that have pill-shaped touch
ripples and a visible selected state.

The Things-list AppWidget transparency slider primarily controls each content
card's own background transparency, including Thing cards and Folder summary
cards. The existing top-bar transparency checkbox applies that same alpha to
the widget header; without it, the header remains opaque.

Existing Things-list AppWidget configuration state maps legacy Underway to All,
legacy single type limits to the matching specific type icon, and new widgets
to root scope, All, List mode, and the existing default alpha/style settings.
Type icon ordering is fixed as All, Note, Reminder, Habit, and Goal. Header
type-name joining uses this fixed order, not click order.

A Folder-scoped Things-list AppWidget shows direct child Things and direct
child Folder summary cards. It does not recursively flatten descendants.
Things-list AppWidget item interactions are click-only; Folder and Thing rows
do not provide widget-level long-press menus or card actions.

The Things-list AppWidget configuration picker follows the Drawer model for its
root row: the Underway root row is selectable, has no trailing expand/collapse
icon, and the first Folder level is always visible beneath it. Folder row bodies
select that Folder as the widget scope, while trailing expand/collapse icons
show or hide child Folders. The picker shows the first Folder level by default,
expands the configured Folder's ancestor path when editing an existing widget,
and does not need search in this slice.

Single-Thing AppWidget configuration remains a Thing selector. It should become
Folder-aware only as navigation: Folder rows appear so users can browse into a
Thing Folder, the configuration title and back/up behavior follow the current
Folder, and only Thing rows can be selected as the single widget target.
Each Folder projection in Single-Thing AppWidget configuration shows child
Thing Folders and direct Things in the same mixed order as the home list. Folder
rows are navigation entries, while only Thing rows can be selected as widget
targets.
Things-list AppWidget configuration is a separate configuration surface, not
the single-Thing card picker, so Folder target selection for list widgets needs
its own UI control.

AppWidget media transparency must be rendered into the bitmap that is assigned
to the RemoteViews `ImageView`. Launcher/runtime handling of
`ImageView.setImageAlpha` is not reliable enough for widget preview or desktop
widget media. This applies to media-background cards and foreground image/video
thumbnail placements.

RemoteViews Thing and Folder cards must keep their foreground affordances
luminance-adaptive. Small icons, audio attachment icons, habit/reminder/state
icons, privacy locks, and dashed separators should use black-side resources or
tints on light cards and white-side resources or tints on dark/media-background
cards. Folder summary cards in Things-list AppWidgets should use the same
rounded card clipping as Thing cards.

Things-list AppWidget configuration type filters should keep the circular icon
touch targets from the app chrome. The panel also shows a live summary label,
for example `Thing type: All` or `Thing type: Reminder/Habit`, above the five
type icons.

## 2026-06-20 - Folder navigation restores parent scroll state

Opening a child Folder projection should keep the existing top-start behavior
for the newly opened Folder, but returning to the parent projection should
restore the parent's previous RecyclerView layout state. `ThingsActivity`
therefore caches `LayoutManager.onSaveInstanceState()` by
`ThingListProjection.key()` before leaving a projection and restores that state
after the parent projection has reloaded and rebound.

The scroll cache is Activity-local only. It should not become persisted Folder
state because it represents transient navigation context, not the user's
stored Folder data.

The restore must be applied synchronously to the `LayoutManager` before the
next frame is drawn, with the Activity Header updated from the restored first
visible adapter position in a pre-draw callback. A posted restore is too late:
it can allow one frame where the parent projection is rebound at the top or
with an expanded Header before jumping to the saved position.

The same restore path should also disable `ThingsAdapter`'s regular Things
appearing animation for that rebind. The visible issue is not smooth scrolling:
the appearing animation can replay when returning to an already-seen parent
projection, making cards animate from an initial state instead of simply
rendering at the restored scroll offset.

Gradient actionbar icon tint should use the same visual strength as pure-colour
toolbar icon tint. The gradient path renders the drawable into a bitmap mask,
normalizes the mask's maximum alpha to opaque, then fills only that icon mask
with the Folder gradient. This prevents old semi-transparent toolbar assets
from looking washed out while still avoiding tint over the whole touch target.

## 2026-06-20 - Activity Header Thing counts are recursive

Activity Header subtitles should keep Folder count as the visible direct child
Folder count, but Thing count should represent the full current subtree. For
root projections this means direct root Things plus Things inside every visible
root Folder subtree. For Folder projections this means direct Things in the
current Folder plus Things in every descendant Folder that matches the current
projection/filter.

The header should reuse the mixed-list `ThingListEntry.FolderEntry` recursive
count instead of issuing a separate database query. This keeps the Activity
Header count aligned with Folder Card count filtering and avoids adding another
counting path.

The create-Thing FAB should use the `vec_ic_create_thing` vector copied from
Everything-Android. Start-doing affordances should use the
`vec_ic_start_thing` vector copied from Everything-Android while preserving the
existing view/layout sizes at each call site.

## 2026-06-20 - Folder projections tint the surrounding chrome

Folder projection chrome should carry the current Folder identity without
making the list surface look like a large colour block. The muted Folder
surface should therefore move even closer to `bg_activity_things`; only toolbar
icons, the Activity Header Folder title, the create-Thing FAB, and contextual
selection chrome use the stronger Folder pure colour or gradient.

For normal in-Folder mode, the create-Thing FAB uses the Folder background and
the Home actionbar icons use the Folder colour/gradient tint. For contextual
selecting mode, the contextual toolbar plus its status-bar spacer use the
Folder colour/gradient as the background; its title and icons use a dark or
light foreground chosen from the Folder background's representative luminance.

Root Activity Header subtitles should use the same direct-child Folder/Thing
count text as Folder projections. Zero-count segments are omitted in both
contexts.

## 2026-06-20 - Folder projections share the muted Folder surface

The muted Folder surface should lean more strongly toward the app's list
background than the first implementation. `BackgroundUtil.mutedSurfaceBackground`
now uses a smaller Folder-accent blend so thumbnail Folder interiors and Folder
projection screens remain visually close to `bg_activity_things`.

When ThingsActivity is showing a Folder projection, the main list surface and
status-bar spacer should use the same muted Folder surface as large
thumbnail-mode Folder Cards. Root projections should restore plain
`bg_activity_things`. The Activity Header Folder title should use the Folder's
own pure colour or gradient text fill, while the subtitle should omit zero
direct-child count segments instead of showing `0 folders` or `0 things`.

## 2026-06-20 - Thumbnail Folder interior fills are muted Folder surfaces

The native-elevation strategy remains the current implementation for
thumbnail-mode Folder Cards and their drag overlays. The interior fill no
longer needs to be the exact `bg_activity_things` colour, though; it should be
a muted version of the Folder background so the surface still reads as close to
the surrounding list background while carrying a small amount of the Folder's
own colour identity.

`BackgroundUtil.mutedSurfaceBackground(...)` owns this derivation. It blends the
current theme's list background toward the Folder background by a small amount,
using a slightly stronger tint in dark mode so the hue remains visible against
`#121212`. For gradient Folder backgrounds, both gradient stops are blended
toward the same list surface and the original gradient orientation is preserved.

The list card and `DragOverlayImageView` must use the same derived
`ThingBackground`. This keeps transparent-looking interior areas visually
consistent before and during overlay drag, while preserving the cheaper native
`CardView` / View elevation path.

## 2026-06-20 - Thumbnail Folder shadows prefer native elevation over true transparency

The outside-only transparent shadow decision below is superseded for current
implementation. Real-device testing showed that drawing a
`MaterialShapeDrawable` compat shadow through a RecyclerView decoration and
clipping it to an outside-only path is too expensive during list scroll and
overlay drag. The result does preserve true transparent interior pixels, but it
does not meet the interaction performance requirement.

Thumbnail-mode Folder Cards should therefore return to the cheaper native
elevation strategy: the `CardView` itself uses the same normal and dragging
elevation as ordinary Thing Cards, and its otherwise empty interior is filled
with `bg_activity_things` to cover the inner half of the native outline shadow.
This makes the interior transparent-like against the current list background
but not alpha-transparent.

Thumbnail-mode Folder drag overlays should follow the same trade-off. They use
native View elevation on expanded overlay bounds with an inset rounded
`Outline`, draw `bg_activity_things` inside the content rect, and then draw the
captured bitmap. This keeps the native shadow look and avoids per-frame compat
shadow, `clipPath`, or software-layer work.

`ThingListOverlayDragController` still belongs in the `managers` package rather
than the `activities` package.

## 2026-06-20 - Transparent thumbnail Folder shadows use an outside-only layer

The previous thumbnail Folder Card shadow strategy that filled the transparent
Folder surface with `bg_activity_things` is superseded. User testing showed
that the blank interior area must be truly transparent in both the normal list
card and the drag overlay; matching the list background is not equivalent.

Android's View shadow model still makes native elevation a poor fit for this
specific surface: the shadow is derived from the View `Outline`,
`clipToOutline` clips content rather than the shadow, and `View.draw(Canvas)`
does not capture real-time shadows or outline clipping into the overlay bitmap.
A single transparent elevated `CardView`/`ImageView` can therefore leak the
inner half of the shadow through transparent pixels.

Thumbnail-mode Folder Cards should keep their real `CardView` background
transparent and set native `cardElevation` / `maxCardElevation` to `0f`. The
visible outer lift is drawn by `ThumbnailFolderCardShadowDecoration`, which
uses `MaterialShapeDrawable` compat elevation under RecyclerView children and
clips the drawable with an even-odd outside-only rounded path. The card content
remains transparent because the inner rounded rect is never painted.

Thumbnail-mode Folder drag overlays use the same outside-only shadow helper.
`DragOverlayImageView` keeps expanded bounds for shadow overflow, clips the
shadow to the area outside the content rounded rect, then clips and draws the
transparent bitmap content inside that rounded rect. Ordinary Thing Cards and
summary Folder Cards continue to use the native elevation path.

`ThingListOverlayDragController` belongs in the `managers` package rather than
the `activities` package. It still communicates with `ThingsActivity` through
the existing Host contract.

## 2026-06-20 - In-Folder header spacer updates are not scroll state

The in-Folder Activity header may change its visible title width and line
count while collapsing into the Toolbar, but the RecyclerView's invisible
header spacer must represent the expanded header's stable occupied space. It
must not be updated from header layout changes that happen during scrolling.

Spacer height requests should be emitted only from explicit expanded-header
refresh points such as Folder navigation, header text changes, or reset after
configuration/search transitions. Applying the spacer to the Thing list adapter
must also wait until the RecyclerView is idle and not computing layout, because
`notifyItemChanged(0)` during `StaggeredGridLayoutManager.fill(...)` can make
RecyclerView try to attach an already-attached header holder and crash.

The collapsed Folder title's vertical centering should be computed from the
current visible title layout, including the capped two-line collapsed title
height, rather than from the expanded header block height.

The header collapse controller should clamp scroll distance to the current
header spacer height instead of resetting to expanded state when scroll exceeds
the legacy 102dp header height. Staggered-grid scroll callers should also use
the minimum visible adapter position across all spans, not only span 0, when
deciding whether the invisible header spacer is still the first visible item.
Otherwise the title, subtitle alpha, and actionbar shadow can jump between
expanded and collapsed states at the spacer boundary.

## 2026-06-19 - Folder privacy, sticky placement, and move dialog rules

Private Thing Folders must protect every operation that reveals or changes
private containment. Opening the card-appearance panel for a Private Thing
Folder, dragging a Thing or Folder into a Private Thing Folder, moving a Thing
or Folder into or out of private containment, expanding a Private Thing Folder
in a move target tree, and swiping a private Thing Card all require the same
password or fingerprint verification path before the operation proceeds.

Sticky Things and sticky Thing Folders share one mixed sticky section at the top
of the home list. They are not separated by type, and toggling sticky state
should update the visible list immediately rather than requiring an app restart.
When a card is dragged in Moving mode, no insertion line should be shown before
the sticky section's first entry because ordinary reordering cannot place an
entry ahead of sticky content.

Folder-scoped sticky placement is independent from root sticky placement. A
Thing or Folder can be sticky inside its current Thing Folder, should be placed
first within that Folder projection, and should use the containing Folder's
pure colour or gradient for the top-right sticky indicator. If the item leaves
that Folder for any reason, its Folder-scoped sticky state must be cleared.
Thumbnail-mode Folder Cards must also render the indicator for sticky child
Things or child Folders in their preview content.

Move-to-Folder UI must use the app's custom `DialogFragment` style rather than
a platform dialog. The dialog title is `移动到文件夹`, the content mirrors the
Drawer Folder tree row model with Folder icon, title, expand/collapse affordance,
and indentation, and the root destination is labelled `正在进行`. The current
parent starts selected, confirm performs the move, and moving a Folder into
itself or one of its descendants is forbidden. The title and confirm action use
the moved Thing or Folder's own pure colour or gradient.

## 2026-06-19 - Folder long press parity and thumbnail overlay shadow

Folder Cards should not swallow long press gestures while the list is already
in selecting mode. The Folder branch should follow the same non-normal-mode
fallback as Thing Cards and call `ModeManager.backNormalMode(listPosition)`.

Thumbnail-mode Folder Cards have a transparent interior, and Android's native
elevation shadow is tied to a `RenderNode` outline rather than to bitmap alpha.
Android documentation and AOSP source show that elevation draws the shadow from
the View's `Outline`, `clipToOutline` clips content rather than the shadow, and
there is no public API to ask the platform to draw only the exterior half of a
shadow. The overlay should therefore keep platform View elevation for the real
outer shadow, use an expanded overlay image view with a content inset, and set
an inset rounded `Outline` matching the real card content rect.

The internal shadow that appears behind transparent thumbnail Folder content
should be hidden by drawing the home/list background as a rounded rect inside
the content rect before drawing the captured transparent bitmap. This preserves
the platform elevation look outside the card outline without maintaining custom
stroke, gradient, or bitmap shadow approximations. Very tall thumbnail Folder
overlays must still avoid full-overlay software layers, full-size
`saveLayer(...)` cleanup, and single oversized texture uploads; the overlay
bitmap should be tile-drawn when it exceeds the safe tile size, so a single
oversized bitmap texture does not make the drag visual disappear.

The same principle applies to thumbnail-mode Folder Cards in the list, not only
to their drag overlay. They should keep ordinary Thing Card normal and dragging
elevation, but the CardView fill should use the home/list background colour
rather than transparent pixels or the Folder background. The content outline
can remain transparent-looking and use the Folder colour/gradient for its
stroke, while the opaque list-background fill hides the inner half of the
platform shadow.

All overlay drag geometry should continue to use the content card rect, not the
expanded shadow view bounds. Finger offsets, Folder-drop top-left hit testing,
create-Folder merge targets, reorder settle targets, and release-in-place
targets should therefore convert between outer overlay coordinates and the
inner content rect so the card content still aligns top-left to top-left and
bottom-right to bottom-right.

## 2026-06-19 - Drag overlay owns enlarged lift scale

The overlay drag bitmap should be captured from the source card's normal view
content, but the session overlay itself should render at the same enlarged
Moving-mode lift scale as the visible dragged card.

After the bitmap is captured and the overlay is attached, the real source
holder should immediately cancel its scale animation, clear any moving-scale
recovery token, reset `scaleX`/`scaleY` to `1f`, and remain only as a
transparent normal-size layout placeholder. Later reveal should therefore not
play a second ViewHolder shrink animation. The overlay should carry the
enlarged visual scale during drag, use the enlarged visible frame for pointer
offset and top-left Folder-drop hit testing, and animate down to the final
normal-size holder frame on reorder or release-in-place settle.

For successful reorder settle, the final source ViewHolder alpha should be
restored only after the overlay movement animation has ended and the overlay
view has been removed from the overlay parent.

While the overlay session is active, the transparent source placeholder must be
enforced on every RecyclerView pre-draw, not only when the source holder is
first hidden or reattached. Adapter rebinds such as `notifyItemMoved(...)`,
`notifyItemChanged(...)`, or delayed full-list rebinds may reset holder alpha to
`1f`; the overlay controller remains responsible for restoring the source
holder to transparent placeholder state before the frame is drawn.

That enforcement should not depend only on the source's current list position,
because RecyclerView pre-layout during `notifyItemMoved(...)` can temporarily
keep the source holder at its old slot while the data list already reports the
new slot. Bound Thing/Folder card roots therefore carry their stable business
id as a view tag, and the overlay controller scans visible children by that tag
to keep every currently attached source holder transparent. The placeholder
reset also applies to the inner card view that owns Moving-mode scale, not only
to the RecyclerView item root.

The overlay drag visual should carry the same elevated card shape as the
Moving-mode source card. Moving mode already raises selected normal Thing and
Folder Cards to `thing_card_dragging_elevation` while scaling them to `1.11f`.
Because Android elevation shadows are not reliably rasterized by
`View.draw(Canvas)` into the content bitmap, the overlay view itself should use
the dragging elevation plus the card corner outline so the shadow is rendered
with the enlarged moving card.

For reorder settle, the overlay must not resolve its final target from
RecyclerView's pre-layout state. After `notifyItemMoved(...)`, the data list may
already report the source at its new position while the transparent source
holder is still attached at the old slot for predictive layout. The overlay
settle animation should therefore wait until RecyclerView has no pending adapter
updates, is not computing layout, has no running item animator, the holder at
the source's final adapter position is bound to the same stable business id,
and that holder's layout rectangle is stable across consecutive frames.

Transparent placeholder enforcement must not cancel the root `itemView`
animation while a reorder settle is in progress. RecyclerView's item animator
uses that root view animation to move the transparent source holder from the old
slot to the final slot. The overlay controller may keep setting the root alpha
to `0f`, but only the inner card view's app-owned Moving-mode scale/elevation
recovery animation should be cancelled.

For overlay-owned reorder settle, RecyclerView should keep ownership of the
surrounding cards' item-move animation. The overlay controller should not force
an immediate full-list refresh to bypass the predictive layout gap, because
that removes the other cards' re-layout animation and can disturb scroll
position. Instead, the commit path should use `notifyItemMoved(...)`; the
overlay should wait until RecyclerView has had at least the item move duration
to start and finish, wait an additional short post-animation grace window, no
item animator is running, scrolling is idle, no adapter updates are pending,
and the final source holder rect is stable across multiple frames before
playing the dragged-card settle animation.

Because `StaggeredGridLayoutManager` keeps a lazy adapter-position-to-span
assignment cache, a moved item's holder rect can look stable before the final
gap-free span assignment has been recalculated. The overlay controller should
therefore request simple animations for the next layout and invalidate
StaggeredGrid span assignments immediately after the successful
`notifyItemMoved(...)` commit, before RecyclerView consumes that adapter update.
That lets the span correction coalesce into the same post-layout animation
pass. Once that final layout rect is available on pre-draw, the overlay should
start on the next animation frame with the same move duration, so the dragged
overlay and RecyclerView's final arrangement animate together.

For a successful drop into an existing Thing Folder, the commit overlay should
not fly into the target Folder Card center. Because the target Folder Card is
already visually highlighted as the receiver, the dragged overlay should keep
its release position, use a top-left pivot, and shrink directly to
`scaleX/scaleY=0`. RecyclerView remains responsible for the source removal and
gap-closing movement of the other cards through targeted adapter notifications.

The full-list rebind used to restore normal colours after exiting Moving mode
must be delayed until RecyclerView has consumed the targeted Folder-drop
adapter update, completed layout, and finished any running `ItemAnimator`
animations. Running that rebind while `notifyItemRemoved(...)` is still
producing move animations can cancel the remaining gap-closing animation and
make the list appear to jump.

When overlay reorder commits near the bottom of the staggered grid, SGLM's
predictive pre-layout can produce an intermediate post rect that still belongs
to the old span assignment. A visible target card may therefore animate
straight down to make room for the moved card, then jump sideways after a later
gap-correction or full-list rebind recalculates the final span. For the single
layout pass after an overlay reorder commit, `ThingsStaggeredLayoutManager`
should suppress predictive item animations while still requesting simple item
animations and clearing span assignments. RecyclerView can then record existing
visible child frames as the pre-layout information and animate them directly to
the final non-predictive SGLM span assignment.

## 2026-06-19 - Overlay release targets use layout-space card frames

Overlay drag release animations should align the overlay bitmap frame to the
final card holder's untransformed layout frame: top-left to top-left and
bottom-right to bottom-right.

The controller must not derive this frame by combining
`View.getLocationOnScreen()` with raw `width`/`height` when the card may still
have transient `scaleX`/`scaleY` from Moving-mode lift or recovery animations.
That mixes a transformed top-left with untransformed dimensions and can make
the overlay land offset from the final ViewHolder. Reorder and release-in-place
targets should therefore compute holder coordinates by walking layout
`left`/`top` values up to the overlay root while ignoring scale and, for final
settle targets, ignoring RecyclerView item-animation translations.

## 2026-06-19 - Reorder insertion line stays near the target card

The overlay-drag reorder insertion line should indicate the final target card
edge, not the geometric midpoint of an arbitrarily large masonry gap.

For an insert-before candidate, draw the line a small fixed distance above the
target card. For an insert-after candidate, draw it a small fixed distance
below the target card. If another visible card in the same horizontal span
leaves too little space, the line may be clamped toward the local gap midpoint
so it does not overlap either card. Large gaps created by tall Things or
Folders should not pull the line far away from the target card.

The insertion line thickness should be specified in dp, not raw pixels, so it
has the same visual weight across different display densities.

## 2026-06-18 - Overlay reorder release settles with RecyclerView move animation

When an overlay drag session commits an ordinary reorder, RecyclerView should
own the surrounding card rearrangement and the session overlay should remain
the visible dragged card until the moved source reaches its final layout slot.

On release, the commit path should perform the data move plus
`notifyItemMoved(...)`, keep the real source holder transparent as the layout
placeholder, wait for RecyclerView to lay out the moved source in its final
slot, and then animate the overlay from the release position to that final
source holder layout rectangle. The overlay settle duration should match the
RecyclerView item move duration so the gap-making animation and the dragged
card landing animation read as one coordinated motion.

The source holder should be revealed only after the overlay settle finishes,
with transient drag scale and tags reset before it becomes visible. If the
final source holder cannot be resolved after layout retries, the overlay may
fade out and request a targeted source rebind as a recovery path rather than
committing another reorder or forcing a scroll back to the original source.

The overlay snapshot may include the card's long-press Moving-mode enlarged
scale. Its release animation should therefore shrink the overlay back toward
the normal card scale while it lands, and should wait until RecyclerView item
animations are finished before revealing the real source holder. Revealing the
holder while it still has a running move translation, or while the overlay is
still visually enlarged, can create a final-frame jitter.

Successful reorder release should reveal the source holder resolved by stable
Thing/Folder identity after the final layout and item-animation cleanup, not
blindly restore the original `sourceView` reference captured at drag start.
That original holder reference may be stale after a move, and restoring its
alpha too early can show the real card underneath the overlay for one frame.

The reorder overlay's final target is the moved source holder's final
`itemView` layout rectangle as a bitmap frame. The lift-time bitmap may already
contain any long-press card scale inside that frame, so the controller should
not divide the whole overlay frame by the captured card scale when calculating
the final `x`/`y`. Mixing the inner card scale into the outer bitmap-frame
target can make the overlay land with a visible coordinate offset.

Any full-list rebind used only to restore cards from Moving-mode colours after
an overlay reorder must wait until the overlay drag session is no longer active.
A delayed `notifyDataSetChanged()` that runs while the overlay is still settling
can reveal the real moved card underneath the overlay and make RecyclerView's
rearrangement look like a separate jump.

When a dragged card returns to its original or equivalent list position, the
release should still animate the overlay back to the original source slot
before entering selecting mode. Returning to the original position is not a
fade-out case; it should preserve the same spatial continuity as a successful
reorder settle.

## 2026-06-15 - Initial requested product shape

Thing Folders will be created from the home list by dragging one Thing onto
another after long-pressing a Thing. A Thing Folder can be named, appears on the
home list as a Folder Card, supports nested folders, shows its path in the home
header when opened, and is referenced from the Detail screen of contained
Things.

The initial request included showing created folders in the drawer. That part
is superseded by the later drawer decision below.

## 2026-06-15 - Domain term

Use **Thing Folder** in docs and code-facing product language, not "note
folder", because the project glossary defines **Thing** as the cross-type item
that can be a note, reminder, habit, goal, task, or related item.

## 2026-06-15 - Thing Folder membership is a strict tree

Thing Folder membership is a strict tree. A Thing may belong to at most one
Thing Folder, and a Thing Folder may have at most one parent Thing Folder. The
feature will not implement multi-parent aliases, shortcuts, or tag-like shared
membership.

## 2026-06-15 - Thing Folders are list projections, not drawer items

Superseded by the 2026-06-17 Drawer folder tree decision below.

Thing Folders are not shown as drawer items. The drawer stays focused on
built-in global destinations such as Underway, Notes, Reminders, Habits, Goals,
Finished, and Deleted.

The current list state is a built-in drawer destination plus an optional Thing
Folder Path projection. Opening a Thing Folder Card keeps the current built-in
drawer destination selected and adds or extends the folder path projection.
Selecting any built-in drawer destination resets the folder path projection and
opens that built-in destination at its root.

Header text shows the built-in destination at root and shows the projected path
when inside folders, for example `Finished / Folder / Child Folder`.

## 2026-06-17 - Drawer shows the Underway Folder tree

The Drawer should show the non-deleted Thing Folder tree directly under the
Underway item and above the Note item. Underway acts as the root directory.

The first Folder level is always visible because the Underway root is always
expanded. Deeper Folder levels are shown only when their parent Folder is
expanded. Folders with child Folders show a trailing dropdown affordance; tapping
that affordance toggles expansion without changing the selected destination.

Tapping a Folder row opens that Folder within the Underway projection. The
Drawer has one checked item at a time: an opened Folder row, Underway root, or
one of the other built-in destinations.

Folder hierarchy is represented by a small indentation that begins at the
Folder icon. Folder icons should use each Folder's own background, supporting
both pure colours and gradients. The Note group starts after a separator below
the Folder tree.

## 2026-06-18 - Home Drawer uses an app-owned navigation view

The home Drawer no longer relies on Material `NavigationView` for its item
rows. Folder tree requirements need precise control over indentation, title
width, trailing expand/collapse affordances, row animation, selected state, and
view recycling. The home Drawer should use the app-owned
`DrawerNavigationView`, backed by typed rows and a RecyclerView adapter, while
continuing to reuse the existing `DrawerHeader`, `DrawerLayout`, and toolbar
toggle behavior.

## 2026-06-15 - Folder Card counts are recursive and include private Things

The count shown on a Thing Folder Card is a recursive descendant Thing count,
not only a direct-child count. It includes Things inside nested Thing Folders.

Private Things are included in the count even when private content is hidden.
This matches the existing home header count behavior, which counts Things by
type and state without filtering out private Things. Folder thumbnails and
inline previews must still avoid exposing private content when private content
is hidden.

## 2026-06-15 - Finished and deleted Things preserve folder membership

Finished and deleted Things keep their Thing Folder membership. The Finished
and Deleted built-in destinations should show corresponding Thing Folder Cards
when a folder contains descendant Things matching that destination.

Restoring a deleted Thing should return it to the same folder membership it had
before deletion unless the containing folder has itself been permanently
removed in a later feature slice.

## 2026-06-15 - Folder Cards open within the active built-in destination

When a Thing Folder Card appears inside a built-in destination, tapping its
non-thumbnail area opens that folder as a projection within the same built-in
destination. For example, tapping a Folder Card in Finished opens the folder's
Finished projection and keeps Finished selected in the drawer. Tapping a Folder
Card in Deleted opens the folder's Deleted projection and keeps Deleted
selected in the drawer.

## 2026-06-15 - Built-in destinations show matching Folder Cards

Every built-in destination can show Thing Folder Cards. Underway, Notes,
Reminders, Habits, Goals, Finished, and Deleted should each show a Folder Card
when that folder recursively contains Things matching the active built-in
destination.

The Folder Card count is computed using the active built-in destination's
matching descendants. Opening that Folder Card keeps the active built-in
destination selected and opens the matching folder projection.

## 2026-06-15 - Folders support sticky, mixed ordering, and privacy

Thing Folders support sticky placement, manual ordering, and privacy.

Folder Cards and Thing Cards share the same mixed ordering space inside a root
or folder projection. Users can reorder Folder Cards among Thing Cards.

Sticky Folder Cards participate in the same top-of-list sticky area as sticky
Thing Cards. A sticky Folder Card stays sticky as a folder property, not only in
one built-in projection.

Thing Folders can be private. Private Folder Cards protect access and contained
previews when private content is hidden. The original hidden-title behavior is
superseded by the 2026-06-17 Private Folder Card title decision below.

## 2026-06-15 - Private folder privacy inherits for display and access

A Private Thing Folder makes all descendant Things and child folders effectively
private for display and access while they remain inside that folder. This
effective privacy protects list cards, thumbnails, search results, path display,
and folder navigation.

Effective inherited privacy does not rewrite a descendant's own stored private
state. If a non-private Thing is moved out of a Private Thing Folder, it becomes
non-private again unless it is private by its own stored title/state.

## 2026-06-16 - Authenticated private folder scope reveals descendants

Opening a Private Thing Folder after authentication creates an authenticated
folder scope for that open projection. Within that scope, descendant Things and
child folders are displayed normally, including real folder names and normal
Thing/Folder cards, because the user has already authenticated to enter the
private container.

The authenticated folder scope is projection-specific rather than a global
"show all private content" mode. Private content outside the opened private
folder remains protected unless the existing global private-content flow has
enabled it.

Setting a Thing Folder private requires the app private password to exist, using
the same prerequisite as setting a Thing private.

## 2026-06-16 - Folder drag hardening avoids Adapter stable ids

Do not enable RecyclerView Adapter stable ids as part of Folder-drop animation
hardening. The project has previously seen stable-id changes interact badly
with mixed projections, broad `notifyDataSetChanged()` rebinds, ViewHolder
visual state, and RecyclerView gesture animations.

Folder drag state may still use stable business ids such as `sourceThingId`,
`targetThingId`, and `targetFolderId` internally. That identity should remain a
drag-session invariant, not an Adapter stable-id contract.

## 2026-06-16 - Active list gestures own temporary z-order

During active Thing-list swipe or drag gestures, the touched card should remain
above all sibling Thing and Folder Cards. The gesture layer owns this temporary
z-order through transient `translationZ`; normal `cardElevation` remains owned
by card touch, selection, moving-mode, and Folder-drop feedback animations.

The temporary z-order must be reset in `ItemTouchHelper.clearView(...)` and
when ViewHolders are rebound, so recycled cards do not keep an old gesture
layer.

## 2026-06-17 - Folder naming uses app DialogFragment semantics

Thing Folder creation and rename naming prompts use a custom app
DialogFragment instead of a platform-default AlertDialog. The dialog adapts its
title, confirm button, and EditText focus treatment to the Folder background,
including gradient-aware text and underline drawing.

Canceling the naming dialog that appears after drag-creating a Folder cancels
the creation itself. The two source Things are reparented back to the Folder's
original parent projection, and only the newly-created Folder record is removed.
This rollback path must not use permanent Folder deletion because that operation
deletes contained Things.

## 2026-06-17 - Thumbnail Folder Cards use interaction-stripped Thing Card previews

Thumbnail-mode Thing Folder Cards should render child previews that stay close
to complete Thing Card presentation, rather than a title/content-only
substitute. The child preview is compacted through content constraints and may
simplify unusually dense type-specific surfaces, but it should preserve the key
visible Thing Card semantics such as empty-title handling, checklist previews,
and image or video Thing Card Media.

Child previews support only tapping to open the child Thing. Nested checklist
toggles, long-press actions, selection, dragging, and other Thing Card
interactions are not active inside Folder Card previews.

For Things with image or video media, child previews strictly reuse Thing Card
Media presentation, including the selected source, crop, target aspect ratio,
video frame, image placement, media background presentation, and full-span Thing
Card presentation where applicable. Media-heavy child previews may use more
vertical space than text-only previews, but the preview should avoid hard
clipping and instead stay compact through preview-specific constraints such as
text max-lines, smaller typography, checklist item limits, simplified Habit
detail, and media target sizing.

Inside a full-span thumbnail-mode Folder Card, a full-span child Thing preview
also spans the full preview width rather than being squeezed into one masonry
column.

Inside a normal-span thumbnail-mode Folder Card, a full-span child Thing preview
cannot become wider than the Folder Card, but it still keeps full-span Thing
Card internal presentation within that one-column preview.

Habit child previews keep the core Habit summary but may omit dense Habit
record details, such as the last-five-record surface, inside constrained Folder
Card previews.

Normal-span Folder Cards show a one-column preview list capped at three Things.
Full-span Folder Cards show a three-column masonry preview capped at six
Things. Both surfaces show a small bottom ellipsis when additional matching
descendants are not rendered. The ellipsis is an explicit Folder-open target
and should stay visually compact, similar in footprint to the checklist
"more items" ellipsis.

## 2026-06-17 - Thumbnail child previews scale rendered card chrome

Thumbnail child previews continue to bind through the real Thing Card or Folder
Card paths first, then apply a preview-only visual scale to the rendered child
card tree. This post-bind scale covers card chrome that does not flow through
content-specific preview hooks, including titles, folder icons, media/audio
count labels and icons, reminder/habit/goal timing labels and icons, private,
sticky, and doing indicators, and TextView compound drawables. Checklist
preview rows also receive preview-specific text sizing and icon scaling through
their own adapter because their row views can be created inside the nested
RecyclerView path.

The post-bind scale is not a replacement for content policy. Preview-specific
hooks still own content max-lines, checklist item limits and text size, Habit
detail simplification, media surface sizing, nested-interaction stripping, and
child Folder summary-mode forcing.

The preview scale applies separately to spacing and to visual content. Internal
padding, margins, and fixed bottom spacer heights are compacted so the child
card does not keep ordinary list-card whitespace, but the same spacing scale
should be used for ordinary vertical top/bottom gaps to avoid flipping which
side looks larger. Actual Thing Card Media surfaces are excluded from icon
scaling so side media panels and media backgrounds remain edge-to-edge, but
their container margins still participate in thumbnail spacing compaction.

Folder thumbnail child previews preserve the ordinary dynamic content text-size
relationship before applying thumbnail limits. The preview may clamp the
computed size so short content remains larger than long content without
becoming full-size ordinary-list typography.

Because preview compaction changes the final rendered target size after the
normal Thing Card bind path has run, Folder thumbnail child previews reapply
Thing Card Media crop once the preview card is posted with its compacted
dimensions. The crop reapply path must preserve the media presentation kind and
prefer the current rendered media target size when available: side media uses
side-panel crop, foreground thumbnails use thumbnail crop, and media
backgrounds use media-background crop.

Child preview adapters reuse the parent list adapter's Thing Card Media bitmap
cache. This keeps the existing media-load cache effective for Folder thumbnail
previews, even though each child preview is bound through a constrained
adapter.

Thumbnail preview containers allow child-card shadow overflow by disabling
parent clipping. If a child preview shadow looks clipped, prefer fixing the
container clipping boundary over lowering preview elevation again.

## 2026-06-17 - Nested Folder previews use direct child entries

Thumbnail-mode Folder Cards preview direct child entries, not a flattened list
of all recursive descendant Things. Direct child Folders that match the current
projection appear in the preview as summary-mode Folder Cards, and tapping such
a child Folder preview opens that Folder through the same privacy/authentication
path as an ordinary Folder Card.

The Folder Card count label combines direct child Folder count and recursive
matching Thing count. In Chinese this is `X个文件夹，Y件记事`; if either count is
zero, omit that segment.

Child preview cards inside thumbnail-mode Folder Cards use reduced elevation so
their shadows fit the existing compact preview spacing. The compact ellipsis
does not reserve a large bottom margin.

## 2026-06-17 - Thumbnail preview spacing and media crop ratios

Thumbnail-mode Folder Cards use a fixed 12dp gap between the Folder count label
and the first child preview, matching the spacing that previously felt correct
on full-span Folder Cards. Child preview cards use a 7dp vertical item gap in
both normal-span one-column previews and full-span masonry previews. Masonry
rows own the first-child top gap so a row margin and a child margin do not add
up into a doubled gap.

Folder thumbnail child previews must preserve the saved Thing Card Media crop
ratio in addition to crop center and user scale. Foreground thumbnails and
side-panel media use `ThingCardThumbnailCrop.sourceAspectRatio`; media
background previews use the source presentation's media-background target
aspect ratio. This applies to images and video frames.

## 2026-06-17 - Moves insert first and empty folders are removed

When a Thing or Thing Folder moves into a different Thing Folder, moves back to
its previous parent, or moves back to root, the moved entry becomes the first
item in that target root or target Folder's corresponding sticky or non-sticky
section, preserving the entry's existing sticky state. The move should not
preserve the entry's old relative order from its source container.

After a move leaves a Thing Folder with no direct child Things and no direct
child Thing Folders, the now-empty Folder is deleted automatically.

## 2026-06-17 - Private Folder Cards keep visible titles

Private Thing Folder Cards still show the Folder's stored title when private
content is hidden. The card may keep a lock indicator and must continue to hide
contained thumbnail previews until authentication, but the Folder name itself is
not treated as protected card content.

## 2026-06-17 - Folder long-press uses drag and selection mode

Long-pressing a Folder Card should no longer open a multi-action dialog. It
should mirror the Thing Card long-press interaction: the Folder can be dragged,
dropped into another Folder, or released back in place to enter selecting mode.

Folder sticky state should be controlled through the existing contextual
selection menu. Folder card appearance should reuse the selection menu's
appearance action, with Folder-specific labels and controls.

Private Folder toggling should be reachable from both the current Folder
projection overflow menu and the selection contextual menu. The same privacy
entry should also be available for Thing Cards so the privacy affordance stays
consistent between Things and Folders.

Folder dissolve and delete actions should be available from the selection
contextual menu and the current Folder overflow menu. Both operations must
confirm through the app's custom DialogFragment surface before mutating data.

Outside Deleted, deleting a Folder moves the Folder and its contained subtree
into the Deleted state. Inside Deleted, the corresponding action text and
operation become permanent delete, which recursively destroys the Folder subtree
and contained Things.

## 2026-06-18 - Full-session drag overlay owns moving visuals

The next hardening pass for long Thing and Folder drags should treat the
session overlay as the single moving visual authority from active drag start
until release or cancellation. The RecyclerView child should no longer be the
live moving card during the drag session; the list should instead provide
layout feedback, hover/drop candidates, auto-scroll, and final mutation
commit.

This supersedes the narrower Folder-drop commit overlay model for the active
drag visual. The existing commit overlay captured only the final Folder-drop
animation and still depended on ItemTouchHelper moving the real child during
the drag. A full-session overlay is intended to decouple the finger-following
visual from ViewHolder detach/recycle behavior during long auto-scrolling
drags through large cards and folders.

The overlay drag session must preserve the existing Thing and Folder drag
feature surface: dragging either a Thing Card or a Folder Card, release-in-place
selection-mode entry, creating a new Folder by dropping a Thing onto another
Thing, adding/moving Things or Folders into an existing Folder, and ordinary
reordering.

During an active overlay drag session, the list should not mutate the
underlying order with live `move(...)` calls or `notifyItemMoved(...)` events.
It should keep the original list as the data source, render only visual
candidate feedback such as insertion position and Folder-drop hover state, and
commit the final reorder or Folder-drop mutation once the pointer is released.

Thing and Folder Card drag sessions should no longer be owned by
`ItemTouchHelper.startDrag(...)`. ItemTouchHelper may continue to support other
list gestures such as swipe, but long-press drag should move to an app-owned
drag session controller that tracks pointer movement, overlay position,
auto-scroll, candidate targets, cancellation, release, and final commit.

On pointer release, the overlay drag session should resolve outcomes in this
order: valid Folder drop, valid reorder, release-in-place selection-mode entry,
then cancellation/interruption cleanup. Moving away and returning to the
original position should be treated as release-in-place and enter selection
mode. Cancellation paths such as `ACTION_CANCEL`, window focus loss, activity
pause, or data-source invalidation should cleanly restore the list without
committing Folder drop, committing reorder, or entering selection mode.

Release-in-place selection should select the original source object by stable
business id, not by the release point or a stale adapter position. A Thing drag
selects the original Thing id; a Folder drag selects the original Folder id. If
the current mixed-list position is available, the session should enter
selection mode at that position. If the object still exists but is not
currently visible, selection state should still be restored by id and surfaced
through rebind rather than selecting another visible card.

The overlay rewrite must preserve existing visible interaction animation
quality rather than replacing all effects with abrupt state changes. Drag start
lift/scale, finger-following motion, Folder-drop hover feedback, commit
animation, reorder settlement, selection-mode entry, and cancellation/recovery
should keep animation ownership explicit so recycled ViewHolders do not keep
stale scale, alpha, outline, elevation, or selection tint.

During an active overlay drag session, the real source card should remain in
the RecyclerView only as a layout placeholder and should be fully transparent,
not dimmed or partially visible. This prevents the user from seeing two copies
of the same Thing or Folder Card while preserving masonry/list layout stability
around large source cards. The transparent placeholder must be non-interactive
and all visibility, alpha, scale, elevation, outline, and selection-tint state
must be restored on release, cancellation, data invalidation, and ViewHolder
rebinding.

The full-session drag overlay should initially render from a full-size bitmap
snapshot of the source card captured at drag start. The overlay should not
construct and bind a second live Thing or Folder Card view during the drag. A
bitmap keeps the drag visual identical to the source card at lift time while
avoiding duplicate adapter state, nested preview interactions, media loading,
and live child-view state during the session. The first implementation should
not downsample large source cards; if capture fails or the source view has an
invalid size, the session should not start.

The overlay snapshot should copy only the card state already visible on screen.
It should not reveal hidden private content, force authentication, expand
protected Folder previews, or otherwise alter privacy/authenticated projection
semantics. A hidden private card should drag as its protected visible card; an
authenticated visible card should drag as currently rendered.

Folder-drop candidates during the overlay drag session should continue to use
the dragged overlay card's top-left corner: a Folder drop is considered only
when that top-left point is inside an existing eligible Thing or Folder Card.
The overlay should preserve the lift-time finger-to-card offset so this
top-left hit-test matches the existing drag behavior. Folder-drop
candidates should still require a short stable hover before arming, so moving
quickly across a valid Thing or Folder target does not accidentally create or
enter a Folder-drop state.

Pointer location should still drive finger tracking, edge-zone auto-scroll,
and ordinary reorder candidate calculation. Folder-drop hit-testing is the
exception because it is based on where the dragged card enters the target card.

The first overlay-drag reorder feedback should be an insertion line rather
than live card displacement. The insertion line should use the dragged Thing or
Folder Card's own `ThingBackground`, rendering a solid line for pure colours
and a gradient line for gradient backgrounds.

The insertion line should attach to the visible edge of the candidate card
rather than trying to preview the full future staggered-grid layout. Inserting
before a visible candidate draws the line at that card's top edge; inserting
after a visible candidate draws it at that card's bottom edge. The line width
should match the candidate card width, or span the content width for full-span
positions. If the candidate edge is off-screen, the session should temporarily
hide the insertion line and continue dragging/auto-scrolling. Folder-drop hover
feedback should hide the reorder insertion line while armed.

Ordinary reorder candidates should be computed from the pointer's current
relationship to visible cards. A pointer inside the upper half of a card means
insert before that card; a pointer inside the lower half means insert after
that card. A pointer in visible whitespace should use the nearest visible card
edge. The transparent source placeholder and the header/list position `0`
should not be valid reorder targets. Folder-drop hover state should suspend
reorder candidate updates while armed.

Final reorder commits should derive the mutation once at release time from the
stable source id plus the final target id and before/after relationship. The
commit path should resolve current source and target list positions, adjust for
the source removal when the source originally appears before the target, and
then perform one data move plus one list update. Returning to the original or
an equivalent position should enter selection mode instead of committing a
move. The drag session should not carry forward accumulated `from`/`to`
positions from intermediate drag frames.

Reorder candidates may retain the most recent target stable id plus
before/after relationship while that target edge is temporarily off-screen, but
the insertion line should be hidden when no visible target edge can anchor it.
Release-time reorder must resolve the retained target id against the current
mixed list again; if the target can no longer be found or no longer accepts the
source move, the session must not commit a stale reorder.

Folder-drop hover animations should keep the existing visual language while
moving lifecycle ownership into the overlay drag session controller. Creating a
Folder from two Things should keep the target-card shrink plus pending Folder
outline feedback. Dropping a Thing or Folder into an existing Folder should
keep the Folder-card shrink, outline, and content-alpha feedback. Entering,
leaving, canceling, and committing hover state should be centralized so every
target touched during the session can animate back cleanly without stale scale,
outline, alpha, background, or selection tint.

Folder-drop armed state should require a currently visible target. If the
target holder scrolls off-screen, is detached or recycled, or the overlay
top-left point no longer remains inside that target, the session should leave
the Folder-drop hover state and animate the target feedback back. Releasing
over an invisible or stale target must not commit a Folder drop.

Release animations should reuse the active session overlay instead of taking a
new post-release snapshot. Folder-drop commits should animate the session
overlay into the target Thing or Folder Card before/while the list reflects the
merge. Reorder commits should settle the overlay toward the final insertion
edge before the list update reveals the moved source in its final position.
Release-in-place selection should animate the overlay back to the transparent
source placeholder and then reveal the real card in selection mode. Cancellation
should restore or fade the overlay back without committing data or entering
selection mode.

Release and cancellation cleanup must not depend on the original source
ViewHolder still being attached. If the source placeholder is still visible,
the session may animate the overlay back and restore that holder directly. If
the source holder has been detached or recycled, cleanup should resolve the
source by stable business id and use targeted rebind/list refresh to clear any
transparent placeholder state. The session should not force-scroll back to the
source merely to play a recovery animation.

Long-press drag startup should keep the current product rhythm: a confirmed
long press enters Moving mode, then starts the overlay drag session only if the
pointer is still active and the source holder can produce the initial bitmap
snapshot. If the user has already released by the startup check, the interaction
should enter selection mode rather than creating a drag session. If the source
holder is unavailable or cannot be captured, the code should not start a partial
session.

An active overlay drag session should be an exclusive gesture. It should track
only the pointer that started the drag, pause card clicks, nested long-presses,
swipe handling, and drawer gestures for the session, and use one cleanup path
for pointer-up, cancellation, Activity pause, and session invalidation.
ItemTouchHelper should remain the owner of ordinary Thing Card swipe behavior,
but it should no longer own Thing/Folder Card drag behavior or receive swipe
control while an overlay drag session is active.

The Things list ItemTouchHelper should expose only swipe flags after the
overlay drag rewrite. Drag flags should be zero for Thing and Folder Cards so
future code cannot accidentally re-enter the old `startDrag(...)` path.

The overlay drag controller should live outside `ThingsActivity.kt` in a new
focused Kotlin file because the Activity is already too large. The extraction
should keep the drag-session state, overlay rendering, auto-scroll, insertion
line, and release/cancel coordination in the controller while using a narrow
host contract back into `ThingsActivity` for existing business operations,
adapter updates, mode transitions, and Folder-drop helpers.

The extracted controller should communicate with `ThingsActivity` through a
small explicit Host interface instead of directly depending on broad Activity
internals. The Host should expose only the operations required for the drag
session, such as current mixed-list lookup, source/target validation, hover
feedback hooks, final reorder/drop/selection/cancel commits, mode transitions,
and access to the RecyclerView plus overlay parent. The controller should not
become a second Activity-shaped class with copied managers and broad private
state access.

While an overlay drag session is active, `ThingsActivity.dispatchTouchEvent(...)`
should be the top-level event source for drag move, release, and cancellation.
Inactive sessions should not intercept normal event dispatch. Once active, the
controller should consume the tracked pointer's move/up/cancel events before
they reach child item views or ItemTouchHelper, so release can still be observed
when the pointer moves outside the RecyclerView's bounds.

Auto-scroll during the overlay drag session should be owned by the app-owned
drag session controller rather than by ItemTouchHelper's out-of-bounds drag
scrolling. Edge-zone scrolling should have a bounded speed, recompute
pointer-based Folder-drop and reorder candidates after scroll movement, keep
the overlay tied to the current pointer position, and continue the session even
when the original or candidate ViewHolder is detached or recycled.

Starting an overlay drag session should stop any existing RecyclerView fling or
ordinary scroll. While the session is active, the only allowed list scrolling
source should be the drag controller's own edge-zone auto-scroll. Normal finger
scrolling, inertial scrolling, nested scrolling, and semantic external scrolls
should not run concurrently with the drag session. External semantic scrolls or
list refreshes should cancel the session before they change the list, while
controller-owned auto-scroll must not cancel merely because ViewHolders detach
or recycle.

Overlay drag sessions should treat stable business identity as authoritative
and list positions as derived frame-local values. The session should record the
source kind, source Thing or Folder id, original mixed-list position, source
background, and relevant projection/container context at drag start. Candidate
positions and targets should be recomputed from the current list and pointer
location on each frame. Release-time commits should revalidate source and
target identities before mutating data, so a stale adapter position cannot move
or merge the wrong Thing or Folder after long scrolling, rebinding, or list
refresh.

External changes that alter the current mixed-list semantics should cancel the
overlay drag session instead of committing or entering selection mode. Examples
include search/projection/drawer-destination changes, folder-path changes,
mode-level changes not owned by the session, Activity pause, private-content
visibility changes, and adapter refreshes caused by unrelated data mutations.
Pure view-layer churn such as scrolling, ViewHolder recycle/detach, and item
animation completion should not cancel the session.

The overlay drag implementation may keep file-backed debug logging behind a
feature flag that defaults to disabled. Logs should use the generic
`DebugFileLogger` path and record session-level events such as start metadata,
candidate changes, Folder hover enter/armed/leave, auto-scroll transitions,
release outcome, cleanup state, and invalidation cancellation reason. The log
should not emit every move frame by default.

The overlay drag rewrite should replace the old ItemTouchHelper drag path
directly instead of keeping a feature-flagged fallback. Once the overlay session
owns Thing/Folder drag, obsolete ItemTouchHelper drag state and callbacks should
be removed or reduced to swipe-only code rather than maintained as a second
drag implementation.

After reviewing modern Android alternatives, the full-session overlay drag
should remain a custom View-based controller rather than adopting platform
`startDragAndDrop(...)`, Jetpack `DropHelper`, or Compose drag-and-drop APIs.
ItemTouchHelper's documented drag model still moves the existing ViewHolder and
can end early when that ViewHolder leaves the layout, which is the failure mode
being fixed. Platform and Compose drag-and-drop APIs are oriented around
ClipData-style data transfer and drop targets rather than this app's mixed
RecyclerView reorder, Folder-drop, release-in-place selection, custom
auto-scroll, and animation requirements. The implementation should therefore
use ordinary View touch handling plus an overlay bitmap visual, while keeping
ItemTouchHelper for swipe only.

## 2026-06-17 - Private Folder Cards hide counts

Private Thing Folder Cards should keep the stored Folder title visible while
private content is hidden, but they should not show child Thing or child Folder
counts. The protected card should keep the Folder icon beside the title and
show a lock indicator below the title area instead of count text.

## 2026-06-15 - Deleting a folder moves the folder subtree to Deleted

Deleting a Thing Folder moves that folder to the Deleted destination while
preserving the folder subtree and all Thing Folder memberships. The deleted
folder appears as a Folder Card in Deleted, and opening it shows that subtree's
deleted projection.

Folder deletion is modeled as folder state, not as immediately rewriting every
descendant Thing's stored state. Descendants become effectively deleted for
display/navigation while they remain inside a Deleted Thing Folder. Restoring
the deleted folder restores the folder structure and makes descendants visible
again according to their own stored states.

Permanent deletion is the operation that actually destroys folder records. It
must clean up contained deleted Things and child folders through the selected
permanent-delete flow.

## 2026-06-15 - Permanent folder deletion deletes the subtree and contents

Permanently deleting a Deleted Thing Folder permanently deletes the entire
folder subtree and its contained Things, including descendants that are only
effectively deleted because of the deleted ancestor folder.

The permanent-delete action should not restore, ungroup, or reparent those
descendants first. From the user's point of view, permanently deleting a folder
from Deleted means permanently deleting that container and everything inside it.

## 2026-06-15 - List widgets do not render folders in v1

Things-list widgets should not render Thing Folder Cards in the first
implementation. They should keep the current Thing-based behavior and remain
compile-safe.

Folder-aware RemoteViews rendering, folder projection intents, private folder
handling, and deleted-folder handling are deferred to a later widget-specific
slice.

## 2026-06-20 - Widget Folder follow-up polish

Single-Thing AppWidget configuration should reuse the home-list `ThingsAdapter`
Thing binding for selectable Thing rows rather than maintaining a separate
`BaseThingsAdapter` approximation. The configuration layer may still provide
its own data source, click behavior, and Folder-auth state, but media placement,
media backgrounds, crop geometry, private Thing presentation, sticky markers,
and ordinary Thing Card layout should come from the shared home card path.

Things-list AppWidget Grid mode should bind click fill-in intents to each
visible grid slot container. The row RemoteViews object exists only to pack
multiple cards into one collection item; it should not decide which Thing or
Folder opens after a tap.

AppWidget alpha should affect media-backed Thing Cards across RemoteViews.
Foreground thumbnails, side media panels, and media-background cards should use
the corresponding RemoteViews `ImageView` alpha so preview updates and launcher
updates follow the same path, while pure-colour and gradient cards can continue
to render their background bitmap with alpha baked in.

## 2026-06-20 - Single-Thing widget configuration parity

Single-Thing AppWidget configuration card delegates must be bound to the
configuration RecyclerView as their host when they are used through the mixed
Thing/Folder adapter. Home-card media sizing depends on the host RecyclerView
width, so delegate binding must not rely only on `onAttachedToRecyclerView`.

Single-Thing AppWidget preview should remain a `RemoteViews` preview instead of
switching to an in-app `card_thing` rendering path. Rounded preview clipping
should be fixed at the widget root by combining a rounded background outline,
`clipToOutline`, and the API 31+ RemoteViews outline-radius action where
available.

Large Folder previews inside the Single-Thing AppWidget configuration should
keep home-card visuals while exposing configuration-specific taps: tapping a
Thing thumbnail selects that Thing for preview, and tapping a Folder thumbnail
opens that Folder.
