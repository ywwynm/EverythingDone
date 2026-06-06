# Current Debug Update Notes

用户反馈：当首页新记事动画由 `ShiningBorder` 驱动时，新记事已经完成创建、对应卡片的 border 动画还在播放期间，如果用户滑动 `RecyclerView`、旋转屏幕，或其它记事更新导致列表重排，动画路径可能继续按旧坐标播放，从而出现错位或状态不一致。用户同意先采用短期方案，同时提醒“别的记事更新”等情况也可能影响记事列表外观和新增卡片位置。

诊断结果：新卡片 `ShiningBorder` 是 item-scoped 动画，但实际绘制在全局 overlay 上，路径由卡片和 overlay 的 window 坐标一次性计算出来；因此滚动、配置变化、搜索/筛选、撤销、排序、Detail 返回更新、delayed adapter refresh、卡片外观预览刷新等都会使这条路径失效。非 shining 的新卡片 reveal 使用 `ViewAnimationUtils.createCircularReveal()` 直接作用在 card view 上，是 card-level 动画，不是同类坐标风险；FAB 进入 Detail 的 reveal 则是单独的全局 `RevealLayout` 入口，发生在创建前。

本次修改：更新 `app/src/main/java/com/ywwynm/everythingdone/activities/ThingsActivity.kt`，为新卡片 `ShiningBorder` 增加独立状态和 token。guard 从卡片被隐藏后的 180ms pending 阶段开始生效，防止旧 delayed runnable 在动画已取消后继续启动。动画期间会停止 `RecyclerView` 滚动、拦截列表和卡片触摸、阻止长按进入选择模式，并通过 `ItemTouchHelper` 禁用 drag/swipe。任何会改变列表几何的入口在刷新前都会先调用 `finishNewItemShiningBorderAnimationIfNeeded()`，把目标卡片恢复为 `VISIBLE`、隐藏并 reset `ShiningBorder`、恢复默认 border 参数并清理 reveal 状态。

覆盖的几何失效入口包括：`onConfigurationChanged()`、`onPause()`、`updateMainUi()` 和各类 delayed `updateMainUiFor*` 刷新、`justNotifyAll()`、搜索/退出搜索、抽屉筛选切换、undo snackbar、habit undo、按提醒时间排序、重置 alarm 后刷新、卡片外观预览刷新，以及用户触发的滚动、长按、拖拽和滑动路径。

评审与后续：根据代码 review，当前实现没有明显 crash 级问题。非 shining 的 card-level reveal 暂不需要和 `ShiningBorder` 同等级处理，因为它绑定在 card view 上，card 移动时动画会跟随 view。已在 `docs/features/home-new-item-animation/decisions.md` 记录 reveal 判断，并在 `docs/features/home-new-item-animation/followups.md` 记录后续可选方向：未来可做通用 new-card entry animation state 或 geometry-aware border anchoring。

文档：新增 `docs/features/home-new-item-animation/`，并更新 `docs/features/README.md`、`memory/decisions.md`、`memory/followups.md`、`memory/sessions.md` 的轻量索引。

验证与发布：`.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过。本次将调用 `:app:publishDebugUpdate` 发布到 debug update channel。
