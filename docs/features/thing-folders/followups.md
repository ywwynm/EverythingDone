# Thing Folders Followups

## 大文件夹缩略图缓存签名补漏（已完成 2026-07-01，GPT 审查后）

移除 reload 全清（A1）后，签名覆盖面的两处缺口经 GPT 审查发现，核实均属实并已修复（详见 decisions / sessions
同日「签名补漏」条目）：
- ✅ 子文件夹 summary 计数：`folderThumbnailSignature` 的 FolderEntry 段已加入 `directFolderCount` +
  `recursiveThingCount`（summary 卡实际渲染的计数，`getFolderCardCountText`；预览 `entry.copy` 保留这两字段）。
  此前只放 `thumbnailEntryCount`，孙层及更深内容增减改这两个计数却不 bump 本子文件夹 `updateTime`（不冒泡），
  会复用旧计数。
- ✅ DAO-only 动态文本：新增 `BaseThingsAdapter.reminderSignaturePart`（`Reminder.notifyTime`+`state`）/
  `habitSignaturePart`（`Habit.intervalInfo`），签名对 reminder / goal / habit 预览 entry 纳入之。覆盖 reminder
  到点（改 `Reminder.state`）、延迟提醒 / goal 重置（改 `notifyTime`）、habit 暂停恢复（改 `intervalInfo`）等不
  bump `thing.updateTime` 的路径。代价：签名对这三类 entry 各多一次按主键 DAO 查询（命中 bind 仍远快于重建）。

## folder.updateTime 漏刷修复（已完成 2026-06-30）

三处漏刷均已修复（详见 sessions「folder.updateTime 内容增减刷新补漏（实现 P1/P2/P3）」）。核心发现：所有永久
删除最终都经 `ThingDAO.updateState` 行 383 的 `db.delete`，遂在该统一物理删除点 touch 记事所属文件夹，一处覆盖
下列前两类入口；P3 单独补。

- ✅ 永久删除所选记事（`updateStates(..., DELETED_FOREVER)`）：经物理删除点统一 touch，无需改走 `deleteThingsForever`。
- ✅ 详情页清空导致永久删除（`manager.updateState` 与 `mThingIndex==-1` 的 `ThingDAO.updateState` DAO 直写）：
  同经物理删除点统一 touch。
- ✅ 取消拖拽建夹回滚（`cancelCreatedFolder`）：末尾对成员恢复后所在容器 + 临时夹父统一补刷，保留原 location
  恢复语义。`deleteThingsForever` 里此前重复的 touch 已移除，统一到物理删除点。

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

- （2026-07-01，已完成）缩略图整树缓存扛不过导航返回的问题已解决（方向 A / A1）：去掉 reload 的
  evictAll 全清，改由 per-folder 内容签名 + 本地日历日时间桶判定失效，同一日历日内进出文件夹 / 看记事
  返回 / 来回滚动全部命中复用。详见 decisions / sessions 2026-07-01 条目。注：这只免除「重建」，下面
  「首次进入仍要完整构建」的 first-paint 成本仍在。
- 将全宽大 Folder Card 内部的缩略图 masonry 列数从固定 3 列改为按当前
  可用宽度响应式计算。实现时要覆盖大屏、横屏和旋转后的重新测量；同时
  检查全宽缩略图预览数量上限和省略号逻辑，避免列数增加后仍只显示过少
  子项。
- （2026-06-30）整树缓存只能消除"来回滚动 / 内容未变的全量重绑"两类重建，
  某文件夹**首次**进入可视区仍要完整构建（6~10 张 `card_thing` inflate + 绑定）。
  若首帧构建成本仍偏高，可评估更激进且更大改动的方向：a) 为缩略图预览做一个
  精简版预览布局替代整套 `card_thing`，从根上降低 inflate 成本；b) 快速 fling 时
  延后构建（用占位高度，settle 后补建）；c) 安全化单卡复用池（须先把
  `applyFolderThumbnailPreviewScale` 改为按基准幂等、并把内容相关字号纳入每次
  重绑）。三者都需要真机验证手感与正确性后再决定是否做。
- （2026-06-30）本次"实测分列 + 整树缓存"改动需真机 sideload 复测：分列是否均匀、
  滑动是否顺滑、以及私密揭示/外观编辑实时预览/选择模式切换下缩略图是否仍正确
  （签名是否覆盖到位）。
- （2026-06-30，临时埋点，诊断完成后移除）`ThingsAdapter` 加了缩略图绑定性能日志，开关
  `DEBUG_FOLDER_THUMBNAIL_PERF`，异步写 `debug_logs/folder_thumbnail_perf.log`。每次绑定大文件夹缩略图记一行：
  `id / 标题 / span(列数) / entries(显示/总数) / scroll(IDLE|DRAG|FLING) / cache(HIT|MISS_NEW|MISS_SIG|
  MISS_DETACHED) / sig / build / attach / measure(该大卡 measure 耗时) / bind(总耗时)`；reload 清缓存时记
  evictAll 行。用途：定位"缓存命中却仍卡"——若 cache=HIT 且 build≈0 但 measure 偏大，说明卡在 RecyclerView
  对大子树的 measure/layout（缓存省不掉它），下一步可往"降子树层级/复杂度、或固定高度避免重测"优化。诊断
  完成后连同 `debugMeasureFolderCard` / `logFolderThumbnailPerf` / `FolderThumbnailObtain` 一并移除
  （`obtainFolderThumbnailTree` 回退为返回 View）。**（2026-06-30 已置 `DEBUG_FOLDER_THUMBNAIL_PERF=false`
  关闭，不再写日志/不再额外 measure；埋点代码暂留备后续 measure 优化诊断，若确定不再用可彻底移除。）**

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
