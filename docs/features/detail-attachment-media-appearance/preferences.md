# Detail Attachment Media Appearance Preferences

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
