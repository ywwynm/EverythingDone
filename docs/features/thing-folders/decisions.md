# Thing Folders Decisions

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
