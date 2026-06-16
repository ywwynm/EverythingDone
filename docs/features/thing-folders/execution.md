# Thing Folders Execution Checklist

## Purpose

Use this document as the implementation ledger for Thing Folders. Check items
off only after the behavior is implemented and verified against `plan.md`.

## Phase 0 - Preflight

- [x] Read global memory indexes and `.agents/rules/`.
- [x] Read `CONTEXT.md` and existing ADRs.
- [x] Read relevant feature docs for Thing Card Appearance, media target
      geometry, home card span mode, and contextual toolbar behavior.
- [x] Inspect Thing model, DAO, DB migration, manager, adapter, drag,
      drawer, header, Detail, backup, export/share, widgets, and ShiningBorder
      integration points.
- [x] Add initial Thing Folder domain language to `CONTEXT.md`.
- [x] Create feature documentation directory.
- [x] Confirm strict tree semantics for Thing Folder membership.
- [x] Confirm Thing Folders are list projections under built-in drawer
      destinations, not drawer items.
- [x] Confirm Folder Card counts are recursive descendant Thing counts and
      include hidden private Things.
- [x] Confirm Finished and Deleted built-in destinations show Folder Cards for
      folders with matching descendant Things.
- [x] Confirm every built-in destination shows Folder Cards for folders with
      matching recursive descendants.
- [x] Confirm Thing Folders support sticky placement, manual mixed ordering,
      and privacy.
- [x] Confirm Private Thing Folder privacy inherits to descendants for
      display/access while preserving each descendant's stored private state.
- [x] Confirm deleting a Thing Folder moves the folder subtree to Deleted while
      preserving structure, memberships, and descendant stored states.
- [x] Confirm permanent-delete behavior for descendants that are only
      effectively deleted by a Deleted Thing Folder.
- [x] Confirm Things-list widgets do not render Folder Cards in v1.
- [x] Confirm open product decisions through the grilling flow.

## Phase 1 - Data Model And Migration

- [x] Bump database version.
- [x] Add `Thing.folderId`.
- [x] Add `ThingFolder` model.
- [x] Add `ThingFolderCardPresentation` model.
- [x] Add `ThingFolder.state`.
- [x] Add `ThingFolder.isPrivate`.
- [x] Support negative `ThingFolder.location` as sticky Folder Card placement.
- [x] Add `thing_folders` table.
- [x] Add `things.folder_id` column.
- [x] Add folder indexes.
- [x] Migrate existing Things to root scope.
- [x] Add Parcelable support for `Thing.folderId`.
- [x] Leave `Thing.noUpdate` unchanged for this slice because Detail does not
      edit folder membership yet.
- [x] Update DAO create/update/state restore paths.
- [x] Add folder DAO create/update/delete/query/path operations.
- [x] Add folder DAO count operations.
- [x] Add malformed/missing folder presentation fallback.

## Phase 2 - Manager And List Projection

- [x] Add a list projection abstraction, such as `ThingListProjection`, with a
      built-in destination and an optional Thing Folder Path.
- [x] Represent built-in destinations backed by existing
      `LimitForGettingThings` values.
- [x] Represent folder path projections backed by one or more Thing Folder ids.
- [ ] Migrate `ThingsActivity` current-list state from raw limit to list
      projection.
- [ ] Migrate `ActivityHeader` title/subtitle generation to list projection.
- [ ] Keep drawer selection tied only to the built-in destination part of the
      projection.
- [x] Add folder stack/path state.
- [x] Load mixed entries for root and current folder path projections.
- [x] Preserve existing built-in destination filtering.
- [x] Ensure folder path projections keep the active built-in destination's
      state/type semantics.
- [x] Preserve search and colour filtering for Thing rows inside projections.
- [x] Preserve search and colour filtering for Folder Card inclusion.
- [x] Add folder path query for Detail.
- [x] Add recursive descendant Thing count query for Folder Cards.
- [x] Ensure Folder Card counts include hidden private Things.
- [x] Add destination-aware Folder Card counts for built-in destinations.
- [x] Include Folder Cards in Underway, Notes, Reminders, Habits, Goals,
      Finished, and Deleted when matching descendants exist.
- [x] Compute effective privacy from ancestor Private Thing Folders.
- [x] Compute effective deletion for Deleted folder root projections.
- [x] Compute effective deletion from arbitrary ancestor Deleted Thing Folders.
- [ ] Add direct visible child count query for header subtitle if selected.
- [x] Add mixed ordering update for Things and Folders.
- [x] Add sticky Folder Card update support.
- [x] Add create-folder operation from two Things.
- [x] Add move Thing into Folder.
- [x] Add move Folder into Folder with cycle prevention.
- [x] Add rename Folder.
- [x] Add update Folder Card presentation.

## Phase 3 - Home Adapter And Layout

- [x] Introduce a mixed list-entry abstraction instead of relying on raw
      `Thing` rows only.
- [x] Add Folder Card view type.
- [x] Add summary Folder Card layout.
- [x] Add thumbnail Folder Card layout or reusable child projection container.
- [x] Add thumbnail-mode outlined Folder Card shell.
- [x] Render folder background and adaptive foreground.
- [x] Render folder icon.
- [x] Render folder title and child count.
- [x] Render recursive Folder Card counts consistently across summary and
      thumbnail modes.
- [x] Render sticky indicator/state for sticky Folder Cards.
- [x] Render private Folder Cards without exposing protected folder identity or
      contained previews when private content is hidden.
- [x] Render Things and child Folder Cards as effectively private when any
      ancestor folder is private and private content is hidden.
- [x] Render Deleted Thing Folders only in Deleted projections unless restored.
- [x] Render thumbnail children without exposing hidden private content.
- [x] Support thumbnail taps opening Thing Detail.
- [x] Support non-thumbnail folder-card taps opening the folder.
- [x] Support normal and full-span Folder Cards.
- [x] Play the existing list appearing animation for Folder Cards.
- [ ] Keep selection/moving dimming consistent.
- [x] Reset recycled holder state for Thing vs Folder cards.

## Phase 4 - Drag-To-Folder

- [x] Track active drag source entry.
- [x] Detect eligible folder-create targets.
- [x] Trigger folder-create targeting only while the dragged Thing Card's
      top-left corner is inside an eligible target Thing Card.
- [x] Animate eligible create targets by shrinking the target card and drawing
      a matching pure/gradient Folder outline for the pending Folder color.
- [x] Drop a Thing onto an existing Folder Card to move the Thing into that
      Folder instead of creating another Folder.
- [x] Animate existing Folder targets by shrinking the card and drawing a
      thicker matching Folder outline.
- [x] Prevent folder creation on header, notify-empty, current Doing Thing, or
      hidden private content.
- [x] Wire drop gesture to create a new folder when a Thing is dropped on
      another Thing.
- [x] Add business operation to create a new folder from two Things.
- [x] Add business operation to move both Things into the new folder.
- [x] Insert the new Folder Card at a predictable location.
- [x] Animate successful drop commits without letting the dragged Thing Card
      visibly snap back to its original position.
- [x] Use targeted removal/change notifications after successful Folder drops
      so the source gap closes and the target Folder Card updates.
- [ ] Recompute mixed ordering around the affected range.
- [x] Preserve ordinary mixed Thing/Folder reorder behavior when no folder
      target is active.
- [x] Allow Folder Cards to be manually reordered among Thing Cards.
- [x] Allow Folder Cards to be made sticky or cancel sticky from Folder Card
      actions.
- [x] Allow Folder Cards to be manually reordered in the sticky area.
- [x] Preserve no-op drag to selecting-mode behavior.
- [ ] Add undo if selected for v1.

## Phase 5 - Folder Navigation And Header

- [x] Open folder from Folder Card.
- [x] Opening a folder extends the current folder path projection while keeping
      the same built-in drawer destination selected.
- [x] Play the list appearing animation when opening a Folder projection.
- [x] Add manager support for back from nested folder to parent.
- [x] Wire activity back navigation from nested folder to parent.
- [x] Back from root follows current ThingsActivity behavior.
- [x] Header title renders built-in destination plus Thing Folder Path.
- [x] Header path segments are clickable.
- [x] Header path segments respect effective privacy when private content is
      hidden.
- [x] Header subtitle counts current visible children.
- [x] Header updates after folder create/rename/move/delete.
- [x] Header-rendered drawer selection and folder path projection stay coherent.

## Phase 6 - Drawer

- [x] Keep the drawer limited to built-in destinations.
- [x] Do not dynamically add folder items to the drawer.
- [x] Selecting a built-in drawer item clears any folder path projection and
      opens that built-in destination at root.
- [x] Close drawer and load the selected root projection.
- [x] Keep Settings, Help, and About items stable.
- [x] Keep existing built-in limit items stable.

## Phase 7 - Detail

- [x] Show containing Thing Folder Path.
- [x] Hide or protect folder-location path segments that are private by stored
      or inherited effective privacy.
- [ ] Hide or show top-level state according to the confirmed decision.
- [x] Leave Detail snapshot/no-update handling unchanged because this slice only
      displays folder location and does not edit membership in Detail.
- [ ] Refresh visible Detail when external folder membership changes.
- [ ] Keep screenshot/share UI clean.

## Phase 8 - State, Search, And Integration

- [x] Preserve folder membership on finish.
- [x] Preserve folder membership on delete/restore.
- [x] Move deleted folders to Deleted by changing folder state.
- [x] Preserve descendant Thing stored states when deleting or restoring a
      folder.
- [x] Exclude descendants of Deleted Thing Folders from non-Deleted projections.
- [x] Include descendants of Deleted Thing Folders in Deleted projections as
      effectively deleted.
- [x] Restore Deleted Thing Folders with their subtree and memberships intact.
- [x] Permanently delete folder records only through the permanent-delete flow.
- [x] Permanently delete the entire folder subtree and contained Things,
      including descendants that are only effectively deleted by the folder.
- [x] Show Folder Cards in Finished for folders with matching descendant
      finished Things.
- [x] Show Folder Cards in Deleted for folders with matching descendant deleted
      Things.
- [x] Show Folder Cards in Notes, Reminders, Habits, and Goals for folders with
      matching descendant Things.
- [x] Preserve folder membership when restoring a deleted Thing.
- [x] Preserve stored private state when moving Things into or out of Private
      Thing Folders.
- [x] Ensure moving a non-private Thing out of a Private Thing Folder removes
      inherited effective privacy.
- [x] Assign newly created Things to the current Folder projection and animate
      their mixed-list insertion when Folder Cards are present.
- [x] Restore left/right swipe actions for Thing Cards in mixed Thing/Folder
      lists while keeping Folder Cards non-swipable.
- [x] Keep reminders, habits, goals, doing, and notifications id-stable.
- [x] Keep app widgets compiling.
- [x] Keep Things-list widgets on their current Thing-based behavior without
      rendering Folder Cards in v1.
- [ ] Update single-Thing export/share if folder path is selected for v1.
- [ ] Update backup restore smoke path.
- [x] Update localization strings, starting with `values-zh-rCN/strings.xml`.

## Verification Matrix

- [ ] Fresh install creates no folders and loads root Things normally.
- [ ] Upgrade from DB v14 adds folder schema and preserves all existing Things.
- [ ] Root underway list matches pre-feature order when there are no folders.
- [ ] Note/Reminder/Habit/Goal lists match pre-feature behavior when there are
      no folders.
- [ ] Long-press no-op still enters selecting mode.
- [ ] Long-press reorder still persists Thing order.
- [ ] Drag Thing onto Thing creates a named folder.
- [ ] Drag Thing onto Thing only creates a folder while the dragged card's
      top-left corner is inside the target card.
- [ ] Drag Thing onto an existing Folder Card moves the Thing into that Folder.
- [ ] Folder creation works inside a nested folder.
- [ ] Creating a new Thing in a mixed list inserts and animates the new card
      without requiring an app restart.
- [ ] Swipe finish/start-doing still works for Thing Cards in a mixed list.
- [ ] Nested folder path displays correctly.
- [ ] Clicking a path segment navigates to that ancestor folder.
- [ ] Summary Folder Card displays icon, title, and count.
- [ ] Summary Folder Card count is recursive through nested folders.
- [ ] Folder Card count includes hidden private Things without exposing private
      content.
- [ ] Thumbnail Folder Card displays child thumbnails.
- [ ] Thumbnail tap opens the child Thing Detail.
- [ ] Non-thumbnail area tap opens the folder.
- [ ] Full-span Folder Card spans the list width.
- [ ] Folder Cards appear in Notes, Reminders, Habits, and Goals when matching
      descendants exist.
- [ ] Folder Cards can be reordered among Thing Cards.
- [ ] Sticky Folder Cards stay in the sticky area.
- [ ] Private Folder Cards hide protected identity/previews when private content
      is hidden.
- [ ] Non-private Things inside a Private Thing Folder render as private while
      inside it.
- [ ] Moving a non-private Thing out of a Private Thing Folder makes it render
      non-private again.
- [ ] Search and Detail do not leak private ancestor folder names while private
      content is hidden.
- [ ] Drawer does not show folder items.
- [ ] Drawer built-in item selection clears the current folder path.
- [ ] Detail shows the folder path.
- [ ] Hidden private Things are not exposed through folder thumbnails.
- [ ] Finished/deleted Things preserve folder path after restore.
- [ ] Deleting a Folder Card moves the folder subtree to Deleted.
- [ ] Deleted folder descendants disappear from non-Deleted projections.
- [ ] Deleted folder descendants appear in Deleted as effectively deleted.
- [ ] Restoring a Deleted Thing Folder restores the subtree and descendant
      visibility according to descendant stored states.
- [ ] Permanently deleting a Deleted Thing Folder removes the folder subtree and
      contained Things.
- [ ] Permanently deleting a Deleted Thing Folder does not restore, ungroup, or
      reparent effectively deleted descendants first.
- [ ] Finished list displays Folder Cards for folders containing finished
      descendants.
- [ ] Deleted list displays Folder Cards for folders containing deleted
      descendants.
- [ ] Search finds Things inside folders.
- [ ] Colour filter remains safe.
- [ ] AppWidget compile paths remain safe.
- [ ] Things-list widgets do not render Folder Cards in v1.
- [x] `:app:assembleDebug` passes.
