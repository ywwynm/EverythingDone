# 图片/视频数量 icon 宽度和文本 margin 调整

本次 debug update 回应用户对图片/视频数量 icon 对齐方式的新判断：

- 图片/视频数量 icon 由于自身图形差异，视觉上仍然比音频 icon 小。
- 希望把图片/视频数量 icon 的控件宽度在 X 方向增加 `2dp`，让图形看起来更大一些。
- 同时把右侧文本 margin 减小 `2dp`，保持后面的数量文本仍然和音频数量文本左对齐。

实现修改：

- 音频数量 icon 保持不变：
  - 普通：`14x14dp`，文本 start margin `8dp`
  - 大号：`16x16dp`，文本 start margin `12dp`
- 图片/视频数量 icon 单独改为：
  - 普通：`16x14dp`，文本 start margin `6dp`
  - 大号：`18x16dp`，文本 start margin `10dp`
- 因此文本起点仍然保持一致：
  - 普通：音频 `14 + 8 = 22dp`，图片/视频 `16 + 6 = 22dp`
  - 大号：音频 `16 + 12 = 28dp`，图片/视频 `18 + 10 = 28dp`
- hidden media inline 图片/视频数量行和 media-background overlay 图片/视频数量行都使用这套规则。
- 图片/视频 icon 继续使用 `fitCenter` 和 `1dp` left/top padding，避免裁切并保持轻微右下偏移。
- 更新 `memory/decisions.md` 记录新的尺寸和 margin 对齐规则。

验证和发布状态：

- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- 已使用
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  编译、打包并发布到 debug update 通道。
- 已发布 debug update `202606050023` 到
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`。
