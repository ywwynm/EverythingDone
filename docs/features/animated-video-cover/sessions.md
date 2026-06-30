# 会话记录 / 动态视频封面

## 2026-06-30 — 后台生成审查

围绕“派生 GIF 在后台生成是否可靠、失败是否影响下一次、是否影响 UI/UX”做静态审查，并运行 `:app:assembleDebug`，构建通过。结论：主流程能回退到静态 Thing Card Video Frame，WorkManager 失败后下次可重新入队；但当前实现仍有三个待修风险，已记入 [followups.md](followups.md)：不同 key Worker 并发时会互删 `.gif.tmp`、永久失败时进程内回调不清理、以及生成前未预清理 LRU/低存储兜底不足。

## 2026-06-30 — 设计评审（grill-with-docs）

用 grill-with-docs 走完整设计树并建档。确定：

- 派生 GIF 走既有动图管线（方案 A，否决"卡片内播真实视频"与"运行时帧循环"）。
- 编码器 Square `gifencoder`（否决 gifski/ffmpeg，附带 ffmpeg-kit 退役 + 编解码专利风险调研）。
- 预览以 Thing Card Video Frame 为循环起点，向后 ~3s、25fps、长边 720px、循环。
- 一个默认开的 **Cover Autoplay** 统一开关，覆盖全部应用内 Thing Card 封面面、排除详情/全屏。
- Lazy 首次显示生成 + `cacheDir` 1GB LRU 持久缓存；缓存 key 不含裁切。
- 参数内部化、不暴露用户；GIF 无硬件解码，性能沿用 M1 + 主动 M2。

产出：`CONTEXT.md` 加 **Thing Card Video Preview** / **Cover Autoplay** 术语与关系、修订 ADR-0007 口径；新增 [ADR-0012](../../adr/0012-thing-card-video-preview-derived-animated-image.md)；本目录 decisions / plan / execution / followups / README / sessions 建档。

## 2026-06-30 — Phase 0–5 实现、编译通过

按 [execution.md](execution.md) 落地 Phase 0–5：新增 `helpers/VideoCoverPreviewManager.kt`（取帧 + Square gifencoder + cacheDir 1GB LRU + 单线程后台 + 多路回调）与 `AttachmentHelper.isVideoCandidate`；`BaseThingsAdapter` 接入视频分支、统一 `isCoverAutoplayEnabled()` 门控、loadKey 加 `:anim` 后缀、M2 滚动暂停；`Def` / `SettingsActivity` / `activity_settings.xml` 加默认开的 Cover Autoplay 开关；`settings_autoplay_cover_dynamic` 覆盖 13 语言。`:app:assembleDebug` 通过。

v1 暂缓：即时清理接线、Eager 预热、生成并发度（见 followups.md）。待用户确认后发布阿里云、真机验收。

## 2026-06-30 — 真机调试：缓存位置 + 生成卡顿

发布后真机排查两轮：

1. **看不到生成文件**：原存内部 `cacheDir`（`/data/data/...`，未 root 不可浏览）。改存**外部缓存** `externalCacheDir/video-cover-previews/`（`Android/data/<pkg>/cache/`，可浏览）；加 `DebugFileLogger` 诊断日志到 `files/debug_logs/video-cover-preview.log`（记录 bind 判定 + 生成过程）。同步更新 D7。
2. **生成卡在中途**：日志确认分支命中、生成启动（`frameCount=75`）但无 `GEN done`、只余 `.gif.tmp`——**75 帧 @ 720px 用纯 Java Square 编码器太慢**（逐帧 median-cut + Floyd-Steinberg）。降参到 **480px / 12.5fps / 2.5s ≈ 31 帧**，加**分阶段计时**（decodeMs / encodeMs / totalMs + 每 10 帧进度）以定位取帧 vs 编码瓶颈，VERSION→2、清残留 `.tmp`。待用户回传计时数据，再定最终帧率/分辨率、是否小线程池并行、或画质要求高时改原生 gifski。

## 2026-06-30 — 编码器换原生 NDK + 生成迁 WorkManager

- **编码器（D2 更新）**：逐帧计时确诊纯 Java Square **~12s/帧**（编码瓶颈，取帧仅 ~90ms）不可用 → 换原生 `io.github.waynejo:androidndkgif:1.0.1`（Maven Central，AAR 含 arm64-v8a 等四 ABI，直接吃 Bitmap）。档位 `ENCODING_TYPE_STABLE_HIGH_MEMORY`；用户确认通畅后参数回到 720px / 25fps / 2.5s（≈62 帧）。取帧弃用在部分机型卡死的 `getScaledFrameAtTime`，改 `getFrameAtTime` + 自降采样。
- **健壮后台（D10）**：生成从内存 Executor 迁到 WorkManager（`services.VideoCoverPreviewWorker`）：持久化、扛进程被杀、进程重启恢复、可重试；唯一 work 按视频+帧定键、KEEP 去重，封面中途改动不取消、跑完备用，视频删除优雅失败。即时换装走进程内 `notifyGenerated`。VERSION→7。

## 2026-06-30 — 后台可靠性修复（P1/P2/P3，据代码审查）

- **P1 并发互删 tmp**：`generate()` 不再扫整目录删 `.gif.tmp`，只删本 key 自己的；孤儿 tmp 由 `enforceLruCap` 按年龄（>10min）清理。修复 WorkManager 并行 Worker 互删在写 tmp 导致的 `rename-failed`/反复重试。
- **P2 回调泄漏**：新增 `notifyGenerationFailed(key)`，Worker 终态失败时清 `callbacks[key]`（不触发、UI 静态回退）；key 经 inputData 传入以便视频删除后仍能定位。
- **P3 LRU/低存储**：生成前也清一次 LRU；WorkRequest 加 `requiresStorageNotLow()`，低存储延后而非反复失败。
- 编译通过。详见 followups.md 对应三条的"已修复"说明。

## 2026-06-30 — 第二轮复查：失败回退与尾部帧

重新按当前工作区复查后台生成与 UI 失败路径，并运行 `:app:assembleDebug`，构建通过。上一轮的 tmp 并发互删、终态失败回调清理、生成前 LRU/低存储约束已在代码中落地；新增两个待修风险：已生成 GIF 文件若存在但 Glide 加载失败，当前动图加载分支会隐藏封面而不是回退到静态视频帧；视频封面帧若选在末尾附近，生成器会把 GIF 起点前移以凑满 3 秒，破坏“静态帧 → 动画”无跳变。已记入 [followups.md](followups.md)。

## 2026-06-30 — 修复坏 GIF 隐藏封面 + 尾帧跳变

- **坏 GIF 回退**：`loadAnimatedThingCardThumbnail` / `loadAnimatedThingCardMediaBackground` 加视频回退参数 + Glide `.error()`——视频派生 GIF 加载失败时改显示该视频 `videoFrameMs` 静态帧（同裁切），封面不再消失；`VideoCoverPreviewManager.onPreviewLoadFailed` 按 key 一次性删坏 GIF 自愈（`badPreviewKeys` 防死循环）。真·动图附件失败仍隐藏。
- **尾帧跳变**：`generate()` 去掉 `startMs` 前移，改 `windowMs = min(DURATION_MS, durationMs - startMs)`，第 0 帧恒为选定帧，末尾不足短循环。VERSION→10 重生成。
- 编译通过。followups 两条标记已修复。
