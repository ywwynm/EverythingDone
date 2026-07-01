# Immersive Thing List（沉浸式记事列表）

状态：设计已定稿（经 grill-with-docs 逐项确认），尚未实现。

ThingsActivity 记事列表的沉浸式滚动：Activity Header 完全折叠进 actionbar 后继续下滑，
顶部 App Chrome（状态栏保护罩＋含折叠标题的 actionbar）随滚动整体收起，让 Thing Card 从
状态栏一直铺到导航栏；上滑即让顶部 chrome 回落。仅 NORMAL 与 MOVING 模式生效，SELECTING
与搜索（`App.isSearching`）不生效。打开文件夹后的列表同样支持。

## 文档

- `decisions.md` — 逐项设计决策与被否决的备选。
- `followups.md` — 实现期需处理的边界与技术注意点。

## 关联记录

- ADR：[ADR-0013](../../adr/0013-immersive-thing-list-manual-scroll-chrome-retraction.md)
  —— 手写滚动驱动、不迁 CoordinatorLayout 的路线决策。
- 术语：根目录 [CONTEXT.md](../../../CONTEXT.md) 的 **Immersive Thing List** /
  **Home Chrome Retraction** / **Activity Header**，及一条关于 "edge-to-edge" 的
  Flagged Ambiguity。
- `docs/features/system-bar-insets/` —— 运行时系统栏 inset 的既有机制（decor inset chain）。
- `docs/features/android-16-migration/` —— Phase 10 Edge-to-Edge 的既有进度与遗留项。
- `docs/features/home-contextual-toolbar/` —— 选择模式下顶部 contextual toolbar 浮层。
