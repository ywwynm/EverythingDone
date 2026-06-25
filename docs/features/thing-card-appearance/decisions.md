# Thing Card Appearance Decisions

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
