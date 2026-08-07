# 空间照片内容扭曲优化调研（2026-07-31）

## 结论

当前可见扭曲首先是**单层连续逆向 UV 重投影的表示限制**，不是单纯换一个深度模型就能解决：

- 当前着色器按 `sampleUv = baseUv + motion * (renderDepth - 0.5)` 反查原图；
- 原始深度断层会造成 UV 折返、重复切片；现有 `SpatialRenderDepthStabilizer` 把断层摊成斜率不超过
  0.02 的连续坡面，消除了折返，却必然让坡面覆盖的原图内容发生局部拉伸和压缩；
- 过扫描只能避免画面外露，不能修复主体形状；继续收紧全局 Lipschitz 上限只会把同一位移差摊到
  更宽区域；
- 因而“明显前后景视差、单张颜色纹理、连续一一映射、无空洞、无形变”不能同时满足。要保住主体
  轮廓，必须允许深度边界断开，并为新显露的背景提供另一层内容。

推荐下一代主路径是**不新增推理模型的 LDI-lite / 软分层渲染**：RGB 引导的深度边界、深度断层处
切断几何、背景局部扩展、前景软 alpha、按深度合成。短期可先增加方向相关的形变预算来限幅，但它
只能缓解，不能替代分层。

进一步对照 Apple 公开资料后，若目标从“比当前效果更稳”提高到“接近 Apple Spatial Scenes”，则
P1 已经是必要条件，而且最终大概率还需要生成式背景补全。Apple 对 visionOS 26 的公开表述是使用
“generative AI algorithm and computational depth”生成多个视角；RealityKit 资料则把结果描述为
有真实深度、可随观察者移动产生运动视差的 3D 图像。当前 App 的单层深度 warp 缺少显式遮挡拓扑和
隐藏背景内容，质量上限不在同一层级。

**LDI-lite 与 inpainting 不是二选一**：

- LDI-lite / 软分层是场景表示、遮挡关系和实时渲染架构；
- inpainting 是在该表示中填充新显露背景颜色与深度的生成手段；
- 直接给现有单层逆向 warp 接一个通用补图模型，仍不能阻止前景跨深度边界拉伸，也不能建立正确的
  前后遮挡和软边界。

## 本地证据

使用此前从 OnePlus 极限视点问题中保存的真实 1200×900 原图与 ZipDepth derivative：

- 旧强视差算法左右分别出现 20,075 / 13,973 个水平映射折返点；
- 现有 0.02 Lipschitz 深度把折返点降为 0；
- 但现有最终算法的局部采样步长仍为正常步长的约 0.586～1.414 倍，说明局部缩放变化仍很大。

此外，片元着色器还按原始深度梯度施加最多 ±4% 的视点相关明暗变化。它不改变 UV，但在人脸、文字和
高对比轮廓上可能加强“表面起伏”的主观感受，应与几何扭曲分开做开关式对照。

## 原始资料结论

1. Apple 在 visionOS 26 的官方发布资料中说明 Spatial Scenes 使用生成式 AI 与计算深度创建多个
   视角，使用户可以探身查看；RealityKit 的 WWDC25 资料称其为从 2D 图片生成的、有真实深度和
   motion parallax 的 3D 图像。这说明以 Apple 当前效果为参照时，不能只比较深度模型或单层 warp。
   [Apple Newsroom](https://www.apple.com/newsroom/2025/06/visionos-26-introduces-powerful-new-spatial-experiences-for-apple-vision-pro/)；
   [WWDC25：What’s new in RealityKit](https://developer.apple.com/videos/play/wwdc2025/287/)。
2. DIBR 研究早已观察到：为隐藏 disocclusion 而平滑深度，会带来几何扭曲和深度质量下降；方向与
   深度断层自适应的平滑只能减少变形，并不能恢复被遮挡内容。
   [Discontinuity-adaptive Depth Map Filtering](https://eudl.eu/doi/10.4108/icst.immerscom2009.6284)
3. CVPR 2020 的 3D Photo Inpainting 使用带显式像素连接关系的 Layered Depth Image，并补全被遮挡
   的颜色与深度；生成后可以用普通图形引擎高效产生运动视差。这直接说明应在表示层解决遮挡，而不是
   继续让一张纹理跨深度边界拉伸。
   [论文页](https://openaccess.thecvf.com/content_CVPR_2020/html/Shih_3D_Photography_Using_Context-Aware_Layered_Depth_Inpainting_CVPR_2020_paper.html)；
   [官方项目](https://shihmengli.github.io/3D-Photo-Inpainting/)。
4. 3D Ken Burns 同样先修正分割与物体边界，再把图片映射为点云；对相机路径的极端视点统一补全颜色
   和深度，以保持几何与时间一致。这支持“生成时准备背景、交互时只渲染”的本机架构。
   [官方项目](https://sniklaus.com/kenburns)。
5. 硬分层对头发、半透明物等复杂边界也会有问题。SLIDE 使用 soft layering 与 depth-aware
   inpainting 改善这类外观，因此 App 的轻量实现不应只做硬阈值二值蒙版，而应保留一个窄的软 alpha
   过渡带。
   [Google Research：SLIDE](https://research.google/pubs/slide-single-image-3d-photography-with-soft-layering-and-depth-aware-inpainting/)。
6. RGB 引导的 depth upsampling 能让低分辨率深度边界更贴近原图，但若仍使用单层连续逆向 warp，
   更锐利的深度跳变反而更容易折返。它必须与“切断边界并分层合成”一起使用。
   [Joint Bilateral Upsampling](https://www.microsoft.com/en-us/research/publication/joint-bilateral-upsampling/)。
7. 如果规则背景扩展无法通过真实图片验收，MI-GAN 提供面向移动端的官方实现、MIT 许可和完整 ONNX
   pipeline，可以作为可选第三模型。其 512 模型论文报告约 5.98M 参数、15.69 GFLOPs，但仓库明确
   提醒公开 ONNX pipeline 的端到端耗时包含额外预处理与后处理，不能直接套用论文的纯模型时延。
   它还只接收颜色图与二值 mask，并不会自动生成与 LDI 一致的隐藏背景深度，因此不能未经专项适配
   就直接接入。
   [MI-GAN 官方仓库](https://github.com/Picsart-AI-Research/MI-GAN)。

## 方案比较

| 方案 | 形变 | 空洞/补图 | 本机成本 | 结论 |
|---|---:|---:|---:|---|
| 降低全局视差或继续收紧 Lipschitz | 缓解但仍存在 | 无显式空洞 | 很低 | 只适合作为短期安全阀 |
| 方向相关的断层限幅与 Jacobian 预算 | 可明确设上限 | 无显式空洞 | 低 | 推荐先落地，用于所有渲染路径 |
| 深度边界切断的规则网格/点 splat | 主体形状明显更稳 | 会暴露背景空洞 | 中 | 需要与背景层一起实现 |
| LDI-lite：背景扩展 + 前景软层 + 深度合成 | 低 | 小范围规则补全 | 中 | 当前约束下的推荐主路径 |
| 完整 LDI / MPI / 神经补图 | 最佳上限较高 | 可生成隐藏内容 | 高 | 只在真实 corpus 证明必要后评估 |

## 推荐实施顺序

### P0：形变预算与可分离对照

- 由最终位移场计算方向相关 Jacobian/采样步长，超过预算时只衰减当前视点分量，不再只依赖固定的
  全局深度斜率；
- 在现有真实 corpus 上先以 0.8～1.25 倍正常采样步长作为实验门槛，而不是直接写死为产品常量；
- 单独对照关闭、减半当前 ±4% relief，判断用户感知中几何和明暗各占多少；
- 保持折返点为 0，并保留用户逐图强度滑杆。

这一步不改变 derivative schema，能较快降低极端视点风险，但困难图片上的有效视差会被压低。

### P1：LDI-lite / 软分层 derivative

1. 生成阶段用原图边缘引导深度上采样，建立深度断层与连接关系；
2. 在断层处切断规则网格三角形，禁止跨前景/背景插值；
3. 只对最大相对位移可能显露的窄带扩展背景颜色与深度，先使用确定性传播/修补；
4. 前景边界使用窄软 alpha，减少头发、细线和半透明边缘的硬剪纸感；
5. 交互时做前向投影、深度测试和分层合成，模型不再逐帧运行；
6. 为背景补全定义独立接口和 mask/depth 合同，但首个里程碑先用确定性传播验证表示与渲染器；
7. derivative schema 升级并保留旧结果兼容；旧 derivative 可继续走 P0，用户主动重做后获得 P1。

### P1b：生成式背景补全

如果验收目标明确为接近 Apple Spatial Scenes，应把生成式背景补全列入 P1 的第二里程碑，而不是
继续当成遥远的可选项；但仍要等 P1a 的遮挡 mask、层连接关系和隐藏背景深度合同稳定后接入。候选
模型只处理最大视差会暴露的窄背景带，在生成阶段执行一次并缓存；交互渲染不得运行模型。

MI-GAN 可作为第一候选，但需要用本项目真实 corpus 单独验证 ONNX Android 延迟、峰值内存、模型
包大小、窄带补图质量、错误内容风险，以及如何生成保守且连续的背景深度。未通过前不把它承诺为
固定第三模型。

## 验收指标

- 所有测试方向的 UV/几何折返点为 0；
- 报告局部采样步长或网格面积 Jacobian 的分位数，不只统计“是否折返”；
- 分别统计深度边界附近的拉伸、空洞占比、背景补全带宽和帧时间；
- 原图、P0、P1 以相同视点录屏盲评，重点覆盖人脸、文字、头发、栏杆、车体直线和屏幕截图；
- 在低端 GPU 上验证 VBO/纹理峰值、首次进入耗时和持续帧时间，再决定网格密度与派生纹理上限。
