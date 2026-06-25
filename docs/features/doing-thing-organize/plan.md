# Doing Thing Organize 实现方案

权威设计见 `decisions.md` 与 `docs/adr/0009-doing-thing-organize-and-selectable.md`；
逐项落地与验证见 `execution.md`。本文记录实现策略与改动地图。

## 目标与范围

让 Doing Thing（`App.doingThingId` 标识、覆盖"正在做"蒙层的唯一计时记事）在首页能像
普通正在进行记事一样被组织：拖拽重排、拖拽建夹 / 拖入文件夹、选择模式"移动到文件夹"、
置顶；同时保护它不被完成 / 删除 / 设为私密。顺带修复三个既有问题：

1. 选择模式下蒙层有时把卡片撑高（渲染）。
2. "全选"能选中 Doing 而点按不能（口径不一致）。
3. 选中含 Doing 的文件夹做完成 / 删除会真的改 Doing 状态却不停计时（且弹窗无提示）。

不在范围：左右滑动手势（维持"正在做这件事"提示）；DoingActivity / 通知 / 详情页的计时逻辑。

## 关键架构决策

1. **"排除 Doing"从 Activity 触摸层下沉到 `ThingManager` 选择 / 范围 / 状态变更层。**
   原实现把 doing 判断散落在若干触摸入口，导致全选、文件夹递归、选择模式状态变更各自
   漏判（bug 2 / bug 3 同源）。新模型：组织类挡板**全部移除**（Doing 可拖可选可移），
   破坏性动作的排除**统一在执行 / 计数层**做，确保单选、混选、文件夹递归、根全量各路径
   一致。
2. **蒙层仍钉成与卡片等大的固定尺寸（保证铺满），但绝不用复用时的旧 `cv.height` 去钉。**
   此 RecyclerView 下 `match_parent` 常因 EXACTLY 测量规格退化成内容大小、铺不满卡片，
   故必须保留固定 px（首版误把固定 px 整个删掉、只留 match_parent，导致蒙层铺不满——已修）。
   "撑高"的根因是用复用时的旧 `cv.height` 设固定高度：修法是卡片已稳定布局时直接钉；待
   重新布局的场景先把蒙层恢复成 `match_parent`（避免旧固定高度撑高卡片），布局完成后再钉成
   与卡片等大并按最终高度算缩放。
3. **卡片外观对 Doing 放开，编辑期间临时抑制蒙层。** 外观面板在真实列表卡上做实时预览，
   蒙层会遮挡；用一个 adapter 级抑制 id，在打开面板时设、关闭面板时（确认 / 取消共用的
   `clearThingCardAppearanceDraft` 单一汇合点）消费并重绑恢复。

## 分阶段方案与改动地图

### Phase 1 - 放开组织挡板

移除以下针对 Doing 的判断：

- `ThingsActivity` 长按处（进入 Moving 模式的条件）。
- `ThingsActivity.onThingCardShortClick` 选择分支（点选）；正常分支不动，保留点按打开计时界面。
- `ThingsActivity.canCreateThingFolderWith`（建夹 source/target 共用，故对称放开）。
- `ThingsActivity.canMoveThingIntoExistingFolderWith`（Activity 版 + 内部类版）。
- `ThingManager.canMoveThingToFolder`（移动到文件夹 dialog / 批量）。
- `ThingsActivity.openThingCardAppearancePanel` 与 `ModeManager.canCustomizeSelectedThingCardAppearance`
  的 doing 挡板（否则外观面板对 Doing 打不开 / 菜单不显示）。

### Phase 2 - 保护：完成 / 删除 / 设为私密跳过 Doing

- 新增 `ThingsActivity.excludeDoingFrom(things): Pair<List, Boolean>`——返回（可操作集合,
  原集合是否含 Doing）。
- `confirmThingsOnlyStateChange` / `confirmMixedStateChange` / `confirmFinishAllThingsInScope`
  用它扣除 Doing，得到真实数量与 `excludesDoing` 标记；子文件夹提示、习惯 / 目标三选项判断
  都改用扣除后的集合；扣除后为空则复用既有空集提示、不弹确认。
- `ThingsActivity.handleUpdateStates` 的 SELECTING 分支补 `&& thing.id != doingId`（变更与
  习惯 / 目标统计都跳过 Doing）。非 SELECTING 分支本就有挡板。
- `ThingManager.changeFolderSubtreeContentState` 顶部按 `effectiveThings` 防御性过滤 Doing，
  保证任何 scope 路径都不会改到 Doing 状态（与调用点扣除互为双保险）。
- 设为私密本就在 `applySelectedPrivateBatch` 跳过 Doing；只改提示文案（见 Phase 5）。

### Phase 3 - 文案

- `HomeActionWordingHelper.stateActionWording` 增 `excludesDoing` 入参，在数量子句后追加
  `scope_excludes_doing`；`ThingsActivity.stateActionWordingForScope` 透传。
- 新增字符串 `scope_excludes_doing`（默认 + `values-zh-rCN`："（不含正在做的记事）"）。

### Phase 4 - 蒙层渲染

- `BaseThingsAdapter.updateCardForDoing`：卡片已稳定布局（`height>0 且无挂起布局`）时直接
  `applyDoingCover`（钉与卡片等大的固定尺寸 + 缩放）；否则先 `resetDoingCoverToMatchParent`
  再 `cv.post { applyDoingCover }`，避免复用残留的旧固定高度把卡片撑高。
- `ThingsActivity` 右滑预览路径仍按当前高度钉固定尺寸（卡片此时已布局，尺寸正确）。
- adapter 加 `doingCoverSuppressedThingId` + `setDoingCoverSuppressedThingId` /
  `consumeDoingCoverSuppression`；`updateCardForDoing` 命中抑制 id 时不画蒙层。
- `ThingsAdapterWrapper` 加两个转发方法（`mAdapter` 是 wrapper，需转发到真实 adapter）。
- `ThingsActivity` 打开外观面板（通过位置校验后）设抑制 id；`clearThingCardAppearanceDraft`
  消费抑制并对该卡 `notifyItemChanged` 恢复蒙层。

### Phase 5 - 设为私密 Toast

- `private_batch_skipped` 改为"正在做的记事未设为私密"（去掉数量占位，空标题已不再是跳过
  原因），更新调用点去掉实参。

## 边界与风险

- Doing 天然处于正在进行，只会出现在"正在进行 → 完成 / 删除"路径；恢复 / 永久删除作用于
  已完成 / 回收站记事，本就不含它，无需处理。
- 移入私密文件夹仍走既有鉴权路径，Doing 不特殊。
- 移动 / 重排只改 `folderId` / `location`，`DoingService` 按 id 在内存持有 Thing、通知 id 不变，
  计时不受影响。
- 风险点（需真机验证）：蒙层在选择 / 移动 1.11x 缩放与外观变更后的最终几何下不被撑高；
  外观编辑预览不被蒙层遮挡且关闭后正确恢复。

## 验证

见 `execution.md` Phase 6 矩阵。编译 `:app:assembleDebug` 已通过；行为项在阿里云 debug
渠道真机自测。
