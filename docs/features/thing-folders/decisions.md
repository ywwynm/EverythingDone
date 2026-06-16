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

Thing Folders can be private. Private Folder Cards must not expose protected
folder identity or contained previews when private content is hidden.

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
