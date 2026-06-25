# Doing Thing Organize Sessions

## 2026-06-25 - grilling + 实现 + 发布

`grill-with-docs` 走完设计：核心原则（组织维度与普通正在进行记事一致）、选择模型 A
（完全可选中）、保护范围（完成 / 删除 / 设为私密在执行层跳过）、文案（确认弹窗写"不含
正在做的记事"、设为私密保留极简 Toast）、拖拽侧（建夹对称放开、蒙层各处显示）、卡片外观
（对 Doing 放开 + "先外观后蒙层"）。产出 `CONTEXT.md` 词条、ADR-0009、feature 文档。

实现要点：
- Phase 1 放开组织挡板：长按、点选、`canCreateThingFolderWith`、两处
  `canMoveThingIntoExistingFolderWith`、`ThingManager.canMoveThingToFolder`。
- Phase 2/3 保护下沉到 `ThingManager` 与确认弹窗构建处：新增 `excludeDoingFrom`，
  `confirmThingsOnlyStateChange` / `confirmMixedStateChange` / `confirmFinishAllThingsInScope`
  扣除 Doing 并传 `excludesDoing`；`handleUpdateStates` 的 SELECTING 分支补挡板；
  `changeFolderSubtreeContentState` 防御性过滤；`HomeActionWordingHelper` 加
  `scope_excludes_doing` 文案。
- Phase 4 蒙层：删除 bind 与右滑预览两处固定 px，蒙层保持 match_parent、缩放按最终几何；
  移除 `openThingCardAppearancePanel` 与 `canCustomizeSelectedThingCardAppearance` 的 doing 挡板；
  编辑期间用 `setDoingCoverSuppressedThingId` 抑制蒙层、关闭时 `consumeDoingCoverSuppression` 重绑恢复。
- Phase 5 `private_batch_skipped` 改为"正在做的记事未设为私密"。

`:app:assembleDebug` 通过；发布 debug 更新到阿里云（updateCode 202606250754，
日志 `debug-updates/update-20260625155356.md`）。行为项待真机验证。
