# 更强的新视角生成模型：能否支持更大视角（2026-08-10 调研）

用户判断 24 px@720 不够，并明确"如果有能支持更大视角的方案，优先选更大视角"。
本文只回答一个问题：**有没有能在大视角下生成正确内容、且我们能用的模型。**

## 为什么现有方案卡在小视角

已测清楚（D157/D159）：显露带宽 **恒等于视差**；带里 67.5% 只有 4–8px 宽；
补全内容在**每个尺度上的纹理能量都已与真实背景匹配**（0.80–1.17×）。
也就是说 LaMa 这条线不缺能量，缺的是**正确的结构**——而结构在单张照片里本就不存在。
视差再放大，需要凭空生成的面积按比例增长，纹理续接类模型必然露馅。

要支持更大视角，只能换成**能发明正确结构**的生成式新视角模型。

## 候选与许可（决定性约束）

| 模型 | 许可 | 规模 | 大视角 | 可用性 |
|---|---|---|---|---|
| SHARP（Apple，2025-12） | 研究，**明令禁商用** | 702M | **不行**，论文明确只优化近视角 | ✗ |
| Stable Virtual Camera（Stability） | **非商用** | 1.3B | 可以 | ✗ |
| ViewCrafter（TPAMI 2025） | 研究探索，作者声明非商业产品 | 视频扩散骨干 | 可以 | ✗ |
| One2Scene（ICLR 2026） | **仓库无 LICENSE**（默认保留全部权利） | 降噪模型 19GB，8 卡推理 | 可以 | ✗ |
| **HY-World 2.0（腾讯，2026-04）** | **Apache-2.0** | 权重约 **34GB** | **可以**：单图→可导航 3DGS 世界 | **○ 见下** |
| HunyuanWorld-Mirror（ICML 2026） | 腾讯社区许可，**≤100 万 MAU 可商用** | 未公布 | 几何强、生成弱 | △ |

**结论：能商用又能撑大视角的，目前只有 HY-World 2.0（Apache-2.0）。**
HunyuanWorld-Mirror 是前馈几何重建（点云／深度／相机／法线／3D 高斯一次出），
数秒级，但它解决的是几何——而几何我们已经用 MoGe-2 解决了，它不补内容。

## HY-World 2.0 的代价：上不了端

34GB 权重，端侧不可行（D122 放宽后的预算是 2–3 分钟／张，但那是算力预算不是显存预算）。
因此若要走这条路，架构必须二选一：

- **A. 云端生成资产**：照片上传→云端跑 HY-World→回传第二层资产。视角上限大幅提高，
  但引入隐私与联网依赖，且有服务成本。
- **B. 端侧维持现状、云端可选增强**：默认走 MoGe-2 + LaMa（视差 ~24px，全端侧），
  用户主动选择时才走云端大视角版本。

## 一个必须说清楚的前提

即便换成 HY-World，被遮挡的内容**在原照片里依然不存在**。生成式模型的价值是
"编得像"而不是"编得对"——它给出的是**合理的结构**（枝条继续、桌沿延伸），
而不是被遮住的真实物体。因此大视角下仍会有"内容不对"的情况，只是不再是抹开的糊斑。

## 来源

- Apple SHARP — https://machinelearning.apple.com/research/sharp-monocular-view ／ https://github.com/apple/ml-sharp
- Stable Virtual Camera（非商用许可）— https://huggingface.co/stabilityai/stable-virtual-camera
- ViewCrafter — https://github.com/Drexubery/ViewCrafter ／ https://arxiv.org/abs/2409.02048
- One2Scene（ICLR 2026）— https://github.com/Wang-pengfei/One2Scene ／ https://arxiv.org/pdf/2602.19766
- HY-World 2.0（Apache-2.0，34GB）— https://github.com/Tencent-Hunyuan/HY-World-2.0
- HunyuanWorld-Mirror（社区许可，≤1M MAU 可商用）— https://github.com/Tencent-Hunyuan/HunyuanWorld-Mirror
- HunyuanWorld-1.0 — https://github.com/Tencent-Hunyuan/HunyuanWorld-1.0

## 补充（同日）：深度模型还有没有更强的、能上端的

用户追问 MoGe-2 是否已是最强、深度是否仍是关键。两问分开答。

### 深度已经不是瓶颈

D146 换 MoGe-2 解决的是**扭曲**，那次深度确实是根因。但当前剩余问题是遮挡带里填什么，
而**带宽 ≡ 视差，与深度精度无关**。深度再准，带也不会变窄。更强的深度只能改善两项次要
指标：剪影边界更贴真实边缘（锯齿）、细结构处深度抖动更少（主网格散点 0.448%）。

### 深度模型横向（截至 2026-08）

| 模型 | 与 MoGe-2 比 | 端侧 |
|---|---|---|
| **Depth Pro**（Apple） | **不是升级**：MoGe-2 论文实测边界锐度与其相当，而相对／度量几何显著更好 | ~ViT-L，偏大 |
| **MoGe-3**（2026-07） | 边界 F1 **16.4 vs 15.6（+5%）**、局部点 δ0.01 好 13%；A100 上 ViT-L 121ms / ViT-G 177ms | **只有 ViT-L / ViT-G，无小型变体** |
| **MoGe-2 ViT-S 35M** | 已实测与 ViT-L 几何质量一致（D148） | **可行** |

MoGe-3 自己也写明：回归范式**本质上无法消解物体边界处的像素歧义**，仍有 fly-points。
也就是说边界锐度这条线，换模型只能拿到个位数百分比，且要付出无法上端的代价。

**结论：在"不上云 + 端侧部署"的约束下，MoGe-2（ViT-S/L）实际上就是天花板。**

### 因此在不上云的前提下，可用杠杆只剩

1. **降低视差上限**——唯一确定有效的，带宽随之变窄，补全难度线性下降；
2. **断边内保边平滑**——针对散点 0.448%，与深度抖动直接相关，不需要换模型；
3. 其余（抗锯齿、色阶、分块补全）已做完，收益已兑现。

### 来源

- MoGe-2（NeurIPS 2025）— https://arxiv.org/html/2507.02546v1
- MoGe-3（2026-07）— https://arxiv.org/html/2607.17967 ／ https://qft-333.github.io/moge3page/
- Apple Depth Pro — https://github.com/apple/ml-depth-pro ／ https://machinelearning.apple.com/research/depth-pro
