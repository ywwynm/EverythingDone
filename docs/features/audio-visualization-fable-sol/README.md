# audio-visualization-fable-sol

把桌面模拟器 `audioVisualizerSimulatorFable`（PySide6 + numpy）的**实时分析 → 可视化**
链路移植进 EverythingDone，作为录音对话框的水波可视化。目标：视觉效果、物理效果
一比一复刻原版；命名统一用 `FableSol` 后缀（对照现有 `Opus` 后缀）；现有 Opus 代码保留。

## 范围

**移植**（原版纯数学核心 + 渲染，作者已注明是"Android 移植蓝本"）：

- 实时音频特征链：`features.py` / `speed.py`
- 音频→驱动映射：`mapping.py`
- 九层水体物理：`simulation.py` / `waves.py` / `ambient.py`
- 渲染：`canvas.py`（环境天空、九层水面、Catmull-Rom、镜面/菲涅尔高光、波峰透光、波冠轻纱、珍珠色）
- 常量/参数：`spec.py` / `params.py`（参数取默认值硬编码）

**不移植**：文件读取（`decode.py`/`offline.py`/`engine.py` 播放段）、参数调整 UI
（`panel.py`/`meters.py`/`main_window.py`）、语义段落（`semantic.py`，原版默认停用
`SEMANTIC_REALTIME=False`）、离线段落导演（`sections.py`）、进程/IPC（`ipc.py`/`audio_client.py`）。

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
- **段落检测**：移植实时 Foote 新奇度 `_NoveltyDetector`（驱动性格档切换；段涌 surge_gain 默认 0）。
- **录音状态**：PREPARED/STOPPED 继续监听并驱动低透明度水面，开始录音不重置 Analyzer；
  这是 Android 录音预览设计，不要求跟随 Python 播放器状态机。见 decisions.md D7。

## 线程模型（对照原版进程/UI 定时器解耦）

- **音频线程**（`AudioRecorder.RecordingThread`）：PCM(16bit)→float → `FableSolRealtimeAnalyzer.feed()`
  → `(frames, events)` → 经 `FableSolFrameReceiver` 塞进 View 的并发队列。
- **UI 线程**（View vsync `onDraw`）：drain 队列 → 只对最新 frame `applyFrame`、对每个 event
  `applyOnset`/`applySection` → `Simulation.update(dt)` → 渲染。
- `Simulation` 只被 UI 线程访问，无需加锁；音频线程只生产 frames/events。

## 命名映射（Python → Kotlin，包 `views.recording.fablesol`）

| 原版 | Kotlin |
|------|--------|
| `spec.py` | `FableSolSpec.kt` |
| `params.py` | `FableSolParams.kt` |
| `core/waves.py` `DynamicWave` | `FableSolDynamicWave.kt` |
| `core/ambient.py` Ambient/Hero/Optical | `FableSolWaveSets.kt` |
| `core/simulation.py` Simulation/LayerSim | `FableSolSimulation.kt` / `FableSolLayerSim` |
| `core/mapping.py` FeatureMapper | `FableSolFeatureMapper.kt` |
| `audio/speed.py` | `FableSolSpeed.kt` |
| `audio/features.py` RealtimeAnalyzer/RingStat/BeatTracker/NoveltyDetector | `FableSolRealtimeAnalyzer.kt` / `FableSolRingStat.kt` / `FableSolBeatTracker.kt` / `FableSolNoveltyDetector.kt` |
| feature dict / event dict | `FableSolFeatureFrame.kt` / `FableSolEvent.kt` |
| `ui/canvas.py` VisCanvas 渲染 | `WaveVisualizerFableSol.kt` |
| `canvas.py` OKLab/hue 工具 | `FableSolColor.kt` |
| — | `FableSolFrameReceiver.kt`（接收器接口） |

## 重力→倾斜

原版 `set_tilt(deg)`；Fragment 已把重力投影到屏幕平面 `(screenX, screenY)`。
`WaveVisualizerFableSol.setContainerGravity(x,y,z)` 内部换算
`deg = toDegrees(atan2(x, y))` 传给 `Simulation.setTilt(deg)`（符号真机校准）。
原版无 z 通道，z 暂忽略。
