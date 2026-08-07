# 空间照片视觉质量根因诊断与改造路线（2026-08-01）

## 结论

当前高强度下的扭曲、伪影、模糊和发虚，首要根因不是深度模型、matting 模型或
补图模型不够强，而是当前表示与渲染链没有满足三个基本不变量：

1. **参考视点恒等**：相机偏移为零时，空间模式必须按普通查看器的同一变换还原原图，
   即 `R(I, c=0) = I`。
2. **已知像素锁定**：原图中仍可见的内容只能重采样，不得被补图、VAE、alpha 二次合成
   或低分辨率中间表示改写。
3. **遮挡拓扑守恒**：深度断层要形成前后两侧各自完整的表面和显式可见性关系，不能靠
   删除三角形制造“断边”。

因此，不再以调阈值、增加 MPI 层数、切换 MI-GAN/AOT-GAN、继续加 MSAA 作为主路线。
推荐的新主线是 **source-locked layered splatting（原图锁定的分层前向投影）**：原图
全分辨率可见层 + 遮挡处 1～2 个持久隐藏层 + 深度/置信度/可见性解析。Diffusion 或
Flow Matching 只在后续负责学习隐藏内容和跨视角一致性，不能替代这套表示和渲染契约。

## 诊断范围与方法

- 设备：OnePlus `3B1629006YC00000`，Android 16，1216×2640。
- “测试空间效果”的两张附件只作为故障探针和后续回归样本，不参与阈值定标，也不构成
  架构结论的样本基础。
- ADB 无法注入真实陀螺仪姿态；本轮用单指拖动到归一化端点做可重复的静止、左右极限
  取证。代码中触摸与传感器最终进入同一个视点偏移，因此可验证渲染端点，但不能替代
  “倾斜—驻留—回中”的传感器时序测试。
- 证据保存在 `build/spatial-quality-diagnosis-20260801/`。没有把测试图加入产品代码、
  测试资源或任何内容特判。

探针的作用只是快速暴露通用问题：LDI 在静止视点已有轮廓缺口和背景泄漏；MPI 在静止
视点已有整体模糊、重影和碎片；P0 较干净，但高强度被形变预算压低，且仍没有解决新显露
区域。这些现象均能由下面的代码不变量直接证明，不依赖人物、头发或当前两张图的语义。

## 当前实现的通用失败证明

### 1. 固定 overscan 使零视点也不再是原图

LDI 无条件传入 `OVERSCAN=0.09`（`SpatialPhotoRenderer.kt:417-420`），顶点着色器再把
纹理中央 82% 映射到整个画布（`:962-968`）。P0 片元路径同样以中央 82% 计算采样坐标
（`:1057-1066`）。MPI 的 `uInset` 也只由强度计算，不乘当前视点偏移
（`:607-623`、`:921-928`）。因此相机回到零位时仍发生裁切和放大；MPI 还先把输入缩到
长边 960（`SpatialMpiBuilder.kt:26-47`）。这会在所有图片上主动损失构图与有效分辨率。

### 2. 当前 LDI 的“断边”是删面，不是分层拓扑

`buildLdiChunks` 在任一相邻边被标为 cut 时，直接不写入对应表面三角形
（`SpatialPhotoRenderer.kt:735-770`）；渲染又先画完整背景层、后画表面层（`:459-468`）。
所以只要检测到一个真实遮挡边，零视点下就至少有一块表面缺失，生成背景会从缺口透出。
这不是某张图的阈值问题，而是对所有带遮挡断层的输入都成立的拓扑错误。

正确做法是在断层两侧复制边界顶点/样本，保留前景与背景各自完整的表面，并通过 z/alpha
可见性决定谁覆盖谁；“断开连接”不能等价为“删除可见表面”。

### 3. Matting 被施加在已经合成的原图 RGB 上，无法保持参考图

当前 LDI 读取原图颜色 `I` 后输出 `(alpha·I, alpha)`，再 over 到生成背景 `B`
（`SpatialPhotoRenderer.kt:997-1033`），结果为：

`I' = alpha·I + (1-alpha)·B`

除非 `alpha=1` 或 `B=I`，否则 `I'≠I`。真正的软前景合成需要分别估计前景颜色 `F` 与
背景颜色 `B`，满足 `I=alpha·F+(1-alpha)·B`。只有 alpha、没有 foreground color
decontamination 时，matting 只能帮助判断边界归属，不能把已合成的原图再透明一次。

### 4. 当前 MPI 是深度分桶，不是学习得到的 RGBA 多平面表示

当前实现把可见图和一张固定补全图按深度分入 10 个、长边 960 的平面，并在层界做羽化
（`SpatialMpiBuilder.kt:26-117`）。随后 `compositeBehind` 把身后合成色预先写入每层颜色，
却保留该层自身 alpha（`:207-248`）；渲染时又把所有平面从远到近 over 一次，并以原图
打底（`SpatialPhotoRenderer.kt:641-664`）。以一个 alpha 为 `a` 的前层 `F` 和不透明后层
`B` 为例，预写后再合成得到 `a²F+(1-a²)B`，而正确结果应为
`aF+(1-a)B`。这会系统性地产生边缘重影、发灰和模糊，增加层数只会增加参与合成的机会。

文献中的 learned MPI 是网络直接预测 RGBA 平面和遮挡后的内容，而不是把一张 RGB-D
图硬分桶；Single-View MPI 明确通过 view-synthesis supervision 学习背景层隐藏内容
（[CVPR 2020](https://openaccess.thecvf.com/content_CVPR_2020/html/Tucker_Single-View_View_Synthesis_With_Multiplane_Images_CVPR_2020_paper.html)）。

### 5. P0 的单步 backward warp 不是通用的新视角渲染器

P0 用目标位置先采一次深度，再做一次 `sampleUv = cameraUv + motion·depth`
（`SpatialPhotoRenderer.kt:1057-1069`）。当深度随位置变化时，这只是逆映射的一阶近似；
位移与深度梯度越大，采样误差越大。它也没有前向投影的多对一遮挡解析，无法表达原图
之外的新显露区域。形变预算能压住最坏拉伸，但代价就是用户看到的空间幅度明显变弱。

## 根治架构：Source-Locked Layered Splat

### 表示

每张派生结果持久化以下内容：

1. **可见层 V0**：原图全分辨率像素、深度、有效性和几何置信度；颜色始终引用原图，
   不经过补图模型或低分辨率平面。
2. **隐藏层 H1/H2**：只沿真实遮挡边向后扩展，联合保存隐藏 RGB、隐藏深度、alpha 与
   置信度。大面积未知区域可以分块生成，不必把整张原图复制成十层。
3. **显式拓扑**：断层前后分别保留样本；细发丝和软边界可用 MODNet 辅助决定覆盖率，
   但参考视点仍直接显示原图。
4. **可信视域**：从目标视点实际产生的 hole ratio、隐藏层覆盖率和深度置信度计算，
   不使用“人像/室内”等内容分类或单图阈值。默认给出可信范围，同时允许用户进入高强度
   实验范围，而不是无提示地硬截断。

### 渲染

- 采用 per-pixel/surfel 的前向 EWA splatting；多个源样本落到同一目标像素时按深度和
  置信度做 z/softmax 解析。Softmax Splatting 证明了前向 warping 能显式处理多对一冲突
  （[CVPR 2020](https://openaccess.thecvf.com/content_CVPR_2020/html/Niklaus_Softmax_Splatting_for_Video_Frame_Interpolation_CVPR_2020_paper.html)）。
- `camera=0` 使用与普通查看器一致的原图路径，或由测试证明 splat 路径逐像素恒等；固定
  overscan 归零。需要防越界时扩大离屏画布，仅随实际偏移增加 cover margin。
- 已知区域优先使用 V0；只有真实 disocclusion hole 才读取 H1/H2。补图内容不得覆盖仍可见
  的源像素。
- 抗锯齿、mipmap/各向异性采样和超采样放在拓扑正确之后；它们负责采样质量，不负责修复
  缺面、双重合成或错误深度。

传统 LDI 的正确方向也是“显式像素连接 + 隐藏颜色和深度联合补全”，而非只补一张背景
RGB（[3D Photo Inpainting](https://shihmengli.github.io/3D-Photo-Inpainting/)）。SLIDE 进一步
说明软分层应配合面向 3D photography 的 depth-aware inpainting，而不是通用补图后再套
alpha（[Google Research](https://research.google/pubs/slide-single-image-3d-photography-with-soft-layering-and-depth-aware-inpainting/)）。

## 生成模型选择

### 近期：先修表示，不新增大模型

MI-GAN/AOT-GAN 暂时只用于 H1/H2 的窄显露带 RGB 初始化，并补齐对应的隐藏深度。它们
不是最高质量终点，但在表示层错误仍存在时换 diffusion 不会改善零视点缺面、原图模糊或
重复 alpha 合成。先让无新模型的 renderer 通过全部不变量，才能公平比较生成模型。

### 中长期：训练任务专用的端侧 student

没有发现一套可直接集成、同时满足“通用场景、商业分发、Android 端侧、强视差高质量”
的现成权重。最合理的研发路线是训练一次性生成持久表示的轻量 student：

- 输入：原图、metric depth、相机内参/目标视域、遮挡与置信度；
- 输出：V0 的几何修正 + H1/H2 的 RGB/depth/alpha/confidence，或等价的双层
  Gaussian/surfel 表示；
- 训练：源像素锁定损失、参考视点恒等损失、多视角重投影与 loop-closure、边缘结构损失、
  不确定性校准；
- teacher：可用高质量 splatting + video diffusion 离线生成训练监督；用户图片仍只在
  手机上推理，不上传。

已有研究支持这条组合，而不是支持“只换补图器”：Flash3D 用首层 Gaussian 加隐藏层完成
遮挡后内容（[arXiv](https://arxiv.org/abs/2406.04343)）；Apple SHARP 说明单次前向网络可生成
可实时渲染的单图 Gaussian 表示，但当前规模与发布条件只适合作为架构参考
（[项目页](https://apple.github.io/ml-sharp/)）；SplatDiff 明确指出纯 splatting 会受几何误差
影响、纯 diffusion 会产生纹理幻觉，因此用 splatting 控制几何并用 texture bridge 锁定
源纹理（[Disney Research](https://studios.disneyresearch.com/2025/07/16/splatdiff/)）。

端侧并非原则上不可行：CheapNVS 已在 Tab S9+ 上实现窄基线实时 NVS，但作者也明确承认
大 baseline 与外部深度误差仍是限制，这说明需要针对本产品视域重新训练，而不是直接照搬
（[Samsung Research](https://research.samsung.com/blog/CheapNVS-Real-Time-On-Device-Novel-View-Synthesis-for-Mobile-Applications)）。
移动端 diffusion 也已有 512×512、约半秒的专用模型实例，但那是经过架构压缩和一步蒸馏的
text-to-image 模型，只证明算力可行，不提供 NVS 能力
（[MobileDiffusion](https://research.google/blog/mobilediffusion-rapid-text-to-image-generation-on-device/)）。

### Flow Matching 的定位

Flow Matching 是训练 continuous normalizing flow 的目标，diffusion path 只是其可选概率
路径之一；它不是可直接替换 MI-GAN 的补图模型
（[原论文](https://arxiv.org/abs/2210.02747)）。它值得用于后续 few-step student 的训练与蒸馏，
但产品设置应按“速度/质量/内存已验证的模型包”呈现，不应把“diffusion/flow matching”
这些训练方法本身当成用户可选效果。模型是否值得增加，必须由相同跨场景基准决定。

## 分阶段实施

### P0：正确性基线（先做）

1. 增加参考视点恒等测试，并让 P0/LDI/MPI 在同一 PhotoView 变换下比较。
2. 去掉零视点固定 overscan/inset；可见颜色保持原图分辨率。
3. 停止“cut 即删三角形”和“compositeBehind 后再次 over”；当前 MPI 不再作为高质量
   模式继续堆参数。
4. 建立最小前向 splat renderer：全分辨率 V0、z/置信度解析、hole mask，可先只渲染
   已知层。此阶段不换任何 AI 模型。

### P1：真正的 Soft-LDI/分层 splat

1. 断层两侧复制样本，构建 H1；联合扩展隐藏 RGB 与 depth。
2. Matting 只用于边界 ownership/coverage；需要透明合成时显式估计 F/B 颜色。
3. 增加 H2 只覆盖复杂交叠和大视差仍有空洞的 tile，避免固定十平面成本。
4. 端侧缓存持久表示；实时交互只做 GPU splat，不逐视角运行生成模型。

### P2：高质量 task-specific student

1. 用带相邻真实视角的视频/多视图数据训练，先做 512/768 PoC，再量化和端侧 profiling。
2. 对比 feed-forward、few-step diffusion 与 rectified-flow student；只保留通过质量/延迟/
   内存/许可门槛的模型包，继续沿用按需下载机制。
3. 生成一次后缓存 H1/H2 或 splat field，而不是每次倾斜重新生成一张图。

## 跨场景质量门槛

这两张附件只占回归集中的两项。正式验收至少覆盖 50～100 张静态图和带真实相邻视角的
视频/双目子集，包括单人、多人、细发丝、文字/直线、透明与反光物、复杂室内、室外、
低照度、近摄和深景；所有阈值按整个 held-out 集确定。

| 维度 | 必须满足的门槛 |
| --- | --- |
| 参考视点 | `camera=0` 与普通查看路径一致；无背景泄漏、无构图变化；理想为同源纹理逐像素恒等，允许采样时 PSNR ≥ 50 dB |
| 源纹理锁定 | 所有视点的已知区域不被生成模型改写；高频/边缘锐度保留率单独统计 |
| 拓扑 | 零视点缺面数为 0；无 fold-over；hole 只出现在真实 disocclusion 区域 |
| 视角一致性 | 左/右/上/下与环形轨迹均检查；回到零位无漂移，左右往返无残留状态 |
| 隐藏内容 | RGB 与 depth 一起验收；跨相邻视角不能出现纹理游走、背景贴前景或同一物体多次出现 |
| 可信视域 | 报告 hole ratio、隐藏覆盖率和置信度随幅度曲线；默认范围由几何证据决定，仍允许用户手动越界 |
| 性能 | 生成阶段峰值内存、温升、耗时；交互阶段 P50/P95 帧时和掉帧；画质测试不能靠主动降源图分辨率通过 |

未来 Spatial Video Effect 不能逐帧独立生成：那会让深度、遮挡边界和补图纹理逐帧变化，
必然闪烁。应复用持久/关键帧 splat 表示，进行时序深度稳定、跨帧特征/flow 传播和联合相机
轨迹监督。MultiDiff 的关键也是联合生成一段目标视角序列，而不是独立生成每帧
（[arXiv](https://arxiv.org/abs/2406.18524)）。

## 决策摘要

- 两张图是回归探针，不是优化目标。
- 当前 handcrafted MPI 与删面式 LDI 不再承担“最高质量”路线。
- 先完成 P0 的表示不变量，再实现 P1 source-locked layered splat。
- Diffusion/Flow Matching 是 P2 的训练与生成候选，不是 P0/P1 的替代品。
- 静态图从一开始就持久化可扩展到时间维的表示，避免未来空间视频推倒重来。

## 2026-08-02 真机补充诊断：隐藏背景错误遮挡对象层

在 OnePlus PLZ110（Android 16，设备 `3B1629006YC00000`）上通过真实产品入口重建
“测试空间效果”的第一张附件。派生数据确认使用
`ldi-lite-v5-object-layer + DA3 + MI-GAN + MODNet + RF-DETR + EdgeTAM`。关闭姿态
传感器后，以和倾斜输入共用的归一化视点做可重复拖动：强度 1.0、水平 ±100% 与对角约
70% 均稳定复现人物脸和躯干被隐藏背景大面积替换，只剩部分发丝和衣物轮廓。

同一派生、同一强度、同一 +100% 视点切换到单层稳定模式后，人物立即完整，证明本次
灾难性 artifact 不来自深度、补图、matting 或 EdgeTAM 推理本身，而来自 P1 对象层合成。
派生平面离线检查进一步确认 ownership label 与 subject mask 覆盖一致，对象内部 alpha
大部分不透明；反例不是 mask 把脸挖空。真正冲突是隐藏背景 pass 先写入 depth，而当前
`backgroundDepth` 与 `surfaceDepth` 相同；对象层只取实例深度中位数，约一半背景样本会
在深度测试中比对象代表平面更近，随后对象 fragment 被拒绝。隐藏背景本应只是未覆盖像素
的颜色兜底，不能拥有阻止任何已知源表面或对象层绘制的深度。

修复不变量：隐藏背景 pass 关闭 depth test 且禁止 depth write；之后重新启用 depth test，
让连续已知表面、断边 splat 与对象层共同写入并解析真实可见层深度。该规则需由可单测的
render-pass policy 驱动，并在真机复跑相同视点矩阵，随后再评估剩余的补洞、边缘和可信
视域问题。

### 2026-08-02 v6～v10 边界与实例补充诊断

隐藏背景深度冲突修复后，第一张附件的脸和躯干在强度 1.0、水平 ±100% 与对角视点保持
完整，但继续暴露三类互不等价的问题：

1. 把所有 RF-DETR 小实例都剥离成刚性平面，会让远处人物、椅子和餐具成为低分辨率贴片。
   输出面积低于画面 1.2% 的实例现保留在连续深度表面；这不是丢弃语义分割，而是拒绝让
   低像素、低收益对象承担独立运动身份。
2. 1024 长边 MODNet 明显增加了发丝和衣缘采样信息，但其偶发的连通误检会沿人物—桌面—
   杯子一路扩张。人物 matte 只能从 RF 人物实例向外传播约长边 1%，且不能覆盖已识别的
   非人物实例；由此保留高分辨率轮廓，同时阻断远距离 false positive。
3. v9 派生的 `ownership-alpha.a8z` 与 450×600 categorical label 对齐统计显示，标签外
   非零 alpha 数量仍为 0。也就是说 1024 MODNet 最终又被低分辨率身份图裁切，披风开口与
   下摆仍以约两个背景像素为一级呈阶梯。v10 将人物身份仅向外传播一个网格采样宽度作为
   门控支持，覆盖率仍完全来自原始高分辨率 matte；实例身份不参与 alpha 量化。

同时，旧 shader 用 MI-GAN 生成的隐藏背景反解前景颜色。生成内容与原图局部背景在曝光、
纹理或结构上不一致时，该差值会被除以低 alpha，放大成发丝白边或暗边。v10 改为沿 alpha
梯度从原图轮廓外侧取局部背景进行去污染；生成背景只负责对象移开后的显露内容，不再参与
已知前景颜色估计。以上规则均与图片内容和人物位置无关。

### 2026-08-02 v13～v14：运动接缝与无效的大面积补全

第二张附件的派生实例统计显示，两个人物的代表深度差约 0.078、直接接触边长 412 个网格边。
把它们作为两个独立平面会在最大视差下制造约 9 px 的无依据内部裂缝。v13 因此增加通用的
接触约束运动分组：只有接触长度达到按较小实例面积开方计算的下限，且整个组合深度跨度不超过
0.10 时才共享运动；短接触和大深度差继续分离。真机左右满幅复核中，两张脸、五官相对位置和
躯干均保持刚性，接触处不再产生新的裂缝。

第一张附件剩余的强视差问题包含两种不同来源。`ownership-alpha.a8z` 的衣物开口确有 4～6 px
硬台阶，但离线全局 guided/box smoothing 会扩张开口或损失发丝，不能作为通用修复。更重要的
取证来自派生 `background.png`：旧实现把完整人物足迹交给通用补全器，生成图内部已经出现桌椅、
墙面和人物残片混合的大面积结构错误。renderer 在任一允许视角只可能读取轮廓附近的有限区域，
因此整块重建既增加任务难度，也把与实际显露无关的幻觉保存进派生。

v14 改为几何保证的 reveal band：由对象外轮廓和内部孔洞向内传播最大相对视差所需的像素半径，
只补该窄带，并保留原有几何 disocclusion。边界颜色稳定从最多 24 px 的最近纹理传播压缩为
3 px 接缝处理，避免把边界纹理拉成长条。下一步真机验收重点不再只看脸是否完整，还要分别检查
最大／中等强度下内部开口、发缘、对象外轮廓和新显露背景是否仍出现前景残影或结构幻觉。

### 2026-08-02 v15～v18：实际显露预算、固定画框与补图反证

第一张 v15 派生的对象代表深度约 0.5105，与对应背景深度的最大相对差约 0.176；按当前最大
视差换算只需约 29 px 显露带，旧 136 px 理论最坏预算会无谓扩大生成区域。v16 因此按 renderer
实际 ownership 运动图逐对象计算显露半径，并优先从 1440 长边 ownership alpha 提取包含内部
孔洞的完整拓扑。

v16/v17 的同模型 A/B 进一步否定了“额外扩大模型输入掩膜”：AOT-GAN 在扩大后的对象内圈生成
大片近黑内容，MI-GAN 在发缘生成块状纹理，而最大视点的实际可见区域没有改善。v18 只把确实
可能显露的像素交给补全器，同时保留“完整对象内部不能成为可信背景种子”的 guard。该结论来自
派生平面和两个补全模型的对照，不把指定人物或坐标写进算法。

对角视点的整幅边界弯曲则定位为网格越过 letterbox，而非深度或补图错误；固定参考图 scissor
后，第一张附件的水平和对角极限均恢复直线矩形画框。第二张附件在强度 1.0 与约 0.65、水平两端
和两个对角端点复核中，两张脸及五官保持刚性，剩余可见误差主要是 ownership alpha 的肩线、
发缘和衣物轮廓台阶。shader 已取消硬边二次锐化，只对 alpha coverage 做一个 texel 的 tent
重建；不对源图颜色或不透明内部做模糊。
