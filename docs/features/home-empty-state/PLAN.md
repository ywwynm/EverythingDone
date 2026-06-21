# Home Empty State Plan

## Objective

Replace legacy `WELCOME_*` and `NOTIFY_EMPTY_*` placeholder Things with
view-layer empty-state guidance on the home list. The database should no longer
create placeholder Things, existing placeholder rows should be cleaned up, and
empty guidance should be derived from projection state, creation history, and
the most recent user operation.

## Confirmed Semantics

- `WELCOME_*` and `NOTIFY_EMPTY_*` remain legacy constants only for cleanup,
  import compatibility, and non-user-content checks.
- New installs do not insert welcome or empty placeholder rows.
- Existing installs delete legacy welcome and empty placeholder rows during
  migration or startup self-healing.
- Welcome guidance is first-use guidance:
  - `UNDERWAY + all types + root` uses `welcome_underway_title` and
    `welcome_underway_content` only while no real Thing and no Thing Folder has
    ever been created.
  - `UNDERWAY + one concrete type` uses that type's welcome content only while
    no real Thing of that type has ever been created.
  - `FINISHED`, `DELETED`, and multi-type filters never show welcome guidance.
- Operation-result empty guidance corresponds to the old `empty_*` text and is
  transient in the current `ThingsActivity` only.
- Ordinary empty guidance uses new strings and appears after first-use has
  ended and no current operation-result marker applies.
- Search and color-filter empty results stay separate and continue to use the
  search no-result UI.
- Empty Thing Folders are valid user content. They must not be deleted merely
  because they have no child Things or child Folders.
- An opened Empty Thing Folder uses Folder-specific empty guidance.
- The explicit entry point for creating an Empty Thing Folder is deferred.

## State Matrix

| Situation | Guidance |
| --- | --- |
| Search or color filter has no results | Existing search no-result UI |
| Current projection has visible user content or Folders | No home empty UI |
| Opened Folder has no children | Folder-specific ordinary empty guidance |
| Current user operation emptied the same projection | Operation-result guidance using existing `empty_*` strings |
| Root `UNDERWAY + all types` before any real content or Folder ever existed | `WELCOME_UNDERWAY` guidance |
| Root `UNDERWAY + one concrete type` before that type ever existed | Type welcome guidance |
| Empty projection after first-use has ended | New ordinary empty guidance |

## Persistence Strategy

Add durable first-use history outside the `things` table:

- A history-initialized flag.
- A global "has ever created user content" flag.
- Per-concrete-type flags for `NOTE`, `REMINDER`, `HABIT`, and `GOAL`.

Migration initializes these flags from existing real Things and Thing Folders
before deleting legacy placeholders. A real Thing in `UNDERWAY`, `FINISHED`, or
`DELETED` marks its concrete type as created. Any Thing Folder marks the global
first-use flag as created. Legacy placeholders, notifications, and headers do
not count.

## Code Areas

- `DBHelper`: stop inserting placeholders, bump the schema version, initialize
  first-use history, and clean up legacy placeholder rows.
- `Thing`: add helper predicates for real content, notification-like types, and
  legacy placeholders while preserving old constants for cleanup.
- `ThingDAO`: remove normal query/count/create/update dependence on
  `WELCOME_*` and `NOTIFY_EMPTY_*`; keep cleanup helpers only where useful.
- `ThingManager`: remove generated/transient `NOTIFY_EMPTY` entries, keep list
  entries to real Things and Folders, and stop auto-deleting structurally empty
  Folders.
- `ThingsActivity`: introduce a home empty-state view, derive guidance from the
  current projection, and replace old `KEY_CALL_CHANGE` placeholder semantics
  with explicit operation-result empty markers.
- Widgets and remote actions: stop depending on placeholder rows while keeping
  widget-specific empty presentation out of scope.
- Resources: add ordinary empty and empty-Folder strings while preserving old
  `empty_*` strings as operation-result text.

## Operation-Result Trigger Scope

Only user-initiated content operations can set the transient operation-result
empty marker:

- finish, delete, restore, permanent delete
- change Thing type
- move Thing
- move Folder
- delete, restore, permanent delete Folder
- dissolve Folder
- undo, when undo empties the current projection

Opening the app, changing status/type/folder/search/color filters, Activity
recreation, system refreshes, and background widget updates do not set this
marker.

## Verification

- Build with the project Gradle wrapper.
- Exercise at least the list-loading logic through focused tests or manual
  inspection where existing test coverage is absent.
- Verify that new installs do not create placeholder rows.
- Verify that old placeholder rows are removed from upgraded installs.
- Verify that empty Folders survive moves and refreshes.
- Verify that search/color empty results still use the existing no-result UI.
- Verify that creating a real Thing clears the relevant welcome state
  permanently.
