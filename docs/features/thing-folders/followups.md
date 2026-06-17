# Thing Folders Followups

## Folder-Aware List Widgets

- Add Thing Folder Card rendering for Things-list widgets after the in-app
  folder model is stable.
- Define RemoteViews layouts for summary Folder Cards and any supported
  thumbnail presentation.
- Add folder projection intents so tapping a widget Folder Card opens the app at
  the matching built-in destination plus Thing Folder Path.
- Apply effective privacy and effective deletion semantics in widget data
  loading.
- Decide whether widget configuration should support root-only projections or a
  user-selected Thing Folder Path.

## Mixed List Gestures

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

## Folder Privacy And Deletion

- Add a dedicated confirmation/authentication flow for toggling an already
  private Thing Folder back to public, instead of relying only on the action
  menu authentication.
