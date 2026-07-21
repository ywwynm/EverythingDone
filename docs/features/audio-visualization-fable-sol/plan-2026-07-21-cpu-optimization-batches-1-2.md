# FableSol CPU 算法优化第一、二批实施计划（2026-07-21）

## 0. 输入与范围

- **必读输入**：本文档 + `research-2026-07-21-cpu-algorithm-deep-dive.md`（下称"调研报告"，
  含每帧成本结构图、各候选的定位行号、循环规模推导与否定方向）。本任务是**实现**，不属于
  "调研任务"，不受调研独立性规则约束，两份文档都应完整阅读。
- **本文档范围**：第一批（C5/C6/C7/C10/C11/C1，全部位级等价）与第二批（RowParallel 扩展
  入口 + C2/C3/C8，输出逐位/逐字节相同的并行重构）。第三批 C4（调度实验）与第四批 C9
  （非位级递推）**明确不在范围内**，不得顺手实施。
- **目标**：真机 debug 构建 work p50 5.3ms → 第一批后约 4.3ms → 第二批后约 3.5ms。
  各项的预期节省与推导依据见调研报告，不在此重复。
- **行号声明**：文中与调研报告中的行号是 2026-07-21 工作区快照；每项落地都会使后续行号
  漂移，**定位一律以函数名/符号为准**，动手前先核对现场代码与调研描述一致，不一致时以
  现场代码为准并在会话文档中记录差异。

## 1. 全局约束与工作纪律

1. **不得降低任何视觉或物理质量**。合同项（216 点物理、120Hz 固定步长、196 列 × 97 行
   显示重建、Hermite C1 / Catmull-Rom / PCHIP / C2 B-spline fairing、六阶软限幅、单调
   修复、4x MSAA、FP16 scRGB HDR、逐像素材质）一律不触碰。
2. **两批所有条目要求输出与现状逐位（浮点）或逐字节（顶点缓冲）相同**。任何既有对照
   测试（FableSolCanvasCurveParityTest、FableSolContinuousSurfaceTest、
   FableSolSheenSlopeFilterTest、FableSolGlOpticsTest 等）失败都说明实现错了，**修实现，
   不修测试**。唯一例外：测试为覆盖新增开关/入口而新增或扩展。
3. **不改 Python 仓库**。位级等价意味着双端同构自动保持；若发现某项无法位级等价，该项
   停止实施并记录，不得引入"近似相同"。
4. **每项独立提交粒度准备，但不自行 git commit**——提交时机由用户掌控（仓库纪律）。
   C1 因体量最大必须与其它项分开成完整独立改动。
5. 每项完成后：`:app:assembleDebug` + 全量 `:app:testDebugUnitTest`（当前基线 218 项
   0 失败，含中文测试名，编码报错见 gradle 规则文档）。按 CLAUDE.md 的强制要求随做随更
   feature 目录的 sessions.md / followups.md。
6. 发布阿里云仅在用户要求时进行，遵循 `.claude/rules/gradle.md`（必须传
   `-PdebugUpdateNotesFile`）。不使用 ADB。
7. 工作区现有未提交改动（帧调度 A/B/C：看门狗、ADPF、HUD rr 字段）是本批的基座，
   **不得回退或"顺手清理"**。

## 2. 真机测量协议（用户执行，实现侧只需保证 HUD 可读）

- 本设备（ColorOS）无触摸约 10s 后会把派发降到 60Hz（触摸即回 120），属系统策略，与本批
  无关。HUD 对比必须**同派发速率**进行：统一取"触摸后 120Hz 窗口内"或统一取"60Hz 稳态"
  读数；60Hz 下 `steps=2` 会使 phys 翻倍，跨速率对比 phys/work 无效。
- 每批前后各留存一张 HUD 截图（相同曲目、相同时长点），关键读数：`work p50/p95`、
  `sample`、`vtx`、`sheen`、`optics`、`comp`（phys 行的 comp 字段）。
- 判断 ADPF/调度问题时看第三行 `work`，不要用首行 `gl p95`（60Hz 派发下它恒为
  16.6~20ms，由栅格决定，与 CPU 无关）。

## 3. 第一批：位级等价（建议顺序 C5 → C6 → C7 → C10 → C11 → C1）

### 3.1 C5 vtx 逐顶点冗余装载削减（FableSolGlRenderer.buildFrame 顶点循环）

改动点（调研报告 C5，Renderer.kt:639-775 一带）：

1. 行级提升：每行循环开始处把 `sample.orbitZ[row]`、`orbitZSlope[row]`、`orbitX[row]`、
   `orbitXSlope[row]`、`worldEta[row]`、`slopeX[row]`、`slopeZ[row]`、`z01[row]`、
   `zDp[row]` 等提为 local 的一维数组引用，内层列循环只对 local 数组做下标访问。
2. 帧级提升：`max(sample.depthDp, 1e-6)`、`info.hG / 2.0` 等帧常量提到循环外算一次。
   注意：只提升"值不随行列变化"的表达式；除法结果参与后续运算的顺序不得改变。
3. `FableSolDepthBaseline.value` 的每顶点 `require(...)` 校验：给 DepthBaseline 增加一个
   跳过校验的内部入口（如 `valueUnchecked`，或先 `validate(anchors)` 一次 + 循环内走无
   校验路径），顶点循环外校验一次。公开入口行为不变。
4. hermiteWeights 的 8 个表数组（h00…dh11）提为循环外 local。

等价性：全部是装载/校验的提升，浮点运算序列不变，位级等价。
测试：既有 parity 测试兜底；无需新增。
验收：`vtx` p50 约 1.2 → 约 1.0 或更低。

### 3.2 C6 派发合并：limit 并回 field、sheen 14 次派发 → 5 次

**limit → field**（FableSolContinuousSurface.kt，sample() 内）：

- 现状两段行并行（field 420-472、limit 479-505）之间有一次汇合；limit 的
  lift/softLimit/单调修复均只依赖本行 field 结果，且代码注释明言两段拆开只为分段计时、
  "逐行数学与并在上一段时逐位一致"（历史上本来就是一段）。
- 做法：把 limit 的行体追加到 field 派发的行体尾部，删除独立 limit 派发。
- **HUD/监控处理（已决策，照做）**：`recordGlStages` 签名与
  FableSolPerformanceMonitor 的字段全部不动，`sampleLimitNs` 上报 0；HUD 会显示
  `limit 0.0/0.0`，在 publishHud 对应行注释注明"已并回 field"。不删字段、不改签名，
  避免连锁改动。
- 等价性：行内数学顺序不变，跨行无依赖，位级等价。

**sheen 派发合并**（FableSolGlMeshLayout.kt / FableSolSheenSlopeFilter.smooth）：

- 现状 7 pass × slopeX/slopeZ 两路 = 14 次派发。3 个水平 pass 只依赖本行 → 融进单次
  派发的行体（行内小 scratch 乒乓，逐元素运算序列保持）；slopeX 与 slopeZ 两路在同一次
  派发内先后处理；4 个纵深 pass 跨行依赖，屏障保留但两路合并。目标 5 次派发。
- 等价性：每个元素经历的运算序列与顺序不变；FableSolSheenSlopeFilterTest 的逐位参考
  对照（曾拦下 `b+3a` 误合并）是硬门禁，必须全绿。
- 验收：`sample` 一段整体下降（field+limit 合并省一次汇合与三数组整轮重读重写）、
  `sheen` 持平或略降；每帧派发总数 19 → 约 10。

### 3.3 C7 depthMeanX 循环互换（FableSolContinuousSurface.prepareComposeMeans）

- 现状：列外层、行内层，对行主序 `[97][216]` 数组做列主序散射读。
- 做法：改为行外层、列内层，向 `depthMeanX[x]` 累加（先清零，最后统一除 Z_ROWS）。
- 等价性：每列的加法到达顺序仍是 r=0→96，逐位一致。测试：既有测试兜底。

### 3.4 C10 逐帧/逐子步分配清理

按调研报告 C10 清单逐项：

1. `packets.removeAll { … }`（ContinuousSurface.kt:342-344）→ 手写下标压缩循环，
   删除逐子步 lambda 分配；元素保留顺序不变。
2. `continuousRenderInfo()` 每帧两次调用（Renderer.kt:594 与 ContinuousSurface.kt:362）
   → buildFrame 算一次，结果传入 sample()；两处消费的数值来源必须仍是同一帧同一次计算。
3. applyInjections 渐入期 bump `DoubleArray` 分配（Simulation.kt:478）与
   DynamicWave.kt:76 的 gradient 分配 → 预分配最大宽度 scratch 复用；写入范围与旧数组
   长度语义逐项核对（scratch 复用必须显式限定有效区间，不得依赖数组默认零值）。
4. `buildInterfaceShoulder` 先分配后早退（GlOptics.kt:318-324）→ 调用点以权重非零为
   前置条件（当前 D135 色板恒为零，整段不再进入）。
5. AudioRecorder.kt:464 mono 缓冲复用（音频线程，顺手项）。
6. Renderer 的 Timing 对象保持现状（监控专用，不动）。

等价性：全部为分配与调用次数变化，数值路径不变。

### 3.5 C11 微项

按调研报告 C11：Ambient/Hero 的 stepSin/stepCos 跨帧缓存（仅 retune 失效）；perFrame
的攻击/释放 `exp` 九层共享同 tau 者合并（27→6，roughness 两条 9→1）；Hero sampleInto
的 minDiff 与 isUniform 扫描合并为一趟；optics 的 uDp 除法与 depthAxis 差值按
microNeeded/glintsEnabled 门禁。每项独立核对"共享缓存的失效时机"（retune、参数变更），
缓存值与逐帧重算逐位一致。

### 3.6 C1 field 循环互换（体量最大，单独成一个完整改动）

改动点（FableSolContinuousSurface.kt sample() 的 field 段）：

1. 现状：模态外层（9 模态 + ≤7 波包）、列内层；eta/orbitX/orbitZ 被约 12 趟
   read-modify-write，每个 (行,模态) 是一条 116 列旋转递推依赖链。
2. 做法：仿 FableSolWaveSets.kt:111-124（Ambient sampleInto）——为 12 组波准备
   (phCos, phSin) 状态数组，列外层推进：每列以寄存器累加 `s=0.0` 起步，按 j=0..8、
   packet 0..k **原顺序**累加，一次 store。三个输出数组从 12 趟写降为 1 趟写。
3. **位级等价论证要点**（实现时逐条自查）：
   - 对固定列 x，加法到达顺序必须仍是 j 升序、再 packet 升序（现状即此顺序）；
   - 每条递推链自身的旋转序列不变（同样的初值、同样的步进旋转、同样的列步数）；
   - packet 项保持 `a * envX[x] * envZ` 的原乘法结合顺序；
   - 行首相位仍逐行直接 sin/cos（跨行递推是 C9，不在本批）。
4. **新增对拍开关**：仿既有 `forceDirectEvaluationForTest` 先例，加内部开关保留旧路径，
   新增测试在生产规模（97×216 与 97×116 裁窗两种）+ 多组随机模态/波包配置下新旧路径
   **逐位对拍**（Double.doubleToRawLongBits 相等）。对拍测试永久保留。
5. C6 已把 limit 并入同一派发，本项重写时保持"行体 = field 新实现 + limit 原实现"结构。

验收：`sample` 的 field 部分 p50 0.7 → 0.35~0.5（HUD 合并后看 `sample` 总量）。

## 4. 第二批：确定性并行重构（顺序：4.1 → 4.2 → 4.3 → 4.4）

### 4.1 前置：FableSolRowParallel 小任务入口

C3/C8 的 total=9 会被 `MIN_PARALLEL_ROWS=16` 直接串行。扩展要求：

- 新增重载 `run(total, minParallelRows, chunkRows, body)`（或等价设计），既有入口语义
  完全不变；chunkRows 经 runLock 内与 totalRows 同批发布（volatile 发布顺序与现有
  generation 屏障一致），worker 与调用线程按新 chunk 窃取。
- 合同保持：正常路径零分配、失败语义不变（调用线程异常原样重抛、后台异常包装）、
  只改变完成顺序不改变逐行结果。
- 新增单测：chunk=1、total=9 的确定性与异常传播；与既有 FableSolRowParallelTest 并存。

### 4.2 C2 optics 按层并行 + 固定顺序拼接（FableSolGlOptics.build）

1. 把 build 的 9 层循环体（readContour / prepareContour / buildBackShade 及 glints
   开启时的层内几何）改为按层并行：每层持有独立 scratch 上下文（现共享的约 15 个 216
   长度数组打包成 LayerScratch，9 份常驻复用，约 233KB，一次分配）与独立顶点段
   （按每层 quad 上限定容）。
2. 并行构建后按层序 `System.arraycopy` 拼进共享顶点数组，layerFirstVertex/Count 由段长
   重建；`scheduleGlitterBirths` 及其它跨层状态保持在层循环之后串行执行。
3. **保留串行回退开关**（内部 @Volatile，默认并行；开关永久保留，出现真机异常时可即时
   回退）。
4. **新增回归**：并行与串行两路在（a）默认参数、（b）glint 开启、（c）若干 tuning 变体
   下构建，顶点缓冲**逐元素 floatToRawIntBits 相等**、层区间元数据相等。
5. 层粒度不均（第 0 层最重）：按"重层优先"顺序投放任务缓解尾部。

等价性：每层内部顶点顺序未变，拼接后缓冲区与串行版字节级相同，非浮点重排。
验收：`optics` p50 0.9 → 约 0.4~0.5。

### 4.3 C3 perFrame（comp 段）按层并行（FableSolSimulation.perFrame）

1. 阻塞点是 6 个 sim 级共享 scratch 被逐层复写（调研点名 heroShiftedX、
   heroBandTargetScratch、heroInterpIndex、heroInterpFraction 等，见 Simulation.kt:109-115
   一带；**以现场代码逐项枚举为准，必须一次列全**）→ 全部迁入 FableSolLayerSim（它已
   持有 ambientSampleDp 等层内 scratch，模式现成）。
2. heroVisibleMask / heroSourceWeight / grandProfile 在层循环前写好，循环内只读，可共享；
   visualTargetDps 仅第 0 层写，无竞争；每层写自身状态与 heights[i]。
3. 用 4.1 的小任务入口并行 9 层；层内数学与串行逐位一致。
4. 测试：FableSolHeroEnvelopeContinuityTest、FableSolExpressionUpgradeTest、
   FeatureMapper 集成测试按构造通过；scratch 迁移后建议临时加一条"并行=串行 heights
   逐位相等"对拍（可与 C8 共用）。

验收：`comp` p50 约 0.6 → 约 0.25；60fps 稳态下收益不随 substep 翻倍（perFrame 每渲染
帧一次）。

### 4.4 C8 物理 substep 层循环并行（FableSolSimulation.update 内 583-630 一带）

1. 9 层的 advance×3 + wave.step + applyInjections 均为层内状态；beatSurge/flow/thInRad
   等在循环前算好为只读。**逐项核对注入路径无共享写**（pending 列表按层隔离）。
2. `surface2d.advance` 必须保持在层循环之后串行。
3. 用 4.1 小任务入口；60fps steps=2 时收益翻倍（正对 phys 1.4/2.7）。
4. 测试：既有物理连续性/注入测试兜底 + 与 C3 共用的"并行=串行逐位"对拍。

验收：120Hz 下 phys p50 0.9 → 约 0.8；60Hz 稳态 phys 1.4 → 约 1.15。

## 5. 交付与验收清单

- [ ] 每项：assembleDebug + 全量 testDebugUnitTest 全绿；新增测试（C1 对拍、C2 字节级
      相等、4.1 小任务、C3/C8 逐位对拍）全部落地并永久保留。
- [ ] HUD 字段语义变化只有一处：`limit` 恒为 0.0（已并回 field），其余字段不动。
- [ ] sessions.md 逐批补实施记录（含实测 HUD 前后对比，由用户提供读数）；followups.md
      的"算法优化四批候选"条目更新第一、二批状态；第三、四批保持待办。
- [ ] 不自行 commit、不发布（除非用户要求）、不使用 ADB、不改 Python。
- [ ] 若某项无法做到位级/字节级等价，停止该项并在 sessions.md 记录原因，不得降级为
      "近似等价"继续。
