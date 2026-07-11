# 会话记录 · audio-visualization-fable-sol

## 2026-07-11 第二轮调研：听觉感知全景、水的美学本体、音画语法

用户反馈第一轮偏实现，要求全面覆盖人对声音的感知维度，并深挖水体如何更真实、更有质感、
更优雅地反映声音（美学设计与音画结合本身优先于实现）。三个并行检索任务完成后综合成
`research-2026-07-11-perception-aesthetics.md`：（1）感知全景——4Hz 音节波动强度、逼近偏差、
谐波性清澈度轴、粗糙度、纹理颗粒度、发声努力等被典型可视化忽视的维度，及水声愉悦性的
1/f/去相关/宽带平滑启示；（2）水美学——波群包络、涌浪+风浪双 register、蒲福外观阶梯、猫爪纹、
闪光带、驻波、马远《水图》×蒲福交叉的"七境表达量表"与"大师会删掉什么"清单；（3）音画语法——
费辛格/惠特尼/Chion/迪士尼/拉班的十五条操作原则、声音事件类→视觉手势类映射语法表、直译
同步边界；综合为七条设计论点（双 register 记忆、乐队分层、张力=相位、七境状态机、快触发慢
展开、休止语法、单色相多光学）并更新讨论问题。未改运行代码、未发布。

## 2026-07-11 视觉质量与音频相关性升级调研

用户要求以设计/物理/计算机科学视角充分调研如何提升 FableSol 的视觉质量与音频相关性（大小、
音高、节奏、节拍、情感），可考虑高级特征与轻量端侧 ML，先调研讨论、不实现。通读当前实现后，
五个并行检索任务分别覆盖实时音频特征、端侧 ML、Android 图形、设计参考、跨模态映射文献，
综合成 `research-2026-07-11-quality-upgrade.md`：四阶段提案（P1 速赢：SuperFlux+白化、K 计权
响度、speech/music 门控、渲染零分配、延迟测量；P2 语音表达力：YIN 音高→旋律浪、音节率、
重音事件、HNR→清澈度、arousal→mood；P3 可选 YAMNet 语义层 +5.7MB 与节拍编排；P4 AGSL
单 pass 渲染上限），并列出明确不做清单（valence/SER/学习型节拍/GPL 库/ONNX 第二运行时等）
与 7 个待讨论决策点。本轮未改任何运行代码、未发布。

用户确认按两段 Android 真机录音的研究结论实施。Python 蓝本与 Android/Kotlin 同步把连续流速改为双时间尺度表层事件率：1 秒快速通道以 72% 权重参与上升，3 秒通道负责稳定值与释放；已通过听觉门的原始 subdivision 在显著度加权之外固定保留 75%，不再随 beat confidence 升高而消失。tempo/phase 继续驱动节拍，只以最高 12% 的正向余量补充连续流速，不再以凸组合向下覆盖高事件密度。分析侧速度攻击由 0.65 秒缩短为 0.35 秒，Simulation 流速执行平滑由 0.72 秒缩短为 0.48 秒，释放仍由 3 秒保持通道和 1.10 秒分析侧释放约束。

真实 WAV 回放中，`20260710234846.wav` 的有声帧 `flow01` 中位数由约 0.49 提高到 0.65，第二个 onset 后约 0.92 秒越过 0.5，第 0 层中位流速约 128dp/s；`20260710235706.wav` 的中位 `flow01` 由约 0.51 提高到 0.83，第 0 层中位流速约 173dp/s，仍低于 213.6dp/s 物理上限。近静音样本 `20260710215433.wav` 仍仅检测到 3 个 onset，97.0% 帧保持静音，平均响度约 0.007，没有复发噪声驱动高速水流。

新增 Python `PerceivedSpeedTest`、`RealtimeSpeedResponseTest` 与 Kotlin `FableSolSpeedTest`、`FableSolRealtimeSpeedResponseTest`，覆盖可靠节拍不删除 subdivision、tempo 不下拉高密度、快速/慢速窗口职责、默认攻击响应和真实 Analyzer 密集脉冲提速；Python 10 项相关测试与完整 Android 单测通过。未使用 adb。已发布阿里云 Debug `202607101619`，APK SHA-256 为 `a49ece45531e35ae9dc64e4146fa7dc7e0ca192d551c34d2a1c3431b3851e4e1`，等待真机确认主观速度。

## 2026-07-11 结合两段真机录音研究感知流速模型

用户补充 91.347 秒 Android 录音 `20260710235706.wav`，反馈其对应水流同样偏慢，并要求结合此前快速“啦啦啦”录音 `20260710234846.wav` 与公开研究，判断如何让流速更贴近人的感知。本轮继续使用 Conda `everythingdone` 环境按当前 Python 实时链路回放，只做诊断、研究和方案设计，未修改运行代码、未发布。

短录音每秒检测到约 5.0 个 onset，稳定段 3 秒原始密度约 5.0~6.0 次/秒，但显著度加权后约 2.4~3.1 次/秒；当前 `flow01` 峰值约 0.54，第 0 层峰值约 107dp/s。3 秒固定分母还造成启动期系统性低估：从第二个 onset 起，当前流速约 2.45 秒才越过 0.4、约 3.74 秒才越过 0.5。长录音每秒检测到约 6.6 个 onset，有声帧的原始密度中位数约 6.67 次/秒，但显著密度中位数仅约 3.69 次/秒；节拍器长期锁定约 110 BPM 且置信度接近 1，使 `effectiveEventRate` 几乎只保留显著密度。其有声帧 `flow01` 中位数约 0.51，第 0 层中位流速约 103dp/s；物理上限 213.6dp/s 仍未成为约束。

研究复核显示，主观速度不是 beat tempo 的同义词：音乐实验与感知速度建模均把 tempo、不同类别的 onset/note density、spectral flux 等作为并列特征；语音实验也确认 syllable rate 是主要速度线索，segment rate/音节复杂度还能提供额外线索。当前实现与此相冲突的地方有两处：一是 beat confidence 越高，表层 subdivision 越容易被删除；二是以凸组合融合 tempo 与 density，约 110 BPM 的中等拍速会反向拉低已经很高的表层事件密度。

建议下一轮以 Python/Android 同构方式试做双通道模型：表层事件率负责连续流速，采用约 1 秒快速估计与 3 秒保持估计，消除固定 3 秒分母的启动偏差；beat tempo/phase 继续负责节拍脉冲与少量正向佐证，不再删除 subdivision，也不再向下覆盖表层速度。onset 显著度主要控制波高、注入能量和材质，不应决定一个已通过绝对可听度/SNR 门的事件是否计入流速。候选范围为原始 subdivision 至少保留 70%~85%、分析侧攻击约 0.25~0.35 秒、物理执行侧平滑约 0.4~0.5 秒、释放约 0.8~1.1 秒；最终系数需用真机 A/B 主观标注校准，不能仅凭两段样本一次定死。

## 2026-07-10 诊断快速“啦啦啦”未产生预期高速水流

用户提供 8.382 秒 Android 录音 `20260710234846.wav`，反馈快速连续发“啦啦啦”时水流不够快，要求判断
是物理速度上限不足，还是音频没有触发高速驱动。Python 分析继续使用 Conda `everythingdone` 环境；
另用临时 JVM 回放探针核对 Kotlin 原生链，探针验证后已删除。

两端均检测到 40 个 onset，说明音节没有漏检。3 秒窗口的原始 onset 密度最高约 5.67 次/秒，但多数
onset 强度为 0.3~0.5；经过显著度加权及可靠节拍下的 subdivision 降权后，有效事件率峰值约
4.08 次/秒。即时速度目标最高约 0.553，0.65 秒攻击平滑后 Python `flow01` 峰值约 0.504，Kotlin
峰值约 0.509；平滑只贡献约 0.04~0.05 的差值，不是主要限制。

Kotlin Simulation 最近层实际峰值约 99.1dp/s，而参数允许的理论上限为 213.6dp/s，仅使用约 46%；
Simulation 基本跟随输入，没有触顶。结论：问题在感知速度映射把“密集但单个不尖锐的元音音节”解释为
中快，不在水流物理上限。若要让此类快速发声更快，应优先提高弱 onset/原始 subdivision 在
`effective_event_rate` 中的保底权重；其次才是略缩短速度攻击。直接提高 `flow_gain` 或物理上限会让所有
同等 `flow01` 的声音一起加速，针对性较差。本轮只诊断，未修改运行代码、未发布。

## 2026-07-10 抑制采集启动低频暂态并禁止既有浪形被音头重塑

用户提供 Android 录音 `20260710231609.wav`：打开录音 Dialog 后，即使环境安静，水位仍会先升后降；
约第 7 秒的拖鞋拍地声还会让几层已经成形的浪生硬改形。Python 分析与测试统一使用 Conda 环境
`everythingdone`。

录音前 0~3 秒的 PCM 实际约为 −20~−23dBFS，约 70%~90% 的 A 加权能量位于 250Hz 以下，
到约 4.5 秒才降到 −55dBFS 以下。这不是 Analyzer 凭空产生；当前应用显式关闭 AGC/NS/AEC，无法仅凭
WAV 判断是 HAL/硬件启动暂态还是拿放手机的机械低频。Android Analyzer 新增采集会话预热门：稳定静音或
可信非低频内容持续 0.3 秒即可开放，最长 4.5 秒；样本原生 Kotlin 回放中前 4 秒最大视觉响度和 onset
均为 0，首个非静音视觉帧在 5.759 秒。门只影响可视化，不修改保存的 WAV。

样本拍击 onset 位于约 6.30、6.76、7.15、7.26 秒。禁用 DynamicWave 注入后，旧 onset 仍会通过
HeroWave punch 在 6 帧内让既有轮廓变化最高约 0.56dp RMS；极端频段目标单帧可变化约 3.97dp RMS。
Python 与 Kotlin 同步取消 onset 对 HeroWave 的直接改写，快速事件只进入物理波包；HeroWave 攻击改为
0.85 秒，各模态最短约 0.72 秒；几何粗糙度另以 1.2 秒慢追，快速材质只影响光学。修复后无物理注入的
onset 轮廓变化严格为 0，极端目标单帧约 0.39dp RMS；真实拍击窗口峰值由旧回放约 0.54 降到约
0.34dp RMS/帧，且不再由远层同步突变主导。

新增 Python 与 Kotlin 回归测试；Python 5 项测试、FableSol JVM 测试、完整 Android 单测和 Debug 构建
均通过。最终发布阿里云 Debug `202607101544`，未使用 adb。

## 2026-07-10 修复完全倒置时水体未转到 Dialog 顶部

用户真机发现完全倒置手机时，FableSol 水面和波浪没有像迁移前的 Opus 一样来到录音 Dialog 顶部，
并要求核对 Dialog 存续期间是否禁止 Activity 自动旋转。

以 180° 重力输入应产生 ±180° 渲染角作为确定性复现信号，新增几何回归测试后确认
`FableSolSimulation.setTilt()` 沿用了 Python 桌面滑块的 `[-90°, 90°]` 限制，把完全倒置稳定截成
侧向 90°。修复为完整圆周角度，并对 179°↔−179° 做最近等价角展开，避免跨边界时反向旋转 358°；
墙面过渡改按偏离水平面的角度计算，使 0° 和 180° 都具有正确的水平水面边界语义。

检查迁移提交前后的 `AudioRecordDialogFragment` diff，确认方向锁定代码没有变化：打开时保存
`requestedOrientation` 和屏幕 rotation 后设置 `SCREEN_ORIENTATION_LOCKED`，在 `onDestroyView()` 或
`onDismiss()` 恢复，重力传感器到屏幕坐标的映射也与 Opus 相同。新增测试修复前 2 项失败、修复后
全部通过；完整 `:app:testDebugUnitTest` 与 `:app:assembleDebug` 通过。已发布阿里云 Debug
`202607101512`，未使用 adb。

## 2026-07-10 修复 Android 近静音噪声被识别为高响度/高活跃度

用户提供 Android 录音 `20260710215433.wav` 及另一会话的分析。复核确认文件在人耳意义上接近安静，
但包含 18.3kHz 强干扰、约 100Hz 嗡声和 5~10 秒的非平稳增益泵动。旧 Python 前端在前 5 秒输出
37 个 onset、平均响度 0.449，5~10 秒再输出 38 个 onset、平均响度 0.600；整段共 90 个假 onset。

诊断确认 `reduceat` 最后一带越过 12kHz 是代码 Bug，但单独修复并不会把前 5 秒 onset 从 37 降低；
直接根因是 A 加权总能量仍累计到 Nyquist，18.3kHz 干扰因而持续撑开静音门。限制总能量到 16kHz 后，
前 5 秒才降至约 6 个 onset；5~10 秒仍有约 40 个，证明还需绝对可听度门抑制 AGC 泵动。

Python 与 Kotlin 同步实现 D9：16kHz 听觉分析上限、12kHz 严格 flux 边界、静音启动、
−66~−54dBFS smoothstep 可听度置信、所有帧进入 flux 基线。新增两端回归测试，覆盖超声调制噪声
保持近静音、频带不越界和明确可听稀疏脉冲仍可检测。修复后真实 WAV 在 Python 路径为 3 个 onset、
平均响度约 0.004；Kotlin 原生 44.1kHz 路径为 4 个 onset、平均响度约 0.008。
完整 Android 单测与 Debug 构建通过；已发布阿里云 Debug `202607101456`，未使用 adb。

## 2026-07-10 物理容器改用 View 最终实测宽度

用户确认两项产品语义：PREPARED/STOPPED 持续监听并驱动水面属于设计；物理容器宽度应使用 Dialog
布局完成后 `WaveVisualizerFableSol` 的最终实测宽度，而不是 XML 的 280dp，也不是固定 320dp。

新增 `FableSolContainerGeometryTest`，先稳定复现水平跨度错误（320dp，而实测宽度为 280dp）和 30° 倾斜
跨度错误（487.128129dp，而实测宽度应为 452.487113dp），再实现修复。View 通过 `onSizeChanged(w, ...)`
传入 `w / density`；Simulation 的容器跨度、沿重力方向尺寸、体积守恒水位、墙面和注入中心改用运行时宽度；
FeatureMapper 的段落 surge 宽度改为运行时宽度的 75%。320dp 仅保留为网格采样间距和测量前回退。

修复后原始几何复现信号与新增测试均通过；完整 `:app:testDebugUnitTest`、`:app:assembleDebug` 通过，未使用 adb。
最终发布阿里云 Debug `202607101423`；首次上传的 `202607101422` 因更新说明小节不完整，已由最终版本替代。

## 2026-07-10 Python → Android 迁移审查

逐项对照实时音频特征、速度/节拍/Foote 事件、FeatureMapper、九层物理、Ambient/Hero/Optical 与 Canvas
渲染，未发现高严重度公式移植错误。使用同一段 44.1kHz、20 秒合成音频做 Python/Kotlin 差分烟测，双方均输出
1719 帧与 34 个 onset，时间戳一致；聚合 loudness/flow 的小差异来自 D3 的动态 frame rate 适配。

审查当时发现四项差异：Android 录音开始/停止不采用 Python 的 analyzer reset/time-base/gating 语义；不可见期间
feature frame 队列与动画/麦克风生命周期可能造成内存、GC 或后台占用；首版 View 实测宽度与 320dp 物理容器
不一致；核心迁移缺少 Python golden fixture 回归测试。其后录音状态被确认为设计，容器宽度已由 D8 修复。完整结论见
`migration-review-2026-07-10.md`。`./gradlew :app:testDebugUnitTest` 通过，未使用 adb。

## 2026-07-10 首次移植并接入

把桌面模拟器 `audioVisualizerSimulatorFable`（PySide6 + numpy，约 2500 行纯数学核心 + 渲染）
的**实时分析 → 九层水体物理 → 渲染**一比一移植为 Kotlin，替换录音对话框可视化（Opus 保留）。

- **新建包** `app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/`：
  Spec/Params/FeatureFrame/Event/Color/Math/Rng/Speed/Fft、DynamicWave/WaveSets/Simulation/FeatureMapper、
  RingStat/BeatTracker/NoveltyDetector/RealtimeAnalyzer、FrameReceiver、WaveVisualizerFableSol。
- **接入**：`AudioRecorder.linkFableSol`（采集线程 PCM→float→feed→分发 frames/events）；
  `AudioRecordDialogFragment` + `fragment_record_audio.xml` 切换到 `WaveVisualizerFableSol`，
  对话框高度 360→420dp。
- **首版决策**：配色接 `ThingBackground`（纯色+渐变+8 向，见 D1）；物理最初固定 320×420dp 逻辑坐标、
  只改高度（D2，宽度部分后来由 D8 修订）；采样率复用 44100Hz（D3）；移植实时 Foote 段落检测（D4）；音频线程产帧、
  UI 线程消费的无锁线程模型（D5）。
- **编译**：`:app:assembleDebug` 通过（修掉 `FableSolPending` 可见性、补 `WaveVisualizerFableSol.onMeasure`
  固有尺寸避免撑大对话框）。
- **发布**：阿里云 debug `202607101136`，待真机验证视觉/物理/性能/重力符号/配色。

关键说明：RNG 用 `java.util.Random`（非 numpy PCG64），逐值不同但分布/层间差异一致，满足"行为
一比一"而非"逐帧像素一致"。

## 2026-07-10 修复水体透明 + 透明度闪烁

真机反馈水体一直透明且逐帧闪烁。根因：渲染复用单个 `Paint`，高光带 `setColor` 把 Paint.alpha 改小
并泄漏到下一层水面渐变填充（Android 会用 Paint.alpha 调制 shader），近层不透明主体被压成半透明、
透出浅天空 → 显透明；泄漏 alpha 随高光每帧变 → 闪烁。修复：拆 `fillPaint`（渐变填充，alpha 恒 255）
/ `bandPaint`（纯色带）。附带 View alpha 0.16→1.0 对齐 Python 不透明。发布 debug `202607101241`。
详见 `debug-updates/update-20260710201205.md`。

## 2026-07-10 诊断最近层水面比 Thing 本色浅灰

真机反馈：正常录音态下，距离屏幕最近的第 0 层水面明显比当前 Thing 背景色更浅、更灰。
通过对照 `WaveVisualizerFableSol`、原始 `audioVisualizerSimulatorFable/canvas.py` 与旧
`WaveVisualizerOpus` 的颜色链路确认：这不是 Paint alpha、View 录音态透明度或 OKLab 移植错误，
而是移植时沿用了原模拟器的 palette 语义——纯色背景会先生成一个向白混合 45% 的 `c2Base`，
第 0 层再把 `c1Base → c2Base` 画成覆盖整个水面以下区域的竖直渐变；同时 `color_breath` 与
`moodBright` 对第 0 层也继续向白混色。第 0 层自身 alpha 为 1.0，因此最终可见的主体正是这层
已经被提亮的渐变。

旧 Opus 的产品语义则是最近层直接使用 Thing 的纯色或原始渐变，只让远层提亮。建议 FableSol
恢复这一语义：纯色背景的两个基础端点都使用 `background.color`，并让空气透视/声音明度变化
随 `depth01` 生效，使第 0 层混白量恒为 0；远层、高光与物理逻辑继续保留。本轮只完成诊断，
尚未修改渲染代码。

## 2026-07-10 修复第 0 层纯色与渐变偏浅

用户确认纯色和渐变 Thing 都应让第 0 层直接保持记事颜色。新增可独立单测的
`FableSolLayerColorPolicy`：纯色基础色的 start/end 都复制 `background.color`，不再生成向白混合
45% 的第二端色；渐变继续保留原始 `color`、`endColor` 与方向。`lighten_far`、`moodBright`、
`color_breath` 的合成混白量统一乘以 `depth01`，保证第 0 层恒为 0，远层仍保留空气透视和声音明度变化。

回归测试先在旧规则下稳定出现 3 项失败，再应用修复；随后新增的 4 项颜色策略测试与完整
`:app:testDebugUnitTest` 均通过，`:app:assembleDebug` 通过。未使用 adb；视觉效果待用户通过阿里云
Debug 版本真机确认。已发布阿里云 Debug `202607101341`。
