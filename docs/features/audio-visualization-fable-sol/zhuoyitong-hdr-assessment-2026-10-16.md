# 卓易通 FableSol 屏幕 HDR 兼容评估

## 结论与证据范围

用户反馈同一台已能通过图片查看器 PQ Surface 正确提亮的卓易通设备，在音频海浪设置中仍显示不支持 HDR。本轮评估可行方案和改动范围，没有修改应用实现、编译、发布、提交或操作设备。

结合此前该设备的诊断值与当前代码，可以解释设置禁用：显示能力报告不完整，而 FableSol 把这些报告作为硬性前提。新增仅在已知卓易通环境尝试的 PQ 屏幕输出是可行方向；图片路径的成功证明了本机存在可用输出方式，但不替代海浪圆角、合成与动态切换的实机验证。

## 当前阻断点

1. `FableSolTuningDialogFragment.isHdrDisplaySupported()` 要求 API 34+ 且 `display.isHdr && display.isHdrSdrRatioAvailable`。此前该设备两项均为 false，设置直接禁用强度滑杆。
2. `WaveVisualizerFableSolGl.canBuildHdrSurface()` 重复同一门槛；`desiredSurfaceFormat()` 和 `willChangeSurfaceFormat()` 另外依赖 `Configuration.isScreenHdr`，仅移除设置判断无法建立 HDR 输出。
3. `FableSolEglSession` 只有 FP16 linear scRGB 与 RGBA8 SDR，且在缺少 EGL 扩展声明时提前回退。此前该设备不声明相关扩展，但实际 PQ 创建成功；scRGB 虽提亮却有颜色问题。
4. `currentHdrSdrRatio()` 在能力未报告时返回 1，随后 `FableSolHdrPolicy.usableHeadroom()` 将额外亮度归零。即使建立 HDR Surface，继续沿用这一数据仍不会产生 HDR 高光。

本轮用此前诊断的 false／false 值回放设置方法中的实际布尔表达式，得到禁用；true／true 对照得到启用。另核对底层同门槛及比例为 1 的收缩路径。这是源码条件验证，不是新的设备 UI 或画面回归。

Android 官方说明：`isHdrSdrRatioAvailable()` 为 false 时，`getHdrSdrRatio()` 固定返回 1；不能把它当作实际测量到的屏幕峰值。见 [Display API](https://developer.android.com/reference/android/view/Display#getHdrSdrRatio())。

## 建议改动范围

初步约 6—8 个核心文件，另加针对性测试和必要状态文案；属于显示输出层的局部适配。具体数量取决于是否抽出共享的输出模式／能力策略。

| 位置 | 预期处理 |
| --- | --- |
| `FableSolTuningDialogFragment` | 已知卓易通环境允许尝试兼容输出，不以缺失声明直接判不支持；创建失败后明确回退状态。关闭档本身不能导致滑杆无法重新开启。 |
| `WaveVisualizerFableSolGl` | 统一设置能力、缓冲格式、格式切换和 HDR 余量来源；只有特定兼容路径绕过原报告前提。 |
| `FableSolEglSession` | 增加 PQ 输出模式，核实实际 EGL 创建、返回的颜色空间和位深，失败保留 SDR；普通 Android 保持现有 scRGB 路径。 |
| `FableSolGlRenderThread` | 向渲染器传递实际输出模式与创建状态，避免仅用一个 HDR 布尔量混淆 scRGB 与 PQ。 |
| `FableSolGlRenderer` 与 `shared/fablesol/glsl/present.frag` | 保留 FP16 线性场景与全部光学合成，仅在最终呈现时转为 BT.2020／PQ；保留原 SDR／scRGB 输出结果。 |
| `FableSolHdrPolicy` 或新输出策略类 | 兼容路径区分实际测量比例与应用内容亮度预算；未知比例不再误当 1 倍实际上限，也不伪称屏幕支持用户设定的全部亮度。 |

可复用的已有实现包括图片 `HdrProbeSurface` 的 PQ 创建／验证经验，以及海浪自身 `export_present.frag` 中的 Rec.709→BT.2020、ST 2084 编码和参考白定义。前者是静态图片渲染器，不直接嵌入海浪；后者的输出数学可以用于屏幕最终呈现，不改变视频编码／导出规则。

Khronos 规定 PQ Surface 接收应用已编码的 PQ 数据，不能仅更换 Surface 色彩空间而继续输出线性值。见 [EGL BT.2020 扩展](https://registry.khronos.org/EGL/extensions/EXT/EGL_EXT_gl_colorspace_bt2020_linear.txt)。

## 需专门验证的细节

- 海浪使用预乘 alpha 的圆角边缘，图片已经验证的 RGBA1010102 仅有 2 位 alpha；不能直接假定同样的配置不会让圆角边缘分档。需验证合成结果，再决定是否采用其它实测可用的 PQ 缓冲配置或边缘合成方式。
- 关闭档必须继续真正退到 SDR，保留 D220 的颜色修复和 D221 的换格式首帧遮盖。不能在此轮顺便将所有普通 Android 都切到 PQ。
- 继续保持只有录音／播放时增加局部 HDR，准备、暂停、停止时收回；沿用现有内容强度语义，参考白和未知屏幕余量要独立标定，不能把输出尼特值等同于面板实测亮度。
- 先验证设置能力策略、PQ 数值转换、失败回退与原 SDR／scRGB 像素一致性，再由用户检查卓易通圆角、暗色与高光、强度开关及启停闪烁。共享 shader 的默认行为要保证桌面模拟器不变。

音频分析、九层水体模拟、浪形、配色、闪点与眩光的生成逻辑均已有 HDR 数据，不需要为此次兼容重写。最终实现前应将 ADR-0016 中“仅 scRGB＋实时比例”的历史限定更新为包含经过验证的兼容输出；本次评估不把候选方案记作已实现决策。
