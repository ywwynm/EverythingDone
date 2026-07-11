# 决策记录 · audio-visualization-fable-sol

## D14 美学章程：克制的抒情（2026-07-11）

升级方向定位采用"克制的抒情"：以平静陪伴为基底（录音对话框里用户的主任务是说话，
水在周边视觉工作），但保留完整表达阶梯——七境式的状态分辨率、只在真峰值出现的瞬态
高潮，以及"张力=相位"级别的作品手段。表达力由阶梯的分辨率承担，不由反应幅度承担。
一句话章程（暂定措辞）："你的声音是掠过一小片真实水面的风——它服从重力，短暂记住
你的能量，在你说完时归于平静。"运动总则："静中有动、动中有静"。

**范围事实**：当前主打麦克风录音可视化（录音内容可能包含音乐）；后续将加入离线音乐
文件的可视化。音乐相关投入（节拍、speech/music 门控、高能境）须按可复用设计，原版
被裁掉的离线路径（`offline.py`/`sections.py`）未来可能回归。

**为何**：设计奖调研显示评审奖励克制、同步感与概念纯度（Odio/Journey/Monument
Valley 先例）；"准确反映声音"与"获奖美感"在 B 定位下不冲突。

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

## D9 自校准前增加频率边界与绝对可听度安全门（2026-07-10）

Android 近静音录音可能包含近 Nyquist 强电子干扰、低频嗡声和非平稳 AGC/噪声泵动。纯相对静音门与
flux 百分位会把这些不可闻或极低电平变化放大成约 0.5 响度和密集 onset，因此“自校准”不能等于
“完全没有绝对可信度边界”。

FableSol 前端统一采用以下规则，Python 蓝本与 Kotlin 实现保持一致：

- A 加权总能量、底噪、质心与平坦度只使用 16kHz 以下频谱；
- 32 个 flux 对数频带严格按相邻边界 `[edge[i], edge[i+1])` 求和并止于 12kHz，不再使用
  `reduceat` 最后一段延伸到 Nyquist 的语义；
- Analyzer 和时间基准重置后从静音状态启动；
- −66dBFS 为零可听度置信、−54dBFS 为满置信，中间采用 smoothstep；响度、低/中/高频动态与
  onset 包络/强度统一乘该置信度。绝对门仅拦截极低电平假活动，可信声音进入后仍使用原有底噪、
  慢中心和滚动百分位自校准；
- flux 环接收全部帧作为基线，保证稀疏真实脉冲无需先积满多个“有声帧”。

回归样本 `20260710215433.wav` 在 Python 48kHz 路径由 90 个假 onset 降至 3 个、平均响度由
0.272 降至约 0.004；Android/Kotlin 原生 44.1kHz 路径为 4 个 onset、平均响度约 0.008。

## D10 Android 重力倾角覆盖完整圆周并保持 Opus 方向锁定语义（2026-07-10）

录音 Dialog 的重力可视化必须接受完整 360° 屏幕平面方向。完全倒置手机时渲染角保持 ±180°，
水体填充方向转向 Dialog 顶部，不得沿用 Python 桌面调节滑块的 `[-90°, 90°]` 限制。
传感器角度跨越 179°↔−179° 时，Simulation 选择与当前角度最近的等价角，避免水体反向旋转 358°。
墙面过渡按水面偏离水平面的角度计算，因此 0° 和 180° 都视为水平水面。

`AudioRecordDialogFragment` 在 `onCreateView()` 锁定宿主 Activity 的当前方向，并在
`onDestroyView()`/`onDismiss()` 恢复原 `requestedOrientation`。该代码在 Opus→FableSol 迁移中没有
改动，继续作为录音 Dialog 的既有行为保留。

## D11 Android 采集会话启动阶段抑制低频暂态（2026-07-10）

部分手机的 AudioRecord/MIC 在采集启动后会输出持续数秒、随后衰减的强低频暂态。样本
`20260710231609.wav` 前 0~3 秒约为 −20~−23dBFS，A 加权能量约 70%~90% 位于 250Hz 以下，
约 4.5 秒后才降至 −55dBFS 以下。该信号真实存在于 PCM；应用当前显式关闭 AGC/NS/AEC，不能把它
简单归因于应用层“先录噪音、再开始降噪”。

Kotlin Analyzer 在每个采集会话开头增加自适应预热门：低频主导且尚未稳定的输入不进入底噪、中心、
flux 与视觉输出；连续稳定静音或可信非低频内容达到 0.3 秒后立即开放，最多等待 4.5 秒。若超时后仍是
低频背景，则把当前电平种为噪声底。该门只影响 FableSol 可视化，不裁剪、滤波或延迟保存的 WAV，
避免在用户未授权的情况下改变录音内容。

## D12 已形成的浪形不得被瞬态参数直接重塑（2026-07-10）

已经显示的浪只能因相位传播、波动方程、阻尼、边界、重力，或显式注入的新能量自然变化。
onset 不得直接改写整层 HeroWave 的振幅和频段模态；快速事件统一进入有穿屏限流的 DynamicWave
物理波包。HeroWave 只表达慢变化的声音背景：默认攻击由 0.16 秒改为 0.85 秒，各模态最短约 0.72 秒；
几何粗糙度另用 1.2 秒慢状态，快速粗糙度/毛细度仍可用于光学高光，但不能重塑宏观轮廓。

真实样本的拍击 onset 位于约 6.30、6.76、7.15、7.26 秒。禁用物理注入时，旧 onset 仍使既有轮廓在
6 帧内变化最高约 0.56dp RMS；极端频段目标单帧变化约 3.97dp RMS。修复后前者严格为 0，后者约
0.39dp RMS；真实拍击窗口不再出现远层同步突变，剩余变化来自连续传播和新注入的物理能量。

## D13 连续流速由表层事件率主导，节拍不得删除 subdivision（2026-07-11）

人的主观速度由表层事件密度、节拍层级和强弱等多种线索共同决定。FableSol 的连续流速采用 1 秒快速事件率与 3 秒保持事件率：快速通道以 72% 权重负责及时上升，慢速通道作为下限负责稳定与自然释放。已通过 D9 绝对可听度/SNR 边界的 onset，在显著度加权之外固定保留 75% 原始 subdivision；`beatConfidence` 不得再降低该保底。

tempo/phase 继续负责节拍脉冲和相位同步；tempo 对连续流速仅能在剩余行程内提供最高 12% 的正向佐证，不得以中等 BPM 向下覆盖已经较高的表层事件密度。onset strength 主要控制波高、注入能量、punch 与材质。分析侧速度攻击为 0.35 秒，Simulation 流速执行平滑为 0.48 秒；物理流速上限和全局 `flow_gain` 不因本决策提高。Python 与 Android/Kotlin 必须维持同构实现。

**为何**：固定 3 秒分母会把新出现的密集声音系统性低估约 3 秒；可靠节拍下删除弱 subdivision 会把快速元音或密集音符误判为中速；tempo 与 density 的凸组合还会让约 110 BPM 反向拉低每秒 6~8 个事件的表层速度。两段 Android 真机录音证明瓶颈位于感知映射，而不是 213.6dp/s 的第 0 层物理上限。
