# Thing Card Appearance Decisions

## 2026-06-26 - 首页外观面板取消与自动滚动

首页选择模式中打开“调整记事外观”或“调整文件夹外观”面板后，系统返回键、contextual toolbar 的关闭按钮，以及面板内“取消”都只取消本次外观调整并关闭面板，不退出选择模式。确定按钮仍表示提交本次调整，按既有流程结束选择态。

面板打开、在颜色页/外观页之间切换，或在颜色页内部切换纯色/渐变页时，列表自动滚动必须基于当前选中卡片的实际 holder 几何计算，而不是只调用 `smoothScrollToPosition(position)`。如果卡片高度超过面板上方的可见区域，则滚动到卡片顶部位于 contextual actionbar 下方并保留与相邻卡片之间一致的可视间距；如果卡片能完整放入可见区域，则只在顶部或底部被遮挡时做最小滚动，已经完整可见时不滚动。

外观面板引发的程序化滚动、面板高度变化和 RecyclerView bottom padding 变化，都必须同步刷新 Activity Header。Activity Header 的折叠位置依赖首页 RecyclerView 当前滚动位置，因此不能只在手指滚动或新建记事显现滚动时刷新；外观面板的 `smoothScrollBy()` / `smoothScrollToPosition()` 路径也要在滚动过程中和滚动结束后的稳定布局帧重新计算 Header。

外观面板高度变化引起的 RecyclerView bottom padding 变化也应当有过渡动画，不能让 `setPadding()` 的瞬时 relayout 直接表现为列表跳动。切换到颜色页、在颜色页内部切换纯色/渐变页、以及切回外观页时，应先让 padding 过渡到 `panelHeight + 首页卡片间距`，再在布局稳定后合并执行一次选中卡片可见性校正，避免短时间内连续两段滚动。

选中卡片可见性校正不能只挂在 `OnPreDrawListener` 上等待下一次绘制。如果页面切换后 RecyclerView padding 目标值没有变化，就可能没有新的绘制帧，导致校正一直等到用户手动滚动才触发。调度时应主动 `postOnAnimation` 并请求下一帧，让“切换 panel 后选中卡片已离屏”这种场景也能立即启动自动滚动。

holder 暂时不在屏幕上时，粗定位和精确定位必须使用同一个坐标基准。`LinearSmoothScroller` 默认按 decorated top 计算，包含 item margin / decoration；而精确校正按 `itemView.top` 计算。外观面板选中卡片滚动应覆盖 `calculateDyToMakeVisible()`，直接按 `itemView.top == recyclerView.paddingTop + 首页卡片间距` 计算，否则落点会比 16dp 稍大，后续页面切换又补一段小滚动。

## 2026-06-20 - Doing cover uses the new start-Thing rocket language

The Thing Card and Things AppWidget currently-doing covers should not keep
using the legacy `ic_doing_thing` PNG. They now use `vec_ic_doing_thing`, a
vector that reuses the `vec_ic_start_thing` rocket shape for the upper glyph
and adds a matching simplified exhaust shape below it.

The vector keeps the old PNG's intrinsic size of 44dp by 48dp so the
right-swipe / doing overlay text layout does not shrink or shift. The visible
glyph is larger than the first vector replacement pass because the legacy PNG
filled its full intrinsic canvas.

The compound-drawable gap between the doing-cover vector and label should be
4dp in the home-list card and Things AppWidget layouts. The initial 12dp gap
made the rocket/exhaust icon feel detached from the `正在做` label, and the
first 8dp follow-up still left slightly too much separation.

## 2026-06-19 - Appearance panel colour editing is draft-only until confirm

The Thing Card Appearance panel and Thing Folder Card Appearance panel should
offer a colour button in the title row. The button icon uses the current Thing
or Folder background as an opaque tint or drawable treatment so it does not
look greyed out.

Tapping the button opens the same ColorPicker content used by Detail's
change-colour popup. On each show, the popup compares available space above
and below the colour button. If the upper space is larger, the popup's
bottom-right corner is pinned to the button's bottom-right corner and the
surface grows from lower-right toward upper-left. Otherwise, the popup's
top-right corner is pinned to the button's top-right corner and the surface
grows from upper-right toward lower-left. The popup always includes world
colour sampling. When the current background is a gradient, it also includes
the gradient-direction control.

Colour changes made from the panel are preview state only. They must update the
selected Thing or Folder Card, the panel controls, and all related foreground
or indicator colours live, but persistent storage is updated only when the user
confirms the panel. Cancelling the panel restores the previous saved background.

Changing a Folder Card from normal size to large thumbnail size is also live
preview state. The selected Folder Card should immediately switch to the large
thumbnail projection and show descendant preview content before the user
confirms.

## 2026-06-25 - 视频裁切预览用 clipToOutline 强制裁剪，不关闭 clipChildren

`ThingCardVideoCropEditorView` 用真实 `TextureView`（硬件合成层）渲染视频，并被
`applyVideoTransform()` 故意撑到完整缩放视频尺寸以实现 cover-crop，因此必然上下溢出
预览框。隐藏溢出不能依赖弹窗容器链的 `clipChildren`：紧凑弹窗为让确定按钮的触摸
ripple 完整溢出显示，特意在 dialog content 容器与 root LinearLayout 上设了
`clipChildren=false`，这是要保留的产品行为，不能为裁视频而关掉。

因此裁剪 TextureView 由该 view 自身承担：`clipToOutline = true` + outline 设为预览框
`previewRect` 圆角矩形（在 `onLayout` 后 `invalidateOutline()`）。这是硬件层裁剪，对
layer-backed 的 TextureView 可靠，且作用范围限定在视频裁切控件内部，不影响其外的按钮
ripple。图片版 `ThingCardCropEditorView` 继续用 `canvas.clipPath` 软裁，无需改动。
