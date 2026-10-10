# Detail Attachment Media Appearance Preferences

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
