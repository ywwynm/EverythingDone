# 分阶段实现 / 验证清单 · Motion Photo 播放

按 tracer bullet：先打通"导入→检测→卡片封面动"这条最薄的端到端，再逐界面补齐。术语见 `CONTEXT.md`，决策见 [decisions.md](decisions.md)。

状态：☐ 未做 / ☑ 完成 / ◐ 进行中

## 阶段 0 — 导入保真与扩展名
- ☑ `FileUtil.getPostfixFromMimeType`：`image/heic(-sequence)`→`.heic`、`image/heif(-sequence)`→`.heif`。
- ☑ `AttachmentHelper.isImageFile` 允许列表加入 `heic`、`heif`；新增 `isMotionPhotoCandidate`（jpg/jpeg/heic/heif 前置过滤）。
- ☑ 确认 `copyUriToFile` / `copyFile` 为裸字节流，不重编码（读码确认，无需改）。
- 验证：导入一张 HEIC，落盘为 `.heic`；字节数与源一致。（待真机）

## 阶段 1 — 检测器 MotionPhotoDetector
- ☑ 新增 `MotionPhotoInfo`（`isMotionPhoto`、`videoOffset`、`videoLength`）。
- ☑ 新增 `MotionPhotoDetector.detect(path)`：全文扫 `ftyp` 盒子头 → 每个候选 `boxStart = ftyp位置 - 4` → 用 `MediaMetadataRetriever(fd, offset, length)` 校验 `HAS_VIDEO=="yes"` → 取第一个通过者。HEIC 容器自身 ftyp 因 HAS_VIDEO=no 被过滤。结果按"路径+大小+修改时间"缓存。
- ☑ 新增 `extractEmbeddedVideo(src, offset, length, dst)`：裸字节抠出内嵌 MP4，供派生 GIF 复用。
- 说明：v1 用"ftyp 扫描 + 校验"作为唯一定位手段（对 OPPO/三星足够且厂商无关），XMP/三星标记的显式解析留作可选增强（followups）。
- 验证：对 OPPO/三星样张返回正确 `videoOffset/Length`；对普通图返回 `isMotionPhoto=false`。（待真机）

## 阶段 2 — 卡片封面派生 GIF ☑（第 2 版已实现，待真机验证）
- ☑ 新增 `MotionPhotoCoverHelper`：检测 → 把内嵌 MP4 抠到确定性缓存文件（mtime=源图 mtime，稳定 GIF 缓存 key；512MB LRU）→ 交现有 `VideoCoverPreviewManager` 派生 GIF。`VideoCoverPreviewManager` 零改动（见 decisions D7 修订）。
- ☑ `BaseThingsAdapter` 两条封面路径 `loadThingCardImage`（缩略图）与 `loadThingCardMediaBackground`（媒体背景）各加 Motion Photo 分支：命中 `getReadyGif` 走 `loadAnimated…`；未就绪 `requestGif` 异步、回退静态主图、就绪且同卡则换上（fallback=主图）。`animatedCover` 的 `:anim` 后缀只对"已确认的 Motion Photo"生效（`peekCached`），避免普通图切开关时抖动。
- ☑ 受 **Cover Autoplay** 门控。
- 验证（真机）：OPPO/三星样张在首页卡片封面循环播放；关 Cover Autoplay 停在主图。

## 阶段 3 — 详情附件列表 ☑（第 2 版已实现，待真机验证）
- ☑ `ImageAttachmentAdapter`：`IMAGE` 且为 Motion Photo（非 GIF/WebP）时，先显示静态主图，`requestGif` 就绪后 `loadDetailMotionGif` 换成派生 GIF（Drawable + `MediaCropTransformation` 逐帧裁切；定制模式套用详情裁切，否则 centerCrop）。回收守卫用 `bindingAdapterPosition` + item 匹配。无条件自动播放（不受 Cover Autoplay 影响）。
- 验证：详情页缩略图自动播放；裁切定制模式下也动且裁切正确。

## 阶段 4 — 全屏按住播放 ☑（第 1 版已实现，待真机验证）
- ☑ `ImageViewerActivity`：`IMAGE` 且扩展名候选 → 后台 `MotionPhotoDetector.detect`；命中保留现有 HDR 静图路径不变。
- ☑ tab 布局加 `TextureView`（`tv_motion_surface`，默认 alpha 0）；**活动级 `GestureDetector` + `dispatchTouchEvent`** 驱动：长按当前动态照片页 → `MediaPlayer.setDataSource(fd, offset, length)` + `isLooping` 就地播放并置 texture alpha 1；松手（ACTION_UP/CANCEL）`stop` 回静图。**不覆盖 PhotoView 的 OnTouchListener，缩放不受影响。**
- ☑ `applyTextureAspect` 让视频按宽高比 letterbox 居中；从视频开头循环。
- ☑ 生命周期：`onPause`/`onDestroy`/翻页 均停止并释放；无 temp 文件产生。
- 验证：全屏默认 HDR 静图；按住播放动态、松手回 HDR 静图。（待真机）

## 阶段 5 — LIVE 徽标
- ☑ 全屏：左下角"实况"徽标——同心圆图标（`ic_live_badge`）+ 半透明药丸（`bg_live_badge`），检测命中后显示、播放中隐藏。（第 1 版粗糙的两字版已按反馈改良）
- ☐ 卡片封面 / 详情的 LIVE 徽标：暂缓——封面/详情本就自动播放，动画本身已示意"活的"；如需再加。
- 验证：全屏徽标出现且不遮挡关键内容；普通图无徽标。（待真机）

## 阶段 6 — 编译、发布、真机实测
- ☐ `:app:assembleDebug` 通过。
- ☐ 写 `debug-updates/update-<时间戳>.md` 发布日志，调用 gradle 发布任务传入。
- ☐ 发布 debug 到阿里云渠道，由用户在 OPPO + 三星真机按 plan.md 验收标准实测。
- ☐ 记录实测反馈，必要时补快滑暂停（M2）、HEIC box 精解等。

## 回归关注点
- 普通图片 / GIF / 视频封面 / HDR 全屏行为不变（Motion Photo 分支只在"检测为 Motion Photo"时进入）。
- 不请求任何新权限；导入不重编码。
- API 26/27 上 HEIC 无法解码属既有限制，不崩溃即可。
