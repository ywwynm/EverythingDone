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

## 2026-06-27 - 修复：大文件夹缩略图里第一个正在做预览的图标/文字不显示（findViewById 抓错蒙层）

现象：正在做的记事作为大文件夹（缩略图模式）里**第一个**预览缩略图时，蒙层显示、但火箭图标 +
"正在做"文字整体不显示；**第二个及以后的预览正常**。确定性复现，与缩放 / 卡片高度 / 是否有图片
均无关。

真正根因（决定性线索是"只有第一个预览坏"）：`card_thing` 布局的根是 `InterceptTouchCardView`，蒙层
`fl_thing_doing_cover` 是它的直接子 View；而缩略图模式文件夹卡把若干**用同一个 card_thing inflate、
带相同 R.id.fl_thing_doing_cover** 的预览卡加进自己的内容区（`ll_thing_content` 内，位于自身蒙层之
前）。`InterceptTouchCardView.onMeasure` 里用 `findViewById(R.id.fl_thing_doing_cover)` 缓存蒙层——
`findViewById` 深度优先遍历整棵子树，会先命中**第一个预览卡的蒙层**而非文件夹卡自己的。于是当第一个
预览恰为正在做的记事（蒙层 VISIBLE），文件夹卡的 onMeasure 把**这个预览的蒙层按整张文件夹卡尺寸**
强制重测，预览里 `layout_gravity=center` 的图标 + 文字被摆到整张文件夹卡的中心、跑到小预览卡之外被
裁掉 → 蒙层在、图文不可见。第二个预览的蒙层 `findViewById` 永远到不了（首个匹配即停），故正常。

修复（`InterceptTouchCardView`）：蒙层改为**只在直接子节点里找**（新增 `findOwnDoingCover()`），不再
用会深度遍历的 `findViewById`。这样文件夹卡只处理自己的（GONE 的）蒙层、onMeasure 提前返回、不再
碰任何预览；每个预览卡也只处理自己的蒙层。`holder.flDoing` 不受影响——它在 holder 创建时（预览尚未
加入）就解析过，拿到的本就是自身的蒙层。

误判记录（三连错，留档以免重蹈）：① 先怪"图片异步加载改高度→scale 失准"；② 再怪"过渡态极小高度
→scale≈0 取整不可见"；③ 又怪"full-span 稳定卡上 tvDoing 重布局落不了地"。前两条被纯文字 / 高图片卡
复现推翻，第三条被"只有第一个预览坏、第二个正常"推翻——真正原因与缩放 / 时序 / span 都无关，是
findViewById 跨预览子卡抓到了同 id 的蒙层。基于②③加的 `applyDoingCoverScale` 强制重测、
`OnLayoutChangeListener` 自愈、图标 `coerceAtLeast(1)` 均已回退（无效且非根因）。

`:app:assembleDebug` 通过。发布到阿里云 debug 通道，更新码 `202606271029`（取代含错误修复的
`202606271018`），日志 `docs/features/thing-folders/debug-updates/update-20260627182927.md`，待平板真机验证。

## 2026-06-27 - 修复：DoingActivity 里文件夹内置顶记事的标识颜色

- 现象：置顶记事在 DoingActivity 右上角的置顶标识用了黄色（`ic_sticky` 原图色），而非首页那样——
  根目录置顶用 accent+accent2 渐变、文件夹内置顶用所属父文件夹的颜色。
- 诊断：置顶标识着色走基类共享私有方法 `BaseThingsAdapter.tintThingStickyOngoingIcon`：根目录置顶
  （`folderId==null`）用 `App.defaultAccentBackground`（全局 accent 渐变）——**首页与 DoingActivity 共用同
  一段代码、本就一致**；文件夹内置顶用 `getStickyThingParentFolderBackground(thing)` 取父文件夹背景，
  但该方法基类默认返回 null，只有 `ThingsAdapter` 覆盖了它（`mThingManager.getFolderById(folderId)
  .getBackground()`），DoingActivity 的匿名 `BaseThingsAdapter` 子类**没覆盖** → 落到 `tintCardIcon`
  （深色记事不染色 → 显示 `ic_sticky` 黄色原图）。
- 改法（`DoingActivity` 匿名适配器）：覆盖 `getStickyThingParentFolderBackground`，用
  `ThingFolderDAO.getInstance(mApp).getFolderById(folderId).getBackground()` 返回父文件夹背景（与
  `ThingManager.getFolderById` 等价，后者也只是委托给 `mFolderDao`）。根目录情况无需改（已共用渐变）。
- Verification：`:app:assembleDebug` 通过。未使用 adb，待平板真机验证（重点验证文件夹内置顶；根目录
  置顶本就是渐变）。
- 发布：通过 `:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/doing-thing-organize/debug-updates/update-20260627192117.md"` 发布到阿里云 debug 通道，更新码 `202606271121`；远端 `latest.json` 指向 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606271121.apk`，SHA-256 为 `e2ad3351cf88fd3dff1af28f5ae85db169255205ef583a5de78c4f16b1ae20a4`。尚未提交，待真机确认后提交。
