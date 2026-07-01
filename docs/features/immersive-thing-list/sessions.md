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
