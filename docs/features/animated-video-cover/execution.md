# 动态视频封面 — 执行清单

顺序：先依赖与基础（编码器、生成器、存储），再接入加载分流与设置，最后性能与发布。详细决定见 [decisions.md](decisions.md)，概览见 [plan.md](plan.md)。

## Phase 0 — 依赖与基础

- [ ] `app/build.gradle` 加 `implementation 'com.squareup:gifencoder:0.10.1'`，确认纯 Java、APK 体积影响可忽略。
- [ ] 定义内部常量：时长（~3s）、帧率（25fps→4cs 帧延迟）、编码长边上限（720px）、LRU 上限（1GB）。

## Phase 1 — 派生预览生成器

- [ ] 新建 `helpers/VideoCoverPreviewGenerator`（暂名）：用 `MediaMetadataRetriever` 从 `videoFrameMs` 起按帧率/时长批量取帧 → 各帧高质量降采样到长边 720px → Square `gifencoder` 逐帧 `addImage`（每帧延迟 4cs）→ 写入 `cacheDir/video-cover-previews/<key>.gif`。
- [ ] 缓存 key：`typePathName` + 文件 mtime/大小 + `videoFrameMs` + 时长 + 帧率 + 分辨率；**不含裁切**。
- [ ] 后台单线程 / 小线程池队列，优先可见卡片；严格不阻塞 UI；失败记录、不重试风暴。

## Phase 2 — 存储与生命周期

- [ ] LRU 目录管理：写入前/后核算总量，超 1GB 按最近最少使用淘汰。
- [ ] 失效清理：改 `videoFrameMs` / 改 Media Source / 删附件 → 删旧 key 文件；记事永久删除 → 删其派生文件（接入既有"保存删除附件清理 per-source 设置"的路径）。

## Phase 3 — 加载分流

- [ ] `BaseThingsAdapter.loadThingCardImage` / `loadThingCardMediaBackground`：视频来源（`videoFrameMs != null`）且 `mAnimatedPlaybackEnabled`（即 Cover Autoplay 开）时查派生 GIF：命中 → 走 `loadAnimatedThingCardThumbnail`（`MediaCropTransformation` 逐帧裁切）；未命中 → 显示静态单帧并触发后台生成，就绪后 `notify` 刷新该卡。
- [ ] 确认首帧（`videoFrameMs`）与静态回退一致，"静态→动画"切换无跳变。

## Phase 4 — 设置

- [ ] `Def.Meta` 加 key（如 `KEY_AUTOPLAY_COVER_DYNAMIC`），默认 true；`FrequentSettings` 读写。
- [ ] `BaseThingsAdapter` 的 `mAnimatedPlaybackEnabled` 初值改为读该设置（widget 预览仍强制 false）。
- [ ] `SettingsActivity` 显示/外观组加一个 CheckBox 项（靠近深色模式）+ 中英文文案；改值后刷新可见列表。
- [ ] strings.xml（en + zh-rCN + zh-rHK + zh-rTW）加标题/副标题。

## Phase 5 — 性能

- [ ] 沿用 M1（Glide 屏外暂停）；加 M2：`OnScrollListener` 在 `DRAGGING/FLING` 暂停可见视频预览、`IDLE` 恢复。

## Phase 6 — 构建与发布

- [ ] `:app:assembleDebug` 通过。
- [ ] 发布 debug 更新到阿里云（`debug-updates/` 留发布说明）；不自动装物理设备，用户真机测。
- [ ] 用户按 [plan.md](plan.md) 验收标准实测（尤其塞满视频封面的滚动流畅度与画质，据此定 fps / 分辨率 / 是否需要更强的 M2）。

## 实现日志

### 2026-06-30 — Phase 0–5 实现、编译通过

- **Phase 0**：`app/build.gradle` 加 `com.squareup:gifencoder:0.10.1`。
- **Phase 1+2**：新建 `helpers/VideoCoverPreviewManager.kt`——`MediaMetadataRetriever`（API≥27 走 `getScaledFrameAtTime`、否则 `getFrameAtTime`+降采样）从 `videoFrameMs` 起取帧 → Square `gifencoder`（默认 median-cut + Floyd-Steinberg、每帧 4cs）→ 写 `cacheDir/video-cover-previews/<key>.gif`；单后台线程、同 key 去重、回调多路、1GB LRU。内部常量：3s / 25fps / 720px。
- **Phase 3**：`AttachmentHelper.isVideoCandidate`（按路径判定视频）；`BaseThingsAdapter` 加 `isCoverAutoplayEnabled()`，`loadThingCardImage` / `loadThingCardMediaBackground` 在 GIF 分支后插入视频分支（命中走 `loadAnimatedThingCardThumbnail` / `...MediaBackground`、未就绪后台生成 + 静态回退 + 就绪换装）；两处门控由 `mAnimatedPlaybackEnabled` 改为 `isCoverAutoplayEnabled()`；loadKey 对动图候选源加 `:anim` 后缀，使切换设置绕过同 key 短路、即时生效。
- **Phase 4**：`Def.Meta.KEY_AUTOPLAY_COVER_DYNAMIC`（默认 true）；`SettingsActivity` 加 CheckBox 项（字段/findView/样式/初始态/点击/`storeConfiguration` 读写 + `App.setJustNotifyAll` 刷新）；`activity_settings.xml` 在 UI 组 auto_link 下方加行；`settings_autoplay_cover_dynamic` 文案覆盖全部 13 个语言。
- **Phase 5**：`BaseThingsAdapter` 加 M2——`OnScrollListener` 在非 IDLE 暂停可见封面 `Animatable`、IDLE 恢复（GIF 与视频预览统一）。
- `:app:assembleDebug` 通过，APK 产出。

**v1 暂缓（见 [followups.md](followups.md)）**：派生预览的"改帧 / 改 source / 删附件即时清理"未接线，暂靠 1GB LRU + key 变孤儿自清；`VideoCoverPreviewManager.deletePreviewsForVideo` 已就绪待接。

**待办**：Phase 6 发布到阿里云需用户确认；真机验收（画质、滚动流畅度、各封面位置、设置开关、升级存量自愈）。
