# 逐帧成本专项：三家 GPU 的外部资料调研（2026-09-18）

承接 [research-2026-09-17-per-frame.md](research-2026-09-17-per-frame.md)（第一轮，主要按 Adreno 的事实
推理）与 [per-frame-batch4-2026-09-17.md](per-frame-batch4-2026-09-17.md)（华为 Maleoon 910 上压力投影
从未运行的缺陷修好之后，默认档位掉到 16/82 帧）。**本轮把范围扩到所有目标设备**，按三个厂商分支
并列检索，另加一节「交互到首帧」。

> 文件名：上一轮的任务书里写的是 `research-2026-09-18-mali.md`；范围扩大之后改成
> `research-2026-09-18-vendors.md`，与方案 [per-frame-plan2-2026-09-18.md](per-frame-plan2-2026-09-18.md)
> 对应。

**本文不含任何实测数字。** 凡标「预期」的都是按资料与代码事实推断的量级，必须由方案第一步的
真机分段归因确认。厂商文档里的性能表述绝大多数是定性的，本文一律标明；凡写了具体倍数的都注明
出处是哪一份规格文档的哪一条。

## 0 检索出发点：本管线与三家硬件的接触面

读过的代码（本轮新读或复核）：`shared/particle-dismiss/{step,peel-splat,peel-blur,peel-project,peel-region,draw-record}.comp`、
`material.{vert,frag}`、`resolve.frag`、`app/src/main/assets/particle-playback/{history,pressure-batched,pressure-batched-large,pressure-tiles}.comp`、
`ParticleMicroflakeRenderer.kt` 的 `render` / `writeDrawRecords` / `PeelPressure.update` / `Shared`。

与厂商差异直接相关的代码事实：

| # | 事实 | 位置 | 相关主题 |
|---|---|---|---|
| F1 | 累积目标是**全屏 RGBA16F**，每帧整屏 `glClear` + 开启混合 `ONE, ONE_MINUS_SRC_ALPHA` 画两遍，再用全屏三角形 resolve | `render()` L643–688 | 2.1、3.2 |
| F2 | `material.vert` 输出 **14 个 highp varying 分量**（`uv` 2、`local_uv` 2、`age/life/light/shape` 4、`surface` 1、`random` 4、`replica` 1） | `material.vert` L36–43 | 2.2 |
| F3 | 绘制是 `glDrawArraysInstanced(TRIANGLE_STRIP,0,4,count)`，**两个实例化顶点缓冲**（绘制记录 0–3、材料 4–5） | `render()` L656/L672 | 2.7 |
| F4 | 所有 compute 的工作组是 **256 线程**（`local_size_x=256` 或 `16×16`） | 全部 `.comp` | 2.3、3.5 |
| F5 | `pressure-batched.comp` 用 **4 × 1024 × 4 B = 16 KiB shared**，256 线程 + 每批 8 次 `barrier()`，32×32 载入只输出 16×16 | `pressure-batched.comp` | 2.4 |
| F6 | `peel-splat.comp` 每片 **12 次全局 `atomicAdd`**（4 格 × 3 分量，定点 ×4096） | `peel-splat.comp` L60–66 | 2.5 |
| F7 | 每帧 `glMemoryBarrier` 恒为 `SHADER_STORAGE \| SHADER_IMAGE_ACCESS \| TEXTURE_FETCH` **三位全开**，现场消散一帧约 21 次 | `PeelPressure.barrier()` L1129 | 2.8、3.6 |
| F8 | `step.comp` 的 `sample_field` 做**显式三线性**：8 个 `field_cell` × 2 张 3D 纹理 = **每次 16 次 `texelFetch`**；`main` 调 `guided_flow` 1–2 次 ⇒ **每步最多 32 次 3D 取样**，每帧 4 步 | `step.comp` L51–66、L106–107 | 2.6、5.1 |
| F9 | 全部着色器顶部是 `precision highp float/int/sampler2D/sampler3D`，**没有任何 `mediump`** | 全部 | 2.2、2.6、3.1 |
| F10 | 压力修正场是 **RGBA32F 的 2D 纹理**，只用 `.xy`；`sample_peel` 每次 4 个 `texelFetch` | `Shared` L1047、`step.comp` L18–24 | 2.6、5.2 |
| F11 | 已有 `Shared.adreno()`（按 `GL_RENDERER` 判一次）与 `program(..., defines=)`，**厂商分支的着色器变体是现成的机制** | `ParticleMicroflakeRenderer.kt` L1419–1427、L1321 | 全部 |
| F12 | 逐次 Jacobi（`peel-project.comp`，无 shared）与批处理 Jacobi（`pressure-batched.comp`，有 shared）**两条路径都在代码里**，由 `update(time, batched)` 选 | `PeelPressure.update` L1179–1186 | 2.4 |

---

# 一、Arm Mali / 华为 Maleoon

Maleoon 910 是海思自研，公开资料只到「4 核、OpenGL ES 3.2 / Vulkan 1.1 / OpenCL 2.0」这一层
（[Notebookcheck HiSilicon Maleoon 910](https://www.notebookcheck.net/HiSilicon-Maleoon-910-Benchmarks-and-Specs.803657.0.html)），
海思**没有公开架构白皮书**。可用的间接证据有三条，都指向 Mali 血统：

1. 扩展串里有 `GL_ARM_rgba8`、`GL_ARM_mali_shader_binary`、`GL_ARM_mali_program_binary`、
   `GL_ARM_shader_framebuffer_fetch_depth_stencil`（本项目实测，见
   [../dialog-particle-dismiss/mali-vertex-ssbo-2026-09-15.md](../dialog-particle-dismiss/mali-vertex-ssbo-2026-09-15.md)）；
2. `GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS = 0`、`MAX_COMPUTE_SHARED_MEMORY_SIZE = 32768`、
   工作组线程上限 1024 —— 与 Mali 的一贯取值一致；
3. 本项目实测的两处行为（跨阶段精度严格拒链接、向量分量原子不按下标寻址）在 Adreno 上都不复现。

**因此本节一律按「Arm 的规则适用、但每一条都必须在 Maleoon 上实测确认」处理**，不假设它与某一代
Mali 完全等同。所有 Arm 侧规则的出处是
[Arm GPU Best Practices Developer Guide, Rev 3.4 / Issue 10 (101897_0304_10_en)](https://documentation-service.arm.com/static/67a62b17091bfc3e0a947695)，
本轮把 PDF 原文取下来逐节读过；华为自己的一份对口文档是
[Maleoon GPU Rendering Optimization](https://developer.huawei.com/consumer/en/doc/best-practices/bpta-maleoon-gpu-best-practices)
（页面是 JS 渲染，`WebFetch` 取不到正文，只能通过搜索摘要间接引用，见第 9 节）。

## 1.1 混合 + 浮点渲染目标：Arm 的两条 Don't 同时被我们踩中

### 资料

Arm 第 7.9 节 Blending：

- Do：「Use blending on unorm formats, rather than floating-point values.」
- Don't：「Do not use blending on floating-point framebuffers.」
- 负面影响原文：「blending disables many of the important optimizations that remove fragment
  overdraw, for example, early ZS testing and FPK. The negative impact is especially noticeable
  for user interfaces and 2D games that use multiple layers of sprites.」
- 但同节也写了一条**缓和**：「From Bifrost onwards, float blending is enabled but in Valhall GPUs
  there is accelerated hardware blending for FP16 and R11G11B10 formats. Simple blends of those
  formats are accelerated, but advanced blends are not.」

Arm 第 7.7 节 HDR rendering：

- 「Use RGB10_A2 UNORM formats for rendering where small increases in dynamic range are required.」
- 「Use B10G11R11 for floating-point rendering as it is only 32bpp, compared to 64bpp with full fp16 float.」
- 「do not use RGBA16F unless: B10G11R11 is not providing suitable image quality; Alpha is a
  requirement in the framebuffer.」

### 对本管线的适用性

F1 正是「在浮点渲染目标上开混合、画上万层精灵」。三点判断：

1. **「混合关掉 FPK / early-ZS」这一条对我们没有额外损失**：粒子本来就是半透明叠加、深度测试已关，
   本来就没有隐面消除可言。这条不是可优化项。
2. **「浮点 vs unorm」这一条是真实的取舍**，而且 Arm 自己给了缓和条款：Valhall 起 FP16 的
   **简单**混合是硬件加速的，我们的 `ONE, ONE_MINUS_SRC_ALPHA` 正是简单混合。**Maleoon 是不是
   带这条加速路径，公开资料查不到，只能实测。**
3. **「Alpha is a requirement」这条豁免对我们成立**：`resolve.frag` 要 `p.rgb / p.a` 再做 sRGB 编码，
   alpha 不能丢，所以 `B10G11R11` 不可用。**但 `RGBA8` / `SRGB8_ALPHA8` 是可用的**，因为混合模式是
   预乘 over 而不是加法累积：`c' = c_src·a_src + (1−a_src)·c_dst`，只要 `c_src·a_src ≤ 1` 结果就留在
   `[0,1]`。`material.frag` 的 `color` 在高光处可以到 1.3–1.5，`color·alpha > 1` 只在 `alpha > 0.7`
   时发生，而那种情形下 resolve 里的 `clamp(...,0,1)` 本来也会削掉。**真正的代价是 8 位量化**（大量
   低 alpha 微片叠加时的条带），用 `SRGB8_ALPHA8` 可以把暗部有效精度提到约 12 位，而 Arm 第 8.7 节
   明确 sRGB 的采样与混合**不额外收费**。

**结论**：累积目标从 RGBA16F 换成 `SRGB8_ALPHA8` 是本节收益最大的候选——每像素 8 B → 4 B，混合从
浮点路径回到 unorm 路径，resolve 的采样量也减半。视觉等级 **V**（要看并排放大图，尤其看密集区的
条带），且对 Adreno 同样是带宽减半（Adreno 侧 bin 数量也会减半，见 3.2）。

| 项 | 内容 |
|---|---|
| 建议 | 做成可开关的累积格式（`RGBA16F` / `SRGB8_ALPHA8`），先在离屏对照里出并排放大图给用户定夺 |
| 收益 | 累积目标带宽减半；Mali 上从浮点混合路径回到 unorm；Adreno 上 bin 数量减半 |
| 风险 | V 级：低 alpha 叠加的条带；高光处 `color·alpha > 1` 的截断时机提前 |
| 验证 | 同快照同种子，两种格式各出 10% / 50% / 90% 三个进度点的整帧 + 4 倍放大裁剪；另落盘「累积纹理里 alpha 的直方图」判断量化档位是否够用 |

## 1.2 varying 的精度与数量：tile 架构上 varying 是写进主存的

### 资料

Arm 第 5.7 节 Varying precision 开头一句是硬事实：「Vertex shader outputs are commonly called
varying outputs. **They are written back to main memory before fragment shading commences.**」

- Do：「Use the mediump qualifier on varying outputs if the precision is acceptable.」
- Don't：「Do not use varyings with more components and precision than is needed.」「Do not use
  vertex shaders with outputs that are left unused in the fragment shader.」
- 可以用 mediump 的典型：法线、顶点色、**非平铺且不超过 512×512 的纹理坐标**；需要 highp 的典型：
  世界坐标、大纹理的坐标。

第 10.1 节还有一条量化表述：「highp can use up to twice the resources and halve the speed.」

### 对本管线的适用性

按 F2 逐个过一遍 14 个分量：

| varying | 能否 mediump | 依据 |
|---|---|---|
| `uv`（2） | **不能** | 前景纹理最大到卡片像素尺寸（全屏 3192 px）。fp16 在 `[0,1]` 上的间隔 2⁻¹¹ ≈ 4.9e-4，乘 3192 = **1.6 px**，直接可见 |
| `local_uv`（2） | 能 | 就是四角 `[0,1]`，只用于 `radial`/`cut` |
| `age_out` / `life_out`（2） | **`life_out` 可删，`age_out` 要留一个替身** | `material.frag` 用它们算 `fade`、四个 `smoothstep`、`facing`、`optical_loosen` —— **这些全是逐片量**，结果对一片的所有像素相同，应当在 `draw-record.comp` 里算好。**唯一的例外是 `facet`**：`sin(age*11.+dot(uv,vec2(7.,11.)))` 里有 `uv`，是逐像素的，所以要保留一个 `age*11.` 的 flat mediump 替身（值域 ≤ 3.3，fp16 步长 0.002，够用） |
| `light_out` / `shape_out`（2） | 能 | 值域小 |
| `surface_out`（1，flat） | 能 | `[0,1]` |
| `random_out`（4，flat） | **可降到 2，其中一处还能再折叠** | 启用 `DRAW_RECORDS` 之后 `material.frag` 只用 `.z`、`.w`：`radial` 里的 `vec2(.85+r.z*.35, .87+r.w*.34)` 是逐片量（可整体折成一个 mediump `vec2`），`facing=.5+.5*sin(age*16.+r.z*6.28)` 也是逐片量（可折成一个标量） |
| `replica_out`（1，flat） | 能，或打包进别的分量的符号位 | 只做 `>.5` 判定 |

**两步走**：

- **第一步（纯精度）**：`uv` 留 `highp`，其余 12 个分量改 `mediump`。varying 写出/读回的字节
  从 56 B/顶点降到约 32 B/顶点。G1。
- **第二步（把逐片量继续下沉）**：把 `fade`、`alpha` 的逐片乘子、`optical_loosen`、`facing`/`glint`、
  `lighting`、`radial` 的两个系数在 `draw-record.comp` 里算成 5 个 flat mediump 量；
  `life_out`、`random_out`（4 个分量）、`replica_out` 随之删除，`age_out` 换成 flat 的 `age*11.`
  替身（`facet` 里的 `uv` 是逐像素的，这一项删不掉）。
  结果是 `uv`(highp 2 = 8 B) + 10 个 mediump 分量（20 B）= **28 B/顶点**。
  这是第一批 A1 的**片元侧延伸**：A1 只搬了顶点数学，片元里同样有一大块逐片常量。

规模感：弹窗 10.7 万片 × 4 顶点 × 2 遍 = 85.6 万顶点/帧。56 B → 28 B 即每帧少写少读约 **24 MB × 2**。

| 项 | 内容 |
|---|---|
| 建议 | 分两次做：先 mediump（Maleoon 上必须先编译一次，跨阶段精度两侧要同时改），再把片元里的逐片量下沉到 `draw-record.comp` |
| 收益 | 每顶点 varying 从 56 B 降到 28 B（Mali 上是主存往返，Adreno 上过 GMEM/主存），另外片元 ALU 下降 |
| 风险 | Maleoon 对跨阶段精度不一致**直接拒绝链接**（已有记忆条目 `glsl-cross-stage-precision-strictness`），Adreno 宽容会掩盖；`uv` 误改会出现 1.6 px 的采样错位 |
| 验证 | 每一步单独 A/B；两台各编译一次；G1 判据两步走（先同路径自比较再比 A/B） |

## 1.3 工作组大小：Arm 的上限建议是 64，我们全线是 256

### 资料

Arm 第 9.2 节 Workgroup sizes：

- 背景：「The GPU hardware can split up, and then merge, workgroups... **If barriers or shared memory
  are used, then GPUs cannot do this**... all work items in the workgroup must be executed
  concurrently in the shader core.」「Large workgroup sizes restrict the number of registers that are
  available to each work item... forcing shader programs to use stack memory if insufficient
  registers are available.」
- 「Arm GPUs currently use subgroup sizes of 16 and smaller.」
- Do：「Use 64 as a baseline workgroup size.」「Use a multiple of 4 as a workgroup size.」
  「Try smaller workgroup sizes before larger ones, especially if using barriers or shared memory.」
  「When working with images or textures, use a square execution dimension, for example 8x8.」
  「Compute shader performance is not always intuitive, so keep measuring.」
- Don't：「**Do not use more than 64 threads per workgroup.**」「Do not assume that barriers with small
  workgroups are free from performance costs.」

### 对本管线的适用性

分两类：

**（a）「一线程一片」的核**：`step.comp`、`peel-splat.comp`、`draw-record.comp`、`history.comp`，
全是 `local_size_x=256`，**没有 `shared`、没有 `barrier()`**。改成 64 只要改一行加对应的
`glDispatchCompute` 除数。这几个核的寄存器压力恰恰很高（`step.comp` 有几十个中间量、
`material.vert` 那条链已经搬进 `draw-record.comp`），Arm 那条「大工作组挤掉寄存器 → 溢出到栈」
正对着它们。**这是本节最便宜的一项，而且逐位恒等**：每个线程做的事一个字不变，
`peel-splat` 的整数 `atomicAdd` 与顺序无关。

**（b）二维网格核**：`peel-blur.comp`、`peel-project.comp`（16×16，无 shared）可以直接改 8×8，
Arm 还专门建议图像类用方形 8×8。`pressure-tiles.comp` 的 16×16 与 `tiles[]` 的 tile 粒度、
活动区域的 16 格对齐**绑死**，改它要连带改 `peel-region.comp` 与 `peel-splat.comp` 的包围盒
量化，**不建议动**。`pressure-batched.comp` 见下一节。

| 项 | 内容 |
|---|---|
| 建议 | 用 `defines` 做厂商变体：非 Adreno 时 `local_size` 取 64（一维核）/ 8×8（`peel-blur`、`peel-project`），Adreno 保持 256 / 16×16 |
| 收益 | Mali 上寄存器压力下降、避免栈溢出；量级只能实测 |
| 风险 | 极低。`pressure-tiles.comp` 与活动区域对齐耦合，不在本项范围内 |
| 验证 | Adreno 侧因为走的是另一份变体，**逐位恒等**，用 `ParticleTrajectoryCheck` 的自比较即可；Mali 侧测离屏分段墙钟 |

## 1.4 shared 在 Mali 上不是片上内存：批处理 Jacobi 的前提在 Mali 上不成立

### 资料

Arm 第 9.3 节 Shared memory，第一句就是结论：

> 「Arm GPUs do not implement dedicated on-chip shared memory for compute shaders. The shared
> memory that is available to use is **system RAM that is backed up by the load-store cache**.」

- Do：「Keep your shared memory as small as possible, as it reduces the chance of thrashing the data
  cache.」「To reduce the size of the shared memory that is needed, reduce the precision and data
  widths.」「**It can be computationally cheaper splitting an algorithm over multiple shaders when
  compared to inserting barriers.**」「For barriers, smaller workgroups are less expensive.」
- Don't：「**Do not copy data from global memory to shared memory on Arm GPUs. Doing so pollutes the
  caches.**」

Arm 的 OpenGL ES SDK 另有一条与屏障相关的：`glMemoryBarrier()` 必须保证可见性，
「this effectively means that we have to flush out our scene to memory, which we really want to
avoid on tiled architectures」
（[Introduction to compute shaders](https://arm-software.github.io/opengl-es-sdk-for-android/compute_intro.html)）。

### 对本管线的适用性

`pressure-batched.comp`（F5）是**为 Adreno 的片上 LDS 设计的**：

- 每个工作组把 32×32 = 1024 格的压力、散度、占据度**从全局内存抄进 shared**（正是 Arm 那条 Don't），
- 用 4 × 1024 × 4 B = **16 KiB shared**（ES 3.1 的下限，Maleoon 给 32 KiB），
- 256 线程 + 每批 **8 次 `barrier()`**，
- 32×32 载入只产出 **16×16** 输出（4 倍冗余读）。

在 Adreno 上这笔买卖成立：LDS 是真片上，冗余读发生在片上，换来 dispatch 从 100 次降到 13 次。
**在 Mali 上三项前提逐条不成立**：shared 是主存 + L1，16 KiB × 并发工作组数会把 L1 冲垮；
barrier 不免费且大工作组更贵；4 倍冗余读变成真的 4 倍主存/缓存流量。

**而替代路径已经在代码里**（F12）：`peel-project.comp` 就是无 shared 的逐次 Jacobi，且**已经带
`ACTIVE_REGION` 早退**。现状只有 `batched && !large` 这条路径被活动区域驱动
（`PeelPressure.update` L1138），把逐次路径也接上活动区域是小改动。

代价是 dispatch 数与屏障数从 13 涨到 100（默认迭代数），而每次 `glMemoryBarrier` 在 tile 架构上
可能触发整场景 flush。**所以这不是「Mali 一定该换回逐次」，而是「Mali 上这两条路径必须实测对照」**，
而且对照几乎零成本——两条路径都已存在，只差把活动区域接上逐次路径。

还有第三个选项：保留批处理但**把 shared 减半**。Arm 说「reduce the precision and data widths」：
`divergence` 与 `occupied` 这两份（8 KiB）在 `pressure-batched-large.comp` 里已经改成**每线程的
寄存器数组**了，把同样的做法搬回 `pressure-batched.comp` 就能把 shared 从 16 KiB 降到 8 KiB，
两条压力各 4 KiB。这是**对两家都可能有益**的改动，且逐位恒等（同样的数、同样的顺序）。

| 项 | 内容 |
|---|---|
| 建议 | ① 先做「`divergence`/`occupied` 移出 shared」（G0，两家都可能受益）；② 把活动区域接到逐次 Jacobi 路径上，Mali 上做「批处理 vs 逐次」的离屏对照；③ `jacobiLargeTile`（32 KiB shared）在 Mali 上一律保持关闭 |
| 收益 | ① shared 16 → 8 KiB；② 若逐次胜出，Mali 上压力段的量级变化（左滑现场帧压力占 46%、预读占 87%，弹窗现场 21%） |
| 风险 | ② 逐次路径的屏障数从 13 涨到 100，可能整体更慢——这正是要实测的；活动区域接上去之后卡片内必须与批处理**逐位相同**（可证：同样的迭代式、同样的边界钳位） |
| 验证 | 离屏分段基准（`--segment-bench`）在两台上各跑一轮；逐位对照用 `ParticleTrajectoryCheck` 比「逐次 + 活动区域」与「逐次 + 全网格」 |

## 1.5 原子：contention 的口径与我们能做的

### 资料

Arm 第 10.13 节 Atomics：

- 「The key performance problem with atomics is contention. Atomic operations from different shader
  cores hitting the same cache line requires data coherency snooping through L2 cache, which is
  computationally expensive.」
- 「Atomics are efficient when a shader core controls the necessary cache line in its L1.」
- Do：「Consider spacing atomics 64 bytes apart to avoid multiple atomics contending on the same
  cache line.」「Consider whether it is possible to amortize the contention by accumulating into a
  shared memory atomic. Then, have one thread push the global atomic operation at the end of the
  workgroup.」
- Don't：「If better solutions that use multiple passes are available, then do not use atomics.」

另一侧的文献（上一轮已引）：MPM 的 P2G 实测**完全不做粒子排序反而快 8%–17%**
（[arXiv:2111.00699](https://arxiv.org/pdf/2111.00699)）。

### 对本管线的适用性

F6：每片 12 次全局原子，弹窗 10.7 万片 ⇒ 每次压力更新约 **128 万次**。

- **「shared 原子聚合」在本管线上不成立**，理由与上一轮相同：粒子按**源格子**编号分组，随 age 增大
  散到各处，工作组与目标格没有固定对应。而 Mali 的 shared 本来就是主存，聚合收益还要再打折。
- **「64 字节间隔」帮不上**：同一格的竞争是算法固有的。
- **能做的仍然只有减少原子次数**：双线性 splat（4 格 × 3 分量）→ **最近格 splat（1 格 × 3 分量）**，
  每片 12 → 3。splat 之后紧跟两遍 11 抽头高斯（σ ≈ 1.2 格），双线性额外提供的约 0.4 格平滑基本冗余。
  这条上一轮已列（主题 6），本轮的新增依据是 Arm 把 contention 明确归到 L2 snoop —— 在多核 GPU 上
  次数减少 4 倍就是 snoop 减少 4 倍。
- **一条本轮新发现的、几乎免费的**：`peel-splat.comp` 的 `pass==0` 清零走的是**整网格** dispatch
  （`(cells+255)/256`），注释里写「实测落在噪声里」——那是 Adreno 上的结论。在 Mali 上「每格写
  16 字节 × 12 万格」是 1.9 MB 的纯写入，值得单独量一次；若显著，改成只清活动区域（区域外恒为零，
  上一帧也已写零，不变式与 `peel-region.comp` 里那条一致）。

| 项 | 内容 |
|---|---|
| 建议 | 试「双线性 → 最近格」；另单独量一次「整网格清零」在 Mali 上的成本 |
| 收益 | 原子次数降到 1/4；清零若显著则再省一次全网格写 |
| 风险 | 最近格属 **V** 级（1 格量化，后续 σ≈1.2 格高斯吸收大部分） |
| 验证 | 先落盘两种 splat 的**压力场逐格差**（不是直接看画面），再出并排放大图 |

## 1.6 采样器精度与格式：3D 与 FP32 各是 2 倍代价，highp 采样器再乘 2

### 资料

Arm 第 8.4 节 Texture sampling performance，「需要额外周期的情形」：

- 「Trilinear, LINEAR_MIPMAP_LINEAR, filtering has a 2x cost.」
- 「**3D formats have a 2x cost.**」
- 「**FP32 formats have a 2x cost.**」
- Do：「Use the narrowest precision that still retains a level of texture quality that is acceptable.」
  「Use `texelFetch()` or `texture()` instead of the slower `imageLoad()` wherever possible.」
  「**Use mediump samplers. highp samplers can be half the speed due to their wider data path
  requirements.**」「If you need higher dynamic range, then consider using packed 32-bit formats.
  Such 32-bit formats include RGB10_A2 or RGB9_E5, as an alternative to FP16 or FP32 textures.」

### 对本管线的适用性 —— 这一节是 Mali 上最集中的一块

按 F8 算规模：每片每步最多 32 次 3D `texelFetch`，每帧 4 步 ⇒ 每片每帧最多 **128 次**；
弹窗 10.7 万片 ⇒ **每帧约 1370 万次 3D 取样**。而每一次都同时踩了「3D 格式 2×」与
「highp 采样器可能再 2×」。四条独立的减法：

| # | 做法 | 倍数 | 视觉等级 |
|---|---|---|---|
| a | `guide_field` / `confidence_field` 的采样器改 **mediump**。`texelFetch` 用整数坐标，坐标精度无关；数据本身是 f16 与 u8，mediump 存得下 | 最多 2× | G1（u8→归一化的舍入与后续 mix 的权重精度） |
| b | **把 confidence 并进 guide 纹理**（`RG16F` + `R8` 两张 → 一张 `RGBA16F`），`field_cell` 从 2 次取样变 1 次 | 2× | G1（confidence 从 u8 归一化变成 f16，差约 1e-4 相对） |
| c | 用**硬件三线性** `texture(guide_field, uvw)` 取代显式 8 点：16 次 → 1 次 | 16×（与 b 叠加后仍是 8×） | **V**：`sample_field` 的注释写明「显式三线性使桌面和移动 GPU 使用同一浮点权重」，换成硬件滤波会让各设备的权重不同 |
| d | `peel_field` 从 **RGBA32F 改 RG16F**，并用硬件双线性取代 `sample_peel` 的 4 点 | 2×（格式）× 4（点数） | G1/V |

（a）+（b）加起来最多 4×，且都不改插值权重；（c）是量级最大但唯一会改变「各设备之间是否一致」
这条性质的一项。**注意本项目的 G 级定义是「同一个二进制、关掉开关 → 逐位相同」，不是跨设备逐位
相同**，所以（c）在规则上是允许的 V 级项，但它会让魅族与华为的轨迹不再可比，做之前要想清楚
`ParticleTrajectoryCheck` 的参考路径怎么定。

（d）另有一个附带好处：`peel-project.comp` 的 `imageStore` 目标从 RGBA32F 变 RG16F，
存储图像的字节减半（Arm 第 8.8 节：存储图像不能用 AFBC，所以它是未压缩的实打实的字节）。

| 项 | 内容 |
|---|---|
| 建议 | 按 a → b → d → c 的顺序，各自单独 A/B。a、b、d 三项对 Adreno 同样有益（Adreno 的 `RGB10_A2 比 FP16 快` 是同一族事实），c 单独裁定 |
| 收益 | 积分段的纹理单元周期最多降到 1/4（a+b），加上 c 到 1/32 |
| 风险 | c 改变跨设备一致性；a 在部分驱动上 `mediump sampler3D` 与 `highp` 的声明不一致会拒绝链接（Maleoon 严格），采样器精度要与所有使用点一致 |
| 验证 | 离屏对照落盘「每帧位置的最大偏差」，a/b/d 应当在 1e-3 px 量级；c 要出并排放大图 |

## 1.7 属性布局、IDVS 与实例化

### 资料

- 第 5.6 节 Attribute layout：「Vertices are shaded using an Index Driven Vertex Shading (IDVS) flow
  on Bifrost Arm GPU architectures and above.」顺序是「1. Positions. 2. Varyings of vertices of
  primitives which have survived culling.」Do：「Use a dedicated, tightly packed, vertex buffer for
  position data.」「Use a second dedicated vertex buffer for non-position data.」Don't：「Do not use
  one buffer per attribute...」「Do not pad interleaved vertex buffer strides up to the power-of-two.」
- 第 5.9 节 Instanced vertex buffers：Do：「Use a single interleaved vertex buffer for all instance
  data.」「Use several vertices per instance that are a power-of-two.」「**Prefer indexed lookups
  using `gl_InstanceID` into uniform buffers or shader storage buffers. Rather than per-instance
  attribute data.**」Don't：「**Do not use more than one instanced vertex buffer per draw call.**」
- 第 5.2 节 Index draw calls Don't：「**Do not use indexed draws for simple geometry which has no
  vertex reuse, such as simple quads or point lists.**」

### 对本管线的适用性

三条结论：

1. **B4（改索引四边形）在 Mali 上同样否定。** Arm 第 5.2 节直接点名「简单四边形没有顶点复用，
   不要用索引绘制」。上一轮基于 Adreno 的否定在 Mali 上也成立，不必重开。
2. **我们踩了「一次绘制不要超过一个实例化顶点缓冲」这条 Don't**（F3：绘制记录 0–3 一个缓冲、
   材料 4–5 另一个）。修法是把 `material_src` 与 `material_random` 需要的那几个分量并进绘制记录。
   结合 1.2 的第二步（`random_out` 只需 `.zw`、`age/life` 可删），绘制记录现在是
   `xform/place/shade/mask` 四个 vec4 = 64 B，里面有空位（`mask.w` 未用），**把 `random.zw` 打包进
   `mask.w`（`packHalf2x16`）、把 `src.xy` 只在 replica 分支需要**——多数片用不到，可以把 replica 的
   `uv` 也在 `draw-record.comp` 里算好。这样能做到**一个实例化缓冲、每实例 64 B**，正好命中 Arm 的两条 Do。
3. **「优先用 `gl_InstanceID` 索引 SSBO / UBO 而不是逐实例属性」这条在 Maleoon 上无法照做**：
   顶点阶段 SSBO 上限是 0，UBO 只有 16 KB。**可用的替代是纹理缓冲**（`samplerBuffer`，ES 3.2 核心，
   Maleoon 实测 `纹理缓冲=268435456`）。这就是当年被否掉的「B 方案」。上一轮按 Adreno 的
   「VS 调用数不变」否定了顶点拉取，**但 Arm 这条 Do 是按 Mali 的取数路径给的**，与 VS 调用数无关。
   **这一项对 Adreno 不做、只在 Mali 分支试**，属于「必须实测才知道」的一格。

| 项 | 内容 |
|---|---|
| 建议 | 先做「合并成一个实例化缓冲」（两家都受益、G1）；「顶点拉取（`samplerBuffer`）」只作为 Mali 分支的待测项，排在最后 |
| 收益 | 合并：少一个属性流的取数与 stride 跳转；顶点拉取：Mali 上取数路径改变，量级未知 |
| 风险 | 顶点拉取要重排 `resolve` 的 VAO 状态（现在靠 `disableInstanceAttributes` 收尾） |
| 验证 | 合并之后 Adreno 侧应当只有浮点结合律级差异（G1）；顶点拉取在 Mali 上测离屏墙钟 |

## 1.8 `glMemoryBarrier` 的位太宽

F7：每次 dispatch 之后都发
`SHADER_STORAGE_BARRIER_BIT | SHADER_IMAGE_ACCESS_BARRIER_BIT | TEXTURE_FETCH_BARRIER_BIT`，
现场消散一帧约 21 次。实际需要的：

- splat / blur / region / tiles / Jacobi 之间只是 SSBO → SSBO，**只需要 `SHADER_STORAGE_BARRIER_BIT`**；
- 只有 `resolveField()` 那一步写了 `imageStore` 再被 `step.comp` 采样，需要
  `SHADER_IMAGE_ACCESS_BARRIER_BIT | TEXTURE_FETCH_BARRIER_BIT`；
- `writeDrawRecords` 那处已经是对的（`SHADER_STORAGE | VERTEX_ATTRIB_ARRAY`）。

Arm 的 SDK 明说屏障在 tile 架构上可能意味着把场景冲进主存。把 20 次屏障的位收窄到只有 SSBO，
是一条 **G0、零风险、两家都适用**的改动。（`glMemoryBarrierByRegion` 对 compute→compute 没有意义，
它的区域语义只对同一 framebuffer 区域内的片元操作成立，**不要用**。）

## 1.9 三角形密度与 overdraw

Arm 第 5.8 节：「Use models that create at least 10-20 fragments per primitive.」
按 `per-frame-baseline` 的读数，弹窗的材料格是 319×314 覆盖 1368×3192，每片约 6.6×6.6 px，
一个三角形约 22 个片元——**恰好落在 Arm 的建议区间内，不是问题**。真正的问题是片数本身
（10.7–15.9 万），它同时决定顶点数、varying 字节、原子次数与片元总量。

**E12（按 dp 定微片尺寸）因此是 Mali 上唯一同时压住所有段的一项**，第四批已经证明：
华为默认档位 0.19、调到 2.4 dp / 40 之后 1.01。本轮的资料只是给它补了一条机制上的解释——
Mali 上每个顶点的 varying 都要写主存，片数是这条带宽的直接乘数。

## 1.10 工具

- Arm Streamline / Performance Advisor / Mali Offline Compiler **对 Maleoon 大概率不可用**：
  它们依赖 Arm 的内核驱动与 gator，海思自研 GPU 不在其列。没有查到 Arm 工具支持 Maleoon 的说法，
  也没有查到明确否定——**开跑前花十分钟试一次即可定论，不要把方案建立在它可用的假设上**。
- 华为自己的对口工具是 **Graphics Profiler**（GPU Counters / Render Load / GPU Memory / GPU and
  Driver Events，[文档](https://developer.huawei.com/consumer/en/doc/Tools-Guides/gpu-counters-0000001886127538)）。
  它面向 HarmonyOS + DevEco，**能否对一台 Android 12 的 MatePad 上的普通 APK 采集，未经证实**。
- `GL_EXT_disjoint_timer_query`：Khronos 规范里的 `TIMESTAMP_EXT` 在 tile 架构上「marks when
  commands complete and copy out of tile memory」，粒度比立即模式粗，多个 draw 常拿到同一时间戳；
  `TIME_ELAPSED_EXT` 是否计入抢占时间是未定义的
  （[EXT_disjoint_timer_query 规范](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_disjoint_timer_query.txt)）。
  本项目已有的结论是「Adreno 上不计 compute dispatch」（`.claude/rules/adb.md`）。
  **Mali 上能不能计 compute，没有查到任何一手说法，只能实测**：探针模式 `b` 已经现成，
  跑一次「包住 1550 次 compute 的一对查询」与「包住两遍绘制的一对查询」，看前者是否给出合理值即可。
- **因此华为上的归因口径默认仍是「整段墙钟 + 消融」**，与 Adreno 相同，见方案第一步。

---

# 二、高通 Adreno（7xx / 8xx）

上一轮已经覆盖了 bin-pass 双份顶点着色器、GMEM tile 数、`EGL_IMG_context_priority`、
freedreno 的 tile restore 警告。本轮只补**上一轮没有的**。

## 2.1 fp16 在 compute 里的吞吐：上一轮把期望调得过低了

上一轮按 Godot 的实测（`mediump` 默认化只拿到 2–3%，
[PR #99767](https://github.com/godotengine/godot/pull/99767)）把 D11 的期望压到个位数百分比。
但那次实测的对象是一个**带宽受限的场景渲染器的片元着色器**，与本管线的 compute 不同。硬件侧的
事实是：Adreno 的每个调度分区有 64 宽的 FP32 单元，**FP16 以双倍速率执行**；上一代 Adreno 640
被描述为「一对 64 宽 FP32 ALU 与一对 128 宽 FP16 ALU」
（[Chips and Cheese, Inside Snapdragon 8+ Gen 1's iGPU](https://chipsandcheese.com/p/inside-snapdragon-8-gen-1s-igpu-adreno-gets-big)、
[Correction on Qualcomm iGPUs](https://chipsandcheese.com/p/correction-on-qualcomm-igpus)）。
Qualcomm 自己的表述是「标量架构在用 16 位中等精度浮点跑片段着色器时可以有两倍性能」，另有
「FP16 的 inverse sqrt 比 FP32 快 2 倍」
（[azhirnov/cpu-gpu-arch Adreno 指南](https://github.com/azhirnov/cpu-gpu-arch/blob/main/gpu/Adreno_Guide.md)）。

本管线里**ALU 密集且值域受限**的地方：`step.comp` 的 `curl()`（3 倍频 × (1 atan + 4 sin/cos)，
每步调 2 次）、`material.frag` 的整条颜色链、`peel-blur.comp` 的 `vec4` 卷积。
**注意 Khronos 的警告仍然成立**：ES 3.1 只有 `mediump`（没有 `float16_t`），要命中打包的 f16x2
指令必须是 `vec2`/`vec4` 的整体运算，而且不要在 FP16/FP32 之间频繁转换
（[Vulkan Samples: Using explicit 16-bit arithmetic](https://docs.vulkan.org/samples/latest/samples/performance/16bit_arithmetic/README.html)）。

**结论：D11 的排序应当提高，并且优先改 compute 而不是只改片元。** 具体：`curl()` 整段改 mediump
（输入 `p/scale` 已归一、输出是方向向量）、`peel-blur.comp` 的 `vec4 sum` 改 mediump。
位置与时间保持 highp。

## 2.2 GMEM、bin 数与 RGBA16F

上一轮引的 freedreno 结论仍然成立：**bin 尺寸由 GMEM 容量除以渲染目标的格式与大小决定**
（[freedreno wiki, Adreno tiling](https://github.com/freedreno/freedreno/wiki/Adreno-tiling)）。
RGBA16F = 8 B/px，相对 RGBA8 **bin 数量翻倍**，binning 的几何遍历与 tile load/store 次数随之翻倍。

这与 1.1 的结论合流：**把累积目标换成 `SRGB8_ALPHA8` 在 Adreno 上是 bin 数减半**，
在 Mali 上是浮点混合变 unorm 混合 + 带宽减半。**这是本轮唯一一条对两家都是「量级级别」的候选**，
也因此是唯一必须先给用户看画面的那一条。

另一条 Adreno 侧的事实：「RGB10_A2 format is faster than FP16」「All formats support UBWC」
（同上 Adreno 指南）。我们需要 alpha 的动态范围，RGB10_A2 的 2 位 alpha 不够，所以这条对累积目标
不适用；但对 `peel_field`（1.6d，RGBA32F 只用 xy）适用——**RG16F 或 RGB10_A2 都比 FP32 快**。

## 2.3 `GL_QCOM_tiled_rendering`：Adreno 上做「按包围盒缩小渲染区域」的正确 API

上一轮把 B6 降级的理由有二：实测清空在 Adreno 上不要钱（±1% 噪声内）、resolve 只值 0.24–0.33 ms；
以及 freedreno 警告「用 scissor 局部清空会让 tile 从免 restore 变成需 restore」。

本轮找到的新东西是**厂商自己给的 API**：
[`GL_QCOM_tiled_rendering`](https://registry.khronos.org/OpenGL/extensions/QCOM/QCOM_tiled_rendering.txt)
的 `glStartTilingQCOM(x, y, w, h, preserveMask)` / `glEndTilingQCOM(preserveMask)`：

- 「all rendering commands (**including clears**) will only update the contents of the render target
  defined by the extents of this tile」；
- `preserveMask` 在开始时决定哪些缓冲要用现有内容初始化（即是否 restore），在结束时决定哪些要写回；
- 不保留的缓冲，「the resulting contents of the buffer regions corresponding to the active tile will
  be undefined」。

也就是说：**`preserveMask = 0` 正好表达「这一块不需要 restore、清了就画」**，恰恰绕开了 freedreno
警告的那个坑，而 scissor 做不到这一点。对本管线：整段动画的保守包围盒（由 splat 已有的原子包围盒
给出，只是要换成像素坐标）→ `glStartTilingQCOM(box, 0)` → clear + 两遍绘制 → `glEndTilingQCOM(COLOR_BUFFER_BIT0_QCOM)`。

**但上一轮的实测已经把这条的天花板钉死了：清空 ≈ 0、resolve 3.4%。** 所以它**不是收益项，是
「RGBA16F → RGBA8 之后重新评估」的备选**：如果换了格式仍然是绘制主导，这条不值得做。列在这里
是为了记下「Adreno 上局部渲染有一条正确的路」，不要再用 scissor 去试。

## 2.4 工作组、wave 与 compute 调度

- Qualcomm 的《Game Developer Guide》说「Prefer fragment shaders to compute shaders when possible,
  since compute shaders require kernel output to be written to memory before the next kernel can
  begin execute」，并建议按「shader 的 GPR 用量与指令数」调工作组数与大小
  （[Snapdragon Game Developer Guide / GPU](https://docs.qualcomm.com/bundle/publicresource/topics/80-78185-2/gpu.html)，
  该域名在本机 `WebFetch` 取不到正文，要点转自搜索摘要）。
- wave 宽度按代际与编译器在 8/16/32/64/128 之间变化；Adreno 7xx 有 wave64 模式
  （Chips and Cheese，同上）。我们的 256 线程是 wave64 的 4 倍，**符合 Adreno 的取向**，
  所以 1.3 那条「256 → 64」**只能做成 Mali 分支，不要横切**。
- 「Vectorized local memory load/store can load up to 128-bits」——`pressure-batched.comp` 的
  `shared float` 是标量访问，改成 `vec4` 打包**理论上**能让 LDS 访问宽一些，但会打乱边带缩减规则，
  风险高于收益，**不建议**。
- **上一轮关于抢占粒度的结论不变**：Qualcomm 的专利只说两级 binning 让**渲染**的抢占粒度更细
  （每个粗 bin 处可停），没有任何公开材料说 compute dispatch 内部可被抢占。因此 F14 之外
  「把长 dispatch 切短」仍然是唯一能改善「出帧排在整段 compute 后面」的手段，
  `yieldToWindow` 那条路不应删。
- 没有查到 Adreno 上应用可控的「异步 compute 队列 / LPAC 显式入口」在 GLES 里的暴露方式。
  `EGL_IMG_context_priority` 之外**没有别的应用侧调度旋钮**；`EGL_NV_context_priority_realtime`
  Adreno 不提供。这一格的结论与上一轮相同。

## 2.5 工具

Snapdragon Profiler 的 `Read Total (Bytes)` / `Write Total (Bytes)` 与 Vertex Memory Read 是唯一
能直接验证「binning 带宽变化」的口径（上一轮已记）。AGI（Android GPU Inspector）在 Adreno 上可用，
能给逐 draw 的计数器；两者都要求 debug 包且需要设备在线，**不在本轮的静态分析范围内**。

---

# 三、联发科天玑

## 3.1 事实

- 主力天玑（9000 / 9200 / 9300 / 9400 系列与中端 8000 / 7000 系列）用的是 **Arm 的 Mali / Immortalis**：
  例如天玑 9400 集成 Immortalis-G925
  （[Arm Immortalis-G925](https://www.arm.com/products/silicon-ip-multimedia/immortalis-gpu/immortalis-g925)）。
  少数早期与低端型号用过 PowerVR（Helio 系列的一部分），但当前在售主力没有。
- 因此**天玑不是独立的第三条分支，而是「Mali 分支 + 更新的 Arm 代际 + 联发科的驱动」**。
  第一节的每一条规则都直接适用，且比 Maleoon 更可靠——那是 Arm 的原生驱动。
- 代际差异里与本管线相关的只有一条：**Immortalis-G925 起有 fragment pre-pass**（Arm 的文档描述为
  「No need to sort by depth any more」），但它对**有混合、无深度写**的绘制不适用，
  对我们没有影响。
- 联发科**没有公开的 GPU 优化最佳实践文档**。检索到的只有第三方（GameSir GameHub 与联发科合作
  改善 Mali 驱动的报道，
  [Android Authority 2025-07](https://www.androidauthority.com/gamehub-mali-gpu-mediatek-support-3581384/)），
  内容是 Vulkan 支持与着色器编译器稳定性，**与 GLES compute 无关**，不能作为本管线的依据。

## 3.2 对方案的影响

1. **Mali 分支的所有改动按 `GL_RENDERER` 判「非 Adreno」而不是判「Maleoon」**，这样天玑、
   三星 Exynos 的 Mali、以及未来的 Immortalis 自动走同一条。现有的 `Shared.adreno()` 恰好就是
   这个形状（判 Adreno，取反即 Mali 族）。
2. **不要按机型表分档**（followups 里已有这条）。工作组大小、采样器精度这类改动按厂商分支即可；
   片数与迭代数这类档位应当按**实测帧率**触发。
3. **风险提示**：本项目手上没有天玑设备。Mali 分支的改动在 Maleoon 上验证通过之后，
   在天玑上属于「按同一族规则外推」，**必须在发布日志里写明未在天玑上实测**，
   并保证每一项都有开关可退回。
4. 一条具体的兼容性纪律：Maleoon 的两个坑（跨阶段精度严格、向量分量原子不按下标寻址）
   **不能假定在天玑上也存在或也不存在**。已经改成标量原子与显式同精度的写法对两者都安全，
   继续保持即可；单测里那条判据式契约（任何 `atomic*` 的第一个实参不得以 `.x/.y/.z/.w` 收尾）
   已经守住了这一点。

---

# 四、三家都受益的候选（普适项）

把上面各节里「与厂商无关」的那些集中列一次，它们应当排在分厂商项之前：

| # | 候选 | 机制 | G |
|---|---|---|---|
| U1 | **屏障位收窄**：compute→compute 只发 `SHADER_STORAGE_BARRIER_BIT` | 每帧约 20 次屏障不再要求图像/纹理可见性 | G0 |
| U2 | **confidence 并进 guide 纹理**：两张 3D 纹理 → 一张 RGBA16F | 3D 取样次数减半（每帧约 1370 万 → 685 万） | G1 |
| U3 | **`peel_field` RGBA32F → RG16F**，并用硬件双线性取代 4 点 `texelFetch` | FP32 格式 2× 代价消失 + 取样次数降到 1/4 | G1 |
| U4 | **片元里的逐片量下沉到 `draw-record.comp`**（A1 的片元侧延伸） | 片元少 4 个 `smoothstep` + 1 `sin` + 1 `pow`；与 U5 合计每顶点 varying 56 B → 28 B | G1 |
| U5 | **varying 改 mediump**（`uv` 除外） | Mali 上是主存往返，Adreno 上是 GMEM/主存 | G1 |
| U6 | **`pressure-batched.comp` 的 `divergence`/`occupied` 移出 shared**（照 `-large` 变体的做法） | shared 16 → 8 KiB | **G0** |
| U7 | **累积目标 RGBA16F → SRGB8_ALPHA8** | Mali：浮点混合 → unorm 混合 + 带宽减半；Adreno：bin 数减半 | **V** |
| U8 | **splat 双线性 → 最近格** | 每片原子 12 → 3 | **V** |
| U9 | **E12 微片尺寸 / C7 迭代数按实测帧率自动降档** | 唯一同时压住所有段的一项；followups 已记 | **V**（用户已裁定默认值，自动降档要再裁定） |
| U10 | **`sample_field` 改硬件三线性** | 取样 16 次 → 1 次 | **V** + 破坏跨设备一致性 |

---

# 五、交互到首帧

三条链路的现状（取自 `per-frame-baseline-2026-09-17.md` 与第三、四批）：

| 链路 | 现状 | 已知构成 |
|---|---|---|
| 左滑：识别 → 首个非零画面 | 魅族 64.9 / 73.7 ms（慢拖/快拖），三星此前 110 ms | 建材 11.9–16.2 ms（魅族）/ 63–72 ms（三星）+ 首帧提交 + Surface 首次合成 |
| 新建按钮 → 首帧呈现 | 命中 171 ms（OPPO）/ 305 ms（三星），未命中 555–1014 ms | 大头在窗口起来与首次布局（已记 followups，本专项不动）+ 抓图 + 预算 |
| 弹窗关闭 → 首帧 | 魅族 72–98 ms（返回键 / 点外部） | 涟漪结算等待（设计保留）+ 抓图 + 建材 + 管线准备 |

## 5.1 已经做完的，不要重复

- 首帧绕开节拍（`immediateFirstFrame`）：第二批实测两台都量不出收益，**默认关**，
  且踩出了「提前出的一帧必须把要替掉的那一拍真的吃掉」的教训。**不要再提这一条**。
- 节拍相位锁定（`beatPhaseLock`）：第三批已落地。
- 预计算期间就让节拍跑 + Surface 一就绪先交换一张全透明帧：3b 已落地（OPPO 108 → 26 ms）。
- 呈现链（合成方式、刷新率投票）：`per-frame-baseline` 第 4 节实测全 Device 合成、60 Hz 投票生效，
  **F16 不做**。
- `requestUnbufferedDispatch`：官方文档自己给了反面结论
  （[Android 触控与手写笔文档](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)），
  **否定项**。

## 5.2 还能压的（按把握排序）

### （1）ADPF `PerformanceHintManager`（API 31+）—— 上一轮已提出，仍未落地

把常驻 GL 线程 + 材料工作线程放进一个 hint session，目标时长 16.6 ms，每帧
`reportActualWorkDuration`。系统据此调核心放置与频率
（[AOSP Performance Hint API](https://source.android.com/docs/core/perf/performance-hint-api)、
[NDK 参考](https://developer.android.com/ndk/reference/group/a-performance-hint)）。
这正是「三星建材 63–72 ms 未归因」那一类问题的对口手段——`THREAD_PRIORITY_URGENT_DISPLAY`
只改 nice 值，不改核心放置与频率。**风险是部分 ROM 空实现（设了没效果，不会更差）。**
判据：`getPreferredUpdateRateNanos()` 返回值 + 建材耗时分布的修前修后。

**本轮补一条实施细节**：左滑的建材发生在**按下之后、识别之前**，属于突发而不是周期负载。
ADPF 的 session 是为周期负载设计的，突发负载应当在 session 里用
`reportActualWorkDuration` 报一个大于目标的值，让系统立刻升频；或者更简单——**在
`ACTION_DOWN` 就创建 session 并报一次「目标 8 ms、实际 30 ms」**，让调速器在识别发生前就上去。

### （2）建材的线程亲和与起点

- 建材现在按核数分道（第三批把道数从 3 改成 6）。**没有做的是把这些线程绑到大核**。
  Android 没有公开的 affinity API，只有 `Process.setThreadPriority` 与 ADPF。
  所以这一格的可用手段就是（1）。
- **更值钱的是把建材的起点提前**：左滑的建材可以在 `ACTION_DOWN`（甚至在 `RecyclerView` 的
  `onInterceptTouchEvent` 判出水平位移倾向时）就起，而不是等 `ItemTouchHelper` 判定左滑。
  代价是「按下但没有左滑」时白建一次材料。第四批的调参页已经证明预览卡片走的是同一条路径，
  这条改动可以在调参页里先验证。**这一条是本节里唯一可能整块吃掉那 60 ms 的**。

### （3）抓图路径

`weak-devices-phaseA` 与 2026-09-16 那次修复之后，消散一律先离屏（1.07 s → 59 ms），
有可见 Surface 的弹窗走「离屏重录 + 逐个 Surface `PixelCopy` 再合成」。**还能压的两点**：

- 离屏抓图现在是**整窗口**尺寸。弹窗的实际内容区往往只占窗口的一部分，按内容包围盒抓图能
  按面积比例缩短；
- 抓图结果紧接着要上传成 `foregroundTexture`（`GL_RGBA8`）。**这一步是否用了 PBO 异步上传，
  需要核对**；同步 `glTexImage2D` 在主线程或 GL 线程上会阻塞。
  Arm 第 3.6 节把 `glTexSubImage()`「while the target texture is still referenced by an in-flight
  draw call」列为要避免的同步操作。

### （4）Surface 首次合成

3a 的数据：粒子 Surface 的第一次缓冲要等 SurfaceFlinger 首次合成，第一次动画 82–88 ms、
之后 14–17 ms；已经用「表面一就绪先交换一张全透明帧」消掉了大部分。**剩下的一格是
`primeParticleSurface` 这个开关的覆盖面**——核对它在左滑与创建页两条路径上是否都真的
在动画开始之前就把 Surface 预热过了。

### （5）呈现时刻不要把流水线加深

`EGL_ANDROID_presentation_time` 的实践语义是「不早于」，设到未来若干帧会把流水线加深相应帧数
（[规范](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_presentation_time.txt)、
[osu-framework PR #6792 的讨论](https://github.com/ppy/osu-framework/pull/6792)）。
第三批的相位锁定改的正是「这一拍的目标时刻怎么算」，**首帧这一次应当写「尽快」而不是按稳态节拍
算出来的未来时刻**——核对一次即可，属 G0。

### （6）前缓冲渲染：否定

`androidx.graphics.lowlatency` 的 `GLFrontBufferedRenderer` 直接写前缓冲，笔到像素可以做到
≤ 25 ms（[Android graphics 库](https://developer.android.com/reference/androidx/graphics/lowlatency/package-summary)、
[Stylus low latency](https://medium.com/androiddevelopers/stylus-low-latency-d4a140a9c982)）。
但它的前提是**每帧只画很小的增量**（湿墨迹），而我们每帧要整屏清空重画累积纹理再 resolve——
直接写前缓冲必然撕裂。**否定项**，记在这里是为了以后不用再查。

### （7）`Choreographer` 的 vsync 回调与 frame timeline

API 33 起 `AChoreographer_postVsyncCallback` 能拿到多条候选 frame timeline 并选一条未来帧
（[NDK Choreographer](https://developer.android.com/ndk/reference/group/choreographer)）。
本项目的节拍已经做了相位锁定并直接用驱动报的呈现时刻，**这条的增量价值低**；
真正有用的是它给出的 `deadline`——与 `.claude/rules/adb.md` 里那条「看到长帧先读 `DEADLINE`」
是同一件事。不列为候选，列为归因时的口径。

---

# 六、总表：按预期收益排序

「适用」列：**通** = 三家都受益；**M** = Mali 族（含天玑）；**A** = Adreno。
收益是按资料与代码事实推断的量级，不是实测。

| 排序 | 条目 | 适用 | 预期收益 | G | Adreno 逐位 | 依据强度 |
|---|---|---|---|---|---|---|
| 1 | 累积目标 RGBA16F → SRGB8_ALPHA8（U7） | 通 | Mali：浮点混合→unorm + 带宽减半；Adreno：bin 数减半 | **V** | 否 | 强（Arm 7.7/7.9 两条 Don't + freedreno 的 bin 计算） |
| 2 | 3D 场取样：mediump 采样器 + confidence 并进 guide（U2 + 1.6a） | 通（Mali 更甚） | 积分段纹理单元周期 → 1/4 | G1 | 否 | 强（Arm 8.4 的三条倍数） |
| 3 | varying 精度 + 片元逐片量下沉（U4 + U5） | 通（Mali 更甚） | 每顶点 varying **56 B → 28 B**（弹窗每帧少写少读约 24 MB × 2）；片元少 4 个 `smoothstep` + 1 `sin` + 1 `pow` | G1 | 否 | 强（Arm 5.7 原文 + 代码事实） |
| 4 | 压力求解器的 Mali 变体（逐次 + 活动区域 vs 批处理 shared）（1.4） | **M** | 左滑现场帧压力占 46%、预读占 87%，弹窗现场 21% | G0（卡片内） | **是**（Adreno 不改） | 强（Arm 9.3 的两条 Don't 与我们的设计正好相反） |
| 5 | 工作组 256 → 64 / 8×8 的 Mali 变体（1.3） | **M** | 未知，Arm 把 64 写成上限而非建议 | G0 | **是** | 强（Arm 9.2 的 Don't 原文） |
| 6 | 屏障位收窄（U1） | 通 | 每帧约 20 次屏障不再要求图像/纹理可见性 | **G0** | **是** | 中（Arm SDK 的定性说明） |
| 7 | `peel_field` RGBA32F → RG16F + 硬件双线性（U3） | 通 | FP32 2× 代价消失、取样 4 → 1 | G1 | 否 | 强（Arm 8.4 + Adreno「RGB10_A2 比 FP16 快」） |
| 8 | `curl()` 与 `peel-blur` 的 `vec4` 改 mediump（2.1） | 通（Adreno 双倍率） | compute ALU，最乐观接近 2× | G1 | 否 | 中（硬件侧 FP16 双倍率明确，`mediump` 能否命中要实测） |
| 9 | `pressure-batched` 的 shared 16 → 8 KiB（U6） | 通 | Mali 少一半缓存污染；Adreno 少一半 LDS 占用 → occupancy | **G0** | **是** | 中（`-large` 变体已证明写法可行） |
| 10 | 合并成一个实例化顶点缓冲（1.7） | 通（Mali 更甚） | 少一个属性流 | G1 | 否 | 中（Arm 5.9 的 Don't 原文） |
| 11 | splat 双线性 → 最近格（U8） | 通 | 原子 128 万 → 32 万次/更新 | **V** | 否 | 中（Arm 10.13 + MPM 文献） |
| 12 | ADPF hint session（5.2.1） | 通（Android 层） | 建材耗时，量级待实测 | G0 | **是** | 中（官方 API，效果逐 ROM） |
| 13 | 左滑建材提前到 `ACTION_DOWN`（5.2.2） | 通（Android 层） | 可能整块吃掉识别→首帧里的建材那一段 | G0 | **是** | 中（纯时序，无外部依赖） |
| 14 | 按实测帧率自动降档（U9） | 通 | 华为默认 0.19 → 1.01 的差距由此自动弥合 | **V** | 是（默认值不变时） | 强（第四批已有两档实测） |
| 15 | 顶点拉取 `samplerBuffer`（1.7.3） | **M** | 未知 | G1 | **是**（Adreno 不改） | 弱（Arm 的 Do 与 Adreno 的实测相反） |
| 16 | `sample_field` 改硬件三线性（U10） | 通 | 取样 16 → 1 | **V** + 破坏跨设备一致 | 否 | 中（Arm 8.4；代价是显式插值的初衷） |
| — | **`GL_QCOM_tiled_rendering` 局部渲染** | A | 上限已被实测钉死（清空 ≈ 0、resolve 3.4%） | G0 | 是 | **备选**，仅在换格式后重估 |
| — | **索引四边形 / 索引缓冲** | — | 无 | — | — | **否定**（Arm 5.2 明文：简单四边形无顶点复用不要用索引） |
| — | **两遍绘制合一** | — | 只省 1 次 draw | V | — | **否定**（上一轮结论不变） |
| — | **shared 原子聚合 splat** | — | 不成立 | — | — | **否定**（粒子按源格分组；Mali 的 shared 本就是主存） |
| — | **粒子空间排序 / 分桶** | — | 文献称不排序反而快 8–17% | — | — | **否定** |
| — | **PCG / 多重网格** | — | 迭代体非线性；多重网格会改画面 | — | — | **否定**（上一轮结论不变） |
| — | **前缓冲渲染 `GLFrontBufferedRenderer`** | — | 必然撕裂 | — | — | **否定** |
| — | **`requestUnbufferedDispatch`** | — | 官方称多数情况不改善 | — | — | **否定** |
| — | **`glMemoryBarrierByRegion` 用于 compute→compute** | — | 语义不成立 | — | — | **否定** |
| — | **framebuffer fetch / PLS 合并累积与 resolve** | — | 负收益 | — | — | **否定**（上一轮结论不变） |

# 七、必须实测才能定的（不要在方案里预设答案）

1. **Maleoon 的 FP16 混合是否走 Valhall 那条加速路径**（1.1）。决定 U7 在华为上是几成收益。
2. **Mali 上批处理 Jacobi 与逐次 Jacobi 谁快**（1.4）。两条路径都在代码里，对照几乎零成本。
3. **`GL_EXT_disjoint_timer_query` 在 Maleoon 上计不计 compute**（1.10）。决定归因方法。
4. **`mediump` 在这两家驱动上是否真的被当成 16 位**（2.1）。Arm 自己说「drivers can ignore mediump」；
   Godot 的 2–3% 很可能就是被忽略了。判据：同一段改 mediump 前后测离屏墙钟，**再加一条画面差异**——
   如果画面逐位相同，说明驱动把它当 highp 处理了。
5. **工作组 64 在 Maleoon 上是否真的更好**（1.3）。Arm 的 Don't 写得很硬，但 Maleoon 不是 Arm 的实现。
6. **Arm Streamline / 华为 Graphics Profiler 能否对这台设备上的普通 APK 采集**（1.10）。
7. **整网格清零在 Mali 上是否显著**（1.5）。Adreno 上落在噪声里，不能外推。

# 八、检索过程中的方法学提醒

1. **`developer.arm.com` 与 `docs.qualcomm.com` 的网页在本机 `WebFetch` 取不到正文**（前者是 JS 渲染、
   后者域名校验失败）。本轮的做法是**把 Arm 的 PDF 整份下载后用 `pdftotext -layout` 转成文本再逐节读**，
   所以第一节里带引号的都是原文。Qualcomm 那一侧仍然只有搜索摘要，**本文凡引 Qualcomm 的地方都已注明**。
   华为的 Maleoon 最佳实践页同样是 JS 渲染，只有摘要。
2. **厂商文档自相矛盾的地方要一并记下**：Arm 在 7.9 节同时写了「不要在浮点渲染目标上混合」与
   「Valhall 起 FP16 的简单混合是硬件加速的」。把两句都摆出来，实测定夺——只引对自己有利的那一句
   会把方案建在沙上。
3. **别把一次第三方实测的百分比当成本管线的期望值**。上一轮引 Godot 的 2–3% 把 D11 排到第 10，
   本轮翻出硬件侧的 FP16 双倍率之后重排到第 8，而真正该做的是第七节第 4 条那个「画面是否逐位相同」
   的判据——它能一次分清「改了没用」与「驱动根本没听」。
4. **代码事实优先于文档推断**。本文最硬的几条（每帧 1370 万次 3D 取样、14 个 highp varying、
   16 KiB shared 只输出 1/4 面积）都是数代码数出来的，文档只提供了「这些数字为什么重要」。
