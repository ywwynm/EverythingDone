# FableSol CPU 端算法性能深挖调研（2026-07-21）

> 由独立调研 agent 完成（按项目规则未读既有 research/analysis 文档，结论只来自代码事实）。
> 基线：真机 HUD @120fps，work p50 5.3 / p95 9.5ms；目标 p50 ≤4ms、p99 ≤8.3ms，
> 不得降低任何视觉或物理质量。

## 一、每帧成本结构图（HUD 字段 → 代码 → 循环规模）

竖屏 280dp 容器、θ≈0 时的窗口实测推导：`continuousRenderInfo()`（FableSolSimulation.kt:828-844）给出 requiredHalf = min(351.7, 146.67×1.176+10) ≈ 182.5dp → 原始列区间 [i0=52, i1=164)，raw 窗口（含 fairing 余量）**116 列**；倾斜时 requiredHalf 触顶 351.7dp，窗口最大回到 ~216 列（×1.86），这是 field/limit p95 的确定性放大项之一（另一项是 DVFS 乘性尖峰）。

```
work 5.3/9.5ms（GL 线程，onFrameTick → renderer.render + swapBuffers）
├─ drain 0.0     drainAndApply（Renderer.kt:465-518）：mapper 逐 hop 标量更新，无向量工作 ✔
├─ phys 0.9/1.3  sim.update（Simulation.kt:530-645）
│  ├─ bc ~0      rebuildBc：30Hz + 每帧 5 层预算摊销，倾斜时才触发（332-400）
│  ├─ wave ~0.15/substep  9 层 × wave.step（DynamicWave.kt:23-69，216 点 × ~12 趟）
│  │              + ambient/hero/optical.advance（各 4/6/10 模态相位标量）
│  ├─ surf ~0.05  surface2d.advance（相位递推 9 模态 + ≤7 波包）
│  └─ comp ~0.6  perFrame（Simulation.kt:647-733，每渲染帧一次、不随 substep 翻倍）
│                 9 层 × [ambient.sampleInto 216×4 递推 + hero.sampleInto 216×6 递推
│                 + 6 趟 216 后处理（梯度/限幅/单调重采样/去均值）+ advectHero 3 频段×2×216
│                 + heights 行写 216]，合计 ≈ 4.5 万次内层迭代 + ~450 sin/cos + ~100 exp
├─ build 3.8/7.5  buildFrame（Renderer.kt:593-870）
│  ├─ sample 1.2/2.7  surface2d.sample（ContinuousSurface.kt:355-570）
│  │  ├─ prep 0.1   模态/波包标量 + packetEnvX：pkt×116 次 exp（377-414）
│  │  ├─ field 0.7~1.7  行并行（420-472）：97 行 × (9 模态+pkt) × 116 列
│  │  │      ≈ 17 万内层迭代（每次 3 组 read-modify-write + 6 flop 旋转递推）
│  │  │      + 97×(9+pkt)×2 ≈ 2328 次行首 sin/cos + 97×pkt 次 envZ exp
│  │  ├─ limit 0.3/1.2  行并行（479-505）：97×116×2 = 22504 次 softLimit
│  │  │      （Resampler.kt:85-101，~90% 走二项快路）+ 97×115 单调最小步扫描
│  │  ├─ fair 0.1~0.2  串行 prepareComposeMeans（185-201：9×216 + 97×116 列主序求和）
│  │  │      + 行并行 composeRow(116×11flop) + 3×fairCubicBsplineRange(114×8flop)
│  │  └─ slope ≈0  行并行 97×114 三项式（540-566）
│  ├─ vtx 1.2/1.6（604-775）：串行 9×216 layerMeans + 196 列 Hermite 权重表
│  │      + 行并行 97×196 = 19012 顶点 × [5 组 Hermite 点积(35flop) + PCHIP 基线
│  │      hermiteValue + 2 次除法 + ~17 次冗余行数组解引用] + 每行 195 列回折扫描
│  ├─ sheen 0.2（778-797）：FableSolSheenSlopeFilter.smooth（GlMeshLayout.kt:65-127）
│  │      19012 元素 × 7 pass × slopeX/Z 两路 ≈ 106 万 mul-add；14 次独立并行派发
│  │      + 串行 19012×2 写回
│  ├─ color 0.0：静态材质缓存命中，仅 9×196 梯度几何扫描 ✔
│  └─ optics 0.9/1.2  FableSolGlOptics.build（GlOptics.kt:127-259）全串行：
│         9 层 × [readContour 196×~10 + prepareContour 2×gradient+2×smooth3]
│         + 默认几何只有波背暗带（7 层，BACK_SHADE_*_WEIGHTS 第 7/8 层为 0）：
│         每层 196 smoothstep + 9-tap Hann + 196 sqrt + 196 四停靠取色
│         + ≤195 quad × 6 顶点 × 13 float ≈ 全帧 ~2.7 万顶点 / ~35 万 float 写
│         （interface shoulder 权重被 D135 色板恒置零，早退不出几何，
│          LayerColorPolicy.kt:134-140；闪点/体光默认关，死算链已短路）
├─ draw 0.3：VBO bulk 上传（1480-1486，FloatBuffer.put 整段 memcpy）+ ~10 次 draw call
└─ swap 0.2
```

每帧并行派发总数 **19 次**（sample 4 + vtx 1 + sheen 14），FableSolRowParallel 实测空 body 一轮同步 22.8µs（自旋命中时更低），派发本身构成 0.1~0.3ms 的固定税。并行覆盖率：field/limit/fair/slope/vtx/sheen 已并行（3 worker + GL 线程 = 4/8）；**串行大块 = optics 0.9 + comp 0.6 + sample 串行预备/depthMeanX + vtx 串行前缀**，合计 ≈ 1.7ms，是 Amdahl 瓶颈所在。

音频链确认：`FableSolRealtimeAnalyzer.feed` 全部在 AudioRecorder 的 RecordingThread 上执行（AudioRecorder.kt:423-479），渲染线程只做 mapper 标量消费，drain=0.0 与 60fps hop=2 时的结构一致，无隐藏成本。唯一小疵：AudioRecorder.kt:464 每次 read 分配 `DoubleArray(sampleCount)`（音频线程 GC，非渲染路径）。

## 二、优化候选清单（按预期节省毫秒数排序）

### C1. field 循环互换：模态外层 → 列外层、状态数组交错推进（预期 −0.25~0.40ms，位级等价）

- **位置**：FableSolContinuousSurface.kt:429-470。
- **现状问题**：field 是全项目唯一"模态外层、列内层"的求值循环（Ambient/Hero 的 sampleInto 早已是列外层交错推进）。后果有二：① 每个 (行,模态) 是一条长度 116 的**旋转递推串行依赖链**（4 mul+2 add 延迟无法被流水线隐藏）；② eta/orbitX/orbitZ 三个数组被 (9+pkt)≈12 趟 read-modify-write，内存往返 ≈ 40 万次/帧，而互换后只剩 1 趟写。
- **改法**：仿照 FableSolWaveSets.kt:111-124 的模式——把 12 组 (phCos, phSin) 放进模态状态数组，列外层推进；每列寄存器累加 s=0.0 起步后按 j=0..8、packet 0..k 顺序累加再 store。
- **位级等价论证**：对固定列 x，当前实现的加法到达顺序就是 j 升序、再 packet 升序（各 pass 依次 +=，起点 Arrays.fill 的 0.0）；互换后同列的求和顺序、每条递推链的旋转序列逐位不变。12 条链变为独立 → ILP 隐藏延迟。
- **推导**：17 万次迭代在 0.7ms/4 线程 ≈ 16ns/迭代（延迟受限 + 逐次界检查）；互换后内存趟数 ÷12、链延迟被吞吐替代，参照 Ambient 同构循环的实测密度，估 35~55% 降幅。
- **测试覆盖**：位级等价 → FableSolContinuousSurfaceTest、FableSolCanvasCurveParityTest 全部按构造通过；建议加一个临时开关做新旧路径逐位对拍（仿 forceDirectEvaluationForTest）。
- **风险**：低。唯一注意点是 packet 的 envX[x]·envZ 乘法顺序保持 `a*envX[x]*envZ` 原式。

### C2. optics 按层并行 + 固定顺序拼接（预期 −0.4~0.5ms，输出字节级相同）

- **位置**：FableSolGlOptics.kt:186-255 的 9 层串行循环（followups.md 既有候选，其"1~2ms"估计按当前 p50 0.9 需下修）。
- **现状问题**：readContour/prepareContour/buildBackShade 逐层独立，却因共享 cursor 和 ~15 个 216 长度 scratch（x/y/gradient/field/smooth/bandTop/backShadeColors…）被迫串行；全帧 ~35 万 float 顶点写全部压在 GL 线程。
- **改法**：每 worker（或每层）持有一份 scratch 上下文 + 独立顶点段（9 × 每层上限），并行构建后按层序 System.arraycopy 拼接（~1.4MB/帧 memcpy ≈ 0.05-0.1ms），layerFirstVertex/Count 由段长重建。glints 开启时轨迹本就按层隔离（`glints[layer]`），scheduleGlitterBirths 保持在层循环后串行。
- **等价性**：拼接后缓冲区与串行版**字节级相同**（每层内部顺序未变），非浮点重排。
- **测试覆盖**：FableSolGlOpticsTest（层区间/容量/暗带渐变/默认几何断言）+ 建议加一条"并行=串行缓冲逐字节相等"回归。
- **风险**：中。scratch 迁移面积大，建议保留串行回退开关；层任务粒度不均（第 0 层最重），9 任务对 4~8 消费者的尾部可用"重层优先"顺序缓解。

### C3. perFrame（comp 段）按层并行（预期 −0.3~0.35ms，位级等价）

- **位置**：FableSolSimulation.kt:668-733 的 9 层循环。
- **现状问题**：每层 ambient(216×4)+hero(216×6 递推+6 趟后处理)+advect(3×2×216)+heights 写，全串行 0.6ms；且 60fps 时它不随 substep 翻倍，是纯并行红利。
- **阻塞点**：6 个 sim 级共享 scratch 被逐层复写——heroShiftedX、heroBandTargetScratch、heroInterpIndex/Fraction（Simulation.kt:109-115）须移入 FableSolLayerSim（它已持有 ambientSampleDp 等层内 scratch）；heroVisibleMask/heroSourceWeight/grandProfile 为循环前写好的只读量，可共享。visualTargetDps 仅第 0 层写，无竞争。
- **等价性**：各层只写自身状态与 heights[i]，数学与串行逐位一致；需要给 FableSolRowParallel 提供小任务入口（现 MIN_PARALLEL_ROWS=16 会把 total=9 直接串行，RowParallel.kt:24,48）。
- **测试覆盖**：FableSolHeroEnvelopeContinuityTest、FableSolExpressionUpgradeTest、FeatureMapper 集成测试按构造通过。
- **风险**：低-中（scratch 归属迁移需逐项核对，共 6 项，上面已枚举完）。

### C4. 提高并行度：worker 3→6~7、块 8→4（预期 −0.3~0.5ms，调度实验）

- **位置**：FableSolRowParallel.kt:25,34-36（CHUNK_ROWS=8，workerCount=min(核-2,3)）。
- **推导**：97 行 = 13 个 8 行块；4 消费者最长者拿 4 块（32 行）→ 理想加速 3.03×；8 消费者最长 2 块（16 行）→ 6.06×。当前并行段 p50 合计 ≈2.3ms，C1~C3 落地后 ≈1.6ms，4→6.5 有效消费者可再省 ~0.3-0.5。块降为 4（25 块）可压小核尾部（大小核 8 核面板，慢核抢到整块 8 行会拖长收尾）。
- **功耗代价**：worker 自旋预算 80µs/次等待，帧间隔期最多 1 次后挂起——新增 3 worker ≈ 3×80µs×120fps ≈ 29ms/s/核 ≈ 每核 <3% 空转，可接受；真正的风险是把小核拉进来后 DVFS 策略变化，**必须 HUD A/B**（正是桌面探针测不出的部分）。
- **等价性**：调度只改完成顺序，行输出逐位不变（RowParallel 合同 + FableSolRowParallelTest 覆盖）。

### C5. vtx 逐顶点冗余装载削减（预期 −0.15~0.25ms，位级等价）

- **位置**：FableSolGlRenderer.kt:639-775。逐项：
  - 每顶点 ~17 次 `sample.xxx[row]` 外层数组解引用（649-652、656-663、694-696、705-707 的 orbitZ/orbitZSlope/orbitX/orbitXSlope/worldEta/slopeX/slopeZ/z01/zDp）→ 提为行级 local（每行 9 个引用），19012×17 次 getfield+aaload+界检查归为 97×9；
  - `max(sample.depthDp, 1e-6)`（677）与 `info.hG / 2.0`（702）是帧常量，被算 19012 次（后者是逐顶点除法）→ 提到帧级；除数数值不变，除法本身保留，位级等价；
  - `FableSolDepthBaseline.value` 每顶点执行 `require(anchors.size>=2…)`（DepthBaseline.kt:38）——19012 次/帧的冗余校验 → 提供 loop 外校验一次的入口；
  - hermiteWeights 的 8 个表数组（h00…dh11）提为循环外 local，去掉每顶点 20 次 getfield。
- **推导**：削掉的都是 debuggable ART 下最贵的"每元素常数项"（界检查 + 字段装载不被 LICM），按每顶点省 30-60ns（4 线程摊薄）估 0.15-0.25ms。
- **测试**：位级等价；FableSolCanvasCurveParityTest/GlRenderTargetSourceTest 兜底。风险：极低。

### C6. limit 并回 field 派发 + sheen 派发合并：19 次/帧 → ~10 次（预期 −0.10~0.2ms，位级等价）

- **limit→field**：ContinuousSurface.kt:476-505 的注释明言两段拆开只为分段计时（"逐行数学与并在上一段时逐位一致"），每行 lift/softLimit/单调修复都是行局部 → 并回 field 的行体尾部，省 1 次汇合 + eta/orbitX/orbitZ 三数组 97×116 的整轮重读重写（~20 万次内存操作）。HUD 的 field/limit 两个读数合并为一项（本轮诊断已完成使命）。
- **sheen**：GlMeshLayout.kt:72-103 的 3 个水平 pass 只依赖本行 → 三 pass 融进单次派发的行体（行内小 scratch 乒乓，逐元素运算序列不变）；X/Z 两路共派发；纵深 4 pass 需跨行屏障保留，但两路合并。14 次派发 → 5 次。
- **fair/slope 不可合并**：depthMeanX 是跨行汇合点（185-201 注释），slope 读相邻行 fair 结果（534 注释），维持现状正确。
- **测试**：FableSolSheenSlopeFilterTest + 位级等价按构造通过。风险：低。

### C7. depthMeanX 循环互换（预期 −0.02~0.05ms，位级等价）

- **位置**：FableSolContinuousSurface.kt:196-200。现为列外层、行内层——对 `[97][216]` 的行主序二维数组做列主序遍历，116×97 = 1.1 万次全散射读（每步换一条 cache line + 一次外层数组解引用）。
- **改法**：行外层、列内层，向 depthMeanX[x] 累加（先清零、最后统一除 Z_ROWS）。每列的加法到达顺序仍是 r=0→96，逐位一致。

### C8. 物理 substep 层循环并行（预期 120Hz −0.1，60fps −0.25ms，位级等价）

- **位置**：FableSolSimulation.kt:583-630。9 层的 advance×3 + wave.step + applyInjections 均为层内状态（pending 列表按层隔离），beatSurge/flow/thInRad 在循环前算好为只读。60fps steps=2 时收益翻倍（正对"phys 翻倍到 1.4/2.7"）。同 C3 需小任务并行入口。风险：中（注入路径需逐项核对无共享写；surface2d.advance 必须留在层循环之后）。

### C9. 行首相位跨行递推（预期 −0.08~0.15ms，**非位级等价**，需门禁）

- **位置**：ContinuousSurface.kt:435-437、456-459——field 每 (行,模态) 的 `cos/sin(rowPhase)`，rowPhase 沿行方向等差（zDp[r] 等距）→ 可在串行 prep 里用 97 步旋转递推生成 97×12 的行首状态表，libm 从 ~2328 次/帧降到 ~48 次。97 步递推漂移 ~1e-13（同 FableSolWavePhaseRecurrenceTest:102-120 的已证 216 步 <1e-12），远低于 float32 下游精度。
- **拦截测试**：无现有测试逐位锁 field 输出；需新增仿 WavePhaseRecurrenceTest 的"跨行递推 vs 直接求值 <1e-11"对照 + 一次 HDR 截图 A/B 证明视觉不可分辨。packetEnvX/envZ 的 exp 也可同法（增量高斯），收益更小（~300 次 exp）。

### C10. 逐帧分配清理（预期 −0.02ms + 削 GC 抖动，位级等价）

- ContinuousSurface.kt:342-344：`packets.removeAll { …捕获 depth… }` 每物理子步分配一个 lambda（120 次/s）→ 手写下标压缩循环；
- Renderer.kt:594 与 ContinuousSurface.kt:362：`continuousRenderInfo()` 每帧两次分配 + 两次重复窗口扫描 → buildFrame 算一次传入 sample()；
- Simulation.kt:478：applyInjections 渐入期每子步每条 pending 分配 bump DoubleArray（注入高峰期数百次/s）→ 预分配最大宽度 scratch；DynamicWave.kt:76 的 gradient 同理；
- GlOptics.kt:318-324：buildInterfaceShoulder 先分配 4 元素数组再早退，且权重被 D135 恒置零 → 调用点直接以权重非零为前置条件（每帧省 8 次调用+分配）；
- Renderer.kt:401 Timing 每帧一只（25 字段）→ 可复用可不动（监控专用）；
- 附带（音频线程，非渲染路径）：AudioRecorder.kt:464 mono 缓冲可复用。

### C11. 微项（各 −0.01~0.03ms，位级等价）

- Ambient/Hero 的 stepSin/stepCos 只依赖 k×dx，k 仅 retune 时变 → 跨帧缓存，每帧 libm 再省 ~200 次（WaveSets.kt:103-109、275-282）；
- perFrame 里 3 频段攻击/释放的 `exp(-dt/(hero_attack_s×ATK_MULT[j]))` 九层共享同一 tau → 27 次 exp/帧 → 6 次（Simulation.kt:689-693）；roughness 0.26/1.2 两条同理 9→1；
- Hero sampleInto 的 minDiff 全扫描（WaveSets.kt:328-330）与 isUniform 扫描可合并为一趟；
- optics 的 uDp 除法（GlOptics.kt:275）与 depthAxis 差值（273-274）只被 micro/glint 消费，默认关闭时是死算 → 按 microNeeded/glintsEnabled 门禁。

**合计**：C1~C11 p50 预期 −1.8~−2.3ms → work p50 5.3 → **3.2~3.5ms**；按已证实的乘性尖峰模型，p95 9.5 → ~6，p99 有较大概率进入 8.3ms 预算内。

## 三、明确否定的方向

1. **把 Hermite 重建移入 vertex shader**：CPU 侧存在四个顶点数组消费者——optics.readContour（GlOptics.kt:261-277）、layerMeanYPx（Renderer.kt:805-814）、crestRim 跨度（799-802）、逐层梯度几何（976-1014），搬 GPU 需回读或重复计算，且破坏与 Python 的 CPU 数组同构。
2. **ambient/hero/heights 裁到可见窗**：全 216 列均值是各层 DC 去除项与水位定义（ContinuousSurface.kt:189-193 注释、Renderer.kt:608-612），裁窗直接改变水位，属视觉合同破坏。
3. **softLimit 只对越界列执行**：六阶软饱和在全域都改变数值（快路多项式因子 ≠1，仅 ratio⁶ 下溢才恒等），"未越界跳过"会改输出；快/慢路分流与纯算术 cbrt 已把单次成本压到位，残余是本征成本。
4. **sheen 纵深 4 pass 合成单个 9-tap 二项核**：改变浮点求和顺序，输出喂 HDR Fresnel 银泽；派发合并（C6）已拿走大部分收益，不值得引入数值漂移。
5. **CPU build 与 GPU draw/swap 跨帧流水线**：GPU 侧仅 draw 0.3 + swap 0.2，重叠上限 ~0.5ms，代价是全套顶点/光学缓冲双份（~5MB）、模拟状态跨帧线程化与一帧延迟——收益/风险比不成立。
6. **sin/cos 查表**：递推已把逐点 libm 降为行首常数次；LUT 插值误差（~1e-7）反而高于递推漂移，且破坏双端同构。
7. **顶点数组增量重写 / FloatBuffer 逐元素 put**：波每帧全域运动，无"未变化行列"；上传已是 bulk put（Renderer.kt:1480-1486），此路无肉。
8. **降列数/行数/物理率/MSAA**：合同项，不触碰。

## 四、建议落地顺序

1. **第一批（位级等价、零视觉风险，可一次发版）**：C5 顶点装载削减 → C6 派发合并（limit 并回 field、sheen 14→5）→ C7 depthMeanX 互换 → C10/C11 分配与微项 → **C1 field 循环互换**（体量最大，单独提交并带新旧路径逐位对拍开关）。预期 work p50 5.3 → ~4.3。
2. **第二批（确定性并行重构，需真机 HUD A/B）**：C2 optics 按层并行（带串行回退开关 + 字节级相等回归）→ C3 comp 按层并行（scratch 迁移）→ C8 物理层循环并行。预期 → ~3.5。
3. **第三批（调度实验）**：C4 worker 3→5/6/7 与块 8→4 的矩阵 A/B，观测 HUD p50/p95 与热余量 th，确认小核参与不劣化 DVFS；该项与前两批乘性叠加。
4. **第四批（需数值门禁的非位级项）**：C9 跨行相位递推，先落新对照测试（<1e-11）+ HDR 截图对比，再单变量上线。

每批之间用现有 HUD（work/sample/vtx/optics/comp 分段）回读验证预期节省是否兑现——所有估算的最终裁决权在真机 debuggable ART 读数（桌面探针对 ART 热点排序不可信，此前已有结论）。
