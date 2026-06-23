# Sessions

## 2026-06-23 - 新记事入场动画先滚到位再播放

- `ThingsActivity.updateMainUiForCreateDone` 正常插入路径：插入后先平滑滚动新记事到工具栏正下方（`LinearSmoothScroller` + `SNAP_TO_START`；RecyclerView 在工具栏下方 `marginTop=actionBarSize`，故无需额外偏移；滚不到时自然 clamp），滚动 IDLE 后再播放。新增门控字段 `mNewItemReveal*` 与方法 `beginGatedNewItemReveal` / `scrollNewItemFullyIntoViewThenReveal`（preDraw 后决策）/ `maybeRevealGatedNewItem` / `abortGatedNewItemRevealIfNeeded`。
- 程序化滚动期间在 `onScrolled` 驱动 `mActivityHeader.updateAll` 让头部跟随折叠；`onScrollStateChanged` IDLE 触发揭示；1200ms 安全兜底；中断（旋转/撤销/再次创建）经 `finishNewItemShiningBorderAnimationIfNeeded` 调 abort 恢复可能已隐藏的卡片。
- `onNewItemBound` 为 holder 唯一来源以避免重复播放；揭示与 shining 两风格通用；已完整可见则不滚动、原地播放；`justNotifyAll` / 类型筛选重置路径维持原状。详见 `decisions.md`。已编译通过，随 debug update `202606230911` 发布，待设备验证。

## 2026-06-06 - Stabilize new-item ShiningBorder geometry

- Added a dedicated new-item `ShiningBorder` guard in `ThingsActivity`.
- The guard covers the 180ms pending window before the border starts, the border
  playback itself, RecyclerView/item touch, configuration changes, lifecycle
  pause, search/filter refreshes, undo operations, delayed detail-result
  updates, and card-appearance preview refreshes.
- Avoided orientation locking because Android large-screen behavior and Android
  16 compatibility make fixed orientation an unreliable control surface.
