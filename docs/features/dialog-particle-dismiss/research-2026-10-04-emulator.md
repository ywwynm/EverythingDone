# HarmonyOS 7 模拟器实现线索检查

2026-10-04 补充：按用户要求重新检查 29 个相关库、53 个 SceneBoard 包和 4 个 HDS ABC 文件，原始安装位置、编译时源码路径、文本偏移及调用方见[完整位置索引](original-code-locations-2026-10-04.md)。此前只给出两段着色器链接不代表仅取得这两段相关实现。

## 代码入口与完整性说明

这里取得的是**二进制中保留的原始着色器文本，以及部分宿主代码的反汇编**，不是完整 C++／ArkTS 源码工程。当前桌面文件是依据这些证据编写的独立移植，必须与原始提取物区分。

- 粒子更新原文：仓库根目录下 `tmp/dialog-takeover-20261004/harmonyos7/shader-strings/offset-000411e9.txt`，139 行为位置更新，198 行为尺寸更新。
- 消蚀遮罩原文：同目录 `offset-00048b8a.txt`。
- 卡片调用参数与行为：`tmp/dialog-takeover-20261004/harmonyos7/formparticle/modules.pa`；这是 ABC 反汇编，不是原始 ArkTS。
- 原生滤镜宿主的反汇编和注释：`tmp/dialog-takeover-20261004/harmonyos7/ablation-host-annotated.txt`。
- 独立桌面移植：本功能 `tuning-0909-gpt6/harmony_core.py`；显式参考起点扩展在 `harmony_origins.py`，观测输入在 `reference_initial_conditions.py`。扩展的起点传播与初期生长规则并非华为原文。

原始运动更新的数值对照只能验证相同输入下的局部公式；最终粒子绘制后端在本 x86 镜像中缺失，参考物理尺度／时钟／随机输入也未全部恢复。此前起点没有接入、当前共同传播产生过快圆洞等则是桌面实现本身的问题，不能一概归因于源码缺失。

## 范围与已确认环境

用户要求从新安装的 DevEco Studio、HarmonyOS 7 镜像和正在运行的模拟器查找粒子消散实现细节，服务于现有桌面复刻工作。优先只读检查，不修改镜像、系统动效开关或安装应用。

- DevEco Studio 位于 `E:\software\DevEco Studio`；模拟器名为 Pura X View，型号配置 PHEMU-FD00，x86_64。
- 镜像元数据：API 26、Release、HarmonyOS 7.0.0.107(SP8)。运行参数为 `emulator 7.0.0.107(SP8DEVC00E999R4P11)`；OpenHarmony 基础版本字段为 7.0.0.105，不能据此误报为另外一个模拟器版本。
- 通过 HDC 显式选择本地 `127.0.0.1:5555`，shell 身份为 uid 2000。`/system` 和系统应用目录读取受限，且参数 `emulator.sandboxview.enabled=true`。这只说明调试 shell 的可见性，不证明系统镜像缺少相应库。
- 已用 7-Zip 只读列出本地 ext4 安装镜像；`system.img` 为约 3.42 GiB，`sys_prod.img` 为 800 MiB。镜像确含 SceneBoard 系统应用。后续仅选择性提取相关文件，不复制整个镜像或展开所有应用。

## 既有线索

[9 月 28 日调研](research-2026-09-28-harmonyos7-ablation.md) 找到了 OpenHarmony 的 `PARTICLE_ABLATION` 滤镜接口；算法委托给扩展库。此次重新打开了[公开参数定义](https://github.com/openharmony/graphic_graphics_effect/blob/master/include/effect/filter/ge_particle_ablation_filter.params.in)，确认有四个中心、风向、湍流、辉光、最大粒子数与扩展尺寸等参数。它与真实系统场景的关系仍需镜像内调用方或运行证据确认。

“模拟器为了性能关闭此动画”当前尚未证实。

## 已取得实际实现文本（2026-10-04）

安装镜像中存在 `system/lib64/libgraphics_effect_ext.z.so`（675,904 字节），并非空实现。SHA-256 为 `5a61a59bab0d82e5c5f01eb20732e1d1f30ef73c441c52b3aca122b41e689d9c`。其只读数据段保留可读 GPU 着色器，且本版本保留了 `GEParticleAblationFilter::OnProcessImage`、`GetParticleAblationEffect`、`DrawParticleImage` 等符号。此前关于扩展库无法获取、只有两个工厂接口可见的结论，不能直接用于这一已安装的 x86_64 镜像。

只读提取物保存在被 Git 忽略的 `tmp/dialog-takeover-20261004/harmonyos7/`；不向仓库提交华为二进制或完整反汇编文本。`shader-string-index.json` 记录原始文件偏移和长度，`extract_shader_strings.py` 可重建提取结果。

- 粒子更新着色器位于扩展库文件偏移 `0x411e9`，长度 9,060 字节，已完整读取。它用消蚀遮罩控制何时激活预先确定的候选采样点，从输入图像取色；风位移与粒子年龄平方成正比，湍流由出生位置及粒子年龄驱动的三维 simplex noise / 多层噪声生成，并乘以年龄。位置从 `initPosition` 重新求值，不是逐帧积分前一帧的位置。
- 尺寸最终由 `UpdateScale` 覆盖，按寿命前 20% 增大、末 40% 缩小；不能误把此前 `UpdatePosition` 内的另一套尺寸表达式当成最终生效公式。最终寿命、半径、时间单位及噪声系数仍需核对宿主端常量。
- SceneBoard 的 `formparticle.hsp` 包含可反汇编的 `ets/modules.abc`；DevEco 自带 `ark_disasm.exe` 保留了类名、方法名、源文件路径和参数常量。`FormParticleConfig` 默认最大粒子数 20,000；卡片尺寸 1×2、2×2、2×4、4×4、6×4 对应 10,000、20,000、30,000、40,000、50,000 个候选粒子。它们是上限，不能直接当作同一帧实际可见数量。
- 该卡片默认参数包含 `ablationRate=2.1`、`glowLevel=8`、`glowBrightness=2`、`turbScale=300000`、`turbEvo=20`、`turbAmp=400`、`duration=2000 ms`。运行前 `setParticleParam` 将风改为 `[randomInteger(-30000,30000),40000]`。`FORCE_STOP_PARTICLE_MS` 静态默认值为 1170；配置覆盖方法默认入参为 1500，需追踪调用方后再确定实际截断时间。
- 这些是本地 HarmonyOS 7 卡片消散的实现证据；尚不能证明此前全部视频参考使用完全相同的版本和路径。需同时核对旧 `GASIFY` 路径及通知清除调用方。

## 两条效果路径必须区分

已通过扩展库静态初始化函数与实际渲染函数的交叉引用定位以下文本，避免仅凭字符串名称归类：

| 路径 | 镜像中实际行为 | 文本文件偏移 |
| --- | --- | --- |
| `GEParticleAblationFilter` 遮罩 | 二维噪声阈值消蚀，或带噪声扰动的单向推进；输出预乘颜色与透明度 | `0x48b8a` |
| 同一滤镜的粒子更新 | 遮罩释放候选粒子，出生位置与年龄驱动风及噪声位移 | `0x411e9` |
| `GEGasifyScaleTwistFilter` | 从 3×3 图集中读取 RG 变形坐标，在若干关键帧间插值，再反向采样源图 | `0x46d6e` |
| `GEGasifyFilter` | 从 8×8 图集选取消失遮罩，`step=58*progress+1`；叠加增亮与末段衰减 | `0x43cad` |

`libhdsvisualcomponentimpl.z.so` 中保留两套场景 JSON：`MaterialGasifyEffect` 接收 `distortedTexture`、`blurTexture`、`gasifyTexture`；`ParticleGasifyEffect` 接收粒子消蚀参数。已提取安装镜像中的 `distortedTexture.png`（1200×1200）与 `gasifyTexture_icon.png`（1414×1414）。**用户此前已明确目标为 HarmonyOS 7 新粒子消散；旧 `GASIFY` 仅作排除混淆的背景，不能拿旧气化图集替代目标粒子算法。** 同一镜像共存两条路径，不代表它们同时参与目标效果。

## 参数语义与实际运动公式

公开头文件给出参数结构顺序；镜像构造函数将这 80 字节顺序复制到对象偏移 96。结合实际读偏移及着色器调用，可确认以下语义，不能再仅从名字猜测：

| 外层参数 | 本镜像中的实际用途 | 直接证据 |
| --- | --- | --- |
| `centers0=(a,b)` | 工作分辨率下 `radius=a/2`、`radiusRange=b/2` | `DrawParticleImage` 的 `0x96770–0x96778` |
| `centers1=(a,b)` | 新出生粒子寿命范围为 `(1-progress)*(a,b)`，单位秒 | `0x96780–0x96798`，帧时间增量在 `0x970a8` 除以 1000 |
| `centers2=(a,b)` | `u_mode=a`、`u_dir=b`：噪声消蚀或方向推进及方向编号 | `OnProcessImage` 的 `0x95cd9`、`0x95d0f` |
| `centers3` | 本次核对的滤镜执行函数尚未发现读取 | 不将其解释为第四起点 |
| `wind` | 内层取外层的一半，再将 y 分量取负 | `0x9729a–0x972a9` 与更新着色器 |
| `turbScale`、`turbAmp` | 内层均取外层的一半；`turbEvo` 原值传入 | `0x972f7–0x97331` |

工作坐标中，对年龄 `t`、出生位置 `p0`，粒子从 `p0` 重新求位置：风位移为 `0.5*t²*0.05*windInner*(0.7+0.6*random)`；噪声位移为 `t*ampInner*(noiseX,noiseY)`。噪声空间频率为 `500/(scaleInner+50)`，时间采样为 `t*turbEvo*0.005` 加少量时间偏移和粒子随机相位。实际配置为两层 simplex noise，第二层幅度乘 0.5、频率乘 1.5；Y 噪声采样相对 X 偏移 `(100,100)`，随机相位幅度 0.5。最终 x 再作宽高比修正；精确屏幕像素尺寸仍须连同工作表面与最终绘制核对。

粒子不循环重生：候选位置由索引哈希确定，遮罩 alpha ≤0.9 后尝试激活；输入快照透明点不会产生有效粒子。寿命结束标记 inactive。颜色来自快照，alpha 随机取 0.6–1.0；若 RGB 三通道均低于 0.1961，则三通道同时抬到该值。最终粒径乘以前 20% 寿命的线性增长及末 40% 寿命的线性收缩。`glowLevel` / `glowBrightness` 虽在接口和调用方中存在，目前不能仅凭传参认定额外辉光确实执行。

## 系统调用方与启用条件

- 桌面卡片 `FormParticleManager.isEnableRemoveParticle` 要求粒子提供者已注册、`VisualEffectUtil.isSupportParticle()` 为真，并排除特定小艺卡片和附加禁用条件。后者要求 `scb_immersive_light_enable` 参数字符串为 `true`，且 HDS 当前材质透明级别为 `THIN`。能力不足时原代码日志为 `ccm not support or transparency not thin`。
- 通知的 `HarmoniumEffectVm` 同样使用粒子场景，有单条滑动删除和清除全部两组参数。滑动删除默认 650 ms、消蚀速率 1.2、风横向 ±30000 / 纵向 15000；清除全部默认 1500 ms、消蚀速率 2.1、横风随机 ±30000 / 纵向 120000。候选数按动画高度计算，上限 100000，不能沿用公开单元测试中的 500–2000 作为真实系统数量。
- 通知 `EnvVm.updateHarmoniumParams` 会根据 HDS 材质支持、透明级别和低效果模式切换气化/粒子标志，故“库存在”不等于当前设备一定播放。
- SceneBoard 通过配置策略加载 `etc/scb_effect_config/scb_visual_effects_config.json` 及 `etc/multiwindow/multiwindow_effects_config.json`。当前只确认代码中的条件，尚未取得本次运行的完整有效配置；过滤后的 HiLog 缓冲没有相关记录。因此暂不将未播放唯一归因于性能或某个具体开关。

SceneBoard 主 ABC 为 31,106,212 字节，完整反汇编中间文本约 178 MB，已在提取相关函数后删除；保留约 46 KB 的相关函数证据和行号索引，避免产生大批缓存。

## 证据保存与后续验证范围

- 本次仅检查用户明确指向的本地 HarmonyOS 模拟器和已安装镜像；未修改系统设置、权限、镜像或系统应用。`persist.sys.graphic.animationscale` 的读取返回不可用，不能把缺少参数当作动画已关闭。
- `evidence-manifest.json` 保存提取库、ABC、HSP 与纹理的 SHA-256；相关函数单独保留，六份可再生完整反汇编以 gzip 无损压缩，并验证解压后的 SHA-256 完全一致。删除了重复提取的 SceneBoard 主 HAP，安装镜像仍保留原件。新增检查目录最终约 87.64 MiB。
- 关键证据文件：`shader-strings/offset-000411e9.txt`、`shader-strings/offset-00048b8a.txt`、`formparticle/modules.pa`、`SceneBoard/particle-selected.pa`、`systemuicommon/particle-selected.pa`、`systemuicommon/gates-and-config.pa`、`launchercommon/particle-selected.pa`、`basecommon/transparency-selected.pa`、`componenthelper/visual-feature-selected.pa`、`ablation-host-annotated.txt`。这些路径均相对于 `tmp/dialog-takeover-20261004/harmonyos7/`。
- 下一阶段应以实际 `PARTICLE_ABLATION` 的出生、运动、寿命和尺寸规则建立独立桌面验证版，先确认工作分辨率、粒子绘制形状／合成方式和外层进度曲线，再与十二段参考逐帧及连续播放比较。保持旧钢铁侠基准与统一规则要求；当前没有证据允许把预制旧气化图集或逐参考拟合场作为正式替代。
- 当前结果是取得核心算法文本与调用链，**不是取得完整 C++／ArkTS 源码工程，也不是已验证与参考视频一致的移植版**。设备能力配置为何在当前模拟器没有放行动画仍未唯一定位；核心算法研究已不依赖先让模拟器播放。

## 桌面复刻前补充核对

- 本镜像的 `lib2d_graphics.z.so` 中，粒子构建工厂 `0xfa920`、粒子效果工厂 `0xfa960` 记录日志后直接返回空对象；`SkiaCanvas::DrawParticle`（`0x1044b0`）仅记录 `SKIA does not support Particle`。`libddgr.z.so` 只有 4,824 字节，未提供另一套实际粒子绘制实现。因此，本 x86 镜像的绘图后端缺少该能力已有直接证据；此前未播放不能简单归结为尚未打开设置。
- HDS 的 `AnimateProgressShader` 通过重定位 `0x182c30` 取得 `OHOS::Ace::Curves::LINEAR`，外层进度使用线性曲线。卡片时长仍为 2 秒；强制结束计时与录像裁切需独立处理。
- 消蚀遮罩在快照原分辨率计算；输入取色图和粒子工作表面缩至一半。粒子初始位置包括扩展表面中的居中偏移，噪声必须以该位置采样，不能改成卡片局部原点。合成顺序为先粒子、后残留原表面。
- 原始随机遮罩参数为 MT19937 的一个 32 位输出乘 `2^-32`，范围约为 `[0,1)`；它与粒子索引哈希不是同一随机来源。
- 运动／消蚀／寿命规则可以据此复刻；最终粒子的栅格化形状和混合细节无法从这个被裁减的绘图后端恢复。桌面首版将单独标明替代栅格化及录像物理尺度的假设，不能称为完整原生渲染等价实现。
