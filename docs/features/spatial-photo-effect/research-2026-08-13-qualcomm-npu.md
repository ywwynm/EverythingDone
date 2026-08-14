# 空间照片端侧推理的高通 NPU 加速方案（2026-08-13）

面向"当前 47–76 秒/张、想用手机 NPU 压下来"的诉求，先适配骁龙。全部证据均来自本轮
实际打开的官方文档、Maven 仓库、AI Hub 模型卡与 ONNX Runtime 源码／issue，链接见文末。

## 0. 结论先行

1. **值得做，收益是数量级的，且不需要先做量化。** 与三星 SM-S9180（8 Gen 2 / SM8550）
   同架构的 AI Hub 机型上，Depth-Anything-V2 ViT-S@518² 以 **float 精度**在 NPU 上
   **47.2 ms**，LaMa 家族 512² **78.0 ms**。当前 CPU 路径上 DAV2 约 5.5 s、Big-LaMa
   每块 5.4 s（均为本项目真机实测）。**同模型、同分辨率、同精度下差 60–110 倍。**
2. **入口是 `enable_htp_fp16_precision`**，让现有 fp32 ONNX 直接以 fp16 在 HTP 上跑，
   模型文件不动。量化反而不是先手：AI Hub 上 DAV2 的 QNN w8a16 在 8 Gen 3 上是
   **113.0 ms**，慢于 float 的 38.7 ms，且峰值内存从 512 MB 涨到 2299 MB。
3. **主要工程量不在推理，而在"把形状钉死"和"把编译产物发下去"。** QNN EP 不支持动态
   形状；HTP 的编译产物（context binary）按 dsp_arch 绑定。这两条是真实成本。
4. **Big-LaMa 必须先重导出，否则上 NPU 会更慢。** 当前导出把 Fourier Unit 展成了 DFT
   的稠密矩阵乘（216 Einsum + 216 MatMul + 288 Cos/Sin，D203）。Einsum 在 HTP 上无对应
   算子，整个 FFC 分支会被切回 CPU，图被切成数百段，跨 EP 拷贝的代价会吃掉全部收益。
5. **改造与现有架构高度契合**：ORT Java API 已有 `addQnn(Map<String,String>)`，用法与项目
   在用的 `addXnnpack` 完全一致；QNN 的 `.so` 可以沿用 `SpatialRuntimeStore` 那套
   "签名 catalog → 下载 → SHA-256 校验 → 解压到私有目录只读 → `System.load`"；
   图编译可以挂进已有的模型下载后 `selfTest` 阶段。

## 1. 对 2026-08-11「NPU 暂不投入」的修正

`followups.md` 与 `research-2026-08-11-view-synthesis-landscape.md` 2.3 节的结论列了五条
依据。本轮逐条复核，**三条需要撤销或改写，两条成立**：

| 原依据 | 复核结果 |
|--------|----------|
| ORT QNN EP 有实证封装损耗 1.3–5.7×（issue #24417 **未关闭**） | **撤销。** 该 issue 已于 2025-08-07 关闭；内容是 Phi-3.5-mini **LLM** 的 ORT GenAI 与 Genie 对比，维护者归因于 burst 性能模式处理差异，切到 balanced 后差距收窄。视觉模型上不存在这个损耗——AI Hub 同一模型的 `ONNX`（即 ORT + QNN EP）与 `QNN_DLC`（原生 QNN）两列几乎重合：DAV2@8 Gen 3 为 36.3 vs 38.7 ms，LaMa@8 Gen 3 为 55.1 vs 52.6 ms。 |
| context binary 须 x86_64 离线生成 | **撤销。** `ep.context_enable=1` 在端上首次建 session 时即生成并落盘，`libQnnHtpPrepare.so` 就是端上编译用的库。离线生成是可选优化，不是前置条件。 |
| 无 ViT／扩散量产案例 | **改写。** ViT 有：AI Hub 官方模型库里 Depth-Anything-V2（ViT-S、24.7M）就是 float-on-NPU 跑通并给出全机型延迟表的。扩散仍无端侧量产先例，但本项目不用扩散。 |
| 不支持动态形状，每分辨率一份产物 | **成立。** 是本方案的主要改造项，见 3.1。 |
| context binary 按 SoC 绑定 | **部分成立。** 绑定粒度是 **dsp_arch**（v73/v75/v79/v81…）而非每颗 SoC，分发矩阵是 4–5 份而不是几十份。 |
| 量化未必更快（w8a16 141.77 vs float 51.94 ms） | **成立且强化。** 本轮在 DAV2 上复现了同一现象。结论：**先 fp16，量化留作后续**。 |
| NNAPI 已在 Android 15 弃用 | **成立，且与本方案无关**（本方案不走 NNAPI）。 |

原文写的翻案条件是"NPU 使 350M 级 ViT 从跑不动变能跑、**或生成预算压到 20 s 内**"。
第二条现在有数据支撑（见 2.3 的预算测算），且用户已明确提出速度诉求，**翻案条件成立**。

## 2. 基线与目标

### 2.1 当前链路（S23 Ultra 实测，2026-08-12/13）

| 阶段 | 模型 | 形状 | 实测 | 来源 |
|------|------|------|------|------|
| 深度 | MoGe-2 ViT-S（35M / 141 MB） | **动态**，长边 ≤720 对齐 14 | 未单独计时 | — |
| 抠像 | MODNet（26 MB） | **动态**，长边 512/1024/1440 三档 | 未单独计时 | — |
| 分割 | RF-DETR Seg Nano（123 MB） | 固定 312² | 未单独计时 | — |
| 边界细化 | EdgeTAM（三段 encoder/prompt/decoder） | 固定 1024² + 1×4 box | 未单独计时 | — |
| 补全 | Big-LaMa（208 MB） | 固定 512² 分块 | **建 session 5.3 s + 每块 5.4 s；540×720 共 27–30 s** | D203 |
| 全链路 | 上述五个 | 九场景逐张 | **47–76 s/张** | sessions.md 2026-08-13 |

早期还有一条可比数据：DAV2 Small@518² 在同机 CPU 上**约 5.5 s**。

**先补齐逐阶段计时是 Phase 0 的第一件事**——上表里五个阶段只有补全有拆解数，其余靠
全链路减法推断，不足以判断优先级。

### 2.2 NPU 侧对照（Qualcomm AI Hub 官方实测，float 精度、NPU 计算单元）

| 模型 | 8 Gen 1 | 8 Gen 2（QCS8550 Proxy） | 8 Gen 3 | 8 Elite | 8 Elite Gen 5 |
|------|---------|--------------------------|---------|---------|---------------|
| Depth-Anything-V2 ViT-S@518²（ONNX float） | 86.1 ms | **47.2 ms** | 36.3 ms | 25.3 ms | 18.8 ms |
| 同上（QNN_DLC float） | 92.7 ms | 50.8 ms | 38.7 ms | 24.9 ms | 19.8 ms |
| 同上（ONNX w8a16） | 44.8 ms | 28.3 ms | 20.2 ms | 15.4 ms | 12.5 ms |
| 同上（QNN_DLC w8a16） | — | 142.8 ms | 113.0 ms | 93.2 ms | 81.7 ms |
| LaMa-Dilated 512²（ONNX float） | 137.3 ms | **78.0 ms** | 55.1 ms | 43.6 ms | 34.3 ms |
| 同上（QNN_DLC float） | 133.2 ms | 75.1 ms | 52.6 ms | 40.5 ms | 32.4 ms |

三点必须注意：

- **LaMa-Dilated 不等于本项目的 Big-LaMa**：前者 45.6M/174 MB（CelebA-HQ dilated 变体），
  后者 208 MB（Places2）。同族、同分辨率、量级相当，**可作数量级参照，不可作精确预测**。
- w8a16 在 ONNX 与 QNN_DLC 两条路上表现相反，说明量化收益强依赖具体图划分，
  **必须实测，不能按"量化必然更快"规划**。
- 这些数字是稳态循环推理的均值，**不含 session 创建与图编译**。

### 2.3 预算测算（8 Gen 2，保守取 AI Hub 数 ×3 作为不确定性余量）

深度 ≈ 150 ms、抠像 ≈ 150 ms、分割 ≈ 200 ms、边界细化 ≈ 400 ms、补全 6 块 ≈ 1.4 s，
合计**推理侧 ≈ 2.3 s**。当前 47–76 s 中，纯几何/合成的 CPU 后处理（`SpatialVNextBuilder`、
`SpatialDepthNormalizer`、`SpatialSurfaceCharts` 等）不会被 NPU 改善，按 5–10 s 计，
**整链落到 10 s 量级**是合理目标；20 s 是保守验收线。

### 2.4 目标设备

| 设备 | SoC | dsp_arch |
|------|-----|----------|
| 三星 SM-S9180（S23 Ultra，主测机） | SM8550 / 8 Gen 2 | **v73** |
| OPPO Pad Mini OPD2515（副测机） | SM8845 / 8 Gen 5 | **待确认**（8 Elite Gen 5 SM8850 为 v81，8 Gen 5 需实机 `Build.SOC_MODEL` + QNN 探针确认） |

两台都是骁龙，验证条件完备。已知的公开映射：8 Gen 2 → v73、8 Gen 3 → v75、
8 Elite → v79、X Elite/X Plus → v73。

## 3. 路线选型

### 采纳：ONNX Runtime + QNN Execution Provider

理由：
- **不换运行时**。项目 43 个文件、5 个引擎、全部模型契约都建在 ORT 上；ORT 1.28.0 的
  Java API 已有 `addQnn(Map<String,String>)`，与现用 `addXnnpack` 同形。
- **不换模型格式**。fp32 ONNX 直接用，`enable_htp_fp16_precision=1`。
- **构建体系已就位**。项目已在用 `build_custom_android_package.py` +
  `ort-android-build-settings.json`；ORT 官方另有 `default_qnn_aar_build_settings.json`，
  加 `--use_qnn` 即可。
- **分发体系已就位**。QNN 的 `.so` 与现有 `libonnxruntime.so` 走同一条按需下载链路。

### 否决：LiteRT + Qualcomm AI Engine Direct Accelerator

Google 2025-11 发布的 LiteRT QNN Accelerator 在指标上很强（官称对 CPU 最高 100×，
8 Elite Gen 5 上 56 个模型 <5 ms），但对本项目有两个硬伤：

1. **需要 ONNX → TFLite 全量换运行时**，五个引擎、全部输入输出契约、全部数值对拍
   基础设施都要重做。项目刚在 D202/D203 上完成 Big-LaMa 的 Kotlin↔Python 逐行对拍，
   这套资产在 TFLite 上作废。
2. **NPU runtime 分发依赖 Google Play for On-device AI（PODAI）**。本项目模型由阿里云
   分发、用户在国内，Play 服务不可假定存在。

结论：**2027 年若 LiteRT 提供不依赖 Play 的 runtime 分发路径，再复评。**

### 否决：直接调 QAIRT / QNN 原生 API

要重写全部推理代码，且从 2.2 表看原生 QNN 相对 ORT+QNN EP 无稳定收益（互有胜负、
差距 <10%）。不值得。

## 4. 关键约束与对策

### 4.1 固定形状（最大的一块改造）

QNN EP 不支持动态形状。现状盘点：

| 引擎 | 当前形状 | 改造 |
|------|----------|------|
| `SpatialDepthEngine` ZipDepth/DAV2/DA3 | 固定 384²／518² | **无需改造** |
| `SpatialDepthEngine` MoGe-2 | **动态**，按源图长宽比对齐 14 | 钉成 4 档长宽比（1:1 / 4:3 / 3:4 / 16:9 + 9:16），源图归入最近档，输出按档裁回 |
| `SpatialMattingEngine` MODNet | **动态**，长边 512/1024/1440 × 任意比例 | 同上，档位 × 长宽比组合需收敛（建议先只保留 1024 一档 × 4 比例） |
| `SpatialSegmentationEngine` RF-DETR | 固定 312² | **无需改造** |
| `SpatialBoundaryRefinementEngine` EdgeTAM | 固定 1024² + 1×4 box | **无需改造**（三段各自建 session） |
| `SpatialInpaintingEngine` Big-LaMa | 固定 512² 分块 | **无需改造**（`SpatialInpaintingTiling` 已按 512 原生分块） |
| `SpatialInpaintingEngine` MI-GAN/AOT-GAN | **动态** | 低优先级，Big-LaMa 是当前主路径 |

**改造顺序应按"零改造 + 高收益"排**：Big-LaMa（占当前耗时的一半以上、形状已固定）
是第一优先，其次 RF-DETR 与 EdgeTAM，MoGe-2 与 MODNet 的形状收敛放到后面。

固定形状可以用 ORT 官方的 `onnxruntime.tools.make_dynamic_shape_fixed` 离线做，
一档一个 `.onnx`；也可以在导出时就固定。**每档一份模型文件会让 catalog 体积上升**，
需要在档位数和体积之间取舍。

### 4.2 图编译（context binary）

- **端上生成**：`ep.context_enable=1` + `ep.context_file_path=<私有目录>`，首次建 session
  时编译并落盘，之后直接加载。已知风险：ORT issue #18353 报告过 Galaxy S23 上量化模型
  `FinalizeGraphs` 耗时数分钟、内存 >4 GB。**fp16 路径的实际编译耗时必须在 Phase 0 实测**，
  这是整个方案最大的单点不确定性。缓解手段：`htp_graph_finalization_optimization_mode`
  取 0（默认，最快编译）、`num_graph_prepare_threads` 并行化。
- **离线预编译**：用 QAIRT SDK 或 AI Hub 编译服务按 dsp_arch 产出，随 catalog 分发。
  v73/v75/v79/v81 四份覆盖 8 Gen 2 及以后的主流骁龙。AI Hub 目前免费（有并发与频率限制），
  且提供云端真机 profile/inference，**可以在只有两台真机的条件下拿到几十种机型的数据**。
- **建议的组合**：**端上生成为主，挂进模型下载后的 `selfTest` 阶段**——用户此时本来就在等
  下载，多一次一次性编译是可接受的，且天然按真实设备编译，不需要分发矩阵。离线预编译
  作为"编译太慢"时的兜底方案。

编译产物必须与 (模型 SHA-256, 形状档, dsp_arch, QNN 版本, ORT 版本) 绑定并校验，
沿用 `SpatialRuntimeStore.ReadyMarker` 的做法；任一维度变化即作废重编。

### 4.3 QNN 库的分发与加载

需要随运行组件下发到 `arm64-v8a`（其余 ABI 无 QNN）：

`libQnnHtp.so`、`libQnnHtpPrepare.so`、`libQnnHtpV<arch>Stub.so`、
`libQnnHtpV<arch>Skel.so`、`libQnnSystem.so`，可能还要 `libcdsprpc.so`
（多数设备 `/vendor` 已有）。Skel 是 DSP 侧库，**每个 arch 一份且是其中最大的**，
四个 arch 全带会显著抬高包体；**按设备 dsp_arch 只下发一份**是必须的。
实际体积需在 Phase 0 下载 QAIRT SDK 后实测（本轮未查到公开数字，记为证据缺口）。

三个必须实测确认的工程点：

1. **`ADSP_LIBRARY_PATH` 怎么设**。Edge Impulse 的示例用
   `System.setProperty("ADSP_LIBRARY_PATH", "$nativeLibDir:/dsp")`，但 Java system property
   不进 native `getenv`；本项目的库还不在 `nativeLibraryDir` 而在私有目录。**大概率需要
   JNI `setenv()`**，或改用 QNN EP 的 provider option 传路径。这条不通，整条路就不通。
2. **unsigned PD 是否可用**。第三方 App 在非 root 下调 HTP 走的是 unsigned PD；
   ORT issue #21214 里 8 Gen 2 上报过 `Failed to create device. Error: 14001`。
   需要在 S23 Ultra 上先跑通最小样例再动主路径。
3. **`.so` 必须是真实文件而非 APK 内 mmap**（Hexagon loader 的要求，第三方实践里对应
   `useLegacyPackaging = true`）。本项目**天然满足**——运行组件本来就是下载到私有目录后
   `System.load` 绝对路径。

许可：`com.qualcomm.qti:qnn-runtime` 在 Maven Central 公开发布（许可为 Qualcomm AI Hub
Model License），AI Hub Apps 官方示例把这些库打进 APK。**但商用分发条款需要在 Phase 0
逐条读原文确认**，不能按"公开发布即可再分发"推定。这是本方案唯一的非技术阻塞点。

### 4.4 Big-LaMa 的前置重导出

D203 已定性：当前 ONNX 把 FFC 的 Fourier Unit 展成了 DFT 稠密矩阵乘。对 CPU 这是
O(n²) 的浪费；对 QNN 更糟——Einsum 在 HTP 上无对应算子，会导致 FFC 分支整体回退 CPU EP，
图被切成数百个子图，跨 EP 的量化/拷贝开销会吃光收益。

**顺序必须是：先重导出（opset 17 原生 DFT，或把 FFC 改写成 QNN 友好的等价形式），
再上 NPU。** 若 QNN 连原生 DFT 也不支持，则需要评估把 FFC 分支整体保留在 CPU 而
主干上 NPU 的混合划分是否仍有净收益——这要靠实测的分段耗时来判断，不能预判。

这件事本身在 CPU 路径上也有收益（D203 称"最高性价比的下一步"），**不是为 NPU 付的税**。

### 4.5 设备覆盖与回退

- 非骁龙（天玑、Exynos、Tensor）与骁龙老型号：保持现有 CPU/XNNPACK 路径不变。
- `Provider` 枚举从 `{CPU, XNNPACK}` 扩为 `{CPU, XNNPACK, QNN_HTP}`，
  选择逻辑：`Build.SOC_MODEL`（API 31+）判定 + QNN 后端探测 + 编译产物就绪三者同时成立
  才走 NPU，任一不成立静默回落。**回落必须静默且可观测**（写探针日志），
  不能像 D210 那样"四条证据全绿但根本没接通"。
- 设置页需要一个"计算加速"开关（自动/强制 CPU），用于用户侧排障。

### 4.6 数值验收

fp16 的动态范围是本项目的实际风险点，不是形式主义：

- **MoGe-2 输出 point map 带米制尺度**，`SpatialMogeGeometry` 要从中反解焦距。
  sessions.md 已记录过"平面上焦距反解病态"的教训；fp16 若在 scale 分支上损失精度，
  症状会是内参漂移而非画面明显坏掉。**必须比对 fx/fy/cx/cy 与 CPU 路径的相对误差**
  （既有基线：Kotlin 与桌面同分辨率差 0.65%–1.4%）。
- 深度归一化后的相对深度、matte 的 α、分割 mask 的实例身份，各自要有阈值判据。
- 沿用既有做法：同一张图 CPU 与 NPU 双跑，逐像素比对并落盘四列，
  不看"像不像"而看数。参考 `tmp/spatial-desktop-tuning/` 下的对拍脚本。

## 5. 分阶段实施

**Phase 0 — 可行性单点（不碰主路径，1 个 debug 探针）**
1. 逐阶段计时探针：把五个引擎的 session 创建与 run 分别计时并落盘（沿用
   `SpatialInpaintingBenchmarkReceiver` 的模式：`-n` 指定组件、结果写
   `files/probe.log`）。**没有这张表，后续优先级排序就是猜。**
2. 下载 QAIRT SDK，量出 arm64 各 `.so` 的实际体积；逐条读许可原文。
3. 在 S23 Ultra 上跑通"ORT + QNN EP + 一个固定形状 fp32 模型"的最小样例，
   优先选 **RF-DETR（312² 固定，零形状改造）** 或 **Big-LaMa 单块（512² 固定）**。
   记录：能否建 session、图划分里多少节点落到 QNN、首次编译耗时、稳态推理耗时、峰值内存。
4. 打通 `ADSP_LIBRARY_PATH` 的设置方式。

**Phase 0 的止损点**：若 (a) 许可不允许再分发，或 (b) unsigned PD 在两台目标机上都起不来，
或 (c) fp16 编译耗时在分钟级且无法离线绕开——任一成立即停，把 Phase 0 的计时表用于
优化 CPU 路径（重导出 Big-LaMa 已知可期）。

**Phase 1 — 运行组件扩展**
`SpatialRuntimeStore` 增加 QNN 库组（新 `packageVersion`，如 `1.28.0-q1`），
catalog 增加 `qnnRuntimes` 条目（按 dsp_arch），`SpatialRuntimeCatalogEntry` 扩展校验。
`REQUIRED_PACKAGE_VERSION` 抬版。构建脚本加 `--use_qnn`。

**Phase 2 — Big-LaMa 重导出 + 首个模型上 NPU**
先修 DFT 展开，再把 Big-LaMa 接上 QNN EP。这是收益最大的单点（当前耗时占比最高、
形状已固定）。做完就应该能在真机上看到整链从 47–76 s 掉到 20–45 s。

**Phase 3 — 其余固定形状模型**
RF-DETR、EdgeTAM、ZipDepth/DAV2/DA3 依次接入。每接一个做一次 CPU/NPU 数值对拍。

**Phase 4 — 动态形状模型的档位收敛**
MoGe-2 与 MODNet 钉档。这一步会改画质（长宽比归档带来的缩放），
**必须走九场景目检 + 视差量化的既有验收流程**，不能只看耗时。

**Phase 5 — 编译产物预热与设置**
把首次编译挂进 `selfTest`；设置页加"计算加速"开关与状态显示；
决定是否引入离线预编译分发。

## 6. 需要拍板的三件事

1. **形状档位数 vs catalog 体积**：MoGe-2 与 MODNet 每档一份 `.onnx`。MoGe-2 单份 141 MB，
   4 档就是 564 MB。可选：只钉 1 档（正方形，牺牲长宽比适配）、钉 4 档（体积换质量）、
   或 MoGe-2 继续留在 CPU（它可能不是耗时大头，等 Phase 0 的计时表出来再定）。
2. **端上编译 vs 离线分发**：端上编译零分发成本但首次可能很慢；离线分发要维护
   4 份 dsp_arch × N 个模型 × M 个形状档的矩阵。建议先端上，实测慢到不可接受再转离线。
3. **要不要现在就用 AI Hub 的云端真机农场**：能在两台真机之外拿到几十种骁龙机型的
   延迟与内存数据，代价是把模型上传到 Qualcomm（本项目模型均为公开权重，
   MoGe-2/RF-DETR/EdgeTAM/MODNet/Big-LaMa 都是可公开获取的，**不涉及用户数据**）。

## 7. 证据缺口

- QNN arm64 各 `.so` 的实际体积（尤其 Skel）——需下载 SDK 实测。
- QAIRT／qnn-runtime 的商用再分发条款原文。
- 8 Gen 5（SM8845）对应的 dsp_arch。
- QNN 是否支持 opset 17 的原生 DFT 算子。
- fp16 路径在 Android 上的 `FinalizeGraphs` 实际耗时（已知数据均来自量化模型）。
- ORT 1.28.0 自定义 AAR 构建加 `--use_qnn` 后的算子裁剪清单是否需要调整。

## 参考

- [QNN Execution Provider — ONNX Runtime](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)
- [onnxruntime/onnxruntime-qnn（插件版 EP，含 provider option 全表）](https://github.com/onnxruntime/onnxruntime-qnn/blob/main/docs/execution_providers/QNN-ExecutionProvider.md)
- [issue #24417（1.3–5.7× 封装损耗的出处，已关闭）](https://github.com/microsoft/onnxruntime/issues/24417)
- [issue #18351（fp32→fp16 on HTP 的支持来源，PR #19863）](https://github.com/microsoft/onnxruntime/issues/18351)
- [issue #18353（FinalizeGraphs 数分钟／>4 GB）](https://github.com/microsoft/onnxruntime/issues/18353)
- [issue #21214（Android HTP 初始化 Error 14001）](https://github.com/microsoft/onnxruntime/issues/21214)
- [Qualcomm AI Hub — Depth-Anything-V2](https://huggingface.co/qualcomm/Depth-Anything-V2)
- [Qualcomm AI Hub — LaMa-Dilated](https://huggingface.co/qualcomm/LaMa-Dilated)
- [Maven Central — com.qualcomm.qti:qnn-runtime](https://central.sonatype.com/artifact/com.qualcomm.qti/qnn-runtime)
- [Maven Central — com.microsoft.onnxruntime:onnxruntime-android-qnn](https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime-android-qnn)
- [Edge Impulse — QNN hardware acceleration on Android](https://docs.edgeimpulse.com/tutorials/topics/android/qnn-acceleration)
- [Google AI Edge — Qualcomm NPU with LiteRT](https://ai.google.dev/edge/litert/android/npu/qualcomm)
- [ONNX Runtime — Build models for Snapdragon NPU](https://onnxruntime.ai/docs/genai/howto/build-models-for-snapdragon.html)
- [Qualcomm AI Hub — Compiling Models](https://workbench.aihub.qualcomm.com/docs/hub/compile_examples.html)

---

# 附录 A：NPU 预算下的模型升级候选（2026-08-13 追加）

用户裁定「质量优先——加速省下的预算换更大的模型」。本节只做候选与量级，
**实际选型必须等 NPU 单点跑通、拿到真实延迟之后再定**。

## A.1 深度：MoGe-2 换更大 backbone（改动最小、优先级最高）

微软官方已导出三档 ONNX，**输入输出契约完全相同**
（`image` + `num_tokens` → `points` / `normal` / `mask` / `metric_scale`，
动态分辨率、opset ≥ 14），全部 **MIT** 许可：

| 变体 | HF 仓库 | ONNX 体积 | fp16 权重 | 现状 |
|------|---------|-----------|-----------|------|
| ViT-S | `Ruicheng/moge-2-vits-normal-onnx` | 141 MB | 约 70 MB | **在用** |
| ViT-B | `Ruicheng/moge-2-vitb-normal-onnx` | **419 MB** | 约 210 MB | 推荐的质量档 |
| ViT-L | `Ruicheng/moge-2-vitl-normal-onnx` | **1.32 GB** | 约 660 MB | 高内存设备实验档 |

代码改动量：`SpatialDepthModel` 加枚举项 + catalog 加条目 + 调 `minimumTotalRamMb`。
`SpatialMogeGeometry` 的焦距反解、`generateMoge` 的两输入四输出路径**一行都不用改**。

量级估计（按 ViT 参数量线性外推，ViT-S 24M / ViT-B 86M / ViT-L 307M）：ViT-B 约 4×、
ViT-L 约 13×。当前 ViT-S 在 8 Gen 2 CPU 上 `depth.run` 实测 5.1 s，若 NPU 把 ViT-S
压到 100–200 ms，则 ViT-B 约 0.4–0.8 s、ViT-L 约 1.5–3 s——**两档都在预算内**。

真正的约束是 **HTP 内存**，不是时间：AI Hub 上 DAV2 ViT-S float 峰值就有 366–477 MB。
ViT-L 的 660 MB fp16 权重加激活很可能超出 HTP 可用范围，届时只能走量化。
**因此推荐路径是 ViT-B 先上，ViT-L 作为需要量化验证的实验档。**

## A.2 其它环节

- **补全**：Big-LaMa 已经是当前家族最大的，升级方向不是换更大模型而是
  **提分辨率／改分块策略**（H0-1 的待验项）。前提仍是 D203 的重导出。
- **分割**：`followups.md` H1 已选定 Mask2Former swin-tiny（MIT，约 190 MB，
  一次前向出实例掩膜栈，AI Hub 实测 8 Gen 3 NPU 104.6 ms、峰值内存 9–23 MB）
  作为 RF-DETR Seg Nano 的升级。NPU 数据现成，是第二顺位的升级。
- **抠像**：H0-3 已选定 `BiRefNet_lite-matting`（MIT 软 α 变体）替代 MODNet。
  它服务的是软边界结构改造，不只是"更大"。
- **边界细化**：EdgeTAM 当前 `boundary.run.encoder` 只占 0.5 s，不是瓶颈，暂不动。

## A.3 排序建议

1. **MoGe-2 ViT-B**——同契约换权重，零算法风险，深度质量是整条链路的上游。
2. **Mask2Former swin-tiny**——AI Hub 有现成 NPU 数据，替换 RF-DETR。
3. Big-LaMa 分辨率／分块（需先重导出）。
4. BiRefNet matting（与 H0-3 的软边界改造绑定，不是单纯换模型）。

每一档升级都必须走既有的九场景目检 + 视差量化验收，不能只看耗时表。
