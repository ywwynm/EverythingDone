# 补全该怎么用：中心一次，还是多视角补了再融合（2026-08-11 调研）

用户提问："这种补全到底怎么用比较好——比如是不是在多个角度下都进行补全然后通过某种
方式融合？总之就是为了实现很好的空间效果，看看怎么综合利用这些 AI 模型。"

## 文献确实是这么做的

单图生成 3D 场景的主流范式就是**迭代 warp-and-inpaint**：变换到新视角 → 在那里补洞
→ 融回 3D 表示 → 换下一个视角重复。

| 工作 | 年份 | 做法 |
|---|---|---|
| Text2Room | 2023 | 迭代生成网格，补全 + 单目深度 |
| LucidDreamer | 2024 | warp-and-refine 迭代生成点云，再转 3DGS |
| WonderJourney | 2024 | 深度对齐 + 同视差物体归平面 + 天空深度修正 |
| Reangle-A-Video | 2025 | **顺序**补全各 warp 视角，后一个以前一个为条件保持一致 |
| IMFine | CVPR 2025 | 把补好的参考视角 warp 到其它视角，作为多视角精修网络的条件 |
| PAInpainter | ICCV 2025 | 透视图采样 + 补全内容传播 + 一致性校验，迭代优化 3DGS |
| DiGA3D | 2025 | 在 2D 补全器的潜空间里把注意力特征从参考视角传播到其它视角 |

## 已知代价

综述口径一致：**"由于 warp-and-inpaint 范式固有的几何误差累积，Text2Room 与
LucidDreamer 在视角间无法保持一致，大幅相机移动时尤其明显"**；且**"逐场景优化代价
高昂、跨视角几何不一致"**。后来那批工作（Reangle / IMFine / PAInpainter / DiGA3D）
做的全部都是**给这个不一致打补丁**。

## 我们不做，理由是两条前提都不成立

1. **相机移动是小幅圆周**，不是场景漫游。累积误差这个问题本来就不存在，
   而它正是那套迭代范式要解决的主要矛盾。
2. **我们的补全器是回归模型，不是生成模型。** 这一条是决定性的：实测 Big-LaMa 在
   未遮挡区的还原是 **138 dB／平均 0.00 级误差**（Moebius 这类潜空间扩散是
   26.66 dB／7.39 级，D160）。回归模型的输出完全由上下文决定，**中心视角补一次**
   放进 LDI 第二层，所有视角看到的就是同一份内容，天然一致，不需要任何跨视角约束。

引入多视角只会把那个已知的不一致失效模式带进来——在我们的场景里它会表现为**倾斜时
补全区域闪烁**，而这比"内容有点糊"糟糕得多。

## 那"综合利用 AI 模型"还能怎么用

当前管线里每个模型各司其职，且都已用真值或消融验证过：

| 环节 | 用什么 | 依据 |
|---|---|---|
| 度量几何 | MoGe-2（ViT-L，端上可换 ViT-S） | D146/D148；D147 用户验收 |
| 遮挡带掩膜 | 无模型，由几何 + 最大基线解析算出 | 带宽自动等于各处实际视差 |
| 带内颜色 | **Big-LaMa 512 分块 1:1** | D160 带真值评测，胜过 Moebius／SDXL／AOT-GAN／Telea |
| 带内深度 | 无模型，背景层级最小值传播 | Shih et al. CVPR 2020 的口径 |
| 色阶 | 无模型，全局标量偏置 | D159；局部参照的三种取法均已否决 |

**唯一还有增量的模型组合是"细节移植"**：LaMa 结构对但偏糊（HF 0.78），Moebius 纹理
能量对但结构错（HF 1.05），取 LaMa 低频 + Moebius 高频 ×0.5，HF 提到 0.94 只花
0.43 dB——而普通锐化冲到 1.19 却要花 1.46 dB，说明 Moebius 的高频**携带真实信息**。
代价是端上要跑两个模型，暂列为可选项。

## 来源

- Reangle-A-Video（2025）— https://arxiv.org/pdf/2503.09151
- IMFine（CVPR 2025）— https://arxiv.org/html/2503.04501
  ／ https://openaccess.thecvf.com/content/CVPR2025/papers/Shi_IMFine_3D_Inpainting_via_Geometry-guided_Multi-view_Refinement_CVPR_2025_paper.pdf
- PAInpainter（ICCV 2025）— https://openaccess.thecvf.com/content/ICCV2025/papers/Cheng_Perspective-aware_3D_Gaussian_Inpainting_with_Multi-view_Consistency_ICCV_2025_paper.pdf
- DiGA3D（2025）— https://arxiv.org/pdf/2507.00429
- ObjFiller-3D（视频扩散做多视角一致 3D 补全）— https://arxiv.org/pdf/2508.18271
- WarpGAN（2025）— https://arxiv.org/abs/2511.08178
- Invisible Stitch（warp-and-inpaint 的误差累积分析）— https://www.robots.ox.ac.uk/~vedaldi/assets/pubs/engstler25invisible.pdf
- PanoDreamer（2025）— https://arxiv.org/html/2504.05152
