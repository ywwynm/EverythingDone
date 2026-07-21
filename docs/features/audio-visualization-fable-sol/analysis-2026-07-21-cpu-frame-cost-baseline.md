# FableSol CPU 帧成本基线与行并行屏障实测（2026-07-21）

## 背景

2026-07-21 的因果七境/连续水面重构（D172）落地后，用户反馈 Android 端动画发卡，
且**新 debug 包比旧 debug 包更卡**——因此不是 D163 的 ART 运行时税，是本轮代码回归。
当前无可用设备，先用纯 JVM 探针在桌面上取相对基线。

## 测量手段

新增 `app/src/test/java/.../FableSolCpuFrameCostProbe.kt`，只测纯 Kotlin 的渲染热路径
阶段（不涉及 GLES，可在 JVM 单测里跑）。默认跳过，显式开启：

```powershell
& "E:\projects\EverythingDone\gradlew.bat" :app:testDebugUnitTest `
    --tests "*FableSolCpuFrameCostProbe*" "-Dfablesol.perf=1"
```

`app/build.gradle` 的 `testOptions.unitTests.all` 只在传了 `-Dfablesol.perf` 时把该属性
透传给测试 JVM 并打开 `showStandardStreams`，因此不影响常规 `:app:testDebugUnitTest`。

**绝对值不代表手机耗时**（桌面 x86 大核远快于手机大核，且没有 debuggable 的 ART 税），
只用于优化前后的相对对照。测量机 32 逻辑核，预热 1200 帧、测量 1200 帧。

## 基线（优化前，2026-07-21）

| 阶段 | mean | p50 | p95 |
|------|------|-----|-----|
| physics `sim.update`（1 子步） | 67.8µs | 65.3µs | 85.9µs |
| build `surface2d.sample` | **416.6µs** | 402.0µs | 525.7µs |
| build `sheenSlopeFilter` | 69.2µs | 67.0µs | 80.8µs |
| 合计（三阶段） | 553.7µs | 536.1µs | 683.8µs |

`sample()` 独占 75%。其内部分解：

| 子阶段 | mean | 备注 |
|--------|------|------|
| 方向场累加（9 模态 + ≤7 波包） | ~223µs（反推） | 97×216×16 ≈ 33.5 万次内层迭代 |
| C2 fairing ×3（**三次独立 run**） | 125µs | 本轮新增 |
| C2 fairing ×3（合并成**一次 run**） | 57µs | 仅合并屏障即省 68µs |
| `composeLayerField` | 36.3µs | |
| slopeZ 梯度 | 31.8µs | |

## 关键发现：`FableSolRowParallel` 的屏障在这个粒度上是净亏损

| 项目 | mean | p95 |
|------|------|-----|
| `RowParallel.run` 空 body（纯屏障） | **22.8µs** | 41.8µs |
| fairing×1 **并行** | 36.2µs | 54.5µs |
| fairing×1 **串行** | **32.5µs** | 36.2µs |

- 空 body 就要 22.8µs：`synchronized(runLock)` + `synchronized(completionMonitor)` +
  `notifyAll()` 唤醒 3 个 worker + 各 worker 完成后再 `notifyAll()` + 调用线程 `wait()`。
- 97 行 × 216 点这类 O(2 万) 的轻量循环，**真实工作量只有约 10µs**，屏障比工作还贵；
  fairing 的并行版本反而比串行慢 3.7µs。
- 每帧共 **5 次** `RowParallel.run`：`composeLayerField` 1 次、`sample()` 3 次
  （方向场 / fairing / slopeZ）、`buildFrame` 顶点填充 1 次
  → **约 114µs/帧纯同步开销**，占三阶段合计的 20%。
- 这还是 32 核桌面机的数字。手机上 futex 唤醒 + 大小核调度延迟更差，worker 还可能
  被调度到小核，实际屏障成本预计显著高于 22.8µs。

## 已落地的改动与效果（2026-07-21）

### 改动 1：`sample()` 内的派发合并（4 次 → 2 次）

`composeLayerField` 拆成串行的 `prepareComposeMeans()`（逐层均值 + 逐列纵深均值，
这是唯一无法回避的跨行汇合点）与逐行的 `composeRow()`；后者与三路 C2 fairing
并入同一次 `RowParallel.run`（都只依赖本行）。逐行结果与拆开时逐位一致。
公开的 `composeLayerField` 保持原签名与原返回值（`FableSolContinuousSurfaceTest`
直接断言锚行等于 `layers[i][x] − mean`，精度 1e-9），fairing 结果只写进 `Sample`。

### 改动 2：`FableSolRowParallel` 等待策略改为「短自旋 + 挂起」

worker 与调用线程都先自旋一小段（80µs / 60µs）再落到 `completionMonitor.wait()`。
帧内相邻两次派发只隔几十微秒的串行预备段，自旋能整段避开 futex 往返；跨帧的
8.3ms 间隔仍然挂起，不空转烧电（三个 worker 每帧最多多烧 3×80µs，单核约 3%）。
`generation` 升为 `@Volatile`（它的写发布 `totalRows`/`currentBody`），汇合计数
改用 `AtomicInteger`。两个 AtomicInteger 都是构造一次的字段，"每轮不新建同步对象"
的约定不变。

### 效果

| 项目 | 改前 | 改后 | 变化 |
|------|------|------|------|
| `RowParallel.run` 空 body | 22.8µs | **1.0µs** | −96% |
| fairing×1 并行 | 36.2µs | **9.0µs** | −75% |
| fairing×1 串行（对照，未改） | 32.5µs | 32.2µs | — |
| `composeLayerField` | 34.7µs | 8.3µs | −76% |
| slopeZ 梯度 | 28.4µs | 1.8µs | −94% |
| **`surface2d.sample`** | **407µs** | **281µs** | **−31%** |
| 三阶段合计 | 554µs | **412µs** | −26% |

屏障降到 1µs 之后结论反转：原先 O(2 万) 的轻量循环串行更快（fairing 串行 32.5µs
< 并行 36.2µs），现在并行快 3.6 倍，因此 slopeZ 保持并行而不是改串行。

## 新测得的第二大热点：`optics.build`

| 阶段 | mean |
|------|------|
| `surface2d.sample` | 281µs |
| **`optics.build`** | **288µs** |
| `sheenSlopeFilter` | 68µs |
| `sim.update` | 62µs |
| sheen 写回 19012 顶点 | 5µs |

`FableSolGlOptics.build` 逐层调 `addContourBand`，每列发 6 个顶点 × 13 分量 = 78 float。
9 层 × 196 列 ×（界面肩 + 波背自阴影）≈ 25 万次 float 写入 ≈ 1MB/帧，与实测吻合。
降低这两条带的列密度是最直接的杠杆，但会在波峰处产生可感知的折面，需要目测取舍。

## 真机读数推翻了桌面推断（2026-07-21，用户实测 `202607210220`）

```
fps 17.7  gl 56.6ms p95 61.8
drain 0.2 phys 4.1 build 50.9 draw 0.2 swap 0.1
sample 44.0 vtx 1.0 sheen 0.3 color 0.0 optics 5.0
steps 6.0 hop 5.0 ev 0.0 drop 0.0 therm 0.0
```

- `sample` 独占 44.0ms（整帧 78%），而桌面同一函数只要 0.258ms——**相差 170 倍**，
  远超"手机比桌面慢"（3~10×）与 ART 通用税（2×）能解释的范围；
- `draw 0.2 / swap 0.1`：**GPU 完全空闲**，瓶颈 100% 在 CPU；
- `phys 4.1 / steps 6.0`：每子步 0.68ms，物理本身正常，6 个子步只是低帧率的连带结果。

### 真凶：`Math.cbrt`，每帧 41904 次

本轮迁移把轨道位移的硬裁剪换成六阶软饱和：

```kotlin
orbitXRow[x] = softLimit(orbitXRow[x], 10.0)   // value / sqrt(cbrt(1 + ratio⁶))
orbitZRow[x] = softLimit(orbitZRow[x], 10.0)
```

97 行 × 216 点 × 两路 = **41904 次 `Math.cbrt`/帧**。44ms ÷ 41904 ≈ **1.05µs/次**。

Android 的 `Math.cbrt` 是 libcore 的**纯 Java FDLIBM** 实现，不像 `sin`/`cos`/`exp`
有快速路径；在 debuggable 构建（弱 JIT、无内联）下 ~1µs 完全合理。原代码注释
"x^(1/6) = sqrt(cbrt(x))；避免通用 pow 路径，适合逐顶点热循环"的假设在 Android 上
恰好相反。

### 方法论教训：桌面 JVM 探针对 ART 特有开销结构性失明

桌面 HotSpot 把 `Math.cbrt` 当内联函数，代价接近零，因此本文件前半部分基于桌面探针
的全部排序都**没能看见这个占 78% 的热点**。结论：

- 桌面探针只适合验证**算法与并行结构**的相对改进，不能用来给热点排序；
- 任何 `Math.*` 超越函数在 Android 上的代价必须**在真机上单独测**，不能从桌面外推；
- 因此本轮把分段计时做进 `sample()`（`prep / field / fair / slope`）与物理段
  （`bc / wave / surf`），并接到屏幕 HUD——后续优先级一律以真机读数为准。

## 尚未量化（需要真机或 GL 上下文）

- `buildFrame()` 的 19012 顶点 × 8 float 填充与 608KB VBO 上传
- `drawFrame()` 的四 pass + 4×MSAA + FP16 GPU 成本
- `drainAndApply` 逐 hop 消费 `mapper.applyFrame` 的成本

后三项已在本轮加了 GL 线程分阶段计时（`Timing.sampleNs/vertexNs/sheenNs/colorNs/opticsNs`
与本帧 hop/event 计数），并接到 debug 构建的屏幕内 HUD，可直接在真机上读数。
