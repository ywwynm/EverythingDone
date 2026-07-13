# Android 卡顿诊断与 CPU 侧优化研究报告 —— 水面动画自定义 View

日期：2026-07-12。适用对象：DialogFragment 内 ~280×420dp 自定义 View，`postInvalidateOnAnimation` 驱动，全部工作在 UI 线程 `onDraw` 内完成（音频特征队列 → 波动方程物理 → 二维场采样 → 逐顶点颜色 → 网格装配 → `drawVertices`/Path/AGSL 绘制），已知每帧多次 `DoubleArray` 分配与每 band 一次 `copyPixelsFromBuffer`。目标设备为 2024–2026 高端机（1280×2856、支持 120Hz），minSdk 26。项目约束：**不可用 adb**，只能靠应用内插桩与设备端工具。

---

## Q1 测量方法：把时间归因到 onDraw / RenderThread / GPU

### 1.1 Perfetto FrameTimeline（最权威的归因来源）

SurfaceFlinger 内置 FrameTimeline 模块，会为每个上屏帧生成两条轨道：**Expected Timeline**（系统给该帧的时间窗）与 **Actual Timeline**（从 `Choreographer#doFrame` 开始、到 GPU 完成并提交 SurfaceFlinger 的真实用时）。Actual 超出 Expected 即判卡顿，并给出明确的 jank type：

- App 侧：`AppDeadlineMissed`（应用侧超时——UI 线程或 RenderThread 太慢）、`BufferStuffing`（应用提交过快、队列积压，帧至少晚一个 vsync 呈现，画面可能仍平滑但延迟增大）；
- 系统侧：`SurfaceFlingerCpuDeadlineMissed`、`SurfaceFlingerGpuDeadlineMissed`、`DisplayHAL`、`PredictionError`；
- 其他：`Unknown` / `None`。

配套 SQL 表：`expected_frame_timeline_slice` 与 `actual_frame_timeline_slice`（关键列 `jank_type`、`present_type`、`on_time_finish`、`layer_name`），可在 ui.perfetto.dev 里直接查询，把丢帧按类型统计。
来源：https://perfetto.dev/docs/data-sources/frametimeline

在同一 trace 中的归因方法（androidperformance.com 的 MainThread/RenderThread 深潜文对照 Perfetto 切片讲得最细）：

- UI 线程 `Choreographer#doFrame` 长 → onDraw/animation/traversal 超支（本工作负载的默认嫌疑）；
- RenderThread `DrawFrame` 长：其中 `syncFrameState` 段（含**纹理上传**）阻塞 UI 线程的 sync；`dequeueBuffer` 等待长 → GPU 供不上 buffer（GPU-bound 或 buffer stuffing）；`eglSwapBuffers`/`queueBuffer` 尾部长 → GPU/driver 排队。
来源：https://androidperformance.com/en/2025/08/02/Android-Perfetto-07-MainThread-And-RenderThread/ 、https://developer.android.com/topic/performance/tracing/navigate-report

### 1.2 FrameMetrics API（应用内、逐帧、无需任何工具）

`Window.addOnFrameMetricsAvailableListener`（API 24+，回调在独立 Handler 线程，几乎零开销）逐帧给出各流水线阶段纳秒时长：

| 字段 | 阶段 | 判读 |
|---|---|---|
| `UNKNOWN_DELAY_DURATION` | vsync 后 UI 线程迟迟未开始 | 大 → UI 线程被别的消息占着（调度/消息队列问题） |
| `INPUT_HANDLING_DURATION` / `ANIMATION_DURATION` | 输入/动画回调 | 传感器→`setTilt`、`postInvalidateOnAnimation` 回调算在这里 |
| `LAYOUT_MEASURE_DURATION` | measure/layout | 本例应≈0 |
| `DRAW_DURATION` | **View.onDraw 录制 display list** | 本工作负载的主战场 |
| `SYNC_DURATION` | UI↔RenderThread 同步（含把新 bitmap 内容排队上传） | 大 → 纹理上传/树同步重 |
| `COMMAND_ISSUE_DURATION` | RenderThread 生成并发出 GPU 命令 | 大 → 绘制命令/上传重 |
| `SWAP_BUFFERS_DURATION` | swap/排队 | 大 → GPU-bound 或队列满 |
| `GPU_DURATION`、`DEADLINE` | GPU 真实用时 / 本帧期限 | **API 31+** 才有 |
| `TOTAL_DURATION`、`INTENDED_VSYNC_TIMESTAMP` vs `VSYNC_TIMESTAMP` | 总时长 / 调度延迟 | 两个 vsync 戳差大 → UI 线程没能及时响应 vsync |

判读规则：DRAW 大而 COMMAND_ISSUE/SWAP 小 → CPU(UI 线程)-bound；COMMAND_ISSUE 大 → RenderThread-bound；GPU_DURATION/SWAP 大 → GPU-bound；各段都不大但 TOTAL 大、UNKNOWN_DELAY 大 → 调度-bound。
来源：https://developer.android.com/reference/android/view/FrameMetrics 、https://medium.com/@froger_mcs/framemetrics-realtime-app-smoothness-tracking-3d8550413c1c

### 1.3 JankStats（FrameMetrics 的官方封装，适合发给真实用户的 debug 包）

`androidx.metrics:metrics-performance`：API 24+ 走 `OnFrameMetricsAvailableListener`（专用线程），默认启发式为「帧耗时 > 期望帧时长 × 2 判 jank」（`jankHeuristicMultiplier` 可调）；`JankStats.createAndTrack(window, listener)` **按 Window 统计——对话框要传 Dialog 自己的 window**（`dialog.window`），传 Activity 的 window 会漏掉对话框那条渲染流水线。`FrameData` 含 `frameDurationUiNanos`、API 31 上还有 `frameOverrunNanos`（超过 deadline 多少）。FrameData 对象复用，回调内必须先拷贝。
来源：https://developer.android.com/topic/performance/jankstats 、https://medium.com/androiddevelopers/jankstats-goes-alpha-8aff942255d5

### 1.4 Choreographer 丢帧日志

`Choreographer` 只有在主线程一次性落后 ≥30 帧（隐藏常量 `SKIPPED_FRAME_WARNING_LIMIT`，属 hidden API）才打 `Skipped N frames! The application may be doing too much work on its main thread.`——它只能抓「灾难级」阻塞，对 1–2 帧的微卡顿完全沉默，不能作为平滑度指标；自行用 `Choreographer.FrameCallback` 记录 `frameTimeNanos` 差值直方图更有用。
来源：https://www.techyourchance.com/android-application-skips-frames/ 、https://developer.android.com/reference/android/view/Choreographer

### 1.5 GPU 侧（Mali/Adreno）2026 现状

- **AGI（Android GPU Inspector）**：支持 Adreno/Mali/PowerVR 的 GPU counters 与 render stage 追踪，但 `google/agi-dev-releases` 仓库已于 2024-08 归档，项目 README 转而推荐新的 **Android Performance Analyzer (APA)** 作系统性剖析；AGI 本体仍可用但更新缓慢，且**需要 adb/桌面端**，不符合本项目约束。来源：https://developer.android.com/agi 、https://github.com/google/agi 、https://developer.android.com/agi/sys-trace/counters
- **Perfetto GPU counters / render stages**：数据源存在（AGI 的配置即 Perfetto config），部分新设备的驱动自带 producer；设备端 System Tracing 应用的类别里若出现 GPU 相关项即可勾选。来源：https://developer.android.com/agi/sys-trace/counters
- 无 adb 场景下务实做法：GPU 是否为瓶颈用 **FrameMetrics.GPU_DURATION（API 31+）+ FrameTimeline jank_type** 判断即可；厂商工具（Arm Streamline/Performance Studio、Snapdragon Profiler）都要求 USB 连接，只能作为开发者自己设备上的补充。

### 1.6 CPU-bound / GPU-bound / 调度-bound（含热与小核）的区分

- CPU-bound（本例先验概率最大）：`DRAW_DURATION`+`ANIMATION_DURATION` 占满预算；trace 中 `doFrame` 长且线程处于 Running。
- GPU-bound：`dequeueBuffer` 等待、`GPU_DURATION` 大、jank_type 为 SF GPU deadline missed。
- 调度-bound：trace 的 sched 轨道显示 UI 线程长时间 **Runnable（可运行但没核跑）**，或被放到小核低频运行——Perfetto 的 CPU 轨道直接显示线程跑在哪个核、当时频率多少。Android 把 top-app 放专用 cpuset、可选 `sys.use_fifo_ui` 将 UI/RenderThread 提为 SCHED_FIFO，但 RT 调度在 big.LITTLE 上也可能把长任务迁到低频小核。来源：https://androidperformance.com/en/2022/01/21/android-systrace-cpu-state-runnable/ 、https://source.android.com/docs/core/tests/debug/jank_jitter 、https://lwn.net/Articles/809545/ 、https://lwn.net/Articles/706374/
- 热节流：应用内可用 `PowerManager.addThermalStatusListener`（API 29+）与 `getThermalHeadroom(forecastSeconds)`（API 30+，返回 1.0 即将 SEVERE 节流）随帧记录，区分「代码慢」与「降频了」。来源：https://developer.android.com/games/optimize/adpf/thermal

---

## Q2 UI 线程调度现实

### 2.1 对话框窗口的渲染优先级

Dialog 是**独立 Window**：有自己的 Surface、自己的 `ViewRootImpl`、自己的 FrameMetrics/JankStats 计量；但 measure/layout/draw 仍跑在**同一条 UI 线程**上，GPU 命令仍由**每进程唯一的 RenderThread** 发出——所以同进程内多一个窗口＝同一线程每 vsync 多做一次 traversal + RenderThread 多一次 DrawFrame，没有独立的优先级或独立预算；背后 Activity 若也在动画就直接互相挤压。前台进程的所有这些线程同在 top-app cpuset，调度优先级一样。
来源：https://medium.com/@MrAndroid/android-window-basic-concepts-a11d6fcaaf3f 、https://androidperformance.com/en/2015/08/12/AndroidL-hwui-RenderThread-workflow/ 、https://androidperformance.com/en/2025/03/26/Android-Perfetto-05-Chorergrapher/

### 2.2 传感器回调会不会饿死帧回调

重力传感器 `SENSOR_DELAY_GAME` = 20ms（50Hz）、`SENSOR_DELAY_UI` ≈ 60–66.7ms（约 15–16Hz）（来源：https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview ）。事件经 `SensorEventQueue` 投递到 `registerListener` 指定 Handler 的 Looper（默认＝主线程），与 Choreographer 的 doFrame 在**同一消息队列排队**：50Hz 的事件流本身不会「饿死」帧回调（vsync 消息是异步消息，可越过同步屏障），但**每个事件在主线程做的工作都直接从 8.3/16.7ms 预算里扣**，且事件处理若触发额外状态更新，会加大 `INPUT_HANDLING/ANIMATION` 段。官方指南明确：`onSensorChanged` 内做的事越少越好，重活移交后台线程。来源同上。

### 2.3 Binder/vsync 抖动

vsync 经 Choreographer 的 `FrameDisplayEventReceiver` 到达；`PredictionError` 型 jank 即 SurfaceFlinger 的时间预测漂移，属系统侧、应用无解（来源：https://perfetto.dev/docs/data-sources/frametimeline ）。应用侧可控的是不要让主线程消息队列里有长消息（Binder 往返、磁盘 IO 等），否则 `UNKNOWN_DELAY_DURATION` 变大。

### 2.4 120Hz 面板：postInvalidateOnAnimation 的真实节奏

`postInvalidateOnAnimation` 跟随**当前显示刷新率**的 vsync——面板跑 120Hz 时回调就是 120fps，预算从 16.7ms 变 **8.3ms**（90Hz 为 11.1ms）。官方 2020 博文已明确「不要假设 60Hz」。一个「在 60Hz 下勉强 12ms 的 onDraw」放到 120Hz 面板上必然半数丢帧，这是本工作负载「感觉不流畅」的头号系统性怀疑对象。
来源：https://android-developers.googleblog.com/2020/04/high-refresh-rate-rendering-on-android.html

把单个 View/Window 锁到 60Hz 的 API 阶梯：

1. **API 35+（Android 15, ARR 设备）**：`View.setRequestedFrameRate(60f)` 或 `REQUESTED_FRAME_RATE_CATEGORY_NORMAL`（≈60）——View 级投票，系统聚合各 View 票（倍数关系取高；非倍数时 >60 算 High、≤60 算 Normal）。注意：ARR 设备上普通 View 默认票就是 NORMAL(≈60)，触摸会临时 boost 到 High（`window.isFrameRateBoostOnTouchEnabled` 可关）；`Display.hasArrSupport()` 查询支持。来源：https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate 、https://source.android.com/docs/core/graphics/arr
2. **API 30+**：`Surface.setFrameRate(60f, FRAME_RATE_COMPATIBILITY_DEFAULT)`（对 Dialog 的 window surface 亦可），这是「意图声明」，系统可拒绝。来源：https://developer.android.com/media/optimize/performance/frame-rate
3. **API 23+ 兜底**：`WindowManager.LayoutParams.preferredDisplayModeId`（或 `preferredRefreshRate`）强切显示模式——粒度是整个窗口/屏幕，官方不鼓励。来源：https://android-developers.googleblog.com/2020/04/high-refresh-rate-rendering-on-android.html
4. **纯应用层自节流（minSdk 26 全兼容）**：在 `Choreographer.FrameCallback` 里用 `frameTimeNanos` 做节拍器，距上帧 <~12ms 就只 re-post 不计算不 invalidate（跳一个 vsync）——面板仍 120Hz，但你的物理/绘制以 60Hz 走，动画用 `frameTimeNanos` 推进即不会因跳帧而不匀。Grafika 的 "Record GL app" 用的正是「落后就跳渲染帧、不跳运动推进」的模式。来源：https://github.com/google/grafika

---

## Q3 Kotlin/ART 数值性能（2024–2026 ARM 大核）

### 3.1 Double vs Float 数组

- 标量运算：大核（Cortex-X 系）上 FP64 加/乘/FMA 的延迟与吞吐同 FP32 同级，纯标量下 Double 不慢多少；
- 但 **内存带宽×2**（8B vs 4B——对 216 点×多层×每帧两遍的读写就是两倍 cache 压力），且 **NEON 128-bit 向量只装 2×double vs 4×float**，向量化收益减半。单个大核 NEON FP32 FMA 峰值量级 ~100+ GFLOPS（AI Benchmark 论文实测 113 GFLOPS 级），FP64 减半。
来源：https://developer.arm.com/documentation/PJDOC1505342170538636/r0p1/?lang=en （Cortex-X4 Software Optimization Guide）、https://arxiv.org/pdf/1810.01109
- 本例结论：物理步进若需精度可留 Double；**场采样、颜色、网格装配全链路改 Float**（`drawVertices` 本来就要 FloatArray，颜色 8bit 足够），省去 Double→Float 转换。

### 3.2 ART 会自动向量化吗

会。自 Android O 起 ART optimizing compiler 内置 loop vectorizer，把字节码内**最内层、简单归纳变量、无方法调用、无复杂控制流**的计数循环编译成 NEON SIMD；论文与 Linaro 报告给出典型 kernel 有数倍收益。任何循环体内的调用（未内联的函数、`Math.pow`）、跨迭代依赖（**递推式相位累进属于跨迭代依赖，不可向量化**）都会让向量化失败。
来源：https://research.google/pubs/automatic-vectorization-in-art-android-runtime/ 、https://slideshare.net/linaroorg/automatic-vectorization-in-art-android-runtime-sfo17216

### 3.3 二维 Array<DoubleArray> vs 扁平 DoubleArray

Kotlin 的 `Array<DoubleArray>` 是「引用数组→行数组」两级结构：每次 `a[i][j]` 多一次对象指针加载 + 一次额外 bounds check + 行间不保证连续（cache 不友好）。扁平 `DoubleArray(rows*cols)` + 手工下标是标准优化，也更利于 BCE 与向量化。原始类型数组 vs 装箱集合的差距在 Kotlin 文献中有量化（均值计算 ~25% 差距只是装箱层面；两级 vs 扁平在遍历 kernel 中常见 1.3–2×）。
来源：https://kt.academy/article/ek-arrays 、https://www.romainguy.dev/posts/2025/eliminating-array-bounds-checks/

### 3.4 Bounds Check Elimination（BCE）现状

ART JIT/AOT 有多层 BCE：静态可证明的直接消除；循环内用 pre-header 检查 + **deoptimization-based BCE**（失败才回解释器）；支持 `a[i-1]`/`a[i+1]` 型偏移。实操建议（Romain Guy, 2025-05）：在 kernel 开头放一次显式 `if (v.size < N) return`，可让后续所有访问的检查被消除（他的 Matrix 例子字节码 136→60 条）；配合 `inline` 帮助编译器做全局判断；**永远 benchmark 验证**。
来源：https://www.romainguy.dev/posts/2025/eliminating-array-bounds-checks/

### 3.5 kotlin.math.pow / sin / cos / exp 的真实成本

`kotlin.math` 直接映射 `java.lang.Math`。ART 里 `Math.sqrt/floor/ceil/rint/round` 是**单条 ARM64 指令**（`Fsqrt`/`Frintm`…）；`Math.sin/cos/tan/exp/log/pow/atan2/cbrt/hypot` 在 intrinsics 列表中（`intrinsics_list.h` 有 `V(MathPow, kStatic, kNeedsEnvironment, …)` 等条目），实现为**绕过 JNI 的直接 libm 调用**——比普通 JNI 快，但仍是几十 ns 级函数调用，且**阻断向量化**。
来源：https://android.googlesource.com/platform/art/+/master/runtime/intrinsics_list.h 、https://android.googlesource.com/platform/art/+/master/compiler/optimizing/intrinsics_arm64.cc

替换策略（数值代码通用结论）：整数/半整数幂 → 乘法链或 `x*x`、`1/sqrt(x)`；固定底数 `pow(c, t)` → `exp(t*ln c)` 预算常数；逐顶点 gamma/sRGB → **一次性 LUT**（本例已有 sRGB LUT，把 pow() 全部并进 LUT 是对的方向）。3000 次 pow ≈ 0.06–0.15ms 纯调用开销 + 失去 SIMD。
来源：https://github.com/llvm/llvm-project/issues/126383 、https://forums.developer.nvidia.com/t/implement-faster-cuda-intrinsics-for-specific-power-functions/158730

### 3.6 ART vs 原生 / 何时上 NDK

- JNI 边界固定成本 ≈ **36ns/次**（Pixel 7a 实测）——每帧 1–2 次跨界、每次干几百 µs 的活，跨界成本可忽略。来源：https://lucodivo.github.io/jni_marshalling_performance.html
- 标量代码 ART 与 C 差距已缩到个位数百分比（64 位 Android 6 实测 ~3%；老设备 38%）；但 **clang 的向量化/流水线调度仍强于 ART**，可向量化 kernel 用 NDK+NEON intrinsics 常见 2–10×。来源：https://www.androidauthority.com/java-vs-c-app-performance-689081/ 、https://blog.minhazav.dev/guide-compiler-to-auto-vectorise/ 、https://dev.to/software_mvp-factory/arm-neon-simd-intrinsics-for-real-time-audio-processing-in-android-ndk-fpb
- RenderScript 已废弃（Android 12 起）；官方替代品 **renderscript-intrinsics-replacement-toolkit**（多线程 CPU + Neon，宣称对 intrinsics 类操作最高 2×）只覆盖 blur/blend/resize 等固定算子，**不适合本例的自定义波场 kernel**；自定义计算的官方推荐是 Vulkan compute 或 NDK。来源：https://developer.android.com/guide/topics/renderscript/migrate 、https://github.com/android/renderscript-intrinsics-replacement-toolkit 、https://android-developers.googleblog.com/2021/04/android-gpu-compute-going-forward.html
- 判据：先做完 Kotlin 侧（Float+扁平数组+去调用+BCE），若热点循环仍 >2–3ms 再考虑单个 `external fun` 接 NEON kernel。

### 3.7 Baseline Profiles / AOT 对热 onDraw 的作用

Baseline Profile 让安装期就 AOT 编译清单内方法，消除**首次运行**的解释/JIT 预热卡顿；对已跑了几秒、JIT 早已编译的稳态动画帮助有限（cloud profiles 通常也会覆盖）。价值在「对话框第一次打开的前 1–2 秒」——把 onDraw 调用链加进 profile 可显著改善首次展示的丢帧。
来源：https://developer.android.com/topic/performance/baselineprofiles/overview

---

## Q4 分配与 GC（60Hz 下的短命 DoubleArray）

### 4.1 ART 2026 的真实成本

- 分配本身极廉价：CC/CMC 用 **RegionTLAB bump-pointer**，线程本地指针递增即完成，无锁。
- 回收：Android 10+ 为 **generational CC**（年轻代优先，年轻对象「以极小代价收集」）；Android 13+ 默认切到 **userfaultfd 的 CMC**（并回portable到 S+）。官方示例数据：young CC 平均暂停 ~**1.83ms**、线程挂起均值 ~47µs——暂停不长，但 **GC 线程（HeapTaskDaemon）的并发标记/拷贝要吃 CPU**，与 UI/Render 线程抢大核，另有 read barrier 常量开销。
来源：https://source.android.com/docs/core/runtime/gc-debug
- 本例量级：每层每帧几个 216 长度 DoubleArray（~1.7KB/个）×9 层×60fps ≈ 1–3MB/s 垃圾 → 每几秒一次 young GC。稳态平均帧耗时几乎不受影响，但 **GC 活跃的那几帧会叠加 1–3ms 的 CPU 竞争**——表现恰为「偶发的、无规律的小顿挫」，与用户「not smooth」的主诉高度吻合。

### 4.2 在 Perfetto 里看 GC

设备端 System Tracing 勾选 **dalvik** 类别：HeapTaskDaemon 线程出现 "Background young concurrent copying GC" 等切片，另有 heap size/allocated 计数器轨道；对照同一时刻的 doFrame 是否被挤压即可定量归因。应用内也可读 `Debug.getRuntimeStat("art.gc.gc-count")` 等运行时统计做长期监控。
来源：https://perfetto.dev/docs/data-sources/atrace 、https://source.android.com/docs/core/runtime/gc-debug

### 4.3 池化最佳实践

帧驱动代码的铁律是**稳态零分配**：所有 per-frame 数组在 View attach 时按最大尺寸预分配并复用（本例 mesh 已 17k floats 常驻，把 optics 的临时 DoubleArray 也提为成员/池即可）；Margelo 的 120Hz 动画调查同样把「删除每帧分配」列为落地修复之一（消除了 settle 阶段的 GC 顿挫）。池的注意点：不要用带锁的通用池（Pools.SynchronizedPool）在帧路径上，单线程直接成员数组即可。
来源：https://blog.margelo.com/profiling-skia-reanimated-low-end-android

---

## Q5 把工作移出 UI 线程

### 5.1 生产者-消费者几何流水线（推荐给本例的结构）

模式：worker（HandlerThread）跑 物理+场采样+颜色+网格装配，产出「帧包」（FloatArray 顶点/颜色 + band 像素 buffer）；UI 线程 onDraw 只做 `drawVertices` + 少量 Path。要点：

- **Choreographer 是 per-Looper-thread 的**：worker 线程有 Looper 就能 `Choreographer.getInstance().postFrameCallback` 拿到自己的 vsync 回调，实现「vsync 驱动的生产者」，官方文档明确支持渲染在其他线程的场景。来源：https://developer.android.com/reference/android/view/Choreographer 、https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/view/Choreographer.java
- 缓冲与撕裂：**三缓冲 + 原子交换**（`AtomicReference<FrameData>` 或 index 轮换：worker 写 back、发布到 pending、UI 取 pending→front）；UI 永远只读 front，绝不等锁——等不到新帧就重画旧帧（顶多重复帧，不 jank）。双缓冲在 UI/worker 节拍漂移时会互相等待，三缓冲以 1 帧延迟换取零等待；tilt 输入延迟敏感的话，把 `setTilt` 直接写 volatile 供 worker 采样，链路延迟 ≈1–2 帧（8–16ms），感知可忽略。
- 参考实现：Google **Grafika**（"Record GL app"：UI 线程收 vsync 转发给渲染线程；落后即跳渲染帧但不跳运动推进）与官方 game loops 文档（Choreographer 驱动、queue 深度控制）。来源：https://github.com/google/grafika 、https://developer.android.com/games/develop/gameloops
- 收益上限：onDraw 只剩「拷 front buffer 引用 + drawVertices 录制」，UI 线程帧成本从「全部计算」跌到 <1ms 级；计算再重也只会让**动画内容**降帧，不会拖垮窗口其他部分与输入响应。

### 5.2 SurfaceView + lockHardwareCanvas 的 2025/2026 结论

`SurfaceView.holder.lockHardwareCanvas()`（API 26+）允许你在**自己的线程**上拿硬件加速 Canvas 画进独立 Surface，完全绕开 UI 线程 traversal；SurfaceView 的内容由 SurfaceFlinger 作为独立 layer（常走 overlay plane）合成——Margelo 实测从 TextureView 换 SurfaceView 后 RenderThread CPU 降 **50–65%**、bitmap 上传归零（TextureView 的每像素双重合成被消除）。
在 Dialog 中的坑：SurfaceView 是「挖洞」合成，圆角裁剪、窗口进出场动画、elevation 阴影、与背景 dim 的混合都不作用于其内容；`setZOrderOnTop`/`setZOrderMediaOverlay` 需要斟酌；截图（PixelCopy 之外的方式）拿不到内容。对话框内小面积水面这类场景通常可接受，但视觉集成成本要预估。lockHardwareCanvas 的注意事项：某些 Canvas 操作在 hardware canvas 上不可用、每次 lock/unlock 提交一帧、不要长期持锁。
来源：https://blog.margelo.com/profiling-skia-reanimated-low-end-android 、https://developer.android.com/reference/android/view/SurfaceHolder#lockHardwareCanvas() 、https://google-developer-training.github.io/android-developer-advanced-course-concepts/unit-5-advanced-graphics-and-views/lesson-11-canvas/11-2-c-the-surfaceview-class/11-2-c-the-surfaceview-class.html
- 与 5.1 的取舍：5.1（worker 算 + UI 画）保留全部 View 系统集成（圆角、dim、动画），已足够把 UI 线程减负 90%+；SurfaceView 方案再把「录制+RenderThread」也移走，适合连 drawVertices 录制都超支的极端情况。**先 5.1，后 5.2**。

---

## Q6 传感器驱动的重绘风暴治理

- 采样档位：GAME=20ms/50Hz、UI≈60ms/16Hz、或 `registerListener(listener, sensor, samplingPeriodUs)` 自定微秒值（API 9+）。50Hz 对 60fps 动画输入正合适，不必降档。来源：https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
- `maxReportLatencyUs` 批处理（API 19+ 四参重载）是**省电特性**：允许 FIFO 攒批后一次投递——对动画输入意味着成批迟到的旧值，**不适合**tilt 驱动的实时动画，保持 0（或极小）即可。来源：https://source.android.com/docs/core/interaction/sensors/batching 、https://developer.android.com/reference/android/hardware/SensorManager
- 正确姿势（本例）：`registerListener(listener, gravity, 20_000, handler /*后台 HandlerThread*/)` → 回调里只做 `latestTilt` 的 volatile/Atomic 写入（几条指令）→ 帧回调/worker 每帧读一次做平滑。效果：主线程消息队列零传感器消息、每事件工作 O(1)、天然把 50Hz 事件流「合并」为每帧一次采样。**不要**在 onSensorChanged 里直接算物理或调 invalidate（invalidate 本身会被 vsync 合并，但事件里的计算不会）。
- 该模式即「事件流 → 最新值寄存器 → 按帧消费」，等价于 Choreographer 对 input 的合并策略，是官方 sensors 指南「callback 里少做事、重活去后台线程」的落地形态。来源：https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview

---

## Q7 copyPixelsFromBuffer + BitmapShader 每帧路径

### 7.1 上传发生在哪

软件 Bitmap 画到硬件 Canvas 时，HWUI/Skia 按 **Bitmap generation ID** 缓存 GPU 纹理：`copyPixelsFromBuffer` 修改像素会 bump genID → 该 Bitmap 的纹理缓存失效 → **下一帧 RenderThread 在 DrawFrame（sync/flush 阶段）重新 glTexSubImage2D/vkCmdCopy 上传**。即：**CPU 拷贝在 UI 线程（copyPixelsFromBuffer 本身），GPU 上传在 RenderThread 的绘制期**，两头都占时间；FrameMetrics 里分别落在 DRAW 与 SYNC/COMMAND_ISSUE。（Hardware Bitmap 则经由专门的 HardwareBitmapUploader 线程。）
来源：https://android.googlesource.com/platform/frameworks/base/+/a029ea1/libs/hwui/TextureCache.cpp 、https://android.googlesource.com/platform/frameworks/base/+/master/libs/hwui/HardwareBitmapUploader.cpp 、https://groups.google.com/d/topic/skia-discuss/5MWTlm-n_yo

### 7.2 小纹理×多次的代价

216×1 RGBA_F16 = 1,728B——数据量可忽略，但**每张脏 Bitmap 一次独立的驱动上传调用 + 状态切换**：10–30 个 band × 每帧 = 10–30 次微上传，成本全在驱动固定开销与打断批处理（Vulkan 后端还有 barrier）。已知实锤案例：Pingo 在 120Hz 低端机上 `dumpsys gfxinfo` 显示 **79% janky 帧归因 "Slow bitmap uploads"**；skia-discuss 亦记录「每帧改 Bitmap → 每帧重上传」的机制。池化 ×3 帧只解决了 CPU 侧写-读竞争，**没有减少上传次数**（每个池化 Bitmap 每帧仍被写→genID 仍每帧变）。
来源：https://blog.margelo.com/profiling-skia-reanimated-low-end-android 、https://groups.google.com/d/topic/skia-discuss/5MWTlm-n_yo

### 7.3 替代方案（按侵入度排序）

1. **单一 atlas**：把所有 band 合成一张 216×N（N=band 数）Bitmap，每帧一次 `copyPixelsFromBuffer`、一次上传；各 band 用同一 BitmapShader + 每 band 平移的 shader local matrix 采样自己那一行 → 上传次数 30→1，还提高 Skia 批处理概率。
2. **消灭 Bitmap**：band 本质是一维渐变的话，直接在现有 AGSL RuntimeShader 里用 uniform 数组/多项式求色，或改 `LinearGradient`（多 stop）——零上传。
3. RGBA_F16 → RGBA_8888（若 LUT 输出本就是 8bit 精度）：字节减半，且 8888 是驱动最快路径；F16 仅在确需线性空间混合时保留。

---

## Q8 HWUI 显示列表成本

- **录制便宜、回放与 GPU 才是钱**：onDraw 在 RecordingCanvas 上每个 op 只是往 display list 追加条目（每 op 纳秒~微秒级）；重放在 RenderThread，光栅在 GPU。本例 8×drawVertices + 10–30 op 的 op 数很小，display list 本身不是瓶颈。参考：Google I/O "Drawn out: how Android renders"（https://www.youtube.com/watch?v=zdQRIYOST64 ）、https://medium.com/revolut/custom-view-from-scratch-part-iii-performance-and-optimisation-54cb6ac57e4b
- **drawVertices 自 API 29 起硬件加速**（minSdk 26 需注意：26–28 的设备走软件回退路径会截然不同——但 2024–2026 高端机不受影响）。来源：https://developer.android.com/topic/performance/hardware-accel
- **批处理规则**：Skia Ganesh 只合并「兼容状态」的相邻 op；**SkPaint 挂了不同 SkShader 就不合并**（skia-discuss 官方回答）。8 个 drawVertices 各带不同 gradient/shader → 8 个独立 GPU draw——对现代 GPU 仍是零头，无需为此重构。来源：https://groups.google.com/g/skia-discuss/c/81Hn2IBdre4
- **RuntimeShader 包装 gradient（dither wrapper）**：每个唯一 AGSL 程序首次使用要编译 pipeline（首帧/首次出现新 shader 时的可感知停顿，之后有缓存）；同一 RuntimeShader 实例改 uniform 不触发重编译。因此：**全局共享一个 dither wrapper 实例**、band 间只换 uniform 与输入 shader；避免每帧 new RuntimeShader。它确实使各 draw 状态互异 → 不参与合并，但 op 数量级下无碍。来源：https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl 、https://developer.android.com/reference/android/graphics/RuntimeShader
- **canvas.rotate 整场景**：对 GPU 光栅就是顶点矩阵，一般免费；次级效应：旋转后 clip 不再轴对齐（scissor→stencil/AA clip，略贵）、渐变的 dither/AA 边缘走慢路径的概率略增。预期影响 <0.5ms，用 GPU_DURATION 对比开/关旋转即可验证。
- Paint 属性切换本身无显著 CPU 成本（Skia 按 op 快照状态），真正贵的是它**打断合并**与触发新 pipeline（新 shader 组合）。

---

# (a) 无 adb 的分步测量方案（面向发给真实用户的 debug 包）

**第 0 步：确认目标帧率与实际节奏。**
在 View attach 时记录 `display.refreshRate`（`context.display` / `windowManager.defaultDisplay`），并在 `Choreographer.FrameCallback` 里累计 `frameTimeNanos` 差值直方图（8.3ms 档还是 16.7ms 档？）。若设备以 120Hz 调度而 onDraw >8.3ms —— 先按 Q2.4 锁 60Hz，可能一步治本。

**第 1 步：JankStats + FrameMetrics 挂到对话框的 window。**
`JankStats.createAndTrack(dialog.window!!, listener)`（注意不是 Activity 的 window）；同时自挂 `addOnFrameMetricsAvailableListener` 收集 DRAW / ANIMATION / SYNC / COMMAND_ISSUE / SWAP / GPU_DURATION(API 31+) / TOTAL 的滚动 P50/P95/P99，按 Q1.2 的规则输出「UI-bound / RT-bound / GPU-bound / 调度-bound」判定。写入本地文件随反馈上传。（https://developer.android.com/topic/performance/jankstats 、https://developer.android.com/reference/android/view/FrameMetrics ）

**第 2 步：onDraw 六阶段应用内计时。**
用 `androidx.tracing.Trace.beginSection/endSection`（"1-audio-drain" / "2-physics" / "3-field-sample" / "4-vertex-color" / "5-mesh" / "6-issue-draws"）包住六段；同时用 `System.nanoTime()` 累计每段滚动均值/P95 打进第 1 步的日志。Trace section 在系统 trace 里可见，nanoTime 则无需任何 trace 就能给出「哪段最肥」的一手答案。（https://perfetto.dev/docs/getting-started/atrace ）

**第 3 步：用户/自己设备端录 Perfetto trace（零 adb）。**
Android 9+ 内置 **System Tracing**：开发者选项 → System Tracing → 开启 Quick Settings 磁贴 →（类别勾 gfx、view、sched、freq、dalvik，并开「Trace debuggable applications」）→ 复现 → 停止 → 通知栏直接**分享 .perfetto-trace 文件**（邮件/IM 发回），在 ui.perfetto.dev 打开。看：FrameTimeline 的 jank_type 分布、doFrame vs DrawFrame 时长、第 2 步的 section、UI 线程 Runnable 占比与所在核/频率、HeapTaskDaemon 的 GC 切片与 doFrame 的重叠。（https://developer.android.com/topic/performance/tracing/on-device ）

**第 4 步（API 35+ 设备）：应用内程序化抓取 —— ProfilingManager。**
`ProfilingManager.requestProfiling()`（配 androidx `core:1.19` + `tracing:1.3` 的 `SystemTraceRequestBuilder`）可**由 App 自己发起系统 trace/堆转储/栈采样**，结果文件回调到应用私有目录——把它接到调试面板的「录制 10 秒性能」按钮上，用户点一下、文件随反馈上传，完全绕开 adb 与开发者选项。Datadog 已在生产环境这样收数。（https://developer.android.com/topic/performance/tracing/profiling-manager/how-to-capture 、https://android-developers.googleblog.com/2026/06/datadog-profilingmanager-performance-insights.html ）

**第 5 步：方法级热点（应用内触发）。**
调试面板按钮触发 `Debug.startMethodTracingSampling(path, bufferSize, 1000/*µs*/)` 10 秒后 stop——采样式开销远低于全插桩（全插桩会整体拖慢、只可看相对占比），.trace 文件从应用目录分享出来，用 Android Studio Profiler 打开看火焰图。（https://developer.android.com/studio/profile/generate-trace-logs 、https://developer.android.com/topic/performance/benchmarking/microbenchmark-profile ）

**第 6 步：环境因子随帧记录。**
`PowerManager.addThermalStatusListener` + `getThermalHeadroom(15)`、电池档位、`display.refreshRate` 变化，与第 1 步的帧日志共表——区分「代码慢」vs「热降频/切 120Hz」。（https://developer.android.com/games/optimize/adpf/thermal ）

**第 7 步：A/B 验证。**
调试面板加开关：60Hz 节流开/关、band 上传 atlas 化开/关、worker 线程管线开/关……每个开关跑 60 秒对比第 1 步的 P95/丢帧率——所有优化以此为准绳（Romain Guy：always benchmark）。

---

# (b) 本工作负载最可能的十大收益（按期望值排序）

| # | 措施 | 预期量级 | 依据 |
|---|---|---|---|
| 1 | **锁定 60Hz**（API 35 `setRequestedFrameRate(60)` / API 30 `Surface.setFrameRate` / 全版本 Choreographer 自节流，Q2.4） | 若当前被调度在 120Hz：预算 8.3→16.7ms，**丢帧可能直接归零**；期望值最高的单点修复 | https://android-developers.googleblog.com/2020/04/high-refresh-rate-rendering-on-android.html |
| 2 | **worker 线程生产者-消费者管线**（物理+采样+颜色+装配移出 UI 线程，三缓冲，Q5.1） | UI 线程 onDraw 从「几乎全部」降到 <1ms；结构性根治，且使后续所有计算优化与 UI 平滑解耦 | https://github.com/google/grafika 、https://developer.android.com/games/develop/gameloops |
| 3 | **稳态零分配**：optics 的每层每帧 DoubleArray 全部池化/成员化（Q4） | 消除偶发 1–3ms 的 GC 竞争尖刺（正是「间歇性不顺」的典型来源） | https://source.android.com/docs/core/runtime/gc-debug 、https://blog.margelo.com/profiling-skia-reanimated-low-end-android |
| 4 | **band 位图 atlas 化**：30 次微上传 → 1 次（或 AGSL 直接求色，0 次）（Q7.3） | RenderThread/驱动侧省 0.5–2ms/帧（设备相关）；同时消除 SYNC 段尖刺 | https://blog.margelo.com/profiling-skia-reanimated-low-end-android 、https://groups.google.com/d/topic/skia-discuss/5MWTlm-n_yo |
| 5 | **场采样+颜色+装配改 Float + 扁平数组**（Q3.1/3.3） | 这三段 1.5–2.5×（带宽减半、SIMD 车道翻倍、去两级索引）；顺带消掉 Double→Float 转换 | https://kt.academy/article/ek-arrays 、https://arxiv.org/pdf/1810.01109 |
| 6 | **逐顶点循环去 pow()**：并入既有 sRGB LUT / 乘法链；循环体内零函数调用以解锁 ART 向量化（Q3.2/3.5） | 颜色段 1.5–3×（3000×几十 ns 的调用 + 恢复向量化资格） | https://android.googlesource.com/platform/art/+/master/runtime/intrinsics_list.h 、https://research.google/pubs/automatic-vectorization-in-art-android-runtime/ |
| 7 | **热 kernel 顶部显式尺寸检查 + inline 消除 bounds check**（Q3.4） | 热循环 5–20%（Romain Guy 实例字节码 -50%+；实测为准） | https://www.romainguy.dev/posts/2025/eliminating-array-bounds-checks/ |
| 8 | **重力传感器移后台 Handler + 最新值合并**（Q6） | 主线程每秒少 50 条消息与其中的处理；0.1–0.5ms/帧 + 消除与 doFrame 的排队干扰 | https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview |
| 9 | **RuntimeShader 实例复用 + 常量 hoist**：单一 dither wrapper 实例改 uniform；波分量不变式提出内层循环（Q8） | 消除新 pipeline 编译型首帧尖刺；采样段 1.2–1.5× | https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl |
| 10 | **仍不够时**：两个最热循环下 NDK+NEON（JNI 36ns 可忽略），或整体改 SurfaceView+lockHardwareCanvas（Q5.2、Q3.6） | 向量化 kernel 2–4×；SurfaceView 再省 RenderThread 50%+（Margelo 实测），代价是对话框视觉集成 | https://lucodivo.github.io/jni_marshalling_performance.html 、https://blog.margelo.com/profiling-skia-reanimated-low-end-android |

补充判断：按工作负载描述估算，各段在大核上的量级为 物理 ~0.1–0.3ms、场采样（86k 次求值）~0.5–2ms、逐顶点颜色（含 pow）~0.5–2ms、装配 ~0.1–0.3ms、录制+杂项 ~0.5–1ms —— 合计 2–6ms，60Hz 下本应有富余；因此「不顺」更可能来自 **(1) 120Hz 调度把预算砍半、(3) GC 尖刺、(4) 上传尖刺** 这类**间歇性因素**，而非稳态均值超支——测量方案第 0/1/3 步会直接分辨这一点。

## 引用汇总（主要来源）

- Perfetto FrameTimeline：https://perfetto.dev/docs/data-sources/frametimeline
- Perfetto atrace/App tracepoints：https://perfetto.dev/docs/getting-started/atrace 、https://perfetto.dev/docs/data-sources/atrace
- 设备端 System Tracing（无 adb）：https://developer.android.com/topic/performance/tracing/on-device
- ProfilingManager（API 35 应用内抓 trace）：https://developer.android.com/topic/performance/tracing/profiling-manager/how-to-capture 、https://android-developers.googleblog.com/2026/06/datadog-profilingmanager-performance-insights.html
- FrameMetrics：https://developer.android.com/reference/android/view/FrameMetrics
- JankStats：https://developer.android.com/topic/performance/jankstats 、https://medium.com/androiddevelopers/jankstats-goes-alpha-8aff942255d5
- Choreographer / 丢帧日志：https://developer.android.com/reference/android/view/Choreographer 、https://www.techyourchance.com/android-application-skips-frames/
- 高刷渲染：https://android-developers.googleblog.com/2020/04/high-refresh-rate-rendering-on-android.html
- ARR / setRequestedFrameRate：https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate 、https://source.android.com/docs/core/graphics/arr 、https://developer.android.com/media/optimize/performance/frame-rate
- UI/RenderThread 每帧协作：https://androidperformance.com/en/2025/08/02/Android-Perfetto-07-MainThread-And-RenderThread/ 、https://androidperformance.com/en/2015/08/12/AndroidL-hwui-RenderThread-workflow/
- 调度/cpuset/FIFO：https://source.android.com/docs/core/tests/debug/jank_jitter 、https://lwn.net/Articles/809545/ 、https://lwn.net/Articles/706374/ 、https://androidperformance.com/en/2022/01/21/android-systrace-cpu-state-runnable/
- 热 API：https://developer.android.com/games/optimize/adpf/thermal
- ART 向量化：https://research.google/pubs/automatic-vectorization-in-art-android-runtime/ 、https://slideshare.net/linaroorg/automatic-vectorization-in-art-android-runtime-sfo17216
- BCE：https://www.romainguy.dev/posts/2025/eliminating-array-bounds-checks/
- ART Math intrinsics：https://android.googlesource.com/platform/art/+/master/runtime/intrinsics_list.h 、https://android.googlesource.com/platform/art/+/master/compiler/optimizing/intrinsics_arm64.cc
- 原始类型数组：https://kt.academy/article/ek-arrays
- JNI 开销：https://lucodivo.github.io/jni_marshalling_performance.html ；ART vs C：https://www.androidauthority.com/java-vs-c-app-performance-689081/
- RenderScript 迁移/Toolkit：https://developer.android.com/guide/topics/renderscript/migrate 、https://github.com/android/renderscript-intrinsics-replacement-toolkit 、https://android-developers.googleblog.com/2021/04/android-gpu-compute-going-forward.html
- Baseline Profiles：https://developer.android.com/topic/performance/baselineprofiles/overview
- ART GC：https://source.android.com/docs/core/runtime/gc-debug
- 传感器：https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview 、https://source.android.com/docs/core/interaction/sensors/batching 、https://developer.android.com/reference/android/hardware/SensorManager
- Grafika / 游戏循环：https://github.com/google/grafika 、https://developer.android.com/games/develop/gameloops
- SurfaceView / lockHardwareCanvas：https://google-developer-training.github.io/android-developer-advanced-course-concepts/unit-5-advanced-graphics-and-views/lesson-11-canvas/11-2-c-the-surfaceview-class/11-2-c-the-surfaceview-class.html 、https://developer.android.com/reference/android/view/SurfaceHolder#lockHardwareCanvas()
- 纹理缓存/上传：https://android.googlesource.com/platform/frameworks/base/+/a029ea1/libs/hwui/TextureCache.cpp 、https://android.googlesource.com/platform/frameworks/base/+/master/libs/hwui/HardwareBitmapUploader.cpp 、https://groups.google.com/d/topic/skia-discuss/5MWTlm-n_yo 、https://blog.margelo.com/profiling-skia-reanimated-low-end-android
- 硬件加速支持表（drawVertices API 29+）：https://developer.android.com/topic/performance/hardware-accel
- Skia 批处理与 shader：https://groups.google.com/g/skia-discuss/c/81Hn2IBdre4
- AGSL/RuntimeShader：https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl 、https://developer.android.com/reference/android/graphics/RuntimeShader
- Buffer stuffing / Frame pacing：https://developer.android.com/games/sdk/frame-pacing 、https://androidperformance.com/en/2019/12/15/Android-Systrace-Triple-Buffer/
- 方法剖析开销：https://developer.android.com/studio/profile/generate-trace-logs 、https://developer.android.com/topic/performance/benchmarking/microbenchmark-profile
- 窗口/ViewRootImpl：https://medium.com/@MrAndroid/android-window-basic-concepts-a11d6fcaaf3f 、https://androidperformance.com/en/2025/03/26/Android-Perfetto-05-Chorergrapher/
- GPU 工具现状：https://developer.android.com/agi 、https://github.com/google/agi 、https://developer.android.com/agi/sys-trace/counters
- Cortex-X4 优化指南：https://developer.arm.com/documentation/PJDOC1505342170538636/r0p1/?lang=en ；NEON 峰值参考：https://arxiv.org/pdf/1810.01109
