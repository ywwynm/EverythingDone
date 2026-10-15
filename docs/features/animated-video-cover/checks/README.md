# 复杂列表持续拖动回归

## 普通滑动与离屏回显

`run_identity.py <授权序列号> <名称> scenario=complex gesture=scroll items=36 cold_load=true memory_audit=true memory_gc=false lifecycle_assert=true` 检查真实普通手指滑动、手指按住、fling 及离屏返回；输出 `regular-scroll-samples.jsonl`、`regular-returns.json`、`regular-summary.json`。`summarize_regular.py <名称> ...` 汇总暂停、帧变化及返回加载圈。帧变化按同一阶段和同一 Drawable 统计，不跨 IDLE 推断。

`gesture=both items=96 passes=2` 先做普通滑动，再执行原全列表长按往返回归。`scenario=gif-lifecycle` 补充了清空 Glide 缓存后的同几何首帧同步回显、像素一致性、恢复动画及文件变更失效检查。

仅可在用户明确授权的设备上运行。使用 `selection-batch-actions/checks/run_identity.py` 的独立包与原应用校验和保护；探针拒绝在正式包名中运行。

## 构建与执行

在仓库根目录，按 `.agents/rules/` 中的工具路径调用：

```powershell
& C:\Users\ywwynm\miniconda3\envs\everythingdone\python.exe docs/features/animated-video-cover/checks/prepare_complex_media.py
& C:\Users\ywwynm\miniconda3\envs\everythingdone\python.exe docs/features/selection-batch-actions/checks/prepare_clone.py
& .\gradlew.bat :app:assembleDebug -I docs/features/selection-batch-actions/checks/clone.init.gradle -I docs/features/animated-video-cover/checks/complex.init.gradle
& C:\Users\ywwynm\miniconda3\envs\everythingdone\python.exe docs/features/selection-batch-actions/checks/run_identity.py <已授权序列号> <唯一名称> scenario=complex items=96 passes=2 cold_load=true
```

- `items=24 passes=1` 为最小回归，旧实现已在三星／OPPO 按“非源卡片透明”断言失败。
- 默认 96 张混合卡片加拖动源，含大量长清单、静态图、独立视频 GIF、直接 GIF 与高比例整行卡。`passes=2` 一次不松手从顶部拖到底，再拖回顶部；最多 6 程。
- 采样发生于真实长按拖动和边缘自动滚动期间，记录截图前后位移及区域交集，不以停滚后检查替代拖动过程。排除浮动卡片遮挡、适应 MOVING 模式正常淡化；像素疑点保留图片人工复核。
- 断言非源项不透明、原源项保持占位、取消后源项恢复、全部卡片经过视口、所有媒体至少在拖动中显示一次，并检查实际 Glide 请求树的 FAILED 状态。GIF 类型与帧变化从原始记录进一步汇总。
- `screenshot_every=12 passes=4` 为低截图频率内存对照，仍采样可见控件及请求状态，但不要求每个媒体都被稀疏截图覆盖。它不能替代逐次画面验证，也不是无工具开销的生产内存基准。
- `summarize_complex.py <名称> ...` 输出机器可读汇总，包括每程内存初值／峰值／末值、PSS 分类、GIF 帧变化、失败日志与原应用校验和。

产物位于 `tmp/selection-transition-20261013/<名称>/`。发布前必须去掉两个 init 脚本，以正常包名构建／发布；测试媒体只由本目录的 init 脚本加入，不进入正常 APK。完成后卸载隔离包。详细结果见 [调查记录](../complex-drag-investigation-2026-10-15.md)。

## 滚动暂停策略的配对性能测试

`run_scroll_performance.py <授权序列号> <名称> legacy|continuous [--no-trace]` 使用同一隔离 APK 的两种策略；构建仍使用上文两个 init 脚本。每次新进程、相同 97 张卡片、清空 Glide 磁盘缓存。`legacy` 只在调试入口注册旧暂停监听，正常应用不会启用。

`ScrollPerformanceAudit` 完整覆盖普通手指往返、惯性往返、选择模式手指向下／惯性返回及长按卡片往返。记录后台 FrameMetrics、每 1.5 秒的后台内存采样、约 192 毫秒间隔的可见 GIF 状态；计时阶段不截图。默认额外采轻量 FrameTimeline，`--no-trace` 仍保留 FrameMetrics，用于采集连接不稳定的设备。

`analyze_scroll_performance.py <名称> ...` 只统计实际输入／惯性区间，区分 fling 起手与松手后时段，输出窗口帧耗时、deadline 超限、FrameTimeline 应用迟帧、各程内存与覆盖。全程 PSS 峰值排除截图与阶段外资源快照；原始全程峰值另列。不得用 `1000 / 帧耗时` 代替屏幕帧率。

运行数据位于 `tmp/selection-transition-20261013/<名称>/perf-*`，环境、轨迹和汇总位于 `tmp/media-memory-20261015/<名称>/`；完整原始证据留在临时目录，精简结论归档到功能文档。失联或调试入口错误须列为无效／不完整轮次，不写成通过。

`--fling` 是仅保留惯性往返的短时复核入口，附带实验性的 SurfaceFlinger latency 记录；厂商可能不返回逐帧数据，不能把空结果当作通过。魅族冷却后的首次短时复核因锁屏未能进入首页，现按用户“先按已有结果整理”的要求暂缓。后续恢复测试时保持设备解锁，冷却时不要主动熄屏。`archive_scroll_performance.py` 从已分析结果生成精简 JSON，并断言完整 A/B 的 APK 摘要一致。

## 内存与生命周期

- `scenario=crop-reuse`：实际 Android BitmapTransformation／BitmapResource 的 120 帧复用断言，以及透明／宽色域／RGB565／RGBA_F16 等 48 组像素比较。首次旧实现的像素 SHA-256 另作不变性基线。
- `scenario=gif-lifecycle`：正式卡片缩略图与媒体背景，检查晚交付、移除、重新附着、同一 drawable 继续播放、回收清理及同 key 重绑后重新播放。
- `scenario=complex items=24 passes=1 screenshot_every=12 memory_audit=true lifecycle_assert=true`：真正触发 RecyclerView 回收池的最小生命周期红绿回归。记录 pool/cache 的 holder 身份，并在 Activity 退出后要求运行的 GIF loader 为 0。
- `memory_audit=true` 使用弱引用追踪 loader／decoder、帧位图、池计数；结束后依次记录正常退出、GC、Glide.clearMemory，最后两步只是诊断，不属于产品策略。额外保存 gfxinfo 以区分系统可回收纹理与显示缓冲。
- `capture_heap.py <序列号> <名称>` 只对隔离包采 heapprofd；`analyze_heap.py <名称>` 汇总 native 分配路径与拖动区间。工具为 1 MiB 统计采样、5 秒快照，有工具开销与边界量化误差，不能将累计分配等同 PSS。
- `pool_multiplier=2` 仅为隔离探针中的单变量容量试验；除非后续实测与决定明确采纳，不代表产品修改缓存上限。
