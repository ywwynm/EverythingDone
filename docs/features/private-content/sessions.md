# 私密记事/文件夹 Sessions

## 2026-06-29 - 提交全面复审 + 评审 8 问题一并修复

先对上一提交（7e9055de）做只读全面复审（首页长按各操作、文件夹各菜单项、通知/小部件、DAO 揭示层），确认主干正确、通知/小部件泄露面已堵，并报告：记事取消私密未清会话认证集、单选私密切换死代码 + 取消私密零门槛、状态变更未鉴权、主列表"当前文件夹"口径依赖投影不变量等。随后用户汇总 8 个问题，一并修复，`:app:assembleDebug` 编译通过：

1. **拖拽取消鉴权列表未复位**：`authenticatePrivateMoveIfNeeded` 的 `onCancel` 原为空体，拖拽到私密文件夹取消鉴权时 `commitFolderDrop` 的 `finishIfReady` 因 `committed` 恒为 null 永不复位，列表停在移动模式遗留的浅色态。加 `onCancelled` 回调，拖拽两个调用方（`commitMoveThingIntoFolderDrop`/`commitMoveFolderIntoFolderDrop`）传 `{ onCommitted(false) }`。
2. **揭示态大文件夹拖入动画错**：`setFolderDropTargetHighlighted`（10260）用 `effectiveCardPresentation()`（私密强制小文件夹摘要）判大/小文件夹。新增 `ThingsAdapter.isFolderShownAsThumbnails`（揭示感知，走 `presentationFor`）+ `ThingsAdapterWrapper` 委托，改用之。
3. **移动鉴权后未标记文件夹已认证**：`authenticatePrivateMoveIfNeeded` 走底层 `AuthenticationHelper.authenticate`、`onAuthenticated` 不 mark。加 `foldersToAuthenticate` 参数，鉴权成功 `markFolderPrivacyAuthenticated` 本次源/目标；5 个调用方全部传入。
4. **回前台仍显示解锁态**：`ThingManager` 加 `mPrivacyAuthGeneration`（真正清认证时 +1）+ `getPrivacyAuthGeneration`；`ThingsActivity.onResume` 比较代次，后台清过认证则 `notifyDataSetChanged` + `updateDrawerFolderItems` 恢复锁态。移动对话框实时刷新留 followup。
5. **全屏通知动作双重鉴权**：`runActionWithPrivacyAuth` 通过后发广播给 receiver、receiver 再鉴权。`Def` 加 `KEY_ALREADY_AUTHENTICATED`，`NoticeableNotificationActivity` 三处发广播带标志，`ReminderNotificationActionReceiver`/`HabitNotificationActionReceiver` 见标志跳过自身有效私密鉴权（widget 广播不带、仍各自鉴权）。
6. **记事取消私密未清认证集**：`ThingManager` 加 `clearThingPrivacyAuthenticated`；批量取消私密、详情页 `cancelPrivateThingUiAndAddAction`、`deleteThingsForever` 三处清，与文件夹取消私密对称。
7. **死代码 + 取消私密零门槛**：删 `toggleSelectedPrivateEntry`/`toggleSelectedThingPrivate`/`persistSelectedThingPrivateChange`（均无调用）；`toggleThingFolderPrivate` 取消分支补 `shouldProtectFolderForAccess` 前提——未认证先 `authenticateThingFolder` 再取消（落实决策 5 的"确实已认证前提"）。
8. **状态变更未鉴权**：新增 `needsSelectedStateChangePrivacyAuthentication` + `authenticateSelectedStateChangePrivacyIfNeeded`（含未认证有效私密项才验证、通过后 mark）。**按用户要求置于确认框点"确定"之后、真正执行前**，接入 `confirmThingsOnlyStateChange`/`confirmMixedStateChange`/`confirmDeleteSelectedStructural`/`showDeleteThingFolderForeverDialog` 四个 onConfirm。文件夹 scope“全部完成/删除”（根目录）未接入，留 followup。

随后按用户要求补做第 4 项的延伸——“移动到文件夹”对话框开着时切后台、回前台未恢复锁态：`MoveToThingFolderDialogFragment.onResume` 比较认证代次，清过认证则收起未再认证的私密文件夹（`collapseUnauthenticatedPrivateFolders`）并 `rebuildRows`，图标恢复闭锁。`:app:assembleDebug` 通过，按用户要求发布阿里云（**更新码 202606291338**）。文件夹 scope“全部完成/删除”鉴权仍留 followup。

**问题 1 二次修复（根因，发布码 202606291338 后真机仍复现）**：上批 `onCancelled → onCommitted(false)` 只覆盖图案锁路径；启用指纹时 `FingerprintHelper.authenticateWithBiometricPrompt` 的 `onAuthenticationError` 在 `ERROR_NEGATIVE_BUTTON`/`ERROR_USER_CANCELED`（用户取消）时**既不回调 onAuthenticated 也不回调 onCancel**，拖拽 `onCancelled` 收不到通知、`commitFolderDrop` 的 `finishIfReady` 因 `committed` 恒 null 永不复位，列表卡在移动遗留的浅色态。修：① BiometricPrompt 取消分支补 `callback?.onCancel()`，与图案锁取消（`PatternLockDialogFragment` 销毁时调 onCancel）对齐——这是全应用所有鉴权点取消回调的根因修复，其余调用点 onCancel 多为空实现、不受影响；② `finishIfReady` 改为仅成功 commit 才等落位动画，取消/失败立即复位（防动画被 onPause 打断或取消早于动画回调而卡住）。`:app:assembleDebug` 通过，重新发布阿里云（**更新码 202606291349**）。

## 2026-06-29 - 私密显示/交互改动代码评审

复核了本轮私密文件夹/私密记事显示与交互改动，覆盖 `ThingPrivacyResolver`、主列表/缩略图揭示、会话级认证、系统通知、NoticeableNotification、小部件与小部件配置入口。`:app:assembleDebug` 编译通过。

结论：主干设计方向基本符合既定决策（有效私密集中、访问即信任、app 外入口隔离、私密文件夹外观保留真实存储值），但仍发现若干遗漏接入点，已同步记录到 `followups.md`：系统通知媒体大图仍可能泄露有效私密附件；单一小部件配置完成时会绕过安全副本；嵌套文件夹缩略图的 A2 揭示未完全传透；Doing 前台通知仍未按系统通知隐私占位；NoticeableNotification 在记事已删除时有空对象崩溃风险。

## 2026-06-29 - 私密交互/显示一致性梳理与优化方案 grilling

起因：用户在文件夹内点 overflow"设为私密"，发现这是把当前文件夹设为私密（容器模型），而非把里面的内容逐个标记私密，由此触发对整套私密交互/显示一致性的复盘。

全量梳理了"私密"在各界面的交互与显示：首页列表、抽屉、移动到文件夹对话框、卡片外观面板、记事详情、DoingActivity、系统通知、NoticeableNotificationActivity、单一记事小部件与列表小部件及各自配置、分享/导出。

通过 grilling 逐条定案，见 `decisions.md` 共 8 条（2026-06-28 ~ 06-29）：

1. 文件夹私密采用容器保护模型，不级联标记后代。
2. "设为私密"动词文案按选中组成三桶自适应，文案逻辑收口到 `HomeActionWordingHelper`。
3. 设私密文件夹后本会话自动认证、内容不立刻锁；私密标识放 Activity Header 文件夹名前。
4. 三套认证集合合并为一套会话级作用域，切后台清空；app 外入口仍各自认证。
5. 取消私密统一为"访问即信任"：会话内已认证则免二次验证，批量保留验证。
6. 隐私边界契约（标题可见、内容保密）；接受空标题私密记事（锁+颜色辨识）；导出/分享按访问即信任鉴权。
7. 私密文件夹卡片外观停止销毁存储值（改显示层遮蔽），编辑器经认证后展示真实外观。
8. 有效私密强制集中化，堵住单一小部件与系统通知的明文泄露。

数据模型决策（前缀编码 vs 真实列 + 有效私密集中强制）记入 `docs/adr/0011`；前缀→列重构与界面审计入 `followups.md`。

## 2026-06-29 - 私密优化实现并发布（P0–P5）

按 `plan.md` 实现 P0–P5 并发布阿里云 debug 版（更新码 202606290137），每期 `:app:assembleDebug` 编译门通过：

- P0：新建 `ThingPrivacyResolver` 统一"展示用有效私密"解析；列表小部件改调它。
- P2：`HomeActionWordingHelper.privateTitle` 三桶组成自适应，收口 `ModeManager`/`configureCurrentFolderMenu`；新增 `set_password_first_title`，未设密码提示改口吻。
- P3：`updateFolderPrivate` 设私密即认证；`ActivityHeader` 文件夹名前加锁；详情页取消私密去二次验证。
- P4：去掉 `ThingManager` 三处 `cardPresentation` default 覆盖（修数据损坏），外观面板草稿用真实值。
- P5：单一小部件、系统通知（+提醒/习惯 receiver）按有效私密堵漏；`NoticeableNotificationActivity` 改锁+标题、动作全鉴权；导出到 SD 卡含私密项先鉴权。

P1（认证作用域合并 + 切后台清空）与 P4 实时预览旁路当时推迟。

## 2026-06-29 - 补做 P1 与 P4 实时预览

应要求补做剩余两项并重新发布：

- P1：三套已认证集合合并为一套会话级 `ThingManager.mAuthenticatedPrivateFolderIds`；`authenticateThingFolder` 成统一写入点，删 `ThingsActivity`/`MoveToThingFolderDialogFragment` 两个本地集；`trimAuthenticatedPrivateFoldersToProjection` 改空体；`App.onCreate` 注册 `ActivityLifecycleCallbacks`，真正切后台（`isChangingConfigurations` 排除旋转）时清空。效果：同一私密文件夹本会话跨抽屉/移动框/主列表只认证一次，切后台重新设防。
- P4 实时预览：`ThingsAdapter.presentationFor(folder)` 旁路 + `ThingsAdapterWrapper` 委托，编辑期间对正在编辑的文件夹展示真实外观；开/确认/取消/清理处设清 reveal，避免私密预览残留；并修复 `confirmFolderCardAppearancePanel` 残留的私密 default 覆盖（否则确认时仍丢弃私密文件夹外观编辑）。

`:app:assembleDebug` 编译通过，重新发布阿里云。

**修复（同日）**：上述 P4 实时预览首发后真机反馈"看不到真实外观"。根因是只改了外观模式的 `presentationFor`，但真正隐藏私密文件夹预览内容的是 `ThingsAdapter.bindFolderCardContent` 里的 `hiddenPrivate = entry.effectivePrivate && !shouldShowFolderPrivateContent()` 开关。修法：`hiddenPrivate` 对 `folder.id == mAppearanceRevealFolderId` 放行，正在编辑的文件夹展示真实预览（缩略图按需经 `getFolderThumbnailPreviewEntries` 取，不遮蔽）。重新发布。

**再修（同日）**：真实外观能显示后，反馈"卡片宽度 / 大小模式调不动"。`bindFolderCardAppearancePanel` 里还有三处旧的私密 gating：span/大小/布局控件对私密文件夹 `View.GONE`、选择项绑定 `if (!privateFolder)` 跳过、以及 `updateFolderCardAppearanceDraft` 开头 `if (folder.isPrivate) return`（点了不生效的根因）。全部移除——私密文件夹（经认证后）现在与普通文件夹一样可调外观；显示遮蔽仍由 effectiveCardPresentation/hiddenPrivate 在展示层负责。重新发布。

## 2026-06-29 - 私密交互两处真机反馈修复

- **右滑鉴权**：已打开（已认证）的私密文件夹内右滑记事仍要求验证。根因 `needsThingSwipePrivacyAuthentication` 漏了 `&& !isCurrentFolderPrivacyAuthenticated()` 豁免，与列表显示口径不一致。补上，右滑鉴权口径对齐显示。
- **大文件夹外观预览不完整**（定为"完整揭示=取消私密后的样子"，方案 A）：编辑私密文件夹的大文件夹/缩略图外观时看不到记事、子文件夹全上锁。根因有两层：① 预览数量 `maxCount` 用 `effectiveCardPresentation()`（私密→摘要）截断了记事；② 子文件夹 `effectivePrivate = isEffectivelyPrivate(child)` 含被揭示的父，故全上锁。修法：在预览取数链路（`getFolderThumbnailPreviewEntries → getThumbnailEntriesForTypeFilterPreview → ...Projection → ...FolderEntries...`）加 `revealRoot` 标志——真实外观取数量、子文件夹 `effectivePrivate` 按 `childFolder.isPrivate || isEffectivelyPrivate(folder.parentFolderId)`（把被揭示的父当非私密）。masonry 渲染不变，复用 `bindFolderCardContent` 按 entry 数据走。

关键认知沉淀：私密文件夹卡片**正常浏览永远是上锁/无预览**，"会显示内容的外观"只在外观编辑揭示时或取消私密后才生效；因此其外观编辑预览统一按"取消私密后的样子"呈现。各层遮蔽点：存储(ThingManager 已去)、确认(confirmFolderCardAppearancePanel 已去)、显示内容(hiddenPrivate)、显示模式(effectiveCardPresentation/presentationFor)、控件(bindFolderCardAppearancePanel 三处已去)、预览取数(DAO maxCount + 子文件夹 effectivePrivate，revealRoot 处理)。

**三修（同日，根因补全，发布码 202606290357）**：上批 revealRoot 修复后真机仍反馈"大文件夹预览只见子文件夹、不见记事"。漏掉了一处**数据层私密闸**：`ThingFolderDAO.getDirectThumbnailThings`(929) 取直接子记事时有 `if (isEffectivelyPrivate(thing.folderId)) continue`，揭示私密文件夹时 `thing.folderId` 即该私密文件夹自身 → 有效私密为真 → **所有直接记事在 SQL 游标层被整条丢弃**。子文件夹走 folder-entry 路径(`getThumbnailFolderEntriesForTypeFilterProjection`)不经这道闸，故能揭示；记事却被拦掉，造成"只见子文件夹"。修法：`getDirectThumbnailThings` 加 `revealRoot` 参数，改 `if (!revealRoot && isEffectivelyPrivate(thing.folderId)) continue`，并在 `getThumbnailEntriesForTypeFilterProjection`(719) 调用处把 revealRoot 透传；非 typeFilter 路径(`getThumbnailEntriesForProjection`:644)保持默认 false，行为不变。至此预览取数层补全：**记事**(getDirectThumbnailThings 闸)与**子文件夹**(effectivePrivate)在 revealRoot 下都按"取消私密后"呈现。教训：私密遮蔽是分层的，folder-entry 与 direct-thing 是两条独立取数路径，改一条不等于改全。

## 2026-06-29 - "层层设防"统一排查（isPrivate vs 有效私密 / 已认证豁免）

全量 grep 排查 `thing.isPrivate()` 与 `isCurrentFolderEffectivelyPrivate` 的所有判定点，找散落的"只看前缀"和"缺已认证豁免"。修复：

- **`AuthenticationActivity.tryToAuthenticate`**（131）只看 `thing.isPrivate()` → 改 `ThingPrivacyResolver.isEffectivelyPrivate`。这是小部件/通知点击的验证总闸；不修则 P5 把有效私密记事正确路由到此闸后，闸自己又放行，等于 P5 通知/小部件堵漏被架空。**最关键。**
- **`ReminderNotificationActionReceiver` / `HabitNotificationActionReceiver`**：动作路由的 `thing.isPrivate()` 全部 → 有效私密。
- **记事移动鉴权**（`needsSelectedThingsMovePrivacyAuthentication` / `needsThingMovePrivacyAuthentication`）缺 `!isFolderPrivacyAuthenticated` 豁免，与 `moveFolderToFolder`(8094) 不一致 → 对齐：源/目标为"未认证私密文件夹"才验证。

已确认一致、无需改：列表显示 `isThingEffectivelyPrivate`(ThingsAdapter:120 / ThingsActivity:9520)、`shouldProtectFolderForAccess`(7915)、`moveFolderToFolder`(8094)、抽屉入口鉴权——均已带 `!...PrivacyAuthenticated` 豁免。小部件渲染的 `AppWidgetHelper.isPrivate()` 系列接收的是已 `resolveForPresentation` 的 thing，OK。

> 订正（见下方"外观面板入口鉴权缺已认证豁免"）：本次审计当时把"外观面板入口鉴权"也列为"已带豁免"，**是错的**——`openSelectedCardAppearancePanel` 用的是 `entry.effectivePrivate` / `entry.thing.isPrivate()`，并未走 `shouldProtectFolderForAccess`，缺已认证豁免。后由真机反馈暴露并修复。教训：审计时"看着像带豁免"必须落到具体判定表达式核对，不能按入口名归类。

## 2026-06-29 - 外观面板入口鉴权缺已认证豁免

真机反馈：打开私密文件夹→验证密码→进入→返回上级→长按→调整文件夹外观，仍要求密码。按"访问即信任"模型，本会话已为该文件夹认证过（进入时），调其外观应免二次验证。

根因：`openSelectedCardAppearancePanel`（`act_customize_card_appearance` 触发）的鉴权门没对齐其它入口。`authenticateThingFolder` 自身**无条件**弹框（认证后才 `markFolderPrivacyAuthenticated`），已认证豁免本应由调用前的 `shouldProtectFolderForAccess`（含 `!isFolderPrivacyAuthenticated`）负责；但此入口文件夹分支用的是 `entry.effectivePrivate`（只等价"有效私密"这一个条件），记事分支用 `entry.thing.isPrivate()`（只看前缀），都漏了豁免。

修复（对齐 7625/右滑等既有口径）：
- 文件夹分支：`if (entry.effectivePrivate)` → `if (shouldProtectFolderForAccess(entry.folder.id))`，与打开文件夹/抽屉展开/移动完全一致。
- 记事分支：`if (entry.thing.isPrivate())` → `if (ThingPrivacyResolver.isEffectivelyPrivate(this, entry.thing) && !isFolderPrivacyAuthenticated(entry.thing.folderId))`，在已认证私密文件夹内调记事外观也免二次验证。

`:app:assembleDebug` 通过，发布阿里云（发布码 202606290613）。`openSelectedCardAppearancePanel` 是外观面板唯一入口（`openFolderCardAppearancePanel` 仅此处调用），已全覆盖。

## 2026-06-29 - 会话级"访问即信任"扩展到记事

承上：用户问"点开私密记事→返回→再点开仍要密码，对不对"。判定为与文件夹不对称的隐患（会话记忆只建在文件夹级），用户拍板"对齐文件夹"。实现（决策见 decisions.md 同日条目，力度 A1 行为对齐）：

- `ThingManager` 新增 `mAuthenticatedPrivateThingIds` 及 `isThingPrivacyAuthenticated/markThingPrivacyAuthenticated/clearAuthenticatedPrivateThings`，与文件夹集对称。
- `App` 切后台清空处一并 `clearAuthenticatedPrivateThings()`。
- `authenticateThing` / `authenticateThingSwipe` 认证成功即 `markThingPrivacyAuthenticated(thing.id)`；打开详情路径收口走 `authenticateThing`（替掉原内联 `AuthenticationHelper.authenticate`）。
- 三处记事鉴权门加 `&& !isThingPrivacyAuthenticated(thing.id)`：打开详情（`onItemClick`）、右滑（`needsThingSwipePrivacyAuthentication`）、调外观（`openSelectedCardAppearancePanel` 记事分支）。移动不动（只看文件夹私密）。
- 刻意**不**改 `isThingEffectivelyPrivateInCurrentProjection`（被 `canCreateThingFolderWith` 复用，建文件夹不应因已认证解禁）。
- A1：首页私密记事卡片仍保持锁，不因认证就明文摊开内容。

`:app:assembleDebug` 通过。

## 2026-06-29 - 升级 A2：认证后揭示真实内容（决策见 decisions.md 同日条目）

用户看到 A1 的"锁图标 vs 免验"错位后要 A2。实现：

- `ThingsAdapter` 新增 `shouldRevealFolderContent(folder)`=外观编辑∨全局显示∨已认证，收口接入 `presentationFor` / `hiddenPrivate` / `getFolderThumbnailEntries(revealRoot)`；记事卡片(120)与缩略图预览记事改用 `isThingRevealedByAuth`。覆盖首页列表 + 大文件夹缩略图。
- 揭示判定抽成可覆写接缝 `isFolderRevealedByAuth`/`isThingRevealedByAuth`（默认单例）。`BaseThingWidgetConfiguration`（app 外入口、本地认证集）的 `FolderCardDelegateAdapter` 覆写为本地语义（文件夹按本地认证、记事恒 false），防止主 app 会话认证泄露到桌面配置界面——守住决策 4 边界。
- 边界不动：DoingActivity / NoticeableNotification / 桌面小部件渲染。
- 性能：`getFolderPath` 是逐级 DB 查询，揭示判定用 `folder.isPrivate` / `entry.effectivePrivate` 短路，仅私密/有效私密文件夹走路径遍历，非私密零额外开销。

排查确认 app 内仅 `ThingsActivity`(ThingsAdapter) 与 widget 配置两处展示面需处理；移动对话框/抽屉只显示文件夹名（无内容遮蔽）。`:app:assembleDebug` 通过。

**修（同日，A2 记事揭示不生效）**：真机反馈"私密记事鉴权后首页列表仍不显示内容"。根因不在揭示判定（`isThingEffectivelyPrivate` 认证后已正确返回 false），而在**列表不重绑**：仅查看记事返回详情走 `RESULT_NO_UPDATE`，不入 notify 队列，`ThingsAdapterWrapper.tryToNotify` 空转，卡片维持上次绑定的上锁态。修法：`authenticateThing` 认证成功 `markThingPrivacyAuthenticated` 后主动 `mAdapter?.notifyDataSetChanged()`，揭示在返回后即生效（开详情用 makeScaleUpAnimation 缩放转场、非共享元素，且重绑随界面暂停延后到返回，不干扰转场）。同时把 `onFolderThumbnailClick` 的内联鉴权收口到 `authenticateThing`（原先缺 `markThingPrivacyAuthenticated`，缩略图点私密记事认证后不记忆）。另注：前两次"发布阿里云"命令因工具格式错乱未执行，A1/A2 此前并未真正上线，本次随该修复一并发布（发布码 202606290735，含 A1 行为对齐 + A2 揭示 + 本重绑修复）。`:app:assembleDebug` 通过。

**修（同日，嵌套大文件夹缩略图缺记事）**：真机反馈"设文件夹 A 私密后，A 内的大文件夹 B 缩略图只见子文件夹、不见记事"。这正是 followups 里"A2 揭示在嵌套大文件夹缩略图未完全传透"那条。根因：B 自身非私密、靠祖先 A 私密而有效私密，故 `effectiveCardPresentation` 仍是大文件夹、投影期会预算 `thumbnailEntries`；但投影按 `revealRoot=false`，B 的直接记事在 `getDirectThumbnailThings` 那道闸被整条过滤、只剩子文件夹。绑定时 `getFolderThumbnailEntries` 的 `if (thumbnailEntries.isNotEmpty()) return` 缓存命中，永远走不到带 `revealRoot=true` 的实时取数。（顶层私密大文件夹自身私密→`effectiveCardPresentation` 变摘要→投影 thumbnailEntries 为空→绕过缓存走实时，故不受影响；嵌套"自身非私密"才触发。）修法：`getFolderThumbnailEntries` 揭示时（`entry.effectivePrivate && shouldRevealFolderContent`）跳过缓存、按 `revealRoot=true` 实时取数。残留 latent（DAO revealRoot 子文件夹 effectivePrivate 对祖先已认证不敏感）记入 followups，因 `shouldShowFolderPrivateContent` 兜底不致可见错误。`:app:assembleDebug` 通过，发布阿里云（发布码 202606290802）。

**修（同日，设私密后文件夹卡片无可见变化）**：真机反馈"长按文件夹 A→设为私密文件夹，卡片外观没变化（虽确已私密），多选/混选估计同样"。

先走偏一版（发布码 202606290817，已被下条推翻）：误判为"缺私密标识"，给揭示态私密文件夹卡片标题行加了 `ic_locked_small` 小锁。

**纠偏（用户指正方向）**：真正问题是 `updateFolderPrivate(isPrivate=true)` **无条件**自动认证。但"自动认证"的决策 3 理由——"人还站在里面，内容不应立刻锁"——**只在用户已打开该文件夹时成立**。从列表长按/批量设私密时，用户并没有打开它，就该立刻上锁、给出"已变私密"的可见反馈（locked 大锁）。修法：① 撤掉小锁标识；② `updateFolderPrivate` 改为**仅当 `folder.id == mProjection.currentFolderId`（正设私密的是当前已打开文件夹）才 `markFolderPrivacyAuthenticated`**。该条件天然区分两类入口：`act_toggle_current_folder_private`（内 overflow，设当前文件夹）→ 认证、内容留存；`toggleSelectedPrivateEntry`/`applySelectedPrivateBatch`（长按单选/批量，设选中的子文件夹）→ 不认证、卡片上锁。批量永不含当前文件夹（列表只显示子项），故永不自动认证。`:app:assembleDebug` 通过，发布阿里云（发布码 202606290830）。

## 2026-06-29 - 已鉴权私密项加"开锁"标识

需求：本会话已鉴权（揭示）的私密文件夹/记事要有可见标识——开锁，区别于未鉴权的闭锁/大锁。

- **文件夹**：复用 Drawer 既有的 `DrawerNavigationView.FolderIconDrawable`（在文件夹形状内画锁，Drawer 与移动对话框本就共用）。给它加 `authenticated` 参，已鉴权时把锁梁绕左下铰点旋转 -28° 画"开锁"，否则画闭锁。透传：Drawer（`DrawerItem.folderAuthenticated` ← `isFolderPrivacyAuthenticated`）、移动对话框（渲染处读共享单例 `isFolderPrivacyAuthenticated`）、主列表卡片（`bindFolderCardHeader` 加 `folder` 参，私密且 `shouldRevealFolderContent` 时用 `FolderIconDrawable(open)` 取代矢量图；未揭示态卡片本就有居中大锁，不重复）。大文件夹子文件夹缩略图复用 `bindFolderCardHeader`，自动生效。
- **记事**：新建 `ic_lock_open.xml`（Material lock_open）。`updateCardForTitle` 对"已揭示的私密记事"（`!masked && rawPrivate`，rawPrivate 用忽略认证的 `ThingPrivacyResolver.isEffectivelyPrivate`）在标题用 `setCompoundDrawablesRelative` 加 start 开锁，尺寸取标题字号 px、单行标题故与首行 y 居中对齐；空标题时仍显示标题行让开锁有处可依（处理无标题私密记事）。
- 隔离：widget 配置继承同款渲染，但揭示判定走其本地认证接缝（`isFolderRevealedByAuth` 本地、记事侧本地 `isThingEffectivelyPrivate`），不串主会话认证。NoticeableNotification 显示遮蔽态（masked），故不显示开锁。

`:app:assembleDebug` 通过，发布阿里云（发布码 202606290855）。

**修（同日，开锁两处反馈）**：
1. **开锁方向**：`FolderIconDrawable` 开锁原绕左下铰点旋转、从右侧开；改为绕右下铰点 `rotate(28f, x(13.65f), y(12.9f))`、从左侧开，与私密记事 Material lock_open 一致。
2. **卡片图标着色**：卡片/缩略图私密文件夹图标原 `FolderIconDrawable(folder.getBackground())` 用文件夹自身颜色填充；改用 `pure(textColorPrimary(baseColor))`（自适应墨色）。`FolderIconDrawable.buildFolderPath` 与 `ic_thing_folder` 是同一形状，故填充自适应墨色后与普通文件夹图标视觉一致、只多了把开锁，锁按对比色绘制可见。
3. **开锁只对本身私密项**：记事开锁判定原用 `ThingPrivacyResolver.isEffectivelyPrivate`（含祖先文件夹私密），导致已鉴权私密文件夹内的**普通**记事也显示开锁；改为 `thing.isPrivate()`（仅自带前缀）。普通记事即便在已鉴权私密文件夹内被揭示，也保持正常态。文件夹侧（卡片/Drawer/对话框）本就以 `folder.isPrivate`（本身私密）为门，无此问题。

`:app:assembleDebug` 通过，发布阿里云（发布码 202606290914）。

**再修（同日，开锁形状 + 缩略图着色再纠偏）**：
1. **开锁形状**：上版用 `canvas.rotate` 旋转锁梁，真机看着歪斜。改为不旋转——`authenticated` 时直接画"删去左侧锁栓下段"的路径（右栓连锁体、左侧悬于锁体上方留缺口），端正且开口在左。closed/open 两套 path 分支绘制。
2. **缩略图着色**：上版把私密文件夹图标一律改自适应墨色，过头了——大文件夹卡片本身的图标本应 tint 为文件夹色（这是大文件夹既有表现），只有"在别的大文件夹缩略图里、以小文件夹形态出现的预览"才要自适应。修法：`bindFolderCardHeader` 的 `FolderIconDrawable` 填充色改为 `titleBackground ?: pure(textColorPrimary(baseColor))`——`titleBackground` 非空恰等价于大文件夹模式（thumbnailMode），故大文件夹用文件夹背景色、小文件夹/预览用自适应墨色，与既有"按形态着色"对齐。

`:app:assembleDebug` 通过，发布阿里云（发布码 202606290926）。

## 2026-06-29 - 代码评审 5 条隐患逐条核对并修复

用户给一份代码评审清单（5 条，行号部分已偏），要求"先评估、别全信，确有问题才改"。逐条核对当前代码：

1. **系统通知媒体大图泄露**（真）：`SystemNotificationUtil` 文字按有效私密占位，但媒体仍传原始 thing 给只看 `thing.isPrivate()` 的 `RemoteThingCardMediaRenderer`。修：复用已算的 `effectivelyPrivate`，`!effectivelyPrivate` 才渲染大图（通知是唯一传原始 thing 处；小部件路径已 resolve，不在渲染器加 folderDAO 开销）。
2. **单一小部件配置完成绕过安全副本**（真）：`endSelectThing` 用原始 thing 建 RemoteViews。修：先 `resolveForPresentation` 再建。
3. **A2 嵌套缩略图**：第一部分（缓存复用）早前已修；第二部分（真）DAO `revealRoot` 子文件夹 `effectivePrivate` 含 `isEffectivelyPrivate(parentFolderId)`，把已认证祖先算成私密、连带非私密子文件夹上锁。修：揭示时只按 `childFolder.isPrivate`。用户引用的 794 行是旧行号。
4. **Doing 前台通知标题泄露**（真）：`getDoingNotificationTitle` 空标题回退读 content/附件。修：有效私密且空标题只显示类型名。
5. **过期全屏通知崩溃**（真）：`NoticeableNotificationActivity` 把可能为 null 的 `pair.first` 传给非空参 `resolveForPresentation`，删除/过期记事 NPE 而非走 `finish()`。修：`rawThing?.let { ... }`，null 则 mThing=null 由 onCreate `finish()`。

`getThingAndPosition` 牵涉约 15 调用方、改返回类型风险大，故在 NoticeableNotificationActivity 调用点加空守卫（与其它调用方把 first 当可空处理的现状一致）。`:app:assembleDebug` 通过，发布阿里云（发布码 202606290939）。

## 2026-06-29 - 复查 3 处遗漏（widget 简单样式泄露 + 两处闭锁）+ Drawer/对话框复核

- **P1 widget 简单样式空标题私密记事泄露**（真）：`AppWidgetHelper.getTitleToDisplayForSimpleStyle` 标题空时回退 `thing.content`；`getTitleToDisplay()` 对私密记事去前缀，故"空标题私密记事"(resolve 后仅前缀)经它得空 → 回退到内容。修：`title` 非空判断后加 `if (thing.isPrivate()) return null`。
- **P2 详情页文件夹路径图标闭锁**（真）：路径文字按 `isFolderPrivacyAuthenticated` 显真实名，但 `FolderIconDrawable` 未传 authenticated。修：传 `manager?.isFolderPrivacyAuthenticated(lastFolder.id) == true`。
- **P3 列表小组件配置页文件夹树闭锁**（真）：`FolderAdapter` 有本地 `authenticatedPrivateFolderIds` / `isFolderPrivacyAuthenticated`，但 `FolderIconDrawable` 未传。修：传本地 `isFolderPrivacyAuthenticated(folder.id)`。
- **Drawer / 移动到文件夹对话框复核**：上一轮开锁任务已正确接入（Drawer 经 `DrawerItem.folderAuthenticated` ← 单例 `isFolderPrivacyAuthenticated`；对话框渲染处读共享单例），且认证/导航后 `updateDrawerFolderItems`（openThingFolder 等多处调用）与对话框重建会刷新，状态不滞后。**无需改动**。

Doing 入口"app 外不鉴权"是否要纳入隐私体系，留作产品口径确认（followups 记录，未动）。`:app:assembleDebug` 通过，发布阿里云（发布码 202606290959）。

## 2026-06-29 - ActivityHeader 私密锁接入认证 + 折叠隐藏；开锁缺口加大

真机反馈（鉴权进入私密文件夹 A 后）：

- **issue 1 详情页路径图标仍闭锁**：P2 代码本就正确（authed 传 true）。判断为开锁在 18dp 小图标上缺口太小、看着像闭锁；把 `FolderIconDrawable` 开锁左侧缺口加大（左梁末端 y 11.5→11.0），各处一致提升辨识度。
- **issue 2/3 ActivityHeader 标题前的锁恒闭锁**：`applyFolderTitleStyle` 原用 `ic_locked_small`（恒闭锁、不接认证）。改为按 `ThingManager.isFolderPrivacyAuthenticated(folder.id)` 切图标——已鉴权 `ic_lock_open`、否则 `ic_locked_small`，进入私密文件夹（含 A 下属私密 B）即已认证故显示开锁。（**纠正**：曾一度改用 `FolderIconDrawable`（文件夹+锁），但用户要求 Header 左侧"直接一把开锁即可"，遂改回纯锁图标，与私密记事标题前的开锁一致。发布码 202606291140。）

**再调（同日）**：纯锁先用 compound drawable，反馈"图标太小"且"应与标题第一行对齐、而非整个 TextView 居中"。compound drawable 会在整段（多行）标题高度上居中、尺寸也不随标题。改为**内联 ImageSpan**：标题文本开头插占位符并挂自定义 `CenteredLockSpan`（按所在行文字视觉中线居中），锁天然落在第一行、与首行文字对齐，尺寸取标题字号、随标题换行/缩放一起走。折叠隐藏改为切换"带锁/纯文字"两版标题文本（`mFolderLockTitle`/`mFolderPlainTitle`，仅显示态变化时切）。minSdk 26 故 `ImageSpan.ALIGN_CENTER`(API29) 不可用，自写居中 span。
- **issue 4 图标影响 Header 折叠**：该图标是 title 的 compound drawable，会占宽并随标题缩放挤进 actionbar 区。新增 `applyFolderLockForProgress`：折叠进度 ≥ `FOLDER_LOCK_HIDE_PROGRESS`(0.6) 时隐藏图标（仅显示态变化时改 compound drawable，避免每帧重布局），由 `updateTitleLayoutForProgress` 按进度调用；`resetTitleTextStyle` 清状态。标题缩小/换行/归位 actionbar 不再受图标干扰。

`:app:assembleDebug` 通过，发布阿里云（发布码 202606291020）。

## 2026-06-29 - 修：选图/拍照跳外部应用导致会话认证被误清

真机反馈（在已认证私密文件夹 A 里创建只含图片的记事）：完成后该非私密记事显示成私密、点击要鉴权、路径图标上锁、返回详情后 A 内所有内容全上锁。一连串现象都指向**A 的会话认证被清掉了**。

根因：加图片要跳**外部相机/图库**（`AddAttachmentDialogFragment` 三处 `startActivityForResult`：拍照/拍视频/选媒体文件）。外部应用占前台时 app 所有 Activity 都 stop、`startedActivities` 归 0，`App` 的 `ActivityLifecycleCallbacks` 把这当"切后台"清空了 `mAuthenticatedPrivateFolderIds`/`ThingIds`——但这是"临时去取结果"，不是用户离开 app。

修：`App` 加 `sSuppressAuthClearOnBackground` 抑制标志 + `suppressPrivacyAuthClearForActivityResult()`；三处外部跳转前置位，`onActivityStopped` 切后台时标志置位则跳过清空，`onActivityResumed` 回前台解除（一次性，回来即恢复正常清空）。app 内图片查看器（`REQUEST_ACTIVITY_IMAGE_VIEWER`）是 app 内 Activity、不会让 `startedActivities` 归 0，无需处理。决策 4 加了对应"切后台清空排除临时取结果"的细化。修后 A 认证在选图往返期间保留，那条图片记事按 A 已认证正常显示、点击免验、路径开锁、返回不再误锁。`:app:assembleDebug` 通过，发布阿里云（发布码 202606291031）。

低优先 latent（随后一并改掉，发布码 202606290330）：

- `BaseThingsAdapter.isThingEffectivelyPrivate`(543) 基类默认改为 `ThingPrivacyResolver.isEffectivelyPrivate(mContext, thing)`（含文件夹私密），用全限定名内联免 import。子类该覆写的都覆写（ThingsAdapter / FolderThingPreviewAdapter:1788 / BaseThingWidgetConfiguration:758），用基类的 DoingActivity（setShouldShowPrivateContent(true)）、Noticeable（mThing 已解析）结果不变；仅为将来新界面兜底，且只单条记事，无性能顾虑。
- `ThingsActivity:7625`（文件夹缩略图点击记事）改 `ThingPrivacyResolver.isEffectivelyPrivate(...) && !isFolderPrivacyAuthenticated(thing.folderId)`（豁免改按记事自身文件夹，更准）；`10932`（右滑后选背景）改有效私密。

至此全应用"记事是否私密"的判定口径完全统一为有效私密；"是否需要鉴权"统一为有效私密 + 已认证豁免。

## 2026-06-29 - 私密显示/交互第二轮复查

用户再次大量修改私密文件夹/私密记事显示和交互后，按 code review 重新检查主列表、详情页、Drawer、移动对话框、单一/列表 widget、通知和 Doing 入口。`:app:assembleDebug` 通过。

确认前一轮 5 个风险点已修复：系统通知媒体大图按 `effectivelyPrivate` 禁止渲染；单一 widget 配置完成时使用 `resolveForPresentation`；嵌套大文件夹缩略图 revealRoot 的缓存与 DAO 子文件夹判定已补；Doing 空标题通知不再回退内容/附件；`NoticeableNotificationActivity` 对过期/删除记事加了 null guard。

本轮新发现并记录到 `followups.md`：widget 简单样式空标题私密记事仍可能回退显示内容；详情页文件夹路径图标未传已鉴权状态；列表小组件配置页文件夹树未传本地鉴权状态；Doing 当前仍是 app 外鉴权体系的显式例外，需要确认是否维持。

## 2026-06-30 - 私密记事移动补鉴权（对齐私密文件夹）

用户反馈私密文件夹移动（拖拽 / "移动到文件夹"对话框）会鉴权、私密记事同样移动却不会，要求对齐。根因：`needsThingMovePrivacyAuthentication` / `needsSelectedThingsMovePrivacyAuthentication` 只看源 / 目标**文件夹**的有效私密，漏了 `thing.isPrivate()`（记事自身私密），而 `needsFolderMovePrivacyAuthentication` 看被移动文件夹自身私密，故不对称。这正是 2026-06-29 决策第 4 条"移动不看记事自身"的遗留，本次推翻（见 `decisions.md` 同日条目）。

改动（均在 `ThingsActivity.kt`）：
- `needsThingMovePrivacyAuthentication` 源判定改为 `(thing.isPrivate() || isFolderEffectivelyPrivate(thing.folderId)) && !isFolderPrivacyAuthenticated(thing.folderId) && !isThingPrivacyAuthenticated(thing.id)`，与右滑 `needsThingSwipePrivacyAuthentication` 完全对称（仅把 currentFolder 换成按 thing.folderId），目标判定不变。
- `needsSelectedThingsMovePrivacyAuthentication` 简化为 `selectedThings.any { needsThingMovePrivacyAuthentication(it, targetFolderId) }`，批量与单条口径不再漂移。
- `authenticatePrivateMoveIfNeeded` 加 `thingsToAuthenticate` 参数，鉴权通过后 `markThingPrivacyAuthenticated`，与 `foldersToAuthenticate` 对称。三处记事移动调用方（纯记事对话框 `moveSelectedThingsToFolderWithPrivacyCheck`、拖拽 `commitMoveThingIntoFolderDrop`、混合对话框 `moveSelectedMixedToFolder`）传入选中 / 拖拽记事 id。

`:app:assembleDebug` 编译通过，按要求发布阿里云（更新码 **202606300337**），发布日志见 `debug-updates/update-20260630113537.md`。

## 2026-06-30 - 私密管理操作鉴权口径复盘：取消私密补豁免、设私密维持不鉴权

用户提出两条：① 长按列表设私密（单 / 多 / 混选）不鉴权；② 已鉴权的项取消私密仍要再验。逐条对照决策与代码：

- **设私密不鉴权**：与 2026-06-28 决策一致，非疏漏；经大厂调研（iOS 照片隐藏不鉴权、Google 锁定文件夹 / 三星安全文件夹"移入容器"才鉴权、iOS 备忘录锁定也不在锁定动作设卡）佐证，维持不改代码。
- **取消私密缺已认证豁免**：批量 / 单选 / 混选取消（`toggleSelectedPrivateBatch` 取消分支）无条件验证，与文件夹 overflow 取消、状态变更、移动口径不一致。改为复用 `needsSelectedStateChangePrivacyAuthentication`——全部已认证免验、仅含未认证有效私密项才验。详见 `decisions.md` 同日条目。

`:app:assembleDebug` 编译通过，按要求发布阿里云（更新码 **202606300356**），发布日志见 `debug-updates/update-20260630115528.md`。
