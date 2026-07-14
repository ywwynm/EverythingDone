# FableSol 干净立体阴影调研（2026-07-14）

## 结论

下一轮不应恢复 `blackMix`、整层 `base * shadow` 或微法线暗纹，而应试验一条独立的
**受限直射光亏损（sun-only form shadow）**：保留当前环境光、水体身份色、体积色与 D87 正向坡面光，
只在宏观背光坡的局部肩部减去很小一部分直射太阳贡献；减色朝当前 Thing 身份色派生的
`deepColor` 走，并用线性亮度损失封顶，不能朝黑色或中性灰走。

首轮建议只在 Python 做单变量 A/B。当前 Android 不修改，待 Python 动态目测确认后再决定是否同步。

## 物理与工程依据

水是电介质，表面反射与透射比例随入射/观察角度变化，不能把水体简化成一块只受 Lambert
`N·L` 控制的漫反射色。[PBRT 的电介质 Fresnel 说明](https://www.pbr-book.org/4ed/Reflection_Models/Specular_Reflection_and_Transmission)
与 [NVIDIA GPU Gems 的水面实现](https://developer.nvidia.com/gpugems/gpugems/part-i-natural-effects/chapter-1-effective-water-simulation-physical-models)
都把水体本色、反射与视角项分开处理。

表面出射光来自各方向入射光的积分；某个方向的直射光被遮蔽，不等于环境与间接光一并消失。
[PBRT 的表面散射方程](https://pbr-book.org/4ed/Radiometry%2C_Spectra%2C_and_Color/Surface_Reflection)
和[光输运方程](https://www.pbr-book.org/4ed/Light_Transport_I_Surface_Reflection/The_Light_Transport_Equation)
支持把“基底/环境贡献”和“可被阴影削弱的直射贡献”分开。

微软研究的实时海水模型明确拆分环境反射 `Cenvir` 与太阳镜面反射 `Cspecular`，最终用 shadow map
衰减太阳镜面项；折射、水体体积色与环境反射不被同一个阴影系数整体压暗。论文还把水下物体色的
指数衰减与水体散射作为另一条体积路径处理。
[Realistic, Real-Time Rendering of Ocean Waves](https://www.microsoft.com/en-us/research/wp-content/uploads/2016/12/rtwave.pdf)

微表面遮蔽不能用多个独立暗化因子随意相乘。PBRT 指出，忽略遮蔽/掩蔽相关性会高估阴影量，产生
不希望出现的暗区；这与此前 `micro_normal_strength`、宽域坡面暗化叠加后“脏灰”的现象一致。
[PBRT 微表面 masking-shadowing](https://www.pbr-book.org/4ed/Reflection_Models/Roughness_Using_Microfacet_Theory)

Beer–Lambert 指数衰减适合描述光在水中随路径长度的有色吸收，不适合冒充表面背坡阴影。后续若要
增加水体厚度感，可以把它作为独立体积项；本轮不与阴影一起恢复。
[PBRT Transmittance](https://pbr-book.org/4ed/Volume_Scattering/Transmittance)

## 当前实现为何显平

当前 D87 的 `relativeLongitudinalLight` 只保留相对参考法线的正向 `N·L` 差，近层最多同色提亮
`1.5%`；负向差恒等，因此水体填充只有“基色 + 局部提亮”，没有连续的背坡明暗转折。轮廓、高光、
HDR 银泽和 SSS 能说明表面位置，却不能在没有高光的普通坡面上持续提供体积线索，所以用户仍能感到平。

此前的大阴影不是“阴影存在”本身的问题，而是阴影同时具备了三个错误特征：覆盖半个长波、压暗整条
填充链、且远层混白后仍朝很深的颜色或黑色移动。

## 固定帧定量比较

使用 Python `everythingdone` 环境、当前 `lighten_far=0.864`、默认光方位 `27°`、固定 `t=4s`
水面，按各层锚点沿 X 方向计算宏观法线的负向相对 `N·L`：

- 若把当前 D87 正向公式直接镜像为负向压暗，以 `0.5%` 相对亮度损失作为可感知门槛，L0～L3
  约有 `24%～35%` 顶点被影响，单段最大连续宽度约 `101～202px`；这仍是“大块阴影”。
- 采用下述阈值、深度衰减与波峰局部性后，L0/L1/L2/L3 的覆盖率约为
  `18%/12%/12%/5%`，最大连续宽度约 `61/81/82/36px`；L4 以后低于 `0.5%` 门槛。
- 当前默认色下，各层 `regularColor` 与 `deepColor` 的线性亮度差约为 `66%～91%`。若固定使用同一
  `deepColor` 混合比例，越远的浅层绝对压暗越强；要得到同样 `1.8%` 亮度损失，所需混合量会从
  L0 的约 `2.02%` 降到 L8 的约 `0.98%`。因此必须封顶最终亮度损失，不能只封顶混色比例。

这些数字只用于筛选公式，不代表最终视觉验收；动画中仍需观察阴影是否随波平滑移动、是否在层间同步，
以及高光经过时是否产生明暗跳变。

## 首选试验：受限直射光亏损

建议保留 D87 正向提亮，新增独立 `macroSunShadow`，首轮参数为：

```text
macro_shadow_luma_cap       = 0.018   # 最终线性亮度损失上限 1.8%，不是 deepColor 混合比
macro_shadow_ndl_start      = 0.080   # 小坡度死区；按最终像素从调研值 0.070 收紧
macro_shadow_ndl_full       = 0.180   # 强背坡才接近上限；按最终像素从 0.170 收紧
macro_shadow_far_start      = 0.350
macro_shadow_far_end        = 0.700   # 中远层归零，交还给 lighten_far 景深阶梯
macro_shadow_crest_start    = 0.005
macro_shadow_crest_full     = 0.080
macro_shadow_local_floor    = 0.300   # 从调研值 0.450 收紧，限制插值后的宽域底权重
```

概念公式：

```text
delta       = dot(macroNormal, L) - dot(referenceNormal, L)
backSlope   = smoothstep(0.08, 0.18, max(-delta, 0))
depthGate   = 1 - smoothstep(0.35, 0.70, depth01)
crestGate   = mix(0.30, 1.0, smoothstep(0.005, 0.08, crestPinch))
shadowMask  = backSlope * depthGate * crestGate
maxLoss     = baseLinearLuma * 0.018 * shadowMask
result      = 朝 deepColor 移动，但 resultLinearLuma >= baseLinearLuma - maxLoss
```

关键约束：

- 只使用连续水面的宏观 `aSlope`，不使用片元微法线生成填充阴影；微法线继续只服务细高光和反射。
- 阴影只削弱直射太阳的视觉份额；环境色、`lighten_far` 层间阶梯、SSS、HDR 银泽和体积底色保持。
- 朝 `deepColor` 移动只负责色相方向，最终以线性亮度损失封顶；绝不朝黑色或灰色混合。
- 首轮只覆盖近中层。若仍偏平，先把亮度封顶从 `1.8%` 提到最多约 `2.4%`，不要先放宽覆盖范围、
  降低坡度门槛或重新压暗远层。
- 若显脏，先把封顶降回 `1.2%～1.5%`；不要用整体抬亮补偿，因为那会破坏现有层间亮度阶梯。

## 备选方案

### 真正的太阳视线自阴影

沿太阳反方向在高度场上做 horizon/visibility 检查，只有前方波峰实际挡住太阳时才产生阴影，再做有限
太阳角半径软化。它比局部 `N·L` 更接近真实投影，但需要邻域采样或 CPU 预计算，容易带来跳变、锯齿和
额外帧成本。只有首选方案仍显人工时再做，且应继续只削弱直射项。

### 有色路径长度吸收

用 Beer–Lambert 让更长的水下路径朝身份色派生的深水色变化，可增加透明介质厚度感；但它不是阴影，
此前 `depth_scattering` 已证明宽域应用会让水体变脏。本轮不恢复，未来只能作为独立 A/B。

## 明确不采用

- `blackMix`、`base * (1-shadow)` 或整层降低曝光；
- 用微法线噪声制造主体暗纹；
- 把负向 D87 直接镜像到半个长波；
- 让阴影继续作用到已高度混白的远层；
- 用 AO、屏幕空间暗边或全局对比度代替直射光可见性；
- 同一版同时改阴影、`lighten_far`、环境色和高光强度。

## 下一步验收

若用户确认方向，先只改 Python 共享着色器注入策略或 Python 专属策略，保持 320dp 宽度，输出同一固定帧的
flat / D87 / D87+shadow 三张对照与 X 向亮度曲线，再进行动态目测。通过条件是：普通坡面可辨明暗转折；
不出现跨半层的灰暗块；远层不回脏；阴影随宏观波形平滑移动；关闭新参数可逐像素回到当前 D87 基线。

## 实施结果（2026-07-14）

Python ModernGL 与 QPainter 已完成上述试验，默认 `macro_shadow_luma_cap=0.018`，归零精确回到 D87。
初始调研门控在真正三角形插值后仍出现约 `133px` 的连续可感知
暗段，因此最终改用 `0.08～0.18` 的负向 `N·L` 门和 `0.30` 局部底权重。

最终 `t=4s` 固定帧按 `0.5%` 亮度差取样，ModernGL 最长连续暗段约 `73px`、QPainter 约
`49px`，最大 8-bit 实测相对亮度损失约 `1.42%`；中远层目标色仍由身份色派生，但门控在远层归零。
连续检查 `t=1～10s` 时，最坏暗段为 `135px`，该帧最大损失仅约 `0.92%`，说明较宽区域只保留
弱底权重，强阴影仍受 crest 与背坡共同局部化。104 项 Python 测试、`compileall`、diff 检查与双后端
离屏性能检查均通过。

用户随后确认方向并要求同步 Android。Android GLES 已将同一公式放入共享 `water.vert`，Canvas 回退
同步 deep 目标、crest 信号与线性亮度封顶；Python ModernGL 转为直接复用共享函数，只保留桌面端关闭
`depthScattering` 的覆盖，避免阴影重复注入。`lighten_far=0.864`、Python 320dp 和其它光学参数不变。
下一步由 Android 真机动态目测决定 `0.018` 是否足够。
