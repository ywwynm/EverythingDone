# 动图播放 — 设计与计划

详细决定见 [decisions.md](decisions.md),架构定级见 [ADR-0007](../../adr/0007-animated-image-playback-scoped-per-surface.md)。本文给出落地概览与验收标准。

## 各界面目标行为

| 界面 | 行为 |
|---|---|
| 全屏预览 `ImageViewerActivity` | Animated Image 走 Drawable 自动播放;非动图保留 `asBitmap`(HDR) |
| 详情默认模式 | 已自动播放,不变 |
| 详情定制模式 | 自定义 Transformation,保留裁切 + 播放 |
| 首页 / 文件夹子项缩略图 / Doing / Noticeable | 同上(缩略图、侧栏、媒体背景),保留裁切 + 播放 |
| widget 配置页预览 | 静态(忠实于不会动的真实 widget) |
| 裁切 dialog | 静态第一帧 |
| 桌面真实 widget(RemoteViews) | 无法播放,静态(本就如此) |
| 视频缩略图 | 不变,静态取帧 |

## 关键设计

- **候选判定**:扩展名 ∈ `{gif, webp}`(`AttachmentHelper.isAnimatedImageCandidate`)。
- **范围控制**:只在源是 Animated Image 时开分支,静态烘焙路径一行不动;影响面钉死在 GIF/动态 WebP。
- **裁切保真**:`MediaCropTransformation` 复用 `MediaCropBitmapRenderer.renderCrop(Bitmap, …)` 逐帧裁切 → "每帧已裁好、`CENTER_CROP` 显示"的 GifDrawable,与烘焙位图同一显示契约,不回到 `imageMatrix`。
- **widget 配置预览**:适配器开关 `setAnimatedPlaybackEnabled(false)`。
- **性能**:M1,仅靠 Glide 屏外暂停;必要时再加 fling 暂停(M2)。

## 验收标准(用户真机测试)

1. 全屏预览打开 GIF / 动态 WebP:**自动循环播放**;打开普通 JPEG/HEIC:HDR 行为不变。
2. 首页带 GIF 封面的卡片:缩略图 / 侧栏 / 媒体背景**按设定裁切并播放**;改裁切后仍正确。
3. 详情定制裁切的 GIF:**裁切生效且播放**;视频缩略图仍是静态取帧。
4. widget 配置页预览里的 GIF:**静态**(与桌面真实 widget 一致)。
5. 裁切 dialog 里的 GIF:**静态第一帧**,拖动裁切正常。
6. 滚动塞满 GIF 的列表:流畅度可接受(若掉帧,再评估 M2)。
