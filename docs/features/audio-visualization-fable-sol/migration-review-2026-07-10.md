# Python → Android 迁移审查（2026-07-10）

## 结论

未发现会导致音频特征、九层水体物理或光学高光整体失真的高严重度移植错误。`Params`、
`Spec`、实时特征、速度、节拍、Foote 新奇度、FeatureMapper、DynamicWave、Ambient/Hero/Optical、
Simulation 与 Canvas 渲染公式已逐项对照；主要数学链路保持一致。

当前最需要处理的是 Android 接线与生命周期语义，而不是 Python 公式翻译：

1. Android 在 PREPARED/STOPPED 状态仍持续分析麦克风并驱动水面；用户已确认这是录音预览设计，
   不属于迁移 Bug。
2. View 在不可见期间仍可能接收并累计全部 feature frame；渲染和分析路径也存在较多短数组分配，
   需要真机确认后台内存、GC 与帧率。
3. 固定 320dp 物理宽度问题已修复：物理容器改用 View 最终实测宽度，320dp 只保留为网格基准和测量前回退。
4. FableSol 自动化测试目前覆盖第 0 层颜色策略、运行时容器几何和近静音噪声 Analyzer 回归，
   仍缺少由 Python 版生成的完整 Mapper/Simulation golden fixture。

## 已确认一致的部分

- `GLOBAL_SPECS`、九层 `LAYER_SPECS` 与 Kotlin 参数表无缺项、无默认值偏差。
- `N_FFT=2048`、`HOP=512`，A 加权、dB 标定、底噪跟踪、静音迟滞、混合归一化、频段能量、
  spectral flux、onset、密度速度、beat tracker、Foote 新奇度公式一致。
- FeatureMapper 的持续驱动、incoming/rhythm 注入、层权重、声学角色与冷却逻辑一致。
- 九层体积守恒倾斜、固定 120Hz 子步、边界吸收/反弹、Ambient/Hero/Optical 与高光公式一致。
- Android 使用 mono，因而 `stereoWidth01=0`、`pan01=0.5`；与 Python 对 mono 输入的退化行为一致。
- 44.1kHz 替代 48kHz、`java.util.Random` 替代 numpy PCG64、ThingBackground 替代 palette、
  MusiCNN/离线导演不移植，均属于已有明确决策。
- 第 0 层改为 Thing 原色后，音频/情绪混白随 `depth01` 增长；这是 D6 产品语义，不是 Python 原版行为。

## 中等级别发现与后续结论

### 1. 录音状态边界与 Python 版不同（已确认为设计）

Android 在 Fragment 建立 View 时立即 `startListening()`；`startRecording()` 只切换文件写入标志，
不会重置 `FableSolRealtimeAnalyzer`。停止录音后又立即新建监听线程和一个全新的 Analyzer，且没有向
FableSol receiver 主动发送 silence。Python 版则在 `record_start` 执行
`reset(full=false)`、`set_time_base(0)` 并清空待消费帧/事件，UI 也只在 PLAYING/RECORDING 时消费音频驱动。

影响：第一次录音会继承 PREPARED 预听阶段的底噪、中心值、beat/onset 历史；STOPPED 状态仍会跟随现场声音；
停止或重录后 Analyzer 是 full reset，而不是保留声源校准的 partial reset。保存的 WAV 起止边界仍由
`mIsRecording` 控制，不受此问题影响。

用户已确认保留这种实时预览语义：不增加录音开始时的 partial reset/time-base reset，也不在 STOPPED 状态
门控水面驱动。该差异已记录为 decisions.md D7。

### 2. 不可见期间的队列、动画与麦克风生命周期风险

`onAudioFrames()` 把每个 frame 全部加入 `pendingFrames`，但 UI drain 时只消费最后一帧；
`shouldAnimate()` 只检查 attached/尺寸，不检查 window visibility；Fragment `onPause()` 只停倾斜传感器，
不会停止 AudioRecorder。窗口不可见且没有发生 onDraw 时，frame 可能持续累计。

建议把连续 feature 改成“单槽 latest frame”，只给离散事件使用有上限队列；动画条件加入可见性；再明确应用进入后台时
是否继续占用麦克风。该项需要 Android 真机或 profiler 复核，当前属于高可信风险，不是已复现崩溃。

### 3. 280dp View 与 320dp 物理容器（已修复）

物理容器现已由 `WaveVisualizerFableSol.onSizeChanged()` 接收最终实测像素宽度并换算为 dp。XML 的
`280dp` 及 TimelyClockView 只参与上游布局测量，不直接进入物理。容器跨度、体积守恒倾斜水位、墙面、注入中心
和段落 surge 宽度均使用实测值；320dp 只保留为 `DX_DP` 网格标尺和测量前回退。见 decisions.md D8。

### 4. 自动化回归覆盖不足

现有 FableSol JVM 测试已覆盖 `FableSolLayerColorPolicy`、运行时容器宽度与完整倒置几何、Analyzer 的
频带边界/近静音/采集启动预热，以及 Mapper/Simulation 的浪形连续性。跨语言完整 golden fixture、beat、
FeatureMapper 全字段输出与 Canvas 截图仍缺少足够的自动防回归能力。

建议把 Python 输出固化为小型 fixture，至少覆盖：FFT/特征帧、onset/beat、FeatureMapper 输出、固定种子下的
Simulation 不变量；Canvas 可增加少量截图 golden 测试。

## 验证记录

- 同一段 44.1kHz、20 秒合成音频：Python 与 Kotlin 均输出 1719 feature frame、34 个 onset、0 个 section，
  首末时间戳均为 `0.023220s / 19.969161s`。
- 聚合结果：Python `meanLoud=0.577523`、`meanFlow=0.262826`；Kotlin
  `meanLoud=0.579886`、`meanFlow=0.254158`。主要差异来自 Kotlin 按实际 44.1kHz 动态计算 frame rate，
  而 Python 内部部分时间常量仍绑定 48kHz 全局 `FRAME_RATE`；这符合 D3。
- 桌面 JVM 烟测：20 秒 Analyzer 用时约 451.8ms（约 44.3× realtime）；纯 Simulation 600 帧约
  106.6ms（约 0.178ms/帧）。这些数字不包含 Android Canvas/GPU，也不能替代低端真机验证。
- `./gradlew :app:testDebugUnitTest` 通过。
- 未使用 adb 或模拟器。
