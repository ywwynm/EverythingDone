# 窄带补全：正确用法与端侧小模型调研（2026-08-12）

背景：D194/D195 回退定案后，用户质疑「Big-LaMa（2022）最适合细带」的结论是没把现代
模型用好。两个调研代理分别核实：①窄带对 latent 扩散的失效机制与正确用法；②≤0.5B
可商用模型重查 + 带体制专门化微调的配方与成本。全部候选按 LICENSE/论文原文核实。

## 1. 「没用好」的部分成立：亚 latent 像素问题是实锤

- 我们的带局部宽度中位 4.4–16.8px（540×720），经 f8 VAE 下采样后只有 **0.5–2.1 个
  latent 像素**（1080 档也只有 1–4.3 个）。CVPR 2026《Your Latent Mask is Wrong》
  （arXiv 2512.05198）原文：掩膜缩到 latent 分辨率会丢失细结构，「小于下采样尺度的
  掩膜区可能被直接忽略」；且 latent 线性混合本身不像素等价，会在缝上产生 halo。
  另一篇（2602.14157）：细窄掩膜会因未遮挡区泄漏产生伪影。
- **因此 D160「Moebius/SDXL 整图直喂输给 LaMa」测出的主要是表示分辨率差异，不是
  模型代差**；该结论适用范围限定为「整图直喂」用法。
- **但假设只证明了「整图直喂是错用」**：crop-zoom 之后扩散能否反超 LaMa，没有任何
  公开量化证据，必须自测。文献里不存在「带宽 vs latent 像素」失效阈值曲线——我们的
  评测台可以产出这个空白数据。

## 2. 正确用法：crop-zoom-inpaint-paste（公认修法，缺量化）

diffusers 官方 `padding_mask_crop`、A1111「仅重绘蒙版区」、ComfyUI Crop-and-Stitch
都是同一协议：沿掩膜裁窗（上下文边距 32–64px 起，带状掩膜按带宽 4–8 倍给）、放大到
模型原生输入（使带占 ≥4 个 latent 像素）、补全后**像素域**贴回（羽化 4–16px，绝不做
latent 融合）。掩膜预处理两类模型方向相反：**准确掩膜下 LaMa 不膨胀**（我们实测
膨胀伤 PSNR 22→18 与社区认知自洽——别人膨胀是为盖住分割误差）；**扩散喂模型时膨胀
1–2 个 latent 像素，羽化只在贴回阶段做**。Moebius 实操：CFG 从 2.0 起测 {1,2,3}，
固定 5 种子取中位；VAE 一次编解码立即贴回。

**公平对照协议**（23 步完整版在调研代理报告中，要点）：整图直喂 / 仅裁 / 裁+放大
三档 × 单视角窄带 / 目标视角包围 两档 × 双模型；同一裁窗生成器、同一 zoom、同一
贴回；指标限定「带 + 外扩 2px 环」（带内 PSNR/L1/LPIPS + 渲染侧缝落差为主判据）；
结论分档陈述不得跨档外推。**裁窗必须做前景侧屏蔽**——经典 DIBR 规则：补 disocclusion
只许用背景侧像素，「四面包围」里有一面是遮挡物，不屏蔽则放大会放大污染。

## 3. 端侧小模型重查：赛道确认空置，但有一条实测有效的路

- **≤0.5B 可商用补全模型 2023 年后无新作**（权威 curated list 2025–2026 条目全是
  diffusion/text-guided，无一 lightweight）。「LaMa 仍最优」不是检索失误，是赛道空置。
- MAT（research-only）、RETHINED（无权重无 LICENSE，只能借思想）、CoordFill（慢且
  许可不明）排除；像素域小扩散在 inpainting 上 2026 年是空的；带 edge/depth 引导
  通道的现成小模型不存在——**引导通道只能自己加**。
- LaMa 无任何高分辨率重训 checkpoint；高分辨率侧的现成手段是 **Feature Refinement**
  （CVPRW 2022，推理期特征优化，不重训，生成期烘焙可直接用于 1080+ 档）。
- Moebius 链路核实：teacher = PixelHacker 0.862B（同 hustvl，Apache-2.0）、VAE =
  SDXL VAE，**不碰 FLUX**；GitHub LICENSE Apache-2.0 与 HF 标签 MIT 不一致（都宽松，
  建议请上游澄清）。

## 4. 本轮最重要的发现：GRT——训练掩膜分布匹配，同模型 +3.77dB

**GRT（Geometric Reciprocity，arXiv 2607.05354，2026-07）**：合成所需的 disocclusion
掩膜 = 从目标反向 warp 回源时丢失的像素集合，只用深度即可解析算出（boundary
violation + depth occlusion 两个条件，缺一不可）；训练目标就是「挖掉带、原图当 GT」。
**同一个 LaMa 27M 架构、同样推理开销，仅换训练掩膜分布：PSNR 31.75→35.52
（+3.77dB）、LPIPS 0.0239→0.0129（−46%）**。训练成本 2×V100、30 epochs、256²、
batch 32。论文称将以 Apache-2.0 放出，**仓库当前 404**——配方完整可自复现。

这与 LaMa 原文 Table 4 的定律一致（训练掩膜分布要匹配测试分布——我们的测试分布
是 4–17px 窄带，恰是 LaMa 训练分布最稀疏的一档），也与五篇先例同构（Shih 2020 /
SLIDE / GRT / StereoCrafter / M2SVid 全部用 depth warp 造掩膜 + 原图当 GT）。
**我们比它们全部占优：渲染器本身就产出精确显露带掩膜，分布零误差匹配。**

**训练语料许可陷阱**：Places2/ImageNet 条款均为 non-commercial research only——
big-lama/MI-GAN/Moebius 现有权重全训在 Places2 上（既有第三方权重的上游数据问题
留档待法务口径，业界普遍灰区）；**自训语料必须换 Open Images（CC BY）/ COCO
（CC BY 4.0）/ 自有照片**，不得复用 Places2/ImageNet。

## 5. 定案建议（按优先级）

1. **GRT 式 LaMa 微调**（工单任务六主项）：渲染器 dump 精确带掩膜 + 原图 GT +
   混入随机笔画掩膜（SLIDE 的遗忘对冲），Open Images/COCO/自有照片语料，256² /
   batch 32 / 20–40 epochs，单 5090 约 1–3 天；评测用带真值台（带内 PSNR/LPIPS/
   边缘梯度）。ONNX 路径已有先例（Carve 的 FourierUnitJIT 替换 fft_rfftn，输出
   逐位一致；输入固定 512 配 tiling）。窄带专用与通用 checkpoint 并存。
2. **crop-zoom 公平对照**：按第 2 节协议重跑，修正 D160 结论适用范围并产出失效
   阈值曲线；若扩散档反超，必须补端侧耗时（N 窗 × 20 步）与 ONNX 可行性评估才算数。
3. **观察项**：DecFormer（7.7M 头，修 latent 合成的定点方案，边缘误差最多 −53%，
   仅在 Moebius 保留档位时值得复现）；MI-GAN（5.95M，MIT，官方 ONNX）底座 +
   深度/结构引导通道的从零小网络——**必须等第 1 项出了 baseline 再启动**（不同时
   动两个变量）。

## 6. 关键来源

- Your Latent Mask is Wrong（2512.05198，CVPR 2026）；Test-Time Guidance
  （2602.14157）；Blended Latent Diffusion（2206.02779）；BrushNet（2403.06976）。
- diffusers inpaint 文档（padding_mask_crop / apply_overlay）；
  ComfyUI-Inpaint-CropAndStitch；Patch-Adapter（2510.13419）。
- **GRT（2607.05354）**；Shih 2020（2004.04727，MIT）；One Shot 3D Photography
  （2008.12298）；SLIDE（2109.01068）；Feature Refinement（2206.13644）。
- LaMa（2109.07161，Apache-2.0 已核）；MI-GAN（MIT 已核，官方 ONNX）；
  Moebius（Apache-2.0/HF MIT 不一致；teacher PixelHacker Apache-2.0）；
  Carve/LaMa-ONNX；Places2/Open Images/COCO 条款。
