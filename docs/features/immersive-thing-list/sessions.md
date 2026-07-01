# Immersive Thing List 会话记录

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
