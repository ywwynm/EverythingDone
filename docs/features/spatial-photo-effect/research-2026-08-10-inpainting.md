# 遮挡补全：2026-08-10 调研

背景：MoGe-2 度量几何 + 真实重投影通过用户验收（D147）后，唯一剩下的画质问题是遮挡空洞
（占画面 1.4–2.9%）。D149 我用了"邻域最远像素取色"的屏幕空间填充，用户指出大视差下明显
不行，且反问"补全模型不就是干这个的吗"。本文是重新调研的结果。

## 一、补全应该放在管线的哪一层（这是我 D149 做错的地方）

文献口径一致，且与项目历史上的 plate 思路一致：**在中心视角补全一次，同时补颜色和深度，
把补出来的内容作为第二层放进 3D 表示**，之后每个视角只是渲染它。

- 经典工作：Shih et al., *3D Photography using Context-aware Layered Depth Inpainting*
  （CVPR 2020）——以 LDI 为表示，"iteratively synthesizes new local **color-and-depth**
  content into the occluded region"；
- 深度那一半的规则很明确：**"disocclusion regions on the depth image are filled with the
  background depth levels to preserve the depth structure"**；
- 且必须排除前景：**"excluding foreground information from both source regions and target
  patches"**——与 D137 膜校正参照环、D149 背景优先取色踩到的是同一条：**参照不能取到被测
  对象自己**。

**D149 的错误**：只在屏幕空间补颜色、没有深度，因此
1. 补出来的内容不在 3D 里，无法随视角一致地被遮挡／显露；
2. 视差越大空洞越宽，邻域取色只能拉伸背景，必然糊——用户观察到的正是这个；
3. 每帧重算，时间上也不稳定。

## 二、补全模型的取舍（纠正我上一条的错误提法）

| 模型 | 许可 | 规模 | 位置 |
|---|---|---|---|
| AOT-GAN / Big-LaMa / **MI-GAN** | MIT 类 | 小 | **端上早就有了**，同一类小型 GAN 补全，画质最弱 |
| SDXL-inpainting（当前桌面） | OpenRAIL++ | ~2.6B UNet | D132 起在用，权重已在本地缓存 |
| **FLUX.2 Klein** | **Apache-2.0** | **4B / 9B** | 比 SDXL **快约 10×**；画质排序 Klein 9B > Klein 4B > SDXL |

上一条我把 MI-GAN 当成新发现是错的：它和 AOT-GAN 是同一类东西，端上已有，不是升级。
真正值得考虑的升级是 **FLUX.2 Klein——画质更好、速度更快、许可更宽松（Apache-2.0
优于 OpenRAIL++）**。

## 三、端侧假设需要更新

项目此前默认"端侧只能退回 GAN 补全"。2026 年的实际情况：

- Snapdragon NPU 上跑 Stable Diffusion，旗舰机 **512²、20 步约 5–10 秒／张**；
  SDXL 级模型在 **8 Gen 3 及以后**受支持；
- 而 D122 已经把端侧预算放宽到 **2–3 分钟／张**。

**因此扩散级补全在端上是可行的**，不必默认降级到 AOT-GAN。具体要看用户两台测试机的芯片
（三星 + OPPO，型号待确认）。

## 四、一个量级判断，可能比选模型更重要

我们的遮挡带**很窄**——宽度等于视差，实测占画面 1.4–2.9%，而且形状是沿深度断崖的细条。
这与"抠掉整个人再补"是完全不同难度的任务：细条补全对任何一个上述模型都不算难。

所以**先把架构改对（颜色+深度、进第二层），再谈换模型**。架构不对的时候，换多好的补全
模型都会被"没有深度、每帧重算"抵消；架构对了之后，模型之间的差别才谈得上有没有必要。

## 五、建议顺序

1. **改架构**：在中心视角算出遮挡带 → 补颜色 → **补深度（取背景深度层级，源区排除前景）**
   → 作为第二层点云导出 → 查看器双层 z-buffer 渲染。补全模型先继续用已缓存、已验证过
   光度问题的 SDXL（D132/D137/D138）。
2. **再 A/B 模型**：同一批掩膜上比 SDXL 与 FLUX.2 Klein，变量单一，结论干净。
3. **端侧重估**：确认两台测试机芯片，实测扩散补全端上耗时；AOT-GAN 不再是默认天花板。

## 来源

- Shih et al., *3D Photography using Context-aware Layered Depth Inpainting* (CVPR 2020)
  — https://shihmengli.github.io/3D-Photo-Inpainting/
  论文 PDF — https://openaccess.thecvf.com/content_CVPR_2020/papers/Shih_3D_Photography_Using_Context-Aware_Layered_Depth_Inpainting_CVPR_2020_paper.pdf
- *A Disocclusion Inpainting Framework for Depth-Based View Synthesis*（IEEE TPAMI）
  — https://ieeexplore.ieee.org/document/8642935/
- *Virtual view synthesis using layered depth image generation and depth-based inpainting*
  — https://www.sciencedirect.com/science/article/abs/pii/S1047320316000535
- MI-GAN（ICCV 2023，MIT）— https://github.com/Picsart-AI-Research/MI-GAN
- LaMa — https://github.com/advimman/lama
- FLUX.2 Klein 与 SDXL 对比 — https://www.apatero.com/blog/flux-2-klein-vs-sdxl-vs-flux-dev
- 开源图像模型许可与规模综述 — https://www.thundercompute.com/blog/best-open-source-image-generation-models
- 端侧 Stable Diffusion（Snapdragon NPU 延迟）— https://github.com/xororz/local-dream
- Grokipedia: Stable Diffusion on Android — https://grokipedia.com/page/Stable_Diffusion_on_Android

## 补充（同日）：比 LaMa 更好的补全模型——要找的是"延续结构"而不是"更清晰"

用户追问是否有比 LaMa 更好的补全模型。先厘清病症：**不是补得不准，是补得没有结构。**
实测补全内容在**每个尺度上的纹理能量都已与真实背景匹配**（0.80–1.17×），缺的是形状——
枝条、椅背横档、窗框在带里断掉，变成抹开的续接。因此要找的是**结构感知**类补全，
不是更高分辨率或更强纹理的模型。

| 候选 | 许可 | 与我们病症的对位 |
|---|---|---|
| **ZITS / ZITS++**（CVPR 2022 / TPAMI 2023） | **Apache-2.0**，权重已放出（Places2，256／512） | **最对位**：先用 Transformer 恢复**边与线**，再补全；论文明确说对"门、窗、画框这类由边界图样构成的室内场景"有效——正是本测试图 |
| MAT | — | 面向大掩膜，我们的带很窄，不对位 |
| BrushNet（扩散） | — | 扩散在细散掩膜上已实测失败（D151） |
| FLUX.2 Klein | Apache-2.0 | 同属扩散，先验上会重复 D151 的问题，但**未实测**，我的否决是先验不是测量 |
| PixelHacker | 待查 | 本机 HF 缓存里已有，可低成本一试 |
| MI-GAN / AOT-GAN | MIT 类 | 与 LaMa 同级或更弱，端上已有 |

**ZITS 的代价**：需要额外跑一个线框检测器 LSM-HAWP（源自 HAWP，CVPR 2020）作为前置步骤，
其上游许可需单独核实；仓库是 PyTorch 1.9 时代，权重挂 Google Drive，搭环境可能有摩擦。

**必须先说清的上限**：ZITS 恢复的是**合理的**线，不是被遮住的真实物体。但"一条直线继续
延伸过去"恰恰是现在最缺的，比抹开的糊斑好得多。

### 计划

沿用既有 A/B 台架（`build_hidden_layer.py --backend` + 打标输出 + 查看器切换），
在**同一批掩膜、同一套几何**上加测 ZITS，必要时再加 FLUX.2 Klein 与 PixelHacker。
变量单一，与 D151 的 A/B 可直接对照。

### 来源

- ZITS（CVPR 2022，Apache-2.0）— https://github.com/DQiaole/ZITS_inpainting ／ https://arxiv.org/pdf/2203.00867
- ZITS++（TPAMI 2023）— https://dl.acm.org/doi/abs/10.1109/TPAMI.2023.3280222
- EdgeConnect（结构引导补全的早期代表）— https://openaccess.thecvf.com/content_ICCVW_2019/papers/AIM/Nazeri_EdgeConnect_Structure_Guided_Image_Inpainting_using_Edge_Prediction_ICCVW_2019_paper.pdf

## 补充二（同日）：撤回"LaMa 是端侧天花板"——2026 年的端侧生图这条线

用户指出前两轮调研全是 2022 年的模型（LaMa 2021/WACV 2022、ZITS 2022），并判断
"这么多手机生图的需求，肯定有参数量没那么大、能在手机上跑、效果又比 big-lama 好的模型"。
**这个判断是对的，我此前的检索词全在学术 inpainting 清单里，没有搜端侧生成这条线。**

### 撤回

- 撤回"在'细带 + 端侧 + 可商用'这个格子里，2026 年没有比 LaMa 更新的东西"；
- 撤回把 ZITS（2022）列为"最对位"候选——它已被下表整体超越；
- 撤回"要上更好的补全就得上 FLUX.2 Klein（4B/9B，端上跑不了）"这个二选一。

### 2026 年的实际候选

| 模型 | 年份 | 参数 | 许可 | Places2 test FID↓ / LPIPS↓ | 位置 |
|---|---|---|---|---|---|
| **Moebius** | **ECCV 2026** | **0.226B** | **MIT（权重）/ Apache-2.0（代码）** | **9.48 / 0.207** | **首选**，华科 + **vivo AI Lab** |
| PixelHacker（Moebius 的教师） | 2025-05 | 0.862B | MIT | 8.59 / 0.203 | 质量略高，参数 3.8× |
| FLUX.1-Fill-Dev | — | 11.9B | 非商用 | 8.02 / 0.279 | 不可用；LPIPS 反而最差 |
| SD3.5 Large-Inpainting | — | 3.02B | — | — | 8.05 s／张 |
| MAT | 2022 | — | — | 9.27 / 0.211 | — |
| PowerPaint | ECCV 2024 | — | — | 17.91 / 0.223 | — |
| **Big-LaMa（当前在用）** | 2021 | 0.05B | 是 | **21.07 / 0.213** | **FID 是 Moebius 的 2.2 倍** |
| BlazeEdit | 2026-05，Google | 0.195B | **未放权重** | — | Pixel 10 Edge TPU **290 ms**／含物体移除 |
| RETHINED | 2025-03 | 0.0043B | — | — | 2048² 下 34 ms，但属 CNN+patch，是更快的 LaMa 不是更好的 |

小掩膜设定下 Moebius 0.92 FID / 0.091 LPIPS，PixelHacker 0.82 / 0.088。

### 为什么 Moebius 值得直接上

1. **量级对**：0.226B、0.154 TFLOPs，512² 单步 26.01 ms（L40S），20 步共 0.52 s；
   对比 FLUX.1-Fill-Dev 8.05 s，>15×。D122 端侧预算 2–3 分钟／张，余量充足。
2. **出身对**：第二作者单位是 **vivo AI Lab**——就是冲着手机端做的。
   BlazeEdit（Google，195M，Pixel 10 上 290 ms）从另一侧印证：这个量级的物体移除
   在手机上已经是量产级需求，只是 Google 没放权重。
3. **许可对**：权重 MIT、代码 Apache-2.0，两者都可商用。
4. **结构上没有文本条件通路**（读代码确认，比"不需要提示词"更强）：
   `RemovalModel` 的条件输入是一张 `nn.Embedding(20, 3072)` 学习型码本，
   推理时固定取前 10 项作条件、后 10 项作无条件（CFG），**整个模型没有文本编码器**
   （依赖里也确实没有 CLIP/T5）。这一条直接打在 D151/D157 的病根上——SDXL 那次的
   "凭空生成多余物体"和"色偏"正是文本引导扩散的通病（ASUKA 的论断），
   Moebius 没有这个入口可以被注入。
5. **依赖可控**：学生模型只依赖 diffusers / torch / einops / timm / cv2；
   `flash-linear-attention` 只被 PixelHacker 教师模型 `unet_gla.py` 引用，
   推理时屏蔽 `model_lib/__init__.py` 里那一行即可，**不需要 CUDA JIT、不必进 WSL**。
   现有 `spatialtuning` 环境（torch 2.11+cu128、diffusers 0.39）可直接跑。

### 仍需实测才能定的事

Moebius 的 FID 全部是在**大块自由掩膜**上测的，而我们的掩膜是**沿深度断崖的细带**
（67.5% 宽度 ≤8 px）。D151 已实测 SDXL 在细散掩膜上失败。Moebius 同属扩散，
**先验上有可能重复 D151**——但它无文本条件、且蒸馏自强调"结构与语义一致性"的
PixelHacker，与 SDXL 是不同的赌注。**结论必须来自同一批掩膜、同一套几何的 A/B，
不能靠先验否决**（这正是我上一轮否决 FLUX 时犯的错）。

### 来源

- Moebius（ECCV 2026）— https://arxiv.org/abs/2606.19195 ／ https://arxiv.org/html/2606.19195v1
  ／ https://github.com/hustvl/Moebius ／ https://huggingface.co/hustvl/Moebius
- PixelHacker（2025-05，MIT）— https://github.com/hustvl/PixelHacker ／ https://arxiv.org/abs/2504.20438
- BlazeEdit（Google，2026-05）— https://arxiv.org/html/2605.28067v1
- RETHINED（边缘设备实时高分辨率补全基准）— https://arxiv.org/html/2503.14757 ／ https://crisalixsa.github.io/rethined/
- FlashClear（步蒸馏 + 特征缓存的快速物体移除）— https://arxiv.org/pdf/2605.09003
- InverFill（少步扩散补全的一步反演）— https://arxiv.org/pdf/2603.23463
- Qualcomm AI Hub（端侧模型库，补全项仍只有 LaMa-Dilated）— https://aihub.qualcomm.com/models/lama_dilated
- MobileDiffusion（Google，520M，手机亚秒级）— https://research.google/blog/mobilediffusion-rapid-text-to-image-generation-on-device/
