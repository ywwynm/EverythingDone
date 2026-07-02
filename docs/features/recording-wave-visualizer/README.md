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
  继续用 `AudioRecord` 采集 PCM；每 100ms 基于最近 2048 个单声道样本生成一帧
  `VoiceAudioFrame`。
- `VoiceAudioFrame` 包含 `loudness`、`low`、`lowMid`、`mid`、`high`、`air` 和 `transient`：
  RMS/分贝控制整体强度，5 个 FFT 频段控制大浪/主体浪/细纹，瞬态控制短时浪涌。
- [VoiceVisualizer](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)
  保留 `receive(Int)` 兼容入口，但主路径是 `receive(VoiceAudioFrame)`。

## 相关

- 记事颜色模型：`ThingBackground`（`PURE` 单色 / `GRADIENT` 双色 + 8 向），见 CONTEXT.md 词条 **Thing Background**。
- 颜色派生工具：`DisplayUtil.getLightColor/getDarkColor`、`BackgroundUtil.lighter/darker/blendColors`。
