# 鸿蒙 OS 7「粒子消散」的实现线索（2026-09-28 调研）

> **2026-10-04 更正**：已从用户安装的 HarmonyOS 7 模拟器镜像取得扩展库中的实际着色器文本及 SceneBoard 调用参数，详见[镜像检查](research-2026-10-04-emulator.md)。下文“看不到着色器”描述的是当时尚未取得镜像的状态；“Centers 是最多四个起点”仅为旧推断，已被这一版本的代码否定：`centers0/1/2` 分别用于粒径、寿命和消蚀模式／方向。公开测试值也不能作为系统实际粒子数与风力的依据。

用户提醒：粒子消散是鸿蒙 OS 7 才有的系统动效，不能把鸿蒙 6 的通用 Particle 组件或「气化」动效当作它的实现。
本轮由 Opus 子代理检索 OpenHarmony 源码、官方描述与专利。下载的源码与专利原文保存在会话临时目录，未入库。

## 一手证据：OpenHarmony 7.0 的 `PARTICLE_ABLATION` 前景滤镜

- 提交标题「粒子消散filter」，需求单「粒子消融filter」；只出现在 OpenHarmony‑7.0‑Beta1 与 7.0‑Release 分支，6.0／6.1 没有。
  - graphic_graphic_2d：<https://github.com/openharmony/graphic_graphic_2d/commit/ad2671c41b9dffa65813f8d19a939ec294ff619d>（2026‑03‑24），合入 <https://github.com/openharmony/graphic_graphic_2d/commit/6b8a69b3ec0567209cc55b094c52ad26efe02505>
  - graphics_effect：<https://github.com/openharmony/graphic_graphics_effect/commit/9e4298c76ad1a79bbdec40c46d7b13c2810eb3cc>
  - 参数定义：<https://github.com/openharmony/graphic_graphics_effect/blob/master/include/effect/filter/ge_particle_ablation_filter.params.in>
- 算法本体在闭源库 `libgraphics_effect_ext.z.so`，**看不到着色器**。公开 SDK 没有暴露，推断只给系统应用使用。
- 参数（测试用例取值只验证传参，不代表产品值）：

| 参数 | 类型 | 可动画 | 测试取值 |
| --- | --- | --- | --- |
| Progress | float，[0,1] | 是 | 0～0.75 |
| AblationRate | float ≥0 | 是 | 0.2～0.8 |
| Centers0..3 | 4 个 Vector2f | 否 | — |
| GlowLevel、GlowBrightness | float ≥0 | 是 | 0.2～0.9 |
| MaxParticleCount | int | 否 | 500～2000 |
| Wind | Vector2f | 否 | (0,0)～(1.5,1) |
| TurbScale／TurbEvo／TurbAmp | int | 否 | 5～20／2～10／1～7 |
| ExpansionSize | Vector2f（像素） | 否 | — |

- 渲染路径：挂在节点的前景滤镜上，输入是节点内容的快照，颗粒颜色只能来自这张快照；脏区扩展为以节点为中心、
  宽高等于 ExpansionSize 的矩形，颗粒可以飞出节点。

## 对复刻的含义（推断，均待画面验证）

1. **没有 Z、透视或相机参数。** 用户感觉到的「向上凸起」若存在，只能来自闭源库内部，公开代码无法确认；
   应优先从辉光与颗粒尺寸解释立体感，而不是先加三维抬升。
2. **有显式辉光（两个可动画参数）。** 原片深色背景上颗粒峰值更亮、近白颗粒更多，地球背景段颗粒比源色更白，
   与之相符。辉光是作用在颗粒上、消融边缘上还是两者都有，公开代码看不到，需要用画面区分。
3. **颗粒数量有上限、量级可能远小于像素数。** 与用户观察「颗粒更大、更稀疏、同屏更少」一致；
   也说明原片可能是「沿前沿擦除图像，同时发射有限数量的颗粒」，而不是整张图全部转成颗粒。
4. **运动由统一的风加湍流（尺度、演化、幅度）驱动，起点最多 4 个。** 我们的共同流场与卷动是从观测提炼的，
   形态上可能已包含等效成分；不按素材切换模型的约束不变。

## 背景（非目标效果）

- 鸿蒙 6 的「气化」删除动效对应 6.1 分支的 `GASIFY` 系列滤镜与专利 CN122019026A（噪波图驱动位移与模糊，叠加气态颗粒层）。
- ArkUI `Particle` 组件与 2026‑04 的粒子场重构（径向力 + Perlin 旋度噪声，CPU 更新）对应音量／亮度条的「灵动粒子」，不是卡片消散。
- 同类开源实现：Telegram `ThanosEffect`（每格一颗点精灵、随机初速、向上加速、按列释放，无辉光、无 Z）。

## 补充检索：公开代码里的调用方与参数（2026-10-02 夜，Opus 子代理）

结论：公开源码里**没有任何代码创建或配置 `PARTICLE_ABLATION`**，算法本体只在闭源的 `libgraphics_effect_ext.z.so` 里，没有 fallback；
因此拿不到产品里实际用的风、湍流、消融速率、起点取法与动画时长。下载的源码与补丁在会话临时目录 `scratchpad/oh-ablation/`，未入库。

- 检索范围：graphic_graphics_effect（完整历史）、graphic_graphic_2d、arkui_ace_engine、window_window_manager、interface_sdk-js 的 master、
  OpenHarmony-7.0-Beta1、7.0-Release；applications_systemui、applications_launcher 等只看了 master。需求单
  [issue #23165](https://gitcode.com/openharmony/graphic_graphic_2d/issues/23165)「[新需求]: 粒子消融filter」（2026-04-07），正文只有「新增了粒子消融filter通路」。
  首个提交里曾有 ui_effect 客户端参数类，合入的 PR !29492 去掉了，**没有 NAPI / ArkTS 入口**。
- 只有两处测试给出取值（都只验证传参）：
  - graphic_2d 像素对比测试（屏幕 1200×2000，挂在节点的前景滤镜上）：Progress 0.5、AblationRate 0.8、Centers 为像素坐标 {100,100}…{400,400}、
    Glow 0.6/0.7、MaxParticleCount 1000、Wind {1.0, 0.5}、Turb 10/5/3、ExpansionSize {1,1}；
  - GE 单元测试：Progress 0.5、AblationRate 0.3、Centers 为四象限的归一化坐标 {0.25,0.25}…{0.75,0.75}、Glow 2.0/1.5、MaxParticleCount 1000、
    Wind {0.1, 0.05}、Turb 4/2/1。两处 Centers 的单位不一致，**单位无法从公开代码确定**。
- 属性定义：Progress、AblationRate、GlowLevel、GlowBrightness 可动画；Centers、MaxParticleCount、Wind、Turb*、ExpansionSize 不可动画。
  推断调用方用属性动画驱动 Progress（可能连同消融速率与辉光），其余只在开始时设一次。默认值除 ExpansionSize {1,1} 外都是 0，只有 Progress 被夹到 [0,1]。
- ExpansionSize 的实际含义：脏区是以节点为中心、宽高等于 ExpansionSize **像素**的矩形（`rs_properties_painter.cpp`），颗粒可以飞出节点。
- 同一作者此前提交的是 Gasify（气体消散）filter。官方资料把粒子效果限定在删除通知、桌面图标、卡片、快捷方式等场景，没有提到弹窗。
  子代理的置信度：通知、卡片、图标的删除粒子由 PARTICLE_ABLATION 实现约六成；弹窗消失使用它低。
- **开源的同类 filter 都把着色器以 SkSL 字符串内嵌在源码里**（约 60 个源文件，`GECreateRuntimeEffectForShader` → `Drawing::RuntimeEffect::CreateForShader`），
  没有预编译的二进制着色器。外部库由 `dlopen("/system/lib64/libgraphics_effect_ext.z.so")` 加载，只导出 `CreateGEXObjectByType` 与 `DestroyGEXObject`
  两个 C 符号，闭源实现的类名与函数名都不可见。若闭源库沿用同一做法，**从设备上取得该库后只提取字符串，很可能就能看到着色器源码**；
  `persist.sys.graphic.geShaderDiagnosticsEnabled=1` 会把经过诊断函数的 SkSL 写到渲染服务目录，闭源库是否经过它不得而知。
  两种做法都需要鸿蒙 7 设备，也可能与系统许可冲突，由用户决定（2026-10-02 用户表示可以试，但不要投入太多资源）。
- HarmonyOS 7 滑块的「光感粒子」走的是开源的 ArkUI Particle（`slider_pattern.cpp`），不是这个 filter。
- 参数命名（Wind + Turbulence 的 Scale / Evolution / Amount）与 AE、Trapcode Particular 的湍流场一致，推测原型来自这类工具（未证实）。
