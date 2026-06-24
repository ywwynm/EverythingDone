# 动图播放 — 执行清单

顺序:先做基础(判定 + Transformation),再按界面铺开,风险最高的滚动列表放在能单独验证之后。

## Phase 1 — 基础

- [ ] `AttachmentHelper`:加 `isAnimatedImageType(postfix)`(`{gif, webp}`)与 `isAnimatedImageCandidate(pathName)`(按扩展名小写判定)。
- [ ] 新建 `helpers/MediaCropTransformation.kt`:Glide `BitmapTransformation`,`transform` 调 `MediaCropBitmapRenderer.renderCrop(toTransform, targetW, targetH, crop)`;`equals/hashCode/updateDiskCacheKey` 纳入裁切 fingerprint。

## Phase 2 — 全屏预览 ImageViewerActivity

- [ ] `loadImage()` 开头:命中候选则走新 `loadAnimatedImage()`,`Glide.load().override().into(PhotoView)`,置 `mHasGainmap[pos]=false` 并刷新 HDR 状态;否则保留现有 `asBitmap` 路径。

## Phase 3 — 详情定制模式 ImageAttachmentAdapter

- [ ] `onBindViewHolder`:加 `animated = IMAGE && isAnimatedImageCandidate`;定制模式下 `animated` 走 `.transform(MediaCropTransformation(size, detailCrop))`(不加 `dontAnimate`),否则保留 `dontTransform/disallowHardwareConfig/dontAnimate` 烘焙。
- [ ] `onResourceReady` 烘焙块加 `&& !animated` 守卫;默认模式(`centerCrop`)不变,本就播放。

## Phase 4 — 卡片面 BaseThingsAdapter + widget 配置门控

- [ ] 加字段 `mAnimatedPlaybackEnabled = true` 与 `setAnimatedPlaybackEnabled()`。
- [ ] `loadThingCardImage`:同 key 短路之后、缓存命中之前,插入 GIF 早分支 → `loadAnimatedThingCardThumbnail()`(transform + 跳过 Bitmap 缓存)。
- [ ] `loadThingCardMediaBackground`:同样插入早分支 → `loadAnimatedThingCardMediaBackground()`;失败回退与静态路径一致(隐藏背景、恢复前景色)。媒体背景前景色取固定深色基准,不受动画影响。
- [ ] `BaseThingWidgetConfiguration`:`mThingCardAdapter` / `mFolderCardAdapter` / fallback 预览适配器均 `setAnimatedPlaybackEnabled(false)`。

## Phase 5 — 构建与发布

- [ ] `:app:assembleDebug` 通过。
- [ ] 发布 debug 更新到阿里云(`debug-updates/` 下留发布说明);不自动安装到物理设备,用户远程测试。
- [ ] 用户按 [plan.md](plan.md) 验收标准在真机确认(尤其塞满 GIF 的滚动列表流畅度)。

## 备注 / 日志

### 2026-06-24 — Phase 1-5 实现、编译、发布

一次性实现:

- **Phase 1**:`AttachmentHelper.isAnimatedImageType` / `isAnimatedImageCandidate`;新增 `helpers/MediaCropTransformation.kt`,复用 `MediaCropBitmapRenderer.renderCrop(Bitmap,…)` 逐帧裁切。
- **Phase 2**:`ImageViewerActivity.loadImage()` 命中候选走新 `loadAnimatedImage()`(Drawable 播放、置 `mHasGainmap=false`),否则保留 `asBitmap` HDR 路径。
- **Phase 3**:`ImageAttachmentAdapter` 定制模式下 `animated` 走 `.transform(MediaCropTransformation)`、不加 `dontAnimate`;`onResourceReady` 烘焙块加 `&& !animated` 守卫。默认模式 `centerCrop` 不变(本就播放)。
- **Phase 4**:`BaseThingsAdapter` 加 `mAnimatedPlaybackEnabled` + `setAnimatedPlaybackEnabled()`,`loadThingCardImage` / `loadThingCardMediaBackground` 在同 key 短路后插入 GIF 早分支(`loadAnimatedThingCardThumbnail` / `loadAnimatedThingCardMediaBackground`,跳过 Bitmap LruCache);`BaseThingWidgetConfiguration` 的 `mThingCardAdapter` / `mFolderCardAdapter` / fallback 预览适配器均 `setAnimatedPlaybackEnabled(false)`。
- **Phase 5**:`:app:assembleDebug` 通过(无相关告警)。发布 debug update `202606241041` 到阿里云,发布说明 `debug-updates/update-20260624184113.md`。未安装到物理设备,按远程测试工作流由用户测试。

待用户真机验收(尤其塞满 GIF 的滚动列表流畅度);若掉帧再评估 M2(fling 暂停)。

### 2026-06-24 — 第一轮测试反馈修复

- **问题 #1(已修,发布 `202606241206`)**:widget 配置页"选择记事"的列表 GIF 不播放。原因是上一版把 `setAnimatedPlaybackEnabled(false)` 误加在了 `mThingCardAdapter` / `mFolderCardAdapter`——这两个其实是选择记事的浏览列表,不是 widget 预览。修复:移除该处开关(选择列表恢复默认播放);真正的 widget 预览走 RemoteViews(`renderPreviewAppWidget`)本就静态,只有 `renderFallbackThingCardPreview` 的预览适配器仍保留 `false`。
- **问题 #2(确认与本功能无关,已转 follow-up)**:列表 widget 里图片显示比例不对、不遵循图片属性。该处走 `ThingsListWidgetService` → `createRemoteViewsForThingsListEntry` → `setAppearance` → `renderImageForWidgetSlot` → `RemoteThingCardMediaRenderer.renderThumbnail`(`BitmapFactory.decodeFile` 取首帧 + `renderCrop`),全程格式无关,且本次动画改动未触及。用户隔离确认**普通图片也同样不对**,所以是列表 widget 既有的通用比例问题,与 GIF / 本功能无关。按用户意见先不处理,已记入 [remote-thing-card-appearance/followups.md](../remote-thing-card-appearance/followups.md)。
