# Doing Thing Organize Execution Checklist

权威设计见 `decisions.md` 与 `docs/adr/0009-doing-thing-organize-and-selectable.md`。
勾选项在实现并编译通过后打勾；行为项（Phase 6 矩阵）留待真机验证。

## Phase 0 - Preflight

- [x] Grilling 确认核心原则、选择模型 A、保护范围、文案与边界。
- [x] 写入 `CONTEXT.md`（Doing Thing / Ongoing Thing 词条）、`decisions.md`、ADR-0009。
- [x] 摸清 Doing 标识（`App.doingThingId`）、蒙层渲染、拖拽 / 选择 / 范围动作各挡板位置。

## Phase 1 - 放开组织限制（Doing 可拖拽 + 可选中）

- [x] 长按 Doing 卡进入 Moving 模式（移除长按处 doing 挡板）。
- [x] 选择模式点按 Doing 卡可选中（移除 `onThingCardShortClick` 选择分支 doing 挡板）。
- [x] 正常模式点按 Doing 卡仍打开计时界面（仅改了选择分支，正常分支未动）。
- [x] `canCreateThingFolderWith` 移除 doing 挡板（建夹 source / target 对称放开）。
- [x] `canMoveThingIntoExistingFolderWith`（Activity 版 + 内部类版）移除 doing 挡板。
- [x] `ThingManager.canMoveThingToFolder` 放开 doing（移动到文件夹 dialog / 批量含 Doing）。
- [x] 拖拽 overlay 保留蒙层（蒙层 match_parent，随位图捕获）。

## Phase 2 - 保护：完成 / 删除 / 设为私密跳过 Doing（执行层）

- [x] `handleUpdateStates` 的 SELECTING 分支补 doing 挡板（跳过 + 不触发习惯 / 目标三选项）。
- [x] `confirmThingsOnlyStateChange`：`excludeDoingFrom` 扣除 Doing，空集则提示返回。
- [x] `confirmMixedStateChange`：union 扣除 Doing（计数 + 变更 + 子文件夹提示口径）。
- [x] `confirmFinishAllThingsInScope`（文件夹内 + 根"全部完成"）：扣除 Doing。
- [x] `changeFolderSubtreeContentState` 防御性过滤 Doing（任何 scope 路径都不改 Doing 状态）。
- [x] 空集边界：扣除 Doing 后无可操作记事时不弹确认，复用既有空集提示。

## Phase 3 - 文案资源

- [x] `HomeActionWordingHelper.stateActionWording` / `stateActionWordingForScope` 增 `excludesDoing`，
      数量后追加"不含正在做的记事"。
- [x] 新增 `scope_excludes_doing`（默认 + `values-zh-rCN`），仅含 Doing 时出现。

## Phase 4 - 蒙层渲染（bug 1 + 卡片外观）

- [x] `onBindViewHolder` 顺序本就先 `setCardAppearance`、后 `updateCardForDoing`。
- [x] 修复蒙层撑高：钉与卡片等大的固定尺寸保证铺满，但不用旧 cv.height——稳定布局直接钉，
      重新布局场景先恢复 match_parent 再 post 钉，并在最终几何上算缩放。
      （首版误删固定 px 只留 match_parent 致铺不满，已修，见 debug-updates/update-20260625162924）
- [x] 卡片外观对 Doing 放开：移除 `openThingCardAppearancePanel` 与 `canCustomizeSelectedThingCardAppearance`
      的 doing 挡板。
- [x] 编辑期间抑制该卡蒙层（`setDoingCoverSuppressedThingId`），关闭面板时恢复并重绑。

## Phase 5 - 设为私密 Toast

- [x] `private_batch_skipped` 改为专指 Doing 的"正在做的记事未设为私密"（去占位），更新调用点。

## Phase 6 - 编译与验证

- [x] `:app:assembleDebug` 通过。
- [ ] 长按 Doing 卡可拖拽重排、可拖入文件夹、可与别的记事互相建夹。
- [ ] 选中 Doing → 工具栏"移动到文件夹"可移动；移动 / 重排不打断计时。
- [ ] 点按与"全选"都能选中 Doing；正常模式点按仍打开计时界面。
- [ ] 选中含 Doing 做完成 / 删除：弹窗数量扣除 Doing、写"不含正在做的记事"，执行后 Doing 仍计时。
- [ ] 文件夹内"完成 / 删除文件夹中所有记事"、根"全部完成"同样跳过并提示 Doing。
- [ ] 选中含 Doing 做"设为私密"：跳过 Doing 并弹"正在做的记事未设为私密"。
- [ ] 卡片外观对 Doing 可编辑：编辑预览不被蒙层遮挡，确认 / 取消后蒙层正确恢复、不撑高。
- [ ] 移入私密文件夹触发既有鉴权（不回归）。

## Publish

- [x] 编译通过后发布 debug 更新到阿里云（见 `debug-updates/`）。
