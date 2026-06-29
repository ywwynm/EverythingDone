# 私密记事/文件夹 优化实现 Plan

依据 `decisions.md`（8 条 + a/b 收尾）落地。本文件给出实现分解与代码触点；进度跟踪见 `execution.md`。

## 依赖与分期

- **基础设施先行**：决议 8 的"集中有效私密解析"和决议 4 的"会话级认证集合"是多条决议的依赖，先做。
- 决议 3 / 5 / 7 依赖决议 4（会话内已认证语义）。
- 决议 8 的堵漏、决议 6 的导出、Noticeable 改造依赖决议 8 的集中入口。
- 决议 2 / b 文案独立、低风险，可并行。

建议分期：

| 阶段 | 内容 | 依赖 |
|---|---|---|
| P0 | 集中有效私密解析入口（决议 8 基础设施） | — |
| P1 | 认证作用域合并为会话级（决议 4） | — |
| P2 | 文案三桶自适应 + 未设密码文案（决议 2、b） | — |
| P3 | 设私密反馈 + Header 锁（决议 3）、取消私密访问即信任（决议 5） | P1 |
| P4 | 私密文件夹外观停止销毁（决议 7） | P1 |
| P5 | 堵漏 + NoticeableNotification 改造 + 导出鉴权（决议 8、a、6） | P0 |
| P6 | 空标题确认（无改动）+ 全界面回归审计 | 全部 |

---

## P0 · 集中有效私密解析入口（决议 8）

- 把 `ThingsListWidgetService.protectThingIfNeeded`（list widget 现有逻辑）泛化为公共入口，建议新建 `helpers/ThingPrivacyResolver`（或类似），提供：
  - `isEffectivelyPrivate(thing, folderDAO)`：`thing.isPrivate() || folderDAO.isEffectivelyPrivate(thing.folderId)`。
  - `resolveForPresentation(thing, folderDAO)`：返回"展示安全"的记事副本（有效私密但无前缀时补前缀，等价 protectThingIfNeeded）。
- 约定：所有展示/通知界面拿到记事后先过此入口，再交给渲染/通知构建。
- `ThingsListWidgetService.protectThingIfNeeded` 改为调用公共入口，去重。

## P1 · 认证作用域合并为会话级（决议 4）

- 现状三套集合合并为一套**会话级**：`mAuthenticatedPrivateFolderIds`（`ThingManager.kt:64`）、`mAuthenticatedDrawerExpandedPrivateFolderIds`（`ThingsActivity.kt:215`）、`mAuthenticatedExpandedPrivateFolderIds`（`MoveToThingFolderDialogFragment.kt:54`）。
- 放置：建议挂在 `ThingManager`（已是跨界面单例）或 App 级，统一读写。抽屉展开、移动对话框展开、主内容打开都查同一集合。
- 生命周期：去掉 `ThingManager.trimAuthenticatedPrivateFoldersToProjection`（`:367`）的"导航即裁剪"；改为 **app 切后台清空**——用 App 级前后台判断（ProcessLifecycleOwner 或前台 Activity 计数），在真正进入后台时 `clear()`。注意区分 Activity 间跳转与真正后台。
- 配置界面 `BaseThingWidgetConfiguration` 另有自己的 `mAuthenticatedPrivateFolderIds`（`:409-423`）；它是独立 Activity、独立会话，暂保持独立（如需统一可后续评估）。

## P2 · 文案三桶自适应 + 未设密码文案（决议 2、b）

- `HomeActionWordingHelper`：把 `privateTitle(context, allPrivate)`（`:124`）升级为组成自适应版本，参考状态动词的 `selectionTarget` 三桶（记事/文件夹/混合），映射到现成串：
  - 记事 → `act_set_as_private_thing` / `act_cancel_private_thing`
  - 文件夹 → `set_thing_folder_private` / `cancel_thing_folder_private`
  - 混合 → `act_set_as_private_items` / `act_cancel_private_items`
- `ModeManager.updateMenuItemPrivate`（`:374-390`）：改用组成自适应文案（传入 things/folders 组成）。
- `ThingsActivity.configureCurrentFolderMenu`（`:836-839`）：**去掉**运行时通用改写那行，让 `act_toggle_current_folder_private` 用 XML 默认 `set_thing_folder_private` / `cancel_thing_folder_private`（按 `currentFolder.isPrivate` 取方向）。
- 文案计算收口到 `HomeActionWordingHelper`，三处不再各算。
- (b) 未设密码提示标题：`DetailActivity.warnNoPassword`（`:1789` 一带）与 `ThingsActivity.warnNoPasswordForPrivateFolder`（`:7935`）改为"请先设置密码"口吻（可新增字符串或复用合适串），不再用 `cannot_set_as_private_thing_title` 作标题。

## P3 · 设私密反馈 + Header 锁、取消私密访问即信任（决议 3、5）

- `ThingManager.updateFolderPrivate`（`:1199`）设私密分支（`isPrivate==true`）：把 `folder.id` 加入会话级已认证集合（P1 合并后的集合），使设完不立刻锁。
- `views.ActivityHeader`：在文件夹标题**前**新增锁标识视图；进入私密文件夹时显示。`ThingsActivity` 刷新 header（`requestActivityHeaderStateRefresh` 一带）时把当前文件夹 `isPrivate` 传入。复用 `ic_locked_small` 或现有锁 drawable，按 header 前景亮暗自适应着色。
- 取消私密"访问即信任"：
  - `ThingsActivity.toggleThingFolderPrivate`（`:7915`）取消分支：补"确实已在会话集合内认证"前提判断后放行（不再零门槛）。
  - `DetailActivity.tryToCancelPrivateThing`（`:1806`）：去掉第二次验证（进入详情已验证）。
  - 批量 `toggleSelectedPrivateBatch`（`:11475`）取消方向：保留验证（可能含未认证成员）。

## P4 · 私密文件夹外观停止销毁（决议 7）

- `ThingManager`：去掉三处对 `cardPresentation` 的 default 覆盖——`updateFolderAppearance`（`:1173`）、`updateFolderCardPresentation`（`:1193`）、`updateFolderPrivate` 设私密分支（`:1207`）。存储始终保留真实值。
- 显示仍走 `effectiveCardPresentation()`（已对私密返回 default，无需改）。
- 外观面板：私密文件夹打开前要求认证（会话内已认证则免）；草稿起点从 `folder.effectiveCardPresentation()`（`ThingsActivity.kt:2918`）改为真实 `folder.cardPresentation`，让用户对着真实外观调整。
- 验证：设私密→取消私密后外观能完整复原（回归用例）。

## P5 · 堵漏 + NoticeableNotification 改造 + 导出鉴权（决议 8、a、6）

- **单一小部件**：`BaseThingWidget.updateSingleThingAppWidget`（`:84-102`）渲染前把 thing 过 P0 集中入口（resolveForPresentation）再交 `AppWidgetHelper.createRemoteViewsForSingleThing`。
- **系统通知**：`SystemNotificationUtil`（`:111`、`:490` 等）改为按"有效私密"决定占位与 action 鉴权；提醒/习惯 receiver（`ReminderReceiver`、`HabitReceiver`、`AlarmHelper`/`AutoNotifyHelper`）传入有效私密而非仅 `thing.isPrivate()`。真正托盘通知仍用 `[私密记事]` 占位文本。
- **NoticeableNotificationActivity**：私密记事改为模仿首页私密卡片——标题 + 锁、隐藏内容（去掉现有 `[私密记事]` 占位文本路径，`:404-418` 一带）；每个 action（完成、开始做等）改走 `AuthenticationActivity` 鉴权。判定用 P0 集中入口。
- **DoingActivity**：审计从通知"开始做"是否绕过认证（`AUTHENTICATE_ACTION_START_DOING` 路径），按有效私密补齐。
- **导出**：`ThingExporter`（`:180`）/ 批量"导出到 SD 卡"入口，选中含会话内未认证私密记事时先认证再导出；详情分享不变。

## P6 · 空标题确认 + 全界面回归审计

- 空标题私密记事：确认不加校验、保持现状（列表卡片已是锁 + 颜色 + 标题(若有)）。
- 全界面审计：列出所有直接用 `thing.isPrivate()` 决定展示/认证的点，逐一确认已接入 P0 集中入口或确属"自身私密"场景。
