# Decisions

Global startup decision index only. Feature-specific decisions live in `docs/features/<kebab-case-feature-slug>/decisions.md`.

## 2026-10-13 - 既有深色配色由应用自行处理，避免厂商二次反色

魅族 Flyme 会在标准 `forceDarkAllowed=false` 之外改写 Canvas 颜色，造成原生选择卡片与动画颜色不连续，软件截图也受影响。已通过该设备框架代码及像素对照确认 `Application.mzNightModeUseOf()` 返回 0 可声明应用自理配色；`App` 保留此回调名，不调用隐藏 API、不按机型切动画方案。继续遵守用户的跟随系统／固定浅色／固定深色设置。证据和回归见 `docs/features/selection-batch-actions/transition-2026-10-13.md`。

## 2026-09-15 - 「这条路走不通」的理由要标明在哪条实现上成立

粒子消散的抓图长期排除「离屏重录 + 逐个 Surface 合成」这条路，理由写在注释里：
「离屏重录画不出 `SurfaceView` 的真实透明洞」。这句话对**软件 `Canvas`** 成立，
对实际在用的 `RenderNode.beginRecording()` / `RecordingCanvas` 不成立——`SurfaceView.draw`
在后者上照常记下 `punchHole`（`BaseRecordingCanvas` 有原生实现）。这条被误推广的限制
让「有可见 Surface 的弹窗」整整多卡了一轮：它们只剩窗口 `PixelCopy` 一条路，而那条路在
某些设备上不可靠，结果就是消散整场不播。

规则：**写下「不能用 X，因为 Y」时，要指明 Y 是在哪条具体实现（哪个类、哪种 Canvas、
哪个 API 版本）上观察到的**；后来的人才有可能发现前提已经不适用。复核这类结论时，优先
找能直接证伪的实测，而不是重读同一段注释——本次只花了一轮真机就把它推翻了。

## 2026-09-15 - 建库时的初始数据 insert 一律显式列名，不得依赖列序

`DBHelper.SQL_INSERT_HEADER`（建库时插入 id=0 的 header 行）原先写成
`insert into things values(...)` 的位置形式。2026-06-23 的提交 9dcb5146e 给 `things` 表加了
第 18 列 `state_before_delete`，值列表没同步，于是**此后所有全新安装在启动时崩溃**
（`SQLiteException: table things has 18 columns but 17 values were supplied`，抛在
`App.onCreate` → `AppUpdateHelper` → `ThingDAO` → `DBHelper.onCreate`，属于 Application
创建失败，应用每次启动都闪退且无法恢复）。已存在的安装走 `onUpgrade` 的
`alter table add column`，完全不受影响——这就是它在两台常用真机上近三个月没暴露的原因。

因此：**`onCreate` 里的初始数据 insert 必须显式列出列名**，只写该行真正需要的列，其余交给
表定义的默认值。加列时便不再需要同步修改 insert。`things` 表目前只有这一处初始数据 insert。

这条 insert 写入的行会被 `ThingDAO.init` 的 `recreateHeader()` 立刻删掉重建（id 取
`max(maxId, maxLocation) + 1`，所以全新安装后 header 行的 id 是 1 而非 insert 写的 0），
但**它不能因此被删掉**：`recreateHeader()` 在 `moveToFirst()` 之后直接 `getLong(0)`，空表会抛
`CursorIndexOutOfBoundsException`。它的作用就是保证 `things` 表非空。`createHeader()` 本身用
`ContentValues`，天然不受加列影响——具名才是这个项目里的常态，`DBHelper` 那条手写 SQL 是历史遗留。

对应的验证纪律：**schema 变更的验收必须包含一次全新安装**（卸载后装，或换一台没装过的设备），
仅做覆盖安装验证的是 `onUpgrade` 这一条路径。

## 2026-09-17 - 自定义动画节拍必须锚在真实 vsync 上，不能按固定间隔累加

「起点 + k × 16 666 666 ns」这种自由计时会相对面板持续漂移：魅族 20 Infinity 触摸期两个
vsync 实测 **16.55 ms**（面板 120.8 Hz），每拍多 **0.117 ms**，几十拍后目标就滑过一个 vsync，
同一批帧的上屏时刻散在四个档位上，间隔在 8.3 / 16.6 / 24.8 / 33.1 ms 之间跳——均值看着达标、
看起来一直在抖。正确做法：**目标直接取本次 vsync 的时刻**，出不出这一拍按「距上一拍够不够久」
判（门限 `目标间隔 − 半个 vsync − 余量`），「一拍多长」用量出来的节拍间隔而不是写死的常量。
**不要按 vsync 计数分频**：非 UI 线程上的 Choreographer 投递本身就不规律（同一次手势里 8.28
与 16.55 交替），按计数分频会跟着乱。

两条配套的平台事实：① `Choreographer.FrameData` 给的「预期呈现时刻」在魅族上是
**vsync + 26.0 ms**，而实际达到的是 vsync + 17.7 ms，把它当目标等于主动多等一个显示周期；
② `eglPresentationTimeANDROID` 在那台上**不能用来推迟自己的帧**（下限写在 vsync + 12.6 ms，
仍有 28/69 帧在 vsync + 8.3 ms 就上屏）。出处见
`docs/features/particle-animation-performance/per-frame-batch3-2026-09-17.md`。

## 2026-09-17 - 同一层两张缓冲落进同一个刷新周期，先提交的那张会被丢掉且永远等不到呈现时刻

任何「不按节拍、临时多提交一帧」的做法，都必须把它要替掉的那一拍真的跳过。
SurfaceFlinger 每层每个 vsync 只 latch 一张缓冲，先提交的直接丢弃，而被丢弃的那一张
**永远等不到 `EGL_DISPLAY_PRESENT_TIME`**——凡是按呈现时刻保序放行的待确认队列都会被它卡住，
症状看起来只是「首帧上屏晚了几十毫秒」，真正的指纹是**一批确认时刻挤在一两毫秒之内**。
出处与完整数据见 `docs/features/particle-animation-performance/per-frame-batch2-2026-09-17.md` 第 4 节。

## 2026-09-15 - 逐位对照在报告差异之前必须先做自比较

GPU 侧的「逐位相同」验收有设备本底噪声：OPD2515（Adreno 829）上同一条命令流重复跑，约
0.5–0.9% 的运行会有一帧不一样（整帧个位数像素）。因此**孤立单帧、个位数像素的差异不得直接
判成回归**，判据分两步：先让同一条路径自己跑多遍，自比较也出分歧就是本底噪声，自比较干净而
A/B 有差异才是真回归。两条常见误判要一并排除：逐帧 `glReadPixels` 连读两次排除回读不稳；
把并发负载做成可独立开关的自变量、各跑几百次试次再比事件率（两三次运行的「关掉就清零」在
1% 量级的事件率下毫无信息量）。完整证据见
`docs/features/particle-animation-performance/batch5-results-2026-09-15.md`。

同一轮审查得到的另一条通则：**诊断工具不得改生产路径会读的进程级状态**，要改就改成线程局部的。

## 2026-07-27 - 勾选框两种状态都跟随强调背景，对号颜色自适应

`BackgroundUtil.applyCheckboxAccent` 新增 `uncheckedGradient`：打开后**未选中**的方框描边也用完整 `ThingBackground`（渐变保留起止色与方向，**不降 alpha**——描边只有 2dp 宽，淡一点就发虚），而不是中性的 `app_chrome_control_unchecked`。已在 FableSol 调参 Dialog 的全部勾选行与 `SettingsActivity` 的 13 个勾选框打开；该参数默认 false，其余调用点（Detail、Widget 配置、`DisplayUtil.setCheckBoxColor`）外观不变。

选中态的对号由固定白色改为 `onColor(background, 1f)`，**全部**勾选框一并生效：浅色强调色（例如明黄）上的白对号本来就看不出来。走 `ThingBackground` 那个重载而不是先取 `representativeColor()`，否则认不出 App 默认强调渐变。

一条实现约束：换色重建时必须把 `uncheckedGradient` 一起带上，漏掉不会报错，只会在第一次换色后悄悄退回灰描边。因此按“每一次 `applyCheckboxAccent` 调用都必须带着它”由源码契约测试钉住，而不是数固定次数。

## 2026-07-17 - 调研归档产物随 feature 目录本地存放且不进版本库

大体积或一次性的调研产物（LaTeX 报告及配图、对比图集、字体候选与子集化中间产物）归档在所属 `docs/features/<slug>/` 目录下，并在根 `.gitignore` 中按具体路径忽略，不进入版本库；已提交文档按目录名提及它们即可。本仓库亦不跟踪 `Everything-Android/`（独立 git 仓库，仅本地参考）。轻量调研 md 是否入库由用户逐一决定。

## 2026-06-24 - Things without a title can be made private

Setting a Thing private no longer requires a non-empty title. The previous
empty-title guards were removed from all three entry points (Detail return,
single-select toggle, multi-select batch). Privacy stays encoded as the
`PRIVATE_THING_PREFIX` title prefix, so a no-title private Thing simply stores
the prefix with an empty display title. The batch "skipped" Toast now only
covers the currently-timed (doing) Thing.

## 2026-06-18 - KeyboardUtil uses WindowInsets for IME visibility

`KeyboardUtil` should control IME show/hide through AndroidX
`WindowCompat.getInsetsController(...).show/hide(WindowInsetsCompat.Type.ime())`
instead of `InputMethodManager` or soft-input state flags. Callers with only a
`View` can resolve the owning `Activity.window` and then use the same
WindowInsets path.

## 2026-06-06 - Memory files are lightweight global indexes

Canonical memory files should stay small and cross-feature. Detailed feature-scoped decisions, follow-ups, and session history belong under the relevant `docs/features/<kebab-case-feature-slug>/` directory. Agents should read global memory at session start, then read the relevant feature directory before working in that area.

## 2026-06-06 - Feature documentation directory structure

Feature-specific project documentation is organized under `docs/features/<kebab-case-feature-slug>/`, with one directory per feature, migration, review track, or substantial technical initiative. `CONTEXT.md`, `docs/adr/`, and canonical `memory/*.md` files remain global sources.

## Feature decision indexes

- `android-16-migration`: `docs/features/android-16-migration/decisions.md`
- `animated-video-cover`: `docs/features/animated-video-cover/decisions.md`
- `app-chrome-polish`: `docs/features/app-chrome-polish/decisions.md`
- `appwidget-platform-compat`: `docs/features/appwidget-platform-compat/decisions.md`
- `cloud-sync`: `docs/features/cloud-sync/decisions.md`
- `color-system-migration`: `docs/features/color-system-migration/decisions.md`
- `dark-mode`: `docs/features/dark-mode/decisions.md`
- `detail-color-sampling`: `docs/features/detail-color-sampling/decisions.md`
- `detail-attachment-media-appearance`: `docs/features/detail-attachment-media-appearance/decisions.md`
- `doing-thing-organize`: `docs/features/doing-thing-organize/decisions.md`
- `home-contextual-toolbar`: `docs/features/home-contextual-toolbar/decisions.md`
- `home-empty-state`: `docs/features/home-empty-state/decisions.md`
- `home-new-item-animation`: `docs/features/home-new-item-animation/decisions.md`
- `kotlin-migration`: `docs/features/kotlin-migration/decisions.md`
- `localization`: `docs/features/localization/decisions.md`
- `popup-picker-insets`: `docs/features/popup-picker-insets/decisions.md`
- `project-maintenance`: `docs/features/project-maintenance/decisions.md`
- `ratio-slider`: `docs/features/ratio-slider/decisions.md`
- `audio-visualization-fable-sol`: `docs/features/audio-visualization-fable-sol/decisions.md`
- `remote-thing-card-appearance`: `docs/features/remote-thing-card-appearance/decisions.md`
- `selection-batch-actions`: `docs/features/selection-batch-actions/decisions.md`
- `undo-to-confirm-dialog`: `docs/features/undo-to-confirm-dialog/decisions.md`
- `system-bar-insets`: `docs/features/system-bar-insets/decisions.md`
- `thing-folders`: `docs/features/thing-folders/decisions.md`
- `thing-card-image-placement`: `docs/features/thing-card-image-placement/decisions.md`
- `thing-card-media-target-geometry`: `docs/features/thing-card-media-target-geometry/decisions.md`
- `timely-digit-typography`: `docs/features/timely-digit-typography/decisions.md`
