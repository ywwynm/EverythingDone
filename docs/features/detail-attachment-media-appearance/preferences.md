# Detail Attachment Media Appearance Preferences

## 2026-10-15 — 同一附件上的标识与按钮统一颜色

- 用户反馈每个标识与图标按钮分别选择颜色显得杂乱，要求详情页图片/视频附件上的 GIF 等标识和按钮统一颜色，取代原先允许同一附件黑白混用的要求。
- 实施沿用刚确认的顶部按钮汇总方式：在同一附件内综合各标识和按钮下方内容，每个元素等权，共同选择一种黑白颜色；不同附件各自判断。
- 保留原有透明度、HDR/GIF 镂空、静态封面配色和裁剪/比例/选帧刷新规则。
- 本会话实机验证沿用 `3B1629006YC00000`、`R5CW20BLNKL` 的授权，不使用下方历史会话列出的其他设备。

## 2026-10-11 — 附件叠加标记与按钮的自适应明暗

- 用户接受局部合成对比度算法并要求开始实施，特别强调颜色设置速度；不能因配色导致详情打开、媒体加载或外观更新明显变慢。
- 本轮明确授权在 `9018f404`、`R5CW20BLNKL`、`192.168.100.67:42641` 三台设备测试，覆盖多个记事且包括已有记事。已有记事用于查看验证，修改与破坏性操作使用专用临时记事。
- 用户反馈详情页图片、视频上的半透明偏白标记和外观、删除按钮，在浅色媒体区域难以辨认，希望评估自动切换为半透明偏黑色。
- 判断需要跟随实际展示内容更新；用户明确提醒，修改附件显示比例、裁剪内容后可能需要重新计算。
- 当时用户接受每个标记、按钮按自身覆盖区域独立判断；这一点已被 2026-10-15 的统一颜色要求取代。保留现有透明度以及 HDR／GIF 镂空样式的要求仍有效。
- 用户明确不需要跟随播放画面变色：仅根据静态封面及其当前裁剪结果配色，播放期间保持不变；比例、裁剪或封面选帧变化后重新判断。
- 用户明确不增加描边、阴影或衬底等装饰，只在半透明黑白之间选择；复杂纹理背景也不改变这一规则。

## 2026-10-11 — 外观弹窗中间内容滚动

- 用户要求一并修复横屏内容超出窗口的问题，参考设置里的“更改应用语言”弹窗划分固定区与滚动区。
- 标题和底部取消/确认按钮固定；宽度、媒体预览、视频选帧和显示比例属于可滚动内容。
- 上下边界使用分割线提示该方向还有内容；顶部隐藏上分割线、底部隐藏下分割线，内容无需滚动时两条都隐藏，内容高度变化后也必须正确刷新。

## 2026-10-10 — 先展示弹窗，再异步填入媒体

- 用户确认触摸 ripple 延迟已改善，要求继续缩短弹窗出现等待。
- 优先读取尺寸等轻量信息，按正确宽高显示外观编辑弹窗并开始既有粒子动画，同时异步加载图片/视频；最终裁剪、宽度/比例、选帧及确认/取消行为保持一致。
- 用户进一步要求：弹窗图片加载过程使用首页记事卡片相同的进度样式，颜色跟随记事背景并完整支持渐变，不能只取渐变的单一代表色。

## 2026-06-08 - Desired Detail media display freedom

The user wants image and video attachments displayed in `DetailActivity` to gain
visual adjustment controls similar to the custom Thing Card Appearance cover
media controls.

The first displayed image/video attachment should be able to control whether it
is full-span, its media target aspect ratio, crop center, and crop user zoom.
Remaining image/video attachments should keep a 1:1 media target aspect ratio
while still allowing crop center and crop user zoom adjustments.

These settings should affect Detail attachment presentation only unless a later
decision explicitly shares them with Thing Card Appearance or another surface.

## 2026-06-08 - Width mode UI language

The Detail attachment appearance editor should not expose the technical
`full-span` term directly in visible UI.

Use the same pill-button selection language as the Thing Card Appearance editor.
The prompt text should be `图片显示宽度`, with options `正常` and `宽`. The ratio
control label above the slider should be `图片显示比例`.

The editor title and controls should visually adapt to the Thing background and
dark mode, matching the rest of the Detail/App Chrome dialog surface instead of
assuming a light default background.

The `图片显示宽度` label should reserve enough horizontal space to stay on one
line on the user's device. Prefer increasing the label column width over
allowing the label to wrap.

## 2026-06-14 - Media-specific editor wording and tighter preview spacing

The Detail attachment appearance editor should name the media type in its title:
image attachments use `图片外观`, and video attachments use `视频外观`.

The full-span ratio label should also follow the media type. Image attachments
use `图片显示比例`; video attachments use `视频显示比例`. The width-mode label should
follow the media type too: image attachments use `图片显示宽度`, and video
attachments use `视频显示宽度`.

The editor title should visually follow the larger title margins used by the
app's other dialogs. The media preview should sit closer to surrounding
controls than before, especially above the preview and between the preview and
video/ratio controls.

## 2026-06-14 - Detail and card crop dialog spacing alignment

The Detail attachment appearance dialog and the home Thing Card Appearance
precise crop dialog are separate generated dialog surfaces, but they share the
same crop editor view classes. Their dialog chrome spacing should stay aligned
when both surfaces show the same image/video preview and ratio controls.

Use App Chrome hint-colour labels for the media ratio prompt in both dialogs so
`封面图片/视频比例` and `图片/视频显示比例` match the visual weight of the width
prompt row across light and dark mode.
