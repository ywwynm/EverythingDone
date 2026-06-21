# Home Empty State - Decisions

## 2026-06-21 - Remove stored welcome and empty placeholders

`WELCOME_*` and `NOTIFY_EMPTY_*` rows are legacy placeholder Things, not
user-owned content. The new home empty-state design should stop inserting them
for new databases and should remove existing legacy placeholder rows from old
databases instead of merely hiding them.

This keeps list projections, ActivityHeader counts, Drawer counts, search,
widgets, Detail entry points, export-like flows, and future empty-state logic
from continuing to treat obsolete guidance content as real Things.

## 2026-06-21 - Track first real Thing creation by concrete type

The home empty-state distinction between first-use guidance and ordinary empty
guidance is tracked per concrete real Thing type: `NOTE`, `REMINDER`, `HABIT`,
and `GOAL`. Once the user creates a real Thing of a concrete type through any
entry point, that type is considered created forever, even if all Things of that
type are later finished, deleted, permanently deleted, or moved into a Thing
Folder.

Legacy placeholder Things, notification pseudo-things, the header row, and
Thing Folders do not count as real Thing creation for this purpose.

## 2026-06-21 - Empty Thing Folders are valid user content

Structurally empty Thing Folders are valid user-owned containers. Moving Things
or child Thing Folders out of a Thing Folder should no longer automatically
delete the now-empty Folder, and the product should allow users to create empty
Thing Folders.

The home empty-state design must therefore distinguish an empty current Folder
view from a missing or invalid Folder. An Empty Thing Folder remains valid data,
but state/type-filtered list projections may hide its parent Folder Card when
the Folder subtree has no Things matching the current projection; opening that
Folder through an existing navigation context can still produce a child-list
empty state.

## 2026-06-21 - Opened Empty Thing Folders use Folder-specific guidance

When the current projection is inside an Empty Thing Folder, the child list
should use Folder-specific Empty-List Guidance rather than first-use welcome
guidance or operation-result empty guidance. The Folder remains real user-owned
content in its parent projection, but its opened child list may still be empty.

This creates four distinct home empty-state families:

- first-use empty guidance for a concrete Thing type before the user has ever
  created that type;
- operation-result empty guidance after a user operation just emptied the
  current projection;
- ordinary projection empty guidance after the user has already created the
  relevant concrete Thing type;
- Folder-specific empty guidance for an opened Empty Thing Folder.

## 2026-06-21 - Operation-result empty guidance is transient per projection

Operation-result empty guidance is an in-memory, one-shot event for the current
Activity and current list projection. It is not stored in the database or
SharedPreferences.

When a user operation such as finishing, deleting, moving a Thing, moving a
Folder, deleting a Folder, or dissolving a Folder changes the current projection
from non-empty to empty, the current screen should immediately show the
operation-result empty guidance that corresponds to the old `NOTIFY_EMPTY_*`
message family.

That transient state ends when the user changes status, type filter, current
Folder path, search text, or color filter; when the Activity cold-starts or is
recreated; when content is created, restored, or moved back into the current
projection; or when a fresh projection load replaces the current list state.
Undo only keeps the operation-result identity if the projection remains empty;
if undo restores visible content, the guidance hides.

## 2026-06-21 - Search and color empty results stay separate

Search text and color filtering keep their existing no-result semantics. A
search or color-filtered projection that has no matches should show search
no-result UI, not first-use guidance, operation-result guidance, ordinary
projection guidance, or Folder-specific guidance.

Home Empty State guidance applies only to ordinary home projections without an
active search query or color filter.

## 2026-06-21 - Welcome guidance only covers first-use entry points

Welcome guidance uses the existing welcome string family, but only for
first-use ordinary UNDERWAY projections:

- UNDERWAY + all types + root + no real Things and no Thing Folders ever
  created shows `welcome_underway_title` and `welcome_underway_content`.
- UNDERWAY + a single concrete type filter + that concrete type has never been
  created shows the corresponding `welcome_note_content`,
  `welcome_reminder_content`, `welcome_habit_content`, or
  `welcome_goal_content`.

UNDERWAY multi-type filters do not concatenate multiple welcome messages; they
fall back to operation-result or ordinary empty guidance. FINISHED and DELETED
projections never show welcome guidance.

## 2026-06-21 - Ordinary empty guidance gets new strings

The existing `empty_underway`, `empty_note`, `empty_reminder`, `empty_habit`,
`empty_goal`, `empty_finished`, and `empty_deleted` strings keep the old
`NOTIFY_EMPTY_*` meaning and become operation-result guidance text.

Ordinary empty projections use a new string family instead, with separate
resources for underway/all-types, each single concrete type, finished, deleted,
and opened Empty Thing Folders. This keeps "just emptied by an operation" text
distinct from "this projection is empty after the operation has ended" text.

## 2026-06-21 - First version reuses the no-result image

The first version of Home Empty State uses the existing `img_no_result` asset
for welcome, operation-result, ordinary projection, and Folder-specific empty
guidance. The UI should use an explicit ImageView plus TextView and keep the
existing dark-mode tint treatment used by search no-result imagery.

Distinct visual assets for different empty-state families are deferred until
after the state-machine and legacy placeholder cleanup are stable.

## 2026-06-21 - AppWidget empty presentation is out of scope

This feature changes the in-app home list empty-state presentation only.
Things-list AppWidgets should no longer depend on legacy placeholder Things,
but their empty presentation remains unchanged for this slice.

Launcher RemoteViews empty-state design is deferred because widget dimensions,
layout capabilities, and interactions differ from the in-app RecyclerView
surface.

## 2026-06-21 - Operation-result triggers are user content operations

Operation-result empty guidance is triggered only by user-initiated content
operations that make the current projection empty. The trigger set includes
finishing, deleting, restoring, permanently deleting, changing a Thing type so
it leaves the current filter, moving a Thing out of the current projection,
moving a Folder out of the current projection, deleting/restoring/permanently
deleting a Folder, dissolving a Folder, and undo operations that themselves make
the current projection empty.

Opening the app, switching status, changing type filters, navigating Folders,
searching, color filtering, Activity recreation, system refreshes, and
background widget/receiver refreshes do not trigger operation-result guidance
unless the current Activity can tie the refresh to a user operation that just
occurred in that Activity.

## 2026-06-21 - Initialize creation history from existing real content

For existing databases, first-use history should be initialized from stored real
content before legacy placeholder Things are removed. A concrete type is marked
as ever-created when the database contains at least one real `NOTE`, `REMINDER`,
`HABIT`, or `GOAL` of that type in UNDERWAY, FINISHED, or DELETED state.

Legacy placeholder Things and notification pseudo-things do not count. Thing
Folders do not count toward any concrete Thing type, but the presence of any
Thing Folder means the app has user-owned content and should not show the global
first-app welcome state.

## 2026-06-21 - Global first-use state exits after any user content

The global first-app welcome state needs a durable user-content history marker.
Once the user creates any real Thing or Thing Folder, the app should permanently
exit the global first-use welcome state, even if that content is later finished,
deleted, permanently deleted, moved elsewhere, or dissolved.

Concrete type welcome guidance still uses per-type real Thing creation history;
the global welcome additionally requires that no real Thing or Thing Folder has
ever been created.

## 2026-06-21 - Empty Folder creation entry is deferred

This slice should preserve Empty Thing Folders and make the data/model/UI states
compatible with them, but it does not add a new explicit entry point for
creating an empty Thing Folder. The existing Folder creation entry can remain
unchanged for now.

## 2026-06-21 - All-types empty uses global history only

UNDERWAY all-types empty guidance uses only global user-content history. If the
user has ever created any real Thing or Thing Folder, an empty all-types
projection shows ordinary all-types empty guidance, even if some concrete Thing
types have never been created.

Concrete type welcome guidance appears only when the user is viewing a single
concrete type filter whose real Thing type has never been created.

## 2026-06-21 - Keep legacy constants for cleanup only

`WELCOME_*` and `NOTIFY_EMPTY_*` constants should remain available as legacy
type identifiers for database cleanup, import compatibility, and explicit
non-user-content checks. They should be removed from normal creation, query,
counting, display, and type-matching business paths.

New code should not generate `WELCOME_*` or `NOTIFY_EMPTY_*` Things. Existing
legacy rows should be removed, and empty-state guidance should be represented
by the home UI state rather than by stored or transient Thing rows.
