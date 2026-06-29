# 私密记事/文件夹 优化执行 Execution

跟踪 `plan.md` 各项落地状态。状态：⬜ 未开始 / 🟡 进行中 / ✅ 完成 / ⏸ 推迟。

本轮（2026-06-29）实现并发布阿里云 debug 版（更新码 202606290137）。P0–P5 已落地，P1 推迟。

## P0 · 集中有效私密解析入口（决议 8）✅

- ✅ 新建 `helpers/ThingPrivacyResolver`（`isEffectivelyPrivate` / `resolveForPresentation`）
- ✅ `ThingsListWidgetService.protectThingIfNeeded` 改调公共入口去重

## P1 · 认证作用域合并为会话级（决议 4）✅（2026-06-29 补做）

- ✅ 三套已认证集合合并为一套会话级 `ThingManager.mAuthenticatedPrivateFolderIds`；`authenticateThingFolder` 成为统一写入点（成功即 `markFolderPrivacyAuthenticated`），删除 `ThingsActivity`/`MoveToThingFolderDialogFragment` 两个本地集
- ✅ `shouldShowPrivateDrawerChildren` 改查共享集；关抽屉不再清认证（仍收起非路径私密文件夹保持整洁）
- ✅ `trimAuthenticatedPrivateFoldersToProjection` 改为空体（会话级，导航不裁剪）
- ✅ `App.onCreate` 注册 `ActivityLifecycleCallbacks` 前台 Activity 计数，真正切后台（`isChangingConfigurations` 排除旋转）时 `clearAuthenticatedPrivateFolders()`

## P2 · 文案三桶自适应 + 未设密码文案（决议 2、b）✅

- ✅ `HomeActionWordingHelper.privateTitle` 升级为组成自适应（记事/文件夹/混合三桶）
- ✅ `ModeManager.updateMenuItemPrivate` 改用组成自适应文案
- ✅ `configureCurrentFolderMenu` 去掉通用改写，用文件夹专属串
- ✅ (b) 未设密码提示标题改"请先设置密码"（新增 `set_password_first_title`，en+三中文；详情 `warnNoPassword`、文件夹 `noPasswordTitle` 收口）

## P3 · 设私密反馈 + Header 锁 + 取消访问即信任（决议 3、5）✅

- ✅ `updateFolderPrivate` 设私密时 `markFolderPrivacyAuthenticated`（内容不立刻锁）
- ✅ `ActivityHeader` 文件夹名前加锁标识 + 代表色着色；`resetTitleTextStyle` 清理
- ✅ `DetailActivity.tryToCancelPrivateThing` 去掉二次验证
- ✅ 批量取消保留验证（现状即符合）

## P4 · 私密文件夹外观停止销毁存储值（决议 7）✅（实时预览留 followup）

- ✅ 去掉 ThingManager 三处 `cardPresentation` default 覆盖（updateFolderPrivate/updateFolderAppearance/updateFolderCardPresentation）
- ✅ 草稿起点改用真实 `folder.cardPresentation`
- ✅ 外观面板私密文件夹先认证（既有 `openSelectedCardAppearancePanel` 已鉴权）
- ✅（2026-06-29 补做）编辑期间列表卡片实时预览：`ThingsAdapter` 加 `presentationFor(folder)` 旁路（reveal 正在编辑的文件夹用真实 `cardPresentation`），开/确认/取消/清理处设清 reveal；`ThingsAdapterWrapper` 加委托；并修复 `confirmFolderCardAppearancePanel` 残留的私密 default 覆盖

## P5 · 堵漏 + NoticeableNotification 改造 + 导出鉴权（决议 8、a、6）✅

- ✅ 单一小部件 `BaseThingWidget` 渲染前过 `ThingPrivacyResolver.resolveForPresentation`
- ✅ 系统通知 `SystemNotificationUtil`（内容 111、ongoing 动作 490）+ `ReminderReceiver`/`HabitReceiver` 动作鉴权按有效私密
- ✅ `NoticeableNotificationActivity` 改为锁+标题、隐藏内容；查看/完成/开始做/延迟每个动作先鉴权；mThing 经解析
- ✅ `DoingActivity` 从通知"开始做"——由通知/全屏通知的鉴权覆盖，无需单独改
- ✅ 导出到 SD 卡含有效私密项时先鉴权（`exportSelectedThingsWithPrivacyAuth`）

## P6 · 回归审计、编译并发布 ✅

- ✅ 确认泄露面（单一小部件、系统通知、全屏通知）已堵；其余 `isPrivate()` 站点审计列入 followup
- ✅ `:app:assembleDebug` 全程编译通过（每期编译门）
- ✅ `publishDebugUpdate` 发布阿里云，更新码 202606290137
