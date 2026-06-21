# Thing Folders Followups

## Folder-Aware List Widgets

- Device-test RemoteViews List/Grid rendering on a launcher, especially
  row-packed Grid mode, full-span Thing rows, nested fill-in intents, and
  summary Folder card sizing.
- Update non-default launcher/configuration translations that still describe
  the Things-list widget as underway-only.
- Decide whether an invalid or deleted configured Folder target should only
  fall back to root at render time or also persistently clear the stale
  `target_folder_id`.
- Decide whether the Things-list AppWidget create button should authenticate
  before creating inside an effectively private Folder target.
- Decide whether empty Things-list AppWidget projections should keep the old
  notify-empty placeholder behavior, intentionally render empty content, or get
  a dedicated RemoteViews empty-state presentation after the in-app Home Empty
  State change removes stored placeholder Things.
- AppWidget RemoteViews cannot reuse the in-app RecyclerView /
  StaggeredGridLayoutManager implementation for large Folder Cards. A
  widget-side large Folder presentation can only be an approximation built from
  supported RemoteViews containers such as `GridLayout`/`LinearLayout` or from
  a top-level `GridView` collection.
- A Folder Card inside a Things-list widget should avoid nested scrolling
  collection views. If it shows child previews, prefer a fixed, non-scrollable
  preview grid with a capped number of direct child entries.

## Mixed List Gestures

- Design and implement an explicit entry point for creating an Empty Thing
  Folder now that Empty Thing Folders are valid user-owned containers.
- Tune the top-left-corner folder drop hit target and animated feedback after
  device testing if ordinary Thing reordering or intentional folder creation
  still feels too easy to trigger.
- Add a dedicated Folder move UI after the drag/selecting behavior lands. The
  current implementation should continue to support direct dragging into another
  Folder, while the later UI gives users an explicit non-drag move path.
- Add Folder Card swipe/delete behavior according to the folder state rules.
- Consider replacing the current live `ItemTouchHelper` Folder-drop path with a
  dedicated drag-session layer. The safer model would freeze RecyclerView
  structural animations while a Folder-drop candidate is armed, render hover and
  commit visuals from stable Thing/Folder ids in an overlay or controlled
  decoration, and submit the final mixed-list mutation after the drag session
  finishes. This would avoid target-card scale, outline, item move/remove, and
  mode-rebind animations writing to the same ViewHolder at the same time.
- If the full drag-session rewrite is deferred, harden the current path by
  keeping Folder-drop drag state keyed by stable Thing/Folder business ids
  without enabling RecyclerView Adapter stable ids, disabling or ending
  RecyclerView item animations during Folder-drop commit, using transformed
  target bounds for hit-testing, and replacing handcrafted post-mutation
  positions with an identity-aware diff or equivalent update contract.
- After the overlay drag controller has been device-tested, remove any
  remaining private helper code in `ThingsActivity` that only served the old
  unreachable ItemTouchHelper drag implementation and is no longer needed by
  swipe or Folder-drop hover feedback.

## Folder Privacy And Deletion

- Add a dedicated confirmation/authentication flow for toggling an already
  private Thing Folder back to public, instead of relying only on the action
  menu authentication.
