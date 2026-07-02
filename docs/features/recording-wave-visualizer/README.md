# 录音波形可视化改造（recording-wave-visualizer）

把录音对话框里的音量可视化，从"底座 + 竖直柱子"改造成"一片会起伏的水体波浪"。

## 范围

- 仅作用于录音对话框的实时录音动画：
  [AudioRecordDialogFragment.kt](../../../app/src/main/java/com/ywwynm/everythingdone/fragments/AudioRecordDialogFragment.kt)
  + [fragment_record_audio.xml](../../../app/src/main/res/layout/fragment_record_audio.xml)
  + [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)
  + [AudioRecorder.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/AudioRecorder.kt)
  + [VoiceAudioFrame.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceAudioFrame.kt)。
- `VoiceVisualizer` 全项目仅此一处引用；录音结束后在详情里播放已保存音频是另一套 UI，**不在本次范围**。

## 现状（改造前）

- 底部一条 96dp 彩色"底座"（`view_voice_visualizer_base`），承载重录 / 主按钮 / 取消三个控件；
  纯色记事填纯色，渐变记事用 `BackgroundUtil.applyBackground` 铺真实渐变。
- 底座上方由 `VoiceVisualizer` 画 24 根竖直柱子：随机高度、按分贝缩放、单一 `accentColor` 纯色。
- 数据流：[AudioRecorder](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/AudioRecorder.kt)
  每 100ms 采样麦克风，算出分贝整数 `10*log10(振幅)`，`receive(decibel)` 推给可视化控件。

## 目标形态

一片由记事颜色填充的"水体"，水面是多层半透明、多分量的 Gerstner 式波浪；声音越强，浪越大，
不同频段驱动不同尺度的波，骤起声音触发短时浪涌。
详见 [decisions.md](decisions.md)。

## 当前数据流

- [AudioRecorder](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/AudioRecorder.kt)
  继续用 `AudioRecord` 采集 PCM；每次读取约 512 个 stereo frame，并约每 20ms 基于最近
  2048 个单声道样本生成一帧 `VoiceAudioFrame`。
- `VoiceAudioFrame` 包含 `loudness`、`low`、`lowMid`、`mid`、`high`、`air`、`transient`，
  以及 `onset`、`beatPulse`、`beatPhase`、`tempoBpm`、`tempoConfidence`、`rhythmEnergy`、
  `lowPulse`、`highPulse`、`activity`。RMS/分贝控制整体强度，5 个 FFT 频段控制大浪/主体浪/细纹，
  瞬态控制短时浪涌；onset/beat/tempo 特征控制更快的节奏强调层；`activity` 作为有效活动度，
  安静时衰减节奏、细节和高频变化，强 onset 或明显能量变化时快速唤醒。D25 之后使用最近
  512 个样本的 fast RMS/rise 作为低延迟 onset 快通道，并限制高频细节对几何轮廓的影响。
- [VoiceVisualizer](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)
  保留 `receive(Int)` 兼容入口，但主路径是 `receive(VoiceAudioFrame)`。

## 相关

- 记事颜色模型：`ThingBackground`（`PURE` 单色 / `GRADIENT` 双色 + 8 向），见 CONTEXT.md 词条 **Thing Background**。
- 颜色派生工具：`DisplayUtil.getLightColor/getDarkColor`、`BackgroundUtil.lighter/darker/blendColors`。
## 2026-07-02 — D26 当前补充

- `AudioRecorder` 每次读取约 512 个 stereo frame，并约每 20ms 输出一帧 `VoiceAudioFrame`。最近 512 个样本的 fast RMS/rise 仍作为低延迟 onset 快通道，2048 FFT 继续提供 5 个频段与节奏特征。
- `VoiceVisualizer` 的动画帧循环由 `postInvalidateOnAnimation()` 跟随系统 vsync，通常是 60fps 或设备当前刷新率；音频采样间隔只决定视觉目标更新频率，不是绘制帧率。
- rhythm / surge / beat phase 的低延迟响应不能直接写当前视觉值，而是通过目标包络合并、限速相位吸附和每帧平滑追随实现。
- 录音水体使用渐变记事颜色时，shader 起止点遵循 `ThingBackground.orientation` 的 8 向方向。
## 2026-07-02 - D27 当前补充

- `VoiceVisualizer` 在音频特征进入视觉目标前增加 `stableInput()` 死区，过滤分量、水位、节奏能量和脉冲里的微小变化，减少 20ms 特征更新带来的果冻感。
- `AudioRecorder.restartListening()` 成为重新开始按钮的唯一重启入口；内部会停止并等待旧 `RecordingThread`，再创建新的 raw 文件和监听线程。
- `RecordingThread` 使用线程私有停止标记，并捕获启动时的 raw 文件和 `AudioRecord`，避免旧监听线程在下一轮 start 后继续读取。
## 2026-07-02 - D28 当前补充

- 停止录音和重新开始录音不再在 UI 点击回调里同步等待 `AudioRecord.stop()`、`RecordingThread.join()` 或 raw -> wav 转存。
- `AudioRecordDialogFragment` 会先更新界面状态，再用后台线程完成录音收束、文件写入和监听重启；后台完成前临时禁用相关按钮，避免文件或监听状态竞态。
- dialog 关闭时的 recorder release 与临时 raw 目录清理也在后台顺序执行。
## 2026-07-02 - D29 当前补充

- `AudioRecorder.VoiceAudioAnalyzer` 会把持续响度、onset/fast impact 和 spectral flux 合成为 `activity`，用于区分安静、有效活动和强冲击。
- 安静时 rhythm/beat/onset/pulse 只保留很低权重或被门控，避免近静音噪声让节拍层持续活跃；强 onset 可绕过安静门快速唤醒。
- `VoiceVisualizer` 根据 `activity` 分别衰减主体驱动、细节驱动、rhythm trigger、tempo confidence、rhythm contour 和 drift boost；静音微动改为更慢、更低频的独立 idle 波。
## 2026-07-02 - D30 当前补充

- `VoiceVisualizer` 的水面绘制使用固定 121 个采样点，并对高位波峰保存上一帧 y 值；峰顶变化小于阈值时不更新，以减少高点细小抖动。
- 主相位横向推进由 `activity` 与 `rhythmEnergy` 控制：安静时低速流动，有效声音出现时恢复速度；tempo confidence 足够时按 BPM 对流速做轻量快慢调整。
- 慢速随机包络、idle 波和基线微漂也使用活动度缩放后的时间，避免安静时底层随机项仍全速变化。
## 2026-07-02 - D31 当前补充

- D30 的逐采样点峰顶 y 冻结会造成相邻采样点不连续，已删除。
- 峰顶稳定改为连续细节衰减：主体波形进入高位波峰区间时，`detailS` 通过 smooth step 降低参与比例，不直接替换最终 y。
- 水面采样数从 120 提到 180，用户后续手动调为 216；活动度驱动横向流速继续保留。
## 2026-07-02 - D32 当前补充

- 工位安静态反馈后，`activity` 的响度、事件和 spectral flux 门槛上调，避免轻微环境声推高活动度。
- 低活动状态的主体浪、细节浪、idle 波和基线微漂底线下调，让水面更平。
- 横向主相位速度改为 `FLOW_ACTIVITY_START/FULL` smooth step 门控；低活动时只保留很慢流动，明确有声音或节奏后再提速。
## 2026-07-02 - D33 当前补充

- 空调风噪反馈后，音频侧将 flux 拆成 low / mid / high；`activity`、onset 和 fast impact 主要由 low/mid/body flux 驱动，high/air 只保留很低权重。
- `transient` 和 high pulse 的高频权重降低，避免稳态 hiss / swish 持续驱动视觉。
- 视觉侧 body/detail 的 activity 映射改为 smooth step 门控；低活动时主体浪更平、细节几乎关闭，横向底速和 rhythm phase 进一步降低。

## 2026-07-02 - D34 当前补充

- 峰高稳定改为连续 envelope 方案：逐层 `ampBoost` 和 `crestFactor` 使用死区平滑，避免小幅 rhythm/surge 变化持续改写已成形波峰。
- 主体分量的慢速随机包络强度下调，细节分量保留较多随机性；低活动状态下两者都会进一步收敛。
- 高位波峰的细节衰减提前并增强，减少峰顶局部上下抖动；不再使用逐采样最终 y 冻结。

## 2026-07-02 - D35 当前补充

- `VoiceAudioFrame` 新增 `intensity` 与 `pace`：前者表示声强对比，后者表示 onset / impact / tempo 得到的速度感。
- `AudioRecorder` 新增自适应 loudness contrast，把正常音量和大音量拉开；新增事件密度 pace，快速说话和快节奏音乐会更快抬高。
- `VoiceVisualizer` 不再只用 `activity` 控制浪高和流速；浪高由 `intensity` 参与放大，横向流速和波形变化速度由 `pace` 参与放大。

## 2026-07-02 - D36 当前补充

- 回看 D3/D15 后，确认水位变化曾被降级为近固定；本轮恢复适度水位涨落。
- `VoiceVisualizer` 新增独立 `levelDrive`，由 `loudness` 与 `intensity` 混合驱动水位，声音小水位低，声音大水位高。
- 水位范围从 `0.36..0.42` 扩到 `0.24..0.49`，并使用独立上升 / 回落时间常数 `0.32s / 0.64s`，让水位像慢速潮位而不是跟着每个音节跳。
