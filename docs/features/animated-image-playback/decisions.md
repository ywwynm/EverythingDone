# 决策记录 / 动图播放

围绕"让 GIF(及动态 WebP)在更多界面播放"的设计决定。术语见根目录 `CONTEXT.md` 的 **Animated Image** / **Animated Playback**。

## 2026-06-24

### D1 裁切 dialog 不做动画
裁切 dialog(`ThingCardCropEditorView`,`onDraw` 里 `canvas.drawBitmap` 单张 Bitmap)是"选择空间裁切"的工具,播放动画对该任务无帮助且改造成本高;视频在裁切时也固定取某一帧(`videoFrameMs`),GIF 用第一帧与之一致。因此裁切 dialog 保持显示第一帧,排除在 Animated Playback 范围之外。

### 候选判定:扩展名 ∈ `{gif, webp}`
"是否按 Animated Image 走 Drawable 路径"用扩展名 `{gif, webp}` 判定。Glide 在 Drawable 路径下会自动区分:真动图就播,静态 WebP 当普通图显示,均不出错;静态 WebP 也不带 gain map,所以走 Drawable 不损失 HDR。一次覆盖 GIF 与动态 WebP。

### D2 全屏预览(ImageViewerActivity)对 Animated Image 播放
GIF / 动态 WebP 不带 HDR gain map,`asBitmap` 对它们无 HDR 价值。按候选判定分流:命中则 `Glide.load().into(PhotoView)` 走 Drawable 自动播放,否则保留现有 `asBitmap` 的 HDR 路径。单图、零裁切冲突、无滚动性能顾虑。

### D3 卡片 + 详情定制模式:只给 GIF 开分支,自定义 Transformation 保留裁切(方案 a)
**背景(为何当初烘焙)**:2026-06-17 先为修文件夹缩略图视频裁切 bug 改成烘焙目标尺寸位图,随后要求去掉所有 `ImageView.imageMatrix` 裁切依赖、"凡可行处都用预裁切位图";`imageMatrix` 那套(stale matrix、replay hook、移动/缩放动画期间矩阵被重置)脆弱。烘焙后 ImageView 收到恰好目标尺寸的位图、统一 `CENTER_CROP`,裁切被冻结进位图,长按移动整卡缩放也不错位。`dontTransform()` 保留以防 Glide 抢先 centerCrop([thing-card-media-target-geometry decisions 2026-06-04/06-17](../thing-card-media-target-geometry/decisions.md))。

**决定**:只在"源是 Animated Image"时开分支,**静态烘焙路径一行不动**,影响面钉死在 GIF/动态 WebP。GIF 分支写一个自定义 Glide `Transformation`,复用 `renderCrop` 的 center/scale/比例数学**逐帧**裁切 → 得到"每帧已裁好、恰好目标尺寸"的 GifDrawable,仍 `CENTER_CROP` 显示。这与烘焙位图是同一显示契约,不回到 `imageMatrix`,长按移动表现一致;给的是显式 Transformation,Glide 不会再抢先 centerCrop。详情定制模式与卡片共用同一条 GIF 分支(详情定制模式的 GIF 也因此能动)。

**实现要点**:抽一个接收单帧 `Bitmap` 的 `renderCrop` 重载供 Transformation 复用;Transformation 的缓存 key 纳入裁切 fingerprint(centerX/centerY/scale/比例/目标尺寸);GIF 源跳过 Bitmap `LruCache`,交给 Glide 自身缓存与生命周期管理。

### D4 应用内卡片面统一播放;widget 的"预览"保持静态
`loadThingCardImage` 被首页列表、文件夹卡片子项缩略图、DoingActivity、NoticeableNotificationActivity、以及 widget 配置页的**记事选择列表**共用。GIF 分支对**所有应用内卡片面**生效。`setAnimatedPlaybackEnabled` 开关只用于真正的"widget 预览"——桌面真实 widget 是 RemoteViews(launcher 进程,只能显示烘焙位图,无法播动画,ADR-0006 同理排除)。视频缩略图不在此列,仍是静态取帧。

#### 修正(2026-06-24,第一轮测试反馈)
第一版把开关错误地加在了 widget 配置页的 `mThingCardAdapter` / `mFolderCardAdapter` 上——但这两个是**选择记事的浏览列表**(`mRecyclerView`),不是 widget 预览,导致选择页的 GIF 不会动。真正的 widget 预览走 `renderPreviewAppWidget` → `createRemoteViewsForSingleThingPreview`(RemoteViews,本就静态),根本不经过这两个适配器。修复:选择列表恢复默认播放;只有 `renderFallbackThingCardPreview` 里那个充当预览的 `BaseThingsAdapter` 仍 `setAnimatedPlaybackEnabled(false)`。

### D5 滚动性能:v1 仅靠 Glide 屏外暂停(M1),真机实测再决定是否加 fling 暂停
v1 不主动加滚动暂停逻辑:Glide 在 item 回收时自动停掉屏外 GifDrawable,只有当前可见 GIF 在解码播放;fling 时照常播。备选 M2(`DRAGGING/FLING` 暂停可见 GIF、`IDLE` 恢复,挂到现有 `OnScrollListener`)随时可加,实测掉帧再上。

**验证方式**:不自测。按既有工作偏好([memory/preferences.md](../../../memory/preferences.md) "Publishing and commits"),编译通过后发布 debug 更新到阿里云渠道,由用户在真机用塞满 GIF 的列表实测。

