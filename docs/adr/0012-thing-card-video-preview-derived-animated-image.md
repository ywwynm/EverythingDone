# Thing Card 视频封面采用派生动图预览（Thing Card Video Preview via a derived animated artifact）

在应用内 Thing Card 封面面，当 **Cover Autoplay** 开启时，视频封面以**从视频派生的 GIF 动图预览**（**Thing Card Video Preview**）循环播放，不再停在单帧。派生 GIF 复用既有 **Animated Playback** 管线：`GifDrawable` + `MediaCropTransformation`（逐帧套用裁切）+ Glide 生命周期。视频本身仍**不是** Animated Image，派生预览是显示用的缓存产物，不改动视频文件或其播放。

这修订 [ADR-0007](0007-animated-image-playback-scoped-per-surface.md) 中"任何视频缩略图一律停在单帧"的结论：应用内 Thing Card 封面面在开启自动播放时改播派生预览；裁切编辑器、RemoteViews、HDR 基帧、以及详情/全屏的视频仍按原样。

## 为什么

**为什么是"派生动图产物"而不是其他两条路：**

- **否决"卡片内播放真实视频"（ExoPlayer/TextureView）**：与 ADR-0007 奠定的"逐帧 `renderCrop`、与烘焙位图同显示契约"正面冲突——live surface 走不了 `renderCrop`，得另起一套 GL 裁切；滚动列表里多个播放器又重又易 jank。对一个列表封面缩略图属过度方案。
- **否决"运行时取帧 + 自定义动画 Drawable"**：屏外暂停、RecyclerView 回收、内存淘汰要自己重写一遍，而派生 GIF 这些全由 Glide 现成提供；本项目在 RecyclerView/布局生命周期上反复踩坑，避免手写动画 Drawable 更稳。
- **选派生 GIF**：把视频接进已反复打磨的动图管线，新增渲染层代码最少；"按需生成 + 带 key 缓存"本就是 Thing Card Video Frame 的既有模式，派生文件缓存是其自然延伸。

**为什么用 Square `gifencoder`（纯 Java）而非更高画质的原生方案：**

- **否决 gifski**（libimagequant + 时域抖动，画质天花板）：需把 `libgifski` 交叉编译进各 ABI 的 `.so` + JNI，APK 每 ABI 膨胀数 MB、自维护原生层；在卡片缩略图尺度边际收益很小。
- **否决 ffmpeg `palettegen/paletteuse`**：ffmpeg-kit 已于 2025-01 退役、4 月撤包，须自建 NDK 二进制，且有 MPEG LA/Via-LA 之后的编解码专利风险。
- 缩略图尺度下，256 色 + 每帧独立量化（逐帧调色板）+ Floyd-Steinberg 抖动 + 先按目标尺寸高质量降采样，肉眼已接近源画质；GIF 画质差主要是全屏/大渐变才明显。

**为什么单独一个默认开的开关：** 现有 GIF 封面本是无条件播放；引入用户开关后必须默认开以免 GIF 行为回归。GIF 与视频预览共用这一个 **Cover Autoplay**，贴合"一个设置项"的诉求，代价是二者无法分开控制。

## 影响

- **范围**：仅统一管控全部应用内 Thing Card 封面面（首页/文件夹缩略图/Doing/Noticeable/widget 选择列表）；详情附件列表与全屏预览不受开关影响、照旧无条件播放；桌面 widget（RemoteViews）无法播放、永远静态。
- **生成**：Lazy——首次显示时后台生成（限并发、优先可见），生成前/失败回退到 Thing Card Video Frame；持久缓存做到"每个封面只生成一次"。Eager 预热留作 follow-up。
- **存储**：`cacheDir/video-cover-previews/`，LRU 上限 1GB；缓存 key 含视频身份/mtime/起始帧/时长/帧率/分辨率，**不含裁切**（裁切显示期逐帧套用，改裁切不重生成）。
- **参数**：内部常量、不暴露用户——时长 ~3s、25fps（GIF 厘秒延迟下接近 30 的干净值，可后调到 ~33fps）、编码长边 720px。
- **性能**：GIF 无硬件解码，逐帧 CPU 软解；沿用 M1（屏外暂停），主动加 M2（快滑暂停）。最终 fps 真机实测定。
- **HDR**：派生 GIF 为 SDR；但卡片封面本就走烘焙 SDR 路径（ADR-0006/0007），无回归。
- 详细决策见 [animated-video-cover/decisions.md](../features/animated-video-cover/decisions.md)，术语见根目录 [CONTEXT.md](../../CONTEXT.md) 的 **Thing Card Video Preview** / **Cover Autoplay**。
