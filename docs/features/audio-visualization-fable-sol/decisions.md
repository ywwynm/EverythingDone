# 决策记录 · audio-visualization-fable-sol

## D1 配色接 ThingBackground（2026-07-10）

物理与高光一比一复刻原版；配色不用原版独立 palette，改接记事 `ThingBackground`，
支持纯色 + 渐变，渐变方向沿用记事 8 向 `Orientation`（复用 `BackgroundUtil.createLinearGradient`）。
原版"远层混白空气透视 / 色彩呼吸 / OKLab 混色 / 珍珠偏色 / 高光"逻辑全部保留，只把每层
两个基色 c1/c2 换成记事色：c1=bg.color；c2=渐变取 endColor，纯色取 `BackgroundUtil.lighter(color)`。
环境天空同样由记事色派生浅色。

**为何**：录音对话框需与当前记事主题一致，不能突兀地换成绯紫配色板。

## D2 坐标：dp 视觉标尺固定，对话框高度对齐 420dp（2026-07-10，宽度部分由 D8 修订）

Fable 按 dp 定义的波长、浪高、速度和网格采样间距继续一比一复用。`fragment_record_audio.xml`
把高度 360→420dp，对齐 `HEIGHT_DP`；渲染按 Android `displayMetrics.density` 把 dp 换算为 px。
本决策最初保留了 320dp 固定物理宽度，后由 D8 修订为运行时最终实测宽度。

## D3 采样率复用现有 44100Hz（2026-07-10）

原版 48000Hz；复用现有 `AudioRecorder` 的 44100Hz 采集。`FRAME_RATE=SR/HOP` 自适应
（512 hop → 86.13 帧/秒 vs 原版 93.75），所有基于 `FRAME_RATE` 的时间常量随之自适应，
算法结构不变；仅 N_FFT=2048 的频率分辨率有极小差异（21.5 vs 23.4 Hz/bin），视觉无感。

## D4 移植实时 Foote 新奇度段落检测（2026-07-10）

移植 `features.py` 的 `_NoveltyDetector`（倒谱 DCT + 棋盘核，实时因果路径），驱动性格档
（mood）切换。语义 MusiCNN（`semantic.py`）原版默认停用，不移植；离线段落导演
（`sections.py`）不移植。段涌巨浪 `surge_gain` 默认 0，段落主要表达为性格档差异。

## D5 线程模型：音频线程产帧，UI 线程消费（2026-07-10）

对照原版"音频进程 + UI 60fps 定时器"解耦。音频线程 `feed` PCM 得 frames/events 进并发
队列；UI 线程 vsync 每帧 drain 队列 → 只对最新 frame `applyFrame`、对每个 event
`applyOnset`/`applySection` → `Simulation.update(dt)` → 渲染。`Simulation` 只被 UI 线程
访问，无需锁。

## D6 第 0 层保持 Thing 原始颜色（2026-07-10）

距离屏幕最近的第 0 层代表当前 Thing 的主体身份色：纯色 Thing 的第 0 层必须完整使用
`background.color`；渐变 Thing 的第 0 层必须保留原始 `color`、`endColor` 与
`orientation`。原模拟器中“纯色 palette 自动生成向白混合 45% 的第二端色”不再用于第 0 层，
`color_breath`、`moodBright` 与 `lighten_far` 产生的混白也必须随 `depth01` 增长，因此第 0 层
混白量恒为 0。远层空气透视、声音明度变化、OKLab 层内混色、高光与物理逻辑继续保留。

**为何**：原模拟器 palette 是独立视觉配色，而 EverythingDone 的 `ThingBackground` 是记事身份色。
最近层若继续使用模拟器的自动提亮规则，会使纯色和渐变记事在正常录音态下都比记事本身浅灰，
也违背旧 `WaveVisualizerOpus`“主体/前景层使用记事本色”的既有语义。

## D7 PREPARED/STOPPED 持续监听并驱动水面（2026-07-10）

Android 录音对话框在 PREPARED 和 STOPPED 状态继续监听麦克风，以低透明度水面提供实时预览；
进入 RECORDING 时只开始写入录音文件，不重置 `FableSolRealtimeAnalyzer`、不重置其时间基准。
停止录音后立即重新开始监听也属于设计行为。保存 WAV 的起止仍严格由 `mIsRecording` 控制。

**为何**：这是 EverythingDone 的录音交互语义，不需要复刻 Python 桌面播放器只在
PLAYING/RECORDING 消费驱动的状态机。

## D8 物理容器宽度使用 View 最终实测宽度（2026-07-10）

运行时容器宽度必须来自 `WaveVisualizerFableSol.onSizeChanged(w, ...)` 的最终像素宽度除以 density。
不得直接使用 XML 的 `280dp`，也不得根据 TimelyClockView 的声明宽度自行推算；父布局、Dialog 测量和
屏幕约束共同决定出的最终 View 宽度才是唯一来源。

`REFERENCE_WIDTH_DP=320` 只保留为网格间距 `DX_DP` 的设计基准和 View 尚未测量时的回退值。
运行时的容器湿润跨度、沿重力方向尺寸、体积守恒倾斜水位、墙面边界、屏幕坐标注入中心以及
段落 surge 的 75% 宽度均使用实测容器宽度。波长、浪高、速度、固定 dp 注入宽度和
`DX_DP` 不随 View 缩放，以免改变原版的空间尺度、传播速度和数值稳定性。
