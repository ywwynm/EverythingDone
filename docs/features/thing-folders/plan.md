# Thing Folders Plan

> 注意（2026-06-23）：本文是实现前的早期规划稿，部分内容已被取代。权威的当前行为见
> `use-cases.md`，关键决策见 `decisions.md`。最重要的偏差：**文件夹不再有删除状态**
> （纯骨架模型）——“删除文件夹/还原文件夹（容器）”已不存在，改为内容操作“删除/恢复
> 文件夹中所有记事”，回收站是 `DELETED` 状态投影。数据库版本也已远超本文所写（现为 v20）。

## Goal

Add a durable folder system for Things. Users can create a Thing Folder by
dragging one Thing onto another in the home list, name it, show it as a Folder
Card, navigate into it, nest folders, keep drawer navigation focused on built-in
lists, and see a Thing's folder location from Detail.

Execution checklist: `execution.md`.

## Existing System Facts

- The canonical item model is `Thing`, stored in the `things` table and loaded
  through `ThingDAO`, `ThingManager`, `ThingsActivity`, and `BaseThingsAdapter`.
- Home list membership is currently controlled by `Def.LimitForGettingThings`
  and `Thing.isTypeStateMatchLimit`; Thing Folders require promoting this into
  a list projection model that combines one built-in drawer destination with an
  optional Thing Folder Path.
- Current home ordering is stored in `Thing.location`; negative locations mean
  sticky items and non-negative locations are ordinary order.
- Long-press on an underway home card enters `ModeManager.MOVING` and starts an
  `ItemTouchHelper` drag. If the drag ends without a real move, the flow enters
  `ModeManager.SELECTING`.
- The drawer is a static `NavigationView` menu backed by `menu_drawer.xml` and
  `ThingsActivity.changeToLimit`.
- The home header title/subtitle are owned by `ActivityHeader` and currently
  derive from the active `limit`.
- Detail loads only the selected `Thing` by id/position and therefore needs a
  folder-path query or cached folder metadata to show the Thing's location.
- Backup/restore zips the app database and shared preferences, so database
  schema changes are covered by backup if migrations are correct.
- Single-Thing export/share currently exports Thing text and attachments only;
  folder path is not represented.
- Thing Card Appearance is a shared presentation model for Thing Cards and
  should not be overloaded with folder membership or folder presentation data.

## Domain Model

### Recommended Shape

Use a tree model:

- A Thing belongs to zero or one Thing Folder.
- A Thing Folder belongs to zero or one parent Thing Folder.
- A Thing Folder can contain Things and child Thing Folders.
- Root-level Things and root-level Thing Folders have no parent folder.
- Moving a Thing or Thing Folder between folders changes only containment and
  local ordering, not the Thing's type, state, reminders, habit data, or goal
  data.

This model matches ordinary folder behavior, supports nesting, keeps header path
logic deterministic, and avoids multi-parent alias semantics.

### Folder Entity

Add a `ThingFolder` model with:

- `id: Long`
- `parentFolderId: Long?`
- `title: String`
- `state: Int`
- `background: ThingBackground`
- `location: Long`
- `isPrivate: Boolean`
- `createTime: Long`
- `updateTime: Long`
- `cardPresentation: ThingFolderCardPresentation`

`ThingFolderCardPresentation` should be a small JSON model, separate from
`ThingCardAppearance`, with:

- `version`
- `mode`: `SUMMARY` or `THUMBNAILS`
- `thumbnailLimit` as a legacy/reserved presentation field. The current
  thumbnail projection applies fixed surface caps instead: three previews for
  normal-span Folder Cards and six previews for full-span Folder Cards.
- `spanMode`: `NORMAL` or `FULL`

### Database

Recommended schema changes:

- Bump `DATABASE_VERSION` from 14 to 15.（已过时：当前为 v20，含纯骨架模型迁移 v20。）
- Add `things.folder_id INTEGER DEFAULT NULL`.
- Add `thing_folders` table:
  - `id INTEGER PRIMARY KEY`
  - `parent_folder_id INTEGER`
  - `title TEXT NOT NULL`
  - `state INTEGER NOT NULL DEFAULT 0`
  - `color INTEGER`
  - `background TEXT`
  - `location INTEGER NOT NULL`
  - `is_private INTEGER NOT NULL DEFAULT 0`
  - `create_time INTEGER NOT NULL`
  - `update_time INTEGER NOT NULL`
  - `card_presentation TEXT`
- Add indexes on `things.folder_id` and `thing_folders.parent_folder_id`.
- Existing databases migrate all user Things to root (`folder_id = NULL`) and
  create no folders.

`Thing.location` remains the Thing ordering value. `ThingFolder.location` is
used in the same ordering space inside a folder. Mixed lists sort Things and
Thing Folders together by location, preserving sticky semantics for both Things
and Thing Folders. Negative `ThingFolder.location` values represent sticky
Folder Cards, matching the existing negative-location sticky convention for
Things.

## Product Behavior

## Confirmed Product Decisions

- Thing Folder membership is a strict tree: each Thing may have zero or one
  parent Thing Folder, and each Thing Folder may have zero or one parent Thing
  Folder.
- Thing Folders are not shown as drawer items. The drawer keeps only built-in
  destinations. Opening a Folder Card keeps the active built-in drawer item
  selected and opens a folder-path projection inside that built-in destination.
- Selecting any built-in drawer item resets the current folder path and opens
  that built-in destination at its root.
- Folder Card counts are recursive descendant Thing counts and include hidden
  private Things, matching the existing home header count behavior.
- Finished and deleted Things preserve folder membership. Finished and Deleted
  built-in destinations show Thing Folder Cards for folders with matching
  descendant Things.
- Folder Cards opened from Finished or Deleted stay inside that built-in
  destination projection instead of jumping to an ordinary underway folder view.
- Underway, Notes, Reminders, Habits, Goals, Finished, and Deleted all show
  Thing Folder Cards for folders with matching recursive descendant Things.
- Thing Folders support sticky placement, manual mixed ordering among Things,
  and privacy.
- Private Thing Folder privacy inherits to descendants for display/access while
  they remain inside it, without rewriting each descendant's own stored private
  state.
- ~~Deleting a Thing Folder moves the folder subtree to Deleted~~ / ~~Restoring a
  Deleted Thing Folder~~ — **已被纯骨架模型取代（2026-06-23）**：文件夹没有删除状态。
  “删除文件夹”改为内容操作“删除文件夹中所有记事”（递归把子树未删除记事移入回收站、
  跟随类型筛选，容器不动）；回收站恢复用“恢复文件夹中所有记事”。详见 `use-cases.md`。
- Permanently deleting：仍保留为结构操作“永久删除文件夹”（销毁整个子树及内容，不可恢复）；
  另有内容操作“永久删除文件夹中所有记事”（只删该范围回收站记事）。
- Multi-parent aliases, shortcuts, and tag-like shared membership are out of
  scope.

### Creating A Folder

- In root or any opened folder, long-press a movable Thing to start drag.
- Dragging the Thing over another eligible Thing exposes a folder-create target
  state.
- Dropping on the eligible target creates a new Thing Folder containing both
  Things.
- The new folder initially takes a generated name or immediately opens a naming
  dialog, depending on the unresolved naming decision.
- The new folder takes a generated `ThingBackground` from the current colour
  system unless the naming/customization flow lets the user choose another
  colour later.
- Creation is undoable if the existing snackbar undo pattern can support the
  membership changes without delaying the first implementation too much.

Eligibility:

- Source and target must be real user Things, not header or notify-empty rows.
- Current doing Thing should not be movable into a new folder during the first
  implementation, matching the current long-press block.
- Private Things can be foldered only when visible/authenticated.
- Private Folder Cards, and entries effectively private because of an ancestor
  private folder, can be moved or opened only through the same private-content
  access rules selected for private Things/Folders.
- Dropping a Thing onto itself is ignored.
- Dropping across sticky/non-sticky boundaries should remain disallowed unless
  the folder create target is explicitly active.

### Opening A Folder

- Tapping a Thing Folder Card opens that folder on the home list.
- Opening a Folder Card keeps the current built-in drawer destination selected
  and adds or extends the current Thing Folder Path projection.
- In Underway, opening a Folder Card shows that folder's matching underway child
  Things and child Thing Folders.
- In Notes, Reminders, Habits, or Goals, opening a Folder Card shows matching
  child Things and child Folder Cards whose descendants match that built-in type
  projection.
- In Finished or Deleted, opening a Folder Card shows the folder's matching
  finished or deleted projection. It does not jump to an ordinary underway
  folder view.
- Opening a Deleted Thing Folder in Deleted shows descendants that are
  effectively deleted because of that ancestor folder state, plus any stored
  deleted descendants inside the subtree.
- Selecting a built-in drawer item while inside a folder clears the folder path
  and opens that built-in destination at root.
- Back closes the current folder and returns to the parent folder before leaving
  the activity.
- Header title shows the active built-in destination and the Thing Folder Path
  when inside folders. Each path segment is clickable and jumps to that
  ancestor projection.
- Header subtitle shows the number of visible child Things/Folders in the
  current projection.

### Folder Card Modes

`SUMMARY` mode:

- Solid card using the folder background.
- Shows folder icon, folder title, and recursive descendant Thing count.
- Behaves like an ordinary card for click, long-press, selection, and dragging.

`THUMBNAILS` mode:

- Outlined card using a ShiningBorder-like border treatment.
- Shows folder title and a thumbnail grid/list of contained Thing Cards.
- Thumbnail layout should adapt to different contained Thing Card heights where
  feasible.
- Tapping a thumbnail opens that Thing's Detail directly.
- Tapping non-thumbnail card area opens the folder.
- The current thumbnail surface caps previews by span: normal-span Folder Cards
  render at most three child Thing previews in one column, and full-span Folder
  Cards render at most six child Thing previews in a three-column masonry
  layout. Additional matching descendants are represented by a bottom ellipsis.
- `spanMode = FULL` makes the Folder Card wide in supported list surfaces.
- The count includes Things inside nested folders and includes private Things
  even when their content is hidden. The count is a metadata count, not a
  promise that every counted Thing is shown as a thumbnail.
- In a built-in destination such as Reminders, Finished, or Deleted, the Folder
  Card count uses that destination's matching descendant Things. In Underway,
  the count uses matching underway descendant Things.
- Folder Cards may be private. When private content is hidden, a private Folder
  Card still shows its stored title, while thumbnails and contained previews
  remain protected until authentication.
- Child thumbnails inherit effective privacy from ancestor Private Thing
  Folders; a non-private child inside a Private Thing Folder must not leak
  visible content through a thumbnail.

The first implementation should reuse as much existing Thing Card projection as
possible for thumbnails, but it must avoid creating full nested RecyclerViews
inside every card if that would destabilize scrolling.

### Drawer

- Created folders do not appear in the drawer.
- The drawer remains a built-in destination selector only.
- Drawer selection remains single-selection and should not need dynamic folder
  menu item management.
- Selecting a drawer item always clears the current folder path projection and
  opens that built-in destination at root.
- Nested folders are reachable from Folder Cards and clickable header path
  segments, not from drawer items.

### Detail

- Detail shows the containing Thing Folder Path for any Thing inside a folder.
- The path is read-only in the first implementation unless the grilling session
  confirms moving from Detail as part of v1.
- A Thing outside folders shows no folder row or shows a compact "Top level"
  row, depending on localization and layout fit.

## Compatibility Matrix

### Existing Limits And States

- Underway, Note, Reminder, Habit, Goal, Finished, and Deleted remain built-in
  destinations and must keep their current state/type filters.
- Folder path projections are evaluated inside the active built-in destination.
  They keep that destination's type/state semantics.
- Every built-in destination can include Folder Cards for folders with matching
  recursive descendants.
- Folder containment should persist across state changes. Finishing a Thing
  should not remove it from its folder.
- Deleted Things should preserve their folder id so restore can put them back in
  the same folder.
- Finished and Deleted built-in destinations show Thing Folder Cards for
  folders with descendant Things matching the current built-in destination.
- Folder membership is state-independent. A Thing's state changes affect which
  built-in destination projection includes it, not which folder owns it.
- Deleted Thing Folder state is folder-state-independent from descendant Thing
  state. A descendant may keep an UNDERWAY or FINISHED stored state while being
  effectively deleted by a Deleted ancestor folder.
- Non-Deleted projections exclude entries inside a Deleted Thing Folder.
- Deleted projections include Deleted Thing Folders and their effectively
  deleted descendants.
- Permanent deletion of a Deleted Thing Folder deletes the container, child
  folders, and contained Things. It does not restore, ungroup, or reparent
  effectively deleted descendants first.

### Sorting And Sticky

- Existing drag sorting updates `Thing.location`. Folder lists need mixed
  ordering updates for Thing and Folder locations.
- Existing sticky behavior uses negative `Thing.location`. Thing Folders should
  use the same negative-location convention for sticky Folder Cards.
- Manual reordering works across mixed Thing and Folder Card entries.
- Sorting by alarm time should operate on visible Things only and should keep
  Folder Cards in their current relative mixed-order positions unless a later
  decision chooses a specific folder-aware alarm sort.

### Search And Colour Filter

- Search should find Things inside folders.
- Search results should either show a flat result list with folder paths or
  preserve folder hierarchy. Recommended v1: flat results with folder paths,
  because current search replaces `mThings` with a flat list.
- Search must not surface effectively deleted descendants outside the Deleted
  projection.
- Colour filter applies to Thing backgrounds. Folder colour matching is an
  open decision.

### Reminders, Habits, Goals, Doing

- Folder membership must not change reminder/habit/goal tables.
- Starting Doing, ongoing notifications, auto notify, and quick create should
  continue to target Things by id.
- A current Doing Thing should keep existing protections against moving or
  appearance editing.

### Private Things And Folders

- Folder thumbnails must not expose private Thing content when private content
  is hidden.
- Folder Card counts include private Things even when private content is
  hidden. This matches existing header count behavior.
- Hidden private descendants may contribute to counts without contributing a
  visible thumbnail or preview card.
- Private Thing Folders keep their card title visible while private content is
  hidden, but must not expose protected thumbnails or contained previews.
- Private Thing Folder privacy inherits to descendant Things and child folders
  for display and access while they remain inside the private folder.
- Inherited effective privacy does not rewrite a descendant's own stored private
  state. Moving a non-private Thing out of a private folder makes it non-private
  again unless it is private by its own stored state.
- Search results, folder paths, Detail folder-location rows, and thumbnail taps
  must respect effective privacy from ancestor folders.

### Widgets And Notifications

- Single-Thing widgets continue to open the Thing Detail by id.
- Things-list widgets do not render Thing Folder Cards in v1. They keep the
  current Thing-based behavior and must remain compile-safe.
- Folder-aware RemoteViews rendering, folder projection intents, effective
  privacy handling, and Deleted folder handling are deferred to a later
  widget-specific slice.
- System notifications and BigPicture media should remain unchanged.

### Backup, Restore, Export, Share

- Backup/restore includes the folder schema automatically through the database.
- Single-Thing export/share should remain Thing-content-focused in v1 unless
  the user wants folder path text included.
- Future import/remap work should preserve folder membership only when importing
  a full app backup, not a single exported Thing zip.

## Implementation Strategy

### Phase 0 - Documentation And Decisions

- Add domain terms to `CONTEXT.md`.
- Create this feature directory.
- Confirm tree semantics, list projection semantics, delete semantics, drawer
  behavior, and export scope before coding broad surfaces.

### Phase 1 - Data Model

- Add `ThingFolder`, `ThingFolderCardPresentation`, and parse/serialize helpers.
- Extend `Thing` with nullable `folderId`.
- Add schema migration and DAO support.
- Add mixed child model, likely `ThingListEntry`, so adapters can render Things
  and Thing Folders without pretending folders are Things.

### Phase 2 - Queries And Managers

- Introduce a current list projection abstraction, such as
  `ThingListProjection`, with a built-in destination and an optional Thing
  Folder Path.
- Migrate `ThingsActivity`, `ActivityHeader`, `ThingManager`, and drawer
  selection from raw `LimitForGettingThings` state to the projection model.
- Query root projections by their existing state/type filters.
- Query folder projections by the active built-in destination's state/type
  semantics plus the current folder path.
- Include Folder Cards in every built-in destination when recursive descendants
  match the current projection.
- Compute effective privacy from ancestor Private Thing Folders for Things and
  Thing Folders in list projections, search results, Detail folder paths, and
  thumbnails.
- Compute effective deletion from ancestor Deleted Thing Folders for Things and
  child Thing Folders in projections, search results, Detail folder paths, and
  restore/permanent-delete flows.
- Compute folder paths, recursive destination-aware Folder Card counts, and
  direct visible child counts where header/list surfaces need them.
- Add create folder, move into folder, move folder, rename folder, and update
  folder presentation operations.
- Add sticky folder update and mixed Thing/Folder reorder operations.

### Phase 3 - Home Rendering

- Add Folder Card layout and adapter view type.
- Support summary and thumbnail modes.
- Integrate folder card click/long-click with existing `ModeManager`.
- Keep selection/moving visuals consistent with current Thing Cards.

### Phase 4 - Drag-To-Folder

- Extend `ItemTouchHelper` drag handling to distinguish reorder from
  drop-on-target.
- Provide visual target feedback while dragging over an eligible Thing/Folder.
- Persist new folder membership and mixed ordering.
- Add undo if it can be implemented safely with current snackbar state.

### Phase 5 - Folder Navigation And Header

- Add current folder stack/path state.
- Update header title to render clickable path segments.
- Add Back behavior for nested folders.
- Make drawer selection and folder path state coherent.

### Phase 6 - Drawer

- Keep the drawer limited to built-in destinations.
- Ensure drawer item selection clears the current folder path projection.
- Avoid dynamic folder drawer item and folder icon tint logic.

### Phase 7 - Detail

- Query and render Thing Folder Path.
- Refresh Detail when folder membership changes externally.
- Keep auto-save/no-update comparisons folder-safe.

### Phase 8 - Integrations And Polish

- Update search, colour filter, selection, delete/restore, sticky, sort by
  alarm, share/export text if selected, app widget compile-safety, and
  localization.
- Implement folder delete, folder restore, and folder permanent-delete flows
  using folder state plus effective deletion semantics.
- Permanent folder deletion removes the entire folder subtree and contained
  Things, including effectively deleted descendants.
- Add debug update notes before publishing a debug build.

## Open Decisions To Grill

No blocking product decisions remain before the first implementation slice.

## Out Of Scope For First Code Slice

- Multi-parent aliases or tags.
- Sharing Thing Card Appearance settings with Thing Folder Card presentation.
- Folder-specific reminder, habit, or goal behavior.
- Folder-scoped widget configuration.
- Folder-aware Things-list widget rendering.
- Importing a single exported Thing zip into a folder.
