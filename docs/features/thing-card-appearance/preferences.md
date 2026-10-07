# Thing Card Appearance Preferences

## 2026-10-07：切换带图片记事后外观面板偶发闪烁

- 用户提供复现顺序：首页长按一条有图片附件的记事，打开并关闭外观面板，取消该记事选中，再选择另一条有图片的记事并打开外观面板；第二次打开有概率闪烁一帧。
- 要求先尝试复现，再修复，完成后发布阿里云。验证应覆盖真实选中切换和图片相关控件变化，不能仅用同一弹窗反复打开代替。
- 用户补充：飘散和流体两种动画都有此问题，复现与修复回归必须覆盖两种样式。
- 用户进一步补充：滑动记事列表后似乎更容易复现，测试序列需要包含列表滚动。
- 复现只选择有图片附件的普通记事，不选择文件夹；打开前确认工具栏为“调整记事外观”。用户认为录屏操作麻烦，本轮改用自动收集的逐帧诊断数据，不再依赖手动录屏。

## Home Appearance Panel

- When the Home appearance panel auto-scrolls the selected Thing or Folder Card
  into view, keep the same visible gap used between adjacent Home Cards below
  the contextual actionbar. This is currently derived from the item margins
  around cards, not a separate hard-coded dp value.

## Doing Cover

- The currently-doing cover shown on Thing Cards and Things AppWidgets should
  use the same vector drawable based on the newer `vec_ic_start_thing` rocket
  language.
- The doing-cover vector should preserve the old `ic_doing_thing` intrinsic
  canvas size but use a larger visible glyph than the first replacement pass,
  so it does not read smaller than the legacy PNG.
- The doing-cover icon and `正在做` label should use a compact compound-drawable
  gap. Use the same 4dp drawable padding on the Thing Card and AppWidget
  layouts so the new vector does not sit too far from the text.
- The doing-cover icon may include a simplified matching exhaust shape below
  the rocket; keep it vector-based so it scales consistently with card and
  AppWidget presentation.

## Media Count Overlay

- When image/video count text is rendered on top of a black overlay for top,
  bottom, left, or right Thing Card media placement, keep that text and icon in
  the fixed light overlay style. Live colour preview in the Thing Card
  Appearance panel should not recolour this overlay count from the draft Thing
  background. Inline hidden-media counts that sit directly on the Thing Card
  foreground may continue to follow the adaptive Thing foreground colour.
