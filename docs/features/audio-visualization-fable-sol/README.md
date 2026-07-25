# audio-visualization-fable-sol

把桌面模拟器 `audioVisualizerSimulatorFable`（PySide6 + numpy）的**实时分析 → 可视化**
链路移植进 EverythingDone，作为录音对话框的水波可视化。目标：视觉效果、物理效果
一比一复刻原版；命名统一用 `FableSol` 后缀（对照现有 `Opus` 后缀）；现有 Opus 代码保留。

## 本目录文档

- `decisions.md` / `sessions.md` / `followups.md` / `preferences.md` — 决策、会话、遗留项与偏好
- `on-device-perf-testing.md` — 实机性能测量方法
- `rim-edge-testing.md` — 银丝沿轮廓连续性的测量方法（栅格相位相关判据、D219 基线数字、暗带排除性检查）

## 范围

**移植**（原版纯数学核心 + 渲染，作者已注明是"Android 移植蓝本"）：

- 实时音频特征链：`features.py` / `speed.py`
- 音频→驱动映射：`mapping.py`
- 九层水体物理：`simulation.py` / `waves.py` / `ambient.py`
- 渲染：`canvas.py`（环境天空、九层水面、C2 B-spline fairing + Hermite 重建、镜面/菲涅尔高光、波峰透光、波冠轻纱、珍珠色）
- 常量/参数：`spec.py` / `params.py`（参数取默认值硬编码）

**不移植**：桌面文件读取/播放 UI（`decode.py`/`offline.py`/`engine.py`、`panel.py`/
`meters.py`/`main_window.py`）、可选神经人声模型、离线前瞻导演和进程 IPC。Android 使用
自己的实时 PCM 输入、原生调参 Dialog 与线程模型；实时 Foote 段落、因果 Drop 和 DSP 人声
运动证据属于实时分析链，已随 FableSol 移植。

## 关键决策

- **配色**：物理/高光一比一；配色不用原版独立 palette，改接记事的 `ThingBackground`
  （支持纯色 + 渐变，渐变方向沿用记事的 8 向 `Orientation`，复用 `BackgroundUtil.createLinearGradient`）。
  原版"远层混白空气透视 / 色彩呼吸 / OKLab 混色 / 珍珠偏色"逻辑保留，但混白量随层深
  `depth01` 增长：第 0 层恒不混白，直接使用记事本色。纯色的 c1/c2 都取 `bg.color`；渐变的
  c1/c2 取原始 `color/endColor` 并保留方向。见 decisions.md D1、D6。
- **接入**：直接把录音对话框可视化换成 `WaveVisualizerFableSol`，Opus 的 .kt 全部保留、不删。
- **坐标/尺寸**：波长、浪高、速度与网格间距继续使用 Fable 的 dp 标尺；`320dp` 只作为
  网格采样间距和 View 尚未完成测量时的回退基准。运行时物理容器宽度来自
  `WaveVisualizerFableSol.onSizeChanged()` 的最终实测 `w / density`，不读取 XML 的 `280dp`，
  也不从 TimelyClockView 的声明尺寸推算。容器高度保持 420dp。体积守恒、墙面、倾斜水位、
  可见跨度和按宽度定义的事件全部使用这项实测宽度。见 decisions.md D2、D8。
- **采样率**：复用现有采集 44100Hz（原版 48000Hz）。`FRAME_RATE=SR/HOP` 自适应，
  算法结构不变，仅频率分辨率有极小差异（bin 21.5Hz vs 23.4Hz），视觉无感。见 decisions.md D3。
- **可信声音门**：A 加权感知总能量限制在 16kHz 以下，flux 的 32 个对数频带严格止于 12kHz；
  Analyzer 从静音启动，并以 −66~−54dBFS smoothstep 置信度缩放响度、频段和 onset，避免手机
  近 Nyquist 电子干扰及 AGC 泵动被相对归一放大。见 decisions.md D9。
- **采集启动预热**：Android 首次启动 AudioRecord 时，连续的低频暂态在最多 4.5 秒的自适应预热内
  不驱动水位或 onset；稳定静音或可信中高频内容持续 0.3 秒即可提前放行。只保护可视化，不裁剪 WAV。
- **浪形连续性**：onset 不再直接修改程序化主浪；快速能量只进入 DynamicWave 物理注入。
  HeroWave 只按慢包络改变，几何粗糙度与快速光学材质分离。见 decisions.md D12。
- **段落检测**：移植实时 Foote 新奇度 `_NoveltyDetector`（驱动性格档切换；段涌 surge_gain 默认 0）。
- **因果感知标定**：动画主链消费固定声学域的 `W/S/K/I`、四类音乐运动、能量上升与
  grade 证据；旧 `loudness01` 只保留诊断兼容，不再驱动水位或浪形。麦克风采集显式套用
  手机录音补偿，原始 A 计权静音门始终读取未补偿 PCM。
- **七境与巨浪**：七境以软时长、迟滞和证据释放实时解码；`LIFT` / `CLIMAX` 可持续。
  巨浪只在 `PEAK` / `CLIMAX` 的有效音乐运动到达时触发，全局冷却 14 秒、无每段配额；
  物理实体固定为第 0 层 840dp × 144dp C2 宽峰，按真实流速从画外自然通过。
- **录音状态**：PREPARED/STOPPED 继续监听并驱动低透明度水面，开始录音不重置 Analyzer；
  这是 Android 录音预览设计，不要求跟随 Python 播放器状态机。见 decisions.md D7。

## 线程模型（对照原版进程/UI 定时器解耦）

- **音频线程**（`AudioRecorder.RecordingThread`）：PCM(16bit)→float → `FableSolRealtimeAnalyzer.feed()`
  → `(frames, events)` → 经 `FableSolFrameReceiver` 塞进 View 的并发队列。
- **渲染线程 / Canvas 回退 UI 线程**：drain 队列 → 按音频时间逐个消费所有 authoritative
  frame，并把稀疏 event 稳定交织回相邻 hop → `Simulation.update(dt)` → 渲染。不能只取最新
  frame，否则会漏掉 0.2 秒证据窗并让 60/120Hz 屏幕得到不同状态。
- `Simulation` 只被当前渲染所有者单线程访问：GLES 主路径为 GL 线程，Canvas 回退为 UI
  线程，因此无需加锁；音频线程只生产 frames/events。

## 命名映射（Python → Kotlin，包 `views.recording.fablesol`）

| 原版 | Kotlin |
|------|--------|
| `spec.py` | `FableSolSpec.kt` |
| `params.py` | `FableSolParams.kt` |
| `core/waves.py` `DynamicWave` | `FableSolDynamicWave.kt` |
| `core/ambient.py` Ambient/Hero/Optical | `FableSolWaveSets.kt` |
| `core/simulation.py` Simulation/LayerSim | `FableSolSimulation.kt` / `FableSolLayerSim` |
| `core/mapping.py` FeatureMapper | `FableSolFeatureMapper.kt` |
| `audio/state_evidence.py` | `FableSolCausalStateEvidence.kt` |
| `core/state_decoder.py` / `core/states.py` | `FableSolSoftDurationGradeDecoder.kt` / `FableSolSevenStateMachine.kt` / `FableSolContinuousStateChannels.kt` |
| `core/grand_wave_gate.py` / `core/grand_wave.py` | `FableSolGrandWaveEventGate.kt` / `FableSolGrandWave.kt` |
| `audio/speed.py` | `FableSolSpeed.kt` |
| `audio/features.py` RealtimeAnalyzer/RingStat/BeatTracker/NoveltyDetector | `FableSolRealtimeAnalyzer.kt` / `FableSolRingStat.kt` / `FableSolBeatTracker.kt` / `FableSolNoveltyDetector.kt` |
| feature dict / event dict | `FableSolFeatureFrame.kt` / `FableSolEvent.kt` |
| `ui/canvas.py` VisCanvas 渲染 | `WaveVisualizerFableSol.kt` |
| `canvas.py` OKLab/hue 工具 | `FableSolColor.kt` |
| — | `FableSolFrameReceiver.kt`（接收器接口） |

## 重力→倾斜

原版 `set_tilt(deg)`；Fragment 已把重力投影到屏幕平面 `(screenX, screenY)`。
`WaveVisualizerFableSol.setContainerGravity(x,y,z)` 内部换算
`deg = toDegrees(atan2(x, y))` 传给 `Simulation.setTilt(deg)`。Android 实现接受完整 360° 重力方向，
完全倒置时保持 ±180°，并在 179°↔−179° 边界选择最短连续旋转，因此水体可与旧 Opus 一样转到
Dialog 顶部。录音 Dialog 打开时锁定宿主 Activity 当前方向，销毁或关闭时恢复原方向；该生命周期与
切换 FableSol 前的 Opus 实现相同。原版无 z 通道，z 暂忽略。
