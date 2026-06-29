# 私密记事/文件夹 Followups

## 2026-06-29 代码评审待修项 —— 已全部处理（发布码见 sessions.md）

- ✅ **系统通知媒体大图泄露**：`SystemNotificationUtil` 文字已按有效私密占位，但仍把原始 `thing` 交给 `RemoteThingCardMediaRenderer`（只看 `thing.isPrivate()`），私密文件夹内自身无前缀且带图/视频的记事会在通知大图泄露。修：复用已算出的 `effectivelyPrivate`，`!effectivelyPrivate` 才渲染大图。
- ✅ **单一小部件配置完成绕过安全副本**：`BaseThingWidgetConfiguration.endSelectThing` 直接用原始 `thing` 建 RemoteViews。修：先 `ThingPrivacyResolver.resolveForPresentation` 再建，与常规刷新口径一致。
- ✅ **A2 嵌套大文件夹缩略图（缓存复用）**：`getFolderThumbnailEntries` 揭示时（`entry.effectivePrivate && shouldRevealFolderContent`）跳过 DAO 预填缓存、按 `revealRoot=true` 实时取数（早前已修）。
- ✅ **A2 嵌套缩略图（DAO 子文件夹判定）**：`revealRoot` 子文件夹 `effectivePrivate` 原按 `child.isPrivate || isEffectivelyPrivate(folder.parentFolderId)`，会把"已认证祖先"算成有效私密、连带把非私密子文件夹标成上锁。修：揭示时只按 `childFolder.isPrivate`（自身），因能揭示根即意味祖先链已认证可访问。
- ✅ **Doing 前台通知标题泄露**：`getDoingNotificationTitle` 空标题时回退读 content/附件摘要。修：有效私密且空标题时只显示类型名，不回退内容/附件。
- ✅ **过期全屏通知崩溃**：`NoticeableNotificationActivity` 把可能为 null 的 `pair.first` 传给非空参的 `resolveForPresentation`。修：`rawThing?.let { resolveForPresentation }`，null 时置 mThing=null 走 onCreate 的 `finish()`。

## 架构债 - Thing 私密从标题前缀迁移为真实布尔列（ADR 0011 方案 C）

把 Thing 私密改成像 ThingFolder 那样的独立布尔列 + 现算有效私密，去掉 `PRIVATE_THING_PREFIX` 前缀 hack。需 DB 版本迁移（扫描所有前缀标题→置列并去前缀，回填）。收益：与文件夹对称、前缀不再污染 `title`、消除"直接读 `title` 显示前缀垃圾"的隐患。推迟原因：迁移风险大，已用集中化（ADR 0011 方案 A）消除安全泄露，干净度提升不紧急。

## 审计 - 所有展示/通知界面接入集中"有效私密解析"

已接入：单一记事小部件（`BaseThingWidget`）、系统通知（`SystemNotificationUtil` + 提醒/习惯 receiver）、`NoticeableNotificationActivity`（锁+标题、动作鉴权）。列表小部件本就有保护。剩余审计：任何后续新增的、直接用 `thing.isPrivate()` 决定展示/认证的路径。

---

P1（认证作用域合并为会话级 + 切后台清空）与 P4 实时预览旁路已于 2026-06-29 实现，详见 `execution.md` / `sessions.md`，不再列为 followup。

## 2026-06-29 复查待处理项

- [x] **Widget 简单样式空标题私密记事内容泄露**（已修，发布码见 sessions.md）：`getTitleToDisplayForSimpleStyle` 在 `title` 非空判断后加 `if (thing.isPrivate()) return null`，私密记事空标题不再回退内容（与"仅附件"返回 null 同路径，外层显示锁）。
- [x] **详情页文件夹路径图标未接入开锁状态**（已修）：`updateThingFolderPath` 创建 `FolderIconDrawable` 时传入 `manager?.isFolderPrivacyAuthenticated(lastFolder.id) == true`，已认证私密文件夹路径图标改画开锁，与路径文字真实名一致。
- [x] **列表小组件配置页文件夹树未接入开锁状态**（已修）：`FolderAdapter` 创建 `FolderIconDrawable` 传入本地 `isFolderPrivacyAuthenticated(folder.id)`，已认证私密文件夹画开锁。
- [x] **复核 Drawer / 移动到文件夹对话框开锁**：均已正确传 `isFolderPrivacyAuthenticated`（Drawer 经 `DrawerItem.folderAuthenticated`，对话框读共享单例），且认证 / 导航后 `updateDrawerFolderItems` / 对话框重建会刷新，无滞后。无需改动。
- [x] **Doing 入口隐私例外，已定调**（2026-06-29）：采"**正在做即默认可信**"——访问/操作正在做的记事（含 app 外的 Doing 前台通知点按与动作）不鉴权，维持现状不改。已写入 `decisions.md`（同日"正在做是隐私鉴权的显式例外"）。注意区分：本例外只豁免鉴权，不放开对旁观者的展示遮蔽（Doing 通知仍遮蔽私密内容）。
