# 2026-09-09 华为粒子消散分析与桌面设计

## 结论与实施目标

本轮优先解决材料交接、粒群相干运动、局部疏密和尾部节奏。增加粒子数量、亮度或随机位移，本身不能保证改善这些问题。桌面实现应允许分别查看释放遮罩、致密微片和最终合成，同时保持它们使用同一个释放时间。

本轮审阅了 **107 个不同 URL 的有效来源**：Houdini 28 个，实时引擎与 Blender 22 个，AE／Trapcode 20 个，影视制作与动画设计 13 个，渲染及物理论文等 24 个。来源指不同文档或论文，不代表 107 家不同机构。每条来源的事实与本轮应用见[来源台账](research-2026-09-09-sources.md)。正文、PDF、访问记录和检索证据放在实验目录，未计入无正文和失效候选。

本文中的“观察”来自用户提供的视频；“公开依据”来自外部资料；“设计选择”是本轮准备实现和检验的方案。公开资料不能证明华为内部使用了同样的算法。本轮完成桌面候选后，仍以用户视觉认可作为迁移 Android 的前提。

## 参考画面的共同特征

### 材料交接

观察：完整区域仍可辨识时，附近已经产生细粒。进入粒子状态的区域仍短暂保留局部颜色与结构；更早释放的部分逐步展开，背景透出。不同区域同时处于不同阶段，不能理解为先整块变透明，再补一层粒子。

钢铁侠原图本身含碎裂图案，因此必须用静态源图区分已有图案和新增运动。科比序列的蓝色、黄色与肤色可用于检查颜色是否来自同一源位置。灭霸序列的脸、手套与右臂能检查未释放内容是否保持位置。

公开依据：Weta 在《复仇者联盟4》制作访谈中说明，他们先审核双色生长遮罩的节奏，再把同一生长算法直接映射到最终效果；灭霸镜头还使用专门速度场控制消散。它支持将释放时间与输运分开设计，同时保持二者的一致性。[Weta 制作主管访谈](https://www.artofvfx.com/avengers-endgame-matt-aitken-vfx-supervisor-sidney-kombo-kintombo-animation-supervisor-gerardo-aguilera-fx-supervisor-weta-digital/)

设计选择：每个材料单元拥有固定身份、源位置、源色和一次性释放时间。未释放区域的纹理坐标不变；释放后微片从原位连续开始运动。细粒不是独立颜色喷射器，所有颜色使用同一个动力学系统。

### 轮廓与起点

观察：参考通常有少量先后出现的起碎区域；局部传播相互连接，但并不形成规则圆孔或四边同步收缩。整体方向与局部释放方向不是同一概念。

| 参考 | 本轮复核的起碎与推进特征 | 使用方式 |
| --- | --- | --- |
| 1，钢铁侠 | 下方偏中部先变化，左上稍后参与；中段人脸仍完整；下方粒群弯曲并向上延伸 | 主序列；源图片内置碎裂不能误计 |
| 2，通知拍屏 | 底部中间先形成弯曲粒群，左侧与上部先后加入 | 验证多起点和跨通知连接；不直接做像素拟合 |
| 3，展开通知拍屏 | 左上图标附近与右下区域先后释放；文字和浅色底连续转为细粒 | 验证非对称双起点；背景解除模糊混入末段 |
| 4，通知直录 | 上部通知的局部顶缘先变化，下部及侧边随后参与 | 验证不按通知逐个整块消失 |
| 5，科比 | 左侧中部首先脱离，黄色局部形成细密弯曲区域；右侧内容保留更久 | 主序列；原片时间最适合直接比较 |
| 6，展开通知拍屏 | 中下部释放发展成横向粒群，上下仍留完整区域与细带 | 验证密度组织；不是全平面均匀撒点 |
| 7，灭霸 | 右上半部先变化，左上与底部后续参与；完整存留区不规则 | 主序列；尾部在剪辑转黑前仍有微弱残留 |
| 8，深色图片 | 顶部偏右、左侧先后出现粒群；深色材料在亮背景上可见 | 验证深色颗粒不能靠统一白色发光表达 |
| 9，通知变帧率 | 下部右侧、底部左侧、顶部左侧依次发展；粒群跨通知间隙 | 只用按 PTS 定位的图集；退出通知中心混入末段 |

设计选择：在控件坐标上构造少量传播起点及平滑到达时间场。每个起点有位置、延迟、横纵传播比例；使用低频形变和中尺度噪声打破规则轮廓。平滑合并可减少多起点交汇的尖锐接缝。[Domain Warping](https://iquilezles.org/articles/warp/)、[Smooth Minimum](https://iquilezles.org/articles/smin/)

形变只作用于释放标量场，不改变未释放内容的位置。三个主序列可以有对应原片的起点参数，但不使用原片逐帧遮罩作为渲染素材。通用对话框通过连续方向坐标构造起点，不为八个方向分别编写动画。

### 粒群的运动与立体感

观察：相邻粒子不是完全独立运动，而是在中尺度范围内形成连续色带、弯曲路径和疏密变化。末段的颜色区域还能追溯到原内容。画面能够支持局部相关运动的判断，不能据此证明存在真实三维流体或布料。

公开依据：Weta 的《复仇者联盟3》制作访谈提及传播、不同密度的粒子层和保持连接的片状灰烬。后者帮助近景灰片产生连续变化，不能直接照搬为整个对话框的三维翻转。[Weta 制作主管访谈](https://www.artofvfx.com/avengers-infinity-war-paul-story-animation-supervisor-with-sean-walker-cg-supervisor-ashraf-ghoniem-and-gerardo-aguilera-fx-supervisors-weta-digital/)

设计选择：粒群短时保持局部相关，随后逐渐响应主风。连续卷流叠加在主风上；细微摆动与随机性绑定材料身份。少量相关深度、表面朝向和遮挡变化用于增加层次，避免所有粒子同时变亮。

二维势场可以产生卷流：`u_curl = (∂ψ/∂y, -∂ψ/∂x)`。如果需要空间强度变化，应在求旋度前调制势场；直接把无散度速度场乘空间权重一般不再无散度。[Curl-Noise 原论文](https://www.cs.ubc.ca/~rbridson/docs/bridson-siggraph2007-curlnoise.pdf)

本轮允许艺术控制项，包括年龄相关的受风响应、少量局部压缩和弱深度运动，因此不将整个系统宣称为严格物理流体。粒子的近似受风模型为 `dv/dt = (u-v)/τ + f_local`。τ 控制响应滞后，不能把固定风速与持续加速混为一谈。[POP Wind](https://www.sidefx.com/docs/houdini/nodes/dop/popwind.html)、[POP Drag](https://www.sidefx.com/docs/houdini/nodes/dop/popdrag.html)

大尺度走向与小尺度细节分开调节。流体研究中可以先保留大尺度解，再补充具有时间相干的小尺度结构；本轮借鉴这一组织方式，不实现完整烟雾求解器。[Wavelet Turbulence](https://www.cs.cornell.edu/~tedkim/WTURB/wavelet_turbulence.pdf)、[Evolving Sub-Grid Turbulence](https://www.cs.ubc.ca/~rbridson/docs/schechter-sca08-turbulence.pdf)

### 时间节奏

观察：前段保留较多可读内容，中段释放明显加快，后段留给粒群输运和稀释。均匀降低整块透明度不能表达这一阶段关系。

历史科比测量显示，在约 5.433、5.633、5.867 秒时，“原位置仍接近源色”的像素比例约为 83%、25%、不足 1%。这只是纹理相似度代理量，受阈值、背景色和移动碎片影响，不能当作精确剩余材料质量。详见已有[原片观察记录](../huawei-particle-dismiss-rebuild/video-observations.md)。本轮的参数拟合与检视应重新保留同样的证据边界。

公开依据：动画中的总时长与逐帧间距不同；即使总时长一致，间距不同也会改变轻重与速度感。准备、运动和尾随可重叠，不需要机械地分成几个开关。[Timing](https://www.animationmentor.com/blog/timing-the-12-basic-principles-of-animation/)、[Slow In and Slow Out](https://www.animationmentor.com/blog/slow-in-and-slow-out-the-12-basic-principles-of-animation/)、[Follow Through](https://www.animationmentor.com/blog/follow-through-and-overlapping-action-the-12-basic-principles-of-animation/)

设计选择：保留独立的释放时钟、粒子年龄和总动画时间。默认候选以 1 秒作为便于试验的逻辑时长；最终对比明确区分归一化进度和参考文件时间。估计的慢放恢复倍率只保留为分析线索，不标成真实原速；不能拿文件长度直接证明设备动画时长。

## 参考时间与素材限制

| 序列 | 起点候选（文件秒） | 尾部候选（文件秒） | 比较限制 |
| --- | --- | --- | --- |
| 钢铁侠 | 约 3.25 | 约 8.45 | 原片有慢放；约 3.42 倍是历史依据得到的估计，不能称精确原速 |
| 科比 | 约 5.20 | 约 6.25 | 30 帧/秒直录；首粒定位约一帧误差 |
| 灭霸 | 约 5.40 | 约 7.95 | 约 2 倍为估计；约 8.0 秒转黑，尾部不算完整自然终点 |

新提帧放在实验目录 `analysis/`。旧灭霸背景图 7.933 秒仍有颗粒，须清理后才能作为固定背景，否则会产生永久残留。其前景矩形为约 `(40,154)–(680,828)`，旧实验中扩大到 x=22 的边界会把壁纸包进材料。

两张新截图与添加附件都保留各自可见背景。添加附件的纯色背景版本有对应无弹窗截图，可使用真实背景；语言、颜色及图片背景附件仅有一张弹窗图，被遮挡的内容没有真实证据，使用局部背景重建并在预览标明。不能替换成无关首页，也不能声称恢复了隐藏文字和图片细节。

## 从 AE 与实时系统得到的工程取舍

AE／Trapcode 可以分别控制图层发射、源色、粒子寿命、空气阻力、流体场、湍流强度图和运动模糊。教程的价值在于控制维度的拆分，而不是插件名称本身。桌面候选应保留这些可审阅的独立参数。[Particular 图层发射](https://help.maxon.net/rg/en-us/Content/html/19-Trapcode-Particular-emitter-layer-emitter.html)、[湍流强度图](https://help.maxon.net/rg/en-us/Content/html/45-Trapcode-Particular-layer-maps_turbulence-strength.html)

本轮粒子系统仍需要处理“遮罩释放了多少”和“新生材料实际覆盖了多少”的交接。只对完整层做宽噪声溶解，而粒子一出生就离开，会形成破洞；提高重叠数来填洞又可能产生实心高亮带。调整顺序应为：释放时间一致 → 原位覆盖 → 新生位移连续 → 局部聚散 → 亮度与粒径。

采用固定随机种子和材料身份，可复现同一方向与不同方向的差异。普通稳定散列不等于蓝噪声，逐帧独立蓝噪声也不自动保证时间稳定。[Hashed Alpha Testing](https://research.nvidia.com/labs/rtr/publication/wyman2017hashed/)、[Spatiotemporal Blue Noise](https://research.nvidia.com/publication/2022-07_spatiotemporal-blue-noise-masks)

## 材质、合成与画质

1. 源纹理保留局部颜色，微片缩小时逐渐使用过滤后的局部平均色；不按颜色类别设置独立速度和寿命。[PBRT Image Texture](https://pbr-book.org/3ed-2018/Texture/Image_Texture)
2. 在线性光空间做材质明暗与透明覆盖，再编码 sRGB。直接对 sRGB 值做亮度运算会改变中间色与透明交接。[GPU Gems：线性处理](https://developer.nvidia.com/gpugems/gpugems3/part-iv-image-effects/chapter-24-importance-being-linear)
3. 多层粒子优先采用正常覆盖与明确深度顺序。加法混合易把白底变为发光云；近似 OIT 也存在深度层次弱化的取舍，不能当作自动正确。[Weighted Blended OIT](https://jcgt.org/published/0002/02/09/paper.pdf)、[作者的实现说明](https://casual-effects.blogspot.com/2015/03/implemented-weighted-blended-order.html)
4. 小粒子需要空间和时间抗锯齿。运动模糊应短，长拖影会吞没粒子结构。降低离屏分辨率适合低频烟雾，不适合所有细碎材料。[Image Reconstruction](https://pbr-book.org/3ed-2018/Sampling_and_Reconstruction/Image_Reconstruction)、[Off-Screen Particles](https://developer.nvidia.com/gpugems/gpugems3/part-iv-image-effects/chapter-23-high-speed-screen-particles)
5. 本轮立体感来自有限深度、相关法线与密度组织。微表面理论支持法线分布影响材质外观，但简化亮度调制不等于完整物理材质；尘埃的体积散射与表面反射也不能混用。[Microfacet Models](https://pbr-book.org/3ed-2018/Reflection_Models/Microfacet_Models)、[Phase Functions](https://pbr-book.org/4ed/Volume_Scattering/Phase_Functions)

## 设计自由度及不采用项

保留的自由度：局部卷动的方向与强度、稀疏区域的停留、相干团簇逐步松开、少量微片的摆动以及尾部的长短。它们都应服务于三段参考的主轮廓和节奏。

本轮不加入火星、明显发光描边、黑色扫过带、整卡布片翻转和大量烟雾。影视物理可提供启发，但真实纸片的摆动频率依赖形状、惯性与流体参数；像素或 dp 不能直接当成真实米制单位。[落纸动力学论文](https://dragonfly.tam.cornell.edu/publications/2005_JFM_Andersen_Pesavento_Wang_b.pdf)、[NASA 阻力方程](https://www1.grc.nasa.gov/beginners-guide-to-aeronautics/drag-equation/)

## 验证和交付安排

先检视三个主序列的 0%、15%、30%、45%、60%、80%、100% 阶段及连续播放。特别检查完整人脸是否滑动、前沿是否出现空隙或硬带、源色是否保留、末段是否突然关掉。每项数值只用于诊断，不以单一误差分数判定审美效果。

每个场景导出 1 倍与 0.5 倍动画及对比视频。三个主序列与华为原片同内容对比，并额外保留真实文件时间标签和速度口径；截图场景与静态源截图对照，明确其没有同内容华为原片。钢铁侠与添加附件提供同屏八方向，使用同一时间与种子。所有最终视频平铺到一个 `videos/` 目录。

桌面预览应支持逐帧、进度拖动、速度、场景与连续方向调整；导出使用固定参数，确保预览和视频采用同一模型。Android、ADB 和发布均不在本轮范围内。

## 实施后的修正

本轮已完成桌面候选 r15 与 40 个视频，见[结果记录](results-2026-09-09-gpt6.md)。重新测量投影运动后，三个主序列对照方向均改为左上；科比不沿用旧实验的右上方向。参考速度只以 192 个低频系数引导群体输运，不重放逐帧流图。局部翻卷、寿命与颜色照明经过迭代，但紧密粒束和部分起碎形状仍有差距。
