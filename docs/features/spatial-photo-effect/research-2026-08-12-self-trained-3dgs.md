# 自训「单图 → 分层 3DGS」（SHARP 类）可行性调研与判断（2026-08-12）

用户提问：现有「深度估计 + 分割 + 补全」管线难以达到高质量，能否自己训练一个 SHARP 类
前馈模型（单图生成 3DGS），只在手机上跑（生成不要求实时），质量能否达到苹果空间照片
级别。调研由四个并行代理执行（方法全景／SHARP 深挖／可商用训练数据盘点／端侧推理与
渲染），另有一份 teacher 蒸馏核实子报告；全部候选按「打开 LICENSE/论文原文核实」执行。
本文为交叉汇总与判断。

## 0. 结论（四句话）

1. **能做，且比 2026-08-11 调研时的判断更可行**：当时认定的最大瓶颈（可商用训练数据）
   已被本轮核实翻案——通用场景数据不缺甚至过剩，伪多视角监督的充分性有硬数字支撑。
2. **怎么做**：不复刻 SHARP 全尺寸（128×A100 量级），做**降配版**——ViT-S/B 骨干 +
   768 输入 1:1 高斯网格 + 两层 pixel-aligned + SHARP 论文的完整损失清单；数据用
   MegaSynth（MIT）+ GEN3C 伪多视角 + CC-BY 真实单图自监督。本地 5090 迭代配方，
   最终一轮租 8–16×H100（2–4 天，$2K–8K）。
3. **质量预期要换标尺**：出货的 iOS 26 Spatial Scenes **大概率不是 SHARP**（时间线
   不符、官方描述为多层+陀螺仪差速，更接近我们现有分层路线）；对齐出货产品的观感是
   现实目标，追平 SHARP 研究原型的全部长尾稳健性不现实也不必要。
4. **端侧全链可行**：生成（ViT-S 档 CPU 17–25s、峰值内存 <1GB、权重约 200MB fp32）、
   渲染（两层 pixel-aligned 可**免排序**，估 50–140 FPS@Adreno 740，SH 0 阶在 4cm
   基线下可证明无损）、资产（图像平面打包 1.5–3MB/张，勿用逐高斯格式的 17MB/张）。

## 1. 三大前提的核查结果

### 1.1 许可与代码

- **SHARP 权重与蒸馏产物明确不可商用**（Apple ML Research Model License 原文：
  "exclusively for Research Purposes"，且 Model Derivatives 同受限）——不能当 teacher。
  内部研究性评测（跑我们的九场景看质量上界，不进产品、不蒸馏、不发布输出）通常属
  研究范畴，但属灰区，由用户裁定是否执行。
- **SHARP 代码许可两位代理读出分歧**（一判可商用，一读出 "personal, non-exclusive" +
  无专利授予的非 OSI 措辞）。**采保守口径：训练环路与模型代码自己写**，官方仓库只当
  论文的可执行注释读（架构思想不受版权保护，逐行复制代码则回避）。仓库本就只有推理
  代码，训练环路横竖要自建。
- 骨干替换：**Depth Pro 权重许可 GitHub 与 HF 冲突，跳过不押注**。可商用替代：
  MoGe-2 全系（代码+权重 MIT，已在本项目 D146 验证过质量）、DA3 的
  SMALL/BASE/METRIC-LARGE/MONO-LARGE 四档（Apache，以 GitHub model zoo 表为准）、
  DINOv2（Apache，2023-08 起）。带 GS head 的 DA3-GIANT 档是 NC，不可用。
- 复现参照系：社区**不存在 SHARP 开源复现**；唯一架构级复刻 UniSHARP（Insta360，
  2026-06，训练代码全开）从代码到骨干（UniK3D）全线 CC BY-NC，只能读不能抄。
  Flash3D 仓库无 LICENSE 文件 + UniDepth NC 依赖，同样不可用。

### 1.2 数据（对 08-11 判断的翻案）

**通用场景：够，甚至过剩。**
- **MegaSynth**（MIT，代码与数据均是）：70 万程序化场景 × 48 视角、4.04TB **已渲好
  可直接下载**——与 SHARP stage-1 的 70 万场景同量级。论文自证纯 MegaSynth 训练与
  真实数据训练相当（低层 3D 重建对语义真实感不敏感）。
- 补充：Infinigen（BSD-3，100% 程序化零外部资产，输出归我们）、uCO3D（Meta 2025，
  **CC BY 4.0**，17 万物体级视频）、MegaScenes CC-BY/PD 子集（≤43 万场景）、
  Objaverse 可商用子集（约 74.5 万物体）。
- **stage-2 不需要多视角**（SHARP 的第二阶段是单图自监督闭环：渲染伪新视角→反向
  当输入→原图当监督）：CommonCatalog-C（1460 万张 CC-BY Flickr 日常照片，域最贴
  相册）+ PD12M。规模是 SHARP 真实数据的 5.5 倍。
- **人物**：学术人体数据全线 NC（SMPL 系明文禁商用）。但按 SHARP 结构人物泛化靠
  stage-2 自监督覆盖，**不必买扫描数据**；若需精确人物几何先验，Ten24 SP-6M（仅头部，
  7632 人、585 万多视角原图，consent+GDPR 合规）与 Renderpeople HumanDataset（全身，
  ML 训练专项授权）可议价采购。宠物是唯一数据真空白，同样落在自监督射程内。
- **明确不可用**（逐字核实）：RealEstate10K（CC-BY 只覆盖相机轨迹 txt；帧是 YouTube
  内容，平台条款禁抓取——Flash3D 的原始配方不能照抄）、DL3DV/CO3D/MVImgNet/
  ScanNet++/Matterport3D/ZInD/CoP3D/Aria 全系（NC 或学术限定）、Hypersim（CC-BY-SA
  传染 + 上游 Evermotion 商业资产双层风险，采保守口径避开；反正 MegaSynth 更干净）。
- **管线硬编码合规红线**：排除一切 NC/SA/ND；pose 工具只用 COLMAP/GLOMAP（BSD）、
  MapAnything Apache 版、VGGT-1B-Commercial；CC-BY 的署名义务以应用内数据来源链接
  满足。

**teacher 链（全部逐字核实）：**
- **GEN3C-Cosmos-7B**（NVIDIA Open Model License）：图 + 相机位姿 → 跨帧一致视频
  （720×1080、121 帧），**许可明文允许用模型或其输出训练/改进其它 AI 模型**，输出
  归属明确放弃。伪多视角主力。
- **Lyra 1.0**（同许可，可商用）：图+位姿→直接出 3DGS `.ply`，可当几何软标签。
  **Lyra 2.0 权重是 internal research only，不可用**——二手报道称 Apache 是错的。
- 配套：MoGe-2（MIT）几何伪真值、MapAnything-Apache 补尺度、VGGT-1B-Commercial
  交叉校验压 floater、Qwen-Image-Layered（Apache）出 RGBA 分层与被遮挡背景、
  SAM 3（无输出限制条款）出实例、Matrix-3D（MIT）小规模补充（A800 约 1 小时/场景，
  只配补充不配主力）。
- **HunyuanWorld 全系从白名单剔除**（修正 08-11 表述）：许可原文禁止用其输出改进
  任何其它 AI 模型，且 100 万 MAU 门槛 + 欧盟/英国/韩国地域排除。
- **伪多视角监督的充分性有硬数字**：Lyra（NeurIPS 2025）的 3DGS 解码器**纯用视频
  扩散生成数据训练**，在 RealEstate10K 真实基准上反超真实数据训练的 Bolt3D；消融
  「纯生成 24.77 PSNR vs 纯真实 19.08」，加真实数据不涨点。最坏下界（LRM-Zero，
  零真实先验）落后 1.6–2.2 dB。已知风险：几何 teacher 的 floater 会被学生继承，
  须在损失里显式加深度一致性与置信度加权。

### 1.3 算力

- 全尺寸复刻：SHARP 原文 128×A100×100K 步 + 32×A100×60K 步，估 4K–12K A100 小时
  ≈ 云上 $4K–25K（中位 $10K）；单张 5090 约 1–3 年，不可行。
- **降配可行区**：768 输入（4× 削减）+ 数据 1/10 + ViT-S/B 骨干 → 约 2–3K 5090
  小时 ≈ 单卡 1–3 个月；推荐混合节奏：本地 5090 小分辨率迭代配方（几天一轮），
  定案后租 8–16×H100 跑 2–4 天（$2K–8K）。
- 关键工程事实：SHARP 的性能主要来自配方而非数据量——感知损失（LaMa 的高感受野
  ResNet-50 版）单项改善 DISTS 62%，损失清单与权重论文全给了；数据规模消融论文
  没做，**必须自己做（1/30、1/10、1/3 三档）作为 go/no-go**。
- 我们的域比 SHARP 窄一个量级（4cm vs 60cm 设计域；语料 720 长边 vs 1536+；显露带
  重算后中位仅 7.2px、p90 22.8px），域收窄可折算成算力/数据的富余。

## 2. 端侧全链（数字与推理链见端侧报告）

- **生成**：SHARP 原架构（双 ViT 1536²，6.85 TMACs）在 S23 Ultra CPU 上 6–10 分钟
  且峰值内存 3.5–4GB，不可行；**保留其 36 次 384² 分块设计 + 骨干降 ViT-S** →
  0.535 TMACs，**CPU 17–25s、峰值 <1GB、权重约 200MB fp32**，无需 NPU 即达标。
  ViT-B 档 64–100s（须 fp16）为质量上限档。分块设计必须保留（1536² 全局注意力的
  二次项是 30 倍开销）。
- **渲染**：两层 pixel-aligned 高斯在 4cm 基线下**排序可结构性删除**（layer 0 恒在
  layer 1 前，两趟固定序 back-to-front 是精确解非近似）；SH 降 0 阶可证明无损（4.6°
  视向变化对应 <1% 颜色变化）；估 50–140 FPS@1080p on Adreno 740（保守外推 20–35）。
  退路：平滑区退化为两张位移纹理网格 + 断崖处保留约 10 万真高斯。层内自遮挡残余
  可用跨帧复用的桶排序处理（视锥不变，桶归属近静态）。
- **资产**：勿用逐高斯格式（SPZ 也要 16–19MB/张）；**像素对齐结构直接走图像平面
  打包**（x,y 由网格隐含、12 通道属性图用 AVIF/WebP/PNG-16 编码，全部系统解码能力，
  零第三方依赖）→ **1.5–3MB/张**。千张相册 = 1.5–3GB，必须按需生成（与现有产品
  语义一致：用户主动生成、持久派生、可删除）。
- 前例：手机上跑通前馈高斯的公开记录只有 Meta SqueezeMe（Quest 3 avatar，同代
  Adreno 740）；「单图→高斯前馈重建」的端侧落地我们会是第一个。

## 3. 质量判断：能不能到「苹果空间照片级别」

**标尺校准**：无任何证据表明出货的 iOS 26 Spatial Scenes 是 SHARP（产品 2025-09
出货、论文 2025-12 发表；官方描述为前景/中景/背景多层 + 陀螺仪差速；第三方也把两者
当不同东西）。出货产品更接近我们现有分层路线的完成度形态；SHARP 是自由度更高的研究
原型，且第三方实测其代价是细节损失与离原视角的幻觉。

**有利因素**：
1. 域窄：4cm 基线在 SHARP 设计域（<0.5m）内侧 10 倍处；显露带重算后 p90 仅 22.8px
   ——学生要幻觉的是 7–23px 条带，不是大面积场景。
2. 几何骨干已验证：MoGe-2 正是当前管线里用户唯一验收通过的几何来源（D146–D148）。
3. 显露区监督可以比 SHARP 更强：SHARP 只靠隐式 perceptual loss，我们有 GEN3C 伪
   多视角 + Qwen-Image-Layered 的 amodal 层 + 仓库既有 masked keyview 基础设施，
   可做显式显露区监督。
4. 现有九场景圆周协议 + 四项并列硬门禁直接迁移为训练验收（全表工作无一做过
   in-the-wild 用户研究，我们的目检矩阵仍是唯一可信验收）。

**风险（按严重度）**：
1. 发丝：SHARP 的合成数据含真实 groom 数字人，我们没有等价资产；缓解 = stage-2
   真实照片自监督 + 剪影处用现有 matting 栈做 α 监督；若不足，Ten24/Renderpeople
   采购是后手。
2. 深度长尾：SHARP 自陈失败全部根因于深度（微距/夜空/水面反射），我们的小骨干只会
   更弱；缓解 = 域内（相册常见内容）优先、困难场景靠逐图安全包络收紧（现有机制）。
3. floater 继承与训练工程复杂度（感知损失显存手术、自建训练环路）——这是本项目
   迄今最大的工程单体。
4. 玻璃/透明：与现管线同为无解难点，不因换路线消失。

**与当前管线的关系**：并行不替代。带结构工单（D183–D187 后续）继续作为出货主线；
学习式路线以周–月为单位推进。两者收敛于同一表示形态（双层 + 软 α + 小基线），
渲染器、资产格式、验收协议全部共用；学生模型一旦达标，替换的是**资产生成期**的
「分割+补全+判定」组合，渲染端几乎不动。

## 4. 建议路线图

**Phase 0（1–2 周，桌面，近零成本）——四个废止实验**：
1. SHARP 官方权重在九场景上做研究性质量上界评测（灰区，是否执行由用户裁定；
   替代方案 = 只看官方 demo 与第三方样例）；
2. TripoSplat（MIT，物体级）在相册照片上跑基线——预期不够用，但零成本排除
   「不用训就有现成的」的可能；
3. GEN3C-Cosmos-7B 在 5090/WSL 上的可运行性与吞吐（决定伪多视角产能与是否租云）；
4. MegaSynth 子集下载 + 数据加载器 + masked keyview 基建复用打通。

**Phase 1（4–8 周，单卡 5090）**：ViT-S 骨干（MoGe-2-S 或 DINOv2-S 初始化）+ 512–768
输入 + 两层 pixel-aligned + SHARP 损失全清单；stage-1 = MegaSynth 子集 + GEN3C 伪
多视角；stage-2 = CommonCatalog-C 自监督。**数据规模消融（1/30、1/10、1/3）作为
go/no-go**；九场景圆周协议对照当前管线。

**Phase 2（决策门）**：学生在九场景上（尤其条带/软边界两项）胜过当前管线 → 租
8–16×H100 跑正式规模（$2K–8K）；否则迭代或停。

**Phase 3（端侧化）**：ONNX 导出、图像平面资产打包、免排序双层渲染器、8–12bit
定点属性；CPU 路径即可达标，NPU 留作后续优化。

**预算与周期**：Phase 0–1 近零现金成本（电费+时间）；Phase 2 现金 $2K–8K；
可选人物数据采购（数万–数十万人民币）默认推迟。拿到可辩护结论的总周期约 2–4 个月。

## 5. 需用户裁定

1. 是否启动 Phase 0（四个实验都便宜，建议直接做；其中 SHARP 权重评测的灰区单独裁定）；
2. 当前带结构工单是否继续并行（建议继续，它是出货主线）；
3. Phase 2 云预算授权时点（等 Phase 1 消融数据）。

## 6. 修正与跟踪

- **修正 08-11 调研**：HunyuanWorld 全系不可作蒸馏 teacher（当时表述有误）；
  「数据许可是自训最大瓶颈」对通用场景不成立，瓶颈换位为算力与工程。
- Lyra 2.0 权重不可商用（1.0 可），二手 Apache 说法是错的。
- 证据缺口：Infinigen 单卡吞吐、SOG 压缩代码许可（疑继承 Inria NC）、TRELLIS.2
  许可、DL3DV 书面澄清、GEN3C 在 32GB 上的实际可运行性（Phase 0-3 回答）。
- 挂号：MoGe-3 权重、MLGS 代码、CompleteSplat 代码、EfficientSAM3。

## 7. 关键来源

- SHARP：arXiv 2512.10685；github.com/apple/ml-sharp（LICENSE 与 LICENSE_MODEL
  原文已核）；issues #77/#80/#83/#90。
- 方法：UniSHARP（2606.07514，NC）；InfiniSplat（2608.02437，Apache 代码）；
  LGTM（2603.25745）；Mobile-GS（2603.11531）；Flux-GS（2606.30017）；
  SqueezeMe（2412.15171）；Texture3dgs（2511.16298）；Sort-free WSR（2410.18931）。
- 数据：MegaSynth（hwjiang1510/MegaSynth，MIT）；uCO3D（facebookresearch/uco3d，
  CC BY 4.0）；CommonCatalog-C；PD12M；Objaverse-XL（ODC-By + 逐物体）；
  Infinigen（BSD-3）；RealEstate10K 条款；Ten24 SP-6M；Renderpeople HumanDataset。
- teacher：GEN3C-Cosmos-7B 与 NVIDIA Open Model License；nvidia/Lyra 与 Lyra-2.0
  model card；Qwen-Image-Layered；SAM 3 License；HunyuanWorld LICENSE 原文；
  Lyra 论文（2509.19296）伪数据消融；VFusion3D（2403.12034）；LRM-Zero（2406.09371）。
- 端侧：Phase Matters（2606.27906，8 Elite CPU/GPU/NPU 实测）；Qualcomm AI Hub；
  Niantic SPZ（MIT）；Self-Organizing Gaussians；3DGS Compression Survey。
