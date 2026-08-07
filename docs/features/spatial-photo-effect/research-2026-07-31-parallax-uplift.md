# 视差幅度与生成效果提升调研

日期：2026-07-31。触发：用户观察「单层（P0）视角似乎比双层（P1）更大，且整体视角都不够大」，
要求调研更好的方案。本文先用代码把观察量化，再盘点业界做法，最后给分层建议。未改任何代码。

## 1. 现状量化：观察与机制吻合

渲染端的视差链路（[SpatialPhotoRenderer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/spatial/SpatialPhotoRenderer.kt)、
[SpatialWarpBudget.kt](../../../app/src/main/java/com/ywwynm/everythingdone/spatial/SpatialWarpBudget.kt)、
[SpatialRenderDepthStabilizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/spatial/SpatialRenderDepthStabilizer.kt)）：

- 请求幅度 = `0.018 + strength × (0.068 − 0.018)` 个画宽；默认强度 0.72 → 约 5.4% 画宽，
  滑杆拉满 6.8%；另有 1.2% 的刚性平移。
- 形变预算：`|motion| × maxGradientNorm ≤ 0.22`，其中 `maxGradientNorm` 是**整图最坏
  单点**的归一化深度梯度（量化到 8-bit 后 × 网格宽）。
- **P0**：渲染深度先经 stabilizer 钳制相邻格深度差 ≤ 0.020，网格宽 384 →
  `maxGradientNorm ≤ 0.020 × 384 ≈ 7.7` → 实际位移上限 ≈ `0.22 / 7.7 ≈ 2.9% 画宽`。
  即强度滑杆在约 0.2 处就饱和，后 80% 的行程没有增量——「整体视角不够大」的直接机制。
- **P1**：预算只统计「仍连通」的表面梯度（断边豁免），但连通面深度**不经** 0.020 钳制
  （网格 600 宽、mesh 深度带边缘保形处理）。只要存在一处「陡但未判为断边」的过渡带
  （深度上采样、边缘 halo 都会产生），`maxGradientNorm` 就超过 7.7，P1 的上限便低于
  P0——与「单层比双层视角更大」的观察一致。
- 结构性缺陷：预算是**全局标量**，整图被最坏单点钳制；一个坏点就吞掉所有行程。

验证项（下轮真机做）：在 debug 构建打印两条路径的 `maxGradientNorm` 与 `limitMotion`
的 scale，按图对比 P0/P1 实际生效位移（像素数），把上述推断变成实测数字。

## 2. 业界做法

- **Apple iOS 26 Spatial Scenes**（消费级标杆）：端上生成式深度 + 主体/背景分离，
  前景稳定、背景移动的强视差，可做锁屏；无需特殊拍摄模式，iPhone 12 起可用。观感上
  视差幅度明显大于我们目前的 ~3% 画宽。
- **Facebook 3D Photos / Shih et al., CVPR 2020（3D Photography using Context-aware
  Layered Depth Inpainting）**：与我们 P1 同构的 LDI + 显式连通性。关键差异在深度边缘
  处理：先把深度边缘**锐化并对齐**，再在不连续处**切断网格**，背景交给 inpainting；
  没有全局最坏值限幅——局部拉伸靠「边缘处尽量断开 + 背景层承接」消化，连续面内的
  形变自然就小。我们的问题恰是断边判定过窄 + 预算取全局 max。
- **MPI 路线**（Tucker & Snavely 单图 MPI；AdaMPI, SIGGRAPH 2022）：32–64 层 alpha
  合成，天然软边、无网格拉伸；代价是纹理内存与填充率，移动端 GLES 可行但偏重。
  AdaMPI 的视差图边缘预滤波（减少深度不连续处伪影）值得单独借鉴。
- **单图 3D Gaussian Splatting**（Flash3D、Splatter Image；Apple SHARP）：质量上限最高的
  方向。SHARP 许可不可用（见 D41）；Flash3D / Splatter Image 的权重许可、端上算力与
  导出可行性未评估，属 v2 候选。

## 3. 建议路线

**短期（纯参数与判定逻辑，预计一个会话）**

1. 保证 P1 ≥ P0：把「陡而未断」的过渡带并入断边（放宽 GeometryBuilder 的断边阈值），
   或对 P1 连通面梯度施加与 P0 相同的 0.020 邻差钳制。两者取一先做样板。
2. 预算统计从「全局 max」改为高分位（如 P99.5）；配合把 `MAX_DISPLACEMENT_GRADIENT`
   从 0.22 放宽到 0.30–0.35（局部尺度 0.65–1.35，3D photo 类产品常见观感范围）。
3. `MAX_PARALLAX_AMPLITUDE` 0.068 → 0.09–0.10，`OVERSCAN` 0.058 同步加大到 ~0.09
   防止露边。改完按档位算出每档实际位移像素数，再真机目检（视觉验收要算数）。

**中期（对齐 Shih 2020 的边缘处理，数个会话）**

4. mesh 深度做边缘锐化 + 断边对齐：把深度过渡带整体归入断边，让背景层承接显露；
   P0 的 stabilizer 改为保边平滑，使 `maxGradientNorm` 的贡献主要来自会被 P1 豁免的
   真断边。预算此后只约束真正的连续面。

**长期（v2 方向）**

5. 评估 8–16 层小型 MPI 或单图 3DGS 蒸馏 student（延续 D41 的 SHARP-as-teacher 思路）；
   以「更大视角下无拉伸、遮挡显露自然」为验收基准，与现有 LDI-lite 在同一组图上对比。

## Sources

- [MacRumors: iOS 26 Spatial Scenes](https://www.macrumors.com/how-to/ios-3d-lock-screen-effect-spatial-scenes/)
- [TECHi: iOS 26 Spatial Scenes explained](https://www.techi.com/ios-26-spatial-scenes-3d-photo-ai-feature-explained/)
- [3D Photography using Context-aware Layered Depth Inpainting（项目页）](https://shihmengli.github.io/3D-Photo-Inpainting/)
- [同论文 CVPR 2020 PDF](https://openaccess.thecvf.com/content_CVPR_2020/papers/Shih_3D_Photography_Using_Context-Aware_Layered_Depth_Inpainting_CVPR_2020_paper.pdf)
- [AdaMPI, SIGGRAPH 2022](https://yxuhan.github.io/AdaMPI/)
- [Single-View View Synthesis with Multiplane Images（Tucker & Snavely）](https://single-view-mpi.github.io/single_view_mpi.pdf)
- [Flash3D](https://arxiv.org/abs/2406.04343)
- [Sharp Monocular View Synthesis in Less Than a Second](https://arxiv.org/html/2512.10685v1)
