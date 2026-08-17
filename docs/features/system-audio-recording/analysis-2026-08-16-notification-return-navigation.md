# 分析：录音后台化后无法从图标/通知返回录音 Dialog（2026-08-16）

OPD2515（Android 16）真机复现与机制定位。录音本身在前台服务中持续运行，问题只在导航返回。

## 实测事实

1. 录音中回桌面后任务栈为 `[ThingsActivity(root) → DetailActivity(top)]`，前台服务持续录音。
2. **点桌面图标**：`ThingsActivity` 声明为 `launchMode="singleTask"`（AndroidManifest 92 行），launcher intent 命中它时把任务提前并**清掉其上的全部 Activity**——实测任务从 `sz=2` 变 `sz=1`，`DetailActivity` 连同录音 Dialog 一起销毁（`wm_destroy_activity ... finishIfPossible`），落在记事列表。录音幸存：Dialog 的 `onDestroyView` 先清了 binder 并解绑，随后 `onDismiss` 里的 `finishSession` 因 binder 为 null 被跳过——目前是**隐性依赖执行顺序**在保护录音会话。
3. **点通知**：通知 channel 为 `IMPORTANCE_LOW`，Android 16 把它归入自动聚合节（`Aggregate_AlertingSection`，通知带 `GROUP_SUMMARY|AUTOGROUP_SUMMARY`）。通知栏里显示的那一行是**聚合组行**：SystemUI 日志显示点击原因为 `REASON_GROUP_COLLAPSE/EXPANDED_TARGET_GROUP_ROW`、`triggerClick=false`——**点击只展开/收起分组，contentIntent 根本不会被触发**。这是"点通知没反应"的直接机制。
4. contentIntent 本身存在且正确：`returnIntent`（原 `DetailActivity` intent，含记事 extras）+ `CLEAR_TOP|SINGLE_TOP` + `EXTRA_OPEN_RECORDING_DIALOG`。若能真正触发，链路应当可用：`DetailActivity` 重建/复用 → `openAudioRecordingDialogFromNotification`（`activeSession` 校验）→ 重开 Dialog → bind 服务 → `RECORDING` 快照恢复。本轮修复验证中"重开 Dialog 直接连上进行中会话"已实测可用。
5. 用不带记事 extras 的 intent 启动 `DetailActivity` 会在 `init()` 的 `initMembers` 找不到 thing 时立即 `finish`（实测 46ms 内 `wm_finish_activity app-request`）——任何返回入口都必须带完整记事 extras，`returnIntent` 满足。
6. 状态正确性不需要额外工作：录音时长由服务快照的 `recordingBaseElapsed`（`elapsedRealtime` 基准）驱动，任何新建 Dialog 都能正确显示；FableSol 是实时特征流，重开后只接收新数据、丢弃隐藏期队列（既有设计）。

## 方案

### 第一层：让通知真正可点（核心）

通知从 Android 16 聚合节里拿出来：

- channel 重要度从 `IMPORTANCE_LOW` 升为 `IMPORTANCE_DEFAULT`（录音是用户主动发起的进行中任务；配合无声 channel 与 `setOnlyAlertOnce` 避免打扰）；
- 显式 `setGroup` 并壓制自动分组，必要时采用 Android 16 的进行中任务通知样式（Live Updates / `ProgressStyle`，实现时核实 API 与降级路径）；
- 改后在 OPD2515 实测：点击必须触发 `contentIntent`（以 SystemUI 日志 `triggerClick=true` 与 activity 启动事件为准）。

### 第二层：通知落点的健壮性

- `returnIntent` 机制保持；补充一个校验：`openAudioRecordingDialogFromNotification` 在复用已有 `DetailActivity` 实例（`onNewIntent`）时，若当前 `mThing.id` 与录音会话所属记事不一致（用户此间打开了别的记事），先 finish 再按 returnIntent 重启，避免录音附件保存到错误的记事。
- 把"宿主被系统清栈"与"用户主动关闭 Dialog"显式分开：`onDismiss` 的 `finishSession` 仅在用户主动路径执行，摆脱当前对 `onDestroyView` 先行清 binder 的隐性顺序依赖。

### 第三层：点桌面图标的返回（两个选项，待定）

- **3a（推荐，改动小）**：`ThingsActivity.onNewIntent` 收到 launcher intent 且 `AudioRecordingService.activeSession` 为真时，直接用服务保存的 returnIntent 跳回 `DetailActivity` 并附 `EXTRA_OPEN_RECORDING_DIALOG`——点图标直达录音 Dialog。用户若想去列表，返回键一步即达。
- **3b（更温和，需做 UI）**：列表页顶部显示"正在录音"横幅/胶囊，点击跳回录音；不改点图标的默认落点。
- 不动 `ThingsActivity` 的 `singleTask`：它是十年前的全局导航前提，多个入口（通知、桌面部件）依赖清栈语义，改它风险面远超本功能。

## 用户裁定与落实结果（2026-08-16）

用户选定 3a + 通知先最小改动。落实结论：

- 最小改动（`IMPORTANCE_DEFAULT` 新 channel + `setGroup`）**不足**：Android 16 仍把 groupKey 强改为 `Aggregate_AlertingSection`，app 的显式分组被忽略。
- 生效手段：`setColorized(true)` + `setColor`。SDK 36 源码 `Notification.hasPromotableCharacteristics()` 要求 ongoing + 标题 + 无自定义视图 + colorized + 默认/BigText/Progress 样式；满足后系统不再聚合，实测通知独立成卡、点击 `triggerClick=true`。
- 3a、落点校验、`onDismiss` 解耦均已实现并在 OPD2515 真机端到端验收；细节见 `execution.md` 2026-08-16 节。
