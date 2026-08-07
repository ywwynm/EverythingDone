# 对象中心分层与视差渲染调研（2026-08-01）

> **2026-08-01 模型栈纠错：** 本文关于表示与渲染根因的结论继续有效；其中将
> MobileSAM/RepViT-SAM 作为首个通用分割 PoC、将 MODNet 作为新路线入口的选型已经被
> [空间照片 AI 模型栈重新审计](research-2026-08-01-model-stack-refresh.md) 取代。
> 当前首选 PoC 为 EdgeTAM，MobileSAM2 为等待官方权重/许可的观察项；MODNet 仅作为旧人像
> fallback。本文列出的旧模型和旧设备数字只保留为历史对照，不再代表 2026 最佳方案。

## 结论

用户对当前观感的判断成立：现实现为了压制人物内部形变，把主体内部统一到一个深度，
再在主体外侧铺设占归一化画面半径 `0.12～0.36` 的连续深度过渡；渲染器又把每个顶点的
深度直接转换为位移。结果不是严格保持物体形状的分层运动，而是让主体周围一大片纹理
发生连续、方向相关的局部缩放，因此会呈现「图片贴在曲面上」的观感。

后续不应继续扩大羽化、压低强度或只替换补图模型，而应把最高质量路线改为
**对象中心的 soft-LDI / layered splat**：

1. 图像分割负责像素属于哪个对象或场景区域，以及哪里存在真实遮挡边界；
2. 公制深度负责层序、层间距离和视差幅度；
3. matting/soft mask 只负责轮廓覆盖率，不再把软边界变成宽深度坡；
4. 人物、显著物体和文字等形状敏感区域默认按整体刚性层或拟合平面运动；
5. 墙、地面、桌面等平面区域用平面诱导 homography；不规则远背景才允许低频、受约束的
   深度面形变；
6. 每条真实遮挡边后面持久保存 H1，必要时增加 H2 隐藏层，显露区使用一次生成的补全
   内容；参考视点始终直通原图。

核心原则是：**几何层间可以离散，轮廓 alpha 可以连续；不能反过来用连续几何坡掩盖
硬边界问题。**

## 当前曲面感的代码证据

`SpatialSubjectLayer.applySoftRenderLayer` 当前执行以下操作：

- mask 内部全部拉到同一个 `targetDepth`；
- 以主体为距离场，在主体外侧按 `featherRadius` 把原深度插值到 `targetDepth`；
- `featherRadius` 被限制在画面尺度的 `0.12～0.36`，并以最大视差和 8% 应变预算反推。

对应代码位于：

- `SpatialSubjectLayer.kt:163-230`；
- `SpatialSubjectLayer.kt:353-361`；
- `SpatialRenderDepthStabilizer.kt:184-198`。

`SpatialPhotoRenderer` 的 LDI 顶点着色器执行：

```text
targetUv = aTexCoord - uParallaxMotion * (aDepth - 0.5) - rigidPan
```

因此只要深度在空间上缓慢变化，位移就会随位置缓慢变化。忽略截断时，当前半径公式把
理想深度坡的最大局部应变约束在 `0.09 / (0.09 / 0.08 × 1.5) ≈ 5.3%`；达到
`0.36` 上限后还可能更高。它避免了裂缝，却不能避免局部形状变化，且过渡覆盖的面积很大。
`relief` 片元明暗项会进一步强调曲率，但不是主因。

## 一手资料结论

### 1. 只做连续深度 warp 的失真属于表示问题

[3D Photography using Context-aware Layered Depth Inpainting](https://openaccess.thecvf.com/content_CVPR_2020/html/Shih_3D_Photography_Using_Context-Aware_Layered_Depth_Inpainting_CVPR_2020_paper.html)
明确展示了朴素深度 warp 在显露区会产生空洞或拉伸，并采用带显式像素连接关系的 LDI，
同时补全隐藏的颜色与深度结构。该结论与当前实现完全对应：把断层附近改成更平缓的连续
深度并没有消除错误，只是在「裂缝」和「拉伸/曲面」之间移动。

[Single-View View Synthesis with Multiplane Images](https://openaccess.thecvf.com/content_CVPR_2020/html/Tucker_Single-View_View_Synthesis_With_Multiplane_Images_CVPR_2020_paper.html)
说明 MPI 的价值不只是把深度量化为若干平面，而是让模型在背景层学习前景边缘之后的
隐藏内容，并以相机中心的 RGBA 层表示保持多视点一致性。当前手工 MPI 已被证实存在分桶、
低分辨率和重复合成问题，不能据此否定真正的分层表示。

### 2. 对象中心分层是有直接论文依据的，但必须包含隐藏内容与场景布局

[Object-Driven Multi-Layer Scene Decomposition From a Single Image](https://openaccess.thecvf.com/content_ICCV_2019/html/Dhamo_Object-Driven_Multi-Layer_Scene_Decomposition_From_a_Single_Image_ICCV_2019_paper.html)
从单张 RGB 构建自适应层数的 LDI：先分别补全对象的颜色和深度，同时估计场景布局，再
重建整幅场景；论文报告语义编码尤其改善了被遮挡的中间对象。这直接支持用户提出的
「先分割不同部分，再利用视差」方向，但也说明分割 mask 本身不够，仍需层序、隐藏内容
和重新合成约束。

### 3. soft layering 的“软”应落在覆盖率，而不是大范围深度坡

Google 的 [SLIDE](https://research.google/pubs/slide-single-image-3d-photography-with-soft-layering-and-depth-aware-inpainting/)
指出硬分层难以表达 matting 等复杂外观，因此采用 soft layering 与 depth-aware
inpainting。对本项目的正确映射是：遮挡边两侧拥有独立层和隐藏内容，发丝等边界以预乘
alpha 跨层合成；不是把层间深度差摊到主体外侧 12%～36% 的画面区域。

### 4. 全部区域做正面纸片同样不够，应混合刚性、平面与低频表面

[PlaneRCNN](https://openaccess.thecvf.com/content_CVPR_2019/html/Liu_PlaneRCNN_3D_Plane_Detection_and_Reconstruction_From_a_Single_Image_CVPR_2019_paper.html)
证明单图可以联合预测任意数量平面区域、平面参数和 mask；
[LoLep](https://openaccess.thecvf.com/content/ICCV2023/html/Wang_LoLep_Single-View_View_Synthesis_with_Locally-Learned_Planes_and_Self-Attention_Occlusion_ICCV_2023_paper.html)
进一步使用局部学习平面和遮挡推理改善单图新视角。它们支持混合变换模型：主体按整体
层运动，墙面/地面/桌面按拟合平面投影，不规则背景保留小幅低频几何。这样既避免连续
橡皮面，也避免整幅场景退化成一组正面纸片。

### 5. 学习式 3D 表示是长期上限，但不是当前最可交付的 Android 路线

Apple 研究项目 [SHARP](https://apple.github.io/ml-sharp/) 从单张图片直接回归公制 3D
Gaussian 表示，在标准 GPU 上一次前向推理后可实时渲染附近视点；
[Flash3D](https://github.com/eldar/flash3d) 也采用单图 feed-forward 的分层 Gaussian
表示。它们证明最高质量路线最终可以越过手工深度层，但当前公开实现面向 CUDA/桌面，
既有调研还发现权重、许可和端侧算子/显存均不适合直接并入当前 Android 产品。不能据此
推断 Apple 产品内部实现，只能把它们作为 P2 student 的表示与训练参考。

## 分割模型调研

### MediaPipe Image Segmenter

Google 官方 [Image Segmenter](https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter)
提供 Android 路径和 Pixel 6 基准：SelfieMulticlass 把人物分为背景、头发、身体皮肤、
脸部皮肤、衣服、配饰，CPU/GPU 延迟约 218/71 ms；DeepLab-v3 是固定类别的通用语义
分割，约 124/103 ms。

它们适合做人像先验或轻量语义提示，但不适合作为通用层所有权的唯一来源：

- SelfieMulticlass 只覆盖人物部件；
- DeepLab-v3 类别有限、输入仅 257×257，不能可靠区分同类的多个实例；
- 头发、脸、衣服的语义不同，不代表它们应以不同视差独立移动。

另外，本项目当前推理栈为按需下载的 ONNX Runtime；直接引入 MediaPipe Tasks 会增加
第二套运行时和发布面，收益不足以支持把它定为主方案。

### SAM 系轻量模型

- [MobileSAM](https://github.com/ChaoningZhang/MobileSAM) 用约 5M 参数 TinyViT encoder
  替换 SAM 大 encoder，完整管线约 9.66M 参数，Apache-2.0，官方仓库提供 ONNX 导出；
  与现有 ORT 分发路径最接近。
- [RepViT-SAM](https://github.com/THU-MIG/RepViT/blob/main/sam/README.md) 官方在 iPhone 12
  Core ML 上报告 1024 输入的 encoder 约 48.9 ms、mask decoder 约 11.6 ms，并且零样本
  instance segmentation 优于 MobileSAM；但官方部署路径是 Core ML，Android/ORT 导出、
  算子覆盖和真机内存仍需 PoC。
- [EfficientViT-SAM](https://github.com/mit-han-lab/efficientvit) 为 Apache-2.0，性能/质量
  很强，但官方重点是 GPU/TensorRT 部署，当前也缺少经过验证的 Android ORT 路径。

SAM 的 automatic mask generator 不能直接视为低成本 panoptic 输出。Meta 官方
[实现](https://github.com/facebookresearch/segment-anything/blob/main/segment_anything/automatic_mask_generator.py)
默认在每边采样 32 个点，即最多触发 `32²` 组 prompt，再做稳定性过滤、NMS、裁剪与小
区域处理；返回 mask 列表还可能嵌套或重叠，并不直接提供互斥的像素所有权。

因此推荐的调用方式不是在手机上盲跑完整 `segment everything`，而是：

1. 用已有公制深度断边、RGB 边缘与 superpixel 生成少量候选区域/box/point；
2. MODNet 直接提供人物候选和高分辨率 alpha；
3. 只对不确定候选调用 promptable segmentation decoder；
4. 以深度顺序、mask 稳定性和边界一致性把重叠候选合并为互斥 ownership graph。

首个模型 PoC 改为 EdgeTAM 的 Android ONNX/ORT 转换与少 prompt 路径；MobileSAM2 等待
官方代码、权重和许可。MobileSAM、RepViT-SAM、EfficientViT-SAM 只保留为转换失败时的
Legacy 对照。最终选择仍必须由 Android arm64 的峰值 RSS、ORT 数值一致性、候选数增长
曲线和跨场景 mask 质量决定。

## 推荐表示：Object-centric Soft-LDI

### 数据结构

每个可见节点保存：

```text
LayerNode {
  ownershipMask / softAlpha
  visibleRGB
  representativeMetricDepth
  optionalPlane
  residualDepthLowPass
  transformType
  confidence
}

OcclusionEdge {
  frontLayerId
  backLayerId
  boundary
  hiddenLayerH1
  optionalH2
}
```

场景分区采用 panoptic 思路，但不依赖固定类别：可数对象是 `thing` 节点；天空、墙、地面、
道路、远景等 `stuff` 先由 RGB+深度 superpixel 分区，再按平面拟合和深度连续性合并。

### 每类区域的运动模型

| 区域 | 默认运动 | 原因 |
|---|---|---|
| 人物、脸、文字、显著物体 | 整体刚性平移或单平面 homography | 优先保持轮廓、五官、文字与直线 |
| 墙、地面、桌面、建筑立面 | 深度点 robust 拟合平面后做 homography | 保留透视变化，又不弯曲纹理 |
| 不规则远背景、植被、云等 | 低频深度面，残差受局部应变与直线保护约束 | 避免全部纸片化 |
| 真遮挡边后内容 | 独立 H1/H2，按 z/alpha 前向合成 | 正确处理显露，禁止拉伸前景纹理 |

同一物体内的脸、头发、皮肤、衣服可以作为边界细化或置信度特征，但默认合并为同一运动
父层；只有公制深度、遮挡关系和分割共同证明它们是前后独立表面时才拆层。分割不确定时
宁可合并回父层，也不能制造新的硬接缝。

### 视差增强

空间强度改为控制虚拟相机 baseline。第 `i` 层使用其代表逆深度计算整体视差，层内不再
让每个高频深度像素独立拉动纹理。视觉增强主要来自：

- 前后对象的相对位移；
- 轮廓显露/遮挡的变化；
- 平面透视变化；
- 必要且受约束的低频层内 relief。

这样可以比继续增大全图 warp 更明显，同时显著降低脸、文字、建筑直线的扭曲。

## 为什么 diffusion / Flow Matching 不能先解决这个问题

当前曲面感发生在**可见原图几何**上；MI-GAN、AOT-GAN、diffusion 或 Flow Matching
主要决定新显露区域画什么。即使补图完美，只要可见层仍经过宽深度坡的连续 warp，人物和
背景仍会弯曲。生成模型升级应放在 ownership、拓扑与渲染正确之后，用于改善 H1/H2，
不能替代分层表示。

## 建议实施顺序

### P1-A：不用新模型先验证表示（tracer bullet）

1. 保留现有 MODNet 人物 mask，但删除 `applySoftRenderLayer` 的宽深度羽化；
2. 把人物建立为真正独立的可见层，而不是在共享网格里硬切或改深度；
3. 人物层默认按代表深度整体移动，轮廓使用 MODNet 预乘 alpha；
4. 背后使用现有隐藏背景建立 H1，mask 两侧复制所有权，避免此前硬 cut 切脸的失败；
5. 背景暂保留现有连通深度面；加入 debug A/B：连续曲面 vs 对象分层。

这一阶段可以直接判定用户指出的曲面感是否来自当前宽深度坡，同时不引入新模型变量。

### P1-B：通用 ownership graph

1. 实现 RGB+公制深度 superpixel、区域合并、平面拟合和遮挡图；
2. PoC EdgeTAM 的深度/RGB 候选 prompt 细化，不跑全量高密度 mask grid；MobileSAM2
   在官方 release 后再进入同一准入测试；
3. 增加人物以外的多个物体层、平面 `stuff` 和置信度回退；
4. 分层派生数据持久化，查看时只做轻量合成。

### P1-C：隐藏内容质量

按每条 `OcclusionEdge` 生成 H1/H2，只补实际可能显露的带状区域；比较现有 GAN、少步
diffusion 与 Flow Matching student。生成失败时缩小该边的可用 baseline，不拉伸前景
纹理兜底。

### P2：学习式 layered splat / Gaussian student

以视频相邻帧和多视角数据监督，直接预测 LayerNode、隐藏层或小型 Gaussian；公开
SHARP/Flash3D 仅作 teacher/表示参考。输出仍需是可持久、可实时渲染且能扩展到 Spatial
Video Effect 的一致表示，不能逐视点或逐帧独立生成图片。

## 验收门槛

- 参考视点与普通查看器保持同变换的原图恒等；
- 对每个刚性/平面层拟合预期 similarity/homography，测量最大视点下的 p95 非模型残差；
- 人脸关键点、文字框、建筑直线不得出现方向相关的非刚性变化；
- 按对象统计边界 halo、alpha 跳变、孔洞、重复 over 和显露覆盖率；
- 同一对象环形视点回到中心时闭环一致，不出现层序翻转或 mask 接缝跳动；
- 在 50～100 张 held-out 图片上比较连续曲面与对象分层，两张现有附件只保留为回归探针；
- 记录分割模型、候选数、生成阶段和渲染阶段的峰值 RSS、耗时、派生体积；
- 为后续 Spatial Video Effect 增加层 ID 跨帧稳定性与边界抖动指标，禁止逐帧独立分层。

## 决策建议

采用 **对象中心 soft-LDI + 混合运动模型** 作为下一条 P1 主线。先用现有 MODNet 和隐藏
背景做最小 tracer bullet，证实表示收益；随后引入可按需下载的通用分割模型。分割模型
接口保持可替换，但首版不把未经验证的模型选择直接暴露给用户。只有 Android 端跨场景
PoC 表明两个模型存在稳定、可解释的质量/性能取舍时，再延续深度/补图模型的多模型选择
机制。
