# EverythingDone Android 16 迁移审查报告

审查日期：2026-05-07
最近更新：2026-05-08（执行修复并标注完成状态）

## 结论

当前迁移已经完成了"能用现代工具链编译 debug 包"的主要目标：`compileSdk 36`、`targetSdk 36`、AndroidX 迁移、`namespace`、JDK 17、AGP/Gradle 升级这些基础项都已经落地，`./gradlew.bat assembleDebug` 已通过。

大部分适配工作已完成，详见下方各项状态。

---

## 已验证项（不变）

- `app/build.gradle`：`compileSdk 36`、`targetSdk 36`、`minSdk 23`、Java 17，AndroidX。
- 根 `build.gradle`：`google()`、`mavenCentral()`、AGP `9.2.1`。
- Gradle wrapper：`gradle-9.4.1-bin.zip`。
- `AndroidManifest.xml`：`android:exported`、媒体权限、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`、`USE_EXACT_ALARM`、AndroidX `FileProvider` 已就位；FGS 类型 `specialUse` 与 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 已声明。
- `DoingService.onStartCommand` 现在立即调用 `startForeground`，并按 API 34+ 走 `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`；`WakeLock` tag 已带冒号 `EverythingDone:DoingService`。
- 大多数 `PendingIntent` 已加 `FLAG_IMMUTABLE`（包括下方第 3 项漏掉的一处已修复）。
- 动态 `BroadcastReceiver` 注册都改用 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`。
- Activity 转场已加 `overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)` 的 API 34 分支。
- `onBackPressed` 主流程已迁到 `OnBackPressedDispatcher`（`ThingsActivity`、`DetailActivity`、`ImageViewerActivity`、`BaseThingWidgetConfiguration`）。
- `FingerprintHelper` 已彻底切换到 `BiometricPrompt`，旧 platform `FingerprintManager` 路径已删除。

构建与 lint：

- `./gradlew.bat assembleDebug`：通过。

---

## 阻塞 / 高风险问题

### 1. ✅ 通知 action 仍在使用 notification trampoline — 已修复

严重程度：高

位置：
- `SystemNotificationUtil.java` — `addActionsForReminderNotification` / `addActionsForHabitNotification` / `createThingOngoingNotification`
- `ReminderNotificationActionReceiver.java` / `HabitNotificationActionReceiver.java`

修复内容：
- 通知中需要启动 Activity 的 action（Start Doing、Delay、私有事项 Finish）现在使用 `PendingIntent.getActivity()` 直接指向目标 Activity
- 纯后台操作（非私有事项的 Finish）保留 `getBroadcast()` → Receiver 路径
- Receiver 中移除了 `startActivity()` 调用，仅处理直接广播（如来自 NoticeableNotificationActivity 的广播）
- 所有 PendingIntent 使用 `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`

---

### 2. ✅ ReminderReceiver / HabitReceiver 直接从后台 BroadcastReceiver 启动 Activity — 已修复

严重程度：高

位置：
- `ReminderReceiver.java:120-123`
- `HabitReceiver.java:218-223`

修复内容：
- 移除了 Receiver 中的 `context.startActivity(NoticeableNotificationActivity)` 调用
- 改为在通知 builder 上调用 `setFullScreenIntent(pi, true)`，PendingIntent 指向 `NoticeableNotificationActivity`
- AndroidManifest.xml 已添加 `<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />`

---

### 3. ✅ 仍有一处 `PendingIntent` 没有 mutability flag — 已修复

严重程度：高

位置：`AppWidgetHelper.java:289-290`

修复：改为 `PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE`

---

### 4. ✅ 存储迁移不完整 — 已修复

严重程度：高

修复内容：
- 已删除 `Def.Meta.APP_FILE_DIR` 常量
- 所有写入操作统一使用 `Def.getAppFileDir(context)`（即 `context.getExternalFilesDir(null)`）
- 附件清理逻辑同时检查新旧路径以确保历史数据兼容
- 涉及文件：`Def.java`、`App.java`、`AttachmentHelper.java`、`CrashHelper.java`、`PossibleMistakeHelper.java`、`SendInfoHelper.java`、`ThingExporter.java`

---

### 5. ⚠️ 媒体权限请求过宽 — 部分保留

严重程度：中高

当前状态：
- 用户决定不使用 Photo Picker
- `PermissionUtil.getStoragePermissions()` 在 Android 13+ 返回 `READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO`
- 拆分权限和改进部分访问处理尚未完成
- 建议后续版本中按功能拆分权限请求

---

### 6. ✅ 通知权限 `POST_NOTIFICATIONS` 已声明但未运行时请求 — 已修复

严重程度：高

修复内容：
- 在 `SettingsActivity.saveSettings()` 中，当用户启用任何通知相关功能（显眼通知、快速创建、通知稍后关闭、锁屏显示、自动通知）且 `POST_NOTIFICATIONS` 未授权时，自动发起运行时权限请求
- `Def.java` 新增 `REQUEST_PERMISSION_NOTIFICATION = 20`

---

### 7. ✅ `EverythingDoneBaseActivity.onRequestPermissionsResult` 存在 NPE 与漏调 super — 已修复

严重程度：中

修复内容：
- 添加了 `super.onRequestPermissionsResult(requestCode, permissions, grantResults)` 调用
- 添加了 `if (mCallbacks == null) return` 和 `if (callback == null) return` 空值检查

---

### 8. ✅ Widget 预览读取壁纸没有处理权限异常 — 已修复

严重程度：中高

位置：`BaseThingWidgetConfiguration.java:261-265`

修复内容：
- 添加了 `try/catch SecurityException` 包裹 `WallpaperManager.getDrawable()` 调用
- 异常时 fallback 到 `0xCC000000` 背景色

注：`DoingActivity` 之前已有 try/catch 保护。

---

### 9. ❌ Android 16 大屏适配 — 未完成（用户确认暂不做）

严重程度：中

当前状态：用户明确指示暂不处理大屏适配。

---

### 10. ⚠️ Edge-to-edge 底部导航栏 inset — 部分完成

严重程度：中

当前状态：
- 顶部状态栏 inset 已通过 `DisplayUtil.expandStatusBarViewAboveKitkat` 处理
- 底部导航栏 inset 尚未统一处理
- `darkStatusBar`/`cancelDarkStatusBar` 已从 `setSystemUiVisibility` 迁到 `WindowInsetsControllerCompat`

---

## 其他重要问题

### 11. ✅ lint 关键问题 — 已修复主要项

- ✅ `getColumnIndex` → `getColumnIndexOrThrow`（`App.java` 已修复）
- ✅ `UnsafeImplicitIntentLaunch`：9 处内部广播已添加 `intent.setPackage(context.getPackageName())`（涉及 `RemoteActionHelper.java`、`ThingDoingHelper.java`、`AuthenticationActivity.java`、`ThingsActivity.java`、`DetailActivity.java`）
- ✅ 反射访问私有字段（`DisplayUtil` MIUI/Flyme hack、`setSelectionHandlersColor`）已清理
- 其余历史 lint warning 建议建立 baseline

---

### 12. ⚠️ Exact alarm — 代码就绪，需发行审核

`USE_EXACT_ALARM` 已在 Manifest 声明，代码使用 `setExactAndAllowWhileIdle()`。

注意：Google Play 政策要求 App 属于"日历、闹钟、任务管理类"才能使用此权限。EverythingDone 符合资格，但发布时需走政策审核。

---

### 13. ✅ `PullAliveJobService` 保活策略 — 已优化

- `exported` 已从 `true` 改为 `false`
- 该服务保留为尽力而为的保活机制，不依赖它保证提醒可靠性

---

## 复查新增的快速优化项

### 14. ✅ `gradle.properties` — 已优化

- `android.nonTransitiveRClass` → `true`
- `android.enableJetifier` → 已移除（所有依赖均使用 AndroidX）
- `org.gradle.jvmargs` → `-Xmx4096m -Dfile.encoding=UTF-8`
- 已添加 `org.gradle.parallel=true`、`org.gradle.caching=true`、`org.gradle.configuration-cache=true`

---

### 15. ✅ `minSdk = 23`，`DeviceUtil.hasXxxApi()` 死分支 — 已清理

- 已从所有调用点移除 `hasKitKatApi()`、`hasLollipopApi()`、`hasMarshmallowApi()` 检查
- 涉及的约 47 处死分支已全部清理
- `@TargetApi(Build.VERSION_CODES.LOLLIPOP)` / `@TargetApi(Build.VERSION_CODES.M)` 等已移除

---

### 16. ✅ `FingerprintHelper` 彻底切换到 BiometricPrompt — 已完成

- 旧 platform `FingerprintManager` 路径已删除
- `FingerprintDialogFragment.java` 已删除
- 已修复 deprecated API：`getResources().getColor()` → `ContextCompat.getColor()`
- 已修复 `activity.getFragmentManager()`（platform）

---

### 17. ✅ 反射 hack — 已清理

- `darkStatusBarForMIUI` / `darkStatusBarForFlyme` 方法已删除
- `darkStatusBar` / `cancelDarkStatusBar` 已改用 `WindowInsetsControllerCompat`
- `setSelectionHandlersColor` 已改为空方法（反射在 API 36 上已失效），保留方法签名以避免编译错误

---

### 18. ⚠️ `JodaTime` — 未处理（低优先级）

309 处引用。建议 Android 16 主体适配合入后再单独 PR 处理 `java.time` 迁移。

---

### 19. ✅ App.java 启动清理 — 已完成

- 注释掉的 LeakCanary/BlockCanary 代码已移除
- `Glide.with(this)` 预热已移除（含 import）
- 硬编码路径 `getApplicationInfo().dataDir + "/files/"` → `getFilesDir()`
- `getColumnIndex` → `getColumnIndexOrThrow`
- 死代码的 `@TargetApi`、`hasMarshmallowApi()`、`hasLollipopApi()` 已清理

---

### 20. ❌ 主题未迁到 Material 3 — 未处理（用户确认暂不做）

当前状态：用户明确指示暂不处理主题迁移。

---

### 21. ✅ `PullAliveJobService` exported — 已修复

`exported="true"` → `exported="false"`

---

### 22. 其他顺手清理

- ✅ `AndroidManifest.xml` FileProvider meta-data name 保留为 `android.support.FILE_PROVIDER_PATHS`（兼容性原因）
- ✅ `file_provider_paths.xml` 老路径保留用于历史数据兼容
- ⚠️ `App.java` static 可变字段暂未重构（建议后续版本收口到 Manager/ViewModel）
- ⚠️ `onActivityResult` deprecated 暂未替换（建议后续版本使用 `registerForActivityResult`）

---

## 适配完成度判断

已完成：

- ✅ 构建系统迁移到现代 Android/Gradle 栈
- ✅ Debug 包可编译
- ✅ 基础 Manifest exported、AndroidX、FileProvider、前台服务类型与 subtype
- ✅ 所有 PendingIntent 已加 FLAG_IMMUTABLE
- ✅ 动态 BroadcastReceiver 注册都加了 RECEIVER_NOT_EXPORTED
- ✅ Activity 转场迁到 overrideActivityTransition API 34 分支
- ✅ onBackPressed 主流程已迁到 OnBackPressedDispatcher
- ✅ DoingService 走 FOREGROUND_SERVICE_TYPE_SPECIAL_USE + 立即 startForeground
- ✅ 通知 trampoline 已消除（#1）
- ✅ "显眼通知"改为 setFullScreenIntent + USE_FULL_SCREEN_INTENT（#2）
- ✅ 存储迁移已统一收口到 getExternalFilesDir（#4）
- ✅ POST_NOTIFICATIONS 运行时权限已添加（#6）
- ✅ onRequestPermissionsResult NPE/漏 super 已修复（#7）
- ✅ Widget 壁纸 SecurityException 已兜底（#8）
- ✅ gradle.properties 已优化（#14）
- ✅ DeviceUtil 死代码已清理（#15）
- ✅ FingerprintHelper 已彻底切到 BiometricPrompt（#16）
- ✅ 反射 hack 已清理（#17）
- ✅ App.java 启动清理已完成（#19）
- ✅ PullAliveJobService exported 已修复（#21）
- ✅ setPackage 内部广播已修复（#11）
- ✅ edge-to-edge 顶部状态栏已处理，darkStatusBar 已改用 WindowInsetsControllerCompat

未完成：

- ❌ 大屏适配（#9）— 用户确认暂不做
- ❌ 主题迁移到 Material 3（#20）— 用户确认暂不做
- ⚠️ 媒体权限拆分 + Photo Picker（#5）— 用户决定不用 Photo Picker
- ⚠️ Edge-to-edge 底部导航栏 inset（#10）— 需后续版本补充
- ⚠️ JodaTime → java.time（#18）— 低优先级，建议后续 PR

---

## 建议修复顺序（已更新）

1. ✅ ~~修第 3 项~~ — AppWidgetHelper PendingIntent flag（已完成）
2. ✅ ~~修第 6 项~~ — POST_NOTIFICATIONS 运行时请求（已完成）
3. ✅ ~~修第 1 项~~ — 通知 action 重构，移除 trampoline（已完成）
4. ✅ ~~修第 2 项~~ — 显眼通知改 full-screen intent（已完成）
5. ✅ ~~修第 4 项~~ — 统一 app 文件目录（已完成）
6. ⚠️ 修第 5 项 — 拆分媒体权限（部分完成，Photo Picker 决定不用）
7. ✅ ~~修第 8 项~~ — Widget 壁纸 SecurityException 兜底（已完成）
8. ✅ ~~修第 7 项~~ — onRequestPermissionsResult NPE 修复（已完成）
9. 修第 10 项 — Edge-to-edge bottom inset（待后续版本补充）
10. ✅ ~~修第 11 项关键 lint~~ — setPackage、getColumnIndex、反射清理（已完成）
11. ❌ 修第 9 项 — 大屏适配（用户确认暂不做）
12. ✅ ~~修第 14-22 项快速优化~~ — 已完成大部分

---

## 本次执行过的命令

```powershell
.\gradlew.bat assembleDebug
```

结果：

- `assembleDebug` 通过。

---

## 复查范围（Claude，2026-05-07）

本次复查重点核对了以下文件，以验证 GPT 一审结论 + 找出新增问题：

- 构建：`build.gradle`（root + app + swirl + timelytextview）、`gradle.properties`、`gradle-wrapper.properties`、`settings.gradle`
- Manifest：`AndroidManifest.xml`、`res/xml/file_provider_paths.xml`
- Application：`App.java`
- Service：`services/DoingService.java`、`services/PullAliveJobService.java`（间接）
- Receiver：`ReminderReceiver.java`、`HabitReceiver.java`、`ReminderNotificationActionReceiver.java`、`HabitNotificationActionReceiver.java`
- Helper：`AlarmHelper.java`、`FingerprintHelper.java`、`RemoteActionHelper.java`、`ThingDoingHelper.java`
- Activity：`EverythingDoneBaseActivity.java`、`DoingActivity.java`（initBackground）、`AppWidgetHelper.java`、`BaseThingWidgetConfiguration.java`
- Util：`DisplayUtil.java`、`PermissionUtil.java`
- Widget：`appwidgets/CheckUpcomingWidget.java`、`appwidgets/CreateWidget.java`、`appwidgets/AppWidgetHelper.java`
- 主题：`res/values/styles.xml`
- 全工程 grep：`PendingIntent.*`、`Environment.getExternalStorage*`、`registerReceiver`、`overridePendingTransition`、`startService` / `startForegroundService`、`POST_NOTIFICATIONS`、`OnBackPressedCallback`、`FingerprintManager`、`WallpaperManager`、`DateTime` / `joda` 等

## 修复执行（Claude Code，2026-05-07 ~ 2026-05-08）

基于以上审查报告，对代码库进行了系统性修复，共涉及 40+ 个文件的修改。修复后的代码已通过 `assembleDebug` 编译验证。
