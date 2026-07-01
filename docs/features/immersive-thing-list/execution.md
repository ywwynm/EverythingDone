# Immersive Thing List 执行清单

进度标记：`[ ]` 未做 · `[~]` 进行中 · `[x]` 完成。代码项已全部完成并通过
`:app:assembleDebug`；标 `(设备验证)` 的项需在真机/模拟器上肉眼确认（本环境不联机操作）。

## Phase 0 — 布局重构（几何对齐、无新行为）
- [x] `activity_things.xml`：`DrawerLayout` 子级重排为 `fl_things` → `view_status_bar` → `drawer`
- [x] `activity_things.xml`：`fl_things` 子级重排为 `rv_things` → `home_empty_state` → `actionbar`
      → `rl_header`(include) → `actionbar_shadow` → `panel` → `fab`
- [x] `rv_things` 去掉 `layout_marginTop="?attr/actionBarSize"`（改代码设 `paddingTop`）
- [x] `updateStatusBarLayoutOffsets`：`fl_things.topMargin = 0`
- [x] `updateStatusBarLayoutOffsets`：动态设 `actionbar.marginTop=SB`、`rl_header.marginTop=SB+82`、
      `home_empty_state.marginTop=SB+AB`、`actionbar_shadow.marginTop=SB+AB`
- [x] `DisplayUtil.applyImmersiveListInsetPadding`（顶=SB+AB、底=navbar，单回调；`resolveActionBarSize`）
- [x] 替换 `applyBottomInsetAsScrollPadding(rv)` → `applyImmersiveListInsetPadding(rv)`
- [x] 编译通过（`:app:assembleDebug`）
- [ ] (设备验证) 静止态与今天一致、首帧无跳变

## Phase 1 — 折叠数学适配 paddingTop
- [x] `ActivityHeader.updateAll` 的 `scrollY = rv.paddingTop - firstChild.top`
- [ ] (设备验证) 折叠时机与幅度与今天一致

## Phase 2 — Home Chrome Retraction 控制器
- [x] `ActivityHeader`：`retractionOffsetPx` / `headerCollapseTranslationY` / `mRetractionAnimator` 字段
- [x] `ActivityHeader.applyRetractionTransforms()`（actionbar / shadow / rl_header）
- [x] `ActivityHeader.setRetractionOffset(px, anim)`（ValueAnimator 吸附）/ `getRetractionOffset` /
      `getMaxRetractionPx`（= actionbar.bottom）/ `isFullyCollapsed`
- [x] `updateHeader` 两路都经 `applyRetractionTransforms`；存 `headerCollapseTranslationY`
- [x] `ThingsActivity.onScrolled`：`updateHomeChromeRetraction(dy)`（仅 finger、仅 eligible、仅折叠后）
- [x] `ThingsActivity.onScrollStateChanged`：`snapHomeChromeRetraction()`（吸附最近端态）
- [x] `immersiveEligible()`：NORMAL/MOVING && !searching
- [x] 编译通过
- [ ] (设备验证) 连续跟手 ＋ 吸附；仅 NORMAL/MOVING

## Phase 3 — 状态栏 scrim ＋ 图标
- [x] `view_status_bar` 竖向渐变 scrim（`applyStatusBarScrim`，surface 派生、随 surface 更新）
- [x] 图标明暗沿用 dark/cancelDarkStatusBar（未改）
- [ ] (设备验证) 深色卡片滑到状态栏下图标可读；scrim alpha 视觉微调

## Phase 4 — 模式 / 复位接线
- [x] 进入搜索：`setRetractionOffset(0,false)`（`toggleSearching` else 分支）
- [x] 退出搜索：`reset(false)` 清零（已含）
- [x] 进入 SELECTING：`ModeManager.toSelectingMode` 复位
- [x] 返回 NORMAL（从 SELECTING）：`backNormalMode` / `finishCurrentModeWithoutListRefresh` 复位
- [x] 切换范围 / 换状态 / 开关文件夹刷新：`refreshActivitySurfaceAndHeader` 复位
- [x] `onConfigurationChanged`：`reset(false)` 清零 + 重算 SB/AB（已含）
- [ ] (设备验证) 各模式切换无残留半隐

## Phase 5 — 边界打磨（按需，见 followups）
- [x] 卡片外观面板打开时挂起 retraction（`immersiveEligible()` 加 `!isThingCardAppearancePanelShowing()`）
- [ ] 可滚动余量不足时不维持隐藏
- [ ] 横屏 / 平板几何复核
- [ ] (决策记录) 完成/删除单项（`updateUIAfterStateUpdated`）当前会一并复位 chrome 为显示；
      如需「原地改单项时保留沉浸」再单独细化
