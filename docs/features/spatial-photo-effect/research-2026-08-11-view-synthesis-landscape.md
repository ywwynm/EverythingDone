# view synthesis 全景调研与管线优化路线（2026-08-11）

用户要求：上网调研 view synthesis，看能否优化当前处理管线、一劳永逸地解决现有问题。
调研由五个并行代理执行（单图前馈表示／软边界与 α／遮挡区补全／端侧落地／工业对标，
另有端侧分割、NPU 两份子报告），全部候选按「实际打开 GitHub / HuggingFace / Qualcomm
AI Hub 页面核实权重真实发布 + 许可条款原文」执行；「论文说会放」一律不算放。本文为
交叉汇总、分析与方案，未核实项在末尾缺口清单单列。

## 一句话结论

**不存在「下载一个模型一劳永逸」的解**；但五路证据收敛到同一判断：当前
「MoGe-2 度量几何 + 双层 LDI + 断边网格」路线与业界终局同构，**层数与架构是对的，
剩余问题集中在两个变量上——边界基元（二值断边 vs 连续 α）与视差预算（带宽 ≡ 视差 ≡
补全难度）**。短期有四件零新权重的修法；中期端侧化选型本轮已可定案；长期
「一劳永逸」的真正形态是自训一个输出软 α 双层资产的前馈 student，所需的架构参考、
正则设计与数据配方本轮已经集齐。

## 1. 业界坐标系（对标核实）

### 1.1 出货形态分层

| 档位 | 代表 | 表示 | 消费方式 |
|---|---|---|---|
| 出货主流 A | Meta Quest、Google Photos on Android XR、visionOS 2 转换 | 深度 + warp → 双目立体对（MV-HEVC/SBS） | 固定视差，不可漫游 |
| 出货主流 B | **Apple iOS 26 Spatial Scenes**、vivo 3D 空间视效 | **深度 + 1～2 层 + 重投影（持久资产）** | 小基线 6DoF／陀螺仪视差 |
| 研究前沿 | Apple SHARP（ICLR 2026，权重 research-only） | **pixel-aligned 两层高斯**（768²×2 ≈ 1.2M） | 实时 >100fps |
| OS 能力 | RealityKit `GaussianSplatComponent`（WWDC26，2026-06） | 3DGS 成为系统级一等表示 | 目前服务多视角捕获 |
| 国内 OEM | 华为 Remy / Pura 90（多帧→3DGS/点云） | 绕开单图补全难题 | 非单图路线 |

我们的产品形态（单图、端侧、烘焙资产、小基线倾斜视差）正是出货主流 B，与 Apple
同一档位；Google Photos 的转换在云端、Meta 当前产物是立体对，都比我们的目标形态弱。

### 1.2 三条业界共识（证据最厚的三条）

1. **双层是反复收敛的落点**：Meta 2020《One Shot 3D Photography》手机端 LDI→mesh
   （2020 年手机全链约 1.1 s：深度 230 + 分层 94 + 补全 540 + 网格 234 ms，二手转述）；
   Google SLIDE 双层软分层；Apple SHARP 两层 pixel-aligned 高斯——深度解码器直接输出
   **主表面 + 被遮挡面两个深度通道**。**层数不是被否定的维度，基元才是**（三角形硬断边
   vs 连续 opacity）。
2. **软边界只有一个答案**：alpha matting 或连续 opacity，且**过渡带两侧都要有内容**。
   Apple 专利与 SLIDE 都点名发丝；SHARP 靠高斯 opacity 天然软边界，再用 BCE alpha
   正则压伪半透明、视差梯度正则压 floater。
3. **小基线用回归式补全就够**：Meta 2020 用小回归网络；Google Cinematic photos 干脆
   不补、逐图优化相机轨迹规避；SHARP 无独立生成网络（perceptual loss 隐式学出第二层）；
   Apple 唯一的生成式（Spatial Reframing，2026-06）只服务大机位变化、且上
   Private Cloud Compute。**我们 4.5 cm 基线稳落在「回归够用」区间。**

### 1.3 Apple 专利的边界带处理（操作价值最高的单条证据）

US 12,495,133 B2（2025-12-09 授权；同族 US 2026/0067439 A1、EP4593374A1），
mono-to-stereo 转换：

1. **低分辨率深度 + 坐标图 warp + 上采样回原分辨率，拿原图当 lookup table 取色**
   （NN 只出约 2MP 深度，细节由原图保住）；
2. **边界带三分 + 双向延伸 + matting α**：过渡带分成「只属近景／只属远景／两者兼有」；
   前景（专利明确举例 hair）与背景**各自向过渡带内延伸**，matting 网络出 α，
   **前景作为半透明层叠在不透明背景之上**——不是硬切、不是模糊；
3. **舒适度参数**：最大视差上限、按观看条件匹配目标视差、cardboard/puppet-theater
   效应预设档。

对照我们的现状：背景侧延伸（隐藏层）已有，**「前景向过渡带延伸 + matte α 半透明
叠加」这一半是缺的**——这正是玻璃条带/轮廓副本的位置。专利风险：该专利与既有
Google SLIDE 专利（US12260572）应合并做差异分析（followups 已有 SLIDE 跟踪项）。

### 1.4 对当前路线的判定

支持（证据强）：双层 + 烘焙 + 小基线回归补全 + 全链端侧（Apple 把 Spatial Scenes
下限压到 iPhone 12 且不挂 Apple Intelligence）全部有直接先例。
风险：三角网格硬断边在发丝/玻璃上先天弱于连续 opacity 基元（SLIDE 与 SHARP 都写明）；
RealityKit 已把 3DGS 组件化，中长期表示可能整体迁移，资产格式宜留「层 → 高斯」出口。

## 2. 四个未解问题的调研答案

### 2.1 剪影软边界（玻璃条带/轮廓副本）

- **αDepth / HairGuard 线确认全网无权重、无放权重的后继**（三轮检索）；Disney 惯例
  不放。要这条线只能借结构自建。
- **许可陷阱（本轮最重要的单条排除依据）**：凡在 Adobe Composition-1k 上训练的公开
  权重（ViTMatte、MEMatte、AEMatter、DiffMatte、FBA…）**一律不可商用**——代码 MIT
  不代表权重可用，HF 的 apache-2.0 标签常只描述代码。
- **立即可用的软 α**：`BiRefNet_lite-matting`（MIT，44.4M，软 α 变体——与现用偏二值
  `BiRefNet_lite` 不是同一变体；ONNX fp16 约 115MB）。备选 BEN2（MIT，94.6M，其
  Confidence Guided Matting「只精修不确定像素」与我们带上求解结构同构）。
- **契约最优的架构**：ViTMatte-S（代码 MIT，25.8M，RGB+trimap 4 通道 → α，int8 ONNX
  约 26MB）——与「断边 trimap → α」逐字对齐，但权重必须自训（见 H2）。
- **F/B 分工有据**：DRIP（NeurIPS 2024）实证「α 与 F 同训会拖累 α」——我们
  「α 用模型、F/B 用解析（去污前景色 + 第二层背景色）」的分工是当前证据下的最优解，
  D164 反解式只需把 α 来源换掉，F/B 机制保留。
- **边界落位升级**：MoDOT（WACV 2026 Oral，Apache-2.0，权重实发）联合估计深度 +
  遮挡边界，**且区分 inter-object 与 self-occlusion**——可校验/替代解析式遮挡带落位
  与 D177/D178 的自遮挡判定。
- **透明物体**：2025–2026 无「权重实发 + 可商用 + 软 α」方案（TransCues 无权重且输出
  二值；SeeClear 权重未核实）。短期靠掩膜兜底：玻璃区域整体划入 unknown 带、禁用其
  深度自遮挡截断。
- **表示级解法**：Apple LGTM（ICLR 2026）textured 2DGS——**per-texel alpha 纹理取代
  高斯解析衰减**，4K 只需约 147k primitives；代码许可宽松（Apple Software License，
  建议法务复核），**权重 NC 不可用**。这是「边界带基元升级」的现成 primitive 设计。

### 2.2 遮挡区内容（细带糊、大洞灰块、抄袭）

- **细带**：2024–2026 **不存在**权重开放、可商用、且在逐像素还原（PSNR）口径上胜过
  LaMa 的回归式后继（社区综述仓库 + 定向检索双向印证；新作全部倒向扩散+文本引导）。
  「糊」的第一嫌疑是 **512² 推理分辨率**而非模型上限：LaMa 的 FFC 全图感受野本就
  设计为分辨率鲁棒（256² 训练泛化到约 2k），且 Qualcomm `LaMa-Dilated`（45.6M，
  174MB，512²）NPU 实测 8 Gen 3 仅 54.8 ms——**提到 768²/1024² 或沿带分块的端侧成本
  可忽略**。备选机制：RETHINED 的 NeuralPatchMatch（4.3M，iPad M2 上 1024² 17.6ms，
  从已知区搬运真实高频块，不会吐条件均值）——但其论文刻意未与 LaMa 对比、无 PSNR
  口径，必须先用我们的带真值评测台验证。
- **大洞**：Moebius 仍是唯一「≤0.5B + 权重真放 + MIT/Apache + 有现成 ONNX 导出」的
  生成式补全（`simonw/Moebius-ONNX`：静态 512²、9 通道 UNet、无 text encoder、VAE
  scaling 0.13025；fp32 UNet 907MB，int8 后约 390MB **贴预算边缘**）。所有更强的 2026
  专用移除模型全部卡死：ObjectClear（NTU S-Lab NC）、OmniEraser（HF 标 apache 但基座
  FLUX.1-dev 非商用，标签不可采信）、GeoRemover（FLUX-Fill 基座）、OSOR-FLUX（同）。
  FLUX.2-klein-4B 虽 Apache 可商用但 4B/13GB VRAM 级，端侧排除。
- **抄袭度对症新线**：training-free 注意力抑制——AdaEraser（2026-05）与 PANDORA
  （2026-03），插件式零训练，可当天在 Moebius 上试、用既有抄袭度指标量化。训练侧
  同源工作 YOEO（CVPR 2026，"code will be available"，挂号）。
- **结构调整**：GeoRemover（NeurIPS 2025 Spotlight）的两阶段顺序「先在深度上移除、
  再以更新后几何为条件渲染 RGB」正中我们已有的「背景深度传播 → RGB 补全」结构——
  只搬顺序（RGB 补全以补好的深度为条件），不搬权重。以几何为条件的补全天然抑制
  「把近旁前景整块搬进洞」的抄袭伪影。
- **联合 RGB+depth 补全无可用件**（Orchid 无权重；JointDiT 不支持 mask 补全 + FLUX
  基座）；维持解耦：深度用几何外推（首选），DepthLab（Apache，但 1.5B/20–50 步）仅
  作外推明显失败时的桌面兜底。
- **端侧扩散可行性**：Qualcomm AI Hub 的 SD2.1 w8a16 三段实测（UNet 单步旗舰 NPU
  约百毫秒级）；BlazeEdit（Google，CVPR 2026 EDGE）以 195M/2 步/512² 在 Pixel 10 上
  290ms 端到端证明了上限存在——但不放权重、无定量对比，只能当设计蓝本。
- **战略问题**：出货基线若定 4.5 cm（对齐 iOS 实测 3.97 cm），遮挡带大多为细带，
  **大洞/背景板档在端侧是否必要要重新评估**——桌面 16 cm 实验才是把掩膜推到
  40–93% 的原因。视差上限裁定是这条线的上游决策。

### 2.3 端侧化选型（几何/分割/边缘/NPU）

**度量几何**：MoGe-2 ViT-S 地位不动——官方 ONNX 已发（2025-07，vits 141MB fp32）、
MIT、度量深度 + 内参齐全、许可链（DINOv2/Apache）最干净；注意 `.infer()` 里的
focal/shift 恢复与重投影**不在 ONNX 图里，端侧需自实现**。质量档 A/B：
**DA3METRIC-LARGE**（Apache-2.0，0.35B，唯一原生 metric depth + 内参 + 天空分割的
可商用档；int8 约 350MB 顶预算上限）。DA3 许可**以 GitHub model zoo 表为准**
（SMALL/BASE/METRIC-LARGE/MONO-LARGE 四档 Apache，LARGE/GIANT 系 CC BY-NC；
HF 上 LARGE-1.1 的 apache 标签为实锤误标，作者已在 discussion 确认）。排除：
Depth Pro（1.9GB + 许可含糊 + issue 无回复）、UniDepth v2（NC）、Metric3D v2
（不估内参）。挂号：MoGe-3（2026-07-21 论文，权重 coming soon）、InfiniDepth
（CVPR 2026，Apache 仓库 + local implicit decoder 任意分辨率查询，正对断边台阶；
但 RGB-only 输出相对深度、backbone DINOv3 权重许可另算）。

**分割**：主路径换 **Mask2Former swin-tiny panoptic**（代码 MIT；约 190MB；100 query
一次前向直接出实例掩膜栈；8 Gen 3 NPU 实测 104.6ms、峰值内存 9–23MB；CPU 上秒级）——
我们要的是实例身份 + 掩膜栈，不是交互抠图，闭词表（COCO-133）不是障碍。精修级：
**SAM 3 tracker q4f16**（vision encoder 296MB + decoder 5MB ≈ 301MB，现成量化档，
SAM License 可商用；仅 point/box 提示、无移动端实测）。兜底：MobileSAM/SAM 2.1 AMG
（Apache，约 50MB；32×32 网格 NPU 约 1–4s、CPU 约 10–30s，在 2–3 分钟预算内可接受）。
**SAM 3 concept 全链路端侧不可行**（唯一完整 ONNX 3.58GB，concept 分支无量化变体）；
工程假设（待验证）：固定概念词表的 text embedding 桌面预计算内置，端上只跑量化
vision encoder + decoder——前提是 concept 分支视觉编码器可自行量化。排除：FastSAM、
YOLOE、Ultralytics 系（AGPL）；EdgeSAM（NTU S-Lab NC）。已知能力边界：细碎枝叶
（植被）任何方案都给不出实例，补全掩膜设计必须按「植被不会被实例化」假定。

**NPU**：**现在不投入**，维持 CPU/XNNPACK。依据：ORT QNN EP 有实证封装损耗
1.3–5.7×（issue #24417 未关闭）且无 ViT/扩散量产案例；不支持动态形状（每分辨率一份
产物）、context binary 按 SoC 绑定且须 x86_64 离线生成；量化未必更快（AI Hub 实测
QNN_DLC w8a16 141.77ms 反慢于 float 51.94ms）；NNAPI 已在 Android 15 弃用。
文档冲突说明：旧 onnxruntime.ai 页（基于 QNN 2.22）称 HTP 仅支持量化模型，新插件仓
（EP v2.5.0，2026）已支持 `enable_htp_fp16_precision` 直跑 fp16，AI Hub 的
float-on-NPU 实测佐证新口径——**按新文档采信，结论不变**。8 Gen 2 数据在 AI Hub 以
「QCS8550 (Proxy)」名义存在：Depth-Anything-V2 ViT-S@518² ONNX float NPU 47.33ms。
CPU 侧预算核对：DA3-SMALL 504² 在 M4 CPU 507ms → 8 Gen 2 CPU 估 1.5–3s/次，串 3–4
个模型仍远在 2–3 分钟内。翻案条件：NPU 使 350M 级 ViT 从跑不动变能跑、或生成预算压到
20s 内、或改走 LiteRT（高通加速器已 GA，2025-11/12；需 ONNX→TFLite 换运行时，2027
复评）。

### 2.4 表示层「一劳永逸」候选

- **现成可下载的没有**：FlashWorld（代码 Apache、权重 CC-BY-NC + 20.9GB）、NVIDIA
  Lyra 2.0（权重 NC + 14B）均为「代码开源、权重 NC」陷阱；HunyuanWorld 系许可可用但
  1B+ 参数端侧不可行（且 Territory 排除欧盟/英国/韩国）；Matrix-3D（MIT，许可最干净）
  形态不对口（全景漫游），仅配当离线蒸馏 teacher；Meta SAM 3D Objects（SAM License
  可商用、原生 GS decoder）是物体级，等同已淘汰的实例路线，仅作遮挡物几何补全备选。
- **SHARP 形态 = 终局参考**（权重 research-only 不可用）：两深度通道 decoder、遮挡区
  无独立生成网络（perceptual loss 隐式补全）、BCE alpha 正则、视差梯度正则——这四条
  设计可直接作为我们自训的架构与判据参考。
- **MLGS**（ACM MM 2025）「base GS 层 + 多个 occlusion GS 层」= 学习式 LDI 的 GS 版，
  与我们最同构，但无代码无权重，仅概念参考。
- **渲染性能托底**：Mobile-GS（ICLR 2026）骁龙 8 Gen 3 GPU 上 1600×1063 达 116 FPS、
  资产 4.8MB——「表示层引入 splat/软基元」在我们的设备量级上渲染不是障碍。
- **自训 student 的完整配方（本轮集齐）**：
  - 架构底座：LGTM 训练代码（textured 2DGS + per-texel alpha，gsplat 集成；backbone
    须换掉——其 Flash3D backbone 是 CC-BY-NC）；
  - 正则与门禁：SHARP 的 BCE alpha + 视差梯度正则；
  - 数据：Infinigen 多层深度合成器（arXiv 2503.11633，**含玻璃/透明层真值**）+
    αMatte4K（CVPR 2026，PBR 渲染 4K matting，许可未核）+ 仓库既有 MegaScenes 三重
    筛选切片与 masked keyview 训练基础设施 + Charge；
  - teacher 白名单（许可核实可商用）：Qwen-Image-Layered（Apache-2.0，RGBA 多层分解
    且能补遮挡内容，20B 桌面产伪标签）、BiRefNet-matting（MIT）、Matrix-3D（MIT）；
  - 成本量级：参照 Flash3D「单卡一天」下限与 LGTM 4K 训练 28GB 显存，推断 4–8 卡 ×
    数天（**推断值，非核实**）。

## 3. 方案建议（三个地平线）

### H0（立即，零新权重，桌面管线上验证）

1. **细带补全分辨率**：LaMa 512² → 768²/1024² 或沿带分块，用带真值评测台 + 抄袭度
   双判据复测——先证伪「糊 = 分辨率问题」再谈换模型。
2. **大洞抄袭度**：Moebius 上挂 training-free 注意力抑制（AdaEraser / PANDORA），
   抄袭度指标量化增益。
3. **软边界按专利结构重做（本地平线的主项）**：沿断边 trimap；α 换
   `BiRefNet_lite-matting`（软 α 变体）；**补上「前景向过渡带延伸」这一半**，前景以
   matte α 半透明叠在不透明背景上——对应 HEAD vNext11 已有的高分辨率 matte + F/B
   去污 + alpha 边界机制，**不是** D161 否决的不透明点 splat（那是补缝角色，这是
   软剪影角色）；渲染期覆盖率混合需 MRT（D164 已知）。自遮挡方向沿用 D176/D178
   判定；验收沿用台阶能量/散点/抄袭度/圆周协议。
4. **逐图自适应视差上限**（Cinematic photos 思路）：按可见拉伸与带宽逐图求解上限，
   替代全局固定值——与既有逐场景安全包络逻辑同构，是它的补全难度版。

### H1（端侧化，工程为主；前置决策 = 视差上限裁定）

| 环节 | 选型 | 体积 | 依据 |
|---|---|---|---|
| 度量几何 | MoGe-2 ViT-S ONNX（后处理自实现）；DA3METRIC-LARGE int8 质量档 A/B | 141MB / ~350MB | MIT/Apache；唯一可商用 metric+内参组合 |
| 实例分割 | Mask2Former swin-tiny 主 + SAM3 tracker q4f16 精修 + AMG 兜底 | ~190MB / 301MB / ~50MB | MIT/SAM License；一次前向实例栈；实测齐 |
| 细带补全 | LaMa ≥768² 或分块（参考 Qualcomm LaMa-Dilated 导出形态） | 174MB | 无更优回归后继；实测 54.8ms@512² |
| 大洞补全 | Moebius int8（**若出货基线定 4.5cm，先复核该档是否必要**） | ~390MB 贴边 | 唯一可商用 ≤0.5B 生成式 |
| 运行时 | CPU/XNNPACK；NPU 暂缓 | — | 2.3 节 |

### H2（自训 soft-α 双层 student——「一劳永逸」的真正候选，需单独立项）

目标：前馈单图 → 双层资产（几何 + 隐藏层 + **per-texel/逐像素软 α**），一次推理替代
「分割 + 双档补全 + 逐条带判定 + matting」的组合。组件清单见 2.4。质量门禁沿用九场景
圆周协议 + 四项并列硬门禁（无形变/无条带/有空间感/无卡片人）；许可门禁：teacher 与
数据全部走可商用白名单，SHARP/LGTM 权重只作对照上界。先训边界局部的小模型
（ViTMatte-S 自训权重，25.8M，服务 H0-3 的 α 升级）作为第一个 tracer，再扩到完整
双层 student。

## 4. 需要用户裁定的三件事

1. **视差上限/出货基线**（已有 followup）：业界证据支持 ~4.5cm 档（Apple 实测
   3.97cm；专利含舒适度上限参数）。该裁定直接决定 H1 的大洞档去留与带宽预算。
2. **H0 四项的执行顺序**：默认按 1→4（1、2 各半天，3 是主项，4 可与 3 并行）。
3. **H2 是否立项**：需要 GPU 资源与数天训练周期，建议在 H0-3 验证「软 α 结构确实
   消除玻璃条带」之后再决定。

## 5. 跟踪清单与证据缺口

**挂号等发布**：MoGe-3 权重；SAMA（AAAI 2026，SAM+1.8% adapter 出 matte，有玻璃
定性证据）；SSMatte（2607.10395，自监督 matting，可解 Adobe 数据许可死结）；
EfficientSAM3 ONNX 与其「Apache 覆盖 SAM License 衍生物」定性；BlazeEdit；MLGS 代码；
YOEO；αMatte4K 许可。

**证据缺口（下轮补查）**：HunyuanWorld-Mirror LICENSE 原文（两次 404）；Matrix-3D
体积/耗时；字节 Seed3D 许可；QNN 随包体积实测；SAM3 concept 视觉编码器与 tracker
编码器是否同构（text embedding 预计算方案的前提）；LaMa-Dilated 原始 checkpoint 训练
数据条款；Snap/TikTok/小米/OPPO 与 Leia 公司现状；Google/Leia 2024–2026 专利号；
Apple Spatial Reframing 官方原文。

**专利跟踪**：Apple US 12,495,133 B2 / US 2024/0414308 A1 并入既有 Google SLIDE
（US12260572）差异分析项。

## 6. 关键来源

- Apple：SHARP（arXiv 2512.10685，github.com/apple/ml-sharp）；LGTM（arXiv
  2603.25745，github.com/apple/ml-lgtm）；专利 US 12,495,133 B2；WWDC25 #287/#317、
  WWDC26 #279。
- Google：SLIDE（arXiv 2109.01068）；Cinematic photos（research.google 2021-02）；
  BlazeEdit（arXiv 2605.28067）；Android XR 云端转换支持文档（2025-10）。
- Meta：One Shot 3D Photography（arXiv 2008.12298）；SAM 3 License；
  onnx-community/sam3-tracker-ONNX。
- matting：ZhengPeng7 全家（HF）；ViTMatte（github.com/hustvl/ViTMatte）；MoDOT
  （github.com/xul-ops/MoDOT）；FBA 非商用声明（github.com/MarcoForte/FBA_Matting）；
  Qwen-Image-Layered（github.com/QwenLM/Qwen-Image-Layered）。
- 补全：qualcomm/LaMa-Dilated 与 qualcomm/Stable-Diffusion-v2.1（HF 实测表）；
  simonw/Moebius-ONNX；OSOR（github.com/Zhouqm-Git/osor）；GeoRemover（arXiv
  2509.18538）；AdaEraser（2605.15921）；PANDORA（2603.27555）；RETHINED
  （2503.14757）。
- 几何：MoGe-2（HF Ruicheng，官方 ONNX）；DA3 model zoo（GitHub 许可表）；
  InfiniDepth（github.com/zju3dv/InfiniDepth）；MoGe-3（arXiv 2607.17967）。
- 表示：Mobile-GS（2603.11531）；MLGS（ACM MM 2025）；Matrix-3D
  （github.com/SkyworkAI/Matrix-3D）；HunyuanWorld（github.com/Tencent-Hunyuan）；
  Infinigen 多层深度（2503.11633）。
