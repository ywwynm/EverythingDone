# 动态附件分享可行性评估（2026-07-26）

评估「让长截图分享出去的视频 / Motion Photo / GIF 附件也能动」的技术路径。

## 一句话结论

技术上完全做得到，项目已经具备全部基础件；真正的约束不在「能不能合成动画」，
而在**产物格式的体积与接收端生态**。整图 GIF 因体积不可用，整图 MP4 形态不对，
可行的是两条：**多文件分享**（低成本，先做）与**滚动视频**（进阶）。

## 现状

分享产物是一张静态 JPEG：`ScreenshotHelper.getScreenShotForScrollViews()` 把
`NestedScrollView` 一次性 `draw(Canvas)` 成单张 Bitmap 存盘，再以 `image/jpeg`
走 `ACTION_SEND`。产物本身**没有时间维度**，附件不可能动。

截图期间还刻意冻结动画：`ImageAttachmentAdapter` 的 `mTakingScreenshot` 会
关掉 Detail Autoplay 分支、跳过派生 GIF（`ImageAttachmentAdapter.kt:270`、
`:275`），保证抓到的是确定的静态代表帧。这个设计在「产物是单帧」的前提下是对的。

## 已有基础件

要合成动画，需要「按时间取帧」和「编码」两件事，两件都已经在仓库里：

| 能力 | 现成实现 | 备注 |
|------|----------|------|
| 视频逐帧 | `VideoCoverPreviewManager.decodeFrame()` | `MediaMetadataRetriever.getFrameAtTime` |
| 视频 → 派生 GIF | `VideoCoverPreviewManager` | 3s / 25fps / 长边 720，已带 LRU 磁盘缓存 |
| Motion Photo → 派生 GIF | `MotionPhotoCoverHelper` | 复用同一管线 |
| GIF 编码 | `io.github.waynejo:androidndkgif:1.0.1` | NDK 原生编码，已在用 |
| GIF 逐帧解码 | Glide 4.16 的 `StandardGifDecoder` | 公开 API，可 `advance()` / `getNextFrame()` / `getDelay(i)` |
| 逐帧套裁切 | `MediaCropTransformation` / `MediaCropBitmapRenderer` | 保证与详情页几何一致 |

唯一缺口是**动态 WebP 逐帧**：`AnimatedImageDrawable` 没有 seek API，Glide 在
API 28+ 走 `ImageDecoder` 只给 Drawable。要么按真实时间在离屏 Canvas 上采样一遍
（3s 就花 3s，可接受，一次性缓存），要么走已有的「归一化成派生 GIF」这条路。

## 合成方式（各方案共用）

**不需要逐帧重绘整个视图树**（那既慢又要回主线程）。正确做法是「底图 + 局部换帧」：

1. 按现状拿一张静态长图 Bitmap 作底图；
2. 遍历 `mRvImageAttachment` 的 ViewHolder，记录每个动态附件相对长图的矩形。
   `rv_image_attachment` 是 `wrap_content` 且 `isNestedScrollingEnabled = false`
   （`DetailActivity.kt:830`），长截图时所有 item 都已 layout，ViewHolder 齐全，
   `getLeft/getTop` 直接可用；
3. 逐帧只把该附件第 i 帧（经同样裁切）blit 到底图对应矩形；
4. 送编码器。

每帧只有 N 次小 Bitmap 拷贝，可全程在后台线程完成，不碰主线程，也不受现有
`Thread.sleep(1600)` 那套约束。

## 实测数据

用 ffmpeg + 合成长截图（渐变背景 + 文字 + 2 列附件网格，动态格填带噪声的
视频式内容）实测，3 秒 / 25fps：

| 产物 | 短记事 1080×2400 | 长记事 1080×4400 |
|------|------------------|------------------|
| **静态 JPEG（现状）** | 0.35 MB | 0.57 MB |
| 整图 GIF（1080 宽） | **5.13 MB** | **10.65 MB** |
| 整图 GIF（540 宽） | 1.41 MB | 2.68 MB |
| 整图 MP4 / H.264 crf23 | 0.40 MB | 0.75 MB |
| 整图动态 WebP q75 | 0.43 MB | 0.81 MB |

**GIF 成本拆解**（1080×4400）：

- 纯静态长图的 GIF 首帧：**1.12 MB**
- 75 帧全静态、零动态附件：仍是 **1.12 MB** —— 帧间差分本身是有效的
- 加上 2 个 475×475 动态附件后：**10.65 MB**

即 74 个差分帧多花了 9.53 MB，约 **64 KB / 附件 / 帧**。同样内容 H.264
整片才 0.75 MB —— **GIF 单位成本约是 H.264 的 14 倍**。原因是 256 色量化 +
抖动把视频内容变成了 LZW 压不动的高熵噪声。

**单附件独立成动图**（现有派生 GIF 规格，3s / 25fps）：

| 尺寸 | 体积 |
|------|------|
| 720×720 | 2.36 MB |
| 480×480 | 1.15 MB |

**长图滚动视频**（1080×4400 长图，视口 1080×2400，12 秒）：

| 参数 | 体积 |
|------|------|
| 1080p / 25fps / crf23 | 16.80 MB |
| 1080p / 25fps / crf28 | 5.01 MB |
| **720 宽 / 15fps / crf28** | **0.61 MB** |

平滑滚动等于每帧全画面位移，crf23 下残差极大；降到 720 宽 + 15fps 后完全可用，
且 720 宽下正文中文仍可读（1080 下 14sp ≈ 42px，缩放后 ≈ 28px）。

复现脚本见 `docs/features/share-screenshot/tools/`。

## 方案矩阵

### 方案 1 — 整张长截图输出 GIF ❌

技术无障碍（编码器和帧源都现成），但**体积致命**：长记事 10.65 MB。即便主流 IM
不直接拒收，发送与接收方加载都会很难受，且多数平台对超大 GIF 会转静态。降到
540 宽能压到 2.68 MB，但文字糊掉一半，长截图的意义就没了。此外 256 色量化会毁掉
应用的渐变背景（色带 + 抖动噪点）。

### 方案 2 — 整张长截图输出 MP4 ~~❌~~ → ✅（排除理由已作废）

体积很好（0.75 MB）。

> **原排除理由作废**（2026-07-26 用户裁定）。原文写的是「1:4 的超长竖视频在聊天里
> 缩略图被裁成方形、播放时被 letterbox 成中间一条，文字完全不可读」——全部是**接收方
> 观感**，不构成约束（详见 B5 开头的说明）。本方案即后续的「长视频」入口，唯一的限制
> 是 B1 的编码器高度能力。

### 方案 3 — 整张长截图输出动态 WebP ❌

体积（0.81 MB）和画质都最优，但两处堵死：Android 没有系统级动图 WebP 编码 API
（要自带 libwebp 的 `WebPAnimEncoder` + 写 JNI）；且主流 IM 作为图片消息基本不支持
动图 WebP，会转静态或直接失败。

### 方案 4 — 长截图 + 各动态附件独立文件，一次多文件分享 ✅ 先做

把现有 `ACTION_SEND` 换成 `ACTION_SEND_MULTIPLE`：第一项仍是长截图 JPEG，后面
依次挂上各动态附件。视频 / Motion Photo 可挂**已缓存的派生 GIF**（生成成本为零，
`VideoCoverPreviewManager.getReadyPreview()` 直接取）或直接挂原视频（原画质、带声音、
可暂停可保存）；GIF / 动态 WebP 直接挂原文件。

- 成本：几十行，集中在 `ScreenshotHelper.ShareCallback` 与调用方；
- 兼容性风险：零，走的是最常规的多文件分享；
- 代价：不是「一张图里动」，接收方拿到的是一组内容；少数接收方只取第一个 URI。

### 方案 5 — 长截图滚动视频 ✅ 进阶

把长图做成一段竖屏 MP4：视口从上往下滚，滚到附件时该附件正在播放。
720 宽 / 15fps / crf28 下 **0.61 MB**。

- 工作量中等：滚动时间曲线、附件播放与滚动位置的同步、`MediaCodec` + `MediaMuxer`
  编码（`MediaMetadataRetriever` 那套已有经验可复用）；
- 真实价值：内容高度超过编码器上限时它是唯一选择，以及提供另一种呈现节奏。
  **原文「文字可读」「体验最好的形态」这类说法基于接收方观感，已作废**，见 B5。

后续细化的视口规则与「播完再滚」时间轴见 [plan.md](plan.md)。

## 建议路线

> **本节为第一轮结论，已被后续几轮推翻，最终方案与分期以 [plan.md](plan.md) 为准。**
> 主要变动：方案 2 的排除理由作废（它成了「长视频」主入口）、方案 4 不再挂派生 GIF
> （用户禁用 GIF）、实施顺序改为长视频优先。以下保留原文供追溯。

1. **先做方案 4**。投入最小、无兼容风险，直接解决「附件不会动」这个诉求，
   而且附件是原画质、有声音的。
2. **再评估方案 5**。做成分享菜单里与「长截图」并列的第二个入口
   （如「分享为视频」），而不是替换现有长截图。
3. 方案 1 / 3 不再考虑；方案 2 只作为方案 5 的实现细节存在。

## 追加评估（2026-07-26）：挂原视频、整图长视频

### A. 多文件分享挂原视频

**不需要任何编码。** 直接把附件原文件的 FileProvider URI 放进 `ACTION_SEND_MULTIPLE`
即可，零转码、零耗时，比挂派生 GIF 还快（后者至少要等缓存命中）。MediaCodec 在这里
没有用武之地——它是「生成新视频」才需要的工具，见下一节。

FileProvider 配置也已经够用，不必改：`file_provider_paths.xml` 里
`external-files-path path="/"` 覆盖应用私有附件目录，`external-path path="."`
覆盖引用公共路径（如 DCIM/Camera）的附件。

三点代价需要权衡：

1. **语义变化**。长截图只呈现用户选定的 Thing Card Video Frame，挂原视频等于把整段
   发出去，可能含用户并不想分享的片段。派生 GIF 恰好只有从该帧起的 3 秒，语义与
   长截图一致。
2. **体积与时长**。原视频可能几十 MB、数分钟，IM 会转码甚至拒收；派生 GIF 稳定在
   2–3 MB。
3. **数量**。一条记事挂多个视频时，一次分享就是多个大文件。

建议按阈值自动决定：原视频体积与时长都在限内就挂原片（原画质、有声、可暂停可保存），
否则退回派生 GIF。阈值待实测 IM 的限制后再定。

### B. 整图长视频（布局与高度和长截图一致）

技术上可行，逐条说明约束与解法。

**B1 编码尺寸。** H.264 标准层面没问题：实测 1080×4400 编成 Level 5.0，1080×6000 与
1080×8000 都是 Level 5.1。Level 5.1 的 MaxFS 是 36864 宏块，1080 宽下高度上限约
8600 px，覆盖任何现实长截图。

真正的限制在**硬件编码器**：不少设备的 AVC 硬编上限是 4096，Android CDD 也只要求
到 1080p。必须运行时查 `MediaCodecInfo.VideoCapabilities.isSizeSupported(w, h)`，
超限就按 `s = min(1, maxW/W, maxH/H)` 等比缩放并做 16 对齐。这层降级几乎无损
（4400 → 4096 只缩 7%）。

**B2 GL texture 上限。** 走 input surface 时底图要上传成 texture，
`GL_MAX_TEXTURE_SIZE` 在部分设备同样是 4096。超了就把底图切成上下两张 texture 拼，
或者直接套用 B1 已经算好的缩放系数。

**B3 编码方式。** 必须 `createInputSurface()` + EGL/GL：MediaCodec 的 input surface
只接受 GL 渲染，`lockCanvas` 那条路不通。ByteBuffer 模式要自己做 ARGB → YUV420，
1080×4400 每帧 4.75M 像素，纯 Kotlin 转换 200 ms+/帧，不可行。

**B4 瓶颈在取帧，不在编码。** 硬编一帧 1080×4400 约 25–50 ms，75 帧合计 2–4 秒；而
`MediaMetadataRetriever.getFrameAtTime` 按现有日志是 ~90 ms/帧，2 个附件 × 75 帧
就是 13.5 秒。

解法：**帧源直接用已缓存的派生 GIF**。规格正好对上（3 秒 / 25fps / 长边 720），用
Glide 的 `StandardGifDecoder` 顺序解码每帧只要 5–10 ms，150 帧 1–1.5 秒。整体
3–6 秒，与现在生成静态长截图（固定 `Thread.sleep(1600)` 再画一次）同一量级。

这条顺带补掉了动态 WebP 没有公开逐帧 API 的缺口——所有动态源统一归一化成派生 GIF，
编码侧只认一种输入。

**B5 唯一的高度限制是 B1 的编码器能力，此外不设限。**

> **本节曾以接收方观感设限，已作废**（2026-07-26 用户裁定）。原文由「视频在接收方屏幕上
> 按 contain 缩放 → 内容越高文字显示得越小 → 正文跌破 12 dp 即不可读」推出一条 4000 px
> 的临界高度，并据此建议禁用 / 降级长视频入口。这条推理链整个不作数：**产物在接收方
> 屏幕上显示多大、看不看得清是接收方的事**，他可以双指放大、可以保存后换播放器。
> 我们只对生成侧的真实成本（编不出来、体积、耗时）与功能性失败（如 IM 把大动图转成
> 静态、动画直接没了）负责。
>
> 同样作废的还有第一轮「方案 2 — 整张长截图输出 MP4 ❌」的排除理由（见上），
> 以及一度写在 plan.md 里的「滚动视频天然 1:1、文字更清楚」这一卖点。

因此长视频的高度判断只剩 B1 那一条：内容高度超过
`getSupportedHeightsFor(1080)` 就禁用入口、提示改用滚动视频；没超就可用，多高都不拦。

**滚动视频的真实价值**相应收敛为两条：内容高度超过编码器上限时它是唯一选择；以及它
提供另一种呈现节奏（附件依次登场）。

体积也不成问题：实测 1080×4400 / 3 秒 / crf23 仅 0.75 MB。

作为参考而非约束记下典型高度：标题 ~100 + 正文六行 ~400 + 附件网格（2 列时
cell ≈ 520，两行 1040；只有 1 张附件时是全宽，`spanCount` 取
`min(itemCount, mMaxSpanImage)`）+ 音频卡 ~200 + padding ~150，合计 1700–3000 px，
多数记事离 4096 还有余量。

### 工作量与建议顺序

| 项 | 新增代码量 | 风险 |
|----|-----------|------|
| A. 多文件分享（含挂原视频） | 几十行，集中在 `ShareCallback` 与调用方 | 低 |
| B. 整图长视频 | 约 400–600 行，独立 helper（如 `ShareVideoComposer`） | 中，主要在设备编码能力差异 |

B 的 EGL 部分有成熟样板可循（Grafika 的 `EglCore` / `WindowSurface`）。两项可以并行
推进，A 先落地拿到反馈，B 做成分享菜单里与「长截图」并列的第三个入口。

## 追加评估（2026-07-26，第二轮）：内嵌原视频、HDR、分享选项

上一节的「挂原视频」被理解成了 `ACTION_SEND_MULTIPLE` 的挂载方式。这里评估的是另一件事：
**在生成的长视频 / 滚动视频内部，动态附件区域直接用原视频画面，而不是派生 GIF**。
多文件分享作为独立的第四种分享方式保留，不受本节影响。

### C. 长视频内嵌原视频

**画质上明显更好**：派生 GIF 是 256 色 / 长边 720 / 3 秒，原视频是全彩、原分辨率、原时长。
而且管线反而更顺：每个视频附件一个 `MediaCodec` 解码器 → `SurfaceTexture` → GL external
texture → 绘到画布对应矩形 → 编码器 input surface。**解码到编码全程在 GPU**，不落 Bitmap，
比「解派生 GIF 成 Bitmap 再上传 texture」还快。

四个新增约束：

1. **并发解码器实例数**。`MediaCodecInfo.getMaxSupportedInstances()` 通常报 8–16，但同时
   活跃的硬解实例在中低端机上更少。一条记事最多 9 张附件，全是视频就要 9 路并发。
   需要查询后降级：超限的附件退回派生 GIF，或分批解码。
2. **时长决策**。附件时长各不相同（5 秒 / 30 秒 / 2 分钟），长视频总时长取什么是个真实的
   设计问题。建议：设总时长上限（12 或 15 秒），每个视频从其 Thing Card Video Frame 起播，
   短于总长的循环，长于总长的截断。这与派生 GIF「从选定帧起 3 秒」的既有语义一致，只是放宽。
3. **音轨**。多个视频同时播，混音必然嘈杂。建议 v1 不带音轨；若要带，只取一个主视频
   （如第一个视频附件）的音轨。
4. **帧率对齐**。源视频 24/30/60fps 混杂，输出固定帧率（30fps），各解码器按输出 PTS 取最近帧。
   标准做法，无难点。

**代价是耗时和体积都随时长线性放大**：15 秒 / 30fps = 450 帧，硬编 1080×4400 每帧 25–50 ms
即 11–22 秒，加并发解码估 20–40 秒，必须给进度条（现在只有一个不确定态的 LoadingDialog）。
体积按实测的 0.75 MB / 3 秒线性推算约 3.75 MB，真实视频内容熵更高，估 4–6 MB，仍可接受。

### D. HDR 能否保留

**结论：长视频 / 滚动视频一律走 SDR。** 这不是新的妥协，是把 [ADR-0006](../../adr/0006-hdr-display-scoped-to-fullscreen-image-viewer.md)
既有的「HDR 只在全屏单图查看器」延伸到分享面。理由分三层：

**D1 两套 HDR 不是一回事。** 应用里的 HDR 是照片的 **UltraHDR / gain map**
（`Bitmap.hasGainmap()`，API 34+，见 `HdrImageDetector`）；视频的 HDR 是 HDR10 / HLG /
Dolby Vision（10-bit、BT.2020、PQ 或 HLG 传输函数）。把前者放进后者要把 SDR base 与
gain map 在 shader 里合成回线性光，再转到 BT.2020 + PQ/HLG 并以 10-bit 编码。
`Gainmap` 对象（`getGainmapContents()` / `getRatioMin/Max()` / `getGamma()` /
`getEpsilonSdr/Hdr()`）确实够写出这个 shader，但这是自己实现一遍 UltraHDR 合成。

**D2 编码与设备支持面窄。** H.264 没有可用的 HDR profile，必须 HEVC Main10 HDR10 或
AV1 Main10，并设 `KEY_PROFILE` / `KEY_COLOR_STANDARD=BT2020` / `KEY_COLOR_TRANSFER=HLG|ST2084`。
HEVC **解码**普及，**Main10 HDR 编码**远没有；GL 侧还要 `EGL_GL_COLORSPACE_BT2020_*_EXT`
扩展与 10-bit surface（RGBA_1010102 / FP16）。整体工作量是长视频功能本身的两三倍。

**D3 混合内容在 SDR 播放器上会颜色错误。** 长视频画布里同时有 HDR 照片、SDR 照片、
文字和渐变背景。整片编成 PQ/HLG 后，在不支持 HDR 的播放器上（以及 IM 转码之后，
这是常态）整个画面会发灰偏色。

这一条**沾接收方观感、按 2026-07-26 的裁定不单独作数**，但 HDR 的结论不依赖它：
D1 的实现成本、D2 的设备支持面已经足够，而最硬的一条是 Motion Photo 的内嵌视频本身
就是 SDR、`MediaMetadataRetriever` 取帧也永远是 SDR——**源里根本没有 HDR 可保**。

**关于动态照片的 HDR 与动态是否二选一：是，而且比「选择」更绝对。**
[ADR-0014](../../adr/0014-motion-photo-as-image-capability.md) 已经确认过这个取舍
（全屏松手看 HDR 静图、按住看动态），原因是 Motion Photo 的**静态主图可以带 gain map，
但内嵌的那段视频本身是 SDR**。所以在长视频里，动态照片区域必然是 SDR——不是我们放弃了 HDR，
是动态源里根本没有 HDR 版本。同理，`MediaMetadataRetriever` 取出的帧永远是 SDR
（见 hdr-media-display 的 Key facts），普通视频附件也没有 HDR 可保。

**唯一可能保住 HDR 的地方是静态长截图本身。** 现在 `getScreenShotForScrollViews` 用
`Canvas(bitmap)` 软件 Canvas 绘制，gain map 必然被丢弃，产物是普通 JPEG。理论上可以改成
硬件 Canvas + 为附件区域构造一张局部 gain map（非 HDR 区域填 0），把长截图存成 UltraHDR
JPEG。收益是接收方在 HDR 屏上看长截图时 HDR 照片会提亮；风险同样是 IM 转码会打平。
这条与本轮的动态化诉求正交，记在待办里，不建议现在做。

### E. 分享选项该怎么收敛

确实需要多个入口，但**不要把技术开关暴露成用户选项**。建议四个并列入口：

| 入口 | 产物 | 适用 |
|------|------|------|
| 长截图 | 静态 JPEG | 最通用，保底 |
| 长视频 | 布局高度与长截图一致的 MP4，附件原位播放 | 长图高度 ≤ 4096 px 时 |
| 滚动视频 | 竖屏 MP4，视口下滚 | 内容高度超过编码器上限时 |
| 附件单独分享 | `ACTION_SEND_MULTIPLE`：长截图 + 各附件原文件 | 要原画质、有声、可保存时 |

> **本节的选项设计已被 [plan.md](plan.md) 取代**：用户后续要求把时长、音轨、附件选择、
> 编码器、画质、是否保留记事布局都做成显式选项，而非代码自动决策；派生 GIF 亦被全面
> 禁用。以下保留原文供追溯。

下面这些**由代码自动决策，不问用户**：

- **动态源用原视频还是派生 GIF**：按并发解码器上限与视频体积自动降级；
- **编码尺寸**：按 `VideoCapabilities.isSizeSupported()` 与 `GL_MAX_TEXTURE_SIZE` 自动缩放；
- **长视频还是滚动视频**：长图超过 4096 px 时把「长视频」入口置灰或直接提示改用滚动视频；
- **HDR**：不给选项，一律 SDR，理由见 D。

真正值得放进设置（而非每次分享都问）的只有两项：**长视频时长上限**（默认 12 或 15 秒）与
**是否带音轨**（默认否）。

## 待实测

- 主流 IM（微信、QQ）对 GIF「转静态」的实际大小 / 尺寸阈值，以及对
  `ACTION_SEND_MULTIPLE` 混合 MIME（image/jpeg + image/gif + video/mp4）的处理。
  这两点决定方案 4 的最终形态是「挂派生 GIF」还是「挂原视频」。
- 长截图实际高度分布。本评估取 2400 / 4400 两档，需在真机上按典型记事核对，
  用来估计长视频入口因编码器高度上限而不可用的实际频率。
- 真机 `MediaCodecInfo.VideoCapabilities` 的实际尺寸上限（`getSupportedHeights()`、
  `isSizeSupported()`）与 `GL_MAX_TEXTURE_SIZE`，决定 B1/B2 降级触发的频率。
- IM 对视频体积与时长的限制，决定 A 里「挂原片还是挂派生 GIF」的阈值。
- 真机 `MediaCodecInfo.getMaxSupportedInstances()` 与实际可同时活跃的硬解实例数，
  决定 C 里几路视频附件之后要退回派生 GIF。
- 长视频的实际生成耗时（估 20–40 秒），决定是否必须把 LoadingDialog 换成带百分比的进度。

## 待办（不在本轮范围）

- **静态长截图保留 HDR**：把 `getScreenShotForScrollViews` 的软件 `Canvas` 换成硬件
  Canvas，并为附件区域构造局部 gain map，输出 UltraHDR JPEG。与动态化诉求正交，
  收益受 IM 转码打平的影响，见 D 末尾。
