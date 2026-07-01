# Immersive Thing List 实现期注意点 / 待确认边界

以下不是新的产品决策，而是实现时必须处理的边界与技术点，附建议解法。标 (待确认) 的
需要在实现或联调时和用户再确认一次。

## 布局 / 几何

- **`paddingTop` 偏移**：`rv_things` 改铺满全高 + `paddingTop=状态栏+actionBar` 后，
  `ActivityHeader.updateAll()` 里 `scrollY = -firstChild.top` 会从负值起（首项在 padding
  之下），现有 `coerceIn(0, …)` 会把折叠起点推迟。需改为按新的 `paddingTop` 做偏移，
  保证折叠起点、header spacer、标题浮层 `rl_header` 定位仍与今天视觉一致。
- **顶部浮层组成**：状态栏保护罩、actionbar、`actionbar_shadow`、折叠标题浮层 `rl_header`
  需作为一个整体 translationY，`view_status_bar` 由不透明底条改为渐变罩。
- **滚动区间不足**：内容太短、折叠后没有足够可滚动余量时，不允许维持"隐藏"末态，
  松手吸附回"显示"。避免出现"chrome 悬在半途/露出空白"的状态。

## 与既有滚动联动的关系

- **仅认手指滚动**：retraction 只响应 `mScrollCausedByFinger` 的滚动；新建项 reveal
  （`mNewItemRevealScrolling`）、卡片外观可见性滚动（`mThingCardAppearanceVisibilityScrolling`）
  等程序化滚动不得触发隐藏。
- **卡片外观编辑面板**(待确认)：`panel_thing_card_appearance` 打开时是聚焦编辑浮层、会把
  目标卡片滚入视野并占据底部。建议此期间**挂起** retraction（强制显示常规 chrome），
  面板关闭后恢复。实现时确认交互是否符合预期。

## 系统栏 / chrome

- **保护罩参数**：渐变罩覆盖状态栏高度、由顶部半透明 surface 派生色向下渐透到全透明；
  具体色值/透明度/是否随 folder 代表色变化，实现时定。图标明暗沿用现有按 surface 亮暗
  的 `darkStatusBar` / `cancelDarkStatusBar`。
- **抽屉访问**：actionbar 隐藏时汉堡键不可见，但 DrawerLayout 边缘滑动仍可打开抽屉；
  抽屉关闭后保留当时的 chrome 状态即可，无需强制复位。

## 覆盖面 / 回归

- **横屏 / 平板**：复用 `ActivityHeader.computeFactors()` 现有的横屏/平板系数；需验证
  折叠 + retraction 的几何在这两种形态下正确。
- **Doing 蒙层等 NORMAL 态叠加**：验证"正在做"蒙层等 NORMAL 模式下的叠加不与 retraction
  冲突。
- **状态切换/开关文件夹/搜索返回**：确认列表重载会滚到顶并复位为"展开＋显示"（多数应已是
  现有行为，联调核对）。
