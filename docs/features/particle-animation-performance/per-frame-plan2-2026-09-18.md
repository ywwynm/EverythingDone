# 逐帧成本专项 第二阶段方案：三家 GPU 与交互延迟（2026-09-18）

承接 [per-frame-plan-2026-09-17.md](per-frame-plan-2026-09-17.md)（第一阶段，目标设备只有魅族）与
四批实施结果。**第二阶段的目标扩到所有设备**：

- **帧率**：消散、出现、左滑三条路径在魅族（Adreno 740）、OPPO（Adreno 829）、三星（Adreno 740）、
  华为（Maleoon 910）上都稳定 60 fps；默认档位下华为不再掉到 0.19。
- **交互延迟**：左滑（识别 → 首个非零画面）、新建按钮（点下 → 首帧呈现）、弹窗关闭（结算 → 首帧）
  三条链路都要比现在短。
- **约束**：**Adreno 路径逐位不变**是默认要求；允许按厂商分支的着色器变体（`Shared.adreno()` +
  `program(defines=)` 已经是现成机制）。凡改画面的项一律先出并排放大图交用户定夺。

资料依据全部在 [research-2026-09-18-vendors.md](research-2026-09-18-vendors.md)，本文只写「做什么、
怎么验」。条目编号沿用该文：`U*` 普适、`M*` Mali 族、`A*` Adreno、`L*` 延迟。

---

## 0 口径与纪律（每一批都适用）

### 0.1 测量口径

- **帧率**：一次动画真正呈现的帧数 / 应有帧数（60 Hz × 跨度），加呈现间隔 p90 与最大值。
  **不用「帧耗时百分位」**——先读 `DEADLINE`，`TOTAL < DEADLINE` 不是丢帧（`.claude/rules/adb.md`）。
- **每帧成本**：离屏分段基准 `ParticleSegmentBench`（`device_per_frame.py --segment-bench`），
  成对 A/B、先热身、**整段末尾只 `glFinish` 一次**。逐帧 `glFinish` 会让同一条件两遍差 2–3 倍。
- **份额**：消融（`ParticleAblation`，线程局部掩码）。**消融只对不回灌模拟的段成立**——
  跳掉压力里的任何一级都会改变活动 tile 列表，`no-jacobi` / `no-project` 反而更慢，
  这类条件已从条件表里删掉（`per-frame-baseline-2026-09-17.md` 第 3.1 节）。
- **画面**：魅族能 `screenrecord`，华为要先确认（ColorOS 不行，鸿蒙/EMUI 未测）。
  每个 V 级改动出「同一进度点 10%/50%/90% 的整帧 + 4 倍放大裁剪」的并排图。
- **逐位**：`ParticleTrajectoryCheck`（`gpuCheck`）。**判据两步走**：先同路径自比较，
  自比较干净才把 A/B 的差异当回归（本项目 GPU 输出有 0.5–0.9% 的单帧本底噪声）。

### 0.2 设备与数据安全

| 设备 | 序列号 | GPU | 角色 |
|---|---|---|---|
| 华为 MatePad Pro 13.2 | `5JB0224328002836` | Maleoon 910，Android 12，横屏 2880×1920 | **本阶段主力**（现状最差） |
| 魅族 20 Infinity | `392QBFED2226B` | Adreno 740，Android 15，1368×3192 | 逐位回归 + 画面证据 |
| OPPO OPD2515 | — | Adreno 829 | 在线时回归一轮（不能 `screenrecord`） |
| 三星 SM-S9180 | `R5CW20BLNKL` | Adreno 740 | 与魅族同 GPU，可替代；延迟那几格它最差 |

- **左滑会改数据**：一律先建 `PerfB<数字>-` 前缀的测试记事，测完经真实多选菜单移入回收站。
  不要拿用户的真实记事去滑（`never-test-destructively-on-real-data`）。
- **不代为授予权限、不改设备设置、不按电源键**。跑之前只读查询 `RECORD_AUDIO`，
  查不到就跳过那一格并在报告里写明。
- **每台在跑之前另起一条 `adb -s <序列号> logcat -v time > 本地文件`**（魅族主缓冲 256 KiB）。
- **真机要省着跑**：不逐项 A/B 反复跑真机，本地有把握之后一轮合并验证（`device-test-runs-sparingly`）。

### 0.3 厂商分支的写法

- 判据用 `Shared.adreno()` 的**取反**（= Mali 族，覆盖 Maleoon / 天玑 / Exynos 的 Mali），
  不要按机型表，也不要按 `GL_RENDERER` 里出现 "Maleoon"。
- 着色器变体走 `program(..., defines = ...)`；`defines` 已经进 `shared.compiled` 的缓存键，
  两套变体可以共存。
- **每一项都要有 `ParticlePerfTuning` 的开关**，默认值 = 发布配置，debug 包由
  `files/particle-perf-tuning.json` 覆盖。没有开关的结构性改动（如本阶段的格式变更）
  必须能用同一个二进制切回旧路径，否则做不了 A/B。

---

## 1 第一步：华为上的分段归因（先归因后实施）

**这一步不改任何生产行为。** 它要回答的是「华为那 40.7 ms/帧花在哪」——现有的所有份额数字
（`per-frame-baseline-2026-09-17.md` 第 3 节）都只在 Adreno 740 上量过，而本轮调研指出
Mali 与 Adreno 在 shared、varying、混合格式、采样器精度四件事上的代价结构**根本不同**，
直接照 Adreno 的份额排序去实施很可能排错。

### 1.1 先定归因工具（半小时，决定后面怎么测）

| # | 要确认的 | 怎么做 | 决定什么 |
|---|---|---|---|
| T1 | `GL_EXT_disjoint_timer_query` 在 Maleoon 上计不计 compute | 探针模式 `b`（`gpuTiming=b`）：一对查询包住出现预计算的约 1550 次 dispatch，另一对包住两遍绘制。前者若是 0.3–0.6 ms 这种明显偏小的值，说明和 Adreno 一样不计 compute | 归因是否只能用墙钟 + 消融 |
| T2 | Arm Streamline / 华为 Graphics Profiler 能否采这台设备的普通 APK | 各试一次，**十分钟为限** | 有则多一路证据，无则按既定口径走，**不要为此花时间** |
| T3 | 设备的动画时长缩放 | 在应用里实测（反射 `ValueAnimator.getDurationScale` + 120 ms 动画的墙钟校准），**不要相信 `settings get global`** | 所有时间口径的前提 |

### 1.2 分段基准：把 Adreno 的那张表在华为上重做一遍

跑 `device_per_frame.py --segment-bench`，条件表与 `per-frame-baseline` 第 3.2/3.3 节一致：

- 路径：**现场消散**（弹窗素材 10.6 万片 + 左滑卡片素材 2.4 万片）、**回放出现**、**预读**；
- 消融条件只保留有效的那些：`PASS0` / `PASS1` / `RESOLVE` / `CLEAR` / `HISTORY` / `PRESSURE`（整段）；
- 压力内部的相对量级用探针模式 `c`（分段 `glFinish`），**只当相对量级看，不当份额**。

**必须新增的三条消融/对照**（本轮调研直接指向的三处，Adreno 上量不出来）：

| # | 条件 | 怎么开 | 回答什么 |
|---|---|---|---|
| B1 | **累积目标 RGBA16F vs RGBA8** | `Shared` 建 FBO 时按开关选内部格式（`ParticlePerfTuning.accumulationRgba8`，默认 false）；画面会变，**只测墙钟** | Arm 的「不要在浮点渲染目标上混合」对 Maleoon 成立吗（1.1） |
| B2 | **批处理 Jacobi vs 逐次 Jacobi** | 现成：`PeelPressure.update(time, batched)`。要补的只有「把活动区域接到逐次路径」（把 `driven` 的条件从 `batched && !large` 放宽到 `!large`） | Mali 上 shared 分块是不是负收益（1.4） |
| B3 | **整网格清零 vs 只清活动区域** | `peel-splat.comp` 的 `pass==0` 分支加 `ACTIVE_REGION` 早退 | 12 万格 × 16 B 的纯写在 Mali 上是否显著（1.5） |

**跑归因之前先修一处**（静态分析发现，followups 已记）：`advance()` 的积分循环每步只发
`GL_SHADER_STORAGE_BARRIER_BIT`（L519），而 `useDrawRecords` 关闭时——也就是
`withPerFrameBaseline` 走的那条 **G0 基线路径**——`material.vert` 把同一批状态缓冲当**实例化顶点
属性**读，按规范需要 `GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT`；开启绘制记录时 `writeDrawRecords`
发了这一位（L631），基线路径上它直接早退，这一位就没人发。**所有 G0/G1 对照都以基线路径为参考**，
Maleoon 上又没验证过这条，先补上再归因。补法：基线路径的最后一步积分之后补发该位（一行）。

再加两条**只测不改**的读数：

- **varying 字节量**：把 `material.vert` 的 12 个可降精度分量临时改 `mediump`（`uv` 不动），
  只测墙钟。这一条同时回答第七节第 4 条——**如果画面逐位相同，说明驱动把 `mediump` 当 highp 了**，
  后面所有精度类改动的期望都要归零。
- **3D 取样的份额**：把 `sample_field` 临时替换成「只取 `field_cell(a)`，不做三线性」
  （画面必然错，只测墙钟），得出那 32 次取样在积分段里的份额。

### 1.3 这一步的产出

`per-frame-huawei-attrib-2026-09-18.md`，内容对齐 `per-frame-baseline-2026-09-17.md` 的形状：

1. 华为上的分段表（弹窗 / 左滑，现场 / 回放 / 预读三条路径）；
2. B1 / B2 / B3 的对照数字与结论；
3. `mediump` 是否被驱动接受的判定；
4. 3D 取样的份额；
5. **按华为的实测份额给出的实施顺序**——第 2 节的排序是按资料推断的，**以这一步的数字为准**。

---

## 2 第二步：实施

排序原则：**普适 G0 → 普适 G1 → 分厂商 G0/G1 → V 级（要用户看图）→ 延迟项**。
每一项都写了「Adreno 逐位」列：**是** = Adreno 输出一个比特都不变（要么不走这条路径，要么运算完全相同）。

### 批 A：普适、零视觉风险（先做，因为它们不需要任何裁定）

| # | 条目 | 做法 | 华为预期 | Adreno 预期 | G | Adreno 逐位 |
|---|---|---|---|---|---|---|
| **U1** | 屏障位收窄 | `PeelPressure.barrier()`（现在恒发 `SHADER_STORAGE \| SHADER_IMAGE_ACCESS \| TEXTURE_FETCH`）拆成两个：`storageBarrier()` 只发 `GL_SHADER_STORAGE_BARRIER_BIT`，用在 splat / region / blur / tiles / Jacobi 之后（每帧约 20 次）；**只有 `resolveField()` 之后保留三位全开**（它 `imageStore` 到 `peel_field`，`step.comp` 随后要采样它）。`advance()` 里的两处（积分后只发 SSBO、绘制记录后发 `SSBO \| VERTEX_ATTRIB_ARRAY`）**已经是对的，不要动**；`prepareTiles` 末尾的 `COMMAND_BARRIER_BIT` 同样不动 | 每帧少 20 次可能触发 flush 的屏障 | 同向，量级小 | **G0** | **是** |
| **U6** | `pressure-batched.comp` 的 shared 从 16 KiB 降到 8 KiB | 照 `pressure-batched-large.comp` 的写法，把 `divergence[1024]` 与 `occupied[1024]` 从 `shared` 改成每线程 `float x[4]` 的寄存器数组（`CELLS = 1024/256 = 4`，循环次数是编译期常量）。两份压力仍留 shared | 缓存污染减半 | occupancy 可能上升 | **G0**（同样的数、同样的顺序） | **是** |
| **M1** | 工作组大小的 Mali 变体 | `program(defines=)` 加 `#define LOCAL_SIZE`：非 Adreno 时 `step.comp` / `peel-splat.comp` / `draw-record.comp` / `history.comp` 用 64，`peel-blur.comp` / `peel-project.comp` 用 8×8；Adreno 保持 256 / 16×16。dispatch 的除数跟着 define 走。**`pressure-tiles.comp` 与 `pressure-batched.comp` 不动**（16×16 与 tile 粒度、活动区域的 16 格对齐绑死） | Arm 把 64 写成上限而非建议，量级待测 | 不走这条变体 | **G0** | **是** |
| **L1** | 首帧的 `eglPresentationTimeANDROID` 写「尽快」 | 核对 `ParticleWindowSurface` / 节拍在首帧这一次写进去的值；若是按稳态节拍算出的未来时刻，改成当前时刻或最近的一个 vsync | 最多 1 个 vsync | 同 | **G0** | **是** |

**批 A 的验收**：两台各一轮九场景矩阵 + `ParticleTrajectoryCheck` 自比较与开关对照；
Adreno 侧必须 **0 帧差异**（U1/U6 是同一条路径，M1 是不同变体但 Adreno 不走）。

### 批 B：普适 G1，压的是带宽与 ALU

| # | 条目 | 做法 | 预期 | G | Adreno 逐位 |
|---|---|---|---|---|---|
| **U2** | 两张 3D 场纹理合成一张 | 离线把 `common-flow.f16`（RG16F）与 `flow-confidence.u8`（R8）合成一张 **RGBA16F**（`.xy` = guide、`.z` = confidence），`field_cell()` 从 2 次 `texelFetch` 变 1 次。资产从 786 KB + 192 KB 变约 1.57 MB | 3D 取样次数减半（每帧约 1370 万 → 685 万） | G1（confidence 由 u8 归一化变 f16） | 否 |
| **U2b** | 3D 采样器改 `mediump` | `step.comp` 顶部 `precision mediump sampler3D`，或对这两个 uniform 单独声明。`texelFetch` 用整数坐标，坐标精度无关 | Arm：highp 采样器可能是一半速度 | G1 | 否 |
| **U3** | `peel_field` RGBA32F → RG16F | `Shared` 的 `glTexStorage2D` 换 `GL_RG16F`，`peel-project.comp` 的 `imageStore` 布局改 `rg16f`、写 `vec2`；`sample_peel` 的 4 点 `texelFetch` 换成一次 `texture()`（该纹理已经是 `GL_LINEAR`） | FP32 的 2× 代价消失 + 取样 4 → 1 | G1 | 否 |
| **U5** | varying 改 `mediump` | `material.vert` / `material.frag` **两侧同时改**（Maleoon 对跨阶段精度不一致直接拒绝链接）。`uv` 保持 `highp`（全屏 3192 px，fp16 误差 1.6 px），其余 12 个分量改 `mediump` | Mali 上 varying 主存往返减少约 40% | G1 | 否 |
| **U4** | 片元里的逐片量下沉到 `draw-record.comp` | `material.frag` 里只依赖 `age`/`life`/`random` 的量全是逐片常量：`fade`、`smoothstep(.001,.022,age)`、`mix(1,.92,smoothstep(.018,.12,age))`、replica 的 `smoothstep(.008,.055,age)`、`optical_loosen`、`facing` 与 `glint`、`lighting`、`radial` 里的 `vec2(.85+r.z*.35,.87+r.w*.34)`。在 `draw-record.comp` 里算成 **4 个 flat mediump 量**（`alphaScale`、`glint`、`lighting`、`radialScale`（vec2））写进记录。随后 `life_out` 与 `random_out`（4 个分量）可以删掉。**`age_out` 不能全删**：`facet = .80+.20*(.5+.5*sin(age*11.+dot(uv,vec2(7.,11.))))` 里有 `uv`，是逐像素的 —— 保留一个 flat mediump 的 `age*11.` 替身即可（值域 ≤ 3.3） | 与 U5 合计：每顶点 **56 B → 28 B**（分量 14 → 12，但 `uv` 之外全部 mediump；`life_out`、`random_out`×4、`replica_out` 删除，新增 5 个 flat mediump）；片元少 4 个 `smoothstep` + 1 个 `sin` + 1 个 `pow(x,12)` | G1 | 否 |
| **U10a** | 合并成一个实例化顶点缓冲 | 把 `material_src.xy`（replica 的 `uv` 用）与 `random.zw` 并进绘制记录的空位（`record_mask.w` 用 `packHalf2x16`），取消 location 4/5 这一路属性。replica 的 `uv` 干脆在 `draw-record.comp` 里算好 | Arm 5.9 的 Don't：一次绘制不要超过一个实例化缓冲 | G1 | 否 |
| **A1** | `curl()` 与 `peel-blur` 的 `vec4` 改 `mediump` | `step.comp` 的 `curl()` 整段（输入已归一、输出是方向向量）；`peel-blur.comp` 的 `vec4 sum` 与权重 | Adreno 的 FP16 ALU 是 FP32 的两倍宽；Mali 同向 | G1 | 否 |

**批 B 的纪律**：

1. **每一项单独 A/B**，否则归因不了。七项一起上等于什么都没测。
2. **每一项先在华为上编译一次**（Maleoon 的链接检查最严；Adreno 的宽容会掩盖问题）。
3. **每一项落盘一条数值判据**：U2/U2b/U3 出「同快照同种子下每帧位置的最大偏差」，应当在
   1e-3 px 量级；U4/U5/U10a 出画面的逐帧最大通道差。超出量级就是写错了，不是「G1 的正常噪声」。
4. **`mediump` 类改动（U2b / U5 / A1）如果画面逐位相同，说明驱动忽略了 `mediump`**——
   此时不要因为「没有回归」就留下，要记进报告：这台设备上这类改动无效。

### 批 C：分厂商，压的是压力求解

| # | 条目 | 做法 | G | Adreno 逐位 |
|---|---|---|---|---|
| **M2** | 把活动区域接到逐次 Jacobi 路径 | `PeelPressure.update` 的 `driven` 条件从 `useActiveRegion && batched && !large` 放宽到 `useActiveRegion && !large`。`peel-project.comp` 已有 `ACTIVE_REGION` 早退，不用改着色器 | **G0**（区域外恒为零，不变式与 `peel-region.comp` 的注释一致） | 是（Adreno 仍走批处理） |
| **M3** | Mali 上改用逐次 Jacobi | 按第一步 B2 的对照结果决定。开关 `ParticlePerfTuning.jacobiBatchedOnMali`，默认按实测定 | G0（两条路径的迭代式相同；**要用 `ParticleTrajectoryCheck` 证明**，不能只推理） | 是 |
| **M4** | 整网格清零改成只清活动区域 | 按第一步 B3 的结果决定。`peel-splat.comp` 的 `pass==0` 分支加 `ACTIVE_REGION` 早退；**注意 `peel-region.comp` 的不变式里写明「累积缓冲不在此列：它每帧整网格清零」**，改这一条要同时改那条注释并补一条单测 | G0（要证明：区域外上一帧写过的值必须被清） | 是 |

### 批 D：V 级，必须先给用户看图

| # | 条目 | 做法 | 为什么值得 | G |
|---|---|---|---|---|
| **U7** | 累积目标 RGBA16F → **SRGB8_ALPHA8** | `Shared` 的 `glTexImage2D(GL_RGBA16F)` 换 `GL_SRGB8_ALPHA8`；`material.frag` 的 `linear()` 可以整段删掉（前景纹理同时改成 `GL_SRGB8_ALPHA8`，采样器直接给线性值），`body` 那条判据的两个阈值按 sRGB→线性换算（0.80 → 0.6038、0.98 → 0.9559）；`resolve.frag` 的 `srgb()` 保留 | Mali：浮点混合 → unorm 混合 + 带宽减半；Adreno：bin 数减半；两家都少一次每片元的 `pow(vec3, 2.4)` | **V** |
| **U8** | splat 双线性 → 最近格 | `peel-splat.comp` 的 2×2 循环改成单格，每片原子 12 → 3 | splat 之后是两遍 σ≈1.2 格的高斯，双线性多给的约 0.4 格平滑基本冗余 | **V** |
| **U9** | 按实测帧率自动降档 | 用 `ParticleFrameStats` 已有的「上一轮帧数 / 应有帧数」：连续两轮 < 0.8 就把微片尺寸与迭代数各降一档并持久化，同时在调参页显示「已自动降档」并允许用户改回。**默认值不变**，只在实测掉帧时动 | 华为默认 0.19 / 调一档 1.01，这个差距不该由用户自己去发现 | **V** + 需裁定 |
| **U10** | `sample_field` 改硬件三线性 | `texture(guide_field, uvw)` 取代显式 8 点 | 取样 16 → 1 | **V**，且**破坏跨设备一致性**——`sample_field` 的注释写明显式插值的初衷就是让各 GPU 用同一套浮点权重。做之前要先定 `ParticleTrajectoryCheck` 的参考路径怎么改 |

**批 D 的交付形式**：每一项一组「同快照、同种子、只切开关」的并排图（4 倍最近邻放大，
裁剪中心取本帧差异最大的像素，`compose_before_after.py --pairs`），加整帧一张，**不许只报数字**。
U7 另出一张「累积纹理里 alpha 的直方图」，说明 8 位量化档位够不够。

### 批 E：交互到首帧

| # | 条目 | 做法 | 预期 | G |
|---|---|---|---|---|
| **L2** | 左滑建材提前到 `ACTION_DOWN` | 现在建材等 `ItemTouchHelper` 判出左滑。改成在 `ACTION_DOWN`（或 `onInterceptTouchEvent` 判出水平倾向时）就把建材派出去，判定没来就丢弃。先在调参页的记事卡片预览上验证（它走的是与首页逐条对应的同一条路径，单测已钉住五个调用点） | 可能整块吃掉识别→首帧里的建材那一段（魅族 11.9–16.2 ms、三星 63–72 ms） | **G0** |
| **L3** | ADPF `PerformanceHintManager` | 常驻 GL 线程 + 材料工作线程放进一个 session，目标 16.6 ms，每帧 `reportActualWorkDuration`。**突发负载（建材）单独处理**：`ACTION_DOWN` 就创建 session 并报一个大于目标的值，让调速器在识别发生前升频 | 建材耗时，量级待实测；部分 ROM 空实现（设了没效果，不会更差） | **G0** |
| **L4** | 抓图按内容包围盒 | 离屏抓图现在是整窗口尺寸。弹窗的内容区常只占窗口一部分，按内容包围盒抓图按面积比例缩短 | 与面积成比例 | **G0**（包围盒必须保守） |
| **L5** | 核对前景纹理的上传路径 | 抓图结果上传成 `foregroundTexture` 这一步是否用 PBO 异步上传。Arm 把「目标纹理还被在途绘制引用时的 `glTexSubImage`」列为要避免的同步操作 | 若是同步上传，改 PBO 可去掉一次阻塞 | **G0** |
| **L6** | 核对 `primeParticleSurface` 的覆盖面 | 「Surface 一就绪先交换一张全透明帧」在左滑与创建页两条路径上是否都真的在动画开始前预热过 | 首次动画 82–88 ms 的那一段 | **G0** |

**创建页那条链路**（点新建 → 首帧 171–305 ms）的大头在「窗口起来与首次布局」，
已在 followups 里、**本阶段不动**；本阶段只做 L4/L5（抓图与上传）这两格。

---

## 3 第三步：验收矩阵

每批一轮，**每台一轮，不做逐项 A/B**（`device-test-runs-sparingly`）。

### 3.1 帧率矩阵（华为为主、魅族回归）

| 场景 | 华为 | 魅族 | 指标 |
|---|---|---|---|
| 附件弹窗 出现 / 消散（返回键） | ✓ | ✓ | 呈现/应有、间隔 p90/max、请求→首帧 |
| 附件弹窗 消散（点外部，无涟漪） | ✓ | ✓ | 同上 |
| 颜色面板 出现 / 消散（点确定，有涟漪） | ✓ | ✓ | 同上（片数最多的一格） |
| 附件 → 录音行（A 消散接 B 出现） | ✓ | ✓ | 两条会话各自的帧数；B 的首帧 |
| 录音弹窗 消散（FableSol 在播） | 视权限 | 视权限 | 同上，另看 FableSol 自身帧率 |
| 左滑 慢拖 / 快拖 / 来回拖 × 四种复杂度的卡 | ✓ | ✓ | 移动期不同画面数、识别→首个非零画面、滞后曲线 |
| 新建记事（命中 / 冷启动未命中） | ✓ | ✓ | 点按钮→首帧、出现帧数 |
| 涟漪与粒子同步播放 | ✓ | ✓ | 宿主窗口 FrameMetrics |

**华为的两条基准线**（第四批实测，本阶段要对着它比）：

| 场景 | 默认 0.9 dp / 100 | 2.4 dp / 40 |
|---|---|---|
| 附件弹窗消散 | 16 / 82.3（**0.19**），p90 182.9 ms | 62 / 61.4（1.01），p90 16.7 ms |
| 左滑矮卡 | 26 / 88.7（0.29），p90 135.7 ms | 56 / 89.2（0.63），p90 66.4 ms |
| 弹窗出现 | 33 / 30.9（1.07），p90 24.9 ms | 34 / 32.4（1.05），p90 24.9 ms |

**本阶段的目标是让「默认 0.9 dp / 100」这一列追上现在「2.4 dp / 40」那一列。**

### 3.2 逐位回归（Adreno）

- `ParticleTrajectoryCheck` 两份（出现 + 消散）全 `passed`；
- 与本阶段直接相关的格子：消散自比较、消散 C10 开/关、出现自比较、出现 C10 开/关 —— **0 帧差异**；
- 批 A / M1 / M2 / M3 / M4 / L* 属「Adreno 逐位 = 是」，任何一帧差异都要当缺陷查，
  **但先跑自比较**（本底噪声 0.5–0.9%）。

### 3.3 华为的轨迹对照

第四批欠着一格：`ParticleTrajectoryCheck` 在华为上跑了 42 分钟没收尾（2880×1920 = 5.5 MP，
CPU 上逐像素比几十条 61 帧序列）。**本阶段要么缩小对照规模**（只比中心 512×512 的窗口，
或只比 10%/50%/90% 三帧），**要么单独留一轮**。不要再让它把整轮矩阵拖垮。

### 3.4 单测

`:app:testDebugUnitTest` **本次真实执行**（不读旧报告，`stale-test-results-are-not-a-run`）。
本阶段要新增的契约：

- U1：`glMemoryBarrier` 的位在每个调用点上与它的依赖一致（切出调用体判据式断言，
  不要按注释或 API 名搜——`source-contract-tests-vs-comments`）；
- M1：两套 `local_size` 变体与对应的 dispatch 除数一致（改了一处忘了另一处 = 少算或越界）；
- M4：如果动了「累积缓冲每帧整网格清零」这条不变式，`peel-region.comp` 的注释与单测要同步；
- U9：自动降档的触发规则是纯逻辑，JVM 单测覆盖（连续两轮 < 0.8、只降一档、用户改回之后不再自动降）。

---

## 4 需要用户裁定的

1. **U7 累积目标换 SRGB8_ALPHA8**：这是本阶段收益最大的一项，但是 V 级。要看并排放大图，
   尤其看密集区有没有条带。
2. **U8 splat 最近格**、**U10 硬件三线性**：两项都是 V 级；U10 另外会让各设备的画面不再逐位可比。
3. **U9 自动降档**：默认值保持 100 是用户上一轮的裁定，自动降档等于在特定设备上改掉默认值。
   要么做自动降档，要么接受华为默认档位掉帧由用户自己去调参页解决。
4. **天玑设备没有实测条件**：Mali 分支的改动在 Maleoon 上验证通过之后，在天玑上属于同族外推。
   发布日志里要写明这一点，且每一项都留开关。

---

## 5 风险与退出

| 风险 | 表现 | 退出 |
|---|---|---|
| `mediump` 被驱动忽略 | 改完画面逐位相同、墙钟不变 | 记进报告，**不要因为「没回归」就留着**，该项在该设备上无效 |
| Maleoon 拒绝链接 | 跨阶段精度不一致、采样器精度声明不一致 | 每一项先在华为上编译一次；两侧同时改 |
| 逐次 Jacobi 在 Mali 上反而更慢 | 屏障从 13 次涨到 100 次 | M3 按 B2 的实测定，开关默认按实测值 |
| 华为轨迹对照跑不完 | 超过驱动脚本的等待窗口 | 3.3 的两条办法二选一，**跑之前就定好**，不要跑到一半中止 |
| 改了画面却没有画面证据 | 只报了数字 | V 级一律并排放大图 + 整帧；`zoom-before-claiming-fixed` |
| 批次一起上导致归因不了 | 一轮改七项 | 批 B 强制逐项 A/B |

**任何一项若在华为上没有可测提升就回退该项**，不留「理论上更快」的改动——
这条沿用第一阶段的规则。
