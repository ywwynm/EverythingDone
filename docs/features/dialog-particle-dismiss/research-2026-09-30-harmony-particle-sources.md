# 华为粒子消散动效与同类公开实现（网上调研，2026-09-30）

调研由 Opus 子代理完成，只做网上检索，未改本地文件。标注：【源码】读过开源代码摘录；【官方文档】官方参考文档；
【官方营销/客服】华为营销页或客服页；【二手】媒体或用户描述；【推测】推断。

## 结论

1. 鸿蒙 7 的系统级效果（滑动删除通知、卡片移除、应用卸载）**没有任何公开实现资料**：没有开发者文章、逆向分析、专利或设计文档
   说明它用的是 ArkUI `Particle`、RenderService 特效还是着色器；也没有资料提到 z 轴、景深、法线或光照。
2. 能核实运动方程的华为资料只有 OpenHarmony 的 ArkUI `Particle` 组件及其 RenderService（RS）后端源码。
3. 同类可核实代码的实现（Telegram Android / iOS 删除消息、Aghajari/ThanosEffect、Google「灭霸」彩蛋复刻、Flutter disintegrate）**全部是二维的**，
   没有 z 向抬升或透视。

## ArkUI Particle

文档【官方文档】：[OpenHarmony ts-particle-animation.md](https://github.com/openharmony/docs/blob/master/zh-cn/application-dev/reference/apis-arkui/arkui-ts/ts-particle-animation.md)。
参数：发射器（数量、寿命及浮动范围、发射率、矩形/圆/椭圆/环形形状）；速度与角度各为区间；加速度大小与角度可挂变化器；
不透明度、缩放、自旋、颜色的变化器有 NONE / RANDOM（固定变化率）/ CURVE（按已存活时间分段插值）三种；
`disturbanceFields`（API 12：强度正推负吸、形状、羽化、噪声尺度/频率/幅度）；API 22 新增 `rippleFields`（幅度、波长、波速、衰减）与 `velocityFields`。

运动方程【源码】（[rs_render_particle_effector.cpp](https://github.com/openharmony/graphic_graphic_2d/blob/master/rosen/modules/render_service_base/src/animation/rs_render_particle_effector.cpp)）：
- 初值在区间内均匀取；圆形发射器半径在 [0, d/2] 内均匀取（偏向中心）；
- 每帧：v ← v + a·dt（写回速度）；x ← x + (v + F(x))·dt，F 为各场之和，**只参与本帧位移、不写回 v**，离开场区即失去这部分速度；
- 没有阻尼，没有 z。
- 扰动场（[rs_particle_noise_field.cpp](https://github.com/openharmony/graphic_graphic_2d/blob/master/rosen/modules/render_service_base/src/animation/rs_particle_noise_field.cpp)）= 径向项
  （沿 normalize(x − c)，大小为强度，按到边缘距离羽化）+ 静态 curl 噪声（N = A·(Perlin(f·x)+1)/2，速度 = (−∂N/∂y, ∂N/∂x)·noiseScale，不随时间变）。
- 波动场（[rs_particle_ripple_field.cpp](https://github.com/openharmony/graphic_graphic_2d/blob/master/rosen/modules/render_service_base/src/animation/rs_particle_ripple_field.cpp)）：
  以波速向外扩张的径向环带（半宽 max(1, 0.15λ)，环内余弦窗），环带之后 0.25λ 内有反向回弹，整体乘 幅度·e^(−衰减·t)/(1+0.002r)。
- 死亡：寿命到期，或 RANDOM 模式下不透明度 / 缩放减到 0。

## 华为系统效果的公开描述

- 鸿蒙 6（2025-11）「沉浸光感」含通透质感、灵动粒子、气态动效；气态动效用于通知删除、应用卸载，模拟气体流动【官方营销，
  [DoNews 转述](https://www.donews.com/news/detail/4/6377116.html)】。
- 华为客服页摘要（原页已下线、无存档，只见搜索摘要）：删除通知后先播完整段粒子散落，下方通知才补位；为了让人看到消融过程，
  采用缓速扩散【官方客服，未核实原文】。
- 鸿蒙 7 官网「沉浸光感」只有四段演示视频（光感粒子、光随指动、光晕引力、3D 空间卡片），无技术说明；媒体称老机型升级后卡片移除、
  应用卸载也有粒子效果，沉浸光感设为「均衡」即可触发【二手】。
- 华为早年 HMS 场景动效的 [ParticleScatter](https://developer.huawei.com/consumer/cn/doc/particlescatter-0000001073902057)：散落前先抖动（默认 150 ms），
  九种样式（向上爆炸、向下坠落、四周散开等），与鸿蒙 7 的关系未知。

## 同类实现【源码】

- **Telegram Android**（[ThanosEffect.java](https://github.com/DrKLO/Telegram/blob/master/TMessagesProj/src/main/java/org/telegram/ui/Components/ThanosEffect.java)）：
  GPU transform feedback，网格方块粒子；初速方向各向同性随机、26～52 dp/s；水平加速度 19·sign(vx) 在 0.35 s 内衰减到 0，竖直恒定向上 65 dp/s²；
  释放前沿从左到右约 0.6 s 扫完，激活后 0.2 s 软启动（同时乘在位移、加速度、寿命消耗上）；寿命 0.61～1.30，alpha = min(寿命, 0.55)/0.55；无阻尼无噪声。
- **Telegram iOS**（[DustEffectShaders.metal](https://github.com/TelegramMessenger/Telegram-iOS/blob/master/submodules/TelegramUI/Components/DustEffect/Metal/DustEffectShaders.metal)）：
  每 1 pt 一粒，初速随机，竖直加速度 +120，寿命 0.7～1.5，软前沿 0.8 个控件宽、0.8 s 扫过；无阻尼无噪声。
- **Aghajari/ThanosEffect**：闭式公式，以控件中心为原点的匀速径向膨胀 + 随 f² 增长的上浮；半径先涨到 1.2 倍再缩到 0。
- **Google 彩蛋复刻**：35 层画布按位置加权分配像素，每层向右上平移、随机旋转、轻微模糊，easeInQuad（先慢后快），层间错开 70 ms。
- **Flutter disintegrate**：drift、scatter、turbulence、sweep 四个参数；位移曲线缓出 1 − (1 − t)²（先冲出后滑行）。

## 对建模的启示（含推测）

1. 典型方程是半隐式欧拉；ArkUI 的场项只加在位移上、不累积到速度，无阻尼。「先冲出、后减速悬停」可用有界径向排斥场实现【推测】。
2. 两份 Telegram 的初速都是各向同性随机，整体方向感来自恒定加速度与前沿扫过的方向。
3. 起点处的扩散在二维里最接近的公开机制是 API 22 的波动场：以波速外扩的推力环，后面带回弹【推测其与鸿蒙 7 效果的关系】。
4. z 向抬升与透视没有任何公开实现或华为说明。

## 未深入的线索

华为开发者网站[沉浸光感设计指南](https://developer.huawei.com/consumer/cn/doc/design-guides/immersivelight-0000002612101053)（需 JS 渲染，正文未读到）；
OpenHarmony `rs_particles_drawable.cpp`（绘制部分）未读。
