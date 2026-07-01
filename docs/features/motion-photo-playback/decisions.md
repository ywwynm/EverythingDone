# 决策记录 / Motion Photo 播放

为 app 加入对各厂商 Motion Photo（动态照片 / 实况照片）的支持，能在应用内正确播放。
术语 **Motion Photo** 见根目录 `CONTEXT.md`。

## 2026-07-01

### D1 建模为图片附件的一种本性，术语用 Motion Photo（非新附件类型）
把 Motion Photo 建模为"图片附件被检测出的一种本性"，与 `HDR Media`、`Animated Image` 同构——同一个文件、额外能力按界面分级呈现（契合 ADR-0006/0007）。**不**新增与 `IMAGE(0)/VIDEO(1)/AUDIO(2)` 并列的附件类型。

理由：它落盘就是一张 `.jpg`/`.heic`（除 VIVO 外），MIME 是 `image/*`，默认处处显示为静态图；新增类型会让所有按类型分叉的逻辑（Thing Card Media Source 解析、裁切、Thing Card Appearance、详情附件网格分流）多一个分支，爆炸半径大而收益为零。

词汇表术语定为 **Motion Photo**（Android 阵营通用叫法），不用苹果商标 Live Photo。已写入 `CONTEXT.md`（术语 + 3 条 Relationships + 1 条 Flagged Ambiguity）。

### D2 v1 范围：检测 + 字节级原样保存 + 应用内播放 + 原样分享
**纳入**：导入时检测；单文件字节级原样保存（保留内嵌视频与元数据、保留真实扩展名）；在支持的应用内界面播放动态成分；分享时直接发原文件（对方设备上天然仍是 Motion Photo，零成本保真）。

**排除**：拍摄/创建；为互通做的格式转换（转 iPhone Live Photo 或其他厂商格式）；动态成分编辑（裁时长、AI 消除等）；拆分成独立的图片+视频两个附件。

注：现有**空间裁切**是展示期偏好、不改文件，裁切 Motion Photo 不会破坏它。

### D3 动态成分按界面分级呈现（沿用 surface-scoped 模型）
- **卡片封面**（首页/文件夹/Doing/Noticeable/widget 选择列表）：派生 GIF（复用 Thing Card Video Preview 管线），Cover Autoplay 开则自动循环，否则停在 Thing Card Video Frame。
- **详情附件列表**：派生 GIF，无条件自动循环（复用 Animated Image 的 Drawable 路径，列表内不放 live player）。
- **全屏预览**：默认 **HDR 静态图**（保留 ADR-0006 现状画质），**按住播放**真·内嵌视频，松手回到 HDR 静图。
- **裁切编辑器 / RemoteViews / 桌面 widget**：单帧不动（沿用 ADR-0007 / ADR-0012）。
- 三处显示 **LIVE/动态徽标**，兼作全屏"按住播放"的提示。

理由：封面 + 详情两个"列表类"界面完全复用派生 GIF（零新增播放器、列表内无 live player）；真视频播放收窄到全屏这一个单界面（画质要紧）；全屏"HDR 静图不可与 SDR 动态同时呈现"的冲突用 press-and-hold 天然化解（按住看动态、松手保 HDR）。全屏真播放是本特性唯一需要新写的一小块播放组件。

### D4 v1 不支持 VIVO 的动态照片
候选四家 **Pixel / 小米 / OPPO / 三星** 都是"单个 JPEG/HEIC 尾部内嵌视频"，被厂商无关的 ftyp 尾部扫描 + XMP 检测统一覆盖，无需特殊导入路径、无需新权限。（**v1 实测范围进一步收窄为 OPPO + 三星，见 D6**。）

**VIVO 排除**：它的视频是同目录下独立 MP4，JPEG 里只有 UUID；当前导入走 `ACTION_GET_CONTENT` 一次只拿到一个文件 URI，够不到兄弟文件。要拿到它必须引入 `READ_MEDIA_VIDEO` 权限（app 当前刻意零存储权限）或让用户手动再选一次，且靠"同名兄弟文件"是启发式、无法保证。综合权衡，v1 不为 VIVO 引入权限与特殊路径；VIVO 动态照片按普通静态图导入（其 JPEG 本身合法），不显示 LIVE 徽标。留作 followup（见 followups.md）。

一旦将来要支持：拿到兄弟 MP4 后，直接拼接到我们复制的 JPEG 尾部即可——ftyp 尾部扫描会像对待其他厂商一样识别它，下游零特判。复杂度全部隔离在"导入时如何拿到那个 MP4"。

### D5 导入/存储与检测接入：JPEG + HEIC 均支持
**导入保真已就绪（读码确认）**：导入拷贝走 `FileUtil.copyUriToFile` / `copyFile`，均为 `openInputStream → FileOutputStream` 裸字节流、不重编码——内嵌视频天然完整保留，无需改动。类型判定按扩展名，`.jpg`/`.heic` 归 `IMAGE`，Motion Photo 自动落为图片附件（合 Decision 1）。

**支持 HEIC**（用户要求）：
- 修 `FileUtil.getPostfixFromMimeType`，`image/heic`→`.heic`、`image/heif`→`.heif`（现状把二者误映射为 `.jpg`），并把 `heic/heif` 加入 `AttachmentHelper` 图片扩展名允许列表。
- 已知限制：Android HEIC 解码需 **API 28+**，本 app minSdk 26；API 26/27 上 HEIC（含其静态图）本就无法解码，属既有限制，不为此特殊处理。

**检测接入**：不改数据库、不改附件编码（`🚩0/path`）。运行时按需检测 + 按"路径+大小+修改时间"签名缓存，后台限并发（沿用 `VideoCoverPreviewManager` 思路）。

### D6 检测策略、依赖与 v1 实测范围
**依赖**：**不引入 Adobe XMP SDK**。对 APP1 段的 XMP 文本做轻量字段提取（只认已知标记），偏移最终靠 ftyp 扫描 + 校验兜底、不依赖 XMP 里的数字。理由见下方 offset 歧义。

**经验式检测（绕开 offset 方向歧义）**：
1. 读 JPEG APP1 段 XMP，取"是否动态照片"布尔标记（OPPO：`Camera:MotionPhoto` / `OpCamera:OLivePhotoVersion`）；三星扫 16 字节 `MotionPhoto_Data` 标记（HEIC 为 `sefd` box）。
2. 从主图数据之后扫 MP4 `ftyp` 盒子头（`ftyp` 前 4 字节为 box size）。注意视频可能从文件中段就开始，扫描起点要从主图结束附近而非仅后半段。
3. 候选偏移逐个用 `isValidMp4` 校验，**取第一个通过者**——彻底绕开 `MicroVideoOffset` 方向歧义（各资料自相矛盾），也天然排除普通照片。
4. HEIC 内嵌视频在 `mpvd`(Google)/`sefd`(三星) box 内，里面仍是带 `ftyp` 的 MP4，尾部扫描 + 校验可定位；box 精确解析留作可选增强。
5. 检测结果按文件签名缓存。

**v1 实测范围 = OPPO + 三星**（用户仅有这两家测试机，沿用真机实测工作流）。ftyp 扫描厂商无关，小米/Pixel 很可能顺带可用，但 v1 不作声明、不测试；有测试机后再纳入（见 followups）。OPPO 的 Motion Photo 常带 GainMap（HDR），正是 Decision 3 全屏"HDR 静图 + 按住播放"要处理的具体场景。

### D7 播放技术：不长期存第二份视频
- **封面/详情派生 GIF**：检测已知偏移 → 把内嵌 MP4 段**临时抠到 temp** → 交现有 `VideoCoverPreviewManager` 派生 GIF（GIF 按现状持久缓存）→ **删 temp**。管理器基本不动；持久磁盘只多那张派生 GIF，与现有视频封面一致。派生 GIF 缓存 key 纳入偏移与文件签名。
- **全屏真播放**：`MediaPlayer.setDataSource(fd, offset, length)` **就地播放**，挂 `Surface`/`TextureView`，按住循环、松手 `stop` 回到 HDR 静图（PhotoView）。不落 temp。`VideoView` 不支持 offset，故全屏自写一小段 `MediaPlayer` + Surface——本特性唯一新增的播放组件。
- 起始帧从视频开头循环（不依赖 `PresentationTimestampUs`，与"不加 XMP SDK"一致）。
- **静态回退即照片本身**：Motion Photo 的静态帧就是它的主图（JPEG/HEIC），不需要像视频那样让用户挑 Thing Card Video Frame，也无需帧选择器。

#### 修订（2026-07-01，实现时）：封面/详情派生 GIF 改为"确定性缓存文件"而非"temp→删"
D7 原定"临时抠→派生→删 temp"。实现时发现 `VideoCoverPreviewManager` 的派生是**异步 WorkManager**、且缓存 key 由源视频文件的"路径+大小+修改时间"算出——temp 每次 mtime 变会让 GIF 缓存 key 不稳定、反复重生成，且 Worker 跑之前不能删。改为：新增 `MotionPhotoCoverHelper`，把内嵌视频抠到一个**确定性缓存文件**（`cacheDir/motion-photo-videos/<图片签名>_<offset>.mp4`），并把它的 **mtime 设为源图 mtime**，使派生 GIF 的缓存 key 跨"被 LRU 淘汰后重抠"保持稳定。抠出 MP4 有独立 512MB LRU；`VideoCoverPreviewManager` 一行未改。代价：抠出的 MP4 在被淘汰前是一份额外副本（有界）。全屏按住播放仍是 `MediaPlayer(fd,offset,length)` 就地读、无副本。

### D8 全屏交互细化与三处修复（第 1 版真机反馈后）
第 1 版全屏在 OPPO 上验证：检测、徽标、长按播放均通。据反馈修三处：
1. **徽标**：从"两个裸字"改为同心圆图标 + 半透明药丸（`ic_live_badge` + `bg_live_badge`），更接近大厂。
2. **保留缩放**：长按前捕获 PhotoView 当前 `displayRect`（含缩放平移），视频铺到同一区域，实现"原位原缩放动起来"。
3. **消除双层**：播放期间 `photoView.isZoomable = false` 锁定下层静图，停止时恢复——修复"播放中缩放出现背后另一层图"的问题。

### D9 全屏播放的"停止契约"：仅长按按住播放在松手时停，自动播放不被触摸打断
把两种播放的生命周期分开：
- **自动播放（打开/翻到该页触发，`loop=false`）**：播完一遍由 `OnCompletionListener` 自行停止。**任何触摸都不打断**——期间点击（切系统 UI）、缩放松手都不停。
- **长按按住播放（`loop=true`）**：抬手 / 取消（`ACTION_UP/CANCEL`）即停，回到静图（press-and-hold 语义）。

实现：`mMotionHoldToPlay = loop`；`dispatchTouchEvent` 仅当 `mMotionHoldToPlay` 为真时在 UP/CANCEL 停止。

理由：自动播放是"打开就自动演示一遍"，被一次点击/缩放就打断违反预期；而按住播放的核心语义就是"按着才动、松手就停"。用一个 `loop` 标记天然区分二者，无需额外状态机。

### D2 补充 卡片封面播放复用现有 视频→GIF 管线
用户明确希望：把内嵌视频抠出后，作为视频源接入现有 `VideoCoverPreviewManager` / Thing Card Video Preview 派生 GIF 管线，从而在记事卡片封面上也能动（受 Cover Autoplay 门控）。这不属于被排除的"格式转换"，而是复用既有展示管线。
