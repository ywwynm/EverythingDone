# Thing Folders Sessions

## 2026-06-17 - Mixed-list position repair after Folder Cards

- Follow-up testing showed that adding Folder Cards exposed more stale
  position assumptions: some code paths still used a Thing-only `mThings`
  index as a RecyclerView adapter position.
- Added `KEY_LIST_POSITION` for Detail results so `KEY_POSITION` can keep its
  existing Thing-index meaning for `ThingManager.update(...)`, while
  RecyclerView notifications use the mixed-list adapter position.
- Updated Detail return handling in `ThingsActivity` to resolve visible list
  positions by Thing id for item changes, removals, sticky moves, and
  doing/cancel refreshes. When a returned Thing is no longer visible, the UI
  falls back to a full list refresh instead of notifying the wrong item.
- Updated the selected-Thing card appearance entry to store the selected
  Thing's mixed-list adapter position, not its Thing-only index.
- Adjusted Folder Card count text by 2dp to the right of the Folder icon's
  layout start to match the user's visual alignment request.
- Verified the changes with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-17 - Folder count alignment and custom naming dialog

- Moved the Folder Card recursive count out of the recycled ordinary
  `tvContent` slot into a dedicated dynamic count TextView inserted directly
  below the Folder header. The count keeps the existing small text size and
  now starts at the same 16dp left inset as the Folder icon.
- Added `ThingFolderNameDialogFragment` with app DialogFragment chrome for
  Folder creation and rename naming flows.
- The Folder naming dialog adapts title, confirm action, and EditText focus
  treatment to the Folder background. Gradient Folders use gradient text and a
  custom gradient EditText underline; pure-color Folders use the folder color
  as the focused text/underline accent. Selected-text background uses the
  folder accent's light color.
- Changed drag-create Folder naming so Cancel rolls back the created Folder:
  the original source/target Things are moved back to the Folder's parent
  projection and only the new Folder record is removed.
- Added a conservative rollback guard that only removes the created Folder
  record when the Folder still contains exactly the Things that the rollback is
  about to reparent.
- Verified the changes with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-16 - Active swipe/drag card z-order stabilization

- Follow-up testing showed that the active Thing Card could change z-order
  relative to nearby Thing and Folder Cards during horizontal swipe or drag.
- Diagnosed the likely cause as competing z sources: AndroidX
  `ItemTouchHelper` raises the selected item based on sibling elevation at the
  start of an active draw path, while card touch, moving-mode, Folder Card
  surfaces, and RecyclerView item animations can later change sibling card z.
- Added per-frame active gesture z enforcement in `ThingsTouchCallback` after
  `super.onChildDraw(...)`: active swipe/drag cards now compute the current
  maximum sibling `z` and receive enough transient `translationZ` to remain
  above it.
- Kept normal `cardElevation` ownership unchanged so card press/release,
  selection, moving-mode, and Folder-drop feedback animations continue to use
  their existing elevation values.
- Reset the transient `translationZ` in `ItemTouchHelper.clearView(...)` and in
  `ThingsAdapter.onBindViewHolder(...)` to avoid recycled card z leakage.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161548` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Folder-drop animation isolation hardening

- Implemented a first hardening pass for Thing-to-Thing create-Folder and
  Thing-to-Folder drag feedback without enabling RecyclerView Adapter stable
  ids.
- Kept Folder-drop drag state keyed by stable business identity
  (`sourceThingId`, `targetThingId`, and `targetFolderId`) while allowing
  adapter positions to move during RecyclerView gap-filling.
- Ended pending RecyclerView item animations before arming or committing a
  Folder drop, and deferred/cleared Folder-drop hover feedback while the
  RecyclerView is computing layout or still running item animations.
- Changed Folder-drop hit-testing to use translated source/target bounds and
  skip holders with `RecyclerView.NO_POSITION`, so animated or moving cards do
  not use stale layout-only coordinates.
- Split normal animated restore from commit cleanup: ordinary hover exit still
  animates target scale/outline back to normal, while successful commit clears
  the real target card's scale/outline immediately after the overlay snapshot
  is captured so the commit overlay owns the visible finish animation.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161401` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Drag-drop animation architecture analysis

- Reviewed the current Thing-to-Thing create-folder and Thing-to-Folder drop
  implementation without changing app code.
- Confirmed that `ThingsTouchCallback` owns ordinary mixed-list reorder,
  Folder-drop hover arming, target feedback, commit overlay capture, business
  mutation, and targeted RecyclerView notifications in one callback.
- Identified the main remaining risk as concurrency between live
  `ItemTouchHelper` reorder/gap-filling, RecyclerView item animations,
  target-card scale/outline feedback, mode rebinds, and commit-time
  `notifyItemRemoved(...)` / `notifyItemChanged(...)`.
- Noted that the strongest long-term mitigation is to move Folder-drop dragging
  toward an overlay/drag-session model that freezes RecyclerView structural
  animation during Folder-drop hover and commit, uses stable entry ids for
  targets, and lets a single drag visual layer own hover and commit animation.

## 2026-06-16 - Create-Folder outline follows target during scroll

- Follow-up testing showed that the pending create-Folder outline could detach
  from its target Thing Card after a long drag path that caused the list to
  scroll.
- Refined the fixed-center model: the outline remains fixed relative to the
  target card's unscaled layout center, but it must not be fixed to one
  RecyclerView coordinate snapshot.
- Changed `FolderDropOutlineDecoration` to keep a reference to the target card
  and recompute the card's unscaled RecyclerView-local layout bounds in
  `onDraw(...)`. This makes the outline follow list scrolling and item
  translation while still ignoring the target card's current `scaleX/scaleY`.
- Kept the outline animation itself as progress-only stroke width and alpha.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161316` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Create-Folder outline fixed-center correction

- Follow-up testing showed that recalculating create-Folder outline bounds from
  the target card's current `scaleX/scaleY` was wrong: the outline drifted
  toward the target Thing Card's lower-right corner.
- Corrected the geometry model. The pending create-Folder outline and the
  target Thing Card should share one fixed center. The entrance/exit animation
  should only animate outline progress, which controls stroke width and alpha.
- Replaced transform-affected `getLocationOnScreen(...)` bounds for the target
  card with an untransformed RecyclerView-local layout bounds calculation.
- Kept the fixed visual gap from the shrunken target card by using the intended
  target scale once when the fixed outline bounds are created, not on every
  draw frame.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161251` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Create-Folder outline exit follows target scale

- Superseded by the fixed-center correction above. Recalculating bounds from
  current `scaleX/scaleY` caused visible lower-right drift.
- Follow-up testing showed that the create-Folder outline entrance animation
  was visible, but the exit animation still looked instant.
- Clarified the root cause: the outline is intentionally drawn below
  RecyclerView child cards so the dragged source Thing Card can occlude it.
  That also means the target Thing Card can cover the outline while restoring
  from its shrunken scale back to normal size.
- Changed the outline decoration to calculate its bounds during `onDraw(...)`
  from the target card's current `scaleX/scaleY`, keeping the outline outside
  the target card's current visual edge while the target card expands.
- Simplified outline exit state so clearing the current highlight detaches the
  decoration from the active-highlight slot, then animates its progress from
  the current value to `0f` and removes the item decoration once.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161240` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Create-Folder outline layering and exit animation

- Follow-up testing showed two create-Folder outline issues: the outline exit
  could disappear without animation, and the dragged source Thing Card did not
  visually occlude the outline because the outline was drawn in
  `RecyclerView.overlay` above child views.
- Moved the create-Folder outline from `RecyclerView.overlay` to a zero-offset
  `RecyclerView.ItemDecoration` drawn in `onDraw(...)`. This places the outline
  below RecyclerView child cards, so the dragged Thing Card naturally covers it
  while passing over the target.
- Kept the existing `FolderDropOutlineDrawable` progress animation for the
  entrance, but now invalidates the RecyclerView while the decoration animates.
- Added animated outline exit: clearing a pending create-Folder target now
  animates decoration progress from its current value to `0f`, then removes the
  item decoration.
- Added a token guard for outline exit removal so a canceled old exit animation
  cannot remove a newly created outline decoration.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161220` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Retargeted Folder-drop restore animations

- Follow-up testing showed that synchronising `scaleX/scaleY = 1f` directly in
  every cancel callback still creates a visible jump. A restore animation may
  be canceled because a newer highlight animation has taken over the same
  target; in that case the old animation must not write its obsolete final
  value.
- Replaced unconditional cancel fallback with per-target animation tokens. When
  a new card-scale animation starts, it invalidates the previous token before
  canceling the old animation. The old cancel callback exits without writing
  any value, so the new animation retargets from the current visual scale.
- Applied the same token guard to thumbnail Folder outline animations.
- Thumbnail outline animations now track the current stroke width per content
  view and start the next animation from that current visual width, rather than
  restarting from the normal or highlighted width.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161209` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Folder-drop restore final-state fallback

- Follow-up testing clarified that animated Folder-drop restore still needs a
  final-state fallback. Restore should animate normally when possible, but if a
  later drag feedback animation cancels it, the target must still be
  synchronised to the intended final visual state.
- Added `withEndAction(...)` final-state synchronisation for target card scale
  restore animations, and paired it with an `onAnimationCancel(...)` listener
  fallback because Android does not run `withEndAction(...)` for canceled view
  property animations.
- Thumbnail-mode Folder outline animations now synchronise the target stroke
  width in both `onAnimationEnd(...)` and `onAnimationCancel(...)`.
- New target highlight animations explicitly clear any previous restore
  listener/end action before starting, so stale restore callbacks cannot affect
  a fresh highlight animation.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161159` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Animated multi-target Folder-drop cleanup

- Follow-up testing clarified that the broad multi-target cleanup should not
  snap Folder-drop feedback back to normal. The cleanup must remain animated so
  target cards and thumbnail outlines visually settle instead of jumping.
- Kept per-drag tracking for every highlighted Folder/Thing target, but changed
  restoration back to animations: each tracked card now animates scale back to
  `1f` with the existing Folder-drop target animation duration.
- Replaced the single thumbnail-outline animator with a per-content animator
  map. This lets multiple thumbnail-mode Folder targets animate their outline
  width back to normal independently instead of cancelling each other's
  recovery animation.
- Removed the direct thumbnail outline reset path from Folder-drop cleanup.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161015` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Multi-target Folder-drop highlight cleanup

- Follow-up testing showed a remaining visual-only drag regression: when a
  Thing Card drag path crossed multiple Folder Cards, triggered Folder-drop
  feedback, and also caused RecyclerView drag gap-filling, one previous Folder
  target could remain scaled down even after the dragged card's top-left corner
  was no longer inside it.
- Changed Folder-drop highlight bookkeeping from a single latest target view
  to per-drag collections of every card and thumbnail-outline content that has
  been highlighted.
- Folder-drop cleanup now cancels pending ViewPropertyAnimators and restores
  all tracked target card scales to `1f`. Thumbnail-mode Folder targets also
  have their outline reset back to the normal stroke immediately.
- Tightened the same-target early-return condition so it is used only when the
  tracked target collection contains exactly the current target card. If extra
  highlighted targets are still recorded, cleanup runs before re-highlighting
  the current target.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161009` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Reorder persistence guard for Folder-drop hover frames

- Follow-up testing showed two remaining drag regressions: a Folder-drop target
  could stay visually shrunken during RecyclerView drag gap-filling, and a drag
  path that passed through Folder targets could leave the in-memory list order
  different from the persisted database order after app restart.
- Fixed the persistence bug by ensuring Folder-drop hover candidate frames do
  not update `finalFrom` or `finalTo`. Only the normal mixed-list move branch
  that actually calls `mThingManager.move(...)` and `notifyItemMoved(...)` now
  affects reorder persistence through `updateLocations(...)`.
- Hardened target highlight refresh for RecyclerView gap-filling by comparing
  the actual target `CardView`, not only the adapter position and action. If a
  position now resolves to a different card view, the old shrunken card is
  restored before the new target can be highlighted.
- Added immediate pending-highlight cleanup from `onMove(...)` while
  RecyclerView is computing layout or its item animator is running, or when the
  current candidate no longer matches the armed pending Folder drop.
- Moved the Folder Card title offset from 2dp to 1dp.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606160958` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Stabilized drag hover arming around RecyclerView gap filling

- Follow-up testing showed that Folder-drop feedback was still too eager while
  RecyclerView was filling the drag gap. Dragging the first Thing Card toward
  the second could let the target card move first while the pending Folder
  outline remained at the old visual location.
- Split Folder-drop detection into a hover candidate and an armed pending drop.
  A candidate must keep the same source Thing id, target Thing/Folder id,
  target adapter position, and action for a short delay before the pending
  Folder feedback is shown or can be committed on release.
- Deferred arming while RecyclerView is computing layout or its item animator
  is running, so Folder-drop feedback waits for normal drag gap-filling to
  settle before deciding whether the user is really creating or merging into a
  Folder.
- Changed Folder-drop highlight cleanup to remember and restore the actual
  highlighted card view instead of resolving it again by adapter position. This
  prevents a target card from staying shrunken when adapter positions shift
  during drag.
- Moved the Folder Card title 2dp lower in the compact header.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606160944` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Folder drop refinement and count typography

- Follow-up testing clarified that thumbnail-mode Folder Cards should both
  shrink and thicken their outline when a Thing Card is dragged onto them.
- Changed create-Folder pending outlines to use a fixed visual gap from the
  shrunken target Thing Card instead of relying on the target card's original
  width and height. This keeps horizontal and vertical gaps visually even
  across different target-card aspect ratios.
- Hardened the drag state machine for the case where a Thing Card enters a
  Folder-drop candidate, leaves it before release, returns to its original
  position, and then releases. Frames immediately after leaving a pending
  Folder target now clear the pending drop without falling through to a normal
  reorder, and release checks the dragged Thing's final list position against
  its drag-start position before deciding whether to enter selecting mode.
- Adjusted Folder Card title/count typography: the title no longer uses the
  previous negative top margin, and the recursive count explicitly uses the
  ordinary small count text size used by normal Thing Card media/audio count
  labels.
- Audited Thing Card count typography. Inline image/video and audio counts are
  `11sp` in their normal state and expand to `18sp` only when the card is
  otherwise attachment-count-only. Image/video counts shown over visible media
  or media backgrounds are separate overlay labels but also use `11sp`.
- Verified with `.\gradlew.bat :app:assembleDebug`; only the pre-existing
  deprecated override warning remains.
- Published debug update `202606160903` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Existing Folder drop feedback and drag-exit crash fix

- Follow-up testing clarified that the create-Folder outline with a visible gap
  applies only when dragging one Thing Card onto another Thing Card.
- Changed dragging a Thing Card onto an existing Folder Card to use
  Folder-card-specific feedback: summary-mode Folder Cards scale down, while
  thumbnail-mode Folder Cards keep their size and animate their outline to a
  thicker stroke.
- Fixed a crash reported on OnePlus Android 16 when a drag entered a Folder
  target and then left it before release. The likely cause was adding/removing a
  high-frequency highlight `View` directly under the activity
  `ContentFrameLayout` during render traversal. The create-Folder highlight now
  uses `RecyclerView.overlay` drawables instead of root child views, and
  existing-Folder highlights no longer use the root overlay path.
- Adjusted Folder Card header alignment: the count now aligns with the Folder
  icon's left edge, and the title `TextView` removes extra font padding with a
  slight top offset so the first title line sits visually closer to the icon.
- Verified with `.\gradlew.bat :app:assembleDebug`; only the pre-existing
  deprecated override warning remains.
- Published debug update `202606160815` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Folder Card layout, drop outline gap, and private folder scope

- Updated the pending drag-drop target highlight so the outline is rendered as
  a root overlay at the original target-card bounds while the target card
  scales down. This creates a visible animated gap between the card body and
  the Folder outline while preserving pure-colour and gradient Folder
  backgrounds. The overlay is also cleared before restoring the target card so
  a recycled target holder cannot leave a stale outline behind.
- Refined Folder Card header layout: the Folder icon is smaller and placed at
  the top-left, the Folder title sits to its right with up to two lines, and
  the recursive count is aligned under the title column with the shorter
  Chinese format `X件记事`.
- Added private Folder prerequisite handling: setting a Folder private now
  requires the existing private-content app password, and shows a
  Folder-specific warning dialog if no password exists.
- Added authenticated private Folder scope support in `ThingManager`. Opening a
  protected Folder after authentication records that Folder as authenticated for
  the current Folder path; descendants render normally inside that path, and the
  scope is trimmed when the user navigates above or outside the authenticated
  path.
- Wired the authenticated scope through Folder Cards, Thing Cards, thumbnail
  clicks, Folder move dialogs, the Activity header path, and Detail screen
  Folder-location text so private Folder names and contained cards are revealed
  only inside the authenticated path or global private-content mode.
- Verified the changes with `.\gradlew.bat :app:assembleDebug` after rerunning
  Gradle with elevated permissions because the sandbox denied access to
  `.gradle/configuration-cache/configuration-cache.lock`.
- Published debug update `202606160743` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-15 - Planning baseline

- Read global memory indexes, operational rules, `CONTEXT.md`, ADRs, and
  relevant feature docs before planning.
- Audited current Thing persistence, home list loading, long-press drag,
  drawer navigation, header rendering, Detail loading, backup/export/share,
  widgets, and ShiningBorder entry animation touchpoints.
- Created the initial Thing Folders `README.md`, `plan.md`, `execution.md`, and
  `decisions.md`.
- Added Thing Folder domain language to `CONTEXT.md`.
- Stopped before code so unresolved product decisions can be grilled one at a
  time.

## 2026-06-15 - Count and state projection decisions

- Confirmed that Folder Card counts are recursive descendant Thing counts.
- Confirmed that hidden private Things contribute to Folder Card counts, while
  thumbnails and previews must still hide private content when private content
  is hidden.
- Verified existing header counts use `ThingsCounts` by type/state and do not
  filter out private Things.
- Confirmed that finished and deleted Things preserve folder membership.
- Confirmed that Finished and Deleted built-in destinations should show Folder
  Cards for folders with matching descendant Things.

## 2026-06-15 - Drawer and folder projection decision

- Confirmed that Thing Folders should not appear as drawer items.
- Updated the navigation model to a `ThingListProjection`: one built-in drawer
  destination plus an optional Thing Folder Path.
- Confirmed that opening a Folder Card keeps the current built-in drawer item
  selected and opens a folder projection inside that built-in destination.
- Confirmed that selecting a built-in drawer item clears the current folder
  path projection.

## 2026-06-15 - Built-in list, ordering, sticky, and privacy decisions

- Confirmed that all built-in destinations, including Notes, Reminders, Habits,
  and Goals, should show Folder Cards for folders with matching recursive
  descendant Things.
- Confirmed that Thing Folders support manual mixed ordering among Thing Cards.
- Confirmed that Thing Folders support sticky placement using the same sticky
  concept as Things.
- Confirmed that Thing Folders support privacy.
- Confirmed that Private Thing Folder privacy inherits to descendants for
  display/access while preserving each descendant's own stored private state.

## 2026-06-15 - Folder delete and restore decision

- Confirmed that deleting a Thing Folder moves the folder subtree to Deleted.
- Confirmed that folder deletion preserves the folder subtree, Thing
  memberships, and descendant stored states.
- Confirmed that descendants of a Deleted Thing Folder are effectively deleted
  for display/navigation while inside that deleted folder.
- Confirmed that restoring a Deleted Thing Folder restores subtree visibility
  according to descendant stored states.
- Confirmed that permanent deletion is the operation that destroys folder
  records.
- Confirmed that permanently deleting a Deleted Thing Folder deletes the entire
  folder subtree and contained Things, including descendants that are only
  effectively deleted by the deleted folder.

## 2026-06-15 - Widget scope decision

- Confirmed that Things-list widgets should not render Thing Folder Cards in
  v1.
- Deferred folder-aware widget rendering, projection intents, effective privacy,
  and effective deletion handling to `followups.md`.

## 2026-06-15 - Data model and migration foundation

- Bumped the database version to v15.
- Added `Thing.folderId` with Cursor and trailing Parcelable compatibility.
- Added `ThingFolder` and `ThingFolderCardPresentation` models.
- Added the `thing_folders` table, `things.folder_id`, and folder lookup
  indexes for fresh installs and v14 upgrades.
- Updated Thing DAO create/update/state-restore paths to preserve folder
  membership.
- Added a basic Thing Folder DAO for create/update, parent movement, state,
  privacy, card presentation, ordering, path lookup, cycle prevention, and
  permanent subtree deletion.
- Verified the foundation with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Projection and recursive count foundation

- Added `ThingListProjection` for built-in destination plus optional Thing
  Folder Path.
- Added `ThingListEntry` as the mixed Thing/Folder home-list entry model.
- Added projection-aware Thing row loading that preserves current type/state,
  search, and colour filtering while filtering by `folder_id`.
- Added recursive destination-aware Folder Card counts and thumbnail seed
  queries.
- Added a parallel mixed-entry list in `ThingManager` while keeping the legacy
  `mThings` list for existing adapters and widgets.
- Verified the projection foundation with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Home summary Folder Card foundation

- Added localized strings and a vector icon for Thing Folder cards.
- Added adapter hooks so `BaseThingsAdapter` can keep legacy Thing rows while
  `ThingsAdapter` renders mixed `ThingListEntry` rows.
- Added a Folder Card view type using the existing `card_thing.xml` holder.
- Implemented summary Folder Card rendering with folder background, adaptive
  foreground, folder icon, recursive count, sticky indicator, private-folder
  title protection, and normal/full-span placement.
- Added a thumbnail-mode outlined card shell; actual child thumbnail rendering
  remains pending.
- Verified the adapter foundation with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Folder Card click navigation foundation

- Added ThingManager helpers that map adapter positions to mixed entries and
  legacy Thing positions.
- Wired Folder Card clicks in normal mode to open that folder projection and
  keep the current built-in destination.
- Updated Thing clicks to pass the legacy Thing index to Detail while using
  mixed adapter positions for UI updates.
- Temporarily disabled legacy drag/swipe paths while Folder Cards are present,
  preventing position-mapping writes before real mixed ordering is implemented.
- Wired Back to navigate from a nested folder projection to its parent before
  falling through to the existing root-exit behavior.
- Verified the click-navigation foundation with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Detail folder path foundation

- Added a Detail path row under the title.
- Displayed a Thing's containing Thing Folder Path when `folderId` is present.
- Protected private folder names in the Detail path row with the private-folder
  placeholder.
- Kept Detail membership read-only in this slice, so `Thing.noUpdate` remains
  unchanged.
- Verified the Detail path foundation with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Folder creation business operation foundation

- Added a manager operation that creates a Thing Folder from two Things in the
  current projection.
- Added a manager operation that moves a Thing into or out of a folder by
  updating `things.folder_id`.
- Kept the drag/drop gesture wiring pending; the UI can call the manager
  operation once target detection and naming are implemented.
- Verified the operation foundation with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Drag-to-folder creation and header path

- Wired long-press drag so dropping one eligible user Thing onto another opens
  a naming dialog and creates a Thing Folder in the current projection.
- Blocked folder creation for header/placeholder content, current Doing Things,
  non-underway Things, and stored-private Things while hidden-private handling
  remains conservative.
- Kept legacy mixed-list reorder/swipe disabled when Folder Cards are present,
  but allowed additional drag-to-folder creation in lists that already contain
  Folder Cards.
- Updated `ModeManager` to select Things by mixed-list position mapping instead
  of raw `mThings` index.
- Added clickable ActivityHeader folder paths that preserve the active built-in
  drawer destination and navigate to root or ancestor folders.
- Verified the drag and header foundation with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Folder Card thumbnails and actions

- Implemented thumbnail-mode Folder Cards using lightweight child Thing preview
  views that adapt their height to title/content length.
- Hid stored-private child Thing content in thumbnails when private content is
  not shown, while still preserving recursive counts.
- Added thumbnail taps that open the child Thing Detail with a `position=-1`
  update path and reload the home projection after Detail changes.
- Added long-press Folder Card actions for rename, summary/thumbnail mode,
  normal/full span, private toggle, and sticky/cancel-sticky.
- Added manager update APIs for Folder rename, card presentation, privacy, and
  sticky location writes.
- Verified the thumbnail/action slice with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Folder state, effective deletion, and effective privacy

- Added Folder Card actions for delete, restore, and permanent delete.
- Implemented folder deletion as a `ThingFolder.state` change so descendant
  Things keep their stored states and memberships.
- Implemented effective deletion through arbitrary deleted ancestors: deleted
  folders and their descendants disappear from non-Deleted projections and
  count all contained Things in Deleted projections.
- Implemented permanent delete for a folder subtree by deleting descendant
  folder records and contained Things.
- Fixed mixed-list empty-state handling so lists with only Folder Cards do not
  create notify-empty placeholder Things.
- Implemented effective privacy through arbitrary private ancestors for Folder
  Cards, current-folder Thing rendering, Folder Card opening/actions, and
  thumbnail seed filtering.
- Routed Folder Card search and colour filtering through the same keyword/hue
  filters used by Thing rows.
- Updated ActivityHeader subtitles inside folder projections to show current
  visible direct children instead of global built-in destination counts.
- Verified the state/privacy/search slice with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Mixed ordering and move targets

- Replaced the temporary drag guard with mixed `ThingListEntry` movement for
  Things and Thing Folders.
- Persisted mixed ordering changes to both `things.location` and
  `thing_folders.location`, including sticky-area Folder Card movement.
- Added an overlap threshold and target-card highlight so Thing-to-Thing drag
  can distinguish folder creation from ordinary reordering.
- Added a Folder Card action that starts manual card-order dragging.
- Added Folder Card movement into another Thing Folder or root, with cycle
  prevention and private-target authentication.
- Added an Underway selection-menu action to move selected Things into any
  non-deleted Thing Folder or back to root.
- Verified the slice with `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606151628` to the Aliyun debug channel for device
  testing.

## 2026-06-16 - Drag feedback and mixed-list regressions

- Replaced the folder-create overlap threshold with top-left-corner hit
  testing: a pending Folder drop is active only while the dragged Thing Card's
  top-left corner is inside the target card.
- Added animated pending-drop feedback for Thing targets: the target card
  shrinks and draws a pure or gradient Folder outline using the same random
  background that will be assigned to the newly created Folder.
- Added Thing-to-existing-Folder drag support: dropping an eligible Thing onto a
  Folder Card moves the Thing into that Folder instead of creating another
  Folder, with a thicker animated target outline.
- Included Folder Cards in the existing list appearing animation and replayed
  that animation when opening or navigating within a Folder projection.
- Fixed mixed-list new Thing creation by assigning new Things to the current
  Folder projection, rebuilding mixed entries after manager mutations, and
  arming the insertion animation against the new Thing's mixed-list position.
- Restored left/right swipe actions for Thing Cards in mixed Thing/Folder
  lists while keeping Folder Cards non-swipable.
- Verified the fixes with `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606160225` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode`.

## 2026-06-16 - Pending drop release and undo position fixes

- Fixed a drag release regression where the pending Folder drop animation could
  be visible, but `clearView` rechecked already-reset View coordinates, cleared
  the pending drop, and fell through to selecting mode instead of creating a
  Folder or moving the Thing into an existing Folder.
- Changed drag release handling to consume the last active pending Folder drop
  state established during drag frames; moving the dragged card out of the
  target continues to clear that state before release.
- Fixed mixed-list undo notifications after swipe-finish by mapping the undone
  Thing id back to its current mixed adapter position before notifying the
  adapter. This prevents a leading Folder Card from flashing when undoing the
  second visible Thing.
- Applied the same mixed-position notification mapping to habit undo after a
  swipe-finish.
- Verified the fixes with `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606160240` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode`.

## 2026-06-16 - Active-frame pending drop hardening

- Follow-up testing showed that the visible pending Folder drop animation could
  still fail to execute after release.
- Hardened the drag lifecycle so pending Folder drop hit-testing is updated
  only during active drag frames. Non-active `ItemTouchHelper` recovery frames
  no longer clear a valid pending drop after the user releases the card.
- Changed pending Folder drop state to store the source Thing id plus the target
  Thing id or target Folder id. Release handling resolves the current Thing or
  Folder from those ids instead of relying on adapter positions captured during
  drag.
- Removed the obsolete position-based drop helpers so future changes do not
  accidentally reintroduce the unstable path.
- Verified the hardening with `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606160305` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode`.

## 2026-06-16 - Drop commit animation and targeted list updates

- Follow-up testing showed that successful Folder drops still felt wrong
  because `ItemTouchHelper.clearView(...)` visually returned the dragged Thing
  Card to its original position before the business change became visible.
- Added a transient overlay snapshot of the dragged Thing Card before
  `clearView(...)` resets the real item view. The overlay now animates from the
  release position into the target Folder Card or target Thing Card, while the
  real source view stays hidden until the removal animation has finished.
- Added a moving-mode exit path that skips the old delayed full-list refresh
  after a successful Folder drop, allowing the drop commit path to own the
  RecyclerView notifications.
- Changed Thing-to-Folder and Thing-to-Thing drop commits to mutate the manager
  first, then issue targeted `notifyItemRemoved(...)` plus
  `notifyItemChanged(...)` calls so the source gap closes and the target Folder
  Card updates without replaying the whole list appearing animation.
- Reset recycled root item-view visibility, alpha, and scale in
  `ThingsAdapter.onBindViewHolder(...)` so a hidden source holder cannot leak
  into later bindings after the overlay animation path.
- Verified the changes with `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606160352` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode`.

## 2026-06-16 - Pre-recovery drop visual capture

- Follow-up testing showed that the dragged Thing Card still visibly returned
  to its original position before entering a Folder, which means the previous
  `clearView(...)` overlay capture still happened after AndroidX
  `ItemTouchHelper` had already started or completed its drag recovery.
- Moved successful Folder-drop visual preparation earlier in the drag lifecycle:
  `getAnimationDuration(...)` now prepares the overlay snapshot, hides the real
  source item view, and returns `0L` for the pending drag recovery before the
  default recovery animation can display a snap-back.
- Added release-time bookkeeping for the active drag holder and last active
  source coordinates in root coordinates, so the overlay starts from the user's
  last real drag position instead of whatever coordinates remain by
  `clearView(...)`.
- Kept `clearView(...)` as the business commit point so normal no-drop drag,
  reorder, and selecting-mode behavior continue to use the existing paths.
- Verified the changes with `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606160403` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode`.

## 2026-06-16 - Thumbnail Folder Card transparent surface fix

- Follow-up testing showed that thumbnail-mode Folder Cards looked wrong in
  light mode: the outlined card interior could show an elevation shadow or a
  stale solid folder-colour fill, and the title/icon/count colours were still
  chosen against the folder background instead of the transparent list
  background.
- Fixed the root cause in `ThingsAdapter`: summary-mode Folder Cards paint
  `CardView.background` through `BackgroundUtil.applyCardBackground(...)`,
  while thumbnail mode had only called `setCardBackgroundColor(Color.TRANSPARENT)`.
  Because these are different drawable layers, recycled holders could keep the
  old summary-mode `GradientDrawable`.
- Thumbnail-mode Folder Cards now explicitly replace the outer `CardView`
  background with a transparent rounded drawable, set both `cardElevation` and
  `maxCardElevation` to `0f`, and keep only the inner `llContent` transparent
  outline drawable.
- Added a `tag_thing_folder_thumbnail_surface` marker so touch animations skip
  elevation changes for thumbnail Folder Cards, while ordinary Thing Cards and
  summary Folder Cards restore normal elevation behavior on bind.
- Changed thumbnail-mode folder title, folder icon, count text, and sticky icon
  colours to use the app/list background as the contrast base, matching light
  and dark mode resources.
- Verified the changes with `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606160528` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode`.
