# Immersive Thing List 会话记录

## 2026-07-05 — 修正选择模式入场不应隐藏 Activity Header

- 反馈：长按记事出现 contextual toolbar 时，Activity Header 也一起消失了。检查确认原因是上一轮为隐藏旧
  home actionbar 复用了 `ActivityHeader.setHomeChromeVisible(false)`，而该方法同时把 `rl_header`
  设为不可见。
- 修复：将该方法收窄并重命名为 `setHomeToolbarChromeVisible()`，只显隐 `view_status_bar`、
  `view_status_bar_scrim` 与 `actionbar`；Activity Header 本体保持可见。折叠到顶部区域的标题仍由
  contextual toolbar 层级自然覆盖，展开态标题不会在进入选择时突然消失。
- Verification：`:app:assembleDebug` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/immersive-thing-list/debug-updates/update-20260705230935.md"`
  发布成功，更新码 `202607051510`。未使用 adb，需真机确认长按进入选择模式时 Activity Header 保持可见。

## 2026-07-05 — 统一 contextual actionbar 与正常 actionbar 的阴影

- 反馈：用户感觉 contextual actionbar 的阴影比正常 actionbar 淡。检查后确认二者虽然共用
  `@drawable/actionbar_shadow`，但正常 home actionbar 阴影是 `4dp` 且完全显示时 alpha 为 `1.0`；
  contextual actionbar 阴影此前是 `5dp` 且固定 `alpha=0.6`，因此视觉上更淡、更散。
- 修改：`app/src/main/res/layout/include_contextual_toolbar_things.xml` 中 contextual 阴影改为 `4dp`，
  并移除额外 `alpha=0.6`，保持与正常 actionbar 同强度、同 drawable。
- Verification：`:app:assembleDebug` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/immersive-thing-list/debug-updates/update-20260705202020.md"`
  发布成功，更新码 `202607051221`。未使用 adb，需真机确认选择模式入场动画中的阴影连续性。

## 2026-07-05 — 调整选择模式入场：让 contextual toolbar 覆盖已显示的 home actionbar

- 反馈：上一版把旧 home actionbar 阴影立即隐藏，虽然避免了阴影残留，但会出现 home actionbar/阴影突然消失，然后 contextual toolbar 再下滑出现的断裂感。期望是在原 actionbar 已显示且带阴影时，contextual toolbar 直接下滑盖住它。
- 诊断：进入选择模式有两类状态：① home actionbar 已完全显示并有阴影；② home chrome 仍隐藏或半隐藏。前者适合覆盖入场，后者不应为了入场先弹出 home actionbar。
- 修复：`ActivityHeader.canContextualToolbarCoverHomeChrome()` 判断 home actionbar 是否完全显示且阴影可见；`ModeManager.toSelectingMode()` 在该场景下保留 home chrome，等 contextual toolbar 360ms 入场完成后再隐藏底层 home chrome/旧阴影。其它场景仍直接隐藏 home chrome。
- Verification：`:app:assembleDebug` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/immersive-thing-list/debug-updates/update-20260705200915.md"` 发布成功，更新码 `202607051209`。未使用 adb，需真机确认选择模式入场动画连续。

## 2026-07-05 — 修复文件夹默认页顶部三段背景分界线

- 反馈：打开文件夹后，默认状态下 statusbar、actionbar、记事列表三块之间会出现两条分界线；预期是整页直接呈现为当前文件夹背景派生出的淡色版本。
- 诊断：沉浸式布局为了保证 actionbar 显示时不透出其下卡片，把同一个 `mutedSurfaceBackground` 分别应用到 `fl_things`、`view_status_bar`、`actionbar`。纯色背景不会暴露问题；渐变文件夹背景会在三块 View 内各自重启渐变，导致交界处有色差。
- 修复：`ThingsActivity.applyThingsActivitySurfaceBackground()` 保留 `fl_things` 的整屏淡色 surface，同时让 `view_status_bar` 与 `actionbar` 通过 `ProjectedHomeChromeSurfaceDrawable` 绘制整屏 surface 在自身位置上的切片；纯色路径仍使用原有 `BackgroundUtil.applyBackground`。`actionbar_shadow` 的滚动出现逻辑未改。
- Verification：`:app:assembleDebug` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/immersive-thing-list/debug-updates/update-20260705180256.md"` 发布成功，更新码 `202607051003`。未使用 adb，默认态视觉仍需用户通过 debug 更新真机确认。

## 2026-07-01 — grill-with-docs 设计定稿

通过 grill-with-docs 逐项确认了 ThingsActivity 沉浸式记事列表的设计，未写实现代码。
定稿决策见 `decisions.md`，路线见 [ADR-0013](../../adr/0013-immersive-thing-list-manual-scroll-chrome-retraction.md)，
术语写入根目录 `CONTEXT.md`（Immersive Thing List / Home Chrome Retraction /
Activity Header ＋ 一条 edge-to-edge 的 Flagged Ambiguity）。

要点：交互＝连续跟手＋松手吸附；状态栏＝保护性渐变罩；折叠（位置驱动）与隐藏（方向驱动、
enterAlways，小标题 pinned）解耦；模式复位策略（搜索/选择强制显示、MOVING 保留、返回复位为
显示）；FAB 保持现状各自独立；实现走手写滚动驱动、`rv_things` 铺满全高 + `paddingTop` 预留。
实现期边界见 `followups.md`。

## 2026-07-01 — 实现完成（代码），待设备验证

按 `plan.md` 五个阶段实现，全程通过 `:app:assembleDebug`。改动文件：
- `res/layout/activity_things.xml`：`fl_things` 填满全高、子级重排（rv 垫底、chrome 浮层在上）、
  `view_status_bar` 移到 fl_things 之后作顶层 scrim。
- `utils/DisplayUtil.kt`：新增 `applyImmersiveListInsetPadding`（单回调管 rv 顶/底 padding）与
  `resolveActionBarSize`。
- `activities/ThingsActivity.kt`：`updateStatusBarLayoutOffsets` 设新的动态 margin/padding；
  `onScrolled`/`onScrollStateChanged` 接 `updateHomeChromeRetraction`/`snapHomeChromeRetraction`；
  `immersiveEligible`；`applyStatusBarScrim`；搜索/刷新处复位 retraction。
- `views/ActivityHeader.kt`：retraction 偏移 ＋ `applyRetractionTransforms` ＋ `setRetractionOffset`
  ＋ `isFullyCollapsed` ＋ `getMaxRetractionPx`（= actionbar.bottom）；`scrollY` 按 paddingTop 偏移；
  `reset` 清零 retraction。
- `managers/ModeManager.kt`：进入/退出 SELECTING 复位 retraction。

关键实现选择：顶部 chrome 收起量 `R = -translationY`，最大 = `actionbar.bottom`（即 SB+AB，
把 actionbar 底边移到 y=0 即完全隐藏）；rv 用 `paddingTop=SB+AB` + `clipToPadding=false` 让卡片
始终绘制在 chrome 之下，收起 chrome 即露出；`view_status_bar` 常驻不随 chrome 收起。
待设备验证项见 `execution.md` 中 `(设备验证)` 条目。

## 2026-07-01 — 迭代：原地改单项保留沉浸态 ＋ 首次发布阿里云

- 按反馈改为「原地完成/删除/恢复单项保留沉浸态」：`refreshActivitySurfaceAndHeader` 加
  `resetRetraction` 形参、仅 `updateUIAfterStateUpdated` 传 `false`；并在 `ActivityHeader.updateHeader`
  加不变量「progress<1 → retraction=0」，删项使列表变短时安全退回。详见 `decisions.md`。
- 顺带把「卡片外观面板打开挂起 retraction」纳入 `immersiveEligible()`。
- 发布 debug 更新到阿里云：更新码 202607010156，日志见
  `debug-updates/update-20260701095553.md`。待真机验证。

## 2026-07-01 — 修复：多行文件夹标题的折叠间距 ＋ 提前沉浸

- 真机反馈：打开名字较长（标题多行）的文件夹上滑，标题归位 actionbar 时第一张卡片离 actionbar
  比单行时远，且行数越多越远；沉浸判定也可能提前。根因是折叠进度用固定 `90dp` 驱动、而 header
  spacer 随标题行数增高，二者脱钩。详见 `decisions.md` 同日条目。
- 改动集中在 `views/ActivityHeader.kt`：`getTitleCollapseScrollY()` 改为 `getHeaderSpacerScrollY() −
  TITLE_DOCK_RESIDUAL_DP(12dp)`；`updateAll` 三处强制折叠用 `ceil(getTitleCollapseScrollY())`（保证
  progress 取到 1、`isFullyCollapsed`/retraction 不失效）；阴影淡入公式的硬编码 90/12 改用动态折叠点与
  新常量。单行/根标题折叠距离回到 90dp，无回归。
- 同批还修了一个 EditText 彩色下划线随长文本左移的通用 bug（`utils/BackgroundUtil.kt` 的
  `BottomLineDrawable.draw` 补偿 host 的 scrollX/Y），由「调整文件夹外观」面板的名称输入框暴露，见
  `docs/features/thing-folders/sessions.md`。
- Verification：`:app:assembleDebug` 通过。未使用 adb，待真机视觉验证。
- 发布：`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/immersive-thing-list/debug-updates/update-20260701115432.md"`，
  更新码 `202607010355`，远端 `latest.json` 指向
  `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607010355.apk`。尚未提交，待真机确认后提交。

## 2026-07-01 — 修复：点击搜索时列表自动滚到底部

- 真机反馈：列表停在顶部（未滑动）点搜索，列表莫名滚到底部。根因是进入搜索用了
  `mRecyclerView.scrollBy(0, Int.MIN_VALUE)`（Kotlin 迁移自原 Java 的"一次滚到顶"写法）：
  `LinearLayoutManager.scrollBy` 内部 `Math.abs(Int.MIN_VALUE)` 整数溢出仍为负，导致
  `offsetChildren(Int.MIN_VALUE)` 天量位移子项；沉浸式改了 rv 的 padding/布局后暴露为"滚到底部"。
- 改法：`ThingsActivity.toggleSearching` 进入搜索分支改用 `scrollToPosition(0)`，与退出搜索分支一致、
  落点稳定。全项目仅此一处该模式。
- Verification：`:app:assembleDebug` 通过。发布更新码 `202607010459`，日志
  `debug-updates/update-20260701125916.md`。未使用 adb，待真机验证。尚未提交。

## 2026-07-01 — 统一 actionbar 到第一张卡片的间距为 16dp

- 真机反馈：搜索态首卡到 actionbar 的间距比非搜索折叠态小；且非搜索那个间距比卡片间距 16dp 还大。
  根因是两处间距来自零散魔法数（搜索 spacer 6dp、折叠余量 12dp）＋卡片自带 8dp 上边距，得 14dp 与 20dp。
  经询问用户选定统一到 16dp（= 卡片间距），详见 `decisions.md` 同日条目。
- 改动：`views/ActivityHeader.kt` 的 `TITLE_DOCK_RESIDUAL_DP` 12→8dp（折叠态 8+8=16dp，多行恒定；
  折叠距离 90→94dp、阴影淡入 12→8dp 随之联动）；`adapters/ThingsAdapter.kt` 搜索态 spacer 6dp→
  `thing_card_outer_spacing`(8dp)（搜索态 8+8=16dp）。两处由同一 8dp 单位推导。
- Verification：`:app:assembleDebug` 通过。发布更新码 `202607010603`，日志
  `debug-updates/update-20260701140319.md`。未使用 adb，待真机验证。尚未提交。

