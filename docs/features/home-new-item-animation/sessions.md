# Sessions

## 2026-09-14 - 涟漪档卡片级揭示在长滚动后失效

- 用户反馈：涟漪档新建完成后卡片级涟漪缺失；补充为自己的手机、置顶记事很多、需要先滚动到新记事再播放的场景。
- 复现：R5CW20BLNKL 先在原地、先滚动再新建、3.6 屏置顶块三种场景均正常；置顶块加高到约 4.5 屏后复现“直接出现”。9018f404 原地场景（5 倍动画时长连拍）正常。证据在 `tmp/thing-animation-device/ripple-card-2026-09-14/<serial>/`（`ripple-card-5-taller` 为失败样本，`pop-zoom.png` 是弹出前后 6 帧）。
- 定位：加 `NewItemReveal` 日志后确认揭示动画启动后 11 ms 即结束，卡片 View 是脱离窗口时绑定的复用 holder。把等待期改为 alpha 0 VISIBLE 的实验（`ripple-card-8-alpha0`）在同一场景恢复为 551 ms 揭示。
- 修复后复测（`final-*`）：三星 4.5 屏置顶块涟漪 550 ms 揭示完整、同场景边框光效卡片按原样淡入、带小段滚动的新建揭示完整且揭示前无提前露出；OPPO 原地涟漪在 5 倍时长下 2720 ms 揭示完整。相关 JVM 测试 7 项通过（`ShiningBorderPathTest` 5、`ThingAnimationPreferencesTest` 2）。
- 兜底改为轮询等待滚动结束；旧的固定 1200 ms 超时在更高置顶块上会先于滚动结束触发。
- 清理：两台设备的测试记事经真实多选工具栏移入可恢复的回收站，动画档位与动画时长缩放恢复原值。

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
