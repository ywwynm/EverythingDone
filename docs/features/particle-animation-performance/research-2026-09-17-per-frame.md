# 逐帧成本专项：外部资料调研（2026-09-17）

对照 [per-frame-plan-2026-09-17.md](per-frame-plan-2026-09-17.md) 第 3 节的十个候选方向，检索厂商开发者
文档、Khronos 扩展规范、Android 图形文档与近年的图形/CFD 一手资料，逐项给出「资料说了什么 / 对本管线
是否适用 / 预期收益 / 风险 / 怎么验证」。

**本文不含任何实测数字**，凡标「预期」的都是按资料推断的量级，必须由阶段 0' 的真机归因与每批的
真机复测确认。资料本身也有时效与设备差异，凡是厂商文档里的定性表述，本文一律标明它是定性的。

## 0 现状回顾（检索的出发点）

读了 `shared/particle-dismiss/{step,peel-splat,peel-blur,peel-project}.comp`、`material.{vert,frag}`、
`resolve.{vert,frag}`、`app/src/main/assets/particle-playback/{history,pressure-batched,pressure-tiles}.comp`
与 `ParticleMicroflakeRenderer.kt` 的 `advance` / `render` / `PeelPressure.update`、
`ParticleReverseHistory.kt`、`ParticleReversePlan.kt`。与检索相关的结构事实：

| 事实 | 位置 | 与哪个主题相关 |
|---|---|---|
| 绘制是 `glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, count)`，**每帧两遍**（`material_pass` 0/1） | `render()` | 1、2 |
| 顶点数据是 6 个 `divisor=1` 的实例化属性：材料 3×vec4（48 B）+ 状态 2×vec4（32 B）+ 色素 1×float，**全部来自 compute 写的同一批缓冲对象**，无拷贝 | `bindInstanceAttributes` | 1、2、7 |
| 顶点阶段**不能用 SSBO**：Mali/Maleoon 的 `GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS` 是 0（规范允许的最小值） | `material.vert` 注释 | 2 |
| `gl_Position` 的依赖链几乎吃掉全部逐顶点数学（`scale`/`angle`/`tilt`/`roll_phase`/`z` 全部汇入 `vertex`） | `material.vert` L76–111 | 1 |
| 累积目标是全屏 `RGBA16F`，每帧整屏 `glClear` + 全屏三角形 resolve；混合 `ONE, ONE_MINUS_SRC_ALPHA` | `render()` L555–588 | 3 |
| 全部着色器顶部是 `precision highp float; highp int; highp sampler2D; highp sampler3D;`，**没有任何 `mediump` 声明** | 全部 | 4 |
| `step.comp` 每步对 `guided_flow` 调 1–2 次，每次 `sample_field` 做显式三线性 = **8 个 `field_cell`，每个 2 次 `texelFetch`**；即每步最多 32 次 3D `texelFetch` | `step.comp` L55–66、L106–107 | 1、4 |
| `curl()` 每次 3 个倍频、每倍频 1 个 `atan` + 4 个 sin/cos；每步调 2 次 | `step.comp` L32–45、L112/L164 | 1 |
| Jacobi 固定 100 次，`pressure-batched.comp` 每个工作组 32×32 共享区、8 次一批、边带逐次缩小；共享内存 **4 × 1024 × 4 B = 16 KiB**（= ES 3.1 的 `MAX_COMPUTE_SHARED_MEMORY_SIZE` 下限） | `PeelPressure.update` L877、`pressure-batched.comp` | 5 |
| 迭代体里有 `max(..., 0.)` 与 `occupied > .025` 两个非线性门 —— **不是纯线性 Poisson**，是带单边约束的投影迭代 | `peel-project.comp` L23、`pressure-batched.comp` L48 | 5 |
| splat 每片对 4 个格子做 3 次 `atomicAdd`（定点 ×4096）= **每片 12 次原子操作** | `peel-splat.comp` L44–48 | 6 |
| 历史缓冲是**片主序变长段**：`h = (r.z + frame - r.x) * 5`，相邻 invocation 的地址相差该片自己的段长 | `history.comp` L20 | 7 |
| 历史每采样 5 × float32 = 20 B（`STATE_BYTES`） | `ParticleReversePlan.kt` L101 | 7 |
| 活动 tile 列表 + `glDispatchComputeIndirect` **只用在 Jacobi**；splat 的清零与 blur 仍是全网格 dispatch | `PeelPressure.update` L859–867 | 5、10 |

---

## 1 逐顶点数学下沉到「逐片每帧一次」

### 资料

- **Adreno 把每个顶点着色器编译成两份**：一份 bin-pass 位置着色器、一份 render-pass 顶点着色器；
  编译器通常会把「只传给片段着色器、不参与算位置」的属性重构成紧挨片段着色器执行的代码
  （[Qualcomm Game Developer Guide / GPU](https://docs.qualcomm.com/bundle/publicresource/topics/80-78185-2/gpu.html)）。
- **binning 着色器对场景里的每一个顶点都要跑**（不管最后可见不可见），所以位置数据的排布是 tile-based
  GPU 上的关键项（[Android《Vertex data management》](https://developer.android.com/games/optimize/vertex-data-management)）。
  该文给的实测：把位置拆成独立流之后，binning 带宽 **27 GB/s → 6.5 GB/s**、binning 取数停顿
  **40% → 0%**、每顶点 binning 字节 **48 B → 12 B**；再叠加顶点压缩，binning 平均每顶点 **48 B → 8 B**。
- **Mali 的 IDVS**（index-driven vertex shading）同样分两段：位置属性应当与非位置属性**分开、紧凑**
  存放，否则位置那一遍会把用不上的属性一起读进来
  （[Arm GPU Best Practices](https://documentation-service.arm.com/static/67a62b17091bfc3e0a947695)、
  [Arm《The Mali GPU: An Abstract Machine, Part 2》](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/the-mali-gpu-an-abstract-machine-part-2---tile-based-rendering)）。
  同篇指出 tile-based 的额外开销主要在「顶点→片段的交接」：逐顶点 varyings 与 tiler 中间态要写出主存
  再读回。

### 对本管线的适用性

这一条**比方案第 3 节 A1 估计的收益更大**，理由是方案里算的「每片 8 次求值」漏掉了 binning：

- `material.vert` 里 `gl_Position` 的依赖链覆盖了几乎全部昂贵数学（`loosen`→`roll_phase`→`scale`
  的七个连乘项→`angle`→`tilt`→`rot`→`z`）。这些**全部属于「算位置所需」**，所以 Adreno 的
  bin-pass 位置着色器**一个也删不掉**，会原样再跑一遍。
- 因此现状是每片每帧 **4 顶点 × 2 遍绘制 × 2 段（bin + render）= 16 次**完整求值，不是 8 次。
  只有 `light_out` / `shape_out` / `surface_out` / `trailing_out` 这些纯 varying 才可能被编译器挪出
  binning（`surface_out` 参与 pass 0 的裁剪判定，实际挪不走）。
- 改成「compute 在积分最后一步 / 历史转存时算好一条绘制记录，顶点着色器只做二维仿射展开」之后，
  bin-pass 着色器退化成「读 4–6 个 float、做一次 2×2 乘加、写 `gl_Position`」——这正是 Adreno
  与 Mali 两套文档都在追求的形态。**每片求值从 16 次降到 1 次。**
- 附带好处（Mali 侧）：绘制记录可以把**位置相关的 4 个 float**（中心 xy + 旋转缩放矩阵的两列）
  放在一个独立的小缓冲里，非位置量（色素、age、life、random）放另一个，正好命中 IDVS 的
  「位置流分开」建议。现状的三个 `vec4` 材料属性里位置与非位置量是混着的。

绘制记录的建议布局（每片 24 B，两个流）：

- 流 A（位置，12 B）：`center.xy`（2×f32）+ 打包的 `rot·scale`（2×f16×2 = 8 B，或 4×f16）
- 流 B（外观，12–16 B）：`age`、`life`、`light`、`shape`、`surface`、`trailing`、`replica` 的压缩形式

### 风险

1. `surface_out` 在 pass 0 里参与「整片丢弃」（`gl_Position=vec4(2,2,2,1)`）。绘制记录里必须把
   「本帧这一片画不画」也算好，否则顶点着色器又得把 `source_clock` 那一段算回来。
   可行做法：绘制记录里放一个已经算好的 `surface` 与一个 `visible` 标志位。
2. `uv` 与 `local_uv` 依赖 `gl_VertexID`（四角 + jitter），**不能**下沉到逐片。但 jitter 是整数散列，
   便宜；`source` 依赖 `grid`，也便宜。真正贵的是被下沉掉的那些。
3. G1 级：`vec2 d = source - m.src.xy` 是逐顶点的（四角各不同），而 `rot`、`scale` 是逐片的。
   把 `rot*scale` 预乘成 2×2 矩阵在数学上等价，但浮点结合律不同 → 逐位会变。属方案已认可的 G1。

### 建议 / 收益 / 风险 / 验证

| 项 | 内容 |
|---|---|
| 建议 | 做，且优先级应高于方案里的排序；绘制记录拆成「位置流 + 外观流」两个缓冲 |
| 收益 | 逐片昂贵求值 16 → 1 次；binning 每顶点读取从 84 B 降到 12 B 量级 |
| 风险 | 裁剪判定要一并下沉；`rot*scale` 预乘改变浮点结合律（G1） |
| 验证 | 消融：临时把 `material.vert` 的位置链替换成常量矩阵（画面必然错），只测帧率，得出这条链的墙钟份额；正式改动后按方案第 5 节矩阵复测 |

---

## 2 绘制形态：实例化 vs 顶点拉取 vs 索引四边形

### 资料

- **Mali**：「不要过度使用实例化 —— 用 `gl_InstanceID` 索引的 uniform 算作动态索引，无法使用寄存器
  映射的 uniform」（[Arm GPU Best Practices](https://documentation-service.arm.com/static/67a62b17091bfc3e0a947695)）。
  注意这条针对的是**用实例 ID 去索引 uniform 数组**，不是实例化属性本身。
- **Mali / Android**：索引缓冲能带来顶点复用，移动端建议控制在 16 位索引范围内
  （[Android《Vertex data management》](https://developer.android.com/games/optimize/vertex-data-management)）。
- **属性无关的四边形展开**（attribute-less，靠 `gl_VertexID` 取四角、靠 `gl_InstanceID` 或
  `gl_VertexID>>2` 取粒子）是当前粒子系统的常见形态，位置/朝向由缓冲里拉取
  （[massive-poly-pusher #23](https://github.com/ajare/massive-poly-pusher/issues/23)、
  [Robert-K/gpu-particles](https://github.com/Robert-K/gpu-particles/blob/master/Assets/GPUParticles/Shaders/Billboard%20Particles.shader)）。
- **`samplerBuffer` / 纹理缓冲**在 ES 3.2 是核心功能（`GL_EXT_texture_buffer` / `GL_OES_texture_buffer`
  在 ES 3.1 上作为扩展），可在顶点阶段使用；这正是 Mali 上「顶点阶段没有 SSBO」的替代路径。

### 对本管线的适用性 —— 三种形态的实际差异比方案预估的小

把三种形态放到同一口径下算「每片的顶点着色器调用次数」：

| 形态 | VS 调用/片 | 索引读取 | 属性读取 | 备注 |
|---|---|---|---|---|
| 现状：`TRIANGLE_STRIP` 4 顶点实例化 | **4** | 无 | 6 个 divisor=1 属性 | 已经是最少调用数 |
| 索引四边形（4 顶点 6 索引） | 4（命中变换后缓存） | 6 × 2 B | 同上 | 多读索引，VS 调用数不减 |
| 顶点拉取（`samplerBuffer`，非索引） | **4**（`gl_VertexID>>2`）或 6（不复用） | 无 | 手工 `texelFetch` | 若不加索引则退化成 6 次 |

**结论：方案 B4「实例化改顶点拉取 + 索引缓冲」在顶点调用数上没有收益**，现状的 4 顶点带状实例化
已经是最优。顶点拉取真正的价值在别处，有两点：

1. **绕开 Mali 的「位置流必须分开」限制**。现状 6 个属性绑在两个大结构体上（材料 48 B、状态 32 B），
   IDVS 的位置那一遍会把整块读进来。如果改成「绘制记录 + 顶点拉取」，位置那一遍只读 12 B。
   但**主题 1 的绘制记录改造已经能拿到同样的收益**（拆成两个紧凑的实例化属性流即可），不必换成
   顶点拉取。
2. **让顶点着色器能读「非逐片」的数据**（例如共享的曲线表）。本管线不需要。

另外要注意一个**没查到反面证据、但值得怀疑**的点：`glVertexAttribDivisor` 的实例化在 Adreno 上会
让驱动走「实例化取数」路径，每 4 个顶点换一次属性基址。查不到公开的量化数据说这条路径有额外成本；
Mali 的那条 Don't 明确只针对 uniform 索引。**在没有实测之前不应假设实例化本身有开销。**

### 两遍绘制合一（方案 B5）

- 两遍的差别只有两处：前景纹理的过滤（`GL_NEAREST` vs `GL_LINEAR`）与片的取舍（pass 0 只画尚有
  `surface` 的静止片，pass 1 只画运动片）。
- ES 3.0 起**采样器对象**（`glGenSamplers` / `glBindSampler`）可以让同一个纹理对象在两个纹理单元上
  用两种过滤，这是标准做法，不需要复制纹理。片段着色器里按 `material_pass` 选单元即可。
- 但**收益需要重新估**：合一之后 draw 从 2 次变 1 次，**顶点着色器调用数不变**（同一片仍要在两种
  语义下各画一次，除非能证明同一片不会同时出现在两遍里）。看 `material.frag`：`material_pass==0`
  与 `==1` 的分支是互斥的，且 pass 0 里 `surface_out<.001` 丢弃、pass 1 里 `age<=0` 丢弃 —— 但
  `material.vert` L39 的注释明确写着「表面与颗粒在交接时可以同时存在」，也就是**同一片在交接期两遍
  都要画**。所以合一只能省掉一次 draw 调用的 CPU/驱动开销与一次状态切换，**省不掉顶点工作量**。
- 代价是方案里已经点明的：混合顺序从「全部 pass 0 → 全部 pass 1」变成逐片交错。在
  `ONE, ONE_MINUS_SRC_ALPHA` 的预乘混合下这**不是**可交换运算，画面会变（V 级）。

**结论：B5 的收益（省 1 次 draw）与代价（V 级画面变化 + 需要用户看图定夺）不成比例，建议降级或
放弃。** 若仍要做，应先把主题 1 的绘制记录落地——那时两遍共享同一条记录，第二遍的顶点成本已经
很低，合一的边际收益更小。

### 建议 / 收益 / 风险 / 验证

| 项 | 内容 |
|---|---|
| 建议 | B4 **否定**（现状已是最少 VS 调用）；改为「把实例化属性拆成位置流 + 外观流」，与主题 1 合并实施。B5 **降级**：先做采样器对象（无视觉风险的部分），合一本身缓办 |
| 收益 | 属性流拆分：binning 每顶点读取 84 B → ~12 B。B5 合一：省 1 次 draw/帧，量级是几十微秒 |
| 风险 | B5 合一改变混合顺序（V 级，不可逆地要看图）；采样器对象本身 G0 |
| 验证 | 属性流拆分后对比 Adreno 的 binning 带宽（Snapdragon Profiler 的 `Vertex Memory Read`）；B5 若做，必须同进度并排放大图 |

---

## 3 累积纹理与 resolve

### 资料

- **Adreno 的 GMEM**：片上 tile 缓冲，容量约 **256 KiB – 1 MiB**；**bin（tile）的尺寸由 GMEM 容量
  除以渲染目标的格式与大小决定**（[freedreno wiki《Adreno tiling》](https://github.com/freedreno/freedreno/wiki/Adreno-tiling)）。
  同页指出：如果上一次的 tile 缓冲没有被清空，GPU 必须先 restore（主存→GMEM），这个带宽代价
  **可能高于直接重画整个 framebuffer**。
- **Arm**：tile-based 的主要额外开销是顶点→片段的交接，以及 tile 的 load/store
  （[Abstract Machine Part 2](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/the-mali-gpu-an-abstract-machine-part-2---tile-based-rendering)、
  [Vulkan TBR Best Practices](https://docs.vulkan.org/guide/latest/tile_based_rendering_best_practices.html)）。
- **`EXT_shader_framebuffer_fetch`**（[Khronos 规范](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_shader_framebuffer_fetch.txt)）：
  ES 3.0+ 把片段输出声明成 `inout` 即可读到该像素当前的 framebuffer 值；与固定功能混合**正交**
  （"There is no interaction. The two remain orthogonal."）。另有
  `EXT_shader_framebuffer_fetch_non_coherent` 变体，需显式调用 `FramebufferFetchBarrierEXT()`。
  限制：helper invocation 读到的值未定义；基于 framebuffer 读取值算出的坐标做隐式 LOD 的纹理采样
  结果未定义；导数函数作用在 framebuffer 读取值上结果未定义。
- **`EXT_shader_pixel_local_storage`**（[Khronos 仓库](https://github.com/KhronosGroup/OpenGL-Registry/blob/main/extensions/EXT/EXT_shader_pixel_local_storage.txt)、
  [Arm 博文](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/pixel-local-storage-on-arm-mali-gpus)）：
  在 tile 内保存任意逐像素值，避免把中间结果写出主存；**但 PLS 不支持固定功能混合**。
  Arm 的进阶博文明确把「软粒子」列为 PLS 的典型用例。

### 对本管线的适用性

**（a）全屏 RGBA16F 的真实代价。** 魅族 1368×3192 = 4.37 MP，RGBA16F = 8 B/px ≈ **35 MB**。
两件事值得注意：

- 这块目标是**离屏 FBO**（`shared.framebuffer[0]`），不是默认 framebuffer。在 Adreno 上它同样走
  GMEM + resolve。8 B/px 的目标意味着**每个 bin 只能覆盖 GMEM 的 1/2 面积**（相对 RGBA8），
  即 bin 数量翻倍 → binning 的几何遍历与 tile load/store 次数翻倍。这是 RGBA16F 在 tiler 上
  最容易被低估的成本，而**不是**「多写了 4 B/px」那么简单。
- 每帧 `glClear(GL_COLOR_BUFFER_BIT)` 在绑定 FBO 后立刻执行，这是正确做法（让驱动把 tile 标记为
  「不需要 restore」）。freedreno 那条正好说明：**不能**为了省事改成「不清、靠 scissor 局部清」——
  一旦 GMEM 里的 tile 需要 restore，代价反而更高。

**（b）方案 B6「包围盒 scissor」需要修正。** 方案设想的是「按包围盒设 scissor 做清空与 resolve」。
按上面那条，**scissor 清空是有害的**：scissor 会让被排除的 tile 从「clear（免 restore）」变成
「需要 restore」。正确的做法是**换一个维度**：

- 保留**整屏 clear**（它在 tile 上是免费的 flag，不是真的写 35 MB）；
- 只把 **resolve 那一遍**限制在包围盒内（resolve 是真正的全屏片段着色器 + 采样 + 写默认
  framebuffer，4.37 MP 的纹理采样与写出是实打实的）。
- 更进一步：绘制那一遍如果能用 `glScissor` 把**渲染区域**限制到包围盒，Adreno 只会为覆盖到的
  bin 生成命令，能同时省下 binning 与 tile store。但这要求 clear 也在同一个 scissor 里，与上一条
  冲突 —— **需要实测取舍**，两种都试。

**（c）framebuffer fetch 合并累积 + resolve：结论是「合不了全部，但能合掉一层」。**

- 想把「加法累积 + resolve（除以 alpha、转 sRGB）」合成一遍，本质要求是**在最后一个片段写入时知道
  这是最后一个**。粒子是无序叠加的，做不到。framebuffer fetch 不改变这一点。
- 能做的是**去掉中间那张 RGBA16F 纹理**：让粒子直接画到默认 framebuffer，用 `inout` 读回当前值、
  在着色器里做「累积 + 就地 resolve」。但这要求每个片段都做一次除法与 sRGB 转换（现在只做
  4.37 MP 一次），而粒子的 overdraw 远大于 1 —— **算得更多，不划算**。
- PLS 的形态更贴切（tile 内保存 premultiplied 累积，render pass 结束时一次性 resolve 出去），
  但：① PLS **不支持固定功能混合**，累积要自己在着色器里做读-改-写；② PLS 是 Mali/PowerVR 的
  扩展，**Adreno 一般不提供 `EXT_shader_pixel_local_storage`**，而目标设备恰恰是 Adreno；
  ③ ES 里没有跨 draw 的 PLS 保序保证，两遍绘制之间要小心。
- 折中且**风险最低**的一条：把累积纹理从 `RGBA16F` 换成 **`R11F_G11F_B10F` + 单独的 alpha**
  不可行（需要 alpha）；换成 **RGBA8 不可行**（加法累积会饱和）。**保持 RGBA16F，但只在包围盒
  内 resolve**，是本主题里最可落地的一条。

### 建议 / 收益 / 风险 / 验证

| 项 | 内容 |
|---|---|
| 建议 | ① 保留整屏 clear，**只对 resolve 限制包围盒**（B6 需按此修正）；② 另测「绘制 + clear 同用 scissor」是否更好；③ framebuffer fetch / PLS **否定**（Adreno 无 PLS；fetch 合一会让 resolve 数学乘以 overdraw） |
| 收益 | resolve 从 4.37 MP 降到包围盒面积。弹窗场景包围盒常只占屏幕 1/4–1/2 → 每帧省 2–3 MP 的采样 + 写出 |
| 风险 | scissor 用错维度反而触发 tile restore（freedreno 明确警告）；包围盒必须保守，原子 min/max 是精确的，但外扩量要含 `extrapolate` 那一步的位移 |
| 验证 | 分别测「只 resolve 加 scissor」与「绘制也加 scissor」两种；`dumpsys SurfaceFlinger` 之外，用 Snapdragon Profiler 看 `Read Total (Bytes)` / `Write Total (Bytes)` 的变化 |

---

## 4 精度：`mediump` / fp16

### 资料

- **Adreno**：标量架构在用 16 位中等精度浮点跑片段着色器时**可以有两倍性能**
  （[Qualcomm Game Developer Guide](https://docs.qualcomm.com/bundle/publicresource/topics/80-78185-2/gpu.html)）。
  另有具体一条：**FP16 的 inverse sqrt 比 FP32 快 2 倍**
  （[azhirnov/cpu-gpu-arch Adreno 指南](https://github.com/azhirnov/cpu-gpu-arch/blob/main/gpu/Adreno_Guide.md)）。
  同页还给了一条与本管线相关的：**`RGB10_A2` 比 FP16 快**（纹理侧）。
- **Khronos Vulkan 示例《Using explicit 16-bit arithmetic》**
  （[docs.vulkan.org](https://docs.vulkan.org/samples/latest/samples/performance/16bit_arithmetic/README.html)）
  三条硬约束：
  - 「必须仔细向量化，用 `f16vec2` / `f16vec4`。现代 GPU 架构靠**打包的 f16x2 指令**取得算术性能，
    **标量 `float16_t` 几乎没有收益**。」
  - 「不要在 FP16 和 FP32 之间频繁转换。多数 GPU 在两者转换时要花周期。」
  - 「很难保证 FP16 一定有提升。代码越复杂，越难用好 FP16。」
  - 收益也来自**寄存器压力下降 → occupancy 提高**，但这一项很难在样例里演示出来。
- **Godot 的两次实践**是重要的反面证据：
  [PR #99767](https://github.com/godotengine/godot/pull/99767)「移动端着色器默认改 mediump」只拿到
  **2–3%**，且作者最终**关闭该 PR**，改走[PR #107119](https://github.com/godotengine/godot/pull/107119)
  的显式 FP16 路线；社区的总结是「`mediump` 机制不好用，没有带来相对显式 FP16 的实际收益」。

### 对本管线的适用性

方案 D11 的判断方向正确，但**两条要修正**：

1. **ES 3.1 的 GLSL 只有 `mediump` 这一种表达方式**（没有 `float16_t`，那需要
   `GL_EXT_shader_explicit_arithmetic_types_float16`，ES 上普遍不可用）。也就是说，本管线**只能
   走被 Godot 证明收益有限的那条路**，拿不到「显式 FP16 + 手工 f16vec2 向量化」的那份收益。
   期望值应当按 **个位数百分比**设，而不是「两倍吞吐」。
2. **向量化是前提**。要真正命中打包的 f16x2 指令，改的必须是 `vec2` / `vec4` 的整体运算。
   本管线里符合这个形状的有：
   - `peel-blur.comp` 的 11 抽头卷积（`vec4 sum` 的加权累加）——**最合适**，整个核就是 `vec4` 乘加，
     且值域小（速度/权重），`mediump` 完全够用；
   - `peel-project.comp` / `pressure-batched.comp` 的压力标量场——**是标量**，按 Khronos 那条
     「标量 f16 几乎没收益」，改了大概率白改，而且 `shared float p0[1024]` 改成 fp16 需要手工打包成
     `uint`（ES 不支持 `shared float16_t`），复杂度高、收益存疑；
   - `step.comp` 的 `target` 累加是 `vec2`，符合打包形状，但它与 `highp` 的 `p`、`m.src.xy` 反复
     互相运算 → **转换开销会吃掉收益**（Khronos 明确警告）。
   - `material.frag` 的颜色运算是 `vec3`/`vec4`、值域 [0,1]，**天然适合 `mediump`**，且片段着色器
     正是 Qualcomm 那条「两倍性能」所指的阶段。**这是方案 D11 完全没提到、但最该做的一处。**

3. **Maleoon 的严格链接**（已记在 `.claude/rules` 与记忆里：`glsl-cross-stage-precision-strictness`）：
   跨阶段共享的 uniform 与 varying 两侧必须显式同精度。本管线 `material.vert` → `material.frag`
   有 9 个 varying，改精度时必须两边一起改，且 Adreno 的宽容会掩盖问题 —— **每次改都要在 Maleoon
   上编译一次**。

4. **位置必须留 `highp`**：3192 px 的屏幕，fp16 在 1024–2048 区间的间隔是 1.0 px，2048–4096 是
   2.0 px，直接可见。方案 D11 这条判断是对的。

### 建议 / 收益 / 风险 / 验证

| 项 | 内容 |
|---|---|
| 建议 | 按「先片段、再 blur、最后才考虑积分」的顺序：① `material.frag` 的颜色链改 `mediump`（vec3/vec4，值域受限）；② `peel-blur.comp` 的 `vec4` 卷积改 `mediump`；③ 压力标量场与 `step.comp` **暂不改**（标量无收益 / 转换吃收益） |
| 收益 | 期望个位数百分比，最乐观到片段阶段的 10–20%；**不要按「两倍」排期** |
| 风险 | Maleoon 跨阶段精度严格拒链接；`mediump` 在不同驱动上被解释成不同实际精度 → G1 判据必须两台设备都过；累积纹理是 RGBA16F，片段输出本来就会被截到 fp16，所以颜色改 mediump 的**视觉风险极低** |
| 验证 | 分三次改、每次单独 A/B（否则归因不了）；每次先在 Maleoon 上编译；G0/G1 按已有两步判据（先自比较再 A/B） |

---

## 5 GPU 压力投影：迭代数、求解器与分块

### 先把规模算清楚

`PeelPressure` 里 `span = min(cardWidth, cardHeight)`、`gridCell = span/96`、
`columns = ceil(cardW/gridCell) + 192`、`rows = ceil(cardH/gridCell) + 192`。全屏消散（魅族
1368×3192）代入：`gridCell = 14.25 px`、`columns = 96 + 192 = 288`、`rows = 224 + 192 = 416`，
**cells = 119,808**。

这带出三个此前没写进方案的事实：

1. **halo 占了压倒性多数。** 两个方向各 96 格 halo（= 一个完整的 `span`）。方形卡片时网格是
   288×288 而卡片只占中央 96×96 —— **卡片本体只有 11% 的格子，89% 是 halo**。全屏时好一些，
   卡片占 96×224/119808 = 18%。
2. **100 次 Jacobi 远没有收敛，而且不可能收敛。** Jacobi 每次迭代只把信息传播一格，100 次迭代的
   影响半径就是 100 格，**小于网格对角 288–416 格的一半**。现在这个场**本质上是一个局部松弛的结果**，
   不是 Poisson 方程的解。
3. **`splat` 的清零与 `blur` 跑的是全网格，只有 Jacobi 用了活动 tile 列表。** 见
   `PeelPressure.update`：`pass==0` 的清零 `glDispatchCompute((cells+255)/256,1,1)`、两遍 blur
   `glDispatchCompute(groupsX, groupsY, 1)` 都是全网格；只有求解器走 `glDispatchComputeIndirect`。
   两遍 11 抽头 blur = **2 × 11 × 119,808 ≈ 2.6M 次抽头**，其中约 85% 落在恒为零的 halo 上。

### 资料

- **Jacobi 易并行但收敛慢**；Gauss-Seidel 迭代次数更少。但在 GPU 上，**红黑 GS 需要按奇偶分支，
  引入线程发散、妨碍向量化，会造成明显的性能下降**；有工作用「X 形多色 GS」（二维四色）来消除
  条件分支、让访存更规整（[MDPI《Application of Graphics Processor Unit Computing Resources to
  Solution of Incompressible Fluid Dynamics Problems》, Computers 14(5):170, 2025](https://doi.org/10.3390/computers14050170)）。
- 有实测给出**反直觉**的结论：红黑 GS 迭代次数比 Jacobi 少、SOR 更少，但在某代 GPU 上
  **Jacobi 的墙钟反而更快**，在另一代上 SOR 才略胜（同上综述引用的对照）。
- **多重网格**在网格变大时相对 O(N²) 的迭代法优势明显，常作为 CG 的预条件子
  （[Bolz et al.《Sparse Matrix Solvers on the GPU: Conjugate Gradients and Multigrid》SIGGRAPH 2003](http://www.cs.columbia.edu/cg/pdfs/28_GPUSim.pdf)、
  [McAdams et al.《A parallel multigrid Poisson solver for fluids simulation on large grids》SCA 2010](https://dl.acm.org/doi/abs/10.5555/1921427.1921438)、
  [2025 年的 matrix-free FAS 多重网格 GPU 实现, arXiv:2510.11152](https://arxiv.org/pdf/2510.11152)）。

### 对本管线的适用性 —— 方案 C8 需要一条重要修正

**`peel-project.comp` 与 `pressure-batched.comp` 的迭代体不是线性算子：**

```glsl
new_pressure[i] = velocity[i].z > .025 ? max((neighbours - div) * .25, 0.) : 0.;
```

`max(..., 0.)` 是单边约束（压力非负），`occupied > .025` 是一个随时间变化的掩模。后果：

- **PCG 直接排除。** 共轭梯度要求固定的对称正定线性算子；带 clamp 的迭代不是线性映射，CG 的
  正交性会立刻失效。方案第 4 节把 PCG 列进候选，**应当划掉**。
- **多重网格要大改，且会改变画面。** 粗网格上的 clamp 语义与 occupancy 掩模都需要重新定义
  （标准做法是 projected multigrid / 主动集，复杂度高）。更关键的是上面第 2 点：**现在的画面是
  「100 次 Jacobi 的欠收敛结果」调出来的**。多重网格若真的把它解收敛了，压力场会显著不同，这
  **不是 G1、是 V 级**，等于重调一次视觉。方案把它排在「7 与 8 都不够时考虑」是对的，但理由要
  改成「它会改画面」而不是「改动大」。
- **红黑 GS / SOR 是可行的，而且能绕开发散问题。** 关键是不要写成按坐标奇偶分支，而是让每个线程
  按固定奇偶直接算出自己的坐标：一个工作组 256 线程负责 32×32 tile 里的 512 个红格，
  `x = 2*(k%16) + ((y + parity) & 1)`。这样两次半遍都是满 lane、无分支，正是 MDPI 那篇所说的
  「多色消除条件分支」的做法。`max(...,0.)` 在 GS 里天然对应投影 Gauss-Seidel（PGS），数值上成立。
  - 但**红黑 GS 与现在的分块方案有冲突**：`pressure-batched.comp` 靠「32×32 共享区 + 边带每次
    缩一格」在一次 dispatch 里做 8 次 Jacobi。GS 的每半遍都依赖同一次迭代内另一半的最新值，
    边带缩减规则要重推（红半遍缩 1 格、黑半遍再缩 1 格，8 次 GS 消耗约 16 格边带，而 8 次 Jacobi
    只消耗 8 格）。32×32 的共享区要么把有效输出从 16×16 降到 8×8（**工作组数翻 4 倍**），要么
    把共享区放大到 48×48 = 4 × 2304 × 4 B = **36 KiB**，而 **ES 3.1 只保证 16 KiB 共享内存**
    （现在的 4 × 1024 × 4 B 正好顶格）。Adreno 740 与 Mali 实际给 32 KiB，但 36 KiB 大概率不行。
    折中：共享区用 fp16 打包（`shared uint` 存两个半精度），或把 `divergence` / `occupied` 从
    共享区改回全局读。

**因此本主题里最该先做的不是换求解器，而是三件更便宜、风险更低的事：**

| # | 改动 | 依据 |
|---|---|---|
| a | **blur 与 splat 清零也走活动 tile 列表** | 85% 的抽头落在恒零的 halo 上；活动 tile 列表已经有了，`pressure-tiles.comp` 只需多输出一份 blur 用的粒度（blur 是 11 抽头，活动区要按 ±5 格外扩） |
| b | **收缩 halo** | 96 格 = 一个完整 `span` 的外扩。粒子实际到达范围可由积分时的原子包围盒给出（与主题 3 的包围盒同一份数据），把 halo 按实际需要设成 32–48 格，格数降到 1/2–1/4 |
| c | **先测残差曲线再定迭代数** | 方案 C7 已写。补充口径：因为迭代远未收敛，**残差不会明显下降**；真正该测的是「第 N 次与第 100 次的压力场之差」随 N 的曲线，以及它对最终画面的影响 |

（a）+（b）叠加起来，压力更新的格子工作量有望降到现在的 1/4 – 1/8，**且完全不改变卡片范围内
的数值**（halo 里本来就是零，前提是收缩后的 halo 仍然覆盖粒子到达范围 —— 这一点由包围盒保证，
是可证明的）。这比换求解器的收益更大、风险更小。

### 建议 / 收益 / 风险 / 验证

| 项 | 内容 |
|---|---|
| 建议 | 顺序改为 **C10（活动 tile 覆盖 blur / clear）→ halo 收缩 → C7（迭代数）→ C8（红黑 GS）**；**PCG 划掉**；多重网格的理由改成「它会改画面」 |
| 收益 | C10 + halo：压力更新格子工作量 → 1/4–1/8，且卡片内 G0；C7：线性于迭代数；C8：同等画面下迭代数可降约 2 倍，但要重推边带且可能撞共享内存上限 |
| 风险 | halo 收缩若不保守会截断远处粒子的压力响应（V 级）；红黑 GS 的共享内存需求可能超过 ES 3.1 保证的 16 KiB；多重网格 / PCG 不适用 |
| 验证 | halo 收缩：用原子包围盒统计粒子在整段动画里的最大到达距离（以 `span` 为单位落盘），取最大值再加一格；C10：改完之后卡片范围内的压力场必须与改前**逐位相同**（这是可做的 G0 对照） |

---

## 6 粒子 → 网格 splat：原子加法 vs 排序 / 分桶

### 资料

- **直接用 `atomicAdd` 会因全局内存竞争带来可观的开销**；MPM 的 P2G 步骤里，邻近粒子会同时写
  同一批格点，用原子操作最简单但「不加处理性能会被严重拖累」
  （[《Principles towards Real-Time Simulation of Material Point Method on Modern GPUs》, arXiv:2111.00699](https://arxiv.org/pdf/2111.00699)）。
- 缓解手段分两类：**不排序**（听任粒子乱序）或**用洗牌 / 分桶调整顺序**，让同一个 warp 内的粒子
  更少撞同一格。该文同时给出反面数据：**完全不做粒子排序反而能带来 8%–17% 的加速**（不同分辨率下），
  说明全量重排本身就是瓶颈。
- 另一条常被引用的经验：按空间位置周期性排序能更好地利用 GPU 的存储层次，**粒子速度高、频繁换格
  时尤其如此**（同上）。

### 对本管线的适用性

现状：`peel-splat.comp` 每片做双线性 splat，**4 个格子 × 3 次 `atomicAdd` = 每片 12 次原子操作**。
15 万片 → 每次压力更新 **约 180 万次全局原子加**。

几点与文献对照后的判断：

1. **不要引入排序。** 本管线的粒子顺序天生就是空间有序的（`id = grid.y*nx + grid.x`，
   见 `material.vert` L48），同一个 256 线程工作组覆盖源卡片上连续的 256 个微片，早期就是空间相邻的。
   文献那条「不排序反而快 8–17%」正好支持保持现状。
2. **共享内存分桶（tile-local 原子）在本管线上不成立。** MPM 的做法是「工作组负责一个网格 tile，
   在共享内存里做原子，最后一次性 flush 到全局」。这要求**粒子按目标格子分组**。本管线的粒子按
   **源格子**分组，随着 age 增大会散开到 halo 各处 —— 分桶需要先做一次空间排序，正好是上一条否掉的东西。
3. **真正划算的是减少原子次数本身**：
   - **双线性 → 最近格**：每片 12 次 → **3 次原子**，直接 4 倍。代价是 splat 引入 1 格量级的量化
     噪声。但注意 **splat 之后立刻跟着两遍 11 抽头高斯**，权重
     `[…, .0939, .2283, .3069, .2283, .0939, …]` 对应 σ ≈ 1.2 格，双线性额外提供的那约 0.4 格平滑
     基本是冗余的。**这是本主题里性价比最高的一条**，属 V 级但预期几乎看不出来。
   - **把三个 `atomicAdd` 合成更少的操作**：GLES 3.1 没有 64 位原子（`EXT_shader_atomic_int64` 在
     移动端不普遍），把 `peel.x` / `peel.y` 打包进一个 32 位整数会溢出（每个分量已经是 ×4096 的
     定点）。**这条走不通。**
   - **跳过 mass 为零的片**：已经有 `if(opacity<=0.) return;` 与寿命判据，没有额外空间。

### 建议 / 收益 / 风险 / 验证

| 项 | 内容 |
|---|---|
| 建议 | **保持原子、不引入排序**（文献直接支持）；试「双线性 → 最近格」把每片原子从 12 降到 3 |
| 收益 | 最近格：splat 阶段的原子操作降到 1/4（约 180 万 → 45 万次 / 更新） |
| 风险 | 最近格属 V 级：理论上引入 1 格量化，但后续 σ≈1.2 格的高斯会吸收大部分；仍需并排放大图 |
| 验证 | 先在离屏对照里把两种 splat 的**压力场**落盘做逐格差（而不是直接看画面），量化差异幅度；再看画面 |

---

## 7 回放缓冲布局：帧主序 vs 片主序、fp16 压缩

### 现状

`history.comp` 的寻址是 `int h = (r.z + frame - r.x) * 5;`，其中 `r.z` 是该片在历史缓冲里的起始
偏移（`ParticleReversePlan` 按片依次累加）。这是**片主序、变长段**：

- 对固定的 `frame`，相邻 invocation `i` 与 `i+1` 的地址相差**该片自己的段长**
  （`last-first+1` 个采样 × 5 float）。典型寿命 0.3 s、`samples = 36` → 段长约 21 个采样
  = 105 float = **420 B**。
- 也就是说 **每条 lane 各自命中一条独立的 cache line，合并度为零**。一个 256 线程工作组要触达
  256 条不同的 cache line 去读 256 × 20 B = 5 KB 的有效数据。
- 每次 restore 还要多读一份 `h+5 … h+9`（手势插值），有效数据翻倍到 40 B / 片。

### 资料

- SoA 与 AoS 的经典结论：**GPU 上多数情况 SoA 更好**；AoS 时相邻线程读的是跨步地址，需要更多
  cache line 才能满足一次事务（[Locality Optimized Unstructured Mesh Algorithms on GPUs, arXiv:1802.03749](https://arxiv.org/pdf/1802.03749)）。
  SoA 元素大小的经验取法是 **cache line / warp size**（例：128 B / 32 = 4 B）。
- Arm / Qualcomm 的编译器会做 uniformity 分析，把整 dispatch 一致的载入提成标量前导
  （[Sebastian Aaltonen《No Graphics API》](https://www.sebastianaaltonen.com/blog/no-graphics-api)）——
  对本管线的逐片载入没有帮助，但说明「让访问模式规整」在移动端编译器上确实有回报。
- 顶点数据侧的等价实践已在主题 1 引用：Android 的《Vertex data management》把位置流拆开之后，
  binning 每顶点读取 **48 B → 12 B**、取数停顿 **40% → 0%**。同一条道理适用于 compute 的 SSBO。

### 对本管线的适用性

**（a）帧主序的收益是真的，但改造代价被低估了。** 帧主序（同一帧的所有片连续存放）能把合并度
从 0 提到接近满 —— 20 B 步长，一条 64 B line 能喂 3.2 条 lane。但因为每片的存活区间不同，
「第 f 帧的第 k 个存活片」这个编号需要一张按帧的前缀表，而这张表本身是 `count × samples` 量级
（15 万 × 36 = 540 万个 int = 21 MB），比省下来的还多。**可行的折中有两条**：

- **按出生时间给片重新编号**。`first` 单调之后，同一帧的存活片在编号轴上接近一段连续区间
  （`last` 不严格单调，会有毛边），片主序也能得到相当好的局部性，而且**不需要任何额外表**：
  只要在建材阶段就按 `birth` 排序输出。**代价是打乱了「下标 = 源格子」的隐含关系** —— 但
  `material.vert` 的 `id` 是从 `m.src.w` 显式读出来的（L44），`peel-splat.comp` 比的也是
  `m.src.w` 的值而不是下标，所以重排不影响正确性。
- **不改布局，改元素大小**（见下）。

**（b）fp16 / 定点压缩是更确定的一条。** 现在 `STATE_BYTES = 20`（pos.xyz + vel.xy，全 float32）。

- `pos.xy` 是**绝对屏幕坐标**（最大约 3192 + halo），fp16 在这个量级的间隔是 2 px，**不能直接用
  fp16**。但用 **16 位定点**（以整个 `grid_bounds` 范围为标度）可得 6000/65536 ≈ **0.09 px**，
  完全不可见。
- `pos.z` 的量级是 `span*0.035` 级，fp16 足够。
- `vel.xy` 是 px/s 量级，只用于 `extrapolate`（`render()` 里默认 `t - step/240`，即 ≤ 4.2 ms 的
  外推），fp16 的相对精度 2⁻¹¹ 绰绰有余。
- 合计 **20 B → 10 B**。带宽直接减半，且**同时把 `ParticleReverseHistory` 里那条
  `check(plan.bytes <= GL_MAX_SHADER_STORAGE_BLOCK_SIZE)` 的余量翻倍** —— 全屏 15 万片 × 21 采样
  × 20 B ≈ 63 MB，这个检查不是摆设。
- ES 3.1 的 GLSL 自带 `packHalf2x16` / `unpackHalf2x16` 与 `packUnorm2x16` / `unpackUnorm2x16`，
  按 `uint` 存即可，**不需要任何扩展**。

### 建议 / 收益 / 风险 / 验证

| 项 | 内容 |
|---|---|
| 建议 | 先做 **（b）压缩到 10 B / 采样**（`packUnorm2x16` 存位置、`packHalf2x16` 存 z 与速度）；**（a）按出生时间重新编号**作为后续项 |
| 收益 | （b）历史读写带宽减半、单缓冲上限余量翻倍；（a）合并度从约 0 提到接近连续 |
| 风险 | （b）位置量化 0.09 px（属 G1，G0 会破）；（a）重排会改变原子 splat 的累加顺序 → 浮点结果变（G1），且要逐一确认按下标的假设 |
| 验证 | （b）离屏对照：同一快照下压缩前后每帧位置的最大偏差应 < 0.1 px，落盘统计；（a）先只测「重新编号后」的墙钟，不与（b）同批 |

---

## 8 多上下文与优先级

### 资料

- **`EGL_IMG_context_priority`**（[Khronos 规范](https://registry.khronos.org/EGL/extensions/IMG/EGL_IMG_context_priority.txt)）：
  `eglCreateContext` 的属性表里传 `EGL_CONTEXT_PRIORITY_LEVEL_IMG`，三档
  `HIGH` / `MEDIUM`（默认）/ `LOW`。**规范原文把它定义成一个 hint**：
  "This attribute is a hint, as an implementation may not support multiple contexts at some
  priority levels and system policy may limit access to high priority contexts."
  另外三条必须记住的：
  - 实现**可以不区分低档**（请求 `LOW` 却返回 `MEDIUM`）；
  - 高优先级上下文的数量可能被限制、可能被系统策略限制给特权进程；
  - **实际拿到的优先级必须用 `eglQueryContext` 读回来**。
  规范对调度只有定性描述：高优先级「时间片最大、延迟更低」，低优先级「时间片小、延迟高」。
- **`EGL_NV_context_priority_realtime`**（[Khronos 规范](https://www.khronos.org/registry/EGL/extensions/NV/EGL_NV_context_priority_realtime.txt)）
  在三档之上加一档带特权的实时级，抢占「应当发生在有待处理的渲染命令且出现隐式或显式 flush 时」。
  典型用例是 HMD 的系统合成器。**Adreno / Mali 上一般不提供，本项目用不上。**
- **抢占粒度**：Qualcomm 的公开专利把「两级 binning」描述成能给出更细的抢占粒度 —— 「两级 binning
  模式的工作负载抢占粒度更好（划分更小），执行可以在每个粗 bin 处停下」
  （[US 12,026,799](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/12026799)、
  [US 12,266,030](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/12266030)）。
  也就是说 **Adreno 的抢占点在渲染的 bin 边界上**；这些文档**没有**说 compute dispatch 内部可以被抢占。
- **跨上下文同步**（[Arm《Thread Synchronisation》白皮书](https://community.arm.com/cfs-file/__key/telligent-evolution-components-attachments/01-2066-00-00-00-00-42-84/Whitepaper_2D00_ThreadSync.pdf)、
  [GL_ARB_sync 规范](https://developer.download.nvidia.com/opengl/specs/GL_ARB_sync.txt)）：
  同步对象可以在同一 share group 的上下文之间共享，一个上下文 `glFenceSync`，另一个
  `glWaitSync` / `glClientWaitSync` 等待。**最常见的坑是 flush**：`glClientWaitSync` 有
  `GL_SYNC_FLUSH_COMMANDS_BIT` 可以自己冲，而 **`glWaitSync` 没有** —— 生产侧必须自己
  `glFlush()`，否则消费侧可能永远等不到（死锁）。多个上下文同时等同一个 sync 时，顺序与竞态
  由程序自己保证。

### 对本管线的适用性

方案 F14 的方向成立，但要补三条纪律：

1. **必须读回实际优先级**。规范明文说是 hint。项目里已经有「恢复默认必须读回来核对」的纪律
   （见记忆 `cache-keys-must-use-stable-values` 同批的 A/B 纪律），这里同样适用：
   `eglQueryContext(dpy, ctx, EGL_CONTEXT_PRIORITY_LEVEL_IMG, &v)`，把两个上下文实际拿到的档位
   都写进 `ParticlePerfProbe` 的 note。**拿不到 HIGH 时这一整项的结论不成立**，不能按「设了就生效」
   去归因。
2. **优先级替代不了「把 dispatch 切短」**。Adreno 的抢占点在 bin 边界；公开资料没有说
   `glDispatchCompute` 能被中途抢占。本管线后台上下文里的**单次** dispatch 规模很大
   （Jacobi 一批覆盖 119,808 格、积分一次覆盖 15 万片）。若抢占确实以 dispatch 为粒度，
   F14 最多把「等一整段 26 次 dispatch」缩短到「等 1 次 dispatch」——这仍然是毫秒量级。
   **因此 `yieldToWindow` 那条路不应在 F14 落地时直接删掉，应改成「按实测决定是否保留」。**
   这一点与方案里「`yieldToWindow` 那类栅栏串行化可以拆掉」的表述冲突，**需要修正**。
3. **交接必须是 `fence + flush + wait`，不是只 fence**。历史缓冲由后台上下文写、出帧上下文读，
   写完之后后台上下文要 `glFenceSync` 再 `glFlush`；出帧上下文 `glWaitSync`。只要漏掉 flush，
   症状是偶发卡死或读到旧数据，而且**在单线程测试里复现不出来**。

另外值得注意的一条：项目现在是「常驻 GL 线程 + 常驻上下文」（第二批 D4）。F14 要加第二个上下文，
就回到了「两条线程各自有上下文」的形态。`ps -T` 里 Adreno 驱动会派生同名工作线程这条已知事实
（见 `.claude/rules/adb.md`）意味着**线程数不能作为判据**，要靠代码结构保证只有两条。

### 建议 / 收益 / 风险 / 验证

| 项 | 内容 |
|---|---|
| 建议 | 做，但 ① 必须 `eglQueryContext` 读回并记录实际档位；② 保留 `yieldToWindow` 作为可开关项而不是直接删；③ 交接写成 `fence → flush → wait` 三步 |
| 收益 | 出帧上下文的一帧不再排在后台上下文整段 dispatch 之后；同时解决「弹窗 A 消散时弹窗 B 预计算抢 GPU」 |
| 风险 | 优先级是 hint，可能不生效；抢占粒度可能只到 dispatch 边界；漏 flush 会造成偶发死锁 |
| 验证 | 用观察器记录「提交 → 呈现」的间隔分布（已有），在**后台有预计算在跑**与**没有**两种条件下各跑一轮；并把两个上下文的实际优先级打进产物 |

---

## 9 呈现：SurfaceView 合成、呈现时刻与帧率投票

### 现状（先核对代码，再看资料）

本项目已经做到的：

- `ParticleWindowSurface`：`setZOrderOnTop(true)` + `holder.setFormat(PixelFormat.TRANSLUCENT)`；
- `applyFrameRate`：API 31+ 用 `setFrameRate(60f, FRAME_RATE_COMPATIBILITY_DEFAULT,
  CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)`，API 30 用两参版本；
- 每帧 `eglPresentationTimeANDROID` 钉呈现时刻；
- `ParticleFrameTimestamps`：JNI 封装 `EGL_ANDROID_get_frame_timestamps`，常量 `PENDING = -2`、
  `INVALID = -1`。

### 资料核对

- **`EGL_ANDROID_presentation_time`**（[Khronos 规范](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_presentation_time.txt)）：
  单位纳秒、绝对时间用 `CLOCK_MONOTONIC`（Java 的 `System.nanoTime()`）；**多次调用后者覆盖前者**；
  "does not cause the color buffer to be made visible" —— 它只是被 `eglSwapBuffers` 消费的状态。
  规范**没有**定义「时间在过去/未来」的行为。实践上 Android 的 BufferQueue 把它当成「不早于」：
  **设到未来若干帧会把流水线加深相应的帧数**（[osu-framework PR #6792 的讨论](https://github.com/ppy/osu-framework/pull/6792)）。
  → **需要核对本项目每帧写进去的值**：若为了对齐节拍写了「下一次 vsync + k 帧」，那是在首帧延迟上
  自己加了 k 帧。这是一条**值得在 0' 阶段专门看一眼**的具体嫌疑点。
- **`EGL_ANDROID_get_frame_timestamps`**（[Khronos 规范](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_get_frame_timestamps.txt)）：
  要先 `eglSurfaceAttrib(EGL_TIMESTAMPS_ANDROID, EGL_TRUE)`（默认关）；`eglGetNextFrameIdANDROID`
  必须在**那一次 swap 之前**取；`PENDING = -2`、`INVALID = -1`。本项目的常量与调用顺序与规范一致，
  **这一项无需改动**。可用的时间戳里只有 `EGL_DISPLAY_PRESENT_TIME_ANDROID` 是真正的扫出时刻，
  项目的判断也正确。
- **60 Hz 投票在 120 Hz 面板上的行为**（[AOSP《Multiple refresh rate》](https://source.android.com/docs/core/graphics/multiple-refresh-rate)、
  [Android Developers Blog《High refresh rate rendering on Android》](https://android-developers.googleblog.com/2020/04/high-refresh-rate-rendering-on-android.html)）：
  SurfaceFlinger **会挑一个是该速率倍数的刷新率**；文中的例子是两层分别投 24 与 60，SF 选 120。
  所以「投 60、面板留在 120、每两拍提交一次」这个理解是**对的**，代码注释无误。
  但要补两条：
  - `CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS` 意味着**不能无缝切换时这一票直接被忽略**；
  - **SF 是按「所有活动图层」一起决策的**。宿主窗口 + 弹窗 + 粒子层三层同时在更新时，最终速率
    由三者共同决定，粒子层单独投 60 不保证结果。这正好对应方案 F16 想查的东西。
- **Device vs Client 合成**（[AOSP《SurfaceFlinger 与 WindowManager》](https://source.android.com/devices/graphics/arch-sf-hwc)）：
  SF 收齐可见图层的缓冲之后**询问 HWC**该怎么合成；HWC 把某层标成 client composition 时由 SF
  （即 GPU）来合。**AOSP 文档没有给出 overlay plane 的数量上限 —— 那是逐硬件的，公开文档查不到。**
  因此这一项**只能在设备上读**，不能从文档推断。项目已有的手段正好够用：
  `dumpsys SurfaceFlinger` 的图层表里 `composition: Device/Client`（`.claude/rules/adb.md`
  的 FableSol 那一节已经写了完整方法与 dataspace 的位拆解）。

### 一条资料支持的新候选

粒子 SurfaceView 现在是 decor 尺寸（全屏）。**若把它按本次动画的包围盒设成更小的固定尺寸**
（`SurfaceHolder.setFixedSize` 或布局尺寸），三处同时受益：

1. GL 侧 viewport 与 resolve 面积按比例下降（与主题 3 的包围盒同一份数据）；
2. 累积 FBO 也可以按同样尺寸建，直接减掉 RGBA16F 的 bin 数量（主题 3）；
3. 合成侧这一层的面积变小，落到 Client 合成时的 GPU 代价也按比例下降。

风险：包围盒要在动画**开始前**确定（Surface 尺寸不能逐帧改），所以必须用「整段动画的保守上界」
而不是逐帧包围盒；弹窗与卡片场景容易给出上界，全屏消散给不出（就退回全屏）。

### 建议 / 收益 / 风险 / 验证

| 项 | 内容 |
|---|---|
| 建议 | ① 核对每帧 `eglPresentationTimeANDROID` 写入的值有没有把流水线加深；② 三层叠加时读 `dumpsys SurfaceFlinger` 定合成类型（**只能实测**）；③ 新候选：按整段动画的保守包围盒缩小粒子 Surface |
| 收益 | ① 最多省 k 个 vsync 的首帧延迟；③ 弹窗场景下 GL + 合成面积可降到 1/4–1/2 |
| 风险 | ③ 上界估错会裁掉飞出的粒子（V 级且很明显），必须用积分的原子包围盒在离屏对照里先验证上界成立 |
| 验证 | ① 修前修后都用 `EGL_DISPLAY_PRESENT_TIME_ANDROID` 量「请求 → 呈现」；② 每台设备各取一次 `dumpsys SurfaceFlinger` 全量落盘再解析（不要只 grep 一行） |

---

## 10 交互到首帧

### 资料

- **链路构成**（[Android Performance《Perfetto 系列 5：基于 Choreographer 的渲染流程》(2025-03)](https://androidperformance.com/en/2025/03/26/Android-Perfetto-05-Chorergrapher/)、
  [《系列 8：Vsync 机制》(2025-08)](https://androidperformance.com/en/2025/08/05/Android-Perfetto-08-Vsync/)、
  [AOSP《VSync》](https://source.android.com/docs/core/graphics/implement-vsync)）：
  输入事件经 InputChannel 送到应用 → `Choreographer` 的 INPUT 回调 → ANIMATION → TRAVERSAL → 绘制 →
  RenderThread → `queueBuffer` → SF 在下一个 vsync latch → 再下一个 vsync 扫出。
  Android 13 起引入 `vsync-appSf`，把「唤醒 SF」与「面向需要与 SF 同步的客户端」分开。
  `DispSync` 是软件锁相环，`Choreographer` 与 SF 用的 VSYNC / SF_VSYNC 由它生成，
  **tick 相对硬件 vsync 的偏移逐设备不同**。
- **不要跑得比显示器快**：不用 `Choreographer` 给渲染循环定速会「渲染过快」，反而**加大提交到呈现
  的延迟**（[Android《Frame rate》](https://developer.android.com/media/optimize/performance/frame-rate)）。
- **`View.requestUnbufferedDispatch`**（[Android 参考](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)）：
  请求输入系统不要按帧批处理 MotionEvent，一有就送，直到对应的 `ACTION_UP`。
  **但官方明确写了反面**：这个 API「不适合大多数应用」，缓冲派发本身有很多好处，
  「在多数 MotionEvent 流上请求非缓冲派发不会改善输入延迟」，副作用包括**延迟变大、滚动抖动、
  无法享受系统重采样**。
- **ADPF / `PerformanceHintManager`**（API 31+，
  [AOSP《Performance Hint API》](https://source.android.com/docs/core/perf/performance-hint-api)、
  [NDK 参考](https://developer.android.com/ndk/reference/group/a-performance-hint)）：
  用一组线程 ID `createHintSession(tids, targetDurationNanos)`，每个周期结束
  `reportActualWorkDuration`，系统据此调核心放置与频率，把实际耗时拉向目标。明确适用于
  **周期性工作负载（如出帧）**。

### 对本管线的适用性

方案 G17 的目标是「识别 → 首个非零画面 < 50 ms」。按上面的链路，可用的杠杆有四条，按把握排序：

1. **首帧不要等 Choreographer。** 粒子层是**独立的 SurfaceView**，它的提交不经过宿主窗口的
   traversal。识别到左滑之后，可以直接把「建材 + 第一帧」派到常驻 GL 线程并立刻 `eglSwapBuffers`，
   而不是等下一个 Choreographer 回调。**这一条能省下最多一整帧（16.7 ms）**，而且是 G0 —— 画面内容
   一个像素不变，只是更早上屏。需要核对 `ParticleClock`（现在是 `Choreographer.postFrameCallback`
   驱动）在首帧这一次是否绕开了它。
2. **ADPF 的 hint session**。把常驻 GL 线程 + 材料工作线程放进一个 session，目标时长设成 16.6 ms，
   每帧 `reportActualWorkDuration`。这正是「三星建材 63–72 ms 未归因」那一类问题的对口手段
   （比 `THREAD_PRIORITY_URGENT_DISPLAY` 更直接：后者只改 nice 值，不改核心放置与频率）。
   方案 G17 里只写了亲和性与线程优先级，**应当补上 ADPF**。
3. **`eglPresentationTimeANDROID` 在首帧写「尽快」**。见主题 9：把它设到未来会加深流水线。
   首帧应当写当前时刻或最近的 vsync，而不是按稳态节拍算出来的未来时刻。
4. **`requestUnbufferedDispatch` 不建议用。** 官方文档自己给了反面结论。左滑的识别阈值是位移，
   批处理带来的延迟上界是一帧；而关闭批处理会失去系统重采样，在滑动场景里容易换来抖动。
   **列为否定项。**

另外一条与方案 G18 一致、资料也支持的：弹窗消散有涟漪结算这段「合法等待」，把建材与管线准备
压进这段时间是纯赚的。这属于「用已有的等待窗口做事」，没有资料层面的风险。

### 建议 / 收益 / 风险 / 验证

| 项 | 内容 |
|---|---|
| 建议 | ① 首帧绕开 Choreographer 直接提交（G0）；② 加 ADPF hint session；③ 首帧的 presentation time 写「尽快」；④ `requestUnbufferedDispatch` **否定** |
| 收益 | ① 最多 16.7 ms；② 目标是把建材的 63–72 ms 压下来，量级待实测；③ 每多写一帧未来就是 16.7 ms |
| 风险 | ① 首帧与后续帧的时间基准要一致，否则第一拍会跳（项目第二批已统一时间基准，需复核）；② ADPF 在部分厂商 ROM 上是空实现，属于「设了没效果」，不会更差 |
| 验证 | 用 `EGL_DISPLAY_PRESENT_TIME_ANDROID` 量「识别 → 首个非零画面」，每条路径 10 轮取分布；ADPF 那项另看 `reportActualWorkDuration` 前后的建材耗时分布 |

---

## 11 总表：按本管线的预期收益排序

收益列是**按资料推断的量级**，不是实测；G 列是视觉保证级别（G0 逐位相同 / G1 浮点噪声 / V 可见）。

| 排序 | 条目 | 对应方案 | 预期收益 | G | 风险 | 依据强度 |
|---|---|---|---|---|---|---|
| 1 | 逐顶点数学下沉到逐片每帧一次（绘制记录 + 位置/外观两个属性流） | A1 | 逐片昂贵求值 **16 → 1 次**；binning 每顶点 84 B → ~12 B | G1 | 裁剪判定要一并下沉 | 强（Adreno 双份顶点着色器 + Android 实测的 binning 带宽数字） |
| 2 | 压力更新的 blur / clear 也走活动 tile + **收缩 halo** | C10（扩） | 格子工作量 **→ 1/4 – 1/8**；卡片内 G0 | G0/V | halo 上界必须由包围盒证明 | 强（现状 89% 格子是恒零 halo，是代码事实） |
| 3 | resolve 按包围盒 scissor（**clear 仍整屏**） | B6（修正） | 每帧省 2–3 MP 的采样与写出 | G0 | 用错维度会触发 tile restore | 强（freedreno 明确警告 restore 更贵） |
| 4 | 逐步不变量提成 uniform / 预存进材料缓冲 | A2 | `step.comp` 每步的 sin/cos/exp 明显下降 | G1 | 需逐项确认「真的与步无关」 | 强（纯代码分析，无外部依赖） |
| 5 | 首帧绕开 Choreographer 直接提交 | G17（补） | 最多 **16.7 ms** 首帧延迟 | G0 | 时间基准要一致 | 强（链路构成明确） |
| 6 | 历史缓冲压到 10 B/采样 | 新增（主题 7） | 历史带宽减半；单缓冲上限余量翻倍 | G1 | 位置量化 0.09 px | 中（ES 内建打包函数，无扩展依赖） |
| 7 | splat 双线性 → 最近格 | 新增（主题 6） | 原子操作 **180 万 → 45 万次/更新** | V | 后续 σ≈1.2 格高斯会吸收大部分 | 中（MPM 文献支持「减少原子」，但取舍是本项目自己的） |
| 8 | 双上下文分优先级 | F14（补纪律） | 出帧不再排在整段 dispatch 后 | G0 | 优先级是 hint；抢占可能只到 dispatch 边界 | 中（规范只有定性表述） |
| 9 | 迭代数 100 → 40–60 | C7 | 线性于迭代数 | V | 现状本就欠收敛，差异形态未知 | 中（需要先测「与 100 次之差」的曲线） |
| 10 | `material.frag` + `peel-blur.comp` 改 `mediump` | D11（改范围） | 个位数百分比，最乐观片段阶段 10–20% | G1 | Maleoon 跨阶段精度严格 | 弱（Godot 实测 `mediump` 只有 2–3%） |
| 11 | ADPF hint session | G17（补） | 建材耗时，量级待实测 | G0 | 部分 ROM 空实现 | 中（官方 API，但效果逐设备） |
| 12 | 粒子 Surface 按保守包围盒缩小 | 新增（主题 9） | 弹窗场景 GL + 合成面积 → 1/4–1/2 | G0/V | 上界估错会裁掉粒子 | 中 |
| 13 | 按出生时间给片重新编号（历史合并度） | 新增（主题 7） | 合并度 0 → 接近连续 | G1 | 改变原子累加顺序 | 中 |
| 14 | 红黑 GS / SOR 取代 Jacobi | C8 | 同画面下迭代数约 ÷2 | V | 共享内存可能超 ES 3.1 的 16 KiB 保证；GPU 上红黑未必更快 | 弱（文献有反例：Jacobi 墙钟反而更快） |
| — | **两遍绘制合一** | B5 | 只省 1 次 draw | V | 改变混合顺序 | **建议放弃** |
| — | **实例化改顶点拉取 + 索引缓冲** | B4 | 无（VS 调用数不变） | — | — | **否定** |
| — | **PCG 求解压力** | 方案第 4 节 | — | — | 迭代体非线性，CG 前提不成立 | **否定** |
| — | **framebuffer fetch / PLS 合并累积与 resolve** | 主题 3 | 负收益 | — | Adreno 无 PLS；fetch 合一让 resolve 数学乘以 overdraw | **否定** |
| — | **`requestUnbufferedDispatch`** | 主题 10 | 官方称多数情况不会改善 | — | 抖动、失去系统重采样 | **否定** |
| — | **粒子空间排序 / 分桶** | 主题 6 | 文献称不排序反而快 8–17% | — | — | **否定** |

## 12 对方案第 3 节的逐条裁定

| 方案条目 | 裁定 | 说明 |
|---|---|---|
| A1 逐顶点 → 逐片每帧一次 | **支持，且低估了收益** | Adreno 的 bin-pass 会把同一条位置链再跑一遍：是 16 次而不是 8 次 |
| A2 逐步 → 常量 | **支持** | 纯代码层面，无外部依赖 |
| A3 流场采样次数 | **支持，且规模比预估大** | 每步最多 **32 次 3D `texelFetch`**（两次 `guided_flow` × 8 个 `field_cell` × 2 张纹理） |
| B4 实例化 → 顶点拉取 + 索引 | **否定** | 4 顶点带状实例化已是最少 VS 调用；索引与顶点拉取都不减少调用数。收益实为「位置属性流拆分」，并入 A1 |
| B5 两遍合一 | **建议放弃 / 降级** | 同一片在交接期两遍都要画，合一省不掉顶点工作量，只省 1 次 draw；代价是 V 级的混合顺序变化 |
| B6 包围盒清空与 resolve | **需修正** | **clear 保持整屏**（scissor 清空会让 tile 从免 restore 变成需 restore），只对 resolve 加包围盒 |
| C7 迭代数 | **支持，但口径要改** | 100 次 Jacobi 在 288×416 的网格上本就远未收敛；该测的是「第 N 次与第 100 次之差」，不是残差 |
| C8 红黑 GS / SOR / 多重网格 | **部分否定** | 红黑 GS 可行但要重推边带、可能撞 16 KiB 共享内存；文献有「Jacobi 墙钟反而更快」的反例；**多重网格会改画面（V 级），不是等价加速**；**PCG 因迭代体非线性直接排除** |
| C9 网格 `span/96 → span/64` | **支持，但优先级应低于 halo 收缩** | 真正的大头是 halo（占 82%–89% 的格子），不是卡片内的分辨率 |
| C10 halo 与活动 tile | **支持，且已确认问题存在** | 代码事实：**splat 的清零与两遍 blur 都是全网格 dispatch**，只有 Jacobi 用了活动 tile |
| D11 精度 | **支持，但范围要改、期望要降** | ES 3.1 只有 `mediump`（Godot 实测 2–3%）；标量场改了没用，**该改的是 `material.frag` 的颜色链与 `peel-blur.comp` 的 `vec4` 卷积** |
| E12 按 dp 定微片尺寸 | **未检索到反面证据** | 属产品取舍，本文不评 |
| E13 按场景分档 | 同上 | |
| F14 双上下文分优先级 | **支持，但要补三条纪律** | 优先级是 hint 必须读回；抢占粒度可能只到 dispatch 边界，**`yieldToWindow` 不应直接删**；交接必须 `fence → flush → wait` |
| F15 左滑预读档数 | 未检索 | 由实测定 |
| F16 合成侧核对 | **支持，且只能实测** | AOSP 不公开 overlay plane 上限；`dumpsys SurfaceFlinger` 是唯一口径 |
| G17 左滑首帧 | **支持，且应补两条** | 补「首帧绕开 Choreographer」与「ADPF hint session」；`requestUnbufferedDispatch` 否定 |
| G18 弹窗消散压进涟漪期 | **支持** | 用已有的等待窗口，无额外风险 |
| G19 创建页不动 | 无异议 | |

## 13 检索过程中的两条方法学提醒

1. **`docs.qualcomm.com` 与 `developer.arm.com` 在本机的网络环境下 `WebFetch` 取不到**（域名安全校验
   失败），只能通过搜索结果的摘要间接引用。本文里凡是引到这两个域的，**要点是搜索摘要转述的，
   不是原文逐字**；需要逐字原文时请人工打开链接确认。
2. **厂商文档对性能几乎只给定性表述**（「可以有两倍性能」「不要过度使用」）。凡是本文写了具体倍数
   或百分比的，都注明了出处是哪一次第三方实测（Android 的 binning 带宽表、Godot 的 2–3%、
   MPM 的 8–17%）。**这些数字来自别的负载，不能直接搬到本管线**，只能用来排序候选。
