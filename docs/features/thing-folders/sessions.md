# Thing Folders Sessions

## 2026-06-24 - 单个记事 Widget 配置顶部栏跟随文件夹配色

`:app:assembleDebug` BUILD SUCCESSFUL。随后通过 `:app:publishDebugUpdate` 发布到阿里云 debug 通道，更新码 `202606241334`，发布日志为 `docs/features/thing-folders/debug-updates/update-20260624213401.md`。

- `BaseThingWidgetConfiguration` 新增顶部 chrome 刷新逻辑，统一设置 `view_status_bar`、`Toolbar` 背景，以及标题、返回图标、overflow/menu 图标颜色。
- 根目录继续使用 `App.defaultAccentBackground`，但标题和图标固定使用 `white_86p`，系统状态栏图标使用浅色模式。
- 打开文件夹后使用文件夹的纯色或渐变背景，并按代表色自动选择偏白或偏黑前景；返回上级、进入文件夹、结束 Widget 预览都会恢复当前范围的顶部配色。

## 2026-06-24 - 单个记事 Widget 配置隐藏空文件夹

`:app:assembleDebug` BUILD SUCCESSFUL。随后随同 Widget 媒体几何修正发布到阿里云 debug 通道，更新码 `202606241314`，发布日志为 `docs/features/thing-card-media-target-geometry/debug-updates/update-20260624211357.md`。

- `BaseThingWidgetConfiguration.loadCurrentFolderEntries()` 的文件夹卡片来源从普通首页投影切换为 `ThingFolderDAO.getFolderEntriesForWidgetProjection(...)`。
- 单个记事 Widget 配置页现在只显示子树中存在正在进行记事内容的文件夹；完全为空、或当前 Widget 可选投影没有命中记事的文件夹不会显示。
- 这只影响单个记事 Widget 的选择列表，不改变 Drawer、移动到文件夹 Dialog 和记事列表 Widget 范围选择器的稳定文件夹骨架语义。

## 2026-06-24 - 记事状态变更保留置顶状态

`:app:assembleDebug` BUILD SUCCESSFUL。

- 修正记事完成、删除、恢复时会丢失置顶的问题：`ThingManager` 在非撤销状态变更中检查原记事 `location`，负数则重新分配到当前父级置顶区顶部之前的新负数位置，再交给 `ThingDAO` 写库，不再固定写入非置顶区。
- 非置顶记事仍沿用既有行为，状态变更后进入目标状态列表的非置顶区顶部；批量状态变更只按非置顶条目数量前移 header。
- 状态变更落位继续放在 `ThingManager` 编排，和普通非置顶记事的状态变更流程保持一致；详情页和通知/远程动作这类直连 DAO 的边缘路径也先向 `ThingManager` 取得目标 `location`。
- 已同步 `preferences.md` 与 `decisions.md`。此前 DAO 计算落位版本已通过 `:app:publishDebugUpdate` 发布到阿里云 debug 通道，更新码为 `202606240628`，发布日志为 `docs/features/thing-folders/debug-updates/update-20260624142807.md`；本次按用户确认把状态变更落位回迁到 `ThingManager` 后，已重新发布到阿里云 debug 通道，更新码为 `202606240642`，远端 `latest.json` 指向 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606240642.apk`，SHA-256 为 `c7d14ae45802bdcb89a31b3760513baeabe3d0d5b2de832ea71a365b8baff0ec`，发布日志为 `docs/features/thing-folders/debug-updates/update-20260624144228.md`。

## 2026-06-24 - 移动与拖拽建夹保留置顶状态

`:app:assembleDebug` BUILD SUCCESSFUL。随后通过 `:app:publishDebugUpdate` 发布到阿里云 debug 通道，更新码为 `202606240340`，发布日志为 `docs/features/thing-folders/debug-updates/update-20260624114005.md`。

- `ThingManager.createFolderFromThings` 改为用两个成员的置顶状态共同决定新文件夹位置：任一成员置顶则新文件夹放入当前父级置顶区顶部，否则放入非置顶区顶部。
- `moveThingIntoFolderInternal` 移动记事时按记事当前 `location < 0` 选择目标父级置顶区或非置顶区，拖拽移动与 Dialog 移动共用该规则。
- `moveFolderIntoFolder` 移动文件夹时按 `folder.isSticky()` 选择目标父级置顶区或非置顶区，目标文件夹接收子项时自身置顶状态不变。
- 已同步 `preferences.md` 与 `decisions.md`，并移除对应的临时 followup。

## 2026-06-24 - 根标签条件化、移动 Dialog 高度自适应、文件夹颜色信息、FAB ripple

完成一组跨界面小迭代（详见 `decisions.md` 同日条目），均通过 `:app:assembleDebug`：

- 根范围根标签（Drawer/移动 Dialog/Widget 配置）按数据库是否有文件夹行切换"所有记事"/"所有内容"，新增 `all_content` 字符串（en、zh-rCN/HK/TW），新增 `ThingFolderDAO.hasAnyFolder()` 与 `ThingManager.hasAnyFolder()`。
- 移动到文件夹 Dialog 列表由固定 280dp 改为按行数自适应、6 行封顶滚动，分界线沿用原 license 式逻辑。
- 三套 `menu_things_*` overflow 新增"颜色信息"项，复用 `ColorInfoDialogFragment` 展示当前文件夹背景。
- 首页根目录新建记事 FAB ripple 从随机新记事色改为固定偏白（`0x3DFFFFFF`），文件夹内仍按文件夹色 `onColor`。
- 另：移除"无标题不能设私密"限制（详见全局 `memory/decisions.md`）。

## 2026-06-23 - 文件夹内容删除改为按当前状态执行

根据用户确认，文件夹“删除/永久删除其中记事”的内容操作不跨状态：

- 正在进行状态：文件夹内容删除只把范围内正在进行的记事移入回收站，保留已完成记事不动。
- 已完成状态：删除动作只作用于范围内已完成的记事。
- 回收站状态：内容永久删除只作用于范围内已删除的记事。
- 结构性“永久删除文件夹”仍是另一类动作，会删除文件夹容器及其全部内容，dialog 文案单独说明。

实现上，统一文案 helper 让标题和正文都明确写出当前状态限定，例如“删除当前文件夹中所有正在进行的记事”“删除所选文件夹中所有已完成的记事”，避免与实际行为不一致。验证：`:app:assembleDebug` BUILD SUCCESSFUL。

## 2026-06-23 - 全宽缩略图文件夹卡片列数随屏幕自适应

- 全宽（`SPAN_FULL`）缩略图文件夹卡片瀑布流列数由固定 3 改为“首页列表 span + 1”：`ThingsAdapter.createFolderThumbnailMasonryView` 构建时动态读 `getBoundListSpanCount()`（新增于 `BaseThingsAdapter`），随 `onConfigurationChanged` 全量重绑自旋转适应。
- 显示数量改为屏幕感知（`folderThumbnailFullSpanDisplayCount`）：手机固定 6，平板 `2×列数`；取数上限 `FULL_SPAN_THUMBNAIL_PREVIEW_LIMIT` 由 6 调到 10，与显示数量解耦，省略号仍按真实总数。
- 详见 `decisions.md`（2026-06-23）。已编译通过，随 debug update `202606230911` 发布，待设备验证。

## 2026-06-23 - use-cases 一致性审计后的修复

对照 use-cases.md 逐用例审计实现，更新过时文档并修复 B 类问题，`:app:assembleDebug` BUILD SUCCESSFUL：

- use-cases 更新（实现按后续需求演进）：核心规则 #1 回收站改为状态分段胶囊的一段（非独立 Drawer 区域）；核心规则 #2 + 操作菜单：完成/恢复改为“文件夹中所有记事”全类型递归（filter-scoped 版本不可达），操作入口在选择模式上下文菜单 + 工具栏。
- B1（T5，原 D4）：删除子文件夹后其内容在回收站不可见的 bug。`ThingFolderDAO.countDescendantThingsForTypeFilterProjection` 对 Projection Folder（DELETED 状态、自身未删）改用新的 `trashedDescendantThingSelection`——统计“自身 state=DELETED 或位于已删子文件夹内”的后代。`shouldIncludeFolderForTypeFilterProjection`/directFolderCount 复用此方法，文件夹纳入与计数标签一并修正。
- B2（W3/T4）：`AppWidgetHelper.resolveThingsListTargetFolder` 在目标文件夹失效（不存在或有效删除）时，调用新增的 `AppWidgetDAO.clearTargetFolder` 持久清空 target folder（回退到根，保留状态/类型/显示模式/透明度/样式），并同步 in-memory info。
- 确认 Dialog 0 计数省略：删除/解散/永久删除确认文案改为单一 `%1$s` 影响短语，由 `ThingsActivity.folderImpactPhrase(folders, things)` 按非零拼接（“X 个子文件夹、Y 件记事”，任一为 0 则省略该段，全 0 显示“无内容”）。新增 `folder_count_segment`/`thing_count_segment`/`folder_impact_separator`/`folder_impact_empty` 字符串（en/zh-rCN）。
- 创建后大文件夹缩略图不刷新 bug：`updateMainUiForCreateDone` 在类型筛选被重置（自定义→全部类型）时，改为整列 `loadThings()` + `notifyDataSetChanged()`，而非定向 `notifyItemInserted`——否则 `mThings` 未按新 mask 重载、且文件夹卡片未重新绑定，导致缩略图仍按旧筛选显示。

## 2026-06-22 - 已完成工具栏新增“恢复全部记事”

`:app:assembleDebug` BUILD SUCCESSFUL。已完成状态工具栏新增 `act_restore_all` 图标（复用 `act_restore_all` drawable），对应正在进行的“全部完成”：根目录显示“恢复全部记事”、文件夹内显示“恢复文件夹中所有记事”（`configureCurrentFolderMenu` 动态设标题）。点击 → `confirmUnfinishAllThingsInScope(currentFolder)`，递归把当前范围所有已完成记事恢复为正在进行。新增字符串 `restore_all_things`（en/zh-rCN）。

## 2026-06-22 - 修正完成入口位置 + 已完成/回收站递归恢复

`:app:assembleDebug` BUILD SUCCESSFUL。

- 修正：上一轮把“完成文件夹中所有记事”加到了 `showThingFolderActions`，但该方法**无调用者、是死代码**（2026-06-17“长按进选择模式”决议后遗留）。实际文件夹操作在选择模式上下文菜单（`OnContextualMenuClickedListener` + 三个 menu_contextual_*.xml + `ModeManager.updateMenuItemsForFolderSelection`）。改为在 underway 上下文菜单新增 `act_finish_thing_folder` → `confirmFinishAllThingsInScope(folder)`，并在 ModeManager 控制其可见（单选非删除文件夹 + 正在进行）。死代码 showThingFolderActions 暂留（无害），记 followups 待清理。
- 新增递归恢复（对应递归完成）：
  - 已完成上下文菜单 `act_restore_thing_folder_content` → `confirmUnfinishAllThingsInScope`：递归把文件夹子树所有已完成记事恢复为正在进行。
  - 回收站上下文菜单同 id，对 Projection Folder（自身未删、含回收站内容）→ `confirmRestoreTrashedThingsInScope`：递归把子树内已删记事恢复到删除前状态。Trashed Folder 仍用 `act_restore_selected` 恢复整个子树（Phase D）。
- Manager 新增 `getFinishedThingsInScope`/`unfinishThings`、`getTrashedThingsInScope`/`restoreTrashedThings`；这两个恢复操作不涉及习惯/目标三选项 dialog（仅完成才需要）。
- 新增字符串 `restore_all_things_in_folder` 及两条 confirm、两条 no_*（en/zh-rCN）。

## 2026-06-22 - 完成文件夹中所有记事（递归 + 习惯/目标三选项 dialog）

`:app:assembleDebug` BUILD SUCCESSFUL。

- `ThingDAO.getAllUserThingsByState`：取某状态的全部用户记事（root 全部完成用）。
- `ThingManager.getUnderwayThingsInScope(folder)`：folder 非空取子树全部类型正在进行；folder=null 取整棵树正在进行并排除 `isEffectivelyDeleted` 的。`finishThings(things)` 公开包装 `changeFolderSubtreeContentState(UNDERWAY→FINISHED)`。
- `ThingsActivity`：`confirmFinishAllThingsInScope(folder?)` 先确认（数量 + 文件夹/accent 渐变色），含习惯/目标则弹 `showFinishScopeHabitGoalDialog`（三选项，去掉/继续/取消），`applyFinishScope` 执行并刷新。
- 入口：Folder Card 菜单新增 `FOLDER_ACTION_FINISH_ALL`；工具栏 `act_finish_all` 改为 `confirmFinishAllThingsInScope(currentFolder)`（递归）；`configureCurrentFolderMenu` 在文件夹内把标题改为“完成文件夹中所有记事”。Phase C 的“完成当前筛选下的内容”改为仅在有自定义类型筛选时出现。
- 三选项 dialog 配色：新路径与既有 `alertForHabitGoal` 都改用 `setTitleBackground`/`setContinueBackground`（文件夹色或 `App.defaultAccentBackground`），不再随机纯色。
- 新增字符串 `finish_all_things_in_folder(_confirm)`、`no_underway_things_to_finish`（en/zh-rCN）。

## 2026-06-22 - 剩余项打磨：确认文案影响摘要 + Widget 标题状态

`:app:assembleDebug` BUILD SUCCESSFUL。

- 删除/解散/永久删除文件夹的确认 Dialog 文案补全影响范围摘要：明确“整个物理文件夹（含当前筛选下看不到的内容）”，并显示 %1$d 个子文件夹、%2$d 件记事。`ThingManager` 新增 `countAllDescendantThings`/`countDescendantFolders`；三处 dialog 用 `getString(id, folders, things)`；en/zh-rCN 三条 confirm 串改为带 %1$d/%2$d（其它语种回退英文，不传参时安全）。
- Widget header 标题反映状态：`AppWidgetHelper.getThingsListHeaderTitle` 增 status 参数，按 文件夹名 · 已完成 · 类型名 组合，根+正在进行+全部回退“正在进行”。
- 核查 analysis-0620 “moving-mode scale recovery debug logging”：`ThingListOverlayDragController.log()` 已被 `OVERLAY_DRAG_DEBUG=false` 开关守住，无需再清理；`tag_thing_card_moving_scale_recovery_token` 是功能性 token，保留。
- 其它语种本地化补全（量大价值低、回退英文）与 D4 递归查询（缺复现）继续保留在 followups。

## 2026-06-22 - Phase F：非正在进行禁用拖拽到文件夹

`:app:assembleDebug` BUILD SUCCESSFUL。在 `ThingListOverlayDragController.findFolderDropTargetUnderOverlayTopLeft` 顶部加状态守卫：`App.getApp().getStatus() != UNDERWAY` 时返回 null，使已完成/回收站状态下的拖拽只能重排、不触发建夹或移入文件夹（hover 与 commit 共用此入口，一处生效）。结构性移动改走显式“移动到文件夹”。符合 Q2 决议。

剩余 Phase F 小项（移动/解散确认文案细化、analysis-0620 的 debug logging 清理等、其它语言本地化补全）见各 followups，属低优先打磨。

## 2026-06-22 - Phase D：删除前状态持久化 + 级联恢复 + 回收站文件夹区分

`:app:assembleDebug` BUILD SUCCESSFUL。含 DB 迁移。

- D1 迁移：`Def.Meta.DATABASE_VERSION` 17→18；新增 `things.state_before_delete` 列（建表 SQL、`migrateStateBeforeDeleteColumn`、`SQL_ADD_COLUMN_STATE_BEFORE_DELETE_THINGS`）。`ThingDAO.updateState` 在转入 DELETED（且原状态非 DELETED）时写入 `state_before_delete = stateBefore`；新增 `ThingDAO.getStateBeforeDelete(id)`（无记录回退 UNDERWAY）。不改 Thing 模型，避免触碰其众多构造器/Parcelable。
- D2 单条/批量恢复回删除前状态：`ThingsActivity.handleUpdateStates(stateBefore, stateAfter)` 在恢复（DELETED→UNDERWAY）时，若所选删除项的删除前状态一致为 FINISHED 则整体恢复为 FINISHED，否则 UNDERWAY。单条恢复必然一致，正好满足 T0b。保留既有计数/撤销机制（仍是单一状态转换）。`ThingManager.getStateBeforeDelete` 透传 DAO。
- D3 文件夹级联恢复（决议 b）：重写 `ThingManager.restoreFolder` 恢复整个物理子树——置回文件夹自身状态、把所有嵌套已删子文件夹置回 UNDERWAY、把子树内自身 state=DELETED 的 Thing 经 `restoreThingsToPreTrashState` 按各自删除前状态分组恢复（复用 `changeFolderSubtreeContentState`，不污染 mUndo*）。
- D5 回收站文件夹区分：新增矢量 `ic_thing_folder_deleted`（folder 图形 evenOdd 挖空 × 删除标记）；`ThingsAdapter.bindFolderCardContent` 对 `folder.isDeleted()` 的 Trashed Thing Folder 用该图标，Projection Folder 仍用 `ic_thing_folder`。
- D4 暂缓：`getThingsForEffectiveDeletedFolderProjection` 的"递归后代"改动缺乏明确复现场景，新模型下打开文件夹显示直接子项+子文件夹卡片，直接子查询符合预期；贸然改动有破坏直接子投影风险，标记到 followups 待复现后再评估。

## 2026-06-22 - Phase C：文件夹内容态操作（完成/恢复当前筛选内容）

`:app:assembleDebug` BUILD SUCCESSFUL。

引擎层：`ThingFolderDAO.getDescendantThingsForProjection(folderId, status, typeFilterMask)` 取子树内（含自身及后代文件夹）状态+类型命中的 Things。`ThingManager` 新增 `finishFolderContent`、`restoreFolderContentToUnderway`、`countFolderContentForProjection`，以及私有 `changeFolderSubtreeContentState`——复用既有 finish/restore 语义（`Thing.getSameCheckStateThing` 处理清单勾选、ongoing 通知取消、按类型计数 `mThingsCounts.handleUpdate`、习惯/目标重置、已完成时取消通知），跨子树后用 `loadThings()` 整体刷新而非对 `mThings` 做增删。

UI：在 `showThingFolderActions` 动作菜单中按当前状态加入入口——正在进行投影显示“完成当前筛选下的内容”，已完成投影显示“恢复当前筛选下的内容为正在进行”（回收站不显示）。两者经 `AlertDialogFragment` 确认，文案含影响范围数量（`countFolderContentForProjection`）。新增字符串 `finish_thing_folder_content(_confirm)`、`restore_thing_folder_content(_confirm)`（en + zh-rCN，带 %1$d 数量占位）。

注意：内容态操作只作用于当前类型筛选命中的 Things，文件夹容器本身无完成状态（符合 use-cases F1/F2/F3）。

## 2026-06-22 - Phase A 投影语义骨架（模型层正交 + 标题/创建/回根）

实现"三个稳定维度"的模型层骨架，`:app:assembleDebug` BUILD SUCCESSFUL：

- `ThingListProjection.withStatus`：保留 folderPath + typeFilterMask（不再清空），使状态与 Scope/类型正交。
- `ThingManager.setStatus`：切状态后调 `trimProjectionToVisibleFolders()` 做越界回退，并用 `trimAuthenticatedPrivateFoldersToProjection()` 取代无条件 `mAuthenticatedPrivateFolderIds.clear()`，保留仍在路径上的私密认证。
- `trimProjectionToVisibleFolders`：改为新模型规则——非删除文件夹在任何状态都是合法 Scope（回收站下显示其已删内容），已删文件夹只在 DELETED 合法，否则路径回退。
- `ActivityHeader`：标题只显示状态名（根）或文件夹名，删除 type-filter 文本分支（决议 #16）。
- `ThingsActivity.updateMainUiForCreateDone`：创建返回时若有自定义类型筛选，重置为全部类型（保留 Scope/状态），保证新建记事可见。
- `ThingsActivity` 外部投影：`openExternalProjectionFromIntent` 的 root 意图分支、`openExternalFolderProjection` 的无效文件夹分支改用 `navigateToFolderPathIndex(-1)` 显式回根，修复 withStatus 保留路径后可能残留旧 folderPath 的问题，并实现范围失效持久回退到全部记事。

注意：当前 Drawer 仍把"正在进行=状态+文件夹根"耦合，完整 UX 需 Phase B（Drawer 重构 + 胶囊筛选组件）。drawer-type-filter 的两项 deferred follow-up（保留 mask、标题语义）随本阶段完成。

## 2026-06-22 - 实现前 use-cases 复审：解决 6 处问题

实现前带批判视角复审 `use-cases.md` 并与既有决议、代码交叉验证，确认全量推进（新“三个稳定维度”模型）的目标边界后，逐条排查出并解决 6 处问题：

1. 移动到文件夹 Dialog 根标签：代码与 2026-06-19 旧决议为“正在进行”，与新模型冲突，改为“全部记事”（作废旧决议表述；`MoveToThingFolderDialogFragment` 当前用 `R.string.underway`，需换新串）。
2. 非正在进行状态下的拖拽：在已完成/回收站禁用一切拖拽到文件夹（建夹与移动均禁），只保留重排/选择；整理历史用显式移动。
3. 创建默认类型：创建从不预设类型，且创建后类型筛选重置为“全部类型”，保证新建记事可见；作废 L9 旧建议。
4. 回收站文件夹区分：Trashed Thing Folder 在 folder icon 内嵌删除小图标（复用 `FolderIconDrawable` 嵌锁做法），Projection Folder 普通 icon 无徽标。
5. 恢复文件夹与独立删除的后代：选 (b) 整夹恢复——自身已 DELETED 的后代也随父恢复到各自“删除前状态”。审计确认这依赖“删除前状态”持久化，而该能力本就被 T0b（单个 Trashed Thing 恢复回 Pre-Trash 状态）独立要求；当前代码恢复一律置 UNDERWAY 且 `finishTime` 不可靠推断，属已有缺陷。需新增删除前状态列（可随 DB v15 一起）。
6. 拖拽移夹：保留，规则统一为“移动整个物理子树、与筛选无关”，仅正在进行可用；显式移动 Dialog 作补充。

排查父→子一致性：删除/永久删除/移动/恢复文件夹按整个物理子树；解散为直接子项上移一级；完成文件夹内容/恢复当前筛选内容仅作用于当前筛选命中。两类操作由确认 Dialog 章节明确区分，全局自洽。新增的唯一能力是“删除前状态”持久化。

决议写入 `decisions.md`，use-cases L9/T3/G3 已同步修正。下一步进入实现 Phase A（投影语义骨架）。

## 2026-06-22 - 稳定文件夹骨架和投影路径补充

- 将文件夹语义收敛为“文件夹是稳定组织骨架，状态筛选只决定内容投影”：完成、恢复或删除单个 Thing 时保留原文件夹归属，不把内容移动到根目录。
- 补充 `use-cases.md`：新增投影空的 Y、类型筛选空的 A、类型筛选下恢复已完成内容、删除单个 B、删除子文件夹 D、恢复时原父级失效、移动 A 后 Drawer 物理树、Widget 直接子项投影等用例。
- 修正拖拽创建文件夹用例：B 和 E、C 和 F 在 A 的投影中不是同层可见项，不能直接拖拽合并；拖拽创建文件夹只适用于当前列表同层可见的 Thing Cards。
- 确认单个 Trashed Thing 从回收站恢复时回到删除前状态：正在进行的 Thing 恢复为正在进行，已完成的 Thing 恢复为已完成。

## 2026-06-22 - 文件夹和状态语义用例矩阵

- 在 `use-cases.md` 中记录混合状态 Thing Folder 子树的工作模型：文件夹保持为稳定范围容器，普通状态筛选只包含正在进行/已完成，回收站是独立生命周期区域，普通范围选择器显示所有未进入回收站的文件夹。
- 补充 Drawer 范围可见性、列表投影、完成文件夹、删除/恢复文件夹、解散文件夹、移动文件夹或单个 Thing、拖拽创建文件夹、记事列表 Widget 配置等具体用例。
- 明确实现区分：状态操作作用于当前投影命中的 Things；结构和生命周期操作作用于整个物理文件夹子树，并保留子项状态。

## 2026-06-21 - Dynamic Things-list AppWidget scope height

Adjusted the Things-list AppWidget configuration Folder scope picker so its
height follows the current visible row count. The picker now uses 44dp per
visible row and caps at four rows (176dp), which preserves the previous maximum
height only when the visible Folder tree needs scrolling. Expanding or
collapsing Folder rows recomputes the height immediately.

Verification: `E:\projects\EverythingDone\gradlew.bat :app:assembleDebug`
completed with `BUILD SUCCESSFUL`. The change shipped in debug update
`202606210410`.

## 2026-06-20 - Folder-scoped AppWidget create-return appearing animation

- Updated the Folder-scoped Things-list AppWidget create-return path to keep
  the ordinary Things appearing animation for the target Folder projection.
- Preserved the duplicate-card fix: `updateMainUiForShortcutFolderCreateDone()`
  still returns before the ordinary same-list create handling can arm the
  created-card animation or call `notifyItemInserted()`.
- Clarified the documented rule: the duplicate risk comes from a second adapter
  insertion signal after projection reload, not from the projection rebind's
  appearing animation.

Verification: a targeted static check confirmed the special create-return path
still returns before ordinary create notifications, does not call
`notifyItemInserted()` or `armNewItemAnimation()`, and enables
`shouldThingsAnimWhenAppearing`. `.\gradlew.bat :app:assembleDebug` completed
with `BUILD SUCCESSFUL`. `git diff --check` passed with only the repository's
existing LF/CRLF warnings. Published debug update `202606201337` and verified
remote `latest.json` points at
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606201337.apk`.
Remote SHA-256:
`77c8c6e3dd445cc21f744447c24e28b52a3f07541ba5fb00e794d6a8bdad2502`.

## 2026-06-20 - Folder-scoped AppWidget create-return duplicate-card fix

- Diagnosed a create-return regression after preserving Folder scope for
  Things-list AppWidgets: the Folder projection opener loaded the target Folder
  and called `notifyDataSetChanged()`, then the ordinary create-result handler
  still armed the new-item animation and called `notifyItemInserted()` against
  data that already contained the new Thing.
- Split Folder-scoped widget create-return into a dedicated
  `updateMainUiForShortcutFolderCreateDone()` path. It opens the external
  Folder projection once, disables the whole-list appearing animation for that
  rebind, clears pending `justNotifyAll` state, and returns before the ordinary
  same-list insertion logic can run.
- Kept normal AppWidget/header Folder opens on the existing external projection
  behavior, including their ordinary list-appearing treatment; only the
  create-return path suppresses the extra animation/insert notification.

Verification: source inspection confirms the special create-return path cannot
reach `armNewItemAnimation()` or `notifyItemInserted()`. A targeted static check
against `ThingsActivity.kt` passed. `.\gradlew.bat :app:assembleDebug`
completed with `BUILD SUCCESSFUL`. `git diff --check` passed with only the
repository's existing LF/CRLF warnings. Published debug update `202606201322`
and verified remote `latest.json` points at
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606201322.apk`.
Remote SHA-256:
`30ea338634b61246a85d1c29bd8f2b5408a3edafd6e8b7de224fcb95c66267d1`.

## 2026-06-20 - AppWidget Folder picker and create-return polish

- Matched the Things-list AppWidget configuration Folder picker with Drawer
  private Folder iconography by using `DrawerNavigationView.FolderIconDrawable`
  for Folder rows, including the embedded lock for private Folders.
- Tightened the Folder picker row chrome by adding a 2dp icon-to-title gap and
  changing trailing expand/collapse affordances from rectangular row ripple to
  the App Chrome circular ripple treatment.
- Preserved Folder scope when completing a create flow launched from a
  Folder-scoped Things-list AppWidget. `DetailActivity` now includes the
  Shortcut-created Folder target in the create result, and `ThingsActivity`
  reuses the existing external Folder projection opener so returning home shows
  that Folder instead of root.

Verification: `.\gradlew.bat :app:assembleDebug` completed with
`BUILD SUCCESSFUL`. `git diff --check` passed with only the repository's
existing LF/CRLF warnings. Published debug update `202606201312` and verified
remote `latest.json` points at
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606201312.apk`.
Remote SHA-256:
`e44d04bbf31c50717aac6fa961466fa6059fd4cc5c8950f8a38ddf838440f427`.

## 2026-06-20 - AppWidget divider, action, and Folder foreground follow-up

- Kept the Things-list AppWidget configuration scope picker's bottom divider
  always visible as the stable boundary below the Folder list, while retaining
  the top divider's scroll-up-only behavior.
- Removed the legacy inline finish action from Single-Thing AppWidgets by
  always hiding the `ll_thing_action` RemoteViews section; this also removes
  that action section's separator without affecting reminder, habit, or state
  separators.
- Changed Things-list AppWidget Folder summary cards to use the same luminance
  foreground tiers as home summary Folder Cards: primary foreground for the
  icon and title, secondary for the private lock, and tertiary for count text.

Verification: `.\gradlew.bat :app:assembleDebug` completed with
`BUILD SUCCESSFUL`. `git diff --check` passed with only the repository's
existing LF/CRLF warnings. Published debug update `202606201255` and verified
remote `latest.json` points at
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606201255.apk`.
Remote SHA-256:
`386009843ac3e6590c7c74761595f1bb800a021aa85b1d4dd9335a477b393c24`.

## 2026-06-20 - Things-list widget configuration scroll divider polish

- Adjusted the Things-list AppWidget configuration scope picker to match app
  chrome chooser-dialog scroll dividers: the top divider appears only after the
  picker can scroll upward, and the bottom divider appears only while more
  scope rows remain below.
- Tightened the five type-filter icon touch/selected targets from 48dp to 40dp,
  preserved the 24dp icon content size, and added 2dp spacing between adjacent
  icons through shared dimensions.
- Changed the configuration confirm button's top gap to use
  `app_chrome_dialog_divided_action_row_margin_top`, keeping the bottom action
  spacing aligned with divided app chrome dialogs.
- Recomputed scope-picker divider visibility after Folder selection,
  expansion, collapse, and private-Folder authentication so the chrome stays in
  sync after row-count changes.

Verification: `.\gradlew.bat :app:assembleDebug` completed with
`BUILD SUCCESSFUL`. `git diff --check` passed with only the repository's
existing LF/CRLF warnings. Published debug update `202606201212` and verified
remote `latest.json` points at
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606201212.apk`.
Remote SHA-256:
`6732202f3bf21105b85ec7cf587ee7acfc97c7d6161b5b8e43f29aa94cb7c6b1`.

## 2026-06-20 - AppWidget folder/card follow-up polish

- Replaced RemoteViews media alpha calls for Thing media backgrounds and
  foreground image/video slots with bitmap-level alpha composition, because
  `ImageView.setImageAlpha` did not update widget preview or launcher media
  reliably.
- Changed the single-Thing widget preview confirm control from a styled
  `Button` to a plain `TextView` with only a foreground pill ripple and
  Thing-background-aware text colour.
- Made Things-list widget Folder summary cards use the same rounded root
  clipping path as Thing cards.
- Audited RemoteViews card foreground affordances: audio icons and checklist
  icons now receive adaptive black/white tints, and all widget card dashed
  separators switch between white and black drawable resources based on the
  rendered Thing background.
- Updated AppWidget foreground luminance checks to use the Thing background's
  representative colour instead of only the legacy colour int, keeping pure and
  gradient backgrounds aligned with the rendered card.
- Adjusted the Things-list widget configuration panel so type filters keep
  circular ripple icon targets with a live type summary label, and List/Grid
  display mode uses the Thing Card appearance panel's label-plus-text-options
  pattern instead of radio controls.
- Opened current Doing Things from `AuthenticationActivity` with the main app
  task flags so list-widget clicks no longer inherit the authentication task
  window context.

Verification: `.\gradlew.bat :app:assembleDebug` completed with
`BUILD SUCCESSFUL`. `git diff --check` passed with only the repository's
existing LF/CRLF warnings. Published debug update `202606201152` and verified
remote `latest.json` points at
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606201152.apk`.
Remote SHA-256:
`9d062b8c9443a81138d72f9c251940c0bb5722d59891b672b9a334f870ee33f8`.

## 2026-06-20 - Direct parent scroll restore without Header flicker

- Changed saved Folder projection restore so `ThingsActivity` applies the
  saved `RecyclerView.LayoutManager` state directly instead of posting the
  restore to the next loop.
- Reordered parent/ancestor navigation refresh so Activity surface and Header
  text are refreshed before the saved layout state is restored, then scheduled
  a pre-draw Header state refresh from the restored first visible adapter
  position.
- Cancelled pending Activity Header translation, title-scale, subtitle-alpha,
  and shadow animations before non-animated Header state writes, so returning
  to a parent Folder can jump to the saved visual state without a leftover
  animation moving the Header afterward.
- Further thickened `vec_ic_create_thing` at the vector path level with a
  white stroke so the create-Thing FAB glyph reads stronger without changing
  FAB padding or layout.
- Follow-up feedback showed the root cause of Folder return motion was the
  Things appearing animation rather than smooth scrolling. Parent/ancestor
  restore paths now disable `setShouldThingsAnimWhenAppearing` for that rebind,
  while new child Folder opens still keep the top-start appearing treatment.
- Reduced the create-Thing vector stroke from 28 to 18 viewport units and
  restored the root create FAB icon tint to `black_54p`, matching the original
  plus icon's foreground strength on the yellow FAB.

Verification: `git diff --check` passed with only the repository's existing
LF/CRLF warnings. `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Published debug
update `202606200558` and verified remote `latest.json` points at
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200558.apk`.
Remote SHA-256:
`f1775e6462875b3bc17a40a6eaa8de155696b09ffd2b9ffcdf99c0f6c1de4936`.

## 2026-06-20 - Folder navigation scroll restore and stronger gradient chrome tint

- Added Activity-local per-projection scroll-state caching in `ThingsActivity`.
  Opening a child Folder still starts at the top, but returning to the parent
  Folder projection restores the parent's previously saved
  `RecyclerView.LayoutManager` state after the list rebinds.
- Applied the same restore path to Activity Header path-segment navigation so
  jumping back to an ancestor projection can reuse that ancestor's saved scroll
  state.
- Kept the restore guarded by `ThingListProjection.key()` so a delayed posted
  restore cannot apply after the user has already navigated elsewhere.
- Updated gradient toolbar icon tint so `BackgroundUtil.tintDrawable(...)`
  normalizes the drawable alpha mask before filling it with the Folder
  gradient. This keeps gradient-tinted icons as strong as pure-colour toolbar
  icons and avoids tinting the whole touch target.
- Adjusted `vec_ic_create_thing` at the vector-resource level so the create
  FAB glyph is slightly larger and visually shifted back toward center without
  changing the FAB layout.

Verification: `git diff --check` passed with only the repository's existing
LF/CRLF warnings. `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Published debug
update `202606200526` and verified remote `latest.json` points at
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200526.apk`.
Remote SHA-256:
`d0087e48b0b409e85c24304ebc07315bc82a7ccdbf7af5b6d302321553d22df9`.

## 2026-06-20 - Recursive header Thing counts and updated action icons

- Updated `ThingManager.getVisibleChildCountsForActivityHeader()` so the
  Folder count remains direct visible child Folders, while the Thing count adds
  each direct child Folder's `recursiveThingCount` to the direct visible Thing
  count.
- Copied `vec_ic_create_thing` and `vec_ic_start_thing` from
  Everything-Android into this app's drawable resources.
- Swapped the ThingsActivity create FAB to `vec_ic_create_thing` and explicitly
  tint the icon from the FAB background luminance so the root yellow FAB keeps
  readable contrast.
- Replaced all code call sites that used `R.drawable.act_start_doing` with
  `R.drawable.vec_ic_start_thing`, preserving existing ImageView, compound
  drawable, and notification action sizes.

Verification: `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. `git diff --check`
passed with only the repository's existing LF/CRLF warnings. Published debug
update `202606200438` and verified remote `latest.json` points at
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200438.apk`.
Remote SHA-256:
`a2a1c2c0bbc2c27d513a921efca20a0f4c259197f317cfc8e8764dabc90e7d12`.

## 2026-06-20 - Folder projection chrome tint and root child counts

- Moved `BackgroundUtil.mutedSurfaceBackground(...)` closer to the list surface
  by reducing the Folder-accent blend to 5% in light mode and 8% in dark mode.
- Added Folder-aware normal ThingsActivity chrome: the in-Folder create FAB
  adopts the current Folder pure colour or gradient, Home actionbar menu icons
  adopt the Folder tint, and root projections reset those surfaces to app
  chrome colours.
- Added gradient support to the app custom `FloatingActionButton` by keeping
  native Material FAB tint for pure colours and drawing a circular gradient in
  `onDraw` for gradient Folder backgrounds.
- Updated contextual selecting mode so the contextual toolbar and status-bar
  spacer use the current Folder background while toolbar title and icons choose
  a dark or light foreground from that background's luminance.
- Changed Activity Header subtitles to use the same direct child Folder/Thing
  count text for root and Folder projections, omitting zero-count segments.

Verification: `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. `git diff --check`
passed with only the repository's existing LF/CRLF warnings. Published debug
update `202606200422` and verified remote `latest.json` points at
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200422.apk`.
Remote SHA-256:
`cc2ab8aad94f56f07fdaf7d58a49ab2efb8171c5474a499e154c57e12b6c2f24`.

## 2026-06-20 - Apply muted Folder surface to Folder projections

- Tuned `BackgroundUtil.mutedSurfaceBackground(...)` so the muted Folder surface
  leans more toward `bg_activity_things` than the first pass while still
  retaining a small pure-colour or gradient Folder accent.
- Applied the same muted Folder surface to ThingsActivity when the current
  projection is inside a Folder. Returning to a root projection restores the
  plain `bg_activity_things` surface.
- Updated `ActivityHeader` so Folder titles use the current Folder's pure
  colour or gradient text fill, and Folder child-count subtitles omit zero
  segments such as `0 folders` or `0 things`.

Verification: `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. The remaining
compiler warning is the existing deprecated override warning in
`ThingsActivity.kt`. `git diff --check` passed with only the repository's
existing LF/CRLF warnings. Published debug update `202606200402` and verified
remote `latest.json` points at
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200402.apk`.
Remote SHA-256:
`d348ba6bc1aff772c2faeee0a56c650106b5ae8cc4be43b3550a95463ee7338b`.

## 2026-06-20 - Tint thumbnail Folder interior surfaces by Folder background

- Updated thumbnail-mode Folder Cards so their opaque interior fill is no
  longer the exact `bg_activity_things` colour. The fill now uses
  `BackgroundUtil.mutedSurfaceBackground(...)`, which keeps the surface close
  to the current light/dark list background while blending in a small amount of
  the Folder's pure colour or gradient.
- Applied the same derived surface to `DragOverlayImageView` before drawing the
  captured bitmap, so transparent-looking areas in large Folder drag overlays
  match the list card and still cover the inner native elevation shadow.
- Kept the native `CardView` / View elevation path and did not reintroduce the
  outside-only shadow decoration or per-frame clipping path.

Verification: `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. The remaining
compiler warnings are the existing deprecated `adapterPosition` warnings in
`ThingListOverlayDragController.kt`. `git diff --check` passed with only the
repository's existing LF/CRLF warnings. Published debug update `202606200345`
and verified remote `latest.json` points at
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200345.apk`.
Remote SHA-256:
`abdf38d71fc930223b37fc0b29e4a814dc5be23202951c354cd19bedf7848996`.

## 2026-06-20 - Revert thumbnail Folder true-transparent shadow path

- Follow-up testing showed that the true-transparent thumbnail Folder shadow
  path was too slow on device. The expensive part was the outside-only
  `MaterialShapeDrawable` compat shadow drawn through RecyclerView decoration
  and clipped on every frame, especially during scroll and overlay drag.
- Kept the `ThingListOverlayDragController.kt` move from `activities` to
  `managers`, but removed `ThumbnailFolderCardShadowDecoration` and the shared
  `OutsideOnlyRoundedShadow` helper.
- Restored thumbnail-mode Folder Cards to the native `CardView` elevation path.
  Their card surface again fills the otherwise empty interior with
  `bg_activity_things`, sets normal/dragging `cardElevation` like ordinary
  cards, and lets touch and Moving-mode elevation animations run normally.
- Restored thumbnail Folder overlays to native View elevation with expanded
  bounds and an inset rounded `Outline`. `DragOverlayImageView` again draws the
  list background inside the content rect before drawing the captured bitmap,
  which covers the inner native shadow without running the outside-only shadow
  path.

Verification: `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. `git diff
--check` passed with only the repository's existing LF/CRLF warnings. Published
debug update `202606200334` and verified remote `latest.json` points at
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200334.apk`.
Remote SHA-256:
`f4055cab9ef9fa86e8def43a93978d6e5809556f38f9888aee8de4e4beb11e28`.

## 2026-06-20 - Restore true transparency for large Folder Card surfaces

- Follow-up testing after the Folder elevation pass showed that thumbnail-mode
  Folder Cards still did not have real transparent interior space. The list
  card and drag overlay both used `bg_activity_things` as a fill to hide the
  inner half of the platform elevation shadow, so the empty region only matched
  the list background instead of preserving alpha.
- Moved `ThingListOverlayDragController.kt` from the `activities` package to
  the `managers` package and updated `ThingsActivity` to import it explicitly.
- Replaced the opaque-fill thumbnail Folder surface strategy. Thumbnail-mode
  Folder Cards now keep a transparent rounded `CardView` background and set
  native `cardElevation` / `maxCardElevation` to `0f` for normal, touch, moving,
  selecting, and mode-exit paths.
- Added `ThumbnailFolderCardShadowDecoration` plus the shared
  `OutsideOnlyRoundedShadow` helper. The decoration draws a
  `MaterialShapeDrawable` compat elevation shadow under transparent thumbnail
  Folder Cards and clips it with an even-odd outside-only rounded path, so the
  visual shadow stays outside the Folder outline and does not paint the
  transparent interior.
- Updated `DragOverlayImageView` so thumbnail Folder overlays no longer use
  native View elevation or draw the list background inside the content rect.
  They render the same outside-only rounded shadow in the expanded overlay
  bounds, then clip and draw the captured transparent bitmap content inside the
  rounded card rect. Ordinary Thing and summary Folder overlays keep the native
  elevation path.

Verification: `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. The remaining
compiler warnings are the existing deprecated `adapterPosition` warnings in
`ThingListOverlayDragController.kt` plus the existing deprecated override
warning in `ThingsActivity.kt`. Published debug update `202606200322` and
verified remote `latest.json` points at
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200322.apk`.
Remote SHA-256:
`3fb04b67aa40780c09751d1d5d7e03d2e0f87a8ce07d6409fb781b492fb5d210`.

## 2026-06-20 - Fix Activity header boundary flicker

- Follow-up testing showed that both root Underway and in-Folder projections
  could flicker when the first visible card approached the actionbar. The title
  and actionbar shadow could briefly disappear, and the in-Folder count subtitle
  could reappear underneath list cards.
- Root cause: `ActivityHeader.updateAll(...)` still treated the old 102dp
  spacer as the maximum valid scroll distance and reset larger values to `0`.
  At the exact spacer boundary, RecyclerView can still report the invisible
  header item as visible while its top is already beyond that legacy threshold,
  so the header jumped from collapsed back to expanded for a frame.
- Fixed the state calculation by clamping scroll distance to the current header
  spacer height instead of resetting to expanded state. The subtitle alpha and
  actionbar shadow now stay continuous through the boundary.
- Updated `ThingsActivity` staggered-grid callers to pass the minimum visible
  adapter position across all spans to `ActivityHeader`, rather than only
  `positions[0]`, so the first-visible decision is stable at span boundaries.
- Added a compact collapsed scale for Folder names that require two actionbar
  lines. The scale animates continuously with the same header-collapse progress
  and the vertical centering calculation uses the two-line collapsed visual
  height immediately.

Verification: source search confirmed the legacy `scrollY >= 102dp -> 0`
reset and `positions[0]` header update paths are gone. `git diff --check`
passed with only the repository's existing LF/CRLF warnings.
`.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
completed with `BUILD SUCCESSFUL`. Published debug update `202606200248` and
verified remote `latest.json` points at that code. Remote APK:
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200248.apk`.
Remote SHA-256:
`8c967fa38d199131fec7b5129e0248c0101cc2c0039abc1c0cedb5ac7d4ebcaf`.

## 2026-06-20 - Fix in-Folder header spacer crash during scroll

- Diagnosed a production crash log from OnePlus PLZ110 / Android 16:
  `RecyclerView` threw `IllegalArgumentException: Called attach on a child
  which is not detached` while `StaggeredGridLayoutManager.fill(...)` was
  handling a touch-driven scroll in `rv_things`.
- Root cause: the previous long-Folder-title spacer fix let `ActivityHeader`
  emit spacer height changes from header layout changes. During scrolling,
  changing the title width/line cap could remeasure the header and trigger
  `notifyItemChanged(0)` for the invisible RecyclerView spacer while the layout
  manager was still attaching children.
- Removed the scroll/layout-change path from spacer updates. The spacer is now
  refreshed only from explicit expanded-header refresh points such as
  `updateText()` and `reset(...)`.
- Added an Activity-side guard that keeps only the latest spacer height request
  and applies it to the adapter only after the RecyclerView is idle and not
  computing layout.
- Improved collapsed Folder-title centering by recalculating the collapsed
  header translation from the current visible title layout, including the
  two-line collapsed height cap.

Verification: the crash log was mapped to the RecyclerView attach path and the
source search confirmed `updateHeaderSpacerHeight()` is no longer reachable
from the scroll update path. `git diff --check` passed with only the
repository's existing LF/CRLF warnings. `.\gradlew.bat :app:assembleDebug
--console=plain --no-configuration-cache` completed with `BUILD SUCCESSFUL`.
No automated UI regression seam exists for the user's exact device/data state
in this workspace; only a physical device was connected, so no emulator smoke
test was run. Published debug update `202606200220` and verified remote
`latest.json` points at that code. Remote APK:
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200220.apk`.
Remote SHA-256:
`47d0066c617659570aa5a86e1dcaa0951c20240098850a71d575b713d2dacc9f`.

## 2026-06-20 - Folder header and disabled move-target visual state follow-up

- Follow-up testing showed that forbidden source Folder subtrees in the
  move-to-Folder dialog were logically disabled but still looked active. Dialog
  binding now leaves disabled rows expandable while applying the App Chrome
  disabled foreground to their Folder icon and title text.
- Changed the in-Folder Activity header from a clickable full path to the
  current Folder name only. It now keeps the same plain style as the root
  Underway header and the subtitle reports direct child counts split as
  folders and things.
- Added dynamic in-Folder header width and line constraints for long Folder
  names. Expanded headers stay inset before the card edge, collapsed headers
  stay before toolbar actions, and the invisible RecyclerView header spacer is
  refreshed from the measured header height so wrapped names do not overlap the
  first visible card.

Verification: `git diff --check` passed with only the repository's existing
LF/CRLF warnings. `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Published debug
update `202606200208` and verified remote `latest.json` points at that code.
Remote APK:
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200208.apk`.
Remote SHA-256:
`ab98531bd6fb30a47c054d67916d73fafccac2d4f604729ec9f9c3e9f689f503`.

## 2026-06-20 - Show forbidden Folder move targets and reset private expansion auth

- Reversed the previous move-dialog hiding rule after follow-up testing: when
  moving a Folder, the source Folder and every descendant now remain visible in
  the target tree. They can be expanded to inspect the hierarchy, but are
  disabled and cannot be selected as move targets.
- Stabilized the move-to-Folder dialog layout by reserving divider and action
  row spacing instead of changing margins and `GONE` divider visibility when
  expansion makes the tree scrollable.
- Drawer private Folders with child Folders now keep their trailing expand
  affordance visible while the subtree is hidden. Expanding requires
  authentication unless the current projection is already inside an
  authenticated private Folder path.
- Drawer and move-dialog private expansion authentication is now transient to
  the active surface. Closing the Drawer or dismissing the dialog resets local
  expansion authorization; Drawer close also collapses private subtrees outside
  the current private path.

Verification: `git diff --check` passed with only the repository's existing
LF/CRLF warnings. `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Published debug
update `202606191645` and verified remote `latest.json` points at that code.

## 2026-06-20 - Hide forbidden Folder move targets and add scroll dividers

- Follow-up testing showed that the move-to-Folder dialog still rendered the
  source Folder as a disabled row in some Folder trees. The dialog now omits
  forbidden Folder ids from the tree entirely, which removes the source Folder
  and its forbidden subtree from possible targets rather than presenting them
  as disabled rows.
- Folder rows now compute expand affordance visibility from visible,
  non-forbidden children, so the root row or another parent does not show an
  expand button when all of its children were excluded by the move guard.
- Added top and bottom dividers to the move-to-Folder dialog's RecyclerView
  region. They appear only when the target tree is scrollable and follow the
  existing App Chrome dialog boundary rule: the divider at the current scroll
  edge is hidden while the opposite edge remains visible.

Verification: `git diff --check` passed with only the repository's existing
LF/CRLF warnings. `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Published debug
update `202606191616` and verified remote `latest.json` points at that code.
Remote APK:
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606191616.apk`.
Remote SHA-256:
`6bd93aa54271df75f57f2c9fc58f0b97381bd9acec24798b365565e7bfd9edf2`.

## 2026-06-19 - Restore Folder move entry points

- Follow-up testing showed that Folder moves were still not reachable from the
  two expected entry points even though the move DialogFragment and manager
  operation existed.
- Fixed the selected-card contextual toolbar visibility rule so `Move to
  Folder` remains visible for a single selected non-deleted Folder, while
  still hiding it for mixed or multi-Folder selections.
- Refreshed the Activity options menu immediately after opening a Folder from a
  Folder Card. The current-Folder overflow menu therefore picks up the new
  in-Folder state and shows the current Folder's move action without requiring
  an app restart or navigation refresh.
- Adjusted Folder move privacy checks so moving a private Folder that is
  already open does not ask for authentication again for the source Folder.
  Moving into another private target still authenticates unless that target
  path has already been authenticated.

Verification: `git diff --check` passed with only the repository's existing
LF/CRLF warnings. `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Published debug
update `202606191559` and verified remote `latest.json` points at that code.
Remote APK:
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606191559.apk`.
Remote SHA-256:
`51009f16b4d4112e13b03d9ccd3d53c1ec03740d22879df3a31f55d843d8ecd8`.

## 2026-06-19 - Keep overlay drag enlarged while source holder stays normal

- Corrected the overlay drag visual model after the user pointed out that
  `View.draw(Canvas)` captures normal view content rather than the View's
  transformed Moving-mode scale. The overlay now applies the drag lift scale
  (`1.11f`) to the bitmap `ImageView` itself, so the moving card remains
  enlarged during drag.
- Adjusted the overlay start frame and finger offset to use the enlarged
  visible overlay bounds. Folder-drop top-left hit testing therefore continues
  to use the visible dragged card's top-left corner, not the unscaled bitmap
  frame.
- Once the bitmap has been captured, the real source holder now clears its
  moving-scale recovery token, cancels any scale animation, resets to
  `scaleX=1f` / `scaleY=1f`, and remains transparent as a normal-size layout
  placeholder. This prevents a second ViewHolder shrink animation from
  appearing when the holder is finally revealed.
- Reorder settle target resolution now waits for RecyclerView item animations
  and a post-animation draw before resolving the final source holder frame.
  This avoids computing the overlay's final target from a stale transparent
  source placeholder that RecyclerView still has at the old list position
  before relayout.
- Tightened successful reorder reveal ordering so the final source ViewHolder
  is restored only after the overlay movement animation has ended and the
  overlay view has been removed from the overlay parent. This avoids showing
  the real card under the final overlay frame.
- After follow-up device feedback that the ViewHolder could still appear early,
  identified the remaining gap as adapter rebinds during `notifyItemMoved(...)`
  or related updates resetting `holder.itemView.alpha` to `1f`. Added a
  RecyclerView pre-draw guard for the active overlay session so the source
  holder is re-applied as a transparent normal-size placeholder immediately
  before each frame is drawn, until the overlay is removed and the deliberate
  reveal runs.
- After the user still observed early ViewHolder reveal and suspected
  `notifyDataSetChanged()`-style rebinding, hardened the guard against
  RecyclerView pre-layout. `ThingsAdapter` now tags each bound card root with
  its Thing/Folder stable business id, and the controller scans visible
  children by that tag instead of relying only on the source's current list
  position. Placeholder application now also resets the inner `cv_thing`
  CardView scale and recovery token, matching the layer that Moving mode
  actually enlarges.
- Confirmed that Moving mode already raises selected normal Thing/Folder Cards
  to `thing_card_dragging_elevation` while scaling them to `1.11f`. The overlay
  snapshot view now uses that same 12dp elevation and the card corner outline,
  so the moving overlay renders as the enlarged elevated card rather than a
  plain rectangular bitmap surface.
- Strengthened successful reorder settle timing after the user reproduced the
  old-slot placeholder problem. The overlay no longer starts its final movement
  as soon as a holder can be found. It waits until RecyclerView has no pending
  adapter updates, is not computing layout, item animations are no longer
  running, the source holder at the final adapter position is bound to the
  source stable id, and its layout rect is stable across consecutive frames.
- Reviewed the user's follow-up video and identified a more specific cause of
  the remaining old-slot behavior: the pre-draw placeholder guard was calling
  `itemView.animate().cancel()` every frame. That cancelled RecyclerView's own
  root ViewPropertyAnimator for moving the transparent source holder, so the
  holder could become stable at the old slot. Placeholder enforcement now keeps
  the root item animation intact and only cancels the inner card view's
  app-owned Moving-mode scale recovery animation.
- The user then rejected the full-refresh workaround because it removed
  RecyclerView's own re-layout animation and could disturb scroll position.
  Restored `notifyItemMoved(...)` for overlay reorder commits, then made the
  overlay wait more patiently before settling: it must allow at least the item
  move duration plus a small grace window for the item animator to start, wait
  for any observed item animator to stop, wait an additional short
  post-animation grace window, require idle scrolling, require no pending
  adapter updates, and require the final source rect to be stable across
  multiple frames.
- After the user reported the same screenshot sequence still reproducing,
  reviewed AndroidX `StaggeredGridLayoutManager` and `RecyclerView` source.
  The important finding is that SGLM keeps a lazy span lookup, `notifyItemMoved`
  only invalidates the affected range, and later gap correction or full-list
  rebinds can clear/rebuild span assignments after a holder rect has already
  looked stable. The first follow-up attempted a two-phase wait, but that made
  the overlay feel late. The revised approach now requests simple animations
  for the next layout and invalidates StaggeredGrid span assignments
  immediately after the successful `notifyItemMoved(...)` commit, before
  RecyclerView consumes the adapter update. The overlay waits only until that
  synchronized final layout rect is available, then starts on the next
  animation frame using RecyclerView's move duration so it runs with the final
  arrangement animation instead of after it.
- Updated successful drops into existing Thing Folders so the session overlay
  no longer flies into the target Folder Card. For `MOVE_TO_FOLDER` commits,
  the overlay now keeps its release position, pivots at its own top-left
  corner, and shrinks directly to `scaleX/scaleY=0`. Create-Folder drops still
  keep their merge-into-target animation.
- Diagnosed the missing remaining-card rearrangement animation on Folder drops.
  The commit path was already using `notifyItemRemoved(...)` plus a targeted
  Folder-card `notifyItemChanged(...)`; the animation was vulnerable because
  the later Moving-mode-exit full-list rebind could run before RecyclerView had
  consumed pending adapter updates, completed layout, or finished
  `DefaultItemAnimator` move animations. `runWhenThingListCanUpdate(...)` now
  checks pending adapter updates and requested layout, and Folder-drop
  mode-exit rebind waits for `ItemAnimatorFinishedListener` before calling
  `notifyDataSetChanged()`.
- Investigated the follow-up case where dropping A above bottom-left card B
  made B animate downward first, then jump to A's right side after the overlay
  finished. AndroidX source review showed that SGLM supports predictive item
  animations by default, while `checkForGaps()` can later clear lazy spans and
  request another simple-animation layout. With a non-top visible anchor near
  the bottom of the list, that means the first `notifyItemMoved(...)` animation
  can be based on an intermediate same-span post rect, and the final span
  correction arrives later.
- Added a one-shot overlay-reorder preparation path to
  `ThingsStaggeredLayoutManager`: the next layout after an overlay reorder
  suppresses predictive item animations, requests simple animations, and
  invalidates span assignments. This keeps RecyclerView move animations for the
  surrounding cards, but makes their post-layout rects come from the final
  non-predictive SGLM span assignment. Overlay reorder's delayed mode-exit
  full-list rebind now also waits for `ItemAnimatorFinishedListener`.
- Matched Folder Card long-press behavior to Thing Cards while selecting mode
  is active. The Folder branch now exits selecting mode through
  `ModeManager.backNormalMode(listPosition)` instead of swallowing the long
  press.
- Reworked thumbnail-mode Folder drag overlays again after source review of
  Android shadow handling. The custom stroke/gradient shadow approximations
  were removed; `DragOverlayImageView` now uses platform elevation again, with
  an expanded view bounds and an inset rounded `Outline` matching the real
  card content rect.
- Covered the transparent-interior shadow without self-drawing the outer
  shadow: before drawing the captured transparent bitmap, the overlay draws a
  rounded `bg_activity_things` fill inside the content rect. The platform still
  draws the real outer elevation shadow, while the interior shadow is hidden by
  the same background the thumbnail Folder Card normally reveals.
- Confirmed the list's thumbnail-mode Folder Cards had also been suppressing
  elevation by setting `cardElevation` and `maxCardElevation` to `0f`. Restored
  their normal and dragging elevation to match ordinary Thing Cards, including
  Moving-mode and touch elevation animations. Their outer CardView now uses
  `bg_activity_things` as an opaque rounded fill under the outlined content, so
  the platform shadow is still hidden inside the outline while remaining
  visible outside it.
- Kept the very tall Folder overlay fix from the previous pass. If the
  captured bitmap is larger than the safe tile size, `DragOverlayImageView`
  splits it into 1024px tiles and draws those tiles into the content rect
  instead of asking ImageView to upload one oversized texture.

Verification: `git diff --check` passed with only the repository's existing
LF/CRLF warnings. `.\gradlew.bat :app:compileDebugKotlin --console=plain
--no-configuration-cache` and `.\gradlew.bat :app:assembleDebug
--console=plain --no-configuration-cache` completed with `BUILD SUCCESSFUL`;
the only compiler warnings were the existing deprecated `adapterPosition`
warnings in `ThingListOverlayDragController.kt`. Published debug update
`202606191435` to the Aliyun debug channel and verified remote `latest.json`
points at that code. Remote APK:
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606191435.apk`.
Remote SHA-256:
`f0048764ef1105d325e9671721342badb5366ef12d5b0744c0d7688a298a8c69`.

## 2026-06-19 - Align overlay release animation to layout-space card frames

- Diagnosed the remaining overlay reorder / release-in-place landing offset as
  a coordinate-frame mismatch. The controller was using
  `View.getLocationOnScreen()` for the target top-left while pairing it with
  raw `width`/`height`; during Moving-mode, the source holder can still carry
  transient `scaleX`/`scaleY`, so that produced a transformed top-left with
  untransformed right/bottom.
- Updated `ThingListOverlayDragController` so overlay start, folder-drop target
  centers, reorder settle targets, and release-in-place return targets use a
  shared layout-space rect helper. The helper walks view `left`/`top` up to the
  overlay root, ignores scale, and lets callers choose whether transient
  translations should be included.
- Reorder and release-in-place now target the final holder's untransformed
  layout frame, preserving top-left to top-left and bottom-right to
  bottom-right alignment when the real ViewHolder is revealed.

Verification: `git diff --check` passed with only the repository's existing
LF/CRLF warnings. `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Publishing was
attempted in-sandbox but timed out at `:app:publishDebugUpdate`; the elevated
rerun was rejected by the safety reviewer because this turn did not explicitly
authorize remote APK upload and metadata updates, so publishing was not
confirmed.

## 2026-06-19 - Separate reorder landing target from RecyclerView relayout timing

- Reviewed the user's slow-motion reorder video from the 3-second mark and
  separated two effects: the overlay landing target could be offset, while the
  RecyclerView rearrangement/full-list rebind could also reveal the real moved
  card during the deliberately prolonged overlay test animation.
- Removed the temporary prolonged reorder settle duration and returned the
  successful reorder overlay flight to the RecyclerView item move duration.
- Changed the successful reorder and release-in-place target calculation to use
  the final source holder `itemView` layout rectangle as the outer bitmap-frame
  target. The controller no longer divides the overlay frame by the captured
  long-press card scale, because that scale is already baked into the lift-time
  bitmap contents and should not shift the frame's final `x`/`y`.
- Changed successful reorder cleanup so source placeholder reveal waits until
  both the overlay flight and RecyclerView item animations have finished. This
  removes the earlier timing experiment where the real holder could be restored
  while the overlay was still visibly moving.
- Deferred the Moving-mode exit full-list rebind while an overlay drag session
  is still active. If the delayed rebind fires before the overlay has ended, it
  is recorded as pending and runs only after `onOverlayDragActiveChanged(false)`.

Verification: `git diff --check` passed with only the repository's existing
LF/CRLF warnings. The in-sandbox
`.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
run was blocked by the usual Kotlin daemon `AccessDeniedException` under the
user profile; the same local assemble task succeeded with elevated
permissions. Debug update publishing remains owned by the user.

## 2026-06-19 - Polish reorder release jitter and insertion line placement

- Revisited the overlay reorder release animation after device feedback that
  the card could jitter at the final reveal. The likely cause was a mismatch
  between the enlarged Moving-mode snapshot and the real holder being restored
  at normal scale, plus the possibility that RecyclerView's item move animator
  had not fully finished when the holder was revealed.
- Stored the card scale captured at overlay start and made the successful
  reorder settle animation shrink the overlay back toward the normal card scale
  while moving to the final source layout slot.
- Delayed source-holder reveal until RecyclerView's item animator reports that
  its animations have finished, keeping the overlay visible at the final target
  if RecyclerView needs an extra frame to finish its own translation cleanup.
- Changed the reorder insertion line from a geometric midpoint between visible
  neighbours to a small fixed offset from the target card edge, clamped only
  when the local visible gap is too small. Large masonry gaps from tall Things
  or Folders no longer pull the line far away from the final target card.
- Follow-up feedback clarified that returning to the original position should
  still play a spatial return animation. The no-op reorder / release-in-place
  path now animates the overlay back to the source layout slot before entering
  selecting mode instead of fading the overlay out.
- Tightened successful reorder reveal timing after the user pointed out that
  restoring the real holder alpha too early could explain the final jitter.
  The controller now waits for RecyclerView item animations and one additional
  draw, then restores only the holder resolved by the source stable id, avoiding
  the stale drag-start `sourceView` reference on successful moves.
- Increased the insertion line thickness from 6px to 12px so the source-colour
  line reads clearly during drag.
- Follow-up feedback clarified that the insertion line thickness should be
  density-independent rather than raw pixels. The line is now 4dp, which maps
  to the previous intended thicker visual weight on high-density devices while
  staying consistent across densities.
- Added a temporary timing experiment for the remaining final-frame jitter:
  successful reorder overlay flight now lasts 420ms, while source-holder alpha
  restoration still follows the current RecyclerView item-animation completion
  timing. This intentionally separates the overlay movement from the holder
  reveal so device testing can identify which moment causes the jitter.

Verification: in-sandbox `:app:assembleDebug` was blocked by the same Kotlin
daemon `AccessDeniedException` under the user profile. The same
`.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
task succeeded with elevated permissions. Debug update publishing was left to
the user.

## 2026-06-18 - Synchronize overlay reorder settle with RecyclerView move

- Reworked the successful overlay reorder release path after device feedback
  that removing the overlay before `notifyItemMoved(...)` avoided flicker but
  made the dragged card landing animation feel disconnected.
- Kept the session overlay visible after release, committed the data move and
  `notifyItemMoved(...)`, then waited for the next RecyclerView pre-draw before
  resolving the moved source holder's final layout rectangle.
- Animated the overlay from the release position to that final source layout
  slot using the RecyclerView item move duration, so the surrounding cards'
  gap-making animation and the dragged card's settle animation share timing.
- Kept the real source holder transparent during the RecyclerView move
  animation, then restored it after the overlay settle and reset transient
  drag scale/tags before reveal.

Verification: in-sandbox `:app:assembleDebug` was blocked by a Kotlin daemon
`AccessDeniedException` under the user profile. The same
`.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
task succeeded with elevated permissions. Debug update publishing was left to
the user.

## 2026-06-18 - Hide IME when closing Folder Card appearance panel

- Rechecked the user's intended UI and corrected the target: the problematic
  input is `et_folder_card_appearance_name` inside
  `panel_thing_card_appearance.xml`, not the Folder naming `DialogFragment`.
- Confirmed the Folder Card appearance UI is an in-Activity bottom panel
  included in `activity_things.xml`. The name input is a standard XML
  `<EditText>` inflated into `android.widget.EditText`; it is not a custom
  project input widget.
- Added IME hiding to `hideThingCardAppearancePanel()` before the panel is set
  to `GONE`, using the existing AndroidX Compat `KeyboardUtil.hideKeyboard(...)`
  path with the Activity window and current focus, falling back to the Folder
  name input.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. The only compiler
warning was the existing deprecated override warning in `ThingsActivity.kt`.
Debug update publishing was left to the user.

## 2026-06-18 - Use WindowInsets for Folder naming dialog IME control

- Reverted the previous delayed-dismiss Folder naming dialog keyboard attempt:
  removed the planned close state, delayed close runnable, repeated
  `InputMethodManager` hide calls, transient root focus handling, and related
  preference notes.
- Researched the current Android guidance and switched the project keyboard
  helper away from `InputMethodManager` for show/hide. `KeyboardUtil` now uses
  `WindowCompat.getInsetsController(window, view).show/hide(WindowInsetsCompat.Type.ime())`
  directly, resolving the `Activity` window from a plain `View` when callers do
  not pass a `Window`.
- Updated `ThingFolderNameDialogFragment` so the dialog no longer uses
  `SOFT_INPUT_STATE_ALWAYS_VISIBLE`. It shows the IME from the dialog window
  after start, and hides the IME from the same dialog window before cancel,
  confirm, and `onDismiss(...)` paths continue closing the dialog.
- Compared this with `DateTimeDialogFragment`: the reminder dialog was stable
  because it moves focus to a still-attached non-input view before hiding the
  IME, but the Folder naming dialog is a single-input dialog that benefits more
  from window-level IME control than from focus juggling or delayed dismissal.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. The only compiler
warning was the existing deprecated override warning in `ThingsActivity.kt`.
Debug update publishing was left to the user.

## 2026-06-18 - Delay Folder naming dialog close until after IME hide

- Reviewed the user's hypothesis about the Folder naming dialog keeping the
  keyboard visible. The analysis matched the important distinction from
  `DateTimeDialogFragment`: the reminder dialog hides the IME before dismissal
  from stable non-input views during normal interaction, while the Folder naming
  dialog was hiding from the `EditText` and then dismissing the dialog in the
  same frame.
- Removed the transient focus workaround from `fragment_thing_folder_name.xml`.
  The dialog root is no longer made focusable just to receive focus during
  close, avoiding rapid `EditText -> root -> no focus` changes in the same
  frame.
- Changed the Folder naming dialog's confirm and cancel buttons to enter a
  planned close state, request IME hide through the input/current focus and
  dialog window, then run the confirm/cancel callback and dismiss after
  `KeyboardUtil.HIDE_DELAY`. `onDismiss(...)` now only delivers the cancel
  callback on unplanned dismissals such as back/outside cancellation.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Debug update
publishing was left to the user.

## 2026-06-18 - Fix Folder naming IME hide and post-drop dimmed cards

- Rechecked the Folder naming dialog against `DateTimeDialogFragment`, which
  moves focus out of the active input before hiding the keyboard and also hides
  from dismissal paths. The previous Folder naming fix only hid through the
  `EditText` or Activity current focus, while `KeyboardUtil.hideKeyboard(window)`
  merely set `SOFT_INPUT_STATE_ALWAYS_HIDDEN` without calling
  `InputMethodManager.hideSoftInputFromWindow(...)` on the dialog window token.
- Updated `KeyboardUtil.hideKeyboard(window)` to hide the IME through the
  dialog decor/focused view window token and clear focus. The Folder naming
  dialog root is now focusable, and `hideNameKeyboard(...)` hides through the
  input/current focus, requests focus on the content root, hides through the
  content view, and finally hides through the dialog window.
- Diagnosed the dimmed-card regression after dropping a Thing into a Folder:
  the successful Folder-drop path intentionally exited Moving mode with
  `finishMovingModeWithoutListRefresh()` to preserve targeted removal and merge
  animations, but visible cards that had already been rebound in Moving mode
  stayed in their unselected dimmed colours because no later normal-mode rebind
  occurred.
- Added a post-drop mode-exit rebind after the Folder-drop merge animation (or
  immediately for no-visual/failed paths). The rebind disables list-appearing
  animation and only refreshes card binding once RecyclerView is safe, restoring
  all visible cards to normal colours without disrupting the targeted drop
  animation.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Debug update
publishing was left to the user.

## 2026-06-18 - Correct private Folder drop, lock spacing, and IME follow-up

- Rechecked the hidden-private Folder Card lock spacing after the user rejected
  the earlier 44dp-only explanation. The actual remaining problem was that
  `resetFolderCardHolder(...)` made the ordinary content TextView visible for
  Folder Cards, and the hidden-private Folder path did not hide it again before
  showing the lock. That recycled content/status state could still participate
  in the private Folder Card measurement below the lock.
- Updated hidden-private Folder binding to hide the ordinary content/status
  surfaces, reset Folder bottom spacing to the same 16dp spacer used by
  hidden-private Thing Cards, and explicitly reset the private lock ImageView to
  the normal 48dp size so recycled full-span private Thing holders cannot leave
  a larger lock.
- Confirmed the reference values: `card_thing.xml` declares the private lock
  ImageView as 48dp with a 16dp top margin and the bottom padding spacer as
  16dp. `BaseThingsAdapter.applyCardContentGeometry(...)` resets ordinary
  hidden-private Thing Cards to those values; the mdpi lock bitmap is 48x48 and
  has 4px transparent space below the visible glyph.
- Allowed Thing Cards and Folder Cards to be dropped onto private Folder Cards.
  The drop eligibility no longer treats the target Folder's effective privacy
  as a reason to block moving into that Folder; privacy authentication still
  applies when opening or viewing the Folder's contents.
- Made the Drawer private Folder lock smaller again while keeping it centered
  inside the Folder glyph.
- Fixed the shared `KeyboardUtil.hideKeyboard(view)` path used by the Folder
  naming dialog so it hides the IME with the current window token before
  clearing focus, instead of clearing focus first and then sometimes deciding
  the input method is no longer active.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Debug update
publishing was left to the user.

## 2026-06-18 - Follow up Folder drop, private lock, and naming IME polish

- Extended existing Folder-drop activation feedback from thumbnail-mode Folder
  Cards to summary-mode Folder Cards. Summary Folder targets now animate both
  their card background and content alpha back to the normal active state while
  the drag is over them, then restore the dimmed selecting/moving appearance
  when the drag leaves.
- Corrected the Drawer private Folder lock drawing. The lock is now smaller and
  centered inside the Folder glyph rather than drawn as a large lower-right
  overlay.
- Updated the Folder naming dialog so cancel, confirm, and dismiss hide the
  soft keyboard before the Activity continues with rename/create/cancel
  callbacks.
- Re-diagnosed the hidden-private Folder Card lock spacing after the user
  clarified that only the bottom space was wrong. The lock ImageView top margin
  was left alone; the Folder-card holder reset now clears recycled bottom
  status spacer height/weight and restores the ordinary 16dp bottom padding,
  preventing media-count padding from leaking into private Folder cards.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Debug update
publishing was prepared with `memory/debug-update-notes.md`.

## 2026-06-18 - Polish private folders, Drawer privacy, and drop cancel restore

- Fixed selecting/moving-mode visuals for summary-mode Folder Cards. Unselected
  summary Folder Cards now wash out their card background and content alpha in
  the same state where unselected Thing Cards are dimmed.
- Fixed thumbnail-mode Folder drop target feedback. While a dimmed thumbnail
  Folder Card is the active drop target, its content alpha animates back to the
  normal value in sync with the existing target scale/outline timing; leaving
  the target restores the dimmed value, and committing the drop keeps the target
  normal-coloured during the merge animation.
- Kept Drawer selection stable after creating a Thing from inside a Folder
  projection. The Drawer now re-checks the current projection instead of
  switching to the Underway root after create-return.
- Tightened private Folder presentation rules. Private Folders now use the
  default normal-span summary presentation as their effective presentation in
  adapters, DAO thumbnail prefetch, manager updates, and privacy toggling. The
  Folder Card appearance panel exposes only the rename field for private
  Folders, with an accent underline on the editable name field, and action
  menus no longer offer size/mode toggles for private Folders.
- Matched hidden-private Folder Card lock spacing to hidden-private Thing Card
  lock spacing.
- Updated the custom Drawer Folder row model with a private-folder flag. Private
  Folder icons now draw a small adaptive lock over the Folder glyph. Private
  Folder descendants and expand/collapse affordances are hidden unless the
  current Folder projection is inside that private Folder scope.
- Fixed canceling the name dialog after a Thing-to-Thing Folder drop. The two
  source Things now restore their pre-creation parent Folder ids and locations
  instead of being reinserted at the start of the parent list.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`.
`.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
--console=plain --no-configuration-cache` was attempted but timed out after
120 seconds at `:app:publishDebugUpdate`; an elevated rerun was not approved by
the safety reviewer, so publishing was not confirmed.

## 2026-06-18 - Harden drag clear during scroll/layout detach

- Analyzed the user's `thing_card_scale_recovery(3).log` from `11:45:07`.
  Several long drags over large Thing/Folder cards showed `clearView(...)`
  running while the Activity-level pointer was still down. This means
  `ItemTouchHelper` ended the active drag because the dragged child was
  detached during RecyclerView scroll/layout, not because the user released
  the drag.
- Analyzed `crash_20260618114415.log`. The crash was
  `IllegalStateException: Cannot call this method while RecyclerView is
  computing a layout or scrolling`, thrown by `notifyItemRemoved(...)` from
  `notifyFolderDropCommitted(...)` while `clearView(...)` was being called via
  `ItemTouchHelper.onChildViewDetachedFromWindow(...)` during RecyclerView
  layout.
- Added an interrupted-drag branch in `ThingsTouchCallback.clearView(...)`.
  If `clearView(...)` happens while the Activity still has a pointer down, the
  current folder-drop hover is canceled instead of committed, dragged-card
  scale animates back to normal when the view is still attached, and any
  in-memory reorder is moved back to the drag start position once RecyclerView
  is safe to update. Detached holders still get an immediate scale reset so a
  recycled view cannot keep a transient drag scale. The UI then enters
  selecting mode rather than pretending the drag was released.
- Added `runWhenThingListCanUpdate(...)` for RecyclerView-safe adapter updates.
  Normal folder-drop release still commits, but if RecyclerView is computing
  layout or still scrolling, the data mutation and adapter notifications are
  delayed until the list is idle.
- Extracted folder-drop commit handling into `commitFolderDropAfterClear(...)`
  so immediate and deferred commit paths share the same mutation/animation
  behavior. Deferred commits skip the merge overlay because the original child
  may already be detached.
- After the user confirmed the behavior looked acceptable on device, disabled
  the card-scale file diagnostics by setting `CARD_SCALE_RECOVERY_DEBUG` to
  `false`. The generic `DebugFileLogger` remains available for future probes,
  but this drag/scale path no longer writes `thing_card_scale_recovery.log`.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Debug update
publishing was left to the user.

## 2026-06-18 - Gate posted long-press drag on the active touch sequence

- Analyzed the user's follow-up device log after `11:32`. The previous fix did
  clear `fingerDown` on moving-mode `CANCEL`, but the delayed posted
  `ItemTouchHelper.startDrag(...)` still ran after the finger had already left
  the screen. `onSelectedChanged(...)` then marked the card as drag-active and
  restored `fingerDown=true`, so the delayed scale recovery still skipped.
- Adjusted the model: `dragActive=true` is not itself wrong, because the
  `ItemTouchHelper` drag state can remain able to move the card if the user
  touches it again. It just must not be treated as proof that the original
  finger is still down.
- Added Activity-level pointer tracking through `dispatchTouchEvent(...)`.
  `ThingsActivity` now records whether any pointer is down and increments a
  touch-sequence id on each new `ACTION_DOWN`.
- Replaced the long-press `post { startDrag(...) }` calls with
  `startLongPressDragIfTouchStillActive(...)`. The posted drag starts only if
  the same touch sequence is still active. If the finger has already left the
  screen, it skips `startDrag(...)`, clears the card's touch/drag tags, and
  converts the existing moving selection into selecting mode at the original
  list position.
- Updated real drag activation to copy the Activity-level pointer state into
  the card's finger tag instead of forcing it to true. The delayed scale
  recovery now shrinks whenever `fingerDown=false` and the card is still
  enlarged, even if `dragActive=true`.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Debug update
publishing was left to the user.

## 2026-06-18 - Distinguish canceled fast release from active drag

- Analyzed the user's device log from `11:25:02.972`. The failing sequence was:
  touch `DOWN` in normal mode, touch `CANCEL` after entering moving mode, then
  the delayed recovery check saw `fingerDown=true` and `stillEnlarged=true`, so
  it correctly skipped recovery according to the previous guard. No touch `UP`
  arrived for that card.
- The root cause was that moving-mode `ACTION_CANCEL` had been treated as
  "the finger is probably still down" to protect real drag startup. That was
  too broad: a quick release can also produce `CANCEL` without a live
  `ItemTouchHelper` drag, leaving the finger tag stuck true.
- Added a separate `tag_thing_card_drag_active` view tag. `ThingsTouchCallback`
  sets it when `ItemTouchHelper` enters `ACTION_STATE_DRAG` and clears it in
  `clearView(...)`.
- Updated the delayed scale recovery check to require both `fingerDown=false`
  and `dragActive=false` before shrinking. This preserves active dragging while
  allowing the fast-release cancel path to recover.
- Updated `ACTION_CANCEL` handling so it clears the finger tag unless the card
  is already marked as an active drag card.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`.

## 2026-06-18 - Add file diagnostics for card scale recovery

- Added targeted file diagnostics because the visual-only recovery for
  fast-release long press still did not take effect on the user's device.
- Introduced the generalized `DebugFileLogger`, which writes debug logs into
  the app-specific files directory under `debug_logs/`, appends entries on a
  background thread, rotates large log files, and can add a per-file session
  header. The card-scale probe is only its first caller: it writes to
  `thing_card_scale_recovery.log` with the unique
  `[DEBUG-card-scale-recovery]` prefix through an adapter-local wrapper.
- Instrumented the boundaries that distinguish the likely failure points:
  touch `DOWN` / `UP` / `CANCEL` / `OUTSIDE`, moving-mode enlarge scheduling,
  delayed recovery checks, stale-token or detached-view exits, actual recovery
  animation start, normal-geometry resets, Folder-card resets, and
  `ItemTouchHelper.clearView(...)`.
- Kept the instrumentation observational only. It does not change long-press
  dispatch, moving/selecting mode transitions, drag startup, or Folder-drop
  behavior.

Verification: `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Debug update
publishing was not run.

## 2026-06-18 - Recover fast-release card scale without blocking drag

- Reverted the previous pending long-press drag coordination attempt because it
  could block dragging Thing and Folder cards after long press.
- Kept the existing long-press mode and drag paths intact. Note cards still
  call `ItemTouchHelper.startDrag(...)` directly after entering moving mode,
  and Folder cards still rely on their existing moving-mode drag behavior.
- Implemented the safer visual-only recovery proposed by the user: when a
  Thing or Folder card starts its moving-mode enlarge animation, the adapter
  schedules a short delayed check after the enlarge animation should have
  completed. If the finger is no longer down on that card and the card is still
  scaled above normal size, the card plays the existing shrink-back animation.
- Tracked finger-down state through view tags instead of altering selection,
  moving mode, or drag state. `ACTION_CANCEL` during moving mode intentionally
  does not clear the finger-down tag, because a real drag can produce cancel
  events while the finger is still on screen. The tag is cleared from
  `ThingsTouchCallback.clearView(...)` when dragging finishes.
- Added dedicated view tag ids for the finger state and scheduled recovery
  token, so recycled card views can cancel stale delayed checks when their
  normal geometry is restored or a newer enlarge animation starts.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Debug update
publishing was not run.

## 2026-06-18 - Polish the custom Drawer tree controls

- Fixed the custom Drawer width calculation. `DrawerLayout` already constrains
  drawer children, so `DrawerNavigationView` no longer subtracts an additional
  right margin from the incoming measure spec. The preferred Drawer width is now
  320dp.
- Made Drawer row press feedback visible by using a bounded ripple mask on the
  row background while preserving a separate selected background colour.
- Kept built-in Drawer destination icons in their original asset colours for
  both normal and selected states instead of retinting selected icons to a
  dimmer app chrome colour.
- Added an explicit end margin to Folder title text when a trailing
  expand/collapse affordance exists. Combined with the measured trailing button
  width, this prevents the title from drawing beneath the affordance.
- Changed Folder rows to always reserve the same trailing expand/collapse slot,
  even for leaf Folders. Leaf rows hide the affordance but keep the slot width,
  so Folder title right edges align with rows that do show an affordance.
- Added an 8dp end margin to the expand/collapse slot so the affordance is not
  flush with the Drawer edge.
- Reduced the Folder expand/collapse touch target from 48dp to 40dp while
  preserving the 24dp icon visual size, making the circular ripple less
  oversized.
- Rendered built-in Drawer destination icons through
  `DisplayUtil.opaqueTintDrawable(...)` so the low-alpha PNG assets do not
  remain visually washed out after tinting. Static destination icons and Drawer
  item titles now share the dedicated `app_chrome_drawer_item_foreground`
  resource in light and dark mode. The trailing Folder expand/collapse icon
  uses the same foreground tier, while selected state uses background/bold
  weight instead of a stronger foreground colour. The current Drawer foreground
  value is `#B0000000` in light mode and `#B0FFFFFF` in dark mode.
- Added explicit group-start and group-end spacing to `DrawerNavigationView`
  rows. The Underway root plus visible Folder tree, Note/Reminder/Habit/Goal,
  Finished/Deleted, and Settings/Help/About groups now each get 8dp breathing
  room above the first row and below the last row.
- Added bottom inset handling to `DrawerNavigationView`; the final Drawer row
  now adds the current bottom system-bar/display-cutout inset to its bottom
  spacer so the last item clears the navigation area.
- Changed Folder Card recursive count text from the secondary text tier to the
  tertiary hint tier used by ordinary Thing Card audio and hidden media count
  labels, preserving light/dark foreground adaptation.
- Passed the toggled Folder id from `ThingsActivity` to `DrawerNavigationView`
  so the trailing icon can animate in the correct direction: clockwise on
  expand and counter-clockwise on collapse.
- Added a custom Drawer tree item animator so inserted Folder rows fade/slide
  downward from above and removed rows fade/slide upward during collapse.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain`.

## 2026-06-18 - Replace the home Drawer NavigationView

- Added `DrawerNavigationView`, an app-owned vertical Drawer container that
  keeps the existing `drawer_header` and renders Drawer rows through a
  RecyclerView adapter with typed `Destination` and `Folder` keys.
- Replaced the home `NavigationView` in `activity_things.xml` with
  `DrawerNavigationView`. The app still uses the existing `DrawerLayout`,
  `DrawerHeader`, and toolbar drawer toggle.
- Moved Drawer Folder row rendering out of Android menu/action-view APIs.
  Folder rows now have explicit 48dp height, 16dp hierarchy indentation from
  the Folder icon, a fixed 48dp trailing expand/collapse target only when child
  Folders exist, and single-line ellipsized titles that cannot draw beneath the
  trailing affordance.
- The custom Drawer adapter uses stable row keys and `DiffUtil`, so expanding
  or collapsing a Folder subtree animates row insertions/removals without
  relying on Material `NavigationView` presenter reuse.
- Updated `ThingsActivity` so Drawer state is tracked as
  `DrawerNavigationView.ItemKey` rather than `MenuItem`, while preserving the
  existing static destinations, Folder privacy authentication, and
  `openFolderPath(...)` navigation behavior.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain`.

## 2026-06-18 - Stabilize Drawer Folder expansion and indentation

- Increased Drawer Folder indentation to 16dp. The custom Folder icon drawable
  now treats indentation as extra leading width before a fixed 24dp icon, so
  the icon itself is not scaled down and the Folder title typography remains
  unchanged.
- Revised Drawer Folder indentation so the first Folder level's title aligns
  with the Underway root title, while the visible hierarchy offset starts at
  the Folder icon. Deeper Folder levels preserve the same icon-to-title gap.
- Constrained Drawer Folder titles before the trailing expand/collapse action
  area by giving expandable rows a fixed 48dp action view and keeping Drawer
  menu item text single-line with ellipsis.
- Added the existing app chrome circular ripple treatment to the trailing
  expand/collapse action view.
- Added a short transition/fade-slide animation when expanding or collapsing a
  Drawer Folder subtree so newly inserted rows do not simply flash into place.
- Fixed stale and cross-wired Drawer dropdown action views by assigning each
  Folder a stable dynamic `MenuItem` id based on its Folder id lifetime in the
  Activity, instead of deriving ids from the current visible row index.
- Leaf Folder rows now bind an explicit zero-size empty action view instead of
  `null`, preventing `NavigationView` from retaining a recycled dropdown icon
  on Folders that do not have child Folders.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain`.

## 2026-06-17 - Add the Underway Folder tree to the Drawer

- Superseded the earlier "Folders stay out of the Drawer" decision. The Drawer
  now shows the non-deleted Thing Folder tree under Underway and above Note,
  with the Note group separated from the Underway root and Folder tree.
- Split the static Drawer menu so Underway and dynamic Folder rows share one
  group, while Note/Reminder/Habit/Goal start in a separate group with the
  requested separator above Note.
- Added dynamic Drawer Folder rows in `ThingsActivity`. The Underway root is
  always expanded, so first-level Folders are always visible; deeper levels are
  shown only when their parent Folder's trailing dropdown action view is
  expanded.
- Corrected Drawer ordering by assigning explicit menu order values: dynamic
  Folder rows sit directly below Underway, while Note and later built-in items
  keep their separator below the Folder tree.
- Adjusted Folder indentation so even first-level Folders have a visible indent
  under the Underway root, and leaf Folders do not show a trailing dropdown.
- Increased Drawer Folder indentation to 16dp and changed the custom Folder
  icon drawable so indentation adds leading width before a fixed-size icon
  rather than shrinking the icon.
- Fixed stale/wrong dropdown actions by assigning a stable Drawer `MenuItem` id
  per Folder id instead of deriving item ids from visible row indexes. Leaf
  Folder rows now explicitly clear their action view so a recycled dropdown
  cannot remain attached.
- Drawer Folder rows open the Folder in the Underway projection through a full
  Folder path, so header path navigation still works for nested Folders.
  Effective private Folders reuse the existing private authentication flow.
- Kept Drawer selection single-item: built-in destinations, Underway root, or
  the current visible Folder row. If the current Folder is inside a collapsed
  subtree, the nearest visible ancestor is checked.
- Added a custom Drawer Folder icon drawable that renders the Folder shape with
  the Folder's own pure colour or gradient background, with modest hierarchy
  indentation beginning at the icon.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain`.

## 2026-06-17 - Move entries to target top and keep private Folder titles visible

- Updated Thing/Folder move semantics so moving a Thing into another Folder,
  moving selected Things to a Folder/root, moving a Folder to another
  Folder/root, and canceling a just-created Folder all assign the moved entry a
  fresh first-position location in the target container instead of preserving
  its old source-container order. Sticky entries keep sticky state and move to
  the first position within the target sticky section; non-sticky entries move
  to the first non-sticky position.
- Added mixed direct-child location queries in `ThingFolderDAO`, covering both
  direct child Things and direct child Folders. `ThingManager` now uses those
  queries when writing Thing `folderId + location` and Folder
  `parentFolderId + location` together.
- Added automatic cleanup for source Folders that become structurally empty
  after moving Things or child Folders out. Cleanup walks upward through empty
  ancestors and trims the active Folder projection if the user was viewing a
  Folder that was removed.
- Changed private Folder Card binding so hidden private Folder Cards keep the
  stored Folder title visible while still hiding thumbnail/contained previews
  and keeping the lock indicator.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain`.

## 2026-06-17 - Generalize the baked media crop path after Folder preview fix

- The previous Folder thumbnail foreground-video-only baked crop trial solved
  the reported top/bottom video cover issue, so the user requested replacing
  all remaining local `ImageView.imageMatrix` crop display paths with
  pre-cropped bitmaps.
- Removed the Folder-preview-specific
  `shouldBakeThingCardForegroundMediaCrop(...)` hook. `FolderThingPreviewAdapter`
  now uses the same baked Thing Card media path as normal cards instead of a
  special-case crop branch.
- Kept the targeted `[DEBUG-tf-video-crop]` logging hook for Folder preview
  top/bottom foreground video while this area is still under active testing,
  but the logged render path is now the general baked bitmap path.
- Folder thumbnail replay continues to call
  `applyThingCardMediaCropToBoundHolder(...)`; that method now compares the
  final measured target geometry and crop fingerprint against the current baked
  bitmap key and reloads/re-bakes if they differ.
- Verified as part of debug update `202606171256`.

## 2026-06-17 - Folder thumbnail foreground video baked crop trial

- After reviewing the device log for Thing `304` (`content="测试测试测试"`),
  narrowed the remaining failure away from Folder-preview target geometry. That
  Thing generated a `316x316` target, loaded a portrait `316x562` video-frame
  drawable, and applied the expected `ImageView.ScaleType.MATRIX` crop. If the
  visual output still appeared portrait, the failure was after or outside the
  matrix-based display path.
- Added a protected `BaseThingsAdapter.shouldBakeThingCardForegroundMediaCrop(...)`
  hook. Normal Thing Cards, side media, and media backgrounds keep the existing
  `ImageView.imageMatrix` path. `FolderThingPreviewAdapter` enables the hook
  only for child Thing previews whose selected foreground media is video, media
  background is disabled, and placement is top or bottom.
- When the hook is enabled, `loadThingCardImage(...)` now appends the crop
  fingerprint to the media cache/load key, converts the loaded video-frame
  drawable into a target-sized bitmap using the same crop-center,
  source-aspect-ratio, and user-scale calculation, sets that bitmap directly on
  the `ImageView`, and skips later matrix replay for that render request. This
  avoids relying on final `ImageView.imageMatrix` drawing state for the failing
  Folder thumbnail top/bottom video path while keeping the behavioral surface
  narrow for device testing.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain`.
- Published debug update `202606171217` to the Aliyun debug channel and
  verified remote `latest.json` points to
  `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606171217.apk`.

## 2026-06-17 - Folder thumbnail top/bottom video crop replay fix

- Added targeted diagnostic logging for the still-failing Folder thumbnail
  top/bottom foreground-video case. The logs use the unique
  `[DEBUG-tf-video-crop]` prefix and are enabled only for `FolderThingPreviewAdapter`
  child Thing previews whose selected media source is a video, media background
  is disabled, and image placement is top or bottom. Each log line includes the
  Thing id, title preview, content preview, media source key, media path, and
  placement so the failing child Thing can be identified from logcat.
- Instrumented the bind, foreground media load, Glide resource callback,
  post-load render request, crop replay, and final matrix application stages.
  The diagnostics record target width/height, thumbnail target aspect ratio,
  folder preview surface height, crop values, video frame timestamp, current
  view/layout sizes, drawable intrinsic size, cache/reuse path, and matrix
  scale/offset inputs. This should distinguish whether the remaining failure is
  caused by wrong generated geometry, video-frame drawable dimensions, skipped
  crop replay, or a later layout/scaleType overwrite.
- Published debug update `202606171203` to the Aliyun debug channel and
  verified remote `latest.json` points to
  `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606171203.apk`.
- Reviewed logcat from the user's device for Thing `304`, whose content preview
  is `测试测试测试`. The bind path generated a `316x316` foreground thumbnail
  target for a bottom-placed video with target aspect ratio `0.99924934`, so
  the Folder-preview minimum-height guard is not the active cause for this
  Thing. Glide returned a portrait video-frame drawable (`316x562`), and the
  crop replay/final post-load path applied a matrix against the `316x316`
  target with source-aspect crop `0.99924934` and vertical offset `-62.455734`.
  If this Thing still visually appears as an uncropped portrait video in the
  Folder thumbnail, the remaining likely cause is that the applied
  `ImageView.ScaleType.MATRIX` state is later overwritten or bypassed by final
  drawing/layout state rather than the earlier target geometry calculation.
- Reverted the ineffective follow-up changes that tried to fix the top/bottom
  foreground-video case by adding a folder-thumbnail replay token, pre-draw
  replay scheduling, and a post-bind top/bottom media reload path. Device
  testing showed debug update `202606171003` still did not change the visible
  result, so that approach was removed before trying the next fix.
- Revised the diagnosis: Folder thumbnail child previews reuse the normal
  Thing Card top/bottom thumbnail height calculation, including the normal
  card's min/max height guardrails based on `surfaceAvailableHeight`. In a
  very narrow Folder preview column, the raw target height
  `imageW / thumbnailTargetAspectRatio` can be smaller than that minimum, so
  the minimum height wins and makes a default 4:3 or custom 1:1 thumbnail look
  much taller, close to a portrait video's intrinsic ratio.
- Added `BaseThingsAdapter.getThingCardForegroundThumbnailHeight(...)` as a
  protected hook. Normal Thing Cards keep the existing min/max guardrails, but
  `FolderThingPreviewAdapter` overrides the hook and returns the raw
  `imageW / getThingCardThumbnailTargetAspectRatio(thing)` height. This makes
  Folder thumbnail child cards generate top/bottom foreground media geometry
  directly from the thumbnail presentation ratio during binding, instead of
  trying to repair an already-bound media surface later.
- Published debug update `202606171146` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.
- After device testing still showed no visible effect from debug update
  `202606171003`, generated a Chinese PDF analysis report at
  `analysis/thing_folder_video_crop_flow_report.pdf`. The report documents the
  current video-frame Drawable generation path, crop parameter sources, crop
  matrix application, normal Thing Card media binding, Folder thumbnail preview
  binding/scale/replay flow, and the differences between top/bottom,
  left/right, and media-background media paths.
- Follow-up device testing showed debug update `202606170954` did not fix the
  top/bottom foreground-video thumbnail case. That version restored the target
  height during replay, but still used a single `post { ... }` replay timing and
  did not move foreground media to the same pre-draw/token pattern already used
  by media backgrounds.
- Left/right foreground media and media-background previews keep their existing
  geometry paths.
- Published debug update `202606170954` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.
- Published corrected debug update `202606171003` to the Aliyun debug channel
  and verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-17 - Thumbnail gap, crop ratio, and folder crash fixes

- Diagnosed two crash logs from device testing. Dragging inside a Folder could
  crash in `RecyclerView.onDraw(...)` because the temporary Folder-drop outline
  `ItemDecoration` was removed while RecyclerView was drawing its decoration
  list. Creating a Thing inside a Folder could crash in
  `ThingManager.deleteNEnow(...)` because folder projections may contain only
  the header row and no notify-empty row at index 1.
- Deferred Folder-drop outline decoration removal with `RecyclerView.post(...)`
  while clearing the adapter's active decoration reference immediately. This
  avoids mutating RecyclerView's decoration list during an active draw pass.
- Guarded `deleteNEnow(...)` so it only deletes the notify-empty row when the
  second `mThings` entry actually exists and is a notify-empty Thing.
- Split Folder thumbnail vertical spacing into a 12dp count-to-first-preview
  header gap and a 7dp child-preview item gap. Full-span masonry rows now own
  their first top gap, and first children inside columns do not add another top
  margin.
- Updated Thing Card Media crop application so thumbnail/side media uses
  `ThingCardThumbnailCrop.sourceAspectRatio`, and media-background previews
  use the saved media-background target aspect ratio. The final matrix now
  applies crop ratio, crop center, and user scale together for image and video
  previews.
- Verified with `git diff --check` and `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606170830` to the Aliyun debug channel with
  `memory/debug-update-notes.md` as the update note source.

## 2026-06-17 - Screenshot-driven thumbnail spacing and crop follow-up

- Reviewed a device screenshot of a thumbnail-mode Folder Card containing a
  mixed child Folder preview, short text/media previews, a full-span side-media
  preview, and a Habit media-background preview.
- Adjusted the child preview bottom spacer scale to match the general layout
  spacing scale. The previous 0.5-only bottom spacer scale overcorrected the
  earlier bottom-heavy cards and made Folder summary previews look top-heavy.
- Changed thumbnail media-surface protection so media container margins are
  still compacted while the actual media `ImageView`/mask is not scaled like an
  icon. This reduces the large gap between short text content and bottom media
  thumbnails without breaking edge-to-edge media drawing.
- Restored dynamic content text sizing for Folder thumbnail child previews by
  using the normal computed content size and clamping it to thumbnail-safe
  bounds. Short content such as a few Chinese characters can now render larger
  than long content inside thumbnails.
- Tightened media-background crop replay to prefer the current rendered media
  target size when available, so Habit media-background previews reapply crop
  against the final thumbnail geometry after compaction.
- Verified with `git diff --check` and `.\gradlew.bat :app:assembleDebug`.

## 2026-06-17 - Thumbnail bottom spacing and media crop replay

- Follow-up testing showed that Folder summary child previews and content-only
  Thing child previews still had too much bottom whitespace. The cause was a
  fixed-height `view_thing_padding_bottom` spacer that was not affected by the
  earlier padding/margin compaction pass.
- Added preview-only scaling for the Thing Card bottom padding spacer so
  `X things` count text and content-only text no longer keep a visibly larger
  bottom gap than top gap in Folder thumbnails.
- Follow-up testing also showed that Habit child previews with media, at least
  video media, could display the wrong crop after thumbnail compaction.
- Changed the bound-holder media crop reapply path so side media uses
  `ThingCardSideMediaCrop` instead of falling back to thumbnail crop.
- Folder thumbnail child previews now post a media-crop replay after the child
  card has been compacted and measured. This reapplies foreground, side-panel,
  or media-background crop against the preview's final target dimensions
  without rebinding the whole media-background card and undoing compact spacing.
- Verified with `git diff --check` and `.\gradlew.bat :app:assembleDebug`.

## 2026-06-17 - Thumbnail preview spacing, media cache, and shadow clipping

- Follow-up testing showed that child preview cards still kept too much of the
  ordinary Thing/Folder Card whitespace. The visible text and icons were
  smaller, but title/content/status padding and margins still consumed too much
  thumbnail space.
- Added preview-only layout spacing compaction in `ThingsAdapter`, applied
  after the child Thing or Folder card is fully bound. This scales internal
  padding and margins separately from text/icon scaling, so Folder headers,
  content, checklist, reminder, Habit, media-count, and audio rows get a
  tighter thumbnail layout without changing ordinary list cards.
- Preserved actual Thing Card Media surfaces during that scale pass. Side media
  panels and media backgrounds are no longer treated as generic `ImageView`
  icons, so left/right media remains edge-to-edge inside the child card.
- Added `BaseThingsAdapter.getThingCardHabitSummaryTextSize(...)` and set Habit
  summary text to the same preview base size as reminder time before the
  post-bind scale, avoiding a larger Habit summary in thumbnail previews.
- Added a protected Thing Card Media bitmap cache hook in `BaseThingsAdapter`
  and made Folder child preview adapters reuse the parent `ThingsAdapter`
  cache. Media-heavy child previews should now benefit from the existing LRU
  cache while scrolling, instead of spinning on every temporary child adapter.
- Disabled clipping on thumbnail preview containers so child preview elevation
  can draw outside column/list container bounds without reducing elevation
  further.
- Verified with `git diff --check` and `.\gradlew.bat :app:assembleDebug`.

## 2026-06-17 - Thumbnail preview card-wide text and icon scaling

- Follow-up testing showed that child preview cards only scaled their main
  content text. Titles, Folder icons, media/audio count labels and icons,
  reminder/habit/goal timing labels and icons, and the doing overlay still used
  ordinary list-card sizes inside thumbnail-mode Folder Cards.
- Kept the existing constrained full-card preview path, including content
  max-lines, checklist item limits, checklist read-only behavior, Habit summary
  simplification, media sizing, and child Folder summary-mode rendering.
- Added a post-bind preview-only scale pass in `ThingsAdapter` for child Thing
  and child Folder preview cards. The pass traverses the rendered view tree and
  scales `TextView` text, `TextView` compound drawables, and `ImageView` icons,
  so ordinary list cards remain unaffected.
- Tightened checklist preview text size and row icon scale through the nested
  checklist adapter so checklist rows stay compact even when their item views
  are created by the nested RecyclerView path.
- Verified with `git diff --check` and `.\gradlew.bat :app:assembleDebug`.

## 2026-06-17 - Nested Folder thumbnail previews and compact preview polish

- Added direct child Folder metadata to `ThingListEntry.FolderEntry`:
  `directFolderCount`, `thumbnailEntries`, and `thumbnailEntryCount`.
- Changed `ThingFolderDAO` thumbnail seed loading from recursive descendant
  Thing-only previews to direct child mixed entries. A thumbnail-mode Folder
  Card can now preview direct child Folders and direct child Things in their
  shared location order, capped by the existing normal/full-span limits.
- Child Folder previews render as summary-mode Folder Cards, regardless of the
  child Folder's own presentation mode. Tapping a child Folder preview opens
  that Folder through the same `openThingFolder(...)` path as ordinary Folder
  Cards.
- Updated Folder Card count text to combine direct child Folder count with the
  recursive matching Thing count, omitting zero-count segments.
- Reduced child preview card elevation to 2dp so shadows are less likely to be
  clipped by the existing compact preview spacing, and reduced the thumbnail
  ellipsis bottom margin to zero.
- Added English and Chinese string resources for mixed Folder/Thing count
  labels.
- Verified with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-17 - Thumbnail Folder Card preview layout polish

- Recorded the new thumbnail-mode Folder Card preview rules in
  `preferences.md`, `decisions.md`, and `plan.md`: normal-span Folder Cards use
  one preview column capped at three Things, full-span Folder Cards use a
  three-column masonry preview capped at six Things, and both show a compact
  bottom ellipsis when additional matching descendants are not rendered.
- Replaced the earlier title/content-only preview path in `ThingsAdapter` with
  a constrained `BaseThingsAdapter` preview path that inflates and binds the
  normal `card_thing` layout for each child Thing. Folder child previews now
  reuse Thing Card title handling, checklist rendering, image/video Thing Card
  Media rendering, media source/crop/frame selection, and full-span internal
  presentation.
- Added preview-specific Thing Card hooks for title text size, content line
  count, content text size, checklist item limit, checklist text size, and
  dense Habit detail visibility. Normal Thing Cards keep their existing
  behavior; Folder child previews use these hooks to stay compact without hard
  clipping the rendered card.
- Stripped nested interactions from child previews. Child cards only open the
  child Thing; checklist row toggles and long-press style card interactions are
  disabled inside the Folder Card preview surface.
- Added full-span-aware preview placement: full-span child Things span the full
  preview width inside a full-span Folder Card, while ordinary child Things use
  the three-column masonry distribution. Normal-span Folder Cards remain a
  one-column preview list.
- Added a shared `ThingFolderCardPresentation.effectiveThumbnailPreviewLimit()`
  so DAO thumbnail seed queries and UI binding use the same normal/full-span
  caps.
- Verified with `.\gradlew.bat :app:assembleDebug`, which completed
  successfully in the sandbox.
- Published debug update `202606170442` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.
- Follow-up testing showed ordinary Thing Card content text became oversized.
  The regression came from reading `TextView.textSize` after setting it: Android
  returns pixels, but assigning that value back through `textSize` treats it as
  sp. Changed the new preview hook to receive the computed default text size in
  sp before writing to the TextView, so ordinary Thing Cards keep their previous
  dynamic content text sizing while Folder child previews can still override it
  to 12sp.
- Re-verified with `.\gradlew.bat :app:assembleDebug`.
- Published fixed debug update `202606170448` to the Aliyun debug channel and
  verified remote `latest.json` points to the fixed APK.

## 2026-06-17 - Cross-entry position audit after mixed-list Detail fixes

- Audited `KEY_POSITION` usage across the home list, Detail, notification
  actions, full-screen notification dialogs, authentication actions, start/do
  and delay flows, widgets, image viewer, help pages, adapters, and
  `ThingManager` update APIs.
- Reconfirmed the intended split: `KEY_POSITION` remains the pure
  `ThingManager.getThings()` index used by manager update APIs, while
  `KEY_LIST_POSITION` carries the mixed Thing/Folder RecyclerView adapter
  position for targeted home-list notifications.
- Found that remote notification/widget actions could mutate `ThingManager`
  before broadcasting back to `ThingsActivity`, while only sending the pure
  Thing index. After mutation, a mixed-list fallback lookup could no longer
  recover the old adapter position reliably.
- Added pre-mutation `KEY_LIST_POSITION` capture in `RemoteActionHelper` so
  remote finish/type-correction broadcasts can remove or refresh the correct
  mixed-list row.
- Found the same missing pre-mutation capture for Detail instances opened from
  outside the home list, such as notification dialogs. Detail now captures the
  current mixed-list position by Thing id before result-producing mutations,
  including sticky/cancel-sticky moves.
- Follow-up audit for opened Thing Folder projections showed that a
  `KEY_LIST_POSITION` also needs the list projection that produced it. Opening
  a Folder replaces both `ThingManager.mThings` and `mThingListEntries` with
  the Folder projection, so an old adapter position from root or another Folder
  must not be trusted for targeted notifications.
- Added a stable `ThingListProjection.key()` and `KEY_LIST_PROJECTION` to bind
  `KEY_LIST_POSITION` to the active built-in destination plus Folder Path.
  `ThingsActivity` now uses old adapter positions only when the result
  projection matches the current projection; otherwise it falls back to id
  lookup or a full refresh.
- Reviewed the external position-audit report and rechecked folder-scoped
  creation. Creation from an `ALL_UNDERWAY` Folder projection was already
  folder-scoped because `ThingManager.create(...)` assigns
  `mProjection.currentFolderId`, but create results that returned to
  `ThingsActivity` from a non-`ALL_UNDERWAY` Folder projection could switch to
  `ALL_UNDERWAY/root` before the manager create call. The create intent now
  carries the source Folder id explicitly so new Things keep the requested
  Folder membership even if the visible list projection changes before create
  commit.
- Re-audited all `RecyclerView.Adapter.notifyItem*` calls. Main-list
  notifications in `ThingsActivity` use mixed-list adapter positions or full
  refresh fallbacks. Local adapters such as attachments, checklist editing,
  chooser rows, and reminder-time rows use their own local adapter positions
  and are not affected by Thing Folder projections.
- Fixed one real mixed-position smell in `ThingsAdapter`: inline checklist
  toggles now convert the card holder's mixed-list adapter position to a pure
  Thing index before calling `ThingManager.update(...)`, while still notifying
  the mixed-list adapter position for the visible card refresh.
- Standardized the main-list naming convention after the audit: pure
  `ThingManager.getThings()` positions use `thingIndex`; mixed
  Thing/Folder adapter positions use `listPosition`; old/new or source/target
  list positions are qualified accordingly. Applied the cleanup to Detail
  result passing, remote action broadcasts, swipe/undo/drop handling, new-item
  animations, and Thing Card appearance preview state.

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

## 2026-06-17 - Folder contextual actions and card appearance editing

- Changed Folder Card long-press behavior to follow Thing Card long-press
  semantics: underway folders enter moving mode and can be reordered or dropped
  into another Folder Card; releasing without a meaningful move enters selecting
  mode.
- Extended mixed `ThingListEntry` selection support so contextual toolbar counts,
  select-all, sticky, private, restore, dissolve, delete, and card-appearance
  actions can target a single selected Folder without routing through the old
  Folder action dialog.
- Added Folder Card appearance editing through the existing card appearance
  panel. Folder editing exposes a Folder name field, card width, and display
  mode choices while reusing the existing confirm/cancel preview lifecycle.
- Renamed the Folder appearance selector from display mode to Folder size, with
  Normal and Large as the user-facing choices.
- Kept the Folder size options on the same row as the label instead of reusing
  the Thing media-position two-line layout.
- Added current-folder overflow actions for toggling private state, dissolving
  the current Folder, and deleting or permanently deleting it depending on the
  current Deleted projection.
- Implemented Folder dissolve in the DAO/manager layer by moving direct child
  Things and child Folders to the parent, then removing the Folder record.
- Kept Folder delete semantics state-based: outside Deleted it moves the Folder
  subtree into Deleted through Folder state; in Deleted it uses recursive
  permanent deletion.
- Replaced Folder dissolve/delete confirmations with `AlertDialogFragment`
  prompts and added localized strings for the new Folder actions.
- Updated private Folder Card rendering so hidden private folders keep the
  Folder icon in the title row, suppress child counts, and show a lock below
  the title.
- Reduced the large audio-only count text in Folder thumbnail previews by 2sp.
- Verified the changes with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-18 - Full-session overlay drag controller

- Replaced the long-press Thing/Folder drag owner with a custom
  `ThingListOverlayDragController` that renders a full-size bitmap snapshot of
  the source card in the Activity overlay for the whole drag session.
- Kept the real source card in the RecyclerView as a transparent layout
  placeholder, with cleanup by stable Thing/Folder identity so recycled or
  detached ViewHolders do not keep transparent, scaled, dimmed, or highlighted
  state.
- Moved active drag move/up/cancel handling to `ThingsActivity.dispatchTouchEvent`
  while leaving inactive touch dispatch unchanged. The controller consumes the
  active session events so release is still observed outside RecyclerView
  bounds.
- Added controller-owned edge auto-scroll and stopped existing RecyclerView
  scroll/fling when the overlay session starts. ItemTouchHelper no longer owns
  Thing/Folder drag and now exposes only swipe flags for Thing Cards.
- Added source-coloured reorder insertion-line feedback, using the dragged
  Thing/Folder `ThingBackground` and supporting pure colours and gradients.
  Reorder is committed once at release by resolving source and target stable
  business ids.
- Preserved existing Folder-drop hover visuals by bridging the overlay
  controller to the current target shrink, outline, background, and content
  alpha feedback. Folder drop still uses the dragged card's top-left point and
  requires stable hover before arming.
- Preserved release outcomes for Folder drop, reorder, release-in-place
  selection, and cancellation. Folder-drop commits reuse the session overlay for
  the fly-into-target animation; selection/cancel restores or fades the overlay
  without committing stale data.
- Kept optional file-backed overlay drag debug logging behind a disabled flag.
- Verified the changes with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-18 - Overlay drag polish after device feedback

- Device testing of the first overlay-drag build showed four regressions:
  reorder insertion lines were drawn directly against card edges instead of in
  the visual gap, cards could remain in Moving-mode dimmed colours after a
  reorder, the source card could become visible again when auto-scrolling back
  to its original position, and reorder release animations fought
  RecyclerView's own `notifyItemMoved(...)` animation.
- Moved the insertion line to the midpoint between the candidate card and its
  nearest vertically adjacent visible card in the same horizontal span, with a
  small fallback gap when there is no visible neighbour.
- Added a RecyclerView child-attach guard to the overlay drag session so any
  reattached source ViewHolder is immediately restored to transparent
  placeholder state while the drag is active.
- Changed reorder release animation ownership: the overlay no longer flies to
  the final insertion edge. It is removed before the final `notifyItemMoved(...)`
  commit so RecyclerView owns the visible reorder movement. Release-in-place
  selection now fades the overlay out instead of flying it back.
- Changed successful reorder cleanup to exit Moving mode through
  `finishMovingModeWithoutListRefresh()` and schedule a delayed full rebind
  after item movement, restoring cards from Moving-mode dimmed colours without
  competing with the move animation.
- Verified the changes with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-19 - Private Folder auth, mixed sticky ordering, and move dialog

- Required privacy authentication before editing a private Folder card
  appearance, before moving Things/Folders into or out of private Folder
  boundaries, before expanding private Folders in the move-target tree, and
  before swipe actions on private Things in private contexts.
- Added Folder sticky support in the home list and kept sticky Things/Folders
  mixed in one parent-scoped sticky region. Toggling sticky now refreshes the
  visible list immediately instead of waiting for an app restart.
- Added parent-scoped sticky support inside Folders. Sticky Things/Folders move
  to the top of that Folder, use the parent Folder colour for their marker, and
  have sticky state cleared when moved out of the parent.
- Added a custom move-to-Folder DialogFragment with drawer-style tree rows,
  root selection, forbidden self/subtree targets, deferred commit until confirm,
  and Folder-coloured title/confirm affordances.
- Updated Folder drop and reorder behavior so private Folder drops authenticate
  before commit, and reorder insertion feedback is hidden when dragging normal
  cards ahead of sticky cards.
- Updated Thing Detail to show the Folder path below timestamps with a
  correctly coloured Folder icon and slash-joined path text.

## 2026-06-19 - Folder privacy titles, thumbnail sticky preview, and move-dialog polish

- Replaced generic private-auth titles for Folder operations with
  operation-specific titles for opening, expanding, managing, moving, and
  customizing private Folder content.
- Fixed large Folder thumbnail previews so sticky child Things/Folders use the
  same sticky-aware ordering as the home list. Negative sticky locations are no
  longer sorted behind normal children and dropped by the preview limit.
- Moved the Detail Folder-path icon down by 1dp so its visual centre aligns
  with the path text baseline.
- Updated the custom move-to-Folder dialog rows to occupy the available dialog
  width, use drawer-style root icon tint, provide full-row ripple feedback,
  and use circular ripple feedback on the expand/collapse affordance.

## 2026-06-20 - Folder-aware Things-list AppWidgets

- Extended Things-list AppWidget records with explicit target Folder, type
  filter mask, and List/Grid display mode fields, including v16 migration from
  the legacy negative `thing_id` limit encoding.
- Reworked Things-list AppWidget configuration to use a Drawer-like Folder
  scope picker, horizontal All/Note/Reminder/Habit/Goal type icons, List/Grid
  radio controls, and the existing content-card alpha/header-alpha/simple-view
  controls.
- Updated Things-list AppWidget rendering to build mixed direct child
  Thing/Folder entries, render Folder summary cards, route Folder/header taps
  through private Folder authentication, and use row-oriented RemoteViews Grid
  packing so full-span Thing and Folder cards can occupy a whole row.
- Updated single-Thing AppWidget configuration to support Folder navigation
  while preserving Thing-only selection: Folder cards navigate into child
  Folder projections, the title/back behavior follows the current Folder, and
  only Thing cards can be previewed/selected.
- Completed the remaining Folder-aware AppWidget gaps from review: Things-list
  AppWidget header/Folder clicks now preserve multi-type masks when opening the
  app, Things-list AppWidget configuration authenticates private Folder
  selection and expansion, and single-Thing AppWidget configuration reuses the
  home Folder Card binding instead of a local summary-card implementation.
- Renamed Things-list widget launcher labels from Underway-specific names to
  generic Things list names in the default and Simplified Chinese resources.
- Verified with `E:\projects\EverythingDone\gradlew.bat :app:assembleDebug`;
  `git diff --check` reported only existing line-ending normalization warnings.
- Published debug update `202606201023` to the Aliyun debug update channel and
  verified the remote `latest.json` points at
  `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606201023.apk`
  with SHA-256 `090e6e54206558b9f9270eb59b4bd57e05f49c8d5e2ad969d1a8f6a11a587a88`.

## 2026-06-20 - AppWidget Folder follow-up fixes

- Diagnosed post-publish feedback for Folder-aware AppWidgets: Single-Thing
  configuration Thing Cards diverged from the home list, selected-card preview
  controls did not fully adapt to the Thing background, Things-list Grid rows
  opened the first slot regardless of tap location, 4x4 Grid widgets could use
  too many columns, and widget alpha did not affect media-backed Thing Cards.
- Changed Single-Thing AppWidget configuration's Thing delegate from a local
  `BaseThingsAdapter` approximation to a `ThingsAdapter` delegate with a
  configuration-scoped data source and Folder-auth private-content rules.
- Updated the Single-Thing widget preview controls so the alpha slider follows
  the selected Thing background, the confirm action uses text-only Thing-colour
  styling with a pill ripple, and both the preview container and applied
  RemoteViews root receive the same rounded outline clipping.
- Updated Things-list AppWidget Grid rows so each visible slot owns its own
  fill-in intent while child item RemoteViews do not bind duplicate collection
  intents. Four-cell-wide Things-list widgets now use two Grid columns.
- Applied AppWidget alpha to rendered foreground media thumbnails and
  media-background bitmaps before setting them into RemoteViews.
- Verified with `.\gradlew.bat :app:assembleDebug`; added static checks for
  the key regression paths.
- Published debug update `202606201050` to the Aliyun debug update channel and
  verified the remote `latest.json` points at
  `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606201050.apk`
  with SHA-256 `b5e8cdf912f1d4a48b100645f2f5a90122caf43ef969ae910acfcc1075dce0a1`.

## 2026-06-20 - Single-Thing AppWidget configuration parity fixes

- Re-diagnosed Single-Thing AppWidget configuration after user feedback that
  the previous follow-up still did not match the home list for private Things
  inside large Folder previews and media-backed Thing Cards.
- Found that the mixed configuration adapter used `ThingsAdapter` delegates
  without attaching those delegates to the host RecyclerView, so home-card media
  width, delayed media background sizing, and crop replay could still use
  fallback dimensions instead of the configuration grid width.
- Added an explicit delegated-host RecyclerView binding path to
  `BaseThingsAdapter` and synchronized the Thing and Folder delegates from the
  Single-Thing configuration list before binding and after layout/span changes.
- Kept the Single-Thing preview on the `RemoteViews` path, fixed widget-root
  rounded clipping with a transparent rounded background, `clipToOutline`, and
  API 31+ RemoteViews outline-radius support, and switched media alpha to
  RemoteViews `ImageView` alpha for real-time preview parity.
- Wired large Folder preview thumbnail taps in Single-Thing configuration:
  child Thing thumbnails now select that Thing for preview, while child Folder
  thumbnails open that Folder through the same private-auth path as top-level
  Folder rows.
- Verified with `.\gradlew.bat :app:assembleDebug`; `git diff --check`
  reported only existing line-ending normalization warnings.
- Published debug update `202606201114` to the Aliyun debug update channel and
  verified the remote `latest.json` points at
  `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606201114.apk`
  with SHA-256 `a0b0472930930c00572ba8b233848c19cde8f4bd2cffa6701e87e0525475c5f6`.
## 2026-06-21 - Empty Thing Folders remain valid containers

- Updated the home-list implementation so structurally empty Thing Folders are
  no longer auto-deleted after moving Things or Folders out of them.
- Main home projections initially kept empty Folder cards visible when no
  search or color filter was active. Superseded below: filtered home-list
  projections now hide Folder cards whose subtree has no matching Things.
- Opened Empty Thing Folders now use Folder-specific home empty-state guidance.
- Verified as part of the Home Empty State build with
  `.\gradlew.bat :app:assembleDebug`.

## 2026-06-21 - Hide Folder Cards without matching filtered Things

- Updated the home-list `getFolderEntriesForTypeFilterProjection` path so a
  Folder Card is shown only when its subtree contains at least one Thing
  matching the current status and type filter.
- Kept structurally Empty Thing Folders valid in the data model; this change
  affects list projection visibility only and does not restore automatic empty
  Folder deletion.
- Left the broader `getFolderEntriesForProjection` path unchanged for
  configuration/browsing surfaces that may need to list empty folders.
- Verified with `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606210824` to
  `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606210824.apk`.
  Remote `latest.json` reports SHA-256
  `9629e4d95acc9c7ecee615137ed894a7c36251114ba240ed3500246a5d25367f`.

## 2026-06-22 - 拖拽创建文件夹 outline 渐变坐标修复

- 诊断拖拽一个记事到另一个记事以创建新文件夹时，pending Folder outline stroke 看起来总是纯色的问题。
- 确认创建路径已经使用 `ThingBackground.fromRandom()` 随机纯色/渐变，但 outline drawable 的渐变 shader 没有按 drawable bounds 平移，导致非左上角卡片大概率采样到同一个端点色。
- 将 `FolderDropOutlineDrawable` 的 `LinearGradient` 坐标改为实际 bounds 坐标，让随机渐变背景在创建文件夹的 stroke 上可见。
- 验证：`./gradlew.bat :app:assembleDebug` 通过。

## 2026-06-23 - 文件夹确认弹窗统一 + 动态筛选提醒

- 将完成/恢复/删除/解散/永久删除/还原文件夹的确认弹窗统一为四段式（动作+范围、计数、
  去向/可逆性、范围提醒），并去掉数字与文字间的空格。
- 新增 `appendFilterScopeReminder`：按受影响记事集合的真实类型/状态分布，动态决定是否
  追加「超出当前筛选」的提醒，并点名当前筛选的具体类型/状态；其它类型、状态无记事时不显示。
- 给原本没有确认的「还原 Trashed Folder」补上 `showRestoreThingFolderDialog`。
- 新增 `ThingFolderDAO.getAllDescendantThings` / `ThingManager.getAllDescendantThings`
  支撑结构态操作的提醒判定。
- 验证：`./gradlew.bat :app:assembleDebug` 通过。

## 2026-06-23 - 内容批量操作跟随类型筛选 + 工具栏文案分层 + 删除类对齐

- 工具栏批量动作与长按文件夹 contextual menu 文案分层：工具栏统一「全部完成/恢复/删除/
  永久删除」（根目录与文件夹内一致），contextual 用「完成/恢复文件夹中所有记事」；
  已完成根目录由"恢复全部记事"改"全部恢复"，回收站由"清空回收站"改"全部永久删除"。
- 「全部删除」「全部永久删除」对齐为递归+确认+提醒（`trashThings`/`deleteThingsForever`
  复用 `changeFolderSubtreeContentState`）。
- 内容类 5 个批量操作改为跟随当前类型筛选；提醒翻转为安抚式
  `folder_op_scope_only_type`。容器操作维持全类型、警告式提醒。
- 验证：`./gradlew.bat :app:assembleDebug` 通过。

## 2026-06-23 - 纯骨架模型重构(删除/还原文件夹 → 内容操作)

- 取消文件夹删除状态:用户对文件夹的"删除"改为"删除文件夹中所有记事"(递归 trash 子树
  进行中+已完成记事、跟随类型筛选);"还原文件夹"并入"恢复文件夹中所有记事"。
- 新增 `ThingManager.getNonDeletedThingsInScope` / `trashThingsPreservingState`;
  `ThingsActivity.confirmTrashFolderContent`;移除 `restoreSelectedFolderIfNeeded`/
  `showRestoreThingFolderDialog`/`showDeleteThingFolderDialog`(软删)。
- DBHelper v20 迁移 `migrateFoldersToSkeletonModel`;DATABASE_VERSION→20。
- `delete_thing_folder` 文案改"删除文件夹中所有记事";`restore_thing_folder_confirm` 移除。
- 验证:`assembleDebug` 通过。**需设备验证**:迁移、删空文件夹后首页消失/回收站可见可恢复、
  回收站永久删除。

## 2026-06-23 - 回收站删除操作二分 + 死代码清理 + 发布

- 回收站里区分「永久删除文件夹中所有记事」(内容、跟随筛选、不跨投影,`confirmDeleteForeverAllInScope`
  加 `titleRes`) 与「永久删除文件夹」(结构、`considerStatus=true` 警告跨投影)。新增菜单项
  `act_delete_thing_folder_content` + 串 `delete_all_things_in_folder_forever`。
- 删除死代码整块 `showThingFolderActions`/`addThingFolderAction`/`showFinishFolderContentDialog`/
  `showRestoreFolderContentDialog`/`FOLDER_ACTION_*` 及孤立串(还原文件夹、完成/恢复当前筛选下的内容)。
- 发布 debug `202606230359` → http://120.25.194.207/everythingdone-updates/debug/latest.json
  发布日志 docs/features/thing-folders/debug-updates/update-20260623115846.md。

## 2026-06-23 - 菜单去重与空状态梳理

- `configureCurrentFolderMenu` 重写:工具栏内容批量动作按 `hasVisibleProjectionContent()`
  门控(空范围隐藏);overflow 的 `act_delete_current_folder` 收敛为仅回收站的结构
  "永久删除文件夹",移除与工具栏重复的"删除文件夹中所有记事"。
- 文件夹内 overflow 只剩结构操作;跨状态"删除文件夹中所有记事"经上层长按 contextual 触发。
- 验证:`assembleDebug` 通过。需设备确认:空文件夹不再出现全部完成/删除等;finished 文件夹内
  不再同时出现"全部删除"和"删除文件夹中所有记事"。

## 2026-06-23 - 回收站全部恢复 + 返回回默认 + 类型筛选会话级

- 回收站工具栏加"全部恢复";`confirmRestoreTrashedThingsInScope` 支持 titleRes/根目录正文。
- 返回键在根目录非默认筛选时先 `resetRootProjectionToDefault()`(正在进行+全部类型+根目录),
  已是默认才退出。"返回退出后不保留类型筛选"由此直接得到(退出时必为默认);按 Home 仍保留筛选。
- 验证:`assembleDebug` 通过。需设备确认:回收站全部恢复;根目录切换状态/类型后按返回先回默认
  再按返回才退出;返回退出再打开为默认筛选;Home 退出再打开仍保留筛选。
- 撤销上一版误加的后台检测重置(它会在 Home 时也清筛选);现"返回退出不保留、Home 保留"仅靠
  返回键回默认实现。发布 debug `202606230609`,日志 update-20260623140902.md(含菜单去重/空状态)。

## 2026-06-23 - 子文件夹字样动态化 + 正在进行文件夹内删除项

- "（含所有子文件夹）"改为按 `subfolderClause(folder, things)` 动态显隐,挂在计数后(共N件%2$s);
  11 条内容正文改为 %1$d + %2$s。
- 正在进行文件夹内 overflow 首项加回"删除文件夹中所有记事"(act_delete_current_folder,
  underway 可见);已完成仍只靠工具栏"全部删除",回收站为"永久删除文件夹"。
- 验证:`assembleDebug` 通过。需设备确认:无子文件夹的文件夹弹窗不再有"含所有子文件夹";
  根目录有子文件夹时弹窗带该字样;正在进行进文件夹 overflow 首项有"删除文件夹中所有记事"。

## 2026-06-23 - 文档审计：use-cases 等与实现对齐

- A：use-cases.md 重写到纯骨架 + 跟随筛选模型——核心规则2/5、D3、S3、F2/F3（删 F4）、
  T1–T5（删 T3b/T3c，区分永久删除文件夹 vs 永久删除文件夹中所有记事）、R2、操作菜单章节、
  确认 Dialog 章节、实现要点章节全部更新。
- B：README 状态改为 implemented 并补 use-cases 入口；followups 死代码清理标记完成、回收站
  递归查询条目按新模型重述。
- C：plan.md 顶部加“已被取代”横幅 + DB 版本/删除模型行内标注；drawer-type-filter/decisions.md
  顶部加横幅，并修正“文件夹进入回收站从范围选择器消失”那段（纯骨架下不成立）。

## 2026-06-25 - 修复大文件夹缩略图里“正在做”图标贴边

- 用户反馈：大文件夹缩略图里若某条记事正在做、且内容只有一行、卡片很矮时，“正在做”蒙层
  的图标（`vec_ic_doing_thing`，固定 48dp 高）会紧贴卡片上下边缘，不美观。
- 根因：doing 蒙层 `fl_thing_doing_cover` 内的 TextView 垂直居中、图标 48dp 固定。矮卡片
  内容区高度不足 48dp 时图标占满高度、上下无留白；文件夹迷你卡片再整体 `scaleX/Y` 缩放，
  贴边更明显。纯加 margin 无效（图标本身比内容区高）。
- 修复（改的是通用 doing 蒙层逻辑，主列表矮卡片一并受益）：`card_thing.xml` 给 doing
  TextView 加 `tv_thing_doing` id；`BaseThingViewHolder` 加 `tvDoing`；`updateCardForDoing`
  在卡片测量后新增 `adjustDoingCoverIconSize`，按卡片高度动态设定图标尺寸——够高保持
  48dp，矮到放不下时压到「卡片高 − 上下各 8dp」。逻辑高度上算留白，随 scale 缩放一起保留。
  顺手移除该方法里每次绑定都打印的 `Log.i` 调试日志。

Verification:
- `:app:assembleDebug` 通过（无 error / warning）。`BaseThingsAdapter` 仍有别处 `Log` 使用，
  import 不受影响。
- 纯视觉调整，未在本会话做真机验证，交用户自测（发布说明已列测试重点）。

Publish:
- `publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/thing-folders/debug-updates/update-20260625111435.md"`
  通过，发布 debug update `202606250314` 到
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`。
- 未创建 Git 提交。

## 2026-06-25 - 正在做提示图标与文字大小协调、矮卡片留白

- 接上一条的后续反馈。上一版 `adjustDoingCoverIconSize` 只按卡片高度缩了 doing 图标，没动
  右侧文字字号，导致图标小、文字偏大、不协调；大文件夹缩略图迷你卡片尤其明显——迷你卡片走
  `FolderThingPreviewAdapter`（继承 `BaseThingsAdapter`，同一 `updateCardForDoing` 路径），
  `adjustDoingCoverIconSize` 经 `cv.post` 在迷你卡片整体缩放（`applyFolderThumbnailPreviewScale`
  → `scaleFolderThumbnailText` / `scaleFolderThumbnailCompoundDrawables`）之后执行，覆盖了图标
  尺寸却没接管文字，于是图标按高度压小、文字仍是迷你卡片那套 0.9 缩放，比例不一致。
- 另外用户反馈普通列表矮卡片里 doing 图标上下间距偏小。
- 修复：把 `adjustDoingCoverIconSize` 重写为 `adjustDoingCoverScale`，按卡片高度算一个统一
  比例，图标、文字字号（以 `tv_thing_doing_text_size` 为基准）、图文间距一起等比缩放；上下
  留白改用卡片高度的比例（`cardHeight * 0.18`），矮卡片留白更舒适、迷你卡片也自适应。本方法
  在迷你卡片缩放之后才跑，统一接管 doing 蒙层尺寸，图标与文字始终协调。`card_thing.xml` 的
  `tv_thing_doing` id 与 `BaseThingViewHolder.tvDoing` 沿用上一条已加的；`BaseThingsAdapter`
  新增 `import android.util.TypedValue`。

Verification:
- `:app:assembleDebug` 通过（无 error / warning）。
- 纯视觉调整，未在本会话做真机验证，交用户自测。

Publish:
- `publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/thing-folders/debug-updates/update-20260625114136.md"`
  通过，发布 debug update `202606250342` 到
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`。
- 未创建 Git 提交。

## 2026-06-25 - 修复右滑时正在做蒙层尺寸先闪现再跳变

- 用户反馈：记事右滑会预览"正在做"蒙层，但蒙层里的图标/文字先以默认大小闪现、随后才跳变
  成最终缩放后的大小。
- 根因：`updateCardForDoing` 把蒙层尺寸与缩放都放在 `cv.post` 回调里，而右滑预览路径
  （`ThingsActivity` 的 `ItemTouchHelper.onChildDraw`，`dX > 0` 分支）只设了蒙层宽高、根本没调
  缩放，于是图标/文字停留在 xml 默认大小（48dp / 16sp），要等下一次 bind 的 post 才变成缩放后
  的大小。
- 修复：把缩放逻辑下沉为 `BaseThingViewHolder.applyDoingCoverScale()`（用 `itemView` 取
  context / 资源），bind 与右滑两条路径共用。`updateCardForDoing` 改为卡片已测量
  （`cv.height > 0`）时同步调用、未测量时才 `post` 兜底；右滑预览首次显示蒙层时也同步调一次。
  这样卡片已布局的场景蒙层一出现即最终大小，不再跳变。删除原 adapter 里的 `adjustDoingCoverScale`。

Verification:
- `:app:assembleDebug` 通过（无 error / warning）。
- 纯视觉调整，未在本会话做真机验证，交用户自测。

Publish:
- `publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/thing-folders/debug-updates/update-20260625115345.md"`
  通过，发布 debug update `202606250354` 到
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`。
- 未创建 Git 提交。
