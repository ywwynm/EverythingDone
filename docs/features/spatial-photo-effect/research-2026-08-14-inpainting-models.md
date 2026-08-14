# 调研：遮挡带补全模型（端上可部署，2026-08-14）

委托背景：D252/D253 定案后，用户约束"新且质量硬、手机可部署"寻找 Big-LaMa/MI-GAN 的
替代。联网代理独立调研，检索截止 2026-08-14。以下为报告全文（微幅排版整理）。

## 一句话结论

**答案不是"换更强的通用 inpainting"，而是"用与线上几何完全一致的遮挡 mask 重新训练
小模型"。** ICML 2026 的 GRT（Geometric Reciprocity）给出完整、已验证、Apache-2.0 的
自监督配方：Big-LaMa 在自动生成的遮挡 mask 上微调 30 epoch，DAVIS-GRT PSNR
31.75→35.52（+3.77dB）、LPIPS 0.0129、单帧 0.05s，超过 StereoCrafter 这类十亿级扩散
（28.95 / 0.0445 / 0.6s）。**缺的不是模型容量，是训练分布。**

## 关键诊断：两种失败模式的文献定名

- **带内假头发 = 前景渗漏（foreground bleeding）**，SpatialMe 对 DL 分支的原文病症
  （"tends to extend foreground into edge regions"）。通用 inpainting 的训练 mask 是大块
  自由形状，先验="洞内≈洞周延续"；遮挡带洞周一半是前景，先验直接指向错误答案。
- **挖掉主体后只剩柔和渐变 = 训练/推理 mask 分布不一致**。GRT Appendix A7：遮挡 mask
  "细长、沿深度不连续散布"，且洞内内容与遮挡前景"视觉与语义都截然不同"。伪背景输入
  是训练时从未见过的分布，模型的最优行为就是条件均值。回归（LaMa）与小生成（Moebius）
  退化到同一处——**原因在数据侧不在架构侧**。
- 佐证：GRT Table 4，LaMa+GRT 微调 +3.77dB，而十亿级 StereoCrafter 加同一套数据只
  +1.9dB——数据增益超过架构容量增益。

## 候选对照表（结构续接 × 不抄前景 × 端上可行）

| # | 候选 | 年份 | 参数量 | 许可(代码/权重) | 结构 | 不抄前景 | 端上 | 现成 |
|---|---|---|---|---|---|---|---|---|
| 1 | **LaMa + GRT 微调** | ICML 2026 | 27M(存疑) | Apache-2.0(承诺) | 强 | 强 | 强 | 权重未公开、配方完整 |
| 2 | **MI-GAN + GRT 数据自训** | 2023+2026 | 5.95M | MIT | 中 | 强 | 极强(线上已跑) | 需自训 |
| 3 | SpatialMe 视差扩张(DE) | 2024.12 | 0(前处理) | 未声明 | 中 | 强 | 极强 | 算法已公开 |
| 4 | Shih 2020 context/synthesis | CVPR 2020 | 3×小UNet | MIT | 中 | 强 | 强 | 权重可下载 |
| 5 | RETHINED(NeuralPatchMatch) | WACV 2025 | ~4.3M | CC BY 4.0(论文) | 中偏弱 | 可改造强 | 极强(888=101ms@2048²) | 无代码 |
| 6 | Moebius | ECCV 2026 | 0.22B | Apache-2.0(HF卡写MIT,冲突) | 强 | 弱(实测) | 弱(1.24GB fp32+多步CFG) | 全公开 |
| 7 | PixelHacker(前后景分离嵌入) | 2025.04 | 0.8B | 未核实 | 强 | 中 | 不可行 | 权重公开 |
| 8 | ASUKA(MAE先验) | CVPR 2025 | 后挂 | 未核实 | 中 | 强 | 弱 | 代码公开 |
| 9 | DreamStereo | 2026.04 | 1.3B DiT | 无承诺 | 强 | 强 | 不可行 | 无代码 |
| 10 | M2SVid | 3DV 2026 | ~4.6GB | **Apache-2.0 含权重** | 强 | 强 | 不可行(视频/A100) | 权重已发布 |
| 11 | XPaintNet(Bi-Warp 无网络) | CVPR 2026 | 未公开 | 未公开 | 弱 | 强 | 极强(2K>100FPS) | 无任何发布 |
| 12 | HairGuard/αDepth | 2026 | 未公开 | 无 | 强(发丝) | 强 | 不可行 | 无代码 |
| 13 | StereoCrafter | 2024.12 | SVD 级 | Tencent 定制 | 中 | 中 | 不可行 | 权重公开 |
| 14 | ProPainter | ICCV 2023 | — | **S-Lab 禁商用** | — | — | — | **排除** |

## 前三推荐

### 推荐一（主线）：复现 GRT 配方，微调自己的 LaMa

**不要等 GRT 放代码**（github 404，配方在论文正文+Appendix A3 伪代码完整）。我们的条件
比论文更好：论文用 Depth Anything V2 伪造几何，我们有 MoGe-2 米制深度 + 真实渲染
warp——能做到论文做不到的严格 train-test mask 一致。要点：
- mask 生成：用自己的深度+实际视差档 warp 记录丢失像素；视差按线上分布采样
  （覆盖 6–61px@720，上探 α≈0.12–0.15·W）；
- **输入构造与线上逐字节一致**：4 通道（masked RGB+mask）。"挖主体/伪背景"要么训练时
  同样做、要么两边都不做——这一步不一致本身就足以解释柔和渐变；
- 损失 L1+LPIPS，Adam 1e-4，batch 32，cosine；256² 预训 + 384/512² 收尾；
- GRT 定理前提是**最近邻 DIBR**；我们生成 mask 时须对齐渲染方式（真透视网格）。

### 推荐二：端上不必固守 5.95M——LaMa 45.6M 实测可跑

高通 AI Hub LaMa-Dilated（174MB fp32，512²）：8 Gen 1 = 137ms、8 Gen 3 = 55ms、
8 Elite = 40ms。8 Gen 2 推断 70–100ms/窗，比 5 秒预算低两个数量级。**容量瓶颈解除**
（该 checkpoint 是人脸模型，仅作延迟代理）。LaMa ONNX 导出（FFT 替换）我们已跑通。

### 推荐三（零训练、可立即叠加）：几何前处理

- **视差扩张**（SpatialMe DE）：Canny 找视差边缘 → 向前景侧外扩 → warp 带出更多背景上下文；
- **双投影遮挡判定**（DreamStereo）：目标视角反投回源求 mask + Jacobian 判遮挡，
  平滑带而非碎洞。

## 自训路线可行性（5090 单卡）

数据：单图 → MoGe-2 深度 → 线上视差档 warp → 丢失像素=mask，原图即真值（无需立体
数据）。图源分层：商用干净（Open Images CC BY、Unsplash Lite、自采）；研究用
（Places2/ImageNet，**非商用条款**——商用发布时起点权重与数据都要换干净来源）。
规模：从 big-lama 微调 30–50 万张 × 3–5 epoch 拿大部分增益。时间：快速验证数小时、
主训练约 1 天、完整复现 2–4 天。

## 评测警告（PROVE，ACM MM 2026）

全参考指标奖励"复制粘贴"、无参考指标系统性偏好模糊——正好命中我们两种失败各自的
盲区（与 D202"缝判据失明"同构）。验收组合：PROVE RC-S + 自建"结构接续误差"（带两侧
直线在带内延长线的偏离，把窗棂/椅背量化）+ 前景渗漏率（主体分割反查带内与主体纹理
相似度）做门禁。

## 许可四层核实

| 项目 | 代码 | 权重 | 数据 | 备注 |
|---|---|---|---|---|
| LaMa | Apache-2.0 | 未单独声明 | Places→**非商用** | 数据层是商用阻断点 |
| MI-GAN | MIT | MIT | Places2/FFHQ→非商用 | 训练代码+ONNX 脚本齐全 |
| Moebius | Apache-2.0(仓库) | 同 | 未公开 | HF 卡写 MIT 与仓库冲突，商用前找作者澄清 |
| M2SVid | Apache-2.0 | Apache-2.0 | Ego4D+Stereo4D | 许可最干净的立体补全权重 |
| GRT | Apache-2.0(承诺) | 未发布 | ImageNet/Kinetics→研究用 | 代码 404；Kinetics-GRT mask 已公开(1.1TB) |
| ProPainter | S-Lab **禁商用** | 同 | — | 排除 |

## 不确定项（原报告§九精简）

GRT 代码/权重未公开（数据集已公开）；GRT 写 "Big LaMa-Fourier 27M" 与通行 big-lama
51M 说法不符；RETHINED/XPaintNet/HairGuard/αDepth/DreamStereo 无代码；8 Gen 2 数值
为 Gen1/Gen3 插值推断；Moebius 许可两处冲突。

## 链接（核心）

- GRT: arXiv:2607.05354 · visual-ai.github.io/grt · HF LuJingyi/kinetics400-grt-video-masks
- MI-GAN: github.com/Picsart-AI-Research/MI-GAN
- LaMa: github.com/advimman/lama · HF qualcomm/LaMa-Dilated
- SpatialMe: arXiv:2412.11512 · Shih2020: github.com/vt-vl-lab/3d-photo-inpainting
- RETHINED: arXiv:2503.14757 · Moebius: github.com/hustvl/Moebius · HF simonw/Moebius-ONNX
- M2SVid: github.com/google-research/m2svid · PROVE: github.com/xiaomi-research/prove
- DreamStereo: arXiv:2604.12270 · PixelHacker: arXiv:2504.20438 · ASUKA: yikai-wang.github.io/asuka
