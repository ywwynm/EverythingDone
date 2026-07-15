# FableSol 水体材质第二轮调研与诊断（2026-07-15）

## 真机复测修订：D135（2026-07-15）

本页以下内容记录第二轮方案形成过程；用户在真机复测后否决其中两项产品结论：固定 hue 的
OKLCH 亮向阶梯仍显得灰暗、发脏，全九层离散闪点及连续太阳 patch 又形成过多扩张、收缩的
圆环/椭圆光斑。最终实现改为静态 `lighten_far=0.864` 的 OKLab 混白阶梯，所有界面肩归零；
离散闪点只保留最近三层 `3/2/1` 个核心，删除周期呼吸和解析外晕；连续 GGX 太阳反射使用
`.72/.49/.21/0/0/0/0/0/0` 的独立逐层权重，Beer/折射背景混合上限由 `.049` 收到 `.016`。
折射、Beer–Lambert、微法线、97 行连续网格和双
FBO 仍保留。正式合同以 `decisions.md` 的 D135 为准。

## 本轮问题

真机反馈集中在四点：九层主体边界变弱且偶发粗重记事色边；跨层移动光影出现沿三角网格的折线；中远层缺少持续可见的光学变化；除离散闪点外几乎没有局部 HDR 超白高光。折射与 Beer–Lambert 吸收也进入本轮设计范围，但不得用伪物理压暗污染任意记事色。

## 代码与性能诊断

Python 固定 640×840 HDR 场景原先约为 2.5 FPS。分段计时和 `cProfile` 证实，主要成本不是 GPU：预构建网格后的 GPU 上传、绘制和同步不足 1 ms；真正瓶颈是 surface/interface 等光学带逐列调用 OKLCH 色域二分映射。改为向量化颜色场、量化材质缓存并缓存静态索引拓扑后，无 CPU 读回的 `render_scene()+finish` 中位数约 16.3 ms（约 61 FPS），HDR 离屏读回中位数约 22.0 ms（约 45 FPS）。读回路径只服务诊断；正常桌面 HDR 继续直接消费 GPU 纹理。

缓存只保存昂贵的颜色推导结果。每个 `GlFrameData` 仍复制小型数组并拥有独立可变数据，防止测试或诊断原地修改一帧后污染后续帧。相关颜色、Android parity、GL 和连续曲面回归共 67 项通过。

视觉诊断工具覆盖 Android 的 10 个内置纯色和用户提供的 8 组真实纯色/渐变。每个消融项必须独立创建、渲染并关闭 standalone OpenGL context；Windows 下同时持有多个 context 会让非 current renderer 的结果退化为环境背景，旧的并行 context 基线已作废。

## 物理与实时渲染约束

1. 九层是产品上的 2.5D 分段，不是九个独立空气—水界面。共享的太阳镜面、环境反射和折射只能表达一张连续可见水面，不能让九层分别无预算地叠加 Fresnel 能量。
2. 水的法向入射反射率约为 2%，可采用 `F0≈0.0204`。连续高光应使用 GGX/Trowbridge–Reitz 分布、Smith 遮蔽和 Schlick Fresnel，并把太阳高光、环境反射和透射纳入同一能量预算。[Filament PBR](https://google.github.io/filament/Filament.md.html)；[Filament material properties](https://google.github.io/filament/Materials.md.html)
3. 真实海面波光来自满足反射方向的局部坡度统计，不应是跨整层的恒定银白膜。Cox–Munk 的太阳闪光测量为方向性坡度分布提供物理依据。[Cox 与 Munk，1954](https://opg.optica.org/abstract.cfm?URI=josa-44-11-838)
4. 低粗糙度镜面必须做 footprint/specular anti-aliasing。屏幕导数应在统一控制流中先计算，再用于有效粗糙度；GLSL ES 明确规定非一致控制流中的导数未定义。[GLSL ES 3.20 规范](https://registry.khronos.org/OpenGL/specs/es/3.2/GLSL_ES_Specification_3.20.html)
5. 折射需要先得到一张不含水体的不可变背景纹理，再以法线扰动屏幕 UV 采样；不能在水体 pass 中读取当前仍挂载为颜色附件的同一纹理。该方案属于有界的 screen-space 近似，不等价于隐藏几何上的真实光线追踪。[GPU Gems 2：Generic Refraction Simulation](https://developer.nvidia.com/gpugems/gpugems2/part-ii-shading-lighting-and-shadows/chapter-19-generic-refraction-simulation)
6. 均匀介质透射遵循 `T=exp(-σ·d)`，透射率始终位于 0～1。任意记事色不能直接套用纯水会偏蓝的吸收谱；本项目只能采用“身份色约束的吸收系数”，保留指数光程关系，同时限制色相和饱和度漂移。[PBRT：Transmittance](https://pbr-book.org/4ed/Volume_Scattering/Transmittance)；[Pope 与 Fry 的纯水可见光吸收测量](https://pubmed.ncbi.nlm.nih.gov/18264420/)
7. 线性 HDR 颜色必须在线性域完成混合、Fresnel、镜面和吸收，最终 SDR present 才编码。RGBA16F 颜色附件由 `EXT_color_buffer_float` 定义；HDR headroom 只开放给局部镜面峰值，不改变主体材质基线。[Khronos EXT_color_buffer_float](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_color_buffer_float.txt)

## 实施次序

1. 先用九层主体感知色阶承担分界职责，移除会压过主体差异的整条 interface shoulder；远端提亮不超过等效 86.4% 混白。
2. 把纵深网格从每层 3 行提高到每层 6 行（总计 49 行），同时用片元级 specular AA 抑制剩余三角折线。是否保留该密度以 Python 性能和图像指标共同决定。
3. 用一套连续 GGX/Smith/Schlick 太阳镜面替换当前阈值式 directional sheen，使近、中、远层都获得低频高光斑和细粒波光；离散 glint 继续表达少量高峰值实体。
4. SDR 保留受控镜面形状；录音 HDR transition 只为局部高光增加 `>1.0` excess，并按覆盖率、最大连通面积和逐层占比回归。
5. 最后增加独立背景 pass、单次 screen-space 折射和身份色约束 Beer–Lambert。折射位移、边缘衰减、光程和颜色漂移均设置上限，避免为“更物理”破坏九层可读性。
