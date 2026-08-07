# Opus 调研 1/3：工业界单图 3D 照片实现（2026-08-04）

## 核心结论速览

1. 幅度共识：画面宽度 2%-4%（ISU 黄金法则 1/30≈3.33%；阿凡达实际 -0.5%~+1.0%；
   Legend3D 转制 50-80px/2K≈2.6%-4.2%；Meta 越界直接淡出）。我们当前 0.12≈12%，
   超出工业界上限 3-4 倍——工业界的答案不是解决这个区间的形变，而是不进入。
2. 形变三源：断边拉伸（我们已治）、物体内深度梯度剪切（相对深度的 scale-shift
   仿射歧义 = 数学根源，模型属性）、backward UV warp 一阶近似（我们是顶点位移
   forward warp，不中此枪）。
3. Google Cinematic Photos：与我们同构的连续网格；零补全（缩小视场内缩取景）；
   预渲染 + 逐图优化相机轨迹；损失按连通域计权 + head/body/background 三区加权
   把伪影赶到背景。
4. Meta One Shot 3D Photography：LDI→真 3D 网格（glTF+透视相机）；断边前中值
   锐化深度；50 次 LDI 扩张（≈50px 补全余量，≈4.3% 宽度）；行程受限+越界淡出；
   端侧 1.1s 全流程。
5. SLIDE（Google，ICCV21）：双层软分层，软可见度 A=exp(-β|∇D|²)——高梯度处前景
   渐透明露出已补全背景，替代硬断边，且救发丝软边；全流程 0.07s。
6. MPI：层内严格刚体平移（纯横移下单应退化为平移）——零层内剪切的构造性答案，
   代价 cardboard。
7. 影视 2D→3D 转制（预算最足、结论最硬）：拒绝投影代理几何（rubber mat）；
   "层深度定位移（可大）、物体体积定层内形变（必须小）"两信号分离原则——单张
   深度图把两者混在一起是形变失控的根源；角色内部另做 ~7 层体积遮罩。
8. 三星专利 US12051150B2：逐图打分（该效果会显露多少遮挡区）→ 为每张图选它
   扛得住的效果，而非统一幅度。
9. 趋势：从"warp 一张图"迁到"生成期多视角/多层高斯（MLGS）"，运行期只插值；
   metric 深度（Apple Depth Pro，2.25MP/0.3s）消除仿射歧义 = 形状失真的结构解。
10. 补全余量与幅度必须绑定为同一组参数（Immersity 把 edge dilation 与 amplitude
    并列暴露）；谁都不在运行期补全。

## 对当前方案的落点建议（原文）

1. 位移在顶点还是 UV：我们已是顶点位移（forward）✓。
2. 深度语义：确认 shift ∝ 逆深度 ✓（我们如此）。
3. 引入 SLIDE 软可见度替代/叠加二值断边。
4. amplitude 与补全余量绑定（Meta ~50px/4.3% 量级）。
5. 幅度预算锚定 ~3% 画宽；更强观感走推拉路径（对深度误差宽容）与内缩取景。
6. 根治形状失真：metric 深度，或逐物体独立归一化（体积由渲染决定）。

## 关键来源

- Google Cinematic: research.google/blog/the-technology-behind-cinematic-photos/
- Meta One-Shot 3D: arxiv.org/abs/2008.12298 (+ github facebookresearch)
- SLIDE: arxiv.org/abs/2109.01068
- Single-View MPI: arxiv.org/abs/2004.11364
- 三星专利: patents.google.com/patent/US12051150B2/en
- Apple Depth Pro: machinelearning.apple.com/research/depth-pro
- 影视转制: fxguide.com/fxfeatured/art-of-stereo-conversion-2d-to-3d(-2012)/
- 幅度规范: isu3d.org/goldenrules/；videoprocessing.ai/stereo_quality/
- Parallax mapping: learnopengl.com/Advanced-Lighting/Parallax-Mapping
- MLGS: dl.acm.org/doi/10.1145/3746027.3755176

（完整报告原文存于会话记录 2026-08-04；未核实项：Immersity 参数默认值、
CapCut 技术来源、DepthFlow 着色器细节、3D-Photo-Inpainting 默认轨迹数值。）
