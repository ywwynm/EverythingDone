# EverythingDone 项目综合审计报告

日期：2026-07-11 · 基线：`versionName 2.0.0` / `versionCode 43` · `minSdk 26` / `targetSdk 36` / Java 17 · 全 Kotlin（230 文件约 8.2 万行）

## 报告说明

本报告由六个维度的并行只读审计汇总而成：系统架构与构建、数据模型与持久化、Android 平台合规与可靠性、UI/UX 实现、代码质量与测试、功能完整性与冲突（含一份逐行核验的功能冲突深度报告）。每条结论均要求带 `路径:行号` 证据；其中最重的若干 P0/P1 结论已单独用工具复核确认。

**严重程度约定**：P0 = 安全定性缺陷或用户数据丢失；P1 = 严重正确性/可靠性/隐私问题；P2 = 中等；P3 = 低/技术债。

**先纠正三处常见误解**（审计中已核实）：
1. 工具链不旧。AGP 9.2.1 + Gradle 9.4.1 + compileSdk 36 + Java 17 均为当前版本。
2. `ThingCardAppearance` 等外观模型用手写 `org.json`（显式字符串键）序列化，不走 Gson 反射，不受 R8 混淆影响。Gson 仅用于 debug 更新与诊断日志。
3. 私密内容在**通知、AppWidget、分享/截图**上均已正确遮蔽（逐面核验，见第一章）——这些不是泄露点。真正的私密泄露只有搜索一处。

---

## 执行摘要：最重要的问题

| 编号 | 严重 | 问题 | 证据 |
|------|------|------|------|
| SEC-1 | P0 | 「私密」只是 UI 门禁：私密正文 DB 明文、图案口令明文存 prefs 且明文比对、指纹仅门禁不加密 | `SettingsActivity.kt:1150`、`AuthenticationHelper.kt:37-45`、`ThingPrivacyResolver.kt:36-39` |
| SEC-2 | P0 | 备份是明文未加密 ZIP，打包完整 DB + 含明文口令的 prefs；`allowBackup=false` 使其成为唯一迁移途径，外泄即全量明文泄露 | `BackupHelper.kt:49,158-169` |
| DATA-1 | P0 | 备份不含附件文件，且附件在 DB 中存绝对路径——恢复/换机后所有图/音/视频引用悬空，静默丢失全部媒体 | `BackupHelper.kt:158-169`、`Def.kt:18`、`AttachmentHelper.kt:223` |
| DATA-2 | P1 | 数据库写入路径静默吞异常：事务体抛错→回滚→方法却正常返回，调用方以为已保存 | `ThingDAO.kt:422-427,508-511` |
| REL-1 | P1 | 错过的提醒静默丢失、无补偿：过期提醒仅置 `EXPIRED` 不补发 | `AlarmHelper.kt:139-141`、`ReminderReceiver.kt:78` |
| DATA-3 | P1 | `onUpgrade` v1–8 段无 `columnExists` 守卫、无 try/catch，某些老库大跨度升级重复加列崩溃→回滚→每次启动崩→只能重装 | `DBHelper.kt:56-94` |
| ARCH-1 | P1 | `doingThingId` 是内存静态、进程重建后不恢复，约 40 处判定失真（卡片高亮、右滑守卫、自动开始专注、widget 态） | `App.kt:398,493-499` |
| REL-2 | P1 | 开机/解锁/升级用裸 `Thread` 重排闹钟、无 `goAsync`，进程被杀则闹钟只重建一部分 | `BootReceiver.kt:24`、`UserPresentReceiver.kt:23`、`AppUpdateReceiver.kt:22` |
| DATA-4 | P1 | 永久删除孤儿：删文件夹/批量删只清 `things`，残留 reminders/habits/records 与附件文件 | `ThingFolderDAO.kt:519-539`、`ThingManager.kt:1649-1725` |
| QA-1 | P1 | 手写 21 版本 SQLite 迁移 + 整个数据层零测试 + 无 CI，用户数据最脆弱环节无任何自动化防线 | `app/src/test` 仅 8 个音频波形测试 |
| PRIV-1 | P1 | 根/文件夹 scope「全部完成/删除/永久删除」对私密子内容未接入鉴权 | `docs/features/private-content/decisions.md:159` |

---

## 一、安全与隐私

这是本次审计最需要正视的一类问题：应用向用户承诺了「私密 / 锁」，但实现只是展示层遮挡，安全预期与实际严重不符。

- **SEC-1 [P0] 私密体系整体是 UI 门禁，无任何 at-rest 加密。** 私密性仅靠标题前缀 `PRIVATE_THING_PREFIX` 标记（`ThingPrivacyResolver.kt:36-39`、`Thing.kt:235-237`），正文明文存 `things.content`；图案锁口令明文写入 SharedPreferences（`SettingsActivity.kt:1150` `putString(KEY_PRIVATE_PASSWORD, pldf.getPassword())`），全项目 20+ 处以明文相等比对校验（`AuthenticationHelper.kt:37-45`，`correctPassword==null` 直接放行）；生物识别（BiometricPrompt + Keystore）仅作门禁，从不加密任何 Thing 数据。**影响**：能访问应用数据目录者（root / adb / 文件管理 / 备份）可直接读全部私密明文并读出明文口令。**建议**：私密内容做 Keystore 派生密钥的 at-rest 加密、口令改加盐哈希；短期至少在 UI/文档明示「私密仅为遮挡、非加密」。

- **SEC-2 [P0] 备份是明文未加密 ZIP。** `BackupHelper.kt:49` `zipDirectory(...)` 无任何 `Cipher`，`getBackupFilePaths()`（:158-169）原样打包完整 SQLite 库 + 4 个 `shared_prefs/*.xml`（含存口令的 `PREFERENCES_NAME`，已核实该 xml 在打包列表内）。叠加 `allowBackup="false"`（`AndroidManifest.xml:57`），手动备份是用户唯一的数据迁移途径。**影响**：`.bak` 落到云盘/共享存储后，任何人解压即得全部（含私密）记事明文与明文口令，与 SEC-1 构成完整泄露链。**建议**：备份用用户口令派生密钥加密，或至少加密敏感字段。

- **PRIV-1 [P1] 根/文件夹 scope「全部完成/删除/永久删除」对私密子内容未鉴权。** 私密鉴权目前只覆盖长按选中路径；`docs/features/private-content/decisions.md:159` 明确记录此为待办。**影响**：从根或文件夹 overflow 一键「全部…」可越过认证批量改动甚至永久删除私密内容，隐私绕过 + 误删双重风险。**建议**：把集中鉴权判定接入这些 scope 的批量确认框。

- **PRIV-2 [P2] 全文搜索会匹配私密 Thing 的受保护正文，构成信息泄露 oracle。** `ThingSearchHelper.searchableContent` 返回 raw `thing.content` 无隐私检查（:44-51），而 `searchableTitle` 却刻意剥离前缀（:39-41）——不对称。**影响**：应用打开时输入只存在于私密正文里的词，会浮现该私密卡片，从而确认词的存在，与应用其余处严格的内容保护不一致。威胁模型为持机在手（on-device），故 P2。**建议**：`searchableContent` 按 `isEffectivelyPrivate` 返回空或仅标题；若属有意设计则加注释与文档。

- **PRIV-3 [P2] 私密前缀编码是系统性泄露架构债。** 「位于私密文件夹内、自身无前缀」的记事，凡只查 `thing.isPrivate()` 而不现算「有效私密」的界面都会明文泄露；历史上 widget、通知都曾泄露、现已集中修补，但仍是逐界面 opt-in。**影响**：任何新增展示/通知界面若忘记走 `ThingPrivacyResolver` 即再次泄露。**建议**：落实「前缀 → 真实布尔列 + 现算有效私密」的重构（已记 ADR/followup）。

- **已正确处理（非泄露，逐行核验，勿误报）**：通知经 `newGeneralNotificationBuilder` 用 `isEffectivelyPrivate` 换占位内容、清附件、抑制大图（`SystemNotificationUtil.kt:112-116,150-152,428-430`），Reminder/Habit/AutoNotify/Ongoing 全走此 builder；AppWidget 渲染前先 resolve 有效私密，再逐 content surface 守卫 `isPrivate`（`AppWidgetHelper.kt:2104,2122,2219,2250,2280,2315`、`ThingsListWidgetService.kt:86`）；分享/截图只能从需认证的 `DetailActivity` 到达。

---

## 二、数据完整性与持久化

数据层是多年迭代的 SQLite + 单例 DAO，功能可用，欠账集中在健壮性而非日常正确性，但其中几处是实打实的数据丢失/损坏风险。

- **DATA-1 [P0] 备份丢附件 + 附件绝对路径。** 见执行摘要。附件存于 external files 目录、DB 存绝对路径（`AttachmentHelper.kt:223`、`Def.kt:18`），而备份只打包 DB + prefs。**建议**：备份纳入 external 附件目录；附件引用改相对句柄（与同步的内容哈希寻址统一）。历史上还有视频附件直接引用 `/DCIM/Camera/VID….mp4` 公共路径（`docs/features/hdr-media-display/followups.md`），源文件删除即失效且不入备份。

- **DATA-2 [P1] 数据库写入静默吞异常。** `ThingDAO.kt:422-427` 与 `:508-511`：`catch (e: Exception) { e.printStackTrace() } finally { db!!.endTransaction() }`。事务体（`updateState`/`updateLocations` 循环）抛错时未到 `setTransactionSuccessful()`，事务回滚，但方法正常返回、未回传失败。**影响**：批量状态变更/重排可能悄无声息回滚，UI 与调用链却认为已保存。**建议**：写入失败向上抛或返回布尔并由调用方处理；关键路径补集成测试。

- **DATA-3 [P1] 迁移旧版本段无守卫、无 try/catch。** `DBHelper.kt:56-94` 对 v1–8 用 `when(oldVersion)` 命中单分支，多分支重复执行无 `columnExists` 守卫的 `ALTER TABLE`（如 v7 的 `SQL_ADD_COLUMN_TYPE_HABIT_RECORD`/`SQL_ADD_COLUMN_BACKGROUND_THINGS`）；v9 起才改用 `if (oldVersion < N)` + `columnExists`；整个 `onUpgrade` 无 try/catch。**影响**：某些历史库大跨度升级时重复加列抛异常，`onUpgrade` 处于事务内 → 回滚 → 每次启动都崩 → 只能重装（不可逆数据事故）。**建议**：旧段也改幂等守卫；一并厘清 `onDowngrade`（:428-433 反向跑 upgrade）语义。

- **DATA-4 [P1] 永久删除孤儿。** `ThingFolderDAO.deleteForever`（:519-539）与批量 `ThingManager.changeFolderSubtreeContentState`（:1649-1725）只删 `things`/`thing_folders`，不清 `reminders`/`habits`/`habit_records`/`habit_reminders` 与附件文件（habit 系列仅 `DetailActivity` 单条删除路径清理，`App.releaseResourcesAfterDeleteForever` 只清 reminder 不清 habit）。**影响**：残留数据 + 附件文件；一期同步全量上云会传播垃圾，叠加 id 复用可致错误关联。**建议**：统一「永久删除清理」入口，按 thingId 级联清 reminder/habit/record/app_widget + 附件文件。

- **DATA-5 [P2] 6 个 DAO 各开一条 writable 连接，无 WAL、跨表写无原子性。** 各 DAO `init` 里 `helper.writableDatabase`（`ThingDAO.kt:43`、`ReminderDAO.kt:28`、`HabitDAO.kt:36`、`ThingFolderDAO.kt:22`、`DoingRecordDAO.kt:24`、`AppWidgetDAO.kt:24`），指向同一库文件的 6 条独立连接。**影响**：删记事与删其提醒不在同一事务，中途失败留半成品；跨连接并发写可 `SQLITE_BUSY`。**建议**：共享单连接或开 WAL，跨表写入同一事务。

- **DATA-6 [P2] 恢复是对存活 dataDir 逐文件裸覆盖，无版本校验、非原子、DB 连接仍打开。** `BackupHelper.restore` 解压后 `copyFilesInDirTo(unzippedDir, dataDir)`（:115）；无格式版本号、无 DB 版本匹配检查，中途 IOException 即半新半旧，且 DAO 仍持旧连接。恢复更高 schema 的备份到旧 App 触发 `onDowngrade`。**建议**：恢复前关闭所有 DAO 连接、恢复后重启进程；备份加版本号；用临时库替换 + 校验成功再原子改名。

- **DATA-7 [P2] 首页热点查询无索引。** `onCreate` 只建了 folder_id / parent_folder_id 两个索引（`DBHelper.kt:496-503`）；核心 `getThingsCursorForDisplay` 按 `type`、`state` 过滤并 `order by location desc`（`ThingDAO.kt:632-641`）无索引，`reminders` 无索引，`deleteHabitRecords` 按 `habit_id=` 删无索引。**影响**：记事量大时全表扫 + filesort 卡顿。**建议**：加 `things(state,type)`、`things(location)`、`habit_records(habit_id)`、`reminders` 相关索引。

- **DATA-8 [P3] 其余数据层健壮性债**：DAO 单例是无 `@Volatile` 的双检锁且构造函数里做磁盘 IO（`ThingDAO.kt:672`，可能发布半构造对象）；旧方法游标未用 `use{}`，异常路径泄漏 Cursor（`getThingById`/`recreateHeader`/`getMin/MaxThingLocation`/`ReminderDAO`）；附件/清单用自定义分隔符（`` `启q琼 `` 等，`strings.xml:6-7`）拼串且**无转义**，用户内容含该 4 字符即破坏 `split`（`AttachmentHelper.kt:106,66`、`CheckListHelper.kt:139`）；`create()`/`updateHeader()` 在 `SQLiteConstraintException` 上无界递归；`app_widget` 外键定义写反且从未启用（`DBHelper.kt:558-563`）；`FrequentSettings` 用非同步 `HashMap` 且被后台线程访问、`put()` 只写内存缓存需各处另写 SP（`FrequentSettings.kt:21,67-70`）。

---

## 三、提醒可靠性与平台合规

提醒是本应用命脉，主链路设计其实相当扎实（见「值得肯定」）。以下是净新增/未闭环项——Android 12–16 的高危变更（通知 trampoline、FGS 类型、`RECEIVER_NOT_EXPORTED`、`FLAG_IMMUTABLE`、后台启动限制）已在 `android-16-migration` 中系统闭环，不重复计入。

- **REL-1 [P1] 错过的提醒无补偿。** 开机/解锁重排时对已过期提醒仅置 `EXPIRED` 写库、不补发（`AlarmHelper.kt:139-141`、`ReminderReceiver.kt:78`），`BootReceiver` 只重排未来闹钟。**影响**：设备关机或长时间被杀跨过提醒点后，该提醒永久静默丢失、无「已错过」提示。**建议**：开机/恢复时扫描 `triggerTime < now 且未完成` 的提醒，补发一次通知或提供「已错过」聚合入口。

- **REL-2 [P1] 开机/解锁/升级用裸 `Thread` 重排闹钟。** `BootReceiver.kt:24`、`UserPresentReceiver.kt:23`、`AppUpdateReceiver.kt:22` 均 `Thread { AlarmHelper.createAllAlarms(...) }.start()`，无 `goAsync()`/`PendingResult` 保护。`onReceive` 返回后系统即可回收进程，`createAllAlarms` 遍历游标 + 多次 `AlarmManager` 调用（`AlarmHelper.kt:124-156`）可能半途中断。**影响**：正是命脉——重启/升级后闹钟可能只重建一部分，直到冷启动自愈 / `USER_PRESENT` / 4h `AlarmHealthWorker` 才补齐；目标用户多为后台管理激进的国产 ROM。**建议**：`goAsync()` 拿 `PendingResult` 后台结束再 `finish()`，或委托 `WorkManager` 一次性任务。

- **REL-3 [P2] `DetailActivity` 无 `onSaveInstanceState`，草稿仅靠 `KEY_AUTO_SAVE_EDITS` 开关兜底。** `onPause` 内需该开关开启才 `saveAfterOnPause()`（`DetailActivity.kt:2208`），全文件无 `onSaveInstanceState`。**影响**：关闭「自动保存」时，编辑中的记事切后台被系统杀进程 → 未保存内容丢失（`configChanges` 已覆盖旋转，故仅进程死亡暴露）。**建议**：实现 `onSaveInstanceState` 持久化标题/正文/附件草稿并在重建时恢复。

- **REL-4 [P2] `DailyUpdateHabitReceiver` 无谓 `exported="true"` 且主线程同步 DB。** `AndroidManifest.xml:198-201`（无 intent-filter 却 exported）；`onReceive` 直接游标遍历所有习惯并逐个刷新 widget、无子线程（对照 `DailyCreateTodoReceiver` 就是 false）。**影响**：外部应用可显式组件触发它、滥推习惯状态更新（数据完整性滥用面）；习惯多时逼近广播 ANR（10s）。**建议**：改 `exported="false"` + 后台线程。

- **REL-5 [P2/政策] 分发合规备案项**：`USE_EXACT_ALARM`（`AndroidManifest.xml:37`）、媒体权限 `READ_MEDIA_IMAGES/VIDEO/AUDIO` 且未声明 `READ_MEDIA_VISUAL_USER_SELECTED`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + 十余厂商自启动跳转（`NotificationReliabilityHelper.kt:186-231`）——经阿里云 sideload 分发不受限，若上 Google Play 需政策审核与说明。`setAlarmClock` 本身不需 `USE_EXACT_ALARM`，可评估收敛。

- **REL-6 [P3] 冗余与遗留**：`PullAliveJobService`（30 分钟 JobScheduler）与 `AlarmHealthWorker`（4h WorkManager）职责重复且每次 `onCreate` 重排（`App.kt:236-244,146-147`），JobScheduler 周期在 Android 12+ 受限，可下线；`DoingService` 的 `WakeLock.acquire()` 无超时（`DoingService.kt:141`）；`FileProvider` 映射整个外部存储根（`file_provider_paths.xml:11`）；遗留 API：`AsyncTask` 9 处、`startActivityForResult` 多处、无类型 `getParcelableExtra` 14 处、`Environment.getExternalStorageDirectory()` 旧路径读取。

---

## 四、系统架构与代码质量

主要债务集中在架构层：超大 God class + 以 `App` 静态字段与广播为骨架的隐式状态耦合 + 全 Kotlin 却零协程、无 Repository/ViewModel，使 UI 直连数据库、难测试难维护。

- **ARCH-1 [P1] `doingThingId` 内存静态、进程重建后失真。** `App.kt:398` `private var doingThingId: Long = -1`，get/set（:493-499）只读写内存，`App.onCreate` 不回填。约 40 处调用点（卡片高亮、右滑完成守卫、自动开始专注、widget 态、`DoingService`）依赖它。**影响**：进程被杀后 `DoingService` 以 START_STICKY 重启时 `doingThingId` 已是 -1，`ReminderReceiver.kt:56` 等判定失效，可能误对另一记事自动开始专注，与前台通知/widget 不一致。**建议**：落 SharedPreferences 或以 `DoingRecordDAO` 进行中记录为唯一真源，在 `App.onCreate`/`DoingService.onCreate` 回填。

- **ARCH-2 [P1] God class 群 + UI 直连 DB。** `ThingsActivity.kt` 12293 行（约 650 fun、145 字段，把卡片外观编辑器、搜索、取色、抽屉/文件夹、选择模式、拖拽、沉浸滚动、私密认证整块内联）、`DetailActivity.kt` 5639 行（272 函数，33 处直接取 DAO 单例）、`BaseThingsAdapter.kt` 3420 行（onBind 扇出 16 个方法约 2800 行绑定树）、`ThingManager.kt` 1908 行（单例持 `mThings` 内存副本 + 多 DAO + Executor + 撤销状态）。**影响**：单文件承载过多变更原因，回归与合并冲突面极大，UI 与持久层直连无法单测，`mThings` 与 DAO 直写并存易发散。**建议**：抽 Repository（封装 DAO）+ 用例层，把编辑器/抽屉/拖拽/选择模式拆为独立控制器或 Fragment，adapter 只做渲染。

- **QA-1 [P1] 数据层零测试、无 CI。** `app/src/test/` 仅 8 个测试文件全在 `views/recording/`（音频波形纯数学），`androidTest/` 为空，`.github/` 无 workflows。数据层/迁移/Activity/Adapter/Widget/通知闹钟零测试；根因是 `ThingManager`/`ThingDAO` 为 `private constructor` + `getInstance(context)` 的 Context 绑定静态单例、无接口抽象，只有纯数学可被单测。**影响**：手写 21 版本迁移这一最脆弱环节无任何自动化防线。**建议**：为迁移建内存 SQLite 回归测试；数据层引入接口以便替身；接入基本 CI。

- **QA-2 [P1] Kotlin 迁移遗留 `set*Listener` 非空签名。** `docs/features/kotlin-migration/followups.md`（N1 规则）：迁移把可空回调参数转成非空，Java 侧传 null 注销时 NPE，已发生一次生产崩溃（新建记事高亮动画），仅局部修复、全局未清扫。**建议**：全局审计 `set*Listener`/`set*Callback` 参数改可空。

- **QA-3 [P2] `!!` 非空断言泛滥（5142 处 / 160 文件），仅 4 处 `lateinit`。** `ThingsActivity` 1060、`DetailActivity` 579、`ThingManager` 205；`FileUtil` 对可空入参一律 `str!!`。迁移把 Java 可空字段整体转 `Type? = null` + `!!`，把编译期空安全退化成运行期崩溃面。**建议**：热点类优先改 `lateinit`/构造注入/`?.` + 早返回。

- **QA-4 [P2] 崩溃处理器自身可能崩溃、无上报。** `CrashHelper.kt:71-79` 用 `mApplication!!.openFileOutput` / `App.getApp()!!` 却只 `catch (IOException)`，NPE 会逃逸 `uncaughtException`；机制为本地写 `crash_*.log`、无上报 SDK；`sCrashHelper` 双检锁但字段非 `@Volatile`。**建议**：处理器内全程 `catch (Throwable)` + 字段加 `@Volatile` + 轻量上报或引导回传日志。

- **QA-5 [P2] 日志与诊断卫生。** `Log.*` 123 处 / 25 文件，`proguard-rules.pro` 无 `-assumenosideeffects Log`，release 保留全部日志；`VideoCoverPreviewManager.kt:77` `const val DEBUG_LOG = true` 硬编码（非 `BuildConfig.DEBUG`），主流视频封面流程持续向 `/debug_logs` 落盘，且 `ThingsAdapter.kt:865` 关闭态日志会把 `folder.title`（用户内容）写盘。**建议**：`DEBUG_*` 常量改由 `BuildConfig.DEBUG` 驱动，`DebugFileLogger` 内部兜底门控，加 `-assumenosideeffects` 剥离 `Log.d/v/i`。

- **QA-6 [P2/P3] 零协程与超长方法。** 全工程无 `kotlinx-coroutines`，并发靠 9 个 `AsyncTask`（均非静态 inner class 持 Activity 引用）+ 裸 `Thread` + 分散的 `Executor`，旋转后不取消易泄漏 Activity；最长方法 `DetailActivity.createMediaCropAppearanceDialogContent`（327 行）、`FableSolRealtimeAnalyzer.process`（295）、`VoiceVisualizer.onDraw`（283，绘制热路径）、`BaseThingsAdapter.loadThingCardImage`（273）。**建议**：迁移到协程 + `lifecycleScope`，耗时任务用已依赖的 `WorkManager`；`onDraw` 计算前置。

- **BUILD-1 [P2/P3] 构建与仓库卫生。** 无 `signingConfigs`——`release` 开了 `minifyEnabled`/`shrinkResources` 却无签名配置、也无 `publishRelease` 任务，release 变体既未签名也从未被实际构建（意味着混淆规则从未验证）；约 100 行 ssh/scp 部署逻辑写死进 `app/build.gradle:207-303`（`publishDebugUpdate`）；内嵌库 `timelytextview`/`swirl`（Apache-2.0，逐文件许可头已保留）缺仓库级 LICENSE/NOTICE 副本；死配置 `fileTree(dir:'libs')` 无 libs 目录、`proguard-rules.pro` 保留已无依赖的 JodaTime keep；无 `kotlinOptions`/`lintOptions`/lint baseline；`README.md` 严重过时（下一章）。

---

## 五、UI/UX 实现

无崩溃级问题。核心矛盾是三个万行/千行级单体承载了几乎全部交互，叠加列表刷新策略粗放。

- **UI-1 [P1] 主列表刷新普遍 `notifyDataSetChanged`，无 `DiffUtil`、无稳定 ID。** `ThingsActivity` 中 42 处 `notifyDataSetChanged`（另 27 处 `notifyItem*` 混用），`ThingsAdapterWrapper` 只延迟转发非 diff，未 `setHasStableIds`。**影响**：多数增删改触发全量重绑（重跑约 2800 行卡片绑定树、重发 Glide 请求），缺稳定 ID 导致无插入/移动动画，长列表掉帧。**建议**：`DiffUtil`/`AsyncListDiffer` + 稳定 ID + payload 局部刷新。

- **UI-2 [P2] 本地化不完整。** 默认 `values` 916 条、`zh-rCN` 909 条（仅缺 7 个彩蛋串），而 `ja/de/fr/es/ru/ko/it/pt/hi` 一律 702 条——9 种语言各缺约 214 键（含用户可见文案与无障碍描述，如 `act_delete_selected_items`、`act_move_to_thing_folder`、`all_types`、`cd_back_parent_folder`），这些 locale 回退英文、出现混排；且新增语言均为机翻未经母语审校（`docs/features/localization/followups.md`）。**建议**：补齐或对未完成 locale 做门控 + 母语复审。

- **UI-3 [P2] 可达性与字体缩放。** 5 处文字用 `dp`/`px` 而非 `sp`（`activity_detail.xml:67` 标题 20dp、`activity_doing.xml:118`、`app_widget_thing.xml:437/463` 等），系统字体放大无效；`views/` 约 19 个自定义控件仅 2 个设了 `AccessibilityDelegate`，`PatternLockView`（1040 行安全解锁，`announceForAccessibility` 定义后零调用）、`ColorPicker`、录音波形等对 TalkBack 不可用；全工程未用 `<plurals>`。**建议**：文字改 `sp`；至少给 `PatternLockView` 用 `ExploreByTouchHelper`；计数文案迁 plurals。

- **UI-4 [P3] 布局与自绘细节。** `RelativeLayout` 93 处 / 21 文件（`activity_settings.xml` 单文件 34 个嵌套）；每张卡片内嵌一个 `RecyclerView`（`card_thing.xml:112`）叠加回收开销；`PatternLockView.kt:648` 动画态每帧无条件 `invalidate`（作者自留 `// TODO: Infinite loop here`）。**建议**：热屏迁 ConstraintLayout；短清单改静态行或共享 `RecycledViewPool`；改有界 `ValueAnimator`。

- **值得肯定**：卡片图片加载有复用占位图 + 尺寸 override + 签名缓存，基本无闪烁；深色模式纯靠 `values-night` + `BackgroundUtil.isLight` 落实「Thing Background 不随深色变」约束，硬编码颜色极少（布局仅 11 处）；单 Thing widget 四尺寸已用 `BaseThingWidget` 抽公共、子类仅 12–14 行、无复制粘贴；zh/en 本地化近乎完整。

---

## 六、功能缺失、冲突与未收敛

- **FEAT-1 [P1] 云同步 docs-only、零实现。** `docs/features/cloud-sync/` 有 12 份设计文档 + ADR-0011/0012，但 `app/src/main` 无同步网络层/账号层/`SyncAccount` 实体/DTO/冲突处理，`build.gradle` 无任何网络依赖（无 Retrofit/OkHttp/Ktor），生产 Manifest 无 `INTERNET` 权限，无 `sync` 包，`server/` 仅 debug-APK 静态分发目录、无后端；提交 `c02030bc` 改 10 文件全为文档。设计锚定 DB v21 + 绝对路径附件，而 App 仍在持续迭代恰好触及该面（附件外观、动图、颜色迁移），迁移面在扩大。**建议**：把「schema 同步化改造（全局唯一 ID + 软删除墓碑 + 变更时钟 + 附件去设备化）」作为独立于网络层的第一步先落地——即使后端未上线，也能顺带解锁本地备份的可迁移性（同时缓解 DATA-1）。当前本地是每设备单调递增计数器 id（`ThingManager.kt:504`、`ThingDAO.kt:657-660`）、物理删除无墓碑（`ThingDAO.kt:383`）、id 可被复用（`recreateHeader`），直接开同步会串号/删除丢失/附件全断。

- **FEAT-2 [P2] 录音波形可视化多套并存、仅一套在用。** live 为 `WaveVisualizerFableSol`（`fragment_record_audio.xml` 内联 + `fablesol/` 约 20 文件）；`WaveVisualizerOpus`、`RecordingWaveVisualizer`、`VoiceVisualizer` 三族约 12 个 `.kt` 仅自引用（死代码），`attrs.xml` 残留 `VoiceVisualizer` styleable；且有未提交的 `FableSolSimulation.kt`/`FableSolWaveSets.kt`/测试改动（约 +334 行，已发阿里云 debug、未 commit、待真机复测）。**影响**：交错命名（Recording*/*Opus/Voice*/FableSol* 各带 Analyzer/FrameReceiver）显著误导维护，工作区长期脏。**建议**：确认 live 实现后删除其余族 + 废弃 feature 目录，合并为单一 `recording-visualizer`；尽快提交或搁置未提交改动。

- **FEAT-3 [P2] 数据保障能力缺位。** 无自动/定期备份（`BackupHelper` 仅手动触发、无 `PeriodicWorkRequest`，`allowBackup=false` 连系统云备份也关）；回收站无自动清理、无容量上限（无任何基于时间/数量的 purge）。叠加 DATA-1/DATA-4，删除的私密内容仍明文滞留、附件孤儿长期占用。**建议**：用已引入的 `work-runtime` 加可选周期本地备份（保留最近 N 份、含附件）+ 可配置回收站保留期后台清理（一并删附件文件）。

- **FEAT-4 [P2] 功能冲突与语义 footgun**（逐行核验，均非当前 live 崩溃）：
  - Habit 的「finish」语义重载——swipe-left 是 `finishOneTime` 记一次打卡并保持 UNDERWAY（`HabitDAO.kt:249-285`），批量「finish」是 `updateStates(FINISHED)` 归档整个习惯，同一动词相反效果（有 `alertForHabitGoal` 确认框缓解，`ThingsActivity.kt:7460+`）。
  - doing-Thing 保护未下沉到 `ThingManager.updateState(s)`，全靠调用者守卫（`ThingManager.kt:580,651-670`），未来新调用者遗漏即会 finish 正在计时的 Thing 并让 `DoingService` 写脏 `DoingRecord`。
  - `isReminderType`（REMINDER‖GOAL）vs `isTypeReminder`（REMINDER‖NOTIFICATION_REMINDER）两个近义反义谓词易选错、静默含/排 Goal（`Thing.kt:486-493`）。
  - 贪睡后已触发的提醒通知残留：Delay 直接起 `DelayReminderActivity` 绕过唯一 cancel 通知的 receiver，旧通知留在栏中且其 stale Finish 仍可点（`SystemNotificationUtil.kt:245-251`）。
  - `Long → Int` 截断所有通知 id 与 PendingIntent reqcode（`.toInt()` 只留低 32 位），长寿命安装/数据恢复后有极低概率碰撞。
  - `ONGOING_NOTIFICATION_ID`（`Def.kt:36`）实为 quick-create id、与「Ongoing Thing」无关，命名陷阱。

- **FEAT-5 [P2] Help 与 README 大面积过时。** `strings.xml:1354` 仍问「Why is there no sync feature yet?」（与在建 cloud-sync 冲突）、`:1385` 「Currently (version 1.3.8)」权限清单、通篇 2016-era ROM 建议，未覆盖 folder/dark mode/card appearance/HDR/motion photo/多级清单等新功能；`README.md` 仍写「no kotlin」「Code will not be updated any longer」「v2 is under developing」+ 失效商店链接 + Copyright 2018。**建议**：重写 `help_titles`/`help_contents` 与 README。

- **FEAT-6 [P2/P3] 其它产品缺口**：导出单向不可回导（`ThingExporter` 导 txt/zip，唯一「导入」是只认 `.bak` 的 `restore`，无 Markdown/JSON/ICS 往返）；release 渠道无自更新（仅 debug 走阿里云）；per-app language 双控（`generateLocaleConfig true` 与自存 `KEY_LANGUAGE_CODE` 并存，需真机确认）；Material You 动态取色缺失（考虑到强色彩身份属可接受取舍）。

---

## 七、已知欠账 Top（来自各 followups.md 汇总）

| # | 欠账 | 来源 |
|---|------|------|
| 1 | 根 scope「全部…」对私密子内容无鉴权（= PRIV-1） | `private-content/followups.md` |
| 2 | Kotlin `set*Listener` null-NPE 全局未清扫（已致崩溃，= QA-2） | `kotlin-migration/followups.md` |
| 3 | 视频附件指向公共相机路径、原文件删除即失效 | `hdr-media-display/followups.md` |
| 4 | 列表 Widget 图片封面不遵循用户比例/裁切（用户已报） | `remote-thing-card-appearance/followups.md` |
| 5 | 文件夹三处缺失入口：无「新建空文件夹」、无非拖拽「移动到文件夹」、无 Folder Card 滑动删除 | `thing-folders/followups.md` |
| 6 | 拖拽建夹并发动画写同一 ViewHolder（抖动/错位风险） | `thing-folders/followups.md` |
| 7 | 大 Folder Card 缩略图固定 3 列、非响应式 | `thing-folders/followups.md` |
| 8 | FableSol「暗色天空」翻转 bug（D21） | `audio-visualization-fable-sol/followups.md` |
| 9 | FableSol 高光渲染 GC 压力（九层×60fps 每帧多次 DoubleArray 分配） | `audio-visualization-fable-sol/followups.md` |
| 10 | Debug 更新通道从未端到端真机验证 + 明文 HTTP/IP 可被篡改 | `debug-update-channel/followups.md` |
| 11 | AppWidget 真机点击 smoke test 从未执行 | `remote-thing-card-appearance/followups.md` |
| 12 | 9 种新增语言机翻未母语审校 | `localization/followups.md` |
| 13 | 新建记事高亮边框未随滚动/旋转锚定 | `home-new-item-animation/followups.md` |
| 14 | 云同步已知语义瑕疵（设私密×改正文并发冲突副本无聚合入口；手机号/微信登录待资质） | `cloud-sync/followups.md` |
| 15 | 「移动到文件夹」对话框根节点文案错误（显示「underway」） | `thing-folders/followups.md` |

---

## 八、优先级行动建议

**立即（P0 — 安全定性与数据丢失，动同步之前必须先做）**
1. 在 UI/文档明示「私密仅为遮挡、非加密」，消除错误安全预期（SEC-1）；随后规划私密内容 at-rest 加密 + 口令加盐哈希。
2. 备份加密 + 纳入附件目录 + 加格式版本号（SEC-2、DATA-1、DATA-6）——这一步同时是同步化改造的地基。
3. 修复 `onUpgrade` 旧段守卫 + 全 `onUpgrade` try/catch，并补内存 SQLite 迁移回归测试（DATA-3、QA-1）。

**近期（P1 — 正确性与可靠性）**
4. `ThingDAO` 写入失败向上传播，不再静默吞异常（DATA-2）。
5. `doingThingId` 持久化并在进程重建时回填（ARCH-1）。
6. 开机/解锁/升级重排闹钟改 `goAsync`/`WorkManager`；错过提醒补发或「已错过」入口（REL-2、REL-1）。
7. 统一「永久删除清理」入口级联清 reminder/habit/record/附件（DATA-4）。
8. 根/文件夹 scope 批量操作接入私密鉴权（PRIV-1）。
9. `DetailActivity` 加 `onSaveInstanceState` 草稿保护（REL-3）。
10. 全局清扫 Kotlin `set*Listener` 可空签名（QA-2）。

**中期（P2 — 结构与体验）**
11. 引入 Repository + ViewModel + 协程，逐步拆解 God class；主列表上 `DiffUtil` + 稳定 ID（ARCH-2、UI-1）。
12. 收敛录音波形可视化到单一实现、清死代码、提交或搁置未提交改动（FEAT-2）。
13. 加自动周期备份 + 回收站自动清理（FEAT-3）；搜索排除私密正文（PRIV-2）。
14. 补 signingConfigs 与可跑的 release 校验任务，验证混淆规则（BUILD-1）；接入基本 CI 与 lint baseline。
15. 补齐本地化缺失键、文字改 `sp`、关键自绘控件补无障碍（UI-2、UI-3）。
16. 重写 Help 与 README（FEAT-5）。

**技术债（P3，随手清理）**：数据层 `@Volatile`/游标 `use{}`/分隔符转义、命名 footgun（`isReminderType`、`ONGOING_NOTIFICATION_ID`）、贪睡通知残留、`DailyUpdateHabitReceiver` exported、遗留 API、内嵌库 LICENSE、`PatternLockView` 死循环、不专业注释清理。

---

## 九、值得肯定

审计中确认了不少做得扎实、易踩雷却没踩的地方，客观记录：

- 提醒主链路以 `AlarmManager.setAlarmClock`（免疫 Doze 与厂商省电、免 `SCHEDULE_EXACT_ALARM`）为核心 + `setExactAndAllowWhileIdle` → `setAndAllowWhileIdle` 分层降级 + 双重 try/catch（`AlarmHelper.kt:213-264`），设计优秀，多层自愈兜底。
- 平台姿态克制：无 `QUERY_ALL_PACKAGES`、无 `REQUEST_INSTALL_PACKAGES`，`<queries>` 精确声明；Android 12–16 高危行为变更已在 `android-16-migration` 系统闭环。
- 私密内容在通知、AppWidget、分享/截图上均已正确遮蔽（逐行核验）；Doing/Ongoing 两概念在批量/范围变更中被显式区分、未混用。
- 内存泄漏面干净：单例 DAO / `ThingManager` 均持 `applicationContext`，未见静态持有 Activity/View。
- 外观模型用手写 `org.json` 显式键，对 R8 混淆天然免疫；`Def.kt` 246 个常量分 6 个嵌套 object 组织得当；DB 迁移 v9 起用 `columnExists` 做了幂等防护。
- 文档纪律罕见完备：`CONTEXT.md` 领域词汇表、17 份 ADR、约 49 个 `docs/features/` 目录 + memory 索引体系，为后续维护（含 AI 协作）提供了扎实上下文。

---

## 附：本次审计方法与已排除的误报

- 方法：六维度并行只读审计 + 一份功能冲突逐行深度核验；最重的 P0/P1 结论（DB 写入吞异常、备份丢附件、私密口令明文、`doingThingId` 不持久化、迁移无守卫、诊断日志生产开启、私密密码所在 prefs 在备份包内）已单独用工具复核。
- 已核实排除的误报：外观模型「Gson 混淆风险」（实为 `org.json` 手写键，不受影响）；「工具链过旧」（实为 AGP 9.2.1 / Gradle 9.4.1）；「widget 四尺寸复制粘贴」（实为 `BaseThingWidget` 抽象、子类 11–14 行）；「私密内容在通知/widget/分享泄露」（逐行核验为已正确遮蔽，唯一泄露点是搜索）；「Doing/Ongoing 互相清错状态」（核验为处理得当，仅余潜在分层不对称）；「提醒重复/双发」（核验为 `FLAG_UPDATE_CURRENT` 去重设计）。
