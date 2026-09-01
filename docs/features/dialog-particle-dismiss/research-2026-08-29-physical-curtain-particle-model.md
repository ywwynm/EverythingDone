# 连续受风薄面与粒子脱离模型调研

日期：2026-08-29

## 结论

目标效果不应被建模为若干粒子发射器或逐帧遮罩，而应被拆成四个连续阶段：贴附薄面、
边界受风解除、局部折叠压缩、粒子脱离输运。参考视频只负责测量这些阶段是否在合理时刻
出现，不向运行时提供释放或密度数据。

## 可采用的物理与渲染规律

1. **薄面约束。** Position Based Dynamics 直接修正位置，适合交互式布料的稳定、可控
   求解；XPBD 用 compliance 消除刚度对时间步长和迭代次数的强依赖。因此生产模型保留
   结构、剪切和弯曲约束，并使用等效 compliance，使桌面与 Android 在不同帧率下仍保持
   接近形态。
2. **风力。** Disney 的布料风场把相对速度拆为切向阻力与法向升力，并按速度平方增长。
   这正好解释“开场轻缓拉伸、后段明显加速”，也比直接给每个点同一方向位移更符合局部
   曲面朝向。阵风包络和空间低频扰动作用于整片速度场，而不是逐粒子随机散射。
3. **释放传播。** 每个边界点都可以受风，但迎风／反向侧具有更低的贴附阈值；释放时间
   沿布面网格邻接传播。这样首批起点主要而非全部位于反向侧，同时每个内部释放区域都与
   已释放边界连通，不会出现悬空岛、内部断层或点源相交形成的尖锐半岛。
4. **折叠与增密。** 离散薄壳以相邻三角形之间的弯曲描述薄面折叠；自适应布料工作还表明
   压缩应变和曲率能预测屈曲／皱褶。渲染时应使用曲面的屏幕投影 Jacobian：材料面积投影
   得越小、曲率越大，固定材料采样在屏幕上自然越密。帷幔亮带由更多同尺寸细粒子和前后
   层重叠形成，而不是增大 `gl_PointSize`。
5. **脱离后的流动。** 粒子继承局部曲面速度和法线，再进入统一主风。Curl noise 可构造
   无散度、空间连续的速度扰动，适合作为低幅度横向卷流；它不能替代主风，也不能随年龄
   无限放大，否则会重新出现四散和机械分块。

## 实现约束

- 运行时释放场由方向、边界受风、材料韧性和网格传播生成；禁止读取参考帧纹理。
- 未释放区域继续贴在 `z=0`；已释放布面通过同一拓扑传递张力，不能分成多张独立片。
- 完整层与粒子层用宽度有限的互补过渡，避免二值截断；圆角轮廓参与边界距离计算。
- CPU 预计算每帧位置与压缩指标；GPU 避免每个粒子重复计算多邻点曲率。
- 彩色区域只增加同一材料位置上的真实样本数，不改变释放、速度、寿命、尺寸或风场。

## 主要资料

- Müller 等，Position Based Dynamics：<https://www.cs.toronto.edu/~jacobson/seminar/mueller-et-al-2007.pdf>
- Macklin 等，XPBD：<https://mmacklin.com/xpbd.pdf>
- Disney Animation，布料与头发的风力模型：<https://media.disneyanimation.com/uploads/production/publication_asset/115/asset/cloth_hair_wind.pdf>
- Grinspun 等，Discrete Shells：<https://doi.org/10.1145/1185657.1185662>
- Narain 等，自适应各向异性布料网格：<https://graphics.berkeley.edu/papers/Narain-AAR-2012-11/Narain-AAR-2012-11.pdf>
- Zwicker 等，Surface Splatting：<https://vcg.seas.harvard.edu/publications/20010101-surface-splatting>
- Bridson 等，Curl Noise：<https://doi.org/10.1145/1276377.1276435>
