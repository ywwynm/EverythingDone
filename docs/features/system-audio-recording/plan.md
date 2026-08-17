# 实施计划

## 目标

在现有录音 Dialog 中加入“麦克风”“系统”“麦克风+系统”三种音频输入，并让最终输入同时驱动 FableSol 和 WAV 保存。正式录音由前台服务持有，离开应用后继续；输入选择、MediaProjection 授权、异常收尾和 UI 状态均遵循 `decisions.md`。

## 总体结构

### 1. 音频输入领域模型与持久化

- 新增稳定的 `AudioInputMode`，以固定字符串键表示三种来源，避免依赖枚举顺序或本地化名称。
- 使用项目现有 `Def.Meta.PREFERENCES_NAME` 保存最近一次选择；Android 8/9 读到系统类值时，运行期降级为麦克风。
- 把来源名称、系统音频能力与选择结果作为录音会话状态的一部分，供 Dialog 和通知共同显示。

### 2. 录音会话前台服务

- 新增 `AudioRecordingService`，通过本地 Binder 暴露会话命令和只读快照；捕获、混音、FableSol 分析、文件写入及 MediaProjection 生命周期均由服务持有。
- Dialog 可见时绑定服务。正式录音前，麦克风可在绑定服务内预览；系统类来源必须在捕获期间以前台服务的 `mediaProjection` 类型运行，即使只是准备态预览。
- 正式录音按来源声明运行期前台服务类型：麦克风为 `microphone`，系统为 `mediaProjection`，混合模式同时声明两者；Android 版本不支持某类型常量时按该版本允许的类型组合执行。
- 正式录音切到后台后继续。准备态进入后台时停止 PCM 捕获；系统类输入为合法持有尚未结束的 `MediaProjection` 保留“系统音频授权已启用”前台通知，回到 Dialog 后恢复预览。停止态不继续捕获。
- Dialog 只订阅服务的实时状态和 FableSol 输出；不可见时解除 FableSol 接收器，返回后从下一批实时特征继续，不回放后台积压。

### 3. MediaProjection 授权与撤销

- Fragment 使用 Activity Result API 请求授权；Android 14+ 用 `MediaProjectionConfig.createConfigForDefaultDisplay()` 请求整个屏幕，旧版本使用普通捕获 Intent。
- 授权结果交给服务创建 `MediaProjection`。每次会话注册 `MediaProjection.Callback`，用户从系统入口撤销时立即通知会话。
- 准备态授权取消、初始化失败或预览硬错误时，当前值和持久化值都回退为麦克风并显示原因。
- 正式录音期间投影或任一必需输入硬终止时，停止整次录音、封装并保留部分 WAV；不得静默降级。

### 4. PCM 采集、混音与 WAV

- 新增服务专用 `AudioCaptureEngine` 以支持可配置输入和同一采样率协商：麦克风单声道，系统立体声，混合输出立体声。现有 `AudioRecorder` 继续供 FableSol 调参工具使用，避免把服务生命周期与已有独立预览用途耦合。
- 系统音频通过 `AudioPlaybackCaptureConfiguration` 捕获允许的 `USAGE_MEDIA`、`USAGE_GAME` 和 `USAGE_UNKNOWN`。
- 混合模式按固定增益把单声道麦克风居中加入系统左右声道，并在总线峰值超过阈值时使用连续软限幅；不做 ducking。
- 混合模式尝试启用平台 `AcousticEchoCanceler`；不可用时继续录制并向 UI 提示使用耳机。纯麦克风继续显式关闭 AGC、NS 和 AEC。
- 所有模式都先生成“最终保存 PCM”；FableSol 和旧分析器消费该 PCM 的单声道副本，原始文件写入同一份最终 PCM。
- WAV 头、byte rate、block align、时长和重力轨迹时长按实际声道数计算。

### 5. 无系统信号与错误状态

- 独立检查系统输入流，不被混合模式的麦克风信号掩盖。
- 连续 6 秒未检测到有效系统 PCM 后显示“未检测到可捕获的系统音频，部分 App 可能禁止捕获”；恢复信号后立即清除。
- 区分内容静音与捕获硬错误：静音只提示并继续，`ERROR_DEAD_OBJECT`、投影撤销等错误触发已确认的停止/回退规则。

### 6. Dialog 与项目 Popup UI

- 在 `TimelyClockView` 下方增加来源行；收起态显示“音频输入”和当前来源。
- 新增继承项目 `PopupPicker` 的 `AudioInputPicker`，复用已有圆角 surface、12dp elevation、缩放转场、项目色彩和行 ripple；不使用原生 `PopupMenu`、`Spinner` 或 Material exposed dropdown。
- Popup 项使用标题和副说明两层文本，支持禁用态；Android 8/9 的系统类项保持可见但不可点。
- 准备态显示且可选；录音态与停止态整行隐藏且不占空间（原"停止态只读显示"方案已被 2026-08-15 用户裁定取代）；重新录音后回到准备态并重新显示。

### 7. 通知与返回路径

- 录音通知显示来源和基于 `elapsedRealtime` 的时长，点击返回当前 `DetailActivity`/录音 Dialog。
- 提供“停止并保留”服务操作，不提供暂停。后台停止后保留一条完成通知，点击可回到停止态。
- 系统类准备态预览使用单独文案“正在预览系统音频”，仅 Dialog 可见时存在。

## 验证策略

- 为固定混音、软限幅、立体声下混、稳定来源值、可变声道 WAV 头和 FableSol 隐藏期队列清理补充 JVM 单元测试；Android 生命周期与权限状态通过编译检查和真机清单验证。
- 运行相关单测与 `:app:assembleDebug`，检查 Manifest 合并、资源和 Kotlin/Java 编译。
- 不使用 ADB；MediaProjection 授权、后台切换、系统撤销和真实播放捕获需要用户在 Android 10+ 设备上验收。

## 非目标

- 不捕获、编码或保存屏幕画面。
- 不绕过来源 App 的 playback-capture policy。
- 第一版不实现软件 AEC、自动 ducking、暂停录音或录音中切换来源。
