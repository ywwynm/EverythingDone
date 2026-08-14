# 调研：遮挡边界"拉伸膜"与深度边不贴轮廓的对策（2026-08-13）

委托背景：用户在 6.4cm 大角度倾斜下追条带伪影。本会话已定案两个几何缺陷
（D250 hidden_z 平滑黏连、D251 第一层发丝混合带），本调研由联网代理独立完成，
用于印证/修正 D251 的"软两层过渡区"修复方向。结论：**方向一致**，并给出
文献级配方与两条增补（前景网格边界改由 α 判据、渲染端拉伸-α 衰减兜底）。
以下为调研报告原文。

---

## 0. 现象与文献术语的对应

| 我们的描述 | 文献术语 | 根因 |
|---|---|---|
| 前景轮廓与补全背景之间的拉伸膜 | rubber-sheet / stretched triangles / flying pixels / ghost contour（DIBR 里叫 boundary noise） | 断崖处的顶点深度介于前后景之间，网格仍把两者连起来；或混合像素（partial pixel）被当成单一深度投影 |
| 补全掩膜边比图像轮廓胖一圈 | depth edge blur / depth halo / texture-depth misalignment | 单目深度在遮挡轮廓处天然过渡平滑，深度边位置系统性偏向前景外侧；掩膜若由深度断崖派生，就继承了这个偏移 |

Google 在 Cinematic Photos 官方博客里把第一条讲得最直白：把 RGB 挤出到深度图上成网格后，
"neighboring points in the mesh can have large depth differences"，虚拟相机一动，
"this will look like the input texture is stretched"。

关键判断：**只按深度差切网格解决不了问题**——过渡带顶点的深度本身就是错的（混合值），
边界位置也是错的（胖一圈）。

## 1. 层边界的深度归属与网格切开

### 1.1 Shih et al., 3D Photography using Context-aware Layered Depth Inpainting (CVPR 2020)

[arXiv:2004.04727](https://arxiv.org/abs/2004.04727)｜[代码](https://github.com/vt-vl-lab/3d-photo-inpainting)

1. **深度锐化**：bilateral median filter，7×7，σ_spatial=4.0，σ_intensity=0.5。
2. **断崖检测**：相邻像素视差差阈值；假阳性用连通分量合并成 linked depth edges，短于 10px 的段丢弃。
3. **断开连通性（核心）**：LDI 逐像素四方向邻居指针，跨断崖处切断 → 前景/背景两组 silhouette 像素；网格在此处不生成三角形。
4. **context region**：从**背景侧** silhouette 沿连通指针 flood-fill 100 次——沿指针走天然不跨断崖，context 绝不混进前景。
5. **synthesis region**：从背景 silhouette 迈进被遮挡区再扩 40 次；**近边额外膨胀 5px，明确写着是补偿深度估计不准**。
6. 补全：edge → color → depth 三个 UNet。
7. **合并回 LDI**：补全像素只与背景侧建立指针，永远不与前景 silhouette 连通。
8. 成网格：只有存在指针的相邻像素才连三角形——rubber sheet 从表示层面消灭。

代码数值（mesh.py）：`tear_edges` 视差差阈值 0.00025；另移除被删边包夹的 dangerous edges；
`remove_dangling` 用最大邻接连通分量的深度均值重赋孤立节点。

### 1.2 Kopf et al., One Shot 3D Photography (SIGGRAPH 2020)

[arXiv:2008.12298](https://arxiv.org/pdf/2008.12298)｜[代码](https://github.com/facebookresearch/one_shot_3d_photography)（端上跑，工程约束最接近我们）

1. **深度边锐化**：加权中值滤波，5×5，核内按视差差高斯加权，σ_disparity=0.2。
2. **连通分量清理**：小于 20px 的分量按接触面并入前景或背景。
3. **LDI 构造**：全连通，除视差差超过 τ_disp=0.05 处。
4. **背景层构造（回答"背景层边界深度怎么定"）**：迭代扩张 LDI——背景侧沿断崖向前景背后长，
   新像素深度取邻居平均，**跑 50 次迭代**，得到 "multi-layered LDI with sufficient overlap
   for displaying it with parallax"。**背景层刻意与前景层在轮廓附近重叠**，任何视角下前景
   边缘背后都有真实几何。（原文没有 "micro-disparity" 术语，实际机制就是这段重叠余量。）
5. 网格生成：texture atlas 域简化网格；深度边前侧像素跨边一段标 unusable，防滤波混面。

### 1.3 通用 DIBR：boundary noise / ghost contour

- 前景边界混合像素被 warp 到新视角，以背景身份落在背景上 = 鬼影轮廓（正是"前景边缘像素被拉进背景"）。
- 标准对策：前景边界一圈从主层剔除不参与 warp（main/boundary layer 分离）。
- 平滑滤波只在背景区做，前景区不动。

## 2. 深度边与图像轮廓对齐（depth edge snapping）

### 2.1 Layered Depth Refinement with Mask Guidance（Adobe, CVPR 2022）——最对口

[arXiv:2206.03048](https://arxiv.org/abs/2206.03048)｜[代码开源](https://github.com/adobe-research/layered-depth-refinement)

1. 用高质量 mask（分割/抠图）把深度按 mask 与 inverse mask **分成两层**；
2. mask 内层 inpainting、外层 outpainting——两层各自把对方区域**补出来**，不在边界上插值；
3. 合并 → 深度边严格贴合 mask；自监督训练（任意 mask + 现成 RGB-D 配对）。

要点：把"深度边贴不贴轮廓"从滤波问题改成**分层外插问题**；边界权威来自 mask，不来自深度。

### 2.2 Depth Pro（Apple）：边界锐度当一等指标

[arXiv:2410.02073](https://arxiv.org/pdf/2410.02073)｜拿 matting/分割 mask 当边界 GT，定义
boundary F1 / recall。**可直接用 BiRefNet matte 当 GT 量化 MoGe-2 深度边偏移**，给膨胀半径
和环带宽度提供依据。

### 2.3–2.6 其它

- SLIDE 的 matting-based visibility：`A' = A · (1 − (M̄ − M)(1 − Ŝ))`，`M̄` 为 max-pool 膨胀
  matte，`(M̄ − M)` 恰是前景轮廓外侧一圈——只在这一圈降可见度。
- Boundary Matting for View Synthesis（Hasinoff 2006）：亚像素边界曲线，联合优化 alpha 与视差。
- **Twin Surface Extrapolation**（[arXiv:2104.02253](https://arxiv.org/pdf/2104.02253), CVPR 2021）：
  遮挡边界逐像素显式建模**前景/背景两个深度假设**，外插而非内插——证明"边界像素只有一个深度"
  这个假设本身是缺陷源。
- 滤波类（joint bilateral / guided filter / bilateral solver、Shih/Kopf 的双边中值）是**锐化**：
  压窄过渡带，**不能纠正边界位置的系统性偏移**。消"胖一圈"必须引入外部边界权威（mask/matte）。
- Google Cinematic Photos：中值滤波 + DeepLab 分割 mask，"masks are used to pull forward
  pixels of the depth map that were incorrectly predicted to be in the background"；
  并且**限制虚拟相机运动幅度**。
- Apple visionOS 2D→spatial：无边界处理细节公开；最接近的公开物是 SHARP（4.1）。

## 3. 软分层 / partial pixel

### 3.1 SLIDE（ICCV 2021 Oral）——与我们的双层网格最可比

[arXiv:2109.01068](https://ar5iv.labs.arxiv.org/html/2109.01068)

原文动机就是 "Stretching artifacts appear at depth discontinuities"，对策是让观者
"see through these discontinuities to the (inpainted) background layer"：

1. 前景软可见度 `A = exp(−β·|∇D|²)`（β 未给值）；
2. matte 增强：`A' = A · (1 − (M̄ − M)(1 − Ŝ))`——治纯深度梯度法对发丝失效；
3. 软 disocclusion 图（tanh 形式，沿水平/垂直扫描线求 max）；
4. **前景与背景各自建三角网格、各自渲染，再 alpha 合成** `I* = A·I_fg + (1−A)·I_bg`；
5. depth-aware inpainting：用"假装前景更大"的 occlusion mask 训练，学会从更远区域借内容。

**核心洞见：拉伸三角形不必删除，变透明即可**——删除留白边，透明化露出背景层。

### 3.2 αDepth（2026-05，Disney/ETH）——把混合像素二义性当第一性问题

[arXiv:2606.00386](https://arxiv.org/html/2606.00386v1)

- 逐像素预测：分层颜色 Ī_FG/Ī_BG、**分层深度 D̄_FG/D̄_BG**、α、软边界掩膜（α_th=0.02）；
- Circular Alpha Representation：α 编码进 sin/cos，消多物体交叠处的 alpha valley；
- 只在软边界内替换，边界外原图原深度原样保留；
- **渲染关键**：前景**预乘 alpha 与 α 一起投影**（softmax splatting），背景单独投影，
  合成 `Ĩ = Ĩ_αFG + (1−α̃)·Ĩ_BG`。**α 必须与前景色联合投影，否则新视角下二者错位**——
  错位正是"轮廓复制/背景渗色"的来源。
- 对比论述：只精修深度但保持单层表示会留走样；单层深度出 "broken edges and flying pixels"。

### 3.3 HairGuard（2026-01，同组）

[arXiv:2601.03362](https://arxiv.org/abs/2601.03362)：gated residual depth fixer 只修软边界；
训练数据按 `α·d_FG + (1−α)·d_BG` 合成；管线 = forward warp → generative scene painter
（补 disocclusion **并消软边界内的冗余背景伪影**）→ color fuser。

### 3.4 3D Moments / 3D Cinemagraphy

特征域 LDI + softmax splatting，避开三角网格连通性问题；逐层 context-aware 颜色+深度补全。

## 4. 2024–2026 新工作（只取边界伪影角度）

- **SHARP**（Apple, 2025-12，[arXiv:2512.10685](https://arxiv.org/abs/2512.10685)，代码开源）：
  单图前馈回归 3DGS，度量尺度，~1s。第三方解读称 768×768 网格 × 两层深度、1.2M 高斯
  （**未经原文直读核对**）。高斯替代网格后断崖处不存在连接前后景的图元。
- **DreamStereo GAPW**（2026-04，[arXiv:2604.12270](https://arxiv.org/html/2604.12270v1)）：
  反向 warp + 用坐标映射**雅可比** `|∂x'/∂x| > δ` 检测遮挡/拉伸。**拉伸比在着色器里免费可得**，
  可驱动透明度或丢弃。
- Restereo：warp mask 形态学膨胀后处理，最便宜的兜底。
- M2SVid / SpatialMe / StereoCrafter / SplatDiff：disocclusion mask 条件扩散补全（多视频向）。
- **MoGe-2**：训练数据精修**主要过滤物体边界附近的真值**再用合成锐标签回填，边界比上代干净，
  但仍是**单层深度**——混合像素二义性必须在我们管线里解决。
- Immersity 4.0 / LIF：前景/背景独立图层各自渲染，业界形态与我们一致。

## 5. 对我们管线最可落地的 5 条建议

约束：端上跑、资产离线烘焙一次、WebGL 前向投影双层网格、已有 BiRefNet 连续 matte + MoGe-2 米制深度。

1. **前景层几何边界改由 matte 决定**：顶点保留判据从"深度差<τ"改为 α>α_hi（0.5 起步）；
   α∈(α_lo,α_hi) 过渡带不进前景网格。依据：MaskDepth、Cinematic Photos、SLIDE。
   直接消灭"胖一圈"。
2. **过渡带深度双侧外插**：前景层过渡带顶点深度从 α>α_hi 内侧 3–5px 环带沿法向外插；
   背景层同区域从 α<α_lo 外侧外插。锐化只能让斜坡变陡，外插才能让它消失。
   依据：Twin Surface Extrapolation、αDepth 的 D̄_FG/D̄_BG、HairGuard 合成式。
3. **补全掩膜按 matte 膨胀、背景层向前景背后多长一圈**：`inpaint_mask = dilate(α>α_lo, r)`，
   r ≥ 最大倾斜下前景边缘位移 + 3–5px。膨胀源必须是 matte 不是深度断崖。背景层补全区深度
   用背景侧外插（Kopf 迭代扩张 50 次的语义）。这是建议 5 生效的前提。
4. **前景层预乘 alpha 渲染，α 与颜色一起投影**：α 取连续 matte 不二值化；背景先画前景后画，
   `ONE, ONE_MINUS_SRC_ALPHA`。依据：αDepth 合成式及其"联合投影"论证。
   同时掩盖建议 1 的 α_hi 硬切锯齿。
5. **兜底（成本最低，可先做）**：残余跨断崖三角形按拉伸量做 α 衰减而非硬丢弃——
   `A = exp(−β·|∇D|²)`（SLIDE）或屏幕空间雅可比/`dFdx` 拉伸比（DreamStereo）。
   硬丢弃留白边，透明化露背景。也是对 matte 局部不准的保险。

**验收**：拿 BiRefNet matte 当 GT，按 Depth Pro 的 boundary F1/recall 口径量化 MoGe-2
深度边偏移与过渡带宽度，让膨胀半径与环带宽度有依据；配合既有倾斜验收规则做前后对比。

**优先级一句话**：建议 2 是拉伸膜的根因修复，建议 1 是"胖一圈"的根因修复，都在离线烘焙
阶段完成、零运行时成本；建议 5 零成本可立刻加；建议 3、4 是前两条生效的必要配套。

**不确定性标注**：(a) SHARP 的结构参数来自第三方解读，落地前核对原文；
(b) SLIDE 未公布 β/γ/ρ 数值，采用其指数衰减形式需自行标定。
