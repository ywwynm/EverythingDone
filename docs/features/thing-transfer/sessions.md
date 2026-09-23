# 记事传输 会话记录

## 2026-09-21 - 调研与 grilling，功能建档

起因是用户看到"有些安卓手机可以碰一碰传数据"，问能不能也做碰一碰传记事。

**调研**（14 次检索 + 官方文档核对）得出的关键结论：第三方应用无法调用系统那套
碰一碰。互传联盟 2026-09-16 发布的碰一碰互传技术标准（小米 / OPPO / vivo / 荣耀，
10 月中旬起推送）直接做在各家系统底层，应用侧接入点就是标准 `ACTION_SEND`；
三家开放平台均无互传 SDK；Android 原生 NFC P2P 已在 Android 14 移除；
Quick Share 需 GMS；华为 Share Kit 是 HarmonyOS NEXT 的 ArkTS 接口，Android 用不了。
但**自建 NFC 握手 + Wi-Fi Direct** 这条路成立，与系统碰一碰同原理。

**交叉核对代码**发现真正的缺口不在"碰"而在"记事"：`SendInfoHelper.shareThing`
传出去的是拼接的纯文本加图片原件，丢掉类型、颜色、清单层级、提醒规则、习惯周期、
卡片外观；`ThingExporter` 产出的 txt 给人读、不可还原；**全项目没有任何导入能力**。

**grilling 逐项确认 16 条决策**，见 [decisions.md](decisions.md)。要点：意图是
给别人一条记事而非设备间迁移；接收方须装完事儿、用私有格式换全保真；范围单条加
多条不含文件夹；附件全部内嵌可勾选不设上限；接收走 `ACTION_SEND` 加应用内导入、
不依赖文件类型关联；导入忠实复制（提醒会真的响）；私密去标记但发送前鉴权；
与 cloud-sync 共用 5.3 字段分域表但不等 P1；导入用预览列表逐条勾选加重复标注；
发送入口是分享对话框第三项「记事文件」，顺带换掉只能放两项的
`TwoOptionsDialogFragment`。

**分期**：先在真机上验二期碰一碰的可行性再定排期。验证方案见
[probe-plan.md](probe-plan.md)。

**一处自我更正**：会话中曾判定 `ThingExporter` / `SendInfoHelper` 未经私密审计、
存在批量导出泄露。实为误判——鉴权做在调用方
（`ThingsActivity.exportSelectedThingsWithPrivacyAuth`），helper 内部不含判定是
正常的。原因是只 grep 了 helper 文件而未查调用点。

**新增词条**：`CONTEXT.md` 加 **Thing File**，与「导出到 SD 卡」的可阅读 txt 区分。

**验证程序已写好并编译通过**（用户确认三星 SM-S9180 与魅族 20 Infinity 两台都有 NFC
且已开启）。`probe/` 是独立 module，纯 Java、零依赖，`settings.gradle` 里 include 并把
`projectDir` 指过去，`gradlew :nfcprobe:assembleDebug` 一条命令出包（约 2.5 MB）。

实现要点：

- AID `F0454454524E53`（F0 + ASCII "EDTRNS"），`category="other"` 而非 `payment`，
  不进系统的默认付款应用竞争
- 三条 APDU：SELECT、ECHO len（量单次 R-APDU 上限）、GET HANDSHAKE
- 前台门控做在 `ProbeState.senderArmed`，未就绪时 SELECT 返回 6985；容量测试因此也
  需要对方处于发送端就绪状态
- 发送端 `createGroup()` 后取 SSID 与密码写进握手内容；接收端用
  `WifiP2pConfig.Builder().setNetworkName().setPassphrase()` 直接入组，跳过发现与
  GO 协商（API 29+）
- 入组广播到来时 DHCP 未必分好地址，socket 连接按 250 ms 间隔重试 24 次
- 所有结果显示在屏幕上，不写 logcat——魅族那台的 main 缓冲只有 256 KiB，跑一轮就冲掉了

跑法与记录表见 [probe-plan.md](probe-plan.md)。

## 2026-09-21 - 真机自测：UI 缺陷修复与不需触碰部分的验证结果

第一版交给用户后被指出两个缺陷，用户要求自己上真机跑。两个缺陷同源：

1. **第一行按钮整行被 ActionBar 盖死**，第二行也切掉上半截。targetSdk 36 在 API 35+ 上强制
   edge-to-edge，`Theme.Material.Light` 的 ActionBar 直接压在内容上，而根布局没处理 insets。
   修法：主题改自定义的 `ProbeTheme`（无 ActionBar），根布局按
   `systemBars() | displayCutout()` 加内边距。
2. **没有授权弹窗**。`requestPermissions` 写在按钮点击里，而那两个按钮正好是被盖住的——
   连点都点不到。改到 `onCreate` 里先查再申请。

追加修复：Flyme **不认 `android:forceDarkAllowed="false"`**，硬编码的浅色被渲染层整片反色
（状态条设的浅绿显示成深紫）。改成主题继承 `@android:style/Theme.DeviceDefault.DayNight`、
颜色交给主题，应用自己声明支持深色后系统不再强制反色。

### 已验证（不需要物理触碰的全部环节）

| 项 | 三星 SM-S9180（Android 16） | 魅族 20 Infinity（Android 15） |
|---|---|---|
| 界面完整可见 | 是 | 是 |
| 权限弹窗与授予 | 是 | 是 |
| 卡模拟 AID 注册 | `*DEFAULT* ApduService`，category other，未被钱包抢走 | 同左 |
| 读取模式开启 | `reader_mode_change flags: 131` | 未测（NFC 关） |
| Wi-Fi Direct 建组 | **191 ms** | **241 ms** |
| 握手内容大小 | **88 B** | **82 B** |
| ServerSocket 监听 | 8899 正常 | 8899 正常 |
| NFC 总开关 | 开 | **关**（需用户自己开，不代改） |

**验证项 1（握手容量）已提前有答案**：握手内容 82–88 B，标准 APDU 数据段上限 255 B，
**装得下，协议不需要分段**。SSID 里含中文设备名（"ywwynm的S23 Ultra"）已计入。

**建组耗时远好于预期**：191 / 241 ms，而 probe-plan 里按文献估的是 2–10 秒。原因是
`createGroup()` + 把 SSID/密码写进握手内容，接收端按 `WifiP2pConfig.Builder` 直接入组，
跳过了发现与 GO 协商。这让否决线（T1+T2 ≤ 10 秒）的压力主要落在 NFC 触碰那一段。

### 仍需用户完成

打开魅族的 NFC 总开关；物理触碰。触碰之后的 T1、入组耗时、传输速率、抢占与天线位置
只能由真实触碰给出。

### 工具坑（已记入 `.claude/rules/adb.md`）

PowerShell 工具的沙箱把 adb 参数里的 `rm` 当本地 `Remove-Item` 拦截，改用随机文件名；
代码建的 UI 没有 `resource-id`，按 `class` 出现顺序取第 N 个控件。

## 2026-09-21 - 第一轮真实触碰：三轮全过，远低于否决线

用户在三星（发送端）与魅族（接收端）之间连碰三次，1 MB 档，**三轮全部成功，无一失败**。
NFC 总开关由用户明确授权后用 `svc nfc enable` 打开（Flyme 会往 `svc` 进程吐一堆
`com.meizu.ems.ExceptionManager` 异常，是噪声，读回 `mState=on` 即可）。

| 轮次 | T1 贴上→握手 | T2 握手→入组 | socket | T3 入组→收完 | 合计 | T1+T2 |
|---|---|---|---|---|---|---|
| 1 | 91 ms | 2997 ms | 21 ms | 335 ms | 3423 ms | 3088 ms |
| 2 | 43 ms | 1039 ms | 28 ms | 337 ms | 1419 ms | 1082 ms |
| 3 | 37 ms | 651 ms | 25 ms | 480 ms | 1168 ms | 688 ms |

**否决线是 T1+T2 > 10 秒或 10 次成功 < 7 次。实测最差 3.1 秒、成功 3/3，通过且余量很大。**

四项关键结论：

1. **入组不弹系统确认框。** `WifiP2pConfig.Builder` 按 SSID + 密码直接入组这条路不需要用户
   二次确认——这是「碰一下就传」能成立的前提，此前只是推断，现在实测确认。
2. **`maxTransceiveLength = 65279 B`。** 这台支持扩展 APDU，远超标准的 255 B。握手 88 B
   毫无压力，且将来若要在 NFC 阶段直接带更多内容（如记事标题预览），有 64 KB 可用。
3. **T1 只有 37–91 ms**，NFC 握手几乎瞬时。整条链路的时间全在 T2。
4. **T2 逐轮下降：2997 → 1039 → 651 ms。** 首次入组要完整关联，后续因射频状态还热而更快。
   产品含义是**第一次碰会明显慢于后续几次**，首轮约 3 秒、之后约 1 秒。

### 一个已修的度量缺陷

发送端原本在 `os.flush()` 后立刻报耗时，量到的是**写进内核 socket 缓冲区**的时间，
1 MB 报出「2 ms / 500 MB/s」这种假数字。已改为接收端收完后回一个字节、发送端读到才计时
（配 `setSoTimeout(30000)`）。真实速率以接收端为准：本轮 **2.26–3.41 MB/s**。

## 2026-09-21 - 10 MB 与 100 MB：速率随文件增大而上升，传输根本不是瓶颈

同一方向（三星发、魅族收）续跑三轮，**累计 6 轮全部成功**。

| 档位 | T1 贴上→握手 | T2 握手→入组 | socket | T3 传输 | 合计 | 速率 |
|---|---|---|---|---|---|---|
| 1 MB × 3 | 37–91 ms | 651–2997 ms | 21–28 ms | 335–480 ms | 1168–3423 ms | 2.26–3.41 MB/s |
| 10 MB 第 1 轮 | 51 ms | 2795 ms | 20 ms | 526 ms | 3372 ms | 20.12 MB/s |
| 10 MB 第 2 轮 | 42 ms | 2075 ms | 22 ms | 467 ms | 2584 ms | 23.09 MB/s |
| **100 MB** | 45 ms | 565 ms | 27 ms | 2353 ms | **2963 ms** | **43.14 MB/s** |

**1 MB 测出的 3 MB/s 是 TCP 慢启动的假象**，不能用来估算大文件。速率随文件增大单调上升：
1 MB 约 3、10 MB 约 20–23、100 MB 达 **43 MB/s**。度量修正后两端数字吻合
（发送端 43.10 vs 接收端 43.14 MB/s）。

三条产品含义：

1. **带 100 MB 视频的记事，碰一下不到 3 秒传完。** 之前按 3 MB/s 估的 33 秒是错的，
   「附件内嵌、不设体积上限」这条决策站得住。
2. **传输不是瓶颈，入组才是。** 10 MB 全程 3.4 秒里传输只占 0.5 秒，T2 占 2.8 秒。
   要让碰一碰更快，该优化的是 Wi-Fi Direct 入组，不是传输。
3. **T2 波动大（565–2997 ms）且与文件大小无关**，取决于射频当时的状态。首轮通常慢，
   连着碰会快。界面上要照顾「第一次碰约 3 秒」这个体验。

### 否决线判定：通过，余量一个数量级

否决线是 T1+T2 > 10 秒或 10 次成功 < 7 次。实测 6 轮全部成功，T1+T2 最差 3.1 秒
（第一轮），最好 610 ms。**二期的应用内碰一碰技术上成立。**

## 2026-09-21 - 反向角色跑通，验证收尾：7 轮全过

魅族当发送端、三星当接收端，100 MB：

| 方向 | T1 | T2 | socket | T3 | 合计 | 速率 |
|---|---|---|---|---|---|---|
| 三星发 → 魅族收 | 45 ms | 565 ms | 27 ms | 2353 ms | 2963 ms | 43.14 MB/s |
| 魅族发 → 三星收 | 81 ms | 2573 ms | 32 ms | 1499 ms | 4153 ms | **68.31 MB/s** |

两个方向都成立，**反向的传输速率还更高**（68 vs 43 MB/s）。合计耗时反向更长，差在 T2
（2573 vs 565 ms），而 T2 的波动与方向无关、与射频当时的状态有关。魅族当 GO 时网段是
`192.168.90.70` 而非三星的 `192.168.49.1`，驱动代码不能把 GO 地址写死。

### 最终判定

**累计 7 轮，成功 7/7。T1+T2 最差 3.1 秒、最好 610 ms，否决线是 10 秒或 10 次里少于 7 次
成功——通过，且余量一个数量级。二期的应用内碰一碰技术上成立。**

逐项对照 probe-plan.md 的七个验证项：

| 项 | 结果 |
|---|---|
| 1 握手容量 | `maxTransceiveLength = 65279 B`，两台一致；握手 84–90 B，**不需要分段** |
| 2 能不能碰上 | 7/7 成功 |
| 3 会不会被抢占 | 未发生。AID 注册为 `*DEFAULT* ApduService`（category other），7 轮无一被钱包截走 |
| 4 天线位置 | 未量化。用户未反馈反复试的情况，但缺直接证据，留作实施期观察 |
| 5 读取模式副作用 | 技术侧已确认（`reader_mode_change flags: 131`）；**实际刷公交/门禁未试**，留作 followup |
| 6 反向角色 | 通过，两方向均成立 |
| 7 建连与传输 | 1/10/100 MB 三档全过，速率 3 → 20 → 43/68 MB/s |

额外确认的一条：**入组全程不弹系统确认框**（7 轮一致），这是「碰一下就传」成立的前提。

## 2026-09-21 - 一期实施：记事文件格式、导出、导入全部落地

代码全部编译通过，**尚未在真机上跑过**。

### 新增

| 文件 | 职责 |
|---|---|
| `thingfile/ThingFile.kt` | 格式常量、清单字段名、待导入记事的中间表示 `PendingThing` |
| `thingfile/ThingFileWriter.kt` | Thing + Reminder + Habit + 附件 → zip |
| `thingfile/ThingFileReader.kt` | zip → `PendingThing`，附件解压到临时目录 |
| `thingfile/ThingFileImporter.kt` | 真正写进数据库，逐条独立、部分成功即报告 |
| `thingfile/ThingFingerprint.kt` | 内容指纹，标出「这条你已经有了」 |
| `thingfile/ThingFileSharer.kt` | 私密鉴权 → 后台生成 → `ACTION_SEND` |
| `activities/ThingFileImportActivity.kt` | 预览列表 + 逐条勾选 + 重复标注 |

### 改动

- `DetailActivity.chooseHowToShareThing`：两项对话框换成三项，加「记事文件」
- `AndroidManifest`：`ThingFileImportActivity` 注册 `ACTION_SEND`
  （`application/zip` + `application/octet-stream`）
- `SettingsActivity`：备份/恢复下面加「导入记事文件」，走 `ACTION_OPEN_DOCUMENT`
- `Def.Communication`：新增 `REQUEST_CHOOSE_THING_FILE = 22`
- 新增图标 `act_share_thing_file.xml`，新增中英文案各 16 条

### 实施期查清的几条事实

- **`Reminder.id == Thing.id == Habit.id`**，1:1 折叠，序列化不需要单独的 id。
- **闹钟由 DAO 内部设**：`ReminderDAO.create` 与 `HabitDAO.createHabitReminder` 各自会调
  `AlarmHelper`，导入器走 DAO 即可，不必也不应再设一遍。
- **习惯的 `record` / `intervalInfo` / `firstTime` 是运行时状态**，新建习惯时本来就是
  空串与 0（`firstTime` 由 `initHabitReminders()` 算出），已从格式里去掉。
- **新记事的 `location` 要自己设**：`ThingManager.create` 会设 id 但不动 location，
  与 `DetailActivity` 新建记事一样取当时的 `getHeaderId()`。
- **`AttachmentHelper.createAttachmentFile` 的文件名只到秒**，同一秒连着建多个附件会重名，
  而导入一条带十几张图的记事正好是这种情形；且它固定用 `.jpg/.mp4/.wav`，对 png、gif、
  m4a 是错的。导入器自己带序号并保留原扩展名。
- **首页刷新走 `App.setJustNotifyAll(true)` + `BROADCAST_ACTION_UPDATE_MAIN_UI`**。
  `isSomethingUpdatedSpecially` 只有 DetailActivity 在读，不是给首页用的。

## 2026-09-21 - 一期真机闭环验证通过（三星 SM-S9180）

先建测试记事 `PROBE-0921-TRANSFER`（内容 `alpha-line-one`），跑完整条链路，最后把
两条都移进回收站。**没有动任何真实记事。**

链路：建记事 → 详情页分享 → 记事文件 → 系统分享面板 → 选「完事儿 / 导入记事」→
预览列表 → 勾选 → 导入 → 首页出现第二条。

逐项结果：

| 环节 | 结果 |
|---|---|
| 三项分享对话框 | 出现，第三项「记事文件」在位 |
| 文件生成与命名 | `PROBE-0921-TRANS-20260921201905.edthing`（标题截 16 字 + 时间戳） |
| FileProvider + ACTION_SEND | 系统分享面板正常拉起 |
| 接收入口注册 | 面板里出现「完事儿 / 导入记事」 |
| **「S 互传」也在面板里** | 这正是碰一碰的入口，一期的设计因此成立 |
| 预览列表 | 标题、类型「记录」、颜色条都正确 |
| **内容指纹去重** | 标出「已有相同内容」并默认不勾选（自己导出自己导入，必然重复） |
| 勾选与按钮文案 | 「未选中任何记事」→「导入选中的 1 件记事」 |
| 导入 | 首页出现 2 条，内容都是 `alpha-line-one` |
| 首页自动刷新 | `App.setJustNotifyAll(true)` + `BROADCAST_ACTION_UPDATE_MAIN_UI` 生效 |

### 验证中发现并修掉的两个视觉缺陷（都出在新图标上）

**其一 · 尺寸与基线。** 新图标原先画成 24dp 满画布，而同排两个 png 是
**400×400 画布（100dp）、墨迹垂直居中**（`act_share_text_image` 墨迹 130×120，
`act_take_long_screenshot` 115×154）。`setCompoundDrawablesWithIntrinsicBounds` 按各自
固有尺寸摆放，结果第三个明显偏小、基线也不齐。改成同规格（400 画布、墨迹 105×140）后对齐。

**其二 · 颜色深浅（用户指出）。** 两个 png 的主体墨迹实测都是 **alpha=138、rgb=(0,0,0)**，
即约 54% 不透明度，**一个 alpha≥250 的像素都没有**。tint 走 `SRC_ATOP`，alpha 取原图的，
所以原图有多淡、上屏就有多淡。新图标写的是 `#FF000000`，比同排两个**深 85%**。
改成 `#8A000000`（0x8A = 138）后，截图实测三个图标的核心色完全一致：rgb(249,186,177)。

**教训：往既有图标排里加新图标，画布尺寸、墨迹尺寸、墨迹 alpha 三项都要先量出来再画。**
「24dp 是标准图标尺寸」「图标用纯黑再交给 tint」这两个常识在这里都是错的。

## 2026-09-21 - 带附件的记事：附件逐字节一致

造一张有辨识特征的测试图（640×480，md5 `49bf3793c4163e783ba7d536d260ed47`，8006 B）推到
设备，通过「选择媒体文件」加进测试记事 `PROBE-0921-ATTACH`，再走同一条闭环。

| 检查点 | 结果 |
|---|---|
| 预览列表的附件计数 | 「记录 · 1 个附件 · 已有相同内容」 |
| 导入后的附件文件 | `files/images/import_1789994790465_0.png`，8006 B |
| **附件 md5** | `49bf3793...` —— **与原图逐字节一致** |
| 扩展名 | `.png` 保留（用 `createAttachmentFile` 会被固定成 `.jpg`） |
| 应用内渲染 | 详情页正常显示该图，背景色也一并传过来了 |

顺带验证了「无标题记事」的分支：第一次建记事时标题没输进去（**有附件后详情页布局会变，
原先记下的输入框坐标不再有效**），卡片显示为「1 张图片」，文件名生成也走了退回分支。

清理：两条测试记事移入回收站，推到设备的测试图已删除，首页残留 0 处
（`fab_create` 与 `actionbar` 均可见，排除浮层遮挡导致的误判）。

## 2026-09-21 - 附件外观设置会全丢：一个必须修的缺陷

用户指出要检查「附件的显示比例、封面图片的显示比例/位置/类型」是否保留。查下来是真缺陷。

**根因**：`DetailAttachmentMediaAppearance` 与 `ThingCardAppearance` 的 `sources` 以
**typePathName（类型 + 本机绝对路径）**作键（`DetailActivity:4553` 的
`withSource(source.typePathName, ...)`，`ImageAttachmentAdapter` 全部按它取）。附件导入后
必然落在别的路径上，这些键一条都对不上，于是显示比例、裁剪、视频帧、卡片封面的选择与
位置**全部丢失**。两个模型原本只有 `retainSources` / `removeSources`，没有重命名键的能力。

**修法**：给两个模型各加 `remapSources(mapping)`（`ThingCardAppearance` 版同时重写
`mediaSourceKey`，`MEDIA_SOURCE_NONE` 哨兵原样保留），并让键在记事文件里与设备无关：

- 导出：把键从 typePathName 换成 zip 内的条目名
- `PendingThing` 多带一个 `attachmentKeys`（与附件同序的条目名）
- 导入：复制完附件后把键换成本机的新 typePathName

### 验证：外观精确跟随对应附件

造一条三附件记事（顺序 mC、mB、mA），**只给第 1 个和第 3 个设裁剪**，中间那个不设——
映射一旦错位，外观就会挂到 mB 上。

| | 源记事 | 导入结果 |
|---|---|---|
| 附件 1 | mC，有外观 | `import_..._0.png`，**有外观** |
| 附件 2 | mB，无 | `import_..._1.png`，**无** |
| 附件 3 | mA，有外观 | `import_..._2.png`，**有外观** |

### 过程中的一次严重疏忽

第一轮验证「通过」是假的：**改完代码编译成功后没有重新安装到设备**，整轮跑的是旧 APK，
导入结果里的外观键还是发送方的旧路径。是逐字段核对数据库才发现的——只看界面完全看不出来。
**编译成功不等于设备上跑的是新代码。**

## 2026-09-21 - 批量分享与跨设备：全部通过

补上了选择模式的入口（`menu_contextual_underway` / `_finished` 加
`act_share_thing_file`，`ThingsActivity.shareSelectedThingsAsThingFile`）。

| 检查点 | 结果 |
|---|---|
| 选择模式菜单 | 「记事文件」在「导出到 SD 卡」下方，选中 2 条可用 |
| 批量产物 | `完事儿-2-20260921212132.edthing`，20887 B（2 条 × 3 图） |
| 传到魅族后 md5 | `07ef32f7...` 一致 |
| **系统对 `.edthing` 的 MIME 判定** | **`application/octet-stream`** —— 正是已注册的类型之一，**跨设备的 MIME 风险解除** |
| `pm query-activities` | `application/zip` 命中、`application/octet-stream` 命中、`*/*` 未命中（预期，注册它会让应用出现在所有文件的分享面板里） |
| 魅族上的预览 | 2 条 × 3 附件，**没有**「已有相同内容」标注，默认全选——去重在两种情形下都对 |
| 魅族上的导入结果 | 两条各 3 附件，外观键精确落在第 1 与第 3 个附件上，与源记事一致 |

`file://` URI 会被 scoped storage 挡掉（`EACCES`），真实路径必须走 `content://`——
设置里的导入入口用 `ACTION_OPEN_DOCUMENT` 正是这一条。

清理：两台设备的测试记事全部移入回收站（`PROBE left=0`，且 `fab_create`、`actionbar` 可见，
排除浮层遮挡导致的误判），推送的测试图与传过去的 `.edthing` 均已删除。

## 2026-09-21 - 按字段逐项验收：提醒、习惯、清单、音频、背景

思路是「格式里序列化了什么，就验什么对应的功能」。核对一律读数据库，不看界面。

### 提醒（type=REMINDER）

| 字段 | 源 (908) | 导入 (909) |
|---|---|---|
| `notify_time` | 1789999397896 | **完全一致** |
| **闹钟注册** | `RTC_WAKEUP #19` | **`#20` 另外注册了一个** |
| `notify_millis` | 900010 | 802563 —— **正确重算**（它是「距现在多久」，由两参构造重新算出） |
| 渐变背景 | `GRADIENT/-12674114/-3300369/R_L` | 一字不差 |
| `createTime` | 1789998497903 | 忠实复制 |

预览列表里也正确显示了「提醒 · 提醒：今天深夜22:03」。

### 习惯（type=HABIT）

| 字段 | 源 (910) | 导入 (912) |
|---|---|---|
| `habit.type` | 5（Calendar.DATE，每天） | 5 |
| `detail`（提醒时刻） | `21:51` | `21:51` |
| `habit_reminders` | 1 条 | **重建 1 条，时刻相同** |
| **`habit_records`** | 0 | **0** —— 打卡记录不传，从零开始 |
| `record` / `interval_info` / `reminded_times` | 空 / 空 / 0 | 空 / 空 / 0 |
| **闹钟** | — | `HabitReceiver` 行数 21 → 22，出现两个同时刻闹钟 |

### 清单内容

清单编码是 `SIGNAL + 层级位 + 状态位 + 文本`，勾选过的项状态位为 1 并被移到末尾。
导入结果的 `content` 与源记事**逐字符一致**（含空项与顺序）：

```
`启Q琼01item-two`启Q琼01item-three`启Q琼01`启Q琼11item-one
```

卡片上也正确渲染成 3 个未完成 + 1 个已完成。

### 音频附件（type=2）

| 检查点 | 结果 |
|---|---|
| 附件类型位 | `2` 保留 |
| 落地目录 | `files/audios/`（按类型分目录正确） |
| **扩展名** | **`.mp3` 保留**——`createAttachmentFile` 会把音频固定成 `.wav`，导入器自己建文件正是为此 |
| 字节 | md5 `79e63428…` **逐字节一致** |

### 背景两种模式都验过

渐变（提醒与习惯记事）与纯色（音频记事）各走过一遍，JSON 一字不差。

### 删除路径的附带验证

清理后 `dumpsys alarm` 里测试用的两个 `origWhen` 条目都归 0 —— 记事进回收站时闹钟被正确取消。

> **更正（2026-09-23）**：这个结论不成立。移入回收站**不取消**提醒闹钟——`ThingDAO.updateState`
> 不碰闹钟；到点时 `ReminderReceiver` 发现记事不在「进行中」，只把提醒标成已过期、不发通知。
> 2026-09-23 的复验里，进回收站的测试提醒（3 天后）两条闹钟条目都还在。9-21 看到的「归 0」
> 不能说明闹钟被取消了。行为本身无害，但判据要换成「到点不发通知」。

### 未能真机验证的两项

- **私密去前缀**：设私密要求先设置密码，而**密码属于用户设置，不代设**。代码路径是确定的：
  `ThingFileWriter` 写标题用 `getTitleToDisplay()`（去前缀），`ThingFileSharer` 发送前走
  `ThingPrivacyResolver.isEffectivelyPrivate` 判定并鉴权。需要用户自己设过密码才能跑完整流程。
- **卡片外观（`thing_card_appearance`）的含路径字段**：改用单元测试覆盖，见下节。

## 2026-09-21 - 卡片外观改用单元测试覆盖；顺带查清了长按的真实语义

原以为「One UI 上长按卡片进不了选择模式」，用户指出不对。读代码后弄清了真实机制
（`ThingsActivity.onItemLongClick`）：

- **在「正在进行」列表里长按进入的是拖拽排序模式**（`toMovingMode` + `startLongPressDragIfTouchStillActive`），
  不是选择模式——所以要「拖一下再放回原位」才会落到选择态。
- 只有在**其他状态的列表**（已完成、回收站）里长按才直接 `toSelectingMode`。

此外在三星上用 adb 注入长按会被 One UI 的系统拖放截走（`com.samsung.android.onetouch`
的引导页被拉到前台），这条 `.claude/rules/adb.md` 早有记录，本次再次命中。

### 为什么改用单元测试

继续驱动 UI 去调一次卡片外观，成本远高于收益：纯值字段（`spanMode`、`imagePlacement`、
`contentMaxLines*`、`checklistMaxItems*` 等）是 JSON 直传、无映射逻辑，实测已随记事过去；
含路径的 `sources` 走的是**与 `detailAppearance` 字面相同的 `remapSources`**（两次调用写在
相邻两行），而那一段已由三附件真机测试严格覆盖。

**唯一未被覆盖的是 `ThingCardAppearance.remapSources` 比附件版多出的 `mediaSourceKey`
重映射**。这是纯 Map 逻辑、不碰 JSON，用 JVM 单元测试覆盖既快又是可重复的回归保护。

新增 `app/src/test/java/com/ywwynm/everythingdone/model/AppearanceRemapSourcesTest.kt`，
8 个用例全绿（报告 `TEST-...AppearanceRemapSourcesTest.xml`，tests=8 failures=0 errors=0）：

| 用例 | 覆盖的契约 |
|---|---|
| `cardAppearance_remapsSourceKeysAndDropsUnmapped` | 键被重写；没有映射的条目整条丢掉，不留指向不存在文件的键 |
| `cardAppearance_remapsMediaSourceKey` | **封面指向的附件也跟着换**（否则导入后卡片选不到封面） |
| `cardAppearance_keepsMediaSourceNoneSentinel` | 「不显示封面」是哨兵值不是路径，不能被清成 null |
| `cardAppearance_clearsMediaSourceKeyWhenItsAttachmentIsGone` | 封面附件没跟过去时封面落空，**绝不保留发送方的旧路径** |
| `cardAppearance_emptyMappingIsNoOp` | 空映射原样返回 |
| `detailAppearance_remapsSourceKeysAndDropsUnmapped` | 附件版同上 |
| `detailAppearance_survivesExportThenImportRoundTrip` | **导出换一次、导入再换一次之后仍挂在同一附件上** |
| `detailAppearance_doesNotShiftOntoTheMiddleAttachment` | 中间附件无外观时不发生 off-by-one 挪位 |

## 2026-09-22 - 用户实际使用中发现的三处问题，均已修复并验证

都是「数据层看不出来、只有真用一遍才会撞上」的问题。

### 一、选择模式菜单的文案

「记事文件」在分享对话框里与「文本与图片」「长截图」并列，是产物名，读得通；但在选择
模式菜单里与「完成所选记事」「移动到文件夹」这些动作并列时，光秃秃一个名词不知道要干什么。
菜单改用「分享为记事文件」，对话框保持不变。

### 二、文件应用里打不开 `.edthing`

原先放弃 `ACTION_VIEW` 的理由只对了一半（见 decisions.md 同日条目）。补上
`content` + `application/octet-stream` 与 `file` + `pathPattern` 两条 filter 后，
系统「打开方式」列表里出现「导入记事 / 完事儿」，选中后预览正确列出 2 条记事各 3 个附件。

测试方法上的坑：用 `am start` 手工构造 VIEW Intent 时不加 `--grant-read-uri-permission`
会报 `has no access to content://…`，那是构造的 Intent 缺 flag，不是功能缺陷。

### 三、互传收到的文件报 EACCES（用户实际撞到的）

从三星导出、经魅族互传收到后在文件应用里打开，报
`/sdcard/Download/MzShare/…: open failed: EACCES`。

根因是**无法用代码绕过**的平台限制：互传落地的文件属于互传应用自己的 uid
（实测 `-rwxrwx--- u0_a156`），而完事儿在 Android 13+ 只有 `READ_MEDIA_*`，记事文件不是
媒体、一条都不覆盖。删掉 `file://` filter 会让入口从「打开方式」里消失，比失败更糟。

改为**给一条走得通的路**：`ThingFileReader.Result` 新增 `NoAccess`，导入界面把提示换成
「这个文件存放在其他应用的目录里…点下方按钮，在文件选择器里选中它就能导入」，底部按钮
变为「去选择这个文件」，点了走 `ACTION_OPEN_DOCUMENT`——SAF 的 content URI 有权限。

魅族上完整验证：`file://` 打开 → 出现新提示与按钮 → 点按钮 → 选择器里选中同一文件 →
预览正确显示 `PerfB9-20260917-1 / 记录`，按钮恢复成「导入选中的 1 件记事」。未执行导入，
设备上无残留（`matched things: 0`）。

**这三处都说明：数据层的逐字段核对证明不了「好不好用」。** 后续应当在真实使用路径上再走
几遍，而不是只盯数据库。

## 2026-09-23 - 渐变背景下「记事文件」图标偏淡：两个 path 重叠抬高了最大 alpha

用户反馈：记事是渐变背景时，分享对话框里「记事文件」的图标比另外两个淡。9-21 核对颜色时
只在纯色背景下量过，渐变那条着色路径没有验过。

### 机制

- 纯色背景走 `SRC_ATOP`，alpha 取原图的，三个图标主体都是 138，所以一致。
- 渐变背景走 `BackgroundUtil.tintDrawable` 的另一支：先把图标画成遮罩，再由
  `normalizeMaskAlpha` 按**整张图的最大 alpha** 拉伸到 255，最后用渐变 `SRC_IN` 上色。
- 两个 png 是扁平的一层，最大 alpha 就是 138，主体被拉满到 255。
- 矢量图原先把外框与折角写成两个 `<path>`。VectorDrawable 逐个 path 绘制，两个
  `#8A000000` 描边在两个交点处按 `SRC_OVER` 叠到 **201**；最大值变成 201 之后，
  主体只被拉到 138 × 255 / 201 = **175**，即 0.686。

用 skia-python（与 Android 同一个绘制引擎）按 VectorDrawable 的画法复算：原图 400px
画布上有 468 个像素超过 138，最大 201，拉伸后众数 175；合并成一个 path 后最大 138、
拉伸后众数 255，覆盖的像素集合不变（400px 下逐像素一致，差异只在两个交点附近）。

### 修复

`act_share_thing_file.xml` 把外框与折角合进同一个 `<path>`（同一个 path 内的重叠按并集
只画一次）；两条内容线与外框不相交，保持单独一个 path。图标注释里记下了这条约束。

### 真机验证（三星 SM-S9180，测试记事 `PROBE-0921-GRAD`）

按图标画布逐列算出渐变的满不透明颜色 G(x)，每个墨迹像素的有效不透明度取
(底色 − 像素) / (底色 − G(x))。只统计画布中部（排除对话框圆角外的页面底色）：

| 条件 | 文本与图片 | 长截图 | 记事文件 |
|---|---|---|---|
| 渐变，修改前 | 1.00 | 1.00 | **0.69**（另有 423 个交点像素高于 0.74） |
| 渐变，修改后 | 1.00 | 1.00 | 1.00（无高于众数 +0.05 的像素） |
| 纯色，修改后 | 0.54 | 0.54 | 0.54（最大 0.541，交点不再偏深） |

渐变色取自数据库里该记事的背景 JSON（`#72166C → #A80ED6`，`L_R`）。对比图在会话
scratchpad 的 `share-icon-gradient-fix.png`。

测试记事已移入回收站（`state=2`）；9-21 批量分享时留在应用外部目录 `files/pull/` 下的
`完事儿-2-20260921212132.edthing`（内含两条 `PROBE-0921-MULTI`）一并删除。魅族锁屏未动，
只装了同一个包（安装不需要解锁）。

## 2026-09-23 - 一期代码检查

用户要求检查目前的代码有没有 bug。逐个读完一期全部改动，并把它依赖的既有代码
（`ThingManager.create`、`AutoNotifyHelper`、`ReminderDAO` / `AlarmHelper`、`ModeManager`、
`DetailActivity` 的保存与新建顺序）读到底。结果见 [review-2026-09-23.md](review-2026-09-23.md)：
高 2 条、中 5 条、低 11 条，另有一处与决策不符的缺口（附件逐个勾选未实现）。

最严重的一条是**导入顺序与既有新建流程相反**：`DetailActivity` 先建提醒与习惯、再建记事，
导入器反过来。用户开了「自动提醒」时，`ThingManager.create` 内部的自动提醒检查会对还不存在的
提醒 / 习惯行做非空断言而抛异常，留下缺习惯行、点开即崩的记事。真机测试没撞到，是因为该设置
默认关闭——**真机全过不代表覆盖了依赖用户设置的分支**。

`AppearanceRemapSourcesTest` 本次重跑，8 个用例全部通过。尚未修改代码，等用户确认。

## 2026-09-23 - 缺陷修复与真机复验

用户确认修复全部缺陷，导入先统一落根目录，附件勾选延后。逐条修法与验证结果见
[review-2026-09-23.md](review-2026-09-23.md) 的「修复状态」表，决策见 decisions.md 同日四条。

### 改动的文件

| 文件 | 改动 |
|---|---|
| `thingfile/ThingFileImporter.kt` | 重写：附件后台改名挪动、数据库逐条切主线程；先建提醒与习惯；过期提醒写 `EXPIRED`；落根目录；失败回滚；刷新小部件 |
| `thingfile/ThingFileReader.kt` | 去掉 2 GB 单条上限改查剩余空间；附件缺失整份报错；类型规整与习惯周期校验；清单 32 MB 上限；`cleanUpStale` |
| `thingfile/ThingFile.kt` | `PendingThing` 加 `attachmentTypes`；纯函数 `normalizeType` / `fileNameStem` |
| `thingfile/ThingFileMessages.kt` | 新增：报错原因取字符串资源，系统消息补在括号里 |
| `thingfile/ThingFileSharer.kt` / `ThingFileWriter.kt` | 去掉内部鉴权；文件名按码位截断；报错本地化 |
| `managers/ThingManager.kt` | `create` 加 `inheritCurrentFolder`（默认 true） |
| `managers/ModeManager.kt` | 「分享为记事文件」与「导出」同一条可见性规则 |
| `activities/ThingsActivity.kt` | 选择模式分享先鉴权，通过后才退出选择 |
| `activities/DetailActivity.kt` | `buildShareSnapshot()`；「文本与图片」「记事文件」都用快照；拆出无副作用的 `currentAttachmentStr()` |
| `activities/ThingFileImportActivity.kt` | 底部按钮避开导航栏；`shutdown()`；读取前 `cleanUpStale` |
| 两份外观模型 `remapSources` | 空映射同样全部丢掉 |
| 字符串（英、简中） | 11 个报错原因 |
| 测试 | `AppearanceRemapSourcesTest` 改 1 加 3；新增 `ThingFileHelpersTest` 9 个 |

### 真机复验（三星 SM-S9180）

构造的 `probe-fix.edthing` 放进应用外部目录、以 `file://` 打开。为验证导入落点，先用两条测试记事
拖放生成测试文件夹 `PROBE-0921-DIR`，并让首页停在这个文件夹里再导入。为验证缺陷 1，临时把
`auto_notify` 从 0 改成 7（调试包里是 1 天后，测试期间不会弹自动提醒），验完改回 0 并核对文件头。

| 条目 | 结果 |
|---|---|
| 预览 | 7 条；`type=-1` 被跳过；缺习惯、习惯周期写错、带提醒的笔记三条都显示为「记录」 |
| 导入 | 提示「已导入 7 件记事」；文件夹视图里仍只有原来 2 条；根目录顶部出现 7 条 |
| 数据库 | `folder_id` 全为空；R1 提醒 state=0；R2 state=3；习惯 type=5 detail=21:30、1 个习惯提醒；三条畸形条目为 NOTE 且无提醒无习惯 |
| 文件 | `import_1790168902715_0_0.png`、`_3_0.png`、`_3_1.mp3`，md5 与原文件一致；临时目录为空 |
| 闹钟 / 通知 | 只有 R1（9-26 20:58）的提醒闹钟与习惯 21:30 的闹钟；通知栏无完事儿通知；R2 卡片显示「已过期」 |
| 习惯记事 | 点开正常，「提醒我于 每天21:30」 |
| 详情页分享 | 追加未保存的文字后分享：「文本与图片」的分享文字、「记事文件」的正文里都有这段修改 |
| 畸形文件 | 声明了附件却缺失：「记事文件读不出来：附件「missing-photo.png」缺失或已损坏」 |
| 菜单 | 只选记事时有「分享为记事文件」，只选文件夹时没有 |

### 驱动上的两点新发现

- 三星上进入选择模式：`input motionevent` 分段发送「按下、停 0.8 秒、下移 80 px、移回、抬起」。
  `input swipe` 原地按住会被当成点击，打开详情页。
- 拖放生成文件夹时，命中判定看的是**拖动浮层的左上角**（内缩 16dp），不是手指位置；矮卡片的
  判定区只剩 86 px 高，终点要按手指在源卡片里的偏移去算。

### 清理

测试文件夹已解散（确认框说明文件夹本身直接删除、内容移回上一级）；9 条测试记事移入回收站；
推送与生成的 `.edthing` 全部删除；`auto_notify` 已恢复为 0。R1 的提醒闹钟随记事进回收站后仍在
（见上面 9-21 那条的更正），到点时接收器会把它标成已过期、不发通知。魅族未动。
