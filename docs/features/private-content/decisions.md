# 私密记事/文件夹 Decisions

## 2026-06-28 - 文件夹私密采用"容器保护"模型，不级联标记后代

确认私密文件夹是容器级属性：把一个 Thing Folder 设为私密只改它自身，后代 Thing 和子文件夹不被逐个标记，靠"有效私密(Effectively Private)"在展示层保护——一条记事被保护，当且仅当它自身私密、或它的某个祖先文件夹私密，且当前未通过该私密范围的身份验证。

不采用"级联标记"方案（设私密时遍历给后代加前缀/置位）。原因：

1. 级联无法干净处理"后代里本来就单独设过私密"的情形——取消文件夹私密时无法区分哪些该还原，会丢失用户原本意图，除非给每条记事额外存"是否被级联设私密"，反而更复杂。
2. 级联还要处理移动语义（移出文件夹是否保留私密），容器模型里"移出即不再受保护"天然成立。
3. 容器模型零迁移成本，无需批量改写标题。

该决议与既有 2026-06-19 "Folder privacy" 决策、CONTEXT.md 术语表一致，是本轮私密交互/显示优化的地基。

本轮优化的真正目标因此收敛为三类：消除"设为私密"动作的对象歧义、统一各界面"私密"的视觉表达与认证流程、堵住分享等泄露口。

## 2026-06-28 - "设为私密"动词文案按选中组成自适应，并把文案逻辑收口

"设为私密/取消私密"在三处入口（详情页 overflow、长按 contextual 菜单、文件夹内 overflow）此前文案不一致：只有详情页贴切（"设为私密记事"），contextual 菜单和文件夹 overflow 都被 `HomeActionWordingHelper.privateTitle` 改写成通用的"设为私密/取消设为私密"，把对象信息丢了。而同一工具栏里的完成/删除/恢复动词早已按选中组成（记事/文件夹/混合）自适应，唯独私密动词没跟上——配套精确字符串（`act_set_as_private_thing`/`act_set_as_private_items`/`set_thing_folder_private` 及取消串）当时已建好，却被通用串盖掉，属未收尾的改名。

决定：

1. 私密动词复用状态动词同一套"三桶"组成自适应——纯记事→"设为私密记事"，纯文件夹→"设为私密文件夹"，混合→"设为私密项"，取消方向同理。
2. 文件夹内 overflow 直接使用"设为私密文件夹/取消私密文件夹"，去掉 `configureCurrentFolderMenu` 里那行运行时通用改写（XML 默认本来就是对的）。
3. 详情页不变。
4. 三处私密文案计算收口到 `HomeActionWordingHelper` 一处，不再各处分散。
5. 不加确认弹窗——对象在文案中点明即可消歧义。

## 2026-06-28 - 设私密文件夹后本会话自动认证，私密标识放 Activity Header 文件夹名前

从文件夹内把当前文件夹设为私密后，旧行为是不标记已认证，导致内容立刻全变锁卡片——人还站在里面，却第一眼看到"内容全没了"，视觉上与"逐个把记事设私密"无法区分，等于把问题 2 刚消除的歧义又用画面制造了一遍。

决定：

1. 设为私密后，本会话内把该文件夹视为已认证（加入 `mAuthenticatedPrivateFolderIds`），内容不立刻锁、照常显示；只有下次未认证重新进入时才锁。
2. 安全依据：设私密只要求"已配置密码"、并不要求当场输入密码（`hasPrivatePassword()` 仅检查存在性），且这些内容在设私密前本就公开可见，因此"设完即视为已认证"不泄露任何新内容。
3. 在 `views.ActivityHeader` 的文件夹名称**前面**新增一个锁标识，作为"当前文件夹是私密"的正向信号；进入私密文件夹时显示。Header 目前无任何私密标识，需新增。

效果：设私密的反馈从"内容消失"变成"内容还在 + 文件夹名前多了个锁"，配合问题 2 的文案，彻底填平用户最初踩的坑。

**细化（2026-06-29）**：自动认证**只对"当前已打开的文件夹"成立**——本条理由"人还站在里面"仅在用户打开了该文件夹时为真。从首页/父级列表**长按或批量**把一个**未打开的**子文件夹设私密时，用户并没有站在里面，应**不自动认证、卡片立刻上锁**，作为"已变私密"的可见反馈（否则 A2 揭示下卡片显示内容、与普通文件夹无异，看不出变化）。实现：`updateFolderPrivate` 仅当 `folder.id == mProjection.currentFolderId` 才 `markFolderPrivacyAuthenticated`。该条件天然区分"内 overflow 设当前文件夹"（认证）与"列表长按/批量设子文件夹"（不认证）。曾误试"给揭示态私密卡片加小锁标识"，方向错误，已撤。

## 2026-06-28 - 私密文件夹认证合并为一套会话级作用域，切后台清空

旧实现有三套互不相通的"已认证私密文件夹"集合，导致同一会话、同一文件夹在不同界面要分别认证（进入一次、抽屉展开再一次、移动对话框展开又一次）：

- 主内容区：`mAuthenticatedPrivateFolderIds`（ThingManager），导航离开按路径裁剪即清。
- 抽屉展开：`mAuthenticatedDrawerExpandedPrivateFolderIds`（ThingsActivity），关抽屉即清空。
- 移动对话框：`mAuthenticatedExpandedPrivateFolderIds`（MoveToThingFolderDialogFragment），对话框销毁即清。

决定：

1. 合并为**一套会话级集合**，app 内所有界面（主内容、抽屉、移动对话框、app 内小部件配置等）共享。同一私密文件夹本会话认证一次即处处解锁。
2. 生命周期：app **切到后台即清空**，回前台需重新认证。
3. 边界：会话级解锁只管 **app 内**连续会话；从 **app 外**进入的入口（桌面小部件点按、通知操作按钮）每次仍各自走 `AuthenticationActivity`，不纳入会话级集合。

原则与问题 3 一致：本会话已证明过身份，就不再为同一个文件夹反复设卡；离开 app 即重新设防。

**细化（2026-06-29）"切后台清空"排除"为取外部结果而临时离开"**：真机反馈——在已认证私密文件夹里创建只含图片的记事，完成后该（非私密）记事却显示成私密、点击要鉴权、返回后整个文件夹内容全上锁。根因：加图片要跳**外部相机/图库**（`AddAttachmentDialogFragment` 的 `startActivityForResult`），那一刻 app 所有 Activity 都 stop、`startedActivities` 归 0，`ActivityLifecycleCallbacks` 把它当"切后台"清空了认证。但"临时离开取结果"并非用户离开 app。修：`App` 加会话级抑制标志 `sSuppressAuthClearOnBackground`，由各外部取结果入口在 `startActivityForResult` 前调 `suppressPrivacyAuthClearForActivityResult()` 置位；`onActivityStopped` 切后台时若标志置位则跳过清空，`onActivityResumed` 回前台解除。已覆盖选图/拍照/拍视频/选媒体文件四处（app 内图片查看器不触发、无需处理）。残留窄边界：跳选图后再按 Home 真正离开、期间认证不清（但此时 app 内容不可见，无泄露），回前台即解除、之后正常清空——可接受。

旧实现"取消私密"三处不对称：详情页记事取消要验证、批量取消要验证，唯独文件夹 overflow 取消私密**零门槛**（[ThingsActivity.kt:7920](app/src/main/java/com/ywwynm/everythingdone/activities/ThingsActivity.kt:7920) 直接翻转）。

采用"哲学一（访问即信任）"，与问题 3、4 自洽——认证用于"访问"，本会话已为某对象认证过，则管理它（含取消私密）不再二次验证。统一规则：

1. **文件夹 overflow 取消**：人已在文件夹内 = 本会话已认证它，放行不再验证；但需补"确实已认证"的前提判断，防止未认证路径误触（现状的零门槛在新模型下结果正确，缺的是这个前提）。
2. **详情页记事取消**：点开它时已验证过，去掉现状那第二次冗余验证。
3. **批量取消**：选中项可能含本会话未单独认证过的私密文件夹/记事（直接在列表勾选、没打开过），保留验证——这正是批量需要验证的真正理由。
4. **设为私密**三处维持现状（仅需已配密码、不挑战身份）。

一句话：访问要认证；会话内认证过的，管理它（含取消私密）免二次认证；够不到的（批量里没打开过的）才补认证。

## 2026-06-29 - 隐私边界契约、空标题私密记事、导出/分享鉴权

**边界契约**：标题（若有）= 可见的定位标签；内容 / 媒体 / 提醒习惯等明细 = 秘密。

**空标题**：接受空标题的私密记事，**不恢复**"私密必须有标题"的强制校验——该旧校验 `warning_title_should_not_be_empty` 已是 Kotlin 代码里的死串（零引用），`cannot_set_as_private_thing_title` 也已被挪用为"未设密码"提示框标题。空标题时锁卡片**不回退**显示 `[私密记事]` 文本——卡片上有锁即可，配合记事自身的颜色身份辨识（列表卡片当前渲染已是"锁+颜色+标题(若有)"，空标题情形无需改动；要做的只是保持不加校验）。

**视觉语言按介质分工**，不强行统一为一种：

- 富界面（列表/小部件卡片、详情、Activity Header）：锁图标 + 颜色身份，标题有则显示。
- 纯文本介质（系统通知）：用 `[私密记事]` 占位文本（无锁图标/颜色卡片可依托）。

**导出/分享**遵循"访问即信任"（问题 5 同款）：

- 批量"导出到 SD 卡"（`ThingExporter` → `getThingShareInfo`）选中含本会话未认证的私密记事时，先验证一次再导出，通过后导出全部完整内容。
- 详情页分享：已在详情即已认证，照旧。

## 2026-06-29 - 私密文件夹卡片外观：停止销毁存储值，编辑器经认证后展示真实外观

发现的不只是"静默重置"，而是**数据损坏**：所有渲染路径本就读 `effectiveCardPresentation()`（私密时自动返回 default），显示层早已自动遮蔽；但 `ThingManager` 三处（[1173](app/src/main/java/com/ywwynm/everythingdone/managers/ThingManager.kt:1173)/[1193](app/src/main/java/com/ywwynm/everythingdone/managers/ThingManager.kt:1193)/设私密时 [1207](app/src/main/java/com/ywwynm/everythingdone/managers/ThingManager.kt:1207)）额外把存储的 `cardPresentation` 覆盖成 default 并落库，导致设私密当场抹掉外观、取消私密也回不来，永久丢失。（被影响的只是 presentation/缩略图预览部分；颜色/背景不受影响。）

"私密文件夹卡片不显示内容预览"的安全意图保留，但改用"显示层遮蔽"而非"销毁存储值"实现：

1. **停止** ThingManager 三处对 `cardPresentation` 的 default 覆盖；存储始终保留用户真实选的外观。普通列表显示继续走 `effectiveCardPresentation()`（私密时自动 default）。于是设私密不再损坏外观、取消私密自动恢复。
2. **外观编辑面板**对私密文件夹先要求认证（密码/指纹；若本会话已认证过则免，遵循问题 4/5）；认证通过后，面板从**真实存储值** `folder.cardPresentation`（而非被遮蔽的 `effectiveCardPresentation()`）起编，临时展示该文件夹的真实外观（含缩略图/预览）供用户调整，结果存回真实值。编辑器是"主动的、已认证的"界面，应展示真相而非遮蔽后的 default。
3. 颜色/背景本就不受私密影响，照常可编辑。

## 2026-06-29 - 有效私密强制集中化，堵住单一小部件与通知泄露；前缀→列重构另立

确认这是容器模型（问题 1）+ 标题前缀编码共同导致的**系统性泄露**：凡只用 `thing.isPrivate()`（查前缀）而不现算"有效私密（祖先文件夹）"的展示/通知界面，对"处于私密文件夹内、自身无前缀"的记事都会泄露。已确认：

- 单一记事小部件（`BaseThingWidget` → `AppWidgetHelper`）：桌面明文显示内容、无锁、点击不认证。
- 系统通知（`SystemNotificationUtil` 提醒/习惯）：锁屏/通知栏明文显示内容，操作按钮因 `isPrivate=false` 跳过认证。
- 需一并审计：`NoticeableNotificationActivity`、从通知"开始做"绕过认证进 `DoingActivity`。

决定（力度 A，集中化 + 堵漏，本轮内）：

1. 把列表小部件的 `protectThingIfNeeded` 泛化为**唯一的"展示用有效私密解析"入口**，所有展示/通知界面拿到记事后先过它；本轮修单一小部件 + 通知，并审计其余。
2. 标题前缀仍作"自身私密"的内部存储；但"是否该保护"的判断全局收敛为一条路径，杜绝新界面再漏。

前缀 hack → 真实布尔列 + 现算有效私密的彻底重构（方案 C）作为独立架构债，另立 followup 并记 ADR，不阻塞本轮。

## 2026-06-29 - NoticeableNotificationActivity 归为富界面，未设密码文案对齐

**(a) NoticeableNotificationActivity**：私密记事改为**模仿首页私密卡片**——显示标题 + 锁、隐藏内容，不再用 `[私密记事]` 占位文本；每个 action（完成、开始做等）都必须经 `AuthenticationActivity` 鉴权。这把问题 6 的边界收得更紧：**只有真正的系统通知托盘（纯文本介质）才用 `[私密记事]` 占位**，其余（含全屏 NoticeableNotification）一律"锁 + 标题"。该界面同样须接入问题 8 的集中有效私密解析（否则对"私密文件夹内无前缀"的记事仍会漏）。

**(b) 未设密码文案**：未配密码时的提示标题不再复用听起来像能力限制的 `cannot_set_as_private_thing_title`（"无法设置为私密记事"），改为明确"请先设置密码"口吻；涉及详情页 `warnNoPassword` 与文件夹 `warnNoPasswordForPrivateFolder`（内容串已是 `warning_should_set_password_first`，标题对齐即可）。

## 2026-06-29 - 会话级"访问即信任"从文件夹扩展到记事（行为对齐，卡片仍锁）

起因：用户反馈"首页点开一条独立私密记事→返回→再点开，仍要密码"。复盘确认：会话级认证记忆此前**只建在文件夹级**（`mAuthenticatedPrivateFolderIds`），记事的私密保护来自 `thing.isPrivate()`（前缀）且**无任何会话记忆**——靠文件夹保护的记事能随文件夹认证免验，靠**自身前缀**保护的独立私密记事则每次打开都重验。这是与"本会话已为某对象认证过则不再二次验证"原则不自洽的不对称，且从未作为决策写下。

决定：**对齐文件夹**——新建一套会话级 `mAuthenticatedPrivateThingIds`，与文件夹集对称，切后台一并清空（`App` 的 `ActivityLifecycleCallbacks` 同时清两集）。本会话认证过某私密记事后，再访问免二次验证。覆盖三处记事鉴权点：

1. **打开详情**（`onItemClick`）：收口走 `authenticateThing`（认证成功即 `markThingPrivacyAuthenticated`），门加 `&& !isThingPrivacyAuthenticated(thing.id)`。
2. **右滑完成/开始做**（`needsThingSwipePrivacyAuthentication` / `authenticateThingSwipe`）：同样豁免与写入。
3. **调记事卡片外观**（`openSelectedCardAppearancePanel` 记事分支）：门加同款豁免。
4. **移动**：只看源/目标**文件夹**私密、不看记事自身（移动不暴露内容），维持不动。
5. 不把豁免塞进 `isThingEffectivelyPrivateInCurrentProjection`——它被 `canCreateThingFolderWith`（拖拽建文件夹）复用，建文件夹是结构操作不应因"已认证"解禁；故各鉴权点单独叠加豁免。

**力度选 A1（行为对齐，非视觉对齐）**：只免二次验证，**首页那张私密记事卡片仍保持"锁+颜色+标题"**，不因认证过就把内容明文摊在列表。理由：① 直接解决"反复输密码"痛点；② 记事是最细粒度秘密，卡片解锁=完整内容长期明文常驻首页，泄露面比文件夹卡片（仅显示子项预览）大；③ 与隐私边界契约（内容=秘密）、用户"锁卡片有锁即可、靠颜色辨识"的偏好一致。代价：认证后卡片仍显示锁、但点击直接打开，存在"锁图标与免验行为"的轻微视觉错位——可接受。若日后要 A2（认证后卡片也解锁显示内容、完全等同文件夹卡片），再单独定。

## 2026-06-29 - 升级到 A2：本会话已认证的私密文件夹/记事直接显示内容

承上：用户看到 A1 的"锁图标 vs 免验行为"错位后，明确要求 A2——"已经认证的文件夹、记事都直接显示内容，包括首页记事列表里、记事 widget 配置界面里、大文件夹缩略图里，等等"。即把"访问即信任"从"免二次验证"推进到"揭示真实内容"。

**统一揭示判据**：复用 P4 为外观编辑预览铺的 `revealRoot` 链路，把"是否揭示"从"仅外观编辑"放宽到 `外观编辑 ∨ 全局显示私密 ∨ 本会话已认证`。`ThingsAdapter` 新增 `shouldRevealFolderContent(folder)` 收口文件夹揭示，接入 `presentationFor`（模式：私密折叠 SUMMARY → 揭示后真实，含大文件夹）、`hiddenPrivate`（内容遮蔽）、`getFolderThumbnailEntries(revealRoot)`（缩略图取数）；记事卡片遮蔽 `isThingEffectivelyPrivate` 加 `!isThingRevealedByAuth(thing.id)`；缩略图预览里的记事同。返回详情后 `onResume → tryToNotify` 重绑，已认证记事卡片即时从锁变内容。

**作用域隔离（关键）**：会话认证集在**单例** `ThingManager`，主 app 各界面共享。但 `BaseThingWidgetConfiguration`（从桌面启动，属决策 4 的"app 外入口"）维护**独立本地认证集**，其 `FolderCardDelegateAdapter` 继承 `ThingsAdapter` 会误用单例认证 → 主 app 认证过的私密内容会泄露到桌面配置界面。故把揭示判定抽成可覆写接缝 `isFolderRevealedByAuth`/`isThingRevealedByAuth`（默认走单例），配置界面覆写为：文件夹按**本地**认证、记事恒 false（配置界面点记事是选中预览、无记事级认证）。决策 4 的"app 外不共享主会话认证"边界由此在揭示层也守住。

**边界**：`DoingActivity`（强制显示）、`NoticeableNotificationActivity`（锁屏语境，维持遮蔽至就地认证）、**桌面小部件渲染**（app 外，决策 4）均**不**接会话揭示，保持各自行为。性能：`getFolderPath` 为逐级 DB 查询，故揭示判定的路径遍历用 `folder.isPrivate` / `entry.effectivePrivate` 短路，仅对私密/有效私密文件夹发生，非私密文件夹零额外开销。

## 2026-06-29 - "正在做"是隐私鉴权的显式例外：正在做即默认可信

承决策 4"app 外入口一律各自鉴权"。Doing（正在做某记事）是其**显式例外**，本条明确确认：

**正在做的那条记事，本身即默认可信，访问/操作它不再要求鉴权**——无论从 app 内还是 app 外（Doing 前台通知点按、通知动作按钮）。具体现状即此行为，确认保留、不改：

- `AuthenticationActivity`（[:47](app/src/main/java/com/ywwynm/everythingdone/activities/AuthenticationActivity.kt:47) / [:227](app/src/main/java/com/ywwynm/everythingdone/activities/AuthenticationActivity.kt:227)）：`App.getDoingThingId() == id` 时直接打开 `DoingActivity`，跳过验证。
- Doing 前台通知点按直接用 `DoingActivity.getOpenIntent`；通知动作（完成/取消）由 `DoingNotificationActionReceiver` 直接执行，不鉴权。
- `DoingActivity` 内 `setShouldShowPrivateContent(true)`，正在做的私密记事直接显示内容。

理由：进入"正在做"本身是一次明确、主动的用户操作（开始做事时通常已在 app 内、或刚解锁设备），把它视为已获信任，避免在专注做事的高频交互里反复设卡。

**与“显示层隐私”区分**：本条只豁免**鉴权**，不放开**对旁观者的展示遮蔽**。Doing 前台通知仍按系统通知口径遮蔽内容（无标题私密记事只显类型名、不露内容/附件，见同日“代码评审 5 条”第 4 条），因为锁屏/通知栏可能被他人看到——“可信”针对的是用户身份、不是把秘密广播给旁人。

## 2026-06-29 - 状态变更（完成/恢复/删除/彻底删除）鉴权置于确认框之后

给完成 / 恢复 / 删除 / 彻底删除私密记事 / 文件夹补隐私鉴权（此前完全没有）。鉴权时机定为**确认框点“确定”之后、真正执行前**，而非弹确认框之前——先让用户确认要做此操作，确定后再验证身份；若用户本就要取消（不点确定），无需先输密码。判定沿用“访问即信任”：选中含“未认证的有效私密”记事 / 文件夹才弹验证（记事按 `isEffectivelyPrivate && !isThingPrivacyAuthenticated && !isFolderPrivacyAuthenticated(folderId)`，文件夹按 `isFolderEffectivelyPrivate && !isFolderPrivacyAuthenticated`），通过后把涉及项标记本会话已认证。接入四个确认框 onConfirm（纯记事 `confirmThingsOnlyStateChange`、混合 `confirmMixedStateChange`、回收站结构删除 `confirmDeleteSelectedStructural`、单 / 当前文件夹永久删除 `showDeleteThingFolderForeverDialog`）。文件夹 scope“全部完成 / 删除”（根 / 文件夹 overflow）暂未接入，记 followup。
