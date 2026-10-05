# HarmonyOS 7 粒子消散原始位置复核

## 结论与检查范围

不止两段代码。两段是直接保留原文的主要着色器；同一效果还涉及参数结构、原生滤镜宿主、HDS 场景与动画控制、卡片／通知／图标调用方，以及绘图后端。

本次重新从安装镜像读取 29 个相关原生库（合计 126,271,864 字节），检查 ELF 符号、压缩局部符号、可读着色器和依赖；另扫描 SceneBoard 全部 53 个 HAP／HSP 及 4 个 HDS ABC 包装文件。核对 `system.img`、`sys_prod.img`、`vendor.img` 文件清单，并检查 DevEco SDK 中相关声明。原镜像里的核心扩展库与此前提取副本逐字节一致。

这是对相关库和系统包的静态复核，不等于逐条恢复全部机器指令，亦不覆盖未安装的真机 ARM 镜像。未发现另一份可补齐目标消散效果绘制后端的实现。

本次只保留索引、选定函数和小型代码片段，新增 `source-audit` 目录约 2 MiB，没有再次展开整幅镜像或保存完整 SceneBoard 反汇编。

## 原始安装位置

所有下列系统路径的原件均在本机这幅镜像中：

`E:\harmonySDK\system-image\HarmonyOS-7.0.0\phone_all_x86\system.img`

版本为 `7.0.0.107(SP8DEVC00E999R4P11)`，x86_64。表中的 `/system/...` 是镜像内部路径，不是 Windows 上单独存在的目录。HSP／HAP 内部的 `ets/modules.abc` 是编译后的 ArkTS 字节码。

| 内容 | 镜像内原始位置 | 已确认内容及形式 |
| --- | --- | --- |
| 消蚀、运动、参数结构、滤镜宿主 | `/system/lib64/libgraphics_effect_ext.z.so` | 两段主体着色器和三段结构字段原文；`GEParticleAblationFilter` 的机器码、符号及反汇编 |
| HDS 粒子场景定义 | `/system/lib64/libhdsvisualcomponentimpl.z.so` | 文件偏移 `0x115e0` 的 `ParticleGasifyEffect` JSON 原文，包含参数绑定 |
| HDS 动画控制与参数传递 | `/system/lib64/libhdsvisualeffectsimpl.z.so` | `ParticleGasifyModel`，包括初始化、进度动画及滤镜参数更新；机器码／反汇编 |
| HDS 场景编号和系统包装 | `/system/etc/abc/hds/hdsvisualcomponent.abc` | `PARTICLE_DISSIPATION_SCENE = 10034`，旧材质气化为 `10032`；ABC／反汇编 |
| 桌面卡片配置与播放 | `/system/app/SceneBoard/formparticle.hsp` → `ets/modules.abc` | `FormParticleConfig`、`FormParticleView`；ABC／反汇编 |
| 通知粒子配置和播放 | `/system/app/SceneBoard/systemuicommon.hsp` → `ets/modules.abc` | `HarmoniumEffectVm.doCardParticleRemove`、`doClearParticleRemove`、`doParticleRemove` |
| 卡片启用条件、通知清除／滑动入口、桌面图标视图 | `/system/app/SceneBoard/SceneBoard.hap` → `ets/modules.abc` | `FormParticleManager`、`NotificationClearButtonVm`、`NotificationSwipeVm`、`MaterialIconParticleView` |
| 图标消散控制与能力判断 | `/system/app/SceneBoard/launchercommon.hsp` → `ets/modules.abc` | `MaterialIconParticleViewModel`、`VisualEffectUtil.isSupportParticle` |
| 原生效果注册和参数接口 | `/system/lib64/libgraphics_effect.z.so` | `ParticleAblation` 外部效果注册、参数包装；算法委托扩展库 |
| 粒子构建与绘制后端 | `/system/lib64/lib2d_graphics.z.so`、`/system/lib64/libddgr.z.so` | 本 x86 镜像的相应工厂返回空对象，Skia 粒子绘制未实现；见下文 |

## 核心库中的精确位置

`libgraphics_effect_ext.z.so` 的 SHA-256：

`5a61a59bab0d82e5c5f01eb20732e1d1f30ef73c441c52b3aca122b41e689d9c`

下表是**文件字节偏移**，不是反汇编虚拟地址。原文长度不含末尾零字节。

| 文件偏移 | 原文长度 | 内容 | 可直接阅读的提取文件 |
| --- | ---: | --- | --- |
| `0x411e9` | 9,060 | 粒子生成、激活、风与湍流运动、寿命和尺寸更新 | [运动着色器](../../../tmp/dialog-takeover-20261004/harmonyos7/source-audit/libgraphics_effect_ext.z.so--000411e9.txt) |
| `0x48b8a` | 2,199 | 噪声消蚀／单向消蚀、源图剩余透明度 | [消蚀着色器](../../../tmp/dialog-takeover-20261004/harmonyos7/source-audit/libgraphics_effect_ext.z.so--00048b8a.txt) |
| `0x386a1` | 327 | `ParticleSystemConfig` 的字段：发射范围、候选数、粒径、寿命、时间等 | [粒子配置原文](../../../tmp/dialog-takeover-20261004/harmonyos7/source-audit/libgraphics_effect_ext.z.so--000386a1.txt) |
| `0x387e9` | 127 | 风、密度、阻力、加速度字段 | [风场配置原文](../../../tmp/dialog-takeover-20261004/harmonyos7/source-audit/libgraphics_effect_ext.z.so--000387e9.txt) |
| `0x38897` | 255 | 湍流尺度、演化、幅度、倍频、相位等字段 | [湍流配置原文](../../../tmp/dialog-takeover-20261004/harmonyos7/source-audit/libgraphics_effect_ext.z.so--00038897.txt) |

三段字段由 `GetParticleSysConfig`、`GetWindField`、`GetTurbulenceField` 取得，参与粒子程序构建；它们不是另外三套运动算法。原先的着色器提取器偏重完整程序，这次补充将这些短字段原文统一列入索引。

库内还保留了编译时使用的原始源码路径字符串，位于文件偏移 `0x15b0e`：

`../../../foundation/graphic/graphics_effect_ext/src/gex_particle_ablation_filter.cpp`

这是华为构建工程内的源文件路径。当前电脑上没有这个 `.cpp` 源文件；可以查看的是库中原文和机器码，不能把反汇编包装成取得了完整 C++ 源码。

宿主函数的 **ELF 虚拟地址**：`GetParticleSysConfig = 0x95055`、`GetWindField = 0x950f8`、`GetTurbulenceField = 0x9519b`、构造函数 `0x9523e`、`OnProcessImage = 0x9530c`、`GetParticleAblationEffect = 0x9642a`、`DrawParticleImage = 0x9660c`。它们控制表面创建、工作尺寸、每帧时间、输入纹理、参数和合成顺序；不是两段着色器可以独立替代的部分。

阅读入口：[带注释的宿主反汇编](../../../tmp/dialog-takeover-20261004/harmonyos7/ablation-host-annotated.txt)。

## HDS 与调用方的阅读入口

- [粒子场景 JSON 原文](../../../tmp/dialog-takeover-20261004/harmonyos7/hds-scene-115e0.json.txt)。
- [HDS 原生控制反汇编](../../../tmp/dialog-takeover-20261004/harmonyos7/hds-particle-host-disassembly.txt)。关键地址：`ParticleGasifyModel::SetEffect = 0x161d2a`、`InitDefaultParam = 0x16249a`、`UpdateShaderProgress = 0x162590`、`AnimateProgressShader = 0x1625ba`、`UpdateParticleGasifyFilter = 0x162d3e`。局部符号保留了 `particle_gasify_model.cpp` 文件名，未取得其完整工程路径或源文件。
- [HDS 系统包装反汇编](../../../tmp/dialog-takeover-20261004/harmonyos7/source-audit/hdsvisualcomponent.pa)。这里可直接核对场景编号；SDK 面向应用的声明没有等量暴露所有系统场景。
- [卡片配置与视图反汇编](../../../tmp/dialog-takeover-20261004/harmonyos7/formparticle/modules.pa)。ABC 保留的模块源码路径为 `@ohos/formparticle/src/main/ets/default/common/config/FormParticleConfig.ts`、`@ohos/formparticle/src/main/ets/default/view/FormParticleView.ts`；另保留布局源位置 `feature/desktop/formparticle/src/main/ets/default/view/FormParticleView.ets`。
- [通知配置与控制的完整相关函数](../../../tmp/dialog-takeover-20261004/harmonyos7/source-audit/systemuicommon-additional.pa)。保留的模块路径为 `@ohos/systemuicommon/src/main/ets/vm/HarmoniumEffectVm.ts`。
- [通知／卡片入口反汇编](../../../tmp/dialog-takeover-20261004/harmonyos7/SceneBoard/particle-selected.pa)。对应 `@ohos/notificationcomponent/src/main/ets/viewmodel/NotificationClearButtonVm.ts`、`NotificationSwipeVm.ts` 及 `@ohos/form/src/main/ets/default/manager/FormParticleManager.ts`。
- [桌面图标控制的相关函数](../../../tmp/dialog-takeover-20261004/harmonyos7/source-audit/launchercommon-additional.pa)。对应 `@ohos/launchercommon/src/main/ets/gasify/interface/MaterialIconParticleViewModel.ts`；主 HAP 另有 `@ohos/desktopvisual/src/main/ets/default/material/MaterialIconParticleView.ts`。

上述 `.ts`／`.ets` 路径来自二进制保留的模块／调试信息，不代表安装目录中存在对应文本源文件。`.pa` 均为反汇编。

通知与桌面卡片不是同一组默认输入。例如桌面卡片时长 2000 ms、`turbEvo=20`、`turbAmp=400`；通知滑动删除为 650 ms、`turbEvo=300`，并在调用时随机改变湍流尺度与幅度；通知清除全部为 1500 ms、`turbAmp=300`、纵向风 120000。候选数量还受区域高度和上限控制。不能将卡片参数一律套到所有系统场景，也不能将这些毫秒数直接当成慢放视频长度。

## 同时找到但不能混用的实现

- 扩展库检出 84 段着色器／结构定义候选，大部分属于玻璃、光效、折射等其它功能。不能把 84 当作粒子消散代码段数量。
- `GASIFY` 旧气化仍在同一扩展库中：消散遮罩 `0x43cad`、横／纵模糊 `0x44582`／`0x45978`、形变图集 `0x46d6e`。它对应 HDS 的 `MaterialGasifyEffect`，不是这次的 `ParticleGasifyEffect`。
- `libgraphics_effect.z.so` 另外含 `ge_particle_circular_halo_shader.cpp` 的三段圆环光效代码（`0x6e770`、`0x6f7a0`、`0x70480`），不能仅因包含 particle 名称就加入卡片消散。
- `librender_service_base.z.so` 和 ArkUI 中还有通用粒子、速度场、噪声场等实现。控制中心和音量滑块的系统包确有对应 `Particle` 调用；没有证据表明它们替代当前卡片／通知的 HDS 消散路径。
- 找到 `/system/variant/phone/base/etc/scene_board/particle/dot01.png`，`componenthelper` 的 `getParticleDot` 供通用粒子界面加载。目标消散的已核对调用使用源快照取色；尚无证据证明该 PNG 是缺失绘制后端使用的点纹理。

## 尚缺什么

`lib2d_graphics.z.so` 中工厂地址 `0xfa920`、`0xfa960` 的指令直接写入空返回值；`SkiaCanvas::DrawParticle = 0x1044b0` 记录不支持日志。本次重新核对了这些机器指令。`libddgr.z.so` 是 4,824 字节的小型库；本次相关依赖和相邻库扫描没有找到目标后端的替代实现。

进一步检查 DevEco 自带 Windows 预览器的 12 个相关 DLL：原始目录为 `E:\software\DevEco Studio\sdk\default\openharmony\previewer\common\bin`。其中 `libgraphics_effect.dll` 有接口和其它着色器，但未找到 `graphics_effect_ext` 对应库；`lib2d_graphics.dll` 的 `SkiaCanvas::DrawParticle`（本次反汇编地址 `0x180024ce0`）只有一条 `retq`，同样不能补齐绘制。证据见[预览器绘制函数](../../../tmp/dialog-takeover-20261004/harmonyos7/source-audit/previewer-draw-particle.txt)。`E:\harmonySDK` 中目前只找到这套 `phone_all_x86` 系统镜像，没有另一个已安装的 ARM 系统镜像可供比较。

因此，已取得的内容足以恢复不少核心规则与调用参数，但仍不是完整产品源码：最终绘制细节缺失，原始视频的精确输入未恢复，桌面移植本身的传播与形状也仍有误差。没有证据支持“只缺最后一段绘制，其他都已经与参考一致”。

公开源代码还可查看 OpenHarmony 的[粒子消蚀参数定义](https://github.com/openharmony/graphic_graphics_effect/blob/master/include/effect/filter/ge_particle_ablation_filter.params.in)，此次已重新打开核对。它提供接口字段，不能替代上述镜像中的产品实现。

## 复核材料

- 脚本：`tmp/dialog-takeover-20261004/harmonyos7/audit_original_code.py`。
- 原生库清单、SHA-256、字节偏移、依赖和符号：`tmp/dialog-takeover-20261004/harmonyos7/source-audit/audit.json`。
- 57 个系统包／ABC 文件清单和相关字符串：`tmp/dialog-takeover-20261004/harmonyos7/source-audit/app-audit.json`。
- Windows 预览器 12 个 DLL 的检查与哈希：`tmp/dialog-takeover-20261004/harmonyos7/source-audit/previewer-audit.json`。
- 原始镜像未修改；未连接设备、调整系统开关、生成整包反汇编或修改预览算法。
