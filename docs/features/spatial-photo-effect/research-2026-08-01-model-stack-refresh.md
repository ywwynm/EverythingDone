# 空间照片 AI 模型栈重新审计（截至 2026-08-01）

## 结论

本次不再以论文年份、榜单名次或某台旧设备上的单项延迟直接决定产品选型，而是同时核验：

1. 截至 2026-08-01 的上游最新代际与维护状态；
2. 是否已公开可复现的代码和权重；
3. 代码、权重及其基础模型许可是否允许产品分发；
4. 参数量、文件体积、运行时、算子和 Android 内存是否现实；
5. 模型解决的是深度、对象所有权、alpha 还是隐藏内容，不能跨职责替代；
6. 是否能保持原图可见像素和物体内部几何，而不是只让单帧截图更“像生成图”。

审计后的产品主线如下：

| 环节 | 2026 产品决策 | 当前状态 |
| --- | --- | --- |
| 深度 | ZipDepth 继续作为端侧默认；DA3-Small 保留为当前代相对深度质量档 | 可发布，但要纠正 DA3 语义 |
| 对象分割 | EdgeTAM 作为首个 Android/ONNX PoC；MobileSAM2 等正式权重与许可 | PoC / 观察 |
| Matting | MODNet 降级为“人像兼容 fallback”；主线改为对象 mask + 窄边界 alpha refiner | 架构改造 |
| 隐藏内容 | MI-GAN/AOT-GAN 降级为 Legacy/Lite；新后端保持可替换，不把当前非商用大模型塞入 App | 兼容保留 |
| 表示/渲染 | 对象中心 source-locked soft-LDI；生成模型只写真实显露区 | 立即实施 |
| 空间视频 | 复用对象 ID、层和隐藏内容，增加时序深度与跟踪；禁止逐帧独立生成 | 后续能力 |

“最新”不等于“现在就能随 App 商业分发”。本轮没有发现一个同时满足通用场景、高质量、
商业可分发、Android 端侧和已发布权重的 2026 通用 matting 或生成式补全模型。因此正确
动作不是继续假装 MODNet/AOT-GAN 是质量前沿，也不是下载一个非商用的 GB 级权重；而是
先修正表示、明确 Legacy 档，并为通过准入的后续模型保留下载接口。

## 深度估计

### Depth Anything 3

[Depth Anything 3 官方仓库](https://github.com/ByteDance-Seed/depth-anything-3) 于
2025-11-14 发布代码和模型。截至本次审计没有找到官方 Depth Anything 4。

需要纠正现有文档和产品语义：

- `DA3-Small` 为 0.08B 参数、Apache-2.0，支持的是 **relative depth**，不是 metric depth；
- `DA3-Base` 为 0.12B 参数、Apache-2.0，同样是 relative depth；
- 真正的单目 metric 型号是 0.35B 的 `DA3Metric-Large`，官方说明还需要焦距换算；
- `DA3-Large/Giant` 及刷新后的 `-1.1` 权重为 CC BY-NC 4.0，不能进入本产品分发；
- 官方模型表仅给 Giant 系列标出 3D Gaussian 能力，不能把 `DA3-Small` 描述成直接输出
  Gaussian 或完整新视角表示。

因此 `DA3-Small` 可以继续作为当前代的高质量**相对深度**选项，但不能再用“公制深度”
为它的遮挡阈值或设置文案背书。现有引擎里 `outputMetricDepth` 实际承担“输出是 depth、
需要先取倒数”的数值契约，应改名为不暗示物理尺度的字段。

### ZipDepth

[ZipDepth 官方仓库](https://github.com/fabiotosi92/ZipDepth) 与
[项目页](https://zipdepth.github.io/) 在 2026-07 发布 ECCV 2026 工作：6.1M 参数、
约 3.0 GMAC（384 输入）、MIT，并明确提供服务器到手机的部署与 ONNX/NPU 路径。
当前 App 使用的正是官方 NPU checkpoint 的 ONNX 导出。

ZipDepth 的旧手机基准只是部署证据，不代表模型本身来自旧年代。按“新近、许可、体积、
官方移动路径”综合判断，它仍是当前最适合作为默认下载的深度模型。它输出相对深度，层序
和相对视差可用，但不能伪装成真实尺度。

### 2026 其他候选

| 模型 | 一手资料结论 | 产品处置 |
| --- | --- | --- |
| [MetaDepth / HyDen](https://github.com/facebookresearch/metadepth) | ICLR 2026，Hybrid CNN 路径约 7.37M；权重采用 FAIR Noncommercial Research License | 拒绝分发，可作研究参考 |
| [MetaDepth-CPU](https://openaccess.thecvf.com/content/CVPR2026W/ECV/html/Mapeke_MetaDepth-CPU_Zero-Shot_Monocular_Depth_Estimation_for_Edge_Devices_CVPRW_2026_paper.html) | CVPRW 2026，论文报告最小约 2.8 MB、移动 CPU 15–30 FPS；未找到可核验的独立官方权重与商业许可 | 观察项 |
| [Video Depth Anything](https://github.com/DepthAnything/Video-Depth-Anything) | CVPR 2025 Highlight；Small 28.4M、Apache-2.0，面向长视频一致深度；官方显存数据仍远超手机静态管线 | 空间视频研究项 |
| [Depth Pro](https://github.com/apple/ml-depth-pro) | 2024 的锐利 metric depth，模型和桌面研究路径较重 | teacher/基准，不做端侧默认 |
| [DepthFM](https://github.com/CompVis/depth-fm) | AAAI 2025、MIT，以 Stable Diffusion 2.1 为基础的 flow-matching 深度 | 体积不合端侧，且不会解决对象几何 |

深度模型只决定几何线索；即使换成更准确的 2026 模型，也不会自动提供对象所有权、刚性约束、
隐藏内容或正确的前向可见性解析。

## 通用对象分割

### 2026-08-01 补充候选：RF-DETR Segmentation

[RF-DETR 官方仓库](https://github.com/roboflow/rf-detr) 已发布 ICLR 2026 的自动实例分割
模型。它与 promptable SAM 路线不同，一次前向即可给出 COCO 类别、置信度和互相独立的实例
mask；Nano 分割版为 33.6M 参数、312×312 输入，官方列出的 Nano 到 2XL 分割权重均为
Apache-2.0，并提供 ONNX export。对当前“自动把照片拆成多个 ownership 层”的需求，它比
逐点运行 automatic mask generator 更直接。

但官方延迟是 NVIDIA T4/TensorRT 数据，不是 Android 数据，且 COCO 类别域无法覆盖所有
未知物体。因此当前状态是**静态照片首选 PoC**，还不是可发布模型。PoC 必须核验：

- 官方 Apache-designated checkpoint 的精确许可证、下载大小和 SHA-256；
- 导出 ONNX 后是否只使用当前裁剪 ORT 能支持的算子，FP16/INT8 是否保持 mask 质量；
- Android arm64 CPU/NNAPI 的耗时和峰值 RSS；
- 未识别区域继续由 RGB+深度连通区域兜底，而不是把 COCO 分类结果当作完整场景解析。

### 高质量边界与视频候选：EdgeTAM

[EdgeTAM 官方仓库](https://github.com/facebookresearch/EdgeTAM) 是 CVPR 2025 的端侧
SAM 2 变体，代码和 checkpoint 均为 Apache-2.0。官方提供静态图 prompt、automatic mask
generation、视频多对象跟踪和 Core ML 导出；公开模型在 iPhone 15 Pro Max 报告约 15.7 FPS
视频跟踪、40.4 FPS 点提示分割，Core ML 三部分约 9.6 + 2 + 8 MB。

这些 iPhone 数据只证明结构具备端侧潜力，不是 Android 验收。它更适合作为 RF-DETR box/
mask 候选的边界细化器，以及未来空间视频的 mask tracking provider。PoC 必须自行完成：

- 导出 ONNX，核验动态 shape、算子并入裁剪 ORT 后的数值一致性；
- Android arm64 的 encoder/decoder 峰值 RSS、NPU/NNAPI 可用性和候选数量增长曲线；
- 使用 RGB、深度边缘和 superpixel 先产生少量 box/point，不在手机盲跑高密度全图 prompt；
- 输出互斥 ownership graph，而不是直接把嵌套 SAM masks 当渲染层。

### 更新但尚不可交付的候选

- [MobileSAM2](https://arxiv.org/abs/2607.12297) 于 2026-07-14 提交，面向 image/video
  spatial intelligence，是本轮找到的最新移动 SAM 系研究；截至 2026-08-01 未找到可核验的
  官方代码、权重和许可，因此列观察项，不能靠论文摘要直接接入产品。
- [SAM 3](https://github.com/facebookresearch/sam3) 在 2025-11 发布，SAM 3.1 于
  2026-03 增加 Object Multiplex。约 0.9B 参数、官方环境以 PyTorch/CUDA 为主，采用
  SAM License；适合作为离线 teacher/质量上界，不适合当前 Android 下载包。

此前文档推荐的 MobileSAM、RepViT-SAM 与 EfficientViT-SAM 不再是 2026 主推荐。它们只能
作为 RF-DETR/EdgeTAM 转换失败时的 Legacy 对照，不得再用 iPhone 12 或 Pixel 6 基准证明
“当前最佳”。

## Matting / alpha

### MODNet 的正确定位

[MODNet 官方仓库](https://github.com/ZHKKKe/MODNet) 对应 2020 年 arXiv、AAAI 2022，
约 6.46M 参数，目标是 trimap-free photographic **portrait** matting。它体积小、许可宽松、
现有 Android 链路已能运行，但存在三个硬边界：

1. 只对人像有明确训练目标，不能作为任意物体 matting；
2. alpha 只表达轮廓 coverage，不提供前后景真实颜色、层序或隐藏内容；
3. 把 alpha 扩成宽深度坡会制造曲面形变，把已合成原图再乘 alpha 会制造 halo。

因此设置与文档应把它改为“人像边缘兼容/Legacy”，不再称为当前质量模型；仅在已确认
人物对象的窄轮廓带使用，并保留无 MODNet 的通用对象路径。

### 2025–2026 前沿审计

| 模型 | 年份与能力 | 发布/许可现实 | 产品处置 |
| --- | --- | --- | --- |
| [µMatting / alphaMatte4K](https://github.com/kadatec/mu-Matting) | CVPR 2026，面向超细精度人像视频 alpha | 截至审计日仓库没有可用代码、权重和许可材料 | 观察 |
| [MatAnyone 2](https://github.com/pq-yang/MatAnyone2) | CVPR 2026 Highlight，人像视频细节与稳定性 | 权重约 141 MB；NTU S-Lab License 1.0 限非商用 | 拒绝分发，视频 teacher |
| [SAM2Matting](https://github.com/FudanCVL/SAM2Matting) | ECCV 2026，SAM2.1/SAM3 驱动的开放对象图像/视频 matting | CC BY-NC-SA 4.0，且 tracker + matting 组合较重 | 拒绝分发，架构参考 |
| [ZIM](https://github.com/naver-ai/ZIM) | ICCV 2025 Highlight，开放对象、透明物体和细节 | ViT-B/L，CC BY-NC 4.0 | 拒绝分发，离线质量基准 |
| [SDMatte](https://github.com/vivoCameraResearch/SDMatte) | ICCV 2025，diffusion-guided interactive matting | 代码 MIT，但依赖 SD2/TAESD/BK-SDM；需逐项审权重且端侧过重 | 研究，不进当前包 |
| [MEMatte](https://github.com/linyiheng123/MEMatte) | AAAI 2025，用 token routing 降内存/时延 | MIT、有权重，但仍是 Detectron2/ViT + trimap 桌面路径 | teacher/refiner 研究 |

截至本次审计，没有一个最新模型同时满足“通用对象、公开权重、商业分发、Android 体积、
稳定端侧运行”。当前最可靠的工程形态是：EdgeTAM 等分割模型确定对象所有权，alpha 只在
边界窄带细化；暂用基于颜色/深度/引导滤波的确定性 refiner 建立契约，再评估合法数据上
蒸馏出的专用轻量 boundary refiner。它比全图运行旧人像 matting 更符合职责，也能扩展到
非人物对象。

## 补图、Diffusion 与 Flow Matching

MI-GAN 和 AOT-GAN 均是 2021 年前后方案。它们现有体积和 ORT 路径使其仍可作为离线生成
隐藏窄带的 Legacy/Lite 档，但不再标为“最高质量”。大视差下补不出可信背景是能力边界，
不能通过继续拉伸可见前景来掩盖。

本轮核验的较新方案：

| 模型 | 一手资料结论 | 为何不直接发布到手机 |
| --- | --- | --- |
| [M2SVid](https://github.com/google-research/m2svid) | 3DV 2026，专门修复单目转立体的 warped view/disocclusion，直接对应未来空间视频 | 权重包 8.5 GB、单模型约 4.6 GB，官方在 A100/H100 测试 |
| [DreamLite](https://github.com/ByteVisionLab/DreamLite) | ECCV 2026，约 0.39B 的移动端统一生成/编辑，4-step/W8A8 展示了手机 diffusion 可行性 | 权重 CC BY-NC 4.0；约 394 MB 还未计大型 text encoder，也非专用 masked disocclusion 模型 |
| [FLUX.2 Klein 4B](https://github.com/black-forest-labs/flux2) | 2026、Apache-2.0、rectified-flow 生成/编辑 | 4B，官方仍需约 13 GB VRAM；不是 Android 补图包 |
| [FLUX.1 Fill dev](https://huggingface.co/black-forest-labs/FLUX.1-Fill-dev) | 12B 的高质量 masked inpainting | 非商用且体积远超手机 |

Flow Matching 是训练/采样框架，不会自动变成更小、更合法或更懂遮挡的模型。当前正确路线是：

1. 只为实际可能显露的 H1/H2 窄带生成 RGB + depth + confidence；
2. 已知原图像素永远不进生成器，不被 VAE 或补图覆盖；
3. 定义可替换 `GenerativeFill` 后端，Legacy GAN 与未来 student 使用同一输出契约；
4. 若要真正达到高质量端侧补图，训练约 0.3–0.5B、W8A8、少步的任务专用 student，
   使用合法的双目/相邻帧 disocclusion 数据与 teacher 监督，而不是下载一个通用非商用模型；
5. 输出生成一次后持久保存，实时倾斜只做 GPU 合成。

## 单图新视角与 3D 表示

- [Apple SHARP](https://github.com/apple/ml-sharp) 在 2025 年从单图一次前向预测 metric
  3D Gaussian，标准 GPU 小于一秒、之后实时渲染；官方渲染仍要求 CUDA，模型另有 Apple
  Research Model License，适合作为质量 teacher/表示参考，不是 Android drop-in 模型。
- [Stable Virtual Camera](https://github.com/Stability-AI/stable-virtual-camera) v1.1 为
  1.3B、576p diffusion NVS，输出与权重为非商用。
- [GEN3C](https://github.com/nv-tlabs/GEN3C) 是 CVPR 2025 Highlight，以 3D cache 约束
  视频生成；规模和 CUDA 栈不适合手机，但“缓存已知 3D、只生成未知”直接支持本项目的
  source-lock 原则。
- [Lyra](https://research.nvidia.com/labs/toronto-ai/lyra/) 是 ICLR 2026 的视频 diffusion
  自蒸馏 feed-forward 3D 场景重建，说明长期方向是把昂贵 teacher 蒸馏成持久可渲染表示，
  不是每次倾斜都重新生成图片。

质量前沿已经转向 feed-forward 3D Gaussian、3D cache + generative NVS 和任务专用
stereo/video refinement；但现有公开实现仍普遍是桌面 GPU、GB 级或非商用。手机端近期应
吸收它们的表示原则，不应伪装成已经能直接集成其权重。

## 2026 最终准入清单

### 立即保留/纠正

- ZipDepth：默认深度，标注相对深度；
- DA3-Small：当前代质量选项，标注相对深度，删除 metric 误称；
- MODNet：只作为人像 Legacy fallback，禁止全图/全对象语义；
- MI-GAN/AOT-GAN：只作为 Legacy/Lite 隐藏窄带生成器；
- 所有模型继续运行时按需下载，不进入基础 APK。

### 立即实施

- 删除宽深度羽化，建立真实对象层；
- 可见对象内部以单一 similarity/plane transform 保形；
- 分割负责 ownership，深度负责层序和层间 baseline，alpha 只负责轮廓 coverage；
- 背景只在真实 disocclusion 区读取 H1/H2；
- 模型 ID 与派生 manifest 保持可替换，使当前 provider 不绑定表示。

### PoC

- RF-DETR-Seg Nano → ONNX/ORT/Android，验证一次前向自动实例 mask；
- EdgeTAM → ONNX/ORT/Android，比较 RF-DETR box 引导、深度引导少 prompt 与自动候选；
- DA3-Base 只在 Small 明确达不到质量、且 120M 参数端侧峰值可接受时评估；
- 窄边界 alpha refiner，先做确定性基线，再决定是否蒸馏自有轻量模型。

### 观察

- MobileSAM2：等官方代码、权重、许可；
- µMatting：等实际 release；
- MetaDepth-CPU：等可核验 checkpoint/许可；
- 对 Spatial Video 比较 Video Depth Anything、EdgeTAM tracking 与 M2SVid teacher。

### 拒绝随 App 分发

- DA3 Large/Giant、MetaDepth/HyDen、MatAnyone 2、SAM2Matting、ZIM、DreamLite、
  Stable Virtual Camera：非商用许可；
- FLUX Fill、FLUX.2 Klein、M2SVid、SHARP/GEN3C/Lyra：体积、运行时或许可不满足当前
  Android 产品，即便论文更新也不能绕过准入条件。

## 验收规则

新模型只有同时通过以下条件才进入用户下载列表：

1. 上游来源、发布日期、checkpoint hash、代码与权重许可全部留档；
2. 基础 APK 体积不增长，模型与所需 Runtime 按 ABI/能力按需下载；
3. 目标 Android 设备报告下载体积、冷启动、P50/P95、峰值 RSS、温升与取消/恢复；
4. 50～100 张 held-out 场景衡量对象覆盖、边界 halo、细节、深度层序、非刚性残差和
   disocclusion 质量，不用两张测试图调阈值；
5. 参考视点保持原图，已知像素不被生成模型改写；
6. 空间视频必须报告跨帧对象 ID、alpha、depth 与隐藏纹理抖动，禁止逐帧独立推理冒充
   时序一致。

## 对此前文档的纠错

- `depth-poc-2026-08-01.md`、D52–D56 中将 DA3-Small 称作“公制深度”的表述已被本审计
  取代；其数值输出需要取倒数不等于具有真实尺度。
- `research-2026-08-01-object-centric-layering.md` 与 D61 中以 MobileSAM/RepViT-SAM 为
  主 PoC 的建议已被取代，当前首选改为 EdgeTAM，MobileSAM2 为观察项。
- MODNet、MI-GAN、AOT-GAN 的现有兼容路径可以暂时保留，但不再代表质量前沿。
- 设备年份不用于判断模型年份；旧设备基准只能作为历史部署数据，最终必须以目标 Android
  设备复测。
