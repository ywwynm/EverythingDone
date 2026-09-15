# 第五批：单像素差异的定性与两项补跑的回归（2026-09-15）

两件事：①查清第四批在 OPPO 上出现的「整帧只有 1 个像素不同」（用户不接受「并发下的偶发」
这个判定）；②补上第四批跳过的两项功能回归（弹窗四条关闭路径、独立 Surface 交接像素）。

**一句话结论**：差异**不是**共享资源在两个会话之间泄漏，也**不是**对照工具的回读不稳，
而是**同一条路径重复跑就会与自己不一样**——同一份材料、同一份命令流，
OPPO（Adreno 829）上约每 110–190 次整段运行出现一次单帧差异（0.5–0.9%）。共享状态审查另外找到一处
**真实的隔离缺陷**（`withBaselineGpu` 改的是进程级开关，会被常驻 GL 线程上的提前预算读到），
已改成线程局部；它不是像素差异的成因，但必须修。

已发布 `202609150517`（SHA-256 `baff6c808f3c21a30d099f7675033a5c81a764ace59a994f9d94a2015bdc7db2`，
本地产物、远端 latest.json 与两台设备实际安装包一致）。

## 1. 结论与证据（任务 1）

按任务书只允许三种定性之一。**取 (c)：驱动层面的非确定性**，证据如下。

### 1.1 决定性事实：同一条路径与它自己不一样

第四批的对照只比「参考路径 vs 优化路径」，两条路径用的是**不同的着色器**（逐次 Jacobi 的
`peel-project.comp` 对分块 Jacobi 的 `pressure-batched.comp`），所以一个 ULP 的差异永远可以
用「驱动对两份代码的浮点代码生成不同」来解释，谁也说服不了谁。

本批把对照改成**每条路径自己跑多遍**（`gpuCheckDiagnostics`），于是出现了这样的行。
全部用同一个固定种子 `20260915`、同一条测试记事、同一个弹窗；每轮两个动画层各跑一份对照，
一份对照里四条路径（正向基线 / 正向优化 / 完整倒放 / 截断倒放）各自重复，
「自比较试次」= `(遍数 − 1) × 4 条路径 × 2 份对照`。

| 轮次 | 并发负载 | 每条路径遍数 | 自比较试次 | 事件 |
| --- | --- | --- | --- | --- |
| b5diagA | 关 | 3 | 16 | 无 |
| b5diagB | **开** | 4 | 24 | `forward-reference-self` 第 **10** 帧 ×1 |
| b5diagC | 开 | 5 | 32 | 无 |
| b5diagD | 开 | 12 | 88 | 无 |
| b5diagE | 开 | 24 | 184 | `reverse-full-self` 第 **11** 帧 ×2（第 2 遍与第 22 遍各一次） |
| b5diagF | **关** | 24 | 184 | `forward-reference-self` ×1（定位阶段 OOM，帧号没落盘，由堆栈可知路径） |
| b5diagG | **关** | 24 | 184 | `truncated-self` 第 **11** 帧 ×1；同一遍在 `truncated-ab` 与既有的「交错」对照上**同样**报第 11 帧 |
| b5diagH | 关 | 2（只为定点复现） | 8 | 无；两份对照对着第 11 帧各跑 60 遍 ×2 条路径，**240 遍全部一致**（见 1.4 的说明） |
| b5merge（OPPO，创建页，合并验证轮） | 关 | 12 ×2 轮 | 88 | `reverse-full-self` 第 **10** 帧 ×2；**定点复现第 2 次就命中**，拿到差异像素与状态缓冲对照（见 1.2） |
| b5merge（三星，创建页，合并验证轮） | 关 | 12 ×2 轮 | 88 | 无 |

合计（不含只做定点复现的 b5diagH）：**OPD2515 上负载开 328 试次 3 次事件（0.91%）、
负载关 472 试次 3 次事件（0.64%）**。两者同一量级，3 与 3 这样的计数分不出差别——
**并发负载不是自变量**。**SM-S9180 88 试次 0 次事件**，率更低或者没有（样本还不够下定论，
写进 followups）。
第四批「关掉提前预算后两份清零」是 2 次运行的结论，按这个事件率本来就有 98% 的概率清零。

`forward-reference-self` 比的是**同一个 `forward(baseline=true)`** 的第 1 遍与第 n 遍：
同一个渲染器类、同一批着色器、同一份 uniform、同一份材料数组、同一个上下文、同一条线程、
同样的 61 次 `draw` + 61 次 `glReadPixels`。**两次之间没有任何代码上的自变量。**
`reverse-full-self` 同理（同一条出现路径跑两遍）。

因此可以直接排除：

- **不是共享资源在两个会话之间泄漏**——自比较里根本没有第二个会话；
- **不是种子撞上了某个临界值**——固定种子 `20260915`，同一份材料在同一轮里既出现过分歧、
  也出现过二十几次完全一致；
- **不是 A/B 两套着色器的代码生成差异**——自比较用的是同一个程序对象。

### 1.2 定点复现：绘制的输入逐位相同，画出来的像素不同

b5merge（OPPO 的合并验证轮，创建页场景）在 `reverse-full-self` 上连着抓到两次（第 7 遍与
第 11 遍，都是**第 10 帧**），定点复现**第 2 次尝试就命中**，于是拿到了完整的一帧对照：

```
comparison = reverse-full-self   frame = 10
hashEqual  = false                     ← 两遍画出来的像素不一样
stateEqual = true                      ← 但这一帧的八分量状态缓冲**逐位相同**
  stateHashA = stateHashB = e8143db0af21032d14b5cff40a2927dec6ceb846d26a94c852cca73955983e18
changedPixels = 2   maxChannelDifference = 13
  (726, 573)  A = [113, 229, 153, 242]   B = [118, 239, 160, 253]
  (727, 573)  A = [112, 227, 152, 240]   B = [118, 239, 160, 253]
```

**这条证据把范围缩到了绘制这一步。** 一帧画出来只取决于：材料缓冲（静态、只上传一次）、
色素缓冲（静态）、状态缓冲（这里已证逐位相同）、前景纹理（同一张）、以及那一批 uniform
（同一个会话、同一次 `activate()`）。**绘制的全部输入都逐位相同，出来的像素却不同**——
差异发生在顶点/光栅/混合/解析这一段，**不在我们的 compute 着色器里**。

两个差异像素在**同一行相邻**（x = 726、727，y = 573），且 B 那一遍两个像素**取到同一个值**、
A 那一遍两个像素各自略低。这正是「某一片微片的四边形边缘多覆盖/少覆盖了这两个像素」的样子：
B 多收了一个（内部平坦的）片元，A 只收到部分或没收到。幅度（通道差 11–13）与第四批记录的
「1 个像素、最大通道差 49」是同一类。

### 1.3 不是对照工具的回读不稳

诊断轮里**每一帧都连读两次**（`glReadPixels` 两遍、画面不重画）。若差异来自回读本身，
两次读会不一样。实测 `unstableReads` 在全部轮次里恒为空（b5diagE 一轮就是
2 × 24 × 2 条路径 ≈ 数千帧）。**差异在被读回来之前就已经存在于画面里。**

### 1.4 差异的规律

- **临界帧是「这一份材料」的属性，不是一个通用帧号。** 同一份对照里，多条互相独立的比较会
  指向**同一帧**：b5diagE 的 `reverse-full-self` 第 2 遍与第 22 遍都是第 11 帧；b5diagG 的
  `truncated-self`、`truncated-ab` 与既有的「交错」对照三条一起报第 11 帧。
  但**换一份材料就不是这一帧**：b5diagH 换了一轮（微片数 108685 对 108684、起点采样 32 对 33，
  固定种子只固定随机流、快照像素每轮仍有细微差别），对着第 11 帧一共跑了 **240 遍，一次都没分歧**。
  这正符合「某个量卡在某个判据的边界上」——边界在哪一帧由材料决定，落到哪一边由这一次的
  执行细节决定。着色器里这样的判据有三处：`peel-project` / `pressure-batched` 的
  `velocity[i].z > .025` 与 `max(…, 0.)`，以及 `material.vert` 的四边形边正好压在某个像素中心上。
  **实用推论：定点复现必须在检出它的那一份对照里做**，换一轮再去钉同一个帧号是白费。
- **幅度**：整帧只有个位数像素不同——本批定点复现到的是 2 个相邻像素、最大通道差 13；
  第四批记录的是 1 个像素、最大通道差 49/255、平均 1.1e-05。都是「某一片微片的四边形
  在一两个像素上覆盖翻转」的量级。
- **频率**：整段路径的自比较约 0.5–0.9% 会出事；材料确实有临界帧时，那一帧上约 4–9% 的运行
  会翻到另一边（b5diagE 的 `reverse-full-self` 23 次里 2 次、b5diagG 的 `truncated-self`
  23 次里 1 次）。换算成逐帧就是 10⁻⁴ 量级。
- **并发负载不是必要条件**：b5diagF 与 b5diagG 都是负载关，照样出现自分歧。
  b5diagG 那一次更进一步——**既有的批次四对照（交错那一条）也报了同一帧**，
  即第四批看到的那个症状在「固定种子 + 无并发负载」下被原样复现出来了。

### 1.5 为什么与我们的代码无关

**第一层，由实测直接给出**（1.2）：出事的那一帧，GPU 算出来的**状态缓冲逐位相同**，
而绘制的其余输入（材料、色素、前景纹理、uniform）全是静态或同一份。**我们喂给绘制的东西
一个比特都没变，画出来的却不一样**——这一句本身就已经把成因排除在应用代码之外了。

**第二层，为完整起见核过 compute 这一侧**：管线里**唯一**的跨调用依赖只有三处，
都不是非确定性的来源：

1. `peel-splat.comp` 的 `atomicAdd` 是**整数**累加（`int(round(peel.x*w*4096.))`），
   加法可交换可结合，工作组完成顺序不影响结果；
2. `pressure-tiles.comp` 用 `atomicAdd(workgroups.x, 1u)` 收集 tile，**收集顺序确实是非确定的**，
   但每个 tile 写的是**互不相交**的 16×16 输出块（`q = tile*16 + local`），顺序不影响任何一个值；
3. Jacobi 的乒乓缓冲与 `peel-blur` 的两趟之间都隔着 `glMemoryBarrier`，且读写不同缓冲。

与 1.2 的实测一致：compute 侧确实是确定性的，分歧在其后的顶点/光栅/混合/解析那一段，
即驱动与硬件那一层。

### 1.6 这对产品意味着什么

- **产品路径上看不到**：一次动画只播一遍，不存在「同一帧两个版本」的对照；差异幅度是
  单个像素上的一次覆盖翻转，在 60 fps 的运动画面里不可见。
- **对 G0 验收的影响**：G0（「GPU 输出逐位不变」）在这台设备上有一个**本底噪声**，
  量级约「每 10⁴ 帧一个像素」。以后 G0 报出**孤立单帧、个位数像素**的差异时，判据不是
  「通过/不通过」，而是要先跑一遍**自比较**：自比较同样出现分歧就是本底噪声，
  自比较干净而 A/B 有差异才是真回归。`gpuCheckDiagnostics` 就是为此留下的。

## 2. 复现方法

```powershell
$env:THING_ANIMATION_SERIAL = "9018f404"; $env:PYTHONUTF8 = "1"
cd E:\projects\EverythingDone\docs\features\particle-animation-performance
python device_particle_perf.py --scenario attach-open --run <新 run 名> --rounds 1 `
    --title PerfB5-20260915-1 --gpu-check --gpu-seed 20260915 --gpu-diagnostics --gpu-repeats 24
```

- `--gpu-seed` 同时打开 `diagnosticOnly`，让整轮用同一个 `hashSeed`；
- `--gpu-diagnostics` 打开自比较、逐帧连读两次、以及定点复现；
- `--gpu-load` 另起一条线程、**在自己的 EGL 上下文里**跑与提前预算同形态的预计算
  （同一批着色器、同样的分批节流），把「并发」变成可以独立开关的自变量，
  不必依赖首页此刻恰好在不在预算。产物里的 `loadSamples` 是负载线程实际推进的采样数，
  为 0 就说明这一轮根本没有制造出负载；
- `--gpu-pin-frame <帧号>` 对着一个已知的临界帧反复跑出现路径。**只在同一份对照里有意义**
  （临界帧是那一份材料的属性），换一轮再钉同一个帧号不会有结果（b5diagH 240 遍全一致）。

产物：`tmp/particle-perf/phase0/9018f404/runs/<run>-0/gpu-parity-00N.json`，
`diagnostics` 一节里：

| 字段 | 含义 |
| --- | --- |
| `forwardReferenceSelfMismatches` / `forwardOptimizedSelfMismatches` | 消散路径自比较（基线 / 优化各自跑多遍） |
| `reverseFullSelfMismatches` / `truncatedSelfMismatches` | 出现路径与截断出现路径的自比较 |
| `forwardAbMismatches` / `truncatedAbMismatches` | 原有的 A/B 比较，只是重复多遍 |
| `unstableReads` | 同一帧连读两次就不一样的帧（回读不稳的判据） |
| `pinpointed` | 自分歧的定点复现：同一条路径再跑至多 60 遍（`PINPOINT_ATTEMPTS`），复现则给出差异像素坐标、两边的 RGBA，以及 `stateEqual`（分歧是否已经存在于八分量状态缓冲里） |
| `located` | A/B 差异帧的像素坐标；`stateEqual` 说明分歧是否已经存在于八分量状态缓冲里 |

## 3. 共享状态审查表（任务 1 第 1 步）

两个层次要分开看：

- **同一个 EGL 上下文里的两个会话**（生产形态：常驻 `ParticleGl` 上下文里的提前预算会话 +
  正常会话）：共用 `Shared`（程序对象、累积纹理与帧缓冲、全屏 VAO、两张流场纹理、历史缓冲池），
  也就共用全部 uniform、纹理单元与 SSBO 绑定点。
- **离屏 G0 对照**：`ParticleTrajectoryCheck.withEgl` 用 `eglCreateContext(..., EGL_NO_CONTEXT, ...)`
  自建上下文，并**自建一份 `Shared`**，与常驻上下文**不共享任何 GL 对象**。
  所以「对照与提前预算之间的 GL 资源泄漏」在结构上不可能发生；两者之间只剩进程级的 Kotlin 状态。

| 共享资源 | 谁写 | 谁读 | 切换会话时是否重设 | 结论 |
| --- | --- | --- | --- | --- |
| 构造期 uniform：`material` 的 frame / card / offset / cell / wind / span / roll_gain / light_gain / body_weight / release_spread / panel_weight / nx / foreground / grid_count / diagnostic | `prepare()` → `applyUniforms()` | 每次 draw | 是（`activate()`） | 无泄漏 |
| 构造期 uniform：`resolve` 的 screen | 同上 | 每次 resolve | 是 | 无泄漏 |
| 构造期 uniform：积分程序的 count / guide_field / confidence_field / **touch_gap** / **touch_strength** / field_geometry / dt / span / wind_gain / curl_gain / roll_gain / guide_gain / wind / card / guide_rotation / variation_affine / variation_bend / variation_wave / variation_clock | `configureIntegration()`，正向与逆向两个程序各一次 | 每次 dispatch | 是 | 无泄漏 |
| 构造期 uniform：`peel_field` / `peel_bounds` | `PeelPressure.configureIntegration()` | 积分 dispatch | 是 | 无泄漏 |
| 构造期 uniform：splat 的 grid_shape / count / grid_count / foreground / card / wind / span / touch_strength / grid_bounds；blur 的 grid_shape / occupancy_scale；project 的 grid_shape | `PeelPressure.applyUniforms()` | 压力更新 | 是 | 无泄漏 |
| 构造期 uniform：history 程序的 count | `ParticleReverseHistory.applyUniforms()`，且 `transfer()` 每次再写一遍 | 历史转存 | 是（两处） | 无泄漏 |
| 逐帧 uniform：`time`、`extrapolate`、`material_pass`、`history_frame`、splat 的 time/pass、blur 的 pass、求解器的 iteration / first_iteration / iterations / grid_shape、tiles 的 pass、history 的 frame / restore / fraction | 每次使用前当场设 | 同左 | 每次都设 | 无泄漏 |
| 纹理单元 0（前景，会话私有对象） | `render()` 每个 pass 前绑定并改 MIN/MAG；`PeelPressure.update()` 开头绑定 | material.frag、peel-splat | 每次使用前重绑 | 无泄漏。**逐 pass 改过滤参数改的是本会话自己的纹理对象**，别的会话的纹理不受影响；离开 `render()` 时恒为 `GL_LINEAR`，与建纹理时一致 |
| 纹理单元 2（累积纹理，`Shared` 一份） | `render()` resolve 前绑 | resolve.frag | 是 | 无泄漏 |
| 纹理单元 3 / 4（流场、置信度，`Shared` 只读） | `advance()` 每次绑 | step.comp | 是 | 无泄漏 |
| 纹理单元 5 + 图像单元 0（压力场，会话私有） | `advance()` 绑一次，`resolveField()` 每次重绑 | step.comp / peel-project | 是 | 无泄漏 |
| 累积纹理与帧缓冲（`Shared` 一份） | `render()` 每次 `glClear(0,0,0,0)` 整幅清空后叠画 | 同一次 render 的 resolve | 每次 render 都清 | **无残留**。`GL_SCISSOR_TEST` 全程没有任何地方开过（`ParticleWindowSurface` 出帧前还显式 disable 一次），所以清空覆盖整幅 |
| SSBO 绑定点 0–3（材料 / 状态 / 色素 / 压缩） | `advance()` 开头与**每次压力更新之后**重绑；`render()` 重绑 0/1/2；`drawReverseFrame` / `drawGestureFrame` 重绑 0–3 | step.comp / material.vert / peel-splat | 是 | 无泄漏。压力更新会把 0/1/2 换成压力缓冲，所有回到积分与绘制的入口都重绑过 |
| SSBO 绑定点 4–6（压力网格 3 块） | `PeelPressure.update()` 每次 `bind()` | peel-splat / blur / 求解器 | 是 | 无泄漏 |
| SSBO 绑定点 4 / 5（历史范围表与历史缓冲） | `bindTargets()`（合并写历史前）与 `transfer()` | history.comp、合并写历史的积分程序 | 是 | 无泄漏。合并写只发生在一个采样的最后一步，写完立刻有 `glMemoryBarrier` |
| SSBO 绑定点 7 与 `GL_DISPATCH_INDIRECT_BUFFER`（tile 表，会话私有） | `prepareTiles()`，每次批处理求解前 | pressure-tiles / pressure-batched | 是，且写完发 `GL_COMMAND_BARRIER_BIT` | 无泄漏 |
| 历史缓冲池（`Shared`，只增不减） | `acquire()` 标 busy / `release()` 清 busy | 会话私有 | 取出即独占 | **无区间重叠**：`acquire` 只挑 `!busy` 的项；`trim()` 只删空闲项。提前预算会话长期占住一块正是设计如此 |
| GL 全局状态：DEPTH_TEST、BLEND、blendFunc、viewport、VAO、clearColor | `render()` 每次全设 | — | 是 | 无泄漏。`glColorMask`、`GL_CULL_FACE`、模板、`GL_SCISSOR_TEST` 全程无人改动 |
| `Shared.owner` | `applyUniforms()` 写 this、`close()` 写 null | `activate()` | — | 正确：`activate()` 在 `advance` / `render` / `drawReverseFrame` / `drawGestureFrame` 四个入口都调用 |
| `Shared.compiled` / `uniforms` / `programs` / `textures`（非线程安全容器） | 只在各自上下文的那条线程 | 同 | — | 无并发访问：常驻上下文只有 `ParticleGl` 线程，离屏对照与预热各有自己的一份 |
| `ParticleGpuWork.playing`（进程级计数） | 会话 `beginPlayback` / `endPlayback` | `PreparationQueue.finishBatch`（决定要不要插栅栏并 flush）、`ParticleCreationPrewarm.beginIfIdle`、`ParticleSession.precomputeStep` | — | 只改**提交节奏**（栅栏与 flush），不改任何数值。这确实让对照的提交形态随并发变化，但栅栏不改结果 |
| `ParticleMicroflakeRenderer.adrenoRenderer` / `sharedMemoryBytes` | 首次查询写一次 | 同 | — | 同机同值 |
| 本地材料库的常驻工作线程池（进程级 5 条） | `parallel()` 派发 | 所有建材调用 | — | 两个线程并发调用只会在同一个任务队列里排队；每一道只写自己那段下标，`future` 各自等待，不改数值 |
| **`ParticlePerfTuning` 的三项 GPU 开关** | **`ParticleTrajectoryCheck.withBaselineGpu`** | `advance()`（`skipBeforeFirstBirth`）、`prepareReversePipeline()`（`historyInIntegration` / `jacobiLargeTile`）、`ParticleCreationPrewarm.recipe()`（三项都进缓存键） | **否** | **确有隔离缺陷**，见 3.1。方向是「对照 → 生产」，不是「生产 → 对照」 |

另有一处依赖调用顺序、核对后安全、值得记一笔的：`PeelPressure.clearTileDestination()` 不重设
tiles 程序的 `grid_shape`，靠的是它一定与同一次 `update()` 里的 `prepareTiles()` 成对出现
（`update()` 内部不会被别的会话插进来）。目前成立，改动这一段时要留意。

### 3.1 唯一一处真实的隔离缺陷：基线配方是进程级的

`ParticlePerfTuning.withBaselineGpu` 把 `jacobiLargeTile` / `historyInIntegration` /
`skipBeforeFirstBirth` 三项**进程级**开关临时改掉。而 G0 对照跑在自己的线程上，与常驻
GL 线程上的**提前预算**同时进行：

- 预算那一侧每次 `advance()` 都读 `skipBeforeFirstBirth`；
- `prepareReversePipeline()` 读 `historyInIntegration` 与 `jacobiLargeTile` 并**在编译时锁定**；
- `ParticleCreationPrewarm.recipe()` 把三项都写进缓存键。

后果（都只在 debug 且开着 `gpuCheck` 的轮次里）：默认配置下三项的基线值与生产值只有
`skipBeforeFirstBirth` 不同，而 2.4 本身是逐位恒等式，所以**没有画面后果**；但缓存键会因此
对不上而白白未命中，A/B 配置（`particle-perf-tuning.json` 打开另外两项）下预算还可能编译成
基线变体。

**已修**：三项改成「线程局部的基线标记 + 进程级设置值」两层，读取写成
`val jacobiLargeTile get() = !baselineGpu && jacobiLargeTileSetting`，读点一个字没动；
`withBaselineGpu` 只设/清一个 `ThreadLocal`。JVM 单测 `ParticleBatchFiveTest` 守住三条：
基线段里本线程读到关掉的取值、同一时刻别的线程读到原值、嵌套时内层退出不提前还原。

## 4. 功能回归（任务 2）

第四批把这两项跳过了（理由是没有改到消散、`startContent`、`BaseDialogFragment` 或任何交接顺序），
本批按第一、二批的口径两台各一轮补上。用的是本批的构建（任务 1 改了 `ParticlePerfTuning`，
所以不能用第四批的发布包 `202609150306`），该构建随后发布为 `202609150517`。

### 4.1 独立 Surface 窗口的交接像素

`verify_dialog_coverage.py <serial> --prefix b5handoff --kinds surface-handoff audio-play-handoff`

| 设备 | 场景 | 保护后 > 2/255 的像素比例 | 保护后最大通道误差 | 无保护对照 | 第一 / 二批同一格 |
| --- | --- | --- | --- | --- | --- |
| SM-S9180 | 海浪测试窗（surface-handoff） | 9.72e-06 | 3/255 | 98.69% | 9.7e-06 / 3 / 98.7% |
| SM-S9180 | 真实音频播放窗（audio-play-handoff） | 8.18e-06 | 3/255 | 97.76% | 8.2e-06 / 3 / 98.0% |
| OPD2515 | 海浪测试窗 | **0** | 1/255 | 98.54% | 0 / 1 / 98.5% |
| OPD2515 | 真实音频播放窗 | **0** | 1/255 | 98.27% | 0 / 1 / 98.2% |

脚本自带的判据是「无保护 > 10%、保护后 < 0.1%」，两台全过（退出码 0）。
三星那两格与第一、二批**逐位同量级**，「粒子层先在弹窗窗口之下就位、再撤弹窗」的顺序仍然成立。

### 4.2 弹窗四条关闭路径

`verify_repeated_appearance.py <serial> --close back|outside|cancel|confirm --system-input --repeat 3`
（`cancel` / `confirm` 必须配 `--kind chooser`，否则探针找不到那两个按钮）。

脚本的常开断言全部通过：每个循环恰好一个出现层 + 一个消散层、输入次数对得上、
`remainingFrameRateVotes == 0`、`rounds` / `menu` / `close` 与请求一致。

**SM-S9180（每条路径 3 个循环，出现层 / 消散层分列）**

| 路径 | 出现层 提交帧 | 出现层 P90 | 消散层 提交帧 | 消散层 P90 | 第二批同一格（出现 / 消散 P90） |
| --- | --- | --- | --- | --- | --- |
| 返回键 back | 34–35 | 18.9–19.8 | 62 | 19.0–30.7 | 18.6–19.2 / 23.0–25.0 |
| 点窗口外 outside | 34–35 | 18.4–20.0 | 62 | 19.5–25.4 | 18.7–19.9 / 20.1–25.2 |
| 取消 cancel | 34–35 | 18.6–19.6 | 62 | 19.9–20.2 | 18.8–19.4 / 20.1–20.3 |
| 确定 confirm | 34 | 18.0–19.0 | 62 | 19.0–20.4 | 19.2 / 19.5–21.5 |

**出现层从 38 帧降到 34 帧是第四批 2c（`dialogAppearanceSkipEmptyTail`）的预期效果**，不是回归。
帧距 P90 与第二批同量级。

**两处需要说清楚的数字**：

1. **出现层末尾有一段 220–242 ms 的空档**（第二批只有 20–90 ms）。逐帧看，它**永远是最后一个
   间隔**：出现动画播完之后会一直**驻留末帧**（与真实面板逐像素相同）直到宿主确认面板上屏，
   驻留期间只有别的会话触发重合成时才会再画一帧。2c 让播放少了 4 个采样（约 67 ms），
   而宿主确认的时刻没变，所以这段驻留自然变长。**不是动画本身的卡顿**，播放期的帧距 P90 没有变化。
2. **`--check` 这个开关的阈值已经过时，本批没有用它**。它要求
   `committed ≥ 32（出现）/ 49（消散)` 且 `p90 < 25` 且 `max < 65`。
   翻第二批留在 `tmp/particle-perf/phase0/` 的原始 `summary.json`：三星那一轮的 `max` 本来就有
   109.7 / 89.5 / 87.5 / 77.5 / 71.6 / 66.0 六格越过 65，`lastMatches` 也有五格是 false——
   **第二批那一轮同样不可能通过 `--check`**，它靠的是脚本的常开断言。本批沿用同一口径，
   并把 `--check` 的阈值写进 followups。

**OPD2515（每条路径 3 个循环）**

| 路径 | 出现层 提交帧 | 出现层 P90 / max | 消散层 提交帧 | 消散层 P90 / max | 第二批同一格（P90） |
| --- | --- | --- | --- | --- | --- |
| 返回键 back | 34–35 | 18.2–19.0 / 20.3–24.7 | 62 | 19.3–21.0 / 22.3–26.2 | 18.6–19.5 / 18.2–20.5 |
| 点窗口外 outside | 34–35 | 18.4–19.3 / 19.8–20.1 | 62 | 19.3–19.9 / 25.5–27.1 | 17.9–19.7 / 19.4–20.3 |
| 取消 cancel | 34–35 | 18.6–19.8 / 20.5–22.3 | 62 | 19.2–20.3 / 21.2–28.0 | 18.3–19.7 / 18.8–19.6 |
| 确定 confirm | 34–35 | 18.5–20.0 / 20.9–41.6 | 62 | 18.6–20.0 / 21.3–25.7 | 18.1–19.0 / 19.2–19.9 |

OPPO 这一台**连 `--check` 的严格阈值也全部满足**（`committed` 34–35 ≥ 32、`lastMatches` 全为真、
`p90 ≤ 21.0`、`max ≤ 41.6`）。**上面第 1 点那段 220 ms 的驻留空档只在三星上有**，
与三星「提交 → 上屏确认」比真实呈现晚 18–55 ms 这条既有记录一致（见 followups 的 3b 一节），
不是本专项引入的。

## 5. 单元测试

`:app:testDebugUnitTest`：**1013 项，1 失败，1 跳过**。失败项仍是既有的
`FableSolCurveFairnessTest.glVertexBuilderUsesTheSharedFairCurvesAndProjectedRowRepair`，与本批无关。

新增 `ParticleBatchFiveTest`（10 项）：①基线配方只改调用线程看到的取值（同一时刻另一条线程
读到原值）、嵌套时内层退出不提前还原、三项 GPU 开关不再是可以被别的线程改掉的进程级 `var`；
②预测素材持久化——同键只留最新的一张、配置数超上限从最旧淘汰、总大小超上限同样从最旧淘汰、
单张自己超上限时不收它但旧的不受牵连、没有新条目时只做一次收缩、磁盘键不含背景色而其余
15 项一项不少、落盘在后台线程且读回按键匹配而内存告警不动磁盘。

**注**：`:app:testDebugUnitTest` 在第 7 节的追加项之后复跑为 **1020 项，1 失败，1 跳过**
（失败项不变）。

## 6. 生产代码改动的验收

任务 1 的审查改到了 `ParticlePerfTuning`（3.1），所以按规矩补齐：

| 项 | OPD2515 | SM-S9180 |
| --- | --- | --- |
| G0 对照（提前预算**开**，创建页场景 ×2 轮） | 六条既有对照（消散 61 帧 / 出现 37 帧 / 交错 / 截断 / 命中逐位 A / 末帧等于真实页面 B）**全部零差异**；同轮的自比较按第 1 节的本底噪声出了 2 次 | 六条对照全部零差异，自比较也干净 |
| G0 对照（提前预算**关**，`particle-perf-tuning.json` 里 `creationPrewarm: false`，创建页场景 ×2 轮） | 六条对照**全部零差异**，观察器确认 `enabled: false` / 两轮都 `miss` | 同左 |
| 提前预算命中（缓存键经过 3.1 的改动后仍然工作） | 第一次 `miss: 还没有真实创建页快照可作预测素材`；第二次 **`hit`，预算耗时 171.1 ms** | 第一次 `miss`；第二次 **`hit`，预算耗时 325.6 ms** |
| 受影响场景的合并验证 | 创建页出现动画两轮正常播完、末帧与真实页面一致（G0 的 A/B 两条对照守住） | 同左 |

**这一处改动在 release 包里是恒等变换**：`withBaselineGpu` 只有 debug 的对照会调用，
release 里线程局部标记永远为空，三个开关的取值与改动前逐字相同。

## 7. 追加项：创建页预测素材持久化（用户批准）

第四批的提前预算用「上一次真实创建页的首屏」当预测素材，所以**进程启动后的第一次新建必然
未命中**。本项把那一屏无损存进应用私有目录，冷启动之后按键读回来，第一次新建也能命中。

### 7.1 存什么、按什么匹配

- **空白创建页的首屏**（标题与正文都是空的，没有任何记事内容），`Bitmap.compress` 无损压缩
  （API 30+ 用 `WEBP_LOSSLESS`，以下退回 PNG），只写 `filesDir/particle-prewarm/`。
- 与它一起存的是**去掉新记事背景色之后的那一份缓存键**（16 项里的其余 15 项，
  [ParticleCreationKey.persistent]）与来源时间。背景色每次新建都重掷，拿它去要求相等等于
  永远不命中；而背景偏差只影响途中每一片的出生、寿命与权重——**末帧照旧由当次真实快照按
  格子拼回，逐像素等于真实页面**（第四批 G0 的 A/B 两条对照守的就是这一点，本项复测仍通过）。
- 键里任何一项变了就不匹配、直接走现有路径：深浅色、语言、字号、密度、方向、窗口尺寸、
  多窗口、系统栏与刘海 inset、输入法高度、文件夹祖先链、动画档位、时长缩放、刷新率、
  新建按钮位置、影响轨迹的开关。
- 淘汰：最多 4 个配置各一张，超出按「最旧先走」；目录总大小上限 8 MB，超限同样从最旧那一端
  淘汰；单张自己就超上限时不收它（**先写临时文件、量完大小再改名**，否则同键的文件名是同一个，
  会把盘上原本好好的那一张覆盖掉再删掉）。纯逻辑在 `ParticlePrewarmRetention`，5 条 JVM 单测覆盖。
- 写盘在 `ParticlePrewarmStore` 自己的线程上、真实首屏被捕获之后；同键那张还新鲜（6 小时内）
  就不重写——键已经覆盖了版式的全部变量，每建一条记事都重压一张全屏图纯属磨损。
  失败只记日志（`diagnostics().disk.error`），不影响任何功能。
- 读回在首页静置、准备预算的那条后台线程上：索引很小，图片只在键匹配时才解码，不挡首页首帧。
- `onTrimMemory ≥ RUNNING_LOW`（含退后台）只清内存态，**磁盘缓存原样留着**。

### 7.2 实测（两台各一轮）

「抬手」= 新建按钮的 `ACTION_UP`（观察器记的真实输入），「首帧上屏」= 该帧的呈现确认。

| 场景 | 命中来源 | OPD2515 抬手 → 首帧上屏 | SM-S9180 |
| --- | --- | --- | --- |
| 冷启动、盘上还没有素材（第一次新建） | `none`（未命中） | 549.7 ms | 971.2 ms |
| 同一进程里的第二次新建 | `memory` | 196.1 ms | 310.2 ms |
| **冷启动之后的第一次新建（盘上有素材）** | **`disk`** | **221.5 ms** | **324.9 ms** |
| 冷启动 → 退后台 + `RUNNING_CRITICAL` → 回前台 → 新建 | `disk` | 195.5 ms | 315.0 ms |
| 深浅色切换后的第一次冷启动新建（键不匹配） | `none`（未命中） | 555.5 ms | 935.1 ms |
| 再冷启动一次（新配置已经存下来了） | `disk` | 209.1 ms | 294.8 ms |

**冷启动的第一次新建从 549.7 / 971.2 ms 降到 221.5 / 324.9 ms**，与同一进程里的第二次
（196.1 / 310.2 ms）基本持平——多出来的二三十毫秒落在首页静置期的解码上，不在点击之后的
关键路径里。

**磁盘占用**：单张全屏 WEBP 无损，OPD2515（2520×1680）浅色 143 460 B / 深色 93 340 B，
SM-S9180（1440×3088）浅色 142 970 B / 深色 69 002 B，**两种配置合计 OPPO 236 800 B、
三星 211 972 B**，离 8 MB 的上限还差一个数量级——4 个配置也就 0.5 MB 量级。

**命中来源统计（本轮 14 次创建页动画）**：`disk` 8 次、`memory` 2 次、`none` 4 次
（其中 2 次是「盘上还没有素材」的首次，2 次是深浅色切换后键不匹配的那一次）。

`onTrimMemory` 那一行要说清楚：`trimMemory()` 会**撤掉待办的预算调度**，所以在前台收到
内存告警之后，预算要等下一次用户交互（或回到前台时的 `onHomeVisible`）才重新排上。
上表那一行走的就是真实路径——退后台、告警、再回前台——`sourceKind` 仍是 `disk`，
证明内存态确实被放掉了而磁盘缓存可用。**没有改这条调度语义**：内存告警之后立刻重建
17 MB 的预测素材不合适。

### 7.3 方向这一项没测成，改用深浅色

两台都没能让**应用窗口**真的转过去：

- **OPD2515**：`settings put system user_rotation` 取 0/1/2/3 四个值，
  `dumpsys window displays` 的 `cur=` 恒为 2520×1680，显示本身就不转。与第四批同一结论，
  只是这次换了个更直接的判据（第四批看的是快照尺寸）。
- **SM-S9180**：`wm user-rotation lock 1` 能把显示与**已经在运行的**窗口转成 3088×1440，
  但驱动每一轮都 `am force-stop` 之后重新启动，而 `ThingsActivity` 是
  `screenOrientation="user"`——自动旋转关掉时它解析成「锁在自然方向」，于是新进程又回到
  1440×3088。快照实测仍是 1440×3088。

所以键失效那一项改用**深浅色**（`cmd uimode night yes/no`）验证：它同样在那 15 项里，
而且真的换了一张明显不同的图（见 7.2 后两行与两种配置不同的文件大小）。

## 8. 未完成项

- **「哪一片粒子、哪一个 pass」没有做到那一层。** 差异像素的坐标、两边的 RGBA 与
  「状态缓冲是否也不同」都拿到了（1.2），足以判定分歧在绘制那一段而不在 compute；
  但没有进一步反查「(726, 573) 这个像素由哪一个 `gl_InstanceID` 的四边形覆盖、
  是第 0 遍（表面）还是第 1 遍（颗粒）」。要做的话得在 `material.frag` 现成的
  `diagnostic` 通道上再加一档「把 instance id 编进颜色」，然后对同一帧重画一次。
  鉴于结论已经落在应用代码之外，本批不做。
- **三星的本底噪声率只有 88 试次、0 次事件**，不足以区分「率低」与「没有」。见 followups。
- **没有从驱动侧证实机制**（是工作组调度、抢占，还是浮点单元在不同占用率下的执行路径）。
  这需要厂商工具或可复现的最小样例，超出本专项范围；本文给出的是**现象层面**的定性：
  同一条命令流不保证逐位复现，且与并发、与回读、与我们的代码都无关。
- **`--check` 的阈值没有重写**（见 followups），本批与第二批一样不带它跑。
- **持久化这一项没测到的**：①**方向变化**两台都没能让应用窗口真的转过去（见 7.3，
  改用深浅色验证键失效）；②**多窗口、字号、语言、密度、输入法高度**这几项键值没有逐项试过，
  它们与深浅色走的是同一段判定（同一个字符串比较），按同一条路径推断；
  ③**淘汰只有 JVM 单测覆盖**，没有在真机上真的堆到 4 个配置或 8 MB——实测两种配置合计才
  0.2 MB，要堆到上限得造十几种配置；④**低于 API 30 的 PNG 分支没有设备可测**（两台都是
  API 30+，走 WEBP 无损）。
