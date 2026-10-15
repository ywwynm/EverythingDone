# 会话记录 / 动态视频封面

## 2026-10-15 - 提交列表滑动与媒体生命周期修复

- 用户授权将已验证的列表滑动相关改动提交，标题与正文按英文在前、中文在后的格式说明拖动复用、持续加载播放、首帧回显、位图复用与回收，以及相应验证证据。
- 提交包含相关调试回归入口、测量脚本、功能文档和三份 debug 发布说明；复用已有构建、像素、生命周期、三机复杂列表和性能对照证据。本次未追加设备测试或生产实现改动。

## 2026-10-15 - 按已有性能证据整理结果

- 用户选择“先按已有结果整理”，魅族冷却补测改为暂缓，不再等待解锁后自动继续。报告、后续项与机器可读归档同步此范围决定，保留惯性呈现异常及工作内存增加的结论边界。
- 本次仅更新记录与归档状态，没有追加设备测试或生产代码改动。

## 2026-10-15 - 取消暂停后的复杂列表性能 A/B

- 按用户要求复核提交历史，建立同一隔离 APK 的旧暂停／当前策略开关；每轮 97 卡、80 媒体、48 动图，8 程包含普通触摸、fling、选择模式和真实长按边缘拖动，全程位移在同设备配对中一致。计时不截图，后台采窗口帧与 PSS，并以系统呈现记录核验。
- 三机各完成一对完整运行，并有反序的前六区间额外样本。未发现一致的明显滑动退化；整体 PSS 峰值约 +2～5%，三星和魅族惯性工作占用增加更明显，不能写成零开销。完整当前轮均覆盖 80 媒体、48 动图且加载圈采样为 0，退出后所追踪的运行 loader 为 0。
- 魅族窗口 fling 回调缺报，后续热状态为 3 的一段系统轨迹另有空洞。没有把缺报算作真实长卡顿，也没有据此宣布通过；冷却补测被 PIN 锁屏阻断，已请求用户在设备解锁。该未完成项见 followups。
- 修正调试工具的控制器字段名、选择入口改真实长按并松手；三星／OPPO 的 25 卡八程补充验证通过，包含选中数量为 1 的断言。正常包构建通过，六轮有效完整对照 APK SHA-256 相同；原应用数据库／设置从本轮开始到清理的哈希均不变，三机隔离包已卸载。
- 本轮无追加生产代码改动、无新发布、无 Git 提交。完整结果见 [性能报告](scroll-performance-comparison-2026-10-15.md)。

## 2026-10-15 - 普通滑动持续播放与 GIF 返回回显

- 用户指出普通 fling／持续触摸与前轮长按拖动不同。本轮先补真实滑动回归，核对 2016 年的请求暂停、视频动图 M2 和静态回显缓存历史，再逐项对照修复。
- 取消首页全局请求暂停与可见 GIF 的 M2 暂停；修复实际临时缓存／预取目标的离屏播放，保留临时缓存画面与正式回收清理。GIF 首帧与静态封面共用原有界缓存，短加载不闪圈，慢加载和静态失败回退保持正确。
- 三机 707 个普通滑动／fling 样本和 97 卡往返 1111 个移动样本通过，每机 80 媒体／48 GIF 显示播放，退出后运行 loader 为 0。首帧像素、文件失效、生命周期、坏 GIF 回退红绿回归通过。1496 项 JVM 测试 0 失败、1 跳过；原数据哈希不变，隔离包已清理。详细证据见 [普通滑动调查](regular-scroll-investigation-2026-10-15.md)。
- 已发布 debug `202610150359`，远端元数据／说明、下载包大小／SHA-256／CRC、正常包名及测试媒体排除均核验通过。未创建 Git 提交。

## 2026-10-15 - 深入定位媒体内存与回收

- 按用户新增要求先复现、采样、核对提交历史，再逐项修改。heapprofd 指向每帧裁切的大量 native 位图分配；弱引用及 RecyclerView owner 记录定位到回收池中仍运行的 GIF，而非所有历史 GIF 持续累积。
- 修复 MediaCropTransformation 的池复用，保持原裁切数学、尺寸、颜色、透明像素和签名；BaseThingsAdapter 正式回收时清理封面请求及绑定标记；两屏池试验通过后新增受默认总预算限制的 MediaGlideModule，low-RAM 和会压缩原资源缓存的设备保留默认。
- 120 帧复用红绿回归由 120 张输出降到 1 张；48 组透明／色域／尺寸像素用例通过，原 24 组修改前 SHA-256 一致。真实 25 卡回收生命周期断言从失败转为通过，普通晚交付、移除、重新附着、回收和同 key 重绑也通过。
- 最终三机各 97 卡往返共 1114 个实际移动采样通过，80 个媒体均显示、48 张 GIF 均有帧变化；退出后运行 loader 为 0。1496 项 JVM 测试 0 失败、1 跳过；三机原数据库／设置哈希不变，隔离包已卸载。完整测量与发布结果见 [内存归因](memory-investigation-2026-10-15.md)，命令及产物见 [checks](checks/README.md)。
- 已发布 debug `202610150326`，远端完整元数据、说明、下载 APK 大小／SHA-256／CRC、正常包名及测试媒体排除均核验通过。未创建 Git 提交。

## 2026-10-15 - 排查高视频 GIF 卡片滚动后占位空白

此首轮记录保留为历史；后续复杂列表持续拖动已复现并修复，见下方最新进展及 [复杂列表调查](complex-drag-investigation-2026-10-15.md)。

- 按用户“先复现再修复”的要求，在三台授权 Android 设备使用隔离包和正式生成的 GIF，覆盖仅视频、高比例、前景／背景封面、按住转滚动及 holder 复用。七组完整测试共 324 次手势，未复现持续空白；最后两组 120 次进一步以视频特征像素排除只显示底色，共 95 个 GIF 样本首次检查通过。
- 区分并记录测试工具误报：命名文件夹弹窗／键盘遮挡、布局尚未提交、裁切后颜色种类减少；OPPO 后续输入被系统拒绝，不计作应用缺陷。卓易通与用户原视频尚未复现。
- 保留 debug 专用探针和 [调查记录](blank-card-investigation-2026-10-15.md)，生产代码未改、未发布。三机原数据库／设置校验和一致，隔离包均已卸载。

## 2026-10-15 - 复杂列表持续拖动复现空白并修复

- 用户纠正验收条件：必须在复杂列表中跨全列表拖动，并在移动过程中检查其它卡片；补充对卓易通内存限制和 GIF 累积的怀疑。
- 三台授权 Android 设备复现同一根因：`ThingListOverlayDragController` 将初始源 View 引用当作永久的源身份，控件复用后仍把其它记事置为 alpha=0；此时 GIF 已加载并播放，普通清单也受影响。生产修复仅改为核对当前绑定稳定 ID，并在结束时恢复原 View 前核对身份。
- 最小用例旧实现失败、新实现通过；随后每机 97 张混合卡片／80 张媒体整列表往返，1128 次采样期间有实际位移，透明／像素疑点／请求失败样本均为零，48 张动图均观察到帧变化，源项占位与取消恢复正常。1496 项 JVM 测试 0 失败、1 跳过。
- 继续以低截图频率进行多程内存对照，不把 Android 结果推论为卓易通不存在内存问题。完整运行编号、内存趋势和工具限制见 [调查记录](complex-drag-investigation-2026-10-15.md)。
- 三机额外 3–4 程低截图频率对照通过，未观察到加载失败或 OOM；总 PSS 仍偏高，保留卓易通实测和内存来源分析后续项。三机原数据／设置哈希不变，隔离包均已卸载。
- 已发布 debug `202610150229`，远端完整元数据、说明、APK 大小／SHA-256／CRC、正常包名及无测试媒体资产均核验通过；未创建 Git 提交。

## 2026-07-05 - 特殊 4:3 普通卡视频封面不播放的诊断日志

- 用户反馈一个特殊 Thing/视频在横屏、普通宽度卡片、封面位于上方或下方、目标比例为 4:3 时，派生 GIF 已生成但卡片仍不播放。
- 本次先不改播放逻辑，只打开 `VideoCoverPreviewManager` 的文件日志，并在 `BaseThingsAdapter` 前景视频封面路径补充分支、尺寸、ready GIF、Glide 加载结果、实际 drawable 动画状态和滚动暂停/恢复日志，统一使用 `[DEBUG-video-cover-preview]` 前缀。
- 日志文件随 debug build 写入应用文件目录的 `debug_logs/video-cover-preview.log`；发布日志为 `docs/features/animated-video-cover/debug-updates/update-20260705211211.md`，已发布 debug update `202607051313`。

### 根因（据日志确诊）

- 关注 `content="棒棒哒"` 的 thingId=36（GIF `897a18b_…_v10.gif`）：同一个 GIF 文件在 504x378、577x577 请求尺寸下都是 `LOCAL → GifDrawable`（会动），唯独 577x432 尺寸下始终是 `RESOURCE_DISK_CACHE/MEMORY_CACHE → BitmapDrawable animatable=false`（静态、不动）。证明 **GIF 文件本身正常**，问题是 **577x432 这一个几何的 Glide 资源缓存被污染成了静态首帧位图**。
- 根因在缓存签名：`loadAnimatedThingCardThumbnail` 里 Glide 的 model 是 GIF 文件，但 `.signature()` 用的是 `ObjectKey(loadKey)`，而 `loadKey`（`getThingCardMediaSourceKey`/`getThingCardImageLoadKey`，`BaseThingsAdapter.kt:1929`/`:1940`）完全由**视频**的路径/大小/修改时间 + 尺寸 + 裁切 + `:anim` 组成，**不含 GIF 文件自身内容**。Glide 的 `ResourceCacheKey` 里 model=GIF 路径、signature=视频派生、transformation 含宽高——GIF 内容变化时这些都不变。
- 于是某次在 577x432 下把该 GIF 解成静态首帧 Bitmap 写进 RESOURCE 缓存后（很可能发生在编码器数轮迭代 / 自愈删后重生成期间该文件短暂只能被解成首帧的窗口；577x432 是普通宽度卡最常先被请求的几何），坏条目就永远留住：GIF 重生成同名同签名，Glide 不知道文件已变好；`MediaCropTransformation` 把宽高编进 transformation key，故每个几何是独立条目、只有 577x432 被污染。`onPreviewLoadFailed` 自愈只在加载失败时触发，而这次“加载成功”（成功拿到静态图），清不掉。
- 待用户决定是否实现修复，方案见 followups.md 对应条目。

### 修复落地（方案 A + replay 归一化）

- 用户选方案 A。新增 `getAnimatedThingCardMediaCacheSignature(loadKey, modelPath)`，`loadAnimatedThingCardThumbnail` / `loadAnimatedThingCardMediaBackground` 的 Glide `.signature()` 改用它，在视频派生 loadKey 上叠加实际 GIF 文件的 `length` + `lastModified`，使 GIF 内容变化能让 Glide 缓存失效、绕开被静态化的坏条目。
- 顺带修 GPT 曾指出的「一处 key 没加 `:anim`」：定位到 `replay*RenderRequest` 两处（媒体背景约 `BaseThingsAdapter.kt:2109`、缩略图约 `:2168`）重算 loadKey 时漏了 `:anim`。判定为真实缺陷但非用户可见 bug（下游同 key 短路兜底），后果是动图封面 replay 快路径退化为每次完整重绑。抽 `isAnimatedCoverActive` / `animatedCoverKeySuffix` helper，绑定路径与 replay 四处统一归一化。
- `:app:assembleDebug` 通过。旧的坏缓存条目靠 LRU 淘汰；如需立即恢复现有设备，可清一次 Glide 缓存。
- 已发布阿里云 debug 通道，更新码 `202607051426`，发布日志 `debug-updates/update-20260705222417.md`。

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
