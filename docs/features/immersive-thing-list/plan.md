# Immersive Thing List 实现计划

对应决策见 `decisions.md`，路线见 [ADR-0013](../../adr/0013-immersive-thing-list-manual-scroll-chrome-retraction.md)，
边界见 `followups.md`。术语：`Immersive Thing List` / `Home Chrome Retraction` / `Activity Header`。

## 核心思路

把 Thing 列表 `rv_things` 改为**铺满全高、内容始终绘制在顶部 App Chrome 之下**；顶部 chrome
（actionbar＋折叠标题浮层＋阴影）作为**浮层**盖在列表之上，`translationY` 随滚动连续上移隐藏 /
下移回落；状态栏保护罩 `view_status_bar` 作为**常驻**的顶层浅色渐变（不随 chrome 收起），保证系统
图标可读。折叠（位置驱动）与隐藏（方向驱动）解耦。

记号：`SB` = 状态栏 top inset；`AB` = `?attr/actionBarSize`；`R` = chrome 收起位移 ∈ `[0, SB+AB]`
（`0`=全显示，`SB+AB`=全隐藏）。

## 现状坐标系（改前）

- `DrawerLayout` 子级：`view_status_bar`（不透明 surface 底条，[0..SB]）、`fl_things`
  （`topMargin=SB`，[SB..screenH]）、`drawer`。
- `fl_things` 子级（绘制序，底→顶）：`actionbar`（top=0 相对 fl_things，即屏幕 SB）、`rl_header`
  （`marginTop=82dp`）、`rv_things`（`marginTop=AB`，`paddingTop=0`）、`home_empty_state`、
  `actionbar_shadow`、`panel_thing_card_appearance`、`fab_create`。
- `rv_things` 首项是 `Thing.HEADER` 隐形卡（`getActivityHeaderSpacerHeight()`，默认 102dp）作为
  header spacer；`ActivityHeader.updateAll()` 用 `scrollY = -firstChild.top`（首项 top 从 0 起）
  驱动折叠。

## 目标坐标系（改后）

- `DrawerLayout` 子级重排为：`fl_things`（`topMargin=0`，[0..screenH]）、`view_status_bar`
  （移到 `fl_things` 之后绘制，作为顶层 scrim，[0..SB]，不随 chrome 收起）、`drawer`。
- `fl_things` 子级重排（绘制序，底→顶）：`rv_things`（`marginTop=0`、`paddingTop=SB+AB`、
  `clipToPadding=false`）、`home_empty_state`（`marginTop=SB+AB`）、`actionbar`（`marginTop=SB`）、
  `rl_header`（`marginTop=SB+82`）、`actionbar_shadow`（`marginTop=SB+AB`）、
  `panel_thing_card_appearance`、`fab_create`。
- 静止态视觉与今天一致：内容起点仍在 `SB+AB` 之下（`paddingTop` 预留），状态栏区域显示 fl_things
  的 surface 底色（透过浅色 scrim），折叠照常。滚动时卡片进入 `paddingTop` 区、绘制在 actionbar 与
  scrim 之下。

> `SB`/`AB` 相关的动态 margin/padding 统一在 `updateStatusBarLayoutOffsets(statusBarHeight)` 里
> 按当前 inset 设置（`AB` 用 `?attr/actionBarSize` 解析像素）。

## 阶段

### Phase 0 — 布局重构（几何对齐、无新行为）

1. `res/layout/activity_things.xml`：
   - `DrawerLayout` 子级重排：`fl_things` → `view_status_bar` → `drawer`。
   - `fl_things` 内子级重排为上面的目标序；`rv_things` 去掉 `layout_marginTop="?attr/actionBarSize"`
     （改由代码设 `paddingTop`），`actionbar`/`rl_header`/`home_empty_state`/`actionbar_shadow` 的
     `marginTop` 改由代码动态设置（XML 先给静态占位值，避免首帧跳变）。
   - `view_status_bar` 背景由 `@color/bg_activity_things` 改为 scrim gradient（Phase 3 定色，先占位）。
2. `ThingsActivity.updateStatusBarLayoutOffsets(statusBarHeight)`：
   - `fl_things` `topMargin` 由 `statusBarHeight` 改为 `0`。
   - 新增：`actionbar.marginTop = SB`；`rl_header.marginTop = SB + 82dp`；
     `rv_things.paddingTop = SB + AB`（保留左右/底 padding）；`home_empty_state.marginTop = SB + AB`；
     `actionbar_shadow.marginTop = SB + AB`。
   - `view_status_bar` 高度仍 = `SB`（保留现有逻辑）。
3. 验证：编译；静止态与今天一致（状态栏 surface 色、列表在 actionbar 下），首帧无跳变。

### Phase 1 — 折叠数学适配 paddingTop

- `ActivityHeader.updateAll()`：`scrollY` 改为按新的 `paddingTop` 偏移
  （`scrollY = rv.paddingTop - firstChild.top`，等价于旧的"首项 top 从 0 起"）。
- 复核 `getHeaderSpacerScrollY()` / `updateHeaderSpacerHeight()` / 折叠起点，使折叠曲线与今天视觉一致。
- 验证：从顶部下滑，大标题→小标题折叠、阴影渐现的时机和幅度与今天一致。

### Phase 2 — Home Chrome Retraction 控制器

- `ActivityHeader`：
  - 新增 `retractionOffsetPx`（`R`）、`maxRetractionPx = SB + AB`。
  - 抽出 `applyTopChromeTransforms()`：`actionbar.translationY = -R`；
    `actionbar_shadow.translationY = -R`；`rl_header.translationY = collapseTransY - R`。
    `updateHeader()` 末尾与 retraction 更新都调用它（`collapseTransY` 存为字段）。
  - `setRetractionOffset(px, anim)`：设 `R` 并 `applyTopChromeTransforms()`；`anim` 时用 ValueAnimator
    做吸附动画。
  - `isFullyCollapsed()`：`headerCollapseProgress >= 1f`。
- `ThingsActivity`：
  - `onScrolled`（仅 `mScrollCausedByFinger`）：`updateAll` 后，若 `immersiveEligible()` 且
    `ActivityHeader.isFullyCollapsed()`，则 `R = (R + dy).coerceIn(0, maxRetraction)` 并
    `setRetractionOffset(R, false)`；未完全折叠时保持 `R=0`。
  - `onScrollStateChanged == IDLE`：吸附 `R` 到最近端态（`R > max/2 → max`，否则 `0`），
    `setRetractionOffset(target, true)`。
  - `immersiveEligible()`：`mode ∈ {NORMAL, MOVING} && !App.isSearching`。
- 验证：折叠后继续下滑 chrome 连续上移、上滑连续回落、松手吸附；仅 NORMAL/MOVING 生效。

### Phase 3 — 状态栏 scrim ＋ 图标

- `view_status_bar` 设为竖向渐变（顶部半透明 surface 派生色 → 底部全透明），随 surface 更新
  （在 `applyThingsActivitySurfaceBackground()` 里生成 gradient 而非 `applyBackground`）。
- 图标明暗沿用 `darkStatusBar`/`cancelDarkStatusBar`（按 surface 亮暗），无需动态采样。
- 验证：深色/高饱和卡片滑到状态栏之下，时钟/图标仍可读。

### Phase 4 — 模式 / 复位接线

- 进入搜索（`toggleSearching` 非 toNormal 分支）：`setRetractionOffset(0, false)`；retraction 门控靠
  `App.isSearching` 与既有 `setShouldListenToScroll(false)`。
- 退出搜索（toNormal）：`reset` 时 `R=0`。
- 进入 SELECTING（`ModeManager.toSelectingMode` 完成后，或 `contextualToolbarVisibilityCallback`）：
  `setRetractionOffset(0, false)`。
- 返回 NORMAL：复位为显示（`R=0`）。
- 列表重载 / 切换范围（已 `scrollToPosition(0)` 处）：随之 `R=0`、`ActivityHeader.reset`。
- `onConfigurationChanged`：重算 `SB`/`AB`、`R=0`。
- 验证：搜索、选择、开关文件夹、切状态、旋转下 chrome 状态正确，无残留半隐。

### Phase 5 — 边界打磨（见 followups，按需）

卡片外观面板打开时挂起 retraction；可滚动余量不足时不维持隐藏；程序化滚动不触发；横屏/平板几何。

## 主要改动文件

- `app/src/main/res/layout/activity_things.xml`（重排 ＋ margin/padding 调整 ＋ scrim 背景）
- `app/src/main/java/.../activities/ThingsActivity.kt`（`updateStatusBarLayoutOffsets`、
  `onScrolled`/`onScrollStateChanged`、surface/scrim、模式接线）
- `app/src/main/java/.../views/ActivityHeader.kt`（retraction 偏移 ＋ `applyTopChromeTransforms`
  ＋ `scrollY` 偏移 ＋ `isFullyCollapsed`）
- 可能：`res/drawable/` 新增 scrim gradient（若不在代码里生成）
