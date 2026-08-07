# 空间照片补图、Flow Matching 与任务专用生成模型调研

日期：2026-07-31
状态：已完成公开资料、可分发产物、AOT-GAN/Big-LaMa 转换与统一桌面对照，并复核 2025～2026
年最新的 SHARP、MetaView 等单图新视角路线；Android 真机质量和性能验收仍待用户测试。

## 结论先行

1. **Flow Matching 不是可以直接替换 MI-GAN 的单个模型。**它是训练连续生成流的方法；真正落地仍
   需要确定网络、输入条件、已训练权重、采样器、量化格式和 Android Runtime。公开的通用
   Flow Matching 代码库没有给出适合本项目的现成遮罩补图权重。
2. **现阶段不应在设置页提供一个名为 “Flow Matching” 的可下载选项。**截至本次调研，没有候选同时
   满足通用自然场景、遮罩补图、可合法再分发、Android 可运行、体积和时延可接受。应让底层模型 ABI
   支持未来接入，而不是把训练算法名称暴露成产品模型。
3. **MI-GAN 不是质量最优方案，但仍适合保留为极速档。**官方明确提示大面积补图更适合用小笔画
   逐步完成，这与当前大视差下的宽显露背景表现一般相符。
4. **第二档最终选择 AOT-GAN Places2；Big-LaMa Places2 不进入 stable catalog。**二者都是单次
   前向、面向大遮罩的通用场景模型，体积与 Android 风险明显低于生成式 diffusion。本轮已完成
   AOT-GAN 官方 Places2 checkpoint 导出、许可归档、ONNX 数值对照、算子覆盖和统一桌面比较；
   Big-LaMa 约 208 MB 且没有形成稳定质量优势。
5. **Moebius Places2 是值得继续验证的生成式高质量候选，但不适合当前稳定版。**它是专用 latent
   diffusion 补图模型，
   226M 参数、20 步，公开自然场景权重；但官方 Places2 主干权重为 905 MB，第三方 FP32 ONNX
   整包约 1.24 GB，且论文时延来自 NVIDIA L40S，不是移动设备数据。因此只能先作为高端设备实验档
   做转换、量化和峰值内存 PoC。
6. **Apple SHARP 更接近正确的表示，但不能用于本 App。**它一次前向输出 metric 3D Gaussians，
   技术上比 RGB 补图更接近邻近视点合成；但官方 checkpoint 为 2,809,738,232 字节，公开渲染链路
   仍以 CUDA 为主，模型许可又明确只允许非商业科研并排除 product development。不能把它上传到
   阿里云或集成到 EverythingDone。
7. **2026 年 7 月发布的 MetaView 证明 diffusion 能做大视差新视角，但不是移动候选。**它依赖
   20B 参数 Qwen-Image-Edit，以及 1.15B 参数、CC BY-NC 4.0 的 Depth Anything 3 GIANT 等多个
   大模型；即使 MetaView 自身权重标为 Apache-2.0，完整依赖链仍同时不满足再分发、体积、内存和
   Android Runtime 门槛。
8. **当前 P1 扭曲不能仅靠换补图模型解决。**补图只补颜色，而当前双层表示还依赖单目深度断边、
   二值拓扑、保守推测的背景深度和硬合成。若遮罩、边界或隐藏深度错误，强生成模型只会生成更真实的
   错误背景。
9. **更接近 Apple 观感的长期主路线应是任务专用模型：输入原图、遮罩、深度和目标相机，联合输出
   隐藏颜色与深度，或直接输出可渲染的软分层/多视图表示。**通用 inpainting 只是过渡组件。
10. **未来 Spatial Video Effect 不能逐帧独立调用静态补图模型。**应至少让相邻帧共享深度、遮罩、
   随机状态和特征，最终采用视频级联合补图或蒸馏后的时序模型。

建议的近期产品分档是：

| 档位 | 候选 | 目标 | 当前状态 |
|---|---|---|---|
| 极速 | MI-GAN Places2 512 | 小体积、低等待、保守视差 | 已接入，继续保留 |
| 均衡 | AOT-GAN Places2 | 单次前向，提供不同的大遮罩场景先验 | 已完成 PoC 并作为第二模型接入 |
| 生成式高质量（实验） | Moebius Places2 | 更强的大遮罩语义与纹理生成 | 仅做高内存设备可行性 PoC |
| 新视角研究参考 | SHARP / MetaView | 直接预测可渲染表示或目标视角 | 许可、体积或依赖链不允许产品接入 |
| 未来任务专用 | RGB + mask + depth + camera 条件模型 | 联合几何与内容、服务照片和视频 | 需要自建训练/蒸馏链路 |

## 落地验证补充

本轮调研后继续执行了候选产物验证，结果如下：

### AOT-GAN Places2

- 从官方 Places2 checkpoint `G0000000.pt` 导出动态空间、opset 18 ONNX；
- checkpoint 为 60,829,150 字节，SHA-256：
  `9c30e3b979b69e46ca80482f8a75f37fa2d62e97c678e7c7b0f12775e75d9e8e`；
- ONNX 为 60,989,366 字节，SHA-256：
  `6b255797029da17f60ef1e8860c6a6ccad13a0de4f97ab877a69f937946388e4`；
- 512² 桌面 CPU 前向约 0.42 秒；PyTorch/ONNX 平均绝对误差约 `3.9e-6`，最大约
  `8.3e-4`；
- 真实 1200×901 空间显露带上，512/768/1024 工作长边前向约
  0.46/1.11/2.09 秒，观测峰值增量约 236/507/941 MB；
- 1024 档的硬边界差异指标优于 MI-GAN，但实际画面仍可能延伸人物等前景；在规则室内结构样本上
  三档均能完成物体移除。结论是它提供**不同的内容先验**，不是对 MI-GAN 的单调质量升级。

因此 AOT-GAN 作为第二种可选模型接入，提供 512/768/1024 三档并设置实时内存门禁；默认 768，
设置文案明确“结果不保证更好”。

### Big-LaMa Places2

- 验证 Carve 的固定 512×512、opset 17 FP32 ONNX：208,044,816 字节，SHA-256：
  `1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6`；
- 两个统一样本的桌面 session 初始化约 5.8 秒，推理约 0.82～0.84 秒，进程峰值约
  760～773 MB；
- 规则结构样本可用，但真实空间显露带仍出现错误填充，且没有相对 MI-GAN/AOT-GAN 的稳定优势；
- 模型还会把 `Cos`、`Sin`、`Einsum`、`Range` 等额外算子带入裁剪 Runtime。

在没有稳定质量收益时，208 MB 下载、固定分辨率、初始化等待和 Runtime 膨胀不成立，因此
Big-LaMa 不上传 stable catalog。

### 几何侧同步修正

代码审计确认 P1 直接使用完整请求视差，而 P0 会执行 `SpatialWarpBudget`。这会让双层模式在同一
强度下更容易拉伸连续表面。本轮让 P0/P1 共用 `safeParallaxMotion`，同时让 P1 的预算忽略显式
断边、只分析仍连接的表面；它因此可以保留由背景层承接的遮挡视差，又不会放任物体内部拉伸。查看器
还提供保持同一视点与强度的即时切换；换补图模型不再承担无法解决的几何问题。

## 一、先区分三种容易混淆的 “flow”

### 1. 光流或位移场

图像配准、视频运动估计、CheapNVS 的 pixel shift map 等也会使用 flow 一词。它表示每个像素向哪里
移动，属于几何或运动场。

### 2. Normalizing Flow

传统 Normalizing Flow 使用可逆变换和变量替换公式显式建模概率密度。它与本次讨论相关，但不是
当前用户所指的主要方向。

### 3. Flow Matching

[Flow Matching 原论文](https://arxiv.org/abs/2210.02747)训练一个随时间变化的速度场，使简单分布
（通常是噪声）沿连续路径到达图像分布。训练阶段直接回归目标速度场，不需要在训练损失内部完整求解
ODE，因此称为 simulation-free training；推理阶段仍通常要对 ODE 积分。

用简化记号表示：

```text
噪声 x0 ──条件概率路径 pt──> 图像 x1
                     │
                 学习速度 vθ(x, t)
                     │
             推理时求解 dx/dt = vθ(x, t)
```

原论文明确指出，常见 diffusion 的高斯概率路径属于 Flow Matching 可表达的路径子集。因此
“diffusion 与 Flow Matching”不是两个完全割裂的时代；区别主要在训练目标、路径、模型参数化和
采样方程。

## 二、Flow Matching 家族到底带来什么

### 2.1 Conditional Flow Matching

基础 Flow Matching 可以把类别、文字、图像、遮罩、深度等作为条件输入。对本项目来说，一个真正
适用的条件至少应包括：

- 已知 RGB；
- 最大允许视点轨迹形成的联合显露遮罩；
- 原始与稳定化深度；
- 前景/背景边界或 soft alpha；
- 目标相机方向或视差范围。

没有用这些条件训练的通用 Flow Matching 图像生成器，不会自动理解“这是单目空间照片中被前景遮挡
的背景”。

Meta 的[官方 Flow Matching 代码库](https://github.com/facebookresearch/flow_matching)目前主要是
算法、求解器和训练示例；官方 FAQ 明确没有发布预训练模型，代码还是 CC BY-NC。它适合作为研究参考，
不构成可发布的 App 模型。

### 2.2 Rectified Flow

[Rectified Flow](https://arxiv.org/abs/2209.03003)希望把噪声到数据的轨迹拉直。路径越直，数值求解
所需步数越少；通过 reflow 或蒸馏还可进一步接近一步生成。

这能降低 NFE（网络前向次数），但不能自动降低：

- 单次前向的参数量与 FLOPs；
- VAE 编解码开销；
- 激活内存；
- 遮罩、深度与相机条件的训练成本；
- 权重的下载体积。

因此“一步 rectified flow”可能仍比一个 6M 参数的单次 GAN 慢得多。

### 2.3 MeanFlow

[MeanFlow](https://arxiv.org/abs/2505.13447)不只预测瞬时速度，而是直接学习一段时间内的平均速度，
可在 ImageNet 256 上一步生成。它说明 flow 家族确实存在无需多步 ODE 的方向。

目前公开的主要结果是无条件/类别条件图像生成，不是通用自然场景遮罩补图，更没有适合 EverythingDone
再分发的 Android 权重。它对本项目的意义是未来自训练/蒸馏时可评估，而不是今天可下载的第三个模型。

### 2.4 Flow Matching 用于 inpainting 的现状

#### Restora-Flow

[Restora-Flow](https://github.com/imigraz/Restora-Flow)使用无条件 flow prior，并在采样轨迹中用遮罩和
退化观测进行校正。它支持 inpainting、超分和去噪；官方示例的 box inpainting 使用 64 个 ODE
steps，公开实验按 CelebA、AFHQ-Cat、COCO 等数据集分别准备 prior。

问题：

- 它是“生成 prior + 采样引导算法”，不是一份通用移动补图权重；
- 64 次前向与当前移动端目标不匹配；
- 官方仓库要求 CUDA 版 PyTorch 环境；
- 本次检查时仓库根目录未提供 LICENSE 文件，不能据此发布到阿里云。

#### PnP-Flow

[PnP-Flow](https://arxiv.org/abs/2410.02423)在数据一致性梯度、Flow Matching 路径重投影和去噪之间
迭代。它针对逆问题很有研究价值，也避免对 ODE 做反向传播，但仍依赖预训练 flow model 和多轮优化，
没有移动端自然场景补图包。

#### FlowChef

[FlowChef](https://github.com/FlowChef/flowchef)在 rectified-flow 的向量场中做 training-free steering，
可把 FLUX、InstaFlow 等生成模型用于 inpainting 逆问题。官方命令对 InstaFlow box inpainting
使用 200 inference/optimization steps，rectified-diffusion 示例也使用 100 steps；所依赖的基础模型
远大于当前手机预算。

#### PMRF

[PMRF](https://github.com/ohayonguy/PMRF)把 posterior mean 与 rectified flow 结合，用于图像恢复。
公开实用权重主要面向人脸恢复；inpainting 结果属于受控实验，不是通用 Places2 移动补图模型。

#### 最新大型模型

- [Mage-Flow](https://arxiv.org/abs/2607.19064)使用 rectified flow matching，Turbo 编辑模型为 4B
  参数、4 步，在 A100 上处理 1024² 编辑约 1.02 秒。它证明 FM 能用于高质量编辑，但模型规模与
  Android 完全不匹配。
- [NanoFLUX](https://arxiv.org/abs/2602.06879)报告 2.4B flow-matching 文生图模型的手机端结果，
  但它不是遮罩补图，且本次未找到可核验的官方 Android 权重和发布包。2.4B 参数即使压到 4-bit，
  也不适合当前按需补图组件的内存预算。

### 2.5 Flow Matching 的本项目结论

Flow Matching 的优点是真实的：

- 路径设计比固定 diffusion 路径更灵活；
- Rectified Flow、MeanFlow 可显著减少采样步数；
- 适合将大型 teacher 蒸馏成少步 student；
- 能把遮罩、深度、相机等条件纳入统一生成模型。

但短期阻塞也很明确：

- 没有合适的公开通用场景移动权重；
- few-step 只解决 NFE，不解决主干参数和激活内存；
- 现成 inverse-problem 方法往往反而需要数十到数百步；
- 公开代码、基础权重与最终权重的许可分别需要审查；
- 只生成 RGB 仍不解决隐藏深度和多视角一致性。

因此 Flow Matching 应进入**自研任务专用模型的候选训练目标**，暂不进入用户可选模型列表。

## 三、现有 deterministic / GAN 候选

### 3.1 MI-GAN：继续作为极速档

[MI-GAN 官方仓库](https://github.com/Picsart-AI-Research/MI-GAN)提供 MIT 代码、Places2 权重和完整
ONNX pipeline。当前发布包约 28.1 MB，单次前向，已经与现有 ONNX Runtime 和 catalog 链路接通。

它的局限并非本项目集成错误：

- 模型容量约 5.98M 参数；
- 官方 ONNX pipeline 会围绕遮罩裁剪和缩放到 512²；
- 官方文档明确建议：高分辨率且补图区很大时，使用小而渐进的笔画效果更好；
- 当前空间照片会一次提交沿主体轮廓延伸的宽显露带，正好偏离其最擅长的用例；
- 它只输出 RGB，不输出隐藏背景深度。

结论：保留、重命名为“极速”或客观描述体积/等待时间；不要再把它视为质量上限。

### 3.2 AOT-GAN：近期均衡档首选 PoC

[AOT-GAN](https://github.com/researchmm/AOT-GAN-for-Inpainting)用多感受野 AOT Block 聚合远处上下文，
目标本来就是大面积高分辨率缺失区域。官方提供 Places2 与 CelebA-HQ 权重，代码为 Apache-2.0。

[Qualcomm AI Hub 页面](https://aihub.qualcomm.com/mobile/models/aotgan)给出的 CelebA-HQ 512 模型
为 15.2M 参数、FP32 58 MB，并列出 phone/tablet 形态；但同一页面当前又标为“not supported on any
Mobile chipset”，且公开 checkpoint 是人脸，不是自然场景。因此该页面只能证明模型规模和可导出
形态，不能当作已经完成手机性能验证。

PoC 前仍需解决：

- 导出官方 Places2 权重，而不是误用 CelebA-HQ；
- 单独确认 Places2 权重的再分发条款，不能仅用代码 Apache 许可替代权重许可；
- 核对 ONNX Runtime 1.28.0 的算子、数值和动态/固定输入；
- 用真实显露带测试结构连续性，而不是只看通用 object removal 示例。

如果这些门槛通过，它比直接引入大 diffusion 更适合作为第一个新增模型。

### 3.3 LaMa：更强全局结构备选

[LaMa](https://github.com/advimman/lama)使用 Fourier Convolution 获得大感受野，官方报告即使只在
256² 训练也能泛化到约 2K，并擅长周期结构。Big-LaMa Places2 对建筑、栏杆、道路等可能比小型 GAN
更稳定。

[Qualcomm LaMa-Dilated 页面](https://aihub.qualcomm.com/mobile/models/lama_dilated)给出的特定
CelebA-HQ 版本为 45.6M 参数、FP32 174 MB；页面当前同样标注没有受支持的移动芯片。官方 Big-LaMa
并非这一人脸 checkpoint，且 FFT/复数相关计算可能成为 ONNX/NNAPI 算子障碍。

结论：作为 AOT-GAN 失败时的第二 deterministic 候选；先做转换，不先上传或承诺。

### 3.4 RETHINED：很适合移动端，但暂时没有可用产物

[RETHINED](https://arxiv.org/abs/2503.14757)使用轻量 CNN 恢复低分辨率结构，再用 NeuralPatchMatch
把原图高频纹理搬回高分辨率，论文报告可在多种移动设备达到不高于 30 ms。

这条路线对窄显露带很有吸引力，因为大部分纹理可以从邻域传播，只有低分辨率结构需要生成。但本次
检查的[项目仓库](https://github.com/CrisalixSA/rethined)仅包含网页资源，没有正式代码、权重和明确
模型许可。因此只能列入观察清单。

### 3.5 Layout-Guided Mobile Inpainting：边界条件值得借鉴

Samsung 的
[Efficient Layout-Guided Image Inpainting for Mobile Use](https://research.samsung.com/research-papers/Efficient-Layout-Guided-Image-Inpainting-for-Mobile-Use)
用 pixel-wise layout 指导粗到细的两个轻量子模型，重点处理精细物体紧邻空洞的 mixed scenes，并
展示了移动端 demo。这与当前前景轮廓紧邻隐藏背景的场景很接近。

本次没有找到官方代码和权重，无法作为下载候选；但它支持一个重要方向：补图输入不应只有 RGB 和
二值 mask，还应提供由深度拓扑得到的前景/背景布局条件。

## 四、diffusion 候选

### 4.1 Moebius：最值得做高质量实验，但还不能直接发

[Moebius 论文](https://arxiv.org/abs/2606.19195)是面向 inpainting 的专用 latent diffusion：

- 226M 参数；
- 512² 输入；
- Places2、CelebA-HQ、FFHQ 专用权重；
- 默认 20 步；
- 论文在单张 NVIDIA L40S、batch 1 上报告 26.01 ms/step、总计约 0.52 秒；
- Places2 评测包含 40%～50% 及 large/small masks，任务比 MI-GAN 的小空洞更接近大显露场景。

部署事实：

- 官方 [Places2 权重目录](https://huggingface.co/hustvl/Moebius/tree/main/ft_places2)为 905 MB；
- 还需要 VAE；
- 第三方 [Moebius ONNX 导出](https://huggingface.co/simonw/Moebius-ONNX/tree/main)为
  1.24 GB：U-Net 907 MB、VAE encoder 137 MB、decoder 198 MB；
- 第三方导出只能证明 ONNX 表达在特定实现中可行，不代表 Android ORT、FP16/INT8 或峰值内存已通过；
- 官方 GitHub 代码是 Apache-2.0，但官方 Hugging Face model card 标成 MIT，发布前必须向上游或
  完整文件条款确认权重许可，不能自行选择较宽松者。

预计的 Android 风险：

- 20 次 226M 主干前向；
- VAE 编码和解码；
- 中间 latent、skip activation 与 ORT arena 同时驻留；
- Local/Interactive-λ 与相关 reshape/attention 图的算子和内存规划；
- FP16 可能降低体积，但 VAE 和归一化层数值稳定性需验证；
- INT8/4-bit 不能按“参数位数”直接估算画质与实际 Runtime 支持。

结论：可作为“生成式高质量（实验）”PoC；只有压缩包、峰值 PSS、时延、热状态和输出质量全部过线后，
才考虑让高内存设备下载。不能直接把第三方 1.24 GB ONNX 上传到稳定 catalog。

### 4.2 MobileDiffusion：证明手机可跑，不等于有可用补图模型

Google 的[MobileDiffusion](https://research.google/blog/mobilediffusion-rapid-text-to-image-generation-on-device/)
是 520M 参数的移动 latent diffusion，通过移动架构和 DiffusionGAN 一步蒸馏，在高端 iOS/Android
设备生成 512² 图像约 0.5 秒。

它证明“生成式模型在手机上一定太慢”已经不成立，但没有公开可供本项目再分发的通用遮罩补图权重；
文生图条件也与本项目不同。真正的启发是：

- 需要移动端专门设计的 U-Net/VAE，而不是直接量化桌面模型；
- 减少步骤与缩小每一步网络必须同时做；
- 任务专用蒸馏比套用通用基础模型更现实。

### 4.3 DreamLite：工程证明很强，但许可和任务都不合适

[DreamLite](https://github.com/ByteVisionLab/DreamLite)为 0.39B diffusion，移动版 4 步，可在
iPhone 17 Pro 上约 3 秒生成或编辑 1024² 图像。但它是文本指令编辑，不是二值 mask inpainting；
权重 gated，官方明确限制为非商业研究且禁止公开再分发。

结论：只作为移动架构和量化参考，不能进入 EverythingDone。

### 4.4 大型 Stable Diffusion / FLUX Fill

SD 2 Inpainting、SDXL、FLUX.1 Fill 等能够在大遮罩中生成更丰富的背景，但模型和文本组件通常以
GB 计，多次采样；FLUX.1-dev 系列还存在非商业许可约束。即使按需下载，也会带来不可接受的安装、
峰值内存和热负载，不适合本项目。

## 五、为什么更强补图仍可能让空间效果变差

通用补图指标通常评价一张最终平面图，而空间照片需要沿连续相机轨迹反复观看。它额外要求：

1. **遮罩外完全保真。**用户的原图主体不能被 VAE 重建或生成模型悄悄改写。
2. **边界拓扑正确。**头发、栏杆、树叶、文字边缘的前后关系不能因深度误差被切错。
3. **隐藏深度合理。**背景颜色生成正确但深度仍贴在前景上，移动时会产生拉伸、穿插或错误遮挡。
4. **多个视点共享同一个隐藏世界。**不能左移生成一套背景、右移又生成另一套互相矛盾的背景。
5. **显露带外要有安全余量。**遮罩应是整个允许相机轨迹显露区域的并集，不只来自单个方向或当前帧。
6. **输出应可复现。**同一 derivative 生成事务使用固定 seed；失败不能替换旧结果。

当前 P1 双层中，可能造成“更大视角但更扭曲”的主要因素包括：

- P1 使用完整请求视差，而 P0 有全局 Jacobian 预算限幅；
- RGB 引导的硬断边仍可能把纹理边缘误当成几何边界，或漏掉真实遮挡边缘；
- 二值连接与两层深度不能表达头发、玻璃、运动模糊和半透明边缘；
- 规则推测的背景深度与补出的 RGB 没有联合约束；
- 600px 网格与硬 depth test 在细结构处仍会显出三角形/切面；
- 通用补图看到的 context 可能仍含前景颜色，容易延伸前景而不是恢复被挡背景。

所以补图模型升级与以下 P1.1 几何改进应并行：

- 为 P0/P1 增加即时切换，保留逐图判断；
- 用完整视点轨迹生成联合 disocclusion mask；
- 边界采用 soft alpha/matting，而不是只有 connected/disconnected；
- 区分纹理边缘与深度遮挡边缘；
- 对背景深度做分区传播或联合预测，并在合成前做一致性检查；
- 在边界风险高时自动降低相对视差，不把全部运动都交给双层。

## 六、任务专用 NVS 比通用补图更接近目标

### 6.1 3D Photo Inpainting 与 SLIDE

[3D Photography using Context-aware Layered Depth Inpainting](https://openaccess.thecvf.com/content_CVPR_2020/html/Shih_3D_Photography_Using_Context-Aware_Layered_Depth_Inpainting_CVPR_2020_paper.html)
不是只补 RGB，而是围绕 LDI 的颜色和深度共同扩展。

[SLIDE](https://research.google/pubs/slide-single-image-3d-photography-with-soft-layering-and-depth-aware-inpainting/)
进一步指出 hard layering 无法很好表达 matting 等复杂外观，引入 soft layering 和 depth-aware
inpainting。它与当前 P1 的问题高度一致：表示层和补图必须联合设计。

这些研究没有现成移动权重可直接接入，但给出了正确的自研目标。

### 6.2 CheapNVS

[CheapNVS](https://arxiv.org/abs/2501.14533)把 RGB-D 编码、目标相机条件位移、遮挡 mask 和 inpainting
放在一个轻量端到端网络中，论文报告 Samsung Tab S9+ 超过 30 FPS。

需要特别说明：论文中的 flow/shift decoder 指像素位移场，不是 Flow Matching。它的局限是
narrow-baseline，公开页面也未提供可直接发布的权重。但其架构比“通用 MI-GAN + 独立双层 renderer”
更贴合长期方向。

### 6.3 Apple 公开研究不能直接变成 App 依赖

- [VIVID](https://machinelearning.apple.com/research/pixel-space-diffusion-models)使用 pixel-space diffusion
  端到端做 novel view synthesis，直接学习保持源视图、移动内容并生成不可见区域。
- [SHARP](https://machinelearning.apple.com/research/sharp-monocular-view)一次前向预测 metric 3D
  Gaussians，可实时渲染邻近视点。

两者都说明高质量空间体验的上限不是“单深度图 + 单张 RGB warp”。SHARP 的预测可运行于
CPU/CUDA/MPS，但官方轨迹渲染目前仍要求 CUDA；checkpoint 的 HTTP `Content-Length` 为
2,809,738,232 字节，也没有 Android/ONNX 交付物。更关键的是
[SHARP 模型许可](https://github.com/apple/ml-sharp/blob/main/LICENSE_MODEL)明确把
product development 和商业产品排除在 Research Purposes 之外。它只能用于技术判断，不能下载后
集成、转换后发布或上传阿里云。

Apple 对已发布 Spatial Scenes 的公开描述只说明结合 generative AI、computational depth 和 multiple
perspectives，未公开生产模型与管线，见
[Apple visionOS 26 Newsroom](https://www.apple.com/newsroom/2025/06/visionos-26-introduces-powerful-new-spatial-experiences-for-apple-vision-pro/)。
不能把 Apple 研究论文等同于系统实际实现。

### 6.4 MetaView：最新 diffusion 新视角路线仍远超移动预算

[MetaView](https://github.com/KlingAIResearch/MetaView)于 2026 年 7 月公开，直接按目标相机姿态
生成单张新视角，并用 Depth Anything 3 的隐式几何特征与 metric depth 约束 MM-DiT。它比通用
inpainting 更贴近“大视差且相机可控”的问题，也说明未来模型应显式接收相机与几何条件。

但当前公开推理链路需要：

- 20B 参数、BF16 的 Qwen-Image-Edit 基础模型；
- 1.15B 参数 DA3-GIANT 和另一个 DA3-NESTED-GIANT-LARGE；
- PyTorch、DiffSynth-Studio、Transformers 等桌面推理栈；
- 多步 diffusion，或另行蒸馏的 8-step lightning 模型。

其中 DA3-GIANT 权重为 CC BY-NC 4.0，已经使完整产品依赖链不可用；模型规模也远超本项目约
100 MB 的稳定按需组件目标。MetaView 可以作为未来 teacher 和训练条件设计参考，不能作为当前
Android 可下载模型。

## 七、Spatial Video Effect 的相关路线

逐帧独立补图会造成背景纹理、人物轮廓和遮挡边缘闪烁。静态模型即使每帧固定 seed，也不能保证输入
轻微变化时输出连续。

[M2SVid](https://github.com/google-research/m2svid)把源视频、深度重投影右视图和 disocclusion mask
共同输入视频模型，并专门让被遮挡 token 利用跨帧信息。公开权重约 8.5 GB，测试环境为 A100/H100，
不能上手机，但证明“视频级联合修复”优于逐帧静态修复。

[DreamStereo](https://openaccess.thecvf.com/content/CVPR2026/html/Huang_DreamStereo_Towards_Real-Time_Stereo_Inpainting_for_HD_Videos_CVPR_2026_paper.html)
的三点值得未来采用：

- Gradient-Aware Parallax Warping 让边缘与遮挡带更连续；
- 用双投影构造几何一致的训练对和准确遮罩；
- 只处理稀疏显露 token，避免对整帧运行 diffusion；论文在 A100 上达到 768×1280、25 FPS。

移动版的合理演进是：

1. 先对整段视频估计时序稳定深度；
2. 生成跨帧一致的遮挡拓扑和联合显露带；
3. 以 keyframe 生成隐藏背景，再用光流/特征传播到相邻帧；
4. 只对置信度低的稀疏区域调用生成模型；
5. 最终再训练或蒸馏视频专用模型，而不是每帧完整跑 Moebius。

## 八、候选模型准入矩阵

### 8.1 桌面离线盲测

使用完全相同的输入、遮罩和合成规则比较：

- MI-GAN Places2 512；
- AOT-GAN Places2；
- Big-LaMa Places2；
- Moebius Places2。

素材至少 30 张，覆盖：

- 单人、多人、头发和半透明边缘；
- 栏杆、树枝、网格、车体直线和建筑；
- 文字、屏幕截图、UI 和规则纹理；
- 室内、道路、天空、植被、低光；
- 前景占比很大和显露带很宽的极限视差。

遮罩不能用模型仓库自带随机 mask，应由当前 renderer 在完整允许相机轨迹上生成 union mask。另用
真实双目/相邻视频帧构造一组有 ground truth 的显露区域，避免盲评只有“看起来合理”而不知道是否
保持场景。

### 8.2 质量指标

- 遮罩外像素必须与输入完全一致；若 latent 模型重建整图，只采用最终硬合成后的遮罩内结果；
- 遮罩边界 16～32px 环带的 LPIPS/SSIM 与结构连续性；
- 直线、文字、重复纹理和人物轮廓的人工错误率；
- 左右、上下极限视点与完整 sweep 视频的闪烁/矛盾率；
- 隐藏 RGB 与背景深度的边界一致性；
- 失败时是否产生明显新物体、错误文字或前景复制；
- 固定 seed 的可复现性。

通用 FID 只能作为参考，不能决定空间照片模型排名。

### 8.3 Android 工程门槛

每个候选都必须记录：

- 最终阿里云压缩下载字节数与解压后字节数；
- 代码许可、原始权重许可、转换产物许可和训练数据限制；
- ONNX opset、固定/动态输入、ORT 1.28.0 CPU/NNAPI 算子覆盖；
- session 初始化 P50/P95；
- 单张生成 P50/P95；
- 峰值 PSS/RSS、Java heap、native heap 和 GPU/NPU buffer；
- 连续生成三张后的温度、降频、耗电和取消响应；
- 4/6/8/12 GB 设备的静态和实时内存准入；
- App 进程重启、模型删除/重下、catalog 升级和旧 derivative 兼容。

建议先使用以下实验门槛，不把它们提前写成产品承诺：

| 档位 | 下载目标 | 生成目标 | 峰值内存目标 | 设备策略 |
|---|---:|---:|---:|---|
| 极速/均衡 | 不高于约 100 MB | 旗舰 <1s，中端 <3s | <700 MB | 普遍可选 |
| 生成式实验 | 量化后尽量不高于约 600 MB | 旗舰 <10s | <1.5 GB | 至少 8 GB，总/可用内存双门禁 |

Moebius 官方/第三方 FP32 产物目前不满足下载目标；必须先证明 FP16、INT8 或分块/外存执行不会破坏
画质和取消语义。

## 九、对现有代码和 catalog 的建议

当前 `SpatialInpaintingModel` 把 MI-GAN 作为单一枚举值，后续应改成稳定的模型描述与 provider：

```text
InpaintingModelDescriptor
├── stableId / version / family
├── task = spatial_disocclusion_rgb | spatial_disocclusion_rgbd
├── inputContract = RGB + mask (+ depth + camera)
├── runtime / precision / opset
├── downloadBytes / installedBytes / expectedPeakBytes
├── minTotalMemory / minAvailableMemory
├── license / notice / source hashes
└── qualityTier / experimental
```

产品语义继续沿用已有深度模型规则：

- 多个补图模型可分别下载、删除和选择；
- 当前选择只影响新生成或主动重新生成；
- derivative 保存实际使用的模型 ID、版本、精度和生成 seed；
- 切换或删除模型不改变已有 derivative；
- 生成失败保留旧结果；
- catalog 只声明通过发布验证的能力，不允许 App 根据 family 猜测资源；
- “Flow Matching”“diffusion”“GAN”作为技术 metadata，不直接充当用户质量标签。

对 diffusion/flow 还要增加：

- 多文件模型包与逐文件哈希；
- scheduler、步数、CFG、seed 等不可缺失的执行配置；
- VAE 与主干的兼容版本；
- FP16/INT8 混合精度声明；
- 生成中的逐步取消和临时内存清理。

## 十、建议实施顺序

### P0：先提供单层/双层即时对照

让用户在同一图片、同一强度和同一视点下切换 P0/P1。该开关是判断后续模型与几何改进是否真实有效
的基础，不能只靠记忆比较。

### P1.1：先修双层表示

实现 union disocclusion mask、soft boundary、背景深度一致性与风险自适应视差。否则大模型生成结果
也会被错误几何消费。

### P1.2：执行四模型桌面盲测

先比较 MI-GAN、AOT-GAN、Big-LaMa、Moebius。只下载上游官方权重到开发机，不上传阿里云稳定目录；
输出统一 sweep 和评分表。

### P1.3：Android PoC

- 优先转换 AOT-GAN Places2，验证能否成为均衡档；
- Moebius 只做独立实验分支，先测 FP16/INT8、20 步时延和峰值 PSS；
- 若 AOT 与 LaMa 都不能明显胜过 MI-GAN，则不增加用户模型，只推进任务专用训练。

### P2：训练任务专用 student

以更强 diffusion/Flow Matching 模型作为离线 teacher，用真实或合成的相机轨迹、深度、显露 mask
训练小型 student：

- 第一阶段输出隐藏 RGB + background depth；
- 第二阶段可直接预测 soft layers、shift map 或少量 3D Gaussian；
- 训练损失加入多视点重投影、遮罩外保真和边界一致性；
- 视目标硬件选择单次 GAN/student、4-step diffusion 或 MeanFlow/Rectified Flow。

这个方向最有可能同时满足画质、手机端时延和后续视频复用。

## 十一、最终建议

本次不建议“因为 Flow Matching 更新，就直接把 MI-GAN 换掉”。更稳妥的决定是：

1. MI-GAN 保留为极速档；
2. AOT-GAN Places2 作为近期均衡档第一 PoC；
3. Moebius Places2 作为生成式高质量实验 PoC；
4. Flow Matching/MeanFlow 暂不作为可下载模型，只纳入未来任务专用 student 的训练路线；
5. 在模型对比前先完成 P0/P1 即时切换与 P1.1 几何改进；
6. 所有候选用真实显露遮罩和完整视点 sweep 排名，不按论文通用 FID 或单帧示例直接选择。
