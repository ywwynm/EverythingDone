# Selection Batch Actions Sessions

## 2026-06-27 - 修复搜索态选择操作后列表跳出搜索结果

- 用户转述 Claude code review，要求核对后再决定是否修改。确认 review 的刷新一致性结论成立：选择模式下的状态操作、恢复、移动到文件夹、置顶和私密等路径，部分会经由 `ThingManager.loadThings()` 清空搜索字段，使搜索框仍保留筛选但列表显示当前文件夹全量内容。
- 修复选择操作的收尾刷新：状态类内容操作统一通过搜索感知的 `refreshHomeAfterScopeStateChange()` 收尾；批量移动、置顶、私密涉及文件夹时，manager 只修改数据并延迟 reload，再由 Activity 恢复当前搜索文本和颜色筛选。
- 单文件夹范围内容操作也同步修正：在搜索态下收集记事时透传当前搜索条件，确认弹窗继续合并“搜索范围”提醒。
- 不改项：`enterSelectionMode()` 的重复选中设置保持不动；DAO SQL 与内存搜索剥离 signal 的差异暂作为维护风险观察。
- 验证：`:app:assembleDebug` 通过，`git diff --check` 通过。未使用 adb。
- 发布：随 `docs/features/thing-folders/debug-updates/update-20260627155027.md` 发布到阿里云 debug 通道，更新码 `202606270752`。

## 2026-06-27 - 清理选择操作刷新冗余

- 用户继续转述两个 nitpick，确认可以安全清理：状态类选择操作不必先在 manager 中 `loadThings()` 再由 Activity 重新搜索；批量置顶记事不必逐项重建列表；进入选择模式也不需要两次设置同一项选中。
- 状态类选择操作传 `reload=false`，保持由 `refreshHomeAfterScopeStateChange()` 统一刷新；批量置顶最后统一 `rebuildCurrentThingListEntries()`；`enterSelectionMode()` 只在模式切换后设置选中。
- 语义不变：选择范围、搜索范围、最终列表刷新和菜单状态保持上一版逻辑。
- 验证：`:app:assembleDebug` 通过，`git diff --check` 通过。未使用 adb。
- 发布：随 `docs/features/thing-folders/debug-updates/update-20260627161550.md` 发布到阿里云 debug 通道，更新码 `202606270816`。

## 2026-06-27 - 结构操作确认弹窗合并搜索范围提示

- 用户反馈：解散文件夹、永久删除文件夹等影响文件夹结构的操作，确认弹窗只提示状态/类型筛选下看不到的内容，没有提示搜索范围。
- 修复 `ThingsActivity.hiddenScopeClauseForStructural(...)`：搜索模式下把“搜索范围”纳入结构操作隐藏范围短语；若同时存在状态筛选、类型筛选和搜索范围，会合并为同一句“该操作作用于整个文件夹子树，包含当前……下看不到的内容。”。
- 新增默认英文、简中、繁中资源短语，覆盖搜索范围与状态/类型筛选的组合。
- 同步更新 `decisions.md` 与 `wording-matrix.md`，明确结构操作是警告式提示：它作用于整个子树，不是仅作用于搜索范围内的记事。
- 验证：`git diff --check` 通过，仅有既有 LF/CRLF 提示；`:app:assembleDebug --console=plain --no-configuration-cache` BUILD SUCCESSFUL。
- 发布：随 `docs/features/thing-folders/debug-updates/update-20260627140824.md` 发布到阿里云 debug 通道，更新码 `202606270608`；远端 `latest.json` 指向 `app-debug-202606270608.apk`，SHA-256 为 `936bc02d07e58ee5154b6e9ca41fbd19687fdf46100388d6a3657d8c792c9aff`。

## 2026-06-27 - 搜索结果中的选择动作限制到搜索命中记事

- 搜索模式下，纯记事选择的确认弹窗新增“当前搜索范围”提醒；包含文件夹的选择会把所选文件夹内容收集限制到当前搜索关键词和颜色命中的记事。
- `ThingManager` 的范围内容收集方法新增可选搜索条件，复用首页搜索对清单状态标记、私密标题前缀和颜色 hue bucket 的匹配语义。
- `HomeActionWordingHelper` 新增 `searchScoped` 输入；类型筛选与搜索范围同时存在时合并为同一句提醒，仅搜索范围时单独提示。范围提醒统一使用“本次操作仅作用于”开头。
- 验证：`git diff --check` 仅有仓库既有 LF/CRLF 提示；`E:\projects\EverythingDone\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` BUILD SUCCESSFUL。
- 发布：随 `docs/features/thing-folders/debug-updates/update-20260627135127.md` 发布到阿里云 debug 通道，更新码 `202606270551`。

## 2026-06-25 - 选择操作确认弹窗按单选/多选配色

- 用户反馈：移动到文件夹、完成/删除/恢复等确认弹窗应区分单选/多选——单选 1 项用该项颜色，多选
  用当前文件夹色（根目录 accent 渐变）。此前先有一版把移动弹窗的单选也改成了当前文件夹色，属理解
  错误，用户要求纠正。
- 新增 `ThingsActivity.selectionDialogBackground()` 统一表达规则（按当前选中数判定单/多选），接入
  移动（`showMoveSelectedThingsDialog`、混合移动）、完成/恢复（`confirmThingsOnlyStateChange` /
  `confirmMixedStateChange`）、结构性永久删除（`confirmDeleteSelectedStructural`）的批量确认弹窗；
  单文件夹专属弹窗（`showMoveThingFolderDialog`、`showDeleteThingFolderForeverDialog`）保持用该文件夹
  颜色。详见 [decisions.md](decisions.md) 同日条目。
- 教训：上一条用户只说"多选"时，我没有逐一辨别移动弹窗各入口的单/多选语义，把单选一并改了。改这类
  "按选择配色"的逻辑必须先分清单选/多选边界。

Verification:
- `:app:assembleDebug` 通过（无 error / warning）。交用户自测。

Publish:
- `publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/selection-batch-actions/debug-updates/update-20260625142404.md"`
  通过，发布 debug update `202606250624` 到
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`。
- 未创建 Git 提交。

## 2026-06-24 - 修正回收站恢复与空文件夹结构操作文案

根据 `HomeActionWordingHelper` action 文案审查，保留“选择单文件夹”与“进入文件夹后的 actionbar”当前差异不动，修正其它问题：

- 纯记事选择在回收站执行“恢复”时改用 `ThingManager.restoreTrashedThings()`，与文件夹/混合选择一样逐条恢复到删除前状态。
- 回收站恢复确认正文新增专用模板，明确说明会恢复到删除前状态；同步默认英文、简中、繁中及现有外语资源。
- 解散/永久删除空文件夹时新增空内容结构正文，避免“共无内容”这类不自然表达。

## 2026-06-24 - 审查 HomeActionWordingHelper 生成的 action 文案

按 `HomeActionWordingHelper`、`ThingsActivity`、`ModeManager` 与简中/默认字符串资源核对首页 action 文案。发现的重点风险集中在回收站“恢复”未说明删除前状态、单文件夹选择文案未与进入文件夹后的 scope 文案完全一致、以及纯记事选择的回收站恢复路径与文件夹/混合路径存在行为差异。暂未修改代码，等待用户确认处理方向。

## 2026-06-23 - Home action 文案改为统一 ActionWording 模型

根据用户反馈，统一 action 文本、dialog 标题、dialog 正文和确认按钮来源，避免“所选文件夹中的/所选文件夹的/所选项中的”等漂移。

- 新增 `HomeActionWordingHelper`：状态内容动作、置顶/私密属性动作、解散/永久删除文件夹等结构动作分 family 生成文案。
- `ModeManager` 的选择模式菜单标题改走 helper：状态动作按纯记事/所选文件夹/所选项生成完整语义标题；置顶、设为私密保持短文案；回收站结构删除按单文件夹/多文件夹/混合选择自适应。
- `ThingsActivity` 的普通工具栏、选择确认框、文件夹内容操作、结构操作确认框统一使用 `ActionWording`，dialog 标题等于 action title，正文继承 title；确认按钮走通用 `R.string.confirm`，中文保持“确定”。
- 正文里的数量、子文件夹命中提醒、类型筛选提醒、可恢复/不可恢复提醒集中生成；删除旧的 `appendFilterScopeReminder` 调用路径。
- i18n：新增完整资源模板；状态目标短语与结构目标短语分开，支持德语/俄语等不同格或介词；中文数字和单位无空格。补齐默认英文、简中、繁中、德/西/法/印地/意/日/韩/葡/俄资源。

验证：`:app:assembleDebug` BUILD SUCCESSFUL。已发布阿里云 debug update，code `202606231559`。

## 2026-06-23 - Part B 实现并发布（多文件夹 / 混合 / 批量私密置顶）

`ModeManager.updateMenuItemsForFolderSelection` 重写：去掉 `singleFolderOnly` / `!hasSelectedFolder`
闸门。状态动词（finish/delete/restore/delete-forever）对任意选择可见，文案随组合自适应
（`setStateVerb`：纯记事 / 单文件夹 / 混合三套 string）。`act_finish_thing_folder` /
`act_restore_thing_folder_content` / `act_delete_thing_folder_content` 在选择模式隐藏（被统一动词覆盖）。
结构操作仅单文件夹：解散（underway）、永久删除文件夹容器（deleted）。置顶 / 私密改为对任意选择可见、
智能置位文案；卡片外观仍单选。

`ThingsActivity`：
- `confirmSelectedStateChange` 分流：纯记事走 `confirmThingsOnlyStateChange`（Part A，handleUpdateStates）；
  含文件夹走 `confirmMixedStateChange` —— 合成「选中记事 + 各文件夹范围内容」并集，一个汇总确认弹窗
  （`appendFilterScopeReminder` + 总数），确认后 `applyUnionStateChange` 调用对应批量方法
  （finishThings / trashThingsPreservingState / trashThings / unfinishThings / restoreTrashedThings /
  deleteThingsForever）+ `refreshHomeAfterScopeStateChange`。finish 命中习惯/目标时复用
  `showFinishScopeHabitGoalDialog`。`collectFolderScopeThings` 按 (status, stateAfter) 选取范围内容
  （underway 删除用 `getNonDeletedThingsInScope`）。
- `confirmMoveSelected` / `moveSelectedMixedToFolder`：纯记事 / 单文件夹复用旧对话框；混合 / 多文件夹用
  统一对话框，禁止目标 = 各选中文件夹及其后代并集，默认选「全部记事」根；移动记事
  `moveSelectedThingsIntoFolder` + 逐个 `moveFolderIntoFolder`；跨私密边界整批一次鉴权。
- `toggleSelectedStickyBatch` / `toggleSelectedPrivateBatch` + `applySelectedPrivateBatch`：智能置位；
  私密置位跳过空标题 / doing 并提示，取消方向整批一次鉴权（含文件夹）。

不变量：选中项为同级兄弟，选中文件夹后代不会同时被选中 → 并集天然不重叠。新模型下文件夹无 DELETED
容器态，回收站中文件夹均为 Projection Folder，故「恢复」只恢复范围内容、不涉及容器。

字符串新增 `act_*_selected_items`（混合标签）、`confirm_*_selected_items`（混合确认）、
`no_matching_things_in_selection`、`private_batch_skipped`（英文默认 + zh-rCN）。

`:app:assembleDebug` 通过。`:app:publishDebugUpdate` 发布阿里云，code `202606231055`。

遗留：`toggleSelectedStickyEntry` / `toggleSelectedPrivateEntry` 等单选旧方法变为未用（仅告警）；
新增字符串其余语言回退英文。详见 `followups.md`。

## 2026-06-23 - 批量动作文案按选择组合三桶自适应（修复）

用户反馈混选时「设为私密记事」文案不对。确立规则：动作文案按选择组成分三桶——全记事用记事文案、
全文件夹（任意数量）用文件夹文案、混合用「项」文案。

- `ModeManager.setStateVerb` 中间桶由「单文件夹」改为「全文件夹（!hasThing）」，复用现有文件夹串；
  完成/删除/恢复/永久删除菜单标签据此三桶。
- `updateMenuItemPrivate` 改三桶：记事用 `act_set/cancel_private_thing`，文件夹用
  `set/cancel_thing_folder_private`，混合用新增 `act_set/cancel_private_items`。
- `ThingsActivity.confirmMixedStateChange` 确认弹窗标题按 `foldersOnly` 选文件夹串或「项」串。
- 置顶/移动/导出/全选类型中立不改；卡片外观单选已按记事/文件夹区分。

新增字符串 `act_set_as_private_items` / `act_cancel_private_items`（英文默认 + zh-rCN）。
发布阿里云，code `202606231136`。

## 2026-06-23 - 普通模式工具栏文案随范围自适应（修复）

把文案规则补到普通模式工具栏（actionbar）及其确认弹窗。此前 `configureCurrentFolderMenu` 刻意保持
工具栏「全部X」中性文案、文件夹文案仅留给长按菜单；用户要求工具栏在文件夹内也用文件夹文案，遂改：

- `configureCurrentFolderMenu`：`act_finish_all` / `act_restore_all` / `act_delete_all` /
  `act_delete_all_forever` 标题按 `inFolder` 切换——根目录用 `act_finish_all` / `restore_all_things` /
  `act_delete_all` / `act_delete_all_forever`（全部X），文件夹内用 `finish_all_things_in_folder` /
  `restore_all_things_in_folder` / `delete_thing_folder` / `delete_all_things_in_folder_forever`。
- 工具栏处理（`act_finish_all` 等分支）传入对应 `titleRes`，确认弹窗标题随之；内容本就按 root/in-folder
  分 `*_root_confirm` / `*_in_folder_confirm`，无需改。
- `confirmTrashAllFinishedInScope` 增加 `titleRes` 参数（默认 `act_delete_all`）。
- 文件夹 overflow 结构操作本就文件夹文案，未改。

四个入口（根目录工具栏 / 文件夹内工具栏 / 长按选择模式 根目录与文件夹内）文案统一。发布阿里云，code `202606231201`。

## 2026-06-23 - 文案全面统一（标题/弹窗标题/弹窗正文）

确立并落地统一规律（见 `wording-matrix.md`）：标题 = 动词 + 对象；动词只用 完成/删除/恢复/永久删除；
类型筛选只改行为与提醒、不进标题；弹窗正文统一陈述句、数字与中文无空格、正文用词与标题一致。

落地：
- 根目录改「动词在前 + 所有」：`act_finish_all`/`restore_all_things`/`act_delete_all`/`act_delete_all_forever`
  值改为 完成/恢复/删除/永久删除所有记事（这 4 个 id 仅用于根作用域工具栏与弹窗，安全改值）。
- 动词统一：`act_restore_selected` 还原→恢复、`act_delete_selected_forever` 彻底删除→永久删除。
- 单文件夹选择复用 scope 弹窗：`confirmSelectedStateChange` 新增分支，单文件夹路由到
  `confirmSingleFolderStateChange`（调用 confirmFinishAllThingsInScope / confirmTrashFolderContent /
  confirmTrashAllFinishedInScope(delete_thing_folder) / confirmUnfinishAllThingsInScope /
  confirmRestoreTrashedThingsInScope / confirmDeleteForeverAllInScope(delete_all_things_in_folder_forever)），
  与文件夹内 actionbar 完全一致。`confirmMixedStateChange` 因此只处理多文件夹与混合。
- 多文件夹用新 `confirm_*_selected_folders`（所选文件夹文案）；混合 `confirm_*_selected_items` 改陈述句+所选项；
  纯记事 `confirm_*_selected_things` 改陈述句+去空格。
- 数字与中文空格：scope 文案与 folderImpactPhrase（folder_count_segment/thing_count_segment）本就无空格；
  仅选择类文案有空格，已去除。

注：作用域名「全部记事」（All Things Scope，drawer/移动对话框/widget 的根标签）是术语，未改；
动作里的「所有」与范围名「全部记事」是不同概念，保持各自用法。发布阿里云，code `202606231256`。

## 2026-06-23 - 混合桶文案消歧（所选项 → 所选项中的记事）

混合选择时状态动作（完成/删除/恢复/永久删除）对象由「所选项」改为「所选项中的记事」，消除"是否删除
文件夹容器"歧义（这些动作对文件夹只动其中记事、容器保留；回收站另有结构操作「永久删除文件夹」）。
只改混合桶：纯记事说「选中的记事」、单/多文件夹说「文件夹中所有记事」本就清楚；移动/置顶/设为私密在混合时
确实作用于文件夹容器，保持「项」。标题、弹窗标题、弹窗正文一并更新（`act_*_selected_items` /
`confirm_*_selected_items`，en + zh）。发布阿里云，code `202606231307`。

## 2026-06-23 - 回收站结构性永久删除 + 外观/私密/置顶限正在进行

1. 回收站新增结构性「永久删除所选项 / 永久删除选中的文件夹」（`act_delete_thing_folder` 由
   `singleFolderOnly && deleted` 放宽到 `hasFolder && deleted`，文案自适应），与内容操作
   「永久删除…中的记事」区分。新方法 `confirmDeleteSelectedStructural`：单文件夹复用
   `showDeleteThingFolderForeverDialog`，多/混合聚合计数后逐个 `deleteFolderForever` + `deleteThingsForever`。
   新字符串 `delete_selected_folders_forever` / `delete_selected_items_forever_structural`（+ `_confirm`）。
2. 卡片外观收口为仅正在进行：`canCustomizeSelectedThingCardAppearance` 加 `status==UNDERWAY`（原只挡已完成、
   漏了回收站）。私密、置顶本就仅正在进行。

发布阿里云，code `202606231316`。

## 2026-06-23 - 结构性永久删除弹窗正文统一

多选文件夹 / 混合的结构性永久删除此前硬编码固定提醒，未反映实际状态/类型筛选。改为与单文件夹一致用
`appendFilterScopeReminder(affected, contentState=false, considerStatus=true)` 动态生成（无类型筛选则不显示
类型部分）；`confirmDeleteSelectedStructural` 收集 `selectedThings + 各文件夹 getAllDescendantThings` 作为
affected。批量 `*_confirm` 去掉硬编码提醒尾巴；单文件夹 `delete_thing_folder_forever_confirm` 补「将」前缀，
三者正文结构一致。文案统一「含」→「包含」（`scope_includes_subfolders`）。发布阿里云，code `202606231328`。

## 2026-06-23 - 文案/行为 9 项修复

1. 多文件夹标题用单数→新增 `act_*_selected_folders`「…所选文件夹中的所有记事」；`setStateVerb` 增 `singleFolder`
   形参区分单/多（单→`finish_all_things_in_folder`，多→`act_*_selected_folders`），与 `confirmMixedStateChange`
   （仅多文件夹/混合可达）标题一致；单文件夹仍走 `confirmSingleFolderStateChange`（沿用 in-folder scope 文案）。
2. 功能：`collectFolderScopeThings` 与 `confirmTrashFolderContent` 的 UNDERWAY+DELETE 改用 `getUnderwayThingsInScope`
   （原 `getNonDeletedThingsInScope`），正在进行删除只删进行中；`delete_thing_folder_confirm` 文案改「正在进行」。
3. `confirmMixedStateChange` 给 `confirm_*_selected_folders/_items` 串加 `%2$s`，按 folderContent 是否落入
   非选中文件夹 id 追加 `scope_includes_subfolders`。
4. 回收站措辞统一「回收站中」（`delete_forever_all_in_folder_confirm`、`restore_all_trashed_*`、
   `confirm_delete_selected_folders_forever`）。
5. 纯记事确认正文改「将X所选记事，共 N 件」（`confirm_*_selected_things`）。
6. 「选中的」→「所选」（`act_*_selected`、`delete_selected_folders_forever`、结构确认正文）。
7. `dissolve_thing_folder_confirm` 改「将解散该文件夹：…」，去掉「彻底删除」。
8. 移动 Dialog 根节点 `R.string.underway`→新增 `all_things_scope`「全部记事」（用户说「所有记事」，实现用范围名
   「全部记事」与抽屉/词汇表一致；待确认）。
9. `toggleSelectedPrivateBatch` 取消私密鉴权标题按组成自适应（记事/文件夹/项）。

发布阿里云，code `202606231415`。

## 2026-06-23 - 确认弹窗正文统一为单一模板（DRY）

把六种入口（根目录、文件夹内工具栏、单/多文件夹、混合、纯记事）的确认正文收敛到同一套带范围占位符的
字符串模板，杜绝漂移：
- 6 个 `*_in_folder_confirm` 串加 `%1$s` 范围占位符（→ `%1$s的所有[状态]的记事，共%2$d件%3$s…`），
  由 `confirmFinishAllThingsInScope` 等（folder 分支传「所选文件夹」）、`confirmMixedStateChange`
  （传「所选文件夹」/「所选项」）、`confirmThingsOnlyStateChange`（传「所选」）共用。
- 新增 `scopeConfirmBodyRes(stateAfter, status)` 按状态选模板；新增 scope 词
  `scope_selected_things/folders/items` 与 `thing_status_deleted`。
- root 串保留无范围；状态词统一「正在进行/已完成/已删除」，`delete_forever_all_root_confirm` /
  `restore_all_trashed_root_confirm` 的「回收站中的」改「已删除的」。
- `statusNameRes(DELETED)` 由 `drawer_deleted`（回收站）改 `thing_status_deleted`（已删除），
  使 `appendFilterScopeReminder` 的状态词为「已删除」。
- 多选/混合也显示「（包含所有子文件夹中的记事）」。
- 「该文件夹/整个文件夹」→「所选文件夹」（dissolve、单文件夹永久删除等）。
- 旧 `confirm_*_selected_things/_items/_folders` 串弃用（保留为死串，后续可清理）。

发布阿里云，code `202606231454`。
