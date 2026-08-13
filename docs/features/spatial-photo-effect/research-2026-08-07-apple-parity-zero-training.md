# 对标 Apple 的零训练优化调研（2026-08-07）

背景：用户对 vNext11 现状不满——相比 Apple 的空间效果空间感不足、可视角度不大、有扭曲畸变，
要求在「推理全部端侧」前提下调研继续优化的路径。本调研按调研独立性规则执行：不读取仓库既有
research 成果文档，四条线并行独立上网检索（Apple 机制、单图→3D 表示模型扫描、端侧部署与
竞品、训练数据与合规），结论只来自公开一手来源与决策/偏好记录。检索日 2026-08-07。

本轮据此形成的决策见 decisions.md D116–D121；本文只记事实与依据。

## 一、Apple 机制结论

### iPhone 端（iOS 26 Spatial Scenes，本轮对标物）

- 官方口径仅有 "a new generative AI algorithm and computational depth"（Apple Newsroom
  2025-06-09）与 "on-device generative AI ... optimized for real-time rendering from
  multiple points of view"（WWDC25 session 317）。无表示/模型细节。
- **可动范围其实有限**：UploadVR（2026-01-11）实测原话 "the degree to which you can move
  in each direction is relatively limited"。
  https://www.uploadvr.com/apple-sharp-open-source-on-device-gaussian-splatting/
- **显露区经常不补内容**：AppleInsider（2025-11-07）"The AI doesn't attempt to fill in the
  area behind objects, so you'll just see a smeared background behind objects."——涂抹拉伸
  而非生成。
- 设备门槛 iPhone 12（A14）起、不需要 Apple Intelligence；生成数秒；派生数据**不持久化**，
  每次查看重新生成（Six Colors 2026-06-02）。
- 推断（已标注）：iPhone 端跑的应是远小于 SHARP 的轻量分层路径；其观感优势来自
  **窄包络内零伪影 + 敢开视差增益 + 边界干净**，而非范围真的大。
- Apple 自己的大角度尝试 iOS 27 Spatial Reframing（WWDC26）公开评测"推远即崩脸"
  （iDropNews 2026-06-26），且疑似经 Private Cloud Compute——大角度端侧干净生成连 Apple
  也未解决。

### Vision Pro 端（质量上界参照）

- Apple Vision Products Group 高级总监 Jeff Norris 经 CNET 采访确认：Personas 与"照片的
  空间 3D 转换"都已使用 **3D Gaussian Splatting**（RadianceFields 转引，2025-10-30）。
- SHARP（arXiv 2512.10685，ICLR 2026）：Depth Pro 双 ViT 主干，**每像素 2 层**共约 118 万
  Gaussian（768²×2，14 维/个，无球谐），总参数 702M；A100 生成 0.91 s、渲染 100+ FPS；
  设计目标是 near-view（约 60 cm 级自然姿态移动），明确不做大幅行走。
- SHARP 训练配方（对未来自研有决定性参考价值）：
  - Stage 1 纯合成全监督：约 70 万场景实例 × 11 视图 = 800 万张渲染图（2000+ 户外与
    5000+ 室内美术场景，含数字人、透明/反射材质），128×A100 100K 步；
  - Stage 2 真实域自监督微调：OpenScene + Shutterstock/Getty/Flickr **商业授权图**
    共 265 万张，伪新视角+输入/目标互换，32×A100 60K 步；
  - 损失含感知项（ResNet-50 + Gram 矩阵）与 BCE alpha；**无 GAN、无外部 teacher**。
- SHARP 权重许可 Apple ML Research Model License（research-only，明确排除商用），2026-08
  无变化；社区 CoreML/mlx 移植全部继承该许可；**无可商用复现**。iw3 维护者拒绝集成的原因
  即"no models compatible with open-source licenses"。

### 结论

用户感知的差距应拆成两层：iPhone 端那种「窄包络高置信」的观感，用现成组件可以逼近；
Vision Pro/SHARP 那种真 Gaussian 资产级自由度，零训练拿不到（见二、五）。

## 二、单图→可渲染 3D 表示模型扫描（2025-01→2026-08）

**核心结论：不存在「现成可商用、≤400 MB、质量达标」的端侧权重。**

| 候选 | 状态（2026-08-07） | 许可 | 判定 |
|---|---|---|---|
| OVIE v1.0（Kyutai，143M / 0.53 GB） | 无 v2/512 版，无社区微调 | HF 标签 MIT（仓库 LICENSE 文件缺失，issue #4 未关） | **唯一可商用正例**，256 分辨率上限不变 |
| InfiniSplat（zju3dv） | 权重仓 2026-08-05 有更新，无小变体 | 代码 Apache-2.0，**权重未标注许可** | 已被真实人物门禁证伪；权重许可不明 |
| Apple SHARP | 无移动/蒸馏版 | research-only | 质量上界，不可用 |
| UniSHARP（Insta360，4.73 GB） | 已发布 | CC BY-NC | 出局 |
| LagerNVS（Meta/Oxford） | 1–10 视图 512，质量强 | FAIR 非商用 | 出局 |
| PixWorld | 权重仍未发布（仓库 4 commits） | 无 LICENSE | 无从评估 |
| FlashWorld（Wan-5B 基座） | 训练代码 Apache | **权重 CC BY-NC-SA** | 出局（其 Apache 训练代码是未来自训权重的合法路径） |
| HunyuanWorld-Mirror | 已发布 | 腾讯社区许可：地域排除 EU/UK/KR、禁止输出训练 | 出局 |
| Gen3R（CVPR 2026） | 单图→视频+深度+位姿+点云；组件约 38 GB | **MIT（代码+权重）** | 端侧不可，**桌面 teacher 头号新发现** |
| Bolt3D / Wonderland / MLGS / SplatDiff | 权重或代码未发布 | — | 不可用 |

深度侧：

- **Depth Anything 3** 许可矩阵（GitHub 官方表）：Apache-2.0 = DA3-SMALL（0.08B）、
  DA3-BASE（0.12B）、DA3METRIC-LARGE（0.35B）、**DA3MONO-LARGE（0.35B，单目专用）**；
  CC BY-NC = DA3-LARGE/GIANT/NESTED 系。**警示：DA3-LARGE-1.1 的 HF Apache 标签是误标**
  （维护者在 HF discussion 确认 NC），且 3D Gaussian/NVS 头只存在于 NC 档。1.1 系
  （2025-12-11）修训练 bug 重训，旧档 deprecated。
- MoGe-3（2026-07-21 论文）权重 "coming soon" 未发布；MoGe-2 为 MIT（其 point map 已被
  D85 实测证伪，MoGe-3 主打 undistorted，权重放出后值得复测）。
- Apple Depth Pro：apple-amlr，商用询问 issue 长期无答复，维持回避。
- **Amodal Depth Anything** DAV2 确定性变体（2024-11）：**MIT**，in-the-wild 相对 amodal
  深度——可商用的被遮挡区深度先验，本轮 B 项评估对象。
- NOVA3R（ICLR 2026，Apache-2.0，权重已发）：含遮挡几何的非像素对齐点云，无外观，
  可作未来监督信号。

## 三、端侧推理与渲染可行性

- **运行时格局**：NNAPI 已在 Android 15 弃用；LiteRT 新增 QNN NPU 加速器（2025-11，
  目标 2026-05 GA）；ExecuTorch 1.0 GA（Hexagon 后端）；ONNX Runtime QNN EP Android
  需自行编译、只接受量化静态图。
- **实测锚点**（Qualcomm AI Hub perf.yaml）：DA3-small（24.7M@518²）8 Gen 2 NPU 约
  119–150 ms、8 Gen 3 54 ms；SD 1.5 INT8 在 8 Gen 2 为 14.42 s/20 步（约 720 ms/步）。
- **143M ViT 类生成模型在 8 Gen 2 的推算区间**：NPU INT8 单次前向约 0.15–0.9 s，8–10 次
  前向合计约 **1.5–9 s**（60 s 预算余量大）；GPU 约 7–40 s；**现有 CPU ORT 路线大概率
  超 60 s**。包体：143M INT8 约 150 MB；0.35B 约 350 MB（贴 400 MB 上限）。
- **渲染**：单照片场景 10–50 万 splat 在 8 Gen 2 可交互（0.74–1.69M splat 实测 60–600
  ms/帧，必须控制规模）；**GLES2 上不了 splat**，需 GLES3（CPU 排序，antimatter15 模式，
  MIT）或 Vulkan compute（GPU radix 排序）；免排序 OIT 路线（Mobile-GS，8 Gen 3 116 FPS）
  论文可复现但官方移动实现未开源。SPZ 格式（MIT）可把资产压到数 MB 级。本轮零训练组合
  暂不需要 splat 渲染器，此节留作未来重启学习式资产时的依据。
- 建议的实测入口：Qualcomm AI Hub 免费设备农场可直接对目标 ONNX 编译+profile 得到
  S23 级真机数字。

## 四、竞品格局（Android 空窗）

- 三星 One UI 8 "Dynamic Effect"：分割 + 双层视差（与我们现状同级），无生成式视角合成。
- Google Photos Cinematic Photos 机制停留在 2021（深度 mesh + 相机轨迹规避拉伸）；
  Android XR 头显侧有 2D→3D 沉浸照片（云/端未明示）。
- Meta 3D Photos 弃养状态；Leia/Immersity 通用手机上的 2D→3D 主要走云端，SDK 面向
  自家光场屏设备；nubia Pad 3D II 证明 8 Gen 2 级芯片可实时做轻量立体合成。
- 华为/小米/荣耀/OPPO 相册端未找到对标功能（检索结论，非存在性证明）。
- **结论：做成即 Android 独一档。**

## 五、训练数据与 teacher 合规（本轮不用，为未来重启保留）

- **合规数据大解锁**：MegaScenes 逐图筛选此前只收 CC0/CC BY 故仅剩 13 场景；Wikimedia
  Commons 政策本身只收允许商用与演绎的自由许可——放宽到"全部 Commons 自由许可 + 逐图
  署名 + SA 合规"可回到 10⁴–10⁵ 场景量级。新增：uCO3D（Meta，17 万段环绕视频，CC BY
  4.0）、WildRGB-D（MIT）、TartanAir V2（CC BY）、Hypersim（CC BY-SA）、Charge（CC BY）。
- **合成引擎栈**（SHARP 配方的合规复刻路径）：Infinigen 全系（BSD-3）、Kubric
  （Apache-2.0）、ProcTHOR（Apache-2.0）+ Poly Haven（CC0，官方明确对 AI 训练友好）+
  BlenderKit CC0 子集 + MakeHuman（CC0 人物）。**避开** Fab（NoAI 标签/UE EULA）与
  Mixamo（Adobe 禁 ML）。
- **teacher 合规首选**：NVIDIA GEN3C-Cosmos-7B 与 Cosmos 系（NVIDIA Open Model License
  **明文允许**用输出训练模型，需署名）；Wan 2.1/2.2 与 Wan2.2-Fun-Control-Camera
  （Apache-2.0，相机位姿可控）；ReCamMaster（MIT）；ViewCrafter（Apache）；Gen3R（MIT，
  单图→视频+深度+位姿，成对数据生成器）。
- **反例清单**（明确禁止输出训练或非商用）：FLUX.1-dev、SD 3.5（社区许可明文禁止用输出
  改进基础模型）、腾讯 Hunyuan 全家（另有 EU/UK/KR 地域排除）、Stable Virtual Camera、
  TrajectoryCrafter、SHARP 权重。
- **人物数据**：学术人体扫描集（THuman 系、MVHumanNet、HuMMan、FaceSynthetics 等）全部
  非商用；RenderPeople 明文禁止用于训练。可商用路径：MakeHuman CC0 自渲、MPI
  （BEDLAM/AGORA）付费商业许可、付费图库授权（Apple 即此路），或用 Apache 系 teacher 从
  合规人像生成伪多视图。
- 悬而未决：DL3DV ToU 原文、LTX-2 条文逐字、"权重是否构成 CC BY-SA 演绎作品"。

## 六、专利警示（非法律意见）

- **Google US12260572B2（SLIDE，2025-03-25 授权，至 2041）**：单图 3D 照片的
  软分层（按深度梯度的连续前景透明度）+ RGB/深度联合单模型补图 + 双层合成——与本项目
  双层可见性 + 窄带补图结构相近，正式发布前应做差异分析并持续跟踪。
- Samsung US11704778：单张高分辨率图的自适应层数 MPI（2023 授权）。
- 未找到能佐证 Apple Spatial Scenes 机制的 Apple 专利公开。

## 七、结论

1. 零训练边界（D120）内不存在"直接换上去"的模型；但 iPhone 端 Apple 效果的实际水平
   （窄包络、显露区常为涂抹）说明差距主要在**包络内的伪影置信度与主体内部体积**，
   而非绝对角度。
2. 由此形成 A+B+C+D 组合（D121）：OVIE 引导运动场（攻主体内部体积与遮挡响应）+
   DA3MONO-LARGE/Amodal 深度升级（降形变门禁误触发）+ 补图重评（显露区质量）+
   包络/强度按 Apple 录屏量化参照重校准。
3. 未来若重启学习式资产路线：SHARP 配方 + 第五节的合规数据/teacher 栈 + 第三节的
   NPU/渲染依据构成完整起点；训练算力边界见 D118。
