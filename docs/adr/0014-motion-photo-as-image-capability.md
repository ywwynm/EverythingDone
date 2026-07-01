# Motion Photo 建模为图片能力、按界面分级播放（Motion Photo modeled as an image capability with surface-scoped playback）

各厂商的动态照片/实况照片（**Motion Photo**）在应用内正确播放。它被建模为**图片附件被检测出的一种本性**（同一个文件、额外能力按界面分级呈现），而非与 IMAGE/VIDEO 并列的新附件类型——与 `HDR Media`、`Animated Image` 同族。检测采用**经验式**（XMP 标记 + 三星 `MotionPhoto_Data` 标记 + 尾部 `ftyp` 扫描 + `isValidMp4` 校验后采用），**不引入 Adobe XMP SDK**。播放**按界面分级**：卡片封面与详情列表复用既有"视频派生 GIF"管线，全屏保留 HDR 静图、按住时用 `MediaPlayer(fd, offset, length)` 就地播放内嵌视频。v1 实测范围为 **OPPO + 三星**；**VIVO 排除**。

这**扩展** [ADR-0007](0007-animated-image-playback-scoped-per-surface.md) 与 [ADR-0012](0012-thing-card-video-preview-derived-animated-image.md) 确立的"按界面分级播放"模型，并**首次引入全屏内的真视频播放**（此前全屏对图片走 `asBitmap` 保 HDR、对视频走外部播放器）。

## 为什么

**为什么建模为图片能力、而非新附件类型：** Motion Photo 落盘就是一张 `.jpg`/`.heic`，MIME 是 `image/*`，默认处处显示为静态图。它与 `HDR Media`（图片带 gain map，仅支持界面提亮）、`Animated Image`（文件本身是动图，仅支持界面逐帧播）在结构上完全同构——"同一个文件、额外能力按界面分级呈现"。若新增 `MOTION_PHOTO` 类型，所有按类型分叉的逻辑（Thing Card Media Source 解析、裁切、Thing Card Appearance、详情附件网格分流）都要多一分支，爆炸半径大而收益为零。

**为什么卡片/详情用派生 GIF、而非真视频：** 沿用 ADR-0012 的结论——列表内跑多个 live 播放器又重又易 jank，且真视频走不了既有的逐帧 `renderCrop` 裁切管线。把内嵌视频接进已反复打磨的"视频→派生 GIF"管线，新增渲染层代码最少、裁切/屏外暂停/回收全部现成。

**为什么全屏是 HDR 静图 + 按住播放真视频：** 全屏为保 UltraHDR gain map 走 `asBitmap` 只解静态图（ADR-0006），而 OPPO 的 Motion Photo 恰恰**既是 HDR、又有动态**——全屏同一时刻只能二选一。`press-and-hold`（iOS 惯例）天然化解：松手看 HDR 静图、按住看动态。全屏是单界面、画质要紧，值得用真视频而非降质 GIF，也是本特性唯一需要新写的一小块播放组件。

**为什么经验式检测、不加 XMP SDK：** `MicroVideoOffset` 的方向各家资料自相矛盾（Google 官方"从尾倒数" vs 实测文章"从头偏移"），光信 XMP 数字会切错。改为"收集候选偏移（含 ftyp 扫描）→ 逐个 `isValidMp4` 校验 → 取第一个通过者"，彻底绕开歧义，也天然排除普通照片。既然不依赖 XMP 精确数字，就只对 XMP 文本做轻量标记提取，不引入 `com.adobe.xmp:xmpcore`，保持项目一贯的依赖克制。

**为什么 v1 只 OPPO + 三星：** 沿用"真机实测"工作流，只声明手上有测试机、能验证的两家。`ftyp` 扫描是厂商无关的，小米（MicroVideo）、Pixel（MotionPhoto/Container）很可能顺带可用，但 v1 不声明、不测试；有测试机后再纳入。

**为什么排除 VIVO：** VIVO 的视频是同目录下独立 MP4，JPEG 里只有 UUID。当前导入走 `ACTION_GET_CONTENT` 一次只拿到一个文件 URI，够不到兄弟文件；要拿到必须引入 `READ_MEDIA_VIDEO` 权限（app 当前刻意零存储权限）或让用户手动再选一次，且靠同名启发式无法保证。权衡后 v1 不为 VIVO 引入权限与特殊路径。

## 影响

- **范围**：v1 声明支持 OPPO + 三星的 JPEG 与 HEIC 动态照片；小米/Pixel 大概率顺带可用但不声明；VIVO 按静态图导入、不显示 LIVE 徽标。
- **播放界面**：卡片封面（Cover Autoplay 门控）与详情列表播派生 GIF；全屏 HDR 静图 + 按住真视频；裁切编辑器 / RemoteViews / 桌面 widget 停在静态主图。三处显示 LIVE 徽标。
- **导入/存储**：裸字节流拷贝（已就绪，不重编码），内嵌视频天然保真；修 `getPostfixFromMimeType` 保留 `.heic/.heif`；检测运行时按文件签名缓存，不改数据库、不改附件编码。
- **检测/提取**：APP1 段 XMP 轻量标记 + 三星 `MotionPhoto_Data` 标记 + 尾部 `ftyp` 扫描 + `isValidMp4` 校验；派生 GIF 时临时抠内嵌 MP4、用完即删；全屏就地 `MediaPlayer(fd,offset,length)`，全程不长期存第二份视频。
- **HDR**：Motion Photo 的静态主图可本身是 HDR（OPPO GainMap），全屏静图态照常提亮；派生 GIF 为 SDR，但卡片/详情本就走 SDR 路径，无回归。
- **HEIC**：解码需 API 28+，本 app minSdk 26；API 26/27 上 HEIC（含静态图）本就无法解码，属既有限制。
- **性能**：派生 GIF 沿用现有屏外暂停/回收/LRU；检测首次读文件、之后命中缓存，后台限并发。
- 详细决策见 [motion-photo-playback/decisions.md](../features/motion-photo-playback/decisions.md)，术语见 [CONTEXT.md](../../CONTEXT.md) 的 **Motion Photo**。
