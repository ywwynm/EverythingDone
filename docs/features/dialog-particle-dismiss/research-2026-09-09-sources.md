# 2026-09-09 粒子消散调研来源台账

本轮实际审阅 **107 个不同 URL 的有效来源**，访问日期均为 2026-09-09。这里的来源指独立文档、论文或制作访谈，**不等于 107 家不同机构，也不等于 107 项独立实验**。重复 URL、正文缺失、重定向首页和仅有标题的候选不计数。

以官方文档、论文作者原文和制作主管访谈为主。表中“来源信息”是阅读所得，“本轮价值”是用于本项目的判断；官方软件能力不能证明华为内部采用同一实现。发布日期未核定的网页明确保留此状态，不以抓取日期充当发布日期。

完整正文、网页检索证据、抓取时间与散列保存在 `docs/features/dialog-particle-dismiss/tuning-0909-gpt6/research/`；本目录仅保留轻量总结。

## Houdini：释放、受力与属性

| 编号与来源 | 来源信息 | 本轮价值 |
| --- | --- | --- |
| H01 · [POP Advect by Volumes](https://www.sidefx.com/docs/houdini/nodes/dop/popadvectbyvolumes.html)<br>SideFX；页面未核定发布日期 | 速度场可作用于力、速度或位置；Treat as Wind 使速度追随目标而避免过冲。 | 采用连续受风响应；不把每帧位置随机改写当成流体运动。 |
| H02 · [POP Drag](https://www.sidefx.com/docs/houdini/nodes/dop/popdrag.html)<br>SideFX；页面未核定发布日期 | 阻力由目标速度和空气阻力控制，Ignore Mass 可解除粒子质量对响应的影响。 | 将响应时间常数与外观粒径分开，避免大粒永远滞后。 |
| H03 · [POP Wind](https://www.sidefx.com/docs/houdini/nodes/dop/popwind.html)<br>SideFX；页面未核定发布日期 | POP Wind 拉向环境风速，POP Force 则持续加速；噪声有空间尺度和时间尺度。 | 主风设置有限速度，局部扰动随时间连续演化。 |
| H04 · [POP Force](https://www.sidefx.com/docs/houdini/nodes/dop/popforce.html)<br>SideFX；页面未核定发布日期 | 随机力可以分别控制幅值、旋涡尺寸、时间周期、粗糙度和分形层数。 | 把形态尺度和运动强度分别调节，避免单个随机系数同时破坏二者。 |
| H05 · [POP Curve Force](https://www.sidefx.com/docs/houdini/nodes/dop/popcurveforce.html)<br>SideFX；页面未核定发布日期 | 沿曲线、趋向曲线和绕曲线的力可以独立控制；急弯会使粒子逸出影响半径。 | 局部卷流要有有限范围，不能把粒子永久锁到预画曲线。 |
| H06 · [POP Axis Force](https://www.sidefx.com/docs/houdini/nodes/dop/popaxisforce.html)<br>SideFX；页面未核定发布日期 | 轴向、轨道和吸入速度可分别定义，影响范围有柔和衰减。 | 用短暂切向运动增加局部弧度；全程保留主方向输运。 |
| H07 · [POP Attract](https://www.sidefx.com/docs/houdini/nodes/dop/popattract.html)<br>SideFX；页面未核定发布日期 | 吸引目标可以是单点、分布点或曲面点，目标匹配方式会改变粒群汇聚。 | 触点只确定总体风向，不把所有粒子吸成触点处的一团。 |
| H08 · [POP Grains](https://www.sidefx.com/docs/houdini/nodes/dop/popgrains.html)<br>SideFX；页面未核定发布日期 | PBD 粒子约束直接改位置，约束迭代和子步不足会改变刚度并导致弹跳或塌陷。 | 当前桌面首版不引入颗粒碰撞求解器；密度不靠硬球堆积产生。 |
| H09 · [POP Spin](https://www.sidefx.com/docs/houdini/nodes/dop/popspin.html)<br>SideFX；页面未核定发布日期 | 自旋角速度与粒子朝向是独立属性，并须明确弧度与角度单位。 | 近景粒子的受光变化跟随连续朝向，避免每帧随机闪亮。 |
| H10 · [POP Torque](https://www.sidefx.com/docs/houdini/nodes/dop/poptorque.html)<br>SideFX；页面未核定发布日期 | 力矩改变角速度，作用轴可随粒子局部坐标旋转。 | 片状颗粒的转动逐渐建立，不在出生帧突然旋转。 |
| H11 · [POP Source](https://www.sidefx.com/docs/houdini/nodes/dop/popsource.html)<br>SideFX；页面未核定发布日期 | 可按全部源点、随机源点或表面散点生成粒子。 | 使用覆盖原控件的固定源采样，使每个单元仅释放一次。 |
| H12 · [POP Replicate](https://www.sidefx.com/docs/houdini/nodes/dop/popreplicate.html)<br>SideFX；页面未核定发布日期 | 复制粒子可扰动出生时间并插值源位置，以消除高速发射时逐帧成团。 | 出生时间保持亚帧精度；不按导出帧率成批生成灰尘。 |
| H13 · [POP Color](https://www.sidefx.com/docs/houdini/nodes/dop/popcolor.html)<br>SideFX；页面未核定发布日期 | 颜色和 Alpha 是独立属性，Ramp 能依据每粒子变量渐进变化。 | 保留源纹理色；覆盖衰减和局部受光分别控制。 |
| H14 · [POP Property](https://www.sidefx.com/docs/houdini/nodes/dop/popproperty.html)<br>SideFX；页面未核定发布日期 | 可分别设粒径、质量及旋转形状；旋转惯量来自形状和尺度。 | 可视尺寸分布与响应参数显式分离，记录艺术控制而非冒充物理推导。 |
| H15 · [POP Solver](https://www.sidefx.com/docs/houdini/nodes/dop/popsolver.html)<br>SideFX；页面未核定发布日期 | 求解器以速度、阻力、力矩更新粒子，CFL 约束子步位移。 | 仿真步长独立于视频播放倍率，并检查较小步长是否改变形态。 |
| H16 · [POP Steer Seek](https://www.sidefx.com/docs/houdini/nodes/dop/popsteerseek.html)<br>SideFX；页面未核定发布日期 | Steer Seek 可输出普通粒子力或群体引导力，多个力可有权重。 | 主风与次级流动通过连续权重组合，不按区域硬切不同动画。 |
| H17 · [Curl Noise](https://www.sidefx.com/docs/houdini/nodes/vop/curlnoise.html)<br>SideFX；页面未核定发布日期 | curl 噪声由势场导数构造无散度速度，也可处理绕障碍物的流动。 | 用于局部相干卷动；不将任意坐标噪声称为无散度。 |
| H18 · [Anti-Aliased Noise](https://www.sidefx.com/docs/houdini/nodes/vop/aanoise.html)<br>SideFX；页面未核定发布日期 | 抗锯齿噪声按位置导数限制频带；坐标系错误会让噪声穿过表面。 | 释放随机性锁定源坐标，屏幕细节频率随像素采样限制。 |
| H19 · [Unified Noise](https://www.sidefx.com/docs/houdini/nodes/vop/unifiednoise.html)<br>SideFX；页面未核定发布日期 | Perlin Flow 通过随时间旋转产生平滑流动；不同噪声具有不同几何特征。 | 前沿只用少量低频连续变化；避免 Worley 蜂窝成为主要轮廓。 |
| H20 · [Scatter](https://www.sidefx.com/docs/houdini/nodes/sop/scatter.html)<br>SideFX；页面未核定发布日期 | 散点可按面积密度或纹理分布，并可抑制成团和空洞。 | 采样按面板实际像素面积确定，避免长弹窗的粒密下降。 |
| H21 · [Attribute Noise](https://www.sidefx.com/docs/houdini/nodes/sop/attribnoise.html)<br>SideFX；页面未核定发布日期 | 可给属性添加相干噪声，并用已有属性控制混合权重。 | 邻域共享速度与法线扰动，末段才逐步增加独立差异。 |
| H22 · [Attribute Transfer](https://www.sidefx.com/docs/houdini/nodes/sop/attribtransfer.html)<br>SideFX；页面未核定发布日期 | 属性可按空间邻近加权传递；同拓扑时直接复制更合适。 | 颜色和释放状态直接使用源单元身份，不靠飞出后的屏幕坐标重新采色。 |
| H23 · [Attribute Blur](https://www.sidefx.com/docs/houdini/nodes/sop/attribblur.html)<br>SideFX；页面未核定发布日期 | 属性平滑可依据邻接和边长进行，不只平滑几何位置。 | 平滑释放时间和局部方向，保持尚未释放的内容位置不变。 |
| H24 · [Pyro Source](https://www.sidefx.com/docs/houdini/nodes/sop/pyrosource.html)<br>SideFX；页面未核定发布日期 | Pyro Source 在源点上赋予颜色和颜色密度，再栅格化到体积。 | 把彩色贡献和可见覆盖分开，控制源内容颜色不会被白粒淹没。 |
| H25 · [Volume Rasterize Attributes](https://www.sidefx.com/docs/houdini/nodes/sop/volumerasterizeattributes.html)<br>SideFX；页面未核定发布日期 | 点的浮点或向量属性可栅格化成场，并按来源属性分组。 | 独立输出覆盖率、颜色和速度场用于评估，避免仅用合成亮度推断粒密。 |
| H26 · [VDB Smooth SDF](https://www.sidefx.com/docs/houdini/nodes/sop/vdbsmoothsdf.html)<br>SideFX；页面未核定发布日期 | 曲率流沿法向平滑凹凸，同时维持有效 SDF。 | 释放场交汇处做有限尺度平滑，防止尖谷；不对整张动画模糊。 |
| H27 · [VDB Reshape SDF](https://www.sidefx.com/docs/houdini/nodes/sop/vdbreshapesdf.html)<br>SideFX；页面未核定发布日期 | SDF 的膨胀、侵蚀、开闭运算改变轮廓和孤立区域，单位可为体素或世界坐标。 | 分析边界时明确屏幕像素单位；避免靠过度闭运算掩盖细碎缺口。 |
| H28 · [Vellum Constraints](https://www.sidefx.com/docs/houdini/nodes/sop/vellumconstraints.html)<br>SideFX；页面未核定发布日期 | 布料和软体由显式约束描述，几何与约束图需保持对应。 | 完整布面方案成本与语义均较重，当前保留为备选，不驱动完整控件变形。 |
## 实时引擎与 Blender：粒子生命周期和渲染

| 编号与来源 | 来源信息 | 本轮价值 |
| --- | --- | --- |
| E01 · [Unity MainModule](https://docs.unity3d.com/Manual/PartSysMainModule.html)<br>Unity；页面未核定发布日期 | 系统持续时间、出生延迟、初速度和粒子寿命分别控制。 | 区分释放结束和尾粒结束；不依赖视频截断收尾。 |
| E02 · [Unity EmissionModule](https://docs.unity3d.com/Manual/PartSysEmissionModule.html)<br>Unity；页面未核定发布日期 | 发射率可按时间或移动距离定义，瞬时发射有独立时刻和概率。 | 采用源面积释放，不用全局固定每秒粒数造成前沿忽稀忽密。 |
| E03 · [Unity ShapeModule](https://docs.unity3d.com/Manual/PartSysShapeModule.html)<br>Unity；页面未核定发布日期 | 可由纹理为粒子着色或剔除，并按发射方向设朝向。 | 只采样控件 Alpha 内的真实内容，圆角和透明区不发射背景像素。 |
| E04 · [Unity VelocityOverLifetimeModule](https://docs.unity3d.com/Manual/PartSysVelOverLifeModule.html)<br>Unity；页面未核定发布日期 | 线性、轨道、径向速度与局部／世界坐标可分别配置。 | 建立统一方向坐标，八方向旋转包括全部次级运动。 |
| E05 · [Unity InheritVelocityModule](https://docs.unity3d.com/Manual/PartSysInheritVelocity.html)<br>Unity；页面未核定发布日期 | Initial 只继承出生时的速度，Current 会一直追随源速度。 | 离开后保持连续运动，但逐步解除与源材料坐标的机械绑定。 |
| E06 · [Unity ForceOverLifetimeModule](https://docs.unity3d.com/Manual/PartSysForceOverLifeModule.html)<br>Unity；页面未核定发布日期 | 逐帧重新随机力方向会产生更不规则和不稳定的运动。 | 禁止用逐帧白噪声制造灵动，改为时间连续扰动。 |
| E07 · [Unity LimitVelocityOverLifetimeModule](https://docs.unity3d.com/Manual/PartSysLimitVelOverLifeModule.html)<br>Unity；页面未核定发布日期 | 速度上限、线性阻力和按粒径／速度加权的阻力是不同控制。 | 给速度与横向范围各自的限制，避免后半段无限扩散。 |
| E08 · [Unity ColorOverLifeModule](https://docs.unity3d.com/Manual/PartSysColorOverLifeModule.html)<br>Unity；页面未核定发布日期 | 颜色与透明度可以按归一化生命进度变化。 | 寿命末段平滑退出，维持颜色身份而不统一变白。 |
| E09 · [Unity SizeOverLifeModule](https://docs.unity3d.com/Manual/PartSysSizeOverLifeModule.html)<br>Unity；页面未核定发布日期 | 粒子大小可以用生命期曲线按各轴独立变化。 | 保留少量薄片的长短轴变化，大多数细粒不无限膨胀。 |
| E10 · [Unity RotationOverLifeModule](https://docs.unity3d.com/Manual/PartSysRotOverLifeModule.html)<br>Unity；页面未核定发布日期 | 角速度可分别作用于三轴。 | 以连续旋转改变薄片投影和明暗，避免随机缩放代替空间感。 |
| E11 · [Unity NoiseModule](https://docs.unity3d.com/Manual/PartSysNoiseModule.html)<br>Unity；页面未核定发布日期 | 低频噪声转向柔和，高频转向急；更多层数有性能代价。 | 主轮廓和细粒分别选尺度，先减高频再讨论增加粒数。 |
| E12 · [Unity RendererModule](https://docs.unity3d.com/Manual/PartSysRendererModule.html)<br>Unity；页面未核定发布日期 | Billboard、Stretched Billboard、Mesh 决定形状、朝向与过绘。 | 新生片段保留纹理，远处细粒用小面片；不普遍拉成线。 |
| E13 · [Blender force_field](https://docs.blender.org/manual/en/latest/physics/forces/force_fields/types/force.html)<br>Blender；页面未核定发布日期 | 正负径向力从对象中心向外或向内作用。 | 把中心爆散列为不采用的对照，避免误把它当有方向消散。 |
| E14 · [Blender wind](https://docs.blender.org/manual/en/latest/physics/forces/force_fields/types/wind.html)<br>Blender；页面未核定发布日期 | Blender Wind 是沿局部 Z 轴的恒定力。 | 不同工具的 Wind 语义不一致；本模型明确采用目标速度阻力定义。 |
| E15 · [Blender vortex](https://docs.blender.org/manual/en/latest/physics/forces/force_fields/types/vortex.html)<br>Blender；页面未核定发布日期 | 旋涡力绕轴旋转，并可另加向内流动分量。 | 旋涡与吸入不捆绑，局部卷动不应导致中心聚团。 |
| E16 · [Blender turbulence](https://docs.blender.org/manual/en/latest/physics/forces/force_fields/types/turbulence.html)<br>Blender；页面未核定发布日期 | 湍流尺度可相对于对象或世界定义。 | 以面板短边定尺度，保证不同长宽比下视觉强度可比。 |
| E17 · [Blender drag](https://docs.blender.org/manual/en/latest/physics/forces/force_fields/types/drag.html)<br>Blender；页面未核定发布日期 | 阻力可与速度或速度平方成正比。 | 当前使用可控的一阶响应，二次阻力保留为后续物理对照。 |
| E18 · [Blender texture](https://docs.blender.org/manual/en/latest/physics/forces/force_fields/types/texture.html)<br>Blender；页面未核定发布日期 | 纹理可直接表示向量，或通过梯度、curl 得到力，差分步长可调。 | 使用势场 curl 时记录差分单位，不混淆方向场与标量释放场。 |
| E19 · [Blender Simulation Zone](https://docs.blender.org/manual/en/latest/modeling/geometry_nodes/simulation/simulation_zone.html)<br>Blender；页面未核定发布日期 | 仿真区状态逐帧传递，需显式保存后续所需属性，支持亚帧插值。 | 缓存明确包含源身份、出生时间和轨迹，拖动时间可复现。 |
| E20 · [Blender Distribute Points on Faces](https://docs.blender.org/manual/en/latest/modeling/geometry_nodes/point/distribute_points_on_faces.html)<br>Blender；页面未核定发布日期 | 表面散点继承源属性并具有稳定 ID；泊松采样有最小距离限制。 | 随机种子按固定源 ID，重播与导出使用同一组粒子。 |
| E23 · [Unity VFX Graph Simulation Spaces](https://docs.unity3d.com/Packages/com.unity.visualeffectgraph@17.0/manual/Systems.html)<br>Unity；页面未核定发布日期 | 系统可共享发射器或 GPU 事件，仿真空间可为对象局部或世界。 | 所有颜色共用同一系统；控件坐标和全屏坐标只转换一次。 |
| E24 · [Unity VFX Graph Curl Noise Force](https://docs.unity3d.com/Packages/com.unity.visualeffectgraph@17.0/manual/Block-Force.html)<br>Unity；页面未核定发布日期 | Relative Force 使速度趋向目标，速率取决于阻力和质量。 | 用指数风响应构造可解释的慢起、加速和转向。 |
## After Effects、Trapcode 与制作教程

| 编号与来源 | 来源信息 | 本轮价值 |
| --- | --- | --- |
| A01 · [Trapcode Particular emitter](https://help.maxon.net/rg/en-us/Content/html/15-Trapcode-Particular-emitter.html)<br>Maxon；页面未核定发布日期 | 发射器决定出生的位置、初速度和方向；动画烘焙需匹配合成帧率。 | 释放场只定义出生，出生之后交给独立连续输运；导出保持统一时间。 |
| A02 · [Trapcode Particular emitter-layer-emitter](https://help.maxon.net/rg/en-us/Content/html/19-Trapcode-Particular-emitter-layer-emitter.html)<br>Maxon；页面未核定发布日期 | Layer Emitter 可在出生时采样图层并终生保留，或每帧重新采样。 | 采用出生采样，粒子携带原内容，避免移动后把背景颜色吸入。 |
| A03 · [Trapcode Particular particle](https://help.maxon.net/rg/en-us/Content/html/22-Trapcode-Particular-particle.html)<br>Maxon；页面未核定发布日期 | 每粒子的寿命在出生时确定，寿命随机性可以避免同时死亡。 | 用稳定随机寿命和有界终点共同收尾，避免整群最后一帧突然消失。 |
| A04 · [Trapcode Particular particle-type](https://help.maxon.net/rg/en-us/Content/html/23-Trapcode-Particular-particle-type.html)<br>Maxon；页面未核定发布日期 | 球点、发光球、云团、长曝光条和纹理精灵具有不同视觉含义。 | 细粒采用克制颗粒与纹理片段，不用大高斯云团覆盖前沿。 |
| A05 · [Trapcode Particular particle-physics](https://help.maxon.net/rg/en-us/Content/html/27-Trapcode-Particular-particle-physics.html)<br>Maxon；页面未核定发布日期 | 质量与空气阻力能独立随机，大小默认不必影响质量或阻力。 | 屏幕粒径不直接等同物理灰片直径，动力学参数需要独立验证。 |
| A06 · [Trapcode Particular environment](https://help.maxon.net/rg/en-us/Content/html/29-Trapcode-Particular-environment.html)<br>Maxon；页面未核定发布日期 | 环境可由多粒群继承；真实风会改变后续路径，Drift 只整体偏移。 | 所有源色共用同一场，曲折来自连续运动而非位置装饰。 |
| A07 · [Trapcode Particular physics-simulations](https://help.maxon.net/rg/en-us/Content/html/30-Trapcode-Particular-physics-simulations.html)<br>Maxon；页面未核定发布日期 | 旧版路径仿真速度快但可能不自然，新物理支持游走、群体和流体。 | 将简洁解析路径与状态积分作取舍，不能只因快速而忽视机械同步。 |
| A08 · [Trapcode Particular physics-fluid](https://help.maxon.net/rg/en-us/Content/html/34-Trapcode-Particular-physics-fluid.html)<br>Maxon；页面未核定发布日期 | 流体力可选浮力旋涡、涡环或涡管，并限制作用区域及是否持续施加。 | 短时局部卷流只占局部区域，不生成贯穿整卡的永久涡管。 |
| A09 · [Trapcode Particular displace-drift-and-spin](https://help.maxon.net/rg/en-us/Content/html/35-Trapcode-Particular-displace-drift-and-spin.html)<br>Maxon；页面未核定发布日期 | Drift 均匀移位；Spin 的半径、频率及渐入时间分别可调。 | 次级转动平滑渐入，并受粒子年龄限制。 |
| A10 · [Trapcode Particular displace-turbulence-field](https://help.maxon.net/rg/en-us/Content/html/38-Trapcode-Particular-displace-turbulence-field.html)<br>Maxon；页面未核定发布日期 | 相邻粒子获得相似而非相同的扰动，噪声场可部分随风漂移。 | 粒群保持邻域相干；噪声坐标随主输运移动，减少固定纹理感。 |
| A11 · [Trapcode Particular layer-maps](https://help.maxon.net/rg/en-us/Content/html/41-Trapcode-Particular-layer-maps.html)<br>Maxon；页面未核定发布日期 | 图层灰度可分别控制颜色、Alpha、位移、尺寸、扰动及旋转；采样时机因行为而异。 | 释放掩码和材质属性有明确语义，避免同一图错误驱动多个不相干量。 |
| A12 · [Trapcode Particular layer-maps_turbulence-strength](https://help.maxon.net/rg/en-us/Content/html/45-Trapcode-Particular-layer-maps_turbulence-strength.html)<br>Maxon；页面未核定发布日期 | 空间图可调节局部扰动强度，黑区不受扰动。 | 对未释放区域严格关闭粒子位移，对前沿局部增强流动。 |
| A13 · [Trapcode Particular render-motion-blur](https://help.maxon.net/rg/en-us/Content/html/57-Trapcode-Particular-render-motion-blur.html)<br>Maxon；页面未核定发布日期 | 运动模糊的快门角控制轨迹长度，增加时间采样会增加渲染负担。 | 细粒只保留短曝光；慢放从更密时间采样输出，不重复一倍速帧冒充高帧率。 |
| A14 · [AE 模拟效果](https://helpx.adobe.com/after-effects/using/simulation-effects.html)<br>Adobe；页面未核定发布日期 | Shatter 可以用梯度图精确控制局部爆裂次序，范围之外保持原图。 | 借鉴共享局部释放场，不采用爆炸初速和厚块。 |
| A15 · [AE 噪声与颗粒](https://helpx.adobe.com/after-effects/using/noise-grain-effects.html)<br>Adobe；页面未核定发布日期 | Fractal Noise 的 Evolution 连续改变形态，动画化 Random Seed 会闪烁。 | 前沿时间变化连续，随机种子只在重播或变体间更换。 |
| A16 · [AE 通道效果](https://helpx.adobe.com/after-effects/using/channel-effects.html)<br>Adobe；页面未核定发布日期 | Set Matte 可由同一图层通道驱动多个图层，并与原 Alpha 组合。 | 控件圆角 Alpha 与释放覆盖相乘，完整层和粒子层读取同一状态。 |
| A17 · [AE 色彩管理](https://helpx.adobe.com/after-effects/using/color-management.html)<br>Adobe；页面未核定发布日期 | 线性光混合能避免高对比饱和色交界的色边，并宜用较高颜色精度。 | 合成使用浮点线性光，输出时再编码为显示色域。 |
| A19 · [Video Copilot Disintegration](https://www.videocopilot.net/tutorials/disintegration/)<br>Video Copilot；页面未核定发布日期 | 作者的消散教程明确组合位移、湍流与粒子系统。 | 借鉴分阶段组成，不把单一溶解遮罩等同完整消散效果。 |
| A20 · [Video Copilot Procedural Crumbling](https://www.videocopilot.net/blog/2011/02/procedural-crumbling-in-ae/)<br>Video Copilot；页面未核定发布日期 | 作者的破碎方案按开裂、碎片剥落、灰尘和粒子组合。 | 材质变化先在原位建立，再渐进进入飞离，避免瞬间变稀。 |
| A21 · [Maxon Cheap Tricks Infinity and Beyond](https://www.maxon.net/en/article/cheap-tricks-3-infinity-and-beyond)<br>Maxon；页面未核定发布日期 | 官方 AE 教程提供内置工具的化灰方法及 Particular 增强方法。 | 插件只是一种制作途径；桌面原型用可复现代码实现同类组织。 |
## 影视制作与动画设计

| 编号与来源 | 来源信息 | 本轮价值 |
| --- | --- | --- |
| F01 · [Phoenix Disintegration](https://www.sidefx.com/tutorials/houdini-tutorial-1-phoenix-disintegration/)<br>SideFX；页面未核定发布日期 | SideFX 的 Phoenix 分解教程将扩散、碎裂和动力学关联，并保留释放组。 | 先建立连续释放场，再将同一个释放时间交给全部碎片。 |
| F05 · [Rebelway Disintegration Part 2](https://www.rebelway.net/disintegration-fx-in-houdini-tutorial-simulation-part2)<br>Rebelway；页面未核定发布日期 | Rebelway 教程分开主要粒子、碎片与辅助烟雾的制作阶段。 | 先解决主轮廓及运动；不依赖烟雾掩盖碎裂接缝。 |
| F06 · [Weta Endgame 制作访谈](https://www.artofvfx.com/avengers-endgame-matt-aitken-vfx-supervisor-sidney-kombo-kintombo-animation-supervisor-gerardo-aguilera-fx-supervisor-weta-digital/)<br>Art of VFX／Weta 制作主管访谈；页面未核定发布日期 | 《复仇者联盟4》Weta 制作主管访谈说明先审批双色消散遮罩，再将同一生长算法一比一映射到最终效果；专门速度场控制灭霸消散。 | 将释放设计与输运设计分开审阅，同时保持同源时钟。 |
| F07 · [Weta Infinity War 制作访谈](https://www.artofvfx.com/avengers-infinity-war-paul-story-animation-supervisor-with-sean-walker-cg-supervisor-ashraf-ghoniem-and-gerardo-aguilera-fx-supervisors-weta-digital/)<br>Art of VFX／Weta 制作主管访谈；页面未核定发布日期 | 《复仇者联盟3》Weta 制作主管说明使用粒子与体积的传播、多层粒子密度避免空心感，片状灰烬保持连接后弯曲。 | 用短时相干微片与不同密度构成立体感；不把平面控件做整体翻转。 |
| F08 · [Timing](https://www.animationmentor.com/blog/timing-the-12-basic-principles-of-animation/)<br>Animation Mentor；页面未核定发布日期 | Animation Mentor 的 Timing 教程区分快慢运动与停留时间，强调观察重量表现。 | 分别调节释放时长、响应滞后与尾部停留。 |
| F09 · [Slow In Slow Out](https://www.animationmentor.com/blog/slow-in-and-slow-out-the-12-basic-principles-of-animation/)<br>Animation Mentor；页面未核定发布日期 | Slow In and Slow Out 教程区分总时间与逐帧间距；等间距常显机械。 | 不以单一 ease 曲线包办全部阶段，检查粒群实际位移。 |
| F10 · [Staging](https://www.animationmentor.com/blog/staging-the-12-basic-principles-of-animation/)<br>Animation Mentor；页面未核定发布日期 | Staging 教程强调构图、层次与注意力的引导。 | 控制高密度区域的位置与数量，避免全边同时突出。 |
| F11 · [Arc](https://www.animationmentor.com/blog/arc-the-12-basic-principles-of-animation/)<br>Animation Mentor；页面未核定发布日期 | Arc 教程强调物体路径的连续性，突然转折需要运动原因。 | 卷流与主风之间连续过渡，不每帧重抽随机方向。 |
| F12 · [Follow Through Overlapping Action](https://www.animationmentor.com/blog/follow-through-and-overlapping-action-the-12-basic-principles-of-animation/)<br>Animation Mentor；页面未核定发布日期 | Follow Through and Overlapping Action 教程以惯性、延迟和后续运动组织自然感。 | 碎片延迟响应主风，主体消失后仍保留短暂衰减尾部。 |
| F13 · [Secondary Action](https://www.animationmentor.com/blog/secondary-action-the-12-basic-principles-of-animation/)<br>Animation Mentor；页面未核定发布日期 | Secondary Action 教程明确区分支持叙事的附加动作与由主运动带动的次级运动。 | 将粒子的细微摆动称为次级运动，不误用叙事动作原则。 |
| F14 · [Timing Contrast](https://www.animationmentor.com/blog/why-your-animation-needs-contrast-part-2-timing/)<br>Animation Mentor；页面未核定发布日期 | Timing Contrast 教程强调快慢对比，恒定间距缺少变化。 | 前沿推进、解体、扩散应有阶段差异，避免匀速消失。 |
| F15 · [Anticipation](https://www.animationmentor.com/blog/anticipation-the-12-basic-principles-of-animation/)<br>Animation Mentor；页面未核定发布日期 | Anticipation 教程指出准备动作可很细微，不必总是向反方向大幅移动。 | 允许材料局部预松解，但不移动未释放控件。 |
| F16 · [Appeal](https://www.animationmentor.com/blog/appeal-the-12-basic-principles-of-animation/)<br>Animation Mentor；页面未核定发布日期 | Appeal 教程将吸引力视为呈现方式与多种原则共同作用的结果。 | 像素差与时长指标仅作诊断，最终仍需用户视觉验收。 |
## 流体、采样、材质及合成研究

| 编号与来源 | 来源信息 | 本轮价值 |
| --- | --- | --- |
| R01 · [Curl-Noise for Procedural Fluid Flow](https://www.cs.ubc.ca/~rbridson/docs/bridson-siggraph2007-curlnoise.pdf)<br>UBC／论文作者；2007 | Curl-Noise 通过势场的旋度产生无散度速度；空间强度调制应先作用于势场。 | 采用连续局部卷流；年龄阻尼与主风属于艺术控制，不宣称完整系统严格无散度。 |
| R02 · [Evolving Sub-Grid Turbulence for Smoke Animation](https://www.cs.ubc.ca/~rbridson/docs/schechter-sca08-turbulence.pdf)<br>UBC／论文作者；2008 | Sub-Grid Turbulence 追踪各频段能量与涡旋的演化，再补充小尺度细节。 | 细节应随粒群输运并演化，不使用每帧独立噪声。 |
| R03 · [Wavelet Turbulence for Fluid Simulation](https://www.cs.cornell.edu/~tedkim/WTURB/wavelet_turbulence.pdf)<br>Cornell／论文作者；2008 | Wavelet Turbulence 允许大尺度运动与小尺度细节独立编辑，并维持时间相干。 | 先验收主运动，再加细碎摆动，不能靠增加噪声修正错误走向。 |
| R04 · [Hashed Alpha Testing](https://research.nvidia.com/labs/rtr/publication/wyman2017hashed/)<br>NVIDIA／论文作者；2017 | Hashed Alpha Testing 以稳定散列阈值改善细薄几何的覆盖及时间稳定性。 | 粒子采样与消退随机数绑定身份；覆盖散列本身不等于碎裂动力学。 |
| R05 · [Weighted Blended Order-Independent Transparency](https://jcgt.org/published/0002/02/09/paper.pdf)<br>JCGT／论文作者；2013 | Weighted Blended OIT 使用有界存储近似多层透明覆盖，颜色结果不同于精确排序。 | 高密度粒子不能用无约束相加混合；优先检验遮挡与背景透过率。 |
| R06 · [Spatiotemporal Blue Noise Masks](https://research.nvidia.com/publication/2022-07_spatiotemporal-blue-noise-masks)<br>NVIDIA／论文作者；2022 | Spatiotemporal Blue Noise 指出逐帧独立的蓝噪声并不自动拥有良好的时间性质。 | 固定种子并连续演化；不将普通散列或抖动网格误称为时空蓝噪声。 |
| R07 · [Fast Poisson Disk Sampling](https://www.cs.ubc.ca/~rbridson/docs/bridson-siggraph07-poissondisk.pdf)<br>UBC／论文作者；2007 | Bridson 的 Poisson Disk 算法通过最小点距、背景网格与活动列表达到线性复杂度。 | 稀疏尘粒可采用有间距约束采样，密集材料层另行保证原图覆盖。 |
| R09 · [GPU Gems High-Speed Off-Screen Particles](https://developer.nvidia.com/gpugems/gpugems3/part-iv-image-effects/chapter-23-high-speed-screen-particles)<br>NVIDIA GPU Gems；页面未核定发布日期 | Off-Screen Particles 通过低分辨率渲染降低过绘，但细节锐利的碎屑不适合一概降采样。 | 主体微片保留足够分辨率，不能把离屏低清造成的模糊当成轻盈。 |
| R10 · [GPU Gems Fast Fluid Dynamics Simulation on the GPU](https://developer.nvidia.com/gpugems/gpugems/part-vi-beyond-triangles/chapter-38-fast-fluid-dynamics-simulation-gpu)<br>NVIDIA GPU Gems；页面未核定发布日期 | GPU 流体教程将输运、外力与压力处理区分；速度场也会被自身输运。 | 区分材料的位置、控制风场与场内局部运动，避免混为单一位移曲线。 |
| R11 · [GPU Gems Implementing Improved Perlin Noise](https://developer.nvidia.com/gpugems/gpugems2/part-iii-high-quality-rendering/chapter-26-implementing-improved-perlin-noise)<br>NVIDIA GPU Gems；页面未核定发布日期 | Improved Perlin Noise 强调确定性、平移统计性质、带限频率及实现一致性。 | 用连续多尺度场调制释放和运动，并保存随机种子。 |
| R12 · [GPU Gems Importance of Being Linear](https://developer.nvidia.com/gpugems/gpugems3/part-iv-image-effects/chapter-24-importance-being-linear)<br>NVIDIA GPU Gems；页面未核定发布日期 | Importance of Being Linear 说明纹理、光照与帧缓冲的伽马处理影响正确滤波和混合。 | 在线性空间合成碎片后编码 sRGB，检查白底和高饱和颜色。 |
| R13 · [IQ Domain Warping](https://iquilezles.org/articles/warp/)<br>Inigo Quilez；页面未核定发布日期 | Domain Warping 通过在函数采样位置加入另一个场构造自然不规则形态。 | 仅扭曲释放时间场；未释放源图的纹理坐标保持不变。 |
| R14 · [IQ Smooth Minimum](https://iquilezles.org/articles/smin/)<br>Inigo Quilez；页面未核定发布日期 | Smooth Minimum 在多个距离场接近时平滑合并，避免硬最小值形成接缝。 | 多起点传播的合流处使用平滑合并，防止尖锐切口。 |
| R15 · [IQ Fractional Brownian Motion](https://iquilezles.org/articles/fbm/)<br>Inigo Quilez；页面未核定发布日期 | fBM 叠加相关的频率与振幅，较小结构通常具有更低振幅。 | 将轮廓、中尺度凹凸与微细释放扰动分级，不平均堆叠各频段。 |
| R16 · [IQ More Noise](https://iquilezles.org/articles/morenoise/)<br>Inigo Quilez；页面未核定发布日期 | More Noise 推导值噪声的解析导数，并说明其精度与成本优势。 | 需要方向信息时从连续场导数获取，而非对每个粒子随意指定法线。 |
| R17 · [IQ Useful Functions](https://iquilezles.org/articles/functions/)<br>Inigo Quilez；页面未核定发布日期 | Useful Functions 给出平滑阈值与先升后降的脉冲包络等信号构造。 | 用连续包络控制松解、卷动和消退，避免阶段开关突变。 |
| R18 · [PBRT Volume Scattering Processes](https://pbr-book.org/4ed/Volume_Scattering/Phase_Functions)<br>PBRT 作者；页面未核定发布日期 | PBRT 相函数描述介质散射的角度分布，单个非对称参数不能唯一决定分布。 | 薄片表面明暗与尘埃散射不能混用；本轮只做有限的表面深度提示。 |
| R19 · [PBRT Image Reconstruction](https://pbr-book.org/3ed-2018/Sampling_and_Reconstruction/Image_Reconstruction)<br>PBRT 作者；页面未核定发布日期 | Image Reconstruction 要求重建、低通预滤波与像素采样，点采样无法稳定表示所有高频边界。 | 使用空间抗锯齿与时间采样保留细粒，检查慢放中闪烁。 |
| R20 · [PBRT Microfacet Models](https://pbr-book.org/3ed-2018/Reflection_Models/Microfacet_Models)<br>PBRT 作者；页面未核定发布日期 | Microfacet Models 将粗糙度与微表面法线的分布联系，受照亮度来自集合统计。 | 用小幅相关法线变化表现材质，不给全部颗粒统一亮边。 |
| R21 · [PBRT Image Texture](https://pbr-book.org/3ed-2018/Texture/Image_Texture)<br>PBRT 作者；页面未核定发布日期 | Image Texture 通过纹理重建和 MIPMap 过滤减少缩小后的混叠。 | 保持源色纹理采样，缩小微片时平滑过渡到平均色。 |
| R22 · [NASA Drag Equation](https://www1.grc.nasa.gov/beginners-guide-to-aeronautics/drag-equation/)<br>NASA Glenn；页面未核定发布日期 | NASA 阻力方程包含流体密度、相对速度平方、面积和形状相关系数。 | 阻力响应有物理依据，但屏幕像素与秒不能冒充真实材料单位；本轮采用可控近似。 |
| R23 · [Falling Paper Aerodynamics](https://dragonfly.tam.cornell.edu/publications/2005_JFM_Andersen_Pesavento_Wang_b.pdf)<br>Cornell／论文作者；2005 | 落纸研究发现摆动、翻滚和稳定下落取决于长宽厚度、惯性与雷诺数等无量纲参数。 | 少量薄片可有连续摆动；不让每个微粒剧烈翻滚，也不直接照搬真实频率。 |
| R24 · [Fluid Simulation SIGGRAPH Course](https://www.cs.ubc.ca/~rbridson/fluidsimulation/fluids_notes.pdf)<br>UBC／论文作者；2007 | 流体课程的半拉格朗日方法从终点回溯旧位置来输运场；粒子法随位置移动即可保存携带量。 | 颜色绑定源材料身份，固定步长输运位置，避免逐帧重采背景颜色。 |
| R26 · [Weighted Blended OIT Implementation](https://casual-effects.blogspot.com/2015/03/implemented-weighted-blended-order.html)<br>Morgan McGuire；2015-03-26 | OIT 作者的实现说明指出相邻深度层区分会减弱，权重需要按应用深度范围调节。 | 透明合成是可比较的工程取舍；先用可控深度排序与正常覆盖验证层次。 |

## 未计入的候选

E21、E22：地址无有效正文；A18：未完成正文审阅；F02、F03：获取内容不足以形成有效制作结论；F04：访问受限且未核实正文；R08：跳转到机构首页；R25：文件失效。F11 已纠正页面地址并重新取得正文。

不同文档有相近术语，台账只计页面一次；模型设计按问题归纳，不把同一结论重复出现的次数当成证据强度。
