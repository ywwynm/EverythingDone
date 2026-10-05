# 统一粒子模型外部调研（2026-10-04）

本轮先复核历史，再搜索华为官方材料、OpenHarmony 与 Telegram 源码、程序流动与粒子渲染论文、工程文档。下述来源与推断分开；没有找到能直接证明鸿蒙 7 系统消散内部方程的公开实现。搜索未找到，不等于断言不存在。

## 来源、适用范围与取舍

| 来源 | 已核实内容 | 本任务取舍 |
| --- | --- | --- |
| [华为 HarmonyOS 7 官网](https://consumer.huawei.com/cn/harmonyos-7/) | 官方展示通知消除时化成光感粒子；没有公开运动与材质公式。 | 可核实产品效果归属，不能据产品宣传推断 3D 或流体实现。 |
| [华为沉浸光感启用文档](https://developer.huawei.com/consumer/cn/doc/HarmonyOS-Guides/arkts-immersive-light-sense-enable) | 说明组件适用范围、API 要求与 GPU 资源成本。 | 通用材质能力不等于系统通知消散算法。 |
| [OpenHarmony 粒子更新源码](https://github.com/openharmony/graphic_graphic_2d/blob/master/rosen/modules/render_service_base/src/animation/rs_render_particle_effector.cpp) | 速度由加速度积分；场值按当前位置取样后参与本帧位移；生命周期变化器单独驱动缩放、透明度等。 | 借鉴把材料运动和外观分开描述、由同一时钟驱动的结构，不照搬并称其为华为系统真相。 |
| [OpenHarmony 扰动场源码](https://github.com/openharmony/graphic_graphic_2d/blob/master/rosen/modules/render_service_base/src/animation/rs_particle_noise_field.cpp) | 扰动包含有限区域的径向场与 Perlin 势的旋度。 | 说明共同空间场可生成相干运动；并不要求对所有区域加同样噪声。 |
| [Curl-Noise for Procedural Fluid Flow](https://www.cs.ubc.ca/~rbridson/docs/bridson-siggraph2007-curlnoise.pdf) | 由势的旋度构造无散流，可组合涡旋与边界条件，幅度调制应作用于势。 | 用作受控流动的候选；二维无散场本身不能制造任意压实轮廓，不能以“物理正确”代替参考验收。 |
| [Cornell 程序流动实验](https://www.cs.cornell.edu/courses/cs5643/2023sp/assignments/A1.html) | 先验证层流，再增加扰动和边界处理。 | 先确定主运动与释放关系，再增加细节；避免一开始同时调全部项。 |
| [Houdini Gas Disturbance](https://www.sidefx.com/docs/houdini/nodes/dop/gasdisturb-.html) | 小尺度扰动应保留总体形状与运动，可按场强与世界尺度控制。 | 随机性在材料空间保持固定、尺度随控件短边定义；限制局部扰动，不靠独立随机转向破坏群体。 |
| [Telegram Android ThanosEffect](https://github.com/DrKLO/Telegram/blob/master/TMessagesProj/src/main/java/org/telegram/ui/Components/ThanosEffect.java) 与 [iOS Metal 着色器](https://github.com/TelegramMessenger/Telegram-iOS/blob/master/submodules/TelegramUI/Components/DustEffect/Metal/DustEffectShaders.metal) | 有完整的应用 UI 粒子删除路径，可核对网格颗粒、生命周期与 GPU 更新。 | 借鉴可实时运行的规模与生命周期组织；其视觉语言不同，不直接移植运动。 |
| [GPU Gems 3：离屏粒子](https://developer.nvidia.com/gpugems/gpugems3/part-iv-image-effects/chapter-23-high-speed-screen-particles) | 降分辨率适合低频烟雾，高频颗粒会损失细节；透明混合与发光相加是不同运算。 | 最终验收保留原分辨率颗粒，避免用低清渲染的灰雾调运动；把覆盖与发光分开。 |
| [Object Space EWA Surface Splatting](https://www.cs.umd.edu/~zwicker/publications/ObjectSpaceEWASplatting-CGF02.pdf) | 投影中的颗粒覆盖范围与重建滤波相关。 | 借鉴屏幕覆盖的面积核算；本任务不引入完整点云曲面重建。 |
| [Stochastic Transparency](https://research.nvidia.com/publication/2011-08_stochastic-transparency) | 用随机样本估计透明覆盖，不是把所有几何都改成很低透明度。 | 优先保留粒子自身可辨识的对比度，减少数量、缩小尺寸和降低透明度须分别验证。 |
| [Spatiotemporal Blue Noise Masks](https://research.nvidia.com/publication/2022-07_spatiotemporal-blue-noise-masks) | 每帧独立采样会牺牲时间稳定性，空间与时间采样质量需要共同设计。 | 粒子身份与随机属性固定到一次动画，不每帧重抽；本轮不为短 UI 动画引入时域累积。 |
| [透明／反射双层图像的光流](https://www.cv-foundation.org/openaccess/content_cvpr_2016/html/Yang_Robust_Optical_Flow_CVPR_2016_paper.html) | 多层透明混合破坏传统亮度恒常假设。 | PIV／光流只作可见图案的运动证据；固定粒子轨迹与图像序列分开检查。 |
| [OpenGL sRGB 扩展规范](https://github.com/KhronosGroup/OpenGL-Registry/blob/main/extensions/ARB/ARB_framebuffer_sRGB.txt) | 正确颜色混合涉及编码值与线性空间转换。 | 保留显式预乘与颜色空间边界，不能在不同合成路径之间用一个亮度系数补偿。 |

华为设计指南页面本轮只能取得空正文，未将它列为已经读懂的实现依据。

## 对下一轮模型的约束

1. 参考拟合路径与随机生产候选分别标识，正式候选只接受尺寸、源图、起点、触点和种子；不读取逐参考速度表或学习密度补丁。
2. 先保护旧模型已有的相干弧边和沿边流动，设为独立回归项；新运动必须先在同一材质下比较。
3. 释放与运动共享材料坐标与时间，原表面和已释放颗粒的交接只发生一次。逐粒身份保持不变，避免时变后置位移造成回摆。
4. 新随机输入须在尺寸、方向和触点微扰下连续。固定种子用来重放，未挑选种子的矩阵用来检查泛化。
5. 材质先保持不变。只有运动连续而形状仍因覆盖不足断裂时，再单独比较粒径、数量、生命周期或投影；不同时调多项。
6. 统一进度、相同素材与背景、全画面播放、相邻帧、原尺寸细节同时验收。指标只描述对应量，不合并成“相似百分比”。
