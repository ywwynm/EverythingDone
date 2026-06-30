# 动态视频封面（Animated Video Cover）— 设计与计划

让视频封面也能在 Thing Card 上自动循环播放。详细决定见 [decisions.md](decisions.md)，架构定级见 [ADR-0012](../../adr/0012-thing-card-video-preview-derived-animated-image.md)（修订 [ADR-0007](../../adr/0007-animated-image-playback-scoped-per-surface.md)）。术语 **Thing Card Video Preview** / **Cover Autoplay** 见根目录 [CONTEXT.md](../../../CONTEXT.md)。

## 核心

视频封面当前只显示单帧（Thing Card Video Frame）。本特性从视频派生一个 GIF 动图预览（**Thing Card Video Preview**），喂进既有 Animated Playback 管线循环播放；由一个默认开的 **Cover Autoplay** 设置统一管控 GIF 与视频预览在卡片封面上的自动播放。

## 各界面目标行为

| 界面 | 行为 |
|---|---|
| 首页列表 / 文件夹子项缩略图 / Doing / Noticeable / widget 选择列表 | Cover Autoplay 开：视频封面播派生预览（缩略图/侧栏/媒体背景，保留裁切）；关：显示 Thing Card Video Frame 单帧 |
| 详情附件列表、全屏预览 | 不受开关影响；视频照旧点击播放真实视频，GIF 照旧播放 |
| 裁切编辑器 | 视频取单帧，不变 |
| 桌面 widget（RemoteViews） | 无法播放，静态，不变 |

## 关键设计

- **机制**：从视频派生 GIF（方案 A）→ 既有 `GifDrawable` + `MediaCropTransformation` + Glide 生命周期。`loadThingCardImage` 加一条"视频来源 + Cover Autoplay 开 + 派生 GIF 可用"的早分支，指向派生 GIF（不复用 `isAnimatedImageCandidate`，那判的是源扩展名）。
- **编码器**：Square `com.squareup:gifencoder:0.10.1`（纯 Java，每帧独立量化 + Floyd-Steinberg）。
- **预览内容**：以 Thing Card Video Frame 为循环起点向后 ~3s、25fps、长边 720px、循环。
- **生成**：Lazy 首次显示后台生成（限并发、优先可见），未就绪/失败回退单帧；持久缓存生成一次。
- **设置**：一个默认开的 Cover Autoplay 开关，统一管控全部应用内 Thing Card 封面面；落点为把 `mAnimatedPlaybackEnabled` 从硬编码改为读设置。
- **存储**：`cacheDir/video-cover-previews/`，LRU 1GB，key 不含裁切。
- **性能**：M1（屏外暂停）+ 主动 M2（快滑暂停）。

## 验收标准（用户真机测试）

1. 给一条记事设视频封面、开 Cover Autoplay：列表里该卡封面**自动循环播放**派生预览，**按设定裁切**；改裁切后仍正确且不重新生成。
2. 关闭 Cover Autoplay：视频封面回到**静态 Thing Card Video Frame**；GIF 封面也回到静态首帧。
3. 同一开关开启时 GIF 封面照常播放，行为不回归。
4. 首次显示有一次"静态→动画"切换；第二次进入直接是动画（命中缓存）。
5. 改 Thing Card Video Frame 后，预览从新帧起循环。
6. 详情/全屏的视频与 GIF 不受开关影响。
7. 塞满视频封面的列表滚动流畅度可接受（掉帧则按 M2 / 调 fps / 降分辨率评估）。
8. 视频/记事删除后派生缓存被清理；缓存总量不超过 1GB。
