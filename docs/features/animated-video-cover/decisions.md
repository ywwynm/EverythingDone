# 决策记录 / 动态视频封面（Animated Video Cover）

让视频封面也能在 Thing Card 上自动循环播放。术语 **Thing Card Video Preview** 见根目录 `CONTEXT.md`；本特性修订 [ADR-0007](../../adr/0007-animated-image-playback-scoped-per-surface.md) 中"任何视频缩略图都停在单帧"的结论。相关既有特性见 [animated-image-playback](../animated-image-playback/README.md)。

## 2026-10-20：首页本地 GIF 使用文件映射

详情独立播放的性能复核扩展到真实首页后，四个约 35 MB GIF 在三机触发 64 MiB 临时数组
分配失败，关闭独立播放隔离的对照组也会出现。首页 String 路径仍走整文件输入流解码，
此前详情 File 映射修复未覆盖此处。

新增 `localAnimatedImageModel`，将存在的本地 GIF／WebP 交给 File loader；同时覆盖首页
缩略图、媒体背景、播放和静态首帧入口。其它模型保留原加载方式。继续使用原有的裁切、
帧尺寸、播放策略、失败回退和缓存预算，不将首页改成独立 decoder。证据及三机结果集中见
[性能复核](../detail-animated-playback/owner-performance-2026-10-20.md)。

## 2026-10-15：取消暂停后的性能结论边界

用户要求取消暂停后继续进行复杂列表性能对照。三机同 APK、97 卡的完整 A/B 中，普通／选择／长按滑动未出现一致的明显流畅度回归，但惯性阶段工作内存增加，整体峰值约增加 2～5%。不能把功能回归通过扩展为“零内存代价”或“全场景无退化”。魅族异常呈现轨迹仍未确认；用户在冷却补测遇到锁屏后要求“先按已有结果整理”，因此补测暂缓。详见 [性能对照](scroll-performance-comparison-2026-10-15.md)。本轮只增调试测量与记录，不恢复暂停、不压低画质、不追加未经归因和 UI 对照的缓存调整，也不重新发布相同生产实现。

## 2026-10-15：普通滑动持续加载播放与返回回显

- 用户要求普通手指滑动和 fling 也必须显示、播放已加载 GIF。经 `43e0ee2f3`／`d4cc84298` 历史及实机红绿对照，取消首页滚动状态对全局请求的暂停，以及 BaseThingsAdapter 的 M2 可见动画暂停。本条取代下文 D8 中“快滑暂停”的旧决定。
- 新复现的临时缓存／预取目标在离屏时仍播放：按 holder 附着边界同步 Drawable 可见性，且目标接收异步资源后核对真实附着状态。临时缓存保留请求与画面，正式回收继续 clear；不依赖滚动状态替代目标生命周期。
- 参考 `1b4c07096` 静态回显缓存，GIF 仅复制已经裁切好的首帧，在同一 8–24 MiB 上限内作为返回时的 placeholder。完整 GIF 继续异步加载；包含实际动图内容身份的 key 保证修改文件、裁切、尺寸或所选视频帧后不复用旧画面。未命中时只将加载圈延迟 120ms，超时仍显示，不隐藏持续等待。
- 详见 [普通滑动调查](regular-scroll-investigation-2026-10-15.md)。不提高缓存预算、不保留所有 decoder、不降低画质或帧率。

## 2026-10-15：按实测修复分配与回收，不改变播放表现

- 先核对 `c6e275ed4`、`2da3f98df`、`d4cc84298` 等历史，保留逐帧预裁切、原输出尺寸／sRGB、裁切参数、缓存签名、帧率、自动播放与失败回退。
- 实测 `MediaCropTransformation` 未查询位图池，连续帧产生大量 native 分配。改为使用 Glide 已提供的 BitmapPool，清空旧像素后按同一数学绘制；静态烘焙仍自行创建输出，避免改变其所有权。像素回归覆盖透明、RGB565、Display-P3 及 RGBA_F16 池内容。
- 实测已进 RecyclerView 回收池的卡片仍持续播放 GIF。BaseThingsAdapter 在 `onViewRecycled` 清除两种封面请求、load key 和异步绑定 token；普通临时离屏 cache 不清空，避免重现时丢失已加载画面。
- 只修复复用后仍可测到大帧挤满一屏池的反复淘汰。魅族受控对照中两屏池把 PSS 峰值从 1127 MiB 降到 760 MiB、池 miss 分配从约 2423 MiB 降到 620 MiB。采用 Glide 官方 MemorySizeCalculator 的两屏池，继续受默认总预算约束；API 26 前、low-RAM 或会压缩原资源缓存的设备维持默认。`MediaGlideModule.java` 使用项目已有 Java annotationProcessor 注册，不引入新依赖。
- 对照详见 [内存归因](memory-investigation-2026-10-15.md)。不以停滚后加载、降低尺寸或停止拖动中的 GIF 作为本轮优化。

## 2026-06-30

### D1 机制：从视频派生 GIF，喂进现有动图管线（方案 A）

在"视频封面播放"的三条路里选 A：后台批量取帧 → 编码成 GIF 文件落磁盘缓存 → 加载器指向派生 GIF，照走现成的 `GifDrawable` + `MediaCropTransformation` + Glide 生命周期。

- **否决 B（卡片内播放真实视频，ExoPlayer/TextureView）**：与"逐帧 `renderCrop`、与烘焙位图同显示契约"的架构正面冲突，live surface 走不了 `renderCrop`；列表里多个播放器又重又易 jank；对列表缩略图属过度方案。
- **否决 C（运行时取帧 + 自定义动画 Drawable，不落地 GIF）**：屏外暂停、RecyclerView 回收、内存淘汰要自己重写一遍，而 A 里这些是 Glide 白送的；本项目在 RecyclerView/布局生命周期上反复踩坑，避免手写动画 Drawable 更稳。
- 选 A 还因为"按需生成 + 带 key 缓存"本就是 Thing Card Video Frame 既有模式（见 [thing-card-media-target-geometry decisions 2026-06-02](../thing-card-media-target-geometry/decisions.md)），派生文件缓存是其自然延伸。

### D2 GIF 编码器：原生 `androidndkgif`（NDK/C++）

`io.github.waynejo:androidndkgif:1.0.1`（Maven Central），原生 NDK GIF 编码器，AAR 自带 arm64-v8a / armeabi-v7a / x86 / x86_64 四 ABI，直接吃 ARGB_8888 Bitmap：`init(w,h,path,EncodingType)` → `encodeFrame(bitmap, delayMs)` → `close()`，原生写文件。

**演进（2026-06-30 真机实测推翻初选）**：最初设计期选 Square `com.squareup:gifencoder`（纯 Java、median-cut + Floyd-Steinberg），理由是无原生依赖、缩略图尺度画质足够。真机逐帧计时显示 Square 编码 **约 12s/帧**（480×320），31 帧约 6 分钟，完全不可用（`decodeMs~90 / encodeMs~13000`）；取帧不慢（~100ms），瓶颈在纯 Java 量化+抖动。

- 改用原生 `androidndkgif`：原生编码约几十毫秒/帧、快几十倍；**正规 Maven 依赖**（自行 vendoring 第三方 Java 源码会被安全策略拦），AAR 含四 ABI，APK 每 ABI +约 150–290KB。
- **否决 gifski**：画质天花板，但只发布桌面平台 `.so`，**无 Android AAR**，需自行把 Rust 交叉编译进四 ABI，工程量大。
- **否决 ffmpeg**：ffmpeg-kit 已退役 + 编解码专利法律风险。
- **否决 vendoring 纯 Java AnimatedGifEncoder(NeuQuant)**：比 Square 快但仍纯 Java(~300ms/帧)，且自行下载第三方源码被安全策略拦。
- 用户取向：生成慢可接受（但要求健壮后台 + 扛进程被杀，见 [followups.md](followups.md)）；原生已足够快，robustness 仍按 WorkManager 跟进。

### D3 预览内容：以 Thing Card Video Frame 为循环起点（方案 a）

动态封面以用户已选的 Thing Card Video Frame 为起点向后播 N 秒、循环。让既有"选一帧"UI 自然延伸为"选循环起点"，不新增起止区间选择器（留作未来增强）。

- **静态回退**：自动播放关闭 / GIF 未生成好 / 生成失败时，显示 Thing Card Video Frame 那一帧（现状不变，零回退风险）。
- **默认参数（可后调 tuning 值）**：时长约 3s；帧率约 20fps（GIF 帧延迟按厘秒存储，20fps=5cs 整除干净；用户要求略高于初始 12–15fps 的提议，再高如 25fps 主要多花文件体积与滚动解码成本）；编码长边设上限（约 ≤480–540px，足以撑过显示期裁切放大）；循环播放。

### D4 自动播放设置：统一一个开关，默认开

加一个用户设置"自动播放封面动态内容（GIF / 动态 WebP / 视频）"，统一管控 Animated Image 与 Thing Card Video Preview 在 Thing Card 封面上的 Animated Playback。

- **统一 vs 拆分**：选统一单开关，贴合"一个设置项"的原意；否决拆成"动图封面 / 视频封面"两个开关（虽更贴合 GIF 零成本、视频有生成+存储成本的不对称，但多一个设置项）。
- **默认值：开**。否则现有 GIF 用户升级后 GIF 会突然停（行为回归）。代价：默认开 + 统一意味着升级后已设视频封面的记事会开始在后台生成 GIF（有界的新成本）。
- **关闭时**：GIF→静态首帧，视频→Thing Card Video Frame，均为现状静态行为。
- 落点：把既有 `mAnimatedPlaybackEnabled`（`BaseThingsAdapter`，默认 true，仅 widget 预览设 false）从硬编码改为读该设置。

### D5 生效范围：统一覆盖全部应用内 Thing Card 封面面；详情/全屏排除

统一开关管控全部 A 类 Thing Card 封面面的封面动态播放（GIF + Thing Card Video Preview）：首页列表、文件夹卡片子项缩略图、DoingActivity、NoticeableNotificationActivity、widget 配置页"选择记事"列表（均走 `loadThingCardImage` / `mAnimatedPlaybackEnabled`）。

- **统一覆盖全部 A 类 vs 仅滚动列表**：选统一。`mAnimatedPlaybackEnabled` 本就集中一处；"设置管的是封面动态内容、不论卡片出现在哪"心智最简单；Doing / Noticeable 虽单卡无性能顾虑，但处处一致最不令人意外。
- **B 类（详情附件列表、全屏预览）不受开关影响**，照旧无条件播放——这两处是"主动打开查看附件"，超出"列表封面"语义；视频在详情/全屏本就是点击播放真实视频，不涉及派生预览。
- **widget 桌面实体（RemoteViews）** 无法播放动画，永远静态（ADR-0006/0007）。

### D6 生成时机：Lazy（首次显示后台生成）+ 持久缓存做到"生成一次"

卡片绑定时若派生 GIF 未缓存，先显示静态 Thing Card Video Frame，后台生成，就绪后换成动画；派生 GIF 持久化存盘，每个封面只生成一次，"静态→动画"跳变一生一次。

- **否决 Eager-only**：升级存量视频封面没有"设封面"触发点，覆盖不到；lazy 路本就绕不开。
- **Eager 预热**（设封面/改帧后预先生成、回列表零跳变）留作 follow-up，不进 v1，见 [followups.md](followups.md)。
- 生成走单线程 / 小线程池队列、优先可见卡片，避免滚动期并发爆发；严格后台、不阻塞 UI。
- 生成失败：保持静态 Thing Card Video Frame，不重试风暴（可记一次失败、下次显示再试）。

### D7 存储与生命周期

- **位置**：`externalCacheDir/video-cover-previews/`（即 `Android/data/<pkg>/cache/`，不可用时退回内部 `cacheDir`）。两者都是缓存语义（卸载即清、系统存储紧张可回收、被回收后 lazy 再生成）。2026-06-30 实现时从内部 `cacheDir` 改为优先外部缓存：内部 `cacheDir`（`/data/data/...`）真机未 root 无法浏览，改外部后用户可直接核验派生文件，且贴合附件存外部目录的心智。
- **缓存 key**：`typePathName` + 文件 mtime/大小 + 起始帧 `videoFrameMs` + 时长 + 帧率 + 编码分辨率上限；**不含裁切**——裁切在显示期由 `MediaCropTransformation` 逐帧套用，改裁切不重生成 GIF（与烘焙缩略图把裁切纳入 key 相反，机制使然）。
- **清理/失效**：改帧 / 改 Media Source / 删附件 → 旧 key 文件立即删；记事永久删除 → 删其派生文件，进回收站不删（可恢复）；调参致 key 变的孤儿由 LRU 回收。
- **容量上限**：该目录 LRU 上限 **1GB**，超限按最近最少使用淘汰；`cacheDir` 同时受系统存储压力回收兜底。

### D8 默认参数与性能取向

- **参数不暴露给用户**：只保留一个 on/off 开关；时长/帧率/分辨率均为内部 tuning 常量。
- **时长 ~3s；编码长边上限 720px**（单一上限，不按目标尺寸分别生成；缩略图/侧栏/普通宽卡锐利，全宽媒体背景略软可接受）。
- **帧率**：GIF 帧延迟按厘秒（1/100s）存储，30fps=3.33cs 无法整除，干净取值是 **25fps（4cs）** 或 **~33fps（3cs）**。默认取 **25fps**（接近电影 24fps、缩略图尺度已很顺，给软件解码留余量），作为可后调常量；真机有余量再上调到 3cs(~33fps)。
- **GIF 无硬件解码**：Android 无硬件 GIF 解码器，Glide `StandardGifDecoder` 逐帧 CPU 软解（后台线程）。已查证其不对帧延迟设人为下限——写多少显式延迟就按多少播，但能否跑满目标帧率受设备软解能力限制；fps×分辨率×可见封面数直接决定 CPU 负载。
- **性能策略**：沿用 ADR-0007 的 M1（Glide 屏外自动暂停屏外 GIF）；因视频派生 GIF 比一般 GIF 更重，**主动加 M2**（`DRAGGING/FLING` 期间暂停可见预览、`IDLE` 恢复，挂现有 `OnScrollListener`）。最终 fps 按真机实测定。
- **HDR**：视频可能是 HDR（HLG/PQ），派生 GIF 是 8-bit SDR；但卡片封面本就走烘焙 SDR 路径（ADR-0006/0007），从不显示视频 HDR，故无回归。
- **首帧对齐**：派生 GIF 以 `videoFrameMs`（Thing Card Video Frame）为第 0 帧，故"静态→动画"切换时画面连续、无跳变。

### D9 设置项落位与文案

- **落位**：设置界面显示/外观组，靠近深色模式开关。
- **文案**：标题"自动播放封面动态内容 / Autoplay cover dynamic content"；副标题"在列表卡片上自动播放作为封面的 GIF 与视频 / Play GIF and video covers on list cards automatically"。
- **键**：`Def.Meta` 新增（如 `KEY_AUTOPLAY_COVER_DYNAMIC`），默认 true，经 `FrequentSettings` 读写。

### D10 生成迁到 WorkManager + 边界情况模型（2026-06-30）

把派生生成从内存 Executor 迁到 WorkManager（`services.VideoCoverPreviewWorker`），满足"后台持续生成、扛进程被杀"：

- 每个派生预览 = 一个唯一 work（名 = 缓存 key），`ExistingWorkPolicy.KEEP` 去重，持久化、进程重启恢复、失败按 `runAttemptCount` 有限重试（≤3 次）后放弃。
- **边界情况由"按视频+帧定键、跑到完成为止"天然处理**：封面中途从视频 A 改成图片 B / 自动变成新增图片 C → A 的 work 不取消、跑完缓存留作备用；改帧 A→A' → 新 key 新 work，旧帧产物由 LRU 回收。
- **视频被删**：Worker 起步即 `File.exists()` 检查，不存在→`Result.failure()` 不重试；生成中途删除→`generate` 返回 null + 复查文件不存在→失败。无崩溃。
- **即时换装**：Worker 与 UI 同进程，成功后调 Manager 内存登记的 swap 回调（`notifyGenerated`）；进程被杀重启则回调丢失，但文件已落盘，下次绑定 `getReadyPreview` 取用。
- 沿用项目既有 WorkManager 约定（`AlarmHealthWorker` 同款 `Worker` + `enqueueUniqueWork`）。

## 状态

设计定稿（2026-06-30）。落地清单见 [execution.md](execution.md)，概览与验收见 [plan.md](plan.md)，架构 [ADR-0012](../../adr/0012-thing-card-video-preview-derived-animated-image.md)。
