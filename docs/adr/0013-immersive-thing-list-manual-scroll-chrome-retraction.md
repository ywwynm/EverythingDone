# 沉浸式记事列表用手写滚动驱动实现 Home Chrome Retraction（Immersive Thing List via manual scroll-driven chrome retraction）

ThingsActivity 的 **Immersive Thing List**（NORMAL / MOVING 模式下，Activity Header
折叠进 actionbar 后继续下滑收起顶部 App Chrome、让 Thing Card 从状态栏铺到导航栏）采用
**手写滚动驱动**实现，而**不迁移到 CoordinatorLayout / AppBarLayout /
CollapsingToolbarLayout**。

具体形态：Thing 列表 `rv_things` 铺满全高、`clipToPadding=false` 并用现有 decor inset
chain 动态维护 `paddingTop = 状态栏 + actionBar`，使内容始终绘制在顶部 App Chrome 之下；
状态栏底色条换成保护性渐变罩，actionbar、其阴影、折叠标题浮层 `rl_header` 作为顶部浮层。
新增一个"沉浸控制器"：从现有 `RecyclerView.onScrolled`（仅认手指驱动的滚动）拿方向与位移，
在 **Activity Header 完全折叠之后**连续驱动顶部浮层 `translationY`（连续跟手），
`onScrollStateChanged` 松手时按位置 / 速度吸附到全显示或全隐藏两个末态。Activity Header 的
折叠仍复用现有位置驱动逻辑与 header spacer；"折叠进度"与"chrome 隐藏位移量"是两个独立量。

## 为什么

**为什么不迁 CoordinatorLayout（看似标准做法）：** 官方折叠工具栏是这类需求的常规解，
未来若有类似需求也更好扩展；但本项目的顶部区域高度定制——`ActivityHeader` 承载渐变标题、
私密文件夹内联锁 span、文件夹路径点击、动态 header spacer 高度、以及新建项 reveal、卡片
外观面板滚动联动等一大批既有逻辑，全都围绕"手写 RecyclerView 滚动监听 + 浮层变换"搭起来。
套进 AppBarLayout 需要连同 DrawerLayout 集成、contextual toolbar 顶部浮层一起重写，风险与
工作量远超本特性收益，且极易回归既有细节。

**为什么手写可行且收敛：** 现状本就用 `onScrolled` 每帧驱动 Activity Header 折叠、用
`onScrollStateChanged` 收尾；把"折叠之后再连续驱动顶部浮层位移 + 松手吸附"接在同一套机制上
是同构扩展，新增代码集中在一个沉浸控制器里。底部方向早已 edge-to-edge（导航栏透明 + 列表
底部滚动 padding），无需改动。此选择也延续 `system-bar-insets` 既有决策——"保守优先、
强化现有 DisplayUtil inset 机制，不引入 Everything-Android 的 base-activity 架构"。

## 影响

- **范围**：仅 NORMAL 与 MOVING 模式、且 `!App.isSearching` 时进入 Immersive Thing List；
  SELECTING 与搜索强制显示常规 chrome；打开文件夹后的列表同样支持。
- **布局改动**：`rv_things` 由"actionbar 之下起"改为"铺满全高 + `paddingTop` 预留"，
  因此 `ActivityHeader.updateAll()` 里 `scrollY = -firstChild.top` 需按新的 `paddingTop`
  做偏移，避免折叠起点被推迟；header spacer 与标题浮层定位需相应对齐。
- **状态栏**：进入沉浸态不做成全透明，保留保护性渐变罩，图标沿用按 surface 亮暗设定的明暗
  （见 [decisions.md](../features/immersive-thing-list/decisions.md)）。
- **FAB**：不纳入 retraction，沿用 `FloatingActionButton.attachToRecyclerView()` 的即时隐现。
- **回退成本**：若日后仍要迁 CoordinatorLayout，本方案的沉浸控制器与 padding 预留可整体替换，
  但属大改；这也是本决策记为 ADR 的原因。
- 详细决策见 [immersive-thing-list/decisions.md](../features/immersive-thing-list/decisions.md)，
  术语见根目录 [CONTEXT.md](../../CONTEXT.md) 的 **Immersive Thing List** /
  **Home Chrome Retraction** / **Activity Header**。
