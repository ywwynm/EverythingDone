# 会话记录 / Motion Photo 播放

## 2026-07-01 — 立项 grill + 第 1 版（全屏按住播放）发布

**调研**：对比 Pixel / 小米 / OPPO / 三星 / VIVO 的动态照片格式（含 2025-10 搜狐《Android动态图片技术深度解析》PDF 与 2025–2026 平台互通进展）。关键结论：四家单文件内嵌、可用厂商无关的尾部 `ftyp` 扫描覆盖；VIVO 独立文件、需权限，排除；`MicroVideoOffset` 方向各资料矛盾，故用"扫 ftyp + 解码校验"绕开。

**Grill 敲定 7 项决策**（见 decisions.md D1–D7），并落文档：[ADR-0014](../../adr/0014-motion-photo-as-image-capability.md)、[CONTEXT.md](../../../CONTEXT.md) 新增术语 **Motion Photo**、README/plan/execution/followups。

**第 1 版实现（tracer bullet：先验证检测 + 真播放核心）**：
- 新增 `MotionPhotoDetector`（扫 `ftyp` + `MediaMetadataRetriever` 校验 `HAS_VIDEO`，按文件签名缓存；`extractEmbeddedVideo` 备用）。
- `AttachmentHelper.isImageFile` 加 heic/heif，新增 `isMotionPhotoCandidate`；`FileUtil.getPostfixFromMimeType` 保留 `.heic/.heif`。
- `ImageViewerActivity` + `tab_image_attachment.xml`：全屏按住播放内嵌视频（活动级 `GestureDetector` + `dispatchTouchEvent`，不碰 PhotoView 触摸；`MediaPlayer(fd,offset,length)` 就地播放，letterbox 适配，HDR 静图保持）；左下角"实况"徽标（`bg_live_badge`）。字符串 en + zh-rCN。

**发布**：`:app:assembleDebug` 通过；`publishDebugUpdate` 发布 debug 更新 **202607010903** 到阿里云，日志 [update-20260701170131.md](debug-updates/update-20260701170131.md)。等用户在 OPPO/三星 真机验证"实况徽标是否出现""按住能否播放"。

## 2026-07-01 — 第 2 版：全屏三处修复 + 封面/详情动起来

**第 1 版 OPPO 反馈**：检测、徽标、长按播放均通；提三点：徽标简陋、放大后播放没跟着放大、播放中缩放出现双层。

**全屏修复（D8）**：徽标改同心圆图标 + 药丸（`ic_live_badge`/`bg_live_badge`）；长按前捕获 `photoView.displayRect`，视频铺到同一区域保留缩放；播放期间 `isZoomable=false` 锁定下层静图消除双层。

**封面 + 详情**：新增 `MotionPhotoCoverHelper`——内嵌视频抠到确定性缓存文件（mtime=源图 mtime 稳定 GIF key，512MB LRU），喂现有 `VideoCoverPreviewManager` 派生 GIF，管理器零改动（D7 实现修订）。`BaseThingsAdapter` 两条封面路径（缩略图 + 媒体背景）+ `ImageAttachmentAdapter` 详情各加 Motion Photo 分支：静图先显示、派生就绪异步换动图、回退主图；封面受 Cover Autoplay 门控，详情无条件播。`MotionPhotoDetector` 加 `peekCached`（主线程只查缓存）。

**发布**：`:app:assembleDebug` 通过；`publishDebugUpdate` 发布 **202607010926**，日志 [update-20260701172543.md](debug-updates/update-20260701172543.md)。等 OPPO/三星 验证封面/详情动效、全屏三处修复、性能。

**待办**：封面/详情的 LIVE 小徽标（暂缓）；三星/HEIC 实测；小米/Pixel 扩展（见 followups）。

### 第 2 版反馈修复（同日）
- **崩溃**（OPPO 上报 `You cannot start a load for a destroyed activity`）：`ImageAttachmentAdapter.loadDetailMotionGif` 的异步回调在 Activity 销毁后仍 `Glide.with(context)`。修复：新增 `isImageViewUsable`（`isAttachedToWindow` + Activity 未 destroyed/finishing），回调前与方法入口双重守卫。
- **发布日志被截断**：`publishDebugUpdate` 只嵌入文件第一个 `## ` 段（见 debug-update-channel/preferences.md），而我用了多个 `## `，导致封面/详情部分未发布。改正：完整用户可见改动全部写进第一个 `## ` 段。
- 重新发布 **202607010939**，日志 [update-20260701173759.md](debug-updates/update-20260701173759.md)。

### 全屏交互精修（同日，第三轮反馈）
三点反馈：徽标要放上方与 HDR 同排且放大、长按要振动、放大后播放有拉伸闪烁且松手丢失缩放。
- **缩放丢失/闪烁根因**：上一轮 `isZoomable=false` 会触发 PhotoView `update()` 把矩阵重置回适配大小。改为：不动 `isZoomable`，播放时把静图 `INVISIBLE`（矩阵不变→缩放保留、消双层），停止恢复 `VISIBLE`；`FrameLayout` 加黑底承接 letterbox；`onPrepared` 里先 `setTransform` 再置 `alpha=1` 消除视频层拉伸。
- **徽标**：从 per-tab 底部小徽标改为**活动级** `tv_live_badge`（`top|start`，与 HDR 同排、放大到 18dp 图标/14sp），`updateLiveBadge()` 按当前页+播放态+系统 UI 统一刷新。
- **触感**：长按识别即 `performHapticFeedback(LONG_PRESS)`。
- 发布 **202607010955**，日志 [update-20260701175443.md](debug-updates/update-20260701175443.md)。

### 详情附件网格媒体标识（同日）
详情图片/视频网格左下角加"实况 / HDR / GIF"标识（可叠加），右下角两个按钮加 3dp 边距留白、并与左下角标识 y 方向居中。
- 新增 `HdrImageDetector`（API 34+ 降采样解码取 `hasGainmap()`，按签名缓存）；GIF 看扩展名、实况用 `MotionPhotoDetector`。
- `ImageAttachmentAdapter.updateMediaBadges()` + `postBadgeUpdate`（回收守卫）；`attachment_image.xml` 加 `ll_media_badges`（40dp 高、`center_vertical`）与按钮 `marginEnd/marginBottom=3dp`；新增 `bg_media_badge`、`media_badge_gif` 字符串。
- 发布 **202607011151**，日志 [update-20260701195056.md](debug-updates/update-20260701195056.md)。（其间还有多轮全屏"实况"徽标 icon 微调：24→16→24 段虚线、铺满 viewport 去内边距等。）

### 播放中缩放跟随（同日）
反馈：自动播放时不能缩放；长按播放时另一只手缩放会作用到隐藏的静图、播完原图被改。根因是播放时把 PhotoView 设 `INVISIBLE`（触摸不到、且静图矩阵被单独改）。改为：**播放期间 PhotoView 保持可见可触**，`setOnMatrixChangeListener` 监听其显示矩阵、实时把视频 `setTransform` 到当前 `displayRect`；视频层在上但 TextureView 不消费触摸，缩放落到下层 PhotoView 再驱动视频跟随；停止移除监听、矩阵不重置故缩放保留。发布 **202607011225**，日志 [update-20260701202516.md](debug-updates/update-20260701202516.md)。
- HDR 检测新类 `HdrImageDetector` 的必要性已向用户解释（全屏那处是内联在解码回调上、且网格位图经裁切烘焙已剥离 gainmap，无法复用），用户默认保留（A 方案）。网格实况 icon 最终用 `android:alpha="0.76"`（仅此实例）。

### 全屏体验再打磨（同日，第四轮反馈）
五点：徽标加胶囊描边/放大图标/垂直居中/与 HDR 同高、打开即自动播一遍（带振动）、播放时徽标不隐藏、起播闪烁、放大后起播概率性上下拉伸（缩回原比例再播 100% 复现）。
- **起播闪烁/拉伸根因**：`onPrepared`（首帧未渲染）就置 `alpha=1`。改为 `OnInfoListener` 收到 `MEDIA_INFO_VIDEO_RENDERING_START` 时才 `revealMotionSurface()`；`onPrepared` 里先 `setDefaultBufferSize` + `applyTextureAspect`；180ms `postDelayed` 兜底。
- **自动播放**：`startMotionPlayback` 加 `loop`；自动 `loop=false` + `OnCompletionListener→stop`；长按 `loop=true`；触感移入统一给；`maybeAutoplayCurrentMotionPage()` 在检测完成/翻页触发。
- **徽标**：改活动级 `ll_live_badge`（LinearLayout：ImageView 20dp + TextView，`center_vertical`、`includeFontPadding=false`），`bg_live_badge` 加 `stroke`；`updateLiveBadge` 去掉播放时隐藏。
- 发布 **202607011025**，日志 [update-20260701182447.md](debug-updates/update-20260701182447.md)。

### 缩放顺滑化 + 消双图 + 全屏徽标对调（同日，第五轮反馈）
- **缩放卡顿**：逐帧 `new Matrix()` + `setTransform` 太重。改为 TextureView 的 **View 属性**（`scaleX/scaleY/translationX/translationY`，走 RenderThread）相对"基准区域"跟随；`setTransform` 只在建立基准时套一次。新增 `mMotionBaseRect` + `trackMotionZoom()`。
- **"上静下动"双图**：视频基准区域原取自静图 `displayRect`，自动播放触发早、静图未加载好时取到错 rect → 双图。改为 `setupMotionBaseTransform()`——基准 = **视频在整个 TextureView 内 fit-center**（只依赖视频与视图尺寸，无时序问题），再用 View 属性同步到静图当前 `displayRect`。移除旧 `applyMotionBaseTransform`。
- **全屏徽标左右对调**：`tv_hdr_badge`→`top|start`、`ll_live_badge`→`top|end`。
- 发布 **202607011254**，日志 [update-20260701205348.md](debug-updates/update-20260701205348.md)。

### 自动播放不被触摸打断 + HDR 徽标 ripple/镂空（同日，第六轮反馈）
两点反馈：自动播放时任何抬手（点击、缩放松手）都会停止播放，应只有长按按住播放才在松手时停；HDR 徽标可点却无 ripple。
- **自动播放不停**：`startMotionPlayback` 记 `mMotionHoldToPlay = loop`（长按=true、自动=false）；`dispatchTouchEvent` 仅在 `mMotionHoldToPlay` 时对 UP/CANCEL `stopMotionPlayback`，自动播放交由 `OnCompletionListener` 收尾。
- **HDR 徽标 ripple + 选中态镂空**：新增自定义 `views.HdrBadgeView`（`saveLayer`+`PorterDuff.CLEAR` 镂空，保留硬件加速）——**选中(HDR 生效)** 白底 + 镂空透明文字（透出照片）+ 偏黑 ripple；**未选中(强制 SDR)** 半透明深底 + 白描边 + 白字 + 偏白 ripple。ripple 由 `foreground` 承载、随选中态切换（`ripple_hdr_badge_on/off.xml`，带圆角 mask）。`ImageViewerActivity.updateHdrBadge` 改调 `setBoostOn`；`tv_hdr_badge` 由 TextView 换为 `HdrBadgeView`。
- 发布 **202607011326**，日志 [update-20260701212613.md](debug-updates/update-20260701212613.md)。

### 全屏顶部两徽标 y 方向居中对齐（同日，第七轮反馈）
反馈：实况胶囊与 HDR 卡片顶边对齐但高度不同（实况 padding 5dp+16dp 图标 ≈26dp、HDR padding 3dp+文字 ≈20dp），视觉未垂直居中。改为把两者包进同一横条 `fl_top_badges`（`match_parent`×`wrap_content`），HDR `start|center_vertical`、实况 `end|center_vertical`，与各自高度无关地 y 方向居中；原 `marginStart/End 16dp` 改为容器 `padding`，`marginTop 64dp` 与顶部 inset 统一加到容器（`applyTopInsetAsMargin` 由两次改为对容器一次）。发布 **202607011343**，日志 [update-20260701214308.md](debug-updates/update-20260701214308.md)。
