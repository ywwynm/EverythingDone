# Doing Thing Organize Decisions

## 2026-06-25 - 核心原则：Doing 记事在组织维度与普通正在进行记事完全一致

Doing Thing（`App.doingThingId` 标识、卡片覆盖"正在做"蒙层的唯一计时记事）在组织维度
——拖拽重排、拖拽建夹 / 拖入已有文件夹、移动到文件夹 dialog、置顶——与普通正在进行记事
完全一致。计时状态与位置 / 文件夹归属解耦（移动只改 `folderId` + `location`，`DoingService`
按 id 在内存持有 Thing，通知 id 不变），因此移动 / 重排不打断计时。仅保留它的计时蒙层视觉。

这取消 thing-folders 当初"Prevent folder creation on … current Doing Thing"的保守限制，
以及长按 Doing 卡直接 `backNormalMode`（连 Moving 模式都进不去）的封锁。

## 2026-06-25 - 选择模型 A：Doing 记事完全可选中

Doing 记事在选择模式下完全可被选中：点按选中、"全选"也包含它。正常模式下点按 Doing 卡
仍打开 DoingActivity（计时界面），与选择模式互不影响（`onThingCardShortClick` 的正常分支
与选择分支分开）。

被否决：B（仅手动可选、全选跳过 + Toast）、C（完全不可选、移动需另造专属入口）。选 A
是因为它最简单、直接复用现有选择 → 工具栏 → 移动流程，且让保护逻辑统一落在"动作执行层
跳过"，而不是在"选择层"做不一致的特判。

## 2026-06-25 - 保护：完成 / 删除 / 设为私密跳过 Doing，逻辑下沉到 ThingManager

完成、删除、设为私密一律不作用于 Doing 记事，无论它是被直接选中、还是在被选中文件夹的
子树里被递归命中。排除逻辑必须从散落的 Activity 触摸层下沉到 ThingManager 的选择 / 范围层
（动作执行层过滤、scope 收集器过滤、`handleUpdateStates` 的 SELECTING 分支补挡板），
从而同时根治：

- bug 2：`isSelectableThing`（不排除 doing）使"全选"选得中、而点按走 `onThingCardShortClick`
  的挡板选不中，口径不一致。
- bug 3：选中含 doing 的文件夹做完成 / 删除时，`getDescendantThingsForProjection` 不排除
  doing → 弹窗计数含它、且 `changeFolderSubtreeContentState` 真的把它改成已完成 / 删除；
  而该方法只停 Ongoing 通知（`KEY_ONGOING_THING_ID`）、不停 Doing 计时，导致计时器指向
  一个已完成 / 删除的记事。

完成 / 删除的确认弹窗：数量按"实际会发生"的口径（扣除 doing），并写明"不含正在做的记事"，
不再用 Toast。

## 2026-06-25 - "设为私密"跳过 Doing 的反馈：保留极简 Toast（仅此动作）

设为私密本身靠私密密码 / 鉴权把关，按既定决策不弹语义确认框，因此没有"提醒 Dialog"可写。
当 Doing 记事在选中集里被设为私密跳过时，保留一个极简 Toast，仅"设为私密"这一个动作使用；
把现有 `private_batch_skipped` 文案从"X 项未能设为私密（标题为空或正在计时）"改为专指
Doing 的"正在做的记事未设为私密"（空标题已不再是跳过原因）。只在选中集真含 Doing 时出现。
被否决：静默零反馈（会丢失"为什么这张没变"的解释）。

被排除的只有这三类；置顶、移动到文件夹、导出、重排、拖入文件夹对 Doing 记事照常可用。
永久删除在常规下不涉及 Doing（计时中的记事是正在进行、进不了回收站），仅作防御性排除。

## 2026-06-25 - 拖拽侧：建夹对称放开 + 蒙层在各处的显示

- **建夹目标对称**：允许把别的记事拖到 Doing 卡上合并成新文件夹。建夹用同一个
  `canCreateThingFolderWith` 同时校验 source 和 target，去掉那一个 doing 挡板即两个方向
  对称放开；A 在不在文件夹里都不影响计时。
- **拖拽 overlay**：被拖动的 Doing 卡保留蒙层（其真实样子，便于辨认正在移动的卡片）。
- **文件夹缩略图预览**：Doing 记事移入文件夹后，缩略图是否显示蒙层跟随现有缩略图渲染路径，
  v1 不为此特判。
- **左右滑动**：Doing 卡维持现状（弹"正在做这件事"提示），不纳入本次组织改动。
- **"移动到文件夹"入口**：不新增专属入口，走"选中 Doing → 工具栏'移动到文件夹'"这条与
  普通记事一致的既有路径。

## 2026-06-25 - 卡片外观自定义对 Doing 可用，但必须"先外观、后蒙层"

Doing 记事可选中后，单选"卡片外观"面板对它可用（呈现偏好，不影响计时，不在受保护三项内）。
实现约束：绑定 / 应用顺序必须是"先应用卡片外观（决定最终几何）→ 再加正在做蒙层"。蒙层尺寸
与图标/文字缩放完全依赖卡片最终高度/宽度（`applyDoingCover` 用 `cv.width/height`，
`applyDoingCoverScale` 按 `cv.height` 算 18% 留白与等比缩放），所以任何改变卡片几何的操作
（外观自定义、span 模式、媒体位置、选择/移动 1.11x 缩放）之后都必须在最终几何上重算蒙层，
不能用旧尺寸。外观编辑预览阶段不应让蒙层遮挡正在编辑的外观。此约束与 bug 1 同源。

## 2026-06-25 - bug 1：选择模式下 Doing 卡高度被撑高（渲染，非产品决策）

`applyDoingCover` 用复用时的旧 `cv.height` 给蒙层设固定高度，在选择 / 复用 / 内容变更下用了
旧高度，把 wrap_content 卡片撑高。

解决（2026-06-25）：蒙层仍钉成与卡片等大的固定尺寸（此 RecyclerView 下纯 match_parent 会因
EXACTLY 规格退化成内容大小、铺不满卡片，故不能只靠 match_parent——首版误删固定 px 已回退）；
但卡片已稳定布局时才直接钉，重新布局的场景先把蒙层恢复成 match_parent 再在 `cv.post` 里钉，
避免用旧固定高度撑高。

## 2026-06-25（修订二）- 蒙层铺满改由卡片 onMeasure 保证；外观预览全程保留蒙层；选择态淡化与菜单收口

真机暴露上一版固定 px 方案仍有问题：拖拽 overlay 位图与退出选择的重绑会闪一下"蒙层缩在左上角"。
最终方案：在 `InterceptTouchCardView.onMeasure` 中、于卡片尺寸经 `super` 确定后，把可见的蒙层按
卡片最终内容区强制重测铺满。蒙层保持 match_parent（测量时退化成小尺寸、故不撑高卡片），`super`
之后的强制重测使其铺满且图文居中，且在同一布局帧内完成（无闪烁、overlay 位图正确）。bind 与右滑
预览不再手动钉固定 px。**此条取代前面所有"用固定 px 钉蒙层"的描述。**

外观编辑：**撤销"编辑期隐藏蒙层"的做法**（取代前面"编辑预览不应被蒙层遮挡"的表述）。卡片外观是
实时预览、点确定才应用，预览过程中必须一直保留"正在做"蒙层；蒙层随外观变更的几何由 onMeasure
自动跟随。同时移除 `openThingCardAppearancePanel` 抑制蒙层的相关代码（adapter 抑制 id、wrapper 转发、
clearDraft 重绑）。

选择态淡化：未选中的正在做记事，其蒙层（含图标 / 文字）随其它内容按未选中透明度淡化
（`applyUnselectedContentAlpha` 增加 `flDoing.alpha`），使选中 / 未选中可区分。

菜单：选中集仅含一件正在做的记事时，隐藏完成 / 删除 / 设为私密（`ModeManager.isOnlyDoingThingSelected`
用于状态动词与私密项可见性）；移动到文件夹 / 置顶 / 卡片外观 / 导出仍可用。

文案：数量后的"不含正在做的记事"与"包含所有子文件夹中的记事"同时出现时合并进同一对括号
（新增 `scope_includes_subfolders_and_excludes_doing` 资源），避免连续两个括号。
