# 后续项 / 动态视频封面（Animated Video Cover）

## 派生 GIF 加载失败时回退静态视频帧（已修复 2026-06-30）

**修复**：视频派生 GIF 的两个动图加载分支（缩略图 / 媒体背景）加 Glide `.error()` 回退——加载失败时改加载该视频 `videoFrameMs` 静态帧（同 `MediaCropTransformation` 裁切），封面不再消失；同时 `onPreviewLoadFailed(file)` 按 key 一次性删除坏 GIF（`badPreviewKeys` 守卫）触发一次自愈式重生成，避免坏编码输出死循环。真·动图附件（GIF/WebP）自身加载失败仍保持隐藏策略。

当前 `getReadyPreview()` 只检查派生 GIF 文件存在且长度大于 0。若文件损坏、编码器生成了 Glide 无法解码的 GIF，或文件被系统/用户部分破坏，`loadAnimatedThingCardThumbnail()` / `loadAnimatedThingCardMediaBackground()` 的 `onLoadFailed` 会沿用 Animated Image 的失败策略：隐藏缩略图或媒体背景。对视频封面来说，这会把“应保持静态 Thing Card Video Frame 回退”的 UX 变成封面消失。

实现要点：视频预览分支加载派生 GIF 时应有专用失败回退：删除该坏 GIF（或标记无效），恢复同一 `pathName + videoFrameMs` 的静态视频帧加载，并按有限策略重新生成，避免每次命中坏文件后直接隐藏封面。Animated Image 源文件自身加载失败仍可保留现有隐藏策略。

## 保持尾部封面帧作为 GIF 第 0 帧（已修复 2026-06-30）

**修复**：生成不再为凑满时长前移 `startMs`；改为从 `videoFrameMs` 起截取 `min(时长, 剩余)`，第 0 帧恒等于用户选定帧，末尾不足就短循环（用户确认不必凑满 3 秒）。

设计要求 Thing Card Video Preview 以用户选定的 Thing Card Video Frame 作为循环起点，确保“静态帧 → 动画”切换无跳变。当前生成器在 `startMs + windowMs > durationMs` 时把 `startMs` 前移到 `durationMs - windowMs`，如果用户选在视频末尾附近，派生 GIF 的第 0 帧会早于静态封面帧，切换时出现倒跳。

实现要点：不要为了凑满固定时长而改动第 0 帧。可选方案：从选定帧开始生成到视频末尾，短于 3 秒则接受短循环；或从选定帧到末尾后绕回视频开头继续补足 3 秒。无论选哪种，第 0 帧必须是 `videoFrameMs` 对应帧。

## 修复 WorkManager 并发生成的 `.gif.tmp` 互删风险（已修复 2026-06-30）

**修复**：`generate()` 改为只删本 key 自己的 `out.absolutePath + ".tmp"`（同 key 由 WorkManager KEEP 保证唯一、不并发）；不再扫整目录。孤儿 tmp 改由 `enforceLruCap` 按年龄清理（`STALE_TMP_MS = 10min`，远大于一次生成耗时，绝不碰近期/正在写入的）。

当前 `VideoCoverPreviewManager.generate()` 开始时会删除同目录所有 `*.gif.tmp`。这只在旧的单线程生成模型下安全；迁到 WorkManager 后，不同视频/不同 key 的 Worker 可能并行运行，一个 Worker 可能删掉另一个 Worker 正在写的临时文件，导致 `rename-failed` 或无产物。

实现要点：只清理当前 key 自己的 `out.absolutePath + ".tmp"`，或对生成段加进程内串行锁；如果仍要清理历史残留，按文件年龄清理且不要触碰近期/正在写入的 tmp。修复后再用多个视频封面同时可见的场景验证。

## 清理失败/取消后的即时换装回调（已修复 2026-06-30）

**修复**：新增 `notifyGenerationFailed(key)`，Worker 在终态失败（视频已删 / 重试用尽）时调用，移除 `callbacks[key]` 而不触发（UI 保持静态回退）。key 经 inputData 传给 Worker，故视频删除后仍能定位回调清理。重试期间保留回调（待最终成功或失败）。残留小优化：同 view/loadKey 的回调去重未做——但终态必清、且 Glide 对同一 ImageView 的连续 `into` 会自动取消旧请求，冗余被吸收，影响轻微。

`requestPreview()` 会把进程内 `onReady` 回调挂到 `callbacks[key]`，但当前只有生成成功的 `notifyGenerated()` 会移除。若视频永久解码失败、文件丢失、低存储失败或 Worker 最终 `Result.failure()`，这些回调会一直持有 `ImageView` / holder 闭包直到进程结束；重复绑定还会叠加同 key 回调。

实现要点：增加失败通知路径（例如 Worker 最终失败时调用 `notifyGenerationFailed(key)`），移除对应 callbacks；同时考虑同一个 view/loadKey 的回调去重，避免生成成功时重复触发多次相同 Glide 加载。

## 生成前的 LRU/低存储兜底（已修复 2026-06-30）

**修复**：`generateBlocking()` 在生成前也执行一次 `enforceLruCap`（成功后仍再执行一次）；WorkRequest 加 `Constraints.setRequiresStorageNotLow(true)`，低存储时 WorkManager 延后而非反复失败，存储恢复后自动跑。

当前 1GB LRU 只在生成成功后执行。若目录已超限或设备存储接近满，新的 GIF 可能在腾空间之前就写入失败；失败会回退静态帧并可下次重试，但用户会看到预览长期不动。

实现要点：生成前先执行一次 LRU 清理，必要时保留一个估算余量；WorkRequest 可考虑加 `requiresStorageNotLow()`，避免低存储状态下反复失败。

## Eager 预热生成（v1 暂不做）

v1 用 Lazy（首次显示时后台生成）+ 持久缓存。Eager 预热——在卡片外观编辑器把视频确认为封面、或改了 Thing Card Video Frame 后，立即后台生成派生 GIF——可作为后续打磨，让用户设完封面回到列表即是动画、零"静态→动画"跳变。

实现要点：在外观编辑确认写库的路径上，对"视频来源 + Cover Autoplay 开"触发一次后台生成；与 Lazy 路共用同一套生成 + 缓存 key，天然幂等（已缓存则跳过）。

不进 v1 的理由：Lazy 已覆盖升级存量与缓存自愈，持久缓存把跳变压成一次性；Eager 只是把这唯一一次生成提前，属锦上添花。

## 派生预览的即时清理接线（v1 暂靠 LRU）

D7 规定"改 Thing Card Video Frame / 改 Media Source / 删附件 → 立即删旧 key 派生文件；记事永久删除 → 删其派生文件"。v1 实现里 `VideoCoverPreviewManager.deletePreviewsForVideo(context, videoPath)` 已就绪，但**尚未接到**附件删除、外观编辑改帧/改来源、记事永久删除这些路径上；当前靠 1GB LRU + key 变孤儿自然淘汰兜底，功能正确、只是孤儿文件会短暂占用。

实现要点：在"保存删除附件清理 per-source 设置"的既有路径（见 thing-card-media-target-geometry 2026-06-02 决策）旁，对被删/被换下的视频附件调用 `deletePreviewsForVideo`；记事永久删除路径同理遍历其视频附件。

## 生成并发度（v1 单线程）

v1 用单后台线程顺序生成，多个未缓存视频封面同时可见时按 FIFO 逐个生成。若真机实测首屏等待偏久，可改为 2–3 线程小池并优先可见项；当前单线程是为规避滚动期并发取帧/编码的发热与卡顿。

## 健壮后台生成：迁移到 WorkManager（已完成 2026-06-30）

已实现，见 decisions.md D10 与 `services.VideoCoverPreviewWorker`。生成迁到 WorkManager 唯一工作：持久化、扛进程被杀、进程重启恢复、可重试；按"视频+帧"定键、KEEP 去重，封面中途改动不取消、跑完留作备用，视频删除则优雅失败。即时换装走进程内 `notifyGenerated` 回调。

残留可选优化：即时换装目前只在生成时仍可见的同一卡片上发生；可由 Activity 观察 WorkManager（按 TAG）做去抖刷新，让进程被杀重启后回到列表也自动换装（当前靠下次绑定取用，已够用）。
