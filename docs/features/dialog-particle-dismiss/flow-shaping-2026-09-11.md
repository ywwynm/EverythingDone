# 流动形变、几何尺度与触点距离

## 用户指出的问题与可证伪假设

1. 上一轮延拓的速度过于平滑，而额外旋度仅乘了 `0.02`。如果这是机械平移的来源，加入随主流迁移的局部旋度后，同一材料群扣除质心位移的残余形变应增加，而可信主流变化应较小。
2. 速度和释放坐标都按完整宽、高缩放。如果这造成长弹窗的尖峰，限制长轴的物理尺度后，长宽比增大不应再按比例放大涡旋和前沿的尖锐折弯。
3. Android 把触点替换成固定半径的虚拟点。如果这是远近无差别的来源，保留触点到控件边缘的距离并传入共享模型后，相同方向、种子的三档输入应得到不同的纵向行程与轮廓。

先保留上一版资源、代码及主要素材视频缓存，再做同材料、同种子、同时刻的数值和画面对比。上一轮“有位移”的指标不能证明形状有变化，本轮增加扣除群体平移后的形变检查。

## 调研依据与取舍

以下是本轮触点与形变问题的补充调研；此前 107 条来源仍保存在 `research-2026-09-09-sources.md`，不重复计数。

| 来源 | 有效信息与本轮取舍 |
| --- | --- |
| [Bridson 等：Curl-Noise for Procedural Fluid Flow](https://www.cs.ubc.ca/~rbridson/docs/bridson-siggraph2007-curlnoise.pdf) | 从势函数取旋度可得到空间连续、无散度的局部运动；叠加递减频段并随时间演化。只增加独立随机速度容易形成汇聚点。补充细节使用相干空间场，不用逐片随机转向。 |
| [Kim 等：Wavelet Turbulence](https://www.cs.cornell.edu/~tedkim/WTURB/) | 分离大尺度主流与新增细节，保持新增结构的时间连续性。本轮保留已有主流，以观测可信度控制补充形变，不重做所有区域。 |
| [Houdini Curl Noise](https://www.sidefx.com/docs/houdini/nodes/vop/curlnoise.html) | 频率、幅度、粗糙度、时间分别控制；尺度应以短边的实际像素长度定义，避免 UV 长轴拉伸。 |
| [Houdini Curl Noise COP](https://www.sidefx.com/docs/houdini/nodes/cop/curlnoise.html) | 多尺度细节的幅度递减，时间连续变化；不用相同间距的平行曲线作为吸附目标。 |
| [Houdini POP Wind](https://www.sidefx.com/docs/houdini/nodes/dop/popwind.html) | 风可表示为目标速度，通过空气阻力逐渐跟随；与不断叠加恒力的无限加速不同。保留固定步长速度响应。 |
| [Houdini POP Curve Force](https://www.sidefx.com/docs/houdini/nodes/dop/popcurveforce.html) | 沿线流动、吸入、绕行是独立分量，作用范围可平滑衰减。采用输运与局部卷动分离，不把所有粒子吸附到预画细线上。 |
| [Houdini POP Attract](https://www.sidefx.com/docs/houdini/nodes/dop/popattract.html) | 距离影响强度需要人为定义；靠近目标的停止或反向排斥不适合本动画。本轮不使用终点停靠或碰撞。 |
| [Houdini POP Advect by Volumes](https://www.sidefx.com/docs/houdini/nodes/dop/popadvectbyvolumes.html) | 粒子按当前位置采样速度场，速度更新与位置更新应明确分开。延拓区域继续采样流动，不冻结为出生时的一根向量。 |
| [Houdini Gas Vorticle Geometry](https://www.sidefx.com/docs/houdini/nodes/dop/gasvorticlegeometry.html) | 局部涡旋可以随流场搬运；旋转区域不能始终钉在屏幕某处。 |
| [Houdini Gas Vortex Confinement](https://www.sidefx.com/docs/houdini/nodes/dop/gasvortexconfinement.html) | 可按控制场增强局部旋度。本轮只借鉴空间选择原则，不声称实现完整流体求解。 |
| [Reynolds：Arrival](https://www.red3d.com/cwr/steer/Arrival.html) | 到达行为会随剩余距离减速并停止；这正是本任务要避免的终态。触点指定流动朝向与延伸尺度，不是所有材料必须抵达的收集点。 |
| [NASA：Drag Equation](https://www1.grc.nasa.gov/beginners-guide-to-aeronautics/drag-equation/) | 阻力与相对速度、面积和形状有关，不能推导“触点越远，物理吸力自然越大”。远近到输运速度和牵伸的映射属于交互设计，必须平滑、有界并经画面验证。 |
| [PBRT：Transformations](https://pbr-book.org/4ed/Geometry_and_Transformations/Transformations) | 点与速度向量必须使用相配的变换。宽高分别缩放会同时拉长形状和速度；本轮将场的尺度与控件纹理尺寸分开。 |

## 实现与验收记录

已实现：

- 原始有效速度不重新拟合。新增 192 KiB 的共同可信度体积，延续区域主要增加随主流迁移的三尺度旋度；可信区域只保留很小的附加量。目标速度平滑跟随，顺风下限使用平滑函数，不对位置或原控件边缘做截断。
- 触点距离定义为中心到触点的射线越过控件边缘后的长度，除以短边。用 `1-exp(-gap/0.85)` 映射到有界的输运增量。近、中、远取 `0.15/0.65/1.5`；真实触摸连续取值，返回键固定左上与中距离。
- 当长宽比超过 1.2，局部流场长轴尺度平滑趋近短边的 1.35 倍。其余长度由间距约 0.96 短边的重叠区域覆盖，区域锚点有种子控制的横向变化。释放和速度共用同一坐标变换及权重，权重固定在出生位置，不在运动中跳区。
- 对方形照片保留原有共同释放结构；长弹窗的变化来自尺寸输入，没有按颜色、附件、钢铁侠等场景设置专属参数。材质、内容色份额、寿命规则没有修改。

已完成数值验收：

- 全部既有 226 输入与 7 个正式画廊输入，共 233 组，分别运行上一版与本轮 GPU 状态，无需重画标注视频。各组顺风行程中位数的比值，中位数为 **1.579**；扣除平移的边缘群体形变比值中位数为 **1.725**。
- 233 组均未出现持续 133 ms、可见且路程低于短边 2% 的窗口，也未出现沿主方向倒退的积分步。此阈值只作停滞回归，不能替代视觉验收。
- 另外 15 个代表输入做更严格的刚体对齐：同时扣除平移与旋转。上一版较接近刚性运动的 186 个边缘材料群中，本轮形变残差比值的中位数为 **4.231**。颜色弹窗的释放时刻有调整，所以群组取两端各自同时可见的材料集合；这不是逐点光流真值误差。
- 三素材、两方向的近中远输入均产生单调递增的行程。钢铁侠向左上三档顺风行程中位数分别约为短边的 **0.122/0.146/0.164**。渲染仍严格在一秒内结束。
- 17 个素材的起止画面、倒放重算、不同展示采样的一致性检查，以及 30 组任意方向检查通过。桌面交互预览已离屏验证。
- Android 单元测试通过；两台授权设备各自从图片独立建材并渲染钢铁侠、附件、颜色、宽面板、长面板，共 10 组。材料、共享资源、逐粒子状态与桌面对照通过。设备离屏计时含同步等待，不当作系统 UI 帧率。

原始测量与画面保存在 `tuning-0909-gpt6/analysis/flow-shaping/`；上一版代码、资源及主要帧缓存保存在 `tuning-0909-gpt6/archive/before-flow-shaping/`。这些大产物不跟踪到 Git。

## 交付

- `videos/flow-shaping.html` 集中展示本轮距离、颜色八方向和上一版对照；全部 118 个正式视频的编码、帧数和抽样解码通过校验，仍平铺在 `videos/`。
- 两设备额外核对钢铁侠、颜色的远距离输入，独立设备链路总计 14 组。真实弹窗近远背景点击的距离不同；返回方向均为 135°、默认距离 0.65。9018f404 上首次远点测试误落系统状态栏，按实测应用区域修正后通过，不修改动画模型。
- 阿里云更新 `202609101930` 已发布，两台设备安装相同发布包并复测返回关闭，粒子与背景动画层均清理，最后回到主列表。远端 APK 完整字节和九份共享资源已校验。
- 发布包 SHA-256：`a5e6bae6d6e04ff99eb870f6a39df8f964536fc2133765221d11a452dc00ea01`；当前模型指纹：`87fbba9d833f6233987a1f67b966d845dbca440515474e728c3b6bfe5cf229f2`。
- 机器可复核结果在 `analysis/flow-shaping/acceptance.json`。代码未提交，未修改 Android 出现动画。
