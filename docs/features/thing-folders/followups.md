# Thing Folders Followups

## 死代码清理（已完成 2026-06-23）

- ✅ 已删除死代码 `showThingFolderActions` / `showThingFolderActionsOrAuthenticate` / `addThingFolderAction` / `showFinishFolderContentDialog` / `showRestoreFolderContentDialog` 及 `FOLDER_ACTION_*` 常量，并清理了随之孤立的字符串（还原文件夹、完成/恢复当前筛选下的内容）。文件夹操作统一在工具栏 + 选择模式上下文菜单。

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

## Folder Card Thumbnail Layout

- 将全宽大 Folder Card 内部的缩略图 masonry 列数从固定 3 列改为按当前
  可用宽度响应式计算。实现时要覆盖大屏、横屏和旋转后的重新测量；同时
  检查全宽缩略图预览数量上限和省略号逻辑，避免列数增加后仍只显示过少
  子项。

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

## 回收站投影递归查询（纯骨架模型下重新评估）

- 纯骨架模型（2026-06-23）下，文件夹不再有删除状态，回收站就是 `DELETED` 状态投影：含已删记事的文件夹以 Projection Folder 出现，按“直接子项 + 子文件夹卡片”逐层显示，与正在进行/已完成投影一致。原 analysis-0620“应递归后代文件夹”的担忧基本失效。若真机上发现某嵌套层级的回收站内容不显示，再按投影层（而非数据查询层）排查修复。

## Folder Privacy And Deletion

- Add a dedicated confirmation/authentication flow for toggling an already
  private Thing Folder back to public, instead of relying only on the action
  menu authentication.

## 移动到文件夹 Dialog 根节点文案

- 2026-06-23 复核批量/范围动作文案时发现，`MoveToThingFolderDialogFragment`
  的根节点当前显示 `underway`（中文为“正在进行”），但它实际表示移动目标的根目录。
  后续应确认并改为“根目录”或“所有记事”，避免与当前状态筛选混淆。
