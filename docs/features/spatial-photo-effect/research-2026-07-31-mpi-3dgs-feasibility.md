# 小型 MPI 与单图 3DGS 可行性调研

日期：2026-07-31。触发：用户要求在碎屑治理（D47）后评估长期项「小型 MPI / 单图 3DGS」
能否落地。结论先行：**小型 MPI 可以完全在本项目内落地，不需要任何新模型与新许可，
建议作为 P2 渲染路径做 PoC；单图 3DGS 当前全部候选被许可或领域错配挡死，列入观察，
不启动**。

## 1. 单图 3DGS：候选逐一核查

| 候选 | 阻断点 |
|---|---|
| Apple SHARP | 权重许可仅限非商业科研、明确排除 product development（D41 已核查）；约 2.81 GB。 |
| Flash3D（Oxford VGG） | 依赖 UniDepth 做度量深度骨干；**UniDepth 代码与权重均为 CC BY-NC 4.0，禁止商用**（仓库许可节原文："This software is released under Creatives Common BY-NC 4.0 license."）。Flash3D 仓库本身未见 LICENSE 文件（默认保留所有权利）；训练域为 RealEstate10K（室内房产视频），对通用照片泛化未证。 |
| Splatter Image | 面向单物体重建（ShapeNet/CO3D 域），与整景照片场景错配。 |

即使许可可解，还有两道端侧门槛：预测网络体积（UniDepth 级骨干远超现有四模型之和）与
GLES2 上的高斯泼溅渲染（排序 + 实例化混合，帧时间未知）。与此前 diffusion/Flow
Matching 调研同一结论模式：没有同时满足许可、领域、体积、端侧资源的候选，不上架
名不副实的选项。可行路径只剩「自训任务专用 student」（架构可参考 SHARP/Flash3D 的
分层高斯思想，不使用其权重），这是一个需要数据集与训练算力预算的独立项目，不在
App 侧会话内启动。

## 2. 小型 MPI：可以在本项目内落地

关键认识：MPI 需要的两种原料——**逐像素深度**与**被遮挡处的背景内容**——恰好是
Spatial Photo Derivative v2 已经持久保存的东西（surfaceDepth + 补全后的背景板）。
因此小型 MPI 不需要任何新模型，本质是**现有派生数据的第三种渲染表示**：

- 构建：把 surfaceDepth 软切成 N=8–12 层前平行平面（soft slicing，深度隶属度做
  1–2 层宽的羽化 alpha），背景板作为最远层；可在进入空间模式时在 CPU 构建
  （600×450×12 层量级，几十毫秒）。
- 渲染：由远及近逐层 alpha 混合、逐层按层深做视差平移；层内可保留现有单层 UV 位移
  作层内连续视差（每层位移量在该层深度邻域内变化），缓解纯 MPI 的层间「纸板感」。
- 直接收益：**软 alpha 边缘天然解决 D47 后残留的头发/柔性轮廓锯齿**——这正是当前
  唯一的主要观感缺陷；层间显露由背景层承接，无网格拉伸。
- 成本量级：纹理内存 ≈ N × 600×450 RGBA ≈ 13 MB（N=12）；填充率 ≈ 12 层全屏混合
  ≈ 1.5 GPix/s @60fps@1080p，Adreno 730/840 均有余量；构建一次性数十毫秒。
- 风险：连续斜面的深度量化分层（cardboarding）——靠层内位移与 N 的选择缓解；
  半透明物体仍超出范围。

## 3. 建议

1. **启动小型 MPI PoC**（1–2 个会话）：debug 构建加第三种渲染模式（P2），从现有 v2
   派生数据渲染时构建 MPI，不改 schema；在 OnePlus 上与 P1 做同图 A/B。验收门槛：
   头发/柔性边缘观感优于 P1、帧时间 ≤ P1 的 1.5 倍、峰值显存增量 ≤ 40 MB、斜面无
   明显分层。通过后再决定是否转正（含 P0/P1/P2 的产品呈现方式）。
2. **单图 3DGS 列入观察**：出现「许可可商用 + 整景域 + 端侧可承受」的 feed-forward
   模型时重启评估；自训 student 作为独立立项讨论，不混入本特性。

## Sources

- [UniDepth（许可节：CC BY-NC 4.0）](https://github.com/lpiccinelli-eth/UniDepth)
- [Flash3D 仓库](https://github.com/eldar/flash3d) 与 [论文](https://arxiv.org/abs/2406.04343)
- [Splatter Image](https://arxiv.org/abs/2312.13150)
- [Sharp Monocular View Synthesis in Less Than a Second](https://arxiv.org/html/2512.10685v1)
- [Single-View View Synthesis with Multiplane Images（Tucker & Snavely）](https://single-view-mpi.github.io/single_view_mpi.pdf)
- [AdaMPI, SIGGRAPH 2022](https://yxuhan.github.io/AdaMPI/)

## 附录：2026-08-01 技术栈刷新核查

- **深度估计升级候选（可商用）**：[DA3-SMALL](https://huggingface.co/depth-anything/DA3-SMALL)
  （0.08B，Apache-2.0；DA3-LARGE/GIANT 为 CC BY-NC 4.0 不可用）与
  [MoGe / MoGe-2](https://github.com/microsoft/MoGe)（MIT，DINOv2 部分 Apache-2.0，
  CVPR'25，主打锐利边缘与度量尺度）。深度边缘锐度是当前生成质量的最大杠杆（断边
  判定、rim、MPI 分层全都吃它），建议按既有 PoC 门槛（导出、数值、体积、真机耗时/
  内存、许可再分发）评估作为第三深度模型。
- **matting/分割候选（软边界模型部分）**：[MODNet](https://github.com/ZHKKKe/MODNet)
  （宽松许可，实时人像 matting，移动端友好）、[BiRefNet](https://github.com/ZhengPeng7/BiRefNet)
  （MIT，通用高精度分割，较重）；RMBG-2.0 权重仅限非商用，排除。alpha matte 与深度
  断边融合可同时服务 LDI 边缘羽化与 MPI 的 alpha 层。
- **单图 3DGS 复查**：无新的「可商用 + 整景 + 端侧」候选，维持正文结论。
- **背景补全**：显露带窄，MI-GAN/AOT-GAN 仍够用；黑块问题已证明与补图质量无关
  （D48）。暂不换模型。
