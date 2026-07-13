# Android 渲染架构调研：DialogFragment 内音频驱动 2.5D 水面（280×420dp，60fps）

调研日期：2026-07-12。工作负载：自定义 View（硬件加速 Canvas），UI 线程内每帧完成 9 层 × 216 点波动方程（120Hz 子步）、2D 方向波场采样（约 25×216 点/帧）、约 3000 个顶点的 CPU 逐顶点配色（9 层 alpha 合成 + Schlick Fresnel + sRGB/linear LUT）、8 次 `Canvas.drawVertices`（约 1.7 万顶点/帧）、10–30 次光学补绘（Path/RadialGradient/AGSL RuntimeShader 条带，216×1 RGBA_F16 位图池轮换），`postInvalidateOnAnimation` 驱动。minSdk 26，API 33+ 走 AGSL。

---

## 0. 结论速览

1. **瓶颈几乎可以确定在 UI 线程 CPU 数学与每帧分配，而不是 GPU 填充率或 draw call 数量。** 1.7 万顶点、约 40 次绘制指令对 2020 年以后的任何移动 GPU 都是小负载；而 60Hz 下 UI 线程必须在约 16.7ms 内完成输入、动画、traversal 与 sync，倾斜手机时采样窗口变宽恰好加重的是 CPU 侧计算，与既有症状吻合。
2. **`drawVertices` 在 API 29+ 的硬件 Canvas 上是真 GPU 三角形渲染**（Skia Ganesh `DrawVerticesOp`，Gouraud 顶点色插值），17k 顶点/帧对 GPU 微不足道；真正的成本是 CPU 侧为这 3000 个顶点算颜色。注意 minSdk 26 的现实：API 26–28 上硬件 Canvas 不支持 `drawVertices`，需软件层回退。
3. **整块水面收进一个 AGSL 全通道片元着色器**在旗舰上（约 1.5M px）轻松 60fps——Android 系统自身的 ripple、blur、stretch overscroll 就是全屏 RuntimeShader；在 Mali-G57 MC2 一类入门中端 GPU 上，按 ARM 官方周期预算公式约 40–67 cycles/px，9 层合成 + `pow(5)` 处于预算边缘，需要 half 精度、循环展开、或 0.5–0.75x 分辨率中间层。每帧 216×25 F16 数据纹理经 `copyPixelsFromBuffer` 更新会因 generation ID 变更触发重新上传，但 43KB 级别的上传成本可忽略。
4. **`android.graphics.Mesh`（API 34+）在架构上最贴合此工作负载**（顶点着色器搬走逐顶点数学、片元着色器搬走配色），但生态极新、无法更新既有 Mesh 的顶点缓冲（每帧重建）、且 API 34+ 截至 2026 年中覆盖率仅约五成，必须维护双渲染器。
5. **GLES 迁移对这个尺寸与场景不划算**：SurfaceView 在对话框内有 z-order、圆角裁剪、出入场动画的固有问题；TextureView 多一次拷贝且实测最多增加 30% 功耗；EGL 生命周期、上下文丢失、无 HWUI 互操作都是持续维护成本。AGSL/Mesh 已能把同样的数学放上 GPU。
6. **最优路径是两阶段混合**：先把物理 + 几何 + 配色移到工作线程（producer/consumer，UI 线程 onDraw 只提交预构建缓冲）并治理分配与 120Hz 帧率放大，这一步零 API 风险、直接解决"倾斜不顺滑"；再把连续水面 + 逐顶点配色收进单一 AGSL 全通道（API 33+，<33 沿用现有 Canvas 回退），把 CPU 逐顶点配色彻底清零。

---

## 1. 瓶颈定位与 CPU 帧预算

**帧预算的官方口径。** 60fps 下每帧必须在 16ms 内完成，超出即丢帧；90fps 是 11ms，120fps 是 8ms（[Android vitals: Slow rendering](https://developer.android.com/topic/performance/vitals/render)；[Perfetto MainThread/RenderThread 深析](https://androidperformance.com/en/2025/08/02/Android-Perfetto-07-MainThread-And-RenderThread/)）。硬件加速下 UI 线程负责输入、动画、measure/layout、draw（onDraw 只是**录制 display list**，不产出像素）以及与 RenderThread 的 sync；RenderThread 负责把 display list 转成 GPU 指令并与 SurfaceFlinger 交互（[Systrace: MainThread & RenderThread](https://androidperformance.com/en/2019/11/06/Android-Systrace-MainThread-And-RenderThread/)）。同一帧内"主线程 + RenderThread 串行部分"合计超窗即掉帧——所以 UI 线程实际可用的份额显著小于 16.7ms，工程上一般要留出 sync + RenderThread 的时间，UI 线程部分控制在个位数毫秒。

**本工作负载的定性归因。**
- 每帧在 onDraw 里做 9×216×2 子步波动方程 + 25×216 方向波场 + 3000 顶点 × (环境渐变 lerp + 至多 9 层 alpha 合成 + Fresnel pow) 的 Kotlin double 数学，全部落在 UI 线程。isolate 环境纯数学 0.4ms 只覆盖连续面采样一项，且"host 上快、设备上慢"与中端 SoC 单核差距一致：Geekbench 6 单核 Snapdragon 8 Gen 3 约 2329，Snapdragon 6 Gen 1 约 946，Dimensity 7050 约 1061——**中端单核吞吐约为旗舰的 40–45%**（[nanoreview SD8G3](https://nanoreview.net/en/soc/qualcomm-snapdragon-8-gen-3)，[SD6G1](https://nanoreview.net/en/soc/qualcomm-snapdragon-6-gen-1)，[D7050](https://nanoreview.net/en/soc/mediatek-dimensity-7050)）。host 上 0.4ms 的数学在中端设备上翻 3–5 倍并叠加其余各项，UI 线程超过 8–10ms 即出现你所描述的间歇性不顺滑。
- 官方 vitals 文档把"draw 循环内分配对象→触发 GC（HeapTaskDaemon）""每帧修改/重建 Path""`clipPath`"列为典型 jank 来源（[Slow rendering](https://developer.android.com/topic/performance/vitals/render)）；硬件加速文档同样强调"不要在 draw() 里创建对象""避免频繁更新位图（每次都会作为 GPU 纹理重新上传）"（[Hardware acceleration](https://developer.android.com/topic/performance/hardware-accel)）。每帧 10–30 次 Path 填充 + RadialGradient/LinearGradient 对象若有新建，都会进入这两个雷区。
- GPU 侧反而宽松：视图约 1.0–1.5M px，约 40 个 draw、1.7 万顶点、若干渐变与小纹理，对 Adreno 6xx/7xx、Mali-G5x/G7x 都远低于填充率与 ALU 上限（见 §3 预算计算）。display list 与 draw call 开销层面，HWUI 每帧重录该 View 的 display list 属正常路径，数十条指令的录制/回放成本在 0.1ms 量级，不构成瓶颈。
- "倾斜手机时更卡"进一步指向 CPU：重力变化→视图变换与采样窗口变宽→**CPU 采样点数与 boundary-profile 重建增加**，GPU 负载几乎不变。已做的 120 列上限与 30Hz 重建缓解但未根治，说明余下的是常态化 CPU 高水位。

**结论：先把模拟与配色从 UI 线程摘出去、消灭每帧分配，再谈渲染 API 替换。** 这也是 Android 团队对此类症状的标准处方（[Slow rendering](https://developer.android.com/topic/performance/vitals/render)）。

---

## 2. Canvas.drawVertices 在 Skia/HWUI 中的执行

**执行路径。** Java `Canvas.drawVertices` → JNI `SkiaCanvas::drawVertices` → `SkCanvas::drawVertices`（[AOSP SkiaCanvas.cpp](https://android.googlesource.com/platform/frameworks/base/+/333321e/core/jni/android/graphics/SkiaCanvas.cpp)）。Skia 语义：以三角形网格绘制；若 Paint 带 shader 且无纹理坐标则用顶点位置采样 shader；顶点色存在时与 shader/Paint 色按 BlendMode 合成，顶点色在三角形内部做插值（[SkCanvas 参考](https://api.skia.org/classSkCanvas.html)）。在 GPU 后端（Ganesh，Android HWUI 现役后端；Skia 的下一代后端 Graphite 已在 Chrome 铺开，见 [Chromium blog 2025-07](https://blog.chromium.org/2025/07/introducing-skia-graphite-chromes.html)），Skia 工程师 Brian Osman 明确表示 `drawVertices` 与 `drawAtlas`"以非常相似的方式使用 GPU、行为与性能相近"——即按 GPU 三角形批量绘制，非软件光栅化（[skia-discuss: drawAtlas vs drawVertices](https://groups.google.com/g/skia-discuss/c/EtCEJkF7CKE)）。

**硬件加速支持从 API 29 开始。** 官方硬件加速支持表：`drawVertices()` 首个支持级别 **API 29**；此前在硬件 Canvas 上不受支持，需 `LAYER_TYPE_SOFTWARE` 回退（[Hardware acceleration 支持表](https://developer.android.com/topic/performance/hardware-accel)；2019 年实践文章亦记载必须关硬件加速才能生效：[Playing with Android canvas drawVertices](https://proandroiddev.com/playing-with-android-canvas-drawvertices-32266c480ab6)）。**对 minSdk 26 的含义：API 26–28 设备上现行 drawVertices 路径要么没在跑（被静默忽略/异常），要么整个 View 落入软件光栅化——值得回项目里核实一次。**

**17k 顶点/帧是否显著？** 不显著。业界参照：Flutter 侧把 `drawVertices`（尤其 `Vertices.raw`）定位为"最快、最底层"的批量几何路径，官方演讲即以此为题（[Craig Labenz: Canvas.drawVertices — Incredibly fast, incredibly low-level](https://www.youtube.com/watch?v=pD38Yyz7N2E)；[High-Performance Canvas Rendering](https://plugfox.dev/high-performance-canvas-rendering/)；[Flutter drawVertices API](https://api.flutter.dev/flutter/dart-ui/Canvas/drawVertices.html)），2 万+ 线段级别的场景靠它一次批量提交解决。移动 GPU 每秒可处理数亿顶点，8 次 draw × 2100 顶点在 GPU 侧成本约等于 8 个小三角形批次；Ganesh 还可能合并相容的 `DrawVerticesOp`。**限制**：顶点色是 8888（每分量 8bit）且按感知空间插值，做不了 HDR/线性插值；索引在 Skia 内部为 uint16（Mesh API 也明文用 `ShortBuffer`），单次 draw 顶点数上限 65535——当前 2100/draw 远未触及。真正的账单在 CPU：为 3000 顶点算 9 层合成色的 Kotlin double 数学 + 每帧 `float[]`/`int[]` 若有重建的分配。

---

## 3. 全通道 AGSL（RuntimeShader 一遍画完整个水面）

**评估的方案形态**：一个 RuntimeShader 覆盖约 1.0–1.5M px 的整个水面，每帧把 25×216 高度场（或 216 宽的轮廓行）与 9 层调色板经 `setInputBuffer(BitmapShader)` 以 RGBA_F16 位图喂入，片元内完成层合成 + Fresnel/镜面。

**数据纹理的每帧上传路径。** `Bitmap.copyPixelsFromBuffer` 会触发 `notifyPixelsChanged` → 像素 generation ID 递增。Skia 的 GPU 纹理缓存**以 PixelRef generation ID 为 key**："每次 genID 变化就产生一个新缓存条目；旧条目周期性冲刷、不会立即释放"——这是 Skia 工程师在 skia-discuss 上对"每帧改位图再 drawBitmap"场景的原文解释（Android P 首个 beta 曾有过度建纹理的 bug，后修复）（[skia-discuss: Issue with Skia's GPU Texture Cache in Android P](https://groups.google.com/g/skia-discuss/c/5MWTlm-n_yo)）。官方硬件加速文档同样警告"频繁更新的位图每次都会重新作为 GPU 纹理上传"（[Hardware acceleration](https://developer.android.com/topic/performance/hardware-accel)）。**量化**：216×25 RGBA_F16 = 43KB，216×1 = 1.7KB；对每秒数 GB 的内存带宽而言，每帧一次 43KB 上传（<0.05ms）可忽略，真正要防的是（a）纹理缓存条目每帧翻新带来的小额驱动开销，（b）**display list 不拷贝位图像素、只持引用**——RenderThread 消费上一帧 display list 时若 CPU 已在改同一 Bitmap，会出现撕裂/串帧；你现有的 3 帧位图池轮换正是标准解法，迁移后必须保留（同一机制参见 [RenderNode 文档](https://developer.android.com/reference/android/graphics/RenderNode)关于 display list 持有位图资源的表述与上引 skia-discuss 线程）。

**uniform 更新成本。** RuntimeShader 的 AGSL→SkSL 编译发生在**构造时一次**；`setFloatUniform` 等只使旧的原生 shader 实例失效（`discardNativeInstance`），下次绘制重建 SkShader 快照，不触发重新编译（[AOSP RuntimeShader.java](https://android.googlesource.com/platform/frameworks/base/+/master/graphics/java/android/graphics/RuntimeShader.java)；官方教程直接演示 ValueAnimator 每帧 `setFloatUniform("iTime", ...)` 的循环，[Using AGSL](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl)）。每帧数十个 float uniform 的 JNI 成本在微秒级。

**着色器编译与首帧卡顿。** HWUI 在绘制时把形状求值、裁剪、Paint shader、ColorFilter、混合与色彩空间转换**拼装成单个复合 GPU 片元着色器**，AGSL 效果只是其中一个函数（[AGSL 概览](https://developer.android.com/develop/ui/views/graphics/agsl)）。真正的 GL/Vulkan 程序编译发生在该组合首次用于绘制时（RenderThread 上），单个程序可达数十至数百毫秒（Flutter 对同一 Skia 机制的量化：[Shader compilation jank](https://liudonghua123.github.io/flutter_website/perf/shader/)）。HWUI 用**磁盘持久化 ShaderCache（BlobCache 存 SkSL/驱动二进制）**把这变成"每个 App 版本首次出现该组合时抖一次"（[AOSP ShaderCache.h](https://android.googlesource.com/platform/frameworks/base/+/master/libs/hwui/pipeline/skia/ShaderCache.h)、[ShaderCache.cpp](https://android.googlesource.com/platform/frameworks/base/+/master/libs/hwui/pipeline/skia/ShaderCache.cpp)）。**工程对策**：对话框首帧前用 1×1 px 把所有 RuntimeShader × BlendMode 组合各画一次做预热。另一个已知坑：`RenderEffect.createRuntimeShaderEffect` 仅支持单个内容输入、且比自定义 View 直接画更贵（官方文档原话"more expensive than drawing a custom View"，[Using AGSL](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl)；haze 曾因单输入限制重构其 liquid-glass 委托，[haze changelog](https://github.com/chrisbanes/haze/blob/main/CHANGELOG.md)）——**本方案应该用 Paint.shader + drawRect，避开 RenderEffect。**

**片元 ALU 预算（中端 GPU 能否扛住 9 层合成）。** ARM 官方预算公式：`fragCycleBudget = 核心数 × 频率 / (FPS × 像素数)`；示例：Galaxy S7（Mali-T880 MP12）1080p60 约 **63 cycles/px**，入门级设备做 1080p60 时"每像素周期数很少，必须精打细算"（[ARM: GPU Processing Budget Approach](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/gpu-processing-budget-approach-to-game-development)；[ARM GPU Best Practices](https://developer.arm.com/documentation/101897/0302/Fragment-shading/Blending)——层数每加一层、每像素成本线性累积）。代入典型档位（视图像素随屏幕分辨率缩放，280×420dp 在 720p 屏约 0.47M px，1080p 屏约 0.8M px，1.5K 旗舰屏约 1.5M px）：
- **Mali-G57 MC2 @950MHz**（Dimensity 700/G99/Helio G99，2021–2025 出货量极大的入门中端，[Notebookcheck 规格](https://www.notebookcheck.net/ARM-Mali-G57-MP2-GPU-Benchmarks-and-Specs.537758.0.html)）：1.9G core-cycles/s。只算水面视图：720p 屏 0.47M px@60 → **约 67 cycles/px**；1080p 屏 0.8M px@60 → **约 40 cycles/px**（还要扣掉同帧其余 UI）。9 层合成（每层 lerp+合成+梯度投影约 8–15 ALU）+ Schlick `pow(5)` 展开 + LUT 改为解析 `toLinearSrgb/fromLinearSrgb`（AGSL 内建，[RuntimeShader 参考](https://developer.android.com/reference/android/graphics/RuntimeShader)）合计约 80–150 fp32 cycles/px——**超预算，必须用 half 精度（Mali 上 fp16 吞吐翻倍）、把与 X 无关的 per-row 量预烘进 216 宽数据行、或对该 pass 用 0.5–0.75x 中间层再放大**（haze 实测 `inputScale=0.5` 省 5–20%，[haze Performance](https://chrisbanes.github.io/haze/latest/performance/)）。
- **Adreno 710**（SD 7s Gen 2 / 6 Gen 1，2023–2026 中端，800MHz，[Notebookcheck](https://www.notebookcheck.net/Qualcomm-Adreno-710-Benchmarks-and-Specs.795463.0.html)）：3DMark Wild Life 约 2956 vs G57 MP2 约 1202 → 预算约 2.5×，1080p 屏上该着色器可直跑 60fps。
- **旗舰（Adreno 750/830、Immortalis-G720+）**：相对 G57 MC2 有 >10× 吞吐，1.5M px 视图 9 层合成余量充足。系统级佐证：Android 13 起系统的 ripple、blur、stretch overscroll 本身就是全屏 RuntimeShader 效果并常年跑在 90/120Hz（[Android 13 first preview blog](https://android-developers.googleblog.com/2022/02/first-preview-android-13.html)；[Chet Haase: AGSL Made in the Shade(r)](https://medium.com/androiddevelopers/agsl-made-in-the-shade-r-7d06d14fe02a)）。
- 学术量化对照：Uppsala 大学 2023 论文《Android Runtime Shader Performance Analysis》对 RuntimeShader 与 OpenGL ES 做了成对实验，结论是**图像效果与粒子系统等场景下 RuntimeShader 性能不劣于甚至优于对应 GLES 实现**，且可定制性更好（[论文 PDF](https://uu.diva-portal.org/smash/get/diva2:1806968/FULLTEXT01.pdf)）。

**60fps 是否现实？** 旗舰：是，且有富余。2023 年后的主流中端（Adreno 7xx / Mali-G68+）：是，注意 half 精度与预烘。2021–2023 入门中端（Mali-G57 MC2 类）：临界，需上面三项手段其一或其二。收益端：**CPU 逐顶点配色（当前最贵的 CPU 项）整体归零**，UI 线程只剩填 43KB 缓冲 + uniform。

---

## 4. android.graphics.Mesh + Canvas.drawMesh（API 34+）

**能力。** `Mesh(MeshSpecification, mode, vertexBuffer, vertexCount, [ShortBuffer indexBuffer], bounds)`，mode 仅 `TRIANGLES`/`TRIANGLE_STRIP`，经 `Canvas.drawMesh(mesh, blendMode, paint)` 绘制；`MeshSpecification.make(attributes, vertexStride, varyings, vertexShader, fragmentShader)` 用 AGSL 写**顶点 + 片元两级**：顶点着色器 `Varyings main(const Attributes)`，片元 `float2 main(const Varyings, out float4 color)`，输出色再与 Paint/BlendMode 合成（[Mesh 参考](https://developer.android.com/reference/android/graphics/Mesh)；[MeshSpecification 参考](https://developer.android.com/reference/android/graphics/MeshSpecification)）。**硬限制（官方文档明列）**：属性最多 8 个、偏移 4 字节对齐、stride 最大 1024 字节、varying 最多 6 个；属性类型 float/float2/float3/float4/ubyte4（[MeshSpecification remarks，MS Learn 镜像全文](https://learn.microsoft.com/en-us/dotnet/api/android.graphics.meshspecification?view=net-android-35.0)）。uniform 支持 float/int/color 系列 setter，语义与 RuntimeShader 相同（[AOSP Mesh.java](https://android.googlesource.com/platform/frameworks/base/+/master/graphics/java/android/graphics/Mesh.java)）。

**与本工作负载的契合度。** 恰好是"把逐顶点 CPU 数学搬进顶点着色器"的官方通道：顶点缓冲只放 (x, z, 波包相位/振幅索引) 等原始字段，顶点着色器算高度与透视投影，片元着色器做 9 层配色 + Fresnel——CPU 每帧只需写一块 float 缓冲。**但有三处现实摩擦**：
1. **顶点数据不可就地更新**：Mesh 没有任何 update 接口，动画几何须每帧 new Mesh（缓冲在构造时被原生侧取用），带来每帧分配与 GC 压力——与 §1 要消灭的问题同类（[AOSP Mesh.java](https://android.googlesource.com/platform/frameworks/base/+/master/graphics/java/android/graphics/Mesh.java)）。
2. **API 34+ 且无向下模拟**：截至 2026 年上半年 Android 14+（API 34+）累计份额约五成（Android 14 约 17–24%、15 约 19%、16 约 7.5%；[apilevels.com](https://apilevels.com/)，[2026-01 分布报道](https://www.androidheadlines.com/2026/01/android-version-distribution-numbers-2025-2026-market-share.html)）——minSdk 26 的应用需要维护 Canvas 与 Mesh 双渲染器，而 AGSL 全通道方案只需沿用你已有的 API<33 回退。
3. **生态与实证稀少**：公开基准几乎没有；最有分量的采用信号是 Jetpack Compose 新增的 `MeshGradientPainter` 官方网格渐变（[Compose Mesh gradients 文档](https://developer.android.com/develop/ui/compose/graphics/draw/mesh-gradient)）与社区网格渐变实践（[sinasamaki: Mesh Gradients in Jetpack Compose](https://www.sinasamaki.com/mesh-gradients-in-jetpack-compose/)，Compose 侧长期用 drawVertices 实现同类效果）。官方 AGI 文档给出的着色器优化原则（把计算从片元挪到顶点、顶点输出用 mediump）与该 API 设计动机一致（[Analyze shader performance](https://developer.android.com/agi/frame-trace/shader-performance)）。

**判定**：架构上最优、工程上最不成熟。作为 2027 年 minSdk 升到 29+/34 覆盖率过 70% 之后的演进方向合适；现在作为主方案风险偏高。

---

## 5. OpenGL ES 迁移（GLSurfaceView / TextureView + EGL 独立渲染线程）

**对话框内 SurfaceView 的固有问题。** SurfaceView 不走 View 合成：SurfaceFlinger 直接合成其独立 layer，默认置于 App 窗口**之后**靠"挖洞"透出（[AOSP: SurfaceView and GLSurfaceView](https://source.android.com/docs/core/graphics/arch-sv-glsv)）。后果：
- z-order 只能整层调（`setZOrderOnTop`/`setZOrderMediaOverlay`，且必须在窗口 attach 前设置，[SurfaceView.setZOrderOnTop 文档](https://learn.microsoft.com/en-us/dotnet/api/android.views.surfaceview.setzorderontop?view=net-android-34.0)），置顶后窗口内任何内容都盖不住它——对话框的背景暗化、圆角卡片、滚动遮挡关系都会被破坏。
- 圆角/透明裁剪不作用于独立 layer：给 SurfaceView 做圆角要么叠一层带圆角挖孔的遮罩 View，要么在 GL 里把内容画到圆角几何上（[Rounded video corners on Android](https://medium.com/@fabrantes/rounded-video-corners-on-android-3467841cc1b)）。
- 对话框出入场动画期间独立 layer 与窗口动画不同步，出现黑闪/错位是长期已知问题（[processing-android #570：SurfaceView 黑闪](https://github.com/processing/processing-android/issues/570)；[ExoPlayer #7414：滚动容器中 SurfaceView 位置滞后](https://github.com/google/ExoPlayer/issues/7414)）。
- 生命周期：surface 的创建/销毁与 Activity/Dialog 生命周期不严格对齐，渲染线程必须处理 `onPause` 后 surface 仍存活等错位（[AOSP arch-sv-glsv](https://source.android.com/docs/core/graphics/arch-sv-glsv)）。

**TextureView 的代价。** 行为等同普通 View（可动画、可裁剪、可进对话框），但内容先渲进 SurfaceTexture 再由 HWUI 当纹理合成——**多一次拷贝、延迟更高、功耗更高**：media3 官方文档明确"SurfaceView 功耗显著更低，TextureView 播放视频总功耗最多高 30%，应优先 SurfaceView"（[media3 Surface types](https://developer.android.com/media/media3/ui/surface)；[media3 Battery consumption](https://developer.android.com/media/media3/exoplayer/battery-consumption)；Android 团队亦在推动 TextureView→SurfaceView 迁移，[Android Developers Medium](https://medium.com/androiddevelopers/android-hdr-migrating-from-textureview-to-surfaceview-part-1-how-to-migrate-6bfd7f4b970e)）。**在 DialogFragment 里若上 GL，2025/2026 年的现实选择是 TextureView + 自管 EGL**（Muzei 的 GLTextureView 模式，见 §8），接受拷贝与功耗代价。

**GL 相对 HWUI+AGSL 还能买到什么？**
- 顶点着色器与实例化：Mesh API（§4）已把顶点着色器带进 HWUI；AGSL 全通道则根本不需要顶点级并行。GL 独有的只剩 instancing、MRT、compute（本负载用不上）。
- MSAA 控制：本负载以填充/渐变为主，无几何锯齿刚需。
- **独立渲染线程与自主帧节奏（eglSwapBuffers 自定节拍 vs Choreographer）**：这是 GL 真正的差异项，但官方游戏循环文档同时警告"queue stuffing"节拍的缺陷并推荐 Choreographer 或 Frame Pacing 库（[Game loops](https://developer.android.com/games/develop/gameloops)；[Frame Pacing library](https://developer.android.com/games/optimize/frame-pacing)）；而 §6 的 worker-thread 模式在 HWUI 内即可拿到"模拟不阻塞 UI 线程"的主要收益。
- 成本清单：EGL 初始化/销毁、context loss 恢复、与 HWUI 零互操作（阴影、圆角、主题、无障碍全部自理）、双语言着色器维护。GLSurfaceView 帮你管线程与 EGL，但它是 SurfaceView（对话框问题全套继承）（[AOSP arch-sv-glsv](https://source.android.com/docs/core/graphics/arch-sv-glsv)）。
- **Vulkan/ANGLE 相关性**：2025 GDC 起 Vulkan 成为 Android 官方图形 API，新设备逐步以 ANGLE 承载 GLES（GL→Vulkan 转译），Android 16 起加速、Android 17 起强制化（有豁免）（[OSnews 汇总](https://www.osnews.com/story/141929/google-makes-vulkan-the-official-graphics-api-for-android/)；[androidheadlines: Android 16 Vulkan 要求](https://www.androidheadlines.com/2025/04/android-16-vulkan-api.html)；[官方 Vulkan 指南](https://developer.android.com/games/develop/vulkan/overview)）。**含义**：直接写 Vulkan 对一个 280×420dp 的对话框视图完全过度；新写 GLES 则是在向一条"经 ANGLE 转译维持"的路径投资。HWUI（内部已走 Vulkan/未来 Graphite）反而是受平台演进保护最好的那一层。

**判定**：除非视觉目标扩展到 HWUI 表达不了的程度（体积水体、后处理链、粒子百万级），GLES 迁移的收益覆盖不了对话框集成 + 生命周期 + 维护的成本。

---

## 6. 不用 GL 的解耦模式（worker 线程模拟 + UI 线程纯提交）

**模式 A（推荐首选）：producer/consumer 双缓冲。** 官方游戏循环文档给出的正是"逻辑线程按固定步长推进状态、渲染侧加锁取快照绘制"的双线程模板，并强调渲染侧只做快速快照、不做复杂计算（[Game loops](https://developer.android.com/games/develop/gameloops)）。落到本负载：worker 线程跑 120Hz 波动方程子步 + 方向波场采样 + 逐顶点配色，产出物是**已填好的 `float[]` 顶点数组、`int[]` 颜色数组、Path、以及 F16 数据位图**；onDraw 只 `drawVertices`/`drawRect` 提交。要点与坑：
- 用 2–3 份预分配缓冲轮换 + `AtomicReference` 交换"最新完成帧"，绘制端永远读整份快照——避免半更新撕裂；你已有的 3 帧位图池就是同一纪律，扩展到所有共享数组即可。
- 延迟增加恒定一帧以内（worker 在 vsync N 产出、UI 在 N+1 提交），对音频驱动动画不可感知。
- Bitmap 仍要防"RenderThread 在读、worker 在写"：位图池保持 3 深度（display list 只持引用不拷贝像素，§3 已述）。
- 每帧分配清零后，GC 造成的偶发长帧同步消失（[Slow rendering: allocation→GC](https://developer.android.com/topic/performance/vitals/render)）。

**模式 B：RenderNode 预录制。** `RenderNode` 是公开的保留式 display list（API 29+）：内容不变时零重录，变换/alpha 属性动画不触发重录；官方文档同时给出线程规则——"可在任意线程创建使用但非线程安全，**必须与消费它的线程同线程使用**；配合自定义 View 时只能在 UI 线程"（[RenderNode 参考](https://developer.android.com/reference/android/graphics/RenderNode)）。对本负载价值有限（几乎所有内容每帧都变），但可以把**静态部分**（天空渐变、抖动包装、静态遮罩）固化成子 RenderNode 免重录。
**模式 C：SurfaceView + `Surface.lockHardwareCanvas()` 后台线程硬件 Canvas。** API 23+ 公开；返回硬件加速 Canvas（AGSL、drawVertices 照用），**每次必须整面重绘（缓冲不保留、不支持局部更新）**，自管 Choreographer 节拍；RenderNode 文档明确认可"后台线程 lockHardwareCanvas + 重绘根 RenderNode + unlockCanvasAndPost"的组合（[Surface.lockHardwareCanvas 文档](https://learn.microsoft.com/en-us/dotnet/api/android.views.surface.lockhardwarecanvas?view=net-android-35.0)；[RenderNode 参考](https://developer.android.com/reference/android/graphics/RenderNode)；[AOSP arch-sh](https://source.android.com/docs/core/graphics/arch-sh)）。它把模拟**和**GPU 指令生成都搬离 UI 线程，代价是重新引入 §5 的 SurfaceView-在对话框内全部问题。**判定：模式 A 收益/风险比最好；C 只有当模式 A 后 RenderThread 仍超载时才考虑，且对话框场景基本排除。**

---

## 7. 帧节奏与 120Hz

**postInvalidateOnAnimation 会跟着显示模式跑到 120Hz。** Choreographer 回调按 vsync 派发：120Hz 模式下每 8.3ms 一次，"若应用每个 vsync 都完成渲染即得 120fps"（[Choreographer 机制详解](https://androidperformance.com/en/2019/10/22/Android-Choreographer/)；[Perfetto: Choreographer 渲染流](https://androidperformance.com/en/2025/03/26/Android-Perfetto-05-Chorergrapher/)）。invalidate 驱动的动画循环在高刷屏上默认以设备当前刷新率运行——**CPU/GPU 成本按刷新率线性放大**，这正是"UI 线程数学已接近 8ms"时在 120Hz 设备上必掉帧的机制。SurfaceFlinger 会按各 layer 的投票选全局刷新率（24 与 60 并存时可选 120 这类倍数解），并有 touch boost/idle 计时器等启发式（[AOSP Multiple refresh rate](https://source.android.com/docs/core/graphics/multiple-refresh-rate)；[High refresh rate rendering on Android](https://android-developers.googleblog.com/2020/04/high-refresh-rate-rendering-on-android.html)——同文说明平台会用 render-ahead 把 90Hz 管线加深到约 21ms 缓解单帧超窗）。

**把这个 View 钉在 60Hz 的可用 API（按覆盖面排序）：**
1. **自节流（全版本可用，唯一保证生效）**：Choreographer 回调里按 frameTimeNanos 累积，不足 16.6ms 直接跳过本帧的模拟与 invalidate。设备仍跑 120Hz（其余 UI 更顺），本 View 只花 60Hz 的成本。
2. **`WindowManager.LayoutParams.preferredRefreshRate`（API 21+）**：设在**对话框自己的 Window** 上（Dialog 有独立 Window），请求整窗 60Hz；被 `preferredDisplayModeId` 覆盖时失效，低电量模式下系统本就限 60（[WindowManager.LayoutParams 文档](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)；[Frame rate 指南](https://developer.android.com/media/optimize/performance/frame-rate)）。注意这会把对话框整窗拉到 60Hz。
3. **`Surface.setFrameRate` / `SurfaceControl.Transaction.setFrameRate`（API 30+）**：向 SurfaceFlinger 表达意图，平台综合裁决（[2020 官方博客](https://android-developers.googleblog.com/2020/04/high-refresh-rate-rendering-on-android.html)；[AOSP Multiple refresh rate](https://source.android.com/docs/core/graphics/multiple-refresh-rate)）。普通 View 不直接持有 Surface，实操上仍走 2 或 1。
4. **`View.setRequestedFrameRate`（ARR，Android 15 QPR1+ 且需设备 HAL 支持，`Display.hasArrSupport()` 探测）**：View 级投票 60Hz/`CATEGORY_NORMAL`；投票不向子 View 传播；touch boost 会临时抬高，可用 `Window.setFrameRateBoostOnTouchEnabled(false)` 关闭本窗 touch boost（[Adaptive refresh rate 官方指南](https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate)）。2026 年中真正具备 ARR 的设备仍是少数，**不能作为唯一手段**。
**结论**：无条件先做 1（几行代码、全版本、确定性），叠加 2 作为窗口级提示；ARR API 作为 API 36+ 的锦上添花。顺带：模拟步长必须继续与 frameTimeNanos 解耦（你已用 120Hz 固定子步，正确），否则 60/120 切换会改变波速。

---

## 8. 业界先例

- **Android 系统自身（最强先例）**：Android 13 起 ripple、blur、stretch overscroll 由 RuntimeShader 实现，全屏、常开、跑在 90/120Hz 设备上——证明"全覆盖 AGSL 片元 pass 在 HWUI 内达实时帧率"是平台自己在生产使用的路径（[Android 13 preview blog](https://android-developers.googleblog.com/2022/02/first-preview-android-13.html)；[Chet Haase: AGSL Made in the Shade(r)](https://medium.com/androiddevelopers/agsl-made-in-the-shade-r-7d06d14fe02a)）。
- **JetLagged（官方 Compose 样板）**：睡眠 App 背景是逐帧 time-uniform 驱动的 AGSL 波浪，API<33 回退为静态渐变——与本项目"AGSL + Canvas 回退"的结构完全同构（[android/compose-samples JetLagged](https://github.com/android/compose-samples/tree/main/JetLagged)；实践复盘 [droidcon 2023](https://www.droidcon.com/2023/05/02/fancy-animated-ui-with-agsl-shaders-in-jetpack-compose/)）。
- **haze（Compose 多平台毛玻璃库，生产广泛使用）**：Android 上用 RenderEffect/RuntimeShader，性能页给出 Pixel 6 基准与 `inputScale=0.5` 降载 5–20% 的实测——"AGSL pass 降分辨率中间层"有生产验证（[haze Performance](https://chrisbanes.github.io/haze/latest/performance/)；[repo](https://github.com/chrisbanes/haze)）。
- **Muzei（Roman Nurik 的动态壁纸）**：壁纸端 GLES；**App 内预览用自研 GLTextureView（TextureView + 自管 EGL 线程）**而非 GLSurfaceView——正是"GL 内容要参与 View 层级/动画时选 TextureView"的经典决策（[muzei/muzei](https://github.com/muzei/muzei)；[GLTextureView.java](https://github.com/romannurik/muzei/blob/master/main/src/main/java/com/google/android/apps/muzei/render/GLTextureView.java)）。
- **Shader Editor（markusfisch）**：GLSL 实时预览 + 动态壁纸，GLSurfaceView + GLES20 渲染器的教科书结构；它是全屏应用，不需要对话框集成，所以 GLSurfaceView 的限制不成立障碍（[markusfisch/ShaderEditor](https://github.com/markusfisch/ShaderEditor)，[ShaderView.java](https://github.com/markusfisch/ShaderEditor/blob/master/app/src/main/java/de/markusfisch/android/shadereditor/widget/ShaderView.java)）。
- **projectM（Milkdrop 系音频可视化）**：native GLES 2/3 渲染，JNI 集成，全屏可视化场景——代表"重型可视化上 GL"的档位，其复杂度（预设编译、逐帧 FFT 纹理、后处理链）远超本水面（[projectM examples-android](https://github.com/projectM-visualizer/examples-android)；[projectm](https://github.com/projectM-visualizer/projectm)）。
- **AGSL vs GLES 的直接测量**：Uppsala 论文（§3）结论 RuntimeShader 在图像效果/粒子实验中不劣于 GLES（[PDF](https://uu.diva-portal.org/smash/get/diva2:1806968/FULLTEXT01.pdf)）；社区 AGSL 集合（[drinkthestars/shady](https://github.com/drinkthestars/shady)、[AGSL-Playground](https://github.com/Carrieukie/AGSL-Playground)）在中端机顺跑多种全屏效果。**模式总结**：全屏重型可视化/壁纸 → GL（自有 surface，无集成问题）；嵌在 View/对话框层级里的效果 → AGSL/HWUI；没有找到任何"对话框内 GLSurfaceView"的成功先例，反而 Muzei 用 GLTextureView 绕开。

---

## 9. 五方案对比

预计帧耗按两档设备给出：旗舰=SD 8 Gen 3 级（视图约 1.5M px）；中端=Dimensity 700/G99、SD 6 Gen 1 级（视图约 0.5–0.8M px）。均指本 View 增量成本，UI=UI 线程，W=worker，RT=RenderThread，GPU=片元+顶点。

| 维度 | A. 留守 Canvas+局部优化（分配治理+60Hz 自节流，UI 线程不动架构） | B. Worker 线程解耦（producer/consumer，onDraw 纯提交） | C. 全通道 AGSL（单片元 pass，数据经 F16 输入缓冲） | D. Mesh API 34（顶点+片元 AGSL） | E. GLES（TextureView+EGL 线程） |
|---|---|---|---|---|---|
| 预计帧耗（旗舰） | UI 3–6ms + RT 1–2ms + GPU <1.5ms：60fps 基本达标，倾斜峰值仍可能超窗 | UI <1.5ms + W 3–6ms（并行）+ GPU <1.5ms：60fps 稳 | UI <1ms + GPU 1–2.5ms：60fps 稳，120Hz 亦可 | UI 1–2ms（重建 Mesh）+ GPU 1–2ms：60fps 稳 | 渲染线程 2–4ms + GPU 1–2ms + TextureView 合成拷贝：60fps 稳但功耗最高 |
| 预计帧耗（中端） | UI 8–15ms：**继续掉帧**（单核约旗舰 40–45%） | UI 2–3ms + W 6–12ms 并行：60fps 大体稳，W 单核饱和是新上限 | GPU 4–9ms（half+预烘后 3–6ms）：60fps 临界偏可行；CPU 压力消失 | GPU 3–6ms + 每帧 Mesh 重建分配：可行 | 同 C 的 GPU + 30% 级额外功耗与拷贝：可行但最费电 |
| 视觉上限 | 现状即上限（顶点色 8bit 插值、逐层 draw 叠加） | 同 A（纯性能改造） | 高：逐像素光照/折射/色散随写随加；受限于片元预算 | 最高（HWUI 内）：顶点位移+逐像素光照兼得 | 最高（无 HWUI 约束），但本场景用不到超出 D 的部分 |
| 工程成本 | 低（1–2 天） | 中低（3–5 天：缓冲协议+线程+回归） | 中（1–2 周：着色器重写配色栈+预热+双路回归） | 中高（2–3 周：双渲染器长期并存） | 高（3 周+：EGL/生命周期/对话框集成/无障碍/持续维护） |
| API 覆盖（minSdk 26） | drawVertices 硬件路径仅 29+（26–28 需软件层，需核实现状） | 同 A，无新增 API | 33+ 用 AGSL（2026 年中约 60–65%），<33 沿用现有 Canvas 回退，**回退已存在** | 34+（约 45–50%），26–33 必须整套 Canvas 渲染器并行维护 | 26+ 全覆盖（GLES2），但平台方向已转 Vulkan/ANGLE |
| 主要风险 | 治标不治本：中端与倾斜场景无解 | 撕裂/串帧（缓冲纪律）、双线程调试 | 入门 Mali 片元预算、首用编译抖动（可预热）、F16 输入缓冲行为的设备差异 | 生态新、几乎无公开实证；每帧 Mesh 重建分配；双渲染器 | 对话框 z-order/圆角/动画（SurfaceView）或拷贝+功耗（TextureView）；context loss；与 HWUI 零互操作 |

依据：帧预算与线程分工（[vitals](https://developer.android.com/topic/performance/vitals/render)、[androidperformance](https://androidperformance.com/en/2025/08/02/Android-Perfetto-07-MainThread-And-RenderThread/)）；单核差距（[nanoreview](https://nanoreview.net/en/soc/qualcomm-snapdragon-8-gen-3)）；GPU 预算（[ARM budget blog](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/gpu-processing-budget-approach-to-game-development)）；drawVertices GPU 路径（[skia-discuss](https://groups.google.com/g/skia-discuss/c/EtCEJkF7CKE)、[支持表](https://developer.android.com/topic/performance/hardware-accel)）；AGSL 系统级使用（[Android 13 blog](https://android-developers.googleblog.com/2022/02/first-preview-android-13.html)）；Mesh 限制（[MeshSpecification](https://learn.microsoft.com/en-us/dotnet/api/android.graphics.meshspecification?view=net-android-35.0)）；SurfaceView/TextureView 取舍（[AOSP](https://source.android.com/docs/core/graphics/arch-sv-glsv)、[media3](https://developer.android.com/media/media3/ui/surface)）；API 份额（[apilevels.com](https://apilevels.com/)）。

---

## 10. 推荐

**推荐：B → C 两阶段混合；不迁 GLES；Mesh 列为观察项。**

**Phase 1（立即，低风险）：worker 线程解耦 + 帧率纪律。**
1. 把 120Hz 物理子步、方向波场采样、逐顶点配色、光学元素几何全部移入单一 worker 线程，产出不可变帧快照（预分配 float[]/int[]/Path/F16 位图，3 缓冲轮换）；onDraw 退化为纯提交。这直接消除"倾斜→采样窗口变宽→UI 线程超窗"的因果链，且与官方游戏循环模板一致（[Game loops](https://developer.android.com/games/develop/gameloops)）。
2. Choreographer 自节流锁 60Hz + 对话框 Window 设 `preferredRefreshRate=60`，杜绝 120Hz 设备上的成本翻倍（§7）。
3. 分配审计：draw 路径零 new（[vitals](https://developer.android.com/topic/performance/vitals/render)）；顺手核实 API 26–28 上 drawVertices 的实际行为（§2）。
预期：旗舰稳 60；中端大幅改善——瓶颈从"UI 线程必然超窗"变为"worker 单核是否装得下"，装不下时 Phase 2 接手。

**Phase 2（结构性上限提升）：把连续水面 + 逐顶点配色收进一个 AGSL 全通道 pass（API 33+）。**
- 高度场/轮廓行 + 9 层调色板打进 216 宽 F16 数据位图（保留 3 帧池），片元内完成层合成 + Fresnel + 纵向光；half 精度、`toLinearSrgb/fromLinearSrgb` 替代 LUT、per-row 量预烘；对话框首帧前预热编译（§3）。光学小元素（珠光、辉点）可继续用现有 Canvas 叠画，减少一次性重写面积。
- API<33 沿用现有 Canvas 路径——该回退本来就必须存在，不新增维护面。
- 中端 Mali-G57 类若实测超窗：该 pass 降 0.75x 中间层（haze 已验证此手段），或减层。
理由：这是把 CPU 配色成本归零、同时把视觉上限（逐像素光学）打开的最短路径；它完全躺在 HWUI/平台演进（Vulkan/Graphite/ANGLE）的保护伞下，且有 Android 系统效果、JetLagged、haze 三类生产先例（§8）。

**不推荐 GLES**：对话框宿主 + 280×420dp 的尺寸让 SurfaceView 的 z-order/圆角/动画问题与 TextureView 的拷贝/功耗代价都无法被"顶点着色器+独立线程"的收益覆盖——这两项收益分别被 Mesh/AGSL 与 Phase 1 worker 线程以低得多的成本拿到（§5）。**Mesh API 34**：等 minSdk 覆盖与生态成熟后再评估，届时它是 Phase 2 的自然升级（顶点位移也上 GPU，CPU 只剩音频参数）。

---

## 附：核心资料来源

- 帧预算/jank 成因：https://developer.android.com/topic/performance/vitals/render ；https://androidperformance.com/en/2025/08/02/Android-Perfetto-07-MainThread-And-RenderThread/ ；https://androidperformance.com/en/2019/11/06/Android-Systrace-MainThread-And-RenderThread/
- 硬件加速支持表（drawVertices=API29、位图重上传、draw 内勿分配）：https://developer.android.com/topic/performance/hardware-accel
- drawVertices 语义与 GPU 路径：https://api.skia.org/classSkCanvas.html ；https://groups.google.com/g/skia-discuss/c/EtCEJkF7CKE ；https://android.googlesource.com/platform/frameworks/base/+/333321e/core/jni/android/graphics/SkiaCanvas.cpp ；https://proandroiddev.com/playing-with-android-canvas-drawvertices-32266c480ab6 ；https://www.youtube.com/watch?v=pD38Yyz7N2E
- Skia 纹理缓存 genID 机制：https://groups.google.com/g/skia-discuss/c/5MWTlm-n_yo
- AGSL/RuntimeShader：https://developer.android.com/develop/ui/views/graphics/agsl ；https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl ；https://developer.android.com/reference/android/graphics/RuntimeShader ；https://android.googlesource.com/platform/frameworks/base/+/master/graphics/java/android/graphics/RuntimeShader.java ；https://medium.com/androiddevelopers/agsl-made-in-the-shade-r-7d06d14fe02a ；https://android-developers.googleblog.com/2022/02/first-preview-android-13.html
- HWUI ShaderCache（编译抖动的平台缓解）：https://android.googlesource.com/platform/frameworks/base/+/master/libs/hwui/pipeline/skia/ShaderCache.h ；https://android.googlesource.com/platform/frameworks/base/+/master/libs/hwui/pipeline/skia/ShaderCache.cpp ；https://liudonghua123.github.io/flutter_website/perf/shader/
- GPU 周期预算：https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/gpu-processing-budget-approach-to-game-development ；https://developer.arm.com/documentation/101897/0302/Fragment-shading/Blending ；https://developer.android.com/agi/frame-trace/shader-performance ；https://www.notebookcheck.net/ARM-Mali-G57-MP2-GPU-Benchmarks-and-Specs.537758.0.html ；https://www.notebookcheck.net/Qualcomm-Adreno-710-Benchmarks-and-Specs.795463.0.html
- Mesh API：https://developer.android.com/reference/android/graphics/Mesh ；https://developer.android.com/reference/android/graphics/MeshSpecification ；https://learn.microsoft.com/en-us/dotnet/api/android.graphics.meshspecification?view=net-android-35.0 ；https://android.googlesource.com/platform/frameworks/base/+/master/graphics/java/android/graphics/Mesh.java ；https://developer.android.com/develop/ui/compose/graphics/draw/mesh-gradient ；https://www.sinasamaki.com/mesh-gradients-in-jetpack-compose/
- SurfaceView/TextureView/GL：https://source.android.com/docs/core/graphics/arch-sv-glsv ；https://source.android.com/docs/core/graphics/arch-sh ；https://developer.android.com/media/media3/ui/surface ；https://developer.android.com/media/media3/exoplayer/battery-consumption ；https://github.com/google/ExoPlayer/issues/7414 ；https://github.com/processing/processing-android/issues/570 ；https://medium.com/@fabrantes/rounded-video-corners-on-android-3467841cc1b ；https://medium.com/androiddevelopers/android-hdr-migrating-from-textureview-to-surfaceview-part-1-how-to-migrate-6bfd7f4b970e ；https://learn.microsoft.com/en-us/dotnet/api/android.views.surfaceview.setzorderontop?view=net-android-34.0
- Vulkan/ANGLE 方向：https://www.osnews.com/story/141929/google-makes-vulkan-the-official-graphics-api-for-android/ ；https://www.androidheadlines.com/2025/04/android-16-vulkan-api.html ；https://developer.android.com/games/develop/vulkan/overview ；https://blog.chromium.org/2025/07/introducing-skia-graphite-chromes.html
- 解耦模式：https://developer.android.com/games/develop/gameloops ；https://developer.android.com/reference/android/graphics/RenderNode ；https://learn.microsoft.com/en-us/dotnet/api/android.views.surface.lockhardwarecanvas?view=net-android-35.0
- 帧率/120Hz：https://androidperformance.com/en/2019/10/22/Android-Choreographer/ ；https://androidperformance.com/en/2025/03/26/Android-Perfetto-05-Chorergrapher/ ；https://android-developers.googleblog.com/2020/04/high-refresh-rate-rendering-on-android.html ；https://source.android.com/docs/core/graphics/multiple-refresh-rate ；https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate ；https://developer.android.com/reference/android/view/WindowManager.LayoutParams ；https://developer.android.com/media/optimize/performance/frame-rate
- 先例：https://github.com/android/compose-samples/tree/main/JetLagged ；https://www.droidcon.com/2023/05/02/fancy-animated-ui-with-agsl-shaders-in-jetpack-compose/ ；https://chrisbanes.github.io/haze/latest/performance/ ；https://github.com/muzei/muzei ；https://github.com/markusfisch/ShaderEditor ；https://github.com/projectM-visualizer/examples-android ；https://github.com/drinkthestars/shady ；https://uu.diva-portal.org/smash/get/diva2:1806968/FULLTEXT01.pdf
- 设备/份额：https://nanoreview.net/en/soc/qualcomm-snapdragon-8-gen-3 ；https://nanoreview.net/en/soc/qualcomm-snapdragon-6-gen-1 ；https://nanoreview.net/en/soc/mediatek-dimensity-7050 ；https://apilevels.com/
